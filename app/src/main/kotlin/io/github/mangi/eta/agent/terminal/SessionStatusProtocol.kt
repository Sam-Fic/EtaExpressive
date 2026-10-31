package io.github.mangi.eta.agent.terminal

import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * 持久 shell 会话的状态行协议：每条命令结束后向同一 stdin 追加一个随机 marker 的 printf，
 * 输出退出码与执行后的 PWD。宿主侧据此切分输出、跟踪 cwd；marker 含随机 UUID，
 * 正常命令输出不会与之混淆。
 */
internal object SessionStatusProtocol {

    fun newMarker(): String = "__ETA_STATUS_${UUID.randomUUID().toString().replace("-", "")}"

    fun statusCommand(marker: String): String =
        "printf '\\n$marker:%s:%s\\n' \"\$?\" \"\$PWD\""

    /**
     * 单逻辑行协议：命令经 eval 执行，状态 printf 与命令在同一行，由 shell 在命令退出后自己输出。
     * 状态行不作为独立行进入 stdin——交互式命令（read、REPL 等）读 stdin 时不会吃掉标记，
     * 会话运行期间写入的用户输入也完整留给前台进程。
     */
    fun commandLine(marker: String, command: String): String =
        "eval ${shellQuote(command)}; eta_ec=\$?; printf '\\n$marker:%s:%s\\n' \"\$eta_ec\" \"\$PWD\""

    fun isStatusLine(line: String, marker: String): Boolean = line.startsWith("$marker:")

    /** 解析状态行；cwd 为空（空行段）时返回 null，由调用方回退到会话当前 cwd。 */
    fun parseStatusLine(line: String, marker: String): Status? {
        if (!isStatusLine(line, marker)) return null
        val status = line.removePrefix("$marker:")
        val separator = status.indexOf(':')
        if (separator <= 0) return Status(exitCode = -1, cwd = null)
        return Status(
            exitCode = status.take(separator).toIntOrNull() ?: -1,
            cwd = status.drop(separator + 1).ifBlank { null },
        )
    }

    data class Status(val exitCode: Int, val cwd: String?)
}

/**
 * 有界输出收集器：读取线程持续排空管道，超过上限后丢弃后续内容并标记截断。
 *
 * 自管缓冲而不是 [java.io.ByteArrayOutputStream]：终端每 50ms 就要取一次增量，
 * 而 ByteArrayOutputStream 只能整段 `toByteArray()`，1MB 缓冲下每次取增量都是一次全量
 * 拷贝 + 解码（每秒约 20 次），GC 压力直接把主线程拖到掉帧。这里按字节偏移切分，
 * 读多少付多少。
 */
internal class ByteArrayOutputCollector {
    private val lock = Any()
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var size = 0
    private var totalBytesRead = 0L
    private var truncated = false

    fun readFrom(input: InputStream, maxBytes: Int = Int.MAX_VALUE) {
        try {
            val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                synchronized(lock) {
                    totalBytesRead += read.toLong()
                    val allowed = (maxBytes - size).coerceAtLeast(0)
                    if (allowed > 0) {
                        append(chunk, read.coerceAtMost(allowed))
                    }
                    if (read > allowed) {
                        truncated = true
                    }
                }
            }
        } catch (_: IOException) {
            // 读端断开：由会话退出检测统一上报，不能让读线程抛异常。
        }
    }

    private fun append(source: ByteArray, length: Int) {
        ensureCapacity(size + length)
        System.arraycopy(source, 0, buffer, size, length)
        size += length
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var capacity = buffer.size
        while (capacity < required) capacity = capacity shl 1
        buffer = buffer.copyOf(capacity)
    }

    fun bytes(): ByteArray = synchronized(lock) { buffer.copyOf(size) }

    fun text(): String = bytes().decodeToString()

    /** 已收集字节数；配合 [newTextSince] 做增量读取。 */
    fun sizeBytes(): Int = synchronized(lock) { size }

    /**
     * 从字节偏移 [offset] 起新增的完整文本，以及新的字节偏移。
     *
     * 只解码新增段。末尾被切断的多字节序列扣到下一次——否则流式输出会在汉字/emoji
     * 中间解出替换字符。返回的偏移永远落在 UTF-8 字符边界上。
     */
    fun newTextSince(offset: Int): Pair<Int, String> = synchronized(lock) {
        val start = offset.coerceIn(0, size)
        if (start >= size) return@synchronized start to ""
        var end = size
        // 往回找到最后一个字符的首字节，判断它是否已被完整写入。
        var lead = size - 1
        var guard = 0
        while (lead > start && guard < 3 && (buffer[lead].toInt() and 0xC0) == 0x80) {
            lead--
            guard++
        }
        if (lead + utf8SequenceLength(buffer[lead].toInt() and 0xFF) > size) end = lead
        if (end <= start) return@synchronized start to ""
        end to buffer.copyOfRange(start, end).decodeToString()
    }

    fun totalBytesRead(): Long = synchronized(lock) { totalBytesRead }

    fun isTruncated(): Boolean = synchronized(lock) { truncated }

    fun clear() {
        synchronized(lock) {
            size = 0
            totalBytesRead = 0
            truncated = false
            // 大输出结束后释放高水位缓冲，避免会话长期占着 1MB。
            if (buffer.size > KEEP_CAPACITY) buffer = ByteArray(INITIAL_CAPACITY)
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 4 * 1024
        const val KEEP_CAPACITY = 64 * 1024
    }
}

/** UTF-8 首字节对应的序列长度；非法首字节按 1 处理，由解码兜底。 */
private fun utf8SequenceLength(leadByte: Int): Int = when {
    leadByte and 0x80 == 0x00 -> 1
    leadByte and 0xE0 == 0xC0 -> 2
    leadByte and 0xF0 == 0xE0 -> 3
    leadByte and 0xF8 == 0xF0 -> 4
    else -> 1
}

/**
 * 流式 UTF-8 解码器：读线程按任意字节边界切块，多字节字符可能被劈在两块里，
 * 每块直接 String(chunk) 会在汉字/emoji 处解出替换字符（控制台中文输出乱码）。
 * 只解以完整字符结尾的前缀，残缺尾部（至多 3 字节）扣到下一块与它拼接。
 */
internal class Utf8StreamDecoder {
    private val lock = Any()
    private var pending = ByteArray(0)
    private var pendingSize = 0

    /** 解码 [chunk]（连同上一次扣下的残缺尾部）中完整可解的部分。 */
    fun decode(chunk: ByteArray): String = synchronized(lock) {
        if (pendingSize == 0) {
            decodeComplete(chunk, 0, chunk.size)
        } else {
            val merged = ByteArray(pendingSize + chunk.size)
            System.arraycopy(pending, 0, merged, 0, pendingSize)
            System.arraycopy(chunk, 0, merged, pendingSize, chunk.size)
            pendingSize = 0
            decodeComplete(merged, 0, merged.size)
        }
    }

    private fun decodeComplete(bytes: ByteArray, start: Int, end: Int): String {
        if (start >= end) return ""
        val cut = completeLength(bytes, end)
        if (cut < end) {
            carry(bytes, cut, end)
            return if (cut > start) bytes.decodeToString(start, cut) else ""
        }
        return bytes.decodeToString(start, end)
    }

    private fun carry(bytes: ByteArray, from: Int, to: Int) {
        val length = to - from
        if (pending.size < length) pending = ByteArray(length)
        System.arraycopy(bytes, from, pending, 0, length)
        pendingSize = length
    }

    /** [end] 之前完整 UTF-8 序列的截止长度；末尾残缺序列不计入。 */
    private fun completeLength(bytes: ByteArray, end: Int): Int {
        var lead = end - 1
        while (lead >= 0 && (bytes[lead].toInt() and 0xC0) == 0x80) lead--
        // 首字节不在本段内（首块就从字符中间开始）：按完整解码，由解码器替换坏字节，不会卡死。
        if (lead < 0) return end
        return if (end - lead >= utf8SequenceLength(bytes[lead].toInt() and 0xFF)) end else lead
    }
}
