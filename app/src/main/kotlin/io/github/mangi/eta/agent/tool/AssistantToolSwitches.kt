package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.data.model.AssistantProfile

/**
 * 按助手的工具开关。
 *
 * 记忆与技能早已是按助手的（`AssistantProfile.memoryEnabled` / `enabledSkillIds`），
 * 工具却是全局的：同一台设备上无法表达「给这个助手终端，另一个不给」。
 * 这里把全局 [AgentToolCapabilities] 式的开关收窄到某个助手：
 * **只能收紧，不能放宽**——助手配置不能把用户全局关掉的能力重新打开，
 * 这与 eta_set_settings 的「模型只能收紧」是同一条原则。
 */
internal object AssistantToolSwitches {
    /** 组 id 与 [io.github.mangi.eta.agent.model.AgentToolGroups] / 工具页分组保持一致。 */
    const val TERMINAL = "terminal"
    const val SCREEN = "screen"
    const val BROWSER = "web"
    const val DEVICE_DIRECT = "device_direct"
    const val DEVICE_ACTIONS = "device_sensitive"
    const val PERSONAL_DATA = "personal_data"
    const val DEVICE_STATE = "device_state"
    const val MEDIA_SEARCH = "media_search"
    const val SELF_SETTINGS = "self"

    val all: List<String> = listOf(
        TERMINAL, SCREEN, BROWSER, DEVICE_DIRECT, DEVICE_ACTIONS,
        PERSONAL_DATA, DEVICE_STATE, MEDIA_SEARCH, SELF_SETTINGS,
    )

    /**
     * 该助手是否可以使用这个组。[globalEnabled] 是用户全局开关：
     * 全局关掉时助手配置无法打开它（只能收紧）。
     */
    fun allows(assistant: AssistantProfile?, groupId: String, globalEnabled: Boolean): Boolean {
        if (!globalEnabled) return false
        return assistant?.toolGroupEnabled(groupId, fallback = true) ?: true
    }
}

/**
 * 工具组的中文标签与全局开关状态。放在这里而不是 UI 层，是为了让「按助手的开关」
 * 与「全局开关」始终引用同一份分组定义，避免两处列表漂移。
 */
internal object UiToolGroupLabel {
    fun label(groupId: String): String = when (groupId) {
        AssistantToolSwitches.TERMINAL -> "终端与文件"
        AssistantToolSwitches.SCREEN -> "屏幕观察与 GUI 操作"
        AssistantToolSwitches.BROWSER -> "网页与浏览器"
        AssistantToolSwitches.DEVICE_DIRECT -> "设备直达"
        AssistantToolSwitches.DEVICE_ACTIONS -> "敏感设备操作"
        AssistantToolSwitches.PERSONAL_DATA -> "个人信息读取"
        AssistantToolSwitches.DEVICE_STATE -> "设备状态读取"
        AssistantToolSwitches.MEDIA_SEARCH -> "媒体语义搜索"
        AssistantToolSwitches.SELF_SETTINGS -> "Eta 自设置"
        else -> groupId
    }

    /** 该组的全局开关是否开启；关闭时助手侧无法打开它。 */
    fun globallyEnabled(groupId: String): Boolean = when (groupId) {
        AssistantToolSwitches.TERMINAL -> io.github.mangi.eta.config.Prefs.isEnabled(
            io.github.mangi.eta.config.Prefs.Keys.AGENT_TERMINAL_TOOLS)
        AssistantToolSwitches.SCREEN -> io.github.mangi.eta.config.Prefs.isEnabled(
            io.github.mangi.eta.config.Prefs.Keys.AGENT_SCREEN_TOOLS)
        AssistantToolSwitches.BROWSER -> io.github.mangi.eta.config.Prefs.isEnabled(
            io.github.mangi.eta.config.Prefs.Keys.AGENT_BROWSER_TOOLS)
        AssistantToolSwitches.DEVICE_DIRECT -> io.github.mangi.eta.config.Prefs.isEnabled(
            io.github.mangi.eta.config.Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS)
        AssistantToolSwitches.DEVICE_ACTIONS -> io.github.mangi.eta.config.Prefs.isEnabled(
            io.github.mangi.eta.config.Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS)
        AssistantToolSwitches.PERSONAL_DATA -> io.github.mangi.eta.config.Prefs.isEnabled(
            io.github.mangi.eta.config.Prefs.Keys.AGENT_PERSONAL_DATA_READ_TOOLS)
        AssistantToolSwitches.DEVICE_STATE -> io.github.mangi.eta.config.Prefs.isEnabled(
            io.github.mangi.eta.config.Prefs.Keys.AGENT_DEVICE_STATE_READ_TOOLS)
        AssistantToolSwitches.MEDIA_SEARCH -> io.github.mangi.eta.config.Prefs.isEnabled(
            io.github.mangi.eta.config.Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS)
        AssistantToolSwitches.SELF_SETTINGS -> io.github.mangi.eta.config.Prefs.isEnabled(
            io.github.mangi.eta.config.Prefs.Keys.AGENT_SELF_SETTINGS_TOOLS)
        else -> true
    }
}
