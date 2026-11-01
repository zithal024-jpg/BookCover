package com.bookcover.diagnostic.testing

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.BookFormat
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.library.LibraryIndex
import com.bookcover.diagnostic.reader.PdfSession
import com.github.barteksc.pdfviewer.PDFView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Generated PDF fixtures only; never touches the installed main app or its library. */
@RunWith(AndroidJUnit4::class)
class PdfReaderIntegrationTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val libraryFolder get() = File(target.filesDir, "library")
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var fixtureFolder: File

    @Before fun prepareIsolatedLibrary() {
        check(target.packageName.endsWith(".qa")) { "PDF tests require the isolated QA app." }
        resetQaBookFixtures()
        target.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).commit()
        fixtureFolder = File(target.cacheDir, "pdf-reader-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun cleanUp() {
        scenario?.close()
        if (::fixtureFolder.isInitialized) fixtureFolder.deleteRecursively()
    }

    @Test fun generatedPdfImportsTurnsAndReopensAtTheSamePage() {
        val book = importGeneratedPdf()
        assertEquals(BookFormat.PDF, book.format)
        assertTrue(File(libraryFolder, "${book.id}.pdf").isFile)
        onActivity { it.openBookForTests(book) }
        val reader = waitForReader(book)
        waitFor("the PDF viewer displays its first page") { viewerPage(reader) == 0 && pagePosition(reader) == 1 }
        val beforeTurn = onActivity { diagnosticState(reader) }
        val immediate = onActivity { reader.next(); diagnosticState(reader) }
        waitForSecondPage(reader, beforeTurn, immediate)
        onActivity { reader.next() }
        waitFor("the last page is visible and its position is reported") { viewerPage(reader) == 2 && pagePosition(reader) == 3 }
        onActivity { reader.flushPosition() }
        waitFor("the last page is saved") { savedPage(book) == 3 }

        scenario!!.recreate()
        waitFor("the PDF reader reopens") { onActivity { it.pdfSessionForTests?.book?.id == book.id } == true }
        val reopened = waitForReader(book)
        assertNotSame(reader, reopened)
        waitFor("the saved last page is displayed after recreation") { viewerPage(reopened) == 2 && pagePosition(reopened) == 3 }
        onActivity { reopened.previous() }
        waitFor("turning back works after recreation") { viewerPage(reopened) == 1 && pagePosition(reopened) == 2 }
    }

    @Test fun damagedPdfDoesNotAlterExistingBookOrIndex() {
        val book = importGeneratedPdf()
        val indexBefore = File(libraryFolder, "books.json").readBytes()
        val bytesBefore = File(libraryFolder, "${book.id}.pdf").readBytes()
        val damaged = File(fixtureFolder, "damaged.pdf").apply { writeText("%PDF-1.7\nthis is not a complete PDF") }
        val library = onActivity { it.libraryForTests }
        val failure = runCatching { runBlocking { library.import(Uri.fromFile(damaged)) } }.exceptionOrNull()
        assertNotNull("A damaged PDF must be refused", failure)
        assertArrayEquals(indexBefore, File(libraryFolder, "books.json").readBytes())
        assertArrayEquals(bytesBefore, File(libraryFolder, "${book.id}.pdf").readBytes())
        assertEquals(listOf(book.id), library.books.value.map { it.id })
    }

    private fun importGeneratedPdf(): BookRecord {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("the library loads") { !onActivity { it.busyForTests } }
        val file = File(fixtureFolder, "three-pages.pdf")
        val document = PdfDocument()
        try {
            for (page in 1..3) {
                val item = document.startPage(PdfDocument.PageInfo.Builder(400, 600, page).create())
                item.canvas.drawColor(if (page == 1) Color.WHITE else if (page == 2) Color.LTGRAY else Color.YELLOW)
                item.canvas.drawText("Generated page $page", 40f, 100f, Paint().apply { color = Color.BLACK; textSize = 32f })
                document.finishPage(item)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return runBlocking { onActivity { it.libraryForTests }.import(Uri.fromFile(file)).first }
    }

    private fun waitForReader(book: BookRecord): PdfSession {
        waitFor("PDF navigator attaches", 20_000) {
            onActivity { it.pdfSessionForTests?.let { reader -> reader.book.id == book.id && reader.fragment.isAdded } } == true
        }
        return onActivity { it.pdfSessionForTests!! }
    }

    private fun pagePosition(reader: PdfSession): Int? = onActivity { reader.currentLocator?.locations?.position }
    private fun savedPage(book: BookRecord): Int? = runCatching {
        val locator = LibraryIndex(libraryFolder).load().records.single { it.id == book.id }.locator ?: return null
        org.readium.r2.shared.publication.Locator.fromJSON(org.json.JSONObject(locator))?.locations?.position
    }.getOrNull()

    /** Inspect the actual rendered viewer, not just a locator that may precede page loading. */
    private fun viewerPage(reader: PdfSession): Int? = onActivity {
        fun find(view: View): View? {
            if (view.javaClass.name == "com.github.barteksc.pdfviewer.PDFView") return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
            return null
        }
        val view = reader.fragment.view?.let(::find) ?: return@onActivity null
        val count = view.javaClass.getMethod("getPageCount").invoke(view) as Int
        if (count != 3) null else view.javaClass.getMethod("getCurrentPage").invoke(view) as Int
    }

    /** Generated fixture only: show the viewer, navigator and position without book content. */
    private fun diagnosticState(reader: PdfSession): String {
        fun find(view: View): PDFView? = when (view) {
            is PDFView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        val view = reader.fragment.view?.let(::find) ?: return "viewer missing"
        val file = runCatching { PDFView::class.java.getDeclaredField("pdfFile").apply { isAccessible = true }.get(view) }.getOrNull()
        fun offset(page: Int): Any? = runCatching {
            file?.javaClass?.getDeclaredMethod("getPageOffset", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                ?.apply { isAccessible = true }?.invoke(file, page, view.zoom)
        }.getOrNull()
        val size0 = view.getPageSize(0)
        val size1 = view.getPageSize(1)
        val locator = reader.fragment.currentLocator.value
        return "viewPage=${view.currentPage}, pageCount=${view.pageCount}, savedPosition=${reader.currentLocator?.locations?.position}, " +
            "navPosition=${locator.locations.position}, navFragments=${locator.locations.fragments}, " +
            "x=${view.currentXOffset}, y=${view.currentYOffset}, width=${view.width}, height=${view.height}, zoom=${view.zoom}, " +
            "page0=${size0.width}x${size0.height}, page1=${size1.width}x${size1.height}, " +
            "pageOffset0=${offset(0)}, pageOffset1=${offset(1)}"
    }

    private fun waitForSecondPage(reader: PdfSession, before: String, immediate: String) {
        val start = SystemClock.uptimeMillis()
        val deadline = start + 15_000
        var at500: String? = null
        var at1500: String? = null
        var last = immediate
        while (SystemClock.uptimeMillis() < deadline) {
            val elapsed = SystemClock.uptimeMillis() - start
            val snapshot = onActivity {
                val state = diagnosticState(reader)
                val position = reader.currentLocator?.locations?.position
                Triple(state, position, reader.fragment.currentLocator.value.locations.position)
            }
            last = snapshot.first
            if (elapsed >= 500 && at500 == null) at500 = last
            if (elapsed >= 1500 && at1500 == null) at1500 = last
            if (viewerPage(reader) == 1 && snapshot.second == 2) return
            SystemClock.sleep(60)
        }
        throw AssertionError("Page two did not settle. Before: $before; immediate: $immediate; " +
            "500ms: $at500; 1500ms: $at1500; last: $last")
    }
    private fun <T> onActivity(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        scenario!!.onActivity { result = runCatching { block(it) } }
        return result!!.getOrThrow()
    }
    private fun waitFor(description: String, timeout: Long = 15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var failure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (error: Throwable) { failure = error }
            SystemClock.sleep(60)
        }
        throw AssertionError("Timed out waiting for $description", failure)
    }
}
