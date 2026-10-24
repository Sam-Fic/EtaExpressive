package io.github.mangi.eta.ui.screens.assistants

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryStore
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.components.AssistantAvatar
import io.github.mangi.eta.ui.components.rememberAssistantAvatarBitmap
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.screens.skills.SkillSwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.material3.Text as M3Text

@Composable
internal fun AssistantEditScreen(
    assistantId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original = remember(assistantId) { AssistantRepository.profile(assistantId) }
    if (original == null) {
        LaunchedEffect(assistantId) { onBack() }
        return
    }
    var name by remember(assistantId) { mutableStateOf(original.name) }
    var prompt by remember(assistantId) { mutableStateOf(original.prompt) }
    var avatarFileName by remember(assistantId) { mutableStateOf(original.avatarFileName) }
    var memoryEnabled by remember(assistantId) { mutableStateOf(original.memoryEnabled) }
    var persistedMemoryEnabled by remember(assistantId) { mutableStateOf(original.memoryEnabled) }
    var enabledSkillIds by remember(assistantId) { mutableStateOf(original.enabledSkillIds.toSet()) }
    var persistedSkillIds by remember(assistantId) { mutableStateOf(original.enabledSkillIds.toSet()) }
    // 按助手的工具开关：与记忆/技能同级，缺失的组回落到全局开关。
    var toolSwitches by remember(assistantId) { mutableStateOf(original.toolGroupSwitches) }
    var persistedToolSwitches by remember(assistantId) { mutableStateOf(original.toolGroupSwitches) }
    var memoryDraft by remember(assistantId) { mutableStateOf("") }
    var memoryRevision by remember(assistantId) { mutableStateOf("") }
    var memorySaved by remember(assistantId) { mutableStateOf("") }
    var installedSkills by remember(assistantId) { mutableStateOf<List<SkillIndexEntry>>(emptyList()) }
    var cropBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    val avatarBitmap = rememberAssistantAvatarBitmap(assistantId, avatarFileName)
    val memoryBytes = remember(memoryDraft) { memoryDraft.toByteArray(Charsets.UTF_8).size }
    val memoryOverLimit = memoryBytes > AgentMemoryStore.MAX_FILE_BYTES
    val dirty = name.trim() != original.name ||
        prompt != original.prompt ||
        avatarFileName != original.avatarFileName ||
        memoryEnabled != persistedMemoryEnabled ||
        enabledSkillIds != persistedSkillIds ||
        toolSwitches != persistedToolSwitches ||
        memoryDraft != memorySaved

    LaunchedEffect(assistantId) {
        val loaded = withContext(Dispatchers.IO) {
            val snapshot = runCatching { AgentMemoryRepository.snapshot(assistantId) }.getOrNull()
            val skills = runCatching {
                SkillRuntime.createIndexService(context.applicationContext)
                    .listSkillsForManagement()
                    .filter { it.installed }
            }.getOrDefault(emptyList())
            snapshot to skills
        }
        memoryDraft = loaded.first?.content.orEmpty()
        memorySaved = loaded.first?.content.orEmpty()
        memoryRevision = loaded.first?.revision.orEmpty()
        installedSkills = loaded.second
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        cropBitmap = decodeAvatarBitmap(context, uri)
    }
    val pickAvatar = {
        picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    fun persistAssistantToggles(
        nextMemory: Boolean = memoryEnabled,
        nextSkills: Set<String> = enabledSkillIds,
        nextTools: Map<String, Boolean> = toolSwitches,
    ) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val current = requireNotNull(AssistantRepository.currentProfile(assistantId)) { context.getString(R.string.aes_assistant_missing) }
                AssistantRepository.update(current.copy(
                    memoryEnabled = nextMemory,
                    enabledSkillIds = nextSkills.toList(),
                    toolGroupSwitches = nextTools,
                ))
            } }
            if (result.isSuccess) {
                persistedMemoryEnabled = nextMemory
                persistedSkillIds = nextSkills
                persistedToolSwitches = nextTools
            } else {
                android.widget.Toast.makeText(context, result.exceptionOrNull()?.message ?: context.getString(R.string.aes_skill_catalog_publish_failed), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    fun save() {
        if (!dirty || saving || memoryOverLimit || memoryRevision.isBlank()) return
        val targetMemory = memoryDraft
        val baseRevision = memoryRevision
        val targetProfile = original.copy(name = name, prompt = prompt, avatarFileName = avatarFileName,
            memoryEnabled = memoryEnabled, enabledSkillIds = enabledSkillIds.toList(),
            toolGroupSwitches = toolSwitches)
        val memoryChanged = targetMemory != memorySaved
        saving = true
        scope.launch {
            var savedSnapshot: io.github.mangi.eta.data.repository.AgentMemorySnapshot? = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (memoryChanged) savedSnapshot = AgentMemoryRepository.replaceAll(targetMemory, assistantId, baseRevision)
                    AssistantRepository.update(targetProfile)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                }
            }
            savedSnapshot?.let { snapshot ->
                memoryRevision = snapshot.revision
                memorySaved = snapshot.content
            }
            saving = false
            if (result.isSuccess) onBack()
            else android.widget.Toast.makeText(context, result.exceptionOrNull()?.message ?: context.getString(R.string.aes_save_failed_draft_kept), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val saveEnabled = dirty && !saving && !memoryOverLimit
    EtaScaffoldPage(
        title = stringResource(R.string.assistant_edit_title),
        onBack = onBack,
        actions = {
            // 官方 TextButton（自带涟漪与禁用态）。
            M3TextButton(onClick = ::save, enabled = saveEnabled) {
                M3Text(text = stringResource(R.string.action_save))
            }
        },
    ) {
        item(key = "preview") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                title = stringResource(R.string.assistant_preview),
            ) {
                item {
                    EtaSegmentedItemContainer {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // 官方可点击 Surface，自带涟漪与按压语义。
                            Surface(
                                onClick = pickAvatar,
                                shape = CircleShape,
                                modifier = Modifier.size(56.dp),
                            ) {
                                AssistantAvatar(bitmap = avatarBitmap, size = 56.dp)
                            }
                            M3Text(
                                text = name.trim().ifBlank { stringResource(R.string.assistant_unnamed) },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
        }
        item(key = "identity") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                title = stringResource(R.string.assistant_identity),
            ) {
                item(surface = false) {
                    // KernelSU 式无边框输入行，直接作为分段行（与其它设置二级页一致）。
                    AssistantField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.assistant_name),
                    )
                }
            }
        }
        item(key = "prompt") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                title = stringResource(R.string.assistant_prompt_title),
            ) {
                item(surface = false) {
                    AssistantField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = "",
                        singleLine = false,
                        minLines = 8,
                        maxLines = 14,
                    )
                }
            }
        }
        item(key = "memory") {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            ) {
                val memoryEnabledFlow = remember { AgentMemoryRepository.enabledFlow() }
                val globalMemoryEnabled by memoryEnabledFlow.collectAsState(
                    initial = AgentMemoryRepository.isGloballyEnabled(),
                )
                EtaSegmentedColumn(
                    // 开关卡与下方的 MEMORY.md 编辑框是两种不同表面：按 M3E 的元素间距留 8dp，
                    // 不把编辑框挤进分段列表的 2dp 接缝里。
                    modifier = Modifier.padding(bottom = EtaDimens.SpaceSm),
                    title = stringResource(R.string.ui_memory_b55ff5),
                ) {
                    item {
                        EtaSwitchRow(
                            title = stringResource(R.string.ui_enable_memory_4b69b7),
                            summary = buildString {
                                append(stringResource(R.string.assistant_memory_summary))
                                if (!globalMemoryEnabled) {
                                    append('\n')
                                    append(stringResource(R.string.memory_global_off_short))
                                }
                            },
                            checked = memoryEnabled,
                            onCheckedChange = {
                                memoryEnabled = it
                                persistAssistantToggles(nextMemory = it)
                            },
                            enabled = !saving,
                        )
                    }
                }
                // 按助手的工具开关：只能收紧全局能力，关闭的组对这个助手不下发。
                EtaSegmentedColumn(
                    modifier = Modifier.padding(bottom = EtaDimens.SpaceSm),
                    title = stringResource(R.string.aes_tool_groups_title),
                ) {
                    io.github.mangi.eta.agent.tool.AssistantToolSwitches.all.forEach { group ->
                        item(key = "tool-$group") {
                            val globalEnabled = io.github.mangi.eta.agent.tool.UiToolGroupLabel
                                .globallyEnabled(group)
                            EtaSwitchRow(
                                title = io.github.mangi.eta.agent.tool.UiToolGroupLabel.label(group),
                                summary = if (globalEnabled) "" else
                                    stringResource(R.string.aes_tool_group_globally_off),
                                checked = io.github.mangi.eta.agent.tool.AssistantToolSwitches
                                    .allows(original.copy(toolGroupSwitches = toolSwitches), group, globalEnabled),
                                onCheckedChange = { enabled ->
                                    val next = toolSwitches + (group to enabled)
                                    toolSwitches = next
                                    persistAssistantToggles(nextTools = next)
                                },
                                enabled = !saving && globalEnabled,
                            )
                        }
                    }
                }
                // MEMORY.md 超限时用支持文案（supportingContent）承载错误提示。
                val memorySupporting: (@Composable () -> Unit)? = if (memoryOverLimit) {
                    {
                        M3Text(
                            text = stringResource(R.string.memory_over_limit),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    null
                }
                AssistantField(
                    value = memoryDraft,
                    onValueChange = { memoryDraft = it },
                    label = "MEMORY.md",
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    minLines = 6,
                    maxLines = 12,
                    monospace = true,
                    isError = memoryOverLimit,
                    supportingContent = memorySupporting,
                )
            }
        }
        item(key = "skills") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                title = stringResource(R.string.route_skills),
            ) {
                item {
                    EtaSegmentedItemContainer {
                        M3Text(
                            text = stringResource(R.string.assistant_skills_summary),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        if (installedSkills.isEmpty()) {
                            M3Text(
                                text = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
                if (installedSkills.isNotEmpty()) {
                    installedSkills.forEach { skill ->
                        item {
                            SkillSwitchRow(
                                skill = skill.toSwitcherUi(enabledSkillIds.contains(skill.id)),
                                enabled = !saving,
                                showMenu = false,
                                onToggle = { enabled ->
                                    val next = if (enabled) {
                                        enabledSkillIds + skill.id
                                    } else {
                                        enabledSkillIds - skill.id
                                    }
                                    enabledSkillIds = next
                                    persistAssistantToggles(nextSkills = next)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    cropBitmap?.let { bitmap ->
        AvatarCropDialog(
            bitmap = bitmap,
            onCancel = { cropBitmap = null },
            onConfirm = { cropped ->
                cropBitmap = null
                scope.launch {
                    val updated = withContext(Dispatchers.IO) {
                        AssistantRepository.saveAvatar(assistantId, cropped)
                    }
                    avatarFileName = updated.avatarFileName
                    if (cropped !== bitmap) cropped.recycle()
                    bitmap.recycle()
                }
            },
        )
    }
}

/**
 * 助手表单输入框。
 *
 * 走官方 [OutlinedTextField]：自带描边圆角与浮动标签，直接落在页面背景上。
 */
@Composable
private fun AssistantField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    useLabelAsPlaceholder: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    monospace: Boolean = false,
    isError: Boolean = false,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    // 空标签不占位：多行正文框（Personality prompt）直接以描边框呈现。
    val floatingLabel: (@Composable () -> Unit)? = if (useLabelAsPlaceholder || label.isEmpty()) {
        null
    } else {
        { M3Text(text = label) }
    }
    val placeholderText: (@Composable () -> Unit)? = if (useLabelAsPlaceholder) {
        { M3Text(text = label) }
    } else {
        null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = floatingLabel,
        placeholder = placeholderText,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyLarge
        },
        isError = isError,
        supportingText = supportingContent,
    )
}

private fun SkillIndexEntry.toSwitcherUi(enabled: Boolean): SkillItemUi = SkillItemUi(
    id = id,
    name = name,
    description = description,
    source = source,
    enabled = enabled,
    installed = installed,
    capabilities = buildList {
        if (hasScripts) add("scripts")
        if (hasReferences) add("references")
        if (hasAssets) add("assets")
        if (hasEvals) add("evals")
    },
)
