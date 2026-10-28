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
 * 这里钉住四件事：
 * 1. 关闭说明只在真正关闭时出现，且列出对应能力；
 * 2. 全开时零成本（不产生额外系统消息）；
 * 3. 说明与「组索引」出自同一份开关，不会自相矛盾；
 * 4. 组内子开关（命令执行、媒体搜索）按更窄的损失说明，不谎称整组没了。
 */
class AgentDisabledCapabilitiesPromptTest {
    private fun switches(
        basic: Boolean = true,
        screen: Boolean = true,
        browser: Boolean = true,
        terminal: Boolean = true,
        shell: Boolean = true,
        device: Boolean = true,
        personal: Boolean = true,
        extensions: Boolean = true,
        mediaSearch: Boolean = true,
        delegation: Boolean = true,
    ) = AgentToolGroups.ToolSwitchState(
        basicTools = basic,
        screenTools = screen,
        browserTools = browser,
        terminalTools = terminal,
        shellTools = shell,
        deviceTools = device,
        personalTools = personal,
        extensionsTools = extensions,
        mediaSearchTools = mediaSearch,
        delegationTools = delegation,
    )

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "k", model = "m", systemPrompt = "",
        terminalTools = true, browserTools = true,
        deviceTools = true, personalTools = true,
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

    /** 索引里一行的前缀；直接用组 id 做子串匹配会撞上别的组摘要里的 "device"。 */
    private fun indexEntry(groupId: String): String = "id=$groupId |"

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

    @Test fun subSwitchesAreReportedAsTheNarrowerLoss() {
        // 终端开着、只关命令执行：说明必须精确到子项，不能谎称整个终端没了。
        val shellOff = requireNotNull(disabledNotice(switches(shell = false)))
        assertTrue(shellOff.contains("命令执行"))
        assertFalse("终端本身没关，不该列进去", shellOff.contains("终端与文件读写"))

        // 个人数据开着、只关媒体搜索：同理。
        val mediaOff = requireNotNull(disabledNotice(switches(mediaSearch = false)))
        assertTrue(mediaOff.contains("媒体语义搜索"))
        assertFalse(mediaOff.contains("个人数据读取"))

        // 整组关掉时只列组，不再列组内子项。
        val terminalOff = requireNotNull(disabledNotice(switches(terminal = false, shell = false)))
        assertTrue(terminalOff.contains("终端与文件读写"))
        assertFalse(terminalOff.contains("命令执行"))
    }

    @Test fun untrustedSnapshotIsNeverGuessedFromConfigDefaults() {
        // 不传快照 = 子代理/压缩模型路径：ModelConfig 默认 terminalTools=false，
        // 但那是「最小配置」而不是「用户关了」，所以不能说「用户已在设置里关闭」。
        assertNull(disabledNotice(null))
    }

    @Test fun noticeAndGroupIndexAgreeAboutClosedGroups() {
        val state = switches(device = false)
        // 用户真的关了：生效值也同步关掉，否则这是「入口收窄」而不是「用户关闭」。
        val texts = prompt(state, configFor = config().copy(deviceTools = false))
        val index = texts.firstOrNull { it.contains("Deferred tool groups") }
        assertTrue(
            "关掉的组不该被宣传",
            index == null || !index.contains(indexEntry(AgentToolGroups.DEVICE_GROUP.id)),
        )
        assertTrue(requireNotNull(disabledNotice(state)).contains("设备控制"))

        // 全开：每个延迟组都应可打开（出现在索引里），且没有关闭说明。
        val allOn = prompt(switches())
        val openIndex = requireNotNull(allOn.firstOrNull { it.contains("Deferred tool groups") })
        AgentToolGroups.deferredGroups.forEach {
            assertTrue("${it.id} 应出现在索引里", openIndex.contains(indexEntry(it.id)))
        }
        assertNull(disabledNotice(switches()))
    }

    @Test fun memoryAndSkillsAreNotListedTwice() {
        // 记忆与 Skills 已有各自的负面门禁，关闭说明不重复列它们，
        // 避免模型在同一份提示里读到两遍同一件事。
        val texts = prompt(switches(personal = false))
        val memoryNotices = texts.count { it.contains("持久记忆已关闭") }
        val skillNotices = texts.count { it.contains("当前助手未开启 Skills") }
        assertEquals(1, memoryNotices)
        assertEquals(1, skillNotices)
        val notice = requireNotNull(disabledNotice(switches(personal = false)))
        assertFalse(notice.contains("持久记忆"))
        assertFalse(notice.contains("Skills"))
    }

    @Test fun groupIndexUsesEffectiveConfigWhileNoticeUsesUserSwitches() {
        // 关键区分：组的「能否打开」看生效值（config.*），「用户关了什么」看 Prefs。
        // 入口把设备控制收窄掉、但用户自己没关时：索引不该宣传该组（打开也拿不到工具），
        // 而关闭说明也不该出现——用户没有关任何东西。
        val narrowed = AgentPromptBuilder.buildSystemMessages(
            config = config().copy(deviceTools = false),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = false,
            delegationAvailable = false,
            // 用户设置全开：说明不该出现。
            disabledCapabilities = switches(device = true),
        )
        val texts = (0 until narrowed.length())
            .map { narrowed.getJSONObject(it).getString("content") }
        val index = texts.firstOrNull { it.contains("Deferred tool groups") }
        assertTrue(
            "生效值不可打开时，索引不该宣传该组",
            index == null || !index.contains(indexEntry(AgentToolGroups.DEVICE_GROUP.id)),
        )
        assertTrue("用户没关就不该有关闭说明", texts.none { it.contains("已在设置里关闭以下能力") })
    }

    @Test fun labelsAreStableForEverySingleSwitchOff() {
        // 每一项单独关闭都要能说清楚，不能有开关关了却列不出名字的情况。
        val cases = listOf(
            switches(basic = false) to "基础交互",
            switches(screen = false) to "屏幕观察",
            switches(browser = false) to "网页",
            switches(terminal = false) to "终端",
            switches(shell = false) to "命令执行",
            switches(device = false) to "设备控制",
            switches(personal = false) to "个人数据读取",
            switches(mediaSearch = false) to "媒体语义搜索",
            switches(extensions = false) to "Eta 自身配置",
            switches(delegation = false) to "子代理委派",
        )
        cases.forEach { (state, expected) ->
            val notice = requireNotNull(disabledNotice(state)) { "关闭 $expected 时必须有说明" }
            assertTrue("$expected 未出现在说明里：$notice", notice.contains(expected))
        }
    }
}
