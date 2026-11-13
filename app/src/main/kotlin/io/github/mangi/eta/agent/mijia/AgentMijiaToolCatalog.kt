package io.github.mangi.eta.agent.mijia

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import org.json.JSONArray
import org.json.JSONObject

internal object AgentMijiaToolCatalog {
    const val MIJIA_CONTROL = "mijia_control"

    private val ACTIONS = listOf(
        "list_devices",
        "get_device_spec",
        "get_props",
        "set_prop",
        "call_action",
        "voice_command",
    )

    fun isUsable(context: Context): Boolean =
        MijiaStore.isEnabled(context) && MijiaStore.loadAuth(context).isAuthorized

    fun appendSchemas(tools: JSONArray, enabled: Boolean) {
        if (!enabled) return
        tools.put(
            AgentToolSchema.function(
                name = MIJIA_CONTROL,
                description = "通过小米官方 HA 云协议直连控制用户的米家智能家居设备（无需打开米家 App）。" +
                    "支持列出家庭与房间设备(list_devices)、查看设备 MIoT 规格与当前状态(get_device_spec / get_props)、" +
                    "开关电源或修改属性(set_prop)、执行设备动作(call_action)以及自然语言快速控制(voice_command)。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray(ACTIONS))
                                    .put("description", "操作类型：list_devices / get_device_spec / get_props / set_prop / call_action / voice_command"),
                            )
                            .put(
                                "did",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "可选，设备唯一 ID (did)；未提供时可通过 device_name 匹配"),
                            )
                            .put(
                                "device_name",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "可选，设备名称或「房间名+设备名」（如“客厅吸顶灯”、“主卧空调”）"),
                            )
                            .put(
                                "room",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "可选，list_devices 时按房间名筛选"),
                            )
                            .put(
                                "query",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "可选，list_devices 时按名称或型号筛选，或 voice_command 的自然语言指令"),
                            )
                            .put(
                                "power",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "可选，set_prop 时直接指定 true/false 控制设备主电源开关，无需传 siid/piid"),
                            )
                            .put(
                                "siid",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "set_prop 或 call_action 的服务 ID (siid)"),
                            )
                            .put(
                                "piid",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "set_prop 的属性 ID (piid)"),
                            )
                            .put(
                                "aiid",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "call_action 的动作 ID (aiid)"),
                            )
                            .put(
                                "value",
                                JSONObject()
                                    .put("description", "set_prop 要写入的属性值（布尔、整数、浮点数或字符串）"),
                            )
                            .put(
                                "in_values",
                                JSONObject()
                                    .put("type", "array")
                                    .put("items", JSONObject())
                                    .put("description", "可选，call_action 的输入参数数组"),
                            ),
                    )
                    .put("required", JSONArray(listOf("action"))),
            ),
        )
    }

    fun execute(
        context: Context,
        argumentsJson: String,
    ): AgentModelClient.ToolResult {
        val args = runCatching { JSONObject(argumentsJson.ifBlank { "{}" }) }
            .getOrElse {
                return fail("MIJIA_BAD_ARGS", "参数不是合法 JSON：${it.message}")
            }
        if (!MijiaStore.isEnabled(context)) {
            return fail("MIJIA_DISABLED", "米家直连工具已关闭")
        }
        if (!MijiaStore.loadAuth(context).isAuthorized) {
            return fail("MIJIA_UNAUTHORIZED", "米家账号尚未授权登录，请先在 Eta 设置 -> 米家直连中完成小米账号 OAuth 授权")
        }

        val action = args.optString("action").trim()
        return when (action) {
            "list_devices" -> handleListDevices(context, args)
            "get_device_spec" -> handleGetDeviceSpec(context, args)
            "get_props" -> handleGetProps(context, args)
            "set_prop" -> handleSetProp(context, args)
            "call_action" -> handleCallAction(context, args)
            "voice_command" -> handleVoiceCommand(context, args)
            else -> fail("MIJIA_UNKNOWN_ACTION", "未知 action: $action")
        }
    }

    private fun ensureDevicesLoaded(context: Context, forceRefresh: Boolean = false): List<MijiaDevice> {
        var list = if (forceRefresh) emptyList() else MijiaStore.loadDevices(context)
        if (list.isEmpty()) {
            MijiaCloudClient.syncHomesAndDevices(context).onSuccess { (_, synced) ->
                list = synced
            }
        }
        return list
    }

    private fun resolveTargetDevice(context: Context, args: JSONObject): MijiaDevice? {
        val devices = ensureDevicesLoaded(context)
        val did = args.optString("did").trim()
        if (did.isNotEmpty()) {
            devices.firstOrNull { it.did == did }?.let { return it }
        }
        val name = args.optString("device_name").trim().ifBlank { args.optString("query").trim() }
        if (name.isEmpty()) return null
        val q = name.lowercase()
        return devices.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: devices.firstOrNull { "${it.roomName}${it.name}".equals(name, ignoreCase = true) }
            ?: devices.firstOrNull { it.name.lowercase().contains(q) || "${it.roomName}${it.name}".lowercase().contains(q) }
    }

    private fun handleListDevices(context: Context, args: JSONObject): AgentModelClient.ToolResult {
        val refresh = args.optBoolean("refresh", false)
        val devices = ensureDevicesLoaded(context, forceRefresh = refresh)
        val roomFilter = args.optString("room").trim().lowercase()
        val queryFilter = args.optString("query").trim().lowercase()

        val filtered = devices.filter { d ->
            (roomFilter.isEmpty() || d.roomName.lowercase().contains(roomFilter)) &&
                (queryFilter.isEmpty() || d.name.lowercase().contains(queryFilter) || d.model.lowercase().contains(queryFilter) || d.category.lowercase().contains(queryFilter))
        }
        val favSet = MijiaStore.favoriteDids(context).toSet()
        val arr = JSONArray().apply {
            filtered.forEach { d ->
                put(
                    JSONObject()
                        .put("did", d.did)
                        .put("name", d.name)
                        .put("room", d.roomName)
                        .put("home", d.homeName)
                        .put("model", d.model)
                        .put("urn", d.urn)
                        .put("category", d.category)
                        .put("online", d.online)
                        .put("favorite", d.did in favSet),
                )
            }
        }
        return ok(
            JSONObject()
                .put("count", filtered.size)
                .put("devices", arr),
        )
    }

    private fun handleGetDeviceSpec(context: Context, args: JSONObject): AgentModelClient.ToolResult {
        val device = resolveTargetDevice(context, args)
            ?: return fail("MIJIA_DEVICE_NOT_FOUND", "未找到匹配的米家设备")
        val spec = MijiaSpecParser.fetchAndParse(context, device.urn).getOrElse { err ->
            return fail("MIJIA_SPEC_ERROR", "获取设备规格失败：${err.message}")
        }
        val readable = spec.readableProps()
        val valuesMap = if (readable.isNotEmpty()) {
            MijiaCloudClient.getProps(
                context,
                readable.map { Triple(device.did, it.siid, it.piid) },
            ).getOrDefault(emptyMap())
        } else {
            emptyMap()
        }

        val propsArr = JSONArray().apply {
            spec.properties.forEach { p ->
                put(
                    JSONObject()
                        .put("siid", p.siid)
                        .put("piid", p.piid)
                        .put("service", p.serviceName)
                        .put("name", p.name)
                        .put("description", p.description)
                        .put("format", p.format)
                        .put("access", JSONArray(p.access))
                        .put("unit", p.unit)
                        .put("current_value", valuesMap["${p.siid}.${p.piid}"])
                        .apply {
                            p.valueRange?.let { r ->
                                put("range", JSONObject().put("min", r.min).put("max", r.max).put("step", r.step))
                            }
                            if (p.valueList.isNotEmpty()) {
                                put(
                                    "value_list",
                                    JSONArray().apply {
                                        p.valueList.forEach { v ->
                                            put(JSONObject().put("value", v.value).put("description", v.description))
                                        }
                                    },
                                )
                            }
                        },
                )
            }
        }
        val actionsArr = JSONArray().apply {
            spec.actions.forEach { a ->
                put(
                    JSONObject()
                        .put("siid", a.siid)
                        .put("aiid", a.aiid)
                        .put("service", a.serviceName)
                        .put("name", a.name)
                        .put("description", a.description)
                        .put("in_piids", JSONArray(a.inPiids)),
                )
            }
        }
        return ok(
            JSONObject()
                .put("did", device.did)
                .put("name", device.name)
                .put("room", device.roomName)
                .put("model", device.model)
                .put("online", device.online)
                .put("properties", propsArr)
                .put("actions", actionsArr),
        )
    }

    private fun handleGetProps(context: Context, args: JSONObject): AgentModelClient.ToolResult {
        val device = resolveTargetDevice(context, args)
            ?: return fail("MIJIA_DEVICE_NOT_FOUND", "未找到匹配的米家设备")
        val spec = MijiaSpecParser.fetchAndParse(context, device.urn).getOrElse { err ->
            return fail("MIJIA_SPEC_ERROR", "获取设备规格失败：${err.message}")
        }
        val readable = spec.readableProps()
        val valuesMap = MijiaCloudClient.getProps(
            context,
            readable.map { Triple(device.did, it.siid, it.piid) },
        ).getOrElse { err ->
            return fail("MIJIA_GET_PROPS_FAILED", "读取属性失败：${err.message}")
        }
        val stateArr = JSONArray().apply {
            readable.forEach { p ->
                put(
                    JSONObject()
                        .put("siid", p.siid)
                        .put("piid", p.piid)
                        .put("name", p.name)
                        .put("description", p.description)
                        .put("value", valuesMap["${p.siid}.${p.piid}"])
                        .put("unit", p.unit),
                )
            }
        }
        return ok(
            JSONObject()
                .put("did", device.did)
                .put("name", device.name)
                .put("room", device.roomName)
                .put("online", device.online)
                .put("props", stateArr),
        )
    }

    private fun handleSetProp(context: Context, args: JSONObject): AgentModelClient.ToolResult {
        val device = resolveTargetDevice(context, args)
            ?: return fail("MIJIA_DEVICE_NOT_FOUND", "未找到匹配的米家设备")
        if (args.has("power") && !args.isNull("power")) {
            val targetOn = args.optBoolean("power")
            val res = MijiaCloudClient.toggleDevicePower(context, device, targetOn)
            return res.fold(
                onSuccess = { newState ->
                    ok(
                        JSONObject()
                            .put("did", device.did)
                            .put("name", device.name)
                            .put("power", newState)
                            .put("message", "已${if (newState) "开启" else "关闭"} ${device.name}"),
                    )
                },
                onFailure = { err ->
                    fail("MIJIA_SET_POWER_FAILED", err.message ?: "开关电源失败")
                },
            )
        }
        val siid = args.optInt("siid", -1)
        val piid = args.optInt("piid", -1)
        if (siid <= 0 || piid <= 0 || !args.has("value")) {
            return fail("MIJIA_BAD_ARGS", "set_prop 需要提供 power=true/false 或 (siid, piid, value)")
        }
        val value = args.opt("value") ?: return fail("MIJIA_BAD_ARGS", "value 不能为空")
        val res = MijiaCloudClient.setProp(context, device.did, siid, piid, value)
        return res.fold(
            onSuccess = {
                ok(
                    JSONObject()
                        .put("did", device.did)
                        .put("name", device.name)
                        .put("siid", siid)
                        .put("piid", piid)
                        .put("value", value),
                )
            },
            onFailure = { err ->
                fail("MIJIA_SET_PROP_FAILED", err.message ?: "写入属性失败")
            },
        )
    }

    private fun handleCallAction(context: Context, args: JSONObject): AgentModelClient.ToolResult {
        val device = resolveTargetDevice(context, args)
            ?: return fail("MIJIA_DEVICE_NOT_FOUND", "未找到匹配的米家设备")
        val siid = args.optInt("siid", -1)
        val aiid = args.optInt("aiid", -1)
        if (siid <= 0 || aiid <= 0) {
            return fail("MIJIA_BAD_ARGS", "call_action 需要提供 siid 与 aiid")
        }
        val inArr = args.optJSONArray("in_values") ?: JSONArray()
        val inValues = (0 until inArr.length()).map { inArr.get(it) }
        val res = MijiaCloudClient.callAction(context, device.did, siid, aiid, inValues)
        return res.fold(
            onSuccess = { msg ->
                ok(
                    JSONObject()
                        .put("did", device.did)
                        .put("name", device.name)
                        .put("siid", siid)
                        .put("aiid", aiid)
                        .put("message", msg),
                )
            },
            onFailure = { err ->
                fail("MIJIA_ACTION_FAILED", err.message ?: "执行动作失败")
            },
        )
    }

    private fun handleVoiceCommand(context: Context, args: JSONObject): AgentModelClient.ToolResult {
        val command = args.optString("query").trim().ifBlank { args.optString("device_name").trim() }
        if (command.isEmpty()) {
            return fail("MIJIA_BAD_ARGS", "voice_command 需要提供 query 自然语言指令")
        }
        val res = MijiaVoiceMatcher.maybeHandleVoiceQuery(context, "/米家 $command")
        return if (res.ok) {
            ok(
                JSONObject()
                    .put("device_name", res.deviceName)
                    .put("reply", res.replyText),
            )
        } else {
            fail("MIJIA_VOICE_COMMAND_FAILED", res.replyText.ifBlank { "未能执行米家语音指令" })
        }
    }

    private fun ok(payload: JSONObject): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = payload.put("ok", true).put("tool", MIJIA_CONTROL).toString(),
        )

    private fun fail(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("tool", MIJIA_CONTROL)
                .put("code", code)
                .put("message", message)
                .toString(),
        )
}
