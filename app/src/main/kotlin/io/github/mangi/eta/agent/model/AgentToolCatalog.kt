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
        mediaSearchTools: Boolean = false,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
        localWebSearch: Boolean = true,
        /** 已按需打开的工具组 id；未打开的延迟组不下发，只留一行索引。 */
        openedToolGroups: Set<String> = emptySet(),
        /** 被用户单独禁用的工具名；组开关之下的细粒度控制。 */
        disabledTools: Set<String> = emptySet(),
        /** 文件系统与命令执行档位；决定 read/write/shell 工具各自是否下发。 */
        fileSystemTier: AgentFileSystemTier.Tier = AgentFileSystemTier.of(terminalTools, shellTools = true),
    ): JSONArray {
        // 组开关只决定「参与下发」；延迟折叠与单工具禁用统一在最后一次过滤完成，
        // 目录过滤、索引行与 tools_use_group 枚举共用同一份可用性判定，
        // 避免「开关关了但仍宣传该组」这类多处判断不一致的缺陷。
        val groupAvailability = AgentToolGroups.availability(openedToolGroups) { group ->
            when (group.id) {
                AgentToolGroups.BASIC_GROUP.id -> basicTools
                AgentToolGroups.SCREEN_GROUP.id -> screenTools
                AgentToolGroups.WEB_GROUP.id -> browserTools
                AgentToolGroups.TERMINAL_GROUP.id -> terminalTools
                AgentToolGroups.DEVICE_GROUP.id -> deviceTools
                AgentToolGroups.PERSONAL_GROUP.id -> personalTools
                AgentToolGroups.EXTENSIONS_GROUP.id -> extensionsTools
                else -> false
            }
        }
        val withheld = AgentToolGroups.withheldTools(openedToolGroups, disabledTools)
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
        if (extensionsTools) {
            AgentSelfSettingsToolCatalog.appendTo(emitted)
        }
        // 媒体语义搜索是个人数据组成员：两个开关都开着才参与下发。
        if (mediaSearchTools && personalTools) AgentMediaSearchToolCatalog.appendTo(emitted)
        // 只要存在「开关允许、但尚未打开」的组，就下发一个 tools_use_group。
        // 枚举只列这些组：全部打开、或开关全关时都不该出现一个必然失败或有多个空选项的工具。
        AgentToolGroupCatalog.appendTo(
            emitted,
            openableIds = AgentToolGroups.openableIds(groupAvailability),
        )
        if (AgentFileSystemTier.allowsFileTools(fileSystemTier)) {
            AgentFileVisionToolCatalog.appendTo(emitted)
            AgentTerminalToolCatalog.appendTo(emitted, fileSystemTier)
        }
        return capabilities.project(filterWithheld(emitted, withheld))
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
