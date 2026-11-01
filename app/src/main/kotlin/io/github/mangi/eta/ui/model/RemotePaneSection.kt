package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus
import io.github.mangi.eta.agent.remote.RemoteSessionInfo

/**
 * One configured remote server as the conversation sidebar sees it: live connection
 * status plus the session list pi reported. Kept out of [ConversationPaneUiState] so
 * remote state never mixes into local search / folders / trash.
 */
@Immutable
data class RemotePaneServer(
    val id: String,
    val name: String,
    val status: RemoteConnectionStatus,
    val sessions: List<RemoteSessionInfo> = emptyList(),
    val currentPath: String? = null,
)

/**
 * The sidebar's remote group. [selectedServerId] / [selectedPath] mark the row the chat
 * is currently rendering so it highlights like an active local conversation.
 */
@Immutable
data class RemotePaneSection(
    val servers: List<RemotePaneServer> = emptyList(),
    val selectedServerId: String? = null,
    val selectedPath: String? = null,
)
