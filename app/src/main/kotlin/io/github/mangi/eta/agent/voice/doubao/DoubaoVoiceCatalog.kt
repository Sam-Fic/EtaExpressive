package io.github.mangi.eta.agent.voice.doubao

import io.github.mangi.eta.R
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Control-plane credentials are used only for this explicitly requested catalog refresh. */
internal object DoubaoVoiceCatalog {
    private const val QUERY = "Action=BatchListMegaTTSTrainStatus&Version=2025-05-21"
    internal const val CONTENT_TYPE = "application/json; charset=utf-8"
    // Control-plane signing must not inherit model JSON header rewrites or redirect policies.
    internal val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).addInterceptor(DoubaoDiagnostics).build()
    }
    private const val HOST = "open.volcengineapi.com"
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hash(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    private fun hmac(key: ByteArray, text: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(text.toByteArray())
    }
    internal fun signedRequest(ak: String, sk: String, body: String, now: Instant): Request {
        val date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now)
        val day = date.take(8); val digest = hash(body)
        val headers = "content-type;host;x-content-sha256;x-date"
        val scope = "$day/cn-beijing/speech_saas_prod/request"
        val canonical = "POST\n/\n$QUERY\ncontent-type:$CONTENT_TYPE\nhost:$HOST\nx-content-sha256:$digest\nx-date:$date\n\n$headers\n$digest"
        var key = hmac(sk.toByteArray(), day)
        key = hmac(key, "cn-beijing"); key = hmac(key, "speech_saas_prod"); key = hmac(key, "request")
        val signature = hex(hmac(key, "HMAC-SHA256\n$date\n$scope\n${hash(canonical)}"))
        return Request.Builder().url("https://$HOST/?$QUERY").header("Host", HOST).header("Content-Type", CONTENT_TYPE).header("X-Date", date).header("X-Content-Sha256", digest)
            .header("Authorization", "HMAC-SHA256 Credential=$ak/$scope, SignedHeaders=$headers, Signature=$signature")
            .post(body.toByteArray(Charsets.UTF_8).toRequestBody(CONTENT_TYPE.toMediaType())).build()
    }
    internal fun responseError(http: Int, root: JSONObject, secrets: List<String>): String? {
        val meta = root.optJSONObject("ResponseMetadata")
        val error = meta?.optJSONObject("Error")
        if (http in 200..299 && error == null) return null
        val code = DoubaoDiagnostics.sanitize(error?.optString("Code").orEmpty(), secrets)
        val message = DoubaoDiagnostics.sanitize(error?.optString("Message").orEmpty(), secrets)
        val requestId = DoubaoDiagnostics.sanitize(meta?.optString("RequestId").orEmpty(), secrets)
        val hint = when {
            code.contains("Signature", true) -> "签名校验失败：请核对 AK/SK 是否为同一对；也可能是客户端签名实现问题"
            code.contains("Expired", true) || code.contains("Time", true) -> "请求时间无效：请检查手机自动日期与时间"
            http == 401 -> "访问密钥鉴权失败：请使用火山访问密钥管理中的 AK/SK，不是豆包 API Key，并检查密钥是否有效"
            http == 403 -> "账户没有查询权限，请核对项目及子账户 IAM 授权"
            else -> "读取失败，未取得音色列表"
        }
        return "$hint\nHTTP $http · $code\n$message\n请求标识：$requestId"
    }
    data class Slot(val id: String, val name: String, val state: String, val remaining: Int)
    /** 控制面请求没有 Context：资源解析失败（如纯 JVM 测试）时回退中文原文。 */
    private fun label(resId: Int, zh: String): String =
        runCatching { io.github.mangi.eta.agent.runtime.AgentAppContext.resolve()?.getString(resId) }.getOrNull() ?: zh
    fun list(ak: String, sk: String, project: String): List<Slot> {
        require(ak.isNotBlank() && sk.isNotBlank() && project.isNotBlank()) { label(R.string.dv_fill_ak_sk_project, "请填写 AK、SK 和项目名") }
        val result = linkedMapOf<String, Slot>()
        for (state in listOf("Unknown", "Training", "Success", "Active")) {
            for (page in 1..20) {
                val body = JSONObject().put("ProjectName", project).put("State", state).put("PageNumber", page).put("PageSize", 100).toString()
                val root = client.newCall(signedRequest(ak, sk, body, Instant.now())).execute().use { response ->
                    val source = response.body.source()
                    check(!source.request(2L * 1024 * 1024 + 1)) { label(R.string.dv_voice_list_too_large, "音色列表响应过大") }
                    val json = runCatching { JSONObject(source.readUtf8()) }.getOrDefault(JSONObject())
                    responseError(response.code, json, listOf(ak, sk))?.let {
                        DoubaoDiagnostics.mark("catalog.rejected", it)
                        error(it)
                    }
                    json
                }
                val data = root.getJSONObject("Result"); val items = data.optJSONArray("Statuses") ?: break
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i); val id = item.optString("SpeakerID")
                    if (id.startsWith("S_")) result[id] = Slot(id, item.optString("Alias").ifBlank { id }, item.optString("State", state), item.optInt("AvailableTrainingTimes", -1))
                }
                if (page * 100 >= data.optInt("TotalCount", items.length()) || items.length() == 0) break
                check(page < 20) { label(R.string.dv_voice_list_over_2000, "音色超过 2000 条，请按项目缩小查询范围") }
            }
        }
        return result.values.toList()
    }
}
