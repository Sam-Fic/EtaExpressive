package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import android.content.SharedPreferences
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaPickerSheet
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.components.ContainedMorphLoadingIndicator
import io.github.mangi.eta.ui.components.filterModelProviderGroups
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import androidx.compose.material3.TextButton as M3TextButton


@Composable
internal fun CompressionEndpointPreference(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val index = if (selected == OpenAiEndpointMode.RESPONSES) 1 else 0
    // 与兄弟界面（外观设置等）一致的下拉交互：点击行弹出锚定菜单二选一，选中项带 Check。
    EtaSegmentedDropdownRow(
        title = stringResource(R.string.ui_compress_request_api),
        summary = stringResource(R.string.ui_compress_endpoint_description),
        icon = Icons.Rounded.Api,
        items = listOf("Chat Completions API", "Responses API"),
        selectedIndex = index,
        onItemSelected = { selectedIndex ->
            onSelect(
                if (selectedIndex == 1) OpenAiEndpointMode.RESPONSES
                else OpenAiEndpointMode.CHAT_COMPLETIONS,
            )
        },
        enabled = enabled,
    )
}


internal fun AgentModelOptionUi?.hasCompressContextWindow(): Boolean =
    this?.contextWindow?.let { it > 0 } == true

internal fun AgentModelPickerUiState.findCompressModel(providerId: String, modelId: String): AgentModelOptionUi? =
    providerGroups.firstOrNull { it.providerId == providerId }?.models?.firstOrNull { it.id == modelId }

@Composable
internal fun CompressModelMissingWindowDialog(
    show: Boolean,
    onDismiss: () -> Unit,
) {
    if (!show) return
    EtaDialog(
        show = true,
        title = stringResource(R.string.compress_model_missing_window_title),
        summary = stringResource(R.string.compress_model_missing_window_summary),
        onDismissRequest = onDismiss,
        actions = {
            // 官方文字按钮：形状、行高与配色均取官方默认值。
            M3TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.ui_knew_cb63c6))
            }
        },
    ) {
    }
}

@Composable
internal fun CompressModelPickerDialog(
    state: AgentModelPickerUiState,
    show: Boolean,
    isLoading: Boolean = false,
    onDismiss: () -> Unit,
    onModelSelected: (String, String) -> Unit,
    title: String? = null,
) {
    if (!show) return
    // 面板内的「待确认」选择：与其它模型选择面板一致——先选中，再点确认提交；取消直接丢弃。
    // 以 show / 当前已选模型为键，保证每次打开都从最新绑定回填、加载完成后补上默认选中。
    var query by remember(show) { mutableStateOf("") }
    var pendingProviderId by remember(show, state.selectedModel) { mutableStateOf(state.selectedModel?.providerId) }
    var pendingModelId by remember(show, state.selectedModel) { mutableStateOf(state.selectedModel?.id) }
    val hasPending = pendingModelId != null
    val groups = remember(state.providerGroups, query) {
        filterModelProviderGroups(state.providerGroups, query.trim())
    }
    EtaPickerSheet(
        title = title ?: stringResource(R.string.ui_compress_model_title),
        onDismiss = onDismiss,
        query = query,
        onQueryChange = { query = it },
        searchPlaceholder = stringResource(R.string.ui_search_model_df5586),
        actions = {
            M3TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
            M3TextButton(
                enabled = hasPending,
                onClick = {
                    val providerId = pendingProviderId
                    val modelId = pendingModelId
                    if (providerId != null && modelId != null) onModelSelected(providerId, modelId)
                },
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            if (isLoading && state.providerGroups.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    // 块级空态统一 28dp contained，归入 loading 选型表的块级档。
                    ContainedMorphLoadingIndicator()
                }
            } else if (groups.isEmpty()) {
                Text(
                    text = stringResource(
                        if (state.providerGroups.isEmpty()) R.string.provider_empty
                        else R.string.page_no_matching_model_found_ae7e96,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            } else {
                // KernelSU 式分段单选列表——提供商作分组标题、模型为组内单选行。
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(EtaSegmentedGroupSpacing),
                ) {
                    groups.forEach { group ->
                        EtaSegmentedColumn(
                            modifier = Modifier.fillMaxWidth(),
                            title = group.providerName,
                        ) {
                            group.models.forEach { model ->
                                item(key = "${model.providerId}/${model.id}") {
                                    ModelPickerRadioRow(
                                        title = model.displayName,
                                        selected = pendingProviderId == model.providerId &&
                                            pendingModelId == model.id,
                                        onClick = {
                                            pendingProviderId = model.providerId
                                            pendingModelId = model.id
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal suspend fun buildCompressModelPickerState(
    prefs: SharedPreferences?,
): AgentModelPickerUiState {
    val providerId = prefs?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, null)
    val modelId = prefs?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_ID, null)
    return buildCompressModelPickerState(providerId, modelId)
}

internal suspend fun buildCompressModelPickerState(
    selectedProviderId: String?,
    selectedModelId: String?,
): AgentModelPickerUiState {
    val providers = runCatching { ProviderRepository.allProviders() }.getOrNull() ?: emptyList()
    return AgentModelPickerProjector.project(
        providers = providers,
        selectedProviderId = selectedProviderId,
        selectedModelId = selectedModelId,
    )
}

internal suspend fun readCompressModelSelection(prefs: SharedPreferences): AgentModelOptionUi? {
    return buildCompressModelPickerState(prefs).selectedModel
}
