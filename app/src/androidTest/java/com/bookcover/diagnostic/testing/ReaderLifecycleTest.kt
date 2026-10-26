package com.bookcover.diagnostic.testing

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.library.LibraryIndex
import com.bookcover.diagnostic.reader.EpubSession
import java.io.File
import java.util.UUID
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

/**
 * The real MainActivity in the separate QA app, with its own library. Covers what Android does to a
 * reader: recreation on rotation, a display change on fold/unfold, and stopping before process death.
 */
@RunWith(AndroidJUnit4::class)
class ReaderLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val libraryFolder get() = File(target.filesDir, "library")
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun freshQaLibrary() {
        check(target.packageName.endsWith(".qa")) { "Lifecycle tests may only clear the isolated QA app's own data." }
        libraryFolder.listFiles()?.forEach { it.deleteRecursively() }
        // No sample import and no outside-cover request, which would show a system dialog.
        target.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).commit()
    }

    @After fun cleanUp() {
        scenario?.close()
        shell("cmd device_state state reset")
    }

    @Test fun rotationReopensTheBookAtTheSamePlace() {
        val book = launchWithLongBook()
        val reader = openAndMoveInto(book)
        val before = progressionOf(reader)
        scenario!!.recreate() // What Android does to the activity when the phone rotates.
        val reopened = waitForReader(book)
        assertNotSame("The activity really was recreated", reader, reopened)
        waitFor("the reopened book shows the same place") { abs(progressionOf(reopened) - before) <= 0.02 }
    }

    @Test fun foldingAndUnfoldingKeepTheBookAtTheSamePlace() {
        val book = launchWithLongBook()
        val reader = openAndMoveInto(book)
        val before = progressionOf(reader)
        val other = if (shell("cmd device_state print-state").trim() == "0") "3" else "0"
        try {
            for (step in listOf("state $other", "state reset")) {
                val width = onActivity { it.resources.configuration.screenWidthDp }
                shell("cmd device_state $step")
                waitFor("the app moves to the other screen ($step)", timeout = 20_000) {
                    onActivity { it.resources.configuration.screenWidthDp } != width
                }
                val current = waitForReader(book)
                // Page breaks differ between the two screens, so allow about one page of difference.
                waitFor("the book shows the same place after $step") { abs(progressionOf(current) - before) <= 0.05 }
            }
        } finally { shell("cmd device_state state reset") }
    }

    @Test fun leavingTheAppSavesThePositionAtOnce() {
        val book = launchWithLongBook()
        val reader = openAndMoveInto(book)
        val before = progressionOf(reader)
        assertNotEquals("The test must leave before the 1.5-second save", before, savedProgression(book) ?: 0.0, 0.01)
        scenario!!.moveToState(Lifecycle.State.CREATED) // The Home button: onStop runs.
        waitFor("the position is on disk within a second of leaving", timeout = 1_000) {
            savedProgression(book)?.let { abs(it - before) <= 0.01 } == true
        }
        scenario!!.close(); scenario = null
        // A restarted process sees only what is on disk.
        val onDisk = LibraryIndex(libraryFolder).load().records.single { it.id == book.id }
        assertEquals(before, onDisk.progression, 0.01)
    }

    private fun launchWithLongBook(): BookRecord {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("the library loads") { !onActivity { it.busyForTests } }
        val fixture = NativeEpubFixture.write(File(target.cacheDir, "lifecycle-${UUID.randomUUID()}").apply { mkdirs() }, false)
        val library = onActivity { it.libraryForTests }
        return runBlocking { library.import(Uri.fromFile(fixture)).first }
    }

    private fun openAndMoveInto(book: BookRecord): EpubSession {
        onActivity { it.openBookForTests(book) }
        val reader = waitForReader(book)
        js(reader, "readium.scrollToId('paragraph-60'); true")
        waitFor("the reader reports the new place") { progressionOf(reader) > 0.3 }
        return reader
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

    private fun savedProgression(book: BookRecord): Double? =
        runCatching { LibraryIndex(libraryFolder).load().records.firstOrNull { it.id == book.id }?.progression }.getOrNull()

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
