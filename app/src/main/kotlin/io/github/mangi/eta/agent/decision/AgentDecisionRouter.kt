package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.model.AgentToolGroup
import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.util.Locale

/**
 * 本轮生效的决策结果（run 级内存 overlay）。
 *
 * **不回写 Prefs**。普通 run 各自计算；工具回填后的续跑 run 会复用
 * [AgentDecisionRouter] 内最近一次成功的 overlay（见 `skipped_for_continuation`），
 * 避免每轮都多打一次判断模型。sticky 通过 conversationId map 参与下一轮 allowed 计算。
 */
internal data class AgentDecisionPlan(
    val mode: String,
    val selectedGroups: Set<String>,
    /** 本轮允许的延迟组；control 下 = 决策 ∪ sticky ∪ 历史保护，advise / fail-open 下 = gate 全集。 */
    val allowedGroups: Set<String>,
    /** 本轮预开组，交给 AgentModelClient 的 initialOpenedGroups。 */
    val openedGroups: Set<String>,
    val stickyGroups: Set<String>,
    /** 闲聊态：needs_tools=false 且没有选中任何组；仅 control 下会关闭提示段。 */
    val chatOnly: Boolean,
    /**
     * 唤起即屏幕上下文（助手弹窗 run 专属问题）：判断模型判定「回答依赖看到当前手机屏幕」。
     * 消费在 AgentRuntimeRunExecutor：预开屏幕组 + 注入 observe_screen 首轮引导。
     * 非弹窗 run 恒为 false（问题根本不发问——硬边界）。
     */
    val needsScreenContext: Boolean = false,
    /** null = 决策成功；非空 = 降级原因（fail-open / fail-closed 都会带）。 */
    val failureReason: String?,
    /** 被关闭的提示段名，仅用于日志。 */
    val promptSectionsDisabled: List<String>,
    /**
     * `tools_use_group` 的可打开上限。
     * - control 成功：= [allowedGroups]（已全部预开，实际枚举通常为空）；
     * - fail-open：null（不接管，保持现状）；
     * - fail-closed：空集（只剩基础交互）。
     */
    val openableGroupLimit: Set<String>?,
    /** 本轮是否用升级模型复判过；仅用于日志与诊断。 */
    val escalated: Boolean = false,
    val escalationReason: String? = null,
    val escalationLatencyMs: Long = 0,
    /** M4：升级触发判据构成（组/阈值/历史样本），仅用于日志调参。 */
    val escalationScore: String? = null,
    /** 命中 [AgentDecisionCache] 时为 true，此时没有真实请求。 */
    val cacheHit: Boolean = false,
    /** 工具回填后的续跑轮次复用了上一轮 overlay，未再调用判断模型。 */
    val skippedForContinuation: Boolean = false,
)

/** 判断模型的 gate 计算。与 AgentPromptBuilder / AgentToolCatalog 的组开关保持同源。 */
internal object AgentDecisionGate {

    fun allowedGroups(config: AgentModelClient.ModelConfig): List<AgentToolGroup> =
        AgentToolGroups.deferredGroups.filter { group ->
            when (group.id) {
                AgentToolGroups.SCREEN_GROUP.id -> config.screenTools
                AgentToolGroups.WEB_GROUP.id -> config.browserTools
                AgentToolGroups.TERMINAL_GROUP.id -> config.terminalTools
                AgentToolGroups.DEVICE_GROUP.id -> config.deviceTools
                AgentToolGroups.CALENDAR_GROUP.id -> config.personalTools
                AgentToolGroups.PERSONAL_GROUP.id -> config.personalTools
                AgentToolGroups.DEVICE_STATE_GROUP.id -> config.personalTools
                AgentToolGroups.EXTENSIONS_GROUP.id -> Prefs.isEnabled(Prefs.Keys.AGENT_EXTENSIONS_TOOLS)
                else -> false
            }
        }

    fun historyGroups(history: List<ConversationMessage>): Set<String> {
        val names = mutableSetOf<String>()
        for (message in history) {
            if (message.toolCallsJson.isBlank()) continue
            val array = runCatching { JSONArray(message.toolCallsJson) }.getOrNull() ?: continue
            for (index in 0 until array.length()) {
                val call = array.optJSONObject(index) ?: continue
                val name = call.optJSONObject("function")?.optString("name").orEmpty()
                if (name.isNotBlank()) names.add(name)
            }
        }
        return AgentToolGroups.deferredGroupsOf(names)
    }
}

/**
 * 工具回填后的续跑检测（纯函数，只读历史与本次 prompt）。
 *
 * 两种历史形态都说明本次 run 是在上一轮工具循环里被中断后继续的，不是新的用户话题：
 * - 历史末尾是 `tool` 消息（工具结果已回填、还没轮到助手回答）；
 * - 或历史末尾是带 `tool_calls` 的助手消息（工具调用已发出、结果尚未回填）。
 *
 * 注意：同一个 run 内的工具多轮由 `AgentModelClient` / `AgentLoop` 完成，
 * 不会重新进入 `route`；只有「中断后继续」才会以新的 run 再次经过这里。
 */
internal object AgentDecisionContinuation {

    private const val ROLE_TOOL = "tool"
    private const val ROLE_ASSISTANT = "assistant"

    /**
     * 判定规则：
     * - 历史末尾是 `tool` 消息（工具结果已回填、还没轮到助手回答）；
     * - 或历史末尾是带 `tool_calls` 的助手消息（工具调用已发出、结果尚未回填）；
     * - **且**本次 prompt 是系统生成的续跑提示（或为空）。
     *
     * 最后一条很关键：用户在上一次工具循环里中断后重新发一个新问题，历史同样以
     * `tool` 结尾，但 prompt 是用户自己的话。那种情况必须重新决策，不能复用旧 overlay，
     * 否则会把新话题限制在上一轮的组集合里（control 模式下等于少给工具）。
     */
    fun detect(history: List<ConversationMessage>, prompt: String): Boolean {
        if (!isContinuationPrompt(prompt)) return false
        val last = history.asReversed().firstOrNull {
            it.content.isNotBlank() || it.toolCallsJson.isNotBlank()
        } ?: return false
        if (last.role == ROLE_TOOL) return true
        return last.role == ROLE_ASSISTANT && last.toolCallsJson.isNotBlank()
    }

    /** 续跑提示与压缩后无缝续跑共用同一个常量，避免在决策层再抄一份文案。 */
    private fun isContinuationPrompt(prompt: String): Boolean =
        prompt.isBlank() ||
            prompt.trim() == AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT
}

/**
 * 决策缓存（进程内 LRU，容量 [MAX_ENTRIES]）。
 *
 * key 覆盖所有会影响结论的输入：会话、规范化输入的哈希、gate 组集合、sticky 集合、
 * 主/升级模型、以及一份策略签名（模式、超时、历史轮数、TTL、升级阈值、端点覆盖）。
 * 任一设置变更都会改变签名而自然失效；gate 变化会改变组集合；会话切换因 key 含
 * conversationId 不会串用。
 *
 * 只缓存**原始评估结果**，`allowed/opened` 每轮按当前模式重算，因此切换 control/advise
 * 不会读到错误的旧结论。
 */
internal object AgentDecisionCache {

    private const val MAX_ENTRIES = 16

    private data class Key(
        val conversationId: String,
        val inputHash: Int,
        val groups: List<String>,
        val sticky: List<String>,
        val modelId: String,
        /** 续跑与非续跑的 state 语义不同，必须分开缓存，否则会读到错误的 `is_tool_loop_continuation`。 */
        val continuation: Boolean,
        val policy: String,
    )

    private val entries = object : LinkedHashMap<Key, AgentDecisionEvaluator.AgentDecisionEvaluation>(
        16,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, AgentDecisionEvaluator.AgentDecisionEvaluation>?,
        ): Boolean = size > MAX_ENTRIES
    }

    fun lookup(
        conversationId: String,
        input: String,
        gateIds: Set<String>,
        sticky: Set<String>,
        modelId: String,
        continuation: Boolean,
        policy: String,
    ): AgentDecisionEvaluator.AgentDecisionEvaluation? = synchronized(entries) {
        entries[Key(conversationId, input.hashCode(), gateIds.sorted(), sticky.sorted(), modelId,
            continuation, policy)]
    }

    fun store(
        conversationId: String,
        input: String,
        gateIds: Set<String>,
        sticky: Set<String>,
        modelId: String,
        continuation: Boolean,
        policy: String,
        evaluation: AgentDecisionEvaluator.AgentDecisionEvaluation,
    ) {
        synchronized(entries) {
            entries[Key(conversationId, input.hashCode(), gateIds.sorted(), sticky.sorted(), modelId,
                continuation, policy)] = evaluation
        }
    }

    /** 供诊断/设置变更时整体失效；目前只在显式调用时使用。 */
    fun clear() = synchronized(entries) { entries.clear() }
}

/**
 * 最近一次 `decision.route` 的只读摘要，供设置页诊断展示。
 *
 * 只保留最后一条（每轮覆盖），避免在长会话里持有大量文本；写入点只在 Router。
 */
internal object AgentDecisionDiagnostics {

    @Volatile
    private var summary: Summary? = null

    data class Summary(
        val mode: String,
        val selected: List<String>,
        val allowed: List<String>,
        val disabledSections: List<String>,
        val latencyMs: Long,
        val usage: String,
        val escalated: Boolean,
        val cacheHit: Boolean,
        val failureReason: String?,
    )

    val last: Summary? get() = summary

    fun record(summary: Summary) {
        this.summary = summary
    }
}

/**
 * sticky 组（按 conversationId 的内存状态）。
 *
 * 规则（M2 明确采用可解释启发式，不做语义模型）：
 * - 每轮先整体 TTL-1；命中（本轮决策选中，或当前输入出现该组的关键词）重置为配置 TTL；
 * - 本轮决策没选任何组、且输入与该组无关键词关联 → 立即清零（话题切换）；
 * - 用户关闭的组即使曾粘住也直接丢弃（用户开关是唯一上限）；
 * - 最多保留 3 个，超出按最近命中淘汰。
 *
 * 生命周期（M3）：拿不到可靠的「会话结束」信号（会话可能只被切走、进程也可能被杀），
 * 因此用「最多 8 个会话 + 30 分钟空闲超时」的 LRU 兜底：每次 `current/update` 刷新最近访问时间，
 * 超时的整条记录直接丢弃，超出容量时淘汰最久未访问的会话。
 */
internal object AgentDecisionStickyStore {

    private const val MAX_STICKY_GROUPS = 3
    private const val MAX_CONVERSATIONS = 8
    private const val IDLE_TIMEOUT_MS = 30L * 60L * 1000L

    private data class Entry(
        val groups: LinkedHashMap<String, Int>,
        var lastTouchMs: Long,
    )

    /** groupId → 关键词。只做「词面关联」判断，宁可保留一轮，不擅自扩大。 */
    private val KEYWORDS: Map<String, List<String>> = mapOf(
        AgentToolGroups.SCREEN_GROUP.id to
            listOf("截图", "屏幕", "点击", "界面", "操作手机", "app", "screenshot", "screen", "tap"),
        AgentToolGroups.WEB_GROUP.id to
            listOf("网页", "搜索", "链接", "浏览", "网站", "url", "web", "browser", "http"),
        AgentToolGroups.TERMINAL_GROUP.id to
            listOf("命令", "终端", "shell", "脚本", "文件夹", "目录", "terminal", "bash", "command"),
        AgentToolGroups.DEVICE_GROUP.id to
            listOf("设备", "设置", "通知", "音量", "亮度", "wifi", "蓝牙", "device", "volume", "brightness"),
        AgentToolGroups.CALENDAR_GROUP.id to
            listOf("日历", "日程", "提醒", "会议", "calendar", "schedule", "reminder", "meeting"),
        AgentToolGroups.PERSONAL_GROUP.id to
            listOf("联系人", "短信", "相册", "照片", "通讯录", "contact", "sms", "photo", "album"),
        AgentToolGroups.DEVICE_STATE_GROUP.id to
            listOf("电量", "状态", "网络", "存储", "battery", "status", "storage"),
        AgentToolGroups.EXTENSIONS_GROUP.id to
            listOf("扩展", "配方", "技能", "记忆", "extension", "recipe", "skill", "memory"),
    )

    /** access-order：最久未访问的会话排在最前，容量淘汰直接取第一个。 */
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    fun current(conversationId: String, gateIds: Set<String>): Set<String> = synchronized(entries) {
        pruneLocked()
        val entry = entries[conversationId] ?: return@synchronized emptySet<String>()
        entry.lastTouchMs = System.currentTimeMillis()
        entry.groups.keys.filterTo(mutableSetOf()) { it in gateIds }
    }

    fun update(
        conversationId: String,
        selected: Set<String>,
        input: String,
        gateIds: Set<String>,
        ttl: Int,
    ): Set<String> = synchronized(entries) {
        pruneLocked()
        if (ttl <= 0) {
            entries.remove(conversationId)
            return@synchronized emptySet<String>()
        }
        val previous = entries[conversationId]?.groups.orEmpty()
        val next = LinkedHashMap<String, Int>()
        val loweredInput = input.lowercase(Locale.ROOT)
        for ((group, remaining) in previous) {
            if (group !in gateIds) continue
            val matched = group in selected || matches(group, loweredInput)
            if (selected.isEmpty() && !matched) continue
            val nextTtl = if (matched) ttl else remaining - 1
            if (nextTtl > 0) {
                // 重新插入以刷新 LinkedHashMap 的命中顺序：下面的上限裁剪按最近命中淘汰。
                next.remove(group)
                next[group] = nextTtl
            }
        }
        for (group in selected.sorted()) {
            if (group !in gateIds) continue
            next.remove(group)
            next[group] = ttl
        }
        // 关键词新增：输入明写了某组的强信号词（如「终端」），即使决策没选它也粘进来。
        // 没有这条时（2026-11-14 日志取证），决策把「在终端里运行程序」判成闲聊后，
        // 用户第二三轮改口重试，sticky 只会续命已有组、永远无法把 terminal 组带回来。
        // 命中即给满 TTL，话题切走后自然衰减；仍受 gateIds（用户开关）与容量上限约束。
        for ((group, keywords) in KEYWORDS) {
            if (group !in gateIds || group in next) continue
            if (keywords.any { loweredInput.contains(it.lowercase(Locale.ROOT)) }) {
                next.remove(group)
                next[group] = ttl
            }
        }
        val capped = LinkedHashMap<String, Int>()
        next.entries.toList().takeLast(MAX_STICKY_GROUPS).forEach { capped[it.key] = it.value }
        if (capped.isEmpty()) {
            entries.remove(conversationId)
            return@synchronized emptySet<String>()
        }
        entries[conversationId] = Entry(capped, System.currentTimeMillis())
        capped.keys
    }

    /** 清空全部会话状态（诊断/测试入口）。 */
    fun clear() = synchronized(entries) { entries.clear() }

    private fun pruneLocked() {
        val now = System.currentTimeMillis()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.lastTouchMs > IDLE_TIMEOUT_MS) iterator.remove()
        }
        while (entries.size > MAX_CONVERSATIONS) {
            val eldest = entries.entries.firstOrNull() ?: break
            entries.remove(eldest.key)
        }
    }

    private fun matches(groupId: String, loweredInput: String): Boolean =
        KEYWORDS[groupId].orEmpty().any { keyword -> loweredInput.contains(keyword.lowercase(Locale.ROOT)) }
}

/**
 * 判断模型的接管入口（影子关闭时才真正生效）。
 *
 * 调用点：`AgentRuntimeRunExecutor` 在首个模型请求之前、`runController.throwIfCancelled()`
 * 之后。返回 null 表示「不接管」——总开关关闭、影子模式、或未满足生效条件。
 * 返回的 [AgentDecisionPlan] 只活在本轮内存里，不回写 Prefs。
 */
internal object AgentDecisionRouter {

    private const val MIN_TIMEOUT_MS = 500
    private const val MAX_TIMEOUT_MS = 10_000
    private const val MAX_HISTORY_TURNS = 10
    private const val MAX_REMEMBERED_PLANS = 8

    /**
     * 最近一轮的 overlay（按会话）。续跑 run 直接复用，避免每轮都打一次判断模型。
     * 只在**成功的** plan 上写入；LRU 上限 [MAX_REMEMBERED_PLANS]。
     */
    private val lastPlans = object : LinkedHashMap<String, AgentDecisionPlan>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, AgentDecisionPlan>?,
        ): Boolean = size > MAX_REMEMBERED_PLANS
    }

    fun route(
        request: AgentRuntimeWire.RunRequest,
        config: AgentModelClient.ModelConfig,
        logger: AgentLogger,
    ): AgentDecisionPlan? {
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_MODEL_ENABLED)) return null
        if (Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_SHADOW)) {
            // 影子阶段：只记录，不返回任何 plan。
            AgentDecisionShadow.run(request, config, logger)
            return null
        }

        val conversationId = request.effectiveModelSessionId
        val mode = readMode()
        val failurePolicy = Prefs.getString(
            Prefs.Keys.AGENT_DECISION_FAILURE_POLICY,
            Prefs.Keys.AGENT_DECISION_FAILURE_OPEN,
        )
        val providerId = Prefs.getString(Prefs.Keys.AGENT_DECISION_PROVIDER_ID).trim()
        val modelId = Prefs.getString(Prefs.Keys.AGENT_DECISION_MODEL_ID).trim()
        val timeoutMs = readTimeoutMs()
        val historyTurns = Prefs.getInt(
            Prefs.Keys.AGENT_DECISION_HISTORY_TURNS,
            Prefs.Keys.AGENT_DECISION_HISTORY_TURNS_DEFAULT,
        ).coerceIn(0, MAX_HISTORY_TURNS)
        val stickyTtl = Prefs.getInt(
            Prefs.Keys.AGENT_DECISION_STICKY_TTL_TURNS,
            Prefs.Keys.AGENT_DECISION_STICKY_TTL_DEFAULT,
        ).coerceIn(0, Prefs.Keys.AGENT_DECISION_HISTORY_TURNS_MAX)
        val endpointOverride = Prefs.getString(Prefs.Keys.AGENT_DECISION_ENDPOINT_OVERRIDE)
        val escalationEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_ESCALATION_ENABLED)
        val escalationModelId = Prefs.getString(Prefs.Keys.AGENT_DECISION_ESCALATION_MODEL_ID).trim()
        // 留空 = 与主模型同 provider；选择器里换 provider 时会写入显式值。
        val escalationProviderId = Prefs.getString(Prefs.Keys.AGENT_DECISION_ESCALATION_PROVIDER_ID)
            .trim()
            .ifBlank { Prefs.getString(Prefs.Keys.AGENT_DECISION_PROVIDER_ID).trim() }
        val confidencePct = Prefs.getInt(
            Prefs.Keys.AGENT_DECISION_ESCALATION_CONFIDENCE_PCT,
            Prefs.Keys.AGENT_DECISION_ESCALATION_CONFIDENCE_DEFAULT_PCT,
        ).coerceIn(0, 100)
        // 工具回填后的续跑：这是同一条工具循环的延续，不是新话题。
        // 只有 prompt 也是系统续跑提示时才算，避免把「中断后用户另发新问题」误判成续跑。
        val isContinuation = AgentDecisionContinuation.detect(request.history, request.prompt)

        val gateGroups = AgentDecisionGate.allowedGroups(config)
        val gateIds = gateGroups.mapTo(mutableSetOf()) { it.id }
        val historyProtected = AgentDecisionGate.historyGroups(request.history) intersect gateIds
        val stickyBefore = AgentDecisionStickyStore.current(conversationId, gateIds)

        // 续跑 run：复用最近一次成功 overlay（模式一致），不再调用判断模型。
        // sticky 不在这里推进 TTL：它属于上一个用户回合的决策，续跑只是同一循环的延续。
        if (isContinuation) {
            val reused = synchronized(lastPlans) { lastPlans[conversationId] }
            if (reused != null && reused.failureReason == null && reused.mode == mode) {
                val allowed: Set<String> = if (mode == Prefs.Keys.AGENT_DECISION_MODE_CONTROL) {
                    (reused.selectedGroups + reused.stickyGroups + historyProtected) intersect gateIds
                } else {
                    gateIds
                }
                val opened: Set<String> = if (mode == Prefs.Keys.AGENT_DECISION_MODE_CONTROL) {
                    allowed
                } else {
                    (reused.selectedGroups + reused.stickyGroups + historyProtected) intersect gateIds
                }
                logger.info(
                    "decision.route skipped_for_continuation run_id=${request.runId} " +
                        "conversation_id=$conversationId mode=$mode model=$modelId " +
                        "selected_groups=${reused.selectedGroups.sorted()} allowed=${allowed.sorted()} " +
                        "opened_after=${opened.sorted()} cache_hit=false",
                )
                return reused.copy(
                    allowedGroups = allowed,
                    openedGroups = opened,
                    openableGroupLimit = if (mode == Prefs.Keys.AGENT_DECISION_MODE_CONTROL) allowed else null,
                    skippedForContinuation = true,
                    cacheHit = false,
                )
            }
        }

        // `modelId` 是 provider-model 记录 id（设置页存的就是它），只用于配置查找、
        // 缓存签名与升级判重；真正发往 `/v1/systemone` 的 `model` 必须是 provider 行里的
        // API 模型名（`ModelConfig.model`），否则网关会以 model_not_allowed 拒绝。
        var requestModel = modelId

        fun degraded(reason: String, detail: String): AgentDecisionPlan {
            val closed = failurePolicy == Prefs.Keys.AGENT_DECISION_FAILURE_CLOSED
            val allowed: Set<String> = if (closed) emptySet() else gateIds
            val opened: Set<String> = if (closed) emptySet() else historyProtected
            if (closed) {
                logger.error(
                    "decision.route degraded_closed run_id=${request.runId} " +
                        "conversation_id=$conversationId mode=$mode model=$requestModel " +
                        "model_record=$modelId " +
                        "fallback_reason=$reason detail=${AgentDecisionEvaluator.sanitize(detail)} " +
                        "allowed=[] sticky=${stickyBefore.sorted()} opened_after=[]",
                )
            } else {
                logger.warn(
                    "decision.route degraded_open run_id=${request.runId} " +
                        "conversation_id=$conversationId mode=$mode model=$requestModel " +
                        "model_record=$modelId " +
                        "fallback_reason=$reason detail=${AgentDecisionEvaluator.sanitize(detail)} " +
                        "allowed=${allowed.sorted()} sticky=${stickyBefore.sorted()} " +
                        "opened_after=${opened.sorted()}",
                )
            }
            // 降级同样写入诊断：否则设置页会一直显示上一次成功的摘要，容易误判当前状态。
            AgentDecisionDiagnostics.record(
                AgentDecisionDiagnostics.Summary(
                    mode = mode,
                    selected = emptyList(),
                    allowed = allowed.sorted(),
                    disabledSections = emptyList(),
                    latencyMs = 0,
                    usage = "-/-",
                    escalated = false,
                    cacheHit = false,
                    failureReason = reason,
                ),
            )
            AgentDecisionTelemetry.recordDegraded(conversationId, reason)
            return AgentDecisionPlan(
                mode = mode,
                selectedGroups = emptySet(),
                allowedGroups = allowed,
                openedGroups = opened,
                stickyGroups = stickyBefore,
                chatOnly = false,
                failureReason = reason,
                promptSectionsDisabled = emptyList(),
                openableGroupLimit = if (closed) emptySet() else null,
            )
        }

        if (gateGroups.isEmpty()) return null
        if (providerId.isBlank()) return degraded("missing_provider", "判断模型 provider 未配置")
        if (modelId.isBlank()) return degraded("missing_model", "判断模型 model 未配置")
        val protocol = Prefs.getString(
            Prefs.Keys.AGENT_DECISION_PROTOCOL,
            Prefs.Keys.AGENT_DECISION_PROTOCOL_SYSTEMONE,
        )
        if (protocol != Prefs.Keys.AGENT_DECISION_PROTOCOL_SYSTEMONE) {
            return degraded("unsupported_protocol", "protocol=$protocol")
        }

        val runtimeConfig = runBlocking {
            RuntimeConfigRepository.configForProviderAndModel(providerId, modelId)
        } ?: return degraded(
            "provider_unavailable",
            "provider=$providerId model=$modelId 没有可用凭证",
        )
        // provider 行解析成功后，后续请求与日志都用 API 模型名。
        requestModel = runtimeConfig.model

        // 升级模型默认复用判断模型 provider 的凭证；用户在设置页换了 provider 则用显式值。
        // 未配置或与主模型相同时不升级；配置缺失只 warn，不影响主流程。
        val escalationConfig = if (escalationEnabled && escalationModelId.isNotBlank() &&
            escalationModelId != modelId
        ) {
            runBlocking {
                RuntimeConfigRepository.configForProviderAndModel(escalationProviderId, escalationModelId)
            }?.let { escalated ->
                // M4：历史样本来自本会话之前的决策；不足 5 条时纯函数会自动回退默认阈值。
                val history = AgentDecisionTelemetry.history(conversationId)
                AgentDecisionEscalation.Config(
                    modelId = escalationModelId,
                    baseUrl = escalated.baseUrl,
                    apiKey = escalated.apiKey,
                    confidenceThresholdPct = confidencePct,
                    historySamples = history.samples,
                    historyMissRate = history.missRate,
                    modelName = escalated.model,
                )
            } ?: run {
                logger.warn(
                    "decision.route escalation_unavailable provider=$escalationProviderId " +
                        "escalation_model=$escalationModelId reason=config_missing 本轮仅用主模型",
                )
                null
            }
        } else {
            null
        }

        // 缓存只存成功的原始评估；allowed/opened 仍按当前模式重算。
        val policySignature = listOf(
            mode,
            failurePolicy,
            timeoutMs,
            historyTurns,
            stickyTtl,
            escalationEnabled,
            escalationModelId,
            escalationProviderId,
            confidencePct,
            endpointOverride,
        ).joinToString("|")
        val cached = AgentDecisionCache.lookup(
            conversationId = conversationId,
            input = request.prompt,
            gateIds = gateIds,
            sticky = stickyBefore,
            modelId = modelId,
            continuation = isContinuation,
            policy = policySignature,
        )
        val cacheHit = cached != null
        // 唤起即屏幕上下文（硬边界）：needs_screen_context 问题只对助手弹窗 run 发问，
        // 主程序 run 的决策调用里不存在这个问题（用户定稿，非"问了不消费"）。
        val popupRun = request.handoff?.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        val evaluation = cached ?: AgentDecisionEvaluator.evaluate(
            request = request,
            modelId = modelId,
            providerBaseUrl = runtimeConfig.baseUrl,
            apiKey = runtimeConfig.apiKey,
            timeoutMs = timeoutMs,
            historyTurns = historyTurns,
            endpointOverride = endpointOverride,
            groups = gateGroups,
            stickyGroups = stickyBefore,
            isContinuation = isContinuation,
            popupRun = popupRun,
            escalation = escalationConfig,
            modelName = runtimeConfig.model,
        )
        if (!cacheHit && evaluation.succeeded) {
            AgentDecisionCache.store(
                conversationId = conversationId,
                input = request.prompt,
                gateIds = gateIds,
                sticky = stickyBefore,
                modelId = modelId,
                continuation = isContinuation,
                policy = policySignature,
                evaluation = evaluation,
            )
        }

        if (evaluation.skipReason != null) {
            return degraded(evaluation.skipReason, evaluation.detail)
        }
        if (evaluation.failureReason != null) {
            return degraded(evaluation.failureReason, evaluation.detail)
        }

        val selected = evaluation.selectedGroups intersect gateIds
        val stickyAfter = AgentDecisionStickyStore.update(
            conversationId = conversationId,
            selected = selected,
            input = request.prompt,
            gateIds = gateIds,
            ttl = stickyTtl,
        )
        val protectedAfter = historyProtected
        val allowed: Set<String>
        val opened: Set<String>
        if (mode == Prefs.Keys.AGENT_DECISION_MODE_CONTROL) {
            allowed = selected + stickyAfter + protectedAfter
            opened = allowed
        } else {
            allowed = gateIds
            opened = selected + stickyAfter + protectedAfter
        }
        // 唤起即屏幕上下文蕴含「非闲聊」：要真看屏幕就绝不能把组索引等提示段关掉。
        // 词面强信号同理：用户话里明写了某延迟组的关键词（如「终端」「命令」「脚本」），
        // 即使判断模型给低 needs_tools 也不判闲聊——闲聊分类会裁掉组索引并清空
        // tools_use_group 枚举，让模型只能谎称「我没有终端工具」（2026-11-14 日志取证）。
        // 判断模型分类错了，词面证据是比它更硬的信号。
        val literalSignals = AgentToolGroups.predictFromText(request.prompt) { it in gateIds }
        val chatOnly = mode == Prefs.Keys.AGENT_DECISION_MODE_CONTROL &&
            selected.isEmpty() && evaluation.needsTools < AgentDecisionEvaluator.NEEDS_TOOLS_THRESHOLD &&
            !evaluation.needsScreenContext && literalSignals.isEmpty()
        val disabledSections = if (chatOnly) {
            listOf(SECTION_SKILL_INDEX, SECTION_MEMORY, SECTION_GROUP_INDEX, SECTION_DELEGATION_RULE)
        } else {
            emptyList()
        }

        logger.info(
            "decision.route run_id=${request.runId} conversation_id=$conversationId " +
                "mode=$mode shadow=false model=$requestModel model_record=$modelId " +
                "escalated=${evaluation.escalated} " +
                "escalation_reason=${evaluation.escalationReason ?: "-"} " +
                "escalation_model=${evaluation.escalationModelId ?: "-"} " +
                "escalation_latency_ms=${evaluation.escalationLatencyMs} " +
                "escalation_failure=${evaluation.escalationFailure ?: "-"} " +
                "escalation_score=${evaluation.escalationScore ?: "-"} " +
                "cache_hit=$cacheHit skipped_for_continuation=false " +
                "endpoint=${evaluation.endpoint} " +
                "state_chars=${evaluation.stateChars} history_turns=${evaluation.historyTurnCount} " +
                "http_status=${evaluation.httpStatus ?: "-"} error_code=${evaluation.errorCode ?: "-"} " +
                "latency_ms=${evaluation.latencyMs} needs_tools=${AgentDecisionEvaluator.ratio(evaluation.needsTools)} " +
                "confidence=${AgentDecisionEvaluator.ratio(evaluation.toolGroupsConfidence)} " +
                "selected_groups=${selected.sorted()} allowed=${allowed.sorted()} " +
                "history_protected=${protectedAfter.sorted()} sticky=${stickyAfter.sorted()} " +
                "opened_after=${opened.sorted()} " +
                "prompt_sections_disabled=${disabledSections.joinToString(",")} " +
                "fallback_reason=none failure_policy=$failurePolicy layers=${evaluation.layers} " +
                "usage_in=${evaluation.usageIn ?: "-"} usage_out=${evaluation.usageOut ?: "-"}",
        )

        AgentDecisionDiagnostics.record(
            AgentDecisionDiagnostics.Summary(
                mode = mode,
                selected = selected.sorted(),
                allowed = allowed.sorted(),
                disabledSections = disabledSections,
                // 命中缓存时没有真实请求，报 0 避免把上一次的耗时当成这一轮的。
                latencyMs = if (cacheHit) 0 else evaluation.latencyMs + evaluation.escalationLatencyMs,
                usage = "${evaluation.usageIn ?: "-"}/${evaluation.usageOut ?: "-"}",
                escalated = evaluation.escalated,
                cacheHit = cacheHit,
                failureReason = null,
            ),
        )

        // M4 遥测：分母只算真实决策（缓存命中单列），降级单独计数。
        if (cacheHit) {
            AgentDecisionTelemetry.recordCacheHit(conversationId)
        } else {
            AgentDecisionTelemetry.recordDecision(conversationId)
        }
        if (evaluation.escalated) {
            AgentDecisionTelemetry.recordEscalation(conversationId, evaluation.escalationReason ?: "unknown")
        }

        val plan = AgentDecisionPlan(
            mode = mode,
            selectedGroups = selected,
            allowedGroups = allowed,
            openedGroups = opened,
            stickyGroups = stickyAfter,
            chatOnly = chatOnly,
            needsScreenContext = evaluation.needsScreenContext,
            failureReason = null,
            promptSectionsDisabled = disabledSections,
            openableGroupLimit = if (mode == Prefs.Keys.AGENT_DECISION_MODE_CONTROL) allowed else null,
            escalated = evaluation.escalated,
            escalationReason = evaluation.escalationReason,
            escalationLatencyMs = evaluation.escalationLatencyMs,
            escalationScore = evaluation.escalationScore,
            cacheHit = cacheHit,
        )
        synchronized(lastPlans) { lastPlans[conversationId] = plan }
        return plan
    }

    private fun readMode(): String = Prefs.getString(
        Prefs.Keys.AGENT_DECISION_MODE,
        Prefs.Keys.AGENT_DECISION_MODE_CONTROL,
    ).takeIf { it == Prefs.Keys.AGENT_DECISION_MODE_CONTROL || it == Prefs.Keys.AGENT_DECISION_MODE_ADVISE }
        ?: Prefs.Keys.AGENT_DECISION_MODE_CONTROL

    private fun readTimeoutMs(): Int = Prefs.getInt(
        Prefs.Keys.AGENT_DECISION_TIMEOUT_MS,
        Prefs.Keys.AGENT_DECISION_TIMEOUT_DEFAULT_MS,
    ).takeIf { it > 0 }?.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        ?: Prefs.Keys.AGENT_DECISION_TIMEOUT_DEFAULT_MS

    const val SECTION_SKILL_INDEX = "skill_index"
    const val SECTION_MEMORY = "memory"
    const val SECTION_GROUP_INDEX = "group_index"
    const val SECTION_DELEGATION_RULE = "delegation_rule"
}
