package io.github.mangi.eta.data.db

import android.app.Application
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * identity hash 容错取证：整文件替换/备份恢复后 room_master_table 指纹漂移，旧实现
 * 直接 IllegalStateException 整库打不开（「对话和供应商全读不到」）。容错实现应删
 * master table 走 Room 全量 schema 校验；结构相符则恢复，结构不符仍抛明确错误。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class EtaDatabaseIdentityRecoveryTest {

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
    }

    @Test
    fun `stale identity hash is recovered by revalidating the schema`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // 先正常建一次库写入正确 hash，再直改 master table 模拟「eta.db 被异版本构建整文件替换」。
        EtaDatabase.get(context)
        EtaDatabase.closeForTests()
        val file = context.getDatabasePath("eta.db")
        assertTrue("正常建库后 eta.db 文件应存在", file.isFile)
        corruptIdentityHash(file.path, "deadbeefdeadbeefdeadbeefdeadbeefdead")

        // 旧实现在这里抛 IllegalStateException 整库打不开；容错后应重建 master table 并通过全量校验。
        val database = EtaDatabase.get(context)
        val recovered = database.openHelper.readableDatabase
            .query("SELECT identity_hash FROM room_master_table LIMIT 1")
            .use { cursor -> cursor.moveToFirst(); cursor.getString(0) }
        assertNotEquals("恢复后指纹不得再是污染值", "deadbeefdeadbeefdeadbeefdeadbeefdead", recovered)
        assertTrue("恢复后指纹应为编译期生成值", recovered.isNotBlank())
    }

    @Test
    fun `structurally mismatched schema still fails loudly after master table drop`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // 种一个 version=41 但没有任何业务表、指纹又是错的库：删 master table 后
        // Room 全量校验发现表全缺，必须抛明确错误而不是静默改库或静默放行。
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("eta.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(ETA_SCHEMA_VERSION) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT NOT NULL)")
                        db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, 'deadbeefdeadbeefdeadbeefdeadbeefdead')")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase
        helper.close()

        try {
            EtaDatabase.get(context)
            fail("结构不符的库在删 master table 后仍应抛明确错误")
        } catch (_: IllegalStateException) {
            // 预期路径：Room 全量校验失败。
        }
        // 校验失败后 master table 不得被重新写回（不能装作恢复成功）。
        val masterTableCount = queryScalarLong(
            context.getDatabasePath("eta.db").path,
            "SELECT COUNT(*) FROM sqlite_master WHERE name = 'room_master_table'",
        )
        assertEquals(0L, masterTableCount)
    }

    private fun corruptIdentityHash(path: String, polluted: String) {
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE room_master_table SET identity_hash = '$polluted'")
        }
    }

    /** 裸 SQL 取单值：显式关 cursor 与连接，不依赖 use 嵌套的类型推断。 */
    private fun queryScalarLong(path: String, sql: String): Long {
        val db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val cursor: Cursor = db.rawQuery(sql, null)
            try {
                cursor.moveToFirst()
                return cursor.getLong(0)
            } finally {
                cursor.close()
            }
        } finally {
            db.close()
        }
    }
}
