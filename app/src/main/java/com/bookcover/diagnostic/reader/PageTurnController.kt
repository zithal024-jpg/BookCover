package com.bookcover.diagnostic.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.bookcover.diagnostic.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * PT-1: runs [PageTurnEngine] against the real reader. It snapshots the page with PixelCopy,
 * turns Readium silently under the snapshot, waits until the new page is drawn and animates the
 * snapshot away. [PageTurnOverlay] draws from the state here without recomposing per frame.
 */
internal class PageTurnController(
    private val activity: FragmentActivity,
    private val publication: Publication,
    private val fragment: EpubNavigatorFragment,
    private val conditions: () -> PageTurnConditions,
    private val controlsVisible: () -> Boolean,
    private val selectionActive: () -> Boolean,
    private val rightToLeft: () -> Boolean,
    private val pageColor: () -> Int,
    /** Side effects of a user turn (anchor, contents, activity); called once per taken-over swipe. */
    private val onSwipeTurn: (PageTurnDirection) -> Unit,
    /** A cancelled swipe is undoing its silent turn: the page did not really advance. */
    private val onTurnUndone: () -> Unit = {},
) : PageTurnGestures {
    private val engine = PageTurnEngine()
    private val scope = activity.lifecycleScope
    private val mainHandler = Handler(Looper.getMainLooper())
    private var container: View? = null

    // Overlay state. Only progress changes per frame, and it is read in the draw phase only.
    var overlayVisible by mutableStateOf(false); private set
    var pageReady by mutableStateOf(true); private set
    var progress by mutableFloatStateOf(0f); private set
    var snapshotVersion by mutableIntStateOf(0); private set
    /** The setting; a turn keeps the style it started with ([turnStyle]) even if this changes. */
    var style by mutableStateOf(PageTurnStyle.SLIDE)
    var turnStyle = PageTurnStyle.SLIDE; private set
    /** -1: the outgoing page moves left; +1: right. */
    var screenSign = -1; private set
    var rubberBand = false; private set
    var bitmap: Bitmap? = null; private set

    private var captureJob: Job? = null
    private var readinessJob: Job? = null
    private var animating = false
    private var animationFrom = 0f
    private var animationTo = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener { frame(it) }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (!animating) return
                animating = false
                dispatch(engine.animationEnded())
            }
        })
    }
    private val reveal = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val settle = DecelerateInterpolator(1f)
    private val linear = LinearInterpolator()

    // Swipe bookkeeping.
    private var swipeDirection: PageTurnDirection? = null
    private var swipeHeld = false
    private var lastAlong = 0f
    /** Turns asked for while one is still running, in order (a few quick key presses); see [turn]. */
    private val pending = ArrayDeque<Pair<PageTurnDirection, PageTurnSource>>()
    private var turnSource: PageTurnSource? = null
    private var overlayRecentlyShown = false

    // QA timing (debuggable builds only; failures are always logged).
    private val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var snapshotMillis = 0L
    private var snapshotMethod = ""
    private var readinessPath = ""
    private val frameMeter = PageTurnFrameMeter()
    private val visibleRect = Rect()

    fun attach(view: View) {
        container = view
    }

    /** Debug/QA builds time each overlay draw (UI-thread work); returns 0 when not measuring. */
    fun drawStarted(): Long = if (debuggable) System.nanoTime() else 0L

    fun drawFinished(started: Long) {
        if (started != 0L) frameMeter.drawn(System.nanoTime() - started)
    }

    /** How far the turn travels from progress 0 to 1, in pixels (the curl travels about twice the width). */
    private fun travel(): Float {
        val view = container ?: return 0f
        val width = view.width.toFloat()
        if (turnStyle != PageTurnStyle.PAPER) return width
        return PageCurlGeometry.maxTravel(width, travelRadius(width))
    }

    private fun travelRadius(width: Float): Float =
        PageCurlGeometry.radiusFor(width, container?.resources?.displayMetrics?.density ?: 1f)

    /** A user turn from a tap, button or key: [path] was decided by the session. */
    fun turn(direction: PageTurnDirection, source: PageTurnSource, path: PageTurnPath) {
        if (engine.busy) finishNow()
        // Still busy (a finger holds a swipe, or a page is still landing), or earlier turns are
        // queued: queue this one behind them. Aborting a live swipe here would keep its silent turn
        // and add this one on top. Queued turns re-decide their path when they run.
        if (engine.busy || pending.isNotEmpty()) {
            if (pending.size < PageTurnRules.MAX_QUEUED_TURNS) pending.addLast(direction to source)
            if (!engine.busy) runPending()
            return
        }
        startTurn(direction, source, path)
    }

    private fun startTurn(direction: PageTurnDirection, source: PageTurnSource, path: PageTurnPath) {
        when (path) {
            PageTurnPath.NATIVE -> turnDirectly(direction, animated = true)
            PageTurnPath.INSTANT -> turnDirectly(direction, animated = false)
            PageTurnPath.ENGINE -> {
                turnSource = source
                turnStyle = style
                screenSign = PageTurnRules.screenSign(direction, rightToLeft())
                dispatch(engine.request(direction, source, atBookEdge(direction)))
            }
        }
        if (!engine.busy) runPending()
    }

    override fun canTakeOverSwipe(dx: Float, longPressed: Boolean): Boolean =
        container != null && PageTurnRules.swipeDirection(dx, rightToLeft()) != null &&
            PageTurnRules.canTakeOverSwipe(conditions(), controlsVisible(), selectionActive(), longPressed)

    override fun swipeStarted(dx: Float) {
        val direction = PageTurnRules.swipeDirection(dx, rightToLeft()) ?: return
        if (engine.busy) finishNow()
        swipeDirection = direction
        lastAlong = 0f
        onSwipeTurn(direction)
        if (engine.busy) { swipeHeld = true; return } // Still waiting on a page: decide on release.
        swipeHeld = false
        turnSource = PageTurnSource.SWIPE
        turnStyle = style
        screenSign = PageTurnRules.screenSign(direction, rightToLeft())
        val path = PageTurnRules.path(conditions())
        dispatch(engine.beginSwipe(direction, atBookEdge(direction), withOverlay = path == PageTurnPath.ENGINE))
        swipeMoved(dx)
    }

    override fun swipeMoved(dx: Float) {
        val direction = swipeDirection ?: return
        lastAlong = PageTurnRules.along(dx, direction, rightToLeft())
        if (swipeHeld || !engine.isSwipe) return
        val phase = engine.phase
        if (phase != PageTurnEngine.Phase.CAPTURING && phase != PageTurnEngine.Phase.DRAGGING) return
        val width = (container?.width ?: 0).toFloat()
        rubberBand = engine.atBookEdge
        // The slide moves with the finger; the curl keeps the lifted page edge under the finger.
        progress = when {
            engine.atBookEdge -> PageTurnRules.rubberBand(lastAlong, width)
            turnStyle == PageTurnStyle.PAPER -> PageCurlGeometry.progressForDrag(lastAlong, width, travelRadius(width))
            else -> PageTurnRules.progress(lastAlong, width)
        }
    }

    override fun swipeEnded(dx: Float, velocityX: Float, cancelled: Boolean) {
        val direction = swipeDirection ?: return
        swipeDirection = null
        val rtl = rightToLeft()
        val along = if (cancelled) lastAlong else PageTurnRules.along(dx, direction, rtl)
        val velocityAlong = velocityX * PageTurnRules.screenSign(direction, rtl)
        val view = container
        val width = (view?.width ?: 0).toFloat()
        val density = view?.resources?.displayMetrics?.density ?: 1f
        val commit = !cancelled && PageTurnRules.shouldCommit(along, velocityAlong, width, density)
        if (swipeHeld) {
            swipeHeld = false
            if (commit) turn(direction, PageTurnSource.SWIPE, PageTurnRules.path(conditions()))
            return
        }
        if (!engine.isSwipe) return
        settleVelocity = velocityAlong
        dispatch(engine.release(commit))
    }

    private var settleVelocity = 0f

    /** Fold, rotation, layout change or close: drop the overlay now; BC-07 continuity keeps the position. */
    fun abort() {
        pending.clear()
        swipeHeld = false
        captureJob?.cancel(); captureJob = null
        readinessJob?.cancel(); readinessJob = null
        stopAnimation()
        dispatch(engine.abort())
    }

    fun close() {
        abort()
        container = null
        bitmap = null
    }

    /** Jump a running animation to its end so a new turn starts from a settled page. */
    private fun finishNow() {
        if (animating) animator.end()
    }

    private fun stopAnimation() {
        if (!animating) return
        animating = false
        animator.cancel()
    }

    private fun dispatch(action: PageTurnAction) {
        when (action) {
            PageTurnAction.None -> Unit
            PageTurnAction.Capture -> capture()
            is PageTurnAction.ShowOverlay -> showOverlay(action)
            PageTurnAction.Reveal -> animate(1f, PageTurnRules.turnMillis(turnStyle), reveal)
            is PageTurnAction.Settle -> {
                val toward = if (action.target >= progress) settleVelocity else -settleVelocity
                animate(action.target, PageTurnRules.settleMillis(progress, action.target, toward, travel(), PageTurnRules.turnMillis(turnStyle)), settle)
            }
            PageTurnAction.RubberBand -> { rubberBand = true; animate(0f, PageTurnRules.RUBBER_BAND_MILLIS, linear, bounce = true) }
            is PageTurnAction.Undo -> { onTurnUndone(); silentTurn(action.direction, action.token) }
            PageTurnAction.RemoveOverlay -> removeOverlay()
            is PageTurnAction.TurnDirectly -> turnDirectly(action.direction, action.animated)
        }
        if (!engine.busy) runPending()
    }

    /**
     * A turn queued while the engine was busy runs once it is idle, whichever way the last turn
     * ended (overlay removed, direct turn, failed snapshot). Read at run time, so abort/close drop it.
     */
    private fun runPending() {
        if (pending.isEmpty() || pendingPosted) return
        pendingPosted = true
        mainHandler.post {
            pendingPosted = false
            if (container == null) { pending.clear(); return@post }
            // A swipe may have started meanwhile; its end drains the queue again.
            if (engine.busy) return@post
            val (direction, source) = pending.removeFirstOrNull() ?: return@post
            startTurn(direction, source, PageTurnRules.path(conditions()))
        }
    }

    private var pendingPosted = false

    private fun turnDirectly(direction: PageTurnDirection, animated: Boolean) {
        runCatching { if (direction == PageTurnDirection.FORWARD) fragment.goForward(animated) else fragment.goBackward(animated) }
    }

    private fun capture() {
        captureJob?.cancel()
        // A programmatic turn may have just closed the controls; a previous overlay may still be in
        // the last frame. Wait until the screen shows only the page before copying it.
        val waitFrames = if (overlayRecentlyShown || engine.source != PageTurnSource.SWIPE) 2 else 0
        captureJob = scope.launch {
            try {
                repeat(waitFrames) { awaitFrame() }
                val started = SystemClock.uptimeMillis()
                val ok = snapshot()
                snapshotMillis = SystemClock.uptimeMillis() - started
                if (!ok) DiagnosticLog.record("PT-1 page snapshot failed; using Readium's own turn")
                dispatch(engine.captured(ok))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                DiagnosticLog.record("PT-1 page snapshot error: ${error.javaClass.simpleName}")
                dispatch(engine.captured(false))
            }
        }
    }

    private suspend fun snapshot(): Boolean {
        val view = container ?: return false
        val width = view.width
        val height = view.height
        if (!view.isAttachedToWindow || width <= 0 || height <= 0) return false
        val target = bitmapFor(width, height)
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val source = Rect(location[0], location[1], location[0] + width, location[1] + height)
        val result = withTimeoutOrNull(250) {
            suspendCancellableCoroutine { continuation ->
                try {
                    PixelCopy.request(activity.window, source, target, { continuation.resume(it) }, mainHandler)
                } catch (_: IllegalArgumentException) { continuation.resume(PixelCopy.ERROR_SOURCE_INVALID) }
            }
        }
        if (result == PixelCopy.SUCCESS) { snapshotMethod = "pixelcopy"; return true }
        // A late copy may still write into this bitmap; never reuse it.
        if (result == null) bitmap = null
        return drawFallback(width, height)
    }

    /** Slow software path; some WebView versions draw blank this way, so it is only the fallback. */
    private fun drawFallback(width: Int, height: Int): Boolean {
        val view = fragment.view ?: return false
        return try {
            val target = bitmapFor(width, height)
            Canvas(target).apply { drawColor(pageColor()) }.also { view.draw(it) }
            snapshotMethod = "draw"
            true
        } catch (_: Exception) { false }
    }

    /** One reusable mutable ARGB_8888 bitmap (PixelCopy cannot target HARDWARE); new only on resize. */
    private fun bitmapFor(width: Int, height: Int): Bitmap {
        bitmap?.takeIf { it.width == width && it.height == height && it.isMutable }?.let { return it }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
    }

    private fun showOverlay(action: PageTurnAction.ShowOverlay) {
        rubberBand = action.turn == null
        pageReady = action.turn == null
        snapshotVersion++
        if (!engine.isSwipe) progress = 0f
        overlayVisible = true
        overlayRecentlyShown = true
        readinessPath = ""
        if (debuggable) frameMeter.startTurn(container?.display?.refreshRate ?: 60f)
        val token = action.token
        scope.launch {
            // Frame N draws the overlay; frame N+1 starts after it reached the screen.
            awaitFrame(); awaitFrame()
            if (!engine.isCurrent(token)) return@launch
            action.turn?.let { silentTurn(it, token) }
            dispatch(engine.overlayShown())
        }
    }

    private fun silentTurn(direction: PageTurnDirection, token: Int) {
        if (!engine.isCurrent(token)) return
        pageReady = false
        val mark = visibleWebView()?.let { it to it.scrollX }
        val before = fragment.currentLocator.value
        val turned = runCatching {
            if (direction == PageTurnDirection.FORWARD) fragment.goForward(false) else fragment.goBackward(false)
        }.getOrDefault(false)
        readinessJob?.cancel()
        readinessJob = scope.launch {
            try {
                if (turned) awaitPage(mark, before) else readinessPath = "no-turn"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { readinessPath = "error" }
            if (engine.isCurrent(token)) {
                pageReady = true
                // A refused silent turn never moved the page: a later cancel must not undo it.
                if (!turned) DiagnosticLog.record("PT-1 silent ${direction.name.lowercase()} turn refused by Readium")
                dispatch(engine.pageReady(token, moved = turned))
            }
        }
    }

    /**
     * Readium turns asynchronously and announces the locator only after a 100 ms debounce, so watch
     * the page itself: a different visible WebView (chapter boundary) or a page-width scroll, then
     * one visual-state callback so Chromium has drawn it. Bounded; the locator change is the fallback.
     */
    private suspend fun awaitPage(mark: Pair<WebView, Int>?, before: Locator) {
        val width = container?.width ?: 0
        var landed: WebView? = null
        if (mark != null) withTimeoutOrNull(400) {
            while (landed == null) {
                awaitFrame()
                val web = visibleWebView()
                if (web != null && (web !== mark.first || abs(web.scrollX - mark.second) >= width * 0.4f)) landed = web
            }
        }
        landed?.let { web ->
            withTimeoutOrNull(150) { visualState(web) }
            awaitFrame()
            readinessPath = "page"
            return
        }
        val changed = withTimeoutOrNull(600) { fragment.currentLocator.first { it != before } } != null
        readinessPath = if (changed) "locator" else "timeout"
    }

    private suspend fun visualState(web: WebView) = suspendCancellableCoroutine { continuation ->
        web.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { if (continuation.isActive) continuation.resume(Unit) }
        })
    }

    private fun animate(target: Float, millis: Long, interpolator: android.animation.TimeInterpolator, bounce: Boolean = false) {
        stopAnimation()
        animationFrom = if (bounce) 0f else progress
        animationTo = target
        bouncing = bounce
        if (debuggable) frameMeter.animationStarted()
        animator.duration = millis
        animator.interpolator = interpolator
        animating = true
        animator.start()
    }

    private var bouncing = false

    private fun frame(animation: ValueAnimator) {
        val fraction = animation.animatedFraction
        progress = if (bouncing) (PageTurnRules.RUBBER_BAND_MAX * sin(PI * fraction)).toFloat()
            else animationFrom + (animationTo - animationFrom) * fraction
        if (debuggable) frameMeter.animationFrame(System.nanoTime())
    }

    private fun removeOverlay() {
        val source = turnSource
        overlayVisible = false
        pageReady = true
        progress = 0f
        rubberBand = false
        if (debuggable) DiagnosticLog.record("PT-1 turn style=${turnStyle.name.lowercase()}; snapshot=${snapshotMillis}ms via $snapshotMethod; " +
            "source=${source?.name?.lowercase()}; ready=$readinessPath; ${frameMeter.summary()}")
        scope.launch {
            // The next snapshot must not copy this overlay; it is gone two frames from now.
            awaitFrame(); awaitFrame()
            if (!overlayVisible) overlayRecentlyShown = false
        }
    }

    private fun atBookEdge(direction: PageTurnDirection): Boolean {
        val web = visibleWebView() ?: return false
        if (web.canScrollHorizontally(PageTurnRules.scrollDirection(direction, rightToLeft()))) return false
        val href = fragment.currentLocator.value.href.removeFragment()
        val order = publication.readingOrder
        val index = order.indexOfFirst { it.url().removeFragment() == href }
        if (index < 0) return false
        return if (direction == PageTurnDirection.FORWARD) index == order.lastIndex else index == 0
    }

    /** The reflowable page on screen: the shown WebView with the largest visible area. */
    private fun visibleWebView(): WebView? {
        val root = fragment.view ?: return null
        var best: WebView? = null
        var bestArea = 0
        fun walk(view: View) {
            if (view is WebView) {
                if (view.isShown && view.getGlobalVisibleRect(visibleRect)) {
                    val area = visibleRect.width() * visibleRect.height()
                    if (area > bestArea) { bestArea = area; best = view }
                }
                return
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(root)
        return best
    }

    private suspend fun awaitFrame(): Long = suspendCancellableCoroutine { continuation ->
        val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(it) }
        val choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { mainHandler.post { choreographer.removeFrameCallback(callback) } }
    }
}
