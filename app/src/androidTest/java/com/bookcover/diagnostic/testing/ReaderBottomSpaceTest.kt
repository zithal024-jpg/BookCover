package com.bookcover.diagnostic.testing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.reader.EpubSession
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The page uses the screen's height: below the last line there is only the progress row and one
 * comfortable margin, never a second reserved band. Real MainActivity in the QA app with a generated book;
 * screenshots go to the test's additional output (no personal books).
 */
@RunWith(AndroidJUnit4::class)
class ReaderBottomSpaceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val libraryFolder get() = File(target.filesDir, "library")
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun freshQaLibrary() {
        check(target.packageName.endsWith(".qa")) { "Layout tests may only clear the isolated QA app's own data." }
        libraryFolder.listFiles()?.forEach { it.deleteRecursively() }
        target.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).commit()
    }

    @After fun cleanUp() {
        scenario?.close()
        shell("cmd device_state state reset")
        SystemClock.sleep(2_000)
    }

    @Test fun pageFillsTheScreenWithOneBottomMargin() = pageFillsTheScreen()

    /** The other physical screen (folded / unfolded), emulated the same way as ReaderLifecycleTest. */
    @Test fun pageFillsTheOtherScreenToo() {
        shell("cmd device_state state ${if (shell("cmd device_state print-state").trim() == "0") "3" else "0"}")
        SystemClock.sleep(2_000)
        pageFillsTheScreen()
    }

    private fun pageFillsTheScreen() {
        val book = launchWithBook()
        onActivity { it.openBookForTests(book) }
        val reader = waitForReader(book)
        val screen = onActivity { it.resources.configuration.screenWidthDp }
        for ((styles, size) in listOf(true to 1.0, false to 1.6)) {
            onMain { if (reader.comfort.publisherStyles != styles) reader.changePublisherStyles(styles); reader.changeFont(size) }
            waitFor("text settings apply") { !onMain { reader.typographyUpdating } }
            js(reader, "readium.scrollToId('paragraph-30'); true")
            settle()
            val label = "w${screen}-${if (styles) "publisher" else "custom"}-${(size * 100).toInt()}"
            val hidden = measure(reader, "$label-full-hidden")
            onMain { reader.controlsVisible = true }
            settle()
            val shown = measure(reader, "$label-full-shown")
            onMain { reader.controlsVisible = false }
            settle()
            assertEquals("Showing the controls does not resize the page ($label)",
                hidden.getInt("pageBottom"), shown.getInt("pageBottom"))
            check(hidden, label)
        }
        // The end of a short chapter: its last line stays visible above the progress row.
        onMain { reader.fragment.go(reader.publication.readingOrder[1]) }
        waitFor("the second chapter opens") { js(reader, "document.body.innerText.indexOf('Linked chapter reached') >= 0") == "true" }
        settle()
        check(measure(reader, "w$screen-chapter-end-hidden"), "chapter end")
    }

    /** Pixel positions on screen: window, Readium's page box and its text, the progress row. */
    private fun measure(reader: EpubSession, label: String): JSONObject {
        val text = JSONObject(js(reader, """(function(){
            var w=innerWidth,h=innerHeight,last=0,cropped=0,lineHeight=0,walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),n,r=document.createRange();
            while((n=walker.nextNode())){ if(!n.textContent.trim()) continue; r.selectNodeContents(n);
              var rs=r.getClientRects(); for(var i=0;i<rs.length;i++){var q=rs[i]; if(q.width<=0||q.left<-1||q.right>w+1) continue;
                if(q.top<h&&q.bottom>h+1) cropped++; if(q.bottom<=h+1&&q.bottom>last){last=q.bottom; lineHeight=q.height;}}}
            return {cssHeight:h,lastLine:last,lineHeight:lineHeight,cropped:cropped,ratio:devicePixelRatio};})()"""))
        // The pager keeps neighbouring pages; measure the one that is on screen, once it has settled there.
        waitFor("the current page is on screen") { onActivity { a -> currentPage(reader, a.window.decorView) } != null }
        val view = onActivity { activity ->
            val decor = activity.window.decorView
            val web = currentPage(reader, decor)!!
            val page = web.parent as View
            val insets = WindowInsetsCompat.toWindowInsetsCompat(decor.rootWindowInsets, decor)
            val nav = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom
            val gestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
            JSONObject().put("label", label).put("density", activity.resources.displayMetrics.density)
                .put("window", decor.height).put("webTop", web.onScreenY()).put("webBottom", web.onScreenY() + web.height)
                .put("pageBottom", page.onScreenY() + page.height).put("readiumPadding", page.paddingBottom)
                .put("navBarIgnoringVisibility", nav).put("navBarNow", insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
                .put("gestureBottom", gestures).put("cutoutBottom", insets.getInsets(WindowInsetsCompat.Type.displayCutout()).bottom)
        }
        // After an emulated fold the accessibility tree can lag; then the row is placed from its 48dp minimum height.
        view.put("footerTop", nodeTop("reader-show-controls").takeIf { it > 0 }
            ?: (view.getInt("window") - 48 * view.getDouble("density")).toInt())
        val ratio = text.getDouble("ratio")
        val lastLine = view.getInt("webTop") + (text.getDouble("lastLine") * ratio).toInt()
        val result = view.put("lastLineBottom", lastLine).put("lineHeight", (text.getDouble("lineHeight") * ratio).toInt())
            .put("cropped", text.getInt("cropped")).put("textAreaBottom", view.getInt("webTop") + (text.getDouble("cssHeight") * ratio).toInt())
            .put("emptyBelowLastLine", view.getInt("window") - lastLine)
        Log.i("ReaderBottomSpace", result.toString())
        screenshot(label)
        return result
    }

    private fun check(m: JSONObject, label: String) {
        val dp = m.getDouble("density")
        assertEquals("No line is cut off at the bottom ($label): $m", 0, m.getInt("cropped"))
        assertTrue("The last line ends above the progress row ($label): $m",
            m.getInt("lastLineBottom") <= m.getInt("footerTop"))
        assertTrue("Text stays clear of the navigation bar when it is swiped in ($label): $m",
            m.getInt("textAreaBottom") <= m.getInt("window") - m.getInt("navBarIgnoringVisibility"))
        // Below the text area: the progress row plus at most a 24dp margin, not a second reserved band.
        val belowTextArea = m.getInt("window") - m.getInt("textAreaBottom")
        assertTrue("Only the progress row and one margin lie below the page ($label): ${belowTextArea / dp}dp; $m",
            belowTextArea <= (72 * dp).toInt())
    }

    private fun screenshot(label: String) {
        val folder = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(target.getExternalFilesDir(null), "bottom-space")
        runCatching {
            folder.mkdirs()
            val image: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
            File(folder, "$label.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Log.i("ReaderBottomSpace", "screenshot ${File(folder, "$label.png")}")
        }.onFailure { Log.w("ReaderBottomSpace", "screenshot failed for $label: $it") }
    }

    private fun currentPage(reader: EpubSession, decor: View): WebView? =
        findWebViews(reader.fragment.requireView()).firstOrNull { it.isShown && it.onScreenX() in 0 until decor.width }

    private fun findWebViews(view: View): List<WebView> = when (view) {
        is WebView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { findWebViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    /** Top of a Compose element on screen, from its test tag in the accessibility tree; -1 when absent. */
    private fun nodeTop(tag: String): Int {
        fun find(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.viewIdResourceName?.substringAfterLast('/') == tag) return node
            return (0 until node.childCount).firstNotNullOfOrNull { find(node.getChild(it)) }
        }
        val automation = instrumentation.uiAutomation
        val roots = automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)
        val node = roots.filter { it.packageName?.toString() == target.packageName }.firstNotNullOfOrNull { find(it) } ?: return -1
        return android.graphics.Rect().also { node.getBoundsInScreen(it) }.top
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun View.onScreenX() = IntArray(2).also { getLocationOnScreen(it) }[0]
    private fun View.onScreenY() = IntArray(2).also { getLocationOnScreen(it) }[1]

    private fun settle() = SystemClock.sleep(1_200)

    private fun launchWithBook(): BookRecord {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("the library loads") { !onActivity { it.busyForTests } }
        val fixture = NativeEpubFixture.write(File(target.cacheDir, "bottom-${UUID.randomUUID()}").apply { mkdirs() }, false)
        val library = onActivity { it.libraryForTests }
        return runBlocking { library.import(Uri.fromFile(fixture)).first }
    }

    private fun waitForReader(book: BookRecord): EpubSession {
        waitFor("the book is open in the reader", timeout = 20_000) {
            onActivity { activity -> activity.sessionForTests?.let { it.book.id == book.id && it.fragment.isAdded } } == true
        }
        val reader = onActivity { it.sessionForTests!! }
        waitFor("the chapter renders") {
            runCatching { js(reader, "Boolean(window.readium && document.getElementById('paragraph-0'))") == "true" }.getOrDefault(false)
        }
        return reader
    }

    private fun <T> onActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        scenario!!.onActivity { result = runCatching { block(it) } }
        return result!!.getOrThrow()
    }

    private fun <T> onMain(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { action() } }

    private fun js(reader: EpubSession, script: String): String = onMain {
        withTimeout(10_000) { reader.fragment.evaluateJavascript(script) } ?: error("No reflowable viewport")
    }

    private fun waitFor(description: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (failure: Throwable) { lastFailure = failure }
            SystemClock.sleep(60)
        }
        throw AssertionError("Timed out waiting for $description", lastFailure)
    }
}
