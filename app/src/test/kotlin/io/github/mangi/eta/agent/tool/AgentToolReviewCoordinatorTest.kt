package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能审查协调器的 LLM 输出解析：容忍围栏/前后解释文字，只认严格 JSON，
 * 未知 decision 与无法解析一律回退（绝不把失败当放行）。
 */
class AgentToolReviewCoordinatorTest {
    private fun parse(raw: String) = AgentToolReviewCoordinator.parseLlmVerdict(raw)

    @Test fun plainJsonAllow() {
        val verdict = parse("""{"decision":"allow","reason":"user asked for it"}""")
        assertTrue(verdict is AgentToolReviewVerdict.Allow)
        assertEquals("user asked for it", (verdict as AgentToolReviewVerdict.Allow).reason)
    }

    @Test fun plainJsonDeny() {
        val verdict = parse("""{"decision":"deny","reason":"超出用户请求范围"}""")
        assertTrue(verdict is AgentToolReviewVerdict.Deny)
        assertEquals("超出用户请求范围", (verdict as AgentToolReviewVerdict.Deny).reason)
    }

    @Test fun fencedJson() {
        val verdict = parse("""```json
{"decision":"allow","reason":"safe"}
```""")
        assertTrue(verdict is AgentToolReviewVerdict.Allow)
    }

    @Test fun jsonWithSurroundingExplanation() {
        val verdict = parse(
            "I reviewed the call. Result: {\"decision\":\"deny\",\"reason\":\"destructive\"} Hope that helps.",
        )
        assertTrue(verdict is AgentToolReviewVerdict.Deny)
        assertEquals("destructive", (verdict as AgentToolReviewVerdict.Deny).reason)
    }

    @Test fun nestedBracesInReason() {
        val verdict = parse("""{"decision":"allow","reason":"args {foo: {bar}} look sane"}""")
        assertTrue(verdict is AgentToolReviewVerdict.Allow)
    }

    @Test fun caseInsensitiveDecision() {
        assertTrue(parse("""{"decision":"ALLOW","reason":""}""") is AgentToolReviewVerdict.Allow)
        assertTrue(parse("""{"decision":"DeNy","reason":""}""") is AgentToolReviewVerdict.Deny)
    }

    @Test fun unknownDecisionFallsBack() {
        assertTrue(parse("""{"decision":"maybe","reason":"not sure"}""") is AgentToolReviewVerdict.Fallback)
    }

    @Test fun unparseableFallsBack() {
        assertTrue(parse("sorry, I cannot decide") is AgentToolReviewVerdict.Fallback)
        assertTrue(parse("") is AgentToolReviewVerdict.Fallback)
        assertTrue(parse("{\"decision\":") is AgentToolReviewVerdict.Fallback)
    }

    @Test fun blankReasonGetsDefault() {
        val verdict = parse("""{"decision":"deny","reason":""}""") as AgentToolReviewVerdict.Deny
        assertTrue(verdict.reason.isNotBlank())
    }
}
