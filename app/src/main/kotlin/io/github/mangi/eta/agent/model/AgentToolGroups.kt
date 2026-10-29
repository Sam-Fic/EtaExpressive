package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具目录的统一分组（taxonomy）：这里是**唯一的分组定义**，同时驱动四处——
 * 设置首页的组开关、工具清单页的分节、模型侧的常驻/延迟下发、按助手的收窄。
 *
 * 分层加载（与 Skills 的思路一致）：只有 [BASIC_GROUP] 常驻，其余组默认延迟，
 * 目录里只留一行索引，模型需要时先 `tools_use_group` 打开，下一轮才出现在目录里。
 *
 * 约束（都与现有架构对齐）：
 * - **必须挂在轮次边界上**：AgentLoop 在每轮请求前重建工具目录与 `AgentToolCallValidator`，
 *   所以打开域之后最早也只在**下一轮**生效，这与技能安装的 `next_turn` 语义一致。
 * - **执行侧双重校验**：组内工具仍受 Prefs 组开关、单工具禁用集、`AgentToolCapabilities`
 *   与 `AgentToolRequirements` 约束；打开组不等于放宽权限。
 * - 组内容不在本轮目录里时调用会被 `AgentToolCallValidator` 拒绝，因此打开动作必须先发生。
 */
internal object AgentToolGroups {
    /**
     * 基础交互：对话基础设施，唯一常驻组。成员各自还有能力门禁
     * （委派看子代理开关、记忆看助手记忆开关、技能看 Skills 启用状态），
     * 组开关只决定「用户是否允许这一层」。
     *
     * summary 不参与提示：[indexMessage] 只枚举未打开的延迟组，常驻组没有索引行。
     */
    val BASIC_GROUP: AgentToolGroup = AgentToolGroup(
        id = "basic",
        summary = "Ask the user, current context, app search/launch, speech, core memory and " +
            "skills reading, sub-agent delegation.",
        toolNames = setOf(
            "ask_user", "get_current_context", "search_apps", "launch_app", "open_uri",
            "text_to_speech",
            "memory_get",
            "skills_list", "skills_read", "skills_read_resource", "skills_list_curated",
            "delegate_task", "get_task_result", "continue_task", "cancel_task",
        ),
        deferredByDefault = false,
    )

    /** 屏幕观察与 GUI 操作；含虚拟显示会话工具。 */
    val SCREEN_GROUP: AgentToolGroup = AgentToolGroup(
        id = "screen",
        summary = "Screen observation and GUI operation: taps, swipes, text input, clipboard, " +
            "waits, virtual-display sessions.",
        toolNames = setOf(
            "observe_screen",
            "tap", "tap_area", "tap_element", "long_press", "long_press_element",
            "swipe", "scroll", "scroll_element",
            "type_text", "input_text", "replace_text", "clear_text", "paste_text", "press_key",
            "wait", "wait_for_text", "wait_for_package", "open_system_panel",
            "get_clipboard", "set_clipboard",
            "start_virtual_session", "keep_virtual_result", "finish_virtual_session",
            "inspect_virtual_backend",
        ),
    )

    /** 网页浏览：离屏浏览器、静态抓取与本地搜索。 */
    val WEB_GROUP: AgentToolGroup = AgentToolGroup(
        id = "web",
        summary = "Offscreen multi-tab browser (JS, login, screenshots), static fetch_url, " +
            "local web_search.",
        toolNames = setOf("browser_use", "fetch_url", "web_search"),
    )

    /** 终端与文件：Android/Linux 命令、文件读写与图片/视频帧读取。shell 是组内子开关。 */
    val TERMINAL_GROUP: AgentToolGroup = AgentToolGroup(
        id = "terminal",
        summary = "Shell sessions on Android and in the Linux environment, file read/write/edit/" +
            "grep/glob, read_image for pictures and video frames.",
        toolNames = setOf(
            "terminal", "run_command",
            "read_file", "write_file", "list_directory", "stat_file", "edit_file",
            "glob_files", "grep_files",
            "read_image",
        ),
    )

    /** 设备直达子集；迁移与确认策略仍需要区分写操作。 */
    val DEVICE_DIRECT_NAMES: Set<String> = setOf(
        "inspect_app", "set_alarm", "set_timer",
        "media_control", "set_volume", "set_ringer_mode", "set_sound_mode",
        "device_status", "network_info", "top_memory_apps", "top_storage_apps",
    )

    /**
     * 日程写入：四个工具的字面量描述很长（无 Root 降级、歧义候选、重复规则），
     * 合在 device 里时组 schema 达 7.9k token，且每轮请求都要重发。
     * 单独成组后，日历任务只付约 1.6k，其余设备能力不参与。
     * 组开关沿用 [DEVICE_DIRECT_NAMES] 所在的设备写开关（开组仍受它门禁）。
     */
    val CALENDAR_WRITE_NAMES: Set<String> = setOf(
        "create_calendar_event", "create_calendar_events",
        "update_calendar_event", "delete_calendar_event",
    )

    /**
     * 设备控制：直达操作（闹钟/计时器/音量/媒体）+ 写操作（设置/网络/电源/应用/便签）。
     * 旧版拆成 direct 与 sensitive_action 两个开关，用户难以会意；合并为一组后，
     * 细粒度控制交给单工具开关（迁移时把原关闭档位的工具注入禁用集，语义无损）。
     */
    val DEVICE_GROUP: AgentToolGroup = AgentToolGroup(
        id = "device",
        summary = "Alarms, timers, volume, media playback, app inspection, and device writes: " +
            "settings, network, power, display, packages, users, notes. Calendar events are a separate group.",
        toolNames = DEVICE_DIRECT_NAMES +
            (AgentDeviceToolCatalog.sensitiveActionToolNames() - CALENDAR_WRITE_NAMES),
    )

    /** 日程写入组；索引里单独一行，避免模型为了建一条日程把整组设备写工具载入。 */
    val CALENDAR_GROUP: AgentToolGroup = AgentToolGroup(
        id = "calendar",
        summary = "Create, modify and delete system calendar events directly (time formats, " +
            "reminders, recurrence, ambiguity handling).",
        toolNames = CALENDAR_WRITE_NAMES,
    )

    /**
     * 个人内容读取 + 媒体语义搜索。semantic_search_media 额外受媒体搜索开关门禁。
     *
     * 设备状态读取曾与此组合并（两档旧开关退役时并为一组），但两者的数据来源与使用场景
     * 没有交集：查联系人和看 logcat 是两件事。33 个工具的合组会让一次「找联系人」
     * 多背 18 个与本任务无关的 schema，因此重新拆开，开关仍共用个人数据总开关。
     */
    val PERSONAL_GROUP: AgentToolGroup = AgentToolGroup(
        id = "personal",
        summary = "Personal content: contacts, call log, SMS, personal notes and recordings, " +
            "files and downloads, bills/todos/trips, chat images, semantic media search.",
        toolNames = AgentDeviceToolCatalog.personalReadToolNames() +
            setOf(AgentMediaSearchToolCatalog.SEARCH_MEDIA),
    )

    /**
     * 设备状态读取：设置项、闹钟与计时器、通知、位置、日志、网络与显示状态。
     *
     * 与 [PERSONAL_GROUP] 同受个人数据总开关门禁：它们读的都是本机实时状态，
     * 旧版也归在同一档「敏感读取」里，拆组只是为了缩小一次任务要载入的 schema。
     */
    val DEVICE_STATE_GROUP: AgentToolGroup = AgentToolGroup(
        id = "device_state",
        summary = "Read-only device state: settings, alarms/timers, notifications, location, " +
            "logcat/dumpsys, network, display and sound, installed apps.",
        toolNames = AgentDeviceToolCatalog.deviceStateReadToolNames(),
    )

    /**
     * Eta 自身与扩展：自写配方（排在最前）、自设置管理、Skills 的 GitHub 发现/安装、记忆写入。
     * 组开关沿用「模型不能自己关掉自己配置能力」的原则：不出现在 eta_set_settings 可改列表。
     *
     * 自写配方放在摘要首位是刻意的：没有专用工具能读的数据（各厂商便签、私有数据库），
     * 正确做法是 author 一个配方，而不是用 root shell 一轮轮挖。曾观测到模型把
     * recipe 功能排到第 5 位才看见，先花了 23 次 run_command 去做字符串提取。
     */
    val EXTENSIONS_GROUP: AgentToolGroup = AgentToolGroup(
        id = "extensions",
        summary = "Author your own reusable tool as a recipe (eta_author_tool, eta_list_recipes) " +
            "when no built-in tool can read the data; manage Eta itself (models, sub-agents, " +
            "history, MCP, settings); discover/install Skills from GitHub or local packages; " +
            "write long-term memory.",
        toolNames = AgentSelfSettingsToolCatalog.manageTools() + setOf(
            AgentSelfSettingsToolCatalog.GET_SETTINGS,
            AgentSelfSettingsToolCatalog.SET_SETTINGS,
            "skills_inspect_github", "skills_install_from_github", "skills_install_local",
            "memory_write",
        ),
    )

    /** 全部组，按设置页展示顺序；[BASIC_GROUP] 参与成员查询但不参与延迟索引。 */
    val groups: List<AgentToolGroup> = listOf(
        BASIC_GROUP,
        SCREEN_GROUP,
        WEB_GROUP,
        TERMINAL_GROUP,
        DEVICE_GROUP,
        CALENDAR_GROUP,
        PERSONAL_GROUP,
        DEVICE_STATE_GROUP,
        EXTENSIONS_GROUP,
    )

    /** 可作为延迟域打开的组（除基础交互外全部）。 */
    val deferredGroups: List<AgentToolGroup> = groups.filter { it.deferredByDefault }

    /** 全部组 id；「所有组都已打开」的场景（子代理只读目录、测试）用它。 */
    val allIds: Set<String> = groups.mapTo(mutableSetOf()) { it.id }

    fun group(id: String): AgentToolGroup? = groups.firstOrNull { it.id == id }

    /** 已打开组里的工具名；`null`/未知 id 返回空集合。 */
    fun toolsOfGroup(id: String?): Set<String> = id?.let(::group)?.toolNames ?: emptySet()

    /** 工具所属的组 id；未收录（配方工具、压缩档案等动态工具）返回 null。 */
    fun groupOfTool(name: String): String? = groups.firstOrNull { name in it.toolNames }?.id

    fun isDeferredTool(name: String): Boolean =
        groups.any { it.deferredByDefault && name in it.toolNames }

    /**
     * 一组工具名里出现过的延迟组。
     *
     * 用于跨 run 恢复「已打开」状态：会话历史里已经出现过某个组内工具的调用时，
     * 新的 run 必须让这些工具继续可见。否则用户每发一条新消息都要先花一轮
     * tools_use_group 重新打开，而模型看到历史里刚成功调用过，只会直接再调一次并被拒。
     */
    fun deferredGroupsOf(toolNames: Collection<String>): Set<String> =
        groups.asSequence()
            .filter { it.deferredByDefault }
            .filter { group -> group.toolNames.any { it in toolNames } }
            .map { it.id }
            .toSet()

    /**
     * 本轮应 withheld（从目录剔除）的工具：
     * - 未打开的延迟组：整组剔除；
     * - 单工具禁用集：无论组是否打开都剔除。
     */
    fun withheldTools(openedGroups: Set<String>, disabledTools: Set<String>): Set<String> =
        buildSet {
            groups.filter { it.deferredByDefault && it.id !in openedGroups }.forEach { addAll(it.toolNames) }
            addAll(disabledTools)
        }

    /**
     * 本轮的组可用性快照。**目录、索引与执行侧必须读同一份**：
     * 若 `tools_use_group` 的枚举不看开关，用户在设置里关掉某组后，模型仍被告知可以打开它——
     * 打开会成功返回，但下一轮组内工具永远不会出现，白花一轮且反复误导模型。
     */
    data class Availability(
        /** 已打开、且其组开关仍然允许的组。 */
        val opened: Set<String>,
        /** 尚未打开、但本轮的开关允许打开，因而值得在索引里宣传的组。 */
        val openable: Set<String>,
    )

    /** 当前能被打开的组 id（按 [groups] 的声明顺序）；给 `tools_use_group` 的枚举用。 */
    fun openableIds(availability: Availability): List<String> =
        groups.filter { it.id in availability.openable }.map { it.id }

    /**
     * 计算本轮的组可用性。[gate] 回答「这个组的开关此刻是否允许它存在」；
     * 已经打开但随后关掉开关的组会从 [Availability.opened] 里消失，因为它的工具本就不再下发。
     */
    fun availability(openedGroups: Set<String>, gate: (AgentToolGroup) -> Boolean): Availability {
        val allowed = groups.filter { it.deferredByDefault && gate(it) }.map { it.id }.toSet()
        return Availability(
            opened = openedGroups.filterTo(mutableSetOf()) { it in allowed },
            openable = allowed - openedGroups,
        )
    }

    /** 从 [ToolSwitchState] 推导组可用性；见 [ToolSwitchState] 对「用户设置值」的要求。 */
    fun availability(openedGroups: Set<String>, switches: ToolSwitchState): Availability =
        availability(openedGroups) { group -> groupEnabled(switches, group.id) }

    /** 组开关判定：组本身的开关 + 组内子开关不在此处（shell 影响的是目录内容而非组可用性）。 */
    fun groupEnabled(switches: ToolSwitchState, groupId: String): Boolean = when (groupId) {
        BASIC_GROUP.id -> switches.basicTools
        SCREEN_GROUP.id -> switches.screenTools
        WEB_GROUP.id -> switches.browserTools
        TERMINAL_GROUP.id -> switches.terminalTools
        DEVICE_GROUP.id -> switches.deviceTools
        CALENDAR_GROUP.id -> switches.deviceTools
        PERSONAL_GROUP.id -> switches.personalTools
        DEVICE_STATE_GROUP.id -> switches.personalTools
        EXTENSIONS_GROUP.id -> switches.extensionsTools
        else -> false
    }

    /**
     * 本轮被用户关闭的能力，按面向模型的说法列出。
     *
     * 为什么要告诉模型而不是静默省略：能力本来存在、只是开关被关掉。若什么都不说，
     * 模型面对「执行一下 ls」只能回答「我没有这个能力」——那是错的，用户会以为 Eta
     * 不支持；也可能转而尝试绕过（建议装 Termux、改用别的工具）。
     *
     * 与目录、组索引共用同一份开关判定，避免出现「提示说关了、目录里却有」的自相矛盾。
     * 措辞刻意克制：只在用户明确要求时说明，不主动推荐去开开关。
     */
    data class DisabledCapabilities(val labels: List<String>) {
        val isEmpty: Boolean get() = labels.isEmpty()
    }

    /**
     * 决定工具是否在场的开关快照；由调用方从既有配置/开关读取，避免各处重复取值。
     *
     * 每个字段回答的是**「用户是否在设置里关掉了它」**，而不是「本轮是否真的可用」。
     * 这两者必须区分：入口（小布、超级小爱、电源键）收窄配置时生效值也会是 false，
     * 若拿生效值生成关闭说明，就会对模型谎称「用户已在设置里关闭」，而用户其实什么都没关。
     * 因此这里只接受来自 Prefs 的用户设置值。
     */
    data class ToolSwitchState(
        val basicTools: Boolean = true,
        val screenTools: Boolean,
        val browserTools: Boolean,
        val terminalTools: Boolean,
        val shellTools: Boolean,
        val deviceTools: Boolean,
        val personalTools: Boolean,
        val extensionsTools: Boolean,
        val mediaSearchTools: Boolean,
        val delegationTools: Boolean,
    )

    /** 关闭说明；只有真正会导致工具缺席的项才列出。全开时零成本。 */
    fun disabledCapabilities(switches: ToolSwitchState): DisabledCapabilities {
        val labels = mutableListOf<String>()
        if (!switches.basicTools) labels += "基础交互（向用户提问、查看上下文、打开应用、语音）"
        if (!switches.screenTools) labels += "屏幕观察与 GUI 操作"
        if (!switches.browserTools) labels += "网页读取与浏览器"
        if (!switches.terminalTools) {
            labels += "终端与文件读写"
        } else if (!switches.shellTools) {
            labels += "命令执行（文件读写仍可用）"
        }
        if (!switches.deviceTools) labels += "设备控制（闹钟、计时器、音量、媒体、修改设置、应用与网络管理等）"
        if (!switches.personalTools) {
            labels += "个人数据读取（通讯录、短信、通话、日历、账单、便签、位置、设备状态等）"
        } else if (!switches.mediaSearchTools) {
            labels += "媒体语义搜索"
        }
        if (!switches.extensionsTools) labels += "Eta 自身配置与扩展管理"
        if (!switches.delegationTools) labels += "子代理委派"
        // 记忆与 Skills 已有各自的负面门禁（buildMemorySystemMessage / buildSkillSystemMessage），
        // 这里不再重复列出，避免同一件事被说两遍。
        return DisabledCapabilities(labels)
    }

    /** 关闭说明文案。刻意不写「主动提醒用户去开」：那会让模型在无关回复里推销设置项。 */
    fun disabledCapabilitiesMessage(disabled: DisabledCapabilities): String? {
        if (disabled.isEmpty) return null
        return "本次运行中，用户已在设置里关闭以下能力（这是正常配置，不是故障或设备限制）：" +
            disabled.labels.joinToString("、") + "。" +
            "用户要求其中某项操作时，如实说明该能力已被关闭、可在设置中开启，不要声称自己不具备这项能力，" +
            "也不要改用其它工具或建议安装第三方应用绕开。"
    }

    /**
     * `tools_use_group` 的系统消息索引：每个可打开的组一行，说明它装了什么。
     * 索引本身很短，替代的是整组 schema。
     * 只宣传 [Availability.openable] 里的组——开关关闭的组不该出现在提示里。
     *
     * 「何时才该打开」由表头和结尾各说一次，不再逐组重复；每行只留组 id 与内容摘要。
     */
    fun indexMessage(availability: Availability): String? {
        val available = groups.filter { it.id in availability.openable }
        if (available.isEmpty()) return null
        return buildString {
            appendLine("Deferred tool groups (schema not loaded yet; open one only when the task needs it):")
            available.forEach { group ->
                appendLine("- id=${group.id} | ${group.summary}")
            }
            append(
                "None of these tools are in your tool list yet. Call tools_use_group with an id to " +
                    "load it; its tools appear from the next round onward. Never call them before " +
                    "opening, and do not open a group you do not need.",
            )
        }
    }

    /** 旧组 id → 新组 id 的迁移映射（按助手配置里的历史值）。 */
    fun migrateLegacyGroupId(legacyId: String): Set<String> = when (legacyId) {
        // 旧快照里 device 是含日程的合组，恢复时日程工具也要一并可见。
        "device_direct", "device_sensitive" -> setOf(DEVICE_GROUP.id, CALENDAR_GROUP.id)
        // 旧两档敏感读取；拆组后各归其位（两者都不再对应合并后的 personal）。
        "personal_data" -> setOf(PERSONAL_GROUP.id)
        "device_state" -> setOf(DEVICE_STATE_GROUP.id)
        "self" -> setOf(EXTENSIONS_GROUP.id)
        "terminal", "screen", "web" -> setOf(legacyId)
        "basic", "device", "personal", "extensions", "calendar" -> setOf(legacyId)
        // media_search 等旧展示分组不再是开关组；无对应新组。
        else -> emptySet()
    }
}

/**
 * 一个工具组的定义。[summary] 只面向模型（[AgentToolGroups.indexMessage] 的一行索引）；
 * 设置页的组名走 strings 资源，不在这里。
 */
internal data class AgentToolGroup(
    val id: String,
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

    /**
     * 打开成功的回执。
     *
     * [availableTools] 必须是**本设备此刻真的能下发**的子集，而不是整组声明名：
     * 目录在组开关之外还要经过 AgentToolRequirements / AgentToolCapabilities / 单工具禁用集过滤。
     * 若照抄整组名单，模型会照着清单去调用一批永远不会出现的工具，连续被拒后终止整轮。
     */
    fun result(group: AgentToolGroup, availableTools: List<String>): String = JSONObject()
        .put("ok", true)
        .put("group", group.id)
        .put("tools", JSONArray(availableTools))
        .put("available", "next_turn")
        .put(
            "message",
            if (availableTools.isEmpty()) {
                "Group opened, but none of its tools can be used on this device right now " +
                    "(missing permission/root or disabled in settings). Do not call them; use another " +
                    "approach or tell the user what is missing."
            } else {
                "Group opened. Only the listed tools are usable on this device; they are available from " +
                    "the next round onward and are not part of the request you are answering right now. " +
                    "Call only these names — other tools of this group are unavailable here."
            },
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
