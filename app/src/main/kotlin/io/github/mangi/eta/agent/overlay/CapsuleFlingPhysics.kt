package io.github.mangi.eta.agent.overlay

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import kotlin.math.min
import kotlinx.coroutines.Job

/**
 * 松手甩出参数。全部单位是物理像素 / 秒（比例与 Compose 弹簧的原生量纲一致）。
 *
 * 位置坐标约定：x = 窗口水平中心，y = 窗口顶边。这样缩球形变时窗口宽度
 * 逐帧收窄，中心不动，贴边目标不会随形变漂移。
 */
internal object CapsuleFlingPhysics {
    /** 抬手速度取样窗口：只拟合最近 120ms（120Hz 触摸约 14 个点，60Hz 约 7 个点）。 */
    const val VelocityWindowMillis = 120L

    /** 抬手前超过 60ms 没有新采样即视为停顿：按 0 速度原位吸附，不甩出。 */
    const val VelocityStallMillis = 60L

    /** 低于 400px/s（约 142dp/s @2.8125）的抬手算轻放：不滑行，只做越界回弹。 */
    const val MinFlingVelocityPxPerSec = 400f

    /**
     * 自由滑行投影时长：projected = 起点 + v × 0.14s。
     * 0.24s 时轻拖 800px/s 也能滑出近 200px，实机表现为“轻轻一拖就被甩很远”；
     * 0.14s 把同速度的滑行距离砍掉约 42%，中速只滑一小段、真甩才够到边缘。
     */
    const val GlideSeconds = 0.14f

    /**
     * 飞行段弹簧：dampingRatio 0.90（接近临界，几乎不产生额外过冲），stiffness 520。
     * ω≈22.8rad/s，约 0.25s 收尾；速度只决定目标距离，不再叠加 14% 过冲行程，
     * 实机手感是“甩出去后沉下去、没有第二段滑行”，越界全靠目标落点本身。
     */
    const val FlightDampingRatio = 0.90f
    const val FlightStiffness = 520f

    /**
     * 边界回弹段弹簧保持 dampingRatio 0.50（明显欠阻尼，约 16% 过冲），stiffness 520。
     * 从屏外/越界位置拉回最近边缘内侧，过冲表现为贴边处的一次“拐”回弹；
     * 这里不跟着调沉——用户认可贴边这一下，调沉会把“拐”抹平。
     */
    const val EdgeDampingRatio = 0.50f
    const val EdgeStiffness = 520f

    /** 两段弹簧共用的落定阈值：半像素以下即视为停住，避免亚像素长尾拖时间。 */
    const val RestThresholdPx = 0.5f

    /**
     * 越界上限 = min(窗口尺寸 × 1.2, 屏幕宽 × 0.9 / 屏幕高 × 0.35)。
     * 横向 1.2 倍窗口宽保证小尺寸缩球能“完整出屏再回来”；
     * 再用屏幕比例压住极端情况，避免大面板远飞、出屏不可见时间过长。
     */
    const val OvershootWindowFactor = 1.2f
    const val OvershootScreenFactorX = 0.9f
    const val OvershootScreenFactorY = 0.35f
}

/** 一次甩出的运行时句柄：job 用于“按下即打断”，center 记录最后一帧落点（配置变化时交还宿主）。 */
internal class CapsuleFlingSession {
    var job: Job? = null
    var center: Offset = Offset.Zero
}

/**
 * 松手瞬间由宿主结算的甩出计划。
 *
 * @property center 松手时窗口的水平中心 + 顶边（物理屏幕坐标，允许已在界外）
 * @property footprint 甩出期间用于边界计算的窗口占位；缩球时取球窗（80×68dp），
 *   否则取当前胶囊窗尺寸（含透明外衬）
 * @property velocity 已按系统 fling 上限钳过的松手速度（px/s）
 */
internal data class CapsuleFlingPlan(
    val center: Offset,
    val footprint: IntSize,
    val velocity: Offset,
)

/** 窗口中心 x / 顶边 y 的合法区间（内边距即宿主 overlayBounds 已扣掉的系统栏 + 挖孔安全区）。 */
internal data class CapsuleFlingBounds(
    val minCenterX: Float,
    val maxCenterX: Float,
    val minTopY: Float,
    val maxTopY: Float,
)

/**
 * 把窗口占位换算成「中心 x / 顶边 y」的贴边区间。
 * y 用顶边而不是中心，与宿主 `capsuleTopY` 的坐标系一致（高度变化时底部自然贴边）。
 */
internal fun capsuleFlingBounds(viewport: Rect, footprint: IntSize): CapsuleFlingBounds {
    val halfWidth = footprint.width / 2f
    val minCenterX = viewport.left + halfWidth
    val maxCenterX = (viewport.right - halfWidth).coerceAtLeast(minCenterX)
    val minTopY = viewport.top
    val maxTopY = (viewport.bottom - footprint.height).coerceAtLeast(minTopY)
    return CapsuleFlingBounds(minCenterX, maxCenterX, minTopY, maxTopY)
}

/**
 * 按松手速度给出自由滑行落点：投影 = 起点 + v × GlideSeconds，再钳到「边界 ± 越界上限」。
 * 落点允许在屏外，所以飞行段可以真的把窗口带出屏幕；回弹段再把它拉回最近边缘。
 */
internal fun capsuleFlingLanding(
    start: Offset,
    velocity: Offset,
    bounds: CapsuleFlingBounds,
    viewport: Rect,
    footprint: IntSize,
): Offset {
    val speed = hypot(velocity.x.toDouble(), velocity.y.toDouble()).toFloat()
    val launch = if (speed < CapsuleFlingPhysics.MinFlingVelocityPxPerSec) Offset.Zero else velocity
    val projected = Offset(
        start.x + launch.x * CapsuleFlingPhysics.GlideSeconds,
        start.y + launch.y * CapsuleFlingPhysics.GlideSeconds,
    )
    val capX = min(
        footprint.width * CapsuleFlingPhysics.OvershootWindowFactor,
        viewport.width * CapsuleFlingPhysics.OvershootScreenFactorX,
    )
    val capY = min(
        footprint.height * CapsuleFlingPhysics.OvershootWindowFactor,
        viewport.height * CapsuleFlingPhysics.OvershootScreenFactorY,
    )
    return Offset(
        projected.x.coerceIn(bounds.minCenterX - capX, bounds.maxCenterX + capX),
        projected.y.coerceIn(bounds.minTopY - capY, bounds.maxTopY + capY),
    )
}

/** 是否越出贴边区间（带半像素容差，避免浮点尾数反复触发回弹）。 */
internal fun capsuleOutsideBounds(position: Offset, bounds: CapsuleFlingBounds): Boolean =
    position.x < bounds.minCenterX - CapsuleFlingPhysics.RestThresholdPx ||
        position.x > bounds.maxCenterX + CapsuleFlingPhysics.RestThresholdPx ||
        position.y < bounds.minTopY - CapsuleFlingPhysics.RestThresholdPx ||
        position.y > bounds.maxTopY + CapsuleFlingPhysics.RestThresholdPx

/** 最近的可停靠点：越界轴贴最近边缘，未越界轴保持原位（即“拐回最近的屏幕边缘”）。 */
internal fun capsuleNearestInside(position: Offset, bounds: CapsuleFlingBounds): Offset =
    Offset(
        position.x.coerceIn(bounds.minCenterX, bounds.maxCenterX),
        position.y.coerceIn(bounds.minTopY, bounds.maxTopY),
    )

/**
 * 甩出动画本体：一个 [Animatable] 依次跑两段真弹簧。
 *
 * 1. 飞行段：目标 = 速度投影落点（可越界），初速度 = 松手速度，欠阻尼；
 * 2. 回弹段：飞行落点越界时，以当前速度接一条更欠阻尼的边界弹簧，拉回最近边缘内侧。
 *
 * 两段之间只有弹簧换参数，没有 tween、没有硬停：位置连续、速度由 Animatable 自身接管。
 * [viewport] 是闭包而非快照值——回弹段重新取一次，旋转/inset 变化后按新边界收尾。
 */
internal suspend fun runCapsuleFling(
    plan: CapsuleFlingPlan,
    viewport: () -> Rect,
    onStep: (Offset) -> Unit,
) {
    val initialViewport = viewport()
    val bounds = capsuleFlingBounds(initialViewport, plan.footprint)
    val landing = capsuleFlingLanding(plan.center, plan.velocity, bounds, initialViewport, plan.footprint)
    val animatable = Animatable(plan.center, Offset.VectorConverter)
    val threshold = Offset(CapsuleFlingPhysics.RestThresholdPx, CapsuleFlingPhysics.RestThresholdPx)

    if ((landing - plan.center).getDistance() > CapsuleFlingPhysics.RestThresholdPx) {
        animatable.animateTo(
            targetValue = landing,
            animationSpec = spring(
                dampingRatio = CapsuleFlingPhysics.FlightDampingRatio,
                stiffness = CapsuleFlingPhysics.FlightStiffness,
                visibilityThreshold = threshold,
            ),
            initialVelocity = plan.velocity,
        ) {
            onStep(value)
        }
    }

    onStep(animatable.value)
    val settleBounds = capsuleFlingBounds(viewport(), plan.footprint)
    if (capsuleOutsideBounds(animatable.value, settleBounds)) {
        animatable.animateTo(
            targetValue = capsuleNearestInside(animatable.value, settleBounds),
            animationSpec = spring(
                dampingRatio = CapsuleFlingPhysics.EdgeDampingRatio,
                stiffness = CapsuleFlingPhysics.EdgeStiffness,
                visibilityThreshold = threshold,
            ),
            initialVelocity = animatable.velocity,
        ) {
            onStep(value)
        }
    }
    onStep(animatable.value)
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
 * 轻放不会因历史速度继续乱飞。
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
        /** 24 × 120Hz ≈ 200ms，足够覆盖 120ms 拟合窗口并留历史余量。 */
        const val Capacity = 24
    }
}
