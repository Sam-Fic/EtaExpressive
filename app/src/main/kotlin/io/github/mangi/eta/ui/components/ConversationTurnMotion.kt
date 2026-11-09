package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import kotlin.math.roundToInt

/** 跳转动画的总时长下限：不到一屏也要有起步/收尾，不能一帧瞬移。 */
internal const val ConversationTurnMinDurationMillis = 180

/**
 * 跳转动画的总时长硬上限。距离再远也不许超过它——以前的巡航速度上限让
 * 「跳到会话开头/末尾」在长会话里滚十几秒；现在速度由「距离 ÷ 时长」反推，
 * 不设上限，时间到就由调用方 snap 到目标。
 */
internal const val ConversationTurnMaxDurationMillis = 650

/** 时长爬到上限所需的屏数；超过这个距离后时长恒定，速度随距离继续放大。 */
internal const val ConversationTurnFullScaleScreens = 8f

/** 单帧最大时间步长：卡帧不让一帧吃掉整段动画，也不让 dt 把时长预算撑爆。 */
internal const val ConversationTurnMaxFrameSeconds = 0.05f

/**
 * 距离只决定时长，不决定速度上限。短距离给一段轻快的缓动，距离越远时长越长，
 * 到 [ConversationTurnFullScaleScreens] 屏后封顶在 [ConversationTurnMaxDurationMillis]；
 * 超长跳转因此始终在同样的时间内结束，只是速度更快。
 */
internal fun resolveConversationJumpDurationMillis(distancePx: Float, viewportPx: Float): Int {
    val screens = (distancePx / viewportPx.coerceAtLeast(1f)).coerceAtLeast(0f)
    val scale = (screens / ConversationTurnFullScaleScreens).coerceIn(0f, 1f)
    return (ConversationTurnMinDurationMillis +
        (ConversationTurnMaxDurationMillis - ConversationTurnMinDurationMillis) * scale)
        .roundToInt()
}

/**
 * M3 Expressive 官方 emphasized easing：`CubicBezierEasing(0.2f, 0f, 0f, 1f)`。
 *
 * 取值核对自本仓 material3 1.5.0-alpha29 的 AAR：
 * `androidx.compose.material3.tokens.MotionTokens.getEasingEmphasizedCubicBezier()`
 * 的控制点是 (0.2, 0, 0, 1)，`EasingStandardCubicBezier` 同值；构造签名
 * `CubicBezierEasing(Float, Float, Float, Float)`、`Easing.transform(Float): Float`
 * 来自 animation-core 1.13.0-alpha01 的 AAR（均经 javap 核实）。
 *
 * 官方动效预设分两类（本仓 material3 1.5.0-alpha29 AAR javap 核实）：
 *  1. `MotionTokens` 的 cubic-bezier easing，共 10 条（emphasized / standard /
 *     legacy / linear 及各自 accelerate / decelerate）。y 控制点全部落在 [0,1]，
 *     **没有一条会过冲回弹**；`EasingEmphasizedCubicBezier` 与
 *     `EasingStandardCubicBezier` 恰好同值 (0.2, 0, 0, 1)。
 *  2. `MotionScheme` 的两类弹簧：`spatial`（位置/尺寸/形状，Expressive 阻尼
 *     0.8 / 0.6、Standard 0.9，欠阻尼 ⇒ 会过冲回弹）与 `effects`（颜色/透明度，
 *     阻尼 1.0 临界阻尼 ⇒ 不过冲）。`MotionScheme.standard()` 与
 *     `MotionScheme.expressive()` 的六个规格全是 SpringSpec，区别只在阻尼与刚度，
 *     不在「有没有 easing」。
 *
 * 滚动跳转是位移 ⇒ 对应 spatial，会回弹；effects 虽不回弹却是给颜色/透明度用的，
 * 而且 SpringSpec 没有固定时长，与「总时长硬封顶 + 到点 snap」冲突。因此这里
 * 只能取第 1 类 easing：官方曲线预设在 `MotionTokens`，不在 MotionScheme。
 *
 * 这条曲线的两个 y 控制点是 0 与 1，因此 P(progress) 全程落在 [0,1] 且单调不减
 * （数值实测：P(0.2)=0.5、P(0.5)=0.878、P(1)=1），两端切线斜率都为 0 ——
 * 起步缓、收尾缓、绝不越过目标，也不会把预算末尾拖成匀速空转。
 * 与 `EtaMotion.DrawerEasing` 同值，同一官方 token：整块位移一律不用弹簧。
 */
internal val ConversationTurnEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

/** 进度先钳进 [0,1]，再交给官方 easing；保证预算用尽的那一帧恰好走到目标。 */
internal fun conversationJumpEasing(progress: Float): Float =
    ConversationTurnEasing.transform(progress.coerceIn(0f, 1f))

/**
 * 时间预算驱动的跳转曲线。
 *
 * 与旧的「加速度 + 巡航速度上限」不同，这里没有任何速度或单帧位移上限：
 * 把整段动画看成在 [duration] 内从 0 走到 total，每帧按「easing 给出的应到
 * 位置 − 实际已消费像素」推进，因而平均速度天然等于 total / duration ——
 * 距离越长速度越快，总时长却封顶；欠消费也会在下一帧被自动补回。
 *
 * [travelledPx] 由调用方按 scrollBy 的**实际消费量**累计，[remainingPx] 是目标
 * 可见时的精确剩余（未量到时传粗估值）。两者之和即本帧重算的总距离，测量误差
 * 会在下一帧自动修正，不需要速度状态机跨帧接力。
 */
internal class ConversationTurnMotion(viewportPx: Float) {
    private val viewport = viewportPx.coerceAtLeast(1f)
    private var elapsedSeconds = 0f
    private var durationSeconds = 0f

    /** 时长只许变长：粗估总距离修正时不能让 progress 倒退，否则会出现零位移帧。 */
    private fun budgetFor(totalPx: Float): Float {
        val seconds = resolveConversationJumpDurationMillis(totalPx, viewport) / 1000f
        durationSeconds = maxOf(durationSeconds, seconds.coerceAtLeast(0.001f))
        return durationSeconds
    }

    fun advance(
        elapsedFrameSeconds: Float,
        travelledPx: Float,
        remainingPx: Float,
    ): Float {
        val dt = elapsedFrameSeconds.coerceIn(0f, ConversationTurnMaxFrameSeconds)
        if (dt <= 0f || budgetExhausted()) return 0f
        val distance = remainingPx.coerceAtLeast(0f)
        val travelled = travelledPx.coerceAtLeast(0f)
        val total = (travelled + distance).coerceAtLeast(viewport)
        val duration = budgetFor(total)
        // 时间必须先推进：即便本帧剩余距离已是 0，预算也要继续走完，否则调用方
        // 会卡在「没位移但预算永不耗尽」的空转里。
        elapsedSeconds = (elapsedSeconds + dt).coerceAtMost(duration)
        if (distance <= 0f) return 0f
        val to = (elapsedSeconds / duration).coerceIn(0f, 1f)
        // 用「本帧应该累计走到的位置 − 实际已消费像素」而非 easing 差分：超长跳转
        // 一帧要跨好几屏，scrollBy 常因 item 还没量到而欠消费，绝对差会在下一帧
        // 自动把欠的量补回来，不至于最后靠 snap 跳过一大截。
        val desiredTravelled = total * conversationJumpEasing(to)
        return (desiredTravelled - travelled).coerceIn(0f, distance)
    }

    /** 时间预算用尽：调用方必须立即 snap 到目标，不再继续逐帧滚动。 */
    fun budgetExhausted(): Boolean =
        durationSeconds > 0f && elapsedSeconds >= durationSeconds - 0.0001f
}
