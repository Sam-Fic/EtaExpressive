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
    private const val STATUS_UNKNOWN = "Unknown"

    /**
     * 工具调用是否**未确认成功**。
     *
     * run 被停止时，尚未收到 ToolFinished 的 Running 卡片会被投影层改成 Unknown；
     * 敏感工具的结果又已脱敏，导出里没有任何可反推终态的证据。把 Unknown 与 Failed
     * 一并计入未完成，避免「一条日程都没建成功」的诊断里失败数显示为 0。
     */
    private fun isUnsuccessful(status: String): Boolean =
        status == STATUS_FAILED || status == STATUS_UNKNOWN

    /** 助手行的 id 形如 `assistant-<run>-<round>-<idx>`，工具行是 `<run>-tool-<round>-<call>`。 */
    internal data class RoundKey(val runId: String, val round: Int)

    private class RoundBuilder {
        var key: RoundKey? = null
        val messageIds = mutableListOf<String>()
        val toolCalls = mutableListOf<EtaConversationAnalysisToolCall>()
        val openedGroups = mutableListOf<String>()
        var usage = EtaConversationAnalysisUsage()
        var thinkingSeconds: Int = 0
        var assistantText: String = ""
        var cache: EtaConversationAnalysisCacheDiagnostics? = null

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
            cache = cache,
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
                    // 同一回合可能有多条 assistant 行（先说话再调工具）；真实回执带诊断，
                    // 取最后一条带诊断的，避免被前面只有本地估算的行覆盖成 null。
                    message.cache?.let { target.cache = it }
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
                    failedCount = calls.count { isUnsuccessful(it.status) },
                )
            }
            .sortedWith(compareByDescending<EtaConversationAnalysisToolAggregate> { it.count }.thenBy { it.name })
        // 延迟工具组的真实使用频次：成功打开与打开失败分开计数，
        // 前者决定还该不该拆；失败数只说明这次打开没成功（参数错、组名错或被门禁挡），
        // 不单独代表「被拒绝」，因此字段命名为 failed。
        val groupAggregates = rounds
            .flatMap { it.toolCalls }
            .mapNotNull { call ->
                groupNameOf(call)?.let { group -> group to isUnsuccessful(call.status) }
            }
            .groupBy({ it.first }, { it.second })
            .map { (name, failedFlags) ->
                EtaConversationAnalysisToolGroupAggregate(
                    name = name,
                    opens = failedFlags.count { !it },
                    failed = failedFlags.count { it },
                )
            }
            .sortedWith(
                compareByDescending<EtaConversationAnalysisToolGroupAggregate> { it.opens }
                    .thenByDescending { it.failed }
                    .thenBy { it.name },
            )
        return EtaConversationAnalysisSummary(
            messageCount = messages.size,
            roundCount = rounds.size,
            toolCallCount = rounds.sumOf { it.toolCalls.size },
            failedToolCallCount = rounds.sumOf { round -> round.toolCalls.count { isUnsuccessful(it.status) } },
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
            toolCallId = message.toolCallId,
            argumentsSummary = message.argumentsSummary,
            resultSummary = message.resultSummary,
            argumentsDetail = message.argumentsDetail,
            resultDetail = message.resultDetail,
            detailsWithheld = message.detailsWithheld,
            mediaCount = message.imageCount,
            media = message.media,
        )

    /**
     * 从工具行 id（`<run>-tool-<round>-<call>`）里拆出 tool_call_id。
     * 老消息没有运行标识时返回 null，调用方不要据此认为调用不存在。
     */
    fun toolCallIdOf(messageId: String): String? {
        val marker = "$TOOL_ID_MARKER"
        val markerIndex = messageId.indexOf(marker)
        if (markerIndex < 0) return null
        val rest = messageId.substring(markerIndex + marker.length)
        val roundSeparator = rest.indexOf('-')
        if (roundSeparator < 0 || roundSeparator == rest.lastIndex) return null
        return rest.substring(roundSeparator + 1).takeIf { it.isNotBlank() }
    }

    /** 成功打开的那个组；打开失败的调用返回 null，单独计入 failed。 */
    private fun openedGroupOf(call: EtaConversationAnalysisToolCall): String? =
        groupNameOf(call)?.takeIf { !isUnsuccessful(call.status) }

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
            cacheCreationTokens = message.cacheCreationTokens?.toLong()?.coerceAtLeast(0L)
                ?: message.cache?.cacheCreationTokens ?: 0L,
            contextTokens = message.contextTokens?.toLong()?.coerceAtLeast(0L) ?: 0L,
        )

    private operator fun EtaConversationAnalysisUsage.plus(other: EtaConversationAnalysisUsage) =
        EtaConversationAnalysisUsage(
            inputTokens = inputTokens + other.inputTokens,
            outputTokens = outputTokens + other.outputTokens,
            reasoningTokens = reasoningTokens + other.reasoningTokens,
            cachedTokens = cachedTokens + other.cachedTokens,
            cacheCreationTokens = cacheCreationTokens + other.cacheCreationTokens,
            contextTokens = contextTokens + other.contextTokens,
        )

    /** 从消息 id（`assistant-<run>-<round>-<idx>` 等前缀）解析运行标识；老消息返回 null。导入侧复用同一解析。 */
    internal fun roundKeyOf(messageId: String, runIds: List<String>): RoundKey? {
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

    /** 工具行 id 里区分工具调用的固定段。 */
    private const val TOOL_ID_MARKER = "-tool-"

    /** 延迟工具组的打开入口；组名是它唯一的参数。 */
    private const val USE_GROUP_TOOL = "tools_use_group"

    /** 兼容 `{"group":"calendar"}` 与 `group=calendar` 两种参数呈现。 */
    private val GROUP_NAME_PATTERN = Regex("group\"?\\s*[:=]\\s*\"?([A-Za-z0-9_]+)")
}
