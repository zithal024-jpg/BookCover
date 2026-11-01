package com.bookcover.diagnostic.reader

import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PdfTextBlock(val text: String, val bounds: List<RectF>)

/** A page index refers to the original PDF's zero-based physical page. */
internal data class PdfTextPage(val index: Int, val blocks: List<PdfTextBlock>)

internal class PdfTextLimitException : Exception("PDF page text exceeds the reading limit")

/** Owns one renderer. Reads and renderer destruction never overlap. */
internal class PdfTextPageSource private constructor(
    private val renderer: PdfRenderer,
    val pageCount: Int,
) : Closeable {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @RequiresApi(35)
    suspend fun readPage(index: Int): PdfTextPage = withContext(Dispatchers.IO) {
        require(index in 0 until pageCount) { "PDF page index is out of range" }
        mutex.withLock {
            check(!closed.get()) { "PDF text source is closed" }
            currentCoroutineContext().ensureActive()
            renderer.openPage(index).use { page ->
                val contents = page.textContents
                if (contents.size > MAX_BLOCKS) throw PdfTextLimitException()
                var totalCharacters = 0L
                var totalBounds = 0L
                val blocks = contents.map { content ->
                    currentCoroutineContext().ensureActive()
                    val text = content.text
                    val bounds = content.bounds
                    totalCharacters += text.length
                    totalBounds += bounds.size
                    if (totalCharacters > MAX_CHARACTERS || totalBounds > MAX_BOUNDS) {
                        throw PdfTextLimitException()
                    }
                    PdfTextBlock(text, bounds.map(::RectF))
                }
                currentCoroutineContext().ensureActive()
                check(!closed.get()) { "PDF text source is closed" }
                PdfTextPage(index, blocks)
            }
        }
    }

    /** Marks the source closed immediately; native cleanup waits for any active read. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cleanupScope.launch {
            try {
                mutex.withLock { runCatching { renderer.close() } }
            } finally {
                cleanupScope.cancel()
            }
        }
    }

    companion object {
        private const val MAX_CHARACTERS = 300_000
        private const val MAX_BLOCKS = 10_000
        private const val MAX_BOUNDS = 20_000

        /** Returns null when Android does not provide PDF text extraction. */
        suspend fun open(file: File): PdfTextPageSource? {
            if (Build.VERSION.SDK_INT < 35) return null
            // withContext may discard its result when the caller is cancelled while
            // returning to the original dispatcher. Keep ownership until it returns.
            val created = AtomicReference<PdfTextPageSource?>()
            try {
                return withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val renderer = try {
                        PdfRenderer(descriptor)
                    } catch (failure: Throwable) {
                        runCatching { descriptor.close() }
                        throw failure
                    }
                    val source = try {
                        PdfTextPageSource(renderer, renderer.pageCount)
                    } catch (failure: Throwable) {
                        runCatching { renderer.close() }
                        throw failure
                    }
                    created.set(source)
                    currentCoroutineContext().ensureActive()
                    source
                }
            } catch (failure: Throwable) {
                created.get()?.close()
                throw failure
            }
        }
    }
}
