package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具目录的分层加载（与 Skills 的思路一致）：常驻工具永远下发，低频的「能力域」只下发一行索引，
 * 模型需要时先打开该域，下一轮才出现在目录里。
 *
 * 为什么要分层：工具 schema 是每轮都发送的固定前缀。`eta_manage_*` 这组自设置工具一次运行里
 * 通常用不到，却要付约 2.9k token；把整组换成一个 `tools_use_group` 的开销只有几十 token。
 *
 * 约束（都与现有架构对齐）：
 * - **必须挂在轮次边界上**：AgentLoop 在每轮请求前重建工具目录与 `AgentToolCallValidator`，
 *   所以打开域之后最早也只在**下一轮**生效，这与技能安装的 `next_turn` 语义一致。
 * - **执行侧双重校验**：域内工具仍受 Prefs 开关、`AgentToolCapabilities` 与
 *   `AgentToolRequirements` 约束；打开域不等于放宽权限。
 * - 域内容不在本轮目录里时调用会被 `AgentToolCallValidator` 拒绝，因此打开动作必须先发生。
 */
internal object AgentToolGroups {
    /** 常驻自设置工具：读配置、改配置。几乎每次自设置对话都要用到。 */
    private val alwaysAvailableSelfSettings = setOf(
        AgentSelfSettingsToolCatalog.GET_SETTINGS,
        AgentSelfSettingsToolCatalog.SET_SETTINGS,
    )

    /** 一批低频自设置管理工具，合并成一个可按需打开的域。 */
    val SELF_SETTINGS_GROUP: AgentToolGroup = AgentToolGroup(
        id = "self_settings_admin",
        title = "Eta configuration management",
        summary = "Manage models, sub-agent presets, conversation history and MCP servers.",
        toolNames = setOf(
            AgentSelfSettingsToolCatalog.MANAGE_MODELS,
            AgentSelfSettingsToolCatalog.MANAGE_SUBAGENTS,
            AgentSelfSettingsToolCatalog.MANAGE_HISTORY,
            AgentSelfSettingsToolCatalog.CONFIGURE_MCP,
        ),
        // 自设置管理工具本身就不是只读的，且会改配置；打开域不改变这一点。
        deferredByDefault = true,
    )

    val groups: List<AgentToolGroup> = listOf(SELF_SETTINGS_GROUP)

    fun group(id: String): AgentToolGroup? = groups.firstOrNull { it.id == id }

    /** 已打开域里的工具名；`null`/未知 id 返回空集合。 */
    fun toolsOfGroup(id: String?): Set<String> = id?.let(::group)?.toolNames ?: emptySet()

    fun isDeferredTool(name: String): Boolean =
        groups.any { it.deferredByDefault && name in it.toolNames }

    /** 未被打开时应从目录里剔除的自设置工具。 */
    fun withheldSelfSettingsTools(openedGroups: Set<String>, selfSettingsToolsEnabled: Boolean): Set<String> {
        if (!selfSettingsToolsEnabled) return AgentSelfSettingsToolCatalog.manageTools()
        val opened = openedGroups.flatMap(::toolsOfGroup).toSet()
        return AgentSelfSettingsToolCatalog.manageTools() - opened
    }

    /**
     * `tools_use_group` 的系统消息索引：每个域一行，说明它何时值得打开。
     * 索引本身很短，替代的是整组 schema。
     */
    fun indexMessage(openedGroups: Set<String>): String? {
        val available = groups.filter { it.deferredByDefault && it.id !in openedGroups }
        if (available.isEmpty()) return null
        return buildString {
            appendLine("Deferred tool groups (schema not loaded yet; open one only when the task needs it):")
            available.forEach { group ->
                appendLine("- id=${group.id} | title=${group.title} | ${group.summary}")
            }
            append(
                "These tools are NOT in your tool list yet. When the task genuinely needs one, call " +
                    "tools_use_group with its id; the group's tools appear from the next round onward. " +
                    "Do not call them before opening, and do not open a group you do not need.",
            )
        }
    }
}

internal data class AgentToolGroup(
    val id: String,
    val title: String,
    val summary: String,
    val toolNames: Set<String>,
    val deferredByDefault: Boolean = true,
)

/** `tools_use_group` 的 schema；单独一个文件方便与目录组装解耦。 */
internal object AgentToolGroupCatalog {
    const val USE_GROUP = "tools_use_group"

    fun appendTo(tools: JSONArray, openableIds: List<String>, disabled: Boolean = false) {
        if (openableIds.isEmpty() || disabled) return
        tools.put(
            AgentToolSchema.function(
                name = USE_GROUP,
                description = "Load a deferred tool group so its tools become available from the next " +
                    "round onward. Use it only when the current task actually needs those tools; the " +
                    "group index in the system prompt lists what each one contains. Opening a group " +
                    "never grants permissions the device or settings would otherwise deny.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "group",
                            JSONObject()
                                .put("type", "string")
                                .put("enum", JSONArray(openableIds))
                                .put("description", "Deferred group id from the system-prompt index."),
                        ),
                    )
                    .put("required", JSONArray().put("group"))
                    .put("additionalProperties", false),
            ),
        )
    }

    fun result(group: AgentToolGroup): String = JSONObject()
        .put("ok", true)
        .put("group", group.id)
        .put("tools", JSONArray(group.toolNames.sorted()))
        .put("available", "next_turn")
        .put(
            "message",
            "Group opened. Its tools are available from the next round onward; they are not part of " +
                "the request you are answering right now.",
        )
        .toString()

    fun unknownGroup(requested: String, known: List<String>): String = JSONObject()
        .put("ok", false)
        .put("code", "UNKNOWN_TOOL_GROUP")
        .put("group", requested)
        .put("available_groups", JSONArray(known.sorted()))
        .put("message", "No such deferred group. Use one of available_groups.")
        .toString()
}
