package com.bookcover.diagnostic.testing

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.BookLibrary
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.library.GeneratedBooks
import com.bookcover.diagnostic.library.LibraryBackup
import com.bookcover.diagnostic.library.LibraryIndex
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** BC-26: actual picker contracts/callbacks and rendered Library messages, using disposable QA books. */
@RunWith(AndroidJUnit4::class)
class FriendlyErrorActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val folder get() = File(target.filesDir, "library")
    private lateinit var sources: File
    private lateinit var original: List<BookRecord>
    private lateinit var protectedBytes: Map<String, ByteArray>
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun prepareGeneratedQaLibrary() = runBlocking {
        check(target.packageName == "com.bookcover.diagnostic.qa") { "Only disposable isolated QA data may be changed." }
        resetQaBookFixtures()
        target.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).commit()
        sources = File(target.cacheDir, "friendly-errors-${UUID.randomUUID()}").apply { mkdirs() }
        val library = BookLibrary.forApp(target).apply { load() }
        original = listOf("First safety book", "Second safety book").mapIndexed { index, title ->
            library.import(Uri.fromFile(GeneratedBooks.write(sources, title))).first.copy(
                collection = "QA collection $index", lastReadAt = 1000L + index,
                progression = 0.25 + index * 0.25,
                locator = """{"href":"EPUB/chapter0.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":${0.25 + index * 0.25}}}""",
                bookmarks = listOf("""{"href":"EPUB/chapter0.xhtml","type":"application/xhtml+xml","text":{"highlight":"paragraph ${index + 1}"}}"""))
        }
        LibraryIndex(folder).apply { load(); save(original) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitIdle()
        assertEquals(original, onActivity { it.libraryForTests.books.value })
        // The primary/backup records and every original book asset must survive each failure byte-for-byte.
        protectedBytes = folder.listFiles().orEmpty().filter { file ->
            file.name in listOf("books.json", "books.last-good.json") || original.any { file.name.startsWith(it.id) }
        }.associate { it.name to it.readBytes() }
        original.forEach { book -> listOf(".epub", ".png", ".thumb.png").forEach { extension ->
            assertTrue("Seeded book asset $extension exists", protectedBytes.containsKey(book.id + extension))
        } }
    }

    @After fun close() {
        scenario?.close()
        if (::sources.isInitialized) sources.deleteRecursively()
    }

    @Test fun corruptEpubResultShowsUsefulErrorPreservesLibraryAndAllowsValidImport() {
        val corrupt = GeneratedBooks.write(sources, "Private corrupt fixture")
        RandomAccessFile(corrupt, "rw").use { it.setLength(60) } // Truncated ZIP, not a mocked exception.
        returnPickerResult(IMPORT, corrupt)
        assertRenderedFailure(IMPORT_ERROR, "corrupt-import-error", corrupt)
        assertOriginalLibraryIntact()
        assertNull("Import progress clears", onActivity { it.importProgressForTests })

        val valid = GeneratedBooks.write(sources, "Retry book")
        returnPickerResult(IMPORT, valid)
        assertVisibleMessage("Imported Retry book. Tap its cover to read.")
        awaitIdle()
        val records = onActivity { it.libraryForTests.books.value }
        assertEquals(3, records.size)
        assertEquals(original, records.filter { book -> original.any { it.id == book.id } })
        assertEquals(records, LibraryIndex(folder).load().records)
        original.forEach { book -> assertOriginalAssets(book) }
        val added = records.single { it.title == "Retry book" }
        assertTrue(File(folder, "${added.id}.epub").isFile)
        assertNoImportResidue()
    }

    @Test fun nonBackupResultShowsUsefulErrorPreservesLibraryAndAllowsValidRestore() {
        val wrong = File(sources, "private-not-a-backup.json").apply {
            writeText("""{"format":"some-other-document","detail":"/storage/emulated/0/private-book.epub"}""")
        }
        returnPickerResult(RESTORE, wrong)
        assertRenderedFailure(RESTORE_ERROR, "non-backup-restore-error", wrong)
        assertOriginalLibraryIntact()

        val restored = original.mapIndexed { index, book -> if (index == 0) book.copy(collection = "Valid retry collection") else book }
        val valid = File(sources, "valid-backup.json").apply { writeText(LibraryBackup.export(restored, exportedAt = 2000)) }
        returnPickerResult(RESTORE, valid)
        assertVisibleMessage("Restored 2 books from the backup.")
        awaitIdle()
        assertEquals(restored, onActivity { it.libraryForTests.books.value })
        assertEquals(restored, LibraryIndex(folder).load().records)
        original.forEach { book -> assertOriginalAssets(book) }
    }

    /** Dispatch raw results so Android parses the real contracts and invokes MainActivity's registered callbacks. */
    private fun returnPickerResult(key: String, source: File) {
        // Like returning from the system picker: an unlaunched raw test result is pending until ON_START.
        scenario!!.moveToState(Lifecycle.State.CREATED)
        onActivity { activity ->
            val routing = Bundle().also(activity.activityResultRegistry::onSaveInstanceState)
            val keys = routing.getStringArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS")!!
            val codes = routing.getIntegerArrayList("KEY_COMPONENT_ACTIVITY_REGISTERED_RCS")!!
            val requestCode = codes[keys.indexOf(key).also { check(it >= 0) }]
            val uri = Uri.fromFile(source)
            val result = if (key == IMPORT) Intent().apply { clipData = ClipData.newRawUri("Generated QA EPUB", uri) }
                else Intent().setData(uri)
            assertTrue("The picker route exists", activity.activityResultRegistry.dispatchResult(requestCode, Activity.RESULT_OK, result))
        }
        scenario!!.moveToState(Lifecycle.State.RESUMED)
    }

    private fun assertRenderedFailure(expected: String, screenshot: String, source: File) {
        assertVisibleMessage(expected)
        awaitIdle()
        val rendered = visibleTexts().joinToString("\n")
        listOf("Exception", "java.", "zip END", "container.xml", "content://", "/data/", "/storage/", source.name, source.absolutePath)
            .forEach { assertFalse("Rendered UI leaked $it: $rendered", rendered.contains(it, ignoreCase = true)) }
        listOf("Imported ", "Restored ", "Saved reading positions", "already in your library", "That backup has no books")
            .forEach { assertFalse("Failure must not report success: $rendered", rendered.contains(it, ignoreCase = true)) }
        assertTrue(onActivity { it.showsLibraryForTests })
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { directory ->
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(directory, "$screenshot.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    private fun assertOriginalLibraryIntact() {
        assertEquals("All in-memory records, positions and bookmarks survive", original, onActivity { it.libraryForTests.books.value })
        assertEquals("All persisted records, positions and bookmarks survive", original, LibraryIndex(folder).load().records)
        protectedBytes.forEach { (name, bytes) -> assertArrayEquals("Existing file $name changed", bytes, File(folder, name).readBytes()) }
        assertNoImportResidue()
    }

    private fun assertOriginalAssets(book: BookRecord) {
        protectedBytes.filterKeys { it.startsWith(book.id) }.forEach { (name, bytes) ->
            assertArrayEquals("Original book asset changed", bytes, File(folder, name).readBytes())
        }
    }

    private fun assertNoImportResidue() {
        assertTrue("No temporary import copies remain", folder.list().orEmpty().none { it.startsWith("import-") })
    }

    private fun assertVisibleMessage(expected: String) = waitFor("visible message: $expected") {
        if (visibleTexts().none { it == expected }) false
        else {
            // Accessibility layout can arrive before the return-to-activity window animation ends.
            try {
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.waitForIdle(500, 5_000)
                visibleTexts().any { it == expected }
            } catch (_: TimeoutException) { false }
        }
    }

    /** Inspect the rendered Android tree, not messageForTests or readerMessage(). Exclude off-screen nodes. */
    private fun visibleTexts(): List<String> {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != target.packageName) return emptyList()
        val window = Rect().also(root::getBoundsInScreen)
        val metrics = target.resources.displayMetrics
        val screen = Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
        val texts = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (node.isVisibleToUser && !bounds.isEmpty && window.contains(bounds) && screen.contains(bounds))
                node.text?.toString()?.let(texts::add)
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        return texts
    }

    private fun awaitIdle() = waitFor("busy state to clear") { !onActivity { it.busyForTests } }

    private fun <T> onActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        scenario!!.onActivity { result = runCatching { block(it) } }
        return result!!.getOrThrow()
    }

    private fun waitFor(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(60)
        }
        fail("Timed out waiting for $description")
    }

    private companion object {
        // Existing MainActivity declaration order, also used by BackupPickerRecreationTest.
        const val IMPORT = "activity_rq#0"
        const val RESTORE = "activity_rq#3"
        const val IMPORT_ERROR = "This file isn't a readable EPUB or PDF. It may be damaged, not fully downloaded, or a different kind of file. Try downloading it again. Your library is unchanged."
        const val RESTORE_ERROR = "That file isn't a BookCover backup. Choose a file made with Save backup. Your library is unchanged."
    }
}
