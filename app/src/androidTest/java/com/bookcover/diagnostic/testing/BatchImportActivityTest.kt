package com.bookcover.diagnostic.testing

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.GeneratedBooks
import com.bookcover.diagnostic.library.ImportProgress
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** BC-27: the real library screen imports 20 generated books in one batch, with progress and a summary. */
@RunWith(AndroidJUnit4::class)
class BatchImportActivityTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val libraryFolder get() = File(target.filesDir, "library")
    private val sources get() = File(target.cacheDir, "batch-import")
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun freshQaLibrary() {
        check(target.packageName.endsWith(".qa")) { "Batch import tests may only clear the isolated QA app's own data." }
        libraryFolder.listFiles()?.forEach { it.deleteRecursively() }
        target.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).commit()
    }

    @After fun cleanUp() {
        scenario?.close()
        sources.deleteRecursively()
    }

    @Test fun twentyBooksWithProgressSummaryAndNoOverlap() {
        sources.mkdirs()
        val good = (1..19).map { Uri.fromFile(GeneratedBooks.write(sources, "Screen book $it")) }
        val broken = Uri.fromFile(File(sources, "broken.epub").apply { writeText("Not a book.") })
        val batch = good.take(10) + broken + good.drop(10)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("the library loads") { !onActivity { it.busyForTests } }

        val seen = mutableSetOf<ImportProgress>()
        onActivity { activity ->
            activity.importBooksForTests(batch)
            activity.importBooksForTests(good.take(1)) // Overlapping request while the first runs: refused.
        }
        waitFor("the batch finishes", timeout = 180_000) {
            onActivity { activity -> activity.importProgressForTests?.let { seen += it }; activity.importSummaryForTests != null }
        }
        val summary = onActivity { it.importSummaryForTests!! }
        assertEquals(20, summary.total)
        assertEquals(19, summary.imported.size)
        assertEquals(listOf("broken.epub"), summary.failed.map { it.name })
        assertTrue("Progress was shown while importing: $seen", seen.isNotEmpty() && seen.all { it.total == 20 })
        assertEquals("The overlapping request added nothing", 19, onActivity { it.libraryForTests.books.value.size })
        assertNull("Progress is cleared at the end", onActivity { it.importProgressForTests })
        assertTrue(onActivity { it.showsLibraryForTests })
        val copies = libraryFolder.list().orEmpty().filter { it.startsWith("import-") }
        assertTrue("No import copies remain: $copies", copies.isEmpty())
    }

    private fun <T> onActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        scenario!!.onActivity { result = runCatching { block(it) } }
        return result!!.getOrThrow()
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
