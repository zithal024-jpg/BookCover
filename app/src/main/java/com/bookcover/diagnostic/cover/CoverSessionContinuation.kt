package com.bookcover.diagnostic.cover

/** The user's cover choice and original timer survive temporary display unavailability. */
internal class CoverSessionContinuation {
    var requested = false
        private set
    var deadline = 0L
        private set
    var resumePending = false
        private set
    private var displayChanging = false
    private var retried = false

    fun start(timerMinutes: Int, now: Long) {
        requested = true
        resumePending = true
        deadline = if (timerMinutes > 0) now + timerMinutes * 60_000L else 0L
    }

    fun restore(wasRequested: Boolean, savedDeadline: Long, now: Long) {
        requested = wasRequested && (savedDeadline == 0L || savedDeadline > now)
        deadline = if (requested) savedDeadline else 0L
        resumePending = requested
    }

    fun displayChanging(suspended: Boolean = false) {
        if (!displayChanging) retried = false
        displayChanging = true
        if (requested && suspended) resumePending = true
    }

    fun displaySettled() { displayChanging = false }
    fun presenting() { resumePending = false }

    /** An unsolicited end waits for an explicit display transition; errors never loop prompts. */
    fun systemEnded(failed: Boolean): Boolean {
        if (!requested || failed || retried) { stop(); return false }
        if (!displayChanging) { resumePending = false; return false }
        retried = true
        resumePending = true
        return true
    }

    fun stop() { requested = false; deadline = 0L; resumePending = false; displayChanging = false; retried = false }

    fun remaining(now: Long): Long? {
        if (!requested || deadline == 0L) return null
        return (deadline - now).coerceAtLeast(0L)
    }
}
