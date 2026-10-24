package io.github.mangi.eta.agent.model

import org.json.JSONArray
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        selfSettingsTools: Boolean = true,
        mediaSearchTools: Boolean = false,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
        localWebSearch: Boolean = true,
        /** 已按需打开的工具域 id；未打开的低频域不下发，只留一行索引。 */
        openedToolGroups: Set<String> = emptySet(),
    ): JSONArray =
        capabilities.project(JSONArray().also { tools ->
            AgentQuestionToolCatalog.appendTo(tools)
            AgentContextAppToolCatalog.appendTo(tools)
            AgentSpeechToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
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
                // 只要存在「可折叠但尚未打开的域」，就下发一个 tools_use_group 让模型按需展开。
                // 没有可打开的域时不下发：避免出现一个调用必然失败的枚举工具。
                val openable = AgentToolGroups.groups
                    .filter { it.deferredByDefault }
                    .map { it.id }
                AgentToolGroupCatalog.appendTo(
                    tools,
                    openableIds = openable,
                    disabled = openable.all { it in openedToolGroups },
                )
            }
            if (mediaSearchTools) AgentMediaSearchToolCatalog.appendTo(tools)
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
            }
        })
}
