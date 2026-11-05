package io.github.mangi.eta.agent.xiaomi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class XiaomiToolsBridgeProtocolTest {
    @Test
    fun `only allowlisted target classes become model tools`() {
        val dial = definition(
            targetClass = "com.aios.tools.builtin.system.SendIntentTool",
            rawName = "send_intent",
        )
        val rejected = XiaomiToolsBridgeProtocol.definitionFromTarget(
            targetClassName = "com.aios.tools.builtin.file.FileDeleteTool",
            rawName = "delete_file",
            description = "delete",
            parametersJson = emptyParameters().toString(),
        )

        assertEquals("xiaomi_dial_phone", dial.modelName)
        assertEquals("send_intent", dial.rawName)
        assertFalse(dial.asModelTool().toString().contains("component_package"))
        assertEquals(null, rejected)
    }

    @Test
    fun `dial alias always becomes activity ACTION_DIAL`() {
        val dial = definition(
            targetClass = "com.aios.tools.builtin.system.SendIntentTool",
            rawName = "send_intent",
        )

        val arguments = JSONObject(
            XiaomiToolsBridgeProtocol.targetArguments(
                dial,
                """{"phone_number":"+86 10086"}""",
            ),
        )

        assertEquals("activity", arguments.getString("type"))
        assertEquals("android.intent.action.DIAL", arguments.getString("action"))
        assertTrue(arguments.getString("data").startsWith("tel:"))
        assertFalse(arguments.has("component_package"))
    }

    @Test
    fun `catalog round trip preserves only declared exposure metadata`() {
        val source = XiaomiToolsBridgeProtocol.Catalog(
            listOf(
                definition(
                    "com.aios.tools.builtin.system.WifiInfoTool",
                    "wifi_info",
                ),
                definition(
                    "com.aios.tools.builtin.system.ReadSMSTool",
                    "read_sms",
                ),
                definition(
                    "com.aios.tools.builtin.system.SMSTool",
                    "send_sms",
                ),
            ),
        )

        val restored = XiaomiToolsBridgeProtocol.catalogFromJson(source.toJson())

        assertEquals(source, restored)
        assertEquals(
            setOf("xiaomi_wifi_info"),
            restored.visibleDefinitions(
                directTools = true,
                sensitiveReadTools = false,
                sensitiveActionTools = false,
            ).mapTo(mutableSetOf()) { it.modelName },
        )
        assertEquals(
            setOf("xiaomi_read_sms", "xiaomi_send_sms"),
            restored.visibleDefinitions(
                directTools = false,
                sensitiveReadTools = true,
                sensitiveActionTools = true,
            ).mapTo(mutableSetOf()) { it.modelName },
        )
    }

    @Test
    fun `bridge call bundle round trip remains structured`() {
        val request = XiaomiToolsBridgeProtocol.CallRequest(
            callId = "call-1",
            sessionId = "run-1",
            modelName = "xiaomi_wifi_info",
            argumentsJson = "{}",
        )
        val restoredRequest = XiaomiToolsBridgeProtocol.callRequestFromBundle(
            XiaomiToolsBridgeProtocol.callRequestBundle(request),
        )
        val result = XiaomiToolsBridgeProtocol.CallResult(
            callId = "call-1",
            status = XiaomiToolsBridgeProtocol.Status.SUCCESS,
            result = "ok",
            executionTimeMs = 12,
        )
        val restoredResult = XiaomiToolsBridgeProtocol.callResultFromBundle(
            XiaomiToolsBridgeProtocol.callResultBundle(result),
        )

        assertEquals(request, restoredRequest)
        assertEquals(result, restoredResult)
        assertNotNull(restoredRequest)
    }

    private fun definition(targetClass: String, rawName: String) =
        requireNotNull(
            XiaomiToolsBridgeProtocol.definitionFromTarget(
                targetClassName = targetClass,
                rawName = rawName,
                description = "test",
                parametersJson = emptyParameters().toString(),
            ),
        )

    private fun emptyParameters() = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject())
}
