package com.bookcover.diagnostic.rewards

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** A separate, expiring appearance profile: these are usable tiers, never earned rewards. */
internal data class RelicTestingState(
    val startedAtMillis: Long = 0,
    val expiresAtMillis: Long = 0,
    val defaultAppearance: RelicAppearance = RelicAppearance(),
    val bookAppearances: Map<String, RelicAppearance> = emptyMap(),
) {
    fun isActive(now: Long): Boolean = startedAtMillis > 0 && now >= startedAtMillis &&
        expiresAtMillis > now && expiresAtMillis - startedAtMillis in 1..RELIC_TEST_DURATION_MILLIS
}

internal const val RELIC_TEST_DURATION_MILLIS = 24 * 60 * 60_000L
private val ALL_TEST_TIERS = MoonlitTier.entries.toSet()

/** Kept separate so the complete expiry/isolation contract can be checked without Android. */
internal interface RelicTestPersistence {
    fun read(): String?
    fun write(value: String?): Boolean
}

/**
 * Debug/QA owner testing only. Nothing is written to RelicStore, ReadingDayStore or their backups.
 * Beginning a session snapshots appearance choices, and all testing Saves affect only this profile.
 * The host calls refresh on resume and at expiresAtMillis, so a visible cover returns to earned
 * settings at expiry. Every read and write also checks the clock rather than trusting the UI.
 * Mutations synchronously persist their small local preference; call them off the UI thread.
 */
internal class RelicTestAccess internal constructor(
    val supported: Boolean,
    private val persistence: RelicTestPersistence,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        PreferencePersistence(context.applicationContext.getSharedPreferences("moonlit-testing", Context.MODE_PRIVATE)),
    )

    private val mutableState = MutableStateFlow(readActiveState())
    val state: StateFlow<RelicTestingState> = mutableState.asStateFlow()

    @Synchronized
    fun begin(collection: RelicCollection) {
        check(supported) { "Temporary reward testing is unavailable in this build." }
        refresh()
        // Reopening the controls does not extend an existing testing window indefinitely.
        if (mutableState.value.isActive(clock())) return
        val now = clock()
        require(now > 0 && now <= Long.MAX_VALUE - RELIC_TEST_DURATION_MILLIS) { "Invalid testing time" }
        require(collection.bookAppearances.size <= MAX_BOOK_CHOICES) { "Too many book appearances" }
        val next = RelicTestingState(now, now + RELIC_TEST_DURATION_MILLIS,
            collection.defaultAppearance.permitted(ALL_TEST_TIERS),
            collection.bookAppearances.mapValues { (id, appearance) ->
                validateBookId(id)
                appearance.permitted(ALL_TEST_TIERS)
            })
        persist(next)
    }

    @Synchronized
    fun end() {
        check(persistence.write(null)) { "Temporary testing could not be ended. Please try again." }
        mutableState.value = RelicTestingState()
    }

    /** Expired or clock-reversed access fails closed even if stale preference cleanup fails. */
    @Synchronized
    fun refresh() {
        val current = mutableState.value
        if (current.startedAtMillis != 0L && (!supported || !current.isActive(clock()))) {
            mutableState.value = RelicTestingState()
            runCatching { persistence.write(null) }
        }
    }

    @Synchronized
    fun saveAppearance(bookId: String?, appearance: RelicAppearance) {
        val current = requireActive()
        bookId?.let(::validateBookId)
        val permitted = appearance.permitted(ALL_TEST_TIERS)
        val next = if (bookId == null) current.copy(defaultAppearance = permitted)
        else current.copy(bookAppearances = current.bookAppearances + (bookId to permitted))
        require(next.bookAppearances.size <= MAX_BOOK_CHOICES) { "Too many book appearances" }
        persist(next)
    }

    @Synchronized
    fun resetBookAppearance(bookId: String) {
        validateBookId(bookId)
        val current = requireActive()
        persist(current.copy(bookAppearances = current.bookAppearances - bookId))
    }

    /** Earned/seen/time always come from the real collection, including reading earned while testing. */
    @Synchronized
    fun editingCollection(collection: RelicCollection): RelicCollection {
        refresh()
        val current = mutableState.value
        return if (supported && current.isActive(clock())) collection.copy(
            defaultAppearance = current.defaultAppearance,
            bookAppearances = current.bookAppearances,
        ) else collection
    }

    @Synchronized
    fun availableTiers(collection: RelicCollection): Set<MoonlitTier> {
        refresh()
        return if (supported && mutableState.value.isActive(clock())) ALL_TEST_TIERS else collection.unlocked
    }

    @Synchronized
    fun appearanceFor(collection: RelicCollection, bookId: String?): RelicAppearance =
        editingCollection(collection).appearanceFor(bookId, availableTiers(collection))

    private fun requireActive(): RelicTestingState {
        refresh()
        check(supported && mutableState.value.isActive(clock())) {
            "Temporary reward testing has ended. Reopen the cabinet before saving an appearance."
        }
        return mutableState.value
    }

    private fun persist(next: RelicTestingState) {
        val json = encode(next).toString()
        require(json.length <= MAX_DOCUMENT_CHARS) { "Testing appearance choices are too large" }
        if (!persistence.write(json)) throw IOException("Your testing appearance could not be saved. Please try again.")
        mutableState.value = next
    }

    private fun readActiveState(): RelicTestingState {
        if (!supported) return RelicTestingState()
        return runCatching {
            val raw = persistence.read() ?: return RelicTestingState()
            require(raw.length <= MAX_DOCUMENT_CHARS) { "Testing document is too large" }
            decode(JSONObject(raw)).takeIf { it.isActive(clock()) } ?: RelicTestingState()
        }.getOrElse { RelicTestingState() }
    }

    private class PreferencePersistence(private val preferences: SharedPreferences) : RelicTestPersistence {
        override fun read(): String? = preferences.getString("session", null)
        override fun write(value: String?): Boolean = preferences.edit().run {
            if (value == null) remove("session") else putString("session", value)
            commit()
        }
    }

    companion object {
        private const val MAX_BOOK_CHOICES = 20_000
        private const val MAX_DOCUMENT_CHARS = 4 * 1024 * 1024

        private fun encode(state: RelicTestingState): JSONObject = JSONObject().apply {
            put("version", 1)
            put("startedAtMillis", state.startedAtMillis)
            put("expiresAtMillis", state.expiresAtMillis)
            put("defaultAppearance", appearanceJson(state.defaultAppearance))
            put("bookAppearances", JSONObject().apply {
                state.bookAppearances.toSortedMap().forEach { (id, appearance) -> put(id, appearanceJson(appearance)) }
            })
        }

        private fun decode(json: JSONObject): RelicTestingState {
            require(strictLong(json, "version") == 1L) { "Unknown testing version" }
            val start = strictLong(json, "startedAtMillis")
            val end = strictLong(json, "expiresAtMillis")
            require(start > 0 && end > start && end - start <= RELIC_TEST_DURATION_MILLIS) { "Invalid testing period" }
            val books = json.getJSONObject("bookAppearances")
            require(books.length() <= MAX_BOOK_CHOICES) { "Too many book appearances" }
            return RelicTestingState(start, end, appearanceFromJson(json.getJSONObject("defaultAppearance")),
                books.keys().asSequence().associateWith { id ->
                    validateBookId(id)
                    appearanceFromJson(books.getJSONObject(id))
                })
        }

        private fun appearanceJson(value: RelicAppearance): JSONObject = JSONObject().apply {
            put("enabled", value.enabled); put("mode", value.mode.name); put("colorMode", value.colorMode.name)
            put("customAccent", value.customAccent); put("edgeTier", value.edgeTier?.id ?: JSONObject.NULL)
            put("bookplate", value.bookplate.name); put("seal", value.seal?.id ?: JSONObject.NULL)
            put("motion", value.motion); put("inscription", value.inscription)
        }

        private fun appearanceFromJson(json: JSONObject): RelicAppearance {
            fun tier(key: String): MoonlitTier? {
                if (json.isNull(key)) return null
                val value = json.get(key)
                require(value is String) { "Invalid relic tier" }
                return requireNotNull(MoonlitTier.fromId(value)) { "Unknown relic tier" }
            }
            val accent = json.get("customAccent")
            require(accent is Number && accent.toDouble() == accent.toInt().toDouble()) { "Invalid custom accent" }
            val inscription = json.get("inscription")
            require(inscription is String && inscription.length <= 1_000) { "Invalid inscription" }
            return RelicAppearance(strictBoolean(json, "enabled"), RelicMode.valueOf(json.getString("mode")),
                RelicColorMode.valueOf(json.getString("colorMode")), accent.toInt(), tier("edgeTier"),
                RelicBookplate.valueOf(json.getString("bookplate")), tier("seal"), strictBoolean(json, "motion"), inscription)
                .permitted(ALL_TEST_TIERS)
        }

        private fun strictLong(json: JSONObject, key: String): Long = json.get(key).let {
            require(it is Byte || it is Short || it is Int || it is Long) { "Invalid whole-number $key" }
            (it as Number).toLong().also { value -> require(value >= 0) { "Negative $key" } }
        }

        private fun strictBoolean(json: JSONObject, key: String): Boolean = json.get(key).let {
            require(it is Boolean) { "Invalid boolean $key" }; it
        }

        private fun validateBookId(id: String) {
            require(id.matches(Regex("[a-f0-9]{64}"))) { "Invalid book identifier" }
        }
    }
}
