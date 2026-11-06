package io.github.mangi.eta.agent.overlay

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
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
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticsClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySnapshot
import io.github.mangi.eta.agent.virtualdisplay.VirtualTouchEffect
import io.github.mangi.eta.ui.adaptive.EtaConnectedGroupRow
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaMotion
import io.github.mangi.eta.ui.adaptive.etaConnectedGroupColors
import io.github.mangi.eta.ui.components.VirtualTouchFocusLayer
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 顶部状态胶囊：收起时只显示一句当前步骤（后台虚拟屏激活时右侧镶嵌 18×32.dp 实时微缩镜），
 * 点击展开补充、暂停、停止及画廊装裱式虚拟屏交互视窗。
 *
 * 位置固定在状态栏下方居中，不随 Agent 的点击位置移动，避免和目标控件重叠。
 * 状态文字只在语义变化时以上滑淡入切换，流式正文不进入胶囊。
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalSharedTransitionApi::class)
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
    /** 窗口内容尺寸上报（px）：早涨晚缩——目标变大即贴、变小等动画落定，morph 两端各动一次窗。 */
    onContentSizeChanged: (widthPx: Int, heightPx: Int) -> Unit = { _, _ -> },
    /** 头部拖动：与光球同一套 [AgentOrbDragGesture]——未过 slop 是点击，过了才回调本项并整窗拖动。 */
    onDragStart: () -> Unit = {},
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    /** 抬手：宿主先落「缩球」决策，再回传甩出计划（占位/起点/钳过的速度）；null = 不甩出。 */
    onDragEnd: (velocityX: Float, velocityY: Float) -> CapsuleFlingPlan? = { _, _ -> null },
    /** 甩出逐帧窗口落位（中心 x + 顶边 y，物理坐标）：允许越界，不做钳制。 */
    onFlingStep: (centerX: Float, topY: Float) -> Unit = { _, _ -> },
    /** 甩出落定（贴边回弹结束或被打断）：宿主据此收尾并恢复正常钳制路径。 */
    onFlingFinish: (centerX: Float, topY: Float) -> Unit = { _, _ -> },
    /** 甩出因边界变化而作废（旋转/inset）：宿主丢弃物理位置并按新边界钳回。 */
    onFlingCancel: () -> Unit = {},
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

    // 浮窗内容确实持有 Expressive 的 motionScheme（下方各 fadeIn/fadeOut 已直接用），
    // 因此这里统一收口到官方规格，只保留脉冲/进度这类自有节奏。
    val motionScheme = MaterialTheme.motionScheme

    // 展开的所有布局轴（宽度 / 展开进度 / 正文高度 / 微缩镜宽度 / 正文三态容器高度 /
    // 暂停钮宽度）共用同一条 320ms 定长时间轴：彼此同时起步、同时收尾，不会出现
    // 「宽度先胀开、正文随后才浮出」的串联观感，也不会有一条弹簧在主轴收尾后继续跑。
    // 逐层套 Expressive spatial 弹簧会各自带过冲长尾，叠起来就是慢动作
    // （取值见 EtaMotion.CapsuleExpandDurationMillis）。
    val expandMillis = EtaMotion.CapsuleExpandDurationMillis
    val expandDpSpec = tween<Dp>(expandMillis, easing = FastOutSlowInEasing)
    val expandSizeSpec = tween<IntSize>(expandMillis, easing = FastOutSlowInEasing)

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
        animationSpec = tween(expandMillis, easing = FastOutSlowInEasing),
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
    // 展开宽按屏钳制：CapsuleExpandedWidth(360) + 窗口外衬 24 = 384dp 在小米 13 Ultra
    // （屏宽恰 384dp）上是零余量全宽请求，会被系统改写并触发「上报→收窗→再上报」的
    // 逐帧缩水回路；留出 ≥40dp 余量保证窗口与放置钳制都有活动空间。
    val density = LocalDensity.current
    val expandedWidth = with(density) {
        (LocalConfiguration.current.screenWidthDp.dp - 40.dp).coerceAtMost(CapsuleExpandedWidth)
    }
    // 展开体三态互切：舞台 / 控制 / 补充都走同一套交叉过渡，避免舞台与输入框瞬间互换。
    // 定义在窗口目标之前：窗口高度的分析目标需按三态分别求和。
    val bodyMode = when {
        supplementMode -> CapsuleBody.Supplement
        virtualDisplay.active -> CapsuleBody.Stage
        else -> CapsuleBody.Controls
    }
    // 窗口目标尺寸（非动画的纯状态函数）。宽度四档与形变动画一致；高度按正文三态
    // 解析求和——头部 56 是固定行（DetailLine 在其内部不占高）：舞台 56+8+328+8+40
    // = 448，控制 56+8+40+8 = 112，补充 56+8+56+8 = 128（进入补充时必为单行：
    // supplementText 关闭时已清空，官方 TextField 最小高 56），收起与缩球 56。
    // 测量值一旦超出分析目标（补充框换行等不可预测长高），由上报状态机按测量值跟涨自愈。
    // 动作行高 = 官方 Small 按钮高（ButtonSmallTokens.ContainerHeight 40dp）。
    val capsuleActionRowHeight = ButtonDefaults.MinHeight
    val bodyTargetHeight = when (bodyMode) {
        CapsuleBody.Stage -> StageCanvasHeight + EtaDimens.SpaceSm + capsuleActionRowHeight
        CapsuleBody.Controls -> capsuleActionRowHeight
        CapsuleBody.Supplement -> TextFieldDefaults.MinHeight
    }
    val stateTargetWidth = when {
        minimized -> CapsuleBallDiameter
        expanded -> expandedWidth
        virtualDisplay.active -> CapsuleMicroLensWidth
        else -> CapsuleDefaultWidth
    }
    val stateTargetHeight = when {
        minimized -> CapsuleBallDiameter
        expanded -> TextFieldDefaults.MinHeight + EtaDimens.SpaceSm + bodyTargetHeight + EtaDimens.SpaceSm
        else -> TextFieldDefaults.MinHeight
    }
    val targetCapsuleWidth by animateDpAsState(
        targetValue = stateTargetWidth,
        animationSpec = expandDpSpec,
        label = "capsule_width",
    )
    // ── 窗口尺寸上报：早涨晚缩，morph 全程零逐帧改窗 ────────────────────
    // 旧方案把动画逐帧值交给窗口（宽=动画值、高=逐帧测量），每帧一次
    // updateViewLayout，且窗口尺寸逐帧变化引发 surface 缓冲整体重分配，
    // 320ms 内 ~120 次跨进程调用，掉帧把动画拉成慢动作。现改为 morph 两端各动一次窗：
    // ① 涨即贴——分析目标变大时立即上报目标，窗口一次涨到位，内容在窗内动画（永不裁切）；
    // ② 落定收拢——变小方向不动窗口，测量值静默 EtaMotion.CapsuleWindowSettleQuietMillis
    //    （动画落定判定）后才上报。
    // 收拢值取 max(分析目标, 测量)：被窗口钳制过的测量值永不回喂，守住「钳制→上报→收窗」
    // 逐帧缩水回路的防线（见 2026-11 修复记录），宽度因此永远 ≥ 状态目标。
    var reportedSize by remember { mutableStateOf(IntSize.Zero) }
    var measuredSize by remember { mutableStateOf(IntSize.Zero) }
    // 甩出（含越界回弹）进行中：窗口尺寸收拢必须等物理落定后再做。
    // 收拢是一次 updateViewLayout + surface 缓冲重分配；若落在飞行/回弹中途，
    // 会与位置逐帧下发叠在同一帧序列里，实机表现为“缩成球那一下抖/顿”。
    var flingRunning by remember { mutableStateOf(false) }
    fun reportContentSize(widthPx: Int, heightPx: Int) {
        val next = IntSize(widthPx, heightPx)
        if (next == reportedSize) return
        reportedSize = next
        onContentSizeChanged(widthPx, heightPx)
    }
    LaunchedEffect(stateTargetWidth, stateTargetHeight, density) {
        val target = IntSize(
            with(density) { stateTargetWidth.roundToPx() },
            with(density) { stateTargetHeight.roundToPx() },
        )
        if (target.width > reportedSize.width || target.height > reportedSize.height) {
            // 涨即贴，并预先多留一个容差：分析目标是整值取整，实测是逐节点取整后求和
            // （56dp/8dp/328dp/40dp 各自取整），恒比目标大 1~3px。留出余量，卡片底缘
            // 就不会被窗口裁掉 1~3px，也就不需要落定后再补一次迟到的涨窗。
            reportContentSize(
                maxOf(target.width, reportedSize.width) + CapsuleReportGrowTolerancePx,
                maxOf(target.height, reportedSize.height) + CapsuleReportGrowTolerancePx,
            )
        }
    }
    LaunchedEffect(stateTargetWidth, stateTargetHeight, density, flingRunning) {
        // 甩出期间冻结“晚缩”：窗口保持松手前尺寸（content 在里面照常 morph），
        // 落定后再收拢。早涨那一支不受影响（飞行中不会有目标变大）。
        if (flingRunning) return@LaunchedEffect
        snapshotFlow { measuredSize }.collectLatest { measured ->
            if (measured == IntSize.Zero) return@collectLatest
            val target = IntSize(
                with(density) { stateTargetWidth.roundToPx() },
                with(density) { stateTargetHeight.roundToPx() },
            )
            // 测量值长过已上报窗口（超容差，过滤微小过冲与取整抖动）：不可预测的长高，立即跟涨。
            if (measured.width - reportedSize.width > CapsuleReportGrowTolerancePx ||
                measured.height - reportedSize.height > CapsuleReportGrowTolerancePx
            ) {
                reportContentSize(
                    maxOf(measured.width, target.width),
                    maxOf(measured.height, target.height),
                )
                return@collectLatest
            }
            delay(EtaMotion.CapsuleWindowSettleQuietMillis)
            val settledWidth = maxOf(measured.width, target.width)
            val settledHeight = maxOf(measured.height, target.height)
            // 收尾只有一个来源：主轴（320ms）结束后这里才落窗口尺寸，且只处理
            // 「状态目标」级别的差量。逐节点 dp→px 取整让分析目标与实测恒差几个 px
            // （56dp=158px、328dp=923px、40dp=113px 各自取整后求和无法精确复现），
            // 这次 ~+150ms 的 updateViewLayout 会落在主轴刚收尾之后，实机就是末尾那下抽搐。
            // 容差内的差量一律不动窗（宁可留 1~3px 透明余量，也不在末段再排一次 surface）。
            if (abs(settledWidth - reportedSize.width) <= CapsuleReportGrowTolerancePx &&
                abs(settledHeight - reportedSize.height) <= CapsuleReportGrowTolerancePx
            ) {
                return@collectLatest
            }
            reportContentSize(settledWidth, settledHeight)
        }
    }
    // 外轮廓圆角恒取官方 extraLarge 28dp，不随展开涨缩：收起 56dp 单行即全圆角胶囊，
    // 缩球角半径 = 56/2 同值。曾按「外圆 = 内圆 + 8dp 间距」在舞台展开态涨到 36dp，
    // 但那个配对内圆不存在——舞台画布按宽高比居中、两侧留白远大于 8dp，角落 8dp 处
    // 并无 28dp 内圆。同心锚点是 40dp 阶段指示器（圆心落左上弧心，20 + 8 = 28），
    // 收起/展开两态同源；展开体其余角均无贴角内圆（画布/按钮组皆居中），故全态恒 28dp。
    val capsuleCorner = CapsuleInnerCorner
    // 缩球时正文文字淡出（宽度由 tween 收窄带走），恢复时随宽展开淡回。
    // 淡出 fastEffects ≈110ms（尽快让位给宽度收缩）；淡入 defaultEffects ≈170ms，
    // 比布局轴 320ms 先收尾（效果层恒短于布局轴，不存在主轴后长尾）。
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
        animationSpec = expandDpSpec,
        label = "capsule_header_end_padding",
    )

    // 正文三态（补充输入 / 舞台 / 控制）切换时，容器尺寸也走主轴 320ms 定长：
    // 用 defaultSpatial（ζ0.8/380，实收 ~440ms + 1.5% 过冲）会让「容器长高」比主轴多跑
    // 120ms，而按钮组正好落在被这段多余生长揭开的最底部——展开瞬间舞台若刚激活，
    // 就复现「舞台段正常、按钮段慢放 + 末尾抽搐」。尺寸轴统一到 expandSizeSpec。
    val bodyOrigin = TransformOrigin(0.5f, 0f)

    // 头部拖动与光球同一套物理手势流：窗口被 WindowManager 移动时 raw 坐标依旧稳定，
    // 单一识别器持有事件流，拖动不会误触发展开；未过 slop 的抬起按点击处理。
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val dragGesture = remember(touchSlop) { AgentOrbDragGesture(touchSlop) }
    val activationKey = remember { AgentOrbActivationKey() }
    // 尾部窗口拟合取代 VelocityTracker：停顿判定 + 120ms 最小二乘，轻放不会带着历史速度乱飞。
    val dragSamples = remember { CapsuleDragVelocitySampler() }
    val flingSession = remember { CapsuleFlingSession() }
    val overlayView = LocalView.current
    val overlayConfiguration = LocalConfiguration.current
    var dragging by remember { mutableStateOf(false) }
    // 旋转/inset 变化会改写可用边界：正在甩出的动画立即作废，位置交还宿主按新边界钳回。
    LaunchedEffect(overlayConfiguration) {
        val job = flingSession.job ?: return@LaunchedEffect
        if (!job.isActive) return@LaunchedEffect
        job.cancel()
        flingSession.job = null
        onFlingCancel()
    }
    val toggleLabel = stringResource(if (expanded) R.string.overlay_collapse else R.string.overlay_expand)

    AnimatedVisibility(
        visible = visible && !virtualDisplay.materializingToMain,
        // 窗口由上报状态机早涨晚缩贴合（onGloballyPositioned 只喂测量值，见上报段注释），
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
                        // 只喂测量值给上报状态机（早涨晚缩），不直连宿主：
                        // 逐帧直报会拖累窗口每帧 resize（见上报段注释）。
                        if (coords.size.height >= 0) {
                            measuredSize = coords.size
                        }
                    },
                shape = RoundedCornerShape(capsuleCorner),
                color = MaterialTheme.colorScheme.surfaceContainer,
                // M3E 浮层对齐 FloatingToolbar 官方默认：无投影，靠 surfaceContainer 色阶
                // 与 tonal（官方 Level2，栏类组件档）分层。旧 8dp 投影在贴边窗口里只留
                // 6dp 下边距，被窗口边界裁掉一截，反而显脏。
                tonalElevation = 3.dp,
                shadowElevation = 0.dp,
            ) {
                // 共享元素层：收起态微缩镜（18×32 圆角镜身）与展开态舞台画布（148×328）以同一
                // key 配对，morph 时由官方 SharedTransition 连续变形，预览不再「瞬移+交叉淡化」。
                // 窗口早涨晚缩已在 morph 两端把窗口撑到位，共享元素 overlay 有完整的绘制空间。
                SharedTransitionLayout {
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
                                            // 甩出途中再按下：立即收手并把当前落点交还宿主；位置不跳变，
                                            // 后续若是拖动就由滑块接管，若是点击则由宿主钳回界内。
                                            flingSession.job?.takeIf { it.isActive }?.let { job ->
                                                job.cancel()
                                                flingSession.job = null
                                                onFlingFinish(flingSession.center.x, flingSession.center.y)
                                            }
                                            dragSamples.reset()
                                            dragSamples.add(event)
                                            dragging = false
                                            dragGesture.begin(event.getPointerId(0), event.rawX, event.rawY)
                                        }
                                        MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                                            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                                                dragSamples.add(event)
                                            }
                                            if (event.pointerCount != 1) {
                                                dragging = false
                                                dragSamples.reset()
                                                dragGesture.cancel()
                                            } else {
                                                dragGesture.move(event.getPointerId(0), event.rawX, event.rawY)?.let {
                                                    // 越过 slop 才移交位置所有权：纯点击不会残留“拖动中”状态。
                                                    if (!dragging) onDragStart()
                                                    dragging = true
                                                    onDrag(it.x, it.y)
                                                }
                                                if (event.actionMasked == MotionEvent.ACTION_UP) {
                                                    if (dragGesture.finish(event.getPointerId(0))) {
                                                        // 点击：不抛掷。
                                                        dragSamples.reset()
                                                        onToggleExpanded()
                                                    } else if (dragging) {
                                                        // 拖过 slop 的抬手：尾部窗口拟合速度 →
                                                        // 宿主结算缩球决策与占位，再跑两段真弹簧。
                                                        dragging = false
                                                        val velocity = dragSamples.estimate(event.eventTime)
                                                        dragSamples.reset()
                                                        val plan = onDragEnd(velocity.x, velocity.y)
                                                        if (plan != null) {
                                                            val view = overlayView
                                                            flingSession.center = plan.center
                                                            flingRunning = true
                                                            flingSession.job = scope.launch {
                                                                try {
                                                                    runCapsuleFling(
                                                                        plan = plan,
                                                                        viewport = { capsuleViewport(view) },
                                                                        onStep = { position ->
                                                                            flingSession.center = position
                                                                            onFlingStep(position.x, position.y)
                                                                        },
                                                                    )
                                                                } finally {
                                                                    // 被打断（按下/旋转）也走这里：解冻收拢，让窗口按当前尺寸收敛。
                                                                    flingRunning = false
                                                                }
                                                                onFlingFinish(
                                                                    flingSession.center.x,
                                                                    flingSession.center.y,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN,
                                        MotionEvent.ACTION_POINTER_UP -> {
                                            dragging = false
                                            dragSamples.reset()
                                            dragGesture.cancel()
                                        }
                                    }
                                    true
                                }
                                // 左缘内缩与外轮廓圆角同心：28dp 圆角（主界面输入框大胶囊同款）的
                                // 弧心在 (R, R)，40dp 指示器（主界面输入框按钮同尺寸）中心要落在
                                // 弧心上，左内缩 = R − 20dp，恒为 8dp；纵向由 56dp 头部居中自动落位。
                                .padding(
                                    start = capsuleCorner -
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
                                        (slideInVertically(CapsuleAxisOffsetSpec) { it / 2 } +
                                            fadeIn(motionScheme.defaultEffectsSpec())) togetherWith
                                            (slideOutVertically(CapsuleAxisOffsetSpec) { -it / 2 } +
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
                                // 副行零占位：高度不计入文字列，钉住状态主行的 y（副行在下方生长）。
                            DetailLine(
                                detail = state.detailText.takeIf { state.phase == AgentOverlayPhase.RUNNING }.orEmpty(),
                                modifier = Modifier.layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    layout(0, 0) { placeable.placeRelative(0, 0) }
                                },
                            )
                            }
                            // 显式标签 lensMorph：下方 sharedBounds 的 animatedVisibilityScope 必须绑本层
                            // （微缩镜的进出场轴）。外层还有胶囊总进出场的隐式 `AnimatedVisibility` 标签，
                            // this@AnimatedVisibility 依赖「最近外层同名标签」规则——中间一旦插入新的
                            // AnimatedVisibility，引用会静默改绑，morph 挂到错误的可见性轴上。
                            AnimatedVisibility(
                                visible = lensVisible,
                                // 微缩镜容器宽度也是「共享元素的目标矩形」：它必须和
                                // sharedBounds 的 320ms tween 同一时刻收尾。走 official
                                // spatial 弹簧时（defaultSpatial ζ0.8/380 实收 ~440ms、
                                // fastSpatial ζ0.6/800 实收 ~400ms 且 9.5% 过冲）会在
                                // morph 交接之后继续变宽/越零被夹，收起的末尾就抽一下。
                                enter = fadeIn(motionScheme.defaultEffectsSpec()) + expandHorizontally(
                                    animationSpec = expandSizeSpec,
                                    expandFrom = Alignment.End,
                                ),
                                exit = fadeOut(motionScheme.fastEffectsSpec()) + shrinkHorizontally(
                                    animationSpec = expandSizeSpec,
                                    shrinkTowards = Alignment.End,
                                ),
                            ) lensMorph@{
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(Modifier.width(EtaDimens.SpaceSm))
                                    // 收起端的共享元素：morph 期间 18×32 镜身由官方 SharedTransition
                                    // 连续长成画布；无配对时（如 VD 单独下线）回退到本处进出场动画。
                                    VirtualDisplayMicroLens(
                                        snapshot = virtualDisplay,
                                        accent = accent,
                                        modifier = Modifier.sharedBounds(
                                            sharedContentState = rememberSharedContentState(
                                                key = VirtualDisplayPreviewSharedKey,
                                            ),
                                            animatedVisibilityScope = this@lensMorph,
                                            enter = EnterTransition.None,
                                            exit = ExitTransition.None,
                                            boundsTransform = { _, _ ->
                                                tween(expandMillis, easing = FastOutSlowInEasing)
                                            },
                                        ),
                                    )
                                }
                            }
                        }

                        // 显式标签 bodyMorph：下方舞台画布的 sharedBounds 必须绑本层（展开/收起轴），
                        // 取内层 AnimatedContent 的 scope 会挂到 bodyMode 互切上；显式命名同时避开
                        // 外层总进出场与 627 那层同名隐式标签的歧义。
                        AnimatedVisibility(
                            visible = expanded && !minimized,
                            enter = expandVertically(
                                animationSpec = expandSizeSpec,
                                clip = true,
                            ) + fadeIn(motionScheme.defaultEffectsSpec()),
                            exit = shrinkVertically(
                                // 收起与展开、以及 graphicsLayer 的 expansion 都取同一条 320ms 时间轴，
                                // 三根动画同相推进，收起时正文不会先于高度收缩上蹿。
                                animationSpec = expandSizeSpec,
                                clip = true,
                            ) + fadeOut(motionScheme.fastEffectsSpec()),
                        ) bodyMorph@{
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
                                            animationSpec = CapsuleAxisFloatSpec,
                                        )
                                        val exit = fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(
                                            targetScale = 0.94f,
                                            transformOrigin = bodyOrigin,
                                            animationSpec = CapsuleAxisFloatSpec,
                                        )
                                        (enter togetherWith exit) using SizeTransform(clip = false) { _, _ -> expandSizeSpec }
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
                                                // 展开端的共享元素：scope 必须取外层正文 AnimatedVisibility
                                                // （this@bodyMorph）——morph 挂在展开/收起这条时间轴上，
                                                // 取 AnimatedContent 的 scope 会挂到 bodyMode 互切上，morph 失配。
                                                frameModifier = Modifier.sharedBounds(
                                                    sharedContentState = rememberSharedContentState(
                                                        key = VirtualDisplayPreviewSharedKey,
                                                    ),
                                                    animatedVisibilityScope = this@bodyMorph,
                                                    enter = EnterTransition.None,
                                                    exit = ExitTransition.None,
                                                    boundsTransform = { _, _ ->
                                                        tween(expandMillis, easing = FastOutSlowInEasing)
                                                    },
                                                ),
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
}

/** 展开体互切的三态；舞台与输入框共用尺寸过渡，不靠条件分支硬切。 */
private enum class CapsuleBody { Stage, Controls, Supplement }

/**
 * 补充 / 暂停恢复 / 停止：官方 M3 button group **标准档** `ButtonGroup`——三枚纯 icon
 * 胶囊钮（官方 [FilledTonalButton] Small 档，仅 Icon 内容，58×40dp）按官方
 * `clickableItem` 模板排布，间距取官方 12dp（`ButtonGroupDefaults.HorizontalArrangement.spacing`）。
 * 按压原生动效：胶囊被挤宽约束拉宽、邻居等量压缩（`Modifier.animateWidth`，官方默认
 * `ExpandedRatio` 0.15）。展开定宽后三枚胶囊排得下；但宽度弹簧从收起宽度过渡时
 * 会短暂窄于按钮总和，溢出菜单因此可能被测量与放置（菜单项照官方
 * `ClickableButtonGroupItem.MenuContent`：先回调再 `dismiss()`）。常规动作取官方
 * tertiaryContainer / onTertiaryContainer 档，停止单独取 error 档。
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
    // 与胶囊主轴同一条 320ms 时间轴（不新建时长体系）：本组内部的尺寸轴都用它收尾
    // （spec 即文件级 CapsuleAxisSizeSpec，全文件布局/尺寸轴共用同一实例）。
    // 共享的 interaction source 同时喂给 animateWidth 与按钮本身：按压时
    // 按钮组测量逻辑据此把本钮加宽、邻居等量压缩。
    val supplementSource = remember { MutableInteractionSource() }
    val phaseSource = remember { MutableInteractionSource() }
    val stopSource = remember { MutableInteractionSource() }
    ButtonGroup(
        overflowIndicator = { menuState ->
            ButtonGroupDefaults.OverflowIndicator(menuState = menuState)
        },
        // 展开宽弹簧会从收起宽度（248/264dp）经过比三个按钮更窄的区间，ButtonGroup 会进入
        // 溢出分支。若这里再套 fillMaxWidth，父约束会把 minWidth 抬到 maxWidth，M3 在溢出分支
        // 用 constraints.copy(maxWidth = 剩余空间) 时就会抛 IllegalArgumentException
        // （ButtonGroup.kt:712 maxWidth must be >= than minWidth）并闪退。
        // 因此外层仍占满整宽、内层放松 minWidth 后居中：正常宽度下与原来视觉一致，
        // 过窄时由官方 overflowIndicator 接管。
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally),
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
                        // 暂停/继续图标切换时中间钮的宽度也是布局轴：默认 SizeTransform 用的是
                        // Compose 通用弹簧（~350ms+），会在主轴 320ms 之后继续改宽度、并让整组
                        // 重新居中——这正是「按钮段末尾还在动」的另一条时长来源。并到主轴。
                        (fadeIn(actionMotionScheme.fastEffectsSpec()) togetherWith
                            fadeOut(actionMotionScheme.fastEffectsSpec())) using
                            SizeTransform(clip = false) { _, _ -> CapsuleAxisSizeSpec }
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
 * 图标亦不位移：普通交叉淡入，槽位恒 40dp——内容 Box 冻结 AnimatedContent 节点尺寸，
 * 容器不再随内容 40↔24 做默认尺寸动画（否则 TopStart 锚点会让图标斜向滑移）。
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
                fadeIn(glyphMotionScheme.defaultEffectsSpec()) togetherWith
                    fadeOut(glyphMotionScheme.fastEffectsSpec())
            },
            label = "capsule_phase_glyph",
        ) { current ->
            // 固定 40dp 槽位：AnimatedContent 节点尺寸恒定，SizeTransform 无事可做，
            // 两枚图标钉在槽位中心，切换时只有 alpha 在动。
            Box(
                modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                contentAlignment = Alignment.Center,
            ) {
                PhaseGlyph(phase = current, accent = accent)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PhaseGlyph(phase: AgentOverlayPhase, accent: Color) {
    when (phase) {
        AgentOverlayPhase.RUNNING -> ContainedLoadingIndicator(
            modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
            // 胶囊外框本身就是 surfaceContainer，圆底若同样取 surfaceContainer 会与背景同色。
            // 因此改用同属中性容器的 surfaceContainerHighest 承托阶段强调色，
            // 避免把 primary/tertiary/error 画在默认 primaryContainer 上造成角色错配。
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
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
 * 流式增量只改文字，不做逐字动画；副行只在有内容时占高。出现/消失用官方
 * expand/shrink（主轴 320ms）顺带淡入淡出，头部高度恒定
 * （[TextFieldDefaults.MinHeight]），不会带动胶囊整体跳动。
 *
 * [modifier] 由调用方传入「零占位」layout：副行高度不计入文字列，在状态行下方
 * 自然生长——头部 CenterVertically 的整组重新居中因此不再牵动状态主行（否则
 * 副行出现时状态行会上移 ~8dp）。超大字号下副行可能探出头部底缘，可接受。
 */
@Composable
private fun DetailLine(detail: String, modifier: Modifier = Modifier) {
    val shown = detail.isNotBlank()
    var lastDetail by remember { mutableStateOf("") }
    if (shown) lastDetail = detail
    AnimatedVisibility(
        visible = shown,
        modifier = modifier,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
            expandVertically(CapsuleAxisSizeSpec),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
            shrinkVertically(CapsuleAxisSizeSpec),
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
 * 按钮组里的单个动作：官方 [FilledTonalButton] Expressive 档、仅 Icon 内容——按钮组规范
 * 的组内 item 是 M3 胶囊按钮（`ButtonGroup` 内置槽即 `Button`/`ToggleButton`，
 * CornerFull 胶囊 + MinHeight 40dp + MinWidth 58dp），纯 icon 也走 Button 家族
 * 保持胶囊形，而不是圆形 IconButton。Expressive 档自带官方按压形状 morph
 * （ButtonSmallTokens 令牌：静止 CornerFull ↔ 按下 CornerSmall，DefaultEffects ≈170ms），
 * 与组内按压挤宽（animateWidth，官方 FAST_SPATIAL）互补成完整的官方按下行为。
 * 图标尺寸取官方 [ButtonDefaults.iconSizeFor]（Small 档 20dp），label 只进
 * contentDescription：没有文字容器，语义可及性不能丢。contentPadding 用官方默认
 * `contentPaddingFor(MinHeight)`（16/16 对称，图标居中）。容器色由调用方按动作
 * 角色给（tertiaryContainer / errorContainer）。宽度随内容走，调用方的按压挤宽
 * 经约束直接改胶囊宽度、松手回弹。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
        shapes = ButtonDefaults.shapes(),
        modifier = modifier,
        interactionSource = interactionSource,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MinHeight)),
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
 * 内画框取官方 extraLarge 28dp，外层卡片同为 28dp 同族圆角；画布按宽高比居中装裱，
 * 四周留白不做同心配对。支持用户手指直接在画布上点按/滑动接管，以及一键全屏投射至物理主屏。
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
    /** 直接挂在画布 Surface（画框本体）上：共享元素 morph 必须以画框的边界为准，
     *  挂到 [modifier]（整段 Column，fillMaxWidth）会把 morph 边界放大成整行宽。 */
    frameModifier: Modifier = Modifier,
) {
    // 内画框取官方 extraLarge 28dp，与外层卡片（恒 28dp，见 capsuleCorner）同族圆角。
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
            modifier = frameModifier
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
                    // 与主轴同源：这是相连组行的布局轴（宽度随钮增减），spatial 弹簧
                    // 实收 400-440ms 会在主轴收尾后继续变宽/过冲顶一下。
                    enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) + expandHorizontally(
                        animationSpec = CapsuleAxisSizeSpec,
                        expandFrom = Alignment.End,
                    ),
                    exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) + shrinkHorizontally(
                        animationSpec = CapsuleAxisSizeSpec,
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
        // 宽度轴钳到主轴：默认 SizeTransform 是 Compose 通用弹簧（~350ms+），
        // 会在主轴收尾后继续改宽并让相连组行重新居中（与暂停钮同款问题）。
        val labelMotionScheme = MaterialTheme.motionScheme
        AnimatedContent(
            targetState = label,
            transitionSpec = {
                (fadeIn(labelMotionScheme.defaultEffectsSpec()) togetherWith
                    fadeOut(labelMotionScheme.fastEffectsSpec())) using
                    SizeTransform(clip = false) { _, _ -> CapsuleAxisSizeSpec }
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
 * 悬浮胶囊外轮廓圆角，全态恒取官方 `extraLarge`（28dp）：收起 56dp 单行即全圆角胶囊，
 * 缩球 R = 56/2 同值；同心锚点是 40dp 阶段指示器（圆心落外角弧心，20 + 8dp 间距），
 * 收起/展开两态同源。展开成卡片亦不涨——曾按画框「28 + 8」推得 36dp，但展开体四角
 * 均无贴角内圆可配对（舞台画布按宽高比居中、按钮组居中，距边远大于 8dp；补充框仅
 * 底部两角贴边且其顶缘在直壁段，单一形状无法全角自洽），28dp 是唯一全态自洽的取值。
 */
private val CapsuleInnerCorner = 28.dp

/**
 * 测量值超出已上报窗口多少才算「不可预测的长高」（px）：过滤取整抖动与逐帧采样差，
 * 避免静止态反复改窗。真实换行一步 ~39px，远大于此容差，不会被吞。
 * 尺寸轴已全部并到 320ms 定长 tween（无过冲），此容差只作数值保护，
 * 同时兼作「落定不上报」的判定阈值（见落定分支的差量判断）。
 */
private const val CapsuleReportGrowTolerancePx = 6

/**
 * 胶囊内所有尺寸轴（IntSize：expand/shrink/SizeTransform）共用的主轴 tween，
 * 与 [EtaMotion.CapsuleExpandDurationMillis] 同源同相——任何一条布局轴都不得
 * 越过主轴继续跑（expressive spatial 弹簧实收 400-440ms 会在主轴收尾后留下长尾）。
 */
private val CapsuleAxisSizeSpec = tween<IntSize>(EtaMotion.CapsuleExpandDurationMillis, easing = FastOutSlowInEasing)

/** 同 [CapsuleAxisSizeSpec]，位移轴（[IntOffset]：slideInVertically/slideOutVertically）版本。 */
private val CapsuleAxisOffsetSpec =
    tween<IntOffset>(EtaMotion.CapsuleExpandDurationMillis, easing = FastOutSlowInEasing)

/** 同 [CapsuleAxisSizeSpec]，transform 轴（Float：scaleIn/scaleOut）版本。 */
private val CapsuleAxisFloatSpec = tween<Float>(EtaMotion.CapsuleExpandDurationMillis, easing = FastOutSlowInEasing)

/** 收起态微缩镜与展开态舞台画布的共享元素 key（官方 SharedTransition 配对用）。 */
private const val VirtualDisplayPreviewSharedKey = "vd_preview"

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
 *
 * 上限值本身不再直接落窗：调用处按「屏宽 − 40dp」钳制（见 [AgentOverlayCapsule]
 * 内 expandedWidth），保证窗口（+24dp 外衬）与放置钳制始终有余量——小米 13 Ultra
 * 屏宽恰 384dp，360+24 = 全宽请求会被系统改写并触发逐帧缩水（见 2026-11 修复）。
 */
private val CapsuleExpandedWidth = 360.dp

/**
 * 缩球直径：56dp 正圆（角半径 28dp 恰与内圈官方令牌同值），内容只剩阶段指示器。
 * 大力甩向屏幕横边时触发，停靠在边缘；点按恢复成收起态胶囊。
 */
private val CapsuleBallDiameter = 56.dp

/** 微缩镜镜身宽度；尾部同心内缩 = 胶囊内角 − 此值一半（镜身中心落在右上圆角弧心上）。 */
private val MicroLensGlassWidth = 18.dp
