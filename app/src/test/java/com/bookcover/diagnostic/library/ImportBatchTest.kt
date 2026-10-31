package com.bookcover.diagnostic.library

import com.bookcover.diagnostic.FailureReason
import com.bookcover.diagnostic.ReaderAction
import com.bookcover.diagnostic.readerMessage
import java.io.IOException
import java.util.zip.ZipException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

/** BC-27: importing many books at once, one at a time, without one book's failure affecting the others. */
class ImportBatchTest {
    private val protectedLibrary = IllegalStateException("Your library list could not be read, so BookCover is not saving changes to protect it.")

    private fun fakeImport(name: String): ImportOutcome = when {
        name.startsWith("dup") -> ImportOutcome.Duplicate("Title of $name")
        name.startsWith("bad") -> throw ZipException("zip END header not found")
        name.startsWith("drm") -> throw IllegalArgumentException("This EPUB is protected. The prototype opens DRM-free EPUBs.")
        name.startsWith("locked") -> throw protectedLibrary
        else -> ImportOutcome.Added("Title of $name")
    }

    @Test fun aMixedBatchImportsEveryGoodBookAndReportsEachProblem() = runTest {
        val progress = mutableListOf<ImportProgress>()
        val summary = runImportBatch(listOf("one.epub", "bad.epub", "two.epub", "dup.epub", "drm.epub", "three.epub"), { it },
            onProgress = { progress += it }) { fakeImport(it) }
        assertEquals(listOf("Title of one.epub", "Title of two.epub", "Title of three.epub"), summary.imported)
        assertEquals(listOf("Title of dup.epub"), summary.duplicates)
        assertEquals(listOf("bad.epub" to FailureReason.NOT_EPUB, "drm.epub" to FailureReason.PROTECTED_BOOK),
            summary.failed.map { it.name to it.reason })
        assertFalse(summary.cancelled)
        assertEquals(0, summary.notAttempted)
        assertEquals("Progress before each book", (0 until 6).map { ImportProgress(it, 6) }, progress)
    }

    @Test fun anUnreadableLibraryListStopsTheBatchInsteadOfFailingEveryBook() = runTest {
        val tried = mutableListOf<String>()
        val summary = runImportBatch(listOf("one.epub", "locked.epub", "two.epub"), { it }) { tried += it; fakeImport(it) }
        assertEquals(listOf("one.epub", "locked.epub"), tried)
        assertTrue(summary.libraryProtected)
        assertEquals(1, summary.notAttempted)
        assertTrue(importSummaryMessage(summary).contains("nothing was deleted"))
    }

    @Test fun stoppingKeepsTheBooksAlreadyImportedAndAbandonsTheCurrentOne() = runTest {
        val cancellation = ImportCancellation()
        var cleanedUp = false
        val summary = runImportBatch(listOf("one.epub", "two.epub", "slow.epub", "four.epub"), { it }, cancellation) { name ->
            if (name == "slow.epub") try { cancellation.cancel(); awaitCancellation() } finally { cleanedUp = true }
            fakeImport(name)
        }
        assertTrue(summary.cancelled)
        assertTrue("The interrupted book's own cleanup ran", cleanedUp)
        assertEquals(listOf("Title of one.epub", "Title of two.epub"), summary.imported)
        assertEquals(2, summary.notAttempted)
        assertEquals("Import stopped. Imported 2 of 4 books; 2 were not imported.", importSummaryMessage(summary))
    }

    @Test fun aBookSavedJustAsStopIsPressedIsStillCounted() = runTest {
        val cancellation = ImportCancellation()
        val summary = runImportBatch(listOf("one.epub", "two.epub", "three.epub"), { it }, cancellation) { name ->
            if (name == "two.epub") cancellation.cancel() // Pressed while the book is being saved, after its last pause.
            fakeImport(name)
        }
        assertTrue(summary.cancelled)
        assertEquals(listOf("Title of one.epub", "Title of two.epub"), summary.imported)
        assertEquals(1, summary.notAttempted)
    }

    @Test fun stoppingBetweenBooksStartsNoMoreBooks() = runTest {
        val cancellation = ImportCancellation()
        val tried = mutableListOf<String>()
        val summary = runImportBatch(listOf("one.epub", "two.epub", "three.epub"), { it }, cancellation,
            onProgress = { if (it.done == 1) cancellation.cancel() }) { tried += it; fakeImport(it) }
        assertEquals(listOf("one.epub"), tried)
        assertTrue(summary.cancelled)
        assertEquals(2, summary.notAttempted)
    }

    @Test fun closingTheAppStillCancelsTheWholeBatch() = runTest {
        val started = CompletableDeferred<Unit>()
        val tried = mutableListOf<String>()
        val job = launch {
            runImportBatch(listOf("one.epub", "slow.epub", "three.epub"), { it }) { name ->
                tried += name
                if (name == "slow.epub") { started.complete(Unit); awaitCancellation() }
                fakeImport(name)
            }
            fail("A cancelled batch must not finish")
        }
        started.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf("one.epub", "slow.epub"), tried)
    }

    @Test fun onlyOneImportRunsAtATime() = runTest {
        val gate = ImportGate()
        val release = CompletableDeferred<Unit>()
        val first = async { gate.runExclusive { release.await(); "first" } }
        yield()
        assertTrue(gate.isRunning)
        assertNull("A second import is refused while one runs", gate.runExclusive { "second" })
        release.complete(Unit)
        assertEquals("first", first.await())
        assertFalse(gate.isRunning)
        assertEquals("Free again afterwards", "third", gate.runExclusive { "third" })
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { gate.runExclusive { throw IOException("x") } } }
        assertFalse("A failed import frees the gate", gate.isRunning)
    }

    @Test fun aSingleBookKeepsTheFamiliarMessages() {
        assertEquals("Imported Moby. Tap its cover to read.",
            importSummaryMessage(ImportSummary(1, imported = listOf("Moby"))))
        assertEquals("Moby is already in your library.", importSummaryMessage(ImportSummary(1, duplicates = listOf("Moby"))))
        val error = ZipException("zip END header not found")
        assertEquals(readerMessage(ReaderAction.IMPORT, error),
            importSummaryMessage(ImportSummary(1, failed = listOf(ImportFailure("x.epub", error)))))
    }

    @Test fun aBatchSummaryCountsEachKindAndNamesTheProblemFiles() {
        val summary = ImportSummary(20, imported = (1..17).map { "Book $it" }, duplicates = listOf("Old book"),
            failed = listOf(ImportFailure("broken.epub", ZipException("zip END header not found")),
                ImportFailure("locked.epub", IllegalArgumentException("This EPUB is protected. The prototype opens DRM-free EPUBs."))))
        assertEquals("Imported 17 of 20 books. 1 was already in your library. 2 couldn't be imported: " +
            "broken.epub (not a readable EPUB or PDF), locked.epub (copy-protected). Your other books are safe.", importSummaryMessage(summary))
        val all = ImportSummary(3, imported = listOf("a", "b", "c"))
        assertEquals("Imported 3 books. Tap a cover to read.", importSummaryMessage(all))
        val many = ImportSummary(6, imported = listOf("a"), failed = (1..5).map { ImportFailure("f$it.epub", ZipException("x")) })
        assertTrue(importSummaryMessage(many), importSummaryMessage(many).contains("f3.epub (not a readable EPUB or PDF) and 2 more"))
    }

    @Test fun progressReadsNaturally() {
        assertEquals("Importing book 3 of 20…", importProgressMessage(ImportProgress(2, 20)))
    }
}
