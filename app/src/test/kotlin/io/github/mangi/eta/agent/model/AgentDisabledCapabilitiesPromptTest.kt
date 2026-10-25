package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 关闭某个工具开关后，工具从目录里消失，但**能力本身还在**。
 * 若提示词对此沉默，模型只能回答「我没有这个能力」——那是错的；也可能转而尝试绕过。
 *
 * 这里钉住三件事：
 * 1. 关闭说明只在真正关闭时出现，且列出对应能力；
 * 2. 全开时零成本（不产生额外系统消息）；
 * 3. 说明与「域索引」出自同一份开关，不会自相矛盾。
 */
class AgentDisabledCapabilitiesPromptTest {
    private fun switches(
        terminal: Boolean = true,
        browser: Boolean = true,
        direct: Boolean = true,
        sensitiveRead: Boolean = true,
        personal: Boolean = true,
        deviceState: Boolean = true,
        sensitiveAction: Boolean = true,
        selfSettings: Boolean = true,
        delegation: Boolean = true,
    ) = AgentToolGroups.ToolSwitchState(
        terminalTools = terminal,
        browserTools = browser,
        deviceDirectTools = direct,
        deviceSensitiveReadTools = sensitiveRead,
        personalDataReads = personal,
        deviceStateReads = deviceState,
        deviceSensitiveActionTools = sensitiveAction,
        selfSettingsTools = selfSettings,
        delegationTools = delegation,
    )

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "k", model = "m", systemPrompt = "",
        terminalTools = true, browserTools = true,
        deviceDirectTools = true, deviceSensitiveReadTools = true, deviceSensitiveActionTools = true,
    )

    /**
     * 用户「在设置里关掉」某个开关时，生效值（config）与用户设置值（state）通常一起变。
     * [configFor] 用来在需要时把两者对齐；只改其中一边的用例刻意不同步，
     * 用来区分「入口收窄」与「用户关闭」。
     */
    private fun prompt(
        state: AgentToolGroups.ToolSwitchState?,
        configFor: AgentModelClient.ModelConfig = config(),
    ): List<String> {
        val messages = AgentPromptBuilder.buildSystemMessages(
            config = configFor,
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = false,
            delegationAvailable = true,
            disabledCapabilities = state,
        )
        return (0 until messages.length()).map { messages.getJSONObject(it).getString("content") }
    }

    private fun disabledNotice(state: AgentToolGroups.ToolSwitchState?): String? =
        prompt(state).firstOrNull { it.contains("已在设置里关闭以下能力") }

    @Test fun everythingEnabledProducesNoDisabledNotice() {
        assertNull("全开时不该有关闭说明，否则白付 token", disabledNotice(switches()))
    }

    @Test fun closingTerminalExplainsTheCapabilityInsteadOfClaimingItIsAbsent() {
        val notice = requireNotNull(disabledNotice(switches(terminal = false)))
        assertTrue("必须点名终端", notice.contains("终端"))
        // 关键语义：这是配置，不是设备限制；也不许声称自己不具备该能力。
        assertTrue(notice.contains("正常配置"))
        assertTrue(notice.contains("不要声称自己不具备"))
        // 必须堵住绕过：不许改用其它工具或建议装第三方应用。
        assertTrue(notice.contains("不要改用其它工具"))
        assertTrue(notice.contains("建议安装第三方应用"))
    }

    @Test fun twoTierReadsAreReportedPerTierNotAsOneLump() {
        // 只关个人信息那一档：设备状态读仍然可用，说明必须精确到档。
        val personalOnly = requireNotNull(disabledNotice(switches(personal = false)))
        assertTrue(personalOnly.contains("个人信息读取"))
        assertFalse("设备状态没关，不该列进去", personalOnly.contains("设备状态读取"))

        // 总开关关掉时两档都缺席，才合并成一项。
        val both = requireNotNull(disabledNotice(switches(sensitiveRead = false)))
        assertTrue(both.contains("敏感设备信息读取"))
        assertFalse(both.contains("个人信息读取"))
    }

    @Test fun untrustedSnapshotIsNeverGuessedFromConfigDefaults() {
        // 不传快照 = 子代理/压缩模型路径：ModelConfig 默认 terminalTools=false，
        // 但那是「最小配置」而不是「用户关了」，所以不能说「用户已在设置里关闭」。
        assertNull(disabledNotice(null))
    }

    @Test fun noticeAndGroupIndexAgreeAboutClosedDomains() {
        // 关掉设备写：device_write 域既不该出现在索引里，能力说明里要列出它。
        val state = switches(sensitiveAction = false)
        // 用户真的关了：生效值也同步关掉，否则这是「入口收窄」而不是「用户关闭」。
        val texts = prompt(state, configFor = config().copy(deviceSensitiveActionTools = false))
        val index = texts.firstOrNull { it.contains("Deferred tool groups") }
        assertTrue("关掉的域不该被宣传",
            index == null || !index.contains(AgentToolGroups.DEVICE_ACTION_GROUP.id))
        assertTrue(requireNotNull(disabledNotice(state)).contains("设备写操作"))

        // 全开：device_write 应可打开（出现在索引里），且没有关闭说明。
        val allOn = prompt(switches())
        val openIndex = requireNotNull(allOn.firstOrNull { it.contains("Deferred tool groups") })
        assertTrue(openIndex.contains(AgentToolGroups.DEVICE_ACTION_GROUP.id))
        assertNull(disabledNotice(switches()))
    }

    @Test fun memoryAndSkillsAreNotListedTwice() {
        // 记忆与 Skills 已有各自的负面门禁，关闭说明不重复列它们，
        // 避免模型在同一份提示里读到两遍同一件事。
        val texts = prompt(switches(sensitiveRead = false))
        val memoryNotices = texts.count { it.contains("持久记忆已关闭") }
        val skillNotices = texts.count { it.contains("当前助手未开启 Skills") }
        assertEquals(1, memoryNotices)
        assertEquals(1, skillNotices)
        val notice = requireNotNull(disabledNotice(switches(sensitiveRead = false)))
        assertFalse(notice.contains("持久记忆"))
        assertFalse(notice.contains("Skills"))
    }

    @Test fun groupIndexUsesEffectiveConfigWhileNoticeUsesUserSwitches() {
        // 关键区分：域的「能否打开」看生效值（config.*），「用户关了什么」看 Prefs。
        // 入口把设备写能力收窄掉、但用户自己没关时：索引不该宣传该域（打开也拿不到工具），
        // 而关闭说明也不该出现——用户没有关任何东西。
        val narrowed = AgentPromptBuilder.buildSystemMessages(
            config = config().copy(deviceSensitiveActionTools = false),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = false,
            delegationAvailable = false,
            // 用户设置全开：说明不该出现。
            disabledCapabilities = switches(sensitiveAction = true),
        )
        val texts = (0 until narrowed.length())
            .map { narrowed.getJSONObject(it).getString("content") }
        val index = texts.firstOrNull { it.contains("Deferred tool groups") }
        assertTrue("生效值不可打开时，索引不该宣传该域",
            index == null || !index.contains(AgentToolGroups.DEVICE_ACTION_GROUP.id))
        assertTrue("用户没关就不该有关闭说明", texts.none { it.contains("已在设置里关闭以下能力") })
    }

    @Test fun labelsAreStableForEverySingleSwitchOff() {
        // 每一项单独关闭都要能说清楚，不能有开关关了却列不出名字的情况。
        val cases = listOf(
            switches(terminal = false) to "终端",
            switches(browser = false) to "网页",
            switches(direct = false) to "设备直达",
            switches(personal = false) to "个人信息",
            switches(deviceState = false) to "设备状态",
            switches(sensitiveAction = false) to "设备写操作",
            switches(selfSettings = false) to "自身配置",
            switches(delegation = false) to "子代理",
        )
        cases.forEach { (state, expected) ->
            val notice = requireNotNull(disabledNotice(state)) { "关闭 $expected 时必须有说明" }
            assertTrue("$expected 未出现在说明里：$notice", notice.contains(expected))
        }
    }
}
