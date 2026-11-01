package com.bookcover.diagnostic

import com.bookcover.diagnostic.library.LibraryBackupException
import com.bookcover.diagnostic.library.LibraryIndexException
import com.bookcover.diagnostic.library.LibraryNotReadyException
import com.bookcover.diagnostic.library.MissingCoverBookException
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException
import org.junit.Assert.*
import org.junit.Test

/** BC-23 / BC-26: readers see plain, actionable messages; technical detail goes to the diagnostic log only. */
class ReaderMessagesTest {
    @Test fun readinessAndRemovedCoverMessagesGiveAnActionWithoutClaimingSuccess() {
        assertTrue(readerMessage(ReaderAction.SAVE_BACKUP, LibraryNotReadyException(false)).contains("Wait for it to finish"))
        assertTrue(readerMessage(ReaderAction.RESTORE_BACKUP, LibraryNotReadyException(true)).contains("Restart BookCover"))
        assertTrue(readerMessage(ReaderAction.REPLACE_COVER, MissingCoverBookException()).contains("no longer in your library"))
    }
    private val technical = listOf("exception", "zip", "header", "null", "java", "json", "index", "prototype",
        "readium", "container.xml", "stack", "uri", "content://", "/data/")

    private val samples: List<Throwable> = listOf(
        ZipException("zip END header not found"), EOFException("Unexpected end of ZLIB input stream"),
        IOException("Could not read the selected file"), IOException("Could not read this EPUB file"),
        IOException("This file could not be opened as an EPUB"), IOException("Could not write the backup file"),
        IOException("Could not save the book cover"), IOException("The imported book files are incomplete"),
        IOException("An earlier copy of this book needs to be preserved"),
        FileNotFoundException("/storage/emulated/0/Download/My Book.epub: open failed: ENOENT"),
        SecurityException("Permission Denial: reading content://com.android.providers"),
        IllegalArgumentException("This prototype imports EPUBs up to 200 MB"),
        IllegalArgumentException("Choose a valid EPUB file"),
        IllegalArgumentException("This EPUB's expanded content exceeds the prototype's import limit"),
        IllegalArgumentException("Please choose an EPUB book"),
        IllegalArgumentException("This EPUB is protected. The prototype opens DRM-free EPUBs."),
        IllegalArgumentException("This EPUB has no readable chapters"),
        IllegalArgumentException("This file is too large to be a BookCover library backup."),
        LibraryBackupException("Not a BookCover library backup"),
        LibraryIndexException("Your library list could not be read. BookCover will not change it until this is fixed."),
        IllegalStateException("Your library list could not be read, so BookCover is not saving changes to protect it."),
        NullPointerException("Attempt to invoke virtual method 'int java.lang.String.length()' on a null object reference"),
        RuntimeException("kotlinx.serialization.json.internal.JsonDecodingException at path $.metadata"),
    )

    @Test fun corruptEpubImportShowsAPlainActionableMessage() {
        val message = readerMessage(ReaderAction.IMPORT, ZipException("zip END header not found"))
        assertTrue(message, message.contains("isn't a readable EPUB"))
        assertTrue(message, message.contains("download"))
        assertTrue(message, message.contains("library is unchanged"))
    }

    @Test fun noMessageLeaksTechnicalDetailForAnyActionOrFailure() {
        for (action in ReaderAction.entries) for (error in samples) {
            val message = readerMessage(action, error)
            technical.forEach { word -> assertFalse("$action ${error.javaClass.simpleName}: '$message' contains $word",
                message.lowercase().contains(word)) }
            assertTrue("$action: '$message' too long", message.length <= 220)
            assertTrue("$action: '$message' should be a sentence", message.endsWith("."))
            assertFalse("$action leaked the raw text", error.message != null && message.contains(error.message!!))
        }
    }

    @Test fun importFailuresSayWhatToDo() {
        assertTrue(readerMessage(ReaderAction.IMPORT, IllegalArgumentException("This EPUB is protected. The prototype opens DRM-free EPUBs."))
            .contains("DRM-free"))
        assertTrue(readerMessage(ReaderAction.IMPORT, IllegalArgumentException("This prototype imports EPUBs up to 200 MB")).contains("200 MB"))
        assertTrue(readerMessage(ReaderAction.IMPORT, IllegalArgumentException("This EPUB has no readable chapters")).contains("no chapters"))
        assertTrue(readerMessage(ReaderAction.IMPORT, IOException("Could not read the selected file")).contains("couldn't read that file"))
    }

    @Test fun openFailureOfAnImportedBookSuggestsReimporting() {
        val message = readerMessage(ReaderAction.OPEN, IOException("This file could not be opened as an EPUB"))
        assertTrue(message, message.contains("import it again"))
        assertTrue(message, message.contains("reading position"))
    }

    @Test fun backupFailuresAreSpecific() {
        assertTrue(readerMessage(ReaderAction.RESTORE_BACKUP, LibraryBackupException("Not a BookCover library backup"))
            .contains("isn't a BookCover backup"))
        assertTrue(readerMessage(ReaderAction.RESTORE_BACKUP, IllegalArgumentException("This file is too large to be a BookCover library backup."))
            .contains("isn't a BookCover backup"))
        assertTrue(readerMessage(ReaderAction.SAVE_BACKUP, IOException("Could not write the backup file")).contains("another folder"))
        assertTrue(readerMessage(ReaderAction.RESTORE_BACKUP, NullPointerException()).contains("Some reading data may have been restored"))
    }

    @Test fun protectedLibraryTellsTheReaderTheirBooksAreSafe() {
        listOf(LibraryIndexException("Your library list could not be read. BookCover will not change it until this is fixed."),
            IllegalStateException("Your library list could not be read, so BookCover is not saving changes to protect it."))
            .forEach { error ->
                for (action in ReaderAction.entries) assertTrue("$action", readerMessage(action, error).contains("nothing was deleted"))
            }
    }

    @Test fun unknownFailuresFallBackToTheActionNotTheErrorText() {
        val message = readerMessage(ReaderAction.OPEN, NullPointerException("Attempt to invoke virtual method"))
        assertEquals(readerMessage(ReaderAction.OPEN, RuntimeException()), message)
        assertTrue(message.contains("couldn't open this book"))
    }

    @Test fun diagnosticSummaryKeepsTheTechnicalTypeButNotDocumentText() {
        val summary = diagnosticSummary(ReaderAction.IMPORT,
            IOException("wrapped", FileNotFoundException("/storage/emulated/0/Download/My Book.epub: ENOENT")))
        assertTrue(summary, summary.contains("IMPORT") && summary.contains("IOException") && summary.contains("FileNotFoundException"))
        assertFalse(summary, summary.contains("My Book"))
    }

    @Test fun batchImportReasonsAreShortAndPlain() {
        for (error in samples) {
            val reason = shortFailureReason(failureReason(error))
            technical.forEach { word -> assertFalse("'$reason' contains $word", reason.lowercase().contains(word)) }
            assertTrue("'$reason' too long", reason.length <= 40)
        }
        assertEquals("not a readable EPUB or PDF", shortFailureReason(FailureReason.NOT_EPUB))
        assertEquals("copy-protected", shortFailureReason(FailureReason.PROTECTED_BOOK))
        assertEquals("over 200 MB", shortFailureReason(FailureReason.TOO_LARGE))
    }

    /** Guards the mapper against new error texts added to the library code without a reader message. */
    @Test fun everyErrorTextThrownByTheLibraryCodeHasASpecificMessage() {
        val sources = listOf("BookLibrary.kt", "ImportFinalization.kt", "EpubArchiveCheck.kt", "ReadiumEngine.kt", "LibraryIndex.kt", "LibraryBackup.kt").map { name ->
            listOf(File("src/main/java/com/bookcover/diagnostic/library/$name"),
                File("app/src/main/java/com/bookcover/diagnostic/library/$name")).first { it.isFile }.readText()
        }
        val thrown = sources.flatMap { source ->
            // Lazy match: a require/check, possibly over several lines, up to its message block.
            Regex("""(?:require|check)\([\s\S]*?\)\s*\{\s*"([^"]+)"\s*\}""").findAll(source).map { it.groupValues[1] } +
                Regex("""(?:IOException|LibraryIndexException|LibraryBackupException)\("([^"$]+)"""").findAll(source).map { it.groupValues[1] }
        }.toSet()
        assertTrue("expected the library's error texts, found $thrown", thrown.size >= 10)
        thrown.forEach { text ->
            listOf(IllegalArgumentException(text), IOException(text), IllegalStateException(text)).forEach { error ->
                assertNotEquals("'$text' has no specific reader message", FailureReason.UNKNOWN, failureReason(error))
            }
        }
    }
}
