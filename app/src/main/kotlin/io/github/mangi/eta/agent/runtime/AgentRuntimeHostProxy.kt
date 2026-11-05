package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.net.Uri
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.XiaomiHostToolCatalog
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.io.FileOutputStream
import java.net.URLConnection
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Synchronous model-tool facade over the bidirectional Messenger channel to an injected host. */
internal class AgentRuntimeHostProxy(
    context: Context,
    private val hostMessenger: Messenger,
    capabilities: Set<String>,
    directTools: Boolean,
    sensitiveActionTools: Boolean,
    private val logger: AgentLogger,
) : AgentHostToolExecutor {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<String, PendingCall>()

    override val toolNames: Set<String> = XiaomiHostToolCatalog.namesFor(
        capabilities = capabilities,
        directTools = directTools,
        sensitiveActionTools = sensitiveActionTools,
    )

    /** 本地多 run 并发扩展：service 侧用它把异步结果路由到正确的 run 代理。 */
    fun isTracking(callId: String): Boolean = pending.containsKey(callId)

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (closed.get()) return errorResult("HOST_BRIDGE_CLOSED", "小米宿主桥已关闭")
        if (toolCall.name !in toolNames) {
            return errorResult("HOST_CAPABILITY_UNAVAILABLE", "当前入口未提供该小米宿主能力")
        }
        val callId = UUID.randomUUID().toString()
        val prepared = runCatching { prepareAttachments(toolCall.argumentsJson) }
            .getOrElse { throwable ->
                return errorResult("INVALID_ATTACHMENT", throwable.message ?: "附件无法读取")
            }
        val call = AgentHostCall(
            callId = callId,
            toolName = toolCall.name,
            argumentsJson = toolCall.argumentsJson,
            attachments = prepared,
        )
        val state = PendingCall()
        pending[callId] = state
        try {
            val message = Message.obtain(null, AgentRuntimeWire.MSG_HOST_CALL)
            message.data = AgentRuntimeWire.hostCallToBundle(call)
            hostMessenger.send(message)
        } catch (throwable: Throwable) {
            pending.remove(callId)
            return errorResult("HOST_BRIDGE_SEND_FAILED", "小米宿主调用发送失败")
        } finally {
            prepared.forEach { attachment -> runCatching { attachment.fileDescriptor?.close() } }
        }
        try {
            if (!state.latch.await(HOST_CALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                pending.remove(callId)
                return errorResult("HOST_BRIDGE_TIMEOUT", "小米宿主调用超时")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            pending.remove(callId)
            return errorResult("HOST_BRIDGE_INTERRUPTED", "小米宿主调用已中断")
        }
        val result = state.result.get()
            ?: return errorResult("HOST_BRIDGE_CLOSED", "小米宿主桥已关闭")
        return projectResult(toolCall.name, result, state.snapshotEvents())
    }

    fun onEvent(event: AgentHostEvent) {
        pending[event.callId]?.record(event)
    }

    fun onResult(result: AgentHostResult) {
        val state = pending.remove(result.callId)
        if (state == null) {
            result.attachments.forEach { attachment ->
                runCatching { attachment.fileDescriptor?.close() }
            }
            return
        }
        state.result.set(result)
        state.latch.countDown()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.values.forEach { state -> state.latch.countDown() }
        pending.clear()
    }

    private fun projectResult(
        toolName: String,
        result: AgentHostResult,
        events: List<AgentHostEvent>,
    ): AgentModelClient.ToolResult {
        val output = JSONObject()
            .put("ok", result.ok)
            .put("payload", parseJsonValue(result.payload))
        result.errorCode?.let { output.put("code", it) }
        result.errorMessage?.let { output.put("message", it) }
        if (result.retryable) output.put("retryable", true)

        val groupedEvents = JSONObject()
        events.groupBy { it.type }.forEach { (type, values) ->
            groupedEvents.put(
                type,
                JSONArray().also { array ->
                    values.forEach { event ->
                        array.put(
                            JSONObject()
                                .put("session_id", event.sessionId)
                                .put("payload", parseJsonValue(event.payload)),
                        )
                    }
                },
            )
        }
        if (groupedEvents.length() > 0) output.put("events", groupedEvents)

        val imported = importResultAttachments(result.callId, result.attachments)
        if (imported.length() > 0) output.put("attachments", imported)
        return AgentModelClient.ToolResult(
            content = output.toString(),
            sensitive = toolName == XiaomiHostToolCatalog.EXTERNAL_AGENT,
        )
    }

    private fun prepareAttachments(argumentsJson: String): List<AgentHostAttachment> {
        val args = JSONObject(argumentsJson.ifBlank { "{}" })
        if (args.optString("action") != "submit") return emptyList()
        val array = args.optJSONArray("attachments") ?: return emptyList()
        require(array.length() <= MAX_ATTACHMENT_COUNT) { "附件最多支持 $MAX_ATTACHMENT_COUNT 个" }
        var totalBytes = 0L
        val prepared = mutableListOf<AgentHostAttachment>()
        try {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val path = item.optString("path").trim()
                val uriText = item.optString("uri").trim()
                require((path.isBlank()) xor (uriText.isBlank())) { "附件 $index 的 path 与 uri 必须二选一" }
                val descriptor: ParcelFileDescriptor
                val fallbackName: String
                val resolvedMime: String?
                if (path.isNotBlank()) {
                    val file = File(path).canonicalFile
                    require(file.isFile) { "附件不存在或不是普通文件：$path" }
                    require(file.length() <= MAX_ATTACHMENT_BYTES) { "单个附件不能超过 ${MAX_ATTACHMENT_BYTES / 1024 / 1024} MiB" }
                    totalBytes += file.length()
                    descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    fallbackName = file.name
                    resolvedMime = URLConnection.guessContentTypeFromName(file.name)
                } else {
                    val uri = Uri.parse(uriText)
                    descriptor = appContext.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("无法打开附件 URI")
                    try {
                        val size = descriptor.statSize.takeIf { it >= 0 } ?: 0L
                        require(size <= MAX_ATTACHMENT_BYTES) { "单个附件不能超过 ${MAX_ATTACHMENT_BYTES / 1024 / 1024} MiB" }
                        totalBytes += size
                        fallbackName = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
                            .ifBlank { "attachment-$index" }
                        resolvedMime = appContext.contentResolver.getType(uri)
                    } catch (throwable: Throwable) {
                        runCatching { descriptor.close() }
                        throw throwable
                    }
                }
                prepared +=
                    AgentHostAttachment(
                        name = item.optString("name").trim().ifBlank { fallbackName },
                        mimeType = item.optString("mime_type").trim()
                            .ifBlank { resolvedMime ?: "application/octet-stream" },
                        fileDescriptor = descriptor,
                    )
                require(totalBytes <= MAX_TOTAL_ATTACHMENT_BYTES) { "附件总大小超过 ${MAX_TOTAL_ATTACHMENT_BYTES / 1024 / 1024} MiB" }
            }
            return prepared
        } catch (throwable: Throwable) {
            prepared.forEach { attachment ->
                runCatching { attachment.fileDescriptor?.close() }
            }
            throw throwable
        }
    }

    private fun importResultAttachments(
        callId: String,
        attachments: List<AgentHostAttachment>,
    ): JSONArray {
        val output = JSONArray()
        if (attachments.isEmpty()) return output
        val directory = File(appContext.cacheDir, "xiaomi-external-agent/$callId")
        runCatching { directory.mkdirs() }
        var totalBytes = 0L
        attachments.take(MAX_ATTACHMENT_COUNT).forEachIndexed { index, attachment ->
            val item = JSONObject()
                .put("name", attachment.name)
                .put("mime_type", attachment.mimeType)
            attachment.uri?.let { item.put("uri", it) }
            val descriptor = attachment.fileDescriptor
            if (descriptor != null) {
                runCatching {
                    val name = safeFileName(attachment.name, index)
                    val target = File(directory, name)
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        FileOutputStream(target).use { sink ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                totalBytes += read
                                require(totalBytes <= MAX_TOTAL_RESULT_BYTES) { "返回附件总大小超限" }
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                    item.put("local_path", target.absolutePath)
                    item.put("bytes", target.length())
                }.onFailure { throwable ->
                    runCatching { descriptor.close() }
                    item.put("error", throwable.message ?: "返回附件读取失败")
                }
            }
            output.put(item)
        }
        attachments.drop(MAX_ATTACHMENT_COUNT).forEach { attachment ->
            runCatching { attachment.fileDescriptor?.close() }
        }
        return output
    }

    private fun errorResult(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = true,
        )

    private fun parseJsonValue(raw: String): Any =
        raw.trim().takeIf { it.isNotEmpty() }?.let { value ->
            runCatching { JSONObject(value) }.getOrNull()
                ?: runCatching { JSONArray(value) }.getOrNull()
                ?: value
        } ?: ""

    private fun safeFileName(raw: String, index: Int): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_')
            .take(120)
        return "${index + 1}-${cleaned.ifBlank { "attachment.bin" }}"
    }

    private class PendingCall(
        val latch: CountDownLatch = CountDownLatch(1),
        val result: AtomicReference<AgentHostResult?> = AtomicReference(),
    ) {
        private val events = mutableListOf<AgentHostEvent>()
        private var eventChars = 0

        fun record(event: AgentHostEvent) = synchronized(events) {
            if (events.size >= MAX_HOST_EVENT_COUNT) return@synchronized
            val addedChars = event.type.length + event.sessionId.length + event.payload.length
            if (eventChars + addedChars > MAX_HOST_EVENT_CHARS) return@synchronized
            eventChars += addedChars
            events += event
        }

        fun snapshotEvents(): List<AgentHostEvent> = synchronized(events) { events.toList() }
    }

    private companion object {
        const val HOST_CALL_TIMEOUT_MINUTES = 10L
        const val MAX_ATTACHMENT_COUNT = 8
        const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024
        const val MAX_TOTAL_ATTACHMENT_BYTES = 64L * 1024 * 1024
        const val MAX_TOTAL_RESULT_BYTES = 64L * 1024 * 1024
        const val MAX_HOST_EVENT_COUNT = 512
        const val MAX_HOST_EVENT_CHARS = 256_000
    }
}
