package io.github.mangi.eta.agent.runtime

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/** Runtime 自己裁决可选能力，不能把入口进程提交的布尔值当作授权。 */
internal object AgentRuntimePolicy {
    private val COMPRESSION_EFFORT_PROBE = listOf(
        ReasoningEffort.MINIMAL,
        ReasoningEffort.LOW,
        ReasoningEffort.MEDIUM,
        ReasoningEffort.HIGH,
        ReasoningEffort.XHIGH,
        ReasoningEffort.MAX,
    )

    data class Permissions(
        val terminalTools: Boolean,
        val browserTools: Boolean,
        val screenTools: Boolean = true,
        val deviceDirectTools: Boolean = false,
        val deviceSensitiveReadTools: Boolean = false,
        val deviceSensitiveActionTools: Boolean = false,
        val thinking: Boolean,
    )

    fun permissions(preferences: SharedPreferences?): Permissions =
        Permissions(
            terminalTools = preferences.allowed(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = preferences.allowed(Prefs.Keys.AGENT_BROWSER_TOOLS),
            screenTools = preferences.allowed(Prefs.Keys.AGENT_SCREEN_TOOLS),
            deviceDirectTools = preferences.allowed(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools =
                preferences.allowed(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools =
                preferences.allowed(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            thinking = preferences.allowed(Prefs.Keys.AGENT_THINKING_ENABLED),
        )

    fun withoutOptionalThinking(config: AgentModelClient.ModelConfig): AgentModelClient.ModelConfig {
        if (config.reasoningCapabilities?.mandatory == true) return config
        return constrain(
            config,
            Permissions(
                terminalTools = true,
                browserTools = true,
                screenTools = true,
                deviceDirectTools = true,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
                thinking = false,
            ),
        )
    }

    /**
     * 压缩思考探测顺序：能关先关；否则从 minimal 一档档往上，不沿用对话里的 high/max。
     */
    fun compressionEffortLadder(config: AgentModelClient.ModelConfig): List<ReasoningEffort> {
        val capabilities = config.reasoningCapabilities
        val steps = ArrayList<ReasoningEffort>()
        if (capabilities == null || (capabilities.canDisable && !capabilities.mandatory)) {
            steps += ReasoningEffort.OFF
        }
        steps += COMPRESSION_EFFORT_PROBE
        if (steps.isEmpty()) {
            steps += if (capabilities?.mandatory == true) ReasoningEffort.DEFAULT else ReasoningEffort.OFF
        }
        return steps.distinct()
    }

    fun forCompression(
        config: AgentModelClient.ModelConfig,
        effort: ReasoningEffort = compressionEffortLadder(config).first(),
    ): AgentModelClient.ModelConfig {
        val disabled = constrain(
            config.copy(reasoningEffort = effort, thinkingEnabled = effort.enablesReasoning),
            Permissions(
                terminalTools = true,
                browserTools = true,
                screenTools = true,
                deviceDirectTools = true,
                deviceSensitiveReadTools = true,
                deviceSensitiveActionTools = true,
                thinking = effort.enablesReasoning,
            ),
        )
        return disabled.copy(reasoningEffort = effort, thinkingEnabled = effort.enablesReasoning)
    }

    fun constrain(
        config: AgentModelClient.ModelConfig,
        permissions: Permissions,
    ): AgentModelClient.ModelConfig {
        val requestedEffort = config.effectiveReasoningEffort
        val effectiveEffort = if (permissions.thinking) requestedEffort else ReasoningEffort.OFF
        val thinkingEnabled = effectiveEffort.enablesReasoning
        val constrained = config.copy(
            terminalTools = config.terminalTools && permissions.terminalTools,
            browserTools = config.browserTools && permissions.browserTools,
            screenTools = config.screenTools && permissions.screenTools,
            deviceDirectTools = config.deviceDirectTools && permissions.deviceDirectTools,
            deviceSensitiveReadTools =
                config.deviceSensitiveReadTools && permissions.deviceSensitiveReadTools,
            deviceSensitiveActionTools =
                config.deviceSensitiveActionTools && permissions.deviceSensitiveActionTools,
            thinkingEnabled = thinkingEnabled,
            reasoningEffort = effectiveEffort,
        )
        if (thinkingEnabled) return constrained
        return constrained.copy(
            extraBodyJson = stripThinkingOverrides(constrained.extraBodyJson),
            customBody = constrained.customBody.mapNotNull { body ->
                if (body.key.isThinkingKey()) return@mapNotNull null
                body.copy(value = body.value.stripThinkingOverrides())
            },
        )
    }

    private fun SharedPreferences?.allowed(key: String): Boolean {
        if (this == null) return false
        val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: false
        return runCatching { getBoolean(key, default) }.getOrDefault(false)
    }

    private fun stripThinkingOverrides(raw: String): String {
        if (raw.isBlank()) return raw
        return runCatching {
            JSONObject(raw).also { it.stripThinkingOverrides() }.toString()
        }.getOrDefault(raw)
    }

    private fun JSONObject.stripThinkingOverrides() {
        val keys = keys().asSequence().toList()
        keys.forEach { key ->
            if (key.isThinkingKey()) {
                remove(key)
                return@forEach
            }
            when (val child = opt(key)) {
                is JSONObject -> child.stripThinkingOverrides()
                is org.json.JSONArray -> {
                    for (index in 0 until child.length()) {
                        (child.opt(index) as? JSONObject)?.stripThinkingOverrides()
                    }
                }
            }
        }
    }

    private fun JsonElement.stripThinkingOverrides(): JsonElement =
        when (this) {
            is JsonObject -> JsonObject(
                entries
                    .filterNot { (key, _) -> key.isThinkingKey() }
                    .associate { (key, value) -> key to value.stripThinkingOverrides() }
            )
            is JsonArray -> JsonArray(map { it.stripThinkingOverrides() })
            else -> this
        }

    private fun String.isThinkingKey(): Boolean =
        lowercase().replace('-', '_') in THINKING_OVERRIDE_KEYS

    private val THINKING_OVERRIDE_KEYS = setOf(
        "thinking",
        "thinking_budget",
        "reasoning",
        "reasoning_effort",
        "reasoning_budget",
        "reasoning_max_tokens",
        "enable_thinking",
    )
}
