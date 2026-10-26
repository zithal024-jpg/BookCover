package com.bookcover.diagnostic

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Test

class CoalescingWriterTest {
    private class QueueExecutor : Executor {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    }

    @Test fun requestDoesNotWriteOnTheCallingThread() {
        val executor = QueueExecutor()
        var writes = 0
        val writer = CoalescingWriter(executor) { writes++ }
        writer.request()
        assertEquals(0, writes)
        executor.runAll()
        assertEquals(1, writes)
    }

    @Test fun aBurstOfRequestsBecomesOneWrite() {
        val executor = QueueExecutor()
        var writes = 0
        val writer = CoalescingWriter(executor) { writes++ }
        repeat(50) { writer.request() }
        assertEquals(1, executor.queue.size)
        executor.runAll()
        assertEquals(1, writes)
    }

    @Test fun aRequestDuringAWriteIsNotLost() {
        val executor = QueueExecutor()
        var writes = 0
        lateinit var writer: CoalescingWriter
        writer = CoalescingWriter(executor) { writes++; if (writes == 1) writer.request() }
        writer.request()
        executor.runAll()
        assertEquals(2, writes)
    }
}
