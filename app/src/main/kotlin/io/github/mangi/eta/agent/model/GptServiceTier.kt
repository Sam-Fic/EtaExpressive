package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.isGptSpeedModel
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import org.json.JSONObject

/** Apply only to final OpenAI text request bodies, after custom-body/model merging. */
internal object GptServiceTier {
    const val UNSUPPORTED_CODEX_TIER = "UNSUPPORTED_CODEX_SERVICE_TIER"

    fun apply(request: JSONObject, config: AgentModelClient.ModelConfig) {
        val mode = config.gptSpeedMode ?: return // Preserve legacy/manual service_tier settings.
        val actualModel = request.opt("model") as? String ?: return
        if (!isGptSpeedModel(actualModel)) return
        // Codex calls the UI option Fast, but its request_value() is "priority".
        // Explicit Normal omits the tier; relay-specific values must not leak to Codex.
        val tier = if (ProviderConnectionProfile.forAuthMode(config.authMode) == ProviderConnectionProfile.OPENAI_CODEX) {
            when (mode) {
                GptSpeedMode.NORMAL -> null
                GptSpeedMode.FAST -> "priority"
                GptSpeedMode.ULTRA_FAST -> throw AgentModelFailure(
                    UNSUPPORTED_CODEX_TIER, false,
                    "当前官方 Codex 适配不支持极速（ULTRA_FAST），请选择普通或快速（FAST）",
                )
            }
        } else {
            when (mode) {
                GptSpeedMode.NORMAL -> "default"
                GptSpeedMode.FAST -> "fast"
                GptSpeedMode.ULTRA_FAST -> "ultrafast"
            }
        }
        if (tier == null) request.remove("service_tier") else request.put("service_tier", tier)
        // Do not change reasoning/model or silently retry with a different tier on API errors.
    }
}
