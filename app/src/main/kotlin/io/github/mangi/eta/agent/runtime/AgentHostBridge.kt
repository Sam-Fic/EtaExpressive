package io.github.mangi.eta.agent.runtime

import android.os.ParcelFileDescriptor
import io.github.mangi.eta.agent.model.AgentModelClient

internal object AgentHostCapabilities {
    const val AIASST_VISION_APP_FUNCTIONS = "xiaomi.aiasst_vision.app_functions.v1"
    const val EXTERNAL_AGENT = "xiaomi.external_agent.v1"
}

/** Request executed inside a trusted injected package process. */
internal data class AgentHostCall(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
    val attachments: List<AgentHostAttachment> = emptyList(),
)

internal data class AgentHostAttachment(
    val name: String,
    val mimeType: String,
    val uri: String? = null,
    val fileDescriptor: ParcelFileDescriptor? = null,
)

internal data class AgentHostEvent(
    val callId: String,
    val type: String,
    val sessionId: String,
    val payload: String,
)

internal data class AgentHostResult(
    val callId: String,
    val ok: Boolean,
    val payload: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val retryable: Boolean = false,
    val attachments: List<AgentHostAttachment> = emptyList(),
)

/** Implemented by target-process adapters, never by the Eta application process. */
internal interface AgentHostBridge : AutoCloseable {
    val capabilities: Set<String>

    fun execute(
        call: AgentHostCall,
        onEvent: (AgentHostEvent) -> Unit,
    ): AgentHostResult
}

/** Runtime-side facade used by the ordinary model tool executor. */
internal interface AgentHostToolExecutor : AutoCloseable {
    val toolNames: Set<String>

    fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult
}
