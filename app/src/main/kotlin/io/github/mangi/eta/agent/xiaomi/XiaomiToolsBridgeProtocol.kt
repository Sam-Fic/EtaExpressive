package io.github.mangi.eta.agent.xiaomi

import android.net.Uri
import android.os.Bundle
import io.github.mangi.eta.agent.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

internal fun interface XiaomiToolsBridgeEndpoint {
    fun execute(
        request: XiaomiToolsBridgeProtocol.CallRequest,
        callback: (XiaomiToolsBridgeProtocol.CallResult) -> Unit,
    )
}

/**
 * 超级小爱内部 ToolsBridge 与 Eta Runtime 之间的最小协议。
 *
 * 目标 APK 的工具对象只在 `com.miui.voiceassist` 类加载器中存在；跨进程只传递经过
 * 白名单收敛的 schema、原始工具名和 JSON 参数，不传递任何目标类实例。
 */
internal object XiaomiToolsBridgeProtocol {
    const val MODEL_DIAL_PHONE = "xiaomi_dial_phone"

    private const val PROTOCOL_VERSION = 1
    private const val MAX_DEFINITIONS = 24
    private const val MAX_NAME_CHARS = 96
    private const val MAX_DESCRIPTION_CHARS = 4_000
    private const val MAX_PARAMETERS_CHARS = 24_000
    private const val MAX_ARGUMENTS_CHARS = 32_000
    private const val MAX_RESULT_CHARS = 64_000

    private const val KEY_VERSION = "version"
    private const val KEY_TOOLS = "tools"
    private const val KEY_TARGET_CLASS = "target_class"
    private const val KEY_RAW_NAME = "raw_name"
    private const val KEY_MODEL_NAME = "model_name"
    private const val KEY_DESCRIPTION = "description"
    private const val KEY_PARAMETERS = "parameters"
    private const val KEY_RISK = "risk"
    private const val KEY_CALL_ID = "call_id"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_ARGUMENTS = "arguments"
    private const val KEY_STATUS = "status"
    private const val KEY_RESULT = "result"
    private const val KEY_ERROR = "error"
    private const val KEY_EXECUTION_TIME_MS = "execution_time_ms"

    enum class Risk {
        DIRECT,
        SENSITIVE_READ,
        SENSITIVE_ACTION,
    }

    enum class Status {
        SUCCESS,
        ERROR,
        PERMISSION_DENIED,
        UNAVAILABLE,
        TIMEOUT,
        INVALID_REQUEST,
    }

    data class Definition(
        val targetClassName: String,
        val rawName: String,
        val modelName: String,
        val description: String,
        val parametersJson: String,
        val risk: Risk,
    ) {
        fun asModelTool(): JSONObject = AgentToolSchema.function(
            name = modelName,
            description = buildString {
                append("通过当前设备的超级小爱系统 ToolsBridge 执行。")
                when (risk) {
                    Risk.DIRECT -> Unit
                    Risk.SENSITIVE_READ -> append("该工具会读取敏感设备数据，原始结果不写入持久会话。")
                    Risk.SENSITIVE_ACTION ->
                        append("该工具仅在敏感设备操作开关与当前运行权限均允许时可用。")
                }
                append(description)
            },
            parameters = JSONObject(parametersJson),
        )
    }

    data class Catalog(
        val definitions: List<Definition>,
    ) {
        fun definition(modelName: String): Definition? =
            definitions.firstOrNull { it.modelName == modelName }

        fun visibleDefinitions(
            directTools: Boolean,
            sensitiveReadTools: Boolean,
            sensitiveActionTools: Boolean,
        ): List<Definition> = definitions.filter { definition ->
            when (definition.risk) {
                Risk.DIRECT -> directTools
                Risk.SENSITIVE_READ -> sensitiveReadTools
                Risk.SENSITIVE_ACTION -> sensitiveActionTools
            }
        }

        fun toJson(): String = JSONObject()
            .put(KEY_VERSION, PROTOCOL_VERSION)
            .put(
                KEY_TOOLS,
                JSONArray().also { tools ->
                    definitions.take(MAX_DEFINITIONS).forEach { definition ->
                        tools.put(
                            JSONObject()
                                .put(KEY_TARGET_CLASS, definition.targetClassName)
                                .put(KEY_RAW_NAME, definition.rawName)
                                .put(KEY_MODEL_NAME, definition.modelName)
                                .put(KEY_DESCRIPTION, definition.description)
                                .put(KEY_PARAMETERS, JSONObject(definition.parametersJson))
                                .put(KEY_RISK, definition.risk.name),
                        )
                    }
                },
            )
            .toString()

        companion object {
            val EMPTY = Catalog(emptyList())
        }
    }

    data class CallRequest(
        val callId: String,
        val sessionId: String,
        val modelName: String,
        val argumentsJson: String,
    )

    data class CallResult(
        val callId: String,
        val status: Status,
        val result: String? = null,
        val error: String? = null,
        val executionTimeMs: Long = 0L,
    )

    data class Exposure(
        val modelName: String,
        val risk: Risk,
        val customParameters: (() -> JSONObject)? = null,
    )

    fun exposureForTargetClass(className: String): Exposure? = exposures[className]

    fun definitionFromTarget(
        targetClassName: String,
        rawName: String,
        description: String,
        parametersJson: String,
    ): Definition? {
        val exposure = exposureForTargetClass(targetClassName) ?: return null
        val normalizedRawName = rawName.trim().takeIf(::isValidToolName) ?: return null
        val normalizedDescription = description.trim().take(MAX_DESCRIPTION_CHARS)
        val parameters = exposure.customParameters?.invoke()
            ?: runCatching { JSONObject(parametersJson) }.getOrNull()
            ?: return null
        if (parameters.optString("type") != "object") return null
        val normalizedParameters = parameters.toString()
        if (normalizedParameters.length > MAX_PARAMETERS_CHARS) return null
        return Definition(
            targetClassName = targetClassName,
            rawName = normalizedRawName,
            modelName = exposure.modelName,
            description = normalizedDescription,
            parametersJson = normalizedParameters,
            risk = exposure.risk,
        )
    }

    fun catalogFromJson(raw: String?): Catalog {
        if (raw.isNullOrBlank()) return Catalog.EMPTY
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return Catalog.EMPTY
        if (root.optInt(KEY_VERSION) != PROTOCOL_VERSION) return Catalog.EMPTY
        val tools = root.optJSONArray(KEY_TOOLS) ?: return Catalog.EMPTY
        val definitions = buildList {
            val seenNames = mutableSetOf<String>()
            for (index in 0 until minOf(tools.length(), MAX_DEFINITIONS)) {
                val item = tools.optJSONObject(index) ?: continue
                val targetClassName = item.optString(KEY_TARGET_CLASS)
                val exposure = exposureForTargetClass(targetClassName) ?: continue
                val rawName = item.optString(KEY_RAW_NAME).trim()
                val modelName = item.optString(KEY_MODEL_NAME).trim()
                if (!isValidToolName(rawName) || modelName != exposure.modelName) continue
                val risk = runCatching { Risk.valueOf(item.optString(KEY_RISK)) }.getOrNull()
                    ?: continue
                if (risk != exposure.risk || !seenNames.add(modelName)) continue
                val description = item.optString(KEY_DESCRIPTION).take(MAX_DESCRIPTION_CHARS)
                val parameters = item.optJSONObject(KEY_PARAMETERS) ?: continue
                if (parameters.optString("type") != "object") continue
                val parametersJson = parameters.toString()
                if (parametersJson.length > MAX_PARAMETERS_CHARS) continue
                add(
                    Definition(
                        targetClassName = targetClassName,
                        rawName = rawName,
                        modelName = modelName,
                        description = description,
                        parametersJson = parametersJson,
                        risk = risk,
                    ),
                )
            }
        }
        return Catalog(definitions)
    }

    fun targetArguments(definition: Definition, modelArgumentsJson: String): String {
        require(modelArgumentsJson.length <= MAX_ARGUMENTS_CHARS) { "ToolsBridge 参数过长" }
        val arguments = JSONObject(modelArgumentsJson.ifBlank { "{}" })
        if (definition.modelName != MODEL_DIAL_PHONE) return arguments.toString()

        val phoneNumber = arguments.optString("phone_number").trim()
        require(phoneNumber.length in 1..64 && phoneNumber.any(Char::isDigit)) {
            "phone_number 必须包含有效号码"
        }
        require(phoneNumber.all { it.isDigit() || it in PHONE_NUMBER_PUNCTUATION }) {
            "phone_number 包含不支持的字符"
        }
        return JSONObject()
            .put("type", "activity")
            .put("action", "android.intent.action.DIAL")
            .put("data", Uri.fromParts("tel", phoneNumber, null).toString())
            .toString()
    }

    fun callRequestBundle(request: CallRequest): Bundle = Bundle().apply {
        require(request.callId.isNotBlank()) { "ToolsBridge callId 不能为空" }
        require(request.modelName.length <= MAX_NAME_CHARS) { "ToolsBridge 工具名过长" }
        require(request.argumentsJson.length <= MAX_ARGUMENTS_CHARS) { "ToolsBridge 参数过长" }
        putString(KEY_CALL_ID, request.callId)
        putString(KEY_SESSION_ID, request.sessionId.take(MAX_NAME_CHARS))
        putString(KEY_MODEL_NAME, request.modelName)
        putString(KEY_ARGUMENTS, request.argumentsJson)
    }

    fun callRequestFromBundle(bundle: Bundle): CallRequest? {
        val callId = bundle.getString(KEY_CALL_ID).orEmpty().trim()
        val sessionId = bundle.getString(KEY_SESSION_ID).orEmpty().trim()
        val modelName = bundle.getString(KEY_MODEL_NAME).orEmpty().trim()
        val arguments = bundle.getString(KEY_ARGUMENTS).orEmpty()
        if (callId.isBlank() || !isValidToolName(modelName)) return null
        if (arguments.length > MAX_ARGUMENTS_CHARS) return null
        if (runCatching { JSONObject(arguments.ifBlank { "{}" }) }.isFailure) return null
        return CallRequest(callId, sessionId, modelName, arguments)
    }

    fun callIdFromBundle(bundle: Bundle): String =
        bundle.getString(KEY_CALL_ID).orEmpty().trim()

    fun callResultBundle(result: CallResult): Bundle = Bundle().apply {
        putString(KEY_CALL_ID, result.callId)
        putString(KEY_STATUS, result.status.name)
        putString(KEY_RESULT, result.result?.take(MAX_RESULT_CHARS))
        putString(KEY_ERROR, result.error?.take(MAX_RESULT_CHARS))
        putLong(KEY_EXECUTION_TIME_MS, result.executionTimeMs.coerceAtLeast(0L))
    }

    fun callResultFromBundle(bundle: Bundle): CallResult? {
        val callId = bundle.getString(KEY_CALL_ID).orEmpty().trim()
        val status = runCatching {
            Status.valueOf(bundle.getString(KEY_STATUS).orEmpty())
        }.getOrNull()
        if (callId.isBlank() || status == null) return null
        return CallResult(
            callId = callId,
            status = status,
            result = bundle.getString(KEY_RESULT)?.take(MAX_RESULT_CHARS),
            error = bundle.getString(KEY_ERROR)?.take(MAX_RESULT_CHARS),
            executionTimeMs = bundle.getLong(KEY_EXECUTION_TIME_MS).coerceAtLeast(0L),
        )
    }

    private fun isValidToolName(value: String): Boolean =
        value.length in 1..MAX_NAME_CHARS && TOOL_NAME.matches(value)

    private fun dialParameters(): JSONObject = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject().put(
                "phone_number",
                JSONObject()
                    .put("type", "string")
                    .put("minLength", 1)
                    .put("maxLength", 64)
                    .put("description", "要预填到系统拨号器的电话号码；该工具不会直接呼出"),
            ),
        )
        .put("required", JSONArray().put("phone_number"))

    private val exposures = mapOf(
        "com.aios.tools.builtin.system.SendIntentTool" to
            Exposure(MODEL_DIAL_PHONE, Risk.DIRECT, ::dialParameters),
        "com.aios.tools.builtin.system.ReadSMSTool" to
            Exposure("xiaomi_read_sms", Risk.SENSITIVE_READ),
        "com.aios.tools.builtin.system.SMSTool" to
            Exposure("xiaomi_send_sms", Risk.SENSITIVE_ACTION),
        "com.aios.tools.builtin.calendar.ReadCalendarTool" to
            Exposure("xiaomi_read_calendar", Risk.SENSITIVE_READ),
        "com.aios.tools.builtin.calendar.CreateCalendarEventTool" to
            Exposure("xiaomi_create_calendar_event", Risk.SENSITIVE_ACTION),
        "com.aios.tools.builtin.calendar.DeleteCalendarEventTool" to
            Exposure("xiaomi_delete_calendar_event", Risk.SENSITIVE_ACTION),
        "com.aios.tools.builtin.system.ContactSearchTool" to
            Exposure("xiaomi_search_contacts", Risk.SENSITIVE_READ),
        "com.aios.tools.builtin.system.ContactManageTool" to
            Exposure("xiaomi_manage_contacts", Risk.SENSITIVE_ACTION),
        "com.aios.tools.builtin.system.WifiInfoTool" to
            Exposure("xiaomi_wifi_info", Risk.DIRECT),
        "com.aios.tools.builtin.system.ListWifiNetworksTool" to
            Exposure("xiaomi_list_wifi_networks", Risk.SENSITIVE_READ),
        "com.aios.tools.builtin.system.ConnectWifiTool" to
            Exposure("xiaomi_connect_wifi", Risk.SENSITIVE_ACTION),
        "com.aios.tools.builtin.system.DisconnectWifiTool" to
            Exposure("xiaomi_disconnect_wifi", Risk.SENSITIVE_ACTION),
        "com.aios.tools.builtin.system.SwitchWifiTool" to
            Exposure("xiaomi_switch_wifi", Risk.SENSITIVE_ACTION),
        "com.aios.tools.builtin.system.bluetooth.BluetoothStatusTool" to
            Exposure("xiaomi_bluetooth_status", Risk.DIRECT),
        "com.aios.tools.builtin.system.bluetooth.BluetoothScanTool" to
            Exposure("xiaomi_bluetooth_scan", Risk.SENSITIVE_READ),
        "com.aios.tools.builtin.system.bluetooth.BluetoothPairedDevicesTool" to
            Exposure("xiaomi_bluetooth_paired_devices", Risk.SENSITIVE_READ),
        "com.aios.tools.builtin.system.bluetooth.BluetoothConnectTool" to
            Exposure("xiaomi_bluetooth_connect", Risk.SENSITIVE_ACTION),
        "com.aios.tools.builtin.system.bluetooth.BluetoothDisconnectTool" to
            Exposure("xiaomi_bluetooth_disconnect", Risk.SENSITIVE_ACTION),
        "com.aios.tools.builtin.system.bluetooth.BluetoothToggleTool" to
            Exposure("xiaomi_bluetooth_toggle", Risk.SENSITIVE_ACTION),
    )

    private val TOOL_NAME = Regex("^[A-Za-z0-9_.-]+$")
    private val PHONE_NUMBER_PUNCTUATION = setOf('+', '*', '#', ' ', '-', '(', ')', ',', ';')
}
