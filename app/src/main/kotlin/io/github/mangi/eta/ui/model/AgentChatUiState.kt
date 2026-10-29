package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.question.AgentQuestionRequest
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort

@Immutable
internal data class AgentChatUiState(
    val messages: List<AgentChatMessageUi>,
    val history: List<AgentModelClient.ConversationMessage> = emptyList(),
    val input: String,
    val isStreaming: Boolean,
    val isPaused: Boolean = false,
    val isWaitingForAnswer: Boolean = false,
    val isCompressingContext: Boolean = false,
    val compactingModelName: String = "",
    val isWaitingForCompression: Boolean = false,
    val thinkingEnabled: Boolean,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.fromLegacy(thinkingEnabled),
    val providerId: String = "",
    val modelId: String = "",
    val assistantId: String = "",
    val availableReasoningEfforts: List<ReasoningEffort> = emptyList(),
    val pendingImages: List<PendingImageUi> = emptyList(),
    val pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    val pendingConversationMentions: List<PendingConversationMentionUi> = emptyList(),
    val appliedRuntimeRunIds: List<String> = emptyList(),
    val messageEdit: MessageEditUiState? = null,
    /** Current request's cloud receipt only; runtime projections are not actual usage. */
    val livePromptTokens: Int? = null,
    val livePromptIsProjected: Boolean = false,
    /** Last real receipt retained for conservative budget deltas, never displayed as actual. */
    val contextBudgetReceiptTokens: Int? = null,
    /** 旧显示学习字段：为兼容持久化保留，圆环已不再读取（见 contextDisplayPolicy）。 */
    val receiptPredictionTokens: Int? = null,
    val contextHasStarted: Boolean = false,
    val contextAwaitingReceipt: Boolean = false,
    val contextReceiptEvidence: ContextReceiptEvidence? = null,
    val cloudReceiptRequestId: String? = null,
    val cloudRouteSignature: String? = null,
    val cloudHistoryTokens: Int? = null,
    val cloudRequestOverheadTokens: Int? = null,
    /**
     * Window the in-flight run was launched with, when it differs from the window the
     * picker currently reports. A run keeps its config snapshot, so changing the
     * maximum context mid-run must not silently restate the percentage of a request
     * that never saw the new limit. Null means "no in-flight override".
     */
    val activeRunContextWindow: Int? = null,
    val childContexts: List<io.github.mangi.eta.agent.delegation.SubAgentContextStats> = emptyList(),
    val childStatusRoster: List<io.github.mangi.eta.agent.delegation.SubAgentContextStats> = emptyList(),
    val childContextRunId: String = "",
    val selectedContextTaskId: String? = null,
    /** False means metadata/preview only; persistence must not replace its stored content. */
    val conversationContentLoaded: Boolean = true,
    /**
     * GPT 速度有效档位：AppState 从独立主代理模型记忆（供应商 ID + 模型选择 ID）投影。
     * 非 GPT/不可用绑定仅显示正常，不清除模型记忆；UI 只渲染并派发切换事件。
     */
    val gptSpeedMode: GptSpeedMode = GptSpeedMode.NORMAL,
)

/**
 * 流式回复期间短按发送后暂存的待发消息：当前运行结束后按队列顺序自动发出。
 * 仅承载纯文字；带附件 / 引用 / 编辑态的内容仍走立即插话，避免附件在延迟发送中失真。
 */
@Immutable
internal data class QueuedMessageUi(
    val id: String,
    val content: String,
)

@Immutable
sealed interface AgentChatMessageUi {
    val id: String
}

@Immutable
internal data class AgentQuestionMessageUi(
    override val id: String,
    val request: AgentQuestionRequest,
    val status: AgentQuestionStatus = AgentQuestionStatus.Waiting,
    val answer: AgentQuestionAnswer? = null,
    val selectedOptionId: String? = null,
    val answerKind: String = "option",
    val otherText: String = "",
    val note: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
) : AgentChatMessageUi

@Immutable
data class UserMessageUi(
    override val id: String,
    val content: String,
    val images: List<String> = emptyList(),
    val isEdited: Boolean = false,
    val imageSources: List<String> = emptyList(),
    val imageIsVideo: List<Boolean> = emptyList(),
    val imageDurationsMs: List<Long?> = emptyList(),
) : AgentChatMessageUi

@Immutable
data class AgentMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean = false,
    val renderMarkdown: Boolean = true,
    val usage: TokenUsageUi? = null,
    val generatedAtMillis: Long? = null,
) : AgentChatMessageUi

enum class SystemNoticeCode(val wireValue: String) {
    Stopped("stopped"),
    EmptyResult("empty_result"),
    RuntimeFailed("runtime_failed"),
    ModelRetry("model_retry"),
    Interrupted("interrupted"),
    Completed("completed");

    companion object {
        fun fromWireValue(value: String): SystemNoticeCode? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

/** Eta 自己生成的消息只保存稳定状态码，展示时再按当前语言解析。 */
@Immutable
data class SystemNoticeMessageUi(
    override val id: String,
    val code: SystemNoticeCode,
    val detail: String? = null,
) : AgentChatMessageUi

internal fun SystemNoticeCode.isRetryableFailure(): Boolean =
    this == SystemNoticeCode.Stopped || this == SystemNoticeCode.RuntimeFailed

internal fun SystemNoticeCode.canContinueDisconnectedRun(): Boolean =
    this == SystemNoticeCode.RuntimeFailed

internal fun List<AgentChatMessageUi>.stoppedDuringModelRetry(): Boolean {
    val last = lastOrNull { message ->
        when (message) {
            is UserMessageUi -> !message.isSteerSupplement()
            is AgentMessageUi, is SystemNoticeMessageUi, is ErrorReconnectMessageUi -> true
            else -> false
        }
    } ?: return false
    if (last !is SystemNoticeMessageUi || last.code != SystemNoticeCode.Stopped) return false
    val stopIndex = indexOf(last)
    val boundary = take(stopIndex).indexOfLast {
        (it is UserMessageUi && !it.isSteerSupplement()) ||
            (it is SystemNoticeMessageUi && it.code.isRetryableFailure())
    }
    return subList(boundary + 1, stopIndex).any {
        it is SystemNoticeMessageUi && it.code == SystemNoticeCode.ModelRetry
    }
}

/** Last visible chat item after the original user turn, ignoring steer/resume supplements. */
internal fun lastContinuableNotice(messages: List<AgentChatMessageUi>): SystemNoticeMessageUi? {
    val last = messages.lastOrNull { message ->
        when (message) {
            is UserMessageUi -> !message.isSteerSupplement()
            is AgentMessageUi, is SystemNoticeMessageUi, is ErrorReconnectMessageUi -> true
            else -> false
        }
    }
    return last as? SystemNoticeMessageUi
}

internal fun canContinuePausedGeneration(messages: List<AgentChatMessageUi>): Boolean {
    val notice = lastContinuableNotice(messages) ?: return false
    return notice.code == SystemNoticeCode.Stopped
}

internal fun canContinueDisconnectedRun(messages: List<AgentChatMessageUi>): Boolean {
    val last = messages.lastOrNull { message ->
        when (message) {
            is UserMessageUi -> !message.isSteerSupplement()
            is AgentMessageUi, is SystemNoticeMessageUi -> true
            is ErrorReconnectMessageUi -> message.status != ErrorReconnectStatus.Succeeded
            else -> false
        }
    }
    if (last is ErrorReconnectMessageUi) return last.isRetryableFailure()
    val notice = last as? SystemNoticeMessageUi ?: return false
    if (notice.code.canContinueDisconnectedRun() || messages.stoppedDuringModelRetry()) return true
    if (notice.code != SystemNoticeCode.Stopped) return false
    // Manual stop retains its control notice for paused/sub-agent continuation. A stopped
    // reconnect immediately before it still offers the disconnected-run continuation.
    val boundary = messages.indexOfLast { it is UserMessageUi && !it.isSteerSupplement() }
    return messages.drop(boundary + 1).filterIsInstance<ErrorReconnectMessageUi>()
        .lastOrNull()?.let { it.isReconnect && it.isRetryableFailure() } == true
}

@Immutable
data class TokenUsageUi(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null
}

@Immutable
data class ConversationTokenUsageUi(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    /**
     * 最近一轮请求的输入量，约等于当前上下文占用。
     *
     * [inputTokens] 是各轮请求的累加（同一段前缀每轮重发），拿它当「上下文」会放大数倍；
     * 界面上的上下文必须取最后一个真实回执，而不是累计值。
     */
    val lastInputTokens: Long = 0,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
    /** Uncached prefix: full prompt minus cache reads and cache writes. */
    val freshInputTokens: Long get() = (inputTokens - cachedTokens - cacheCreationTokens).coerceAtLeast(0L)
    val hasUsage: Boolean get() = inputTokens > 0 || outputTokens > 0 || cachedTokens > 0 || cacheCreationTokens > 0
    val cachePercent: Double?
        get() = if (inputTokens > 0) {
            cachedTokens.toDouble() / inputTokens.toDouble() * 100.0
        } else {
            null
        }
}

fun clearBilledTokenUsage(messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
    messages.map { message ->
        if (message is AgentMessageUi && message.usage != null) {
            message.copy(usage = null)
        } else {
            message
        }
    }

fun conversationTokenUsage(messages: List<AgentChatMessageUi>): ConversationTokenUsageUi {
    var input = 0L
    var output = 0L
    var cached = 0L
    var lastInput = 0L
    messages.forEach { message ->
        when (message) {
            is AgentMessageUi -> {
                val usage = message.usage ?: return@forEach
                input += usage.inputTokens ?: 0
                output += usage.outputTokens ?: 0
                cached += usage.cachedTokens ?: 0
                // 消息流按时间顺序，最后一次真实输入即最近一轮的上下文占用。
                usage.inputTokens?.let { lastInput = it.toLong() }
            }
            is ContextCompactedMessageUi -> {
                input += message.preservedUsage.inputTokens
                output += message.preservedUsage.outputTokens
                cached += message.preservedUsage.cachedTokens
            }
            else -> Unit
        }
    }
    return ConversationTokenUsageUi(
        inputTokens = input,
        outputTokens = output,
        cachedTokens = cached,
        lastInputTokens = lastInput,
    )
}

/**
 * Streaming deltas replace one message per snapshot. When that exact certificate holds and
 * the replaced slot contributes the same usage, the total cannot change; anything else
 * falls back to the full [conversationTokenUsage] scan.
 */
internal class ConversationTokenUsageProjection {
    private var previous: List<AgentChatMessageUi>? = null
    private var total = ConversationTokenUsageUi()

    fun project(messages: List<AgentChatMessageUi>): ConversationTokenUsageUi {
        val old = previous
        if (messages === old) return total
        val index = if (old == null) null else (messages as? AgentIncrementalList<*>)?.singleReplacementFrom(old)
        if (old == null || index == null || !sameUsageContribution(old[index], messages[index])) {
            total = conversationTokenUsage(messages)
        }
        previous = messages
        return total
    }

    private fun sameUsageContribution(old: AgentChatMessageUi, new: AgentChatMessageUi): Boolean = when (old) {
        is AgentMessageUi -> new is AgentMessageUi && old.usage == new.usage
        is ContextCompactedMessageUi -> new is ContextCompactedMessageUi && old.preservedUsage == new.preservedUsage
        else -> new !is AgentMessageUi && new !is ContextCompactedMessageUi
    }
}

@Immutable
data class ThinkingMessageUi(
    override val id: String,
    val content: String,
    val isStreaming: Boolean,
    val elapsedSeconds: Int? = null,
    val collapsed: Boolean = false,
) : AgentChatMessageUi

/**
 * 首页的 Run trace 入口卡片：展示 Agent 当前可调用的能力分组。
 */
@Immutable
data class RunTraceMessageUi(
    override val id: String,
    val capabilities: List<CapabilityUi>,
) : AgentChatMessageUi

@Immutable
data class CapabilityUi(
    val title: String,
    val items: List<String>,
)

/**
 * 工具调用摘要：出现在消息流中，显示当前/最近一步调用了哪些工具。
 */
@Immutable
data class ToolSummaryMessageUi(
    override val id: String,
    val tools: List<String>,
) : AgentChatMessageUi

/** 上下文压缩分界：插在被折叠消息和保留消息之间，点击可查看摘要。 */
@Immutable
data class ContextCompactedMessageUi(
    override val id: String,
    val compactedCount: Int,
    val summary: String,
    val compressorLabel: String = "",
    val baselineTokens: Int = 0,
    val resumeRound: Int = 0,
    val preservedUsage: ConversationTokenUsageUi = ConversationTokenUsageUi(),
) : AgentChatMessageUi

@Immutable
data class ToolActivityMessageUi(
    override val id: String,
    val toolName: String,
    val status: ToolActivityStatusUi,
    val argumentsSummary: String,
    val command: String? = null,
    val resultSummary: String? = null,
    val imageCount: Int = 0,
    /**
     * 展开区展示的完整调用参数（已脱敏、已截断）。为 null 表示这类工具的原始参数
     * 不进入会话记录（敏感工具），或旧记录没有保存过。
     */
    val argumentsDetail: String? = null,
    /** 展开区展示的完整返回内容（已脱敏、已截断）。 */
    val resultDetail: String? = null,
    /** 工具当次读到的图片小预览（data URL），点击可全屏查看。 */
    val mediaPreviews: List<String> = emptyList(),
    /**
     * 敏感工具：原文只在当次运行中可见，不写入会话记录。重开会话后如果一条细节都没有，
     * 卡片明确说明原因，而不是只留一句「完成」。
     */
    val detailsWithheld: Boolean = false,
) : AgentChatMessageUi

enum class ToolActivityStatusUi {
    Running,
    Success,
    Failed,
    Unknown,
}

/**
 * 建议语 chip 行。
 */
@Immutable
data class SuggestionChipsMessageUi(
    override val id: String,
    val prompts: List<String>,
) : AgentChatMessageUi

@Immutable
data class PendingImageUi(
    val id: String,
    val uri: String,
    val dataUrl: String,
    val mimeType: String,
    val isVideo: Boolean = false,
    val durationMs: Long? = null,
    val byteSize: Int = 0,
)

@Immutable
data class PendingFileReferenceUi(
    val id: String,
    val reference: AgentFileReference,
)

@Immutable
data class PendingConversationMentionUi(
    val id: String,
    val conversationId: String,
    val title: String,
    val transcript: String,
    val snapshotPath: String = "",
    val toolsIndexPath: String = "",
)

@Immutable
internal data class MessageEditUiState(
    val targetMessageId: String,
    val previousInput: String,
    val previousImages: List<PendingImageUi>,
    val previousFileReferences: List<PendingFileReferenceUi>,
    val hasLaterTurns: Boolean,
    val previousConversationMentions: List<PendingConversationMentionUi> = emptyList(),
    /** Temporary archive restoration. The conversation itself is unchanged until send. */
    val preparedHistory: List<AgentModelClient.ConversationMessage>? = null,
    /** Detect any intervening history rewrite before committing the prepared edit. */
    val preparedFromHistory: List<AgentModelClient.ConversationMessage>? = null,
)

internal fun UserMessageUi.isSteerSupplement(): Boolean =
    id.contains("-supplement-")

internal fun UserMessageUi.isResumeAfterCompress(): Boolean =
    id.contains("-supplement-resume")

internal fun AgentChatUiState.hasRunningTools(): Boolean =
    messages.any { message ->
        message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running
    }

internal fun AgentChatUiState.lastRealUserIndex(): Int =
    messages.indexOfLast { message ->
        message is UserMessageUi && !message.isSteerSupplement()
    }

/** Failed/stopped notices close that round. Later continue output starts after this boundary. */
internal fun AgentChatUiState.lastTurnBoundaryIndex(): Int =
    messages.indexOfLast { message ->
        (message is UserMessageUi && !message.isSteerSupplement()) ||
            (message is SystemNoticeMessageUi && message.code.isRetryableFailure())
    }

private fun AgentChatUiState.currentTurnMessages(): List<AgentChatMessageUi> {
    val lastUserIndex = lastTurnBoundaryIndex()
    return if (lastUserIndex >= 0) {
        messages.subList(lastUserIndex + 1, messages.size)
    } else {
        messages
    }
}

/** 当前用户消息之后是否已经出现工具。用于避免自动压缩打断工具循环。 */
internal fun AgentChatUiState.hasCurrentTurnTools(): Boolean =
    currentTurnMessages().any { it is ToolActivityMessageUi || it is ToolSummaryMessageUi }

/** 最后一条非空助手正文是否在本轮原问题之后，追加/续写不另开一轮。 */
internal fun AgentChatUiState.hasPartialAssistantAfterLastUser(): Boolean {
    val lastUserIndex = lastTurnBoundaryIndex()
    val lastAssistantIndex = messages.indexOfLast { message ->
        message is AgentMessageUi && message.content.isNotBlank()
    }
    return lastAssistantIndex > lastUserIndex
}

/** 当前用户消息之后是否已经开始思考、工具或正文。不看更早轮次。 */
internal fun AgentChatUiState.hasStartedCurrentTurnOutput(): Boolean {
    val lastUserIndex = lastTurnBoundaryIndex()
    val currentTurn = if (lastUserIndex >= 0) {
        messages.subList(lastUserIndex + 1, messages.size)
    } else {
        messages
    }
    return currentTurn.any { message ->
        when (message) {
            is ThinkingMessageUi -> message.isStreaming || message.content.isNotBlank()
            is AgentMessageUi -> message.isStreaming || message.content.isNotBlank()
            is ToolActivityMessageUi, is ToolSummaryMessageUi -> true
            else -> false
        }
    }
}

