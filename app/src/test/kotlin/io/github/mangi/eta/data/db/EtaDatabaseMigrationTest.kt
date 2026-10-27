package io.github.mangi.eta.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.github.mangi.eta.data.model.ModelSource
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaDatabaseMigrationTest {
    @Test
    fun migration6To30PreservesDataAndMovesBoundedConversationContext() {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "migration-${UUID.randomUUID()}.db"
        createVersion6Database(context, databaseName)

        val migration16To17WithMcpData = Migration(16, 17) { database ->
            EtaDatabase.MIGRATION_16_17.migrate(database)
            database.execSQL(
                "INSERT INTO mcp_servers (id, name, url, enabled, protocol_mode, " +
                    "authorization_type, tools_json, enabled_tool_names_json, created_at, " +
                    "sort_order, last_refreshed_at, last_protocol_version) VALUES " +
                    "('mcp-1', 'MCP', 'http://127.0.0.1:8787/mcp', 1, 'auto', 'none', " +
                    "'[]', '[]', 1, 0, 2, '2026-07-28')"
            )
        }

        val database = try {
            Room.databaseBuilder(context, EtaDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .addMigrations(
                EtaDatabase.MIGRATION_6_7,
                EtaDatabase.MIGRATION_7_8,
                EtaDatabase.MIGRATION_8_9,
                EtaDatabase.MIGRATION_9_10,
                EtaDatabase.MIGRATION_10_11,
                EtaDatabase.MIGRATION_11_12,
                EtaDatabase.MIGRATION_12_13,
                EtaDatabase.MIGRATION_13_14,
                EtaDatabase.MIGRATION_14_15,
                EtaDatabase.MIGRATION_15_16,
                migration16To17WithMcpData,
                EtaDatabase.MIGRATION_17_18,
                EtaDatabase.MIGRATION_18_19,
                EtaDatabase.MIGRATION_19_20,
                EtaDatabase.MIGRATION_20_21,
                EtaDatabase.MIGRATION_21_22,
                EtaDatabase.MIGRATION_22_23,
                EtaDatabase.MIGRATION_23_24,
                EtaDatabase.MIGRATION_24_25,
                EtaDatabase.MIGRATION_25_26,
                EtaDatabase.MIGRATION_26_27,
                EtaDatabase.MIGRATION_27_28,
                EtaDatabase.MIGRATION_28_29,
                EtaDatabase.MIGRATION_29_30,
                EtaDatabase.MIGRATION_30_31,
                EtaDatabase.MIGRATION_31_32,
                EtaDatabase.MIGRATION_32_33,
                EtaDatabase.MIGRATION_33_34,
                EtaDatabase.MIGRATION_34_35,
                EtaDatabase.MIGRATION_35_36,
                EtaDatabase.MIGRATION_36_37,
            )
            .build()
        } catch (error: Throwable) {
            throw AssertionError("Room 构建失败：${error.message}", error)
        }
        try {
            database.openHelper.writableDatabase
        } catch (error: Throwable) {
            throw AssertionError(
                "Room 打开迁移后的数据库失败：${error}\n${error.stackTraceToString().take(2500)}",
                error,
            )
        }
        try {
            val result = runBlocking(Dispatchers.IO) {
                database.runtimeRunDao().runtimeResults().single()
            }
            val archive = runBlocking(Dispatchers.IO) {
                database.runtimeRunDao().archivedRunHeaders().single()
            }
            val conversations = runBlocking(Dispatchers.IO) {
                database.conversationDao().conversations()
            }
            val retainedCheckpoint = runBlocking(Dispatchers.IO) {
                database.conversationDao().contextCheckpoint("conv-1")
            }
            assertEquals("", retainedCheckpoint?.cloudUsageJson)
            assertEquals(37, database.openHelper.readableDatabase.version)
            assertTrue(conversations.none { it.hasCompletionMarker })
            val oversizedCheckpoint = runBlocking(Dispatchers.IO) {
                database.conversationDao().contextCheckpoint("conv-oversized")
            }
            val clearedLegacyHistory = database.openHelper.readableDatabase
                .query("SELECT history_json FROM conversations WHERE id = 'conv-oversized'")
                .use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0)
                }
            val provider = runBlocking(Dispatchers.IO) {
                database.providerDao().providerById("provider-1")!!.toDomain()
            }
            val migratedMessage = runBlocking(Dispatchers.IO) {
                database.conversationDao().messages().single()
            }
            val inFlightRuns = runBlocking(Dispatchers.IO) {
                database.runtimeRunDao().inFlightRunHeaders()
            }
            val mcpServers = runBlocking(Dispatchers.IO) {
                database.mcpServerDao().servers()
            }

            assertEquals("保留的结果", result.content)
            assertEquals("[]", result.transcriptJson)
            assertEquals(false, result.virtualDeliveryCompleted)
            assertEquals("保留的归档", archive.content)
            assertEquals("[]", archive.transcriptJson)
            assertEquals(false, archive.virtualDeliveryCompleted)
            assertEquals("[]", archive.userImagePreviewsJson)
            assertEquals(
                setOf("conv-1", "conv-enabled", "conv-custom-empty", "conv-oversized"),
                conversations.mapTo(mutableSetOf()) { it.id },
            )
            assertEquals(
                "[{\"role\":\"user\",\"content\":\"保留上下文\"}]",
                retainedCheckpoint?.historyJson,
            )
            assertEquals("[]", oversizedCheckpoint?.historyJson)
            assertEquals("[]", clearedLegacyHistory)
            assertEquals("[]", conversations.first { it.id == "conv-1" }.appliedRuntimeRunIdsJson)
            assertEquals("off", conversations.first { it.id == "conv-1" }.reasoningEffort)
            assertEquals("default", conversations.first { it.id == "conv-enabled" }.reasoningEffort)
            assertEquals("", conversations.first { it.id == "conv-1" }.providerId)
            assertEquals("", conversations.first { it.id == "conv-1" }.modelId)
            assertEquals(null, runBlocking(Dispatchers.IO) { database.conversationDao().state() })
            assertEquals(listOf("built-in", "manual"), provider.models.map { it.modelId })
            assertEquals(false, provider.responsesStripReasoningStatus)
            assertEquals(false, provider.hostedWebSearchEnabled)
            assertEquals(false, migratedMessage.isEdited)
            assertEquals(null, migratedMessage.generatedAtMillis)
            assertEquals(emptyList<RuntimeInFlightRunEntity>(), inFlightRuns)
            assertEquals(listOf("mcp-1"), mcpServers.map { it.id })
            assertEquals(null, mcpServers.single().toolsExpireAt)
            assertEquals(null, provider.models.first().contextWindowOverride)
            assertEquals(null, provider.models.first().reasoningOverride)
            assertEquals(null, provider.models.first().reasoningCapabilitiesOverride)
            assertEquals(
                listOf(ModelSource.CATALOG, ModelSource.MANUAL),
                provider.models.map { it.source },
            )
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migration32To33BoundsOversizedInFlightEventRows() {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "migration-32-33-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(32) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE runtime_inflight_runs (" +
                                    "run_id TEXT NOT NULL PRIMARY KEY, " +
                                    "owner_instance_id TEXT NOT NULL, " +
                                    "handoff_id TEXT NOT NULL, " +
                                    "handoff_source TEXT NOT NULL, " +
                                    "handoff_payload TEXT NOT NULL, " +
                                    "dismiss_entry_surface INTEGER NOT NULL, " +
                                    "created_at INTEGER NOT NULL, " +
                                    "updated_at INTEGER NOT NULL)"
                            )
                            db.execSQL(
                                "CREATE TABLE runtime_inflight_events (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "run_id TEXT NOT NULL, " +
                                    "sort_index INTEGER NOT NULL, " +
                                    "event_json TEXT NOT NULL)"
                            )
                            db.execSQL(
                                "CREATE TABLE runtime_archive_runs (" +
                                    "archive_run_id TEXT NOT NULL PRIMARY KEY)"
                            )
                            db.execSQL(
                                "CREATE TABLE runtime_archive_events (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "archive_run_id TEXT NOT NULL, " +
                                    "sort_index INTEGER NOT NULL, " +
                                    "event_json TEXT NOT NULL)"
                            )
                            db.execSQL(
                                "INSERT INTO runtime_inflight_runs " +
                                    "(run_id, owner_instance_id, handoff_id, handoff_source, " +
                                    "handoff_payload, dismiss_entry_surface, created_at, updated_at) " +
                                    "VALUES ('legacy-run', 'owner', 'handoff', 'test', '{}', 0, 1, 1)"
                            )
                            db.execSQL(
                                "INSERT INTO runtime_inflight_events " +
                                    "(run_id, sort_index, event_json) VALUES (?, 0, ?)",
                                arrayOf<Any>("legacy-run", "x".repeat(70_000)),
                            )
                            db.execSQL("INSERT INTO runtime_archive_runs (archive_run_id) VALUES ('legacy-archive')")
                            db.execSQL(
                                "INSERT INTO runtime_archive_events " +
                                    "(archive_run_id, sort_index, event_json) VALUES (?, 0, ?)",
                                arrayOf<Any>("legacy-archive", "y".repeat(70_000)),
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        try {
            val database = helper.writableDatabase
            EtaDatabase.MIGRATION_32_33.migrate(database)

            val recoveryIncomplete = database.query(
                "SELECT recovery_incomplete FROM runtime_inflight_runs WHERE run_id = 'legacy-run'"
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            val event = database.query(
                "SELECT event_json, length(CAST(event_json AS BLOB)) " +
                    "FROM runtime_inflight_events WHERE run_id = 'legacy-run'"
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0) to cursor.getInt(1)
            }
            val archiveEvent = database.query(
                "SELECT event_json, length(CAST(event_json AS BLOB)) " +
                    "FROM runtime_archive_events WHERE archive_run_id = 'legacy-archive'"
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0) to cursor.getInt(1)
            }

            assertEquals(1, recoveryIncomplete)
            assertEquals("{\"__eta_checkpoint_skipped\":true}", event.first)
            assertTrue(event.second <= 65_536)
            assertEquals("{\"__eta_checkpoint_skipped\":true}", archiveEvent.first)
            assertTrue(archiveEvent.second <= 65_536)
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migration21To22AddsOrKeepsBalanceOptionColumn() {
        val context = RuntimeEnvironment.getApplication() as Context
        listOf(false, true).forEach { alreadyPresent ->
            val databaseName = "migration-21-22-${alreadyPresent}-${UUID.randomUUID()}.db"
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(databaseName)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(21) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                db.execSQL(
                                    "CREATE TABLE model_providers (" +
                                        "id TEXT NOT NULL PRIMARY KEY, " +
                                        "type TEXT NOT NULL, name TEXT NOT NULL, " +
                                        "base_url TEXT NOT NULL, api_key TEXT NOT NULL, " +
                                        "is_enabled INTEGER NOT NULL, is_built_in INTEGER NOT NULL, " +
                                        "sort_order INTEGER NOT NULL, system_prompt TEXT, " +
                                        "custom_headers_json TEXT NOT NULL, custom_body_json TEXT NOT NULL, " +
                                        "created_at INTEGER NOT NULL, endpoint_mode TEXT NOT NULL, " +
                                        "anthropic_version TEXT NOT NULL, " +
                                        "hosted_web_search_enabled INTEGER NOT NULL" +
                                        (if (alreadyPresent) ", balance_option_json TEXT NOT NULL DEFAULT '{}'" else "") +
                                        ")",
                                )
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = Unit
                        },
                    )
                    .build(),
            )
            val database = helper.writableDatabase
            try {
                EtaDatabase.MIGRATION_21_22.migrate(database)
                val info = database.query("PRAGMA table_info(model_providers)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    val defaultIndex = cursor.getColumnIndex("dflt_value")
                    buildMap {
                        while (cursor.moveToNext()) {
                            put(cursor.getString(nameIndex), cursor.getString(defaultIndex))
                        }
                    }
                }
                assertTrue(alreadyPresent.toString(), "balance_option_json" in info)
                assertEquals("'{}'", info.getValue("balance_option_json"))
            } finally {
                helper.close()
            }
        }
    }

    @Test
    fun migration28To30PreservesReceiptsAndDefaults() {
        checkReceiptMigration(28, false, false)
    }

    @Test
    fun bothVersion29VariantsMigrateWithoutLosingReceipts() {
        for ((cloud, delivery) in listOf(true to false, false to true, true to true)) {
            checkReceiptMigration(29, cloud, delivery)
        }
    }

    @Test
    fun migration34To35AddsMediaTablesAndPreservesExistingData() {
        val context = RuntimeEnvironment.getApplication() as Context
        val databaseName = "migration-34-35-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(34) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE conversations (id TEXT NOT NULL, title TEXT NOT NULL, " +
                                    "thinking_enabled INTEGER NOT NULL, history_json TEXT NOT NULL, " +
                                    "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id))"
                            )
                            db.execSQL(
                                "INSERT INTO conversations " +
                                    "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                    "VALUES ('conv-keep', '保留的对话', 0, '[]', 1, 1)"
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        try {
            val database = helper.writableDatabase
            EtaDatabase.MIGRATION_34_35.migrate(database)
            // 幂等：重复执行不得报错（所有 CREATE 均带 IF NOT EXISTS）。
            EtaDatabase.MIGRATION_34_35.migrate(database)

            val tables = database.query("SELECT name FROM sqlite_master WHERE type = 'table'")
                .use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
            assertTrue("media_index 表缺失：$tables", "media_index" in tables)
            assertTrue("media_source 表缺失：$tables", "media_source" in tables)

            val indices = database.query("SELECT name FROM sqlite_master WHERE type = 'index'")
                .use { cursor ->
                    buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
            assertTrue("index_media_index_kind 缺失", "index_media_index_kind" in indices)
            assertTrue("index_media_index_modified_at 缺失", "index_media_index_modified_at" in indices)

            val mediaIndexColumns = database.query("PRAGMA table_info(media_index)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val pkIndex = cursor.getColumnIndex("pk")
                buildMap {
                    while (cursor.moveToNext()) {
                        put(cursor.getString(nameIndex), cursor.getInt(pkIndex))
                    }
                }
            }
            assertEquals(
                setOf(
                    "source_key", "model_id", "vector_dim", "kind", "display_name", "mime",
                    "size_bytes", "modified_at", "content_hash", "duration_ms", "width", "height",
                    "frame_time_ms", "thumb_path", "vector", "indexed_at",
                ),
                mediaIndexColumns.keys,
            )
            // 复合主键 (source_key, model_id, vector_dim) 及其顺序决定 Upsert 语义，必须保持。
            assertEquals(
                mapOf("source_key" to 1, "model_id" to 2, "vector_dim" to 3),
                mediaIndexColumns.filterValues { it > 0 },
            )

            val mediaSourceColumns = database.query("PRAGMA table_info(media_source)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                buildSet { while (cursor.moveToNext()) add(cursor.getString(nameIndex)) }
            }
            assertEquals(
                setOf("id", "type", "uri", "label", "enabled", "last_scan_at", "item_count"),
                mediaSourceColumns,
            )

            // 旧数据不受影响。
            val retained = database.query("SELECT title FROM conversations WHERE id = 'conv-keep'")
                .use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0)
                }
            assertEquals("保留的对话", retained)

            // 新表可写：BLOB 向量与可空列往返正确。
            val payload = byteArrayOf(0x00, 0x3C, 0xFF.toByte())
            database.execSQL(
                "INSERT INTO media_index (source_key, model_id, vector_dim, kind, display_name, mime, " +
                    "size_bytes, modified_at, content_hash, vector, indexed_at) " +
                    "VALUES ('content://media/1', 'model-1', 768, 'image', 'a.png', 'image/png', " +
                    "10, 20, 'hash', ?, 30)",
                arrayOf<Any>(payload),
            )
            database.execSQL(
                "INSERT INTO media_source (id, type, uri, label, enabled, item_count) " +
                    "VALUES ('workspace', 'workspace', '', '终端工作区', 1, 0)"
            )
            val roundTrip = database.query("SELECT vector, duration_ms FROM media_index").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getBlob(0) to cursor.isNull(1)
            }
            assertArrayEquals(payload, roundTrip.first)
            assertTrue(roundTrip.second)
            val source = database.query("SELECT enabled, last_scan_at FROM media_source").use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0) to cursor.isNull(1)
            }
            assertEquals(1, source.first)
            assertTrue(source.second)
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun checkReceiptMigration(version: Int, cloud: Boolean, delivery: Boolean) {
        val context = RuntimeEnvironment.getApplication() as Context
        val name = "receipts-${UUID.randomUUID()}.db"
        createVersion6Database(context, name)
        try {
            val legacy = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                        override fun onCreate(db: SupportSQLiteDatabase) = error("Missing legacy fixture")
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            check(oldVersion == 6)
                            for (migration in listOf(
                                EtaDatabase.MIGRATION_6_7,
                                EtaDatabase.MIGRATION_7_8,
                                EtaDatabase.MIGRATION_8_9,
                                EtaDatabase.MIGRATION_9_10,
                                EtaDatabase.MIGRATION_10_11,
                                EtaDatabase.MIGRATION_11_12,
                                EtaDatabase.MIGRATION_12_13,
                                EtaDatabase.MIGRATION_13_14,
                                EtaDatabase.MIGRATION_14_15,
                                EtaDatabase.MIGRATION_15_16,
                                EtaDatabase.MIGRATION_16_17,
                                EtaDatabase.MIGRATION_17_18,
                                EtaDatabase.MIGRATION_18_19,
                                EtaDatabase.MIGRATION_19_20,
                                EtaDatabase.MIGRATION_20_21,
                                EtaDatabase.MIGRATION_21_22,
                                EtaDatabase.MIGRATION_22_23,
                                EtaDatabase.MIGRATION_23_24,
                                EtaDatabase.MIGRATION_24_25,
                                EtaDatabase.MIGRATION_25_26,
                                EtaDatabase.MIGRATION_26_27,
                                EtaDatabase.MIGRATION_27_28,
                            )) migration.migrate(db)
                        }
                    }).build(),
            )
            try {
                val db = legacy.writableDatabase
                if (cloud) {
                    EtaDatabase.MIGRATION_28_29.migrate(db)
                    db.execSQL("UPDATE conversation_context_checkpoints SET cloud_usage_json = ?", arrayOf<Any>("preserved-cloud"))
                }
                if (delivery) {
                    for (table in listOf("runtime_results", "runtime_archive_runs")) {
                        db.execSQL("ALTER TABLE $table ADD COLUMN virtual_delivery_completed INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("UPDATE $table SET virtual_delivery_completed = 1")
                    }
                }
            } finally {
                legacy.close()
            }
            val database = Room.databaseBuilder(context, EtaDatabase::class.java, name)
                .allowMainThreadQueries()
                .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
                .addMigrations(EtaDatabase.MIGRATION_28_29, EtaDatabase.MIGRATION_29_30,
                    EtaDatabase.MIGRATION_30_31, EtaDatabase.MIGRATION_31_32,
                    EtaDatabase.MIGRATION_32_33, EtaDatabase.MIGRATION_33_34,
                    EtaDatabase.MIGRATION_34_35, EtaDatabase.MIGRATION_35_36,
                    EtaDatabase.MIGRATION_36_37)
                .build()
            try {
                assertEquals(37, database.openHelper.writableDatabase.version)
                runBlocking(Dispatchers.IO) {
                    val result = database.runtimeRunDao().runtimeResults().single()
                    val archive = database.runtimeRunDao().archivedRunHeaders().single()
                    val checkpoint = database.conversationDao().contextCheckpoint("conv-1")
                    assertEquals("保留的结果", result.content)
                    assertEquals("保留的归档", archive.content)
                    assertEquals(delivery, result.virtualDeliveryCompleted)
                    assertEquals(delivery, archive.virtualDeliveryCompleted)
                    assertEquals(if (cloud) "preserved-cloud" else "", checkpoint?.cloudUsageJson)
                }
            } finally {
                database.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun createVersion6Database(
        context: Context,
        databaseName: String,
    ) {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        VERSION_6_SCHEMA.forEach(db::execSQL)
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-enabled', '启用推理', 1, '[]', 4, 4)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-1', '保留的对话', 0, " +
                                "'[{\"role\":\"user\",\"content\":\"保留上下文\"}]', 1, 1)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-bug-empty', '新对话', 0, '[]', 2, 2)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES ('conv-custom-empty', '用户命名', 0, '[]', 3, 3)"
                        )
                        db.execSQL(
                            "INSERT INTO conversations " +
                                "(id, title, thinking_enabled, history_json, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?)",
                            arrayOf<Any>(
                                "conv-oversized",
                                "超长上下文",
                                0,
                                "[\"${"x".repeat(140_000)}\"]",
                                5,
                                5,
                            ),
                        )
                        db.execSQL(
                            "INSERT INTO conversation_state (id, selected_conversation_id) " +
                                "VALUES ('main', 'conv-bug-empty')"
                        )
                        db.execSQL(
                            "INSERT INTO conversation_messages " +
                                "(id, conversation_id, sort_index, type, content, images_json, " +
                                "image_count, tools_json) VALUES " +
                                "('message-1', 'conv-1', 0, 'user', '旧消息', '[]', 0, '[]')"
                        )
                        db.execSQL(
                            "INSERT INTO model_providers " +
                                "(id, type, name, base_url, api_key, is_enabled, is_built_in, sort_order, " +
                                "system_prompt, custom_headers_json, custom_body_json, created_at, endpoint_mode, anthropic_version) " +
                                "VALUES ('provider-1', 'openai_compatible', 'Provider', 'https://example.com/v1', '', 1, 0, 0, " +
                                "NULL, '[]', '[]', 1, 'chat_completions', '2023-06-01')"
                        )
                        db.execSQL(providerModelInsert("built-in-id", "built-in", 1, 0))
                        db.execSQL(providerModelInsert("manual-id", "manual", 0, 1))
                        db.execSQL(providerModelInsert("blank-id", "", 0, 2))
                        db.execSQL(
                            "INSERT INTO runtime_results " +
                                "(run_id, handoff_id, handoff_source, handoff_payload, " +
                                "dismiss_entry_surface, ok, content, error, reasoning_content, created_at) " +
                                "VALUES ('run-1', 'handoff-1', 'test', '{}', 0, 1, '保留的结果', NULL, '', 1)"
                        )
                        db.execSQL(
                            "INSERT INTO runtime_archive_runs " +
                                "(archive_run_id, run_id, handoff_id, handoff_source, handoff_payload, " +
                                "dismiss_entry_surface, ok, content, error, reasoning_content, created_at) " +
                                "VALUES ('archive-1', 'run-1', 'handoff-1', 'test', '{}', 0, 1, " +
                                "'保留的归档', NULL, '', 1)"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }
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
        fun providerModelInsert(id: String, modelId: String, builtIn: Int, sortOrder: Int): String =
            "INSERT INTO provider_models " +
                "(id, provider_id, model_id, display_name, is_enabled, is_built_in, sort_order, owned_by, " +
                "context_window, input_modalities_json, output_modalities_json, attachment, tool_call, reasoning, " +
                "structured_output, supports_temperature, custom_headers_json, custom_body_json, created_at) " +
                "VALUES ('$id', 'provider-1', '$modelId', 'Model', 1, $builtIn, $sortOrder, NULL, NULL, " +
                "'[\"text\"]', '[\"text\"]', NULL, NULL, NULL, NULL, NULL, '[]', '[]', 1)"

        val VERSION_6_SCHEMA = listOf(
            "CREATE TABLE conversations (id TEXT NOT NULL, title TEXT NOT NULL, thinking_enabled INTEGER NOT NULL, history_json TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE conversation_messages (id TEXT NOT NULL, conversation_id TEXT NOT NULL, sort_index INTEGER NOT NULL, type TEXT NOT NULL, content TEXT NOT NULL, images_json TEXT NOT NULL, render_markdown INTEGER, context_tokens INTEGER, input_tokens INTEGER, output_tokens INTEGER, reasoning_tokens INTEGER, cached_tokens INTEGER, elapsed_seconds INTEGER, tool_name TEXT, tool_status TEXT, arguments_summary TEXT, result_summary TEXT, image_count INTEGER NOT NULL, tools_json TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_conversation_messages_conversation_id ON conversation_messages(conversation_id)",
            "CREATE UNIQUE INDEX index_conversation_messages_conversation_id_sort_index ON conversation_messages(conversation_id, sort_index)",
            "CREATE TABLE conversation_state (id TEXT NOT NULL, selected_conversation_id TEXT NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE model_providers (id TEXT NOT NULL, type TEXT NOT NULL, name TEXT NOT NULL, base_url TEXT NOT NULL, api_key TEXT NOT NULL, is_enabled INTEGER NOT NULL, is_built_in INTEGER NOT NULL, sort_order INTEGER NOT NULL, system_prompt TEXT, custom_headers_json TEXT NOT NULL, custom_body_json TEXT NOT NULL, created_at INTEGER NOT NULL, endpoint_mode TEXT NOT NULL, anthropic_version TEXT NOT NULL, PRIMARY KEY(id))",
            "CREATE TABLE provider_models (id TEXT NOT NULL, provider_id TEXT NOT NULL, model_id TEXT NOT NULL, display_name TEXT NOT NULL, is_enabled INTEGER NOT NULL, is_built_in INTEGER NOT NULL, sort_order INTEGER NOT NULL, owned_by TEXT, context_window INTEGER, input_modalities_json TEXT NOT NULL, output_modalities_json TEXT NOT NULL, attachment INTEGER, tool_call INTEGER, reasoning INTEGER, structured_output INTEGER, supports_temperature INTEGER, custom_headers_json TEXT NOT NULL, custom_body_json TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(provider_id) REFERENCES model_providers(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_provider_models_provider_id ON provider_models(provider_id)",
            "CREATE INDEX index_provider_models_provider_id_sort_order ON provider_models(provider_id, sort_order)",
            "CREATE TABLE runtime_results (run_id TEXT NOT NULL, handoff_id TEXT NOT NULL, handoff_source TEXT NOT NULL, handoff_payload TEXT NOT NULL, dismiss_entry_surface INTEGER NOT NULL, ok INTEGER NOT NULL, content TEXT NOT NULL, error TEXT, reasoning_content TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(run_id))",
            "CREATE TABLE runtime_archive_runs (archive_run_id TEXT NOT NULL, run_id TEXT NOT NULL, handoff_id TEXT NOT NULL, handoff_source TEXT NOT NULL, handoff_payload TEXT NOT NULL, dismiss_entry_surface INTEGER NOT NULL, ok INTEGER NOT NULL, content TEXT NOT NULL, error TEXT, reasoning_content TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(archive_run_id))",
            "CREATE TABLE runtime_archive_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, archive_run_id TEXT NOT NULL, sort_index INTEGER NOT NULL, event_json TEXT NOT NULL, FOREIGN KEY(archive_run_id) REFERENCES runtime_archive_runs(archive_run_id) ON UPDATE NO ACTION ON DELETE CASCADE)",
            "CREATE INDEX index_runtime_archive_events_archive_run_id ON runtime_archive_events(archive_run_id)",
            "CREATE UNIQUE INDEX index_runtime_archive_events_archive_run_id_sort_index ON runtime_archive_events(archive_run_id, sort_index)",
            "CREATE TABLE skill_registry (skill_id TEXT NOT NULL, enabled INTEGER NOT NULL, source TEXT NOT NULL, install_state TEXT NOT NULL, PRIMARY KEY(skill_id))",
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES (42, 'bd87dd0053b011246cba304c35316f07')",
        )
    }
}
