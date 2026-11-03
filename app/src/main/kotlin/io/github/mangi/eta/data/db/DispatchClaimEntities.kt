package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 调度债权持久层：决定之后要做的事先落库（dsh Stored work 原则），重启对账的唯一依据。 */
@Entity(
    tableName = "dispatch_claims",
    indices = [Index("conversation_id")],
)
internal data class DispatchClaimEntity(
    @PrimaryKey @ColumnInfo(name = "dispatch_id") val dispatchId: String,
    @ColumnInfo(name = "conversation_id") val conversationId: String,
    val brief: String,
    @ColumnInfo(name = "origin_text") val originText: String,
    /** 武装（起跑）后写入；排队中为 null。 */
    @ColumnInfo(name = "run_id") val runId: String? = null,
    /** queued 排队中 | armed 执行中。 */
    val state: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val STATE_QUEUED = "queued"
        const val STATE_ARMED = "armed"
    }
}
