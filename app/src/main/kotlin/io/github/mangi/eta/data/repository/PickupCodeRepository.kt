// 取餐码/取件码记录存储。
// 组织形态跟随 NotificationHistoryRepository（原生 SQLiteOpenHelper 独立库，不进
// EtaDatabase 主迁移链）：取餐码是通知派生的低频个人记录，生命周期与保留策略
// 独立于主业务库。全部方法为同步阻塞实现，调用方负责切到 Dispatchers.IO。
// 记录变更与提醒通知联动（参考澎湃记 cancelNotification(orderId)）：提醒通知常驻到
// 记录完成/删除为止，所以 markCompleted/delete/update/clearAll 在此统一撤卡
// （id = code.hashCode()，原生与超级岛共用同一槽位，撤不存在的 id 是无害 no-op）。

package io.github.mangi.eta.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.mangi.eta.agent.pickup.PickupCodeNotifier
import java.util.UUID

/** 记录来源。 */
enum class PickupCodeSource {
    /** 助手预设 chip / Agent 工具主动识别。 */
    AGENT,

    /** 通知监听自动拦截。 */
    NOTIFICATION,

    /** 用户在管理页手动录入。 */
    MANUAL,
}

/** 一条取餐码/取件码记录。 */
data class PickupCodeRecord(
    val id: String,
    val code: String,
    /** 餐食 / 饮品 / 快递。 */
    val orderType: String,
    val brandName: String?,
    val pickupLocation: String?,
    val source: PickupCodeSource,
    val sourcePackage: String?,
    val fullText: String?,
    val createdAt: Long,
    val isCompleted: Boolean,
    val completedAt: Long?,
)

/** 仅在 Eta 内部保存取餐码记录；未完成记录常驻，已完成记录限期清理。 */
internal class PickupCodeRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = Database(appContext)

    /** 插入记录，返回记录 id。调用方负责去重（[findUnfinishedByCode]）。 */
    fun insert(
        code: String,
        orderType: String,
        brandName: String?,
        pickupLocation: String?,
        source: PickupCodeSource,
        sourcePackage: String?,
        fullText: String?,
        createdAt: Long = System.currentTimeMillis(),
    ): String {
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id)
            put("code", code.take(MAX_CODE_CHARS))
            put("order_type", orderType.take(MAX_TYPE_CHARS))
            put("brand_name", brandName.bounded())
            put("pickup_location", pickupLocation.bounded())
            put("source", source.name)
            put("source_package", sourcePackage?.take(MAX_PACKAGE_CHARS))
            put("full_text", fullText.bounded())
            put("created_at", createdAt)
            put("is_completed", 0)
        }
        database.writableDatabase.transaction {
            insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            cleanupLocked()
        }
        return id
    }

    /** 同码未完成记录查询（通知去重用）。 */
    fun findUnfinishedByCode(code: String): PickupCodeRecord? =
        database.readableDatabase.query(
            TABLE,
            COLUMNS,
            "code=? AND is_completed=0",
            arrayOf(code),
            null,
            null,
            "created_at DESC",
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    /** 按 id 查单条记录（变更路径联动撤卡前取码值用）。 */
    private fun findById(id: String): PickupCodeRecord? =
        database.readableDatabase.query(
            TABLE,
            COLUMNS,
            "id=?",
            arrayOf(id),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    /** 未完成列表（新→旧）。 */
    fun pending(limit: Int = DEFAULT_LIST_LIMIT): List<PickupCodeRecord> =
        query("is_completed=0", limit)

    /** 已完成列表（完成时间新→旧）。 */
    fun completed(limit: Int = DEFAULT_LIST_LIMIT): List<PickupCodeRecord> =
        query("is_completed=1", limit, order = "completed_at DESC")

    /** 关键词查询（码值/品牌/地点 LIKE），供 Agent 工具使用。 */
    fun search(query: String, limit: Int = DEFAULT_LIST_LIMIT): List<PickupCodeRecord> {
        if (query.isBlank()) return pending(limit)
        val pattern = "%${query.escapeLike()}%"
        return database.readableDatabase.query(
            TABLE,
            COLUMNS,
            "(code LIKE ? ESCAPE '\\' OR brand_name LIKE ? ESCAPE '\\' OR pickup_location LIKE ? ESCAPE '\\')",
            arrayOf(pattern, pattern, pattern),
            null,
            null,
            "is_completed ASC, created_at DESC",
            limit.coerceAtMost(MAX_RECORDS).toString(),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toRecord()) }
        }
    }

    /** 更新记录的可编辑字段（码值/类型/品牌/地点），来源与时间戳不动；返回是否命中。 */
    fun update(
        id: String,
        code: String,
        orderType: String,
        brandName: String?,
        pickupLocation: String?,
    ): Boolean {
        val oldCode = findById(id)?.code
        val values = ContentValues().apply {
            put("code", code.take(MAX_CODE_CHARS))
            put("order_type", orderType.take(MAX_TYPE_CHARS))
            put("brand_name", brandName.bounded())
            put("pickup_location", pickupLocation.bounded())
        }
        val changed = database.writableDatabase.update(TABLE, values, "id=?", arrayOf(id)) > 0
        // 码值被改 = 原提醒内容作废，撤掉旧码的常驻提醒（新码不重发，岛面不再展示旧码）。
        if (changed) oldCode?.let { PickupCodeNotifier.cancelCode(appContext, it) }
        return changed
    }

    /** 标记完成；已完成记录返回 false。 */
    fun markCompleted(id: String): Boolean {
        val record = findById(id) ?: return false
        val values = ContentValues().apply {
            put("is_completed", 1)
            put("completed_at", System.currentTimeMillis())
        }
        val changed = database.writableDatabase.update(
            TABLE, values, "id=? AND is_completed=0", arrayOf(id),
        ) > 0
        if (changed) PickupCodeNotifier.cancelCode(appContext, record.code)
        return changed
    }

    fun delete(id: String): Boolean {
        val record = findById(id)
        val deleted = database.writableDatabase.delete(TABLE, "id=?", arrayOf(id)) > 0
        // 删未完成记录时撤掉它的常驻提醒；已完成记录的提醒早已撤过，重复撤无害。
        if (deleted) record?.code?.let { PickupCodeNotifier.cancelCode(appContext, it) }
        return deleted
    }

    /** 一键清空已完成记录（待取记录不动）；返回删除的条数。 */
    fun clearCompleted(): Int =
        database.writableDatabase.delete(TABLE, "is_completed=1", null)

    fun clearAll(): Boolean {
        // 清库前把未完成记录的常驻提醒一并撤掉，避免留幽灵岛。
        pending(limit = MAX_RECORDS).forEach { PickupCodeNotifier.cancelCode(appContext, it.code) }
        return database.writableDatabase.delete(TABLE, null, null) >= 0
    }

    /**
     * 清理：已完成超 [RETENTION_DAYS] 的记录删除，总数超出 [MAX_RECORDS] 时按
     * 完成时间优先、创建时间最旧优先淘汰。在写入事务内调用。
     */
    private fun SQLiteDatabase.cleanupLocked() {
        val now = System.currentTimeMillis()
        delete(TABLE, "is_completed=1 AND completed_at<?", arrayOf((now - RETENTION_MS).toString()))
        execSQL(
            "DELETE FROM $TABLE WHERE id NOT IN " +
                "(SELECT id FROM $TABLE ORDER BY is_completed ASC, " +
                "CASE WHEN is_completed=1 THEN completed_at ELSE created_at END DESC LIMIT $MAX_RECORDS)",
        )
    }

    private fun query(
        clause: String,
        limit: Int,
        order: String = "created_at DESC",
    ): List<PickupCodeRecord> = database.readableDatabase.query(
        TABLE,
        COLUMNS,
        clause,
        null,
        null,
        null,
        order,
        limit.coerceAtMost(MAX_RECORDS).toString(),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toRecord()) }
    }

    private fun android.database.Cursor.toRecord(): PickupCodeRecord {
        val sourceName = getString(5)
        return PickupCodeRecord(
            id = getString(0),
            code = getString(1),
            orderType = getString(2),
            brandName = if (isNull(3)) null else getString(3),
            pickupLocation = if (isNull(4)) null else getString(4),
            source = runCatching { PickupCodeSource.valueOf(sourceName) }
                .getOrDefault(PickupCodeSource.AGENT),
            sourcePackage = if (isNull(6)) null else getString(6),
            fullText = if (isNull(7)) null else getString(7),
            createdAt = getLong(8),
            isCompleted = getInt(9) != 0,
            completedAt = if (isNull(10)) null else getLong(10),
        )
    }

    private fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun String?.bounded(): String? = this?.take(MAX_FIELD_CHARS)

    private fun String.escapeLike(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private class Database(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "id TEXT PRIMARY KEY NOT NULL," +
                    "code TEXT NOT NULL," +
                    "order_type TEXT NOT NULL," +
                    "brand_name TEXT,pickup_location TEXT," +
                    "source TEXT NOT NULL,source_package TEXT,full_text TEXT," +
                    "created_at INTEGER NOT NULL," +
                    "is_completed INTEGER NOT NULL DEFAULT 0,completed_at INTEGER)",
            )
            db.execSQL("CREATE INDEX pickup_codes_code ON $TABLE(code)")
            db.execSQL("CREATE INDEX pickup_codes_created_at ON $TABLE(created_at DESC)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private companion object {
        const val DATABASE_NAME = "eta_pickup_codes.db"
        const val TABLE = "pickup_codes"
        val COLUMNS = arrayOf(
            "id", "code", "order_type", "brand_name", "pickup_location",
            "source", "source_package", "full_text", "created_at", "is_completed", "completed_at",
        )
        const val MAX_RECORDS = 200
        const val RETENTION_DAYS = 7
        const val HOUR_MS = 60L * 60 * 1_000
        const val RETENTION_MS = RETENTION_DAYS * 24L * HOUR_MS
        const val DEFAULT_LIST_LIMIT = 50
        const val MAX_FIELD_CHARS = 4_000
        const val MAX_CODE_CHARS = 64
        const val MAX_TYPE_CHARS = 16
        const val MAX_PACKAGE_CHARS = 255
    }
}
