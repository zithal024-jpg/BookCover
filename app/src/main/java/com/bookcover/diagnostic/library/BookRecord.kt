package com.bookcover.diagnostic.library

import org.json.JSONObject
import org.json.JSONArray

internal enum class BookFormat(val extension: String) { EPUB("epub"), PDF("pdf") }

/** PDF permits its signature within the first 1024 bytes. ZIP files remain EPUB candidates. */
internal fun detectBookFormat(header: ByteArray, length: Int): BookFormat {
    val count = length.coerceIn(0, header.size)
    if (count >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) return BookFormat.EPUB
    val signature = "%PDF-".toByteArray(Charsets.US_ASCII)
    return if ((0..count - signature.size).any { offset -> signature.indices.all { header[offset + it] == signature[it] } })
        BookFormat.PDF else BookFormat.EPUB
}

internal data class BookRecord(
    val id: String,
    val title: String,
    val author: String,
    val addedAt: Long,
    val collection: String = "Books",
    val lastReadAt: Long = 0,
    val progression: Double = 0.0,
    val locator: String? = null,
    val coverRevision: Long = 0,
    val bookmarks: List<String> = emptyList(),
    val format: BookFormat = BookFormat.EPUB,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("author", author); put("addedAt", addedAt)
        put("collection", collection); put("lastReadAt", lastReadAt)
        put("progression", progression); put("locator", locator); put("coverRevision", coverRevision)
        put("bookmarks", JSONArray(bookmarks))
        put("format", format.extension)
    }

    companion object {
        fun fromJson(json: JSONObject): BookRecord {
            val id = json.getString("id")
            require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid library identifier" }
            return BookRecord(
                id, json.optString("title", "Untitled"), json.optString("author", "Unknown author"),
                json.optLong("addedAt"), json.optString("collection", "Books"), json.optLong("lastReadAt"),
                json.optDouble("progression", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
                json.optString("locator").takeIf { it.isNotBlank() && it != "null" }, json.optLong("coverRevision"),
                json.optJSONArray("bookmarks")?.let { array -> (0 until array.length()).map { array.getString(it) } } ?: emptyList(),
                when (json.optString("format", "epub")) {
                    "epub" -> BookFormat.EPUB
                    "pdf" -> BookFormat.PDF
                    else -> throw IllegalArgumentException("Unknown book format")
                },
            )
        }
    }
}
