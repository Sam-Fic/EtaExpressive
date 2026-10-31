package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.LocalToolRequirement
import io.github.mangi.eta.agent.tool.RootRequirement

/** 浏览器的几个说明卡展示同一工具的不同操作，要求仍来自实际执行工具。 */
internal fun toolCardRequirement(id: String): LocalToolRequirement {
    val toolName = actualToolName(id)
    return requireNotNull(AgentToolRequirements.find(toolName)) { "Unknown tool card: $id" }
}

internal fun visibleOnCurrentDevice(id: String, rootGranted: Boolean, colorOs: Boolean): Boolean {
    val requirement = toolCardRequirement(id)
    return (rootGranted || requirement.rootRequirement != RootRequirement.REQUIRED) &&
        (colorOs || !requirement.colorOs)
}

internal fun actualToolName(id: String): String = when (id) {
    "browser_read", "browser_interact", "browser_screenshot" -> "browser_use"
    else -> id
}

/**
 * 「当前设备 / 全部」两个页签的投影：按本机能力过滤工具行，再丢掉过滤后没有行的卡片。
 * 只剩子开关、没有工具的卡片也一并丢掉——留一张空卡会让用户以为还能开什么。
 */
internal fun projectToolCapabilities(
    capabilities: List<ToolCapabilityUi>,
    showAll: Boolean,
    rootGranted: Boolean,
    colorOs: Boolean,
): List<ToolCapabilityUi> =
    capabilities.map { capability ->
        capability.copy(
            tools = capability.tools.filter {
                showAll || visibleOnCurrentDevice(it.id, rootGranted, colorOs)
            },
        )
    }.filter { it.tools.isNotEmpty() }

/** 普通权限的缺失优先给出可执行的授权入口，查看增强说明本身不会请求 Root。 */
internal fun toolCardAction(id: String, capabilities: AgentToolCapabilities): AgentToolsAction? {
    val requirement = toolCardRequirement(id)
    return when (capabilities.unavailableCode(actualToolName(id))) {
        "ROOT_REQUIRED", "DEVICE_UNSUPPORTED" -> AgentToolsAction.OpenEnhancements
        null -> when {
            id.startsWith("browser_") -> AgentToolsAction.OpenBrowser
            id == "web_search" || id == "fetch_url" -> AgentToolsAction.OpenWebToolsSettings
            !capabilities.rootAvailable && requirement.rootRequirement == RootRequirement.PARTIAL ->
                AgentToolsAction.OpenEnhancements
            else -> null
        }
        else -> AgentToolsAction.OpenPermissions
    }
}
