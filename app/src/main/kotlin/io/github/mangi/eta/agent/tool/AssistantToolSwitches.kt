package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.AssistantProfile

/**
 * 按助手的工具开关。
 *
 * 记忆与技能早已是按助手的（`AssistantProfile.memoryEnabled` / `enabledSkillIds`），
 * 工具却是全局的：同一台设备上无法表达「给这个助手终端，另一个不给」。
 * 这里把全局开关收窄到某个助手：**只能收紧，不能放宽**——助手配置不能把用户全局
 * 关掉的能力重新打开，这与 eta_set_settings 的「模型只能收紧」是同一条原则。
 *
 * 组 id 与 [AgentToolGroups] 是同一份：设置首页、工具清单页、模型侧目录、助手收窄
 * 全部引用同一套分组，避免多套分类互相漂移。
 */
internal object AssistantToolSwitches {
    const val BASIC = "basic"
    const val SCREEN = "screen"
    const val WEB = "web"
    const val TERMINAL = "terminal"
    const val DEVICE = "device"
    const val PERSONAL = "personal"
    const val EXTENSIONS = "extensions"

    /**
     * 可被助手收窄的组。只有进入 `ModelConfig` 的组才能同时收窄「目录」与「执行」；
     * 基础交互与 Eta 自身配置直接读 Prefs，助手级开关对它们无效，因此不在这里列出——
     * 列出来只会变成一个点了没反应的假开关。
     */
    val all: List<String> = listOf(
        SCREEN, WEB, TERMINAL, DEVICE, PERSONAL,
    )

    /**
     * 旧分组 id → 新 id。老助手档案里存的是细分 id（设备直达 / 敏感操作、个人信息 /
     * 设备状态、Eta 自设置），合并后按最窄结果生效：任一旧细项被关掉，整组就关掉。
     */
    private val legacyIds: Map<String, List<String>> = mapOf(
        DEVICE to listOf("device_direct", "device_sensitive"),
        PERSONAL to listOf("personal_data", "device_state"),
        EXTENSIONS to listOf("self"),
    )

    /**
     * 该助手是否可以使用这个组。[globalEnabled] 是用户全局开关：
     * 全局关掉时助手配置无法打开它（只能收紧）。
     */
    fun allows(assistant: AssistantProfile?, groupId: String, globalEnabled: Boolean): Boolean {
        if (!globalEnabled) return false
        if (assistant == null) return true
        if (!assistant.toolGroupEnabled(groupId, fallback = true)) return false
        return legacyIds[groupId].orEmpty().all { assistant.toolGroupEnabled(it, fallback = true) }
    }
}

/**
 * 工具组的中文标签与全局开关状态。放在这里而不是 UI 层，是为了让「按助手的开关」
 * 与「全局开关」始终引用同一份分组定义，避免两处列表漂移。
 */
internal object UiToolGroupLabel {
    fun label(groupId: String): String = when (groupId) {
        AssistantToolSwitches.BASIC -> "基础交互"
        AssistantToolSwitches.SCREEN -> "屏幕观察与 GUI 操作"
        AssistantToolSwitches.WEB -> "网页与浏览器"
        AssistantToolSwitches.TERMINAL -> "终端与文件"
        AssistantToolSwitches.DEVICE -> "设备控制"
        AssistantToolSwitches.PERSONAL -> "个人数据"
        AssistantToolSwitches.EXTENSIONS -> "Eta 配置与扩展"
        else -> groupId
    }

    /** 该组的全局开关是否开启；关闭时助手侧无法打开它。 */
    fun globallyEnabled(groupId: String): Boolean = when (groupId) {
        AssistantToolSwitches.BASIC -> Prefs.isEnabled(Prefs.Keys.AGENT_BASIC_TOOLS)
        AssistantToolSwitches.SCREEN -> Prefs.isEnabled(Prefs.Keys.AGENT_SCREEN_TOOLS)
        AssistantToolSwitches.WEB -> Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS)
        AssistantToolSwitches.TERMINAL -> Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS)
        AssistantToolSwitches.DEVICE -> Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_TOOLS)
        AssistantToolSwitches.PERSONAL -> Prefs.isEnabled(Prefs.Keys.AGENT_PERSONAL_TOOLS)
        AssistantToolSwitches.EXTENSIONS -> Prefs.isEnabled(Prefs.Keys.AGENT_EXTENSIONS_TOOLS)
        else -> true
    }
}
