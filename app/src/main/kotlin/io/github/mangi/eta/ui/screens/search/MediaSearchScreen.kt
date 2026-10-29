package io.github.mangi.eta.ui.screens.search

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.media.index.EmbeddingModelStore
import io.github.mangi.eta.agent.media.index.MediaFileOps
import io.github.mangi.eta.agent.media.index.MediaFileOps.MediaDir
import io.github.mangi.eta.agent.media.index.MediaFileOps.MediaTarget
import io.github.mangi.eta.agent.media.index.MediaSearchService
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MediaKind
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDecorativeShape
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSearchHistory
import io.github.mangi.eta.ui.adaptive.EtaSearchHistoryItems
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
import io.github.mangi.eta.ui.adaptive.etaRandomDecorativeShape
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.components.ContainedMorphLoadingIndicator
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.navigation.AppRoute
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * 搜索前后都能切换范围；多选入口是与「找到 X 项」同行的纯文字按钮（有结果时显示
 * 「多选」，多选态变为「取消」）。
 *
 * 检索统一走 [MediaSearchService]（与 AI 工具 `search_media` 同一入口），全程离线；
 * 模型未下载或扫描源未确认时显示引导卡片跳设置页。
 *
 * 搜索记录与其它独立搜索页同源（[EtaSearchHistory]，分域键 `media`）：每次真正跑检索落一条，
 * 搜索框为空时先列记录（点一条直接检索），无记录才显示官方 Empty state。
 */
@Composable
internal fun MediaSearchScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
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
    var errorText by remember { mutableStateOf<String?>(null) }
    // 匹配度阈值：进页面恢复上次调节值（EmbeddingModelStore prefs），拖动结束落盘。
    var minScore by rememberSaveable { mutableStateOf(0f) }
    // 多选批量管理：勾选集合以 sourceKey 为键；操作完成或新一轮检索后退出并清空。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedKeys by remember { mutableStateOf(setOf<String>()) }
    var confirmDeleteFiles by remember { mutableStateOf(false) }
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
    }

    /** 记录一次检索词：空词忽略，去重置顶（与其它搜索页同一份实现）。 */
    fun recordSearch(text: String) {
        EtaSearchHistory.add(context, HISTORY_KEY, text)
        history = EtaSearchHistory.load(context, HISTORY_KEY)
    }

    /**
     * 执行一次语义检索；查询为空或索引未就绪时不动作（引导卡片负责指向设置页）。
     * [text] 显式传入是为了「点搜索记录」这种先改词再检索的场景（state 还没重组，读 [query] 拿到的是旧值）。
     */
    fun runSearch(text: String = query.trim()) {
        if (text.isEmpty() || searching) return
        // 真正跑过检索的词就是有效检索：落一条搜索记录。
        recordSearch(text)
        // 检索是显式动作：触发后立刻收起键盘，结果区不再被输入法挤成半屏。
        focusManager.clearFocus()
        // 换结果集先退出多选：旧结果上的勾选不延续到新结果。
        selectionMode = false
        selectedKeys = emptySet()
        scope.launch {
            searching = true
            errorText = null
            val outcome = runCatching {
                MediaSearchService.search(
                    context = context,
                    query = text,
                    kinds = kindsForFilter(filterIndex),
                    limit = 20,
                )
            }
            result = outcome.getOrNull()
            outcome.exceptionOrNull()?.let { errorText = it.message ?: "search failed" }
            searching = false
            status = runCatching { MediaSearchService.status(context) }.getOrNull()
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
    val rawHits = result?.hits.orEmpty()
    // 阈值即时过滤：低于阈值的命中不显示（0 不过滤），与服务端 MediaSearchService.applyMinScore 同口径。
    val hits = MediaSearchService.applyMinScore(rawHits, minScore)
    val hiddenCount = rawHits.size - hits.size
    // 勾选按当前显示的命中取交集：阈值抬高后被隐藏的条目不再计入批量操作。
    val selectedHits = hits.filter { it.sourceKey in selectedKeys }

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

    Box(modifier = Modifier.fillMaxSize()) {
        EtaSearchScaffold(
            query = query,
            onQueryChange = { query = it },
            searchPlaceholder = stringResource(R.string.media_search_placeholder),
            onBack = onBack,
            // IME 的搜索键与顶栏的「搜索」按钮走同一条路径。
            onSubmit = { runSearch() },
            actions = {
                // 语义检索每次都要跑嵌入模型，不能随输入实时触发，所以顶栏常驻一个显式按钮。
                // 用放大镜图标表示（与设置主页的搜索入口同一套图标）。
                IconButton(
                    onClick = { runSearch() },
                    enabled = query.trim().isNotEmpty() && ready && !searching,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.media_action_search),
                    )
                }
            },
            topContent = {
                // 类型标签常驻顶栏下方：搜索前后都能切换范围，不用滚回页面顶部。
                MediaFilterBar(
                    selectedIndex = filterIndex,
                    onSelect = { index ->
                        TouchHaptics.click(view)
                        filterIndex = index
                        if (result != null && query.trim().isNotEmpty()) runSearch()
                    },
                )
            },
        ) {
            // 选中「音频 / 文件」时的索引口径提示：常驻在结果列表上方。
            if (filterIndex == 3 || filterIndex == 4) {
                item(key = "filter-note") {
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
                searching -> {
                    // 检索中：块级 loading（M3E 官方 morph 指示器），整体居中展示。
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
                result == null -> {
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
                    // 无结果态：索引为空时直接给「去建立索引」引导。
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
                else -> {
                    // 匹配度阈值调节行：结果区上方，低于阈值的命中即时隐藏并提示已隐藏条数。
                    item(key = "threshold") {
                        MediaThresholdRow(
                            minScore = minScore,
                            hiddenCount = hiddenCount,
                            onValueChange = { minScore = it },
                            onValueChangeFinished = {
                                EmbeddingModelStore.setSearchMinScore(context, minScore)
                            },
                        )
                    }
                    // 结果计数行：左侧「找到 X 项」组标题，右侧多选入口（纯文字按钮，
                    // 原顶栏图标钮迁到这里）。非多选态显示「多选」，多选态变为「取消」，
                    // 点按退出并清空勾选；长按结果行同样可进入多选。
                    item(key = "result-count") {
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
                                text = stringResource(R.string.media_search_result_count, hits.size),
                                style = MaterialTheme.typography.titleSmallEmphasized,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    TouchHaptics.click(view)
                                    selectionMode = !selectionMode
                                    selectedKeys = emptySet()
                                },
                            ) {
                                Text(
                                    text = stringResource(
                                        if (selectionMode) {
                                            R.string.action_cancel
                                        } else {
                                            R.string.media_action_multiselect
                                        },
                                    ),
                                )
                            }
                        }
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
                }
            }
        }

        // 多选批量操作栏：底部悬浮常驻，显示已选数量并承载全选与两个批量操作。
        if (selectionMode) {
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
                onExit = {
                    TouchHaptics.click(view)
                    selectionMode = false
                    selectedKeys = emptySet()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
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
 * 匹配度阈值调节行：左侧标签 + 右侧当前值 + [EtaSlider]（Material3 Slider，0.00–1.00，步进 0.05）。
 * 0 表示不过滤；低于阈值的命中即时隐藏，下方小字提示已隐藏条数（0 条不显示）。
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
 * 多选批量操作栏：底部悬浮（[EtaCard]），显示已选数量；
 * 操作顺序：移动到 / 复制到 / 移出索引 / 删除文件（破坏性操作靠右，文案走 error 色）。
 */
@Composable
private fun MediaSelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    busy: Boolean,
    onSelectAll: () -> Unit,
    onMoveTo: () -> Unit,
    onCopyTo: () -> Unit,
    onRemoveFromIndex: () -> Unit,
    onDeleteFiles: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    EtaCard(
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EtaText(
                    text = stringResource(R.string.media_selected_count, selectedCount),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSelectAll, enabled = !busy) {
                    Text(
                        stringResource(
                            if (allSelected) {
                                R.string.media_action_deselect_all
                            } else {
                                R.string.media_action_select_all
                            },
                        ),
                    )
                }
                TextButton(onClick = onExit, enabled = !busy) {
                    Text(stringResource(R.string.media_action_exit_select))
                }
            }
            // 四个操作分两行：非破坏性的「移动到 / 复制到」在上，破坏性的「移出索引 / 删除文件」在下。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onMoveTo,
                    enabled = selectedCount > 0 && !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.media_action_move_to))
                }
                TextButton(
                    onClick = onCopyTo,
                    enabled = selectedCount > 0 && !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.media_action_copy_to))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onRemoveFromIndex,
                    enabled = selectedCount > 0 && !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.media_action_remove_from_index))
                }
                // 删除文件是破坏性操作：文案用 error 色区分，执行前另有确认弹窗。
                TextButton(
                    onClick = onDeleteFiles,
                    enabled = selectedCount > 0 && !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.media_action_delete_files),
                        color = MaterialTheme.colorScheme.error,
                    )
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

/** 结果行缩略图：thumbPath 有值用缩略图，否则按 kind 图标占位。 */
@Composable
private fun HitThumbnail(hit: MediaSearchService.Hit) {
    val thumbPath = hit.thumbPath
    val bitmap by produceState<ImageBitmap?>(initialValue = null, thumbPath) {
        value = thumbPath?.let { path ->
            withContext(Dispatchers.IO) {
                runCatching {
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
                        ?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(
        modifier = Modifier
            // 右侧留 8dp：与设置主页行首图标的间距一致，标题起点对齐。
            .padding(end = 8.dp)
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 结果行：非多选态点按打开、长按进入多选并选中当前行；多选态行首显示复选框，
 * 整行点击改勾选（单条「打开」入口随多选模式隐藏，专注批量）。
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
        selected = selectionMode && checked,
        // 多选勾选行：上下保持官方内边距，不参与动作行的纵向补偿。
        choiceRow = true,
        leadingContent = {
            if (selectionMode) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
        content = { Text(text = hit.displayName) },
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
 * 删缩略图缓存（源文件不受影响）：优先删索引行上的 thumbPath，
 * 兜底删 `media-thumbs/SHA1(sourceKey).jpg`（命名见 MediaExtraction.saveThumbnail）。
 */
private fun deleteThumbnail(context: Context, hit: MediaSearchService.Hit) {
    runCatching { hit.thumbPath?.let { File(it).delete() } }
    val name = MessageDigest.getInstance("SHA-1")
        .digest(hit.sourceKey.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    runCatching { File(File(context.cacheDir, "media-thumbs"), "$name.jpg").delete() }
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

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
