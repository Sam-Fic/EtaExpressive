package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.model.AgentModelClient.ToolCall
import io.github.mangi.eta.agent.model.AgentModelClient.ToolResult
import io.github.mangi.eta.core.AppFileLogger
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * One instance per run, never a process singleton. Evidence only: no tool execution or repair.
 * All retained external identifiers are keyed hashes; no event, argument or result is retained.
 * Hashes are HMAC-SHA256 (128-bit display), domain separated. Identifier/argument hashes use
 * UTF-16BE code units, preserving split streaming pairs. Body/result hashes use final UTF-8 bytes.
 * The random run key is lazy, never exported, and is shared by all attempts in this instance.
 */
internal class AgentToolCallDiagnostics(
    private val enabled: () -> Boolean = { AppFileLogger.isEnabled() },
    private val sink: (String) -> Unit = { AppFileLogger.info(it) },
) {
    private val lock = Any()
    private var key: SecretKeySpec? = null
    private var runId: String? = null
    private var records = 0L
    private var attempts = 0L
    private var droppedDetails = 0L
    private var oversizedRecords = 0L
    private var sinkFailures = 0L
    private var calls = 0L

    fun beginAttempt(round: Int, providerId: String): Attempt? = try {
        if (!isEnabled()) null else synchronized(lock) {
            if (!isEnabled()) null else {
                if (key == null) {
                    val random = SecureRandom()
                    key = SecretKeySpec(ByteArray(32).also(random::nextBytes), "HmacSHA256")
                    runId = hex(ByteArray(12).also(random::nextBytes))
                }
                Attempt(++attempts, round).also {
                    it.emit("attempt", JSONObject().put("provider_hmac", fingerprint("provider", providerId)))
                }
            }
        }
    } catch (_: Throwable) { null }

    inner class Attempt internal constructor(private val attemptId: Long, private val round: Int) {
        private var records = 0
        private var detailDropped = 0L
        private var usageOrdinal = 0L
        private val criticalRecords = HashMap<String, Int>()
        private val tracked = ArrayList<CallState>()
        private val aliases = HashMap<String, CallState>()
        private val positions = HashMap<Int, CallState>()
        private var droppedCalls = 0L
        private var orphanDeltas = 0L
        private var orphanDeltaChars = 0L
        private var sawTerminal = false
        private var sawToolCall = false

        fun responsesEvent(event: JSONObject) = safely {
            when (event.opt("type") as? String) {
                "response.function_call_arguments.delta" -> {
                    sawToolCall = true
                    val hasIdentity = listOf(event.opt("item_id"), event.opt("call_id"))
                        .any { it is String && it.isNotBlank() } || index(event.opt("output_index")) != null
                    val call = if (hasIdentity) resolveRaw(event, null, null) else null
                    val delta = event.opt("delta") as? String
                    if (call == null) {
                        orphanDeltas = add(orphanDeltas, 1)
                        orphanDeltaChars = add(orphanDeltaChars, delta?.length?.toLong() ?: 0)
                    } else {
                        call.deltaCount = add(call.deltaCount, 1)
                        if (delta == null) call.nonStringDeltas = add(call.nonStringDeltas, 1)
                        else {
                            call.deltaChars = add(call.deltaChars, delta.length.toLong())
                            val digest = call.deltaDigest ?: newMac("arguments").also { call.deltaDigest = it }
                            update(digest, delta)
                        }
                    }
                }
                "response.output_item.added", "response.output_item.done" -> {
                    val item = event.optJSONObject("item") ?: return@safely
                    if (item.opt("type") != "function_call") return@safely
                    val stage = if (event.opt("type") == "response.output_item.added") "raw_added" else "raw_item_done"
                    rawItem(stage, event, item, null)
                }
                "response.function_call_arguments.done" -> {
                    sawToolCall = true
                    val call = resolveRaw(event, null, null) ?: return@safely
                    summarizeDeltas(call)
                    val fields = rawEnvelope(event, null)
                    toolMetadata(fields, event.opt("name"))
                    argumentsMetadata(fields, event.opt("arguments"))
                    emit("raw_args_done", fields, call)
                }
                "response.completed", "response.incomplete", "response.failed" -> {
                    sawTerminal = true
                    val status = when (event.opt("type")) {
                        "response.completed" -> "completed"
                        "response.incomplete" -> "incomplete"
                        else -> "failed"
                    }
                    val response = event.optJSONObject("response")
                    val output = response?.optJSONArray("output")
                    for (index in 0 until minOf(output?.length() ?: 0, MAX_OUTPUT_SCAN)) {
                        val item = output?.optJSONObject(index) ?: continue
                        if (item.opt("type") == "function_call") rawItem("raw_terminal", event, item, index, status)
                    }
                    tracked.forEach { summarizeDeltas(it) }
                    emit("raw_terminal_summary", counts().put("terminal_status", status)
                        .put("output_kind", kind(response?.opt("output")))
                        .put("output_count", output?.length() ?: 0)
                        .put("output_scan_capped", (output?.length() ?: 0) > MAX_OUTPUT_SCAN)
                        .put("code", safeCode(response?.optJSONObject("error")?.opt("code")
                            ?: event.optJSONObject("error")?.opt("code"))))
                    orphanDeltas = 0
                    orphanDeltaChars = 0
                }
            }
        }

        /** Observe the provider's assistant object before Codec normalizes missing arguments. */
        fun providerParsed(assistant: JSONObject) = safely {
            val toolCalls = assistant.optJSONArray("tool_calls")
            if ((toolCalls?.length() ?: 0) > 0) sawToolCall = true
            emit("provider_parsed_summary", JSONObject()
                .put("tool_calls_kind", kind(assistant.opt("tool_calls")))
                .put("tool_calls_count", toolCalls?.length() ?: 0))
            for (index in 0 until minOf(toolCalls?.length() ?: 0, MAX_CALLS)) {
                val envelope = toolCalls?.optJSONObject(index)
                val call = resolve(listOf(envelope?.opt("id")), position = index) ?: continue
                summarizeDeltas(call)
                val function = envelope?.optJSONObject("function")
                val fields = JSONObject().put("index", index)
                    .put("call_kind", kind(toolCalls?.opt(index)))
                    .put("function_kind", kind(envelope?.opt("function")))
                field(fields, "id", envelope?.opt("id"), "id")
                toolMetadata(fields, function?.opt("name"))
                argumentsMetadata(fields, function?.opt("arguments"))
                emit("provider_parsed", fields, call)
            }
        }

        fun parsed(call: ToolCall, index: Int) = safely {
            val state = resolveCall(call, index) ?: return@safely
            summarizeDeltas(state)
            emit("parsed", callMetadata(call).put("index", index), state)
        }

        fun validation(call: ToolCall, accepted: Boolean, index: Int? = null) = safely {
            val state = resolveCall(call, index) ?: return@safely
            emit("validation", callMetadata(call).put("accepted", accepted), state)
        }

        fun dispatch(call: ToolCall, index: Int? = null) = safely {
            val state = resolveCall(call, index) ?: return@safely
            emit("dispatch", callMetadata(call), state)
        }

        /**
         * [result] is the final content paired into history (after local guards such as
         * [AgentShellFailureGuard]); [rawResult] is the executor output before those guards.
         */
        fun result(call: ToolCall, result: ToolResult, index: Int? = null, rawResult: ToolResult? = null) = safely {
            val state = resolveCall(call, index) ?: return@safely
            val fields = callMetadata(call)
            // Only lengths and run-keyed fingerprints, never stdout/stderr/message or sensitive prose.
            contentMetadata(fields, "raw_result", rawResult?.content ?: result.content)
            contentMetadata(fields, "guarded_result", result.content)
            val parsed = parseObject(result.content, MAX_RESULT_CHARS)
            fields.put("result_state", parsed.first)
            val body = parsed.second
            if (call.name == "terminal" || call.name == "run_command") {
                field(fields, "actual_tool", body?.opt("tool"), "tool")
                fields.put("actual_tool", toolLabel(body?.opt("tool")))
                fields.put("actual_environment", enumValue(body?.opt("environment"), ENVIRONMENTS))
                fields.put("actual_environment_kind", kind(body?.opt("environment")))
                val exitCode = body?.opt("exit_code")
                // Do not coerce strings, custom Number.toString(), or arbitrary numeric values.
                if (exitCode is Int || exitCode is Long) {
                    val number = (exitCode as Number).toLong()
                    if (number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) fields.put("exit_code", number)
                }
                (body?.opt("timed_out") as? Boolean)?.let { fields.put("timed_out", it) }
            }
            (body?.opt("ok") as? Boolean)?.let { fields.put("ok", it) }
            fields.put("code", safeCode(body?.opt("code")))
            if (rawResult != null && rawResult.content != result.content) {
                val raw = parseObject(rawResult.content, MAX_RESULT_CHARS).second
                fields.put("guard_annotated", true)
                (raw?.opt("ok") as? Boolean)?.let { fields.put("raw_ok", it) }
                fields.put("raw_code", safeCode(raw?.opt("code")))
            }
            shellGuardFields(body?.optJSONObject("shell_failure_diagnostic"), fields)
            emit("result", fields, state)
        }

        fun historyResult(call: ToolCall, message: JSONObject) = safely {
            val state = resolveCall(call, null) ?: return@safely
            emit("history_result", JSONObject().also {
                contentMetadata(it, "history_result", message.optString("content"))
            }, state)
        }

        /** Receipt as received, not merged with earlier usage and not an accounting change. */
        fun usage(usage: AgentTokenUsage) = safely {
            val fields = JSONObject().put("receipt_ordinal", ++usageOrdinal)
            for ((name, value) in listOf("input_tokens" to usage.inputTokens, "output_tokens" to usage.outputTokens,
                "cached_tokens" to usage.cachedTokens, "cache_creation_tokens" to usage.cacheCreationTokens,
                "context_tokens" to usage.contextTokens, "reasoning_tokens" to usage.reasoningTokens)) {
                fields.put("${name}_present", value != null)
                value?.let { fields.put(name, it) }
            }
            // 命中率只在 provider 真的上报了 cached_tokens 时才有意义：
            // 很多 OpenAI 兼容网关根本不返回该字段，此时 0% 只代表「未上报」。
            val cached = usage.cachedTokens
            fields.put("cached_reported", cached != null)
            val input = usage.inputTokens
            if (cached != null && input != null && input > 0) {
                fields.put("cache_hit_ratio", (cached.toDouble() / input.toDouble()).coerceIn(0.0, 1.0))
            }
            emit("usage", fields)
        }

        /** Called with the very string used to create the HTTP request body. No retained body. */
        fun requestShape(fields: JSONObject, serializedBody: String?, body: JSONObject) = safely {
            if (serializedBody != null) {
                fields.put("body_hmac", utf8Fingerprint("serialized_body", serializedBody))
                fields.put("body_hmac_basis", "serialized_utf8")
                fields.put("body_chars", serializedBody.length)
            }
            val seen = HashSet<String>()
            var missing = 0L; var duplicate = 0L; var untracked = 0L
            val input = body.optJSONArray("input")
            for (i in 0 until (input?.length() ?: 0)) {
                val item = input?.optJSONObject(i) ?: continue
                if (item.optString("type") != "reasoning") continue
                val id = item.opt("id") as? String
                if (id.isNullOrBlank()) { missing++; continue }
                val hash = fingerprint("reasoning_id", id)
                if (hash in seen) duplicate++
                else if (seen.size < MAX_REASONING_IDS) seen.add(hash) else untracked++
            }
            fields.put("reasoning_id_missing", missing).put("reasoning_id_duplicates", duplicate)
                .put("reasoning_id_tracking_capped", untracked > 0).put("reasoning_ids_untracked", untracked)
                .put("reasoning_duplicate_count_is_lower_bound", untracked > 0)
            emit("request_shape", fields)
        }

        private fun contentMetadata(fields: JSONObject, prefix: String, content: String) {
            fields.put("${prefix}_chars", content.length)
                .put("${prefix}_utf8_bytes", utf8Size(content))
                .put("${prefix}_hmac", utf8Fingerprint("tool_result", content))
        }

        /** Only fixed counters and flags; never the executable name or command text. */
        private fun shellGuardFields(diagnostic: JSONObject?, fields: JSONObject) {
            if (diagnostic == null) return
            val failures = diagnostic.optJSONArray("failures")
            var maxAttempt = 0
            if (failures != null) for (i in 0 until minOf(failures.length(), MAX_CALLS)) {
                val attempt = failures.optJSONObject(i)?.opt("attempt")
                if (attempt is Int && attempt in 1..AgentShellFailureGuard.MAX_FAILURES) maxAttempt = maxOf(maxAttempt, attempt)
            }
            fields.put("shell_missing_count", minOf(failures?.length() ?: 0, MAX_CALLS))
            if (maxAttempt > 0) fields.put("shell_failure_attempt", maxAttempt)
            fields.put("shell_failure_max", AgentShellFailureGuard.MAX_FAILURES)
            (diagnostic.opt("stop_after_batch") as? Boolean)?.let { fields.put("shell_stop_after_batch", it) }
        }

        fun failed(code: String) = safely {
            tracked.forEach { summarizeDeltas(it) }
            emit("failed", counts().put("code", safeCode(code)))
            orphanDeltas = 0
            orphanDeltaChars = 0
        }

        private fun counts(): JSONObject = JSONObject().put("saw_terminal", sawTerminal)
            .put("saw_tool_call", sawToolCall).put("tracked_calls", tracked.size)
            .put("dropped_calls", droppedCalls).put("untracked_delta_count", orphanDeltas)
            .put("untracked_delta_chars", orphanDeltaChars)

        private fun rawItem(stage: String, event: JSONObject, item: JSONObject, outputIndex: Int?, status: String? = null) {
            sawToolCall = true
            val call = resolveRaw(event, item, outputIndex) ?: return
            if (stage != "raw_added") summarizeDeltas(call)
            val fields = rawEnvelope(event, item)
            if (outputIndex != null) fields.put("output_index", outputIndex)
            if (status != null) fields.put("terminal_status", status)
            toolMetadata(fields, item.opt("name"))
            argumentsMetadata(fields, item.opt("arguments"))
            emit(stage, fields, call)
        }

        private fun rawEnvelope(event: JSONObject, item: JSONObject?): JSONObject {
            val fields = JSONObject().put("item_kind", kind(event.opt("item")))
            field(fields, "item_id", event.opt("item_id"), "id")
            field(fields, "id", item?.opt("id"), "id")
            field(fields, "call_id", item?.opt("call_id") ?: event.opt("call_id"), "id")
            fields.put("output_index_kind", kind(event.opt("output_index")))
            index(event.opt("output_index"))?.let { fields.put("output_index", it) }
            return fields
        }

        private fun callMetadata(call: ToolCall): JSONObject = JSONObject().also {
            field(it, "id", call.id, "id")
            toolMetadata(it, call.name)
            argumentsMetadata(it, call.argumentsJson)
        }

        private fun resolveRaw(event: JSONObject, item: JSONObject?, outputIndex: Int?): CallState? {
            val output = outputIndex ?: index(event.opt("output_index"))
            return resolve(
                ids = listOf(event.opt("item_id"), event.opt("call_id"), item?.opt("id"), item?.opt("call_id")),
                outputIndex = output,
            )?.also {
                it.raw = true
                if (output != null && it.outputIndex == null) it.outputIndex = output
            }
        }

        private fun resolveCall(call: ToolCall, position: Int? = null): CallState? {
            sawToolCall = true
            // Blank IDs should not split an otherwise identical call across validation/result.
            val fallback = if (call.id.isBlank()) fingerprint("anonymous_tool", call.name) + fingerprint("arguments", call.argumentsJson) else null
            if (fallback != null && position != null) {
                // An explicit tool ordinal distinguishes identical anonymous calls. Retain only
                // keyed metadata, never ToolCall objects or their argument payloads.
                val state = resolve(emptyList(), position = position) ?: return null
                val alias = "anonymous:$fallback"
                val previous = aliases[alias]
                if (previous != null && previous !== state) {
                    previous.ambiguousAnonymous = true
                    state.ambiguousAnonymous = true
                } else if (aliases.size < MAX_ALIASES || previous != null) aliases[alias] = state
                return state
            }
            return resolve(listOf(call.id), position = position, fallback = fallback)
        }

        /** Bounded aliases join Responses item IDs, call IDs and output indices to local call IDs. */
        private fun resolve(ids: List<Any?>, outputIndex: Int? = null, position: Int? = null, fallback: String? = null): CallState? {
            val keys = ids.filterIsInstance<String>().filter { it.isNotBlank() }
                .map { "id:" + fingerprint("id", it) }.toMutableList()
            if (outputIndex != null) keys.add("output:$outputIndex")
            if (fallback != null) keys.add("anonymous:$fallback")
            val matches = keys.mapNotNull { aliases[it] }.distinct()
            var state = matches.minByOrNull { it.sequence }
            if (state == null && position != null) {
                state = positions[position]
                // Output indices include non-tool items. Fallback uses tool order, not the raw
                // output index. Mark this weaker correlation when a provider synthesizes IDs.
                if (state == null) state = tracked.filter { it.raw }
                    .sortedBy { it.outputIndex ?: Int.MAX_VALUE }.getOrNull(position)
                if (state != null) state.positional = true
            }
            if (state == null) {
                if (tracked.size >= MAX_CALLS) {
                    droppedCalls = add(droppedCalls, 1)
                    return null
                }
                state = CallState(++calls)
                tracked.add(state)
            }
            val target = state
            for (other in matches) {
                if (other === target) continue
                summarizeDeltas(other)
                emit("call_link", JSONObject().put("previous_call", other.sequence), target)
                aliases.entries.forEach { if (it.value === other) it.setValue(target) }
                positions.entries.forEach { if (it.value === other) it.setValue(target) }
                target.raw = target.raw || other.raw
                if (target.outputIndex == null) target.outputIndex = other.outputIndex
                target.positional = target.positional || other.positional
                target.ambiguousAnonymous = target.ambiguousAnonymous || other.ambiguousAnonymous
                // Merged aliases must not leave a duplicate in subsequent ordinal fallback.
                tracked.remove(other)
            }
            keys.forEach { if (aliases.size < MAX_ALIASES || aliases.containsKey(it)) aliases[it] = target }
            if (position != null && positions.size < MAX_CALLS) positions[position] = target
            return target
        }

        private fun summarizeDeltas(call: CallState) {
            if (call.deltaCount == 0L) return
            val fields = JSONObject().put("delta_count", call.deltaCount)
                .put("delta_scope", "since_last_summary").put("delta_chars", call.deltaChars)
                .put("delta_utf16_bytes", add(call.deltaChars, call.deltaChars))
                .put("non_string_deltas", call.nonStringDeltas)
            call.deltaDigest?.let { fields.put("arguments_hmac", hex(it.doFinal().copyOf(16))) }
            emit("raw_delta_summary", fields, call)
            call.deltaDigest = null
            call.deltaCount = 0
            call.deltaChars = 0
            call.nonStringDeltas = 0
        }

        private fun safely(block: () -> Unit) {
            try {
                if (!isEnabled()) return
                synchronized(lock) {
                    if (isEnabled()) block()
                }
            } catch (_: Throwable) { /* Diagnostics must never change provider/tool outcomes. */ }
        }

        internal fun emit(stage: String, fields: JSONObject, call: CallState? = null) {
            if (!isEnabled()) return
            val critical = stage in CRITICAL_STAGES
            if (!critical && records >= MAX_ATTEMPT_RECORDS) {
                detailDropped = add(detailDropped, 1)
                droppedDetails = add(droppedDetails, 1)
                return
            }
            // Separate bounded quota per critical stage, reset for every request attempt.
            val used = criticalRecords[stage] ?: 0
            if (critical && used >= MAX_CRITICAL_STAGE_RECORDS) {
                detailDropped = add(detailDropped, 1)
                droppedDetails = add(droppedDetails, 1)
                return
            }
            fields.put("stage", stage).put("run", runId).put("attempt", attemptId).put("round", round)
                .put("seq", this@AgentToolCallDiagnostics.records + 1)
            if (critical) fields.put("detail_dropped_attempt", detailDropped).put("detail_dropped_total", droppedDetails)
                .put("detail_exhausted", records >= MAX_ATTEMPT_RECORDS).put("oversized_records", oversizedRecords)
                .put("sink_failures", sinkFailures)
            if (call != null) fields.put("call", call.sequence).put("positional_correlation", call.positional)
                .put("ambiguous_anonymous", call.ambiguousAnonymous)
            val line = PREFIX + fields.toString()
            // All output is generated ASCII, fixed keys/enums and hashes. Keep JSON intact.
            if (line.length > MAX_LINE_CHARS) { oversizedRecords = add(oversizedRecords, 1); return }
            if (critical) criticalRecords[stage] = used + 1 else records++
            this@AgentToolCallDiagnostics.records++
            // FileLogSink is a bounded rolling log. Never permanently stop later run attempts.
            try { if (isEnabled()) sink(line) } catch (_: Throwable) { sinkFailures = add(sinkFailures, 1) }
        }
    }

    internal class CallState(val sequence: Long) {
        var raw = false
        var outputIndex: Int? = null
        var positional = false
        var ambiguousAnonymous = false
        var deltaCount = 0L
        var deltaChars = 0L
        var nonStringDeltas = 0L
        var deltaDigest: Mac? = null
    }

    private fun toolMetadata(fields: JSONObject, name: Any?) {
        field(fields, "tool", name, "tool")
        fields.put("tool", toolName(name))
    }

    /**
     * 可读工具名进日志：工具名本身不涉密，保留原名才能按 `task_dispatch` 等字样 grep。
     * 只接受短标识字符集，避免把任意内容（含空白/换行）带进日志。
     */
    private fun toolName(name: Any?): String {
        val raw = name as? String ?: return toolLabel(name)
        return raw.takeIf { it.length in 1..64 && it.all { c -> c.isLetterOrDigit() || c in "_.:-" } }
            ?: "unknown"
    }

    private fun toolLabel(name: Any?): String = when (name) {
        null -> "missing"
        JSONObject.NULL -> "null"
        "terminal" -> "terminal"
        "run_command" -> "run_command"
        else -> "other"
    }

    private fun argumentsMetadata(fields: JSONObject, value: Any?) {
        field(fields, "arguments", value, "arguments")
        if (value is JSONObject) {
            // Non-string envelopes have no original wire string. Compare bounded JSONObject
            // serialization to the provider's later string, explicitly labeling the basis.
            val text = boundedObjectText(value)
            fields.put("arguments_hmac_basis", if (text == null) "unavailable" else "object_serialized")
            if (text != null) fields.put("arguments_chars", text.length)
                .put("arguments_hmac", fingerprint("arguments", text))
        }
        val parsed = when (value) {
            null -> "missing" to null
            JSONObject.NULL -> "null" to null
            is JSONObject -> (if (value.length() == 0) "empty_object" else "object") to value
            is String -> parseObject(value, MAX_ARGUMENT_CHARS)
            else -> "wrong_type" to null
        }
        fields.put("arguments_state", parsed.first)
        val args = parsed.second ?: return
        fields.put("argument_key_count", args.length())
        // Fixed field names only; unknown argument keys and all their values are never copied.
        field(fields, "command", args.opt("command"), "command")
        fields.put("requested_environment", enumValue(args.opt("environment"), ENVIRONMENTS))
        fields.put("environment_kind", kind(args.opt("environment")))
        fields.put("requested_action", enumValue(args.opt("action"), ACTIONS))
        fields.put("action_kind", kind(args.opt("action")))
    }

    private fun field(fields: JSONObject, name: String, value: Any?, domain: String) {
        fields.put(name + "_present", value != null).put(name + "_kind", kind(value))
        when (value) {
            is String -> fields.put(name + "_chars", value.length).put(name + "_hmac", fingerprint(domain, value))
            is JSONObject -> fields.put(name + "_key_count", value.length())
            is JSONArray -> fields.put(name + "_count", value.length())
        }
    }

    /** Transient, bounded serialization only for object-valued arguments; never retained/logged. */
    private fun boundedObjectText(value: JSONObject): String? {
        val out = StringBuilder()
        fun append(text: String): Boolean {
            if (text.length > MAX_ARGUMENT_CHARS - out.length) return false
            out.append(text)
            return true
        }
        fun quoted(text: String): Boolean = text.length <= MAX_ARGUMENT_CHARS && append(JSONObject.quote(text))
        fun write(value: Any?, depth: Int): Boolean {
            if (depth > MAX_JSON_DEPTH) return false
            return when (value) {
                null, JSONObject.NULL -> append("null")
                is String -> quoted(value)
                is Boolean -> append(if (value) "true" else "false")
                is Int, is Long, is Short, is Byte, is Double, is Float -> append(JSONObject.numberToString(value as Number))
                is JSONObject -> {
                    if (!append("{") || value.length() > MAX_ARGUMENT_CHARS) return false
                    val keys = value.keys()
                    var first = true
                    while (keys.hasNext()) {
                        val key = keys.next()
                        if (!first && !append(",")) return false
                        if (!quoted(key) || !append(":") || !write(value.opt(key), depth + 1)) return false
                        first = false
                    }
                    append("}")
                }
                is JSONArray -> {
                    if (!append("[") || value.length() > MAX_ARGUMENT_CHARS) return false
                    for (index in 0 until value.length()) {
                        if (index > 0 && !append(",")) return false
                        if (!write(value.opt(index), depth + 1)) return false
                    }
                    append("]")
                }
                else -> false // Never invoke arbitrary objects' toString().
            }
        }
        return try { if (write(value, 0)) out.toString() else null } catch (_: Throwable) { null }
    }

    private fun parseObject(text: String, maxChars: Int): Pair<String, JSONObject?> {
        if (text.isEmpty()) return "empty_string" to null
        if (text.length > maxChars) return "too_large" to null
        if (text.isBlank()) return "blank_string" to null
        // Bound recursive JSON parsers before invoking either implementation.
        if (!boundedDepth(text)) return "too_deep" to null
        return try {
            val element = Json.parseToJsonElement(text)
            if (!strictValues(element)) "malformed" to null
            else if (element !is JsonObject) "non_object" to null
            else {
                val body = JSONObject(text)
                (if (body.length() == 0) "empty_object" else "object") to body
            }
        } catch (_: Throwable) { "malformed" to null }
    }

    // Json tree parsing can represent unquoted literals that are not valid JSON primitives.
    private fun strictValues(value: JsonElement): Boolean = when (value) {
        is JsonObject -> value.values.all(::strictValues)
        is JsonArray -> value.all(::strictValues)
        is JsonPrimitive -> value.isString || value === JsonNull || value.content == "true" ||
            value.content == "false" || JSON_NUMBER.matches(value.content)
    }

    private fun boundedDepth(text: String): Boolean {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> if (++depth > MAX_JSON_DEPTH) return false
                '}', ']' -> depth--
            }
        }
        return true
    }

    private fun kind(value: Any?): String = when {
        value == null -> "missing"
        value === JSONObject.NULL -> "null"
        value is String -> "string"
        value is JSONObject -> "object"
        value is JSONArray -> "array"
        value is Boolean -> "boolean"
        value is Number -> "number"
        else -> "other"
    }

    private fun enumValue(value: Any?, allowed: Set<String>): String = when {
        value == null -> "missing"
        value === JSONObject.NULL -> "null"
        value is String && value in allowed -> value
        else -> "other"
    }

    private fun safeCode(value: Any?): String = when {
        value == null || value === JSONObject.NULL -> "none"
        value is String && value in ERROR_CODES -> value
        else -> "other"
    }

    private fun index(value: Any?): Int? = when (value) {
        is Int -> value.takeIf { it >= 0 }
        is Long -> value.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
        else -> null
    }

    private fun isEnabled(): Boolean = try { enabled() } catch (_: Throwable) { false }

    private fun newMac(domain: String): Mac = Mac.getInstance("HmacSHA256").also {
        it.init(checkNotNull(key))
        it.update(domain.toByteArray(Charsets.US_ASCII))
        it.update(0.toByte())
    }

    private fun utf8Size(value: String): Long {
        var size = 0L; var i = 0
        while (i < value.length) {
            val c = value[i++]
            size += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i < value.length && value[i].isLowSurrogate() -> { i++; 4 }
                c.isSurrogate() -> 1 // JVM UTF-8 replacement byte, same as RequestBody encoding.
                else -> 3
            }
        }
        return size
    }

    private fun utf8Fingerprint(domain: String, value: String): String = newMac(domain).let { mac ->
        // Bounded chunks, never allocate another whole large body or split a surrogate pair.
        var start = 0
        while (start < value.length) {
            var end = minOf(value.length, start + 4096)
            if (end < value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) end--
            mac.update(value.substring(start, end).toByteArray(Charsets.UTF_8))
            start = end
        }
        hex(mac.doFinal().copyOf(16))
    }

    private fun fingerprint(domain: String, value: String): String = newMac(domain).let {
        update(it, value)
        hex(it.doFinal().copyOf(16))
    }

    /** Fixed-size scratch space; unlike toByteArray this cannot allocate proportional to input. */
    private fun update(mac: Mac, value: String) {
        val bytes = ByteArray(2048)
        var offset = 0
        while (offset < value.length) {
            val count = minOf(bytes.size / 2, value.length - offset)
            for (index in 0 until count) {
                val char = value[offset + index].code
                bytes[index * 2] = (char ushr 8).toByte()
                bytes[index * 2 + 1] = char.toByte()
            }
            mac.update(bytes, 0, count * 2)
            offset += count
        }
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            append(HEX[(byte.toInt() ushr 4) and 15])
            append(HEX[byte.toInt() and 15])
        }
    }

    private fun add(left: Long, right: Long): Long = if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object {
        const val PREFIX = "ToolCallDiag "
        const val HEX = "0123456789abcdef"
        const val MAX_LINE_CHARS = 3968 // Reserve space for AppFileLogger framing.
        const val MAX_CRITICAL_STAGE_RECORDS = 64
        const val MAX_REASONING_IDS = 4096
        val CRITICAL_STAGES = setOf("request_shape", "request_context", "usage", "result", "history_result", "failed", "provider_parsed_summary", "raw_terminal_summary")
        const val MAX_ATTEMPT_RECORDS = 128
        const val MAX_CALLS = 32
        const val MAX_ALIASES = 256
        const val MAX_OUTPUT_SCAN = 128
        const val MAX_ARGUMENT_CHARS = 32 * 1024
        const val MAX_RESULT_CHARS = 256 * 1024
        const val MAX_JSON_DEPTH = 32
        val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        val ENVIRONMENTS = setOf("android", "linux", "debian", "alpine")
        val ACTIONS = setOf("open", "exec", "open_and_exec", "read_async_result", "close", "daemon_start", "daemon_list", "daemon_logs", "daemon_stop")
        val ERROR_CODES = setOf(
            "provider_error", "model_error", "network_error", "timeout", "cancelled", "validation_rejected",
            "invalid_arguments", "dispatch_failed", "result_failed", "unknown", "rate_limit_exceeded",
            "server_error", "invalid_request_error", "context_length_exceeded", "max_output_tokens", "content_filter",
            "SHELL_COMMAND_NOT_FOUND", "SHELL_COMMAND_NOT_FOUND_REPAIR_EXHAUSTED", "TOOL_ARGUMENTS_INVALID",
            "TOOL_NOT_FOUND", "TOOL_EXECUTION_FAILED", "PERMISSION_DENIED", "TIMEOUT", "CANCELLED",
            "RESPONSES_TOOL_CALL_INCOMPLETE", "RESPONSES_TOOL_ARGUMENTS_INCOMPLETE", "INVALID_TOOL_ARGUMENTS",
            "INVALID_TOOL_ARGUMENTS_REPAIR_EXHAUSTED", "PROVIDER_EXCEPTION", "STREAM_INCOMPLETE",
            "MODEL_CONNECTION_FAILED", "MODEL_TIMEOUT",
        )
    }
}
