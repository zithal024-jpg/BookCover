package com.bookcover.diagnostic.rewards

import com.bookcover.diagnostic.streak.ReadingDays
import com.bookcover.diagnostic.streak.ReadingDayStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.DateTimeException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal class RelicStoreException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Permanent, local-only unlocks. Dated high-water marks prevent repeat credit after restore. The
 * Library restores these floors to ReadingDayStore before readers start, so a missing history file
 * cannot make new reading wait to catch up to an older lifetime total.
 *
 * Both files hold the complete current document. Writing the backup first means an interrupted
 * primary replacement is recoverable; load chooses the newest valid revision. Unreadable files are
 * never treated as an empty collection or overwritten without preserving their original bytes.
 * Methods perform file work and must be called off the UI thread.
 */
internal class RelicStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Document(
        val collection: RelicCollection = RelicCollection(),
        val readingFloor: Map<LocalDate, Long> = emptyMap(),
        val initialized: Boolean = false,
        val defaultConfigured: Boolean = false,
        val revision: Long = 0,
        /** A reset is an explicit preference too; older backups must not resurrect its override. */
        val inheritedBooks: Set<String> = emptySet(),
    )

    private data class Candidate(val file: File, val bytes: ByteArray, val document: Document)
    private val backup = File(file.parentFile, "${file.name}.bak")
    private val state = MutableStateFlow(RelicCollection())
    val collection: StateFlow<RelicCollection> = state.asStateFlow()
    private var document = Document()
    private var loaded = false
    private var observedFiles = emptyMap<File, ByteArray?>()
    private var damaged = emptySet<File>()

    @Synchronized
    fun load(): RelicCollection {
        if (loaded) return state.value
        val observations = linkedMapOf<File, ByteArray?>()
        val invalid = linkedSetOf<File>()
        val valid = mutableListOf<Candidate>()
        var failure: Exception? = null
        for (candidate in listOf(file, backup)) {
            if (!candidate.exists()) { observations[candidate] = null; continue }
            try {
                require(candidate.length() <= MAX_DOCUMENT_BYTES) { "Relic file is too large" }
                val bytes = candidate.readBytes()
                observations[candidate] = bytes
                valid += Candidate(candidate, bytes, decode(JSONObject(bytes.toString(Charsets.UTF_8))))
            } catch (error: Exception) {
                if (error !is IOException && error !is IllegalArgumentException && error !is DateTimeException && error !is org.json.JSONException) throw error
                failure = failure ?: error
                invalid += candidate
                // A failed read is not safe to overwrite, even if another copy can be loaded.
                if (!observations.containsKey(candidate)) observations[candidate] = null
            }
        }
        if (valid.isEmpty() && invalid.isNotEmpty()) throw RelicStoreException(
            "Your reading rewards could not be read. BookCover is keeping them unchanged.", failure)
        document = valid.maxWithOrNull(compareBy<Candidate> { it.document.revision }.thenBy { it.file == file })?.document ?: Document()
        observedFiles = observations
        damaged = invalid
        loaded = true
        state.value = document.collection
        return state.value
    }

    @Synchronized
    fun reconcile(days: ReadingDays): RelicCollection {
        load()
        val floors = document.readingFloor.toMutableMap()
        var total = document.collection.totalMillis
        for ((date, day) in days.days) {
            require(date.year in 1..9999 && day.millis in 0..ReadingDayStore.MAX_DAY_MILLIS) { "Invalid dated reading time" }
            val previous = floors[date] ?: 0
            if (day.millis > previous) {
                total = saturatedAdd(total, day.millis - previous)
                floors[date] = day.millis
            }
        }
        val earned = document.collection.earned.toMutableMap()
        MoonlitTier.entries.filter { total >= it.thresholdMillis && it.id !in earned }.forEach {
            earned[it.id] = if (document.initialized) clock().coerceAtLeast(0) else 0
        }
        val updated = document.copy(
            collection = document.collection.copy(totalMillis = total, earned = earned.toMap()),
            readingFloor = floors.toMap(), initialized = true,
        )
        if (updated != document) save(updated)
        return state.value
    }

    /** Merge this floor into ReadingDayStore before starting the reader or collecting its changes. */
    @Synchronized
    fun readingHistoryFloorJson(): JSONObject {
        load()
        return floorJson(document.readingFloor)
    }

    @Synchronized
    fun setAppearance(bookId: String?, appearance: RelicAppearance) {
        load()
        bookId?.let(::validateBookId)
        val permitted = appearance.permitted(document.collection.unlocked)
        val updated = if (bookId == null) document.copy(
            collection = document.collection.copy(defaultAppearance = permitted), defaultConfigured = true,
        ) else document.copy(collection = document.collection.copy(
            bookAppearances = document.collection.bookAppearances + (bookId to permitted),
        ), inheritedBooks = document.inheritedBooks - bookId)
        if (updated != document) save(updated)
    }

    @Synchronized
    fun resetBookAppearance(bookId: String) {
        validateBookId(bookId)
        load()
        val updated = document.copy(collection = document.collection.copy(
            bookAppearances = document.collection.bookAppearances - bookId,
        ), inheritedBooks = document.inheritedBooks + bookId)
        if (updated != document) save(updated)
    }

    @Synchronized
    fun markSeen() {
        load()
        val seen = document.collection.seen + document.collection.earned.keys
        if (seen != document.collection.seen) save(document.copy(collection = document.collection.copy(seen = seen)))
    }

    @Synchronized
    fun exportJson(): JSONObject {
        load()
        return encode(document)
    }

    /** Local explicit appearance choices win; restored overrides fill previously unconfigured books. */
    @Synchronized
    fun mergeBackup(json: JSONObject): RelicCollection {
        val restored = decode(json) // Validate the whole document before even loading/mutating local state.
        load()
        val local = document.collection
        val incoming = restored.collection
        val floors = document.readingFloor.toMutableMap()
        restored.readingFloor.forEach { (date, millis) -> floors[date] = maxOf(floors[date] ?: 0, millis) }
        val earned = local.earned.toMutableMap()
        incoming.earned.forEach { (id, time) -> earned[id] = earned[id]?.let { minOf(it, time) } ?: time }
        val total = maxOf(local.totalMillis, incoming.totalMillis, saturatedSum(floors.values))
        // Restore may provide enough historic time for another tier even when the older export did not
        // include that catalog item. It is retroactive credit, rather than a new reading celebration.
        MoonlitTier.entries.filter { total >= it.thresholdMillis && it.id !in earned }.forEach { earned[it.id] = 0 }
        val unlocked = earned.keys.mapNotNull(MoonlitTier::fromId).toSet()
        val appearance = if (document.defaultConfigured) local.defaultAppearance else incoming.defaultAppearance
        val locallyConfigured = local.bookAppearances.keys + document.inheritedBooks
        val choices = incoming.bookAppearances.filterKeys { it !in locallyConfigured } + local.bookAppearances
        val inherited = document.inheritedBooks + restored.inheritedBooks.filter { it !in locallyConfigured }
        val updated = document.copy(
            collection = RelicCollection(total, earned.toMap(), local.seen + incoming.seen,
                appearance.permitted(unlocked), choices.mapValues { it.value.permitted(unlocked) }),
            readingFloor = floors.toMap(),
            initialized = document.initialized || restored.initialized,
            defaultConfigured = document.defaultConfigured || restored.defaultConfigured,
            inheritedBooks = inherited,
        )
        if (updated != document) save(updated)
        return state.value
    }

    private fun save(updated: Document) {
        check(loaded) { "Load reading rewards before changing them" }
        require(updated.collection.bookAppearances.size + updated.inheritedBooks.size <= MAX_BOOK_CHOICES) { "Too many book appearances" }
        // Refuse unexpected outside changes instead of replacing an unknown earned collection.
        for ((path, before) in observedFiles) {
            val now = if (path.exists()) path.readBytes() else null
            if (!nullableBytesEqual(before, now)) throw RelicStoreException("Reading rewards changed outside this session; reopen BookCover before changing them.")
        }
        val next = updated.copy(revision = saturatedAdd(document.revision, 1))
        val bytes = encode(next).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_DOCUMENT_BYTES) { "Relic file is too large" }
        file.parentFile?.mkdirs()
        for (path in damaged) {
            // A read failure has no known bytes to preserve. Fail closed, keeping both originals.
            val original = observedFiles[path] ?: throw RelicStoreException("A reading rewards file cannot be read safely.")
            FileOutputStream(File(path.parentFile, "${path.name}.damaged-${clock()}-${java.util.UUID.randomUUID()}.json")).use {
                it.write(original); it.fd.sync()
            }
        }
        replace(backup, bytes)
        replace(file, bytes)
        document = next
        observedFiles = mapOf(file to bytes, backup to bytes)
        damaged = emptySet()
        state.value = next.collection
    }

    companion object {
        private const val MAX_DOCUMENT_BYTES = 4 * 1024 * 1024L
        private const val MAX_BOOK_CHOICES = 20_000

        /** Full structural/semantic validation, with no files or StateFlows touched. */
        fun validateBackup(json: JSONObject) { decode(json) }

        private fun decode(json: JSONObject): Document {
            require(strictLong(json, "version") == 1L) { "Unknown rewards version" }
            val total = strictLong(json, "totalMillis")
            val revision = strictLong(json, "revision")
            val initialized = strictBoolean(json, "initialized")
            val configured = strictBoolean(json, "defaultConfigured")
            val earnedJson = json.getJSONObject("earned")
            require(earnedJson.length() <= 256) { "Too many earned relics" }
            val earned = earnedJson.keys().asSequence().associateWith { id ->
                require(id.matches(Regex("[a-z][a-z0-9_-]{0,63}"))) { "Invalid relic identifier" }
                strictLong(earnedJson, id)
            }
            require(earned.keys.mapNotNull(MoonlitTier::fromId).all { total >= it.thresholdMillis }) { "Earned reward exceeds recorded reading time" }
            val seenJson = json.getJSONArray("seen")
            require(seenJson.length() <= 256) { "Too many seen relics" }
            val seen = (0 until seenJson.length()).map { index ->
                val value = seenJson.get(index)
                require(value is String && value in earned) { "Invalid seen relic" }
                value
            }.toSet()
            val readingDays = ReadingDayStore.validateBackup(json.getJSONObject("readingDays"))
            val floors = readingDays.days.mapValues { it.value.millis }
            require(total >= saturatedSum(floors.values)) { "Lifetime time is less than its reading history" }
            val unlocked = earned.keys.mapNotNull(MoonlitTier::fromId).toSet()
            val appearance = appearanceFromJson(json.getJSONObject("defaultAppearance")).permitted(unlocked)
            val booksJson = json.getJSONObject("bookAppearances")
            require(booksJson.length() <= MAX_BOOK_CHOICES) { "Too many book appearances" }
            val books = booksJson.keys().asSequence().associateWith { id ->
                validateBookId(id)
                appearanceFromJson(booksJson.getJSONObject(id)).permitted(unlocked)
            }
            val inherited = if (json.has("inheritedBooks")) {
                val values = json.getJSONArray("inheritedBooks")
                require(values.length() <= MAX_BOOK_CHOICES) { "Too many inherited book appearances" }
                (0 until values.length()).map { index ->
                    val value = values.get(index)
                    require(value is String) { "Invalid inherited book identifier" }
                    validateBookId(value)
                    value
                }.toSet()
            } else emptySet()
            require(books.size + inherited.size <= MAX_BOOK_CHOICES) { "Too many book appearances" }
            require(inherited.none { it in books }) { "A book cannot both inherit and override its appearance" }
            return Document(RelicCollection(total, earned, seen, appearance, books), floors, initialized, configured, revision, inherited)
        }

        private fun encode(document: Document): JSONObject = JSONObject().apply {
            put("version", 1); put("revision", document.revision); put("initialized", document.initialized)
            put("defaultConfigured", document.defaultConfigured); put("totalMillis", document.collection.totalMillis)
            put("earned", JSONObject().apply { document.collection.earned.toSortedMap().forEach { (id, time) -> put(id, time) } })
            put("seen", JSONArray(document.collection.seen.sorted()))
            put("defaultAppearance", appearanceJson(document.collection.defaultAppearance))
            put("bookAppearances", JSONObject().apply {
                document.collection.bookAppearances.toSortedMap().forEach { (id, appearance) -> put(id, appearanceJson(appearance)) }
            })
            put("inheritedBooks", JSONArray(document.inheritedBooks.sorted()))
            put("readingDays", floorJson(document.readingFloor))
        }

        private fun floorJson(floors: Map<LocalDate, Long>): JSONObject = JSONObject().put("version", 1).put("days", JSONObject().apply {
            floors.toSortedMap().forEach { (date, millis) -> put(date.toString(), JSONObject().put("millis", millis)
                .put("completed", millis >= com.bookcover.diagnostic.streak.DAILY_GOAL_MILLIS)) }
        })

        private fun appearanceJson(value: RelicAppearance): JSONObject = JSONObject().apply {
            put("enabled", value.enabled); put("mode", value.mode.name); put("colorMode", value.colorMode.name)
            put("customAccent", value.customAccent); put("edgeTier", value.edgeTier?.id ?: JSONObject.NULL)
            put("bookplate", value.bookplate.name); put("seal", value.seal?.id ?: JSONObject.NULL)
            put("motion", value.motion); put("inscription", value.inscription)
        }

        private fun appearanceFromJson(json: JSONObject): RelicAppearance {
            fun tier(key: String): MoonlitTier? {
                if (json.isNull(key)) return null
                val id = json.get(key)
                require(id is String) { "Invalid relic tier" }
                return requireNotNull(MoonlitTier.fromId(id)) { "Unknown relic tier" }
            }
            val accent = json.get("customAccent")
            require(accent is Number && accent.toDouble() == accent.toInt().toDouble()) { "Invalid custom accent" }
            val inscription = json.get("inscription")
            require(inscription is String && inscription.length <= 1_000) { "Invalid inscription" }
            return RelicAppearance(strictBoolean(json, "enabled"), RelicMode.valueOf(json.getString("mode")),
                RelicColorMode.valueOf(json.getString("colorMode")), accent.toInt(), tier("edgeTier"),
                RelicBookplate.valueOf(json.getString("bookplate")), tier("seal"), strictBoolean(json, "motion"), inscription)
        }

        private fun strictLong(json: JSONObject, key: String): Long {
            val value = json.get(key)
            require(value is Byte || value is Short || value is Int || value is Long) { "Invalid whole-number $key" }
            return (value as Number).toLong().also { require(it >= 0) { "Negative $key" } }
        }

        private fun strictBoolean(json: JSONObject, key: String): Boolean = json.get(key).let {
            require(it is Boolean) { "Invalid boolean $key" }; it
        }

        private fun validateBookId(id: String) { require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid book identifier" } }
        private fun saturatedSum(values: Collection<Long>): Long = values.fold(0L, ::saturatedAdd)
        private fun saturatedAdd(a: Long, b: Long): Long = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
        private fun nullableBytesEqual(a: ByteArray?, b: ByteArray?): Boolean = if (a == null || b == null) a == null && b == null else a.contentEquals(b)

        private fun replace(target: File, bytes: ByteArray) {
            val parent = requireNotNull(target.absoluteFile.parentFile) { "A rewards file needs a parent directory" }
            val temp = Files.createTempFile(parent.toPath(), "${target.name}.", ".tmp").toFile()
            try {
                FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally { temp.delete() }
        }
    }
}
