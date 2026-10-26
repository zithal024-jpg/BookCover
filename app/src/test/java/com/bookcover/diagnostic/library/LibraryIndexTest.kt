package com.bookcover.diagnostic.library

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryIndexTest {
    @get:Rule val folder = TemporaryFolder()

    private val first = BookRecord("a".repeat(64), "First", "Author", 1)
    private val second = BookRecord("b".repeat(64), "Second", "Author", 2)
    private val third = BookRecord("c".repeat(64), "Third", "Author", 3)
    private val directory get() = folder.root
    private val primary get() = File(directory, "books.json")

    private fun indexJson(vararg books: Any): String =
        JSONObject().put("version", 1).put("books", JSONArray().apply { books.forEach { put(it) } }).toString()

    @Test fun missingIndexLoadsEmptyAndAllowsSaving() {
        val index = LibraryIndex(directory)
        assertTrue(index.load().records.isEmpty())
        index.save(listOf(first))
        assertEquals(listOf(first), LibraryIndex(directory).load().records)
    }

    @Test fun refusesToSaveBeforeLoad() {
        primary.writeText(indexJson(first.toJson()))
        val index = LibraryIndex(directory)
        assertThrows(IllegalStateException::class.java) { index.save(emptyList()) }
        assertEquals(listOf(first), LibraryIndex(directory).load().records)
    }

    @Test fun unreadableIndexWithoutBackupRefusesWritesAndIsLeftUntouched() {
        primary.writeText("{\"books\": [ this is not JSON")
        val index = LibraryIndex(directory)
        assertThrows(LibraryIndexException::class.java) { index.load() }
        assertThrows(IllegalStateException::class.java) { index.save(emptyList()) }
        assertEquals("{\"books\": [ this is not JSON", primary.readText())
    }

    @Test fun badRecordsAreSkippedAndTheRestAreKept() {
        primary.writeText(indexJson(first.toJson(), JSONObject().put("id", "../outside"), "not a record", second.toJson()))
        val result = LibraryIndex(directory).load()
        assertEquals(listOf(first, second), result.records)
        assertEquals(2, result.skipped)
    }

    @Test fun damagedIndexIsKeptAsACopyBeforeItIsReplaced() {
        val damaged = indexJson(first.toJson(), JSONObject().put("id", 7))
        primary.writeText(damaged)
        val index = LibraryIndex(directory)
        index.load()
        index.save(listOf(first, second))
        val copies = directory.listFiles { file -> file.name.startsWith("books.damaged-") }.orEmpty()
        assertEquals(1, copies.size)
        assertEquals(damaged, copies.single().readText())
    }

    @Test fun unreadableIndexRecoversFromLastGoodBackup() {
        LibraryIndex(directory).apply { load(); save(listOf(first)); save(listOf(first, second)) }
        primary.writeText("garbage")
        val result = LibraryIndex(directory).load()
        assertEquals(listOf(first), result.records)
        assertTrue(result.recoveredFromBackup)
    }

    @Test fun legacyAtomicFileBackupIsUsedWhenIndexIsMissing() {
        File(directory, "books.json.bak").writeText(indexJson(first.toJson()))
        assertEquals(listOf(first), LibraryIndex(directory).load().records)
    }

    @Test fun backupIsNotReplacedByADamagedIndex() {
        LibraryIndex(directory).apply { load(); save(listOf(first)); save(listOf(first, second)) }
        primary.writeText(indexJson(first.toJson(), second.toJson(), JSONObject()))
        LibraryIndex(directory).apply { load(); save(listOf(first, second, third)) }
        primary.writeText("garbage")
        assertEquals(listOf(first), LibraryIndex(directory).load().records)
    }

    @Test fun duplicateRecordsKeepTheFirstCopy() {
        primary.writeText(indexJson(first.toJson(), first.copy(title = "Copy").toJson()))
        val result = LibraryIndex(directory).load()
        assertEquals(listOf(first), result.records)
        assertEquals(1, result.skipped)
    }
}
