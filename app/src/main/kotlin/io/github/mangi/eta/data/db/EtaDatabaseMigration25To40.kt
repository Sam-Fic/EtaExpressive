package io.github.mangi.eta.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 25 → 40 的整库重建迁移。
 *
 * 本机（以及任何从 3.4.2 之前升上来的设备）的库停在 user_version = 25，
 * 后续逐级迁移链覆盖不到 conversations 等表的列变化，Room 校验会直接抛
 * IllegalStateException(Migration didn't properly handle ...)。
 *
 * 这里不逐级补 15 条迁移，而是拿 schema 40 的目标 DDL 逐表重建：
 * 目标列与旧列取交集原样拷贝，新列吃 DDL 里的默认值，索引一并重建。
 */
internal val MIGRATION_25_40 = object : Migration(25, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA foreign_keys = OFF")
        TARGET_TABLES.forEach { table ->
            val oldColumns = readColumns(db, table.name)
            if (oldColumns.isEmpty()) {
                db.execSQL(table.createSql)
            } else {
                val shared = table.columns.filter { it in oldColumns }
                val newName = table.name + "__migrating"
                db.execSQL(table.createSqlFor(newName))
                if (shared.isNotEmpty()) {
                    val colList = shared.joinToString(", ") { "`" + it + "`" }
                    db.execSQL("INSERT INTO `" + newName + "` (" + colList + ") SELECT " + colList + " FROM `" + table.name + "`")
                }
                db.execSQL("DROP TABLE `" + table.name + "`")
                db.execSQL("ALTER TABLE `" + newName + "` RENAME TO `" + table.name + "`")
            }
            table.indexSqls.forEach { db.execSQL(it) }
        }
        db.execSQL("PRAGMA foreign_keys = ON")
    }
}

private class TargetTable(
    val name: String,
    val columns: List<String>,
    private val createSqlTemplate: String,
    val indexSqls: List<String>,
) {
    val createSql: String get() = createSqlFor(name)
    fun createSqlFor(table: String): String =
        createSqlTemplate.replace("${'$'}{TABLE_NAME}", table)
}

private fun readColumns(db: SupportSQLiteDatabase, table: String): List<String> {
    val columns = mutableListOf<String>()
    db.query("PRAGMA table_info(`" + table + "`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (nameIndex >= 0) columns += cursor.getString(nameIndex)
        }
    }
    return columns
}

private val TARGET_TABLES: List<TargetTable> = listOf(
    TargetTable(
        name = "conversations",
        columns = listOf("id", "title", "thinking_enabled", "reasoning_effort", "history_json", "applied_runtime_run_ids_json", "created_at", "updated_at", "folder_id", "is_pinned", "has_completion_marker", "provider_id", "model_id", "assistant_id", "deleted_at", "dispatch_parent_id", "dispatch_managed_at"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `conversations` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `thinking_enabled` INTEGER NOT NULL, `reasoning_effort` TEXT NOT NULL DEFAULT 'default', `history_json` TEXT NOT NULL, `applied_runtime_run_ids_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `folder_id` TEXT NOT NULL DEFAULT '', `is_pinned` INTEGER NOT NULL DEFAULT 0, `has_completion_marker` INTEGER NOT NULL DEFAULT 0, `provider_id` TEXT NOT NULL DEFAULT '', `model_id` TEXT NOT NULL DEFAULT '', `assistant_id` TEXT NOT NULL DEFAULT '', `deleted_at` INTEGER, `dispatch_parent_id` TEXT, `dispatch_managed_at` INTEGER, PRIMARY KEY(`id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "conversation_context_checkpoints",
        columns = listOf("conversation_id", "history_json", "cloud_usage_json"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `conversation_context_checkpoints` (`conversation_id` TEXT NOT NULL, `history_json` TEXT NOT NULL, `cloud_usage_json` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`conversation_id`), FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "conversation_messages",
        columns = listOf("id", "conversation_id", "sort_index", "type", "content", "images_json", "is_edited", "render_markdown", "context_tokens", "input_tokens", "output_tokens", "reasoning_tokens", "cached_tokens", "elapsed_seconds", "tool_name", "tool_status", "arguments_summary", "result_summary", "arguments_detail", "result_detail", "media_json", "image_count", "tools_json", "generated_at_millis"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `conversation_messages` (`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `sort_index` INTEGER NOT NULL, `type` TEXT NOT NULL, `content` TEXT NOT NULL, `images_json` TEXT NOT NULL, `is_edited` INTEGER NOT NULL DEFAULT 0, `render_markdown` INTEGER, `context_tokens` INTEGER, `input_tokens` INTEGER, `output_tokens` INTEGER, `reasoning_tokens` INTEGER, `cached_tokens` INTEGER, `elapsed_seconds` INTEGER, `tool_name` TEXT, `tool_status` TEXT, `arguments_summary` TEXT, `result_summary` TEXT, `arguments_detail` TEXT, `result_detail` TEXT, `media_json` TEXT NOT NULL, `image_count` INTEGER NOT NULL, `tools_json` TEXT NOT NULL, `generated_at_millis` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`conversation_id`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        indexSqls = listOf("CREATE INDEX IF NOT EXISTS `index_conversation_messages_conversation_id` ON `conversation_messages` (`conversation_id`)", "CREATE UNIQUE INDEX IF NOT EXISTS `index_conversation_messages_conversation_id_sort_index` ON `conversation_messages` (`conversation_id`, `sort_index`)"),
    ),
    TargetTable(
        name = "conversation_state",
        columns = listOf("id", "selected_conversation_id"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `conversation_state` (`id` TEXT NOT NULL, `selected_conversation_id` TEXT NOT NULL, PRIMARY KEY(`id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "conversation_folders",
        columns = listOf("id", "name", "sort_index", "created_at"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `conversation_folders` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `sort_index` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "model_providers",
        columns = listOf("id", "type", "name", "base_url", "api_key", "is_enabled", "is_built_in", "sort_order", "system_prompt", "custom_headers_json", "custom_body_json", "created_at", "endpoint_mode", "hosted_web_search_enabled", "responses_strip_reasoning_status", "anthropic_version", "balance_option_json", "auth_mode", "session_gateway_json"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `model_providers` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `name` TEXT NOT NULL, `base_url` TEXT NOT NULL, `api_key` TEXT NOT NULL, `is_enabled` INTEGER NOT NULL, `is_built_in` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `system_prompt` TEXT, `custom_headers_json` TEXT NOT NULL, `custom_body_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `endpoint_mode` TEXT NOT NULL, `hosted_web_search_enabled` INTEGER NOT NULL DEFAULT 0, `responses_strip_reasoning_status` INTEGER NOT NULL DEFAULT 0, `anthropic_version` TEXT NOT NULL, `balance_option_json` TEXT NOT NULL DEFAULT '{}', `auth_mode` TEXT NOT NULL DEFAULT 'api_key', `session_gateway_json` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "provider_models",
        columns = listOf("id", "provider_id", "model_id", "display_name", "is_enabled", "is_built_in", "sort_order", "owned_by", "context_window", "context_window_override", "input_modalities_json", "output_modalities_json", "attachment", "vision_override", "tool_call", "reasoning", "reasoning_capabilities_json", "reasoning_override", "reasoning_capabilities_override_json", "preferred_reasoning_effort", "structured_output", "supports_temperature", "custom_headers_json", "custom_body_json", "source", "created_at"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `provider_models` (`id` TEXT NOT NULL, `provider_id` TEXT NOT NULL, `model_id` TEXT NOT NULL, `display_name` TEXT NOT NULL, `is_enabled` INTEGER NOT NULL, `is_built_in` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `owned_by` TEXT, `context_window` INTEGER, `context_window_override` INTEGER, `input_modalities_json` TEXT NOT NULL, `output_modalities_json` TEXT NOT NULL, `attachment` INTEGER, `vision_override` INTEGER, `tool_call` INTEGER, `reasoning` INTEGER, `reasoning_capabilities_json` TEXT NOT NULL DEFAULT 'null', `reasoning_override` INTEGER, `reasoning_capabilities_override_json` TEXT NOT NULL DEFAULT 'null', `preferred_reasoning_effort` TEXT, `structured_output` INTEGER, `supports_temperature` INTEGER, `custom_headers_json` TEXT NOT NULL, `custom_body_json` TEXT NOT NULL, `source` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`provider_id`) REFERENCES `model_providers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        indexSqls = listOf("CREATE INDEX IF NOT EXISTS `index_provider_models_provider_id` ON `provider_models` (`provider_id`)", "CREATE INDEX IF NOT EXISTS `index_provider_models_provider_id_sort_order` ON `provider_models` (`provider_id`, `sort_order`)"),
    ),
    TargetTable(
        name = "runtime_results",
        columns = listOf("run_id", "handoff_id", "handoff_source", "handoff_payload", "dismiss_entry_surface", "ok", "content", "error", "reasoning_content", "transcript_json", "created_at", "virtual_delivery_completed"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `runtime_results` (`run_id` TEXT NOT NULL, `handoff_id` TEXT NOT NULL, `handoff_source` TEXT NOT NULL, `handoff_payload` TEXT NOT NULL, `dismiss_entry_surface` INTEGER NOT NULL, `ok` INTEGER NOT NULL, `content` TEXT NOT NULL, `error` TEXT, `reasoning_content` TEXT NOT NULL, `transcript_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `virtual_delivery_completed` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`run_id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "runtime_archive_runs",
        columns = listOf("archive_run_id", "run_id", "handoff_id", "handoff_source", "handoff_payload", "dismiss_entry_surface", "ok", "content", "error", "reasoning_content", "transcript_json", "user_image_previews_json", "created_at", "virtual_delivery_completed"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `runtime_archive_runs` (`archive_run_id` TEXT NOT NULL, `run_id` TEXT NOT NULL, `handoff_id` TEXT NOT NULL, `handoff_source` TEXT NOT NULL, `handoff_payload` TEXT NOT NULL, `dismiss_entry_surface` INTEGER NOT NULL, `ok` INTEGER NOT NULL, `content` TEXT NOT NULL, `error` TEXT, `reasoning_content` TEXT NOT NULL, `transcript_json` TEXT NOT NULL, `user_image_previews_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `virtual_delivery_completed` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`archive_run_id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "runtime_archive_events",
        columns = listOf("id", "archive_run_id", "sort_index", "event_json"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `runtime_archive_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `archive_run_id` TEXT NOT NULL, `sort_index` INTEGER NOT NULL, `event_json` TEXT NOT NULL, FOREIGN KEY(`archive_run_id`) REFERENCES `runtime_archive_runs`(`archive_run_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        indexSqls = listOf("CREATE INDEX IF NOT EXISTS `index_runtime_archive_events_archive_run_id` ON `runtime_archive_events` (`archive_run_id`)", "CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_archive_events_archive_run_id_sort_index` ON `runtime_archive_events` (`archive_run_id`, `sort_index`)"),
    ),
    TargetTable(
        name = "runtime_inflight_runs",
        columns = listOf("run_id", "owner_instance_id", "handoff_id", "handoff_source", "handoff_payload", "dismiss_entry_surface", "created_at", "updated_at", "recovery_incomplete"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `runtime_inflight_runs` (`run_id` TEXT NOT NULL, `owner_instance_id` TEXT NOT NULL, `handoff_id` TEXT NOT NULL, `handoff_source` TEXT NOT NULL, `handoff_payload` TEXT NOT NULL, `dismiss_entry_surface` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `recovery_incomplete` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`run_id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "runtime_inflight_events",
        columns = listOf("id", "run_id", "sort_index", "event_json"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `runtime_inflight_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `run_id` TEXT NOT NULL, `sort_index` INTEGER NOT NULL, `event_json` TEXT NOT NULL, FOREIGN KEY(`run_id`) REFERENCES `runtime_inflight_runs`(`run_id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        indexSqls = listOf("CREATE INDEX IF NOT EXISTS `index_runtime_inflight_events_run_id` ON `runtime_inflight_events` (`run_id`)", "CREATE UNIQUE INDEX IF NOT EXISTS `index_runtime_inflight_events_run_id_sort_index` ON `runtime_inflight_events` (`run_id`, `sort_index`)"),
    ),
    TargetTable(
        name = "skill_registry",
        columns = listOf("skill_id", "enabled", "source", "install_state"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `skill_registry` (`skill_id` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `source` TEXT NOT NULL, `install_state` TEXT NOT NULL, PRIMARY KEY(`skill_id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "mcp_servers",
        columns = listOf("id", "name", "url", "enabled", "protocol_mode", "authorization_type", "tools_json", "enabled_tool_names_json", "created_at", "sort_order", "last_refreshed_at", "last_protocol_version", "tools_expire_at"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `mcp_servers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `protocol_mode` TEXT NOT NULL, `authorization_type` TEXT NOT NULL, `tools_json` TEXT NOT NULL, `enabled_tool_names_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `last_refreshed_at` INTEGER, `last_protocol_version` TEXT, `tools_expire_at` INTEGER, PRIMARY KEY(`id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "media_index",
        columns = listOf("source_key", "model_id", "vector_dim", "kind", "display_name", "mime", "size_bytes", "modified_at", "content_hash", "duration_ms", "width", "height", "frame_time_ms", "thumb_path", "vector", "indexed_at"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `media_index` (`source_key` TEXT NOT NULL, `model_id` TEXT NOT NULL, `vector_dim` INTEGER NOT NULL, `kind` TEXT NOT NULL, `display_name` TEXT NOT NULL, `mime` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `modified_at` INTEGER NOT NULL, `content_hash` TEXT NOT NULL, `duration_ms` INTEGER, `width` INTEGER, `height` INTEGER, `frame_time_ms` INTEGER, `thumb_path` TEXT, `vector` BLOB NOT NULL, `indexed_at` INTEGER NOT NULL, PRIMARY KEY(`source_key`, `model_id`, `vector_dim`))",
        indexSqls = listOf("CREATE INDEX IF NOT EXISTS `index_media_index_kind` ON `media_index` (`kind`)", "CREATE INDEX IF NOT EXISTS `index_media_index_modified_at` ON `media_index` (`modified_at`)"),
    ),
    TargetTable(
        name = "media_source",
        columns = listOf("id", "type", "uri", "label", "enabled", "last_scan_at", "item_count"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `media_source` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `uri` TEXT NOT NULL, `label` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `last_scan_at` INTEGER, `item_count` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        indexSqls = listOf(),
    ),
    TargetTable(
        name = "dispatch_claims",
        columns = listOf("dispatch_id", "conversation_id", "brief", "origin_text", "run_id", "state", "created_at"),
        createSqlTemplate = "CREATE TABLE IF NOT EXISTS `dispatch_claims` (`dispatch_id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `brief` TEXT NOT NULL, `origin_text` TEXT NOT NULL, `run_id` TEXT, `state` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`dispatch_id`))",
        indexSqls = listOf("CREATE INDEX IF NOT EXISTS `index_dispatch_claims_conversation_id` ON `dispatch_claims` (`conversation_id`)"),
    ),
)
