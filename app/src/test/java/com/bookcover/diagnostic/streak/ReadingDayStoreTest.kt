package com.bookcover.diagnostic.streak

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}
