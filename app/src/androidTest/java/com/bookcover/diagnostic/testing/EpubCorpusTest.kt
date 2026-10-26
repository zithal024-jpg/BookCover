package com.bookcover.diagnostic.testing

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.reader.EpubSession
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.AssumptionViolatedException
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.readium.r2.shared.publication.services.positions

/**
 * BC-13: every generated corpus book either opens, shows readable text, turns pages and reopens at
 * the same place, or is rejected without changing the library. Runs the real MainActivity in the QA app.
 */
@RunWith(Parameterized::class)
internal class EpubCorpusTest(private val book: EpubCorpus.Book) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun books(): List<Array<Any>> = EpubCorpus.books.map { arrayOf<Any>(it) }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val libraryFolder get() = File(target.filesDir, "library")
    private val corpusFolder get() = File(target.cacheDir, "corpus")
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun freshQaLibrary() {
        check(target.packageName.endsWith(".qa")) { "Corpus tests may only clear the isolated QA app's own data." }
        libraryFolder.listFiles()?.forEach { it.deleteRecursively() }
        target.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).commit()
    }

    @After fun cleanUp() {
        scenario?.close()
        corpusFolder.deleteRecursively()
    }

    @Test fun corpusBook() {
        val issue = book.knownIssue ?: return check()
        try { check() } catch (failure: AssertionError) {
            throw AssumptionViolatedException("Known issue $issue: ${failure.message}")
        }
        fail("${book.name} now works: $issue may be fixed, so remove its knownIssue marker")
    }

    private fun check() {
        val file = File(corpusFolder, "${book.name}.epub").apply { parentFile!!.mkdirs(); book.write(this) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("the library loads") { !onActivity { it.busyForTests } }
        val library = onActivity { it.libraryForTests }

        if (book.expect == EpubCorpus.Expect.REJECTED) {
            val error = runCatching { runBlocking { library.import(Uri.fromFile(file)) } }.exceptionOrNull()
            assertNotNull("${book.name} should be rejected", error)
            Log.i("EpubCorpus", "${book.name} rejected; message shown to the reader: ${error!!.message}")
            assertTrue("A rejected book leaves the library unchanged", library.books.value.isEmpty())
            val leftovers = libraryFolder.listFiles { f -> f.name.endsWith(".epub") || f.name.endsWith(".png") }.orEmpty()
            assertTrue("No partial copies remain: ${leftovers.map { it.name }}", leftovers.isEmpty())
            return
        }

        val record = runBlocking { library.import(Uri.fromFile(file)).first }
        onActivity { it.openBookForTests(record) }
        val reader = waitForReader(record)
        if (!book.fixedLayout) waitFor("the first chapter's text is readable") {
            js(reader, "document.body.innerText.indexOf(${JSONObject.quote(book.marker)}) >= 0") == "true"
        }

        val positions = onMain { reader.publication.positions().size }
        if (positions > 1) {
            val start = place(reader)
            var moved = false
            for (attempt in 1..3) {
                onMain { reader.next() }
                moved = within(3_000) { place(reader) != start }
                if (moved) break
            }
            assertTrue("Turning pages moves through the book ($positions positions, still at $start)", moved)
        }

        val before = progression(reader)
        scenario!!.recreate() // Reopen, as after rotation or a restart from the saved position.
        val reopened = waitForReader(record)
        waitFor("the book reopens at the same place") { abs(progression(reopened) - before) <= 0.02 }
    }

    private fun waitForReader(book: BookRecord): EpubSession {
        waitFor("the book opens in the reader", timeout = 30_000) {
            onActivity { activity -> activity.sessionForTests?.let { it.book.id == book.id && it.fragment.isAdded } } == true
        }
        return onActivity { it.sessionForTests!! }
    }

    private fun place(reader: EpubSession): String = onMain {
        reader.fragment.currentLocator.value.let { "${it.href} ${it.locations.progression} ${it.locations.totalProgression}" }
    }

    private fun progression(reader: EpubSession): Double =
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

    private fun within(timeout: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (runCatching(predicate).getOrDefault(false)) return true
            SystemClock.sleep(60)
        }
        return false
    }

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
