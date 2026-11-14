package io.github.mangi.eta.data.model

import org.json.JSONObject

/**
 * 单个模型请求的前缀缓存归因诊断。
 *
 * 前缀缓存按「tools → system → history」的顺序逐块命中，任一段变化都会让它之后的
 * 块全部失效。四段状态是 AgentLoop 在发请求前对三段前缀算出的短指纹比较结果
 * （first / same / changed），只有把它随账单一起落库，事后看到 `cached=0` 时才能
 * 区分「我们自己把前缀改了」与「上游缓存没保住」，不必再靠上下文猜。
 *
 * 只含枚举值与 token 数，不含提示词、工具参数或任何原文，可随会话导出分享。
 */
data class UsageDiagnostics(
    val prefixState: String? = null,
    val toolsState: String? = null,
    val systemState: String? = null,
    val historyState: String? = null,
    /** 本次写入缓存的 token 数；属于 inputTokens 的子集，不是命中。 */
    val cacheCreationTokens: Int? = null,
) {
    val isEmpty: Boolean
        get() = prefixState == null && toolsState == null && systemState == null &&
            historyState == null && cacheCreationTokens == null
}

/** [UsageDiagnostics] 与 `conversation_messages.usage_diagnostics_json` 列的编解码。 */
internal object UsageDiagnosticsJson {
    private const val KEY_PREFIX = "prefix"
    private const val KEY_TOOLS = "tools"
    private const val KEY_SYSTEM = "system"
    private const val KEY_HISTORY = "history"
    private const val KEY_CREATION = "creation"

    /** 空值返回空串：老数据与无诊断的回合不占空间。 */
    fun encode(value: UsageDiagnostics?): String {
        if (value == null || value.isEmpty) return ""
        return JSONObject().apply {
            value.prefixState?.let { put(KEY_PREFIX, it) }
            value.toolsState?.let { put(KEY_TOOLS, it) }
            value.systemState?.let { put(KEY_SYSTEM, it) }
            value.historyState?.let { put(KEY_HISTORY, it) }
            value.cacheCreationTokens?.let { put(KEY_CREATION, it) }
        }.toString()
    }

    /** 兼容旧库空串、脏 JSON 与历史字段名；解析失败按「无诊断」处理，绝不阻断读会话。 */
    fun decode(raw: String?): UsageDiagnostics? {
        if (raw.isNullOrBlank()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        fun text(key: String): String? = json.optString(key, "").takeIf { it.isNotBlank() }
        val creation = json.optInt(KEY_CREATION, -1).takeIf { it >= 0 }
        return UsageDiagnostics(
            prefixState = text(KEY_PREFIX),
            toolsState = text(KEY_TOOLS),
            systemState = text(KEY_SYSTEM),
            historyState = text(KEY_HISTORY),
            cacheCreationTokens = creation,
        ).takeUnless { it.isEmpty }
    }
}
