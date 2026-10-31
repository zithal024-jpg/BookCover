package com.bookcover.diagnostic.testing

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.library.BookRecord
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

/** Whole-page geometry for generated PDF pages; never reads or screenshots the owner's books. */
@RunWith(AndroidJUnit4::class)
class PdfPageVisibilityTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var fixtures: File

    @Before fun prepare() {
        check(target.packageName.endsWith(".qa")) { "PDF layout tests require the isolated QA app." }
        File(target.filesDir, "library").listFiles()?.forEach { it.deleteRecursively() }
        target.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).commit()
        fixtures = File(target.cacheDir, "pdf-page-visibility-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun cleanup() {
        scenario?.close()
        if (::fixtures.isInitialized) fixtures.deleteRecursively()
    }

    @Test fun tallWideAndMixedSizePagesFitInsideTheirVisibleViewport() {
        val book = importFixture()
        onActivity { it.openBookForTests(book) }
        val reader = waitForReader(book)
        for (page in 0..2) {
            waitFor("page ${page + 1} to load", 20_000) {
                onActivity { viewer(reader)?.let { it.pageCount == 3 && it.currentPage == page && it.getPageSize(page).height > 0 } } == true
            }
            // Readium uses an animated page turn; measure after the page has settled, not mid-swipe.
            SystemClock.sleep(1_000)
            val measured = onActivity { pageBounds(viewer(reader)!!, page) }
            val tolerance = 3f // viewer rounds bitmap tiles to integer pixels
            assertTrue("Page ${page + 1} clipped at left: $measured", measured.left >= measured.visibleLeft - tolerance)
            assertTrue("Page ${page + 1} clipped at top: $measured", measured.top >= measured.visibleTop - tolerance)
            assertTrue("Page ${page + 1} clipped at right: $measured", measured.right <= measured.visibleRight + tolerance)
            assertTrue("Page ${page + 1} clipped at bottom: $measured", measured.bottom <= measured.visibleBottom + tolerance)
            if (page < 2) onActivity { reader.next() }
        }
    }

    private data class PageBounds(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val visibleLeft: Float, val visibleTop: Float, val visibleRight: Float, val visibleBottom: Float,
        val zoom: Float, val width: Int, val height: Int,
    )

    /** Matches PDFView.drawPart's documented canvas offsets, including per-page centering. */
    private fun pageBounds(view: PDFView, page: Int): PageBounds {
        val fileField = PDFView::class.java.getDeclaredField("pdfFile").apply { isAccessible = true }
        val file = requireNotNull(fileField.get(view))
        fun pageOffset(name: String): Float = file.javaClass
            .getDeclaredMethod(name, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(file, page, view.zoom) as Float
        val primary = pageOffset("getPageOffset")
        val secondary = pageOffset("getSecondaryPageOffset")
        val x = view.currentXOffset + if (view.isSwipeVertical) secondary else primary
        val y = view.currentYOffset + if (view.isSwipeVertical) primary else secondary
        val size = view.getPageSize(page)
        val visible = Rect()
        check(view.getGlobalVisibleRect(visible)) { "PDF viewer is not visible" }
        val screen = IntArray(2).also(view::getLocationOnScreen)
        return PageBounds(x, y, x + size.width * view.zoom, y + size.height * view.zoom,
            (visible.left - screen[0]).toFloat(), (visible.top - screen[1]).toFloat(),
            (visible.right - screen[0]).toFloat(), (visible.bottom - screen[1]).toFloat(),
            view.zoom, view.width, view.height)
    }

    private fun importFixture(): BookRecord {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("library loading") { !onActivity { it.busyForTests } }
        val file = File(fixtures, "different-page-sizes.pdf")
        val pdf = PdfDocument()
        try {
            listOf(400 to 900, 1000 to 300, 500 to 700).forEachIndexed { index, (width, height) ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 24f }
                page.canvas.drawText("Generated top ${index + 1}", 10f, 30f, pen)
                page.canvas.drawText("Generated bottom ${index + 1}", 10f, height - 10f, pen)
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        return runBlocking { onActivity { it.libraryForTests }.import(Uri.fromFile(file)).first }
    }

    private fun waitForReader(book: BookRecord): PdfSession {
        waitFor("PDF navigator", 20_000) {
            onActivity { it.pdfSessionForTests?.let { reader -> reader.book.id == book.id && reader.fragment.isAdded } } == true
        }
        return onActivity { it.pdfSessionForTests!! }
    }

    private fun viewer(reader: PdfSession): PDFView? {
        fun find(view: View): PDFView? = when (view) {
            is PDFView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return reader.fragment.view?.let(::find)
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
