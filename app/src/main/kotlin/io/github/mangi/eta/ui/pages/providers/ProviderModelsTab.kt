@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.pages.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaFloatingToolbarConcentricPadding
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaPickerSheet
import io.github.mangi.eta.ui.adaptive.EtaSectionTitle
import io.github.mangi.eta.ui.adaptive.EtaSegmentedCheckboxRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val modelSearchSeparators = Regex("""[^\p{L}\p{N}]+""")
internal fun filterProviderModels(models: List<Model>, query: String): List<Model> {
    val queryTokens = query.lowercase().split(modelSearchSeparators).filter(String::isNotBlank)
    return models
        .sortedBy { it.sortOrder }
        .filter { model ->
            if (queryTokens.isEmpty()) {
                true
            } else {
                val searchableFields = listOf(model.displayName, model.modelId).map { field ->
                    field.lowercase().filter(Char::isLetterOrDigit)
                }
                queryTokens.all { token ->
                    searchableFields.any { field -> field.containsCharactersInOrder(token) }
                }
            }
        }
}

private fun String.containsCharactersInOrder(query: String): Boolean {
    var queryIndex = 0
    for (character in this) {
        if (character == query[queryIndex]) {
            queryIndex++
            if (queryIndex == query.length) return true
        }
    }
    return false
}

@Composable
internal fun ProviderModelsTab(
    provider: ProviderSetting,
    scope: CoroutineScope,
    scrollConnection: NestedScrollConnection,
    contentSidePadding: Dp,
    currentModelId: String? = null,
    onSelectCurrent: ((String) -> Unit)? = null,
    // 编辑 / 新建模型改走整屏路由（AppRoute.ModelProviderModelEdit，RULES §3.3），
    // 由宿主接线 pushRoute；缺省 null 时（理论上门面之外无此用法）入口静默不响应。
    onEditModel: ((String, Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val storedModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    val selectedModelId = currentModelId?.takeIf { it.isNotBlank() } ?: storedModelId
    var isFetching by remember { mutableStateOf(false) }
    var isMutatingModel by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var selectionMode by remember(provider.id) { mutableStateOf(false) }
    var selectedModelIds by remember(provider.id) { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var modelSearchQuery by remember(provider.id) { mutableStateOf("") }
    var remoteCandidates by remember(provider.id) { mutableStateOf<List<Model>?>(null) }
    val normalizedModelSearchQuery = modelSearchQuery.trim()
    val filteredModels = remember(provider.models, modelSearchQuery) {
        filterProviderModels(provider.models, modelSearchQuery)
    }
    // 「全选/全不选」以当前搜索过滤后的列表为全集（沿用原全选按钮的语义）。
    // id 集合按 filteredModels 记忆：模型列表可达几百条，每次重组都 map 一遍纯浪费。
    val filteredModelIds = remember(filteredModels) { filteredModels.mapTo(mutableSetOf()) { it.id } }
    val allModelsSelected = filteredModels.isNotEmpty() &&
        selectedModelIds.containsAll(filteredModelIds)

    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectionMode,
        onBackCompleted = {
            selectionMode = false
            selectedModelIds = emptySet()
        },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollConnection),
            contentPadding = PaddingValues(
                start = contentSidePadding,
                end = contentSidePadding,
            ),
            overscrollEffect = null,
        ) {
            item(key = "actions", contentType = "section") {
                // KernelSU 式分段列：管理行独立成段（2dp 缝隙），消息提示落在
                // 同组的容器段里（对应参考实现的 SegmentedItemContainer）。
                EtaSegmentedColumn(
                    modifier = ProviderSectionGroupPadding,
                    title = stringResource(R.string.ui_model_management_183414),
                ) {
                    item {
                    ProviderClickableRow(
                        title = if (isFetching) context.getString(R.string.page_retrieving_a880c9) else context.getString(R.string.page_automatically_pull_from_remote_f883d0),
                        summary = stringResource(R.string.provider_models_endpoint_summary, provider.baseUrl),
                        enabled = !isFetching && !isMutatingModel && !SpeechSynthesisModels.isSpeechOnlyProvider(provider),
                        showChevron = false,
                        leading = {
                            Icon(imageVector = Icons.Rounded.CloudDownload, contentDescription = null)
                        },
                        onClick = {
                            scope.launch {
                                isFetching = true
                                message = null
                                try {
                                    val fetchedCatalog = RemoteModelFetcher.fetchCatalog(provider).getOrElse { throwable ->
                                        message = context.getString(
                                            R.string.provider_error,
                                            throwable.message ?: throwable.javaClass.simpleName,
                                        )
                                        return@launch
                                    }
                                    if (ProviderAuthModes.isOAuth(provider.authMode)) {
                                        val syncResult = ModelRepository.syncRemoteCatalog(fetchedCatalog)
                                        if (syncResult.applied) {
                                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                        }
                                        message = context.getString(
                                            R.string.provider_models_sync_summary,
                                            syncResult.fetchedCount,
                                            syncResult.removedCount,
                                        )
                                        return@launch
                                    }
                                    val models = fetchedCatalog.models
                                    val chatModels = models.filter(RemoteModelFetcher::isChatCapableModel)
                                    val syncResult = ModelRepository.syncRemoteCatalog(fetchedCatalog, emptySet())
                                    if (syncResult.applied) {
                                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                    }
                                    val syncMessage = if (syncResult.applied) {
                                        context.getString(
                                            R.string.provider_models_sync_summary,
                                            syncResult.fetchedCount,
                                            syncResult.removedCount,
                                        )
                                    } else null
                                    val existingKeys = ModelRepository.modelsByProvider(provider.id)
                                        .map { it.modelId.trim().lowercase() }.toSet()
                                    val fresh = chatModels
                                        .distinctBy { it.modelId.trim().lowercase() }
                                        .filter { it.modelId.trim().lowercase() !in existingKeys }
                                    val filteredCount = models.size - chatModels.size
                                    if (fresh.isEmpty()) {
                                        message = if (chatModels.isEmpty()) {
                                            context.getString(R.string.page_the_remote_end_did_not_return_a_usable_conversation__781487)
                                        } else {
                                            syncMessage ?: context.getString(R.string.provider_remote_none_new)
                                        }
                                    } else {
                                        remoteCandidates = fresh
                                        message = if (filteredCount > 0) {
                                            listOfNotNull(
                                                syncMessage,
                                                context.getString(
                                                    R.string.provider_models_fetched_filtered,
                                                    chatModels.size,
                                                    filteredCount,
                                                ),
                                            ).joinToString("\n")
                                        } else syncMessage
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (throwable: Throwable) {
                                    message = context.getString(
                                        R.string.provider_error,
                                        throwable.message ?: context.getString(R.string.provider_sync_failed),
                                    )
                                } finally {
                                    isFetching = false
                                }
                            }
                        },
                    )
                    }

                    item {
                        ProviderClickableRow(
                            title = stringResource(R.string.ui_add_custom_model_a5ddc0),
                            summary = stringResource(R.string.ui_manually_fill_in_the_display_name_and_model_id_077a7b),
                            enabled = !isFetching && !isMutatingModel,
                            showChevron = false,
                            leading = {
                                Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
                            },
                            onClick = {
                                onEditModel?.invoke("", true)
                            },
                        )
                    }

                    // 消息提示落在同组的容器段里（对应参考实现的 SegmentedItemContainer），
                    // 无消息时整段以 spring 动画收起。
                    item(visible = message != null) {
                        ProviderSegmentBody {
                            message?.let {
                                // M3：官方字阶 + 官方 error / primary 角色色。
                                MaterialText(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (it.startsWith(context.getString(R.string.page_fail_3e3c80))) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }

            item(key = "model_search", contentType = "search") {
                ProviderSearchField(
                    query = modelSearchQuery,
                    onQueryChange = { modelSearchQuery = it },
                    placeholder = stringResource(R.string.ui_search_model_df5586),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, top = 12.dp, bottom = 8.dp),
                )
            }

            val modelListTitle = if (normalizedModelSearchQuery.isBlank()) {
                context.getString(R.string.provider_model_list_count, provider.models.size)
            } else {
                context.getString(
                    R.string.provider_model_list_matches,
                    filteredModels.size,
                    provider.models.size,
                )
            }
            if (provider.models.isEmpty() || filteredModels.isEmpty()) {
                item(key = "models_title", contentType = "section_title") {
                    EtaSectionTitle(text = modelListTitle)
                }
                item(key = "models_empty", contentType = "empty") {
                    // 与供应商列表页一致：空态是无容器的居中文本，不再塞进分段容器。
                    ListEmptyState(
                        title = if (provider.models.isEmpty()) {
                            context.getString(R.string.page_there_is_no_model_yet_please_pull_it_from_the_remote_ced865)
                        } else {
                            context.getString(R.string.page_no_matching_model_found_ae7e96)
                        },
                    )
                }
            } else {
                item(key = "models_title", contentType = "section_title") {
                    Row(
                        // 尾部留出与搜索页计数行同档的边距：多选入口按钮不贴屏幕右缘
                        // （标题起点由 EtaSectionTitle 自带内边距保证，不受影响）。
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = EtaGroupHorizontalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 多选态把「已选 n 项」并入标题（居中点号隔开），与自然语言搜索文件页
                        // 的「找到 X 项」计数行同款。
                        Box(modifier = Modifier.weight(1f)) {
                            EtaSectionTitle(
                                text = if (selectionMode) {
                                    modelListTitle + " · " +
                                        stringResource(R.string.media_selected_count, selectedModelIds.size)
                                } else {
                                    modelListTitle
                                },
                            )
                        }
                        // 多选入口（纯图标）：多选态原位变「取消」，退出并清空勾选——取消不落
                        // 底栏（同搜索页计数行的入口原位切换）；长按模型行同样可进入多选。
                        // M3：官方 EtaIconButton（`Standard` 档裸图标，容器/图标尺寸取官方
                        // IconButtonDefaults.smallContainerSize / smallIconSize 令牌），自带长按提示。
                        EtaIconButton(
                            icon = if (selectionMode) Icons.Rounded.Close else Icons.Rounded.Checklist,
                            contentDescription = stringResource(
                                if (selectionMode) R.string.action_cancel else R.string.media_action_multiselect,
                            ),
                            onClick = {
                                TouchHaptics.click(view)
                                if (selectionMode) {
                                    selectionMode = false
                                    selectedModelIds = emptySet()
                                } else {
                                    selectionMode = true
                                }
                            },
                            enabled = !isFetching && !isMutatingModel,
                            size = EtaIconSize.S,
                            style = EtaButtonStyle.Standard,
                        )
                    }
                }
                itemsIndexed(
                    items = filteredModels,
                    key = { _, model -> "model:${model.id}" },
                    contentType = { _, _ -> "model" },
                ) { index, model ->
                    ModelListGroupItem(
                        isFirst = index == 0,
                        isLast = index == filteredModels.lastIndex,
                    ) {
                        ModelListItem(
                            model = model,
                            itemIndex = index,
                            itemCount = filteredModels.size,
                            enabled = !isFetching && !isMutatingModel,
                            isSelected = model.id == selectedModelId,
                            selectionMode = selectionMode,
                            checked = model.id in selectedModelIds,
                            onToggleChecked = {
                                selectedModelIds = if (model.id in selectedModelIds) {
                                    selectedModelIds - model.id
                                } else {
                                    selectedModelIds + model.id
                                }
                            },
                            onEnterSelection = {
                                selectionMode = true
                                selectedModelIds = setOf(model.id)
                            },
                            onEdit = {
                                onEditModel?.invoke(model.id, false)
                            },
                            onSetCurrent = {
                                onSelectCurrent?.invoke(model.id)
                                scope.launch {
                                    RuntimeConfigRepository.setSelectedModelId(model.id)
                                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                }
                            },
                        )
                    }
                }
                item(key = "models_section_gap", contentType = "spacer") {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            item(key = "bottom_spacer", contentType = "spacer") {
                // 多选操作栏悬浮在底部时，预留高度避免遮挡最后一个列表项；其余情况与大圆角屏幕下沿保持间距
                Spacer(
                    modifier = Modifier
                        .height(if (selectionMode) 88.dp else 24.dp)
                        .navigationBarsPadding(),
                )
            }
        }

        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            // 批量选择栏的进退场：位移走 M3E Spatial（轻微过冲），透明度走 Effects（无过冲）。
            enter = slideInVertically(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            ) { it } + fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = slideOutVertically(
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            ) { it } + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            ModelSelectionBar(
                selectedCount = selectedModelIds.size,
                allSelected = allModelsSelected,
                enabled = !isFetching && !isMutatingModel,
                onSelectAll = {
                    TouchHaptics.click(view)
                    selectedModelIds = if (allModelsSelected) {
                        emptySet()
                    } else {
                        filteredModels.mapTo(mutableSetOf()) { it.id }
                    }
                },
                onDelete = {
                    TouchHaptics.click(view)
                    showBatchDeleteDialog = true
                },
            )
        }
    }

    remoteCandidates?.let { candidates ->
        RemoteModelPickDialog(
            models = candidates,
            onDismiss = { remoteCandidates = null },
            onConfirm = { selected ->
                remoteCandidates = null
                scope.launch {
                    isMutatingModel = true
                    try {
                        val added = ModelRepository.addSelectedRemoteModels(provider.id, selected)
                        if (added > 0) {
                            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                        }
                        message = context.resources.getQuantityString(
                            R.plurals.provider_models_added,
                            added,
                            added,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        message = context.getString(
                            R.string.provider_error,
                            throwable.message ?: context.getString(R.string.provider_sync_failed),
                        )
                    } finally {
                        isMutatingModel = false
                    }
                }
            },
        )
    }

    if (showBatchDeleteDialog) {
        val confirmLabel = if (isMutatingModel) {
            context.getString(R.string.page_deleting_6f941d)
        } else {
            context.getString(R.string.ui_delete_3755f5)
        }
        val dialogTitle = stringResource(R.string.ui_delete_model_cf24da)
        val dialogSummary = pluralStringResource(
            R.plurals.provider_selected_delete_summary,
            selectedModelIds.size,
            selectedModelIds.size,
        )
        val onDismissBatch = { if (!isMutatingModel) showBatchDeleteDialog = false }
        val onCancelBatch = { showBatchDeleteDialog = false }
        val onConfirmBatch: () -> Unit = {
            scope.launch {
                val deletedCount = selectedModelIds.size
                isMutatingModel = true
                try {
                    ModelRepository.deleteModels(provider.id, selectedModelIds)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    message = context.resources.getQuantityString(
                        R.plurals.provider_models_deleted,
                        deletedCount,
                        deletedCount,
                    )
                    showBatchDeleteDialog = false
                    selectionMode = false
                    selectedModelIds = emptySet()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    message = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_delete_failed),
                    )
                    showBatchDeleteDialog = false
                } finally {
                    isMutatingModel = false
                }
            }
        }
        // 自适应弹窗：M3 → 官方 AlertDialog + TextButton。
        EtaDialog(
            show = true,
            title = dialogTitle,
            summary = dialogSummary,
            onDismissRequest = onDismissBatch,
            actions = {
                EtaDialogActions(
                    confirmText = confirmLabel,
                    cancelEnabled = !isMutatingModel,
                    confirmEnabled = !isMutatingModel,
                    destructive = true,
                    onCancel = onCancelBatch,
                    onConfirm = onConfirmBatch,
                )
            },
        ) {
        }
    }
}

@Composable
private fun ModelListGroupItem(
    isFirst: Boolean,
    isLast: Boolean,
    content: @Composable () -> Unit,
) {
    // M3：容器底色与首尾圆角交给官方 ListItem（segmentedColors + segmentedShapes），
    // 行间露出背景缝隙（EtaSegmentedGap），末项补足组间距（EtaSegmentedGroupSpacing），
    // 水平取组边距 token，与供应商列表页一致。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = EtaGroupHorizontalPadding,
                end = EtaGroupHorizontalPadding,
                bottom = if (isLast) EtaSegmentedGroupSpacing else EtaSegmentedGap,
            ),
    ) {
        content()
    }
}

/**
 * 多选批量操作栏：官方 M3E [HorizontalFloatingToolbar]（纯图标药丸），底部居中悬浮，
 * 与自然语言搜索文件页（MediaSearchScreen.MediaSelectionBar）同款装配。
 * 动作序：全选（全不选）→ 删除（error 色标破坏性，执行前另有确认弹窗）；「取消」不落
 * 底栏，由标题行右侧多选入口原位切换（Checklist→Close）承载，已选数量并入标题行文案。
 *
 * 组件结论（随同仓 MediaSearchScreen 同版本依赖的既有调用核证，非凭记忆）：
 * [HorizontalFloatingToolbar] 为 ExperimentalMaterial3ExpressiveApi；`leadingContent = {}`
 * 显式传空以归零官方 minimumInteractiveBalancedPadding 的高度补偿（本栏布局全显式，
 * 官方参数语义非 hack）；端盖同心取 [EtaFloatingToolbarConcentricPadding]`(smallContainerSize().width)`；
 * 全选/全不选用 EtaIconButton（`Standard` 档 + `EtaIconSize.S` = 官方 smallContainerSize /
 * smallIconSize 令牌）；删除用官方裸 [IconButton]（同尺寸令牌 + `IconButtonDefaults.shapes()`
 * 按压 morph），图标 error 色——EtaIconButton 无 tint 参数，裸按钮才好只用官方 token
 * 表达破坏性配色。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModelSelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    enabled: Boolean,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
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
            // leading/trailing 内容时才生效，会按内容里的 MinimumInteractive 对齐线补偿
            // 内边距、把药丸撑高；本栏布局完全显式（固定间距 + 同心端盖），传空让补偿归零。
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
                        if (allSelected) R.string.media_action_deselect_all else R.string.media_action_select_all,
                    ),
                    onClick = onSelectAll,
                    enabled = enabled,
                    size = EtaIconSize.S,
                    style = EtaButtonStyle.Standard,
                )
                // 删除是破坏性操作：图标用 error 色区分（官方裸 IconButton，容器/图标尺寸
                // 与 EtaIconButton 的 `Standard` + S 档一致），执行前另有确认弹窗。
                val deleteDescription = stringResource(R.string.ui_delete_3755f5)
                EtaTooltip(text = deleteDescription) {
                    IconButton(
                        onClick = onDelete,
                        enabled = selectedCount > 0 && enabled,
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelListItem(
    model: Model,
    enabled: Boolean,
    isSelected: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onToggleChecked: () -> Unit,
    onEnterSelection: () -> Unit,
    onEdit: () -> Unit,
    onSetCurrent: () -> Unit,
    modifier: Modifier = Modifier,
    itemIndex: Int = 0,
    itemCount: Int = 1,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val onClick = {
        TouchHaptics.click(view)
        if (selectionMode) onToggleChecked() else onSetCurrent()
    }
    val onLongClick = {
        if (selectionMode) onToggleChecked() else onEnterSelection()
    }
    // 尾部：多选时为官方 Checkbox，否则为「当前模型」勾 + 官方图标按钮。
    val trailing: @Composable () -> Unit = {
        if (selectionMode) {
            Checkbox(checked = checked, onCheckedChange = { onClick() }, enabled = enabled)
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.wrapContentWidth(),
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = context.getString(R.string.page_current_model_a0af8f),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(end = 2.dp)
                            .size(EtaDimens.DenseIconSize),
                    )
                }
                ProviderIconAction(
                    icon = Icons.Rounded.Tune,
                    contentDescription = stringResource(R.string.ui_edit_model_parameters_ba4864),
                    enabled = enabled,
                    onClick = {
                        TouchHaptics.click(view)
                        onEdit()
                    },
                )
            }
        }
    }
    // M3：官方 SegmentedListItem（selected + onClick + onLongClick）一次覆盖点按、长按与多选；
    // 分组圆角与分组底色改由官方 ListItemDefaults.segmentedShapes / segmentedColors 提供，
    // 容器色与 KernelSU 参考实现一致取 surfaceBright（从页面 surfaceContainer 浮出一档）。
    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        selected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(R.string.action_enter_selection),
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shapes = ListItemDefaults.segmentedShapes(index = itemIndex, count = itemCount),
        colors = ListItemDefaults.segmentedColors(
            containerColor = MaterialTheme.colorScheme.surfaceBright,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceBright,
            supportingContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        content = {
            MaterialText(
                text = model.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            // Column 不能省：supportingContent 内 modelId 与标签行若裸排会叠在同一位置。
            Column {
                MaterialText(
                    text = model.modelId,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    capabilityTags(model).forEach { tag ->
                        TagChip(text = tag)
                    }
                    if (isSelected) {
                        TagChip(text = stringResource(R.string.ui_current_25e74d), tone = TagChipTone.Emphasized)
                    }
                }
            }
        },
        trailingContent = trailing,
    )
}

@Composable
private fun showsReasoningTag(model: Model): Boolean {
    if (model.reasoningOverride == false) return false
    if (model.supportsReasoning) return true
    return ReasoningCapabilityResolver.resolve(
        sourceType = ProviderSourceTypes.CUSTOM,
        model = model,
    ) != null
}

@Composable
private fun capabilityTags(model: Model): List<String> {
    val context = LocalContext.current
    return buildList {
    add(
        model.effectiveContextWindow?.let { contextWindow ->
            stringResource(
                R.string.provider_context_tag,
                formatCompactTokenCount(contextWindow),
            )
        } ?: context.getString(R.string.page_context_unknown_b6ae7b)
    )
    if (showsReasoningTag(model)) add(context.getString(R.string.ui_support_thinking_5b9e4c))
    }
}

@Composable
private fun RemoteModelPickDialog(
    models: List<Model>,
    onDismiss: () -> Unit,
    onConfirm: (List<Model>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedKeys by remember(models) { mutableStateOf(emptySet<String>()) }
    val visible = remember(models, query) {
        val needle = query.trim()
        if (needle.isEmpty()) models
        else models.filter { model ->
            model.displayName.contains(needle, ignoreCase = true) ||
                model.modelId.contains(needle, ignoreCase = true)
        }
    }
    fun keyOf(model: Model) = model.modelId.trim().lowercase()
    // 远端模型通常很多：底部面板承载搜索、全选与分段多选列表，动作固定在面板尾部。
    EtaPickerSheet(
        title = stringResource(R.string.provider_remote_pick_title),
        onDismiss = onDismiss,
        query = query,
        onQueryChange = { query = it },
        searchPlaceholder = stringResource(R.string.ui_search_model_df5586),
        actions = {
            TextButton(onClick = onDismiss) {
                MaterialText(stringResource(R.string.action_cancel))
            }
            TextButton(
                enabled = selectedKeys.isNotEmpty(),
                onClick = { onConfirm(models.filter { keyOf(it) in selectedKeys }) },
            ) {
                MaterialText(stringResource(R.string.provider_remote_add))
            }
        },
    ) {
        val view = LocalView.current
        val onSelectAll = {
            TouchHaptics.click(view)
            selectedKeys = selectedKeys + visible.map(::keyOf)
        }
        val onInvert = {
            TouchHaptics.click(view)
            val visibleKeys = visible.map(::keyOf).toSet()
            selectedKeys = (selectedKeys - visibleKeys) + (visibleKeys - selectedKeys)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // M3：官方 TextButton。
            TextButton(onClick = onSelectAll) {
                MaterialText(stringResource(R.string.provider_remote_select_all))
            }
            TextButton(onClick = onInvert) {
                MaterialText(stringResource(R.string.provider_remote_invert))
            }
            Spacer(Modifier.weight(1f))
            MaterialText(
                "${selectedKeys.size}/${models.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (visible.isEmpty()) {
            ListEmptyState(
                title = stringResource(R.string.page_no_matching_model_found_ae7e96),
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                itemsIndexed(
                    items = visible,
                    key = { _, model -> keyOf(model) },
                ) { index, model ->
                    val key = keyOf(model)
                    // 分段条目：首/末项由官方分段形状收外圆角，行间 2dp 露出背景缝隙
                    //（与供应商列表页的 `EtaSegmentedItem` 用法一致）。
                    EtaSegmentedItem(index = index, count = visible.size) {
                        EtaSegmentedCheckboxRow(
                            checked = key in selectedKeys,
                            onCheckedChange = { on: Boolean ->
                                selectedKeys = if (on) selectedKeys + key else selectedKeys - key
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    bottom = if (index == visible.lastIndex) 0.dp else EtaSegmentedGap,
                                ),
                            content = {
                                MaterialText(
                                    model.displayName.ifBlank { model.modelId },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = if (model.displayName.isNotBlank() && model.displayName != model.modelId) {
                                {
                                    MaterialText(
                                        model.modelId,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}
