package io.github.mangi.eta.agent.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * A pluggable remote agent protocol. [open] builds a live [RemoteSession] for a
 * configured server and starts streaming normalized [RemoteAgentEvent]s.
 *
 * The first implementation is pi-remote (`agent/remote/pi`); OpenCode v2
 * (`agent/remote/opencode`) and others plug in here without the UI bridge changing.
 */
interface RemoteAgentProtocolAdapter {
    val protocolId: String
    val capabilities: RemoteCapabilities
    fun open(
        config: RemoteServerConfig,
        scope: CoroutineScope,
        onEvent: (RemoteAgentEvent) -> Unit,
    ): RemoteSession
}

/**
 * One live connection to a remote server. Every command is fire-and-forget over
 * the wire; results arrive later as [RemoteAgentEvent]s on the adapter's sink.
 *
 * The connection is supervised: it reconnects on drop and relies on the server's
 * snapshot-on-connect to rebuild state, so callers never dedupe deltas.
 *
 * **只有 [status] / [sendMessage] / [close] 是每个协议都必须实现的**。其余能力按
 * [RemoteCapabilities] 分档：声明 `false` 的协议可以整段不写，接口给了语义正确的
 * 默认实现（命令被丢掉 / 返回 false），这样接一个新协议不必先补八个空方法——
 * 空方法写多了，谁分得清哪个是「协议确实忽略」哪个是「忘了写」。
 * 附件这两个方法例外：它们是带返回值的 IO，默认实现会抛错，靠 capabilities 把关
 * （`supportsAttachments = false` 时界面不会调用）。
 */
interface RemoteSession {
    val status: StateFlow<RemoteConnectionStatus>

    /**
     * Send a chat line. Returns false when the frame could not be handed to the
     * socket (disconnected), so the caller can roll back an optimistic echo.
     */
    suspend fun sendMessage(text: String, attachmentIds: List<String> = emptyList()): Boolean

    /** 回答服务端弹出的一个提问；协议没有提问机制时不用实现。 */
    suspend fun answerPrompt(id: String, value: String?) = Unit

    suspend fun interrupt() = Unit

    /** 切换模型；`supportsModelSwitch = false` 的协议不用实现。 */
    suspend fun setModel(spec: String) = Unit

    /**
     * Start a brand-new session server-side. The server answers by making it the
     * active session: a reset + snapshot rebuild the transcript and the next
     * `sessions` frame carries the new path as `current`.
     *
     * Returns false when the frame could not be handed to the socket (disconnected),
     * like [sendMessage]. 默认返回 false：没有会话概念的协议（单一长连接）就是这种语义。
     */
    suspend fun newSession(): Boolean = false
    suspend fun switchSession(path: String) = Unit
    suspend fun deleteSession(path: String) = Unit

    /** 拉一次会话列表；支持会话的协议在连接建立后自己发 [RemoteAgentEvent.SessionsUpdated]。 */
    suspend fun listSessions() = Unit

    /** Request a page older than the live window; the answer arrives as [RemoteAgentEvent.HistoryPage]. */
    suspend fun fetchHistory(before: String?) = Unit

    /** Upload bytes for a chat attachment; returns the server-assigned id. */
    suspend fun uploadAttachment(name: String, mime: String, bytes: ByteArray): String =
        throw UnsupportedOperationException(
            "adapter does not support attachments; check RemoteCapabilities.supportsAttachments first",
        )

    /** Download a previously uploaded attachment's bytes. */
    suspend fun fetchAttachment(id: String): ByteArray =
        throw UnsupportedOperationException(
            "adapter does not support attachments; check RemoteCapabilities.supportsAttachments first",
        )

    fun close()
}
