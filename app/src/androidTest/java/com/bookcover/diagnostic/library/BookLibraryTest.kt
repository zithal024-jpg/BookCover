package com.bookcover.diagnostic.library

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.testing.NativeEpubFixture
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/** BookLibrary with real Android storage, Readium parsing and bitmaps, in a throwaway folder of the QA app. */
@RunWith(AndroidJUnit4::class)
class BookLibraryTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var context: Context
    private val index get() = File(directory, "library/books.json")

    @Before fun setUp() {
        check(target.packageName.endsWith(".qa")) { "Library tests run only in the isolated QA app." }
        directory = File(target.noBackupFilesDir, "library-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(target) { override fun getFilesDir(): File = directory }
    }

    @After fun tearDown() { directory.deleteRecursively() }

    private fun newLibrary() = BookLibrary(context, ReadiumEngine(context))
    private fun epub(fixed: Boolean = false): Uri =
        Uri.fromFile(NativeEpubFixture.write(File(directory, "source-${UUID.randomUUID()}").apply { mkdirs() }, fixed))
    private fun locator(progression: Double) = Locator(Url("chapter0.xhtml")!!, MediaType.XHTML,
        locations = Locator.Locations(totalProgression = progression))

    @Test fun importCopiesTheBookMakesACoverAndSurvivesReopening() = runBlocking {
        val library = newLibrary().apply { load() }
        val (book, added) = library.import(epub())
        assertTrue(added)
        assertEquals("Native renderer fixture", book.title)
        assertEquals("BookCover test suite", book.author)
        assertTrue("The EPUB is copied into private storage", library.epubFile(book).length() > 0)
        assertNotNull("A cover image is stored", BitmapFactory.decodeFile(library.coverFile(book).absolutePath))
        val reopened = newLibrary().apply { load() }
        assertEquals(listOf(book), reopened.books.value)
    }

    @Test fun importingTheSameFileTwiceKeepsOneCopy() = runBlocking {
        val library = newLibrary().apply { load() }
        val first = library.import(epub()).first
        val (second, added) = library.import(epub())
        assertFalse(added)
        assertEquals(first, second)
        assertEquals(1, library.books.value.size)
        library.import(epub(fixed = true))
        assertEquals("A different book is added", 2, library.books.value.size)
    }

    @Test fun removeDeletesTheEntryAndItsFiles() = runBlocking {
        val library = newLibrary().apply { load() }
        val book = library.import(epub()).first
        library.remove(book.id)
        assertTrue(library.books.value.isEmpty())
        assertFalse(library.epubFile(book).exists())
        assertFalse(library.coverFile(book).exists())
        assertFalse(library.thumbnailFile(book).exists())
        assertTrue("Removal is saved", newLibrary().apply { load() }.books.value.isEmpty())
    }

    @Test fun importSavesASmallThumbnail() = runBlocking {
        val library = newLibrary().apply { load() }
        val book = library.import(epub()).first
        val cover = BitmapFactory.decodeFile(library.coverFile(book).absolutePath)
        val saved = BitmapFactory.decodeFile(library.thumbnailFile(book).absolutePath)
        assertNotNull("A thumbnail file is written at import", saved)
        assertTrue("At most 400×600: ${saved.width}×${saved.height}", saved.width <= 400 && saved.height <= 600)
        assertEquals("Keeps the cover's shape", cover.width.toDouble() / cover.height, saved.width.toDouble() / saved.height, 0.01)
        val tile = library.thumbnailBitmap(book)
        assertTrue("A tile uses far less memory than the cover: ${tile.byteCount} vs ${cover.byteCount}",
            tile.byteCount * 2 < cover.byteCount && tile.byteCount <= 400 * 600 * 4)
    }

    @Test fun replacingACoverReplacesItsThumbnailAndCacheEntry() = runBlocking {
        val library = newLibrary().apply { load() }
        val book = library.import(epub()).first
        val before = library.thumbnailBitmap(book)
        assertSame("A repeated tile comes from memory", before, library.thumbnailBitmap(book))
        val image = File(directory, "cover.png").apply {
            outputStream().use { Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                .compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        SystemClock.sleep(5)
        library.replaceCover(book.id, Uri.fromFile(image))
        val tile = library.thumbnailBitmap(library.book(book.id)!!)
        assertNotSame("The old cover is not served from memory", before, tile)
        assertEquals("A small image is not enlarged", 40, tile.width)
        assertEquals(Color.RED, tile.getPixel(20, 30))
        assertEquals(Color.RED, BitmapFactory.decodeFile(library.thumbnailFile(book).absolutePath).getPixel(20, 30))
    }

    @Test fun aMissingThumbnailIsRebuiltFromTheCover() = runBlocking {
        val library = newLibrary().apply { load() }
        val book = library.import(epub()).first
        assertTrue(library.thumbnailFile(book).delete()) // Like a book imported before thumbnails existed.
        val tile = newLibrary().apply { load() }.thumbnailBitmap(book)
        assertTrue("Rebuilt at thumbnail size: ${tile.width}×${tile.height}", tile.width <= 400 && tile.height <= 600)
        assertTrue("The rebuilt thumbnail is saved for next time", library.thumbnailFile(book).exists())
    }

    @Test fun replaceCoverStoresTheNewImageAndChangesTheRevision() = runBlocking {
        val library = newLibrary().apply { load() }
        val book = library.import(epub()).first
        val image = File(directory, "cover.png").apply {
            outputStream().use { Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                .compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        SystemClock.sleep(5)
        library.replaceCover(book.id, Uri.fromFile(image))
        val updated = library.book(book.id)!!
        assertTrue(updated.coverRevision > book.coverRevision)
        val cover = BitmapFactory.decodeFile(library.coverFile(updated).absolutePath)
        assertEquals(40, cover.width)
        assertEquals(60, cover.height)
        assertEquals(Color.RED, cover.getPixel(20, 30))
        assertEquals(updated.coverRevision, newLibrary().apply { load() }.book(book.id)!!.coverRevision)
    }

    @Test fun positionsBookmarksAndCollectionsSurviveReopening() = runBlocking {
        val library = newLibrary().apply { load() }
        val book = library.import(epub()).first
        library.updatePosition(book.id, locator(0.42))
        library.bookmark(book.id, locator(0.2))
        library.move(book.id, "Favourites")
        val saved = newLibrary().apply { load() }.book(book.id)!!
        assertEquals(0.42, saved.progression, 0.0001)
        assertEquals(1, saved.bookmarks.size)
        assertEquals("Favourites", saved.collection)
        assertTrue(saved.lastReadAt > 0)
    }

    @Test fun namedBookmarksDeduplicateRenameAndKeepPreciseTargetsWhenReopened() = runBlocking {
        val library = newLibrary().apply { load() }
        val book = library.import(epub()).first
        val original = locator(0.2).copy(
            title = "Publisher chapter title",
            locations = Locator.Locations(progression = 0.3, position = 12, totalProgression = 0.2,
                otherLocations = mapOf("cssSelector" to "#paragraph-20", "fixtureExtension" to mapOf("keep" to "original"))),
            text = Locator.Text(before = "Original context", highlight = "The fixture's precise saved passage", after = "Following context"),
        )
        library.bookmark(book.id, original, name = "First name", chapterTitle = "The long chapter")
        // The same semantic text anchor may have a different resource progression after reflow.
        library.bookmark(book.id, original.copy(title = "Changed metadata",
            locations = original.locations.copy(progression = 0.31)), name = "Updated name", chapterTitle = "Changed chapter")
        val single = library.book(book.id)!!.bookmarks.single()
        assertEquals("Updated name", SavedBookmarks.name(single))
        assertEquals("The long chapter", SavedBookmarks.chapterTitle(single))
        assertEquals("An explicit duplicate save must not move the original saved location", 0.3,
            JSONObject(single).getJSONObject("locations").getDouble("progression"), 0.0)

        // Another point in the same coarse global position is still a separate bookmark.
        val another = original.copy(
            locations = original.locations.copy(progression = 0.32,
                otherLocations = original.locations.otherLocations + ("cssSelector" to "#paragraph-21")),
            text = Locator.Text(highlight = "Another distinct fixture passage"),
        )
        library.bookmark(book.id, another, name = "Second place", chapterTitle = "The long chapter")
        assertEquals(2, library.book(book.id)!!.bookmarks.size)
        library.renameBookmark(book.id, single, "Final name")

        val reopened = newLibrary().apply { load() }
        val saved = reopened.book(book.id)!!.bookmarks
        assertEquals(2, saved.size)
        val renamed = saved.single { SavedBookmarks.name(it) == "Final name" }
        val json = JSONObject(renamed)
        assertEquals("The long chapter", SavedBookmarks.chapterTitle(renamed))
        assertEquals(original.href.toString(), json.getString("href"))
        assertEquals(0.3, json.getJSONObject("locations").getDouble("progression"), 0.0)
        assertEquals("#paragraph-20", json.getJSONObject("locations").getString("cssSelector"))
        assertEquals("original", json.getJSONObject("locations").getJSONObject("fixtureExtension").getString("keep"))
        assertEquals(original.text.highlight, json.getJSONObject("text").getString("highlight"))
        assertEquals(original.text.before, json.getJSONObject("text").getString("before"))
        assertEquals(original.text.after, json.getJSONObject("text").getString("after"))

        reopened.removeBookmark(book.id, renamed)
        val afterRemoval = newLibrary().apply { load() }.book(book.id)!!.bookmarks
        assertEquals(1, afterRemoval.size)
        assertEquals("Second place", SavedBookmarks.name(afterRemoval.single()))
    }

    @Test fun anUnreadableIndexBlocksChangesAndIsLeftUntouched() = runBlocking {
        index.parentFile!!.mkdirs()
        index.writeText("{ not a library")
        val library = newLibrary()
        assertThrows(LibraryIndexException::class.java) { runBlocking { library.load() } }
        assertThrows(IllegalStateException::class.java) { runBlocking { library.import(epub()) } }
        assertEquals("{ not a library", index.readText())
    }

    @Test fun aFileThatIsNotAnEpubIsRejectedWithoutLeftovers() = runBlocking {
        val library = newLibrary().apply { load() }
        val text = File(directory, "notes.epub").apply { writeText("These are notes, not a book.") }
        assertThrows(Exception::class.java) { runBlocking { library.import(Uri.fromFile(text)) } }
        assertTrue(library.books.value.isEmpty())
        val leftovers = File(directory, "library").listFiles { file -> file.name.endsWith(".epub") }.orEmpty()
        assertTrue("No partial copies remain: ${leftovers.map { it.name }}", leftovers.isEmpty())
    }
}
