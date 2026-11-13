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
 *
 * 隐私边界：敏感工具的 argumentsDetail / resultDetail / media 在落库时就已剔除，
 * 归档事件在写库时也会做同样的剔除，因此本文件不含这些原文；被剔除的调用 id
 * 列在 [withheldToolCallIds]，单条调用另有 detailsWithheld 标记。
 * 检查点的 cloud_usage_json（云侧计费回执展示态）同样有意不导出：
 * 对复盘无用，且会扩大可分享文件的隐私面；消息级 token 五项不受影响。
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
    /** 导出时生效的只读配置快照，用于复现同一段对话在不同配置下的行为。 */
    val config: EtaConversationAnalysisConfig = EtaConversationAnalysisConfig(),
    /** 因敏感工具策略而从未导出明细（参数 / 结果 / 预览）的工具调用 id。 */
    val withheldToolCallIds: List<String>? = null,
    /** 是否命中存储层的截断；命中时 [truncatedIn] 给出具体位置。 */
    val truncated: Boolean? = null,
    val truncatedIn: List<String>? = null,
    /** 体积过大时的提示；按实际序列化长度判定，正常体积不写。 */
    val sizeWarning: String? = null,
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
    val isPinned: Boolean = false,
    /** 进入回收站的时间；未回收时省略。 */
    val deletedAt: Long? = null,
    /** 是否已跑完过一次完整回复（用于区分草稿会话）。 */
    val hasCompletionMarker: Boolean = false,
    /** 是否为调度台主会话；UI 标题固定显示为「调度台」，但 title 存的是真实标题。 */
    val isDispatchConsole: Boolean = false,
    /** 派生会话所属的调度台会话 id。 */
    val dispatchParentId: String? = null,
    val dispatchManagedAt: Long? = null,
    /**
     * 本会话应用过的运行 id；回合切分靠它把消息 id 解析成真实模型回合。
     * 导入时还原进新会话，否则「导出 → 导入 → 再导出」的回合结构会退化为顺序分段。
     * 旧版文件没有这一项，解码为 null，导入后按原样走顺序分段兜底。
     */
    val appliedRuntimeRunIds: List<String>? = null,
)

/** 本次导出实际带了哪些可选内容。 */
@Serializable
internal data class EtaConversationAnalysisOptions(
    val includeRawRuntimeIo: Boolean = false,
    val includeImages: Boolean = false,
)

/**
 * 导出时生效的只读配置快照。
 *
 * 只放值与枚举，绝不放密钥、端点或 provider 凭据；用途是让读者知道
 * 同一段会话在不同工具加载 / 审查 / 重连配置下为什么会表现不同。
 */
@Serializable
internal data class EtaConversationAnalysisConfig(
    val appVersion: String = "",
    val versionCode: Int = 0,
    /** BCP-47（如 zh-CN）；落库时的工具摘要文案跟随该语言。 */
    val locale: String = "",
    /** 本会话实际生效的延迟工具加载档：search / group / off。 */
    val toolLoadingMode: String = "",
    /** MCP 工具是否按需加载（当前值，不是默认值）。 */
    val mcpToolExposureDeferred: Boolean = false,
    /** 工具调用审查档位。 */
    val toolReviewMode: String = "",
    /** 错误重连策略的持久化值。 */
    val errorReconnectPolicy: String = "",
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

/**
 * 单个延迟工具组的打开次数。
 *
 * [failed] 是打开失败次数，口径为「调用 tools_use_group 但状态为 Failed」；
 * 可能包含参数错误、组名拼错等，并不等于「被开关或门禁拒绝」，消费方不要按拒绝解读。
 */
@Serializable
internal data class EtaConversationAnalysisToolGroupAggregate(
    val name: String,
    val opens: Int,
    /** 缺省为 0：旧文件里这个位置叫 rejected，解码时按未知字段忽略，不因缺项报错。 */
    val failed: Int = 0,
)

/** 一回合的 token 账单。 */
@Serializable
internal data class EtaConversationAnalysisUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val cachedTokens: Long = 0,
    val contextTokens: Long = 0,
    /**
     * 计费口径：输入 + 输出。缓存命中的部分仍算在输入里。
     * 旧文件缺这一项时按 input + output 补算。
     */
    val billedTokens: Long = inputTokens + outputTokens,
) {

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
    /** 工具调用 id；老消息只能从 message.id 里拆，拆不出时省略。 */
    val toolCallId: String? = null,
    val argumentsSummary: String? = null,
    val resultSummary: String? = null,
    val argumentsDetail: String? = null,
    val resultDetail: String? = null,
    /** 该调用属敏感工具，明细 / 结果 / 预览按策略未导出（即使原本就没有明细）。 */
    val detailsWithheld: Boolean? = null,
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
    val toolCallId: String? = null,
    val argumentsSummary: String? = null,
    val resultSummary: String? = null,
    val argumentsDetail: String? = null,
    val resultDetail: String? = null,
    val detailsWithheld: Boolean? = null,
    val imageCount: Int = 0,
    val tools: List<String> = emptyList(),
    /** 工具当次读到的图片预览；只有勾了「含图」才会带上。 */
    val media: List<String> = emptyList(),
    val images: List<EtaConversationAnalysisImage> = emptyList(),
    val generatedAtMillis: Long? = null,
)

/**
 * 用户消息里的图片或视频。preview 是 data URL 小图。
 *
 * width / height / mime / estimatedTokens 来自 `history` 的 image_file 部件（老会话可能没有）。
 */
@Serializable
internal data class EtaConversationAnalysisImage(
    val preview: String,
    val source: String? = null,
    val isVideo: Boolean = false,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val mime: String? = null,
    /** 按应用统一的图片 token 公式估算；缺宽高时省略。 */
    val estimatedTokens: Int? = null,
)

/** 一次运行时任务。transcript 是原始模型 I/O，events 是运行时事件日志。 */
@Serializable
internal data class EtaConversationAnalysisRuntimeRun(
    val runId: String,
    val createdAt: Long,
    val ok: Boolean,
    val error: String? = null,
    val transcript: List<AgentModelClient.ConversationMessage> = emptyList(),
    /**
     * 运行时事件日志。只覆盖已归档的 run：未归档 run 的事件在 run 结束确认后即被清理，
     * 导出时拿不到——events 为空可能是「没产生事件」也可能是「run 尚未归档」，
     * 消费方不要据此断言事件缺失。
     */
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

    /**
     * 先按真实序列化长度判断体积；只有超阈值时才补写 [EtaConversationAnalysisDocument.sizeWarning]
     * 并再编码一次，正常体积只编码一次。
     */
    fun encode(document: EtaConversationAnalysisDocument): String {
        val raw = json.encodeToString(document)
        if (raw.length <= SIZE_WARNING_CHARS) return raw
        val millionChars = raw.length / 1_000_000
        val warning = "导出体积较大（约 ${millionChars.coerceAtLeast(1)} 百万字符），" +
            "部分编辑器或脚本可能无法一次打开；可关闭「原始模型 I/O」或「含图」后重新导出。"
        return json.encodeToString(document.copy(sizeWarning = warning))
    }

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

    /** 超过约 8 百万字符时提示体积；阈值按真实序列化结果判断，不按消息条数猜。 */
    private const val SIZE_WARNING_CHARS = 8_000_000
}
