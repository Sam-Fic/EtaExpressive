package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件系统三档：总闸（terminal）+ 命令执行（shell）两个开关表达
 * disabled / read-write / read-write+shell。只读档由「总闸关闭」表达，
 * 但 [AgentFileSystemTier] 也保留了 READ_ONLY 枚举供未来的显式只读授权使用。
 */
class AgentFileSystemTierTest {
    private fun catalog(tier: AgentFileSystemTier.Tier): JSONArray = AgentToolCatalog.build(
        terminalTools = AgentFileSystemTier.allowsFileTools(tier),
        browserTools = false,
        fileSystemTier = tier,
    )

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    @Test fun tiersAreDerivedFromTwoSwitches() {
        assertEquals(AgentFileSystemTier.Tier.DISABLED,
            AgentFileSystemTier.of(terminalTools = false, shellTools = false))
        assertEquals(AgentFileSystemTier.Tier.DISABLED,
            AgentFileSystemTier.of(terminalTools = false, shellTools = true))
        assertEquals(AgentFileSystemTier.Tier.READ_WRITE,
            AgentFileSystemTier.of(terminalTools = true, shellTools = false))
        assertEquals(AgentFileSystemTier.Tier.READ_WRITE_SHELL,
            AgentFileSystemTier.of(terminalTools = true, shellTools = true))
    }

    @Test fun shellOffKeepsFileToolsButDropsCommands() {
        val tier = AgentFileSystemTier.Tier.READ_WRITE
        val names = catalog(tier).names()

        // 命令执行类完全不下发。
        AgentFileSystemTier.shellTools.forEach { assertFalse("$it 不应下发", it in names) }
        // 读写文件仍然可用。
        AgentFileSystemTier.readTools.forEach { assertTrue("$it 应下发", it in names) }
        AgentFileSystemTier.writeTools.forEach { assertTrue("$it 应下发", it in names) }
    }

    @Test fun shellOnPublishesEverything() {
        val names = catalog(AgentFileSystemTier.Tier.READ_WRITE_SHELL).names()
        AgentFileSystemTier.allFileTools.forEach { assertTrue("$it 应下发", it in names) }
    }

    @Test fun disabledPublishesNothingFromTheFileFamily() {
        val names = catalog(AgentFileSystemTier.Tier.DISABLED).names()
        AgentFileSystemTier.allFileTools.forEach {
            assertFalse("$it 不应下发", it in names)
        }
    }

    @Test fun readOnlyPublishesOnlyReaders() {
        assertEquals(false, AgentFileSystemTier.allowsWrite(AgentFileSystemTier.Tier.READ_ONLY))
        assertEquals(false, AgentFileSystemTier.allowsShell(AgentFileSystemTier.Tier.READ_ONLY))
        assertEquals(true, AgentFileSystemTier.allowsFileTools(AgentFileSystemTier.Tier.READ_ONLY))
        AgentFileSystemTier.readTools.forEach {
            assertTrue(it, AgentFileSystemTier.publishes(AgentFileSystemTier.Tier.READ_ONLY, it))
        }
        (AgentFileSystemTier.writeTools + AgentFileSystemTier.shellTools).forEach {
            assertFalse(it, AgentFileSystemTier.publishes(AgentFileSystemTier.Tier.READ_ONLY, it))
        }
    }

    @Test fun unrelatedToolsAreUnaffectedByTheTier() {
        for (tier in AgentFileSystemTier.Tier.entries) {
            for (name in listOf("web_search", "observe_screen", "memory_get", "ask_user")) {
                assertTrue("$name @ $tier", AgentFileSystemTier.publishes(tier, name))
            }
        }
    }

    @Test fun shellOffAlsoDropsTheLinuxTerminalTool() {
        // terminal 会执行命令，必须和 run_command 一起被关掉。
        val names = catalog(AgentFileSystemTier.Tier.READ_WRITE).names()
        assertFalse("terminal" in names)
        assertFalse("run_command" in names)
        assertTrue("read_file" in names)
    }
}
