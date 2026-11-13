package io.github.mangi.eta.agent.mijia

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.LinkedHashSet
import java.util.UUID

internal data class MijiaAuthState(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMillis: Long = 0L,
    val uid: String = "",
    val nickname: String = "",
    val cloudServer: String = "cn",
    val lastError: String = "",
) {
    val isAuthorized: Boolean
        get() = accessToken.isNotBlank() && refreshToken.isNotBlank()
}

internal data class MijiaRoomInfo(
    val roomId: String,
    val roomName: String,
    val dids: List<String> = emptyList(),
)

internal data class MijiaHomeInfo(
    val homeId: String,
    val homeName: String,
    val uid: String = "",
    val dids: List<String> = emptyList(),
    val rooms: List<MijiaRoomInfo> = emptyList(),
)

internal data class MijiaDevice(
    val did: String,
    val name: String,
    val model: String,
    val urn: String,
    val online: Boolean,
    val homeId: String,
    val homeName: String,
    val roomId: String,
    val roomName: String,
    val icon: String = "",
    val category: String = "",
) {
    /**
     * 对于多键开关等拆分设备（did 形如 `1043723822.s4`），
     * 米家云端接口要求传父设备真实 did（`1043723822`），并将 `.s4` 映射为对应的服务实例 `siid = 4`。
     */
    val cloudDid: String
        get() = SPLIT_SUFFIX_REGEX.find(did)?.let { did.removeSuffix(it.value) } ?: did

    val splitSiid: Int?
        get() = SPLIT_SUFFIX_REGEX.find(did)?.groupValues?.getOrNull(1)?.toIntOrNull()

    companion object {
        private val SPLIT_SUFFIX_REGEX = Regex("\\.s(\\d+)$", RegexOption.IGNORE_CASE)

        fun resolveCloudTarget(rawDid: String, siid: Int): Pair<String, Int> {
            val match = SPLIT_SUFFIX_REGEX.find(rawDid.trim()) ?: return rawDid.trim() to siid
            val parentDid = rawDid.trim().removeSuffix(match.value)
            val subSiid = match.groupValues.getOrNull(1)?.toIntOrNull() ?: siid
            val effectiveSiid = if (siid == 2 && subSiid > 0) subSiid else siid
            return parentDid to effectiveSiid
        }
    }
}

internal object MijiaStore {
    const val MAX_FAVORITES = 12

    private const val PREF_NAME = "eta_mijia_store"
    private const val KEY_ENABLED = "mijia_enabled"
    private const val KEY_BREENO_DIRECT = "mijia_breeno_direct"
    private const val KEY_DEVICE_ID = "mijia_device_id"
    private const val KEY_ACCESS_TOKEN = "mijia_access_token"
    private const val KEY_REFRESH_TOKEN = "mijia_refresh_token"
    private const val KEY_EXPIRES_AT = "mijia_expires_at"
    private const val KEY_UID = "mijia_uid"
    private const val KEY_NICKNAME = "mijia_nickname"
    private const val KEY_CLOUD_SERVER = "mijia_cloud_server"
    private const val KEY_LAST_ERROR = "mijia_last_error"
    private const val KEY_HOMES_JSON = "mijia_homes_json"
    private const val KEY_DEVICES_JSON = "mijia_devices_json"
    private const val KEY_FAVORITES_JSON = "mijia_favorites_json"

    private val consumedAuthCodes: MutableSet<String> =
        Collections.synchronizedSet(object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                val added = super.add(element)
                while (size > 64) {
                    val it = iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
                return added
            }
        })

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isBreenoDirectEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BREENO_DIRECT, true)

    fun setBreenoDirectEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_BREENO_DIRECT, enabled).apply()
    }

    @Synchronized
    fun deviceId(context: Context): String {
        val sp = prefs(context)
        val existing = sp.getString(KEY_DEVICE_ID, null)?.trim().orEmpty()
        if (existing.startsWith("ha.") && existing.length > 6) {
            return existing
        }
        val created = "ha.${UUID.randomUUID().toString().replace("-", "")}"
        sp.edit().putString(KEY_DEVICE_ID, created).commit()
        return created
    }

    fun markAuthCodeConsumed(code: String): Boolean {
        val normalized = code.trim()
        if (normalized.isEmpty()) return false
        return consumedAuthCodes.add(normalized)
    }

    fun loadAuth(context: Context): MijiaAuthState {
        val sp = prefs(context)
        return MijiaAuthState(
            accessToken = sp.getString(KEY_ACCESS_TOKEN, "").orEmpty(),
            refreshToken = sp.getString(KEY_REFRESH_TOKEN, "").orEmpty(),
            expiresAtMillis = sp.getLong(KEY_EXPIRES_AT, 0L),
            uid = sp.getString(KEY_UID, "").orEmpty(),
            nickname = sp.getString(KEY_NICKNAME, "").orEmpty(),
            cloudServer = sp.getString(KEY_CLOUD_SERVER, "cn").orEmpty().ifBlank { "cn" },
            lastError = sp.getString(KEY_LAST_ERROR, "").orEmpty(),
        )
    }

    fun saveAuth(context: Context, auth: MijiaAuthState) {
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, auth.accessToken)
            .putString(KEY_REFRESH_TOKEN, auth.refreshToken)
            .putLong(KEY_EXPIRES_AT, auth.expiresAtMillis)
            .putString(KEY_UID, auth.uid)
            .putString(KEY_NICKNAME, auth.nickname)
            .putString(KEY_CLOUD_SERVER, auth.cloudServer.ifBlank { "cn" })
            .putString(KEY_LAST_ERROR, auth.lastError)
            .commit()
    }

    fun clearAuth(context: Context, lastError: String? = "") {
        prefs(context).edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_UID)
            .remove(KEY_NICKNAME)
            .putString(KEY_LAST_ERROR, lastError.orEmpty())
            .commit()
    }

    fun recordAuthError(context: Context, error: String) {
        prefs(context).edit().putString(KEY_LAST_ERROR, error).commit()
    }

    fun loadHomes(context: Context): List<MijiaHomeInfo> {
        val raw = prefs(context).getString(KEY_HOMES_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val homeId = obj.optString("homeId")
                val homeName = obj.optString("homeName")
                if (homeId.isBlank()) return@mapNotNull null
                val didsArr = obj.optJSONArray("dids") ?: JSONArray()
                val dids = (0 until didsArr.length()).mapNotNull { didsArr.optString(it).takeIf(String::isNotBlank) }
                val roomsArr = obj.optJSONArray("rooms") ?: JSONArray()
                val rooms = (0 until roomsArr.length()).mapNotNull { rIdx ->
                    val rObj = roomsArr.optJSONObject(rIdx) ?: return@mapNotNull null
                    val rId = rObj.optString("roomId")
                    val rName = rObj.optString("roomName")
                    if (rId.isBlank()) return@mapNotNull null
                    val rDidsArr = rObj.optJSONArray("dids") ?: JSONArray()
                    val rDids = (0 until rDidsArr.length()).mapNotNull { rDidsArr.optString(it).takeIf(String::isNotBlank) }
                    MijiaRoomInfo(roomId = rId, roomName = rName, dids = rDids)
                }
                MijiaHomeInfo(
                    homeId = homeId,
                    homeName = homeName,
                    uid = obj.optString("uid"),
                    dids = dids,
                    rooms = rooms,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun loadDevices(context: Context): List<MijiaDevice> {
        val raw = prefs(context).getString(KEY_DEVICES_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { idx ->
                val obj = arr.optJSONObject(idx) ?: return@mapNotNull null
                val did = obj.optString("did")
                val name = obj.optString("name")
                if (did.isBlank() || name.isBlank()) return@mapNotNull null
                MijiaDevice(
                    did = did,
                    name = name,
                    model = obj.optString("model"),
                    urn = obj.optString("urn"),
                    online = obj.optBoolean("online", false),
                    homeId = obj.optString("homeId"),
                    homeName = obj.optString("homeName"),
                    roomId = obj.optString("roomId"),
                    roomName = obj.optString("roomName"),
                    icon = obj.optString("icon"),
                    category = obj.optString("category"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveHomesAndDevices(
        context: Context,
        homes: List<MijiaHomeInfo>,
        devices: List<MijiaDevice>,
    ) {
        val homesArr = JSONArray().apply {
            homes.forEach { h ->
                put(
                    JSONObject()
                        .put("homeId", h.homeId)
                        .put("homeName", h.homeName)
                        .put("uid", h.uid)
                        .put("dids", JSONArray(h.dids))
                        .put(
                            "rooms",
                            JSONArray().apply {
                                h.rooms.forEach { r ->
                                    put(
                                        JSONObject()
                                            .put("roomId", r.roomId)
                                            .put("roomName", r.roomName)
                                            .put("dids", JSONArray(r.dids)),
                                    )
                                }
                            },
                        ),
                )
            }
        }
        val devicesArr = JSONArray().apply {
            devices.forEach { d ->
                put(
                    JSONObject()
                        .put("did", d.did)
                        .put("name", d.name)
                        .put("model", d.model)
                        .put("urn", d.urn)
                        .put("online", d.online)
                        .put("homeId", d.homeId)
                        .put("homeName", d.homeName)
                        .put("roomId", d.roomId)
                        .put("roomName", d.roomName)
                        .put("icon", d.icon)
                        .put("category", d.category),
                )
            }
        }
        prefs(context).edit()
            .putString(KEY_HOMES_JSON, homesArr.toString())
            .putString(KEY_DEVICES_JSON, devicesArr.toString())
            .commit()
    }

    fun favoriteDids(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_FAVORITES_JSON, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                .distinct()
                .take(MAX_FAVORITES)
        }.getOrDefault(emptyList())
    }

    fun setFavorites(context: Context, dids: List<String>) {
        val normalized = dids.filter { it.isNotBlank() }.distinct().take(MAX_FAVORITES)
        prefs(context).edit()
            .putString(KEY_FAVORITES_JSON, JSONArray(normalized).toString())
            .commit()
    }

    @Synchronized
    fun toggleFavorite(context: Context, did: String): Boolean {
        val current = favoriteDids(context).toMutableList()
        val nowFav = if (did in current) {
            current.remove(did)
            false
        } else {
            if (current.size >= MAX_FAVORITES) return false
            current.add(did)
            true
        }
        setFavorites(context, current)
        return nowFav
    }

    private fun specCacheFile(context: Context, urn: String): File {
        val dir = File(context.applicationContext.filesDir, "mijia_specs").apply { mkdirs() }
        val safeName = urn.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(dir, "$safeName.json")
    }

    fun loadSpecCache(context: Context, urn: String): MijiaDeviceSpec? {
        if (urn.isBlank()) return null
        val file = specCacheFile(context, urn)
        if (!file.isFile) return null
        return runCatching {
            MijiaSpecParser.fromCacheJson(JSONObject(file.readText()))
        }.getOrNull()
    }

    fun saveSpecCache(context: Context, spec: MijiaDeviceSpec) {
        if (spec.urn.isBlank()) return
        runCatching {
            val file = specCacheFile(context, spec.urn)
            file.writeText(MijiaSpecParser.toCacheJson(spec).toString())
        }
    }
}
