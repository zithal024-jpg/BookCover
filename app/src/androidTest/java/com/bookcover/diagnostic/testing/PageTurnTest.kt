package com.bookcover.diagnostic.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.DiagnosticLog
import com.bookcover.diagnostic.reader.EpubSession
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * PT-1 on-phone checks of the paper page turn (the app-owned engine) and of the standard turn,
 * which with "Paper page turn" off must stay Readium's own, exactly as before PT-1. Isolated QA app, [ReaderTestActivity] and the generated [NativeEpubFixture] only: chapter 0
 * is a long multi-page chapter, chapter 1 a short final chapter, so both chapter boundaries and
 * both ends of the book are reachable.
 *
 * Every engine turn writes one `PT-1 turn …` DiagnosticLog line (snapshot method and time, readiness
 * path, frame counts). Each test waits for that line, checks its fields and prints it to logcat
 * under the tag `PT1QA` so the QA run's output carries the numbers.
 *
 * Swipes are injected as real touch events through the window, so they reach `PageTurnTouchLayer`
 * exactly like a finger. A committed swipe either travels past 30% of the width and stops before
 * release (distance rule) or is fast (fling rule); a cancelled one travels 12% and stops.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalReadiumApi::class)
class PageTurnTest {
    @get:Rule val testName = TestName()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val target get() = instrumentation.targetContext
    private val preferences get() = target.getSharedPreferences(ReaderTestActivity.TEST_PREFERENCES, Context.MODE_PRIVATE)
    private var scenario: ActivityScenario<ReaderTestActivity>? = null
    private lateinit var host: ReaderTestActivity
    private lateinit var reader: EpubSession
    private var previousFlags = 0

    @Before fun prepare() {
        check(target.packageName.endsWith(".qa")) { "Page-turn tests must use the isolated QA app." }
        preferences.edit().clear().putBoolean("publisherStyles", false).commit()
        val service = automation.serviceInfo
        previousFlags = service.flags
        service.flags = service.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service
    }

    @After fun cleanUp() {
        scenario?.close()
        preferences.edit().clear().commit()
        automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
    }

    // ---- Swipes within a chapter ----------------------------------------------------------------

    /**
     * Owner report 2026-10-31: the app-drawn slide made normal reading stutter. With the setting off
     * a swipe, an edge tap and a page button must be Readium's own turns: no snapshot overlay is ever
     * shown and the engine logs no turn.
     */
    @Test fun standardTurnsStayNativeWithNoOverlay() {
        launch(paper = false)
        awaitIdle() // Standard turns need no Android animations: Readium animates them itself.
        val width = pageWidth()
        val start = settledOffset()
        val mark = turnLines().lastOrNull()
        var overlaySeen = false
        val watch = { if (main { reader.pageTurns.overlayVisible }) overlaySeen = true }
        drag(0.80f, 0.20f, watch = watch)
        val afterSwipe = settledOffset()
        assertEquals("A standard swipe turns exactly one page", width, afterSwipe - start, 2.0)
        main { reader.changeEdgeTaps(true) }
        tapViewport(0.98f, 0.60f)
        repeat(20) { watch(); SystemClock.sleep(16) }
        assertEquals("A standard edge tap turns exactly one page", width, settledOffset() - afterSwipe, 2.0)
        main { reader.changeEdgeTaps(false) }
        click("reader-show-controls")
        click("reader-more")
        scrollTo("reader-previous-page")
        click("reader-previous-page")
        repeat(20) { watch(); SystemClock.sleep(16) }
        assertEquals("Previous page returns exactly one page", afterSwipe, settledOffset(), 2.0)
        assertFalse("The standard turn never shows the paper overlay", overlaySeen)
        assertTrue("The standard turn never goes through the paper engine: ${linesSince(mark)}", linesSince(mark).isEmpty())
        assertTrue("No reader error was reported during the turns: ${host.reports}", host.reports.isEmpty())
    }

    @Test fun paperSwipesTurnExactlyOnePageAndShortSwipesCancel() {
        val paper = true
        launch(paper = paper)
        requireEngine()
        val width = pageWidth()
        val start = settledOffset()
        val startHref = href()

        engineTurn("backward swipe at the start of the book", paper, "swipe") { drag(0.20f, 0.65f, hold = true) }
        assertEquals("The start of the book rubber-bands; the page stays", start, settledOffset(), 1.0)
        assertEquals(startHref, href())

        engineTurn("slow forward swipe past 30%", paper, "swipe") { drag(0.80f, 0.35f, hold = true) }
        val afterSlow = settledOffset()
        assertEquals("A swipe past 30% of the width turns exactly one page", width, afterSlow - start, 2.0)

        engineTurn("quick forward fling", paper, "swipe") { drag(0.80f, 0.20f) }
        val afterFling = settledOffset()
        assertEquals("A fling turns exactly one page", width, afterFling - afterSlow, 2.0)

        val locatorBefore = settledLocator()
        engineTurn("short swipe (12%, stopped)", paper, "swipe") { drag(0.80f, 0.68f, hold = true) }
        assertEquals("A short swipe cancels and leaves the page where it was", afterFling, settledOffset(), 1.0)
        assertEquals("A cancelled swipe leaves the reading position unchanged", locatorBefore, settledLocator())

        engineTurn("swipe back", paper, "swipe") { drag(0.20f, 0.65f, hold = true) }
        assertEquals("A swipe back returns exactly one page", afterSlow, settledOffset(), 2.0)
        assertTrue("No reader error was reported during the turns: ${host.reports}", host.reports.isEmpty())
    }

    // ---- Chapter boundaries and the end of the book ---------------------------------------------

    @Test fun paperSwipesCrossChapterBoundariesAndStopAtTheEndOfTheBook() {
        val paper = true
        launch(paper = paper)
        requireEngine()
        js("readium.scrollToEnd(); true")
        settledOffset()
        assertTrue("The test starts on the last page of chapter 0", main { !visibleWebView().canScrollHorizontally(1) })

        val forwardReveal = RevealSamples()
        engineTurn("forward swipe across the chapter boundary", paper, "swipe", forwardReveal) { drag(0.80f, 0.35f, hold = true, sampler = forwardReveal) }
        waitFor("chapter 1 is current") { href().endsWith("chapter1.xhtml") && js("Boolean(document.getElementById('linked'))") == "true" }
        assertEquals("A forward crossing lands on the next chapter's first page", 0.0, settledOffset(), 1.0)
        assertTrue(main { !visibleWebView().canScrollHorizontally(-1) })
        forwardReveal.assertAll("forward crossing reveals chapter 1's first page") { url, canBack, _ ->
            url.contains("chapter1.xhtml") && !canBack
        }

        val backwardReveal = RevealSamples()
        engineTurn("backward swipe across the chapter boundary", paper, "swipe", backwardReveal) { drag(0.20f, 0.65f, hold = true, sampler = backwardReveal) }
        waitFor("chapter 0 is current") { href().endsWith("chapter0.xhtml") && js("Boolean(document.getElementById('paragraph-0'))") == "true" }
        settledOffset()
        assertEquals("A backward crossing lands on the previous chapter's LAST page", "true",
            js("(scrollX + innerWidth >= document.scrollingElement.scrollWidth - 2)"))
        assertTrue(main { !visibleWebView().canScrollHorizontally(1) })
        // R12: the reveal must never show chapter 0's first page before Readium moves it to its end.
        backwardReveal.assertAll("backward crossing reveals chapter 0's last page (R12)") { url, _, canForward ->
            url.contains("chapter0.xhtml") && !canForward
        }

        engineTurn("forward swipe into the final chapter", paper, "swipe") { drag(0.80f, 0.20f) }
        waitFor("chapter 1 is current again") { href().endsWith("chapter1.xhtml") && js("Boolean(document.getElementById('linked'))") == "true" }
        js("readium.scrollToEnd(); true")
        val end = settledOffset()
        val endLocator = settledLocator()
        engineTurn("forward swipe at the end of the book", paper, "swipe") { drag(0.80f, 0.35f, hold = true) }
        SystemClock.sleep(300) // Past Readium's 100 ms locator debounce, so a stray turn would show.
        assertEquals("The end of the book rubber-bands; the page stays", end, settledOffset(), 1.0)
        assertEquals("The end-of-book swipe leaves the locator unchanged", endLocator, settledLocator())
        assertTrue(href().endsWith("chapter1.xhtml"))
    }

    // ---- Edge taps, the controls tap and the page buttons ---------------------------------------

    @Test fun paperEdgeTapsControlsTapAndPageButtonsStillWork() {
        val paper = true
        launch(paper = paper)
        requireEngine()
        val width = pageWidth()
        val start = settledOffset()
        main { reader.changeEdgeTaps(true) }

        engineTurn("right-edge tap", paper, "edge_tap") { tapViewport(0.98f, 0.60f) }
        val afterTap = settledOffset()
        assertEquals("An edge tap turns exactly one page", width, afterTap - start, 2.0)
        engineTurn("left-edge tap", paper, "edge_tap") { tapViewport(0.02f, 0.60f) }
        assertEquals("The opposite edge returns exactly one page", start, settledOffset(), 2.0)

        // Below the top toolbar and above the controls panel in either layout.
        tapViewport(0.50f, 0.16f)
        waitFor("the centre tap still reveals the reading controls") { main { reader.controlsVisible } }
        main { reader.controlsVisible = false }
        main { reader.changeEdgeTaps(false) }
        awaitIdle()

        click("reader-show-controls")
        click("reader-more")
        scrollTo("reader-next-page")
        engineTurn("Next page button", paper, "button") { click("reader-next-page") }
        val afterNext = settledOffset()
        assertEquals("Next page moves exactly one page", width, afterNext - start, 2.0)
        assertFalse("A page button closes the controls", main { reader.controlsVisible })

        click("reader-show-controls")
        click("reader-more")
        scrollTo("reader-previous-page")
        engineTurn("Previous page button", paper, "button") { click("reader-previous-page") }
        assertEquals("Previous page moves exactly one page", start, settledOffset(), 2.0)
    }

    // ---- Right-to-left ---------------------------------------------------------------------------

    @Test fun rtlPaperSwipesFollowTheBookDirection() {
        launch(rtl = true, paper = true)
        requireEngine()
        assertEquals(ReadingProgression.RTL, main { reader.fragment.settings.value.readingProgression })
        val width = pageWidth()
        val start = settledOffset()
        // R9: backward in an RTL book is a right-to-left finger; at the start of the book it must not move.
        engineTurn("RTL backward swipe at the start of the book", true, "swipe") { drag(0.80f, 0.35f, hold = true) }
        assertEquals("The RTL start of the book rubber-bands; the page stays", start, settledOffset(), 1.0)

        val before = settledOffset()
        engineTurn("RTL forward swipe", true, "swipe") { drag(0.20f, 0.65f, hold = true) }
        val forward = settledOffset()
        assertEquals("An RTL forward swipe turns exactly one page in book direction", width, before - forward, 2.0)
        engineTurn("RTL backward swipe", true, "swipe") { drag(0.80f, 0.35f, hold = true) }
        assertEquals("An RTL backward swipe returns exactly one page", before, settledOffset(), 2.0)
    }

    // ---- The toggle -----------------------------------------------------------------------------

    @Test fun paperPageTurnToggleRowPersistsAcrossANewSession() {
        launch()
        assertFalse("Paper page turn is off by default", main { reader.paperPageTurn })
        click("reader-show-controls")
        // Owner report 2026-10-31: the row was hard to find under More; it is now in the Aa sheet.
        click("reader-text-settings")
        scrollTo("paper-page-turn")
        assertTrue("The toggle row announces its label", label(findTag("paper-page-turn")!!).contains("Paper page turn"))
        assertFalse("The row announces its off state", findTag("paper-page-turn")!!.isChecked)
        click("paper-page-turn")
        waitFor("the row turns the paper style on") { main { reader.paperPageTurn } }
        waitFor("the row announces its on state") { findTag("paper-page-turn")?.isChecked == true }
        waitFor("the setting is saved") { preferences.getBoolean("paperPageTurn", false) }

        scenario!!.close(); scenario = null
        launch(paper = null) // Keep what the row saved.
        assertTrue("The paper setting survives opening a new session", main { reader.paperPageTurn })
        click("reader-show-controls")
        click("reader-text-settings")
        scrollTo("paper-page-turn")
        assertTrue("The reopened row shows on", findTag("paper-page-turn")!!.isChecked)
        click("reader-sheet-close")
        main { reader.controlsVisible = false }
        waitFor("the reading controls are closed") { main { !reader.controlsVisible && !reader.panelsOpen } }
        if (animationsOn()) {
            val start = settledOffset()
            engineTurn("swipe after reopening", true, "swipe") { drag(0.80f, 0.35f, hold = true) }
            assertEquals(pageWidth(), settledOffset() - start, 2.0)
        }
    }

    // ---- Frame-time sample on the inner screen --------------------------------------------------

    /** Ten paper swipes on the unfolded inner screen; prints the frame-time counts for the record. */
    @Test fun tenPaperSwipesReportFrameTimesOnTheInnerScreen() {
        launch()
        assumeTrue("Unfold the phone: frame times are recorded on the inner screen " +
            "(smallest width ${host.resources.configuration.smallestScreenWidthDp}dp)",
            host.resources.configuration.smallestScreenWidthDp >= 600)
        requireEngine()
        val width = pageWidth()
        for (paper in listOf(true)) {
            main { reader.changePaperPageTurn(paper) }
            // Start at the top of the long chapter: ten turns must stay inside it, or a later turn
            // would cross into chapter 1 and the in-chapter offset would reset.
            js("readium.scrollToStart(); true")
            settledOffset()
            var overBudget = 0
            var frames = 0
            repeat(10) { turn ->
                val before = settledOffset()
                val line = engineTurn("frame sample ${turn + 1}/10", paper, "swipe") { drag(0.80f, 0.35f, hold = true) }
                assertEquals("Sample turn ${turn + 1} turns exactly one page", width, settledOffset() - before, 2.0)
                frames += Regex("frames=(\\d+)").find(line)!!.groupValues[1].toInt()
                overBudget += Regex("overBudget=(\\d+)").find(line)!!.groupValues[1].toInt()
            }
            Log.i(TAG, "${testName.methodName} | style=${style(paper)} | ten turns: frames=$frames; overBudget=$overBudget")
        }
    }

    // ---- Engine turn bookkeeping ----------------------------------------------------------------

    private fun animationsOn() = ValueAnimator.areAnimatorsEnabled()

    /** The engine's animated path needs Android animations on; "Remove animations" turns instantly. */
    private fun requireEngine() {
        assumeTrue("Android animations are off on this phone, so turns take the instant path, not the engine", animationsOn())
        awaitIdle()
        assertTrue("The generated book is reflowable, so the engine handles its turns", main { reader.supportsTypography })
    }

    /** What the visible page looked like while a turn's snapshot was revealing it (R12). */
    private inner class RevealSamples {
        /** url, can scroll backward (left), can scroll forward (right). */
        val samples = mutableListOf<Triple<String, Boolean, Boolean>>()

        fun sample() {
            main {
                val turns = reader.pageTurns
                if (turns.overlayVisible && turns.pageReady && turns.progress > 0.02f) {
                    val web = visibleWebView()
                    samples += Triple(web.url.orEmpty(), web.canScrollHorizontally(-1), web.canScrollHorizontally(1))
                }
            }
        }

        fun assertAll(description: String, expected: (String, Boolean, Boolean) -> Boolean) {
            Log.i(TAG, "${testName.methodName} | $description: ${samples.size} reveal samples")
        // Zero samples would let the check below pass without checking anything.
        assertTrue("$description: no reveal samples were taken, so the reveal was not checked", samples.isNotEmpty())
            val wrong = samples.filterNot { expected(it.first.substringAfterLast('/'), it.second, it.third) }
            assertTrue("$description; ${wrong.size} of ${samples.size} samples showed another page: " +
                wrong.take(3).joinToString { "${it.first.substringAfterLast('/')} back=${it.second} forward=${it.third}" },
                wrong.isEmpty())
        }
    }

    /**
     * Runs [action] as one user turn through the engine, waits for its `PT-1 turn` line and for the
     * engine to be idle, then checks and prints the line (tag [TAG]).
     */
    private fun engineTurn(label: String, paper: Boolean, source: String, reveal: RevealSamples? = null, action: () -> Unit): String {
        awaitIdle()
        val mark = turnLines().lastOrNull()
        action()
        var line: String? = null
        try {
            waitFor("$label: the engine logs its PT-1 turn line") {
                reveal?.sample()
                line = linesSince(mark).lastOrNull()
                line != null
            }
        } catch (failure: AssertionError) {
            val recent = DiagnosticLog.report().lineSequence().filter { "PT-1" in it }.toList().takeLast(5)
            throw AssertionError("${failure.message}; recent PT-1 lines: $recent", failure)
        }
        awaitIdle()
        val text = line!!
        val snapshot = Regex("snapshot=(\\d+)ms via (pixelcopy|draw)").find(text)
        val frames = Regex("frames=(\\d+); overBudget=(\\d+) @(\\d+)Hz").find(text)
        Log.i(TAG, "${testName.methodName} | $label | snapshotMs=${snapshot?.groupValues?.get(1)} " +
            "method=${snapshot?.groupValues?.get(2)} frames=${frames?.groupValues?.get(1)} " +
            "overBudget=${frames?.groupValues?.get(2)} refreshHz=${frames?.groupValues?.get(3)} | $text")
        assertTrue("$label uses the ${style(paper)} style: $text", "style=${style(paper)};" in text)
        assertTrue("$label is a $source turn: $text", "source=$source;" in text)
        assertNotNull("$label records how and how fast the page was copied: $text", snapshot)
        assertNotNull("$label records its frame counts: $text", frames)
        // The curl must follow the finger from its first move: the copy is taken at finger-down.
        if (source == "swipe") assertTrue("$label used the snapshot taken at finger-down: $text", "early=yes;" in text)
        return text
    }

    private fun style(paper: Boolean) = if (paper) "paper" else "slide"

    private fun turnLines(): List<String> =
        DiagnosticLog.report().lineSequence().filter { "PT-1 turn style=" in it }.toList()

    private fun linesSince(mark: String?): List<String> {
        val lines = turnLines()
        val index = if (mark == null) -1 else lines.lastIndexOf(mark)
        return lines.drop(index + 1)
    }

    private fun awaitIdle() = waitFor("the page-turn engine is idle") { main { reader.pageTurns.idle } }

    // ---- Reader host ----------------------------------------------------------------------------

    /** [paper] null keeps the saved setting. Retries once when the first reader never attaches (known flake). */
    private fun launch(rtl: Boolean = false, paper: Boolean? = false) {
        if (paper != null) preferences.edit().putBoolean("paperPageTurn", paper).commit()
        for (attempt in 1..2) {
            try { open(rtl); break } catch (failure: AssertionError) {
                if (attempt == 2 || failure.message?.contains("navigator is attached") != true) throw failure
                Log.w(TAG, "${testName.methodName}: the first reader screen did not attach; reopening once")
                scenario?.close(); scenario = null
            }
        }
        if (paper != null) assertEquals(paper, main { reader.paperPageTurn })
    }

    private fun open(rtl: Boolean) {
        val intent = Intent(target, ReaderTestActivity::class.java)
        if (rtl) intent.putExtra(ReaderTestActivity.EXTRA_READING_PROGRESSION, "rtl").putExtra(ReaderTestActivity.EXTRA_LANGUAGE, "ar")
        scenario = ActivityScenario.launch<ReaderTestActivity>(intent).also { it.onActivity { activity -> host = activity } }
        reader = main { withTimeout(20_000) { host.ready.await() } }
        waitFor("navigator is attached") { main { reader.fragment.isAdded && reader.fragment.view?.width != 0 } }
        waitFor("the generated chapter renders and the window has focus") {
            main { host.hasWindowFocus() } && js("Boolean(document.getElementById('paragraph-0') && window.readium)") == "true"
        }
        settledOffset()
    }

    private fun href(): String = main { reader.fragment.currentLocator.value.href.toString() }

    private fun pageWidth(): Double = js("window.innerWidth").toDouble()

    private fun offset(): Double = js("window.scrollX").toDouble()

    /** The page offset once the engine is idle and the offset has not moved for 240 ms. */
    private fun settledOffset(): Double {
        var previous = offset()
        var stableSince = SystemClock.uptimeMillis()
        waitFor("the page turn reaches a stable endpoint", timeout = 8_000) {
            val current = offset()
            if (abs(current - previous) > 0.5 || !main { reader.pageTurns.idle }) stableSince = SystemClock.uptimeMillis()
            previous = current
            SystemClock.uptimeMillis() - stableSince >= 240
        }
        return previous
    }

    /** Readium's locator after its 100 ms debounce: resource and in-resource progression. */
    private fun settledLocator(): String {
        settledOffset()
        SystemClock.sleep(250)
        return main { reader.fragment.currentLocator.value.let { "${it.href} @${it.locations.progression}" } }
    }

    private fun js(script: String): String = main {
        withTimeout(10_000) { reader.fragment.evaluateJavascript(script) } ?: error("No reflowable viewport")
    }

    private fun <T> main(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { action() } }

    private fun waitFor(description: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (failure: Throwable) { lastFailure = failure }
            SystemClock.sleep(16)
        }
        throw AssertionError("Timed out waiting for $description", lastFailure)
    }

    // ---- Touch injection ------------------------------------------------------------------------

    private fun visibleWebView(): WebView {
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        return descendants(reader.fragment.requireView()).filterIsInstance<WebView>().maxByOrNull {
            val visible = Rect(); if (it.getGlobalVisibleRect(visible)) visible.width() * visible.height() else 0
        } ?: error("No native WebView")
    }

    private fun webViewFrame(): Rect = main {
        val view = visibleWebView(); val point = IntArray(2); view.getLocationOnScreen(point)
        Rect(point[0], point[1], point[0] + view.width, point[1] + view.height)
    }

    private fun tapViewport(x: Float, y: Float) {
        val frame = webViewFrame()
        val px = frame.left + frame.width() * x
        val py = frame.top + frame.height() * y
        val down = SystemClock.uptimeMillis()
        send(down, down, MotionEvent.ACTION_DOWN, px, py)
        SystemClock.sleep(40)
        send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, px, py)
        instrumentation.waitForIdleSync()
    }

    /**
     * A horizontal drag at 60% height from [start] to [end] (fractions of the page width). Without
     * [hold] it takes 240 ms (a fling). With [hold] it moves at a walking pace and then rests for
     * 200 ms before lifting, so the release velocity is about zero and only the distance decides.
     */
    private fun drag(start: Float, end: Float, hold: Boolean = false, sampler: RevealSamples? = null, watch: (() -> Unit)? = null) {
        val frame = webViewFrame()
        val y = frame.top + frame.height() * 0.60f
        fun x(fraction: Float) = frame.left + frame.width() * fraction
        val steps = if (hold) 24 else 12
        val stepMillis = if (hold) 25L else 20L
        val down = SystemClock.uptimeMillis()
        send(down, down, MotionEvent.ACTION_DOWN, x(start), y)
        for (step in 1..steps) {
            SystemClock.sleep(stepMillis)
            send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x(start + (end - start) * step / steps), y)
            sampler?.sample()
            watch?.invoke()
        }
        if (hold) repeat(5) {
            SystemClock.sleep(40)
            send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x(end), y)
            sampler?.sample()
        }
        send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x(end), y)
        instrumentation.waitForIdleSync()
        if (watch != null) repeat(30) { watch(); SystemClock.sleep(16) }
    }

    private fun send(down: Long, time: Long, action: Int, x: Float, y: Float) {
        MotionEvent.obtain(down, time, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            .also { instrumentation.sendPointerSync(it); it.recycle() }
    }

    // ---- Accessibility access to the controls (same approach as ReadingKeysAccessibilityTest) ---

    private fun click(tag: String) {
        waitFor("$tag is reachable through accessibility") { findTag(tag)?.let(::visible) == true }
        val tagged = findTag(tag)!!
        val owner = (sequenceOf(tagged) + children(tagged)).firstOrNull {
            it.isClickable || it.actionList.any { action -> action.id == AccessibilityNodeInfo.ACTION_CLICK }
        } ?: error("$tag has no accessibility click action")
        assertTrue("$tag accepts an accessibility click", owner.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun scrollTo(tag: String) = waitFor("scrolling reveals $tag") {
        if (findTag(tag)?.let(::visible) == true) return@waitFor true
        findTag(tag)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
        instrumentation.waitForIdleSync()
        if (findTag(tag)?.let(::visible) == true) return@waitFor true
        findTag("reader-controls-scroll")?.let { panel ->
            (sequenceOf(panel) + children(panel)).firstOrNull { node ->
                node.isScrollable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
            }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        SystemClock.sleep(120)
        false
    }

    private fun children(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
            yield(child)
            if (child.className?.toString() != "android.webkit.WebView") yieldAll(children(child))
        }
    }

    private fun label(node: AccessibilityNodeInfo): String = (sequenceOf(node) + children(node))
        .flatMap { sequenceOf(it.contentDescription?.toString(), it.text?.toString()) }.filterNotNull().joinToString(" ")

    private fun visible(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        var ancestor: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (ancestor != null && depth++ < 128) {
            if (ancestor.viewIdResourceName?.substringAfterLast('/') == "reader-controls-scroll") {
                val panel = Rect().also(ancestor::getBoundsInScreen)
                return panel.contains(bounds.centerX(), bounds.centerY())
            }
            ancestor = ancestor.parent
        }
        return true
    }

    private fun findTag(tag: String): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) automation.clearCache()
        return (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
            .asSequence().filter { it.packageName?.toString() == target.packageName }
            .mapNotNull { root -> (sequenceOf(root) + children(root)).firstOrNull { it.viewIdResourceName?.substringAfterLast('/') == tag } }
            .firstOrNull()
    }

    private companion object {
        const val TAG = "PT1QA"
    }
}
