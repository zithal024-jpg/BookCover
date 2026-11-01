package com.bookcover.diagnostic.rewards

import com.bookcover.diagnostic.streak.DAILY_GOAL_MILLIS
import com.bookcover.diagnostic.streak.ReadingDay
import com.bookcover.diagnostic.streak.ReadingDays
import com.bookcover.diagnostic.streak.ReadingDayStore
import java.io.File
import java.time.LocalDate
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RelicStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val file get() = File(folder.root, "relics.json")
    private val backup get() = File(folder.root, "relics.json.bak")
    private val date = LocalDate.of(2026, 11, 1)
    private val bookId = "a".repeat(64)
    private fun store() = RelicStore(file, clock = { 42 })
    private fun days(millis: Long, date: LocalDate = this.date) = ReadingDays(mapOf(date to ReadingDay(millis, millis >= DAILY_GOAL_MILLIS)))
    private fun hours(value: Int): ReadingDays = ReadingDays((0 until (value + 23) / 24).associate { index ->
        date.plusDays(index.toLong()) to ReadingDay(minOf(24, value - index * 24) * 3_600_000L, true)
    })

    @Test fun missingFileStartsEmptyWithoutWriting() {
        assertEquals(RelicCollection(), store().load())
        assertFalse(file.exists())
        assertFalse(backup.exists())
    }

    @Test fun exactThresholdsGrantEachTierAndOneMillisecondBeforeDoesNot() {
        for (tier in MoonlitTier.entries) {
            val target = File(folder.newFolder(), "relics.json")
            val relics = RelicStore(target)
            // Split longer milestones over plausible dates rather than inventing 100-hour days.
            val fullHours = (tier.thresholdMillis / 3_600_000L).toInt()
            val reading = if (fullHours > 0) hours(fullHours) else days(tier.thresholdMillis)
            val lastDate = reading.days.keys.max()
            val before = reading.copy(days = reading.days + (lastDate to reading.days.getValue(lastDate).copy(
                millis = reading.days.getValue(lastDate).millis - 1)))
            assertFalse(tier in relics.reconcile(before).unlocked)
            assertTrue(tier in relics.reconcile(reading).unlocked)
        }
    }

    @Test fun previousReadingUnlocksRetroactivelyAtZeroAndNewReadingGetsTimestamp() {
        val relics = store()
        assertEquals(0L, relics.reconcile(days(3_600_000)).earned["lamp"])
        relics.reconcile(hours(5))
        assertEquals(42L, relics.collection.value.earned["key"])
    }

    @Test fun repeatedReconcileAndSmallerHistoryNeverRemoveOrDuplicateCredit() {
        val relics = store()
        val original = relics.reconcile(hours(5))
        assertEquals(original, relics.reconcile(hours(5)))
        assertEquals(original, relics.reconcile(ReadingDays()))
        assertEquals(original, relics.reconcile(days(10_000)))
        assertEquals(original, RelicStore(file).load())
    }

    @Test fun restoredDatedFloorAllowsNewReadingImmediatelyAfterHistoryLoss() {
        val relics = store()
        relics.reconcile(days(3_600_000))
        val history = ReadingDayStore(File(folder.root, "reading-days.json"))
        history.mergeBackup(relics.readingHistoryFloorJson())
        val noon = date.atTime(12, 0).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        history.add(noon, noon + 60_000, java.time.ZoneOffset.UTC)
        assertEquals(3_660_000L, relics.reconcile(history.days.value).totalMillis)
    }

    @Test fun unseenStatusAndAppearanceSurviveRestart() {
        val relics = store()
        relics.reconcile(hours(100))
        assertEquals(6, relics.collection.value.unseen.size)
        relics.markSeen()
        relics.setAppearance(null, RelicAppearance(enabled = true, edgeTier = MoonlitTier.MYTHIC,
            seal = MoonlitTier.EPIC, bookplate = RelicBookplate.PRESTIGE, motion = false))
        relics.setAppearance(bookId, RelicAppearance(enabled = true, mode = RelicMode.STANDALONE,
            seal = MoonlitTier.LEGENDARY, inscription = "Moonlit shelf"))
        val reread = store().load()
        assertEquals(relics.collection.value, reread)
        assertTrue(reread.unseen.isEmpty())
        assertEquals(RelicMode.STANDALONE, reread.appearanceFor(bookId).mode)
    }

    @Test fun appearanceCannotEquipLockedTiersAndResetReturnsToDefault() {
        val relics = store()
        relics.reconcile(days(3_600_000))
        relics.setAppearance(null, RelicAppearance(enabled = true, edgeTier = MoonlitTier.MYTHIC,
            bookplate = RelicBookplate.PRESTIGE, seal = MoonlitTier.RARE))
        val appearance = relics.collection.value.defaultAppearance
        assertNull(appearance.edgeTier)
        assertEquals(RelicBookplate.NONE, appearance.bookplate)
        assertNull(appearance.seal)
        relics.setAppearance(bookId, RelicAppearance(mode = RelicMode.OVERLAY))
        relics.resetBookAppearance(bookId)
        assertEquals(appearance, relics.collection.value.appearanceFor(bookId))
    }

    @Test fun inscriptionsAreSanitizedAndBookIdentifiersValidatedBeforeSaving() {
        val relics = store()
        relics.setAppearance(null, RelicAppearance(inscription = "shelf\n\u202e" + "x".repeat(100)))
        val text = relics.collection.value.defaultAppearance.inscription
        assertEquals(80, text.length)
        assertFalse(text.contains('\n'))
        assertFalse(text.contains('\u202e'))
        val before = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { relics.setAppearance("../cover", RelicAppearance()) }
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun backupRoundTripAndRepeatedMergeDoNotDoubleTimeOrRewards() {
        val source = store()
        source.reconcile(hours(15))
        source.markSeen()
        source.setAppearance(null, RelicAppearance(enabled = true, seal = MoonlitTier.EPIC))
        val target = RelicStore(File(folder.newFolder(), "relics.json"))
        val restored = target.mergeBackup(source.exportJson())
        assertEquals(source.collection.value, restored)
        assertEquals(restored, target.mergeBackup(source.exportJson()))
        assertEquals(restored, target.reconcile(hours(15)))
    }

    @Test fun backupMergeUsesDateMaxAndAddsDisjointHistory() {
        val local = store()
        local.reconcile(days(3_600_000))
        val incoming = RelicStore(File(folder.newFolder(), "relics.json"))
        incoming.reconcile(ReadingDays(mapOf(date to ReadingDay(1_800_000, true), date.plusDays(1) to ReadingDay(7_200_000, true))))
        val merged = local.mergeBackup(incoming.exportJson())
        assertEquals(10_800_000L, merged.totalMillis)
        assertEquals(merged, local.mergeBackup(incoming.exportJson()))
    }

    @Test fun backupDoesNotReplaceLocalExplicitAppearanceButFillsMissingBooks() {
        val local = store()
        local.reconcile(hours(5))
        local.setAppearance(null, RelicAppearance(mode = RelicMode.STANDALONE))
        local.setAppearance(bookId, RelicAppearance(inscription = "Local"))
        val incoming = RelicStore(File(folder.newFolder(), "relics.json"))
        incoming.reconcile(hours(15))
        incoming.setAppearance(null, RelicAppearance(mode = RelicMode.OVERLAY))
        incoming.setAppearance(bookId, RelicAppearance(inscription = "Incoming"))
        incoming.setAppearance("b".repeat(64), RelicAppearance(inscription = "New"))
        val merged = local.mergeBackup(incoming.exportJson())
        assertEquals(RelicMode.STANDALONE, merged.defaultAppearance.mode)
        assertEquals("Local", merged.bookAppearances.getValue(bookId).inscription)
        assertEquals("New", merged.bookAppearances.getValue("b".repeat(64)).inscription)
    }

    @Test fun corruptPrimaryRecoversMirrorAndPreservesOriginalBeforeSaving() {
        val original = store().reconcile(hours(5))
        file.writeText("{corrupt primary")
        val recovered = store()
        assertEquals(original, recovered.load())
        assertEquals("{corrupt primary", file.readText())
        recovered.markSeen()
        assertTrue(folder.root.listFiles()!!.any { it.name.startsWith("relics.json.damaged-") && it.readText() == "{corrupt primary" })
        assertEquals(recovered.collection.value, store().load())
    }

    @Test fun bothCorruptCopiesFailClosedAndEveryMutationRefuses() {
        file.writeText("corrupt primary")
        backup.writeText("corrupt mirror")
        val relics = store()
        assertThrows(RelicStoreException::class.java) { relics.load() }
        assertThrows(RelicStoreException::class.java) { relics.reconcile(hours(100)) }
        assertThrows(RelicStoreException::class.java) { relics.setAppearance(null, RelicAppearance()) }
        assertThrows(RelicStoreException::class.java) { relics.exportJson() }
        assertEquals("corrupt primary", file.readText())
        assertEquals("corrupt mirror", backup.readText())
    }

    @Test fun laterCompleteMirrorRecoversInterruptedPrimaryReplacement() {
        val source = store()
        source.reconcile(days(300_000))
        val older = file.readBytes()
        source.reconcile(hours(5))
        file.writeBytes(older) // Backup was committed but primary still holds its prior version.
        assertEquals(source.collection.value, store().load())
    }

    @Test fun outsideFileChangesAfterLoadRefuseSaveWithoutOverwritingThem() {
        val relics = store()
        relics.reconcile(hours(5))
        val before = relics.collection.value
        file.writeText("external modification")
        assertThrows(RelicStoreException::class.java) { relics.markSeen() }
        assertEquals(before, relics.collection.value)
        assertEquals("external modification", file.readText())
    }

    @Test fun invalidBackupIsRejectedBeforeAnyLocalMutation() {
        val relics = store()
        relics.reconcile(hours(5))
        val before = file.readBytes()
        val bad = relics.exportJson().put("totalMillis", "18000000")
        assertThrows(IllegalArgumentException::class.java) { relics.mergeBackup(bad) }
        assertArrayEquals(before, file.readBytes())
        val negative = relics.exportJson().put("totalMillis", -1)
        assertThrows(IllegalArgumentException::class.java) { RelicStore.validateBackup(negative) }
        val unearned = store().exportJson().put("earned", JSONObject().put("archive", 0)).put("totalMillis", 300_000)
        assertThrows(IllegalArgumentException::class.java) { RelicStore.validateBackup(unearned) }
    }

    @Test fun nearMaximumLifetimeTimeSaturatesWithoutOverflow() {
        val relics = store()
        val json = relics.exportJson().put("totalMillis", Long.MAX_VALUE - 10)
        relics.mergeBackup(json)
        assertEquals(Long.MAX_VALUE, relics.reconcile(days(100)).totalMillis)
        assertEquals(6, relics.collection.value.unlocked.size)
    }

    @Test fun negativeOrOverlongDatedCreditCannotGrantRewards() {
        val relics = store()
        assertThrows(IllegalArgumentException::class.java) { relics.reconcile(days(-1)) }
        assertThrows(IllegalArgumentException::class.java) { relics.reconcile(days(ReadingDayStore.MAX_DAY_MILLIS + 1)) }
        assertFalse(file.exists())
    }

    @Test fun explicitResetSurvivesRestartAndCannotBeResurrectedByAnOlderBackup() {
        val relics = store()
        val inherited = RelicAppearance(inscription = "Inherited default")
        relics.setAppearance(null, inherited)
        relics.setAppearance(bookId, RelicAppearance(inscription = "Old book override"))
        val older = relics.exportJson()
        relics.resetBookAppearance(bookId)
        val reopened = store()
        val merged = reopened.mergeBackup(older)
        assertFalse(bookId in merged.bookAppearances)
        assertEquals(inherited, merged.appearanceFor(bookId))
        assertEquals(merged, store().mergeBackup(older))
    }

    @Test fun resettingAnAlreadyInheritedBookStillRecordsAnExplicitChoice() {
        val local = store()
        local.resetBookAppearance(bookId)
        val source = RelicStore(File(folder.newFolder(), "relics.json"))
        source.setAppearance(bookId, RelicAppearance(inscription = "Restored override"))
        assertFalse(bookId in local.mergeBackup(source.exportJson()).bookAppearances)
        assertFalse(bookId in store().load().bookAppearances)
    }

    @Test fun backupRestoresInheritanceChoiceIntoFreshStoreAndKeepsItOnLaterOlderRestore() {
        val source = store()
        source.setAppearance(bookId, RelicAppearance(inscription = "Old choice"))
        val oldBackup = source.exportJson()
        source.resetBookAppearance(bookId)
        val freshFile = File(folder.newFolder(), "relics.json")
        val fresh = RelicStore(freshFile)
        fresh.mergeBackup(source.exportJson())
        assertFalse(bookId in RelicStore(freshFile).mergeBackup(oldBackup).bookAppearances)
    }

    @Test fun localOverrideWinsOverIncomingInheritanceAndSettingOverrideClearsPriorReset() {
        val local = store()
        local.resetBookAppearance(bookId)
        val localChoice = RelicAppearance(inscription = "Current local override")
        local.setAppearance(bookId, localChoice)
        assertEquals(0, local.exportJson().getJSONArray("inheritedBooks").length())
        val incoming = RelicStore(File(folder.newFolder(), "relics.json"))
        incoming.resetBookAppearance(bookId)
        assertEquals(localChoice, local.mergeBackup(incoming.exportJson()).bookAppearances[bookId])
        assertEquals(localChoice, store().load().bookAppearances[bookId])
    }

    @Test fun schemaOneBackupsWithoutInheritanceFieldRemainCompatible() {
        val source = store()
        source.setAppearance(bookId, RelicAppearance(inscription = "Legacy override"))
        val legacy = source.exportJson().apply { remove("inheritedBooks") }
        RelicStore.validateBackup(legacy)
        val fresh = RelicStore(File(folder.newFolder(), "relics.json"))
        assertEquals("Legacy override", fresh.mergeBackup(legacy).bookAppearances.getValue(bookId).inscription)
    }

    @Test fun malformedOrConflictingInheritanceEntriesRejectTheEntireBackupBeforeMutation() {
        val local = store()
        local.setAppearance(bookId, RelicAppearance(inscription = "Keep local"))
        val valid = local.exportJson().toString()
        val before = file.readBytes()
        val invalidArrays = listOf(JSONArray().put("../invalid"), JSONArray().put(42),
            JSONArray().put(bookId)) // The same book cannot have both an override and inheritance.
        for (invalid in invalidArrays) {
            val incoming = JSONObject(valid).put("inheritedBooks", invalid)
            assertThrows(IllegalArgumentException::class.java) { local.mergeBackup(incoming) }
            assertArrayEquals(before, file.readBytes())
        }
        assertThrows(Exception::class.java) { RelicStore.validateBackup(JSONObject(valid).put("inheritedBooks", JSONObject.NULL)) }
    }
}
