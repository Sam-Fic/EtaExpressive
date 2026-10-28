package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolGroupCatalog
import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `tools_use_group` 的执行侧复查。
 *
 * 目录与系统提示只宣传「开关允许且尚未打开」的组，但模型可以重放历史里的旧调用——
 * 若执行侧不按同一套开关复查，关掉的组仍会被成功「打开」，返回 next_turn，
 * 而组内工具永远不会出现：白花一轮，还反复误导模型。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentToolGroupSwitchGateTest {
    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    private fun tools(
        device: Boolean = true,
        personal: Boolean = true,
        extensions: Boolean = true,
    ): AgentLocalTools = AgentLocalTools(
        context = RuntimeEnvironment.getApplication() as Context,
        logger = NoOpLogger,
        browserRunId = "group-gate-run",
        deviceToolsEnabled = { device },
        personalToolsEnabled = { personal },
        extensionsToolsEnabled = { extensions },
    )

    private fun open(tools: AgentLocalTools, groupId: String): JSONObject {
        val call = AgentModelClient.ToolCall(
            "call-$groupId",
            AgentToolGroupCatalog.USE_GROUP,
            JSONObject().put("group", groupId).toString(),
        )
        return JSONObject(tools.execute(call).content)
    }

    @Test
    fun allowedGroupOpensAndReportsNextTurn() {
        val tools = tools()
        try {
            val result = open(tools, AgentToolGroups.DEVICE_GROUP.id)
            assertTrue(result.getBoolean("ok"))
            assertEquals("next_turn", result.getString("available"))
            assertEquals(
                AgentToolGroups.DEVICE_GROUP.toolNames.sorted(),
                (0 until result.getJSONArray("tools").length())
                    .map { result.getJSONArray("tools").getString(it) },
            )
            assertTrue(AgentToolGroups.DEVICE_GROUP.id in tools.openedToolGroups())
        } finally {
            tools.close()
        }
    }

    @Test
    fun switchedOffDeviceGroupCannotBeOpened() {
        val tools = tools(device = false)
        try {
            val result = open(tools, AgentToolGroups.DEVICE_GROUP.id)
            assertEquals("TOOL_GROUP_DISABLED", result.getString("code"))
            assertTrue(
                "关掉的组不能被记为已打开",
                AgentToolGroups.DEVICE_GROUP.id !in tools.openedToolGroups(),
            )
        } finally {
            tools.close()
        }
    }

    @Test
    fun extensionsGroupFollowsItsOwnSwitchNotOtherGroups() {
        // 扩展组关闭不该连带拒绝设备组——这两个开关互不相干。
        val tools = tools(extensions = false, device = true)
        try {
            assertEquals(
                "TOOL_GROUP_DISABLED",
                open(tools, AgentToolGroups.EXTENSIONS_GROUP.id).getString("code"),
            )
            assertTrue(open(tools, AgentToolGroups.DEVICE_GROUP.id).getBoolean("ok"))
        } finally {
            tools.close()
        }
    }

    @Test
    fun personalGroupIsRefusedWhenPersonalReadsAreOff() {
        val tools = tools(personal = false)
        try {
            assertEquals(
                "TOOL_GROUP_DISABLED",
                open(tools, AgentToolGroups.PERSONAL_GROUP.id).getString("code"),
            )
        } finally {
            tools.close()
        }
    }

    @Test
    fun residentBasicGroupIsNotOpenable() {
        // 基础交互组常驻，不是延迟组：对它调用 tools_use_group 是「没有这个组」。
        val tools = tools()
        try {
            assertEquals(
                "UNKNOWN_TOOL_GROUP",
                open(tools, AgentToolGroups.BASIC_GROUP.id).getString("code"),
            )
            assertTrue(AgentToolGroups.BASIC_GROUP.id !in tools.openedToolGroups())
        } finally {
            tools.close()
        }
    }

    @Test
    fun unknownGroupListsOnlyGroupsThatAreActuallyOpenable() {
        val tools = tools(device = false)
        try {
            val result = open(tools, "no_such_group")
            assertEquals("UNKNOWN_TOOL_GROUP", result.getString("code"))
            val available = result.getJSONArray("available_groups")
            val ids = (0 until available.length()).map { available.getString(it) }
            assertTrue("未知组应列出可打开的组", ids.isNotEmpty())
            assertTrue("已关闭的组不该出现在可选列表里", AgentToolGroups.DEVICE_GROUP.id !in ids)
        } finally {
            tools.close()
        }
    }

    @Test
    fun openingTwiceIsIdempotent() {
        val tools = tools()
        try {
            assertTrue(open(tools, AgentToolGroups.PERSONAL_GROUP.id).getBoolean("ok"))
            // 已打开后再打开同一个组仍应成功（模型重复调用不该报错）。
            assertTrue(open(tools, AgentToolGroups.PERSONAL_GROUP.id).getBoolean("ok"))
            assertEquals(1, tools.openedToolGroups().size)
        } finally {
            tools.close()
        }
    }
}
