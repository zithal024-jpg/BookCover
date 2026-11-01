package com.bookcover.diagnostic.testing

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Build
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.reader.BookSearchStatus
import com.bookcover.diagnostic.reader.EpubSession
import com.bookcover.diagnostic.reader.visibleReadingAnchor
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
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/** Real renderer, generated books, QA app only. Device-state overrides are NOT physical folding. */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderContinuityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var initialReadingMillis = 0L
    private var openedAt = 0L

    @Before fun prepareQa() {
        check(target.packageName.endsWith(".qa"))
        // Preserve other QA fixtures too; each test imports its own generated book.
        target.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).commit()
    }

    @After fun finishQa() {
        scenario?.close()
        shell("cmd device_state state reset")
        waitFor("physical screen restored") {
            val physical = Regex("identifier=(\\d+)").find(shell("cmd device_state base-state"))?.groupValues?.get(1)
            physical != null && shell("cmd device_state print-state").trim() == physical
        }
    }

    @Test fun tenFoldCyclesKeepOneReaderAndTheVisiblePassage() {
        val book = launchBook()
        val reader = reader(book)
        val activity = onActivity { it }
        val readyAt = SystemClock.elapsedRealtime()
        val transitions = java.util.Collections.synchronizedList(mutableListOf<Pair<Lifecycle.Event, Long>>())
        onActivity { it.lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME || event == Lifecycle.Event.ON_PAUSE)
                transitions += event to SystemClock.elapsedRealtime()
        }) }
        main {
            reader.changeFont(1.4)
            reader.changeTheme("Sepia")
            reader.changeEdgeTaps(true)
            reader.changeVolumeKeys(true)
        }
        waitFor("typography applied") { !main { reader.typographyUpdating } }
        main { reader.search.open(); reader.search.updateQuery("distinct marker number 60") }
        waitFor("search result") { main { reader.search.state.value.status } == BookSearchStatus.RESULTS }
        main { assertTrue(reader.goToSearchResult(reader.search.state.value.results.first())) }
        waitFor("search passage visible") { js(reader, "document.getElementById('paragraph-60').getBoundingClientRect().right>0") == "true" }
        SystemClock.sleep(400)
        val passage = main { reader.fragment.visibleReadingAnchor() } ?: error("No semantic passage")
        assertFalse(passage.text.highlight.isNullOrBlank())
        main { reader.controlsVisible = true }
        repeat(10) { cycle ->
            switchScreen()
            assertContinuous(activity, reader, passage, "fold $cycle")
            switchScreen()
            assertContinuous(activity, reader, passage, "unfold $cycle")
        }
        main {
            assertEquals(1.4, reader.fontScale, 0.0)
            assertTrue(reader.comfort.publisherStyles)
            assertEquals("Sepia", reader.themeName)
            assertTrue(reader.edgeTaps)
            assertTrue(reader.volumeKeys)
            assertTrue(reader.controlsVisible)
            assertNotNull(reader.bookmarkLocator())
        }
        // Search is part of the same open reader, including its current query/results.
        main { reader.search.open(); reader.search.updateQuery("distinct marker number 60") }
        waitFor("search ready") { main { reader.search.state.value.status } == BookSearchStatus.RESULTS }
        val search = main { reader.search.state.value }
        switchScreen(); switchScreen()
        assertEquals(search, main { reader.search.state.value })
        main { assertTrue(reader.goToSearchResult(reader.search.state.value.results.first())) }
        scenario!!.moveToState(Lifecycle.State.CREATED)
        val stoppedAt = SystemClock.elapsedRealtime()
        val library = onActivity { it.libraryForTests }
        fun credited() = library.readingDays.days.value.days.values.sumOf { it.millis } - initialReadingMillis
        var foreground = 0L
        var resumedAt: Long? = readyAt
        transitions.toList().filter { it.second >= readyAt }.forEach { (event, at) ->
            if (event == Lifecycle.Event.ON_PAUSE) { resumedAt?.let { foreground += at - it }; resumedAt = null }
            else if (resumedAt == null) resumedAt = at
        }
        resumedAt?.let { foreground += stoppedAt - it }
        waitFor("all active time retained across twenty transitions") { credited() >= foreground - 300 }
        assertTrue("One tracker must not credit duplicate sessions", credited() <= stoppedAt - openedAt + 300)
        val pausedTotal = credited()
        SystemClock.sleep(1_200)
        assertEquals("Background time is not active reading", pausedTotal, credited())
    }

    @Test fun backgroundReturnAndRecreationRestoreTheSemanticPassage() {
        val book = launchBook()
        var current = reader(book)
        js(current, "readium.scrollToId('paragraph-60'); true")
        SystemClock.sleep(500)
        val passage = main { current.fragment.visibleReadingAnchor() } ?: error("No passage")
        scenario!!.moveToState(Lifecycle.State.CREATED)
        SystemClock.sleep(800)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        assertSame(current, reader(book))
        waitFor("passage after background") { passageVisible(current, passage) }
        val snapshot = main { current.restorationLocator } ?: error("No recreation snapshot")
        assertFalse("Reflowable recreation keeps a selector", (snapshot.locations["cssSelector"] as? String).isNullOrBlank())
        assertNotNull("Reflowable recreation keeps a refined visible quote: ${snapshot.toJSON()}", snapshot.text.before)
        assertTrue("Cached recreation snapshot must describe visible content: ${snapshot.toJSON()}", passageVisible(current, snapshot))
        val previous = current
        scenario!!.recreate() // Unhandled configuration changes and process-style Bundle restoration still work.
        current = reader(book)
        assertNotSame(previous, current)
        waitFor("semantic passage restored after recreation; expected=${passage.toJSON()}; snapshot=${snapshot.toJSON()}") { passageVisible(current, passage) }
        // Back exits this reader. Returning to this book creates one fresh navigator.
        main { current.controlsVisible = false }
        onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("library after reader exit") { onActivity { it.showsLibraryForTests } }
        onActivity { it.openBookForTests(it.libraryForTests.book(book.id)!!) }
        val reopened = reader(book)
        assertNotSame(current, reopened)
        waitFor("passage after explicit exit/reopen") { passageVisible(reopened, passage) }
        assertEquals(1, onActivity { it.supportFragmentManager.fragments.count { fragment -> fragment is org.readium.r2.navigator.epub.EpubNavigatorFragment } })
    }

    /**
     * R2: system bars resize the reader without a configuration change. On the Fold the navigation
     * bar finished hiding just after a reopened book placed its saved passage, and Readium kept the
     * page index instead (33% then 0%). Large-screen emulators keep the reader size when the bars
     * hide, so this drives the same kind of resize by showing and hiding them. Emulators have not
     * reproduced the Fold's reset with this timing, so the Fold run of the test above stays decisive.
     */
    @Test fun systemBarResizeDuringReopenPlacementKeepsTheSavedPassage() {
        val book = launchBook()
        val current = reader(book)
        js(current, "readium.scrollToId('paragraph-60'); true")
        SystemClock.sleep(500)
        val passage = main { current.fragment.visibleReadingAnchor() } ?: error("No passage")
        fun showBars(visible: Boolean) = onActivity {
            val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
            if (visible) bars.show(WindowInsetsCompat.Type.systemBars()) else bars.hide(WindowInsetsCompat.Type.systemBars())
        }
        fun passageStays(session: EpubSession, transition: String) {
            // Readium reacts to a resize asynchronously, so require the settled page, not a first glimpse.
            waitFor("passage after $transition; expected=${passage.toJSON()}") { passageVisible(session, passage) }
            SystemClock.sleep(1_500)
            assertTrue("Passage still visible after $transition settled", passageVisible(session, passage))
        }
        // Leave through the app's own Back route (some emulators send the global Back action to the
        // taskbar). On reopen, resize the reader the moment the new navigator first reports its saved
        // position, as the Fold's bar-hide animation did.
        main { current.controlsVisible = false }
        onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("library after reader exit") { onActivity { it.showsLibraryForTests } }
        onActivity { it.openBookForTests(it.libraryForTests.book(book.id)!!) }
        waitFor("reopened navigator", 20_000) { onActivity { it.sessionForTests?.let { s -> s !== current && s.fragment.isAdded } } == true }
        val reopened = onActivity { it.sessionForTests!! }
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (runCatching { js(reopened, "Boolean(document.getElementById('paragraph-0'))") }.getOrNull() != "true") {
            check(SystemClock.uptimeMillis() < deadline) { "Reopened book never rendered" }
        }
        showBars(true)
        SystemClock.sleep(80)
        showBars(false)
        passageStays(reopened, "resize during reopen placement")
        assertEquals(1, onActivity { it.supportFragmentManager.fragments.count { fragment -> fragment is org.readium.r2.navigator.epub.EpubNavigatorFragment } })
    }

    /** A keyboard dismissal must keep the unique passage through the ordinary exit/reopen path. */
    @Test fun searchKeyboardDismissThenExitReopenKeepsVisiblePassage() {
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            val book = launchBook(searchFixture = true)
            val current = reader(book, searchFixture = true)
            js(current, "readium.scrollToOffset(window.innerWidth); true")
            SystemClock.sleep(500)
            val passage = main { current.fragment.visibleReadingAnchor() } ?: error("No unique passage")
            assertFalse(passage.text.highlight.isNullOrBlank())
            main { current.openSearch(); current.search.updateQuery(SearchEpubFixture.LATE_PHRASE) }
            waitFor("search results") { main { current.search.state.value.status } == BookSearchStatus.RESULTS }
            activateSearchTag("search-query")
            waitFor("actual keyboard shown") { automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
            activateSearchTag("search-close")
            waitFor("search and keyboard dismissed") {
                !main { current.search.state.value.visible } &&
                    automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            }
            waitFor("same unique passage after keyboard dismissal") { passageVisible(current, passage) }
            main { current.flushPosition(); current.controlsVisible = false }
            waitFor("exact passage saved before leaving") {
                onActivity { it.libraryForTests.book(book.id)?.locator }?.let {
                    JSONObject(it).optJSONObject("text")?.optString("highlight") == passage.text.highlight
                } == true
            }
            onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor("library after search and exit") { onActivity { it.showsLibraryForTests } }
            onActivity { it.openBookForTests(it.libraryForTests.book(book.id)!!) }
            val reopened = reader(book, searchFixture = true)
            assertNotSame(current, reopened)
            waitFor("same unique passage after search, exit and reopen") { passageVisible(reopened, passage) }
            SystemClock.sleep(750) // Check the settled page, not the first placement before a resize.
            assertTrue("Reopened passage remains visible after settling", passageVisible(reopened, passage))
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
        }
    }

    @Test fun rtlMangaKeepsItsResourceAndNativeNavigation() {
        val book = launchBook(manga = true)
        val current = reader(book, manga = true)
        assertEquals(ReadingProgression.RTL, main { current.fragment.settings.value.readingProgression })
        main { current.next() }
        waitFor("second image page") { main { current.fragment.currentLocator.value.href.toString().contains("chapter1") } }
        val passage = main { current.fragment.currentLocator.value }
        val activity = onActivity { it }
        repeat(2) { switchScreen(); assertContinuous(activity, current, passage, "RTL image") }
        main { current.previous() }
        waitFor("native previous page still works") { main { current.fragment.currentLocator.value.href.toString().contains("chapter0") } }
        main { current.controlsVisible = true }
        assertTrue(main { current.controlsVisible })
    }

    private fun assertContinuous(activity: MainActivity, current: EpubSession, passage: Locator, transition: String) {
        assertSame("Same activity after $transition", activity, onActivity { it })
        assertSame("No duplicate reading session after $transition", current, onActivity { it.sessionForTests })
        assertFalse(onActivity { it.showsLibraryForTests })
        assertEquals(1, onActivity { it.supportFragmentManager.fragments.count { fragment -> fragment is org.readium.r2.navigator.epub.EpubNavigatorFragment } })
        assertTrue(main { current.fragment.requireView().isAttachedToWindow })
        waitFor("saved passage visible after $transition") { passageVisible(current, passage) }
    }

    private fun launchBook(manga: Boolean = false, searchFixture: Boolean = false): BookRecord {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("library loaded") { !onActivity { it.busyForTests } }
        val folder = File(target.cacheDir, "continuity-${UUID.randomUUID()}").apply { mkdirs() }
        val fixture = if (searchFixture) SearchEpubFixture.write(folder).file else NativeEpubFixture.write(folder,
            fixed = false, progression = if (manga) "rtl" else null, language = if (manga) "ja" else "en", imagePages = manga)
        val library = onActivity { it.libraryForTests }
        val book = runBlocking { library.import(Uri.fromFile(fixture)).first }
        initialReadingMillis = library.readingDays.days.value.days.values.sumOf { it.millis }
        openedAt = SystemClock.elapsedRealtime()
        onActivity { it.openBookForTests(book) }
        return book
    }

    private fun reader(book: BookRecord, manga: Boolean = false, searchFixture: Boolean = false): EpubSession {
        waitFor("reader attached", 20_000) { onActivity { it.sessionForTests?.let { s -> s.book.id == book.id && s.fragment.isAdded } } == true }
        val current = onActivity { it.sessionForTests!! }
        waitFor("rendered generated book") {
            js(current, "Boolean(document.getElementById('${if (manga) "graphic" else if (searchFixture) "search-0-0" else "paragraph-0"}'))") == "true"
        }
        return current
    }

    private fun switchScreen() {
        val width = onActivity { it.resources.configuration.screenWidthDp }
        val other = if (shell("cmd device_state print-state").trim() == "0") "3" else "0"
        shell("cmd device_state state $other")
        waitFor("display width changed", 20_000) { onActivity { it.resources.configuration.screenWidthDp } != width }
        SystemClock.sleep(600) // Require the settled page, not a briefly visible pre-reflow viewport.
    }

    private fun passageVisible(current: EpubSession, passage: Locator): Boolean {
        if (main { current.fragment.currentLocator.value.href } != passage.href) return false
        val selector = passage.locations["cssSelector"] as? String ?: return true // Image/fixed pages use a resource locator.
        return js(current, """(function(){
            var e=document.querySelector(${JSONObject.quote(selector)}), quote=${JSONObject.quote(passage.text.highlight.orEmpty())};
            if(!e) return false;
            var walker=document.createTreeWalker(e,NodeFilter.SHOW_TEXT), n;
            while((n=walker.nextNode())) {
                var i=n.data.indexOf(quote); if(i<0) continue;
                var r=document.createRange(); r.setStart(n,i); r.setEnd(n,Math.min(n.length,i+1));
                return Array.from(r.getClientRects()).some(function(b){return b.width>0 && b.height>0 && b.right>1 && b.left<innerWidth-1 && b.bottom>1 && b.top<innerHeight-1;});
            } return false;
        })()""") == "true"
    }

    private fun <T> onActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        scenario!!.onActivity { result = runCatching { block(it) } }
        return result!!.getOrThrow()
    }

    private fun activateSearchTag(tag: String) {
        val automation = instrumentation.uiAutomation
        fun nodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
            yield(node)
            if (node.className?.toString() != "android.webkit.WebView")
                for (index in 0 until node.childCount) node.getChild(index)?.let { yieldAll(nodes(it)) }
        }
        waitFor("accessible $tag") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) automation.clearCache()
            val roots = (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
                .filter { it.packageName?.toString() == target.packageName }
            val tagged = roots.asSequence().flatMap(::nodes).firstOrNull {
                it.viewIdResourceName?.substringAfterLast('/') == tag && it.isVisibleToUser && it.isEnabled
            } ?: return@waitFor false
            val action = nodes(tagged).firstOrNull { node ->
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            } ?: return@waitFor false
            action.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }
    private fun <T> main(block: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { block() } }
    private fun js(current: EpubSession, script: String): String = main {
        withTimeout(5_000) { current.fragment.evaluateJavascript(script) } ?: error("No rendered viewport")
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
    private fun waitFor(description: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var failure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (error: Throwable) { failure = error }
            SystemClock.sleep(60)
        }
        throw AssertionError("Timed out: $description", failure)
    }
}
