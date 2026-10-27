package com.bookcover.diagnostic.library

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** BC-27: the import limits hold for entries whose size the archive does not state, counted while reading. */
class EpubArchiveCheckTest {
    @get:Rule val folder = TemporaryFolder()

    private val small = ArchiveLimits(maxEntries = 5, maxEntryBytes = 1_000, maxTotalBytes = 2_500)
    private val container = known("META-INF/container.xml", 100)

    private fun known(name: String, size: Int) = ArchiveEntry(name, size.toLong()) { ByteArrayInputStream(ByteArray(size)) }
    private fun unknown(name: String, stream: InputStream) = ArchiveEntry(name, -1) { stream }

    /** An endless stream that remembers how much was read from it. */
    private class Endless : InputStream() {
        var served = 0L
        override fun read(): Int { served++; return 0 }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int { served += length; return length }
    }

    private fun rejected(entries: List<ArchiveEntry>): String =
        assertThrows(IllegalArgumentException::class.java) { checkArchiveEntries(entries, small) }.message.orEmpty()

    @Test fun anArchiveWithinTheLimitsPasses() {
        checkArchiveEntries(listOf(container, known("a", 1_000), known("b", 1_000)), small)
    }

    @Test fun anArchiveWithoutAContainerIsNotAnEpub() {
        assertEquals("Choose a valid EPUB file", rejected(listOf(known("a", 10))))
    }

    @Test fun knownSizesOverTheLimitsAreRejected() {
        val limit = "This EPUB's expanded content exceeds the prototype's import limit"
        assertEquals("one entry too big", limit, rejected(listOf(container, known("a", 1_001))))
        assertEquals("total too big", limit, rejected(listOf(container, known("a", 1_000), known("b", 1_000), known("c", 401))))
        assertEquals("too many entries", limit, rejected(listOf(container) + (1..5).map { known("e$it", 1) }))
    }

    @Test fun anUnknownSizeWithinTheLimitsIsAcceptedNotRejected() {
        checkArchiveEntries(listOf(container, unknown("a", ByteArrayInputStream(ByteArray(1_000)))), small)
    }

    @Test fun anUnknownSizeOverTheEntryLimitIsStoppedWhileReading() {
        val stream = Endless()
        assertEquals("This EPUB's expanded content exceeds the prototype's import limit", rejected(listOf(container, unknown("a", stream))))
        assertTrue("Stopped soon after the limit, read ${stream.served}", stream.served <= 1_000 + 64 * 1024)
    }

    @Test fun unknownSizesCountTowardsTheTotal() {
        val stream = Endless()
        // 100 + 1,000 known leaves 1,400 of the 2,500 total for the two unknown entries together.
        rejected(listOf(container, known("a", 1_000), unknown("b", ByteArrayInputStream(ByteArray(900))), unknown("c", stream)))
        assertTrue("Stopped soon after the total, read ${stream.served}", stream.served <= 1_000 + 64 * 1024)
        checkArchiveEntries(listOf(container, known("a", 1_000), unknown("b", ByteArrayInputStream(ByteArray(900))),
            unknown("c", ByteArrayInputStream(ByteArray(500)))), small)
    }

    @Test fun aRealZipFileIsCheckedWithTheDefaultLimits() {
        fun zip(vararg names: String) = File(folder.root, "${names.size}.zip").also { file ->
            ZipOutputStream(file.outputStream()).use { zip -> names.forEach { zip.putNextEntry(ZipEntry(it)); zip.write(ByteArray(10)); zip.closeEntry() } }
        }
        checkEpubArchive(zip("mimetype", "META-INF/container.xml", "EPUB/book.opf"))
        assertThrows(IllegalArgumentException::class.java) { checkEpubArchive(zip("mimetype")) }
        assertEquals(20_000, ArchiveLimits().maxEntries)
        assertEquals(100L * 1024 * 1024, ArchiveLimits().maxEntryBytes)
        assertEquals(500L * 1024 * 1024, ArchiveLimits().maxTotalBytes)
    }
}
