package io.github.mangi.eta.ui.adaptive

import androidx.compose.ui.unit.dp

/**
 * L1 设计令牌：间距 / 尺寸 / 笔画。
 *
 * 官方已给出的尺寸一律优先用官方令牌（`IconButtonDefaults.extraSmallIconSize`、
 * `ButtonDefaults.IconSize`、`ProgressIndicatorDefaults.CircularStrokeWidth`…），
 * 本文件只收官方没有、但项目内反复出现的值。新代码从这里取值，不要在文件里
 * 再定义私有尺寸常量；存量字面量渐进收敛，不做一次性全量替换。
 */
object EtaDimens {

    // ── 间距阶（4 的倍数；官方组件内部间距不在此列，跟随官方默认） ──

    /** 图标与相邻文字的最小间隙（状态标记、图标 + 文本行）。 */
    val SpaceXs = 4.dp

    /** 行内元素间距（图标 + 文本、chip 间隙）。 */
    val SpaceSm = 8.dp

    /** 卡片内段落间距。 */
    val SpaceMd = 12.dp

    /** 卡片水平外边距、展开体内容列内边距。 */
    val SpaceLg = 16.dp

    /**
     * 行尾槽内多个元素之间的间距：开关 ↔ 展开箭头、状态文字 ↔ 箭头、徽标 ↔ 箭头等。
     *
     * 官方 ListItem 只规定「正文列 ↔ 行尾槽」的槽间距，不规定尾槽内部怎么排；
     * 这里统一取 12dp，与官方 `ItemBetweenSpace` 的中等节奏一致。
     *
     * 尾槽内的排列顺序同样统一：**开关永远最右**，展开/前进箭头、动作按钮、徽标、
     * 状态文字都在它左侧（有开关时箭头在开关左侧；没有开关时箭头才占最右）。
     * 这样「主开关 + 可展开子弹关行」的开关能纵向对齐，不会在同卡片内错位 36dp。
     */
    val TrailingElementGap = 12.dp

    // ── 尺寸 / 笔画 ──

    /**
     * 行尾箭头（静态导航 / 展开收起）的标准图标尺寸。
     *
     * 与官方 ListItem 行尾图标的 24dp 对齐；需要沿用官方控件自身尺寸的调用点
     * （如 AssistChip 的 18dp trailingIcon）显式传 `size` 覆盖。
     */
    val TrailingChevronSize = 24.dp

    /** 状态标记图标（工具行、朗读按钮等 16dp 状态槽）。 */
    val StatusIconSize = 16.dp

    /** 行内加载槽（预览占位、压缩行、共享文件夹行等 18dp）。 */
    val InlineLoadingSize = 18.dp

    /** 行首动作槽里的加载指示器（备份 / 技能等 24dp 行首动作位）。 */
    val RowLeadingLoadingSize = 24.dp

    /** 块级加载容器的默认 morph 尺寸（28dp，与 `ContainedMorphLoadingIndicator` 默认值同源）。 */
    val BlockLoadingSize = 28.dp

    /** 会话行运行状态圆点（会话列表「运行中 / 已完成」标记）。 */
    val StatusDotSize = 6.dp

    /** 远程连接状态圆点（侧栏远程行、远程服务器管理页），比运行状态点大一级。 */
    val RemoteStatusDotSize = 10.dp

    /**
     * 极小标记图标（12dp）：仅用于纯装饰性的点状/短划元素。
     *
     * 官方未给出该尺寸；12dp 低于 M3 最小图标尺寸（18dp），
     * 因此只用于不承载语义、不参与点击目标计算的装饰元素。
     */
    val MicroIconSize = 12.dp

    /**
     * 密集布局图标（20dp）：桌面端、高密度列表、紧凑设置页。
     *
     * 官方系统图标规范给出的密集模式尺寸为 20dp，但 Compose 未暴露对应令牌，
     * 故在此定义。触摸目标仍由 `minimumInteractiveComponentSize()` 保证 48dp。
     */
    val DenseIconSize = 20.dp

    /**
     * 空状态提示图标（36dp）：空态里压在主图形下方的次级提示图标。
     *
     * 官方未给出该尺寸；与 FAB 大号图标同值但语义不同，故单独定义。
     */
    val EmptyStateHintIconSize = 36.dp

    /**
     * 空状态主图形（72dp）：空状态页面中央的主插图位图标。
     *
     * 官方未给出该尺寸。空状态的装饰形状底板另有 [EmptyStateShapeSize]。
     */
    val EmptyStateGraphicIconSize = 72.dp

    /** 小号确定进度环笔画（输入区上下文用量环、朗读按钮 spinner 同款）。 */
    val SmallRingStroke = 2.5.dp

    /** 空态装饰形状（M3E 形状）的边长：图标坐在上面，形状在下缘露出一截。 */
    val EmptyStateShapeSize = 128.dp

    /** 虚拟屏舞台在窄屏下的最大高度：再高就会把状态/控制分组挤出首屏。 */
    val VirtualDisplayStageMaxHeight = 480.dp

    // ── 圆角 ──
    //
    // 同心圆角规则：外层容器圆角 = 内层圆角 + 两层之间的间距（外圆 = 内圆 + 间距），
    // 嵌套时据此递推，避免内层「穿墙」或缝隙不均。
    //
    // 圆角 token 沿用 KernelSU 的就近归属惯例，不集中搬进本文件：
    // - 分段列表：EtaSegmentedOuterCornerRadius 16 / 内角 4 / 组内间距 2（EtaSegmentedList.kt）
    // - 连接式按钮组：EtaConnectedInnerCornerRadius 8 / 按压态 4（EtaExpressiveKit.kt）
    // - 聊天域：用户气泡 24（ChatMessageItem）、输入条组合 36（AgentChatComposerM3）、
    //   文件 chip 12/接缝 4（AgentChatFileAttachments）等，各自在文件顶部命名私藏
    // - 语音浮窗：面板顶角 28（EtaVoicePanel，同 BottomSheetDefaults）、屏幕上下文预览卡
    //   15 / 内图 11（同心，差 = 卡片内边距 4，EtaVoicePanel 顶部派生）
    // 新圆角先对照上面找同语义值；确实全新才就近命名，不新增一次性字面量。
}
