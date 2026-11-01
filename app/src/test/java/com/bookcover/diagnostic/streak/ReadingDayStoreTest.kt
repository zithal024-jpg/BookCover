package com.bookcover.diagnostic.streak

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject

class ReadingDayStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val zone = ZoneId.of("Europe/Madrid")
    private val date = LocalDate.of(2026, 10, 27)
    private val noon = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val file get() = File(folder.root, "reading-days.json")

    @Test fun progressAndCompletionSurviveARestart() {
        ReadingDayStore(file).apply { load(); add(noon, noon + 180_000, zone); add(noon + 600_000, noon + 720_000, zone) }
        val reopened = ReadingDayStore(file).load()
        assertEquals(300_000L, reopened.day(date).millis)
        assertTrue(reopened.completed(date))
    }

    @Test fun celebrationIsRememberedAcrossRestarts() {
        ReadingDayStore(file).apply { add(noon, noon + 300_000, zone); markCelebrated(date) }
        assertEquals(date, ReadingDayStore(file).load().celebrated)
    }

    @Test fun missingFileStartsEmptyWithoutBackfill() {
        assertTrue(ReadingDayStore(file).load().days.isEmpty())
        assertFalse(file.exists())
    }

    @Test fun damagedFileIsKeptAsideNotLost() {
        file.writeText("{ not json")
        val store = ReadingDayStore(file, clock = { 42 })
        assertTrue(store.load().days.isEmpty())
        assertEquals("{ not json", File(folder.root, "reading-days.damaged-42.json").readText())
        store.add(noon, noon + 60_000, zone)
        assertEquals(60_000L, ReadingDayStore(file).load().day(date).millis)
    }

    @Test fun roundTripAndRepeatedRestoreMergeDailyMaximumNotSum() {
        val source = ReadingDayStore(file)
        source.add(noon, noon + 300_000, zone)
        source.markCelebrated(date)
        val targetFile = File(folder.newFolder(), "reading-days.json")
        val target = ReadingDayStore(targetFile)
        target.add(noon, noon + 60_000, zone)
        val merged = target.mergeBackup(source.exportJson())
        assertEquals(300_000L, merged.day(date).millis)
        assertTrue(merged.completed(date))
        assertEquals(date, merged.celebrated)
        val before = targetFile.readBytes()
        assertEquals(merged, target.mergeBackup(source.exportJson()))
        assertArrayEquals(before, targetFile.readBytes())
        assertEquals(merged, ReadingDayStore(targetFile).load())
    }

    @Test fun restoredCompletedFlagsAndDisjointDatesArePreserved() {
        val store = ReadingDayStore(file)
        store.add(noon, noon + 300_000, zone)
        val older = date.minusDays(1).toString()
        val json = JSONObject().put("version", 1).put("days", JSONObject()
            .put(date.toString(), JSONObject().put("millis", 60_000).put("completed", false))
            .put(older, JSONObject().put("millis", 60_000).put("completed", true)))
        val merged = store.mergeBackup(json)
        assertEquals(300_000L, merged.day(date).millis)
        assertTrue(merged.completed(date))
        assertTrue(merged.completed(date.minusDays(1)))
    }

    @Test fun invalidBackupRefusesWithoutChangingLocalHistory() {
        val store = ReadingDayStore(file)
        store.add(noon, noon + 300_000, zone)
        val before = file.readBytes()
        fun json(dateKey: String, millis: Any, completed: Any = false) = JSONObject().put("version", 1)
            .put("days", JSONObject().put(dateKey, JSONObject().put("millis", millis).put("completed", completed)))
        for (invalid in listOf(json("not-a-date", 0), json(date.toString(), -1),
            json(date.toString(), ReadingDayStore.MAX_DAY_MILLIS + 1), json(date.toString(), "300000"),
            json(date.toString(), 1.5), json(date.toString(), 0, "true"))) {
            assertThrows(Exception::class.java) { store.mergeBackup(invalid) }
            assertArrayEquals(before, file.readBytes())
        }
    }

    @Test fun damagedHistoryCannotExportEmptyButValidRecoveryRestoresIt() {
        file.writeText("{ bad history")
        val store = ReadingDayStore(file, clock = { 42 })
        assertThrows(IllegalStateException::class.java) { store.exportJson() }
        val json = JSONObject().put("version", 1).put("days", JSONObject().put(date.toString(),
            JSONObject().put("millis", 300_000).put("completed", true)))
        assertEquals(300_000L, store.mergeBackup(json).day(date).millis)
        assertEquals(300_000L, ReadingDayStore.validateBackup(store.exportJson()).day(date).millis)
        assertEquals("{ bad history", File(folder.root, "reading-days.damaged-42.json").readText())
    }

    @Test fun localCelebrationMarkerWinsOverBackup() {
        val store = ReadingDayStore(file)
        store.markCelebrated(date)
        val backup = store.exportJson().put("celebrated", date.minusDays(1).toString())
        assertEquals(date, store.mergeBackup(backup).celebrated)
    }
}
