package io.github.mangi.eta.agent.mijia

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

internal data class MijiaValueRange(
    val min: Double,
    val max: Double,
    val step: Double,
)

internal data class MijiaValueItem(
    val value: Any,
    val description: String,
)

internal data class MijiaPropSpec(
    val siid: Int,
    val piid: Int,
    val serviceName: String,
    val serviceDesc: String,
    val name: String,
    val description: String,
    val format: String,
    val access: List<String>,
    val unit: String = "",
    val valueRange: MijiaValueRange? = null,
    val valueList: List<MijiaValueItem> = emptyList(),
) {
    val serviceDescription: String
        get() = serviceDesc

    val readable: Boolean
        get() = access.any { it.equals("read", ignoreCase = true) }

    val writable: Boolean
        get() = access.any { it.equals("write", ignoreCase = true) }

    val isBool: Boolean
        get() = format.equals("bool", ignoreCase = true)
}

internal data class MijiaActionSpec(
    val siid: Int,
    val aiid: Int,
    val serviceName: String,
    val name: String,
    val description: String,
    val inPiids: List<Int> = emptyList(),
) {
    val inProps: List<Int>
        get() = inPiids
}

internal data class MijiaDeviceSpec(
    val urn: String,
    val deviceName: String,
    val description: String,
    val properties: List<MijiaPropSpec>,
    val actions: List<MijiaActionSpec>,
) {
    fun primaryPowerProp(preferredSiid: Int? = null): MijiaPropSpec? {
        val boolWritables = properties.filter { it.isBool && it.writable }
        if (boolWritables.isEmpty()) return null
        val excludedServices = setOf(
            "indicator-light",
            "physical-controls-locked",
            "alarm",
            "do-not-disturb",
            " ambient-light",
        )
        val preferredServices = listOf(
            "light",
            "switch",
            "air-conditioner",
            "fan",
            "air-purifier",
            "heater",
            "humidifier",
            "dehumidifier",
            "television",
            "tv-box",
            "curtain",
            "water-heater",
            "outlet",
            "vacuum",
        )
        val candidates = boolWritables.filter {
            it.serviceName.lowercase() !in excludedServices &&
                !it.name.contains("lock", ignoreCase = true) &&
                !it.name.contains("indicator", ignoreCase = true)
        }.ifEmpty { boolWritables }

        if (preferredSiid != null && preferredSiid > 0) {
            candidates.firstOrNull {
                it.siid == preferredSiid &&
                    (it.name.equals("on", ignoreCase = true) || it.name.equals("power", ignoreCase = true))
            }?.let { return it }
            candidates.firstOrNull { it.siid == preferredSiid }?.let { return it }
        }

        for (srv in preferredServices) {
            candidates.firstOrNull {
                it.serviceName.equals(srv, ignoreCase = true) &&
                    (it.name.equals("on", ignoreCase = true) || it.name.equals("power", ignoreCase = true))
            }?.let { return it }
        }
        return candidates.firstOrNull {
            it.name.equals("on", ignoreCase = true) || it.name.equals("power", ignoreCase = true)
        } ?: candidates.firstOrNull { it.siid == 2 && it.piid == 1 }
            ?: candidates.first()
    }

    fun writableProps(): List<MijiaPropSpec> = properties.filter { it.writable }

    fun readableProps(): List<MijiaPropSpec> = properties.filter { it.readable }
}

internal object MijiaSpecParser {
    private const val SPEC_INSTANCE_URL = "https://miot-spec.org/miot-spec-v2/instance"
    private val memoryCache = ConcurrentHashMap<String, MijiaDeviceSpec>()

    private val zhNameTranslations = mapOf(
        "on" to "开关",
        "status" to "状态",
        "mode" to "模式",
        "fault" to "故障状态",
        "brightness" to "亮度",
        "color-temperature" to "色温",
        "color" to "颜色",
        "saturability" to "饱和度",
        "flow" to "流光模式",
        "target-temperature" to "目标温度",
        "temperature" to "温度",
        "relative-humidity" to "相对湿度",
        "target-humidity" to "目标湿度",
        "fan-level" to "风速档位",
        "heating" to "制热",
        "dryer" to "干燥",
        "sleep-mode" to "睡眠模式",
        "eco" to "节能模式",
        "anion" to "负离子",
        "uv" to "UV杀菌",
        "vertical-swing" to "上下摆风",
        "horizontal-swing" to "左右摆风",
        "motor-control" to "电机控制",
        "target-position" to "目标开合度",
        "current-position" to "当前开合度",
        "volume" to "音量",
        "mute" to "静音",
        "battery-level" to "电池电量",
        "charging-state" to "充电状态",
        "pm2.5-density" to "PM2.5浓度",
        "co2-density" to "CO2浓度",
        "air-quality" to "空气质量",
        "filter-life-level" to "滤芯剩余寿命",
        "electric-power" to "当前功率",
        "power-consumption" to "耗电量",
        "toggle" to "切换开关",
        "start-sweep" to "开始清扫",
        "stop-sweeping" to "停止清扫",
        "start-charge" to "开始回充",
        "pause" to "暂停",
        "play" to "播放",
    )

    private val zhServiceTranslations = mapOf(
        "light" to "灯光",
        "switch" to "开关",
        "air-conditioner" to "空调",
        "fan" to "风扇",
        "air-purifier" to "空气净化器",
        "humidifier" to "加湿器",
        "dehumidifier" to "除湿机",
        "heater" to "取暖器",
        "curtain" to "窗帘",
        "vacuum" to "扫拖机器人",
        "environment" to "环境传感器",
        "battery" to "电池",
        "speaker" to "音箱",
        "television" to "电视",
        "indicator-light" to "指示灯",
        "physical-controls-locked" to "童锁",
    )

    fun fetchAndParse(
        context: Context,
        urn: String,
        forceRefresh: Boolean = false,
    ): Result<MijiaDeviceSpec> = runCatching {
        require(urn.isNotBlank()) { "设备规格 URN 为空" }
        if (!forceRefresh) {
            memoryCache[urn]?.let { return@runCatching it }
            MijiaStore.loadSpecCache(context, urn)?.let { cached ->
                memoryCache[urn] = cached
                return@runCatching cached
            }
        }
        val encodedUrn = URLEncoder.encode(urn, "UTF-8")
        val conn = (URL("$SPEC_INSTANCE_URL?type=$encodedUrn").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ha_xiaomi_home/0.4.5 Android")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            check(code == 200 && body.isNotBlank()) { "拉取 MIoT Spec 失败 (HTTP $code)" }
            val json = JSONObject(body)
            val parsed = parseInstanceJson(urn, json)
            memoryCache[urn] = parsed
            MijiaStore.saveSpecCache(context, parsed)
            parsed
        } finally {
            conn.disconnect()
        }
    }

    fun parseInstanceJson(urn: String, instance: JSONObject): MijiaDeviceSpec {
        val urnParts = urn.split(":")
        val deviceShortName = urnParts.getOrNull(3).orEmpty().ifBlank { "device" }
        val deviceDesc = instance.optString("description").ifBlank { deviceShortName }
        val servicesArr = instance.optJSONArray("services") ?: JSONArray()

        val props = mutableListOf<MijiaPropSpec>()
        val actions = mutableListOf<MijiaActionSpec>()

        for (sIdx in 0 until servicesArr.length()) {
            val srvObj = servicesArr.optJSONObject(sIdx) ?: continue
            val siid = srvObj.optInt("iid", -1)
            val srvType = srvObj.optString("type")
            if (siid <= 0 || srvType.isBlank()) continue
            val srvName = srvType.split(":").getOrNull(3).orEmpty()
            if (srvName == "device-information") continue
            val rawSrvDesc = srvObj.optString("description").ifBlank { srvName }
            val srvDesc = zhServiceTranslations[srvName.lowercase()] ?: rawSrvDesc

            val propsArr = srvObj.optJSONArray("properties") ?: JSONArray()
            for (pIdx in 0 until propsArr.length()) {
                val pObj = propsArr.optJSONObject(pIdx) ?: continue
                val piid = pObj.optInt("iid", -1)
                val pType = pObj.optString("type")
                val format = pObj.optString("format")
                val accessArr = pObj.optJSONArray("access") ?: JSONArray()
                if (piid <= 0 || pType.isBlank() || format.isBlank()) continue
                val access = (0 until accessArr.length()).mapNotNull {
                    accessArr.optString(it).takeIf(String::isNotBlank)
                }
                if (access.isEmpty()) continue
                val pName = pType.split(":").getOrNull(3).orEmpty()
                val rawDesc = pObj.optString("description").ifBlank { pName }
                val zhDesc = translateDesc(pName, rawDesc)
                val unit = pObj.optString("unit").takeIf { it != "none" }.orEmpty()

                val valueRange = pObj.optJSONArray("value-range")?.let { rArr ->
                    if (rArr.length() >= 3) {
                        MijiaValueRange(
                            min = rArr.optDouble(0, 0.0),
                            max = rArr.optDouble(1, 100.0),
                            step = rArr.optDouble(2, 1.0).takeIf { it > 0.0 } ?: 1.0,
                        )
                    } else null
                }

                val valueList = pObj.optJSONArray("value-list")?.let { vArr ->
                    (0 until vArr.length()).mapNotNull { vIdx ->
                        val vObj = vArr.optJSONObject(vIdx) ?: return@mapNotNull null
                        val vVal = vObj.opt("value") ?: return@mapNotNull null
                        val vDesc = vObj.optString("description").ifBlank { vVal.toString() }
                        MijiaValueItem(value = vVal, description = translateDesc(vDesc, vDesc))
                    }
                }.orEmpty()

                props.add(
                    MijiaPropSpec(
                        siid = siid,
                        piid = piid,
                        serviceName = srvName,
                        serviceDesc = srvDesc,
                        name = pName,
                        description = zhDesc,
                        format = format,
                        access = access,
                        unit = unit,
                        valueRange = valueRange,
                        valueList = valueList,
                    ),
                )
            }

            val actionsArr = srvObj.optJSONArray("actions") ?: JSONArray()
            for (aIdx in 0 until actionsArr.length()) {
                val aObj = actionsArr.optJSONObject(aIdx) ?: continue
                val aiid = aObj.optInt("iid", -1)
                val aType = aObj.optString("type")
                if (aiid <= 0 || aType.isBlank()) continue
                val aName = aType.split(":").getOrNull(3).orEmpty()
                val rawDesc = aObj.optString("description").ifBlank { aName }
                val zhDesc = translateDesc(aName, rawDesc)
                val inArr = aObj.optJSONArray("in") ?: JSONArray()
                val inPiids = (0 until inArr.length()).mapNotNull { idx ->
                    val v = inArr.optInt(idx, -1)
                    v.takeIf { it > 0 }
                }
                actions.add(
                    MijiaActionSpec(
                        siid = siid,
                        aiid = aiid,
                        serviceName = srvName,
                        name = aName,
                        description = zhDesc,
                        inPiids = inPiids,
                    ),
                )
            }
        }

        return MijiaDeviceSpec(
            urn = urn,
            deviceName = deviceShortName,
            description = zhServiceTranslations[deviceShortName.lowercase()] ?: deviceDesc,
            properties = props,
            actions = actions,
        )
    }

    private fun translateDesc(name: String, rawDesc: String): String {
        val key = name.trim().lowercase()
        zhNameTranslations[key]?.let { return it }
        val descKey = rawDesc.trim().lowercase().replace(" ", "-")
        zhNameTranslations[descKey]?.let { return it }
        return rawDesc.ifBlank { name }
    }

    fun toCacheJson(spec: MijiaDeviceSpec): JSONObject =
        JSONObject()
            .put("urn", spec.urn)
            .put("deviceName", spec.deviceName)
            .put("description", spec.description)
            .put(
                "properties",
                JSONArray().apply {
                    spec.properties.forEach { p ->
                        put(
                            JSONObject()
                                .put("siid", p.siid)
                                .put("piid", p.piid)
                                .put("serviceName", p.serviceName)
                                .put("serviceDesc", p.serviceDesc)
                                .put("name", p.name)
                                .put("description", p.description)
                                .put("format", p.format)
                                .put("access", JSONArray(p.access))
                                .put("unit", p.unit)
                                .apply {
                                    p.valueRange?.let { r ->
                                        put("valueRange", JSONArray().put(r.min).put(r.max).put(r.step))
                                    }
                                    if (p.valueList.isNotEmpty()) {
                                        put(
                                            "valueList",
                                            JSONArray().apply {
                                                p.valueList.forEach { item ->
                                                    put(
                                                        JSONObject()
                                                            .put("value", item.value)
                                                            .put("description", item.description),
                                                    )
                                                }
                                            },
                                        )
                                    }
                                },
                        )
                    }
                },
            )
            .put(
                "actions",
                JSONArray().apply {
                    spec.actions.forEach { a ->
                        put(
                            JSONObject()
                                .put("siid", a.siid)
                                .put("aiid", a.aiid)
                                .put("serviceName", a.serviceName)
                                .put("name", a.name)
                                .put("description", a.description)
                                .put("inPiids", JSONArray(a.inPiids)),
                        )
                    }
                },
            )

    fun fromCacheJson(json: JSONObject): MijiaDeviceSpec {
        val propsArr = json.optJSONArray("properties") ?: JSONArray()
        val props = (0 until propsArr.length()).mapNotNull { idx ->
            val p = propsArr.optJSONObject(idx) ?: return@mapNotNull null
            val accessArr = p.optJSONArray("access") ?: JSONArray()
            val access = (0 until accessArr.length()).mapNotNull { accessArr.optString(it).takeIf(String::isNotBlank) }
            val range = p.optJSONArray("valueRange")?.let { r ->
                if (r.length() >= 3) MijiaValueRange(r.optDouble(0), r.optDouble(1), r.optDouble(2, 1.0)) else null
            }
            val vList = p.optJSONArray("valueList")?.let { vArr ->
                (0 until vArr.length()).mapNotNull { vIdx ->
                    val vObj = vArr.optJSONObject(vIdx) ?: return@mapNotNull null
                    val v = vObj.opt("value") ?: return@mapNotNull null
                    MijiaValueItem(value = v, description = vObj.optString("description"))
                }
            }.orEmpty()
            MijiaPropSpec(
                siid = p.optInt("siid"),
                piid = p.optInt("piid"),
                serviceName = p.optString("serviceName"),
                serviceDesc = p.optString("serviceDesc"),
                name = p.optString("name"),
                description = p.optString("description"),
                format = p.optString("format"),
                access = access,
                unit = p.optString("unit"),
                valueRange = range,
                valueList = vList,
            )
        }
        val actionsArr = json.optJSONArray("actions") ?: JSONArray()
        val actions = (0 until actionsArr.length()).mapNotNull { idx ->
            val a = actionsArr.optJSONObject(idx) ?: return@mapNotNull null
            val inArr = a.optJSONArray("inPiids") ?: JSONArray()
            val inPiids = (0 until inArr.length()).mapNotNull { inArr.optInt(it, -1).takeIf { v -> v > 0 } }
            MijiaActionSpec(
                siid = a.optInt("siid"),
                aiid = a.optInt("aiid"),
                serviceName = a.optString("serviceName"),
                name = a.optString("name"),
                description = a.optString("description"),
                inPiids = inPiids,
            )
        }
        return MijiaDeviceSpec(
            urn = json.optString("urn"),
            deviceName = json.optString("deviceName"),
            description = json.optString("description"),
            properties = props,
            actions = actions,
        )
    }
}
