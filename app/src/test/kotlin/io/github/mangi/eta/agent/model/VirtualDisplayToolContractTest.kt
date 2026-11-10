package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.RootRequirement
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayToolContractTest {
    @Test
    fun inspectVirtualBackendIsDiagnosticOnlyAndNotAdvertised() {
        // 只读探针固定返回 ready=false / session_authenticated=false，模型曾把它误当成
        // 当前虚拟会话状态。不再向模型宣传，且注册表已整体摘除（everyRegisteredTool
        // 契约要求注册表=目录并集）；本地诊断入口走 AgentLocalTools 的直接分发与工具页。
        val rooted = catalog(rootAvailable = true)
        assertFalse("inspect_virtual_backend" in rooted.names())
        assertFalse("inspect_virtual_backend" in AgentToolGroups.SCREEN_GROUP.toolNames)
        assertFalse("inspect_virtual_backend" in AgentToolRequirements.toolNames)
        val rootless = AgentToolRequirements.project(rooted, rootAvailable = false)
        assertFalse("inspect_virtual_backend" in rootless.names())
    }

    @Test
    fun keepVirtualResultRemainsCallableButDoesNotPromiseRetentionOrCleanup() {
        assertEquals(RootRequirement.NONE, AgentToolRequirements.rootRequirement("keep_virtual_result"))
        val description = catalog(rootAvailable = false).function("keep_virtual_result").getString("description")
        assertTrue(description.contains("finish_virtual_session"))
        assertTrue(description.contains("only marks"))
        assertFalse(description.contains("shut down"))
        assertFalse(description.contains("close the virtual display"))
        assertFalse(description.contains("中间应用不要"))
        assertFalse(description.contains("all be closed"))
        assertTrue("keep_virtual_result" in catalog(rootAvailable = false).names())
    }

    @Test
    fun unmarkedFinishCleansUpWithoutDeliveryAndLaunchReusesVirtualTask() {
        // finish_virtual_session is root-only, so read it from the rooted catalog.
        val finish = catalog(rootAvailable = true).function("finish_virtual_session").getString("description")
        assertTrue(finish.contains("handedOff=false"))
        assertTrue(finish.contains("released=true"))
        assertTrue(finish.contains("without delivering"))
        val launch = catalog(rootAvailable = false).function("launch_app").getString("description")
        assertTrue(launch.contains("reused=true"))
    }

    private fun catalog(rootAvailable: Boolean): JSONArray = AgentToolCatalog.build(
        terminalTools = false,
        browserTools = false,
        capabilities = AgentToolCapabilities(rootAvailable = rootAvailable, virtualDisplay = true),
        // 虚拟显示会话工具属屏幕组，默认延迟；这里断言其 schema，因此视为已打开。
        openedToolGroups = AgentToolGroups.allIds,
    )

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    private fun JSONArray.function(name: String): JSONObject =
        (0 until length())
            .map { getJSONObject(it).getJSONObject("function") }
            .single { it.getString("name") == name }
}
