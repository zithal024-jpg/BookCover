package com.bookcover.diagnostic.streak

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject

/**
 * `reading-days.json`: reading time and completed goals per local date, kept beside the library index.
 * Only dated totals are stored, never book names or positions. A damaged file is copied aside, never
 * silently replaced. Methods are synchronized and do file work: call them off the main thread.
 */
internal class ReadingDayStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val state = MutableStateFlow(ReadingDays())
    val days: StateFlow<ReadingDays> = state.asStateFlow()
    private var loaded = false
    private var healthy = true

    @Synchronized
    fun load(): ReadingDays {
        if (loaded) return state.value
        state.value = try {
            if (file.exists()) parse(file.readText()) else ReadingDays()
        } catch (error: Exception) {
            if (error !is IOException && error !is JSONException && error !is DateTimeParseException && error !is IllegalArgumentException) throw error
            file.copyTo(File(file.parentFile, "reading-days.damaged-${clock()}.json"), overwrite = true)
            healthy = false
            ReadingDays()
        }
        loaded = true
        return state.value
    }

    @Synchronized
    fun add(startMillis: Long, endMillis: Long, zone: ZoneId = ZoneId.systemDefault()) {
        if (endMillis <= startMillis) return
        save(load().plus(startMillis, endMillis, zone))
    }

    /** Remembers that [date]'s completion was celebrated, so it is shown at most once. */
    @Synchronized
    fun markCelebrated(date: LocalDate) {
        val current = load()
        if (current.celebrated != date) save(current.copy(celebrated = date))
    }

    /** A complete validated snapshot; an unreadable history must never export an empty replacement. */
    @Synchronized
    fun exportJson(): JSONObject {
        val current = load()
        check(healthy) { "Your reading history could not be read, so BookCover cannot back it up yet." }
        return toJson(current)
    }

    /** Merges maximum time per date, rather than adding the same backed-up time again. */
    @Synchronized
    fun mergeBackup(json: JSONObject): ReadingDays {
        val restored = validateBackup(json) // All dates and values are checked before touching local state.
        val current = load()
        val days = current.days.toMutableMap()
        restored.days.forEach { (date, incoming) ->
            val existing = days[date] ?: ReadingDay()
            days[date] = ReadingDay(maxOf(existing.millis, incoming.millis), existing.completed || incoming.completed)
        }
        // Keep the local celebration marker so restoring cannot replay a prior celebration.
        val merged = current.copy(days = days.toMap(), celebrated = current.celebrated ?: restored.celebrated)
        if (merged != current || !healthy) save(merged)
        return state.value
    }

    private fun save(updated: ReadingDays) {
        val json = toJson(updated)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { output -> output.write(json.toString().toByteArray()); output.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        state.value = updated
        healthy = true
    }

    private fun parse(text: String): ReadingDays = validateBackup(JSONObject(text))

    companion object {
        // A local date can be revisited when crossing time zones; allow up to two full days of credit.
        internal const val MAX_DAY_MILLIS = 48 * 60 * 60_000L
        private const val MAX_RECORDED_DAYS = 100_000

        /** Strict preflight used by the library before any part of a backup is committed. */
        fun validateBackup(json: JSONObject): ReadingDays {
            val version = json.get("version")
            require(version is Number && version.toLong() == 1L && version.toDouble() == 1.0) { "Unknown reading history version" }
            val saved = json.getJSONObject("days")
            require(saved.length() <= MAX_RECORDED_DAYS) { "Reading history is too large" }
            val days = saved.keys().asSequence().associate { key ->
                val date = date(key)
                val day = saved.getJSONObject(key)
                val value = day.get("millis")
                require(value is Byte || value is Short || value is Int || value is Long) { "Reading time must be whole milliseconds" }
                val millis = (value as Number).toLong()
                require(millis in 0..MAX_DAY_MILLIS) { "Reading time is outside a valid daily range" }
                val completed = day.get("completed")
                require(completed is Boolean) { "Reading goal completion must be true or false" }
                date to ReadingDay(millis, completed || millis >= DAILY_GOAL_MILLIS)
            }
            val celebrated = if (!json.has("celebrated") || json.isNull("celebrated")) null else {
                val value = json.get("celebrated")
                require(value is String) { "Invalid celebration date" }
                date(value)
            }
            return ReadingDays(days, celebrated)
        }

        private fun date(value: String): LocalDate = LocalDate.parse(value).also {
            require(it.year in 1..9999 && it.toString() == value) { "Invalid calendar date" }
        }

        private fun toJson(value: ReadingDays): JSONObject = JSONObject().put("version", 1).apply {
            value.celebrated?.let { put("celebrated", it.toString()) }
            put("days", JSONObject().apply {
                value.days.toSortedMap().forEach { (date, day) ->
                    put(date.toString(), JSONObject().put("millis", day.millis).put("completed", day.completed))
                }
            })
        }
    }
}
