package io.github.mangi.eta.ui.pages.providers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.typeLabel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaCard
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSectionTitle
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.material3.Text as M3Text

/** 分组标题（已含上下留白）+ 分段列表的组合外边距。 */
private val SectionGroupPadding = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 21.dp)

@Composable
internal fun ModelProviderListScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    currentProviderId: String? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val storedProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val selectedProviderId = currentProviderId?.takeIf { it.isNotBlank() } ?: storedProviderId
    val listState = rememberLazyListState()
    var selectionMode by remember { mutableStateOf(false) }
    var selectedProviderIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    // 主页不过滤（query 恒空即全量），搜索交给独立搜索页。
    val filteredProviders = providers
    val selectableProviders = remember(providers) {
        providers.filterNot(ProviderSetting::isBuiltIn)
    }

    LaunchedEffect(selectionMode, selectedProviderIds, providers) {
        if (!selectionMode) return@LaunchedEffect
        val existingIds = providers.filterNot { it.isBuiltIn }.mapTo(mutableSetOf()) { it.id }
        val pruned = selectedProviderIds.filter { it in existingIds }.toSet()
        if (pruned != selectedProviderIds) selectedProviderIds = pruned
        if (existingIds.isEmpty()) {
            selectionMode = false
            selectedProviderIds = emptySet()
        }
    }

    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectionMode,
        onBackCompleted = {
            selectionMode = false
            selectedProviderIds = emptySet()
        },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        EtaScaffoldPage(
            title = stringResource(R.string.ui_model_provider_e8c7f5),
            onBack = {
                if (selectionMode) {
                    selectionMode = false
                    selectedProviderIds = emptySet()
                } else {
                    onBack()
                }
            },
            listState = listState,
            actions = {
                // 搜索入口：大标题栏只保留这一个单搜索图标，点击进独立搜索页。
                M3IconButton(onClick = {
                    TouchHaptics.click(view)
                    onNavigate(AppRoute.ModelProviderSearch)
                }) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.ui_search_provider_74e049),
                    )
                }
            },
        ) {
            materialProviderListContent(
                searchQuery = "",
                filteredProviders = filteredProviders,
                selectableProviders = selectableProviders,
                selectionMode = selectionMode,
                selectedProviderIds = selectedProviderIds,
                selectedProviderId = selectedProviderId,
                onToggleChecked = { id ->
                    if (id in selectedProviderIds) {
                        selectedProviderIds - id
                    } else {
                        selectedProviderIds + id
                    }
                },
                onSelectAll = {
                    TouchHaptics.click(view)
                    selectedProviderIds = if (
                        selectableProviders.isNotEmpty() &&
                        selectedProviderIds.containsAll(selectableProviders.map { it.id })
                    ) {
                        emptySet()
                    } else {
                        selectableProviders.mapTo(mutableSetOf()) { it.id }
                    }
                },
                onOpen = { id ->
                    TouchHaptics.click(view)
                    onNavigate(AppRoute.ModelProviderDetail(id))
                },
                onEnterSelection = { id ->
                    selectionMode = true
                    selectedProviderIds = setOf(id)
                },
                onAddProvider = { type ->
                    TouchHaptics.click(view)
                    onNavigate(
                        when (type) {
                            NewProviderType.OpenAiCompatible -> {
                                AppRoute.ModelProviderAuthMethod(NewProviderType.OpenAiCompatible)
                            }
                            else -> AppRoute.ModelProviderNew(type)
                        },
                    )
                },
            )

            // 选择模式下预留底部操作条高度；其余场景用骨架自带的底距。
            if (selectionMode) {
                item(key = "selection_spacer") {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(88.dp)
                            .navigationBarsPadding(),
                    )
                }
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
            ProviderSelectionBar(
                selectedCount = selectedProviderIds.size,
                enabled = !isDeleting,
                onDelete = {
                    TouchHaptics.click(view)
                    showBatchDeleteDialog = true
                },
                onExit = {
                    TouchHaptics.click(view)
                    selectionMode = false
                    selectedProviderIds = emptySet()
                },
            )
        }
    }

    if (showBatchDeleteDialog) {
        val confirmLabel = if (isDeleting) {
            context.getString(R.string.page_deleting_6f941d)
        } else {
            context.getString(R.string.ui_delete_3755f5)
        }
        val dialogTitle = stringResource(R.string.ui_remove_provider_9f848f)
        val dialogSummary = pluralStringResource(
            R.plurals.provider_batch_delete_summary,
            selectedProviderIds.size,
            selectedProviderIds.size,
        )
        val onDismissBatch = { if (!isDeleting) showBatchDeleteDialog = false }
        val onCancelBatch = { showBatchDeleteDialog = false }
        val onConfirmBatch: () -> Unit = {
            scope.launch {
                isDeleting = true
                try {
                    ProviderRepository.deleteProviders(selectedProviderIds)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    showBatchDeleteDialog = false
                    selectionMode = false
                    selectedProviderIds = emptySet()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    isDeleting = false
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
                    cancelEnabled = !isDeleting,
                    confirmEnabled = !isDeleting && selectedProviderIds.isNotEmpty(),
                    destructive = true,
                    onCancel = onCancelBatch,
                    onConfirm = onConfirmBatch,
                )
            },
        ) {
        }
    }
}

/**
 * Material 3 列表内容：KernelSU 式分段列表布局 —— 添加组标题+分段列、
 * 已配置标题行（含全选）+ 分段供应商行；空态为无容器的居中文本。
 *
 * 供应商主页（大标题栏，query 恒空即全量）与独立搜索页共用。
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.materialProviderListContent(
    searchQuery: String,
    filteredProviders: List<ProviderSetting>,
    selectableProviders: List<ProviderSetting>,
    selectionMode: Boolean,
    selectedProviderIds: Set<String>,
    selectedProviderId: String?,
    onToggleChecked: (String) -> Unit,
    onSelectAll: () -> Unit,
    onOpen: (String) -> Unit,
    onEnterSelection: (String) -> Unit,
    onAddProvider: (NewProviderType) -> Unit,
) {
    item(key = "create_section") {
        EtaSegmentedColumn(
            modifier = SectionGroupPadding,
            title = stringResource(R.string.ui_add_new_provider_74df54),
        ) {
            item {
                ProviderClickableRow(
                    title = stringResource(R.string.ui_added_openai_compatible_6bd471),
                    summary = stringResource(R.string.ui_support_chatgpt_deepseek_kimi_glm_qwen_etc_b31d02),
                    leading = { ProviderBrandIcon(ProviderSourceTypes.OPENAI) },
                    onClick = { onAddProvider(NewProviderType.OpenAiCompatible) },
                )
            }
            item {
                ProviderClickableRow(
                    title = stringResource(R.string.ui_new_anthropic_db6098),
                    summary = stringResource(R.string.ui_support_anthropic_claude_official_or_compatible_api_de3f80),
                    leading = { ProviderBrandIcon(ProviderSourceTypes.ANTHROPIC) },
                    onClick = { onAddProvider(NewProviderType.Anthropic) },
                )
            }
            item {
                ProviderClickableRow(
                    title = stringResource(R.string.ui_new_doubao_speech),
                    summary = stringResource(R.string.ui_new_doubao_speech_summary),
                    leading = { ProviderBrandIcon(ProviderSourceTypes.DOUBAO_SPEECH) },
                    onClick = { onAddProvider(NewProviderType.DoubaoSpeech) },
                )
            }
        }
    }

    item(key = "list_title") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                EtaSectionTitle(
                    text = pluralStringResource(
                        R.plurals.provider_configured_count,
                        filteredProviders.size,
                        filteredProviders.size,
                    ),
                )
            }
            if (selectionMode && selectableProviders.isNotEmpty()) {
                // M3：官方 TextButton。
                M3TextButton(onClick = onSelectAll) {
                    M3Text(text = stringResource(R.string.page_select_all_3e44b2))
                }
            }
        }
    }

    if (filteredProviders.isEmpty()) {
        item(key = "empty") {
            ListEmptyState(
                title = if (searchQuery.isBlank()) {
                    stringResource(R.string.provider_empty)
                } else {
                    stringResource(R.string.provider_no_matches)
                },
            )
        }
    } else {
        items(
            count = filteredProviders.size,
            key = { index -> filteredProviders[index].id },
        ) { index ->
            val provider = filteredProviders[index]
            EtaSegmentedItem(
                index = index,
                count = filteredProviders.size,
            ) {
                // 分段间 2dp 露出背景缝隙（KernelSU 的 spacedBy(2.dp)），
                // 末项补足组间距 13dp。
                val isLast = index == filteredProviders.lastIndex
                Column(
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = if (isLast) 13.dp else 2.dp,
                    ),
                ) {
                    ProviderListItem(
                        provider = provider,
                        isCurrent = provider.id == selectedProviderId,
                        selectionMode = selectionMode,
                        checked = provider.id in selectedProviderIds,
                        onOpen = { onOpen(provider.id) },
                        onToggleChecked = { onToggleChecked(provider.id) },
                        onEnterSelection = { onEnterSelection(provider.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderSelectionBar(
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
                .padding(8.dp),
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

/**
 * 供应商列表项。
 *
 * Material 3 走官方 [ListItem]（selected + onClick + onLongClick 一次覆盖点按、长按与
 * 多选勾选框），替掉「Row + combinedClickable + 手搓勾选」。
 */
@Composable
private fun ProviderListItem(
    provider: ProviderSetting,
    isCurrent: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onOpen: () -> Unit,
    onToggleChecked: () -> Unit,
    onEnterSelection: () -> Unit,
) {
    val selectable = !provider.isBuiltIn
    val opacity = if (provider.isEnabled) 1f else 0.6f
    val metaText = listOfNotNull(
        provider.typeLabel,
        pluralStringResource(R.plurals.provider_models_count, provider.models.size, provider.models.size),
        stringResource(R.string.ui_built_in_09ceea).takeIf { provider.isBuiltIn },
        stringResource(R.string.ui_current_25e74d).takeIf { isCurrent },
    ).joinToString(" · ")
    val showCheckbox = selectionMode && selectable
    val showCurrentMark = !selectionMode && isCurrent

    // 行尾三态：多选勾选框 → 选择模式下不可多选的内建行无尾部 → 常态显示
    // 「当前」对勾（若有）与导航箭头共存。
    val trailing: (@Composable () -> Unit)? = when {
        showCheckbox -> {
            {
                Checkbox(checked = checked, onCheckedChange = { onToggleChecked() })
            }
        }
        selectionMode -> null
        else -> {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showCurrentMark) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.ui_current_25e74d),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val meta: @Composable () -> Unit = {
        M3Text(
            text = metaText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!provider.isEnabled) {
            M3Text(
                text = stringResource(R.string.ui_disabled_0fe5a9),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    EtaSegmentedRow(
        selected = isCurrent,
        choiceRow = true,
        onClick = {
            when {
                selectionMode && selectable -> onToggleChecked()
                selectionMode -> Unit
                else -> onOpen()
            }
        },
        onLongClick = {
            if (selectable) {
                if (selectionMode) onToggleChecked() else onEnterSelection()
            }
        },
        onLongClickLabel = stringResource(R.string.action_enter_selection),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = opacity },
        leadingContent = { ProviderIcon(provider) },
        content = {
            M3Text(
                text = provider.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            // Column 不能省：supportingContent 内多个文本若裸排会叠在同一位置。
            Column {
                M3Text(
                    text = provider.baseUrl,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                meta()
            }
        },
        trailingContent = trailing,
    )
}
