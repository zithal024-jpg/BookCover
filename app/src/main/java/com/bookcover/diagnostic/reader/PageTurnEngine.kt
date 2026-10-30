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
    /**
     * A drag is a page turn when it is more sideways than up or down. Readium's `R2WebView` pages
     * on sideways travel alone (it never checks dy), so the earlier 1.5 ratio left angled thumb
     * swipes turning the old native way (step 5 review, D3). Vertical-dominant drags pass through.
     */
    const val HORIZONTAL_DOMINANCE = 1f
    const val COMMIT_FRACTION = 0.3f
    /** Matches Readium's own fling feel: 400dp/s over at least 25dp. */
    const val FLING_DP_PER_SECOND = 400f
    const val FLING_MIN_DISTANCE_DP = 25f
    const val TURN_MILLIS = 280L
    /** The curl travels about twice as far as the slide; a little longer keeps it unhurried. */
    const val PAPER_TURN_MILLIS = 340L
    const val MIN_SETTLE_MILLIS = 120L
    const val RUBBER_BAND_MILLIS = 260L
    /** At the start or end of the book the page gives a little, then springs back. */
    const val RUBBER_BAND_MAX = 0.06f
    const val RUBBER_BAND_RESISTANCE = 0.25f
    /** Turns asked for while one runs (quick key presses) wait in order; more than this are dropped. */
    const val MAX_QUEUED_TURNS = 3
    /**
     * After a native Readium turn (its animated turn, or a passed-through swipe) the settle scroll
     * runs about this long. A touch-down stops that scroll half-way and only Readium's own ACTION_UP
     * snaps it back, so swipes in this window stay native (step 5 review, D4).
     */
    const val NATIVE_SETTLE_MILLIS = 650L
    /** Fade of the page-colour cover when a committed swipe outran its page (step 5 review, D10). */
    const val COVER_FADE_MILLIS = 150L
    /** A turned page counts as landed when the visible WebView changed or scrolled this share of a page. */
    const val LANDED_SCROLL_SHARE = 0.4f

    fun path(conditions: PageTurnConditions): PageTurnPath = when {
        !conditions.reflowable || conditions.typographyUpdating || conditions.restoringViewport -> PageTurnPath.NATIVE
        !conditions.animationsEnabled -> PageTurnPath.INSTANT
        else -> PageTurnPath.ENGINE
    }

    /**
     * The layer takes a swipe only when the engine can run, the controls are closed (their panels
     * would otherwise appear in the snapshot), and the gesture is not text selection.
     */
    fun canTakeOverSwipe(
        conditions: PageTurnConditions, controlsVisible: Boolean, selectionActive: Boolean, longPressed: Boolean,
        nativeSettling: Boolean = false,
    ) = path(conditions) != PageTurnPath.NATIVE && !controlsVisible && !selectionActive && !longPressed && !nativeSettling

    /**
     * A drag first judged vertical may still become a turn while its sideways travel is below the
     * WebView's paging slop; past it, Readium may already be paging natively.
     */
    fun mayStillTakeOver(dx: Float, pagingSlopPx: Float): Boolean = abs(dx) < pagingSlopPx

    /** The page on screen changed after a silent turn: another WebView, or a scroll of most of a page. */
    fun pageChanged(differentView: Boolean, scrollDelta: Int, width: Int): Boolean =
        differentView || (width > 0 && abs(scrollDelta) >= width * LANDED_SCROLL_SHARE)

    /**
     * Whether a silent turn really moved the reader. Readium returns true from `goForward/goBackward`
     * whenever a WebView exists, then quietly does nothing at the start or end of the book, so
     * [accepted] alone is not enough (step 5 review, D1). When a page could be watched only a
     * visible change counts: the debounced locator may merely be catching up with an earlier turn.
     */
    fun turnLanded(accepted: Boolean, watched: Boolean, pageChanged: Boolean, locatorChanged: Boolean): Boolean =
        accepted && if (watched) pageChanged else locatorChanged

    /** [index] is the reading-order item on screen; -1 (unknown) is never an edge. */
    fun atBookEdge(index: Int, count: Int, direction: PageTurnDirection): Boolean = when {
        index < 0 || index >= count -> false
        direction == PageTurnDirection.FORWARD -> index == count - 1
        else -> index == 0
    }

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
    fun settleMillis(from: Float, to: Float, velocityTowardTarget: Float, width: Float, fullMillis: Long = TURN_MILLIS): Long {
        val share = abs(to - from).coerceIn(0f, 1f)
        var millis = fullMillis * share
        if (velocityTowardTarget > 0f && width > 0f) millis = minOf(millis, 2f * share * width / velocityTowardTarget * 1000f)
        return millis.toLong().coerceIn(MIN_SETTLE_MILLIS, fullMillis)
    }

    /** Full-turn duration: the slide, or the paper curl, which travels about twice as far. */
    fun turnMillis(style: PageTurnStyle): Long = if (style == PageTurnStyle.PAPER) PAPER_TURN_MILLIS else TURN_MILLIS
}

/** What the controller must do next. Each engine call returns exactly one action. */
internal sealed interface PageTurnAction {
    data object None : PageTurnAction
    /** Snapshot the visible page, then report `captured(success)`. */
    data object Capture : PageTurnAction
    /**
     * Show the snapshot. Once it is on screen, ask `issueTurn(token)` (when [turn] is not null) and
     * turn silently in the direction it returns, then report `overlayShown()`; report
     * `pageReady(token, moved)` when the turned page is drawn.
     */
    data class ShowOverlay(val turn: PageTurnDirection?, val token: Int) : PageTurnAction
    /** Animate the snapshot fully away (programmatic turns). */
    data object Reveal : PageTurnAction
    /**
     * Animate from the current finger position to [target] (1 = turned, 0 = flat). [awaitingPage]:
     * the turned page is not drawn yet, so a fast flick must not shorten the settle and outrun it.
     */
    data class Settle(val target: Float, val awaitingPage: Boolean = false) : PageTurnAction
    data object RubberBand : PageTurnAction
    /** A committed swipe finished before its page was drawn; now it is: fade the page-colour cover. */
    data object FadeCover : PageTurnAction
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
        /** The held page is drawn: the page-colour cover fades away instead of the text popping in. */
        FADING,
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
    /** Silent turns Readium refused; they are never undone. */
    var refusedTurns = 0; private set
    var token = 0; private set
    private var swipe = false
    private var pendingRelease: Boolean? = null
    private var directAnimated = true

    val busy get() = phase != Phase.IDLE
    val isSwipe get() = swipe && busy

    /** A swipe the user has not committed: still under the finger, cancelling, or being undone. */
    val swipeUncommitted get() = isSwipe && phase != Phase.SETTLING_COMMIT && phase != Phase.HOLDING && phase != Phase.FADING

    /**
     * Readium is one page away from where the user left it: an uncommitted swipe's silent turn moved
     * the page and its undo has not landed. Closing or folding now must not keep that page (D8).
     */
    val uncommittedTurnOutstanding get() = swipeUncommitted && (turned || phase == Phase.UNDOING)

    /** The uncommitted silent turn still has to be reversed (its undo has not been issued). */
    val undoOwed get() = swipeUncommitted && turned

    /** The overlay shows a page other than the one Readium has underneath: touches must not reach it (D5). */
    val coversOtherPage get() = overlay && (turned || !pageReady)

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
        if (swipe && pendingRelease == false) {
            // Cancelled, or let go without committing, while the snapshot was taken: nothing has
            // moved on screen yet, so Readium is never turned (step 5 review, D7).
            reset()
            return PageTurnAction.None
        }
        overlay = true
        token++
        phase = when { swipe -> Phase.DRAGGING; atBookEdge -> Phase.RUBBER_BAND; else -> Phase.AWAITING_PAGE }
        if (atBookEdge) return PageTurnAction.ShowOverlay(null, token)
        pageReady = false
        return PageTurnAction.ShowOverlay(turn, token)
    }

    /**
     * The overlay from [PageTurnAction.ShowOverlay] is on screen: returns the direction to turn
     * Readium silently now, or null when the turn is stale or no longer wanted (a swipe cancelled
     * in the frames before it, D7). Then nothing needs undoing.
     */
    fun issueTurn(token: Int): PageTurnDirection? {
        if (!isCurrent(token) || turned || !overlay || atBookEdge) return null
        if (phase == Phase.SETTLING_CANCEL || phase == Phase.UNDO_WAITING) {
            pageReady = true
            return null
        }
        turned = true
        silentTurns++
        return direction
    }

    /** The snapshot is on screen (and the silent turn, if any, has been issued). */
    fun overlayShown(): PageTurnAction = when (phase) {
        Phase.RUBBER_BAND -> PageTurnAction.RubberBand
        Phase.AWAITING_PAGE -> if (pageReady) { phase = Phase.REVEALING; PageTurnAction.Reveal } else PageTurnAction.None
        Phase.DRAGGING -> pendingRelease?.let { pendingRelease = null; release(it) } ?: PageTurnAction.None
        else -> PageTurnAction.None
    }

    /**
     * The page under the overlay is drawn after the silent turn or undo identified by [token].
     * [moved] is false when Readium refused the silent turn (`goForward/goBackward` returned false
     * or threw): the page never left, so a later cancel must not "undo" it onto the wrong page.
     */
    fun pageReady(token: Int, moved: Boolean = true): PageTurnAction {
        if (!isCurrent(token) || pageReady) return PageTurnAction.None
        pageReady = true
        if (!moved && phase != Phase.UNDOING && turned) { turned = false; refusedTurns++ }
        return when (phase) {
            Phase.AWAITING_PAGE -> { phase = Phase.REVEALING; PageTurnAction.Reveal }
            Phase.HOLDING -> { phase = Phase.FADING; PageTurnAction.FadeCover }
            Phase.UNDOING -> finish()
            Phase.UNDO_WAITING -> if (turned) undo() else finish()
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
                else -> { phase = Phase.SETTLING_COMMIT; PageTurnAction.Settle(1f, awaitingPage = !pageReady) }
            }
            else -> PageTurnAction.None
        }
    }

    fun animationEnded(): PageTurnAction = when (phase) {
        Phase.REVEALING, Phase.RUBBER_BAND, Phase.FADING -> finish()
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
