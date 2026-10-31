package io.github.mangi.eta.agent.voice

/**
 * 助手气泡 id 的身份判定，供回复自动朗读使用。
 *
 * id 由 [io.github.mangi.eta.ui.app.AgentRunMessageProjector] 生成，形态有
 * `assistant-<runId>-<round>-<index>`、断流续接的 `...-reconnect-<len>:<reconnectId>` 后缀、
 * 媒体直出的 `assistant-<runId>-1` 与模型重试的 `assistant-<runId>-retry-N`。
 * runId 本身是含 `-` 的 UUID，所以**不做反解**，只做「归属于哪一轮」的前缀判定
 * （与 projector 私有的 `isAssistantMessageForRun` 保持一致）。
 */
internal object AssistantMessageId {

    /** 这一条气泡是否属于 [runId] 那一轮运行。 */
    fun belongsToRun(messageId: String?, runId: String?): Boolean {
        if (messageId.isNullOrBlank() || runId.isNullOrBlank()) return false
        return messageId == "assistant-$runId" || messageId.startsWith("assistant-$runId-")
    }

    /**
     * 段身份：同 round/index 的气泡在断流续接后会换上带 `-reconnect-` 后缀的新 id，
     * 但读的还是同一段文字。朗读进度必须跟着归一化的段走，否则整段会被重读一遍。
     */
    fun segmentKey(messageId: String?): String? = messageId?.substringBefore(SEGMENT_SUFFIX)

    private const val SEGMENT_SUFFIX = "-reconnect-"
}
