package com.bookcover.diagnostic.testing

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.reader.EpubSession
import com.bookcover.diagnostic.reader.InnerScreenIdle
import java.io.File
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

/**
 * BC-22 in the real MainActivity of the separate QA app: only genuine touches and keys renew the
 * inner screen's hold; navigation by code, recreation, folding and resuming keep the earlier deadline.
 * Expiry is checked with a later clock value instead of waiting minutes; the window flag is real.
 */
@RunWith(AndroidJUnit4::class)
class ScreenIdleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val minute = 60_000L
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun freshQaLibrary() {
        check(target.packageName.endsWith(".qa")) { "Screen tests may only clear the isolated QA app's own data." }
        resetQaBookFixtures()
        target.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).commit()
    }

    @After fun cleanUp() {
        scenario?.close()
        shell("cmd device_state state reset")
        waitFor("the phone returns to its physical fold state") {
            val physical = Regex("identifier=(\\d+)").find(shell("cmd device_state base-state"))?.groupValues?.get(1)
            physical != null && shell("cmd device_state print-state").trim() == physical
        }
        SystemClock.sleep(1_000)
    }

    @Test fun onlyGenuineInputRenewsTheReadingScreen() {
        val book = launchWithLongBook()
        assertFalse("The library never holds the screen", onActivity { it.keepsScreenOnForTests })
        val reader = open(book)
        assertTrue("Opening a book holds the screen", onActivity { it.keepsScreenOnForTests })
        val opened = lastInput()

        // Navigation by code moves the locator and saves a position, but is not the reader's input.
        js(reader, "readium.scrollToId('paragraph-60'); true")
        waitFor("the reader reports the new place") { progressionOf(reader) > 0.3 }
        SystemClock.sleep(500)
        assertEquals("Locator notifications do not renew", opened, lastInput())

        onActivity { it.checkScreenAwakeForTests(opened!! + 5 * minute) }
        assertFalse("Released after 5 idle minutes", onActivity { it.keepsScreenOnForTests })

        // A consumed page key never reaches Activity.dispatchKeyEvent; it must still count.
        instrumentation.waitForIdleSync()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_PAGE_DOWN)
        val afterKey = lastInput()!!
        assertTrue("A key renews", afterKey > opened!!)
        assertTrue(onActivity { it.keepsScreenOnForTests })

        onActivity { it.checkScreenAwakeForTests(afterKey + 5 * minute) }
        assertFalse(onActivity { it.keepsScreenOnForTests })
        tapMiddle()
        assertTrue("A touch renews", lastInput()!! > afterKey)
        assertTrue(onActivity { it.keepsScreenOnForTests })
    }

    @Test fun recreationAndFoldingKeepTheOriginalDeadline() {
        val book = launchWithLongBook()
        open(book)
        val opened = lastInput()!!
        scenario!!.recreate()
        waitForReader(book)
        assertEquals("Recreation restores, not renews", opened, lastInput())
        assertTrue(onActivity { it.keepsScreenOnForTests })

        val other = if (shell("cmd device_state print-state").trim() == "0") "3" else "0"
        val states = shell("cmd device_state print-states")
        // Fail at once, not as a later display-width timeout, where the device lacks the Fold's states.
        assertTrue("Needs device state $other of a physical Fold; this device offers: ${states.trim()}", "identifier=$other," in states)
        for (step in listOf("state $other", "state reset")) {
            val width = onActivity { it.resources.configuration.screenWidthDp }
            shell("cmd device_state $step")
            waitFor("the app moves to the other screen ($step)", timeout = 20_000) {
                onActivity { it.resources.configuration.screenWidthDp } != width
            }
            waitForReader(book)
            SystemClock.sleep(500)
            assertEquals("Folding ($step) is not reader input", opened, lastInput())
        }
        onActivity { it.checkScreenAwakeForTests(opened + 5 * minute) }
        assertFalse("The original deadline still expires", onActivity { it.keepsScreenOnForTests })
    }

    @Test fun pauseReleasesAndResumeKeepsTheDeadline() {
        val book = launchWithLongBook()
        open(book)
        val opened = lastInput()!!
        scenario!!.moveToState(Lifecycle.State.STARTED) // onPause only.
        assertFalse("A paused window does not hold the screen", onActivity { it.keepsScreenOnForTests })
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        assertEquals("Returning is not new input", opened, lastInput())
        assertTrue("Still within the original 5 minutes", onActivity { it.keepsScreenOnForTests })
        onActivity { it.checkScreenAwakeForTests(opened + 5 * minute) }
        assertFalse(onActivity { it.keepsScreenOnForTests })
    }

    @Test fun alwaysAndTheSavedChoiceSurviveRecreation() {
        val book = launchWithLongBook()
        open(book)
        val opened = lastInput()!!
        onActivity { it.chooseScreenIdleForTests(InnerScreenIdle.ALWAYS) }
        assertEquals("always", target.getSharedPreferences("reader", Context.MODE_PRIVATE).getString("innerScreenIdle", null))
        onActivity { it.checkScreenAwakeForTests(opened + 60 * minute) }
        assertTrue("Always keeps the screen on", onActivity { it.keepsScreenOnForTests })

        scenario!!.recreate()
        waitForReader(book)
        assertEquals(InnerScreenIdle.ALWAYS, onActivity { it.screenAwakeForTests.idle })
        onActivity { it.checkScreenAwakeForTests(opened + 60 * minute) }
        assertTrue(onActivity { it.keepsScreenOnForTests })

        onActivity { it.chooseScreenIdleForTests(InnerScreenIdle.TWO) }
        onActivity { it.checkScreenAwakeForTests(opened + 2 * minute) }
        assertFalse("Two minutes from the same last input", onActivity { it.keepsScreenOnForTests })
    }

    private fun lastInput(): Long? = onActivity { it.screenAwakeForTests.lastInput }

    private fun tapMiddle() {
        val (x, y) = onActivity { activity ->
            val location = IntArray(2)
            val view = activity.sessionForTests!!.fragment.requireView()
            view.getLocationOnScreen(location)
            (location[0] + view.width / 2f) to (location[1] + view.height / 2f)
        }
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0))
        instrumentation.waitForIdleSync()
    }

    private fun launchWithLongBook(): BookRecord {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("the library loads") { !onActivity { it.busyForTests } }
        val fixture = NativeEpubFixture.write(File(target.cacheDir, "screen-idle-${UUID.randomUUID()}").apply { mkdirs() }, false)
        val library = onActivity { it.libraryForTests }
        return runBlocking { library.import(Uri.fromFile(fixture)).first }
    }

    private fun open(book: BookRecord): EpubSession {
        onActivity { it.openBookForTests(book) }
        return waitForReader(book)
    }

    private fun waitForReader(book: BookRecord): EpubSession {
        waitFor("the book is open in the reader", timeout = 20_000) {
            onActivity { activity -> activity.sessionForTests?.let { it.book.id == book.id && it.fragment.isAdded } } == true
        }
        val reader = onActivity { it.sessionForTests!! }
        waitFor("the chapter renders") {
            runCatching { js(reader, "Boolean(window.readium && document.getElementById('paragraph-0'))") == "true" }.getOrDefault(false)
        }
        return reader
    }

    private fun progressionOf(reader: EpubSession): Double =
        onMain { reader.fragment.currentLocator.value.locations.totalProgression ?: 0.0 }

    private fun <T> onActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        scenario!!.onActivity { result = runCatching { block(it) } }
        return result!!.getOrThrow()
    }

    private fun <T> onMain(action: suspend () -> T): T = runBlocking { withContext(Dispatchers.Main) { action() } }

    private fun js(reader: EpubSession, script: String): String = onMain {
        withTimeout(10_000) { reader.fragment.evaluateJavascript(script) } ?: error("No reflowable viewport")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private fun waitFor(description: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (failure: Throwable) { lastFailure = failure }
            SystemClock.sleep(60)
        }
        throw AssertionError("Timed out waiting for $description", lastFailure)
    }
}
