package io.github.mangi.eta.agent.xiaomi

import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Runtime 进程侧代理；真正的工具代码仍在超级小爱进程内执行。 */
internal class XiaomiToolsBridgeRemoteExecutor(
    private val catalog: XiaomiToolsBridgeProtocol.Catalog,
    private val sessionId: String,
    private val riskEnabled: (XiaomiToolsBridgeProtocol.Risk) -> Boolean,
    private val invokeRemote: (
        XiaomiToolsBridgeProtocol.CallRequest,
    ) -> XiaomiToolsBridgeProtocol.CallResult,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    val toolNames: Set<String> = catalog.definitions.mapTo(linkedSetOf()) { it.modelName }

    private val closed = AtomicBoolean(false)
    private val mutationFingerprints = ConcurrentHashMap.newKeySet<String>()

    override fun close() {
        closed.set(true)
        mutationFingerprints.clear()
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val definition = catalog.definition(toolCall.name)
            ?: return failure("XIAOMI_TOOL_NOT_DECLARED", "该超级小爱工具未在本轮能力目录中声明")
        if (closed.get()) {
            return failure("XIAOMI_BRIDGE_CLOSED", "超级小爱 ToolsBridge 已关闭")
        }
        if (!riskEnabled(definition.risk)) {
            val message = when (definition.risk) {
                XiaomiToolsBridgeProtocol.Risk.DIRECT -> "请先启用设备直达工具"
                XiaomiToolsBridgeProtocol.Risk.SENSITIVE_READ -> "请先允许读取敏感设备信息"
                XiaomiToolsBridgeProtocol.Risk.SENSITIVE_ACTION -> "请先显式启用敏感设备操作"
            }
            return failure("XIAOMI_TOOL_PERMISSION_DISABLED", message)
        }

        val arguments = runCatching {
            JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
        }.getOrElse {
            return failure("INVALID_ARGUMENT", "工具参数不是有效的 JSON object")
        }
        val normalizedArguments = arguments.toString()
        if (
            definition.risk == XiaomiToolsBridgeProtocol.Risk.SENSITIVE_ACTION &&
            !mutationFingerprints.add(
                "${definition.modelName}:${canonicalJsonValue(arguments)}",
            )
        ) {
            return failure(
                "XIAOMI_TOOL_RETRY_BLOCKED",
                "本轮已提交过完全相同的敏感操作；为避免重复执行，禁止自动重试",
            )
        }

        val result = invokeRemote(
            XiaomiToolsBridgeProtocol.CallRequest(
                callId = UUID.randomUUID().toString(),
                sessionId = sessionId,
                modelName = definition.modelName,
                argumentsJson = normalizedArguments,
            ),
        )
        val ok = result.status == XiaomiToolsBridgeProtocol.Status.SUCCESS
        val content = JSONObject()
            .put("ok", ok)
            .put("source", "xiaomi_tools_bridge")
            .put("tool", definition.modelName)
            .put("status", result.status.name.lowercase())
            .put("execution_time_ms", result.executionTimeMs)
            .also { json ->
                result.result?.let { raw -> json.put("result", parseJsonOrString(raw)) }
                result.error?.let { json.put("error", it) }
                if (!ok) json.put("code", "XIAOMI_TOOLS_BRIDGE_${result.status.name}")
            }
            .toString()
        return AgentModelClient.ToolResult(content = content, sensitive = true)
    }

    private fun failure(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("source", "xiaomi_tools_bridge")
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = true,
        )

    private fun parseJsonOrString(raw: String): Any = runCatching {
        JSONTokener(raw).nextValue().takeUnless { value -> value == null }
    }.getOrNull() ?: raw

    private fun canonicalJsonValue(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted()
            .joinToString(prefix = "{", postfix = "}") { key ->
                "${JSONObject.quote(key)}:${canonicalJsonValue(value.opt(key))}"
            }
        is JSONArray -> (0 until value.length())
            .joinToString(prefix = "[", postfix = "]") { index ->
                canonicalJsonValue(value.opt(index))
            }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}
