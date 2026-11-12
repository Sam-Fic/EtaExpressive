package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.MediaReasoningSettings
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState

/**
 * 子智能体 profile 的只读派生逻辑单一出处。
 *
 * 摘要行（[SubAgentProfileRow]，即时写 editor）与配置弹窗（[SubAgentProfileConfigDialog]，
 * 写草稿）对同一份 profile 做同一套解析/投影/判定，此前各写一遍靠注释维持一致；
 * 这里收编成共享函数，写入通道仍由各调用方自持。
 */

/** Read-only resolution outside any editor transaction. No unsupported role gets a replacement model. */
internal fun resolveSubAgentProfileConfig(profile: SubAgentProfile, providers: List<ProviderSetting>): AgentModelClient.ModelConfig? {
    val provider = providers.singleOrNull { it.id == profile.providerId && it.isEnabled } ?: return null
    val model = provider.models.singleOrNull { it.id == profile.modelId && it.isEnabled } ?: return null
    if (model.supportsSpeechSynthesis || !profile.acceptsModel(model.supportsImageGeneration, model.supportsVideoGeneration)) return null
    return runCatching { RuntimeConfigRepository.buildRuntimeConfig(provider, model) }.getOrNull()
}

/** Grok 图像端点判定（extraBody 合并后交给 `GrokImageProfile.applies`）；config 为 null 一律 false。 */
internal fun grokImageBinding(config: AgentModelClient.ModelConfig?): Boolean = config?.let { runtime ->
    runCatching {
        val body = if (runtime.extraBodyJson.isBlank()) org.json.JSONObject() else org.json.JSONObject(runtime.extraBodyJson)
        io.github.mangi.eta.agent.model.RequestBodyMerge.mergeCustomBody(body, runtime.customBody)
        io.github.mangi.eta.agent.model.GrokImageProfile.applies(runtime.baseUrl, runtime.model, body)
    }.getOrDefault(false)
} ?: false

/** 思维档派生：媒体角色走 `MediaReasoningSettings.resolve`，其余走 reasoningCapabilities；config 为 null 时全部落空。 */
internal data class SubAgentProfileThinkingState(
    val media: MediaReasoningSettings.Capability?,
    val canThink: Boolean,
    val effective: ReasoningEffort?,
    val efforts: List<ReasoningEffort>,
)

internal fun resolveSubAgentProfileThinking(profile: SubAgentProfile, config: AgentModelClient.ModelConfig?): SubAgentProfileThinkingState {
    val media = if (profile.isMedia && config != null) MediaReasoningSettings.resolve(config, profile.role) else null
    val canThink = config != null && (!profile.isMedia || media?.status == MediaReasoningSettings.Status.SUPPORTED)
    val effective = config?.let { SubAgentPreferences.applyReasoning(profile, it).effectiveReasoningEffort }
    val efforts = if (profile.isMedia) media?.efforts.orEmpty() else config?.reasoningCapabilities?.selectableEfforts.orEmpty()
    return SubAgentProfileThinkingState(media, canThink, effective, efforts)
}

/**
 * 思维档行上状态文案。[missingModelLabel] 由调用方传入：摘要行是「选择模型」、配置弹窗是
 * 「无有效模型」，两处刻意不同；其余分支两边语义等价（非媒体角色 config 非空必可思考）。
 */
@Composable
internal fun thinkingStateLabel(
    config: AgentModelClient.ModelConfig?,
    profile: SubAgentProfile,
    state: SubAgentProfileThinkingState,
    missingModelLabel: String,
): String = when {
    config == null -> missingModelLabel
    profile.isMedia && !state.canThink -> state.media?.let { stringResource(it.labelRes) } ?: stringResource(R.string.sap_endpoint_unsupported)
    profile.isMedia && state.effective !in state.efforts -> stringResource(R.string.sap_reselect_tier)
    else -> state.effective?.let { stringResource(it.labelRes) } ?: stringResource(R.string.sap_not_enabled)
}

/** 模型投影 + 角色模态过滤：同一份 `acceptsModel` 过滤链供摘要行选择器与配置弹窗共用，空供应商组剔除。 */
internal fun profileCompatibleModels(
    providers: List<ProviderSetting>,
    profile: SubAgentProfile,
): AgentModelPickerUiState {
    val projected = AgentModelPickerProjector.project(providers, profile.providerId, profile.modelId)
    return projected.copy(providerGroups = projected.providerGroups.map { group ->
        group.copy(models = group.models.filter { profile.acceptsModel(it.supportsImageGeneration, it.supportsVideoGeneration) })
    }.filter { it.models.isNotEmpty() })
}
