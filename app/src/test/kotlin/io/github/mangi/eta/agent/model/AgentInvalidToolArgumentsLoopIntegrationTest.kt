package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure loop tests: no network, terminal backend, Android lifecycle or command execution. */
class AgentInvalidToolArgumentsLoopIntegrationTest {
    @get:org.junit.Rule val timeout = org.junit.rules.Timeout.seconds(45)

    @Test fun repeatedEmptyRunCommandStopsAtThreeWithoutExecutingOrRequestingAgain() {
        val run = runBatches(List(3) { listOf(invalid("empty-$it")) })
        assertStopped(run, requests = 3, results = 3)
        assertTrue(run.executed.isEmpty())
        assertEquals(listOf(INVALID, INVALID, STOP), run.results.map { it.getString("code") })
        assertTrue(run.results.last().getString("message").contains("本次调用未执行"))
        assertTrue(run.failure!!.message!!.contains("纠错预算耗尽"))
    }

    @Test fun exhaustionInsideBatchStillPairsEveryInvalidAndValidCallInOrder() {
        val firstValid = valid("valid-before", "read_file", "{\"path\":\"/tmp/a\"}")
        val lastValid = valid("valid-after", arguments = "{\"command\":\"  echo ok  \"}")
        val run = runBatches(listOf(listOf(
            invalid("bad-1"), firstValid, invalid("bad-2"), invalid("bad-3"),
            lastValid, invalid("bad-4"),
        )))
        assertStopped(run, requests = 1, results = 6)
        // Valid calls after exhaustion still execute exactly once; rejected calls never do.
        assertEquals(listOf(firstValid, lastValid), run.executed)
        assertEquals(listOf(INVALID, "OK", INVALID, STOP, "OK", STOP),
            run.results.map { it.getString("code") })
    }

    @Test fun correctedArgumentsResetOnlyTheUnexhaustedBudgetAndThenContinue() {
        val corrected = valid("corrected")
        val run = runBatches(listOf(
            listOf(invalid("bad-1")), listOf(invalid("bad-2")), listOf(corrected),
            listOf(invalid("bad-3")), listOf(invalid("bad-4")),
        ))
        assertCompleted(run, requests = 6, results = 5)
        assertEquals(listOf(corrected), run.executed)
        assertEquals(4, run.results.count { it.optString("code") == INVALID })
    }

    @Test fun validArgumentsResetBudgetEvenWhenExecutorReturnsAnOrdinaryFailure() {
        val corrected = valid("corrected-but-runtime-failed")
        val run = runBatches(listOf(
            listOf(invalid("bad-1")), listOf(invalid("bad-2")), listOf(corrected),
            listOf(invalid("bad-3")), listOf(invalid("bad-4")),
        ), executorResult = {
            AgentModelClient.ToolResult("{\"ok\":false,\"code\":\"TEST_RUNTIME_ERROR\"}")
        })
        assertCompleted(run, requests = 6, results = 5)
        assertEquals(listOf(corrected), run.executed)
    }

    @Test fun legitimateLongTaskHasNoTotalRoundLimitAndPreservesEveryValidCall() {
        val calls = List(96) { index ->
            valid("long-$index", arguments = "{\"command\":\"  echo step-$index  \"}")
        }
        val run = runBatches(calls.map { listOf(it) })
        assertCompleted(run, requests = 97, results = 96)
        assertEquals(calls, run.executed)
    }

    @Test fun changingIdsAndUnrelatedSuccessCannotBypassFailingToolBudget() {
        val goodCalls = List(3) { valid("read-$it", "read_file", "{\"path\":\"/tmp/a\"}") }
        val run = runBatches(List(3) { index ->
            listOf(invalid("fresh-call-id-$index"), goodCalls[index])
        })
        assertStopped(run, requests = 3, results = 6)
        assertEquals(goodCalls, run.executed)
        assertEquals(STOP, run.results[4].getString("code"))
        assertEquals("OK", run.results[5].getString("code"))
    }

    @Test fun emptyOrWhitespaceIdsNamesAndArgumentFormattingDoNotBypassBudget() {
        // One batch preserves distinct fallback indices while exercising blank raw IDs.
        val run = runBatches(listOf(listOf(
            invalid("", arguments = ""),
            invalid("   ", name = "  run_command\t", arguments = " \n\t"),
            invalid("fresh-id", name = "\trun_command ", arguments = " {  } "),
        )))
        assertStopped(run, requests = 1, results = 3)
        assertTrue(run.executed.isEmpty())
        assertEquals(listOf(INVALID, INVALID, STOP), run.results.map { it.getString("code") })
    }

    @Test fun repeatedRejectedIdIsHandledOnlyByTheParameterBudget() {
        // No replay guard exists: the same id across batches is ordinary history,
        // and the invalid-argument budget alone terminates the run.
        val run = runBatches(List(3) { listOf(invalid("same-id")) })
        assertStopped(run, requests = 3, results = 3)
        assertTrue(run.executed.isEmpty())
        assertEquals(listOf(INVALID, INVALID, STOP), run.results.map { it.getString("code") })
    }

    @Test fun sameRejectedIdStillCountsEachCallAtTheParameterBudgetLayer() {
        // The budget counts each validation rejection, not distinct IDs; no executor exists here.
        val call = AgentModelClient.ToolCall("same-id", "run_command", "{}")
        val validator = AgentToolCallValidator(tools())
        val guard = AgentInvalidToolArgumentsGuard()
        val codes = List(3) {
            val error = requireNotNull(validator.validate(call))
            assertTrue(error.contains("command"))
            guard.reject(call.name, validator.declares(call.name), error).code
        }
        assertEquals(listOf(INVALID, INVALID, STOP), codes)
        assertTrue(requireNotNull(guard.stopMessage).contains("本次调用未执行"))
    }

    @Test fun differentInvalidArgumentsShareTheSameToolBudget() {
        val run = runBatches(listOf(
            listOf(invalid("bad-1", arguments = "not-json")),
            listOf(invalid("bad-2", arguments = "{\"command\":7}")),
            listOf(invalid("bad-3", arguments = "{\"command\":\"\"}")),
        ))
        assertStopped(run, requests = 3, results = 3)
        assertTrue(run.executed.isEmpty())
    }

    @Test fun arbitraryUnknownNamesAndBlankNamesShareOneBoundedBucket() {
        val read = valid("read-ok", "read_file", "{\"path\":\"/tmp/a\"}")
        val run = runBatches(listOf(
            listOf(invalid("unknown-1", name = "missing_alpha")),
            listOf(read, invalid("unknown-2", name = "missing_beta")),
            listOf(invalid("unknown-3", name = "  ")),
        ))
        assertStopped(run, requests = 3, results = 4)
        assertEquals(listOf(read), run.executed)
        assertTrue(run.results.last().getString("message").contains("所有未知名称共用预算"))
    }

    @Test fun differentDeclaredToolsHaveIndependentBudgets() {
        val run = runBatches(listOf(
            listOf(invalid("run-1")), listOf(invalid("read-1", name = "read_file")),
            listOf(invalid("run-2")), listOf(invalid("read-2", name = "read_file")),
        ))
        assertCompleted(run, requests = 5, results = 4)
        assertTrue(run.executed.isEmpty())
    }

    @Test fun declaredDelegateTaskKeepsItsExistingIndependentDisablePolicy() {
        val tools = tools().put(function("delegate_task", "task"))
        val read = valid("read-after-delegation-disable", "read_file", "{\"path\":\"/tmp/a\"}")
        val run = runBatches(listOf(
            listOf(invalid("delegate-1", name = "delegate_task")),
            listOf(invalid("delegate-2", name = "delegate_task")),
            listOf(invalid("delegate-3", name = "delegate_task")),
            listOf(invalid("delegate-4", name = "delegate_task"), read),
        ), catalog = tools)
        assertCompleted(run, requests = 5, results = 5)
        assertEquals(listOf(read), run.executed)
        assertEquals(listOf(
            "DELEGATION_ARGUMENT_REPAIR", "DELEGATION_ARGUMENT_REPAIR",
            "DELEGATION_ARGUMENT_REPAIR_EXHAUSTED", "DELEGATION_ARGUMENT_REPAIR_EXHAUSTED", "OK",
        ), run.results.map { it.getString("code") })
    }

    @Test fun aNewRunDoesNotInheritPreviousRunsBudget() {
        repeat(2) { index ->
            val run = runBatches(List(2) { listOf(invalid("run-$index-bad-$it")) })
            assertCompleted(run, requests = 3, results = 2)
            assertTrue(run.executed.isEmpty())
        }
    }

    @Test fun redactedPlaceholderCopyGetsTargetedGuidanceInsideTheSameBudget() {
        // 真实会话里模型把历史脱敏占位 {"redacted":true} 当作 tool_call 的真实参数回传，
        // 通用的「缺少必填字段」看不出错因；纠正文案必须自述占位性质，预算照常计数。
        val catalog = tools().put(function("tool_call", "name"))
        val run = runBatches(
            List(3) { index -> listOf(invalid("copy-$index", name = "tool_call", arguments = "{\"redacted\":true}")) },
            catalog = catalog,
        )
        assertStopped(run, requests = 3, results = 3)
        assertTrue(run.executed.isEmpty())
        assertEquals(listOf(INVALID, INVALID, STOP), run.results.map { it.getString("code") })
        run.results.take(2).forEach { result ->
            val message = result.getString("message")
            assertTrue(message.contains("历史脱敏占位"))
            assertTrue(message.contains("不要把占位原样回传"))
            assertFalse(message.contains("缺少必填字段"))
        }
    }

    private data class Call(val id: String, val name: String, val arguments: String)
    private data class Run(
        val requests: Int,
        val history: JSONArray,
        val executed: List<Call>,
        val failure: AgentModelFailure?,
        val completion: AgentLoop.Result?,
    ) {
        val results: List<JSONObject>
            get() = (0 until history.length()).map { history.getJSONObject(it) }
                .filter { it.optString("role") == "tool" }
                .map { JSONObject(it.getString("content")) }
    }

    private fun runBatches(
        batches: List<List<Call>>,
        catalog: JSONArray = tools(),
        executorResult: (AgentModelClient.ToolCall) -> AgentModelClient.ToolResult = {
            AgentModelClient.ToolResult("{\"ok\":true,\"code\":\"OK\"}")
        },
    ): Run {
        val history = JSONArray().put(AgentConversationCodec.userTextMessage("完成测试任务"))
        val executed = mutableListOf<Call>()
        var requests = 0
        val provider = object : AgentProviderClient {
            override val id = "invalid-tool-arguments-loop-test"
            override val capabilities = ProviderCapabilities(
                EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false,
            )

            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                // At every provider boundary all previous assistant calls must be paired.
                assertPaired(history)
                requests++
                // A broken guard fails assertions instead of hanging or making real requests.
                check(requests <= batches.size + 1) { "Unexpected extra provider request" }
                if (requests == batches.size + 1) {
                    return ProviderResponse(JSONObject().put("role", "assistant")
                        .put("content", "测试完成").put("finish_reason", "stop"))
                }
                return ProviderResponse(JSONObject()
                    .put("role", "assistant")
                    .put("content", "")
                    .put("finish_reason", "tool_calls")
                    .put("tool_calls", JSONArray().apply {
                        batches[requests - 1].forEach { call ->
                            put(JSONObject().put("id", call.id).put("type", "function")
                                .put("function", JSONObject().put("name", call.name)
                                    .put("arguments", call.arguments)))
                        }
                    }))
            }
        }
        var failure: AgentModelFailure? = null
        var completion: AgentLoop.Result? = null
        try {
            completion = AgentLoop(
                config = AgentModelClient.ModelConfig(
                    baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test",
                    systemPrompt = "", contextWindow = 1_000_000,
                ),
                messages = history,
                tools = catalog,
                provider = provider,
                toolExecutor = AgentModelClient.ToolExecutor { call ->
                    executed += Call(call.id, call.name, call.argumentsJson)
                    executorResult(call)
                },
                runController = AgentRunController(),
                traceFormatter = AgentTraceFormatter(),
                onEvent = {},
            ).run()
        } catch (caught: AgentModelFailure) {
            failure = caught
        }
        assertPaired(history)
        return Run(requests, history, executed, failure, completion)
    }

    private fun assertStopped(run: Run, requests: Int, results: Int) {
        assertNotNull("the invalid argument guard must terminate the run", run.failure)
        assertNull(run.completion)
        assertEquals(STOP, run.failure!!.code)
        assertFalse(run.failure.retryable)
        assertTrue(run.failure.message!!.contains(STOP))
        assertEquals(requests, run.requests)
        assertEquals(results, run.results.size)
    }

    private fun assertCompleted(run: Run, requests: Int, results: Int) {
        assertNull(run.failure)
        assertEquals("测试完成", run.completion?.content)
        assertEquals(requests, run.requests)
        assertEquals(results, run.results.size)
    }

    private fun assertPaired(history: JSONArray) {
        var pending = emptyList<String>()
        var paired = 0
        for (index in 0 until history.length()) {
            val message = history.getJSONObject(index)
            when (message.optString("role")) {
                "assistant" -> {
                    assertEquals("previous batch must be fully paired", pending.size, paired)
                    val calls = message.optJSONArray("tool_calls")
                    pending = if (calls == null) emptyList() else (0 until calls.length())
                        .map { calls.getJSONObject(it).getString("id") }
                    paired = 0
                }
                "tool" -> {
                    assertTrue("no duplicate or unpaired result", paired < pending.size)
                    assertEquals("result order must match assistant calls", pending[paired],
                        message.getString("tool_call_id"))
                    paired++
                }
            }
        }
        assertEquals("every call in the final batch must have a result", pending.size, paired)
    }

    private fun invalid(
        id: String,
        name: String = "run_command",
        arguments: String = "{}",
    ) = Call(id, name, arguments)

    private fun valid(
        id: String,
        name: String = "run_command",
        arguments: String = "{\"command\":\"echo ok\"}",
    ) = Call(id, name, arguments)

    private fun tools() = JSONArray()
        .put(function("run_command", "command"))
        .put(function("read_file", "path"))

    private fun function(name: String, required: String) = AgentToolSchema.function(
        name = name,
        description = "test $name",
        parameters = JSONObject().put("type", "object")
            .put("properties", JSONObject().put(required,
                JSONObject().put("type", "string").put("minLength", 1)))
            .put("required", JSONArray().put(required)),
    )

    private companion object {
        const val INVALID = AgentInvalidToolArgumentsGuard.FAILURE_CODE
        const val STOP = AgentInvalidToolArgumentsGuard.STOP_CODE
    }
}
