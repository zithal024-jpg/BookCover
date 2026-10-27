package com.bookcover.diagnostic.library

import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LibraryAccessTest {
    private class Destination {
        var bytes = "existing good backup".toByteArray()
        var opens = 0
        fun open() = object : ByteArrayOutputStream() {
            init { opens++; bytes = byteArrayOf() }
            override fun close() { bytes = toByteArray() }
        }
    }

    private suspend fun refuses(block: suspend () -> Unit): LibraryNotReadyException {
        try { block(); error("Unready library was accepted") }
        catch (expected: LibraryNotReadyException) { return expected }
    }

    @Test fun beforeLoadExportPreservesDestinationAndRestoreCannotMerge() = runTest {
        val access = LibraryAccess()
        val destination = Destination()
        val original = destination.bytes.copyOf()
        refuses { access.export({ PreparedBackup(0, byteArrayOf()) }, destination::open) }
        var merged = false
        refuses { access.withReadyLibrary { merged = true } }
        assertFalse(merged)
        assertEquals(0, destination.opens)
        assertArrayEquals(original, destination.bytes)
    }

    @Test fun exportAndRestoreWaitForDelayedLoadAndUseLoadedRecords() = runTest {
        val access = LibraryAccess()
        val gate = CompletableDeferred<Unit>()
        var records = emptyList<String>()
        val load = launch(start = CoroutineStart.UNDISPATCHED) { access.load { gate.await(); records = listOf("saved position") } }
        val destination = Destination()
        val export = async(start = CoroutineStart.UNDISPATCHED) {
            access.export({ PreparedBackup(records.size, records.joinToString().toByteArray()) }, destination::open)
        }
        val restore = async(start = CoroutineStart.UNDISPATCHED) { access.withReadyLibrary { records.size } }
        assertEquals(0, destination.opens)
        assertFalse(export.isCompleted)
        assertFalse(restore.isCompleted)
        gate.complete(Unit); load.join()
        assertEquals(1, export.await())
        assertEquals(1, restore.await())
        assertEquals("saved position", destination.bytes.toString(Charsets.UTF_8))
    }

    @Test fun failedAndCancelledLoadsRefuseWithoutTouchingDestinationAndCanRetry() = runTest {
        for (cancel in listOf(false, true)) {
            val access = LibraryAccess()
            if (cancel) {
                val job = launch(start = CoroutineStart.UNDISPATCHED) { access.load { awaitCancellation() } }
                job.cancelAndJoin()
            } else {
                try { access.load { throw IOException("unreadable index") } } catch (_: IOException) { }
            }
            val destination = Destination()
            val original = destination.bytes.copyOf()
            assertTrue(refuses { access.export({ PreparedBackup(0, byteArrayOf()) }, destination::open) }.loadFailed)
            refuses { access.withReadyLibrary { error("Must not merge") } }
            assertArrayEquals(original, destination.bytes)
            assertEquals(0, destination.opens)
            access.load { }
            assertEquals(0, access.export({ PreparedBackup(0, "empty backup".toByteArray()) }, destination::open))
        }
    }

    @Test fun preparationFailureDoesNotTruncateAndGenuinelyEmptyLibraryIsAllowed() = runTest {
        val access = LibraryAccess()
        access.load { }
        val destination = Destination()
        val original = destination.bytes.copyOf()
        try {
            access.export({ throw IOException("serialization failed") }, destination::open)
            fail("Expected preparation failure")
        } catch (_: IOException) { }
        assertEquals(0, destination.opens)
        assertArrayEquals(original, destination.bytes)
        assertEquals(0, access.export({ PreparedBackup(0, "valid empty backup".toByteArray()) }, destination::open))
        assertEquals("valid empty backup", destination.bytes.toString(Charsets.UTF_8))
    }

    @Test fun cancellationWhileWaitingNeverOpensDestinationOrBlocksLoading() = runTest {
        val access = LibraryAccess()
        val gate = CompletableDeferred<Unit>()
        val load = launch(start = CoroutineStart.UNDISPATCHED) { access.load { gate.await() } }
        val destination = Destination()
        val export = launch(start = CoroutineStart.UNDISPATCHED) { access.export({ PreparedBackup(1, byteArrayOf(1)) }, destination::open) }
        export.cancelAndJoin()
        gate.complete(Unit); load.join()
        assertEquals(0, destination.opens)
        assertEquals(7, access.withReadyLibrary { 7 })
    }

    @Test fun cancellationDuringPreparationNeverOpensDestination() = runTest {
        val access = LibraryAccess()
        access.load { }
        val destination = Destination()
        val export = launch(start = CoroutineStart.UNDISPATCHED) {
            val caller = currentCoroutineContext()
            access.export({ caller.cancel(); PreparedBackup(1, byteArrayOf(1)) }, destination::open)
        }
        export.join()
        assertTrue(export.isCancelled)
        assertEquals(0, destination.opens)
    }

    @Test fun snapshotIsPreparedBeforeProviderIsOpened() = runTest {
        val access = LibraryAccess()
        var records = listOf("original position")
        access.load { }
        val destination = Destination()
        access.export({ PreparedBackup(records.size, records.joinToString().toByteArray()) }, {
            records = listOf("later position", "later book")
            destination.open()
        })
        assertEquals("original position", destination.bytes.toString(Charsets.UTF_8))
    }
}
