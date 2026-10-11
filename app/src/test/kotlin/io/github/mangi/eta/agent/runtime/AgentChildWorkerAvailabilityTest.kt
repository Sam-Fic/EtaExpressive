package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.AgentRevisionRuntimeSuffix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class AgentChildWorkerAvailabilityTest {
    private val profile = SubAgentProfile("stable-worker", "Worker", role = "review",
        providerId = "provider", modelId = "explicit-selection")
    private val provider = OpenAiCompatibleProviderSetting("provider", "Provider", "https://example.invalid/v1",
        apiKey = "must-not-leak", models = listOf(Model("explicit-selection", "explicit-api-model", "Selected")))
    private val model = RuntimeConfigRepository.buildRuntimeConfig(provider, provider.models.single())
    private val available = ChildTaskConfigPolicy.Candidate(
        ChildTaskConfigPolicy.WorkerKey("owner", profile.id, profile.role),
        ChildTaskConfigPolicy.Availability.AVAILABLE,
        "private-revision-token",
        ChildWorkerConfigResolver.Configuration(profile, model),
    )
    private val unavailable = ChildTaskConfigPolicy.Candidate<ChildWorkerConfigResolver.Configuration>(
        ChildTaskConfigPolicy.WorkerKey("owner", "missing-worker", "research"),
        ChildTaskConfigPolicy.Availability.MODEL_UNAVAILABLE,
    )

    @Test fun coordinatorUsesOnlyAvailableExactSelectionInOriginalOrder() {
        val configured = AgentChildWorkerAvailability.configuredChildren(listOf(unavailable, available))
        assertEquals(1, configured.size)
        assertSame(profile, configured.single().first)
        assertSame(model, configured.single().second)
        assertEquals("explicit-api-model", configured.single().second.model)
    }

    @Test fun safePromptRetainsUnavailableReasonButNotCredentialsOrRevision() {
        val prompt = AgentChildWorkerAvailability.appendToPrompt("question", listOf(unavailable, available))
        assertTrue(prompt.startsWith("question"))
        assertTrue(prompt.contains("missing-worker"))
        assertTrue(prompt.contains("MODEL_UNAVAILABLE"))
        assertTrue(prompt.contains("stable-worker"))
        assertFalse(prompt.contains("must-not-leak"))
        assertFalse(prompt.contains("private-revision-token"))
        assertFalse(prompt.contains("https://example.invalid"))
    }

    private fun dispatchDescription(prompt: String): JSONObject = JSONObject(
        prompt.substringAfter("\n{", "").let { "{" + it.substringBefore("\n[/本轮子代理派发配置可用性]") })

    @Test fun promptUsesCurrentCandidatesAndMatchingOrdinarySlots() {
        val changedUnavailable = ChildTaskConfigPolicy.Candidate<ChildWorkerConfigResolver.Configuration>(
            available.worker, ChildTaskConfigPolicy.Availability.MODEL_UNAVAILABLE)
        val alternate = ChildTaskConfigPolicy.Candidate(
            ChildTaskConfigPolicy.WorkerKey("owner", "alternate-worker", profile.role),
            ChildTaskConfigPolicy.Availability.AVAILABLE, "alternate-private-revision",
            ChildWorkerConfigResolver.Configuration(profile.copy(id = "alternate-worker"), model))
        val plan = ChildTaskOrdinaryDispatchSelection.plan(listOf(changedUnavailable, alternate))
        val prompt = AgentChildWorkerAvailability.appendToPrompt("question", plan)
        assertTrue(AgentRevisionRuntimeSuffix.matches(prompt, "question"))
        val dto = dispatchDescription(prompt)
        assertEquals("CURRENT", dto.getString("ordinary_configuration_source"))
        assertEquals("CURRENT_CONFIGURATION_AVAILABLE", dto.getString("ordinary_configuration_code"))
        val ordinary = dto.getJSONArray("ordinary")
        assertEquals(2, ordinary.length())
        assertEquals("stable-worker", ordinary.getJSONObject(0).getString("agent_id"))
        assertFalse(ordinary.getJSONObject(0).has("worker"))
        assertEquals(false, ordinary.getJSONObject(0).getBoolean("configuration_available"))
        assertEquals("alternate-worker", ordinary.getJSONObject(1).getString("agent_id"))
        assertEquals(1, ordinary.getJSONObject(1).getInt("worker"))
        val replacement = dto.getJSONArray("explicit_replacement")
        assertEquals("MODEL_UNAVAILABLE", replacement.getJSONObject(0).getString("configuration_code"))
        assertEquals("alternate-worker", replacement.getJSONObject(1).getString("agent_id"))
        assertFalse(replacement.getJSONObject(1).has("worker"))
        assertTrue(prompt.contains("替换请用稳定 agent_id"))
        listOf("must-not-leak", "private-revision-token", "alternate-private-revision", "https://example.invalid")
            .forEach { assertFalse(prompt.contains(it)) }
    }

    @Test fun promptReportsUnavailableCurrentWithoutInventingOrdinarySlots() {
        val current = ChildTaskOrdinaryDispatchSelection.plan(listOf(unavailable))
        val prompt = AgentChildWorkerAvailability.appendToPrompt("question", current)
        assertTrue(AgentRevisionRuntimeSuffix.matches(prompt, "question"))
        val disabled = dispatchDescription(prompt)
        assertEquals("CURRENT", disabled.getString("ordinary_configuration_source"))
        assertEquals("NEW_CONFIGURATION_UNAVAILABLE", disabled.getString("ordinary_configuration_code"))
        assertFalse(disabled.getJSONArray("ordinary").getJSONObject(0).has("worker"))
    }

    @Test fun absentCandidatesDoNotChangePromptOrInventFallback() {
        assertEquals("question", AgentChildWorkerAvailability.appendToPrompt("question", emptyList()))
        assertTrue(AgentChildWorkerAvailability.configuredChildren(listOf(unavailable)).isEmpty())
    }
}
