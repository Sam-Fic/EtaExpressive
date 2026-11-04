package io.github.mangi.eta.ui.adaptive

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

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
     * 导航转场时长：300ms。
     *
     * 页面层级仍按原来的定长 tween 走（新页整屏推入、旧页 1/4 视差淡到 0.9）。
     * 这个值是单一来源：导航转场的动画时长、导航活跃采样窗口
     * （`NAVIGATION_TRANSITION_WINDOW_NS`）、以及 [drawerNavExitMillis] 的基数都从这里取。
     * 同时它也是弹簧规格无法解析时的兜底采样时长。
     */
    const val NavigationFallbackDurationMillis = 300L

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
     * 导航离场时侧栏跟随退出的时长：导航转场时长 + 两帧余量（300 + 32 = 332ms）。
     *
     * 取「不短于页面推入」是关键约束——侧栏若比新页先收完，新旧之间会露出空档；
     * 只要新页先盖住侧栏，侧栏收得再慢也不会被看见。基数直接复用
     * [NavigationFallbackDurationMillis]，不另写魔数，导航改时长时侧栏自动跟上。
     */
    fun drawerNavExitMillis(): Long = NavigationFallbackDurationMillis + FrameMarginMillis

    /** 转场收尾的两帧余量：让跟随动作比主转场多撑两帧，避免卡在最后一帧的尾迹。 */
    const val FrameMarginMillis = 32L
}
