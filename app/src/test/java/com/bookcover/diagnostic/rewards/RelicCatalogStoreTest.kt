package com.bookcover.diagnostic.rewards

import com.bookcover.diagnostic.streak.DAILY_GOAL_MILLIS
import com.bookcover.diagnostic.streak.ReadingDay
import com.bookcover.diagnostic.streak.ReadingDays
import com.bookcover.diagnostic.streak.ReadingDayStore
import java.io.File
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RelicCatalogStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val first = LocalDate.of(2025, 1, 1)
    private val bookId = "a".repeat(64)
    private fun store(): RelicStore = RelicStore(File(folder.newFolder(), "relics.json"), clock = { 42 })
    private fun history(count: Int, spacing: Long = 1, millis: Long = DAILY_GOAL_MILLIS, offset: Long = 0) =
        ReadingDays((0 until count).associate { first.plusDays(offset + it * spacing) to ReadingDay(millis, true) })

    @Test fun catalogHasStableUniqueIdsAndIndependentFamilyPermissions() {
        assertEquals(18, RelicCatalog.all.size)
        assertEquals(18, RelicCatalog.all.map { it.id }.toSet().size)
        assertEquals(MoonlitTier.entries.map { it.id }, RelicCatalog.forSet(RelicSet.MOONLIT).map { it.id })
        RelicSet.entries.forEach { set -> assertEquals(MoonlitTier.entries.toList(), RelicCatalog.forSet(set).map { it.tier }) }
        assertEquals("hearth_everlasting", RelicCatalog.item(RelicSet.HEARTHKEEPER, MoonlitTier.MYTHIC).id)
        val mixed = RelicAppearance(enabled = true, edgeTier = MoonlitTier.RARE, edgeSet = RelicSet.WAYFARER,
            seal = MoonlitTier.COMMON, sealSet = RelicSet.HEARTHKEEPER, bookplate = RelicBookplate.PRESTIGE)
        val wrongFamily = mixed.permittedRelics(RelicCatalog.forSet(RelicSet.MOONLIT).toSet())
        assertNull(wrongFamily.edgeTier); assertNull(wrongFamily.seal)
        val allowed = mixed.permittedRelics(setOf(RelicCatalog.item(RelicSet.WAYFARER, MoonlitTier.RARE),
            RelicCatalog.item(RelicSet.HEARTHKEEPER, MoonlitTier.COMMON)))
        assertEquals(MoonlitTier.RARE, allowed.edgeTier)
        assertEquals(MoonlitTier.COMMON, allowed.seal)
        assertEquals(RelicBookplate.NONE, allowed.bookplate)
    }

    @Test fun atlasEachExactQualifyingDayMilestoneWorksWithoutAStreak() {
        for (item in RelicCatalog.forSet(RelicSet.WAYFARER)) {
            val relics = store()
            assertFalse(item in relics.reconcile(history(item.requiredDays - 1, spacing = 2)).unlockedRelics)
            val earned = relics.reconcile(history(item.requiredDays, spacing = 2))
            assertTrue(item in earned.unlockedRelics)
            assertEquals(item.requiredDays, earned.qualifyingDays)
            assertEquals(1, earned.bestStreak)
        }
    }

    @Test fun hearthEachExactBestStreakMilestoneWorksBeforeItsTotalDayAlternative() {
        for (item in RelicCatalog.forSet(RelicSet.HEARTHKEEPER)) {
            val relics = store()
            assertFalse(item in relics.reconcile(history(item.requiredStreak - 1)).unlockedRelics)
            val earned = relics.reconcile(history(item.requiredStreak))
            assertTrue(item in earned.unlockedRelics)
            assertEquals(item.requiredStreak, earned.bestStreak)
            assertTrue(earned.qualifyingDays < item.requiredDays)
        }
    }

    @Test fun hearthEachExactTotalDayAlternativeWorksWithOnlyOneDayBestStreak() {
        for (item in RelicCatalog.forSet(RelicSet.HEARTHKEEPER)) {
            val relics = store()
            assertFalse(item in relics.reconcile(history(item.requiredDays - 1, spacing = 2)).unlockedRelics)
            val earned = relics.reconcile(history(item.requiredDays, spacing = 2))
            assertTrue(item in earned.unlockedRelics)
            assertEquals(1, earned.bestStreak)
        }
    }

    @Test fun partialDaysNeverCountButHistoricalCompletedFlagsSurviveFloorRecovery() {
        val relics = store()
        relics.reconcile(ReadingDays(mapOf(first to ReadingDay(100, false))))
        assertEquals(0, relics.collection.value.qualifyingDays)
        val flagged = history(7, millis = 100)
        val earned = relics.reconcile(flagged)
        assertEquals(7, earned.qualifyingDays)
        assertEquals(7, earned.bestStreak)
        assertTrue(RelicCatalog.item(RelicSet.WAYFARER, MoonlitTier.RARE) in earned.unlockedRelics)
        val historyStore = ReadingDayStore(File(folder.newFolder(), "reading-days.json"))
        val recovered = historyStore.mergeBackup(relics.readingHistoryFloorJson())
        assertEquals(flagged.days, recovered.days)
        assertEquals(earned, relics.reconcile(recovered))
        val export = relics.exportJson()
        val reopened = store().mergeBackup(export)
        assertEquals(earned, reopened)
        assertTrue(export.getJSONObject("readingDays").getJSONObject("days").getJSONObject(first.toString()).getBoolean("completed"))
    }

    @Test fun mergedDatedRecordsBridgeStreaksAndRepeatedRestoreNeverAddsCreditTwice() {
        val local = store()
        local.reconcile(history(2))
        val incoming = store()
        incoming.reconcile(history(2, offset = 2))
        val restored = local.mergeBackup(incoming.exportJson())
        assertEquals(4, restored.qualifyingDays)
        assertEquals(4, restored.bestStreak)
        assertEquals(4 * DAILY_GOAL_MILLIS, restored.totalMillis)
        assertEquals(0L, restored.earned["hearth_spark"])
        repeat(3) {
            assertEquals(restored, local.mergeBackup(incoming.exportJson()))
            assertEquals(restored, local.reconcile(history(4)))
        }
    }

    @Test fun missingOrSmallerHistoryCannotRevokeAnyAwardOrHistoricalStatistic() {
        val relics = store()
        val earned = relics.reconcile(history(365, spacing = 2, millis = 3_600_000))
        assertEquals(18, earned.unlockedRelics.size)
        assertEquals(earned, relics.reconcile(ReadingDays()))
        assertEquals(earned, relics.reconcile(history(1)))
        assertEquals(earned, relics.mergeBackup(store().exportJson()))
        assertTrue(earned.unseenRelics.all { earned.earned[it.id] == 0L })
    }

    @Test fun allFamiliesAndIndependentEdgeSealChoicesRoundTripInVersionTwoBackup() {
        val source = store()
        source.reconcile(history(365, spacing = 2, millis = 3_600_000))
        val mixed = RelicAppearance(enabled = true, edgeTier = MoonlitTier.MYTHIC, edgeSet = RelicSet.WAYFARER,
            seal = MoonlitTier.MYTHIC, sealSet = RelicSet.HEARTHKEEPER, motion = true)
        source.setAppearance(null, mixed)
        source.setAppearance(bookId, mixed.copy(edgeSet = RelicSet.HEARTHKEEPER, sealSet = RelicSet.MOONLIT))
        source.markSeen()
        val json = source.exportJson()
        assertEquals(2, json.getInt("version"))
        val restored = store().mergeBackup(json)
        assertEquals(source.collection.value, restored)
        assertEquals(mixed, restored.appearanceForRelics(null))
        assertEquals(RelicSet.HEARTHKEEPER, restored.appearanceForRelics(bookId).edgeSet)
        assertTrue(restored.unseenRelics.isEmpty())
    }

    @Test fun legacyVersionOneMigratesDatedFlagsAndNewAwardsRetroactivelyWithoutInventedTime() {
        val source = store()
        source.reconcile(history(7, millis = 100))
        val legacy = source.exportJson().apply {
            put("version", 1); remove("qualifyingDays"); remove("bestStreak")
            put("earned", JSONObject()); put("seen", JSONArray())
            getJSONObject("defaultAppearance").apply {
                remove("edgeSet"); remove("sealSet"); put("bookplate", "PRESTIGE")
            }
        }
        val file = File(folder.newFolder(), "relics.json").apply { writeText(legacy.toString()) }
        val before = file.readBytes()
        val upgraded = RelicStore(file).load()
        assertEquals(700L, upgraded.totalMillis)
        assertEquals(7, upgraded.qualifyingDays)
        assertEquals(7, upgraded.bestStreak)
        assertEquals(0L, upgraded.earned["atlas_lens"])
        assertEquals(0L, upgraded.earned["hearth_candle"])
        assertEquals(RelicBookplate.NONE, upgraded.defaultAppearance.bookplate)
        assertEquals(RelicSet.MOONLIT, upgraded.defaultAppearance.edgeSet)
        assertArrayEquals(before, file.readBytes())
        val export = RelicStore(file).exportJson()
        assertEquals(2, export.getInt("version"))
        assertEquals(upgraded, store().mergeBackup(export))
    }

    @Test fun invalidFamilyStatisticsAndUnearnedNewItemsRejectBeforeWriting() {
        val local = store()
        local.reconcile(history(1))
        val original = local.exportJson().toString()
        val invalid = listOf(
            JSONObject(original).put("qualifyingDays", "1"),
            JSONObject(original).put("qualifyingDays", 0),
            JSONObject(original).put("bestStreak", 2),
            JSONObject(original).put("bestStreak", -1),
            JSONObject(original).put("qualifyingDays", 100_001),
            JSONObject(original).put("earned", JSONObject().put("atlas_horizon", 0)),
            JSONObject(original).put("earned", JSONObject().put("hearth_everlasting", 0)),
            JSONObject(original).apply { getJSONObject("defaultAppearance").put("edgeSet", "unknown") },
            JSONObject(original).apply { getJSONObject("defaultAppearance").remove("sealSet") },
            JSONObject(original).put("version", 3),
        )
        invalid.forEach { bad ->
            assertThrows(Exception::class.java) { local.mergeBackup(bad) }
            assertEquals(original, local.exportJson().toString())
        }
    }

    @Test fun firstFullHistoryAfterLegacyLoadGrantsRetroactiveDatesThenNewDaysGetCurrentDates() {
        val source = store()
        source.reconcile(history(2))
        val legacy = source.exportJson().apply {
            put("version", 1); remove("qualifyingDays"); remove("bestStreak")
            put("earned", JSONObject()); put("seen", JSONArray())
            getJSONObject("defaultAppearance").apply { remove("edgeSet"); remove("sealSet") }
        }
        val file = File(folder.newFolder(), "relics.json").apply { writeText(legacy.toString()) }
        val migrated = RelicStore(file, clock = { 42 })
        val reconciled = migrated.reconcile(history(6))
        assertEquals(0L, reconciled.earned["atlas_compass"])
        assertEquals(0L, reconciled.earned["hearth_spark"])
        val newDay = migrated.reconcile(history(7))
        assertEquals(42L, newDay.earned["atlas_lens"])
        assertEquals(42L, newDay.earned["hearth_candle"])
        assertEquals(7 * DAILY_GOAL_MILLIS, newDay.totalMillis)
    }
}
