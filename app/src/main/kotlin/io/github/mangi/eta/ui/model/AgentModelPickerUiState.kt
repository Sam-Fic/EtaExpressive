package io.github.mangi.eta.ui.model

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.text.NumberFormat
import java.util.Locale

@Immutable
internal data class AgentModelPickerUiState(
    val providerGroups: List<AgentModelProviderGroupUi> = emptyList(),
    val selectedModel: AgentModelOptionUi? = null,
    val isChanging: Boolean = false,
    // 生成中改选的目标：正在跑的这一轮仍用 [selectedModel]（运行配置按轮冻结），本项在终态后才落到会话。
    val pendingModel: AgentModelOptionUi? = null,
    val pendingAssistantId: String? = null,
)

@Immutable
internal data class AgentModelProviderGroupUi(
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val models: List<AgentModelOptionUi>,
)

@Immutable
internal data class AgentModelOptionUi(
    val id: String,
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val modelId: String,
    val displayName: String,
    val contextWindow: Int?,
    val preferredReasoningEffort: ReasoningEffort? = null,
    val supportsVision: Boolean = true,
    val supportsImageGeneration: Boolean = false,
    val supportsVideo: Boolean = false,
    val supportsVideoGeneration: Boolean = false,
    val gptSpeedSupported: Boolean = false,
    val requestEndpoint: io.github.mangi.eta.agent.model.EndpointKind = io.github.mangi.eta.agent.model.EndpointKind.CHAT_COMPLETIONS,
)

@Immutable
internal data class AgentContextUsageUi(
    val contextTokens: Int?,
    val contextWindow: Int?,
    val estimated: Boolean = false,
    val firstTurn: Boolean = false,
) {
    val progress: Float?
        get() = contextUsageProgress(contextTokens, contextWindow)
}

internal object AgentModelPickerProjector {
    fun project(
        providers: List<ProviderSetting>,
        selectedProviderId: String?,
        selectedModelId: String?,
        includeSpeechModels: Boolean = false,
        speechOnly: Boolean = false,
    ): AgentModelPickerUiState {
        val enabledProviders = providers
            .asSequence()
            .filter(ProviderSetting::isEnabled)
            .filter { !speechOnly || SpeechSynthesisModels.isReadAloudProvider(it) }
            .sortedBy(ProviderSetting::sortOrder)
            .toList()
        val selectedProvider = enabledProviders.firstOrNull { it.id == selectedProviderId }
        val selectedModel = selectedProvider?.let { provider ->
            listedModels(provider, includeSpeechModels || speechOnly, speechOnly)
                .firstOrNull { it.id == selectedModelId && it.isEnabled }
                ?.let { model -> provider.toOption(model) }
        }
        val groups = enabledProviders
            .asSequence()
            .filter { it.apiKey.isNotBlank() }
            .filter { includeSpeechModels || speechOnly || !SpeechSynthesisModels.isSpeechOnlyProvider(it) }
            .mapNotNull { provider ->
                val sourceType = ProviderSourceRegistry.resolve(provider)
                val models = listedModels(provider, includeSpeechModels || speechOnly, speechOnly)
                    .asSequence()
                    .filter { it.isEnabled }
                    .map { model -> provider.toOption(model) }
                    .toList()
                models.takeIf(List<*>::isNotEmpty)?.let {
                    AgentModelProviderGroupUi(
                        providerId = provider.id,
                        providerName = provider.name,
                        providerSourceType = sourceType,
                        models = models,
                    )
                }
            }
            .toList()
        return AgentModelPickerUiState(
            providerGroups = groups,
            selectedModel = selectedModel,
        )
    }


    private fun listedModels(provider: ProviderSetting, includeSpeechModels: Boolean, speechOnly: Boolean): List<Model> {
        val seen = HashSet<String>()
        return SpeechSynthesisModels.mergeCatalog(provider).filter { model ->
            if (!model.isEnabled) return@filter false
            if (speechOnly && !SpeechSynthesisModels.isReadAloudModel(model, provider)) return@filter false
            if (!includeSpeechModels && model.supportsSpeechSynthesis) return@filter false
            seen.add(model.modelId.lowercase())
        }
    }

    private fun ProviderSetting.toOption(model: Model): AgentModelOptionUi =
        AgentModelOptionUi(
            id = model.id,
            providerId = id,
            providerName = name,
            providerSourceType = ProviderSourceRegistry.resolve(this),
            modelId = model.modelId,
            displayName = model.displayName.ifBlank { model.modelId },
            contextWindow = model.effectiveContextWindow,
            preferredReasoningEffort = model.preferredReasoningEffort,
            gptSpeedSupported = io.github.mangi.eta.data.model.supportsGptSpeedBinding(this, model),
            supportsVision = model.supportsVision,
            supportsImageGeneration = model.supportsImageGeneration,
            supportsVideo = model.supportsVideo,
            supportsVideoGeneration = model.supportsVideoGeneration,
            requestEndpoint = when (this) {
                is io.github.mangi.eta.data.model.AnthropicProviderSetting -> io.github.mangi.eta.agent.model.EndpointKind.ANTHROPIC_MESSAGES
                else -> {
                    val mode = when (this) {
                        is io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting -> endpointMode
                        is io.github.mangi.eta.data.model.CustomProviderSetting -> endpointMode
                        else -> ""
                    }
                    if (mode == io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES)
                        io.github.mangi.eta.agent.model.EndpointKind.RESPONSES
                    else io.github.mangi.eta.agent.model.EndpointKind.CHAT_COMPLETIONS
                }
            },
        )
}

internal fun latestContextUsage(
    messages: List<AgentChatMessageUi>,
    selectedModel: AgentModelOptionUi?,
): AgentContextUsageUi = AgentContextUsageUi(
    contextTokens = latestBilledContextTokens(messages),
    contextWindow = selectedModel?.contextWindow,
)

/**
 * Reads a bill only within a still-valid message context. Callers must not use
 * message bills to restore occupancy after model/history invalidation.
 * A compaction marker's baseline is local metadata, never cloud occupancy.
 */
internal fun latestBilledContextTokens(messages: List<AgentChatMessageUi>): Int? {
    val compactIndex = messages.indexOfLast { it is ContextCompactedMessageUi }
    val marker = messages.getOrNull(compactIndex) as? ContextCompactedMessageUi
    fun runPrefix(message: AgentMessageUi): String? =
        if (messageRoundFromId(message.id) == null) null
        else message.id.substringBeforeLast("-").substringBeforeLast("-")
    // Rounds restart in a new run: apply the marker only to its preceding run.
    val compactedRun = if (compactIndex >= 0) messages.take(compactIndex)
        .filterIsInstance<AgentMessageUi>().lastOrNull()?.let(::runPrefix) else null
    for (index in messages.lastIndex downTo (compactIndex + 1)) {
        val message = messages[index] as? AgentMessageUi ?: continue
        val round = messageRoundFromId(message.id)
        if (compactedRun != null && runPrefix(message) == compactedRun &&
            round != null && round < (marker?.resumeRound ?: 0)) continue
        windowTokensFromUsage(message.usage)?.let { return it }
    }
    return null
}

internal fun countUnbilledTail(
    messages: List<AgentChatMessageUi>,
    startIndex: Int,
    resumeRound: Int = 0,
    billedIndex: Int = -1,
): Int {
    var tail = 0
    for (index in startIndex until messages.size) {
        val message = messages[index]
        if (resumeRound > 0) {
            val round = messageRoundFromId(message.id)
            if (round == null || round < resumeRound) continue
        }
        if (index == billedIndex && message is AgentMessageUi) {
            tail += countAssistantLiveTokens(message)
            continue
        }
        if (index < billedIndex && message is UserMessageUi) continue
        tail += countLiveMessageTokens(message)
    }
    return tail
}

internal fun countUncommittedLiveTokens(messages: List<AgentChatMessageUi>): Int {
    val lastCompletedAssistant = messages.indices.lastOrNull { index ->
        val message = messages[index]
        message is AgentMessageUi && !message.isStreaming
    } ?: -1
    var tail = 0
    for (index in lastCompletedAssistant + 1 until messages.size) {
        val message = messages[index]
        if (message is UserMessageUi) continue
        tail += countLiveMessageTokens(message)
    }
    return tail
}

internal fun countLiveMessageTokens(message: AgentChatMessageUi): Int =
    when (message) {
        is UserMessageUi -> {
            if (message.content.isBlank() && message.images.isEmpty()) {
                0
            } else {
                AgentContextBudget.countCurrentTurn(message.content, emptyList()) +
                    AgentContextBudget.countStoredImages(message.images.size)
            }
        }
        is AgentMessageUi -> countAssistantLiveTokens(message)
        is ThinkingMessageUi -> {
            if (message.content.isBlank()) 0
            else AgentContextBudget.countCurrentTurn(message.content, emptyList())
        }
        is ToolActivityMessageUi -> {
            val text = buildString {
                if (message.argumentsSummary.isNotBlank()) append(message.argumentsSummary)
                message.command?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
                message.resultSummary?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
            }
            if (text.isBlank()) 0 else AgentContextBudget.countCurrentTurn(text, emptyList())
        }
        else -> 0
    }

internal fun countAssistantLiveTokens(message: AgentMessageUi): Int {
    if (message.content.isBlank()) return 0
    val estimated = AgentContextBudget.countCurrentTurn(message.content, emptyList())
    val billedOutput = message.usage?.outputTokens ?: 0
    return when {
        windowTokensFromUsage(message.usage) == null -> estimated
        message.isStreaming -> (estimated - billedOutput).coerceAtLeast(0)
        else -> 0
    }
}

// 消息 id 轮次解析的三条 Pattern 提为常量：messageRoundFromId 在全消息循环与状态同步路径上
// 被逐条调用，每次调用现场 Pattern.compile 是纯浪费（对标 MessageTimestamp.kt 的做法）。
private val MessageIdThinkingRoundPattern = Regex("""-thinking-(\d+)-""")
private val MessageIdToolRoundPattern = Regex("""-tool-(\d+)-""")
private val MessageIdHostedRoundPattern = Regex("""-hosted-(\d+)-""")

internal fun messageRoundFromId(id: String): Int? {
    MessageIdThinkingRoundPattern.find(id)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    MessageIdToolRoundPattern.find(id)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    MessageIdHostedRoundPattern.find(id)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    if (id.startsWith("assistant-run-")) {
        val parts = id.split("-")
        if (parts.size >= 9) return parts[parts.size - 2].toIntOrNull()
    }
    return null
}

internal fun windowTokensFromUsage(usage: TokenUsageUi?): Int? {
    if (usage == null || usage.isEmpty) return null
    usage.inputTokens?.let { return it.takeIf { tokens -> tokens > 0 } }
    // A total accompanying output without prompt usage is not measured input.
    if (usage.outputTokens != null || usage.reasoningTokens != null) return null
    return usage.contextTokens?.takeIf { it > 0 }
}

/** Ring display: only a real cloud measurement moves the ring. Unmeasured contexts are never
 * rendered from local counts, drafted text, images or learned sample/receipt ratios: a first
 * turn shows a display-only "0k", every other unmeasured state stays "未知" with an unmoved ring. */
internal fun liveContextUsage(
    billedContextTokens: Int? = null,
    activeRunContextWindow: Int? = null,
    selectedModel: AgentModelOptionUi? = null,
    contextDisplayPolicy: ContextDisplayPolicy = ContextDisplayPolicy(),
): AgentContextUsageUi {
    // An in-flight run keeps the window it was launched with, so a mid-run settings
    // change must not restate the percentage of a request that never saw the new limit.
    val window = activeRunContextWindow?.takeIf { it > 0 } ?: selectedModel?.contextWindow
    if (billedContextTokens != null && billedContextTokens > 0) {
        return AgentContextUsageUi(billedContextTokens, window)
    }
    if (contextDisplayPolicy.awaitingReceipt) return AgentContextUsageUi(null, window)
    return AgentContextUsageUi(null, window, firstTurn = contextDisplayPolicy.firstTurn)
}

/** Predict the next cloud input: a validated receipt is the full prompt baseline, including cache.
 * Keep an unchanged baseline marked as actual so the ring does not show a false estimate. */
internal fun compressionContextUsage(
    history: List<AgentModelClient.ConversationMessage>,
    currentInput: String,
    pendingImages: List<PendingImageUi>,
    selectedModel: AgentModelOptionUi?,
    pendingFileReferences: List<PendingFileReferenceUi> = emptyList(),
    pendingConversationMentions: List<PendingConversationMentionUi> = emptyList(),
    historyTokenCount: Int? = null,
    billedContextTokens: Int? = null,
    requestOverheadTokens: Int = 0,
    billedOverheadTokens: Int? = null,
    billedHistoryTokens: Int? = null,
    localHistoryTokenCount: Int? = null,
    activeRunContextWindow: Int? = null,
    projectedContextTokens: Int? = null,
): AgentContextUsageUi {
    if (billedContextTokens == null || billedContextTokens <= 0 ||
        billedHistoryTokens == null || billedOverheadTokens == null) {
        // Legacy cloud receipts keep the ring accurate, but lack the calibration
        // needed for a safe delta. Only the silent budget falls back to a full estimate.
        // Unknown UI must not remove the conservative internal budget or change send blocking.
        val draft = draftContextTokens(currentInput, pendingImages, selectedModel, pendingFileReferences, pendingConversationMentions)
        val local = (localHistoryTokenCount ?: io.github.mangi.eta.agent.model.AgentRequestTokenEstimate.history(
            history, selectedModel?.supportsVision == true, selectedModel?.supportsVideo == true)).toLong() +
            requestOverheadTokens.coerceAtLeast(0) + draft
        val floor = (billedContextTokens?.coerceAtLeast(0)?.toLong() ?: 0L) + draft
        return AgentContextUsageUi(maxOf(local, floor).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            activeRunContextWindow?.takeIf { it > 0 } ?: selectedModel?.contextWindow, estimated = true)
    }
    // Both calibration snapshots belong to the validated cloud receipt.
    val delta = billedHistoryTokens?.let { baseline ->
        (historyTokenCount ?: history.sumOf { AgentContextBudget.countMessage(it) }).toLong() - baseline
    } ?: 0L
    val fixedDelta = billedOverheadTokens?.let { requestOverheadTokens.toLong() - it } ?: 0L
    val draft = draftContextTokens(currentInput, pendingImages, selectedModel, pendingFileReferences, pendingConversationMentions)
    val projected = (billedContextTokens.toLong() + delta + fixedDelta + draft)
        .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    return AgentContextUsageUi(
        projected,
        activeRunContextWindow?.takeIf { it > 0 } ?: selectedModel?.contextWindow,
        estimated = projected != billedContextTokens,
    )
}

private fun draftContextTokens(
    input: String, pendingImages: List<PendingImageUi>, selectedModel: AgentModelOptionUi?,
    files: List<PendingFileReferenceUi>, mentions: List<PendingConversationMentionUi>,
): Int {
    val vision = selectedModel?.supportsVision == true
    val video = selectedModel?.supportsVideo == true
    fun accepted(image: PendingImageUi): Boolean = if (image.isVideo) video || vision else vision
    val imageFiles = pendingImages.mapIndexedNotNull { index, image ->
        if (accepted(image)) null else AgentFileReference(displayName = image.cacheDisplayName(index),
            absolutePath = "/cache/chat-image-${index + 1}", kind = AgentFileReferenceKind.File)
    }
    val prompt = AgentFileReferencePromptCodec.format(input, files.map { it.reference } + imageFiles, mentions.toMentionedConversations())
    val images = pendingImages.filter(::accepted).map { it.toOutboundModelImage(video) }
    return if (prompt.isEmpty() && images.isEmpty()) 0 else AgentContextBudget.countCurrentTurn(prompt, images)
}

internal fun PendingImageUi.toLiveModelImage(): AgentModelClient.ModelImage =
    if (isVideo) {
        AgentModelClient.ModelImage(
            reference = uri,
            mimeType = mimeType,
            bytes = byteSize,
            width = widthPx,
            height = heightPx,
            source = uri,
        )
    } else {
        AgentModelClient.ModelImage(
            reference = dataUrl,
            mimeType = mimeType,
            bytes = dataUrl.length,
            // 预览 dataUrl 只有 1024 档；估算必须用 Attach 时的原图宽高，
            // 由 AgentContextBudget 统一按 1920/2.07MP 夹取后才等于送模型的视觉 token。
            width = widthPx,
            height = heightPx,
            source = uri,
        )
    }

internal fun PendingImageUi.toOutboundModelImage(supportsVideo: Boolean): AgentModelClient.ModelImage =
    if (isVideo && !supportsVideo) {
        // 视频按预览帧发给图片模型：这里的尺寸是视频分辨率而非预览帧尺寸，
        // 不能拿来算视觉 token，保持按字节兜底。
        AgentModelClient.ModelImage(
            reference = dataUrl,
            mimeType = "image/jpeg",
            bytes = dataUrl.length,
            source = uri,
        )
    } else {
        toLiveModelImage()
    }

/**
 * 真正发给模型的附件：从原始引用（[PendingImageUi.uri]）重编码为「尽量无损」档，
 * 而不是复用 [dataUrl] 这份 1024 有损预览。编码失败、视频或旧草稿缺少原始引用时，
 * 回退 [toOutboundModelImage]（即预览），保证老数据仍可发送。**必须在 IO 线程调用。**
 */
internal fun PendingImageUi.toRequestModelImage(
    context: Context,
    supportsVideo: Boolean,
): AgentModelClient.ModelImage {
    if (isVideo) return toOutboundModelImage(supportsVideo)
    val encoded = AgentImageCodec.attachmentModelFromReference(context, uri)
    return encoded ?: toOutboundModelImage(supportsVideo)
}

internal fun PendingImageUi.cacheDisplayName(index: Int): String {
    if (isVideo) {
        val extension = io.github.mangi.eta.agent.media.AgentVideoCodec.extensionForMime(mimeType, uri)
        return "chat-video-${index + 1}.$extension"
    }
    val extension = when {
        mimeType.contains("png", ignoreCase = true) -> "png"
        mimeType.contains("webp", ignoreCase = true) -> "webp"
        mimeType.contains("gif", ignoreCase = true) -> "gif"
        else -> "jpg"
    }
    return "chat-image-${index + 1}.$extension"
}

internal fun isContextWindowExceeded(
    usage: AgentContextUsageUi,
    thresholdPercent: Float = 0.99f,
): Boolean {
    val tokens = usage.contextTokens ?: return false
    val window = usage.contextWindow ?: return false
    return window > 0 && tokens.toFloat() / window.toFloat() >= thresholdPercent
}

internal fun shouldBlockSendForContextWindow(
    autoCompressEnabled: Boolean,
    usage: AgentContextUsageUi,
): Boolean = !autoCompressEnabled && isContextWindowExceeded(usage)

internal fun contextUsageProgress(contextTokens: Int?, contextWindow: Int?): Float? {
    if (contextTokens == null || contextTokens < 0 || contextWindow == null || contextWindow <= 0) {
        return null
    }
    return (contextTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
}

internal fun formatContextUsage(
    usage: AgentContextUsageUi,
    noUsageText: String = "No conversation context yet",
    noLimitText: String = "The current model does not provide a context limit",
    // 默认值与默认语言（values/）一致；生产调用方均传 R.string.amp_unknown，中文由资源提供。
    unknownText: String = "Unknown",
    locale: Locale = Locale.getDefault(),
): String {
    // A first-turn "0k" is a display-only label, not a measured zero.
    // Unmeasured states keep null occupancy/progress and omit a percentage;
    // measured values and trusted estimates are formatted separately below.
    val measured = usage.contextTokens
    val window = usage.contextWindow
    if (measured == null) {
        // Only the numerator is unknown; the configured window the ring is measured against is
        // already known, so keep it in the same denominator format a measured reading uses.
        // The ratio stays null, so the ring is still shown unmoved for both unmeasured states.
        val state = if (usage.firstTurn) "0k" else unknownText
        if (window == null || window <= 0) return state
        return "$state / ${formatCompactTokenCount(window, locale)} tokens"
    }
    val tokens = measured.coerceAtLeast(0)
    val tokenText = (if (usage.estimated) "≈" else "") +
        (if (tokens == 0) "0K" else formatCompactTokenCount(tokens, locale))
    if (window == null || window <= 0) return "$tokenText tokens" + 10.toChar() + noLimitText
    val percentFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    return "$tokenText / ${formatCompactTokenCount(window, locale)} tokens · " +
        "${percentFormat.format(tokens.toDouble() / window * 100.0)}%"
}

internal fun formatCompactTokenCount(value: Int, locale: Locale = Locale.getDefault()): String {
    val absolute = kotlin.math.abs(value.toLong())
    val divisor = when {
        absolute >= 1_000_000 -> 1_000_000.0
        absolute >= 1_000 -> 1_000.0
        else -> return NumberFormat.getIntegerInstance(locale).format(value)
    }
    val suffix = if (divisor == 1_000_000.0) "M" else "K"
    val formatted = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
        isGroupingUsed = false
    }.format(value / divisor)
    return "$formatted$suffix"
}
