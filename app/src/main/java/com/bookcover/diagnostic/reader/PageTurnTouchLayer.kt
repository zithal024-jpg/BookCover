package com.bookcover.diagnostic.reader

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.fragment.app.FragmentContainerView
import kotlin.math.abs

/** What the touch layer asks of the page-turn controller. */
internal interface PageTurnGestures {
    /** Whether a horizontal swipe that just crossed the decision slop may be taken over. */
    fun canTakeOverSwipe(dx: Float, longPressed: Boolean): Boolean
    fun swipeStarted(dx: Float)
    fun swipeMoved(dx: Float)
    /** [velocityX] in px/s from the real touch events; [cancelled] for ACTION_CANCEL. */
    fun swipeEnded(dx: Float, velocityX: Float, cancelled: Boolean)
    /** True while the snapshot shows a page other than the one Readium has underneath. */
    fun touchesBlocked(): Boolean = false
    /** A tap swallowed while [touchesBlocked], in this layer's coordinates. */
    fun blockedTap(x: Float, y: Float) {}
    /** A passed-through gesture ended after enough sideways travel for Readium to page natively. */
    fun nativeSwipeEnded() {}
    /** A finger touched the (unblocked) page: a swipe may follow, so its snapshot can start now. */
    fun touchDown() {}
}

/**
 * PT-1: wraps the reader's [FragmentContainerView] and decides, before Readium does, whether a
 * horizontal swipe becomes an app-owned page turn.
 *
 * Returning true from Readium's `InputListener.onDrag` only stops Chromium's own scroll; Readium's
 * `R2WebView` and `R2ViewPager` still page natively on the Android touch events. So this layer
 * works in `dispatchTouchEvent`, which children cannot switch off with
 * `requestDisallowInterceptTouchEvent`. Taps never cross the slop and pass through untouched,
 * as do vertical drags, long presses (text selection) and multi-finger gestures.
 *
 * Step 5 review: a drag first judged vertical is still watched until its sideways travel reaches
 * the WebView's paging slop (D3). While the snapshot hides a turned page, touches never reach that
 * hidden page, but edge taps still turn and a swipe can still start (D5). An owned swipe follows
 * its own finger and cancels when that finger lifts first (D6).
 */
@SuppressLint("ViewConstructor")
internal class PageTurnTouchLayer(context: Context, val container: FragmentContainerView) : FrameLayout(context) {
    var gestures: PageTurnGestures? = null

    private enum class Mode {
        IDLE,
        /** Passed to the children; not decided yet. */
        UNDECIDED,
        /** Passed to the children as a vertical drag, but may still become a turn (D3). */
        WATCH,
        /** Passed to the children for good. */
        PASS,
        /** Swallowed: the snapshot hides a turned page (D5). A tap may still turn; a swipe may start. */
        BLOCKED,
        OWNED,
        /** Swallowed until the gesture ends (the owning finger lifted first, D6). */
        IGNORE,
    }

    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var activePointer = MotionEvent.INVALID_POINTER_ID
    private var longPressed = false
    private var tapCandidate = false
    private var velocity: VelocityTracker? = null
    private val slop = PageTurnRules.DECISION_SLOP_DP * resources.displayMetrics.density
    /** `R2WebView` starts its own paging drag past this sideways travel (it never checks dy). */
    private val pagingSlop = ViewConfiguration.get(context).scaledPagingTouchSlop.toFloat()
    private val markLongPress = Runnable { longPressed = true; tapCandidate = false }

    init { addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                activePointer = event.getPointerId(0)
                longPressed = false
                tapCandidate = true
                removeCallbacks(markLongPress)
                postDelayed(markLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                velocity?.clear()
                velocity = velocity ?: VelocityTracker.obtain()
                velocity?.addMovement(event)
                if (gestures?.touchesBlocked() == true) { mode = Mode.BLOCKED; return true }
                mode = Mode.UNDECIDED
                gestures?.touchDown()
                return super.dispatchTouchEvent(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> when (mode) {
                Mode.UNDECIDED, Mode.WATCH -> mode = Mode.PASS
                Mode.BLOCKED -> { mode = Mode.IGNORE; removeCallbacks(markLongPress) }
                else -> Unit
            }
        }
        return when (mode) {
            Mode.OWNED -> { owned(event); true }
            Mode.BLOCKED -> { blocked(event); true }
            Mode.IGNORE -> { if (ended(event)) end(); true }
            Mode.UNDECIDED, Mode.WATCH -> {
                velocity?.addMovement(event)
                if (event.actionMasked == MotionEvent.ACTION_MOVE) decide(event)
                if (mode == Mode.OWNED) true else {
                    if (ended(event)) passedEnded(event)
                    super.dispatchTouchEvent(event)
                }
            }
            else -> {
                if (ended(event)) passedEnded(event)
                super.dispatchTouchEvent(event)
            }
        }
    }

    private fun ended(event: MotionEvent) =
        event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL

    private fun decide(event: MotionEvent) {
        val dx = event.x - downX
        val dy = event.y - downY
        when (PageTurnRules.classify(dx, dy, slop)) {
            SwipeDecision.UNDECIDED -> Unit
            SwipeDecision.OTHER -> {
                removeCallbacks(markLongPress)
                // Keep watching while R2WebView cannot page on its own yet: a thumb arc that starts
                // steep and then flattens out is still a page turn (D3).
                mode = if (!longPressed && PageTurnRules.mayStillTakeOver(dx, pagingSlop)) Mode.WATCH else Mode.PASS
            }
            SwipeDecision.HORIZONTAL -> {
                removeCallbacks(markLongPress)
                if (mode == Mode.WATCH && !PageTurnRules.mayStillTakeOver(dx, pagingSlop)) { mode = Mode.PASS; return }
                val owner = gestures
                if (owner == null || !owner.canTakeOverSwipe(dx, longPressed)) { mode = Mode.PASS; return }
                mode = Mode.OWNED
                // Children saw less sideways travel than their paging slop, so a cancel leaves Readium,
                // the pager and Chromium with nothing to finish: no native scroll, fling or page snap.
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                owner.swipeStarted(dx)
            }
        }
    }

    /** The hidden page under the snapshot gets nothing; taps and swipes still work (D5). */
    private fun blocked(event: MotionEvent) {
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> when (PageTurnRules.classify(event.x - downX, event.y - downY, slop)) {
                SwipeDecision.UNDECIDED -> Unit
                SwipeDecision.OTHER -> { tapCandidate = false; removeCallbacks(markLongPress) }
                SwipeDecision.HORIZONTAL -> {
                    tapCandidate = false
                    removeCallbacks(markLongPress)
                    val owner = gestures ?: return
                    val dx = event.x - downX
                    // The children never saw this gesture, so they need no cancel.
                    if (!owner.canTakeOverSwipe(dx, longPressed)) return
                    mode = Mode.OWNED
                    owner.swipeStarted(dx)
                }
            }
            MotionEvent.ACTION_UP -> {
                val tap = tapCandidate && !longPressed
                end()
                if (tap) gestures?.blockedTap(event.x, event.y)
            }
            MotionEvent.ACTION_CANCEL -> end()
        }
    }

    private fun owned(event: MotionEvent) {
        velocity?.addMovement(event)
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.getPointerId(event.actionIndex) == activePointer) {
            // The finger that owns the turn lifted while another stays down. Following the other
            // finger would jump the page, and could commit a turn nobody swiped through: cancel.
            val dx = event.getX(event.actionIndex) - downX
            end()
            mode = Mode.IGNORE
            gestures?.swipeEnded(dx, 0f, cancelled = true)
            return
        }
        val index = event.findPointerIndex(activePointer)
        if (index < 0) return
        val dx = event.getX(index) - downX
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> gestures?.swipeMoved(dx)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = velocity
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.getXVelocity(activePointer) ?: 0f
                end()
                gestures?.swipeEnded(dx, vx, cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL)
            }
        }
    }

    /**
     * A gesture the children handled has ended. If it travelled far enough sideways, Readium is now
     * settling a native turn, and a swipe that interrupts that settle must stay native too (D4).
     */
    private fun passedEnded(event: MotionEvent) {
        val index = event.findPointerIndex(activePointer)
        val travel = if (index >= 0) event.getX(index) - downX else 0f
        end()
        if (event.actionMasked == MotionEvent.ACTION_UP && abs(travel) >= pagingSlop) gestures?.nativeSwipeEnded()
    }

    private fun end() {
        mode = Mode.IDLE
        removeCallbacks(markLongPress)
        velocity?.clear()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // The window going away mid-gesture must never leave a turn waiting for a finger.
        if (mode == Mode.OWNED) gestures?.swipeEnded(0f, 0f, cancelled = true)
        end()
        velocity?.recycle(); velocity = null
    }
}
