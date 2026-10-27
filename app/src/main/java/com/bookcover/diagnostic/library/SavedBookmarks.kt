package com.bookcover.diagnostic.library

import org.json.JSONArray
import org.json.JSONObject

/** Bookmark labels extend the existing locator JSON, so old indexes and backups need no migration. */
internal object SavedBookmarks {
    private const val NAME = "bookcoverBookmarkName"
    const val MAX_NAME_LENGTH = 120

    fun name(value: String): String? = parse(value)?.optJSONObject("locations")?.string(NAME)
    fun chapterTitle(value: String): String? = parse(value)?.string("title")

    fun create(locator: String, name: String? = null, chapterTitle: String? = null): String {
        val json = parse(locator) ?: return locator
        chapterTitle?.trim()?.takeIf(String::isNotEmpty)?.let { json.put("title", it) }
        setName(json, name)
        return json.toString()
    }

    /** Changes the label only; every navigation field and unknown extension remains intact. */
    fun rename(value: String, name: String): String {
        val json = parse(value) ?: return value
        val normalized = cleanName(name)
        if (this.name(value) == normalized) return value
        setName(json, normalized)
        return json.toString()
    }

    /** Explicit saves can relabel an existing target, but never move its saved location. */
    fun upsert(existing: List<String>, value: String): List<String> {
        val index = existing.indexOfFirst { sameTarget(it, value) }
        if (index < 0) return existing + value
        val updated = enrich(existing[index], value, replaceName = true)
        return if (updated == existing[index]) existing else existing.toMutableList().apply { this[index] = updated }
    }

    /** Keep local bookmark order and names. A backup may fill a missing name, not overwrite one. */
    fun merge(local: List<String>, backup: List<String>): List<String> {
        val result = local.toMutableList()
        backup.forEach { incoming ->
            val index = result.indexOfFirst { sameTarget(it, incoming) }
            if (index < 0) result += incoming
            else result[index] = enrich(result[index], incoming, replaceName = false)
        }
        return result
    }

    /**
     * Match precise reading targets, not byte formatting, chapter labels or bookmark names.
     * A resource alone or its coarse global position cannot distinguish pages within a chapter.
     * Missing or conflicting evidence is kept separate rather than discarding a possible bookmark.
     */
    fun sameTarget(first: String, second: String): Boolean {
        if (first == second) return true
        val a = parse(first) ?: return false
        val b = parse(second) ?: return false
        val href = a.string("href") ?: return false
        if (href != b.string("href")) return false
        val typeA = a.string("type")
        val typeB = b.string("type")
        if (typeA != null && typeB != null && typeA != typeB) return false
        val locationsA = a.optJSONObject("locations")
        val locationsB = b.optJSONObject("locations")
        val cssA = locationsA?.string("cssSelector")
        val cssB = locationsB?.string("cssSelector")
        val fragmentsA = fragments(locationsA)
        val fragmentsB = fragments(locationsB)
        val textA = a.optJSONObject("text")?.string("highlight")
        val textB = b.optJSONObject("text")?.string("highlight")
        if (cssA != null && cssB != null && cssA != cssB) return false
        if (fragmentsA.isNotEmpty() && fragmentsB.isNotEmpty() && fragmentsA != fragmentsB) return false
        if (textA != null && textB != null && textA != textB) return false

        val sameCss = cssA != null && cssA == cssB
        val sameFragments = fragmentsA.isNotEmpty() && fragmentsA == fragmentsB
        // A visible text quote within a known block survives repagination. Repeated prose in
        // different paragraphs is not the same target because its selector/fragment differs.
        if (textA != null && textA == textB && (sameCss || sameFragments)) {
            val beforeA = a.optJSONObject("text")?.string("before")
            val beforeB = b.optJSONObject("text")?.string("before")
            val afterA = a.optJSONObject("text")?.string("after")
            val afterB = b.optJSONObject("text")?.string("after")
            return !(beforeA != null && beforeB != null && beforeA != beforeB) &&
                !(afterA != null && afterB != null && afterA != afterB)
        }

        val progressionA = locationsA?.progression()
        val progressionB = locationsB?.progression()
        if (progressionA != null && progressionB != null) return progressionA == progressionB
        // A bare explicit fragment/selector is an anchor, but do not collapse it with a target
        // refined by progression or a text quote that the other bookmark cannot identify.
        if (progressionA != null || progressionB != null || textA != null || textB != null) return false
        return sameCss || sameFragments || ('#' in href && href.substringAfter('#').isNotBlank())
    }

    private fun enrich(existing: String, incoming: String, replaceName: Boolean): String {
        val current = parse(existing) ?: return existing
        val other = parse(incoming) ?: return existing
        var changed = false
        val incomingName = name(incoming)
        if (incomingName != null && (replaceName || name(existing) == null) && incomingName != name(existing)) {
            setName(current, incomingName)
            changed = true
        }
        if (current.string("title") == null) other.string("title")?.let {
            current.put("title", it)
            changed = true
        }
        return if (changed) current.toString() else existing
    }

    private fun cleanName(value: String?): String? = value?.trim()?.take(MAX_NAME_LENGTH)?.takeIf(String::isNotEmpty)

    private fun setName(json: JSONObject, value: String?) {
        val name = cleanName(value)
        val locations = json.optJSONObject("locations")
        if (name == null) locations?.remove(NAME)
        else (locations ?: JSONObject().also { json.put("locations", it) }).put(NAME, name)
    }

    private fun parse(value: String): JSONObject? = runCatching { JSONObject(value) }.getOrNull()
    private fun JSONObject.string(key: String): String? = (opt(key) as? String)?.trim()?.takeIf(String::isNotEmpty)
    private fun JSONObject.progression(): Double? = (opt("progression") as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it in 0.0..1.0 }

    private fun fragments(locations: JSONObject?): List<String> {
        val value = locations?.opt("fragments") ?: locations?.opt("fragment")
        return when (value) {
            is String -> listOf(value)
            is JSONArray -> (0 until value.length()).mapNotNull { value.opt(it) as? String }
            else -> emptyList()
        }.filter(String::isNotBlank).distinct().sorted()
    }
}
