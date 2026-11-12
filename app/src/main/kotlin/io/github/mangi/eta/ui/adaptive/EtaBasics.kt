package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults as M3ButtonDefaults
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider as M3Slider
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 分组小标题。 */
@Composable
fun EtaSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    // KernelSU 式组标题：titleSmallEmphasized + primary，起点与分段列表条目对齐（16dp）。
    M3Text(
        text = text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(
            start = EtaGroupHorizontalPadding,
            end = EtaGroupHorizontalPadding,
            top = EtaDimens.SpaceMd,
            bottom = EtaDimens.SpaceSm,
        ),
    )
}

/**
 * 页面级说明文字块的外边距：横向 24dp 与官方弹窗正文槽同档，纵向 12dp。
 * ModelFeature / ProviderAuthMethod 等页顶说明共用，避免各写一份。
 */
internal val EtaNoticeBlockPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)

/**
 * 纯说明文字块（M3）。
 *
 * 只承载标题 + 摘要的描述性内容：不带任何容器底色，直接排在页面背景上，
 * 卡片容器留给可交互分组。
 */
@Composable
fun EtaNoticeText(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        M3Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (summary != null) {
            M3Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 承载一组设置项的卡片容器（真卡片外观；分段列表容器见 EtaSegmentedList）。 */
@Composable
fun EtaCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // 官方 Card 默认值：容器 surfaceContainerHighest、形状 shapes.medium，不再覆盖。
    M3Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            content()
        }
    }
}

/**
 * 文本层级（EtaText 专用，设置/终端等「表单型」界面的四档）。
 *
 * 与 [EtaType]（主页/侧栏的六档语义字阶）的关系：Body/Secondary/Footnote 在
 * EtaText 内部委托到 [etaTextStyle] 对应档实现；仅 [EtaTextStyle.Title] 有意取
 * 更小的 titleMedium——EtaType.Title 指弹窗/抽屉大标题（titleLarge）。新代码
 * 优先直接用 [etaTextStyle]。
 */
enum class EtaTextStyle { Title, Body, Secondary, Footnote }

/** 自适应文本。 */
@Composable
fun EtaText(
    text: String,
    modifier: Modifier = Modifier,
    style: EtaTextStyle = EtaTextStyle.Body,
    secondary: Boolean = false,
    color: Color? = null,
    textAlign: TextAlign? = null,
) {
    val effective = if (secondary) EtaTextStyle.Secondary else style
    // Body/Secondary/Footnote 与 etaTextStyle(EtaType) 对应档取值完全一致，委托单源；
    // Title 是文档化的有意分档（titleMedium），不走 EtaType.Title（titleLarge）。
    val textStyle = when (effective) {
        EtaTextStyle.Title -> MaterialTheme.typography.titleMedium
        EtaTextStyle.Body -> etaTextStyle(EtaType.Body)
        EtaTextStyle.Secondary -> etaTextStyle(EtaType.Secondary)
        EtaTextStyle.Footnote -> etaTextStyle(EtaType.Caption)
    }
    val defaultColor = when (effective) {
        EtaTextStyle.Title, EtaTextStyle.Body -> MaterialTheme.colorScheme.onSurface
        EtaTextStyle.Secondary, EtaTextStyle.Footnote -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    M3Text(
        text = text,
        modifier = modifier,
        style = textStyle,
        color = color ?: defaultColor,
        textAlign = textAlign,
    )
}

/** 自适应开关。 */
@Composable
fun EtaSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // KernelSU 式 Expressive 开关：拇指常驻状态图标（选中 Check / 关闭 Close）
    // + 完整禁用态配色，见 EtaButtons.kt。
    EtaExpressiveSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
    )
}

/** 自适应滑块。[keyPoints] 在滑轨上绘制刻度标记。 */
@Composable
fun EtaSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyPoints: List<Float>? = null,
    magnetThreshold: Float = 0.01f,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    // 官方新版 Slider API：SliderState + onValueChange（旧的 value 重载已废弃）。
    val state = rememberSliderState(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        trackRange = valueRange,
    )
    LaunchedEffect(value, valueRange) {
        val coerced = value.coerceIn(valueRange.start, valueRange.endInclusive)
        if (state.value != coerced) state.value = coerced
    }
    BoxWithConstraints(modifier = modifier) {
        M3Slider(
            state = state,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
        )
        keyPoints?.let { points ->
            if (points.isEmpty()) return@let
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }
            val thumbRadiusPx = with(density) { ThumbRadius.toPx() }
            val trackWidthPx = (widthPx - 2 * thumbRadiusPx).coerceAtLeast(0f)
            val rangeSpan = valueRange.endInclusive - valueRange.start
            val centerY = heightPx / 2f
            val tickHalfHeight = with(density) { TickHeight.toPx() } / 2f
            val tickStroke = with(density) { TickWidth.toPx() }
            val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = TickAlpha)

            Canvas(modifier = Modifier.matchParentSize()) {
                points.forEach { point ->
                    if (point < valueRange.start || point > valueRange.endInclusive) return@forEach
                    val fraction = (point - valueRange.start) / rangeSpan
                    val x = thumbRadiusPx + fraction * trackWidthPx
                    drawLine(
                        color = tickColor,
                        start = Offset(x, centerY - tickHalfHeight),
                        end = Offset(x, centerY + tickHalfHeight),
                        strokeWidth = tickStroke,
                    )
                }
            }
        }
    }
}

private val ThumbRadius = 10.dp
private val TickHeight = 4.dp
private val TickWidth = 1.5.dp
private const val TickAlpha = 0.5f

/** 自适应主操作按钮。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EtaTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    // 官方按钮默认形状为胶囊（shape style "full"），不额外覆盖。
    // shapes 必传（AGENTS §2.5 容器型按钮规则）：官方 Button 的 plain 重载没有按压
    // morph，shapes() 才带上按下压向 CornerSmall 的 ButtonShapes。
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shapes = M3ButtonDefaults.shapes(),
        // 破坏性动作取官方 error 角色色，与各列表里的「删除」菜单项统一。
        colors = if (destructive) {
            M3ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            M3ButtonDefaults.buttonColors()
        },
    ) {
        M3Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * 状态圆点：8dp，[active] 为真取 primary，否则取弱化的描边色（未就绪/未授权）。
 *
 * 直径与 [EtaDimens.StatusDotSize]（会话列表运行状态点，6dp）是两个语义：这里标注
 * 分段区块的就绪态，那边标注会话的运行态，各自成立，不合并取值。
 */
@Composable
fun EtaStatusDot(
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(modifier = modifier.size(EtaStatusDotDiameter).background(color, CircleShape))
}

private val EtaStatusDotDiameter = 8.dp
