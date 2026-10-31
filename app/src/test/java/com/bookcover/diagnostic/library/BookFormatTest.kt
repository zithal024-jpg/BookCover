package com.bookcover.diagnostic.library

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BookFormatTest {
    @Test fun oldRecordsRemainEpubAndPdfRoundTrips() {
        val original = BookRecord("a".repeat(64), "Title", "Author", 10)
        val legacy = original.toJson().apply { remove("format") }
        assertEquals(BookFormat.EPUB, BookRecord.fromJson(legacy).format)
        val pdf = original.copy(format = BookFormat.PDF, locator = "saved page")
        assertEquals(pdf, BookRecord.fromJson(JSONObject(pdf.toJson().toString())))
        assertThrows(IllegalArgumentException::class.java) {
            BookRecord.fromJson(original.toJson().put("format", "other"))
        }
    }

    @Test fun detectsPdfHeaderWithoutTrustingFilenameAndKeepsZipAsEpub() {
        fun detect(value: ByteArray) = detectBookFormat(value, value.size)
        assertEquals(BookFormat.PDF, detect("%PDF-1.7".toByteArray()))
        assertEquals(BookFormat.PDF, detect("prefix\n%PDF-1.4".toByteArray()))
        assertEquals(BookFormat.EPUB, detect("PK\u0003\u0004 archive containing %PDF-1.7".toByteArray()))
        assertEquals(BookFormat.EPUB, detect(byteArrayOf()))
        assertEquals(BookFormat.EPUB, detect("%PDF".toByteArray()))
    }

    @Test fun pdfBackupPreservesFormatAndRestoresPositionToCurrentCopy() {
        val current = BookRecord("c".repeat(64), "PDF", "Author", 10, format = BookFormat.PDF)
        val saved = current.copy(lastReadAt = 20, locator = "saved page", progression = 0.5)
        val parsed = LibraryBackup.parse(LibraryBackup.export(listOf(saved), 30)).records
        assertEquals(listOf(saved), parsed)
        assertEquals(saved, LibraryBackup.merge(listOf(current), parsed).records.single())
    }
}
