package com.bookcover.diagnostic.library

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real backup APIs, restricted to generated fixtures in a disposable folder of the isolated QA app. */
@RunWith(AndroidJUnit4::class)
class BookLibraryBackupSafetyTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var context: Context
    private val libraryDirectory get() = File(directory, "library").apply { mkdirs() }
    private val index get() = File(libraryDirectory, "books.json")
    private val first = BookRecord("a".repeat(64), "Backup safety fixture", "Test suite", 1, coverRevision = 7)
    private val second = BookRecord("b".repeat(64), "Other safety fixture", "Test suite", 2, coverRevision = 9)
    private val saved = first.copy(collection = "Restored collection", lastReadAt = 500, progression = 0.4,
        locator = "{\"href\":\"chapter-2.xhtml\"}", bookmarks = listOf("{\"href\":\"chapter-1.xhtml#saved\"}"))

    @Before fun setUp() {
        check(target.packageName == "com.frankrodriguez.bookcover.qa") { "Backup safety tests require the isolated QA app." }
        directory = File(target.noBackupFilesDir, "backup-safety-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(target) { override fun getFilesDir(): File = directory }
    }

    @After fun tearDown() { if (::directory.isInitialized) directory.deleteRecursively() }

    private fun newLibrary() = BookLibrary(context, ReadiumEngine(context))
    private fun seed(vararg books: BookRecord) = LibraryIndex(libraryDirectory).apply { load(); save(books.toList()) }
    private fun backup() = File(directory, "disposable-backup.json").apply {
        writeText(LibraryBackup.export(listOf(saved), exportedAt = 1000))
    }
    private fun cover(book: BookRecord, color: Int) = image(File(libraryDirectory, "${book.id}.png"), color)
    private fun image(file: File, color: Int) = file.apply {
        val bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try { outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    @Test fun backupBeforeLoadingIsRefusedWithoutChangingTheDestinationOrIndex() = runBlocking {
        seed(first)
        val library = newLibrary()
        val destination = backup()
        val backupBefore = destination.readBytes()
        val indexBefore = index.readBytes()

        assertThrows(LibraryNotReadyException::class.java) { runBlocking { library.exportBackup(Uri.fromFile(destination)) } }
        assertArrayEquals("Readiness failure must not truncate an existing backup", backupBefore, destination.readBytes())
        assertThrows(LibraryNotReadyException::class.java) { runBlocking { library.restoreBackup(Uri.fromFile(destination)) } }
        assertArrayEquals(indexBefore, index.readBytes())
        assertArrayEquals(backupBefore, destination.readBytes())
        assertTrue("An unloaded flow is not evidence of an empty library", library.books.value.isEmpty())
    }

    @Test fun failedLoadingRefusesBackupAndRestoreAndPreservesTheUnreadableIndex() = runBlocking {
        index.writeText("{ damaged safety fixture")
        val library = newLibrary()
        val destination = backup()
        val backupBefore = destination.readBytes()
        val indexBefore = index.readBytes()
        assertThrows(LibraryIndexException::class.java) { runBlocking { library.load() } }

        val exportError = assertThrows(LibraryNotReadyException::class.java) {
            runBlocking { library.exportBackup(Uri.fromFile(destination)) }
        }
        assertTrue(exportError.loadFailed)
        assertArrayEquals(backupBefore, destination.readBytes())
        val restoreError = assertThrows(LibraryNotReadyException::class.java) {
            runBlocking { library.restoreBackup(Uri.fromFile(destination)) }
        }
        assertTrue(restoreError.loadFailed)
        assertArrayEquals(indexBefore, index.readBytes())
        assertArrayEquals(backupBefore, destination.readBytes())
    }

    @Test fun aSuccessfullyLoadedEmptyLibraryCanBeBackedUp() = runBlocking {
        val library = newLibrary()
        assertTrue(library.load().records.isEmpty())
        val destination = backup()
        assertEquals(0, library.exportBackup(Uri.fromFile(destination)))
        assertTrue(LibraryBackup.parse(destination.readText()).records.isEmpty())
    }

    @Test fun restoringAfterSuccessfulLoadAppliesAndPersistsTheIntendedMetadata() = runBlocking {
        seed(first, second)
        val library = newLibrary().apply { load() }
        val source = backup()
        val sourceBefore = source.readBytes()
        val result = library.restoreBackup(Uri.fromFile(source))

        assertEquals(1, result.restored)
        assertEquals(0, result.missing)
        assertEquals(saved, library.book(first.id))
        assertEquals(second, library.book(second.id))
        assertEquals(listOf(saved, second), LibraryIndex(libraryDirectory).load().records)
        assertArrayEquals("Restoring never changes the backup source", sourceBefore, source.readBytes())
    }

    @Test fun aRemovedCoverTargetIsReportedAndCannotChangeAnotherBooksCover() = runBlocking {
        seed(first, second)
        cover(first, Color.RED)
        val remainingCover = cover(second, Color.BLUE)
        val replacement = image(File(directory, "replacement.png"), Color.GREEN)
        val library = newLibrary().apply { load() }
        library.remove(first.id)
        val coverBefore = remainingCover.readBytes()
        val indexBefore = index.readBytes()

        assertThrows(MissingCoverBookException::class.java) {
            runBlocking { library.replaceCover(first.id, Uri.fromFile(replacement)) }
        }
        assertEquals(listOf(second), library.books.value)
        assertArrayEquals(coverBefore, remainingCover.readBytes())
        assertArrayEquals(indexBefore, index.readBytes())
        assertFalse(library.coverFile(first).exists())
    }

    @Test fun anUnusableCoverUriLeavesEveryBookAndCoverUntouched() = runBlocking {
        seed(first, second)
        val firstCover = cover(first, Color.RED)
        val secondCover = cover(second, Color.BLUE)
        val library = newLibrary().apply { load() }
        val firstBefore = firstCover.readBytes()
        val secondBefore = secondCover.readBytes()
        val indexBefore = index.readBytes()

        assertThrows(Exception::class.java) {
            runBlocking { library.replaceCover(first.id, Uri.fromFile(File(directory, "missing-image.png"))) }
        }
        assertEquals(listOf(first, second), library.books.value)
        assertArrayEquals(firstBefore, firstCover.readBytes())
        assertArrayEquals(secondBefore, secondCover.readBytes())
        assertArrayEquals(indexBefore, index.readBytes())
    }
}
