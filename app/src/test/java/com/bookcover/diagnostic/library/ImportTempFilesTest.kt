package com.bookcover.diagnostic.library

import org.junit.Assert.*
import org.junit.Test

/** BC-27: start-up cleanup removes only import copies left behind by an interrupted import. */
class ImportTempFilesTest {
    private val book = "a".repeat(64)

    @Test fun onlyAbandonedImportCopiesAreSelected() {
        val names = listOf("import-123456789.epub", "import--42.epub", "import-77.epub", "import-18.pdf", "import-19.pdf", "$book.pdf", "$book.epub", "$book.png",
            "$book.thumb.png", "books.json", "books.last-good.json", "books.json.tmp", "import-notes.txt",
            "import-12.epub.part", "my-import-5.epub", "import-.epub", "IMPORT-5.EPUB", "import-1a.epub")
        assertEquals(listOf("import-123456789.epub", "import--42.epub", "import-18.pdf"), abandonedImportTemps(names, active = setOf("import-77.epub", "import-19.pdf")))
    }

    @Test fun anImportStillRunningIsNeverSelected() {
        assertTrue(abandonedImportTemps(listOf("import-5.epub"), active = setOf("import-5.epub")).isEmpty())
    }

    @Test fun javaAndAndroidTemporaryNamesAreRecognised() {
        // File.createTempFile adds a random number, which Android may write with a minus sign.
        assertTrue(isImportTempName("import-9223372036854775807.epub"))
        assertTrue(isImportTempName("import--2147483648.epub"))
        assertFalse(isImportTempName("$book.epub"))
    }
}
