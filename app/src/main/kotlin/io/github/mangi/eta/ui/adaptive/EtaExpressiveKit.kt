package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.material3.ripple
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle

/**
 * Material 3 Expressive 基础组件套件。
 *
 * 全部基于 androidx.compose.material3 标准组件与官方令牌，
 * 颜色一律通过 `MaterialTheme.colorScheme` 角色引用，不写死颜色值；尺寸、形状、间距、
 * 动效曲线均取官方默认值（IconButtonDefaults / ButtonGroupDefaults /
 * MaterialTheme.motionScheme），不自行定义。
 */

// ---------------------------------------------------------------------------
// 图标按钮
// ---------------------------------------------------------------------------

/**
 * M3 Expressive 图标按钮档位。容器与图标尺寸一律取官方 `IconButtonDefaults` 令牌：
 * XS 32/20、S 40/24、M 56/24、L 96/32、XL 136/40。
 */
enum class EtaIconSize { XS, S, M, L, XL }

/** 官方图标按钮容器尺寸（正方形）。 */
@Composable
private fun EtaIconSize.containerSize(): DpSize = when (this) {
    EtaIconSize.XS -> IconButtonDefaults.extraSmallContainerSize()
    EtaIconSize.S -> IconButtonDefaults.smallContainerSize()
    EtaIconSize.M -> IconButtonDefaults.mediumContainerSize()
    EtaIconSize.L -> IconButtonDefaults.largeContainerSize()
    EtaIconSize.XL -> IconButtonDefaults.extraLargeContainerSize()
}

/** 官方图标按钮图标尺寸。 */
@Composable
private fun EtaIconSize.iconSize(): Dp = when (this) {
    EtaIconSize.XS -> IconButtonDefaults.extraSmallIconSize
    EtaIconSize.S -> IconButtonDefaults.smallIconSize
    EtaIconSize.M -> IconButtonDefaults.mediumIconSize
    EtaIconSize.L -> IconButtonDefaults.largeIconSize
    EtaIconSize.XL -> IconButtonDefaults.extraLargeIconSize
}

/** 按钮外观：填充 / 色调 / 色调强调（tertiary 档）/ 中性表面（surfaceContainerHighest 档）/ 描边 / 标准。 */
enum class EtaButtonStyle { Filled, Tonal, TonalEmphasis, SurfaceHighest, Outlined, Standard }

/**
 * 自适应图标按钮（M3 Expressive）。
 *
 * @param style 按钮档位；`TonalEmphasis` 在 Tonal 基础上把容器提到 tertiaryContainer
 *   强调档（官方 `FilledTonalIconButton` + 官方 `filledTonalIconButtonColors` 覆盖，
 *   形状/尺寸/涟漪仍走官方默认）；`SurfaceHighest` 用中性表面档
 *   `surfaceContainerHighest` / `onSurface`（会话侧栏顶部胶囊未选中态同款配色 token）。
 * @param shapes 官方 [IconButtonShapes] 形状对；传官方 `IconButtonDefaults.shapes()` 即
 *   开启 M3E 按压 morph（圆 → 方圆角），不传保持官方默认的固定形状。
 * @param stretch 为 true 时横向拉伸占满剩余宽度（用于“最后的按钮向右拉伸”的行布局）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EtaIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: EtaIconSize = EtaIconSize.M,
    style: EtaButtonStyle = EtaButtonStyle.Tonal,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
    shapes: IconButtonShapes? = null,
) {
    val containerSize = size.containerSize()
    val iconSize = size.iconSize()
    val sizedModifier = if (fillWidth) {
        modifier.fillMaxWidth().heightIn(min = containerSize.height)
    } else {
        modifier.size(containerSize)
    }
    val content: @Composable () -> Unit = {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
        )
    }
    when (style) {
        EtaButtonStyle.Filled ->
            if (shapes == null) {
                FilledIconButton(onClick = onClick, modifier = sizedModifier, enabled = enabled, content = content)
            } else {
                FilledIconButton(
                    onClick = onClick,
                    shapes = shapes,
                    modifier = sizedModifier,
                    enabled = enabled,
                    content = content,
                )
            }
        EtaButtonStyle.Tonal ->
            if (shapes == null) {
                FilledTonalIconButton(onClick = onClick, modifier = sizedModifier, enabled = enabled, content = content)
            } else {
                FilledTonalIconButton(
                    onClick = onClick,
                    shapes = shapes,
                    modifier = sizedModifier,
                    enabled = enabled,
                    content = content,
                )
            }
        EtaButtonStyle.TonalEmphasis -> FilledTonalIconButton(
            onClick = onClick,
            modifier = sizedModifier,
            enabled = enabled,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
            content = content,
        )
        EtaButtonStyle.TonalEmphasis ->
            if (shapes == null) {
                FilledTonalIconButton(
                    onClick = onClick,
                    modifier = sizedModifier,
                    enabled = enabled,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                    content = content,
                )
            } else {
                FilledTonalIconButton(
                    onClick = onClick,
                    shapes = shapes,
                    modifier = sizedModifier,
                    enabled = enabled,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                    content = content,
                )
            }
        EtaButtonStyle.SurfaceHighest ->
            if (shapes == null) {
                FilledTonalIconButton(
                    onClick = onClick,
                    modifier = sizedModifier,
                    enabled = enabled,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    content = content,
                )
            } else {
                FilledTonalIconButton(
                    onClick = onClick,
                    shapes = shapes,
                    modifier = sizedModifier,
                    enabled = enabled,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    content = content,
                )
            }
        EtaButtonStyle.Outlined ->
            if (shapes == null) {
                OutlinedIconButton(onClick = onClick, modifier = sizedModifier, enabled = enabled, content = content)
            } else {
                OutlinedIconButton(
                    onClick = onClick,
                    shapes = shapes,
                    modifier = sizedModifier,
                    enabled = enabled,
                    content = content,
                )
            }
        EtaButtonStyle.Standard ->
            if (shapes == null) {
                IconButton(onClick = onClick, modifier = sizedModifier, enabled = enabled, content = content)
            } else {
                IconButton(
                    onClick = onClick,
                    shapes = shapes,
                    modifier = sizedModifier,
                    enabled = enabled,
                    content = content,
                )
            }
    }
}

// ---------------------------------------------------------------------------
// 浮动工具栏
// ---------------------------------------------------------------------------

/**
 * `HorizontalFloatingToolbar` 端盖同心的内容内边距。
 *
 * 官方药丸的端盖是半径 = 容器高一半（64dp ⇒ 32dp）的半圆，官方 `ContentPadding` 各方向都只让出
 * 8dp：圆钮被官方纵向居中到上下各 (H − d)/2（40dp 钮即 12dp），水平却只贴住 8dp 内边距，圆心落在
 * 8 + d/2 = 28dp，而端盖弧心在 H/2 = 32dp——两者错开 4dp，沿弧的留白中段窄、上下宽，看着偏心。
 *
 * 把水平内边距也取 (H − d)/2（恒等于「端盖半径 − 圆钮半径」），圆心正落在端盖弧心上，
 * 圆钮四周留白等宽，端盖与圆钮真正同心；纵向仍用官方值（居中后实际留白就是 (H − d)/2，不受影响）。
 *
 * @param buttonSize 工具栏两端圆钮的容器边长（一般取 `IconButtonDefaults.smallContainerSize().width`）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun EtaFloatingToolbarConcentricPadding(buttonSize: Dp): PaddingValues {
    val officialPadding = FloatingToolbarDefaults.ContentPadding.calculateTopPadding()
    val concentricPadding = (FloatingToolbarDefaults.ContainerSize - buttonSize) / 2
    return PaddingValues(
        horizontal = concentricPadding.coerceAtLeast(officialPadding),
        vertical = officialPadding,
    )
}

// ---------------------------------------------------------------------------
// 相连按钮组
// ---------------------------------------------------------------------------

/** 相连按钮组的容器：官方间距（`ButtonGroupDefaults.ConnectedSpaceBetween` = 2dp）、垂直居中。 */
@Composable
fun EtaConnectedGroupRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        content = content,
    )
}

/**
 * 相连按钮组统一配色：未选中态取官方默认 secondaryContainer 档，与页面其他
 * FilledTonal 系组件同色阶；选中态（`checked = true`，如朗读中 / 编辑中）取官方
 * `FilledButtonTokens.SelectedContainerColor` / `SelectedPressedLabelTextColor`
 * 的 primary / onPrimary，配合官方 `connectedButtonCheckedShape`（全圆角胶囊）
 * 形成「保持按下」的双状态。
 */
@Composable
fun etaConnectedGroupColors() = ToggleButtonDefaults.colors(
    containerColor = MaterialTheme.colorScheme.secondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    // 选中态用 primaryContainer（Tonal 风格的淡主色），比 primary 更轻盈，
    // 视觉上更接近官方 FilledTonal 的选中态；内容色取 onPrimaryContainer。
    checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    // 禁用态显式配色，避免落到默认的深灰。
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.38f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
)

/**
 * 相连按钮组里的图标动作（KernelSU AppProfile 同款官方模式）：官方 `ToggleButton` +
 * `ButtonGroupDefaults` 连体形状（首位/中间/末位）。纯图标按钮用官方
 * `extraSmallContainerSize` 强制成等宽等高的方形容器（顶栏组用 Small/40dp，消息操作条
 * 再小一号用 XS/32dp），`contentPadding(0.dp)` 置零官方文字按钮的水平内边距。
 * 单按钮（`count <= 1`）时退回官方默认全圆角形状。
 *
 * [checked] 交给官方 `ToggleButton` 的双状态：`true` 时按钮保持官方选中态——形状
 * morph 到 `connectedButtonCheckedShape`（全圆角胶囊），容器色切到
 * [etaConnectedGroupColors] 的选中档（primary）。朗读中 / 编辑中这类「保持按下」
 * 的动作由调用方把自己的状态传进来，点击仍走 [onClick]（选中态由调用方持有）。
 *
 * 此前实现私藏在 `AgentTurnFooter.kt`（M3ConnectedIconAction / M3ConnectedGroupColors），
 * 现收编进本套件作为唯一出处。
 */
@Composable
fun EtaConnectedIconAction(
    onClick: () -> Unit,
    enabled: Boolean,
    index: Int,
    count: Int,
    checked: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val shapes: ToggleButtonShapes? = when {
        count <= 1 -> null
        index <= 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
        index >= count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
    }
    val colors = etaConnectedGroupColors()
    if (shapes == null) {
        ToggleButton(
            checked = checked,
            onCheckedChange = { onClick() },
            enabled = enabled,
            modifier = Modifier.size(IconButtonDefaults.extraSmallContainerSize()),
            buttonSize = ToggleButtonSize.Small,
            contentPadding = PaddingValues(0.dp),
            colors = colors,
            content = content,
        )
    } else {
        ToggleButton(
            checked = checked,
            onCheckedChange = { onClick() },
            enabled = enabled,
            modifier = Modifier.size(IconButtonDefaults.extraSmallContainerSize()),
            buttonSize = ToggleButtonSize.Small,
            shapes = shapes,
            contentPadding = PaddingValues(0.dp),
            colors = colors,
            content = content,
        )
    }
}

/**
 * 相连按钮组里的位置：决定官方 `ButtonGroupDefaults` 哪一侧保持完全圆角。
 */
enum class EtaConnectedPosition { Single, Leading, Middle, Trailing }

/** 按组内下标 / 总数归位；`count <= 1` 时退化为官方单按钮的全圆角形状。 */
fun etaConnectedPosition(index: Int, count: Int): EtaConnectedPosition = when {
    count <= 1 -> EtaConnectedPosition.Single
    index <= 0 -> EtaConnectedPosition.Leading
    index >= count - 1 -> EtaConnectedPosition.Trailing
    else -> EtaConnectedPosition.Middle
}

/**
 * 官方相连按钮组形状（静止 + 按压），全部取自 `ButtonGroupDefaults`：
 * 外侧 `CornerFull`、内侧 8dp，按压时内圆角收到 4dp。
 *
 * 仅适用于**不可选中**的按钮（`Button` / `FilledTonalButton` / `IconButton`）：它只给
 * 连体位置形状，不带选中态形状。可选中的相连按钮组请改用官方
 * `ButtonGroupDefaults.connected*ButtonShapes()` —— `ToggleButtonShapes` 自带
 * `shape = 位置形状`、`checkedShape = connectedButtonCheckedShape`（四角全部大圆角），
 * 交给官方 `ToggleButton` 后选中态会自动切换（连体形状 ↔ 全圆角胶囊，含官方形变动画）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun etaConnectedButtonShapes(position: EtaConnectedPosition): ButtonShapes = when (position) {
    EtaConnectedPosition.Single -> ButtonDefaults.shapes()
    EtaConnectedPosition.Leading -> ButtonDefaults.shapes(
        shape = ButtonGroupDefaults.connectedLeadingButtonShape,
        pressedShape = ButtonGroupDefaults.connectedLeadingButtonPressShape,
    )
    EtaConnectedPosition.Middle -> ButtonDefaults.shapes(
        shape = ButtonGroupDefaults.connectedMiddleButtonShapes().shape,
        pressedShape = ButtonGroupDefaults.connectedMiddleButtonPressShape,
    )
    EtaConnectedPosition.Trailing -> ButtonDefaults.shapes(
        shape = ButtonGroupDefaults.connectedTrailingButtonShape,
        pressedShape = ButtonGroupDefaults.connectedTrailingButtonPressShape,
    )
}

// ---------------------------------------------------------------------------
// Morph 形状（M3E 形状渐变）
// ---------------------------------------------------------------------------

/** 相连按钮组内侧圆角的官方档位（`ShapeTokens.CornerValueSmall` = 8dp）。 */
val EtaConnectedInnerCornerRadius: Dp = 8.dp

/** 相连按钮组按压时内侧圆角的官方档位（`ShapeTokens.CornerValueExtraSmall` = 4dp）。 */
val EtaConnectedPressedInnerCornerRadius: Dp = 4.dp

/**
 * 单位坐标空间：`1f` = 按钮高度。全圆角档取半高（等价官方 `CornerFull` =
 * `RoundedCornerShape(percent = 50)`），在单位空间里就是 0.5。
 */
private const val EtaHalfHeight = 0.5f

/**
 * 「未选中连体形状 ↔ 选中胶囊 ↔ 按压形状」之间的 Morph 形状（M3E 形状渐变），
 * 由选中与按压两个独立进度驱动。
 *
 * 顶点以「按钮高度 = 1」为单位构造（`y ∈ [-0.5, 0.5]`、`x = ±宽高比 / 2`，圆心在原点），
 * 绘制时按布局高度等比缩放并居中：因此圆角在两个方向上都是精确的 dp 圆角，不会像
 * `MaterialShapes` 那样被按 (宽, 高) 非均匀缩放。两端形状的顶点数、顺序、宽高比完全一致
 * （只差每组圆角半径），[Morph] 因此只在圆角之间插值，中途不会出现形状跳变。
 *
 * 按压沿用官方语义，只收**内侧**圆角：官方 `ButtonGroupDefaults.connected*PressShape` 即
 * 「外侧仍 `CornerFull`、内侧取 `PressedInnerCornerCornerSize`（4dp）」——未选中端内侧
 * 8dp → 4dp、选中端全圆角 → 4dp；结构性全圆的边（首位的左、末位的右）一直保持全圆角。
 * 两个进度互不干扰，可任意叠加（按下时同时切换选中也不会跳变）。
 *
 * @param selectionProgress 0 = 未选中（连体形状）、1 = 选中（全圆角胶囊）。
 * @param pressProgress 0 = 未按压、1 = 完全按下（内侧圆角收到 4dp，即官方按压形状）。
 * @param position 组内位置：靠外一侧（首位的左、末位的右）结构上保持全圆角，内侧按进度取值。
 * @param innerCornerRadius 连体形状的内侧圆角，默认官方 8dp。
 * @param pressedInnerCornerRadius 按压时的内侧圆角，默认官方 4dp。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
class EtaMorphChipShape(
    private val selectionProgress: Float,
    private val pressProgress: Float,
    private val position: EtaConnectedPosition,
    private val innerCornerRadius: Dp = EtaConnectedInnerCornerRadius,
    private val pressedInnerCornerRadius: Dp = EtaConnectedPressedInnerCornerRadius,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (size.width <= 0f || size.height <= 0f) {
            return Outline.Rectangle(Rect(Offset.Zero, size))
        }
        val aspect = size.width / size.height
        val innerBase = with(density) { innerCornerRadius.toPx() } / size.height
        val innerPressed = with(density) { pressedInnerCornerRadius.toPx() } / size.height
        // 未选中端按压：8dp → 4dp；选中端按压：全圆角 → 4dp。
        val connectedInner = innerBase + (innerPressed - innerBase) * pressProgress
        val pillInner = EtaHalfHeight + (innerPressed - EtaHalfHeight) * pressProgress
        val fullStart = position == EtaConnectedPosition.Single || position == EtaConnectedPosition.Leading
        val fullEnd = position == EtaConnectedPosition.Single || position == EtaConnectedPosition.Trailing
        val connected = etaConnectedPolygon(
            aspect = aspect,
            startRadius = if (fullStart) EtaHalfHeight else connectedInner,
            endRadius = if (fullEnd) EtaHalfHeight else connectedInner,
        )
        val pill = etaConnectedPolygon(
            aspect = aspect,
            startRadius = if (fullStart) EtaHalfHeight else pillInner,
            endRadius = if (fullEnd) EtaHalfHeight else pillInner,
        )

        // 单位空间 → 布局空间：按高度等比缩放 + 居中（圆角在两轴同倍，保持正圆）。
        val scale = size.height
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val path = Path()
        var started = false
        Morph(connected, pill).forEachCubic(selectionProgress) { cubic ->
            val ax0 = centerX + cubic.anchor0X * scale
            val ay0 = centerY + cubic.anchor0Y * scale
            if (started) path.lineTo(ax0, ay0) else path.moveTo(ax0, ay0)
            started = true
            path.cubicTo(
                centerX + cubic.control0X * scale,
                centerY + cubic.control0Y * scale,
                centerX + cubic.control1X * scale,
                centerY + cubic.control1Y * scale,
                centerX + cubic.anchor1X * scale,
                centerY + cubic.anchor1Y * scale,
            )
        }
        path.close()
        return Outline.Generic(path)
    }
}

/**
 * 单位空间里的标签多边形：宽 [aspect] × 高 1、圆心在原点，左侧圆角取 [startRadius]、
 * 右侧取 [endRadius]（`0.5` = 全圆角，即半高）。
 *
 * 顶点顺序沿用官方 `RoundedPolygon.rectangle` 的固定顺序「右下 → 左下 → 左上 → 右上」，
 * 因此逐个顶点的圆角列表为 `[右, 左, 左, 右]`。
 */
private fun etaConnectedPolygon(
    aspect: Float,
    startRadius: Float,
    endRadius: Float,
): RoundedPolygon {
    val end = CornerRounding(endRadius, 0f)
    val start = CornerRounding(startRadius, 0f)
    return RoundedPolygon.rectangle(
        width = aspect,
        height = 1f,
        perVertexRounding = listOf(end, start, start, end),
        centerX = 0f,
        centerY = 0f,
    )
}

// ---------------------------------------------------------------------------
// 圆形进度
// ---------------------------------------------------------------------------

/**
 * 圆形进度环：`wavy = true` 时使用 M3 Expressive 的波浪样式。
 *
 * @param stroke 轨道粗细；未指定默认 4dp。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EtaCircularProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    stroke: Dp = 4.dp,
    wavy: Boolean = false,
) {
    val clamped = progress.coerceIn(0f, 1f)
    val strokePx = with(LocalDensity.current) { stroke.toPx() }
    if (wavy) {
        CircularWavyProgressIndicator(
            progress = { clamped },
            modifier = modifier.size(size),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.secondaryContainer,
            stroke = Stroke(width = strokePx),
            trackStroke = Stroke(width = strokePx),
        )
    } else {
        CircularProgressIndicator(
            progress = { clamped },
            modifier = modifier.size(size),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.secondaryContainer,
            strokeWidth = stroke,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

// ---------------------------------------------------------------------------
// 装饰形状（M3E 形状色块）
// ---------------------------------------------------------------------------

/**
 * M3E 装饰形状：把官方 [MaterialShapes] 的多边形轮廓铺成一块实心色底。
 *
 * 纯装饰、不承载信息——空态页在图标下方垫一块这样的形状，给整页一点 M3E 的形状趣味
 * （与聊天空态的 M3E 色块同源）。形状取官方轮廓，不自绘多边形；颜色只引用
 * `colorScheme` 角色并降 alpha，换配色风格（单色 / 中性 / 彩虹）都协调。
 *
 * @param shape 官方形状轮廓；默认 [MaterialShapes.Puffy]（柔软的鼓包形）。
 * @param color 形状填充色；默认弱化的 `onSurfaceVariant`。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EtaDecorativeShape(
    modifier: Modifier = Modifier,
    shape: RoundedPolygon = MaterialShapes.Puffy,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
) {
    Surface(
        modifier = modifier,
        shape = shape.toShape(),
        color = color,
        contentColor = Color.Transparent,
    ) {}
}

/**
 * 空态装饰形状的候选池：官方 M3E 轮廓里挑的一组「剪影一眼看得出区别」的异形，
 * 全部是饱满的圆鼓轮廓——尖刺 / 细长类（Burst、Arrow、Pill）垫在图标下方会显得脏，不收。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val EtaDecorativeShapePool: List<RoundedPolygon> = listOf(
    MaterialShapes.Puffy,
    MaterialShapes.PuffyDiamond,
    MaterialShapes.Flower,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Clover8Leaf,
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Cookie12Sided,
    MaterialShapes.Ghostish,
    MaterialShapes.Bun,
    MaterialShapes.Gem,
    MaterialShapes.SoftBurst,
    MaterialShapes.ClamShell,
)

/**
 * 随机取一枚装饰形状轮廓：调用方用 `remember` 存住，于是「每次进页面换一枚、
 * 同一次停留内保持稳定」（页内重组成不会跳形状）。
 */
fun etaRandomDecorativeShape(): RoundedPolygon = EtaDecorativeShapePool.random()
