package com.bookcover.diagnostic

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [write] on [executor] instead of the calling thread. Requests that arrive while a write is
 * already queued are merged into it; a request made during a write queues one more.
 */
internal class CoalescingWriter(private val executor: Executor, private val write: () -> Unit) {
    private val queued = AtomicBoolean(false)

    fun request() {
        if (queued.compareAndSet(false, true)) executor.execute { queued.set(false); write() }
    }
}
