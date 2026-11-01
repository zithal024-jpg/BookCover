package com.bookcover.diagnostic.cover

/** Preview owns its own request; editing never replaces a reader's original timer or Stop choice. */
internal class CoverPreviewContinuation {
    val reading = CoverSessionContinuation()
    private val preview = CoverSessionContinuation()
    var previewing = false
        private set
    val current: CoverSessionContinuation get() = if (previewing) preview else reading

    /** Returns true only for the first draft, so slider/tier changes do not restart the session. */
    fun begin(now: Long): Boolean {
        if (previewing) return false
        previewing = true
        preview.start(0, now)
        return true
    }

    fun end() {
        preview.stop()
        previewing = false
    }

    fun stop() {
        end()
        reading.stop()
    }
}
