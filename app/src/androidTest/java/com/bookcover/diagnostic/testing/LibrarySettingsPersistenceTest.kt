package com.bookcover.diagnostic.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.BookLibrary
import com.bookcover.diagnostic.library.coverColorChoices
import com.bookcover.diagnostic.library.libraryThemeLabel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real settings callbacks and saved preferences, exclusively in the isolated QA app. */
@RunWith(AndroidJUnit4::class)
class LibrarySettingsPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val preferences get() = target.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val keys = setOf("libraryTheme", "autoCover", "coverTimer")
    private var saved: Map<String, Any?>? = null
    private var previousFlags: Int? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private var addedSample: String? = null
    private lateinit var library: BookLibrary

    @Before fun prepareQaPreferences() {
        check(target.packageName.endsWith(".qa")) { "Settings persistence tests require the isolated QA app." }
        saved = preferences.all.filterKeys { it in keys }
        previousFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        assertTrue(preferences.edit().putString("libraryTheme", "cover:25a7b9")
            .putBoolean("autoCover", true).putInt("coverTimer", 30).commit())
    }

    @After fun restoreQaPreferences() {
        scenario?.close()
        if (::library.isInitialized) addedSample?.let { id -> runBlocking { library.remove(id) } }
        saved?.let { values ->
            val edit = preferences.edit()
            keys.forEach { edit.remove(it) }
            values.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
            } }
            assertTrue(edit.commit())
        }
        previousFlags?.let { before -> automation.serviceInfo = automation.serviceInfo.apply { flags = before } }
    }

    @Test fun realCallbacksAndCoverSelectionSurviveClosingAndRelaunchingTheActivity() {
        launch()
        library = onActivity { it.libraryForTests }
        var colors = runBlocking { library.bookCoverColors() }
        if (colors.isEmpty()) {
            val (book, added) = runBlocking { library.importSample("samples/lantern-keeper.epub") }
            if (added) addedSample = book.id
            colors = runBlocking { library.bookCoverColors() }
        }
        val detachedId = "cover:25a7b9"
        val detached = coverColorChoices(colors, detachedId).first { it.id == detachedId }
        click("Settings")
        waitFor("the detached color summary is published") {
            find("Appearance")?.stateDescription?.toString() == libraryThemeLabel(detachedId)
        }
        click("Appearance")
        assertTrue("A saved detached cover choice loads selected", selected(reveal(detached.label)))
        click("Paper · light", forward = false)
        waitFor("the preset callback persists its value") { preferences.getString("libraryTheme", null) == "paper" }
        val liveChoice = coverColorChoices(colors, "paper").first()
        click(liveChoice.label)
        waitFor("the live cover callback persists its RGB selection") {
            preferences.getString("libraryTheme", null) == liveChoice.id && find(liveChoice.label)?.let(::selected) == true
        }
        click("Back to Library settings")
        click("Outside cover")
        assertTrue(reveal("Start outside cover when opening a book").isChecked)
        click("Start outside cover when opening a book")
        click("Outside cover timer: manual")
        waitFor("the cover controls persist") { !preferences.getBoolean("autoCover", true) && preferences.getInt("coverTimer", -1) == 0 }
        click("Done")
        scenario!!.close()
        scenario = null

        launch()
        click("Settings")
        waitFor("the saved appearance and cover summaries are published after relaunch") {
            find("Appearance")?.stateDescription?.toString() == libraryThemeLabel(liveChoice.id) &&
                find("Outside cover")?.stateDescription?.toString() == "Start manually · Manual timer"
        }
        click("Appearance")
        assertTrue("The source cover remains selected after relaunch", selected(reveal(liveChoice.label)))
        assertFalse("Automatic does not replace the explicit cover selection", selected(reveal("Automatic (Book Cover)", forward = false)))
        click("Back to Library settings")
        click("Outside cover")
        assertFalse("Automatic outside-cover startup remains off", reveal("Start outside cover when opening a book").isChecked)
        assertTrue("Manual timer remains selected", selected(reveal("Outside cover timer: manual")))
        click("Done")
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        waitFor("the real library loads") { !onActivity { it.busyForTests } && onActivity { it.showsLibraryForTests } && find("Settings") != null }
    }

    private fun <T> onActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        scenario!!.onActivity { result = runCatching { block(it) } }
        return result!!.getOrThrow()
    }

    private fun click(label: String, forward: Boolean = true) {
        reveal(label, forward)
        waitFor("$label exposes a current enabled click action") {
            find(label)?.let { it.isEnabled && fullyVisible(it) && it.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
        }
        instrumentation.waitForIdleSync()
    }

    private fun reveal(label: String, forward: Boolean = true): AccessibilityNodeInfo {
        waitFor("the requested settings control becomes visible") {
            find(label)?.let { if (fullyVisible(it)) return@waitFor true
                it.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) }
            instrumentation.waitForIdleSync()
            if (find(label)?.let(::fullyVisible) == true) return@waitFor true
            val root = find("Done")?.window?.root
            (root?.let { descend(it) { node -> node.isScrollable && node.isVisibleToUser } }
                ?: findNode { it.isScrollable && it.isVisibleToUser })
                ?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            false
        }
        return find(label)!!
    }

    private fun selected(node: AccessibilityNodeInfo): Boolean {
        if (node.isSelected || node.isCheckable && node.isChecked) return true
        val state = node.stateDescription?.toString().orEmpty()
        return state.contains("selected", true) && !state.contains("not selected", true) && !state.contains("unselected", true)
    }

    private fun fullyVisible(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (!node.isVisibleToUser || bounds.isEmpty) return false
        val window = Rect().also { node.window?.getBoundsInScreen(it) }
        if (!window.isEmpty && !window.contains(bounds)) return false
        var parent = node.parent
        while (parent != null) {
            if (parent.isScrollable && !Rect().also(parent::getBoundsInScreen).contains(bounds)) return false
            parent = parent.parent
        }
        return true
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        val node = findNode { it.text?.toString()?.trim() == label || it.contentDescription?.toString()?.trim() == label } ?: return null
        var owner: AccessibilityNodeInfo? = node
        while (owner != null && owner.packageName?.toString() == target.packageName) {
            if (owner.isClickable || owner.isCheckable || owner.isSelected || owner.stateDescription != null) return owner
            owner = owner.parent
        }
        return node
    }

    private fun findNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) automation.clearCache()
        return (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
            .filter { it.packageName?.toString() == target.packageName }
            .firstNotNullOfOrNull { descend(it, predicate) }
    }

    private fun descend(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) node.getChild(index)?.let { descend(it, predicate)?.let { found -> return found } }
        return null
    }

    private fun waitFor(description: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        var failure: Throwable? = null
        while (SystemClock.uptimeMillis() < end) {
            try { if (predicate()) return } catch (error: Throwable) { failure = error }
            SystemClock.sleep(80)
        }
        throw AssertionError("Timed out waiting for $description", failure)
    }
}
