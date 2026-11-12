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
            "ask_user", "get_current_context", "search_apps", "launch_app", "present_to_user", "open_uri",
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
     * 单独成组后，日历任务只付约 1.6k，其余能力不参与。
     *
     * 组开关跟随「个人数据」（[PERSONAL_GROUP] 的开关）：日程是用户自己的内容，
     * 不是设备控制。界面侧它也在个人数据卡里，不出独立卡片——模型侧的拆分纯为省 token。
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
        summary = "Read, create, modify and delete system calendar events directly (search/read by " +
            "event_id, list writable calendars, time formats, reminders, recurrence, ambiguity handling).",
        toolNames = CALENDAR_WRITE_NAMES + AgentDeviceToolCatalog.calendarReadToolNames(),
    )

    /**
     * 个人内容读取 + 媒体语义搜索。semantic_search_media 没有组级开关，
     * 是否下发只看个人数据开关与单工具禁用集。
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

    /**
     * 动态延迟组（MCP server）的 id 前缀。动态组不进 [groups]：成员由各 run 的
     * MCP 快照决定，静态定义里写不下；需要参与可用性/索引/解锁判定的地方
     * 通过 [extraGroups] 显式并入，保持「目录、索引与执行侧读同一份」的约定。
     */
    const val MCP_GROUP_PREFIX = "mcp:"

    fun mcpGroupId(serverId: String): String = "$MCP_GROUP_PREFIX$serverId"

    fun isMcpGroupId(groupId: String): Boolean = groupId.startsWith(MCP_GROUP_PREFIX)

    private fun allGroups(extraGroups: List<AgentToolGroup>): List<AgentToolGroup> =
        if (extraGroups.isEmpty()) groups else groups + extraGroups

    fun group(id: String, extraGroups: List<AgentToolGroup> = emptyList()): AgentToolGroup? =
        allGroups(extraGroups).firstOrNull { it.id == id }

    /** 已打开组里的工具名；`null`/未知 id 返回空集合。 */
    fun toolsOfGroup(id: String?): Set<String> = id?.let(::group)?.toolNames ?: emptySet()

    /** 工具所属的组 id；未收录（配方工具、压缩档案等动态工具）返回 null。 */
    fun groupOfTool(name: String, extraGroups: List<AgentToolGroup> = emptyList()): String? =
        allGroups(extraGroups).firstOrNull { name in it.toolNames }?.id

    /** 面向模型的组短名；关闭说明与「工具缺席」的原因共用一份措辞，避免两处漂移。 */
    fun label(groupId: String, extraGroups: List<AgentToolGroup> = emptyList()): String =
        extraGroups.firstOrNull { it.id == groupId }?.label ?: when (groupId) {
            BASIC_GROUP.id -> "基础交互"
            SCREEN_GROUP.id -> "屏幕观察与 GUI 操作"
            WEB_GROUP.id -> "网页读取与浏览器"
            TERMINAL_GROUP.id -> "终端与文件读写"
            DEVICE_GROUP.id -> "设备控制"
            CALENDAR_GROUP.id -> "日程读写"
            PERSONAL_GROUP.id -> "个人数据读取"
            DEVICE_STATE_GROUP.id -> "设备状态读取"
            EXTENSIONS_GROUP.id -> "Eta 自身配置与扩展管理"
            else -> groupId
        }

    fun isDeferredTool(name: String, extraGroups: List<AgentToolGroup> = emptyList()): Boolean =
        allGroups(extraGroups).any { it.deferredByDefault && name in it.toolNames }

    /**
     * 一组工具名里出现过的延迟组。
     *
     * 用于跨 run 恢复「已打开」状态：会话历史里已经出现过某个组内工具的调用时，
     * 新的 run 必须让这些工具继续可见。否则用户每发一条新消息都要先花一轮
     * tools_use_group 重新打开，而模型看到历史里刚成功调用过，只会直接再调一次并被拒。
     */
    fun deferredGroupsOf(
        toolNames: Collection<String>,
        extraGroups: List<AgentToolGroup> = emptyList(),
    ): Set<String> =
        allGroups(extraGroups).asSequence()
            .filter { it.deferredByDefault }
            .filter { group -> group.toolNames.any { it in toolNames } }
            .map { it.id }
            .toSet()

    /**
     * 本轮应 withheld（从目录剔除）的工具：
     * - 未打开的延迟组：整组剔除；
     * - 单工具禁用集：无论组是否打开都剔除。
     */
    fun withheldTools(
        openedGroups: Set<String>,
        disabledTools: Set<String>,
        extraGroups: List<AgentToolGroup> = emptyList(),
    ): Set<String> =
        buildSet {
            allGroups(extraGroups)
                .filter { it.deferredByDefault && it.id !in openedGroups }
                .forEach { addAll(it.toolNames) }
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

    /** 当前能被打开的组 id（静态组按声明顺序、动态组随后）；给 `tools_use_group` 的枚举用。 */
    fun openableIds(
        availability: Availability,
        extraGroups: List<AgentToolGroup> = emptyList(),
    ): List<String> {
        val static = groups.filter { it.id in availability.openable }.map { it.id }
        if (extraGroups.isEmpty()) return static
        return static + extraGroups.filter { it.id in availability.openable }.map { it.id }
    }

    /**
     * 计算本轮的组可用性。[gate] 回答「这个组的开关此刻是否允许它存在」；
     * 已经打开但随后关掉开关的组会从 [Availability.opened] 里消失，因为它的工具本就不再下发。
     * [extraGroups] 是动态延迟组（MCP server）：开关模型不同，由 [gate] 一并裁决。
     * 注意 [gate] 必须是最后一个参数：既有调用方使用尾随 lambda 语法。
     */
    fun availability(
        openedGroups: Set<String>,
        extraGroups: List<AgentToolGroup> = emptyList(),
        gate: (AgentToolGroup) -> Boolean,
    ): Availability {
        val allowed = allGroups(extraGroups)
            .filter { it.deferredByDefault && gate(it) }
            .map { it.id }
            .toSet()
        return Availability(
            opened = openedGroups.filterTo(mutableSetOf()) { it in allowed },
            openable = allowed - openedGroups,
        )
    }

    /** 组开关判定：组本身的开关 + 组内子开关不在此处（shell 影响的是目录内容而非组可用性）。 */
    fun groupEnabled(switches: ToolSwitchState, groupId: String): Boolean = when (groupId) {
        BASIC_GROUP.id -> switches.basicTools
        SCREEN_GROUP.id -> switches.screenTools
        WEB_GROUP.id -> switches.browserTools
        TERMINAL_GROUP.id -> switches.terminalTools
        DEVICE_GROUP.id -> switches.deviceTools
        // 日程跟随「个人数据」，不跟设备：日程是用户自己的内容，不是设备控制，
        // 界面侧也归在个人数据卡里（见 ToolCapabilities.PERSONAL）。
        CALENDAR_GROUP.id -> switches.personalTools
        PERSONAL_GROUP.id -> switches.personalTools
        DEVICE_STATE_GROUP.id -> switches.personalTools
        EXTENSIONS_GROUP.id -> switches.extensionsTools
        else -> false
    }

    /**
     * 本轮被用户关闭的**工具组**，按面向模型的说法列出。
     *
     * 为什么要告诉模型而不是静默省略：组本来存在、只是开关被关掉。若什么都不说，
     * 模型面对「执行一下 ls」只能回答「我没有这个能力」——那是错的，用户会以为 Eta
     * 不支持；也可能转而尝试绕过（建议装 Termux、改用别的工具）。
     *
     * 列的是组而不是单个工具：被关闭组的工具整组不在目录里，模型既不能调用，
     * `tools_use_group` 也无法把它打开（该枚举只含开关允许的组）。因此按组列名
     * 才能和「组打不开」这件事对上，避免模型反复尝试打开或重试。
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
        val delegationTools: Boolean,
    )

    /**
     * 关闭说明；只有真正会导致工具缺席的组才列出。全开时零成本。
     *
     * 按**组**列：开关关闭时整组缺席，`tools_use_group` 也打不开（见 [disabledCapabilitiesMessage]）。
     * 组名统一取 [label]，避免这里与组索引、校验器各写一套措辞后漂移。
     * 组内子开关（命令执行、媒体搜索）不是组，按更窄的损失单独说明。
     */
    fun disabledCapabilities(switches: ToolSwitchState): DisabledCapabilities {
        val labels = mutableListOf<String>()
        if (!switches.basicTools) labels += label(BASIC_GROUP.id) + "（向用户提问、查看上下文、打开应用、语音）"
        if (!switches.screenTools) labels += label(SCREEN_GROUP.id)
        if (!switches.browserTools) labels += label(WEB_GROUP.id)
        if (!switches.terminalTools) {
            labels += label(TERMINAL_GROUP.id)
        } else if (!switches.shellTools) {
            labels += "命令执行（文件读写仍可用）"
        }
        if (!switches.deviceTools) {
            labels += label(DEVICE_GROUP.id) + "（闹钟、计时器、音量、媒体、修改设置、应用与网络管理等）"
        }
        if (!switches.personalTools) {
            // 日程组与设备状态组都挂在个人数据开关上：只列个人内容会让模型以为仍能建日程。
            labels += label(PERSONAL_GROUP.id) + "（通讯录、短信、通话、账单、便签等）"
            labels += label(DEVICE_STATE_GROUP.id) + "（设置项、通知、位置、日志、网络与显示状态等）"
            labels += label(CALENDAR_GROUP.id)
        }
        if (!switches.extensionsTools) labels += label(EXTENSIONS_GROUP.id)
        if (!switches.delegationTools) labels += "子代理委派"
        // 记忆与 Skills 已有各自的负面门禁（buildMemorySystemMessage / buildSkillSystemMessage），
        // 这里不再重复列出，避免同一件事被说两遍。
        return DisabledCapabilities(labels)
    }

    /**
     * 关闭说明文案。刻意不写「主动提醒用户去开」：那会让模型在无关回复里推销设置项。
     *
     * 必须把「不能用」说到位：被关闭组的工具不在目录里，`tools_use_group` 的枚举也不含它，
     * 直接调用会被 [AgentToolCallValidator] 拒绝。只说「已被关闭」时，模型仍可能去打开、
     * 重试，或用终端/广播变相执行——这正是「关了 GUI 还在点屏幕」的成因。
     */
    fun disabledCapabilitiesMessage(disabled: DisabledCapabilities): String? {
        if (disabled.isEmpty) return null
        return "本次运行中，用户已在设置里关闭以下工具组，它们在本轮不可用（这是正常配置，不是故障或设备限制）：" +
            disabled.labels.joinToString("、") + "。" +
            "被关闭工具组里的工具不在本次的工具目录中，tools_use_group 也无法打开它们；" +
            "直接调用会被拒绝，重试或换参数都不会生效。" +
            "不要改用其它工具、终端命令、广播变相执行，也不要建议安装第三方应用绕开。" +
            "用户要求其中某项操作时，如实说明该能力已在设置里关闭、可在设置中开启，" +
            "不要声称自己不具备这项能力，也不要假装已经完成。"
    }

    /**
     * `tools_use_group` 的系统消息索引：每个可打开的组一行，说明它装了什么。
     * 索引本身很短，替代的是整组 schema。
     * 只宣传 [Availability.openable] 里的组——开关关闭的组不该出现在提示里。
     * [extraGroups] 是动态延迟组（MCP server），其行在静态组之后。
     *
     * 「何时才该打开」由表头和结尾各说一次，不再逐组重复；每行只留组 id 与内容摘要。
     * 结尾同时宣传 `tools_search`：只需要一两个工具时按关键词搜，命中的组自动解锁，
     * 省一次单独的打开轮次。
     */
    fun indexMessage(
        availability: Availability,
        extraGroups: List<AgentToolGroup> = emptyList(),
        /** 缓存优先档：加载后立即经 [AgentToolGroupCatalog.CALL] 调用，不用等下一轮。 */
        callsViaToolCall: Boolean = false,
    ): String? {
        val available = allGroups(extraGroups).filter { it.id in availability.openable }
        if (available.isEmpty()) return null
        return buildString {
            appendLine("Deferred tool groups (schema not loaded yet; open one only when the task needs it):")
            available.forEach { group ->
                appendLine("- id=${group.id} | ${group.summary}")
            }
            if (callsViaToolCall) {
                append(
                    "None of these tools are in your tool list. Call tools_use_group with an id, or " +
                        "tools_search with keywords when you only need one or two tools — a match opens the " +
                        "owning group for you. The result lists each tool's parameters; call them with " +
                        "${AgentToolGroupCatalog.CALL} ({name, arguments}) right away, same round is fine. " +
                        "Never call a tool before its group is loaded, and do not open a group you do not need.",
                )
            } else {
                append(
                    "None of these tools are in your tool list yet. Call tools_use_group with an id to " +
                        "load it, or tools_search with keywords when you only need one or two tools — " +
                        "a match opens the owning group for you. Their tools appear from the next round " +
                        "onward. Never call them before loading, and do not open a group you do not need.",
                )
            }
        }
    }

    /**
     * 首轮预开组的本地关键词预测。
     *
     * 只在会话首个 run 使用：把「几乎肯定要用」的组提前并进原生集合，模型就不必在
     * run 中途开组，`tools` 与 system 也因此保持字节稳定。命中不明确时宁可不预开——
     * 多开一组要付它的 schema，误判比漏判更贵。
     *
     * 关键词表就近写在组定义旁边，与 [summary] 描述的是同一批能力，改组时一起改。
     */
    fun predictFromText(text: String, allowed: (String) -> Boolean): Set<String> {
        if (text.isBlank()) return emptySet()
        val normalized = text.lowercase()
        val predicted = linkedSetOf<String>()
        for (group in deferredGroups) {
            if (predicted.size >= MAX_PREDICTED_GROUPS) break
            if (!allowed(group.id)) continue
            val keywords = PREFETCH_KEYWORDS[group.id] ?: continue
            if (keywords.any { normalized.contains(it) }) predicted += group.id
        }
        return predicted
    }

    /** 预开关键词：组 id → 小写匹配片段。只放强信号，避免「打开」这类泛词误开整组。 */
    private val PREFETCH_KEYWORDS: Map<String, List<String>> = mapOf(
        SCREEN_GROUP.id to listOf(
            "屏幕", "截图", "界面", "点击", "点一下", "滑动", "手势", "输入框",
            "screenshot", "tap ", "swipe",
        ),
        WEB_GROUP.id to listOf(
            "网页", "浏览器", "网站", "链接", "http://", "https://", "抓取",
            "browser", "url",
        ),
        TERMINAL_GROUP.id to listOf(
            "终端", "命令", "shell", "文件", "目录", "代码", "编译", "git",
            "脚本", "apk", "安装包", "python", "node ", "run_command",
        ),
        DEVICE_GROUP.id to listOf(
            "闹钟", "计时器", "音量", "铃声", "勿扰", "静音", "亮度", "飞行模式",
            "播放", "暂停", "卸载", "安装应用",
        ),
        CALENDAR_GROUP.id to listOf("日程", "日历", "提醒", "会议", "约会", "行程", "calendar"),
        PERSONAL_GROUP.id to listOf(
            "联系人", "短信", "通话记录", "相册", "照片", "账单", "待办", "录音",
        ),
        DEVICE_STATE_GROUP.id to listOf(
            "电量", "存储空间", "剩余空间", "通知栏", "定位", "logcat", "dumpsys", "网络状态",
        ),
        EXTENSIONS_GROUP.id to listOf("记住", "记忆", "配方", "自定义工具", "安装技能", "skill"),
    )

    /**
     * 组的使用要点（system 里那段指引的压缩版）。
     *
     * 缓存优先档不再把各组指引写进 system——那会让组开关改动整段前缀。模型打开组时，
     * 要点随组加载回执一起给出，仍然在调用前看得到「这组该怎么用」。
     */
    fun groupUsage(groupId: String): String? = when (groupId) {
        SCREEN_GROUP.id ->
            "先 observe_screen 读 UI 树；只有确实需要画面时才 include_screenshot=true，并保持 " +
                "include_ui_tree=true，让截图与节点来自同一次观察。点击优先 tap_element/tap_area，" +
                "坐标工具必须写明 coordinate_space（截图定位用 normalized 0–999，节点坐标用 screen），" +
                "节点调用要把同一次观察的 observation_id 传回。动作结果先读 after 判断是否生效；" +
                "返回 ACTION_OUTCOME_UNKNOWN 或 DIRECTION_MISMATCH 必须先重新观察，不要重放动作。"
        WEB_GROUP.id ->
            "读取网页用网页工具，不要用 open_uri 代替读取；截图默认视口，整页用 full_page=true。" +
                "用户打开 Eta 浏览器页会接管当前会话的 WebView，期间网页工具暂停。"
        TERMINAL_GROUP.id ->
            "Android 文件与系统命令用 environment=android，用户选择 Alpine/Debian 时统一用 " +
                "environment=linux。连续多步先 action=open 取 session_id 再 action=exec 复用；" +
                "长命令用 async=true 后 read_async_result 轮询；常驻服务用 daemon_start，不要用 nohup 或 &。"
        DEVICE_GROUP.id ->
            "设备直写工具跟随用户开关；日程读写需要运行时权限，闹钟直写需要 Root 与系统时钟库，" +
                "缺少权限时如实说明，不要改用 Shell 绕过。"
        CALENDAR_GROUP.id ->
            "读日程用 search_calendar_events / list_calendars，写日程用 create_calendar_event(s) / " +
                "update_calendar_event / delete_calendar_event；写入需要日历权限。"
        PERSONAL_GROUP.id ->
            "联系人、短信、通话记录、相册、账单等属于个人数据；只读取完成任务所必需的部分，" +
                "不要批量导出，也不要把内容写进最终答复之外的地方。"
        DEVICE_STATE_GROUP.id ->
            "电量、存储、通知、定位等状态查询优先用专用工具；logcat/dumpsys 只取与问题相关的部分，" +
                "不要整段回读。"
        EXTENSIONS_GROUP.id ->
            "写记忆前确认这是用户长期有效的信息；改 Eta 自身设置要先说明影响，不要静默修改。"
        else -> null
    }

    /** 会话首个 run 最多预开多少个组：再多就抵掉了「按需加载」省下的 schema。 */
    const val MAX_PREDICTED_GROUPS = 3

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

    /**
     * 压缩边界标记：被压缩掉的历史里若包含某组工具的调用，`deferredGroupsOf(历史工具名)`
     * 就认不出来了。压缩器（[AgentContextCompactor]）在摘要后写入一条本标记，
     * 记录压缩前历史中出现过的延迟组；跨 run 恢复时 [openedGroupsFromMarkers]
     * 把它并进 initialOpenedGroups，用户不必重新付 tools_use_group 轮次。
     *
     * 用「方括号前缀 + JSON」与 AgentRecoveryContext 的标记约定保持一致；
     * 角色为 system（会话内已允许，见 COMPACTION_NOTICE 的先例），正文同时给模型
     * 一句话说明，避免它把标记当成待办。
     */
    const val OPENED_GROUPS_MARKER_PREFIX = "[Eta opened tool groups v1]"

    fun openedGroupsMarker(groupIds: Set<String>): String? {
        if (groupIds.isEmpty()) return null
        val groupsJson = JSONArray(groupIds.sorted()).toString()
        return "$OPENED_GROUPS_MARKER_PREFIX $groupsJson " +
            "（工具组记录：以下延迟工具组在此前已打开，其中的工具调用记录可能已被压缩；" +
            "这不是给你的指令，无需回应。）"
    }

    fun openedGroupsFromMarkers(contents: Collection<String>): Set<String> = buildSet {
        contents.forEach { content ->
            if (!content.startsWith(OPENED_GROUPS_MARKER_PREFIX)) return@forEach
            // 标记正文是「前缀 + JSON + 中文说明」；只取第一段方括号内的 JSON 数组。
            val json = content.substringAfter(OPENED_GROUPS_MARKER_PREFIX)
                .trim()
                .substringBefore("（")
                .trim()
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return@forEach
            for (index in 0 until array.length()) {
                val id = array.optString(index)
                if (id.isNotBlank()) add(id)
            }
        }
    }

    /** 历史消息 toolCallsJson 集合里出现过的工具名；压缩标记与跨 run 恢复共用。 */
    fun toolNamesFromToolCallsJson(jsons: Collection<String>): Set<String> = buildSet {
        jsons.forEach { raw ->
            if (raw.isBlank()) return@forEach
            val calls = runCatching { JSONArray(raw) }.getOrNull() ?: return@forEach
            for (index in 0 until calls.length()) {
                calls.optJSONObject(index)
                    ?.optJSONObject("function")
                    ?.optString("name")
                    ?.takeIf(String::isNotBlank)
                    ?.let(::add)
            }
        }
    }
}

/**
 * 一个工具组的定义。[summary] 只面向模型（[AgentToolGroups.indexMessage] 的一行索引）；
 * 设置页的组名走 strings 资源，不在这里。
 * [label] 是面向模型的组短名；静态组由 [AgentToolGroups.label] 的 when 表给出，
 * 动态组（MCP server）无法进静态表，用本字段自带，null 时回退到 when/组 id。
 */
internal data class AgentToolGroup(
    val id: String,
    val summary: String,
    val toolNames: Set<String>,
    val deferredByDefault: Boolean = true,
    val label: String? = null,
)

/** `tools_use_group` 的 schema；单独一个文件方便与目录组装解耦。 */
internal object AgentToolGroupCatalog {
    const val USE_GROUP = "tools_use_group"

    /**
     * 缓存优先转发（search/group 档共用）的通用调用入口名。
     *
     * 延迟组的工具不再进入 `tools`：每次开组都会改写请求前缀，而 `tools` 排在 system
     * 与历史之前，一改就让 provider 的前缀缓存整段失效。改由这个 schema 恒定的入口转发，
     * 工具的名字与参数说明随组加载回执以**工具结果**的形式进入历史尾部（后缀），
     * 前缀因此保持不变。
     */
    const val CALL = "tool_call"

    /**
     * 通用调用入口的固定 schema：与会话、已加载组、设备能力全部无关。
     *
     * [recipesViaCall] 为 true 时额外说明「自建配方也走这里」：缓存优先档不把配方 schema
     * 放进 `tools`（否则模型每新建一份配方就改写一次前缀），入口描述是模型唯一常驻的线索。
     * 措辞恒定、不含任何配方名，因此不会反过来引入抖动。
     */
    fun toolCallEntry(recipesViaCall: Boolean = false): JSONObject = AgentToolSchema.function(
        name = CALL,
        description = "Call a tool from a loaded deferred group. `name` must be one listed by the " +
            "most recent tools_use_group / tools_search result; `arguments` must follow the " +
            "parameter documentation in that same result." +
            if (recipesViaCall) {
                " Also calls your own recipes: `name` is the recipe_<name> given by eta_author_tool " +
                    "or eta_list_recipes, and `arguments` follows the parameters documented in that " +
                    "same result. Recipes never appear as native tools in this mode."
            } else {
                ""
            },
        parameters = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put(
                        "name",
                        JSONObject()
                            .put("type", "string")
                            .put("description", "Tool name listed by the latest group-load result."),
                    )
                    .put(
                        "arguments",
                        JSONObject()
                            .put("type", "object")
                            .put("description", "Arguments for that tool, as documented in the group-load result."),
                    ),
            )
            // 只要求 name：无参工具可以省略 arguments，执行侧会按 {} 处理。
            .put("required", JSONArray().put("name")),
    )

    /**
     * @param allowEmpty openableIds 为空时仍下发工具。control 模式把可打开组收窄到空集
     * （闲聊或判断模型未选中任何组）时，若直接摘掉工具，模型只能看到「工具不存在」，
     * 容易改试其他工具名并连续被拒；保留工具可以让它拿到一条解释性错误。
     * 此时不写 `enum`（空数组在部分网关会被判为非法 schema），改用描述说明本轮无可用组。
     */
    fun appendTo(
        tools: JSONArray,
        openableIds: List<String>,
        disabled: Boolean = false,
        allowEmpty: Boolean = false,
    ) {
        if (disabled || (openableIds.isEmpty() && !allowEmpty)) return
        val empty = openableIds.isEmpty()
        val groupProperty = JSONObject()
            .put("type", "string")
            .put(
                "description",
                if (empty) {
                    "No deferred group can be opened in this run. Calling this tool returns an " +
                        "explanatory error; do not retry it and do not guess other group ids."
                } else {
                    "Deferred group id from the system-prompt index."
                },
            )
        if (!empty) groupProperty.put("enum", JSONArray(openableIds))
        tools.put(
            AgentToolSchema.function(
                name = USE_GROUP,
                description = "Load a deferred tool group so its tools become available from the next " +
                    "round onward. Use it only when the current task actually needs those tools; the " +
                    "group index in the system prompt lists what each one contains. Opening a group " +
                    "never grants permissions the device or settings would otherwise deny.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("group", groupProperty))
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
    fun result(
        group: AgentToolGroup,
        availableTools: List<String>,
        /** 每个工具的名字、描述与参数 schema；缓存优先档用它替代下发原生 schema。 */
        toolDocs: JSONArray? = null,
        /** 该组的使用指引（原先挂在 system 里的段落），随回执一起给出。 */
        usage: String? = null,
        /** true = 该组工具不在 `tools` 里，必须经 [AgentToolGroupCatalog.CALL] 调用。 */
        requiresToolCall: Boolean = false,
    ): String = JSONObject().apply {
        put("ok", true)
        put("group", group.id)
        put("tools", JSONArray(availableTools))
        toolDocs?.let { put("tool_docs", it) }
        usage?.takeIf { it.isNotBlank() }?.let { put("usage", it) }
        put("available", if (requiresToolCall) "now" else "next_turn")
        put("call_via", if (requiresToolCall) AgentToolGroupCatalog.CALL else "direct")
        put(
            "message",
            when {
                availableTools.isEmpty() ->
                    "Group opened, but none of its tools can be used on this device right now " +
                        "(missing permission/root or disabled in settings). Do not call them; use another " +
                        "approach or tell the user what is missing."
                requiresToolCall ->
                    "Group loaded. Call these tools with `${AgentToolGroupCatalog.CALL}` " +
                        "({name, arguments}); tool_docs carries each tool's parameters. They are callable " +
                        "right now, no extra round needed. Call only the listed names."
                else ->
                    "Group opened. Only the listed tools are usable on this device; they are available from " +
                        "the next round onward and are not part of the request you are answering right now. " +
                        "Call only these names — other tools of this group are unavailable here."
            },
        )
    }.toString()

    fun unknownGroup(requested: String, known: List<String>): String = JSONObject()
        .put("ok", false)
        .put("code", "UNKNOWN_TOOL_GROUP")
        .put("group", requested)
        .put("available_groups", JSONArray(known.sorted()))
        .put("message", "No such deferred group. Use one of available_groups.")
        .toString()
}
