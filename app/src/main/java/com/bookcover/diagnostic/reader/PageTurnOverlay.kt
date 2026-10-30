package com.bookcover.diagnostic.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * PT-1: draws the page snapshot above the reader during a turn. It recomposes only when a turn
 * starts or ends; per-frame progress is read in the draw phase, so each frame only redraws.
 *
 * [bottomClip] is the strip at the bottom of the page that the progress row covers. The snapshot
 * includes that row, so it is not drawn there (the page's own bottom margin shows instead).
 */
@Composable
internal fun PageTurnOverlay(controller: PageTurnController, pageColor: Color, bottomClip: Dp, modifier: Modifier = Modifier) {
    if (!controller.overlayVisible) return
    val density = LocalDensity.current
    val shadowWidth = with(density) { 20.dp.toPx() }
    val clip = with(density) { bottomClip.toPx() }
    val painter = remember { PageTurnPainter() }
    val color = pageColor.toArgb()
    Spacer(modifier.drawBehind {
        controller.snapshotVersion // A new snapshot redraws even at the same progress.
        painter.draw(drawContext.canvas.nativeCanvas, controller.bitmap, size.width, size.height, controller.progress,
            controller.screenSign, controller.style, !controller.pageReady || controller.rubberBand, color, shadowWidth, clip)
    })
}

/** Preallocated paints and shader: drawing a frame allocates nothing. */
internal class PageTurnPainter {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint()
    private val shadowPaint = Paint()
    private var shaderWidth = -1f

    fun draw(
        canvas: Canvas, bitmap: Bitmap?, width: Float, height: Float, progress: Float, sign: Int, style: PageTurnStyle,
        coverUnderneath: Boolean, pageColor: Int, shadowWidth: Float, bottomClip: Float,
    ) {
        if (bitmap == null || width <= 0f || height <= 0f) return
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        if (coverUnderneath) {
            // Until the turned page is drawn (or at the book's edge), the uncovered area is plain page.
            fillPaint.color = pageColor
            canvas.drawRect(0f, 0f, width, height, fillPaint)
        }
        when (style) {
            // Step 3 replaces PAPER with the cylindrical curl; until then both styles slide.
            PageTurnStyle.SLIDE, PageTurnStyle.PAPER -> slide(canvas, bitmap, width, height, progress, sign, shadowWidth, bottomClip)
        }
        canvas.restore()
    }

    private fun slide(canvas: Canvas, bitmap: Bitmap, width: Float, height: Float, progress: Float, sign: Int, shadowWidth: Float, bottomClip: Float) {
        val offset = sign * progress.coerceIn(0f, 1f) * width
        val pageBottom = (height - bottomClip).coerceAtLeast(0f)
        canvas.save()
        canvas.clipRect(0f, 0f, width, pageBottom)
        canvas.drawBitmap(bitmap, offset, 0f, bitmapPaint)
        canvas.restore()
        if (progress <= 0f || progress >= 1f || shadowWidth <= 0f) return
        if (shaderWidth != shadowWidth) {
            shaderWidth = shadowWidth
            shadowPaint.shader = LinearGradient(0f, 0f, shadowWidth, 0f, 0x38000000, 0x00000000, Shader.TileMode.CLAMP)
        }
        // A faint shadow just beyond the moving page's trailing edge: strongest early in the turn.
        shadowPaint.alpha = (255 * (1f - progress) * (progress * 12f).coerceAtMost(1f)).toInt().coerceIn(0, 255)
        val edge = if (sign < 0) offset + width else offset
        canvas.save()
        canvas.clipRect(0f, 0f, width, pageBottom)
        canvas.translate(edge, 0f)
        if (sign > 0) canvas.scale(-1f, 1f)
        canvas.drawRect(0f, 0f, shadowWidth, pageBottom, shadowPaint)
        canvas.restore()
    }
}
