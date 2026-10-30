package io.github.mangi.eta.agent.model

import android.graphics.BitmapFactory

import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.media.hasSupportedImageMagic
import io.github.mangi.eta.agent.media.sniffAgentImageMimeType
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class AgentImageGenerationClient(
    private val httpClient: OkHttpClient = AgentHttpClient.modelClient,
    private val runController: AgentRunController? = null,
) {
    // Generation is billable: a transport failure is not proof the server did not generate an image.
    private val generationHttpClient = httpClient.newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    data class InputImage(
        val bytes: ByteArray,
        val mimeType: String,
    )

    data class GeneratedImage(
        val bytes: ByteArray,
        val mimeType: String,
        val width: Int = 0,
        val height: Int = 0,
    )

    data class Result(
        val images: List<GeneratedImage>,
        val text: String = "",
    )

    fun generate(
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<InputImage> = emptyList(),
        options: AgentImageGenerationOptions = AgentImageGenerationOptions(),
        mask: InputImage? = null,
    ): Result {
        runController?.throwIfCancelled()
        require(config.baseUrl.isNotBlank()) { "请先配置 API 地址" }
        require(prompt.isNotBlank()) { "请输入图片描述" }
        val parsedPrompt = ImagePromptOptions.parse(prompt, options)
        require(parsedPrompt.prompt.isNotBlank()) { "请输入图片描述" }
        val inputBody = generationsBody(config, parsedPrompt.prompt)
        val grok = GrokImageProfile.applies(config.baseUrl, config.model, inputBody)
        fun prepare(inline: AgentImageGenerationOptions, overrides: AgentImageGenerationOptions) =
            if (grok) GrokImageProfile.prepare(inputBody, inline, overrides, images.size, mask != null)
            else ImageRequestParameters.prepare(inputBody, inline, overrides, defaultCount = 1)
        val prepared = prepare(parsedPrompt.options, options)
        if (config.providerType == ProviderTypes.ANTHROPIC && !prepared.explicitEndpoint)
            AgentImageGenerationOptions.invalid("Anthropic 原生消息协议不是生图接口；若该中转另有 Images 端点，请显式配置 eta_image_config.endpoint")
        val batchCount = prepared.options.count ?: 1
        val parallelism = prepared.options.concurrency
        val single = if (parallelism == null) prepared else prepare(
            parsedPrompt.options.copy(count = 1, concurrency = null),
            options.copy(count = 1, concurrency = null),
        )
        val plan = ImageEndpointPlan.create(single, images.size, mask != null)
        (images + listOfNotNull(mask)).forEach {
            require(it.bytes.isNotEmpty() && it.bytes.size <= MAX_AGENT_IMAGE_BYTES && it.bytes.hasSupportedImageMagic()) {
                "参考图或遮罩为空、过大或不是支持的图片；不会丢弃后继续生成"
            }
        }
        if (mask != null) {
            require(mask.bytes.sniffAgentImageMimeType() == "image/png") { "遮罩必须为 PNG" }
            fun bounds(bytes: ByteArray): Pair<Int, Int> {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
                return b.outWidth to b.outHeight
            }
            val size = bounds(images.single().bytes)
            require(size.first > 0 && size.second > 0 && bounds(mask.bytes) == size) { "遮罩尺寸必须与参考图一致；不会缩放" }
        }
        val request = buildRequest(config, plan, images, mask) // validate the full plan before any billable call
        if (parallelism == null) return executePlan(plan, request, prepared.summary, runController)
        val batchController = AgentRunController()
        val cancellation = runController?.register { batchController.cancel() }
        try {
            val results = ImageBatchRunner.run(batchCount, parallelism, {
                runController?.throwIfCancelled(); batchController.throwIfCancelled()
            }) {
                // Re-plan each native request so an unspecified random seed is not reused
                // across the whole batch. Explicit configured seeds remain unchanged.
                val itemPlan = ImageEndpointPlan.create(single, images.size, mask != null)
                executePlan(itemPlan, buildRequest(config, itemPlan, images, mask), single.summary, batchController)
            }
            val completed = results.mapNotNull { it.getOrNull() }
            check(completed.isNotEmpty()) {
                "IMAGE_BATCH_FAILED：$batchCount 张均未获得结果；未重试" + results.mapNotNull { it.exceptionOrNull()?.message?.take(120) }.distinct().joinToString("；")
            }
            val allImages = completed.flatMap { it.images }
            val report = buildString {
                append("本地分批生成：总张数 $batchCount，并发 ${minOf(batchCount,parallelism)}；每次发送 n=1，失败不重试。\n")
                if (results.any { it.isFailure }) append("IMAGE_BATCH_PARTIAL：${results.count { it.isFailure }} 个请求失败，保留已返回图片。\n")
                if (allImages.size != batchCount) append("IMAGE_COUNT_MISMATCH：请求 $batchCount 张，实际 ${allImages.size} 张。\n")
                results.forEachIndexed { index, result ->
                    append("任务 ${index + 1}：")
                    append(result.getOrNull()?.text ?: "失败：${result.exceptionOrNull()?.message?.take(180)}")
                    append('\n')
                }
            }
            return Result(allImages, report)
        } finally {
            batchController.cancel()
            cancellation?.close()
        }
    }


    private fun imageOutputLine(plan: ImageEndpointPlan.Plan, width: Int, height: Int): String {
        val report = plan.options.dimensionReport(width, height, plan.expectedSize)
        val ratio = plan.options.aspectRatio?.takeUnless { it.isBlank() || it == "auto" }
            ?: reducedRatio(width, height)
        val size = if (width > 0 && height > 0) "${width}x$height" else "未知"
        val problem = when {
            "IMAGE_DIMENSIONS_UNVERIFIED" in report -> " IMAGE_DIMENSIONS_UNVERIFIED"
            "IMAGE_DIMENSIONS_MISMATCH" in report -> " IMAGE_DIMENSIONS_MISMATCH"
            "IMAGE_RESOLUTION_UNVERIFIED" in report -> " IMAGE_RESOLUTION_UNVERIFIED"
            else -> ""
        }
        return "分辨率:$size 比例:$ratio$problem"
    }

    private fun reducedRatio(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "未知"
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val divisor = gcd(width, height)
        return "${width / divisor}:${height / divisor}"
    }

    private fun executePlan(
        plan: ImageEndpointPlan.Plan,
        request: Request,
        @Suppress("UNUSED_PARAMETER") parameterSummary: String,
        controller: AgentRunController?,
    ): Result {
        // One selected endpoint, one billable POST. No chat fallback or parameter-changing retries.
        val parsed = executeGenerationRequest(generationHttpClient, request, controller) { response ->
            val limit = if (plan.kind == ImageEndpointPlan.Kind.NOVELAI) NovelAiImageProtocol.MAX_RESPONSE_BYTES
                else MAX_AGENT_IMAGE_BYTES / 3 * 4 + 1024 * 1024
            val bytes = response.body.byteStream().readGenerationBytes(limit)
            check(response.isSuccessful) {
                val requested = plan.options.toJson().toString()
                "生图请求失败（HTTP ${response.code}，协议 ${plan.kind.name.lowercase()}）\n" +
                    AgentImageGenerationParser.errorMessage(bytes.toString(Charsets.UTF_8), response.code) +
                    "\n本次输出参数：$requested\n未自动重试。服务端未明确原因时，不能断言是尺寸、模型限制或内容审核"
            }
            if (plan.kind == ImageEndpointPlan.Kind.NOVELAI)
                NovelAiImageProtocol.parse(bytes) { controller?.throwIfCancelled() }
            else AgentImageGenerationParser.parse(bytes.toString(Charsets.UTF_8))
        }
        val generated = materialize(parsed, controller)
        check(generated.images.isNotEmpty()) { "响应里没有图片；不会自动重发可能已计费的请求" }
        val reports = generated.images.map { image ->
            imageOutputLine(plan, image.width, image.height)
        }.toMutableList()
        val expectedCount = plan.options.count ?: 1
        if (generated.images.size != expectedCount)
            reports += "IMAGE_COUNT_MISMATCH：请求 $expectedCount 张，实际 ${generated.images.size} 张"
        return generated.copy(text = reports.joinToString("\n") +
            generated.text.takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty())
    }

    private fun buildRequest(
        config: AgentModelClient.ModelConfig,
        plan: ImageEndpointPlan.Plan,
        images: List<InputImage>,
        mask: InputImage?,
    ): Request {
        fun dataUrl(image: InputImage): String = "data:${image.bytes.sniffAgentImageMimeType()};base64," +
            java.util.Base64.getEncoder().encodeToString(image.bytes)
        val parameters = JSONObject(plan.body.toString())
        val body = when (plan.kind) {
            ImageEndpointPlan.Kind.EDITS_MULTIPART -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                parameters.keys().forEach { key -> addFormDataPart(key, parameters.get(key).toString()) }
                images.forEachIndexed { index, image ->
                    val mime = image.bytes.sniffAgentImageMimeType()
                    addFormDataPart("image", "image$index.${AgentImageGenerationParser.extensionForMime(mime)}", image.bytes.toRequestBody(mime.toMediaType()))
                }
                mask?.let { addFormDataPart("mask", "mask.png", it.bytes.toRequestBody("image/png".toMediaType())) }
            }.build()
            ImageEndpointPlan.Kind.EDITS_JSON -> parameters.put("image", JSONObject().put("type", "image_url")
                .put("url", dataUrl(images.single()))).toString().toRequestBody(JSON_MEDIA_TYPE)
            ImageEndpointPlan.Kind.GENERATIONS_IMAGE -> {
                parameters.put("image", dataUrl(images.single()))
                mask?.let { parameters.put("mask", dataUrl(it)) }
                parameters.toString().toRequestBody(JSON_MEDIA_TYPE)
            }
            else -> parameters.toString().toRequestBody(JSON_MEDIA_TYPE)
        }
        return Request.Builder().url(plan.url(config.baseUrl)).headers(requestHeaders(config))
            .header("Accept", if (plan.kind == ImageEndpointPlan.Kind.NOVELAI) "application/zip, image/*" else "application/json")
            .post(body).build()
    }

    private fun generationsBody(config: AgentModelClient.ModelConfig, prompt: String): JSONObject =
        JSONObject().put("model", config.model).put("prompt", prompt)
            .also { mergeRequestExtras(it, config, keepMessages = false) }

    private fun mergeRequestExtras(
        target: JSONObject,
        config: AgentModelClient.ModelConfig,
        keepMessages: Boolean,
    ) {
        val messages = if (keepMessages) target.optJSONArray("messages") else null
        val prompt = if (!keepMessages) target.opt("prompt") else null
        if (config.extraBodyJson.isNotBlank()) {
            val extra = try { JSONObject(config.extraBodyJson) } catch (_: Exception) {
                AgentImageGenerationOptions.invalid("生图额外请求体不是有效 JSON 对象；不会忽略后继续发送")
            }
            extra.keys().forEach { key -> target.put(key, extra.get(key)) }
        }
        RequestBodyMerge.mergeCustomBody(target, config.customBody)
        target.remove("tools")
        target.remove("tool_choice")
        if (keepMessages) {
            target.put("stream", false)
            if (messages != null) target.put("messages", messages)
        } else {
            target.remove("stream")
            target.remove("messages")
            if (prompt != null) target.put("prompt", prompt)
        }
        target.put("model", config.model)
    }

    private fun requestHeaders(config: AgentModelClient.ModelConfig): Headers =
        Headers.Builder()
            .add("Accept", "application/json")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders) }
            .build()

    private fun materialize(parsed: AgentImageGenerationParser.Parsed, controller: AgentRunController?): Result {
        val failures = mutableListOf<Int>()
        val images = parsed.images.mapIndexedNotNull { index, ref ->
            try {
                val bytes = when {
                    ref.bytes != null -> ref.bytes
                    !ref.url.isNullOrBlank() -> download(ref.url, controller)
                    else -> null
                }
                if (bytes == null || bytes.isEmpty() || bytes.size > MAX_AGENT_IMAGE_BYTES) {
                    failures += index + 1
                    return@mapIndexedNotNull null
                }
                val mime = when {
                    bytes.hasSupportedImageMagic() -> bytes.sniffAgentImageMimeType()
                    ref.mimeType.startsWith("image/") -> ref.mimeType
                    else -> { failures += index + 1; return@mapIndexedNotNull null }
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                GeneratedImage(bytes = bytes, mimeType = mime, width = bounds.outWidth, height = bounds.outHeight)
            } catch (failure: Exception) {
                controller?.throwIfCancelled()
                if (failure is java.util.concurrent.CancellationException) throw failure
                failures += index + 1
                null
            }
        }
        val report = if (failures.isEmpty()) "" else
            "IMAGE_DOWNLOAD_PARTIAL：第 ${failures.joinToString()} 张读取失败；已保留其他成功图片，不重试"
        return Result(images = images, text = listOf(parsed.text, report).filter { it.isNotBlank() }.joinToString("\n"))
    }

    private fun download(url: String, controller: AgentRunController?): ByteArray? {
        val request = Request.Builder().url(url).get().build()
        return executeGenerationRequest(downloadClient, request, controller) { response ->
            if (!response.isSuccessful) return@executeGenerationRequest null
            val declared = response.body.contentLength()
            if (declared > MAX_AGENT_IMAGE_BYTES) return@executeGenerationRequest null
            val bytes = response.body.byteStream().readGenerationBytes(MAX_AGENT_IMAGE_BYTES)
            bytes.takeIf { it.isNotEmpty() && it.size <= MAX_AGENT_IMAGE_BYTES }
        }
    }

    private val downloadClient by lazy {
        httpClient.newBuilder().retryOnConnectionFailure(false).readTimeout(60_000, TimeUnit.MILLISECONDS).build()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
