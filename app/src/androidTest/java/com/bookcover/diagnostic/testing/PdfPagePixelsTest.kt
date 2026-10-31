package com.bookcover.diagnostic.testing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Compares rendered marks, including page edges and tile seams, using generated PDFs only. */
@RunWith(AndroidJUnit4::class)
class PdfPagePixelsTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var fixtures: File

    @Before fun prepare() {
        check(target.packageName.endsWith(".qa")) { "PDF pixel tests require the isolated QA app." }
        File(target.filesDir, "library").listFiles()?.forEach { it.deleteRecursively() }
        target.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).commit()
        fixtures = File(target.cacheDir, "pdf-pixels-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun cleanup() {
        scenario?.close()
        if (::fixtures.isInitialized) fixtures.deleteRecursively()
    }

    @Test fun actualPagesMatchReferenceAtBothEdgesAndAcrossTiles() {
        val source = makeFixture()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("library loading") { !onActivity { it.busyForTests } }
        val book = runBlocking { onActivity { it.libraryForTests }.import(Uri.fromFile(source)).first }
        onActivity { it.openBookForTests(book) }
        val reader = waitForReader(book)
        val fd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(fd)
        try {
            for (index in 0 until 3) {
                waitFor("physical page ${index + 1} to load") {
                    onActivity { viewer(reader)?.let { it.pageCount == 3 && it.currentPage == index && it.getPageSize(index).height > 0 } } == true
                }
                // Page changes and tile rendering are asynchronous; keep checking the actual pixels.
                waitFor("reference marks and text on page ${index + 1}", 20_000) {
                    onActivity { matchesReference(viewer(reader)!!, renderer, index) }
                }
                if (index < 2) onActivity { reader.next() }
            }
            // Exercise the native gesture recognizer independently of the Next/Previous controls.
            // A right swipe goes from physical page 3 back to 2; a left swipe returns to 3.
            swipe(reader, 0.20f, 0.80f)
            waitForStablePage(reader, 1)
            waitFor("page 2 pixels after a right swipe") {
                onActivity { matchesReference(viewer(reader)!!, renderer, 1) }
            }
            swipe(reader, 0.80f, 0.20f)
            waitForStablePage(reader, 2)
            waitFor("page 3 pixels after a left swipe") {
                onActivity { matchesReference(viewer(reader)!!, renderer, 2) }
            }
        } finally { renderer.close() }
    }

    /** Send real touchscreen events in the visible reader area, never a navigator command. */
    private fun swipe(reader: PdfSession, from: Float, to: Float) {
        val bounds = onActivity {
            val view = requireNotNull(viewer(reader))
            Rect().also { check(view.getGlobalVisibleRect(it)) { "PDF viewer is not visible" } }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val y = bounds.top + bounds.height() * 0.60f
        fun x(fraction: Float) = bounds.left + bounds.width() * fraction
        fun send(down: Long, time: Long, action: Int, px: Float) {
            MotionEvent.obtain(down, time, action, px, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                .also { event -> try { instrumentation.sendPointerSync(event) } finally { event.recycle() } }
        }
        val down = SystemClock.uptimeMillis()
        send(down, down, MotionEvent.ACTION_DOWN, x(from))
        for (step in 1..12) {
            SystemClock.sleep(20)
            send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x(from + (to - from) * step / 12f))
        }
        send(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x(to))
        instrumentation.waitForIdleSync()
    }

    /** Verify physical index, saved locator and settled horizontal offset, not just callback delivery. */
    private fun waitForStablePage(reader: PdfSession, page: Int) {
        var previousOffset: Float? = null
        var stableSince = 0L
        waitFor("physical page ${page + 1} and locator after swipe") {
            val state = onActivity {
                val view = viewer(reader)
                Triple(view?.currentPage, reader.currentLocator?.locations?.position, view?.currentXOffset)
            }
            val now = SystemClock.uptimeMillis()
            val x = state.third
            if (state.first != page || state.second != page + 1 || x == null ||
                previousOffset == null || abs(x - previousOffset!!) > 0.5f) stableSince = now
            previousOffset = x
            state.first == page && state.second == page + 1 && x != null && now - stableSince >= 300
        }
    }
    private fun matchesReference(view: PDFView, renderer: PdfRenderer, page: Int): Boolean {
        val pdfField = PDFView::class.java.getDeclaredField("pdfFile").apply { isAccessible = true }
        val file = pdfField.get(view) ?: return false
        fun offset(name: String): Float = file.javaClass
            .getDeclaredMethod(name, Int::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(file, page, view.zoom) as Float
        val primary = offset("getPageOffset")
        val secondary = offset("getSecondaryPageOffset")
        val left = view.currentXOffset + if (view.isSwipeVertical) secondary else primary
        val top = view.currentYOffset + if (view.isSwipeVertical) primary else secondary
        val size = view.getPageSize(page)
        val width = (size.width * view.zoom).roundToInt()
        val height = (size.height * view.zoom).roundToInt()
        if (width <= 0 || height <= 0 || left < -2f || top < -2f || left + width > view.width + 2f || top + height > view.height + 2f) return false
        val reference = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val shown = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            reference.eraseColor(Color.WHITE)
            val referencePage = renderer.openPage(page)
            try { referencePage.render(reference, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
            finally { referencePage.close() }
            view.draw(Canvas(shown))
            var tested = 0
            var mismatches = 0
            // Sample inside horizontal black and white bands spanning tile boundaries and page edges.
            for (band in 0..8) for (delta in listOf(0f, 0.04f)) for (column in 1..19) {
                val x = (width * column / 20f).roundToInt().coerceIn(0, width - 1)
                val yFraction = 0.025f + band * 0.118f + delta
                if (yFraction >= 0.995f) continue
                val y = (height * yFraction).roundToInt().coerceIn(0, height - 1)
                val sx = (left + x).roundToInt()
                val sy = (top + y).roundToInt()
                if (sx !in 0 until shown.width || sy !in 0 until shown.height) return false
                val expected = reference.getPixel(x, y)
                val actual = shown.getPixel(sx, sy)
                fun dark(pixel: Int) = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) < 384
                if (dark(expected) != dark(actual)) mismatches++
                tested++
            }
            // Check the text itself, near both edges, against the independently rendered page.
            // A small neighbourhood absorbs subpixel placement and rasterizer differences.
            for (edge in listOf(0.08f, 0.96f)) {
                var ink = 0
                var found = 0
                val xLimit = (width * 0.65f).toInt().coerceAtMost(width - 1)
                val y0 = (height * (edge - 0.045f)).toInt().coerceAtLeast(0)
                val y1 = (height * (edge - 0.003f)).toInt().coerceAtMost(height - 1)
                for (y in y0..y1 step 2) for (x in 0..xLimit step 2) {
                    val px = reference.getPixel(x, y)
                    if (Color.red(px) + Color.green(px) + Color.blue(px) >= 180) continue
                    val sx = (left + x).roundToInt()
                    val sy = (top + y).roundToInt()
                    ink++
                    var matched = false
                    for (dy in -2..2) for (dx in -2..2) {
                        val xx = sx + dx; val yy = sy + dy
                        if (xx in 0 until shown.width && yy in 0 until shown.height) {
                            val value = shown.getPixel(xx, yy)
                            if (Color.red(value) + Color.green(value) + Color.blue(value) < 300) matched = true
                        }
                    }
                    if (matched) found++
                }
                if (ink < 8 || found.toDouble() / ink < 0.80) return false
            }
            return tested > 0 && mismatches <= tested / 20
        } finally { reference.recycle(); shown.recycle() }
    }

    private fun makeFixture(): File {
        val file = File(fixtures, "page-pixel-markers.pdf")
        val pdf = PdfDocument()
        try {
            listOf(400 to 900, 1000 to 300, 500 to 700).forEachIndexed { index, (width, height) ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
                // Wide lines cross multiple PDFView tiles; the first and last are near page edges.
                for (band in 0..8) {
                    val cy = height * (0.025f + band * 0.118f)
                    page.canvas.drawRect(width * 0.03f, cy - height * 0.006f,
                        width * 0.97f, cy + height * 0.006f, black)
                }
                black.textSize = minOf(width, height) * 0.065f
                page.canvas.drawText("TOP EDGE PAGE ${index + 1}", width * 0.04f, height * 0.08f, black)
                page.canvas.drawText("BOTTOM EDGE PAGE ${index + 1}", width * 0.04f, height * 0.96f, black)
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally { pdf.close() }
        return file
    }

    private fun waitForReader(book: BookRecord): PdfSession {
        waitFor("PDF navigator") { onActivity { it.pdfSessionForTests?.let { s -> s.book.id == book.id && s.fragment.isAdded } } == true }
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
    private fun waitFor(description: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var failure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (error: Throwable) { failure = error }
            SystemClock.sleep(80)
        }
        throw AssertionError("Timed out waiting for $description", failure)
    }
}
