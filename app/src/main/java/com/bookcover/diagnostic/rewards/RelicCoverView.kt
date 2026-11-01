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
    private var inscription = "My library"
    private var layout = relicCoverLayout(0, 0, 0, 0, appearance)
    private var palette = relicCoverPalette(intArrayOf(), intArrayOf(), 0xff18231f.toInt(), RelicColorMode.SET, 0)
    // Match ImageView's smooth scaling. This samples the original texture; there is no color filter
    // or altered/recolored/cropped bitmap, and the source is never replaced by the result.
    private val bitmapPaint = Paint().apply { isFilterBitmap = true }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val tracePath = Path()
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
        inscription = relicInscription(appearance.inscription)
        contentDescription = buildString {
            append(if (appearance.enabled && appearance.mode == RelicMode.STANDALONE) "Standalone book cover" else "Current book cover")
            if (boundedTitle.isNotBlank()) append(": $boundedTitle")
            if (appearance.enabled) {
                appearance.edgeTier?.let { append(". ${it.rarity} Moonlit Archive edges") }
                if (appearance.bookplate != RelicBookplate.NONE) append(". Bookplate: $inscription")
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
                layout.plate?.let { p ->
                    for (y in 0 until 4) for (x in 0 until 8) sample(p.left + p.width * (x + .5f) / 8, p.top + p.height * (y + .5f) / 4)
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
            appearance.colorMode, appearance.customAccent)
        perimeter.reset()
        perimeter.addRoundRect(layout.frame.inset(stroke() * 1.5f).androidRect(), stroke() * 2, stroke() * 2, Path.Direction.CW)
        perimeterMeasure.setPath(perimeter, false)
        prepareText()
        staticNode.setPosition(0, 0, width, height)
        val recording = staticNode.beginRecording(width, height)
        try { drawStatic(recording) } finally { staticNode.endRecording() }
        invalidate()
    }

    private fun stroke(): Float = (min(width, height) * .0035f).coerceAtLeast(1f)

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
        titleLayout = textLayout(title.ifBlank { "Moonlit Archive" }, titleSize, 4)
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
        canvas.drawColor(if (appearance.enabled) palette.background else art?.background ?: palette.background)
        if (appearance.enabled && appearance.mode == RelicMode.STANDALONE) drawStandalone(canvas)
        else if (art != null && !art.bitmap.isRecycled && layout.image.width > 0f && layout.image.height > 0f)
            canvas.drawBitmap(art.bitmap, null, layout.image.androidRect(), bitmapPaint)
        if (!appearance.enabled) return
        appearance.edgeTier?.takeIf { it.ordinal >= MoonlitTier.RARE.ordinal }?.let { drawEdges(canvas, it) }
        layout.plate?.let { drawPlate(canvas, it) }
        appearance.seal?.let { tier ->
            val rect = layout.seal.androidRect()
            paint.style = Paint.Style.FILL; paint.color = palette.plate; paint.alpha = 255
            canvas.drawOval(rect, paint)
            paint.style = Paint.Style.STROKE; paint.color = palette.ink; paint.strokeWidth = stroke() * .75f
            canvas.drawOval(rect, paint)
            rect.inset(rect.width() * .15f, rect.height() * .15f)
            drawMoonlitGlyph(canvas, tier, rect, palette.plateText)
        }
    }

    private fun drawStandalone(canvas: Canvas) {
        val rect = layout.image.androidRect()
        paint.style = Paint.Style.FILL; paint.color = palette.plate; paint.alpha = 255
        canvas.drawRect(rect, paint)
        val glyphSize = min(rect.width(), rect.height()) * .22f
        drawMoonlitGlyph(canvas, appearance.seal ?: appearance.edgeTier ?: MoonlitTier.COMMON,
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

    private fun star(canvas: Canvas, x: Float, y: Float, r: Float) {
        line(canvas, x - r, y, x + r, y, true); line(canvas, x, y - r, x, y + r, true)
    }

    private fun drawPlate(canvas: Canvas, p: RelicRect) {
        val rect = p.androidRect(); val inset = stroke() * 1.5f
        paint.style = Paint.Style.FILL; paint.color = palette.plate; paint.alpha = 255
        canvas.drawRoundRect(rect, inset * 2, inset * 2, paint)
        paint.style = Paint.Style.STROKE; paint.color = palette.plateText; paint.strokeWidth = stroke() * .65f
        rect.inset(inset, inset); canvas.drawRoundRect(rect, inset, inset, paint)
        if (appearance.bookplate == RelicBookplate.PRESTIGE) {
            rect.inset(inset * 1.7f, inset * 1.7f); canvas.drawRoundRect(rect, inset, inset, paint)
        }
        val size = p.height * .56f
        drawMoonlitGlyph(canvas, if (appearance.bookplate == RelicBookplate.PRESTIGE) MoonlitTier.MYTHIC else MoonlitTier.UNCOMMON,
            RectF(p.left + p.height * .17f, p.centerY - size / 2, p.left + p.height * .17f + size, p.centerY + size / 2), palette.plateText)
        val textLeft = p.left + p.height * .93f
        val textRight = p.right - p.height * .16f
        val available = (textRight - textLeft).coerceAtLeast(0f)
        if (available <= 0f) return
        textPaint.typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
        textPaint.color = palette.plateText; textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = p.height * .20f
        canvas.drawText("EX LIBRIS", (textLeft + textRight) / 2, p.top + p.height * .40f, textPaint)
        textPaint.textSize = p.height * .29f
        val label = TextUtils.ellipsize(inscription, textPaint, available, TextUtils.TruncateAt.END)
        canvas.drawText(label, 0, label.length, (textLeft + textRight) / 2, p.top + p.height * .76f, textPaint)
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
        paint.color = palette.ink; paint.alpha = 150; paint.strokeWidth = stroke() * 1.4f
        canvas.drawPath(tracePath, paint)
        perimeterMeasure.getPosTan(head, traceDot, null)
        paint.style = Paint.Style.FILL; paint.alpha = 220
        canvas.drawCircle(traceDot[0], traceDot[1], stroke() * 1.7f, paint)
        paint.alpha = 255
    }
}
