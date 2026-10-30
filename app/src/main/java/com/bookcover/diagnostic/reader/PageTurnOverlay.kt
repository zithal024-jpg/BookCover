package com.bookcover.diagnostic.reader

import android.graphics.Bitmap
import android.graphics.BitmapShader
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
    val pixelDensity = density.density
    Spacer(modifier.drawBehind {
        controller.snapshotVersion // A new snapshot redraws even at the same progress.
        val started = controller.drawStarted()
        painter.draw(drawContext.canvas.nativeCanvas, controller.bitmap, size.width, size.height, controller.progress,
            controller.screenSign, controller.turnStyle, !controller.pageReady || controller.rubberBand, color, shadowWidth,
            clip, pixelDensity)
        controller.drawFinished(started)
    })
}

/** Preallocated paints, shaders and mesh: drawing a frame allocates nothing. */
internal class PageTurnPainter {
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint()
    private val shadowPaint = Paint()
    private var shaderWidth = -1f

    // Paper curl.
    private val mesh = PageCurlMesh()
    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val washPaint = Paint()
    private var meshBitmap: Bitmap? = null
    /** A unit-wide soft shadow (dark at x = 0), stretched and mirrored with the canvas. */
    private val castPaint = Paint().apply {
        shader = LinearGradient(0f, 0f, 1f, 0f, intArrayOf(0x55000000, 0x1A000000, 0x00000000), floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP)
    }

    fun draw(
        canvas: Canvas, bitmap: Bitmap?, width: Float, height: Float, progress: Float, sign: Int, style: PageTurnStyle,
        coverUnderneath: Boolean, pageColor: Int, shadowWidth: Float, bottomClip: Float, density: Float = 1f,
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
            PageTurnStyle.SLIDE -> slide(canvas, bitmap, width, height, progress, sign, shadowWidth, bottomClip)
            PageTurnStyle.PAPER -> curl(canvas, bitmap, width, height, progress, sign, pageColor, bottomClip, density)
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

    /**
     * The paper curl: the page wraps around a small cylinder ([PageCurlMesh]). Drawn in this order:
     * the soft shadow cast on the revealed page, the front of the page (darkening toward the crest),
     * a faint shadow under the folded-back flap, then the back of the page (its print mirrored and
     * washed lighter, like paper seen from behind). Canvas.drawVertices (hardware accelerated from
     * API 29) is used rather than drawBitmapMesh because drawBitmapMesh spaces texture coordinates
     * evenly, and the mesh needs columns exactly on the fold line and the crest.
     */
    private fun curl(
        canvas: Canvas, bitmap: Bitmap, width: Float, height: Float, progress: Float, sign: Int, pageColor: Int,
        bottomClip: Float, density: Float,
    ) {
        val pageBottom = (height - bottomClip).coerceAtLeast(0f)
        if (pageBottom <= 0f) return
        if (meshBitmap !== bitmap) {
            // Once per snapshot bitmap (it is reused between turns), never per frame.
            meshBitmap = bitmap
            meshPaint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val radius = PageCurlGeometry.radiusFor(width, density)
        val p = progress.coerceIn(0f, 1f)
        mesh.update(p, width, pageBottom, radius, sign, bitmap.width / width, bitmap.height / height,
            lift = minOf(radius * 0.25f, pageBottom * 0.008f))
        mesh.updateColors(lighten(pageColor), WASH_ALPHA)

        val fade = 1f - p
        // How far the paper has lifted toward the crest: shadows grow with it.
        val lifted = ((width - mesh.fold) / (radius * HALF_PI)).coerceIn(0f, 1f)
        if (mesh.reach < width && lifted > 0f && fade > 0f) {
            castShadow(canvas, width, sign, mesh.reach, radius * CAST_SHADOW_SHARE * (0.5f + 0.5f * lifted), 255f * lifted * fade, pageBottom)
        }
        val vertexFloats = mesh.vertexCount * 2
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, vertexFloats, mesh.vertices, 0, mesh.textures, 0,
            mesh.frontColors, 0, mesh.frontIndices, 0, mesh.frontIndices.size, meshPaint)
        if (!mesh.backVisible) return
        // The flap floats above the page: a short shadow beyond its edge, deeper as it rises.
        val rise = (mesh.edgeZ / (2f * radius)).coerceIn(0f, 1f)
        if (mesh.edgeX > 0f && rise > 0f && fade > 0f) {
            castShadow(canvas, width, sign, mesh.edgeX, -radius * FLAP_SHADOW_SHARE * rise, FLAP_SHADOW_ALPHA * rise * fade, pageBottom)
        }
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, vertexFloats, mesh.vertices, 0, mesh.textures, 0,
            mesh.backColors, 0, mesh.backIndices, 0, mesh.backIndices.size, meshPaint)
        canvas.drawVertices(Canvas.VertexMode.TRIANGLES, vertexFloats, mesh.vertices, 0, null, 0,
            mesh.washColors, 0, mesh.backIndices, 0, mesh.backIndices.size, washPaint)
    }

    /**
     * A soft shadow starting at normalized X [at] and fading over [extent] pixels (negative: toward
     * the left). The normalized frame peels from the right edge; [sign] > 0 mirrors it.
     */
    private fun castShadow(canvas: Canvas, width: Float, sign: Int, at: Float, extent: Float, alpha: Float, pageBottom: Float) {
        canvas.save()
        canvas.clipRect(0f, 0f, width, pageBottom)
        if (sign > 0) { canvas.translate(width, 0f); canvas.scale(-1f, 1f) }
        canvas.translate(at, 0f)
        canvas.scale(extent, 1f)
        castPaint.alpha = alpha.toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, 1f, pageBottom, castPaint)
        canvas.restore()
    }

    private companion object {
        const val HALF_PI = (Math.PI / 2).toFloat()
        /** The back of the page shows its print only faintly through the paper: mostly page colour. */
        const val WASH_ALPHA = 0xD0
        const val CAST_SHADOW_SHARE = 0.9f
        const val FLAP_SHADOW_SHARE = 0.35f
        const val FLAP_SHADOW_ALPHA = 150f

        /** The page colour nudged 8% toward white: "slightly lighter" in every theme, Night included. */
        fun lighten(color: Int): Int {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return (0xFF shl 24) or ((r + ((255 - r) * 0.08f).toInt()) shl 16) or
                ((g + ((255 - g) * 0.08f).toInt()) shl 8) or (b + ((255 - b) * 0.08f).toInt())
        }
    }
}
