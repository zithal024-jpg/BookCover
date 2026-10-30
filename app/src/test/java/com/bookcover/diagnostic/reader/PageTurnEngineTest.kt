package com.bookcover.diagnostic.reader

import com.bookcover.diagnostic.reader.PageTurnDirection.BACKWARD
import com.bookcover.diagnostic.reader.PageTurnDirection.FORWARD
import com.bookcover.diagnostic.reader.PageTurnEngine.Phase
import org.junit.Assert.*
import org.junit.Test

class PageTurnEngineTest {
    private val width = 1800f
    private val density = 2.625f
    private val ready = PageTurnConditions(reflowable = true, typographyUpdating = false, restoringViewport = false, animationsEnabled = true)

    @Test fun commitNeedsThirtyPercentOrAFlingInTheTurnDirection() {
        assertFalse(PageTurnRules.shouldCommit(0.29f * width, 0f, width, density))
        assertTrue(PageTurnRules.shouldCommit(0.30f * width, 0f, width, density))
        val fling = PageTurnRules.FLING_DP_PER_SECOND * density
        val minimum = PageTurnRules.FLING_MIN_DISTANCE_DP * density
        assertTrue("A quick short flick turns", PageTurnRules.shouldCommit(minimum + 1f, fling + 1f, width, density))
        assertFalse("A flick shorter than 25dp is a wobble", PageTurnRules.shouldCommit(minimum - 1f, fling * 3, width, density))
        assertFalse("A slow short drag cancels", PageTurnRules.shouldCommit(minimum * 2, fling - 1f, width, density))
        assertFalse("Flicking back cancels even past 30%", PageTurnRules.shouldCommit(0.8f * width, -fling - 1f, width, density))
        assertFalse("Dragging against the turn never commits", PageTurnRules.shouldCommit(-0.5f * width, fling * 2, width, density))
        assertFalse(PageTurnRules.shouldCommit(100f, 0f, 0f, density))
    }

    @Test fun directionsFollowReadingProgression() {
        assertEquals(FORWARD, PageTurnRules.swipeDirection(-40f, rightToLeft = false))
        assertEquals(BACKWARD, PageTurnRules.swipeDirection(40f, rightToLeft = false))
        assertEquals(BACKWARD, PageTurnRules.swipeDirection(-40f, rightToLeft = true))
        assertEquals(FORWARD, PageTurnRules.swipeDirection(40f, rightToLeft = true))
        assertNull(PageTurnRules.swipeDirection(0f, rightToLeft = false))
        // Forward in a left-to-right book: the page leaves to the left and content lies to the right.
        assertEquals(-1, PageTurnRules.screenSign(FORWARD, rightToLeft = false))
        assertEquals(1, PageTurnRules.scrollDirection(FORWARD, rightToLeft = false))
        // Right-to-left books peel forward from the left edge: the page leaves to the right.
        assertEquals(1, PageTurnRules.screenSign(FORWARD, rightToLeft = true))
        assertEquals(-1, PageTurnRules.scrollDirection(FORWARD, rightToLeft = true))
        assertEquals(-1, PageTurnRules.screenSign(BACKWARD, rightToLeft = true))
        // The finger's travel into the turn is positive in both directions.
        assertEquals(300f, PageTurnRules.along(-300f, FORWARD, rightToLeft = false), 0f)
        assertEquals(300f, PageTurnRules.along(300f, FORWARD, rightToLeft = true), 0f)
        assertEquals(300f, PageTurnRules.along(300f, BACKWARD, rightToLeft = false), 0f)
    }

    @Test fun verticalAndShortDragsPassThrough() {
        val slop = PageTurnRules.DECISION_SLOP_DP * density
        assertEquals(SwipeDecision.UNDECIDED, PageTurnRules.classify(slop * 0.6f, slop * 0.6f, slop))
        assertEquals(SwipeDecision.OTHER, PageTurnRules.classify(4f, 60f, slop))
        assertEquals(SwipeDecision.OTHER, PageTurnRules.classify(30f, 25f, slop))
        assertEquals(SwipeDecision.HORIZONTAL, PageTurnRules.classify(-60f, 10f, slop))
        assertTrue(PageTurnRules.canTakeOverSwipe(ready, controlsVisible = false, selectionActive = false, longPressed = false))
        assertFalse("Text selection keeps its handles", PageTurnRules.canTakeOverSwipe(ready, false, selectionActive = true, longPressed = false))
        assertFalse("A long press is selection, not a page turn", PageTurnRules.canTakeOverSwipe(ready, false, false, longPressed = true))
        assertFalse("Open controls keep native swipes", PageTurnRules.canTakeOverSwipe(ready, controlsVisible = true, false, false))
    }

    @Test fun pathsFallBackToNativeWhileTypographyOrViewportIsBusyAndForFixedLayout() {
        assertEquals(PageTurnPath.ENGINE, PageTurnRules.path(ready))
        assertEquals(PageTurnPath.NATIVE, PageTurnRules.path(ready.copy(typographyUpdating = true)))
        assertEquals(PageTurnPath.NATIVE, PageTurnRules.path(ready.copy(restoringViewport = true)))
        assertEquals(PageTurnPath.NATIVE, PageTurnRules.path(ready.copy(reflowable = false)))
        assertEquals(PageTurnPath.NATIVE, PageTurnRules.path(ready.copy(typographyUpdating = true, animationsEnabled = false)))
        assertFalse("No app-owned swipe during reflow", PageTurnRules.canTakeOverSwipe(ready.copy(typographyUpdating = true), false, false, false))
        assertFalse(PageTurnRules.canTakeOverSwipe(ready.copy(reflowable = false), false, false, false))
    }

    @Test fun removeAnimationsTurnsInstantlyWithoutAnOverlay() {
        val instant = ready.copy(animationsEnabled = false)
        assertEquals(PageTurnPath.INSTANT, PageTurnRules.path(instant))
        assertTrue("The swipe is still taken so nothing scrolls", PageTurnRules.canTakeOverSwipe(instant, false, false, false))
        val engine = PageTurnEngine()
        assertEquals(PageTurnAction.None, engine.beginSwipe(FORWARD, atBookEdge = false, withOverlay = false))
        assertEquals(Phase.DRAGGING, engine.phase)
        assertFalse(engine.overlay)
        assertEquals(PageTurnAction.TurnDirectly(FORWARD, animated = false), engine.release(commit = true))
        assertEquals(Phase.IDLE, engine.phase)
        assertEquals(0, engine.silentTurns)
        engine.beginSwipe(BACKWARD, atBookEdge = false, withOverlay = false)
        assertEquals("A short instant swipe does nothing", PageTurnAction.None, engine.release(commit = false))
        assertFalse(engine.busy)
    }

    @Test fun programmaticTurnRevealsOnlyAfterTheNewPageIsDrawn() {
        val engine = PageTurnEngine()
        assertEquals(PageTurnAction.Capture, engine.request(FORWARD, PageTurnSource.EDGE_TAP, atBookEdge = false))
        assertEquals("One turn at a time", PageTurnAction.None, engine.request(BACKWARD, PageTurnSource.BUTTON, false))
        val show = engine.captured(true) as PageTurnAction.ShowOverlay
        assertEquals(FORWARD, show.turn)
        assertFalse("Page colour covers the gap until the page is drawn", engine.pageReady)
        assertEquals(PageTurnAction.None, engine.overlayShown())
        assertEquals(Phase.AWAITING_PAGE, engine.phase)
        assertEquals("A stale callback is ignored", PageTurnAction.None, engine.pageReady(show.token - 1))
        assertEquals(PageTurnAction.Reveal, engine.pageReady(show.token))
        assertEquals(PageTurnAction.RemoveOverlay, engine.animationEnded())
        assertFalse(engine.busy)
        assertEquals(1, engine.silentTurns)
        assertEquals(0, engine.undos)
    }

    @Test fun failedSnapshotFallsBackToReadiumsAnimatedTurn() {
        val engine = PageTurnEngine()
        engine.request(BACKWARD, PageTurnSource.VOLUME_KEY, atBookEdge = false)
        assertEquals(PageTurnAction.TurnDirectly(BACKWARD, animated = true), engine.captured(false))
        assertFalse(engine.busy)
        engine.beginSwipe(FORWARD, atBookEdge = false, withOverlay = true)
        assertEquals(PageTurnAction.None, engine.captured(false))
        assertEquals(PageTurnAction.TurnDirectly(FORWARD, animated = true), engine.release(commit = true))
        assertEquals(0, engine.silentTurns)
    }

    @Test fun committedSwipeHoldsPageColourUntilTheTurnLands() {
        val engine = PageTurnEngine()
        assertEquals(PageTurnAction.Capture, engine.beginSwipe(FORWARD, atBookEdge = false, withOverlay = true))
        val show = engine.captured(true) as PageTurnAction.ShowOverlay
        assertEquals(PageTurnAction.None, engine.overlayShown())
        assertEquals(PageTurnAction.Settle(1f), engine.release(commit = true))
        assertEquals("Animation done before the page is drawn", PageTurnAction.None, engine.animationEnded())
        assertEquals(Phase.HOLDING, engine.phase)
        assertEquals(PageTurnAction.RemoveOverlay, engine.pageReady(show.token))
        assertEquals(0, engine.undos)
    }

    @Test fun releaseDuringSnapshotIsAppliedOnceTheOverlayIsUp() {
        val engine = PageTurnEngine()
        engine.beginSwipe(FORWARD, atBookEdge = false, withOverlay = true)
        assertEquals(PageTurnAction.None, engine.release(commit = true))
        engine.captured(true)
        assertEquals(PageTurnAction.Settle(1f), engine.overlayShown())
    }

    @Test fun cancelAfterSilentTurnUndoesExactlyOnce() {
        val engine = PageTurnEngine()
        engine.beginSwipe(FORWARD, atBookEdge = false, withOverlay = true)
        val show = engine.captured(true) as PageTurnAction.ShowOverlay
        engine.overlayShown()
        engine.pageReady(show.token)
        assertEquals(PageTurnAction.Settle(0f), engine.release(commit = false))
        val undo = engine.animationEnded() as PageTurnAction.Undo
        assertEquals(BACKWARD, undo.direction)
        assertFalse(engine.turned)
        assertEquals("Repeated callbacks never undo twice", PageTurnAction.None, engine.animationEnded())
        assertEquals(PageTurnAction.None, engine.release(commit = false))
        assertEquals(PageTurnAction.None, engine.pageReady(show.token))
        assertEquals(PageTurnAction.RemoveOverlay, engine.pageReady(undo.token))
        assertEquals(PageTurnAction.None, engine.pageReady(undo.token))
        assertEquals(1, engine.undos)
        assertEquals(2, engine.silentTurns)
        assertFalse(engine.busy)
    }

    @Test fun cancelBeforeTheSilentTurnLandsWaitsThenUndoesOnce() {
        val engine = PageTurnEngine()
        engine.beginSwipe(BACKWARD, atBookEdge = false, withOverlay = true)
        val show = engine.captured(true) as PageTurnAction.ShowOverlay
        engine.overlayShown()
        engine.release(commit = false)
        assertEquals("Undoing before the turn lands could undo the wrong page", PageTurnAction.None, engine.animationEnded())
        assertEquals(Phase.UNDO_WAITING, engine.phase)
        val undo = engine.pageReady(show.token) as PageTurnAction.Undo
        assertEquals(FORWARD, undo.direction)
        assertEquals(PageTurnAction.RemoveOverlay, engine.pageReady(undo.token))
        assertEquals(1, engine.undos)
    }

    @Test fun endOfBookRubberBandsWithoutTurning() {
        val engine = PageTurnEngine()
        engine.request(FORWARD, PageTurnSource.KEYBOARD, atBookEdge = true)
        val show = engine.captured(true) as PageTurnAction.ShowOverlay
        assertNull("No silent turn past the end of the book", show.turn)
        assertEquals(PageTurnAction.RubberBand, engine.overlayShown())
        assertEquals(PageTurnAction.RemoveOverlay, engine.animationEnded())
        assertEquals(0, engine.silentTurns)

        engine.beginSwipe(BACKWARD, atBookEdge = true, withOverlay = true)
        assertNull((engine.captured(true) as PageTurnAction.ShowOverlay).turn)
        assertEquals("Even a full swipe springs back", PageTurnAction.Settle(0f), engine.release(commit = true))
        assertEquals(PageTurnAction.RemoveOverlay, engine.animationEnded())
        assertEquals(0, engine.silentTurns)
        assertEquals(0, engine.undos)
        assertEquals(PageTurnRules.RUBBER_BAND_MAX, PageTurnRules.rubberBand(width, width), 0f)
        assertEquals(0f, PageTurnRules.rubberBand(-width, width), 0f)
        assertEquals(PageTurnAction.Capture, engine.request(FORWARD, PageTurnSource.EDGE_TAP, atBookEdge = true))
        assertEquals("A failed snapshot at the end of the book does nothing", PageTurnAction.None, engine.captured(false))
    }

    @Test fun abortDropsTheOverlayAndIgnoresLateCallbacks() {
        val engine = PageTurnEngine()
        engine.beginSwipe(FORWARD, atBookEdge = false, withOverlay = true)
        val show = engine.captured(true) as PageTurnAction.ShowOverlay
        assertEquals(PageTurnAction.RemoveOverlay, engine.abort())
        assertFalse(engine.busy)
        assertFalse(engine.isCurrent(show.token))
        assertEquals(PageTurnAction.None, engine.pageReady(show.token))
        assertEquals(PageTurnAction.None, engine.animationEnded())
        assertEquals(PageTurnAction.None, engine.abort())
        engine.request(FORWARD, PageTurnSource.BUTTON, atBookEdge = false)
        assertEquals("Nothing on screen yet", PageTurnAction.None, engine.abort())
    }

    @Test fun progressAndSettleTimingStayBounded() {
        assertEquals(0f, PageTurnRules.progress(-50f, width), 0f)
        assertEquals(0.5f, PageTurnRules.progress(width / 2, width), 0f)
        assertEquals(1f, PageTurnRules.progress(width * 2, width), 0f)
        assertEquals(PageTurnRules.TURN_MILLIS, PageTurnRules.settleMillis(0f, 1f, 0f, width))
        assertEquals(PageTurnRules.MIN_SETTLE_MILLIS, PageTurnRules.settleMillis(0.95f, 1f, 0f, width))
        val slow = PageTurnRules.settleMillis(0.4f, 1f, 0f, width)
        val fast = PageTurnRules.settleMillis(0.4f, 1f, 30_000f, width)
        assertTrue("A fast finger finishes sooner", fast < slow)
        assertTrue(fast >= PageTurnRules.MIN_SETTLE_MILLIS)
        val paper = PageTurnRules.turnMillis(PageTurnStyle.PAPER)
        assertEquals(PageTurnRules.TURN_MILLIS, PageTurnRules.turnMillis(PageTurnStyle.SLIDE))
        assertEquals(paper, PageTurnRules.settleMillis(0f, 1f, 0f, 2 * width, paper))
        assertTrue(PageTurnRules.settleMillis(0.4f, 1f, 30_000f, 2 * width, paper) in PageTurnRules.MIN_SETTLE_MILLIS..paper)
    }
}
