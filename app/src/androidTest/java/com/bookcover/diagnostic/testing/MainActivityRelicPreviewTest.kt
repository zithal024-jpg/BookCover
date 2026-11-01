package com.bookcover.diagnostic.testing

import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.BookLibrary
import com.bookcover.diagnostic.rewards.MoonlitTier
import com.bookcover.diagnostic.rewards.RelicAppearance
import com.bookcover.diagnostic.rewards.RelicCatalog
import com.bookcover.diagnostic.rewards.RelicSet
import com.bookcover.diagnostic.rewards.RelicTestAccess
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real activity wiring, confined to the QA package; never clears its library or captures books. */
@RunWith(AndroidJUnit4::class)
class MainActivityRelicPreviewTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var testingAccess: RelicTestAccess? = null

    @After fun finishTemporaryQaSession() {
        // Preference commits run on this test worker, not the activity's UI thread.
        try { testingAccess?.end() }
        finally { scenario?.close() }
    }

    @Test fun debugIntentStartsActualTestingAndLivePreviewThenRestoresEarnedSettings() {
        check(target.packageName.endsWith(".qa")) { "Relic activity checks require the separate QA app." }
        val setupAccess = RelicTestAccess(target).also { testingAccess = it }
        assertTrue("The QA build supports explicit temporary testing", setupAccess.supported)
        setupAccess.end()
        val realLibrary = BookLibrary.forApp(target)
        runBlocking { realLibrary.load() }
        assertNull("The genuine QA rewards load before testing", realLibrary.relicError.value)
        val before = realLibrary.relics.collection.value
        val readingDaysBefore = realLibrary.readingDays.days.value

        // Launch the real explicit debug intent directly, as owner deployment does. Avoid a
        // self-start/onNewIntent transition inside ActivityScenario's managed lifecycle.
        scenario = ActivityScenario.launch<MainActivity>(Intent(target, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_MOONLIT_TESTING, true))
        waitFor("the real QA library and rewards load") { onActivity {
            !it.busyForTests && it.libraryForTests.relicsLoaded.value && it.libraryForTests.relicError.value == null
        } }
        val access = onActivity { it.relicTestingForTests }.also { testingAccess = it }
        assertTrue("The QA build supports explicit temporary testing", access.supported)
        waitFor("debug request enables testing and automatically requests the live outside preview") {
            access.state.value.isActive(System.currentTimeMillis()) && onActivity {
                !it.busyForTests && it.relicCabinetShownForTests && it.relicPreviewRequestedForTests
            }
        }
        assertEquals(RelicCatalog.all.toSet(), access.availableRelics(before))
        assertEquals("Testing access does not award tiers", before.earned,
            onActivity { it.libraryForTests.relics.collection.value.earned })

        val atlas = RelicAppearance(enabled = true, edgeSet = RelicSet.WAYFARER, edgeTier = MoonlitTier.MYTHIC,
            sealSet = RelicSet.HEARTHKEEPER, seal = MoonlitTier.LEGENDARY, motion = true)
        access.saveAppearance(null, atlas)
        assertEquals("The actual temporary profile permits Atlas edges with a Hearthkeeper seal", atlas, access.appearanceFor(before, null))
        val hearth = atlas.copy(edgeSet = RelicSet.HEARTHKEEPER, sealSet = RelicSet.WAYFARER, seal = MoonlitTier.RARE)
        access.saveAppearance(null, hearth)
        assertEquals("The actual temporary profile also permits Mythic Hearthkeeper edges", hearth, access.appearanceFor(before, null))
        onActivity { it.closeRelicCabinetForTests() }
        waitFor("closing the cabinet ends its outside-preview request") { onActivity {
            !it.relicCabinetShownForTests && !it.relicPreviewRequestedForTests
        } }

        access.end()
        waitFor("temporary access ends") { !access.state.value.isActive(System.currentTimeMillis()) }
        val after = onActivity { it.libraryForTests.relics.collection.value }
        assertEquals("Ending testing restores earned-only availability", before.unlockedRelics, access.availableRelics(after))
        assertEquals("Ending testing restores the genuine appearance", before.appearanceForRelics(null), access.appearanceFor(after, null))
        assertEquals("Testing never changes permanent reading credit", before.totalMillis, after.totalMillis)
        assertEquals("Testing never changes qualifying reading days", before.qualifyingDays, after.qualifyingDays)
        assertEquals("Testing never changes the best streak", before.bestStreak, after.bestStreak)
        assertEquals("Testing never changes permanent awards", before.earned, after.earned)
        assertEquals("Testing never changes the genuine global profile", before.defaultAppearance, after.defaultAppearance)
        assertEquals("Testing never changes genuine book profiles", before.bookAppearances, after.bookAppearances)
        assertEquals("Testing never changes the dated reading history", readingDaysBefore,
            onActivity { it.libraryForTests.readingDays.days.value })
        // An emulator can prove the requested lifecycle, not physical outside-screen visibility.
    }

    private fun <T> onActivity(action: (MainActivity) -> T): T {
        var result: Result<T>? = null
        scenario!!.onActivity { result = runCatching { action(it) } }
        return result!!.getOrThrow()
    }

    private fun waitFor(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (failure: Throwable) { lastFailure = failure }
            SystemClock.sleep(60)
        }
        throw AssertionError("Timed out waiting for $description in the isolated QA activity", lastFailure)
    }
}
