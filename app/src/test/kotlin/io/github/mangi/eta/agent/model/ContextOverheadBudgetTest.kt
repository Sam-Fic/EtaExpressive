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
 * 系统提示 + 本轮下发的工具 schema。它不包含用户消息、历史、记忆正文或 MCP schema，
 * 因此只适合做上下界，不是账单预测。
 *
 * 预算的意义有两条：
 * 1. **常驻前缀必须小**——除基础交互外所有组默认延迟，开关全开也不该在第一轮付整份 schema；
 * 2. **打开一个组才付那个组的钱**——成本跟着模型的实际需要走，而不是跟着用户的开关走。
 */
class ContextOverheadBudgetTest {
    private data class Overhead(val system: Int, val tools: Int, val count: Int) {
        val total: Int get() = system + tools
    }

    private fun measure(
        config: AgentModelClient.ModelConfig,
        extensionsTools: Boolean = false,
        personalTools: Boolean = false,
        withDisabledNotice: Boolean = false,
        openedToolGroups: Set<String> = emptySet(),
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
            openedToolGroups = openedToolGroups,
            disabledCapabilities = if (withDisabledNotice) {
                AgentToolGroups.ToolSwitchState(
                    basicTools = true,
                    screenTools = config.screenTools,
                    browserTools = false,
                    terminalTools = false,
                    shellTools = false,
                    deviceTools = false,
                    personalTools = false,
                    extensionsTools = false,
                    mediaSearchTools = false,
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
            deviceTools = config.deviceTools,
            personalTools = personalTools,
            extensionsTools = extensionsTools,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = false,
            mediaSearchTools = false,
            capabilities = capabilities,
            localWebSearch = true,
            openedToolGroups = openedToolGroups,
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
        screenTools = false,
        deviceTools = false,
        personalTools = false,
    )

    private val allOn = allOff.copy(
        terminalTools = true,
        browserTools = true,
        screenTools = true,
        deviceTools = true,
    )

    @Test
    fun screenOffKeepsTheDisabledPrefixUnderBudget() {
        // 实测 6179（system 4561 + tools 1618 / 9 个工具）。
        val screenOff = measure(allOff, withDisabledNotice = true)
        assertTrue("screen-off prefix grew to ${screenOff}", screenOff.total <= 6_500)
    }

    @Test
    fun enabledCapabilitiesCostNothingUntilTheirGroupIsOpened() {
        // 开关全开但一个组都没打开：第一轮只付基础交互 + 一行索引。
        // 这正是「除基础交互外全延迟」要买到的东西——成本跟着需要走，不跟着开关走。
        // 实测 6284（system 4437 + tools 1847 / 11 个工具）；同一份配置全部打开是 40525。
        val resident = measure(allOn, extensionsTools = true, personalTools = true)
        assertTrue(
            "resident prefix grew to ${resident}",
            resident.total <= 6_600,
        )
        // 常驻开销里系统提示占七成：它才是下一个要压的对象，单独钉一条上界。
        assertTrue("resident system prompt grew to ${resident.system}", resident.system <= 4_800)
        val screenOpened = measure(
            allOn,
            extensionsTools = true,
            personalTools = true,
            openedToolGroups = setOf(AgentToolGroups.SCREEN_GROUP.id),
        )
        assertTrue(
            "打开屏幕组应真的花掉 GUI 的钱：resident=${resident.total} opened=${screenOpened.total}",
            screenOpened.total - resident.total > 3_000,
        )
    }

    @Test
    fun screenSwitchStillRemovesTheGuiCostOnceTheGroupIsOpen() {
        val opened = setOf(AgentToolGroups.SCREEN_GROUP.id)
        val screenOn = measure(allOn.copy(screenTools = true), openedToolGroups = opened)
        val screenOff = measure(allOn.copy(screenTools = false), openedToolGroups = opened)
        // 关掉屏幕能力必须真的省下 schema 与提示词，而不是只改一个开关状态（实测差 5110）。
        assertTrue("screen-on=${screenOn.total} screen-off=${screenOff.total}", screenOff.total < screenOn.total)
        assertTrue("GUI should cost more than 3k tokens", screenOn.total - screenOff.total > 3_000)
    }

    @Test
    fun allCapabilitiesStayWithinTheDocumentedCeiling() {
        // 上界：所有开关打开、所有组都被模型打开、并带上关闭说明——最坏的一轮。
        // 实测 40525（system 9580 + tools 30945 / 152 个工具）。
        val allOpened = measure(
            allOn,
            extensionsTools = true,
            personalTools = true,
            withDisabledNotice = true,
            openedToolGroups = AgentToolGroups.allIds,
        )
        assertTrue("all-opened prefix grew to ${allOpened}", allOpened.total <= 42_000)
    }
}
