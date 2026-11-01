package com.bookcover.diagnostic.rewards

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min

/** Original, compact line emblems shared by the cabinet and exterior; no bitmap or publication edits. */
internal fun drawMoonlitGlyph(canvas: Canvas, tier: MoonlitTier, bounds: RectF, color: Int) =
    drawRelicGlyph(canvas, RelicSet.MOONLIT, tier, bounds, color)

internal fun drawRelicGlyph(canvas: Canvas, set: RelicSet, tier: MoonlitTier, bounds: RectF, color: Int) {
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
    when (set) {
        RelicSet.MOONLIT -> drawMoonlitEmblem(canvas, tier, paint, path)
        RelicSet.WAYFARER -> drawAtlasEmblem(canvas, tier, paint, path)
        RelicSet.HEARTHKEEPER -> drawHearthEmblem(canvas, tier, paint, path)
    }
    canvas.restoreToCount(save)
}

private fun drawMoonlitEmblem(canvas: Canvas, tier: MoonlitTier, paint: Paint, path: Path) {
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
}

private fun drawAtlasEmblem(canvas: Canvas, tier: MoonlitTier, paint: Paint, path: Path) {
    when (tier) {
        MoonlitTier.COMMON -> { // Trail Ribbon
            path.moveTo(33f, 19f); path.lineTo(67f, 19f); path.lineTo(67f, 79f)
            path.lineTo(50f, 68f); path.lineTo(33f, 79f); path.close()
            canvas.drawPath(path, paint)
            canvas.drawLine(43f, 30f, 57f, 30f, paint)
            canvas.drawLine(50f, 39f, 50f, 56f, paint)
        }
        MoonlitTier.UNCOMMON -> { // Pocket Compass
            canvas.drawCircle(50f, 53f, 28f, paint)
            canvas.drawArc(RectF(43f, 11f, 57f, 27f), 180f, 180f, false, paint)
            path.moveTo(57f, 35f); path.lineTo(56f, 59f); path.lineTo(43f, 71f)
            path.lineTo(44f, 47f); path.close(); canvas.drawPath(path, paint)
            canvas.drawLine(29f, 53f, 34f, 53f, paint); canvas.drawLine(66f, 53f, 71f, 53f, paint)
        }
        MoonlitTier.RARE -> { // Cartographer's Lens
            canvas.drawCircle(42f, 42f, 23f, paint)
            canvas.drawLine(59f, 59f, 81f, 81f, paint)
            path.moveTo(24f, 45f); path.cubicTo(34f, 30f, 45f, 60f, 59f, 38f)
            canvas.drawPath(path, paint)
            canvas.drawLine(37f, 27f, 37f, 32f, paint); canvas.drawLine(49f, 51f, 49f, 56f, paint)
        }
        MoonlitTier.EPIC -> { // Map of Hidden Roads
            path.moveTo(17f, 29f); path.lineTo(38f, 21f); path.lineTo(62f, 29f)
            path.lineTo(83f, 21f); path.lineTo(83f, 73f); path.lineTo(62f, 81f)
            path.lineTo(38f, 73f); path.lineTo(17f, 81f); path.close()
            canvas.drawPath(path, paint)
            canvas.drawLine(38f, 22f, 38f, 73f, paint); canvas.drawLine(62f, 29f, 62f, 80f, paint)
            path.reset(); path.moveTo(25f, 64f); path.cubicTo(34f, 40f, 58f, 66f, 74f, 42f)
            canvas.drawPath(path, paint); canvas.drawCircle(74f, 42f, 3f, paint)
        }
        MoonlitTier.LEGENDARY -> { // Atlas of Distant Realms
            canvas.drawRoundRect(RectF(22f, 17f, 79f, 81f), 4f, 4f, paint)
            canvas.drawLine(31f, 18f, 31f, 81f, paint); canvas.drawLine(23f, 86f, 78f, 86f, paint)
            canvas.drawCircle(55f, 45f, 17f, paint)
            canvas.drawOval(RectF(47f, 28f, 63f, 62f), paint)
            canvas.drawLine(38f, 45f, 72f, 45f, paint); canvas.drawLine(42f, 72f, 68f, 72f, paint)
        }
        MoonlitTier.MYTHIC -> { // Compass of Every Horizon
            canvas.drawCircle(50f, 50f, 26f, paint)
            path.moveTo(50f, 13f); path.lineTo(57f, 43f); path.lineTo(87f, 50f)
            path.lineTo(57f, 57f); path.lineTo(50f, 87f); path.lineTo(43f, 57f)
            path.lineTo(13f, 50f); path.lineTo(43f, 43f); path.close(); canvas.drawPath(path, paint)
            canvas.drawCircle(50f, 50f, 5f, paint)
        }
    }
}

private fun drawHearthEmblem(canvas: Canvas, tier: MoonlitTier, paint: Paint, path: Path) {
    fun flame(left: Float, top: Float, right: Float, bottom: Float) {
        val width = right - left; val height = bottom - top
        path.reset(); path.moveTo(left + width * .55f, top)
        path.cubicTo(left + width * .63f, top + height * .34f, right, top + height * .44f, right, top + height * .68f)
        path.cubicTo(right, bottom + height * .10f, left, bottom + height * .10f, left, top + height * .66f)
        path.cubicTo(left, top + height * .40f, left + width * .36f, top + height * .26f, left + width * .55f, top)
        canvas.drawPath(path, paint)
    }
    when (tier) {
        MoonlitTier.COMMON -> { // First Spark
            flame(34f, 23f, 67f, 73f)
            canvas.drawLine(21f, 45f, 25f, 50f, paint); canvas.drawLine(74f, 27f, 78f, 22f, paint)
            canvas.drawCircle(51f, 61f, 4f, paint)
        }
        MoonlitTier.UNCOMMON -> { // Keeper's Candle
            flame(43f, 13f, 58f, 37f)
            canvas.drawRoundRect(RectF(37f, 43f, 63f, 78f), 3f, 3f, paint)
            canvas.drawLine(31f, 82f, 69f, 82f, paint)
            canvas.drawLine(46f, 44f, 46f, 55f, paint); canvas.drawLine(56f, 44f, 56f, 50f, paint)
        }
        MoonlitTier.RARE -> { // Hearth Seal
            canvas.drawCircle(50f, 44f, 27f, paint)
            path.moveTo(33f, 66f); path.lineTo(29f, 84f); path.lineTo(45f, 76f)
            path.moveTo(67f, 66f); path.lineTo(71f, 84f); path.lineTo(55f, 76f); canvas.drawPath(path, paint)
            flame(40f, 27f, 60f, 58f)
        }
        MoonlitTier.EPIC -> { // Phoenix Feather
            path.moveTo(22f, 79f); path.cubicTo(22f, 50f, 48f, 29f, 66f, 17f)
            path.cubicTo(65f, 38f, 80f, 45f, 74f, 61f)
            path.cubicTo(66f, 77f, 44f, 80f, 22f, 79f); canvas.drawPath(path, paint)
            canvas.drawLine(22f, 83f, 64f, 35f, paint)
            canvas.drawLine(39f, 64f, 40f, 49f, paint); canvas.drawLine(49f, 53f, 64f, 54f, paint)
            canvas.drawCircle(81f, 23f, 3f, paint)
        }
        MoonlitTier.LEGENDARY -> { // Lantern of the Long Watch
            canvas.drawArc(RectF(40f, 12f, 60f, 31f), 180f, 180f, false, paint)
            path.moveTo(25f, 32f); path.lineTo(50f, 21f); path.lineTo(75f, 32f); path.close()
            canvas.drawPath(path, paint)
            canvas.drawRoundRect(RectF(31f, 37f, 69f, 76f), 3f, 3f, paint)
            canvas.drawLine(25f, 82f, 75f, 82f, paint); flame(42f, 45f, 58f, 68f)
        }
        MoonlitTier.MYTHIC -> { // The Everlasting Hearth
            path.moveTo(20f, 79f); path.lineTo(20f, 37f); path.cubicTo(20f, 9f, 80f, 9f, 80f, 37f)
            path.lineTo(80f, 79f); canvas.drawPath(path, paint)
            path.reset(); path.moveTo(33f, 76f); path.lineTo(33f, 48f)
            path.cubicTo(33f, 29f, 67f, 29f, 67f, 48f); path.lineTo(67f, 76f); canvas.drawPath(path, paint)
            canvas.drawLine(13f, 33f, 87f, 33f, paint); canvas.drawLine(14f, 84f, 86f, 84f, paint)
            flame(41f, 47f, 60f, 72f)
        }
    }
}
