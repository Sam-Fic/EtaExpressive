package io.github.mangi.eta.ui.model

import io.github.mangi.eta.config.Prefs

/**
 * 工具页分组与全局工具开关的对应关系。
 *
 * 工具页原先只能看：卡片上的点击只会跳去权限页/增强页，用户看到「短信检索」想关掉它，
 * 必须退出去找全局开关，而且找不到（它混在几十个敏感读工具里）。把分组直接绑到
 * [Prefs.Keys.LOCAL_AGENT_KEYS] 里的开关，卡片旁就有开关，用户能就地收紧能力。
 *
 * 只有整组恰好由一个开关控制时才提供开关；跨开关或没有开关的分组返回 null，
 * 避免出现一个开关只关掉组里一半工具这种误导性状态。
 */
internal object ToolGroupSwitch {
    /** 分组 id → 开关。返回 null 表示该组没有单一开关可用。 */
    fun keyFor(groupId: String): String? = when (groupId) {
        "web" -> Prefs.Keys.AGENT_BROWSER_TOOLS
        "device_direct" -> Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS
        "device_sensitive" -> Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS
        "personal_data" -> Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS
        "file_vision", "terminal" -> Prefs.Keys.AGENT_TERMINAL_TOOLS
        "memory" -> null // 记忆是按助手的开关，不是全局 Prefs
        "media_search" -> Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS
        "self" -> Prefs.Keys.AGENT_SELF_SETTINGS_TOOLS
        else -> null
    }

    fun isEnabled(key: String): Boolean = Prefs.isEnabled(key)

    fun setEnabled(key: String, enabled: Boolean) {
        Prefs.localAgentPreferences()?.edit()?.putBoolean(key, enabled)?.apply()
        runCatching { Prefs.reconcileAgentPreferences(io.github.mangi.eta.EtaApp.serviceInstance) }
    }

    /**
     * 该分组是否还能提供开关。`file_vision` 与 `terminal` 共用一个开关：
     * 只在 `terminal` 组显示，`file_vision` 组不重复显示，避免两处开关互相打架。
     */
    fun showsInGroup(groupId: String): Boolean = when (groupId) {
        "file_vision" -> false
        else -> keyFor(groupId) != null
    }
}
