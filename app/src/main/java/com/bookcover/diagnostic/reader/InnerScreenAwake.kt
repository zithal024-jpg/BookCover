package com.bookcover.diagnostic.reader

import android.content.SharedPreferences

/** BC-22: how long the inner screen stays on after the reader's last touch or key. */
internal enum class InnerScreenIdle(val id: String, val minutes: Int?, val label: String) {
    TWO("2", 2, "2 min"), FIVE("5", 5, "5 min"), TEN("10", 10, "10 min"), ALWAYS("always", null, "Always");

    val millis: Long? get() = minutes?.let { it * 60_000L }

    companion object {
        val DEFAULT = FIVE
        fun fromId(id: String?): InnerScreenIdle = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

internal const val INNER_SCREEN_IDLE_KEY = "innerScreenIdle"
internal fun SharedPreferences.innerScreenIdle() = InnerScreenIdle.fromId(getString(INNER_SCREEN_IDLE_KEY, null))

/**
 * Decides when the reading window holds the inner screen on, using the monotonic
 * [android.os.SystemClock.elapsedRealtime] clock passed in as `now`. Only [input] renews the interval;
 * opening a book counts as input, while recreation and resuming restore the earlier deadline instead of
 * starting a new one. After expiry the hold is released and Android's own timeout applies.
 *
 * Every decision carries a token; a scheduled check with an older token is stale and ignored.
 */
internal class InnerScreenAwake(idle: InnerScreenIdle) {
    /** [keepScreenOn] is the window flag to apply; check again after [checkAfterMillis] with [token]. */
    data class Decision(val keepScreenOn: Boolean, val checkAfterMillis: Long?, val token: Int)

    var idle = idle; private set
    /** The last genuine input, if any; kept across pause and saved across recreation. */
    var lastInput: Long? = null; private set
    var reading = false; private set
    private var resumed = false
    var token = 0; private set

    /** A touch or key that reached the activity. Outside reading it is only remembered. */
    fun input(now: Long): Decision {
        lastInput = maxOf(lastInput ?: now, now)
        return decide(now)
    }

    /** The reader chose a book: that choice is activity, so the interval starts now. */
    fun opened(now: Long): Decision {
        reading = true
        return input(now)
    }

    /** A book reopened after recreation keeps its earlier deadline; nothing here counts as input. */
    fun restored(savedLastInput: Long?, now: Long): Decision {
        reading = true
        // elapsedRealtime restarts at boot, so a later value cannot be from this boot: treat it as expired.
        lastInput = savedLastInput?.takeIf { it in 0..now }
        return decide(now)
    }

    fun closed(now: Long): Decision {
        reading = false
        return decide(now)
    }

    fun paused(now: Long): Decision {
        resumed = false
        return decide(now)
    }

    fun resumed(now: Long): Decision {
        resumed = true
        return decide(now)
    }

    fun choose(choice: InnerScreenIdle, now: Long): Decision {
        idle = choice
        return decide(now)
    }

    /** A scheduled check; null when a newer decision has replaced the one that scheduled it. */
    fun check(scheduled: Int, now: Long): Decision? = if (scheduled == token) decide(now) else null

    private fun decide(now: Long): Decision {
        token += 1
        if (!reading || !resumed) return Decision(false, null, token)
        val limit = idle.millis ?: return Decision(true, null, token)
        val remaining = lastInput?.let { it + limit - now } ?: 0L
        return if (remaining > 0) Decision(true, remaining, token) else Decision(false, null, token)
    }
}
