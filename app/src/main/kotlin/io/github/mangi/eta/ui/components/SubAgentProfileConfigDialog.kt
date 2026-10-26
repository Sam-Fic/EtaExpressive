package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Assignment
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ImageResolutionTier
import io.github.mangi.eta.agent.model.MediaReasoningSettings
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.supportsGptSpeedBinding
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.TtsModelPickerDialog
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaPageSurface
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedScope
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One opening owns one editor, application, expected profile and parallel-pool snapshot.
 * Nothing here writes preferences until commitProfileDraft; abandoning a session is zero-write. */
internal class SubAgentProfileDraftSession private constructor(
    val editor: ConversationSubAgentEditor,
    val expectedProfile: SubAgentProfile?,
    val expectedApplicationToken: String?,
    private val openedConfig: ConversationSubAgentConfig,
    initialDraft: SubAgentProfile,
) {
    val owner = editor.owner
    val openToken = Any()
    private var active = true
    var draft by mutableStateOf(initialDraft)
        private set
    var name by mutableStateOf(initialDraft.name)
    var parallelValue by mutableStateOf("")
        private set
    private var parallelEdited by mutableStateOf(false)
    private var bindingChanged = false
    private var parallelModel by mutableStateOf<SubAgentParallelModel?>(null)
    var submitting by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    fun dismiss() { active = false }
    fun isCurrent(currentEditor: ConversationSubAgentEditor?, allowed: Boolean): Boolean {
        if (!active || !allowed || editor.isDisposed || currentEditor !== editor || currentEditor?.owner != owner) return false
        // A durability failure is recoverable in this form; never discard its draft just for Error.
        val current = (editor.state as? SubAgentEditorState.Loaded)?.config ?: return true
        return editor.canEdit() && current.presetApplicationToken == expectedApplicationToken
    }
    fun canCommit(currentEditor: ConversationSubAgentEditor?, allowed: Boolean, ticket: Any = openToken): Boolean =
        ticket === openToken && isCurrent(currentEditor, allowed) && editor.enabled

    fun edit(change: (SubAgentProfile) -> SubAgentProfile) {
        val old = draft
        draft = change(old)
        if (old.providerId != draft.providerId || old.modelId != draft.modelId) {
            bindingChanged = true
            resetParallel()
        }
    }
    fun selectModel(selection: ModelFeatureSelection) {
        // This read-only API restores LAST CONFIRMED complete settings. Never cache local A/B edits.
        edit { editor.changeProfileModel(it, selection) }
    }
    private fun resetParallel() {
        parallelEdited = false
        parallelModel = null
        parallelValue = editor.rememberedParallelLimit(draft)?.toString().orEmpty()
    }
    fun bindParallel(config: AgentModelClient.ModelConfig?) {
        val next = config?.takeIf { it.providerId.isNotBlank() && it.model.isNotBlank() }
            ?.let { SubAgentParallelModel(it.providerId, it.model) }
        if (next != parallelModel) {
            parallelModel = next
            val originalBinding = expectedProfile != null && !bindingChanged &&
                draft.providerId == expectedProfile.providerId && draft.modelId == expectedProfile.modelId
            // Existing unchanged bindings show the actual owner pool. New/rebound bindings restore
            // confirmed memory as a pending pool write; null change must mean truly untouched.
            val restored = if (next != null && !originalBinding) editor.rememberedParallelLimit(draft) else null
            parallelEdited = restored != null
            parallelValue = if (next == null) "" else
                (restored ?: openedConfig.parallelLimit(next)).toString()
        }
    }
    fun editParallel(value: String) { parallelValue = value; parallelEdited = true }
    fun parallelChange(): SubAgentParallelLimitChange? {
        if (!parallelEdited) return null
        val model = parallelModel ?: return null
        val number = parallelValue.trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return SubAgentParallelLimitChange(model, number, openedConfig.parallelLimit(model))
    }
    fun parallelBoundTo(config: AgentModelClient.ModelConfig?): Boolean = config == null ||
        parallelModel == SubAgentParallelModel(config.providerId, config.model)
    fun parallelIsValid(): Boolean = !parallelEdited ||
        (parallelModel != null && parallelValue.trim().toIntOrNull()?.let { it >= 0 } == true)

    companion object {
        fun open(editor: ConversationSubAgentEditor, expectedProfile: SubAgentProfile? = null): SubAgentProfileDraftSession? {
            if (!editor.enabled) return null
            val config = (editor.state as? SubAgentEditorState.Loaded)?.config ?: return null
            if (expectedProfile != null && config.profiles.singleOrNull { it.id == expectedProfile.id } != expectedProfile) return null
            return SubAgentProfileDraftSession(editor, expectedProfile, config.presetApplicationToken,
                config.detached(), expectedProfile ?: editor.newProfileDraft())
        }
    }
}

/** Read-only resolution outside any editor transaction. No unsupported role gets a replacement model. */
internal fun resolveSubAgentProfileConfig(profile: SubAgentProfile, providers: List<ProviderSetting>): AgentModelClient.ModelConfig? {
    val provider = providers.singleOrNull { it.id == profile.providerId && it.isEnabled } ?: return null
    val model = provider.models.singleOrNull { it.id == profile.modelId && it.isEnabled } ?: return null
    if (model.supportsSpeechSynthesis || !profile.acceptsModel(model.supportsImageGeneration, model.supportsVideoGeneration)) return null
    return runCatching { RuntimeConfigRepository.buildRuntimeConfig(provider, model) }.getOrNull()
}

private class SubAgentResolvedDraftBinding(val providerId: String, val modelId: String, val role: String,
    val config: AgentModelClient.ModelConfig?)

/** 预设分组之间的垂直间距，与兄弟设置页的分段分组节奏一致。 */
private val ProfileDialogGroupSpacing = 13.dp

/**
 * 分段卡片条目。
 *
 * 本页是 [EtaPageSurface] 的整屏弹窗，比分段行所在的普通页面多一层窗口：分段行自带的容器色
 * 在这里不会绘制出来，行会直接贴在页面上（预设组详情页不套弹窗，所以那边有卡片底板）。
 * 与工具配方编辑页同样的做法，显式套一层 [EtaSegmentedItemContainer]，由容器负责
 * `surfaceBright` 底板与首末外圆角，行本身转为透明，两处外观由此一致。
 */
private fun EtaSegmentedScope.cardItem(key: Any?, content: @Composable () -> Unit) {
    item(key = key) {
        EtaSegmentedItemContainer(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
internal fun SubAgentProfileConfigDialog(
    session: SubAgentProfileDraftSession,
    currentEditor: ConversationSubAgentEditor?,
    providers: List<ProviderSetting>,
    enabled: Boolean = true,
    onDismiss: () -> Unit,
    onRetry: () -> Unit = { session.editor.retry() },
) {
    val latestEditor by rememberUpdatedState(currentEditor)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val current = session.isCurrent(currentEditor, enabled)
    DisposableEffect(session) { onDispose { session.dismiss() } }
    LaunchedEffect(session, current) {
        if (!current) { session.dismiss(); latestDismiss() }
    }
    if (!current) return
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    val draft = session.draft
    val usable = session.canCommit(currentEditor, enabled) && !session.submitting
    var modelPicker by remember(session) { mutableStateOf(false) }
    // 与预设组详情页同款：并行上限是「取值行 + 箭头 → 编辑弹窗」，不再在卡片里内联输入。
    var parallelEditor by remember(session) { mutableStateOf(false) }
    // 名称同样只在标题行展示，改名走与预设组详情页一致的重命名弹窗。
    var nameEditor by remember(session) { mutableStateOf(false) }
    // 模型面板与草稿共用一套投影：只列出该角色可接受的模态模型，供应商分组保持原顺序。
    val modelPickerState = remember(providers, draft.providerId, draft.modelId, draft.role) {
        AgentModelPickerProjector.project(providers, draft.providerId, draft.modelId).let { projected ->
            projected.copy(providerGroups = projected.providerGroups.map { group ->
                group.copy(models = group.models.filter { draft.acceptsModel(it.supportsImageGeneration, it.supportsVideoGeneration) })
            }.filter { it.models.isNotEmpty() })
        }
    }
    // Resolve outside the write lock. A produceState result must carry its original selection:
    // API-model aliases must not make a stale selection look like the current draft binding.
    val resolved by produceState<SubAgentResolvedDraftBinding?>(null, draft.providerId, draft.modelId, draft.role, providers) {
        value = SubAgentResolvedDraftBinding(draft.providerId, draft.modelId, draft.role,
            resolveSubAgentProfileConfig(draft, providers))
    }
    val boundConfig = resolved?.takeIf { it.providerId == draft.providerId &&
        it.modelId == draft.modelId && it.role == draft.role }?.config
    val resolutionPending = draft.providerId.isNotBlank() && draft.modelId.isNotBlank() &&
        (resolved == null || resolved?.providerId != draft.providerId || resolved?.modelId != draft.modelId || resolved?.role != draft.role)
    LaunchedEffect(session, draft.providerId, draft.modelId, draft.role, resolutionPending, boundConfig?.providerId, boundConfig?.model) {
        // Do not discard a manual/pending pool edit during a transient lookup of the same binding.
        if (!resolutionPending) session.bindParallel(boundConfig)
    }
    LaunchedEffect(usable) { if (!usable) { modelPicker = false; parallelEditor = false; nameEditor = false } }
    // Require the resolved role-compatible binding AND the selected record's actual GPT capability.
    // The preference key is the selection ID, never boundConfig.model (an API alias).
    val speedProvider = providers.singleOrNull { it.id == draft.providerId && it.isEnabled }
    val speedModel = speedProvider?.models?.singleOrNull { it.id == draft.modelId && it.isEnabled }
    val canEditSpeed = boundConfig != null && supportsGptSpeedBinding(speedProvider, speedModel)
    val media = if (draft.isMedia && boundConfig != null) MediaReasoningSettings.resolve(boundConfig, draft.role) else null
    val canThink = boundConfig != null && (!draft.isMedia || media?.status == MediaReasoningSettings.Status.SUPPORTED)
    val effective = boundConfig?.let { SubAgentPreferences.applyReasoning(draft, it).effectiveReasoningEffort }
    val efforts = if (draft.isMedia) media?.efforts.orEmpty() else boundConfig?.reasoningCapabilities?.selectableEfforts.orEmpty()
    val thinkingLabel = when {
        boundConfig == null -> stringResource(R.string.sap_no_valid_model)
        !canThink -> media?.let { stringResource(it.labelRes) } ?: stringResource(R.string.sap_endpoint_unsupported)
        draft.isMedia && effective !in efforts -> stringResource(R.string.sap_reselect_tier)
        else -> effective?.let { stringResource(it.labelRes) } ?: stringResource(R.string.sap_not_enabled)
    }
    val verifiedGrok = boundConfig?.let { runtime -> runCatching {
        val body = if (runtime.extraBodyJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(runtime.extraBodyJson)
        io.github.mangi.eta.agent.model.RequestBodyMerge.mergeCustomBody(body, runtime.customBody)
        io.github.mangi.eta.agent.model.GrokImageProfile.applies(runtime.baseUrl, runtime.model, body)
    }.getOrDefault(false) } ?: false
    val ready = usable && !resolutionPending && session.parallelBoundTo(boundConfig) &&
        session.name.trim().isNotBlank() && session.parallelIsValid() &&
        (session.expectedProfile != null || boundConfig != null)
    val dismiss = { session.dismiss(); latestDismiss() }
    // Back/outside taps cannot abandon an in-flight commit. Saved/owner invalidation still close.
    val userDismiss = { if (!session.submitting) dismiss() }
    val pageTitle = if (session.expectedProfile == null) stringResource(R.string.sap_add_profile)
        else stringResource(R.string.sap_profile_config)
    val formScroll = rememberScrollState()
    CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration()) {
        WithoutPressRipple {
            EtaPageSurface(onDismissRequest = userDismiss) {
                EtaScaffold(
                    title = pageTitle,
                    onBack = userDismiss,
                    modifier = Modifier.fillMaxSize().imePadding(),
                    bottomBar = {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                            EtaDialogActions(
                                modifier = Modifier.navigationBarsPadding().imePadding()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                confirmText = stringResource(R.string.action_confirm),
                                cancelText = stringResource(R.string.action_cancel),
                                confirmEnabled = ready && !session.submitting,
                                cancelEnabled = !session.submitting,
                                onCancel = { userDismiss() },
                                onConfirm = {
                                    if (ready && !session.submitting && session.canCommit(latestEditor, latestEnabled)) {
                                        // Snapshot every argument before suspension; callbacks never retarget another opening.
                                        val capturedDraft = session.draft.copy(name = session.name.trim())
                                        val capturedTicket = session.openToken
                                        val capturedExpected = session.expectedProfile
                                        val capturedApplication = session.expectedApplicationToken
                                        val capturedParallel = session.parallelChange()
                                        session.submitting = true
                                        session.error = null
                                        scope.launch {
                                            try {
                                                val result = session.editor.commitProfileDraft(capturedDraft, capturedExpected,
                                                    capturedApplication, capturedParallel, canCommit = {
                                                        session.canCommit(latestEditor, latestEnabled, capturedTicket)
                                                    })
                                                if (result is ConversationSubAgentPreferences.WriteResult.Saved &&
                                                    session.isCurrent(latestEditor, latestEnabled)) {
                                                    TouchHaptics.click(view)
                                                    dismiss()
                                                } else if (session.isCurrent(latestEditor, latestEnabled)) {
                                                    session.error = context.getString(R.string.sap_not_saved)
                                                }
                                            } catch (cancelled: CancellationException) { throw cancelled }
                                              catch (_: Exception) { session.error = context.getString(R.string.sap_save_failed) }
                                            finally { session.submitting = false }
                                        }
                                    }
                                },
                            )
                        }
                    },
                ) { padding, scrollConnection, _ ->
                    Column(
                        Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                            .nestedScroll(scrollConnection)
                            .verticalScroll(formScroll)
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 16.dp),
                    ) {
                        // 分段列表：名称、启用开关与各设置行同处一组，首末项自动获得外圆角、行间留缝。
                        // 名称与并行上限不再单独摆描边输入框，避免同一页混用「卡片行」与「表单字段」两种形态。
                        EtaSegmentedColumn(
                            modifier = Modifier.fillMaxWidth().padding(bottom = ProfileDialogGroupSpacing),
                        ) {
                            // 与预设组详情页的标题行逐像素同款：同一套 EtaSegmentedRow（choiceRow）
                            // + 只读名称正文 + 行尾 Expressive 开关；改名为点击整行打开重命名弹窗。
                            cardItem(key = "header") {
                                EtaSegmentedRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = usable,
                                    choiceRow = true,
                                    onClick = { if (usable) nameEditor = true },
                                    leadingContent = {
                                        // 不显式 tint：让官方行内容色在禁用态把图标一并压暗。
                                        Icon(
                                            imageVector = when (draft.role) {
                                                "review" -> Icons.AutoMirrored.Rounded.FactCheck
                                                "image_generation" -> Icons.Rounded.Image
                                                "video_generation" -> Icons.Rounded.Videocam
                                                else -> Icons.Rounded.AccountTree
                                            },
                                            contentDescription = null,
                                        )
                                    },
                                    trailingContent = {
                                        EtaSwitch(
                                            checked = draft.enabled,
                                            enabled = usable,
                                            modifier = Modifier.semantics {
                                                contentDescription = context.getString(R.string.sas_enable_for, session.name)
                                            },
                                            onCheckedChange = { active ->
                                                session.edit { it.copy(enabled = active) }
                                            },
                                        )
                                    },
                                ) {
                                    Text(
                                        text = session.name.ifBlank { stringResource(R.string.sap_name) },
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            cardItem(key = "model") {
                                SubAgentSettingsRow(stringResource(R.string.sap_model),
                                    boundConfig?.let { it.modelDisplayName.ifBlank { it.model } }
                                        ?: if (draft.modelId.isBlank()) stringResource(R.string.sap_select_model) else stringResource(R.string.sap_model_unavailable),
                                    Icons.Rounded.ViewInAr, stringResource(R.string.sap_draft_model), usable,
                                    badge = boundConfig?.providerName,
                                    onClick = { if (usable) modelPicker = true })
                            }
                            if (canEditSpeed) cardItem(key = "speed") {
                                SubAgentSettingsRow(stringResource(R.string.sap_gpt_speed), gptSpeedDisplayName(draft.gptSpeedForModel()),
                                    Icons.Rounded.Speed, stringResource(R.string.sap_draft_gpt_speed), usable, onClick = {
                                        session.edit { profile ->
                                            if (profile.providerId != draft.providerId || profile.modelId != draft.modelId || profile.role != draft.role) profile
                                            else profile.copy(gptSpeedByModel = profile.gptSpeedByModel +
                                                (SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId) to profile.gptSpeedForModel().next()))
                                        }
                                    })
                            }
                            cardItem(key = "role") {
                                // 与上下文压缩页「请求接口」行同款：原位弹出锚定菜单，点选即写入草稿。
                                val roles = listOf(
                                    "implementation" to stringResource(R.string.sap_role_impl),
                                    "review" to stringResource(R.string.sap_role_review),
                                    "image_generation" to stringResource(R.string.sap_role_image),
                                    "video_generation" to stringResource(R.string.sap_role_video),
                                )
                                EtaSegmentedDropdownRow(
                                    title = stringResource(R.string.sap_role),
                                    items = roles.map { it.second },
                                    selectedIndex = roles.indexOfFirst { it.first == draft.role },
                                    onItemSelected = { index ->
                                        val value: String? = roles.getOrNull(index)?.first
                                            ?: return@EtaSegmentedDropdownRow
                                        if (session.canCommit(latestEditor, latestEnabled)) {
                                            session.edit { profile -> profile.withRole(requireNotNull(value)) }
                                        }
                                    },
                                    icon = Icons.AutoMirrored.Rounded.Assignment,
                                    enabled = usable,
                                    description = stringResource(R.string.sap_draft_role),
                                )
                            }
                            if (draft.supportsTaskTier) cardItem(key = "tier") {
                                val tiers = listOf<SubAgentTaskTier?>(null) + SubAgentTaskTier.entries
                                EtaSegmentedDropdownRow(
                                    title = stringResource(R.string.sap_task_tier),
                                    items = tiers.map { tier ->
                                        tier?.let { stringResource(it.labelRes) } ?: stringResource(R.string.sap_tier_unset)
                                    },
                                    selectedIndex = tiers.indexOf(draft.tier),
                                    onItemSelected = { index ->
                                        if (index !in tiers.indices ||
                                            !session.canCommit(latestEditor, latestEnabled)
                                        ) return@EtaSegmentedDropdownRow
                                        val tier = tiers[index]
                                        session.edit { profile ->
                                            if (profile.supportsTaskTier) profile.copy(tier = tier) else profile
                                        }
                                    },
                                    icon = Icons.Rounded.AccountTree,
                                    enabled = usable,
                                    description = stringResource(R.string.sap_draft_tier),
                                )
                            }
                            if (draft.role == "image_generation") cardItem(key = "resolution") {
                                // 与职责/任务分工同款：原位弹出锚定菜单；Grok 端点只留低/高，但当前值始终保留以免取值显示错位。
                                val tierValues = (listOf<String?>(null) + ImageResolutionTier.values.filter {
                                    !verifiedGrok || it in setOf("low", "high") || it == draft.imageResolution
                                })
                                val tierLabels = tierValues.map { tier ->
                                    tier?.let(ImageResolutionTier::labelRes)?.let { stringResource(it) }
                                        ?: stringResource(R.string.sap_follow_endpoint)
                                }
                                EtaSegmentedDropdownRow(
                                    title = stringResource(R.string.sap_default_resolution),
                                    items = tierLabels,
                                    selectedIndex = tierValues.indexOf(draft.imageResolution),
                                    onItemSelected = { index ->
                                        val value: String? = if (index in tierValues.indices) tierValues[index]
                                            else return@EtaSegmentedDropdownRow
                                        if (session.canCommit(latestEditor, latestEnabled)) {
                                            session.edit { profile ->
                                                if (profile.role == "image_generation") profile.copy(imageResolution = value) else profile
                                            }
                                        }
                                    },
                                    icon = Icons.Rounded.Image,
                                    enabled = usable && boundConfig != null,
                                    description = stringResource(R.string.sap_draft_resolution),
                                )
                            }
                            cardItem(key = "thinking") {
                                // 当前值不在可选档位里（无模型/端点不支持/未启用/需重选）时用行上状态文本占位，
                                // 占位项不可写入，语义与原二级选择页的提示保持一致。
                                val matchedIndex = if (canThink) efforts.indexOf(effective) else -1
                                val effortLabels = efforts.map { effort -> stringResource(effort.labelRes) }
                                val items = if (matchedIndex >= 0) effortLabels
                                    else listOf(thinkingLabel) + effortLabels
                                EtaSegmentedDropdownRow(
                                    title = stringResource(R.string.sap_thinking),
                                    items = items,
                                    selectedIndex = if (matchedIndex >= 0) matchedIndex else 0,
                                    onItemSelected = { index ->
                                        val next = efforts.getOrNull(if (matchedIndex >= 0) index else index - 1)
                                        if (next != null && session.canCommit(latestEditor, latestEnabled)) {
                                            session.edit { it.copy(reasoning = next) }
                                        }
                                    },
                                    icon = Icons.Rounded.AutoAwesome,
                                    enabled = usable && canThink,
                                    description = stringResource(R.string.sap_draft_thinking),
                                )
                            }
                            // 与预设组详情页同款：行内只呈现生效值，点开二级弹窗再输入数字。
                            cardItem(key = "parallel") {
                                SubAgentSettingsRow(
                                    label = stringResource(R.string.sap_parallel_label),
                                    value = when {
                                        boundConfig == null && draft.modelId.isBlank() -> stringResource(R.string.sap_parallel_no_model)
                                        boundConfig == null -> stringResource(R.string.sap_model_unavailable)
                                        session.parallelValue.trim() == "0" -> stringResource(R.string.sap_parallel_unlimited)
                                        else -> session.parallelValue.ifBlank { "-" }
                                    },
                                    icon = Icons.AutoMirrored.Rounded.CallSplit,
                                    description = stringResource(R.string.sap_parallel_set_for, session.name),
                                    enabled = usable && boundConfig != null,
                                    onClick = { parallelEditor = true },
                                )
                            }
                        }
                        session.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                        val editorState = session.editor.state
                        if (editorState is SubAgentEditorState.Error) {
                            Text(stringResource(R.string.sap_config_error, editorState.reason), color = MaterialTheme.colorScheme.error)
                            TextButton(enabled = !session.submitting, onClick = onRetry) { Text(stringResource(R.string.sap_retry_restore_draft)) }
                        }
                    }
                }
            }
            // 模型选择走底部面板（与「标题模型」页同款），不再在弹窗内翻二级页。
            TtsModelPickerDialog(
                state = modelPickerState,
                show = modelPicker && usable,
                onDismiss = { modelPicker = false },
                onModelSelected = { providerId, modelId ->
                    modelPicker = false
                    if (session.canCommit(latestEditor, latestEnabled)) {
                        session.selectModel(ModelFeatureSelection(true, providerId, modelId))
                    }
                },
                title = stringResource(R.string.sap_select_model_for, draft.name),
                // 保留「无」选项：清除后表单退回未选择模型的草稿状态。
                onClearSelection = {
                    modelPicker = false
                    if (session.canCommit(latestEditor, latestEnabled)) {
                        session.selectModel(ModelFeatureSelection(true, "", ""))
                    }
                },
            )
            // 重命名弹窗：与预设组详情页的改名入口同一个组件，写入的仍是本页草稿。
            if (nameEditor && usable) {
                var editName by remember { mutableStateOf(session.name) }
                SubAgentPresetNameDialog(
                    title = stringResource(R.string.sas_rename_agent),
                    name = editName,
                    onNameChange = { if (usable) editName = it },
                    saveEnabled = editName.trim().isNotBlank(),
                    onDismiss = { nameEditor = false },
                    onSave = {
                        if (usable && editName.trim().isNotBlank()) {
                            session.name = editName.trim()
                            nameEditor = false
                        }
                    },
                    fieldHint = stringResource(R.string.sap_name),
                )
            }
            // 并行上限二级弹窗：与预设组详情页 SubAgentParallelLimitContent 同款布局与文案，
            // 区别仅在提交时机——这里改的是草稿，随本页「确认」一并写入。
            if (parallelEditor && usable && boundConfig != null) {
                var editValue by remember { mutableStateOf(session.parallelValue) }
                val number = editValue.trim().toIntOrNull()?.takeIf { it >= 0 }
                EtaDialog(
                    show = true,
                    title = stringResource(R.string.sap_parallel_dialog_title,
                        session.name.trim().ifBlank { pageTitle }),
                    onDismissRequest = { parallelEditor = false },
                    actions = {
                        EtaDialogActions(
                            confirmText = stringResource(R.string.action_save),
                            cancelText = stringResource(R.string.action_cancel),
                            confirmEnabled = number != null,
                            onCancel = { parallelEditor = false },
                            onConfirm = {
                                if (number != null) {
                                    session.editParallel(editValue)
                                    parallelEditor = false
                                }
                            },
                        )
                    },
                ) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${boundConfig.providerName} · ${boundConfig.modelDisplayName.ifBlank { boundConfig.model }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        EtaFormTextField(editValue, { if (usable) editValue = it },
                            hint = stringResource(R.string.sap_parallel_input_hint), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            enabled = usable, modifier = Modifier.fillMaxWidth())
                        if (number == null) Text(stringResource(R.string.sap_parallel_invalid), color = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.sap_parallel_shared_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
