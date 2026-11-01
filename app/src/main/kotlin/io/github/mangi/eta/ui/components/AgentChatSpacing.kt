package io.github.mangi.eta.ui.components

import androidx.compose.ui.unit.dp

/**
 * 主对话流的垂直节奏常量。
 *
 * 对话列表没有任何 `Arrangement.spacedBy`：相邻两行的视觉间隙是两行各自
 * padding 之和。因此这里只存「行内的一侧」，注释里写清它参与合成的有效间隙；
 * 改任何一个值前先对照下表验算加法：
 *
 * | 边界 | 有效间隙 | 合成 |
 * |---|---|---|
 * | 用户气泡 → 模型开始回复（文字 / 工作组首行） | 16dp | 8 + 8 |
 * | 模型文字 ↔ 工作组 | 10dp | 8 + 2 |
 * | 工作组内露缝（摘要↔步骤、步骤↔步骤） | 2dp | 1 + 1 |
 * | 用户气泡组内缝（连续多条用户气泡） | 2dp | 1 + 1 |
 * | 模型文字 → 底部按钮组 | 10dp | 8 + 0 + 2 |
 * | 按钮组 → 下一条用户气泡（轮间） | 16dp | 8 + 8 |
 */
internal object AgentChatSpacing {

    /** 行水平外边距：用户气泡、模型文字、页脚按钮组三处行共用。 */
    val RowHorizontal = 16.dp

    /** 行基准垂直半距（行外側）：与相邻行的基准半距合成 16dp 的回复起点 / 轮间间隙。 */
    val RowBaseHalf = 8.dp

    /** 工作组行（摘要 / 步骤）的薄边：组外接内容，与文字行基准半距合成 10dp 轮内间隙。 */
    val WorkEdge = 2.dp

    /** 工作组内露缝的一半：1 + 1 = 2dp。 */
    val WorkSeamHalf = 1.dp

    /** 用户气泡组内缝的一半：1 + 1 = 2dp，缝距与附件行、设置页分段条目同语言。 */
    val BubbleGroupSeamHalf = 1.dp

    /**
     * 按钮行的内层上间隙。页脚路径的有效上间隙 = 文字行底 8 + 页脚外层 0 + 本值 = 10dp；
     * 内嵌路径（折叠步骤里的复制行）外层没有 padding，有效上间隙即本值。
     */
    val ActionRowTopGap = 2.dp

    /**
     * 页脚锚在工作组行（摘要 / 末步骤，薄边 2dp）下方时的上侧补偿：
     * 2 + 本值 + 内层 2 = 10dp，与锚在文字行下的有效上间隙对齐。
     */
    val FooterAfterWorkTopGap = 6.dp
}
