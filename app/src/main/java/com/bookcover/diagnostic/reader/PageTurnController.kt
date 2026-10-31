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
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.bookcover.diagnostic.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * PT-1: runs [PageTurnEngine] against the real reader when "Paper page turn" is on. It snapshots
 * the page with PixelCopy, turns Readium silently under the snapshot, waits until the new page is
 * drawn and curls the snapshot away. [PageTurnOverlay] draws from the state here without
 * recomposing per frame. With the setting off every turn is Readium's own ([PageTurnPath.NATIVE]).
 *
 * A swipe's snapshot is taken when the finger first touches the page ([touchDown]), not when the
 * swipe is recognised: copying then took 6 to 43 ms, during which the page sat still under a moving
 * finger and then jumped to it (owner report, 2026-10-31).
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
    /** A tap swallowed while the snapshot hides a turned page (layer coordinates): edge taps still turn. */
    private val onBlockedTap: (Float, Float) -> Unit = { _, _ -> },
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
    /** -1: the outgoing page moves left; +1: right. */
    var screenSign = -1; private set
    var rubberBand = false; private set
    var bitmap: Bitmap? = null; private set
    /** Alpha of the page-colour cover fading off a page that a committed swipe outran (read in draw). */
    var coverFade by mutableFloatStateOf(0f); private set
    /** The progress-row strip to leave undrawn, set by [PageTurnOverlay] from the screen's live state. */
    var liveBottomClip = 0f
    /** [liveBottomClip] when the snapshot was taken: it describes that bitmap, not today's controls (D11). */
    var turnBottomClip = 0f; private set

    private var captureJob: Job? = null
    private var readinessJob: Job? = null
    /** The snapshot started at finger-down for the swipe that may follow ([touchDown]). */
    private var earlySnapshot: Deferred<Boolean>? = null
    private var earlyClip = 0f
    private var earlyGeneration = -1
    /** The page the early copy shows: its WebView, scroll position and Readium locator at finger-down. */
    private var earlyWeb: WebView? = null
    private var earlyScrollX = 0
    private var earlyLocator: Locator? = null
    /** Bumped whenever the page on screen may have changed: an early snapshot from before is stale. */
    private var pageGeneration = 0
    private var snapshotEarly = false
    private var animating = false
    private var fading = false
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
    /** Readium's locator just before the current turn's silent turn; restored if an uncommitted swipe is dropped. */
    private var turnBefore: Locator? = null
    /** Until this uptime a native Readium settle scroll may be running (see [PageTurnRules.NATIVE_SETTLE_MILLIS]). */
    private var nativeSettleUntil = 0L

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
        pageGeneration++
        when (path) {
            PageTurnPath.NATIVE -> turnDirectly(direction, animated = true)
            PageTurnPath.INSTANT -> turnDirectly(direction, animated = false)
            PageTurnPath.ENGINE -> {
                turnSource = source
                screenSign = PageTurnRules.screenSign(direction, rightToLeft())
                dispatch(engine.request(direction, source, atBookEdge(direction)))
            }
        }
        if (!engine.busy) runPending()
    }

    override fun canTakeOverSwipe(dx: Float, longPressed: Boolean): Boolean =
        container != null && PageTurnRules.swipeDirection(dx, rightToLeft()) != null &&
            PageTurnRules.canTakeOverSwipe(conditions(), controlsVisible(), selectionActive(), longPressed,
                nativeSettling = SystemClock.uptimeMillis() < nativeSettleUntil)

    override fun touchesBlocked(): Boolean = overlayVisible && engine.coversOtherPage

    override fun blockedTap(x: Float, y: Float) = onBlockedTap(x, y)

    override fun nativeSwipeEnded() {
        pageGeneration++
        nativeSettleUntil = SystemClock.uptimeMillis() + PageTurnRules.NATIVE_SETTLE_MILLIS
    }

    /**
     * A finger touched the page. If this gesture could become a paper turn, copy the page now, so
     * the curl can follow the finger from the first move instead of a few frames later. A tap, long
     * press or vertical drag just leaves the copy unused; the next touch replaces it.
     */
    override fun touchDown() {
        dropEarlySnapshot()
        if (container == null || engine.busy || overlayVisible || overlayRecentlyShown || pending.isNotEmpty() || pendingPosted) return
        if (PageTurnRules.path(conditions()) != PageTurnPath.ENGINE || controlsVisible() || selectionActive()) return
        if (SystemClock.uptimeMillis() < nativeSettleUntil) return // The page may still be moving.
        earlyGeneration = pageGeneration
        earlyClip = liveBottomClip
        earlyWeb = visibleWebView()
        earlyScrollX = earlyWeb?.scrollX ?: 0
        earlyLocator = fragment.currentLocator.value
        earlySnapshot = scope.async {
            try { timedSnapshot() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
        }
    }

    /**
     * Forgets an unused early snapshot. A copy still in flight may yet write into [bitmap], so the
     * next snapshot gets a fresh bitmap rather than racing it.
     */
    private fun dropEarlySnapshot() {
        val early = earlySnapshot ?: return
        earlySnapshot = null
        earlyWeb = null
        if (!early.isCompleted) { early.cancel(); bitmap = null }
    }

    /** A swipe the user has not committed yet: its silent turn is provisional (reading pace ignores it). */
    val swipeUncommitted get() = engine.swipeUncommitted

    /** No turn running, on screen or queued. On-phone QA tests wait for this before the next gesture. */
    val idle get() = !engine.busy && !overlayVisible && pending.isEmpty() && !pendingPosted

    override fun swipeStarted(dx: Float) {
        val direction = PageTurnRules.swipeDirection(dx, rightToLeft()) ?: return
        if (engine.busy) finishNow()
        swipeDirection = direction
        lastAlong = 0f
        onSwipeTurn(direction)
        if (engine.busy) { swipeHeld = true; return } // Still waiting on a page: decide on release.
        swipeHeld = false
        turnSource = PageTurnSource.SWIPE
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
        // The curl keeps the lifted page edge under the finger.
        progress = if (engine.atBookEdge) PageTurnRules.rubberBand(lastAlong, width)
            else PageCurlGeometry.progressForDrag(lastAlong, width, travelRadius(width))
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

    /**
     * Fold, rotation, layout change or close: drop the overlay now. A committed turn is kept and
     * BC-07 continuity keeps the position. A swipe the user had not committed (cancelled, or still
     * under the finger) must not leave the reader on its silent turn (D8): with [reverse] Readium is
     * turned back at once, and the locator from before that turn is returned so the caller can save
     * or restore it instead of Readium's (possibly already turned) current locator.
     */
    fun abort(reverse: Boolean = true): Locator? {
        val outstanding = engine.uncommittedTurnOutstanding
        val owed = engine.undoOwed
        val direction = engine.direction
        val before = turnBefore
        pending.clear()
        swipeHeld = false
        pageGeneration++
        dropEarlySnapshot()
        captureJob?.cancel(); captureJob = null
        readinessJob?.cancel(); readinessJob = null
        stopAnimation()
        dispatch(engine.abort())
        turnBefore = null
        if (owed && reverse && direction != null) turnDirectly(direction.opposite, animated = false)
        return if (outstanding) before else null
    }

    /** Ends the controller; returns the position to save when an uncommitted swipe was dropped (see [abort]). */
    fun close(): Locator? {
        val revert = abort(reverse = false)
        container = null
        earlyWeb = null
        earlyLocator = null
        bitmap = null
        return revert
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
            PageTurnAction.Reveal -> animate(1f, PageTurnRules.TURN_MILLIS, reveal)
            is PageTurnAction.Settle -> {
                // A flick that commits before its page is drawn keeps the full-length settle, so the
                // snapshot does not slide off over plain page colour (D10).
                val toward = when {
                    action.awaitingPage -> 0f
                    action.target >= progress -> settleVelocity
                    else -> -settleVelocity
                }
                animate(action.target, PageTurnRules.settleMillis(progress, action.target, toward, travel()), settle)
            }
            PageTurnAction.RubberBand -> { rubberBand = true; animate(0f, PageTurnRules.RUBBER_BAND_MILLIS, linear, bounce = true) }
            PageTurnAction.FadeCover -> fadeCover()
            is PageTurnAction.Undo -> silentTurn(action.direction, action.token)
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
        pageGeneration++
        // Readium's animated turn ends in a settle scroll; swipes during it stay native (D4).
        if (animated) nativeSwipeEnded()
        runCatching { if (direction == PageTurnDirection.FORWARD) fragment.goForward(animated) else fragment.goBackward(animated) }
    }

    private fun capture() {
        captureJob?.cancel()
        // A swipe on the page this finger touched uses the copy started at finger-down.
        val early = earlySnapshot?.takeIf { engine.source == PageTurnSource.SWIPE && earlyPageStillShown() }
        if (early == null) dropEarlySnapshot() else earlySnapshot = null
        // A programmatic turn may have just closed the controls; a previous overlay may still be in
        // the last frame. Wait until the screen shows only the page before copying it.
        val waitFrames = if (overlayRecentlyShown || engine.source != PageTurnSource.SWIPE) 2 else 0
        captureJob = scope.launch {
            try {
                if (early != null && early.await()) {
                    snapshotEarly = true
                    turnBottomClip = earlyClip
                    dispatch(engine.captured(true))
                    return@launch
                }
                snapshotEarly = false
                repeat(waitFrames) { awaitFrame() }
                // Read the clip only now: a button turn has just closed the controls, and the overlay's
                // SideEffect updates liveBottomClip during the frames waited above. The bitmap describes
                // the screen at this moment; later control changes must not re-clip it (D11).
                turnBottomClip = liveBottomClip
                val ok = timedSnapshot()
                if (!ok) DiagnosticLog.record("PT-1 page snapshot failed; using Readium's own turn")
                dispatch(engine.captured(ok))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                DiagnosticLog.record("PT-1 page snapshot error: ${error.javaClass.simpleName}")
                dispatch(engine.captured(false))
            }
        }
    }

    /**
     * Whether the page under the finger is still the one copied at finger-down. The app also moves the
     * page without a turn (contents refinement, search, bookmark and link jumps), so the counter alone
     * is not enough: the same WebView must show the same scroll position and locator.
     */
    private fun earlyPageStillShown(): Boolean {
        if (earlyGeneration != pageGeneration) return false
        val web = visibleWebView() ?: return false
        return web === earlyWeb && web.scrollX == earlyScrollX && fragment.currentLocator.value == earlyLocator
    }

    private suspend fun timedSnapshot(): Boolean {
        val started = SystemClock.uptimeMillis()
        return snapshot().also { snapshotMillis = SystemClock.uptimeMillis() - started }
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
            if (action.turn != null) {
                // A swipe cancelled in these two frames no longer wants its turn (D7).
                val turn = engine.issueTurn(token)
                if (turn != null) {
                    turnBefore = fragment.currentLocator.value
                    silentTurn(turn, token)
                } else pageReady = true
            }
            dispatch(engine.overlayShown())
        }
    }

    private fun silentTurn(direction: PageTurnDirection, token: Int) {
        if (!engine.isCurrent(token)) return
        pageReady = false
        val mark = visibleWebView()?.let { it to it.scrollX }
        val before = fragment.currentLocator.value
        val accepted = runCatching {
            if (direction == PageTurnDirection.FORWARD) fragment.goForward(false) else fragment.goBackward(false)
        }.getOrDefault(false)
        readinessJob?.cancel()
        readinessJob = scope.launch {
            var moved = accepted
            try {
                moved = if (accepted) awaitPage(mark, before) else { readinessPath = "no-turn"; false }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { readinessPath = "error" }
            if (engine.isCurrent(token)) {
                pageReady = true
                // A refused or silently dropped turn never moved the page: a later cancel must not undo it.
                if (!moved) DiagnosticLog.record("PT-1 silent ${direction.name.lowercase()} turn did not move the page ($readinessPath)")
                dispatch(engine.pageReady(token, moved = moved))
            }
        }
    }

    /**
     * Readium turns asynchronously and announces the locator only after a 100 ms debounce, so watch
     * the page itself: a different visible WebView (chapter boundary) or a page-width scroll, then
     * one visual-state callback so Chromium has drawn it. Bounded; the locator change is the fallback
     * only when no page could be watched. Returns whether the reader really moved (D1).
     */
    private suspend fun awaitPage(mark: Pair<WebView, Int>?, before: Locator): Boolean {
        val width = container?.width ?: 0
        fun landedPage(): WebView? {
            val web = visibleWebView() ?: return null
            return web.takeIf { mark != null && PageTurnRules.pageChanged(web !== mark.first, web.scrollX - mark.second, width) }
        }
        var landed: WebView? = null
        if (mark != null) withTimeoutOrNull(400) {
            while (landed == null) {
                awaitFrame()
                landed = landedPage()
            }
        }
        if (landed == null) {
            val locatorChanged = withTimeoutOrNull(600) { fragment.currentLocator.first { it != before } } != null
            // Readium may have reported success and quietly done nothing (the start or end of the book).
            landed = landedPage()
            if (landed == null) {
                readinessPath = if (locatorChanged) "locator" else "timeout"
                return PageTurnRules.turnLanded(accepted = true, watched = mark != null, pageChanged = false, locatorChanged = locatorChanged)
            }
        }
        val web = landed ?: return false
        withTimeoutOrNull(150) { visualState(web) }
        awaitFrame()
        readinessPath = "page"
        return PageTurnRules.turnLanded(accepted = true, watched = true, pageChanged = true, locatorChanged = false)
    }

    private suspend fun visualState(web: WebView) = suspendCancellableCoroutine { continuation ->
        web.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { if (continuation.isActive) continuation.resume(Unit) }
        })
    }

    private fun animate(target: Float, millis: Long, interpolator: android.animation.TimeInterpolator, bounce: Boolean = false, fade: Boolean = false) {
        stopAnimation()
        animationFrom = if (bounce) 0f else progress
        animationTo = target
        bouncing = bounce
        fading = fade
        if (debuggable) frameMeter.animationStarted()
        animator.duration = millis
        animator.interpolator = interpolator
        animating = true
        animator.start()
    }

    private var bouncing = false

    /** The committed page is drawn after its slide ended: fade the page colour off it, no pop (D10). */
    private fun fadeCover() {
        coverFade = 1f
        animate(progress, PageTurnRules.COVER_FADE_MILLIS, linear, fade = true)
    }

    private fun frame(animation: ValueAnimator) {
        val fraction = animation.animatedFraction
        if (fading) { coverFade = 1f - fraction; return }
        progress = if (bouncing) (PageTurnRules.RUBBER_BAND_MAX * sin(PI * fraction)).toFloat()
            else animationFrom + (animationTo - animationFrom) * fraction
        if (debuggable) frameMeter.animationFrame(System.nanoTime())
    }

    private fun removeOverlay() {
        val source = turnSource
        pageGeneration++
        overlayVisible = false
        pageReady = true
        progress = 0f
        coverFade = 0f
        rubberBand = false
        if (debuggable) DiagnosticLog.record("PT-1 turn style=paper; snapshot=${snapshotMillis}ms via $snapshotMethod; " +
            "early=${if (snapshotEarly) "yes" else "no"}; source=${source?.name?.lowercase()}; ready=$readinessPath; " +
            "${frameMeter.summary()}")
        scope.launch {
            // The next snapshot must not copy this overlay; it is gone two frames from now.
            awaitFrame(); awaitFrame()
            if (!overlayVisible) overlayRecentlyShown = false
        }
    }

    /**
     * Whether the page on screen is the first (backward) or last (forward) page of the book. The
     * resource is read from the visible WebView's own page fragment, not from Readium's locator,
     * which trails a turn by its 100 ms debounce: a quick turn back after crossing into the first or
     * last chapter was taken as the book's edge and lost (D2, D13). The locator is only the fallback.
     */
    private fun atBookEdge(direction: PageTurnDirection): Boolean {
        val web = visibleWebView() ?: return false
        if (web.canScrollHorizontally(PageTurnRules.scrollDirection(direction, rightToLeft()))) return false
        val order = publication.readingOrder
        val href = (resourceOf(web) ?: fragment.currentLocator.value.href).removeFragment()
        val index = order.indexOfFirst { it.url().removeFragment() == href }
        return PageTurnRules.atBookEdge(index, order.size, direction)
    }

    /**
     * The reading-order resource shown by [web]: the link of Readium's page fragment whose view holds
     * it. Readium 3.4.0 keeps that link in the (internal) R2EpubPageFragment's arguments under "link";
     * null whenever that is not found, and the caller then falls back to the locator.
     */
    private fun resourceOf(web: WebView) = runCatching {
        fun holds(view: View?): Boolean {
            var at: Any? = web
            while (at is View) { if (at === view) return true; at = at.parent }
            return false
        }
        fun find(fragments: List<Fragment>): Link? {
            for (child in fragments) {
                if (!child.isAdded || !holds(child.view)) continue
                // The innermost fragment holding the WebView is its page.
                find(child.childFragmentManager.fragments)?.let { return it }
                child.arguments?.let { BundleCompat.getParcelable(it, "link", Link::class.java) }?.let { return it }
            }
            return null
        }
        if (fragment.isAdded) find(fragment.childFragmentManager.fragments)?.url() else null
    }.getOrNull()

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
