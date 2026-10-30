package io.github.mangi.eta.ui.model

import io.github.mangi.eta.config.Prefs

/**
 * 工具页分组与全局工具开关的对应关系。
 *
 * 工具页原先只能看：卡片上的点击只会跳去权限页/增强页，用户看到「短信检索」想关掉它，
 * 必须退出去找全局开关，而且找不到（它混在几十个敏感读工具里）。把分组直接绑到
 * [Prefs.Keys.LOCAL_AGENT_KEYS] 里的开关，卡片旁就有开关，用户能就地收紧能力。
 *
 * 分组 id 取自 [io.github.mangi.eta.agent.model.AgentToolGroups]：设置首页、工具清单页、
 * 模型侧目录与助手收窄共用同一份定义，这里只是「组 id → 用户开关」的映射。
 * 组内的单个工具由 [Prefs.disabledTools] 承接，不再有跨开关的组。
 */
internal object ToolGroupSwitch {
    /** 分组 id → 开关。返回 null 表示该组没有单一开关可用。 */
    fun keyFor(groupId: String): String? = when (groupId) {
        "basic" -> Prefs.Keys.AGENT_BASIC_TOOLS
        "screen" -> Prefs.Keys.AGENT_SCREEN_TOOLS
        "web" -> Prefs.Keys.AGENT_BROWSER_TOOLS
        "terminal" -> Prefs.Keys.AGENT_TERMINAL_TOOLS
        "device" -> Prefs.Keys.AGENT_DEVICE_TOOLS
        // 日程与设备状态组复用宿主组的开关：与 AgentToolGroups.groupEnabled、
        // AgentToolCatalog 的组可用性判定保持一致，用户关一次设备/个人数据开关即整体收起。
        "calendar" -> Prefs.Keys.AGENT_DEVICE_TOOLS
        "personal" -> Prefs.Keys.AGENT_PERSONAL_TOOLS
        "device_state" -> Prefs.Keys.AGENT_PERSONAL_TOOLS
        // Eta 自身配置组刻意不提供开关入口之外的模型控制，但用户可以在这里关掉。
        "extensions" -> Prefs.Keys.AGENT_EXTENSIONS_TOOLS
        else -> null
    }

    fun isEnabled(key: String): Boolean = Prefs.isEnabled(key)

    fun setEnabled(key: String, enabled: Boolean) {
        Prefs.localAgentPreferences()?.edit()?.putBoolean(key, enabled)?.apply()
        runCatching { Prefs.reconcileAgentPreferences(io.github.mangi.eta.EtaApp.serviceInstance) }
    }

    /** 该分组是否还能提供开关；没有对应 Prefs 键的分组不渲染开关行。 */
    fun showsInGroup(groupId: String): Boolean = keyFor(groupId) != null
}
