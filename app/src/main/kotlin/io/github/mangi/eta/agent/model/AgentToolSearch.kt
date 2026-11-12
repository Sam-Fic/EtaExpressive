package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具级关键词搜索（设计移植自 ZCode 的 ToolSearch 本地投影）：
 * 对未打开延迟组里的工具按名字/描述评分排序，让模型按需加载单个工具，
 * 而不是为了一两个工具打开整组 schema。
 *
 * 与 [AgentToolGroups] 的单一事实源约定一致：候选范围由调用方按
 * availability/决策收窄/单工具禁用集预先圈定，这里只做纯文本匹配，
 * 不感知任何开关与权限——搜索不等于解锁，解锁仍走 tools_use_group 的同一套闸门。
 *
 * 只返回目录里的名字，不执行目标工具；关键词不按正则解释，
 * `|`、`.*` 之类字符一律按字面处理。
 */
internal object AgentToolSearch {
    const val DEFAULT_RESULTS = 5
    private val MCP_NAME = Regex("""^mcp_(.+?)_(.+?)_[0-9a-f]{4}$""")
    private val SPLIT_CHARS = arrayOf(" ", "_", ".", "-")

    /** 一个可搜索的延迟工具：名字、描述与所属延迟组（用于命中后整组解锁）。 */
    data class Candidate(
        val name: String,
        val description: String?,
        /** 组索引摘要之外可参与匹配的补充关键词；null 表示没有。 */
        val searchHint: String?,
        val mcp: Boolean,
        val groupId: String,
    )

    data class Hit(
        val name: String,
        val groupId: String,
        val score: Int,
    )

    private class Tokenized(
        val coarse: List<String>,
        val parts: List<String>,
        val full: String,
        val mcp: Boolean,
    )

    private fun tokenize(candidate: Candidate): Tokenized {
        val name = candidate.name
        val mcpMatch = if (candidate.mcp) MCP_NAME.find(name) else null
        return if (mcpMatch != null) {
            val (server, tool) = mcpMatch.destructured
            val coarse = listOf(server.lowercase(), tool.lowercase())
            val parts = coarse.flatMap { it.split(*SPLIT_CHARS).filter(String::isNotEmpty) }
            Tokenized(coarse, parts, parts.joinToString(" "), mcp = true)
        } else {
            // camelCase 拆词 + snake/kebab 分隔符拆词，保证「发送文本」能命中 sendText。
            val parts = name
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .replace('_', ' ')
                .replace('-', ' ')
                .lowercase()
                .split(' ')
                .filter(String::isNotEmpty)
            Tokenized(listOf(name.lowercase()), parts, parts.joinToString(" "), mcp = false)
        }
    }

    /**
     * 对 [candidates] 评分并返回最高分的若干命中，分数降序、同分按名字稳定排序。
     *
     * 查询语法（与 ZCode 对齐）：
     * - `select:name1,name2`：按名字（或组内别名）精确选择，不做模糊匹配；
     * - `+term`：term 必须在名字分词或描述里出现，其余词只参与排序；
     * - 空格分隔关键词：全部词都要命中（AND），按综合得分排序。
     */
    fun search(
        query: String,
        candidates: List<Candidate>,
        maxResults: Int = DEFAULT_RESULTS,
    ): List<Hit> {
        if (candidates.isEmpty()) return emptyList()
        val select = Regex("""^select:(.+)$""", RegexOption.IGNORE_CASE).find(query.trim())
        if (select != null) {
            val byName = candidates.associateBy { it.name.lowercase() }
            return select.groupValues[1].split(',')
                .mapNotNull { requested ->
                    val name = requested.trim().lowercase()
                    byName[name]?.let { Hit(it.name, it.groupId, Int.MAX_VALUE) }
                }
                .distinctBy { it.name }
                .take(maxResults.coerceAtLeast(1))
        }
        val text = query.lowercase().trim()
        if (text.isEmpty()) return emptyList()
        // MCP 工具名前缀直达：模型看到组索引里的 mcp_ 名后常会直接抄前缀。
        if (text.startsWith("mcp_")) {
            val prefixHits = candidates
                .filter { it.name.lowercase().startsWith(text) }
                .take(maxResults.coerceAtLeast(1))
                .map { Hit(it.name, it.groupId, Int.MAX_VALUE) }
            if (prefixHits.isNotEmpty()) return prefixHits
        }
        val terms = text.split(' ').filter(String::isNotEmpty)
        val required = terms.filter { it.length > 1 && it.startsWith("+") }.map { it.drop(1) }
        val keywords = if (required.isEmpty()) terms else required + terms.filter { it.length <= 1 || !it.startsWith("+") }
        val patterns = keywords.associateWith { term -> Regex("\\b${Regex.escape(term)}\\b") }
        return candidates.asSequence()
            .map { candidate -> candidate to tokenize(candidate) }
            .mapNotNull { (candidate, tokenized) ->
                val description = candidate.description?.lowercase().orEmpty()
                val hint = candidate.searchHint?.lowercase().orEmpty()
                // 必含词不满足直接淘汰：这是模型表达「必须是这类工具」的手段。
                val matched = required.all { term ->
                    tokenized.parts.any { part -> part.contains(term) } ||
                        tokenized.coarse.any { part -> part.contains(term) } ||
                        patterns[term]?.containsMatchIn(description) == true ||
                        patterns[term]?.containsMatchIn(hint) == true
                }
                if (!matched) return@mapNotNull null
                var score = 0
                for (term in keywords) {
                    val mcpBonus = if (tokenized.mcp) 2 else 0
                    when {
                        tokenized.parts.contains(term) -> score += 10 + mcpBonus
                        tokenized.parts.any { part -> part.contains(term) } -> score += 5 + mcpBonus
                    }
                    when {
                        tokenized.coarse.contains(term) -> score += 10 + mcpBonus
                        tokenized.coarse.any { part -> part.contains(term) } -> score += 3 + mcpBonus
                    }
                    if (tokenized.full.contains(term) && score == 0) score += 3
                    if (hint.isNotEmpty() && patterns[term]?.containsMatchIn(hint) == true) score += 4
                    if (patterns[term]?.containsMatchIn(description) == true) score += 2
                }
                if (score <= 0) null else Hit(candidate.name, candidate.groupId, score)
            }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy(Hit::name))
            .take(maxResults.coerceAtLeast(1))
            .toList()
    }
}

/**
 * `tools_search` 的 schema 与回执；与 [AgentToolGroupCatalog] 同一文件族，
 * 供 [AgentToolCatalog] 常驻下发、[io.github.mangi.eta.agent.tool.AgentLocalTools] 执行。
 */
internal object AgentToolSearchCatalog {
    const val SEARCH = "tools_search"

    /**
     * 只在「渐进加载为自动档」（[searchEnabled]）且存在「开关允许、尚未打开」的
     * 延迟组时下发：按组档模型只能整组打开（tools_use_group），没有可搜索对象时
     * 出现这个工具只会诱导模型空搜。与 tools_use_group 的枚举共用同一份 openable 集合。
     */
    fun appendTo(tools: JSONArray, openableGroupIds: List<String>, searchEnabled: Boolean = true) {
        if (!searchEnabled || openableGroupIds.isEmpty()) return
        tools.put(
            AgentToolSchema.function(
                name = SEARCH,
                description = "Search deferred tools by keyword and load the ones you need without " +
                    "opening their whole group. Query forms: \"select:tool_name,another_one\" for exact " +
                    "names; space-separated keywords (all must match); \"+term\" requires term in the " +
                    "tool name or description. Regular expressions are not supported. A match makes the " +
                    "tool's group available from the next round onward, like tools_use_group, but only " +
                    "the group that owns the matched tool.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "query",
                                JSONObject()
                                    .put("type", "string")
                                    .put(
                                        "description",
                                        "Keywords, \"+term\" required terms, or \"select:name1,name2\". " +
                                            "Use the group index summaries and tool purposes as vocabulary.",
                                    ),
                            )
                            .put(
                                "max_results",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "Maximum matches to return (default 5, max 20)."),
                            ),
                    )
                    .put("required", JSONArray().put("query"))
                    .put("additionalProperties", false),
            ),
        )
    }

    /**
     * 命中回执。列出命中的工具与其所属组，并说明解锁语义：与 tools_use_group 相同的
     * next_turn 语义，但省掉了单独的打开轮次。
     *
     * [unlockedGroups] 是本次真正打开的组（闸门允许且此前未打开）；
     * [unavailableGroups] 是命中但被决策收窄/用户开关挡住的组，明确告知模型别再尝试。
     */
    fun result(
        hits: List<AgentToolSearch.Hit>,
        labels: Map<String, String>,
        unlockedGroups: Set<String>,
        unavailableGroups: Map<String, String>,
        /** 缓存优先档：命中工具的参数说明，随回执直接给出，省掉再开一次组。 */
        toolDocs: JSONArray? = null,
        /** true = 这些工具不在 `tools` 里，必须经 [AgentToolGroupCatalog.CALL] 调用。 */
        requiresToolCall: Boolean = false,
    ): String {
        val byGroup = hits.groupBy { it.groupId }
        val lines = JSONArray()
        byGroup.forEach { (groupId, groupHits) ->
            lines.put(
                JSONObject()
                    .put("group", groupId)
                    .put("group_label", labels[groupId] ?: groupId)
                    .put("matched_tools", JSONArray(groupHits.map { it.name })),
            )
        }
        val payload = JSONObject()
            .put("ok", true)
            .put("matches", lines)
            .put("available", if (requiresToolCall) "now" else "next_turn")
        if (requiresToolCall) {
            payload.put("call_via", AgentToolGroupCatalog.CALL)
            toolDocs?.let { payload.put("tool_docs", it) }
        }
        if (unlockedGroups.isNotEmpty()) {
            payload.put(
                "message",
                when {
                    requiresToolCall ->
                        "Matched tools listed above; their groups are now loaded. Call them with " +
                            "`${AgentToolGroupCatalog.CALL}` ({name, arguments}) right now — tool_docs " +
                            "carries each tool's parameters. Call only the listed names."
                    else ->
                        "Matched tools listed above. Their groups are now opened; the tools are available " +
                            "from the next round onward and are not part of the request you are answering " +
                            "right now. Call only these names."
                },
            )
        } else {
            payload.put(
                "message",
                "Matched tools listed above, but none of their groups can be opened in this run " +
                    "(closed in settings or narrowed by the decision model). Do not call them.",
            )
        }
        if (unavailableGroups.isNotEmpty()) {
            payload.put(
                "unavailable_groups",
                JSONObject(unavailableGroups.mapValues { it.value }),
            )
        }
        return payload.toString()
    }

    fun noMatches(query: String, knownGroups: List<String>): String = JSONObject()
        .put("ok", false)
        .put("code", "NO_MATCHING_TOOLS")
        .put("query", query)
        .put("available_groups", JSONArray(knownGroups.sorted()))
        .put(
            "message",
            "No matching deferred tools found. Tools already available in this round are not " +
                "searchable here and need no loading. Check spelling, try broader keywords, or " +
                "open a whole group with tools_use_group if you know which one you need.",
        )
        .toString()

    fun invalidQuery(): String = JSONObject()
        .put("ok", false)
        .put("code", "INVALID_ARGUMENT")
        .put("message", "query must be a non-empty string.")
        .toString()
}
