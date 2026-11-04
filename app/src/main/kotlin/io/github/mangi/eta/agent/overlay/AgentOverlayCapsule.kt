package io.github.mangi.eta.agent.overlay

import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticsClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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
@OptIn(ExperimentalComposeUiApi::class)
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
    /** 每次布局上报 Surface 实际尺寸（px）；宿主据此外扩留白、逐帧贴合窗口，展开收起才无缝。 */
    onContentSizeChanged: (widthPx: Int, heightPx: Int) -> Unit = { _, _ -> },
    /** 头部拖动：与光球同一套 [AgentOrbDragGesture]——未过 slop 是点击，过了就是整窗拖动。 */
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    /** 抬手抛掷速度（px/s，VelocityTracker 实测）；仅真拖动过 slop 才回调，供宿主起惯性 fling。 */
    onDragEnd: (velocityX: Float, velocityY: Float) -> Unit = { _, _ -> },
    /** 缩球态：大力甩向横边后停靠用，内容只剩阶段指示器（运行中即 contained LoadingIndicator）。 */
    minimized: Boolean = false,
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

    // 浮窗内容确实持有 Expressive 的 motionScheme（下方 bodySpatial 等已直接用），
    // 因此这里统一收口到官方规格，只保留脉冲/进度这类自有节奏。
    val motionScheme = MaterialTheme.motionScheme

    // 为 Agent 手势让路：离开时快速上移收小淡出，回来时用弹簧落回，只改绘制层不触发重排。
    val clearance by animateFloatAsState(
        targetValue = if (yielded) 1f else 0f,
        animationSpec = if (yielded) {
            motionScheme.fastEffectsSpec()
        } else {
            motionScheme.defaultSpatialSpec()
        },
        label = "capsule_clearance",
    )
    val accent by animateColorAsState(
        targetValue = phaseAccent(state.phase),
        animationSpec = motionScheme.defaultEffectsSpec(),
        label = "capsule_accent",
    )
    val statusText = state.status.localizedText()
    val expandDescription = stringResource(R.string.overlay_capsule_toggle, statusText)
    // 展开进度只驱动绘制层的透明度与位移，不参与测量；控制区用 AnimatedVisibility 只在两端各测一次。
    val expansion by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = motionScheme.defaultSpatialSpec(),
        label = "capsule_expansion",
        finishedListener = { value ->
            if (value == 0f) {
                // 展开体已完全收拢，此时复位补充输入不会露出面板内部的切换动画。
                if (supplementMode) setSupplementMode(false)
                onCollapsedSettled()
            }
        },
    )

    // 微缩镜只在收起 + 副屏在场时出现；尾部内缩让它中心落在右上圆角弧心上（与左上指示器对称）。
    val lensVisible = virtualDisplay.active && !expanded && !minimized
    val targetCapsuleWidth by animateDpAsState(
        targetValue = when {
            minimized -> CapsuleBallDiameter
            expanded -> CapsuleExpandedWidth
            virtualDisplay.active -> CapsuleMicroLensWidth
            else -> CapsuleDefaultWidth
        },
        animationSpec = motionScheme.defaultSpatialSpec(),
        label = "capsule_width",
    )
    val targetCapsuleCorner by animateDpAsState(
        targetValue = when {
            // 缩球 56dp 圆：角半径 = 直径一半，恰与内圈 28dp 令牌同值。
            minimized -> CapsuleBallDiameter / 2
            virtualDisplay.active && expanded -> CapsuleOuterCorner
            else -> CapsuleInnerCorner
        },
        animationSpec = motionScheme.defaultSpatialSpec(),
        label = "capsule_corner",
    )
    // 缩球时正文文字淡出（宽度由弹簧收窄带走），恢复时随宽展开淡回。
    // 淡出快（尽快让位给宽度收缩）；淡入慢半拍且带延迟——宽度弹簧 ~400ms，
    // 文字太早浮现会先被窄容器省略截断、再解锁重排，看起来像卡了一下。
    val headerTextAlpha by animateFloatAsState(
        targetValue = if (minimized) 0f else 1f,
        animationSpec = if (minimized) motionScheme.fastEffectsSpec() else motionScheme.defaultEffectsSpec(),
        label = "capsule_header_text_alpha",
    )
    // 末端内边距随缩球同步收放，不能硬切：morph 首帧文字仍 ~95% 不透明，
    // 16↔8dp 跳变会让整段文字横移一瞬。微缩镜在场时收到 28−9=19dp，
    // 让 18dp 宽的镜身中心恰好落在右上圆角弧心上（28,28），与左上指示器同一同心规则。
    val headerEndPadding by animateDpAsState(
        targetValue = when {
            minimized -> EtaDimens.SpaceSm
            lensVisible -> CapsuleInnerCorner - MicroLensGlassWidth / 2
            else -> EtaDimens.SpaceLg
        },
        animationSpec = motionScheme.defaultSpatialSpec(),
        label = "capsule_header_end_padding",
    )

    // 展开体三态互切：舞台 / 控制 / 补充都走同一套交叉过渡，避免舞台与输入框瞬间互换。
    val bodyMode = when {
        supplementMode -> CapsuleBody.Supplement
        virtualDisplay.active -> CapsuleBody.Stage
        else -> CapsuleBody.Controls
    }
    val bodySpatial = motionScheme.defaultSpatialSpec<Float>()
    val bodyFastSpatial = motionScheme.fastSpatialSpec<Float>()
    val bodySizeSpatial = motionScheme.defaultSpatialSpec<IntSize>()
    val bodyOrigin = TransformOrigin(0.5f, 0f)

    // 头部拖动与光球同一套物理手势流：窗口被 WindowManager 移动时 raw 坐标依旧稳定，
    // 单一识别器持有事件流，拖动不会误触发展开；未过 slop 的抬起按点击处理。
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val dragGesture = remember(touchSlop) { AgentOrbDragGesture(touchSlop) }
    val activationKey = remember { AgentOrbActivationKey() }
    val dragVelocity = remember { VelocityTracker.obtain() }
    var dragging by remember { mutableStateOf(false) }
    val toggleLabel = stringResource(if (expanded) R.string.overlay_collapse else R.string.overlay_expand)

    AnimatedVisibility(
        visible = visible && !virtualDisplay.materializingToMain,
        // 窗口逐帧贴合本 Surface 的布局尺寸（onGloballyPositioned 回调宿主），
        // 位移/缩放只走绘制层不参与测量，所以入场/退场走缩放+淡入淡出。
        enter = fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(
            initialScale = 0.92f,
            transformOrigin = TransformOrigin(0.5f, 0f),
            animationSpec = motionScheme.defaultSpatialSpec(),
        ),
        exit = fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(
            targetScale = 0.92f,
            transformOrigin = TransformOrigin(0.5f, 0f),
            animationSpec = motionScheme.fastSpatialSpec(),
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
                    }
                    .onGloballyPositioned { coords ->
                        onContentSizeChanged(coords.size.width, coords.size.height)
                    },
                shape = RoundedCornerShape(targetCapsuleCorner),
                color = MaterialTheme.colorScheme.surfaceContainer,
                // M3E 浮层对齐 FloatingToolbar 官方默认：无投影，靠 surfaceContainer 色阶
                // 与 tonal（官方 Level2，栏类组件档）分层。旧 8dp 投影在贴边窗口里只留
                // 6dp 下边距，被窗口边界裁掉一截，反而显脏。
                tonalElevation = 3.dp,
                shadowElevation = 0.dp,
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(TextFieldDefaults.MinHeight)
                            .semantics {
                                role = Role.Button
                                contentDescription = expandDescription
                                semanticsClick(label = toggleLabel) { onToggleExpanded(); true }
                            }
                            .onKeyEvent { event ->
                                if (event.key == Key.Enter || event.key == Key.NumPadEnter ||
                                    event.key == Key.DirectionCenter || event.key == Key.Spacebar
                                ) {
                                    when (event.type) {
                                        KeyEventType.KeyDown -> activationKey.down(event.key.hashCode())
                                        KeyEventType.KeyUp -> if (activationKey.up(event.key.hashCode())) onToggleExpanded()
                                        else -> Unit
                                    }
                                    true
                                } else false
                            }
                            .onFocusChanged { if (!it.isFocused) activationKey.cancel() }
                            .pointerInteropFilter { event ->
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        dragVelocity.clear()
                                        dragVelocity.addMovement(event)
                                        dragging = false
                                        dragGesture.begin(event.getPointerId(0), event.rawX, event.rawY)
                                        onDragStart()
                                    }
                                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                                        dragVelocity.addMovement(event)
                                        if (event.pointerCount != 1) {
                                            dragging = false
                                            dragGesture.cancel()
                                        } else {
                                            dragGesture.move(event.getPointerId(0), event.rawX, event.rawY)?.let {
                                                dragging = true
                                                onDrag(it.x, it.y)
                                            }
                                            if (event.actionMasked == MotionEvent.ACTION_UP) {
                                                if (dragGesture.finish(event.getPointerId(0))) {
                                                    // 点击：不抛掷。
                                                    dragVelocity.clear()
                                                    onToggleExpanded()
                                                } else if (dragging) {
                                                    // 拖过 slop 的抬手：回传实测速度给宿主起惯性。
                                                    dragging = false
                                                    dragVelocity.computeCurrentVelocity(1000)
                                                    onDragEnd(dragVelocity.xVelocity, dragVelocity.yVelocity)
                                                }
                                            }
                                        }
                                    }
                                    MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN,
                                    MotionEvent.ACTION_POINTER_UP -> {
                                        dragging = false
                                        dragVelocity.clear()
                                        dragGesture.cancel()
                                    }
                                }
                                true
                            }
                            // 左缘内缩与外轮廓圆角同心：28dp 圆角（主界面输入框大胶囊同款）的
                            // 弧心在 (R, R)，40dp 指示器（主界面输入框按钮同尺寸）中心要落在
                            // 弧心上，左内缩 = R − 20dp，默认态即 8dp；纵向由 56dp 头部居中
                            // 自动落位，舞台态圆角涨到 36dp 时随动到 16dp。
                            .padding(
                                start = targetCapsuleCorner -
                                    IconButtonDefaults.smallContainerSize().width / 2,
                                // 缩球态末端收到 8dp：8 + 40dp 指示器槽 + 8 = 56dp 球径内正中。
                                end = headerEndPadding,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhaseIndicator(phase = state.phase, accent = accent)
                        Spacer(Modifier.width(EtaDimens.SpaceSm))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .graphicsLayer { alpha = headerTextAlpha },
                        ) {
                            AnimatedContent(
                                targetState = statusText,
                                transitionSpec = {
                                    (slideInVertically(motionScheme.defaultSpatialSpec()) { it / 2 } +
                                        fadeIn(motionScheme.defaultEffectsSpec())) togetherWith
                                        (slideOutVertically(motionScheme.defaultSpatialSpec()) { -it / 2 } +
                                            fadeOut(motionScheme.fastEffectsSpec())) using
                                        SizeTransform(clip = false) { _, _ -> snap() }
                                },
                                label = "capsule_status",
                            ) { text ->
                                Text(
                                    text = text,
                                    // 官方类型标尺 labelLarge 档（14sp/500）：状态主行不做手排规格。
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            DetailLine(detail = state.detailText.takeIf { state.phase == AgentOverlayPhase.RUNNING }.orEmpty())
                        }
                        AnimatedVisibility(
                            visible = lensVisible,
                            enter = fadeIn(motionScheme.defaultEffectsSpec()) + expandHorizontally(
                                animationSpec = motionScheme.defaultSpatialSpec(),
                                expandFrom = Alignment.End,
                            ),
                            exit = fadeOut(motionScheme.fastEffectsSpec()) + shrinkHorizontally(
                                animationSpec = motionScheme.fastSpatialSpec(),
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
                        visible = expanded && !minimized,
                        enter = expandVertically(
                            animationSpec = motionScheme.defaultSpatialSpec(),
                            clip = true,
                        ) + fadeIn(motionScheme.defaultEffectsSpec()),
                        exit = shrinkVertically(
                            // 收起与展开、以及 graphicsLayer 的 expansion 都取同一条 defaultSpatialSpec，
                            // 三根动画同相推进，收起时正文不会先于高度收缩上蹿。
                            animationSpec = motionScheme.defaultSpatialSpec(),
                            clip = true,
                        ) + fadeOut(motionScheme.fastEffectsSpec()),
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
                                    val enter = fadeIn(motionScheme.defaultEffectsSpec()) + scaleIn(
                                        initialScale = 0.94f,
                                        transformOrigin = bodyOrigin,
                                        animationSpec = bodySpatial,
                                    )
                                    val exit = fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(
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

/**
 * 补充 / 暂停恢复 / 停止：官方 M3 button group **标准档** `ButtonGroup`——「图标+文字」
 * Small 按钮按官方 `clickableItem` 模板排布，间距取官方 12dp
 * （`ButtonGroupDefaults.HorizontalArrangement.spacing`）。按压原生动效：按钮变宽、
 * 邻居等量压缩（`Modifier.animateWidth`，官方默认 `ExpandedRatio` 0.15）。
 * 三个按钮排得下（展开体 360dp），`overflowIndicator` 不会被测量与放置；真折行时
 * 溢出菜单才接管（菜单项照官方 `ClickableButtonGroupItem.MenuContent`：先回调再
 * `dismiss()`）。常规动作取官方 tertiaryContainer / onTertiaryContainer 档，
 * 停止单独取 error 档。
 */
@Composable
private fun CapsuleActions(
    phase: AgentOverlayPhase,
    onSupplement: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // 共享的 interaction source 同时喂给 animateWidth 与按钮本身：按压时
    // 按钮组测量逻辑据此把本钮加宽、邻居等量压缩。
    val supplementSource = remember { MutableInteractionSource() }
    val phaseSource = remember { MutableInteractionSource() }
    val stopSource = remember { MutableInteractionSource() }
    ButtonGroup(
        overflowIndicator = { menuState ->
            ButtonGroupDefaults.OverflowIndicator(menuState = menuState)
        },
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(
            ButtonGroupDefaults.HorizontalArrangement.spacing,
            Alignment.CenterHorizontally,
        ),
    ) {
        customItem(
            buttonGroupContent = {
                CapsuleAction(
                    icon = Icons.Rounded.Edit,
                    label = stringResource(R.string.overlay_supplement),
                    containerColor = scheme.tertiaryContainer,
                    contentColor = scheme.onTertiaryContainer,
                    interactionSource = supplementSource,
                    modifier = Modifier.animateWidth(supplementSource),
                    onClick = onSupplement,
                )
            },
            menuContent = { menuState ->
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    text = { Text(stringResource(R.string.overlay_supplement)) },
                    onClick = {
                        onSupplement()
                        menuState.dismiss()
                    },
                )
            },
        )
        customItem(
            buttonGroupContent = {
                val actionMotionScheme = MaterialTheme.motionScheme
                AnimatedContent(
                    targetState = phase,
                    transitionSpec = {
                        (fadeIn(actionMotionScheme.fastEffectsSpec()) togetherWith
                            fadeOut(actionMotionScheme.fastEffectsSpec())) using SizeTransform(clip = false)
                    },
                    modifier = Modifier.animateWidth(phaseSource),
                    label = "capsule_phase_action",
                ) { current ->
                    when (current) {
                        AgentOverlayPhase.RUNNING -> CapsuleAction(
                            icon = Icons.Rounded.Pause,
                            label = stringResource(R.string.overlay_pause),
                            containerColor = scheme.tertiaryContainer,
                            contentColor = scheme.onTertiaryContainer,
                            interactionSource = phaseSource,
                            onClick = onPause,
                        )
                        AgentOverlayPhase.PAUSED -> CapsuleAction(
                            icon = Icons.Rounded.PlayArrow,
                            label = stringResource(R.string.overlay_resume),
                            containerColor = scheme.tertiaryContainer,
                            contentColor = scheme.onTertiaryContainer,
                            interactionSource = phaseSource,
                            onClick = onResume,
                        )
                        else -> Spacer(Modifier.size(0.dp))
                    }
                }
            },
            menuContent = { menuState ->
                val paused = phase == AgentOverlayPhase.PAUSED
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null)
                    },
                    text = {
                        Text(stringResource(if (paused) R.string.overlay_resume else R.string.overlay_pause))
                    },
                    onClick = {
                        if (paused) onResume() else onPause()
                        menuState.dismiss()
                    },
                )
            },
        )
        customItem(
            buttonGroupContent = {
                CapsuleAction(
                    icon = Icons.Rounded.Stop,
                    label = stringResource(R.string.action_stop),
                    containerColor = scheme.errorContainer,
                    contentColor = scheme.onErrorContainer,
                    interactionSource = stopSource,
                    modifier = Modifier.animateWidth(stopSource),
                    onClick = onStop,
                )
            },
            menuContent = { menuState ->
                DropdownMenuItem(
                    leadingIcon = { Icon(Icons.Rounded.Stop, null) },
                    text = { Text(stringResource(R.string.action_stop)) },
                    onClick = {
                        onStop()
                        menuState.dismiss()
                    },
                )
            },
        )
    }
}

/**
 * 运行中是官方 M3E [ContainedLoadingIndicator]（contained 变体），其余阶段用官方图标；
 * 不自绘旋转弧线。槽位固定为官方 Small 容器（40dp，主界面输入框按钮同尺寸）：
 * 指示器撑满槽位，各阶段图标槽内居中，阶段互切时文字起点不左右挪。
 */
@Composable
private fun PhaseIndicator(phase: AgentOverlayPhase, accent: Color) {
    val glyphMotionScheme = MaterialTheme.motionScheme
    Box(
        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (fadeIn(glyphMotionScheme.defaultEffectsSpec()) +
                    scaleIn(initialScale = 0.7f, animationSpec = glyphMotionScheme.defaultSpatialSpec())) togetherWith
                    (fadeOut(glyphMotionScheme.fastEffectsSpec()) +
                        scaleOut(targetScale = 0.7f, animationSpec = glyphMotionScheme.fastSpatialSpec()))
            },
            label = "capsule_phase_glyph",
        ) { current ->
            PhaseGlyph(phase = current, accent = accent)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PhaseGlyph(phase: AgentOverlayPhase, accent: Color) {
    when (phase) {
        AgentOverlayPhase.RUNNING -> ContainedLoadingIndicator(
            modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
            indicatorColor = accent,
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
 * 流式增量只改文字，不做逐字动画；副行只在有内容时占高——只有一行状态字时
 * 状态行在 56dp 头部里 y 向居中。出现/消失用官方 expand/shrink 顺带淡入淡出，
 * 头部高度恒定（[TextFieldDefaults.MinHeight]），不会带动胶囊整体跳动。
 */
@Composable
private fun DetailLine(detail: String) {
    val shown = detail.isNotBlank()
    var lastDetail by remember { mutableStateOf("") }
    if (shown) lastDetail = detail
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
            expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
            shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
        Text(
            text = lastDetail,
            // 官方类型标尺最小档 labelSmall（11sp/500），副行字号与原手排值一致。
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            // 起始端省略：保留尾部最新的推理，像滚动字幕一样向前推进。
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.padding(top = 1.dp),
        )
    }
}

/**
 * 按钮组里的单个动作：官方 [FilledTonalButton] Small 档（默认形状即官方全圆角胶囊）。
 * icon+text 排布照官方 `clickableItem` 内部模板：`ButtonWithIconContentPadding` +
 * `iconSizeFor(MinHeight)` 图标槽 + `IconSpacing` + 单行 `Text` 且 `overflow=Visible`
 * ——这正是按压挤压邻居时文字「可见地」被压缩的原因。容器色由调用方按动作角色给。
 */
@Composable
private fun CapsuleAction(
    icon: ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        interactionSource = interactionSource,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MinHeight)),
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(
            text = label,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
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
                width = MicroLensGlassWidth,
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
            val lensMotionScheme = MaterialTheme.motionScheme
            AnimatedContent(
                targetState = snapshot.frame != null,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    fadeIn(lensMotionScheme.defaultEffectsSpec()) togetherWith
                        fadeOut(lensMotionScheme.fastEffectsSpec())
                },
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
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
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
                val stageMotionScheme = MaterialTheme.motionScheme
                AnimatedContent(
                    targetState = snapshot.frame != null,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        fadeIn(stageMotionScheme.defaultEffectsSpec()) togetherWith
                            fadeOut(stageMotionScheme.fastEffectsSpec())
                    },
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
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            val footerMotionScheme = MaterialTheme.motionScheme
            AnimatedContent(
                targetState = footerTitle,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    fadeIn(footerMotionScheme.defaultEffectsSpec()) togetherWith
                        fadeOut(footerMotionScheme.fastEffectsSpec())
                },
                label = "stage_footer_title",
            ) { title ->
                Text(
                    text = title,
                    // 官方 labelSmall 档：画廊页脚与状态副行同一标尺位（原 11.5sp 手排值）。
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + expandHorizontally(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        expandFrom = Alignment.End,
                    ),
                    exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + shrinkHorizontally(
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
        val labelMotionScheme = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = label,
            transitionSpec = {
                (fadeIn(labelMotionScheme.defaultEffectsSpec()) togetherWith
                    fadeOut(labelMotionScheme.fastEffectsSpec())) using SizeTransform(clip = false)
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

/**
 * 展开体统一宽度（舞台 / 控制 / 补充三态）。依据：按钮组行需要三个官方
 * 「图标+文字」Small 按钮自然宽（约 97dp/个 + 官方 12dp 间距 ≈ 315dp），
 * 加外衬 2×12dp + 展开体内衬 2×[EtaDimens.SpaceSm] → 360dp 恰好放下（留 ~5dp
 * 文字测量容差），标准档 button group 永不触发溢出
 * （`overflowIndicator` 不会被测量、放置）。
 * 舞台画布高锁定比例、独立于体宽，加宽只多出两侧留白。
 */
private val CapsuleExpandedWidth = 360.dp

/**
 * 缩球直径：56dp 正圆（角半径 28dp 恰与内圈官方令牌同值），内容只剩阶段指示器。
 * 大力甩向屏幕横边时触发，停靠在边缘；点按恢复成收起态胶囊。
 */
private val CapsuleBallDiameter = 56.dp

/** 微缩镜镜身宽度；尾部同心内缩 = 胶囊内角 − 此值一半（镜身中心落在右上圆角弧心上）。 */
private val MicroLensGlassWidth = 18.dp
