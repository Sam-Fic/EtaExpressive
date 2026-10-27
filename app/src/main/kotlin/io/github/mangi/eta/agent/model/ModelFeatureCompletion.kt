package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray

/** Bounded one-shot requests. No tool execution, no fallback to a different provider. */
internal object ModelFeatureCompletion {
    fun complete(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        controller: AgentRunController,
        sessionId: String,
        timeoutMs: Long = 60_000,
        outputLimit: Int = 2048,
        providerOverride: AgentProviderClient? = null,
        usageConversationId: String = sessionId,
        onErrorReconnect: (io.github.mangi.eta.agent.runtime.AgentEvent.ErrorReconnectChanged) -> Unit = {},
    ): String {
        val owner = Thread.currentThread()
        val child = AgentRunController()
        val binding = controller.register(interruptible = true) { child.cancel() }
        val enforceTotalDeadline = io.github.mangi.eta.data.model.ErrorReconnectPolicy
            .fromPersistedValue(config.errorReconnectPolicy) == io.github.mangi.eta.data.model.ErrorReconnectPolicy.NONE
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val watchdog = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    if (owner.isInterrupted || controller.isCancelled || controller.isPaused ||
                        controller.hasPendingSteering || (enforceTotalDeadline && System.nanoTime() >= deadline)) {
                        child.cancel()
                        break
                    }
                    Thread.sleep(50)
                }
            } catch (_: InterruptedException) { }
        }, "eta-model-feature-timeout").apply { isDaemon = true }
        try {
            require(timeoutMs > 0 && outputLimit > 0)
            require(config.baseUrl.isNotBlank() && config.apiKey.isNotBlank() && config.model.isNotBlank()) { "辅助模型配置不完整" }
            controller.throwIfCancelled()
            watchdog.start()
            val requestConfig = io.github.mangi.eta.agent.runtime.AgentRuntimePolicy.withoutOptionalThinking(config).copy(
                hostedWebSearchEnabled = false,
                terminalTools = false, browserTools = false, screenTools = false,
                deviceDirectTools = false, deviceSensitiveReadTools = false, deviceSensitiveActionTools = false,
                summaryOutputLimit = outputLimit,
            )
            val provider = providerOverride ?: ProviderClientFactory.getClient(requestConfig)
            val response = AgentModelRetry().complete(
                initialRound = 1,
                request = ProviderRequest(requestConfig, messages, JSONArray(), sessionId, usageConversationId),
                provider = provider,
                controller = child,
                onEvent = { event ->
                    if (event is io.github.mangi.eta.agent.runtime.AgentEvent.ErrorReconnectChanged) onErrorReconnect(event)
                },
                onProviderEvent = { _, _ -> },
                discardAttemptReasoning = {},
            ).response
            controller.throwIfCancelled()
            check(!child.isCancelled && !owner.isInterrupted) { "辅助模型请求已取消或超时" }
            require(response.stopReason == AssistantStopReason.END_TURN) { "辅助模型未完整返回正文" }
            require((response.assistantMessage.optJSONArray("tool_calls")?.length() ?: 0) == 0) { "辅助模型返回了工具调用" }
            return response.assistantMessage.optString("content").trim()
                .also { require(it.isNotBlank() && it != "null") { "辅助模型返回了空正文" } }
        } catch (failure: io.github.mangi.eta.agent.runtime.AgentRunCancelledException) {
            // Preserve the feature timeout/steering contract, without hiding a user stop.
            if (!controller.isCancelled && !owner.isInterrupted) {
                throw IllegalStateException("辅助模型请求已取消或超时", failure)
            }
            throw failure
        } finally {
            watchdog.interrupt()
            child.cancel()
            binding.close()
        }
    }
}
