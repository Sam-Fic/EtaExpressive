package io.github.mangi.eta.data.repository

/**
 * 回合切分与汇总。
 *
 * 消息 id 自带运行与回合标识（`assistant-<run>-<round>-<idx>`、`<run>-tool-<round>-<call>`、
 * `<run>-thinking-<round>-<idx>`），所以能按 id 还原出真实的模型回合。
 * 没有标识的老消息按出现顺序并进当前回合：用户消息另起一轮，工具与助手行归到它后面那一轮。
 */
internal object EtaConversationAnalysisRounds {
    const val TYPE_USER = "user"
    const val TYPE_ASSISTANT = "assistant"
    const val TYPE_TOOL = "tool"
    const val TYPE_THINKING = "thinking"
    const val TYPE_CONTEXT_COMPACTED = "context_compacted"

    private const val STATUS_FAILED = "Failed"

    /** 助手行的 id 形如 `assistant-<run>-<round>-<idx>`，工具行是 `<run>-tool-<round>-<call>`。 */
    private data class RoundKey(val runId: String, val round: Int)

    private class RoundBuilder {
        var key: RoundKey? = null
        val messageIds = mutableListOf<String>()
        val toolCalls = mutableListOf<EtaConversationAnalysisToolCall>()
        val openedGroups = mutableListOf<String>()
        var usage = EtaConversationAnalysisUsage()
        var thinkingSeconds: Int = 0
        var assistantText: String = ""

        fun toRound(index: Int): EtaConversationAnalysisRound = EtaConversationAnalysisRound(
            index = index,
            runId = key?.runId,
            round = key?.round,
            messageIds = messageIds.toList(),
            toolCalls = toolCalls.toList(),
            usage = usage,
            thinkingSeconds = thinkingSeconds,
            openedGroups = openedGroups.toList(),
            assistantText = assistantText,
        )
    }

    fun segment(
        messages: List<EtaConversationAnalysisMessage>,
        runIds: List<String>,
    ): List<EtaConversationAnalysisRound> {
        val rounds = mutableListOf<RoundBuilder>()
        var current: RoundBuilder? = null
        messages.forEach { message ->
            val key = roundKeyOf(message.id, runIds)
            val opensTurn = message.type == TYPE_USER && !message.id.contains(SUPPLEMENT_MARKER)
            val builder = current
            if (builder == null || opensTurn || (key != null && builder.key != null && builder.key != key)) {
                current = RoundBuilder().also { rounds += it }
            }
            val target = current ?: RoundBuilder().also { rounds += it; current = it }
            // 用户消息开的那一轮还没有运行标识，先记下第一条带标识的消息属于哪一轮。
            if (target.key == null && key != null) target.key = key
            target.messageIds += message.id
            when (message.type) {
                TYPE_TOOL -> {
                    val call = toolCallOf(message)
                    target.toolCalls += call
                    openedGroupOf(call)?.let { target.openedGroups += it }
                }
                TYPE_ASSISTANT -> {
                    target.usage = target.usage + usageOf(message)
                    if (message.content.isNotBlank()) target.assistantText = message.content
                }
                TYPE_THINKING -> target.thinkingSeconds += message.elapsedSeconds ?: 0
                else -> Unit
            }
        }
        return rounds.mapIndexed { index, builder -> builder.toRound(index) }
    }

    /**
     * 汇总口径与应用自身的账单一致：assistant 行与 context_compacted 行都算。
     * 回合内的用量只取 assistant 行——压缩行记的是压缩时保留下来的用量，不属于某个模型回合。
     */
    fun summarize(
        messages: List<EtaConversationAnalysisMessage>,
        rounds: List<EtaConversationAnalysisRound>,
    ): EtaConversationAnalysisSummary {
        var usage = EtaConversationAnalysisUsage()
        var thinkingSeconds = 0
        messages.forEach { message ->
            if (message.type == TYPE_ASSISTANT || message.type == TYPE_CONTEXT_COMPACTED) {
                usage = usage + usageOf(message)
            }
            if (message.type == TYPE_THINKING) thinkingSeconds += message.elapsedSeconds ?: 0
        }
        val aggregates = rounds
            .flatMap { it.toolCalls }
            .groupBy { it.name }
            .map { (name, calls) ->
                EtaConversationAnalysisToolAggregate(
                    name = name,
                    count = calls.size,
                    failedCount = calls.count { it.status == STATUS_FAILED },
                )
            }
            .sortedWith(compareByDescending<EtaConversationAnalysisToolAggregate> { it.count }.thenBy { it.name })
        // 延迟工具组的真实使用频次：成功打开与「想开但被挡掉」分开计数，
        // 前者决定还该不该拆，后者说明门禁或索引文案有问题。
        val groupAggregates = rounds
            .flatMap { it.toolCalls }
            .mapNotNull { call ->
                groupNameOf(call)?.let { group -> group to (call.status == STATUS_FAILED) }
            }
            .groupBy({ it.first }, { it.second })
            .map { (name, rejectedFlags) ->
                EtaConversationAnalysisToolGroupAggregate(
                    name = name,
                    opens = rejectedFlags.count { !it },
                    rejected = rejectedFlags.count { it },
                )
            }
            .sortedWith(
                compareByDescending<EtaConversationAnalysisToolGroupAggregate> { it.opens }
                    .thenByDescending { it.rejected }
                    .thenBy { it.name },
            )
        return EtaConversationAnalysisSummary(
            messageCount = messages.size,
            roundCount = rounds.size,
            toolCallCount = rounds.sumOf { it.toolCalls.size },
            failedToolCallCount = rounds.sumOf { round -> round.toolCalls.count { it.status == STATUS_FAILED } },
            tools = aggregates,
            toolGroups = groupAggregates,
            usage = usage,
            thinkingSeconds = thinkingSeconds,
        )
    }

    private fun toolCallOf(message: EtaConversationAnalysisMessage): EtaConversationAnalysisToolCall =
        EtaConversationAnalysisToolCall(
            name = message.toolName.orEmpty().ifBlank { "unknown_tool" },
            status = message.toolStatus.orEmpty().ifBlank { "Unknown" },
            command = message.content,
            argumentsSummary = message.argumentsSummary,
            resultSummary = message.resultSummary,
            argumentsDetail = message.argumentsDetail,
            resultDetail = message.resultDetail,
            mediaCount = message.imageCount,
            media = message.media,
        )

    /** 成功打开的那个组；被开关或能力挡掉的调用返回 null，单独计入 rejected。 */
    private fun openedGroupOf(call: EtaConversationAnalysisToolCall): String? =
        groupNameOf(call)?.takeIf { call.status != STATUS_FAILED }

    private fun groupNameOf(call: EtaConversationAnalysisToolCall): String? {
        if (call.name != USE_GROUP_TOOL) return null
        val source = call.argumentsDetail?.takeIf { it.isNotBlank() }
            ?: call.argumentsSummary?.takeIf { it.isNotBlank() }
            ?: return null
        return GROUP_NAME_PATTERN.find(source)?.groupValues?.get(1)
    }

    private fun usageOf(message: EtaConversationAnalysisMessage): EtaConversationAnalysisUsage =
        EtaConversationAnalysisUsage(
            inputTokens = message.inputTokens?.toLong()?.coerceAtLeast(0L) ?: 0L,
            outputTokens = message.outputTokens?.toLong()?.coerceAtLeast(0L) ?: 0L,
            reasoningTokens = message.reasoningTokens?.toLong()?.coerceAtLeast(0L) ?: 0L,
            cachedTokens = message.cachedTokens?.toLong()?.coerceAtLeast(0L) ?: 0L,
            contextTokens = message.contextTokens?.toLong()?.coerceAtLeast(0L) ?: 0L,
        )

    private operator fun EtaConversationAnalysisUsage.plus(other: EtaConversationAnalysisUsage) =
        EtaConversationAnalysisUsage(
            inputTokens = inputTokens + other.inputTokens,
            outputTokens = outputTokens + other.outputTokens,
            reasoningTokens = reasoningTokens + other.reasoningTokens,
            cachedTokens = cachedTokens + other.cachedTokens,
            contextTokens = contextTokens + other.contextTokens,
        )

    private fun roundKeyOf(messageId: String, runIds: List<String>): RoundKey? {
        for (runId in runIds) {
            if (runId.isBlank()) continue
            val prefixes = listOf("assistant-$runId-", "$runId-tool-", "$runId-thinking-")
            for (prefix in prefixes) {
                if (!messageId.startsWith(prefix)) continue
                val round = messageId.removePrefix(prefix).substringBefore('-').toIntOrNull() ?: continue
                return RoundKey(runId, round)
            }
        }
        return null
    }

    private const val SUPPLEMENT_MARKER = "-supplement-"

    /** 延迟工具组的打开入口；组名是它唯一的参数。 */
    private const val USE_GROUP_TOOL = "tools_use_group"

    /** 兼容 `{"group":"calendar"}` 与 `group=calendar` 两种参数呈现。 */
    private val GROUP_NAME_PATTERN = Regex("group\"?\\s*[:=]\\s*\"?([A-Za-z0-9_]+)")
}
