package com.bookcover.diagnostic.library

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** F2: ownership evidence, rather than absence from the current list, permits asset cleanup. */
class ImportFinalizationTest {
    @get:Rule val folder = TemporaryFolder()

    private val existing = BookRecord("a".repeat(64), "Existing", "Author", 1,
        lastReadAt = 42, progression = 0.37, locator = "saved passage", bookmarks = listOf("saved bookmark"))
    private val incoming = BookRecord("b".repeat(64), "Incoming", "Author", 2)
    private val directory get() = folder.root
    private val primary get() = File(directory, "books.json")
    private val marker get() = File(directory, "pending-import-${incoming.id}")

    private fun assets(root: File = directory, id: String = incoming.id): List<File> =
        listOf("$id.epub", "$id.png", "$id.thumb.png").map { File(root, it) }

    private fun writeAssets(root: File = directory, id: String = incoming.id) {
        assets(root, id).forEachIndexed { position, file -> file.writeText("asset $position for $id") }
    }

    private fun indexJson(vararg books: Any): String =
        JSONObject().put("version", 1).put("books", JSONArray().apply { books.forEach { put(it) } }).toString()

    private fun receipt(root: File = directory) = File(root, marker.name).apply {
        writeText("BookCover import 1\n${incoming.id}\n")
    }

    private fun seedExisting(): LibraryIndex = LibraryIndex(directory).apply {
        load(); save(listOf(existing)); writeAssets(id = existing.id)
    }

    private fun assertIncomingAbsent(root: File = directory) {
        assets(root).forEach { assertFalse("Failed import left ${it.name}", it.exists()) }
    }

    private fun assertIncomingPresent(root: File = directory) {
        assets(root).forEach { assertTrue("Recoverable asset removed: ${it.name}", it.isFile) }
    }

    @Test fun persistenceFailureRemovesOwnedAssetsAndPreservesExistingDataByteForByte() {
        seedExisting()
        val protected = (listOf(primary) + assets(id = existing.id)).associateWith { it.readBytes() }
        val failure = IOException("Injected index persistence failure")

        val thrown = assertThrows(IOException::class.java) {
            ImportFinalization(directory).commit(incoming.id, { writeAssets() }) { throw failure }
        }

        assertSame(failure, thrown)
        assertIncomingAbsent()
        assertFalse(marker.exists())
        assertEquals(0, ImportFinalization(directory).recover())
        protected.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
        assertEquals("Position and bookmarks survive reload", listOf(existing), LibraryIndex(directory).load().records)
    }

    @Test fun writeFailureAtEveryAssetBoundaryNeverPersistsOrLeavesOwnedFiles() {
        for (written in 0..3) {
            val root = folder.newFolder("write-failure-$written")
            var persisted = false
            assertThrows(IOException::class.java) {
                ImportFinalization(root).commit(incoming.id, {
                    assets(root).take(written).forEach { it.writeText("partial") }
                    throw IOException("Asset write failed")
                }) { persisted = true }
            }
            assertFalse("No index may commit after $written asset writes", persisted)
            assertIncomingAbsent(root)
            assertEquals(0, ImportFinalization(root).recover())
        }
    }

    @Test fun cancellationDuringAssetWriteCleansOnlyThatOperation() {
        seedExisting()
        val stopped = CancellationException("Stopped during finalization")
        var persisted = false
        val thrown = assertThrows(CancellationException::class.java) {
            ImportFinalization(directory).commit(incoming.id, {
                assets().first().writeText("partial")
                throw stopped
            }) { persisted = true }
        }
        assertSame(stopped, thrown)
        assertFalse(persisted)
        assertIncomingAbsent()
        assertEquals(listOf(existing), LibraryIndex(directory).load().records)
        assets(id = existing.id).forEach { assertTrue(it.isFile) }
    }

    @Test fun cancellationBeforePersistenceCleansCompletedButUncommittedAssets() {
        seedExisting()
        assertThrows(CancellationException::class.java) {
            ImportFinalization(directory).commit(incoming.id, { writeAssets() }) {
                throw CancellationException("Stopped before save")
            }
        }
        assertIncomingAbsent()
        assertEquals(listOf(existing), LibraryIndex(directory).load().records)
    }

    @Test fun simulatedDeathBetweenAssetsLeavesReceiptAndRecoveryRemovesOnlyCreatedFiles() {
        seedExisting()
        val before = primary.readBytes()
        assertThrows(SimulatedProcessDeath::class.java) {
            ImportFinalization(directory).commit(incoming.id, {
                assets().take(2).forEach { it.writeText("completed before interruption") }
                throw SimulatedProcessDeath()
            }) { fail("Interrupted asset writing cannot persist") }
        }
        assertTrue("Error leaves durable restart evidence", marker.isFile)
        assertEquals(2, ImportFinalization(directory).recover())
        assertIncomingAbsent()
        assertFalse(marker.exists())
        assertEquals(0, ImportFinalization(directory).recover())
        assertArrayEquals(before, primary.readBytes())
        assertEquals(listOf(existing), LibraryIndex(directory).load().records)
        assets(id = existing.id).forEach { assertTrue(it.isFile) }
    }

    @Test fun simulatedDeathImmediatelyBeforeSaveIsRecoveredOnRestart() {
        seedExisting()
        assertThrows(SimulatedProcessDeath::class.java) {
            ImportFinalization(directory).commit(incoming.id, { writeAssets() }) { throw SimulatedProcessDeath() }
        }
        assertIncomingPresent()
        assertTrue(marker.isFile)
        assertEquals(3, ImportFinalization(directory).recover())
        assertIncomingAbsent()
        assertEquals(listOf(existing), LibraryIndex(directory).load().records)
    }

    @Test fun simulatedDeathAfterSuccessfulSaveNeverDeletesTheCommittedBook() {
        val index = seedExisting()
        assertThrows(SimulatedProcessDeath::class.java) {
            ImportFinalization(directory).commit(incoming.id, { writeAssets() }) {
                index.save(listOf(existing, incoming))
                throw SimulatedProcessDeath()
            }
        }
        assertTrue("Interrupted completion leaves a receipt", marker.isFile)
        val before = primary.readBytes()
        assertEquals(0, ImportFinalization(directory).recover())
        assertIncomingPresent()
        assertArrayEquals(before, primary.readBytes())
        assertEquals(listOf(existing, incoming), LibraryIndex(directory).load().records)
    }

    @Test fun anExceptionAfterSaveAlsoPreservesCommittedFiles() {
        val index = seedExisting()
        assertThrows(IOException::class.java) {
            ImportFinalization(directory).commit(incoming.id, { writeAssets() }) {
                index.save(listOf(existing, incoming))
                throw IOException("Failure after the index committed")
            }
        }
        assertEquals(0, ImportFinalization(directory).recover())
        assertIncomingPresent()
        assertEquals(listOf(existing, incoming), LibraryIndex(directory).load().records)
    }

    @Test fun successReturnsTheResultAndLeavesNoPendingReceipt() {
        val index = seedExisting()
        val result = ImportFinalization(directory).commit(incoming.id, {
            assertTrue("Receipt must precede the first final file", marker.isFile)
            writeAssets()
        }) {
            assertIncomingPresent()
            index.save(listOf(existing, incoming))
            "saved"
        }
        assertEquals("saved", result)
        assertFalse(marker.exists())
        assertEquals(0, ImportFinalization(directory).recover())
        assertIncomingPresent()
        assertEquals(listOf(existing, incoming), LibraryIndex(directory).load().records)
    }

    @Test fun everyRecoveryIndexCandidateProtectsReferencedAssets() {
        val candidates = listOf("books.json", "books.json.bak", "books.last-good.json").flatMap { name ->
            listOf(name, "$name.tmp", "$name.new")
        } + "books.damaged-123456.json"
        candidates.forEachIndexed { position, name ->
            val root = folder.newFolder("candidate-$position")
            receipt(root); writeAssets(root)
            val evidence = File(root, name).apply { writeText(indexJson(incoming.toJson())) }
            val before = evidence.readBytes()
            assertEquals("Reference in $name must protect its files", 0, ImportFinalization(root).recover())
            assertIncomingPresent(root)
            assertArrayEquals(before, evidence.readBytes())
        }
    }

    @Test fun rawIdProtectsARecordWhoseOtherMetadataCannotBeLoaded() {
        val damagedRecord = incoming.toJson().put("bookmarks", JSONArray().put(JSONObject().put("invalid", true)))
        primary.writeText(indexJson(damagedRecord))
        assertTrue("The normal loader skips this record", LibraryIndex(directory).load().records.isEmpty())
        receipt(); writeAssets()
        val before = primary.readBytes()
        assertEquals(0, ImportFinalization(directory).recover())
        assertIncomingPresent()
        assertArrayEquals(before, primary.readBytes())
    }

    @Test fun unreadableOrAmbiguousIndexEvidenceDefersCleanup() {
        val ambiguous = listOf("{", "{}", "{\"books\":{}}", "{\"books\":[\"unknown record\"]}",
            "{\"books\":[{\"id\":17}]}")
        ambiguous.forEachIndexed { position, text ->
            val root = folder.newFolder("ambiguous-$position")
            File(root, "books.json").writeText(indexJson(existing.toJson()))
            val evidence = File(root, "books.last-good.json").apply { writeText(text) }
            receipt(root); writeAssets(root)
            assertEquals(0, ImportFinalization(root).recover())
            assertIncomingPresent(root)
            assertEquals(text, evidence.readText())
        }
    }

    @Test fun directoryThatBlockedPersistenceIsPreservedButCannotReferenceABook() {
        primary.writeText(indexJson(existing.toJson()))
        File(directory, "books.json.tmp").mkdir()
        receipt(); writeAssets()
        assertEquals(3, ImportFinalization(directory).recover())
        assertIncomingAbsent()
        assertTrue(File(directory, "books.json.tmp").isDirectory)
    }

    @Test fun preexistingTargetsAndMarkersAreRefusedBeforeAnyWrite() {
        for ((position, name) in (assets().map { it.name } + marker.name).withIndex()) {
            val root = folder.newFolder("preexisting-$position")
            val prior = File(root, name).apply { writeText("existing bytes") }
            var wrote = false
            var persisted = false
            assertThrows(Exception::class.java) {
                ImportFinalization(root).commit(incoming.id, { wrote = true }) { persisted = true }
            }
            assertFalse("Existing $name must be detected before writes", wrote)
            assertFalse(persisted)
            assertEquals("existing bytes", prior.readText())
            assertEquals(listOf(name), root.list().orEmpty().toList())
        }
    }

    @Test fun unmarkedLegacyAssetsAndInvalidReceiptsNeverPermitDeletion() {
        writeAssets()
        val unrelated = File(directory, "other-book.epub").apply { writeText("unrelated") }
        assertEquals(0, ImportFinalization(directory).recover())
        assertIncomingPresent()
        for (text in listOf("", "BookCover import 1\n${existing.id}\n", "BookCover import 2\n${incoming.id}\n")) {
            marker.writeText(text)
            assertEquals(0, ImportFinalization(directory).recover())
            assertIncomingPresent()
            assertEquals(text, marker.readText())
        }
        assertEquals("unrelated", unrelated.readText())
    }

    @Test fun recoveryFromAnotherInstanceSkipsAnOperationUntilItFinishes() {
        val index = seedExisting()
        val other = ImportFinalization(directory)
        ImportFinalization(directory).commit(incoming.id, {
            writeAssets()
            assertEquals("An active operation is not abandoned", 0, other.recover())
            assertIncomingPresent()
            assertTrue(marker.isFile)
            var wroteAgain = false
            assertThrows(Exception::class.java) {
                other.commit(incoming.id, { wroteAgain = true }) { fail("Overlapping claim cannot persist") }
            }
            assertFalse(wroteAgain)
        }) {
            assertEquals("Also protected while persistence is running", 0, other.recover())
            index.save(listOf(existing, incoming))
        }
        assertIncomingPresent()
        assertFalse(marker.exists())
        assertEquals(0, other.recover())
        assertEquals(listOf(existing, incoming), LibraryIndex(directory).load().records)
    }

    @Test fun recoveryThroughAnAliasOfTheSameDirectoryAlsoPreservesActiveAssets() {
        val index = seedExisting()
        val aliased = ImportFinalization(File(directory, "."))
        ImportFinalization(directory).commit(incoming.id, {
            writeAssets()
            assertEquals("Equivalent paths identify the same active import", 0, aliased.recover())
            assertIncomingPresent()
            assertTrue(marker.isFile)
        }) {
            assertEquals(0, aliased.recover())
            index.save(listOf(existing, incoming))
        }
        assertIncomingPresent()
        assertFalse(marker.exists())
        assertEquals(listOf(existing, incoming), LibraryIndex(directory).load().records)
    }

    @Test fun incompleteAssetsCannotCommitEvenWhenTheWriterReturnsNormally() {
        for (position in 0..2) for (empty in listOf(false, true)) {
            val root = folder.newFolder("incomplete-$position-$empty")
            var persisted = false
            assertThrows(IOException::class.java) {
                ImportFinalization(root).commit(incoming.id, {
                    writeAssets(root)
                    val incomplete = assets(root)[position]
                    if (empty) incomplete.writeText("") else assertTrue(incomplete.delete())
                }) { persisted = true }
            }
            assertFalse("Missing or empty asset $position cannot be indexed", persisted)
            assertIncomingAbsent(root)
            assertFalse(File(root, marker.name).exists())
            assertEquals(0, ImportFinalization(root).recover())
        }
    }

    @Test fun activeImportPreventsAStaleThumbnailWriterFromCreatingAnUnexpectedTarget() {
        val index = seedExisting()
        val other = ImportFinalization(directory)
        val thumbnail = assets().last()
        var staleWriterRan = false
        ImportFinalization(directory).commit(incoming.id, {
            assertFalse(other.writeExistingAsset(incoming.id) {
                staleWriterRan = true
                thumbnail.writeText("stale thumbnail")
            })
            assertFalse(staleWriterRan)
            assertFalse("The import still exclusively owns the absent target", thumbnail.exists())
            writeAssets()
        }) { index.save(listOf(existing, incoming)) }

        assertIncomingPresent()
        assertEquals("asset 2 for ${incoming.id}", thumbnail.readText())
        assertTrue("Normal thumbnail writes resume after commit", other.writeExistingAsset(incoming.id) {
            thumbnail.writeText("updated committed thumbnail")
        })
        assertEquals("updated committed thumbnail", thumbnail.readText())
        assertEquals(listOf(existing, incoming), LibraryIndex(directory).load().records)
    }

    @Test fun aThumbnailWriterFinishingBeforeImportMakesTheImportRefuseExistingBytes() {
        val finalization = ImportFinalization(directory)
        val thumbnail = assets().last()
        assertTrue(finalization.writeExistingAsset(incoming.id) { thumbnail.writeText("existing thumbnail") })
        var wroteAssets = false
        var persisted = false

        assertThrows(IOException::class.java) {
            finalization.commit(incoming.id, { wroteAssets = true }) { persisted = true }
        }

        assertFalse(wroteAssets)
        assertFalse(persisted)
        assertEquals("existing thumbnail", thumbnail.readText())
        assertFalse(marker.exists())
        assertEquals(listOf(thumbnail.name), directory.list().orEmpty().toList())
    }

    @Test fun abandonedAndMalformedPendingReceiptsAlsoBlockThumbnailDiskWrites() {
        val receipts = listOf("BookCover import 1\n${incoming.id}\n", "", "unreadable operation evidence")
        receipts.forEachIndexed { position, text ->
            val root = folder.newFolder("blocked-thumbnail-$position")
            val pending = File(root, marker.name).apply { writeText(text) }
            var wrote = false

            assertFalse(ImportFinalization(root).writeExistingAsset(incoming.id) {
                wrote = true
                assets(root).last().writeText("unexpected thumbnail")
            })

            assertFalse("An inactive or malformed receipt still reserves its assets", wrote)
            assertIncomingAbsent(root)
            assertEquals(text, pending.readText())
        }
    }

    /** Leaves the same on-disk residue as abrupt termination; this does not actually kill a JVM. */
    private class SimulatedProcessDeath : Error("Simulated abrupt termination")
}
