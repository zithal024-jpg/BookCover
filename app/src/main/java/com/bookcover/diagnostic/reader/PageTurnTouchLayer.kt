package com.bookcover.diagnostic.reader

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.fragment.app.FragmentContainerView

/** What the touch layer asks of the page-turn controller. */
internal interface PageTurnGestures {
    /** Whether a horizontal swipe that just crossed the decision slop may be taken over. */
    fun canTakeOverSwipe(dx: Float, longPressed: Boolean): Boolean
    fun swipeStarted(dx: Float)
    fun swipeMoved(dx: Float)
    /** [velocityX] in px/s from the real touch events; [cancelled] for ACTION_CANCEL. */
    fun swipeEnded(dx: Float, velocityX: Float, cancelled: Boolean)
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
 */
@SuppressLint("ViewConstructor")
internal class PageTurnTouchLayer(context: Context, val container: FragmentContainerView) : FrameLayout(context) {
    var gestures: PageTurnGestures? = null

    private enum class Mode { IDLE, UNDECIDED, OWNED, PASS }

    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private var velocity: VelocityTracker? = null
    private val slop = PageTurnRules.DECISION_SLOP_DP * resources.displayMetrics.density
    private val markLongPress = Runnable { longPressed = true }

    init { addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = Mode.UNDECIDED
                downX = event.x; downY = event.y
                longPressed = false
                removeCallbacks(markLongPress)
                postDelayed(markLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                velocity?.clear()
                velocity = velocity ?: VelocityTracker.obtain()
                velocity?.addMovement(event)
                return super.dispatchTouchEvent(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (mode == Mode.UNDECIDED) mode = Mode.PASS
        }
        return when (mode) {
            Mode.OWNED -> { owned(event); true }
            Mode.UNDECIDED -> {
                velocity?.addMovement(event)
                if (event.actionMasked == MotionEvent.ACTION_MOVE) decide(event)
                if (mode == Mode.OWNED) true else {
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) end()
                    super.dispatchTouchEvent(event)
                }
            }
            else -> {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) end()
                super.dispatchTouchEvent(event)
            }
        }
    }

    private fun decide(event: MotionEvent) {
        val dx = event.x - downX
        val dy = event.y - downY
        when (PageTurnRules.classify(dx, dy, slop)) {
            SwipeDecision.UNDECIDED -> Unit
            SwipeDecision.OTHER -> { mode = Mode.PASS; removeCallbacks(markLongPress) }
            SwipeDecision.HORIZONTAL -> {
                removeCallbacks(markLongPress)
                val owner = gestures
                if (owner == null || !owner.canTakeOverSwipe(dx, longPressed)) { mode = Mode.PASS; return }
                mode = Mode.OWNED
                // Children saw only sub-slop movement, so a cancel leaves Readium, the pager and
                // Chromium with nothing to finish: no native scroll, fling or page snap follows.
                val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                owner.swipeStarted(dx)
            }
        }
    }

    private fun owned(event: MotionEvent) {
        velocity?.addMovement(event)
        val dx = event.x - downX
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> gestures?.swipeMoved(dx)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tracker = velocity
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                end()
                gestures?.swipeEnded(dx, vx, cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL)
            }
        }
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
