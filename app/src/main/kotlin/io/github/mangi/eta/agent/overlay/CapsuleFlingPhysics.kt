package io.github.mangi.eta.agent.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.channels.Channel

/**
 * 甩球物理参数：与产品提供的参考实现（throw-ball.html）逐项对齐。
 *
 * 参考实现是一段 requestAnimationFrame 逐帧积分脚本，不是 CSS keyframe：没有
 * cubic-bezier、没有关键帧、没有 stagger，动画由「指数插值速率 + 速度分段阻力 +
 * 一根 K/C 弹簧」构成。因此这里的常量直接对应脚本里的数值（脚本单位 CSS px ≈ dp），
 * 由调用方按当前密度换算成物理像素后再积分。
 *
 * 位置坐标约定：内部一律按**内容盒**积分——`centerX/centerY` 是内容中心，边界只按内容的
 * 实时半宽半高算（与参考实现直接读 `hx()/hy()` 同源）。12dp 阴影外衬不参与边界，改由宿主
 * 投影时补回（窗口 = 内容 + 外衬），所以停靠时本体贴合可用边界、外衬允许溢出屏外
 * （前提就是 `FLAG_LAYOUT_NO_LIMITS`）。参考实现里元素本身就是可见外形，没有这层外衬。
 */
internal object CapsuleFlingPhysics {
    /** 抬手速度取样窗口：只拟合最近 110ms 的指针位移（参考实现 cutoff = now - 110）。 */
    const val VelocityWindowMillis = 110L

    /** 抬手前超过 90ms 没有新采样即视为停顿：按 0 速度轻轻放下（参考实现 90ms）。 */
    const val VelocityStallMillis = 90L

    /** 松手速度达到该值 → 胶囊 morph 成球（参考实现 MORPHV = 1200px/s，任意方向）。 */
    const val MorphVelocityDpPerSec = 1200f

    /** 甩出初速度上限（参考实现 MAXV = 3400px/s）。 */
    const val MaxVelocityDpPerSec = 3400f

    /** 阻力分段的速度分界（参考实现 KSPEED = 1400px/s）。 */
    const val FastDragSpeedDpPerSec = 1400f

    /**
     * 高速阻力（参考实现 DRAG_FAST = 2.5，单位 1/s）：保证用力甩得出去、甩得远。
     * 与低速档按速度线性过渡，不是分段硬切。
     */
    const val FastDrag = 2.5f

    /**
     * 低速阻力（参考实现 DRAG_SLOW = 8，单位 1/s）：“黏”——缓缓滑动的部分很快刹停。
     * 屏内低速不是急刹清零，而是交给 settle 弹簧带着余速平滑减速（轨迹连续）。
     */
    const val SlowDrag = 8f

    /** 屏内低于该速度进入 settle 收尾（参考实现 STOPV = 35px/s）。 */
    const val StopVelocityDpPerSec = 35f

    /** 回飞/落定共用弹簧刚度与阻尼（参考实现 K = 70, C = 13，ζ≈0.78）。 */
    const val SpringStiffness = 70f
    const val SpringDamping = 13f

    /** 贴边回飞速度上限（参考实现 2600px/s）。 */
    const val ReturnMaxVelocityDpPerSec = 2600f

    /** 单帧步长上限：切后台回来不炸（参考实现 dt ≤ 0.033s）。 */
    const val MaxFrameSeconds = 0.033f

    /** 形变指数插值速率（参考实现 `1 - exp(-16·dt)`）。 */
    const val MorphRatePerSecond = 16f

    /**
     * 拖动跟手插值速率：对应参考实现 `1 - Math.exp(-30 * dt)` 的那根时间常数，但取值放大了 8 倍。
     *
     * 参考实现改的是 DOM transform，写入即同帧上屏；这里每次下发都要经 binder 走
     * WindowManagerService，天然再叠一帧。照抄 30/s（τ≈33ms）会让卡片比手指慢 ~50ms，
     * 实机就是「拖不动/黏手」的卡顿感。240/s 时一帧内收敛 98%，残差不足一帧，
     * 既保留参考实现「不是瞬移」的平滑（避免 240Hz 输入采样抖动直接透传成窗口抖动），
     * 又不引入可感延迟。
     */
    const val DragFollowRatePerSecond = 240f

    /**
     * 尺寸稳定判据（帧）：实测内容尺寸连续这么多帧不再变化才算「尺寸动画跑完」。
     *
     * 绝不能用「实测 ≈ 解析目标」当判据：实测会**合法地**超出解析目标（补充框换行、
     * 舞台画布按宽高比居中），那样循环永远等不到落定、永不休眠，`onSettled` 不执行
     * → `flingRunning` 永久为真 → 「晚缩」被冻结在甩出前的大窗（透明区白吃触摸），
     * 直到下一次交互末尾才一次性收窗——实机就是「动画末尾抽搐一下」。
     */
    const val SizeStableFramesToSettle = 2

    /** settle 收敛判定（参考实现 |Δ| < 0.5px 且 |v| < 15px/s）。 */
    const val SettleRestDistanceDp = 0.5f
    const val SettleRestSpeedDpPerSec = 15f

    /** 贴边落定判定（参考实现法向 < 0.8px 且 |v| < 30px/s）。 */
    const val DockNormalToleranceDp = 0.8f
    const val DockRestSpeedDpPerSec = 30f

    /** 收尾强制超时（参考实现 settle 1.5s / return 4s）。 */
    const val SettleTimeoutMillis = 1500L
    const val ReturnTimeoutMillis = 4000L
}

/**
 * 松手瞬间由宿主结算的甩出计划。
 *
 * 只带**速度**与**是否缩球**两项：拖动段也归帧循环积分，抬手时起点就是循环自己的
 * 当前内容中心（无需重新播种），边界尺寸由循环按实时实测值读，宿主完全不参与几何。
 *
 * @property velocity 最近 [CapsuleFlingPhysics.VelocityWindowMillis] 指针位移拟合出的甩出速度（px/s）
 * @property ballTarget 宿主已判定本次甩出把胶囊 morph 成球（宽度目标切到球径）
 */
internal data class CapsuleFlingPlan(
    val velocity: Offset,
    val ballTarget: Boolean,
)

/** 与参考实现的 phase 一一对应：drag / free / settle / return → docked，纯屏内停下 = rest。 */
internal enum class CapsuleMotionPhase { Drag, Free, Settle, Return, Docked, Rest }

/** 停靠边：参考实现 dockEdge 的四个取值。 */
internal enum class CapsuleEdge { Left, Right, Top, Bottom }

/**
 * 单一帧循环的运动状态（对应参考实现脚本里的那一组顶层变量）。
 *
 * 宽度**不**归本循环独占：它是 Compose 的 [Animatable]，由组合侧推进（展开 320ms tween /
 * 胶囊⇄球逐帧指数 morph），布局阶段读取（只失效布局、不触发重组）。本循环只读取它做边界
 * 计算，并在甩出触发的形变期间用它算贴边目标。
 *
 * 注意：本循环**不得**自己拥有宽度通道。上一版把宽度搬进循环（`mutableFloatStateOf` +
 * 自造 tween）后，与拖拽/宿主钳制互相覆盖，真机上表现为乱跳、不跟手，已回退。
 * 高度同理不自有——几何一律读宿主实测的内容尺寸（见 [runCapsuleMotion] 的 liveSize）。
 */
internal class CapsuleMotion(
    /** 宿主密度：参考实现常量按 dp 表达，积分/边界用物理像素。 */
    val density: Float,
) {
    /** 宽度目标（dp，对应参考实现 wT）：只在形态切换时改写，不逐帧重建。 */
    var widthTargetDp = 0f

    /**
     * 宽度是否由本循环推进：只有「胶囊 ⇄ 球」这一段为 true（甩出缩球、点球回胶囊）。
     * 展开 / 收起大卡的宽度仍归组合侧 320ms tween，本循环只在旁边读它算边界。
     */
    var morphWidth = false

    /**
     * 状态目标内容尺寸（px）：**不参与几何积分**，只在首轮还没量到实测尺寸时兜底做起值。
     * 几何一律用实时实测值：按目标尺寸钳位会在「早涨」那一瞬把位置一次推到位，
     * 实机就是展开/收起的位置跳变（跳变量 = Δ宽/2）。循环能否休眠看的是实测是否
     * 连续几帧不再变化（见 [CapsuleFlingPhysics.SizeStableFramesToSettle]），与这两个值无关。
     */
    var targetContentWidthPx = 0f
    var targetContentHeightPx = 0f

    /** 内容中心（px，对应参考实现 x / y）与速度。 */
    var centerX = 0f
    var centerY = 0f
    var velocityX = 0f
    var velocityY = 0f

    /**
     * 顶边锚（px，内容顶边）：静止 / 停靠段尺寸涨缩时**顶边不动、中心跟着尺寸走**。
     * 大卡是下拉式容器（根 Box 只有 TopCenter，多出来的高度在下方），按中心涨缩会让整块上下窜。
     * 飞行段每帧与 [centerY] 同步，所以从飞行切到静止时锚点即当前顶边，不跳位。
     */
    var anchorTopY = 0f

    /** 手指是否按住：按住期间循环不得休眠（参考实现 pointerdown → drag，直到 release 才换相位）。 */
    var pointerDown = false

    /**
     * 拖动目标 = 指针 raw 坐标 + 按下瞬间的抓取偏移（对应参考实现 `px + ox`）。
     * 指针层逐事件只写这两个字段并 [wake]，**不碰窗口**，所以每个 ACTION_MOVE 零跨进程调用；
     * 真正跟手由循环按 [CapsuleFlingPhysics.DragFollowRatePerSecond] 逐帧插值并每帧下发一次。
     */
    var dragTargetX = 0f
    var dragTargetY = 0f

    /** 位置是否已由宿主播种：未播种前循环不得做任何约束或落位。 */
    var positioned = false

    var phase = CapsuleMotionPhase.Rest
    var dockEdge = CapsuleEdge.Bottom
    var settleX = 0f
    var settleY = 0f
    var phaseStartNanos = 0L

    /** 唤醒信号：目标变化 / 甩出 / 按下 / 实测尺寸变化时投递；循环落定后挂起等待，不空转占帧。 */
    val wakeups = Channel<Unit>(Channel.CONFLATED)

    fun wake() {
        wakeups.trySend(Unit)
    }

    /** 上一次真正下发给宿主的落点：变化不足半像素就不重复走一次窗口重排。 */
    var lastEmittedCenterX = Float.NaN
    var lastEmittedContentTopY = Float.NaN

    /**
     * 作废去重基线。宿主自己动过窗口位置（首次落位、旋转/inset 重钳）或循环重新播种后**必须**
     * 调用：否则下一次下发可能因与旧基线差不足半像素而被跳过，宿主收不到真实落点，
     * 位置就永久停在宿主钳出来的那个值上（表现为交接后头一帧不动）。
     * NaN 是「基线不存在」的哨兵，循环内侧见到它一律无条件下发（见 runCapsuleMotion 的 emit）。
     */
    fun resetEmitted() {
        lastEmittedCenterX = Float.NaN
        lastEmittedContentTopY = Float.NaN
    }
}

/** 复用的边界盒子：帧循环里每帧算两次（step 前 / morph 后），不新建对象。 */
private class MotionBounds {
    var minCenterX = 0f
    var maxCenterX = 0f
    var minCenterY = 0f
    var maxCenterY = 0f
}

/**
 * 按**实时内容尺寸**算可停靠区间（参考实现 step 里每帧直接读 `hx()` / `hy()`）。
 *
 * 不含阴影外衬：贴合的是可见本体，外衬那 12dp 由宿主投影时补回并允许溢出屏外。
 * 按目标尺寸而不是按实测尺寸算，就是「早涨那一瞬位置一次推到位」的跳变来源。
 */
private fun updateMotionBounds(
    viewport: Rect,
    contentWidthPx: Float,
    contentHeightPx: Float,
    out: MotionBounds,
) {
    val halfWidth = contentWidthPx / 2f
    val halfHeight = contentHeightPx / 2f
    out.minCenterX = viewport.left + halfWidth
    out.maxCenterX = (viewport.right - halfWidth).coerceAtLeast(out.minCenterX)
    out.minCenterY = viewport.top + halfHeight
    out.maxCenterY = (viewport.bottom - halfHeight).coerceAtLeast(out.minCenterY)
}

/** 参考实现 dockTarget() 的 x 分量：左右边贴死，上下边只做夹回。 */
private fun dockTargetX(cx: Float, bounds: MotionBounds, edge: CapsuleEdge): Float = when (edge) {
    CapsuleEdge.Left -> bounds.minCenterX
    CapsuleEdge.Right -> bounds.maxCenterX
    else -> cx.coerceIn(bounds.minCenterX, bounds.maxCenterX)
}

/** 参考实现 dockTarget() 的 y 分量。 */
private fun dockTargetY(cy: Float, bounds: MotionBounds, edge: CapsuleEdge): Float = when (edge) {
    CapsuleEdge.Top -> bounds.minCenterY
    CapsuleEdge.Bottom -> bounds.maxCenterY
    else -> cy.coerceIn(bounds.minCenterY, bounds.maxCenterY)
}

/** 参考实现 enterReturn() 的最近边选择：按到四条屏幕边的距离排序，取最小。 */
private fun nearestEdge(cx: Float, cy: Float, viewport: Rect): CapsuleEdge {
    val left = cx - viewport.left
    val right = viewport.right - cx
    val top = cy - viewport.top
    val bottom = viewport.bottom - cy
    var edge = CapsuleEdge.Left
    var best = left
    if (right < best) {
        edge = CapsuleEdge.Right
        best = right
    }
    if (top < best) {
        edge = CapsuleEdge.Top
        best = top
    }
    if (bottom < best) {
        edge = CapsuleEdge.Bottom
    }
    return edge
}

/**
 * 单一帧循环：逐字复刻参考实现 `frame()` 的每帧顺序
 * `dt → step(dt) → 形变 → 约束（docked/rest 按当前尺寸重算）→ render`。
 *
 * 循环醒着时它是**唯一的位置写入方**：拖动、飞行、回弹、形变期间的落位都从这里逐帧下发，
 * 宿主只把「内容中心 + 内容顶边」投影成窗口坐标。上一版把所有权在宿主与循环之间来回让渡
 * （指针捕获 / 甩出中 / 420ms 锚定期 / positioned 重新播种），每一次让渡就是一次跳变。
 *
 * 与上一版的差别（也是「看起来和 HTML 不一样」的根因）：
 * 1. 拖动段也归本循环积分（参考实现的 `phase === "drag"` 分支）：指针层逐事件只写目标点，
 *    每帧一次 `1 - exp(-30·dt)` 跟手插值 + 内容盒硬钳，不再有橡皮筋，也不再逐事件写窗口；
 * 2. 边界不再固定为松手瞬间的占位，而是每帧用**实测内容尺寸** [liveSize] 重算（参考实现
 *    step 里就是直接读 hx()/hy()）；缩球途中贴边目标跟着形变走，停靠不跳变；
 * 3. 边界只算内容盒，12dp 阴影外衬不参与 → 停靠时本体贴合可用边界（外衬由宿主补回，
 *    允许溢出屏外，前提就是窗口的 `FLAG_LAYOUT_NO_LIMITS`）；
 * 4. 宽度是组合侧推进的 [Animatable]：展开/收起走 320ms tween，甩出缩球走本循环的指数
 *    morph（`mk = 1 - exp(-16·dt)`），两条通道互斥、绝不同帧两个写入方；高度不在这里推进，
 *    它由 Compose 的展开/收起动画驱动，本循环只读实测值；
 * 5. 圆角在参考实现里恒为 h/2：胶囊与球同高 56dp，Compose 侧圆角恒 28dp（= h/2），
 *    两端完全等价，故不另设独立过渡；
 * 6. 静止/停靠段按**顶边锚**涨缩（[CapsuleMotion.anchorTopY]）：容器是下拉式的，多出来的
 *    高度在下方，按中心涨缩会让整块上下窜；撞到可用边界时是平滑上移，不是瞬移。
 *
 * 收敛收口：指数曲线永远到不了目标，宽度残差 < 0.05dp 时直接落到目标（亚像素，不可见）；
 * 尺寸是否还在动，按「实测尺寸连续 [CapsuleFlingPhysics.SizeStableFramesToSettle] 帧不变」判定，
 * **绝不按「实测 ≈ 解析目标」判**——实测会合法超出解析目标，那样循环就永远等不到落定、永不休眠。
 *
 * @param widthDp 胶囊宽度（dp）：组合侧推进的 [Animatable]，布局阶段读取；本循环只在
 *   甩出缩球通道里推进它，其余时间只读它算边界。
 * @param liveSize 当前**实测内容尺寸**（px，不含阴影外衬）：所有边界与贴边目标的唯一尺寸来源。
 *   用状态目标尺寸钳位会在「早涨」那一瞬把位置一次推到位，实机就是展开/收起的位置跳变。
 * @param onPosition 每帧把「内容中心 x + 内容顶边 y」下发给宿主；变化不足 0.5px 不重复下发。
 * @param onSettled 循环落定（尺寸收敛且物理已静止、手指已抬起）后回调一次，随后函数返回。
 */
internal suspend fun runCapsuleMotion(
    motion: CapsuleMotion,
    widthDp: Animatable<Float, AnimationVector1D>,
    liveSize: () -> IntSize,
    viewport: () -> Rect,
    onPosition: (centerX: Float, contentTopY: Float) -> Unit,
    onSettled: () -> Unit,
) {
    val scale = motion.density.coerceAtLeast(0.1f)
    val maxVelocity = CapsuleFlingPhysics.MaxVelocityDpPerSec * scale
    val fastDragSpeed = CapsuleFlingPhysics.FastDragSpeedDpPerSec * scale
    val stopVelocity = CapsuleFlingPhysics.StopVelocityDpPerSec * scale
    val returnMaxVelocity = CapsuleFlingPhysics.ReturnMaxVelocityDpPerSec * scale
    val settleRestSpeed = CapsuleFlingPhysics.SettleRestSpeedDpPerSec * scale
    val dockRestSpeed = CapsuleFlingPhysics.DockRestSpeedDpPerSec * scale
    val settleRestDistance = CapsuleFlingPhysics.SettleRestDistanceDp * scale
    val dockNormalTolerance = CapsuleFlingPhysics.DockNormalToleranceDp * scale
    val settleTimeoutNanos = CapsuleFlingPhysics.SettleTimeoutMillis * 1_000_000L
    val returnTimeoutNanos = CapsuleFlingPhysics.ReturnTimeoutMillis * 1_000_000L

    var cx = motion.centerX
    var cy = motion.centerY
    var vx = motion.velocityX
    var vy = motion.velocityY
    var phase = motion.phase
    var phaseStartNanos = motion.phaseStartNanos
    var settleX = motion.settleX
    var settleY = motion.settleY
    // 实时内容尺寸：首轮还没量过时退回状态目标（目标只作初值，一旦量到就以实测为准）。
    var liveWidthPx = widthDp.value * scale
    var liveHeightPx = motion.targetContentHeightPx
    // 尺寸稳定跟踪：只比**上一帧的实测尺寸**，与解析目标无关（判据见 SizeStableFramesToSettle）。
    var lastMeasuredSize = IntSize.Zero
    var stableSizeFrames = 0
    val bounds = MotionBounds()

    // 初速度上限（参考实现 release() 里先钳速度，再进 free）。
    val launchSpeed = hypot(vx, vy)
    if (launchSpeed > maxVelocity) {
        val factor = maxVelocity / launchSpeed
        vx *= factor
        vy *= factor
    }

    fun emit(force: Boolean) {
        val contentTopY = cy - liveHeightPx / 2f
        // 基线被作废（NaN）后第一帧必须无条件下发：`abs(x - NaN) >= 0.5f` 恒为 false，
        // 少了这一句，去重会把整趟循环的非强制下发全部吃掉，基线永远建不起来——
        // 实机就是「甩出看不见飞、拖动不跟手，末了一帧一次 teleport」（抽搐的同一个人）。
        val baselineLost = motion.lastEmittedCenterX.isNaN() || motion.lastEmittedContentTopY.isNaN()
        if (force || baselineLost ||
            abs(cx - motion.lastEmittedCenterX) >= 0.5f ||
            abs(contentTopY - motion.lastEmittedContentTopY) >= 0.5f
        ) {
            motion.lastEmittedCenterX = cx
            motion.lastEmittedContentTopY = contentTopY
            onPosition(cx, contentTopY)
        }
    }

    fun commit() {
        motion.centerX = cx
        motion.centerY = cy
        motion.velocityX = vx
        motion.velocityY = vy
        motion.phase = phase
        motion.phaseStartNanos = phaseStartNanos
        motion.settleX = settleX
        motion.settleY = settleY
    }

    var lastFrameNanos = withFrameNanos { it }
    while (true) {
        val frameNanos = withFrameNanos { it }
        var dt = (frameNanos - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = frameNanos
        if (dt > CapsuleFlingPhysics.MaxFrameSeconds) dt = CapsuleFlingPhysics.MaxFrameSeconds
        if (dt <= 0f) continue

        // 每帧重读相位：手指按下 / 取消 / 旋转会在外部改相位，本循环必须当帧就停手。
        // 否则仍在跑的那次循环会继续按旧的 docked/rest 逐帧下发窗口位置，
        // 与拖拽路径每帧写入互相覆盖（真机症状：乱跳、不跟手）。
        if (phase != motion.phase) {
            phase = motion.phase
            phaseStartNanos = motion.phaseStartNanos
            // 换相位必然伴随外部写入新速度（startFling / releaseToFree / 取消手势都是成对写）：
            // 拖动期间本循环一直醒着，局部 vx/vy 停在 Drag 段清零的 0，不在这里重读，
            // 抬手甩出的拟合速度就进不了积分——Free 段拿 0 速度瞬间落 Settle，根本抛不出去。
            vx = motion.velocityX
            vy = motion.velocityY
        }

        // ① 取实时尺寸与边界（参考实现每帧直接读 w/h）。
        val measured = liveSize()
        if (measured.width > 0) liveWidthPx = measured.width.toFloat()
        if (measured.height > 0) liveHeightPx = measured.height.toFloat()
        // 尺寸动画是否还在跑，只看实测有没有继续变：展开 / 收起 / morph 每帧都在换尺寸，
        // 量到不再换（整值相等）才允许本循环休眠。判据不能用「实测 ≈ 解析目标」。
        stableSizeFrames = if (measured == lastMeasuredSize) stableSizeFrames + 1 else 0
        lastMeasuredSize = measured
        val vp = viewport()
        val resting = phase == CapsuleMotionPhase.Rest || phase == CapsuleMotionPhase.Docked
        updateMotionBounds(vp, liveWidthPx, liveHeightPx, bounds)
        if (resting) {
            // 顶边锚：尺寸涨缩时顶边不动，中心跟着尺寸走；撞到可用区下沿才平滑上移。
            cy = (motion.anchorTopY + liveHeightPx / 2f).coerceIn(bounds.minCenterY, bounds.maxCenterY)
        }

        // ② step(dt)：此刻尺寸还是形变前的 w/h（参考实现 step 在 morph 之前执行）。
        when (phase) {
            CapsuleMotionPhase.Drag -> {
                // 跟手插值 + 整块限制在可用区内（参考实现 drag 分支：硬钳，没有橡皮筋）。
                val follow = 1f - exp(-CapsuleFlingPhysics.DragFollowRatePerSecond * dt)
                val targetX = motion.dragTargetX.coerceIn(bounds.minCenterX, bounds.maxCenterX)
                val targetY = motion.dragTargetY.coerceIn(bounds.minCenterY, bounds.maxCenterY)
                cx += (targetX - cx) * follow
                cy += (targetY - cy) * follow
                vx = 0f
                vy = 0f
            }

            CapsuleMotionPhase.Free -> {
                val speed = hypot(vx, vy)
                // 速度越低阻力越大：高速甩得出去，低速黏住快停。
                val drag = CapsuleFlingPhysics.SlowDrag +
                    (CapsuleFlingPhysics.FastDrag - CapsuleFlingPhysics.SlowDrag) *
                    min(1f, speed / fastDragSpeed)
                val decay = exp(-drag * dt)
                vx *= decay
                vy *= decay
                cx += vx * dt
                cy += vy * dt
                if (cx < bounds.minCenterX || cx > bounds.maxCenterX ||
                    cy < bounds.minCenterY || cy > bounds.maxCenterY
                ) {
                    // 只要有一点点出屏，回拉弹簧立刻介入，带着余速连续拉回（中途不停顿）。
                    motion.dockEdge = nearestEdge(cx, cy, vp)
                    phase = CapsuleMotionPhase.Return
                    phaseStartNanos = frameNanos
                } else if (hypot(vx, vy) < stopVelocity) {
                    // 屏内低速：不清零速度，交给弹簧带着余速平滑减速。
                    settleX = cx.coerceIn(bounds.minCenterX, bounds.maxCenterX)
                    settleY = cy.coerceIn(bounds.minCenterY, bounds.maxCenterY)
                    phase = CapsuleMotionPhase.Settle
                    phaseStartNanos = frameNanos
                }
            }

            CapsuleMotionPhase.Settle -> {
                vx += ((settleX - cx) * CapsuleFlingPhysics.SpringStiffness - vx * CapsuleFlingPhysics.SpringDamping) * dt
                vy += ((settleY - cy) * CapsuleFlingPhysics.SpringStiffness - vy * CapsuleFlingPhysics.SpringDamping) * dt
                cx += vx * dt
                cy += vy * dt
                if ((abs(settleX - cx) < settleRestDistance &&
                        abs(settleY - cy) < settleRestDistance &&
                        hypot(vx, vy) < settleRestSpeed) ||
                    frameNanos - phaseStartNanos > settleTimeoutNanos
                ) {
                    cx = settleX
                    cy = settleY
                    vx = 0f
                    vy = 0f
                    phase = CapsuleMotionPhase.Rest
                }
            }

            CapsuleMotionPhase.Return -> {
                // 贴边目标每帧按当前尺寸重算：形变进行中贴合面在移动，目标跟着走，停靠不跳变。
                val horizontal = motion.dockEdge == CapsuleEdge.Left || motion.dockEdge == CapsuleEdge.Right
                val tx = dockTargetX(cx, bounds, motion.dockEdge)
                val ty = dockTargetY(cy, bounds, motion.dockEdge)
                // 弹簧只沿这条边的垂向回拉；切向不受弹簧力，仅受飞行阻力，可沿边缘滑动。
                if (horizontal) {
                    vx += ((tx - cx) * CapsuleFlingPhysics.SpringStiffness -
                        vx * CapsuleFlingPhysics.SpringDamping) * dt
                } else {
                    vy += ((ty - cy) * CapsuleFlingPhysics.SpringStiffness -
                        vy * CapsuleFlingPhysics.SpringDamping) * dt
                }
                val speed = hypot(vx, vy)
                val decay = exp(
                    -(CapsuleFlingPhysics.SlowDrag +
                        (CapsuleFlingPhysics.FastDrag - CapsuleFlingPhysics.SlowDrag) *
                        min(1f, speed / fastDragSpeed)) * dt
                )
                if (horizontal) vy *= decay else vx *= decay
                val speedAfterDrag = hypot(vx, vy)
                if (speedAfterDrag > returnMaxVelocity) {
                    val factor = returnMaxVelocity / speedAfterDrag
                    vx *= factor
                    vy *= factor
                }
                cx += vx * dt
                cy += vy * dt
                // 切向顶到相邻的边就按住，不戳出去（可停在角落）。
                if (horizontal) {
                    cy = cy.coerceIn(bounds.minCenterY, bounds.maxCenterY)
                } else {
                    cx = cx.coerceIn(bounds.minCenterX, bounds.maxCenterX)
                }
                val normalOk = if (horizontal) {
                    abs(tx - cx) < dockNormalTolerance
                } else {
                    abs(ty - cy) < dockNormalTolerance
                }
                if ((normalOk && speedAfterDrag < dockRestSpeed) ||
                    frameNanos - phaseStartNanos > returnTimeoutNanos
                ) {
                    if (horizontal) {
                        cx = tx
                        cy = cy.coerceIn(bounds.minCenterY, bounds.maxCenterY)
                    } else {
                        cy = ty
                        cx = cx.coerceIn(bounds.minCenterX, bounds.maxCenterX)
                    }
                    vx = 0f
                    vy = 0f
                    phase = CapsuleMotionPhase.Docked
                }
            }

            CapsuleMotionPhase.Rest, CapsuleMotionPhase.Docked -> Unit
        }

        // ③ 形变：宽度只在「甩出缩球」这一段由本循环推进（与物理同一个 dt、同一帧、同一顺序）；
        //    展开/收起交给组合侧的 320ms tween，两条通道互斥，避免同帧两个写入方互相覆盖。
        //    高度不在这里推进——它由 Compose 的展开/收起动画驱动，本循环只读实测尺寸。
        if (motion.morphWidth) {
            val mk = 1f - exp(-CapsuleFlingPhysics.MorphRatePerSecond * dt)
            val currentDp = widthDp.value
            if (motion.widthTargetDp != currentDp) {
                widthDp.snapTo(currentDp + (motion.widthTargetDp - currentDp) * mk)
                // 本帧就用 morph 后的宽度参与约束（实测值要到下一轮布局才跟上来），
                // 否则贴边目标永远慢一帧，缩球收尾会看到一下横向抽动。
                liveWidthPx = widthDp.value * scale
            }
        }

        // ④ 约束：docked 用当前尺寸重算贴边；rest 用当前尺寸夹回可用区。
        //    参考实现 frame() 里 morph 之后就是这两句，形变与贴合因此同帧收敛、不跳变。
        updateMotionBounds(vp, liveWidthPx, liveHeightPx, bounds)
        when (phase) {
            CapsuleMotionPhase.Docked -> {
                cx = dockTargetX(cx, bounds, motion.dockEdge)
                cy = dockTargetY(cy, bounds, motion.dockEdge)
            }

            CapsuleMotionPhase.Rest -> {
                cx = cx.coerceIn(bounds.minCenterX, bounds.maxCenterX)
                cy = cy.coerceIn(bounds.minCenterY, bounds.maxCenterY)
            }

            else -> Unit
        }
        // 顶边锚逐帧跟随：飞行段结束时锚点就是当前顶边，切到静止/停靠后尺寸涨缩不再跳位。
        motion.anchorTopY = cy - liveHeightPx / 2f

        // ⑤ 落定判定：尺寸动画不再推进实测、物理已静止、手指已抬起 → 收口、下发、休眠。
        //    宽度收敛只对「甩出缩球」这一通道有意义（本循环自己 snapTo 的那条）；
        //    展开/收起的宽度由组合侧 tween 负责，本循环只看实测什么时候停下来。
        //    按住期间永不落定（参考实现 pointerdown 后一直停在 drag 相位），下一次 MOVE 继续积分。
        val widthSettled = !motion.morphWidth ||
            abs(motion.widthTargetDp - widthDp.value) <= 0.05f
        val sizeSettled = stableSizeFrames >= CapsuleFlingPhysics.SizeStableFramesToSettle
        val settled = widthSettled && sizeSettled && !motion.pointerDown &&
            (phase == CapsuleMotionPhase.Drag ||
                phase == CapsuleMotionPhase.Rest ||
                phase == CapsuleMotionPhase.Docked)
        if (settled) {
            // 手势作废留下的 drag 相位（无甩出计划）收口成静止，位置已按实时尺寸夹好。
            if (phase == CapsuleMotionPhase.Drag) phase = CapsuleMotionPhase.Rest
            if (motion.morphWidth && motion.widthTargetDp != widthDp.value) {
                widthDp.snapTo(motion.widthTargetDp)
                liveWidthPx = widthDp.value * scale
            }
            updateMotionBounds(vp, liveWidthPx, liveHeightPx, bounds)
            if (phase == CapsuleMotionPhase.Docked) {
                cx = dockTargetX(cx, bounds, motion.dockEdge)
                cy = dockTargetY(cy, bounds, motion.dockEdge)
            } else if (phase == CapsuleMotionPhase.Rest) {
                cx = cx.coerceIn(bounds.minCenterX, bounds.maxCenterX)
                cy = cy.coerceIn(bounds.minCenterY, bounds.maxCenterY)
            }
            motion.anchorTopY = cy - liveHeightPx / 2f
            commit()
            emit(force = true)
            onSettled()
            return
        }

        commit()
        emit(force = false)
    }
}

/** 宿主 overlayBounds 的同源口径：显示边界扣除系统栏与挖孔（忽略可见性，避免瞬态 inset 抖动）。 */
internal fun capsuleViewport(view: View): Rect {
    val manager = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    val metrics = manager?.let { runCatching { it.currentWindowMetrics }.getOrNull() }
    if (metrics != null) {
        val bounds = metrics.bounds
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        return Rect(
            (bounds.left + insets.left).toFloat(),
            (bounds.top + insets.top).toFloat(),
            (bounds.right - insets.right).toFloat(),
            (bounds.bottom - insets.bottom).toFloat(),
        )
    }
    val metricsFallback = view.resources.displayMetrics
    return Rect(0f, 0f, metricsFallback.widthPixels.toFloat(), metricsFallback.heightPixels.toFloat())
}

/**
 * 拖拽速度采样：保留最近一小段带时间戳的指针位置，抬手时用尾部窗口最小二乘拟合斜率。
 * 相比平台 VelocityTracker 的固定窗口，这里先判停顿（末段无新采样）再拟合，
 * 轻放不会因历史速度继续乱飞。窗口与停顿阈值与参考实现一致（110ms / 90ms）。
 */
internal class CapsuleDragVelocitySampler {
    private val stamps = LongArray(Capacity)
    private val xs = FloatArray(Capacity)
    private val ys = FloatArray(Capacity)
    private var size = 0
    private var lastStamp = 0L

    fun reset() {
        size = 0
        lastStamp = 0L
    }

    fun add(event: MotionEvent) {
        val stamp = event.eventTime
        val x = event.rawX
        val y = event.rawY
        if (!x.isFinite() || !y.isFinite() || stamp <= 0L) return
        // 同一毫秒的重复事件不增采样点：时间方差为 0 会污染最小二乘。
        if (size > 0 && stamp <= lastStamp) {
            val last = size - 1
            stamps[last] = stamp
            xs[last] = x
            ys[last] = y
            lastStamp = stamp
            return
        }
        if (size == Capacity) {
            stamps.copyInto(stamps, 0, 1, size)
            xs.copyInto(xs, 0, 1, size)
            ys.copyInto(ys, 0, 1, size)
            size--
        }
        stamps[size] = stamp
        xs[size] = x
        ys[size] = y
        size++
        lastStamp = stamp
    }

    /** @param upTime ACTION_UP 的 eventTime；末次移动距它超过停顿阈值时返回 0。 */
    fun estimate(upTime: Long): Offset {
        if (size < 2) return Offset.Zero
        if (upTime - lastStamp > CapsuleFlingPhysics.VelocityStallMillis) return Offset.Zero
        val windowStart = stamps[size - 1] - CapsuleFlingPhysics.VelocityWindowMillis
        var first = size - 1
        while (first > 0 && stamps[first - 1] >= windowStart) first--
        val count = size - first
        if (count < 2) return Offset.Zero
        val origin = stamps[first]
        var sumT = 0.0
        var sumX = 0.0
        var sumY = 0.0
        var sumTT = 0.0
        var sumTX = 0.0
        var sumTY = 0.0
        for (index in first until size) {
            val time = (stamps[index] - origin).toDouble()
            sumT += time
            sumX += xs[index].toDouble()
            sumY += ys[index].toDouble()
            sumTT += time * time
            sumTX += time * xs[index].toDouble()
            sumTY += time * ys[index].toDouble()
        }
        val denominator = count * sumTT - sumT * sumT
        if (denominator < 1e-3) return Offset.Zero
        val velocityX = ((count * sumTX - sumT * sumX) / denominator * 1000.0).toFloat()
        val velocityY = ((count * sumTY - sumT * sumY) / denominator * 1000.0).toFloat()
        if (!velocityX.isFinite() || !velocityY.isFinite()) return Offset.Zero
        return Offset(velocityX, velocityY)
    }

    private companion object {
        /** 24 × 120Hz ≈ 200ms，足够覆盖 110ms 拟合窗口并留历史余量。 */
        const val Capacity = 24
    }
}
