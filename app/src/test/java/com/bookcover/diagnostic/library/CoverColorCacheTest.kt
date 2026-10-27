package com.bookcover.diagnostic.library

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CoverColorCacheTest {
    private val directory = Files.createTempDirectory("cover-colors-test").toFile()
    private val file = File(directory, "cover-colors.json")
    private val pink = 0xFFF8D7E3.toInt()
    private val cyan = 0xFF37BECA.toInt()
    private fun book(id: Char = 'a', revision: Long = 10) = BookRecord(id.toString().repeat(64), "Cover $id", "Test", 1, coverRevision = revision)

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun settingsReopenAndReadingPositionChangesReuseSourceColor() {
        val cache = CoverColorCache(file)
        var calls = 0
        val original = cache.colors(listOf(book())) { calls++; pink }
        val changedPosition = book().copy(lastReadAt = 500, progression = 0.7, title = "Renamed")
        val refreshed = cache.colors(listOf(changedPosition)) { calls++; cyan }
        assertEquals(1, calls)
        assertEquals(original.single().seed, refreshed.single().seed)
        assertEquals("Renamed", refreshed.single().title)
    }

    @Test fun processRestartUsesSavedColorsWithoutDecodingAgain() {
        CoverColorCache(file).colors(listOf(book())) { pink }
        var calls = 0
        val restored = CoverColorCache(file).colors(listOf(book())) { calls++; cyan }
        assertEquals(0, calls)
        assertEquals(pink, restored.single().seed)
    }

    @Test fun importAndCoverReplacementOnlyExtractChangedCovers() {
        val cache = CoverColorCache(file)
        cache.colors(listOf(book())) { pink }
        val sampled = mutableListOf<String>()
        val imported = cache.colors(listOf(book(), book('b'))) { sampled += it.id; cyan }
        assertEquals(listOf(book('b').id), sampled)
        assertEquals(listOf(pink, cyan), imported.map { it.seed })
        sampled.clear()
        val replaced = cache.colors(listOf(book(revision = 11), book('b'))) { sampled += it.id; cyan }
        assertEquals(listOf(book().id), sampled)
        assertEquals(listOf(cyan, cyan), replaced.map { it.seed })
    }

    @Test fun removalPrunesDiskCacheAndReimportExtractsAgain() {
        val cache = CoverColorCache(file)
        cache.colors(listOf(book(), book('b'))) { pink }
        cache.colors(listOf(book('b'))) { error("Unchanged cover must be cached") }
        assertFalse(file.readText().contains(book().id))
        var calls = 0
        val reimported = CoverColorCache(file).colors(listOf(book(), book('b'))) { calls++; cyan }
        assertEquals(1, calls)
        assertEquals(listOf(cyan, pink), reimported.map { it.seed })
    }

    @Test fun damagedCacheRebuildsWithoutTouchingLibraryIndex() {
        val index = File(directory, "books.json").apply { writeText("library must stay unchanged") }
        file.writeText("incomplete json")
        assertEquals(pink, CoverColorCache(file).colors(listOf(book())) { pink }.single().seed)
        assertEquals("library must stay unchanged", index.readText())
        assertEquals(pink, CoverColorCache(file).colors(listOf(book())) { cyan }.single().seed)
    }

    @Test fun missingOrInvalidCoversUseCachedSafeFallback() {
        val colors = CoverColorCache(file).colors(listOf(book(), book('b'))) {
            if (it.id == book().id) throw IllegalArgumentException("Invalid bitmap") else 0x0037BECA
        }
        assertEquals(listOf(DEFAULT_COVER_SEED, DEFAULT_COVER_SEED), colors.map { it.seed })
        assertEquals(colors, CoverColorCache(file).colors(listOf(book(), book('b'))) { cyan })
    }

    @Test fun cacheWriteFailureDoesNotLoseColorsOrRetryExtraction() {
        val unusable = File(directory, "missing-parent/colors.json")
        val cache = CoverColorCache(unusable)
        assertEquals(pink, cache.colors(listOf(book())) { pink }.single().seed)
        assertEquals(pink, cache.colors(listOf(book())) { error("Must reuse memory after disk write fails") }.single().seed)
        assertFalse(unusable.exists())
    }
}
