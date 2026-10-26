package com.bookcover.diagnostic.library

import org.json.JSONObject
import org.json.JSONArray

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
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("author", author); put("addedAt", addedAt)
        put("collection", collection); put("lastReadAt", lastReadAt)
        put("progression", progression); put("locator", locator); put("coverRevision", coverRevision)
        put("bookmarks", JSONArray(bookmarks))
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
            )
        }
    }
}
