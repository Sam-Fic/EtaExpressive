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
     * 导航转场时长：500ms（miuix `NavTransitions.MiuixDefault` 的程序化转场时长）。
     *
     * 页面转场改由 miuix NavDisplay 驱动：整屏滑入 + 系统圆角裁切 + 轻微压暗，
     * 程序化转场是定长 `tween(500, NavProgrammaticEasing)`，收尾曲线为 `NavSettleEasing`。
     * 这个值是单一来源：导航活跃信号的兜底窗口、以及 [drawerNavExitMillis] 的基数都从这里取。
     */
    const val NavigationFallbackDurationMillis = 500L

    // ------------------------------------------------------------------ 加载

    /**
     * 加载指示器显示延迟：120ms。
     *
     * 本地解码、本地目录/统计首载这类操作常在几十毫秒内完成；立刻挂指示器会闪一下。
     * 超过该阈值才认为“用户真的在等”，由 `EtaLoadingGate` 统一消费。
     */
    const val LoadingShowDelayMillis = 120L

    /**
     * 指示器最短可见时长：400ms。
     *
     * 一旦显示，就不要在下一帧又撤掉，否则闪烁比不显示更刺眼。
     * 失败 / 出错路径例外：错误反馈要立刻跟进（错误气泡、Toast 等），
     * 指示器再滞留 400ms 会让「明明已经失败却还在转」的错觉盖过错误本身，
     * 见 `rememberEtaLoadingShown` 的 `failVisible` 参数。
     */
    const val LoadingMinVisibleMillis = 400L

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
     * 导航离场时侧栏跟随退出的时长：导航转场时长 + 两帧余量（500 + 32 = 532ms）。
     *
     * 取「不短于页面推入」是关键约束——侧栏若比新页先收完，新旧之间会露出空档；
     * 只要新页先盖住侧栏，侧栏收得再慢也不会被看见。基数直接复用
     * [NavigationFallbackDurationMillis]，不另写魔数，导航改时长时侧栏自动跟上。
     */
    fun drawerNavExitMillis(): Long = NavigationFallbackDurationMillis + FrameMarginMillis

    /** 转场收尾的两帧余量：让跟随动作比主转场多撑两帧，避免卡在最后一帧的尾迹。 */
    const val FrameMarginMillis = 32L

    // ------------------------------------------------------------ 浮层胶囊

    /**
     * 虚拟屏胶囊展开 / 收起的统一时长：320ms。
     *
     * 胶囊展开由「宽度、圆角、展开进度、正文高度、微缩镜↔画布共享元素边界」五层同时
     * 驱动（共享元素的 boundsTransform 也取本值，见 AgentOverlayCapsule）。改造时曾把
     * 每一层都换成官方 Expressive spatial 弹簧（0.8 / 380），结果各层各自带可感知的
     * 过冲与长尾、还要叠在窗口逐帧 resize 上，合起来像慢动作；而改造前这几层是
     * NoBouncy / MediumLow（约 350–400ms 收尾）或定长 tween，观感明显更利落。
     *
     * 所以这里保留定长 tween（配 FastOutSlowIn，与侧栏整块平移同族曲线：起步快、收尾缓、
     * 无过冲），各层共用一个值，保证「宽度胀开」与「正文浮出」在同一条时间轴上收尾。
     * 上限 350ms 是有意约束：再长就会重新出现「先胀开、内容随后慢慢浮出来」的串联感。
     */
    const val CapsuleExpandDurationMillis = 320

    /**
     * 胶囊窗口「早涨晚缩」的落定判定静默期：150ms。
     *
     * 窗口不再逐帧跟随动画（每帧 resize 会引发 surface 缓冲整体重分配，掉帧成慢动作），
     * 变小方向要等测量值停止变化满本静默期才收拢窗口。太短：动画仍在逐帧改布局时误判
     * 落定、把窗口收小裁掉内容；太长：morph 结束后大窗滞留，透明边多吃触摸。
     * 动画中测量每帧都在变（16ms 间隔），远不满静默期；落定后再等 150ms 即收。
     */
    const val CapsuleWindowSettleQuietMillis = 150L
}
