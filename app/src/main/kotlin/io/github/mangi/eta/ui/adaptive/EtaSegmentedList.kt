package io.github.mangi.eta.ui.adaptive

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.zIndex
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * KernelSU 式分段列表（Segmented list）：
 *
 * - 每组条目用官方 [ListItemDefaults.segmentedShapes] 做「外圆内方」的分段形状，
 *   首项上角大圆角、末项下角大圆角、中间项小圆角，条目间 2dp 露出背景缝隙；
 * - 容器底色走官方 `segmentedColors` 默认值，不再是整块大卡片；
 * - 组标题取官方 `titleSmallEmphasized` + primary 色，起点与列表条目对齐。
 */
val LocalEtaSegmentedShapes = staticCompositionLocalOf<ListItemShapes?> { null }

/**
 * 分段列表的几何令牌（与 KernelSU `SegmentedList.kt` 逐值一致）：
 * 组首/末项外圆角 16dp、中间项内圆角 4dp，条目间距 2dp，
 * 显隐与形状动画取官方 `MotionScheme` 的 defaultSpatialSpec（不再自带刚度）。
 */
internal val EtaSegmentedOuterCornerRadius = 16.dp
private val SegmentedOuterRadius = EtaSegmentedOuterCornerRadius
private val SegmentedInnerRadius = 4.dp
private val SegmentedEntryGap = 2.dp


/**
 * 分段形状：单条目组整体用 [MaterialTheme.shapes.large]（KernelSU `defaultSingleSegmentedShape`），
 * 多条目组直接取官方 `segmentedShapes`。
 */
@Composable
private fun defaultSegmentedShape(index: Int, count: Int): ListItemShapes {
    val base = ListItemDefaults.segmentedShapes(index, count)
    return if (count == 1) {
        // remember 锁住实例身份：官方 shapeForInteraction 以 shapes 实例为 key 保存
        // rememberAnimatedShape 状态，若每次重组都新建 shapes 实例，选中 morph 会被
        // 反复重置成硬跳。官方 segmentedShapes 内部已 remember（多条目组走 base 即可）。
        val shapes = MaterialTheme.shapes
        remember(base, shapes) { base.copy(shape = shapes.large) }
    } else {
        base
    }
}

/**
 * 是否位于 [EtaSegmentedItemContainer] 内部。为 true 时分段行不再自绘容器底色
 * （由外层容器统一提供），避免同色叠层出现接缝。
 */
val LocalEtaSegmentedRowBare = staticCompositionLocalOf { false }

/**
 * 分段行容器色的局部覆盖：只给「搜索命中后回到主页聚焦某一行」这类一次性高亮用。
 *
 * 默认 null（走官方配色），不传就与原来完全一致；传了颜色则启用/禁用两种状态都用它，
 * 保证高亮期间行的底色连续。
 */
val LocalEtaSegmentedRowContainerColor = compositionLocalOf<Color?> { null }

/**
 * 分段行内容色的局部覆盖：容器色被临时换成亮色（如宽屏设置左栏的常驻选中行）时，
 * 文字 / 图标 / 箭头必须跟着换成配套的 `onXxxContainer`，否则对比度会塌掉。
 *
 * 默认 null = 完全沿用官方 ListItem 令牌；容器色本身是动画中间色，不能从它反推内容色，
 * 所以调用方要显式给出目标内容色。
 */
val LocalEtaSegmentedRowContentColor = compositionLocalOf<Color?> { null }

/** 分段行的容器色：位于容器内时透明，否则用 surfaceBright 从页面背景浮出一档。 */
@Composable
internal fun etaSegmentedRowColors(
    selectedContainerColor: Color? = null,
    /** 调用方指定容器色（如展开态的高亮底色）；null 时走默认 surfaceBright。 */
    containerColorOverride: Color? = null,
): ListItemColors {
    if (LocalEtaSegmentedRowBare.current) {
        // 禁用态同样交出底色：容器在启用/禁用两种状态下都要保持表面连续，
        // 否则禁用行会用令牌色在容器上叠出一块异色。
        return ListItemDefaults.segmentedColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
        )
    }
    val containerColor = containerColorOverride
        ?: LocalEtaSegmentedRowContainerColor.current
        ?: MaterialTheme.colorScheme.surfaceBright
    // 只有显式指定底色时才推导配套内容色（tertiaryContainer → onTertiaryContainer），
    // 文字/图标/箭头与底色始终成对。LocalEtaSegmentedRowContainerColor 可能是动画中的
    // 中间色（如设置页搜索命中高亮），不能据此判定内容色；这类临时高亮由调用方通过
    // LocalEtaSegmentedRowContentColor 显式给目标内容色，其余情况保持原来的默认内容色。
    val contentColor = containerColorOverride?.let { contentColorFor(it) }
        ?: LocalEtaSegmentedRowContentColor.current
    // 选中态同理：由选中的容器色推出配套内容色，并覆盖前导/尾部/支持文本，
    // 避免选中行里图标、时间戳仍用未选中态颜色（如会话抽屉选中行）。
    val selectedContentColor = selectedContainerColor?.let { contentColorFor(it) }
    return ListItemDefaults.segmentedColors(
        containerColor = containerColor,
        disabledContainerColor = containerColor,
        contentColor = contentColor ?: Color.Unspecified,
        leadingContentColor = contentColor ?: Color.Unspecified,
        trailingContentColor = contentColor ?: Color.Unspecified,
        supportingContentColor = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
        selectedContainerColor = selectedContainerColor ?: Color.Unspecified,
        selectedContentColor = selectedContentColor ?: Color.Unspecified,
        selectedLeadingContentColor = selectedContentColor ?: Color.Unspecified,
        selectedTrailingContentColor = selectedContentColor ?: Color.Unspecified,
        selectedSupportingContentColor = selectedContentColor ?: Color.Unspecified,
    )
}

/**
 * 分段行的形状：位于容器内时用官方默认行形状（容器已提供外圆角，行本身只承载涟漪
 * 与按压形变），否则用外层分段列注入的形状、独立使用时退化为单条目形状。
 */
@Composable
internal fun etaSegmentedRowShapes(): ListItemShapes = if (LocalEtaSegmentedRowBare.current) {
    ListItemDefaults.shapes()
} else {
    LocalEtaSegmentedShapes.current ?: ListItemDefaults.segmentedShapes(0, 1)
}

/** 分段列表组的标题 → 条目间距。 */
internal val EtaSegmentedTitleSpacing = 8.dp

/**
 * 设置页分组之间的垂直间距（同 KernelSU ColorPalette 的 `spacedBy(13.dp)`，
 * 也是各设置页 `bottom = 13.dp` 的既有节奏）。页面里所有分组统一引用此值，
 * 调用方按 `padding(start = 16.dp, end = 16.dp, bottom = EtaSegmentedGroupSpacing)` 使用。
 */
internal val EtaSegmentedGroupSpacing = 13.dp

/** 设置页分组容器的水平页边距。 */
internal val EtaGroupHorizontalPadding = 16.dp

/** 动作行在官方内边距之上补的纵向留白。 */
private val ActionRowVerticalCompensation = 4.dp

/**
 * 动作行（菜单 / 跳转 / 展开）的内容内边距：官方 [ListItemDefaults.ContentPadding]
 * 的纵向值各补 [ActionRowVerticalCompensation]。
 *
 * 官方上下 10dp（精确指针 12dp）是给「带勾选控件 / 开关」的行调的：右侧控件撑起
 * 视觉重量，10dp 刚好；动作行只有文案与行尾图标，同样的留白会显得贴边。这里只补
 * 纵向，左右仍取官方 16dp——否则动作行的文字起点会和勾选行错开。
 *
 * 取值随官方内边距一起走，精确指针模式下同样成立，不写死 14dp。
 *
 * 分段行默认已回归官方内边距，本值改为**显式补偿档**：目前只有对话流工作气泡
 * （AgentWorkSegmentedBubble，行高锚点依赖它）在用。
 */
internal val EtaActionRowPadding: PaddingValues
    get() = with(ListItemDefaults.ContentPadding) {
        PaddingValues(
            start = calculateLeftPadding(LayoutDirection.Ltr),
            top = calculateTopPadding() + ActionRowVerticalCompensation,
            end = calculateRightPadding(LayoutDirection.Ltr),
            bottom = calculateBottomPadding() + ActionRowVerticalCompensation,
        )
    }

/**
 * 一组分段列表：KernelSU 式分段条目 + `titleSmallEmphasized` primary 组标题。
 *
 * [modifier] 同时作用于标题与条目容器（调用方传水平边距与组间距）。
 * 标题起点 16dp 与分段条目的内容起点对齐。
 *
 * @param title 组标题；空串/null 时不渲染标题行。
 */
@Composable
fun EtaSegmentedColumn(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: List<@Composable () -> Unit>,
) {
    if (content.isEmpty()) return
    Column(modifier = modifier) {
        if (!title.isNullOrEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                // 标题左边缘与分组容器（分段卡片）左边缘对齐：不再额外叠加 start=16dp，
                // 与 KernelSU 设置主页里 16dp 的卡片左边缘保持一致。
                modifier = Modifier.padding(bottom = EtaSegmentedTitleSpacing),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(SegmentedEntryGap)) {
            content.forEachIndexed { index, itemContent ->
                CompositionLocalProvider(
                    LocalEtaSegmentedShapes provides defaultSegmentedShape(index, content.size),
                ) {
                    itemContent()
                }
            }
        }
    }
}

/**
 * [EtaSegmentedColumn] 的 DSL 形式：条目按注册顺序编号，支持条件注册与 `visible` 隐藏。
 *
 * 与 KernelSU `SegmentedColumn` DSL 版一致：隐藏条目以 spring 动画收缩高度、
 * 淡出并把首/末圆角让给相邻可见条目。
 */
@Composable
fun EtaSegmentedColumn(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: EtaSegmentedScope.() -> Unit,
) {
    val entries = EtaSegmentedScope().apply(content).items

    Column(modifier = modifier) {
        if (!title.isNullOrEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                // 与 list 版一致：标题左边缘对齐分组容器（分段卡片）左边缘。
                modifier = Modifier.padding(bottom = EtaSegmentedTitleSpacing),
            )
        }

        // 分段显隐、首末圆角与间距统一取官方 Expressive spatial 规格，与全局动效同源。
        // 原先这里自带 spring(0.9, 800)（刚度偏硬），是项目内第二套时间轴；官方规格更软，
        // 也更接近 M3 分段列表的实际手感。
        val motionScheme = MaterialTheme.motionScheme
        val floatSpring: FiniteAnimationSpec<Float> = motionScheme.defaultSpatialSpec()
        val dpSpring: FiniteAnimationSpec<Dp> = motionScheme.defaultSpatialSpec()

        val progresses = entries.mapIndexed { index, entry ->
            key(entry.key ?: index) {
                animateFloatAsState(
                    targetValue = if (entry.visible) 1f else 0f,
                    animationSpec = floatSpring,
                    label = "SegmentedProgress",
                )
            }
        }

        val firstSurface = entries.indexOfFirst { it.visible && it.surface }
        val lastSurface = entries.indexOfLast { it.visible && it.surface }

        Layout(
            content = {
                entries.forEachIndexed { index, entry ->
                    key(entry.key ?: index) {
                        val isFirst = entry.surface &&
                            (if (firstSurface == -1) index == 0 else index == firstSurface)
                        val isLast = entry.surface &&
                            (if (lastSurface == -1) index == entries.lastIndex else index == lastSurface)

                        val topRadius by animateDpAsState(
                            if (isFirst) SegmentedOuterRadius else SegmentedInnerRadius,
                            dpSpring, label = "SegmentedTopRadius",
                        )
                        val bottomRadius by animateDpAsState(
                            if (isLast) SegmentedOuterRadius else SegmentedInnerRadius,
                            dpSpring, label = "SegmentedBottomRadius",
                        )
                        val animatedGap by animateDpAsState(
                            if (isFirst) 0.dp else SegmentedEntryGap,
                            dpSpring, label = "SegmentedGap",
                        )
                        // 欠阻尼弹簧回弹时动画值会短暂越过 0dp，而 Modifier.padding 不接受负值
                        // （否则抛 IllegalArgumentException: Padding must be non-negative），这里兜底。
                        val gap = animatedGap.coerceAtLeast(0.dp)

                        val shape = RoundedCornerShape(
                            topStart = topRadius, topEnd = topRadius,
                            bottomStart = bottomRadius, bottomEnd = bottomRadius,
                        )

                        Box(
                            modifier = Modifier
                                .zIndex(if (entry.visible) (entries.size - index).toFloat() else -index.toFloat())
                                .graphicsLayer {
                                    val progress = progresses[index].value.coerceAtLeast(0f)
                                    clip = true
                                    this.shape = object : Shape {
                                        override fun createOutline(
                                            size: Size,
                                            layoutDirection: LayoutDirection,
                                            density: Density,
                                        ): Outline = Outline.Rectangle(Rect(0f, 0f, size.width, size.height * progress))
                                    }
                                    alpha = (progress * 1.5f).coerceIn(0f, 1f)
                                }
                        ) {
                            CompositionLocalProvider(
                                LocalEtaSegmentedShapes provides ListItemDefaults.segmentedShapes(0, 1).copy(shape = shape),
                            ) {
                                Column(modifier = Modifier.padding(top = gap)) {
                                    entry.content()
                                }
                            }
                        }
                    }
                }
            }
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints) }
            val positions = IntArray(placeables.size)
            var y = 0f
            placeables.forEachIndexed { index, placeable ->
                positions[index] = y.roundToInt()
                y += placeable.height * progresses[index].value.coerceAtLeast(0f)
            }
            layout(constraints.maxWidth, y.roundToInt().coerceAtLeast(0)) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(0, positions[index])
                }
            }
        }
    }
}

/** [EtaSegmentedColumn] 的 DSL 作用域。 */
class EtaSegmentedScope {
    internal data class Entry(
        val key: Any?,
        val visible: Boolean,
        val surface: Boolean,
        val content: @Composable () -> Unit,
    )

    internal val items = mutableListOf<Entry>()

    /**
     * 注册一个条目。[visible] 为 false 时条目不渲染，但仍占据形状计算的位置——
     * 与 KernelSU 的 SegmentedColumn 行为一致：隐藏条目两侧的可见条目自动接管首/末圆角。
     *
     * [surface] 标记该条目是否为分段卡片：只有卡片才参与首/末外圆角（16dp）的计算。
     * 说明文字、Apply 按钮行、预设 chips 等不绘制卡片底色的内容传 `false`，
     * 否则它们会顶替真正末项的位置、把内圆角（4dp）留给最后一张卡片。
     */
    fun item(
        key: Any? = null,
        visible: Boolean = true,
        surface: Boolean = true,
        content: @Composable () -> Unit,
    ) {
        items.add(Entry(key ?: items.size, visible, surface, content))
    }
}

/** 当前分段形状（由 [EtaSegmentedColumn] 提供）；未在组内时返回 null。 */
@Composable
internal fun currentSegmentedShapes(): ListItemShapes? = LocalEtaSegmentedShapes.current

/**
 * 分段列表中的「非行」内容容器（如说明块、进度条、表单）：外形与分段条目一致，
 * 容器色同样取 `surfaceBright`，让同组内的自定义内容与分段行拼成一块连续表面。
 *
 * 对应 KernelSU 参考实现的 `SegmentedItemContainer`。
 */
@Composable
fun EtaSegmentedItemContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shapes = currentSegmentedShapes() ?: ListItemDefaults.segmentedShapes(0, 1)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceBright,
        shape = shapes.shape,
    ) {
        CompositionLocalProvider(LocalEtaSegmentedRowBare provides true) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

/** 预留：条目间距显式值（与官方 SegmentedGap 一致，供非 Column 场景使用）。 */
internal val EtaSegmentedGap: Dp = ListItemDefaults.SegmentedGap

/**
 * 分段列表行（KernelSU 参考实现的 `SegmentedListItem` 对应物）。
 *
 * 形状与容器色取自外层 [EtaSegmentedColumn] 通过 `LocalEtaSegmentedShapes` 注入的分段值；
 * 独立使用时退化为单条目形状。默认容器色为 `surfaceBright`，与页面背景 `surfaceContainer`
 * 形成一档层次。
 *
 * 供需要自定义 leading/trailing/headline 的页面直接使用，避免各处重复写
 * `SegmentedListItem(shapes = …, colors = …)` 样板。
 *
 * 内边距统一走官方 [ListItemDefaults.ContentPadding]，所有行类型一致，不再默认补偿。
 * 需要纵向补偿的动作行（仅对话流工作气泡）显式传 [EtaActionRowPadding]。
 * `choiceRow` 只是调用点的语义标注，不再影响布局。
 *
 * 勾选值需要反复翻转的多选行（勾选/取消勾选伴随官方选中形状 morph 与底色过渡）必须
 * 走 `checked`/`onCheckedChange`（官方 checked 变体，单调用点）；不要用 `selected`
 * 承载这种翻转——按 `selected` 在多个调用点之间切换会重建官方子树、重置动画状态。
 *
 * 轻触反馈由本行自己触发，调用方**不要**在 `onClick` 里再写 `TouchHaptics.click`。
 */
@Composable
fun EtaSegmentedRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    // 官方 SegmentedListItem 原生带 onLongClickLabel：长按是某功能唯一入口的行必须给出标签，
    // 否则 TalkBack 读不到这个功能，普通用户也不知道长按有菜单。
    onLongClickLabel: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    // 勾选行（官方 checked 变体）：非 null 时优先于 selected，走勾选语义（Role.Checkbox，
    // 行点击经 onCheckedChange 切换）。多选列表必须用本变体承载反复勾选/取消——官方选中
    // 形状 morph（shapeForInteraction → rememberAnimatedShape）与选中底色 animateColor
    // 都是调用点内的 remember 状态，若按 selected 翻转在多个调用点之间切换，整棵官方子树
    // 被销毁重建、状态重置，morph 就成了硬跳。
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    // 选中态容器色覆盖：不传时保持官方 segmentedColors 默认。仅个别列表（如会话抽屉）
    // 需要自定义选中色，其他调用方不受影响。
    selectedContainerColor: Color? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    // 语义标注：勾选 / 开关 / 单选行。内边距已统一官方默认，本参数不再影响布局；
    // 保留参数是为了不动大量调用点，待侧栏等共用方空闲后再清理。
    choiceRow: Boolean = false,
    contentPadding: PaddingValues = ListItemDefaults.ContentPadding,
    content: @Composable () -> Unit,
) {
    val shapes = etaSegmentedRowShapes()
    val colors = if (selectedContainerColor != null) {
        etaSegmentedRowColors(selectedContainerColor = selectedContainerColor)
    } else {
        etaSegmentedRowColors()
    }
    val view = LocalView.current
    // 显式类型：let 返回嵌套 lambda 时推断器会落到 Nothing?。
    val hapticClick: (() -> Unit)? = onClick?.let { click -> { TouchHaptics.click(view); click() } }
    // 勾选行的行级触感同样由本组件内建，调用方不要在 onCheckedChange 里再加。
    val hapticOnCheckedChange = onCheckedChange?.let { callback ->
        { value: Boolean -> TouchHaptics.click(view); callback(value) }
    }
    // 勾选行必须且只能走这一个调用点：checked 值在同一调用点内翻转，官方形状/颜色
    // morph 的 remember 状态才不会丢。官方 checked 变体长按入口可用（onLongClick 槽位）。
    if (checked != null) {
        SegmentedListItem(
            verticalAlignment = Alignment.CenterVertically,
            checked = checked,
            // 官方 checked 变体的 onCheckedChange 为非空参数；未给时以空实现兜底
            // （行点击无效果），因此 checked 非 null 时调用方应同时提供 onCheckedChange。
            onCheckedChange = hapticOnCheckedChange ?: {},
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
            enabled = enabled,
            shapes = shapes,
            colors = colors,
            modifier = modifier,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            overlineContent = overlineContent,
            supportingContent = supportingContent,
            contentPadding = contentPadding,
            content = content,
        )
    } else if (selected) {
        SegmentedListItem(
            verticalAlignment = Alignment.CenterVertically,
            selected = true,
            onClick = hapticClick ?: {},
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
            enabled = enabled,
            shapes = shapes,
            colors = colors,
            modifier = modifier,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            overlineContent = overlineContent,
            supportingContent = supportingContent,
            contentPadding = contentPadding,
            content = content,
        )
    } else if (hapticClick != null) {
        SegmentedListItem(
            verticalAlignment = Alignment.CenterVertically,
            onClick = hapticClick,
            onLongClick = onLongClick,
            onLongClickLabel = onLongClickLabel,
            enabled = enabled,
            shapes = shapes,
            colors = colors,
            modifier = modifier,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            overlineContent = overlineContent,
            supportingContent = supportingContent,
            contentPadding = contentPadding,
            content = content,
        )
    } else {
        SegmentedListItem(
            verticalAlignment = Alignment.CenterVertically,
            shapes = shapes,
            colors = colors,
            enabled = enabled,
            modifier = modifier,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            overlineContent = overlineContent,
            supportingContent = supportingContent,
            contentPadding = contentPadding,
            content = content,
        )
    }
}

/**
 * 动态列表的单个分段条目：按 `index`/`count` 计算首项/末项圆角。
 *
 * 对应 KernelSU 参考实现的 `SegmentedItem`，供 `LazyColumn` + `itemsIndexed`
 * 这类无法预先构造整个组列表的场景使用。
 */
@Composable
fun EtaSegmentedItem(
    index: Int,
    count: Int,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalEtaSegmentedShapes provides defaultSegmentedShape(index, count),
    ) {
        content()
    }
}

/**
 * 带勾选框的分段行（KernelSU 参考实现的 `SegmentedCheckboxItem`）。
 *
 * 整行切换：`SegmentedListItem(checked = …)` 自带 Checkbox 语义、涟漪与按压形变；
 * 前导勾选框仅作视觉呈现（`onCheckedChange = null`），避免两次回调。
 * 内边距保持官方值——勾选行不属于动作行，不做纵向补偿。
 * 轻触反馈由本行自己触发，调用方**不要**在 `onCheckedChange` 里再写 `TouchHaptics.click`。
 */
@Composable
fun EtaSegmentedCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "",
    summary: String? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable () -> Unit = { Text(text = title) },
    supportingContent: (@Composable () -> Unit)? = null,
) {
    val shapes = etaSegmentedRowShapes()
    val colors = etaSegmentedRowColors()
    val view = LocalView.current
    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        checked = checked,
        onCheckedChange = { value ->
            TouchHaptics.click(view)
            onCheckedChange(value)
        },
        enabled = enabled,
        shapes = shapes,
        colors = colors,
        modifier = modifier,
        interactionSource = interactionSource,
        leadingContent = {
            Checkbox(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
                interactionSource = interactionSource,
                // KernelSU 参考实现同样显式取 24dp：官方容器尺寸令牌在 Checkbox 内不可见。
                modifier = Modifier.size(24.dp),
            )
        },
        supportingContent = when {
            supportingContent != null -> supportingContent
            summary != null -> { { Text(text = summary) } }
            else -> null
        },
        content = content,
    )
}

/**
 * 带单选按钮的分段行（KernelSU 参考实现的 `SegmentedRadioItem`）。
 * 整行可选；`selected` 决定官方选中态配色。内边距保持官方值（同勾选行）。
 *
 * 轻触反馈由本行自己触发，调用方**不要**在 `onClick` 里再写 `TouchHaptics.click`。
 */
@Composable
fun EtaSegmentedRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit = { Text(text = title) },
) {
    val shapes = etaSegmentedRowShapes()
    val colors = etaSegmentedRowColors()
    val view = LocalView.current
    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        selected = selected,
        onClick = {
            TouchHaptics.click(view)
            onClick()
        },
        enabled = enabled,
        shapes = shapes,
        colors = colors,
        modifier = modifier,
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
                enabled = enabled,
            )
        },
        supportingContent = summary?.let { { Text(text = it) } },
        content = content,
    )
}

/**
 * 分段输入行（KernelSU 参考实现的 `SegmentedTextField`）：行内嵌 `BasicTextField`，
 * 占位符默认 "-"，聚焦时自动滚入视野。
 */
@Composable
fun EtaSegmentedTextField(
    modifier: Modifier = Modifier,
    label: String = "",
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    cursorBrush: Brush = SolidColor(colorScheme.primary),
    placeholder: @Composable (() -> Unit)? = { Text("-") },
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val shapes = etaSegmentedRowShapes()
    val colors = etaSegmentedRowColors()

    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .bringIntoViewRequester(bringIntoViewRequester)
            .focusRequester(focusRequester),
        shapes = shapes,
        colors = colors,
        onClick = { focusRequester.requestFocus() },
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        supportingContent = supportingContent,
        content = {
            Column {
                if (label.isNotEmpty()) {
                    Text(text = label, color = if (isError) colorScheme.error else colors.contentColor)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (it.isFocused) {
                                coroutineScope.launch {
                                    bringIntoViewRequester.bringIntoView()
                                }
                            }
                        },
                    enabled = enabled,
                    readOnly = readOnly,
                    textStyle = textStyle.copy(color = colors.supportingContentColor),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    singleLine = singleLine,
                    maxLines = maxLines,
                    minLines = minLines,
                    visualTransformation = visualTransformation,
                    onTextLayout = onTextLayout,
                    interactionSource = interactionSource,
                    cursorBrush = cursorBrush,
                    decorationBox = { innerTextField ->
                        if (value.isEmpty() && placeholder != null) {
                            Box(contentAlignment = Alignment.CenterStart) {
                                CompositionLocalProvider(
                                    LocalContentColor provides colors.supportingContentColor,
                                ) {
                                    // 占位符沿用分段行的官方正文令牌（content 槽注入的 bodyLarge），
                                    // 与输入文字/标签保持同一默认字阶，不再单独指定 bodyMedium。
                                    placeholder()
                                }
                            }
                        }
                        innerTextField()
                    },
                )
            }
        },
    )
}
