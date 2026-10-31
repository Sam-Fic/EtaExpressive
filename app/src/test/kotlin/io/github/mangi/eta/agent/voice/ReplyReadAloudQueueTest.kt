package io.github.mangi.eta.agent.voice

import org.junit.Assert.*
import org.junit.Test

class ReplyReadAloudQueueTest {

    private fun line(id: String, text: String) = ReadAloudUtterance(id, text)

    @Test fun itemsLeaveInEnqueueOrder() {
        val queue = ReplyReadAloudQueue()
        queue.enqueueAll(listOf(line("m1", "一。"), line("m1", "二。")))
        assertEquals(2, queue.size)
        assertFalse(queue.isEmpty)
        assertEquals("一。", queue.peek()?.text)
        assertEquals(2, queue.size)
        assertEquals("一。", queue.take()?.text)
        assertEquals("二。", queue.take()?.text)
        assertNull(queue.take())
        assertNull(queue.peek())
        assertTrue(queue.isEmpty)
    }

    @Test fun dropStaleKeepsOnlyTheNewestSegment() {
        val queue = ReplyReadAloudQueue()
        queue.enqueueAll(listOf(line("m1", "旧一。"), line("m1", "旧二。")))
        queue.enqueueAll(listOf(line("m2", "新一。")))
        assertEquals(2, queue.dropStale("m2"))
        assertEquals(listOf("新一。"), List(queue.size) { queue.take()!!.text })
    }

    @Test fun reconnectIdKeepsSentencesOfTheSameSegment() {
        val queue = ReplyReadAloudQueue()
        queue.enqueueAll(listOf(line("m1", "一。"), line("m1-reconnect-3:abc", "二。"), line("m2", "三。")))
        assertEquals(1, queue.dropStale("m1-reconnect-9:zzz"))
        assertEquals(setOf("m1"), queue.segments())
    }

    @Test fun nullKeepIsEquivalentToClearing() {
        val queue = ReplyReadAloudQueue()
        queue.enqueueAll(listOf(line("m1", "一。"), line("m2", "二。")))
        assertEquals(2, queue.dropStale(null))
        assertTrue(queue.isEmpty)
    }

    @Test fun clearReturnsWhatItRemovedAndTakenItemsNeverComeBack() {
        val queue = ReplyReadAloudQueue()
        queue.enqueueAll(listOf(line("m1", "一。"), line("m1", "二。"), line("m1", "三。")))
        queue.take()
        assertEquals(2, queue.clear())
        assertTrue(queue.isEmpty)
        queue.enqueueAll(listOf(line("m1", "一。")))
        assertEquals(1, queue.size)
    }

    @Test fun segmentsReportsNormalizedIdentities() {
        val queue = ReplyReadAloudQueue()
        queue.enqueueAll(listOf(line("m1-reconnect-1:a", "一。"), line("m2", "二。")))
        assertEquals(setOf("m1", "m2"), queue.segments())
    }
}
