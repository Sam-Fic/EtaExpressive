package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.ProviderAuthModes
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Provider 边界分类失败；模型请求重试预算由 AgentModelRetry 持有。 */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
    val diagnostic: String = "",
    // Only the Responses provider's per-request delivery guard may authorize correction.
    val envelopeCorrectionAllowed: Boolean = false,
    /**
     * 提供方 `Retry-After` 或 `RetryInfo.retryDelay` 提示的等待秒数；无法解析或不存在时为 null。
     * 重连退避据此下限等待，避免在明确要求的冷却期内反复撞限流。
     */
    val retryAfterSec: Long? = null,
    // Only errors a client may safely resend with the same request get bounded network retries.
    val retryMode: RetryMode = RetryMode.TRANSIENT,
    val permanentReason: PermanentReason? = null,
) : IllegalStateException(message, cause) {
    /**
     * 重连策略对该失败的处理方式。区分「网络在抖」与「需要用户改配置」：
     * 后者不得进入无限重连窗口。`transport()` 对未知异常一律按 [TRANSIENT] 处理，
     * 因此新增的显式控制流失败必须自己声明模式。
     */
    enum class RetryMode {
        /** 传输/暂时性错误：按用户所选重连窗口持续重试。 */
        TRANSIENT,

        /**
         * 永久性错误：重发同一请求不会变好。只允许一次立即重发（容忍瞬时抖动/刚完成的
         * 配置写入），之后直接结束并把原因交给用户，不再烧配额、也不把配置问题伪装成网络抖动。
         */
        PERMANENT,
    }

    /**
     * 永久原因。认证类失败在此只作标记：OAuth 每次尝试都重新取 token，用户在重连窗口内
     * 重新登录可以恢复，所以由 AgentModelRetry 按认证方式决定是否降级为窗口内重试；
     * API Key 烧进配置，重发永远不可能变好。
     */
    enum class PermanentReason { AUTH, QUOTA, NOT_FOUND, BAD_REQUEST }

    companion object {
        private val transientStatus = setOf(408, 429, 500, 502, 503, 504, 524, 529)
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        private val transientCodes = setOf(
            "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
            "api_error", "internal_error", "provider_unavailable", "service_unavailable",
        )

        private fun toolEnvelopeRejected() = AgentModelFailure(
            code = ResponsesToolEnvelopeRecovery.CODE,
            retryable = false,
            envelopeCorrectionAllowed = true,
            message = "模型生成的工具封装未通过代理 JSON 校验（missing-separator）。",
            // Never retain the rejected code, request body, headers, or provider text.
            diagnostic = "responses_tool_envelope_rejected; json_failure=missing-separator",
        )

        fun http(
            status: Int,
            body: String,
            headers: okhttp3.Headers? = null,
            secrets: List<String> = emptyList(),
        ): AgentModelFailure {
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: org.json.JSONException) {
                null
            }
            if (ResponsesToolEnvelopeRecovery.matches(error, status)) {
                val guard = ResponsesToolEnvelopeRecovery.DeliveryGuard()
                guard.inspectEnvelope(JSONObject(body))
                return guard.protect(toolEnvelopeRejected()) as AgentModelFailure
            }
            val diagnostic = AgentHttpFailureDiagnostics.collect(status, body, headers, secrets)
            if (status in setOf(400, 413) && isContextOverflow(error)) {
                return AgentModelFailure("CONTEXT_WINDOW_EXCEEDED", false, "提供方确认上下文超限，需缩减上下文后重试。", diagnostic = diagnostic)
            }
            val permanent = isPermanent(error, body)
            val providerMessage = error?.optString("message")
                ?.replace('\n', ' ')
                ?.replace('\r', ' ')
                ?.trim()
                .orEmpty()
            val safeProviderMessage = AgentHttpFailureDiagnostics.safe(providerMessage, secrets, 400)
            val retryAfter = headers?.get("Retry-After")?.let { AgentHttpFailureDiagnostics.safe(it, secrets, 100) }
            val retryAfterSec = retryAfterSeconds(headers, error)
            val validationUrl = extractGoogleValidationUrl(error, body)
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = status in transientStatus && !permanent,
                // 永久错误（鉴权失败、额度/计费受限、模型或参数不存在）重发同一请求不会变好，
                // 之前它们和网络抖动共用无限重连窗口。
                retryAfterSec = retryAfterSec,
                retryMode = if (status in transientStatus && !permanent) RetryMode.TRANSIENT else RetryMode.PERMANENT,
                permanentReason = if (status in transientStatus && !permanent) null
                    else permanentReasonFor(status, permanent),
                message = if (permanent) "模型接口额度或计费受限（HTTP $status），请检查服务商账户。" +
                    safeProviderMessage.takeIf { it.isNotBlank() }?.let { " 服务端：$it" }.orEmpty()
                else when (status) {
                    400 -> {
                        val detail = safeProviderMessage.takeIf { it.isNotBlank() }
                            ?: body.replace('\n', ' ').replace('\r', ' ').trim().take(400)
                                .takeIf { it.isNotBlank() }
                        if (detail != null) "模型请求参数无效（HTTP 400）：$detail"
                        else "模型请求参数无效（HTTP 400），请检查模型配置。"
                    }
                    401 -> {
                        val detail = safeProviderMessage.takeIf { it.isNotBlank() }
                        if (detail != null && detail.contains("verify", true))
                            "Google 要求验证这个账号（HTTP 401）：$detail"
                        else "模型接口认证失败（HTTP 401）。OAuth 请重新登录，API Key 请检查密钥。"
                    }
                    403 -> {
                        val detail = safeProviderMessage.takeIf { it.isNotBlank() }
                            ?: body.replace('\n', ' ').replace('\r', ' ').trim().take(400)
                                .takeIf { it.isNotBlank() }
                        val verify = validationUrl != null || (
                            detail != null && detail.contains("verify your account", ignoreCase = true)
                        )
                        when {
                            validationUrl != null ->
                                "Google 要求验证这个账号（HTTP 403）。请用无痕浏览器打开：$validationUrl 登录被标记的账号完成验证，然后再发消息。官网首页通常不会弹验证。"
                            verify -> "Google 要求先验证这个账号（HTTP 403）。请用无痕浏览器打开接口返回的 validation_url 完成账号验证。"
                            detail != null -> "模型接口拒绝访问（HTTP 403）：$detail"
                            else -> "模型接口拒绝访问（HTTP 403），请检查账户与模型权限。"
                        }
                    }
                    404 -> "模型接口或模型不存在（HTTP 404），请检查接口地址与模型名称。"
                    429 -> "模型接口暂时限流（HTTP 429）。" +
                        safeProviderMessage.takeIf { it.isNotBlank() }?.let { " 服务端：$it" }.orEmpty() +
                        retryAfter?.let { " Retry-After：$it" }.orEmpty()
                    else -> {
                        val title = htmlTitle(body)
                        if (title != null || body.contains("<html", ignoreCase = true) ||
                            body.contains("<!DOCTYPE", ignoreCase = true)
                        ) {
                            "模型接口返回了错误网页（HTTP $status${title?.let { "：$it" } ?: ""}）"
                        } else {
                            "模型接口返回 HTTP $status"
                        }
                    }
                },
                diagnostic = diagnostic,
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            if (ResponsesToolEnvelopeRecovery.matches(error)) return toolEnvelopeRejected()
            // A stream may already have emitted visible text or invoked hosted tools.
            // Do not turn its late error into a replay of possible side effects.
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            val quota = isPermanent(error, error.optString("message"))
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !quota && codes.any { it in transientCodes || it.toIntOrNull() in transientStatus },
                message = message,
                // 流式错误同样要区分「暂时」与「需要改配置」；鉴权类保留 OAuth 的窗口内恢复。
                retryMode = if (quota ||
                    codes.any { it in permanentCodes || it.contains("auth", true) ||
                        it in setOf("401", "403", "404") }
                ) RetryMode.PERMANENT else RetryMode.TRANSIENT,
                permanentReason = when {
                    quota -> PermanentReason.QUOTA
                    codes.any { it.contains("auth", true) || it in setOf("401", "403") } -> PermanentReason.AUTH
                    "404" in codes -> PermanentReason.NOT_FOUND
                    else -> null
                },
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                "模型请求等待超时（连接或写入超时，或读取响应等待超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟）。",
                failure,
            )
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, "模型连接中断或暂时无法建立，请检查网络与服务商状态。", failure,
            )
            else -> unexpectedMediaType(failure)
        }

        fun unexpectedResponse(
            status: Int?,
            contentType: String?,
            body: String,
            cause: Throwable? = null,
        ): AgentModelFailure {
            val trimmed = body.trim()
            val type = contentType.orEmpty()
            if (trimmed.startsWith("{")) {
                return http(status ?: 200, trimmed)
            }
            if (trimmed.contains("event: response.") || trimmed.contains("response.created")) {
                return AgentModelFailure(
                    "HTTP_${status ?: 200}",
                    false,
                    "接口返回了 Responses API 事件流。请把该提供商的 Endpoint 模式改为 Responses API。",
                    cause,
                    // 明确要用户改配置，不是网络抖动；无限重发只会一直失败到用户手动停止。
                    retryMode = RetryMode.PERMANENT,
                    permanentReason = PermanentReason.BAD_REQUEST,
                )
            }
            val html = type.contains("html", ignoreCase = true) ||
                trimmed.startsWith("<!doctype", ignoreCase = true) ||
                trimmed.startsWith("<html", ignoreCase = true)
            val title = htmlTitle(trimmed)
            val statusLabel = status?.let { "HTTP $it" } ?: type.ifBlank { "未知类型" }
            val detail = title
                ?: trimmed.replace('\n', ' ').replace('\r', ' ').trim().take(120).ifBlank { null }
            val transientShape = status == null || status in transientStatus
            return AgentModelFailure(
                code = "HTTP_${status ?: 200}",
                retryable = transientShape,
                message = if (html) {
                    "模型接口返回了网页而不是数据流（$statusLabel${detail?.let { "：$it" } ?: ""}）。Chat Completions 可能被反代拦截，可改用 Responses API 或检查隧道/上游。"
                } else {
                    "模型接口返回了无法解析的响应（$statusLabel）${detail?.let { "：$it" } ?: ""}"
                },
                cause = cause,
                // 502 反代页仍是暂时性问题；4xx 的不可解析响应属于配置问题，只允许有限重发。
                retryMode = if (transientShape) RetryMode.TRANSIENT else RetryMode.PERMANENT,
                permanentReason = if (transientShape) null else PermanentReason.BAD_REQUEST,
            )
        }

        private fun unexpectedMediaType(failure: Exception): AgentModelFailure? {
            val message = failure.message.orEmpty()
            if (!message.startsWith("Invalid content-type")) return null
            val contentType = message.substringAfter("Invalid content-type:", "").trim().ifBlank { null }
            return unexpectedResponse(status = null, contentType = contentType, body = "", cause = failure)
        }

        private fun htmlTitle(body: String): String? {
            val start = body.indexOf("<title", ignoreCase = true).takeIf { it >= 0 } ?: return null
            val openEnd = body.indexOf('>', start).takeIf { it >= 0 } ?: return null
            val close = body.indexOf("</title>", openEnd + 1, ignoreCase = true).takeIf { it >= 0 } ?: return null
            val title = body.substring(openEnd + 1, close)
            val collapsed = buildString(title.length) {
                var gap = false
                title.forEach { ch ->
                    if (ch.isWhitespace()) {
                        if (!gap) {
                            append(' ')
                            gap = true
                        }
                    } else {
                        append(ch)
                        gap = false
                    }
                }
            }.trim()
            return collapsed.take(80).ifBlank { null }
        }

        internal fun extractGoogleValidationUrl(error: JSONObject?, body: String): String? {
            val details = error?.optJSONArray("details")
            if (details != null) {
                for (index in 0 until details.length()) {
                    val item = details.optJSONObject(index) ?: continue
                    val url = item.optJSONObject("metadata")?.optString("validation_url")
                        ?.takeIf { it.isNotBlank() }
                    if (url != null && (
                            item.optString("reason").equals("VALIDATION_REQUIRED", true) ||
                                url.startsWith("https://accounts.google.com/")
                            )
                    ) {
                        return url
                    }
                }
            }
            val match = Regex("""https://accounts\.google\.com/signin/continue[^"\\\s]+""").find(body)
            return match?.value
        }

        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            val code = error.optString("code").lowercase()
            val type = error.optString("type").lowercase()
            if (code in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long") ||
                type in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long")) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("context window exceeded")
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }

        private fun permanentReasonFor(status: Int, permanent: Boolean): PermanentReason = when {
            permanent -> PermanentReason.QUOTA
            status == 401 || status == 403 -> PermanentReason.AUTH
            status == 404 -> PermanentReason.NOT_FOUND
            else -> PermanentReason.BAD_REQUEST
        }

        /**
         * 认证失败是否真的不可恢复。API Key 烧在模型配置里，重发同一请求永远不可能变好；
         * OAuth 每次尝试都重新取 token，用户在重连窗口内重新登录即可恢复，因此保留其窗口内重试。
         * [reason] 为 null 表示非认证类永久原因（额度/路径/参数），没有这种恢复途径。
         */
        fun permanentForAuthMode(reason: PermanentReason?, authMode: String): Boolean {
            if (reason != PermanentReason.AUTH) return true
            return !ProviderAuthModes.isOAuth(authMode)
        }

        /**
         * `Retry-After` 允许 delta-seconds 与 HTTP-date 两种形式；HTTP-date 按当前时刻折算，
         * 已经过去或无法解析时返回 null（退回常规退避）。上限由重连侧统一收敛。
         */
        internal fun parseRetryAfter(value: String?): Long? {
            val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            raw.toLongOrNull()?.takeIf { it >= 0 }?.let { return it }
            val seconds = raw.indexOf("seconds=").takeIf { it >= 0 }?.let {
                raw.substring(it + "seconds=".length).trim().toLongOrNull()
            }
            if (seconds != null) return seconds.coerceAtLeast(0)
            val date = runCatching {
                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.parse(raw, java.time.OffsetDateTime::from)
            }.getOrNull() ?: return null
            return java.time.Duration.between(java.time.OffsetDateTime.now(), date).seconds.coerceAtLeast(0)
        }

        /** Google `google.rpc.RetryInfo.retryDelay`，形如 `30s` / `1.5s`。 */
        private fun parseRetryDelay(detail: String?): Long? {
            val raw = detail?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            raw.removeSuffix("s").trim().toDoubleOrNull()?.let { return it.toLong().coerceAtLeast(0) }
            return null
        }

        internal fun retryAfterSeconds(headers: okhttp3.Headers?, error: JSONObject?): Long? {
            headerRetryAfter(headers)?.let { return it }
            val details = error?.optJSONArray("details") ?: return null
            for (index in 0 until details.length()) {
                val item = details.optJSONObject(index) ?: continue
                if (!item.optString("@type").endsWith("RetryInfo")) continue
                parseRetryDelay(item.optString("retryDelay"))?.let { return it }
            }
            return null
        }

        private fun headerRetryAfter(headers: okhttp3.Headers?): Long? =
            headers?.get("Retry-After")?.let { parseRetryAfter(it) }
    }
}
