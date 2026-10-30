package com.bookcover.diagnostic.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.library.SavedBookmarks
import com.bookcover.diagnostic.reader.EpubSession
import com.bookcover.diagnostic.reader.PACE_PREFERENCES
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi

/** Generated EPUBs and the isolated QA host only. No personal books or screenshots are used. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalReadiumApi::class)
class ReadingNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val target get() = instrumentation.targetContext
    private var scenario: ActivityScenario<ReaderTestActivity>? = null
    private lateinit var host: ReaderTestActivity
    private lateinit var reader: EpubSession
    private var previousAccessibilityFlags = 0

    @Before fun prepareIsolatedReader() {
        check(target.packageName.endsWith(".qa")) { "Navigation tests must use the isolated QA app." }
        target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear()
            .putBoolean("publisherStyles", false).commit()
        // A pace remembered by earlier runs would make the "no invented estimate" check meaningless.
        target.getSharedPreferences(PACE_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        val service = automation.serviceInfo
        previousAccessibilityFlags = service.flags
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service
    }

    @After fun cleanUp() {
        scenario?.close()
        target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        automation.serviceInfo = automation.serviceInfo.apply { flags = previousAccessibilityFlags }
    }

    @Test fun nestedLtrContentsIdentifyAndRevealTheCurrentChapter() {
        launch()
        checkCurrentContentsSelection(ReadingProgression.LTR)
    }

    @Test fun nestedRtlContentsIdentifyAndRevealTheCurrentChapter() {
        launch(rtl = true)
        checkCurrentContentsSelection(ReadingProgression.RTL)
    }

    @Test fun chapterProgressGrowsSurvivesReflowAndIdentifiesTheFinalChapter() {
        launch(detailedContents = false)
        val initial = main { reader.chapterStatus!! }
        assertEquals(0, initial.currentTocIndex)
        val initialProgress = initial.progression ?: error("The long chapter has no progress")
        main { reader.next() }
        waitFor("a native page turn advances the chapter progress") {
            main { reader.chapterStatus?.progression?.let { it.isFinite() && it > initialProgress } == true }
        }
        val paragraph = firstVisibleParagraph()
        main { reader.changeFont(1.6) }
        waitFor("chapter progress resolves again after real font reflow") {
            main { !reader.typographyUpdating && reader.chapterStatus?.currentTocIndex == 0 &&
                reader.chapterStatus?.progression?.let { it.isFinite() && it in 0.0..1.0 } == true }
        }
        assertEquals("Reflow keeps the current chapter title", initial.title, main { reader.chapterStatus?.title })
        assertTrue("Reflow retains the nearby reading paragraph", abs(firstVisibleParagraph() - paragraph) <= 2)

        main { reader.goToContents(1) }
        waitFor("the final contents chapter is selected and rendered") {
            main { reader.chapterStatus?.currentTocIndex == 1 &&
                reader.fragment.currentLocator.value.href.toString().endsWith("chapter1.xhtml") } &&
                js("Boolean(document.getElementById('linked'))") == "true"
        }
        assertEquals("The linked chapter", main { reader.chapterStatus?.title })
        val finalProgress = main { reader.chapterStatus?.progression }
        assertTrue("The final chapter also has bounded progress", finalProgress != null && finalProgress.isFinite() && finalProgress in 0.0..1.0)
    }

    @Test fun fragmentContentsResolveAfterEnteringAnotherResource() {
        launch(rtl = true)
        moveToSection(61)
        main { reader.goToContents(91) }
        waitFor("the new resource's fragment is measured and selected") {
            main { reader.chapterStatus?.currentTocIndex == 91 && reader.fragment.currentLocator.value.href.toString().endsWith("chapter1.xhtml") }
        }
        assertEquals("The linked chapter", main { reader.chapterStatus!!.title })
        moveToSection(61)
        assertEquals("Returning to the previous resource keeps the selected subsection", "Section 61", main { reader.chapterStatus!!.title })
    }

    @Test fun bookmarkNamesSaveDeduplicateRenameAndRestoreTheirReadingTarget() {
        launch()
        moveToSection(61)
        val originalParagraph = firstVisibleParagraph()
        main { reader.controlsVisible = true }
        saveBookmarkThroughDialog("Harbor turning")
        waitFor("a named bookmark is persisted") {
            bookmarks().size == 1 && SavedBookmarks.name(bookmarks().single()) == "Harbor turning"
        }
        val original = bookmarks().single()
        assertEquals("The named bookmark retains chapter context", "Section 61", SavedBookmarks.chapterTitle(original))

        // Opening the dialog again at the same rendered position must update, not duplicate, it.
        saveBookmarkThroughDialog("Harbor turning again")
        waitFor("saving this same position updates its name without adding a duplicate") {
            bookmarks().size == 1 && SavedBookmarks.name(bookmarks().single()) == "Harbor turning again"
        }
        assertTrue("Changing a name must not change its navigation target", SavedBookmarks.sameTarget(original, bookmarks().single()))

        clickTag("open-contents")
        scrollToTag("rename-bookmark-0")
        clickTag("rename-bookmark-0")
        setBookmarkName("Harbor revised")
        clickTag("bookmark-save")
        waitFor("renaming a bookmark updates the isolated library") {
            bookmarks().size == 1 && SavedBookmarks.name(bookmarks().single()) == "Harbor revised"
        }
        main { host.library.load() }
        val renamed = bookmarks().single()
        assertEquals("The edited name survives reloading the library index", "Harbor revised", SavedBookmarks.name(renamed))
        assertEquals("Renaming retains chapter context", "Section 61", SavedBookmarks.chapterTitle(renamed))
        assertTrue("Renaming preserves the original target", SavedBookmarks.sameTarget(original, renamed))

        moveToSection(6)
        main { reader.goToBookmark(renamed) }
        waitFor("opening the saved bookmark restores the nearby semantic position") {
            main { reader.fragment.currentLocator.value.href.toString().endsWith("chapter0.xhtml") } &&
                abs(firstVisibleParagraph() - originalParagraph) <= 2
        }
        assertTrue("The saved location is well beyond the temporary earlier chapter", firstVisibleParagraph() > 40)

        // Reveal the lower Remove button itself, not merely a sliver of its taller bookmark row.
        try {
            scrollToTag("remove-bookmark-0")
            clickTag("remove-bookmark-0")
            waitFor("the bookmark is removed") { bookmarks().isEmpty() }
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}; removal=${accessibilityDiagnostics("remove-bookmark-0", "bookmark-item-0", "rename-bookmark-0")}", failure)
        }
        main { host.library.load() }
        assertTrue("Removal persists after reopening the index", bookmarks().isEmpty())
    }

    private fun launch(rtl: Boolean = false, detailedContents: Boolean = true) {
        val intent = Intent(target, ReaderTestActivity::class.java)
            .putExtra(ReaderTestActivity.EXTRA_DETAILED_CONTENTS, detailedContents)
        if (rtl) intent.putExtra(ReaderTestActivity.EXTRA_READING_PROGRESSION, "rtl")
            .putExtra(ReaderTestActivity.EXTRA_LANGUAGE, "ar")
        scenario = ActivityScenario.launch<ReaderTestActivity>(intent).also {
            it.onActivity { activity -> host = activity }
        }
        reader = main { withTimeout(20_000) { host.ready.await() } }
        waitFor("the generated reader is attached and rendered") {
            main { reader.fragment.isAdded && (reader.fragment.view?.width ?: 0) > 0 } &&
                js("Boolean(document.getElementById('paragraph-0') && window.readium)") == "true"
        }
        waitFor("the chapter model resolves the generated TOC") { main { reader.chapterStatus != null } }
        assertEquals("The generated fixture provides its expected contents", if (detailedContents) 92 else 2, main { reader.contentsEntries.size })
        if (detailedContents) assertEquals("The fixture really contains nested contents", 1, main { reader.contentsEntries[61].depth })
    }

    private fun checkCurrentContentsSelection(expectedDirection: ReadingProgression) {
        assertEquals(expectedDirection, main { reader.fragment.settings.value.readingProgression })
        moveToSection(61)
        val status = main { reader.chapterStatus!! }
        assertEquals("Section 61", status.title)
        assertNotNull("Actual chapter progress is available", status.progression)
        val progress = status.progression
        assertTrue("Chapter progress is finite and bounded", progress != null && progress.isFinite() && progress in 0.0..1.0)
        assertNull("Rapid automated jumps must not invent a reading-time estimate", status.remainingMinutes)
        main { reader.controlsVisible = true }
        clickTag("open-contents")
        // Do not issue a scroll action: opening Contents itself must reveal the selected item.
        try {
            waitFor("Contents automatically reveals and selects the current nested chapter") {
                val current = findTag("contents-entry-61") ?: return@waitFor false
                isCurrentContentsRow(current) && actuallyVisible(current)
            }
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}; ${accessibilityDiagnostics("contents-entry-61", "rename-bookmark-0")}", failure)
        }
        val selected = findTag("contents-entry-61")!!
        assertTrue("Current contents item exposes selection or its explicit current-chapter marker", isCurrentContentsRow(selected))
        assertTrue("Current contents item has real visible screen bounds", actuallyVisible(selected))
    }

    private fun moveToSection(index: Int) {
        main { reader.goToContents(index) }
        try {
            waitFor("chapter navigation resolves nested TOC index $index") {
                main { reader.chapterStatus?.currentTocIndex == index && !reader.typographyUpdating }
            }
            // The locator and chapter resolution are asynchronous; wait for a stable viewport endpoint.
            var previous = js("window.scrollX").toDouble()
            var stableSince = SystemClock.uptimeMillis()
            waitFor("the native chapter navigation settles") {
                val current = js("window.scrollX").toDouble()
                if (abs(current - previous) > 0.5) stableSince = SystemClock.uptimeMillis()
                previous = current
                SystemClock.uptimeMillis() - stableSince >= 240
            }
        } catch (failure: AssertionError) {
            // Only this generated-fixture test records geometry. Production never logs book content.
            val native = main {
                val locator = reader.fragment.currentLocator.value
                "status=${reader.chapterStatus}; href=${locator.href}; progression=${locator.locations.progression}; " +
                    "totalProgression=${locator.locations.totalProgression}; position=${locator.locations.position}; " +
                    "nativeViewWidth=${reader.fragment.view?.width}"
            }
            val geometry = runCatching {
                js("""(function(){
                    function rect(r){return r ? {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height} : null;}
                    function anchor(id){var e=document.getElementById(id);return e ? {bounds:rect(e.getBoundingClientRect()),firstRect:rect(e.getClientRects()[0])} : null;}
                    return {scrollX:window.scrollX,innerWidth:innerWidth,devicePixelRatio:devicePixelRatio,
                        scrollingWidth:document.scrollingElement ? document.scrollingElement.scrollWidth : null,
                        documentWidth:document.documentElement.scrollWidth,bodyWidth:document.body.scrollWidth,
                        rootDirection:getComputedStyle(document.documentElement).direction,baseURI:document.baseURI,
                        paragraph60:anchor('paragraph-60'),paragraph59:anchor('paragraph-59')};
                })()""")
            }.getOrElse { "Geometry query failed: ${it.javaClass.simpleName}: ${it.message}" }
            throw AssertionError("${failure.message}; $native; generatedGeometry=$geometry", failure)
        }
    }

    private fun saveBookmarkThroughDialog(name: String) {
        clickTag("add-bookmark")
        setBookmarkName(name)
        clickTag("bookmark-save")
        waitFor("the bookmark name dialog closes") { findTag("bookmark-dialog") == null }
    }

    private fun setBookmarkName(name: String) {
        waitFor("bookmark name field is visible") { findTag("bookmark-name")?.isVisibleToUser == true }
        val field = findTag("bookmark-name")!!
        val editor = descend(field) { node -> node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
            ?: error("Bookmark field does not expose its editable accessibility action")
        assertTrue("The bookmark name can be edited through Android accessibility",
            editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
            }))
        instrumentation.waitForIdleSync()
    }

    private fun clickTag(tag: String) {
        var previousBounds: Rect? = null
        var stableSince = SystemClock.uptimeMillis()
        waitFor("$tag has stable usable touch bounds") {
            val bounds = findTag(tag)?.let(::visibleBounds)
            if (bounds == null) {
                previousBounds = null
                stableSince = SystemClock.uptimeMillis()
                return@waitFor false
            }
            if (bounds != previousBounds) stableSince = SystemClock.uptimeMillis()
            previousBounds = Rect(bounds)
            SystemClock.uptimeMillis() - stableSince >= 240
        }
        // Look up the actual tagged owner again after the list/fling settles; accessibility is
        // used only for locating, and the action still follows a real pointer DOWN/UP path.
        tapNode(findTag(tag)!!)
    }

    /** Accessibility locates/asserts controls; actual navigation follows the user's pointer path. */
    private fun tapNode(node: AccessibilityNodeInfo) {
        val bounds = visibleBounds(node) ?: throw AssertionError("The tagged control has no usable visible touch bounds")
        val down = SystemClock.uptimeMillis()
        sendPointer(down, down, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY())
        SystemClock.sleep(40)
        sendPointer(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY())
        instrumentation.waitForIdleSync()
    }

    /** Explicit scrolling is used only to reach bookmark actions, never for the TOC auto-scroll check. */
    private fun scrollToTag(tag: String) = scrollToControl(tag) { findTag(tag) }

    private fun scrollToControl(label: String, findControl: () -> AccessibilityNodeInfo?) {
        try {
            waitFor("scrolling reveals a usable $label control") {
                if (findControl()?.let(::actuallyVisible) == true) return@waitFor true
                // The native EPUB ViewPager can also report itself scrollable; target the
                // revealed Compose controls explicitly instead of scrolling the underlying book.
                (findTag("reader-contents-scroll") ?: findTag("reader-controls-scroll"))?.let { controls ->
                    visibleBounds(controls)?.let(::swipePanelUp)
                }
                SystemClock.sleep(120)
                false
            }
        } catch (failure: AssertionError) {
            throw AssertionError("${failure.message}; ${accessibilityDiagnostics(label, "contents-entry-61")}", failure)
        }
    }

    private fun swipePanelUp(bounds: Rect) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val x = bounds.exactCenterX()
        val startY = bounds.top + bounds.height() * 0.80f
        val endY = bounds.top + bounds.height() * 0.20f
        val down = SystemClock.uptimeMillis()
        sendPointer(down, down, MotionEvent.ACTION_DOWN, x, startY)
        for (step in 1..12) {
            SystemClock.sleep(20)
            sendPointer(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x,
                startY + (endY - startY) * step / 12)
        }
        sendPointer(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, endY)
        instrumentation.waitForIdleSync()
    }

    private fun sendPointer(down: Long, time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
    }

    /** Diagnostic contains only IDs, geometry and actions from this generated-fixture QA host. */
    private fun accessibilityDiagnostics(vararg tags: String): String {
        fun describe(node: AccessibilityNodeInfo?): String {
            if (node == null) return "absent"
            val bounds = Rect().also(node::getBoundsInScreen)
            val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) node.stateDescription else null
            return "id=${node.viewIdResourceName},class=${node.className},clickable=${node.isClickable},selected=${node.isSelected}," +
                "checkable=${node.isCheckable},checked=${node.isChecked},state=$state,description=${node.contentDescription}," +
                "visible=${node.isVisibleToUser},bounds=$bounds,scrollable=${node.isScrollable}," +
                "actions=${node.actionList.map { it.id }}"
        }
        val requested = (tags.toList() + listOf("reader-contents-scroll", "reader-controls-scroll")).distinct()
            .joinToString("; ") { "$it={${describe(findTag(it))}}" }
        val remove = describe(findNode { it.contentDescription?.toString()?.startsWith("Remove bookmark") == true })
        val scrollables = mutableListOf<String>()
        val visibleControlText = linkedSetOf<String>()
        fun inspect(node: AccessibilityNodeInfo) {
            if (node.isScrollable && scrollables.size < 6) scrollables += describe(node)
            if (node.className?.toString() == "android.webkit.WebView") return
            if (node.isVisibleToUser && visibleControlText.size < 24) {
                node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.take(100)?.let(visibleControlText::add)
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::inspect)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) automation.clearCache()
        (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
            .onEach { it.refresh() }
            .filter { it.packageName?.toString() == target.packageName }.forEach(::inspect)
        return "status=${main { reader.chapterStatus }}; $requested; removeControl={$remove}; scrollables=$scrollables; visibleControls=$visibleControlText"
    }

    private fun findTag(tag: String): AccessibilityNodeInfo? = findNode {
        it.viewIdResourceName == tag || it.viewIdResourceName?.endsWith("/$tag") == true
    }

    private fun isCurrentContentsRow(node: AccessibilityNodeInfo): Boolean {
        if (node.isSelected || (node.isCheckable && node.isChecked)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val state = node.stateDescription?.toString().orEmpty()
            if (state.contains("selected", ignoreCase = true) &&
                !state.contains("not selected", ignoreCase = true) && !state.contains("unselected", ignoreCase = true)) return true
        }
        // Compose can expose Selected on a button differently across Android versions. Its
        // explicit marker is scoped to this exact row, not a different selected entry in the list.
        return descend(node) { child ->
            child.viewIdResourceName == "current-chapter-marker" ||
                child.viewIdResourceName?.endsWith("/current-chapter-marker") == true ||
                child.text?.toString()?.contains("Current chapter") == true
        } != null
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        // Compose can replace a subtree without changing its Android window. Do not keep using
        // UiAutomation's pre-click semantics snapshot after opening Contents in that same window.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) automation.clearCache()
        val roots = (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
            .onEach { it.refresh() }
        return roots.asSequence().filter { it.packageName?.toString() == target.packageName }
            .mapNotNull { descend(it, predicate) }.firstOrNull()
    }

    private fun descend(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        // No need to enumerate the generated book's page text to find Compose reader controls.
        if (node.className?.toString() == "android.webkit.WebView") return null
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            descend(child, predicate)?.let { return it }
        }
        return null
    }

    private fun actuallyVisible(node: AccessibilityNodeInfo): Boolean {
        return visibleBounds(node) != null
    }

    private fun visibleBounds(node: AccessibilityNodeInfo): Rect? {
        if (!node.isVisibleToUser) return null
        val original = Rect().also(node::getBoundsInScreen)
        if (original.width() <= 0 || original.height() <= 0) return null
        val clipped = Rect(original)
        val window = Rect().also { node.window?.getBoundsInScreen(it) }.takeUnless { it.isEmpty }
            ?: main { Rect().also { host.window.decorView.getWindowVisibleDisplayFrame(it) } }
        if (!clipped.intersect(window)) return null
        // Accessibility may report a partially clipped child as visible. Intersect the actual
        // Compose list/panel (or dialog surface) before choosing any pointer coordinates.
        var ancestor: AccessibilityNodeInfo? = node
        var depth = 0
        while (ancestor != null && depth++ < 128) {
            val id = ancestor.viewIdResourceName?.substringAfterLast('/')
            if (id in listOf("reader-contents-scroll", "reader-controls-scroll", "bookmark-dialog")) {
                val panel = Rect().also(ancestor::getBoundsInScreen)
                if (!clipped.intersect(panel)) return null
                break
            }
            ancestor = ancestor.parent
        }
        val centerVisible = original.exactCenterX() >= clipped.left && original.exactCenterX() < clipped.right &&
            original.exactCenterY() >= clipped.top && original.exactCenterY() < clipped.bottom
        val usablePixels = main { 32f * host.resources.displayMetrics.density }
        return clipped.takeIf { centerVisible && it.width() >= minOf(original.width().toFloat(), usablePixels) &&
            it.height() >= minOf(original.height().toFloat(), usablePixels) }
    }

    private fun bookmarks(): List<String> = main { host.library.book(reader.book.id)?.bookmarks.orEmpty() }

    private fun firstVisibleParagraph(): Int {
        val locator = main { reader.fragment.firstVisibleElementLocator() } ?: error("No visible generated paragraph")
        return Regex("Paragraph (\\d+)").find(locator.text.highlight.orEmpty())?.groupValues?.get(1)?.toInt()
            ?: error("The generated fixture did not provide a visible paragraph marker")
    }

    private fun js(script: String): String = main {
        withTimeout(10_000) { reader.fragment.evaluateJavascript(script) } ?: error("No reflowable viewport")
    }

    private fun <T> main(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { action() } }

    private fun waitFor(description: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (failure: Throwable) { lastFailure = failure }
            SystemClock.sleep(60)
        }
        throw AssertionError("Timed out waiting for $description", lastFailure)
    }
}
