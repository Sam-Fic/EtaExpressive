package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionRequest
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.core.toSafeLogToken

internal sealed interface AgentEvent {
    fun toLogLine(): String

    enum class AssistantBlockKind {
        TEXT,
        THINKING,
        TOOL_CALL,
    }

    companion object {
        /**
         * 协议标记：compressorLabel 取该值表示「仅修剪、非摘要」。
         * 该值随事件落库并经 bundle 跨进程传递（含历史数据），**不是展示文案，禁止翻译**；
         * 展示文案由 UI 层映射到 R.string.context_pruning_label。
         */
        const val PRUNING_ONLY_COMPRESSOR_LABEL = "工具输出预算修剪（原文可回读）"
    }

    data class RunStarted(
        val initialImages: Int,
        val initialImageBytes: Int,
        val toolCount: Int,
        val terminalTools: Boolean
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_started images=$initialImages, image_bytes=$initialImageBytes, tools=$toolCount, terminal=$terminalTools"
    }

    data class RoundStarted(
        val round: Int,
        val messageCount: Int,
        val historySnapshotId: String = "",
    ) : AgentEvent {
        override fun toLogLine(): String =
            "round_started round=$round, messages=$messageCount"
    }

    data class ModelRetryScheduled(
        val round: Int,
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Int,
        val reasonCode: String,
        val reasonDetail: String = "",
        /** 永久性失败（鉴权/额度/参数）：只允许有限重发，界面不应把它描述成网络抖动。 */
        val permanent: Boolean = false,
    ) : AgentEvent {
        val displayMessage: String
            get() = if (reasonCode == "DELEGATION_ARGUMENT_REPAIR") {
                "正在补全委派任务参数（$attempt/$maxAttempts）；尚未创建子任务，已完成的工具不会重放。"
            } else if (permanent) {
                "模型接口返回了不会自行恢复的错误，${delayMs / 1000} 秒后再试最后一次（$attempt/$maxAttempts）；" +
                    "此前工具结果已保留。这通常不是网络问题，请按原因检查密钥、额度或模型配置。" +
                    reasonDetail.takeIf { it.isNotBlank() }?.let { "\n原因：$it" }.orEmpty()
            } else "模型请求暂时中断，${delayMs / 1000} 秒后重试（$attempt/$maxAttempts）；此前工具结果已保留。" +
                reasonDetail.takeIf { it.isNotBlank() }?.let { "\n原因：$it" }.orEmpty()

        override fun toLogLine(): String =
            "model_retry_scheduled round=$round, attempt=$attempt, delay_ms=$delayMs, code=${reasonCode.toSafeLogToken()}, permanent=$permanent"
    }

    /** One request-scoped disconnect; independent of bounded tool-envelope correction. */
    data class ErrorReconnectChanged(
        val round: Int,
        val reconnectId: String,
        val status: String,
        val elapsedMs: Long,
        val reasonCode: String = "",
        val reasonDetail: String = "",
    ) : AgentEvent {
        override fun toLogLine(): String =
            "error_reconnect_changed round=$round, id=${reconnectId.toSafeLogToken()}, status=${status.toSafeLogToken()}, elapsed_ms=$elapsedMs, code=${reasonCode.toSafeLogToken()}"
    }

    data class ProviderRequestStarted(
        val round: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_request_started round=$round"
    }

    data class ProviderResponseStarted(
        val round: Int,
        val httpCode: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "provider_response_started round=$round, http_code=$httpCode"
    }

    data class AssistantBlockStart(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_start round=$round, kind=$kind, index=$index, " +
                "name=${name.toSafeLogToken()}"
    }

    data class AssistantBlockDelta(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val deltaChars: Int,
        val delta: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_delta round=$round, kind=$kind, index=$index, chars=$deltaChars"
    }

    data class AssistantBlockEnd(
        val round: Int,
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
        val contentChars: Int,
        val replacementContent: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_block_end round=$round, kind=$kind, index=$index, " +
                "name=${name.toSafeLogToken()}, chars=$contentChars"
    }

    data class AssistantReceived(
        val round: Int,
        val contentChars: Int,
        val reasoningContent: String,
        val toolNames: List<String>
    ) : AgentEvent {
        override fun toLogLine(): String =
            "assistant_received round=$round, content_chars=$contentChars, " +
                "reasoning_chars=${reasoningContent.length}, tool_count=${toolNames.size}, " +
                "tools=${toolNames.take(MAX_LOGGED_TOOL_NAMES).map { it.toSafeLogToken() }}"
    }

    data class ChildContextUpdated(val stats: io.github.mangi.eta.agent.delegation.SubAgentContextStats) : AgentEvent {
        override fun toLogLine() = "child_context worker=${stats.worker}, status=${stats.status}, tokens=${stats.contextTokens}"
    }

    data class UsageReceived(
        val round: Int,
        val usage: AgentTokenUsage,
        val projected: Boolean = false,
        // Local request-shape snapshots for silent next-request budgeting, not billing.
        val requestHistoryTokens: Int? = null,
        val requestOverheadTokens: Int? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "usage_received round=$round, projected=$projected, ctx=${usage.contextTokens}, in=${usage.inputTokens}, out=${usage.outputTokens}, reasoning=${usage.reasoningTokens}, cache=${usage.cachedTokens}"
    }

    data class UserSupplementReceived(
        val index: Int,
        val text: String,
        val requestId: String = "",
        val imagesJson: String = "[]",
    ) : AgentEvent {
        override fun toLogLine(): String =
            "user_supplement_received index=$index, chars=${text.length}"
    }

    data class ToolStarted(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val argsPreview: String,
        val command: String? = null,
        /**
         * 卡片展开区展示的完整调用参数（已脱敏、已截断）。敏感工具按策略不进入持久会话，
         * 这里为 null。只供用户核对，不参与模型请求。
         */
        val argumentsDetail: String? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_started round=$round, name=${name.toSafeLogToken()}, " +
                "args_chars=${argsPreview.length}, command_chars=${command?.length ?: 0}, " +
                "detail_chars=${argumentsDetail?.length ?: 0}"
    }

    data class ToolFinished(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val resultSummary: String,
        val imageCount: Int,
        val imageBytes: Int,
        /** 可选：旧版本 Runtime 不发送，消费端缺省时回退到摘要文本判断。 */
        val success: Boolean? = null,
        /** 卡片展开区展示的完整返回内容（已脱敏、已截断）。敏感工具为 null。 */
        val resultDetail: String? = null,
        /** 工具带回来的图片小预览（data URL 的 JSON 数组），供卡片直接展示。 */
        val mediaPreviewsJson: String = "[]",
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_finished round=$round, name=${name.toSafeLogToken()}, " +
                "${resultSummary.toSafeResultLogFields()}, images=$imageCount, image_bytes=$imageBytes, " +
                "detail_chars=${resultDetail?.length ?: 0}, previews=${mediaPreviewsJson.length}"
    }

    data class HostedToolStarted(
        val round: Int,
        val toolCallId: String,
        val name: String,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "hosted_tool_started round=$round, name=${name.toSafeLogToken()}"
    }

    data class HostedToolFinished(
        val round: Int,
        val toolCallId: String,
        val name: String,
        val success: Boolean,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "hosted_tool_finished round=$round, name=${name.toSafeLogToken()}, success=$success"
    }

    data class ToolImagesAttached(
        val round: Int,
        val toolName: String,
        val imageCount: Int,
        val imageBytes: Int
    ) : AgentEvent {
        override fun toLogLine(): String =
            "tool_images_attached round=$round, name=${toolName.toSafeLogToken()}, " +
                "images=$imageCount, image_bytes=$imageBytes"
    }

    data class AutoCompactWaiting(val round: Int) : AgentEvent {
        override fun toLogLine(): String = "auto_compact_waiting round=$round"
    }

    data class ContextCompactionStarted(
        val round: Int,
        val modelName: String = "",
    ) : AgentEvent {
        override fun toLogLine(): String = "context_compaction_started round=$round"
    }

    data class ContextCompacted(
        val round: Int,
        val applied: Boolean,
        val originalCount: Int,
        val compactedCount: Int,
        val history: List<AgentModelClient.ConversationMessage> = emptyList(),
        val compressorLabel: String = "",
        val blocked: Boolean = false,
        val reason: String = "",
        /** Pruning changes oversized tool bodies, not the accounting/compaction boundary. */
        val pruningOnly: Boolean = compressorLabel == PRUNING_ONLY_COMPRESSOR_LABEL,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "context_compacted round=$round, applied=$applied, $originalCount->$compactedCount"
    }

    data class RunFinished(
        val round: Int,
        val contentChars: Int,
        val generatedAtMillis: Long? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_finished round=$round, content_chars=$contentChars"
    }

    data class RunFailed(
        val reason: String
    ) : AgentEvent {
        override fun toLogLine(): String =
            "run_failed reason_chars=${reason.length}"
    }

    data class QuestionRequested(
        val request: AgentQuestionRequest,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "question_requested question_id=${request.questionId.toSafeLogToken()} " +
                "run_id=${request.runId.toSafeLogToken()}"
    }

    data class QuestionResolved(
        val questionId: String,
        val runId: String,
        val status: AgentQuestionStatus,
        val answer: AgentQuestionAnswer? = null,
    ) : AgentEvent {
        override fun toLogLine(): String =
            "question_resolved question_id=${questionId.toSafeLogToken()} " +
                "run_id=${runId.toSafeLogToken()} status=$status answered=${answer != null}"
    }
}

private const val MAX_LOGGED_TOOL_NAMES = 8
private const val RESULT_CODE_MARKER = "code="

/** 摘要字段分隔符：旧格式用逗号，人文化摘要用间隔号。 */
private val RESULT_FIELD_SEPARATORS = listOf(", ", " · ")

private fun String.toSafeResultLogFields(): String = buildString {
    append("summary_chars=").append(this@toSafeResultLogFields.length)
    extractResultCode()?.let { resultCode ->
        append(", code=").append(resultCode.toSafeLogToken())
    }
}

private fun String.extractResultCode(): String? {
    val fieldStart = when {
        startsWith(RESULT_CODE_MARKER) -> 0
        else -> RESULT_FIELD_SEPARATORS
            .mapNotNull { separator ->
                indexOf(separator + RESULT_CODE_MARKER)
                    .takeIf { it >= 0 }
                    ?.plus(separator.length)
            }
            .minOrNull() ?: return null
    }
    val valueStart = fieldStart + RESULT_CODE_MARKER.length
    val valueEnd = RESULT_FIELD_SEPARATORS
        .mapNotNull { separator ->
            indexOf(separator, startIndex = valueStart).takeIf { it >= 0 }
        }
        .minOrNull() ?: length
    return substring(valueStart, valueEnd)
}
