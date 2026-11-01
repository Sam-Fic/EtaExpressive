package io.github.mangi.eta.agent.remote.opencode

import io.github.mangi.eta.agent.remote.RemoteAgentEvent
import io.github.mangi.eta.agent.remote.RemoteAttachment
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus
import io.github.mangi.eta.agent.remote.RemoteConnectionSupervisor
import io.github.mangi.eta.agent.remote.RemoteContextUsage
import io.github.mangi.eta.agent.remote.RemoteFailure
import io.github.mangi.eta.agent.remote.RemoteHttpClient
import io.github.mangi.eta.agent.remote.RemoteModelOption
import io.github.mangi.eta.agent.remote.RemotePart
import io.github.mangi.eta.agent.remote.RemotePrompt
import io.github.mangi.eta.agent.remote.RemotePromptAction
import io.github.mangi.eta.agent.remote.RemoteServerAddress
import io.github.mangi.eta.agent.remote.RemoteServerConfig
import io.github.mangi.eta.agent.remote.RemoteSession
import io.github.mangi.eta.agent.remote.RemoteSessionInfo
import io.github.mangi.eta.agent.remote.RemoteTurn
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

/**
 * OpenCode v2 服务器的远程会话（`opencode serve`，HTTP REST + SSE）。
 *
 * 与 pi 的「单 WebSocket + 连接即推 snapshot」不同，OpenCode 的事件流是**易失的**
 * （官方契约：断线期间的事件不补发、慢消费者会被断流），所以这里的 (重)连流程是：
 *
 * 1. 打开 `GET /api/event` 的 SSE 长读；
 * 2. 连上后主动拉一轮全量状态（会话列表 / 运行中集合 / 当前会话末页消息 / 待答
 *    权限与表单 / 模型列表），折成与 pi 语义一致的 Snapshot / SessionsUpdated /
 *    ModelsUpdated —— 上层折叠器无需知道差异；
 * 3. 之后逐行读 SSE，把 `session.*` / `permission.*` / `form.*` 事件折成
 *    [RemoteAgentEvent]。
 *
 * 重连退避、失败归类（含「系统拦局域网」的甄别）直接复用
 * [RemoteConnectionSupervisor] 的静态逻辑，保证两种协议在界面上的失败话术一致。
 *
 * 事件侧用 [JsonObject] 手取字段（新增字段天然免疫），REST 侧用 [OpencodeModels]
 * 的 DTO（字段名集中在 Models 文件里可查证）。**跨机器时钟不可信**：事件里的
 * `created` 是服务器时钟，重连窗口里缓冲的旧事件与重建拉到的消息可能重叠，
 * 去重用两条互补的规则——
 * - 流式事件（text/reasoning/tool）按 `assistantMessageID ∈ 快照消息集合` 且
 *   `created ≤ 重建完成标记` 丢弃；标记取自消息响应的 HTTP `Date` 头（服务器
 *   时钟、秒级）加 2 秒余量，不做手机/服务器时钟对比；
 * - 权限/表单按 id 去重：重建时先入 [pendingAnswers]，重复的 asked 事件直接忽略。
 *
 * 所有上行命令都不抛异常：协调器用裸 `scope.launch` 调它们，一个未捕获的
 * IOException 就能带走整条远程链路的协程。
 */
internal class OpencodeSession(
    private val config: RemoteServerConfig,
    private val scope: CoroutineScope,
    private val onEvent: (RemoteAgentEvent) -> Unit,
) : RemoteSession {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** 复用远程专用客户端：绕过系统代理、8 秒连接超时、按需绑 Wi-Fi。 */
    private val httpClient = RemoteHttpClient.client

    private val _status = MutableStateFlow<RemoteConnectionStatus>(RemoteConnectionStatus.Disconnected)
    override val status: StateFlow<RemoteConnectionStatus> = _status.asStateFlow()

    private var streamJob: Job? = null

    @Volatile private var closed = false

    /** 当前 SSE 请求；close() 时 cancel 掉，阻塞中的 readUtf8Line 会以 IOException 醒来。 */
    @Volatile private var currentCall: Call? = null

    // ─── 折叠状态（SSE 读线程独占写；activeSessionId/model* 与命令线程共享，@Volatile） ──

    /** Eta 是「每服务器一个活跃会话」模型；OpenCode 没有服务端 current，这里本地维护。 */
    @Volatile private var activeSessionId: String? = null

    @Volatile private var runActive = false
    @Volatile private var modelLabel: String? = null
    @Volatile private var modelSpec: String? = null
    @Volatile private var contextWindow: Long? = null
    @Volatile private var lastContextUsage: RemoteContextUsage? = null
    @Volatile private var modelCache: List<OpencodeModelInfo> = emptyList()

    /** 最近一次重建拉到的消息 id 集合 + 重建完成标记（服务器时钟毫秒）。 */
    @Volatile private var snapshotMessageIds: Set<String> = emptySet()
    @Volatile private var fetchMarkerMs: Long? = null

    /** SSE 读线程独占；只在 handleFrame 里读写。 */
    private val toolNames = HashMap<String, String>()
    private val toolStartedAtMs = HashMap<String, Long>()

    /** prompt id → 应答路由（权限 once/always/reject，表单按字段名应答）。 */
    private val pendingAnswers = ConcurrentHashMap<String, PendingAnswer>()

    /** 已挑选、待随下一条消息发出的附件。OpenCode 无上传端点，字节留在本地直到发送。 */
    private val pendingUploads = ConcurrentHashMap<String, PendingUpload>()
    private val uploadSeq = AtomicInteger(0)

    /** 历史分页游标：重建取末页时记下「更老一页」的位置，fetchHistory(null) 从这继续。 */
    @Volatile private var historyCursor: String? = null

    private class PendingUpload(val name: String, val mime: String, val bytes: ByteArray)

    private sealed interface PendingAnswer {
        data class Permission(val sessionID: String) : PendingAnswer
        data class Form(val sessionID: String, val fieldKey: String, val boolean: Boolean) : PendingAnswer
    }

    init {
        streamJob = scope.launch(Dispatchers.IO) { streamLoop() }
    }

    override fun close() {
        closed = true
        currentCall?.cancel()
        streamJob?.cancel()
        _status.value = RemoteConnectionStatus.Disconnected
    }

    // ─── SSE 长连接循环 ─────────────────────────────────────────────────────

    private sealed interface Outcome {
        data class Terminal(val kind: RemoteFailure.Kind, val detail: String?) : Outcome
        data class Disconnected(val kind: RemoteFailure.Kind?, val detail: String?) : Outcome
    }

    private suspend fun streamLoop() {
        var attempt = 0
        var lastKind: RemoteFailure.Kind? = null
        var lastDetail: String? = null
        while (!closed) {
            _status.value = if (attempt == 0) {
                RemoteConnectionStatus.Connecting
            } else {
                RemoteConnectionStatus.Reconnecting(attempt, lastKind, lastDetail)
            }
            val call = runCatching { buildEventCall() }.getOrNull()
            if (call == null) {
                AndroidAgentLogger.warn("Remote opencode address malformed: ${config.host}:${config.port}")
                _status.value = RemoteConnectionStatus.Failed(RemoteFailure.Kind.MalformedAddress)
                return
            }
            currentCall = call
            val outcome = runCatching {
                call.execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 ->
                            Outcome.Terminal(RemoteFailure.Kind.Unauthorized, response.code.toString())
                        response.code == 404 ->
                            Outcome.Terminal(RemoteFailure.Kind.NotFound, response.code.toString())
                        !response.isSuccessful ->
                            Outcome.Disconnected(RemoteFailure.Kind.Unknown, "HTTP ${response.code}")
                        else -> {
                            attempt = 0
                            lastKind = null
                            lastDetail = null
                            _status.value = RemoteConnectionStatus.Connected
                            rebuild(response.headers.getDate("Date")?.time)
                            consumeStream(response.body?.source())
                            Outcome.Disconnected(null, null) // 干净的 EOF 同样按断线重连
                        }
                    }
                }
            }.getOrElse { e ->
                if (closed) return
                val failure = RemoteConnectionSupervisor.classify(e, eventUrl().toString())
                AndroidAgentLogger.warn(
                    "Remote opencode stream failure: ${e.javaClass.simpleName} -> ${failure.kind} (${failure.detail})",
                )
                if (failure.terminal) Outcome.Terminal(failure.kind, failure.detail)
                else Outcome.Disconnected(failure.kind, failure.detail)
            }
            when (outcome) {
                is Outcome.Terminal -> {
                    _status.value = RemoteConnectionStatus.Failed(outcome.kind, outcome.detail)
                    return
                }
                is Outcome.Disconnected -> {
                    if (closed) return
                    attempt += 1
                    outcome.kind?.let { lastKind = it }
                    outcome.detail?.let { lastDetail = it }
                    if (lastKind == RemoteFailure.Kind.ConnectTimeout) {
                        // TCP 没连上时，下一跳试试绑 Wi-Fi（部分 ROM 会把流量搬到移动网络）。
                        RemoteHttpClient.preferWifiOnNextAttempt()
                    }
                    delay(RemoteConnectionSupervisor.backoffMs(attempt))
                }
            }
        }
    }

    private fun buildEventCall(): Call {
        val request = Request.Builder().url(eventUrl()).get().withAuth().build()
        return httpClient.newCall(request)
    }

    private fun consumeStream(source: BufferedSource?) {
        // body 缺失属于协议级异常，抛出去由外层归类重连。
        val buffered = source ?: throw IOException("event stream body missing")
        while (true) {
            val line = buffered.readUtf8Line() ?: return
            if (!line.startsWith("data:")) continue // ": heartbeat" 注释行与空行
            val payload = line.removePrefix("data:").trim()
            if (payload.isNotEmpty()) handleFrame(payload)
        }
    }

    // ─── 重建（对齐 pi 的 snapshot 语义） ────────────────────────────────────

    /**
     * 拉一轮全量状态并折成三连事件。任何一步网络失败都会抛给 [streamLoop] 归类重连，
     * 所以这里不吞异常。
     *
     * @param eventDateMs 事件响应的 `Date` 头（服务器时钟），作重建标记的兜底基准
     */
    private suspend fun rebuild(eventDateMs: Long?) {
        val sessions = getJson<OpencodeSessionListEnvelope>(apiUrl("api", "session"))?.data.orEmpty()
        val running = getJson<OpencodeActiveEnvelope>(apiUrl("api", "session", "active"))
            ?.data?.keys.orEmpty()
        val models = getJson<OpencodeModelListEnvelope>(apiUrl("api", "model"))?.data.orEmpty()

        // 活跃会话：尽量保持上一次的选择（重连不跳页）；没有就挑最近更新的一个。
        val chosen = sessions.firstOrNull { it.id == activeSessionId && it.id != null }?.id
            ?: sessions.maxByOrNull { it.time?.updated ?: it.time?.created ?: 0L }?.id
        activeSessionId = chosen

        val chosenInfo = sessions.firstOrNull { it.id == chosen }
        val chosenModel = chosenInfo?.model ?: defaultModelRef()
        applyModel(chosenModel, models)

        // 消息页最后拉：它的 Date 头是「重建完成」的界碑，此前的流式事件都已在数据里。
        var messages: List<OpencodeMessage> = emptyList()
        var cursor: String? = null
        if (chosen != null) {
            val page = getJsonWithMarker<OpencodeMessageListEnvelope>(
                apiUrl(
                    "api", "session", chosen, "message",
                    query = listOf("limit" to "50", "order" to "desc"),
                ),
                fallbackMarker = eventDateMs,
            )
            if (page != null) {
                messages = page.data
                cursor = page.cursor?.next
            }
        }
        snapshotMessageIds = messages.mapNotNullTo(HashSet()) { it.id }
        historyCursor = cursor

        // 待答事项：权限按会话列，表单全局列再按会话过滤。表单只支持单字段
        // string/boolean（见 [formToPrompt]）；不支持的题型在重建里静默跳过，
        // 否则每次重连都会重复弹同一条「不支持」通知。
        val permissions = chosen?.let { sid ->
            getJson<OpencodePermissionListEnvelope>(apiUrl("api", "session", sid, "permission"))
                ?.data.orEmpty()
                .filter { it.id != null }
        }.orEmpty()
        val forms = getJson<OpencodeFormListEnvelope>(apiUrl("api", "form"))?.data.orEmpty()
            .filter { it.sessionID == chosen }

        pendingAnswers.clear()
        toolNames.clear()
        toolStartedAtMs.clear()
        val pendingPrompt = permissions.firstOrNull()?.let { permission ->
            pendingAnswers[permission.id!!] = PendingAnswer.Permission(permission.sessionID ?: "")
            permission.toPrompt()
        } ?: forms.firstOrNull()?.let { form ->
            formToPrompt(form)?.also { mapped ->
                form.id?.let { pendingAnswers[it] = mapped.answerTarget() }
            }?.prompt
        }

        // 运行中 = 运行集合里有它，或 busy 晚于 idle（覆盖运行始于两次拉取之间的竞态）。
        val info = chosenInfo
        val wasRunning = chosen != null && (
            running.contains(chosen) ||
                (info?.time?.busy ?: Long.MIN_VALUE) > (info?.time?.idle ?: Long.MIN_VALUE)
            )
        runActive = wasRunning
        lastContextUsage = info?.tokens?.toContextUsage()

        emit(RemoteAgentEvent.SessionsUpdated(current = chosen, sessions = sessions.map { it.toRemote() }))
        emit(RemoteAgentEvent.ModelsUpdated(current = modelSpec, models = models.map { it.toOption() }))
        emit(
            RemoteAgentEvent.Snapshot(
                turns = messages.asReversed().mapNotNull { it.toTurn() },
                live = null,
                running = wasRunning,
                prompt = pendingPrompt,
                contextUsage = lastContextUsage,
                modelLabel = modelLabel,
                held = emptyList(),
                heldRunActive = false,
                taskWidget = null,
            ),
        )
    }

    // ─── 下行事件折叠 ───────────────────────────────────────────────────────

    private fun handleFrame(payload: String) {
        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
        if (root == null) {
            AndroidAgentLogger.warn("Remote opencode frame undecodable: chars=${payload.length}")
            return
        }
        val type = root.str("type") ?: return
        if (type == "server.connected") return
        val data = root.obj("data") ?: return
        val created = root.long("created")
        val sid = data.str("sessionID")
        when (type) {
            "session.status" -> if (sid == activeSessionId) foldStatus(data.obj("status"))
            "session.inbox.enqueued" -> if (sid == activeSessionId && !isStale(created)) foldEnqueued(data)
            "session.text.delta" -> if (liveEligible(data, created)) emit(RemoteAgentEvent.TextDelta(data.str("delta") ?: ""))
            "session.text.ended" -> if (liveEligible(data, created)) emit(RemoteAgentEvent.TextEnd)
            "session.reasoning.delta" -> if (liveEligible(data, created)) emit(RemoteAgentEvent.ThinkingDelta(data.str("delta") ?: ""))
            "session.reasoning.ended" -> if (liveEligible(data, created)) emit(RemoteAgentEvent.ThinkingEnd)
            "session.tool.input.started" -> if (liveEligible(data, created)) {
                val id = data.str("id")
                val name = data.str("name")
                if (id != null && name != null) toolNames[id] = name
            }
            "session.tool.called" -> if (liveEligible(data, created)) foldToolCalled(data, created)
            "session.tool.success" -> if (liveEligible(data, created)) {
                foldToolEnd(data, created, isError = false, resultJson = data.array("content")?.toString())
            }
            "session.tool.failed" -> if (liveEligible(data, created)) {
                foldToolEnd(data, created, isError = true, resultJson = data.obj("error")?.str("message") ?: "工具执行失败")
            }
            "session.step.ended" -> if (sid == activeSessionId) {
                data.obj("tokens")?.let { obj ->
                    runCatching { json.decodeFromJsonElement<OpencodeTokens>(obj) }.getOrNull()
                }?.let { lastContextUsage = it.toContextUsage() }
            }
            "session.step.failed" -> if (sid == activeSessionId && !isStale(created)) {
                emit(RemoteAgentEvent.RunFailed(data.obj("error")?.str("message") ?: "远程运行出错"))
            }
            "session.model.selected" -> if (sid == activeSessionId) {
                applyModel(data.obj("model")?.toModelRef(), modelCache)
                emit(RemoteAgentEvent.ModelsUpdated(current = modelSpec, models = modelCache.map { it.toOption() }))
            }
            "permission.asked" -> foldPermissionAsked(data)
            "permission.replied" -> data.str("requestID")?.let { id ->
                pendingAnswers.remove(id)
                emit(RemoteAgentEvent.PromptResolved(id))
            }
            "form.created" -> foldFormCreated(data)
            "form.replied", "form.cancelled" -> data.str("id")?.let { id ->
                pendingAnswers.remove(id)
                emit(RemoteAgentEvent.PromptResolved(id))
            }
            else -> if (type !in IGNORED_TYPES) {
                AndroidAgentLogger.debug { "Remote opencode event ignored: $type" }
            }
        }
    }

    private fun foldStatus(status: JsonObject?) {
        when (status?.str("type")) {
            "busy" -> if (!runActive) {
                runActive = true
                emit(RemoteAgentEvent.RunStarted(modelLabel))
            }
            "retry" -> {
                if (!runActive) {
                    runActive = true
                    emit(RemoteAgentEvent.RunStarted(modelLabel))
                }
                emit(RemoteAgentEvent.Notify("warning", status.str("message") ?: "远程运行重试中"))
            }
            "idle" -> if (runActive) {
                runActive = false
                emit(RemoteAgentEvent.RunFinished(lastContextUsage, modelLabel))
            }
        }
    }

    private fun foldEnqueued(data: JsonObject) {
        val item = data.obj("item") ?: return
        if (item.str("type") != "user") return
        val payload = item.obj("payload") ?: return
        val text = payload.str("text") ?: return
        val attachments = payload.array("files").orEmpty()
            .mapNotNull { it as? JsonObject }
            .map { file ->
                val uri = file.str("uri").orEmpty()
                RemoteAttachment(
                    id = uri,
                    name = file.str("name") ?: attachmentNameOf(uri),
                    kind = if (uri.startsWith("data:image/")) "image" else "file",
                    mimeType = uri.substringAfter("data:", "").substringBefore(";base64"),
                    size = 0,
                    url = uri,
                )
            }
        emit(RemoteAgentEvent.UserMessageEcho(text, attachments))
    }

    private fun foldToolCalled(data: JsonObject, created: Long?) {
        val id = data.str("id") ?: return
        emit(
            RemoteAgentEvent.ToolStart(
                toolCallId = id,
                toolName = toolNames[id].orEmpty(),
                argsJson = data.obj("input")?.toString(),
            ),
        )
        if (created != null) toolStartedAtMs[id] = created
    }

    private fun foldToolEnd(data: JsonObject, created: Long?, isError: Boolean, resultJson: String?) {
        val id = data.str("id") ?: return
        val startedAt = toolStartedAtMs.remove(id)
        val elapsed = if (startedAt != null && created != null) (created - startedAt).coerceAtLeast(0) else null
        emit(
            RemoteAgentEvent.ToolEnd(
                toolCallId = id,
                toolName = toolNames[id].orEmpty(),
                resultJson = resultJson,
                isError = isError,
                elapsedMs = elapsed,
            ),
        )
    }

    private fun foldPermissionAsked(data: JsonObject) {
        if (data.str("sessionID") != activeSessionId) return
        val permission = runCatching { json.decodeFromJsonElement<OpencodePermission>(data) }.getOrNull()
        val id = permission?.id ?: return
        if (pendingAnswers.putIfAbsent(id, PendingAnswer.Permission(permission.sessionID ?: "")) != null) return
        emit(RemoteAgentEvent.PromptPosted(permission.toPrompt()))
    }

    private fun foldFormCreated(data: JsonObject) {
        val form = data.obj("form")?.let { runCatching { json.decodeFromJsonElement<OpencodeForm>(it) }.getOrNull() }
            ?: return
        val id = form.id ?: return
        if (form.sessionID != activeSessionId) return
        val mapped = formToPrompt(form) ?: run {
            emit(RemoteAgentEvent.Notify("warning", "OpenCode 表单包含暂不支持的题型，请在服务器端处理"))
            return
        }
        if (pendingAnswers.putIfAbsent(id, mapped.answerTarget()) != null) return
        emit(RemoteAgentEvent.PromptPosted(mapped.prompt))
    }

    /** 只有「单字段 string/boolean」能映射成问答卡；其余题型返回 null 表示不支持。 */
    private fun formToPrompt(form: OpencodeForm): MappedForm? {
        val id = form.id ?: return null
        val field = form.fields.singleOrNull() ?: return null
        val key = field.key ?: return null
        val question = form.title?.takeIf { it.isNotBlank() } ?: field.title?.takeIf { it.isNotBlank() } ?: "OpenCode 表单"
        return when (field.type) {
            "string" -> MappedForm(
                prompt = RemotePrompt(
                    id = id,
                    question = question,
                    actions = field.options.mapNotNull { option ->
                        val value = option.value ?: return@mapNotNull null
                        RemotePromptAction(option.label ?: value, value)
                    },
                ),
                answerTarget = { PendingAnswer.Form(form.sessionID ?: "", key, boolean = false) },
            )
            "boolean" -> MappedForm(
                prompt = RemotePrompt(
                    id = id,
                    question = question,
                    actions = listOf(
                        RemotePromptAction("是", "true"),
                        RemotePromptAction("否", "false"),
                    ),
                ),
                answerTarget = { PendingAnswer.Form(form.sessionID ?: "", key, boolean = true) },
            )
            else -> null
        }
    }

    private class MappedForm(
        val prompt: RemotePrompt,
        val answerTarget: () -> PendingAnswer,
    )

    /**
     * 流式事件是否应当折进 live turn。
     *
     * 会话过滤 + 陈旧过滤双闸：重建拉到的消息里已经带着这些部分的内容，缓冲期里
     * 的旧 delta 若照单全收，同一段文字就会在界面上出现两遍。
     */
    private fun liveEligible(data: JsonObject, created: Long?): Boolean {
        if (data.str("sessionID") != activeSessionId) return false
        val messageId = data.str("assistantMessageID")
        if (messageId != null && messageId in snapshotMessageIds && isStale(created)) return false
        return true
    }

    private fun isStale(created: Long?): Boolean {
        if (created == null) return false
        val marker = fetchMarkerMs ?: return false
        return created <= marker
    }

    // ─── 上行命令（全部不抛异常：协调器是裸 scope.launch 调它们） ─────────────

    override suspend fun sendMessage(text: String, attachmentIds: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            val sid = activeSessionId ?: return@withContext false
            val files = attachmentIds.mapNotNull { pendingUploads[it] }
            val body = buildJsonObject {
                put("text", text)
                if (files.isNotEmpty()) {
                    putJsonArray("files") {
                        files.forEach { file ->
                            add(
                                buildJsonObject {
                                    put("uri", file.dataUri())
                                    put("name", file.name)
                                },
                            )
                        }
                    }
                }
            }
            val delivered = runCatching {
                callHttp(apiUrl("api", "session", sid, "prompt"), "POST", body.toString()).use { it.isSuccessful }
            }.getOrDefault(false)
            if (delivered) attachmentIds.forEach { pendingUploads.remove(it) }
            delivered
        }

    override suspend fun answerPrompt(id: String, value: String?) {
        runCatching {
            when (val target = pendingAnswers[id]) {
                is PendingAnswer.Permission -> when (value) {
                    "once", "always", "reject" ->
                        callHttp(
                            apiUrl("api", "session", target.sessionID, "permission", id, "reply"),
                            "POST",
                            buildJsonObject { put("decision", value) }.toString(),
                        ).use { it.isSuccessful }
                    null -> Unit // 空值 = 跳过；权限没有 skip 语义，保持待答
                    else -> emit(RemoteAgentEvent.Notify("warning", "该确认只接受按钮选择"))
                }
                is PendingAnswer.Form -> {
                    if (value == null) {
                        callHttp(apiUrl("api", "session", target.sessionID, "form", id), "DELETE").use { }
                    } else {
                        callHttp(
                            apiUrl("api", "session", target.sessionID, "form", id, "reply"),
                            "POST",
                            buildJsonObject {
                                putJsonObject("answer") {
                                    if (target.boolean) put(target.fieldKey, value == "true")
                                    else put(target.fieldKey, value)
                                }
                            }.toString(),
                        ).use { }
                    }
                }
                null -> Unit // 已被应答，或上一轮重建的残留
            }
        }.onFailure { e -> notifyFailure("应答发送失败", e) }
    }

    override suspend fun interrupt() {
        runCatching {
            val sid = activeSessionId ?: return
            callHttp(apiUrl("api", "session", sid, "interrupt"), "POST").use { }
        }.onFailure { e -> notifyFailure("中断失败", e) }
    }

    override suspend fun setModel(spec: String) {
        runCatching {
            val sid = activeSessionId ?: return
            val providerID = spec.substringBefore('/')
            val id = spec.substringAfter('/')
            if (providerID.isEmpty() || id.isEmpty() || providerID == spec) {
                emit(RemoteAgentEvent.Notify("warning", "模型标识无法识别：$spec"))
                return
            }
            callHttp(
                apiUrl("api", "session", sid, "model"),
                "POST",
                buildJsonObject {
                    put("model", buildJsonObject {
                        put("id", id)
                        put("providerID", providerID)
                    })
                }.toString(),
            ).use { response ->
                if (!response.isSuccessful) {
                    emit(RemoteAgentEvent.Notify("error", "切换模型失败：HTTP ${response.code}"))
                }
            }
        }.onFailure { e -> notifyFailure("切换模型失败", e) }
    }

    override suspend fun newSession(): Boolean = withContext(Dispatchers.IO) {
        val created = runCatching {
            callHttp(apiUrl("api", "session"), "POST", "{}").use { response ->
                if (!response.isSuccessful) {
                    AndroidAgentLogger.warn("Remote opencode session create failed: HTTP ${response.code}")
                    null
                } else {
                    response.body?.string()
                        ?.let { runCatching { json.decodeFromString<OpencodeDataEnvelope<OpencodeSessionInfo>>(it) }.getOrNull() }
                        ?.data?.id
                }
            }
        }.getOrNull()
        if (created == null) return@withContext false
        switchSessionInternal(created)
        true
    }

    override suspend fun switchSession(path: String) {
        runCatching { switchSessionInternal(path) }
            .onFailure { e -> notifyFailure("切换会话失败", e) }
    }

    /**
     * 本地切换：OpenCode 没有服务端 current，切会话 = 改活跃 id + Reset + 重建。
     * [path] 为 null 时由重建挑选最近更新的会话（删除当前会话后的落位）。
     */
    private suspend fun switchSessionInternal(path: String?) {
        activeSessionId = path
        emit(RemoteAgentEvent.Reset)
        rebuild(null)
    }

    override suspend fun deleteSession(path: String) {
        runCatching {
            callHttp(apiUrl("api", "session", path), "DELETE").use { response ->
                if (!response.isSuccessful) {
                    emit(RemoteAgentEvent.Notify("error", "删除会话失败：HTTP ${response.code}"))
                    return
                }
            }
            if (path == activeSessionId) switchSessionInternal(null) else listSessions()
        }.onFailure { e -> notifyFailure("删除会话失败", e) }
    }

    override suspend fun listSessions() {
        runCatching {
            val sessions = getJson<OpencodeSessionListEnvelope>(apiUrl("api", "session"))?.data.orEmpty()
            emit(RemoteAgentEvent.SessionsUpdated(current = activeSessionId, sessions = sessions.map { it.toRemote() }))
        }.onFailure { e -> notifyFailure("刷新会话列表失败", e) }
    }

    /**
     * 拉更老的一页。[before] 是上一页带回来的游标；为 null 时沿用重建时记下的
     * [historyCursor]；两边都空说明没有更老的页了，无需发事件。
     */
    override suspend fun fetchHistory(before: String?) {
        runCatching {
            val sid = activeSessionId ?: return
            val cursor = before ?: historyCursor ?: return
            val page = getJson<OpencodeMessageListEnvelope>(
                apiUrl("api", "session", sid, "message", query = listOf("limit" to "50", "cursor" to cursor)),
            ) ?: return
            historyCursor = page.cursor?.next
            emit(
                RemoteAgentEvent.HistoryPage(
                    turns = page.data.asReversed().mapNotNull { it.toTurn() },
                    before = page.cursor?.next,
                    hasMore = page.cursor?.next != null,
                ),
            )
        }.onFailure { e -> notifyFailure("加载历史失败", e) }
    }

    /**
     * OpenCode 没有上传端点：字节以 `data:` URI 内联在下一条 prompt 里。
     * 这里先暂存并返回一个不透明的本地 id，[sendMessage] 发送时再编码、清账。
     */
    override suspend fun uploadAttachment(name: String, mime: String, bytes: ByteArray): String {
        val id = "ocatt-${uploadSeq.incrementAndGet()}"
        pendingUploads[id] = PendingUpload(name, mime, bytes)
        return id
    }

    override suspend fun fetchAttachment(id: String): ByteArray = withContext(Dispatchers.IO) {
        when {
            id.startsWith("data:") -> android.util.Base64.decode(
                id.substringAfter(',', id),
                android.util.Base64.DEFAULT,
            )
            id.startsWith("media:") -> callHttp(
                apiUrl("api", "experimental", "media", id.removePrefix("media:")),
                "GET",
            ).use { response ->
                if (!response.isSuccessful) error("附件下载失败：HTTP ${response.code}")
                response.body?.bytes() ?: ByteArray(0)
            }
            else -> throw IllegalArgumentException("无法识别的附件引用：$id")
        }
    }

    // ─── HTTP 与地址 ────────────────────────────────────────────────────────

    private fun apiUrl(vararg segments: String, query: List<Pair<String, String>> = emptyList()): HttpUrl {
        val builder = HttpUrl.Builder()
            .scheme("http")
            .host(RemoteServerAddress.bareHost(config.host))
            .port(config.port)
        segments.filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        query.forEach { (name, value) -> builder.addQueryParameter(name, value) }
        return builder.build()
    }

    private fun eventUrl(): HttpUrl = apiUrl("api", "event")

    /** opencode 的鉴权是 HTTP Basic，用户名固定 `opencode`，密码在配置的令牌栏里。 */
    private fun Request.Builder.withAuth(): Request.Builder = apply {
        val password = config.authToken.trim()
        if (password.isNotEmpty()) header("Authorization", Credentials.basic(AUTH_USERNAME, password))
    }

    private suspend fun callHttp(url: HttpUrl, method: String, body: String? = null) =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            when (method) {
                "POST" -> builder.post((body ?: "").toRequestBody())
                "DELETE" -> builder.delete()
                else -> builder.get()
            }
            httpClient.newCall(builder.withAuth().build()).execute()
        }

    private suspend inline fun <reified T> getJson(url: HttpUrl): T? = runCatching {
        callHttp(url, "GET").use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()?.let { json.decodeFromString<T>(it) }
        }
    }.onFailure { e ->
        AndroidAgentLogger.warn("Remote opencode GET failed: ${e.message}")
    }.getOrNull()

    /** 同 [getJson]，另外把响应的 `Date` 头记成重建完成标记（服务器时钟，秒级 + 余量）。 */
    private suspend inline fun <reified T> getJsonWithMarker(url: HttpUrl, fallbackMarker: Long?): T? = runCatching {
        callHttp(url, "GET").use { response ->
            fetchMarkerMs = (response.headers.getDate("Date")?.time ?: fallbackMarker ?: 0L) + FETCH_MARKER_SLACK_MS
            if (!response.isSuccessful) return@use null
            response.body?.string()?.let { json.decodeFromString<T>(it) }
        }
    }.onFailure { e ->
        AndroidAgentLogger.warn("Remote opencode GET failed: ${e.message}")
    }.getOrNull()

    private fun notifyFailure(prefix: String, e: Throwable) {
        AndroidAgentLogger.warn("Remote opencode $prefix: ${e.javaClass.simpleName} ${e.message}")
        emit(RemoteAgentEvent.Notify("error", "$prefix：${e.message ?: "网络不可用"}"))
    }

    // ─── 投影辅助 ───────────────────────────────────────────────────────────

    private fun emit(event: RemoteAgentEvent) {
        scope.launch(Dispatchers.Main.immediate) { onEvent(event) }
    }

    private fun OpencodeSessionInfo.toRemote(): RemoteSessionInfo = RemoteSessionInfo(
        path = id ?: "",
        name = title,
        firstMessage = "",
        modified = time?.updated?.let { java.time.Instant.ofEpochMilli(it).toString() } ?: "",
        messageCount = 0,
        unsaved = false,
    )

    private fun OpencodeModelInfo.toOption(): RemoteModelOption = RemoteModelOption(
        spec = "${providerID.orEmpty()}/${id.orEmpty()}",
        name = name ?: id ?: "unknown",
    )

    private fun OpencodeTokens.toContextUsage(): RemoteContextUsage? {
        val total = total ?: return null
        val window = contextWindow
        return RemoteContextUsage(
            tokens = total.toInt(),
            contextWindow = window?.toInt(),
            percent = if (window != null && window > 0) total * 100.0 / window else null,
        )
    }

    private fun OpencodePermission.toPrompt(): RemotePrompt = RemotePrompt(
        id = id ?: "",
        question = message?.takeIf { it.isNotBlank() }
            ?: "${action ?: "permission"}：${resources.joinToString("、").ifBlank { "未指定资源" }}",
        actions = listOf(
            RemotePromptAction("Once", "once"),
            RemotePromptAction("Always", "always"),
            RemotePromptAction("Reject", "reject"),
        ),
    )

    private fun OpencodeMessage.toTurn(): RemoteTurn? = when (type) {
        "user" -> RemoteTurn(
            role = "user",
            text = text,
            attachments = files.map { file ->
                val uri = file.uri.orEmpty()
                val mime = file.mime ?: uri.substringAfter("data:", "").substringBefore(";base64")
                RemoteAttachment(
                    id = uri,
                    name = file.name ?: attachmentNameOf(uri),
                    kind = if (mime.startsWith("image/")) "image" else "file",
                    mimeType = mime,
                    size = 0,
                    // 默认响应里字节内联在 data；历史渲染只用名字，不把几 MB 的 base64 搬进 UI 模型。
                    url = uri,
                )
            },
            ts = time?.created,
        )
        "assistant" -> RemoteTurn(
            role = "assistant",
            text = error?.message,
            parts = content.mapNotNull { it.toPart() },
            error = error != null,
            ts = time?.created,
        )
        "synthetic", "system" -> RemoteTurn(role = "system", text = text, ts = time?.created)
        "skill" -> RemoteTurn(role = "system", text = text ?: "skill: ${name.orEmpty()}", ts = time?.created)
        "compaction" -> RemoteTurn(role = "system", text = summary ?: "历史已压缩", ts = time?.created)
        "shell" -> RemoteTurn(
            role = "assistant",
            parts = listOfNotNull(
                command?.let { cmd ->
                    RemotePart.Tool(
                        toolCallId = id ?: "shell",
                        toolName = "shell",
                        argsJson = buildJsonObject { put("command", cmd) }.toString(),
                        resultJson = output?.output,
                        isError = status == "timeout" || status == "killed",
                        done = status != "running",
                        elapsedMs = null,
                    )
                },
            ),
            ts = time?.created,
        )
        else -> null // agent-switched / model-switched / location-switched / idle：无界面承载
    }

    private fun OpencodeContent.toPart(): RemotePart? = when (type) {
        "text" -> RemotePart.Text(text ?: "")
        "reasoning" -> RemotePart.Thinking(text ?: "", done = true)
        "tool" -> {
            val st = state
            val toolError = st?.error
            val toolContent = st?.content
            RemotePart.Tool(
                toolCallId = id ?: "",
                toolName = name ?: "",
                argsJson = st?.input.jsonText(),
                resultJson = when {
                    toolError != null -> toolError.message ?: "工具执行失败"
                    toolContent != null -> toolContent.toString()
                    else -> null
                },
                isError = toolError != null,
                done = st?.status == "completed" || st?.status == "error",
                elapsedMs = time?.let { t ->
                    val created = t.created
                    val completed = t.completed
                    if (created != null && completed != null) (completed - created).coerceAtLeast(0) else null
                },
            )
        }
        else -> null
    }

    /** 工具输入在 streaming 态是原始 JSON 文本、其余态是对象；统一成可显示的文本。 */
    private fun JsonElement?.jsonText(): String? = when (this) {
        null -> null
        is JsonPrimitive -> if (isString) content else toString()
        else -> toString()
    }

    private fun applyModel(ref: OpencodeModelRef?, models: List<OpencodeModelInfo>) {
        modelCache = models
        val id = ref?.id ?: return
        val providerID = ref.providerID ?: return
        modelSpec = "$providerID/$id"
        val info = models.firstOrNull { it.providerID == providerID && it.id == id }
        modelLabel = info?.let { it.name ?: it.id } ?: modelSpec
        contextWindow = info?.limit?.context
    }

    private suspend fun defaultModelRef(): OpencodeModelRef? {
        val info = getJson<OpencodeDefaultModelEnvelope>(apiUrl("api", "model", "default"))?.data ?: return null
        val id = info.id ?: return null
        val providerID = info.providerID ?: return null
        return OpencodeModelRef(id = id, providerID = providerID)
    }

    private fun JsonObject.toModelRef(): OpencodeModelRef? {
        val id = str("id") ?: return null
        val providerID = str("providerID") ?: return null
        return OpencodeModelRef(id = id, providerID = providerID)
    }

    private fun attachmentNameOf(uri: String): String =
        if (uri.startsWith("data:image/")) "图片" else "附件"

    private fun PendingUpload.dataUri(): String =
        "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    // ─── JSON 便捷取值 ──────────────────────────────────────────────────────

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    companion object {
        private const val AUTH_USERNAME = "opencode"

        /** 重建完成标记的时钟余量：HTTP `Date` 只到秒，向后多放宽一截防边界重影。 */
        private const val FETCH_MARKER_SLACK_MS = 2_000L

        /** 事件量大且无界面承载（进度增量、执行生命周期、压缩流等），静默忽略让日志保持干净。 */
        private val IGNORED_TYPES = setOf(
            "session.text.started",
            "session.reasoning.started",
            "session.tool.input.delta",
            "session.tool.input.ended",
            "session.tool.progress",
            "session.step.started",
            "session.step.streamed",
            "session.retry.scheduled",
            "session.compaction.started",
            "session.compaction.delta",
            "session.compaction.ended",
            "session.compaction.failed",
            "session.execution.started",
            "session.execution.succeeded",
            "session.execution.failed",
            "session.execution.interrupted",
            "session.inbox.delivered",
            "session.agent.selected",
            "session.renamed",
            "session.archived",
            "session.unarchived",
            "session.viewed",
            "session.usage.updated",
            "session.created",
        )
    }
}
