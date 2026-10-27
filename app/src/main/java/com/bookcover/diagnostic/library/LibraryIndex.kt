package com.bookcover.diagnostic.library

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class LibraryIndexException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads each record on its own, skipping unreadable ones and repeated IDs. Returns the records and the skipped count. */
internal fun readBookRecords(array: JSONArray): Pair<List<BookRecord>, Int> {
    val records = LinkedHashMap<String, BookRecord>()
    var skipped = 0
    for (position in 0 until array.length()) {
        val record = try { BookRecord.fromJson(array.getJSONObject(position)) }
        catch (error: JSONException) { null }
        catch (error: IllegalArgumentException) { null }
        if (record == null || record.id in records) skipped++ else records[record.id] = record
    }
    return records.values.toList() to skipped
}

/**
 * Reads and writes `books.json`. Writes are refused until a load succeeds, so an unreadable index is
 * never replaced by an empty list. Bad records are skipped one by one, the previous good index is kept
 * as `books.last-good.json`, and a damaged index is copied aside before it is first overwritten.
 */
internal class LibraryIndex(private val directory: File, private val clock: () -> Long = System::currentTimeMillis) {
    data class LoadResult(val records: List<BookRecord>, val skipped: Int = 0, val recoveredFromBackup: Boolean = false,
                          val abandonedImportsRemoved: Int = 0)

    private val primary = File(directory, "books.json")
    // Older Android AtomicFile versions leave the previous index here during a write.
    private val legacyBackup = File(directory, "books.json.bak")
    private val lastGood = File(directory, "books.last-good.json")
    private var loaded = false
    private var primaryIsGood = false
    private var preserveDamagedPrimary = false

    fun load(): LoadResult {
        val candidates = listOf(primary, legacyBackup, lastGood).filter { it.exists() }
        if (candidates.isEmpty()) return accept(LoadResult(emptyList()), good = false, damaged = false)
        var failure: Exception? = null
        for (file in candidates) {
            val result = try { parse(file.readText()) }
            catch (error: IOException) { failure = failure ?: error; continue }
            catch (error: JSONException) { failure = failure ?: error; continue }
            val fromPrimary = file == primary
            return accept(result.copy(recoveredFromBackup = !fromPrimary), good = fromPrimary && result.skipped == 0,
                damaged = primary.exists() && !(fromPrimary && result.skipped == 0))
        }
        throw LibraryIndexException("Your library list could not be read. BookCover will not change it until this is fixed.", failure)
    }

    fun save(records: List<BookRecord>) {
        check(loaded) { "Your library list could not be read, so BookCover is not saving changes to protect it." }
        if (preserveDamagedPrimary) {
            primary.copyTo(File(directory, "books.damaged-${clock()}.json"))
            preserveDamagedPrimary = false
        } else if (primaryIsGood) {
            replace(lastGood, primary.readBytes())
        }
        val json = JSONObject().put("version", 1).put("books", JSONArray().apply { records.forEach { put(it.toJson()) } })
        replace(primary, json.toString().toByteArray())
        primaryIsGood = true
    }

    private fun accept(result: LoadResult, good: Boolean, damaged: Boolean): LoadResult {
        loaded = true; primaryIsGood = good; preserveDamagedPrimary = damaged
        return result
    }

    private fun parse(text: String): LoadResult {
        val (records, skipped) = readBookRecords(JSONObject(text).getJSONArray("books"))
        return LoadResult(records, skipped)
    }

    private fun replace(target: File, bytes: ByteArray) {
        val temporary = File(directory, "${target.name}.tmp")
        FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
