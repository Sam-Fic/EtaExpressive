package io.github.mangi.eta.agent.model

import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.agent.runtime.localizedText
import java.net.URI
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 工具摘要面向用户展示，不包含敏感参数；终端命令通过独立字段提供给用户核对。 */
internal class AgentTraceFormatter(
    private val linuxEnvironmentLabelProvider: () -> String = { "Linux" },
    private val terminalSessionEnvironmentProvider: (String) -> String? = { null },
    private val terminalSessionIdentityProvider: (String) -> String? = { null },
) {
    /** 数量文案走复数资源；quantity 决定单复数，value 是占位值（默认与 quantity 相同）。 */
    private fun localizedPlural(resId: Int, quantity: Int, fallback: String, value: Any = quantity): String =
        runCatching {
            AgentAppContext.resolve()?.resources?.getQuantityString(resId, quantity, value)
        }.getOrNull() ?: String.format(fallback, value)

    fun summarizeArguments(toolCall: AgentModelClient.ToolCall): String =
        when (toolCall.name) {
            BROWSER_TOOL_NAME -> summarizeBrowserArguments(toolCall.argumentsJson)
            "web_search" -> "搜索公开网页"
            "fetch_url" -> summarizeFetchArguments(toolCall.argumentsJson)
            "open_uri" -> summarizeOpenUriArguments(toolCall.argumentsJson)
            "terminal" -> summarizeTerminalArguments(toolCall.argumentsJson)
            "run_command" -> summarizeRunCommandArguments(toolCall.argumentsJson)
            "write_file" -> summarizeTextLength(localizedText(R.string.trace_action_write_file, "写入文件"), toolCall.argumentsJson, "content")
            "read_file" -> localizedText(R.string.trace_action_read_file, "读取文件")
            "list_directory" -> localizedText(R.string.trace_action_list_directory, "列出目录")
            "stat_file" -> localizedText(R.string.trace_action_stat_file, "查看文件信息")
            "edit_file" -> localizedText(R.string.trace_action_edit_file, "精确编辑文件")
            "glob_files" -> localizedText(R.string.trace_action_glob_files, "按路径查找文件")
            "grep_files" -> localizedText(R.string.trace_action_grep_files, "搜索文件内容")
            "input_text" -> summarizeTextLength(localizedText(R.string.trace_action_input_text, "输入文本"), toolCall.argumentsJson, "text")
            "type_text" -> summarizeTextLength(localizedText(R.string.trace_action_input_text, "输入文本"), toolCall.argumentsJson, "text")
            "replace_text" -> summarizeTextLength(localizedText(R.string.trace_action_replace_text, "替换文本"), toolCall.argumentsJson, "text")
            "paste_text", "set_clipboard" ->
                summarizeTextLength(localizedText(R.string.trace_action_paste_text, "粘贴文本"), toolCall.argumentsJson, "text")
            "clear_text" -> localizedText(R.string.trace_action_clear_text, "清空文本")
            "get_clipboard" -> localizedText(R.string.trace_action_get_clipboard, "读取剪贴板")
            "search_apps" -> summarizeQueryArguments(localizedText(R.string.trace_action_search_apps, "搜索应用"), toolCall.argumentsJson)
            "inspect_app" -> localizedText(R.string.tool_inspect_app, "应用详情")
            "launch_app" -> localizedText(R.string.trace_action_launch_app, "打开应用")
            "get_current_context" -> localizedText(R.string.trace_action_context, "读取当前上下文")
            "observe_screen" -> summarizeObservationArguments(toolCall.argumentsJson)
            "tap" -> summarizePointArguments(localizedText(R.string.trace_action_tap, "点击屏幕"), toolCall.argumentsJson)
            "long_press" -> summarizePointArguments(localizedText(R.string.trace_action_long_press, "长按屏幕"), toolCall.argumentsJson)
            "tap_area" -> localizedText(R.string.trace_action_tap_area, "点击区域")
            "tap_element" -> summarizeElementArguments(localizedText(R.string.trace_action_tap_element, "点击元素"), toolCall.argumentsJson)
            "long_press_element" -> summarizeElementArguments(localizedText(R.string.trace_action_long_press_element, "长按元素"), toolCall.argumentsJson)
            "swipe" -> localizedText(R.string.trace_action_swipe, "滑动屏幕")
            "scroll" -> summarizeScrollArguments(localizedText(R.string.trace_action_scroll, "滚动屏幕"), toolCall.argumentsJson)
            "scroll_element" ->
                summarizeScrollArguments(localizedText(R.string.trace_action_scroll_element, "滚动元素"), toolCall.argumentsJson, withIndex = true)
            "press_key" -> summarizePressKeyArguments(toolCall.argumentsJson)
            "wait" -> summarizeWaitArguments(toolCall.argumentsJson)
            "wait_for_text" -> localizedText(R.string.trace_action_wait_text, "等待文本出现")
            "wait_for_package" -> localizedText(R.string.trace_action_wait_package, "等待应用就绪")
            "open_system_panel" -> localizedText(R.string.trace_action_panel, "打开系统面板")
            "read_image" -> localizedText(R.string.trace_action_read_image, "查看图片或视频")
            "delegate_task" -> localizedText(R.string.trace_action_delegate, "委派子代理任务")
            "manage_agent_workspace" -> localizedText(R.string.trace_action_workspace, "管理任务工作区")
            "get_task_result" -> localizedText(R.string.trace_action_task_result, "查询子代理结果")
            "continue_task" -> localizedText(R.string.trace_action_continue_task, "继续子代理任务")
            "cancel_task" -> localizedText(R.string.trace_action_cancel_task, "取消子代理任务")
            "memory_get" -> summarizeMemoryGetArguments(toolCall.argumentsJson)
            "memory_write" -> summarizeMemoryWriteArguments(toolCall.argumentsJson)
            "skills_list" -> localizedText(R.string.trace_action_skills_list, "查看技能列表")
            "skills_read" -> localizedText(R.string.trace_action_skills_read, "读取技能")
            "skills_read_resource" -> localizedText(R.string.trace_action_skills_resource, "读取技能资源")
            "skills_list_curated" -> localizedText(R.string.trace_action_skills_curated, "浏览精选技能")
            "skills_inspect_github" -> localizedText(R.string.trace_action_skills_inspect, "查看技能详情")
            "skills_install_from_github" -> localizedText(R.string.trace_action_skills_install, "安装技能")
            // 自建配方工具：名字是动态注册的 recipe_<name>，显示配方名比通用文案有用。
            else -> if (toolCall.name.startsWith("recipe_")) {
                val recipeName = toolCall.name.removePrefix("recipe_")
                localizedText(
                    R.string.trace_dev_recipe_tool,
                    "调用自建工具 %1\$s",
                    recipeName,
                )
            } else {
                val label = DEVICE_ACTION_LABELS[toolCall.name]
                when {
                    label == null -> localizedText(R.string.trace_action_prepare, "准备执行")
                    toolCall.name.startsWith("search_") ->
                        summarizeQueryArguments(localizedText(label.first, label.second), toolCall.argumentsJson)
                    else -> localizedText(label.first, label.second)
                }
            }
        }

    /** 命令以脱敏后的用户可见投影进入运行轨迹；日志仍只记录长度。 */
    fun displayCommand(toolCall: AgentModelClient.ToolCall): String? =
        if (toolCall.name == "terminal" || toolCall.name == "run_command") {
            runCatching {
                JSONObject(toolCall.argumentsJson)
                    .optString("command")
                    .trim()
                    .takeIf { it.isNotBlank() && it.length <= MAX_DISPLAY_COMMAND_CHARS }
                    ?.redactDisplaySecrets()
            }.getOrNull()
        } else {
            null
        }

    private fun String.redactDisplaySecrets(): String =
        replace(SENSITIVE_ASSIGNMENT) { match ->
            "${match.groupValues[1]}=<已隐藏>"
        }
            .replace(SENSITIVE_FLAG) { match ->
                "${match.groupValues[1]}<已隐藏>"
            }
            .replace(SENSITIVE_HEADER) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}<已隐藏>"
            }

    /**
     * 卡片展开后展示的完整调用参数：工具真正收到的字段原样保留（含路径、命令、查询词），
     * 只隐藏凭据与内联媒体。
     *
     * 敏感工具（[AgentSensitiveToolPolicy]）在这里不做屏蔽——用户就在自己的屏幕上，
     * 展开时理应看到模型到底怎么调的；这类工具的原文由会话存储层在写库时剔除，
     * 只有当场能看到，重开会话后不再保留。
     */
    fun detailArguments(toolCall: AgentModelClient.ToolCall): String? {
        val json = runCatching { JSONObject(toolCall.argumentsJson) }.getOrNull()
        if (json != null && (toolCall.name == "terminal" || toolCall.name == "run_command")) {
            // 命令原文已经由 displayCommand 单独成块，参数里去掉这一项，避免同一段命令出现两次。
            json.remove("command")
            if (json.length() == 0) return null
            return formatDetailPayload(json.toString(2), MAX_ARGUMENT_DETAIL_CHARS)
        }
        return formatDetailPayload(toolCall.argumentsJson, MAX_ARGUMENT_DETAIL_CHARS)
    }

    /** 卡片展开后展示的完整返回内容：模型当时读到的就是这份文本，超长时截断（同样只当场展示）。 */
    fun detailResult(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String? {
        val json = parseResultJson(result)
        val body = when {
            json == null -> result.content.trim()
            toolName == "terminal" || toolName == "run_command" -> terminalDetail(json)
            else -> runCatching { json.toString(2) }.getOrDefault(result.content)
        }
        return body.takeIf { it.isNotBlank() }?.finishDetail(MAX_RESULT_DETAIL_CHARS)
    }

    /** 终端输出按流展开，避免把整段 stdout 压进一条转义过的 JSON 字符串。 */
    private fun terminalDetail(json: JSONObject): String = buildString {
        if (json.has("exit_code") && !json.isNull("exit_code")) {
            append("exit_code: ").append(json.optInt("exit_code")).append('\n')
        }
        if (json.optBoolean("timed_out", false)) append("timed_out: true\n")
        json.optString("stdout").takeIf { it.isNotBlank() }?.let {
            append("stdout:\n").append(it.trimEnd()).append('\n')
        }
        json.optString("stderr").takeIf { it.isNotBlank() }?.let {
            append("stderr:\n").append(it.trimEnd()).append('\n')
        }
        if (isEmpty()) append(json.toString(2))
    }.trimEnd()

    private fun formatDetailPayload(raw: String, maxChars: Int): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val pretty = runCatching { JSONObject(trimmed).toString(2) }
            .recoverCatching { JSONArray(trimmed).toString(2) }
            .getOrDefault(trimmed)
        return pretty.finishDetail(maxChars)
    }

    /** 凭据与内联媒体先脱敏，再按上限截断；两处都走同一入口，卡片与日志口径一致。 */
    private fun String.finishDetail(maxChars: Int): String {
        val redacted = redactDisplaySecrets().replace(INLINE_MEDIA) { match ->
            localizedText(
                R.string.trace_detail_inline_media,
                "<已省略 %1\$d KB 内联数据>",
                match.groupValues[2].length / 1024,
            )
        }
        if (redacted.length <= maxChars) return redacted
        return redacted.take(maxChars) +
            localizedText(R.string.trace_detail_truncated, "\n… （已截断，共 %1\$d 字符）", redacted.length)
    }

    /** 外部 URI 摘要不记录 path、query、fragment 或用户信息。 */
    fun summarizeOpenUriArguments(argumentsJson: String): String =
        runCatching {
            val raw = JSONObject(argumentsJson).optString("uri").trim()
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()?.take(24)
            val host = uri.host?.lowercase()?.take(160)
            listOfNotNull(localizedText(R.string.trace_action_external, "交给外部应用"), scheme, host).joinToString(" · ")
        }.getOrDefault(localizedText(R.string.trace_action_external, "交给外部应用"))

    /** browser_use 摘要只暴露动作和安全提取的 host。 */
    fun summarizeBrowserArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").browserActionLabel()
            val host = safeHttpHost(arguments.optString("url"))
            listOfNotNull(action, host).joinToString(" · ")
        }.getOrElse { localizedText(R.string.trace_action_browser, "浏览器操作") }

    private fun summarizeFetchArguments(argumentsJson: String): String = runCatching {
        val arguments = JSONObject(argumentsJson)
        val label = if (arguments.has("document_id")) "续读网页正文" else "读取网页正文"
        listOfNotNull(label, safeHttpHost(arguments.optString("url"))).joinToString(" · ")
    }.getOrDefault("读取网页正文")

    private fun summarizeRunCommandArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val resolved = resolveTerminalRuntime(arguments)
            buildList {
                add(localizedText(R.string.trace_action_exec, "执行命令"))
                add(resolved.environment)
                resolved.identity?.let(::add)
            }.joinToString(" · ")
        }.getOrDefault(localizedText(R.string.trace_action_exec, "执行命令"))

    private fun summarizeTerminalArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").terminalActionLabel()
            val resolved = resolveTerminalRuntime(arguments)
            buildList {
                add(localizedText(R.string.trace_action_terminal, "终端"))
                add(action)
                add(resolved.environment)
                resolved.identity?.let(::add)
                if (arguments.optBoolean("async", false)) add(localizedText(R.string.trace_action_background, "后台"))
            }.joinToString(" · ")
        }.getOrDefault(localizedText(R.string.trace_action_terminal, "终端"))

    private fun resolveTerminalRuntime(arguments: JSONObject): TerminalRuntimeSummary {
        val sessionId = arguments.optString("session_id").takeIf { it.isNotBlank() }
        val environment = arguments.optString("environment")
            .ifBlank { sessionId?.let(terminalSessionEnvironmentProvider).orEmpty() }
            .ifBlank { "android" }
            .terminalEnvironmentLabel()
        val identity = arguments.optString("identity")
            .ifBlank { sessionId?.let(terminalSessionIdentityProvider).orEmpty() }
            .ifBlank { "root" }
            .takeIf { it == "root" || it == "user" }
        return TerminalRuntimeSummary(environment = environment, identity = identity)
    }

    private fun summarizeTextLength(
        label: String,
        argumentsJson: String,
        key: String,
    ): String =
        runCatching {
            val chars = JSONObject(argumentsJson).optString(key).length
            localizedText(R.string.trace_args_chars, "%1\$s · %2\$d 字符", label, chars)
        }.getOrDefault(label)

    /** 搜索关键词是用户自己发起的查询，直接展示；仍做单行化与长度截断。 */
    private fun summarizeQueryArguments(label: String, argumentsJson: String): String =
        runCatching {
            val query = sanitizeSummaryValue(
                JSONObject(argumentsJson).optString("query"),
                MAX_QUERY_SUMMARY_CHARS,
            )
            if (query.isNotBlank()) "$label · $query" else label
        }.getOrDefault(label)

    private fun summarizePointArguments(label: String, argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            "$label · (${arguments.optInt("x")}, ${arguments.optInt("y")})"
        }.getOrDefault(label)

    private fun summarizeElementArguments(label: String, argumentsJson: String): String =
        runCatching {
            val index = JSONObject(argumentsJson).optInt("index", -1)
            if (index >= 0) "$label · #$index" else label
        }.getOrDefault(label)

    private fun summarizeScrollArguments(
        label: String,
        argumentsJson: String,
        withIndex: Boolean = false,
    ): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            buildList {
                add(label)
                if (withIndex) {
                    arguments.optInt("index", -1).takeIf { it >= 0 }?.let { add("#$it") }
                }
                arguments.optString("direction").scrollDirectionLabel()?.let(::add)
            }.joinToString(" · ")
        }.getOrDefault(label)

    private fun summarizePressKeyArguments(argumentsJson: String): String =
        runCatching {
            val button = JSONObject(argumentsJson).optString("button").pressKeyLabel()
            listOfNotNull(localizedText(R.string.trace_action_key, "按键"), button).joinToString(" · ")
        }.getOrDefault(localizedText(R.string.trace_action_key, "按键"))

    private fun summarizeWaitArguments(argumentsJson: String): String =
        runCatching {
            val durationMs = JSONObject(argumentsJson).optInt("duration_ms", 1_000)
                .coerceAtLeast(0)
            val duration = if (durationMs >= 1_000) {
                val seconds = String.format(Locale.US, "%.1f", durationMs / 1_000f)
                    .trimEnd('0').trimEnd('.')
                // 单复数按整秒判断：恰好 1 秒用单数，其余（含小数秒）用复数
                localizedPlural(
                    R.plurals.trace_wait_seconds,
                    if (seconds == "1") 1 else 2,
                    "%1\$s 秒",
                    seconds,
                )
            } else {
                localizedPlural(R.plurals.trace_wait_ms, durationMs, "%1\$d 毫秒")
            }
            "${localizedText(R.string.trace_action_wait, "等待")} · $duration"
        }.getOrDefault(localizedText(R.string.trace_action_wait, "等待"))

    private fun summarizeObservationArguments(argumentsJson: String): String =
        runCatching {
            val options = AgentScreenObservationContract.resolve(JSONObject(argumentsJson))
            buildList {
                add(localizedText(R.string.trace_action_observe, "观察屏幕"))
                if (options.includeScreenshot) add(localizedText(R.string.trace_action_with_screenshot, "含截图"))
                if (options.includeUiTree) add(localizedText(R.string.trace_action_with_ui_tree, "含界面树"))
            }.joinToString(" · ")
        }.getOrDefault(localizedText(R.string.trace_action_observe, "观察屏幕"))

    private fun summarizeMemoryGetArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            if (arguments.optString("query").isNotBlank()) {
                localizedText(R.string.trace_memory_search, "检索记忆")
            } else {
                localizedText(R.string.trace_memory_read, "读取记忆")
            }
        }.getOrDefault(localizedText(R.string.trace_memory_read, "读取记忆"))

    private fun summarizeMemoryWriteArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val mode = when (arguments.optString("mode")) {
                "replace_range" -> localizedText(R.string.trace_memory_replace, "替换片段")
                "append" -> localizedText(R.string.trace_memory_append, "追加")
                "clear" -> localizedText(R.string.trace_memory_clear, "清空")
                else -> null
            }
            val content = arguments.optString("content")
            val lines = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
            buildList {
                add(localizedText(R.string.trace_memory_update, "更新记忆"))
                mode?.let(::add)
                add(localizedPlural(R.plurals.trace_lines, lines, "%d 行"))
                add(localizedPlural(R.plurals.trace_bytes, content.toByteArray(Charsets.UTF_8).size, "%d 字节"))
            }.joinToString(" · ")
        }.getOrDefault(localizedText(R.string.trace_memory_update, "更新记忆"))

    /** 结果成败供事件与 UI 状态使用，不再依赖摘要文本里的标记。 */
    fun isSuccessResult(result: AgentModelClient.ToolResult): Boolean {
        val json = parseResultJson(result) ?: return true
        if (!json.optBoolean("ok", true)) return false
        if (json.optString("tool") in setOf("terminal", "run_command")) {
            if (json.optBoolean("timed_out", false)) return false
            if (json.has("exit_code") && !json.isNull("exit_code") && json.optInt("exit_code") != 0) return false
        }
        return true
    }

    fun summarizeResult(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String {
        val json = parseResultJson(result)
        if (toolName == "web_search" || toolName == "fetch_url") return summarizeWebResult(toolName, json)
        // 终端 exit_code != 0 时 ok=false 但没有 code 字段，必须走专用分支保留退出码与输出
        if (toolName == "terminal" || toolName == "run_command") {
            return summarizeTerminalResult(json)
        }
        if (!isSuccessResult(result)) return summarizeFailure(json)
        return when (toolName) {
            BROWSER_TOOL_NAME ->
                json?.let(::summarizeBrowserResult)
                    ?: localizedText(R.string.trace_result_browser_done, "浏览器操作完成")
            "memory_get", "memory_write" ->
                json?.let { summarizeMemoryResult(toolName, it) }
                    ?: localizedText(R.string.trace_result_done, "完成")
            "delegate_task", "get_task_result", "continue_task", "cancel_task" -> summarizeSubAgentResult(json)
            "search_apps" ->
                json?.let(::summarizeSearchAppsResult) ?: localizedText(R.string.trace_result_done, "完成")
            "launch_app" ->
                json?.let(::summarizeLaunchAppResult) ?: localizedText(R.string.trace_result_opened, "已打开")
            else ->
                json?.let { summarizeGenericResult(it, result) }
                    ?: localizedText(R.string.trace_result_done, "完成")
        }
    }

    /** A delegate reply records submission; it is not a live task-status subscription. */
    private fun summarizeSubAgentResult(json: JSONObject?): String {
        if (json == null) return localizedText(R.string.trace_subagent_unknown, "未取得子代理状态")
        val tasks = json.optJSONArray("tasks")
        if (tasks != null) {
            if (tasks.length() == 0) {
                return if (json.optInt("total", 0) == 0) {
                    localizedText(R.string.trace_subagent_none, "暂无子代理任务")
                } else {
                    localizedText(R.string.trace_subagent_none_page, "本页无子代理任务")
                }
            }
            val counts = linkedMapOf<String, Int>()
            for (i in 0 until tasks.length()) {
                val status = tasks.optJSONObject(i)?.optString("status").orEmpty()
                val label = when (status) {
                    "awaiting_decision" -> localizedText(R.string.trace_status_awaiting, "超时待主代理决定")
                    "queued" -> localizedText(R.string.trace_status_queued, "排队中")
                    "running" -> localizedText(R.string.trace_status_running, "执行中")
                    "completed" -> localizedText(R.string.trace_status_completed, "已完成")
                    "cancelled" -> localizedText(R.string.trace_status_cancelled, "已取消")
                    "timed_out" -> localizedText(R.string.trace_status_timed_out, "已超时")
                    "failed" -> localizedText(R.string.trace_failed, "失败")
                    else -> localizedText(R.string.trace_status_missing, "未提供状态")
                }
                counts[label] = (counts[label] ?: 0) + 1
            }
            return localizedText(R.string.trace_subagent_tasks, "子代理任务") +
                " · " + counts.entries.joinToString(" · ") { "${it.key} ${it.value}" }
        }
        return when (json.optString("status")) {
            "awaiting_decision" ->
                localizedText(R.string.trace_subagent_awaiting, "子代理超时 · 等待主代理继续或取消")
            "queued" -> localizedText(R.string.trace_subagent_queued, "子代理已提交 · 排队中")
            "running" -> localizedText(R.string.trace_subagent_running, "子代理执行中")
            "completed" ->
                localizedText(R.string.trace_subagent_completed, "子代理已返回 · 等待主代理审核")
            "cancelled" -> localizedText(R.string.trace_subagent_cancelled, "子代理已取消")
            "timed_out" -> localizedText(R.string.trace_subagent_timed_out, "子代理超时 · 主代理接手")
            "failed" -> localizedText(R.string.trace_subagent_failed, "子代理失败 · 主代理接手")
            else -> localizedText(R.string.trace_subagent_unknown, "未取得子代理状态")
        }
    }

    private fun parseResultJson(result: AgentModelClient.ToolResult): JSONObject? =
        runCatching { JSONObject(result.content) }.getOrNull()

    /** 网页错误正文、搜索词和页面标题可能回显私密 URL，只保留动作、主机与计数。 */
    private fun summarizeWebResult(toolName: String, json: JSONObject?): String {
        val label = if (toolName == "web_search") "网页搜索" else "网页读取"
        if (json == null) return label
        if (!json.optBoolean("ok", true)) {
            val code = json.optString("code").takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) }
            return listOfNotNull("${label}失败", code?.let { "code=$it" }).joinToString(" · ")
        }
        return buildList {
            if (toolName == "web_search") {
                add("已搜索公开网页")
                val count = json.optJSONArray("results")?.length() ?: json.firstNonNegativeInt("count")
                count?.let { add("$it 条结果") }
            } else {
                add("已读取网页正文")
                sequenceOf("final_url", "url").map { json.optString(it) }.mapNotNull(::safeHttpHost).firstOrNull()?.let(::add)
                val chars = (json.opt("text") as? String)?.length
                    ?: json.firstNonNegativeInt("returned_chars", "text_chars")
                chars?.let { add(formatCharCount(it)) }
                if (json.optBoolean("has_more")) add("还有正文")
            }
            if (json.optBoolean("truncated")) add("内容不完整")
        }.joinToString(" · ")
    }

    /** 失败摘要保留 code= 标记，供运行日志提取稳定错误码；message 是工具侧给出的中文原因。 */
    private fun summarizeFailure(json: JSONObject?): String {
        val code = json?.optString("code")?.takeIf { it.isNotBlank() }
        val reason = json?.optString("message")
            ?.let(::sanitizeSummaryValue)
            ?.takeIf { it.isNotBlank() }
        return buildList {
            add(localizedText(R.string.trace_failed, "失败"))
            reason?.let(::add)
            code?.let { add("code=$it") }
        }.joinToString(" · ")
    }

    private fun summarizeMemoryResult(toolName: String, json: JSONObject): String =
        buildList {
            add(
                if (toolName == "memory_get") {
                    localizedText(R.string.trace_memory_read_done, "已读取记忆")
                } else {
                    localizedText(R.string.trace_memory_updated_done, "已更新记忆")
                },
            )
            if (json.has("line_count")) {
                add(localizedPlural(R.plurals.trace_lines, json.optInt("line_count"), "%d 行"))
            }
            if (json.has("bytes")) {
                add(localizedPlural(R.plurals.trace_bytes, json.optInt("bytes"), "%d 字节"))
            }
        }.joinToString(" · ")

    private fun summarizeGenericResult(
        json: JSONObject,
        result: AgentModelClient.ToolResult,
    ): String =
        buildList {
            add(localizedText(R.string.trace_result_done, "完成"))
            json.optJSONArray("apps")?.let {
                add(localizedPlural(R.plurals.trace_apps_found_generic, it.length(), "找到 %d 个应用"))
            }
            json.optJSONArray("candidates")?.let {
                add(localizedPlural(R.plurals.trace_candidates, it.length(), "%d 个候选"))
            }
            if (result.images.isNotEmpty()) {
                add(localizedPlural(R.plurals.trace_images, result.images.size, "%d 张图片"))
            }
        }.joinToString(" · ")

    private fun summarizeSearchAppsResult(json: JSONObject): String {
        val apps = json.optJSONArray("apps")
            ?: return localizedText(R.string.trace_apps_none, "未找到匹配应用")
        val total = apps.length()
        if (total == 0) return localizedText(R.string.trace_apps_none, "未找到匹配应用")
        val names = (0 until total).mapNotNull { index ->
            apps.optJSONObject(index)?.optString("app_name")
                ?.let(::sanitizeSummaryValue)
                ?.takeIf { it.isNotBlank() }
        }
        return buildString {
            append(localizedPlural(R.plurals.trace_apps_found, total, "已找到 %d 个应用"))
            val shown = names.take(MAX_LISTED_APP_NAMES)
            if (shown.isNotEmpty()) {
                val joined = shown.joinToString(localizedText(R.string.trace_app_name_separator, "、"))
                append(" · ")
                append(
                    if (total > shown.size) {
                        localizedText(R.string.trace_app_names_suffix, "%1\$s 等", joined)
                    } else {
                        joined
                    },
                )
            }
        }
    }

    private fun summarizeLaunchAppResult(json: JSONObject): String {
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        val opened = localizedText(R.string.trace_result_opened, "已打开")
        return if (appName.isNotBlank()) "$opened · $appName" else opened
    }

    /**
     * 终端结果面向用户展示退出状态与输出预览；输出可能很长，
     * 只保留开头几行，截断时追加省略标记。
     */
    private fun summarizeTerminalResult(json: JSONObject?): String {
        if (json == null) return localizedText(R.string.trace_action_terminal, "终端")
        if (json.optString("code").isNotBlank()) return summarizeFailure(json)
        val terminalLabel = localizedText(R.string.trace_action_terminal, "终端")
        val failedLabel = localizedText(R.string.trace_failed, "失败")
        if (!json.has("exit_code") || json.isNull("exit_code")) {
            val action = json.optString("action").terminalActionLabel()
            return if (json.optBoolean("ok", true)) {
                "$terminalLabel · $action"
            } else {
                "$failedLabel · $action"
            }
        }
        val exitCode = json.optInt("exit_code")
        val timedOut = json.optBoolean("timed_out", false)
        val status = when {
            timedOut -> localizedText(R.string.trace_failed_timeout, "失败 · 执行超时")
            exitCode == 0 -> localizedText(R.string.trace_result_exec_done, "执行完成")
            else -> localizedText(R.string.trace_failed_exit_code, "失败 · 退出码 %d", exitCode)
        }
        // Preview streams separately: long stdout must not hide stderr warnings.
        // Stderr alone is not a failure; exit status and structured diagnostics decide that.
        val stdout = terminalOutputPreview(json.optString("stdout"), json.optBoolean("stdout_truncated", false))
        val stderr = terminalOutputPreview(json.optString("stderr"), json.optBoolean("stderr_truncated", false))
        return buildList {
            add(status)
            if (exitCode != 0 || timedOut) {
                stderr?.let { add(it) }
                stdout?.let { add(it) }
            } else {
                stdout?.let { add(it) }
                stderr?.let { add(localizedText(R.string.trace_stderr_warning, "警告 · stderr\n%1\$s", it)) }
            }
        }.joinToString("\n")
    }

    private fun terminalOutputPreview(output: String, truncated: Boolean): String? {
        val normalized = output.trim()
        if (normalized.isEmpty()) return null
        val allLines = normalized.lines()
        var preview = allLines.take(MAX_TERMINAL_PREVIEW_LINES).joinToString("\n")
        var capped = allLines.size > MAX_TERMINAL_PREVIEW_LINES || truncated
        if (preview.length > MAX_TERMINAL_PREVIEW_CHARS) {
            preview = preview.take(MAX_TERMINAL_PREVIEW_CHARS)
            capped = true
        }
        return if (capped) "$preview\n…" else preview
    }

    private fun summarizeBrowserResult(json: JSONObject): String {
        val page = json.optJSONObject("page")
            ?: json.optJSONObject("page_info")
            ?: json.optJSONObject("pageInfo")
        val action = json.optString("action")
            .takeIf { it in BROWSER_ACTIONS }
            ?: "unknown"
        val host = sequenceOf(json, page)
            .filterNotNull()
            .flatMap { source ->
                sequenceOf("url", "current_url", "currentUrl", "final_url", "finalUrl")
                    .map(source::optString)
            }
            .mapNotNull(::safeHttpHost)
            .firstOrNull()
        val title = sequenceOf(json, page)
            .filterNotNull()
            .map { it.opt("title") }
            .filterIsInstance<String>()
            .map(::sanitizeSummaryValue)
            .firstOrNull { it.isNotBlank() }
        val textChars = sequenceOf(json, page)
            .filterNotNull()
            .mapNotNull { source ->
                source.firstNonNegativeInt("text_length", "textLength", "text_chars", "textChars")
            }
            .firstOrNull()
            ?: sequenceOf(json, page)
                .filterNotNull()
                .flatMap { source -> sequenceOf("text", "readable", "content").map(source::opt) }
                .filterIsInstance<String>()
                .map(String::length)
                .firstOrNull()
        val elementCount = json.firstNonNegativeInt("element_count", "elementCount", "elements_count")
            ?: json.optJSONArray("elements")?.length()

        return buildList {
            add(action.browserSuccessLabel())
            host?.let(::add)
            title?.let { add(localizedText(R.string.trace_browser_title, "《%1\$s》", it)) }
            if (action in BROWSER_TEXT_ACTIONS) {
                textChars?.let { add(localizedText(R.string.trace_approx_count, "约 %1\$s", formatCharCount(it))) }
            }
            elementCount?.let { add(localizedPlural(R.plurals.trace_elements, it, "%d 个元素")) }
            if (json.optBoolean("truncated", false)) add(localizedText(R.string.trace_browser_truncated, "已截断"))
        }.joinToString(" · ")
    }

    private fun formatCharCount(chars: Int): String =
        if (chars >= 10_000) {
            val wan = String.format(Locale.US, "%.1f", chars / 10_000f).trimEnd('0').trimEnd('.')
            val k = String.format(Locale.US, "%.0f", chars / 1000f)
            // 中文按「万」计、英文按「k」计，两个数值随资源语言各取所需
            localizedText(R.string.trace_chars_wan, "%1\$s 万字", wan, k)
        } else {
            localizedPlural(R.plurals.trace_chars, chars, "%d 字")
        }

    private fun JSONObject.firstNonNegativeInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key)) return@firstNotNullOfOrNull null
            optInt(key, -1).takeIf { it >= 0 }
        }

    private fun sanitizeSummaryValue(value: String, maxChars: Int = 80): String =
        value.replace(Regex("\\s+"), " ")
            .trim()
            .replace(',', '，')
            .replace('=', '＝')
            .let { if (it.length <= maxChars) it else it.take(maxChars) + "..." }

    private fun safeHttpHost(rawUrl: String): String? =
        rawUrl.trim()
            .takeIf(String::isNotEmpty)
            ?.let { value ->
                runCatching {
                    val uri = URI(value)
                    uri.host
                        ?.takeIf {
                            uri.scheme.equals("http", ignoreCase = true) ||
                                uri.scheme.equals("https", ignoreCase = true)
                        }
                        ?.lowercase()
                        ?.take(160)
                }.getOrNull()
            }

    private fun String.browserActionLabel(): String = when (this) {
        "navigate" -> localizedText(R.string.trace_browser_open, "打开网页")
        "get_readable" -> localizedText(R.string.trace_browser_readable, "提取正文")
        "get_text" -> localizedText(R.string.trace_browser_text, "读取文本")
        "find_elements" -> localizedText(R.string.trace_browser_find, "查找元素")
        "click" -> localizedText(R.string.trace_browser_click, "点击网页")
        "type" -> localizedText(R.string.trace_browser_type, "输入内容")
        "scroll" -> localizedText(R.string.trace_browser_scroll, "滚动网页")
        "screenshot" -> localizedText(R.string.trace_browser_screenshot, "网页截图")
        "get_page_info" -> localizedText(R.string.trace_browser_page_info, "查看网页信息")
        "go_back" -> localizedText(R.string.trace_browser_back, "网页后退")
        "go_forward" -> localizedText(R.string.trace_browser_forward, "网页前进")
        "reload" -> localizedText(R.string.trace_browser_reload, "刷新网页")
        "wait_for_selector" -> localizedText(R.string.trace_browser_wait_selector, "等待网页元素")
        else -> localizedText(R.string.trace_action_browser, "浏览器操作")
    }

    private fun String.browserSuccessLabel(): String = when (this) {
        "navigate" -> localizedText(R.string.trace_result_opened, "已打开")
        "get_readable" -> localizedText(R.string.trace_browser_done_readable, "已提取正文")
        "get_text" -> localizedText(R.string.trace_browser_done_text, "已读取文本")
        "find_elements" -> localizedText(R.string.trace_browser_done_found, "已找到元素")
        "click" -> localizedText(R.string.trace_browser_done_click, "已点击网页")
        "type" -> localizedText(R.string.trace_browser_done_type, "已输入内容")
        "scroll" -> localizedText(R.string.trace_browser_done_scroll, "已滚动网页")
        "screenshot" -> localizedText(R.string.trace_browser_done_screenshot, "已截图")
        "get_page_info" -> localizedText(R.string.trace_browser_done_page_info, "已读取页面信息")
        "go_back" -> localizedText(R.string.trace_browser_done_back, "已后退")
        "go_forward" -> localizedText(R.string.trace_browser_done_forward, "已前进")
        "reload" -> localizedText(R.string.trace_browser_done_reload, "已刷新")
        "wait_for_selector" -> localizedText(R.string.trace_browser_done_wait_selector, "已等到目标元素")
        else -> localizedText(R.string.trace_result_browser_done, "浏览器操作完成")
    }

    private fun String.scrollDirectionLabel(): String? = when (lowercase(Locale.US)) {
        "up" -> localizedText(R.string.trace_dir_up, "向上")
        "down" -> localizedText(R.string.trace_dir_down, "向下")
        "left" -> localizedText(R.string.trace_dir_left, "向左")
        "right" -> localizedText(R.string.trace_dir_right, "向右")
        else -> null
    }

    private fun String.pressKeyLabel(): String? = when (lowercase(Locale.US)) {
        "back" -> localizedText(R.string.trace_key_back, "返回")
        "home" -> localizedText(R.string.trace_key_home, "主页")
        "recents", "recent" -> localizedText(R.string.trace_key_recents, "最近任务")
        "notifications" -> localizedText(R.string.trace_key_notifications, "通知栏")
        "quick_settings" -> localizedText(R.string.trace_key_quick_settings, "控制中心")
        "power" -> localizedText(R.string.trace_key_power, "电源")
        "volume_up" -> localizedText(R.string.trace_key_volume_up, "音量加")
        "volume_down" -> localizedText(R.string.trace_key_volume_down, "音量减")
        "mute" -> localizedText(R.string.trace_key_mute, "静音")
        else -> null
    }

    private fun String.terminalActionLabel(): String = when (this) {
        "open" -> localizedText(R.string.trace_term_open, "创建会话")
        "exec" -> localizedText(R.string.trace_action_exec, "执行命令")
        "open_and_exec" -> localizedText(R.string.trace_term_open_exec, "单次执行")
        "read_async_result" -> localizedText(R.string.trace_term_read_async, "读取后台输出")
        "close" -> localizedText(R.string.trace_term_close, "关闭终端")
        "daemon_start" -> localizedText(R.string.trace_term_daemon_start, "启动守护任务")
        "daemon_list" -> localizedText(R.string.trace_term_daemon_list, "守护任务列表")
        "daemon_logs" -> localizedText(R.string.trace_term_daemon_logs, "查看守护日志")
        "daemon_stop" -> localizedText(R.string.trace_term_daemon_stop, "停止守护任务")
        else -> localizedText(R.string.trace_term_fallback, "终端操作")
    }

    private fun String.terminalEnvironmentLabel(): String = when (this) {
        "alpine" -> "Alpine"
        "debian" -> "Debian"
        "linux" -> linuxEnvironmentLabelProvider()
        else -> "Android"
    }

    private data class TerminalRuntimeSummary(
        val environment: String,
        val identity: String?,
    )

    private companion object {
        const val BROWSER_TOOL_NAME = "browser_use"
        const val MAX_DISPLAY_COMMAND_CHARS = 4_000
        /** 卡片展开区展示的参数/结果上限。参数短，结果可能是一整页文件内容。 */
        const val MAX_ARGUMENT_DETAIL_CHARS = 4_000
        const val MAX_RESULT_DETAIL_CHARS = 8_000
        const val MAX_QUERY_SUMMARY_CHARS = 30
        const val MAX_LISTED_APP_NAMES = 3
        const val MAX_TERMINAL_PREVIEW_LINES = 3
        const val MAX_TERMINAL_PREVIEW_CHARS = 240
        val SENSITIVE_ASSIGNMENT = Regex(
            """(?i)\b([A-Z0-9_]*(?:API[_-]?KEY|ACCESS[_-]?TOKEN|AUTH[_-]?TOKEN|TOKEN|PASSWORD|PASSWD|SECRET)[A-Z0-9_]*)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_FLAG = Regex(
            """(?i)(--?(?:password|passwd|token|api[-_]?key|secret)(?:\s*=\s*|\s+))(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_HEADER = Regex(
            """(?i)\b(Authorization|Proxy-Authorization|X-Api-Key)(\s*:\s*)[^'"\r\n;&|]+"""
        )
        /** 内联 base64 图片/音视频：展开区只保留体积提示，避免整段 data URL 撑爆卡片与数据库。 */
        val INLINE_MEDIA = Regex(
            """(data:[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+;base64,)([A-Za-z0-9+/=\r\n]{64,})"""
        )
        val BROWSER_ACTIONS = setOf(
            "navigate",
            "get_readable",
            "get_text",
            "find_elements",
            "click",
            "type",
            "scroll",
            "screenshot",
            "get_page_info",
            "go_back",
            "go_forward",
            "reload",
            "wait_for_selector",
        )
        val BROWSER_TEXT_ACTIONS = setOf("get_readable", "get_text")

        /** 结构化设备工具只展示动作标签，不暴露任何参数。 */
        val DEVICE_ACTION_LABELS: Map<String, Pair<Int, String>> = mapOf(
            "set_timer" to (R.string.trace_dev_set_timer to "设置计时器"),
            "delete_alarm" to (R.string.trace_dev_delete_alarm to "删除闹钟"),
            "update_alarm" to (R.string.trace_dev_update_alarm to "修改闹钟"),
            "create_calendar_event" to (R.string.trace_dev_create_calendar_event to "创建日程"),
            "update_calendar_event" to (R.string.trace_dev_update_calendar_event to "修改日程"),
            "delete_calendar_event" to (R.string.trace_dev_delete_calendar_event to "删除日程"),
            "device_status" to (R.string.trace_dev_device_status to "查看设备状态"),
            "network_info" to (R.string.trace_dev_network_info to "查看网络信息"),
            "top_memory_apps" to (R.string.trace_dev_top_memory_apps to "查看内存占用排行"),
            "top_storage_apps" to (R.string.trace_dev_top_storage_apps to "查看存储占用排行"),
            "media_control" to (R.string.trace_dev_media_control to "控制媒体播放"),
            "set_volume" to (R.string.trace_dev_set_volume to "调整音量"),
            "device_actions" to (R.string.trace_dev_device_actions to "端侧动作执行"),
            "get_setting" to (R.string.trace_dev_get_setting to "读取系统设置"),
            "wifi_credentials" to (R.string.trace_dev_wifi_credentials to "读取 Wi-Fi 密码"),
            "recent_notifications" to (R.string.trace_dev_recent_notifications to "读取最近通知"),
            "search_notification_history" to (R.string.trace_dev_search_notification_history to "搜索通知历史"),
            "recent_app_activity" to (R.string.trace_dev_recent_app_activity to "查看应用活动"),
            "app_usage_summary" to (R.string.trace_dev_app_usage_summary to "查看应用使用统计"),
            "get_current_location" to (R.string.trace_dev_get_current_location to "获取当前位置"),
            "get_device_environment" to (R.string.trace_dev_get_device_environment to "查看设备环境"),
            "list_alarms" to (R.string.trace_dev_list_alarms to "查看闹钟列表"),
            "list_active_timers" to (R.string.trace_dev_list_active_timers to "查看计时器"),
            "search_clipboard_history" to (R.string.trace_dev_search_clipboard_history to "搜索剪贴板历史"),
            "get_health_summary" to (R.string.trace_dev_get_health_summary to "查看健康摘要"),
            "read_sms_code" to (R.string.trace_dev_read_sms_code to "读取短信验证码"),
            "get_logcat" to (R.string.trace_dev_get_logcat to "读取系统日志"),
            "search_media" to (R.string.trace_dev_search_media to "搜索媒体文件"),
            "search_audio" to (R.string.trace_dev_search_audio to "搜索音频"),
            "search_recordings" to (R.string.trace_dev_search_recordings to "搜索录音"),
            "search_files" to (R.string.trace_dev_search_files to "搜索文件"),
            "search_calendar_events" to (R.string.trace_dev_search_calendar_events to "搜索日程"),
            "search_contacts" to (R.string.trace_dev_search_contacts to "搜索联系人"),
            "search_call_history" to (R.string.trace_dev_search_call_history to "搜索通话记录"),
            "search_messages" to (R.string.trace_dev_search_messages to "搜索短信"),
            "search_downloads" to (R.string.trace_dev_search_downloads to "搜索下载内容"),
            "search_coloros_notes" to (R.string.trace_dev_search_coloros_notes to "搜索便签"),
            "search_coloros_recordings" to (R.string.trace_dev_search_coloros_recordings to "搜索录音机"),
            "search_recording_summaries" to (R.string.trace_dev_search_recording_summaries to "搜索录音摘要"),
            "search_coloros_memories" to (R.string.trace_dev_search_coloros_memories to "搜索小布记忆"),
            "search_saved_places" to (R.string.trace_dev_search_saved_places to "搜索收藏地点"),
            "search_personal_orders" to (R.string.trace_dev_search_personal_orders to "搜索个人订单"),
            "search_qq_chat_images" to (R.string.trace_dev_search_qq_chat_images to "搜索 QQ 聊天图片"),
            "search_wechat_chat_images" to (R.string.trace_dev_search_wechat_chat_images to "搜索微信聊天图片"),
            "set_setting" to (R.string.trace_dev_set_setting to "修改系统设置"),
            "set_device_state" to (R.string.trace_dev_set_device_state to "修改设备状态"),
            "app_state_control" to (R.string.trace_dev_app_state_control to "管理应用状态"),
            "set_ringer_mode" to (R.string.trace_dev_set_ringer_mode to "切换铃声模式"),
            "set_do_not_disturb" to (R.string.trace_dev_set_do_not_disturb to "设置勿扰模式"),
            "set_brightness" to (R.string.trace_dev_set_brightness to "调整屏幕亮度"),
            "screen_action" to (R.string.trace_dev_screen_action to "屏幕电源动作"),
            "list_installed_apps" to (R.string.trace_dev_list_installed_apps to "查看已安装应用"),
            "manage_app" to (R.string.trace_dev_manage_app to "管理应用"),
            "manage_file" to (R.string.trace_dev_manage_file to "管理文件"),
            "system_property" to (R.string.trace_dev_system_property to "管理系统属性"),
            "display_control" to (R.string.trace_dev_display_control to "调整显示参数"),
            "deep_launch" to (R.string.trace_dev_deep_launch to "直达应用界面"),
            "package_control" to (R.string.trace_dev_package_control to "管理安装包"),
            "user_control" to (R.string.trace_dev_user_control to "管理用户"),
            "appops_control" to (R.string.trace_dev_appops_control to "管控应用权限行为"),
            "power_control" to (R.string.trace_dev_power_control to "电源策略控制"),
            "ime_control" to (R.string.trace_dev_ime_control to "管理输入法"),
            "connect_wifi" to (R.string.trace_dev_connect_wifi to "连接 Wi-Fi"),
            "set_timezone" to (R.string.trace_dev_set_timezone to "设置时区"),
            "search_settings" to (R.string.trace_dev_search_settings to "搜索系统设置"),
            "dumpsys_diag" to (R.string.trace_dev_dumpsys_diag to "系统诊断"),
            "eta_author_tool" to (R.string.trace_dev_eta_author_tool to "编写自建工具"),
            "eta_list_recipes" to (R.string.trace_dev_eta_list_recipes to "查看自建工具"),
        )
    }
}
