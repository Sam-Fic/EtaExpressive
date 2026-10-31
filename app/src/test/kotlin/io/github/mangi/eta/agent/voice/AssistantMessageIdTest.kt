package io.github.mangi.eta.agent.voice

import org.junit.Assert.*
import org.junit.Test

class AssistantMessageIdTest {

    private val runId = "3f2b8a10-6c4e-4d5f-9a1b-2c3d4e5f6a7b"
    private val otherRunId = "9a8b7c6d-5e4f-3a2b-1c0d-9e8f7a6b5c4d"

    @Test fun everyAssistantBubbleShapeOfTheSameRunIsOwnedByIt() {
        val legacy = "assistant-$runId"
        val retry = "assistant-$runId-retry-1"
        val media = "assistant-$runId-1"
        val round = "assistant-$runId-2-1"
        val reconnected = "assistant-$runId-2-1-reconnect-3:abc"
        listOf(legacy, retry, media, round, reconnected).forEach { id ->
            assertTrue(id, AssistantMessageId.belongsToRun(id, runId))
        }
    }

    @Test fun anotherRunsBubbleIsNotOwned() {
        assertFalse(AssistantMessageId.belongsToRun("assistant-$otherRunId-2-1", runId))
        // 旧版前缀拼接的会话/用户消息同样不该被误认成本轮回复。
        assertFalse(AssistantMessageId.belongsToRun("user-$runId", runId))
    }

    @Test fun missingIdOrRunIsNeverOwned() {
        assertFalse(AssistantMessageId.belongsToRun(null, runId))
        assertFalse(AssistantMessageId.belongsToRun("  ", runId))
        assertFalse(AssistantMessageId.belongsToRun("assistant-$runId-1-0", null))
        assertFalse(AssistantMessageId.belongsToRun("assistant-$runId-1-0", " "))
    }

    @Test fun reconnectSuffixIsNotANewSegment() {
        val base = "assistant-$runId-2-1"
        assertEquals(base, AssistantMessageId.segmentKey("$base-reconnect-3:abc"))
        assertEquals(AssistantMessageId.segmentKey(base), AssistantMessageId.segmentKey("$base-reconnect-9:xyz"))
        assertNull(AssistantMessageId.segmentKey(null))
    }

    @Test fun idsWithoutTheSuffixKeepTheirOwnKey() {
        listOf("assistant-$runId", "assistant-$runId-1", "assistant-$runId-retry-1", "m1").forEach { id ->
            assertEquals(id, AssistantMessageId.segmentKey(id))
        }
    }

    @Test fun differentRoundsAndSegmentsStayDifferent() {
        assertNotEquals(
            AssistantMessageId.segmentKey("assistant-$runId-1-0"),
            AssistantMessageId.segmentKey("assistant-$runId-2-0"),
        )
        assertNotEquals(
            AssistantMessageId.segmentKey("assistant-$runId-2-0"),
            AssistantMessageId.segmentKey("assistant-$runId-2-1"),
        )
    }
}
