package com.bookcover.diagnostic.library

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/** How much an imported EPUB may expand to: entry count, one entry's size, and all entries together. */
internal data class ArchiveLimits(
    val maxEntries: Int = 20_000,
    val maxEntryBytes: Long = 100L * 1024 * 1024,
    val maxTotalBytes: Long = 500L * 1024 * 1024,
)

/** One archive entry. [size] is negative when the archive does not state it. */
internal class ArchiveEntry(val name: String, val size: Long, val open: () -> InputStream)

/** Checks an imported EPUB's zip structure before Readium parses it. */
internal fun checkEpubArchive(file: File, limits: ArchiveLimits = ArchiveLimits()) {
    ZipFile(file).use { zip ->
        checkArchiveEntries(zip.entries().asSequence().map { entry ->
            ArchiveEntry(entry.name, entry.size) { zip.getInputStream(entry) }
        }.toList(), limits)
    }
}

/**
 * Stated sizes are checked first. An entry without a stated size is read and counted, and reading stops
 * as soon as it would pass the entry limit or the total, so the limits hold without trusting the archive.
 */
internal fun checkArchiveEntries(entries: List<ArchiveEntry>, limits: ArchiveLimits = ArchiveLimits()) {
    require(entries.any { it.name == "META-INF/container.xml" }) { "Choose a valid EPUB file" }
    val (unknown, known) = entries.partition { it.size < 0 }
    var total = known.sumOf { it.size }
    require(entries.size <= limits.maxEntries && known.all { it.size <= limits.maxEntryBytes } &&
        total <= limits.maxTotalBytes) { "This EPUB's expanded content exceeds the prototype's import limit" }
    val buffer = ByteArray(32 * 1024)
    for (entry in unknown) {
        val allowed = minOf(limits.maxEntryBytes, limits.maxTotalBytes - total)
        var counted = 0L
        entry.open().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                counted += read
                require(counted <= allowed) { "This EPUB's expanded content exceeds the prototype's import limit" }
            }
        }
        total += counted
    }
}
