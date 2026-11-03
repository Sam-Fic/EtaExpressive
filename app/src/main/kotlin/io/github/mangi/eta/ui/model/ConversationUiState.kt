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

@Immutable
data class ConversationFolderUi(
    val id: String,
    val name: String,
    val sortIndex: Int = 0,
)

@Immutable
data class ConversationSummaryUi(
    val id: String,
    val title: String,
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
 * null = 「聊天」tab：包含所有对话；[UNCATEGORIZED_FOLDER_ID] = 未分类：仅未归入文件夹的对话；
 * 其余为具体文件夹 id：仅该文件夹下的对话。
 */
fun List<ConversationSummaryUi>.filterForFolder(selectedFolderId: String?): List<ConversationSummaryUi> =
    when (selectedFolderId) {
        null -> this
        UNCATEGORIZED_FOLDER_ID -> filter { it.folderId.isNullOrBlank() }
        else -> filter { it.folderId == selectedFolderId }
    }

/** Always derive from the complete folder list, never from the previous search results. */
internal fun ConversationPaneUiState.searchResults(): List<ConversationSummaryUi> {
    val query = searchQuery.trim()
    if (query.isEmpty()) return conversations
    return conversations.filter {
        it.title.contains(query, ignoreCase = true) ||
            it.preview.contains(query, ignoreCase = true)
    }
}
