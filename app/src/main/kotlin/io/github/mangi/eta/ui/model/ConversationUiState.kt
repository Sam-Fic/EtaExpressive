package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class ConversationPaneUiState(
    val conversations: List<ConversationSummaryUi>,
    val selectedConversationId: String?,
    val searchQuery: String,
    val folders: List<ConversationFolderUi> = emptyList(),
    val selectedFolderId: String? = null,
    val historyConversations: List<ConversationSummaryUi> = emptyList(),
    /** 回收站成员，按删除时间由新到旧；正常历史列表不含它们。 */
    val trashConversations: List<ConversationSummaryUi> = emptyList(),
)

/**
 * 调度台系统会话的稳定 id。侧栏里它是系统级条目，而非普通用户会话：
 * 身份只认这个固定 id（老档案额外兼容助手身份），不依赖标题或置顶标记。
 */
const val DISPATCH_CONSOLE_CONVERSATION_ID: String = "dispatch-console"

@Immutable
data class ConversationFolderUi(
    val id: String,
    val name: String,
    val sortIndex: Int = 0,
)

@Immutable
data class ConversationSummaryUi(
    val id: String,
    /** 真实会话标题：自动标题、手动重命名、Agent 工具改名与导出/备份都以此为准。 */
    val title: String,
    /**
     * 展示用标题：侧栏等「会话标题展示位」读这个字段。
     * 普通会话与 [title] 相同；调度台固定显示「调度台」，但底层 [title] 仍是真实标题。
     */
    val displayTitle: String = title,
    val preview: String,
    val timeLabel: String,
    val updatedAtMillis: Long = 0L,
    val createdAtMillis: Long = 0L,
    val mode: ConversationModeUi,
    val isPinned: Boolean = false,
    val isActiveRun: Boolean = false,
    val hasCompletionMarker: Boolean = false,
    /** 该会话由调度台所建或接续使用中；抽屉行前显示派生标记。 */
    val isDispatchManaged: Boolean = false,
    /** 调度台本体：侧栏中的系统级条目，不可删除、置顶强制成立。 */
    val isDispatchConsole: Boolean = false,
    val folderId: String? = null,
    // Captured with timeLabel; consumers must not classify it using a live clock.
    val isToday: Boolean = false,
)

@Immutable
enum class ConversationModeUi {
    Chat,
    PhoneAgent,
    Terminal,
    Automation,
}

/** Sentinel [ConversationPaneUiState.selectedFolderId] value: show only conversations not filed into any folder. */
const val UNCATEGORIZED_FOLDER_ID: String = "\$uncategorized"

/**
 * null = 「聊天」tab：包含所有对话（含调度台系统条目）；[UNCATEGORIZED_FOLDER_ID] = 未分类：
 * 仅未归入文件夹的普通会话（调度台是系统级条目，不参与分类，不进未分类组）；
 * 其余为具体文件夹 id：仅该文件夹下的对话。
 */
fun List<ConversationSummaryUi>.filterForFolder(selectedFolderId: String?): List<ConversationSummaryUi> =
    when (selectedFolderId) {
        null -> this
        UNCATEGORIZED_FOLDER_ID -> filter { it.folderId.isNullOrBlank() && !it.isDispatchConsole }
        else -> filter { it.folderId == selectedFolderId }
    }

/**
 * 「当前选中会话」的全量查找池。[conversations] 是按 [ConversationPaneUiState.selectedFolderId]
 * 筛选后的可见子集，而选中会话可以不属于当前浏览的文件夹（回到调度台、跨文件夹跳转等）：
 * 顶栏身份判据、标题这类「选中会话是谁」的问题必须查全量，否则查不到会把调度台误判成普通会话。
 * 回收站成员不进池：选中会话恒为未删除状态。
 */
val ConversationPaneUiState.allConversations: List<ConversationSummaryUi>
    get() = historyConversations.ifEmpty { conversations }

/** Always derive from the complete folder list, never from the previous search results. */
internal fun ConversationPaneUiState.searchResults(): List<ConversationSummaryUi> {
    val query = searchQuery.trim()
    if (query.isEmpty()) return conversations
    return conversations.filter {
        it.title.contains(query, ignoreCase = true) ||
            // 调度台显示名固定为「调度台」，只搜真实标题会让改名后的调度台搜不到。
            it.displayTitle.contains(query, ignoreCase = true) ||
            it.preview.contains(query, ignoreCase = true)
    }
}
