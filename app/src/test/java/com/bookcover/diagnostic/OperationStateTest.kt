package com.bookcover.diagnostic

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OperationStateTest {
    @Test fun overlappingOperationsStayBusyWhenTheFirstFinishesFirst() = runTest {
        val state = OperationState()
        val firstDone = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { state.run { firstDone.await(); "first" } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { state.run { secondDone.await(); "second" } }
        assertTrue(state.busy)

        firstDone.complete(Unit)
        assertEquals("first", first.await())
        assertTrue("The second operation still blocks conflicting actions", state.busy)
        secondDone.complete(Unit)
        assertEquals("second", second.await())
        assertFalse(state.busy)
    }

    @Test fun overlappingOperationsStayBusyWhenTheSecondFinishesFirst() = runTest {
        val state = OperationState()
        val firstDone = CompletableDeferred<Unit>()
        val secondDone = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) { state.run { firstDone.await() } }
        val second = async(start = CoroutineStart.UNDISPATCHED) { state.run { secondDone.await() } }

        secondDone.complete(Unit)
        second.await()
        assertTrue(state.busy)
        firstDone.complete(Unit)
        first.await()
        assertFalse(state.busy)
    }

    @Test fun aFailedOperationDoesNotReleaseAnotherOperationsBusyState() = runTest {
        val state = OperationState()
        val done = CompletableDeferred<Unit>()
        val ongoing = launch(start = CoroutineStart.UNDISPATCHED) { state.run { done.await() } }
        val expected = IOException("fixture failure")
        try {
            state.run { throw expected }
            fail("The failure must propagate")
        } catch (actual: IOException) {
            assertSame(expected, actual)
        }
        assertTrue(state.busy)
        done.complete(Unit)
        ongoing.join()
        assertFalse(state.busy)
        assertEquals("A subsequent operation still runs normally", 7, state.run { 7 })
        assertFalse(state.busy)
    }

    @Test fun cancellingOneOperationKeepsBusyUntilTheOtherFinishes() = runTest {
        val state = OperationState()
        val done = CompletableDeferred<Unit>()
        val ongoing = launch(start = CoroutineStart.UNDISPATCHED) { state.run { done.await() } }
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { state.run { awaitCancellation() } }
        cancelled.cancel()
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        assertTrue(state.busy)
        done.complete(Unit)
        ongoing.join()
        assertFalse(state.busy)
    }

    @Test fun startupHintCannotReplaceAnOperationsResultOrError() {
        val state = OperationState()
        assertEquals("Preparing your library…", state.message)
        state.publishInitialMessage("Ready to read.")
        assertEquals("Ready to read.", state.message)
        state.message = "Backup saved."
        state.publishInitialMessage("Ready to read.")
        assertEquals("Backup saved.", state.message)
        state.message = "The backup could not be restored."
        state.publishInitialMessage("Ready to read.")
        assertEquals("The backup could not be restored.", state.message)
    }
}
