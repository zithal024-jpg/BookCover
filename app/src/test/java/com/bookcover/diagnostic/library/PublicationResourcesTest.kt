package com.bookcover.diagnostic.library

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.data.asInputStream
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.resource.Resource

class PublicationResourcesTest {
    @Test fun closeRejectsHandedOutResourcesWithoutTouchingTheClosedContainer() = runTest {
        val raw = TestResource()
        val container = TestContainer(raw)
        val guarded = PublicationResources(container)
        val resource = guarded.resource(raw)

        assertEquals(container.entries, guarded.entries)
        assertSame(container.sourceUrl, guarded.sourceUrl)
        assertArrayEquals(raw.bytes, resource.read().getOrElse { error(it.message) })
        val callsBeforeClose = raw.calls
        guarded.close()
        guarded.close()

        assertEquals(1, container.closeCalls)
        assertAccessFailure(resource.properties())
        assertAccessFailure(resource.length())
        assertAccessFailure(resource.read())
        assertEquals(callsBeforeClose, raw.calls)
    }

    @Test fun aLazyInputStreamObtainedBeforeCloseReportsAnIoFailureInsteadOfReadingTheArchive() {
        val raw = TestResource()
        val container = TestContainer(raw)
        val guarded = PublicationResources(container)
        val stream = guarded.resource(raw).asInputStream()
        guarded.close()

        try {
            stream.read()
            fail("A stream retained after publication close must fail")
        } catch (_: IOException) {
            assertEquals(0, raw.calls)
            assertEquals(1, container.closeCalls)
        } finally {
            stream.close()
        }
        assertEquals(1, raw.closeCalls)
    }

    @Test fun everyActiveOperationFinishesBeforeRequestedResourceAndContainerClose() = runTest {
        for (operation in Operation.entries) {
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val raw = TestResource(beforeCall = {
                events += "started"
                entered.complete(Unit)
                finish.await()
                events += "finished"
            }, afterClose = { events += "resource closed" })
            val container = TestContainer(raw, afterClose = { events += "container closed" })
            val guarded = PublicationResources(container)
            val resource = guarded.resource(raw)
            val result = async {
                when (operation) {
                    Operation.PROPERTIES -> resource.properties()
                    Operation.LENGTH -> resource.length()
                    Operation.READ -> resource.read()
                }
            }
            entered.await()
            resource.close()
            resource.close()
            guarded.close()
            guarded.close()

            assertAccessFailure(resource.read())
            assertEquals(0, raw.closeCalls)
            assertEquals(0, container.closeCalls)
            finish.complete(Unit)
            assertTrue(result.await() is Try.Success)
            assertEquals(listOf("started", "finished", "resource closed", "container closed"), events)
            assertEquals(1, raw.calls)
            assertEquals(1, raw.closeCalls)
            assertEquals(1, container.closeCalls)
        }
    }

    @Test fun containerCloseWaitsForAllResourcesRatherThanOnlyTheFirstCompletedRead() = runTest {
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val finishSecond = CompletableDeferred<Unit>()
        var started = 0
        val raw = TestResource(beforeCall = {
            if (++started == 1) {
                firstEntered.complete(Unit)
                finishFirst.await()
            } else {
                secondEntered.complete(Unit)
                finishSecond.await()
            }
        })
        val container = TestContainer(raw)
        val guarded = PublicationResources(container)
        val firstResource = guarded.resource(raw)
        val secondResource = guarded.resource(raw)
        val first = async { firstResource.read() }
        firstEntered.await()
        val second = async { secondResource.read() }
        secondEntered.await()
        guarded.close()

        finishFirst.complete(Unit)
        assertTrue(first.await() is Try.Success)
        assertEquals(0, container.closeCalls)
        finishSecond.complete(Unit)
        assertTrue(second.await() is Try.Success)
        assertEquals(1, container.closeCalls)
    }

    @Test fun cancellationReleasesAnActiveReadAndClosesTheArchive() = runTest {
        val entered = CompletableDeferred<Unit>()
        val neverFinish = CompletableDeferred<Unit>()
        val raw = TestResource(beforeCall = { entered.complete(Unit); neverFinish.await() })
        val container = TestContainer(raw)
        val guarded = PublicationResources(container)
        val resource = guarded.resource(raw)
        val read = launch { resource.read() }
        entered.await()
        resource.close()
        guarded.close()
        assertEquals(0, container.closeCalls)

        read.cancelAndJoin()
        assertEquals(1, raw.closeCalls)
        assertEquals(1, container.closeCalls)
    }

    @Test fun anUnexpectedReadExceptionIsPreservedAndStillReleasesTheArchive() = runTest {
        val failure = IllegalStateException("read failed")
        lateinit var guarded: PublicationResources
        val raw = TestResource(beforeCall = { guarded.close(); throw failure })
        val container = TestContainer(raw)
        guarded = PublicationResources(container)

        try {
            guarded.resource(raw).read()
            fail("The original failure must escape")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, container.closeCalls)
    }

    @Test fun resourceCloseFailureStillClosesTheArchive() = runTest {
        val failure = IllegalStateException("resource close failed")
        lateinit var guarded: PublicationResources
        lateinit var resource: Resource
        val raw = TestResource(beforeCall = { resource.close(); guarded.close() }, afterClose = { throw failure })
        val container = TestContainer(raw)
        guarded = PublicationResources(container)
        resource = guarded.resource(raw)

        try {
            resource.read()
            fail("The cleanup failure must escape")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals(1, raw.closeCalls)
        assertEquals(1, container.closeCalls)
    }

    @Test fun nestedGuardsDoNotCloseAnActiveReadOrRetainTheArchiveAfterItFinishes() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val raw = TestResource(beforeCall = { entered.complete(Unit); finish.await() })
        val container = TestContainer(raw)
        val inner = PublicationResources(container)
        val guarded = PublicationResources(inner)
        val resource = guarded.resource(inner.resource(raw))
        val result = async { resource.read() }
        entered.await()
        resource.close()
        guarded.close()
        assertEquals(0, raw.closeCalls)
        assertEquals(0, container.closeCalls)

        finish.complete(Unit)
        assertArrayEquals(raw.bytes, result.await().getOrElse { error(it.message) })
        assertEquals(1, raw.closeCalls)
        assertEquals(1, container.closeCalls)
    }

    private fun assertAccessFailure(result: Try<*, ReadError>) {
        assertTrue(result is Try.Failure && result.value is ReadError.Access)
    }

    private enum class Operation { PROPERTIES, LENGTH, READ }

    private class TestResource(
        private val beforeCall: suspend () -> Unit = {},
        private val afterClose: () -> Unit = {},
    ) : Resource {
        val bytes = byteArrayOf(1, 2, 3)
        var calls = 0
        var closeCalls = 0
        override val sourceUrl: AbsoluteUrl? = null

        override suspend fun properties(): Try<Resource.Properties, ReadError> {
            call()
            return Try.success(Resource.Properties())
        }

        override suspend fun length(): Try<Long, ReadError> {
            call()
            return Try.success(bytes.size.toLong())
        }

        override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
            call()
            return Try.success(bytes)
        }

        private suspend fun call() {
            calls++
            check(closeCalls == 0)
            beforeCall()
            check(closeCalls == 0)
        }

        override fun close() {
            closeCalls++
            afterClose()
        }
    }

    private class TestContainer(
        private val resource: Resource,
        private val afterClose: () -> Unit = {},
    ) : Container<Resource> {
        var closeCalls = 0
        override val entries: Set<Url> = emptySet()

        override fun get(url: Url): Resource? = resource

        override fun close() {
            closeCalls++
            afterClose()
        }
    }
}
