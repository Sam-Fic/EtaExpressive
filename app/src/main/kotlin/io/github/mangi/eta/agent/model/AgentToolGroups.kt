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

    /**
     * 改设备的写操作。默认折叠除了省 token，还有安全收益：模型不会顺手误触
     * `set_setting` / `manage_app` / `system_property` 这类会真实改变设备状态的工具。
     * 打开域只让 schema 进入目录，各工具自身的 Root/权限判定照旧。
     */
    val DEVICE_ACTION_GROUP: AgentToolGroup = AgentToolGroup(
        id = "device_write",
        title = "Device changes",
        summary = "Write operations: settings, alarms, calendar events, packages, power, " +
            "Wi-Fi/Bluetooth, notes, users, AppOps, display and screen timeout. Open this only " +
            "when the user actually asked to change something.",
        toolNames = AgentDeviceToolCatalog.sensitiveActionToolNames(),
        deferredByDefault = true,
    )

    /**
     * 只读组里的冷门长尾：出行、账单、记忆合集、便签/录音、聊天图片缓存这类「特定场景才用」的查询。
     * 日常高频入口（日历、短信、通知、通讯录、应用列表、位置、设备状态）保持常驻，
     * 因此折叠不会让日常任务多一次往返。
     */
    val DEVICE_TAIL_READ_GROUP: AgentToolGroup = AgentToolGroup(
        id = "device_tail_queries",
        title = "Occasional personal-data lookups",
        summary = "Trip, bill, memory-collection, note/recording and chat-image queries that only " +
            "matter in specific situations. Common lookups such as calendar, messages, " +
            "notifications, contacts, installed apps and device state stay available without opening this.",
        toolNames = AgentDeviceToolCatalog.tailReadToolNames(),
        deferredByDefault = true,
    )

    val groups: List<AgentToolGroup> = listOf(
        SELF_SETTINGS_GROUP,
        DEVICE_ACTION_GROUP,
        DEVICE_TAIL_READ_GROUP,
    )

    fun group(id: String): AgentToolGroup? = groups.firstOrNull { it.id == id }

    /** 已打开域里的工具名；`null`/未知 id 返回空集合。 */
    fun toolsOfGroup(id: String?): Set<String> = id?.let(::group)?.toolNames ?: emptySet()

    fun isDeferredTool(name: String): Boolean =
        groups.any { it.deferredByDefault && name in it.toolNames }

    /** 未被打开时应从目录里剔除的自设置工具。 */
    fun withheldSelfSettingsTools(openedGroups: Set<String>, selfSettingsToolsEnabled: Boolean): Set<String> {
        if (!selfSettingsToolsEnabled) return AgentSelfSettingsToolCatalog.manageTools()
        return withheld(SELF_SETTINGS_GROUP, openedGroups)
    }

    /** 未被打开时应从目录里剔除的设备写操作。 */
    fun withheldDeviceActions(openedGroups: Set<String>): Set<String> =
        withheld(DEVICE_ACTION_GROUP, openedGroups)

    /** 未被打开时应从目录里剔除的冷门只读查询。 */
    fun withheldTailReads(openedGroups: Set<String>): Set<String> =
        withheld(DEVICE_TAIL_READ_GROUP, openedGroups)

    private fun withheld(group: AgentToolGroup, openedGroups: Set<String>): Set<String> =
        if (group.id in openedGroups) emptySet() else group.toolNames

    /**
     * 本轮的域可用性快照。**目录、索引与执行侧必须读同一份**：
     * 之前 `tools_use_group` 的枚举只按「是否已打开」筛选，不看开关，于是用户在设置里
     * 关掉敏感设备操作后，模型仍被告知可以打开 `device_write`——打开会成功返回，
     * 但下一轮域内工具永远不会出现，白花一轮且反复误导模型。
     */
    data class Availability(
        /** 已打开、且其依赖的开关仍然允许的域。 */
        val opened: Set<String>,
        /** 尚未打开、但本轮的开关允许打开，因而值得在索引里宣传的域。 */
        val openable: Set<String>,
    )

    /** 当前能被打开的域 id（按 [groups] 的声明顺序）；给 `tools_use_group` 的枚举用。 */
    fun openableIds(availability: Availability): List<String> =
        groups.filter { it.id in availability.openable }.map { it.id }

    /**
     * 计算本轮的域可用性。
     *
     * [gate] 回答「这个域依赖的开关此刻是否允许它存在」——它把每个域映射到真正决定它
     * 能否下发的那个开关（自设置管理域看 `selfSettingsTools`，设备域看各自的风险组开关）。
     * 已经打开但随后关掉开关的域会从 [Availability.opened] 里消失，因为它的工具本就不再下发。
     */
    fun availability(openedGroups: Set<String>, gate: (AgentToolGroup) -> Boolean): Availability {
        val allowed = groups.filter { it.deferredByDefault && gate(it) }.map { it.id }.toSet()
        return Availability(
            opened = openedGroups.filterTo(mutableSetOf()) { it in allowed },
            openable = allowed - openedGroups,
        )
    }

    /**
     * `tools_use_group` 的系统消息索引：每个域一行，说明它何时值得打开。
     * 索引本身很短，替代的是整组 schema。
     * 只宣传 [Availability.openable] 里的域——开关关闭的域不该出现在提示里。
     */
    fun indexMessage(availability: Availability): String? {
        val available = groups.filter { it.id in availability.openable }
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
