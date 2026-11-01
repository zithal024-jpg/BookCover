package com.bookcover.diagnostic.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.library.LibraryThemeChoice
import com.bookcover.diagnostic.library.coverColorThemeId
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
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

    @Test fun readingStreakCardFitsLabelsDaysAndOpensDetails() = checkReadingStreak(1f, "reading-streak-100")

    @Test fun readingStreakCardStaysUsableAtTwoHundredPercentText() = checkReadingStreak(2f, "reading-streak-200")

    /** Three completed days and two minutes today, on the folded 360 dp layout; saves screenshots as test output. */
    private fun checkReadingStreak(fontScale: Float, shot: String) {
        launch(fontScale)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun hour(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        main { withContext(Dispatchers.IO) {
            host.libraryForTests.readingDays.apply {
                listOf(3L, 2L, 1L).forEach { back -> today.minusDays(back).let { add(hour(it, 12), hour(it, 12) + 6 * 60_000L) } }
                add(hour(today, 0) + 60_000L, hour(today, 0) + 3 * 60_000L)
            }
        } }
        fun streakCard() = findNode { it.viewIdResourceName?.substringAfterLast('/') == "reading-streak" }
        // Android reports only the visible part of a clipped node, so require clear space on both edges.
        waitFor("the streak card is entirely in view") {
            val scroll = findNode { it.viewIdResourceName?.substringAfterLast('/') == "library-scroll" } ?: return@waitFor false
            val clip = Rect().also(scroll::getBoundsInScreen)
            val node = streakCard()
            val bounds = node?.let { Rect().also(it::getBoundsInScreen) }
            if (node != null && bounds != null && !bounds.isEmpty && bounds.top > clip.top && bounds.bottom < clip.bottom) return@waitFor true
            if (node != null) node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            else scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) // Off-screen nodes are not reported.
            instrumentation.waitForIdleSync()
            SystemClock.sleep(150)
            false
        }
        waitFor("the card shows the streak") { findNode { it.text?.contains("3-day streak") == true } != null }
        val card = streakCard()!!
        assertTrue("Today's progress is shown", findNode { it.text?.contains("2 of 5 minutes today") == true } != null)
        val density = main { host.resources.displayMetrics.density }
        val bounds = Rect().also(card::getBoundsInScreen)
        assertTrue("The card is a comfortable touch target", bounds.height() / density >= 47.5f)
        assertTrue("The card fits the 360 dp library width", bounds.width() / density <= 360f)
        assertTrue("The card is fully visible", fullyVisible(card))
        saveScreenshot("$shot-card")
        val dayLabels = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            node.contentDescription?.toString()?.takeIf { it.contains(", ") }?.let(dayLabels::add)
            for (i in 0 until node.childCount) collect(node.getChild(i))
        }
        collect(card)
        listOf("Today, 2 of 5 minutes read", "${today.minusDays(1).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}, reading goal completed")
            .forEach { label -> assertTrue("Day labelled: $label; found $dayLabels", label in dayLabels) }
        assertTrue("The card opens through accessibility", card.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitFor("the streak details open") { find("Reading streak") != null && find("Longest streak") != null }
        val calendarToday = "Today, " + today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())) + ", 2 minutes read"
        waitFor("today is labelled in the calendar") {
            if (findNode { it.contentDescription?.toString() == calendarToday } != null) return@waitFor true
            findNode { it.viewIdResourceName?.substringAfterLast('/') == "reading-streak-details" }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) // Off-screen cells are not reported.
            SystemClock.sleep(150)
            false
        }
        reveal("Read for 5 minutes in a day to build your streak.", dialog = true)
        saveScreenshot("$shot-details")
        click("Done")
        assertNull("The details close", find("Reading streak"))
    }

    /** Written where AGP collects additional test output, when it provides that folder. */
    private fun saveScreenshot(name: String) {
        val folder = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
        instrumentation.waitForIdleSync()
        SystemClock.sleep(1_200) // Let any entry animation finish.
        automation.takeScreenshot()?.let { bitmap ->
            FileOutputStream(File(folder, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
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
        click("Appearance", dialog = true)
        val automatic = reveal("Automatic (Book Cover)", dialog = true)
        assertTrue("The automatic theme exposes selected state; ${selectionDiagnostics(automatic)}", selected(automatic))
        click("Paper · light", dialog = true)
        waitFor("the new theme is selected") { find("Paper · light")?.let(::selected) == true }
        assertEquals("paper", main { host.themeId })
        click("Back to Library settings", dialog = true)
        click("Outside cover", dialog = true)
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
        click("Import books")
        assertEquals("Import can be activated through accessibility", 1, main { host.importRequests })
        click("Open ${book.title}, by ${book.author}")
        assertEquals("The full book label opens the expected book", book.id, main { host.openedBookId })
        val options = reveal("Options for ${book.title}")
        assertTrue("The options target remains usable with large text", fullyVisible(options))
        click("Settings", forward = false)
        click("Appearance", dialog = true)
        click("Paper · light", dialog = true)
        assertEquals("Large text preserves the theme action", "paper", main { host.themeId })
        click("Plum", dialog = true)
        assertEquals("The last built-in preset stays reachable with large text", "plum", main { host.themeId })
        click("${book.title} · #19A6D1", dialog = true)
        assertEquals("The cover colour stays reachable with large text", coverColorThemeId(LibraryAccessibilityActivity.TEST_COVER_SEED), main { host.themeId })
        click("Back to Library settings", dialog = true)
        listOf("Appearance", "Outside cover", "Backup", "About").forEach { category ->
            assertTrue("$category is reachable with large text", fullyVisible(reveal(category, dialog = true)))
        }
        click("Outside cover", forward = false, dialog = true)
        click("Outside cover timer: manual", dialog = true)
        assertEquals(0, main { host.timer })
        click("Back to Library settings", dialog = true)
        click("About", dialog = true)
        repeat(7) { click("BookCover 0.4.0 prototype", dialog = true) }
        assertTrue("The revealed diagnostic action stays reachable with large text", fullyVisible(reveal("Device diagnostics", dialog = true)))
        click("Back to Library settings", dialog = true)
        click("Done", dialog = true)
        waitFor("the settings dialog closes") { find("Done") == null }
    }

    @Test fun compactCategoriesKeepEveryPresetAndTimerOptionReachable() {
        launch()
        click("Settings")
        listOf("Appearance", "Outside cover", "Backup", "About").forEach { category ->
            val row = reveal(category, dialog = true)
            val bounds = Rect().also(row::getBoundsInScreen)
            assertTrue("$category has a 48 dp target", bounds.height() / main { host.resources.displayMetrics.density } >= 47.5f)
        }
        assertNull("The full theme list is absent from the compact first level", find("Paper · light"))
        assertNull("Backup actions stay inside Backup", find("Save backup"))
        click("Appearance", forward = false, dialog = true)
        LibraryThemeChoice.entries.forEach { choice ->
            click(choice.label, dialog = true)
            waitFor("${choice.label} remains selectable") { main { host.themeId } == choice.id && find(choice.label)?.let(::selected) == true }
        }
        click("Back to Library settings", dialog = true)
        click("Outside cover", dialog = true)
        listOf("Outside cover timer: 30 minutes" to 30, "Outside cover timer: 60 minutes" to 60,
            "Outside cover timer: manual" to 0).forEach { (label, value) ->
            click(label, dialog = true)
            waitFor("$label is selected") { main { host.timer } == value && find(label)?.let(::selected) == true }
        }
    }

    @Test fun backDismissAndReopenKeepValuesAndRestoreThePreviousLevel() {
        launch()
        click("Settings")
        click("Appearance", dialog = true)
        click("Paper · light", dialog = true)
        pressBack()
        waitFor("Back returns to the compact settings level") { find("Library settings") != null && find("Appearance")?.isClickable == true }
        assertNull("The picker closes on Back", find("Library theme"))
        click("Outside cover", dialog = true)
        click("Start outside cover when opening a book", dialog = true)
        click("Outside cover timer: 60 minutes", dialog = true)
        click("Done", dialog = true)
        waitFor("Done returns to the library") { find("Done") == null && find("Settings") != null }
        click("Settings")
        assertEquals("The Appearance summary reflects the chosen theme", "Paper · light", reveal("Appearance", dialog = true).stateDescription?.toString())
        click("Outside cover", dialog = true)
        assertTrue(reveal("Start outside cover when opening a book", dialog = true).isChecked)
        assertTrue(selected(reveal("Outside cover timer: 60 minutes", dialog = true)))
        pressBack()
        waitFor("Back returns from Outside cover to the first level") { find("Library settings") != null }
        pressBack()
        waitFor("Back from the first level dismisses to the library") { find("Done") == null }
        click("Settings")
        click("Appearance", dialog = true)
        tapOutsideSettings()
        waitFor("An outside tap dismisses the focused page to the library") { find("Done") == null && find("Settings") != null }
    }

    @Test fun coverColorSelectionSurvivesActivityRestartWithCoverAndTimerPreferences() {
        launch()
        click("Settings")
        click("Appearance", dialog = true)
        val label = "${book.title} · #19A6D1"
        click(label, dialog = true)
        assertEquals(coverColorThemeId(LibraryAccessibilityActivity.TEST_COVER_SEED), main { host.themeId })
        assertTrue(selected(reveal(label, dialog = true)))
        val automatic = reveal("Automatic (Book Cover)", forward = false, dialog = true)
        assertFalse("A dynamic selection must not also select Automatic", selected(automatic))
        click("Back to Library settings", dialog = true)
        click("Outside cover", dialog = true)
        click("Start outside cover when opening a book", dialog = true)
        click("Outside cover timer: manual", dialog = true)
        scenario!!.recreate()
        scenario!!.onActivity { host = it }
        book = main { withTimeout(20_000) { host.ready.await() } }
        waitFor("the recreated library renders") { find("Settings") != null }
        assertEquals(coverColorThemeId(LibraryAccessibilityActivity.TEST_COVER_SEED), main { host.themeId })
        assertTrue(main { host.autoCover })
        assertEquals(0, main { host.timer })
        click("Settings")
        click("Appearance", dialog = true)
        assertTrue("The persisted derived colour is still selected", selected(reveal(label, dialog = true)))
    }

    @Test fun backupActionsKeepBusyGuardsAndDismissBeforeInvokingCallbacks() {
        launch()
        click("Settings")
        click("Backup", dialog = true)
        main { host.busy = true }
        waitFor("both backup actions disable while busy") {
            find("Save backup")?.isEnabled == false && find("Restore backup")?.isEnabled == false
        }
        assertEquals(0, main { host.exportRequests })
        assertEquals(0, main { host.restoreRequests })
        main { host.busy = false }
        waitFor("both backup actions re-enable after work finishes") {
            find("Save backup")?.isEnabled == true && find("Restore backup")?.isEnabled == true
        }
        click("Save backup", dialog = true)
        waitFor("Save invokes its callback and returns to the library") { main { host.exportRequests } == 1 && find("Done") == null }
        click("Settings")
        click("Backup", dialog = true)
        click("Restore backup", dialog = true)
        waitFor("Restore invokes its callback and returns to the library") { main { host.restoreRequests } == 1 && find("Done") == null }
    }

    @Test fun noticesLicencesAndSevenTapDiagnosticEntryRemainAvailable() {
        launch()
        click("Settings")
        click("About", dialog = true)
        waitFor("About publishes the retained Readium and OpenDyslexic notices") {
            find("Reading engine: Readium 3.4.0 · BSD 3-Clause\nOpenDyslexic font by Abelardo Gonzalez · CC BY 3.0, based on Bitstream Vera") != null
        }
        assertNull("Diagnostics start hidden", find("Device diagnostics"))
        click("Licences", dialog = true)
        click("Show full licence for OpenDyslexic font")
        waitFor("the bundled font licence text expands") { find("Hide full licence for OpenDyslexic font") != null }
        click("Back to About", forward = false)
        waitFor("Licences Back returns to About") { find("About") != null && find("BookCover 0.4.0 prototype") != null }
        click("Licences", dialog = true)
        pressBack()
        waitFor("System Back also returns from Licences to About") { find("About") != null && find("BookCover 0.4.0 prototype") != null }
        repeat(6) { click("BookCover 0.4.0 prototype", dialog = true) }
        assertNull("Six taps do not reveal diagnostics", find("Device diagnostics"))
        click("BookCover 0.4.0 prototype", dialog = true)
        reveal("Device diagnostics", dialog = true)
        click("Done", dialog = true)
        click("Settings")
        click("About", dialog = true)
        click("Device diagnostics", dialog = true)
        waitFor("unlocked diagnostics survive reopen and invoke the callback") { main { host.diagnosticRequests } == 1 && find("Done") == null }
    }

    private fun pressBack() {
        waitFor("the current settings dialog receives keyboard Back") {
            val window = (find("Back to About") ?: find("Done"))?.window
            window?.isFocused == true && window.isActive
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()
    }

    private fun tapOutsideSettings() {
        val dialogBounds = Rect().also { find("Done")!!.window.getBoundsInScreen(it) }
        val hostBounds = main { Rect().also { host.window.decorView.getGlobalVisibleRect(it) } }
        val leftGap = dialogBounds.left - hostBounds.left
        val topGap = dialogBounds.top - hostBounds.top
        assertTrue("The dialog leaves a dismissible outside area", leftGap > 4 || topGap > 4)
        val x = if (leftGap > 4) (hostBounds.left + dialogBounds.left) / 2f else dialogBounds.centerX().toFloat()
        val y = if (leftGap > 4) dialogBounds.centerY().toFloat() else (hostBounds.top + dialogBounds.top) / 2f
        val downAt = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val up = MotionEvent.obtain(downAt, downAt + 40, MotionEvent.ACTION_UP, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertTrue(automation.injectInputEvent(down, true))
            assertTrue(automation.injectInputEvent(up, true))
        } finally {
            down.recycle(); up.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    private fun launch(fontScale: Float = 1f) {
        LibraryAccessibilityActivity.fontScaleForTests = fontScale
        val intent = Intent(target, LibraryAccessibilityActivity::class.java).putExtra("settingsSession", UUID.randomUUID().toString())
        scenario = ActivityScenario.launch<LibraryAccessibilityActivity>(intent).also {
            it.onActivity { activity -> host = activity }
        }
        book = main { withTimeout(20_000) { host.ready.await() } }
        waitFor("the generated library renders") { find("Settings") != null }
    }

    private fun click(label: String, forward: Boolean = true, dialog: Boolean = false) {
        reveal(label, forward, dialog)
        // A just-opened dialog or re-enabled control may still have an old accessibility node.
        // Retry only rejected actions, obtaining a fresh node each time; an accepted click is never repeated.
        waitFor("$label accepts an enabled accessibility click") {
            val node = find(label)
            node != null && fullyVisible(node) && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
        // Accessibility actions can be accepted before the next frame/window is published.
        // Wait for the destination itself, so a root summary cannot masquerade as a picker row.
        when (label) {
            "Settings", "Back to Library settings" -> waitFor("the compact settings level renders") {
                find("Library settings") != null && find("Appearance") != null && find("Library theme") == null
            }
            "Appearance" -> waitFor("the Appearance picker renders") {
                find("Library theme") != null || (find("Appearance")?.isClickable == false &&
                    find("Back to Library settings") != null && find("Library settings") == null)
            }
            "Outside cover" -> waitFor("the Outside cover controls render") { find("Outside cover timer") != null }
            "Backup" -> waitFor("the Backup actions render") { find("Save backup") != null }
            "About", "Back to About" -> waitFor("the About controls render") {
                find("Reading engine: Readium 3.4.0 · BSD 3-Clause\nOpenDyslexic font by Abelardo Gonzalez · CC BY 3.0, based on Bitstream Vera") != null &&
                    find("Back to About") == null
            }
            "Licences" -> waitFor("the Licences window receives focus") {
                val window = find("Back to About")?.window
                window?.isFocused == true && window.isActive
            }
            "Done" -> waitFor("settings dismisses to the library") { find("Done") == null && find("Settings") != null }
        }
    }

    private fun reveal(label: String, forward: Boolean = true, dialog: Boolean = false): AccessibilityNodeInfo {
        var scrollForward = forward
        waitFor("$label is fully visible and actionable") {
            val node = find(label)
            if (node != null && fullyVisible(node)) return@waitFor true
            node?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            instrumentation.waitForIdleSync()
            if (find(label)?.let(::fullyVisible) == true) return@waitFor true
            val scroll = if (dialog) find("Done")?.window?.root?.let { root ->
                descend(root) { it.isScrollable && it.isVisibleToUser }
            }
                else findNode { it.viewIdResourceName?.substringAfterLast('/') == "library-scroll" }
            if (scroll != null) {
                val targetBounds = find(label)?.let { Rect().also(it::getBoundsInScreen) }
                val scrollBounds = Rect().also(scroll::getBoundsInScreen)
                // A partially clipped target tells us which edge to bring into view.
                if (targetBounds != null && !targetBounds.isEmpty) {
                    if (targetBounds.top <= scrollBounds.top && targetBounds.bottom < scrollBounds.bottom) scrollForward = false
                    else if (targetBounds.bottom >= scrollBounds.bottom && targetBounds.top > scrollBounds.top) scrollForward = true
                }
                val accepted = scroll.performAction(if (scrollForward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                // At an exhausted edge, search in the other direction instead of staying stuck there.
                if (!accepted) scrollForward = !scrollForward
            }
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
