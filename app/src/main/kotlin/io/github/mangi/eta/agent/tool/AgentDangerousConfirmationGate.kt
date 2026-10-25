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
 * 因此记录「已获用户同意的一次性授权」：
 * 1. 命中危险清单 → 拒绝并回 [AgentDangerousOperationPolicy.CONFIRM_CODE]，
 *    错误里要求模型先调 ask_user，并把同意选项的 id 指定为 [CONFIRM_OPTION_ID]；
 * 2. 模型调 ask_user（协议要求它必须单独成批），用户选了该选项 → 登记授权，
 *    绑定到「工具名 + 参数指纹」；
 * 3. 模型重发**同一个调用** → 授权一次性消费，放行；
 * 4. 换了参数或换了工具 → 没有对应授权，仍然要求确认（防止拿授权去执行别的操作）。
 *
 * 授权只用一次且短暂存活（[TTL_MS]）：即使用户同意过「卸载 A」，也不能拿它去卸载 B
 * 或反复卸载。
 *
 * 待确认槽只保留**一个**：`ask_user` 必须单独成批的既有协议保证了同一时刻
 * 最多只有一个危险调用在等确认，用单槽即可，不需要把任意 ask_user 和任意危险调用配对。
 */
internal object AgentDangerousConfirmationGate {
    /** 授权的存活时间：用户同意后模型通常下一轮就重发，留足一轮即可。 */
    const val TTL_MS = 5 * 60 * 1000L

    /**
     * `ask_user` 里表示「同意继续」的选项 id。模型被要求在提问时使用这个 id，
     * 闸门据此判定用户确实点了同意，而不是把「自定义/交给后续」当成同意。
     */
    const val CONFIRM_OPTION_ID = "confirm_once"

    private data class Grant(val expiresAt: Long)

    private val grants = ConcurrentHashMap<String, Grant>()

    /** 当前正在等用户确认的那次调用签名；空表示没有。 */
    private val pending = AtomicReference<String?>(null)

    /** 工具名 + 参数（规范化后）的唯一签名：参数不同就是另一次操作。 */
    fun signature(toolName: String, argumentsJson: String): String {
        val normalized = runCatching {
            org.json.JSONObject(argumentsJson.ifBlank { "{}" }).toString()
        }.getOrDefault(argumentsJson.trim())
        return toolName.trim().lowercase() + "\u0000" + normalized
    }

    /** 拒绝时登记「这次调用在等确认」。 */
    fun expectConfirmation(signature: String) {
        pending.set(signature)
    }

    fun pendingSignature(): String? = pending.get()

    /**
     * `ask_user` 拿到用户回答后调用。只有「明确同意」才登记授权：
     * `kind == option` 且 `optionId == CONFIRM_OPTION_ID`。
     * 其它回答（换选项、自定义文本、delegate）都不算同意，待确认槽保持原样。
     */
    fun onUserAnswered(answeredKind: String, optionId: String?): Boolean {
        val signature = pending.get() ?: return false
        val agreed = answeredKind == "option" && optionId == CONFIRM_OPTION_ID
        if (!agreed) return false
        grants[signature] = Grant(System.currentTimeMillis() + TTL_MS)
        pending.compareAndSet(signature, null)
        return true
    }

    /** 该调用是否已有可用授权；有则消费掉（一次性）。 */
    fun consume(signature: String): Boolean {
        val grant = grants.remove(signature) ?: return false
        if (System.currentTimeMillis() > grant.expiresAt) return false
        if (pending.get() == signature) pending.set(null)
        return true
    }

    /** 清理过期授权，避免长会话里积累。 */
    fun pruneExpired(now: Long = System.currentTimeMillis()) {
        grants.entries.removeIf { it.value.expiresAt < now }
    }

    fun clear() {
        grants.clear()
        pending.set(null)
    }
}
