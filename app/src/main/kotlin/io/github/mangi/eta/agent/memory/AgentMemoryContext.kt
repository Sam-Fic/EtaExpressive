package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot

internal data class AgentMemoryContext(
    val enabled: Boolean,
    val revision: String,
    val byteSize: Int,
    val coreContent: String,
    val coreTruncated: Boolean,
    val headingIndex: String,
    val coreBudgetChars: Int,
    /** 被识别为核心记忆的段数（同名 "# 核心记忆" 可以出现多段，全部会被合并注入）。 */
    val coreSectionCount: Int = 0,
    /** 实际注入的行数。 */
    val coreInjectedLines: Int = 0,
    /** 核心记忆的总行数（注入 + 未注入）。 */
    val coreTotalLines: Int = 0,
    /** 被截断时，下一次 `memory_get(start_line=…)` 应该从这一行继续（文件绝对行号，1-based）。 */
    val coreNextLine: Int? = null,
    /** 标题索引上的提醒（同名标题重复、索引被截断等）。 */
    val headingWarning: String = "",
) {
    companion object {
        val DISABLED = AgentMemoryContext(
            enabled = false,
            revision = "",
            byteSize = 0,
            coreContent = "",
            coreTruncated = false,
            headingIndex = "",
            coreBudgetChars = 0,
        )
    }
}

internal object AgentMemoryContextBuilder {
    fun empty(contextWindow: Int?): AgentMemoryContext = build(
        snapshot = AgentMemorySnapshot(
            content = "",
            revision = EMPTY_SHA256,
            byteSize = 0,
            lineCount = 0,
        ),
        contextWindow = contextWindow,
    )

    fun build(
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
    ): AgentMemoryContext {
        val coreBudget = coreBudgetChars(contextWindow)
        val core = extractCoreSections(snapshot.content)
        val slice = truncateCore(core, coreBudget)
        val headingLines = snapshot.content.lineSequence()
            .map { it.trimEnd() }
            .filter { line -> HEADING.matches(line) }
            .toList()
        val index = boundedHeadingIndex(headingLines)
        return AgentMemoryContext(
            enabled = true,
            revision = snapshot.revision,
            byteSize = snapshot.byteSize,
            coreContent = slice.text,
            coreTruncated = slice.truncated,
            headingIndex = index.text,
            coreBudgetChars = coreBudget,
            coreSectionCount = core.sectionCount,
            coreInjectedLines = slice.injectedLines,
            coreTotalLines = core.totalLines,
            coreNextLine = slice.nextLine,
            headingWarning = headingWarning(headingLines, index.truncated),
        )
    }

    fun coreBudgetChars(contextWindow: Int?): Int {
        val resolvedWindow = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        return (resolvedWindow / CONTEXT_WINDOW_DIVISOR)
            .coerceAtLeast(MIN_CORE_CHARS)
    }

    private data class CoreExtraction(
        val text: String,
        /** 合并后每一行对应的文件绝对行号（1-based）。 */
        val fileLines: List<Int>,
        val sectionCount: Int,
    ) {
        val totalLines: Int get() = fileLines.size
    }

    /**
     * 收集**所有** `# 核心记忆` 段（原先只取第一段，导致第二个同名段整段被静默排除在注入之外）。
     * 每段从标题行开始，到下一个一级标题为止。
     */
    private fun extractCoreSections(content: String): CoreExtraction {
        if (content.isEmpty()) return CoreExtraction("", emptyList(), 0)
        val lines = content.split('\n')
        val text = StringBuilder()
        val fileLines = mutableListOf<Int>()
        var sectionCount = 0
        var index = 0
        while (index < lines.size) {
            if (lines[index].trim() != CORE_HEADING) {
                index++
                continue
            }
            val end = ((index + 1) until lines.size)
                .firstOrNull { lines[it].trimEnd().startsWith("# ") }
                ?: lines.size
            sectionCount++
            for (cursor in index until end) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(lines[cursor])
                fileLines += cursor + 1
            }
            index = end
        }
        return CoreExtraction(text.toString(), fileLines, sectionCount)
    }

    private data class CoreSlice(
        val text: String,
        val truncated: Boolean,
        val injectedLines: Int,
        val nextLine: Int?,
    )

    /**
     * 按**行边界**截断，而不是直接 `take(budget)`：
     * 原实现会把最后一行切在句子中间（甚至切断 UTF-16 代理对），且不告诉模型"从哪继续"。
     * 单行本身超预算时（例如一行 1000+ 字符的长条目）才退化为按字符安全截断，并标记该行需要重读。
     */
    private fun truncateCore(core: CoreExtraction, budget: Int): CoreSlice {
        if (core.text.isEmpty()) return CoreSlice("", false, 0, null)
        val lines = core.text.split('\n')
        if (core.text.length <= budget) {
            return CoreSlice(core.text, false, lines.size, null)
        }
        val kept = StringBuilder()
        var count = 0
        var partial = false
        for (index in lines.indices) {
            val line = lines[index]
            val separator = if (kept.isEmpty()) 0 else 1
            if (kept.length + separator + line.length > budget) {
                if (kept.isEmpty()) {
                    kept.append(safeTake(line, budget))
                    count = 1
                    partial = true
                }
                break
            }
            if (separator == 1) kept.append('\n')
            kept.append(line)
            count++
        }
        if (count == 0) {
            return CoreSlice("", true, 0, core.fileLines.firstOrNull())
        }
        val nextMergedIndex = if (partial) 0 else count
        return CoreSlice(
            text = kept.toString(),
            truncated = true,
            injectedLines = count,
            nextLine = core.fileLines.getOrNull(nextMergedIndex) ?: core.fileLines.last(),
        )
    }

    private data class HeadingIndex(val text: String, val truncated: Boolean)

    /** 按**整行**边界限制标题索引，避免切出半个标题；同时报告是否被截断。 */
    private fun boundedHeadingIndex(headingLines: List<String>): HeadingIndex {
        val kept = mutableListOf<String>()
        var length = 0
        for (line in headingLines) {
            if (length + line.length + 1 > MAX_HEADING_INDEX_CHARS) break
            kept += line
            length += line.length + 1
        }
        return HeadingIndex(kept.joinToString("\n"), kept.size < headingLines.size)
    }

    private fun headingWarning(headingLines: List<String>, indexTruncated: Boolean): String {
        val duplicated = headingLines.groupingBy { it }.eachCount().filterValues { it > 1 }
        return buildString {
            if (duplicated.isNotEmpty()) {
                append("同名标题出现多次：")
                append(duplicated.entries.joinToString("、") { "${it.key} ×${it.value}" })
                append("。")
                if (duplicated.containsKey(CORE_HEADING)) {
                    append("重复的 \"$CORE_HEADING\" 不会额外获得注入位置，建议用 ")
                    append("memory_write(mode=\"replace_section\", section=\"…\") 合并成一节。")
                }
            }
            if (indexTruncated) {
                if (isNotEmpty()) append(' ')
                append("标题索引已截断到 $MAX_HEADING_INDEX_CHARS 字符，文件里可能还有更多标题。")
            }
        }
    }

    private val HEADING = Regex("^#{1,2}\\s+.+$")
    private const val CORE_HEADING = "# 核心记忆"
    private const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val CONTEXT_WINDOW_DIVISOR = 16
    private const val MIN_CORE_CHARS = 4_000
    private const val MAX_HEADING_INDEX_CHARS = 4_000
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}

/** 不在截断点切断 UTF-16 代理对（emoji 等）。 */
private fun safeTake(text: String, limit: Int): String {
    if (limit <= 0) return ""
    if (text.length <= limit) return text
    val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
    return text.substring(0, end)
}
