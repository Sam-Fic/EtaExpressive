package io.github.mangi.eta.ui.screens.search

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.media.index.EmbeddingModelStore
import io.github.mangi.eta.agent.media.index.EtaEmbeddingEngine
import io.github.mangi.eta.agent.media.index.MediaExtraction
import io.github.mangi.eta.agent.media.index.MediaFileOps
import io.github.mangi.eta.agent.media.index.MediaFileOps.MediaDir
import io.github.mangi.eta.agent.media.index.MediaFileOps.MediaTarget
import io.github.mangi.eta.agent.media.index.MediaSearchService
import io.github.mangi.eta.agent.media.index.MediaThumbnailStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MediaKind
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDecorativeShape
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaFloatingToolbarConcentricPadding
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize
import io.github.mangi.eta.ui.adaptive.EtaSearchHistory
import io.github.mangi.eta.ui.adaptive.EtaSearchHistoryItems
import io.github.mangi.eta.ui.adaptive.EtaSearchGridScaffold
import io.github.mangi.eta.ui.adaptive.EtaSearchScaffold
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
import io.github.mangi.eta.ui.adaptive.EtaSlider
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.etaRandomDecorativeShape
import io.github.mangi.eta.ui.adaptive.rememberEtaSheetState
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.components.ContainedMorphLoadingIndicator
import io.github.mangi.eta.ui.components.horizontalScrollEdgeFade
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.navigation.AppRoute
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 查询历史分域键：媒体搜索历史独立存储（与设置 / 助手 / 供应商 / 消息搜索互不干扰）。 */
private const val HISTORY_KEY = "media"

/** 类型过滤 chips：全部 / 图片 / 视频 / 音频 / 文件。 */
private val FILTER_LABELS = intArrayOf(
    R.string.media_filter_all,
    R.string.media_filter_image,
    R.string.media_filter_video,
    R.string.media_filter_audio,
    R.string.media_filter_document,
)

/** 与 [FILTER_LABELS] 一一对应的 [MediaKind] 过滤集合；空集合表示不过滤。 */
private fun kindsForFilter(index: Int): Set<MediaKind> = when (index) {
    1 -> setOf(MediaKind.IMAGE)
    2 -> setOf(MediaKind.VIDEO)
    3 -> setOf(MediaKind.AUDIO)
    4 -> setOf(MediaKind.DOCUMENT)
    else -> emptySet()
}

/**
 * 本地多模态语义检索页：自然语言搜本机图片 / 视频 / 音频 / 文件。
 *
 * 版式沿用应用内其它搜索页（[EtaSearchScaffold]）：顶栏是「返回钮 + 透明底搜索框 +
 * 清除按钮 + 搜索按钮」，没有大标题页，搜索词就在顶栏里改；类型标签条常驻在顶栏下方，
 * 搜索前后都能切换范围——切标签只对已持有的结果做本地筛选、不重跑检索（流式中切标签
 * 同样只过滤在途分片、不打断检索，命中行随分片到达逐步进入该分类视图），点搜索按钮
 * 才按当前标签定界真正跑一遍；「找到 X 项」行右端是纯图标按钮——多选入口（多选态原位
 * 变为「取消」，退出并清空勾选）与匹配度阈值（始终居最右；多选态已选计数以居中
 * 点号并入行首文案）——阈值是检索时参数：拖动结束落盘并同词静默重检，结果集就是
 * 「所有 ≥ 阈值的命中」，无数量上限；批量操作集中在底部官方浮动图标工具栏，其中的
 * 「添加到会话」把选中项经 [onAddToChat] 交给当前会话输入区（与系统分享同一入口，
 * 按类型落成图片/视频/文件附件），回聊天页即见待发附件。
 *
 * 结果视图可选行列表或宫格（相册式纯缩略图墙，官方 LazyVerticalGrid 固定 3 列）：选择
 * 收在「找到 X 项」行右端设置按钮打开的搜索设置面板（匹配度阈值 + 视图模式），切换
 * 即时落盘（[EmbeddingModelStore]）。宫格是纯展示层形态——只在已有命中时挂载宫格骨架
 * （[EtaSearchGridScaffold]；全屏态条目没有 fillParentMaxSize 等价物，一律留在列表骨架），
 * 不影响检索、筛选与多选。
 *
 * 检索统一走 [MediaSearchService]（与 AI 工具 `search_media` 同一入口），全程离线；
 * 模型未下载或扫描源未确认时显示引导卡片跳设置页。结果流式渲染：命中行按匹配度分片
 * 构建、分片到达即渲染（首屏不等全量 resolvePath 构建，低阈值万级命中收益最大）。
 * 视频时刻精排异步补齐：搜索主流程以 refineMoments=false 先出结果，视频命中的命中
 * 时刻再经 [MediaSearchService.refineTopMoments] 逐条原位回填（时刻缓存跨阈值重检
 * 复用，不重复抽帧），进页先预热嵌入引擎免冷启动。
 *
 * 搜索记录与其它独立搜索页同源（[EtaSearchHistory]，分域键 `media`）：每次真正跑检索落一条，
 * 搜索框为空时先列记录（点一条直接检索），无记录才显示官方 Empty state。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MediaSearchScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    /** 多选工具栏「添加到会话」：把选中项的 uri 清单交给当前会话输入区后由壳层回聊天页。 */
    onAddToChat: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    // 搜索记录：与其它独立搜索页同一套存储（[EtaSearchHistory]），进页面读一次，
    // 每次真正跑检索时写一次。
    var history by remember { mutableStateOf(EtaSearchHistory.load(context, HISTORY_KEY)) }
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<MediaSearchService.Status?>(null) }
    var searching by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MediaSearchService.SearchResult?>(null) }
    // 流式分片：检索中已到达、已构建的命中行（result 在检索完成前为空）。
    // 首片到达即开始渲染，后续分片继续流入；检索完成由 result 整体接管并清空。
    // 用 SnapshotStateList 增量 addAll：分片可达万级命中，旧写法 `partialHits + chunk`
    // 每片都整表拷贝，主线程 O(n²) 分配 + 全列表 diff。
    val partialHits = remember { mutableStateListOf<MediaSearchService.Hit>() }
    var errorText by remember { mutableStateOf<String?>(null) }
    // 匹配度阈值：进页面恢复上次调节值（EmbeddingModelStore prefs，默认 [EmbeddingModelStore.DEFAULT_SEARCH_MIN_SCORE]），
    // 拖动结束落盘并同词静默重检——阈值是检索时参数，结果集就是「所有 ≥ 阈值的命中」。
    // 初值直接取存储默认常量：滑杆默认停在对应档位（0.05 步进，0.75 恰为 15/20 档），
    // 异步恢复前不会闪到旧位置。
    var minScore by rememberSaveable { mutableStateOf(EmbeddingModelStore.DEFAULT_SEARCH_MIN_SCORE) }
    // 多选批量管理：勾选集合以 sourceKey 为键；操作完成或新一轮检索后退出并清空。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var confirmDeleteFiles by remember { mutableStateOf(false) }
    // 搜索设置面板（匹配度阈值 / 视图模式）：从「找到 X 项」行的设置按钮打开（官方 ModalBottomSheet）。
    var showSettingsSheet by remember { mutableStateOf(false) }
    // 结果视图模式：宫格（相册式纯缩略图墙）或行列表（默认）。同步读 prefs——store 是唯一
    // 事实源、切换即时落盘，不用 rememberSaveable 避免两份状态分叉，进程重建自然回读。
    var viewGrid by remember { mutableStateOf(EmbeddingModelStore.searchViewGrid(context)) }
    // 检索协程 Job：显式检索与滑杆重检共用（同一时刻至多一个在跑，新检索先取消旧检索，
    // 换词中途重搜即此路径）。
    var searchJob by remember { mutableStateOf<Job?>(null) }
    // 检索代数：每次 runSearch 递增。被取消的旧检索据此判断 searching 复位是否归属自己，
    // 避免旧检索的收尾覆盖新检索的检索态。
    var searchGeneration by remember { mutableIntStateOf(0) }
    // 视频时刻异步精排 Job：结果先渲染，命中时刻逐条回填；新检索取消在途旧精排。
    var momentJob by remember { mutableStateOf<Job?>(null) }
    var batchBusy by remember { mutableStateOf(false) }
    // 批量移动/复制：先由目录选择器拿目标文件夹，再二次确认；两者齐备才弹确认框。
    var pendingOperation by remember { mutableStateOf<MediaFileOps.Op?>(null) }
    var pendingTargetDir by remember { mutableStateOf<MediaDir?>(null) }

    // 目标文件夹选择：SAF tree uri，落盘持久化授权后进二次确认。
    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) {
            pendingOperation = null
            pendingTargetDir = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            // 读写都尝试，读成功即可复制；写权限决定能否在目标树内新建 / 移动文件。
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }.recoverCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            pendingTargetDir = MediaDir(treeUri = uri.toString())
        }
    }

    LaunchedEffect(Unit) {
        status = runCatching { MediaSearchService.status(context) }.getOrNull()
        minScore = EmbeddingModelStore.searchMinScore(context)
        // 预热嵌入引擎：模型就绪时提前消化引擎初始化的秒级开销，首次搜索不吃冷启动。
        if (status?.ready == true) {
            runCatching { EtaEmbeddingEngine.warmUp(context) }
        }
    }

    /** 记录一次检索词：空词忽略，去重置顶（与其它搜索页同一份实现）。 */
    fun recordSearch(text: String) {
        EtaSearchHistory.add(context, HISTORY_KEY, text)
        history = EtaSearchHistory.load(context, HISTORY_KEY)
    }

    /**
     * 执行一次语义检索；查询为空时不动作（引导卡片负责指向设置页）。
     * [text] 显式传入是为了「点搜索记录」这种先改词再检索的场景（state 还没重组，读 [query] 拿到的是旧值）。
     *
     * 新检索先取消在途检索与时刻精排（同一时刻至多一个检索在跑）：换词中途重搜即此路径，
     * 在途流式分片整体作废、取消不计为检索失败（[CancellationException] 原样上抛，
     * errorText 与旧结果都不动），last-wins。被取消方经 [searchGeneration] 判断
     * searching 复位归属，不会覆盖新检索的检索态。
     *
     * [fromSlider] 为 true 是阈值滑杆松手触发的重检：空闲时走「静默原位更新」——旧结果
     * 原位保留、不切 loading、不落搜索记录、不做流式分片，查询向量缓存命中后通常 <150ms，
     * 瞬时失败保持旧结果不动；检索进行中（流式未完）则与显式检索相同，取消在途后按新
     * 阈值重新流式（阈值是检索时参数，静默原位更新只对完整旧结果成立）。
     *
     * 显式检索走流式：先清旧结果出 loading，命中行按匹配度分片到达即渲染（[partialHits]），
     * 检索完成由完整结果接管——低阈值万级命中时首屏不再等全量 resolvePath 构建。
     */
    fun runSearch(text: String = query.trim(), fromSlider: Boolean = false) {
        if (text.isEmpty()) return
        if (!fromSlider) {
            // 真正跑过检索的词就是有效检索：落一条搜索记录。
            recordSearch(text)
            // 检索是显式动作：触发后立刻收起键盘，结果区不再被输入法挤成半屏。
            focusManager.clearFocus()
        }
        // 滑杆重检的静默原位更新只在空闲时成立；流式进行中滑杆松手与显式检索同路径重启。
        val silentRecheck = fromSlider && !searching
        // 换结果集先退出多选：旧结果上的勾选不延续到新结果。
        selectionMode = false
        selectedKeys = emptySet()
        // 新检索开始前取消在途检索与时刻精排，避免旧结果晚到覆盖新结果。
        searchJob?.cancel()
        momentJob?.cancel()
        val generation = ++searchGeneration
        suspend fun executeSearch() {
            errorText = null
            val outcome: Result<MediaSearchService.SearchResult> = try {
                Result.success(
                    MediaSearchService.search(
                        context = context,
                        query = text,
                        kinds = kindsForFilter(filterIndex),
                        limit = MediaSearchService.UNLIMITED_RESULTS,
                        minScore = minScore,
                        // 时刻精排不阻塞结果：先渲染列表，视频命中时刻随后异步逐条补齐。
                        refineMoments = false,
                        // 命中行分片流式：逐片到达即渲染（静默重检不做，旧结果原位保留）。
                        onPartialHits = if (silentRecheck) {
                            null
                        } else {
                            { chunk ->
                                withContext(Dispatchers.Main) { partialHits.addAll(chunk) }
                            }
                        },
                    ),
                )
            } catch (cancellation: CancellationException) {
                // 换词重启/离屏导致的取消原样上抛：不计为失败，errorText 与旧结果都不动。
                throw cancellation
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            if (silentRecheck) {
                outcome.getOrNull()?.let { newResult ->
                    result = newResult
                    errorText = null
                }
            } else {
                result = outcome.getOrNull()
                outcome.exceptionOrNull()?.let { errorText = it.message ?: "search failed" }
            }
            // 检索完成由完整结果接管，分片清场（失败同样清掉，错误分支接管展示）。
            partialHits.clear()
            // 时刻精排异步补齐：逐条完成后按 sourceKey 原位更新行；时刻缓存命中时
            // search 已直接带回时刻，这里对已带时刻的命中跳过（无可补即空转返回）。
            // 失败保底也启动：静默重检失败时旧结果还在，补齐其中尚缺的时刻。
            result?.let { current ->
                momentJob = scope.launch {
                    MediaSearchService.refineTopMoments(
                        context = context,
                        query = text,
                        hits = current.hits,
                    ) { refined ->
                        val latest = result ?: return@refineTopMoments
                        result = latest.copy(
                            hits = latest.hits.map { hit ->
                                if (hit.sourceKey == refined.sourceKey) refined else hit
                            },
                        )
                    }
                }
            }
            status = runCatching { MediaSearchService.status(context) }.getOrNull()
        }
        if (silentRecheck) {
            searchJob = scope.launch { executeSearch() }
        } else {
            // 清旧结果与检索态同帧生效，不留空闲态闪帧；首片命中到达后边流边渲染。
            result = null
            partialHits.clear()
            searching = true
            searchJob = scope.launch {
                try {
                    executeSearch()
                } finally {
                    // 期间又起了新检索（代数已变）时不动 searching，由新检索收尾。
                    if (generation == searchGeneration) searching = false
                }
            }
        }
    }

    /** 勾选切换：选中/取消选中一条结果。 */
    fun toggleHit(sourceKey: String) {
        selectedKeys = if (sourceKey in selectedKeys) {
            selectedKeys - sourceKey
        } else {
            selectedKeys + sourceKey
        }
    }

    val ready = status?.ready == true
    // 结果行数据源：检索完成用最终结果；流式检索中 result 尚空，用已到达的分片。
    val rawHits = result?.hits ?: partialHits
    // 类型标签是展示层过滤：切分类只筛已持有的命中，不重跑检索（空集合 = 「全部」不过滤）。
    // 流式检索中同样适用——rawHits 是已到达的分片，切标签即刻按分类过滤在途命中，
    // 该类目的命中行随分片到达逐步进入视图，不打断检索；要按新类目重新定界再点搜索按钮。
    // 匹配度阈值不在此过滤——它已下推到检索时（search 的 minScore），结果集本身就是
    // 「所有 ≥ 阈值的命中」，数量不设上限。
    val activeKinds = kindsForFilter(filterIndex)
    // 过滤结果按「结果集引用 + 类目 + 分片到达数」缓存：万级命中的 filter 不再跟随
    // 勾选/滚动等无关重组反复全量执行（分片到达数变化 = 新命中流入，才重新筛）。
    val hits = remember(result, activeKinds, partialHits.size) {
        if (activeKinds.isEmpty()) rawHits else rawHits.filter { it.kind in activeKinds }
    }
    // 阈值面板文案用：检索时 kinds 范围内低于阈值、未进入结果的候选数（服务端随结果返回）。
    val hiddenCount = result?.belowThresholdCount ?: 0
    // 勾选按当前显示的命中取交集：类型标签切走后被隐藏的条目不再计入批量操作。
    val selectedHits = hits.filter { it.sourceKey in selectedKeys }
    // 宫格骨架只承载「已有命中」的正文（计数行 + 命中格 + 流式尾部 + 分类筛空态提示）；
    // 「检索中 / 空闲 / 错误 / 无结果」等全屏态一律留在列表骨架——官方 LazyGridItemScope
    // 没有 fillParentMaxSize（1.13.0-alpha01 源码核验），全屏态条目进不了宫格。
    // 以 rawHits（而非 hits）判定：宫格内切类型标签只换全跨提示行，骨架不重挂。
    val useGrid = viewGrid && rawHits.isNotEmpty()

    /** 移出索引：删 media_index 行与缩略图缓存，源文件保留。 */
    fun runRemoveFromIndex() {
        val targets = selectedHits
        if (targets.isEmpty() || batchBusy) return
        scope.launch {
            batchBusy = true
            withContext(Dispatchers.IO) {
                targets.forEach { hit -> deleteThumbnail(context, hit) }
                EtaDatabase.get(context).mediaIndexDao().deleteBySourceKeys(targets.map { it.sourceKey })
            }
            batchBusy = false
            selectionMode = false
            selectedKeys = emptySet()
            Toast.makeText(
                context,
                context.getString(R.string.media_remove_index_done, targets.size),
                Toast.LENGTH_LONG,
            ).show()
            runSearch()
        }
    }

    /** 删除文件：逐条删源文件（失败不中断其他项），成功项同步移出索引，最后汇总提示。 */
    fun runDeleteFiles() {
        val targets = selectedHits
        if (targets.isEmpty() || batchBusy) return
        scope.launch {
            batchBusy = true
            val failed = withContext(Dispatchers.IO) {
                val failedNames = mutableListOf<String>()
                targets.forEach { hit ->
                    if (deleteSourceFile(context, hit)) {
                        // 源文件已删：同步清缩略图缓存与索引行。
                        deleteThumbnail(context, hit)
                        EtaDatabase.get(context).mediaIndexDao().deleteBySourceKeys(listOf(hit.sourceKey))
                    } else {
                        // 无权限 / 被占用等失败项只记录，不影响其他条目。
                        failedNames += hit.displayName
                    }
                }
                failedNames
            }
            batchBusy = false
            selectionMode = false
            selectedKeys = emptySet()
            Toast.makeText(
                context,
                if (failed.isEmpty()) {
                    context.getString(R.string.media_delete_files_done, targets.size)
                } else {
                    context.getString(
                        R.string.media_delete_files_partial,
                        targets.size - failed.size,
                        failed.size,
                        failed.joinToString(", "),
                    )
                },
                Toast.LENGTH_LONG,
            ).show()
            runSearch()
        }
    }

    /**
     * 批量移动到 / 复制到目标文件夹：逐条执行（单项失败不中断），完成后退出多选并刷新结果
     * （move 的条目已不在原处，刷新后自然消失；copy 的副本由下一轮增量索引发现）。
     */
    fun runBatchToTarget(operation: MediaFileOps.Op, targetDir: MediaDir) {
        val targets = selectedHits.map { MediaTarget.from(it) }
        if (targets.isEmpty() || batchBusy) return
        scope.launch {
            batchBusy = true
            val outcome = withContext(Dispatchers.IO) {
                MediaFileOps.runBatch(context, targets, targetDir, operation)
            }
            batchBusy = false
            selectionMode = false
            selectedKeys = emptySet()
            val failed = outcome.failed
            val message = if (failed.isEmpty()) {
                context.getString(
                    if (operation == MediaFileOps.Op.MOVE) {
                        R.string.media_move_done
                    } else {
                        R.string.media_copy_done
                    },
                    outcome.succeeded.size,
                )
            } else {
                // 失败只报条数与首个原因，足够定位方向；完整清单由 AI 工具的结构化输出承担。
                context.getString(
                    R.string.media_batch_partial,
                    outcome.succeeded.size,
                    failed.size,
                    failed.first().message,
                )
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            runSearch()
        }
    }

    // 共享顶栏参数：列表与宫格两套骨架传同一份，观感与行为完全一致。
    val searchActions: @Composable RowScope.() -> Unit = {
        MediaSearchAction(
            enabled = query.trim().isNotEmpty() && ready,
            onSearch = { runSearch() },
        )
    }
    val filterBar: @Composable () -> Unit = {
        // 类型标签常驻顶栏下方：搜索前后都能切换范围，不用滚回页面顶部。
        // 切标签只对当前结果集做本地筛选，不重跑检索；想在某个分类下
        // 重新定界检索时，再点顶栏的搜索按钮即可（runSearch 按当前标签取 kinds）。
        MediaFilterBar(
            selectedIndex = filterIndex,
            onSelect = { index ->
                TouchHaptics.click(view)
                filterIndex = index
            },
        )
    }

    /**
     * 列表正文：类型口径提示 + 全屏态（未就绪 / 检索中 / 错误 / 空闲 / 无结果 / 分类筛空）
     * + 分组结果行。全屏态条目只能在列表骨架呈现（fillParentMaxSize 是 LazyItemScope 专属，
     * 宫格条目没有等价物），宫格正文见 [mediaSearchGridBody]。
     *
     * 仅装配 item 的 [LazyListScope] 扩展（与 EtaSearchHistoryItems 同一套写法），本身不是
     * @Composable：闭包捕获页面状态，重组时读取最新值。
     */
    fun LazyListScope.mediaSearchListBody() {
        // 选中「音频 / 文件」时的索引口径提示：常驻在结果列表上方。
        if (filterIndex == 3 || filterIndex == 4) {
            item(key = "filter-note") {
                MediaFilterNote(filterIndex = filterIndex)
            }
        }
        when {
            // 未就绪态：模型未下载或扫描源未确认，引导到设置页。
            !ready -> {
                item(key = "not-ready") {
                    EtaSegmentedColumn(
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            bottom = EtaSegmentedGroupSpacing,
                        ),
                    ) {
                        item {
                            EtaClickableRow(
                                title = stringResource(R.string.media_search_not_ready_title),
                                summary = stringResource(R.string.media_search_not_ready_summary),
                                onClick = { onNavigate(AppRoute.MediaIndexSettings) },
                            )
                        }
                    }
                }
            }
            searching && hits.isEmpty() -> {
                // 检索中且当前分类视图还没有可展示的命中：块级 loading（M3E 官方 morph
                // 指示器），整体居中展示。覆盖两种情形：首片未到；流式中切换了类目标签、
                // 该类目的命中还在后续分片里——此时不宣判「分类筛空态」（命中可能正在
                // 路上），检索结束后该类目确实无命中时才转下方的分类筛空态。
                // 首片命中到达且分类视图非空后转 else 分支边流边渲染，不再整屏 loading。
                item(key = "searching") {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(horizontal = 32.dp),
                        ) {
                            ContainedMorphLoadingIndicator()
                            EtaText(
                                text = stringResource(R.string.media_search_searching),
                                style = EtaTextStyle.Secondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            errorText != null -> {
                item(key = "search-error") {
                    EtaText(
                        text = stringResource(R.string.media_search_error, errorText ?: ""),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            bottom = EtaSegmentedGroupSpacing,
                        ),
                    )
                }
            }
            result == null && rawHits.isEmpty() -> {
                // 空闲态与其它搜索页同口径：搜索框为空且有搜索记录时先列记录（点一条直接检索），
                // 其余情况退回官方 Empty state（装饰形状 + 放大镜 + 一句说明）。
                if (query.isBlank() && history.isNotEmpty()) {
                    EtaSearchHistoryItems(
                        history = history,
                        onPick = { record ->
                            query = record
                            runSearch(record)
                        },
                        onClear = {
                            EtaSearchHistory.clear(context, HISTORY_KEY)
                            history = emptyList()
                        },
                    )
                } else {
                    item(key = "search-idle") {
                        MediaSearchEmptyState(
                            text = stringResource(R.string.media_search_hint),
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                }
            }
            rawHits.isEmpty() -> {
                // 无结果态：有候选但全部低于阈值 → 提示调低匹配度阈值（点击直达设置面板，
                // 默认 0.75 后这是常见状态）；索引为空时直接给「去建立索引」引导。
                if ((result?.belowThresholdCount ?: 0) > 0) {
                    item(key = "threshold-empty") {
                        EtaSegmentedColumn(
                            modifier = Modifier.padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                bottom = EtaSegmentedGroupSpacing,
                            ),
                        ) {
                            item {
                                EtaClickableRow(
                                    title = stringResource(R.string.media_threshold_empty_hint),
                                    onClick = { showSettingsSheet = true },
                                    showChevron = false,
                                )
                            }
                        }
                    }
                } else {
                    item(key = "no-results") {
                        EtaSegmentedColumn(
                            modifier = Modifier.padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                bottom = EtaSegmentedGroupSpacing,
                            ),
                        ) {
                            item {
                                EtaClickableRow(
                                    title = stringResource(R.string.media_search_no_results),
                                    summary = if (result?.totalCandidates == 0) {
                                        stringResource(R.string.media_search_empty_index)
                                    } else {
                                        null
                                    },
                                    onClick = null,
                                    showChevron = false,
                                )
                            }
                            item(visible = result?.totalCandidates == 0) {
                                EtaClickableRow(
                                    title = stringResource(R.string.media_action_build_index),
                                    onClick = { onNavigate(AppRoute.MediaIndexSettings) },
                                )
                            }
                        }
                    }
                }
            }
            hits.isEmpty() -> {
                // 分类筛空态：检索本身有结果，只是当前类型标签筛不出任何条目。
                item(key = "filter-empty") {
                    MediaFilterEmptyHint()
                }
            }
            else -> {
                // 结果计数行 + 分组结果行 + 流式尾部：结果段在列表形态下的排布。
                item(key = "result-count") {
                    MediaResultCountRow(
                        resultCount = hits.size,
                        selectionMode = selectionMode,
                        selectedCount = selectedHits.size,
                        onToggleSelectionMode = {
                            selectionMode = !selectionMode
                            selectedKeys = emptySet()
                        },
                        onOpenSettings = { showSettingsSheet = true },
                    )
                }
                // 结果行完全复用设置主页的分组列表：行距 2dp、组末 13dp、左右页边距 16dp，
                // 分段形状由 [EtaSegmentedItem] 按 index/count 注入（首末项外圆角、中间内圆角）。
                hits.forEachIndexed { index, hit ->
                    item(key = hit.sourceKey) {
                        EtaSegmentedItem(index = index, count = hits.size) {
                            MediaHitRow(
                                hit = hit,
                                summary = hitSummary(context, hit),
                                selectionMode = selectionMode,
                                checked = hit.sourceKey in selectedKeys,
                                onClick = {
                                    // 多选态点击改勾选；单条「打开」仅在非多选态可用。
                                    if (selectionMode) {
                                        toggleHit(hit.sourceKey)
                                    } else {
                                        openHit(context, hit)
                                    }
                                },
                                onLongClick = {
                                    // 长按进入多选并选中当前行（长按触感由行内建，不重复触发）。
                                    selectionMode = true
                                    selectedKeys = selectedKeys + hit.sourceKey
                                },
                                modifier = Modifier.padding(
                                    start = EtaGroupHorizontalPadding,
                                    end = EtaGroupHorizontalPadding,
                                    bottom = if (index == hits.lastIndex) {
                                        EtaSegmentedGroupSpacing
                                    } else {
                                        EtaSegmentedGap
                                    },
                                ),
                            )
                        }
                    }
                }
                if (searching) {
                    item(key = "streaming-more") {
                        MediaStreamingTail()
                    }
                }
            }
        }
    }

    /**
     * 宫格正文：类型口径提示 + 结果墙（计数行全跨 + 命中逐格 + 流式尾部全跨）+ 分类筛空态。
     * 全屏态不在这里（宫格条目没有 fillParentMaxSize 等价物），那时整体退回列表骨架；
     * 仅装配 item 的 [LazyGridScope] 扩展，与 [mediaSearchListBody] 同一套写法。
     */
    fun LazyGridScope.mediaSearchGridBody() {
        if (filterIndex == 3 || filterIndex == 4) {
            item(key = "filter-note", span = { GridItemSpan(maxLineSpan) }) {
                MediaFilterNote(filterIndex = filterIndex)
            }
        }
        if (hits.isEmpty()) {
            // 分类筛空态：检索本身有结果，只是当前类型标签筛不出任何条目。
            item(key = "filter-empty", span = { GridItemSpan(maxLineSpan) }) {
                MediaFilterEmptyHint()
            }
        } else {
            item(key = "result-count", span = { GridItemSpan(maxLineSpan) }) {
                MediaResultCountRow(
                    resultCount = hits.size,
                    selectionMode = selectionMode,
                    selectedCount = selectedHits.size,
                    onToggleSelectionMode = {
                        selectionMode = !selectionMode
                        selectedKeys = emptySet()
                    },
                    onOpenSettings = { showSettingsSheet = true },
                )
            }
            hits.forEach { hit ->
                item(key = hit.sourceKey) {
                    HitGridCell(
                        hit = hit,
                        selectionMode = selectionMode,
                        checked = hit.sourceKey in selectedKeys,
                        onClick = {
                            // 多选态点击改勾选；单条「打开」仅在非多选态可用。
                            if (selectionMode) {
                                toggleHit(hit.sourceKey)
                            } else {
                                openHit(context, hit)
                            }
                        },
                        onLongClick = {
                            // 长按进多选并选中当前格（长按触感由 combinedClickable 内建）。
                            selectionMode = true
                            selectedKeys = selectedKeys + hit.sourceKey
                        },
                    )
                }
            }
            if (searching) {
                item(key = "streaming-more", span = { GridItemSpan(maxLineSpan) }) {
                    MediaStreamingTail()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (useGrid) {
            // 宫格墙：官方 LazyVerticalGrid（[EtaSearchGridScaffold]），只在已有命中时挂载。
            EtaSearchGridScaffold(
                query = query,
                onQueryChange = { query = it },
                searchPlaceholder = stringResource(R.string.media_search_placeholder),
                onBack = onBack,
                // IME 的搜索键与顶栏的「搜索」按钮走同一条路径。
                onSubmit = { runSearch() },
                // 顶栏重挂（结果出现 / 切视图）不再弹键盘：自动聚焦只保留给「进页初始态」。
                autoFocus = query.isBlank() && rawHits.isEmpty(),
                actions = searchActions,
                topContent = filterBar,
                columns = GRID_COLUMNS,
                cellSpacing = GRID_GAP_DP.dp,
            ) {
                mediaSearchGridBody()
            }
        } else {
            EtaSearchScaffold(
                query = query,
                onQueryChange = { query = it },
                searchPlaceholder = stringResource(R.string.media_search_placeholder),
                onBack = onBack,
                // IME 的搜索键与顶栏的「搜索」按钮走同一条路径。
                onSubmit = { runSearch() },
                // 顶栏重挂（切视图回列表）不再弹键盘：自动聚焦只保留给「进页初始态」。
                autoFocus = query.isBlank() && rawHits.isEmpty(),
                actions = searchActions,
                topContent = filterBar,
            ) {
                mediaSearchListBody()
            }
        }

        // 多选批量操作栏：底部官方浮动图标工具栏（纯图标），常驻承载全选与批量操作。
        // 进退场与模型列表页同款：AnimatedVisibility 承载上浮/下沉——位移走 M3E Spatial
        // （轻微过冲），透明度走 Effects（无过冲）；不用 if 直切硬蹦。
        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            ) { it } + fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = slideOutVertically(
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            ) { it } + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            MediaSelectionBar(
                selectedCount = selectedHits.size,
                allSelected = hits.isNotEmpty() && selectedHits.size == hits.size,
                busy = batchBusy,
                onSelectAll = {
                    TouchHaptics.click(view)
                    selectedKeys = if (hits.isNotEmpty() && selectedHits.size == hits.size) {
                        emptySet()
                    } else {
                        hits.map { it.sourceKey }.toSet()
                    }
                },
                onAddToChat = {
                    TouchHaptics.click(view)
                    // 选中项交给当前会话输入区：content uri 优先（与系统分享同一入口，
                    // 按类型落成图片/视频/文件附件），无 uri 的本地文件回落绝对路径；
                    // 全部不可解析时不动作也不跳转，留在原位可重选。
                    val uris = selectedHits.mapNotNull { it.contentUri ?: it.path }
                    if (uris.isNotEmpty()) onAddToChat(uris)
                },
                onMoveTo = {
                    TouchHaptics.click(view)
                    pendingTargetDir = null
                    pendingOperation = MediaFileOps.Op.MOVE
                    dirPicker.launch(null)
                },
                onCopyTo = {
                    TouchHaptics.click(view)
                    pendingTargetDir = null
                    pendingOperation = MediaFileOps.Op.COPY
                    dirPicker.launch(null)
                },
                onRemoveFromIndex = {
                    TouchHaptics.click(view)
                    runRemoveFromIndex()
                },
                onDeleteFiles = {
                    TouchHaptics.click(view)
                    confirmDeleteFiles = true
                },
            )
        }
    }

    // 搜索设置面板：官方 ModalBottomSheet 承载阈值调节行与视图模式行，
    // 从「找到 X 项」行的设置按钮（以及无结果态的提示行）打开。
    if (showSettingsSheet) {
        MediaSearchSettingsSheet(
            minScore = minScore,
            hiddenCount = hiddenCount,
            viewGrid = viewGrid,
            onViewGridChange = { grid ->
                // 视图是展示层偏好：切换即时落盘并立即生效，不动检索与结果集。
                viewGrid = grid
                EmbeddingModelStore.setSearchViewGrid(context, grid)
            },
            onValueChange = { minScore = it },
            onValueChangeFinished = {
                EmbeddingModelStore.setSearchMinScore(context, minScore)
                // 阈值是检索时参数：松手落盘后同词静默重检（向量缓存命中，近乎即时）。
                runSearch(fromSlider = true)
            },
            onDismiss = { showSettingsSheet = false },
        )
    }

    // 删除文件是破坏性操作：必须经确认弹窗（标题/说明带数量）后才执行。
    if (confirmDeleteFiles) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.media_delete_files_title, selectedHits.size),
            summary = stringResource(R.string.media_delete_files_summary),
            onDismissRequest = { confirmDeleteFiles = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.media_action_delete_files),
                    onCancel = { confirmDeleteFiles = false },
                    onConfirm = {
                        confirmDeleteFiles = false
                        runDeleteFiles()
                    },
                    destructive = true,
                )
            },
        )
    }

    // 移动/复制同理：选好目标文件夹后再确认一次，标题带文件数与目标文件夹名。
    val batchOperation = pendingOperation
    val batchTargetDir = pendingTargetDir
    if (batchOperation != null && batchTargetDir != null) {
        val targetLabel = directoryLabel(context, batchTargetDir)
        EtaDialog(
            show = true,
            title = stringResource(
                if (batchOperation == MediaFileOps.Op.MOVE) {
                    R.string.media_move_title
                } else {
                    R.string.media_copy_title
                },
                selectedHits.size,
                targetLabel,
            ),
            summary = stringResource(
                if (batchOperation == MediaFileOps.Op.MOVE) {
                    R.string.media_move_summary
                } else {
                    R.string.media_copy_summary
                },
            ),
            onDismissRequest = {
                pendingOperation = null
                pendingTargetDir = null
            },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(
                        if (batchOperation == MediaFileOps.Op.MOVE) {
                            R.string.media_action_move_to
                        } else {
                            R.string.media_action_copy_to
                        },
                    ),
                    onCancel = {
                        pendingOperation = null
                        pendingTargetDir = null
                    },
                    onConfirm = {
                        val operation = batchOperation
                        val target = batchTargetDir
                        pendingOperation = null
                        pendingTargetDir = null
                        runBatchToTarget(operation, target)
                    },
                )
            },
        )
    }
}

/**
 * 类型过滤标签条：全部 / 图片 / 视频 / 音频 / 文件。
 *
 * 走官方 [FilterChip]（自带选中态、涟漪与语义），横向滚动排布，常驻在顶栏下方。
 */
@Composable
private fun MediaFilterBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val filterScrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 两端截断淡出带：筛选标签横向滚动时，被视口切一半的胶囊用 DstIn 蒙版擦掉。
            .horizontalScrollEdgeFade(filterScrollState)
            .horizontalScroll(filterScrollState)
            .padding(
                start = EtaGroupHorizontalPadding,
                end = EtaGroupHorizontalPadding,
                bottom = EtaSegmentedGroupSpacing,
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FILTER_LABELS.forEachIndexed { index, labelRes ->
            FilterChip(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                label = { Text(text = stringResource(labelRes)) },
            )
        }
    }
}

/**
 * 空闲态：官方 Empty state 观感——装饰形状 + 放大镜 + 一句说明（与其它搜索页一致）。
 *
 * 装饰形状每次进页面换一枚轮廓，页内保持稳定（纯装饰，不承载信息）。
 */
@Composable
private fun MediaSearchEmptyState(
    text: String,
    modifier: Modifier = Modifier,
) {
    val decorativeShape = remember { etaRandomDecorativeShape() }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Box(
                modifier = Modifier.size(EtaDimens.EmptyStateShapeSize),
                contentAlignment = Alignment.Center,
            ) {
                EtaDecorativeShape(
                    modifier = Modifier.size(EtaDimens.EmptyStateShapeSize),
                    shape = decorativeShape,
                )
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.size(EtaDimens.EmptyStateGraphicIconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            EtaText(
                text = text,
                style = EtaTextStyle.Secondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 顶栏搜索动作钮：语义检索不能随输入实时触发（每次都要跑嵌入模型），常驻显式按钮，
 * 放大镜图标与设置主页的搜索入口同一套。列表与宫格两套骨架共用。
 */
@Composable
private fun MediaSearchAction(enabled: Boolean, onSearch: () -> Unit) {
    val searchDescription = stringResource(R.string.media_action_search)
    EtaTooltip(text = searchDescription) {
        IconButton(
            onClick = onSearch,
            // 流式中保持可用：再点 = 取消在途检索并按当前词/标签重启（last-wins）。
            enabled = enabled,
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = searchDescription,
            )
        }
    }
}

/**
 * 「音频 / 文件」分类的索引口径提示：常驻在结果区最上方（列表与宫格共用）。
 */
@Composable
private fun MediaFilterNote(filterIndex: Int) {
    EtaText(
        text = stringResource(
            if (filterIndex == 3) {
                R.string.media_filter_audio_note
            } else {
                R.string.media_filter_document_note
            },
        ),
        secondary = true,
        modifier = Modifier.padding(
            start = EtaGroupHorizontalPadding,
            end = EtaGroupHorizontalPadding,
            top = 4.dp,
            bottom = EtaSegmentedGroupSpacing,
        ),
    )
}

/**
 * 分类筛空态：检索本身有结果，只是当前类型标签筛不出任何条目（列表与宫格共用）。
 */
@Composable
private fun MediaFilterEmptyHint() {
    EtaSegmentedColumn(
        modifier = Modifier.padding(
            start = EtaGroupHorizontalPadding,
            end = EtaGroupHorizontalPadding,
            bottom = EtaSegmentedGroupSpacing,
        ),
    ) {
        item {
            EtaClickableRow(
                title = stringResource(R.string.media_filter_no_hits),
                onClick = null,
                showChevron = false,
            )
        }
    }
}

/**
 * 「找到 X 项」计数行：左侧组标题，多选态把「已选 n 项」并入行首文案（居中点号隔开）。
 * 右端纯图标按钮：多选入口（多选态原位变为「取消」，退出并清空勾选）与搜索设置
 * （始终居最右，打开阈值 / 视图模式面板）；长按结果行 / 结果格同样可进入多选。
 * 列表与宫格两套骨架共用（宫格下作为全跨条目呈现）。
 */
@Composable
private fun MediaResultCountRow(
    resultCount: Int,
    selectionMode: Boolean,
    selectedCount: Int,
    onToggleSelectionMode: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = EtaGroupHorizontalPadding,
                end = EtaGroupHorizontalPadding,
                bottom = EtaSegmentedTitleSpacing,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = if (selectionMode) {
                stringResource(R.string.media_search_result_count, resultCount) +
                    " · " +
                    stringResource(R.string.media_selected_count, selectedCount)
            } else {
                stringResource(R.string.media_search_result_count, resultCount)
            },
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        // 多选入口：多选态原位变为「取消」，退出并清空勾选（底栏不再放取消钮）。
        EtaIconButton(
            icon = if (selectionMode) Icons.Rounded.Close else Icons.Rounded.Checklist,
            contentDescription = stringResource(
                if (selectionMode) {
                    R.string.action_cancel
                } else {
                    R.string.media_action_multiselect
                },
            ),
            onClick = {
                TouchHaptics.click(view)
                onToggleSelectionMode()
            },
            size = EtaIconSize.S,
            style = EtaButtonStyle.Standard,
        )
        EtaIconButton(
            icon = Icons.Rounded.Tune,
            contentDescription = stringResource(R.string.media_search_settings),
            onClick = {
                TouchHaptics.click(view)
                onOpenSettings()
            },
            size = EtaIconSize.S,
            style = EtaButtonStyle.Standard,
        )
    }
}

/**
 * 流式尾部指示：还有分片在构建，结果与「找到 X 项」计数会继续增长；检索完成由
 * result 接管后自然消失（列表与宫格共用，宫格下作为全跨条目呈现）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaStreamingTail() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = EtaSegmentedGroupSpacing),
        contentAlignment = Alignment.Center,
    ) {
        // 流式尾部是行内语义：用无容器指示器，不再放 20dp contained。
        LoadingIndicator(
            modifier = Modifier.size(16.dp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 搜索设置面板：官方 [ModalBottomSheet]（不进半展开态）承载 [MediaThresholdRow] 与
 * [MediaViewModeRow]，从「找到 X 项」行的设置按钮打开；关闭走下拉 / 点遮罩 / 返回键。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaSearchSettingsSheet(
    minScore: Float,
    hiddenCount: Int,
    viewGrid: Boolean,
    onViewGridChange: (Boolean) -> Unit,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberEtaSheetState(),
    ) {
        MediaThresholdRow(
            minScore = minScore,
            hiddenCount = hiddenCount,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
        )
        MediaViewModeRow(
            viewGrid = viewGrid,
            onViewGridChange = onViewGridChange,
        )
    }
}

/**
 * 匹配度阈值调节行：左侧标签 + 右侧当前值 + [EtaSlider]（Material3 Slider，0.00–1.00，步进 0.05）。
 * 0 表示不过滤；低于阈值的命中即时隐藏，下方小字提示已隐藏条数（0 条不显示）。
 * 承载于 [MediaSearchSettingsSheet]，不再常驻结果列表上方。
 */
@Composable
private fun MediaThresholdRow(
    minScore: Float,
    hiddenCount: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(
            start = EtaGroupHorizontalPadding,
            end = EtaGroupHorizontalPadding,
            top = 4.dp,
            bottom = EtaSegmentedGroupSpacing,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EtaText(
                text = stringResource(R.string.media_threshold_label),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = String.format(Locale.US, "%.2f", minScore),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        EtaSlider(
            value = minScore,
            // 步进 0.05：拖动值量化到最近档位；0 档即不过滤。
            onValueChange = { onValueChange((it * 20f).roundToInt().coerceIn(0, 20) / 20f) },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
            onValueChangeFinished = onValueChangeFinished,
        )
        if (hiddenCount > 0) {
            EtaText(
                text = stringResource(R.string.media_threshold_hidden, hiddenCount),
                style = EtaTextStyle.Footnote,
            )
        }
    }
}

/**
 * 视图模式行：标签 + 官方单选分段按钮（列表 / 宫格），切换即时回调（调用方落盘）。
 *
 * 走官方 [SingleChoiceSegmentedButtonRow] + [SegmentedButton]（签名已对 material3-android
 * 1.5.0-alpha29 sources 核验：`SegmentedButton(selected, onClick, shape, modifier, enabled,
 * colors, border, contentPadding, interactionSource, icon, label)`，
 * `SegmentedButtonDefaults.itemShape(index, count, baseShape)` 为 @Composable），写法照抄
 * UsageStatsScreen 的 UsageStatsTabs 范本：形状取官方 itemShape，配色 / 行高 / 字阶均为
 * 官方默认值。取证（RULES §1.3）：`~/.gradle/caches/modules-2/files-2.1/
 * androidx.compose.material3/material3-android/1.5.0-alpha29/…-sources.jar` →
 * `commonMain/…/material3/SegmentedButton.kt`；该族无 ExperimentalMaterial3Api 注解。
 */
@Composable
private fun MediaViewModeRow(
    viewGrid: Boolean,
    onViewGridChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Column(
        modifier = modifier.padding(
            start = EtaGroupHorizontalPadding,
            end = EtaGroupHorizontalPadding,
            bottom = EtaSegmentedGroupSpacing,
        ),
    ) {
        EtaText(text = stringResource(R.string.media_view_mode_label))
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = EtaSegmentedTitleSpacing),
        ) {
            listOf(
                false to R.string.media_view_mode_list,
                true to R.string.media_view_mode_grid,
            ).forEachIndexed { index, (grid, labelRes) ->
                SegmentedButton(
                    selected = viewGrid == grid,
                    onClick = {
                        TouchHaptics.click(view)
                        onViewGridChange(grid)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                ) {
                    Text(text = stringResource(labelRes))
                }
            }
        }
    }
}

/**
 * 多选批量操作栏：官方 M3E [HorizontalFloatingToolbar]（纯图标药丸），底部居中悬浮。
 * 操作顺序：全选（全不选）/ 添加到会话 / 移动到 / 复制到 / 移出索引 / 删除文件；
 * 退出多选的「取消」在结果计数行（多选入口原位切换），已选数量提示也与
 * 「找到 X 项」合并显示在同一行。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MediaSelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    busy: Boolean,
    onSelectAll: () -> Unit,
    onAddToChat: () -> Unit,
    onMoveTo: () -> Unit,
    onCopyTo: () -> Unit,
    onRemoveFromIndex: () -> Unit,
    onDeleteFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            // 显式空 leadingContent：官方 minimumInteractiveBalancedPadding 只在两侧都没有
            // leading/trailing 内容时才生效，会按内容里的 MinimumInteractive 对齐线补偿内边距、
            // 把药丸撑高；本栏布局完全显式（固定间距 + 同心端盖），传空让补偿归零
            // （官方参数语义，非 hack）。
            leadingContent = {},
            // 端盖同心：首末两枚 40dp 圆钮与药丸端头弧心对齐（环隙与上下等宽）。
            contentPadding = EtaFloatingToolbarConcentricPadding(
                IconButtonDefaults.smallContainerSize().width,
            ),
        ) {
            // 动作组：固定 8dp 间距的一行图标按钮（EtaIconButton 的 `Standard` 档裸图标）。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EtaIconButton(
                    icon = if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                    contentDescription = stringResource(
                        if (allSelected) {
                            R.string.media_action_deselect_all
                        } else {
                            R.string.media_action_select_all
                        },
                    ),
                    onClick = onSelectAll,
                    enabled = !busy,
                    size = EtaIconSize.S,
                    style = EtaButtonStyle.Standard,
                )
                EtaIconButton(
                    icon = Icons.Rounded.AddComment,
                    contentDescription = stringResource(R.string.media_action_add_to_chat),
                    onClick = onAddToChat,
                    enabled = selectedCount > 0 && !busy,
                    size = EtaIconSize.S,
                    style = EtaButtonStyle.Standard,
                )
                EtaIconButton(
                    icon = Icons.Rounded.DriveFileMove,
                    contentDescription = stringResource(R.string.media_action_move_to),
                    onClick = onMoveTo,
                    enabled = selectedCount > 0 && !busy,
                    size = EtaIconSize.S,
                    style = EtaButtonStyle.Standard,
                )
                EtaIconButton(
                    icon = Icons.Rounded.FileCopy,
                    contentDescription = stringResource(R.string.media_action_copy_to),
                    onClick = onCopyTo,
                    enabled = selectedCount > 0 && !busy,
                    size = EtaIconSize.S,
                    style = EtaButtonStyle.Standard,
                )
                EtaIconButton(
                    icon = Icons.Rounded.BookmarkRemove,
                    contentDescription = stringResource(R.string.media_action_remove_from_index),
                    onClick = onRemoveFromIndex,
                    enabled = selectedCount > 0 && !busy,
                    size = EtaIconSize.S,
                    style = EtaButtonStyle.Standard,
                )
                // 删除文件是破坏性操作：图标用 error 色区分（官方裸 IconButton，容器/图标
                // 尺寸与 EtaIconButton 的 `Standard` + S 档一致），执行前另有确认弹窗。
                val deleteDescription = stringResource(R.string.media_action_delete_files)
                EtaTooltip(text = deleteDescription) {
                    IconButton(
                        onClick = onDeleteFiles,
                        enabled = selectedCount > 0 && !busy,
                        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = deleteDescription,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                        )
                    }
                }
            }
        }
    }
}

/** 结果行摘要：类型 · 分数 · 命中时刻（视频）· 尺寸/时长 · 体积 · 修改时间。 */
@Composable
private fun hitSummary(context: Context, hit: MediaSearchService.Hit): String {
    val kindLabel = stringResource(
        when (hit.kind) {
            MediaKind.IMAGE -> R.string.media_kind_image
            MediaKind.VIDEO -> R.string.media_kind_video
            MediaKind.AUDIO -> R.string.media_kind_audio
            MediaKind.DOCUMENT -> R.string.media_kind_document
        },
    )
    val parts = buildList {
        add(kindLabel)
        add(context.getString(R.string.media_score, String.format(Locale.US, "%.2f", hit.score)))
        // 视频「时刻检索」命中时刻（如 01:23）。
        hit.frameTimeMs?.let { add(context.getString(R.string.media_hit_moment, formatDuration(it))) }
        hit.width?.let { width ->
            hit.height?.let { height -> add("${width}x${height}") }
        }
        hit.durationMs?.let { add(formatDuration(it)) }
        add(formatBytes(hit.sizeBytes))
        add(formatTime(hit.modifiedAt))
    }
    return parts.joinToString(" · ")
}

/** 结果行缩略图宽度（与设置页行首图标一致）。 */
private const val THUMB_DP = 48
/** 原图回退解码的目标边长（px 按 density 换算，只用于行内小图）。 */
private const val FALLBACK_THUMB_DP = 96
/** 宫格列数：相册式纯缩略图墙固定 3 列。 */
private const val GRID_COLUMNS = 3
/** 宫格间距（水平与垂直同值）：与行列表 2dp 行缝同一节奏。 */
private const val GRID_GAP_DP = 2
/** 宫格单格圆角：与行内缩略图同一规格。 */
private const val GRID_CELL_CORNER_DP = 8
/** 宫格单格缩略图解码目标边长：约 3 列格宽，防拉伸模糊。 */
private const val GRID_THUMB_DP = 128
/** 宫格原图回退解码目标边长。 */
private const val GRID_FALLBACK_THUMB_DP = 256
private const val THUMB_LOG_TAG = "MediaSearchThumb"
/** 原图回退解码的并发上限：避免快速滚动时几十张原图同时解码把内存打满。 */
private val ThumbnailDecodePermits = Semaphore(3)
/**
 * 原图回退的进程内 LRU（约 24MB）：行内 48dp 与宫格约 128dp 的小图共用。宫格单格位图
 * 较大，8MB 只能驻留十几张，快速回滚会反复重解码闪占位块。
 */
private val ThumbnailBitmapCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

/** 缩略图状态：可显示 / 不可用（不可用时按 kind 与「本来就没有」区分底色，见 [HitThumbnail]）。 */
private sealed interface ThumbState {
    data object Loading : ThumbState
    data class Ready(val bitmap: ImageBitmap) : ThumbState
    data object Missing : ThumbState
}

/**
 * 结果行缩略图（48dp 圆角 8dp，右侧留 8dp 与标题起点对齐）。
 * 加载链见 [MediaThumbCore]。
 */
@Composable
private fun HitThumbnail(hit: MediaSearchService.Hit) {
    MediaThumbCore(
        hit = hit,
        targetDp = THUMB_DP.dp,
        fallbackDp = FALLBACK_THUMB_DP.dp,
        modifier = Modifier
            .padding(end = 8.dp)
            .size(THUMB_DP.dp),
    )
}

/**
 * 缩略图渲染核：加载链 + 底色 / 图标分支 + 裁剪贴图；尺寸与边距全部交给调用方的
 * [modifier]（行列表传 48dp 固定尺寸，宫格传铺满格子）。[targetDp] / [fallbackDp] 由
 * 调用方按呈现尺寸给出，解码与原图回退按目标边长采样。
 *
 * 视频命中的时刻已知（`hit.frameTimeMs`）时先用该时刻现取的帧（[momentThumbnail]），
 * 预览图对齐「命中 mm:ss」那一刻；取不到再走下面的回退，不会出现空白卡片。
 *
 * 1. 索引里的缩略图只存文件名（[MediaThumbnailStore] 解析到当前安装目录），按目标像素采样解码；
 * 2. 缺失或解码失败时，对图片按原始 contentUri/路径在 IO 线程解一张小图（带并发上限与 LRU）；
 * 3. 仍不可用时：**图片/视频**用错误色底 + error 图标（这是"加载失败/索引缺图"），
 *    音频/文档用中性底色 + 常规图标（它们本来就没有缩略图）。
 */
@Composable
private fun MediaThumbCore(
    hit: MediaSearchService.Hit,
    targetDp: Dp,
    fallbackDp: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val targetPx = with(density) { targetDp.roundToPx() }
    val fallbackPx = with(density) { fallbackDp.roundToPx() }
    val state by produceState<ThumbState>(
        initialValue = ThumbState.Loading,
        key1 = hit.sourceKey,
        key2 = hit.thumbPath,
        // 时刻晚到（精排异步回填）时也要重载：不带这个 key 会一直停在封面图。
        key3 = hit.frameTimeMs,
    ) {
        value = withContext(Dispatchers.IO) {
            loadThumbnail(context, hit, targetPx, fallbackPx)
        }
    }
    val colors = MaterialTheme.colorScheme
    val isMediaKind = hit.kind == MediaKind.IMAGE || hit.kind == MediaKind.VIDEO
    val ready = state as? ThumbState.Ready
    // 只有"确实拿不到"才用错误色；加载中保持中性，避免快速滚动时红块闪烁。
    // 空串是 LRU 主动淘汰的哨兵（不是失败，等下次索引/内容变更才重建），用中性占位。
    val missingMedia = isMediaKind && state is ThumbState.Missing &&
        hit.thumbPath != MediaThumbnailStore.EVICTED
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    ready != null -> colors.surfaceContainerHighest
                    // 媒体类缺图/解码失败与「本来没有缩略图」用不同底色区分；
                    // 加载中保持中性，避免快速滚动时红块闪烁。
                    missingMedia -> colors.errorContainer
                    else -> colors.surfaceContainerHighest
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (ready != null) {
            Image(
                bitmap = ready.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = when (hit.kind) {
                    MediaKind.IMAGE -> Icons.Rounded.Image
                    MediaKind.VIDEO -> Icons.Rounded.Videocam
                    MediaKind.AUDIO -> Icons.Rounded.MusicNote
                    MediaKind.DOCUMENT -> Icons.Rounded.Description
                },
                contentDescription = null,
                tint = if (missingMedia) colors.error else colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * 宫格单格：相册式纯缩略图墙的一格，方形铺满官方 [androidx.compose.foundation.lazy.grid.LazyVerticalGrid]
 * 的固定列宽（3 列、格间 2dp），无文字——文件名 / 摘要 / 分数只在行列表形态呈现。
 *
 * 交互与行列表同套回调：非多选点按打开（[openHit] 链路）、多选态点按改勾选、长按进多选
 * 并选中当前格（[combinedClickable] 的长按触感内建，`hapticFeedbackEnabled` 默认开启——
 * 取证（RULES §1.3）：foundation-android 1.13.0-alpha01 sources jar →
 * `commonMain/…/foundation/Clickable.kt`，onLongClick 触发后
 * `performHapticFeedback(HapticFeedbackType.LongPress)`；该重载无 Experimental 注解）。
 * 多选态左上角叠官方 [Checkbox]（24dp + 8dp 内边距，与行列表多选勾选框同一规格），
 * 选中格加 2dp primary 描边。视频格左下角标白色时长（[MediaVideoBadge]），底下垫
 * 48dp 半透明黑渐变条保证可读性，与图片区分。缩略图走 [MediaThumbCore]
 * （宫格解码目标 128dp / 回退 256dp，防拉伸模糊）。
 */
@Composable
private fun HitGridCell(
    hit: MediaSearchService.Hit,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            // 格内无文字：文件名作为无障碍内容，与行列表（Text 提供朗读内容）对齐。
            .semantics { contentDescription = hit.displayName }
            .clip(RoundedCornerShape(GRID_CELL_CORNER_DP.dp))
            .then(
                if (checked) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(GRID_CELL_CORNER_DP.dp),
                    )
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = {
                    TouchHaptics.click(view)
                    onClick()
                },
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MediaThumbCore(
            hit = hit,
            targetDp = GRID_THUMB_DP.dp,
            fallbackDp = GRID_FALLBACK_THUMB_DP.dp,
            modifier = Modifier.fillMaxSize(),
        )
        if (hit.kind == MediaKind.VIDEO) {
            // 底部渐变条：半透明黑渐隐托住左下角的白色时长角标（官方相册同款的可读性
            // 处理；写法沿用仓内渐隐带先例 ChatMessageItem / ScrollEdgeFade——具名
            // Color 常量 + Brush.verticalGradient，不在 R1 拦截之列）。只垫视频格：
            // 图片格保持纯净的墙。渐变条在缩略图之上、角标与勾选框之下。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                        ),
                    ),
            )
            MediaVideoBadge(hit)
        }
        if (selectionMode) {
            // 前导勾选框仅作视觉呈现（与行列表多选一致），切换统一由整格点击完成，
            // 避免两次回调。
            Checkbox(
                checked = checked,
                onCheckedChange = null,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .size(24.dp),
            )
        }
    }
}

/**
 * 宫格视频标记：相册式墙没有文字行，视频格在左下角标白色时长区分于图片——官方
 * Google 相册 / 系统照片选择器的网格都以左下角时长标记视频，写法与本仓聊天视频附件
 * 的标记（ChatMessageItem：白色时长直叠缩略图、bottom-start 8dp、官方 bodySmall）
 * 同一套；白色直叠沿用聊天先例（R1 只拦十六进制颜色字面量，具名 Color 常量不在
 * 拦截之列）。时长未知时退化为白色 PlayArrow 角标（尺寸取官方
 * [FloatingActionButtonDefaults.MediumIconSize]，与聊天视频的播放箭头同一令牌），
 * 保证视频始终可与图片区分。不用居中箭头：缺图占位已居中一枚 kind 图标，再叠一枚
 * 会重合，且居中箭头是「可播放」语义、不属于浏览墙。白字底下由调用方垫半透明黑
 * 渐变条（亮图上的可读性），本组件只负责角标本体。
 */
@Composable
private fun BoxScope.MediaVideoBadge(hit: MediaSearchService.Hit) {
    val duration = hit.durationMs
    if (duration == null) {
        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .size(FloatingActionButtonDefaults.MediumIconSize),
        )
    } else {
        Text(
            text = formatDuration(duration),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        )
    }
}

/** 缩略图加载：视频命中时刻帧 → 缓存文件 → 图片原图；全程 IO 线程。 */
private suspend fun loadThumbnail(
    context: Context,
    hit: MediaSearchService.Hit,
    targetPx: Int,
    fallbackPx: Int,
): ThumbState {
    // 视频且命中时刻已知：预览图优先对齐命中时刻；取帧失败继续走索引封面图。
    if (hit.kind == MediaKind.VIDEO) {
        hit.frameTimeMs?.let { timeMs ->
            momentThumbnail(context, hit, timeMs)?.let { return ThumbState.Ready(it) }
        }
    }
    val cached = MediaThumbnailStore.fileFor(context, hit.thumbPath)
    if (cached != null && cached.isFile && cached.length() > 0) {
        decodeFileSampled(cached, targetPx)?.let { return ThumbState.Ready(it) }
        // 文件在但解不出来（截断/损坏）：留证据，随后走原图回退。
        Log.w(THUMB_LOG_TAG, "缩略图解码失败 source=${hit.sourceKey} kind=${hit.kind.wire}")
    }
    if (hit.kind != MediaKind.IMAGE) {
        // 音频没有原图可解；视频的时刻帧已在上方尝试过，缺缩略图就是缺。
        return ThumbState.Missing
    }
    val key = "fallback:${hit.sourceKey}"
    ThumbnailBitmapCache.get(key)?.let { return ThumbState.Ready(it) }
    return ThumbnailDecodePermits.withPermit {
        ThumbnailBitmapCache.get(key)?.let { return@withPermit ThumbState.Ready(it) }
        val bitmap = runCatching { decodeOriginal(context, hit, fallbackPx) }
            .onFailure { failure ->
                Log.w(
                    THUMB_LOG_TAG,
                    "原图回退解码失败 source=${hit.sourceKey} mime=${hit.mime} " +
                        "type=${failure.javaClass.simpleName}",
                )
            }.getOrNull()
        if (bitmap == null) {
            Log.w(THUMB_LOG_TAG, "缩略图与回退均不可用 source=${hit.sourceKey} kind=${hit.kind.wire}")
            ThumbState.Missing
        } else {
            ThumbnailBitmapCache.put(key, bitmap)
            ThumbState.Ready(bitmap)
        }
    }
}

/**
 * 视频命中时刻帧：按 `hit.frameTimeMs` 现取一帧（[MediaExtraction.momentFrameJpeg] 已把帧缩到
 * [MediaExtraction.MAX_THUMB_EDGE] 以内），让预览图对应「命中 mm:ss」那一刻而不是索引封面。
 *
 * 缓存键由 `sourceKey + modifiedAt + frameTimeMs` 组成：同一文件被不同查询命中到不同时刻时
 * 互不覆盖，也需要靠它区分「文件被替换」后的旧帧。
 * 取帧阻塞且不可中断，与图片原图回退共用 [ThumbnailDecodePermits] 并发上限，只在 IO 线程调用；
 * 失败返回 null，由 [loadThumbnail] 回落到索引封面图。
 */
private suspend fun momentThumbnail(
    context: Context,
    hit: MediaSearchService.Hit,
    timeMs: Long,
): ImageBitmap? {
    val key = "moment:${hit.sourceKey}:${hit.modifiedAt}:$timeMs"
    ThumbnailBitmapCache.get(key)?.let { return it }
    return ThumbnailDecodePermits.withPermit {
        ThumbnailBitmapCache.get(key)?.let { return@withPermit it }
        val bitmap = runCatching {
            MediaExtraction.momentFrameJpeg(
                context = context,
                sourceKey = hit.sourceKey,
                contentUri = hit.contentUri,
                filePath = hit.path,
                timeMs = timeMs,
            )?.let { bytes ->
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 },
                )?.asImageBitmap()
            }
        }.onFailure { failure ->
            Log.w(
                THUMB_LOG_TAG,
                "命中时刻帧解码失败 source=${hit.sourceKey} timeMs=$timeMs " +
                    "type=${failure.javaClass.simpleName}",
            )
        }.getOrNull()
        if (bitmap != null) ThumbnailBitmapCache.put(key, bitmap)
        bitmap
    }
}

/** 采样解码到不小于目标边长的最小 2 的幂，修正原先固定 `inSampleSize = 2` 导致的糊。 */
private fun decodeFileSampled(file: File, targetPx: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds, targetPx) }
    BitmapFactory.decodeFile(file.absolutePath, options)?.asImageBitmap()
}.getOrNull()

@Suppress("SameParameterValue")
private fun decodeOriginal(context: Context, hit: MediaSearchService.Hit, targetPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decodeHit(context, hit, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds, targetPx)
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return decodeHit(context, hit, options)?.asImageBitmap()
}

private fun decodeHit(
    context: Context,
    hit: MediaSearchService.Hit,
    options: BitmapFactory.Options,
): Bitmap? = runCatching {
    val uri = hit.contentUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    if (uri != null) {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } else {
        hit.path?.takeIf { it.isNotBlank() }?.let { BitmapFactory.decodeFile(it, options) }
    }
}.getOrNull()

private fun sampleSizeFor(bounds: BitmapFactory.Options, targetPx: Int): Int {
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / (sample * 2) >= targetPx) sample *= 2
    return sample
}

/**
 * 结果行：非多选态点按打开、长按进入多选并选中当前行；多选态行首显示复选框，
 * 整行点击改勾选（单条「打开」入口随多选模式隐藏，专注批量）。
 * 多选态走官方 checked 变体（[EtaSegmentedRow] 的 checked/onCheckedChange 入口）：
 * 勾选翻转发生在同一调用点内，官方的选中形状 morph 与底色过渡才能动画播放。
 * 点按/长按触感由 [EtaSegmentedRow] 内建（与设置主页同一套），行内不再重复触发。
 */
@Composable
private fun MediaHitRow(
    hit: MediaSearchService.Hit,
    summary: String,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EtaSegmentedRow(
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        // 多选态切到官方 checked 变体：勾选/取消勾选在同一调用点内翻转，官方选中形状
        // morph（rememberAnimatedShape）与选中底色过渡才会动画播放；非多选态仍是普通
        // 可点行。行点击统一走调用方注入的 onClick（多选态即勾选切换，触感由组件内建）。
        checked = if (selectionMode) checked else null,
        onCheckedChange = if (selectionMode) {
            { onClick() }
        } else {
            null
        },
        // 多选勾选行：上下保持官方内边距，不参与动作行的纵向补偿。
        choiceRow = true,
        leadingContent = {
            if (selectionMode) {
                // 8dp 间距与缩略图右侧留白同一节奏（行内既定值）；官方勾选行里勾选框
                // 独占 leading 槽，组内间距无官方默认值可依，这里显式取 8dp。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 前导勾选框仅作视觉呈现（与官方 SegmentedCheckboxItem 一致），
                    // 切换统一由整行点击完成，避免两次回调。
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        modifier = Modifier.size(24.dp),
                    )
                    HitThumbnail(hit)
                }
            } else {
                HitThumbnail(hit)
            }
        },
        content = {
            // 长文件名在单行内截断：行首缩略图与行尾状态不被长标题挤走。
            Text(
                text = hit.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(text = summary) },
    )
}

/**
 * 打开命中项：优先 content uri（MediaStore），路径走 FileProvider 的 ACTION_VIEW；
 * 与聊天文件链接同一套打开链路，不做额外预览。
 */
private fun openHit(context: Context, hit: MediaSearchService.Hit) {
    try {
        val uri = hit.contentUri?.let { Uri.parse(it) }
            ?: hit.path?.let {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(it))
            }
            ?: error("no uri")
        val mime = hit.mime.ifEmpty { "*/*" }
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            clipData = android.content.ClipData.newRawUri(hit.displayName, uri)
        })
    } catch (e: Exception) {
        Toast.makeText(
            context,
            context.getString(R.string.media_open_failed, e.message ?: ""),
            Toast.LENGTH_LONG,
        ).show()
    }
}

/**
 * 目标文件夹展示名：绝对路径取末级目录名；SAF tree uri 从 tree document id（`primary:DCIM/Camera`）
 * 解出末段，查询不到再回落 DISPLAY_NAME / 末段路径。
 */
private fun directoryLabel(context: Context, dir: MediaDir): String {
    dir.path?.let { path ->
        return File(path).name.ifEmpty { path }
    }
    val uri = dir.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return ""
    val fromDocumentId = runCatching {
        Uri.decode(DocumentsContract.getTreeDocumentId(uri))
            .substringAfterLast('/')
            .substringAfterLast(':')
    }.getOrNull()
    if (!fromDocumentId.isNullOrBlank()) return fromDocumentId
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
}

/**
 * 删缩略图（源文件不受影响）：索引里存的是文件名，统一交给 [MediaThumbnailStore] 解析与删除。
 */
private fun deleteThumbnail(context: Context, hit: MediaSearchService.Hit) {
    MediaThumbnailStore.deleteForSourceKey(context, hit.sourceKey, hit.thumbPath)
}

/**
 * 删源文件本体，三种形态各有分支：SAF tree 文档走 [DocumentsContract.deleteDocument]，
 * MediaStore content 走 ContentResolver.delete，普通文件按绝对路径 [File.delete]。
 * 单条失败只返回 false（无权限 / 被占用等），不抛异常、不影响其他条目。
 */
private fun deleteSourceFile(context: Context, hit: MediaSearchService.Hit): Boolean {
    val uri = hit.contentUri?.let(Uri::parse)
    val path = hit.path
    return when {
        uri != null && DocumentsContract.isDocumentUri(context, uri) ->
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
        uri != null ->
            runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
        path != null ->
            runCatching { File(path).let { file -> file.exists() && file.delete() } }.getOrDefault(false)
        else -> false
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(Locale.getDefault(), bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(Locale.getDefault(), bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.1f KB".format(Locale.getDefault(), bytes / 1_000.0)
    else -> "$bytes B"
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(Locale.getDefault(), minutes, seconds)
}

// formatter 提为单例：结果行每次组合都会调 formatTime（经 hitSummary），现场 new
// SimpleDateFormat 在滚动/勾选/筛选的重组路径上是纯分配。调用点全部在主线程，无并发问题。
private val SearchResultTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTime(millis: Long): String = SearchResultTimeFormat.format(Date(millis))
