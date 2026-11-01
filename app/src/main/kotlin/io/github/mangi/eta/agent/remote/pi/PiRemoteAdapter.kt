package io.github.mangi.eta.agent.remote.pi

import io.github.mangi.eta.agent.remote.RemoteAgentEvent
import io.github.mangi.eta.agent.remote.RemoteAgentProtocolAdapter
import io.github.mangi.eta.agent.remote.RemoteAttachment
import io.github.mangi.eta.agent.remote.RemoteCapabilities
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus
import io.github.mangi.eta.agent.remote.RemoteConnectionSupervisor
import io.github.mangi.eta.agent.remote.RemoteContextUsage
import io.github.mangi.eta.agent.remote.RemoteHttpClient
import io.github.mangi.eta.agent.remote.RemoteLiveTurn
import io.github.mangi.eta.agent.remote.RemoteModelOption
import io.github.mangi.eta.agent.remote.RemotePart
import io.github.mangi.eta.agent.remote.RemotePrompt
import io.github.mangi.eta.agent.remote.RemotePromptAction
import io.github.mangi.eta.agent.remote.RemoteServerAddress
import io.github.mangi.eta.agent.remote.RemoteSession
import io.github.mangi.eta.agent.remote.RemoteSessionInfo
import io.github.mangi.eta.agent.remote.RemoteServerConfig
import io.github.mangi.eta.agent.remote.RemoteTurn
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * pi-remote protocol adapter. Speaks the single-WS + HTTP-upload protocol served
 * by `D:\pi-remote`, including the token auth and history paging added for Eta.
 *
 * Frame decode is lenient (see [PiFrame]); unknown or malformed frames are logged
 * and dropped, never fatal. Client frames are built by hand so an absent optional
 * value is OMITTED rather than sent as JSON null — pi's `isClientMessage` treats
 * `null` as invalid where it accepts `undefined`.
 */
internal class PiRemoteAdapter : RemoteAgentProtocolAdapter {
    override val protocolId: String = PROTOCOL_ID

    override val capabilities: RemoteCapabilities = RemoteCapabilities(
        supportsPrompt = true,
        supportsSessions = true,
        supportsModelSwitch = true,
        supportsAttachments = true,
        supportsInterrupt = true,
        supportsHistoryPaging = true,
    )

    override fun open(
        config: RemoteServerConfig,
        scope: CoroutineScope,
        onEvent: (RemoteAgentEvent) -> Unit,
    ): RemoteSession = PiRemoteSession(config, scope, onEvent)

    companion object {
        const val PROTOCOL_ID = "pi"
    }
}

private class PiRemoteSession(
    private val config: RemoteServerConfig,
    scope: CoroutineScope,
    private val onEvent: (RemoteAgentEvent) -> Unit,
) : RemoteSession {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * 附件上传/下载用的 HTTP 客户端。
     *
     * 走 [RemoteHttpClient] 而不是云端模型的 `AgentHttpClient`：附件同样发往
     * 局域网服务器，必须绕过系统 HTTP 代理（代理不认识 192.168.x.x），
     * 也用不上模型 API 那套 20 秒连接超时。
     */
    private val httpClient = RemoteHttpClient.client

    private val _status = MutableStateFlow<RemoteConnectionStatus>(RemoteConnectionStatus.Disconnected)
    override val status: StateFlow<RemoteConnectionStatus> = _status.asStateFlow()

    private val supervisor = RemoteConnectionSupervisor(
        scope = scope,
        connectUrl = { wsUrl() },
        onFrame = { raw -> handleFrame(raw) },
        onStatus = { next -> _status.value = next },
    )

    init {
        supervisor.start()
    }

    private val token: String get() = config.authToken.trim()

    /**
     * WebSocket 入口地址。
     *
     * 用 `HttpUrl.Builder` 而不是字符串拼接：拼接遇到 IPv6、非法主机名、大小写
     * scheme 时会拼出 `ws://http://…` 这种废地址，握手直接失败，而失败原因
     * 又往往被上层吞掉，用户只看到一句「连接失败」。交给 OkHttp 规范化，
     * 非法地址会在这里直接抛错，由监管器翻译成「地址格式无效」。
     *
     * OkHttp 的 WebSocket 握手走 http/https scheme，升级由客户端自己发起，
     * 所以这里用 `http` 而不是 `ws`——两者对服务端完全等价。
     */
    private fun wsUrl(): String {
        val builder = HttpUrl.Builder()
            .scheme("http")
            .host(RemoteServerAddress.bareHost(config.host))
            .port(config.port)
            .addPathSegment("ws")
        if (token.isNotEmpty()) {
            builder.addQueryParameter("token", token)
        }
        return builder.build().toString()
    }

    private fun httpBase(): HttpUrl =
        HttpUrl.Builder()
            .scheme("http")
            .host(RemoteServerAddress.bareHost(config.host))
            .port(config.port)
            .build()

    private fun HttpUrl.Builder.withToken(): HttpUrl.Builder = apply {
        if (token.isNotEmpty()) addQueryParameter("token", token)
    }

    // ─── Downstream decode ───────────────────────────────────────────────────

    private fun handleFrame(raw: String) {
        val frame = runCatching { json.decodeFromString<PiFrame>(raw) }.getOrNull()
        if (frame == null) {
            AndroidAgentLogger.warn("Remote pi frame undecodable: chars=${raw.length}")
            return
        }
        val event = frame.toEvent()
        if (event == null) {
            if (frame.type.isNotBlank() && frame.type !in IGNORED_TYPES) {
                AndroidAgentLogger.debug { "Remote pi frame ignored: type=${frame.type}" }
            }
            return
        }
        onEvent(event)
    }

    private fun PiFrame.toEvent(): RemoteAgentEvent? = when (type) {
        "snapshot" -> RemoteAgentEvent.Snapshot(
            turns = turns.orEmpty().map { it.toRemote() },
            live = live?.toRemote(),
            running = agentRunning ?: false,
            prompt = prompt?.toRemote(),
            contextUsage = context?.toRemote(),
            modelLabel = model,
            held = held.orEmpty(),
            heldRunActive = heldRunActive ?: false,
            taskWidget = taskWidget,
        )
        "agent_start" -> RemoteAgentEvent.RunStarted(model)
        "text_delta" -> RemoteAgentEvent.TextDelta(delta ?: "")
        "text_end" -> RemoteAgentEvent.TextEnd
        "thinking_delta" -> RemoteAgentEvent.ThinkingDelta(delta ?: "")
        "thinking_end" -> RemoteAgentEvent.ThinkingEnd
        "tool_start" -> RemoteAgentEvent.ToolStart(
            toolCallId = toolCallId ?: "",
            toolName = toolName ?: "",
            argsJson = args?.toString(),
        )
        "tool_end" -> RemoteAgentEvent.ToolEnd(
            toolCallId = toolCallId ?: "",
            toolName = toolName ?: "",
            resultJson = result?.toString(),
            isError = isError ?: false,
            elapsedMs = elapsedMs,
        )
        "agent_end" -> RemoteAgentEvent.RunFinished(contextUsage?.toRemote(), model)
        "agent_error" -> RemoteAgentEvent.RunFailed(message ?: "远程运行出错")
        "user_message" -> RemoteAgentEvent.UserMessageEcho(text ?: "", attachments.orEmpty().map { it.toRemote() })
        "system_note" -> RemoteAgentEvent.SystemNote(text ?: "")
        "reset" -> RemoteAgentEvent.Reset
        "prompt" -> RemoteAgentEvent.PromptPosted(toPrompt())
        "prompt_resolved" -> RemoteAgentEvent.PromptResolved(id ?: "")
        "models" -> RemoteAgentEvent.ModelsUpdated(current, models.orEmpty().map { RemoteModelOption(it.spec, it.name) })
        "sessions" -> RemoteAgentEvent.SessionsUpdated(current, sessions.orEmpty().map { it.toRemote() })
        "held" -> RemoteAgentEvent.HeldUpdated(texts.orEmpty(), runActive ?: false)
        "notify" -> RemoteAgentEvent.Notify(level ?: "info", message ?: "")
        "history_page" -> RemoteAgentEvent.HistoryPage(
            turns = turns.orEmpty().map { it.toRemote() },
            before = before,
            hasMore = hasMore ?: false,
        )
        // Transient / no Eta surface: partial tool results, standalone context
        // (rides on agent_end in production), and the task widget.
        else -> null
    }

    private fun PiTurn.toRemote(): RemoteTurn = RemoteTurn(
        role = role,
        text = text,
        attachments = attachments.orEmpty().map { it.toRemote() },
        parts = parts.orEmpty().mapNotNull { it.toRemote() },
        error = error ?: false,
        ts = ts,
    )

    private fun PiLiveTurn.toRemote(): RemoteLiveTurn = RemoteLiveTurn(
        parts = parts.orEmpty().mapNotNull { it.toRemote() },
        textOpen = textOpen,
    )

    private fun PiPart.toRemote(): RemotePart? = when (kind) {
        "text" -> RemotePart.Text(text ?: "")
        "thinking" -> RemotePart.Thinking(text ?: "", done ?: false)
        "tool" -> RemotePart.Tool(
            toolCallId = toolCallId ?: "",
            toolName = toolName ?: "",
            argsJson = args?.toString(),
            resultJson = result?.toString(),
            isError = isError ?: false,
            done = done ?: false,
            elapsedMs = elapsedMs,
        )
        else -> null
    }

    private fun PiAttachment.toRemote(): RemoteAttachment = RemoteAttachment(
        id = id, name = name, kind = kind, mimeType = mimeType, size = size, url = url,
    )

    private fun PiContextUsage.toRemote(): RemoteContextUsage =
        RemoteContextUsage(tokens = tokens, contextWindow = contextWindow, percent = percent)

    private fun PiPrompt.toRemote(): RemotePrompt = RemotePrompt(
        id = id,
        question = question,
        recommended = recommended,
        recommended2 = recommended2,
        actions = actions.orEmpty().map { RemotePromptAction(it.label, it.value) },
        dismissOnly = dismissOnly ?: false,
        allowSkip = allowSkip,
    )

    private fun PiFrame.toPrompt(): RemotePrompt = RemotePrompt(
        id = id ?: "",
        question = question ?: "",
        recommended = recommended,
        recommended2 = recommended2,
        actions = actions.orEmpty().map { RemotePromptAction(it.label, it.value) },
        dismissOnly = dismissOnly ?: false,
        allowSkip = allowSkip ?: false,
    )

    private fun PiSession.toRemote(): RemoteSessionInfo = RemoteSessionInfo(
        path = path,
        name = name,
        firstMessage = firstMessage,
        modified = modified,
        messageCount = messageCount,
        unsaved = unsaved ?: false,
    )

    // ─── Upstream commands ───────────────────────────────────────────────────

    override suspend fun sendMessage(text: String, attachmentIds: List<String>): Boolean {
        val frame = buildJsonObject {
            put("type", "message")
            put("text", text)
            if (attachmentIds.isNotEmpty()) {
                putJsonArray("attachments") { attachmentIds.forEach { add(it) } }
            }
        }
        return supervisor.send(frame.toString())
    }

    override suspend fun answerPrompt(id: String, value: String?) {
        val frame = buildJsonObject {
            put("type", "prompt_answer")
            put("id", id)
            // Omit when null (skip): pi accepts `undefined`, not JSON `null`.
            if (value != null) put("value", value)
        }
        supervisor.send(frame.toString())
    }

    override suspend fun interrupt() {
        supervisor.send(buildJsonObject { put("type", "interrupt") }.toString())
    }

    override suspend fun setModel(spec: String) {
        supervisor.send(buildJsonObject { put("type", "set_model"); put("spec", spec) }.toString())
    }

    override suspend fun switchSession(path: String) {
        supervisor.send(buildJsonObject { put("type", "switch_session"); put("path", path) }.toString())
    }

    override suspend fun deleteSession(path: String) {
        supervisor.send(buildJsonObject { put("type", "delete_session"); put("path", path) }.toString())
    }

    override suspend fun listSessions() {
        supervisor.send(buildJsonObject { put("type", "list_sessions") }.toString())
    }

    override suspend fun fetchHistory(before: String?) {
        val frame = buildJsonObject {
            put("type", "fetch_history")
            if (before != null) put("before", before)
        }
        supervisor.send(frame.toString())
    }

    override suspend fun uploadAttachment(name: String, mime: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val url = httpBase().newBuilder()
                .addPathSegment("upload")
                .addQueryParameter("name", name)
                .addQueryParameter("type", mime)
                .withToken()
                .build()
            val body = bytes.toRequestBody(mime.toMediaTypeOrNull())
            val request = Request.Builder().url(url).post(body).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("附件上传失败：HTTP ${response.code}")
                val raw = response.body?.string().orEmpty()
                json.decodeFromString<PiAttachment>(raw).id
            }
        }

    override suspend fun fetchAttachment(id: String): ByteArray =
        withContext(Dispatchers.IO) {
            val url = httpBase().newBuilder()
                .addPathSegment("uploads")
                .addPathSegment(id)
                .withToken()
                .build()
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("附件下载失败：HTTP ${response.code}")
                response.body?.bytes() ?: ByteArray(0)
            }
        }

    override fun close() {
        supervisor.stop()
    }

    companion object {
        /** Frames intentionally not surfaced; kept so the drop-log stays quiet. */
        private val IGNORED_TYPES = setOf("tool_update", "context", "widget")
    }
}
