package io.github.mangi.eta.agent.remote.opencode

import io.github.mangi.eta.agent.remote.RemoteAgentEvent
import io.github.mangi.eta.agent.remote.RemoteAgentProtocolAdapter
import io.github.mangi.eta.agent.remote.RemoteCapabilities
import io.github.mangi.eta.agent.remote.RemoteServerConfig
import io.github.mangi.eta.agent.remote.RemoteSession
import kotlinx.coroutines.CoroutineScope

/**
 * OpenCode v2 协议适配器。连的是 `opencode serve` 起的 HTTP 服务器（v2 API，
 * REST + SSE），wire 细节见 [OpencodeSession] 的 KDoc。
 *
 * 能力面：全开。OpenCode 的会话 / 模型切换 / 历史分页 / 中断 / 权限与表单应答 /
 * 附件（prompt 内联 data URI，单文件 ≤20MiB）都有对应端点。
 */
internal class OpencodeRemoteAdapter : RemoteAgentProtocolAdapter {
    override val protocolId: String = PROTOCOL_ID

    override val capabilities: RemoteCapabilities = RemoteCapabilities(
        supportsPrompt = true,
        supportsSessions = true,
        supportsModelSwitch = true,
        supportsAttachments = true,
        supportsInterrupt = true,
        supportsHistoryPaging = true,
    )

    override fun open(
        config: RemoteServerConfig,
        scope: CoroutineScope,
        onEvent: (RemoteAgentEvent) -> Unit,
    ): RemoteSession = OpencodeSession(config, scope, onEvent)

    companion object {
        /** 稳定协议标识，存进 [RemoteServerConfig.protocolId]；改名会让老配置连不上。 */
        const val PROTOCOL_ID = "opencode"
    }
}
