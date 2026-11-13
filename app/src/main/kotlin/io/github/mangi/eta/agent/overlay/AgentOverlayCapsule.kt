package io.github.mangi.eta.agent.overlay

import android.os.SystemClock
import android.view.MotionEvent
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
import androidx.compose.foundation.Image
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
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
import io.github.mangi.eta.ui.adaptive.EtaConnectedGroupRow
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaMotion
import io.github.mangi.eta.ui.adaptive.etaConnectedGroupColors
import io.github.mangi.eta.ui.components.VirtualTouchFocusLayer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 顶部状态胶囊：收起时只显示阶段指示器 + 一句当前步骤（不再嵌屏幕预览），
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
    /** 超级岛「补充」键触发计数：递增时展开胶囊并直进补充输入模式。 */
    supplementRequestCount: Int = 0,
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
    /**
     * 抬手：宿主只结算「缩球」决策并回传计划（速度 + 是否成球）；null = 窗口已不在，不必甩出。
     * 起点与实时尺寸都握在帧循环手里，宿主不再传占位，也不再有「拖动期间位置归宿主」这一段。
     */
    onDragEnd: (velocityX: Float, velocityY: Float) -> CapsuleFlingPlan? = { _, _ -> null },
    /**
     * 帧循环逐帧下发的落点（**内容盒**中心 x + 内容顶边 y，物理像素）：宿主只做
     * 「内容盒 → 窗口」的对称投影（补回阴影外衬），允许越界、不做任何钳制。
     */
    onPosition: (centerX: Float, contentTopY: Float) -> Unit = { _, _ -> },
    /** 当前落点（内容中心 x + 内容顶边 y）：帧循环首次参与积分时用它播种起点。 */
    capsulePlacement: () -> Offset? = { null },
    /** 缩球态：大力甩向横边后停靠用，内容只剩阶段指示器（运行中即 contained LoadingIndicator）。 */
    minimized: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    var supplementMode by remember { mutableStateOf(false) }
    var supplementText by remember { mutableStateOf("") }
    // 本次补充输入是否由岛「补充」键唤起：外部唤起的输入结束后要自动收拢胶囊，
    // 交还控制权（用户没碰过胶囊，不能让它停在展开态挡住 agent 注入点按）。
    var supplementExternal by remember { mutableStateOf(false) }
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
            if (supplementExternal) {
                supplementExternal = false
                onToggleExpanded()
            }
            then()
        }
    }

    // 岛「补充」键：展开胶囊并直进补充输入模式（窗口可聚焦/可触摸由 onSupplementModeChange 驱动）。
    LaunchedEffect(supplementRequestCount) {
        if (supplementRequestCount > 0) {
            supplementExternal = true
            if (!expanded) onToggleExpanded()
            setSupplementMode(true)
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

    // 展开的所有布局轴（展开进度 / 正文高度 / 正文三态容器高度 / 暂停钮宽度）
    // 共用同一条 320ms 定长时间轴：彼此同时起步、同时收尾，不会出现
    // 「宽度先胀开、正文随后才浮出」的串联观感，也不会有一条弹簧在主轴收尾后继续跑。
    // 逐层套 Expressive spatial 弹簧会各自带过冲长尾，叠起来就是慢动作
    // （取值见 EtaMotion.CapsuleExpandDurationMillis）。
    // 唯一例外是胶囊 ⇄ 球的宽度 morph：它按参考实现逐帧指数插值（见 CapsuleMotion / 帧循环）。
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
        else -> CapsuleDefaultWidth
    }
    val stateTargetHeight = when {
        minimized -> CapsuleBallDiameter
        expanded -> TextFieldDefaults.MinHeight + EtaDimens.SpaceSm + bodyTargetHeight + EtaDimens.SpaceSm
        else -> TextFieldDefaults.MinHeight
    }
    // 胶囊 ⇄ 球宽度 morph：与参考实现逐帧同构 `w += (wT − w) × (1 − e^(−16·dt))`。
    // 宽度是组合侧推进的 Animatable（dp），只在**布局阶段**被读取——组合作用域不读它，
    // 因此逐帧推进只失效布局、不触发任何重组（旧实现在组合里读 Animatable.value，
    // 每帧整棵胶囊树重组，是掉帧主因）。
    // 两条通道互斥：展开 / 收起走 320ms 主轴 tween；胶囊 ⇄ 球走 `runCapsuleMotion` 的
    // 单一帧循环（与物理共用同一个 dt、同一帧、同一顺序）。绝不让两个写入方同帧覆盖。
    // 圆角恒为 28dp（= 56/2），宽度收到 56dp 时自然成为正圆，与参考实现
    // `borderRadius = h/2` 完全等价（胶囊与球同高 56dp，圆角两端都是 h/2）。
    // 运动状态机（位置/速度/形态目标 + 唤醒信号）。宽度**不**住在这里。
    val motion = remember(density) { CapsuleMotion(density = density.density) }
    val capsuleWidthAnim = remember { Animatable(stateTargetWidth.value) }
    var onBallMorphAxis by remember { mutableStateOf(minimized) }
    LaunchedEffect(stateTargetWidth, minimized) {
        val target = stateTargetWidth.value
        val ballWidth = CapsuleBallDiameter.value
        // 起止任一端是球（56dp）才走 morph 轴；纯胶囊窗的换档仍交给主轴 tween。
        val morph = minimized || onBallMorphAxis ||
            abs(capsuleWidthAnim.value - ballWidth) <= 1f
        if (!morph) {
            onBallMorphAxis = false
            motion.morphWidth = false
            capsuleWidthAnim.animateTo(
                target,
                tween<Float>(durationMillis = expandMillis, easing = FastOutSlowInEasing),
            )
            return@LaunchedEffect
        }
        // 目标还在球的这一侧（去程）就保持标记；回到胶囊后由下一次目标切换离开该轴。
        onBallMorphAxis = abs(target - ballWidth) <= 1f
        // 交给单一帧循环：形变与物理同帧推进，收敛后循环自行挂起。
        motion.morphWidth = true
        motion.widthTargetDp = target
        motion.wake()
    }
    // 状态目标内容尺寸（px）：**只作首轮实测前的起值兜底**（见 liveSizePx）。循环能否休眠
    // 看的是实测尺寸连续几帧不变，绝不拿这两个值去比——几何也一律走实测值，把目标交给循环
    // 去钳位就是展开瞬间位置跳变的来源。
    LaunchedEffect(stateTargetWidth, stateTargetHeight, density) {
        motion.targetContentWidthPx = with(density) { stateTargetWidth.roundToPx() }.toFloat()
        motion.targetContentHeightPx = with(density) { stateTargetHeight.roundToPx() }.toFloat()
        motion.wake()
    }
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
    // 缩球时正文文字淡出（宽度由指数 morph 收窄带走），恢复时随宽展开淡回。
    // 淡出 fastEffects ≈110ms（尽快让位给宽度收缩）；淡入 defaultEffects ≈170ms，
    // 比布局轴 320ms 先收尾（效果层恒短于布局轴，不存在主轴后长尾）。
    val headerTextAlpha by animateFloatAsState(
        targetValue = if (minimized) 0f else 1f,
        animationSpec = if (minimized) motionScheme.fastEffectsSpec() else motionScheme.defaultEffectsSpec(),
        label = "capsule_header_text_alpha",
    )
    // 末端内边距随缩球同步收放，不能硬切：morph 首帧文字仍 ~95% 不透明，
    // 16↔8dp 跳变会让整段文字横移一瞬。
    val headerEndPadding by animateDpAsState(
        targetValue = if (minimized) EtaDimens.SpaceSm else EtaDimens.SpaceLg,
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
    val overlayView = LocalView.current
    val overlayConfiguration = LocalConfiguration.current
    var dragging by remember { mutableStateOf(false) }
    // 抓取偏移 = 按下瞬间「内容中心 − 指针 raw」，对应参考实现的 `ox = x - px`。
    var grabOffset by remember { mutableStateOf(Offset.Zero) }
    // 按压回弹只认真按压（按下缩一点、抬手弹回）。曾用「展开进度」算出中点缩 3% 的钟形，
    // 于是每一次涨缩（含程序触发的展开）都凭空多一下缩放，与宽度主轴对着干。
    var pressed by remember { mutableStateOf(false) }
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = if (pressed) {
            motionScheme.fastSpatialSpec()
        } else {
            motionScheme.defaultSpatialSpec()
        },
        label = "capsule_press",
    )
    // 可用边界每次配置只取一次：currentWindowMetrics + inset 读取有 IPC 成本，不能逐帧做。
    // 用 state 承载而不是进帧循环的 key：配置一变就把正在跑的循环 cancel 掉，甩出就永远等不到
    // 落定回调，flingRunning 一直为真 → 「晚缩」冻结在大窗、透明区域白吃触摸。
    var viewport by remember { mutableStateOf(capsuleViewport(overlayView)) }
    // 实时内容尺寸：几何、边界、落位全只认它（morph / 展开 / 换态期间逐帧变化）。
    // 还没量过时退回状态目标，避免首轮拿到 Zero 把可用区间算成一条线。
    fun liveSizePx(): IntSize {
        val measured = measuredSize
        return if (measured.width > 0 && measured.height > 0) measured else IntSize(
            motion.targetContentWidthPx.roundToInt(),
            motion.targetContentHeightPx.roundToInt(),
        )
    }
    /** 向宿主取当前落点（内容盒语义）；取到就同步顶边锚并作废去重基线。 */
    fun seedFromHost(): Boolean {
        val seed = capsulePlacement() ?: return false
        motion.centerX = seed.x
        motion.anchorTopY = seed.y
        motion.centerY = seed.y + liveSizePx().height / 2f
        motion.resetEmitted()
        motion.positioned = true
        return true
    }
    // 唯一的帧循环：跟手 → 物理 step → 形变 → 约束 → 落位，与参考实现 frame() 同序同帧。
    // 空闲时挂起在 wakeups 上（不注册 Choreographer 回调），只有按下/移动/甩出/目标变化才唤醒；
    // 上一版「物理循环 + 宽度动画循环」两条帧驱动并存，同一帧两次唤醒且 dt 不同源。
    LaunchedEffect(motion) {
        try {
            while (true) {
                motion.wakeups.receive()
                // 首次参与约束时向宿主取当前落点（未知则跳过本次，等下一次唤醒）。
                if (!motion.positioned && !seedFromHost()) continue
                runCapsuleMotion(
                    motion = motion,
                    widthDp = capsuleWidthAnim,
                    liveSize = { liveSizePx() },
                    viewport = { viewport },
                    onPosition = onPosition,
                    // 落定只需解除「甩出期间冻结晚缩」：最终落点已经由最后一次强制下发交清，
                    // 宿主不再需要单独的收尾回调去接管位置——位置从头到尾只有这一个来源。
                    onSettled = { flingRunning = false },
                )
            }
        } finally {
            // 循环被取消（组合销毁、motion 重建）也要解除冻结，否则窗口永久停在甩出时的大尺寸。
            flingRunning = false
        }
    }
    // 旋转/inset 变化改写可用边界：更新 viewport 后唤醒循环，由它按新边界逐帧夹回并重新下发。
    // 这里不自己挪窗口——那正是「两个写入方」的起点。
    LaunchedEffect(overlayConfiguration) {
        viewport = capsuleViewport(overlayView)
        motion.wake()
    }
    // 抬手甩出：把宿主结算的计划灌进状态机，唤醒唯一帧循环（不再另起一条动画协程）。
    // 起点就是循环上一帧积分出的内容中心，实时尺寸由循环自己按实测读，这里都不重新播种。
    fun startFling(plan: CapsuleFlingPlan) {
        motion.velocityX = plan.velocity.x
        motion.velocityY = plan.velocity.y
        motion.phase = CapsuleMotionPhase.Free
        motion.phaseStartNanos = 0L
        if (plan.ballTarget) {
            // 甩出缩球：宽度立刻进 morph 通道（与物理同帧起步，不等下一次重组）。
            // 高度只切目标档位，由 Compose 的收起动画逐帧量出实测值，循环跟着实测走。
            motion.morphWidth = true
            motion.widthTargetDp = CapsuleBallDiameter.value
        }
        flingRunning = true
        motion.wake()
    }
    // 抬手进 free：参考实现里非点击的 release 一律是 free + 拟合速度（哪怕为 0，也会由
    // free → settle → rest 连续收尾），不在这里写死落点，轨迹才不会有急刹或闪现。
    fun releaseToFree(velocity: Offset) {
        motion.velocityX = velocity.x
        motion.velocityY = velocity.y
        motion.phase = CapsuleMotionPhase.Free
        motion.phaseStartNanos = 0L
        motion.wake()
    }
    // 手势作废（多指接管、系统取消）：不抛掷、不补速度，停在原地由循环按实时尺寸夹回。
    fun cancelCapsuleGesture() {
        dragging = false
        pressed = false
        dragSamples.reset()
        dragGesture.cancel()
        motion.pointerDown = false
        motion.phase = CapsuleMotionPhase.Rest
        motion.velocityX = 0f
        motion.velocityY = 0f
        motion.wake()
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
                    // 四周外衬全部给阴影留白（见 CapsuleShadowGutter）：窗口尺寸 = 内容 + 外衬，
                    // 外衬即落影的净空，宿主常量必须同值同步。
                    .padding(horizontal = CapsuleShadowGutter, vertical = CapsuleShadowGutter)
                    // 逐帧宽度在 **measure 阶段** 读取（宽度是胶囊⇄球 morph 的唯一变量）。
                    // 旧实现在组合作用域读 Animatable.value → 每帧整棵胶囊树重组，是掉帧根因；
                    // 这里只失效布局（一次 Row 重测），组合、绘制缓存都不动。
                    .layout { measurable, constraints ->
                        // 宽度是 dp，按当前密度换算成 px；Animatable 只在布局阶段被读。
                        val widthPx = (capsuleWidthAnim.value * density.density)
                            .roundToInt()
                            .coerceIn(0, constraints.maxWidth)
                        val placeable = measurable.measure(
                            constraints.copy(minWidth = widthPx, maxWidth = widthPx),
                        )
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(0, 0)
                        }
                    }
                    .graphicsLayer {
                        // 按压回弹 + 让路缩放都只改绘制层，不触发重新布局。
                        val shrink = press * (1f - 0.08f * clearance)
                        scaleX = shrink
                        scaleY = shrink
                        translationY = -12.dp.toPx() * clearance
                        alpha = 1f - clearance
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .onGloballyPositioned { coords ->
                        // 只喂测量值给上报状态机（早涨晚缩），不直连宿主：
                        // 逐帧直报会拖累窗口每帧 resize（见上报段注释）。
                        // 尺寸没变就不写 state：morph 之外的布局也会回调本项，
                        // 无谓的 snapshot 写入会让 snapshotFlow 的静默期计时反复重启。
                        if (coords.size != measuredSize) {
                            measuredSize = coords.size
                            // 尺寸一变就唤醒循环：边界与顶边锚都按实时内容尺寸算，展开/收起
                            // 途中必须逐帧重贴，落定前循环也不许休眠。唤醒写在布局回调里，
                            // 而不是加一条 LaunchedEffect(measuredSize)——那会把实测尺寸读进
                            // 组合作用域，morph 期间逐帧整棵胶囊树重组，正是当初的掉帧主因。
                            motion.wake()
                        }
                    },
                shape = RoundedCornerShape(capsuleCorner),
                color = MaterialTheme.colorScheme.surfaceContainer,
                // 落影取官方的「漂浮容器」档位：MenuDefaults.ShadowElevation
                // = MenuTokens.ContainerElevation = ElevationTokens.Level2 = 3dp。
                // 取证（androidx.compose.material3:material3:1.5.0-alpha29 sources）：
                // · commonMain/androidx/compose/material3/MenuDefaults.kt「ShadowElevation」
                // · commonMain/androidx/compose/material3/tokens/MenuTokens.kt「ContainerElevation」
                // · commonMain/androidx/compose/material3/tokens/ElevationTokens.kt「Level2 = 3.0.dp」
                // M3E 没有为 overlay 浮层单独定义 elevation：FloatingToolbarDefaults 的展开/收起
                // 容器档都是 Level0，且源码自标「TODO read from token」(FloatingToolbar.kt)，
                // 不足以作依据；ElevationTokens / ElevationDefaults 本身是 internal，
                // 因此这里引用公开派生值 MenuDefaults.ShadowElevation，不另造数值。
                // 该档位由 Surface 内部 Modifier.graphicsLayer(shadowElevation, clip = false)
                // 绘制在布局边界之外（Surface.kt「Modifier.surface」），必须配合 12dp 外衬，
                // 否则会被紧贴内容的胶囊窗口裁成硬边。
                tonalElevation = 3.dp,
                shadowElevation = MenuDefaults.ShadowElevation,
            ) {
                // 微缩镜预览已移除：收起态只保留阶段指示器 + 状态文字，不再嵌实时画面。
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
                                            // 按住即接管（参考实现 pointerdown → phase = "drag"）：飞行途中
                                            // 可以徒手接住，形变若在进行中继续由同一循环收敛。
                                            // 位置仍归循环积分——这里只记抓取偏移，逐事件不碰窗口：
                                            // 每个 ACTION_MOVE 一次 updateViewLayout（还要现取一次屏幕边界）
                                            // 才是「卡手」的直接来源，120/240Hz 采样率下每帧白付好几次。
                                            if (!motion.positioned) seedFromHost()
                                            motion.pointerDown = true
                                            motion.phase = CapsuleMotionPhase.Drag
                                            motion.velocityX = 0f
                                            motion.velocityY = 0f
                                            motion.dragTargetX = motion.centerX
                                            motion.dragTargetY = motion.centerY
                                            grabOffset = Offset(
                                                motion.centerX - event.rawX,
                                                motion.centerY - event.rawY,
                                            )
                                            pressed = true
                                            dragging = false
                                            dragSamples.reset()
                                            dragSamples.add(event)
                                            dragGesture.begin(event.getPointerId(0), event.rawX, event.rawY)
                                            motion.wake()
                                        }

                                        MotionEvent.ACTION_MOVE -> {
                                            dragSamples.add(event)
                                            if (event.pointerCount != 1) {
                                                cancelCapsuleGesture()
                                            } else {
                                                // 越过 slop 才算拖动：纯点击不会残留「拖动中」状态。
                                                if (dragGesture.move(
                                                        event.getPointerId(0),
                                                        event.rawX,
                                                        event.rawY,
                                                    ) != null
                                                ) {
                                                    dragging = true
                                                }
                                                if (dragging) {
                                                    // 参考实现的 px + ox：只推进目标点，跟手交给循环插值。
                                                    motion.dragTargetX = event.rawX + grabOffset.x
                                                    motion.dragTargetY = event.rawY + grabOffset.y
                                                    motion.wake()
                                                }
                                            }
                                        }

                                        MotionEvent.ACTION_UP -> {
                                            pressed = false
                                            motion.pointerDown = false
                                            val wasDragging = dragging
                                            dragging = false
                                            val velocity = dragSamples.estimate(event.eventTime)
                                            dragSamples.reset()
                                            if (dragGesture.finish(event.getPointerId(0))) {
                                                // 点击：不抛掷、不改位置，只把相位交回静止，
                                                // 展开/收起途中由循环按实时尺寸逐帧重贴边界。
                                                motion.phase = CapsuleMotionPhase.Rest
                                                motion.velocityX = 0f
                                                motion.velocityY = 0f
                                                motion.wake()
                                                onToggleExpanded()
                                            } else if (wasDragging) {
                                                // 拖过 slop 的抬手：宿主只结算「是否缩球」，拟合速度直接进
                                                // free；速度为 0 也照这条链收尾（free → settle → rest 连续）。
                                                val plan = onDragEnd(velocity.x, velocity.y)
                                                if (plan != null) startFling(plan) else releaseToFree(velocity)
                                            } else {
                                                // 手势早已被多指/取消接管：这里只让循环按实时尺寸夹回一次。
                                                motion.wake()
                                            }
                                        }

                                        MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN,
                                        MotionEvent.ACTION_POINTER_UP -> cancelCapsuleGesture()
                                    }
                                    true
                                }
                                // 左缘内缩与外轮廓圆角同心：28dp 圆角（主界面输入框大胶囊同款）的
                                // 弧心在 (R, R)，40dp 指示器（主界面输入框按钮同尺寸）中心要落在
                                // 弧心上，左内缩 = R − 20dp，恒为 8dp；纵向由 56dp 头部居中自动落位。
                                .padding(
                                    start = capsuleCorner -
                                        IconButtonDefaults.smallContainerSize().width / 2,
                                )
                                // 缩球态末端内边距在 **measure 阶段** 读取（原样等价于
                                // `.padding(end = headerEndPadding)`）：组合期读 animateDpAsState
                                // 会让整段内容（Surface + 正文）在 320ms 内逐帧重组，
                                // 与宽度 morph 撞在同一段帧里，是掉帧的第二根因。
                                .layout { measurable, constraints ->
                                    val endPx = headerEndPadding.roundToPx()
                                    val inner = constraints.copy(
                                        minWidth = 0,
                                        maxWidth = (constraints.maxWidth - endPx).coerceAtLeast(0),
                                    )
                                    val placeable = measurable.measure(inner)
                                    layout(placeable.width + endPx, placeable.height) {
                                        placeable.placeRelative(0, 0)
                                    }
                                },
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
                        }

                        // 正文展开/收起轴：舞台画布与按钮组都在这一条 320ms 时间轴上进出。
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
 * 阴影外衬（每侧 dp）：[Surface] 的投影由 `Modifier.graphicsLayer(shadowElevation, clip = false)`
 * 绘制在布局边界之外，而胶囊窗口尺寸 = 内容 + 外衬 + 缓冲、紧贴内容，外衬不足就会被窗口
 * 裁成硬边。3dp 档位（[MenuDefaults.ShadowElevation]）的投影在 2.8125 密度下外扩约 10px，
 * 12dp ≈ 34px 足够容纳四周落影，因此四周统一 12dp。
 *
 * 同步要求：宿主 AgentRuntimeService 的
 * `CAPSULE_PADDING_HORIZONTAL_DP` / `CAPSULE_PADDING_VERTICAL_DP` 必须与本值一致，
 * 否则窗口会裁内容或留出多余空隙。
 * 垂直外衬由原来的 6dp 提到 12dp：窗口顶边仍是位置锚点（拖拽/甩出坐标语义不变），
 * 所以胶囊贴系统栏上沿时本体比原先内缩 6dp——这 6dp 正是投影所需净空。
 */
private val CapsuleShadowGutter = 12.dp

/**
 * 测量值超出已上报窗口多少才算「不可预测的长高」（px）：过滤取整抖动与逐帧采样差，
 * 避免静止态反复改窗。真实换行一步 ~39px，远大于此容差，不会被吞。
 * 尺寸轴中除胶囊 ⇄ 球宽度 morph（逐帧指数插值）外均并到 320ms 定长 tween（无过冲），
 * 此容差只作数值保护，同时兼作「落定不上报」的判定阈值（见落定分支的差量判断）。
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

/**
 * 舞台画布高度 = 舞台窗口 536 − 上下留白 24（每侧 12dp 阴影外衬）− 头部 56
 * （`TextFieldDefaults.MinHeight`）− 展开体上下内边距 16（[EtaDimens.SpaceSm] × 2）
 * − 两处 8dp 间距 − 接管栏 48 − 动作行 48（官方 40dp 按钮的 48dp 触摸槽）。
 */
private val StageCanvasHeight = 328.dp

private val CapsuleDefaultWidth = 248.dp

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
