package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface DispatchClaimDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(claim: DispatchClaimEntity)

    @Query("DELETE FROM dispatch_claims WHERE dispatch_id = :dispatchId")
    suspend fun delete(dispatchId: String)

    @Query("DELETE FROM dispatch_claims WHERE conversation_id = :conversationId")
    suspend fun deleteByConversation(conversationId: String)

    @Query("SELECT * FROM dispatch_claims ORDER BY created_at ASC")
    suspend fun listAll(): List<DispatchClaimEntity>

    @Query(
        "UPDATE dispatch_claims SET run_id = :runId, state = 'armed' WHERE dispatch_id = :dispatchId"
    )
    suspend fun markArmed(dispatchId: String, runId: String)
}
