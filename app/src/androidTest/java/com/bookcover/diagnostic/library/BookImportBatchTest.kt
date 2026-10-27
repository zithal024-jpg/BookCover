package com.bookcover.diagnostic.library

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.FailureReason
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/** BC-27: batch import with real storage and Readium, in a throwaway folder of the isolated QA app. */
@RunWith(AndroidJUnit4::class)
class BookImportBatchTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var context: Context
    private val libraryFolder get() = File(directory, "library")

    @Before fun setUp() {
        check(target.packageName.endsWith(".qa")) { "Import tests run only in the isolated QA app." }
        directory = File(target.noBackupFilesDir, "import-batch-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(target) { override fun getFilesDir(): File = directory }
    }

    @After fun tearDown() { directory.deleteRecursively() }

    private fun newLibrary() = BookLibrary(context, ReadiumEngine(context))
    private val sources get() = File(directory, "sources").apply { mkdirs() }
    private fun books(count: Int, from: Int = 1) = (from until from + count).map { Uri.fromFile(GeneratedBooks.write(sources, "Batch book $it")) }
    private fun notAZip() = Uri.fromFile(File(sources, "notes.epub").apply { writeText("These are notes, not a book.") })
    private fun noContainer() = Uri.fromFile(GeneratedBooks.zip(File(sources, "no-container.epub"), listOf("EPUB/a.xhtml" to "<p>x</p>".toByteArray())))
    private fun importCopies() = libraryFolder.list().orEmpty().filter { it.startsWith("import-") }

    @Test fun twentyBooksImportInOneBatch() = runBlocking {
        val library = newLibrary().apply { load() }
        val progress = mutableListOf<ImportProgress>()
        val summary = library.importAll(books(20)) { progress += it }!!
        assertEquals(20, summary.imported.size)
        assertTrue(summary.failed.isEmpty() && summary.duplicates.isEmpty() && !summary.cancelled)
        assertEquals((0 until 20).map { ImportProgress(it, 20) }, progress)
        assertEquals((1..20).map { "Batch book $it" }.toSet(), library.books.value.map { it.title }.toSet())
        assertEquals("Every book is saved", 20, newLibrary().apply { load() }.books.value.size)
        assertTrue("No import copies remain: ${importCopies()}", importCopies().isEmpty())
        assertEquals("Imported 20 books. Tap a cover to read.", importSummaryMessage(summary))
    }

    @Test fun badAndDuplicateBooksDoNotUndoTheGoodOnesOrTheExistingLibrary() = runBlocking {
        val library = newLibrary().apply { load() }
        val existing = library.import(books(1, from = 100).single()).first
        library.updatePosition(existing.id, Locator(Url("chapter0.xhtml")!!, MediaType.XHTML,
            locations = Locator.Locations(totalProgression = 0.37)))
        library.bookmark(existing.id, Locator(Url("chapter0.xhtml")!!, MediaType.XHTML), name = "Kept")
        val before = library.book(existing.id)!!
        val good = books(3)
        val batch = listOf(good[0], notAZip(), good[1], books(1, from = 100).single(), noContainer(), good[1], good[2])
        val summary = library.importAll(batch)!!
        assertEquals(listOf("Batch book 1", "Batch book 2", "Batch book 3"), summary.imported)
        assertEquals(listOf("Batch book 100", "Batch book 2"), summary.duplicates)
        assertEquals(listOf("notes.epub" to FailureReason.NOT_EPUB, "no-container.epub" to FailureReason.NOT_EPUB),
            summary.failed.map { it.name to it.reason })
        val reopened = newLibrary().apply { load() }
        assertEquals("Existing book, reading position and bookmark unchanged", before, reopened.book(existing.id))
        assertEquals(4, reopened.books.value.size)
        assertTrue("No import copies remain: ${importCopies()}", importCopies().isEmpty())
    }

    @Test fun stoppingKeepsFinishedBooksAndLeavesNoCopies() = runBlocking {
        val library = newLibrary().apply { load() }
        val cancellation = ImportCancellation()
        val summary = library.importAll(books(6), cancellation) { if (it.done == 2) cancellation.cancel() }!!
        assertTrue(summary.cancelled)
        assertEquals(listOf("Batch book 1", "Batch book 2"), summary.imported)
        assertEquals(4, summary.notAttempted)
        assertEquals(2, newLibrary().apply { load() }.books.value.size)
        assertTrue("No import copies remain: ${importCopies()}", importCopies().isEmpty())
    }

    @Test fun aSecondBatchIsRefusedWhileOneRuns() = runBlocking {
        val library = newLibrary().apply { load() }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            library.importAll(books(3)) { if (it.done == 1) { started.complete(Unit); runBlocking { release.await() } } }
        }
        withTimeout(30_000) { started.await() }
        assertNull("Overlapping import refused", library.importAll(books(1, from = 50)))
        release.complete(Unit)
        assertEquals(3, withTimeout(60_000) { first.await() }!!.imported.size)
        assertEquals("Only the first batch's books were added", 3, library.books.value.size)
    }

    @Test fun startUpRemovesOnlyCopiesLeftByAKilledImport() = runBlocking {
        val library = newLibrary().apply { load() }
        val book = library.import(books(1).single()).first
        // What a forced kill mid-import leaves behind, next to files cleanup must never touch.
        val orphans = listOf("import-123456789.epub", "import--42.epub").map { File(libraryFolder, it).apply { writeText("partial") } }
        val unrelated = listOf("import-notes.txt", "my-import-1.epub", "import-12.epub.part")
            .map { File(libraryFolder, it).apply { writeText("keep") } }
        val result = newLibrary().load()
        assertEquals(2, result.abandonedImportsRemoved)
        orphans.forEach { assertFalse("${it.name} removed", it.exists()) }
        unrelated.forEach { assertTrue("${it.name} kept", it.exists()) }
        assertEquals(listOf(book), result.records)
        assertTrue(library.epubFile(book).exists() && library.coverFile(book).exists() && library.thumbnailFile(book).exists())
        assertTrue(File(libraryFolder, "books.json").exists())
    }

    @Test fun cleanupNeverRemovesACopyThatIsStillBeingWritten() {
        val library = newLibrary()
        val active = library.newImportTemp()
        try {
            // Another instance in the same process (e.g. a recreated screen) must see it as active too.
            assertEquals(0, newLibrary().cleanAbandonedImports())
            assertTrue(active.exists())
        } finally { library.finishImportTemp(active) }
        assertFalse(active.exists())
    }
}

/** Small, distinct, generated EPUBs: original text only, nothing downloaded. */
internal object GeneratedBooks {
    fun write(directory: File, title: String): File {
        val chapter = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><h1>$title</h1>""" +
            (1..12).joinToString("") { "<p>$title, paragraph $it. The keeper walked along the river and counted the stones.</p>" } + "</body></html>"
        val nav = """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol><li><a href="chapter0.xhtml">$title</a></li></ol></nav></body></html>"""
        val opf = """<?xml version="1.0" encoding="UTF-8"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">urn:bookcover:batch:${title.replace(' ', '-')}</dc:identifier><dc:title>$title</dc:title><dc:creator>BookCover test suite</dc:creator><dc:language>en</dc:language><meta property="dcterms:modified">2026-10-26T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="c0" href="chapter0.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c0"/></spine></package>"""
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="EPUB/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        return zip(File(directory, "${title.replace(' ', '-')}.epub"), listOf("META-INF/container.xml" to container.toByteArray(),
            "EPUB/book.opf" to opf.toByteArray(), "EPUB/nav.xhtml" to nav.toByteArray(), "EPUB/chapter0.xhtml" to chapter.toByteArray()))
    }

    fun zip(file: File, entries: List<Pair<String, ByteArray>>): File {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                time = 0; method = ZipEntry.STORED; size = mimetype.size.toLong(); compressedSize = size
                crc = CRC32().apply { update(mimetype) }.value
            })
            zip.write(mimetype); zip.closeEntry()
            entries.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name).apply { time = 0 }); zip.write(data); zip.closeEntry() }
        }
        return file
    }
}
