package io.github.mangi.eta.agent.mijia

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

internal object MijiaCloudClient {
    const val OAUTH2_CLIENT_ID = "2882303761520251711"
    const val OAUTH2_CLIENT_ID_LONG = 2882303761520251711L
    const val OAUTH2_AUTH_URL = "https://account.xiaomi.com/oauth2/authorize"
    const val OAUTH_REDIRECT_URL = "http://homeassistant.local:8123"
    const val DEFAULT_OAUTH2_API_HOST = "ha.api.io.mi.com"
    private const val INTEGRATION_VERSION = "0.4.5"
    private const val TOKEN_EXPIRES_RATIO = 0.7

    private val UNSUPPORTED_MODELS = setOf(
        "chuangmi.ir.v2",
        "era.airp.cwb03",
        "hmpace.motion.v6nfc",
        "k0918.toothbrush.t700",
    )

    private fun apiHost(cloudServer: String): String {
        val srv = cloudServer.trim().lowercase().ifBlank { "cn" }
        return if (srv == "cn") DEFAULT_OAUTH2_API_HOST else "$srv.$DEFAULT_OAUTH2_API_HOST"
    }

    private fun userAgent(context: Context): String {
        val devId = MijiaStore.deviceId(context)
        return "ha_xiaomi_home/$INTEGRATION_VERSION Android client/$devId"
    }

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun buildAuthorizeUrl(context: Context, skipConfirm: Boolean = false): String {
        val devId = MijiaStore.deviceId(context)
        val state = sha1Hex("d=$devId")
        return Uri.parse(OAUTH2_AUTH_URL).buildUpon()
            .appendQueryParameter("redirect_uri", OAUTH_REDIRECT_URL)
            .appendQueryParameter("client_id", OAUTH2_CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("device_id", devId)
            .appendQueryParameter("state", state)
            .appendQueryParameter("skip_confirm", if (skipConfirm) "true" else "false")
            .build()
            .toString()
    }

    fun parseRedirectCode(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        if (!trimmed.startsWith(OAUTH_REDIRECT_URL, ignoreCase = true)) return null
        return runCatching {
            Uri.parse(trimmed).getQueryParameter("code")?.trim()?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    @Synchronized
    fun exchangeCodeForToken(context: Context, code: String): Result<MijiaAuthState> = runCatching {
        val cleanCode = code.trim()
        require(cleanCode.isNotEmpty()) { "授权码为空" }
        if (!MijiaStore.markAuthCodeConsumed(cleanCode)) {
            val existing = MijiaStore.loadAuth(context)
            if (existing.isAuthorized) return@runCatching existing
            error("该一次性授权码已使用，请重新登录")
        }

        val currentAuth = MijiaStore.loadAuth(context)
        val cloudServer = currentAuth.cloudServer.ifBlank { "cn" }
        val devId = MijiaStore.deviceId(context)
        val dataJson = JSONObject()
            .put("client_id", OAUTH2_CLIENT_ID_LONG)
            .put("redirect_uri", OAUTH_REDIRECT_URL)
            .put("code", cleanCode)
            .put("device_id", devId)

        val tokenObj = requestOAuthToken(context, cloudServer, dataJson)
        val accessToken = tokenObj.optString("access_token")
        val refreshToken = tokenObj.optString("refresh_token")
        val expiresIn = tokenObj.optLong("expires_in", 7200L)
        check(accessToken.isNotBlank() && refreshToken.isNotBlank()) { "OAuth 响应缺少 access_token" }

        val expiresAtMillis = System.currentTimeMillis() + (expiresIn * 1000L * TOKEN_EXPIRES_RATIO).toLong()
        val nickname = runCatching { fetchUserNickname(context, accessToken) }.getOrNull().orEmpty()

        val newAuth = MijiaAuthState(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = expiresAtMillis,
            uid = currentAuth.uid,
            nickname = nickname,
            cloudServer = cloudServer,
            lastError = "",
        )
        MijiaStore.saveAuth(context, newAuth)
        newAuth
    }.onFailure { err ->
        MijiaStore.recordAuthError(context, err.message ?: "OAuth 换取 Token 失败")
    }

    @Synchronized
    fun ensureValidAccessToken(context: Context, forceRefresh: Boolean = false): Result<String> = runCatching {
        val auth = MijiaStore.loadAuth(context)
        check(auth.isAuthorized) { "米家账号尚未授权登录，请先在 Eta 设置 -> 米家直连中完成小米账号 OAuth 授权" }
        val now = System.currentTimeMillis()
        if (!forceRefresh && auth.expiresAtMillis > now + 60_000L) {
            return@runCatching auth.accessToken
        }

        val dataJson = JSONObject()
            .put("client_id", OAUTH2_CLIENT_ID_LONG)
            .put("redirect_uri", OAUTH_REDIRECT_URL)
            .put("refresh_token", auth.refreshToken)

        val tokenObj = try {
            requestOAuthToken(context, auth.cloudServer, dataJson)
        } catch (e: Exception) {
            if (e.message?.contains("401") == true) {
                MijiaStore.clearAuth(context, "米家授权已过期 (401)，请重新登录")
            }
            throw e
        }
        val accessToken = tokenObj.optString("access_token")
        val refreshToken = tokenObj.optString("refresh_token").ifBlank { auth.refreshToken }
        val expiresIn = tokenObj.optLong("expires_in", 7200L)
        check(accessToken.isNotBlank()) { "刷新米家 Token 失败" }

        val updated = auth.copy(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = now + (expiresIn * 1000L * TOKEN_EXPIRES_RATIO).toLong(),
            lastError = "",
        )
        MijiaStore.saveAuth(context, updated)
        accessToken
    }

    private fun requestOAuthToken(
        context: Context,
        cloudServer: String,
        dataJson: JSONObject,
    ): JSONObject {
        val host = apiHost(cloudServer)
        val encodedData = URLEncoder.encode(dataJson.toString(), "UTF-8")
        val url = URL("https://$host/app/v2/ha/oauth/get_token?data=$encodedData")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", userAgent(context))
        }
        try {
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (status == 401) {
                error("OAuth 未授权或已过期 (HTTP 401)")
            }
            check(status == 200 && body.isNotBlank()) { "OAuth 请求失败 (HTTP $status): ${body.take(180)}" }
            val json = JSONObject(body)
            val code = json.optInt("code", -1)
            check(code == 0) {
                "OAuth 响应错误 (code=$code): ${json.optString("message").ifBlank { body.take(160) }}"
            }
            return json.optJSONObject("result") ?: error("OAuth 响应缺少 result 字段")
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchUserNickname(context: Context, accessToken: String): String {
        val url = Uri.parse("https://open.account.xiaomi.com/user/profile").buildUpon()
            .appendQueryParameter("clientId", OAUTH2_CLIENT_ID)
            .appendQueryParameter("token", accessToken)
            .build()
            .toString()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", userAgent(context))
        }
        try {
            if (conn.responseCode != 200) return ""
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) return ""
            return json.optJSONObject("data")?.optString("miliaoNick").orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    private fun mihomeApiPost(
        context: Context,
        urlPath: String,
        payload: JSONObject,
        timeoutMs: Int = 15_000,
        retriedAfterRefresh: Boolean = false,
    ): JSONObject {
        val token = ensureValidAccessToken(context).getOrThrow()
        val auth = MijiaStore.loadAuth(context)
        val host = apiHost(auth.cloudServer)
        val conn = (URL("https://$host$urlPath").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Host", host)
            setRequestProperty("X-Client-BizId", "haapi")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer$token")
            setRequestProperty("X-Client-AppId", OAUTH2_CLIENT_ID)
            setRequestProperty("User-Agent", userAgent(context))
        }
        try {
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (status == 401) {
                if (!retriedAfterRefresh) {
                    val refreshed = ensureValidAccessToken(context, forceRefresh = true)
                    if (refreshed.isSuccess) {
                        return mihomeApiPost(context, urlPath, payload, timeoutMs, retriedAfterRefresh = true)
                    }
                }
                MijiaStore.clearAuth(context, "米家登录状态已失效 (401)，请重新登录")
                error("米家登录已失效 (401)，请重新授权")
            }
            check(status == 200 && body.isNotBlank()) {
                "米家接口请求失败 ($urlPath HTTP $status): ${body.take(160)}"
            }
            val json = JSONObject(body)
            val code = json.optInt("code", -1)
            check(code == 0) {
                "米家接口返回错误 ($urlPath code=$code): ${json.optString("message").ifBlank { body.take(160) }}"
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    fun syncHomesAndDevices(
        context: Context,
    ): Result<Pair<List<MijiaHomeInfo>, List<MijiaDevice>>> = runCatching {
        val homeRes = mihomeApiPost(
            context = context,
            urlPath = "/app/v2/homeroom/gethome",
            payload = JSONObject()
                .put("limit", 150)
                .put("fetch_share", true)
                .put("fetch_share_dev", true)
                .put("plat_form", 0)
                .put("app_ver", 9),
        ).optJSONObject("result") ?: error("gethome 缺少 result")

        val homes = mutableListOf<MijiaHomeInfo>()
        data class DevicePlace(
            val homeId: String,
            val homeName: String,
            val roomId: String,
            val roomName: String,
        )
        val didPlaceMap = LinkedHashMap<String, DevicePlace>()
        var primaryUid = ""

        for (sourceKey in listOf("homelist", "share_home_list")) {
            val arr = homeRes.optJSONArray(sourceKey) ?: continue
            for (i in 0 until arr.length()) {
                val hObj = arr.optJSONObject(i) ?: continue
                val homeId = hObj.opt("id")?.toString().orEmpty()
                val homeName = hObj.optString("name").ifBlank { "我的家" }
                val uid = hObj.opt("uid")?.toString().orEmpty()
                if (homeId.isBlank()) continue
                if (primaryUid.isBlank() && sourceKey == "homelist" && uid.isNotBlank()) {
                    primaryUid = uid
                }
                val homeDidsArr = hObj.optJSONArray("dids") ?: JSONArray()
                val homeDids = (0 until homeDidsArr.length())
                    .mapNotNull { homeDidsArr.optString(it).takeIf(String::isNotBlank) }
                    .toMutableList()
                homeDids.forEach { did ->
                    didPlaceMap[did] = DevicePlace(
                        homeId = homeId,
                        homeName = homeName,
                        roomId = homeId,
                        roomName = "默认房间",
                    )
                }

                val roomListArr = hObj.optJSONArray("roomlist") ?: JSONArray()
                val rooms = mutableListOf<MijiaRoomInfo>()
                for (rIdx in 0 until roomListArr.length()) {
                    val rObj = roomListArr.optJSONObject(rIdx) ?: continue
                    val roomId = rObj.opt("id")?.toString().orEmpty()
                    val roomName = rObj.optString("name").ifBlank { "未命名房间" }
                    if (roomId.isBlank()) continue
                    val rDidsArr = rObj.optJSONArray("dids") ?: JSONArray()
                    val rDids = (0 until rDidsArr.length())
                        .mapNotNull { rDidsArr.optString(it).takeIf(String::isNotBlank) }
                    rDids.forEach { did ->
                        didPlaceMap[did] = DevicePlace(
                            homeId = homeId,
                            homeName = homeName,
                            roomId = roomId,
                            roomName = roomName,
                        )
                    }
                    rooms.add(MijiaRoomInfo(roomId = roomId, roomName = roomName, dids = rDids))
                }
                homes.add(
                    MijiaHomeInfo(
                        homeId = homeId,
                        homeName = homeName,
                        uid = uid,
                        dids = homeDids,
                        rooms = rooms,
                    ),
                )
            }
        }

        if (primaryUid.isNotBlank()) {
            val auth = MijiaStore.loadAuth(context)
            if (auth.uid != primaryUid) {
                MijiaStore.saveAuth(context, auth.copy(uid = primaryUid))
            }
        }

        val allDids = didPlaceMap.keys.toList()
        val fetchedDevices = LinkedHashMap<String, MijiaDevice>()

        if (allDids.isNotEmpty()) {
            for (chunk in allDids.chunked(150)) {
                fetchDeviceListPage(context, chunk, didPlaceMap, fetchedDevices)
            }
        }
        // 同时拉取一次不限定 dids 的列表（包含独立共享设备）
        runCatching {
            fetchDeviceListPage(context, emptyList(), didPlaceMap, fetchedDevices)
        }

        val deviceList = fetchedDevices.values
            .sortedWith(compareByDescending<MijiaDevice> { it.online }.thenBy { it.roomName }.thenBy { it.name })

        MijiaStore.saveHomesAndDevices(context, homes, deviceList)
        if (MijiaStore.favoriteDids(context).isEmpty() && deviceList.isNotEmpty()) {
            val defaultFavs = deviceList.filter { it.online }.take(8).ifEmpty { deviceList.take(8) }.map { it.did }
            MijiaStore.setFavorites(context, defaultFavs)
        }
        homes to deviceList
    }

    private fun fetchDeviceListPage(
        context: Context,
        dids: List<String>,
        placeMap: Map<String, Any>,
        outMap: MutableMap<String, MijiaDevice>,
        startDid: String? = null,
    ) {
        val req = JSONObject()
            .put("limit", 200)
            .put("get_split_device", true)
            .put("get_third_device", true)
            .put("dids", JSONArray(dids))
        if (!startDid.isNullOrBlank()) {
            req.put("start_did", startDid)
        }
        val res = mihomeApiPost(context, "/app/v2/home/device_list_page", req)
            .optJSONObject("result") ?: return
        val list = res.optJSONArray("list") ?: JSONArray()
        for (i in 0 until list.length()) {
            val dObj = list.optJSONObject(i) ?: continue
            val did = dObj.optString("did")
            val name = dObj.optString("name")
            val urn = dObj.optString("spec_type")
            val model = dObj.optString("model")
            if (did.isBlank() || name.isBlank() || urn.isBlank() || model.isBlank()) continue
            if (did.startsWith("miwifi.")) continue
            if (model in UNSUPPORTED_MODELS) continue

            val online = dObj.optBoolean("isOnline", false)
            val icon = dObj.optString("icon")
            val category = urn.split(":").getOrNull(3).orEmpty()
            val ownerObj = dObj.optJSONObject("owner")
            val place = placeMap[did]
            val homeId: String
            val homeName: String
            val roomId: String
            val roomName: String
            if (place != null) {
                val pClass = place.javaClass
                homeId = pClass.getDeclaredMethod("getHomeId").invoke(place) as? String ?: ""
                homeName = pClass.getDeclaredMethod("getHomeName").invoke(place) as? String ?: ""
                roomId = pClass.getDeclaredMethod("getRoomId").invoke(place) as? String ?: ""
                roomName = pClass.getDeclaredMethod("getRoomName").invoke(place) as? String ?: ""
            } else if (ownerObj != null) {
                homeId = ownerObj.optString("userid", "shared")
                homeName = ownerObj.optString("nickname", "共享家庭").ifBlank { "共享设备" }
                roomId = "shared_device"
                roomName = "共享设备"
            } else {
                homeId = "default"
                homeName = "默认家庭"
                roomId = "default_room"
                roomName = "未分配房间"
            }

            outMap[did] = MijiaDevice(
                did = did,
                name = name,
                model = model,
                urn = urn,
                online = online,
                homeId = homeId,
                homeName = homeName,
                roomId = roomId,
                roomName = roomName,
                icon = icon,
                category = category,
            )
        }
        val hasMore = res.optBoolean("has_more", false)
        val nextStart = res.optString("next_start_did")
        if (hasMore && nextStart.isNotBlank() && nextStart != startDid) {
            fetchDeviceListPage(context, dids, placeMap, outMap, startDid = nextStart)
        }
    }

    /**
     * 读取属性。
     * 返回 Map 的 key 同时包含 `"$did.$siid.$piid"` 和 `"$siid.$piid"` 两种形式，方便单设备/多设备读取。
     * 自动兼容 `.s2` / `.s3` / `.s4` 等多键拆分设备 did。
     */
    fun getProps(
        context: Context,
        params: List<Triple<String, Int, Int>>,
    ): Result<Map<String, Any?>> = runCatching {
        if (params.isEmpty()) return@runCatching emptyMap()
        val resultMap = LinkedHashMap<String, Any?>()
        for (chunk in params.chunked(150)) {
            val arr = JSONArray().apply {
                chunk.forEach { (rawDid, siid, piid) ->
                    val (cloudDid, _) = MijiaDevice.resolveCloudTarget(rawDid, siid)
                    put(JSONObject().put("did", cloudDid).put("siid", siid).put("piid", piid))
                }
            }
            val payload = JSONObject()
                .put("datasource", 1)
                .put("params", arr)
            val resArr = mihomeApiPost(context, "/app/v2/miotspec/prop/get", payload)
                .optJSONArray("result") ?: JSONArray()
            for (i in 0 until resArr.length()) {
                val item = resArr.optJSONObject(i) ?: continue
                val did = item.optString("did")
                val siid = item.optInt("siid", -1)
                val piid = item.optInt("piid", -1)
                if (did.isBlank() || siid <= 0 || piid <= 0) continue
                val value = if (item.has("value") && !item.isNull("value")) item.opt("value") else null
                resultMap["$did.$siid.$piid"] = value
                resultMap["$did.s$siid.$siid.$piid"] = value
                resultMap["$siid.$piid"] = value
            }
        }
        resultMap
    }

    fun setProp(
        context: Context,
        did: String,
        siid: Int,
        piid: Int,
        value: Any,
    ): Result<Int> = runCatching {
        val (cloudDid, effectiveSiid) = MijiaDevice.resolveCloudTarget(did, siid)
        val params = JSONArray().put(
            JSONObject()
                .put("did", cloudDid)
                .put("siid", effectiveSiid)
                .put("piid", piid)
                .put("value", value),
        )
        val resArr = mihomeApiPost(
            context = context,
            urlPath = "/app/v2/miotspec/prop/set",
            payload = JSONObject().put("params", params),
        ).optJSONArray("result") ?: JSONArray()
        val first = resArr.optJSONObject(0)
        val code = first?.optInt("code", 0) ?: 0
        // 小米官方 HA 集成规范：rc in [0, 1] 均表示成功（1 = 异步已受理下发）
        check(code == 0 || code == 1) { "设备拒绝属性写入 (code=$code)" }
        code
    }

    fun callAction(
        context: Context,
        did: String,
        siid: Int,
        aiid: Int,
        inValues: List<Any> = emptyList(),
    ): Result<String> = runCatching {
        val (cloudDid, effectiveSiid) = MijiaDevice.resolveCloudTarget(did, siid)
        val params = JSONObject()
            .put("did", cloudDid)
            .put("siid", effectiveSiid)
            .put("aiid", aiid)
            .put("in", JSONArray(inValues))
        val resObj = mihomeApiPost(
            context = context,
            urlPath = "/app/v2/miotspec/action",
            payload = JSONObject().put("params", params),
        ).optJSONObject("result")
        val code = resObj?.optInt("code", 0) ?: 0
        check(code == 0 || code == 1) { "动作执行失败 (code=$code)" }
        "动作执行成功"
    }

    fun toggleDevicePower(
        context: Context,
        device: MijiaDevice,
        targetOn: Boolean? = null,
    ): Result<Boolean> = runCatching {
        val spec = MijiaSpecParser.fetchAndParse(context, device.urn).getOrThrow()
        val powerProp = spec.primaryPowerProp(preferredSiid = device.splitSiid)
            ?: error("设备「${device.name}」不支持开关电源属性")
        val desired = targetOn ?: run {
            val currentMap = getProps(context, listOf(Triple(device.cloudDid, powerProp.siid, powerProp.piid))).getOrNull()
            val raw = currentMap?.get("${powerProp.siid}.${powerProp.piid}")
            val currentBool = when (raw) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                is String -> raw.equals("true", ignoreCase = true) || raw == "1"
                else -> false
            }
            !currentBool
        }
        setProp(context, device.cloudDid, powerProp.siid, powerProp.piid, desired).getOrThrow()
        desired
    }
}
