package com.bookcover.diagnostic.rewards

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min

/** Original, compact line emblems shared by the cabinet and exterior; no bitmap or publication edits. */
internal fun drawMoonlitGlyph(canvas: Canvas, tier: MoonlitTier, bounds: RectF, color: Int) {
    val size = min(bounds.width(), bounds.height())
    if (size <= 0f) return
    val save = canvas.save()
    canvas.translate(bounds.centerX() - size / 2, bounds.centerY() - size / 2)
    canvas.scale(size / 100f, size / 100f)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 3.4f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    val path = Path()
    when (tier) {
        MoonlitTier.COMMON -> {
            path.moveTo(24f, 77f); path.cubicTo(25f, 41f, 45f, 20f, 80f, 20f)
            path.cubicTo(78f, 55f, 55f, 75f, 24f, 77f); canvas.drawPath(path, paint)
            canvas.drawLine(20f, 82f, 68f, 33f, paint)
            canvas.drawLine(36f, 62f, 36f, 49f, paint); canvas.drawLine(47f, 52f, 63f, 51f, paint)
        }
        MoonlitTier.UNCOMMON -> {
            canvas.drawRoundRect(RectF(28f, 30f, 72f, 75f), 4f, 4f, paint)
            canvas.drawArc(RectF(39f, 15f, 61f, 37f), 180f, 180f, false, paint)
            canvas.drawLine(24f, 29f, 76f, 29f, paint); canvas.drawLine(25f, 80f, 75f, 80f, paint)
            path.moveTo(49f, 39f); path.cubicTo(55f, 49f, 63f, 58f, 51f, 66f)
            path.cubicTo(35f, 59f, 44f, 47f, 49f, 39f); paint.style = Paint.Style.FILL
            paint.alpha = 170; canvas.drawPath(path, paint)
        }
        MoonlitTier.RARE -> {
            canvas.drawCircle(37f, 35f, 18f, paint); canvas.drawCircle(37f, 35f, 6f, paint)
            canvas.drawLine(50f, 49f, 77f, 77f, paint)
            canvas.drawLine(64f, 64f, 72f, 56f, paint); canvas.drawLine(72f, 72f, 80f, 64f, paint)
        }
        MoonlitTier.EPIC -> {
            canvas.drawCircle(50f, 50f, 22f, paint)
            canvas.drawOval(RectF(13f, 35f, 87f, 65f), paint)
            val turn = canvas.save(); canvas.rotate(52f, 50f, 50f)
            canvas.drawOval(RectF(13f, 35f, 87f, 65f), paint); canvas.restoreToCount(turn)
            paint.style = Paint.Style.FILL; canvas.drawCircle(50f, 50f, 5f, paint)
            canvas.drawCircle(76f, 38f, 4f, paint)
        }
        MoonlitTier.LEGENDARY -> {
            canvas.drawRoundRect(RectF(24f, 18f, 77f, 80f), 5f, 5f, paint)
            canvas.drawLine(32f, 19f, 32f, 80f, paint); canvas.drawLine(24f, 85f, 76f, 85f, paint)
            path.moveTo(57f, 32f); path.cubicTo(37f, 36f, 41f, 62f, 62f, 60f)
            path.cubicTo(50f, 56f, 45f, 43f, 57f, 32f); canvas.drawPath(path, paint)
            canvas.drawLine(69f, 35f, 69f, 43f, paint); canvas.drawLine(65f, 39f, 73f, 39f, paint)
        }
        MoonlitTier.MYTHIC -> {
            path.moveTo(17f, 39f); path.lineTo(50f, 24f); path.lineTo(83f, 39f); path.close()
            canvas.drawPath(path, paint); canvas.drawLine(18f, 45f, 82f, 45f, paint)
            for (x in listOf(28f, 42f, 58f, 72f)) canvas.drawLine(x, 48f, x, 74f, paint)
            canvas.drawLine(18f, 79f, 82f, 79f, paint); canvas.drawLine(13f, 85f, 87f, 85f, paint)
            paint.style = Paint.Style.FILL; canvas.drawCircle(50f, 13f, 4f, paint)
        }
    }
    canvas.restoreToCount(save)
}
