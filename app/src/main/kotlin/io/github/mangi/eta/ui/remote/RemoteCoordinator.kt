package io.github.mangi.eta.ui.remote

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus
import io.github.mangi.eta.agent.remote.RemoteFailure
import io.github.mangi.eta.agent.remote.RemoteHttpClient
import io.github.mangi.eta.agent.remote.RemoteServerConfig
import io.github.mangi.eta.agent.remote.RemoteSession
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentChatAction
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.AgentModelProviderGroupUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.RemotePaneSection
import io.github.mangi.eta.ui.model.RemotePaneServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the chat screens render: either the local agent run or one remote server's
 * active session. [Remote] carries the server id and the session path so a switch
 * back to [Local] is lossless and the remote draft/scroll keys stay isolated.
 */
sealed interface ChatTarget {
    data object Local : ChatTarget
    data class Remote(val serverId: String, val path: String) : ChatTarget
}

/**
 * 「+」新建远程会话的挂起请求：记录目标服务器与发出请求时的 current。
 * sessions 帧给同一服务器带回**不同的** current，即视为服务端已建好并切到新会话。
 */
data class NewSessionRequest(val serverId: String, val previousCurrent: String?)

/**
 * Process-singleton hub for remote agent connections, mounted on `AgentSessionHost`.
 *
 * Responsibilities:
 * - mirror the persisted server list and keep one supervised connection per server
 *   (reconnecting on config edits, tearing down on removal);
 * - expose Compose-observable status/session maps for the sidebar's remote group;
 * - project each connection's folded [AgentChatUiState] for the chat screens;
 * - translate [AgentChatAction]s into remote commands, and hold a per-session draft
 *   so switching sessions never leaks text between them.
 *
 * Remote sessions never touch Room or the local conversation registry: this class and
 * the holder are the only owners of remote state, and it is all in-memory (a reconnect
 * snapshot rebuilds it). Everything mutates on the host's Main.immediate scope.
 */
internal class RemoteCoordinator(
    private val appContext: android.content.Context,
    private val scope: CoroutineScope,
) {

    private class Connection(
        val config: RemoteServerConfig,
        val session: RemoteSession,
        val holder: RemoteSessionStateHolder,
        val statusJob: Job,
        val sessionsJob: Job,
        val modelsJob: Job,
    )

    /** Snapshot map so the sidebar recomposes when a connection appears or disappears. */
    private val connections = mutableStateMapOf<String, Connection>()

    /** Persisted server list, mirrored for the sidebar and settings. */
    var servers by mutableStateOf<List<RemoteServerConfig>>(emptyList())
        private set

    /** Per-server connection status; drives the sidebar dot and the chat banner. */
    val statusById = mutableStateMapOf<String, RemoteConnectionStatus>()

    /** Per-server session list + active path; drives the sidebar's remote rows. */
    val sessionsById = mutableStateMapOf<String, RemoteSessionsState>()

    /** Per-server switchable model list + current spec; drives the remote model picker. */
    val modelsById = mutableStateMapOf<String, RemoteModelsState>()

    /**
     * 「+」新建远程会话：请求中的服务器及其发出请求时的 current；解析完成后清除。
     * 私有——解析由 [resolveNewSessionRequest] 在 sessions 流上完成，UI 只听 [newSessionReady]。
     */
    private var newSessionRequest: NewSessionRequest? by mutableStateOf(null)

    /** 解析完成的新会话落位目标；由 AgentAppRoot 收集并切 selectedTarget（服务端已切好，无需再发 switch_session）。 */
    private val _newSessionReady = MutableSharedFlow<ChatTarget.Remote>(extraBufferCapacity = 1)
    val newSessionReady: SharedFlow<ChatTarget.Remote> = _newSessionReady.asSharedFlow()

    private val drafts = mutableMapOf<String, TextFieldState>()

    /** 判死期间点下的目标会话（重连成功拿到 sessions 帧后补发切换，见 [resolvePendingSwitch]）。 */
    private val pendingSwitchByServer = mutableMapOf<String, String>()
    private var configJob: Job? = null

    init {
        // 兜底注入：远程客户端要把 socket 绑到 Wi-Fi 网络就得有 Context，
        // 全局初始化只要漏一次，那个「换 Wi-Fi 重试」就会静默退化成不生效。
        RemoteHttpClient.initialize(appContext)
    }

    fun start() {
        configJob?.cancel()
        configJob = scope.launch {
            SettingsDataStore.remoteServersFlow().collect { list ->
                servers = list
                reconcile(list)
            }
        }
    }

    private fun reconcile(list: List<RemoteServerConfig>) {
        val ids = list.mapTo(HashSet()) { it.id }
        connections.keys.filterNot { it in ids }.forEach { disconnect(it) }
        list.forEach { config ->
            val existing = connections[config.id]
            when {
                existing == null -> connect(config)
                existing.config != config -> {
                    disconnect(config.id)
                    connect(config)
                }
            }
        }
    }

    private fun connect(config: RemoteServerConfig) {
        // 协议 → 适配器只在 RemoteProtocolRegistry 里登记一处；这里不认识就是真的没适配，
        // 不能悄悄退回 pi 去连——那会把「服务器不是 pi」错报成「连不上」。
        val adapter = io.github.mangi.eta.agent.remote.RemoteProtocolRegistry.adapterFor(config.protocolId)
        if (adapter == null) {
            statusById[config.id] = RemoteConnectionStatus.Failed(
                RemoteFailure.Kind.UnsupportedProtocol,
                config.protocolId,
            )
            return
        }
        val holder = RemoteSessionStateHolder(scope)
        val session = adapter.open(config, scope) { event -> holder.onEvent(event) }
        holder.attach(session)
        val statusJob = scope.launch { session.status.collect { statusById[config.id] = it } }
        val sessionsJob = scope.launch {
            holder.sessions.collect {
                sessionsById[config.id] = it
                resolveNewSessionRequest(config.id, it.current)
                resolvePendingSwitch(config.id, it.current)
            }
        }
        val modelsJob = scope.launch { holder.models.collect { modelsById[config.id] = it } }
        connections[config.id] = Connection(config, session, holder, statusJob, sessionsJob, modelsJob)
        statusById[config.id] = session.status.value
        sessionsById[config.id] = holder.sessions.value
        modelsById[config.id] = holder.models.value
    }

    private fun disconnect(serverId: String) {
        val connection = connections.remove(serverId)
        statusById.remove(serverId)
        sessionsById.remove(serverId)
        modelsById.remove(serverId)
        pendingSwitchByServer.remove(serverId)
        if (connection == null) return
        connection.statusJob.cancel()
        connection.sessionsJob.cancel()
        connection.modelsJob.cancel()
        runCatching { connection.session.close() }
    }

    /** Chat projection for a remote target; an empty remote state until the connection is up. */
    fun chatState(target: ChatTarget.Remote): AgentChatUiState =
        connections[target.serverId]?.holder?.chatState ?: EMPTY_REMOTE_STATE

    fun statusOf(serverId: String): RemoteConnectionStatus =
        statusById[serverId] ?: RemoteConnectionStatus.Disconnected

    /**
     * 这台服务器所用协议能表达什么；协议没适配时返回 null。
     *
     * 第二个协议接进来时，「切模型 / 会话列表 / 附件」这些控件靠它决定显不显示，
     * 而不是在页面里各自判断协议 id。
     */
    fun capabilitiesOf(serverId: String): io.github.mangi.eta.agent.remote.RemoteCapabilities? =
        servers.find { it.id == serverId }
            ?.let { io.github.mangi.eta.agent.remote.RemoteProtocolRegistry.capabilitiesOf(it.protocolId) }

    /** Manual reconnect after a terminal [RemoteConnectionStatus.Failed]; tears down and re-opens. */
    fun retry(serverId: String) {
        val config = servers.find { it.id == serverId } ?: return
        disconnect(serverId)
        connect(config)
    }

    /**
     * Sidebar projection: one group per configured server carrying its live status and
     * session list, plus which server/session is currently rendered so the row highlights.
     * Built fresh each recomposition from the Compose-observable maps, so the drawer
     * updates as connections come and go without any extra plumbing.
     */
    fun paneSection(selected: ChatTarget): RemotePaneSection {
        val selectedRemote = selected as? ChatTarget.Remote
        return RemotePaneSection(
            servers = servers.map { config ->
                val sessionsState = sessionsById[config.id]
                RemotePaneServer(
                    id = config.id,
                    name = config.name,
                    status = statusById[config.id] ?: RemoteConnectionStatus.Disconnected,
                    sessions = sessionsState?.sessions ?: emptyList(),
                    currentPath = sessionsState?.current,
                )
            },
            selectedServerId = selectedRemote?.serverId,
            selectedPath = selectedRemote?.path,
        )
    }

    /**
     * Remote model picker: one synthetic "remote" provider group whose option ids are the
     * server's model specs, so [AgentChatAction.ModelSelected] carries the spec straight to
     * [RemoteSession.setModel]. Empty until the connection reports its `models` frame.
     */
    fun modelPickerState(target: ChatTarget.Remote): AgentModelPickerUiState {
        val models = modelsById[target.serverId] ?: return AgentModelPickerUiState()
        val serverName = servers.find { it.id == target.serverId }?.name ?: "remote"
        val options = models.options.map { option ->
            AgentModelOptionUi(
                id = option.spec,
                providerId = "remote",
                providerName = serverName,
                providerSourceType = "remote",
                modelId = option.spec,
                displayName = option.name,
                contextWindow = null,
            )
        }
        if (options.isEmpty()) return AgentModelPickerUiState()
        return AgentModelPickerUiState(
            providerGroups = listOf(
                AgentModelProviderGroupUi(
                    providerId = "remote",
                    providerName = serverName,
                    providerSourceType = "remote",
                    models = options,
                ),
            ),
            selectedModel = options.firstOrNull { it.id == models.current },
        )
    }

    /** Isolated per remote session so switching never carries text over. */
    fun draftFieldFor(target: ChatTarget.Remote): TextFieldState {
        val key = "${target.serverId}:${target.path}"
        return drafts.getOrPut(key) { TextFieldState() }
    }

    /**
     * 远程会话的输入草稿归本类所有（[draftFieldFor]），Composer 只把正文随 action 交上来，
     * 自己不会改 TextFieldState。本地路径是 AgentAppState.sendCurrentMessage 清草稿，
     * 远程必须在这里同样清一次：否则正文已经进了乐观气泡，输入框还留着同一段字，
     * 再点一次发送就是重复发同一条。
     *
     * 没送出去的原文由 holder 回调填回来（连接未就绪 / 帧发出去失败都会回调），
     * 且只在草稿仍为空时填，不覆盖用户在这段时间里新敲的字。
     *
     * 清空用 `TextFieldState.setTextAndPlaceCursorAtEnd(String)`（foundation 扩展，签名
     * `text: String`），与 ConversationDrafts.replace 清本地草稿同款；传空串即正文归零、光标归零。
     */
    private fun submitText(target: ChatTarget.Remote, text: String) {
        val connection = connections[target.serverId] ?: return
        val field = draftFieldFor(target)
        val accepted = connection.holder.sendMessage(text) { undelivered ->
            if (field.text.isEmpty()) field.setTextAndPlaceCursorAtEnd(undelivered)
        }
        if (accepted) field.setTextAndPlaceCursorAtEnd("")
    }

    fun conversationKey(target: ChatTarget.Remote): String = "remote:${target.serverId}:${target.path}"

    /**
     * 「+」新建远程会话：发 new_session 帧，等服务端把新会话置为 current 后经
     * [newSessionReady] 通知落位（服务端已切好，落位无需再发 switch_session）。
     * 帧送不出去（断连）时清请求并在转录本上留提示；旧版服务端会静默忽略未知帧，
     * 请求挂住无表现（两端同步升级，可接受）。
     */
    fun requestNewSession(serverId: String) {
        val connection = connections[serverId] ?: return
        newSessionRequest = NewSessionRequest(serverId, sessionsById[serverId]?.current)
        scope.launch {
            val delivered = runCatching { connection.session.newSession() }.getOrDefault(false)
            if (!delivered && newSessionRequest?.serverId == serverId) {
                newSessionRequest = null
                connection.holder.notice("新建会话失败：与远程服务器的连接不可用", isError = true)
            }
        }
    }

    /** 显式点了某个会话行：丢弃挂起中的新建请求，避免稍后的落位把用户刚点的选择顶掉。 */
    fun consumeNewSessionRequest() {
        newSessionRequest = null
    }

    /** sessions 帧命中挂起请求且 current 已变化：清请求并广播落位目标。 */
    private fun resolveNewSessionRequest(serverId: String, current: String?) {
        val request = newSessionRequest ?: return
        if (request.serverId != serverId) return
        if (current == null || current == request.previousCurrent) return
        newSessionRequest = null
        _newSessionReady.tryEmit(ChatTarget.Remote(serverId, current))
    }

    /** 判死期间点下的目标会话：重连成功拿到 sessions 帧后补发切换（服务端当前会话相同则免发）。 */
    private fun resolvePendingSwitch(serverId: String, current: String?) {
        val pending = pendingSwitchByServer.remove(serverId) ?: return
        val connection = connections[serverId] ?: return
        if (current != null && current != pending) {
            scope.launch { connection.session.switchSession(pending) }
        }
    }

    /**
     * Open (lazily already-connected) a remote session. pi keeps ONE active session per
     * server, so switching is server-global; the caller shows the confirmation before this.
     */
    fun selectRemoteSession(target: ChatTarget.Remote) {
        val connection = connections[target.serverId] ?: return
        // 自动重连到上限已判死的连接：切换会话本身就是「我要用了」的信号——重开连接，
        // 记住目标路径，等重连成功后的 sessions 帧再补发切换（socket 没起来前发帧只会丢）。
        if (statusById[target.serverId] is RemoteConnectionStatus.Failed) {
            pendingSwitchByServer[target.serverId] = target.path
            retry(target.serverId)
            return
        }
        if (sessionsById[target.serverId]?.current != target.path) {
            scope.launch { connection.session.switchSession(target.path) }
        }
    }

    fun handleChatAction(target: ChatTarget.Remote, action: AgentChatAction) {
        val connection = connections[target.serverId] ?: return
        when (action) {
            is AgentChatAction.SubmitMessage -> submitText(target, action.text)
            AgentChatAction.StopRun -> scope.launch { connection.session.interrupt() }
            is AgentChatAction.QuestionDraftChanged -> connection.holder.updatePromptDraft(action.answer)
            is AgentChatAction.SubmitQuestionAnswer -> {
                val value = connection.holder.resolvePromptValue(action.questionId)
                scope.launch { connection.session.answerPrompt(action.questionId, value) }
            }
            is AgentChatAction.ModelSelected -> scope.launch { connection.session.setModel(action.modelId) }
            is AgentChatAction.ImageAttached -> attachImage(connection, action.uri)
            is AgentChatAction.RemoveImage -> connection.holder.removePendingImage(action.id)
            is AgentChatAction.FilesAttached -> unsupportedAttachment(connection)
            is AgentChatAction.FolderAttached -> unsupportedAttachment(connection)
            is AgentChatAction.FilePathAttached -> unsupportedAttachment(connection)
            is AgentChatAction.VideoAttached -> unsupportedAttachment(connection)
            // The remaining local-only actions (edit/delete/regenerate/branch/effort)
            // are no-ops on a remote transcript.
            else -> Unit
        }
    }

    /** Home-stage twin of [handleChatAction]; the two screens share one action vocabulary. */
    fun handleHomeAction(target: ChatTarget.Remote, action: AgentHomeAction) {
        val connection = connections[target.serverId] ?: return
        when (action) {
            is AgentHomeAction.SubmitMessage -> submitText(target, action.text)
            AgentHomeAction.StopRun -> scope.launch { connection.session.interrupt() }
            is AgentHomeAction.QuestionDraftChanged -> connection.holder.updatePromptDraft(action.answer)
            is AgentHomeAction.SubmitQuestionAnswer -> {
                val value = connection.holder.resolvePromptValue(action.questionId)
                scope.launch { connection.session.answerPrompt(action.questionId, value) }
            }
            is AgentHomeAction.ModelSelected -> scope.launch { connection.session.setModel(action.modelId) }
            is AgentHomeAction.ImageAttached -> attachImage(connection, action.uri)
            is AgentHomeAction.RemoveImage -> connection.holder.removePendingImage(action.id)
            is AgentHomeAction.FilesAttached -> unsupportedAttachment(connection)
            is AgentHomeAction.FolderAttached -> unsupportedAttachment(connection)
            is AgentHomeAction.FilePathAttached -> unsupportedAttachment(connection)
            is AgentHomeAction.VideoAttached -> unsupportedAttachment(connection)
            else -> Unit
        }
    }

    /**
     * Read the picked image, upload it to the server, and stage the returned id as a pending
     * attachment so the composer shows a preview and the next send carries it. pi accepts image
     * uploads only here; files/folders/videos get an honest transcript notice instead.
     *
     * 先过协议能力闸：声明不支持附件的协议走同一条「不支持」提示，
     * 而不是让 [RemoteSession.uploadAttachment] 的默认实现抛异常炸掉协程。
     */
    private fun attachImage(connection: Connection, uri: String) {
        if (capabilitiesOf(connection.config.id)?.supportsAttachments == false) {
            unsupportedAttachment(connection)
            return
        }
        scope.launch(Dispatchers.IO) {
            val image = runCatching {
                io.github.mangi.eta.agent.media.AgentImageCodec.fromReference(appContext, uri, "user_attach")
            }.getOrNull()
            val bytes = image?.let { decodeDataUrl(it.reference) }
            if (image == null || bytes == null) {
                withContext(Dispatchers.Main.immediate) {
                    connection.holder.notice(appContext.getString(R.string.remote_attach_failed), isError = true)
                }
                return@launch
            }
            val name = "image-${System.currentTimeMillis()}.${extensionFor(image.mimeType)}"
            val id = runCatching { connection.session.uploadAttachment(name, image.mimeType, bytes) }.getOrNull()
            val srcWidth = image.width
            val srcHeight = image.height
            val bounds = if (srcWidth != null && srcHeight != null && srcWidth > 0 && srcHeight > 0) {
                srcWidth to srcHeight
            } else {
                io.github.mangi.eta.agent.media.AgentImageCodec.boundsOfReference(appContext, image.reference)
            }
            withContext(Dispatchers.Main.immediate) {
                if (id == null) {
                    connection.holder.notice(appContext.getString(R.string.remote_attach_failed), isError = true)
                } else {
                    connection.holder.stagePendingImage(
                        PendingImageUi(
                            id = id,
                            uri = image.reference,
                            dataUrl = image.reference,
                            mimeType = image.mimeType,
                            byteSize = image.bytes,
                            widthPx = bounds?.first,
                            heightPx = bounds?.second,
                        ),
                    )
                }
            }
        }
    }

    private fun unsupportedAttachment(connection: Connection) {
        connection.holder.notice(appContext.getString(R.string.remote_attach_unsupported), isError = true)
    }

    private fun decodeDataUrl(reference: String): ByteArray? {
        val comma = reference.indexOf(',')
        if (!reference.startsWith("data:") || comma < 0) return null
        return runCatching {
            android.util.Base64.decode(reference.substring(comma + 1), android.util.Base64.DEFAULT)
        }.getOrNull()
    }

    private fun extensionFor(mime: String): String = when {
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        else -> "img"
    }

    private companion object {
        val EMPTY_REMOTE_STATE = AgentChatUiState(
            messages = emptyList(),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
            isRemote = true,
            providerId = "remote",
        )
    }
}
