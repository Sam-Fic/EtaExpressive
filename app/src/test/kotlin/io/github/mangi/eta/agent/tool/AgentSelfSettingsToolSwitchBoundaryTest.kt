package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.config.Prefs
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 工具开关只能由用户放宽。模型可以关掉它（能力变窄），但不能把用户关掉的开关重新打开。
 *
 * 为什么这条边界必须钉住：AgentLocalTools 在执行每个工具前都会重读 Prefs，所以
 * 「模型先关终端、再自己开回来、然后立刻用终端」是一条真实可行的提权路径，
 * 而用户关掉开关这个动作本身就是安全决策，不该被模型反转。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentSelfSettingsToolSwitchBoundaryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        Prefs.localAgentPreferences()!!.edit().clear().commit()
    }

    @After
    fun tearDown() {
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
    }

    private fun set(vararg pairs: Pair<String, String>): JSONObject {
        val settings = org.json.JSONArray()
        pairs.forEach { (key, value) ->
            settings.put(JSONObject().put("key", key).put("value", value))
        }
        return JSONObject(
            AgentSelfSettingsTools().set(JSONObject().put("settings", settings)),
        )
    }

    private fun enabled(key: String): Boolean = Prefs.isEnabled(key)

    @Test
    fun reEnablingAUserDisabledToolSwitchIsRefused() {
        for (key in listOf(
            Prefs.Keys.AGENT_TERMINAL_TOOLS,
            Prefs.Keys.AGENT_BROWSER_TOOLS,
            Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
            Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
        )) {
            // 用户关掉了这个能力域。
            assertTrue(set(key to "off").getBoolean("ok"))
            assertFalse("$key 应已被关闭", enabled(key))

            // 模型尝试自己开回来：必须被拒绝，且开关保持关闭。
            val response = set(key to "on")
            assertFalse("$key 不应允许模型重新打开", response.getBoolean("ok"))
            assertFalse("$key 必须保持用户设定的关闭", enabled(key))
            val result = response.getJSONArray("results").getJSONObject(0)
            assertEquals("USER_CONSENT_REQUIRED", result.getString("code"))
            assertEquals("off", result.getString("from"))
            assertEquals("on", result.getString("to"))
        }
    }

    @Test
    fun modelMayStillNarrowCapabilities() {
        // 默认开启，模型可以主动关闭：这是收紧，属于允许的方向。
        assertTrue(enabled(Prefs.Keys.AGENT_TERMINAL_TOOLS))
        val response = set(Prefs.Keys.AGENT_TERMINAL_TOOLS to "off")
        assertTrue(response.getBoolean("ok"))
        assertFalse(enabled(Prefs.Keys.AGENT_TERMINAL_TOOLS))
        assertEquals(
            "next_tool_call",
            response.getJSONArray("results").getJSONObject(0).getString("effect"),
        )
    }

    @Test
    fun idempotentOnStaysAllowedWhenAlreadyEnabled() {
        // 已经开着的时候再要求 on 不算放宽，不该被这条规则挡住（否则模型无法复述现状）。
        assertTrue(enabled(Prefs.Keys.AGENT_TERMINAL_TOOLS))
        val response = set(Prefs.Keys.AGENT_TERMINAL_TOOLS to "on")
        assertTrue(response.getBoolean("ok"))
        assertTrue(enabled(Prefs.Keys.AGENT_TERMINAL_TOOLS))
    }

    @Test
    fun nonToolSettingsAreNotAffected() {
        // 非工具开关（触感、思考模式）不属于安全边界，模型可以自由开关。
        assertTrue(enabled(Prefs.Keys.AGENT_THINKING_ENABLED))
        assertTrue(set(Prefs.Keys.AGENT_THINKING_ENABLED to "off").getBoolean("ok"))
        assertFalse(enabled(Prefs.Keys.AGENT_THINKING_ENABLED))
        assertTrue(set(Prefs.Keys.AGENT_THINKING_ENABLED to "on").getBoolean("ok"))
        assertTrue(enabled(Prefs.Keys.AGENT_THINKING_ENABLED))
    }

    @Test
    fun mixedBatchStillAppliesTheAllowedNarrowing() {
        // 一批里既有允许的收紧、也有被拒的放宽：允许项照常写入，被拒项单独报错。
        assertTrue(enabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS))
        assertTrue(set(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to "off").getBoolean("ok"))

        val response = set(
            Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS to "off",
            Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to "on",
        )
        assertFalse("整批 ok 应为 false（有一项被拒）", response.getBoolean("ok"))
        assertEquals(1, response.getInt("changed"))
        assertFalse(enabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS))
        assertFalse("被拒的放宽不得生效", enabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS))
        val results = response.getJSONArray("results")
        assertEquals(2, results.length())
        assertTrue(results.getJSONObject(0).getBoolean("ok"))
        assertEquals("USER_CONSENT_REQUIRED", results.getJSONObject(1).getString("code"))
    }
}
