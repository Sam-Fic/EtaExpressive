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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.VisionChatModels
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaPageSurface
import io.github.mangi.eta.ui.adaptive.EtaPickerSheet
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaSectionTitle
import io.github.mangi.eta.ui.adaptive.EtaSegmentedCheckboxRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val modelSearchSeparators = Regex("""[^\p{L}\p{N}]+""")
private val allReasoningEffortTiers = listOf(
    ReasoningEffort.MINIMAL,
    ReasoningEffort.LOW,
    ReasoningEffort.MEDIUM,
    ReasoningEffort.HIGH,
    ReasoningEffort.XHIGH,
    ReasoningEffort.MAX,
)

internal fun contextWindowInputError(
    value: String,
    errorMessage: String = "Context window must be a positive integer",
): String? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    return if (normalized.toIntOrNull()?.let { it > 0 } == true) {
        null
    } else {
        errorMessage
    }
}

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
) {
    val context = LocalContext.current
    val storedModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    val selectedModelId = currentModelId?.takeIf { it.isNotBlank() } ?: storedModelId
    var isFetching by remember { mutableStateOf(false) }
    var isMutatingModel by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editingModel by remember { mutableStateOf<Model?>(null) }
    var isCreatingModel by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var modelPendingDelete by remember { mutableStateOf<Model?>(null) }
    var selectionMode by remember(provider.id) { mutableStateOf(false) }
    var selectedModelIds by remember(provider.id) { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var modelSearchQuery by remember(provider.id) { mutableStateOf("") }
    var remoteCandidates by remember(provider.id) { mutableStateOf<List<Model>?>(null) }
    val normalizedModelSearchQuery = modelSearchQuery.trim()
    val filteredModels = remember(provider.models, modelSearchQuery) {
        filterProviderModels(provider.models, modelSearchQuery)
    }

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
                                editorError = null
                                isCreatingModel = true
                                editingModel = Model(
                                    id = "",
                                    modelId = "",
                                    displayName = context.getString(R.string.page_custom_model_25be0f),
                                )
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
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
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
                    val view = LocalView.current
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            EtaSectionTitle(text = modelListTitle)
                        }
                        if (selectionMode) {
                            val onSelectAll = {
                                TouchHaptics.click(view)
                                selectedModelIds = if (
                                    filteredModels.isNotEmpty() &&
                                    selectedModelIds.containsAll(filteredModels.map { it.id })
                                ) {
                                    emptySet()
                                } else {
                                    filteredModels.mapTo(mutableSetOf()) { it.id }
                                }
                            }
                            // M3：官方 TextButton。
                            TextButton(
                                onClick = onSelectAll,
                                enabled = !isFetching && !isMutatingModel,
                            ) {
                                MaterialText(text = stringResource(R.string.page_select_all_3e44b2))
                            }
                        }
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
                                editorError = null
                                isCreatingModel = false
                                editingModel = model
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
                enabled = !isFetching && !isMutatingModel,
                onDelete = { showBatchDeleteDialog = true },
                onExit = {
                    selectionMode = false
                    selectedModelIds = emptySet()
                },
            )
        }
    }

    editingModel?.let { model ->
        ModelEditPage(
            model = model,
            isNew = isCreatingModel,
            isSaving = isMutatingModel,
            error = editorError,
            onDismiss = {
                if (!isMutatingModel) editingModel = null
            },
            onSubmit = { updated ->
                if (isMutatingModel) return@ModelEditPage
                scope.launch {
                    isMutatingModel = true
                    editorError = null
                    try {
                        val saved = ModelRepository.saveModel(provider.id, updated)
                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                        editingModel = null
                        message = context.getString(R.string.provider_model_saved, saved.displayName)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        editorError = throwable.message ?: context.getString(R.string.page_save_failed_40525a)
                    } finally {
                        isMutatingModel = false
                    }
                }
            },
            onDelete = if (isCreatingModel) null else {
                {
                    modelPendingDelete = model
                    editingModel = null
                }
            },
        )
    }

    modelPendingDelete?.let { model ->
        val confirmLabel = if (isMutatingModel) {
            context.getString(R.string.page_deleting_6f941d)
        } else {
            context.getString(R.string.ui_delete_3755f5)
        }
        val onConfirmDelete: () -> Unit = {
            scope.launch {
                isMutatingModel = true
                try {
                    ModelRepository.deleteModel(provider.id, model.id)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    message = context.getString(R.string.provider_model_deleted, model.displayName)
                    modelPendingDelete = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    message = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_delete_failed),
                    )
                    modelPendingDelete = null
                } finally {
                    isMutatingModel = false
                }
            }
        }
        val dialogTitle = stringResource(R.string.ui_delete_model_cf24da)
        val dialogSummary = stringResource(R.string.provider_model_delete_summary, model.displayName)
        val onDismissDelete = { if (!isMutatingModel) modelPendingDelete = null }
        val onCancelDelete = { modelPendingDelete = null }
        // 自适应弹窗：M3 → 官方 AlertDialog + TextButton。
        EtaDialog(
            show = true,
            title = dialogTitle,
            summary = dialogSummary,
            onDismissRequest = onDismissDelete,
            actions = {
                EtaDialogActions(
                    confirmText = confirmLabel,
                    cancelEnabled = !isMutatingModel,
                    confirmEnabled = !isMutatingModel,
                    destructive = true,
                    onCancel = onCancelDelete,
                    onConfirm = onConfirmDelete,
                )
            },
        ) {
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

/** 多选模式底部悬浮操作栏：退出在左，已选数量其次，全选与删除在右；删除沿用统一破坏性配色。 */
@Composable
private fun ModelSelectionBar(
    selectedCount: Int,
    enabled: Boolean,
    onDelete: () -> Unit,
    onExit: () -> Unit,
) {
    EtaCard(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProviderDestructiveAction(
                text = stringResource(R.string.ui_delete_3755f5),
                onClick = onDelete,
                enabled = selectedCount > 0 && enabled,
                modifier = Modifier.weight(1f),
            )
            ProviderPrimaryAction(
                text = stringResource(R.string.action_cancel),
                onClick = onExit,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
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
private fun ModelEditPage(
    model: Model,
    isNew: Boolean,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (Model) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var displayName by remember(model.id, isNew) { mutableStateOf(model.displayName) }
    var modelId by remember(model.id, isNew) { mutableStateOf(model.modelId) }
    var contextWindowOverrideText by remember(model.id, isNew) {
        mutableStateOf(model.contextWindowOverride?.toString().orEmpty())
    }
    val automaticReasoning = remember(model.id, model.modelId, model.reasoning, model.reasoningCapabilities) {
        ReasoningCapabilityResolver.automaticCapabilities(model)
    }
    val suggestedReasoning = remember(model.id, model.modelId, model.reasoning, model.reasoningCapabilities) {
        ReasoningCapabilityResolver.suggestedCapabilities(model)
    }
    var reasoningOverrideActive by remember(model.id, isNew) {
        mutableStateOf(
            model.reasoningOverride != null || model.reasoningCapabilitiesOverride != null
        )
    }
    var reasoningEnabled by remember(model.id, isNew) {
        mutableStateOf(
            when {
                model.reasoningOverride == false -> false
                model.supportsReasoning -> true
                else -> automaticReasoning != null
            }
        )
    }
    val automaticVision = remember(model.id, modelId, model.attachment, model.inputModalities) {
        modelId.trim() == model.modelId && VisionChatModels.matches(model)
    }
    var visionOverrideActive by remember(model.id, isNew) {
        mutableStateOf(model.visionOverride != null)
    }
    var visionEnabled by remember(model.id, isNew) {
        mutableStateOf(model.supportsVision)
    }
    LaunchedEffect(automaticVision, visionOverrideActive) {
        if (!visionOverrideActive) visionEnabled = automaticVision
    }
    val contextError = contextWindowInputError(
        contextWindowOverrideText,
        context.getString(R.string.page_the_context_length_must_be_a_positive_integer_06ca7a),
    )

    fun resetAutomaticReasoning() {
        reasoningOverrideActive = false
        reasoningEnabled = automaticReasoning != null
    }

    fun updated(): Model = model.copy(
        displayName = displayName.trim(),
        modelId = modelId.trim(),
        contextWindowOverride = contextWindowOverrideText.trim()
            .takeIf(String::isNotEmpty)
            ?.toInt(),
        visionOverride = if (visionOverrideActive) visionEnabled else null,
        attachment = model.attachment.takeIf { modelId.trim() == model.modelId },
        inputModalities = if (modelId.trim() == model.modelId) model.inputModalities else listOf(Model.TEXT_MODALITY),
        reasoningOverride = reasoningEnabled.takeIf { reasoningOverrideActive },
        reasoningCapabilitiesOverride = if (reasoningOverrideActive && reasoningEnabled) {
            (model.effectiveReasoningCapabilities ?: suggestedReasoning).copy(
                supportedEfforts = allReasoningEffortTiers,
                defaultEffort = model.effectiveReasoningCapabilities
                    ?.defaultEffort
                    ?.takeIf { it != ReasoningEffort.OFF }
                    ?: suggestedReasoning.defaultEffort
                    ?: ReasoningEffort.MEDIUM,
                defaultEnabled = true,
                mandatory = false,
                canDisable = true,
            )
        } else {
            null
        },
    )

    val dismissPage = { if (!isSaving) onDismiss() }
    // 多字段编辑已超出弹窗的决策语义：改为整屏页面，动作固定在底部，正文由 Scaffold 统一滚动。
    EtaPageSurface(onDismissRequest = dismissPage) {
        EtaScaffold(
            title = if (isNew) context.getString(R.string.page_add_model_532a64) else context.getString(R.string.page_edit_model_29e31e),
            onBack = dismissPage,
            modifier = Modifier.fillMaxSize().imePadding(),
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                    EtaDialogActions(
                        modifier = Modifier.navigationBarsPadding().imePadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        confirmText = if (isSaving) context.getString(R.string.page_saving_d70d42) else context.getString(R.string.page_save_fadf24),
                        confirmEnabled = !isSaving &&
                            displayName.isNotBlank() &&
                            modelId.isNotBlank() &&
                            contextError == null,
                        cancelEnabled = !isSaving,
                        onCancel = onDismiss,
                        onConfirm = { onSubmit(updated()) },
                    )
                }
            },
        ) { padding, scrollConnection, _ ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .nestedScroll(scrollConnection)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
            ) {
                ProviderField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = stringResource(R.string.ui_display_name_ed16be),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                ProviderField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = stringResource(R.string.provider_model_id),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                ProviderField(
                    value = contextWindowOverrideText,
                    onValueChange = { contextWindowOverrideText = it },
                    label = stringResource(R.string.ui_context_length_tokens_227860),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                contextError?.let { validationError ->
                    MaterialText(
                        text = validationError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                MaterialText(
                    text = stringResource(R.string.ui_this_value_is_used_for_session_clipping_and_context__c3f9e7),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    item {
                    EtaSwitchRow(
                        checked = reasoningEnabled,
                        onCheckedChange = { enabled ->
                            reasoningOverrideActive = true
                            reasoningEnabled = enabled
                        },
                        title = stringResource(R.string.ui_support_thinking_5b9e4c),
                        summary = if (reasoningOverrideActive) {
                            context.getString(R.string.page_covered_model_automatic_capabilities_3fa7d4)
                        } else {
                            stringResource(
                                if (automaticReasoning != null) {
                                    R.string.provider_auto_reasoning_supported
                                } else {
                                    R.string.provider_auto_reasoning_unknown
                                },
                            )
                        },
                        enabled = !isSaving,
                    )
                    }
                    item {
                    EtaSwitchRow(
                        checked = visionEnabled,
                        onCheckedChange = { enabled ->
                            visionOverrideActive = true
                            visionEnabled = enabled
                        },
                        title = stringResource(R.string.ui_support_vision),
                        summary = if (visionOverrideActive) {
                            context.getString(R.string.page_covered_model_automatic_capabilities_3fa7d4)
                        } else {
                            stringResource(
                                if (automaticVision) {
                                    R.string.provider_auto_vision_supported
                                } else {
                                    R.string.provider_auto_vision_unknown
                                },
                            )
                        },
                        enabled = !isSaving,
                    )
                    }
                }
                error?.let { message ->
                    MaterialText(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (reasoningOverrideActive) {
                    ProviderPrimaryAction(
                        text = stringResource(R.string.ui_restore_automatic_8d4e1e),
                        onClick = {
                            TouchHaptics.click(view)
                            resetAutomaticReasoning()
                        },
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
                onDelete?.let { delete ->
                    ProviderDestructiveAction(
                        text = stringResource(R.string.ui_delete_model_cf24da),
                        onClick = {
                            TouchHaptics.click(view)
                            delete()
                        },
                        enabled = !isSaving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            }
        }
    }
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
