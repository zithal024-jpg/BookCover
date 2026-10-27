package com.bookcover.diagnostic

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Activity-owned work state; use on the main thread, including each operation's start and finish. */
internal class OperationState {
    private var activeCount by mutableIntStateOf(0)
    private var currentMessage by mutableStateOf("Preparing your library…")
    private var hasResult = false

    val busy: Boolean get() = activeCount > 0

    var message: String
        get() = currentMessage
        set(value) {
            hasResult = true
            currentMessage = value
        }

    /** A normal startup hint must not hide a result or error from another operation. */
    fun publishInitialMessage(value: String) {
        if (!hasResult) currentMessage = value
    }

    suspend fun <T> run(block: suspend () -> T): T {
        activeCount++
        try {
            return block()
        } finally {
            activeCount--
        }
    }
}
