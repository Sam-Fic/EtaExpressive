package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * ask_user 的无设置工具合同：主代理在「关键参数确实缺失且无法从上下文推断」时，单独成批向用户提一个问题。
 *
 * 这里只声明模型可见的 Schema；解析与受理边界在 [io.github.mangi.eta.agent.question.AgentQuestionCodec]。
 * 该工具不依赖任何用户开关，默认随主目录发布；子代理白名单会把它排除，子代理只能把缺信息报告给主代理。
 */
internal object AgentQuestionToolCatalog {
    const val NAME = "ask_user"

    fun appendTo(tools: JSONArray) {
        val option = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject()
                    .put("id", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 64)
                        .put("description", "Stable option id that the answer is matched against; never substitute display text for it."))
                    .put("label", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 200)
                        .put("description", "Short label shown to the user."))
                    .put("description", JSONObject().put("type", "string").put("maxLength", 1000)
                        .put("description", "Optional additional note explaining what the option means or implies.")),
            )
            .put("required", JSONArray().put("id").put("label"))

        tools.put(
            AgentToolSchema.function(
                name = NAME,
                description =
                    "Ask the user one key question that changes the outcome and wait for the answer. Use it only when a critical parameter is genuinely missing" +
                        "and cannot be reasonably inferred from the current context; details that can be determined from context or defaulted reliably should just be handled, not asked about." +
                        "Must be called alone in its batch: do not mix other tools into the same batch, and send exactly one ask_user." +
                        "Provide 2-8 mutually exclusive, actionable options; recommended_option_id is only a hint," +
                        "so do not decide for the user and do not imply it is the only choice." +
                        "The user answers with an option to choose explicitly, with other to write a custom answer, or with delegate to defer the decision;" +
                        "do not request capabilities that allow_other/allow_delegation/allow_note do not grant.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put("additionalProperties", false)
                    .put(
                        "properties",
                        JSONObject()
                            .put("title", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 200)
                                .put("description", "Question title, briefly stating what must be decided."))
                            .put("question", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 4000)
                                .put("description", "The full question, including the background and constraints needed to choose."))
                            .put(
                                "options",
                                JSONObject()
                                    .put("type", "array")
                                    .put("minItems", 2)
                                    .put("maxItems", 8)
                                    .put("items", option)
                                    .put("description", "2-8 candidate options; each id must be unique and stable."),
                            )
                            .put("recommended_option_id", JSONObject().put("type", "string").put("maxLength", 64)
                                .put("description", "Optional; when given it must be the id of one option, serves only as a hint, and never selects automatically."))
                            .put("allow_other", JSONObject().put("type", "boolean")
                                .put("description", "Whether the user may write a custom answer; default true."))
                            .put("allow_delegation", JSONObject().put("type", "boolean")
                                .put("description", "Whether the user may defer the question for later handling; default true."))
                            .put("allow_note", JSONObject().put("type", "boolean")
                                .put("description", "Whether the user may add a note; default true.")),
                    )
                    .put("required", JSONArray().put("title").put("question").put("options")),
            ),
        )
    }
}
