package com.bookcover.diagnostic.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.rewards.MoonlitTier
import com.bookcover.diagnostic.rewards.RelicMode
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native Android accessibility actions against the actual cabinet; generated fixture data only. */
@RunWith(AndroidJUnit4::class)
class RelicCabinetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val target get() = instrumentation.targetContext
    private var scenario: ActivityScenario<RelicCabinetTestActivity>? = null
    private lateinit var host: RelicCabinetTestActivity
    private lateinit var book: BookRecord
    private var previousFlags = 0

    @Before fun inspectQaWindows() {
        check(target.packageName.endsWith(".qa")) { "Relic cabinet tests require the separate QA app." }
        automation.serviceInfo = automation.serviceInfo.apply {
            previousFlags = flags
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    @After fun closeFixture() {
        scenario?.close()
        RelicCabinetTestActivity.fontScaleForTests = 1f
        RelicCabinetTestActivity.testingControlsForTests = false
        RelicCabinetTestActivity.outsideAspectRatioForTests = .41f
        automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
    }

    @Test fun lockedPreviewCannotSaveButCollectedCommonPersistsGlobally() {
        launch()
        clickTag("relic-item-archive")
        assertFalse("Locked Mythic cannot be saved", revealTag("relic-save").isEnabled)
        assertPreviewFits(revealTag("relic-cover-preview", forward = false))
        screenshot("relic-cabinet-locked-mythic")
        clickTag("relic-item-quill", forward = false)
        val save = revealTag("relic-save")
        assertTrue("Collected Common can be saved", save.isEnabled)
        assertTouchTarget(save)
        click(save)
        waitFor("global appearance persisted") { onHost { it.saveRequests == 1 && !it.busy && !it.showCabinet } }
        assertNull(onHost { it.failureForTests })
        assertNull(onHost { it.lastSavedId })
        val stored = host.readStoredCollectionForTests()
        assertTrue(stored.defaultAppearance.enabled)
        assertEquals(MoonlitTier.COMMON, stored.defaultAppearance.seal)
        assertNull(stored.defaultAppearance.edgeTier)
        assertEquals(setOf(MoonlitTier.COMMON), stored.unlocked)
    }

    @Test fun perBookAppearancePersistsAndResetRestoresInheritance() {
        launch()
        chooseTargetBook()
        clickTag("relic-item-quill", forward = false)
        click(revealTag("relic-save"))
        waitFor("book-specific appearance persisted") { onHost { it.saveRequests == 1 && !it.busy && !it.showCabinet } }
        assertNull(onHost { it.failureForTests })
        assertEquals(book.id, onHost { it.lastSavedId })
        assertEquals(MoonlitTier.COMMON, host.readStoredCollectionForTests().bookAppearances.getValue(book.id).seal)
        onHost { it.reopenCabinetForTests() }
        waitFor("cabinet reopened") { findTag("relic-cabinet-scroll") != null }
        chooseTargetBook()
        val reset = revealTag("relic-reset-book")
        assertTrue(reset.isEnabled)
        assertTouchTarget(reset)
        click(reset)
        waitFor("book appearance reset") { onHost { it.resetRequests == 1 && !it.busy && !it.showCabinet } }
        assertNull(onHost { it.failureForTests })
        val stored = host.readStoredCollectionForTests()
        assertFalse(stored.bookAppearances.containsKey(book.id))
        assertEquals(stored.defaultAppearance, stored.appearanceFor(book.id))
    }

    @Test fun twoHundredPercentTextKeepsRelicsPreviewChoicesAndSaveReachable() {
        launch(2f)
        assertEquals(2f, onHost { it.resources.configuration.fontScale }, .01f)
        assertFalse("The fixture starts without decorations", host.readStoredCollectionForTests().defaultAppearance.enabled)
        assertTouchTarget(revealTag("relic-cabinet-close"))
        MoonlitTier.entries.forEach { tier ->
            val tile = revealTag("relic-item-${tier.id}")
            assertTouchTarget(tile)
            assertRelicAnnouncement(tier)
        }
        val selectedCommon = revealTag("relic-item-quill", forward = false)
        assertTrue("Common is already showing its details", selectedCommon.stateDescription?.contains("Details shown") == true)
        assertEquals("Relic presets expose a repeatable button action", "android.widget.Button", selectedCommon.className?.toString())
        assertTrue("The already-selected preset remains activatable", selectedCommon.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })
        click(selectedCommon)
        clickTag("relic-mode")
        click(revealMenuChoice("Standalone title and author cover"))
        assertPreviewFits(revealTag("relic-cover-preview", forward = false))
        screenshot("relic-cabinet-200-common-preview")
        val save = revealTag("relic-save")
        assertTrue("Save remains available at 200% text", save.isEnabled)
        assertTouchTarget(save)
        val viewport = Rect().also { save.window?.getBoundsInScreen(it) }
        val bounds = Rect().also(save::getBoundsInScreen)
        assertTrue("Save fits the visible native window", viewport.isEmpty || viewport.contains(bounds))
        screenshot("relic-cabinet-200-save")
        click(save)
        waitFor("large-text standalone appearance persisted") { onHost { it.saveRequests == 1 && !it.busy } }
        assertNull(onHost { it.failureForTests })
        val saved = host.readStoredCollectionForTests().defaultAppearance
        assertEquals(RelicMode.STANDALONE, saved.mode)
        assertTrue("Activating the already-selected Common preset enables its decorations", saved.enabled)
        assertEquals(MoonlitTier.COMMON, saved.seal)
    }

    @Test fun temporaryTestingAllowsMythicDraftAndSaveWithoutCollectingIt() {
        launch(testingControls = true)
        val before = host.readStoredCollectionForTests()
        clickTag("relic-testing-start")
        waitFor("temporary testing access enabled") { onHost { it.testingActive } }
        clickTag("relic-item-archive")
        waitFor("live Mythic draft delivered") { onHost {
            it.lastPreviewAppearance?.edgeTier == MoonlitTier.MYTHIC && it.lastPreviewAppearance?.motion == true
        } }
        val mythic = revealTag("relic-item-archive")
        assertTrue("Testing access is announced honestly", mythic.stateDescription?.contains("Available for testing; not collected") == true)
        assertEquals("The actual collection remains earned-only", setOf(MoonlitTier.COMMON), host.readStoredCollectionForTests().unlocked)
        clickTag("relic-mode")
        click(revealMenuChoice("Standalone title and author cover"))
        waitFor("changed layout delivered live") { onHost { it.lastPreviewAppearance?.mode == RelicMode.STANDALONE } }
        assertPreviewFits(revealTag("relic-cover-preview", forward = false))
        screenshot("relic-cabinet-testing-mythic-narrow-preview")
        val save = revealTag("relic-save")
        assertTrue("Temporary access permits saving the Mythic test appearance", save.isEnabled)
        click(save)
        waitFor("temporary appearance callback completed") { onHost { it.saveRequests == 1 && !it.busy && !it.showCabinet } }
        waitFor("live preview closed after save") { onHost { it.previewClosedRequests == 1 } }
        assertEquals(MoonlitTier.MYTHIC, onHost { it.lastSavedAppearance?.edgeTier })
        val after = host.readStoredCollectionForTests()
        assertEquals("Temporary testing never awards a relic", before.earned, after.earned)
        assertEquals("Temporary testing never adds reading credit", before.totalMillis, after.totalMillis)
        assertEquals("The UI sends test choices without writing the genuine profile", before.defaultAppearance, after.defaultAppearance)
    }

    @Test fun liveDraftTracksTargetAndDismissalAndTestingCanEnd() {
        launch(testingControls = true)
        waitFor("initial live preview delivered") { onHost { it.previewRequests > 0 && it.lastPreviewId == null } }
        clickTag("relic-testing-start")
        waitFor("temporary testing access enabled") { onHost { it.testingActive } }
        chooseTargetBook()
        waitFor("book target delivered live") { onHost { it.lastPreviewId == book.id } }
        clickTag("relic-item-archive", forward = false)
        waitFor("Mythic target draft delivered live") { onHost { it.lastPreviewAppearance?.edgeTier == MoonlitTier.MYTHIC } }
        clickTag("relic-testing-end", forward = false)
        waitFor("testing access ended and draft reverted") { onHost { !it.testingActive && it.lastPreviewAppearance?.edgeTier == null } }
        clickTag("relic-item-archive")
        assertFalse("Ended testing access restores earned-only Save gating", revealTag("relic-save").isEnabled)
        clickTag("relic-cabinet-close", forward = false)
        waitFor("preview disposal delivered on dismissal") { onHost { it.dismissRequests == 1 && it.previewClosedRequests == 1 } }
        assertEquals(setOf(MoonlitTier.COMMON), host.readStoredCollectionForTests().unlocked)
    }

    private fun chooseTargetBook() {
        clickTag("relic-target")
        click(revealMenuChoice(book.title))
    }

    private fun launch(scale: Float = 1f, testingControls: Boolean = false) {
        RelicCabinetTestActivity.fontScaleForTests = scale
        RelicCabinetTestActivity.testingControlsForTests = testingControls
        scenario = ActivityScenario.launch(Intent(target, RelicCabinetTestActivity::class.java))
        scenario!!.onActivity { host = it }
        book = runBlocking { withTimeout(30_000) { host.ready.await() } }
        waitFor("the real cabinet opens") { findTag("relic-cabinet-scroll") != null }
        assertEquals(setOf(MoonlitTier.COMMON), host.readStoredCollectionForTests().unlocked)
    }

    private fun clickTag(tag: String, forward: Boolean = true) = click(revealTag(tag, forward))

    private fun click(node: AccessibilityNodeInfo) {
        assertTrue("Native accessibility click is supported", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun revealTag(tag: String, forward: Boolean = true): AccessibilityNodeInfo = revealNode(
        tag, forward, { findTag(tag) }, { findTag("relic-cabinet-scroll") },
    ) { node -> tag != "relic-cover-preview" || previewHasFullAspect(node) }

    private fun revealNode(
        description: String,
        forward: Boolean,
        find: () -> AccessibilityNodeInfo?,
        findScroll: () -> AccessibilityNodeInfo?,
        failureDetails: () -> String = { "" },
        extraCheck: (AccessibilityNodeInfo) -> Boolean = { true },
    ): AccessibilityNodeInfo {
        var scrollForward = forward
        fun ready(node: AccessibilityNodeInfo?) = node != null && fullyVisible(node) && extraCheck(node)
        return waitForNode(description, failureDetails) {
            var node = find()
            if (ready(node)) return@waitForNode node
            node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            instrumentation.waitForIdleSync()
            node = find()
            if (ready(node)) return@waitForNode node
            // Native interop wrappers may not implement Show on Screen. Scroll even when a
            // tagged node already exists, and use its clipped edge to choose the direction.
            findScroll()?.let { scroll ->
                val targetBounds = node?.let { Rect().also(it::getBoundsInScreen) }
                val scrollBounds = Rect().also(scroll::getBoundsInScreen)
                if (targetBounds != null && !targetBounds.isEmpty) {
                    if (targetBounds.top <= scrollBounds.top && targetBounds.bottom < scrollBounds.bottom) scrollForward = false
                    else if (targetBounds.bottom >= scrollBounds.bottom && targetBounds.top > scrollBounds.top) scrollForward = true
                }
                if (targetBounds != null && Rect.intersects(targetBounds, scrollBounds)) {
                    // A page can skip over a large preview. A short drag brings a present,
                    // partly visible target into view without alternating between two pages.
                    fineScroll(scrollBounds, scrollForward)
                } else {
                    val accepted = scroll.performAction(if (scrollForward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    if (!accepted) scrollForward = !scrollForward
                }
            }
            instrumentation.waitForIdleSync()
            null
        }
    }

    private fun assertTouchTarget(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val density = onHost { it.uiDensityForTests }
        assertTrue("Action has at least 48 dp height: $bounds at $density", bounds.height() / density >= 47.5f)
        assertTrue("Action has at least 48 dp width: $bounds at $density", bounds.width() / density >= 47.5f)
    }

    /** Native gestures stay inside the isolated, generated-data cabinet's scroll viewport. */
    private fun fineScroll(bounds: Rect, forward: Boolean) {
        val distance = bounds.height() * .12f
        val x = bounds.exactCenterX()
        val startY = bounds.exactCenterY() + if (forward) distance / 2f else -distance / 2f
        val endY = bounds.exactCenterY() - if (forward) distance / 2f else -distance / 2f
        val downAt = SystemClock.uptimeMillis()
        fun inject(action: Int, y: Float) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { assertTrue("Generated cabinet scroll gesture is accepted", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        var ended = false
        try {
            inject(MotionEvent.ACTION_DOWN, startY)
            for (step in 1..6) {
                SystemClock.sleep(20)
                inject(MotionEvent.ACTION_MOVE, startY + (endY - startY) * step / 6f)
            }
            // Hold before lifting so the small adjustment does not become a fling.
            SystemClock.sleep(100)
            inject(MotionEvent.ACTION_MOVE, endY)
            inject(MotionEvent.ACTION_UP, endY)
            ended = true
        } finally {
            if (!ended) inject(MotionEvent.ACTION_CANCEL, endY)
        }
    }

    private fun assertRelicAnnouncement(tier: MoonlitTier) {
        val tag = "relic-item-${tier.id}"
        val milestone = when (tier) {
            MoonlitTier.COMMON -> "5 total reading minutes"
            MoonlitTier.UNCOMMON -> "1 total reading hour"
            MoonlitTier.RARE -> "5 total reading hours"
            MoonlitTier.EPIC -> "15 total reading hours"
            MoonlitTier.LEGENDARY -> "40 total reading hours"
            MoonlitTier.MYTHIC -> "100 total reading hours"
        }
        waitFor("${tier.id} announces ${tier.title} and $milestone", failureDetails = {
            val node = findTag(tag)?.apply { refresh() }
            "expectedTier=${tier.id},expectedTitle=${tier.title},expectedMilestone=$milestone," +
                "actualFullyVisible=${node?.let(::fullyVisible)},${tileSemanticsDiagnostics(node)}"
        }) {
            val fresh = findTag(tag)?.takeIf { it.refresh() }
            val description = fresh?.contentDescription?.toString().orEmpty()
            fresh != null && fullyVisible(fresh) && description.contains(tier.title) && description.contains(milestone)
        }
    }

    private fun tileSemanticsDiagnostics(node: AccessibilityNodeInfo?): String {
        val children = (0 until minOf(node?.childCount ?: 0, 6)).joinToString("; ") { describeNode(node?.getChild(it)) }
        return "tile={${describeNode(node)}},parent={${describeNode(node?.parent)}},children=[$children]"
    }

    private fun describeNode(value: AccessibilityNodeInfo?): String = if (value == null) "absent" else
        "windowId=${value.windowId},tag=${value.viewIdResourceName},description=${value.contentDescription},text=${value.text},state=${value.stateDescription}," +
            "class=${value.className},role=${value.extras.getCharSequence("AccessibilityNodeInfo.roleDescription")}," +
            "clickable=${value.isClickable},actions=${value.actionList.map { it.id }},scrollable=${value.isScrollable}," +
            "bounds=${Rect().also(value::getBoundsInScreen)},visible=${value.isVisibleToUser}"

    private fun previewHasFullAspect(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        return bounds.height() > 0 && kotlin.math.abs(bounds.width().toFloat() / bounds.height() - RelicCabinetTestActivity.outsideAspectRatioForTests) < .025f
    }

    private fun assertPreviewFits(node: AccessibilityNodeInfo) {
        assertTrue("The full outside-screen-shaped preview is visible, rather than a clipped slice", previewHasFullAspect(node))
        assertTrue("The preview announces the generated book title and author",
            node.contentDescription?.let { it.contains(book.title) && it.contains(book.author) } == true)
        val bounds = Rect().also(node::getBoundsInScreen)
        val window = Rect().also { node.window?.getBoundsInScreen(it) }
        assertTrue("The native preview fits within its dialog window", window.isEmpty || window.contains(bounds))
    }

    private fun fullyVisible(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        if (node.isClickable || node.isCheckable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
            val density = host.uiDensityForTests
            if (bounds.width() / density < 47.5f || bounds.height() / density < 47.5f) return false
        }
        val window = Rect().also { node.window?.getBoundsInScreen(it) }
        if (!window.isEmpty) {
            // Popup nodes round transformed coordinates independently of WindowInfo. A visible
            // 48-dp menu row can extend one pixel past its reported window edge on API 36.
            window.inset(-1, -1)
            if (!window.contains(bounds)) return false
        }
        var parent = node.parent
        while (parent != null) {
            if (parent.isScrollable) {
                val clip = Rect().also(parent::getBoundsInScreen)
                // BringIntoView may align a complete target exactly with an edge. The minimum
                // action size and preview aspect check above reject the observed clipped slices.
                if (!clip.isEmpty && !clip.contains(bounds)) return false
            }
            parent = parent.parent
        }
        return true
    }

    private fun findTag(tag: String): AccessibilityNodeInfo? = findNode { it.viewIdResourceName?.substringAfterLast('/') == tag }

    private fun textOwner(text: String, root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node = descend(root) { it.text?.toString()?.trim() == text || it.contentDescription?.toString()?.trim() == text } ?: return null
        while (true) {
            if (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return node
            val parent = node.parent ?: return null
            if (parent.packageName?.toString() != target.packageName || parent.windowId != root.windowId) return null
            node = parent
        }
    }

    private fun revealMenuChoice(text: String): AccessibilityNodeInfo {
        // Dropdown scale-in bounds can briefly be smaller than a usable action. Let the
        // opening settle before considering a scroll; a short, fitting menu needs no gesture.
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        return revealNode(text, true,
            { activePopupRoot()?.let { textOwner(text, it) } },
            { activePopupRoot()?.let { root -> descend(root) { it.isScrollable && it.isVisibleToUser } } },
            failureDetails = { popupDiagnostics(text) },
        )
    }

    private fun activePopupRoot(): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) automation.clearCache()
        // WindowInfo is the authoritative focus source. A virtual node's getWindow() can
        // be absent even while that popup and its exact-label text are in the native tree.
        val focusedPopup = automation.windows.firstNotNullOfOrNull { window ->
            if (window.isFocused) window.root?.takeIf(::isQaPopupRoot) else null
        }
        return focusedPopup ?: automation.rootInActiveWindow?.takeIf(::isQaPopupRoot)
    }

    private fun isQaPopupRoot(root: AccessibilityNodeInfo): Boolean =
        root.packageName?.toString() == target.packageName &&
            descend(root) { it.viewIdResourceName?.substringAfterLast('/') == "relic-cabinet-scroll" } == null

    private fun popupDiagnostics(text: String): String {
        val root = activePopupRoot()
        val leaf = root?.let { descend(it) { node -> node.text?.toString()?.trim() == text || node.contentDescription?.toString()?.trim() == text } }
        val owner = root?.let { textOwner(text, it) }
        val ancestors = mutableListOf<String>()
        var ancestor = leaf?.parent
        while (ancestor != null && ancestors.size < 8) {
            ancestors += describeNode(ancestor)
            ancestor = ancestor.parent
        }
        val focused = automation.windows.filter { it.isFocused }.joinToString { "id=${it.id},package=${it.root?.packageName},bounds=${Rect().also(it::getBoundsInScreen)}" }
        return "expectedMenuLabel=$text,focusedWindowInfo=[$focused],selectedPopupRoot={${describeNode(root)}}," +
            "exactLabelLeaf={${describeNode(leaf)}},actionOwner={${describeNode(owner)}},ownerFullyVisible=${owner?.let(::fullyVisible)},ancestors=$ancestors"
    }

    private fun qaRoots(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) automation.clearCache()
        return (listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root })
            .filter { it.packageName?.toString() == target.packageName }.distinctBy { it.windowId }
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        qaRoots().firstNotNullOfOrNull { descend(it, predicate) }

    private fun descend(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { child -> descend(child, predicate)?.let { return it } }
        return null
    }

    private fun waitFor(description: String, failureDetails: () -> String = { "" }, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(80)
        }
        val image = failureScreenshot()
        val details = runCatching { "${failureDetails()}; ${fixtureDiagnostics()}" }
            .getOrElse { "diagnostics unavailable: ${it.javaClass.simpleName}" }
        throw AssertionError("Timed out waiting for $description in the isolated generated relic fixture; $details; failureScreenshot=$image")
    }

    /** Tags, window labels and bounds from this generated-data fixture only. */
    private fun fixtureDiagnostics(): String {
        val nodes = mutableListOf<String>()
        findNode { node ->
            val tag = node.viewIdResourceName?.substringAfterLast('/').orEmpty()
            if (tag.startsWith("relic-") && nodes.size < 28) {
                nodes += "$tag: bounds=${Rect().also(node::getBoundsInScreen)},visible=${node.isVisibleToUser},enabled=${node.isEnabled},actions=${node.actionList.map { it.id }}"
            }
            false
        }
        val configuration = onHost { "density=${it.uiDensityForTests},fontScale=${it.resources.configuration.fontScale},viewport=${it.resources.configuration.screenWidthDp}x${it.resources.configuration.screenHeightDp}dp" }
        val windows = qaRoots().take(3).joinToString("; ") { root ->
            val window = root.window
            val labels = linkedSetOf<String>()
            descend(root) { node ->
                val label = listOfNotNull(node.text, node.contentDescription).map { it.toString().trim() }
                    .filter { it.isNotEmpty() }.distinct().joinToString(" / ").take(180)
                if (label.isNotEmpty() && labels.size < 16) {
                    labels += "$label: class=${node.className},bounds=${Rect().also(node::getBoundsInScreen)},click=${node.isClickable}"
                }
                false
            }
            "window=${root.windowId},focused=${window?.isFocused},active=${window?.isActive},layer=${window?.layer}," +
                "bounds=${Rect().also { window?.getBoundsInScreen(it) }},labels=$labels"
        }
        return "$configuration; ${nodes.joinToString("; ")}; QA windows=[$windows]"
    }

    private fun failureScreenshot(): String = runCatching {
        val focusedQa = automation.windows.any { it.isFocused && it.root?.packageName?.toString() == target.packageName }
        if (!focusedQa) "skipped: QA is not focused"
        else screenshot("relic-cabinet-failure-${SystemClock.uptimeMillis()}") ?: "skipped: no test output directory"
    }.getOrElse { "not captured: ${it.javaClass.simpleName}" }

    private fun waitForNode(description: String, failureDetails: () -> String = { "" }, node: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        waitFor(description, failureDetails) { result = node(); result != null }
        return result!!
    }

    private fun <T> onHost(action: (RelicCabinetTestActivity) -> T): T {
        var result: T? = null
        scenario!!.onActivity { result = action(it) }
        @Suppress("UNCHECKED_CAST") return result as T
    }

    private fun screenshot(name: String): String? {
        val folder = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return null
        instrumentation.waitForIdleSync()
        val image = automation.takeScreenshot() ?: throw AssertionError("Could not capture generated cabinet preview")
        File(folder).mkdirs()
        FileOutputStream(File(folder, "$name.png")).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        return File(folder, "$name.png").absolutePath
    }
}
