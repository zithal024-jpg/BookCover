package com.bookcover.diagnostic.rewards

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewTreeObserver
import com.bookcover.diagnostic.cover.CoverArtwork
import kotlin.math.min

/** Owns only drawing state. The publication's bitmap remains unchanged and is never recycled here. */
internal class RelicCoverView(context: Context) : View(context) {
    private var viewInitialized = false
    private var artwork: CoverArtwork? = null
    private var appearance = RelicAppearance()
    private var title = ""
    private var author = ""
    private var hasBound = false
    private var layout = relicCoverLayout(0, 0, 0, 0, appearance)
    private var palette = relicCoverPalette(intArrayOf(), intArrayOf(), 0xff18231f.toInt(), RelicColorMode.SET, 0)
    // Match ImageView's smooth scaling. This samples the original texture; there is no color filter
    // or altered/recolored/cropped bitmap, and the source is never replaced by the result.
    private val bitmapPaint = Paint().apply { isFilterBitmap = true }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tracePath = Path()
    private val traceHead = Path()
    private val perimeter = Path()
    private val perimeterMeasure = PathMeasure()
    private val traceDot = FloatArray(2)
    private val staticNode = RenderNode("BookCover relic static artwork")
    private var titleLayout: StaticLayout? = null
    private var authorLayout: StaticLayout? = null
    private var released = false
    private var presentationVisible = true
    private var observerRegistered = false
    private var framePending = false
    private var epoch = 0L
    private var phase = 0f
    private val globalVisibleRect = Rect()
    private var visibilityObserver: ViewTreeObserver? = null
    private val scrollVisibility = ViewTreeObserver.OnScrollChangedListener { updateMotion() }
    private val layoutVisibility = ViewTreeObserver.OnGlobalLayoutListener { updateMotion() }
    private var preparationCount = 0
    private val frame = object : Runnable {
        override fun run() {
            framePending = false
            if (!motionAllowed()) { epoch = 0L; return }
            phase = ((SystemClock.uptimeMillis() - epoch) % 10_000L) / 10_000f
            invalidate()
            postFrame()
        }
    }
    private val animationSetting = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { updateMotion() }
    }

    init { viewInitialized = true }

    internal val motionRunningForTests: Boolean get() = framePending && motionAllowed()
    internal val motionPhaseForTests: Float get() = phase
    internal val imageBoundsForTests: RectF get() = layout.image.androidRect()
    internal val frameBoundsForTests: RectF get() = layout.frame.androidRect()
    internal val preparationCountForTests: Int get() = preparationCount
    internal val backingColor: Int get() = if (appearance.enabled) palette.background else artwork?.background ?: palette.background

    fun bind(artwork: CoverArtwork?, appearance: RelicAppearance, title: String, author: String) {
        if (released) return
        val boundedTitle = title.take(500)
        val boundedAuthor = author.take(300)
        if (hasBound && this.artwork === artwork && this.appearance == appearance &&
            this.title == boundedTitle && this.author == boundedAuthor) {
            updateMotion()
            return
        }
        hasBound = true
        this.artwork = artwork
        this.appearance = appearance
        this.title = boundedTitle
        this.author = boundedAuthor
        contentDescription = buildString {
            append(if (appearance.enabled && appearance.mode == RelicMode.STANDALONE) "Standalone book cover" else "Current book cover")
            if (boundedTitle.isNotBlank()) append(": $boundedTitle")
            if (appearance.enabled) {
                appearance.edgeTier?.let { append(". ${it.rarity} ${familyName(appearance.edgeSet)} edges") }
                appearance.seal?.let { append(". ${it.rarity} ${familyName(appearance.sealSet)} relic seal") }
            }
        }
        prepareArtwork()
        updateMotion()
    }

    fun setPresentationVisible(visible: Boolean) {
        presentationVisible = visible
        updateMotion()
    }

    /** Terminal for this view: no callbacks, setting observers or retained bitmap references remain. */
    fun release() {
        if (released) return
        released = true
        removeCallbacks(frame); framePending = false
        epoch = 0L
        unregisterAnimationObserver()
        unregisterVisibilityObservers()
        staticNode.discardDisplayList()
        artwork = null
        titleLayout = null; authorLayout = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        prepareArtwork()
        updateMotion()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!released && !observerRegistered) {
            runCatching {
                context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animationSetting)
                observerRegistered = true
            }
        }
        if (!released) registerVisibilityObservers()
        updateMotion()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frame); framePending = false
        epoch = 0L
        unregisterAnimationObserver()
        unregisterVisibilityObservers()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateMotion()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateMotion()
    }

    private fun unregisterAnimationObserver() {
        if (observerRegistered) runCatching { context.contentResolver.unregisterContentObserver(animationSetting) }
        observerRegistered = false
    }

    private fun registerVisibilityObservers() {
        unregisterVisibilityObservers()
        viewTreeObserver.takeIf { it.isAlive }?.let { observer ->
            observer.addOnScrollChangedListener(scrollVisibility)
            observer.addOnGlobalLayoutListener(layoutVisibility)
            visibilityObserver = observer
        }
    }

    private fun unregisterVisibilityObservers() {
        visibilityObserver?.takeIf { it.isAlive }?.let { observer ->
            observer.removeOnScrollChangedListener(scrollVisibility)
            observer.removeOnGlobalLayoutListener(layoutVisibility)
        }
        visibilityObserver = null
    }

    private fun hasVisiblePixels(): Boolean = isShown && windowVisibility == VISIBLE &&
        getGlobalVisibleRect(globalVisibleRect) && globalVisibleRect.width() > 0 && globalVisibleRect.height() > 0

    private fun motionAllowed(): Boolean = relicMotionAllowed(appearance, isAttachedToWindow,
        hasVisiblePixels(), presentationVisible,
        runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(false), released)

    private fun updateMotion() {
        if (!viewInitialized || released) return
        if (motionAllowed()) {
            if (epoch == 0L) epoch = SystemClock.uptimeMillis() - (phase * 10_000L).toLong()
            postFrame()
        } else {
            removeCallbacks(frame); framePending = false
            epoch = 0L
            invalidate()
        }
    }

    private fun postFrame() {
        if (!framePending) {
            framePending = true
            postOnAnimationDelayed(frame, 33L)
        }
    }

    private fun RelicRect.androidRect() = RectF(left, top, right, bottom)

    private fun prepareArtwork() {
        if (!viewInitialized || released || width <= 0 || height <= 0) return
        preparationCount++
        val bitmap = artwork?.bitmap
        layout = relicCoverLayout(width, height, bitmap?.width ?: 0, bitmap?.height ?: 0, appearance)
        val global = ArrayList<Int>(256)
        val footprint = ArrayList<Int>(192)
        if (bitmap != null && !bitmap.isRecycled) runCatching {
            for (y in 0 until 16) for (x in 0 until 16) global += bitmap.getPixel(
                ((x + 0.5) * bitmap.width / 16).toInt().coerceIn(0, bitmap.width - 1),
                ((y + 0.5) * bitmap.height / 16).toInt().coerceIn(0, bitmap.height - 1))
            if (appearance.mode == RelicMode.OVERLAY && layout.image.width > 0f && layout.image.height > 0f) {
                fun sample(x: Float, y: Float) {
                    val px = ((x - layout.image.left) * bitmap.width / layout.image.width).toInt().coerceIn(0, bitmap.width - 1)
                    val py = ((y - layout.image.top) * bitmap.height / layout.image.height).toInt().coerceIn(0, bitmap.height - 1)
                    footprint += bitmap.getPixel(px, py)
                }
                val f = layout.frame
                for (i in 0 until 32) {
                    val t = i / 31f
                    sample(f.left + f.width * t, f.top); sample(f.left + f.width * t, f.bottom)
                    sample(f.left, f.top + f.height * t); sample(f.right, f.top + f.height * t)
                }
            } else {
                for (i in 0 until 32) {
                    val x = i * (bitmap.width - 1) / 31; val y = i * (bitmap.height - 1) / 31
                    footprint += bitmap.getPixel(x, 0); footprint += bitmap.getPixel(x, bitmap.height - 1)
                    footprint += bitmap.getPixel(0, y); footprint += bitmap.getPixel(bitmap.width - 1, y)
                }
            }
        }
        palette = relicCoverPalette(global.toIntArray(), footprint.toIntArray(), artwork?.background ?: 0xff18231f.toInt(),
            appearance.colorMode, appearance.customAccent, if (appearance.edgeTier != null) appearance.edgeSet else appearance.sealSet)
        perimeter.reset()
        perimeter.addRoundRect(layout.frame.inset(stroke() * 1.5f).androidRect(), stroke() * 2, stroke() * 2, Path.Direction.CW)
        perimeterMeasure.setPath(perimeter, false)
        prepareText()
        staticNode.setPosition(0, 0, width, height)
        val recording = staticNode.beginRecording(width, height)
        try { drawStatic(recording) } finally { staticNode.endRecording() }
        invalidate()
    }

    private fun stroke(): Float = (min(layout.frame.width, layout.frame.height) * .0045f).coerceAtLeast(1f)

    private fun familyName(set: RelicSet): String = when (set) {
        RelicSet.MOONLIT -> "Moonlit Archive"
        RelicSet.WAYFARER -> "Wayfarer's Atlas"
        RelicSet.HEARTHKEEPER -> "Hearthkeeper's Collection"
    }

    private fun prepareText() {
        val rect = layout.image
        val available = (rect.width * .80f).toInt().coerceAtLeast(1)
        val titleSize = min(rect.width * .10f, rect.height * .075f).coerceAtLeast(1f)
        fun textLayout(text: String, size: Float, lines: Int): StaticLayout {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = palette.plateText; textSize = size
                typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
            }
            return StaticLayout.Builder.obtain(text, 0, text.length, p, available)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
        }
        titleLayout = textLayout(title.ifBlank { familyName(if (appearance.edgeTier != null) appearance.edgeSet else appearance.sealSet) }, titleSize, 4)
        authorLayout = textLayout(author, titleSize * .45f, 2)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (released) return
        if (canvas.isHardwareAccelerated && staticNode.hasDisplayList()) canvas.drawRenderNode(staticNode) else drawStatic(canvas)
        if (appearance.enabled && appearance.edgeTier == MoonlitTier.MYTHIC) drawTrace(canvas)
    }

    private fun drawStatic(canvas: Canvas) {
        val art = artwork
        canvas.drawColor(backingColor)
        if (appearance.enabled && appearance.mode == RelicMode.STANDALONE) drawStandalone(canvas)
        else if (art != null && !art.bitmap.isRecycled && layout.image.width > 0f && layout.image.height > 0f)
            canvas.drawBitmap(art.bitmap, null, layout.image.androidRect(), bitmapPaint)
        if (!appearance.enabled) return
        appearance.edgeTier?.takeIf { it.ordinal >= MoonlitTier.RARE.ordinal }?.let { drawEdges(canvas, it) }
        appearance.seal?.let { tier ->
            val rect = layout.seal.androidRect()
            paint.style = Paint.Style.FILL; paint.color = palette.plate; paint.alpha = 255
            canvas.drawOval(rect, paint)
            paint.style = Paint.Style.STROKE; paint.color = palette.ink; paint.strokeWidth = stroke() * .75f
            canvas.drawOval(rect, paint)
            rect.inset(rect.width() * .15f, rect.height() * .15f)
            drawRelicGlyph(canvas, appearance.sealSet, tier, rect, palette.plateText)
        }
    }

    private fun drawStandalone(canvas: Canvas) {
        val rect = layout.image.androidRect()
        paint.style = Paint.Style.FILL; paint.color = palette.plate; paint.alpha = 255
        canvas.drawRect(rect, paint)
        val glyphSize = min(rect.width(), rect.height()) * .22f
        drawRelicGlyph(canvas, if (appearance.seal != null) appearance.sealSet else appearance.edgeSet,
            appearance.seal ?: appearance.edgeTier ?: MoonlitTier.COMMON,
            RectF(rect.centerX() - glyphSize / 2, rect.top + rect.height() * .13f,
                rect.centerX() + glyphSize / 2, rect.top + rect.height() * .13f + glyphSize), palette.plateText)
        var y = rect.top + rect.height() * .47f
        for (block in listOfNotNull(titleLayout, authorLayout)) {
            val saved = canvas.save(); canvas.translate(rect.centerX() - block.width / 2f, y)
            block.draw(canvas); canvas.restoreToCount(saved)
            y += block.height + rect.height() * .035f
        }
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, thin: Boolean = false) {
        paint.style = Paint.Style.STROKE; paint.alpha = 255; paint.strokeCap = Paint.Cap.ROUND
        paint.color = palette.keyline; paint.strokeWidth = stroke() * if (thin) 1.8f else 2.8f
        canvas.drawLine(x1, y1, x2, y2, paint)
        paint.color = palette.ink; paint.strokeWidth = stroke() * if (thin) .6f else 1.1f
        canvas.drawLine(x1, y1, x2, y2, paint)
    }

    private fun drawEdges(canvas: Canvas, tier: MoonlitTier) {
        when (appearance.edgeSet) {
            RelicSet.MOONLIT -> drawMoonlitEdges(canvas, tier)
            RelicSet.WAYFARER -> drawAtlasEdges(canvas, tier)
            RelicSet.HEARTHKEEPER -> drawHearthEdges(canvas, tier)
        }
    }

    private fun drawMoonlitEdges(canvas: Canvas, tier: MoonlitTier) {
        val f = layout.frame; val corner = min(f.width, f.height) * .11f
        for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
            val x = if (right) f.right else f.left; val y = if (bottom) f.bottom else f.top
            val dx = if (right) -1f else 1f; val dy = if (bottom) -1f else 1f
            line(canvas, x, y, x + dx * corner, y)
            line(canvas, x, y, x, y + dy * corner)
            if (tier.ordinal >= MoonlitTier.LEGENDARY.ordinal) {
                val offset = stroke() * 2.8f
                line(canvas, x + dx * offset, y + dy * offset, x + dx * corner * .75f, y + dy * offset, true)
                line(canvas, x + dx * offset, y + dy * offset, x + dx * offset, y + dy * corner * .75f, true)
            }
        }
        if (tier.ordinal >= MoonlitTier.EPIC.ordinal) {
            val gap = min(f.width, f.height) * .045f
            line(canvas, f.left + corner + gap, f.top, f.centerX - gap, f.top, true)
            line(canvas, f.centerX + gap, f.top, f.right - corner - gap, f.top, true)
            line(canvas, f.left + corner + gap, f.bottom, f.right - corner - gap, f.bottom, true)
            line(canvas, f.left, f.top + corner + gap, f.left, f.bottom - corner - gap, true)
            line(canvas, f.right, f.top + corner + gap, f.right, f.bottom - corner - gap, true)
            drawCrescent(canvas, f.centerX, f.top, gap * .65f)
        }
        if (tier.ordinal >= MoonlitTier.LEGENDARY.ordinal) {
            val r = stroke() * 1.7f
            for (x in listOf(f.left, f.right)) for (t in listOf(.32f, .68f)) star(canvas, x, f.top + f.height * t, r)
        }
    }

    private fun drawCrescent(canvas: Canvas, x: Float, y: Float, r: Float) {
        val path = Path().apply {
            moveTo(x + r * .35f, y - r); cubicTo(x - r * 1.2f, y - r, x - r * 1.2f, y + r, x + r * .35f, y + r)
            cubicTo(x - r * .35f, y + r * .3f, x - r * .35f, y - r * .3f, x + r * .35f, y - r)
        }
        paint.style = Paint.Style.STROKE; paint.color = palette.keyline; paint.strokeWidth = stroke() * 2
        canvas.drawPath(path, paint); paint.style = Paint.Style.FILL; paint.color = palette.ink; canvas.drawPath(path, paint)
    }

    private fun edgePath(canvas: Canvas, path: Path, thin: Boolean = false) {
        paint.style = Paint.Style.STROKE; paint.alpha = 255
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        paint.color = palette.keyline; paint.strokeWidth = stroke() * if (thin) 1.8f else 2.8f
        canvas.drawPath(path, paint)
        paint.color = palette.ink; paint.strokeWidth = stroke() * if (thin) .6f else 1.1f
        canvas.drawPath(path, paint)
    }

    private fun drawConnectingRails(canvas: Canvas, corner: Float, gap: Float) {
        val f = layout.frame
        line(canvas, f.left + corner + gap, f.top, f.centerX - gap, f.top, true)
        line(canvas, f.centerX + gap, f.top, f.right - corner - gap, f.top, true)
        line(canvas, f.left + corner + gap, f.bottom, f.right - corner - gap, f.bottom, true)
        line(canvas, f.left, f.top + corner + gap, f.left, f.bottom - corner - gap, true)
        line(canvas, f.right, f.top + corner + gap, f.right, f.bottom - corner - gap, true)
    }

    private fun drawAtlasEdges(canvas: Canvas, tier: MoonlitTier) {
        val f = layout.frame; val corner = min(f.width, f.height) * .11f
        for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
            val x = if (right) f.right else f.left; val y = if (bottom) f.bottom else f.top
            val dx = if (right) -1f else 1f; val dy = if (bottom) -1f else 1f
            fun contour(inset: Float, length: Float, thin: Boolean) {
                val cx = x + dx * inset; val cy = y + dy * inset
                val bend = length * .32f
                val path = Path().apply {
                    moveTo(cx + dx * length, cy); lineTo(cx + dx * bend, cy)
                    quadTo(cx, cy, cx, cy + dy * bend); lineTo(cx, cy + dy * length)
                }
                edgePath(canvas, path, thin)
            }
            contour(0f, corner, false)
            if (tier.ordinal >= MoonlitTier.LEGENDARY.ordinal) contour(stroke() * 2.8f, corner * .74f, true)
        }
        if (tier.ordinal >= MoonlitTier.EPIC.ordinal) {
            val gap = min(f.width, f.height) * .045f
            drawConnectingRails(canvas, corner, gap)
            diamond(canvas, f.centerX, f.top, gap * .63f)
            line(canvas, f.centerX, f.top - gap * .8f, f.centerX, f.top + gap * .8f, true)
        }
        if (tier.ordinal >= MoonlitTier.LEGENDARY.ordinal)
            for (x in listOf(f.left, f.right)) for (t in listOf(.32f, .68f))
                diamond(canvas, x, f.top + f.height * t, stroke() * 1.8f)
    }

    private fun drawHearthEdges(canvas: Canvas, tier: MoonlitTier) {
        val f = layout.frame; val corner = min(f.width, f.height) * .11f
        for (right in listOf(false, true)) for (bottom in listOf(false, true)) {
            val x = if (right) f.right else f.left; val y = if (bottom) f.bottom else f.top
            val dx = if (right) -1f else 1f; val dy = if (bottom) -1f else 1f
            fun copperCorner(inset: Float, length: Float, thin: Boolean) {
                val cx = x + dx * inset; val cy = y + dy * inset; val bevel = length * .20f
                val path = Path().apply {
                    moveTo(cx + dx * length, cy); lineTo(cx + dx * bevel, cy)
                    lineTo(cx, cy + dy * bevel); lineTo(cx, cy + dy * length)
                }
                edgePath(canvas, path, thin)
            }
            copperCorner(0f, corner, false)
            if (tier.ordinal >= MoonlitTier.LEGENDARY.ordinal) copperCorner(stroke() * 2.8f, corner * .74f, true)
        }
        if (tier.ordinal >= MoonlitTier.EPIC.ordinal) {
            val gap = min(f.width, f.height) * .045f
            drawConnectingRails(canvas, corner, gap)
            flame(canvas, f.centerX, f.top, gap * .66f)
        }
        if (tier.ordinal >= MoonlitTier.LEGENDARY.ordinal)
            for (x in listOf(f.left, f.right)) for (t in listOf(.32f, .68f))
                flame(canvas, x, f.top + f.height * t, stroke() * 1.8f)
    }

    private fun diamond(canvas: Canvas, x: Float, y: Float, radius: Float) {
        val path = Path().apply {
            moveTo(x, y - radius); lineTo(x + radius * .7f, y)
            lineTo(x, y + radius); lineTo(x - radius * .7f, y); close()
        }
        edgePath(canvas, path, true)
    }

    private fun flame(canvas: Canvas, x: Float, y: Float, radius: Float) {
        val path = Path().apply {
            moveTo(x + radius * .1f, y - radius)
            cubicTo(x + radius * .2f, y - radius * .3f, x + radius * .7f, y, x + radius * .6f, y + radius * .4f)
            cubicTo(x + radius * .4f, y + radius * 1.2f, x - radius * .7f, y + radius * 1.2f, x - radius * .6f, y + radius * .3f)
            cubicTo(x - radius * .5f, y - radius * .2f, x - radius * .1f, y - radius * .4f, x + radius * .1f, y - radius)
        }
        paint.style = Paint.Style.STROKE; paint.color = palette.keyline; paint.alpha = 255; paint.strokeWidth = stroke() * 1.8f
        canvas.drawPath(path, paint); paint.style = Paint.Style.FILL; paint.color = palette.ink; canvas.drawPath(path, paint)
    }

    private fun star(canvas: Canvas, x: Float, y: Float, r: Float) {
        line(canvas, x - r, y, x + r, y, true); line(canvas, x, y - r, x, y + r, true)
    }

    private fun drawTrace(canvas: Canvas) {
        val length = perimeterMeasure.length
        if (length <= 0f) return
        val head = phase * length
        val tail = head - length * .075f
        tracePath.reset()
        if (tail < 0f) {
            perimeterMeasure.getSegment(length + tail, length, tracePath, true)
            perimeterMeasure.getSegment(0f, head, tracePath, true)
        } else perimeterMeasure.getSegment(tail, head, tracePath, true)
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        // The same two-tone treatment as the static edges keeps motion visible on both light
        // and dark parts of an arbitrary overlay. This is a thin trace, without a glow or wash.
        paint.color = palette.keyline; paint.alpha = 190; paint.strokeWidth = stroke() * 2.8f
        canvas.drawPath(tracePath, paint)
        paint.color = palette.ink; paint.alpha = 230; paint.strokeWidth = stroke() * 1.3f
        canvas.drawPath(tracePath, paint)
        perimeterMeasure.getPosTan(head, traceDot, null)
        drawTraceHead(canvas)
        paint.alpha = 255
    }

    private fun drawTraceHead(canvas: Canvas) {
        val x = traceDot[0]; val y = traceDot[1]; val radius = stroke() * 1.6f
        if (appearance.edgeSet == RelicSet.MOONLIT) {
            paint.style = Paint.Style.FILL; paint.color = palette.keyline; paint.alpha = 200
            canvas.drawCircle(x, y, stroke() * 2.1f, paint)
            paint.color = palette.ink; paint.alpha = 240
            canvas.drawCircle(x, y, stroke() * 1.3f, paint)
            return
        }
        traceHead.reset()
        if (appearance.edgeSet == RelicSet.WAYFARER) {
            traceHead.moveTo(x, y - radius); traceHead.lineTo(x + radius * .75f, y)
            traceHead.lineTo(x, y + radius); traceHead.lineTo(x - radius * .75f, y); traceHead.close()
        } else {
            traceHead.moveTo(x, y - radius)
            traceHead.cubicTo(x + radius * .2f, y - radius * .3f, x + radius, y + radius * .1f, x + radius * .5f, y + radius * .7f)
            traceHead.cubicTo(x, y + radius * 1.3f, x - radius, y + radius * .5f, x - radius * .5f, y)
            traceHead.quadTo(x - radius * .15f, y - radius * .4f, x, y - radius)
        }
        paint.style = Paint.Style.STROKE; paint.color = palette.keyline; paint.alpha = 200; paint.strokeWidth = stroke() * 1.6f
        canvas.drawPath(traceHead, paint)
        paint.style = Paint.Style.FILL; paint.color = palette.ink; paint.alpha = 240; canvas.drawPath(traceHead, paint)
    }
}
