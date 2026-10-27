package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ProviderAuthModes

internal object AgentRuntimeRequestConfigResolver {
    fun requiresRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        usesDefaultRuntimeConfig(request) || ProviderAuthModes.isOAuth(request.config.authMode)

    fun usesDefaultRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        request.handoff?.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE

    fun applyRuntimeConfig(
        request: AgentRuntimeWire.RunRequest,
        config: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest {
        if (!requiresRuntimeConfig(request)) return request
        if (usesDefaultRuntimeConfig(request)) {
            val handoff = request.handoff ?: return request.copy(config = config)
            val archivePayload = AgentExternalArchivePayload.from(handoff.payload)
            return request.copy(
                config = config,
                handoff = archivePayload?.let { payload ->
                    handoff.copy(
                        payload = payload.copy(
                            thinkingEnabled = config.effectiveReasoningEffort.enablesReasoning,
                            reasoningEffort = config.effectiveReasoningEffort,
                        ).toJson(),
                    )
                } ?: handoff,
            )
        }
        // 账号登录的凭据只在本进程解密：配置以本地仓库为准，仅保留发起方声明的工具权限，
        // 否则远端进程传来的旧配置会绕开刚更新的登录状态。
        val entry = request.config
        require(
            config.providerId == entry.providerId &&
                config.authMode == entry.authMode && config.model == entry.model,
        ) { "账号登录配置已变更，请重新选择服务商和模型" }
        val effort = config.reasoningCapabilities?.normalize(entry.effectiveReasoningEffort)
            ?: config.effectiveReasoningEffort
        return request.copy(
            config = config.copy(
                terminalTools = entry.terminalTools,
                browserTools = entry.browserTools,
                screenTools = entry.screenTools,
                deviceDirectTools = entry.deviceDirectTools,
                deviceSensitiveReadTools = entry.deviceSensitiveReadTools,
                deviceSensitiveActionTools = entry.deviceSensitiveActionTools,
                thinkingEnabled = effort.enablesReasoning,
                reasoningEffort = effort,
            ),
        )
    }
}
