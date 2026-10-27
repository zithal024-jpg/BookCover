package com.bookcover.diagnostic.library

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * Disposable source-color cache, separate from the library index and its backups. Only a changed
 * cover is sampled again. Call under BookLibrary's lock, off the main thread.
 */
internal class CoverColorCache(private val file: File) {
    private data class Entry(val revision: Long, val seed: Int)
    private var entries: Map<String, Entry>? = null

    fun colors(records: List<BookRecord>, extract: (BookRecord) -> Int): List<BookCoverColor> {
        val previous = entries ?: read().also { entries = it }
        val current = records.associate { book ->
            val cached = previous[book.id]?.takeIf { it.revision == book.coverRevision }
            val entry = cached ?: Entry(book.coverRevision,
                runCatching { extract(book) }.getOrDefault(DEFAULT_COVER_SEED).let { seed ->
                    if (seed ushr 24 == 255) seed else DEFAULT_COVER_SEED
                })
            book.id to entry
        }
        if (current != previous) {
            entries = current
            // Losing this cache must never prevent opening/importing a book or saving its position.
            runCatching { write(current) }
        }
        return records.map { BookCoverColor(it.id, it.title, current.getValue(it.id).seed) }
    }

    private fun read(): Map<String, Entry> = runCatching {
        if (!file.isFile || file.length() > 4 * 1024 * 1024) return@runCatching emptyMap()
        val root = JSONObject(file.readText())
        if (root.optInt("version") != 1) return@runCatching emptyMap()
        val values = root.getJSONArray("covers")
        buildMap {
            for (index in 0 until values.length()) runCatching {
                val entry = values.getJSONObject(index)
                val id = entry.getString("id")
                val revision = entry.getLong("revision")
                val seed = entry.getLong("seed").also { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }.toInt()
                require(id.matches(Regex("[a-f0-9]{64}")) && revision >= 0 && seed ushr 24 == 255)
                put(id, Entry(revision, seed))
            }
        }
    }.getOrDefault(emptyMap())

    private fun write(values: Map<String, Entry>) {
        val json = JSONObject().put("version", 1).put("covers", JSONArray().apply {
            values.forEach { (id, entry) -> put(JSONObject().put("id", id)
                .put("revision", entry.revision).put("seed", entry.seed)) }
        })
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
