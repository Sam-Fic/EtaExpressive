package io.github.mangi.eta.agent.question

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * ask_user 的唯一解析/编码/校验入口。
 *
 * 三件事都收在这里，保证「模型参数」「持久化请求」「用户回答」「工具结果」使用同一套上限与词表：
 * - [parseArguments] 只接受严格类型的模型参数，越界、错类型、未知字段一律 [IllegalArgumentException]，
 *   不做字符串/数字到布尔的隐式转换，也不自动补推荐项。
 * - 请求持久化保存 questionId/conversationId/runId/toolCallId + createdAtMillis 的稳定归属，
 *   重新解码时会再次校验核心约束，避免被绕过的历史数据进入结果投影。
 * - [validateAnswer]/[resultJson] 是回答的唯一受理边界；option 的可见标签只取自运行时原始选项，
 *   不信任 UI 回传的展示文本。[resultJson] 对非法回答直接失败，绝不会产出 status=answered。
 */
internal object AgentQuestionCodec {
    const val CODE_OK = "OK"
    const val CODE_INVALID_KIND = "INVALID_KIND"
    const val CODE_OPTION_REQUIRED = "OPTION_REQUIRED"
    const val CODE_UNKNOWN_OPTION = "UNKNOWN_OPTION"
    const val CODE_OPTION_WITH_OTHER_TEXT = "OPTION_WITH_OTHER_TEXT"
    const val CODE_OTHER_NOT_ALLOWED = "OTHER_NOT_ALLOWED"
    const val CODE_OTHER_WITH_OPTION_ID = "OTHER_WITH_OPTION_ID"
    const val CODE_OTHER_TEXT_REQUIRED = "OTHER_TEXT_REQUIRED"
    const val CODE_OTHER_TEXT_TOO_LONG = "OTHER_TEXT_TOO_LONG"
    const val CODE_DELEGATION_NOT_ALLOWED = "DELEGATION_NOT_ALLOWED"
    const val CODE_DELEGATE_WITH_OPTION_ID = "DELEGATE_WITH_OPTION_ID"
    const val CODE_DELEGATE_WITH_OTHER_TEXT = "DELEGATE_WITH_OTHER_TEXT"
    const val CODE_NOTE_NOT_ALLOWED = "NOTE_NOT_ALLOWED"
    const val CODE_NOTE_TOO_LONG = "NOTE_TOO_LONG"

    const val STATUS_ANSWERED = "answered"
    const val KEY_SELECTED_OPTION = "selected_option"

    const val MAX_TITLE_CHARS = 200
    const val MAX_QUESTION_CHARS = 4_000
    const val MIN_OPTIONS = 2
    const val MAX_OPTIONS = 8
    const val MAX_OPTION_ID_CHARS = 64
    const val MAX_OPTION_LABEL_CHARS = 200
    const val MAX_OPTION_DESCRIPTION_CHARS = 1_000
    const val MAX_OTHER_TEXT_CHARS = 2_000
    const val MAX_NOTE_CHARS = 2_000

    /** 参数 UTF-8 总长度硬上限，避免把超长选项正文塞进事件与持久化。 */
    const val MAX_ARGUMENTS_BYTES = 24 * 1024

    private const val ARG_TITLE = "title"
    private const val ARG_QUESTION = "question"
    private const val ARG_OPTIONS = "options"
    private const val ARG_RECOMMENDED = "recommended_option_id"
    private const val ARG_ALLOW_OTHER = "allow_other"
    private const val ARG_ALLOW_DELEGATION = "allow_delegation"
    private const val ARG_ALLOW_NOTE = "allow_note"

    private const val OPTION_ID = "id"
    private const val OPTION_LABEL = "label"
    private const val OPTION_DESCRIPTION = "description"

    private const val KEY_QUESTION_ID = "question_id"
    private const val KEY_CONVERSATION_ID = "conversation_id"
    private const val KEY_RUN_ID = "run_id"
    private const val KEY_TOOL_CALL_ID = "tool_call_id"
    private const val KEY_TITLE = ARG_TITLE
    private const val KEY_QUESTION = ARG_QUESTION
    private const val KEY_OPTIONS = ARG_OPTIONS
    private const val KEY_RECOMMENDED = ARG_RECOMMENDED
    private const val KEY_ALLOW_OTHER = ARG_ALLOW_OTHER
    private const val KEY_ALLOW_DELEGATION = ARG_ALLOW_DELEGATION
    private const val KEY_ALLOW_NOTE = ARG_ALLOW_NOTE
    private const val KEY_CREATED_AT = "created_at_millis"

    private const val KEY_KIND = "kind"
    private const val KEY_OPTION_ID = "option_id"
    private const val KEY_OTHER_TEXT = "other_text"
    private const val KEY_NOTE = "note"
    private const val KEY_STATUS = "status"
    private const val KEY_QUESTION_ID_OUT = KEY_QUESTION_ID

    /** delegate 回答的输出字段：把「委托」的后续动作讲清楚，模型读到即自决推进。 */
    private const val KEY_GUIDANCE = "guidance"

    /**
     * delegate 回答的工具结果指引。没有它，模型读到裸 `kind:"delegate"` 只能自行脑补
     * （在派发会话语境下尤其容易解释成「继续等」）；明说「按最合理默认推进、不要重复
     * 提问、不要原地等待」并要求它事后说明选择理由。派发会话里「汇报」即本轮任务的
     * 终态 summary，调度台自然收到，无需另指路径。
     */
    private const val DELEGATE_GUIDANCE =
        "用户选择「交给你决定」：没有给出具体答案。不要重复提问，也不要原地等待；" +
            "基于已有上下文选择最合理的默认方案继续推进，并在后续输出中说明你的选择与理由。"

    private val ARG_KEYS = setOf(
        ARG_TITLE, ARG_QUESTION, ARG_OPTIONS, ARG_RECOMMENDED,
        ARG_ALLOW_OTHER, ARG_ALLOW_DELEGATION, ARG_ALLOW_NOTE,
    )

    private val OPTION_KEYS = setOf(OPTION_ID, OPTION_LABEL, OPTION_DESCRIPTION)

    // ---------------------------------------------------------------------
    // 模型参数
    // ---------------------------------------------------------------------

    fun parseArguments(
        argumentsJson: String,
        conversationId: String,
        runId: String,
        toolCallId: String,
        questionId: String,
        createdAtMillis: Long,
    ): AgentQuestionRequest {
        require(questionId.isNotBlank()) { "question_id 不能为空" }
        if (argumentsJson.isBlank()) throw IllegalArgumentException("ask_user 缺少参数对象")
        if (argumentsJson.toByteArray(Charsets.UTF_8).size > MAX_ARGUMENTS_BYTES) {
            throw IllegalArgumentException("ask_user 参数超过 $MAX_ARGUMENTS_BYTES 字节上限")
        }
        val tokener = JSONTokener(argumentsJson)
        val parsed = runCatching { tokener.nextValue() }
            .getOrElse { throw IllegalArgumentException("ask_user 参数不是合法 JSON") }
        if (parsed !is JSONObject) throw IllegalArgumentException("ask_user 参数必须是 JSON 对象")
        val trailing = runCatching { tokener.nextClean() }.getOrDefault('\u0000')
        if (trailing != '\u0000') throw IllegalArgumentException("ask_user 参数包含多余内容")
        parsed.keys().forEach { key ->
            if (key !in ARG_KEYS) throw IllegalArgumentException("ask_user 不支持参数: $key")
        }

        val title = requireBoundedText(parsed, ARG_TITLE, MAX_TITLE_CHARS)
        val question = requireBoundedText(parsed, ARG_QUESTION, MAX_QUESTION_CHARS)
        val options = parseOptions(parsed.opt(ARG_OPTIONS))
        val ids = options.mapTo(linkedSetOf()) { it.id }
        val recommended = optionalString(parsed, ARG_RECOMMENDED)
        if (recommended != null && recommended !in ids) {
            throw IllegalArgumentException("recommended_option_id 不存在: $recommended")
        }

        return AgentQuestionRequest(
            questionId = questionId,
            conversationId = conversationId,
            runId = runId,
            toolCallId = toolCallId,
            title = title,
            question = question,
            options = options,
            recommendedOptionId = recommended,
            allowOther = booleanArg(parsed, ARG_ALLOW_OTHER),
            allowDelegation = booleanArg(parsed, ARG_ALLOW_DELEGATION),
            allowNote = booleanArg(parsed, ARG_ALLOW_NOTE),
            createdAtMillis = createdAtMillis,
        )
    }

    private fun parseOptions(value: Any?): List<AgentQuestionOption> {
        if (value !is JSONArray) throw IllegalArgumentException("$ARG_OPTIONS 必须是数组")
        if (value.length() !in MIN_OPTIONS..MAX_OPTIONS) {
            throw IllegalArgumentException("$ARG_OPTIONS 数量必须在 $MIN_OPTIONS..$MAX_OPTIONS")
        }
        val options = ArrayList<AgentQuestionOption>(value.length())
        val seen = HashSet<String>()
        for (index in 0 until value.length()) {
            val item = value.opt(index)
            if (item !is JSONObject) throw IllegalArgumentException("$ARG_OPTIONS[$index] 必须是对象")
            item.keys().forEach { key ->
                if (key !in OPTION_KEYS) throw IllegalArgumentException("$ARG_OPTIONS[$index] 不支持字段: $key")
            }
            val id = requireBoundedText(item, OPTION_ID, MAX_OPTION_ID_CHARS)
            val label = requireBoundedText(item, OPTION_LABEL, MAX_OPTION_LABEL_CHARS)
            val description = optionalString(item, OPTION_DESCRIPTION, MAX_OPTION_DESCRIPTION_CHARS)
            if (!seen.add(id)) throw IllegalArgumentException("$ARG_OPTIONS id 重复: $id")
            options.add(AgentQuestionOption(id = id, label = label, description = description.orEmpty()))
        }
        return options
    }

    // ---------------------------------------------------------------------
    // 请求持久化
    // ---------------------------------------------------------------------

    fun requestToJson(request: AgentQuestionRequest): JSONObject {
        val options = JSONArray()
        request.options.forEach { option ->
            options.put(
                JSONObject()
                    .put(OPTION_ID, option.id)
                    .put(OPTION_LABEL, option.label)
                    .put(OPTION_DESCRIPTION, option.description),
            )
        }
        return JSONObject()
            .put(KEY_QUESTION_ID, request.questionId)
            .put(KEY_CONVERSATION_ID, request.conversationId)
            .put(KEY_RUN_ID, request.runId)
            .put(KEY_TOOL_CALL_ID, request.toolCallId)
            .put(KEY_TITLE, request.title)
            .put(KEY_QUESTION, request.question)
            .put(KEY_OPTIONS, options)
            .also { if (request.recommendedOptionId != null) it.put(KEY_RECOMMENDED, request.recommendedOptionId) }
            .put(KEY_ALLOW_OTHER, request.allowOther)
            .put(KEY_ALLOW_DELEGATION, request.allowDelegation)
            .put(KEY_ALLOW_NOTE, request.allowNote)
            .put(KEY_CREATED_AT, request.createdAtMillis)
    }

    fun requestFromJson(json: JSONObject): AgentQuestionRequest {
        val questionId = requireBoundedText(json, KEY_QUESTION_ID, MAX_OPTION_ID_CHARS * 16)
        val title = requireBoundedText(json, KEY_TITLE, MAX_TITLE_CHARS)
        val question = requireBoundedText(json, KEY_QUESTION, MAX_QUESTION_CHARS)
        val options = parseOptions(json.opt(KEY_OPTIONS))
        val ids = options.mapTo(linkedSetOf()) { it.id }
        val recommended = optionalString(json, KEY_RECOMMENDED)
        if (recommended != null && recommended !in ids) {
            throw IllegalArgumentException("recommended_option_id 不存在: $recommended")
        }
        val createdAt = when (val raw = json.opt(KEY_CREATED_AT)) {
            null, JSONObject.NULL -> 0L
            is Number -> raw.toLong()
            else -> throw IllegalArgumentException("$KEY_CREATED_AT 必须是数字")
        }
        return AgentQuestionRequest(
            questionId = questionId,
            conversationId = lenientString(json, KEY_CONVERSATION_ID),
            runId = lenientString(json, KEY_RUN_ID),
            toolCallId = lenientString(json, KEY_TOOL_CALL_ID),
            title = title,
            question = question,
            options = options,
            recommendedOptionId = recommended,
            allowOther = booleanArg(json, KEY_ALLOW_OTHER),
            allowDelegation = booleanArg(json, KEY_ALLOW_DELEGATION),
            allowNote = booleanArg(json, KEY_ALLOW_NOTE),
            createdAtMillis = createdAt,
        )
    }

    // ---------------------------------------------------------------------
    // 回答持久化
    // ---------------------------------------------------------------------

    fun answerToJson(answer: AgentQuestionAnswer): JSONObject =
        JSONObject()
            .put(KEY_KIND, answer.kind)
            .also { if (answer.optionId != null) it.put(KEY_OPTION_ID, answer.optionId) }
            .put(KEY_OTHER_TEXT, answer.otherText)
            .put(KEY_NOTE, answer.note)

    fun answerFromJson(json: JSONObject): AgentQuestionAnswer {
        if (!json.has(KEY_KIND)) throw IllegalArgumentException("$KEY_KIND 缺失")
        val kind = json.opt(KEY_KIND)
        if (kind !is String || kind !in AgentQuestionAnswer.KINDS) {
            throw IllegalArgumentException("$KEY_KIND 必须是 option、other 或 delegate")
        }
        return AgentQuestionAnswer(
            kind = kind,
            optionId = optionalString(json, KEY_OPTION_ID),
            otherText = textOrEmpty(json, KEY_OTHER_TEXT),
            note = textOrEmpty(json, KEY_NOTE),
        )
    }

    // ---------------------------------------------------------------------
    // 受理与结果
    // ---------------------------------------------------------------------

    fun validateAnswer(request: AgentQuestionRequest, answer: AgentQuestionAnswer): AgentQuestionReceipt {
        if (answer.kind !in AgentQuestionAnswer.KINDS) {
            return rejected(CODE_INVALID_KIND, "kind 必须是 option、other 或 delegate")
        }
        if (answer.otherText.length > MAX_OTHER_TEXT_CHARS) {
            return rejected(CODE_OTHER_TEXT_TOO_LONG, "other_text 超过 $MAX_OTHER_TEXT_CHARS 字符")
        }
        if (answer.note.length > MAX_NOTE_CHARS) {
            return rejected(CODE_NOTE_TOO_LONG, "note 超过 $MAX_NOTE_CHARS 字符")
        }
        when (answer.kind) {
            AgentQuestionAnswer.KIND_OPTION -> {
                val optionId = answer.optionId
                if (optionId.isNullOrBlank()) return rejected(CODE_OPTION_REQUIRED, "option 需要 option_id")
                if (request.options.none { it.id == optionId }) {
                    return rejected(CODE_UNKNOWN_OPTION, "option_id 不存在: $optionId")
                }
                if (answer.otherText.isNotBlank()) {
                    return rejected(CODE_OPTION_WITH_OTHER_TEXT, "option 不能携带 other_text")
                }
            }
            AgentQuestionAnswer.KIND_OTHER -> {
                if (!request.allowOther) return rejected(CODE_OTHER_NOT_ALLOWED, "本问题不允许自定义回答")
                if (!answer.optionId.isNullOrBlank()) {
                    return rejected(CODE_OTHER_WITH_OPTION_ID, "other 不能携带 option_id")
                }
                if (answer.otherText.isBlank()) return rejected(CODE_OTHER_TEXT_REQUIRED, "other 需要非空 other_text")
            }
            AgentQuestionAnswer.KIND_DELEGATE -> {
                if (!request.allowDelegation) {
                    return rejected(CODE_DELEGATION_NOT_ALLOWED, "本问题不允许委派处理")
                }
                if (!answer.optionId.isNullOrBlank()) {
                    return rejected(CODE_DELEGATE_WITH_OPTION_ID, "delegate 不能携带 option_id")
                }
                if (answer.otherText.isNotBlank()) {
                    return rejected(CODE_DELEGATE_WITH_OTHER_TEXT, "delegate 不能携带 other_text")
                }
            }
        }
        if (answer.note.isNotBlank() && !request.allowNote) {
            return rejected(CODE_NOTE_NOT_ALLOWED, "本问题不允许备注")
        }
        return AgentQuestionReceipt(accepted = true, code = CODE_OK)
    }

    /**
     * 由运行时原始请求投影工具结果。option 的标签/描述一律来自 [request]，UI 标签不会进入结果。
     * 非法回答直接 [IllegalArgumentException]，绝不返回成功的 answered。
     */
    fun resultJson(request: AgentQuestionRequest, answer: AgentQuestionAnswer): JSONObject {
        val receipt = validateAnswer(request, answer)
        if (!receipt.accepted) {
            throw IllegalArgumentException("非法 ask_user 回答: ${receipt.code}")
        }
        val result = JSONObject()
            .put(KEY_STATUS, STATUS_ANSWERED)
            .put(KEY_QUESTION_ID_OUT, request.questionId)
            .put(KEY_KIND, answer.kind)
        when (answer.kind) {
            AgentQuestionAnswer.KIND_OPTION -> {
                val option = request.options.first { it.id == answer.optionId }
                result.put(
                    KEY_SELECTED_OPTION,
                    JSONObject()
                        .put(OPTION_ID, option.id)
                        .put(OPTION_LABEL, option.label)
                        .put(OPTION_DESCRIPTION, option.description),
                )
            }
            AgentQuestionAnswer.KIND_OTHER -> result.put(KEY_OTHER_TEXT, answer.otherText)
            // 委托没有实质答案：带上指引，模型自决推进而不是原地等待/重复提问。
            AgentQuestionAnswer.KIND_DELEGATE -> result.put(KEY_GUIDANCE, DELEGATE_GUIDANCE)
        }
        if (request.allowNote && answer.note.isNotBlank()) {
            result.put(KEY_NOTE, answer.note)
        }
        return result
    }

    // ---------------------------------------------------------------------
    // 基础读取
    // ---------------------------------------------------------------------

    private fun rejected(code: String, message: String) = AgentQuestionReceipt(accepted = false, code = code, message = message)

    private fun JSONObject.present(key: String): Boolean = has(key) && opt(key) != JSONObject.NULL

    private fun requireBoundedText(json: JSONObject, key: String, max: Int): String {
        val value = json.opt(key)
        if (value !is String) throw IllegalArgumentException("$key 必须是字符串")
        if (value.isBlank()) throw IllegalArgumentException("$key 不能为空")
        if (value.length > max) throw IllegalArgumentException("$key 超过 $max 字符")
        return value
    }

    private fun optionalString(json: JSONObject, key: String, max: Int = Int.MAX_VALUE): String? {
        if (!json.present(key)) return null
        val value = json.opt(key)
        if (value !is String) throw IllegalArgumentException("$key 必须是字符串")
        if (value.length > max) throw IllegalArgumentException("$key 超过 $max 字符")
        return value
    }

    private fun lenientString(json: JSONObject, key: String): String {
        if (!json.present(key)) return ""
        val value = json.opt(key)
        return if (value is String) value else throw IllegalArgumentException("$key 必须是字符串")
    }

    /** 回答正文只接受真正的字符串；显式 null 或数字都按错类型拒绝，不做隐式转换。 */
    private fun textOrEmpty(json: JSONObject, key: String): String {
        if (!json.has(key)) return ""
        val value = json.opt(key)
        if (value !is String) throw IllegalArgumentException("$key 必须是字符串")
        return value
    }

    private fun booleanArg(json: JSONObject, key: String): Boolean {
        if (!json.has(key)) return true
        val value = json.opt(key)
        if (value !is Boolean) throw IllegalArgumentException("$key 必须是布尔值")
        return value
    }
}
