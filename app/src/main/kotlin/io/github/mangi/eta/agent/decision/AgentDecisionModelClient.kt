package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.agent.model.AgentHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * 判断模型客户端的固定配置。
 *
 * `baseUrl` / `apiKey` 来自既有 provider 行（`RuntimeConfigRepository.configForProviderAndModel`），
 * 这里不做独立凭证存储，避免出现第二份 key。
 */
internal data class AgentDecisionClientConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val timeoutMs: Int,
)

/**
 * `/v1/systemone` 端点归一化。
 *
 * 输入可以是：
 * - provider 的 baseUrl（默认预设，通常已带 `/v1`）；
 * - 用户手动填写的完整 chat 端点（`.../v1/chat/completions`）；
 * - 用户手动填写的决策端点（`.../v1/systemone`），或只到 `/v1`，或裸 host。
 *
 * 规则（纯函数，便于设置页复用与单测）：
 * 1. 去掉首尾空白与尾部 `/`；
 * 2. 含 `?` / `#` 直接拒绝：查询串与片段对决策调用没有意义，静默剥离会掩盖用户填错；
 * 3. 已以 `/systemone` 结尾（大小写不敏感）→ 原样返回；
 * 4. 已以 `/chat/completions` 结尾 → 剥掉该后缀再走 5/6；
 * 5. 已以 `/v1` 结尾 → 拼 `/systemone`；
 * 6. 裸 host（path 为空）或其它路径 → 拼 `/v1/systemone`（保持 M1 对存量 provider 的假设）。
 *
 * 只接受 https 且 host 非空；否则返回 [Invalid]，由调用方 fail-open 或提示用户，
 * 不抛异常。
 */
internal object AgentDecisionEndpoint {

    sealed interface Resolution {
        data class Success(val url: String) : Resolution
        data class Invalid(val reason: String) : Resolution
    }

    fun normalize(providerBaseUrl: String, override: String = ""): Resolution {
        val raw = override.trim().ifBlank { providerBaseUrl.trim() }
        if (raw.isEmpty()) return Resolution.Invalid(REASON_BLANK)
        if (raw.contains('?') || raw.contains('#')) return Resolution.Invalid(REASON_QUERY)
        val trimmed = raw.trimEnd('/')
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull()
            ?: return Resolution.Invalid(REASON_MALFORMED)
        if (!uri.scheme.equals("https", ignoreCase = true)) return Resolution.Invalid(REASON_SCHEME)
        if (uri.host.isNullOrBlank()) return Resolution.Invalid(REASON_HOST)
        val path = uri.rawPath.orEmpty()
        val lower = path.lowercase()
        val resolvedPath = when {
            lower.endsWith("/systemone") -> path
            lower.endsWith("/chat/completions") -> {
                val stripped = path.dropLast("/chat/completions".length)
                when {
                    stripped.lowercase().endsWith("/v1") -> "$stripped/systemone"
                    stripped.isEmpty() || stripped == "/" -> "/v1/systemone"
                    else -> "$stripped/v1/systemone"
                }
            }
            lower.endsWith("/v1") -> "$path/systemone"
            lower.isEmpty() || lower == "/" -> "/v1/systemone"
            else -> "$path/v1/systemone"
        }
        val port = uri.port.takeIf { it in 1..65535 && it != 443 }?.let { ":$it" }.orEmpty()
        val full = "https://${uri.host}$port$resolvedPath"
        return Resolution.Success(full)
    }

    const val REASON_BLANK = "blank"
    const val REASON_QUERY = "query_not_allowed"
    const val REASON_MALFORMED = "malformed"
    const val REASON_SCHEME = "https_required"
    const val REASON_HOST = "host_missing"

    /** 兼容 M1 调用点：推导失败返回 null。 */
    fun resolve(baseUrl: String): String? =
        (normalize(baseUrl) as? Resolution.Success)?.url
}

/** 决策题目的四种题型；请求体字段名与 `LingDT-Decision` 契约一致。 */
internal sealed interface AgentDecisionQuestion {
    val id: String
    val type: String

    fun toJson(): JSONObject

    /** 选择题的合法标签；非选择题返回空集。 */
    val labels: Set<String> get() = emptySet()

    /** 离散档位数量（score 用）；其他题型为 0。 */
    val levelCount: Int get() = 0

    data class Noul(
        override val id: String,
        val instructions: String,
        val trueCriterion: String,
        val falseCriterion: String,
    ) : AgentDecisionQuestion {
        override val type: String get() = "noul"

        override fun toJson(): JSONObject = JSONObject()
            .put("type", type)
            .put("instructions", instructions)
            .put("criteria", JSONObject().put("true", trueCriterion).put("false", falseCriterion))
    }

    data class Choice(
        override val id: String,
        val instructions: String,
        val criteria: Map<String, String>,
    ) : AgentDecisionQuestion {
        override val type: String get() = "choice"
        override val labels: Set<String> get() = criteria.keys

        override fun toJson(): JSONObject = JSONObject()
            .put("type", type)
            .put("instructions", instructions)
            .put("criteria", criteria.toJsonObject())
    }

    data class MultiChoice(
        override val id: String,
        val instructions: String,
        val criteria: Map<String, String>,
    ) : AgentDecisionQuestion {
        override val type: String get() = "multi-choice"
        override val labels: Set<String> get() = criteria.keys

        override fun toJson(): JSONObject = JSONObject()
            .put("type", type)
            .put("instructions", instructions)
            .put("criteria", criteria.toJsonObject())
    }

    data class Score(
        override val id: String,
        val instructions: String,
        val levels: List<String>,
    ) : AgentDecisionQuestion {
        override val type: String get() = "score"
        override val levelCount: Int get() = levels.size

        override fun toJson(): JSONObject = JSONObject()
            .put("type", type)
            .put("instructions", instructions)
            .put("criteria", JSONArray().also { array -> levels.forEach { level -> array.put(level) } })
    }
}

/** 解析后的答案；`type` 已与请求题目逐一校验。 */
internal sealed interface AgentDecisionAnswer {
    data class Noul(val probability: Double) : AgentDecisionAnswer

    data class Choice(
        val choice: String,
        val probabilities: Map<String, Double>,
        val confidence: Double,
    ) : AgentDecisionAnswer

    data class MultiChoice(
        val choices: Set<String>,
        val probabilities: Map<String, Double>,
        val confidence: Double,
    ) : AgentDecisionAnswer

    data class Score(
        val score: Double,
        val legend: Map<String, String>,
        val probabilities: Map<Int, Double>,
    ) : AgentDecisionAnswer
}

internal data class AgentDecisionUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)

/** 成功响应；`layers` 只保留解析结果，不再透传原始 JSON 文本。 */
internal data class AgentDecisionResponse(
    val answers: Map<String, AgentDecisionAnswer>,
    val layers: List<JSONObject>,
    val usage: AgentDecisionUsage?,
    val httpStatus: Int,
    val latencyMs: Long,
)

/**
 * 决策调用结果。失败一律收敛为 [Failure]，不向上抛异常：
 * 调用方（影子/后续主路径）只按 fail-open 或 fail-closed 处理。
 */
internal sealed interface AgentDecisionOutcome {
    data class Success(val response: AgentDecisionResponse) : AgentDecisionOutcome

    data class Failure(
        /** 机器可读原因，例如 `timeout` / `http_400` / `error_code_1`。 */
        val reason: String,
        val httpStatus: Int? = null,
        val errorCode: Int? = null,
        val errorDesc: String = "",
        val latencyMs: Long = 0,
        val detail: String = "",
    ) : AgentDecisionOutcome
}

/**
 * 独立决策客户端：只实现 `POST /v1/systemone`，不复用聊天客户端的
 * messages / responses 语义，也不做流式。
 *
 * 请求体按契约构造：`model` / `state`（对象）/ `questions` / `options.dag`。
 * HTTP 非 200 与 `errorCode != 0` 都视为失败；答案逐字段校验（类型、标签、概率范围）。
 */
internal object AgentDecisionModelClient {

    private val jsonMediaType = "application/json".toMediaType()

    suspend fun complete(
        config: AgentDecisionClientConfig,
        state: JSONObject,
        questions: List<AgentDecisionQuestion>,
        dagEdges: Map<String, List<String>> = emptyMap(),
    ): AgentDecisionOutcome = withContext(Dispatchers.IO) {
        val url = AgentDecisionEndpoint.resolve(config.baseUrl)
            ?: return@withContext AgentDecisionOutcome.Failure(
                reason = "invalid_base_url",
                detail = "baseUrl 必须是 https 且能推导出 /v1/systemone",
            )
        if (config.apiKey.isBlank()) {
            return@withContext AgentDecisionOutcome.Failure(reason = "missing_api_key")
        }
        if (config.model.isBlank()) {
            return@withContext AgentDecisionOutcome.Failure(reason = "missing_model")
        }

        val payload = buildPayload(config, state, questions, dagEdges)
        val startedAt = System.nanoTime()
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("X-Request-Id", "eta-decision-${System.currentTimeMillis()}")
            .build()

        val timeoutMs = config.timeoutMs.coerceAtLeast(500).toLong()
        val call = AgentHttpClient.client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(minOf(timeoutMs, 20_000L), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)

        try {
            call.execute().use { response ->
                handleResponse(response, startedAt, questions)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: InterruptedIOException) {
            // OkHttp 的 callTimeout 以 InterruptedIOException("timeout") 结束。
            AgentDecisionOutcome.Failure(
                reason = "timeout",
                latencyMs = elapsedMs(startedAt),
                detail = timeout.message.orEmpty(),
            )
        } catch (error: java.io.IOException) {
            AgentDecisionOutcome.Failure(
                reason = "network_error",
                latencyMs = elapsedMs(startedAt),
                detail = error.message.orEmpty(),
            )
        } catch (error: Exception) {
            AgentDecisionOutcome.Failure(
                reason = "exception",
                latencyMs = elapsedMs(startedAt),
                detail = "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
            )
        }
    }

    private fun buildPayload(
        config: AgentDecisionClientConfig,
        state: JSONObject,
        questions: List<AgentDecisionQuestion>,
        dagEdges: Map<String, List<String>>,
    ): JSONObject {
        val questionObject = JSONObject()
        questions.forEach { question -> questionObject.put(question.id, question.toJson()) }
        val edges = JSONObject()
        dagEdges.forEach { (downstream, upstream) ->
            if (upstream.isEmpty()) return@forEach
            edges.put(downstream, JSONObject().put("depends", JSONArray().also { array ->
                upstream.forEach { dependency -> array.put(dependency) }
            }))
        }
        return JSONObject()
            .put("model", config.model)
            .put("state", state)
            .put("questions", questionObject)
            .put("options", JSONObject().put("dag", JSONObject().put("edges", edges)))
    }

    private fun handleResponse(
        response: Response,
        startedAt: Long,
        questions: List<AgentDecisionQuestion>,
    ): AgentDecisionOutcome {
        val status = response.code
        val latency = elapsedMs(startedAt)
        val body = response.body?.string().orEmpty()
        if (status != 200) {
            return AgentDecisionOutcome.Failure(
                reason = "http_$status",
                httpStatus = status,
                latencyMs = latency,
                detail = body.take(200),
            )
        }
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return AgentDecisionOutcome.Failure(
                reason = "invalid_json",
                httpStatus = status,
                latencyMs = latency,
                detail = body.take(200),
            )
        val errorCode = root.optInt("errorCode", MISSING_ERROR_CODE)
        if (errorCode == MISSING_ERROR_CODE) {
            return AgentDecisionOutcome.Failure(
                reason = "missing_error_code",
                httpStatus = status,
                latencyMs = latency,
            )
        }
        if (errorCode != 0) {
            return AgentDecisionOutcome.Failure(
                reason = "error_code_$errorCode",
                httpStatus = status,
                errorCode = errorCode,
                errorDesc = root.optString("errorDesc"),
                latencyMs = latency,
            )
        }

        val parsed = runCatching { parseAnswers(root.optJSONObject("answers"), questions) }
            .getOrElse { error ->
                return AgentDecisionOutcome.Failure(
                    reason = "invalid_answer",
                    httpStatus = status,
                    errorCode = errorCode,
                    latencyMs = latency,
                    detail = error.message.orEmpty(),
                )
            }
        val layers = root.optJSONArray("layers")?.let { array ->
            val result = mutableListOf<JSONObject>()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { layer -> result.add(layer) }
            }
            result
        }.orEmpty()
        return AgentDecisionOutcome.Success(
            AgentDecisionResponse(
                answers = parsed,
                layers = layers,
                usage = root.optJSONObject("usage")?.let { usage ->
                    AgentDecisionUsage(
                        inputTokens = usage.optIntOrNull("input_tokens"),
                        outputTokens = usage.optIntOrNull("output_tokens"),
                        totalTokens = usage.optIntOrNull("total_tokens"),
                    )
                },
                httpStatus = status,
                latencyMs = latency,
            ),
        )
    }

    /**
     * 逐题校验：题型必须与请求一致，`choice` / `choices` 必须在声明标签内，
     * `noul` 在 [0,1]，`score` 在 [0, 档位数-1]。
     */
    private fun parseAnswers(
        answers: JSONObject?,
        questions: List<AgentDecisionQuestion>,
    ): Map<String, AgentDecisionAnswer> {
        val answersObject = requireNotNull(answers) { "answers 缺失" }
        return questions.associate { question ->
            val answer = requireNotNull(answersObject.optJSONObject(question.id)) { "答案缺失：${question.id}" }
            require(answer.optString("type") == question.type) {
                "${question.id} 类型不匹配：期望 ${question.type}，实际 ${answer.optString("type")}"
            }
            question.id to when (question) {
                is AgentDecisionQuestion.Noul -> {
                    val probability = answer.optDouble("noul", Double.NaN)
                    require(probability.isFinite() && probability in 0.0..1.0) {
                        "${question.id} noul 越界：$probability"
                    }
                    AgentDecisionAnswer.Noul(probability)
                }

                is AgentDecisionQuestion.Choice -> {
                    val choice = answer.optString("choice")
                    require(choice in question.labels) {
                        "${question.id} 选项不在声明标签内：$choice"
                    }
                    AgentDecisionAnswer.Choice(
                        choice = choice,
                        probabilities = answer.optJSONObject("probabilities").probabilities(),
                        confidence = answer.optDouble("confidence", 0.0),
                    )
                }

                is AgentDecisionQuestion.MultiChoice -> {
                    val choices = answer.optJSONArray("choices")?.let { array ->
                        val result = mutableSetOf<String>()
                        for (index in 0 until array.length()) result.add(array.optString(index))
                        result
                    }.orEmpty()
                    require(choices.all { it in question.labels }) {
                        "${question.id} 勾选含未声明标签：${choices - question.labels}"
                    }
                    AgentDecisionAnswer.MultiChoice(
                        choices = choices,
                        probabilities = answer.optJSONObject("probabilities").probabilities(),
                        confidence = answer.optDouble("confidence", 0.0),
                    )
                }

                is AgentDecisionQuestion.Score -> {
                    val score = answer.optDouble("score", Double.NaN)
                    val levels = question.levelCount
                    require(score.isFinite() && score >= 0.0 && score <= (levels - 1).toDouble()) {
                        "${question.id} score 越界：$score（档位数 $levels）"
                    }
                    val probabilityMap = mutableMapOf<Int, Double>()
                    answer.optJSONObject("probabilities")?.let { source ->
                        for (key in source.keys()) {
                            val index = key.toIntOrNull() ?: continue
                            val value = source.optDouble(key, Double.NaN)
                            if (value.isFinite()) probabilityMap[index] = value
                        }
                    }
                    AgentDecisionAnswer.Score(
                        score = score,
                        legend = answer.optJSONObject("legend")?.let { legend ->
                            val result = mutableMapOf<String, String>()
                            for (key in legend.keys()) result[key] = legend.optString(key)
                            result
                        }.orEmpty(),
                        probabilities = probabilityMap,
                    )
                }
            }
        }
    }

    private fun JSONObject?.probabilities(): Map<String, Double> {
        val source = this ?: return emptyMap()
        val result = mutableMapOf<String, Double>()
        for (key in source.keys()) {
            val value = source.optDouble(key, Double.NaN)
            if (value.isFinite()) result[key] = value
        }
        return result
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key)) optInt(key) else null

    private fun elapsedMs(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)

    private const val MISSING_ERROR_CODE = Int.MIN_VALUE
}

/** 题型标签表转 JSON；顶层声明，供 [AgentDecisionQuestion] 的 `toJson()` 使用。 */
private fun Map<String, String>.toJsonObject(): JSONObject {
    val result = JSONObject()
    forEach { (key, value) -> result.put(key, value) }
    return result
}
