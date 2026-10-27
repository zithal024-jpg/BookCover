package com.bookcover.diagnostic.library

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/** F2: real Readium/filesystem imports, using only generated books in disposable QA storage. */
@RunWith(AndroidJUnit4::class)
class BookImportFinalizationTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var context: Context
    private val libraryFolder get() = File(directory, "library")

    @Before fun setUp() {
        check(target.packageName.endsWith(".qa"))
        directory = File(target.noBackupFilesDir, "import-finalization-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(target) { override fun getFilesDir() = directory }
    }

    @After fun tearDown() { directory.deleteRecursively() }

    private fun newLibrary() = BookLibrary(context, ReadiumEngine(context))
    private fun source(title: String) = GeneratedBooks.write(File(directory, "sources").apply { mkdirs() }, title)
    private fun id(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun assets(id: String) = listOf("$id.epub", "$id.png", "$id.thumb.png").map { File(libraryFolder, it) }
    private fun receipt(id: String) = File(libraryFolder, "pending-import-$id").apply { writeText("BookCover import 1\n$id\n") }

    private suspend fun failedSave(reload: Boolean) {
        val library = newLibrary().apply { load() }
        val existing = library.import(Uri.fromFile(source("Existing book"))).first
        val locator = Locator(Url("chapter0.xhtml")!!, MediaType.XHTML,
            locations = Locator.Locations(totalProgression = 0.42))
        library.updatePosition(existing.id, locator)
        library.bookmark(existing.id, locator, name = "Keep this place")
        val before = library.book(existing.id)!!
        val kept = listOf("books.json", "${existing.id}.epub", "${existing.id}.png", "${existing.id}.thumb.png")
            .associateWith { File(libraryFolder, it).readBytes() }
        val incoming = source("Failed new book")
        // LibraryIndex must back up the existing index before replacing it. A directory at the
        // temporary backup path forces a real IOException after the import's final files exist.
        val blocker = File(libraryFolder, "books.last-good.json.tmp").apply { check(mkdir()) }
        try {
            assertThrows(IOException::class.java) { runBlocking { library.import(Uri.fromFile(incoming)) } }
        } finally { check(blocker.delete()) }
        val result = if (reload) newLibrary().apply { load() } else library
        assertEquals(listOf(before), result.books.value)
        kept.forEach { (name, bytes) -> assertArrayEquals("Existing $name unchanged", bytes, File(libraryFolder, name).readBytes()) }
        val abandoned = libraryFolder.list().orEmpty().filter { it.startsWith(id(incoming)) || it.startsWith("import-") }
        assertTrue("Failed import left final assets: $abandoned", abandoned.isEmpty())
    }

    @Test fun failedPersistenceRemovesOnlyTheNewImportsAssets() = runBlocking { failedSave(reload = false) }
    @Test fun reloadingAfterFailedPersistencePreservesExistingDataAndCleansAbandonedAssets() = runBlocking { failedSave(reload = true) }

    @Test fun restartCleansReceiptedResidueButPreservesIndexedAndUnmarkedFiles() = runBlocking {
        val library = newLibrary().apply { load() }
        val existing = library.import(Uri.fromFile(source("Kept on restart"))).first
        library.bookmark(existing.id, Locator(Url("chapter0.xhtml")!!, MediaType.XHTML), name = "Kept")
        val before = library.book(existing.id)!!
        val indexBefore = File(libraryFolder, "books.json").readBytes()
        val kept = assets(existing.id).associateWith { it.readBytes() }
        // Synthetic on-disk residue: no exception/finally is involved in this new-instance recovery.
        // This models termination before index save; it does not actually kill an Android process.
        val abandoned = "d".repeat(64)
        assets(abandoned).forEach { it.writeText("interrupted import") }
        val pending = receipt(abandoned)
        val unmarked = File(libraryFolder, "${"e".repeat(64)}.epub").apply { writeText("keep recoverable old copy") }
        val reloaded = newLibrary().apply { load() }
        assertEquals(listOf(before), reloaded.books.value)
        assertArrayEquals(indexBefore, File(libraryFolder, "books.json").readBytes())
        kept.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
        assertTrue(assets(abandoned).none { it.exists() })
        assertFalse(pending.exists())
        assertEquals("keep recoverable old copy", unmarked.readText())
    }

    @Test fun restartAfterCommitRetainsEveryAssetAndTheSavedRecord() = runBlocking {
        val library = newLibrary().apply { load() }
        val book = library.import(Uri.fromFile(source("Committed before interruption"))).first
        val pending = receipt(book.id)
        val before = assets(book.id).associateWith { it.readBytes() }
        assertEquals(listOf(book), newLibrary().load().records)
        before.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
        assertFalse("The index now owns the files", pending.exists())
    }

    @Test fun unreadableIndexCannotAuthorizeFinalAssetCleanup() = runBlocking {
        libraryFolder.mkdirs()
        val abandoned = "f".repeat(64)
        val pending = receipt(abandoned)
        assets(abandoned).forEach { it.writeText("possibly recoverable") }
        val index = File(libraryFolder, "books.json").apply { writeText("{ damaged index") }
        assertThrows(LibraryIndexException::class.java) { runBlocking { newLibrary().load() } }
        assertEquals("{ damaged index", index.readText())
        assertTrue(pending.isFile)
        assets(abandoned).forEach { assertEquals("possibly recoverable", it.readText()) }
    }

    @Test fun reimportNeverOverwritesAnExistingUnindexedCopy() = runBlocking {
        val library = newLibrary().apply { load() }
        val incoming = source("Previously unindexed")
        val prior = File(libraryFolder, "${id(incoming)}.epub").apply { writeText("preserve these existing bytes") }
        assertThrows(IOException::class.java) { runBlocking { library.import(Uri.fromFile(incoming)) } }
        assertEquals("preserve these existing bytes", prior.readText())
        assertTrue(library.books.value.isEmpty())
        assertEquals(listOf(prior.name), libraryFolder.list().orEmpty().toList())
    }

    @Test fun simultaneousDuplicateImportsCommitOnceAndKeepSavedMetadata() = runBlocking {
        val library = newLibrary().apply { load() }
        val uri = Uri.fromFile(source("Simultaneous duplicate"))
        val first = async(Dispatchers.Default) { library.import(uri) }
        val second = async(Dispatchers.Default) { library.import(uri) }
        val results = listOf(first.await(), second.await())
        assertEquals(1, results.count { it.second })
        assertEquals(1, library.books.value.size)
        val book = library.books.value.single()
        val locator = Locator(Url("chapter0.xhtml")!!, MediaType.XHTML,
            locations = Locator.Locations(totalProgression = 0.63))
        library.updatePosition(book.id, locator)
        library.bookmark(book.id, locator, name = "Keep after duplicate")
        val before = library.book(book.id)!!
        val bytes = assets(book.id).associateWith { it.readBytes() }
        assertEquals(before to false, library.import(uri))
        assertEquals(listOf(before), newLibrary().load().records)
        bytes.forEach { (file, saved) -> assertArrayEquals(file.name, saved, file.readBytes()) }
        assertFalse(libraryFolder.list().orEmpty().any { it.startsWith("pending-import-") || it.startsWith("import-") })
    }

    @Test fun staleThumbnailRequestCannotWriteIntoAnActiveImport() = runBlocking {
        val library = newLibrary().apply { load() }
        val stale = BookRecord("c".repeat(64), "Removed book", "Author", 1)
        assertThrows(IOException::class.java) {
            ImportFinalization(libraryFolder).commit(stale.id, {
                library.thumbnailBitmap(stale)
                assertFalse("A stale rebuild cannot become an import-owned file", library.thumbnailFile(stale).exists())
                throw IOException("Abandon the generated fixture")
            }) { fail("No index should be saved") }
        }
        assertTrue(library.books.value.isEmpty())
        assertTrue(assets(stale.id).none { it.exists() })
    }

    @Test fun batchContinuesAfterPersistenceFailureAndCountsSuccessAndDuplicate() = runBlocking {
        val library = newLibrary().apply { load() }
        val existingUri = Uri.fromFile(source("Existing batch book"))
        val existing = library.import(existingUri).first
        library.bookmark(existing.id, Locator(Url("chapter0.xhtml")!!, MediaType.XHTML), name = "Retained")
        val before = library.book(existing.id)!!
        val bad = source("Failed batch book")
        val good = source("Successful batch book")
        val blocker = File(libraryFolder, "books.last-good.json.tmp")
        val summary = library.importAll(listOf(Uri.fromFile(bad), Uri.fromFile(good), existingUri)) {
            if (it.done == 0) check(blocker.mkdir())
            if (it.done == 1) check(blocker.delete())
        }!!
        assertEquals(listOf("Successful batch book"), summary.imported)
        assertEquals(listOf("Existing batch book"), summary.duplicates)
        assertEquals(1, summary.failed.size)
        assertFalse(summary.cancelled)
        val reopened = newLibrary().apply { load() }
        assertEquals(2, reopened.books.value.size)
        assertEquals(before, reopened.book(existing.id))
        assertTrue(assets(id(bad)).none { it.exists() })
        assertFalse(libraryFolder.list().orEmpty().any { it.startsWith("pending-import-") || it.startsWith("import-") })
    }
}
