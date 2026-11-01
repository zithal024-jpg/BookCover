package com.bookcover.diagnostic.rewards

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bookcover.diagnostic.library.BookLibrary
import com.bookcover.diagnostic.library.BookRecord
import com.bookcover.diagnostic.library.LibraryBackupException
import com.bookcover.diagnostic.library.LibraryIndex
import com.bookcover.diagnostic.library.ReadiumEngine
import com.bookcover.diagnostic.streak.ReadingDayStore
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Library export/restore with generated records only, inside disposable isolated-QA storage. */
@RunWith(AndroidJUnit4::class)
class MoonlitArchiveBackupTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private val firstDate = LocalDate.of(2026, 10, 20)
    private val hundredHours = 100 * 3_600_000L
    private val fixtureBook = BookRecord("a".repeat(64), "Moonlit backup fixture", "Generated test suite", 1,
        collection = "Fixture shelf", lastReadAt = 500, progression = 0.4,
        locator = "{\"href\":\"chapter-two.xhtml\"}", bookmarks = listOf("{\"href\":\"chapter-one.xhtml#saved\"}"))

    private inner class Fixture(name: String) {
        val directory = File(root, name).apply { check(mkdirs()) }
        val context: Context = object : ContextWrapper(target) { override fun getFilesDir(): File = directory }
        val libraryDirectory = File(directory, "library").apply { check(mkdirs()) }
        val index = File(libraryDirectory, "books.json")
        val history = File(libraryDirectory, "reading-days.json")
        val relicFile = File(libraryDirectory, "reading-relics.json")
        val relicBackup = File(libraryDirectory, "reading-relics.json.bak")
        fun library() = BookLibrary(context, ReadiumEngine(context))
        fun seedBook(book: BookRecord = fixtureBook) {
            LibraryIndex(libraryDirectory).apply { load(); save(listOf(book)) }
        }
        fun seedHundredHours() {
            // Five 20-hour dates stay below the history validator's daily bound, including on-device JSON.
            val days = JSONObject()
            repeat(5) { index -> days.put(firstDate.plusDays(index.toLong()).toString(),
                JSONObject().put("millis", 20 * 3_600_000L).put("completed", true)) }
            ReadingDayStore(history).mergeBackup(JSONObject().put("version", 1).put("days", days))
        }
        fun seedAllSets() {
            val days = JSONObject()
            val start = LocalDate.of(2025, 1, 1)
            repeat(365) { index -> days.put(start.plusDays(index.toLong()).toString(),
                JSONObject().put("millis", 3_600_000L).put("completed", true)) }
            ReadingDayStore(history).mergeBackup(JSONObject().put("version", 1).put("days", days))
        }
        fun protectedFiles(): Map<String, ByteArray> = listOf(index, history, relicFile, relicBackup)
            .associate { it.name to it.readBytes() }
        fun assertProtectedFiles(expected: Map<String, ByteArray>) {
            expected.forEach { (name, bytes) -> assertArrayEquals("$name must stay unchanged", bytes, File(libraryDirectory, name).readBytes()) }
        }
    }

    @Before fun setUp() {
        check(target.packageName == "com.bookcover.diagnostic.qa") { "Moonlit backup checks require the isolated QA app." }
        root = File(target.noBackupFilesDir, "moonlit-backup-${UUID.randomUUID()}").apply { check(mkdirs()) }
    }

    @After fun tearDown() {
        if (::root.isInitialized) {
            check(root.canonicalPath.startsWith(target.noBackupFilesDir.canonicalPath + File.separator))
            root.deleteRecursively()
        }
    }

    @Test fun hundredHoursRetroactivelyUnlockAllSixTiersAndRealBackupRestoresOptionsWithoutDoubleCredit() = runBlocking {
        val source = Fixture("source").apply { seedBook(); seedHundredHours() }
        val library = source.library().apply { load() }
        assertNull(library.relicError.value)
        assertEquals(hundredHours, library.relics.collection.value.totalMillis)
        assertEquals(MoonlitTier.entries.toSet(), library.relics.collection.value.unlocked)
        assertTrue("Existing reading is retroactive credit", library.relics.collection.value.earned.values.all { it == 0L })
        val default = RelicAppearance(enabled = true, colorMode = RelicColorMode.CUSTOM,
            customAccent = 0xff8ac4a7.toInt(), edgeTier = MoonlitTier.MYTHIC,
            seal = MoonlitTier.MYTHIC, motion = false, inscription = "Fixture archive")
        val perBook = RelicAppearance(enabled = true, mode = RelicMode.OVERLAY,
            edgeTier = MoonlitTier.EPIC, seal = MoonlitTier.RARE,
            colorMode = RelicColorMode.DARK, inscription = "Fixture edition")
        library.relics.setAppearance(null, default)
        library.relics.setAppearance(fixtureBook.id, perBook)
        library.relics.markSeen()
        val expected = library.relics.collection.value
        val expectedDays = library.readingDays.load()
        val backup = File(root, "generated-archive-backup.json")
        assertEquals(1, library.exportBackup(Uri.fromFile(backup)))
        val backupBytes = backup.readBytes()
        assertEquals(2, JSONObject(backup.readText()).getInt("version"))

        val destination = Fixture("destination").apply {
            seedBook(fixtureBook.copy(collection = "Books", lastReadAt = 0, progression = 0.0, locator = null, bookmarks = emptyList()))
        }
        val restored = destination.library().apply { load() }
        assertEquals(0L, restored.relics.collection.value.totalMillis)
        val result = restored.restoreBackup(Uri.fromFile(backup))
        assertEquals(1, result.restored)
        assertEquals(0, result.missing)
        assertEquals(fixtureBook, restored.book(fixtureBook.id))
        assertEquals(expected, restored.relics.collection.value)
        assertEquals(expectedDays, restored.readingDays.load())
        assertEquals(default, restored.relics.collection.value.defaultAppearance)
        assertEquals(perBook, restored.relics.collection.value.appearanceFor(fixtureBook.id))
        val durable = destination.protectedFiles()
        restored.restoreBackup(Uri.fromFile(backup))
        destination.assertProtectedFiles(durable)
        assertEquals(hundredHours, restored.relics.collection.value.totalMillis)
        val reopened = destination.library().apply { load() }
        assertEquals(expected, reopened.relics.collection.value)
        assertEquals(expectedDays, reopened.readingDays.load())
        assertArrayEquals("Reading a backup never modifies its source", backupBytes, backup.readBytes())
    }

    @Test fun missingHistoryIsRecoveredFromRewardFloorAndNewSameDateReadingCountsImmediately() = runBlocking {
        val fixture = Fixture("history-recovery").apply { seedBook(); seedHundredHours() }
        val original = fixture.library().apply { load() }
        assertEquals(hundredHours, original.relics.collection.value.totalMillis)
        assertTrue("Only the generated QA history is removed", fixture.history.delete())
        val reopened = fixture.library().apply { load() }
        assertNull(reopened.relicError.value)
        assertEquals(hundredHours, reopened.relics.collection.value.totalMillis)
        assertEquals(20 * 3_600_000L, reopened.readingDays.load().day(firstDate).millis)
        val noon = firstDate.atTime(12, 0).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        reopened.readingDays.add(noon, noon + 60_000L, ZoneOffset.UTC)
        reopened.reconcileRelics(reopened.readingDays.load())
        assertEquals(hundredHours + 60_000L, reopened.relics.collection.value.totalMillis)
        assertEquals(hundredHours + 60_000L, fixture.library().apply { load() }.relics.collection.value.totalMillis)
    }

    @Test fun allEighteenRelicsAndMixedFamilyEdgesSurviveRealBackupWithoutRestoringBookplates() = runBlocking {
        val source = Fixture("all-sets-source").apply { seedBook(); seedAllSets() }
        val library = source.library().apply { load() }
        val collection = library.relics.collection.value
        assertEquals(RelicCatalog.all.toSet(), collection.unlockedRelics)
        assertEquals(365, collection.qualifyingDays)
        assertEquals(365, collection.bestStreak)
        assertTrue(collection.earned.values.all { it == 0L })
        val mixed = RelicAppearance(enabled = true, edgeTier = MoonlitTier.MYTHIC,
            edgeSet = RelicSet.WAYFARER, seal = MoonlitTier.LEGENDARY, sealSet = RelicSet.HEARTHKEEPER)
        val hearth = RelicAppearance(enabled = true, mode = RelicMode.STANDALONE,
            edgeTier = MoonlitTier.MYTHIC, edgeSet = RelicSet.HEARTHKEEPER,
            seal = MoonlitTier.RARE, sealSet = RelicSet.WAYFARER)
        // Legacy Bookplate preferences are accepted for migration and then ignored.
        library.relics.setAppearance(null, mixed.copy(bookplate = RelicBookplate.PRESTIGE))
        library.relics.setAppearance(fixtureBook.id, hearth.copy(bookplate = RelicBookplate.STANDARD))
        assertEquals(mixed, library.relics.collection.value.defaultAppearance)
        assertEquals(hearth, library.relics.collection.value.appearanceForRelics(fixtureBook.id))
        val expected = library.relics.collection.value
        val expectedDays = library.readingDays.load()
        val backup = File(root, "all-sets-backup.json")
        library.exportBackup(Uri.fromFile(backup))
        val destination = Fixture("all-sets-destination").apply { seedBook() }
        val restored = destination.library().apply { load() }
        restored.restoreBackup(Uri.fromFile(backup))
        assertEquals(expected, restored.relics.collection.value)
        assertEquals(expectedDays, restored.readingDays.load())
        assertEquals(mixed, restored.relics.collection.value.appearanceForRelics(null))
        assertEquals(hearth, restored.relics.collection.value.appearanceForRelics(fixtureBook.id))
        val durable = destination.protectedFiles()
        restored.restoreBackup(Uri.fromFile(backup))
        destination.assertProtectedFiles(durable)
        assertEquals(expected, destination.library().apply { load() }.relics.collection.value)
    }

    @Test fun malformedRewardOrHistoryExtrasCannotChangeIndexHistoryOrRelics() = runBlocking {
        val fixture = Fixture("malformed").apply { seedBook(); seedHundredHours() }
        val library = fixture.library().apply { load() }
        val backup = File(root, "generated-malformed-backup.json")
        library.exportBackup(Uri.fromFile(backup))
        val valid = backup.readText()
        val expectedFiles = fixture.protectedFiles()
        val expectedRelics = library.relics.collection.value
        val expectedHistory = library.readingDays.load()
        for (field in listOf("relics", "readingDays")) {
            val json = JSONObject(valid)
            // A valid metadata change makes an accidental partial merge observable.
            json.getJSONArray("books").getJSONObject(0).put("collection", "Must never apply")
            if (field == "relics") json.getJSONObject("relics").put("totalMillis", -1)
            else json.getJSONObject("readingDays").getJSONObject("days").put("bad-date",
                JSONObject().put("millis", 1).put("completed", false))
            backup.writeText(json.toString())
            assertThrows(LibraryBackupException::class.java) { runBlocking { library.restoreBackup(Uri.fromFile(backup)) } }
            fixture.assertProtectedFiles(expectedFiles)
            assertEquals(expectedRelics, library.relics.collection.value)
            assertEquals(expectedHistory, library.readingDays.load())
            assertEquals(fixtureBook, library.book(fixtureBook.id))
        }
    }

    @Test fun unreadableRewardCopiesRefuseExportBeforeExistingDestinationIsOpened() = runBlocking {
        val fixture = Fixture("damaged-rewards").apply { seedBook(); seedHundredHours() }
        fixture.library().load()
        fixture.relicFile.writeText("{ damaged generated relic primary")
        fixture.relicBackup.writeText("{ damaged generated relic backup")
        val destination = File(root, "existing-backup.json").apply { writeText("Existing generated backup bytes must remain") }
        val before = destination.readBytes()
        val protectedBefore = fixture.protectedFiles()
        val library = fixture.library().apply { load() }
        assertNotNull("Reward failure is reported without hiding books", library.relicError.value)
        assertEquals(listOf(fixtureBook), library.books.value)
        assertThrows(RelicStoreException::class.java) { runBlocking { library.exportBackup(Uri.fromFile(destination)) } }
        assertArrayEquals(before, destination.readBytes())
        fixture.assertProtectedFiles(protectedBefore)
    }
}
