package com.bookcover.diagnostic.reader

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * PT-1: draws the page snapshot above the reader during a turn. It recomposes only when a turn
 * starts or ends; per-frame progress is read in the draw phase, so each frame only redraws.
 *
 * [bottomClip] is the strip at the bottom of the page that the progress row covers. The snapshot
 * includes that row, so it is not drawn there (the page's own bottom margin shows instead). The
 * clip in force when the snapshot was taken is used for the whole turn, because opening the
 * controls mid-turn removes the row from the screen but not from the bitmap (step 5 review, D11).
 */
@Composable
internal fun PageTurnOverlay(controller: PageTurnController, pageColor: Color, bottomClip: Dp, modifier: Modifier = Modifier) {
    // Kept across turns (above the early return), so its mesh arrays and shader are reused.
    val painter = remember(controller) { PageTurnPainter() }
    val density = LocalDensity.current
    val clip = with(density) { bottomClip.toPx() }
    SideEffect { controller.liveBottomClip = clip }
    if (!controller.overlayVisible) return
    val color = pageColor.toArgb()
    val pixelDensity = density.density
    Spacer(modifier.drawBehind {
        controller.snapshotVersion // A new snapshot redraws even at the same progress.
        // Read in draw too: a turn can end after this frame's recomposition, and the overlay must not
        // draw the outgoing page again in the frame before it is removed.
        if (!controller.overlayVisible) return@drawBehind
        val started = controller.drawStarted()
        // Until the turned page is drawn (and at the book's edge) the uncovered area is plain page;
        // a committed swipe that outran its page fades that cover off instead of popping (D10).
        val cover = if (!controller.pageReady || controller.rubberBand) 1f else controller.coverFade
        painter.draw(drawContext.canvas.nativeCanvas, controller.bitmap, size.width, size.height, controller.progress,
            controller.screenSign, cover, color, controller.turnBottomClip, pixelDensity)
        controller.drawFinished(started)
    })
}

/** Preallocated paints, shaders and mesh: drawing a frame allocates nothing. */
internal class PageTurnPainter {
    private val fillPaint = Paint()

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
        canvas: Canvas, bitmap: Bitmap?, width: Float, height: Float, progress: Float, sign: Int,
        cover: Float, pageColor: Int, bottomClip: Float, density: Float = 1f,
    ) {
        if (bitmap == null || width <= 0f || height <= 0f) return
        canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        if (cover > 0f) {
            // Page colour over the page underneath: opaque until it is drawn, then fading (D10).
            fillPaint.color = pageColor
            fillPaint.alpha = (255 * cover.coerceAtMost(1f)).toInt()
            canvas.drawRect(0f, 0f, width, height, fillPaint)
        }
        curl(canvas, bitmap, width, height, progress, sign, pageColor, bottomClip, density)
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
