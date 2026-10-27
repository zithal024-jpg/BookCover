package com.bookcover.diagnostic.reader

/** A semantic passage held across one burst of viewport changes; explicit navigation always wins. */
internal class ReadingViewportContinuity<T : Any> {
    var anchor: T? = null
        private set
    var pending: T? = null
        private set
    var generation = 0L
        private set

    fun remember(locator: T, capturedGeneration: Long) {
        // A DOM query may return after the user has navigated or resizing has already begun.
        if (capturedGeneration == generation && pending == null) anchor = locator
    }

    fun begin(fallback: T?) {
        if (pending == null) {
            pending = anchor ?: fallback
            generation++
        }
    }

    fun navigated() {
        generation++
        anchor = null
        pending = null
    }

    fun complete(expectedGeneration: Long): T? {
        if (expectedGeneration != generation) return null
        return pending.also { if (it != null) anchor = it; pending = null }
    }
}
