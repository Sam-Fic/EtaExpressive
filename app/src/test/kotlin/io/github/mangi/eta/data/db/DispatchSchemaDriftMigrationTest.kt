package io.github.mangi.eta.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v39 结构漂移的复现与守门测试。
 *
 * 现场：设备上那份 eta.db 的 user_version 已经是 39，但它是「调度功能半成品」构建落库的——
 * conversations 只有 dispatch_parent_id、dispatch_claims 还没建，Room 把那个残缺结构记成了
 * v39 的 identity hash。最终版补齐列与表却没推版本号，同版本号 hash 不符时 Room 既不迁移也不
 * 清库，冷启动直接抛 "Room cannot verify the data integrity"，进程死在 EtaApp 创建阶段（点开即闪退）。
 *
 * 种子库用的是从真机（b95ffa0c）逐字导出的 DDL，含 ALTER 追加导致的列顺序与 media_json 遗留默认值，
 * 因此这里跑的是设备真实结构，不是理想化剧本。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DispatchSchemaDriftMigrationTest {
    @Test
    fun staleVersion39FromDeviceUpgradesTo40AndKeepsData() {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "stale-v39-${UUID.randomUUID()}.db"
        seed(context, name, version = 39)

        // 修复前：种子库停在残缺 v39 现场（真机上就是这个 hash），Room 才会拒绝打开。
        assertEquals(
            "种子库必须停在残缺的 v39 指纹，否则本测试失去意义",
            STALE_IDENTITY_HASH,
            seededIdentityHash(context, name, version = 39),
        )

        val database = openWith(context, name, EtaDatabase.MIGRATION_39_40, EtaDatabase.MIGRATION_40_41)
        try {
            assertEquals(ETA_SCHEMA_VERSION, database.openHelper.readableDatabase.version)
            assertEquals(expectedIdentityHash(context), identityHash(database))

            // 补出来的列与表必须真的可用：Room 的 SELECT * 与实体 insert 都会踩到。
            assertTrue(tableHasColumn(database, "conversations", "dispatch_managed_at"))
            runBlocking(Dispatchers.IO) {
                database.dispatchClaimDao().upsert(
                    DispatchClaimEntity(
                        dispatchId = "dispatch-1",
                        conversationId = "conv-keep",
                        brief = "派发的活",
                        originText = "原话",
                        state = DispatchClaimEntity.STATE_QUEUED,
                        createdAt = 7,
                    ),
                )
                assertEquals(listOf("dispatch-1"), database.dispatchClaimDao().listAll().map { it.dispatchId })
            }

            // 数据一字未丢。
            val conversations = runBlocking(Dispatchers.IO) { database.conversationDao().conversations() }
            assertEquals(listOf("conv-keep"), conversations.map { it.id })
            assertEquals(
                "保留的消息",
                singleCellValue(database, "SELECT content FROM conversation_messages WHERE id = 'msg-keep'"),
            )
            assertEquals(
                "[]",
                singleCellValue(database, "SELECT media_json FROM conversation_messages WHERE id = 'msg-keep'"),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun repairMigrationIsIdempotentForDevicesAlreadyAtTheRepairedSchema() {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "repair-twice-${UUID.randomUUID()}.db"
        seed(context, name, version = 39)
        val first = openWith(context, name, EtaDatabase.MIGRATION_39_40, EtaDatabase.MIGRATION_40_41)
        first.openHelper.writableDatabase
        first.close()

        // 已修好的设备再走一遍 39→40：模拟另一条升级路径，缺列判断必须让它成为空操作，
        // 否则 SQLite 的 duplicate column name 会当场炸掉冷启动。
        seedAlreadyRepairedAtVersion39(context, name)
        val second = openWith(context, name, EtaDatabase.MIGRATION_39_40, EtaDatabase.MIGRATION_40_41)
        try {
            assertEquals(ETA_SCHEMA_VERSION, second.openHelper.readableDatabase.version)
            assertEquals(expectedIdentityHash(context), identityHash(second))
            assertTrue(tableHasColumn(second, "conversations", "dispatch_managed_at"))
        } finally {
            second.close()
        }
    }

    @Test
    fun version38ChainEndsAtTheRepairedSchema() {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "v38-chain-${UUID.randomUUID()}.db"
        // 38 的真实形态：连 dispatch_parent_id 都还没有。
        seed(context, name, version = 38, stripDispatchParentColumn = true)

        val database = openWith(context, name, EtaDatabase.MIGRATION_38_39, EtaDatabase.MIGRATION_39_40, EtaDatabase.MIGRATION_40_41)
        try {
            assertEquals(ETA_SCHEMA_VERSION, database.openHelper.readableDatabase.version)
            assertEquals(expectedIdentityHash(context), identityHash(database))
            assertTrue(tableHasColumn(database, "conversations", "dispatch_parent_id"))
            assertTrue(tableHasColumn(database, "conversations", "dispatch_managed_at"))
            assertEquals(
                "派发的活",
                runBlocking(Dispatchers.IO) {
                    database.dispatchClaimDao().upsert(
                        DispatchClaimEntity(
                            dispatchId = "dispatch-1",
                            conversationId = "conv-keep",
                            brief = "派发的活",
                            originText = "原话",
                            state = DispatchClaimEntity.STATE_ARMED,
                            createdAt = 7,
                        ),
                    )
                    database.dispatchClaimDao().listAll().single().brief
                },
            )
        } finally {
            database.close()
        }
    }

    /**
     * 「半成品 v38」重试现场：迁移中途失败时 user_version 仍停在 38，已经落下的列不会回滚，
     * 下次冷启动会把同一段 ALTER 再跑一遍。裸 ALTER 在这种情况下撞 duplicate column name，
     * 把一次本可恢复的失败钉成崩溃循环。种子库用的就是设备那份真实 DDL（有 dispatch_parent_id、
     * 没有 dispatch_managed_at、没有 dispatch_claims），只是版本号盖成 38。
     */
    @Test
    fun halfAppliedVersion38RetriesWithoutDuplicateColumnCrash() {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "half-v38-${UUID.randomUUID()}.db"
        seed(context, name, version = 38)

        val database = openWith(context, name, EtaDatabase.MIGRATION_38_39, EtaDatabase.MIGRATION_39_40, EtaDatabase.MIGRATION_40_41)
        try {
            assertEquals(ETA_SCHEMA_VERSION, database.openHelper.readableDatabase.version)
            assertEquals(expectedIdentityHash(context), identityHash(database))
            assertTrue(tableHasColumn(database, "conversations", "dispatch_parent_id"))
            assertTrue(tableHasColumn(database, "conversations", "dispatch_managed_at"))
            assertTrue(tableExists(database, "dispatch_claims"))
            assertEquals(
                "保留的会话",
                singleCellValue(database, "SELECT title FROM conversations WHERE id = 'conv-keep'"),
            )
            runBlocking(Dispatchers.IO) {
                database.dispatchClaimDao().upsert(
                    DispatchClaimEntity(
                        dispatchId = "dispatch-1",
                        conversationId = "conv-keep",
                        brief = "派发的活",
                        originText = "原话",
                        state = DispatchClaimEntity.STATE_QUEUED,
                        createdAt = 7,
                    ),
                )
                assertEquals(1, database.dispatchClaimDao().listAll().size)
            }
        } finally {
            database.close()
        }
    }

    /**
     * 走生产入口 [EtaDatabase.get] 校验迁移策略收紧后的两条性质：
     * 1) 链必须完整——少注册一条，v38 就不在允许的破坏式重建集合里，这里直接打不开；
     * 2) 维护区间内绝不丢数据——数据能原样读出，即证明它没走「清库重建」这条静默后路。
     * 用生产入口而不是测试自建 builder，才拿得到 fallbackToDestructiveMigrationFrom 的配置。
     */
    @Test
    fun productionBuilderMigratesVersion38WithoutWipingAnything() {
        val context = RuntimeEnvironment.getApplication() as Context
        // 生产入口的实例可能已被先前的用例建好并握着旧文件句柄：它指着的是那个已删除的 inode，
        // 于是同名新库里塞多少数据它都看不见。本仓库其余测试也在 @Before 里先关单例。
        EtaDatabase.closeForTests()
        purgeProductionDatabase(context)
        seed(context, PRODUCTION_DB_NAME, version = 38, stripDispatchParentColumn = true)
        // 先自证种子状态：若这里就不是 38 + 1 行，那「数据没了」与 Room 无关。
        val seeded = readSeededState(context, PRODUCTION_DB_NAME, version = 38)
        assertEquals("种子库 user_version", 38, seeded.first)
        assertEquals("种子库 conversations 行数", 1L, seeded.second)

        var opened: EtaDatabase? = null
        try {
            opened = EtaDatabase.get(context).also { it.openHelper.writableDatabase }
            assertEquals(ETA_SCHEMA_VERSION, opened.openHelper.readableDatabase.version)
            assertEquals(expectedIdentityHash(context), identityHash(opened))
            assertTrue(tableHasColumn(opened, "conversations", "dispatch_parent_id"))
            assertTrue(tableHasColumn(opened, "conversations", "dispatch_managed_at"))
            assertTrue(tableExists(opened, "dispatch_claims"))
            assertEquals(
                "保留的会话",
                singleCellValue(opened, "SELECT title FROM conversations WHERE id = 'conv-keep'"),
            )
            assertEquals(
                "保留的消息",
                singleCellValue(opened, "SELECT content FROM conversation_messages WHERE id = 'msg-keep'"),
            )
        } finally {
            opened?.close()
            EtaDatabase.closeForTests()
            purgeProductionDatabase(context)
        }
    }

    /** 1–5 从未写过升级路径（迁移链从 6 起），这类上古库只能重建，不能崩在启动。 */
    @Test
    fun ancientVersion5DatabaseIsRebuiltInsteadOfCrashLooping() {
        val context = RuntimeEnvironment.getApplication() as Context
        EtaDatabase.closeForTests()
        purgeProductionDatabase(context)
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(PRODUCTION_DB_NAME)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE legacy_notes (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT)")
                        db.execSQL("INSERT INTO legacy_notes (body) VALUES ('上古数据')")
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit

                    // 同一个 Robolectric worker 里别的测试类也会经 EtaDatabase 打开 eta.db。
                    // 那条连接不走单例、closeForTests() 关不到；删文件后它仍可能把 v40 写回来，
                    // 框架默认的 onDowngrade 会直接抛 "Can't downgrade database from 40 to 5"，
                    // 让本用例随机变红。种子阶段只关心「造出干净的 v5 库」，这里直接重建。
                    override fun onDowngrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) {
                        val tables = mutableListOf<String>()
                        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
                            .use { cursor ->
                                while (cursor.moveToNext()) tables += cursor.getString(0)
                            }
                        tables.forEach { db.execSQL("DROP TABLE IF EXISTS `${it}`") }
                        onCreate(db)
                    }
                },
            )
            .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).also { it.writableDatabase }.close()

        var opened: EtaDatabase? = null
        try {
            opened = EtaDatabase.get(context).also { it.openHelper.writableDatabase }
            assertEquals(ETA_SCHEMA_VERSION, opened.openHelper.readableDatabase.version)
            assertEquals(expectedIdentityHash(context), identityHash(opened))
            assertFalse(tableExists(opened, "legacy_notes"))
            assertTrue(tableExists(opened, "dispatch_claims"))
        } finally {
            opened?.close()
            EtaDatabase.closeForTests()
            purgeProductionDatabase(context)
        }
    }

    /**
     * Robolectric 的应用私有目录在同一测试类里是复用的，而生产入口的库名固定是 eta.db：
     * 不清干净的话，上一个用例留在 40 的库会被这次当成「降级到 38」直接拒掉。
     */
    private fun purgeProductionDatabase(context: Context) {
        val db = context.getDatabasePath(PRODUCTION_DB_NAME)
        // 先走 Context.deleteDatabase 断开同名连接再清文件：只 unlink 主文件时，别的测试类
        // 留下的 SQLite 连接仍持有 fd，WAL checkpoint 会把旧版本写回同一个路径。
        context.deleteDatabase(PRODUCTION_DB_NAME)
        listOf(db, File("${'$'}{db.path}-wal"), File("${'$'}{db.path}-shm"), File("${'$'}{db.path}-journal"))
            .forEach { it.delete() }
    }

    /** 当前代码编译出来的期望 schema 指纹：与全新安装一致，实体再漂移而版本号不动时本组测试即失败。 */
    private fun expectedIdentityHash(context: Context): String {
        val database = Room.databaseBuilder(context, EtaDatabase::class.java, "oracle-${UUID.randomUUID()}.db")
            .allowMainThreadQueries()
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()
        try {
            return identityHash(database)
        } finally {
            database.close()
        }
    }

    private fun identityHash(database: EtaDatabase): String =
        singleCellValue(database, "SELECT identity_hash FROM room_master_table LIMIT 1")

    private fun singleCellValue(database: EtaDatabase, query: String): String =
        database.openHelper.readableDatabase.query(query).use { cursor ->
            check(cursor.moveToFirst()) { "查询无结果：$query" }
            cursor.getString(0)
        }

    private fun tableHasColumn(database: EtaDatabase, table: String, column: String): Boolean =
        database.openHelper.readableDatabase.query("PRAGMA table_info($table)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            generateSequence { if (cursor.moveToNext()) cursor else null }
                .any { it.getString(nameIndex) == column }
        }

    /** 用框架 helper 直接读库内指纹：目标版本与已存版本相同，不会触发任何升降级。 */
    private fun seededIdentityHash(context: Context, name: String, version: Int): String {        val helper = frameworkHelper(context, name, version)
        val hash = helper.readableDatabase
            .query("SELECT identity_hash FROM room_master_table LIMIT 1")
            .use { cursor ->
                check(cursor.moveToFirst()) { "种子库里没有 room_master_table 指纹" }
                cursor.getString(0)
            }
        helper.close()
        return hash
    }

    /** 不经 Room，直接用框架 helper 读回 (user_version, conversations 行数)。 */
    private fun readSeededState(context: Context, name: String, version: Int): Pair<Int, Long> {
        val helper = frameworkHelper(context, name, version)
        try {
            val db = helper.readableDatabase
            val rows = db.query("SELECT COUNT(*) FROM conversations").use { cursor ->
                check(cursor.moveToFirst()) { "种子库里没有 conversations 表" }
                cursor.getLong(0)
            }
            return db.version to rows
        } finally {
            helper.close()
        }
    }

    private fun tableExists(database: EtaDatabase, table: String): Boolean =
        database.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$table'")
            .use { it.moveToFirst() }

    private fun openWith(
        context: Context,
        name: String,
        vararg migrations: Migration,
    ): EtaDatabase =
        try {
            Room.databaseBuilder(context, EtaDatabase::class.java, name)
                .allowMainThreadQueries()
                .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
                .addMigrations(*migrations)
                .build()
                .also { it.openHelper.writableDatabase }
        } catch (error: Throwable) {
            throw AssertionError("Room 打开失败：${error.message}", error)
        }

    private fun frameworkHelper(context: Context, name: String, version: Int): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

                        // 显式吞掉降级：这个 helper 还要用来把已升到 40 的库重新盖回 39，
                        // 依赖 Callback 默认把 onDowngrade 转发给 onUpgrade 太隐晦。
                        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )

    /** 把已修好的库重新盖回 version 39 + 残缺 hash，用于验证迁移的可重复执行。 */
    private fun seedAlreadyRepairedAtVersion39(context: Context, name: String) {
        val helper = frameworkHelper(context, name, version = 39)
        val db = helper.writableDatabase
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, '$STALE_IDENTITY_HASH')")
        helper.close()
    }

    private fun seed(
        context: Context,
        name: String,
        version: Int,
        stripDispatchParentColumn: Boolean = false,
    ) {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        DEVICE_V39_SCHEMA
                            .asSequence()
                            .filterNot { it.startsWith("CREATE TABLE android_metadata") }
                            .map { if (stripDispatchParentColumn) it.replace(", dispatch_parent_id TEXT", "") else it }
                            .forEach(db::execSQL)
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                                "VALUES (42, '$STALE_IDENTITY_HASH')",
                        )
                        db.execSQL(
                            "INSERT INTO conversations (id, title, thinking_enabled, history_json, " +
                                "applied_runtime_run_ids_json, created_at, updated_at) " +
                                "VALUES ('conv-keep', '保留的会话', 0, '[]', '[]', 1, 2)",
                        )
                        db.execSQL(
                            "INSERT INTO conversation_messages (id, conversation_id, sort_index, type, " +
                                "content, images_json, image_count, tools_json) " +
                                "VALUES ('msg-keep', 'conv-keep', 0, 'user', '保留的消息', '[]', 0, '[]')",
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                },
            )
            .build()
        FrameworkSQLiteOpenHelperFactory()
            .create(configuration)
            .also { helper ->
                helper.writableDatabase
                helper.close()
            }
    }

    private companion object {
        /** 真机 user_version=39 时记录的 hash：残缺结构，与当前代码期望的 28aa8ffd… 不一致。 */
        const val STALE_IDENTITY_HASH = "9a4c19ff55cb624e0c9a5bf7c5250992"

        /** [EtaDatabase.get] 里硬编码的文件名；改它等于改生产代码，这里跟着一起改。 */
        const val PRODUCTION_DB_NAME = "eta.db"

        val DEVICE_V39_SCHEMA: List<String> = loadResource("/migration/device-v39-schema.sql")

        /** 逐字加载导出的 DDL，android_metadata 交给框架自己建。 */
        fun loadResource(path: String): List<String> {
            val stream = checkNotNull(
                DispatchSchemaDriftMigrationTest::class.java.getResourceAsStream(path),
            ) { "缺少测试夹具：$path" }
            return stream.bufferedReader().use { reader ->
                buildList {
                    val pending = StringBuilder()
                    reader.forEachLine { line ->
                        val text = line.trim()
                        if (text.isEmpty() || text.startsWith("--")) return@forEachLine
                        pending.append(if (pending.isEmpty()) text else " $text")
                        if (text.endsWith(";")) {
                            add(pending.toString().trim().removeSuffix(";"))
                            pending.setLength(0)
                        }
                    }
                    if (pending.isNotBlank()) add(pending.toString().trim().removeSuffix(";"))
                }
            }
        }
    }
}
