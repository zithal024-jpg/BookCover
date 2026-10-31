package com.bookcover.diagnostic.library

import java.io.IOException
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.file.FileSystemError
import org.readium.r2.shared.util.resource.Resource

/**
 * Stops lazy reads at publication close without closing an archive beneath an active operation.
 * WebView response streams can retain a Resource after their publication has been closed.
 *
 * Calls admitted before close may finish. Later calls fail through Readium's read-error contract.
 * Only active calls defer the underlying close: an unconsumed response cannot retain the archive.
 * The state lock is never held while a suspend resource operation runs.
 */
internal class PublicationResources(private val delegate: Container<Resource>) : Container<Resource> {
    private val lock = Any()
    private var closed = false
    private var activeCalls = 0
    private var closeStarted = false

    override val sourceUrl: AbsoluteUrl? = delegate.sourceUrl
    override val entries: Set<Url> = delegate.entries

    override fun get(url: Url): Resource? = synchronized(lock) {
        if (closed) null else delegate[url]?.let(::resource)
    }

    // Also lets JVM tests exercise the resource lifetime without Android-backed URL construction.
    internal fun resource(resource: Resource): Resource = Entry(resource)

    override fun close() {
        val closeDelegate = synchronized(lock) {
            closed = true
            claimContainerClose()
        }
        if (closeDelegate) delegate.close()
    }

    /** Must be called with [lock] held. */
    private fun claimContainerClose(): Boolean {
        if (!closed || activeCalls != 0 || closeStarted) return false
        closeStarted = true
        return true
    }

    private inner class Entry(private val resource: Resource) : Resource {
        private var closed = false
        private var activeCalls = 0
        private var closeStarted = false

        override val sourceUrl: AbsoluteUrl? = resource.sourceUrl

        override suspend fun properties(): Try<Resource.Properties, ReadError> = access { properties() }

        override suspend fun length(): Try<Long, ReadError> = access { length() }

        override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> = access { read(range) }

        private suspend fun <T> access(action: suspend Resource.() -> Try<T, ReadError>): Try<T, ReadError> {
            val admitted = synchronized(lock) {
                if (this@PublicationResources.closed || closed) false else {
                    this@PublicationResources.activeCalls++
                    activeCalls++
                    true
                }
            }
            if (!admitted) return Try.failure(ReadError.Access(
                FileSystemError.IO(IOException("Publication resource is closed")),
            ))

            try {
                return resource.action()
            } finally {
                val (closeResource, closeContainer) = synchronized(lock) {
                    activeCalls--
                    this@PublicationResources.activeCalls--
                    claimResourceClose() to claimContainerClose()
                }
                // Still release the archive if an individual resource's cleanup throws.
                try {
                    if (closeResource) resource.close()
                } finally {
                    if (closeContainer) delegate.close()
                }
            }
        }

        override fun close() {
            val closeResource = synchronized(lock) {
                closed = true
                claimResourceClose()
            }
            if (closeResource) resource.close()
        }

        /** Must be called with [lock] held. */
        private fun claimResourceClose(): Boolean {
            if (!closed || activeCalls != 0 || closeStarted) return false
            closeStarted = true
            return true
        }
    }
}
