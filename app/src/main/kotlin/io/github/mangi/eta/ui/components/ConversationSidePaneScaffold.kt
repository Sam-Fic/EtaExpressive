package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedAnimationSpec
import androidx.compose.animation.core.VectorizedDecayAnimationSpec
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import io.github.mangi.eta.ui.components.streamDiagnosticDraw
import io.github.mangi.eta.ui.components.streamDiagnosticMeasure
import io.github.mangi.eta.ui.components.streamDiagnosticPlacement
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.ManageHistory
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.adaptive.EtaConnectedPosition
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaExpressiveToggleButton
import io.github.mangi.eta.ui.adaptive.EtaFloatingToolbarConcentricPadding
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
import io.github.mangi.eta.ui.adaptive.EtaTextField
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaConnectedButtonShapes
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.layout.LocalPageContentWidth
import io.github.mangi.eta.ui.layout.rememberIsWideScreen
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.UNCATEGORIZED_FOLDER_ID
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.material3.Icon
import kotlin.math.roundToInt

private object DrawerMetrics {
    val PaneMaxWidth = 320.dp
    val PaneWidthFraction = 0.78f
    // 宽屏（平板横屏）按 M3 side sheet 规格：固定宽度，上限 360dp，
    // 窗口刚过 600dp 时按比例收窄；停靠时与对话并排，手势打开时叠在对话之上。
    val SideSheetMaxWidth = 360.dp
    val SideSheetWidthFraction = 0.46f
    val SideSheetCornerRadius = 16.dp
    val DrawerCornerRadius = 24.dp
    const val OpenDurationMs = 220
    const val CloseDurationMs = 200
    // 手势松手后的合拢速度下限（dp/s）。低于它算「拖着不放」，按位置判；高于它算甩，
    // 直接朝甩的方向定锚，速度越快越早到位——这是「跟手」的关键。
    // 官方是 400dp/s；取 480 略高一点，避免列表里横向轻扫误关，正常甩动仍能直接命中。
    const val SettleVelocityThresholdDp = 480
    // 位置阈值占全程的比例。官方 0.5 意味着拖过一半才松手。
    // 抽屉是「越长越全」的连续面板，稍微一拉就有反馈更符合预期。
    const val SettlePositionalThreshold = 0.32f
    // 临界阻尼（无回弹）。全屏抽屉越过打开锚点会露出底下的主页，所以收敛不做回弹。
    const val SettleDampingRatio = Spring.DampingRatioNoBouncy
    // 松手收敛弹簧的刚度随甩动速度在 Slow..Fast 之间插值：
    // 慢拖轻放用低刚度（柔和靠岸），快甩用高刚度（迅速到位，但仍是减速落地而不是硬截停）。
    const val SettleStiffnessSlow = 520f
    const val SettleStiffnessFast = 1600f
    // 刚度插值对应的松手速度区间（dp/s）：低于 Soft 一律最软，高于 Firm 一律最硬。
    const val SettleSoftVelocityDp = 100f
    const val SettleFirmVelocityDp = 2000f
    // 临界阻尼弹簧的初始速度超过 ω·剩余距离就会越过锚点；留 8% 余量，保证不露底。
    const val SettleVelocityHeadroom = 0.92f
    val PaneHorizontalPadding = 16.dp
    // 首排（胶囊行）与首页顶栏同一水平中线：首页顶栏是 64dp 行高 + 4dp 纵向内边距，
    // 40dp 图标按钮居中后中线在状态栏下 4 + 8 + 20 = 32dp；这里 12 + 20 = 32dp 与之对齐。
    val TopInset = 12.dp
    val ChipGap = 8.dp
    // 胶囊行横向滚动的两端淡出带宽度。与底色同色，滚动到中间时把视口切开的胶囊边缘擦掉。
    val ChipRowEdgeFadeWidth = 24.dp
    val AfterChips = 10.dp
    // 底部浮动工具栏（M3E HorizontalFloatingToolbar）：高度与离底间距取官方
    // FloatingToolbarDefaults.ContainerSize / ScreenOffset（64dp / 16dp），
    // 列表底部再留 12dp，让最后一条会话能完整滚到工具栏上方而不被药丸压住。
    // 这里不含导航栏 inset：列表铺到屏幕底缘，导航栏高度在 Material3ConversationPane 里另加。
    val ToolbarHeight = 64.dp
    val ToolbarBottomOffset = 16.dp
    val ToolbarListGap = 12.dp
    val ToolbarListBottomPadding = ToolbarHeight + ToolbarBottomOffset + ToolbarListGap
    val ActionIconSize = 20.dp
    val CircleButtonPadding = 10.dp
    val SectionTopPadding = 10.dp
    val SectionBottomPadding = 6.dp
    val RowMinHeight = 44.dp
    val RowGap = 2.dp
    val RowCornerRadius = 16.dp
    val RowHorizontalPadding = 14.dp
    val RowVerticalPadding = 10.dp
    val ActiveDotSize = 6.dp
    val ActiveDotGap = 8.dp
    val EmptyVerticalPadding = 28.dp
    val DockTopGap = 8.dp
    val AssistantBarHeight = 48.dp
}

@Composable
fun ConversationSidePaneScaffold(
    state: ConversationPaneUiState,
    visible: Boolean,
    backHandlerEnabled: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit = {},
    onConversationTogglePin: (ConversationSummaryUi) -> Unit = {},
    onConversationExport: (ConversationSummaryUi) -> Unit = {},
    onConversationAnalysisExport: (ConversationSummaryUi) -> Unit = {},
    onOpenSearchHistory: () -> Unit = {},
    onOpenManageChats: () -> Unit = {},
    onOpenManageFolders: () -> Unit = {},
    onSelectFolder: (String?) -> Unit = {},
    onRestoreConversation: (String) -> Unit = {},
    onConversationDeleteForever: (String) -> Unit = {},
    onEmptyTrash: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    selectedAssistantId: String = "",
    onSelectAssistant: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsageStats: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val sceneLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    // 宽屏（平板横屏）只有并排一种形态：侧栏固定停在左侧，对话列被挤窄，永远不覆盖、不压暗。
    // 因此宽屏下抽屉完全不参与，只有窄屏才用它做全屏平推侧栏。
    val isWide = rememberIsWideScreen()
    val drawerState = rememberDrawerState(
        // 宽屏没有抽屉；窄屏保留按展开态恢复。
        initialValue = if (visible && !isWide) DrawerValue.Open else DrawerValue.Closed,
    )
    // 每次组合后重设一次：阈值/规格是 anchored state 上的可写字段，
    // 重设可跟上 density 变化；M3 自带的 SideEffect 只改 open/close/anchored spec，
    // 不碰手势收敛实际读取的那几项。
    val density = LocalDensity.current
    SideEffect {
        drawerState.applyEtaDrawerMotion(density)
    }
    val drawerScope = rememberCoroutineScope()
    val currentVisible by rememberUpdatedState(visible)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var drawerBlocksIme by remember { mutableStateOf(visible) }

    // 窄屏的侧栏是全屏覆盖形态，选中会话 / 新建会话后必须收起才能看到对话，
    // 走后缀动画而不是瞬时合上：和手滑关闭同一条缓动，观感一致。
    // 宽屏是并排布局，侧栏留在原地，切换到哪条、新建几条都不影响它开着。
    val closePaneIfCovering: () -> Unit = {
        if (!isWide) {
            drawerScope.launch {
                if (drawerState.targetValue != DrawerValue.Closed ||
                    drawerState.currentValue != DrawerValue.Closed
                ) {
                    @Suppress("DEPRECATION")
                    drawerState.animateTo(
                        targetValue = DrawerValue.Closed,
                        anim = DrawerCloseMotion,
                    )
                }
            }
        }
    }

    val selectConversation: (String) -> Unit = { conversationId ->
        onConversationSelected(conversationId)
        closePaneIfCovering()
    }

    LaunchedEffect(visible, isWide) {
        // 宽屏只做并排停靠，根本不组抽屉，也就没有要驱动的开合动作。
        if (isWide) return@LaunchedEffect
        // 窄屏：抽屉就是侧栏本体，直接按 expanded 开合（保留原有推平与缓动）。
        val target = if (visible) DrawerValue.Open else DrawerValue.Closed
        if (drawerState.targetValue == target) return@LaunchedEffect
        if (!visible && drawerState.currentValue == DrawerValue.Open) {
            // 程序化收起（新建对话、返回键等）：抽屉是完全打开的，瞬时合上。
            drawerState.snapTo(target)
            return@LaunchedEffect
        }
        @Suppress("DEPRECATION")
        drawerState.animateTo(
            targetValue = target,
            anim = if (visible) DrawerOpenMotion else DrawerCloseMotion,
        )
    }

    LaunchedEffect(drawerState, isWide) {
        // 宽屏没有抽屉，手势/回写都不成立。
        if (isWide) return@LaunchedEffect
        // 丢掉首次发射：首次组合时抽屉按展开态恢复，不能被当成刚被划回而误收。
        snapshotFlow { drawerState.currentValue to drawerState.isAnimationRunning }
            .drop(1)
            .collectLatest { (value, running) ->
                // 快滑时 currentValue 会在 settle 开始就切换。立刻改 visible 会让
                // 聊天页在惯性动画中间整页重组，看起来就是顿一下。
                if (running) return@collectLatest
                if (value == DrawerValue.Open) {
                    if (!currentVisible) currentOnOpen()
                } else if (currentVisible) {
                    currentOnDismiss()
                }
            }
    }

    LaunchedEffect(visible) {
        // 并排停靠时抽屉是合上的，但侧栏仍在屏幕上，所以同样要挡住 IME 的可见性判断。
        drawerBlocksIme = visible
    }

    // 侧栏宿主页（首页）被导航盖住、整页移出组合后，复位调用方持有的「展开」标记。
    // 转场期间必须保持展开：此时侧栏是首页仅有的可见表面，若提前合上会露出聊天主页
    // 造成闪动；等首页真正移出组合再复位，回首页时侧栏仍从收起态开始。
    DisposableEffect(Unit) {
        onDispose { currentOnDismiss() }
    }

    LaunchedEffect(drawerState, visible, focusManager, keyboard, view) {
        snapshotFlow {
            visible ||
                drawerState.targetValue == DrawerValue.Open ||
                drawerState.currentValue == DrawerValue.Open
        }.collect { shouldHide ->
            if (shouldHide) hideChatInputIme(focusManager, keyboard, view)
        }
    }

    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = visible &&
            backHandlerEnabled &&
            sceneLifecycleState == Lifecycle.State.RESUMED,
        onBackCompleted = { currentOnDismiss() },
    )

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 紧凑窗口：Material 3 Expressive 的侧边栏是全屏态——铺满宽度、直角；
        // 走「平移推动」，侧栏把页面推走，而不是盖在页面上再压暗。
        // 宽屏（平板横屏）：固定宽度的 side sheet，且只做并排停靠，不提供覆盖形态。
        // 分支条件与上面几个 effect 共用 isWide，避免两处判定来源不一致。
        val widePane = isWide
        val paneWidth = if (widePane) {
            minOf(DrawerMetrics.SideSheetMaxWidth, maxWidth * DrawerMetrics.SideSheetWidthFraction)
        } else {
            maxWidth
        }
        // 两种形态都是通高、贴边：宽屏并排时侧栏是 standard side sheet，窄屏是整屏平推，
        // 都不需要圆角。
        val drawerShape = DockedPaneShape
        // 拖过「过半」那条线时给一次轻触反馈。抽屉只有开/合两个锚点，手指感受不到
        // 卡位，加一道振动手感才知道自己已经决定了松手后的归宿。
        // 只在真的越过瞬间触发一次（拿上一帧的 bool 做边沿检测），来回蹭不会连续震。
        // offset 语义：闭合锚点是「-面板宽度」，打开锚点是 0，
        // 所以「越过过半」等价于 |offset| < 面板宽度 / 2。
        LaunchedEffect(drawerState, paneWidth, view, density) {
            val halfPanePx = with(density) { paneWidth.toPx() / 2f }
            var wasPastCommitLine by mutableStateOf(false)
            snapshotFlow {
                val offset = drawerState.currentOffset
                if (offset.isNaN()) false else abs(offset) < halfPanePx
            }.collectLatest { pastLine ->
                if (pastLine != wasPastCommitLine) {
                    wasPastCommitLine = pastLine
                    if (pastLine && !drawerState.isAnimationRunning) {
                        TouchHaptics.gestureThreshold(view)
                    }
                }
            }
        }
        CompositionLocalProvider(LocalConversationDrawerBlocksIme provides drawerBlocksIme) {
        val paneContent: @Composable (AbsoluteRoundedCornerShape, Dp) -> Unit =
            { shape, panelWidth ->
                ConversationPanePanel(
                    state = state,
                    width = panelWidth,
                    drawerShape = shape,
                    onOpenSearchHistory = onOpenSearchHistory,
                    onConversationSelected = selectConversation,
                    onConversationRename = onConversationRename,
                    onConversationDelete = onConversationDelete,
                    onMoveConversationToFolder = onMoveConversationToFolder,
                    onConversationTogglePin = onConversationTogglePin,
                    onConversationExport = onConversationExport,
                    onConversationAnalysisExport = onConversationAnalysisExport,
                    onOpenManageChats = onOpenManageChats,
                    onOpenManageFolders = onOpenManageFolders,
                    onSelectFolder = onSelectFolder,
                    onRestoreConversation = onRestoreConversation,
                    onConversationDeleteForever = onConversationDeleteForever,
                    onEmptyTrash = onEmptyTrash,
                    onOpenTrash = onOpenTrash,
                    selectedAssistantId = selectedAssistantId,
                    onSelectAssistant = onSelectAssistant,
                    onEditAssistant = onEditAssistant,
                    onOpenAssistants = onOpenAssistants,
                    onOpenSettings = onOpenSettings,
                    onOpenUsageStats = onOpenUsageStats,
                    modifier = Modifier.fillMaxHeight(),
                )
            }
        if (widePane) {
            // 宽屏（平板横屏）只有并排一种形态：侧栏从左向右平移进来、对话列同步收窄，
            // 不压暗、不淡入，两块表面在同一条水平线上，没有叠放的层级感。
            WideSidePaneLayout(
                visible = visible,
                gestureEnabled = backHandlerEnabled,
                paneWidth = paneWidth,
                onOpen = { onOpen() },
                onDismiss = { onDismiss() },
                paneContent = { width -> paneContent(DockedPaneShape, width) },
                content = content,
            )
        } else {
            // 平推（非覆盖）：侧栏占据左侧 paneWidth，聊天页整体右移同样的距离，不加遮罩、不压暗。
            // 手势收敛与锚点仍交给 Material3 的 Dismissible 实现，只是内容被推走而不是被盖住。
            DismissibleNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = backHandlerEnabled,
                modifier = Modifier.fillMaxSize(),
                drawerContent = {
                    paneContent(
                        drawerShape,
                        paneWidth,
                    )
                },
            ) {
                content()
            }
        }
        }
    }
}

/**
 * 宽屏（平板横屏）的并排侧栏：侧栏从左侧平移进入，对话列同步收窄。
 *
 * 只有一个进度值 [progress]（0 = 收起 / 1 = 展开）同时驱动两侧宽度：
 * 侧栏占据 `paneWidth * progress`，内部固定按 [paneWidth] 排版并左移 `-(1 - progress) * paneWidth`，
 * 因此看到的是整块侧栏向右滑入，而不是宽度被拉伸后再淡入——没有叠层、压暗或投影。
 * 对话列用 `weight(1f)` 吃掉剩余宽度，随进度连续变窄。
 *
 * 手势与手机端一致：向右滑拉开、向左滑推回，松手后按位置 + 速度决定去向，
 * 收敛用同一套 [DrawerSettleSpringSpec] 弹簧；按钮等程序化开合用同一套非线性缓动。
 */
@Composable
private fun WideSidePaneLayout(
    visible: Boolean,
    gestureEnabled: Boolean,
    paneWidth: Dp,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    paneContent: @Composable (Dp) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val currentVisible by rememberUpdatedState(visible)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val paneWidthPx = with(density) { paneWidth.toPx() }.coerceAtLeast(1f)
    val fullPanePx = paneWidthPx.roundToInt()
    val currentPaneWidthPx by rememberUpdatedState(paneWidthPx)
    // 首帧就按展开态落位：从别的页面回来时不该再播一遍滑入。
    val progress = remember { Animatable(if (visible) 1f else 0f) }
    val dragging = remember { mutableStateOf(false) }
    val pastHalf = remember { mutableStateOf(false) }
    // 侧栏只在「露出宽度由零变为非零」时进入组合：进度本身每帧都在变，
    // 不能让整块列表跟着逐帧重组。
    var paneComposed by remember { mutableStateOf(visible) }
    LaunchedEffect(progress) {
        snapshotFlow { progress.value > 0f }.collect { paneComposed = it }
    }
    val velocityThresholdPx = with(density) { DrawerMetrics.SettleVelocityThresholdDp.dp.toPx() }

    // 程序化开合（汉堡按钮、返回键、新建 / 切换会话）：与手机端抽屉同一套缓动。
    LaunchedEffect(visible) {
        if (dragging.value) return@LaunchedEffect
        val target = if (visible) 1f else 0f
        if (progress.value == target) return@LaunchedEffect
        progress.animateTo(target, if (visible) DrawerOpenMotion else DrawerCloseMotion)
    }

    val dragState = rememberDraggableState { delta ->
        val next = (progress.value + delta / currentPaneWidthPx).coerceIn(0f, 1f)
        if (next != progress.value) {
            scope.launch { progress.snapTo(next) }
        }
        // 越过一半时补一次轻触反馈，和手机端抽屉的「过半」手感一致。
        val past = next >= 0.5f
        if (past != pastHalf.value) {
            pastHalf.value = past
            TouchHaptics.gestureThreshold(view)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (gestureEnabled) {
                    Modifier.draggable(
                        state = dragState,
                        orientation = Orientation.Horizontal,
                        onDragStarted = {
                            dragging.value = true
                            // 以当前进度为基线，否则从收起态起手会立刻误报一次「过半」。
                            pastHalf.value = progress.value >= 0.5f
                            scope.launch { progress.stop() }
                        },
                        onDragStopped = { velocity ->
                            dragging.value = false
                            // 甩得够快就按方向定锚，否则看拖过半没有——与手机端同一套判据。
                            val velocityFraction = velocity / currentPaneWidthPx
                            val target = when {
                                velocityFraction >= velocityThresholdPx / currentPaneWidthPx -> 1f
                                velocityFraction <= -velocityThresholdPx / currentPaneWidthPx -> 0f
                                progress.value >= DrawerMetrics.SettlePositionalThreshold -> 1f
                                else -> 0f
                            }
                            scope.launch {
                                progress.animateTo(
                                    targetValue = target,
                                    animationSpec = DrawerSettleSpringSpec(density.density),
                                    initialVelocity = velocityFraction,
                                )
                                // 停稳后再回写展开状态：动画中间改会让整页重组，看着像顿一下。
                                if (target == 1f) {
                                    if (!currentVisible) currentOnOpen()
                                } else if (currentVisible) {
                                    currentOnDismiss()
                                }
                            }
                        },
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        if (paneComposed) {
            // 侧栏固定按完整宽度排版（文字不重排），外层只报出「露出多宽」、内层向左让位，
            // 画出来就是整块侧栏从左向右滑入，而不是宽度被拉伸或淡入。
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .layout { measurable, constraints ->
                        val revealed = (fullPanePx * progress.value).roundToInt()
                            .coerceIn(0, constraints.maxWidth)
                        val placeable = measurable.measure(
                            Constraints.fixed(fullPanePx, constraints.maxHeight),
                        )
                        layout(revealed, placeable.height) {
                            // 让侧栏的右缘贴住露出的右缘：进度 0 完全在屏幕外，1 完全归位。
                            placeable.place(x = revealed - fullPanePx, y = 0)
                        }
                    }
                    .clipToBounds(),
            ) {
                paneContent(paneWidth)
            }
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // 被侧栏挤窄后内容列按剩余宽度重新算居中留白，而不是继续按整窗宽度算。
            CompositionLocalProvider(LocalPageContentWidth provides maxWidth) {
                content()
            }
        }
    }
}

@Composable
private fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: androidx.compose.ui.unit.Dp,
    drawerShape: AbsoluteRoundedCornerShape,
    onOpenSearchHistory: () -> Unit = {},
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit = {},
    onConversationTogglePin: (ConversationSummaryUi) -> Unit = {},
    onConversationExport: (ConversationSummaryUi) -> Unit = {},
    onConversationAnalysisExport: (ConversationSummaryUi) -> Unit = {},
    onOpenManageChats: () -> Unit = {},
    onOpenManageFolders: () -> Unit = {},
    onSelectFolder: (String?) -> Unit = {},
    onRestoreConversation: (String) -> Unit = {},
    onConversationDeleteForever: (String) -> Unit = {},
    onEmptyTrash: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    selectedAssistantId: String = "",
    onSelectAssistant: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsageStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = selectedAssistantId.ifBlank { repositoryActiveId }
    val activeAssistant = remember(profiles, activeId) {
        profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    }
    Material3ConversationPane(
        state = state,
        width = width,
        drawerShape = drawerShape,
        onOpenSearchHistory = onOpenSearchHistory,
        onConversationSelected = onConversationSelected,
        onConversationRename = onConversationRename,
        onConversationDelete = onConversationDelete,
        onMoveConversationToFolder = onMoveConversationToFolder,
        onConversationTogglePin = onConversationTogglePin,
        onConversationExport = onConversationExport,
        onConversationAnalysisExport = onConversationAnalysisExport,
        onOpenManageChats = onOpenManageChats,
        onOpenManageFolders = onOpenManageFolders,
        onSelectFolder = onSelectFolder,
        onRestoreConversation = onRestoreConversation,
        onConversationDeleteForever = onConversationDeleteForever,
        onEmptyTrash = onEmptyTrash,
        onOpenTrash = onOpenTrash,
        onOpenSettings = onOpenSettings,
        onOpenUsageStats = onOpenUsageStats,
        modifier = modifier,
    )
}

@Composable
internal fun FolderNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    // 打开即聚焦并唤起输入法；键盘 Done 等价于「保存」（空名时不响应）。
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    EtaDialog(
        show = true,
        title = title,
        onDismissRequest = onDismiss,
        actions = {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_save),
                confirmEnabled = name.isNotBlank(),
                onCancel = onDismiss,
                onConfirm = { onConfirm(name) },
            )
        },
    ) {
        Column {
            EtaTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.drawer_folder_name),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboard?.hide()
                        if (name.isNotBlank()) onConfirm(name)
                    },
                ),
            )
        }
    }
}

/**
 * Material 3 Expressive 会话抽屉：搜索栏、相连按钮组、日期分组标题、会话列表、底部工具组。
 */
@Composable
private fun Material3ConversationPane(
    state: ConversationPaneUiState,
    width: androidx.compose.ui.unit.Dp,
    drawerShape: AbsoluteRoundedCornerShape,
    onOpenSearchHistory: () -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit,
    onConversationTogglePin: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationAnalysisExport: (ConversationSummaryUi) -> Unit,
    onOpenManageChats: () -> Unit,
    onOpenManageFolders: () -> Unit,
    onSelectFolder: (String?) -> Unit,
    onRestoreConversation: (String) -> Unit = {},
    onConversationDeleteForever: (String) -> Unit = {},
    onEmptyTrash: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenUsageStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 抽屉不再做标题过滤：搜索一律走与管理聊天页同一个全文搜索页（[onOpenSearchHistory]），
    // 这里只按当前文件夹展示全部会话。
    val visibleConversations = state.conversations
    val groups = remember(visibleConversations) { visibleConversations.groupForDrawer() }
    val folders = state.folders
    val view = LocalView.current
    // 抽屉底色。胶囊行的两端淡出带必须取同一个颜色，否则会浮出一层色差。
    val paneContainerColor = MaterialTheme.colorScheme.surfaceContainer

    M3Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .graphicsLayer {
                clip = true
                shape = drawerShape
            },
        shape = drawerShape,
        // 与 M3 页面背景（壳层 Scaffold 的 surfaceContainer）同色，抽屉盖在页面上不再有色差。
        color = paneContainerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        // 底部导航栏（手势小白条 / 三键栏）只补给工具栏和列表末尾：列表本身铺到屏幕底缘，
        // 滚动时内容从导航栏下方透出，而不是整块留白。
        val navigationBarBottom =
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // 顶部只按 statusBars 留白，与主页顶栏（Material3HomeTopBar）同口径：顶栏就是按
        // statusBars 留白的，这里若并入 displayCutout，部分设备上刘海顶部 inset 高于状态栏，
        // 整排胶囊会比主页顶栏按钮低几 dp，中线对不齐。横向仍按 systemBars ∪ displayCutout
        // 避开刘海与系统栏。键盘动画期间这些 inset 恒定（ime 只影响 bottom），
        // 抽屉不会逐帧重测量/重组。
        val paneTopInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top)
        val paneHorizontalInsets = WindowInsets.systemBars
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal)
        val paneInsets = paneTopInsets.union(paneHorizontalInsets)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(paneInsets)
                .consumeWindowInsets(paneInsets)
                .padding(horizontal = DrawerMetrics.PaneHorizontalPadding),
        ) {
            Spacer(modifier = Modifier.height(DrawerMetrics.TopInset))

            // 胶囊行：左侧可横向滚动的「聊天 / 未分类 / [文件夹…]」，右侧固定「管理文件夹」。
            // 行内混着两类高度：官方 ToggleButton 是纯 40dp 布局；走官方 Surface 的按钮
            // （FilledTonalButton、文件夹胶囊的 Surface）自带 48dp 最小触摸目标——布局被撑到
            // 48dp、40dp 可视区在内部居中，整行随之变 48dp，全部胶囊的可视中线整体下移 4dp，
            // 比首页顶栏（状态栏下 32dp）低一截。这里把行内的最小触摸目标统一到 40dp
            // （= 官方 ToggleButton 的布局高度），整行恢复 40dp，中线回到 12 + 20 = 32dp。
            val chipCount = 2 + folders.size
            val chipScrollState = rememberScrollState()
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(chipScrollState),
                        verticalAlignment = Alignment.CenterVertically,
                        // 官方相连按钮组间距：ButtonGroupDefaults.ConnectedSpaceBetween = 2dp
                        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                    ) {
                        var chipIndex = 0
                        Material3DrawerChip(
                            text = stringResource(R.string.drawer_chats_chip),
                            position = drawerGroupPosition(chipIndex++, chipCount),
                            selected = state.selectedFolderId == null,
                            selectable = true,
                            leadingIcon = null,
                            onClick = { onSelectFolder(null) },
                        )
                        Material3DrawerChip(
                            text = stringResource(R.string.drawer_uncategorized_chip),
                            position = drawerGroupPosition(chipIndex++, chipCount),
                            selected = state.selectedFolderId == UNCATEGORIZED_FOLDER_ID,
                            selectable = true,
                            leadingIcon = null,
                            onClick = { onSelectFolder(UNCATEGORIZED_FOLDER_ID) },
                        )
                        // 文件夹胶囊与「聊天 / 未分类」同款官方 ToggleButton；
                        // 重命名 / 删除只在文件夹管理页提供，这里长按不再弹菜单。
                        folders.forEach { folder ->
                            Material3DrawerChip(
                                text = folder.name,
                                position = drawerGroupPosition(chipIndex++, chipCount),
                                selected = state.selectedFolderId == folder.id,
                                selectable = true,
                                leadingIcon = Icons.Rounded.Folder,
                                onClick = { onSelectFolder(folder.id) },
                            )
                        }
                    }
                    // 两端淡出带：滚动到中间时，被视口切一半的胶囊与抽屉底色融合，不留硬切边。
                    ChipRowEdgeFades(
                        scrollState = chipScrollState,
                        color = paneContainerColor,
                    )
                }
                Spacer(modifier = Modifier.width(ButtonGroupDefaults.ConnectedSpaceBetween))
                // 独立胶囊按钮（DrawerGroupPosition.Single）：始终贴右，不随文件夹列表滚动。
                // 文件夹的新建 / 重命名 / 删除都在独立管理页完成，这里只负责入口。
                Material3DrawerChip(
                    text = stringResource(R.string.drawer_manage_folders),
                    position = DrawerGroupPosition.Single,
                    selected = false,
                    leadingIcon = Icons.Rounded.Folder,
                    onClick = onOpenManageFolders,
                )
            }
            }
            Spacer(modifier = Modifier.height(12.dp))

            // 列表不再给底部工具栏让位：铺满到抽屉底缘（含导航栏区域），工具栏以药丸形态浮在列表上方。
            // 底部 contentPadding 预留「工具栏高度 + 离底间距 + 间隙 + 导航栏高度」，
            // 最后一条会话仍能完整滚出到工具栏和手势条上方。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .streamDiagnosticMeasure("drawer.lazy.measure")
                        .streamDiagnosticPlacement("drawer.lazy.place")
                        .streamDiagnosticDraw("drawer.lazy.draw"),
                    contentPadding = PaddingValues(
                        bottom = DrawerMetrics.ToolbarListBottomPadding + navigationBarBottom,
                    ),
                    overscrollEffect = null,
                ) {
                    if (visibleConversations.isEmpty()) {
                        item(contentType = "drawer-empty") {
                            Material3EmptyConversations()
                        }
                    } else {
                        groups.forEachIndexed { groupIndex, group ->
                            val isLastGroup = groupIndex == groups.lastIndex
                            item(key = group.key, contentType = "drawer-section") {
                                Material3DrawerSectionHeader(
                                    label = group.localizedLabel(),
                                    count = group.items.size,
                                )
                            }
                            itemsIndexed(
                                items = group.items,
                                key = { _, conversation -> conversation.id },
                                contentType = { _, _ -> "drawer-conversation" },
                            ) { index, conversation ->
                                Material3ConversationRow(
                                    conversation = conversation,
                                    index = index,
                                    count = group.items.size,
                                    isLastGroup = isLastGroup,
                                    selected = conversation.id == state.selectedConversationId,
                                    onClick = { onConversationSelected(conversation.id) },
                                    onRename = { onConversationRename(conversation) },
                                    onDelete = { onConversationDelete(conversation) },
                                    onMoveToFolder = { onMoveConversationToFolder(conversation) },
                                    onTogglePin = { onConversationTogglePin(conversation) },
                                    onExport = { onConversationExport(conversation) },
                                    onExportAnalysis = { onConversationAnalysisExport(conversation) },
                                )
                            }
                        }
                    }
                }
                // 底部入口：搜索 / 管理聊天 / 用量 / 回收站 / 设置，同一颗居中药丸。
                Material3DrawerToolbar(
                    onOpenSearchHistory = onOpenSearchHistory,
                    onOpenManageChats = onOpenManageChats,
                    onOpenUsageStats = onOpenUsageStats,
                    onOpenTrash = onOpenTrash,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 与列表同一口径：工具栏整体抬到导航栏上方，保持官方 16dp 离底间距。
                        .padding(
                            bottom = DrawerMetrics.ToolbarBottomOffset + navigationBarBottom,
                        ),
                )
            }
        }
    }

}

/**
 * 横向胶囊行（聊天 / 未分类 / 文件夹）两端的淡出带。
 *
 * 胶囊行可以横向滚动，但视口自身没有内边距：滚到中间时，首尾胶囊会被视口边缘
 * 竖直切开，留下一条生硬的截断边。这里在两端各盖一条与抽屉底色同色的渐变，
 * 把切开的胶囊「擦」进背景里。
 *
 * 只在对应方向真的还有内容可滚时才显示（[ScrollState.canScrollBackward]
 * / [ScrollState.canScrollForward]），
 * 停在两端时不会把完整可见的胶囊再罩上一层。渐变透明端用同 RGB 的 alpha 0，
 * 不用 `Color.Transparent`（黑色全透明），避免渐出一道灰边。
 */
@Composable
private fun BoxScope.ChipRowEdgeFades(
    scrollState: ScrollState,
    color: Color,
) {
    // 边缘淡出带只变透明度：M3E Effects fast 档，临界阻尼无过冲。
    val chipEdgeFadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val startAlpha by animateFloatAsState(
        targetValue = if (scrollState.canScrollBackward) 1f else 0f,
        animationSpec = chipEdgeFadeSpec,
        label = "drawerChipFadeStart",
    )
    val endAlpha by animateFloatAsState(
        targetValue = if (scrollState.canScrollForward) 1f else 0f,
        animationSpec = chipEdgeFadeSpec,
        label = "drawerChipFadeEnd",
    )
    Box(modifier = Modifier.matchParentSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(DrawerMetrics.ChipRowEdgeFadeWidth)
                .graphicsLayer { alpha = startAlpha }
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(color, color.copy(alpha = 0f)),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(DrawerMetrics.ChipRowEdgeFadeWidth)
                .graphicsLayer { alpha = endAlpha }
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(color.copy(alpha = 0f), color),
                    ),
                ),
        )
    }
}

/** M3 抽屉：相连按钮组里的位置，决定官方相连形状哪一侧保持完全圆角。 */
private enum class DrawerGroupPosition { Single, Leading, Middle, Trailing }

private fun drawerGroupPosition(index: Int, count: Int): DrawerGroupPosition = when {
    count <= 1 -> DrawerGroupPosition.Single
    index <= 0 -> DrawerGroupPosition.Leading
    index >= count - 1 -> DrawerGroupPosition.Trailing
    else -> DrawerGroupPosition.Middle
}

/**
 * 语音设置页 / 外观设置页同款的官方 `ToggleButtonShapes`，全部取自 `ButtonGroupDefaults`：
 * 外侧 `CornerFull`、内侧 8dp，按压时内圆角收到 4dp，选中态 `checkedShape` 为
 * `connectedButtonCheckedShape`（四角全大圆角）。
 *
 * 抽屉里可选中胶囊（第二排的「聊天 / 未分类」）恒为相连组的首 / 中 / 末位，
 * `Single` 分支用官方单体形状令牌（`ToggleButtonDefaults.shape` 三件套）兜底。
 */
@Composable
private fun drawerToggleButtonShapes(position: DrawerGroupPosition): ToggleButtonShapes = when (position) {
    DrawerGroupPosition.Single -> ToggleButtonShapes(
        ToggleButtonDefaults.shape,
        ToggleButtonDefaults.pressedShape,
        ToggleButtonDefaults.checkedShape,
    )
    DrawerGroupPosition.Leading -> ButtonGroupDefaults.connectedLeadingButtonShapes()
    DrawerGroupPosition.Middle -> ButtonGroupDefaults.connectedMiddleButtonShapes()
    DrawerGroupPosition.Trailing -> ButtonGroupDefaults.connectedTrailingButtonShapes()
}

/** 抽屉的组内位置 → M3E 套件的相连位置（[etaConnectedButtonShapes] / `EtaMorphChipShape` 取后者的枚举）。 */
private fun DrawerGroupPosition.toEtaConnectedPosition(): EtaConnectedPosition = when (this) {
    DrawerGroupPosition.Single -> EtaConnectedPosition.Single
    DrawerGroupPosition.Leading -> EtaConnectedPosition.Leading
    DrawerGroupPosition.Middle -> EtaConnectedPosition.Middle
    DrawerGroupPosition.Trailing -> EtaConnectedPosition.Trailing
}

/**
 * 抽屉相连按钮组配色：与语音设置页 / 外观设置页同款（[EtaExpressiveToggleButton] 里的官方
 * `ToggleButton` 默认档）—— 未选中 `surfaceContainerHighest` / `onSurface`，选中
 * `primary` / `onPrimary`。
 *
 * 供组内非 `ToggleButton` 成员（不可选中的 `FilledTonalButton`）取同一套色。
 */
@Composable
private fun drawerConnectedGroupColors(selected: Boolean): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return if (selected) {
        scheme.primary to scheme.onPrimary
    } else {
        scheme.surfaceContainerHighest to scheme.onSurface
    }
}

/**
 * M3 抽屉：相连按钮组里的文字按钮。
 *
 * 可选中项（`selectable = true`，第二排的「聊天 / 未分类」）用语音设置页 / 外观设置页同款的
 * [EtaExpressiveToggleButton]：官方 `ToggleButton` + 官方 `ButtonGroupDefaults` 连体形状，
 * 选中 / 取消选中由官方组件内部对形状做 morph（`checkedShape` 已是四角全大圆角），
 * 因此是连续渐变而不是闪现。
 *
 * 不可选中项（第一排动作按钮、独立「新建文件夹」）保持官方 `FilledTonalButton` + 官方连体形状
 * （它们不切换选中态、也就没有形状变化），配色对齐到同一套官方档位。
 */
@Composable
private fun Material3DrawerChip(
    text: String,
    position: DrawerGroupPosition,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectable: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    // 高度不覆盖，直接落在官方 Small 档（Button 与 ToggleButton 的 MinHeight 同档）。
    // 内容排布照官方 ButtonGroupSamples.kt：Icon → Spacer(IconSpacing) → Text。
    // 图标尺寸取官方 icon 槽的推荐档（iconSizeFor(Small) = 20dp，与文字行高同高）：
    // 图标 + 官方上下 10dp 内容边距恰好落回 40dp MinHeight，与纯文字胶囊等高；
    // 默认 24dp 图标会把内容撑到 44dp，带图标胶囊比纯文字胶囊高 4dp。
    val content: @Composable RowScope.() -> Unit = {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.iconSizeFor(ToggleButtonDefaults.MinHeight)),
            )
            Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
        }
        M3Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (selectable) {
        EtaExpressiveToggleButton(
            checked = selected,
            // 单选组：只在「被选中」时回调，点已选中项不会产生 uncheck 的中间态。
            onCheckedChange = { checked -> if (checked) onClick() },
            shapes = drawerToggleButtonShapes(position),
            modifier = modifier.semantics { role = Role.RadioButton },
            content = content,
        )
    } else {
        val (containerColor, contentColor) = drawerConnectedGroupColors(selected = false)
        FilledTonalButton(
            onClick = onClick,
            shapes = etaConnectedButtonShapes(position.toEtaConnectedPosition()),
            modifier = modifier,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = containerColor,
                contentColor = contentColor,
            ),
            content = content,
        )
    }
}

/**
 * M3 抽屉：底部浮动工具栏。
 *
 * 官方 M3E [HorizontalFloatingToolbar] 承载会话抽屉的全部入口（搜索 / 管理聊天 / 用量 / 回收站 / 设置），
 * 药丸整体居中、悬浮在会话列表上方；容器与内容色取官方 vibrant 档，
 * 高度、圆角、海拔全部走官方令牌，内边距按 [EtaFloatingToolbarConcentricPadding]
 * 把水平方向收到与端盖同心，`expanded = true` 常驻展开。
 */
@Composable
private fun Material3DrawerToolbar(
    onOpenSearchHistory: () -> Unit,
    onOpenManageChats: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 官方 minimumInteractiveBalancedPadding 只在两侧都没有 leading/trailing 内容时生效：
    // 它会把内容里的 MinimumInteractive…AlignmentLine 当成补偿内边距，把整条工具栏撑高。
    // 本行布局完全显式（固定尺寸 + 固定间距），显式传空的 leadingContent 让这组补偿归零
    // （官方参数语义，非 hack，与输入区 / 浏览器工具栏同款处理）。
    val toolbarColors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()
    HorizontalFloatingToolbar(
        expanded = true,
        colors = toolbarColors,
        // 端盖同心：官方 8dp 内边距会让 40dp 圆钮贴住药丸端头（圆心距端盖弧心差 4dp），
        // 沿弧的留白中段窄、上下宽。水平改取 (64 − 40)/2 = 12dp，圆钮四周留白等宽、与端盖同心。
        contentPadding = EtaFloatingToolbarConcentricPadding(
            IconButtonDefaults.smallContainerSize().width,
        ),
        leadingContent = {},
        modifier = modifier,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Material3DrawerToolbarButton(
                icon = Icons.Rounded.Search,
                contentDescription = stringResource(R.string.action_search_history),
                contentColor = toolbarColors.toolbarContentColor,
                // 与管理聊天页的放大镜同一个目的地：全文搜索页（跨会话检索标题与消息）。
                onClick = onOpenSearchHistory,
            )
            Material3DrawerToolbarButton(
                icon = Icons.Rounded.ManageHistory,
                contentDescription = stringResource(R.string.drawer_manage_chats),
                contentColor = toolbarColors.toolbarContentColor,
                onClick = onOpenManageChats,
            )
            Material3DrawerToolbarButton(
                icon = Icons.Rounded.BarChart,
                contentDescription = stringResource(R.string.stats_page_title),
                contentColor = toolbarColors.toolbarContentColor,
                onClick = onOpenUsageStats,
            )
            Material3DrawerToolbarButton(
                icon = Icons.Rounded.DeleteSweep,
                contentDescription = stringResource(R.string.drawer_trash_title),
                contentColor = toolbarColors.toolbarContentColor,
                onClick = onOpenTrash,
            )
            Material3DrawerToolbarButton(
                icon = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.route_settings),
                contentColor = toolbarColors.toolbarContentColor,
                onClick = onOpenSettings,
            )
        }
    }
}

/**
 * 浮动工具栏里的图标按钮：官方标准 [IconButton] 的 Small 档（40dp 容器 / 24dp 图标），
 * 内容色直接取工具栏自己的内容色，容器透明，视觉上就是药丸里的一枚裸图标。
 */
@Composable
private fun Material3DrawerToolbarButton(
    icon: ImageVector,
    contentDescription: String?,
    contentColor: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
        colors = IconButtonDefaults.iconButtonColors(contentColor = contentColor),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
}

/** 官方下拉菜单项：形状走统一的 `EtaMenuItemShape`，行高 / 字阶 / 颜色全用官方默认值。 */
@Composable
private fun DrawerActionMenuItem(
    index: Int,
    count: Int,
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val contentColor = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified
    DropdownMenuItem(
        text = { M3Text(text = text, color = contentColor) },
        shape = EtaMenuItemShape(index = index, count = count),
        leadingIcon = {
            // 非破坏性项不显式指定 tint：`Color.Unspecified` 会让 Icon 走矢量原始色（黑色），
            // 深色模式下菜单底板上几乎不可见，这里交给 LocalContentColor 取官方图标色。
            if (destructive) {
                Icon(imageVector = icon, contentDescription = null, tint = contentColor)
            } else {
                Icon(imageVector = icon, contentDescription = null)
            }
        },
        onClick = onClick,
    )
}

/**
 * M3 抽屉的日期分组标题：「标题 · 数量」一行内联展示，不再右侧单独放计数。
 *
 * 款式照搬设置页 `settingsGroup` 的分类标题：官方 `titleSmallEmphasized` + primary 色。
 * 节奏沿用分段列表令牌：
 * - 上一组末行已补 [EtaSegmentedGroupSpacing]（13dp）组间距，标题自身不再加上边距；
 * - 标题到本组首行的间距用 [EtaSegmentedTitleSpacing]（8dp）；
 * - 标题左边缘贴分段卡片左边缘（与设置页组标题一致，抽屉外层已有 16dp 页边距）。
 */
@Composable
private fun Material3DrawerSectionHeader(label: String, count: Int) {
    M3Text(
        text = "$label · $count",
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = EtaSegmentedTitleSpacing),
    )
}

/** M3 抽屉空态：官方 Text + 官方排版 / 颜色角色。 */
@Composable
private fun Material3EmptyConversations() {
    M3Text(
        text = stringResource(R.string.conversation_empty),
        style = etaTextStyle(EtaType.Secondary),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(ListItemDefaults.ContentPadding),
    )
}

/**
 * M3 抽屉：会话行。
 *
 * 与设置页（错误重连等）同款分段列表样式：[EtaSegmentedItem] 按组内 index/count 注入
 * 首/末圆角，[EtaSegmentedRow] 走官方 `SegmentedListItem`（`surfaceBright` 容器、
 * 选中态 tertiaryContainer），组内行距 2dp、组末补 13dp 组间距（末组末行除外，
 * 由列表 contentPadding 收尾）；行首不加 radio 图标。
 * 长按菜单仍用官方 `DropdownMenu` / `DropdownMenuItem`。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Material3ConversationRow(
    conversation: ConversationSummaryUi,
    index: Int,
    count: Int,
    isLastGroup: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveToFolder: () -> Unit,
    onTogglePin: () -> Unit,
    onExport: () -> Unit,
    onExportAnalysis: () -> Unit,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    EtaSegmentedItem(index = index, count = count) {
        // 组内行距走官方分段缝隙（2dp），组末行补足分组间距（末组由列表底部留白收尾）；
        // 缝隙放在外层，长按菜单的锚点仍紧贴本行。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    bottom = if (index == count - 1) {
                        if (isLastGroup) 0.dp else EtaSegmentedGroupSpacing
                    } else {
                        EtaSegmentedGap
                    },
                ),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                EtaSegmentedRow(
                    selected = selected,
                    choiceRow = true,
                    // 选中行用 tertiaryContainer（与页面按钮的强调档一致），不覆盖时走官方默认。
                    selectedContainerColor = if (selected) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        null
                    },
                    onClick = onClick,
                    onLongClick = { showActionMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DrawerMetrics.ActiveDotGap),
                        ) {
                            if (conversation.isActiveRun) {
                                // M3E LoadingIndicator（morph 变形）：行尾运行态标记，
                                // 与工具行、AgentStatusCard 的运行态同一套 loading 语言。
                                LoadingIndicator(
                                    modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else if (conversation.hasCompletionMarker) {
                                Box(
                                    modifier = Modifier
                                        .size(DrawerMetrics.ActiveDotSize)
                                        .clip(CircleShape)
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape,
                                        ),
                                )
                            }
                            if (conversation.isPinned) {
                                Icon(
                                    imageVector = Icons.Rounded.PushPin,
                                    contentDescription = stringResource(R.string.conversation_pin),
                                )
                            }
                        }
                    },
                ) {
                    M3Text(
                        text = conversation.title.ifBlank { conversation.preview },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                EtaMenuPopup(
                    expanded = showActionMenu,
                    onDismissRequest = { showActionMenu = false },
                ) {
                    EtaMenuGroup(index = 0, count = 1) {
                        DrawerActionMenuItem(
                            index = 0,
                            count = 6,
                            text = stringResource(R.string.action_rename),
                            icon = Icons.Rounded.Edit,
                            onClick = {
                                showActionMenu = false
                                onRename()
                            },
                        )
                        DrawerActionMenuItem(
                            index = 1,
                            count = 6,
                            text = stringResource(
                                if (conversation.isPinned) R.string.conversation_unpin
                                else R.string.conversation_pin,
                            ),
                            icon = Icons.Rounded.PushPin,
                            onClick = {
                                showActionMenu = false
                                onTogglePin()
                            },
                        )
                        DrawerActionMenuItem(
                            index = 2,
                            count = 6,
                            text = stringResource(R.string.conversation_export),
                            icon = Icons.Rounded.Download,
                            onClick = {
                                showActionMenu = false
                                onExport()
                            },
                        )
                        DrawerActionMenuItem(
                            index = 3,
                            count = 6,
                            text = stringResource(R.string.conversation_export_analysis),
                            icon = Icons.Rounded.QueryStats,
                            onClick = {
                                showActionMenu = false
                                onExportAnalysis()
                            },
                        )
                        DrawerActionMenuItem(
                            index = 4,
                            count = 6,
                            text = stringResource(R.string.drawer_move_to_folder),
                            icon = Icons.Rounded.Folder,
                            onClick = {
                                showActionMenu = false
                                onMoveToFolder()
                            },
                        )
                        DrawerActionMenuItem(
                            index = 5,
                            count = 6,
                            text = stringResource(R.string.conversation_move_to_trash),
                            icon = Icons.Rounded.Delete,
                            onClick = {
                                showActionMenu = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

private val DrawerMotionEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)

private val DrawerOpenMotion: FiniteAnimationSpec<Float> = tween(
    durationMillis = DrawerMetrics.OpenDurationMs,
    easing = DrawerMotionEasing,
)

private val DrawerCloseMotion: FiniteAnimationSpec<Float> = tween(
    durationMillis = DrawerMetrics.CloseDurationMs,
    easing = DrawerMotionEasing,
)

/** 停靠侧栏是 station side sheet：贴着页面站好，直角、无投影。 */
private val DockedPaneShape = AbsoluteRoundedCornerShape(
    topLeft = 0.dp,
    topRight = 0.dp,
    bottomRight = 0.dp,
    bottomLeft = 0.dp,
)

/**
 * 松手后的收敛弹簧：刚度随甩动速度变化。
 *
 * 慢拖轻放用低刚度，柔和靠岸；快甩用高刚度，更快到位。
 * 不管哪种速度，弹簧都会把残余速度平滑衰减到 0 再贴住锚点，
 * 而不是「借惯性冲到锚点后被硬截停」——后者正是高速甩动时发直、发硬的来源。
 *
 * 临界阻尼下初始速度超过 ω·剩余距离就会越过锚点（全屏抽屉越过 0 位会露出主页），
 * 所以按剩余距离给初始速度设了上限。
 */
internal class DrawerSettleSpringSpec(
    private val density: Float,
) : FiniteAnimationSpec<Float> {
    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<Float, V>,
    ): VectorizedFiniteAnimationSpec<V> = object : VectorizedFiniteAnimationSpec<V> {
        private var resolvedStiffness = Float.NaN
        private var resolvedVelocity = Float.NaN
        private var resolvedVelocityVector: V? = null
        private var delegate: VectorizedAnimationSpec<V>? = null

        private fun prepare(
            initialValue: V,
            targetValue: V,
            initialVelocity: V,
        ): VectorizedAnimationSpec<V> {
            val from = converter.convertFromVector(initialValue)
            val target = converter.convertFromVector(targetValue)
            val distance = abs(target - from)
            val rawVelocity = converter.convertFromVector(initialVelocity).takeIf { it.isFinite() } ?: 0f
            val speedDp = abs(rawVelocity) / density
            val speedFraction = (
                (speedDp - DrawerMetrics.SettleSoftVelocityDp) /
                    (DrawerMetrics.SettleFirmVelocityDp - DrawerMetrics.SettleSoftVelocityDp)
                ).coerceIn(0f, 1f)
            val stiffness = DrawerMetrics.SettleStiffnessSlow +
                (DrawerMetrics.SettleStiffnessFast - DrawerMetrics.SettleStiffnessSlow) * speedFraction
            val velocityLimit = DrawerMetrics.SettleVelocityHeadroom * sqrt(stiffness) * distance
            val velocity = if (abs(rawVelocity) > velocityLimit) {
                if (rawVelocity < 0f) -velocityLimit else velocityLimit
            } else {
                rawVelocity
            }
            if (delegate == null || stiffness != resolvedStiffness || velocity != resolvedVelocity) {
                resolvedStiffness = stiffness
                resolvedVelocity = velocity
                resolvedVelocityVector = converter.convertToVector(velocity)
                delegate = spring<Float>(
                    dampingRatio = DrawerMetrics.SettleDampingRatio,
                    stiffness = stiffness,
                ).vectorize(converter)
            }
            return delegate!!
        }

        override fun getValueFromNanos(
            playTimeNanos: Long,
            initialValue: V,
            targetValue: V,
            initialVelocity: V,
        ): V {
            val animation = prepare(initialValue, targetValue, initialVelocity)
            return animation.getValueFromNanos(
                playTimeNanos,
                initialValue,
                targetValue,
                resolvedVelocityVector!!,
            )
        }

        override fun getVelocityFromNanos(
            playTimeNanos: Long,
            initialValue: V,
            targetValue: V,
            initialVelocity: V,
        ): V {
            val animation = prepare(initialValue, targetValue, initialVelocity)
            return animation.getVelocityFromNanos(
                playTimeNanos,
                initialValue,
                targetValue,
                resolvedVelocityVector!!,
            )
        }

        override fun getDurationNanos(
            initialValue: V,
            targetValue: V,
            initialVelocity: V,
        ): Long {
            val animation = prepare(initialValue, targetValue, initialVelocity)
            return animation.getDurationNanos(initialValue, targetValue, resolvedVelocityVector!!)
        }
    }
}

/**
 * 松手收敛不走「惯性投影」，把投影目标固定为当前位置，foundation 因此总会回退到
 * [DrawerSettleSpringSpec]。
 *
 * alpha29 的 `animateToWithDecay`：投影能越过目标锚点时，它直接跑衰减动画，并在到达
 * 锚点的那一帧 `cancelAnimation()`。速度越快，被截断时残留的速度越大——刚好是最需要
 * 柔和落地的地方，却是硬截停。把它交给弹簧，速度才会真正参与整段动画。
 */
private object DrawerNoFlingDecay : DecayAnimationSpec<Float> {
    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<Float, V>,
    ): VectorizedDecayAnimationSpec<V> = object : VectorizedDecayAnimationSpec<V> {
        override val absVelocityThreshold: Float = 1f

        override fun getValueFromNanos(
            playTimeNanos: Long,
            initialValue: V,
            initialVelocity: V,
        ): V = initialValue

        override fun getDurationNanos(initialValue: V, initialVelocity: V): Long = 0L

        override fun getVelocityFromNanos(
            playTimeNanos: Long,
            initialValue: V,
            initialVelocity: V,
        ): V = initialVelocity

        override fun getTargetValue(initialValue: V, initialVelocity: V): V = initialValue
    }
}

/**
 * 抽屉动效调参（Material3 `DismissibleNavigationDrawer` + `AnchoredDraggableState`）。
 *
 * 实测 alpha29 的链路（都对着 sources/javap 核过）：
 * - 手指拖拽：`AnchoredDraggableNode.drag()` → `newOffsetForDelta`，offset 被钳在
 *   闭合锚点（-面板宽度）和打开锚点（0）之间。
 * - 松手：`onDragStopped` → `fling(velocity)`。`DrawerState` 用的是带
 *   positionalThreshold/velocityThreshold/snapAnimationSpec/decayAnimationSpec 的重载构造，
 *   所以 `usePreModifierChangeBehavior == true`，走的是 `state.settle(velocity)` 这条老路。
 * - `settle` → `computeTarget(positionalThreshold, velocityThreshold)` 定目标锚点 →
 *   `animateToWithDecay(snapAnimationSpec, decayAnimationSpec)` 收敛。
 *
 * 关键点：这四个字段都在 `DrawerState` 那个 private final 的 `anchoredDraggableState` 上，
 * 官方没有公开入口改，只能反射；而且它们是 **settle 时现读的**，改完立刻对下一次手势生效。
 * （不要改成 `Modifier.anchoredDraggable(flingBehavior = ...)`：那只有在
 * `usePreModifierChangeBehavior == false` 时才走得到，抽屉用不上。）
 *
 * `DismissibleNavigationDrawer` 的 `SideEffect` 每次组合都会把 DrawerState 自己的
 * open/close/anchored 三个 spec 覆写回官方值，但这三个入口我们都不依赖：程序化开合
 * 全部显式传 tween，手势松手读的是 anchored state 上这四项，M3 从不触碰。
 * 宿主每次组合重设一次，顺带跟上 density 变化（弹簧刚度按 dp/s 换算）。
 *
 * 每一步都单独 `runCatching`：任何一项反射失败就跳过该项，整体退回官方行为，不会崩。
 */
private object DrawerMotionSetters {
    private val anchoredClass = runCatching {
        Class.forName("androidx.compose.foundation.gestures.AnchoredDraggableState")
    }.getOrNull()
    private val function1Class = runCatching {
        Class.forName("kotlin.jvm.functions.Function1")
    }.getOrNull()
    private val function0Class = runCatching {
        Class.forName("kotlin.jvm.functions.Function0")
    }.getOrNull()

    private val anchoredField = runCatching {
        DrawerState::class.java
            .getDeclaredField("anchoredDraggableState")
            .apply { isAccessible = true }
    }.getOrNull()

    private fun method(
        target: Class<*>,
        name: String,
        arg: Class<*>,
    ) = runCatching { target.getMethod(name, arg) }.getOrNull()

    // 阈值和规格的 setter 在 AnchoredDraggableState 上，不在 DrawerState 上，别找错类。
    // 参数类型也得对上：阈值是 Function1/Function0，规格是 AnimationSpec/DecayAnimationSpec。
    // function1Class / function0Class 是 runCatching 探测结果，可能为空：空着就不挂对应
    // setter，手势回退到官方默认阈值，不能把 null 传进 getMethod。
    private val positionalSetter by lazy {
        val anchored = anchoredClass ?: return@lazy null
        val arg = function1Class ?: return@lazy null
        method(anchored, "setPositionalThreshold\$foundation", arg)
    }
    private val velocitySetter by lazy {
        val anchored = anchoredClass ?: return@lazy null
        val arg = function0Class ?: return@lazy null
        method(anchored, "setVelocityThreshold\$foundation", arg)
    }
    private val snapSetter by lazy {
        anchoredClass?.let {
            method(
                it,
                "setSnapAnimationSpec\$foundation",
                Class.forName("androidx.compose.animation.core.AnimationSpec"),
            )
        }
    }
    private val decaySetter by lazy {
        anchoredClass?.let {
            method(
                it,
                "setDecayAnimationSpec\$foundation",
                Class.forName("androidx.compose.animation.core.DecayAnimationSpec"),
            )
        }
    }

    fun apply(state: DrawerState, density: Density) {
        val anchored = anchoredField?.get(state) ?: return
        val settleSpec = DrawerSettleSpringSpec(density.density)
        // 每一项单独兜底：某个 setter 缺失/签名漂移时，其余项仍然生效。
        runCatching { positionalSetter?.invoke(anchored, positionalThresholdProxy()) }
        runCatching { velocitySetter?.invoke(anchored, velocityThresholdProxy(density)) }
        runCatching { snapSetter?.invoke(anchored, settleSpec) }
        runCatching { decaySetter?.invoke(anchored, DrawerNoFlingDecay) }
    }

    /**
     * `positionalThreshold: (Float) -> Float`，入参是两个锚点的距离。
     * 比例越小越「一拉就开」，越大越「拖过半才开」。
     */
    private fun positionalThresholdProxy(): Any {
        val cls = function1Class ?: return Unit
        return java.lang.reflect.Proxy.newProxyInstance(
            cls.classLoader,
            arrayOf(cls),
        ) { _, method, args ->
            when (method.name) {
                "invoke" -> {
                    val distance = args?.firstOrNull() as? Float ?: 0f
                    distance * DrawerMetrics.SettlePositionalThreshold
                }
                else -> null
            }
        }
    }

    /**
     * `velocityThreshold: () -> Float`，松手速度超过它就直接按甩的方向定锚，
     * 不再看位置。越大越「必须甩得够快」。
     */
    private fun velocityThresholdProxy(density: Density): Any {
        val cls = function0Class ?: return Unit
        val thresholdPx = with(density) { DrawerMetrics.SettleVelocityThresholdDp.dp.toPx() }
        return java.lang.reflect.Proxy.newProxyInstance(
            cls.classLoader,
            arrayOf(cls),
        ) { _, method, _ ->
            when (method.name) {
                "invoke" -> thresholdPx
                else -> null
            }
        }
    }
}

private fun DrawerState.applyEtaDrawerMotion(density: Density) {
    DrawerMotionSetters.apply(this, density)
}

internal data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
) {
    val key: String get() = "section-$section"
}

internal sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
}

@Composable
private fun ConversationDrawerGroup.localizedLabel(): String = when (val value = section) {
    ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
    ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
    is ConversationDrawerSection.Dated -> value.label
}

internal fun List<ConversationSummaryUi>.groupForDrawer(): List<ConversationDrawerGroup> {
    // A cached label or a differently ordered input can put the same section in disjoint
    // runs. Merge all of them, keeping the first section occurrence and row order stable.
    val groups = linkedMapOf<ConversationDrawerSection, MutableList<ConversationSummaryUi>>()
    for (conversation in this) {
        groups.getOrPut(conversation.drawerSection()) { mutableListOf() } += conversation
    }
    return groups.map { (section, items) -> ConversationDrawerGroup(section, items.toList()) }
}

private fun ConversationSummaryUi.drawerSection(): ConversationDrawerSection = when {
    isPinned -> ConversationDrawerSection.Pinned
    isToday -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}
