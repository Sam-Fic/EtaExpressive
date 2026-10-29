package io.github.mangi.eta.agent.model

/** 标记原始参数或结果不得进入持久会话的工具。 */
internal object AgentSensitiveToolPolicy {
    fun isSensitive(toolName: String): Boolean =
        toolName.startsWith("mcp_") ||
            toolName.startsWith("recipe_") ||
            toolName in sensitiveTools

    private val sensitiveTools = setOf(
        "delegate_task",
        "manage_agent_workspace",
        "workspace_file",
        "get_task_result",
        "cancel_task",
        "continue_task",
        "supervise_task",
        "get_setting",
        "wifi_credentials",
        "recent_notifications",
        "list_installed_apps",
        "search_notification_history",
        "recent_app_activity",
        "app_usage_summary",
        "get_current_location",
        "get_device_environment",
        "list_alarms",
        "list_active_timers",
        "search_clipboard_history",
        "get_health_summary",
        "read_sms_code",
        "get_logcat",
        "search_media",
        "search_audio",
        "search_recordings",
        "search_files",
        "search_calendar_events",
        "search_contacts",
        "search_call_history",
        "search_messages",
        "search_downloads",
        "search_coloros_notes",
        "search_coloros_recordings",
        "search_recording_summaries",
        "search_coloros_memories",
        "search_saved_places",
        "search_personal_orders",
        "search_qq_chat_images",
        "search_wechat_chat_images",
        // 个人数据历史索引：结果是用户的账单、待办、行程与生活事件。
        "search_bills",
        "search_todos",
        "search_calendar_todos",
        "search_memory_collections",
        "search_daily_events",
        "search_flights",
        "search_hotels",
        "search_trains",
        "read_personal_item",
        "summarize_bills",
        // 便签、闹钟与设备开关：参数与结果包含个人安排或本机实时状态。
        "create_note",
        "read_note",
        "delete_note",
        "set_alarm_enabled",
        "update_alarm_time",
        "get_flashlight",
        "set_flashlight",
        "get_display_state",
        "set_screen_timeout",
        "get_sound_state",
        "set_sound_mode",
        "get_hotspot",
        "set_hotspot",
        "get_device_state",
        "inspect_app",
        // 结果包含用户本机媒体的文件名与路径；原始结果不进入持久会话。
        "semantic_search_media",
        "read_image",
        "set_setting",
        // 系统控制写入：参数与结果反映用户对本机开关的实时控制，原始内容不进入持久会话。
        "set_do_not_disturb",
        "set_brightness",
        "screen_action",
        "manage_app",
        "memory_get",
        "memory_write",
        // 日程与闹钟写入：参数与结果包含用户的个人安排，原始内容不进入持久会话。
        "create_calendar_event",
        // 批量创建与单条创建承载同样的个人安排（标题、时间），漏一个就会把内容写进持久会话。
        "create_calendar_events",
        "update_calendar_event",
        "delete_calendar_event",
        "delete_alarm",
        "update_alarm",
        // Root 深度控制与文件管理：参数与结果涉及系统内部状态、用户文件路径与网络凭据，
        // 原始内容不进入持久会话。
        "manage_file",
        "system_property",
        "display_control",
        "deep_launch",
        "package_control",
        "user_control",
        "appops_control",
        "power_control",
        "ime_control",
        "connect_wifi",
        "set_timezone",
        "search_settings",
        "dumpsys_diag",
        // 参数可能携带供应商密钥与端点；原始结果不进入持久会话。
        "eta_manage_models",
        // 参数可能携带 MCP 服务器的端点与 Bearer 密钥；原始结果不进入持久会话。
        "eta_configure_mcp",
        // 参数与结果可能包含用户历史对话的标题与正文；原始结果不进入持久会话。
        "eta_manage_history",
        // 参数是手机上的本地目录，结果含本机 Skill 的文件路径；原始结果不进入持久会话。
        "skills_install_local",
        // 自进化：模型自己写的配方及其执行结果，可能包含系统状态与用户数据。
        "eta_author_tool",
        "eta_list_recipes",
    )
}
