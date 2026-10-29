package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 常用设备能力的结构化 schema；按统一分组决定是否向模型公开。 */
internal object AgentDeviceToolCatalog {
    /** 敏感动作组的全部工具名，与 appendSensitiveActionTools 保持一致。 */
    private val ACTION_NAMES: Set<String> = setOf(
        "app_state_control", "appops_control", "connect_wifi", "create_calendar_event",
        "create_calendar_events", "create_note", "deep_launch", "delete_alarm",
        "delete_calendar_event", "delete_note", "display_control", "ime_control",
        "manage_app", "manage_file", "package_control", "power_control", "screen_action",
        "set_alarm_enabled", "set_brightness", "set_device_state", "set_do_not_disturb",
        "set_flashlight", "set_hotspot", "set_screen_timeout", "set_setting", "set_timezone",
        "system_property", "update_alarm", "update_alarm_time", "update_calendar_event",
        "user_control",
    )

    private val PERSONAL_READ_NAMES: Set<String> = setOf(
        "get_health_summary",
        "list_calendars",
        "read_calendar_event",
        "read_note",
        "read_personal_item",
        "read_sms_code",
        "recent_notifications",
        "search_audio",
        "search_bills",
        "search_calendar_events",
        "search_calendar_todos",
        "search_call_history",
        "search_clipboard_history",
        "search_coloros_memories",
        "search_coloros_notes",
        "search_coloros_recordings",
        "search_contacts",
        "search_daily_events",
        "search_downloads",
        "search_files",
        "search_flights",
        "search_hotels",
        "search_media",
        "search_memory_collections",
        "search_messages",
        "search_notes",
        "search_notification_history",
        "search_personal_orders",
        "search_qq_chat_images",
        "search_recording_summaries",
        "search_recordings",
        "search_saved_places",
        "search_todos",
        "search_trains",
        "search_wechat_chat_images",
        "summarize_bills",
    )

    private val DEVICE_STATE_READ_NAMES: Set<String> = setOf(
        "app_usage_summary",
        "dumpsys_diag",
        "get_current_location",
        "get_device_environment",
        "get_device_state",
        "get_display_state",
        "get_flashlight",
        "get_hotspot",
        "get_logcat",
        "get_setting",
        "get_sound_state",
        "list_active_timers",
        "list_alarms",
        "list_installed_apps",
        "recent_app_activity",
        "search_settings",
        "wifi_credentials",
    )

    /** 敏感动作组的全部工具名；统一分组与单工具迁移使用。 */
    fun sensitiveActionToolNames(): Set<String> = ACTION_NAMES
    /** 个人内容读取档的全部工具名；统一分组与单工具迁移使用。 */
    fun personalReadToolNames(): Set<String> = PERSONAL_READ_NAMES
    /** 设备状态读取档的全部工具名；统一分组与单工具迁移使用。 */
    fun deviceStateReadToolNames(): Set<String> = DEVICE_STATE_READ_NAMES

    /**
     * 下发设备侧工具。组成员边界由 [AgentToolGroups] 定义：直达 + 敏感写属于设备控制组，
     * 个人内容读 + 设备状态读属于个人数据组。
     *
     * 旧的「个人信息 / 设备状态」两档细分不再由这里判断：档位差异已迁移成单工具禁用集，
     * 在 [AgentToolCatalog.build] 的最后一次过滤里统一剔除，避免同一件事有两处真相。
     */
    fun appendTo(
        tools: JSONArray,
        directTools: Boolean,
        sensitiveReadTools: Boolean,
        sensitiveActionTools: Boolean,
    ) {
        if (directTools) appendDirectTools(tools)
        if (sensitiveReadTools) appendSensitiveReadTools(tools)
        if (sensitiveActionTools) appendSensitiveActionTools(tools)
    }

    private fun appendDirectTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "inspect_app",
                    "Read the version, UID, install source, install path, enabled state and requested permissions of a package in the current Android user. The app does not need a launcher entry." +
                        "Only Eta's own Android user is supported; work profiles are not queried. The permission list is paginated with permission_offset/permission_limit.",
                    properties(
                        "package_name" to string("Exact Android package name", 255),
                        "user_id" to integer("Android user id that owns Eta; usually you do not need to pass it", 0, 2_147_483_647),
                        "permission_offset" to integer("Start index of the requested permission list; default 0", 0, 2_147_483_647),
                        "permission_limit" to integer("Maximum number of permissions to return; default 100", 1, 200),
                    ),
                    "package_name",
                ),
            )
            .put(
                function(
                    "set_alarm",
                    "Create a system alarm directly; do not use the GUI. Convert relative dates with get_current_context first; hour/minute use device local time. If the system refuses direct insertion, the clock screen may only be opened.",
                    properties(
                        "hour" to integer("0 to 23", 0, 23),
                        "minute" to integer("0 to 59", 0, 59),
                        "label" to string("Alarm label, at most 100 characters", 100),
                        "repeat_days" to stringArray(
                            "Weekdays to repeat; omit for a one-shot alarm",
                            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
                        ),
                        "vibrate" to boolean("Whether to vibrate; default true"),
                    ),
                    "hour", "minute",
                ),
            )
            .put(
                function(
                    "set_timer",
                    "Create a system timer directly; do not use the GUI. duration_seconds must be between 1 and 86400 seconds.",
                    properties(
                        "duration_seconds" to integer("Timer length in seconds", 1, 86_400),
                        "label" to string("Timer label, at most 100 characters", 100),
                    ),
                    "duration_seconds",
                ),
            )
            .put(emptyFunction("device_status", "Read battery, memory, storage, OS version and uptime."))
            .put(emptyFunction("network_info", "Read the current network type, connectivity validation state and basic current Wi-Fi information. Saved passwords are not returned."))
            .put(limitFunction("top_memory_apps", "List processes with the highest memory use by current RSS."))
            .put(limitFunction("top_storage_apps", "List apps with the highest storage use, counting app, data and cache together."))
            .put(
                function(
                    "media_control",
                    "Control the current media session directly; do not drive a player GUI.",
                    properties(
                        "action" to enumString(
                            "Media action",
                            "play", "pause", "play_pause", "next", "previous", "stop",
                        ),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "set_volume",
                    "Set system volume directly; do not drive the volume GUI.",
                    properties(
                        "stream" to enumString("Audio stream", "media", "alarm", "ring", "notification"),
                        "percent" to integer("Volume percentage from 0 to 100", 0, 100),
                    ),
                    "stream", "percent",
                ),
            )
            .put(
                function(
                    "set_ringer_mode",
                    "Switch the ringer mode (normal/vibrate/silent) directly; do not drive the volume GUI. Root is not required." +
                        "Silent stops calls and notifications from ringing but leaves media volume unchanged; to mute media as well use set_volume." +
                        "To suppress all notifications use set_do_not_disturb; the two are not the same.",
                    properties(
                        "mode" to enumString("Ringer mode", "normal", "vibrate", "silent"),
                    ),
                    "mode",
                ),
            )
            .put(
                function(
                    "set_sound_mode",
                    "Switch ringer mode (normal/vibrate/silent). Same effect as set_ringer_mode, no Root required." +
                        "Affects calls and notifications only; media volume is unchanged.",
                    properties("mode" to enumString("Ringer mode", "normal", "vibrate", "silent")),
                    "mode",
                ),
            )
    }

    private fun appendSensitiveReadTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "get_setting",
                    "Read one Android Settings value. The result may contain sensitive data such as device identifiers; the raw result is not persisted.",
                    properties(
                        "namespace" to enumString("Settings namespace", "system", "secure", "global"),
                        "key" to string("Exact settings key", 200),
                    ),
                    "namespace", "key",
                ),
            )
            .put(
                function(
                    "wifi_credentials",
                    "Read Wi-Fi networks saved on the phone. By default only network names are returned; pass include_password=true only when the task genuinely needs the password. The raw result is not persisted.",
                    properties(
                        "ssid" to string("Optional exact Wi-Fi name", 128),
                        "include_password" to boolean("Include saved passwords in the result; default false"),
                        "limit" to integer("Maximum number of results; default 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "recent_notifications",
                    "Read titles and bodies of notifications currently in the shade. The result is not written to the durable conversation.",
                    properties(
                        "package_name" to string("Optional exact package name filter", 255),
                        "limit" to integer("Maximum number of results; default 10", 1, 20),
                    ),
                ),
            )
            .put(
                function(
                    "search_notification_history",
                    "Search notifications Eta recorded over the last 7 days after the user granted notification access. Raw results are not written to the durable conversation.",
                    properties(
                        "query" to string("Optional title or body keyword", 200),
                        "package_name" to string("Optional exact package name", 255),
                        "max_age_hours" to integer("Hours to look back; default 24", 1, 168),
                        "limit" to integer("Maximum number of results; default 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "recent_app_activity",
                    "Read the order in which apps were recently opened. Requires usage access.",
                    properties(
                        "package_name" to string("Optional exact package name", 255),
                        "max_age_hours" to integer("Hours to look back; default 24", 1, 168),
                        "limit" to integer("Maximum number of results; default 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "app_usage_summary",
                    "Summarize recent app usage by foreground time. Requires usage access.",
                    properties(
                        "max_age_hours" to integer("Hours to summarize; default 24", 1, 168),
                        "limit" to integer("Maximum number of results; default 20", 1, 50),
                    ),
                ),
            )
            .put(emptyFunction("get_current_location", "Read the last known system location. Does not keep listening or wake the GPS."))
            .put(emptyFunction("get_device_environment", "Read lock screen, do-not-disturb, ringer, audio output and external display state."))
            .put(
                function(
                    "list_alarms",
                    "Read scheduled alarms from the system clock app, adapting to the device's default clock app.",
                    properties(
                        "enabled_only" to boolean("Return only enabled alarms; default true"),
                        "limit" to integer("Maximum number of results; default 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "list_active_timers",
                    "Read running or paused timers from the system clock app, adapting to the device's default clock app.",
                    properties("limit" to integer("Maximum number of results; default 20", 1, 50)),
                ),
            )
            .put(searchFunction("search_clipboard_history", "Search clipboard history saved by the current system input method."))
            .put(
                function(
                    "get_health_summary",
                    "Summarize steps, sleep, exercise, heart rate, weight and blood oxygen from system health data; raw measurement series are not returned.",
                    properties("days" to integer("Number of recent days to summarize; default 7", 1, 30)),
                ),
            )
            .put(
                function(
                    "read_sms_code",
                    "Extract only 4-8 digit verification codes, senders and times from recent SMS; full message bodies are not returned.",
                    properties(
                        "max_age_minutes" to integer("Only inspect SMS from the last N minutes; default 10", 1, 1_440),
                    ),
                ),
            )
            .put(
                function(
                    "get_logcat",
                    "Read recent system logs. query only filters the logs already read; it never reaches a shell.",
                    properties(
                        "query" to string("Optional filter text", 200),
                        "max_lines" to integer("Maximum log lines; default 200", 20, 500),
                    ),
                ),
            )
            .put(searchFunction("search_media", "Search images in the local gallery, filtered by file name or album path. Returns metadata and openable content URIs; image contents are not read."))
            .put(searchFunction("search_audio", "Search local music and audio files, filtered by title or file name."))
            .put(searchFunction("search_recordings", "Search local recordings. Results come from the system media library; transcripts and audio contents are not read."))
            .put(searchFunction("search_files", "Search documents and downloads in shared storage, filtered by file name. Private directories of other apps are not traversed."))
            .put(searchFunction("search_calendar_events", "Search system calendar events, filtered by title, location or description."))
            .put(searchFunction("search_contacts", "Search system contacts; returns names and lookup URIs."))
            .put(searchFunction("search_call_history", "Search call logs, filtered by number or cached contact name."))
            .put(searchFunction("search_messages", "Search SMS, filtered by sender or body keyword. Results are sensitive personal content."))
            .put(searchFunction("search_downloads", "Search system download records, filtered by file name or description."))
            .put(searchFunction("search_coloros_notes", "Search ColorOS notes and to-dos, filtered by title or body. Only available when ColorOS Notes is installed and accessible."))
            .put(
                function(
                    "search_notes",
                    "Alias of search_coloros_notes; searches ColorOS notes and to-dos." +
                        "Falls back to the historical index when raw notes are unreadable; with current_only=true only raw notes are queried, without fallback.",
                    properties(
                        "query" to string("Optional keyword, at most 200 characters"),
                        "limit" to integer("Maximum number of results; default 10", 1, 30),
                        "current_only" to boolean("Use only the current raw note source; do not fall back to the historical index"),
                    ),
                ),
            )
            .put(
                function(
                    "read_note",
                    "Read the title, body and update time of one ColorOS note by note_id. Requires Root to reach the notes provider directly.",
                    properties("note_id" to string("Raw UUID returned for the note", 36)),
                    "note_id",
                ),
            )
            .put(
                function(
                    "list_calendars",
                    "List system calendars writable by the current user (calendar_id, name, account, read-only flag)." +
                        "Call this first when unsure which calendar to write to before creating an event; an empty result means a calendar must be created in the calendar app first.",
                    properties(),
                ),
            )
            .put(
                function(
                    "read_calendar_event",
                    "Read the full fields, reminders and recurrence rule of one system calendar event by raw event_id." +
                        "event_id must come from search_calendar_events; do not pass document ids from system memory.",
                    properties("event_id" to integer("Event id", 1, 2_147_483_647)),
                    "event_id",
                ),
            )
            .put(emptyFunction("get_flashlight", "Read flashlight on/off state. The state comes from a CameraManager torch connection; reading does not turn the light on."))
            .put(emptyFunction("get_sound_state", "Read ringer mode, do-not-disturb state and current/maximum volume of each audio stream without changing any setting."))
            .put(emptyFunction("get_hotspot", "Read whether the personal hotspot is on; ordinary apps can only read it and cannot change it."))
            .put(
                function(
                    "get_display_state",
                    "Read screen brightness percentage, auto-brightness, auto-rotate and screen-off timeout. Root is required to read the current system brightness.",
                    properties(),
                ),
            )
            .put(
                function(
                    "get_device_state",
                    "Read the current state of one system toggle, read-only, without changing it. target supports wifi/bluetooth/mobile_data/" +
                        "airplane_mode/location/nfc/battery_saver/auto_rotate/auto_brightness/dark_mode/night_light." +
                        "enabled=null means the device cannot confirm that state.",
                    properties(
                        "target" to enumString(
                            "Device capability",
                            "wifi", "bluetooth", "mobile_data", "airplane_mode", "location", "nfc",
                            "battery_saver", "auto_rotate", "auto_brightness", "dark_mode", "night_light",
                        ),
                    ),
                    "target",
                ),
            )
            .put(searchFunction("search_coloros_recordings", "Search normal and call recordings in the ColorOS recorder app; returns name, duration, type and file path."))
            .put(searchFunction("search_recording_summaries", "Search transcription summaries and notes linked to ColorOS recordings. Only available when the recorder app has produced a summary."))
            .put(searchFunction("search_coloros_memories", "Search ColorOS system memory for collected information, bills, schedules, pickup codes, deliveries, places and attachments."))
            .put(searchFunction("search_saved_places", "Search places saved or recognized in system memory."))
            .put(searchFunction("search_personal_orders", "Search food delivery, shopping, courier, ticket and travel orders recognized in system memory."))
            .put(personalSearchFunction("search_bills", "Find bill records by merchant, content and time."))
            .put(personalSearchFunction("search_todos", "Query to-do signals extracted by the system from personal information; records without a recognized time cannot be matched by time."))
            .put(personalSearchFunction("search_calendar_todos", "Query to-dos in the calendar and their scheduled times."))
            .put(personalSearchFunction("search_memory_collections", "Find saved memory collections; business-time filtering is not supported."))
            .put(personalSearchFunction("search_daily_events", "Find life events the system recorded or inferred; inferred events are not confirmed facts."))
            .put(personalSearchFunction("search_flights", "Search trips by flight, airport and scheduled departure time."))
            .put(personalSearchFunction("search_hotels", "Search bookings by hotel, address, order and check-in time."))
            .put(personalSearchFunction("search_trains", "Search trips by train number, station and departure time."))
            .put(
                function(
                    "read_personal_item",
                    "Read index details with a ref returned by a personal-data query; the ref cannot modify the original record directly.",
                    properties("ref" to string("Full ref returned by the query result", 160)),
                    "ref",
                ),
            )
            .put(
                function(
                    "summarize_bills",
                    "Exactly summarize the matching bill index, distinguishing income, expense, transfer and unknown types; at most 1000 entries are summarized in full," +
                        "and an oversized range fails instead of returning a partial total.",
                    properties(
                        "query" to string("Optional keyword, at most 200 characters"),
                        "start_time" to string("Inclusive start time, ISO 8601 with timezone offset", 64),
                        "end_time" to string("Exclusive end time, ISO 8601 with timezone offset", 64),
                    ),
                ),
            )
            .put(searchFunction("search_qq_chat_images", "Search the QQ chat image cache; returns time, size, type and private path of the most recent files. Only available when QQ is installed and its cache still exists."))
            .put(searchFunction("search_wechat_chat_images", "Search the WeChat chat image cache; returns time, size and private path of the most recent files. Only available when WeChat is installed and its cache still exists."))
            .put(
                function(
                    "list_installed_apps",
                    "List installed apps (including system and disabled apps); returns package name, app name, version, system-app flag and enabled state. No Root required." +
                        "Results are device-private and are not written to the durable conversation. Once the target is found, start it with launch_app and package_name.",
                    properties(
                        "filter" to enumString("Filter scope; default all", "all", "user", "system", "disabled"),
                        "limit" to integer("Maximum number of results; default 50", 1, 200),
                    ),
                ),
            )
            .put(
                function(
                    "search_settings",
                    "List all keys under an Android Settings namespace with keyword filtering. Requires Root." +
                        "Use this to find the real key name of a settable item (do not guess key names). Change it with set_setting afterwards." +
                        "Common uses: confirming the key behind a system toggle, and diagnosing why set_setting failed.",
                    properties(
                        "namespace" to enumString("Settings namespace", "system", "secure", "global"),
                        "keyword" to string("Filter by a keyword contained in the key name or value; may be empty", 100),
                        "limit" to integer("Maximum number of results; default 100", 1, 500),
                    ),
                    "namespace",
                ),
            )
            .put(
                function(
                    "dumpsys_diag",
                    "Capture runtime state of an Android system service (dumpsys) for diagnosis. Requires Root." +
                        "Optionally specify a service name (for example battery, wifi, activity, alarm, job, notification, gfxinfo, cpuinfo, SurfaceFlinger)" +
                        "plus a focus keyword to filter lines. Output is truncated to 4000 characters by default; raise it with max_chars." +
                        "Diagnostic reading only; do not use it to change system state.",
                    properties(
                        "service" to string("System service name, such as battery/wifi/activity", 40),
                        "focus" to string("Keep only lines containing this keyword; may be empty", 100),
                        "max_chars" to integer("Maximum output characters; default 4000", 200, 20_000),
                    ),
                    "service",
                ),
            )
    }

    private fun appendSensitiveActionTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "set_setting",
                    "Modify one Android Settings value. Without Root, the user must have granted Eta the \"Modify system settings\" permission" +
                        "(when not granted it returns WRITE_SETTINGS_REQUIRED and points the user to Settings → Apps → Eta → Modify system settings;" +
                        "do not repeat the call in that case). With Root it writes directly. The value is read back to verify." +
                        "Common keys: system/screen_off_timeout (seconds until screen off), system/screen_brightness (0-255)," +
                        "system/screen_brightness_mode (0 manual, 1 auto), system/accelerometer_rotation (0/1 auto-rotate)," +
                        "system/font_scale (font scale), system/show_touches, secure/ui_night_mode (dark mode)," +
                        "global/stay_on_while_charging (keep screen on while charging). Convert relative dates with get_current_context first.",
                    properties(
                        "namespace" to enumString("Settings namespace", "system", "secure", "global"),
                        "key" to string("Exact settings key", 200),
                        "value" to string("New value", 2_000),
                    ),
                    "namespace", "key", "value",
                ),
            )
            .put(
                function(
                    "set_device_state",
                    "Enable or disable Wi-Fi/Bluetooth/airplane mode/mobile data/NFC/hotspot directly; do not drive the settings GUI." +
                        "With Root and a direct command for that toggle it writes directly and verifies; without Root, for the hotspot toggle, or when the Root command fails," +
                        "it opens the matching system settings panel and returns ok=false with RADIO_PANEL_FALLBACK." +
                        "Tell the user to operate the panel manually and do not repeat this tool.",
                    properties(
                        "target" to enumString(
                            "Device capability",
                            "wifi", "bluetooth", "airplane_mode", "mobile_data", "nfc", "hotspot",
                        ),
                        "enabled" to boolean("true to enable, false to disable"),
                    ),
                    "target", "enabled",
                ),
            )
            .put(
                function(
                    "app_state_control",
                    "Stop, freeze or unfreeze an exact package, including system apps.",
                    properties(
                        "package_name" to string("Exact Android package name", 255),
                        "action" to enumString("Action", "force_stop", "freeze", "unfreeze"),
                    ),
                    "package_name", "action",
                ),
            )
            .put(
                function(
                    "set_do_not_disturb",
                    "Turn do-not-disturb on or off. Without Root, the user must have granted Eta DND access" +
                        "(when not granted it returns NOTIFICATION_POLICY_REQUIRED and points the user to Settings → Notifications → Do Not Disturb;" +
                        "do not repeat the call in that case). With Root it writes directly. priority_only=true allows only priority content through (supported without Root;" +
                        "the Root fallback only toggles fully on/off and says so in the result).",
                    properties(
                        "enabled" to boolean("true to turn DND on, false to turn it off"),
                        "priority_only" to boolean("Allow only priority interruptions; default false"),
                    ),
                    "enabled",
                ),
            )
            .put(
                function(
                    "set_brightness",
                    "Set screen brightness or switch auto-brightness. Without Root, the user must have granted Eta the \"Modify system settings\" permission" +
                        "(when not granted it returns WRITE_SETTINGS_REQUIRED and points to the permission; do not repeat the call). With Root it writes directly." +
                        "percent is ignored when auto=true. The value is read back to verify; brightness tolerates a vendor-clamped error of ±2.",
                    properties(
                        "percent" to integer("Brightness percentage from 0 to 100; optional when auto=true", 0, 100),
                        "auto" to boolean("Switch to auto-brightness; default false"),
                    ),
                ),
            )
            .put(
                function(
                    "screen_action",
                    "Screen power action. lock and screen_off are equivalent (screen off also locks)." +
                        "power_menu works without Root via the accessibility power dialog; lock/screen_off and reboot require Root." +
                        "reboot restarts the device immediately and interrupts this conversation; you must confirm with ask_user first," +
                        "and must not call it without confirmation.",
                    properties(
                        "action" to enumString("Power action", "lock", "screen_off", "power_menu", "reboot"),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "manage_app",
                    "App management: install an APK, uninstall, clear data, grant or revoke runtime permissions." +
                        "apk_path for install must be an absolute path on the device (search for .apk files with search_files first)." +
                        "Without Root, install/uninstall open the system install/uninstall UI for the user to confirm (returns ok=false;" +
                        "tell the user to confirm and do not repeat the call); clear_data/grant_permission/revoke_permission require Root." +
                        "Permission names must be fully qualified, for example android.permission.CAMERA.",
                    properties(
                        "action" to enumString(
                            "Management action",
                            "install", "uninstall", "clear_data", "grant_permission", "revoke_permission",
                        ),
                        "package_name" to string("Target package name; not needed for install", 255),
                        "apk_path" to string("Absolute APK path for install", 4_096),
                        "permission" to string("Fully qualified permission name for grant/revoke, such as android.permission.CAMERA", 200),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "create_calendar_event",
                    "Create a calendar event directly (writing to the calendar provider without opening the calendar UI). For relative dates such as \"tomorrow/Friday\"," +
                        "get the current time with get_current_context before converting; do not guess the year." +
                        "start is required in the form YYYY-MM-DDTHH:MM (device local time; seconds are optional, and YYYY-MM-DD HH:MM is also accepted;" +
                        "a date alone creates an all-day event). When end is omitted it defaults to start plus 1 hour (1 day for all-day events)." +
                        "reminder_minutes is how many minutes early to notify, default 10; pass 0 for no reminder." +
                        "repeat accepts none/daily/weekly/monthly/yearly; with repeat=weekly, repeat_days selects the weekdays." +
                        "Without Root it degrades to opening a pre-filled calendar UI and returns ok=false; tell the user to confirm and save in the calendar, and do not repeat the call.",
                    properties(
                        "title" to string("Event title", 200),
                        "start" to string("Start time, YYYY-MM-DDTHH:MM or YYYY-MM-DD", 32),
                        "end" to string("End time in the same format as start; omit for the default duration", 32),
                        "all_day" to boolean("All-day event; automatically true when start is a date alone"),
                        "description" to string("Event description", 2_000),
                        "location" to string("Location", 500),
                        "reminder_minutes" to integer("Minutes of advance notice, default 10; 0 means no reminder", 0, 20_160),
                        "repeat" to enumString("Recurrence rule", "none", "daily", "weekly", "monthly", "yearly"),
                        "repeat_days" to stringArray(
                            "Weekdays to repeat; only effective with repeat=weekly",
                            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
                        ),
                    ),
                    "title", "start",
                ),
            )
            .put(
                function(
                    "update_calendar_event",
                    "Modify an existing system calendar event, changing only the fields you provide; omitted fields stay unchanged." +
                        "Locate the event one of two ways: prefer event_id (returned by search_calendar_events or on creation);" +
                        "otherwise find it by title with match_title, optionally narrowing to that day with match_date(YYYY-MM-DD)." +
                        "When several events match it returns a candidate list with EVENT_AMBIGUOUS; retry once with an event_id from the candidates instead of repeating the same call." +
                        "reminder_minutes replaces all advance reminders of that event; passing repeat=none clears the recurrence rule." +
                        "Time formats are the same as create_calendar_event.",
                    properties(
                        "event_id" to integer("Event id; preferred", 1, 2_147_483_647),
                        "match_title" to string("Keyword used when locating by title", 200),
                        "match_date" to string("Restrict to this day, YYYY-MM-DD", 10),
                        "title" to string("New title", 200),
                        "start" to string("New start time in the same format as create_calendar_event", 32),
                        "end" to string("New end time in the same format as start", 32),
                        "all_day" to boolean("Change to an all-day event"),
                        "description" to string("New description; an empty string clears it", 2_000),
                        "location" to string("New location; an empty string clears it", 500),
                        "reminder_minutes" to integer("New minutes of advance notice; 0 means no reminder", 0, 20_160),
                        "repeat" to enumString("New recurrence rule; none clears it", "none", "daily", "weekly", "monthly", "yearly"),
                        "repeat_days" to stringArray(
                            "New weekdays to repeat; only effective with repeat=weekly",
                            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
                        ),
                    ),
                ),
            )
            .put(
                function(
                    "delete_calendar_event",
                    "Delete a system calendar event. Located the same way as update_calendar_event: prefer event_id," +
                        "otherwise use match_title (optionally with match_date). When several match it returns a candidate list; retry once with an event_id from the candidates." +
                        "Deleting a recurring event by event id deletes the whole series.",
                    properties(
                        "event_id" to integer("Event id; preferred", 1, 2_147_483_647),
                        "match_title" to string("Keyword used when locating by title", 200),
                        "match_date" to string("Restrict to this day, YYYY-MM-DD", 10),
                    ),
                ),
            )
            .put(
                function(
                    "delete_alarm",
                    "Delete an alarm in the system clock (Root writes straight to the clock database without opening the clock UI)." +
                        "Locate it by alarm_id (the _id returned by list_alarms) first; otherwise combine match_time(HH:MM) and match_label." +
                        "When several alarms match it returns a candidate list with ALARM_AMBIGUOUS; retry once with an alarm_id from the candidates;" +
                        "when nothing matches it returns ALARM_NOT_FOUND; you can inspect with list_alarms first." +
                        "If the clock UI still shows the old alarm after deletion, reopening the clock app refreshes it; do not repeat this tool.",
                    properties(
                        "alarm_id" to integer("Alarm id; preferred", 1, 2_147_483_647),
                        "match_time" to string("Locate by time, HH:MM", 5),
                        "match_label" to string("Locate by label keyword", 100),
                    ),
                ),
            )
            .put(
                function(
                    "update_alarm",
                    "Modify an existing alarm in the system clock, changing only the fields you provide (enabled/label/vibrate/hour/minute)." +
                        "Located the same way as delete_alarm: alarm_id first, otherwise match_time(HH:MM) and match_label." +
                        "After a time change, a one-shot alarm recomputes its next trigger; a repeating alarm is recomputed by the clock app on its next open." +
                        "To change the recurrence (weekdays), delete_alarm first and rebuild with set_alarm instead of editing repeatedly." +
                        "When several match it returns a candidate list; retry once with an alarm_id.",
                    properties(
                        "alarm_id" to integer("Alarm id; preferred", 1, 2_147_483_647),
                        "match_time" to string("Locate by time, HH:MM", 5),
                        "match_label" to string("Locate by label keyword", 100),
                        "enabled" to boolean("Whether the alarm is enabled"),
                        "hour" to integer("New hour, 0 to 23", 0, 23),
                        "minute" to integer("New minute, 0 to 59", 0, 59),
                        "label" to string("New label; an empty string clears it", 100),
                        "vibrate" to boolean("Whether to vibrate"),
                    ),
                ),
            )
            .put(
                function(
                    "create_calendar_events",
                    "Create multiple events in one calendar in a batch (at most 30), writing each one and reading it back to verify." +
                        "Each item uses the same fields as create_calendar_event; calendar_id may be given once for the batch; confirm it with list_calendars first." +
                        "A mid-batch failure stops further writes and returns CALENDAR_BATCH_PARTIAL with the list of created events;" +
                        "in that case confirm with search_calendar_events and create only the missing items instead of repeating the whole batch.",
                    properties(
                        "calendar_id" to integer("Optional calendar id", 1, 2_147_483_647),
                        "events" to JSONObject()
                            .put("type", "array")
                            .put("minItems", 1)
                            .put("maxItems", 30)
                            .put("description", "Array of events; each item must contain title and start")
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        properties(
                                            "title" to string("Event title, at most 200 characters", 200),
                                            "start" to string("Start time: YYYY-MM-DDTHH:MM, or YYYY-MM-DD for all-day", 40),
                                            "end" to string("Optional end time in the same format as start", 40),
                                            "description" to string("Optional note", 1_000),
                                            "location" to string("Optional location", 200),
                                            "reminder_minutes" to integer("Optional minutes of advance notice", 0, 20_160),
                                        ),
                                    )
                                    .put("required", JSONArray().put("title").put("start")),
                            ),
                    ),
                    "events",
                ),
            )
            .put(
                function(
                    "create_note",
                    "Create a ColorOS note directly and read it back to verify, without opening the notes UI. Requires Root." +
                        "The title may be empty; the body allows at most 20000 characters. When it returns ok=false with code=NOTE_CREATE_UNVERIFIED," +
                        "confirm with search_coloros_notes first instead of creating again.",
                    properties(
                        "content" to string("Note body, at most 20000 characters", 20_000),
                        "title" to string("Optional title, at most 200 characters", 200),
                    ),
                    "content",
                ),
            )
            .put(
                function(
                    "delete_note",
                    "Move one ColorOS note to the trash and read it back to confirm. Requires Root; note_id must be the raw UUID returned by create_note/read_note.",
                    properties("note_id" to string("Raw UUID of the note", 36)),
                    "note_id",
                ),
            )
            .put(
                function(
                    "set_alarm_enabled",
                    "Enable or disable a system alarm without changing its time. Requires Root; prefer alarm_id, otherwise match_time/match_label." +
                        "When several match it returns candidates; retry once with an alarm_id; if nothing matches, inspect with list_alarms first.",
                    properties(
                        "alarm_id" to integer("Alarm id; preferred", 1, 2_147_483_647),
                        "match_time" to string("Locate by time, HH:MM", 5),
                        "match_label" to string("Locate by label keyword", 100),
                        "enabled" to boolean("true to enable, false to disable"),
                    ),
                    "enabled",
                ),
            )
            .put(
                function(
                    "update_alarm_time",
                    "Change only the trigger time of a system alarm (hour/minute); other fields stay unchanged. Located the same way as set_alarm_enabled." +
                        "A one-shot alarm recomputes its trigger time; to change the recurrence, delete_alarm first and rebuild with set_alarm.",
                    properties(
                        "alarm_id" to integer("Alarm id; preferred", 1, 2_147_483_647),
                        "match_time" to string("Locate by its current time, HH:MM", 5),
                        "match_label" to string("Locate by label keyword", 100),
                        "hour" to integer("New hour, 0 to 23", 0, 23),
                        "minute" to integer("New minute, 0 to 59", 0, 59),
                    ),
                    "hour", "minute",
                ),
            )
            .put(
                function(
                    "set_flashlight",
                    "Turn the flashlight on or off and read the state back, without driving the camera UI. Available to ordinary apps;" +
                        "FLASHLIGHT_BUSY means the camera holds the flash unit; do not repeat the call.",
                    properties("enabled" to boolean("true turns it on, false turns it off")),
                    "enabled",
                ),
            )
            .put(
                function(
                    "set_screen_timeout",
                    "Set the screen-off timeout and read it back to verify. Requires Root. seconds accepts 15 to 1800.",
                    properties("seconds" to integer("Seconds until screen off", 15, 1_800)),
                    "seconds",
                ),
            )
            .put(
                function(
                    "set_hotspot",
                    "Turn the personal hotspot on or off. Ordinary apps usually lack the privilege to change it," +
                        "in which case it opens wireless settings and returns HOTSPOT_PANEL_FALLBACK/ok=false;" +
                        "tell the user to operate the panel and do not repeat this tool.",
                    properties("enabled" to boolean("true turns the hotspot on, false turns it off")),
                    "enabled",
                ),
            )
            .put(
                function(
                    "manage_file",
                    "Copy, move, delete or rename files and directories on the device without Root (the app already holds all-files access)." +
                        "Cross-storage or private-path failures fall back to Root. Writes are verified by reading back (target exists, source is gone)." +
                        "Paths must be absolute device paths; locate them first with search_files / list_directory." +
                        "Critical OS directories (/system, the top level of /data, and similar) are rejected.",
                    properties(
                        "action" to enumString("Action", "copy", "move", "delete", "rename", "mkdir"),
                        "path" to string("Source path (absolute)", 1_024),
                        "dest_path" to string("Target path; if the target is an existing directory the file lands inside it and keeps its original name", 1_024),
                        "new_name" to string("New name for rename (without /)", 255),
                    ),
                    "action", "path",
                ),
            )
            .put(
                function(
                    "system_property",
                    "Read or modify Android system properties (getprop/setprop). Requires Root." +
                        "get supports an exact key or a prefix batch match (such as persist.sys.);" +
                        "set writes and reads back to verify. Read-only properties (ro.*) and system-reserved properties (ctl.*, init.*) are rejected;" +
                        "on PROPERTY_READONLY do not call again; use the corresponding setting instead.",
                    properties(
                        "action" to enumString("Action", "get", "set"),
                        "key" to string("Property name, such as persist.sys.locale", 128),
                        "prefix" to string("Prefix batch match for get, such as persist.sys.", 128),
                        "value" to string("New value for set", 512),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "display_control",
                    "Change screen resolution, screen density (DPI) or overscan margins; each can be reset to default. Requires Root (wm command)." +
                        "Writes are read back to verify. If the UI looks wrong, recover with reset_resolution / reset_density / reset_overscan." +
                        "Changing DPI is often used to make UI elements larger or smaller; changing resolution restarts the UI.",
                    properties(
                        "action" to enumString(
                            "Action",
                            "set_resolution", "reset_resolution",
                            "set_density", "reset_density",
                            "set_overscan", "reset_overscan",
                        ),
                        "resolution" to string("set_resolution: WxH, such as 1080x2400", 16),
                        "density" to integer("set_density: DPI value from 100 to 800", 100, 800),
                        "overscan" to string("set_overscan: left,top,right,bottom, such as 0,0,0,-100", 32),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "deep_launch",
                    "Jump straight to a specific activity or service of any app (am start/startservice) without hunting through launchers. Requires Root." +
                        "Pass either component (package/class, where the class may omit the package and start with .) or action (intent action), optionally with data_uri." +
                        "If the component does not exist it returns DEEP_LAUNCH_FAILED; do not repeat the same call;" +
                        "for ordinary launches prefer launch_app with a package name.",
                    properties(
                        "mode" to enumString("Launch type; default activity", "activity", "service"),
                        "component" to string("Component name, such as com.android.settings/.Settings", 255),
                        "action" to string("Intent action, such as android.settings.WIFI_SETTINGS", 255),
                        "data_uri" to string("Intent data URI; may be empty", 2_000),
                    ),
                ),
            )
            .put(
                function(
                    "package_control",
                    "Package management (pm command): list packages, enable/disable them (including system apps), get the APK path," +
                        "uninstall a system app update back to the factory version, and reinstall for the current user. Requires Root." +
                        "Disabling a core system app may be refused (PACKAGE_STATE_NOT_APPLIED); do not repeat the call in that case." +
                        "For ordinary apps use manage_app to install/uninstall/clear data/grant permissions.",
                    properties(
                        "action" to enumString(
                            "Action",
                            "list", "enable", "disable", "app_path", "uninstall_updates", "reinstall",
                        ),
                        "package_name" to string("Target package name", 255),
                        "filter" to enumString("list filter: default all", "all", "system", "disabled", "enabled", "third_party"),
                        "keyword" to string("list filtered by a keyword contained in the package name; may be empty", 100),
                        "limit" to integer("list maximum number of results; default 50", 1, 200),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "user_control",
                    "Multi-user management: list users, show the current user, switch, create and remove users. Requires Root." +
                        "Switching is asynchronous; if unconfirmed, check again with action=current instead of repeating the call." +
                        "Removing a user wipes all of its data (the primary user, id=0, cannot be removed)." +
                        "You must obtain explicit user confirmation with ask_user before destructive operations.",
                    properties(
                        "action" to enumString("Action", "list", "current", "switch", "create", "remove"),
                        "user_id" to integer("Target user id for switch/remove", 0, 99),
                        "name" to string("User name for create", 64),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "appops_control",
                    "Control app behaviour at operation granularity (AppOps): read all AppOps records of an app," +
                        "or set a given operation to allow/ignore/deny/default. Requires Root." +
                        "Common ops: COARSE_LOCATION, FINE_LOCATION, CAMERA, RECORD_AUDIO, READ_CONTACTS," +
                        "READ_SMS, READ_CLIPBOARD, SYSTEM_ALERT_WINDOW, WAKE_LOCK." +
                        "set reads back to verify; this is a lower-level control than runtime permissions.",
                    properties(
                        "action" to enumString("Action", "get", "set"),
                        "package_name" to string("Target package name", 255),
                        "op" to string("Operation name, such as CAMERA", 40),
                        "mode" to enumString("Set mode", "allow", "ignore", "deny", "default"),
                    ),
                    "action", "package_name",
                ),
            )
            .put(
                function(
                    "power_control",
                    "Power policy: toggle power saving, keep the screen awake while charging, add/remove apps from the battery optimisation allowlist (Doze)," +
                        "and restart the device. Requires Root. A restart interrupts this conversation, so you must obtain explicit user confirmation with ask_user first," +
                        "and must not issue another restart within the same day.",
                    properties(
                        "action" to enumString("Action", "battery_saver", "stay_awake", "doze_whitelist", "reboot"),
                        "enabled" to boolean("Toggle for battery_saver/stay_awake"),
                        "allow" to boolean("doze_whitelist: true adds to the allowlist / false removes it"),
                        "package_name" to string("Target package name for doze_whitelist", 255),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "ime_control",
                    "Input method management: list enabled IMEs, show the current default IME, switch the default IME." +
                        "list/current need no Root (public API); set requires Root." +
                        "Without Root, set opens the system IME picker and returns ok=false; tell the user to choose manually," +
                        "and do not repeat the call. After switching, confirm with the verified field in the set result.",
                    properties(
                        "action" to enumString("Action", "list", "current", "set"),
                        "id" to string("IME component id for set, such as com.baidu.input/.ImeService", 255),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "connect_wifi",
                    "Fine-grained Wi-Fi operations: scan nearby hotspots, connect to a network (with password), disconnect, forget a saved network." +
                        "Requires Root (cmd wifi). Connecting is asynchronous and the tool waits for confirmation, up to about 8 seconds;" +
                        "when unconfirmed it returns WIFI_CONNECT_UNVERIFIED; do not repeat with the same arguments, and confirm the hotspot with action=scan first." +
                        "Without Root it opens Wi-Fi settings and returns ok=false so the user can connect manually.",
                    properties(
                        "action" to enumString("Action", "scan", "connect", "disconnect", "forget"),
                        "ssid" to string("Hotspot name", 64),
                        "password" to string("Password (required for secured networks, at least 8 characters)", 128),
                        "security" to enumString("Encryption type; default wpa2", "open", "wep", "wpa", "wpa2", "wpa3"),
                        "keyword" to string("Keyword filtering scan results; may be empty", 100),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "set_timezone",
                    "Set the system timezone (IANA name, such as Asia/Shanghai) or toggle automatic time/automatic timezone." +
                        "Timezone requires Root (cmd alarm set-timezone); the automatic-time toggle is writable without Root (it needs the modify-system-settings permission)." +
                        "Writes are read back to verify; the system may overwrite the manual value after network time sync, so do not call again because of that.",
                    properties(
                        "timezone_id" to string("IANA timezone id, such as Asia/Shanghai", 64),
                        "auto_time" to boolean("Use network time automatically"),
                        "auto_timezone" to boolean("Use the network-provided timezone automatically"),
                    ),
                ),
            )
    }

    private fun emptyFunction(name: String, description: String): JSONObject =
        function(name, description, properties())

    private fun limitFunction(name: String, description: String): JSONObject =
        function(
            name,
            description,
            properties("limit" to integer("Maximum number of results; default 10", 1, 30)),
        )

    private fun searchFunction(name: String, description: String): JSONObject =
        function(
            name,
            description,
            properties(
                "query" to string("Optional keyword, at most 200 characters"),
                "limit" to integer("Maximum number of results; default 10", 1, 30),
            ),
        )

    /** 个人数据历史索引查询：可按带时区的 ISO 8601 时间筛选并分页。 */
    private fun personalSearchFunction(name: String, description: String): JSONObject =
        function(
            name,
            "$description Results come from the historical index and may lag; the returned ref reads details.",
            properties(
                "query" to string("Optional keyword, at most 200 characters"),
                "start_time" to string("Inclusive start time, ISO 8601 with timezone offset", 64),
                "end_time" to string("Exclusive end time, ISO 8601 with timezone offset", 64),
                "limit" to integer("Maximum number of results; default 10", 1, 30),
                "offset" to integer("Paging start offset; default 0", 0, 10_000),
                "sort" to enumString("Sort direction; default newest", "newest", "oldest"),
            ),
        )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        vararg required: String,
    ): JSONObject =
        AgentToolSchema.function(
            name = name,
            description = description,
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .also { schema ->
                    if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
                },
        )

    private fun properties(vararg entries: Pair<String, JSONObject>): JSONObject =
        JSONObject().also { target -> entries.forEach { (name, schema) -> target.put(name, schema) } }

    private fun string(description: String, maxLength: Int? = null): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("description", description)
            .also { schema -> maxLength?.let { schema.put("maxLength", it) } }

    private fun boolean(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun integer(description: String, minimum: Int, maximum: Int): JSONObject =
        JSONObject()
            .put("type", "integer")
            .put("minimum", minimum)
            .put("maximum", maximum)
            .put("description", description)

    private fun enumString(description: String, vararg values: String): JSONObject =
        string(description).put("enum", JSONArray(values.toList()))

    private fun stringArray(description: String, vararg values: String): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("items", enumString(description, *values))
            .put("uniqueItems", true)
            .put("maxItems", 7)
            .put("description", description)
}
