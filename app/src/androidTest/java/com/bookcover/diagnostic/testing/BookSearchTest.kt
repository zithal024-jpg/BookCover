package com.bookcover.diagnostic.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.reader.BookSearchController
import com.bookcover.diagnostic.reader.BookSearchResult
import com.bookcover.diagnostic.reader.BookSearchStatus
import com.bookcover.diagnostic.reader.EpubSession
import com.bookcover.diagnostic.reader.visibleReadingAnchor
import java.util.zip.ZipFile
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
import org.readium.r2.shared.ExperimentalReadiumApi

/** BC-29: generated books only, in the separate QA app, using real Readium text locators. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalReadiumApi::class)
class BookSearchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val target get() = instrumentation.targetContext
    private var scenario: ActivityScenario<ReaderTestActivity>? = null
    private lateinit var host: ReaderTestActivity
    private lateinit var reader: EpubSession
    private var previousFlags = 0

    @Before fun prepare() {
        check(target.packageName.endsWith(".qa")) { "Search tests require the isolated QA app." }
        target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        val service = automation.serviceInfo
        previousFlags = service.flags
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service
    }

    @After fun cleanUp() {
        scenario?.close()
        ReaderTestActivity.fontScaleForTests = 0f
        target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
    }

    @Test fun lateChapterPhraseHasCorrectSnippetNavigatesAndMeetsMeasuredTarget() {
        launch()
        val initialBookmark = main { reader.bookmarkLocator() } ?: error("No initial bookmark target")
        main { host.library.bookmark(reader.book.id, initialBookmark, "Before searching", "Chapter 1") }
        val bookmarks = main { host.library.book(reader.book.id)!!.bookmarks }
        main { reader.openSearch() }

        // The publication is newly opened and has never been searched. Import/open, initial
        // pagination and the first rendered chapter are excluded. OS file caches are uncontrolled.
        // Timed from query delivery through observed complete results, including the 200 ms debounce.
        val cold = timedQuery(SearchEpubFixture.LATE_PHRASE)
        val result = results().single()
        assertTrue(result.locator.href.toString().endsWith("chapter${SearchEpubFixture.LATE_CHAPTER}.xhtml"))
        assertEquals(SearchEpubFixture.LATE_PHRASE, result.highlight.lowercase())
        assertEquals(SearchEpubFixture.chapterTitle(SearchEpubFixture.LATE_CHAPTER), result.chapter)
        assertTrue("Snippet includes useful text after the matching phrase", result.after.isNotBlank())
        assertTrue("Search supplies its actual Readium text locator", result.locator.text.highlight?.contains(SearchEpubFixture.LATE_PHRASE) == true)

        main { reader.search.updateQuery("") }
        val warm = timedQuery(SearchEpubFixture.LATE_PHRASE)
        val fixture = main { host.library.epubFile(reader.book) }
        val htmlBytes = ZipFile(fixture).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".xhtml") }.sumOf { it.size }
        }
        val record = "BC29_SEARCH_PERFORMANCE model=${Build.MODEL}; android=${Build.VERSION.RELEASE}; api=${Build.VERSION.SDK_INT}; " +
            "chapters=${SearchEpubFixture.CHAPTERS}; proseWords=150000; bodyWords=150120; pageEquivalent=500@300words; " +
            "htmlBytes=$htmlBytes; zipBytes=${fixture.length()}; coldMs=$cold; warmMs=$warm; " +
            "cold=new-publication-first-search; warm=same-publication-repeat; process=warm; OS-cache=uncontrolled; " +
            "includes=200ms-debounce-and-complete-book-results; excludes=fixture-generation-import-open-pagination; observationPollingMs=20"
        Log.i("BookSearchTest", record)
        println(record)

        activateTag("search-result-0")
        waitFor("choosing the search match closes search") { !main { reader.search.state.value.visible } }
        waitFor("Readium brings the actual late-chapter phrase into view") { phraseIsVisible() }
        waitFor("normal debounced saving stores the navigated chapter") {
            main { host.library.book(reader.book.id)?.locator?.let { JSONObject(it).optString("href").endsWith("chapter28.xhtml") } == true }
        }
        assertEquals("Search navigation preserves existing bookmarks", bookmarks, main { host.library.book(reader.book.id)!!.bookmarks })
        val destination = main { reader.bookmarkLocator() } ?: error("No destination bookmark target")
        assertTrue("The existing bookmark capture follows the navigated result", destination.href.toString().endsWith("chapter28.xhtml"))
        main { host.library.bookmark(reader.book.id, destination, "Search destination", reader.chapterStatus?.title) }
        assertEquals(2, main { host.library.book(reader.book.id)!!.bookmarks.size })
        assertTrue("500-page target missed on cold search: $record", cold < 3_000)
        assertTrue("500-page target missed on warm search: $record", warm < 3_000)
    }

    @Test fun rapidQueriesEmptyAndNoMatchNeverKeepEarlierResults() {
        launch()
        main {
            reader.openSearch()
            reader.search.updateQuery(SearchEpubFixture.LATE_PHRASE)
            reader.search.updateQuery("orchard")
            reader.search.updateQuery(SearchEpubFixture.EARLY_PHRASE.uppercase())
        }
        awaitStatus(BookSearchStatus.RESULTS)
        assertEquals(1, results().size)
        assertTrue(results().single().locator.href.toString().endsWith("chapter0.xhtml"))
        assertEquals(SearchEpubFixture.EARLY_PHRASE, results().single().highlight.lowercase())

        main { reader.search.updateQuery("words-absent-from-this-generated-river-survey") }
        assertTrue("Earlier results disappear as soon as the query changes", results().isEmpty())
        awaitStatus(BookSearchStatus.NO_RESULTS)
        waitFor("no-match state is presented") { nodeText(findTag("search-status")).contains("No matches found") }
        main { reader.search.updateQuery(" \t\n ") }
        awaitStatus(BookSearchStatus.EMPTY)
        assertTrue(results().isEmpty())
        waitFor("empty-query guidance is presented") { nodeText(findTag("search-status")).contains("Enter words or a phrase") }
        main { reader.search.updateQuery(SearchEpubFixture.EARLY_PHRASE) }
        awaitStatus(BookSearchStatus.RESULTS)
        assertEquals(1, results().size)
    }

    @Test fun dismissingSearchPreservesVisibleAndSavedLocationAndInvalidatesResults() {
        launch()
        main { reader.next() }
        waitFor("a normal page turn emits its advanced reading position") {
            js("window.scrollX").toDouble() > 10 &&
                main { (reader.fragment.currentLocator.value.locations.progression ?: 0.0) > 0.0 }
        }
        val (location, offset) = settledReadingLocation()
        val visibleBefore = main { reader.fragment.visibleReadingAnchor() } ?: error("No visible passage before search")
        val selector = visibleBefore.locations["cssSelector"] as? String ?: error("No visible block before search")
        Log.i("BookSearchTest", "BC29 before search: visible=${visibleBefore.toJSON()}; " +
            "restoration=${main { reader.restorationLocator }?.toJSON()}; " +
            "saved=${main { host.library.book(reader.book.id)?.locator }}")
        main { reader.flushPosition(); reader.openSearch() }
        timedQuery(SearchEpubFixture.LATE_PHRASE)
        val discarded = results().single()
        activateTag("search-query")
        waitFor("focusing the search field displays the actual soft keyboard") {
            automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        activateTag("search-close")
        waitFor("close dismisses search") { !main { reader.search.state.value.visible } }
        waitFor("closing search dismisses the soft keyboard") {
            automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        val dismissedLocation = settledReadingLocation()
        val original = JSONObject(location)
        val dismissed = JSONObject(dismissedLocation.first)
        assertEquals("Closing search stays in the same resource", original.getString("href"), dismissed.getString("href"))
        // Readium's raw fraction can retain the IME's column count. Verify the actual visible
        // page and unique quote, then require that exact quote in saved state instead.
        assertEquals("Closing search with the keyboard open leaves the visible page unchanged", offset, dismissedLocation.second, 1.0)
        val visibleAfter = main { reader.fragment.visibleReadingAnchor() } ?: error("No visible passage after search")
        assertEquals("Closing search keeps the visible block", visibleBefore.locations["cssSelector"], visibleAfter.locations["cssSelector"])
        assertEquals("Closing search keeps the visible quote", visibleBefore.text.highlight, visibleAfter.text.highlight)
        Log.i("BookSearchTest", "BC29 keyboard dismissal: raw progression=${original.getJSONObject("locations").getDouble("progression")}/${dismissed.getJSONObject("locations").getDouble("progression")}; exact offset=$offset/${dismissedLocation.second}; visible selector and quote match")
        assertFalse("A discarded result cannot navigate after dismissal", main { reader.goToSearchResult(discarded) })
        var savedPosition: String? = null
        try {
            waitFor("the original position saves normally") {
                savedPosition = main { host.library.book(reader.book.id)?.locator }
                savedPosition?.let { value ->
                    val saved = JSONObject(value)
                    saved.getString("href") == original.getString("href") &&
                        saved.getJSONObject("locations").optString("cssSelector") == selector &&
                        saved.getJSONObject("text").optString("highlight") == visibleBefore.text.highlight
                } == true
            }
        } catch (failure: AssertionError) {
            // Generated fixture only. Preserve exact semantic evidence rather than hiding a
            // persistence/repagination race behind a generic timeout or a wider tolerance.
            Log.e("BookSearchTest", "BC29 saved passage mismatch: before=${visibleBefore.toJSON()}; " +
                "after=${visibleAfter.toJSON()}; saved=$savedPosition; " +
                "restoration=${main { reader.restorationLocator }?.toJSON()}", failure)
            throw failure
        }
        main { reader.openSearch() }
        assertEquals(BookSearchStatus.EMPTY, main { reader.search.state.value.status })
        assertTrue(results().isEmpty())
        main { reader.dismissSearch(); reader.controlsVisible = false; reader.next() }
        waitFor("intentional navigation after search advances the visible page") {
            js("window.scrollX").toDouble() > dismissedLocation.second + 10
        }
        waitFor("the search hold cannot pin saving after intentional navigation") {
            main { host.library.book(reader.book.id)?.locator }?.let { value ->
                val saved = JSONObject(value)
                val nextSelector = saved.getJSONObject("locations").optString("cssSelector")
                val nextQuote = saved.getJSONObject("text").optString("highlight")
                saved.getString("href") == original.getString("href") &&
                    nextSelector.isNotBlank() && nextSelector != selector &&
                    nextQuote.isNotBlank() && nextQuote != visibleBefore.text.highlight
            } == true
        }
    }

    @Test fun bookChangeAndRecreationDiscardThePreviousSearch() {
        launch()
        val oldReader = reader
        val oldController: BookSearchController = reader.search
        main {
            reader.openSearch()
            reader.search.updateQuery(SearchEpubFixture.LATE_PHRASE)
        }
        scenario!!.close()
        scenario = null
        launch(searchFixture = false)
        assertNotEquals("The second generated EPUB is a different book", oldReader.book.id, reader.book.id)
        assertFalse(main { reader.search.state.value.visible })
        assertEquals(BookSearchStatus.EMPTY, main { reader.search.state.value.status })
        assertTrue(results().isEmpty())
        SystemClock.sleep(600)
        assertFalse("The old controller remains dismissed after any late completion", main { oldController.state.value.visible })
        assertTrue(main { oldController.state.value.results.isEmpty() })
        main { reader.openSearch(); reader.search.updateQuery("river") }
        awaitStatus(BookSearchStatus.RESULTS)
        scenario!!.recreate()
        scenario!!.onActivity { host = it }
        reader = main { withTimeout(60_000) { host.ready.await() } }
        assertFalse("The established reopen lifecycle starts with search dismissed", main { reader.search.state.value.visible })
        assertTrue(results().isEmpty())
    }

    @Test fun commonUnicodeCaseAndAccentsMatchAndLargeResultSetsAreCapped() {
        launch()
        main { reader.openSearch() }
        timedQuery("CAFE NAIVE FACADE ANGSTROM")
        assertEquals(1, results().size)
        assertEquals(SearchEpubFixture.UNICODE_PHRASE, results().single().highlight)
        timedQuery("καλημέρα")
        assertEquals(1, results().size)
        assertTrue(results().single().highlight.contains("Καλημέρα"))
        timedQuery("river")
        assertTrue("Common phrases have a finite rendered result list", results().size <= 200)
        assertTrue("The UI identifies truncated results", main { reader.search.state.value.capped })
    }

    @Test fun searchControlsExposeEditableLabeledActionsAtTwoHundredPercentText() {
        ReaderTestActivity.fontScaleForTests = 2f
        launch()
        main { reader.controlsVisible = true }
        scrollToTag("open-search")
        activateTag("open-search")
        waitFor("the search dialog is accessible") { findTag("search-dialog") != null }
        val query = findTag("search-query") ?: error("Missing accessible query field")
        assertTrue("Search field has an accessible label", nodeText(query).contains("Find words or a phrase"))
        assertNotNull("The field exposes Android's text editing action", descend(query) {
            it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        })
        setQuery(SearchEpubFixture.EARLY_PHRASE)
        awaitStatus(BookSearchStatus.RESULTS)
        scrollToTag("search-result-0", "search-results")
        waitFor("a search result remains accessible at large text") { findTag("search-result-0")?.let(::visible) == true }
        assertTrue("The result supplies chapter context for screen readers", nodeText(findTag("search-result-0")).contains("Chapter 1"))
        assertTouchSize("search-close")
        assertTouchSize("search-clear")
        activateTag("search-clear")
        awaitStatus(BookSearchStatus.EMPTY)
        waitFor("clear removes editable text") { main { reader.search.state.value.query.isEmpty() } }
        activateTag("search-close")
        waitFor("the close action remains usable") { !main { reader.search.state.value.visible } }
    }

    private fun launch(searchFixture: Boolean = true) {
        val intent = Intent(target, ReaderTestActivity::class.java)
            .putExtra(ReaderTestActivity.EXTRA_SEARCH_FIXTURE, searchFixture)
        scenario = ActivityScenario.launch<ReaderTestActivity>(intent).also { it.onActivity { activity -> host = activity } }
        reader = main { withTimeout(60_000) { host.ready.await() } }
        waitFor("the generated book's Readium navigator is attached", timeout = 60_000) {
            main { reader.fragment.isAdded && (reader.fragment.view?.width ?: 0) > 0 }
        }
        val marker = if (searchFixture) "search-0-0" else "paragraph-0"
        waitFor("the generated first chapter has rendered", timeout = 40_000) {
            js("Boolean(document.getElementById('$marker') && window.readium)") == "true"
        }
    }

    private fun timedQuery(query: String): Long {
        val start = SystemClock.elapsedRealtimeNanos()
        main { reader.search.updateQuery(query) }
        awaitStatus(BookSearchStatus.RESULTS)
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
    }

    private fun awaitStatus(status: BookSearchStatus) {
        waitFor("search reaches $status", timeout = 30_000, pollingMillis = 20) {
            val state = main { reader.search.state.value }
            if (state.status == BookSearchStatus.FAILED) error("Search failed: ${state.error}")
            state.status == status
        }
    }

    private fun results(): List<BookSearchResult> = main { reader.search.state.value.results }

    private fun phraseIsVisible(): Boolean = js("""(function(){
        var p=document.getElementById('search-28-40'); if(!p)return false;
        var walker=document.createTreeWalker(p,NodeFilter.SHOW_TEXT),n;
        while(n=walker.nextNode()){
            var i=n.textContent.indexOf(${JSONObject.quote(SearchEpubFixture.LATE_PHRASE)});
            if(i<0)continue;
            var range=document.createRange(); range.setStart(n,i); range.setEnd(n,i+${SearchEpubFixture.LATE_PHRASE.length});
            return Array.from(range.getClientRects()).some(function(r){return r.right>0&&r.left<innerWidth&&r.bottom>0&&r.top<innerHeight;});
        } return false;
    })()""") == "true"

    private fun setQuery(value: String) {
        val field = findTag("search-query") ?: error("Search field is absent")
        val editor = descend(field) { node -> node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
            ?: error("Search field does not expose a text edit action")
        assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        instrumentation.waitForIdleSync()
    }

    private fun activateTag(tag: String) {
        waitFor("$tag is visible and enabled") { findTag(tag)?.let { visible(it) && it.isEnabled } == true }
        val node = findTag(tag) ?: error("Missing $tag")
        val action = descend(node) { it.actionList.any { candidate -> candidate.id == AccessibilityNodeInfo.ACTION_CLICK } }
            ?: error("$tag does not expose an accessible click action")
        assertTrue("$tag can be activated through Android accessibility", action.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun settledReadingLocation(): Pair<String, Double> {
        var locator = main { reader.fragment.currentLocator.value.toJSON().toString() }
        var offset = js("window.scrollX").toDouble()
        var stableSince = SystemClock.uptimeMillis()
        waitFor("the emitted reading position and visible page both settle") {
            val currentLocator = main { reader.fragment.currentLocator.value.toJSON().toString() }
            val currentOffset = js("window.scrollX").toDouble()
            if (currentLocator != locator || kotlin.math.abs(currentOffset - offset) > 0.5) {
                stableSince = SystemClock.uptimeMillis()
            }
            locator = currentLocator
            offset = currentOffset
            SystemClock.uptimeMillis() - stableSince >= 750
        }
        return locator to offset
    }

    /** Reveal the requested control through the same accessibility scrolling actions as TalkBack. */
    private fun scrollToTag(tag: String, panelTag: String = "reader-controls-scroll") {
        var scanForward = true
        waitFor("accessibility scrolling reveals $tag") {
            val targetNode = findTag(tag)
            if (targetNode?.let(::visible) == true) return@waitFor true
            targetNode?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            instrumentation.waitForIdleSync()
            if (findTag(tag)?.let(::visible) == true) return@waitFor true
            findTag(panelTag)?.let { panel ->
                val panelBounds = Rect().also(panel::getBoundsInScreen)
                val targetBounds = findTag(tag)?.let { Rect().also(it::getBoundsInScreen) }
                val forward = if (targetBounds != null && !targetBounds.isEmpty)
                    targetBounds.exactCenterY() >= panelBounds.top else scanForward
                scanForward = forward
                val desired = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                val opposite = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                fun owner(action: Int) = descend(panel) { node ->
                    node.isScrollable && node.actionList.any { it.id == action }
                }
                owner(desired)?.performAction(desired) ?: owner(opposite)?.let { fallback ->
                    scanForward = opposite == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    fallback.performAction(opposite)
                }
            }
            SystemClock.sleep(120)
            false
        }
    }

    private fun visible(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val original = Rect().also(node::getBoundsInScreen)
        if (original.isEmpty) return false
        val clipped = Rect(original)
        val window = Rect().also { node.window?.getBoundsInScreen(it) }
        if (!window.isEmpty && !clipped.intersect(window)) return false
        var ancestor: AccessibilityNodeInfo? = node
        var depth = 0
        while (ancestor != null && depth++ < 128) {
            if (ancestor.viewIdResourceName?.substringAfterLast('/') in listOf("reader-controls-scroll", "search-results")) {
                val panel = Rect().also(ancestor::getBoundsInScreen)
                if (!clipped.intersect(panel)) return false
                break
            }
            ancestor = ancestor.parent
        }
        return original.exactCenterX() >= clipped.left && original.exactCenterX() < clipped.right &&
            original.exactCenterY() >= clipped.top && original.exactCenterY() < clipped.bottom
    }

    private fun assertTouchSize(tag: String) {
        val node = findTag(tag) ?: error("Missing $tag")
        val bounds = Rect().also(node::getBoundsInScreen)
        val minimum = main { 48f * host.resources.displayMetrics.density }
        assertTrue("$tag is at least 48 dp wide: $bounds", bounds.width() + 1 >= minimum)
        assertTrue("$tag is at least 48 dp high: $bounds", bounds.height() + 1 >= minimum)
    }

    private fun findTag(tag: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) automation.clearCache()
        val roots = (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
            .onEach { it.refresh() }.filter { it.packageName?.toString() == target.packageName }
        return roots.asSequence().mapNotNull { root -> descend(root) {
            it.viewIdResourceName == tag || it.viewIdResourceName?.endsWith("/$tag") == true
        } }.firstOrNull()
    }

    private fun descend(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        if (node.className?.toString() == "android.webkit.WebView") return null
        for (index in 0 until node.childCount) node.getChild(index)?.let { child -> descend(child, predicate)?.let { return it } }
        return null
    }

    private fun nodeText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val values = mutableListOf<String>()
        node.text?.toString()?.let(values::add)
        node.contentDescription?.toString()?.let(values::add)
        for (index in 0 until node.childCount) values += nodeText(node.getChild(index))
        return values.joinToString(" ")
    }

    private fun js(script: String): String = main {
        withTimeout(10_000) { reader.fragment.evaluateJavascript(script) } ?: error("No reflowable viewport")
    }

    private fun <T> main(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { action() } }

    private fun waitFor(description: String, timeout: Long = 20_000, pollingMillis: Long = 60, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (failure: Throwable) { lastFailure = failure }
            SystemClock.sleep(pollingMillis)
        }
        throw AssertionError("Timed out waiting for $description", lastFailure)
    }
}
