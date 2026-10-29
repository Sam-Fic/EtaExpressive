package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.config.InteractiveModePreference
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray

/**
 * 估算下一轮请求里不在对话历史中的固定开销：系统提示、Skills/记忆注入、工具 JSON Schema。
 *
 * 第一轮还没有账单时，把它加进本地估算；已有账单时只把「当前开销 - 账单当时开销」当作差额。
 */
internal object AgentRequestOverhead {
    fun estimate(
        config: AgentModelClient.ModelConfig,
        skillContext: SkillContext = SkillContext.EMPTY,
        memoryContext: AgentMemoryContext = AgentMemoryContext.DISABLED,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = false),
        additionalTools: JSONArray = JSONArray(),
        // Display-only projection; the returned legacy budget is intentionally unchanged.
        onProtocolPreview: ((Int) -> Unit)? = null,
        interactiveModeEnabled: Boolean = InteractiveModePreference.read(),
    ): Int {
        // 估算必须与真实请求同源：域索引、目录与「已关闭能力」说明都按同一套开关计算，
        // 否则会高估或低估固定开销。
        val disabledCapabilities =
            AgentModelClient.ToolCapabilitySwitches.capture().toDisabledState()
        // 目录先算：提示只点名目录里真实存在的工具，两边必须同源。
        val tools = AgentToolCatalog.build(
            basicTools = Prefs.isEnabled(Prefs.Keys.AGENT_BASIC_TOOLS),
            terminalTools = config.terminalTools,
            browserTools = config.browserTools,
            screenTools = config.screenTools,
            deviceTools = config.deviceTools,
            personalTools = config.personalTools,
            extensionsTools = Prefs.isEnabled(Prefs.Keys.AGENT_EXTENSIONS_TOOLS),
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = memoryContext.enabled,
            mediaSearchTools = Prefs.isEnabled(Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS),
            capabilities = capabilities,
            localWebSearch = !config.usesHostedWebSearch,
            disabledTools = Prefs.disabledTools(),
            fileSystemTier = AgentFileSystemTier.of(
                terminalTools = config.terminalTools,
                shellTools = Prefs.isEnabled(Prefs.Keys.AGENT_SHELL_TOOLS),
            ),
        )
        for (index in 0 until additionalTools.length()) {
            additionalTools.opt(index)?.let(tools::put)
        }
        val systemMessages = AgentPromptBuilder.buildSystemMessages(
            config = config,
            skillContext = skillContext,
            memoryContext = memoryContext,
            rootAvailable = capabilities.rootAvailable,
            delegationAvailable = AgentPromptBuilder.delegationToolsAvailable(additionalTools),
            shellTools = capabilities.shellTools,
            interactiveModeEnabled = interactiveModeEnabled,
            disabledCapabilities = disabledCapabilities,
            callableTools = AgentToolCatalog.toolNames(tools),
        )
        var tokens = 0
        for (index in 0 until systemMessages.length()) {
            val content = systemMessages.optJSONObject(index)?.optString("content").orEmpty()
            if (content.isBlank()) continue
            tokens += AgentContextBudget.countMessage(
                AgentModelClient.ConversationMessage(role = "system", content = content),
            )
        }
        if (tools.length() > 0) {
            tokens += AgentContextBudget.countTokens(tools.toString())
        }
        onProtocolPreview?.let { publish ->
            // A preview failure must not change the legacy silent budget or trigger network IO.
            runCatching {
                AgentWireRequestEstimate.measure(
                    AgentWireRequestEstimate.previewBody(config, systemMessages, tools),
                    AgentWireRequestEstimate.endpoint(config)).tokens
            }.getOrNull()?.let(publish)
        }
        return tokens
    }
}
