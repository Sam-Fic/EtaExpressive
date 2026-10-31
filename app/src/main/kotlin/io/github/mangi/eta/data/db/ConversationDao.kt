package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction

@Dao
internal interface ConversationDao {
    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, created_at, updated_at, folder_id, is_pinned, has_completion_marker, " +
            "provider_id, model_id, assistant_id, deleted_at " +
            "FROM conversations ORDER BY updated_at DESC"
    )
    suspend fun conversations(): List<ConversationMetadata>

    // 启动兜底选中的会话不取回收站里的。
    @Query(
        "SELECT id, title, thinking_enabled, reasoning_effort, " +
            "applied_runtime_run_ids_json, created_at, updated_at, folder_id, is_pinned, has_completion_marker, " +
            "provider_id, model_id, assistant_id, deleted_at " +
            "FROM conversations WHERE deleted_at IS NULL ORDER BY updated_at DESC LIMIT :limit OFFSET :offset"
    )
    suspend fun conversationsPage(limit: Int, offset: Int): List<ConversationMetadata>

    @Query("SELECT id, title, thinking_enabled, reasoning_effort, applied_runtime_run_ids_json, " +
        "created_at, updated_at, folder_id, is_pinned, has_completion_marker, provider_id, model_id, assistant_id, deleted_at " +
        "FROM conversations WHERE id = :id")
    suspend fun conversationMetadata(id: String): ConversationMetadata?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissingConversations(rows: List<ConversationEntity>)

    // Partial UPDATE, never REPLACE: REPLACE would cascade-delete unloaded child rows.
    @Update(entity = ConversationEntity::class)
    suspend fun updateConversationMetadata(rows: List<ConversationMetadata>)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Query("SELECT id, conversation_id, type, substr(content, 1, 2048) AS content, " +
        "tool_name, tool_status, NULL AS arguments_summary, NULL AS result_summary " +
        "FROM conversation_messages WHERE conversation_id = :id ORDER BY sort_index DESC LIMIT 1")
    suspend fun conversationPreview(id: String): ConversationTextRow?

    @Query("SELECT id, conversation_id, type, content, tool_name, tool_status, arguments_summary, result_summary " +
        "FROM conversation_messages WHERE conversation_id = :id " +
        "AND type IN ('user', 'assistant', 'thinking', 'tool') ORDER BY sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun searchablePage(id: String, limit: Int, offset: Int): List<ConversationTextRow>

    @Query("SELECT DISTINCT conversation_id FROM conversation_messages " +
        "WHERE type = 'question' AND conversation_id IN " +
        "(SELECT id FROM conversations WHERE deleted_at IS NULL)")
    suspend fun questionConversationIds(): List<String>

    @Query("SELECT * FROM conversation_messages ORDER BY conversation_id ASC, sort_index ASC")
    suspend fun messages(): List<ConversationMessageEntity>

    @Query("SELECT * FROM conversations ORDER BY updated_at ASC")
    suspend fun conversationEntities(): List<ConversationEntity>

    @Query("SELECT * FROM conversation_context_checkpoints ORDER BY conversation_id ASC")
    suspend fun contextCheckpoints(): List<ConversationContextCheckpointEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun conversationEntity(id: String): ConversationEntity?

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId ORDER BY sort_index ASC")
    suspend fun messagesForConversation(conversationId: String): List<ConversationMessageEntity>

    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId ORDER BY sort_index ASC LIMIT :limit OFFSET :offset")
    suspend fun messagesPage(conversationId: String, limit: Int, offset: Int): List<ConversationMessageEntity>

    // 展示分页：倒序取一批再翻正，加载窗口只碰 sort_index 边界之前的行。
    @Query("SELECT * FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index < :before " +
        "ORDER BY sort_index DESC LIMIT :limit")
    suspend fun messageRowsBefore(conversationId: String, before: Int, limit: Int): List<ConversationMessageEntity>

    @Transaction
    suspend fun messagesBefore(conversationId: String, before: Int, limit: Int): List<ConversationMessageEntity> =
        messageRowsBefore(conversationId, before, limit).asReversed()

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index < :before)")
    suspend fun hasMessagesBefore(conversationId: String, before: Int): Boolean

    @Query("SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index < :before")
    suspend fun messageCountBefore(conversationId: String, before: Int): Int

    // 保存边界：只清掉已加载窗口覆盖到的行（>= fromSortIndex），未加载的更早记录原地保留。
    // fromSortIndex = 0 时等价于 deleteMessagesForConversation，即历史全量重写路径。
    @Query("DELETE FROM conversation_messages WHERE conversation_id = :conversationId AND sort_index >= :fromSortIndex")
    suspend fun deleteMessagesFrom(conversationId: String, fromSortIndex: Int)

    // Message IDs are primary keys for the whole table, not just one conversation.
    @Query("SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE id = :id)")
    suspend fun messageIdExists(id: String): Boolean

    @Query("UPDATE conversation_messages SET id = :newId " +
        "WHERE conversation_id = :conversationId AND id = :oldId AND id <> :newId " +
        "AND NOT EXISTS(SELECT 1 FROM conversation_messages WHERE id = :newId)")
    suspend fun renameMessageId(conversationId: String, oldId: String, newId: String): Int

    @Query("SELECT COUNT(*) FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun messageCount(conversationId: String): Int

    @Query("SELECT COUNT(*) FROM conversation_messages")
    suspend fun storedMessageCount(): Int

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun conversationCount(): Int

    /**
     * Visible chat bubbles only; thinking/tool rows are not messages.
     *
     * Empty assistant rows are excluded: a round whose text lands in another block still
     * gets a content-free `...-usage` carrier row so its bill has somewhere to live. On
     * this device those carriers were 1980 of 2550 counted rows, inflating the reported
     * message count roughly fourfold. They are not bubbles the user ever saw.
     */
    @Query(
        "SELECT COUNT(*) FROM conversation_messages WHERE type IN ('user', 'assistant') " +
            "AND (type = 'user' OR (content IS NOT NULL AND TRIM(content) <> ''))"
    )
    suspend fun totalMessageCount(): Int

    @Query(
        "SELECT conversation_id, type, input_tokens, output_tokens, cached_tokens " +
            "FROM conversation_messages WHERE type IN ('assistant', 'context_compacted')"
    )
    suspend fun usageContentRows(): List<UsageContentRow>

    @Query(
        "SELECT date(created_at / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count " +
            "FROM conversations WHERE created_at >= :startAt GROUP BY day"
    )
    suspend fun conversationCountPerDay(startAt: Long): List<ConversationDayCount>

    @Query("SELECT * FROM conversation_context_checkpoints WHERE conversation_id = :conversationId")
    suspend fun contextCheckpoint(conversationId: String): ConversationContextCheckpointEntity?

    @Query("SELECT * FROM conversation_state WHERE id = :id")
    suspend fun state(id: String = ConversationStateEntity.SINGLETON_ID): ConversationStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<ConversationMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContextCheckpoints(checkpoints: List<ConversationContextCheckpointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertState(state: ConversationStateEntity)

    @Query("SELECT * FROM conversation_folders ORDER BY sort_index ASC, created_at ASC")
    suspend fun folders(): List<ConversationFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolders(folders: List<ConversationFolderEntity>)

    @Query("DELETE FROM conversation_folders")
    suspend fun deleteFolders()

    @Transaction
    suspend fun replaceFolders(folders: List<ConversationFolderEntity>) {
        deleteFolders()
        if (folders.isNotEmpty()) {
            insertFolders(folders)
        }
    }

    @Query("DELETE FROM conversations")
    suspend fun deleteConversations()

    @Query("DELETE FROM conversation_messages")
    suspend fun deleteMessages()

    @Query("DELETE FROM conversation_context_checkpoints")
    suspend fun deleteContextCheckpoints()

    @Query("DELETE FROM conversation_state")
    suspend fun deleteState()

    @Query("DELETE FROM conversation_messages WHERE conversation_id = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    @Query("DELETE FROM conversation_context_checkpoints WHERE conversation_id = :conversationId")
    suspend fun deleteContextCheckpoint(conversationId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertImportedConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertImportedMessages(messages: List<ConversationMessageEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertImportedCheckpoint(checkpoint: ConversationContextCheckpointEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteImportedConversation(id: String)

    @Transaction
    suspend fun importAsNewConversation(
        conversation: ConversationEntity,
        messages: List<ConversationMessageEntity>,
        contextCheckpoint: ConversationContextCheckpointEntity?,
    ) {
        insertImportedConversation(conversation)
        if (messages.isNotEmpty()) insertImportedMessages(messages)
        contextCheckpoint?.let { insertImportedCheckpoint(it) }
    }

    @Transaction
    suspend fun upsertConversation(
        conversation: ConversationEntity,
        messages: List<ConversationMessageEntity>,
        contextCheckpoint: ConversationContextCheckpointEntity?,
    ) {
        deleteMessagesForConversation(conversation.id)
        deleteContextCheckpoint(conversation.id)
        insertConversations(listOf(conversation))
        if (messages.isNotEmpty()) insertMessages(messages)
        contextCheckpoint?.let { insertContextCheckpoints(listOf(it)) }
    }

    @Transaction
    suspend fun replaceAll(
        conversations: List<ConversationEntity>,
        messages: List<ConversationMessageEntity>,
        contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
        state: ConversationStateEntity?,
    ) {
        deleteMessages()
        deleteContextCheckpoints()
        deleteConversations()
        deleteState()
        insertConversations(conversations)
        insertContextCheckpoints(contextCheckpoints)
        insertMessages(messages)
        state?.let { insertState(it) }
    }
}


internal data class UsageContentRow(
    val type: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String = "",
    @ColumnInfo(name = "input_tokens") val inputTokens: Int? = null,
    @ColumnInfo(name = "output_tokens") val outputTokens: Int? = null,
    @ColumnInfo(name = "cached_tokens") val cachedTokens: Int? = null,
)

internal data class ConversationDayCount(
    val day: String,
    val count: Int,
)

/** Text-only projection: searching/previewing a chat never decodes its image JSON or history. */
internal data class ConversationTextRow(
    val id: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val type: String,
    val content: String,
    @ColumnInfo(name = "tool_name") val toolName: String?,
    @ColumnInfo(name = "tool_status") val toolStatus: String?,
    @ColumnInfo(name = "arguments_summary") val argumentsSummary: String?,
    @ColumnInfo(name = "result_summary") val resultSummary: String?,
) {
    fun asMessageEntity() = ConversationMessageEntity(
        id = id, conversationId = conversationId, sortIndex = 0, type = type,
        content = content, toolName = toolName, toolStatus = toolStatus,
        argumentsSummary = argumentsSummary, resultSummary = resultSummary,
    )
}
