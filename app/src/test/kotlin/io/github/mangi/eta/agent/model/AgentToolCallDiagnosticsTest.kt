package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.model.AgentModelClient.ToolCall
import io.github.mangi.eta.agent.model.AgentModelClient.ToolResult
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCallDiagnosticsTest {
    @Test fun hostileNamesKeysAndValuesNeverEnterLogsAtAnyStage() {
        val capture = Capture()
        val secret = "PRIVATE_SENTINEL_\n\"你好\"_Bearer_sensitive"
        val args = JSONObject().put(secret + "key", secret + "value")
            .put("command", secret + "command").put("cwd", secret + "cwd")
            .put("headers", JSONObject().put("Authorization", secret))
            .put("api_key", secret).put("text", secret).put("session_id", secret)
            .put("environment", secret).put("action", secret).toString()
        val call = ToolCall(secret + "call", secret + "tool", args)
        val attempt = capture.begin(provider = secret + "provider")
        val item = item(call, secret + "item")
        val event = added(item, 7).put(secret + "event-key", secret)
        val before = event.toString()
        attempt.responsesEvent(event)
        attempt.responsesEvent(delta(args, secret + "item"))
        attempt.responsesEvent(done(args, secret + "item"))
        attempt.responsesEvent(JSONObject().put("type", "response.output_item.done").put("item", item))
        val assistant = assistant(call).put("content", secret).put("reasoning_content", secret)
        val assistantBefore = assistant.toString()
        attempt.providerParsed(assistant)
        attempt.parsed(call, 0)
        attempt.validation(call, false)
        attempt.dispatch(call)
        attempt.result(call, ToolResult(JSONObject().put("ok", false).put("tool", secret)
            .put("environment", secret).put("stdout", secret).put("stderr", secret)
            .put("message", secret).put("code", secret).put(secret, secret).toString(), sensitive = true))
        attempt.failed(secret)
        assertEquals(before, event.toString())
        assertEquals(assistantBefore, assistant.toString())
        assertEquals(args, call.argumentsJson)
        val all = capture.lines.joinToString("\n")
        listOf("PRIVATE_SENTINEL", "Bearer_sensitive", "你好", "Authorization", "api_key", "session_id", "cwd", "stdout", "stderr", "reasoning_content")
            .forEach { assertFalse("Unexpected plaintext: $it", all.contains(it)) }
        val parsed = capture.stage("parsed").single()
        // 工具名只放行短标识字符集：敌意名字进不了日志，落 "unknown" 桶
        // （"other" 留给合法但未识别的工具名）。
        assertEquals("unknown", parsed.getString("tool"))
        assertEquals("other", parsed.getString("requested_environment"))
        assertEquals("other", parsed.getString("requested_action"))
        assertEquals(9, parsed.getInt("argument_key_count"))
        assertEquals((secret + "command").length, parsed.getInt("command_chars"))
        assertTrue(parsed.getString("tool_hmac").matches(Regex("[0-9a-f]{32}")))
        assertEquals("other", capture.stage("failed").single().getString("code"))
        assertSafeLines(capture.lines)
    }

    @Test fun itemCallAndOutputAliasesCorrelateAllStagesWithEqualExactArgumentDigests() {
        val capture = Capture()
        val attempt = capture.begin()
        val args = """{"action":"open_and_exec","environment":"linux","command":"echo private"}"""
        val call = ToolCall("private-call-id", "terminal", args)
        // Tool ordinal zero differs from output index four because reasoning/text preceded it.
        attempt.responsesEvent(added(item(call).put("arguments", ""), 4))
        attempt.responsesEvent(JSONObject().put("type", "response.function_call_arguments.delta")
            .put("output_index", 4).put("delta", args.substring(0, 20)))
        attempt.responsesEvent(delta(args.substring(20)))
        attempt.responsesEvent(done(args))
        attempt.responsesEvent(JSONObject().put("type", "response.output_item.done").put("item", item(call)))
        attempt.responsesEvent(terminal("completed", JSONArray().put(JSONObject().put("type", "message")).put(item(call))))
        attempt.providerParsed(assistant(call))
        attempt.parsed(call, 0)
        attempt.validation(call, true)
        attempt.dispatch(call)
        attempt.result(call, ToolResult("""{"tool":"terminal","environment":"debian","exit_code":0,"timed_out":false,"ok":true}"""))
        val stages = listOf("raw_added", "raw_delta_summary", "raw_args_done", "raw_item_done", "raw_terminal", "provider_parsed", "parsed", "validation", "dispatch", "result")
        val records = stages.map { capture.stage(it).single() }
        assertEquals(1, records.map { it.getInt("call") }.distinct().size)
        assertEquals(1, records.map { it.getString("run") }.distinct().size)
        assertEquals(1, records.map { it.getInt("attempt") }.distinct().size)
        assertEquals(1, records.drop(1).map { it.getString("arguments_hmac") }.distinct().size)
        assertNotEquals(records.first().getString("arguments_hmac"), records.last().getString("arguments_hmac"))
        assertEquals(2L, capture.stage("raw_delta_summary").single().getLong("delta_count"))
        assertFalse(capture.lines.joinToString().contains("private-call-id"))
        assertFalse(capture.lines.joinToString().contains("private-item-id"))
    }

    @Test fun orphanOutputDeltaJoinsLaterAddedItemWithoutRetainingTheDelta() {
        val capture = Capture()
        val attempt = capture.begin()
        attempt.responsesEvent(JSONObject().put("type", "response.function_call_arguments.delta")
            .put("output_index", 9).put("delta", "{}"))
        val call = ToolCall("later-call", "terminal", "{}")
        attempt.responsesEvent(added(item(call), 9))
        attempt.responsesEvent(done("{}"))
        attempt.parsed(call, 0)
        assertEquals(capture.stage("raw_delta_summary").single().getInt("call"), capture.stage("parsed").single().getInt("call"))
        assertEquals(capture.stage("raw_delta_summary").single().getString("arguments_hmac"), capture.stage("parsed").single().getString("arguments_hmac"))
    }

    @Test fun synthesizedIdsUseExplicitlyMarkedToolOrdinalFallback() {
        val capture = Capture()
        val attempt = capture.begin()
        val raw = JSONObject().put("type", "function_call").put("name", "terminal").put("arguments", "{}")
        attempt.responsesEvent(added(raw, 5))
        val call = ToolCall("provider-generated-id", "terminal", "{}")
        attempt.providerParsed(assistant(call))
        attempt.parsed(call, 0)
        assertEquals(1, capture.records().filter { it.has("call") }.map { it.getInt("call") }.distinct().size)
        assertTrue(capture.stage("parsed").single().getBoolean("positional_correlation"))
    }

    @Test fun streamingDigestHandlesSurrogatePairsSplitAcrossDeltas() {
        val capture = Capture()
        val attempt = capture.begin()
        val args = "{\"command\":\"\uD83D\uDE80\"}"
        val split = args.indexOf('\uD83D') + 1
        val call = ToolCall("unicode-call", "terminal", args)
        attempt.responsesEvent(added(item(call), 0))
        listOf(args.substring(0, split), args.substring(split)).forEach { attempt.responsesEvent(delta(it)) }
        attempt.responsesEvent(done(args))
        attempt.parsed(call, 0)
        val summary = capture.stage("raw_delta_summary").single()
        assertEquals(args.length.toLong(), summary.getLong("delta_chars"))
        assertEquals(args.length.toLong() * 2, summary.getLong("delta_utf16_bytes"))
        assertEquals(summary.getString("arguments_hmac"), capture.stage("parsed").single().getString("arguments_hmac"))
    }

    @Test fun missingNullBlankEmptyObjectMalformedAndWrongTypeAreNotNormalized() {
        val capture = Capture()
        val attempt = capture.begin()
        val inputs = listOf<Any?>(null, JSONObject.NULL, "", " \t\r\n", "{}", "{broken", "[]", JSONArray(), JSONObject(), "null", "{} trailing", "{unquoted:1}")
        val expected = listOf("missing", "null", "empty_string", "blank_string", "empty_object", "malformed", "non_object", "wrong_type", "empty_object", "non_object", "malformed", "malformed")
        val calls = JSONArray()
        inputs.forEachIndexed { index, value ->
            val function = JSONObject().put("name", "terminal")
            if (value != null) function.put("arguments", value)
            calls.put(JSONObject().put("id", "call-$index").put("function", function))
        }
        attempt.providerParsed(JSONObject().put("tool_calls", calls))
        val records = capture.stage("provider_parsed")
        assertEquals(expected, records.map { it.getString("arguments_state") })
        assertFalse(records[0].getBoolean("arguments_present"))
        assertTrue(records[1].getBoolean("arguments_present"))
        assertEquals("null", records[1].getString("arguments_kind"))
        assertEquals("string", records[4].getString("arguments_kind"))
        assertEquals("object", records[8].getString("arguments_kind"))
        assertEquals(0, records[4].getInt("argument_key_count"))
        assertNotEquals(records[2].getString("arguments_hmac"), records[3].getString("arguments_hmac"))
    }

    @Test fun providerMissingArgumentsAndCodecEmptyObjectRemainCorrelatedButDistinct() {
        val capture = Capture()
        val attempt = capture.begin()
        attempt.providerParsed(JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("id", "call-x")
            .put("function", JSONObject().put("name", "terminal")))))
        attempt.parsed(ToolCall("call-x", "terminal", "{}"), 0)
        val provider = capture.stage("provider_parsed").single()
        val parsed = capture.stage("parsed").single()
        assertEquals(provider.getInt("call"), parsed.getInt("call"))
        assertEquals("missing", provider.getString("arguments_state"))
        assertEquals("empty_object", parsed.getString("arguments_state"))
        assertFalse(provider.has("arguments_hmac"))
        assertTrue(parsed.has("arguments_hmac"))
    }

    @Test fun rawArgumentsFieldKindsAreRecordedBeforeProviderNormalization() {
        val capture = Capture()
        val attempt = capture.begin()
        listOf<Any?>(null, JSONObject.NULL, "", JSONObject(), 7, true, JSONArray()).forEachIndexed { index, value ->
            val raw = JSONObject().put("type", "function_call").put("id", "raw-$index")
                .put("call_id", "call-$index").put("name", "terminal")
            if (value != null) raw.put("arguments", value)
            attempt.responsesEvent(added(raw, index))
        }
        assertEquals(listOf("missing", "null", "string", "object", "number", "boolean", "array"),
            capture.stage("raw_added").map { it.getString("arguments_kind") })
    }

    @Test fun boundedObjectArgumentsHashLikeTheirFinalSerializedStringWithoutLeakingKeys() {
        val capture = Capture()
        val attempt = capture.begin()
        val args = JSONObject().put("command", "SECRET_COMMAND").put("SECRET_KEY", "SECRET_VALUE")
            .put("nested", JSONArray().put(JSONObject().put("private-key", true)).put(JSONObject.NULL).put(3))
        val call = ToolCall("object-call", "terminal", args.toString())
        attempt.responsesEvent(added(item(call).put("arguments", args), 0))
        val provider = assistant(call)
        provider.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments", args)
        attempt.providerParsed(provider)
        attempt.parsed(call, 0)
        assertEquals(1, listOf("raw_added", "provider_parsed", "parsed")
            .map { capture.stage(it).single().getString("arguments_hmac") }.distinct().size)
        assertEquals("object_serialized", capture.stage("raw_added").single().getString("arguments_hmac_basis"))
        listOf("SECRET_", "private-key", "nested").forEach { assertFalse(capture.lines.joinToString().contains(it)) }
    }

    @Test fun hugeDeepCyclicAndNonJsonObjectValuesNeverTriggerUnsafeSerialization() {
        val capture = Capture()
        val attempt = capture.begin()
        val cycle = JSONObject().also { it.put("cycle", it) }
        val hostile = object { override fun toString(): String = throw AssertionError("must not stringify") }
        val inputs = listOf(JSONObject().put("private-key", "X".repeat(40_000)), cycle, JSONObject().put("private-key", hostile))
        inputs.forEachIndexed { index, args ->
            val raw = JSONObject().put("type", "function_call").put("call_id", "call-$index")
                .put("name", "terminal").put("arguments", args)
            attempt.responsesEvent(added(raw, index))
        }
        assertEquals(3, capture.stage("raw_added").size)
        assertTrue(capture.stage("raw_added").all { it.getString("arguments_hmac_basis") == "unavailable" })
        assertFalse(capture.lines.joinToString().contains("private-key"))
        assertSafeLines(capture.lines)
    }

    @Test fun linuxRequestAndDebianResultAreSeparateAllowlistedEvidence() {
        val capture = Capture()
        val attempt = capture.begin()
        val call = ToolCall("linux-call", "terminal", """{"environment":"linux","action":"open_and_exec","command":"secret-command","cwd":"secret-path"}""")
        val result = ToolResult("""{"ok":true,"tool":"run_command","environment":"debian","action":"open_and_exec","identity":"root","cwd":"secret-cwd","exit_code":0,"timed_out":false,"stdout":"secret-stdout","stderr":"secret-stderr"}""")
        attempt.parsed(call, 0)
        attempt.result(call, result)
        val record = capture.stage("result").single()
        assertEquals("terminal", record.getString("tool"))
        assertEquals("run_command", record.getString("actual_tool"))
        assertEquals("linux", record.getString("requested_environment"))
        assertEquals("debian", record.getString("actual_environment"))
        assertEquals("open_and_exec", record.getString("requested_action"))
        assertEquals(0, record.getInt("exit_code"))
        assertTrue(record.getBoolean("ok"))
        assertFalse(record.getBoolean("timed_out"))
        listOf("secret-", "identity", "stdout", "stderr", "cwd", "root").forEach { assertFalse(capture.lines.joinToString().contains(it)) }
    }

    @Test fun structuredResultDoesNotCoerceStringsOrTraverseNestedPayloads() {
        val capture = Capture()
        val attempt = capture.begin()
        val call = ToolCall("call", "run_command", "{}")
        attempt.result(call, ToolResult("""{"tool":"HOSTILE_TOOL","environment":"HOSTILE_ENV","ok":"true","timed_out":"false","exit_code":"123","code":"HOSTILE_ERROR","data":{"environment":"debian","tool":"terminal","ok":true}}"""))
        val record = capture.stage("result").single()
        assertEquals("other", record.getString("actual_tool"))
        assertEquals("other", record.getString("actual_environment"))
        assertEquals("other", record.getString("code"))
        assertFalse(record.has("ok"))
        assertFalse(record.has("timed_out"))
        assertFalse(record.has("exit_code"))
        assertFalse(capture.lines.joinToString().contains("HOSTILE"))
        assertFalse(capture.lines.joinToString().contains("debian"))
    }

    @Test fun malformedDeepAndOversizedResultsAreNonthrowingAndDoNotLeak() {
        val capture = Capture()
        val attempt = capture.begin()
        val call = ToolCall("call", "terminal", "{}")
        listOf("RESULT_SECRET", "[".repeat(40) + "RESULT_SECRET" + "]".repeat(40), "RESULT_SECRET".repeat(30_000)).forEach {
            attempt.result(call, ToolResult(it, sensitive = true))
        }
        assertEquals(listOf("malformed", "too_deep", "too_large"), capture.stage("result").map { it.getString("result_state") })
        assertFalse(capture.lines.joinToString().contains("RESULT_SECRET"))
        assertSafeLines(capture.lines)
    }

    @Test fun differentArgumentsHaveDifferentDigestsAndRunKeysCannotLinkRuns() {
        val one = Capture()
        val first = one.begin()
        first.parsed(ToolCall("a", "terminal", "{}"), 0)
        first.parsed(ToolCall("b", "terminal", "{ }"), 1)
        one.begin(round = 2).parsed(ToolCall("a", "terminal", "{}"), 0)
        val two = Capture()
        two.begin().parsed(ToolCall("a", "terminal", "{}"), 0)
        val records = one.stage("parsed")
        assertNotEquals(records[0].getString("arguments_hmac"), records[1].getString("arguments_hmac"))
        assertEquals(records[0].getString("arguments_hmac"), records[2].getString("arguments_hmac"))
        assertNotEquals(records[0].getInt("attempt"), records[2].getInt("attempt"))
        assertNotEquals(records[0].getInt("call"), records[2].getInt("call"))
        assertNotEquals(records[0].getString("arguments_hmac"), two.stage("parsed").single().getString("arguments_hmac"))
        assertNotEquals(records[0].getString("run"), two.stage("parsed").single().getString("run"))
        assertEquals(one.records().indices.map { it + 1 }, one.records().map { it.getInt("seq") })
    }

    @Test fun dynamicDisabledGateSkipsEveryStageAndCanBeReenabled() {
        val capture = Capture()
        capture.on = false
        assertNull(capture.diagnostics.beginAttempt(1, "provider"))
        assertTrue(capture.lines.isEmpty())
        capture.on = true
        val attempt = capture.begin()
        capture.lines.clear()
        capture.on = false
        exercise(attempt)
        assertTrue(capture.lines.isEmpty())
        capture.on = true
        attempt.parsed(ToolCall("call", "terminal", "{}"), 0)
        assertEquals(1, capture.lines.size)
        capture.on = false
        attempt.failed("timeout")
        assertEquals(1, capture.lines.size)
    }

    @Test fun throwingEnableCallbackAndThrowingSinkNeverEscape() {
        val disabled = AgentToolCallDiagnostics(enabled = { throw AssertionError("secret gate") }, sink = { error("must not run") })
        assertNull(disabled.beginAttempt(1, "provider"))
        var throwGate = false
        var sinkCalls = 0
        val diagnostics = AgentToolCallDiagnostics(enabled = {
            if (throwGate) throw AssertionError("secret gate") else true
        }, sink = { sinkCalls++; throw AssertionError("secret sink") })
        val candidate = diagnostics.beginAttempt(1, "provider")
        assertNotNull(candidate)
        val attempt = requireNotNull(candidate)
        exercise(attempt)
        assertTrue(sinkCalls > 1)
        val before = sinkCalls
        throwGate = true
        exercise(attempt)
        assertEquals(before, sinkCalls)
    }

    @Test fun disabledBetweenMetadataAndEmissionNeverCallsSink() {
        var gates = 0
        val lines = mutableListOf<String>()
        val diagnostics = AgentToolCallDiagnostics(enabled = { ++gates < 3 }, sink = { lines.add(it) })
        diagnostics.beginAttempt(1, "provider")
        assertTrue(lines.isEmpty())
    }

    @Test fun criticalQuotasResetPerAttemptEvenBeyondTheOldRunCapAndFailingSinks() {
        val capture = Capture()
        repeat(10) { round ->
            val attempt = capture.begin(round = round)
            repeat(300) { attempt.failed("timeout") }
        }
        assertEquals(640, capture.stage("failed").size)
        assertTrue(capture.records().groupBy { it.getInt("attempt") }.values.all { it.size == 65 })
        assertNotNull(capture.diagnostics.beginAttempt(11, "provider"))
        assertSafeLines(capture.lines)
        var calls = 0
        val throwing = AgentToolCallDiagnostics(enabled = { true }, sink = { calls++; error("sink") })
        repeat(10) {
            val attempt = requireNotNull(throwing.beginAttempt(it, "provider"))
            repeat(300) { attempt.failed("timeout") }
        }
        assertEquals(650, calls)
    }

    @Test fun detailExhaustionDoesNotHideRequestUsageOrHistoryOnLaterAttempts() {
        val capture = Capture()
        val call = ToolCall("call", "get_task_result", "{}")
        repeat(6) { round ->
            val attempt = capture.begin(round = round)
            repeat(300) { attempt.emit("parsed", JSONObject()) }
            attempt.requestShape(JSONObject(), "{}", JSONObject())
            attempt.usage(AgentTokenUsage(inputTokens = 214558 + round, cachedTokens = 0))
            attempt.historyResult(call, JSONObject().put("content", "PRIVATE_REPORT"))
        }
        assertEquals(6 * 127, capture.stage("parsed").size)
        assertEquals(6, capture.stage("request_shape").size)
        assertEquals(6, capture.stage("usage").size)
        assertEquals(6, capture.stage("history_result").size)
        assertTrue(capture.stage("request_shape").all { it.getBoolean("detail_exhausted") && it.getLong("detail_dropped_attempt") > 0 })
        assertEquals(6 * 173L, capture.stage("usage").last().getLong("detail_dropped_total"))
        assertFalse(capture.lines.joinToString().contains("PRIVATE_REPORT"))
    }

    @Test fun bodyAndResultFingerprintsDistinguishExactBytesWithoutLoggingTheBody() {
        val capture = Capture()
        val attempt = capture.begin()
        val plain = "PRIVATE_BODY中文🙂" + "x".repeat(4095) + "🙂"
        val call = ToolCall("id", "get_task_result", "{}")
        val raw = ToolResult(plain, sensitive = true)
        val guarded = ToolResult(plain + " ", sensitive = true)
        attempt.result(call, guarded, rawResult = raw)
        attempt.historyResult(call, JSONObject().put("content", guarded.content))
        attempt.requestShape(JSONObject(), plain, JSONObject())
        attempt.requestShape(JSONObject(), plain, JSONObject())
        attempt.requestShape(JSONObject(), plain + " ", JSONObject())
        val result = capture.stage("result").single()
        assertEquals(plain.length, result.getInt("raw_result_chars"))
        assertEquals(plain.toByteArray(Charsets.UTF_8).size.toLong(), result.getLong("raw_result_utf8_bytes"))
        assertNotEquals(result.getString("raw_result_hmac"), result.getString("guarded_result_hmac"))
        assertEquals(result.getString("guarded_result_hmac"), capture.stage("history_result").single().getString("history_result_hmac"))
        val shapes = capture.stage("request_shape")
        assertEquals(shapes[0].getString("body_hmac"), shapes[1].getString("body_hmac"))
        assertNotEquals(shapes[0].getString("body_hmac"), shapes[2].getString("body_hmac"))
        assertEquals("serialized_utf8", shapes[0].getString("body_hmac_basis"))
        assertFalse(capture.lines.joinToString().contains("PRIVATE_BODY"))
        assertSafeLines(capture.lines)
    }

    @Test fun usageReceiptsAreNotMergedAndReasoningIdentifiersAreOnlyCounted() {
        val capture = Capture()
        val attempt = capture.begin(round = 22)
        attempt.usage(AgentTokenUsage(inputTokens = 214558, cachedTokens = 0))
        attempt.usage(AgentTokenUsage(outputTokens = 54))
        assertEquals(listOf(1L, 2L), capture.stage("usage").map { it.getLong("receipt_ordinal") })
        val later = capture.stage("usage")[1]
        assertFalse(later.getBoolean("input_tokens_present"))
        assertFalse(later.has("input_tokens"))
        assertFalse(later.getBoolean("cached_tokens_present"))
        val body = JSONObject().put("input", JSONArray()
            .put(JSONObject().put("type", "reasoning").put("id", "PRIVATE_ID"))
            .put(JSONObject().put("type", "reasoning").put("id", "PRIVATE_ID"))
            .put(JSONObject().put("type", "reasoning")))
        attempt.requestShape(JSONObject(), body.toString(), body)
        val shape = capture.stage("request_shape").single()
        assertEquals(1L, shape.getLong("reasoning_id_missing"))
        assertEquals(1L, shape.getLong("reasoning_id_duplicates"))
        assertFalse(capture.lines.joinToString().contains("PRIVATE_ID"))
        capture.begin(round = 23).usage(AgentTokenUsage(inputTokens = 326594))
        assertEquals(1L, capture.stage("usage").last().getLong("receipt_ordinal"))
    }

    @Test fun callTrackingAndOrphanDeltaStorageAreBoundedWithoutPerDeltaLogging() {
        val capture = Capture()
        val attempt = capture.begin()
        repeat(2_000) { attempt.responsesEvent(delta("private-delta", "hostile-orphan-$it")) }
        assertEquals(1, capture.lines.size)
        attempt.failed("network_error")
        assertTrue(capture.stage("raw_delta_summary").size <= 32)
        val failed = capture.stage("failed").single()
        assertEquals(32, failed.getInt("tracked_calls"))
        assertEquals(1968L, failed.getLong("untracked_delta_count"))
        assertEquals(1968L * "private-delta".length, failed.getLong("untracked_delta_chars"))
        assertFalse(failed.getBoolean("saw_terminal"))
        assertTrue(failed.getBoolean("saw_tool_call"))
        assertFalse(capture.lines.joinToString().contains("private-delta"))
        assertFalse(capture.lines.joinToString().contains("hostile-orphan"))
        assertSafeLines(capture.lines)
    }

    @Test fun anonymousDeltasAreCountsOnlyAndNonStringDeltasAreExplicit() {
        val capture = Capture()
        val attempt = capture.begin()
        repeat(1_000) {
            attempt.responsesEvent(JSONObject().put("type", "response.function_call_arguments.delta").put("delta", "secret"))
        }
        attempt.responsesEvent(JSONObject().put("type", "response.function_call_arguments.delta")
            .put("item_id", "item").put("delta", JSONObject.NULL))
        attempt.failed("network_error")
        assertEquals(1, capture.stage("raw_delta_summary").size)
        assertEquals(1L, capture.stage("raw_delta_summary").single().getLong("non_string_deltas"))
        assertFalse(capture.stage("raw_delta_summary").single().has("arguments_hmac"))
        assertEquals(1000L, capture.stage("failed").single().getLong("untracked_delta_count"))
        assertEquals(6000L, capture.stage("failed").single().getLong("untracked_delta_chars"))
    }

    @Test fun hugeArgumentAndIdentifierStringsProduceBoundedLinesAndFullStringFingerprints() {
        val capture = Capture()
        val attempt = capture.begin()
        val huge = "private-long-command".repeat(10_000)
        val args = JSONObject().put("command", huge).toString()
        val call = ToolCall(huge, huge, args)
        attempt.responsesEvent(added(item(call, huge), 0))
        attempt.providerParsed(assistant(call))
        attempt.parsed(call, 0)
        attempt.validation(call, true)
        attempt.result(call, ToolResult(huge))
        assertSafeLines(capture.lines)
        val records = listOf("raw_added", "provider_parsed", "parsed", "validation", "result").map { capture.stage(it).single() }
        assertEquals(1, records.map { it.getString("arguments_hmac") }.distinct().size)
        assertEquals(1, records.map { it.getInt("call") }.distinct().size)
        assertTrue(records.all { it.getString("arguments_state") == "too_large" })
        assertFalse(capture.lines.joinToString().contains("private-long-command"))
    }

    @Test fun terminalCompletedIncompleteAndFailedOutputItemsAreObservedAndSummarized() {
        val capture = Capture()
        listOf("completed", "incomplete", "failed").forEach { status ->
            val attempt = capture.begin()
            val call = ToolCall("call-$status", "terminal", "{}")
            attempt.responsesEvent(terminal(status, JSONArray()
                .put(JSONObject().put("type", "message").put("content", "SECRET_RESPONSE_TEXT"))
                .put(item(call))))
            attempt.failed("PRIVATE_SERVER_ERROR")
        }
        assertEquals(listOf("completed", "incomplete", "failed"), capture.stage("raw_terminal").map { it.getString("terminal_status") })
        assertTrue(capture.stage("failed").all { it.getBoolean("saw_terminal") && it.getBoolean("saw_tool_call") })
        assertTrue(capture.stage("failed").all { it.getString("code") == "other" })
        assertFalse(capture.lines.joinToString().contains("SECRET_RESPONSE_TEXT"))
        assertFalse(capture.lines.joinToString().contains("PRIVATE_SERVER_ERROR"))
    }

    @Test fun resultAndClassifiedFailureCodesAreExplicitlyAllowlisted() {
        val capture = Capture()
        val attempt = capture.begin()
        val codes = listOf("timeout", "SHELL_COMMAND_NOT_FOUND", "RESPONSES_TOOL_CALL_INCOMPLETE", "RESPONSES_TOOL_ARGUMENTS_INCOMPLETE", "INVALID_TOOL_ARGUMENTS", "INVALID_TOOL_ARGUMENTS_REPAIR_EXHAUSTED", "PROVIDER_EXCEPTION", "STREAM_INCOMPLETE", "MODEL_CONNECTION_FAILED", "MODEL_TIMEOUT")
        codes.forEach { attempt.failed(it) }
        attempt.failed("private server failure with header")
        attempt.result(ToolCall("a", "run_command", "{}"), ToolResult("""{"tool":"run_command","environment":"android","exit_code":127,"timed_out":true,"ok":false,"code":"SHELL_COMMAND_NOT_FOUND"}"""))
        assertEquals(codes + "other", capture.stage("failed").map { it.getString("code") })
        val result = capture.stage("result").single()
        assertEquals("SHELL_COMMAND_NOT_FOUND", result.getString("code"))
        assertEquals(127, result.getInt("exit_code"))
        assertTrue(result.getBoolean("timed_out"))
        assertFalse(result.getBoolean("ok"))
    }

    @Test fun blankCallIdDoesNotSplitValidationAndResultCorrelation() {
        val capture = Capture()
        val attempt = capture.begin()
        val call = ToolCall("", "terminal", "{}")
        attempt.parsed(call, 0)
        attempt.validation(call, false)
        attempt.dispatch(call)
        attempt.result(call, ToolResult("{}"))
        assertEquals(1, capture.records().filter { it.has("call") }.map { it.getInt("call") }.distinct().size)
    }

    @Test fun unknownEventsAndAssistantTextAreIgnored() {
        val capture = Capture()
        val attempt = capture.begin()
        val count = capture.lines.size
        attempt.responsesEvent(JSONObject().put("type", "HOSTILE_EVENT").put("content", "SECRET_TEXT"))
        attempt.responsesEvent(JSONObject().put("type", "response.output_text.delta").put("delta", "SECRET_TEXT"))
        attempt.responsesEvent(added(JSONObject().put("type", "message").put("content", "SECRET_TEXT"), 0))
        assertEquals(count, capture.lines.size)
        attempt.providerParsed(JSONObject().put("content", "SECRET_TEXT"))
        assertTrue(capture.stage("provider_parsed").isEmpty())
        assertEquals("missing", capture.stage("provider_parsed_summary").single().getString("tool_calls_kind"))
        assertFalse(capture.lines.joinToString().contains("SECRET_TEXT"))
    }

    @Test fun identicalAnonymousCallsStaySeparateWhenOrdinalsAreKnown() {
        val capture = Capture()
        val attempt = capture.begin()
        val call = ToolCall("", "run_command", "{}")
        val envelope = assistant(call)
        envelope.getJSONArray("tool_calls").put(assistant(call).getJSONArray("tool_calls").getJSONObject(0))
        attempt.providerParsed(envelope)
        repeat(2) { attempt.parsed(call, it) }
        repeat(2) { attempt.validation(call, false, it) }
        val parsed = capture.stage("parsed")
        val checked = capture.stage("validation")
        assertNotEquals(parsed[0].getInt("call"), parsed[1].getInt("call"))
        assertEquals(parsed.map { it.getInt("call") }, checked.map { it.getInt("call") })
        assertTrue(checked.all { it.getBoolean("ambiguous_anonymous") })
    }

    @Test fun mergedRawAliasesDoNotConsumeAnotherOrdinalFallbackSlot() {
        val capture = Capture()
        val attempt = capture.begin()
        attempt.responsesEvent(JSONObject().put("type", "response.function_call_arguments.delta")
            .put("item_id", "private-item-id").put("delta", "{"))
        attempt.responsesEvent(JSONObject().put("type", "response.function_call_arguments.delta")
            .put("call_id", "wire-first").put("delta", "}"))
        attempt.responsesEvent(added(item(ToolCall("wire-first", "terminal", "{}")), 0))
        attempt.responsesEvent(added(item(ToolCall("wire-second", "terminal", "{}"), "second-item"), 1))
        attempt.parsed(ToolCall("generated-first", "terminal", "{}"), 0)
        attempt.parsed(ToolCall("generated-second", "terminal", "{}"), 1)
        val added = capture.stage("raw_added")
        val parsed = capture.stage("parsed")
        assertNotEquals(parsed[0].getInt("call"), parsed[1].getInt("call"))
        assertEquals(added.map { it.getInt("call") }, parsed.map { it.getInt("call") })
    }

    private fun exercise(attempt: AgentToolCallDiagnostics.Attempt) {
        val call = ToolCall("call", "terminal", "{}")
        attempt.responsesEvent(added(item(call), 0))
        attempt.responsesEvent(done("{}"))
        attempt.providerParsed(assistant(call))
        attempt.parsed(call, 0)
        attempt.validation(call, true)
        attempt.dispatch(call)
        attempt.result(call, ToolResult("{}"))
        attempt.failed("timeout")
    }

    private fun item(call: ToolCall, id: String = "private-item-id"): JSONObject = JSONObject()
        .put("type", "function_call").put("id", id).put("call_id", call.id)
        .put("name", call.name).put("arguments", call.argumentsJson)

    private fun added(item: JSONObject, index: Int): JSONObject = JSONObject()
        .put("type", "response.output_item.added").put("output_index", index).put("item", item)

    private fun delta(args: String, itemId: String = "private-item-id"): JSONObject = JSONObject()
        .put("type", "response.function_call_arguments.delta").put("item_id", itemId).put("delta", args)

    private fun done(args: String, itemId: String = "private-item-id"): JSONObject = JSONObject()
        .put("type", "response.function_call_arguments.done").put("item_id", itemId).put("arguments", args)

    private fun terminal(status: String, output: JSONArray): JSONObject = JSONObject()
        .put("type", "response.$status").put("response", JSONObject().put("output", output))

    private fun assistant(call: ToolCall): JSONObject = JSONObject().put("tool_calls", JSONArray().put(
        JSONObject().put("id", call.id).put("type", "function")
            .put("function", JSONObject().put("name", call.name).put("arguments", call.argumentsJson)),
    ))

    private fun assertSafeLines(lines: List<String>) {
        assertTrue(lines.isNotEmpty())
        lines.forEach {
            assertTrue(it.startsWith("ToolCallDiag "))
            assertTrue(it.toByteArray(Charsets.UTF_8).size <= 4096)
            assertFalse(it.contains('\n'))
            assertFalse(it.contains('\r'))
            assertTrue(it.all { char -> char.code in 32..126 })
            JSONObject(it.removePrefix("ToolCallDiag "))
        }
    }

    private class Capture {
        var on = true
        val lines = mutableListOf<String>()
        val diagnostics = AgentToolCallDiagnostics(enabled = { on }, sink = { lines.add(it) })
        fun begin(round: Int = 1, provider: String = "openai_responses"): AgentToolCallDiagnostics.Attempt =
            requireNotNull(diagnostics.beginAttempt(round, provider))
        fun records(): List<JSONObject> = lines.map { JSONObject(it.removePrefix("ToolCallDiag ")) }
        fun stage(stage: String): List<JSONObject> = records().filter { it.optString("stage") == stage }
    }
}
