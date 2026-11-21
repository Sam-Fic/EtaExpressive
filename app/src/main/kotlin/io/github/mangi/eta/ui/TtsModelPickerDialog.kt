package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaPickerSheet
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.components.filterModelProviderGroups
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelPickerUiState

/**
 * 模型选择面板（辅助视觉模型 / 标题模型 / TTS / 子代理等处共用）。
 *
 * 列表按提供商分组平铺，组内为单选行（KernelSU「Select KMI」式）。选中只改变面板内的待确认
 * 状态，需再点「确认」才通过 [onModelSelected]（或 [onClearSelection]）提交，「取消」则直接关闭。
 *
 * @param onClearSelection 非空时在列表顶部提供「无」选项；确认「无」时回调它。
 * @param highlightSelection 仅为兼容既有调用方保留；新设计统一由单选行呈现选中态，此参数不再生效。
 */
@Composable
internal fun TtsModelPickerDialog(
    state: AgentModelPickerUiState,
    show: Boolean,
    onDismiss: () -> Unit,
    onModelSelected: (String, String) -> Unit,
    title: String,
    onClearSelection: (() -> Unit)? = null,
    highlightSelection: Boolean = false,
) {
    if (!show) return
    // 面板内的「待确认」选择：先选中，再点确认提交；取消直接丢弃。
    var query by remember(show) { mutableStateOf("") }
    var pendingProviderId by remember { mutableStateOf(state.selectedModel?.providerId) }
    var pendingModelId by remember { mutableStateOf(state.selectedModel?.id) }
    val hasPending = pendingModelId != null
    val confirmEnabled = hasPending || onClearSelection != null
    val groups = remember(state.providerGroups, query) {
        filterModelProviderGroups(state.providerGroups, query.trim())
    }
    val view = LocalView.current

    EtaPickerSheet(
        title = title,
        onDismiss = onDismiss,
        query = query,
        onQueryChange = { query = it },
        searchPlaceholder = stringResource(R.string.ui_search_model_df5586),
        actions = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
            TextButton(
                enabled = confirmEnabled,
                onClick = {
                    TouchHaptics.click(view)
                    if (hasPending) {
                        onModelSelected(pendingProviderId.orEmpty(), pendingModelId.orEmpty())
                    } else {
                        onClearSelection?.invoke()
                    }
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
            verticalArrangement = Arrangement.spacedBy(EtaSegmentedGroupSpacing),
        ) {
            if (onClearSelection != null && query.isBlank()) {
                EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    item(key = "none") {
                        ModelPickerCheckRow(
                            title = stringResource(R.string.tts_none),
                            selected = !hasPending,
                            onClick = {
                                pendingProviderId = null
                                pendingModelId = null
                            },
                        )
                    }
                }
            }
            if (groups.isEmpty()) {
                Text(
                    text = stringResource(
                        if (state.providerGroups.isEmpty()) R.string.provider_empty
                        else R.string.page_no_matching_model_found_ae7e96,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            groups.forEach { group ->
                EtaSegmentedColumn(
                    modifier = Modifier.fillMaxWidth(),
                    title = group.providerName,
                ) {
                    group.models.forEach { model ->
                        item(key = "${model.providerId}/${model.id}") {
                            ModelPickerCheckRow(
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

/**
 * 模型选择弹窗中的可选项（对勾版）：选中行行尾显示对勾，
 * 选中态由整行底色 + 对勾共同承担（分段行外观与轻触反馈由 [EtaSegmentedRow] 提供）。
 *
 * 底部面板里逐行放左侧圆点过重，模型选择类弹窗（[TtsModelPickerDialog]、
 * 上下文压缩的模型选择）与输入区「Model & assistant」面板统一使用这一版。
 */
@Composable
internal fun ModelPickerCheckRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    // 行尾短标签：输入区弹窗用它标出「本轮」与「下一轮」生效的目标。
    badge: String? = null,
) {
    // 显式类型：if 分支里的可组合 lambda 需要标注，避免推断落到 Nothing?。
    val trailing: (@Composable () -> Unit)? = if (selected) {
        { Icon(imageVector = Icons.Rounded.Check, contentDescription = null) }
    } else {
        null
    }
    EtaSegmentedRow(
        selected = selected,
        choiceRow = true,
        onClick = onClick,
        trailingContent = trailing,
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = title)
                if (badge != null) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
    )
}

internal data class SpeechVoiceSections(
    val female: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
    val male: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
    val other: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
    val personal: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
)

internal fun groupedSpeechVoices(
    voices: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
): SpeechVoiceSections {
    val personal = voices.filter { it.personal }
    val publicVoices = voices.filter { !it.personal }
    return SpeechVoiceSections(
        female = publicVoices.filter { "_female_" in it.id },
        male = publicVoices.filter { "_male_" in it.id },
        other = publicVoices.filter { "_female_" !in it.id && "_male_" !in it.id },
        personal = personal,
    )
}

@Composable
internal fun TtsVoicePickerDialog(
    show: Boolean,
    voices: List<io.github.mangi.eta.agent.voice.tts.SpeechVoice>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
    title: String,
) {
    if (!show) return
    var query by remember(show) { mutableStateOf("") }
    val visible = remember(voices, query) {
        val needle = query.trim()
        if (needle.isEmpty()) voices
        else voices.filter { it.name.contains(needle, ignoreCase = true) || it.id.contains(needle, ignoreCase = true) }
    }
    val sections = remember(visible) { groupedSpeechVoices(visible) }
    EtaPickerSheet(
        title = title,
        onDismiss = onDismiss,
        query = query,
        onQueryChange = { query = it },
        searchPlaceholder = stringResource(R.string.tts_search_voice),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
        ) {
            if (visible.isEmpty() && voices.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.tts_no_matching_voice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sections.female.isNotEmpty()) {
                VoiceSectionTitle(stringResource(R.string.tts_voice_female))
                sections.female.forEach { VoiceRow(it, selectedId, onSelected) }
            }
            if (sections.male.isNotEmpty()) {
                VoiceSectionTitle(stringResource(R.string.tts_voice_male))
                sections.male.forEach { VoiceRow(it, selectedId, onSelected) }
            }
            sections.other.forEach { VoiceRow(it, selectedId, onSelected) }
            if (sections.personal.isNotEmpty()) {
                VoiceSectionTitle(stringResource(R.string.tts_voice_personal))
                sections.personal.forEach { VoiceRow(it, selectedId, onSelected) }
            }
        }
    }
}

@Composable
internal fun SpeechRadioPickerDialog(
    show: Boolean,
    title: String,
    rows: List<Pair<String, String>>,
    selectedId: String,
    emptyText: String = "",
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    if (!show) return
    var query by remember(show) { mutableStateOf("") }
    val visible = remember(rows, query) {
        val needle = query.trim()
        if (needle.isEmpty()) rows
        else rows.filter { (id, label) ->
            id.contains(needle, ignoreCase = true) || label.contains(needle, ignoreCase = true)
        }
    }
    EtaPickerSheet(
        title = title,
        onDismiss = onDismiss,
        query = query,
        onQueryChange = { query = it },
        searchPlaceholder = stringResource(R.string.speech_search_option),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .selectableGroup(),
        ) {
            if (visible.isEmpty()) {
                Text(if (rows.isEmpty()) emptyText else stringResource(R.string.ui_no_matching_options))
            }
            visible.forEach { (id, label) -> SpeechRadioRow(id, label, id == selectedId, onSelected) }
        }
    }
}

@Composable
private fun SpeechRadioRow(id: String, label: String, selected: Boolean, onSelected: (String) -> Unit) {
    val view = LocalView.current
    // 官方单选版 ListItem 提供选中容器色、Role.RadioButton 语义与按压形变；
    // 底部面板的选择指示器统一放行尾对勾，避免每行左侧都挂一个圆点。
    val trailing: (@Composable () -> Unit)? = if (selected) {
        { Icon(imageVector = Icons.Rounded.Check, contentDescription = null) }
    } else {
        null
    }
    ListItem(
        selected = selected,
        onClick = { TouchHaptics.click(view); onSelected(id) },
        // 播报文本由官方合并 content 槽位的 Text 提供，不额外挂 contentDescription。
        modifier = Modifier.fillMaxWidth(),
        trailingContent = trailing,
        content = { Text(label) },
    )
}

@Composable
private fun VoiceSectionTitle(text: String) {
    // 官方 typography 令牌 + colorScheme.onSurfaceVariant。
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun VoiceRow(
    voice: io.github.mangi.eta.agent.voice.tts.SpeechVoice,
    selectedId: String,
    onSelected: (String) -> Unit,
) {
    val view = LocalView.current
    val selected = voice.id == selectedId
    // 官方单选版 ListItem 提供选中容器色、Role.RadioButton 语义与按压形变；
    // 选择指示器与模型面板一致，取行尾对勾。
    val trailing: (@Composable () -> Unit)? = if (selected) {
        { Icon(imageVector = Icons.Rounded.Check, contentDescription = null) }
    } else {
        null
    }
    ListItem(
        selected = selected,
        onClick = { TouchHaptics.click(view); onSelected(voice.id) },
        // 播报文本由官方合并 content 槽位的 Text 提供，不额外挂 contentDescription。
        modifier = Modifier.fillMaxWidth(),
        trailingContent = trailing,
        content = { Text(voice.name) },
    )
}
