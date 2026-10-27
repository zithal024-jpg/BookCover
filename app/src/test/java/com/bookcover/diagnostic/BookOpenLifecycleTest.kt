package com.bookcover.diagnostic

import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BookOpenLifecycleTest {
    private class FakePublication {
        var closeCount = 0
        val closed get() = closeCount > 0
        fun close() { closeCount++ }
    }

    private class FakeLifecycle {
        val observers = mutableSetOf<Any>()
    }

    private class FakeSession(
        val publication: FakePublication,
        private val lifecycle: FakeLifecycle,
    ) {
        private val observer = Any()
        var closeCount = 0

        init {
            lifecycle.observers += observer
        }

        fun close() {
            if (closeCount++ > 0) return
            lifecycle.observers -= observer
            publication.close()
        }
    }

    private class Harness {
        val lifecycle = FakeLifecycle()
        var createdPublication: FakePublication? = null
        var createdSession: FakeSession? = null
        var committedSession: FakeSession? = null
        var committedArtwork: String? = "previous-cover"

        fun openPublication(): FakePublication = FakePublication().also { createdPublication = it }
        fun createSession(publication: FakePublication): FakeSession =
            FakeSession(publication, lifecycle).also { createdSession = it }
        fun commit(session: FakeSession, artwork: String) {
            committedArtwork = artwork
            committedSession = session
        }
    }

    @Test fun cancellationAsOpenPublicationReturnsAcrossDispatcherClosesPublicationBeforeSessionCreation() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler, name = "io")
        val harness = Harness()
        var openFinished = false
        var cancelOnReturnToMain = false
        var jobToCancel: kotlinx.coroutines.Job? = null
        val delegateMain = currentCoroutineContext()[ContinuationInterceptor] as CoroutineDispatcher
        val interceptingMain = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (openFinished && cancelOnReturnToMain) {
                    cancelOnReturnToMain = false
                    jobToCancel?.cancel(CancellationException("cancelled while returning from IO to Main"))
                }
                delegateMain.dispatch(context, block)
            }
        }

        val job = launch(context = interceptingMain, start = CoroutineStart.UNDISPATCHED) {
            openBookWithCleanup(
                ioContext = ioDispatcher,
                openPublication = {
                    harness.openPublication().also {
                        openFinished = true
                        cancelOnReturnToMain = true
                    }
                },
                closePublication = FakePublication::close,
                loadArtwork = { "new-cover" },
                createSession = harness::createSession,
                closeSession = FakeSession::close,
                markOpened = {},
                commit = harness::commit,
            )
        }
        jobToCancel = job

        testScheduler.advanceUntilIdle()
        job.join()

        val publication = checkNotNull(harness.createdPublication)
        assertTrue(openFinished)
        assertTrue(job.isCancelled)
        assertEquals(1, publication.closeCount)
        assertNull(harness.createdSession)
        assertTrue(harness.lifecycle.observers.isEmpty())
        assertNull(harness.committedSession)
        assertEquals("previous-cover", harness.committedArtwork)
    }

    @Test fun cancellationImmediatelyAfterOpenPublicationCompletesSynchronouslyClosesPublication() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler, name = "io")
        val harness = Harness()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            val caller = currentCoroutineContext()
            openBookWithCleanup(
                ioContext = ioDispatcher,
                openPublication = {
                    harness.openPublication().also {
                        caller.cancel(CancellationException("cancelled as engine.open returns"))
                    }
                },
                closePublication = FakePublication::close,
                loadArtwork = { "new-cover" },
                createSession = harness::createSession,
                closeSession = FakeSession::close,
                markOpened = {},
                commit = harness::commit,
            )
        }

        testScheduler.advanceUntilIdle()
        job.join()

        val publication = checkNotNull(harness.createdPublication)
        assertTrue(job.isCancelled)
        assertEquals(1, publication.closeCount)
        assertNull(harness.createdSession)
        assertTrue(harness.lifecycle.observers.isEmpty())
        assertNull(harness.committedSession)
        assertEquals("previous-cover", harness.committedArtwork)
    }

    @Test fun failureWhileLoadingArtworkClosesPublicationAndDoesNotCreateSession() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler, name = "io")
        val harness = Harness()
        val expected = IOException("corrupt cover bitmap")

        try {
            openBookWithCleanup(
                ioContext = ioDispatcher,
                openPublication = { harness.openPublication() },
                closePublication = FakePublication::close,
                loadArtwork = { throw expected },
                createSession = harness::createSession,
                closeSession = FakeSession::close,
                markOpened = {},
                commit = harness::commit,
            )
            fail("Expected failure to propagate")
        } catch (actual: IOException) {
            assertEquals(expected.message, actual.message)
        }

        val publication = checkNotNull(harness.createdPublication)
        assertEquals(1, publication.closeCount)
        assertNull(harness.createdSession)
        assertTrue(harness.lifecycle.observers.isEmpty())
        assertNull(harness.committedSession)
        assertEquals("previous-cover", harness.committedArtwork)
    }

    @Test fun failureDuringMarkOpenedClosesSessionObserverAndPublicationOnce() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler, name = "io")
        val harness = Harness()
        val expected = IOException("index write failed in markOpened")

        try {
            openBookWithCleanup(
                ioContext = ioDispatcher,
                openPublication = { harness.openPublication() },
                closePublication = FakePublication::close,
                loadArtwork = { "new-cover" },
                createSession = harness::createSession,
                closeSession = FakeSession::close,
                markOpened = {
                    assertEquals(1, harness.lifecycle.observers.size)
                    throw expected
                },
                commit = harness::commit,
            )
            fail("Expected markOpened failure to propagate")
        } catch (actual: IOException) {
            assertSame(expected, actual)
        }

        val session = checkNotNull(harness.createdSession)
        val publication = checkNotNull(harness.createdPublication)
        assertEquals(1, session.closeCount)
        assertEquals(1, publication.closeCount)
        assertTrue("Lifecycle observer registered by EpubSession must be removed", harness.lifecycle.observers.isEmpty())
        assertNull(harness.committedSession)
        assertEquals("previous-cover", harness.committedArtwork)
    }

    @Test fun cancellationDuringMarkOpenedClosesSessionObserverAndPublication() = runTest {
        for (cancelOnReturn in listOf(false, true)) {
            val ioDispatcher = StandardTestDispatcher(testScheduler, name = "io")
            val harness = Harness()
            val insideMarkOpened = CompletableDeferred<Unit>()

            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                val caller = currentCoroutineContext()
                openBookWithCleanup(
                    ioContext = ioDispatcher,
                    openPublication = { harness.openPublication() },
                    closePublication = FakePublication::close,
                    loadArtwork = { "new-cover" },
                    createSession = harness::createSession,
                    closeSession = FakeSession::close,
                    markOpened = {
                        assertEquals(1, harness.lifecycle.observers.size)
                        insideMarkOpened.complete(Unit)
                        if (cancelOnReturn) {
                            caller.cancel(CancellationException("cancelled as markOpened returns"))
                        } else {
                            awaitCancellation()
                        }
                    },
                    commit = harness::commit,
                )
            }

            testScheduler.advanceUntilIdle()
            assertTrue(insideMarkOpened.isCompleted)
            if (!cancelOnReturn) {
                job.cancel(CancellationException("cancelled while markOpened suspended"))
                testScheduler.advanceUntilIdle()
            }
            job.join()

            val session = checkNotNull(harness.createdSession)
            val publication = checkNotNull(harness.createdPublication)
            assertTrue(job.isCancelled)
            assertEquals(1, session.closeCount)
            assertEquals(1, publication.closeCount)
            assertTrue(harness.lifecycle.observers.isEmpty())
            assertNull(harness.committedSession)
            assertEquals("previous-cover", harness.committedArtwork)
        }
    }

    @Test fun successfulOpenTransfersOwnershipWithoutClosingSessionOrPublication() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler, name = "io")
        val harness = Harness()
        var markedOpened = false

        val returned = openBookWithCleanup(
            ioContext = ioDispatcher,
            openPublication = { harness.openPublication() },
            closePublication = FakePublication::close,
            loadArtwork = { "new-cover" },
            createSession = harness::createSession,
            closeSession = FakeSession::close,
            markOpened = { markedOpened = true },
            commit = harness::commit,
        )

        val session = checkNotNull(harness.createdSession)
        val publication = checkNotNull(harness.createdPublication)
        assertSame(session, returned)
        assertSame(session, harness.committedSession)
        assertTrue(markedOpened)
        assertEquals("new-cover", harness.committedArtwork)
        assertEquals(0, session.closeCount)
        assertEquals(0, publication.closeCount)
        assertEquals(1, harness.lifecycle.observers.size)
    }

    @Test fun failureInsideCommitAfterTransferDoesNotCloseTransferredSession() = runTest {
        val ioDispatcher = StandardTestDispatcher(testScheduler, name = "io")
        val harness = Harness()
        val expected = IllegalStateException("post-transfer cover start error")

        try {
            openBookWithCleanup(
                ioContext = ioDispatcher,
                openPublication = { harness.openPublication() },
                closePublication = FakePublication::close,
                loadArtwork = { "new-cover" },
                createSession = harness::createSession,
                closeSession = FakeSession::close,
                markOpened = {},
                commit = { session, artwork ->
                    harness.commit(session, artwork)
                    throw expected
                },
            )
            fail("Expected post-transfer failure to propagate")
        } catch (actual: IllegalStateException) {
            assertSame(expected, actual)
        }

        val session = checkNotNull(harness.createdSession)
        val publication = checkNotNull(harness.createdPublication)
        assertSame(session, harness.committedSession)
        assertEquals(0, session.closeCount)
        assertEquals(0, publication.closeCount)
        assertEquals(1, harness.lifecycle.observers.size)
    }
}
