package io.github.mangi.eta.agent.tool

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 危险操作的确认闸门。
 *
 * 确认交互走**模型自己用 ask_user 问**，所以这里必须回答一个问题：
 * 拒绝之后，模型问完用户、再次发起同一个调用时，凭什么放行？
 * 如果策略是无状态的，重发会被再次拒绝，模型就陷入「拒绝 → 询问 → 拒绝」的死循环。
 *
 * 因此记录「已获用户同意的授权」，并且区分粒度：
 * 1. 命中危险清单 → 拒绝并回 [AgentDangerousOperationPolicy.CONFIRM_CODE]，
 *    错误里要求模型先调 ask_user，并给出两个同意选项 id
 *    （[CONFIRM_OPTION_ID] 本次允许 / [CONFIRM_SESSION_OPTION_ID] 本会话允许）；
 * 2. 模型调 ask_user（协议要求它必须单独成批），用户选了同意选项 → 登记授权，
 *    绑定到「工具名 + 参数指纹」，并记录粒度；
 * 3. 模型重发**同一个调用** → 本次授权消费掉，本会话授权保留到过期，放行；
 * 4. 换了参数或换了工具 → 没有对应授权，仍然要求确认（防止拿授权去执行别的操作）。
 *
 * 授权只用一次或有限时长（[ONCE_TTL_MS] / [SESSION_TTL_MS]）：即使用户同意过「卸载 A」，
 * 也不能拿它去卸载 B，或跨会话反复使用。
 *
 * 待确认槽只保留**一个**：`ask_user` 必须单独成批的既有协议保证了同一时刻
 * 最多只有一个危险调用在等确认，用单槽即可，不需要把任意 ask_user 和任意危险调用配对。
 */
internal object AgentDangerousConfirmationGate {
    /** 一次性授权的存活时间：用户同意后模型通常下一轮就重发，留足一轮即可。 */
    const val ONCE_TTL_MS = 5 * 60 * 1000L

    /** 本会话授权的存活时间：同一个调用在此窗口内不再重复询问。 */
    const val SESSION_TTL_MS = 60 * 60 * 1000L

    /** 兼容旧调用方的常量名。 */
    const val TTL_MS = ONCE_TTL_MS

    /**
     * `ask_user` 里表示「同意继续」的选项 id。模型被要求在提问时使用这个 id，
     * 闸门据此判定用户确实点了同意，而不是把「自定义/交给后续」当成同意。
     */
    const val CONFIRM_OPTION_ID = "confirm_once"

    /** `ask_user` 里表示「本会话都允许同一调用」的选项 id；仅对中风险操作生效。 */
    const val CONFIRM_SESSION_OPTION_ID = "confirm_session"

    /** `ask_user` 里表示「取消」的推荐选项 id；不构成同意，仅用于让模型统一文案。 */
    const val CANCEL_OPTION_ID = "cancel"

    private data class Grant(val expiresAt: Long, val scope: AgentConfirmationScope)

    private data class Pending(val signature: String, val allowSession: Boolean)

    private val grants = ConcurrentHashMap<String, Grant>()

    /** 当前正在等用户确认的那次调用签名；空表示没有。 */
    private val pending = AtomicReference<Pending?>(null)

    /** 工具名 + 参数（规范化后）的唯一签名：参数不同就是另一次操作。 */
    fun signature(toolName: String, argumentsJson: String): String {
        val normalized = runCatching {
            org.json.JSONObject(argumentsJson.ifBlank { "{}" }).toString()
        }.getOrDefault(argumentsJson.trim())
        return toolName.trim().lowercase() + "\u0000" + normalized
    }

    /** 拒绝时登记「这次调用在等确认」，并记录用户最多可以授权到哪一档。 */
    fun expectConfirmation(signature: String, assessment: AgentRiskAssessment) {
        pending.set(
            Pending(
                signature = signature,
                allowSession = assessment.scope == AgentConfirmationScope.SESSION,
            ),
        )
    }

    fun pendingSignature(): String? = pending.get()?.signature

    /**
     * `ask_user` 拿到用户回答后调用。只有「明确同意」才登记授权：
     * `kind == option` 且 optionId 是本次允许 / 本会话允许。
     * 其它回答（换选项、自定义文本、delegate）都不算同意，待确认槽保持原样。
     *
     * @return 是否登记了授权（供旧调用方与测试判断）
     */
    fun onUserAnswered(answeredKind: String, optionId: String?): Boolean =
        onUserAnsweredScoped(answeredKind, optionId) != null

    /**
     * 与 [onUserAnswered] 相同，但返回实际登记的粒度；null 表示这次回答不构成同意。
     *
     * 高风险/致命操作即使选了「本会话允许」也只授权一次：这两档不允许一劳永逸。
     */
    fun onUserAnsweredScoped(
        answeredKind: String,
        optionId: String?,
    ): AgentConfirmationScope? {
        val awaiting = pending.get() ?: return null
        if (answeredKind != "option") return null
        val scope = when (optionId) {
            CONFIRM_OPTION_ID -> AgentConfirmationScope.ONCE
            CONFIRM_SESSION_OPTION_ID ->
                if (awaiting.allowSession) AgentConfirmationScope.SESSION else AgentConfirmationScope.ONCE
            else -> return null
        }
        val ttl = if (scope == AgentConfirmationScope.SESSION) SESSION_TTL_MS else ONCE_TTL_MS
        grants[awaiting.signature] = Grant(System.currentTimeMillis() + ttl, scope)
        pending.compareAndSet(awaiting, null)
        return scope
    }

    /**
     * 该调用是否已有可用授权。
     *
     * [requiredScope] 为 SESSION 时，本会话授权会被保留（可复用）；其余情况一律消费掉。
     */
    fun consume(signature: String, requiredScope: AgentConfirmationScope): Boolean {
        val grant = grants[signature] ?: return false
        if (System.currentTimeMillis() > grant.expiresAt) {
            grants.remove(signature, grant)
            return false
        }
        if (grant.scope != AgentConfirmationScope.SESSION || requiredScope == AgentConfirmationScope.ONCE) {
            grants.remove(signature, grant)
        }
        if (pending.get()?.signature == signature) pending.set(null)
        return true
    }

    /** 兼容旧签名：按一次性授权消费。 */
    fun consume(signature: String): Boolean = consume(signature, AgentConfirmationScope.ONCE)

    /** 清理过期授权，避免长会话里积累。 */
    fun pruneExpired(now: Long = System.currentTimeMillis()) {
        grants.entries.removeIf { it.value.expiresAt < now }
    }

    fun clear() {
        grants.clear()
        pending.set(null)
    }
}
