package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.data.repository.ProviderRepository

internal enum class ModelFeature(val key: String) { VISION("agent_auxiliary_vision"), TITLE("agent_title_model") }

/** Store references only; credentials remain in the provider repository. */
internal data class ModelFeatureSelection(val custom: Boolean, val providerId: String, val modelId: String) {
    suspend fun resolve(generationRole: String? = null): AgentModelClient.ModelConfig? {
        if (providerId.isBlank() || modelId.isBlank()) return null
        val provider = ProviderRepository.providerById(providerId)?.takeIf { it.isEnabled } ?: return null
        val model = provider.models.firstOrNull { it.id == modelId && it.isEnabled } ?: return null
        if (model.supportsSpeechSynthesis) return null
        val compatible = when (generationRole) {
            "image_generation" -> model.supportsImageGeneration
            "video_generation" -> model.supportsVideoGeneration
            null -> !model.supportsImageGeneration && !model.supportsVideoGeneration
            else -> false
        }
        if (!compatible) return null
        return RuntimeConfigRepository.configForProviderAndModel(providerId, modelId, assistant = null)
    }
}

internal object ModelFeaturePreferences {
    /** 标题是否随每轮对话刷新。与 "_custom" 同风格用字符串存：Prefs.isEnabled 对新 key 默认 true，会误开。 */
    private const val TITLE_FOLLOW_KEY = "agent_title_model_follow"

    /** 手动改过标题的会话 id（换行分隔）。持久化后，重启也不会被自动标题覆盖。 */
    private const val MANUAL_TITLE_CONVERSATIONS_KEY = "agent_title_manual_conversations"
    private const val MANUAL_TITLE_LIMIT = 500

    fun selection(feature: ModelFeature) = ModelFeatureSelection(
        Prefs.getString("${feature.key}_custom") == "true",
        Prefs.getString("${feature.key}_provider"),
        Prefs.getString("${feature.key}_model"),
    )
    fun save(feature: ModelFeature, selection: ModelFeatureSelection) {
        Prefs.putString("${feature.key}_provider", selection.providerId)
        Prefs.putString("${feature.key}_model", selection.modelId)
        Prefs.putString("${feature.key}_custom", selection.custom.toString())
    }
    fun visionEnabled() = selection(ModelFeature.VISION).custom

    fun titleFollowsConversation(): Boolean = Prefs.getString(TITLE_FOLLOW_KEY) == "true"

    fun saveTitleFollowsConversation(enabled: Boolean) {
        Prefs.putString(TITLE_FOLLOW_KEY, enabled.toString())
    }

    fun manuallyRenamedConversations(): Set<String> =
        Prefs.getString(MANUAL_TITLE_CONVERSATIONS_KEY)
            .split('\n')
            .filter { it.isNotBlank() }
            .toSet()

    fun markManuallyRenamed(conversationId: String) {
        if (conversationId.isBlank()) return
        // 已存在时保持原顺序，只追加，避免每次改名重写整串。
        val current = manuallyRenamedConversations()
        if (conversationId in current) return
        val next = (current + conversationId).toList().takeLast(MANUAL_TITLE_LIMIT)
        Prefs.putString(MANUAL_TITLE_CONVERSATIONS_KEY, next.joinToString("\n"))
    }

    fun forgetManuallyRenamed(conversationId: String) {
        val current = manuallyRenamedConversations()
        if (conversationId !in current) return
        Prefs.putString(MANUAL_TITLE_CONVERSATIONS_KEY, (current - conversationId).joinToString("\n"))
    }
}
