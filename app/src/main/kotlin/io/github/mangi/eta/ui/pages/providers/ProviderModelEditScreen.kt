package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.VisionChatModels
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

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

/**
 * 模型编辑整屏路由页（`AppRoute.ModelProviderModelEdit`）。
 *
 * 由 Provider 详情「模型」tab 的编辑 / 添加自定义模型入口 push；整屏子页按
 * `docs/RULES.md` §3.3 必须做成路由（编辑身份 providerId + Model.id 可序列化），
 * 进出场交给 miuix `NavDisplay` 的程序化转场，不再走 `EtaPageSurface` 窗口。
 *
 * 保存成功静默返回（AssistantEditScreen 同款，不再回列表页 banner 提示）；
 * 删除确认框就地弹在本页上，确认后删除并返回。
 */
@Composable
internal fun ProviderModelEditScreen(
    providerId: String,
    modelId: String,
    isNew: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 初值 null 区分「流未发射」与「确实没有」：Room 流首次发射前不误判缺失。
    val providers by ProviderRepository.providersFlow().collectAsState(initial = null)
    val provider = providers?.firstOrNull { it.id == providerId }
    // 占位模型与原 tab 内联实现一致；context 进 key：语言变化时草稿名随资源语言重建。
    val placeholder = remember(isNew, context) {
        Model(
            id = "",
            modelId = "",
            displayName = context.getString(R.string.page_custom_model_25be0f),
        )
    }
    val model = if (isNew) placeholder else provider?.models?.firstOrNull { it.id == modelId }
    val missing = providers != null && !isNew && model == null
    // 目标模型不存在（远端同步删除等）：不渲染空壳，直接退栈（AssistantEditScreen 同款）。
    LaunchedEffect(missing) {
        if (missing) onBack()
    }
    model?.let {
        ModelEditPage(
            providerId = providerId,
            model = it,
            isNew = isNew,
            onBack = onBack,
        )
    }
}

@Composable
private fun ModelEditPage(
    providerId: String,
    model: Model,
    isNew: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var isSaving by rememberSaveable { mutableStateOf(false) }
    var isDeleting by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var displayName by rememberSaveable(model.id, isNew) { mutableStateOf(model.displayName) }
    var modelId by rememberSaveable(model.id, isNew) { mutableStateOf(model.modelId) }
    var contextWindowOverrideText by rememberSaveable(model.id, isNew) {
        mutableStateOf(model.contextWindowOverride?.toString().orEmpty())
    }
    val automaticReasoning = remember(model.id, model.modelId, model.reasoning, model.reasoningCapabilities) {
        ReasoningCapabilityResolver.automaticCapabilities(model)
    }
    val suggestedReasoning = remember(model.id, model.modelId, model.reasoning, model.reasoningCapabilities) {
        ReasoningCapabilityResolver.suggestedCapabilities(model)
    }
    var reasoningOverrideActive by rememberSaveable(model.id, isNew) {
        mutableStateOf(
            model.reasoningOverride != null || model.reasoningCapabilitiesOverride != null
        )
    }
    var reasoningEnabled by rememberSaveable(model.id, isNew) {
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
    var visionOverrideActive by rememberSaveable(model.id, isNew) {
        mutableStateOf(model.visionOverride != null)
    }
    var visionEnabled by rememberSaveable(model.id, isNew) {
        mutableStateOf(model.supportsVision)
    }
    LaunchedEffect(automaticVision, visionOverrideActive) {
        if (!visionOverrideActive) visionEnabled = automaticVision
    }
    val contextError = contextWindowInputError(
        contextWindowOverrideText,
        context.getString(R.string.page_the_context_length_must_be_a_positive_integer_06ca7a),
    )
    val busy = isSaving || isDeleting

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

    val dismissPage = { if (!busy) onBack() }
    // 多字段编辑已超出弹窗的决策语义：改为整屏页面，动作固定在底部，正文由 Scaffold 统一滚动。
    // 页面骨架走官方 Scaffold + LargeFlexibleTopAppBar（EtaScaffold 已做官方取证），
    // 整页背景 surfaceContainer 与路由页一致。
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
                    confirmEnabled = !busy &&
                        displayName.isNotBlank() &&
                        modelId.isNotBlank() &&
                        contextError == null,
                    cancelEnabled = !busy,
                    onCancel = onBack,
                    onConfirm = {
                        if (!isSaving) {
                            scope.launch {
                                isSaving = true
                                error = null
                                try {
                                    ModelRepository.saveModel(providerId, updated())
                                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                    onBack()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (throwable: Throwable) {
                                    error = throwable.message ?: context.getString(R.string.page_save_failed_40525a)
                                } finally {
                                    isSaving = false
                                }
                            }
                        }
                    },
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
                    enabled = !busy,
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
                    enabled = !busy,
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
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
            if (!isNew) {
                ProviderDestructiveAction(
                    text = stringResource(R.string.ui_delete_model_cf24da),
                    onClick = {
                        TouchHaptics.click(view)
                        showDeleteConfirm = true
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        }
    }
    // 删除确认就地弹在本页（原流程是先关编辑页再到列表页确认；路由化后决策点贴近操作处）。
    EtaDialog(
        show = showDeleteConfirm,
        title = stringResource(R.string.ui_delete_model_cf24da),
        summary = stringResource(R.string.provider_model_delete_summary, model.displayName),
        onDismissRequest = { if (!isDeleting) showDeleteConfirm = false },
        actions = {
            EtaDialogActions(
                confirmText = if (isDeleting) context.getString(R.string.page_deleting_6f941d) else context.getString(R.string.ui_delete_3755f5),
                cancelEnabled = !isDeleting,
                confirmEnabled = !isDeleting,
                destructive = true,
                onCancel = { showDeleteConfirm = false },
                onConfirm = {
                    if (!isDeleting) {
                        scope.launch {
                            isDeleting = true
                            try {
                                ModelRepository.deleteModel(providerId, model.id)
                                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                onBack()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (throwable: Throwable) {
                                error = context.getString(
                                    R.string.provider_error,
                                    throwable.message ?: context.getString(R.string.provider_delete_failed),
                                )
                                showDeleteConfirm = false
                            } finally {
                                isDeleting = false
                            }
                        }
                    }
                },
            )
        },
    ) {
    }
}
