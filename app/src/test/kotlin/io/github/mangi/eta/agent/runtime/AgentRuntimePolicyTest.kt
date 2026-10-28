package io.github.mangi.eta.agent.runtime

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import java.lang.reflect.Proxy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimePolicyTest {
    @Test fun resumeStripsThinkingOverridesEvenWhenUiAlreadySaysOff() {
        val source = modelConfig(terminalTools = true, browserTools = true, thinking = false).copy(
            reasoningEffort = ReasoningEffort.OFF,
            extraBodyJson = """{"thinking":{"type":"enabled"},"temperature":0.2}""",
            customBody = listOf(CustomBody("reasoning_effort", JsonPrimitive("high"))),
        )
        val result = AgentRuntimePolicy.withoutOptionalThinking(source)
        assertFalse(JSONObject(result.extraBodyJson).has("thinking"))
        assertEquals(0.2, JSONObject(result.extraBodyJson).getDouble("temperature"), 0.0)
        assertTrue(result.customBody.isEmpty())
        assertEquals(ReasoningEffort.OFF, result.reasoningEffort)
    }

    @Test
    fun unavailablePreferencesFailClosed() {
        assertEquals(
            AgentRuntimePolicy.Permissions(
                terminalTools = false,
                browserTools = false,
                screenTools = false,
                thinking = false,
            ),
            AgentRuntimePolicy.permissions(null),
        )
    }

    @Test
    fun missingKeysUseConfiguredCapabilityDefaults() {
        val preferences = booleanPreferences { _, default -> default }

        assertEquals(
            AgentRuntimePolicy.Permissions(
                terminalTools = true,
                browserTools = true,
                screenTools = true,
                deviceTools = true,
                personalTools = true,
                thinking = true,
            ),
            AgentRuntimePolicy.permissions(preferences),
        )
    }

    @Test
    fun preferenceReadFailuresFailClosedForEveryCapability() {
        val preferences = booleanPreferences { _, _ -> error("remote preferences unavailable") }

        assertEquals(
            AgentRuntimePolicy.Permissions(
                terminalTools = false,
                browserTools = false,
                screenTools = false,
                thinking = false,
            ),
            AgentRuntimePolicy.permissions(preferences),
        )
    }

    @Test
    fun callerCannotEnableCapabilitiesThatRuntimeDidNotAuthorize() {
        val constrained = AgentRuntimePolicy.constrain(
            config = modelConfig(
                terminalTools = true,
                browserTools = true,
                thinking = true,
            ).copy(
                deviceTools = true,
                personalTools = true,
            ),
            permissions = AgentRuntimePolicy.Permissions(
                terminalTools = false,
                browserTools = false,
                thinking = false,
            ),
        )

        assertFalse(constrained.terminalTools)
        assertFalse(constrained.browserTools)
        assertFalse(constrained.thinkingEnabled)
        assertEquals(ReasoningEffort.OFF, constrained.reasoningEffort)
        assertFalse(constrained.deviceTools)
        assertFalse(constrained.personalTools)
    }

    @Test
    fun runtimePermissionDoesNotOverrideCallerFeatureSelection() {
        val constrained = AgentRuntimePolicy.constrain(
            config = modelConfig(
                terminalTools = false,
                browserTools = true,
                thinking = false,
            ),
            permissions = AgentRuntimePolicy.Permissions(
                terminalTools = true,
                browserTools = true,
                thinking = true,
            ),
        )

        assertFalse(constrained.terminalTools)
        assertTrue(constrained.browserTools)
        assertFalse(constrained.thinkingEnabled)
    }

    @Test
    fun disabledThinkingCannotBeReenabledThroughCustomRequestBody() {
        val constrained = AgentRuntimePolicy.constrain(
            config = modelConfig(
                terminalTools = false,
                browserTools = false,
                thinking = true,
            ).copy(
                extraBodyJson = """{"thinking":{"budget_tokens":4096},"temperature":0.2}""",
                customBody = listOf(
                    CustomBody("reasoning_effort", JsonPrimitive("high")),
                    CustomBody(
                        "metadata",
                        JsonObject(
                            mapOf(
                                "enable_thinking" to JsonPrimitive(true),
                                "safe" to JsonPrimitive("kept"),
                            )
                        ),
                    ),
                ),
            ),
            permissions = AgentRuntimePolicy.Permissions(
                terminalTools = false,
                browserTools = false,
                thinking = false,
            ),
        )

        assertFalse(constrained.thinkingEnabled)
        val extra = JSONObject(constrained.extraBodyJson)
        assertFalse(extra.has("thinking"))
        assertEquals(0.2, extra.getDouble("temperature"), 0.0)
        assertEquals(listOf("metadata"), constrained.customBody.map { it.key })
        val metadata = constrained.customBody.single().value as JsonObject
        assertFalse("enable_thinking" in metadata)
        assertEquals(JsonPrimitive("kept"), metadata["safe"])
    }


    @Test
    fun withoutOptionalThinkingDisablesReasoningButKeepsTools() {
        val constrained = AgentRuntimePolicy.withoutOptionalThinking(
            modelConfig(terminalTools = true, browserTools = true, thinking = true).copy(
                deviceTools = true,
                reasoningEffort = ReasoningEffort.HIGH,
            ),
        )
        assertTrue(constrained.terminalTools)
        assertTrue(constrained.browserTools)
        assertTrue(constrained.deviceTools)
        assertFalse(constrained.thinkingEnabled)
        assertEquals(ReasoningEffort.OFF, constrained.reasoningEffort)
    }

    @Test
    fun compressionUsesOffWhenReasoningCanBeDisabled() {
        val constrained = AgentRuntimePolicy.forCompression(
            modelConfig(terminalTools = true, browserTools = true, thinking = true).copy(
                reasoningEffort = ReasoningEffort.HIGH,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                    canDisable = true,
                    mandatory = false,
                ),
            ),
        )
        assertFalse(constrained.thinkingEnabled)
        assertEquals(ReasoningEffort.OFF, constrained.reasoningEffort)
        assertTrue(constrained.terminalTools)
    }

    @Test
    fun compressionUsesLowestMandatoryEffortInsteadOfChatPreference() {
        val constrained = AgentRuntimePolicy.forCompression(
            modelConfig(terminalTools = true, browserTools = false, thinking = true).copy(
                reasoningEffort = ReasoningEffort.MAX,
                reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.MAX),
                    canDisable = false,
                    mandatory = true,
                ),
            ),
        )
        assertTrue(constrained.thinkingEnabled)
        assertEquals(ReasoningEffort.MINIMAL, constrained.reasoningEffort)
        assertEquals(
            listOf(
                ReasoningEffort.MINIMAL,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH,
                ReasoningEffort.XHIGH,
                ReasoningEffort.MAX,
            ),
            AgentRuntimePolicy.compressionEffortLadder(
                modelConfig(terminalTools = true, browserTools = false, thinking = true).copy(
                    reasoningCapabilities = ModelReasoningCapabilities(
                        supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
                        canDisable = false,
                        mandatory = true,
                    ),
                ),
            ),
        )
    }

    @Test
    fun withoutOptionalThinkingKeepsMandatoryReasoning() {
        val source = modelConfig(terminalTools = true, browserTools = false, thinking = true).copy(
            reasoningEffort = ReasoningEffort.MEDIUM,
            reasoningCapabilities = ModelReasoningCapabilities(mandatory = true),
        )
        val constrained = AgentRuntimePolicy.withoutOptionalThinking(source)
        assertEquals(source, constrained)
    }

    private fun modelConfig(
        terminalTools: Boolean,
        browserTools: Boolean,
        thinking: Boolean,
    ): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
            terminalTools = terminalTools,
            browserTools = browserTools,
            thinkingEnabled = thinking,
        )

    private fun booleanPreferences(
        getBoolean: (key: String, default: Boolean) -> Boolean,
    ): SharedPreferences =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getBoolean" -> getBoolean(
                    arguments?.get(0) as String,
                    arguments[1] as Boolean,
                )
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "BooleanSharedPreferences"
                else -> error("Unexpected SharedPreferences method: ${method.name}")
            }
        } as SharedPreferences
}
