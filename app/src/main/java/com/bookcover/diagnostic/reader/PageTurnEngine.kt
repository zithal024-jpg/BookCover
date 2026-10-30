package com.bookcover.diagnostic.reader

import kotlin.math.abs
import kotlin.math.hypot

/*
 * PT-1 page-turn engine: pure Kotlin, no Android types, so every decision is unit tested.
 *
 * How a turn works: the app snapshots the visible page, shows the snapshot above the reader,
 * turns Readium silently (animated = false) underneath it, waits until the new page is drawn,
 * then animates the snapshot away (slide, or a paper curl in step 3). A swipe drives the same
 * snapshot with the finger; letting go decides commit or cancel, and a cancel undoes the silent
 * turn exactly once while the snapshot still covers the page.
 */

internal enum class PageTurnDirection {
    FORWARD, BACKWARD;
    val opposite get() = if (this == FORWARD) BACKWARD else FORWARD
}

/** Where a user turn came from. Every one of them goes through `EpubSession.turnPage`. */
internal enum class PageTurnSource { SWIPE, EDGE_TAP, BUTTON, VOLUME_KEY, KEYBOARD }

internal enum class PageTurnStyle {
    SLIDE, PAPER;
    companion object { fun of(paper: Boolean) = if (paper) PAPER else SLIDE }
}

/** How a user turn is carried out right now. */
internal enum class PageTurnPath {
    /** Snapshot overlay with a slide or paper animation. */
    ENGINE,
    /** "Remove animations" is on: turn at once, nothing moves. */
    INSTANT,
    /** Readium's own animated turn and native swipes, exactly as before PT-1. */
    NATIVE,
}

internal data class PageTurnConditions(
    /** Fixed-layout books stay entirely native until an FXL fixture is checked on the phone. */
    val reflowable: Boolean,
    val typographyUpdating: Boolean,
    val restoringViewport: Boolean,
    /** `ValueAnimator.areAnimatorsEnabled()`; false when Android's "Remove animations" is on. */
    val animationsEnabled: Boolean,
)

internal enum class SwipeDecision { UNDECIDED, HORIZONTAL, OTHER }

/** Thresholds and geometry shared by the touch layer, the controller and the overlay. */
internal object PageTurnRules {
    /** Below Chromium's touch slop (8dp) and the pager/WebView paging slop (16dp). */
    const val DECISION_SLOP_DP = 6f
    const val HORIZONTAL_DOMINANCE = 1.5f
    const val COMMIT_FRACTION = 0.3f
    /** Matches Readium's own fling feel: 400dp/s over at least 25dp. */
    const val FLING_DP_PER_SECOND = 400f
    const val FLING_MIN_DISTANCE_DP = 25f
    const val TURN_MILLIS = 280L
    const val MIN_SETTLE_MILLIS = 120L
    const val RUBBER_BAND_MILLIS = 260L
    /** At the start or end of the book the page gives a little, then springs back. */
    const val RUBBER_BAND_MAX = 0.06f
    const val RUBBER_BAND_RESISTANCE = 0.25f

    fun path(conditions: PageTurnConditions): PageTurnPath = when {
        !conditions.reflowable || conditions.typographyUpdating || conditions.restoringViewport -> PageTurnPath.NATIVE
        !conditions.animationsEnabled -> PageTurnPath.INSTANT
        else -> PageTurnPath.ENGINE
    }

    /**
     * The layer takes a swipe only when the engine can run, the controls are closed (their panels
     * would otherwise appear in the snapshot), and the gesture is not text selection.
     */
    fun canTakeOverSwipe(conditions: PageTurnConditions, controlsVisible: Boolean, selectionActive: Boolean, longPressed: Boolean) =
        path(conditions) != PageTurnPath.NATIVE && !controlsVisible && !selectionActive && !longPressed

    fun classify(dx: Float, dy: Float, slopPx: Float): SwipeDecision = when {
        hypot(dx, dy) < slopPx -> SwipeDecision.UNDECIDED
        abs(dx) > HORIZONTAL_DOMINANCE * abs(dy) -> SwipeDecision.HORIZONTAL
        else -> SwipeDecision.OTHER
    }

    /** Finger moving left turns forward in left-to-right books, backward in right-to-left books. */
    fun swipeDirection(dx: Float, rightToLeft: Boolean): PageTurnDirection? = when {
        dx == 0f -> null
        (dx < 0f) != rightToLeft -> PageTurnDirection.FORWARD
        else -> PageTurnDirection.BACKWARD
    }

    /** Screen direction the outgoing page moves in: -1 left, +1 right. */
    fun screenSign(direction: PageTurnDirection, rightToLeft: Boolean): Int =
        if ((direction == PageTurnDirection.FORWARD) != rightToLeft) -1 else 1

    /** `View.canScrollHorizontally` argument for content that lies further in [direction]. */
    fun scrollDirection(direction: PageTurnDirection, rightToLeft: Boolean): Int = -screenSign(direction, rightToLeft)

    /** Finger travel measured along the turn: positive means further into the turn. */
    fun along(dx: Float, direction: PageTurnDirection, rightToLeft: Boolean): Float = dx * screenSign(direction, rightToLeft)

    fun progress(along: Float, width: Float): Float = if (width <= 0f) 0f else (along / width).coerceIn(0f, 1f)

    fun rubberBand(along: Float, width: Float): Float =
        if (width <= 0f) 0f else (along * RUBBER_BAND_RESISTANCE / width).coerceIn(0f, RUBBER_BAND_MAX)

    /** Commit past 30% of the width, or on a fling in the turn direction; a fling back always cancels. */
    fun shouldCommit(along: Float, velocityAlong: Float, width: Float, density: Float): Boolean {
        if (width <= 0f || along <= 0f) return false
        val fling = FLING_DP_PER_SECOND * density
        if (velocityAlong <= -fling) return false
        if (along >= COMMIT_FRACTION * width) return true
        return velocityAlong >= fling && along >= FLING_MIN_DISTANCE_DP * density
    }

    /**
     * Settle time after the finger lifts: the remaining share of a full turn, shortened to keep up
     * with a fast finger (a decelerating curve starts at twice its average speed), never below 120 ms.
     */
    fun settleMillis(from: Float, to: Float, velocityTowardTarget: Float, width: Float): Long {
        val share = abs(to - from).coerceIn(0f, 1f)
        var millis = TURN_MILLIS * share
        if (velocityTowardTarget > 0f && width > 0f) millis = minOf(millis, 2f * share * width / velocityTowardTarget * 1000f)
        return millis.toLong().coerceIn(MIN_SETTLE_MILLIS, TURN_MILLIS)
    }
}

/** What the controller must do next. Each engine call returns exactly one action. */
internal sealed interface PageTurnAction {
    data object None : PageTurnAction
    /** Snapshot the visible page, then report `captured(success)`. */
    data object Capture : PageTurnAction
    /**
     * Show the snapshot. Once it is on screen, turn silently in [turn] (when not null) and report
     * `overlayShown()`; report `pageReady(token)` when the turned page is drawn.
     */
    data class ShowOverlay(val turn: PageTurnDirection?, val token: Int) : PageTurnAction
    /** Animate the snapshot fully away (programmatic turns). */
    data object Reveal : PageTurnAction
    /** Animate from the current finger position to [target] (1 = turned, 0 = flat). */
    data class Settle(val target: Float) : PageTurnAction
    data object RubberBand : PageTurnAction
    /** Undo the silent turn under the flat snapshot; report `pageReady(token)` when drawn. */
    data class Undo(val direction: PageTurnDirection, val token: Int) : PageTurnAction
    data object RemoveOverlay : PageTurnAction
    /** No overlay: turn through Readium directly (instant path, or a failed snapshot). */
    data class TurnDirectly(val direction: PageTurnDirection, val animated: Boolean) : PageTurnAction
}

/**
 * The page-turn state machine. One turn at a time; the controller finishes or queues any other.
 * Late callbacks from an older turn are ignored through [token].
 */
internal class PageTurnEngine {
    enum class Phase {
        IDLE,
        /** Waiting for the snapshot. */
        CAPTURING,
        /** A finger owns the turn (with or without an overlay). */
        DRAGGING,
        /** Programmatic turn: snapshot shown, waiting for the new page before the reveal. */
        AWAITING_PAGE,
        REVEALING,
        RUBBER_BAND,
        SETTLING_COMMIT,
        SETTLING_CANCEL,
        /** Committed animation finished before the new page was drawn: page colour covers it. */
        HOLDING,
        /** Cancel finished before the silent turn landed; undo as soon as it does. */
        UNDO_WAITING,
        UNDOING,
    }

    var phase = Phase.IDLE; private set
    var direction: PageTurnDirection? = null; private set
    var source: PageTurnSource? = null; private set
    var atBookEdge = false; private set
    /** True while a silent turn has been issued and not undone. */
    var turned = false; private set
    var overlay = false; private set
    /** Whether the page under the overlay is drawn; until then the overlay shows the page colour. */
    var pageReady = true; private set
    /** Silent turns issued, including undos; tests check that a cancel undoes exactly once. */
    var silentTurns = 0; private set
    var undos = 0; private set
    var token = 0; private set
    private var swipe = false
    private var pendingRelease: Boolean? = null
    private var directAnimated = true

    val busy get() = phase != Phase.IDLE
    val isSwipe get() = swipe && busy

    fun isCurrent(token: Int) = busy && token == this.token

    /** Tap, button or key turn on the [PageTurnPath.ENGINE] path. */
    fun request(direction: PageTurnDirection, source: PageTurnSource, atBookEdge: Boolean): PageTurnAction {
        if (busy) return PageTurnAction.None
        start(direction, source, atBookEdge, swipe = false)
        phase = Phase.CAPTURING
        return PageTurnAction.Capture
    }

    /** The touch layer took a horizontal swipe. Without an overlay (instant path) nothing moves until release. */
    fun beginSwipe(direction: PageTurnDirection, atBookEdge: Boolean, withOverlay: Boolean): PageTurnAction {
        if (busy) return PageTurnAction.None
        start(direction, PageTurnSource.SWIPE, atBookEdge, swipe = true)
        if (!withOverlay) {
            directAnimated = false
            phase = Phase.DRAGGING
            return PageTurnAction.None
        }
        phase = Phase.CAPTURING
        return PageTurnAction.Capture
    }

    fun captured(success: Boolean): PageTurnAction {
        if (phase != Phase.CAPTURING) return PageTurnAction.None
        val turn = direction!!
        if (!success) {
            // Reading never breaks: fall back to Readium's own animated turn.
            directAnimated = true
            if (swipe) {
                phase = Phase.DRAGGING
                return pendingRelease?.let { pendingRelease = null; release(it) } ?: PageTurnAction.None
            }
            val edge = atBookEdge
            reset()
            return if (edge) PageTurnAction.None else PageTurnAction.TurnDirectly(turn, animated = true)
        }
        overlay = true
        token++
        phase = when { swipe -> Phase.DRAGGING; atBookEdge -> Phase.RUBBER_BAND; else -> Phase.AWAITING_PAGE }
        if (atBookEdge) return PageTurnAction.ShowOverlay(null, token)
        turned = true
        pageReady = false
        silentTurns++
        return PageTurnAction.ShowOverlay(turn, token)
    }

    /** The snapshot is on screen (and the silent turn, if any, has been issued). */
    fun overlayShown(): PageTurnAction = when (phase) {
        Phase.RUBBER_BAND -> PageTurnAction.RubberBand
        Phase.AWAITING_PAGE -> if (pageReady) { phase = Phase.REVEALING; PageTurnAction.Reveal } else PageTurnAction.None
        Phase.DRAGGING -> pendingRelease?.let { pendingRelease = null; release(it) } ?: PageTurnAction.None
        else -> PageTurnAction.None
    }

    /** The page under the overlay is drawn after the silent turn or undo identified by [token]. */
    fun pageReady(token: Int): PageTurnAction {
        if (!isCurrent(token) || pageReady) return PageTurnAction.None
        pageReady = true
        return when (phase) {
            Phase.AWAITING_PAGE -> { phase = Phase.REVEALING; PageTurnAction.Reveal }
            Phase.HOLDING, Phase.UNDOING -> finish()
            Phase.UNDO_WAITING -> undo()
            else -> PageTurnAction.None
        }
    }

    /** Finger lifted (or the gesture was cancelled, with [commit] false). */
    fun release(commit: Boolean): PageTurnAction {
        if (!swipe) return PageTurnAction.None
        return when (phase) {
            Phase.CAPTURING -> { pendingRelease = commit; PageTurnAction.None }
            Phase.DRAGGING -> when {
                !overlay -> {
                    val turn = direction!!
                    val animated = directAnimated
                    val edge = atBookEdge
                    reset()
                    if (commit && !edge) PageTurnAction.TurnDirectly(turn, animated) else PageTurnAction.None
                }
                atBookEdge || !commit -> { phase = Phase.SETTLING_CANCEL; PageTurnAction.Settle(0f) }
                else -> { phase = Phase.SETTLING_COMMIT; PageTurnAction.Settle(1f) }
            }
            else -> PageTurnAction.None
        }
    }

    fun animationEnded(): PageTurnAction = when (phase) {
        Phase.REVEALING, Phase.RUBBER_BAND -> finish()
        Phase.SETTLING_COMMIT -> if (pageReady) finish() else { phase = Phase.HOLDING; PageTurnAction.None }
        Phase.SETTLING_CANCEL -> when {
            !turned -> finish()
            pageReady -> undo()
            else -> { phase = Phase.UNDO_WAITING; PageTurnAction.None }
        }
        else -> PageTurnAction.None
    }

    /** Fold, rotation or closing the reader: drop the overlay at once; BC-07 continuity keeps the position. */
    fun abort(): PageTurnAction {
        if (!busy) return PageTurnAction.None
        val hadOverlay = overlay
        reset()
        return if (hadOverlay) PageTurnAction.RemoveOverlay else PageTurnAction.None
    }

    private fun undo(): PageTurnAction {
        turned = false
        pageReady = false
        undos++
        silentTurns++
        token++
        phase = Phase.UNDOING
        return PageTurnAction.Undo(direction!!.opposite, token)
    }

    private fun finish(): PageTurnAction {
        val hadOverlay = overlay
        reset()
        return if (hadOverlay) PageTurnAction.RemoveOverlay else PageTurnAction.None
    }

    private fun start(direction: PageTurnDirection, source: PageTurnSource, atBookEdge: Boolean, swipe: Boolean) {
        this.direction = direction
        this.source = source
        this.atBookEdge = atBookEdge
        this.swipe = swipe
        turned = false
        overlay = false
        pageReady = true
        pendingRelease = null
        directAnimated = true
    }

    private fun reset() {
        phase = Phase.IDLE
        direction = null
        source = null
        atBookEdge = false
        swipe = false
        turned = false
        overlay = false
        pageReady = true
        pendingRelease = null
        // Invalidate late readiness callbacks from the finished turn.
        token++
    }
}
