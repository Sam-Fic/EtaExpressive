package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * AI 工具创建的日程与闹钟：由 [io.github.mangi.eta.agent.tool.AgentScheduleTools]
 * 读写，到点触发走 AgentScheduleAlertReceiver，重启后由 AgentScheduleBootReceiver 重排。
 * 两个表都由工具全权管理，不与系统日历 / OEM 时钟混用，避免双数据源。
 */
@Entity(tableName = "agent_schedules")
internal data class AgentScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String,
    @ColumnInfo(name = "start_at") val startAt: Long,
    @ColumnInfo(name = "end_at") val endAt: Long?,
    @ColumnInfo(name = "reminder_minutes") val reminderMinutes: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "agent_alarms")
internal data class AgentAlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    val label: String,
    /** 逗号分隔的 1（周一）到 7（周日）；空串表示仅下一次。 */
    @ColumnInfo(name = "repeat_days") val repeatDays: String,
    val enabled: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Dao
internal interface AgentScheduleDao {
    @Insert
    fun insert(entity: AgentScheduleEntity): Long

    @Update
    fun update(entity: AgentScheduleEntity)

    @Query("SELECT * FROM agent_schedules WHERE id = :id")
    fun byId(id: Long): AgentScheduleEntity?

    @Query("SELECT * FROM agent_schedules WHERE start_at >= :from ORDER BY start_at ASC LIMIT :limit")
    fun upcoming(from: Long, limit: Int): List<AgentScheduleEntity>

    @Query("SELECT * FROM agent_schedules ORDER BY start_at DESC LIMIT :limit")
    fun recent(limit: Int): List<AgentScheduleEntity>

    @Query("SELECT * FROM agent_schedules WHERE start_at >= :from ORDER BY start_at ASC")
    fun upcomingFlow(from: Long): Flow<List<AgentScheduleEntity>>

    @Query("DELETE FROM agent_schedules WHERE id = :id")
    fun delete(id: Long): Int
}

@Dao
internal interface AgentAlarmDao {
    @Insert
    fun insert(entity: AgentAlarmEntity): Long

    @Update
    fun update(entity: AgentAlarmEntity)

    @Query("SELECT * FROM agent_alarms WHERE id = :id")
    fun byId(id: Long): AgentAlarmEntity?

    @Query("SELECT * FROM agent_alarms ORDER BY hour ASC, minute ASC, id ASC")
    fun all(): List<AgentAlarmEntity>

    @Query("SELECT * FROM agent_alarms WHERE enabled = 1")
    fun enabled(): List<AgentAlarmEntity>

    @Query("DELETE FROM agent_alarms WHERE id = :id")
    fun delete(id: Long): Int
}
