package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentHostCapabilities
import org.json.JSONArray
import org.json.JSONObject

/** Xiaomi system capabilities that are only exposed when a trusted injected host is attached. */
internal object XiaomiHostToolCatalog {
    const val OPEN_AI_SUBTITLES = "xiaomi_open_ai_subtitles"
    const val OPEN_CONVERSATION_TRANSLATION = "xiaomi_open_conversation_translation"
    const val OPEN_SIMULTANEOUS_INTERPRETATION = "xiaomi_open_simultaneous_interpretation"
    const val OPEN_TEXT_TRANSLATION = "xiaomi_open_text_translation"
    const val EXTERNAL_AGENT = "xiaomi_external_agent"

    val visionToolNames = setOf(
        OPEN_AI_SUBTITLES,
        OPEN_CONVERSATION_TRANSLATION,
        OPEN_SIMULTANEOUS_INTERPRETATION,
        OPEN_TEXT_TRANSLATION,
    )

    fun appendTo(
        tools: JSONArray,
        capabilities: Set<String>,
        directTools: Boolean,
        sensitiveActionTools: Boolean,
    ) {
        if (directTools && AgentHostCapabilities.AIASST_VISION_APP_FUNCTIONS in capabilities) {
            tools
                .put(emptyFunction(OPEN_AI_SUBTITLES, "通过小米 AiasstVision App Function 打开 AI 实时字幕。会启动前台悬浮字幕界面。"))
                .put(emptyFunction(OPEN_CONVERSATION_TRANSLATION, "通过小米 AiasstVision App Function 打开面对面对话翻译。"))
                .put(emptyFunction(OPEN_SIMULTANEOUS_INTERPRETATION, "通过小米 AiasstVision App Function 打开同声传译。"))
                .put(emptyFunction(OPEN_TEXT_TRANSLATION, "通过小米 AiasstVision App Function 打开文本翻译悬浮窗。"))
        }
        if (sensitiveActionTools && AgentHostCapabilities.EXTERNAL_AGENT in capabilities) {
            tools.put(externalAgentFunction())
        }
    }

    fun namesFor(
        capabilities: Set<String>,
        directTools: Boolean,
        sensitiveActionTools: Boolean,
    ): Set<String> = buildSet {
        if (directTools && AgentHostCapabilities.AIASST_VISION_APP_FUNCTIONS in capabilities) {
            addAll(visionToolNames)
        }
        if (sensitiveActionTools && AgentHostCapabilities.EXTERNAL_AGENT in capabilities) {
            add(EXTERNAL_AGENT)
        }
    }

    private fun externalAgentFunction(): JSONObject = function(
        name = EXTERNAL_AGENT,
        description = "调用超级小爱 OSbot External Agent 完整会话接口。先 open_session，再 submit；close_session 会关闭会话并取消正在执行的请求。submit 会返回文本、推理增量、工具事件、TTS 事件和返回附件。该接口可驱动敏感系统能力，仅在用户已允许敏感设备操作时可见。",
        properties = JSONObject()
            .put(
                "action",
                string("接口动作").put(
                    "enum",
                    JSONArray(listOf("probe", "open_session", "submit", "close_session")),
                ),
            )
            .put("session_id", string("submit/close_session 使用的会话 ID", 256))
            .put("text", string("submit 的用户文本；与 request_json 至少提供一个", 32_000))
            .put("request_json", string("submit 的原始请求 JSON；省略时自动构造 type=message", 64_000))
            .put("tts_enabled", boolean("open_session 是否接收 TTS 事件，默认 false"))
            .put(
                "app_meta",
                JSONObject()
                    .put("type", "object")
                    .put(
                        "description",
                        "会话元数据。target_package 默认 com.miui.voiceassist；biz_id/feature_id 默认 eta/external_agent，均可按系统计费配置覆盖。",
                    )
                    .put(
                        "properties",
                        JSONObject()
                            .put("app_name", string("接入应用名", 128))
                            .put("locale", string("BCP-47 或语言区域标记", 64))
                            .put("context", string("当前场景说明", 4_000))
                            .put("tag", string("接入场景标签", 128))
                            .put("target_package", string("目标 Agent 包名", 255))
                            .put("chat_id", string("可选持久会话标识", 256))
                            .put("biz_id", string("商业化计费 bizId", 128))
                            .put("feature_id", string("商业化计费 featureId", 128)),
                    ),
            )
            .put(
                "attachments",
                JSONObject()
                    .put("type", "array")
                    .put("maxItems", 8)
                    .put("description", "submit 附件；path 与 uri 二选一，单个最多 10 MiB、合计最多 64 MiB。文件通过只读 FD 跨进程传递，不内联进 Binder。")
                    .put(
                        "items",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("path", string("Eta 进程可读取的本地绝对路径", 1_024))
                                    .put("uri", string("Eta 进程可读取的 content/file URI", 2_048))
                                    .put("name", string("附件显示名", 255))
                                    .put("mime_type", string("MIME 类型", 128)),
                            ),
                    ),
            ),
        required = arrayOf("action"),
    )

    private fun emptyFunction(name: String, description: String): JSONObject =
        function(name, description, JSONObject())

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        required: Array<String> = emptyArray(),
    ): JSONObject = AgentToolSchema.function(
        name = name,
        description = description,
        parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .also { schema ->
                if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
            },
    )

    private fun string(description: String, maxLength: Int? = null): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("description", description)
            .also { schema -> maxLength?.let { schema.put("maxLength", it) } }

    private fun boolean(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)
}
