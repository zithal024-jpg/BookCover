package com.bookcover.diagnostic.library

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportFinalizationPdfTest {
    @get:Rule val folder = TemporaryFolder()
    private val id = "b".repeat(64)
    private val pdfAssets get() = listOf("$id.pdf", "$id.png", "$id.thumb.png").map { File(folder.root, it) }

    @Test fun abandonedPdfReceiptRemovesOnlyOwnedPdfAssets() {
        val priorEpub = File(folder.root, "$id.epub").apply { writeText("preserve") }
        val receipt = File(folder.root, "pending-import-$id").apply { writeText("BookCover import 2\n$id\npdf\n") }
        pdfAssets.forEach { it.writeText("owned") }
        assertEquals(3, ImportFinalization(folder.root).recover())
        assertTrue(pdfAssets.none { it.exists() })
        assertFalse(receipt.exists())
        assertEquals("preserve", priorEpub.readText())
    }

    @Test fun pdfFailureCleansOwnedFilesAndRefusesExistingOtherFormat() {
        val finalization = ImportFinalization(folder.root)
        assertThrows(IOException::class.java) {
            finalization.commit(id, BookFormat.PDF, { pdfAssets.forEach { it.writeText("owned") } }) {
                throw IOException("failed to save index")
            }
        }
        assertTrue(pdfAssets.none { it.exists() })
        val prior = File(folder.root, "$id.epub").apply { writeText("preserve") }
        var wrote = false
        assertThrows(IOException::class.java) {
            finalization.commit(id, BookFormat.PDF, { wrote = true }) { }
        }
        assertFalse(wrote)
        assertEquals("preserve", prior.readText())
    }

    @Test fun committedPdfSurvivesInterruptedCompletion() {
        val record = BookRecord(id, "PDF", "Author", 1, format = BookFormat.PDF)
        val index = LibraryIndex(folder.root).apply { load() }
        assertThrows(SimulatedDeath::class.java) {
            ImportFinalization(folder.root).commit(id, BookFormat.PDF, { pdfAssets.forEach { it.writeText("owned") } }) {
                index.save(listOf(record))
                throw SimulatedDeath()
            }
        }
        assertEquals(0, ImportFinalization(folder.root).recover())
        assertTrue(pdfAssets.all { it.exists() })
        assertEquals(listOf(record), LibraryIndex(folder.root).load().records)
    }

    private class SimulatedDeath : Error()
}
