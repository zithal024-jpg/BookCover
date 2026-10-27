package com.bookcover.diagnostic

import com.bookcover.diagnostic.library.LibraryBackupException
import com.bookcover.diagnostic.library.LibraryIndexException
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.util.zip.ZipException

/** What the reader was doing when something failed; picks the wording and the fallback (BC-23 / BC-26). */
internal enum class ReaderAction { LOAD_LIBRARY, IMPORT, OPEN, SAVE_BACKUP, RESTORE_BACKUP, REPLACE_COVER, ORGANIZE, BOOKMARK }

internal enum class FailureReason {
    LIBRARY_PROTECTED, NOT_EPUB, PROTECTED_BOOK, TOO_LARGE, NO_CHAPTERS, UNREADABLE_FILE,
    BACKUP_NOT_WRITTEN, NOT_A_BACKUP, REMOTE_BLOCKED, UNKNOWN,
}

/**
 * Sorts a failure by its type and by BookCover's own error texts, never by showing them.
 * `ReaderMessagesTest` fails if the library code throws a text this does not recognise.
 */
internal fun failureReason(error: Throwable): FailureReason {
    val text = error.message.orEmpty()
    return when {
        error is LibraryIndexException || "library list could not be read" in text -> FailureReason.LIBRARY_PROTECTED
        error is LibraryBackupException || "library backup" in text -> FailureReason.NOT_A_BACKUP
        "DRM-free" in text || "is protected" in text -> FailureReason.PROTECTED_BOOK
        "up to 200 MB" in text || "exceeds the prototype's import limit" in text -> FailureReason.TOO_LARGE
        "no readable chapters" in text -> FailureReason.NO_CHAPTERS
        "Could not write the backup file" in text -> FailureReason.BACKUP_NOT_WRITTEN
        "Remote publication resources" in text -> FailureReason.REMOTE_BLOCKED
        error is ZipException || error is EOFException || "valid EPUB" in text ||
            "opened as an EPUB" in text || "choose an EPUB" in text -> FailureReason.NOT_EPUB
        error is FileNotFoundException || error is SecurityException || "Could not read" in text -> FailureReason.UNREADABLE_FILE
        else -> error.cause?.takeIf { it !== error }?.let(::failureReason) ?: FailureReason.UNKNOWN
    }
}

/** A short, plain message for the reader: what happened, whether anything changed, and what to try next. */
internal fun readerMessage(action: ReaderAction, error: Throwable): String = when (failureReason(error)) {
    FailureReason.LIBRARY_PROTECTED -> "BookCover couldn't read its list of your books, so it is leaving it untouched; " +
        "nothing was deleted. Restart BookCover to try again."
    FailureReason.NOT_A_BACKUP -> "That file isn't a BookCover backup. Choose a file made with Save backup. Your library is unchanged."
    FailureReason.PROTECTED_BOOK -> "This book is copy-protected (DRM), so BookCover can't open it. " +
        "Look for a DRM-free EPUB download of it. Your library is unchanged."
    FailureReason.TOO_LARGE -> "This book is too big to import: BookCover handles EPUBs up to 200 MB. Your library is unchanged."
    FailureReason.NO_CHAPTERS -> if (action == ReaderAction.OPEN) "This book has no chapters BookCover can show. Its copy may be damaged; " +
        "remove it and import it again." else "This EPUB has no chapters BookCover can show. It may be damaged; " +
        "try downloading it again. Your library is unchanged."
    FailureReason.BACKUP_NOT_WRITTEN -> "BookCover couldn't save the backup there. Choose another folder and try again."
    FailureReason.REMOTE_BLOCKED -> "Part of this book is stored on the internet, which BookCover never connects to. " +
        "The rest of the book may still work."
    FailureReason.NOT_EPUB -> when (action) {
        ReaderAction.OPEN -> "This book couldn't be opened; its copy may be damaged. Your reading position is saved. " +
            "Remove the book and import it again."
        ReaderAction.REPLACE_COVER -> "That file couldn't be used as a cover. Choose a picture such as a JPEG or PNG."
        else -> "This file isn't a readable EPUB. It may be damaged, not fully downloaded, or a different kind of file. " +
            "Try downloading it again. Your library is unchanged."
    }
    FailureReason.UNREADABLE_FILE -> when (action) {
        ReaderAction.OPEN -> "BookCover couldn't read this book's copy on your phone. Your reading position is saved. " +
            "Remove the book and import it again."
        ReaderAction.SAVE_BACKUP -> "BookCover couldn't save the backup there. Choose another folder and try again."
        else -> "BookCover couldn't read that file. Make sure it is fully downloaded and saved on this phone, then try again."
    }
    FailureReason.UNKNOWN -> when (action) {
        ReaderAction.LOAD_LIBRARY -> "BookCover couldn't finish starting up. Your books are still on this phone. Restart BookCover to try again."
        ReaderAction.IMPORT -> "BookCover couldn't import this book. Your library is unchanged. Try again, or try another copy of the book."
        ReaderAction.OPEN -> "BookCover couldn't open this book. Your reading position is saved. Try again; if it keeps failing, " +
            "remove the book and import it again."
        ReaderAction.SAVE_BACKUP -> "The backup couldn't be saved. Try again, or choose another folder."
        ReaderAction.RESTORE_BACKUP -> "The backup couldn't be restored. Your library is unchanged. Try again."
        ReaderAction.REPLACE_COVER -> "That picture couldn't be used as a cover. Try another picture."
        ReaderAction.ORGANIZE -> "That change couldn't be saved. Try again."
        ReaderAction.BOOKMARK -> "The bookmark couldn't be saved. Try again."
    }
}

/** A few words for one file in a batch import summary, e.g. "broken.epub (not a readable EPUB)" (BC-27). */
internal fun shortFailureReason(reason: FailureReason): String = when (reason) {
    FailureReason.LIBRARY_PROTECTED -> "library list unreadable"
    FailureReason.NOT_A_BACKUP, FailureReason.NOT_EPUB -> "not a readable EPUB"
    FailureReason.PROTECTED_BOOK -> "copy-protected"
    FailureReason.TOO_LARGE -> "over 200 MB"
    FailureReason.NO_CHAPTERS -> "no chapters to show"
    FailureReason.UNREADABLE_FILE, FailureReason.BACKUP_NOT_WRITTEN -> "couldn't be read"
    FailureReason.REMOTE_BLOCKED -> "needs the internet"
    FailureReason.UNKNOWN -> "unexpected problem"
}

/** For the diagnostic log: action, reason and exception types, without messages that may name a book or file. */
internal fun diagnosticSummary(action: ReaderAction, error: Throwable): String =
    "$action failed (${failureReason(error)}): " +
        generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(4).joinToString(" <- ") { it.javaClass.simpleName }
