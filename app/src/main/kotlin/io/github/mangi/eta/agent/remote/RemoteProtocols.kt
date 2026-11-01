package io.github.mangi.eta.agent.remote

import io.github.mangi.eta.agent.remote.pi.PiRemoteAdapter

/**
 * 一个远程智能体协议在用户眼里是什么样子。
 *
 * [id] 会存进 [RemoteServerConfig.protocolId]，是**稳定标识**：改名会让老配置连不上，
 * 所以它跟界面文案无关。`displayName` 是品牌专名（Pi Agent 这种），不进翻译文件。
 *
 * 这张目录只负责「怎么称呼」，**能不能用**由 [RemoteProtocolRegistry] 决定：
 * 只有注册了适配器的协议才算可用。分开是故意的——接入新协议时只注册适配器一处，
 * 不会出现「目录里写了、适配器没写」这种两处容易漏一个的状态。
 */
internal data class RemoteProtocolDescriptor(
    val id: String,
    val displayName: String,
)

/**
 * 已知协议的目录。
 *
 * 加一个协议要做的事只有两件：写一个 [RemoteAgentProtocolAdapter] 实现，
 * 在 [RemoteProtocolRegistry] 里注册；再到这里的 [descriptors] 补一行中听的名字。
 * 忘了补名字不会让协议不可用（[displayNameOf] 回显 id），只是界面上不好看。
 */
internal object RemoteProtocols {
    /** 默认协议：老配置没有 `protocolId` 字段，反序列化时也会落到它。 */
    const val DEFAULT_ID: String = PiRemoteAdapter.PROTOCOL_ID

    private val descriptors: List<RemoteProtocolDescriptor> = listOf(
        RemoteProtocolDescriptor(id = PiRemoteAdapter.PROTOCOL_ID, displayName = "Pi Agent"),
    )

    fun all(): List<RemoteProtocolDescriptor> = descriptors

    fun descriptor(id: String): RemoteProtocolDescriptor? =
        descriptors.firstOrNull { it.id == id }

    /**
     * 界面可选项：只列真能被 [RemoteProtocolRegistry] 打开的那些。
     *
     * 目录里有、注册表里没有（适配器还没写）时**不出现**，而不是给一个点了没反应的
     * 选项——那比没有这一项更糟。
     */
    fun selectable(): List<RemoteProtocolDescriptor> =
        descriptors.filter { RemoteProtocolRegistry.has(it.id) }

    /** 列表行展示用；未知协议原样回显 id，别留空让人以为没配。 */
    fun displayNameOf(id: String): String = descriptor(id)?.displayName ?: id
}

/**
 * 协议 → 适配器。**接入新协议的唯一切入点**。
 *
 * 做法：按 [PiRemoteAdapter] 的样子实现 [RemoteAgentProtocolAdapter]（协议自己的帧格式
 * 全在那一个包里，别漏到 `ui/remote` 去），然后把实例加进 [adapters]。
 * 其余地方一律通过 [adapterFor] 取，不再出现 `if (protocolId == "pi")`。
 *
 * [capabilitiesOf] 是留给界面的钩子：第二个协议接进来时，用它决定「切模型 / 会话列表 /
 * 附件」这些控件显不显示，而不是让每个页面各自判断协议 id。
 */
internal object RemoteProtocolRegistry {
    private val adapters: Map<String, RemoteAgentProtocolAdapter> = listOf(
        PiRemoteAdapter(),
    ).associateBy { it.protocolId }

    fun has(id: String): Boolean = adapters.containsKey(id)

    fun adapterFor(id: String): RemoteAgentProtocolAdapter? = adapters[id]

    fun capabilitiesOf(id: String): RemoteCapabilities? = adapters[id]?.capabilities

    fun ids(): Set<String> = adapters.keys
}
