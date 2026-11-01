package com.bookcover.diagnostic.rewards

import com.bookcover.diagnostic.streak.ReadingDay
import com.bookcover.diagnostic.streak.ReadingDays
import java.io.File
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RelicTestAccessTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = 1_793_500_000_000L
    private val bookId = "a".repeat(64)
    private val mythic = RelicAppearance(enabled = true, mode = RelicMode.STANDALONE,
        edgeTier = MoonlitTier.MYTHIC, seal = MoonlitTier.MYTHIC)

    private class MemoryPersistence : RelicTestPersistence {
        var value: String? = null
        var failWrites = false
        override fun read(): String? = value
        override fun write(value: String?): Boolean {
            if (failWrites) return false
            this.value = value
            return true
        }
    }

    private fun access(persistence: MemoryPersistence = MemoryPersistence(), supported: Boolean = true) =
        RelicTestAccess(supported, persistence, clock = { now })

    @Test fun allTiersAreTemporarilyUsableWithoutChangingReadingEarnedOrBackupBytes() {
        val file = File(folder.root, "relics.json")
        val store = RelicStore(file)
        val real = store.reconcile(ReadingDays(mapOf(LocalDate.of(2026, 11, 1) to ReadingDay(3_600_000, true))))
        val originalFile = file.readBytes()
        val originalBackup = File(folder.root, "relics.json.bak").readBytes()
        val originalExport = store.exportJson().toString()
        val testing = access()
        testing.begin(real)
        testing.saveAppearance(null, mythic)
        testing.saveAppearance(bookId, mythic.copy(inscription = "Test book"))
        assertEquals(MoonlitTier.entries.toSet(), testing.availableTiers(real))
        assertEquals(mythic, testing.appearanceFor(real, null))
        val editing = testing.editingCollection(real)
        assertEquals(real.earned, editing.earned)
        assertEquals(real.seen, editing.seen)
        assertEquals(real.totalMillis, editing.totalMillis)
        assertEquals(real, store.collection.value)
        assertEquals(originalExport, store.exportJson().toString())
        assertArrayEquals(originalFile, file.readBytes())
        assertArrayEquals(originalBackup, File(folder.root, "relics.json.bak").readBytes())
        testing.end()
        assertEquals(real, testing.editingCollection(real))
        assertEquals(real.appearanceFor(bookId), testing.appearanceFor(real, bookId))
        assertEquals(real.unlocked, testing.availableTiers(real))
    }

    @Test fun temporaryProfilesSurviveRestartButExpireAtExactlyTwentyFourHours() {
        val persistence = MemoryPersistence()
        val real = RelicCollection()
        val testing = access(persistence)
        testing.begin(real)
        testing.saveAppearance(null, mythic)
        val start = now
        val restored = access(persistence)
        assertEquals(mythic, restored.appearanceFor(real, null))
        now = start + RELIC_TEST_DURATION_MILLIS - 1
        assertTrue(restored.state.value.isActive(now))
        assertEquals(6, restored.availableTiers(real).size)
        now++
        restored.refresh()
        assertEquals(RelicTestingState(), restored.state.value)
        assertEquals(real.appearanceFor(null), restored.appearanceFor(real, null))
        assertNull(persistence.value)
        assertThrows(IllegalStateException::class.java) { restored.saveAppearance(null, mythic) }
        assertEquals(RelicTestingState(), access(persistence).state.value)
    }

    @Test fun endingTestingRestoresActualDefaultAndPerBookChoices() {
        val default = RelicAppearance(inscription = "Real default")
        val book = RelicAppearance(mode = RelicMode.OVERLAY, inscription = "Real book")
        val real = RelicCollection(defaultAppearance = default, bookAppearances = mapOf(bookId to book))
        val persistence = MemoryPersistence()
        val testing = access(persistence)
        testing.begin(real)
        testing.saveAppearance(null, mythic)
        testing.saveAppearance(bookId, mythic.copy(inscription = "Temporary book"))
        testing.resetBookAppearance(bookId)
        assertEquals(mythic, testing.appearanceFor(real, bookId))
        testing.end()
        assertEquals(default, testing.appearanceFor(real, null))
        assertEquals(book, testing.appearanceFor(real, bookId))
        assertNull(persistence.value)
    }

    @Test fun rewardsGenuinelyEarnedDuringTestingStillComeFromRealCollection() {
        val testing = access()
        testing.begin(RelicCollection())
        testing.saveAppearance(null, mythic)
        val newlyEarned = RelicCollection(totalMillis = 3_600_000, earned = mapOf("quill" to now, "lamp" to now))
        val editing = testing.editingCollection(newlyEarned)
        assertEquals(newlyEarned.earned, editing.earned)
        assertEquals(newlyEarned.totalMillis, editing.totalMillis)
        testing.end()
        assertEquals(newlyEarned, testing.editingCollection(newlyEarned))
        assertEquals(newlyEarned.unlocked, testing.availableTiers(newlyEarned))
    }

    @Test fun reopeningTestingDoesNotExtendItsWindowOrReplaceDraftProfiles() {
        val testing = access()
        testing.begin(RelicCollection())
        testing.saveAppearance(null, mythic)
        val expiry = testing.state.value.expiresAtMillis
        now += 3_600_000
        testing.begin(RelicCollection())
        assertEquals(expiry, testing.state.value.expiresAtMillis)
        assertEquals(mythic, testing.appearanceFor(RelicCollection(), null))
    }

    @Test fun unsupportedBuildAndInvalidOrOverlongSessionsCannotUnlockAnything() {
        val persistence = MemoryPersistence()
        access(persistence).begin(RelicCollection())
        val unsupported = access(persistence, supported = false)
        assertEquals(RelicTestingState(), unsupported.state.value)
        assertTrue(unsupported.availableTiers(RelicCollection()).isEmpty())
        assertThrows(IllegalStateException::class.java) { unsupported.begin(RelicCollection()) }
        persistence.value = JSONObject(persistence.value!!).put("expiresAtMillis", now + RELIC_TEST_DURATION_MILLIS + 1).toString()
        assertEquals(RelicTestingState(), access(persistence).state.value)
        persistence.value = "damaged test preferences"
        assertEquals(RelicTestingState(), access(persistence).state.value)
    }

    @Test fun clockRollbackAndExpiredFailedCleanupRemainFailClosed() {
        val persistence = MemoryPersistence()
        val testing = access(persistence)
        testing.begin(RelicCollection())
        now--
        assertTrue(testing.availableTiers(RelicCollection()).isEmpty())
        assertEquals(RelicTestingState(), access(persistence).state.value)
        now += 2
        testing.begin(RelicCollection())
        now += RELIC_TEST_DURATION_MILLIS
        persistence.failWrites = true
        testing.refresh()
        assertTrue(testing.availableTiers(RelicCollection()).isEmpty())
        assertEquals(RelicTestingState(), access(persistence).state.value)
    }

    @Test fun failedPreferenceCommitDoesNotApplyAnUnpersistedTestingAppearance() {
        val persistence = MemoryPersistence()
        val testing = access(persistence)
        testing.begin(RelicCollection())
        val previous = testing.state.value
        persistence.failWrites = true
        assertThrows(java.io.IOException::class.java) { testing.saveAppearance(null, mythic) }
        assertEquals(previous, testing.state.value)
        assertThrows(IllegalStateException::class.java) { testing.end() }
        assertEquals(previous, testing.state.value)
    }

    @Test fun existingTestingWindowMigratesWithoutExtensionAndNowIncludesAllEighteenRelics() {
        val persistence = MemoryPersistence()
        val testing = access(persistence)
        testing.begin(RelicCollection())
        testing.saveAppearance(null, mythic)
        val originalExpiry = testing.state.value.expiresAtMillis
        val legacy = JSONObject(persistence.value!!).put("version", 1)
        legacy.getJSONObject("defaultAppearance").apply {
            remove("edgeSet"); remove("sealSet"); put("bookplate", "PRESTIGE")
        }
        persistence.value = legacy.toString()
        now += 3_600_000
        val upgraded = access(persistence)
        assertTrue(upgraded.state.value.isActive(now))
        assertEquals(originalExpiry, upgraded.state.value.expiresAtMillis)
        assertEquals(18, upgraded.availableRelics(RelicCollection()).size)
        upgraded.begin(RelicCollection())
        assertEquals(originalExpiry, upgraded.state.value.expiresAtMillis)
        assertEquals(RelicSet.MOONLIT, upgraded.appearanceFor(RelicCollection(), null).edgeSet)
        assertEquals(RelicBookplate.NONE, upgraded.appearanceFor(RelicCollection(), null).bookplate)
    }

    @Test fun independentEdgeAndSealFamiliesPersistOnlyInsideTemporaryProfile() {
        val persistence = MemoryPersistence()
        val real = RelicCollection(qualifyingDays = 1, bestStreak = 1,
            earned = mapOf("atlas_ribbon" to now))
        val testing = access(persistence)
        testing.begin(real)
        val mixed = mythic.copy(edgeSet = RelicSet.WAYFARER, sealSet = RelicSet.HEARTHKEEPER)
        testing.saveAppearance(bookId, mixed)
        val restored = access(persistence)
        assertEquals(mixed, restored.appearanceFor(real, bookId))
        assertEquals(real.earned, restored.editingCollection(real).earned)
        assertEquals(real.qualifyingDays, restored.editingCollection(real).qualifyingDays)
        assertEquals(real.bestStreak, restored.editingCollection(real).bestStreak)
        restored.end()
        assertEquals(real.appearanceForRelics(bookId), restored.appearanceFor(real, bookId))
        assertEquals(real.unlockedRelics, restored.availableRelics(real))
    }
}
