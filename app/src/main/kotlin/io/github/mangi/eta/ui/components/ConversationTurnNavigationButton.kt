package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics

/**
 * 会话跳转按钮组：上一轮 / 下一轮两颗连体图标按钮。
 *
 * 只渲染「该方向还有轮次」的那半颗：没有目标的方向直接不渲染，不出现禁用态，
 * 因此四角整圆的 tertiaryContainer 只表示「可点击 / 可长按」一种语义。
 *
 * 无缝衔接：两格几何恒定（行宽不随半颗增删变化，避免「先播完退场、再整体跳位」的两段式）。
 * 消失的半颗在自己槽内淡出 + 缩小；留下的那颗同一时刻平移进 / 让出右槽，并按官方同一机制
 * （CornerBasedShape 四角像素域插值）在连体半颗与独占整圆之间形变，规格取
 * MaterialTheme.motionScheme 的 spatial 规格。
 *
 * 长按语义不变：长按「上一轮」跳到会话开头、长按「下一轮」跳到会话末尾。
 *
 * 投影：整组一份 3dp（官方 Level2）阴影，轮廓由两颗按钮当前形状合成一条路径
 * （见 [TurnNavigationGroupShape]），随形变与位移一起动，退场的那颗影子同步缩掉。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationTurnNavigationButton(
    upEnabled: Boolean,
    downEnabled: Boolean,
    visible: Boolean,
    onStep: (ConversationNavigationDirection) -> Unit,
    onEdge: (ConversationNavigationDirection) -> Unit,
    modifier: Modifier = Modifier,
) {
    // M3 Expressive：透明度走 Effects（临界阻尼、无过冲），缩放走 Spatial（轻微过冲）。
    val fadeInSpec: FiniteAnimationSpec<Float> =
        MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val scaleInSpec: FiniteAnimationSpec<Float> =
        MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val fadeOutSpec: FiniteAnimationSpec<Float> =
        MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val scaleOutSpec: FiniteAnimationSpec<Float> =
        MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    // 半颗的进退场与「留下的那颗平移进右槽」共用这批官方规格：退场与位移同时起步，
    // 才是一次连贯的衔接。位移属空间动效，取 expressive 的 spatial 规格。
    val halfEnter: EnterTransition = fadeIn(fadeInSpec) + scaleIn(scaleInSpec, initialScale = 0.82f)
    val halfExit: ExitTransition = fadeOut(fadeOutSpec) + scaleOut(scaleOutSpec, targetScale = 0.86f)
    val shiftSpec: FiniteAnimationSpec<Dp> =
        MaterialTheme.motionScheme.defaultSpatialSpec<Dp>()
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = halfEnter,
        exit = halfExit,
    ) {
        val colors = ToggleButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            checkedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        // 两格几何恒定：行宽不随半颗增删变化，避免「先播完退场、再整体跳位」的两段式。
        // 每格在自己槽内淡入淡出 + 缩放（不参与布局变化），留下的那颗同一时刻平移进右槽。
        val paired = upEnabled && downEnabled
        val spacing = ButtonGroupDefaults.ConnectedSpaceBetween
        val slot = IconButtonDefaults.smallContainerSize().width
        val leadingShift = animateDpAsState(
            targetValue = if (upEnabled && !downEnabled) slot + spacing else 0.dp,
            animationSpec = shiftSpec,
            label = "turnNavigationShift",
        )
        // 内侧两角「连体 ↔ 独占整圆」的形变进度提到这一层：两颗按钮与整组投影共用同一进度，
        // 影子才会跟着按钮一起变形（各自驱动会差半拍）。
        val morphProgress = animateFloatAsState(
            targetValue = if (paired) 0f else 1f,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "turnNavigationShapeMorph",
        )
        // 半颗的「在场度」：影子随按钮一起退场（按钮淡出时影子同步缩到零），
        // 否则空槽里会留下一块残影。
        val upPresence = remember { Animatable(if (upEnabled) 1f else 0f) }
        LaunchedEffect(upEnabled) {
            if (upEnabled) {
                upPresence.animateTo(1f, fadeInSpec)
            } else {
                upPresence.animateTo(0f, fadeOutSpec)
            }
        }
        val downPresence = remember { Animatable(if (downEnabled) 1f else 0f) }
        LaunchedEffect(downEnabled) {
            if (downEnabled) {
                downPresence.animateTo(1f, fadeInSpec)
            } else {
                downPresence.animateTo(0f, fadeOutSpec)
            }
        }
        // 官方连体组的内侧内角（ConnectedButtonGroupSmallTokens.InnerCornerCornerSize），
        // 直接从官方形状取，影子才不会和按钮本身脱节。
        val density = LocalDensity.current
        val slotPx = with(density) { slot.toPx() }
        val innerCornerPx = with(density) {
            (ButtonGroupDefaults.connectedLeadingButtonShapes().shape as CornerBasedShape)
                .topEnd.toPx(Size(slotPx, slotPx), density)
        }
        // 整组一个投影：把两颗按钮当前轮廓合成一条路径交给 shadow，
        // 接缝处才不会叠出双重暗线（两颗各自投影必然在 2dp 间隙处叠暗）。
        val groupShape = remember {
            TurnNavigationGroupShape(
                slot = slot,
                spacing = spacing,
                innerCornerPx = innerCornerPx,
                leadingOffset = { leadingShift.value },
                morph = { morphProgress.value },
                leadingPresence = { upPresence.value },
                trailingPresence = { downPresence.value },
            )
        }
        Box(
            modifier = Modifier.shadow(
                elevation = 3.dp,
                shape = groupShape,
                clip = false,
            ),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                NavigationHalfSlot(present = upEnabled, shiftX = leadingShift.value, enter = halfEnter, exit = halfExit) {
                    M3TurnNavigationToggle(
                        direction = ConversationNavigationDirection.Up,
                        leading = true,
                        morphProgress = morphProgress,
                        colors = colors,
                        onStep = onStep,
                        onEdge = onEdge,
                    )
                }
                NavigationHalfSlot(present = downEnabled, shiftX = 0.dp, enter = halfEnter, exit = halfExit) {
                    M3TurnNavigationToggle(
                        direction = ConversationNavigationDirection.Down,
                        leading = false,
                        morphProgress = morphProgress,
                        colors = colors,
                        onStep = onStep,
                        onEdge = onEdge,
                    )
                }
            }
        }
    }
}

/**
 * 固定槽位：两格几何恒定，不随半颗增删改变布局，退场动画就在槽内播完，
 * 因此不会出现「先播完退场、再跳位」的两段式。
 * [shiftX] 用于让留下的那颗平移进 / 让出右槽，位移与退场同一时刻起步。
 */
@Composable
private fun NavigationHalfSlot(
    present: Boolean,
    shiftX: Dp,
    enter: EnterTransition,
    exit: ExitTransition,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(IconButtonDefaults.smallContainerSize())
            .offset(x = shiftX),
    ) {
        AnimatedVisibility(visible = present, enter = enter, exit = exit) {
            content()
        }
    }
}

/**
 * 连体组里的单颗跳转按钮（KernelSU AppProfile 同款官方模式）：官方 `ToggleButton` 承担
 * 容器、连体形状、涟漪与最小可点区域，尺寸取官方 `smallContainerSize`（40dp），
 * `contentPadding(0.dp)` 清掉文字按钮的水平内边距。
 *
 * 长按检测放在官方按钮内容里（官方按钮没有 onLongClick 槽位，material3 1.5.0-alpha29 中
 * 只有 ListItem 提供），只观察指针不消费，点击仍由官方按钮处理；长按语义由
 * ChatInputLongPressSlot 自己声明的 onLongClick key 提供，不要在按钮 modifier 上补一份，
 * 否则父非空会整份遮掉共享助手的语义。
 */
@Composable
private fun M3TurnNavigationToggle(
    direction: ConversationNavigationDirection,
    leading: Boolean,
    morphProgress: State<Float>,
    colors: ToggleButtonColors,
    onStep: (ConversationNavigationDirection) -> Unit,
    onEdge: (ConversationNavigationDirection) -> Unit,
) {
    val down = direction == ConversationNavigationDirection.Down
    val stepLabel = stringResource(if (down) R.string.chat_next_user_message else R.string.chat_previous_user_message)
    val edgeLabel = stringResource(if (down) R.string.chat_go_to_bottom else R.string.chat_go_to_top)
    val view = LocalView.current
    // 形变（morph）自己驱动，原因如下。
    // 反编译确认官方 ToggleButton 内部确实会 morph：shapeByInteraction → material3.internal
    // 的 AnimatedShape.kt/rememberAnimatedShape（对我们的形状走的就是同族的四角插值）。
    // 但它把规格写死为 MotionSchemeKeyTokens.FastSpatial（约百毫秒），而这里真正变化的只有
    // 「内侧两个角」（官方连体内角 CornerSmall = 8dp → 独占整圆 20dp），变化量小 + 时长极短，
    // 于是看起来近似瞬变。故按官方同一机制自己驱动，规格改用 MaterialTheme.motionScheme 的
    // spatial 规格，形变才看得出。
    // 形状对象刻意不是 CornerBasedShape（见 CornerMorphShape），官方便不会再叠一层自己的动画。
    //
    // 配对态直接用官方连体形状（内侧角与按下态几何都保持官方值）；独占态只把内侧角补成整圆
    // （半径 = 方钮半边长）。两态同族，按像素域插值即可。
    val officialShapes = if (leading) {
        ButtonGroupDefaults.connectedLeadingButtonShapes()
    } else {
        ButtonGroupDefaults.connectedTrailingButtonShapes()
    }
    val connected = officialShapes.shape as CornerBasedShape
    val fullCorner = CornerSize(IconButtonDefaults.smallContainerSize().height / 2)
    val standalone: CornerBasedShape = if (leading) {
        RoundedCornerShape(
            topStart = connected.topStart,
            bottomStart = connected.bottomStart,
            topEnd = fullCorner,
            bottomEnd = fullCorner,
        )
    } else {
        RoundedCornerShape(
            topEnd = connected.topEnd,
            bottomEnd = connected.bottomEnd,
            topStart = fullCorner,
            bottomStart = fullCorner,
        )
    }
    val shape = remember(connected, standalone, morphProgress) {
        CornerMorphShape(start = connected, stop = standalone) { morphProgress.value }
    }
    val shapes = ToggleButtonShapes(
        shape = shape,
        pressedShape = officialShapes.pressedShape,
        checkedShape = shape,
    )
    ToggleButton(
        checked = false,
        onCheckedChange = { TouchHaptics.click(view); onStep(direction) },
        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
        buttonSize = ToggleButtonSize.Small,
        shapes = shapes,
        contentPadding = PaddingValues(0.dp),
        colors = colors,
        // 整组只出一份投影：官方 elevation 一律置空（默认 hover 态还有 1dp 自己的影子，
        // 会和整组影子打架），影子统一由外层 TurnNavigationGroupShape 出。
        elevation = null,
    ) {
        ChatInputLongPressSlot(
            enabled = true,
            onLongPress = { onEdge(direction) },
            longClickLabel = edgeLabel,
        ) {
            Icon(
                imageVector = if (down) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                contentDescription = stepLabel,
                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
            )
        }
    }
}

/**
 * 把两个同族圆角形状的四个角在「像素域」线性插值，得到形变中途的形状。
 *
 * 机制与官方 material3.internal.AnimatedShapeState 完全一致（它同样是把 CornerBasedShape
 * 的四个角按 progress 插值），差别只是规格由调用方给——官方那份被写死成 FastSpatial，太快。
 *
 * 刻意实现为普通 [Shape] 而非 [CornerBasedShape]：官方 ToggleButton 的 shapeByInteraction
 * 只对 CornerBasedShape 走自己的形变动画，这样一来它就不会在外部再叠一层。
 * [progress] 在 createOutline 中才读取（布局/绘制阶段），因此动画期间不会引起重组。
 */
private class CornerMorphShape(
    private val start: CornerBasedShape,
    private val stop: CornerBasedShape,
    private val progress: () -> Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val fraction = progress().coerceIn(0f, 1f)
        val morphed = when {
            fraction <= 0f -> start
            fraction >= 1f -> stop
            else -> lerpCorners(start, stop, size, density, fraction)
        }
        return morphed.createOutline(size, layoutDirection, density)
    }
}

/** 四角各自在像素域插值后重新组装成 [RoundedCornerShape]（与官方插值行为一致）。 */
private fun lerpCorners(
    start: CornerBasedShape,
    stop: CornerBasedShape,
    size: Size,
    density: Density,
    fraction: Float,
): RoundedCornerShape {
    fun corner(
        from: CornerSize,
        to: CornerSize,
    ): CornerSize = with(density) {
        CornerSize(lerp(from.toPx(size, density), to.toPx(size, density), fraction).toDp())
    }
    return RoundedCornerShape(
        topStart = corner(start.topStart, stop.topStart),
        topEnd = corner(start.topEnd, stop.topEnd),
        bottomEnd = corner(start.bottomEnd, stop.bottomEnd),
        bottomStart = corner(start.bottomStart, stop.bottomStart),
    )
}

/**
 * 整组投影的轮廓：把「上一轮 / 下一轮」两颗按钮当前轮廓合成一条路径。
 *
 * 整组只出一份影子，而不是两颗各自投影——官方连体组两半之间有 2dp 间隙，
 * 各自投影必然在缝里叠出一条暗线；合成一条路径则只有一份干净的影子。
 *
 * 轮廓跟着状态一起动（读的都是动画中的实时值，不引起重组）：
 * - 留下的那颗平移进右槽（[leadingOffset]），内侧两角从官方连体内角插值到整圆（[morph]）；
 * - 退场的那颗按 [leadingPresence] / [trailingPresence] 缩到零，影子随之消失，空槽不留残影。
 *
 * minSdk 34 ≥ API 30，自定义路径轮廓可以走 RenderNode.setPath，投影按真实轮廓绘制。
 */
private class TurnNavigationGroupShape(
    private val slot: Dp,
    private val spacing: Dp,
    private val innerCornerPx: Float,
    private val leadingOffset: () -> Dp,
    private val morph: () -> Float,
    private val leadingPresence: () -> Float,
    private val trailingPresence: () -> Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val slotPx = with(density) { slot.toPx() }
        val spacingPx = with(density) { spacing.toPx() }
        val fraction = morph().coerceIn(0f, 1f)
        // 外侧两角是官方整圆（= 半边长，官方角是 100% 百分比，落到路径上按半边长收敛）；
        // 内侧两角从官方连体内角插值到整圆，与按钮自身的形变进度一致。
        val outer = slotPx / 2f
        val inner = lerp(innerCornerPx, outer, fraction)
        val path = Path()
        // 上一轮：外侧（左）两角整圆，内侧（右）两角随形变插值。
        val leadingLeft = with(density) { leadingOffset().toPx() }
        addScaledRoundRect(
            path = path,
            left = leadingLeft,
            top = 0f,
            right = leadingLeft + slotPx,
            bottom = size.height,
            topLeft = outer,
            topRight = inner,
            bottomRight = inner,
            bottomLeft = outer,
            scale = leadingPresence(),
        )
        // 下一轮：外侧（右）两角整圆，内侧（左）两角随形变插值。
        val trailingLeft = slotPx + spacingPx
        addScaledRoundRect(
            path = path,
            left = trailingLeft,
            top = 0f,
            right = trailingLeft + slotPx,
            bottom = size.height,
            topLeft = inner,
            topRight = outer,
            bottomRight = outer,
            bottomLeft = inner,
            scale = trailingPresence(),
        )
        return Outline.Generic(path)
    }
}

/**
 * 把一个圆角矩形按 [scale] 围绕自身中心缩放后并入 [path]（[scale] 为 0 时整颗不画）。
 * 影子随按钮退场时就是这么缩掉的。
 */
private fun addScaledRoundRect(
    path: Path,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    topLeft: Float,
    topRight: Float,
    bottomRight: Float,
    bottomLeft: Float,
    scale: Float,
) {
    val factor = scale.coerceIn(0f, 1f)
    if (factor <= 0f) return
    val centerX = (left + right) / 2f
    val centerY = (top + bottom) / 2f
    val halfWidth = (right - left) / 2f * factor
    val halfHeight = (bottom - top) / 2f * factor
    path.addRoundRect(
        RoundRect(
            left = centerX - halfWidth,
            top = centerY - halfHeight,
            right = centerX + halfWidth,
            bottom = centerY + halfHeight,
            topLeftCornerRadius = CornerRadius(topLeft * factor, topLeft * factor),
            topRightCornerRadius = CornerRadius(topRight * factor, topRight * factor),
            bottomRightCornerRadius = CornerRadius(bottomRight * factor, bottomRight * factor),
            bottomLeftCornerRadius = CornerRadius(bottomLeft * factor, bottomLeft * factor),
        )
    )
}

