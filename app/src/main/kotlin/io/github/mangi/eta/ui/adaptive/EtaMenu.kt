package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MenuItemShapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * 动作菜单（重命名、删除、分享等）在官方内边距之上补的纵向留白。
 *
 * 官方单组气泡上下只有 2dp，纯动作菜单会显得贴边；补到 4dp 才和圆角容器的视觉厚度相称。
 * 单选菜单不能用这档补偿——气泡会发空，选中项药丸周围的紧凑感就没了。
 */
private val ActionMenuVerticalCompensation = 2.dp

/** 动作菜单的组内边距：横向沿用官方，纵向在官方 2dp 上补 [ActionMenuVerticalCompensation]。 */
internal val EtaActionMenuGroupPadding: PaddingValues
    get() = with(MenuDefaults.DropdownMenuGroupContentPadding) {
        PaddingValues(
            start = calculateLeftPadding(LayoutDirection.Ltr),
            top = calculateTopPadding() + ActionMenuVerticalCompensation,
            end = calculateRightPadding(LayoutDirection.Ltr),
            bottom = calculateBottomPadding() + ActionMenuVerticalCompensation,
        )
    }

/**
 * 按菜单类型取组内边距。
 *
 * 单选菜单（条目带勾选）保持官方 2dp 的紧凑档；动作菜单补到 4dp，多组动作菜单的每组
 * 同样补偿——组间距之外，每组自身的上下留白也保持一致。
 */
internal fun etaMenuGroupPadding(selectable: Boolean): PaddingValues =
    if (selectable) {
        MenuDefaults.DropdownMenuGroupContentPadding
    } else {
        EtaActionMenuGroupPadding
    }

/**
 * KernelSU `OffsetAnchoredExpressiveMenu` 对应物：锚定在按下位置的表达性菜单。
 *
 * 菜单内容用 [EtaMenuGroupSurface]（与官方 `DropdownMenuGroup` 逐参数等价），与参考实现一致。
 * [selectableMenu] 为 true 表示条目带单选勾选，走官方紧凑纵向边距。
 */
@Composable
fun EtaOffsetAnchoredMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorOffset: IntOffset = IntOffset.Zero,
    selectableMenu: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(Constraints())
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
            layout(width, 0) {
                placeable.place(anchorOffset.x, anchorOffset.y)
            }
        }
    ) {
        DropdownMenuPopup(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
        ) {
            EtaMenuGroupSurface(scrollable = true, selectableMenu = selectableMenu) {
                content()
            }
        }
    }
}

/** 记录按下位置（供 [EtaOffsetAnchoredMenu] 锚定），与 KernelSU `trackPressPosition` 一致。 */
fun Modifier.trackPressPosition(onPress: (Offset) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onPress(down.position)
    }
}

/**
 * Material 3 Expressive 菜单分组：官方 `DropdownMenuGroup` + 官方 `MenuDefaults.groupShape`。
 *
 * 形状由 [index] / [count] 决定：单组取 standalone 组形状，首组取 leading、末组取
 * trailing、中间组取 middle 组形状。
 *
 * 本项目多段菜单一律放进同一个组（`index = 0, count = 1`）、段落之间用 [EtaMenuDivider]
 * 分隔，不再拆成多个组靠组间距分区。
 *
 * 容器色固定 standard 档（`MenuDefaults.groupStandardContainerColor`，即 surfaceContainerLow），
 * 不启用 vibrant 强调色——vibrant 会把整组染成 tertiaryContainer，在聊天输入区这种高频、
 * 低信息密度的菜单里过于抢眼。
 *
 * 组内边距按菜单类型取（见 [etaMenuGroupPadding]）：单选菜单沿用官方
 * `MenuDefaults.DropdownMenuGroupContentPadding`（左右 0、上下 2dp），条目自身的横向
 * 留白由官方条目内容内边距（12dp）提供——选中项因此是一枚内缩的圆角药丸，与
 * 「上下文压缩 → 请求接口」那个下拉（`EtaSegmentedDropdownRow`）同款；动作菜单才补到
 * 上下 4dp。不要再把单选菜单的纵向补到和横向一样宽：气泡上下会明显发空。
 *
 * 官方 `DropdownMenuGroup` 的 `modifier` 落在最外层 Surface 上（`hoverable` 之后），
 * 需要滚动时圆角背景会跟着内容一起滚、被视口裁成直角；要滚动请改用
 * [EtaMenuGroupSurface]。
 *
 * material3 1.5.0-alpha29 起这批菜单 API 已是稳定 API（早期 alpha 里的
 * `@ExperimentalMaterial3ExpressiveApi` 只留在 `DropdownMenuItemChecked` /
 * `DropdownMenuItemSelected` 这两个已隐藏的旧名上），因此这里不需要 `@OptIn`。
 */
@Composable
fun EtaMenuGroup(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    selectableMenu: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenuGroup(
        shapes = MenuDefaults.groupShape(index = index, count = count),
        modifier = modifier,
        containerColor = MenuDefaults.groupStandardContainerColor,
        contentPadding = etaMenuGroupPadding(selectable = selectableMenu),
        content = content,
    )
}

/**
 * Expressive 菜单分组容器：与官方 `DropdownMenuGroup` 逐参数等价，但滚动落在内层 Column。
 *
 * 官方 `DropdownMenuGroup` 的 `modifier` 挂在外层 Surface 上，滚动时圆角背景会随内容
 * 一起滚、被视口裁成直角；这里把相同的 Surface 配置（组形状 / standard 容器色 /
 * 0dp tonal / 3dp shadow）留在外层，把滚动挂到内层 Column 上，圆角因此始终完整。
 *
 * 组内边距按菜单类型取（见 [etaMenuGroupPadding]）：单选菜单沿用官方
 * `MenuDefaults.DropdownMenuGroupContentPadding`（左右 0、上下 2dp），也就是
 * 「上下文压缩 → 请求接口」那个下拉的紧凑上下边距；动作菜单补到上下 4dp。
 */
@Composable
fun EtaMenuGroupSurface(
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    selectableMenu: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MenuDefaults.groupShape(index = 0, count = 1).shape,
        color = MenuDefaults.groupStandardContainerColor,
        // 官方 MenuDefaults.TonalElevation / ShadowElevation 取值（0dp / 3dp）。
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(etaMenuGroupPadding(selectable = selectableMenu))
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            content = content,
        )
    }
}

/**
 * 菜单段落分割线：官方 `HorizontalDivider` + 官方 `MenuDefaults.HorizontalDividerPadding`。
 *
 * 多段菜单放进同一个圆角容器，段落之间用这条分割线分开；不要再拆成多个
 * [EtaMenuGroup] 靠组间距分区。
 */
@Composable
fun EtaMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(MenuDefaults.HorizontalDividerPadding),
    )
}

/**
 * 条目形状：多项按位置取官方 leading / middle / trailing；单项取官方 medium 圆角。
 *
 * 官方 `itemShape(0, 1)` 给的是 standalone 条目形状（extraSmall，4dp），按下高亮在圆角
 * 气泡里显得特别小；而单项又不能直接用容器形状（large，16dp）——条目本身内缩 2dp，
 * 同半径会画出一圈偏心描边。这里取 medium（12dp），与多项菜单首末行的端部圆角同档，
 * 单项气泡才和别处同款。其余情况仍代理官方 `MenuDefaults.itemShape`，跟随主题 `Shapes`。
 */
@Composable
fun EtaMenuItemShape(index: Int, count: Int): Shape =
    if (count <= 1) {
        MenuDefaults.selectedItemShape
    } else {
        MenuDefaults.itemShape(index = index, count = count).shape
    }

/**
 * 可选中条目形状：未选中态用 [EtaMenuItemShape]（单项时取 medium 圆角），选中态用官方
 * medium 全圆角药丸（与「上下文压缩 → 请求接口」那个下拉同款）。
 */
@Composable
fun EtaMenuItemShapes(index: Int, count: Int): MenuItemShapes {
    val shapes = MenuDefaults.itemShape(index = index, count = count)
    return if (count <= 1) {
        shapes.copy(shape = EtaMenuItemShape(index = index, count = count))
    } else {
        shapes
    }
}

/**
 * 文案型菜单条目：前导图标 + 文案 + 可用态 + 点击回调。
 *
 * 与其它菜单一致，条目一律带前导图标（左侧 Icon、右侧名字）；`icon` 为空时才退化为纯文案。
 * [destructive] 让图标与文案同取 error 色（删除、移除等不可逆操作）。
 */
@Immutable
data class EtaMenuEntry(
    val text: String,
    val icon: ImageVector? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 文案型下拉菜单：官方 Expressive 弹层 + 官方分组，条目形状按位置取官方 leading / middle / trailing。
 *
 * 条目数固定后形状由 [EtaMenuItemShape] 按位置推导，调用方不必自己算圆角。
 */
@Composable
fun EtaTextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    entries: List<EtaMenuEntry>,
    modifier: Modifier = Modifier,
) {
    EtaMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        EtaMenuGroup(index = 0, count = 1) {
            entries.forEachIndexed { index, entry ->
                DropdownMenuItem(
                    text = {
                        if (entry.destructive) {
                            Text(text = entry.text, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(text = entry.text)
                        }
                    },
                    enabled = entry.enabled,
                    leadingIcon = entry.icon?.let { icon ->
                        {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                // 前导槽位自带内容色，非破坏性项不要再写死颜色。
                                tint = if (entry.destructive) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    LocalContentColor.current
                                },
                            )
                        }
                    },
                    shape = EtaMenuItemShape(index, entries.size),
                    onClick = entry.onClick,
                )
            }
        }
    }
}

/** Expressive 菜单宽度下限：够放下「前置图标 + 四字中文」，与旧版平台默认弹窗宽度接近。 */
val EtaMenuMinWidth: androidx.compose.ui.unit.Dp = 140.dp

/** Expressive 菜单宽度上限：避免超长文案把菜单撑到屏幕边缘。 */
val EtaMenuMaxWidth: androidx.compose.ui.unit.Dp = 280.dp

/**
 * Material 3 Expressive 菜单弹层：官方 `DropdownMenuPopup` + 本项目统一的宽度约束。
 *
 * 官方 `DropdownMenuPopup` 不带平台默认宽度（`usePlatformDefaultWidth` 恒为 false），
 * 弹层宽度完全由内容决定；这里补上 [EtaMenuMinWidth] / [EtaMenuMaxWidth]，
 * 让「图标 + 四字中文」的短菜单也不会缩成一条窄缝。
 *
 * [anchorPosition] 选展开策略：默认 [MenuAnchorPosition.Below]（锚下展开，放不下时逐候选
 * 回退）；「向上展开、底部停在锚上沿」传 [MenuAnchorPosition.Above]。
 *
 * [maxHeight] 给长菜单封高度预算（如锚顶到状态栏地板的距离扣去边距）：多条目菜单不再
 * 顶到屏幕最上沿、盖住运行胶囊等更高层的窗口；超预算部分交给
 * [EtaMenuGroupSurface]（scrollable = true）内滚。空预算（null）保持内容自量高度。
 *
 * 窗口属性沿用官方默认（`MenuDefaults.DefaultMenuProperties`，即 focusable = true）：
 * 覆盖 `focusable` 会给弹窗加 `FLAG_NOT_FOCUSABLE`，而 `createFlags()` 又无条件带
 * `FLAG_WATCH_OUTSIDE_TOUCH`，点开菜单的那一次抬手会被当成外部点击，菜单刚弹出就被关掉。
 */
@Composable
fun EtaMenuPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    anchorPosition: MenuAnchorPosition = MenuAnchorPosition.Below,
    offset: DpOffset = DpOffset.Zero,
    maxHeight: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        popupPositionProvider =
            MenuDefaults.rememberDropdownMenuPopupPositionProvider(anchorPosition, offset),
        modifier = modifier
            .widthIn(min = EtaMenuMinWidth, max = EtaMenuMaxWidth)
            .then(maxHeight?.let { height -> Modifier.heightIn(max = height) } ?: Modifier),
        content = content,
    )
}
