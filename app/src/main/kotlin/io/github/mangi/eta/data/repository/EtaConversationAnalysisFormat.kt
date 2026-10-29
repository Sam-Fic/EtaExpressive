package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 会话分析导出格式：一个 JSON 单文件。
 *
 * 除了消息本身，还带每回合的 token 账单、工具调用明细，以及可选的原始模型 I/O 与运行时事件日志。
 * 目的是事后定位 agent 对话流里出了什么问题；同一份文件也能导入成一条普通新会话继续聊。
 */
@Serializable
internal data class EtaConversationAnalysisDocument(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val conversation: EtaConversationAnalysisConversation,
    val options: EtaConversationAnalysisOptions = EtaConversationAnalysisOptions(),
    val summary: EtaConversationAnalysisSummary = EtaConversationAnalysisSummary(),
    val rounds: List<EtaConversationAnalysisRound> = emptyList(),
    val messages: List<EtaConversationAnalysisMessage> = emptyList(),
    /** 模型侧的历史；导入后用它把新会话接上上下文。 */
    val history: List<AgentModelClient.ConversationMessage> = emptyList(),
    val runtimeRuns: List<EtaConversationAnalysisRuntimeRun> = emptyList(),
) {
    companion object {
        const val FORMAT = "eta-conversation-analysis"
        const val SCHEMA_VERSION = 1
    }
}

/** 会话元信息。设备相关的字段只作记录，导入时不直接沿用。 */
@Serializable
internal data class EtaConversationAnalysisConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val thinkingEnabled: Boolean = false,
    val reasoningEffort: String = "default",
    val providerId: String = "",
    val modelId: String = "",
    val assistantId: String = "",
    val folderId: String = "",
)

/** 本次导出实际带了哪些可选内容。 */
@Serializable
internal data class EtaConversationAnalysisOptions(
    val includeRawRuntimeIo: Boolean = false,
    val includeImages: Boolean = false,
)

/** 一次性汇总，省得看的人自己 jq 一遍。 */
@Serializable
internal data class EtaConversationAnalysisSummary(
    val messageCount: Int = 0,
    val roundCount: Int = 0,
    val toolCallCount: Int = 0,
    val failedToolCallCount: Int = 0,
    val tools: List<EtaConversationAnalysisToolAggregate> = emptyList(),
    /**
     * `tools_use_group` 的打开次数，按组名聚合。
     *
     * 用来判断「哪些延迟工具组真的会被打开」：打开次数长期为 0 的组是拆组或合并的候选，
     * 次数高的组则值得继续拆分，减少一次任务要背的 schema 量。
     */
    val toolGroups: List<EtaConversationAnalysisToolGroupAggregate> = emptyList(),
    val usage: EtaConversationAnalysisUsage = EtaConversationAnalysisUsage(),
    /** 全部 thinking 行累计的思考秒数。 */
    val thinkingSeconds: Int = 0,
)

/** 单个工具的调用次数与失败次数。 */
@Serializable
internal data class EtaConversationAnalysisToolAggregate(
    val name: String,
    val count: Int,
    val failedCount: Int,
)

/** 单个延迟工具组的打开次数；[rejected] 是被开关或能力挡掉的尝试。 */
@Serializable
internal data class EtaConversationAnalysisToolGroupAggregate(
    val name: String,
    val opens: Int,
    val rejected: Int,
)

/** 一回合的 token 账单。 */
@Serializable
internal data class EtaConversationAnalysisUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cachedTokens: Long = 0,
    val contextTokens: Long = 0,
) {
    /** 计费口径：输入 + 输出。缓存命中的部分仍算在输入里。 */
    val billedTokens: Long get() = inputTokens + outputTokens

    operator fun plus(other: EtaConversationAnalysisUsage): EtaConversationAnalysisUsage =
        EtaConversationAnalysisUsage(
            inputTokens = inputTokens + other.inputTokens,
            outputTokens = outputTokens + other.outputTokens,
            reasoningTokens = reasoningTokens + other.reasoningTokens,
            cachedTokens = cachedTokens + other.cachedTokens,
            contextTokens = contextTokens + other.contextTokens,
        )
}

/**
 * 一个回合。
 *
 * 回合边界来自消息自带的运行标识：一次运行里的每个 round 各算一个回合，
 * 用户消息则另起一轮。没有标识的老消息按出现顺序并入当前回合。
 */
@Serializable
internal data class EtaConversationAnalysisRound(
    val index: Int,
    val runId: String? = null,
    /** 运行内部的回合号；没有标识时为空。 */
    val round: Int? = null,
    val messageIds: List<String> = emptyList(),
    val toolCalls: List<EtaConversationAnalysisToolCall> = emptyList(),
    val usage: EtaConversationAnalysisUsage = EtaConversationAnalysisUsage(),
    /** 该回合 thinking 行累计的思考秒数。 */
    val thinkingSeconds: Int = 0,
    /**
     * 该回合里成功打开的延迟工具组。组在下一回合才进入工具目录，
     * 所以「本回合 openedGroups 非空」正好对应「下一回合 prefix 必然变化」。
     */
    val openedGroups: List<String> = emptyList(),
    val assistantText: String = "",
)

/** 一次工具调用。敏感工具的原始参数与结果在落库时就没了，这里同样是空的。 */
@Serializable
internal data class EtaConversationAnalysisToolCall(
    val name: String,
    val status: String,
    val command: String = "",
    val argumentsSummary: String? = null,
    val resultSummary: String? = null,
    val argumentsDetail: String? = null,
    val resultDetail: String? = null,
    val mediaCount: Int = 0,
    /** 工具当次读到的图片预览；只有勾了「含图」才会带上。 */
    val media: List<String> = emptyList(),
)

/** 一条会话消息。字段与 `conversation_messages` 表一一对应，导入时逐字段还原。 */
@Serializable
internal data class EtaConversationAnalysisMessage(
    val id: String,
    val sortIndex: Int,
    val type: String,
    val content: String,
    val isEdited: Boolean = false,
    val renderMarkdown: Boolean? = null,
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    val elapsedSeconds: Int? = null,
    val toolName: String? = null,
    val toolStatus: String? = null,
    val argumentsSummary: String? = null,
    val resultSummary: String? = null,
    val argumentsDetail: String? = null,
    val resultDetail: String? = null,
    val imageCount: Int = 0,
    val tools: List<String> = emptyList(),
    /** 工具当次读到的图片预览；只有勾了「含图」才会带上。 */
    val media: List<String> = emptyList(),
    val images: List<EtaConversationAnalysisImage> = emptyList(),
    val generatedAtMillis: Long? = null,
)

/** 用户消息里的图片或视频。preview 是 data URL 小图。 */
@Serializable
internal data class EtaConversationAnalysisImage(
    val preview: String,
    val source: String? = null,
    val isVideo: Boolean = false,
    val durationMs: Long? = null,
)

/** 一次运行时任务。transcript 是原始模型 I/O，events 是运行时事件日志。 */
@Serializable
internal data class EtaConversationAnalysisRuntimeRun(
    val runId: String,
    val createdAt: Long,
    val ok: Boolean,
    val error: String? = null,
    val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
    val events: List<JsonElement> = emptyList(),
)

/** 导出格式的读写与合法性检查。 */
internal object EtaConversationAnalysisCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(document: EtaConversationAnalysisDocument): String =
        json.encodeToString(document)

    fun decode(raw: String): EtaConversationAnalysisDocument {
        val document = json.decodeFromString<EtaConversationAnalysisDocument>(raw)
        require(document.format == EtaConversationAnalysisDocument.FORMAT) { "这不是 Eta 会话分析文件" }
        require(document.schemaVersion in 1..EtaConversationAnalysisDocument.SCHEMA_VERSION) {
            "会话分析文件版本不受支持：${document.schemaVersion}"
        }
        return document
    }

    /** 只看开头就能判断是不是分析文件，用来在导入入口分流。 */
    fun looksLikeAnalysis(raw: String): Boolean =
        raw.contains(EtaConversationAnalysisDocument.FORMAT)
}
