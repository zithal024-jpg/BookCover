package com.bookcover.diagnostic.library

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.bookcover.diagnostic.rewards.RelicStore
import com.bookcover.diagnostic.streak.ReadingDayStore

internal class LibraryBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A user-saved copy of reading metadata, history, relics and appearance choices. Book files are not
 * included. Book positions match existing SHA-256 identifiers; history/rewards can restore without books.
 */
internal object LibraryBackup {
    private const val FORMAT = "bookcover-library-backup"

    data class Parsed(val records: List<BookRecord>, val skipped: Int,
                      val relics: JSONObject? = null, val readingDays: JSONObject? = null)
    data class MergeResult(val records: List<BookRecord>, val restored: Int, val missing: Int,
                           val readingRewardsRestored: Boolean = false)

    fun export(records: List<BookRecord>, exportedAt: Long, relics: JSONObject? = null, readingDays: JSONObject? = null): String {
        relics?.let(RelicStore::validateBackup)
        readingDays?.let(ReadingDayStore::validateBackup)
        return JSONObject().put("format", FORMAT).put("version", if (relics != null || readingDays != null) 2 else 1)
            .put("exportedAt", exportedAt)
            .put("books", JSONArray().apply { records.forEach { put(it.toJson()) } })
            .apply { relics?.let { put("relics", it) }; readingDays?.let { put("readingDays", it) } }.toString(2)
    }

    /** Accepts a backup made by [export] or a plain `books.json` index. */
    fun parse(text: String): Parsed {
        val json = try { JSONObject(text) } catch (error: JSONException) { throw notABackup(error) }
        val format = json.optString("format", FORMAT)
        val array = json.optJSONArray("books")
        if (format != FORMAT || array == null) throw notABackup(null)
        // Validate the entire optional payload before any library/history/reward file is changed.
        val version = if (!json.has("version")) 1L else json.get("version").let {
            if (it !is Int && it !is Long) throw notABackup(null)
            (it as Number).toLong()
        }
        if (version > 2) throw LibraryBackupException("This backup needs a newer version of BookCover.")
        if (version < 1) throw notABackup(null)
        val relics = if (json.has("relics")) json.optJSONObject("relics")
            ?: throw notABackup(null) else null
        val readingDays = if (json.has("readingDays")) json.optJSONObject("readingDays")
            ?: throw notABackup(null) else null
        try {
            relics?.let(RelicStore::validateBackup)
            readingDays?.let(ReadingDayStore::validateBackup)
        } catch (error: Exception) { throw LibraryBackupException("This backup has damaged reading rewards or history.", error) }
        val (records, skipped) = readBookRecords(array)
        return Parsed(records, skipped, relics, readingDays)
    }

    /**
     * Restores each backed-up book that is in [current]: its collection, any bookmarks not already
     * present, and its reading position when the backup was read more recently. Books only in
     * [current] are unchanged; books only in the backup are counted as missing.
     */
    fun merge(current: List<BookRecord>, backup: List<BookRecord>): MergeResult {
        val saved = backup.associateBy { it.id }
        val records = current.map { book ->
            val copy = saved[book.id] ?: return@map book
            val position = if (copy.lastReadAt > book.lastReadAt) copy else book
            book.copy(collection = copy.collection, bookmarks = SavedBookmarks.merge(book.bookmarks, copy.bookmarks),
                lastReadAt = position.lastReadAt, progression = position.progression, locator = position.locator)
        }
        val present = current.map { it.id }.toSet()
        return MergeResult(records, restored = saved.keys.count { it in present }, missing = saved.keys.count { it !in present })
    }

    private fun notABackup(cause: Throwable?) =
        LibraryBackupException("This file is not a BookCover library backup.", cause)
}
