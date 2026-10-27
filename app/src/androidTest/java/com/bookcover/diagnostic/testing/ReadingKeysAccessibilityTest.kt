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
        ReaderTestActivity.fontScaleForTests = 1f
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
        scrollTo("reader-next-page")
        assertLabelAndTarget("reader-next-page", "Next page")
        click("reader-next-page")
        waitFor("an accessibility click turns to the next page") { offset() > start + 50 }
        val forward = settledOffset()
        click("reader-show-controls")
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

    @Test fun readingThemeAndVolumeSwitchExposeSelectionAndState() {
        launch()
        click("reader-show-controls")
        scrollTo("volume-key-page-turns")
        val volume = findTag("volume-key-page-turns")!!
        assertTrue("Volume setting exposes its purpose", volume.contentDescription?.contains("volume", ignoreCase = true) == true)
        assertTrue("Volume setting exposes a checkable state", volume.isCheckable)
        assertFalse(volume.isChecked)
        click("volume-key-page-turns")
        waitFor("accessibility toggles volume turns") { main { reader.volumeKeys } }
        assertTrue(findTag("volume-key-page-turns")!!.isChecked)
        scrollTo("reading-theme-Paper")
        assertTrue("Paper exposes its selected state", selected(findTag("reading-theme-Paper")!!))
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
        assertTrue("$tag has an accessibility click action", findTag(tag)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun scrollTo(tag: String) {
        waitFor("scrolling reveals $tag") {
            if (findTag(tag)?.let(::visible) == true) return@waitFor true
            findTag("reader-controls-scroll")?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(120)
            false
        }
    }

    private fun assertLabelAndTarget(tag: String, label: String) {
        val node = findTag(tag)!!
        assertTrue("$tag announces $label", node.contentDescription?.contains(label) == true || node.text?.contains(label) == true)
        val bounds = Rect().also(node::getBoundsInScreen)
        val minimum = main { 48f * host.resources.displayMetrics.density } - 1
        assertTrue("$tag is at least 48dp wide", bounds.width() >= minimum)
        assertTrue("$tag is at least 48dp high", bounds.height() >= minimum)
    }

    private fun selected(node: AccessibilityNodeInfo): Boolean = node.isSelected || (node.isCheckable && node.isChecked) ||
        node.stateDescription?.toString()?.lowercase()?.let { it == "selected" } == true

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
