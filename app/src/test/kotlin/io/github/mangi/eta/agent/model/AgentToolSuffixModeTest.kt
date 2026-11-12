package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存优先档（suffix）的目录合同：`tools` 只由**会话级原生组集合**决定，
 * run 中途打开多少个组都不改一个字节——`tools` 排在 system 与历史之前，
 * 改一次就等于整段前缀重算，这正是本档要消除的损失。
 */
class AgentToolSuffixModeTest {
    private fun catalog(
        nativeGroups: Set<String>?,
        opened: Set<String> = emptySet(),
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
        preset: Set<String>? = null,
    ): JSONArray = AgentToolCatalog.build(
        terminalTools = true,
        browserTools = true,
        screenTools = true,
        deviceTools = true,
        personalTools = true,
        extensionsTools = true,
        capabilities = capabilities,
        openedToolGroups = opened,
        nativeGroups = nativeGroups,
        presetToolNames = preset,
    )

    private fun JSONArray.names(): Set<String> = AgentToolCatalog.toolNames(this)

    private fun JSONArray.useGroupEnum(): Set<String> {
        val tool = (0 until length()).map { getJSONObject(it).getJSONObject("function") }
            .firstOrNull { it.getString("name") == AgentToolGroupCatalog.USE_GROUP } ?: return emptySet()
        val enum = tool.optJSONObject("parameters")?.optJSONObject("properties")
            ?.optJSONObject("group")?.optJSONArray("enum") ?: return emptySet()
        return (0 until enum.length()).mapTo(linkedSetOf()) { enum.getString(it) }
    }

    @Test fun onlySessionNativeGroupsPublishTheirSchemas() {
        // run 内已经打开了 screen/terminal，但它们不在会话级集合里：schema 一律不下发。
        val names = catalog(
            nativeGroups = setOf(AgentToolGroups.DEVICE_GROUP.id),
            opened = setOf(
                AgentToolGroups.SCREEN_GROUP.id,
                AgentToolGroups.TERMINAL_GROUP.id,
                AgentToolGroups.DEVICE_GROUP.id,
            ),
        ).names()
        assertTrue("原生组内的工具应下发", "set_volume" in names)
        assertFalse("非原生组的工具不该下发", "tap" in names)
        assertFalse("非原生组的工具不该下发", "run_command" in names)
        assertTrue("基础交互组不受延迟折叠影响", "ask_user" in names)
        assertTrue(AgentToolGroupCatalog.CALL in names)
        assertTrue(AgentToolGroupCatalog.USE_GROUP in names)
        assertTrue(AgentToolSearchCatalog.SEARCH in names)
    }

    @Test fun toolsBytesDoNotChangeWhenGroupsOpenMidRun() {
        val native = setOf(AgentToolGroups.DEVICE_GROUP.id)
        val before = catalog(native).toString()
        val after = catalog(native, opened = AgentToolGroups.allIds).toString()
        assertEquals("开组不得改写 tools 字节", before, after)
    }

    @Test fun useGroupEnumDoesNotShrinkAsGroupsOpen() {
        val native = setOf(AgentToolGroups.DEVICE_GROUP.id)
        val before = catalog(native).useGroupEnum()
        val after = catalog(native, opened = AgentToolGroups.allIds).useGroupEnum()
        assertEquals("枚举缩水同样是前缀改写", before, after)
        // 原生组已经带着 schema，不该再宣传「去打开它」。
        assertFalse(AgentToolGroups.DEVICE_GROUP.id in before)
        assertTrue(AgentToolGroups.TERMINAL_GROUP.id in before)
    }

    @Test fun emptyNativeGroupsStillLeavesTheForwardingEntry() {
        val names = catalog(nativeGroups = emptySet()).names()
        assertTrue(AgentToolGroupCatalog.CALL in names)
        AgentToolGroups.deferredGroups.forEach { group ->
            group.toolNames.forEach { assertFalse("$it 属未加载的 ${group.id} 组", it in names) }
        }
    }

    @Test fun forwardingEntrySurvivesCapabilityProjection() {
        // tool_call 不属于任何组，能力投影不认识它；放在投影之后追加才不会被 UNKNOWN_TOOL 丢掉。
        val names = catalog(
            nativeGroups = emptySet(),
            capabilities = AgentToolCapabilities(rootAvailable = false),
        ).names()
        assertTrue(AgentToolGroupCatalog.CALL in names)
    }

    @Test fun nullNativeGroupsKeepsTheLegacyProgressiveCatalog() {
        val names = catalog(nativeGroups = null, opened = setOf(AgentToolGroups.TERMINAL_GROUP.id)).names()
        assertTrue("回退档必须保持「打开即下发」", "run_command" in names)
        assertFalse("回退档不该出现转发入口", AgentToolGroupCatalog.CALL in names)
    }

    @Test fun presetRunIsNotASuffixRun() {
        // 语音快捷功能自己管工具集：既不下发转发入口，也不受原生组集合影响。
        val names = catalog(
            nativeGroups = setOf(AgentToolGroups.DEVICE_GROUP.id),
            preset = setOf("ask_user"),
        ).names()
        assertEquals(setOf("ask_user"), names)
    }

    @Test fun indexTellsTheModelToCallThroughTheForwardingEntry() {
        val availability = AgentToolGroups.availability(setOf(AgentToolGroups.DEVICE_GROUP.id)) { true }
        val suffix = requireNotNull(AgentToolGroups.indexMessage(availability, callsViaToolCall = true))
        val legacy = requireNotNull(AgentToolGroups.indexMessage(availability, callsViaToolCall = false))
        assertNotEquals(suffix, legacy)
        assertTrue(suffix.contains(AgentToolGroupCatalog.CALL))
        assertTrue("加载后本轮即可调用", suffix.contains("same round"))
        assertFalse("原生组已加载，不该再出现在索引里", suffix.contains("id=${AgentToolGroups.DEVICE_GROUP.id} |"))
        assertTrue(legacy.contains("next round"))
        assertFalse(legacy.contains(AgentToolGroupCatalog.CALL))
    }

    @Test fun everyDeferredGroupCarriesUsageTextForTheLoadReceipt() {
        // 各组指引已从 system 摘掉，改随组加载回执给出；漏一组就等于模型永远看不到那组的使用要点。
        AgentToolGroups.deferredGroups.forEach { group ->
            assertNotNull("${group.id} 缺少组使用要点", AgentToolGroups.groupUsage(group.id))
        }
    }

    @Test fun predictHitsStrongKeywordsOnly() {
        assertEquals(
            setOf(AgentToolGroups.SCREEN_GROUP.id),
            AgentToolGroups.predictFromText("帮我截图") { true },
        )
        assertEquals(
            setOf(AgentToolGroups.DEVICE_GROUP.id),
            AgentToolGroups.predictFromText("设个闹钟") { true },
        )
        assertEquals(emptySet<String>(), AgentToolGroups.predictFromText("你好") { true })
    }

    @Test fun predictIsCappedAndFollowsDeclarationOrder() {
        // 四组都命中，但只预开前三个：再多就抵掉了按需加载省下的 schema。
        val predicted = AgentToolGroups.predictFromText("先截图，再打开网页，把文件里的闹钟改一下") { true }
        assertEquals(AgentToolGroups.MAX_PREDICTED_GROUPS, predicted.size)
        assertEquals(
            listOf(
                AgentToolGroups.SCREEN_GROUP.id,
                AgentToolGroups.WEB_GROUP.id,
                AgentToolGroups.TERMINAL_GROUP.id,
            ),
            predicted.toList(),
        )
    }

    @Test fun predictRespectsTheGroupGate() {
        assertEquals(
            setOf(AgentToolGroups.DEVICE_GROUP.id),
            AgentToolGroups.predictFromText("截图并设个闹钟") { it != AgentToolGroups.SCREEN_GROUP.id },
        )
        assertEquals(
            emptySet<String>(),
            AgentToolGroups.predictFromText("截图") { false },
        )
    }

    @Test fun predictIsDeterministicAndBlankSafe() {
        val text = "看看网页上的联系人照片"
        assertEquals(
            AgentToolGroups.predictFromText(text) { true },
            AgentToolGroups.predictFromText(text) { true },
        )
        assertEquals(emptySet<String>(), AgentToolGroups.predictFromText("   ") { true })
    }
}