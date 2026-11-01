package com.bookcover.diagnostic.testing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests only a generated PDF in the isolated QA app; no owner book is read or saved. */
@RunWith(AndroidJUnit4::class)
class PdfClippedTextExtractionTest {
    @Test
    @SdkSuppress(minSdkVersion = 35)
    fun platformTextExtractionRecoversTheSameClippedLineOnBothPhysicalPages() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName.endsWith(".qa")) { "PDF extraction test requires the isolated QA app." }
        val fixture = File(target.cacheDir, "generated-clipped-text-${UUID.randomUUID()}.pdf")
        try {
            createClippedFixture(fixture)
            val descriptor = ParcelFileDescriptor.open(fixture, ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(descriptor).use { renderer ->
                assertEquals(2, renderer.pageCount)
                for (index in 0..1) {
                    renderer.openPage(index).use { page ->
                        assertEquals(index, page.index)
                        assertEquals(PAGE_WIDTH, page.width)
                        assertEquals(PAGE_HEIGHT, page.height)

                        // First prove the independently generated page truly contains a clipped line:
                        // ink is present on the visible side of the clip and absent on the other side.
                        val bitmap = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            if (index == 0) {
                                assertTrue("page 1 should show upper fragments above the bottom clip", darkPixels(bitmap, 695, 720) > 10)
                                assertEquals("page 1 must be clipped below the bottom boundary", 0, darkPixels(bitmap, 721, 746))
                            } else {
                                assertEquals("page 2 must be clipped above the top boundary", 0, darkPixels(bitmap, 46, 71))
                                assertTrue("page 2 should show lower fragments below the top clip", darkPixels(bitmap, 72, 97) > 10)
                            }
                        } finally { bitmap.recycle() }

                        val contents = page.textContents
                        val extracted = contents.joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()
                        assertTrue("page ${index + 1} did not recover the whole generated line: $extracted", extracted.contains(SEAM_TEXT))
                    }
                }
            }
        } finally {
            fixture.delete()
        }
    }

    private fun createClippedFixture(file: File) {
        val document = PdfDocument()
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 24f
            }
            for (index in 0..1) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.save()
                page.canvas.clipRect(72f, TOP_CLIP, 540f, BOTTOM_CLIP)
                val baseline = if (index == 0) BOTTOM_CLIP + 4f else TOP_CLIP + 4f
                page.canvas.drawText(SEAM_TEXT, 90f, baseline, paint)
                page.canvas.restore()
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
    }

    private fun darkPixels(bitmap: Bitmap, top: Int, bottomExclusive: Int): Int {
        var count = 0
        for (y in top until bottomExclusive) for (x in 75 until 535) {
            val color = bitmap.getPixel(x, y)
            if (Color.red(color) + Color.green(color) + Color.blue(color) < 600) count++
        }
        return count
    }

private companion object {
        const val PAGE_WIDTH = 612
        const val PAGE_HEIGHT = 792
        const val TOP_CLIP = 72f
        const val BOTTOM_CLIP = 720f
        const val SEAM_TEXT = "Generated line across pages"
    }
}
