package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.mangi.eta.data.model.ReasoningEffort
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "conversations")
internal data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "thinking_enabled") val thinkingEnabled: Boolean,
    @ColumnInfo(name = "reasoning_effort", defaultValue = "'default'")
    val reasoningEffort: String = ReasoningEffort.DEFAULT.wireValue,
    @ColumnInfo(name = "history_json") val historyJson: String = "[]",
    @ColumnInfo(name = "applied_runtime_run_ids_json") val appliedRuntimeRunIdsJson: String = "[]",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "folder_id", defaultValue = "''")
    val folderId: String = "",
    @ColumnInfo(name = "is_pinned", defaultValue = "0")
    val isPinned: Boolean = false,
    @ColumnInfo(name = "has_completion_marker", defaultValue = "0")
    val hasCompletionMarker: Boolean = false,
    @ColumnInfo(name = "provider_id", defaultValue = "''")
    val providerId: String = "",
    @ColumnInfo(name = "model_id", defaultValue = "''")
    val modelId: String = "",
    @ColumnInfo(name = "assistant_id", defaultValue = "''")
    val assistantId: String = "",
    /** 回收站：非空表示已软删除、待恢复或永久清理的时间戳；恢复后回到 null。 */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
    /** 调度台所建的工作会话：值为调度台会话 id；用户手建的会话为 null。 */
    @ColumnInfo(name = "dispatch_parent_id")
    val dispatchParentId: String? = null,
    /**
     * 最近一次被调度台接续派发的时间；非空表示会话处于（或曾经处于）派发管理下。
     * 与 [dispatchParentId] 共同决定抽屉的「派生会话」标记；fork 出的副本两列皆空。
     */
    @ColumnInfo(name = "dispatch_managed_at")
    val dispatchManagedAt: Long? = null,
)

internal data class ConversationMetadata(
    val id: String,
    val title: String,
    @ColumnInfo(name = "thinking_enabled") val thinkingEnabled: Boolean,
    @ColumnInfo(name = "reasoning_effort") val reasoningEffort: String,
    @ColumnInfo(name = "applied_runtime_run_ids_json") val appliedRuntimeRunIdsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "folder_id") val folderId: String = "",
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "has_completion_marker") val hasCompletionMarker: Boolean = false,
    @ColumnInfo(name = "provider_id") val providerId: String = "",
    @ColumnInfo(name = "model_id") val modelId: String = "",
    @ColumnInfo(name = "assistant_id") val assistantId: String = "",
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Serializable
@Entity(tableName = "conversation_folders")
internal data class ConversationFolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "conversation_context_checkpoints",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class ConversationContextCheckpointEntity(
    @PrimaryKey
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "history_json") val historyJson: String,
    @ColumnInfo(name = "cloud_usage_json", defaultValue = "''") val cloudUsageJson: String = "",
)

@Serializable
@Entity(tableName = "conversation_state")
internal data class ConversationStateEntity(
    @PrimaryKey val id: String = SINGLETON_ID,
    @ColumnInfo(name = "selected_conversation_id") val selectedConversationId: String,
) {
    companion object {
        const val SINGLETON_ID = "main"
    }
}

@Serializable
@Entity(
    tableName = "conversation_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversation_id"),
        Index(value = ["conversation_id", "sort_index"], unique = true),
    ],
)
internal data class ConversationMessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    @ColumnInfo(name = "sort_index") val sortIndex: Int,
    val type: String,
    val content: String,
    @ColumnInfo(name = "images_json") val imagesJson: String = "[]",
    @ColumnInfo(name = "is_edited", defaultValue = "0") val isEdited: Boolean = false,
    @ColumnInfo(name = "render_markdown") val renderMarkdown: Boolean? = null,
    @ColumnInfo(name = "context_tokens") val contextTokens: Int? = null,
    @ColumnInfo(name = "input_tokens") val inputTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "reasoning_tokens") val reasoningTokens: Int? = null,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Int? = null,
    @ColumnInfo(name = "elapsed_seconds") val elapsedSeconds: Int? = null,
    @ColumnInfo(name = "tool_name") val toolName: String? = null,
    @ColumnInfo(name = "tool_status") val toolStatus: String? = null,
    @ColumnInfo(name = "arguments_summary") val argumentsSummary: String? = null,
    @ColumnInfo(name = "result_summary") val resultSummary: String? = null,
    /** 工具卡片展开后展示的完整参数/结果（已脱敏、已截断）；敏感工具不写入。 */
    @ColumnInfo(name = "arguments_detail") val argumentsDetail: String? = null,
    @ColumnInfo(name = "result_detail") val resultDetail: String? = null,
    /** 工具当次读到的图片小预览：data URL 的 JSON 数组。 */
    @ColumnInfo(name = "media_json") val mediaJson: String = "[]",
    @ColumnInfo(name = "image_count") val imageCount: Int = 0,
    @ColumnInfo(name = "tools_json") val toolsJson: String = "[]",
    @ColumnInfo(name = "generated_at_millis") val generatedAtMillis: Long? = null,
    /**
     * 该行 assistant 回执对应的前缀缓存归因（[io.github.mangi.eta.data.model.UsageDiagnosticsJson] 编码）。
     * 空串表示旧数据或本地估算；只含枚举值与 token 数，可随会话导出。
     */
    @ColumnInfo(name = "usage_diagnostics_json", defaultValue = "''")
    val usageDiagnosticsJson: String = "",
)
