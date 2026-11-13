package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    /**
     * 按统一分组（[AgentToolGroups]）下发工具：
     * - 组开关决定该组的工具是否参与下发（生效值 = 用户开关 && 入口收窄）；
     * - 除基础交互外所有组默认延迟：未打开时整组被 [AgentToolGroups.withheldTools] 剔除，
     *   只留索引与一个 `tools_use_group`；
     * - [disabledTools] 是单工具禁用集，无论组是否打开都剔除。
     */
    fun build(
        basicTools: Boolean = true,
        terminalTools: Boolean,
        browserTools: Boolean,
        /** 屏幕观察与 GUI 操作总开关；关闭时不声明任何无障碍界面工具。 */
        screenTools: Boolean = true,
        deviceTools: Boolean = false,
        personalTools: Boolean = false,
        extensionsTools: Boolean = true,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        /** 调度台专用：只在调度助手身份的 run 里下发（见 AgentDispatchAssistant）。 */
        dispatchTools: Boolean = false,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
        localWebSearch: Boolean = true,
        /** 已按需打开的工具组 id；未打开的延迟组不下发，只留一行索引。 */
        openedToolGroups: Set<String> = emptySet(),
        /** 被用户单独禁用的工具名；组开关之下的细粒度控制。 */
        disabledTools: Set<String> = emptySet(),
        /** 文件系统与命令执行档位；决定 read/write/shell 工具各自是否下发。 */
        fileSystemTier: AgentFileSystemTier.Tier = AgentFileSystemTier.of(terminalTools, shellTools = true),
        /**
         * 判断模型收窄后的可打开组上限（control 模式）。
         * null = 不接管；空集 = fail-closed，只剩基础交互。
         */
        openableGroupLimit: Set<String>? = null,
        /**
         * control 收窄到空集时仍保留 `tools_use_group`（见 [AgentToolGroupCatalog.appendTo]）。
         * 只对「决策接管且枚举为空」生效；用户把工具组全关时不恢复。
         */
        allowEmptyGroupTool: Boolean = false,
        /**
         * 动态延迟组（MCP server）：参与可用性与枚举，成员工具不在这里下发
         * （MCP schema 由 AgentModelClient 按已打开 server 追加），这里只为它们
         * 产出索引行、tools_use_group 枚举与 withheld 集合。
         */
        dynamicGroups: List<AgentToolGroup> = emptyList(),
        /**
         * 搜索索引出口：把「开关允许的所有工具」的名字→描述在 withheld 过滤前写进去，
         * 供 `tools_search` 对未打开延迟组做关键词匹配（调用方在每轮重建时清空重填）。
         * null 表示调用方不需要搜索。
         */
        searchIndex: MutableMap<String, String>? = null,
        /**
         * 是否下发 tools_search（渐进加载按工具档）。按组档为 false：延迟组只能整组打开，
         * 不提供工具级搜索——目录其余部分与按工具档完全一致。
         */
        toolSearchEnabled: Boolean = true,
        /**
         * 语音快捷功能预设：非 null 时跳过延迟组解锁，只保留这些工具（空集 = 零工具纯对话）。
         *
         * 预设工具不因「所属延迟组未打开」被折叠，但仍受组开关、单工具禁用与能力投影约束；
         * 同时不再下发 `tools_search` / `tools_use_group`，判断模型也不参与收窄。
         */
        presetToolNames: Set<String>? = null,
        /**
         * 决策闲聊态（chatOnly）逃生通道：true 时「工具发现面」（tools_use_group 枚举、
         * tools_search 下发、组索引）不再按决策收窄过滤，改按用户开关全集渲染。
         *
         * 背景（2026-11-14 日志取证）：control 模式把「运行一个空程序」判成闲聊后，
         * openableGroupLimit 收窄到空集 → tools_use_group 空枚举、tools_search 消失、
         * 组索引整段被裁，模型既看不到终端组存在、也没有任何自救通道，只能如实回答
         * 「我没有终端工具」（假话）。原则：决策收窄管「schema 是否原生下发」，
         * 不管「能不能发现」——模型主动打开的组仍逐次经过执行侧闸门复查。
         *
         * 只影响发现面；`schemaUnlocked`/原生 schema 下发仍严格按决策与已打开集合，
         * 用户组开关（groupGate）是任何情况下都不可翻越的上限。
         */
        groupDiscoveryUnrestricted: Boolean = false,
        /**
         * 缓存优先转发的会话级原生组集合（search/group 档共用）；null = 关闭该路径，
         * 保持既有渐进加载（off 档）。
         *
         * 非 null 时只有集合内的延迟组下发原生 schema，其余延迟组一律不下发，改由
         * [AgentToolGroupCatalog.CALL] 转发（工具说明作为组加载回执进入历史尾部）。
         * 这样 `tools` 在会话内字节恒定，provider 的前缀缓存只在集合增长时失效一次。
         */
        nativeGroups: Set<String>? = null,
    ): JSONArray {
        // 组开关只决定「参与下发」；延迟折叠与单工具禁用统一在最后一次过滤完成，
        // 目录过滤、索引行与 tools_use_group 枚举共用同一份可用性判定，
        // 避免「开关关了但仍宣传该组」这类多处判断不一致的缺陷。
        val groupGate: (AgentToolGroup) -> Boolean = { group ->
            when (group.id) {
                AgentToolGroups.BASIC_GROUP.id -> basicTools
                AgentToolGroups.SCREEN_GROUP.id -> screenTools
                AgentToolGroups.WEB_GROUP.id -> browserTools
                AgentToolGroups.TERMINAL_GROUP.id -> terminalTools
                AgentToolGroups.DEVICE_GROUP.id -> deviceTools
                // 日程跟随个人数据开关，与 AgentToolGroups.groupEnabled / AgentPromptBuilder
                // / AgentLocalTools 的执行侧闸门保持同一套映射。
                AgentToolGroups.CALENDAR_GROUP.id -> personalTools
                AgentToolGroups.PERSONAL_GROUP.id -> personalTools
                AgentToolGroups.DEVICE_STATE_GROUP.id -> personalTools
                AgentToolGroups.EXTENSIONS_GROUP.id -> extensionsTools
                // 动态组（MCP server）：存在即允许；决策收窄不排除它（决策模型只认识
                // 静态组），否则原本常驻可用的 MCP 能力会被收窄整块锁死。
                else -> AgentToolGroups.isMcpGroupId(group.id)
            }
        }
        val groupAvailability = AgentToolGroups.availability(openedToolGroups, dynamicGroups, groupGate)
        // 缓存优先档：下发的「已解锁」集合固定为会话级原生组，与 run 内开组无关。
        val suffixMode = nativeGroups != null && presetToolNames == null
        val schemaUnlocked = if (suffixMode) nativeGroups.orEmpty() else openedToolGroups
        val withheld = if (presetToolNames == null) {
            AgentToolGroups.withheldTools(schemaUnlocked, disabledTools, dynamicGroups)
        } else {
            // 预设工具直接可见：先只按「延迟组是否打开」折叠，再豁免预设名单，
            // 最后并入单工具禁用——用户关掉的工具不会因为进了预设而被重新打开。
            AgentToolGroups.withheldTools(schemaUnlocked, emptySet(), dynamicGroups)
                .minus(presetToolNames) + disabledTools
        }
        val emitted = JSONArray()
        if (basicTools) AgentQuestionToolCatalog.appendTo(emitted)
        AgentContextAppToolCatalog.appendTo(emitted, screenTools = screenTools, basicTools = basicTools)
        if (basicTools) AgentSpeechToolCatalog.appendTo(emitted)
        if (screenTools) {
            AgentGestureToolCatalog.appendTo(emitted)
            AgentTextSystemToolCatalog.appendTo(emitted)
        }
        AgentDeviceToolCatalog.appendTo(
            emitted,
            directTools = deviceTools,
            sensitiveReadTools = personalTools,
            sensitiveActionTools = deviceTools,
            calendarReadTools = personalTools,
            calendarWriteTools = personalTools,
        )
        if (browserTools) {
            AgentWebToolCatalog.appendTo(emitted, includeSearch = localWebSearch)
            AgentBrowserToolCatalog.appendTo(emitted)
        }
        // Skills 核心读取属于基础交互组；GitHub 发现/安装属于扩展组，随扩展组开关下发。
        if (basicTools) {
            AgentSkillToolCatalog.appendTo(
                emitted,
                githubDiscovery = skillGitHubDiscovery && extensionsTools,
                githubInstall = skillGitHubInstall && extensionsTools,
            )
        }
        if (memoryTools) AgentMemoryToolCatalog.appendTo(emitted)
        if (dispatchTools) AgentDispatchToolCatalog.appendTo(emitted)
        if (extensionsTools) {
            AgentSelfSettingsToolCatalog.appendTo(emitted, recipesViaCall = suffixMode)
        }
        // 媒体语义搜索是个人数据组成员：个人数据开关放行、未被单独禁用才可见
        // （禁用与否由最后的 withheld 过滤统一裁决，没有独立的组级开关）。
        if (personalTools) AgentMediaSearchToolCatalog.appendTo(emitted)
        // 只要存在「开关允许、但尚未打开」的组，就下发一个 tools_use_group。
        // 枚举只列这些组：全部打开、或开关全关时都不该出现一个必然失败或有多个空选项的工具。
        // 决策收窄（control）时再按 openableGroupLimit 过滤：提示里不能点名一个 gate 允许、
        // 但被判断模型排除的组，否则模型会反复尝试一个必然被拒的组。
        // 动态组（MCP）不受决策枚举约束：决策模型只认识静态组，见 availability 注释。
        // 闲聊逃生态（groupDiscoveryUnrestricted）例外：枚举回到 gate 全集，让模型在
        // 决策判闲聊后仍能发现并打开它实际需要的组（执行侧会按同一次决策复查放行）。
        val openable = if (presetToolNames != null) {
            // 快捷功能运行跳过渐进加载：没有可搜索/可解锁的组，目录一次到位。
            emptyList<String>()
        } else if (groupDiscoveryUnrestricted) {
            AgentToolGroups.openableIds(
                AgentToolGroups.availability(
                    if (suffixMode) nativeGroups.orEmpty() else openedToolGroups,
                    dynamicGroups,
                    groupGate,
                ),
                dynamicGroups,
            )
        } else if (suffixMode) {
            // 枚举必须与「本轮已打开」无关：否则开一个组就改 tools 字节，整段前缀失效。
            // 这里以会话级原生组当作「已解锁」，只看用户开关与决策收窄。
            AgentToolGroups.openableIds(
                AgentToolGroups.availability(nativeGroups.orEmpty(), dynamicGroups, groupGate),
                dynamicGroups,
            ).filter { groupId ->
                openableGroupLimit == null || groupId in openableGroupLimit ||
                    AgentToolGroups.isMcpGroupId(groupId)
            }
        } else {
            AgentToolGroups.openableIds(groupAvailability, dynamicGroups)
                .filter { groupId ->
                    openableGroupLimit == null || groupId in openableGroupLimit ||
                        AgentToolGroups.isMcpGroupId(groupId)
                }
        }
        AgentToolGroupCatalog.appendTo(
            emitted,
            openableIds = openable,
            // 只在「决策接管且被收窄到空集」时保留工具；gate 全被关掉时仍需返回 true 让模型
            // 看到「组已在设置中关闭」的解释性错误。预设运行始终不下发空枚举提示。
            allowEmpty = presetToolNames == null &&
                allowEmptyGroupTool && openableGroupLimit != null && openable.isEmpty(),
        )
        // 工具级搜索与 tools_use_group 同一门面：有可打开的组才有可搜索的对象；
        // 按组档不下发（模型仍可用 tools_use_group 整组打开）；预设运行两者都不给。
        AgentToolSearchCatalog.appendTo(
            emitted,
            openable,
            toolSearchEnabled && presetToolNames == null,
        )
        if (AgentFileSystemTier.allowsFileTools(fileSystemTier)) {
            AgentFileVisionToolCatalog.appendTo(emitted)
            AgentTerminalToolCatalog.appendTo(emitted, fileSystemTier)
        }
        // 搜索索引在 withheld 过滤**之前**填：未打开延迟组的名字与描述正是搜索的对象。
        // 组开关已经作用在 emitters 上，因此索引天然遵守「用户关掉的能力不可搜」。
        if (searchIndex != null) {
            for (index in 0 until emitted.length()) {
                val function = emitted.optJSONObject(index)?.optJSONObject("function") ?: continue
                val name = function.optString("name")
                val description = function.optString("description")
                if (name.isNotEmpty()) searchIndex[name] = description
            }
        }
        val projected = capabilities.project(filterWithheld(emitted, withheld))
        if (!suffixMode) return presetToolNames?.let { retainOnly(projected, it) } ?: projected
        // 通用入口放在能力投影之后：它是固定门面，不属于任何组，也不该被投影规则剔除
        // （AgentToolCapabilities 不认识这个名字，会按 UNKNOWN_TOOL 丢掉）。
        projected.put(AgentToolGroupCatalog.toolCallEntry(recipesViaCall = suffixMode && extensionsTools))
        return projected
    }

    /**
     * 快捷功能预设目录：只保留预设点名的工具（空集返回空目录）。
     *
     * 基础交互组不隐式保留：翻译/整理是零工具纯对话；创建日程在预设里显式带上所需基础工具。
     */
    fun retainOnly(tools: JSONArray, toolNames: Set<String>): JSONArray {
        if (toolNames.isEmpty()) return JSONArray()
        val kept = JSONArray()
        for (index in 0 until tools.length()) {
            val item = tools.optJSONObject(index) ?: continue
            val name = item.optJSONObject("function")?.optString("name").orEmpty()
            if (name in toolNames) kept.put(item)
        }
        return kept
    }

    /**
     * 目录里真实存在的工具名。
     *
     * 提示只能点名这里的工具：模型照着提示去调一个没下发的工具，会被
     * [AgentToolCallValidator] 以「工具未在本次运行的能力目录中声明」拒掉，
     * 连续三次直接终止整轮。
     */
    fun toolNames(tools: JSONArray): Set<String> = buildSet {
        for (index in 0 until tools.length()) {
            tools.optJSONObject(index)
                ?.optJSONObject("function")
                ?.optString("name")
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }
    }

    private fun filterWithheld(emitted: JSONArray, withheld: Set<String>): JSONArray {
        if (withheld.isEmpty()) return emitted
        val kept = JSONArray()
        for (index in 0 until emitted.length()) {
            val tool = emitted.getJSONObject(index)
            val name = tool.optJSONObject("function")?.optString("name").orEmpty()
            if (name in withheld) continue
            kept.put(tool)
        }
        return kept
    }
}
