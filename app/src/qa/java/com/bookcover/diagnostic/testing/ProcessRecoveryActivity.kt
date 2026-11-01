package com.bookcover.diagnostic.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.reader.EpubSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/** QA-only: the real MainActivity lifecycle, private generated fixtures, and disk witnesses. */
@OptIn(ExperimentalReadiumApi::class)
class ProcessRecoveryActivity : MainActivity() {
    private val nonce: String get() = intent.getStringExtra(EXTRA_NONCE)
        ?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: error("Missing QA recovery nonce")
    private val root: File get() = File(super.getApplicationContext().noBackupFilesDir, "process-recovery-$nonce").apply { mkdirs() }
    private val isolatedApplication by lazy {
        val app = super.getApplicationContext()
        val folder = root
        object : ContextWrapper(app) {
            override fun getFilesDir(): File = folder
            override fun getApplicationContext(): Context = this
        }
    }
    private var openedAt = 0L
    private var libraryFlashed = false
    private var restoring = false

    override fun getApplicationContext(): Context = isolatedApplication
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        super.getSharedPreferences(if (name == "reader") "reader-process-recovery-$nonce" else name, mode)

    override fun onCreate(savedInstanceState: Bundle?) {
        check(packageName == "com.frankrodriguez.bookcover.qa")
        check(android.app.Application.getProcessName() == "$packageName:process_recovery")
        if (!webDataConfigured) {
            WebView.setDataDirectorySuffix("process-recovery")
            webDataConfigured = true
        }
        val savedNonce = savedInstanceState?.getString(SAVED_NONCE)
        val savedPid = savedInstanceState?.getInt(SAVED_PID)
        restoring = savedInstanceState != null
        if (!restoring) getSharedPreferences("reader", 0).edit()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).putInt("coverTimer", 0).commit()
        super.onCreate(savedInstanceState)
        if (restoring) window.decorView.viewTreeObserver.addOnPreDrawListener {
            if (showsLibraryForTests) libraryFlashed = true
            true
        }
        lifecycleScope.launch {
            try {
                if (restoring) {
                    check(savedNonce == nonce && savedPid != null && savedPid != Process.myPid()) { "Retained Bundle/PID witness missing" }
                    check(savedInstanceState?.getString("openBook") != null) { "Reader book missing from retained Bundle" }
                    check(savedInstanceState.getBoolean("coverRequested").not()) { "Stopped cover intent changed" }
                    verifyRestored(savedPid)
                } else {
                    // A fresh launch after killing the process must not silently prepare the book again.
                    check(!File(root, "expected.json").exists()) { "Android did not restore the retained task Bundle" }
                    prepare()
                }
            } catch (error: Exception) {
                write("failed.json", identity().put("error", "${error.javaClass.simpleName}: ${error.message}"))
            }
        }
    }

    private suspend fun prepare() {
        await { !busyForTests }
        val fixture = withContext(Dispatchers.IO) { NativeEpubFixture.write(root, fixed = false) }
        val book = libraryForTests.import(android.net.Uri.fromFile(fixture)).first
        openedAt = SystemClock.elapsedRealtime()
        openBookForTests(book)
        val reader = readyReader()
        reader.changeFontFamily("sans")
        reader.changeFont(1.4)
        reader.changeLineSpacing(2.0)
        reader.changePageMargins("wide")
        reader.changeTextAlignment("justified")
        reader.changeTheme("Sepia")
        reader.changeEdgeTaps(true)
        reader.changeVolumeKeys(true)
        await { !reader.typographyUpdating }
        js(reader, "readium.scrollToId('paragraph-60'); true")
        await {
            reader.restorationLocator?.let { it.text.before != null && passageVisible(reader, it) &&
                (reader.fragment.currentLocator.value.locations.totalProgression ?: 0.0) > 0.2 } == true
        }
        delay(300)
        val passage = reader.restorationLocator ?: error("No precise passage")
        check(passage.text.before != null && passageVisible(reader, passage))
        val bookmark = reader.bookmarkLocator() ?: error("No bookmark")
        libraryForTests.bookmark(book.id, bookmark, "Process recovery witness")
        reader.flushPosition()
        check(getSharedPreferences("reader", 0).edit().commit())
        delay(2_000) // A measurable, genuine resumed reading interval before backgrounding.
        write("expected.json", identity().put("bookId", book.id).put("passage", passage.toJSON())
            .put("bookmark", libraryForTests.book(book.id)!!.bookmarks.single())
            .put("openedAt", openedAt))
        write("prepared.json", identity())
    }

    private suspend fun verifyRestored(savedPid: Int) {
        val expected = read("expected.json")
        val control = read("resume-request.json")
        check(savedPid == control.getInt("oldPid"))
        val reader = readyReader()
        check(reader.book.id == expected.getString("bookId")) { "Wrong book restored" }
        val passage = Locator.fromJSON(expected.getJSONObject("passage")) ?: error("Invalid generated passage")
        await { passageVisible(reader, passage) }
        check(!libraryFlashed) { "Library flashed during process restoration" }
        check(supportFragmentManager.fragments.count { it is EpubNavigatorFragment } == 1) { "Duplicate navigator" }
        check(reader.fontScale == 1.4 && reader.fontId == "sans" && reader.themeName == "Sepia") { "Typography/theme changed" }
        check(!reader.comfort.publisherStyles && reader.comfort.lineSpacing == 2.0 &&
            reader.comfort.margins.id == "wide" && reader.comfort.alignment.id == "justified") { "Formatting changed" }
        check(reader.edgeTaps && reader.volumeKeys) { "Gesture/key settings changed" }
        check(libraryForTests.book(reader.book.id)!!.bookmarks == listOf(expected.getString("bookmark"))) { "Bookmark changed" }
        check(libraryForTests.books.value.size == 1) { "Library duplicated" }
        val preferences = getSharedPreferences("reader", 0)
        check(!preferences.getBoolean("autoCover", true) && preferences.getInt("coverTimer", -1) == 0) { "Cover settings changed" }
        val credited = withContext(Dispatchers.IO) { libraryForTests.readingDays.load().days.values.sumOf { it.millis } }
        val foregroundBound = SystemClock.elapsedRealtime() - control.getLong("resumeAt")
        check(credited >= control.getLong("readingMillis")) { "Accumulated reading time reset" }
        check(credited <= control.getLong("readingMillis") + foregroundBound + 300) { "Background/dead time or duplicate session credited" }
        // Controls and bookmark navigation must still work in the cold, restored reader.
        reader.controlsVisible = true
        reader.goToBookmark(expected.getString("bookmark"))
        await { passageVisible(reader, passage) }
        reader.controlsVisible = false
        write("restored.json", identity().put("savedPid", savedPid).put("bundleRestored", true)
            .put("readingMillis", credited).put("foregroundBound", foregroundBound)
            .put("passageVisible", true).put("navigatorCount", 1))
    }

    private suspend fun readyReader(): EpubSession {
        await { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !busyForTests && sessionForTests?.fragment?.isAdded == true }
        val reader = sessionForTests!!
        await { runCatching { js(reader, "Boolean(document.getElementById('paragraph-0'))") == "true" }.getOrDefault(false) }
        return reader
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(SAVED_NONCE, nonce)
        outState.putInt(SAVED_PID, Process.myPid())
        write("saved.json", identity().put("bookId", outState.getString("openBook"))
            .put("locator", outState.getString("openLocator")))
    }

    override fun onStop() {
        super.onStop()
        write("stopped.json", identity().put("stoppedAt", SystemClock.elapsedRealtime()))
    }

    override fun onDestroy() {
        write("destroyed-${Process.myPid()}.json", identity())
        super.onDestroy()
    }

    private fun identity() = JSONObject().put("nonce", nonce).put("pid", Process.myPid()).put("uid", Process.myUid())
        .put("process", android.app.Application.getProcessName()).put("taskId", taskId)

    private fun read(name: String): JSONObject = AtomicFile(File(root, name)).openRead().bufferedReader().use { JSONObject(it.readText()) }
    private fun write(name: String, value: JSONObject) {
        val file = AtomicFile(File(root, name))
        val output = file.startWrite()
        try { output.write(value.toString().toByteArray()); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    private suspend fun js(reader: EpubSession, script: String) =
        withTimeout(5_000) { reader.fragment.evaluateJavascript(script) } ?: error("No rendered viewport")

    private suspend fun passageVisible(reader: EpubSession, passage: Locator): Boolean {
        if (reader.fragment.currentLocator.value.href != passage.href) return false
        val selector = passage.locations["cssSelector"] as? String ?: return false
        return js(reader, """(function(){
            var e=document.querySelector(${JSONObject.quote(selector)}), q=${JSONObject.quote(passage.text.highlight.orEmpty())};
            if(!e || !q) return false;
            var w=document.createTreeWalker(e,NodeFilter.SHOW_TEXT), n;
            while((n=w.nextNode())) {var i=n.data.indexOf(q); if(i<0) continue;
                var r=document.createRange();r.setStart(n,i);r.setEnd(n,i+1);
                return Array.from(r.getClientRects()).some(function(b){return b.width>0&&b.height>0&&b.right>1&&b.left<innerWidth-1&&b.bottom>1&&b.top<innerHeight-1;});
            } return false;
        })()""") == "true"
    }

    private suspend fun await(predicate: suspend () -> Boolean) {
        withTimeout(25_000) { while (!predicate()) delay(50) }
    }

    companion object {
        private var webDataConfigured = false
        const val EXTRA_NONCE = "processRecoveryNonce"
        private const val SAVED_NONCE = "qaProcessRecoveryNonce"
        private const val SAVED_PID = "qaProcessRecoveryPid"
    }
}
