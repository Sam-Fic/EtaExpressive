package io.github.mangi.eta.ui.remote

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus

/**
 * 当前聊天视图挂在哪个远程智能体上；值是用户给这台远程起的名字。
 *
 * `null` = 本机会话。空串 = 挂在远程上但配置里没有名字（服务器记录被删或名字为空），
 * 由调用方用通用称呼兜底——**不能把空串当成「本机」**，否则远程会话里会列出本机的
 * 一整套助手供人切换，而切换在远程侧根本不生效。
 *
 * 远程会话的「助手」由服务器决定，本机既没有它的列表也没有切换的能力，所以助手列表
 * 在远程下只能出现这一项、且不可点。用 CompositionLocal 而不是逐层传参：
 * Root → Home/Chat → ChatBody → InputBar → Composer 中间隔了五层，每层都只做转发，
 * 为传一个名字加五个参数不值当。
 */
internal val LocalRemoteAgentName = staticCompositionLocalOf<String?> { null }

/**
 * 输入框胶囊的重连/失败状态变体；`null` = 正常输入框。
 *
 * 仅 [RemoteConnectionStatus.Reconnecting] 与 [RemoteConnectionStatus.Failed] 会走到
 * 这里（与原顶部横幅同口径）：断线期间整颗输入框胶囊让位给状态胶囊，提示语顶替
 * 输入框、麦克风圆位换成 LoadingIndicator / 重试。走 CompositionLocal 的理由与
 * [LocalRemoteAgentName] 相同——隔着五层只做转发，不值得为它加五个参数。
 */
internal data class RemoteComposerStatus(
    val status: RemoteConnectionStatus,
    val serverName: String,
    val onRetry: () -> Unit,
    val onOpenSettings: () -> Unit,
)

internal val LocalRemoteComposerStatus = staticCompositionLocalOf<RemoteComposerStatus?> { null }
