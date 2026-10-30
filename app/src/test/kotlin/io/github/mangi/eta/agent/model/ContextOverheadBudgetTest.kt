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
        // 当前实测约 5111（system 3491 + tools 1618 / 9 个工具），上界留 240 余量。
        // 系统提示里只剩通用策略、关闭说明与「没有屏幕能力」的禁止条款：委派结果规则、
        // Root 只读检查、副屏交接、习惯分析都已跟随各自组，此配置下全部不在场。
        // 禁止条款约 120 token，换来的是不再出现「关了 GUI 仍尝试点击」的失败轮次。
        // 关闭说明多花的 171 token 说明组内工具不在目录、tools_use_group 打不开、调用会被拒：
        // 只说「已关闭」时模型仍会重试或变相执行，这部分是必要成本。
        val screenOff = measure(allOff, withDisabledNotice = true)
        assertTrue("screen-off prefix grew to ${screenOff}", screenOff.total <= 5_350)
    }

    @Test
    fun enabledCapabilitiesCostNothingUntilTheirGroupIsOpened() {
        // 开关全开但一个组都没打开：第一轮只付基础交互 + 一行索引。
        // 这正是「除基础交互外全延迟」要买到的东西——成本跟着需要走，不跟着开关走。
        // 当前实测 4882（system 3028 + tools 1854 / 11 个工具）；同一份配置全部打开是 38998。
        val resident = measure(allOn, extensionsTools = true, personalTools = true)
        assertTrue(
            "resident prefix grew to ${resident}",
            resident.total <= 4_950,
        )
        // 常驻开销里系统提示占大头，单独钉一条上界：它是唯一每轮必付、且与任务无关的部分。
        assertTrue("resident system prompt grew to ${resident.system}", resident.system <= 3_100)
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
        // 打开屏幕组还会把副屏交接规则带进前缀：它每条指令都点名屏幕组工具，
        // 组没打开时留着只是白付 token（见 AgentPromptBuilder 里对 screenActive 的门禁）。
        assertTrue(
            "屏幕组打开后应带上交接规则：resident=${resident.system} opened=${screenOpened.system}",
            screenOpened.system > resident.system,
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
        // 上一次实测 39169（system 9008 + tools 30161 / 150 个工具）：关闭说明改为按组
        // 声明「不可用」后多了约 171 token（见 screenOffKeepsTheDisabledPrefixUnderBudget）。
        // 这一轮所有组都在场，所以按组门禁的三条指引照旧付费，省下的只有委派结果规则
        // （本 run 尚未派发，−750）、重复的一句细节处理、以及 personalTools=false 时的习惯分析句。
        // 换句话说：最坏一轮的天花板几乎没动，压的是**第一轮**和**用不到的组**。
        val allOpened = measure(
            allOn,
            extensionsTools = true,
            personalTools = true,
            withDisabledNotice = true,
            openedToolGroups = AgentToolGroups.allIds,
        )
        assertTrue("all-opened prefix grew to ${allOpened}", allOpened.total <= 39_500)
    }

    @Test
    fun delegationResultRulesAreNotPaidBeforeTheFirstDispatch() {
        // 委派规则拆成两半：调度规则常驻，结果解读规则等本 run 真的派出过子任务才进前缀。
        // 差值就是那半条规则的钱——它是常驻系统提示里最大的一块，之前每一轮都白付。
        val before = measure(allOn, extensionsTools = true, personalTools = true)
        val after = AgentPromptBuilder.buildSystemMessages(
            config = allOn,
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = true,
            delegationAvailable = true,
            delegationEngaged = true,
        ).let { messages ->
            (0 until messages.length()).sumOf { index ->
                AgentContextBudget.countTokens(messages.getJSONObject(index).optString("content"))
            }
        }
        assertTrue(
            "派发后才该多付结果规则：before=${before.system} after=$after",
            after - before.system in 500..1_200,
        )
    }
}
