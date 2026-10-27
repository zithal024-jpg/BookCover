package com.bookcover.diagnostic.reader

import com.bookcover.diagnostic.library.passivePublicationMarkup
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.resource.Resource

class BookSearchResourceTest {
    @Test fun rawReadStopsAtEightMiBPlusOneSentinelBeforeParsing() = runTest {
        val limit = 8 * 1024 * 1024L
        val raw = TestResource(size = limit + 100_000)
        val result = BoundedSearchMarkupResource(raw).read()
        var failed = false
        result.getOrElse { failed = true; byteArrayOf() }
        assertTrue(failed)
        assertEquals(129, raw.ranges.size)
        assertEquals(limit, raw.ranges.last().last)
        assertEquals(limit + 1, raw.ranges.sumOf { it.last - it.first + 1 })
        assertTrue(raw.ranges.all { it.last - it.first + 1 <= 65_536 })
        assertEquals(0, raw.unboundedReads)
    }

    @Test fun cancellationAfterAChunkPreventsFurtherReads() = runTest {
        val raw = TestResource(size = 1_000_000, afterRead = { currentCoroutineContext().cancel() })
        val job = launch { BoundedSearchMarkupResource(raw).read() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, raw.ranges.size)
        assertEquals(0, raw.unboundedReads)
    }

    @Test fun searchUsesTheSameUtf16DecodingAndPassiveMarkupAsTheNavigator() = runTest {
        val markup = """<?xml version="1.0" encoding="UTF-16"?><html xmlns="http://www.w3.org/1999/xhtml"><head><script>secretScriptToken</script></head><body><p onclick="secretHandler">Café العربية 中文</p><form>secretFormToken</form></body></html>"""
        val original = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + markup.toByteArray(Charsets.UTF_16LE)
        val raw = TestResource(original.size.toLong(), original)
        val actual = BoundedSearchMarkupResource(raw).read().getOrElse { throw AssertionError(it.message) }
        assertArrayEquals(passivePublicationMarkup(original), actual)
        val text = actual.toString(Charsets.UTF_8)
        assertTrue(text.contains("Café العربية 中文"))
        assertFalse(text.contains("secretScriptToken"))
        assertFalse(text.contains("secretHandler"))
        assertFalse(text.contains("secretFormToken"))
        assertEquals(1, raw.ranges.size)
        assertEquals(0, raw.unboundedReads)
    }

    @Test fun multiChunkResourceKeepsAllTextAndClosesOnlyItsOwnEntry() = runTest {
        val original = ("<html><body><p>" + "ordinary text ".repeat(7_000) + "unique final passage</p></body></html>")
            .toByteArray(Charsets.UTF_8)
        val raw = TestResource(original.size.toLong(), original)
        val resource = BoundedSearchMarkupResource(raw)
        val actual = resource.read().getOrElse { throw AssertionError(it.message) }
        assertArrayEquals(passivePublicationMarkup(original), actual)
        assertTrue(raw.ranges.size > 1)
        assertEquals(0, raw.closeCalls)
        resource.close()
        assertEquals(1, raw.closeCalls)
    }

    private class TestResource(
        private val size: Long,
        private val bytes: ByteArray? = null,
        private val afterRead: suspend () -> Unit = {},
    ) : Resource {
        val ranges = mutableListOf<LongRange>()
        var unboundedReads = 0
        var closeCalls = 0
        override val sourceUrl: AbsoluteUrl? = null
        override suspend fun properties(): Try<Resource.Properties, ReadError> = Try.success(Resource.Properties())
        override suspend fun length(): Try<Long, ReadError> = Try.success(size)
        override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> {
            if (range == null) unboundedReads++ else ranges += range
            val start = (range?.first ?: 0).coerceAtMost(size)
            val end = minOf(range?.last?.plus(1) ?: size, size).coerceAtLeast(start)
            val content = bytes?.copyOfRange(start.toInt(), end.toInt())
                ?: ByteArray((end - start).toInt()) { 'x'.code.toByte() }
            afterRead()
            return Try.success(content)
        }
        override fun close() { closeCalls++ }
    }
}
