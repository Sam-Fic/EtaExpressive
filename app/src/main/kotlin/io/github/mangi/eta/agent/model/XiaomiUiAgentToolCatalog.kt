package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 超级小爱 App Tool Provider 的稳定、最小模型契约。
 *
 * 目标 APK 的 app_tools.json 只用于能力探测；模型描述与授权规则由 Eta 自己维护，
 * 避免把可更新目标应用中的任意文本直接拼进模型提示。
 */
internal object XiaomiUiAgentToolCatalog {
    data class Issue(val code: String, val message: String)

    /** 8.0.17 provider 发布的名称；废弃的 get_memory_data 只用于识别，不再暴露给模型。 */
    val providerToolNames: Set<String> = linkedSetOf(
        "controlApp",
        "search_mi_knowledge",
        "favorite",
        "write_memory",
        "read_memory",
        "get_memory_data",
        "text_to_image",
        "image_to_text",
        "trans_text",
        "take_photo_recognize",
        "trans_image",
        "search_media_tool",
        "play_media_tool",
        "settings_access",
        "get_screen_context",
    )

    val modelToolNames: Set<String> = providerToolNames - "get_memory_data"

    fun appendTo(tools: JSONArray, availableTools: Set<String>) {
        definitions.forEach { (name, definition) ->
            if (name in availableTools) tools.put(definition())
        }
    }

    fun enabledToolNames(
        availableTools: Collection<String>,
        directTools: Boolean,
        sensitiveReadTools: Boolean,
        sensitiveActionTools: Boolean,
    ): Set<String> = availableTools
        .asSequence()
        .filter { it in modelToolNames }
        .filter { name ->
            when (name) {
                "favorite" -> sensitiveReadTools || sensitiveActionTools
                "settings_access" -> directTools || sensitiveActionTools
                in sensitiveReadToolNames -> sensitiveReadTools
                in sensitiveActionToolNames -> sensitiveActionTools
                else -> directTools
            }
        }
        .toCollection(linkedSetOf())

    fun validate(name: String, arguments: JSONObject): Issue? {
        if (name !in modelToolNames) return Issue("UNKNOWN_ENTRY_TOOL", "未知的小米 UIAgent 工具：$name")
        if (arguments.toString().toByteArray(Charsets.UTF_8).size > MAX_ARGUMENT_BYTES) {
            return Issue("ENTRY_TOOL_ARGUMENTS_TOO_LARGE", "UIAgent 工具参数不能超过 64 KiB")
        }
        return when (name) {
            "controlApp" -> requiredString(arguments, "query", 2_000)
            "search_mi_knowledge" ->
                requiredString(arguments, "query", 1_000)
                    ?: requiredEnum(arguments, "scene", setOf("车载", "非车载", "模糊"))
                    ?: requiredEnum(arguments, "query_intent", setOf("对比", "推荐", "查询"))
            "favorite" -> validateFavorite(arguments)
            "write_memory" -> requiredString(arguments, "query", 4_000)
            "read_memory" ->
                optionalString(arguments, "keywords", 500)
                    ?: optionalInteger(arguments, "page", 1, 10_000)
                    ?: optionalInteger(arguments, "page_size", 1, 50)
            "text_to_image" -> requiredString(arguments, "query", 2_000)
            "image_to_text" ->
                requiredUri(arguments, "image")
                    ?: requiredString(arguments, "query", 1_000)
            "trans_text" ->
                optionalString(arguments, "from", 32)
                    ?: optionalString(arguments, "to", 32)
                    ?: requiredString(arguments, "text", 10_000)
            "take_photo_recognize" -> requiredEnum(arguments, "mode", setOf("translate", "qa"))
            "trans_image" ->
                requiredContentUri(arguments, "image_uri")
                    ?: optionalString(arguments, "from", 32)
                    ?: optionalString(arguments, "to", 32)
            "search_media_tool" -> validateSearchMedia(arguments)
            "play_media_tool" -> validatePlayMedia(arguments)
            "settings_access" -> validateSettings(arguments)
            "get_screen_context" -> null
            else -> Issue("UNKNOWN_ENTRY_TOOL", "未知的小米 UIAgent 工具：$name")
        }
    }

    fun authorizationIssue(
        name: String,
        arguments: JSONObject,
        directTools: Boolean,
        sensitiveReadTools: Boolean,
        sensitiveActionTools: Boolean,
    ): Issue? {
        val allowed = when (name) {
            "favorite" -> when (arguments.optString("type")) {
                "trigger" -> sensitiveActionTools
                "list", "detail" -> sensitiveReadTools
                else -> false
            }
            "settings_access" -> when (arguments.optString("action")) {
                "set" -> sensitiveActionTools
                "get", "list" -> directTools
                else -> false
            }
            in sensitiveReadToolNames -> sensitiveReadTools
            in sensitiveActionToolNames -> sensitiveActionTools
            else -> directTools
        }
        if (allowed) return null
        val (code, message) = when {
            name == "favorite" && arguments.optString("type") == "trigger" ->
                "DEVICE_SENSITIVE_ACTION_TOOLS_DISABLED" to "请先允许敏感设备操作后再收藏当前屏幕"
            name == "settings_access" && arguments.optString("action") == "set" ->
                "DEVICE_SENSITIVE_ACTION_TOOLS_DISABLED" to "请先允许敏感设备操作后再修改小爱设置"
            name in sensitiveReadToolNames || name == "favorite" ->
                "DEVICE_SENSITIVE_READ_TOOLS_DISABLED" to "请先允许读取敏感设备信息"
            name in sensitiveActionToolNames ->
                "DEVICE_SENSITIVE_ACTION_TOOLS_DISABLED" to "请先允许敏感设备操作"
            else -> "DEVICE_DIRECT_TOOLS_DISABLED" to "请先启用设备直达工具"
        }
        return Issue(code, message)
    }

    fun isSensitiveCall(name: String, arguments: JSONObject): Boolean = when (name) {
        "favorite", "controlApp", "write_memory", "read_memory", "image_to_text",
        "trans_text", "take_photo_recognize", "trans_image", "get_screen_context" -> true
        "settings_access" -> arguments.optString("action") == "set"
        else -> false
    }

    private fun validateFavorite(arguments: JSONObject): Issue? {
        val typeIssue = requiredEnum(arguments, "type", setOf("trigger", "list", "detail"))
        if (typeIssue != null) return typeIssue
        optionalInteger(arguments, "pageNumber", 1, 10_000)?.let { return it }
        optionalInteger(arguments, "pageSize", 1, 50)?.let { return it }
        optionalString(arguments, "keywords", 500)?.let { return it }
        optionalString(arguments, "favoriteId", 256)?.let { return it }
        return if (
            arguments.optString("type") == "detail" &&
            arguments.optString("favoriteId").isBlank()
        ) {
            Issue("INVALID_ARGUMENT", "favorite type=detail 时必须提供 favoriteId")
        } else {
            null
        }
    }

    private fun validateSearchMedia(arguments: JSONObject): Issue? =
        optionalEnum(arguments, "mediaType", setOf("music", "station", "video"))
            ?: optionalString(arguments, "query", 1_000)
            ?: optionalString(arguments, "name", 500)
            ?: optionalString(arguments, "artist", 500)
            ?: optionalString(arguments, "album", 500)
            ?: optionalString(arguments, "keyword", 500)
            ?: optionalString(arguments, "type", 100)
            ?: optionalString(arguments, "category", 100)
            ?: optionalString(arguments, "tag", 200)
            ?: optionalEnum(
                arguments,
                "defaultSource",
                setOf("xiaowei", "wangyiyun", "kugou", "kuwo", "miui"),
            )

    private fun validatePlayMedia(arguments: JSONObject): Issue? =
        optionalEnum(arguments, "mediaType", setOf("music", "station", "video"))
            ?: optionalString(arguments, "query", 1_000)
            ?: optionalString(arguments, "cpName", 255)
            ?: optionalString(arguments, "packageName", 255)
            ?: optionalString(arguments, "resourceId", 1_000)
            ?: optionalString(arguments, "albumId", 1_000)
            ?: optionalString(arguments, "uri", 4_096)
            ?: optionalString(arguments, "extraParams", 32_000)

    private fun validateSettings(arguments: JSONObject): Issue? {
        requiredEnum(arguments, "action", setOf("get", "set", "list"))?.let { return it }
        requiredEnum(arguments, "key", setOf("dialect"))?.let { return it }
        optionalString(arguments, "value", 100)?.let { return it }
        return if (
            arguments.optString("action") == "set" && arguments.optString("value").isBlank()
        ) {
            Issue("INVALID_ARGUMENT", "settings_access action=set 时必须提供 value")
        } else {
            null
        }
    }

    private fun requiredUri(arguments: JSONObject, key: String): Issue? {
        requiredString(arguments, key, 4_096)?.let { return it }
        val value = arguments.optString(key)
        return if (
            value.startsWith("content://", ignoreCase = true) ||
            value.startsWith("file://", ignoreCase = true)
        ) {
            null
        } else {
            Issue("INVALID_ARGUMENT", "$key 必须是 content:// 或 file:// URI")
        }
    }

    private fun requiredContentUri(arguments: JSONObject, key: String): Issue? {
        requiredString(arguments, key, 4_096)?.let { return it }
        return if (arguments.optString(key).startsWith("content://", ignoreCase = true)) {
            null
        } else {
            Issue("INVALID_ARGUMENT", "$key 必须是 content:// URI")
        }
    }

    private fun requiredString(arguments: JSONObject, key: String, maxLength: Int): Issue? {
        if (!arguments.has(key) || arguments.isNull(key)) {
            return Issue("INVALID_ARGUMENT", "缺少必填参数 $key")
        }
        val value = arguments.opt(key)
        if (value !is String) return Issue("INVALID_ARGUMENT", "参数 $key 必须是字符串")
        if (value.isBlank()) return Issue("INVALID_ARGUMENT", "参数 $key 不能为空")
        return value.takeIf { it.length > maxLength }?.let {
            Issue("INVALID_ARGUMENT", "参数 $key 不能超过 $maxLength 个字符")
        }
    }

    private fun optionalString(arguments: JSONObject, key: String, maxLength: Int): Issue? {
        if (!arguments.has(key) || arguments.isNull(key)) return null
        val value = arguments.opt(key)
        if (value !is String) return Issue("INVALID_ARGUMENT", "参数 $key 必须是字符串")
        return value.takeIf { it.length > maxLength }?.let {
            Issue("INVALID_ARGUMENT", "参数 $key 不能超过 $maxLength 个字符")
        }
    }

    private fun requiredEnum(arguments: JSONObject, key: String, values: Set<String>): Issue? {
        requiredString(arguments, key, 100)?.let { return it }
        return if (arguments.optString(key) in values) {
            null
        } else {
            Issue("INVALID_ARGUMENT", "参数 $key 仅支持 ${values.joinToString("/")}")
        }
    }

    private fun optionalInteger(
        arguments: JSONObject,
        key: String,
        minimum: Int,
        maximum: Int,
    ): Issue? {
        if (!arguments.has(key) || arguments.isNull(key)) return null
        val value = arguments.opt(key)
        if (value !is Number || value.toDouble() % 1.0 != 0.0) {
            return Issue("INVALID_ARGUMENT", "参数 $key 必须是整数")
        }
        val number = value.toLong()
        return if (number in minimum.toLong()..maximum.toLong()) {
            null
        } else {
            Issue("INVALID_ARGUMENT", "参数 $key 必须在 $minimum 到 $maximum 之间")
        }
    }

    private fun optionalEnum(
        arguments: JSONObject,
        key: String,
        values: Set<String>,
    ): Issue? {
        if (!arguments.has(key) || arguments.isNull(key)) return null
        optionalString(arguments, key, 100)?.let { return it }
        return if (arguments.optString(key) in values) {
            null
        } else {
            Issue("INVALID_ARGUMENT", "参数 $key 仅支持 ${values.joinToString("/")}")
        }
    }

    private val definitions: LinkedHashMap<String, () -> JSONObject> = linkedMapOf(
        "controlApp" to {
            function(
                "controlApp",
                "调用小米 GUI Agent 在一个受支持的第三方 App 内完成一个完整任务。把同一 App 的多步操作合并进 query；不要用于系统设置或微信。下单、发布等敏感终点仍遵循小米原生限制。",
                properties("query" to string("App 名称和完整任务描述", 2_000)),
                "query",
            )
        },
        "search_mi_knowledge" to {
            function(
                "search_mi_knowledge",
                "查询小米官方产品、汽车、售后与使用知识。结果来自小米知识库。",
                properties(
                    "query" to string("包含产品名和具体问题的检索词", 1_000),
                    "scene" to enumString("知识场景", "车载", "非车载", "模糊"),
                    "query_intent" to enumString("用户意图", "对比", "推荐", "查询"),
                ),
                "query", "scene", "query_intent",
            )
        },
        "favorite" to {
            function(
                "favorite",
                "触发小米屏幕收藏、列出收藏，或读取一条收藏详情。trigger 会读取当前屏幕并写入收藏；list/detail 会读取个人收藏。",
                properties(
                    "type" to enumString("操作类型", "trigger", "list", "detail"),
                    "pageNumber" to integer("list 页码，默认 1", 1, 10_000),
                    "pageSize" to integer("list 每页数量，默认 10", 1, 50),
                    "keywords" to string("list 搜索关键词，空格分隔", 500),
                    "favoriteId" to string("detail 对应的收藏 ID", 256),
                ),
                "type",
            )
        },
        "write_memory" to {
            function(
                "write_memory",
                "仅在用户明确要求记住、记录或保存信息时，写入超级小爱记忆。",
                properties("query" to string("需要记住的原始指令或文本", 4_000)),
                "query",
            )
        },
        "read_memory" to {
            function(
                "read_memory",
                "搜索超级小爱的本地记忆和云端收藏；个人结果不会写入持久会话。",
                properties(
                    "keywords" to string("可选关键词，空格分隔", 500),
                    "page" to integer("页码，默认 1", 1, 10_000),
                    "page_size" to integer("每页条数，默认 10", 1, 50),
                ),
            )
        },
        "text_to_image" to {
            function(
                "text_to_image",
                "调用小米文生图并返回生成结果或真实图片路径。",
                properties("query" to string("希望生成的图片描述", 2_000)),
                "query",
            )
        },
        "image_to_text" to {
            function(
                "image_to_text",
                "调用小米视觉能力回答一个本地图片相关问题。image 必须是目标进程可读取的 content:// 或 file:// URI。",
                properties(
                    "image" to string("图片 content:// 或 file:// URI", 4_096),
                    "query" to string("图片相关问题", 1_000),
                ),
                "image", "query",
            )
        },
        "trans_text" to {
            function(
                "trans_text",
                "调用小米翻译服务翻译文本。除 zh-Hans、yue 等约定外使用 ISO 639-1 语言代码。",
                properties(
                    "from" to string("可选源语言代码", 32),
                    "to" to string("可选目标语言代码", 32),
                    "text" to string("需要翻译的文本", 10_000),
                ),
                "text",
            )
        },
        "take_photo_recognize" to {
            function(
                "take_photo_recognize",
                "打开小米拍照翻译或拍照问答页面。此工具会启动相机界面。",
                properties("mode" to enumString("translate=拍照翻译，qa=拍照问答", "translate", "qa")),
                "mode",
            )
        },
        "trans_image" to {
            function(
                "trans_image",
                "调用小米图片翻译，返回识别原文、译文以及可能的结果图片。",
                properties(
                    "image_uri" to string("目标进程可读取的图片 content:// URI", 4_096),
                    "from" to string("可选源语言代码", 32),
                    "to" to string("可选目标语言代码", 32),
                ),
                "image_uri",
            )
        },
        "search_media_tool" to {
            function(
                "search_media_tool",
                "用小米媒体服务搜索音乐、电台/有声或视频，只搜索不播放。播放任务必须先搜索，再把返回的播放指令交给 play_media_tool。",
                mediaSearchProperties(),
            )
        },
        "play_media_tool" to {
            function(
                "play_media_tool",
                "执行小米媒体播放。优先把上一轮 search_media_tool 返回的完整播放指令 JSON 原样放入 extraParams。",
                properties(
                    "mediaType" to enumString("媒体类型", "music", "station", "video"),
                    "query" to string("可选自然语言任务", 1_000),
                    "cpName" to string("可选内容源名称或包名", 255),
                    "packageName" to string("目标播放 App 包名", 255),
                    "resourceId" to string("歌曲/节目资源 ID", 1_000),
                    "albumId" to string("专辑 ID", 1_000),
                    "uri" to string("视频直连 URI/deeplink", 4_096),
                    "extraParams" to string("search_media_tool 返回的播放指令 JSON", 32_000),
                ),
            )
        },
        "settings_access" to {
            function(
                "settings_access",
                "读取、列出或修改超级小爱设置。当前仅支持 dialect；set 属于敏感设备操作。",
                properties(
                    "action" to enumString("操作", "get", "set", "list"),
                    "key" to enumString("设置项", "dialect"),
                    "value" to string("set 时的新值", 100),
                ),
                "action", "key",
            )
        },
        "get_screen_context" to {
            function(
                "get_screen_context",
                "读取小米提供的当前前台应用、页面和屏幕上下文。个人结果不会写入持久会话。",
                properties(),
            )
        },
    )

    private fun mediaSearchProperties(): JSONObject = properties(
        "mediaType" to enumString("媒体类型", "music", "station", "video"),
        "query" to string("原始或剩余自然语言意图", 1_000),
        "name" to string("歌曲、节目或影视名称", 500),
        "artist" to string("歌手、演员或演播者", 500),
        "album" to string("专辑名称", 500),
        "keyword" to string("其他检索关键词", 500),
        "type" to string("节目形态或视频类型", 100),
        "category" to string("电影/电视剧/综艺等视频分类", 100),
        "tag" to string("场景或内容标签", 200),
        "defaultSource" to enumString(
            "音乐来源；QQ音乐=xiaowei，网易云=wangyiyun，酷狗=kugou，酷我=kuwo，小米音乐=miui",
            "xiaowei", "wangyiyun", "kugou", "kuwo", "miui",
        ),
    )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        vararg required: String,
    ): JSONObject = AgentToolSchema.function(
        name = name,
        description = description,
        parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("additionalProperties", false)
            .also { schema ->
                if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
            },
    )

    private fun properties(vararg entries: Pair<String, JSONObject>): JSONObject =
        JSONObject().also { target -> entries.forEach { (name, schema) -> target.put(name, schema) } }

    private fun string(description: String, maxLength: Int): JSONObject = JSONObject()
        .put("type", "string")
        .put("description", description)
        .put("maxLength", maxLength)

    private fun integer(description: String, minimum: Int, maximum: Int): JSONObject = JSONObject()
        .put("type", "integer")
        .put("description", description)
        .put("minimum", minimum)
        .put("maximum", maximum)

    private fun enumString(description: String, vararg values: String): JSONObject =
        string(description, 100).put("enum", JSONArray(values.toList()))

    private val sensitiveReadToolNames = setOf(
        "read_memory",
        "image_to_text",
        "trans_image",
        "get_screen_context",
    )

    private val sensitiveActionToolNames = setOf(
        "controlApp",
        "write_memory",
        "take_photo_recognize",
    )

    private const val MAX_ARGUMENT_BYTES = 64 * 1024
}
