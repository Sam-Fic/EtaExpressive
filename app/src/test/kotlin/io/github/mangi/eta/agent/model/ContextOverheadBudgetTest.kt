package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 固定请求开销的回归预算。
 *
 * 这些数字是「空历史、无记忆、无 Skills、无 MCP」时的核心前缀：
 * 系统提示 + 常驻工具 schema。它不包含用户消息、历史、记忆正文或 MCP schema，
 * 因此只适合做上下界，不是账单预测。
 *
 * 预算的意义：工具开关全关时，一句「你好」不应再为 GUI schema 和 GUI 指引付费；
 * 只有显式打开屏幕能力时才恢复这部分成本。
 */
class ContextOverheadBudgetTest {
    private data class Overhead(val system: Int, val tools: Int, val count: Int) {
        val total: Int get() = system + tools
    }

    private fun measure(
        config: AgentModelClient.ModelConfig,
        selfSettingsTools: Boolean = false,
        personalDataReads: Boolean = false,
        deviceStateReads: Boolean = false,
        withDisabledNotice: Boolean = false,
    ): Overhead {
        val capabilities = AgentToolCapabilities(rootAvailable = true, virtualDisplay = false)
        val system = AgentPromptBuilder.buildSystemMessages(
            config = config,
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = true,
            delegationAvailable = true,
            shellTools = null,
            interactiveModeEnabled = false,
            selfSettingsTools = selfSettingsTools,
            personalDataReads = personalDataReads,
            deviceStateReads = deviceStateReads,
            disabledCapabilities = if (withDisabledNotice) {
                AgentToolGroups.ToolSwitchState(
                    terminalTools = false,
                    browserTools = false,
                    screenTools = config.screenTools,
                    deviceDirectTools = false,
                    deviceSensitiveReadTools = false,
                    personalDataReads = false,
                    deviceStateReads = false,
                    deviceSensitiveActionTools = false,
                    selfSettingsTools = false,
                    delegationTools = false,
                )
            } else {
                null
            },
        )
        val systemTokens = (0 until system.length()).sumOf { index ->
            AgentContextBudget.countTokens(system.getJSONObject(index).optString("content"))
        }
        val tools = AgentToolCatalog.build(
            terminalTools = config.terminalTools,
            browserTools = config.browserTools,
            screenTools = config.screenTools,
            deviceDirectTools = config.deviceDirectTools,
            deviceSensitiveReadTools = config.deviceSensitiveReadTools,
            deviceSensitiveActionTools = config.deviceSensitiveActionTools,
            personalDataReads = personalDataReads,
            deviceStateReads = deviceStateReads,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = false,
            selfSettingsTools = selfSettingsTools,
            mediaSearchTools = false,
            capabilities = capabilities,
            localWebSearch = true,
            fileSystemTier = AgentFileSystemTier.of(config.terminalTools, shellTools = true),
        )
        return Overhead(systemTokens, AgentContextBudget.countTokens(tools.toString()), tools.length())
    }

    private val allOff = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = "k",
        model = "m",
        systemPrompt = "",
        terminalTools = false,
        browserTools = false,
        deviceDirectTools = false,
        deviceSensitiveReadTools = false,
        deviceSensitiveActionTools = false,
    )

    @Test
    fun screenOffKeepsTheDisabledPrefixUnderBudget() {
        val screenOff = measure(allOff.copy(screenTools = false), withDisabledNotice = true)
        assertTrue("screen-off prefix grew to ${screenOff.total}", screenOff.total <= 7_300)
    }

    @Test
    fun screenSwitchIsTheDominantCostOfAnOtherwiseDisabledPrefix() {
        val screenOn = measure(allOff.copy(screenTools = true), withDisabledNotice = true)
        val screenOff = measure(allOff.copy(screenTools = false), withDisabledNotice = true)
        // 关闭屏幕能力必须真的省下 schema 与提示词，而不是只改一个开关状态。
        assertTrue("screen-on=${screenOn.total} screen-off=${screenOff.total}", screenOff.total < screenOn.total)
        assertTrue("GUI should cost more than 3k tokens", screenOn.total - screenOff.total > 3_000)
        assertTrue("screen-on prefix grew to ${screenOn.total}", screenOn.total <= 12_500)
    }

    @Test
    fun allCapabilitiesStayWithinTheDocumentedCeiling() {
        val allOn = measure(
            allOff.copy(
                terminalTools = true,
                browserTools = true,
                screenTools = true,
                deviceDirectTools = true,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
            ),
            selfSettingsTools = true,
            personalDataReads = true,
            deviceStateReads = true,
        )
        assertTrue("all-on prefix grew to ${allOn.total}", allOn.total <= 28_500)
    }
}
