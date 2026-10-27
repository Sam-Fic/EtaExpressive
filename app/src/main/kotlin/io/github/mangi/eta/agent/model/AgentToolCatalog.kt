package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        /** 屏幕观察与 GUI 操作总开关；关闭时不声明任何无障碍界面工具。 */
        screenTools: Boolean = true,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        personalDataReads: Boolean = deviceSensitiveReadTools,
        deviceStateReads: Boolean = deviceSensitiveReadTools,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        selfSettingsTools: Boolean = true,
        mediaSearchTools: Boolean = false,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
        localWebSearch: Boolean = true,
        /** 已按需打开的工具域 id；未打开的低频域不下发，只留一行索引。 */
        openedToolGroups: Set<String> = emptySet(),
        /** 文件系统与命令执行档位；决定 read/write/shell 工具各自是否下发。 */
        fileSystemTier: AgentFileSystemTier.Tier = AgentFileSystemTier.of(terminalTools, shellTools = true),
    ): JSONArray =
        capabilities.project(JSONArray().also { tools ->
            AgentQuestionToolCatalog.appendTo(tools)
            AgentContextAppToolCatalog.appendTo(tools, screenTools = screenTools)
            AgentSpeechToolCatalog.appendTo(tools)
            if (screenTools) {
                AgentGestureToolCatalog.appendTo(tools)
                AgentTextSystemToolCatalog.appendTo(tools)
            }
            // 域可用性只算一次，目录过滤、索引行与 tools_use_group 枚举共用同一份，
            // 避免「开关关了但仍宣传该域」这类三处判断不一致的缺陷。
            val groupAvailability = AgentToolGroups.availability(openedToolGroups) { group ->
                when (group.id) {
                    AgentToolGroups.SELF_SETTINGS_GROUP.id -> selfSettingsTools
                    AgentToolGroups.DEVICE_ACTION_GROUP.id ->
                        deviceSensitiveActionTools
                    AgentToolGroups.DEVICE_TAIL_READ_GROUP.id ->
                        deviceSensitiveReadTools && (personalDataReads || deviceStateReads)
                    else -> false
                }
            }
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
                personalDataReads = personalDataReads,
                deviceStateReads = deviceStateReads,
                // 冷门只读长尾默认折叠；改设备的写操作默认折叠（省 token，也少误触）。
                withheldReads = AgentToolGroups.withheldTailReads(openedToolGroups),
                withheldActions = AgentToolGroups.withheldDeviceActions(openedToolGroups),
            )
            if (browserTools) {
                AgentWebToolCatalog.appendTo(tools, includeSearch = localWebSearch)
                AgentBrowserToolCatalog.appendTo(tools)
            }
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools)
            if (selfSettingsTools) {
                AgentSelfSettingsToolCatalog.appendTo(
                    tools,
                    withheld = AgentToolGroups.withheldSelfSettingsTools(openedToolGroups, selfSettingsToolsEnabled = true),
                )
            }
            // 只要存在「开关允许、但尚未打开」的域，就下发一个 tools_use_group。
            // 枚举只列这些域：全部打开、或开关全关时都不该出现一个必然失败或有多个空选项的工具。
            AgentToolGroupCatalog.appendTo(
                tools,
                openableIds = AgentToolGroups.openableIds(groupAvailability),
            )
            if (mediaSearchTools) AgentMediaSearchToolCatalog.appendTo(tools)
            if (AgentFileSystemTier.allowsFileTools(fileSystemTier)) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools, fileSystemTier)
            }
        })
}
