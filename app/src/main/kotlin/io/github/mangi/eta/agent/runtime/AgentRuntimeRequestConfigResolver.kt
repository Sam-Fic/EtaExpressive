package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ProviderAuthModes

internal object AgentRuntimeRequestConfigResolver {
    /**
     * 只有账号登录（OAuth）才需要用本进程的解密凭据刷新配置。
     *
     * 语音浮窗与主界面使用同一个 `RuntimeConfigRepository.buildRuntimeConfig` 组装请求，
     * 工具开关、助手人格、思考档位都已随 config 传入；这里再整体替换会抹掉发起方的
     * 设置（历史问题：语音运行因此永远拿到默认工具开关），所以不再按入口来源替换。
     */
    fun requiresRuntimeConfig(request: AgentRuntimeWire.RunRequest): Boolean =
        ProviderAuthModes.isOAuth(request.config.authMode)

    fun applyRuntimeConfig(
        request: AgentRuntimeWire.RunRequest,
        config: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest {
        if (!requiresRuntimeConfig(request)) return request
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
                deviceTools = entry.deviceTools,
                personalTools = entry.personalTools,
                thinkingEnabled = effort.enablesReasoning,
                reasoningEffort = effort,
            ),
        )
    }
}
