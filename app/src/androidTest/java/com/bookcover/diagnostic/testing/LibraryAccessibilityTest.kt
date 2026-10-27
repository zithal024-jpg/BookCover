package com.bookcover.diagnostic.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.library.BookRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Checks Android's actual accessibility tree/actions on generated books, at normal and 200% text. */
@RunWith(AndroidJUnit4::class)
class LibraryAccessibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private val target get() = instrumentation.targetContext
    private var scenario: ActivityScenario<LibraryAccessibilityActivity>? = null
    private lateinit var host: LibraryAccessibilityActivity
    private lateinit var book: BookRecord
    private var previousFlags = 0

    @Before fun enableWindowInspection() {
        check(target.packageName.endsWith(".qa")) { "Accessibility tests require the isolated QA app." }
        automation.serviceInfo = automation.serviceInfo.apply {
            previousFlags = flags
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
    }

    @After fun closeHost() {
        scenario?.close()
        LibraryAccessibilityActivity.fontScaleForTests = 1f
        automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
    }

    @Test fun bookOptionsHaveALabelAndA48DpTarget() {
        launch()
        val options = reveal("Options for ${book.title}")
        val bounds = Rect().also(options::getBoundsInScreen)
        val density = main { host.resources.displayMetrics.density }
        assertTrue("Book options are at least 48 dp wide", bounds.width() / density >= 47.5f)
        assertTrue("Book options are at least 48 dp tall", bounds.height() / density >= 47.5f)
        assertTrue("Options expose an accessibility click", options.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitFor("the labeled book menu opens") { find("Move to collection")?.isVisibleToUser == true }
    }

    @Test fun sortCollectionThemeAndCoverSwitchExposeTheirState() {
        launch()
        assertTrue("Recent sort is selected", selected(reveal("Sort by Recent")))
        click("Sort by Title")
        waitFor("the new sort exposes selected state") { find("Sort by Title")?.let(::selected) == true }
        assertFalse("The previous sort is no longer selected", selected(find("Sort by Recent")!!))
        assertTrue("All collections starts selected", selected(reveal("Collection: All", forward = false)))
        click("Collection: Samples", forward = false)
        waitFor("the collection exposes selected state") { find("Collection: Samples")?.let(::selected) == true }
        click("Settings", forward = false)
        val automatic = reveal("Automatic (Book Cover)", dialog = true)
        assertTrue("The automatic theme exposes selected state; ${selectionDiagnostics(automatic)}", selected(automatic))
        click("Paper · light", dialog = true)
        waitFor("the new theme is selected") { find("Paper · light")?.let(::selected) == true }
        assertEquals("paper", main { host.themeId })
        val coverSwitch = reveal("Start outside cover when opening a book", dialog = true)
        assertTrue("The cover switch is checkable", coverSwitch.isCheckable)
        assertFalse("The cover switch starts off", coverSwitch.isChecked)
        assertTrue(coverSwitch.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitFor("the named switch is checked") {
            main { host.autoCover } && find("Start outside cover when opening a book")?.isChecked == true
        }
    }

    @Test fun twoHundredPercentTextKeepsImportBooksOptionsAndSettingsReachable() {
        launch(fontScale = 2f)
        assertEquals("Only this QA activity uses 200% system text", 2f, main { host.resources.configuration.fontScale }, 0.01f)
        click("Import EPUB")
        assertEquals("Import can be activated through accessibility", 1, main { host.importRequests })
        click("Open ${book.title}, by ${book.author}")
        assertEquals("The full book label opens the expected book", book.id, main { host.openedBookId })
        val options = reveal("Options for ${book.title}")
        assertTrue("The options target remains usable with large text", fullyVisible(options))
        click("Settings", forward = false)
        click("Paper · light", dialog = true)
        assertEquals("Large text preserves the theme action", "paper", main { host.themeId })
        click("Done", dialog = true)
        waitFor("the settings dialog closes") { find("Library settings") == null }
    }

    private fun launch(fontScale: Float = 1f) {
        LibraryAccessibilityActivity.fontScaleForTests = fontScale
        val intent = Intent(target, LibraryAccessibilityActivity::class.java)
        scenario = ActivityScenario.launch<LibraryAccessibilityActivity>(intent).also {
            it.onActivity { activity -> host = activity }
        }
        book = main { withTimeout(20_000) { host.ready.await() } }
        waitFor("the generated library renders") { find("Settings") != null }
    }

    private fun click(label: String, forward: Boolean = true, dialog: Boolean = false) {
        val node = reveal(label, forward, dialog)
        assertTrue("$label exposes an accessibility click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun reveal(label: String, forward: Boolean = true, dialog: Boolean = false): AccessibilityNodeInfo {
        waitFor("$label is fully visible and actionable") {
            val node = find(label)
            if (node != null && fullyVisible(node)) return@waitFor true
            node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            instrumentation.waitForIdleSync()
            if (find(label)?.let(::fullyVisible) == true) return@waitFor true
            val scroll = if (dialog) find("Library settings")?.window?.root?.let { root ->
                descend(root) { it.isScrollable && it.isVisibleToUser }
            }
                else findNode { it.viewIdResourceName?.substringAfterLast('/') == "library-scroll" }
            scroll?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(100)
            false
        }
        return find(label)!!
    }

    private fun selected(node: AccessibilityNodeInfo): Boolean {
        if (node.isSelected || (node.isCheckable && node.isChecked)) return true
        // Android/Compose may announce a radio row's Selected semantics through its state
        // description rather than isSelected. Check only this exact row's declared state.
        val state = node.stateDescription?.toString().orEmpty()
        return state.contains("selected", ignoreCase = true) &&
            !state.contains("not selected", ignoreCase = true) && !state.contains("unselected", ignoreCase = true)
    }

    private fun selectionDiagnostics(node: AccessibilityNodeInfo): String {
        fun describe(candidate: AccessibilityNodeInfo?): String {
            if (candidate == null) return "absent"
            return "class=${candidate.className},text=${candidate.text},label=${candidate.contentDescription}," +
                "click=${candidate.isClickable},selected=${candidate.isSelected},checkable=${candidate.isCheckable}," +
                "checked=${candidate.isChecked},state=${candidate.stateDescription},role=${candidate.extras.getCharSequence("AccessibilityNodeInfo.roleDescription")}," +
                "bounds=${Rect().also(candidate::getBoundsInScreen)}"
        }
        val children = (0 until minOf(node.childCount, 6)).joinToString("; ") { index -> describe(node.getChild(index)) }
        return "row={${describe(node)}}; parent={${describe(node.parent)}}; children=[$children]"
    }

    private fun fullyVisible(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty) return false
        val window = Rect().also { node.window?.getBoundsInScreen(it) }
        if (!window.isEmpty && !window.contains(bounds)) return false
        var ancestor = node.parent
        while (ancestor != null) {
            if (ancestor.isScrollable) {
                val clip = Rect().also(ancestor::getBoundsInScreen)
                if (!clip.isEmpty && !clip.contains(bounds)) return false
            }
            ancestor = ancestor.parent
        }
        return true
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        val labeledNode = findNode { node ->
            node.contentDescription?.toString()?.trim() == label || node.text?.toString()?.trim() == label
        } ?: return null
        if (label == "Library settings") return labeledNode
        // Compose can expose a button's text as a child of the clickable semantics owner.
        // Resolve the action owner so assertions inspect its selected/checkable state too.
        var owner: AccessibilityNodeInfo? = labeledNode
        while (owner != null && owner.packageName?.toString() == target.packageName) {
            if (owner.isClickable || owner.isCheckable || owner.isSelected || owner.stateDescription != null ||
                owner.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return owner
            owner = owner.parent
        }
        return labeledNode
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) automation.clearCache()
        return (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
            .onEach { it.refresh() }.filter { it.packageName?.toString() == target.packageName }
            .distinctBy { it.windowId }
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        roots().firstNotNullOfOrNull { descend(it, predicate) }

    private fun descend(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let { child ->
            descend(child, predicate)?.let { return it }
        }
        return null
    }

    private fun <T> main(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { action() } }

    /** Only the isolated generated-sample host is inspected; never dumps a personal library. */
    private fun accessibilityDiagnostics(): String {
        val nodes = mutableListOf<String>()
        fun inspect(node: AccessibilityNodeInfo) {
            if (nodes.size >= 32) return
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotBlank() || description.isNotBlank() || node.isScrollable) {
                val bounds = Rect().also(node::getBoundsInScreen)
                nodes += "text=${text.take(90)},label=${description.take(110)},id=${node.viewIdResourceName}," +
                    "click=${node.isClickable},selected=${node.isSelected},checked=${node.isChecked},state=${node.stateDescription}," +
                    "visible=${node.isVisibleToUser},bounds=$bounds,actions=${node.actionList.map { it.id }}"
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(::inspect)
        }
        val currentRoots = roots()
        currentRoots.forEach(::inspect)
        val viewport = if (::host.isInitialized) main {
            "${host.window.decorView.width}x${host.window.decorView.height}px," +
                "${host.resources.configuration.screenWidthDp}dp,fontScale=${host.resources.configuration.fontScale}"
        } else "host unavailable"
        return "viewport=$viewport; QA windows=${currentRoots.size}; ${nodes.joinToString("; ")}"
    }

    private fun waitFor(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (failure: Throwable) { lastFailure = failure }
            SystemClock.sleep(60)
        }
        val diagnostics = runCatching { accessibilityDiagnostics() }.getOrElse { "diagnostics failed: ${it.javaClass.simpleName}" }
        throw AssertionError("Timed out waiting for $description; $diagnostics", lastFailure)
    }
}
