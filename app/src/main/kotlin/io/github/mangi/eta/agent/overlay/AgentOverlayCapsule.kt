package io.github.mangi.eta.agent.overlay

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
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
import io.github.mangi.eta.ui.adaptive.EtaConnectedGroupRow
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.etaConnectedGroupColors
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
    /** owner 副屏无法直接破壁投到主屏（需先 handoff），不展示全屏入口。 */
    ownerBacked: Boolean = false,
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
        // 收起时只先收键盘；输入态留到整块展开体收拢后再复位，
        // 避免退场过程中补充输入切回舞台、在面板里闪一下。
        if (!expanded && supplementMode) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
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
        finishedListener = { value ->
            if (value == 0f) {
                // 展开体已完全收拢，此时复位补充输入不会露出面板内部的切换动画。
                if (supplementMode) setSupplementMode(false)
                onCollapsedSettled()
            }
        },
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
        targetValue = if (virtualDisplay.active && expanded) CapsuleOuterCorner else CapsuleInnerCorner,
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
            // 官方 M3 Surface 作为整块胶囊容器：实色 surfaceContainer + 官方海拔，
            // 不再叠 0.96 半透明底色或描边，外观全部由 M3 容器与形状令牌决定。
            Surface(
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
                    },
                shape = RoundedCornerShape(targetCapsuleCorner),
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(TextFieldDefaults.MinHeight)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onToggleExpanded,
                            )
                            .semantics { contentDescription = expandDescription }
                            .padding(horizontal = EtaDimens.SpaceLg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhaseIndicator(phase = state.phase, accent = accent)
                        Spacer(Modifier.width(EtaDimens.SpaceSm))
                        Column(modifier = Modifier.weight(1f)) {
                            AnimatedContent(
                                targetState = statusText,
                                transitionSpec = {
                                    (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                                        (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(160))) using
                                        SizeTransform(clip = false) { _, _ -> snap() }
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
                            enter = fadeIn(tween(180)) + expandHorizontally(
                                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                                expandFrom = Alignment.End,
                            ),
                            exit = fadeOut(tween(120)) + shrinkHorizontally(
                                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                                shrinkTowards = Alignment.End,
                            ),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width(EtaDimens.SpaceSm))
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
                                .padding(EtaDimens.SpaceSm)
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
                                            fullscreenEnabled = !ownerBacked,
                                            onTakeOver = onTakeOverVirtualDisplay,
                                            onResumeAuto = onResumeVirtualDisplay,
                                            onFullscreen = onFullscreenVirtualDisplay,
                                            onTapCanvas = onTapVirtualCanvas,
                                            onSwipeCanvas = onSwipeVirtualCanvas,
                                        )
                                        Spacer(Modifier.height(EtaDimens.SpaceSm))
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
}

/** 展开体互切的三态；舞台与输入框共用尺寸过渡，不靠条件分支硬切。 */
private enum class CapsuleBody { Stage, Controls, Supplement }

/** 补充 / 暂停恢复 / 停止：官方 M3E 小号图标按钮一行居中。 */
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
        horizontalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm, Alignment.CenterHorizontally),
    ) {
        CapsuleAction(
            icon = Icons.Rounded.Edit,
            label = stringResource(R.string.overlay_supplement),
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = onSupplement,
        )
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(tween(160)) togetherWith fadeOut(tween(120))) using SizeTransform(clip = false)
            },
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

/** 运行中是官方 M3E [LoadingIndicator]，其余阶段用官方图标；不自绘旋转弧线。 */
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PhaseGlyph(phase: AgentOverlayPhase, accent: Color) {
    when (phase) {
        AgentOverlayPhase.RUNNING -> LoadingIndicator(
            modifier = Modifier.size(EtaDimens.StatusIconSize),
            color = accent,
        )
        AgentOverlayPhase.PAUSED -> Icon(
            imageVector = Icons.Rounded.Pause,
            contentDescription = null,
            modifier = Modifier.size(EtaDimens.StatusIconSize),
            tint = accent,
        )
        AgentOverlayPhase.FINISHED -> Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(EtaDimens.StatusIconSize),
            tint = accent,
        )
        AgentOverlayPhase.FAILED -> Icon(
            imageVector = Icons.Rounded.Error,
            contentDescription = null,
            modifier = Modifier.size(EtaDimens.StatusIconSize),
            tint = accent,
        )
    }
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

/** 官方 M3E [FilledTonalIconButton]（S 档容器 + 按压 morph），容器与图标尺寸取官方令牌。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CapsuleAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = tint,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
}

/**
 * 镶嵌在收起态顶部胶囊右侧的 18×32.dp 实时微缩取景器（Micro-Lens）。
 *
 * 平时安静展示虚拟屏缩略画面；当虚拟屏内发生点击或滑动时，外圈泛起柔和的呼吸微光。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
        MaterialTheme.colorScheme.outlineVariant,
        accent,
        pulse.value,
    )
    // 取景器高 = 官方 XS 图标按钮容器高度（32dp），形状保持全圆角。
    val lensShape = RoundedCornerShape(percent = 50)

    // 官方 M3 Surface 容器：实色 surfaceContainerHigh + 实色描边，无半透明底色。
    Surface(
        modifier = modifier
            .size(
                width = 18.dp,
                height = IconButtonDefaults.extraSmallContainerSize().height,
            )
            .graphicsLayer {
                val scale = 1f + 0.06f * pulse.value
                scaleX = scale
                scaleY = scale
            },
        shape = lensShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, borderColor),
    ) {
        // 保留最后一帧，画面丢失时旧帧跟着淡出，避免取景器瞬间变空。
        var lastFrame by remember { mutableStateOf(snapshot.frame) }
        LaunchedEffect(snapshot.frame) { snapshot.frame?.let { lastFrame = it } }
        val frame = snapshot.frame ?: lastFrame
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // 帧到达/退出时淡入淡出，避免占位点与缩略画面硬切。
            AnimatedContent(
                targetState = snapshot.frame != null,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                contentAlignment = Alignment.Center,
                label = "lens_frame_presence",
            ) { hasFrame ->
                if (hasFrame && frame != null) {
                    Image(
                        bitmap = frame,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(accent),
                    )
                }
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
                        color = accent,
                        radius = 2.2.dp.toPx() * pulse.value,
                        center = center,
                    )
                }
            }
        }
    }
}

/**
 * 画廊装裱式虚拟屏视窗（Gallery Matte Stage）。
 *
 * 采用外圈 36.dp（extraLarge 28 + 间距 8）+ 8.dp 留白衬底 + 内画框 28.dp 的同心圆角装裱设计，
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
    fullscreenEnabled: Boolean = true,
) {
    // 同心：外圈 36dp（extraLarge 28 + 间距 8），内画框取官方 extraLarge 28dp。
    val frameShape = MaterialTheme.shapes.extraLarge
    // 接管/暂停切换时画框颜色渐渡，与胶囊其余状态色保持同一节奏。
    val borderColor by animateColorAsState(
        targetValue = if (snapshot.userControlling || isPaused) {
            accent
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(220),
        label = "stage_border_color",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── 画廊留白内衬画布（官方 extraLarge 28dp，与 8dp 衬底同心） ──────
        Surface(
            modifier = Modifier
                .height(StageCanvasHeight)
                .aspectRatio(snapshot.aspectRatio, matchHeightConstraintsFirst = true)
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
            shape = frameShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = BorderStroke(1.dp, borderColor),
        ) {
            // 保留最后一帧：画面丢失时旧帧跟着淡出，而不是瞬间消失。
            var lastFrame by remember { mutableStateOf(snapshot.frame) }
            LaunchedEffect(snapshot.frame) { snapshot.frame?.let { lastFrame = it } }
            val frame = snapshot.frame ?: lastFrame
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // 首帧到达 / 副屏结束时淡入淡出，避免占位文字与画面硬切；逐帧更新不触发过渡。
                AnimatedContent(
                    targetState = snapshot.frame != null,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(160)) },
                    contentAlignment = Alignment.Center,
                    label = "stage_frame_presence",
                ) { hasFrame ->
                    if (hasFrame && frame != null) {
                        Image(
                            bitmap = frame,
                            contentDescription = snapshot.appName.ifBlank {
                                stringResource(R.string.overlay_virtual_canvas_auto)
                            },
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(
                            text = snapshot.appName.ifBlank {
                                stringResource(R.string.overlay_virtual_canvas_auto)
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }

                VirtualTouchFocusLayer(
                    effect = snapshot.touchEffect,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }

        Spacer(Modifier.height(EtaDimens.SpaceSm))

        // ── 极简画廊信息与接管栏 ───────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
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
            AnimatedContent(
                targetState = footerTitle,
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "stage_footer_title",
            ) { title ->
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(EtaDimens.SpaceSm))
            val takeoverActive = snapshot.userControlling || isPaused
            val actionCount = if (fullscreenEnabled) 2 else 1
            EtaConnectedGroupRow {
                StageActionToggle(
                    label = stringResource(
                        if (takeoverActive) {
                            R.string.overlay_virtual_continue
                        } else {
                            R.string.overlay_virtual_takeover
                        },
                    ),
                    checked = takeoverActive,
                    index = 0,
                    count = actionCount,
                    onClick = {
                        if (takeoverActive) onResumeAuto() else onTakeOver()
                    },
                )
                AnimatedVisibility(
                    visible = fullscreenEnabled,
                    enter = fadeIn(tween(180)) + expandHorizontally(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        expandFrom = Alignment.End,
                    ),
                    exit = fadeOut(tween(140)) + shrinkHorizontally(
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                        shrinkTowards = Alignment.End,
                    ),
                ) {
                    StageActionToggle(
                        label = stringResource(R.string.overlay_virtual_fullscreen),
                        checked = false,
                        index = 1,
                        count = 2,
                        onClick = onFullscreen,
                    )
                }
            }
        }
    }
}

/** 舞台底部动作：官方 M3E [ToggleButton]（Small 档 + 官方相连形状 + 实色令牌）。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StageActionToggle(
    label: String,
    checked: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val shapes = when {
        count <= 1 -> null
        index <= 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
        index >= count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
    }
    val colors = etaConnectedGroupColors()
    val content: @Composable RowScope.() -> Unit = {
        // 文案切换（接管 ↔ 继续）时按钮宽度随内容平滑过渡，不瞬间跳宽。
        AnimatedContent(
            targetState = label,
            transitionSpec = {
                (fadeIn(tween(160)) togetherWith fadeOut(tween(120))) using SizeTransform(clip = false)
            },
            contentAlignment = Alignment.Center,
            label = "stage_action_label",
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (shapes == null) {
        ToggleButton(
            checked = checked,
            onCheckedChange = { onClick() },
            buttonSize = ToggleButtonSize.Small,
            contentPadding = PaddingValues(horizontal = EtaDimens.SpaceMd),
            colors = colors,
            content = content,
        )
    } else {
        ToggleButton(
            checked = checked,
            onCheckedChange = { onClick() },
            buttonSize = ToggleButtonSize.Small,
            shapes = shapes,
            contentPadding = PaddingValues(horizontal = EtaDimens.SpaceMd),
            colors = colors,
            content = content,
        )
    }
}

/**
 * 圆角与聊天输入胶囊（AgentChatComposerM3）同一套同心规则：
 * 内层取官方 `extraLarge`（28dp，单行 56dp 高即胶囊），外层 = 内层 + 8dp 间距 = 36dp。
 * 舞台画布与补充输入框都用内层 28dp，距外圈正好 8dp（[EtaDimens.SpaceSm]），弧心重合。
 */
private val CapsuleInnerCorner = 28.dp
private val CapsuleOuterCorner = 36.dp

/**
 * 舞台画布高度 = 舞台窗口 524 − 上下留白 12 − 头部 56（`TextFieldDefaults.MinHeight`）
 * − 展开体上下内边距 16（[EtaDimens.SpaceSm] × 2）− 两处 8dp 间距
 * − 接管栏 48 − 动作行 48（官方 40dp 按钮的 48dp 触摸槽）。
 */
private val StageCanvasHeight = 328.dp

private val CapsuleDefaultWidth = 248.dp
private val CapsuleMicroLensWidth = 264.dp
private val CapsuleStageWidth = 296.dp
