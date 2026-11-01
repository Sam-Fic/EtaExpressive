package io.github.mangi.eta.agent.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * A pluggable remote agent protocol. [open] builds a live [RemoteSession] for a
 * configured server and starts streaming normalized [RemoteAgentEvent]s.
 *
 * The first implementation is pi-remote (`agent/remote/pi`); opencode and others
 * plug in here without the UI bridge changing.
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
 */
interface RemoteSession {
    val status: StateFlow<RemoteConnectionStatus>

    /**
     * Send a chat line. Returns false when the frame could not be handed to the
     * socket (disconnected), so the caller can roll back an optimistic echo.
     */
    suspend fun sendMessage(text: String, attachmentIds: List<String> = emptyList()): Boolean
    suspend fun answerPrompt(id: String, value: String?)
    suspend fun interrupt()
    suspend fun setModel(spec: String)

    /**
     * Start a brand-new session server-side. The server answers by making it the
     * active session: a reset + snapshot rebuild the transcript and the next
     * `sessions` frame carries the new path as `current`.
     *
     * Returns false when the frame could not be handed to the socket (disconnected),
     * like [sendMessage].
     */
    suspend fun newSession(): Boolean
    suspend fun switchSession(path: String)
    suspend fun deleteSession(path: String)
    suspend fun listSessions()

    /** Request a page older than the live window; the answer arrives as [RemoteAgentEvent.HistoryPage]. */
    suspend fun fetchHistory(before: String?)

    /** Upload bytes for a chat attachment; returns the server-assigned id. */
    suspend fun uploadAttachment(name: String, mime: String, bytes: ByteArray): String

    /** Download a previously uploaded attachment's bytes. */
    suspend fun fetchAttachment(id: String): ByteArray

    fun close()
}
