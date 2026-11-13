package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.VectorizedDecayAnimationSpec
import androidx.compose.animation.core.VectorizedFiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus
import io.github.mangi.eta.agent.remote.RemoteSessionInfo
import io.github.mangi.eta.ui.remote.remoteStatusLabel
import io.github.mangi.eta.ui.model.RemotePaneServer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import io.github.mangi.eta.ui.components.streamDiagnosticDraw
import io.github.mangi.eta.ui.components.streamDiagnosticMeasure
import io.github.mangi.eta.ui.components.streamDiagnosticPlacement
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.ManageHistory
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaExpressiveToggleButton
import io.github.mangi.eta.ui.adaptive.EtaFloatingToolbarConcentricPadding
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.adaptive.EtaMotion
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
import io.github.mangi.eta.ui.adaptive.EtaTextField
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaConnectedColor
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
import androidx.compose.ui.semantics.contentDescription
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
import kotlin.math.sign
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
    // 手势松手后的合拢速度下限（dp/s）。低于它算「拖着不放」，按位置判；高于它算甩，
    // 直接朝甩的方向定锚，速度越快越早到位——这是「跟手」的关键。
    // 官方是 400dp/s；取 480 略高一点，避免列表里横向轻扫误关，正常甩动仍能直接命中。
    const val SettleVelocityThresholdDp = 480
    // 位置阈值占全程的比例。官方 0.5 意味着拖过一半才松手。
    // 抽屉是「越长越全」的连续面板，稍微一拉就有反馈更符合预期。
    const val SettlePositionalThreshold = 0.32f
    // 无过冲是硬约束：全屏抽屉越过任一锚点都会露出底下的主页。松手收敛不用弹簧
    // （见 DrawerSettleCurveSpec 的弃用理由），改用恒定减速度的分段二次曲线，
    // 结构性无过冲。
    // 松手收敛标称时长（整步）：450ms，按 sqrt(残程/整步) 收缩（小修正不该拖满整步）、
    // 下限 120ms；峰速触及封顶时标称会被「梯形闭合」反推的实际时长取代——
    // 手递手交接的整步收敛约 460~610ms，与 iOS sheet 同档。
    // 为什么峰速必须封顶（四轮实测体感的结论，作者逐一反馈）：
    //  ① 速度自适应弹簧 520..1600 →「太线性」；② 低刚度临界阻尼弹簧 →「松手后有匀速段」
    // （指数尾速度等比缓降永不归零，读成匀速巡航）；③ Hermite 500ms →「手指右划仍有匀速段」
    // （S 曲线中段平台 + 峰速仅 1.3× 手速，与拖拽匀速段连成一条）；④ Hermite 280ms →
    // 「进/出/轻抛还是很线性」（峰速 2.4× 手速 ≈4800px/s，±10% 峰速带 90ms 掠过 44% 屏宽，
    // 读成匀速扫屏）。注入本身被 DrawerMotionInjectionTest 证明生效，真因是结构性的：
    // 手势松手时面板已在运动中，「从静止起飞」的加速段永远看不到（按钮路径有，所以
    // 按钮一直被评价为「还可以」）；收敛段能传达的只有减速，而减速要被看见，速度必须
    // 落在可跟踪区间——于是改为恒定减速度：峰速封顶、制动段占残程 50% 以上、减速度
    // 全程不衰减、终点精确归零，像刹车而不是滑行。
    const val SettleDurationMillis = 450
    // 短残程收敛的时长下限（ms）：低于它的小修正会显得突兀。
    const val SettleMinDurationMillis = 120f
    // 收敛峰速封顶（单位：整步距离/秒）。实际峰速 = 2.8·√(残程·整步)——随残程收缩，
    // D=整步时 ≈1075dp/s（小米 13 Ultra），整段速度都可跟踪；快甩松手速度若已高于
    // 该峰速，直接进入纯制动（恒定减速从松手速度落到 0，时长 = 2·残程/松手速度）。
    const val SettlePeakVelocityFraction = 2.8f
    val PaneHorizontalPadding = 16.dp
    // 首排（胶囊行）与首页顶栏同一水平中线：首页顶栏是 64dp 行高 + 4dp 纵向内边距，
    // 40dp 图标按钮居中后中线在状态栏下 4 + 8 + 20 = 32dp；这里 12 + 20 = 32dp 与之对齐。
    val TopInset = 12.dp
    val ChipGap = 8.dp
    // 胶囊行横向滚动的两端淡出带宽度。用 DstIn 蒙版淡化这行内容本身，
    // 滚动到中间时把视口切开的胶囊边缘擦掉，底下露出的自然是抽屉底色。
    val ChipRowEdgeFadeWidth = 24.dp
    // 底部浮动工具栏（M3E HorizontalFloatingToolbar with FAB）：可视药丸高 64dp（官方
    // ContainerSize），但官方布局以 FAB 的最大尺寸 80dp（FabSizeRange 上限）作 minHeight、
    // 药丸在其内垂直居中，组合总高 80dp、药丸中线落在总高一半（40dp）处。离底间距取 8dp
    // （官方 ScreenOffset 16dp 减去药丸下方 8dp 的 FAB 预留空隙）：药丸底缘仍在导航栏上
    // 8 + 8 = 16dp（与官方 ScreenOffset 下无 FAB 时的可视位置一致），中线落在导航栏上
    // 8 + 40 = 48dp，与主页输入框单行胶囊（72dp 高、底部留白 12dp，中线 12 + 36 = 48dp）
    // 同一条水平线。列表底部再留 12dp，让最后一条会话能完整滚到药丸上方而不被压住。
    // 这里不含导航栏 inset：列表铺到屏幕底缘，导航栏高度在 Material3ConversationPane 里另加。
    val ToolbarHeight = 64.dp
    val ToolbarBottomOffset = 8.dp
    val ToolbarListGap = 12.dp
    val ToolbarListBottomPadding = ToolbarHeight + ToolbarBottomOffset + ToolbarListGap
    val ActionIconSize = 20.dp
    val RowGap = 2.dp
    val RowCornerRadius = 16.dp
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
    onNewConversation: () -> Unit = {},
    onOpenSearchHistory: () -> Unit = {},
    onOpenManageChats: () -> Unit = {},
    onOpenManageFolders: () -> Unit = {},
    onSelectFolder: (String?) -> Unit = {},
    onRestoreConversation: (String) -> Unit = {},
    onConversationDeleteForever: (String) -> Unit = {},
    onOpenTrash: () -> Unit = {},
    selectedAssistantId: String = "",
    onSelectAssistant: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsageStats: () -> Unit,
    remoteSection: io.github.mangi.eta.ui.model.RemotePaneSection? = null,
    onSelectRemoteSession: (String, String) -> Unit = { _, _ -> },
    onOpenRemoteSettings: () -> Unit = {},
    selectedRemoteServerId: String? = null,
    onSelectRemoteServer: (String) -> Unit = {},
    /** 当前聊天目标是本机会话：为 false（远程目标）时抽屉不高亮任何本机会话行。 */
    isLocalTarget: Boolean = true,
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
    // 不碰手势收敛实际读取的那几项。注入本身在下方窄屏分支内进行（需要 paneWidth）。
    val density = LocalDensity.current
    val drawerScope = rememberCoroutineScope()
    // 侧栏跟随页面退场时用的时长：导航转场时长（500ms）+ 两帧余量，见 EtaMotion.drawerNavExitMillis。
    // 只要不短于新页推入，新页就会先把侧栏盖住，侧栏收得再慢也不会露出空档。
    val drawerNavExitMotion: FiniteAnimationSpec<Float> = remember {
        tween(
            durationMillis = EtaMotion.drawerNavExitMillis().toInt(),
            easing = EtaMotion.DrawerEasing,
        )
    }
    val currentVisible by rememberUpdatedState(visible)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    var drawerBlocksIme by remember { mutableStateOf(visible) }

    // 窄屏的侧栏是全屏覆盖形态，选中会话 / 新建会话 / 系统返回键都要先收起才能看到对话，
    // 走同一条收拢缓动而不是瞬时合上（返回键底下就是主页，直接跳变等于硬切）。
    // 这里只驱动抽屉本身，不动 visible：收拢动画停稳后由下面的「回写」effect 复位展开态，
    // 动画期间首页看到的仍是「侧栏开着」，不会中途先重组成收起态。
    // 宽屏是并排布局，侧栏留在原地，切换到哪条、新建几条都不影响它开着。
    val closePaneWithMotion: () -> Unit = {
        if (!isWide) {
            drawerScope.launch {
                // 已经在收 / 已收好就不再重启：连续按返回键不会把动画从头再播一遍。
                if (drawerState.targetValue != DrawerValue.Closed) {
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
        closePaneWithMotion()
    }

    // 远程会话行与本地会话行走同一条收尾：窄屏先播收拢动画再回到对话页，
    // 宽屏由 closePaneWithMotion 内部跳过（并排停靠不需要收起）。
    // 漏掉这一步会让远程切换后停在侧栏里。
    val selectRemoteSession: (String, String) -> Unit = { serverId, path ->
        onSelectRemoteSession(serverId, path)
        closePaneWithMotion()
    }

    LaunchedEffect(visible, isWide) {
        // 宽屏只做并排停靠，根本不组抽屉，也就没有要驱动的开合动作。
        if (isWide) return@LaunchedEffect
        // 窄屏：抽屉就是侧栏本体，直接按 expanded 开合（保留原有推平与缓动）。
        val target = if (visible) DrawerValue.Open else DrawerValue.Closed
        if (drawerState.targetValue == target) return@LaunchedEffect
        if (!visible && drawerState.currentValue == DrawerValue.Open) {
            // 程序化收起且侧栏随页面一起退场（切页入栈、命中跳转）。
            // 不再 snapTo：瞬时合上会让侧栏先消失、新页后到，中间露出一段空白。
            // 这里改用「与新页推入同收敛时间」的收拢动画——新页从右侧压过来覆盖侧栏，
            // 侧栏在下面同步左移，两者读起来是同一个横向动作，收完时侧栏已完全被盖住。
            // 返回键 / 点选会话不走这里——它们先播收拢动画，动画结束才把 visible 复位。
            @Suppress("DEPRECATION")
            drawerState.animateTo(
                targetValue = target,
                anim = drawerNavExitMotion,
            )
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

    // 首页（侧栏宿主页）被导航盖住、整页移出组合时，刻意不复位调用方持有的「展开」标记。
    // 侧栏展开是用户明确选择的界面状态：从侧栏进入设置 / 管理分类 / 管理会话等页面再退回来，
    // 必须仍停在侧栏页，不能掉回聊天主页。离场时侧栏已随首页一起滑走，不会留下任何可见残留。
    // 反过来，从顶栏 / 首页动作进入这些页面时侧栏本来就是收起的，标记天然是 false，
    // 返回时也就不会凭空弹出侧栏——不需要额外的「哪一页是从侧栏打开的」追踪。

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
        onBackCompleted = {
            // 窄屏：先播收拢动画，停稳后再把展开态复位（回写 effect 负责），避免瞬时合上的硬切。
            // 宽屏侧栏是并排停靠，WideSidePaneLayout 会自己按展开态播放收拢，直接复位即可。
            if (isWide) {
                currentOnDismiss()
            } else if (drawerState.targetValue == DrawerValue.Closed) {
                // 已经在合（或刚合上）：连按返回不重播动画，直接落定并复位，
                // 否则这次返回会被吞掉，展开态卡在 true 就不再响应返回键。
                drawerScope.launch {
                    drawerState.snapTo(DrawerValue.Closed)
                    currentOnDismiss()
                }
            } else {
                closePaneWithMotion()
            }
        },
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
                    // 新建会话后必须落到对话页：窄屏侧栏是全屏覆盖，按「点选会话」同一条路径收起；
                    // 宽屏是并排停靠，closePaneWithMotion 内部会直接跳过，侧栏留在原地。
                    onNewConversation = {
                        onNewConversation()
                        closePaneWithMotion()
                    },
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
                    onOpenTrash = onOpenTrash,
                    selectedAssistantId = selectedAssistantId,
                    onSelectAssistant = onSelectAssistant,
                    onEditAssistant = onEditAssistant,
                    onOpenAssistants = onOpenAssistants,
                    onOpenSettings = onOpenSettings,
                    onOpenUsageStats = onOpenUsageStats,
                    remoteSection = remoteSection,
                    onSelectRemoteSession = selectRemoteSession,
                    onOpenRemoteSettings = onOpenRemoteSettings,
                    selectedRemoteServerId = selectedRemoteServerId,
                    onSelectRemoteServer = onSelectRemoteServer,
                    isLocalTarget = isLocalTarget,
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
            // 阈值/收敛曲线是 anchored state 上的可写字段，这里每次组合重设一次：
            // 跟上 density 与 paneWidth 变化；M3 自带的 SideEffect 只改 open/close/anchored
            // spec，不碰手势收敛实际读取的那几项。
            SideEffect {
                drawerState.applyEtaDrawerMotion(density, with(density) { paneWidth.toPx() })
            }
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
 * 收敛用同一套 [DrawerSettleCurveSpec] 速度匹配曲线；按钮等程序化开合用同一套非线性缓动。
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
                                    // 宽屏动画值是 progress（0..1），整步距离即 1f。
                                    animationSpec = DrawerSettleCurveSpec(fullDistance = 1f),
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
    onNewConversation: () -> Unit = {},
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
    onOpenTrash: () -> Unit = {},
    selectedAssistantId: String = "",
    onSelectAssistant: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenUsageStats: () -> Unit,
    remoteSection: io.github.mangi.eta.ui.model.RemotePaneSection? = null,
    onSelectRemoteSession: (String, String) -> Unit = { _, _ -> },
    onOpenRemoteSettings: () -> Unit = {},
    selectedRemoteServerId: String? = null,
    onSelectRemoteServer: (String) -> Unit = {},
    isLocalTarget: Boolean = true,
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
        onNewConversation = onNewConversation,
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
        onOpenTrash = onOpenTrash,
        onOpenSettings = onOpenSettings,
        onOpenUsageStats = onOpenUsageStats,
        remoteSection = remoteSection,
        onSelectRemoteSession = onSelectRemoteSession,
        onOpenRemoteSettings = onOpenRemoteSettings,
        selectedRemoteServerId = selectedRemoteServerId,
        onSelectRemoteServer = onSelectRemoteServer,
        isLocalTarget = isLocalTarget,
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
    onNewConversation: () -> Unit,
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
    onOpenTrash: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenUsageStats: () -> Unit,
    remoteSection: io.github.mangi.eta.ui.model.RemotePaneSection? = null,
    onSelectRemoteSession: (String, String) -> Unit = { _, _ -> },
    onOpenRemoteSettings: () -> Unit = {},
    selectedRemoteServerId: String? = null,
    onSelectRemoteServer: (String) -> Unit = {},
    /** 远程目标期间为 false：本机会话行一律不高亮（选中态只属于当前聊天目标）。 */
    isLocalTarget: Boolean = true,
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

            // 胶囊行：左侧可横向滚动的「本机 / [远程…] / 未分类 / [分类…]」，右侧固定「管理分类」。
            // 行内混着两类高度：官方 ToggleButton 是纯 40dp 布局；走官方 Surface 的按钮
            // （FilledTonalButton、文件夹胶囊的 Surface）自带 48dp 最小触摸目标——布局被撑到
            // 48dp、40dp 可视区在内部居中，整行随之变 48dp，全部胶囊的可视中线整体下移 4dp，
            // 比首页顶栏（状态栏下 32dp）低一截。这里把行内的最小触摸目标统一到 40dp
            // （= 官方 ToggleButton 的布局高度），整行恢复 40dp，中线回到 12 + 20 = 32dp。
            // 远程服务器胶囊与文件夹胶囊同排：胶囊名 = 用户起的服务器名，前导是实时状态点。
            // 选中哪台服务器，下方列表就只显示那台的状态卡与会话；本机作用域（本机/未分类/
            // 文件夹）与远程胶囊互斥。服务器在设置页删除后这里自然回落本机（匹配不到即视为未选中）。
            val remoteServers = remoteSection?.servers ?: emptyList()
            val selectedRemoteServer = remoteServers.firstOrNull { it.id == selectedRemoteServerId }
            val chipCount = 2 + folders.size + remoteServers.size
            val chipScrollState = rememberScrollState()
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        // 两端淡出带：滚动到中间时，被视口切一半的胶囊会留一条生硬的
                        // 竖直截断边，用 DstIn 蒙版把切开的部分擦掉（底下露出的自然是抽屉底色）。
                        .horizontalScrollEdgeFade(chipScrollState, DrawerMetrics.ChipRowEdgeFadeWidth)
                        .horizontalScroll(chipScrollState),
                    verticalAlignment = Alignment.CenterVertically,
                    // 官方相连按钮组间距：ButtonGroupDefaults.ConnectedSpaceBetween = 2dp
                    horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                ) {
                    var chipIndex = 0
                    Material3DrawerChip(
                        text = stringResource(R.string.drawer_chats_chip),
                        position = drawerGroupPosition(chipIndex++, chipCount),
                        selected = selectedRemoteServer == null && state.selectedFolderId == null,
                        selectable = true,
                        leadingIcon = null,
                        onClick = { onSelectFolder(null) },
                    )
                    // 远程服务器胶囊紧跟「本机」：设备与设备并排，一眼看出这是两台机器。
                    remoteServers.forEach { server ->
                        Material3DrawerChip(
                            text = server.name,
                            position = drawerGroupPosition(chipIndex++, chipCount),
                            selected = selectedRemoteServer?.id == server.id,
                            selectable = true,
                            leadingContent = { RemoteStatusDot(server.status) },
                            onClick = { onSelectRemoteServer(server.id) },
                        )
                    }
                    Material3DrawerChip(
                        text = stringResource(R.string.drawer_uncategorized_chip),
                        position = drawerGroupPosition(chipIndex++, chipCount),
                        selected = selectedRemoteServer == null &&
                            state.selectedFolderId == UNCATEGORIZED_FOLDER_ID,
                        selectable = true,
                        leadingIcon = null,
                        onClick = { onSelectFolder(UNCATEGORIZED_FOLDER_ID) },
                    )
                    // 文件夹胶囊与「本机 / 未分类」同款官方 ToggleButton；
                    // 重命名 / 删除只在文件夹管理页提供，这里长按不再弹菜单。
                    folders.forEach { folder ->
                        Material3DrawerChip(
                            text = folder.name,
                            position = drawerGroupPosition(chipIndex++, chipCount),
                            selected = selectedRemoteServer == null && state.selectedFolderId == folder.id,
                            selectable = true,
                            leadingIcon = Icons.Rounded.Folder,
                            onClick = { onSelectFolder(folder.id) },
                        )
                    }
                }
                Spacer(modifier = Modifier.width(ButtonGroupDefaults.ConnectedSpaceBetween))
                // 独立胶囊按钮（DrawerGroupPosition.Single）：始终贴右，不随文件夹列表滚动。
                // 文件夹的新建 / 重命名 / 删除都在独立管理页完成，这里只负责入口。
                // 图标用 FolderOpen 与左侧文件夹胶囊的 Folder 区分：这是「进入管理」的动作。
                Material3DrawerChip(
                    text = stringResource(R.string.drawer_manage_folders),
                    position = DrawerGroupPosition.Single,
                    selected = false,
                    leadingIcon = Icons.Rounded.FolderOpen,
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
                // 顶部截断淡出带：滚动时上沿被切一半的会话行在这里化开，
                // 不留一条生硬的横切边。做法与主页对话流的顶部带子同一套实现、
                // 同一组数值（带高 40dp，起点即列表视口上沿）。
                // 只在列表真能往上滚（顶部确实有内容被切走）时才出现——停在最上方时
                // 第一行是完整的，不该被削淡；底部不加带子（末尾由浮动工具栏托着）。
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .streamDiagnosticMeasure("drawer.lazy.measure")
                        .streamDiagnosticPlacement("drawer.lazy.place")
                        .streamDiagnosticDraw("drawer.lazy.draw")
                        // 淡出带挂在列表自己身上：蒙版坐标系即视口坐标，
                        // 固定在视口上沿，不随内容滚动位移。
                        .lazyColumnEdgeFade(
                            listState = listState,
                            edges = ScrollEdgeFadeEdges.Start,
                        ),
                    contentPadding = PaddingValues(
                        bottom = DrawerMetrics.ToolbarListBottomPadding + navigationBarBottom,
                    ),
                    overscrollEffect = null,
                ) {
                    // 远程胶囊选中时列表只显示那台服务器的会话行（设备名与状态都在胶囊上，
                    // 不再重复一张状态头；空列表时由 RemoteServerGroup 给一行状态占位），
                    // 本机列表与空态占位一并不渲染。不再有常驻的「远程 · N」分区：入口就是胶囊本身。
                    selectedRemoteServer?.let { server ->
                        item(key = "remote-server-${server.id}", contentType = "drawer-remote-server") {
                            RemoteServerGroup(
                                server = server,
                                selectedServerId = remoteSection?.selectedServerId,
                                selectedPath = remoteSection?.selectedPath,
                                onSelectSession = onSelectRemoteSession,
                                onOpenSettings = onOpenRemoteSettings,
                            )
                        }
                    }
                    if (visibleConversations.isEmpty() && selectedRemoteServer == null) {
                        item(contentType = "drawer-empty") {
                            Material3EmptyConversations()
                        }
                    } else if (selectedRemoteServer == null) {
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
                                    selected = isLocalTarget &&
                                        conversation.id == state.selectedConversationId,
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
                // 底部入口：新建对话 FAB + 搜索 / 管理聊天 / 用量 / 回收站 / 设置，同一颗居中药丸。
                Material3DrawerToolbar(
                    onNewConversation = onNewConversation,
                    onOpenSearchHistory = onOpenSearchHistory,
                    onOpenManageChats = onOpenManageChats,
                    onOpenUsageStats = onOpenUsageStats,
                    onOpenTrash = onOpenTrash,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 与列表同一口径：工具栏整体抬到导航栏上方；组合总高 80dp、可视药丸
                        // 底缘在导航栏上 16dp，中线对齐主页输入框单行胶囊（见 ToolbarBottomOffset）。
                        .padding(
                            bottom = DrawerMetrics.ToolbarBottomOffset + navigationBarBottom,
                        ),
                )
            }
        }
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
    leadingContent: (@Composable () -> Unit)? = null,
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
        } else if (leadingContent != null) {
            // 自定义前导槽（远程胶囊放实时状态点）：与图标路径同一 20dp 槽位、同一间距，
            // 带点胶囊与带图标胶囊等宽等高。10dp 的点在 20dp 槽内居中。
            Box(
                modifier = Modifier.size(ButtonDefaults.iconSizeFor(ToggleButtonDefaults.MinHeight)),
                contentAlignment = Alignment.Center,
            ) {
                leadingContent()
            }
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
 *
 * 行尾挂官方 Floating toolbar with FAB 结构的「新建对话」FAB，与药丸之间是官方 8dp
 * [FloatingToolbarDefaults.ToolbarToFabGap]。配色仍取 vibrant 档，但不用
 * [FloatingToolbarDefaults.VibrantFloatingActionButton]——它把海拔写死在 Level 2 档，
 * 与无阴影的工具栏放一起会浮出一圈投影；这里改用官方 [FloatingActionButton] 显式归零海拔，
 * 容器 / 内容色仍取 [FloatingToolbarColors] 的 vibrant FAB 档。药丸自己的阴影也一并归零
 * （[HorizontalFloatingToolbar] 的两个阴影海拔参数），整条工具栏与 FAB 均无投影。
 */
@Composable
private fun Material3DrawerToolbar(
    onNewConversation: () -> Unit,
    onOpenSearchHistory: () -> Unit,
    onOpenManageChats: () -> Unit,
    onOpenUsageStats: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 带 FAB 的官方 overload 没有 leading/trailingContent 槽位，也没有无 FAB 版那组
    // minimumInteractiveBalancedPadding 补偿，内边距完全由 contentPadding 决定。
    val toolbarColors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()
    HorizontalFloatingToolbar(
        expanded = true,
        // 行尾 FAB：新建对话。位置取官方默认的 End，与药丸之间是官方 ToolbarToFabGap。
        // 纯图标按钮，长按补官方 TooltipBox 提示（复用 FooterTooltipBox 封装）。
        floatingActionButton = {
            FooterTooltipBox(text = stringResource(R.string.action_new_conversation), enabled = true) {
                FloatingActionButton(
                    onClick = onNewConversation,
                    containerColor = toolbarColors.fabContainerColor,
                    contentColor = toolbarColors.fabContentColor,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AddComment,
                        contentDescription = stringResource(R.string.action_new_conversation),
                    )
                }
            }
        },
        colors = toolbarColors,
        // 端盖同心：官方 8dp 内边距会让 40dp 圆钮贴住药丸端头（圆心距端盖弧心差 4dp），
        // 沿弧的留白中段窄、上下宽。水平改取 (64 − 40)/2 = 12dp，圆钮四周留白等宽、与端盖同心。
        contentPadding = EtaFloatingToolbarConcentricPadding(
            IconButtonDefaults.smallContainerSize().width,
        ),
        // 无阴影：药丸的展开 / 收起两档海拔都归零。
        expandedShadowElevation = 0.dp,
        collapsedShadowElevation = 0.dp,
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
                icon = Icons.Rounded.DeleteSweep,
                contentDescription = stringResource(R.string.drawer_trash_title),
                contentColor = toolbarColors.toolbarContentColor,
                onClick = onOpenTrash,
            )
            Material3DrawerToolbarButton(
                icon = Icons.Rounded.BarChart,
                contentDescription = stringResource(R.string.stats_page_title),
                contentColor = toolbarColors.toolbarContentColor,
                onClick = onOpenUsageStats,
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
 *
 * 回收站 / 用量等图标动作不自明，长按补官方 TooltipBox 提示；文案直接复用
 * [contentDescription]，无描述时不包。
 */
@Composable
private fun Material3DrawerToolbarButton(
    icon: ImageVector,
    contentDescription: String?,
    contentColor: Color,
    onClick: () -> Unit,
) {
    val button: @Composable () -> Unit = {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
            colors = IconButtonDefaults.iconButtonColors(contentColor = contentColor),
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
            )
        }
    }
    if (contentDescription == null) {
        button()
    } else {
        FooterTooltipBox(text = contentDescription, enabled = true) { button() }
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
    // 「等待回答」问号徽标的读屏说明（行尾 trailing 槽内引用）。
    val waitingBadgeDescription = stringResource(R.string.dispatch_question_waiting_badge)
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
                    onLongClickLabel = stringResource(R.string.action_conversation_menu),
                    modifier = Modifier.fillMaxWidth(),
                    leadingContent = if (conversation.isDispatchConsole) {
                        // 调度台是系统级条目：行首用专属图标区分，不靠标题匹配。
                        // 颜色跟随行内容色（LocalContentColor）：未选中为常规内容色，选中行
                        // 背景是 tertiaryContainer，图标自动取 onTertiaryContainer，不再出现
                        // primary 画在容器色上的角色错配（浅色/深色/纯黑下都保持可读）。
                        ({
                            Icon(
                                imageVector = Icons.Rounded.Hub,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        })
                    } else if (conversation.isDispatchManaged) ({
                        // 派生会话标记：调度台所建或接续使用中（fork 出的副本不继承）。
                        Icon(
                            imageVector = Icons.Rounded.AccountTree,
                            contentDescription = stringResource(R.string.conversation_dispatch_managed),
                            modifier = Modifier.size(20.dp),
                        )
                    }) else null,
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(EtaDimens.TrailingElementGap),
                        ) {
                            if (conversation.isActiveRun) {
                                // M3E LoadingIndicator（morph 变形）：行尾运行态标记，
                                // 与工具行、AgentStatusCard 的运行态同一套 loading 语言。
                                // 签名按依赖版本 material3 1.5.0-alpha29 源码核实：
                                // `LoadingIndicator(modifier, color: Color = LoadingIndicatorDefaults.indicatorColor, polygons)`；
                                // 默认色走 token `LoadingIndicatorTokens.ActiveIndicatorColor`
                                // = `ColorSchemeKeyTokens.Primary`（即 colorScheme.primary），
                                // 与行底 tertiaryContainer 不成对，选中行会重演角色错配。
                                // 这里显式覆盖为 LocalContentColor：未选中取常规内容色，
                                // 选中行底为 tertiaryContainer 时取 onTertiaryContainer，
                                // 与同行的前导图标、PushPin 保持同一套内容色语义。
                                LoadingIndicator(
                                    modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                                    color = LocalContentColor.current,
                                )
                            } else if (conversation.hasCompletionMarker) {
                                // 完成标记：实心小圆点，表示「有尚未查看的完成结果」。
                                // 官方组件按依赖版本 material3 1.5.0-alpha29 源码核实：
                                // `Badge(modifier, containerColor = BadgeDefaults.containerColor,
                                //   contentColor = contentColorFor(containerColor),
                                //   content: (@Composable RowScope.() -> Unit)? = null)`；
                                // content 为 null 时走指示点形态，尺寸取 token `BadgeTokens.Size`
                                // = 6dp、形状取 `BadgeTokens.Shape` = `ShapeKeyTokens.CornerFull`
                                // （正圆），与既有 EtaDimens.StatusDotSize（6dp）同档，故直接
                                // 用官方默认尺寸，不再另传 size。官方默认 containerColor 是
                                // token `BadgeTokens.Color` = `ColorSchemeKeyTokens.Error`
                                // （通知红点语义），与本行不成对，因此显式覆盖为 LocalContentColor：
                                // 未选中取 trailing 槽内容色 onSurfaceVariant，选中行底为
                                // tertiaryContainer 时取 onTertiaryContainer，与同槽的
                                // loading 指示器、PushPin 保持同一套取色。
                                Badge(containerColor = LocalContentColor.current)
                            }
                            if (conversation.isWaitingQuestion) {
                                // 派发子会话「等待回答」问号徽标：该会话的 run 正阻塞在 ask_user
                                // 上等用户作答，与运行态 loading 并存（一个在跑、一个要人）；
                                // 回答/终态即清。签名按依赖版本 material3 1.5.0-alpha29 源码核实
                                // （Badge.kt:150-155）：`Badge(modifier, containerColor =
                                //   BadgeDefaults.containerColor, contentColor =
                                //   contentColorFor(containerColor),
                                //   content: (@Composable RowScope.() -> Unit)? = null)`；
                                // 带 content 时取 Large 档令牌：minSize 16dp、文字 LabelSmall、
                                // 水平内边距 BadgeWithContentHorizontalPadding（Badge.kt:160-187）。
                                // containerColor 用 tertiary（本抽屉的强调色语言），contentColor
                                // 走默认 contentColorFor(tertiary)=onTertiary 的官方配对；不套
                                // 默认 Error 红——等待回答是「需要你」而非「出错」，且与完成红点
                                // 同槽会撞语义。不用 LocalContentColor：contentColorFor 认不得
                                // 非 theme 色，问号会回退成与底同色而隐形。
                                Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                                    // 问号本身不是文本语义；给行读屏一个明确的「等待回答」说明。
                                    M3Text(
                                        "?",
                                        modifier = Modifier.semantics {
                                            contentDescription = waitingBadgeDescription
                                        },
                                    )
                                }
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
                        text = conversation.displayTitle.ifBlank { conversation.preview },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                EtaMenuPopup(
                    expanded = showActionMenu,
                    onDismissRequest = { showActionMenu = false },
                ) {
                    EtaMenuGroup(index = 0, count = 1) {
                        // 调度台是系统条目：显示名固定，但重命名仍改真实标题；取消置顶、
                        // 移动到分类与删除入口不提供（分类归属是普通会话的整理属性）。
                        val systemEntry = conversation.isDispatchConsole
                        val actionCount = if (systemEntry) 3 else 6
                        var actionIndex = 0
                        DrawerActionMenuItem(
                            index = actionIndex++,
                            count = actionCount,
                            text = stringResource(R.string.action_rename),
                            icon = Icons.Rounded.Edit,
                            onClick = {
                                showActionMenu = false
                                onRename()
                            },
                        )
                        if (!systemEntry) {
                            DrawerActionMenuItem(
                                index = actionIndex++,
                                count = actionCount,
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
                        }
                        DrawerActionMenuItem(
                            index = actionIndex++,
                            count = actionCount,
                            text = stringResource(R.string.conversation_export),
                            icon = Icons.Rounded.Download,
                            onClick = {
                                showActionMenu = false
                                onExport()
                            },
                        )
                        DrawerActionMenuItem(
                            index = actionIndex++,
                            count = actionCount,
                            text = stringResource(R.string.conversation_export_analysis),
                            icon = Icons.Rounded.QueryStats,
                            onClick = {
                                showActionMenu = false
                                onExportAnalysis()
                            },
                        )
                        if (!systemEntry) {
                            DrawerActionMenuItem(
                                index = actionIndex++,
                                count = actionCount,
                                text = stringResource(R.string.drawer_move_to_folder),
                                icon = Icons.Rounded.Folder,
                                onClick = {
                                    showActionMenu = false
                                    onMoveToFolder()
                                },
                            )
                        }
                        if (!systemEntry) {
                            DrawerActionMenuItem(
                                index = actionIndex,
                                count = actionCount,
                                text = stringResource(R.string.conversation_move_to_trash),
                                icon = Icons.Rounded.Delete,
                                destructive = true,
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
}

// 时间轴统一取 EtaMotion 单一出处，本文件不再各自持有时长与曲线。
private val DrawerOpenMotion: FiniteAnimationSpec<Float> = tween(
    durationMillis = EtaMotion.DrawerOpenDurationMillis,
    easing = EtaMotion.DrawerEasing,
)

private val DrawerCloseMotion: FiniteAnimationSpec<Float> = tween(
    durationMillis = EtaMotion.DrawerCloseDurationMillis,
    easing = EtaMotion.DrawerEasing,
)

/** 停靠侧栏是 station side sheet：贴着页面站好，直角、无投影。 */
private val DockedPaneShape = AbsoluteRoundedCornerShape(
    topLeft = 0.dp,
    topRight = 0.dp,
    bottomRight = 0.dp,
    bottomLeft = 0.dp,
)

/**
 * 松手后的收敛曲线：恒定减速度的分段二次曲线（梯形速度剖面）。
 *
 * 速度剖面：从松手速度 u0 以恒定加速度 a 升到峰速 vP，再以同一个 a 匀减速到 0——
 * 全程 |dv/dt| = a 恒定（只在峰速瞬间翻转方向），位置是分段二次函数，闭式可算。
 *
 * 为什么是「恒定减速度」而不是弹簧/Hermite/更大更小的 S 曲线（四轮实测体感迭代，
 * 作者逐一反馈，注入链路由 [DrawerMotionInjectionTest] 证明全程生效）：
 * - 手势松手时面板已经在运动中，「从静止起飞」的加速段永远看不到——按钮路径
 *   （程序化 tween，从静止开始）一直被评价为「还可以」，手势路径一直被评价为「线性」，
 *   差别就在这里。收敛段能传达的只有减速。
 * - 而减速要被眼睛看到，速度必须落在可跟踪区间：峰速过高（Hermite 280ms 方案的
 *   2.4×u0 ≈ 4800px/s）时 ±10% 峰速带在 90ms 内掠过 44% 屏宽，加减速再大也只是
 *   「匀速扫屏」；刹车太软（临界阻尼弹簧的指数尾）速度等比缓降、永不归零，读成
 *   「匀速巡航」后段。两个坑这四轮都踩过。
 * - 恒定减速度同时避开两者：峰速封顶在可跟踪档（[DrawerMetrics.SettlePeakVelocityFraction]），
 *   制动段占残程 50% 以上、减速度全程不衰减（像刹车而不是滑行）、终点速度精确归零
 *   （无爬行尾，isAnimationRunning 及时落定）。进侧栏 / 出侧栏 / 轻轻抛出 / 快甩共用。
 *
 * 参数（u0 = 朝目标的速度，可负=阈值下反向松手；D = 残程）：
 * - 峰速封顶 vPcap = 2.8·√(D·整步)（[DrawerMetrics.SettlePeakVelocityFraction]，随残程收缩）。
 *   u0 ≥ vPcap（快甩）→ 纯制动：vP = u0，T = 2D/u0，a = u0²/(2D)。
 * - 否则从标称时长 T_nom = 450·√(D/整步)（[DrawerMetrics.SettleDurationMillis]，钳
 *   [DrawerMetrics.SettleMinDurationMillis] 下限）解梯形闭合方程
 *   `2T·vP² − 4D·vP + (2D·u0 − T·u0²) = 0` 取大根；解出的 vP 超过 vPcap 时取 vPcap
 *   并用 `T = 2D(2vP−u0)/(2vP²−u0²)` 反推实际时长（制动为主，速度连续性不受影响）。
 *   a = (2vP−u0)/T，加速段时长 t1 = (vP−u0)/a。
 *
 * 不变量（DrawerSettleMotionTest 全量锚定）：速度处处连续（t=0 = u0）、v ≥ 0 单调
 * （u0 ≥ 0 时结构性无过冲）、终点速度与位置精确归零、制动段速度线性下降
 * （对抗指数尾回归）。整步距离 [fullDistance] 用动画值单位（窄屏 DrawerState = 屏宽 px；
 * 宽屏 Animatable progress = 1f），峰速/时长公式与单位无关，窄宽两端共用。
 *
 * 不用 tween 的官方 API 是因为它不能 seed 速度（松手瞬间速度断崖）；不走
 * `animateToWithDecay` 的衰减路径见 [DrawerNoFlingDecay]。
 */
internal class DrawerSettleCurveSpec(
    private val fullDistance: Float,
) : FiniteAnimationSpec<Float> {
    override fun <V : AnimationVector> vectorize(
        converter: TwoWayConverter<Float, V>,
    ): VectorizedFiniteAnimationSpec<V> = object : VectorizedFiniteAnimationSpec<V> {
        // 三元组（from, target, u0）→收敛计划是纯函数，每次调用现算现用，
        // 同一实例被不同三元组交替调用（测试、复用）也不会串状态。
        private var delta = Float.NaN
        private var durationMillis = Float.NaN
        private var toward = Float.NaN
        private var peakVelocity = Float.NaN
        private var acceleration = Float.NaN
        private var surgeMillis = Float.NaN
        private var surgeDistance = Float.NaN

        private fun prepare(
            initialValue: V,
            targetValue: V,
            initialVelocity: V,
        ) {
            val from = converter.convertFromVector(initialValue)
            val target = converter.convertFromVector(targetValue)
            delta = target - from
            val distance = abs(delta)
            if (distance < EpsilonDistance || fullDistance < EpsilonDistance) {
                // 零距离本来就在锚点上：0 时长立即完成。运动字段一并归零，
                // 防止复用同一 vectorize 实例时读到上一组计划的残留值。
                durationMillis = 0f
                toward = 0f
                peakVelocity = 0f
                acceleration = 0f
                surgeMillis = 0f
                surgeDistance = 0f
                return
            }
            val rawVelocity = converter.convertFromVector(initialVelocity).takeIf { it.isFinite() } ?: 0f
            // 朝目标的速度（值单位/秒）；背离目标时为负，梯形加速段会先折返再正向加速。
            toward = rawVelocity * sign(delta)
            val peakCap = DrawerMetrics.SettlePeakVelocityFraction * sqrt(distance * fullDistance)
            if (toward >= peakCap) {
                // 快甩：松手速度已高于可跟踪峰速，直接恒定减速落到 0。
                peakVelocity = toward
                durationMillis = 2f * distance / toward * 1000f
            } else {
                val nominalMillis = (DrawerMetrics.SettleDurationMillis * sqrt(distance / fullDistance))
                    .coerceIn(DrawerMetrics.SettleMinDurationMillis, DrawerMetrics.SettleDurationMillis.toFloat())
                val nominalSeconds = nominalMillis / 1000f
                // 梯形闭合：T·vP² + (T·u0 − 2D)·vP + D·u0 − T·u0²/2 = 0 的大根。
                // （由 D = (u0+vP)/2·t1 + vP/2·t2、T = t1+t2 消去 t1/t2。）
                val discriminant = 4f * distance * distance -
                    2f * nominalSeconds * toward * (2f * distance - nominalSeconds * toward)
                val solved = if (discriminant > 0f) {
                    (2f * distance + sqrt(discriminant)) / (2f * nominalSeconds)
                } else {
                    peakCap
                }
                peakVelocity = minOf(solved, peakCap)
                durationMillis = if (solved <= peakCap) {
                    nominalMillis
                } else {
                    2f * distance * (2f * peakVelocity - toward) /
                        (2f * peakVelocity * peakVelocity - toward * toward) * 1000f
                }
            }
            acceleration = (2f * peakVelocity - toward) / (durationMillis / 1000f)
            surgeMillis = if (acceleration > 0f) (peakVelocity - toward) / acceleration * 1000f else 0f
            val surgeSeconds = surgeMillis / 1000f
            surgeDistance = (toward + peakVelocity) / 2f * surgeSeconds
        }

        /** 沿目标方向的已行进距离（值单位）。t 已钳到 [0, T]。 */
        private fun traveledAt(tSeconds: Float): Float {
            return if (tSeconds <= surgeMillis / 1000f) {
                toward * tSeconds + acceleration * tSeconds * tSeconds / 2f
            } else {
                val brakeSeconds = tSeconds - surgeMillis / 1000f
                surgeDistance + peakVelocity * brakeSeconds - acceleration * brakeSeconds * brakeSeconds / 2f
            }
        }

        /** 沿目标方向的速度（值单位/秒）。t 已钳到 [0, T]。 */
        private fun velocityAt(tSeconds: Float): Float {
            return if (tSeconds <= surgeMillis / 1000f) {
                toward + acceleration * tSeconds
            } else {
                maxOf(peakVelocity - acceleration * (tSeconds - surgeMillis / 1000f), 0f)
            }
        }

        private fun clampSeconds(playTimeNanos: Long): Float {
            if (durationMillis <= 0f) return durationMillis / 1000f
            val tSeconds = playTimeNanos / 1_000_000_000f
            return tSeconds.coerceIn(0f, durationMillis / 1000f)
        }

        override fun getValueFromNanos(
            playTimeNanos: Long,
            initialValue: V,
            targetValue: V,
            initialVelocity: V,
        ): V {
            prepare(initialValue, targetValue, initialVelocity)
            val from = converter.convertFromVector(initialValue)
            // sign 而非 delta/|delta|：delta 恰为 0（已在锚点上）时后者是 0/0 = NaN。
            return converter.convertToVector(from + sign(delta) * traveledAt(clampSeconds(playTimeNanos)))
        }

        override fun getVelocityFromNanos(
            playTimeNanos: Long,
            initialValue: V,
            targetValue: V,
            initialVelocity: V,
        ): V {
            prepare(initialValue, targetValue, initialVelocity)
            val along = velocityAt(clampSeconds(playTimeNanos))
            return converter.convertToVector(along * sign(delta))
        }

        override fun getDurationNanos(
            initialValue: V,
            targetValue: V,
            initialVelocity: V,
        ): Long {
            prepare(initialValue, targetValue, initialVelocity)
            return (durationMillis * 1_000_000f).toLong()
        }
    }

    private companion object {
        const val EpsilonDistance = 1e-4f
    }
}

/**
 * 松手收敛不走「惯性投影」，把投影目标固定为当前位置，foundation 因此总会回退到
 * [DrawerSettleCurveSpec]。
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
 * 宿主每次组合重设一次，顺带跟上 density 与 paneWidth 变化（速度阈值按 dp 折算 px，
 * 收敛曲线的整步距离取屏宽）。
 *
 * 每一步都单独 `runCatching`：任何一项反射失败就跳过该项，整体退回官方行为，不会崩。
 * internal 供 DrawerMotionInjectionTest 在 JVM 上验证注入真的落位（runCatching 会吞错，
 * 没有这条测试，注入失败只能靠体感反推）。
 */
internal object DrawerMotionSetters {
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

    fun apply(state: DrawerState, density: Density, fullDistancePx: Float) {
        val anchored = anchoredField?.get(state) ?: return
        // 窄屏动画值单位是 px，整步距离 = 屏宽（闭合锚点 −paneWidth 到打开锚点 0）。
        val settleSpec = DrawerSettleCurveSpec(fullDistance = fullDistancePx)
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

private fun DrawerState.applyEtaDrawerMotion(density: Density, fullDistancePx: Float) {
    DrawerMotionSetters.apply(this, density, fullDistancePx)
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

/**
 * 远程胶囊选中后的列表：这台服务器的会话行（点击切到该远程会话）。设备名与连接状态
 * 已由胶囊本身承载（名字 + 状态点），这里不再重复一张状态头。会话列表为空（从未连上 /
 * 刚断开）时退化为一行状态占位：只有状态文本、不带设备名，点击进远程设置页。
 * 整组作为一个分段列表渲染，行距 / 圆角与本机会话行同一套官方分段令牌。
 */
@Composable
private fun RemoteServerGroup(
    server: RemotePaneServer,
    selectedServerId: String?,
    selectedPath: String?,
    onSelectSession: (String, String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (server.sessions.isEmpty()) {
        EtaSegmentedItem(index = 0, count = 1) {
            EtaSegmentedRow(
                onClick = onOpenSettings,
                choiceRow = true,
                modifier = Modifier.fillMaxWidth(),
                leadingContent = { RemoteStatusDot(server.status) },
            ) {
                M3Text(
                    text = remoteStatusLabel(server.status),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        server.sessions.forEachIndexed { index, session ->
            val selected = selectedServerId == server.id && selectedPath == session.path
            val isGroupLast = index == server.sessions.lastIndex
            EtaSegmentedItem(index = index, count = server.sessions.size) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = if (isGroupLast) EtaSegmentedGroupSpacing else EtaSegmentedGap),
                ) {
                    EtaSegmentedRow(
                        selected = selected,
                        choiceRow = true,
                        selectedContainerColor = if (selected) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            null
                        },
                        onClick = { onSelectSession(server.id, session.path) },
                        modifier = Modifier.fillMaxWidth(),
                        supportingContent = session.modified.takeIf { it.isNotBlank() }?.let { modified ->
                            {
                                // 字号 / 配色全部走官方 SegmentedListItem 槽位默认：
                                // 标题 bodyLarge、副文本 bodyMedium + onSurfaceVariant，
                                // 与本机会话行同一套（此前显式 bodyMedium 把标题压小了）。
                                M3Text(
                                    text = modified,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                    ) {
                        M3Text(
                            text = remoteSessionTitle(session),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 远程会话的展示名：服务端给的标题优先，其次首条消息，最后路径尾段。
 * 顶栏标题与侧栏会话行必须共用同一取名规则，否则同一会话在两处显示不同名字。
 */
internal fun remoteSessionTitle(session: RemoteSessionInfo): String =
    session.name?.takeIf { it.isNotBlank() }
        ?: session.firstMessage.takeIf { it.isNotBlank() }
        ?: session.path.substringAfterLast('/').ifBlank { session.path }

@Composable
internal fun RemoteStatusDot(status: RemoteConnectionStatus) {
    val color = when (status) {
        // 已连接固定走成功语义绿（etaConnectedColor）：抽屉、分类页、设置页三处同源。
        // 不用 primary：它与胶囊选中态（primaryContainer 一族）同源，pi 胶囊被选中时
        // 点会融进选中底色里；也不再用 vibrant FAB 容器色，因为那会随壁纸漂移。
        RemoteConnectionStatus.Connected -> etaConnectedColor()
        RemoteConnectionStatus.Connecting, is RemoteConnectionStatus.Reconnecting ->
            MaterialTheme.colorScheme.tertiary
        is RemoteConnectionStatus.Failed -> MaterialTheme.colorScheme.error
        RemoteConnectionStatus.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(EtaDimens.RemoteStatusDotSize)
            .clip(CircleShape)
            .background(color),
    )
}
