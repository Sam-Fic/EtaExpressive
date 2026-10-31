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

    @Test fun streamDecoderHoldsBackSplitUtf8AcrossChunks() {
        val decoder = Utf8StreamDecoder()
        val bytes = "中文".toByteArray()

        // 每次一个字节地喂：残缺尾部扣住，拼齐后解出完整字符，全程不出现替换字符。
        val output = buildString {
            bytes.forEach { byte -> append(decoder.decode(byteArrayOf(byte))) }
        }
        assertEquals("中文", output)
    }

    @Test fun streamDecoderDecodesFourByteEmojiSplitAcrossChunks() {
        val decoder = Utf8StreamDecoder()
        val bytes = "a🚀b".toByteArray()

        val output = buildString {
            bytes.forEach { byte -> append(decoder.decode(byteArrayOf(byte))) }
        }
        assertEquals("a🚀b", output)
    }

    @Test fun streamDecoderPassesAsciiChunksThrough() {
        val decoder = Utf8StreamDecoder()

        assertEquals("hello ", decoder.decode("hello ".toByteArray()))
        assertEquals("world", decoder.decode("world".toByteArray()))
    }

    @Test fun streamDecoderDoesNotStallOnInvalidBytes() {
        val decoder = Utf8StreamDecoder()

        // 非法首字节按完整序列处理：解码出替换字符但不卡住后续输出。
        assertTrue(decoder.decode(byteArrayOf(0xFF.toByte())).isNotEmpty())
        assertEquals("ok", decoder.decode("ok".toByteArray()))
    }

    @Test fun streamDecoderCarriesTailAcrossMixedChunks() {
        val decoder = Utf8StreamDecoder()
        val cjk = "中".toByteArray()

        // ASCII 与被劈开的多字节字符混排：残缺尾部只扣 3 字节以内，不吞正文。
        assertEquals("id=", decoder.decode("id=".toByteArray()))
        assertEquals("", decoder.decode(cjk.copyOf(2)))
        assertEquals("中;path", decoder.decode(cjk.copyOfRange(2, cjk.size) + ";path".toByteArray()))
    }
}
