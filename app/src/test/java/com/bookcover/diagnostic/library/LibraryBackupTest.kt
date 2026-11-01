package com.bookcover.diagnostic.library

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import com.bookcover.diagnostic.rewards.MoonlitTier
import com.bookcover.diagnostic.rewards.RelicAppearance
import com.bookcover.diagnostic.rewards.RelicStore
import com.bookcover.diagnostic.streak.ReadingDay
import com.bookcover.diagnostic.streak.ReadingDays
import com.bookcover.diagnostic.streak.ReadingDayStore

class LibraryBackupTest {
    @get:Rule val folder = TemporaryFolder()
    private val read = BookRecord("a".repeat(64), "Read", "Author", 1, "Favourites", 500, 0.4,
        "{\"href\":\"ch3.xhtml\"}", 9, listOf("{\"href\":\"ch1.xhtml\"}", "{\"href\":\"ch2.xhtml\"}"))
    private val unread = BookRecord("b".repeat(64), "Unread", "Author", 2, "Samples")

    // The same book re-imported: reading data is gone, the local cover stays as it is.
    private fun fresh(record: BookRecord) = BookRecord(record.id, record.title, record.author, record.addedAt, coverRevision = record.coverRevision)

    @Test fun exportThenRestoreBringsBackPositionsBookmarksAndCollections() {
        val backup = LibraryBackup.parse(LibraryBackup.export(listOf(read, unread), exportedAt = 1000))
        val result = LibraryBackup.merge(listOf(fresh(read), fresh(unread)), backup.records)
        assertEquals(listOf(read, unread), result.records)
        assertEquals(2, result.restored)
        assertEquals(0, result.missing)
    }

    @Test fun bookmarksAreCombinedNotReplaced() {
        val current = read.copy(bookmarks = listOf("{\"href\":\"ch2.xhtml\"}", "{\"href\":\"ch9.xhtml\"}"))
        val merged = LibraryBackup.merge(listOf(current), listOf(read)).records.single()
        assertEquals(listOf("{\"href\":\"ch2.xhtml\"}", "{\"href\":\"ch9.xhtml\"}", "{\"href\":\"ch1.xhtml\"}"), merged.bookmarks)
    }

    @Test fun moreRecentReadingPositionIsKept() {
        val newer = read.copy(lastReadAt = 900, progression = 0.8, locator = "{\"href\":\"ch8.xhtml\"}")
        val merged = LibraryBackup.merge(listOf(newer), listOf(read)).records.single()
        assertEquals(0.8, merged.progression, 0.0)
        assertEquals("{\"href\":\"ch8.xhtml\"}", merged.locator)
        assertEquals(900L, merged.lastReadAt)
    }

    @Test fun booksNotInTheLibraryAreCountedAsMissing() {
        val result = LibraryBackup.merge(listOf(fresh(unread)), listOf(read, unread))
        assertEquals(listOf(unread), result.records)
        assertEquals(1, result.restored)
        assertEquals(1, result.missing)
    }

    @Test fun booksOnlyInTheLibraryAreLeftAlone() {
        val result = LibraryBackup.merge(listOf(read, unread), listOf(unread))
        assertEquals(listOf(read, unread), result.records)
    }

    @Test fun rejectsFilesThatAreNotBackups() {
        assertThrows(LibraryBackupException::class.java) { LibraryBackup.parse("not JSON") }
        assertThrows(LibraryBackupException::class.java) { LibraryBackup.parse("{\"hello\": 1}") }
        assertThrows(LibraryBackupException::class.java) {
            LibraryBackup.parse(JSONObject().put("format", "something-else").put("books", JSONArray()).toString())
        }
    }

    @Test fun badRecordsInABackupAreSkipped() {
        val text = JSONObject().put("format", "bookcover-library-backup").put("version", 1)
            .put("books", JSONArray().put(read.toJson()).put(JSONObject().put("id", "../x")).put(42)).toString()
        val backup = LibraryBackup.parse(text)
        assertEquals(listOf(read), backup.records)
        assertEquals(2, backup.skipped)
    }

    @Test fun acceptsAPlainLibraryIndexFile() {
        val index = JSONObject().put("version", 1).put("books", JSONArray().put(read.toJson())).toString()
        assertEquals(listOf(read), LibraryBackup.parse(index).records)
    }

    private fun extras(): Pair<JSONObject, JSONObject> {
        val store = RelicStore(File(folder.newFolder(), "relics.json"), clock = { 42 })
        store.reconcile(ReadingDays(mapOf(LocalDate.of(2026, 11, 1) to ReadingDay(5 * 3_600_000L, true))))
        store.setAppearance(null, RelicAppearance(enabled = true, seal = MoonlitTier.RARE, inscription = "My archive"))
        store.markSeen()
        return store.exportJson() to store.readingHistoryFloorJson()
    }

    @Test fun versionTwoRoundTripRestoresReadingDaysPermanentRelicsAndAppearance() {
        val (relics, days) = extras()
        val text = LibraryBackup.export(listOf(read, unread), exportedAt = 1000, relics = relics, readingDays = days)
        assertEquals(2, JSONObject(text).getInt("version"))
        val parsed = LibraryBackup.parse(text)
        assertEquals(listOf(read, unread), parsed.records)
        assertEquals(0, parsed.skipped)
        val relicTarget = RelicStore(File(folder.newFolder(), "relics.json"))
        val dayTarget = ReadingDayStore(File(folder.newFolder(), "reading-days.json"))
        val restored = relicTarget.mergeBackup(requireNotNull(parsed.relics))
        val history = dayTarget.mergeBackup(requireNotNull(parsed.readingDays))
        assertEquals(5 * 3_600_000L, restored.totalMillis)
        assertEquals(setOf(MoonlitTier.COMMON, MoonlitTier.UNCOMMON, MoonlitTier.RARE), restored.unlocked)
        assertTrue(restored.unseen.isEmpty())
        assertEquals(MoonlitTier.RARE, restored.defaultAppearance.seal)
        assertEquals("My archive", restored.defaultAppearance.inscription)
        assertEquals(5 * 3_600_000L, history.day(LocalDate.of(2026, 11, 1)).millis)
        assertEquals(listOf(read, unread), LibraryBackup.merge(listOf(fresh(read), fresh(unread)), parsed.records).records)
        // A second import of the same exported file never counts its reading time twice.
        assertEquals(restored, relicTarget.mergeBackup(requireNotNull(parsed.relics)))
        assertEquals(history, dayTarget.mergeBackup(requireNotNull(parsed.readingDays)))
    }

    @Test fun malformedRelicsRejectTheWholeBackupBeforeRecordsAreReturned() {
        val (relics, days) = extras()
        val json = JSONObject(LibraryBackup.export(listOf(read), 1000, relics, days))
        json.getJSONObject("relics").put("totalMillis", -1)
        assertThrows(LibraryBackupException::class.java) { LibraryBackup.parse(json.toString()) }
    }

    @Test fun malformedReadingDaysRejectTheWholeBackupBeforeRecordsAreReturned() {
        val (relics, days) = extras()
        val json = JSONObject(LibraryBackup.export(listOf(read), 1000, relics, days))
        json.getJSONObject("readingDays").getJSONObject("days").put("not-a-date",
            JSONObject().put("millis", 100).put("completed", false))
        assertThrows(LibraryBackupException::class.java) { LibraryBackup.parse(json.toString()) }
    }

    @Test fun extrasMustBeObjectsRatherThanNullArraysOrStrings() {
        val (relics, days) = extras()
        for (field in listOf("relics", "readingDays")) {
            for (invalid in listOf(JSONObject.NULL, JSONArray(), "not-an-object")) {
                val json = JSONObject(LibraryBackup.export(listOf(read), 1000, relics, days)).put(field, invalid)
                assertThrows(LibraryBackupException::class.java) { LibraryBackup.parse(json.toString()) }
            }
        }
    }

    @Test fun serializationRejectsInvalidExtrasAndDoesNotAlterTheirSourceObjects() {
        val (relics, days) = extras()
        val sourceRelics = relics.toString()
        val sourceDays = days.toString()
        val malformedRelics = JSONObject(sourceRelics).put("earned", JSONObject().put("archive", -1))
        val malformedDays = JSONObject(sourceDays).put("days", JSONObject().put("2026-11-01",
            JSONObject().put("millis", "five hours").put("completed", false)))
        assertThrows(IllegalArgumentException::class.java) { LibraryBackup.export(listOf(read), 1000, malformedRelics, days) }
        assertThrows(IllegalArgumentException::class.java) { LibraryBackup.export(listOf(read), 1000, relics, malformedDays) }
        assertEquals(sourceRelics, relics.toString())
        assertEquals(sourceDays, days.toString())
    }

    @Test fun legacyVersionOneAndUnversionedIndexKeepTheirOriginalRestoreBehavior() {
        val v1 = LibraryBackup.export(listOf(read, unread), 1000)
        assertEquals(1, JSONObject(v1).getInt("version"))
        val index = JSONObject().put("books", JSONArray().put(read.toJson()).put(unread.toJson())).toString()
        for (text in listOf(v1, index)) {
            val parsed = LibraryBackup.parse(text)
            assertNull(parsed.relics)
            assertNull(parsed.readingDays)
            assertEquals(listOf(read, unread), LibraryBackup.merge(listOf(fresh(read), fresh(unread)), parsed.records).records)
        }
    }

    @Test fun futureBackupVersionIsRejectedEvenWithOtherwiseValidExtras() {
        val (relics, days) = extras()
        val json = JSONObject(LibraryBackup.export(listOf(read), 1000, relics, days)).put("version", 3)
        assertThrows(LibraryBackupException::class.java) { LibraryBackup.parse(json.toString()) }
    }

    @Test fun explicitlyMalformedVersionsCannotBeCoercedIntoSupportedVersions() {
        val (relics, days) = extras()
        for (version in listOf("garbage", false, 2.5, JSONObject.NULL)) {
            val json = JSONObject(LibraryBackup.export(listOf(read), 1000, relics, days)).put("version", version)
            assertThrows(LibraryBackupException::class.java) { LibraryBackup.parse(json.toString()) }
        }
    }
}
