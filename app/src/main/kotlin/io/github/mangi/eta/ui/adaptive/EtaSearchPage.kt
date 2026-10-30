package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.layout.rememberPageContentSidePadding
import kotlinx.coroutines.delay

/**
 * 搜索页骨架：顶栏与 [EtaSearchPage] 完全同款——返回钮 + 透明底搜索框（无边框融入标题栏）
 * + 有输入时出现的清除按钮——但**查询状态与列表内容全部交给调用方**。
 *
 * 与 [EtaSearchPage] 的分工：[EtaSearchPage] 把「搜索历史 / 官方空态 / 结果」写死在页内，
 * 且没有搜索词就不渲染内容；本骨架只提供顶栏与列表容器，适合「顶栏常驻 + 还要挂常驻
 * 筛选条」的场景（媒体语义搜索把类型标签固定在顶栏下方，搜索前后都能切换类型）。
 * 这类页自绘搜索记录时用 [EtaSearchHistoryItems]，与 [EtaSearchPage] 共用同一份版式。
 *
 * @param query 当前搜索词。
 * @param onQueryChange 搜索词变化回调。
 * @param searchPlaceholder 搜索框占位文案。
 * @param onBack 顶栏返回钮回调。
 * @param onSubmit IME「搜索」键回调（参数为当前搜索词）；回调后自动收起键盘。
 * @param autoFocus 进入页面是否自动聚焦并弹出输入法。
 * @param showClear 是否显示顶栏右侧的清除按钮。
 * @param actions 顶栏右侧动作区（排在清除按钮之后）。
 * @param topContent 顶栏下方的常驻内容（如筛选标签条），随顶栏一起固定不随列表滚动。
 * @param bottomSpacer 列表末尾是否补一段底部安全区间隙。
 * @param content 列表内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtaSearchScaffold(
    query: String,
    onQueryChange: (String) -> Unit,
    searchPlaceholder: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onSubmit: ((String) -> Unit)? = null,
    autoFocus: Boolean = true,
    showClear: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    topContent: (@Composable () -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    bottomSpacer: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    // 进入页面即聚焦并弹出输入法（等输入框摆好后请求，失败稍候重试）。
    if (autoFocus) {
        LaunchedEffect(Unit) {
            repeat(10) {
                if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(32)
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // KernelSU 式：整页背景与顶栏同用 surfaceContainer，分段条目（surfaceBright）浮在其上。
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            Column(
                // 顶栏整块（连同下方的常驻标签条）自带不透明底色：未选中的 FilterChip
                // 容器色是透明的，少了这层底色就会透出后面的内容。
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                TopAppBar(
                    title = {
                        TextField(
                            value = query,
                            onValueChange = onQueryChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge,
                            placeholder = {
                                Text(
                                    text = searchPlaceholder,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    onSubmit?.invoke(query)
                                    focusManager.clearFocus()
                                },
                            ),
                        )
                    },
                    navigationIcon = {
                        EtaTopBarBackButton(
                            onClick = onBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    },
                    actions = {
                        // 有输入内容时才出现清除按钮。
                        if (showClear && query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.action_close),
                                )
                            }
                        }
                        actions()
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                )
                topContent?.invoke()
            }
        },
    ) { innerPadding ->
        val sidePadding = rememberPageContentSidePadding()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // 列表视口整体落在顶栏之下，而不是伸进顶栏里再靠 contentPadding 顶开：
                // 顶栏下方可能挂常驻内容（如媒体搜索的类型标签条），未选中的 FilterChip 底色
                // 是透明的，视口一旦伸进去，滚动内容就会从标签缝隙里透出来——看着像标签变透明、
                // 内容和标签重叠。把视口整体压到顶栏之下，这块区域始终是干净的页面底色。
                .padding(top = innerPadding.calculateTopPadding()),
            // 宽屏下结果列居中限宽，列表本体仍铺满全宽，两侧不留滚动死区。
            contentPadding = PaddingValues(start = sidePadding, end = sidePadding),
        ) {
            content()
            if (bottomSpacer) {
                item(key = "eta_bottom_spacer") {
                    Spacer(
                        modifier = Modifier
                            .height(24.dp)
                            .navigationBarsPadding(),
                    )
                }
            }
        }
    }
}

/**
 * 独立搜索页骨架：从宿主页右上角的单搜索图标跳转进来，整页就是搜索。
 *
 * 顶栏为官方 [TopAppBar]：左侧返回钮 + 标题槽透明底搜索框（无边框融入标题栏）+
 * 右侧清除按钮（有输入时才出现）。进入页面自动聚焦并弹出输入法；IME 搜索动作会记录
 * 查询历史并收起键盘，过滤逻辑由调用方的 [content] 按搜索词实时完成（输入即出结果）。
 *
 * 未输入时：有历史则显示「搜索记录」列表（点一条回填搜索框，可一键清除）；
 * 无历史则显示官方 Empty state（空状态）——居中的非交互占位图标 + 提示标语，
 * 图标下方垫一块 M3E 装饰形状（纯装饰，轮廓每次进页面随机换一枚，见 [EtaDecorativeShape]），
 * **不复制宿主页内容**（避免与主页重复）；有搜索结果由各页自己的空态文案兜底。
 *
 * 记录口径（全应用统一）：**IME 提交**由本页按 [historyKey] 自动记一条；**点开某条结果**
 * 由各页在跳转前调 `EtaSearchHistory.add(context, historyKey, query)` 补记一条——两者合起来
 * 才能覆盖「回车上屏页」和「实时过滤页」两类：后者根本不会按搜索键，只在点结果时才证明这个词有用。
 * 记录列表本身统一用 [EtaSearchHistoryItems] 渲染，版式与设置主页分组列表一致。
 *
 * 与宿主页的 [EtaScaffoldPage] 保持同一套背景色与底栏安全区处理；[query] 用
 * `rememberSaveable` 保存，旋转屏幕不丢关键词。
 *
 * @param searchPlaceholder 搜索框占位文案（通常即「搜索XX」），同时用作空态标语。
 * @param onBack 顶栏返回钮回调。
 * @param historyKey 查询历史的存储分域键（如 "assistants"）；null 禁用历史。
 * @param content 列表内容，参数为当前搜索词；仅在有输入时渲染。
 */
@Composable
fun EtaSearchPage(
    searchPlaceholder: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    historyKey: String? = null,
    listState: LazyListState = rememberLazyListState(),
    content: LazyListScope.(query: String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // 空态装饰形状：每次进页面随机换一枚官方 M3E 轮廓。用 remember 存住，
    // 于是同一次停留内（输入再清空、列表重组）都保持同一枚，不会跳。
    val emptyStateShape = remember { etaRandomDecorativeShape() }
    val context = LocalContext.current
    // 历史状态只在页内两处变更（IME 提交 / 清除），结果点按方直接写盘，
    // 返回本页时 remember 重建自然重读。
    var history by remember(historyKey) {
        mutableStateOf(historyKey?.let { EtaSearchHistory.load(context, it) }.orEmpty())
    }
    // 各搜索页在「点结果跳转」时会把当时的查询词写盘（见类注释）。页面可能一直留在组合里
    // （搜索页是入栈的独立路由，跳转到详情后本页仍活着），所以每次搜索框被清空——也就是
    // 即将展示记录列表的那一刻——重读一次，刚记下的词才会立刻出现在列表里。
    LaunchedEffect(historyKey, query.isBlank()) {
        if (query.isBlank()) {
            history = historyKey?.let { EtaSearchHistory.load(context, it) }.orEmpty()
        }
    }

    /** 记录一次搜索（IME 提交时调用），空查询忽略。 */
    fun recordSearch(text: String) {
        val key = historyKey ?: return
        EtaSearchHistory.add(context, key, text)
        history = EtaSearchHistory.load(context, key)
    }

    EtaSearchScaffold(
        query = query,
        onQueryChange = { query = it },
        searchPlaceholder = searchPlaceholder,
        onBack = onBack,
        modifier = modifier,
        onSubmit = { text -> recordSearch(text) },
        listState = listState,
        // 空态占满整屏时不补底部间隙，避免空态被顶出多余滚动。
        bottomSpacer = query.isNotBlank(),
    ) {
        if (query.isBlank()) {
            // 未输入：有历史先列「搜索记录」（点一条回填搜索框，可一键清除）；
            // 无历史再退回官方 Empty state（空状态）占位。都不铺宿主页内容。
            if (history.isNotEmpty()) {
                EtaSearchHistoryItems(
                    history = history,
                    onPick = { record -> query = record },
                    onClear = {
                        historyKey?.let { EtaSearchHistory.clear(context, it) }
                        history = emptyList()
                    },
                )
            } else {
                item(key = "search_empty_state") {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(horizontal = 32.dp),
                        ) {
                            // 图标坐在一块 M3E 装饰形状正中：形状垫在图标下方四周均匀露出，
                            // 每次进页面换一枚轮廓（见 [etaRandomDecorativeShape]），
                            // 给「没有结果」的空态一点形状趣味（纯装饰，不承载信息）。
                            Box(
                                modifier = Modifier.size(EtaDimens.EmptyStateShapeSize),
                                contentAlignment = Alignment.Center,
                            ) {
                                EtaDecorativeShape(
                                    modifier = Modifier.size(EtaDimens.EmptyStateShapeSize),
                                    shape = emptyStateShape,
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(EtaDimens.EmptyStateGraphicIconSize),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = searchPlaceholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        } else {
            content(query)
        }
    }
}

/**
 * 搜索记录列表（[LazyListScope] 扩展）：组标题 +「清除」 + 若干记录行，点一条回填搜索框。
 *
 * 版式与设置主页的分组列表完全一致：组标题下 8dp、行间 2dp 露出背景缝、组末 13dp、
 * 左右页边距 16dp。抽成扩展是为了让自绘顶栏的搜索页（[EtaSearchScaffold] 那一支，如媒体搜索）
 * 与 [EtaSearchPage] 共用同一份「搜索记录」外观——应用内所有独立搜索页的记录列表必须是同一个样子。
 *
 * @param history 记录列表（最近在前，写盘由 [EtaSearchHistory] 负责）。
 * @param onPick 点按某条记录：调用方通常把它回填进搜索框。
 * @param onClear 点按「清除」：调用方负责清盘并清空本地列表。
 * @param keyPrefix 列表键前缀，同一页里若还要挂别的列表，用它避免键冲突。
 */
fun LazyListScope.EtaSearchHistoryItems(
    history: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
    keyPrefix: String = "search_history",
) {
    if (history.isEmpty()) return
    item(key = "$keyPrefix-header") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedTitleSpacing,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.action_search_history),
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) {
                Text(text = stringResource(R.string.action_clear))
            }
        }
    }
    history.forEachIndexed { index, record ->
        item(key = "$keyPrefix-$index") {
            EtaSegmentedItem(index = index, count = history.size) {
                EtaClickableRow(
                    title = record,
                    leading = {
                        // 官方默认几何：裸 Icon（24dp + 官方 ItemBetweenSpace 12dp）；
                        // 着色与设置行统一 onSurfaceVariant。
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    showChevron = false,
                    onClick = { onPick(record) },
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = if (index == history.lastIndex) {
                            EtaSegmentedGroupSpacing
                        } else {
                            EtaSegmentedGap
                        },
                    ),
                )
            }
        }
    }
}
