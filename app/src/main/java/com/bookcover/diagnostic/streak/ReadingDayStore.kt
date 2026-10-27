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

    @Synchronized
    fun load(): ReadingDays {
        if (loaded) return state.value
        state.value = try {
            if (file.exists()) parse(file.readText()) else ReadingDays()
        } catch (error: Exception) {
            if (error !is IOException && error !is JSONException && error !is DateTimeParseException) throw error
            file.copyTo(File(file.parentFile, "reading-days.damaged-${clock()}.json"), overwrite = true)
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

    private fun save(updated: ReadingDays) {
        val json = JSONObject().put("version", 1)
        updated.celebrated?.let { json.put("celebrated", it.toString()) }
        json.put("days", JSONObject().apply {
            updated.days.toSortedMap().forEach { (date, day) ->
                put(date.toString(), JSONObject().put("millis", day.millis).put("completed", day.completed))
            }
        })
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { output -> output.write(json.toString().toByteArray()); output.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        state.value = updated
    }

    private fun parse(text: String): ReadingDays {
        val json = JSONObject(text)
        val saved = json.getJSONObject("days")
        val days = saved.keys().asSequence().associate { key ->
            val day = saved.getJSONObject(key)
            LocalDate.parse(key) to ReadingDay(day.optLong("millis", 0).coerceAtLeast(0), day.optBoolean("completed", false))
        }
        val celebrated = json.optString("celebrated", "").takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
        return ReadingDays(days, celebrated)
    }
}
