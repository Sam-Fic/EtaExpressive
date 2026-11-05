package io.github.mangi.eta.agent.xiaomi

import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiToolsBridgeRemoteExecutorTest {
    @Test
    fun `disabled risk group is rejected before remote invocation`() {
        val calls = AtomicInteger()
        val definition = smsDefinition()
        val executor = XiaomiToolsBridgeRemoteExecutor(
            catalog = XiaomiToolsBridgeProtocol.Catalog(listOf(definition)),
            sessionId = "run-1",
            riskEnabled = { false },
            invokeRemote = {
                calls.incrementAndGet()
                success(it.callId)
            },
        )

        val result = executor.execute(sendSmsCall())

        assertEquals(0, calls.get())
        assertTrue(result.sensitive)
        assertEquals(
            "XIAOMI_TOOL_PERMISSION_DISABLED",
            JSONObject(result.content).getString("code"),
        )
    }

    @Test
    fun `identical sensitive mutation cannot be retried in one run`() {
        val calls = AtomicInteger()
        val definition = smsDefinition()
        val executor = XiaomiToolsBridgeRemoteExecutor(
            catalog = XiaomiToolsBridgeProtocol.Catalog(listOf(definition)),
            sessionId = "run-1",
            riskEnabled = { true },
            invokeRemote = {
                calls.incrementAndGet()
                success(it.callId)
            },
        )

        val first = executor.execute(sendSmsCall())
        val second = executor.execute(
            sendSmsCall().copy(
                id = "call-2",
                argumentsJson = """{"message":"test","phone_number":"10086"}""",
            ),
        )

        assertEquals(1, calls.get())
        assertTrue(JSONObject(first.content).getBoolean("ok"))
        assertEquals(
            "XIAOMI_TOOL_RETRY_BLOCKED",
            JSONObject(second.content).getString("code"),
        )
    }

    private fun sendSmsCall() = AgentModelClient.ToolCall(
        id = "call-1",
        name = "xiaomi_send_sms",
        argumentsJson = """{"phone_number":"10086","message":"test"}""",
    )

    private fun smsDefinition() = requireNotNull(
        XiaomiToolsBridgeProtocol.definitionFromTarget(
            targetClassName = "com.aios.tools.builtin.system.SMSTool",
            rawName = "send_sms",
            description = "send",
            parametersJson = JSONObject()
                .put("type", "object")
                .put("properties", JSONObject())
                .toString(),
        ),
    )

    private fun success(callId: String) = XiaomiToolsBridgeProtocol.CallResult(
        callId = callId,
        status = XiaomiToolsBridgeProtocol.Status.SUCCESS,
        result = "sent",
    )
}
