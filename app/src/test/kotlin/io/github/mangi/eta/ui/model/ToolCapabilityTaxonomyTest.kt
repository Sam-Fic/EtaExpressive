package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面能力（[ToolCapabilities]）与模型分组（[AgentToolGroups]）的关系契约。
 *
 * 这一层存在的唯一理由：模型侧按 token 经济拆得比用户心智细，界面侧按用户心智合并。
 * 两边的对应关系一旦漂移，症状就是用户曾经看到的「关掉日历卡，设备控制也跟着关」
 * ——所以下面四条不变量必须一直是绿的。
 */
class ToolCapabilityTaxonomyTest {
    @Test
    fun capabilityGroupsCoverTheTaxonomyExactlyOnce() {
        val covered = ToolCapabilities.all.flatMap { it.groupIds }
        assertEquals(
            "一个模型组不能被两个界面能力同时认领",
            covered.size,
            covered.toSet().size,
        )
        assertEquals(
            "每个模型组都必须有一个可关闭它的界面能力，否则工具页会出现关不掉的工具",
            AgentToolGroups.groups.map { it.id }.toSet(),
            covered.toSet(),
        )
    }

    @Test
    fun everyCapabilityHasItsOwnSwitchKey() {
        val keys = ToolCapabilities.all.map { it.switchKey }
        assertEquals("界面能力数量", 7, keys.size)
        assertEquals("开关不能两个能力共用，否则一张卡会影响另一张", keys.size, keys.toSet().size)
        keys.forEach { key ->
            assertTrue("$key 必须是本地 Agent 开关", key in Prefs.Keys.LOCAL_AGENT_KEYS)
            assertTrue("$key 必须有默认值", key in Prefs.Keys.BOOLEAN_DEFAULTS)
        }
    }

    @Test
    fun subSwitchesAreDistinctFromGroupSwitches() {
        val groupKeys = ToolCapabilities.all.map { it.switchKey }.toSet()
        ToolCapabilities.all.mapNotNull { it.subSwitch }.forEach { sub ->
            assertFalse("子开关 ${sub.switchKey} 撞了某个能力开关", sub.switchKey in groupKeys)
            assertTrue(sub.switchKey in Prefs.Keys.LOCAL_AGENT_KEYS)
            assertTrue("子开关必须门禁至少一个工具", sub.gatedTools.isNotEmpty())
        }
    }

    @Test
    fun groupToSwitchMappingHasASingleSource() {
        // ToolGroupSwitch 只是 capability 的投影，不允许自己再维护一份 when。
        AgentToolGroups.groups.forEach { group ->
            val owner = requireNotNull(ToolCapabilities.forGroup(group.id)) { "${group.id} 没有界面归属" }
            assertEquals(owner.switchKey, ToolGroupSwitch.keyFor(group.id))
        }
    }

    /**
     * 模型侧的门禁必须与界面归属一致：只打开某个能力的开关时，它名下的模型组全部可用，
     * 其余组全部不可用。这条能同时抓住两类漂移——界面把某组并进了这张卡，
     * 但 `groupEnabled` 还在看另一张卡的开关；或者反过来。
     */
    @Test
    fun modelGateMatchesCapabilityOwnership() {
        ToolCapabilities.all.forEach { capability ->
            val switches = switchesWithOnly(capability.switchKey)
            capability.groupIds.forEach { groupId ->
                assertTrue(
                    "${capability.id} 的开关应能让 $groupId 可用",
                    AgentToolGroups.groupEnabled(switches, groupId),
                )
            }
            (AgentToolGroups.allIds - capability.groupIds.toSet()).forEach { groupId ->
                assertFalse(
                    "$groupId 不该被 ${capability.id} 的开关打开",
                    AgentToolGroups.groupEnabled(switches, groupId),
                )
            }
        }
    }

    /** 只把 [key] 对应的组开关打开，其余全关；子开关与委派保持开启，避免干扰判定。 */
    private fun switchesWithOnly(key: String) = AgentToolGroups.ToolSwitchState(
        basicTools = key == Prefs.Keys.AGENT_BASIC_TOOLS,
        screenTools = key == Prefs.Keys.AGENT_SCREEN_TOOLS,
        browserTools = key == Prefs.Keys.AGENT_BROWSER_TOOLS,
        terminalTools = key == Prefs.Keys.AGENT_TERMINAL_TOOLS,
        shellTools = true,
        deviceTools = key == Prefs.Keys.AGENT_DEVICE_TOOLS,
        personalTools = key == Prefs.Keys.AGENT_PERSONAL_TOOLS,
        extensionsTools = key == Prefs.Keys.AGENT_EXTENSIONS_TOOLS,
        mediaSearchTools = true,
        delegationTools = true,
    )
}
