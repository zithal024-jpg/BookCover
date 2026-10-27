package com.bookcover.diagnostic.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.library.SavedBookmarks
import com.bookcover.diagnostic.reader.EpubSession
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
import kotlin.math.abs

/** Real Readium key dispatch and Android accessibility actions, using only generated QA books. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalReadiumApi::class)
class ReadingKeysAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val target get() = instrumentation.targetContext
    private var scenario: ActivityScenario<ReaderTestActivity>? = null
    private lateinit var host: ReaderTestActivity
    private lateinit var reader: EpubSession
    private var previousFlags = 0

    @Before fun prepare() {
        check(target.packageName.endsWith(".qa")) { "Reader input tests require the isolated QA app." }
        target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear()
            .putBoolean("publisherStyles", false).commit()
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

    @Test fun keyboardAndOptionalVolumeTurnsUseWholePressesAndPersistTheSetting() {
        launch()
        assertFalse("Volume keys remain normal until enabled", main { reader.volumeKeys })
        val start = settledOffset()
        // Inspect pass-through without sending an Android volume command to the real phone.
        assertFalse(main { reader.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)) })
        assertFalse(main { reader.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN)) })
        assertEquals(start, offset(), 1.0)

        press(KeyEvent.KEYCODE_PAGE_DOWN)
        waitFor("Page Down advances the generated book") { offset() > start + 50 }
        val afterKeyboard = settledOffset()
        val width = js("window.innerWidth").toDouble()
        assertEquals("Page Down turns one page", width, afterKeyboard - start, 2.0)
        main { reader.changeVolumeKeys(true) }

        val downTime = SystemClock.uptimeMillis()
        assertTrue(dispatch(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0)))
        waitFor("enabled volume down advances") { offset() > afterKeyboard + 50 }
        val afterVolume = settledOffset()
        assertTrue(dispatch(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 1)))
        assertTrue(dispatch(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 2)))
        assertTrue(dispatch(KeyEvent(downTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_DOWN, 0)))
        SystemClock.sleep(350)
        assertEquals("A held volume key turns once", width, afterVolume - afterKeyboard, 2.0)
        assertEquals("Repeat and release do not add another page", afterVolume, offset(), 1.0)
        press(KeyEvent.KEYCODE_VOLUME_UP)
        waitFor("volume up returns to the preceding page") { offset() < afterVolume - 50 }
        assertEquals(afterKeyboard, settledOffset(), 2.0)

        scenario!!.close(); scenario = null
        launch()
        assertTrue("The volume-key preference survives opening a new session", main { reader.volumeKeys })
        val reopened = settledOffset()
        press(KeyEvent.KEYCODE_VOLUME_DOWN)
        waitFor("saved volume-key behavior works in the new reader") { offset() > reopened + 50 }
    }

    @Test fun rtlArrowsFollowPhysicalDirectionWhilePageKeysFollowBookOrder() {
        launch(rtl = true)
        assertEquals(ReadingProgression.RTL, main { reader.fragment.settings.value.readingProgression })
        val start = settledOffset()
        val width = js("window.innerWidth").toDouble()
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        waitFor("left arrow moves forward in RTL") { offset() < start - 50 }
        val forward = settledOffset()
        assertEquals(width, start - forward, 2.0)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        waitFor("right arrow moves backward in RTL") { offset() > forward + 50 }
        assertEquals(start, settledOffset(), 2.0)
        press(KeyEvent.KEYCODE_PAGE_DOWN)
        waitFor("Page Down still means next in RTL") { offset() < start - 50 }
        val pageDown = settledOffset()
        press(KeyEvent.KEYCODE_PAGE_UP)
        waitFor("Page Up still means previous in RTL") { offset() > pageDown + 50 }
        assertEquals(start, settledOffset(), 2.0)
    }

    @Test fun controlsModifiersReflowAndTextSelectionDoNotTurnThePage() {
        launch()
        main { reader.changeVolumeKeys(true) }
        val start = settledOffset()
        main { reader.controlsVisible = true }
        press(KeyEvent.KEYCODE_PAGE_DOWN, expectConsumed = false)
        SystemClock.sleep(300)
        assertEquals("Reading controls keep the underlying page still", start, offset(), 1.0)
        main { reader.controlsVisible = false }
        assertFalse("Modified keys remain available to Android and accessibility shortcuts", main {
            reader.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, 0, KeyEvent.META_CTRL_ON))
        })
        assertEquals(start, offset(), 1.0)

        assertEquals("true", js("(function(){var r=document.createRange();r.selectNodeContents(document.getElementById('paragraph-0'));var s=window.getSelection();s.removeAllRanges();s.addRange(r);return !s.isCollapsed;})()"))
        assertNotNull("The selection is exposed through Readium's native selection API", main { reader.fragment.currentSelection() })
        press(KeyEvent.KEYCODE_PAGE_DOWN)
        SystemClock.sleep(350)
        assertEquals("A selected passage is not navigated away from", start, offset(), 1.0)
        js("window.getSelection().removeAllRanges(); true")

        main {
            reader.changeFont(1.6)
            assertTrue("The test really exercises active reflow", reader.typographyUpdating)
            assertFalse("No navigation starts during reflow", reader.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN)))
            assertFalse(reader.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_PAGE_DOWN)))
        }
        waitFor("typography finishes") { !main { reader.typographyUpdating } }
    }

    @Test fun accessibilityActionsReachPageTurnsAndBookmarkAtTwoHundredPercentText() {
        launch(fontScale = 2f)
        assertEquals(2f, main { host.resources.configuration.fontScale }, 0.001f)
        val start = settledOffset()
        click("reader-show-controls")
        assertTrue(main { reader.controlsVisible })
        click("reader-more")
        scrollTo("reader-next-page")
        assertLabelAndTarget("reader-next-page", "Next page")
        click("reader-next-page")
        waitFor("an accessibility click turns to the next page") { offset() > start + 50 }
        val forward = settledOffset()
        click("reader-show-controls")
        click("reader-more")
        scrollTo("reader-previous-page")
        assertLabelAndTarget("reader-previous-page", "Previous page")
        click("reader-previous-page")
        waitFor("an accessibility click turns to the previous page") { offset() < forward - 50 }
        assertEquals(start, settledOffset(), 2.0)

        click("reader-show-controls")
        scrollTo("add-bookmark")
        click("add-bookmark")
        waitFor("bookmark editor is exposed") { findTag("bookmark-name") != null }
        val field = findTag("bookmark-name")!!
        val editor = descend(field) { it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_SET_TEXT } }
            ?: error("Bookmark name does not expose an accessibility edit action")
        assertTrue(editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Accessible harbor")
        }))
        assertFalse("Keys leave bookmark text entry alone", main {
            reader.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
        })
        click("bookmark-save")
        waitFor("the bookmark is saved through accessibility actions") {
            main { host.library.book(reader.book.id)?.bookmarks?.any { SavedBookmarks.name(it) == "Accessible harbor" } == true }
        }
    }

    /** H3: labelled 48dp bar buttons at 200% text, and the Aa sheet leaves most of the page visible. */
    @Test fun compactBarIsLabelledAtTwoHundredPercentAndTextSheetLeavesThePageVisible() {
        launch(fontScale = 2f)
        click("reader-show-controls")
        listOf("open-contents" to "Contents", "reader-text-settings" to "Text size and page colour",
            "add-bookmark" to "Bookmark", "reader-cover" to "Outside cover", "reader-more" to "More reading options").forEach { (tag, label) ->
            waitFor("$tag is shown") { findTag(tag)?.let(::visible) == true }
            assertLabelAndTarget(tag, label)
        }
        click("reader-text-settings")
        waitFor("the text sheet opens") { findTag("text-size-increase")?.let(::visible) == true }
        assertNull("The top bar steps aside while a sheet is open", findTag("reader-top-bar"))
        assertLabelAndTarget("text-size-increase", "Increase text size")
        val panel = Rect().also(findTag("reader-controls-panel")!!::getBoundsInScreen)
        val window = Rect().also { main { host.window.decorView.getGlobalVisibleRect(it) } }
        assertTrue("At least 55% of the page stays visible above the Aa sheet: panel=$panel window=$window",
            (panel.top - window.top).toDouble() / window.height() >= 0.55)
        click("text-size-increase")
        waitFor("A+ grows the text with publisher styles unchanged") { main { !reader.typographyUpdating && reader.fontScale > 1.05 } }
        click("reader-sheet-close")
        waitFor("Done returns to the compact bar") { findTag("reader-more")?.let(::visible) == true }
    }

    @Test fun readingThemeAndVolumeSwitchExposeSelectionAndState() {
        launch()
        click("reader-show-controls")
        click("reader-more")
        scrollTo("volume-key-page-turns")
        val volume = findTag("volume-key-page-turns")!!
        assertTrue("Volume setting exposes its purpose; ${controlDiagnostics(volume)}",
            controlLabel(volume).contains("Volume-key page turns", ignoreCase = true))
        assertEquals("Volume setting exposes its off state; ${controlDiagnostics(volume)}", false, checked(volume))
        click("volume-key-page-turns")
        waitFor("accessibility toggles volume turns") { main { reader.volumeKeys } }
        waitFor("the volume control announces its on state") { findTag("volume-key-page-turns")?.let(::checked) == true }
        click("reader-sheet-close")
        click("reader-text-settings")
        scrollTo("reading-theme-Paper")
        val paper = findTag("reading-theme-Paper")!!
        assertTrue("Paper exposes its selected state; ${controlDiagnostics(paper)}", selected(paper))
        click("reading-theme-Sepia")
        waitFor("Sepia becomes selected") { main { reader.themeName == "Sepia" } && selected(findTag("reading-theme-Sepia")!!) }
        assertFalse("The old theme no longer announces selected", selected(findTag("reading-theme-Paper")!!))
    }

    private fun launch(rtl: Boolean = false, fontScale: Float = 1f) {
        ReaderTestActivity.fontScaleForTests = fontScale
        val intent = Intent(target, ReaderTestActivity::class.java)
        if (rtl) intent.putExtra(ReaderTestActivity.EXTRA_READING_PROGRESSION, "rtl")
            .putExtra(ReaderTestActivity.EXTRA_LANGUAGE, "ar")
        scenario = ActivityScenario.launch<ReaderTestActivity>(intent).also { it.onActivity { activity -> host = activity } }
        reader = main { withTimeout(20_000) { host.ready.await() } }
        waitFor("the generated reader renders and has focus") {
            main { reader.fragment.isAdded && host.hasWindowFocus() && (reader.fragment.view?.width ?: 0) > 0 } &&
                js("Boolean(document.getElementById('paragraph-0') && window.readium)") == "true"
        }
        settledOffset()
    }

    private fun dispatch(event: KeyEvent): Boolean = main { host.window.callback.dispatchKeyEvent(event) }
    private fun press(keyCode: Int, expectConsumed: Boolean = true) {
        val down = dispatch(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        val up = dispatch(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        if (expectConsumed) { assertTrue("Key down is captured by the reader", down); assertTrue("Key up is captured too", up) }
    }

    private fun click(tag: String) {
        waitFor("$tag is reachable through accessibility") { findTag(tag)?.let(::visible) == true }
        val tagged = findTag(tag)!!
        val owner = actionOwner(tagged)
        assertNotNull("$tag has a scoped accessibility click owner; ${controlDiagnostics(tagged)}", owner)
        assertTrue("$tag has an accessibility click action; ${controlDiagnostics(tagged)}",
            owner!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun scrollTo(tag: String) {
        var scanForward = true
        try {
            waitFor("scrolling reveals $tag") {
                val targetNode = findTag(tag)
                if (targetNode?.let(::visible) == true) return@waitFor true
                // Reopening Controls can retain its previous scroll position. Ask this exact
                // control to reveal itself before choosing a directional panel scroll.
                targetNode?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                instrumentation.waitForIdleSync()
                if (findTag(tag)?.let(::visible) == true) return@waitFor true
                val panel = findTag("reader-controls-scroll")
                if (panel != null) {
                    val panelBounds = Rect().also(panel::getBoundsInScreen)
                    val targetBounds = findTag(tag)?.let { Rect().also(it::getBoundsInScreen) }
                    val forward = if (targetBounds != null && !targetBounds.isEmpty)
                        targetBounds.exactCenterY() >= panelBounds.top else scanForward
                    scanForward = forward
                    val desired = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    val opposite = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    val scrollables = controlNodes(panel).filter { it.isScrollable }
                    fun owner(action: Int) = scrollables.firstOrNull { candidate ->
                        candidate.actionList.any { it.id == action }
                    }
                    val desiredOwner = owner(desired)
                    if (desiredOwner != null) desiredOwner.performAction(desired)
                    else owner(opposite)?.let { fallback ->
                        // Offscreen controls can disappear from Android's accessibility tree.
                        // At a panel boundary reverse the scan, keeping that direction until
                        // the requested tag appears or the other boundary is reached.
                        scanForward = opposite == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        fallback.performAction(opposite)
                    }
                }
                SystemClock.sleep(120)
                false
            }
        } catch (failure: AssertionError) {
            val requested = findTag(tag)?.let(::controlDiagnostics) ?: "absent"
            val panel = nodeDiagnostics(findTag("reader-controls-scroll"))
            throw AssertionError("${failure.message}; target={$requested}; panel={$panel}", failure)
        }
    }

    private fun assertLabelAndTarget(tag: String, label: String) {
        val tagged = findTag(tag)!!
        assertTrue("$tag announces $label; ${controlDiagnostics(tagged)}", controlLabel(tagged).contains(label))
        val node = actionOwner(tagged) ?: error("$tag has no accessibility action owner; ${controlDiagnostics(tagged)}")
        val bounds = Rect().also(node::getBoundsInScreen)
        val minimum = main { 48f * host.resources.displayMetrics.density } - 1
        assertTrue("$tag is at least 48dp wide", bounds.width() >= minimum)
        assertTrue("$tag is at least 48dp high", bounds.height() >= minimum)
    }

    /** A tag may identify the wrapper while Android exposes labels and state on its children. */
    private fun controlNodes(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        fun collect(candidate: AccessibilityNodeInfo, depth: Int) {
            nodes += candidate
            if (depth >= 6 || candidate.className?.toString() == "android.webkit.WebView") return
            for (index in 0 until candidate.childCount) candidate.getChild(index)?.let { collect(it, depth + 1) }
        }
        collect(node, 0)
        return nodes
    }

    // Label evidence is confined to the tagged control, never its containing panel or siblings.
    private fun controlLabel(node: AccessibilityNodeInfo): String = controlNodes(node).flatMap {
        listOfNotNull(it.contentDescription?.toString(), it.text?.toString())
    }.joinToString(" ")

    private fun actionOwner(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun clickable(candidate: AccessibilityNodeInfo) = candidate.isClickable ||
            candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        controlNodes(node).firstOrNull(::clickable)?.let { return it }
        // Some Compose wrappers are children of the clickable semantics owner. Only accept an
        // ancestor with these same bounds, so a broad settings panel can never satisfy the test.
        val bounds = Rect().also(node::getBoundsInScreen)
        var parent = node.parent
        repeat(2) {
            val candidate = parent ?: return null
            if (Rect().also(candidate::getBoundsInScreen) != bounds) return null
            if (clickable(candidate)) return candidate
            parent = candidate.parent
        }
        return null
    }

    private fun checked(node: AccessibilityNodeInfo): Boolean? {
        val candidates = controlNodes(node) + listOfNotNull(actionOwner(node))
        candidates.firstOrNull { it.isCheckable }?.let { return it.isChecked }
        return candidates.firstNotNullOfOrNull { candidate ->
            when (candidate.stateDescription?.toString()?.trim()?.lowercase()) {
                "on", "checked" -> true
                "off", "not checked", "unchecked" -> false
                else -> null
            }
        }
    }

    private fun selected(node: AccessibilityNodeInfo): Boolean =
        (controlNodes(node) + listOfNotNull(actionOwner(node))).any { candidate ->
        val state = candidate.stateDescription?.toString().orEmpty()
        candidate.isSelected || (candidate.isCheckable && candidate.isChecked) ||
            (state.contains("selected", ignoreCase = true) && !state.contains("not selected", ignoreCase = true) &&
                !state.contains("unselected", ignoreCase = true))
    }

    /** Only generated QA control labels and geometry, scoped to the tag plus immediate relatives. */
    private fun controlDiagnostics(node: AccessibilityNodeInfo): String {
        val descendants = controlNodes(node).drop(1).take(12).joinToString("; ", transform = ::nodeDiagnostics)
        return "tag={${nodeDiagnostics(node)}}; parent={${nodeDiagnostics(node.parent)}}; children=[$descendants]"
    }

    private fun nodeDiagnostics(candidate: AccessibilityNodeInfo?): String {
        if (candidate == null) return "absent"
        return "id=${candidate.viewIdResourceName},class=${candidate.className},text=${candidate.text}," +
            "label=${candidate.contentDescription},click=${candidate.isClickable},selected=${candidate.isSelected}," +
            "checkable=${candidate.isCheckable},checked=${candidate.isChecked},state=${candidate.stateDescription}," +
            "visible=${candidate.isVisibleToUser},bounds=${Rect().also(candidate::getBoundsInScreen)}," +
            "actions=${candidate.actionList.map { it.id }}"
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
            if (ancestor.viewIdResourceName?.substringAfterLast('/') == "reader-controls-scroll") {
                val panel = Rect().also(ancestor::getBoundsInScreen)
                if (!clipped.intersect(panel)) return false
                break
            }
            ancestor = ancestor.parent
        }
        return original.exactCenterX() >= clipped.left && original.exactCenterX() < clipped.right &&
            original.exactCenterY() >= clipped.top && original.exactCenterY() < clipped.bottom
    }

    private fun findTag(tag: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) automation.clearCache()
        return (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
            .asSequence().filter { it.packageName?.toString() == target.packageName }
            .mapNotNull { root -> descend(root) { it.viewIdResourceName?.substringAfterLast('/') == tag } }.firstOrNull()
    }

    private fun descend(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        if (node.className?.toString() == "android.webkit.WebView") return null
        for (index in 0 until node.childCount) node.getChild(index)?.let { descend(it, predicate) }?.let { return it }
        return null
    }

    private fun offset(): Double = js("window.scrollX").toDouble()
    private fun settledOffset(): Double {
        var previous = offset()
        var stableSince = SystemClock.uptimeMillis()
        waitFor("the page animation settles", timeout = 5_000) {
            val current = offset()
            if (abs(current - previous) > 0.5) stableSince = SystemClock.uptimeMillis()
            previous = current
            SystemClock.uptimeMillis() - stableSince >= 240
        }
        return previous
    }

    private fun js(script: String): String = main {
        withTimeout(10_000) { reader.fragment.evaluateJavascript(script) } ?: error("No generated EPUB viewport")
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
