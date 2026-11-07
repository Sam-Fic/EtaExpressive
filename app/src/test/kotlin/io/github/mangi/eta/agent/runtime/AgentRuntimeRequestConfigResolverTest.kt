package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class AgentRuntimeRequestConfigResolverTest {
    @Test
    fun etaVoiceRequestKeepsEntryConfigForParityWithInAppChat() {
        val entryConfig = modelConfig("entry-key", ReasoningEffort.OFF)
        val request = request(
            source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
            config = entryConfig,
        )

        val resolved = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(
            request,
            modelConfig("runtime-key", ReasoningEffort.HIGH),
        )

        // 语音与应用内共用同一份由发起方组装的配置；这里再替换会抹掉工具开关与助手人格。
        assertFalse(AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request))
        assertSame(request, resolved)
        assertSame(entryConfig, resolved.config)
    }

    @Test
    fun externalAssistantRequestKeepsEntryConfig() {
        val entryConfig = modelConfig("entry-key", ReasoningEffort.OFF)
        val request = request(source = "breeno", config = entryConfig)

        val resolved = AgentRuntimeRequestConfigResolver.applyRuntimeConfig(
            request,
            modelConfig("runtime-key", ReasoningEffort.HIGH),
        )

        assertFalse(AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request))
        assertSame(request, resolved)
        assertSame(entryConfig, resolved.config)
    }

    private fun request(
        source: String,
        config: AgentModelClient.ModelConfig,
    ): AgentRuntimeWire.RunRequest = AgentRuntimeWire.RunRequest(
        runId = "run-1",
        prompt = "测试",
        config = config,
        images = emptyList(),
        handoff = AgentRuntimeWire.EntryHandoff(
            id = "handoff-1",
            source = source,
            payload = AgentExternalArchivePayload(
                userText = "测试",
                conversationKey = "conversation-1",
                title = "测试",
            ).toJson(),
        ),
    )

    private fun modelConfig(
        apiKey: String,
        reasoningEffort: ReasoningEffort,
    ): AgentModelClient.ModelConfig = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = apiKey,
        model = "test-model",
        systemPrompt = "",
        thinkingEnabled = reasoningEffort.enablesReasoning,
        reasoningEffort = reasoningEffort,
    )
}
