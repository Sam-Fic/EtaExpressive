package io.github.mangi.eta.ui.adaptive

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 全局动效令牌：官方 `MotionScheme` 之外的「项目自有时间轴」单一出处。
 *
 * 官方 Expressive 规格（`MaterialTheme.motionScheme` 的 spatial / effects 弹簧）一律优先，
 * **不要在这里复制一份**。这里只收官方没有、但项目里已存在且必须保持的定长与自定曲线值，
 * 每条都写清为什么不能直接用官方规格。
 *
 * 改动这里的任何取值都会直接改变观感，属于设计决策而非实现细节。
 */
object EtaMotion {

    // ------------------------------------------------------------------ 导航

    /**
     * 导航转场的兜底采样窗口。
     *
     * 实际转场动画已改为 `defaultSpatialSpec`（弹簧没有固定时长），所以这个值只在
     * 规格无法解析为 `SpringSpec` 时使用（见 ChatNavigationActivity）。
     * 300ms 是改造前定长 tween 的时长，作为兜底下限保留。
     */
    const val NavigationFallbackDurationMillis = 300

    /**
     * 被覆盖页面的视差位移占整屏宽度的比例。
     *
     * 用 1/4 而不是整屏：新页推入时旧页只轻轻跟随，既表达「新页盖在上面」，
     * 又不会让整屏内容大幅横移（大幅横移会在大字号/长文本页面上产生明显重排观感）。
     */
    const val NavigationParallaxFraction = 0.25f

    /**
     * 被覆盖页面淡出的目标透明度：保留 10%。
     *
     * 不完全淡出——完全淡出会让旧页在过渡中变成一块空白，丢失「它还在下面」的层级关系。
     */
    const val NavigationExitTargetAlpha = 0.9f

    // ------------------------------------------------------------------ 侧栏

    /**
     * 侧栏程序化展开时长：220ms。
     *
     * 官方 `DrawerState` 的程序化开合同样是定长 tween（而非 spatial 弹簧），
     * 所以这里保持 tween；值略长于关闭，让「拉开」显得从容。
     */
    const val DrawerOpenDurationMillis = 220

    /**
     * 侧栏程序化收起时长：200ms。
     *
     * 比展开短 20ms：「收起」是让位动作，快一点更利落。
     * 手势松手不走这条规格（见 `DrawerSettleSpringSpec`，那条按甩动速度自适应）。
     */
    const val DrawerCloseDurationMillis = 200

    /**
     * 侧栏整块平移的缓动曲线。
     *
     * 与官方 emphasized easing 同值（0.2, 0, 0, 1）：起步快、收尾缓。
     * 整块全高面板的位移不适合用弹簧——弹簧过冲会让侧栏越过展开锚点后再弹回，
     * 在贴边面板上会露出底色缝隙。
     */
    val DrawerEasing: Easing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

    /**
     * 导航离场时侧栏跟随退出的时长：与导航弹簧的收敛时间对齐后再留两帧余量。
     *
     * 取「不短于页面推入」是关键约束——侧栏若比新页先收完，新旧之间会露出空档；
     * 只要新页先盖住侧栏，侧栏收得再慢也不会被看见。因此这里直接用
     * [springSettleMillis] 从同一个弹簧推出来，而不是另写一个魔数。
     */
    fun drawerNavExitMillis(navigationSpec: FiniteAnimationSpec<*>): Long =
        springSettleMillis(navigationSpec) + FrameMarginMillis

    /** 弹簧收敛时间估算时的两帧余量，避免卡在最后一帧的尾迹。 */
    const val FrameMarginMillis = 32L

    /**
     * 估算弹簧「收敛到 [tolerance] 误差」所需的时间（毫秒）。
     *
     * 弹簧没有 duration，转场采样窗口、侧栏跟随收起时长都必须按实际动力学推，
     * 否则换 spec 后写死的时长会立刻失配（采样提前 → 页面被误判为静止）。
     *
     * 欠阻尼弹簧的包络按 `e^(-ζωt)` 衰减，ω = √stiffness，
     * 因此 t = -ln(tolerance) / (ζω)。非弹簧规格回退到 [NavigationFallbackDurationMillis]。
     */
    fun springSettleMillis(
        spec: FiniteAnimationSpec<*>,
        tolerance: Double = 1e-3,
    ): Long {
        val spring = spec as? SpringSpec<*> ?: return NavigationFallbackDurationMillis
        val omega = sqrt(spring.stiffness.toDouble())
        val decay = spring.dampingRatio.toDouble() * omega
        if (decay <= 0.0) return NavigationFallbackDurationMillis
        return (-ln(tolerance) / decay * 1000.0).toLong()
    }
}
