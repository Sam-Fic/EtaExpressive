package io.github.mangi.eta.agent.mijia

import android.content.Context
import kotlin.math.roundToInt

internal object MijiaVoiceMatcher {

    data class VoiceControlResult(
        val matched: Boolean,
        val ok: Boolean = false,
        val replyText: String = "",
        val deviceName: String = "",
    )

    private val EXPLICIT_PREFIXES = listOf("/米家 ", "/米家", "米家 ", "米家：", "米家:", "/mijia ")

    private val TURN_ON_WORDS = listOf("打开", "开启", "启动", "开一下", "开下", "亮起")
    private val TURN_OFF_WORDS = listOf("关闭", "关掉", "关上", "关了", "关一下", "关下", "熄灭")
    private val TOGGLE_WORDS = listOf("切换", "反转")
    private val QUERY_WORDS = listOf("状态", "怎么样", "多少度", "湿度多少", "温度多少", "开了吗", "关了吗", "在线吗")
    private val ADJUST_WORDS = listOf("调到", "设为", "设置", "调成", "改成", "调至", "调高", "调低", "调亮", "调暗", "亮度", "温度", "色温", "风速", "音量", "开合度")

    /**
     * 仅做零网络开销的本地语义与设备列表匹配判断，供小布 UI 主线程毫秒级判定是否拦截请求。
     */
    fun peekMatch(context: Context, rawText: String): VoiceControlResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return VoiceControlResult(matched = false)
        if (!MijiaStore.isEnabled(context)) return VoiceControlResult(matched = false)

        val explicitPrefix = EXPLICIT_PREFIXES.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        val isExplicit = explicitPrefix != null
        if (!isExplicit && !MijiaStore.isBreenoDirectEnabled(context)) {
            return VoiceControlResult(matched = false)
        }
        val commandText = if (explicitPrefix != null) {
            trimmed.substring(explicitPrefix.length).trim()
        } else {
            trimmed
        }
        if (commandText.isEmpty()) return VoiceControlResult(matched = false)
        if (isExplicit) {
            return VoiceControlResult(matched = true, deviceName = commandText)
        }
        val auth = MijiaStore.loadAuth(context)
        if (!auth.isAuthorized) return VoiceControlResult(matched = false)
        val devices = MijiaStore.loadDevices(context)
        if (devices.isEmpty()) return VoiceControlResult(matched = false)

        val hasActionIntent = TURN_ON_WORDS.any { commandText.contains(it) } ||
            TURN_OFF_WORDS.any { commandText.contains(it) } ||
            TOGGLE_WORDS.any { commandText.contains(it) } ||
            QUERY_WORDS.any { commandText.contains(it) } ||
            ADJUST_WORDS.any { commandText.contains(it) }
        if (!hasActionIntent) return VoiceControlResult(matched = false)

        if (commandText.contains("所有") || commandText.contains("全部") || commandText.contains("全屋")) {
            val batchTarget = matchBatchDevices(commandText, devices)
            if (batchTarget.isNotEmpty()) {
                return VoiceControlResult(matched = true, deviceName = batchTarget.first().name)
            }
        }
        val targetDevice = matchBestDevice(commandText, devices, isExplicit = false)
            ?: return VoiceControlResult(matched = false)
        return VoiceControlResult(matched = true, deviceName = targetDevice.name)
    }

    fun maybeHandleVoiceQuery(context: Context, rawText: String): VoiceControlResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return VoiceControlResult(matched = false)
        if (!MijiaStore.isEnabled(context)) return VoiceControlResult(matched = false)

        val explicitPrefix = EXPLICIT_PREFIXES.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        val isExplicit = explicitPrefix != null
        if (!isExplicit && !MijiaStore.isBreenoDirectEnabled(context)) {
            return VoiceControlResult(matched = false)
        }

        val commandText = if (explicitPrefix != null) {
            trimmed.substring(explicitPrefix.length).trim()
        } else {
            trimmed
        }
        if (commandText.isEmpty()) return VoiceControlResult(matched = false)

        val auth = MijiaStore.loadAuth(context)
        if (!auth.isAuthorized) {
            return if (isExplicit) {
                VoiceControlResult(
                    matched = true,
                    ok = false,
                    replyText = "米家直连尚未登录，请先打开 Eta -> 设置 -> 米家直连，完成小米账号 OAuth 授权。",
                )
            } else {
                VoiceControlResult(matched = false)
            }
        }

        var devices = MijiaStore.loadDevices(context)
        if (devices.isEmpty() && isExplicit) {
            MijiaCloudClient.syncHomesAndDevices(context).onSuccess { (_, synced) ->
                devices = synced
            }
        }
        if (devices.isEmpty()) {
            return if (isExplicit) {
                VoiceControlResult(
                    matched = true,
                    ok = false,
                    replyText = "当前未发现米家设备，请在 Eta「米家直连」设置页点击同步设备。",
                )
            } else {
                VoiceControlResult(matched = false)
            }
        }

        // 显式查询设备列表
        if (isExplicit && (commandText == "列表" || commandText == "设备" || commandText == "设备列表" || commandText == "刷新" || commandText == "同步")) {
            if (commandText == "刷新" || commandText == "同步") {
                val syncRes = MijiaCloudClient.syncHomesAndDevices(context)
                return syncRes.fold(
                    onSuccess = { (homes, synced) ->
                        VoiceControlResult(
                            matched = true,
                            ok = true,
                            replyText = "已同步 ${homes.size} 个家庭、${synced.size} 台米家设备。",
                        )
                    },
                    onFailure = { err ->
                        VoiceControlResult(
                            matched = true,
                            ok = false,
                            replyText = "同步米家设备失败：${err.message}",
                        )
                    },
                )
            }
            val onlineCount = devices.count { it.online }
            val preview = devices.take(15).joinToString("\n") { d ->
                "- ${d.roomName.ifBlank { "默认房间" }} · **${d.name}** (${if (d.online) "在线" else "离线"})"
            }
            return VoiceControlResult(
                matched = true,
                ok = true,
                replyText = "共缓存 ${devices.size} 台米家设备（$onlineCount 台在线）：\n$preview",
            )
        }

        // 检查是否有控制或查询意图
        val hasActionIntent = isExplicit ||
            TURN_ON_WORDS.any { commandText.contains(it) } ||
            TURN_OFF_WORDS.any { commandText.contains(it) } ||
            TOGGLE_WORDS.any { commandText.contains(it) } ||
            QUERY_WORDS.any { commandText.contains(it) } ||
            ADJUST_WORDS.any { commandText.contains(it) }

        if (!hasActionIntent) {
            return VoiceControlResult(matched = false)
        }

        // 批量控制检测（如“把所有灯关掉” / “关掉客厅所有灯”）
        if (commandText.contains("所有") || commandText.contains("全部") || commandText.contains("全屋")) {
            val batchTarget = matchBatchDevices(commandText, devices)
            if (batchTarget.isNotEmpty()) {
                val targetOn = when {
                    TURN_ON_WORDS.any { commandText.contains(it) } -> true
                    TURN_OFF_WORDS.any { commandText.contains(it) } -> false
                    else -> null
                }
                if (targetOn != null) {
                    var successCount = 0
                    val names = mutableListOf<String>()
                    for (dev in batchTarget.filter { it.online }) {
                        if (MijiaCloudClient.toggleDevicePower(context, dev, targetOn).isSuccess) {
                            successCount++
                            names.add(dev.name)
                        }
                    }
                    val actionWord = if (targetOn) "开启" else "关闭"
                    return if (successCount > 0) {
                        VoiceControlResult(
                            matched = true,
                            ok = true,
                            replyText = "已为您$actionWord $successCount 台米家设备：${names.joinToString("、")}",
                            deviceName = names.firstOrNull().orEmpty(),
                        )
                    } else {
                        VoiceControlResult(
                            matched = true,
                            ok = false,
                            replyText = "未能成功${actionWord}匹配的设备（共匹配 ${batchTarget.size} 台，可能离线或不支持直接开关）。",
                        )
                    }
                }
            }
        }

        val targetDevice = matchBestDevice(commandText, devices, isExplicit)
            ?: return if (isExplicit) {
                VoiceControlResult(
                    matched = true,
                    ok = false,
                    replyText = "未在米家设备列表中找到匹配的设备：「$commandText」",
                )
            } else {
                VoiceControlResult(matched = false)
            }

        return executeOnDevice(context, targetDevice, commandText)
    }

    private fun matchBatchDevices(text: String, devices: List<MijiaDevice>): List<MijiaDevice> {
        val matchedRoom = devices.map { it.roomName }.filter { it.isNotBlank() && text.contains(it) }
            .maxByOrNull { it.length }
        val categoryFilter: (MijiaDevice) -> Boolean = when {
            text.contains("灯") -> { d -> d.name.contains("灯") || d.category.contains("light", true) }
            text.contains("空调") -> { d -> d.name.contains("空调") || d.category.contains("air-conditioner", true) }
            text.contains("风扇") -> { d -> d.name.contains("风扇") || d.category.contains("fan", true) }
            text.contains("窗帘") -> { d -> d.name.contains("窗帘") || d.category.contains("curtain", true) }
            else -> { _ -> false }
        }
        return devices.filter { d ->
            (matchedRoom == null || d.roomName == matchedRoom) && categoryFilter(d)
        }
    }

    private fun matchBestDevice(
        text: String,
        devices: List<MijiaDevice>,
        isExplicit: Boolean,
    ): MijiaDevice? {
        val normText = normalizeText(text)
        var bestDevice: MijiaDevice? = null
        var bestScore = 0

        for (dev in devices) {
            val normName = normalizeText(dev.name)
            val normRoom = normalizeText(dev.roomName)
            var score = 0

            // 1. 完整设备名称包含在语音文本中（最强匹配）
            if (normName.length >= 2 && normText.contains(normName)) {
                score += 100 + normName.length * 10
            }
            // 2. 房间名 + 设备名组合匹配
            if (normRoom.length >= 2 && normText.contains(normRoom)) {
                score += 40
                val strippedDevName = normName.removePrefix(normRoom)
                if (strippedDevName.length >= 1 && normText.contains(strippedDevName)) {
                    score += 80 + strippedDevName.length * 10
                } else {
                    // 房间名命中 + 设备类型词命中（如 客厅 + 灯/空调/窗帘/风扇）
                    val typeHits = listOf("灯", "吸顶灯", "台灯", "灯带", "筒灯", "射灯", "空调", "风扇", "窗帘", "净化器", "加湿器", "除湿机", "电视", "音箱", "插座", "开关", "扫地机", "热水器")
                    for (kw in typeHits) {
                        if (normText.contains(kw) && normName.contains(kw)) {
                            score += 75 + kw.length * 5
                        }
                    }
                }
            }
            // 3. 显式 `/米家` 模式下允许更宽松的子串匹配
            if (isExplicit && score < 80) {
                val commonChars = normName.count { normText.contains(it) }
                if (commonChars >= 2 && commonChars * 2 >= normName.length) {
                    score = maxOf(score, 65 + commonChars * 5)
                }
            }

            if (score > 0 && dev.online) {
                score += 5
            }

            if (score > bestScore) {
                bestScore = score
                bestDevice = dev
            }
        }

        val threshold = if (isExplicit) 60 else 100
        return if (bestScore >= threshold) bestDevice else null
    }

    private fun executeOnDevice(
        context: Context,
        device: MijiaDevice,
        commandText: String,
    ): VoiceControlResult {
        val spec = MijiaSpecParser.fetchAndParse(context, device.urn).getOrElse { err ->
            return VoiceControlResult(
                matched = true,
                ok = false,
                replyText = "获取「${device.name}」规格失败：${err.message}",
                deviceName = device.name,
            )
        }

        // 1. 检查是否是包含数值的参数调节（如“调到 26 度”、“亮度设为 80%”、“色温 4000”）
        val numberRegex = Regex("(-?\\d+(?:\\.\\d+)?)")
        val numberMatch = numberRegex.find(commandText)
        val hasAdjustKeyword = ADJUST_WORDS.any { commandText.contains(it) } ||
            commandText.contains("度") || commandText.contains("%") || commandText.contains("档")

        if (numberMatch != null && hasAdjustKeyword) {
            val numVal = numberMatch.groupValues[1].toDoubleOrNull()
            if (numVal != null) {
                val targetProp = matchNumericWritableProp(commandText, spec)
                if (targetProp != null) {
                    val coerced = targetProp.valueRange?.let { r ->
                        numVal.coerceIn(r.min, r.max)
                    } ?: numVal
                    val finalValue: Any = if (targetProp.format.contains("float", ignoreCase = true)) {
                        coerced
                    } else {
                        coerced.roundToInt()
                    }
                    // 如果是调亮度/温度且设备是关着的，顺便打开电源
                    spec.primaryPowerProp(preferredSiid = device.splitSiid)?.let { pProp ->
                        if (pProp.siid != targetProp.siid || pProp.piid != targetProp.piid) {
                            runCatching { MijiaCloudClient.setProp(context, device.did, pProp.siid, pProp.piid, true) }
                        }
                    }
                    val setRes = MijiaCloudClient.setProp(context, device.did, targetProp.siid, targetProp.piid, finalValue)
                    return setRes.fold(
                        onSuccess = {
                            val unitStr = when (targetProp.unit.lowercase()) {
                                "percentage", "percent" -> "%"
                                "celsius" -> "°C"
                                "kelvin" -> "K"
                                else -> ""
                            }
                            VoiceControlResult(
                                matched = true,
                                ok = true,
                                replyText = "已将「${device.name}」的${targetProp.description.ifBlank { targetProp.name }}调至 $finalValue$unitStr",
                                deviceName = device.name,
                            )
                        },
                        onFailure = { err ->
                            VoiceControlResult(
                                matched = true,
                                ok = false,
                                replyText = "调节「${device.name}」失败：${err.message}",
                                deviceName = device.name,
                            )
                        },
                    )
                }
            }
        }

        // 2. 检查开关意图
        val wantOn = TURN_ON_WORDS.any { commandText.contains(it) }
        val wantOff = TURN_OFF_WORDS.any { commandText.contains(it) }
        val wantToggle = TOGGLE_WORDS.any { commandText.contains(it) }

        if (wantOn || wantOff || wantToggle) {
            val targetOn = when {
                wantOn && !wantOff -> true
                wantOff && !wantOn -> false
                else -> null
            }
            val toggleRes = MijiaCloudClient.toggleDevicePower(context, device, targetOn)
            return toggleRes.fold(
                onSuccess = { newState ->
                    val stateLabel = if (newState) "开启" else "关闭"
                    VoiceControlResult(
                        matched = true,
                        ok = true,
                        replyText = "已为您$stateLabel「${device.roomName.let { if (it.isNotBlank() && !device.name.startsWith(it)) "$it·" else "" }}${device.name}」",
                        deviceName = device.name,
                    )
                },
                onFailure = { err ->
                    VoiceControlResult(
                        matched = true,
                        ok = false,
                        replyText = "控制「${device.name}」失败：${err.message ?: err.javaClass.simpleName}",
                        deviceName = device.name,
                    )
                },
            )
        }

        // 3. 检查枚举模式或无参 Action（如“睡眠模式”、“开始清扫”、“回充”）
        for (prop in spec.writableProps()) {
            for (item in prop.valueList) {
                if (item.description.isNotBlank() && commandText.contains(item.description, ignoreCase = true)) {
                    val res = MijiaCloudClient.setProp(context, device.did, prop.siid, prop.piid, item.value)
                    return res.fold(
                        onSuccess = {
                            VoiceControlResult(
                                matched = true,
                                ok = true,
                                replyText = "已将「${device.name}」的${prop.description}设为「${item.description}」",
                                deviceName = device.name,
                            )
                        },
                        onFailure = { err ->
                            VoiceControlResult(
                                matched = true,
                                ok = false,
                                replyText = "设置「${device.name}」模式失败：${err.message}",
                                deviceName = device.name,
                            )
                        },
                    )
                }
            }
        }

        for (action in spec.actions.filter { it.inPiids.isEmpty() }) {
            if (action.description.isNotBlank() && commandText.contains(action.description, ignoreCase = true)) {
                val res = MijiaCloudClient.callAction(context, device.did, action.siid, action.aiid)
                return res.fold(
                    onSuccess = {
                        VoiceControlResult(
                            matched = true,
                            ok = true,
                            replyText = "已对「${device.name}」执行「${action.description}」",
                            deviceName = device.name,
                        )
                    },
                    onFailure = { err ->
                        VoiceControlResult(
                            matched = true,
                            ok = false,
                            replyText = "执行「${action.description}」失败：${err.message}",
                            deviceName = device.name,
                        )
                    },
                )
            }
        }

        // 4. 默认读取并播报设备状态
        val readable = spec.readableProps().take(12)
        if (readable.isEmpty()) {
            return VoiceControlResult(
                matched = true,
                ok = true,
                replyText = "「${device.name}」当前${if (device.online) "在线" else "离线"}。",
                deviceName = device.name,
            )
        }
        val propMap = MijiaCloudClient.getProps(
            context,
            readable.map { Triple(device.did, it.siid, it.piid) },
        ).getOrElse { err ->
            return VoiceControlResult(
                matched = true,
                ok = false,
                replyText = "读取「${device.name}」状态失败：${err.message}",
                deviceName = device.name,
            )
        }
        val summaryParts = readable.mapNotNull { p ->
            val v = propMap["${p.siid}.${p.piid}"] ?: return@mapNotNull null
            val formatted = when {
                p.isBool -> if (v == true || v.toString() == "1") "开启" else "关闭"
                p.valueList.isNotEmpty() -> p.valueList.firstOrNull { it.value.toString() == v.toString() }?.description ?: v.toString()
                p.unit.equals("percentage", true) -> "$v%"
                p.unit.equals("celsius", true) -> "${v}°C"
                else -> v.toString()
            }
            "${p.description.ifBlank { p.name }}：$formatted"
        }
        val body = if (summaryParts.isEmpty()) {
            "状态：${if (device.online) "在线" else "离线"}"
        } else {
            summaryParts.joinToString("，")
        }
        return VoiceControlResult(
            matched = true,
            ok = true,
            replyText = "「${device.name}」（${device.roomName.ifBlank { "默认房间" }}）当前状态：$body",
            deviceName = device.name,
        )
    }

    private fun matchNumericWritableProp(text: String, spec: MijiaDeviceSpec): MijiaPropSpec? {
        val candidates = spec.writableProps().filter { !it.isBool && (it.valueRange != null || it.format.contains("int") || it.format.contains("float")) }
        if (candidates.isEmpty()) return null
        return when {
            text.contains("色温") || text.contains("K", ignoreCase = true) ->
                candidates.firstOrNull { it.name.contains("color-temperature", true) || it.description.contains("色温") }
            text.contains("温度") || text.contains("度") ->
                candidates.firstOrNull { it.name.contains("temperature", true) || it.description.contains("温度") }
            text.contains("亮度") || text.contains("亮") || text.contains("暗") ->
                candidates.firstOrNull { it.name.contains("brightness", true) || it.description.contains("亮度") }
            text.contains("风速") || text.contains("档") ->
                candidates.firstOrNull { it.name.contains("fan-level", true) || it.description.contains("风速") }
            text.contains("湿度") ->
                candidates.firstOrNull { it.name.contains("humidity", true) || it.description.contains("湿度") }
            text.contains("音量") || text.contains("声音") ->
                candidates.firstOrNull { it.name.contains("volume", true) || it.description.contains("音量") }
            text.contains("窗帘") || text.contains("开合") ->
                candidates.firstOrNull { it.name.contains("position", true) || it.description.contains("开合") }
            else -> candidates.firstOrNull()
        } ?: candidates.firstOrNull()
    }

    private fun normalizeText(s: String): String =
        s.lowercase()
            .replace(Regex("[\\s·,，。！!？?的把将给一下儿]"), "")
}
