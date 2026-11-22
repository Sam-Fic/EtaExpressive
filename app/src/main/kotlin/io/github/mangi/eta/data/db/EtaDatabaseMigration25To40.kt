package io.github.mangi.eta.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 25 → 40 整库重建迁移。
 *
 * 老设备（3.4.2 及更早）的库停在 user_version = 25，逐级迁移链覆盖不到后续
 * 表结构变化，Room 校验会抛 IllegalStateException(Migration didn't properly handle ...)。
 * 这里按 schema 40 的目标 DDL 逐表重建：列取交集并用 COALESCE 兜默认值
 * （旧库里 `model_id`、`media_json` 允许 NULL，新 schema 要求 NOT NULL），
 * 新表才有的列直接吃默认值，索引一并重建。
 */
internal val MIGRATION_25_40 = object : Migration(25, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = OFF")
        TARGET_TABLES.forEach { table ->
            val oldColumns = readColumns(db, table.name)
            val newName = table.name + "__migrating"
            db.execSQL(table.createSqlFor(newName))
            if (oldColumns.isNotEmpty()) {
                val shared = table.columns.filter { it.first in oldColumns }
                val insertCols = shared.map { it.first } + table.columns.filter { it.first !in oldColumns }.map { it.first }
                val selectExpr = shared.map { (c, d) -> "COALESCE(`" + c + "`, " + d + ")" } +
                    table.columns.filter { it.first !in oldColumns }.map { it.second }
                if (insertCols.isNotEmpty()) {
                    val colList = insertCols.joinToString(", ") { "`" + it + "`" }
                    db.execSQL("INSERT INTO `" + newName + "` (" + colList + ") SELECT " + selectExpr.joinToString(", ") + " FROM `" + table.name + "`")
                }
                db.execSQL("DROP TABLE `" + table.name + "`")
            }
            db.execSQL("ALTER TABLE `" + newName + "` RENAME TO `" + table.name + "`")
            table.indexSqls.forEach { db.execSQL(it) }
        }
        db.execSQL("PRAGMA foreign_keys = ON")
    }
}

private class TargetTable(
    val name: String,
    /** 列名 → 兜底值字面量，顺序与 DDL 一致。 */
    val columns: List<Pair<String, String>>,
    private val createSqlTemplate: String,
    val indexSqls: List<String>,
) {
    fun createSqlFor(table: String): String = createSqlTemplate.replace("\${TABLE_NAME}", table)
}

private fun readColumns(db: SupportSQLiteDatabase, table: String): List<String> {
    val columns = mutableListOf<String>()
    db.query("PRAGMA table_info(`" + table + "`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) { if (nameIndex >= 0) columns += cursor.getString(nameIndex) }
    }
    return columns
}

private val TARGET_TABLES: List<TargetTable> = listOf(
    TargetTable(
        name = "conversations",
        columns = listOf("id" to "''", "title" to "''", "thinking_enabled" to "0", "reasoning_effort" to "'default'", "history_json" to "''", "applied_runtime_run_ids_json" to "''", "created_at" to "0", "updated_at" to "0", "folder_id" to "''", "is_pinned" to "0", "has_completion_marker" to "0", "provider_id" to "''", "model_id" to "''", "assistant_id" to "''", "deleted_at" to "0", "dispatch_parent_id" to "''", "dispatch_managed_at" to "0"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `thinking_enabled` INTEGER NOT NULL, `reasoning_effort` TEXT NOT NULL DEFAULT 'default', `history_json` TEXT NOT NULL, `applied_runtime_run_ids_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `folder_id` TEXT NOT NULL DEFAULT '', `is_pinned` INTEGER NOT NULL DEFAULT 0, `has_completion_marker` INTEGER NOT NULL DEFAULT 0, `provider_id` TEXT NOT NULL DEFAULT '', `model_id` TEXT NOT NULL DEFAULT '', `assistant_id` TEXT NOT NULL DEFAULT '', `deleted_at` INTEGER, `dispatch_parent_id` TEXT, `dispatch_managed_at` INTEGER, PRIMARY KEY(`id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "conversation_context_checkpoints",
        columns = listOf("conversation_id" to "''", "history_json" to "''", "cloud_usage_json" to "''"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`conversation_id` TEXT NOT NULL, `history_json` TEXT NOT NULL, `cloud_usage_json` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`conversation_id`), FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "conversation_messages",
        columns = listOf("id" to "''", "conversation_id" to "''", "sort_index" to "0", "type" to "''", "content" to "''", "images_json" to "''", "is_edited" to "0", "render_markdown" to "0", "context_tokens" to "0", "input_tokens" to "0", "output_tokens" to "0", "reasoning_tokens" to "0", "cached_tokens" to "0", "elapsed_seconds" to "0", "tool_name" to "''", "tool_status" to "''", "arguments_summary" to "''", "result_summary" to "''", "arguments_detail" to "''", "result_detail" to "''", "media_json" to "''", "image_count" to "0", "tools_json" to "''", "generated_at_millis" to "0"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `sort_index` INTEGER NOT NULL, `type` TEXT NOT NULL, `content` TEXT NOT NULL, `images_json` TEXT NOT NULL, `is_edited` INTEGER NOT NULL DEFAULT 0, `render_markdown` INTEGER, `context_tokens` INTEGER, `input_tokens` INTEGER, `output_tokens` INTEGER, `reasoning_tokens` INTEGER, `cached_tokens` INTEGER, `elapsed_seconds` INTEGER, `tool_name` TEXT, `tool_status` TEXT, `arguments_summary` TEXT, `result_summary` TEXT, `arguments_detail` TEXT, `result_detail` TEXT, `media_json` TEXT NOT NULL, `image_count` INTEGER NOT NULL, `tools_json` TEXT NOT NULL, `generated_at_millis` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        indexSqls = listOf("CREATE INDEX IF NOT EXISTS `index_conversation_messages_conversation_id` ON `conversation_messages` (`conversation_id`)", "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversation_messages_conversation_id_sort_index` ON `conversation_messages` (`conversation_id`, `sort_index`)"),
    ),
    TargetTable(
        name = "conversation_state",
        columns = listOf("id" to "''", "selected_conversation_id" to "''"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `selected_conversation_id` TEXT NOT NULL, PRIMARY KEY(`id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "conversation_folders",
        columns = listOf("id" to "''", "name" to "''", "sort_index" to "0", "created_at" to "0"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `sort_index` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "model_providers",
        columns = listOf("id" to "''", "type" to "''", "name" to "''", "base_url" to "''", "api_key" to "''", "is_enabled" to "0", "is_built_in" to "0", "sort_order" to "0", "system_prompt" to "''", "custom_headers_json" to "''", "custom_body_json" to "''", "created_at" to "0", "endpoint_mode" to "''", "hosted_web_search_enabled" to "0", "responses_strip_reasoning_status" to "0", "anthropic_version" to "''", "balance_option_json" to "'{}'", "auth_mode" to "'api_key'", "session_gateway_json" to "''"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `base_url` TEXT NOT NULL, `api_key` TEXT NOT NULL, `is_enabled` INTEGER NOT NULL, `is_built_in` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `system_prompt` TEXT, `custom_headers_json` TEXT NOT NULL, `custom_body_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `endpoint_mode` TEXT NOT NULL, `hosted_web_search_enabled` INTEGER NOT NULL DEFAULT 0, `responses_strip_reasoning_status` INTEGER NOT NULL DEFAULT 0, `anthropic_version` TEXT NOT NULL, `balance_option_json` TEXT NOT NULL DEFAULT '{}', `auth_mode` TEXT NOT NULL DEFAULT 'api_key', `session_gateway_json` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "provider_models",
        columns = listOf("id" to "''", "provider_id" to "''", "model_id" to "''", "display_name" to "''", "is_enabled" to "0", "is_built_in" to "0", "sort_order" to "0", "owned_by" to "''", "context_window" to "0", "context_window_override" to "0", "input_modalities_json" to "''", "output_modalities_json" to "''", "attachment" to "0", "vision_override" to "0", "tool_call" to "0", "reasoning" to "0", "reasoning_capabilities_json" to "'null'", "reasoning_override" to "0", "reasoning_capabilities_override_json" to "'null'", "preferred_reasoning_effort" to "''", "structured_output" to "0", "supports_temperature" to "0", "custom_headers_json" to "''", "custom_body_json" to "''", "source" to "''", "created_at" to "0"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `provider_id` TEXT NOT NULL, `model_id` TEXT NOT NULL, `display_name` TEXT NOT NULL, `is_enabled` INTEGER NOT NULL, `is_built_in` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `owned_by` TEXT, `context_window` INTEGER, `context_window_override` INTEGER, `input_modalities_json` TEXT NOT NULL, `output_modalities_json` TEXT NOT NULL, `attachment` INTEGER, `vision_override` INTEGER, `tool_call` INTEGER, `reasoning` INTEGER, `reasoning_capabilities_json` TEXT NOT NULL DEFAULT 'null', `reasoning_override` INTEGER, `reasoning_capabilities_override_json` TEXT NOT NULL DEFAULT 'null', `preferred_reasoning_effort` TEXT, `structured_output` INTEGER, `supports_temperature` INTEGER, `custom_headers_json` TEXT NOT NULL, `custom_body_json` TEXT NOT NULL, `source` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`provider_id`) REFERENCES `model_providers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        indexSqls = listOf("CREATE INDEX IF NOT EXISTS `index_provider_models_provider_id` ON `provider_models` (`provider_id`)", "CREATE INDEX IF NOT EXISTS `index_provider_models_provider_id_sort_order` ON `provider_models` (`provider_id`, `sort_order`)"),
    ),
    TargetTable(
        name = "runtime_results",
        columns = listOf("run_id" to "''", "handoff_id" to "''", "handoff_source" to "''", "handoff_payload" to "''", "dismiss_entry_surface" to "0", "ok" to "0", "content" to "''", "error" to "''", "reasoning_content" to "''", "transcript_json" to "''", "created_at" to "0", "virtual_delivery_completed" to "0"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`run_id` TEXT NOT NULL, `handoff_id` TEXT NOT NULL, `handoff_source` TEXT NOT NULL, `handoff_payload` TEXT NOT NULL, `dismiss_entry_surface` INTEGER NOT NULL, `ok` INTEGER NOT NULL, `content` TEXT NOT NULL, `error` TEXT, `reasoning_content` TEXT NOT NULL, `transcript_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `virtual_delivery_completed` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`run_id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "runtime_archive_runs",
        columns = listOf("archive_run_id" to "''", "run_id" to "''", "handoff_id" to "''", "handoff_source" to "''", "handoff_payload" to "''", "dismiss_entry_surface" to "0", "ok" to "0", "content" to "''", "error" to "''", "reasoning_content" to "''", "transcript_json" to "''", "user_image_previews_json" to "''", "created_at" to "0", "virtual_delivery_completed" to "0"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`archive_run_id` TEXT NOT NULL, `run_id` TEXT NOT NULL, `handoff_id` TEXT NOT NULL, `handoff_source` TEXT NOT NULL, `handoff_payload` TEXT NOT NULL, `dismiss_entry_surface` INTEGER NOT NULL, `ok` INTEGER NOT NULL, `content` TEXT NOT NULL, `error` TEXT, `reasoning_content` TEXT NOT NULL, `transcript_json` TEXT NOT NULL, `user_image_previews_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `virtual_delivery_completed` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`archive_run_id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "runtime_archive_events",
        columns = listOf("id" to "0", "archive_run_id" to "''", "sort_index" to "0", "event_json" to "''"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `archive_run_id` TEXT NOT NULL, `sort_index` INTEGER NOT NULL, `event_json` TEXT NOT NULL, FOREIGN KEY(`archive_run_id`) REFERENCES `runtime_archive_runs`(`archive_run_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        indexSqls = listOf("CREATE INDEX IF NOT EXISTS `index_runtime_archive_events_archive_run_id` ON `runtime_archive_events` (`archive_run_id`)", "CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_archive_events_archive_run_id_sort_index` ON `runtime_archive_events` (`archive_run_id`, `sort_index`)"),
    ),
    TargetTable(
        name = "runtime_inflight_runs",
        columns = listOf("run_id" to "''", "owner_instance_id" to "''", "handoff_id" to "''", "handoff_source" to "''", "handoff_payload" to "''", "dismiss_entry_surface" to "0", "created_at" to "0", "updated_at" to "0", "recovery_incomplete" to "0"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`run_id` TEXT NOT NULL, `owner_instance_id` TEXT NOT NULL, `handoff_id` TEXT NOT NULL, `handoff_source` TEXT NOT NULL, `handoff_payload` TEXT NOT NULL, `dismiss_entry_surface` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `recovery_incomplete` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`run_id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "runtime_inflight_events",
        columns = listOf("id" to "0", "run_id" to "''", "sort_index" to "0", "event_json" to "''"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `run_id` TEXT NOT NULL, `sort_index` INTEGER NOT NULL, `event_json` TEXT NOT NULL, FOREIGN KEY(`run_id`) REFERENCES `runtime_inflight_runs`(`run_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        indexSqls = listOf("CREATE INDEX IF NOT EXISTS `index_runtime_inflight_events_run_id` ON `runtime_inflight_events` (`run_id`)", "CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_inflight_events_run_id_sort_index` ON `runtime_inflight_events` (`run_id`, `sort_index`)"),
    ),
    TargetTable(
        name = "skill_registry",
        columns = listOf("skill_id" to "''", "enabled" to "0", "source" to "''", "install_state" to "''"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`skill_id` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `source` TEXT NOT NULL, `install_state` TEXT NOT NULL, PRIMARY KEY(`skill_id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "mcp_servers",
        columns = listOf("id" to "''", "name" to "''", "url" to "''", "enabled" to "0", "protocol_mode" to "''", "authorization_type" to "''", "tools_json" to "''", "enabled_tool_names_json" to "''", "created_at" to "0", "sort_order" to "0", "last_refreshed_at" to "0", "last_protocol_version" to "''", "tools_expire_at" to "0"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `protocol_mode` TEXT NOT NULL, `authorization_type` TEXT NOT NULL, `tools_json` TEXT NOT NULL, `enabled_tool_names_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `last_refreshed_at` INTEGER, `last_protocol_version` TEXT, `tools_expire_at` INTEGER, PRIMARY KEY(`id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "media_index",
        columns = listOf("source_key" to "''", "model_id" to "''", "vector_dim" to "0", "kind" to "''", "display_name" to "''", "mime" to "''", "size_bytes" to "0", "modified_at" to "0", "content_hash" to "''", "duration_ms" to "0", "width" to "0", "height" to "0", "frame_time_ms" to "0", "thumb_path" to "''", "vector" to "''", "indexed_at" to "0"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`source_key` TEXT NOT NULL, `model_id` TEXT NOT NULL, `vector_dim` INTEGER NOT NULL, `kind` TEXT NOT NULL, `display_name` TEXT NOT NULL, `mime` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `modified_at` INTEGER NOT NULL, `content_hash` TEXT NOT NULL, `duration_ms` INTEGER, `width` INTEGER, `height` INTEGER, `frame_time_ms` INTEGER, `thumb_path` TEXT, `vector` BLOB NOT NULL, `indexed_at` INTEGER NOT NULL, PRIMARY KEY(`source_key`, `model_id`, `vector_dim`))",
        indexSqls = listOf("CREATE INDEX IF NOT EXISTS `index_media_index_kind` ON `media_index` (`kind`)", "CREATE INDEX IF NOT EXISTS `index_media_index_modified_at` ON `media_index` (`modified_at`)"),
    ),
    TargetTable(
        name = "media_source",
        columns = listOf("id" to "''", "type" to "''", "uri" to "''", "label" to "''", "enabled" to "0", "last_scan_at" to "0", "item_count" to "0"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `uri` TEXT NOT NULL, `label` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `last_scan_at` INTEGER, `item_count` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "dispatch_claims",
        columns = listOf("dispatch_id" to "''", "conversation_id" to "''", "brief" to "''", "origin_text" to "''", "run_id" to "''", "state" to "''", "created_at" to "0"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}` (`dispatch_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `brief` TEXT NOT NULL, `origin_text` TEXT NOT NULL, `run_id` TEXT, `state` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`dispatch_id`))",
        indexSqls = listOf("CREATE INDEX IF NOT EXISTS `index_dispatch_claims_conversation_id` ON `dispatch_claims` (`conversation_id`)"),
    ),
)
