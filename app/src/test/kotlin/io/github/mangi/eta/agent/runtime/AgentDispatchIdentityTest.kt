package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.dispatch.AgentDispatchPayload
import io.github.mangi.eta.agent.model.AgentDispatchToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 调度派发协议契约：会话身份解析与防注入行存在性。 */
class AgentDispatchIdentityTest {

    private fun handoff(payload: String?) = AgentRuntimeWire.EntryHandoff(
        id = "run-1",
        source = AgentRuntimeWire.ETA_DISPATCH_HANDOFF_SOURCE,
        payload = payload.orEmpty(),
    )

    @Test
    fun dispatchHandoffResolvesTargetConversation() {
        val payload = AgentDispatchPayload(
            conversationId = "task-abc",
            dispatchId = "dispatch-1",
            originText = "改功能 B",
        ).toJson()
        assertEquals(
            "task-abc",
            AgentConversationIdentity.fromHandoff(handoff(payload), fallbackRunId = "run-1"),
        )
    }

    @Test
    fun malformedDispatchPayloadFallsBackToRunIdentity() {
        assertEquals(
            "run:run-1",
            AgentConversationIdentity.fromHandoff(handoff("{not json"), fallbackRunId = "run-1"),
        )
        assertEquals(
            "run:run-1",
            AgentConversationIdentity.fromHandoff(
                handoff(AgentDispatchPayload(conversationId = "", dispatchId = "d", originText = "").toJson()),
                fallbackRunId = "run-1",
            ),
        )
    }

    @Test
    fun payloadRoundTrips() {
        val original = AgentDispatchPayload("task-abc", "dispatch-9", "原话")
        val parsed = AgentDispatchPayload.from(original.toJson())
        assertEquals(original, parsed)
        assertNull(AgentDispatchPayload.from(null))
        assertNull(AgentDispatchPayload.from(""))
    }

    @Test
    fun conversationHistorySchemaCarriesAntiInjectionClause() {
        val tools = org.json.JSONArray()
        AgentDispatchToolCatalog.appendTo(tools)
        val history = (0 until tools.length())
            .map { tools.getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == "conversation_history" }
        assertTrue(
            "conversation_history 的 description 必须带防注入行",
            history.getString("description").contains(AgentDispatchToolCatalog.ANTI_INJECTION_CLAUSE),
        )
        assertEquals(
            AgentDispatchToolCatalog.TOOL_NAMES.size,
            tools.length(),
        )
    }
}
