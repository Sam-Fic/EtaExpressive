package io.github.mangi.eta.agent.model

import kotlin.math.min

/**
 * 模型请求重发退避的单一计算入口，供 [AgentModelRetry] 与 AgentLoop 的工具后重连共用。
 *
 * 三条约束，按优先级：
 * 1. `Retry-After` 是提供方的下限，不能比它更早重发（否则只是继续撞限流）；
 * 2. 指数退避（2s、4s、8s…）是本地基线；
 * 3. 重连剩余窗口是上限，不能让单次等待跨过窗口边界而错过终态。
 *
 * 抖动只放宽第 2 条：同一账号下的多台设备此前会在 2s/4s/8s 上完全同步重试，
 * 一起撞开限流并互相延长恢复时间。[jitterFraction] 在基线上加 0..fraction 的比例，
 * 上限 [MAX_JITTER_CAP_MS]，绝不缩短提供方明确要求的时间。
 */
internal object ModelRetryBackoff {
    const val BASE_DELAY_MS = 2_000L
    const val MAX_RETRIES = 3

    /** 抖动比例。0 表示确定性退避，供测试与需要严格复现的调用方使用。 */
    const val DEFAULT_JITTER_FRACTION = 0.2
    const val MAX_JITTER_CAP_MS = 10_000L

    /** 尊重 `Retry-After` 的最长等待；超过它按本地退避继续，避免异常头值卡死整个窗口。 */
    const val MAX_RETRY_AFTER_MS = 120_000L

    /**
     * @param attempt 本轮退避周期内的第几次重发，从 1 开始。
     * @param retryAfterSec 提供方提示的等待秒数，无提示时为 null。
     * @param remainingMs 重连窗口剩余时间；无界窗口（CONTINUOUS）传 null。
     * @param jitterFraction 基线抖动比例，取值 0..1。
     */
    fun delayMs(
        attempt: Int,
        retryAfterSec: Long?,
        remainingMs: Long?,
        jitterFraction: Double,
        jitterSource: () -> Double,
    ): Long {
        val base = BASE_DELAY_MS shl (attempt - 1).coerceIn(0, 16)
        val jitter = if (jitterFraction <= 0.0) 0L else {
            val ratio = jitterFraction.coerceAtMost(1.0) * jitterSource().coerceIn(0.0, 1.0)
            min((base * ratio).toLong(), MAX_JITTER_CAP_MS)
        }
        var delay = base + jitter
        retryAfterSec
            ?.takeIf { it > 0 }
            ?.let { (it * 1_000L).coerceAtMost(MAX_RETRY_AFTER_MS) }
            ?.let { delay = maxOf(delay, it) }
        remainingMs?.let { delay = min(delay, it) }
        return delay.coerceAtLeast(0)
    }

    /** 该失败是否只允许有限次重发。永久性原因重发同一请求不会变好。 */
    fun permanentResendBudget(policyWindowMillis: Long?): Int = when (policyWindowMillis) {
        null -> 1 // 无限窗口降级为一次有界重发，不再伪装成网络抖动。
        0L -> 0
        else -> 1
    }
}
