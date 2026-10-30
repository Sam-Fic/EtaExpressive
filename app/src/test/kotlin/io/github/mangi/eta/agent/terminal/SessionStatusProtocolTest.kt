package io.github.mangi.eta.agent.terminal

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStatusProtocolTest {
    @Test fun outputCollectorBoundsStorageWhileDrainingInput() {
        val collector = ByteArrayOutputCollector()
        val source = "0123456789".toByteArray()

        collector.readFrom(ByteArrayInputStream(source), maxBytes = 4)

        assertArrayEquals(source.copyOf(4), collector.bytes())
        assertEquals(source.size.toLong(), collector.totalBytesRead())
        assertTrue(collector.isTruncated())
    }

    @Test fun incrementalReadOnlyReturnsTextThatArrivedAfterOffset() {
        val collector = ByteArrayOutputCollector()
        collector.readFrom(ByteArrayInputStream("hello".toByteArray()))

        val first = collector.newTextSince(0)
        assertEquals("hello", first.second)
        assertEquals(5, first.first)

        collector.readFrom(ByteArrayInputStream(" world".toByteArray()))

        val second = collector.newTextSince(first.first)
        assertEquals(" world", second.second)
        assertEquals(collector.sizeBytes(), second.first)
        // 没有新字节时返回空串且偏移不变，调用方可以每轮无脑取增量。
        assertEquals("", collector.newTextSince(second.first).second)
    }

    @Test fun incrementalReadHoldsBackSplitUtf8Sequence() {
        val collector = ByteArrayOutputCollector()
        val bytes = "中".toByteArray()

        // 只到前两个字节：半个字符不能交付，否则流式输出会显示替换字符。
        collector.readFrom(ByteArrayInputStream(bytes.copyOf(2)))
        assertEquals("", collector.newTextSince(0).second)

        collector.readFrom(ByteArrayInputStream(bytes.copyOfRange(2, bytes.size)))
        assertEquals("中", collector.newTextSince(0).second)
    }

    @Test fun clearReleasesCollectedBytes() {
        val collector = ByteArrayOutputCollector()
        collector.readFrom(ByteArrayInputStream("payload".toByteArray()))

        collector.clear()

        assertEquals(0, collector.sizeBytes())
        assertEquals("", collector.text())
        assertFalse(collector.isTruncated())
    }
}
