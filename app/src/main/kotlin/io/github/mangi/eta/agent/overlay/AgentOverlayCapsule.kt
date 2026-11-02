package io.github.mangi.eta.agent.overlay

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySnapshot
import io.github.mangi.eta.agent.virtualdisplay.VirtualTouchEffect
import io.github.mangi.eta.ui.components.VirtualTouchFocusLayer
import kotlin.math.hypot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 顶部状态胶囊：收起时只显示一句当前步骤（后台虚拟屏激活时右侧镶嵌 18×32.dp 实时微缩镜），
 * 点击展开补充、暂停、停止及画廊装裱式虚拟屏交互视窗。
 *
 * 位置固定在状态栏下方居中，不随 Agent 的点击位置移动，避免和目标控件重叠。
 * 状态文字只在语义变化时以上滑淡入切换，流式正文不进入胶囊。
 */
@Composable
internal fun AgentOverlayCapsule(
    state: AgentOverlayState,
    expanded: Boolean,
    yielded: Boolean,
    onToggleExpanded: () -> Unit,
    onCollapsedSettled: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSupplementModeChange: (Boolean) -> Unit,
    onSupplement: (String) -> Unit,
    virtualDisplay: VirtualDisplaySnapshot = VirtualDisplaySnapshot.Inactive,
    onTakeOverVirtualDisplay: () -> Unit = {},
    onResumeVirtualDisplay: () -> Unit = {},
    onFullscreenVirtualDisplay: () -> Unit = {},
    onTapVirtualCanvas: (Float, Float, Boolean) -> Unit = { _, _, _ -> },
    onSwipeVirtualCanvas: (Float, Float, Float, Float, Int) -> Unit = { _, _, _, _, _ -> },
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    var supplementMode by remember { mutableStateOf(false) }
    var supplementText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun setSupplementMode(enabled: Boolean) {
        onSupplementModeChange(enabled)
        supplementMode = enabled
        if (!enabled) supplementText = ""
    }

    // 收起键盘后再切换窗口焦点，避免输入法在窗口失焦瞬间闪退或残留。
    fun leaveSupplementMode(then: () -> Unit = {}) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            delay(80)
            setSupplementMode(false)
            then()
        }
    }

    LaunchedEffect(expanded) {
        if (!expanded && supplementMode) leaveSupplementMode()
    }

    // 为 Agent 手势让路：离开时快速上移收小淡出，回来时用弹簧落回，只改绘制层不触发重排。
    val clearance by animateFloatAsState(
        targetValue = if (yielded) 1f else 0f,
        animationSpec = if (yielded) {
            tween(durationMillis = 180, easing = FastOutLinearInEasing)
        } else {
            spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "capsule_clearance",
    )
    val accent by animateColorAsState(
        targetValue = phaseAccent(state.phase),
        animationSpec = tween(240),
        label = "capsule_accent",
    )
    val statusText = state.status.localizedText()
    val expandDescription = stringResource(R.string.overlay_capsule_toggle, statusText)
    // 展开进度只驱动绘制层的透明度与位移，不参与测量；控制区用 AnimatedVisibility 只在两端各测一次。
    val expansion by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
        label = "capsule_expansion",
        finishedListener = { value -> if (value == 0f) onCollapsedSettled() },
    )

    val targetCapsuleWidth by animateDpAsState(
        targetValue = when {
            virtualDisplay.active && expanded -> CapsuleStageWidth
            virtualDisplay.active -> CapsuleMicroLensWidth
            else -> CapsuleDefaultWidth
        },
        animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
        label = "capsule_width",
    )
    val targetCapsuleCorner by animateDpAsState(
        targetValue = if (virtualDisplay.active && expanded) CapsuleStageCorner else CapsuleCorner,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
        label = "capsule_corner",
    )

    // 展开体三态互切：舞台 / 控制 / 补充都走同一套交叉过渡，避免舞台与输入框瞬间互换。
    val bodyMode = when {
        supplementMode -> CapsuleBody.Supplement
        virtualDisplay.active -> CapsuleBody.Stage
        else -> CapsuleBody.Controls
    }
    val bodySpatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val bodyFastSpatial = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val bodySizeSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    val bodyOrigin = TransformOrigin(0.5f, 0f)

    AnimatedVisibility(
        visible = visible && !virtualDisplay.materializingToMain,
        // 窗口本身按内容分档定死不随动画改尺寸，位移会被窗口裁掉，所以入场/退场走缩放+淡入淡出。
        enter = fadeIn(tween(180)) + scaleIn(
            initialScale = 0.92f,
            transformOrigin = TransformOrigin(0.5f, 0f),
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        ),
        exit = fadeOut(tween(120)) + scaleOut(
            targetScale = 0.92f,
            transformOrigin = TransformOrigin(0.5f, 0f),
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .width(targetCapsuleWidth)
                    .graphicsLayer {
                        // 点击时轻微回弹，展开后保持原尺寸；只改绘制层，不触发重新布局。
                        val press = 1f - 0.03f * (1f - expansion) * expansion * 4f
                        val shrink = 1f - 0.08f * clearance
                        scaleX = press * shrink
                        scaleY = press * shrink
                        translationY = -12.dp.toPx() * clearance
                        alpha = 1f - clearance
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .shadow(
                        elevation = 10.dp,
                        shape = RoundedCornerShape(targetCapsuleCorner),
                        ambientColor = accent,
                        spotColor = accent,
                    )
                    .clip(RoundedCornerShape(targetCapsuleCorner))
                    .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f))
                    .border(
                        width = 0.75.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(targetCapsuleCorner),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onToggleExpanded,
                        )
                        .semantics { contentDescription = expandDescription }
                        .padding(
                            start = 12.dp,
                            end = if (virtualDisplay.active && !expanded) 10.dp else 16.dp,
                            top = 9.dp,
                            bottom = 9.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhaseIndicator(phase = state.phase, accent = accent)
                    Spacer(Modifier.width(9.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        AnimatedContent(
                            targetState = statusText,
                            transitionSpec = {
                                (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                                    (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(160))) using
                                    SizeTransform(clip = true) { _, _ -> snap() }
                            },
                            label = "capsule_status",
                        ) { text ->
                            Text(
                                text = text,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DetailLine(detail = state.detailText.takeIf { state.phase == AgentOverlayPhase.RUNNING }.orEmpty())
                    }
                    AnimatedVisibility(
                        visible = virtualDisplay.active && !expanded,
                        enter = fadeIn(tween(180)),
                        exit = fadeOut(tween(120)),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(8.dp))
                            VirtualDisplayMicroLens(snapshot = virtualDisplay, accent = accent)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(
                        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                        clip = true,
                    ) + fadeIn(tween(180, delayMillis = 40)),
                    exit = shrinkVertically(
                        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMedium),
                        clip = true,
                    ) + fadeOut(tween(90)),
                ) {
                    Column(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                            .graphicsLayer { translationY = (1f - expansion) * -6.dp.toPx() },
                    ) {
                        AnimatedContent(
                            targetState = bodyMode,
                            contentAlignment = Alignment.TopCenter,
                            transitionSpec = {
                                val enter = fadeIn(tween(200)) + scaleIn(
                                    initialScale = 0.94f,
                                    transformOrigin = bodyOrigin,
                                    animationSpec = bodySpatial,
                                )
                                val exit = fadeOut(tween(140)) + scaleOut(
                                    targetScale = 0.94f,
                                    transformOrigin = bodyOrigin,
                                    animationSpec = bodyFastSpatial,
                                )
                                (enter togetherWith exit) using SizeTransform(clip = false) { _, _ -> bodySizeSpatial }
                            },
                            label = "capsule_body",
                        ) { mode ->
                            when (mode) {
                                CapsuleBody.Stage -> Column(modifier = Modifier.fillMaxWidth()) {
                                    VirtualDisplayStageSection(
                                        snapshot = virtualDisplay,
                                        accent = accent,
                                        isPaused = state.phase == AgentOverlayPhase.PAUSED,
                                        onTakeOver = onTakeOverVirtualDisplay,
                                        onResumeAuto = onResumeVirtualDisplay,
                                        onFullscreen = onFullscreenVirtualDisplay,
                                        onTapCanvas = onTapVirtualCanvas,
                                        onSwipeCanvas = onSwipeVirtualCanvas,
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    CapsuleActions(
                                        phase = state.phase,
                                        onSupplement = { setSupplementMode(true) },
                                        onPause = onPause,
                                        onResume = onResume,
                                        onStop = onStop,
                                    )
                                }
                                CapsuleBody.Controls -> CapsuleActions(
                                    phase = state.phase,
                                    onSupplement = { setSupplementMode(true) },
                                    onPause = onPause,
                                    onResume = onResume,
                                    onStop = onStop,
                                )
                                CapsuleBody.Supplement -> SupplementInput(
                                    value = supplementText,
                                    onValueChange = { supplementText = it },
                                    onCancel = { leaveSupplementMode() },
                                    onSend = {
                                        val text = supplementText.trim()
                                        if (text.isNotBlank()) leaveSupplementMode { onSupplement(text) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 展开体互切的三态；舞台与输入框共用尺寸过渡，不靠条件分支硬切。 */
private enum class CapsuleBody { Stage, Controls, Supplement }

/** 补充 / 暂停恢复 / 停止：暂停与恢复图标同在 40×34.dp 槽位内交叉淡化。 */
@Composable
private fun CapsuleActions(
    phase: AgentOverlayPhase,
    onSupplement: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        CapsuleAction(
            icon = Icons.Rounded.Edit,
            label = stringResource(R.string.overlay_supplement),
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = onSupplement,
        )
        AnimatedContent(
            targetState = phase,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "capsule_phase_action",
        ) { current ->
            when (current) {
                AgentOverlayPhase.RUNNING -> CapsuleAction(
                    icon = Icons.Rounded.Pause,
                    label = stringResource(R.string.overlay_pause),
                    tint = MaterialTheme.colorScheme.onSurface,
                    onClick = onPause,
                )
                AgentOverlayPhase.PAUSED -> CapsuleAction(
                    icon = Icons.Rounded.PlayArrow,
                    label = stringResource(R.string.overlay_resume),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onResume,
                )
                else -> Spacer(Modifier.size(0.dp))
            }
        }
        CapsuleAction(
            icon = Icons.Rounded.Stop,
            label = stringResource(R.string.action_stop),
            tint = MaterialTheme.colorScheme.error,
            onClick = onStop,
        )
    }
}

/** 运行中是旋转的弧形光点，暂停为静止空心环，结束为实心点；只在运行时持有帧动画。 */
@Composable
private fun PhaseIndicator(phase: AgentOverlayPhase, accent: Color) {
    AnimatedContent(
        targetState = phase,
        transitionSpec = {
            (fadeIn(tween(200)) + scaleIn(initialScale = 0.7f)) togetherWith
                (fadeOut(tween(140)) + scaleOut(targetScale = 0.7f))
        },
        label = "capsule_phase_glyph",
    ) { current ->
        PhaseGlyph(phase = current, accent = accent)
    }
}

@Composable
private fun PhaseGlyph(phase: AgentOverlayPhase, accent: Color) {
    val size = 14.dp
    if (phase != AgentOverlayPhase.RUNNING) {
        Box(
            modifier = Modifier
                .size(size)
                .drawBehind {
                    val radius = this.size.minDimension / 2f
                    if (phase == AgentOverlayPhase.PAUSED) {
                        drawCircle(accent, radius * 0.78f, style = Stroke(radius * 0.32f))
                    } else {
                        drawCircle(accent, radius * 0.6f)
                    }
                },
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "capsule_indicator")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "capsule_indicator_rotation",
    )
    val breathe by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "capsule_indicator_breathe",
    )
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer { rotationZ = rotation }
            .drawBehind {
                val radius = this.size.minDimension / 2f
                val center = Offset(radius, radius)
                drawCircle(accent.copy(alpha = 0.18f), radius)
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, accent), center),
                    startAngle = 0f,
                    sweepAngle = 300f,
                    useCenter = false,
                    style = Stroke(radius * 0.34f, cap = StrokeCap.Round),
                )
                drawCircle(accent.copy(alpha = breathe), radius * 0.26f, center)
            },
    )
}

/**
 * 状态副行：单行、从左侧裁掉旧内容，让最新的字始终可见。
 * 流式增量只改文字，不做逐字动画；行的出现与消失用淡入淡出，避免胶囊上下跳动。
 */
@Composable
private fun DetailLine(detail: String) {
    val shown = detail.isNotBlank()
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(220), label = "detail_alpha")
    var lastDetail by remember { mutableStateOf("") }
    if (shown) lastDetail = detail
    Text(
        text = lastDetail,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        maxLines = 1,
        // 起始端省略：保留尾部最新的推理，像滚动字幕一样向前推进。
        overflow = TextOverflow.StartEllipsis,
        modifier = Modifier
            .padding(top = 1.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

@Composable
private fun CapsuleAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = tint)
    }
}

/**
 * 镶嵌在收起态顶部胶囊右侧的 18×32.dp 实时微缩取景器（Micro-Lens）。
 *
 * 平时安静展示虚拟屏缩略画面；当虚拟屏内发生点击或滑动时，外圈泛起柔和的呼吸微光。
 */
@Composable
internal fun VirtualDisplayMicroLens(
    snapshot: VirtualDisplaySnapshot,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(snapshot.touchEffect?.sequence) {
        if (snapshot.touchEffect != null) {
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(durationMillis = 420, easing = FastOutSlowInEasing))
        }
    }

    val borderColor = lerp(
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
        accent.copy(alpha = 0.78f),
        pulse.value,
    )
    val lensShape = RoundedCornerShape(6.dp)

    Box(
        modifier = modifier
            .size(width = 18.dp, height = 32.dp)
            .graphicsLayer {
                val scale = 1f + 0.06f * pulse.value
                scaleX = scale
                scaleY = scale
            }
            .clip(lensShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(width = 0.85.dp, color = borderColor, shape = lensShape),
        contentAlignment = Alignment.Center,
    ) {
        val frame = snapshot.frame
        // 首帧到达或副屏释放时画面与占位点交叉淡化，避免微缩镜里画面瞬切。
        val frameAlpha by animateFloatAsState(
            targetValue = if (frame != null) 1f else 0f,
            animationSpec = tween(220),
            label = "lens_frame_alpha",
        )
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = frameAlpha },
            )
        }
        if (frameAlpha < 1f) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent.copy(alpha = 0.55f * (1f - frameAlpha))),
            )
        }
        val effect = snapshot.touchEffect
        if (effect != null && pulse.value > 0.02f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val (nx, ny) = when (effect) {
                    is VirtualTouchEffect.Tap -> effect.normalizedX to effect.normalizedY
                    is VirtualTouchEffect.Swipe -> effect.endX to effect.endY
                }
                val center = Offset(nx * size.width, ny * size.height)
                drawCircle(
                    color = accent.copy(alpha = 0.85f * pulse.value),
                    radius = 2.2.dp.toPx(),
                    center = center,
                )
            }
        }
    }
}

/**
 * 画廊装裱式虚拟屏视窗（Gallery Matte Stage）。
 *
 * 采用外框 28.dp + 10.dp 留白衬底 + 内画框 18.dp 的同心圆角装裱设计，
 * 支持用户手指直接在画布上点按/滑动接管，以及一键全屏投射至物理主屏。
 */
@Composable
internal fun VirtualDisplayStageSection(
    snapshot: VirtualDisplaySnapshot,
    accent: Color,
    isPaused: Boolean,
    onTakeOver: () -> Unit,
    onResumeAuto: () -> Unit,
    onFullscreen: () -> Unit,
    onTapCanvas: (Float, Float, Boolean) -> Unit,
    onSwipeCanvas: (Float, Float, Float, Float, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val frameShape = RoundedCornerShape(StageCanvasCorner)
    // 接管/暂停切换时画框颜色渐渡，与胶囊其余状态色保持同一节奏。
    val borderColor by animateColorAsState(
        targetValue = if (snapshot.userControlling || isPaused) {
            accent.copy(alpha = 0.42f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.70f)
        },
        animationSpec = tween(220),
        label = "stage_border_color",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = StageMattePadding, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 画廊留白内衬画布（Concentric 18.dp Corner） ────────────────────
        Box(
            modifier = Modifier
                .height(StageCanvasHeight)
                .aspectRatio(snapshot.aspectRatio, matchHeightConstraintsFirst = true)
                .clip(frameShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(width = 0.75.dp, color = borderColor, shape = frameShape)
                .pointerInput(snapshot.displayId, snapshot.userControlling, isPaused) {
                    val touchSlopPx = viewConfiguration.touchSlop
                    val longPressTimeoutMs = viewConfiguration.longPressTimeoutMillis
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val startPos = down.position
                        val downAt = SystemClock.uptimeMillis()
                        var lastPos = startPos
                        var dragged = false

                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                            if (change != null) {
                                lastPos = change.position
                                if (hypot(lastPos.x - startPos.x, lastPos.y - startPos.y) > touchSlopPx) {
                                    dragged = true
                                }
                                change.consume()
                            }
                        } while (event.changes.any { it.pressed })

                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        if (!snapshot.userControlling && !isPaused) {
                            onTakeOver()
                        }
                        val elapsed = (SystemClock.uptimeMillis() - downAt).toInt()
                        if (dragged) {
                            onSwipeCanvas(
                                (startPos.x / w).coerceIn(0f, 1f),
                                (startPos.y / h).coerceIn(0f, 1f),
                                (lastPos.x / w).coerceIn(0f, 1f),
                                (lastPos.y / h).coerceIn(0f, 1f),
                                elapsed.coerceIn(180, 900),
                            )
                        } else {
                            onTapCanvas(
                                (lastPos.x / w).coerceIn(0f, 1f),
                                (lastPos.y / h).coerceIn(0f, 1f),
                                elapsed >= longPressTimeoutMs,
                            )
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val frame = snapshot.frame
            // 帧与文字标签交叉淡化，避免首帧到达或副屏结束时舞台画面瞬切。
            val frameAlpha by animateFloatAsState(
                targetValue = if (frame != null) 1f else 0f,
                animationSpec = tween(200),
                label = "stage_frame_alpha",
            )
            if (frame != null) {
                Image(
                    bitmap = frame,
                    contentDescription = snapshot.appName.ifBlank {
                        stringResource(R.string.overlay_virtual_canvas_auto)
                    },
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = frameAlpha },
                )
            }
            if (frameAlpha < 1f) {
                Text(
                    text = snapshot.appName.ifBlank {
                        stringResource(R.string.overlay_virtual_canvas_auto)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    modifier = Modifier.graphicsLayer { alpha = 1f - frameAlpha },
                )
            }

            VirtualTouchFocusLayer(
                effect = snapshot.touchEffect,
                modifier = Modifier.matchParentSize(),
            )
        }

        Spacer(Modifier.height(10.dp))

        // ── 极简画廊信息与接管栏 ───────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val modeLabel = stringResource(
                if (snapshot.userControlling || isPaused) {
                    R.string.overlay_virtual_canvas_takeover
                } else {
                    R.string.overlay_virtual_canvas_auto
                },
            )
            val footerTitle = if (snapshot.appName.isNotBlank()) {
                "${snapshot.appName} · $modeLabel"
            } else {
                modeLabel
            }
            Text(
                text = footerTitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StagePillButton(
                label = stringResource(
                    if (snapshot.userControlling || isPaused) {
                        R.string.overlay_virtual_continue
                    } else {
                        R.string.overlay_virtual_takeover
                    },
                ),
                highlighted = snapshot.userControlling || isPaused,
                accent = accent,
                onClick = {
                    if (snapshot.userControlling || isPaused) onResumeAuto() else onTakeOver()
                },
            )
            Spacer(Modifier.width(6.dp))
            StagePillButton(
                label = stringResource(R.string.overlay_virtual_fullscreen),
                highlighted = false,
                accent = accent,
                onClick = onFullscreen,
            )
        }
    }
}

@Composable
private fun StagePillButton(
    label: String,
    highlighted: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (highlighted) {
            accent.copy(alpha = 0.16f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(200),
        label = "stage_pill_background",
    )
    val textColor by animateColorAsState(
        targetValue = if (highlighted) accent else MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(200),
        label = "stage_pill_text",
    )
    Box(
        modifier = Modifier
            .height(27.dp)
            .clip(RoundedCornerShape(13.5.dp))
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

private val StageMattePadding = 10.dp
private val StageCanvasCorner = 18.dp
private val StageCanvasHeight = 336.dp
private val CapsuleDefaultWidth = 248.dp
private val CapsuleMicroLensWidth = 264.dp
private val CapsuleStageWidth = 296.dp
private val CapsuleCorner = 22.dp
private val CapsuleStageCorner = 28.dp
