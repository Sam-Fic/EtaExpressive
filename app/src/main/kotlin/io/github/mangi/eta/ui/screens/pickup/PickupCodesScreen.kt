// 取餐码记录管理页（整屏子页 = 独立路由 AppRoute.PickupCodes）。
// 列表与设置分组走本仓 M3 分段列表口径（EtaSegmentedColumn）；
// 数据读写 PickupCodeRepository，设置读写 PickupCodeSettings。
// 记录行交互：整行点击 = 编辑弹窗（可编辑值收进弹窗，行尾保留箭头暗示可点）、
// 行尾对勾 = 标记完成（仅待取行）；长按进入多选，删除只走多选批量操作栏
// （共享套件 EtaSelectionKit，破坏性动作 error 色 + 二次确认弹窗），行内不放删除钮。
// 「已完成」组标题行右侧挂一键清空钮（破坏性 error 色 + 确认弹窗，多选态隐藏）。

package io.github.mangi.eta.ui.screens.pickup

import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.capture.AssistantProjectionService
import io.github.mangi.eta.agent.pickup.PickupCodeSettings
import io.github.mangi.eta.data.repository.PickupCodeRecord
import io.github.mangi.eta.data.repository.PickupCodeRepository
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSelectionAction
import io.github.mangi.eta.ui.adaptive.EtaSelectionBar
import io.github.mangi.eta.ui.adaptive.EtaSelectionBarOverlay
import io.github.mangi.eta.ui.adaptive.EtaSelectionBottomSpacer
import io.github.mangi.eta.ui.adaptive.EtaSelectionTopBarAction
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PickupCodesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current

    var pending by remember { mutableStateOf<List<PickupCodeRecord>>(emptyList()) }
    var completed by remember { mutableStateOf<List<PickupCodeRecord>>(emptyList()) }
    var interceptionEnabled by remember { mutableStateOf(true) }
    var localOcrOnly by remember { mutableStateOf(false) }

    // 多选态与勾选集合：入口在顶栏（Checklist→Close 原位切换），长按行也可进入。
    // 批量完成/删除逐条走既有 markCompleted()/delete()，不新建批量 API（技能页同口径）。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var editingRecord by remember { mutableStateOf<PickupCodeRecord?>(null) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showClearCompletedDialog by remember { mutableStateOf(false) }

    val selectableRecords = remember(pending, completed) { pending + completed }
    val allSelected = selectableRecords.isNotEmpty() &&
        selectableRecords.all { it.id in selectedIds }
    // 批量完成只对待取记录有意义：勾选集里没有待取行时禁用（删除资格分离同款口径）。
    val selectedPendingCount = selectableRecords.count { it.id in selectedIds && !it.isCompleted }

    // 返回键优先退出多选，不退页面（技能页同款）。
    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectionMode,
        onBackCompleted = {
            selectionMode = false
            selectedIds = emptySet()
        },
    )

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun reloadRecords() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val repository = PickupCodeRepository(context.applicationContext)
                pending = repository.pending()
                completed = repository.completed()
            }
        }
    }

    fun reloadSettings() {
        PickupCodeSettings.load(context)
        interceptionEnabled = PickupCodeSettings.interceptionEnabled
        localOcrOnly = PickupCodeSettings.localOcrOnly
    }

    LaunchedEffect(Unit) {
        reloadRecords()
        reloadSettings()
    }

    // 共享屏幕授权：系统弹窗回传 resultData 后转交单次截屏前台服务，
    // 截到的帧走本地 OCR + 规则引擎识别并入库（管理页内的手动截屏识别通道）。
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            AssistantProjectionService.clearResult()
            AssistantProjectionService.start(context, result.resultCode, result.data!!)
            scope.launch {
                // 有界等待首帧：前台服务自带 3s 超时兜底会发 null，这里再封顶一层。
                val bitmap = withTimeoutOrNull(PROJECTION_RESULT_TIMEOUT_MS) {
                    AssistantProjectionService.resultFlow().filterNotNull().first()
                }
                AssistantProjectionService.clearResult()
                if (bitmap == null) {
                    toast(context, R.string.pickup_projection_failed)
                    return@launch
                }
                val drafts = withContext(Dispatchers.IO) {
                    runCatching {
                        io.github.mangi.eta.agent.pickup.PickupCodeExtractor.fromBitmap(
                            context.applicationContext,
                            bitmap,
                        )
                    }.getOrDefault(emptyList())
                }
                bitmap.recycle()
                if (drafts.isEmpty()) {
                    toast(context, R.string.pickup_projection_none)
                } else {
                    withContext(Dispatchers.IO) {
                        val repository = PickupCodeRepository(context.applicationContext)
                        drafts.forEach { draft ->
                            if (repository.findUnfinishedByCode(draft.code) == null) {
                                repository.insert(
                                    code = draft.code,
                                    orderType = draft.orderType,
                                    brandName = draft.brandName,
                                    pickupLocation = draft.pickupLocation,
                                    source = io.github.mangi.eta.data.repository.PickupCodeSource.AGENT,
                                    sourcePackage = context.packageName,
                                    fullText = draft.fullText,
                                )
                            }
                        }
                    }
                    toast(
                        context,
                        R.string.pickup_projection_done,
                        drafts.size,
                    )
                    reloadRecords()
                }
            }
        }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.settings_pickup_codes_title),
        onBack = onBack,
        actions = {
            // 多选入口原位切换：Checklist→Close，退出并清空勾选。无记录可管时禁用。
            EtaSelectionTopBarAction(
                selectionMode = selectionMode,
                onToggle = {
                    TouchHaptics.click(view)
                    if (selectionMode) exitSelection() else selectionMode = true
                },
                enabled = selectableRecords.isNotEmpty() || selectionMode,
            )
        },
    ) {
        // ── 提醒与识别 ──────────────────────────────────────────
        item(key = "settings") {
            EtaSegmentedColumn(
                modifier = pickupGroupModifier(),
                title = stringResource(R.string.pickup_group_notification),
            ) {
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.pickup_interception_title),
                        checked = interceptionEnabled,
                        onCheckedChange = { enabled ->
                            PickupCodeSettings.setInterceptionEnabled(context, enabled)
                            interceptionEnabled = enabled
                        },
                        summary = stringResource(R.string.pickup_interception_summary),
                    )
                }
                item {
                    // 纯本地识别：双态摘要（开=本地 OCR 直接入库不经模型；关=模型确认保存）。
                    EtaSwitchRow(
                        title = stringResource(R.string.pickup_local_ocr_only_title),
                        checked = localOcrOnly,
                        onCheckedChange = { enabled ->
                            PickupCodeSettings.setLocalOcrOnly(context, enabled)
                            localOcrOnly = enabled
                        },
                        summary = stringResource(R.string.pickup_local_ocr_only_summary),
                        summaryOff = stringResource(R.string.pickup_local_ocr_only_summary_off),
                    )
                }
                item {
                    // 手动「截屏识别」：直接发起共享屏幕授权 + 单帧 OCR，不依赖
                    // 「屏幕截图方式」设置（该设置在系统接管页，管助手兜底链路）。
                    EtaClickableRow(
                        title = stringResource(R.string.pickup_projection_grant_title),
                        summary = stringResource(R.string.pickup_projection_grant_summary),
                        onClick = {
                            val manager = context.getSystemService(
                                MediaProjectionManager::class.java,
                            )
                            manager?.createScreenCaptureIntent()?.let { intent ->
                                projectionLauncher.launch(intent)
                            }
                        },
                    )
                }
            }
        }

        // ── 待取记录 ────────────────────────────────────────────
        // 记录行升格为顶层 LazyColumn item（ManageChatsScreen 模式）：官方 animateItem
        // 承载行删除淡出与跨组换位弹簧；分段外观由 EtaSegmentedItem 逐行注入，组内
        // 行距走官方分段缝隙（EtaSegmentedGap）。
        if (pending.isNotEmpty() || !selectionMode) {
            item(key = "pending_header") {
                // 组标题独立成 item（多选态把「已选 n 项」并入标题，点号隔开，不另立计数行）。
                Text(
                    text = if (selectionMode) {
                        stringResource(R.string.pickup_group_pending, pending.size) + " · " +
                            stringResource(R.string.media_selected_count, selectedIds.size)
                    } else {
                        stringResource(R.string.pickup_group_pending, pending.size)
                    },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        top = EtaSegmentedGroupSpacing,
                        bottom = EtaSegmentedTitleSpacing,
                    ),
                )
            }
            if (pending.isEmpty()) {
                // 空态说明不是卡片行：不参与分段圆角，直接落页面背景。
                item(key = "pending_empty") {
                    ListEmptyState(
                        title = stringResource(R.string.pickup_empty_title),
                        summary = stringResource(R.string.pickup_empty_summary),
                    )
                }
            }
            itemsIndexed(
                items = pending,
                key = { _, record -> record.id },
                contentType = { _, _ -> "pickup_record" },
            ) { index, record ->
                PickupRecordRow(
                    record = record,
                    segmentIndex = index,
                    segmentCount = pending.size,
                    selectionMode = selectionMode,
                    checked = record.id in selectedIds,
                    onToggleChecked = {
                        selectedIds = if (record.id in selectedIds) {
                            selectedIds - record.id
                        } else {
                            selectedIds + record.id
                        }
                    },
                    onEnterSelection = {
                        if (!selectionMode) {
                            selectionMode = true
                            selectedIds = setOf(record.id)
                        }
                    },
                    onEdit = { editingRecord = record },
                    onMarkCompleted = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                PickupCodeRepository(context.applicationContext)
                                    .markCompleted(record.id)
                            }
                            reloadRecords()
                        }
                    },
                    modifier = Modifier
                        // 官方 animateItem（会话管理页同款口径）：删除淡出走 Effects、
                        // 换位（含标记完成跨组移动）弹簧走 Spatial；入场不播动画，
                        // 避免首屏全列表闪淡入。
                        .animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        )
                        .padding(horizontal = EtaGroupHorizontalPadding),
                )
            }
        }

        // ── 已完成 ──────────────────────────────────────────────
        if (completed.isNotEmpty()) {
            item(key = "completed_header") {
                // 标题行自绘（供应商模型页计数行同款）：右侧挂一键清空钮——破坏性
                // error 色 + Tooltip（套件破坏性动作同款口径）；多选态隐藏，批量
                // 操作交给底部药丸。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            top = EtaSegmentedGroupSpacing,
                            bottom = EtaSegmentedTitleSpacing,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (selectionMode) {
                            stringResource(R.string.pickup_group_completed, completed.size) +
                                " · " +
                                stringResource(R.string.media_selected_count, selectedIds.size)
                        } else {
                            stringResource(R.string.pickup_group_completed, completed.size)
                        },
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (!selectionMode) {
                        val clearLabel = stringResource(R.string.pickup_clear_completed_action)
                        EtaTooltip(text = clearLabel) {
                            IconButton(
                                onClick = {
                                    TouchHaptics.click(view)
                                    showClearCompletedDialog = true
                                },
                                enabled = completed.isNotEmpty(),
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteSweep,
                                    contentDescription = clearLabel,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                                )
                            }
                        }
                    }
                }
            }
            itemsIndexed(
                items = completed,
                key = { _, record -> record.id },
                contentType = { _, _ -> "pickup_record" },
            ) { index, record ->
                PickupRecordRow(
                    record = record,
                    segmentIndex = index,
                    segmentCount = completed.size,
                    selectionMode = selectionMode,
                    checked = record.id in selectedIds,
                    onToggleChecked = {
                        selectedIds = if (record.id in selectedIds) {
                            selectedIds - record.id
                        } else {
                            selectedIds + record.id
                        }
                    },
                    onEnterSelection = {
                        if (!selectionMode) {
                            selectionMode = true
                            selectedIds = setOf(record.id)
                        }
                    },
                    onEdit = { editingRecord = record },
                    // 已完成行没有「标记完成」动作，行尾不放对勾。
                    onMarkCompleted = {},
                    modifier = Modifier
                        // 待取→已完成的跨组移动是同 key 换位：弹簧滑到新位置，而非闪现。
                        .animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        )
                        .padding(horizontal = EtaGroupHorizontalPadding),
                )
            }
        }

        item(key = "bottom_spacer", contentType = "spacer") {
            // 多选操作栏悬浮在底部时预留高度，避免遮挡最后一个列表项。
            EtaSelectionBottomSpacer(isSelecting = selectionMode)
        }
    }

    // 批量操作栏：共享多选套件的官方 M3E 浮动药丸，底部居中悬浮。
    // 动作序：全选（全不选）→ 批量标记完成 → 删除（error 色标破坏性，执行前另有确认弹窗）。
    Box(modifier = Modifier.fillMaxSize()) {
        EtaSelectionBarOverlay(visible = selectionMode) {
            EtaSelectionBar(
                selectedCount = selectedIds.size,
                allSelected = allSelected,
                onSelectAll = {
                    TouchHaptics.click(view)
                    selectedIds = if (allSelected) {
                        emptySet()
                    } else {
                        selectableRecords.map { it.id }.toSet()
                    }
                },
                actions = listOf(
                    // 批量标记完成：勾选集里没有待取行时禁用（已完成行无可完成）。
                    EtaSelectionAction(
                        icon = Icons.Rounded.DoneAll,
                        contentDescription = stringResource(R.string.pickup_action_mark_done),
                        onClick = {
                            TouchHaptics.click(view)
                            // 同步捕获勾选集再清空：协程读委托属性会拿到 exitSelection 后的空集。
                            val targets = selectedIds.toList()
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val repository = PickupCodeRepository(context.applicationContext)
                                    targets.forEach { repository.markCompleted(it) }
                                }
                                reloadRecords()
                            }
                            exitSelection()
                        },
                        enabled = selectedPendingCount > 0,
                    ),
                    // 删除是破坏性操作：error 色图标区分；执行前另有确认弹窗。
                    EtaSelectionAction(
                        icon = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        onClick = {
                            TouchHaptics.click(view)
                            showBatchDeleteDialog = true
                        },
                        destructive = true,
                    ),
                ),
            )
        }
    }

    // 批量删除二次确认：逐条走既有 delete()，不新建批量 API（技能页同口径）。
    if (showBatchDeleteDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.pickup_batch_delete_title),
            summary = stringResource(R.string.pickup_batch_delete_message, selectedIds.size),
            onDismissRequest = { showBatchDeleteDialog = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { showBatchDeleteDialog = false },
                    onConfirm = {
                        showBatchDeleteDialog = false
                        // 同步捕获勾选集再清空：协程读委托属性会拿到 exitSelection 后的空集。
                        val targets = selectedIds.toList()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                val repository = PickupCodeRepository(context.applicationContext)
                                targets.forEach { repository.delete(it) }
                            }
                            reloadRecords()
                        }
                        exitSelection()
                    },
                )
            },
        )
    }

    // 一键清空已完成二次确认：待取记录不动。
    if (showClearCompletedDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.pickup_clear_completed_title),
            summary = stringResource(R.string.pickup_clear_completed_message, completed.size),
            onDismissRequest = { showClearCompletedDialog = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { showClearCompletedDialog = false },
                    onConfirm = {
                        showClearCompletedDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                PickupCodeRepository(context.applicationContext).clearCompleted()
                            }
                            reloadRecords()
                        }
                    },
                )
            },
        )
    }

    // 整行点击进入的编辑弹窗：可编辑值收进弹窗（设置页行形态约定）。
    editingRecord?.let { record ->
        PickupEditDialog(
            record = record,
            onDismiss = { editingRecord = null },
            onConfirm = { code, orderType, brandName, pickupLocation ->
                val id = record.id
                scope.launch {
                    withContext(Dispatchers.IO) {
                        PickupCodeRepository(context.applicationContext).update(
                            id = id,
                            code = code,
                            orderType = orderType,
                            brandName = brandName.ifBlank { null },
                            pickupLocation = pickupLocation.ifBlank { null },
                        )
                    }
                    reloadRecords()
                }
                editingRecord = null
            },
        )
    }
}

/**
 * 单条取餐码记录行：顶层 LazyColumn item 内容（会话管理页 ManageChatRow 的同构外壳）
 * ——[EtaSegmentedItem] 按组内序注入分段形状，组内行距走官方分段缝隙（末行不补）。
 * [modifier] 由调用方挂 animateItem + 页边距。
 *
 * 常态走 [EtaClickableRow]（整行点击 = 编辑、待取行尾对勾 = 标记完成、长按进入多选）；
 * 多选态走官方 checked 变体 [EtaSegmentedRow]（整行点按 = 勾选切换，行尾只剩官方 Checkbox）。
 */
@Composable
private fun PickupRecordRow(
    record: PickupCodeRecord,
    segmentIndex: Int,
    segmentCount: Int,
    selectionMode: Boolean,
    checked: Boolean,
    onToggleChecked: () -> Unit,
    onEnterSelection: () -> Unit,
    onEdit: () -> Unit,
    onMarkCompleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val createdText = remember(record.createdAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(record.createdAt))
    }
    val meta = listOfNotNull(
        record.brandName,
        record.orderType,
        createdText,
    ).joinToString(" · ")
    EtaSegmentedItem(index = segmentIndex, count = segmentCount) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(
                    bottom = if (segmentIndex == segmentCount - 1) 0.dp else EtaSegmentedGap,
                ),
        ) {
            if (selectionMode) {
                // 多选态：行尾动作让位，整行点按都是勾选切换（技能页同款）。
                EtaSegmentedRow(
                    modifier = Modifier.fillMaxWidth(),
                    checked = checked,
                    onCheckedChange = { onToggleChecked() },
                    leadingContent = { PreferenceIcon(icon = Icons.AutoMirrored.Rounded.ReceiptLong) },
                    content = {
                        Text(text = record.code, style = MaterialTheme.typography.bodyLarge)
                    },
                    supportingContent = {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Checkbox(checked = checked, onCheckedChange = { onToggleChecked() })
                    },
                )
            } else {
                EtaClickableRow(
                    title = record.code,
                    summary = meta.ifBlank { null },
                    // 行点击 = 编辑记录（弹窗收编辑值）；保留行尾箭头暗示整行可点（作者拍板，
                    // 覆盖「瞬时动作不挂箭头」的默认判据）。
                    onClick = onEdit,
                    // 长按进入多选：删除只走批量操作栏，行内不放删除钮。
                    onLongClick = onEnterSelection,
                    onLongClickLabel = stringResource(R.string.action_enter_selection),
                    leading = { PreferenceIcon(icon = Icons.AutoMirrored.Rounded.ReceiptLong) },
                    endActions = {
                        if (!record.isCompleted) {
                            IconButton(
                                onClick = onMarkCompleted,
                                // 容器型按钮直用必须传官方 shapes（RULES §2.5，M3E 按压 morph）。
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = context.getString(R.string.pickup_action_mark_done),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * 记录编辑弹窗：码值（必填，空则确认禁用）、品牌、取餐位置三个官方 [OutlinedTextField]，
 * 类型用官方 [FilterChip] 三选一。
 */
@Composable
private fun PickupEditDialog(
    record: PickupCodeRecord,
    onDismiss: () -> Unit,
    onConfirm: (code: String, orderType: String, brandName: String, pickupLocation: String) -> Unit,
) {
    // 初始值随编辑目标重建：remember 锚定记录 id，换行编辑自动重置表单。
    var codeInput by remember(record.id) { mutableStateOf(record.code) }
    var brandInput by remember(record.id) { mutableStateOf(record.brandName.orEmpty()) }
    var locationInput by remember(record.id) { mutableStateOf(record.pickupLocation.orEmpty()) }
    var typeIndex by remember(record.id) {
        mutableStateOf(PickupOrderTypes.indexOf(record.orderType).coerceAtLeast(0))
    }

    EtaDialog(
        show = true,
        title = stringResource(R.string.pickup_edit_title),
        onDismissRequest = onDismiss,
        actions = {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_save),
                confirmEnabled = codeInput.isNotBlank(),
                onCancel = onDismiss,
                onConfirm = {
                    onConfirm(
                        codeInput.trim(),
                        PickupOrderTypes[typeIndex],
                        brandInput.trim(),
                        locationInput.trim(),
                    )
                },
            )
        },
        content = {
            val codeLabel = stringResource(R.string.pickup_edit_code_label)
            OutlinedTextField(
                value = codeInput,
                onValueChange = { codeInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = codeLabel) },
                singleLine = true,
            )
            val brandLabel = stringResource(R.string.pickup_edit_brand_label)
            OutlinedTextField(
                value = brandInput,
                onValueChange = { brandInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = brandLabel) },
                singleLine = true,
            )
            val locationLabel = stringResource(R.string.pickup_edit_location_label)
            OutlinedTextField(
                value = locationInput,
                onValueChange = { locationInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = locationLabel) },
                singleLine = true,
            )
            Text(
                text = stringResource(R.string.pickup_edit_type_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PickupOrderTypes.forEachIndexed { index, type ->
                    FilterChip(
                        selected = typeIndex == index,
                        onClick = { typeIndex = index },
                        label = { Text(text = type) },
                    )
                }
            }
        },
    )
}

/**
 * 编辑弹窗的类型选项：与规则引擎落库的规格值一致（[SimpleRuleCategory].resultType 与
 * 两步引擎 pageType，即「餐食/饮品/快递」），是数据库数据值不是 UI 文案，不做本地化——
 * 与记录行的 meta 展示口径相同。
 */
private val PickupOrderTypes = listOf("餐食", "饮品", "快递")

/** 分段列表分组的统一页边距（引用项目自适应令牌，不手写数值）；组间距由各 header 的 top 承担。 */
private fun pickupGroupModifier(): Modifier = Modifier.padding(
    start = EtaGroupHorizontalPadding,
    end = EtaGroupHorizontalPadding,
)

private fun toast(context: android.content.Context, resId: Int, arg: Int? = null) {
    val text = arg?.let { context.getString(resId, it) } ?: context.getString(resId)
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** 共享屏幕截屏结果的有界等待上限（服务侧 3s 超时 + 网络余量）。 */
private const val PROJECTION_RESULT_TIMEOUT_MS = 8_000L
