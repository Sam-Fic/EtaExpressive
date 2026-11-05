package io.github.mangi.eta.hook.xiaoai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaoAiUiAgentBridgeTest {
    @Test
    fun assetDiscoveryOnlyAcceptsKnownToolsFromTheExpectedService() {
        val names = XiaoAiUiAgentBridge.availableToolNamesFromAsset(
            """
            [
              {"name":"controlApp","class":"com.xiaomi.voiceassistant.UIAgentServiceForOSBot"},
              {"name":"get_memory_data","class":"com.xiaomi.voiceassistant.UIAgentServiceForOSBot"},
              {"name":"future_tool","class":"com.xiaomi.voiceassistant.UIAgentServiceForOSBot"},
              {"name":"read_memory","class":"attacker.Service"}
            ]
            """.trimIndent()
        )

        assertEquals(setOf("controlApp", "get_memory_data"), names)
    }

    @Test
    fun rpcRequestUsesThePublishedToolsCallEnvelope() {
        val request = JSONObject(
            XiaomiUiAgentRpc.buildRequest(
                id = 7L,
                toolName = "trans_text",
                arguments = JSONObject().put("text", "你好").put("to", "en"),
            )
        )

        assertEquals("2.0", request.getString("jsonrpc"))
        assertEquals(7L, request.getLong("id"))
        assertEquals("tools/call", request.getString("method"))
        assertEquals("trans_text", request.getJSONObject("params").getString("name"))
        assertEquals("你好", request.getJSONObject("params").getJSONObject("arguments").getString("text"))
    }

    @Test
    fun rpcJsonTextResultRemainsStructuredAndSensitiveToolsStaySensitive() {
        val result = XiaomiUiAgentRpc.parseResponse(
            toolName = "read_memory",
            arguments = JSONObject().put("keywords", "地址"),
            responseJson = """
                {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"items\":[{\"id\":1}]}"}],"isError":false}}
            """.trimIndent(),
        )
        val content = JSONObject(result.content)

        assertTrue(content.getBoolean("ok"))
        assertEquals(1, content.getJSONObject("result").getJSONArray("items").length())
        assertTrue(result.sensitive)
    }

    @Test
    fun rpcToolErrorsAreNormalizedWithoutLeakingAnInvalidSuccess() {
        val result = XiaomiUiAgentRpc.parseResponse(
            toolName = "search_mi_knowledge",
            arguments = JSONObject(),
            responseJson = """
                {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"服务繁忙"}],"isError":true}}
            """.trimIndent(),
        )
        val content = JSONObject(result.content)

        assertFalse(content.getBoolean("ok"))
        assertEquals("XIAOMI_UIAGENT_TOOL_ERROR", content.getString("code"))
        assertFalse(result.sensitive)
    }
}
