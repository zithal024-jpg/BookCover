package com.bookcover.diagnostic.library

import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LibraryNotReadyException(val loadFailed: Boolean) : Exception("Library not ready")

internal data class PreparedBackup(val count: Int, val bytes: ByteArray)

/** Coordinates index loading and backup access using the library's existing storage lock. */
internal class LibraryAccess(private val lock: Mutex = Mutex()) {
    private var ready = false
    private var loadFailed = false

    suspend fun <T> load(block: suspend () -> T): T = lock.withLock {
        ready = false
        // Failure/cancellation leaves access refused; a later successful load may retry.
        loadFailed = true
        currentCoroutineContext().ensureActive()
        val result = block()
        currentCoroutineContext().ensureActive()
        ready = true
        loadFailed = false
        result
    }

    suspend fun <T> withReadyLibrary(block: () -> T): T = lock.withLock {
        currentCoroutineContext().ensureActive()
        if (!ready) throw LibraryNotReadyException(loadFailed)
        block()
    }

    suspend fun export(prepare: () -> PreparedBackup, openDestination: () -> OutputStream): Int {
        // Serialize a consistent snapshot while locked, before touching a provider destination.
        val backup = withReadyLibrary(prepare)
        currentCoroutineContext().ensureActive()
        // SAF providers need not support atomic replacement; a failure after open can leave a partial file.
        openDestination().use { output ->
            output.write(backup.bytes)
            return backup.count
        }
    }
}
