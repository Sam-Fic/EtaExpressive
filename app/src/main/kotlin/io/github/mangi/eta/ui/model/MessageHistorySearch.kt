package io.github.mangi.eta.ui.model

/** 命中来源：会话标题 / 消息正文。标题命中没有目标消息，只是把会话本身定位出来。 */
enum class MessageSearchHitKind {
    Title,
    Message,
}

data class MessageSearchHit(
    val conversationId: String,
    val conversationTitle: String,
    val messageId: String,
    val snippet: String,
    val roleLabel: String,
    val kind: MessageSearchHitKind = MessageSearchHitKind.Message,
) {
    /** 标题命中：没有可跳转的消息 id。 */
    val isTitleMatch: Boolean get() = kind == MessageSearchHitKind.Title
}

data class MessageSearchRoleLabels(
    val user: String,
    val assistant: String,
    val thinking: String,
    val tool: String,
)

/**
 * 正文命中：查询词出现在消息正文里时返回命中（含用户、助手、思考与工具轨迹）。
 *
 * 标题不在这条路径里判定：标题是会话级属性，按消息分页扫描会重复产出，
 * 所以标题命中统一由 [searchConversationTitle] 在会话这一层给出一次。
 */
internal fun searchConversationMessages(
    conversations: Map<String, AgentChatUiState>,
    titles: Map<String, String>,
    updatedAt: Map<String, Long>,
    query: String,
    unnamedTitle: String,
    roleLabels: MessageSearchRoleLabels,
): List<MessageSearchHit> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()

    return conversations.entries
        .sortedWith(
            compareBy<Map.Entry<String, AgentChatUiState>> { updatedAt[it.key] ?: 0L }
                .thenBy { it.key },
        )
        .flatMap { (conversationId, state) ->
            val title = titles[conversationId].orEmpty().ifBlank { unnamedTitle }
            state.messages.mapIndexedNotNull { index, message ->
                val haystack = message.searchableText() ?: return@mapIndexedNotNull null
                if (!haystack.contains(needle, ignoreCase = true)) return@mapIndexedNotNull null
                val roleLabel = when (message) {
                    is UserMessageUi -> roleLabels.user
                    is AgentMessageUi -> roleLabels.assistant
                    is ThinkingMessageUi -> roleLabels.thinking
                    is ToolActivityMessageUi -> roleLabels.tool
                    else -> return@mapIndexedNotNull null
                }
                index to MessageSearchHit(
                    conversationId = conversationId,
                    conversationTitle = title,
                    messageId = message.id,
                    snippet = excerptAround(haystack, needle),
                    roleLabel = roleLabel,
                )
            }
        }
        .sortedWith(compareBy({ updatedAt[it.second.conversationId] ?: 0L }, { it.first }))
        .map { it.second }
}

/**
 * 标题命中：查询词出现在会话标题里时返回一条命中。
 *
 * 标题命中不带消息 id，点开只切到会话本身；正文命中仍由 [searchConversationMessages] 给出。
 * 未命名会话用的是占位标题，不参与标题匹配（否则「未命名」会命中一堆会话）。
 */
internal fun searchConversationTitle(
    conversationId: String,
    title: String,
    query: String,
    titleRoleLabel: String,
): MessageSearchHit? {
    val needle = query.trim()
    if (needle.isEmpty()) return null
    if (title.isBlank()) return null
    if (!title.contains(needle, ignoreCase = true)) return null
    return MessageSearchHit(
        conversationId = conversationId,
        conversationTitle = title,
        messageId = "",
        snippet = excerptAround(title, needle),
        roleLabel = titleRoleLabel,
        kind = MessageSearchHitKind.Title,
    )
}

private fun AgentChatMessageUi.searchableText(): String? = when (this) {
    is UserMessageUi -> content
    is AgentMessageUi -> content
    is ThinkingMessageUi -> content
    is ToolActivityMessageUi -> listOfNotNull(
        toolName,
        argumentsSummary,
        command,
        resultSummary,
    ).joinToString(" ")
    else -> null
}

// 空白折叠 Pattern 提为常量：excerptAround 对每条命中正文调用，现场编译纯浪费。
private val WhitespaceCollapsePattern = Regex("\\s+")

internal fun excerptAround(text: String, query: String, radius: Int = 36): String {
    val collapsed = text.replace(WhitespaceCollapsePattern, " ").trim()
    val index = collapsed.indexOf(query, ignoreCase = true)
    if (index < 0) return collapsed.take(80)
    val start = (index - radius).coerceAtLeast(0)
    val end = (index + query.length + radius).coerceAtMost(collapsed.length)
    return buildString {
        if (start > 0) append("…")
        append(collapsed.substring(start, end))
        if (end < collapsed.length) append("…")
    }
}
