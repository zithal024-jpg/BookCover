package com.bookcover.diagnostic.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Saves only the newest submitted value, once submissions pause for [quietMillis]. [flush] saves a
 * pending value immediately. A save that has started always finishes. Call from one thread (main).
 */
internal class LatestValueSaver<T : Any>(
    private val scope: CoroutineScope,
    private val quietMillis: Long,
    private val save: suspend (T) -> Unit,
) {
    private var pending: T? = null
    private var waiting: Job? = null

    fun submit(value: T) {
        pending = value
        waiting?.cancel()
        waiting = scope.launch { delay(quietMillis); saveNow() }
    }

    fun flush() {
        waiting?.cancel()
        waiting = null
        if (pending != null) scope.launch { saveNow() }
    }

    private suspend fun saveNow() {
        val value = pending ?: return
        pending = null
        withContext(NonCancellable) { save(value) }
    }
}
