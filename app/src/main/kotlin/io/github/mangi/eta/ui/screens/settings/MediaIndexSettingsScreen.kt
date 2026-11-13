package io.github.mangi.eta.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.media.index.EmbeddingModelCatalog
import io.github.mangi.eta.agent.media.index.EmbeddingModelStore
import io.github.mangi.eta.agent.media.index.EtaEmbeddingEngine
import io.github.mangi.eta.agent.media.index.IndexMode
import io.github.mangi.eta.agent.media.index.InferenceBackend
import io.github.mangi.eta.agent.media.index.MediaIndexAutoUpdate
import io.github.mangi.eta.agent.media.index.MediaIndexer
import io.github.mangi.eta.agent.media.index.MediaIndexService
import io.github.mangi.eta.agent.media.index.MediaSearchService
import io.github.mangi.eta.agent.media.index.MediaSourceScanner
import io.github.mangi.eta.agent.terminal.SharedFolderMount
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MediaSourceEntity
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaLoadingSlot
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedCheckboxRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRadioRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.navigation.AppRoute
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 允许的 Matryoshka 截断维度（与 [EmbeddingModelStore] 一致）。 */
private val VECTOR_DIMS = listOf(768, 512, 256)

/** 推理后端下拉项顺序：自动（GPU → NPU → CPU）优先，其余按算力降序。 */
private val BACKEND_OPTIONS = listOf(
    InferenceBackend.AUTO,
    InferenceBackend.CPU,
    InferenceBackend.GPU,
    InferenceBackend.NPU,
)

/** 定时自动更新档位（小时）：关闭 / 每 6 小时 / 每天 / 每 3 天 / 每周（取值与 [MediaIndexAutoUpdate] 一致）。 */
private val AUTO_INTERVAL_HOURS = listOf(0, 6, 24, 72, 168)

/** 系统媒体库源需要的按需运行时权限（minSdk 34，含部分授权）。 */
private val MEDIA_READ_PERMISSIONS = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_AUDIO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
)

/**
 * 媒体语义搜索设置页：模型下载/校验、扫描范围确认、索引构建。
 *
 * 扫描范围必须先由用户勾选并「确认」才会写入 media_source 表并开始首次索引；
 * 系统媒体库项在确认时按需申请媒体读权限，拒绝后仍可用无限权的附件缓存/工作区范围。
 */
@Composable
internal fun MediaIndexSettingsScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val modelState by EmbeddingModelStore.state.collectAsState()
    val progress by MediaIndexer.state.collectAsState()

    var dimIndex by remember { mutableIntStateOf(VECTOR_DIMS.indexOf(EmbeddingModelStore.vectorDim(context)).coerceAtLeast(0)) }
    var backendIndex by remember {
        mutableIntStateOf(BACKEND_OPTIONS.indexOf(EmbeddingModelStore.backend(context)).coerceAtLeast(0))
    }
    var indexMode by remember { mutableStateOf(EmbeddingModelStore.indexMode(context)) }
    var autoIndexOnLaunch by remember { mutableStateOf(MediaIndexAutoUpdate.autoIndexOnLaunch(context)) }
    var autoIntervalHours by remember { mutableIntStateOf(MediaIndexAutoUpdate.autoIndexIntervalHours(context)) }
    var activeBackend by remember { mutableStateOf(EtaEmbeddingEngine.activeBackend) }
    var backendFellBack by remember { mutableStateOf(EtaEmbeddingEngine.activeBackendFellBack) }
    var lowMemoryDevice by remember { mutableStateOf(EmbeddingModelStore.lowMemoryDevice(context)) }
    var partialMediaAccess by remember { mutableStateOf(isPartialMediaAccess(context)) }
    var annSwitchPending by remember { mutableStateOf(false) }
    var savedSources by remember { mutableStateOf<Map<String, MediaSourceEntity>>(emptyMap()) }
    var mounts by remember { mutableStateOf<List<SharedFolderMount>>(emptyList()) }
    var selected by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var estimates by remember { mutableStateOf<Map<String, MediaSourceScanner.Estimate>>(emptyMap()) }
    var estimating by remember { mutableStateOf(true) }
    var safPermissionLost by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingSafTargetId by remember { mutableStateOf<String?>(null) }
    var pendingRemoveSource by remember { mutableStateOf<MediaSourceEntity?>(null) }
    var indexedCount by remember { mutableIntStateOf(0) }
    var lastIndexedAt by remember { mutableStateOf<Long?>(null) }
    var indexError by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    // SAF 自选文件夹取自已落库的源行；共享文件夹按当前挂载配置派生（勾选态落 media_source）。
    val safSources = remember(savedSources) {
        savedSources.values.filter { it.type == MediaSourceScanner.TYPE_SAF_TREE }.sortedBy { it.label }
    }
    val sharedSources = remember(mounts, savedSources) {
        mounts.map { mount ->
            sharedFolderSource(context, mount, savedSources[sharedFolderSourceId(mount.name)])
        }
    }

    suspend fun refreshIndexMeta() {
        val status = runCatching {
            withContext(Dispatchers.IO) { MediaSearchService.status(context) }
        }.getOrNull()
        indexedCount = status?.indexedCount ?: indexedCount
        lastIndexedAt = status?.lastIndexedAt ?: lastIndexedAt
        annSwitchPending = status?.approachingAnnSwitch ?: annSwitchPending
        // 引擎是懒加载的，索引/检索结束后读一次生效后端快照即可。
        activeBackend = EtaEmbeddingEngine.activeBackend
        backendFellBack = EtaEmbeddingEngine.activeBackendFellBack
    }

    /** 刷新推荐项 + SAF + 共享文件夹的勾选态、SAF 授权态与预估；加删文件夹后调用。 */
    suspend fun refreshSources() {
        estimating = true
        val loaded = withContext(Dispatchers.IO) {
            val dao = EtaDatabase.get(context).mediaIndexDao()
            val savedNow = dao.sources().associateBy { it.id }
            val mountsNow = SharedFolderMounts.current()
            val sharedNow = mountsNow.map { mount ->
                sharedFolderSource(context, mount, savedNow[sharedFolderSourceId(mount.name)])
            }
            Triple(savedNow, mountsNow, sharedNow)
        }
        val (saved, mountsNow, shared) = loaded
        savedSources = saved
        mounts = mountsNow
        val saf = saved.values.filter { it.type == MediaSourceScanner.TYPE_SAF_TREE }.sortedBy { it.label }
        // 授权丢失的 SAF 源只提示重新授权；扫描端同样会跳过，不会抛异常。
        safPermissionLost = saf
            .filterNot { MediaSourceScanner.hasPersistedPermission(context, it.uri) }
            .mapTo(mutableSetOf()) { it.id }
        // 部分授权（Android 14+）下媒体集合不完整，刷新后同步提示态。
        partialMediaAccess = isPartialMediaAccess(context)
        val all = MediaSourceScanner.recommendedSources(context) + saf + shared
        selected = all.associate { source ->
            source.id to (selected[source.id] ?: saved[source.id]?.enabled ?: source.enabled)
        }
        all.forEach { source ->
            val estimate = withContext(Dispatchers.IO) {
                runCatching {
                    MediaSourceScanner.estimate(context, source)
                }.getOrDefault(MediaSourceScanner.Estimate(0, 0))
            }
            estimates = estimates + (source.id to estimate)
        }
        estimating = false
    }

    LaunchedEffect(Unit) {
        EmbeddingModelStore.initialize(context)
        refreshSources()
        refreshIndexMeta()
    }

    // 索引跑在 MediaIndexService 里，本页只观察进度：结束（running 复位）后刷新条目数与上次索引时间。
    LaunchedEffect(progress.running) {
        if (!progress.running) refreshIndexMeta()
    }

    /**
     * 索引构建：交由 [MediaIndexService] 后台执行（前台服务 + 唤醒锁），
     * 不依赖本页协程存活，进度仍由 [MediaIndexer.state] 驱动。
     */
    fun startIndex() {
        indexError = null
        MediaIndexService.start(context)
    }

    /** 确认扫描范围：勾选结果 upsert 进 media_source（enabled 按勾选），然后开始首次索引。 */
    fun confirmAndIndex() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val dao = EtaDatabase.get(context).mediaIndexDao()
                // 推荐项 + SAF + 共享文件夹一并纳入：勾选即 enabled 落库，确认后开始索引。
                val pending = MediaSourceScanner.recommendedSources(context).map { recommended ->
                    (dao.sourceById(recommended.id) ?: recommended)
                        .copy(enabled = selected[recommended.id] ?: recommended.enabled)
                } + sharedSources.map { source ->
                    source.copy(enabled = selected[source.id] ?: source.enabled)
                } + safSources.map { source ->
                    source.copy(enabled = selected[source.id] ?: source.enabled)
                }
                pending.forEach { dao.upsertSource(it) }
            }
            savedSources = withContext(Dispatchers.IO) {
                EtaDatabase.get(context).mediaIndexDao().sources().associateBy { it.id }
            }
            startIndex()
        }
    }

    // 系统媒体库项按需申请权限；无论授予与否都继续确认流程（拒绝后仍可用无限权范围）。
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        partialMediaAccess = isPartialMediaAccess(context)
        confirmAndIndex()
    }

    // 部分授权补充入口：重新发起图片/视频权限申请（Android 14+ 会再次弹出系统照片选择器）。
    val mediaReauthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        partialMediaAccess = isPartialMediaAccess(context)
    }

    fun onConfirmClick() {
        val wantsMediaStore = selected[MediaSourceScanner.TYPE_MEDIASTORE] == true
        val granted = MEDIA_READ_PERMISSIONS.any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (wantsMediaStore && !granted) {
            permissionLauncher.launch(MEDIA_READ_PERMISSIONS)
        } else {
            confirmAndIndex()
        }
    }

    // SAF 目录选择器：pendingSafTargetId 非空 = 重新授权既有源（同 id 换新 tree uri）。
    val safTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val replaceId = pendingSafTargetId
        pendingSafTargetId = null
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    // 逐目录授权持久化：只保留读权限，重启后扫描仍有效。
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    EtaDatabase.get(context).mediaIndexDao().upsertSource(
                        MediaSourceEntity(
                            id = replaceId ?: safSourceId(uri),
                            type = MediaSourceScanner.TYPE_SAF_TREE,
                            uri = uri.toString(),
                            label = safDisplayName(context, uri),
                            enabled = true,
                        ),
                    )
                }
                refreshSources()
            }
        }
    }

    /** 共享文件夹开关：勾选即 enabled 落库，确认/索引时随其他源一起生效。 */
    fun persistSharedSource(mount: SharedFolderMount, enabled: Boolean) {
        val source = sharedFolderSource(context, mount, savedSources[sharedFolderSourceId(mount.name)])
        scope.launch(Dispatchers.IO) {
            EtaDatabase.get(context).mediaIndexDao().upsertSource(source.copy(enabled = enabled))
        }
    }

    /** 移除 SAF 源：释放持久化授权并删源行；已入库条目在下次重建索引时按 stale 清理。 */
    fun removeSafSource(source: MediaSourceEntity) {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(source.uri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                EtaDatabase.get(context).mediaIndexDao().deleteSource(source.id)
            }
            pendingRemoveSource = null
            refreshSources()
        }
    }

    val confirmed = savedSources.values.any { it.enabled }
    // 增量/全量差分判据：当前模型+维度已有索引条目（indexedCount）或曾完成过索引（lastIndexedAt）
    // → 「更新索引」（增量，已索引未变的跳过）；否则「建立索引」（首次全量）。
    val hasIndex = indexedCount > 0 || lastIndexedAt != null
    val modelReady = modelState.ready
    val downloading = modelState.downloading
    // 有 `.part` 说明上次取消/中断过：入口文案改成「继续下载」，点击后会从断点续传。
    var partialModelBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(modelState.downloading, modelState.ready, modelState.model.id, modelState.source.id) {
        partialModelBytes = withContext(Dispatchers.IO) { EmbeddingModelStore.partialBytes(context) }
    }
    // 局部副本：委托属性无法做智能转换（Kotlin 不允许对 by 委托的属性 smart cast）。
    val backend = activeBackend

    EtaScaffoldPage(
        title = stringResource(R.string.media_settings_title),
        onBack = onBack,
    ) {
        // ── 模型区 ──────────────────────────────────────────────────
        item(key = "model-group") {
            EtaSegmentedColumn(
                title = stringResource(R.string.media_model_group),
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                item(key = "model-select") {
                    // 与「向量维度」一致：单行 + 点击弹出锚定菜单，当前模型作为右侧取值展示。
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.media_model_title),
                        summary = modelSummary(modelState.model),
                        items = EmbeddingModelCatalog.models.map { modelTitle(it) },
                        selectedIndex = EmbeddingModelCatalog.models
                            .indexOfFirst { it.id == modelState.model.id }
                            .coerceAtLeast(0),
                        enabled = !downloading,
                        onItemSelected = { index ->
                            EmbeddingModelCatalog.models.getOrNull(index)?.let { model ->
                                EmbeddingModelStore.select(context, model.id, modelState.source.id)
                            }
                        },
                    )
                }
                // 低内存设备：默认模型与推荐不一致，作为模型行的脚注说明（不画卡片底色）。
                item(key = "model-low-memory", visible = lowMemoryDevice, surface = false) {
                    EtaText(
                        text = stringResource(R.string.media_low_memory_note),
                        style = EtaTextStyle.Footnote,
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            top = 4.dp,
                            bottom = 4.dp,
                        ),
                    )
                }
                item(key = "model-dim") {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.media_dim_title),
                        summary = stringResource(R.string.media_dim_summary),
                        items = VECTOR_DIMS.map { it.toString() },
                        selectedIndex = dimIndex,
                        onItemSelected = { index ->
                            if (index in VECTOR_DIMS.indices) {
                                dimIndex = index
                                EmbeddingModelStore.setVectorDim(context, VECTOR_DIMS[index])
                            }
                        },
                    )
                }
                item(key = "model-backend") {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.media_backend_title),
                        // 生效后端只有「初始化失败回退」时才需要提醒，追加在本行副标题里。
                        summary = backendSummary(backend, backendFellBack),
                        items = BACKEND_OPTIONS.map { backendLabel(it) },
                        selectedIndex = backendIndex,
                        onItemSelected = { index ->
                            if (index in BACKEND_OPTIONS.indices) {
                                backendIndex = index
                                EmbeddingModelStore.setBackend(context, BACKEND_OPTIONS[index])
                                // 释放旧引擎：下次索引/检索按新后端重建，生效后端随之更新。
                                scope.launch {
                                    EtaEmbeddingEngine.release()
                                    refreshIndexMeta()
                                }
                            }
                        },
                    )
                }
                item(key = "model-download") {
                    // 下载 / 取消合并为一行：标题随状态切换，模型状态 + 体积作为副标题。
                    EtaClickableRow(
                        title = stringResource(
                            when {
                                downloading -> R.string.media_model_cancel_download
                                partialModelBytes > 0L && !modelState.ready -> R.string.media_model_continue_download
                                else -> R.string.media_model_download
                            },
                        ),
                        summary = modelStatusSummary(modelState, partialModelBytes),
                        onClick = {
                            if (downloading) {
                                EmbeddingModelStore.cancelDownload()
                            } else {
                                EmbeddingModelStore.download(context)
                            }
                        },
                        // 模型校验 / 下载有分母时走确定型，无分母（校验、准备）仍为不定型。
                        leading = if (modelState.checking || modelState.downloading) {
                            {
                                EtaLoadingSlot(
                                    progress = modelState.downloadFraction(),
                                    size = EtaDimens.DenseIconSize,
                                )
                            }
                        } else {
                            null
                        },
                        showChevron = false,
                        enabled = downloading || (!modelState.checking && !modelState.ready),
                    )
                }
            }
        }

        // ── 扫描范围确认区（核心） ─────────────────────────────────
        item(key = "sources-group") {
            EtaSegmentedColumn(
                title = stringResource(R.string.media_sources_group),
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                MediaSourceScanner.recommendedSources(context).forEach { source ->
                    item(key = "source-${source.id}") {
                        EtaSegmentedCheckboxRow(
                            checked = selected[source.id] ?: source.enabled,
                            onCheckedChange = { selected = selected + (source.id to it) },
                            title = sourceTitle(source),
                            summary = sourceSummary(source, estimates[source.id], estimating),
                        )
                    }
                }
                // 部分授权（Android 14+）：媒体集合只含用户选中的照片/视频，给出补充授权入口。
                item(key = "source-mediastore-partial", visible = partialMediaAccess) {
                    EtaClickableRow(
                        title = stringResource(R.string.media_permission_partial_note),
                        onClick = null,
                        showChevron = false,
                        endActions = {
                            EtaTextButton(
                                text = stringResource(R.string.media_permission_grant_more),
                                onClick = {
                                    TouchHaptics.click(view)
                                    mediaReauthLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.READ_MEDIA_IMAGES,
                                            Manifest.permission.READ_MEDIA_VIDEO,
                                        ),
                                    )
                                },
                            )
                        },
                    )
                }
            }
        }

        // 确认按钮不占用分段卡片：独立一行、无底板，靠右放置。
        item(key = "confirm-index") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = EtaSegmentedGroupSpacing,
                    ),
                horizontalArrangement = Arrangement.End,
            ) {
                EtaTextButton(
                    // 与索引区动作共用同一份字符串：尚无索引「建立索引」（首次全量），已有索引「更新索引」（增量）。
                    text = stringResource(
                        if (hasIndex) R.string.media_action_index_now else R.string.media_action_index_build,
                    ),
                    enabled = selected.values.any { it } && !progress.running,
                    onClick = {
                        TouchHaptics.click(view)
                        onConfirmClick()
                    },
                )
            }
        }

        // ── 自选文件夹（SAF）────────────────────────────────────────
        item(key = "saf-group") {
            EtaSegmentedColumn(
                title = stringResource(R.string.media_saf_group),
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                safSources.forEach { source ->
                    item(key = "saf-${source.id}") {
                        val permissionLost = source.id in safPermissionLost
                        EtaClickableRow(
                            title = source.label,
                            summary = if (permissionLost) {
                                stringResource(R.string.media_saf_permission_lost)
                            } else {
                                sourceSummary(source, estimates[source.id], estimating)
                            },
                            onClick = null,
                            showChevron = false,
                            endActions = {
                                if (permissionLost) {
                                    EtaTextButton(
                                        text = stringResource(R.string.media_saf_reauthorize),
                                        onClick = {
                                            TouchHaptics.click(view)
                                            pendingSafTargetId = source.id
                                            safTreeLauncher.launch(null)
                                        },
                                    )
                                }
                                EtaTextButton(
                                    text = stringResource(R.string.media_saf_remove),
                                    onClick = {
                                        TouchHaptics.click(view)
                                        pendingRemoveSource = source
                                    },
                                )
                            },
                        )
                    }
                }
                item(key = "saf-add") {
                    EtaClickableRow(
                        title = stringResource(R.string.media_saf_add),
                        // 空态提示并入这一行的副标题，不再单列一行纯文字。
                        summary = if (safSources.isEmpty()) {
                            stringResource(R.string.media_saf_empty)
                        } else {
                            null
                        },
                        onClick = {
                            pendingSafTargetId = null
                            safTreeLauncher.launch(null)
                        },
                        showChevron = false,
                    )
                }
            }
        }

        // ── Linux 共享文件夹 ────────────────────────────────────────
        item(key = "shared-group") {
            EtaSegmentedColumn(
                title = stringResource(R.string.media_shared_group),
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                item(key = "shared-configure", visible = mounts.isEmpty()) {
                    EtaClickableRow(
                        title = stringResource(R.string.media_shared_configure),
                        // 空态提示并入这一行的副标题，不再单列一行纯文字。
                        summary = stringResource(R.string.media_shared_empty),
                        onClick = { onNavigate(AppRoute.SharedFolders) },
                    )
                }
                mounts.forEach { mount ->
                    item(key = "shared-${mount.name}") {
                        val sourceId = sharedFolderSourceId(mount.name)
                        EtaSegmentedCheckboxRow(
                            checked = selected[sourceId] ?: savedSources[sourceId]?.enabled ?: false,
                            onCheckedChange = { checked ->
                                selected = selected + (sourceId to checked)
                                persistSharedSource(mount, checked)
                            },
                            title = mount.name,
                            summary = sharedFolderSummary(mount, estimates[sourceId], estimating),
                        )
                    }
                }
            }
        }

        // ── 索引模式（全力索引 / 低耗后台）───────────────────────
        item(key = "index-mode-group") {
            EtaSegmentedColumn(
                title = stringResource(R.string.media_index_mode_title),
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                IndexMode.entries.forEach { mode ->
                    item(key = "index-mode-${mode.wire}") {
                        EtaSegmentedRadioRow(
                            title = stringResource(mode.labelRes),
                            summary = stringResource(mode.summaryRes),
                            selected = indexMode == mode,
                            onClick = {
                                indexMode = mode
                                // 切档即时落库生效：下一轮「确认并开始首次索引」/「立即索引 / 重建」按新档跑。
                                EmbeddingModelStore.setIndexMode(context, mode)
                            },
                        )
                    }
                }
            }
        }

        // ── 自动更新（启动后 / 定时）────────────────────────────────
        item(key = "auto-update-group") {
            EtaSegmentedColumn(
                title = stringResource(R.string.media_auto_group),
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                item(key = "auto-on-launch") {
                    EtaSwitchRow(
                        title = stringResource(R.string.media_auto_on_launch),
                        checked = autoIndexOnLaunch,
                        summary = stringResource(R.string.media_auto_on_launch_summary),
                        onCheckedChange = { enabled ->
                            autoIndexOnLaunch = enabled
                            // 即时落库生效：下次应用启动起，自动跑一轮增量索引。
                            MediaIndexAutoUpdate.setAutoIndexOnLaunch(context, enabled)
                        },
                    )
                }
                item(key = "auto-interval") {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.media_auto_interval),
                        summary = stringResource(R.string.media_auto_power_saver_note),
                        items = listOf(
                            stringResource(R.string.media_auto_interval_off),
                            stringResource(R.string.media_auto_interval_6h),
                            stringResource(R.string.media_auto_interval_daily),
                            stringResource(R.string.media_auto_interval_3d),
                            stringResource(R.string.media_auto_interval_weekly),
                        ),
                        selectedIndex = AUTO_INTERVAL_HOURS.indexOf(autoIntervalHours).coerceAtLeast(0),
                        onItemSelected = { index ->
                            if (index in AUTO_INTERVAL_HOURS.indices) {
                                autoIntervalHours = AUTO_INTERVAL_HOURS[index]
                                // 即时落库并重排/取消闹钟，改档立即生效。
                                MediaIndexAutoUpdate.setAutoIndexIntervalHours(context, AUTO_INTERVAL_HOURS[index])
                                MediaIndexAutoUpdate.schedule(context)
                            }
                        },
                    )
                }
            }
        }

        // ── 索引区 ──────────────────────────────────────────────────
        item(key = "index-group") {
            EtaSegmentedColumn(
                title = stringResource(R.string.media_index_group),
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                item(key = "index-now") {
                    EtaClickableRow(
                        // 差分：已有索引 → 「更新索引」（增量）；尚无索引 → 「建立索引」（首次全量）。
                        title = stringResource(
                            if (hasIndex) R.string.media_action_index_now else R.string.media_action_index_build,
                        ),
                        // 进度 / 失败 / 未确认 / 上次构建时间都并入这一行的副标题。
                        summary = when {
                            progress.running -> progressSummary(context, progress)
                            indexError != null -> stringResource(
                                R.string.media_error_index_failed,
                                indexError ?: "",
                            )
                            !confirmed -> stringResource(R.string.media_index_not_confirmed)
                            else -> {
                                val status = lastIndexedAt?.let {
                                    stringResource(R.string.media_status_last_built, formatTime(it))
                                } ?: stringResource(R.string.media_status_never_built)
                                // 已有索引时副标题补一句增量说明，与「更新索引」呼应；首次全量不加。
                                if (hasIndex) {
                                    "$status · ${stringResource(R.string.media_action_index_now_summary)}"
                                } else {
                                    status
                                }
                            }
                        },
                        onClick = { startIndex() },
                        // 索引有真实分母（已处理 / 总数）时走确定型，扫描计数阶段仍为不定型。
                        leading = if (progress.running) {
                            {
                                EtaLoadingSlot(
                                    progress = progress.fraction(),
                                    size = EtaDimens.DenseIconSize,
                                )
                            }
                        } else {
                            null
                        },
                        showChevron = false,
                        enabled = confirmed && modelReady && !progress.running,
                    )
                }
                item(key = "index-clear") {
                    EtaClickableRow(
                        title = stringResource(R.string.media_action_clear_index),
                        onClick = { showClearDialog = true },
                        showChevron = false,
                        enabled = !progress.running && indexedCount > 0,
                        destructive = true,
                    )
                }
            }
        }

        // 条目规模超过暴力检索阈值才提示；暴力点积仍可用，后续版本切换 ANN。
        // 提示走脚注文案，不占分段行，避免和可点行混排。
        if (annSwitchPending) {
            item(key = "index-ann-notice") {
                EtaText(
                    text = stringResource(R.string.media_ann_threshold_note),
                    style = EtaTextStyle.Footnote,
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = EtaSegmentedGroupSpacing,
                    ),
                )
            }
        }

        // ── 去搜索入口 ──────────────────────────────────────────────
        item(key = "open-search") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                item {
                    EtaClickableRow(
                        title = stringResource(R.string.media_action_open_search),
                        summary = stringResource(R.string.media_action_open_search_summary),
                        onClick = { onNavigate(AppRoute.MediaSearch) },
                    )
                }
            }
        }

    }

    if (showClearDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.media_index_clear_title),
            summary = stringResource(R.string.media_index_clear_summary),
            onDismissRequest = { showClearDialog = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.media_action_clear_index),
                    onCancel = { showClearDialog = false },
                    onConfirm = {
                        showClearDialog = false
                        scope.launch {
                            MediaIndexer.clear(context)
                            refreshIndexMeta()
                        }
                    },
                    destructive = true,
                )
            },
        )
    }

    pendingRemoveSource?.let { source ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.media_saf_remove_title),
            summary = stringResource(R.string.media_saf_remove_summary, source.label),
            onDismissRequest = { pendingRemoveSource = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.media_saf_remove),
                    onCancel = { pendingRemoveSource = null },
                    onConfirm = { removeSafSource(source) },
                    destructive = true,
                )
            },
        )
    }
}

/** 模型变体展示名：全模态 740M（默认）/ 纯文本 270M。 */
@Composable
private fun modelTitle(model: EmbeddingModelCatalog.Model): String = when (model.id) {
    EmbeddingModelCatalog.OMNIMODAL_ID -> stringResource(R.string.media_model_omnimodal)
    EmbeddingModelCatalog.TEXT_ONLY_ID -> stringResource(R.string.media_model_text_only)
    else -> model.id
}

@Composable
private fun modelSummary(model: EmbeddingModelCatalog.Model): String = when (model.id) {
    EmbeddingModelCatalog.OMNIMODAL_ID -> stringResource(R.string.media_model_omnimodal_summary)
    EmbeddingModelCatalog.TEXT_ONLY_ID -> stringResource(R.string.media_model_text_only_summary)
    else -> ""
}

/**
 * 下载行的副标题：模型状态 + 体积。
 *
 * 状态卡删掉后，模型是否就绪的信息并入这一行，不再单列一块只读区域。
 */
@Composable
private fun modelStatusSummary(state: EmbeddingModelStore.State, partialBytes: Long = 0L): String {
    val total = formatBytes(state.totalBytes)
    val status = when {
        state.checking -> stringResource(R.string.media_model_checking)
        state.downloading -> stringResource(
            R.string.media_model_downloading,
            formatBytes(state.downloadedBytes),
            total,
            percentOf(state.downloadedBytes, state.totalBytes),
        )
        state.ready -> stringResource(R.string.media_model_ready)
        state.error -> stringResource(R.string.media_model_error)
        // 取消/中断留下的 `.part`：副标题给出断点位置，入口标题同时变成「继续下载」。
        partialBytes > 0L -> stringResource(
            R.string.media_model_partial_summary,
            percentOf(partialBytes, state.totalBytes),
            formatBytes(partialBytes),
            total,
        )
        else -> stringResource(R.string.media_model_not_downloaded)
    }
    // 下载中/有断点的状态里已经带了进度数字，不再重复体积。
    val showsProgress = state.downloading || (partialBytes > 0L && !state.ready)
    return if (showsProgress) status else "$status · $total"
}

private fun percentOf(bytes: Long, total: Long): Int =
    if (total <= 0L) 0 else ((bytes * 100L) / total).coerceIn(0L, 100L).toInt()

/**
 * 推理后端行的副标题：默认说明 + 回退提示。
 *
 * 「尚未初始化」是首次索引前的常态，不提示；只有初始化失败真的回退了才补一句。
 */
@Composable
private fun backendSummary(backend: InferenceBackend?, fellBack: Boolean): String {
    val base = stringResource(R.string.media_backend_summary)
    val note = if (fellBack && backend != null) {
        stringResource(R.string.media_backend_active_fallback, backendLabel(backend))
    } else {
        null
    }
    return listOfNotNull(base, note).joinToString(" · ")
}

@Composable
private fun backendLabel(backend: InferenceBackend): String = when (backend) {
    InferenceBackend.AUTO -> stringResource(R.string.media_backend_auto)
    InferenceBackend.CPU -> stringResource(R.string.media_backend_cpu)
    InferenceBackend.GPU -> stringResource(R.string.media_backend_gpu)
    InferenceBackend.NPU -> stringResource(R.string.media_backend_npu)
}

/**
 * 部分授权判定：Android 14+ 用户只选了部分照片/视频时 `READ_MEDIA_VISUAL_USER_SELECTED`
 * 已授但图片/视频未全授，此时媒体集合不完整（§17 风险 7），需在 UI 说明并支持补充授权。
 */
private fun isPartialMediaAccess(context: Context): Boolean {
    fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
        !(granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO))
}

@Composable
private fun sourceTitle(source: MediaSourceEntity): String = when (source.type) {
    MediaSourceScanner.TYPE_MEDIASTORE -> stringResource(R.string.media_source_mediastore)
    MediaSourceScanner.TYPE_ATTACHMENT_CACHE -> stringResource(R.string.media_source_attachment_cache)
    MediaSourceScanner.TYPE_WORKSPACE -> stringResource(R.string.media_source_workspace)
    else -> source.label
}

@Composable
private fun sourceSummary(
    source: MediaSourceEntity,
    estimate: MediaSourceScanner.Estimate?,
    estimating: Boolean,
): String {
    val estimateText = when {
        estimating -> stringResource(R.string.media_source_estimate_counting)
        estimate != null -> stringResource(
            R.string.media_source_estimate,
            estimate.count,
            formatBytes(estimate.bytes),
        )
        else -> ""
    }
    val note = when (source.type) {
        MediaSourceScanner.TYPE_MEDIASTORE -> stringResource(R.string.media_source_mediastore_summary)
        MediaSourceScanner.TYPE_ATTACHMENT_CACHE -> stringResource(R.string.media_source_attachment_cache_summary)
        MediaSourceScanner.TYPE_WORKSPACE -> stringResource(R.string.media_source_workspace_summary)
        else -> ""
    }
    return if (note.isEmpty()) estimateText else "$estimateText · $note"
}

/** 索引进度：已处理 / 总数；两者未就绪（扫描计数阶段）时为 null，保持不定型。 */
private fun MediaIndexer.Progress.fraction(): Float? =
    if (total > 0) (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null

/** 模型下载进度：已下载 / 总字节；不在下载或总字节未知时为 null，走不定型。 */
private fun EmbeddingModelStore.State.downloadFraction(): Float? =
    if (downloading && totalBytes > 0L) {
        (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    } else {
        null
    }

private fun progressSummary(context: Context, progress: MediaIndexer.Progress): String {
    val phase = when (progress.phase) {
        "scanning" -> context.getString(R.string.media_phase_scanning)
        else -> context.getString(R.string.media_phase_indexing)
    }
    return "$phase · " + context.getString(
        R.string.media_index_progress,
        progress.processed,
        progress.total,
        progress.indexed,
        progress.skipped,
        progress.failed,
        progress.removed,
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(Locale.getDefault(), bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(Locale.getDefault(), bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.1f KB".format(Locale.getDefault(), bytes / 1_000.0)
    else -> "$bytes B"
}

// formatter 提为单例：设置页状态行每次重组都调 formatTime，现场 new 纯浪费（主线程专用）。
private val MediaIndexTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTime(millis: Long): String = MediaIndexTimeFormat.format(Date(millis))

/** 共享文件夹扫描源实体 id：type 前缀 + 挂载名（挂载名在 SharedFolderMounts 内唯一）。 */
private fun sharedFolderSourceId(name: String): String =
    "${MediaSourceScanner.TYPE_SHARED_FOLDER}:$name"

/**
 * 共享文件夹挂载 → 扫描源实体：uri 取 Android 侧真实目录（[SharedFolderMount.sourcePath]），
 * label 按当前语言生成；已有源行保留 lastScanAt / itemCount。
 */
private fun sharedFolderSource(
    context: Context,
    mount: SharedFolderMount,
    saved: MediaSourceEntity?,
): MediaSourceEntity {
    val id = sharedFolderSourceId(mount.name)
    return (saved ?: MediaSourceEntity(
        id = id,
        type = MediaSourceScanner.TYPE_SHARED_FOLDER,
        uri = mount.sourcePath,
        label = "",
        enabled = false,
    )).copy(
        uri = mount.sourcePath,
        label = context.getString(R.string.media_shared_source_label, mount.name),
    )
}

/** SAF 源实体 id：type 前缀 + tree URI 的 SHA-1 前 12 位十六进制，跨重启稳定。 */
private fun safSourceId(treeUri: Uri): String =
    "${MediaSourceScanner.TYPE_SAF_TREE}:" + MessageDigest.getInstance("SHA-1")
        .digest(treeUri.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(12)

/** SAF 文件夹展示名：优先取根文档 DISPLAY_NAME，回落 tree URI 末段。 */
private fun safDisplayName(context: Context, treeUri: Uri): String {
    val name = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).orEmpty().takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()
    return name
        ?: treeUri.lastPathSegment?.substringAfterLast(':')?.takeIf { it.isNotBlank() }
        ?: treeUri.toString()
}

@Composable
private fun sharedFolderSummary(
    mount: SharedFolderMount,
    estimate: MediaSourceScanner.Estimate?,
    estimating: Boolean,
): String {
    val estimateText = when {
        estimating -> stringResource(R.string.media_source_estimate_counting)
        estimate != null -> stringResource(
            R.string.media_source_estimate,
            estimate.count,
            formatBytes(estimate.bytes),
        )
        else -> ""
    }
    return if (estimateText.isEmpty()) mount.sourcePath else "${mount.sourcePath} · $estimateText"
}
