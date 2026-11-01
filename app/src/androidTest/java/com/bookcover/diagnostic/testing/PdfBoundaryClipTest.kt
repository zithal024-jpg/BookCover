package com.bookcover.diagnostic.testing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.MainActivity
import com.bookcover.diagnostic.reader.PdfSession
import com.github.barteksc.pdfviewer.PDFView
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Generated PDF only: a duplicated line cut by internal page-content clips, not by a page edge. */
@RunWith(AndroidJUnit4::class)
class PdfBoundaryClipTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var fixtures: File

    @Before fun prepare() {
        check(target.packageName.endsWith(".qa")) { "PDF tests require the isolated QA app." }
        File(target.filesDir, "library").listFiles()?.forEach { it.deleteRecursively() }
        target.getSharedPreferences("reader", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("samplesInstalled", true).putBoolean("autoCover", false).commit()
        fixtures = File(target.cacheDir, "pdf-boundary-clip-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun cleanup() {
        scenario?.close()
        if (::fixtures.isInitialized) fixtures.deleteRecursively()
    }

    @Test fun duplicatedLineRespectsInternalClipsOnBothPages() {
        val source = fixture("clipped.pdf", clipped = true)
        val clippedReference = fixture("clipped-reference.pdf", clipped = true)
        val fullReference = fixture("unclipped-reference.pdf", clipped = false)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("library loading") { !onActivity { it.busyForTests } }
        val book = runBlocking { onActivity { it.libraryForTests }.import(Uri.fromFile(source)).first }
        onActivity { it.openBookForTests(book) }
        waitFor("PDF navigator") {
            onActivity { it.pdfSessionForTests?.let { reader -> reader.book.id == book.id && reader.fragment.isAdded } } == true
        }
        val reader = onActivity { it.pdfSessionForTests!! }
        val clippedRenderer = PdfRenderer(ParcelFileDescriptor.open(clippedReference, ParcelFileDescriptor.MODE_READ_ONLY))
        val fullRenderer = PdfRenderer(ParcelFileDescriptor.open(fullReference, ParcelFileDescriptor.MODE_READ_ONLY))
        try {
            for (page in 0..1) {
                waitFor("physical page ${page + 1}") {
                    onActivity { viewer(reader)?.let { it.pageCount == 2 && it.currentPage == page && it.getPageSize(page).height > 0 } } == true
                }
                waitFor("the clipped generated boundary line on page ${page + 1}") {
                    onActivity { matchesClippedBoundary(viewer(reader)!!, clippedRenderer, fullRenderer, page) }
                }
                if (page == 0) onActivity { reader.next() }
            }
        } finally { clippedRenderer.close(); fullRenderer.close() }
    }

    /** Compare the displayed and clipped-reference ink, and prove the unclipped reference differs. */
    private fun matchesClippedBoundary(view: PDFView, clippedRenderer: PdfRenderer, fullRenderer: PdfRenderer, page: Int): Boolean {
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
        if (width <= 0 || height <= 0 || left < -2f || top < -2f ||
            left + width > view.width + 2f || top + height > view.height + 2f) return false
        val clippedReference = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val fullReference = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val shown = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            fun render(renderer: PdfRenderer, bitmap: Bitmap) {
                bitmap.eraseColor(Color.WHITE)
                val refPage = renderer.openPage(page)
                try { refPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                finally { refPage.close() }
            }
            render(clippedRenderer, clippedReference)
            render(fullRenderer, fullReference)
            view.draw(Canvas(shown))
            fun dark(pixel: Int) = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) < 180
            fun nearbyShownInk(x: Int, y: Int): Boolean {
                val sx = (left + x).roundToInt(); val sy = (top + y).roundToInt()
                for (dy in -2..2) for (dx in -2..2) {
                    val xx = sx + dx; val yy = sy + dy
                    if (xx in 0 until shown.width && yy in 0 until shown.height) {
                        val pixel = shown.getPixel(xx, yy)
                        if (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) < 300) return true
                    }
                }
                return false
            }
            // Clip at y=720 on the first page and y=72 on the second. The inner
            // band must match the clipped reference, while the outside band must
            // lack ink that exists in the otherwise identical unclipped reference.
            val clip = height * (if (page == 0) 720f else 72f) / 792f
            val y0 = if (page == 0) (height * 700f / 792f).toInt() else (height * 55f / 792f).toInt()
            val y1 = if (page == 0) (height * 738f / 792f).toInt() else (height * 90f / 792f).toInt()
            var visibleReferenceInk = 0; var matchedVisibleInk = 0
            var clippedAwayInk = 0; var unexpectedlyVisibleInk = 0
            for (y in y0.coerceAtLeast(0)..y1.coerceAtMost(height - 1)) {
                for (x in 0 until (width * 0.8f).toInt()) {
                    val outside = if (page == 0) y > clip + 3 else y < clip - 3
                    if (outside) {
                        if (dark(fullReference.getPixel(x, y)) && !dark(clippedReference.getPixel(x, y))) {
                            clippedAwayInk++
                            if (nearbyShownInk(x, y)) unexpectedlyVisibleInk++
                        }
                    } else if (dark(clippedReference.getPixel(x, y))) {
                        visibleReferenceInk++
                        if (nearbyShownInk(x, y)) matchedVisibleInk++
                    }
                }
            }
            assertTrue("The clipped reference must contain visible line ink", visibleReferenceInk >= 12)
            assertTrue("The unclipped reference must contain ink hidden by the clip", clippedAwayInk >= 12)
            return matchedVisibleInk.toDouble() / visibleReferenceInk >= 0.80 &&
                unexpectedlyVisibleInk.toDouble() / clippedAwayInk <= 0.20
        } finally { clippedReference.recycle(); fullReference.recycle(); shown.recycle() }
    }

    private fun fixture(name: String, clipped: Boolean): File {
        val file = File(fixtures, name)
        val document = PdfDocument()
        try {
            for (index in 0..1) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.save()
                // This affine-scaled polygon clips at PDF y=72 and y=720. The same
                // line appears at y=712..727 and y=64..79 on consecutive pages.
                page.canvas.translate(72f, 0f)
                page.canvas.scale(0.72f, 0.72f)
                if (clipped) {
                    val path = Path().apply {
                        fillType = Path.FillType.EVEN_ODD
                        moveTo(0f, 100f); lineTo(650f, 100f); lineTo(650f, 1000f)
                        lineTo(0f, 1000f); close()
                    }
                    page.canvas.clipPath(path)
                }
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 20f }
                page.canvas.drawText("GENERATED BOUNDARY LINE SPLIT EXAMPLE", 12f, if (index == 0) 1008f else 108f, paint)
                page.canvas.restore()
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return file
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
        var lastFailure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (predicate()) return } catch (error: Throwable) { lastFailure = error }
            SystemClock.sleep(80)
        }
        throw AssertionError("Timed out waiting for $description", lastFailure)
    }
}
