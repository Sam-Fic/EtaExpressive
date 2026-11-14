package io.github.mangi.eta.agent.model

/** Strict checkpoint structure, shared by acceptance and format repair. Never fills missing sections. */
internal object AgentSummaryFormat {
    private const val SUMMARY_PREFIX = "[Conversation summary]"
    private const val SUMMARY_PREFIX_ZH = "[对话摘要]"
    /**
     * 第 9 段「关键实体与线索」用于后续按关键词检索归档原文：路径、包名/控件/ID、
     * 具体数值、错误码、用户约束集中列出，模型不必靠重读整段前缀找回标识。
     * 取证：上游 Mangi-11/Eta 提交 01d2148 要求摘要末尾保留关键实体清单。
     */
    val SUMMARY_SECTIONS = listOf(
        "Primary Request and Intent",
        "Key Technical Concepts",
        "Files and Code",
        "Errors and Fixes",
        "Pending Jobs",
        "Current Work",
        "Next Step",
        "Critical Context",
        "Key Entities and Clues",
    )

    fun validateSummary(text: String) {
        require(coerceSummary(text) != null) { "摘要结构不完整或顺序无效，原历史保持不变" }
    }

    fun coerceSummary(text: String): String? {
        val body = stripSummaryWrapper(text)
        if (body.isBlank()) return null
        val sections = extractSummarySections(body) ?: return null
        return buildString {
            appendLine(SUMMARY_PREFIX_ZH)
            SUMMARY_SECTIONS.forEachIndexed { index, heading ->
                append("## ").append(heading).append('\n')
                append(sections[index].ifBlank { "- (none)" })
                if (index != SUMMARY_SECTIONS.lastIndex) append('\n')
            }
        }.trimEnd()
    }

    private fun stripSummaryWrapper(text: String): String {
        var body = text.trim()
        if (body.startsWith("```")) {
            body = body.removePrefix("```").substringAfter('\n', body)
            if (body.endsWith("```")) body = body.removeSuffix("```")
            body = body.trim()
        }
        val marker = listOf(SUMMARY_PREFIX, SUMMARY_PREFIX_ZH, "[Summary of previous conversation]", "[Summary")
            .firstOrNull { needle -> body.contains(needle) }
        if (marker != null) {
            body = body.substring(body.indexOf(marker)).trim()
        }
        return body
    }

    private fun extractSummarySections(text: String): List<String>? {
        val aliases = mapOf(
            "primary request and intent" to 0, "主要请求与意图" to 0, "主要请求" to 0, "goal" to 0, "目标" to 0,
            "key technical concepts" to 1, "关键技术概念" to 1, "关键技术" to 1,
            "files and code" to 2, "文件与代码" to 2, "files and identifiers" to 2, "文件和标识符" to 2,
            "文件与标识符" to 2, "文件" to 2,
            "errors and fixes" to 3, "错误与修复" to 3, "errors and open issues" to 3,
            "错误和待解决问题" to 3, "错误与待办" to 3, "错误" to 3,
            "pending jobs" to 4, "pending work" to 4, "待办工作" to 4, "未完成工作" to 4, "待办" to 4,
            "current work" to 5, "current state" to 5, "当前工作" to 5, "当前状态" to 5, "现状" to 5,
            "verified evidence" to 5, "已验证证据" to 5, "已核实证据" to 5,
            "next step" to 6, "下一步" to 6, "下一步行动" to 6,
            "critical context" to 7, "关键上下文" to 7, "constraints" to 7, "约束" to 7, "限制" to 7,
            "key entities and clues" to 8, "关键实体与线索" to 8, "关键实体" to 8,
        )
        val heading = Regex("""^#{1,3}\s+(.+)$""")
        val buckets = MutableList(SUMMARY_SECTIONS.size) { StringBuilder() }
        var current = -1
        var fence: String? = null
        var headingDepth: Int? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val marker = trimmed.take(3)
                if (fence == null) fence = marker else if (fence == marker) fence = null
                if (current >= 0) buckets[current].append(line).append('\n')
                return@forEach
            }
            val match = if (fence == null) heading.matchEntire(trimmed) else null
            if (match != null) {
                val title = match.groupValues[1].trim().trimStart('#', ' ', '：', ':')
                    .removePrefix("[")
                    .removeSuffix("]")
                    .lowercase()
                val index = aliases[title]
                val depth = trimmed.takeWhile { it == '#' }.length
                if (index != null) {
                    if (index != current + 1 || (headingDepth != null && depth != headingDepth)) return null
                    headingDepth = depth
                    current = index
                    return@forEach
                }
                // Nested code/document subsections are content; unknown peer headings are malformed.
                if (headingDepth == null || depth <= headingDepth) return null
            }
            if (current >= 0 && line.isNotBlank() && !line.startsWith(SUMMARY_PREFIX) && !line.startsWith(SUMMARY_PREFIX_ZH)) {
                if (buckets[current].isNotEmpty()) buckets[current].append('\n')
                buckets[current].append(line.trim())
            }
        }
        if (current != SUMMARY_SECTIONS.lastIndex || fence != null) return null
        return buckets.map { it.toString().trim() }.takeIf { sections -> sections.all { it.isNotBlank() } }
    }

}
