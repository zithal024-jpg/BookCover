package com.bookcover.diagnostic.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Small line glyphs for Library Home, drawn rather than taken from an icon library so the app keeps
 * no extra dependency. Each is decorative: the button around it carries the accessible label.
 */

@Composable
internal fun SearchGlyph(size: Dp = 20.dp, color: Color = LocalContentColor.current) = Canvas(Modifier.size(size)) {
    val stroke = 2.dp.toPx()
    drawCircle(color, radius = this.size.width * 0.3f, center = Offset(this.size.width * 0.42f, this.size.height * 0.42f), style = Stroke(stroke))
    drawLine(color, Offset(this.size.width * 0.64f, this.size.height * 0.64f), Offset(this.size.width * 0.9f, this.size.height * 0.9f),
        strokeWidth = stroke, cap = StrokeCap.Round)
}

@Composable
internal fun PlusGlyph(size: Dp = 18.dp, color: Color = LocalContentColor.current) = Canvas(Modifier.size(size)) {
    val stroke = 2.dp.toPx()
    val w = this.size.width; val h = this.size.height
    drawLine(color, Offset(w / 2, h * 0.15f), Offset(w / 2, h * 0.85f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.15f, h / 2), Offset(w * 0.85f, h / 2), strokeWidth = stroke, cap = StrokeCap.Round)
}

@Composable
internal fun CloseGlyph(size: Dp = 18.dp, color: Color = LocalContentColor.current) = Canvas(Modifier.size(size)) {
    val stroke = 2.dp.toPx()
    val w = this.size.width; val h = this.size.height
    drawLine(color, Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f), strokeWidth = stroke, cap = StrokeCap.Round)
}

/** Eight teeth around an open ring: the conventional Settings gear. */
@Composable
internal fun GearGlyph(size: Dp = 24.dp, color: Color = LocalContentColor.current) = Canvas(Modifier.size(size)) {
    val s = this.size.width
    val toothWidth = s * 0.17f
    for (index in 0 until 8) rotate(index * 45f) {
        drawRoundRect(color, topLeft = Offset(center.x - toothWidth / 2, center.y - s * 0.46f),
            size = Size(toothWidth, s * 0.2f), cornerRadius = CornerRadius(s * 0.04f))
    }
    drawCircle(color, radius = s * 0.255f, style = Stroke(s * 0.15f))
}

/** Three stacked dots: options for one book. */
@Composable
internal fun MoreGlyph(size: Dp = 24.dp, color: Color = LocalContentColor.current) = Canvas(Modifier.size(size)) {
    listOf(0.25f, 0.5f, 0.75f).forEach { y ->
        drawCircle(color, radius = 2.dp.toPx(), center = Offset(this.size.width / 2, this.size.height * y))
    }
}

/** A right-pointing chevron, for rows that open more detail. */
@Composable
internal fun ChevronGlyph(size: Dp = 16.dp, color: Color = LocalContentColor.current) = Canvas(Modifier.size(size)) {
    val stroke = 2.dp.toPx()
    val w = this.size.width; val h = this.size.height
    drawLine(color, Offset(w * 0.38f, h * 0.2f), Offset(w * 0.66f, h * 0.5f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, Offset(w * 0.66f, h * 0.5f), Offset(w * 0.38f, h * 0.8f), strokeWidth = stroke, cap = StrokeCap.Round)
}
