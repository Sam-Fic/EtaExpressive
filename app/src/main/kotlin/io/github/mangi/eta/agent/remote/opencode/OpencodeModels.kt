package io.github.mangi.eta.agent.remote.opencode

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenCode v2 服务器（`opencode serve`）的 REST 响应 DTO。
 *
 * 全部字段可空 + `ignoreUnknownKeys`，与 [io.github.mangi.eta.agent.remote.pi.PiFrame]
 * 同一条宽容原则：服务器版本升级加了字段/换了枚举值时，老客户端只丢细节、不崩连接。
 * 字段名对照的是 v2.2.5 的 wire 契约（`packages/schema/src/*.ts`）：
 * - 消息与工具状态的判别字段是 `type` / `status`（没有 `role`）；
 * - 时间一律是 epoch 毫秒数；
 * - `model` 引用是 `{id, providerID}`，注意 `id` 在前。
 */

/** 两个时间戳之外都可空的 Session.Info——会话列表行与「当前模型」的来源。 */
@Serializable
internal data class OpencodeSessionInfo(
    val id: String? = null,
    val title: String? = null,
    val model: OpencodeModelRef? = null,
    val tokens: OpencodeTokens? = null,
    val time: OpencodeSessionTime? = null,
)

@Serializable
internal data class OpencodeSessionTime(
    val created: Long? = null,
    val updated: Long? = null,
    val busy: Long? = null,
    val idle: Long? = null,
)

/** `Model.Ref`：`{id, providerID, variant?}`——序列化时 providerID 在后。 */
@Serializable
internal data class OpencodeModelRef(
    val id: String? = null,
    val providerID: String? = null,
    val variant: String? = null,
)

/** `TokenUsage.Info`：`{input, output, reasoning, cache:{read, write}}`。 */
@Serializable
internal data class OpencodeTokens(
    val input: Long? = null,
    val output: Long? = null,
    val reasoning: Long? = null,
    val cache: OpencodeTokenCache? = null,
) {
    /** 估算上下文占用量：输入 + 缓存读 + 推理 + 输出。缺项按 0。 */
    val total: Long? get() = if (input == null && output == null && reasoning == null && cache == null) {
        null
    } else {
        (input ?: 0) + (output ?: 0) + (reasoning ?: 0) + (cache?.read ?: 0)
    }
}

@Serializable
internal data class OpencodeTokenCache(
    val read: Long? = null,
    val write: Long? = null,
)

/** `Model.Info` 里 Eta 用得到的子集（模型列表 + 上下文窗口上限）。 */
@Serializable
internal data class OpencodeModelInfo(
    val id: String? = null,
    val providerID: String? = null,
    val name: String? = null,
    val limit: OpencodeModelLimit? = null,
)

@Serializable
internal data class OpencodeModelLimit(
    val context: Long? = null,
    val output: Long? = null,
)

/**
 * 列表端点的响应壳。两种外形都归到这里：会话/消息组是 `{data, cursor?}`，
 * 模型/表单/权限组是 `{location, data}`——多出来的 location 靠 ignoreUnknownKeys 丢掉。
 */
@Serializable
internal data class OpencodeModelListEnvelope(
    val data: List<OpencodeModelInfo> = emptyList(),
)

@Serializable
internal data class OpencodeFormListEnvelope(
    val data: List<OpencodeForm> = emptyList(),
)

@Serializable
internal data class OpencodePermissionListEnvelope(
    val data: List<OpencodePermission> = emptyList(),
)

/** 单对象响应壳：`{data: {...}}`；`data` 可为 null（如 GET /api/model/default 无默认模型时）。 */
@Serializable
internal data class OpencodeDataEnvelope<T>(
    val data: T? = null,
)

/** 会话列表响应的分页游标（opaque base64url，回传即可）。 */
@Serializable
internal data class OpencodeCursor(
    val previous: String? = null,
    val next: String? = null,
)

@Serializable
internal data class OpencodeSessionListEnvelope(
    val data: List<OpencodeSessionInfo> = emptyList(),
    val cursor: OpencodeCursor? = null,
)

/**
 * GET /api/session/:id/message 的消息联合类型的扁平宽容投影：
 * 十一种消息类型共用一个 DTO，`type` 判别，其余全可空。
 */
@Serializable
internal data class OpencodeMessage(
    val type: String? = null,
    val id: String? = null,
    val time: OpencodeMessageTime? = null,
    // user / synthetic / system / skill
    val text: String? = null,
    val files: List<OpencodeMessageFile> = emptyList(),
    // assistant
    val content: List<OpencodeContent> = emptyList(),
    val model: OpencodeModelRef? = null,
    val tokens: OpencodeTokens? = null,
    val error: OpencodeError? = null,
    // shell
    val command: String? = null,
    val status: String? = null,
    val output: OpencodeShellOutput? = null,
    // compaction
    val summary: String? = null,
    // skill
    val name: String? = null,
)

@Serializable
internal data class OpencodeMessageTime(
    val created: Long? = null,
)

/**
 * 用户消息附件。默认响应（未带 experimental-media-uri 头）里字节内联在 `data`，
 * `mime` 是精确的 MIME；开了 media_blob 才会有 `uri: "media:<hash>"`。
 */
@Serializable
internal data class OpencodeMessageFile(
    val data: String? = null,
    val mime: String? = null,
    val uri: String? = null,
    val name: String? = null,
)

@Serializable
internal data class OpencodeShellOutput(
    val output: String? = null,
)

/** assistant `content` 的成员：text / reasoning / tool 三种，`type` 判别。 */
@Serializable
internal data class OpencodeContent(
    val type: String? = null,
    val text: String? = null,
    // tool
    val id: String? = null,
    val name: String? = null,
    val state: OpencodeToolState? = null,
    val time: OpencodeContentTime? = null,
)

@Serializable
internal data class OpencodeContentTime(
    val created: Long? = null,
    val completed: Long? = null,
)

/** 工具状态四态：streaming（input 是原始 JSON 文本）/ running / completed / error。 */
@Serializable
internal data class OpencodeToolState(
    val status: String? = null,
    val input: JsonElement? = null,
    val content: JsonElement? = null,
    val error: OpencodeError? = null,
)

@Serializable
internal data class OpencodeError(
    val type: String? = null,
    val message: String? = null,
)

@Serializable
internal data class OpencodeMessageListEnvelope(
    val data: List<OpencodeMessage> = emptyList(),
    val cursor: OpencodeCursor? = null,
)

/** GET /api/model/default 的响应：`{location, data: ModelInfo | null}`。 */
@Serializable
internal data class OpencodeDefaultModelEnvelope(
    val data: OpencodeModelInfo? = null,
)

/** GET /api/session/active 的响应：`{data: {"<sessionID>": {"type":"running"}}}`，只取键集合。 */
@Serializable
internal data class OpencodeActiveEnvelope(
    val data: Map<String, JsonElement> = emptyMap(),
)

/** `permission.asked` 的请求体（事件 data 与 GET 列表项同形）。 */
@Serializable
internal data class OpencodePermission(
    val id: String? = null,
    val sessionID: String? = null,
    val action: String? = null,
    val resources: List<String> = emptyList(),
    val message: String? = null,
)

/** `Form.Info`：MCP elicitation / 追问表单。字段类型联合只投影 Eta 能渲染的部分。 */
@Serializable
internal data class OpencodeForm(
    val id: String? = null,
    val sessionID: String? = null,
    val title: String? = null,
    val fields: List<OpencodeFormField> = emptyList(),
)

@Serializable
internal data class OpencodeFormField(
    val key: String? = null,
    val type: String? = null,
    val title: String? = null,
    val options: List<OpencodeFormOption> = emptyList(),
)

@Serializable
internal data class OpencodeFormOption(
    val value: String? = null,
    val label: String? = null,
)
