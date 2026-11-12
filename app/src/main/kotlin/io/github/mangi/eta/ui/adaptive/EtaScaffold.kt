package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.layout.rememberPageContentSidePadding

/**
 * 页面骨架所在的 list-detail 详情栏层级：`null` = 不在详情栏里（普通二级页）。
 *
 * `0` = 详情栏栈底，也就是二级页第一层 —— 它身后没有可返回的上层，层级返回靠点左栏或
 * 系统返回，因此顶栏**不画返回箭头**；`> 0` = 压在栈底之上的三级/四级页，照旧带箭头。
 *
 * 由承载详情栏的宿主按右栏栈深发布（见 `AgentAppRoot` 的 `entry<AppRoute.Settings>`）。
 * 判据只在页面**首次组合**时取一次（见 [EtaMaterialScaffoldPage] 里的 `remember`）：
 * 栈底页被三级页压住时栈深会变成 2，若持续观察，push 动画第一帧就会在栈底页上闪出箭头。
 */
val LocalEtaDetailPaneDepth: ProvidableCompositionLocal<Int?> = compositionLocalOf { null }

/**
 * 二级列表页骨架：标准 [Scaffold] + [LargeFlexibleTopAppBar] + [LazyColumn]。
 *
 * [titleContent] 非空时顶栏标题槽改放该内容（如页内搜索框），替代 [title] 文本。
 */
@Composable
fun EtaScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    topBarModifier: Modifier = Modifier,
    listModifier: Modifier = Modifier,
    floatingActionButton: (@Composable () -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    EtaMaterialScaffoldPage(
        title = title,
        onBack = onBack,
        modifier = modifier,
        actions = actions,
        listState = listState,
        topBarModifier = topBarModifier,
        listModifier = listModifier,
        floatingActionButton = floatingActionButton,
        titleContent = titleContent,
        bottomBar = bottomBar,
        content = content,
    )
}

/**
 * FAB 的展开态随列表滚动收起 / 展开（KernelSU `TemplateMaterial` 同款阈值状态机）：
 * 回到顶部必定展开；向下滚动累计超过 [threshold] 收起；向上滚动累计超过 [threshold] 展开。
 *
 * 供页面用 `EtaScaffoldPage(floatingActionButton = …)` + `EtaExtendedFab(expanded = …)` 消费。
 */
@Composable
fun rememberFabExpandedState(
    listState: LazyListState,
    threshold: Dp = 100.dp,
): State<Boolean> {
    val thresholdPx = with(LocalDensity.current) { threshold.toPx() }
    return remember(listState, thresholdPx) {
        var lastIndex = 0
        var lastOffset = 0
        var scrollDelta = 0f
        var expanded = true
        derivedStateOf {
            val currentIndex = listState.firstVisibleItemIndex
            val currentOffset = listState.firstVisibleItemScrollOffset
            val delta = when {
                currentIndex == lastIndex -> (currentOffset - lastOffset).toFloat()
                currentIndex > lastIndex -> thresholdPx
                else -> -thresholdPx
            }
            scrollDelta = (scrollDelta + delta).coerceIn(-thresholdPx, thresholdPx)
            lastIndex = currentIndex
            lastOffset = currentOffset
            when {
                currentIndex == 0 -> {
                    expanded = true
                    scrollDelta = 0f
                }
                expanded && scrollDelta >= thresholdPx -> {
                    expanded = false
                    scrollDelta = 0f
                }
                !expanded && scrollDelta <= -thresholdPx -> {
                    expanded = true
                    scrollDelta = 0f
                }
            }
            expanded
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EtaMaterialScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    topBarModifier: Modifier = Modifier,
    listModifier: Modifier = Modifier,
    floatingActionButton: (@Composable () -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    // 大标题随滚动折叠成单行顶栏（M3 LargeTopAppBar）。
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    // 宽屏下内容列居中限宽：列表仍铺满全宽，只把条目收进最大宽度内。
    val sidePadding = rememberPageContentSidePadding()
    // 顶栏自己会吃 `TopAppBarDefaults.windowInsets`，横屏挖孔侧会向右错一大截；
    // 列表必须补同一份水平安全区，卡片左缘才会和大标题/返回按钮落在同一条竖线上。
    // 取证：material3 1.5.0-alpha29 sources jar
    //   `commonMain/androidx/compose/material3/AppBar.kt:1521` windowInsets =
    //     `WindowInsets.systemBarsForVisualComponents.only(Horizontal + Top)`；
    //   `androidMain/.../internal/SystemBarsDefaultInsets.android.kt` 实测该值 =
    //     `systemBars.union(displayCutout)`（内部 API，这里用公开 API 复现同一集合）；
    //   `AppBar.kt:3552/3557` TopAppBarHorizontalPadding=4.dp / TopAppBarTitleInset=12.dp，
    //   展开态大标题左缘 = insetStart + 16.dp，与分段行 `EtaGroupHorizontalPadding=16.dp`
    //   加上同一 insetStart 后严格相等。
    val topBarGutter = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val gutterStart = topBarGutter.calculateStartPadding(layoutDirection)
    val gutterEnd = topBarGutter.calculateEndPadding(layoutDirection)
    // 详情栏栈底（二级页第一层）不画返回箭头。判据只在首次组合时读一次：栈底页在被三级页
    // 压住时栈深会变成 2，持续观察会让 push 动画第一帧在栈底页上闪出一个箭头。
    // 去掉箭头不会让大标题错位：material3 1.5.0-alpha29 sources jar
    //   `commonMain/androidx/compose/material3/AppBar.kt:2855` 里两行顶栏的展开层恒传
    //   `navigationIcon = {}`，`:3027` 的 `titleStartSpace = max(TopAppBarTitleInset, navIconWidth)`
    //   因此展开态大标题始终在 `TopAppBarTitleInset(12.dp) + TopAppBarHorizontalPadding(4.dp)`
    //   = 16.dp；收起态少了 48.dp 的箭头槽后标题也从 52.dp 落到 16.dp，与展开态同一竖线。
    val detailPaneDepth = LocalEtaDetailPaneDepth.current
    val showBackButton = remember { detailPaneDepth == null || detailPaneDepth > 0 }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // KernelSU 式：整页背景与顶栏同用 surfaceContainer，分段条目（surfaceBright）浮在其上。
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        bottomBar = { bottomBar?.invoke() },
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    if (titleContent != null) {
                        titleContent()
                    } else {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                modifier = topBarModifier,
                navigationIcon = {
                    if (showBackButton) {
                        EtaTopBarBackButton(
                            onClick = onBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        // Scaffold 不会为 FAB 让出导航栏安全区，这里按 KernelSU `TemplateMaterial` 的做法补上。
        floatingActionButtonPosition = FabPosition.End,
        floatingActionButton = floatingActionButton?.let { fab ->
            {
                Box(
                    modifier = Modifier.padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                        // 宽屏下 FAB 跟着收进内容列右缘，不飘到屏幕最右；
                        // 与顶栏动作按钮共用同一份水平安全区。
                        end = sidePadding + gutterEnd,
                    ),
                ) {
                    fab()
                }
            }
        } ?: {},
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = listModifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                start = sidePadding + gutterStart,
                end = sidePadding + gutterEnd,
            ),
        ) {
            content()
            item(key = "eta_bottom_spacer") {
                // FAB 浮在列表之上，末条内容需要额外让出「Small Extended FAB 高度 + 底部间距」。
                Spacer(
                    modifier = Modifier
                    .height(if (floatingActionButton != null) 88.dp else 24.dp)
                    .navigationBarsPadding(),
                )
            }
        }
    }
}

/**
 * 自定义内容二级页骨架。
 *
 * 内容回调提供 `paddingValues`、用于 `nestedScroll` 的滚动连接，以及宽屏内容侧边距。
 */
@Composable
fun EtaScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (
        paddingValues: PaddingValues,
        scrollConnection: NestedScrollConnection,
        sidePadding: Dp,
    ) -> Unit,
) {
    EtaMaterialScaffold(title, onBack, modifier, actions, bottomBar, content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EtaMaterialScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues, NestedScrollConnection, Dp) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    // 宽屏下内容列居中限宽：自定义内容也要与列表页保持同一条内容边界。
    val sidePadding = rememberPageContentSidePadding()
    // 详情栏栈底页同样不画返回箭头（口径见 [EtaMaterialScaffoldPage]）。
    val detailPaneDepth = LocalEtaDetailPaneDepth.current
    val showBackButton = remember { detailPaneDepth == null || detailPaneDepth > 0 }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // KernelSU 式：整页背景与顶栏同用 surfaceContainer，分段条目（surfaceBright）浮在其上。
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        // 与壳层同一口径：底部不垫导航栏，页面背景与滚动内容铺到屏幕底缘（内容从手势条下方透出），
        // 导航栏高度由各页在滚动内容末尾或底栏内部补；有 bottomBar 时 innerPadding.bottom 仍是栏高。
        contentWindowInsets = WindowInsets.systemBars
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        bottomBar = { bottomBar?.invoke() },
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (showBackButton) {
                        EtaTopBarBackButton(
                            onClick = onBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            content(paddingValues, scrollBehavior.nestedScrollConnection, sidePadding)
        }
    }
}
