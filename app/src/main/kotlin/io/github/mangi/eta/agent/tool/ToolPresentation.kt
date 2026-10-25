package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentToolGroupCatalog
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.AdsClick
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ContentPasteGo
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardCommandKey
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.WebAsset
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.icons.SubAgents

/**
 * 工具 UI 呈现元数据的单一数据源：一个工具 id 同时拥有图标与显示名，只此一处维护。
 *
 * 与 `agent/model/AgentToolCatalog`（运行时工具 schema 注册表）不是一回事：那张表决定
 * 「有哪些工具、参数是什么」，本对象只决定「工具在界面上长什么样、叫什么」。
 *
 * 合并前 `ui/components/ToolIcons.kt`（iconForTool，约百条）与
 * `agent/overlay/AgentOverlayText.kt`（toolDisplayNameResource，约五十条）是两张分居
 * 两个包的表，新增工具要改两处、且 overlay 包被 chat UI 反向依赖。现在收敛到 agent 层：
 * UI 与浮窗都查这一张表，新增工具只改一处。
 *
 * 未登记的 id：图标回退 FallbackIcon（`mcp_` 前缀走 MCP 扩展图标），显示名回退 id 原文。
 */
internal object ToolPresentation {

    /** 未登记工具 id 的图标回退值（与迁移前 `iconForTool` 行为一致）。 */
    private val FallbackIcon: ImageVector = Icons.Rounded.Build

    /** 工具显示名的字符串资源；未登记返回 null（调用方回退 id 原文）。 */
    @StringRes
    fun nameRes(toolId: String): Int? = when (toolId) {
        "observe_screen" -> R.string.tool_observe_screen
        "tap" -> R.string.tool_tap
        "tap_element" -> R.string.tool_tap_element
        "tap_area" -> R.string.tool_tap_area
        "long_press" -> R.string.tool_long_press
        "long_press_element" -> R.string.tool_long_press_element
        "swipe" -> R.string.tool_swipe
        "scroll" -> R.string.tool_scroll
        "scroll_element" -> R.string.tool_scroll_element
        "paste_text" -> R.string.tool_paste_text
        "get_clipboard" -> R.string.tool_get_clipboard
        "set_clipboard" -> R.string.tool_set_clipboard
        "input_text" -> R.string.tool_input_text
        "type_text" -> R.string.tool_type_text
        "replace_text" -> R.string.tool_replace_text
        "clear_text" -> R.string.tool_clear_text
        "wait" -> R.string.tool_wait
        "wait_for_text" -> R.string.tool_wait_for_text
        "wait_for_package" -> R.string.tool_wait_for_package
        "search_apps" -> R.string.tool_search_apps
        "get_current_context" -> R.string.tool_current_context
        "launch_app" -> R.string.tool_launch_app
        "open_uri" -> R.string.tool_open_uri
        "browser_use" -> R.string.tool_browser_use
        "web_search" -> R.string.tool_web_search
        "fetch_url" -> R.string.tool_fetch_url
        "delegate_task" -> R.string.tool_delegate_task
        "get_task_result" -> R.string.tool_get_task_result
        "cancel_task" -> R.string.tool_cancel_task
        "memory_get" -> R.string.tool_memory_get
        "memory_write" -> R.string.tool_memory_write
        "press_key" -> R.string.tool_press_key
        "open_system_panel" -> R.string.tool_open_system_panel
        "alarm_manage" -> R.string.tool_alarm_manage
        "schedule_manage" -> R.string.tool_schedule_manage
        "set_timer" -> R.string.tool_set_timer
        "delete_alarm" -> R.string.tool_delete_alarm
        "update_alarm" -> R.string.tool_update_alarm
        "set_alarm_enabled" -> R.string.tool_set_alarm_enabled
        "update_alarm_time" -> R.string.tool_update_alarm_time
        "create_calendar_event" -> R.string.tool_create_calendar_event
        "create_calendar_events" -> R.string.tool_create_calendar_events
        "update_calendar_event" -> R.string.tool_update_calendar_event
        "delete_calendar_event" -> R.string.tool_delete_calendar_event
        "list_calendars" -> R.string.tool_list_calendars
        "read_calendar_event" -> R.string.tool_read_calendar_event
        "create_note" -> R.string.tool_create_note
        "read_note" -> R.string.tool_read_note
        "delete_note" -> R.string.tool_delete_note
        "get_flashlight" -> R.string.tool_get_flashlight
        "set_flashlight" -> R.string.tool_set_flashlight
        "get_sound_state" -> R.string.tool_get_sound_state
        "set_sound_mode" -> R.string.tool_set_sound_mode
        "get_hotspot" -> R.string.tool_get_hotspot
        "set_hotspot" -> R.string.tool_set_hotspot
        "get_display_state" -> R.string.tool_get_display_state
        "set_screen_timeout" -> R.string.tool_set_screen_timeout
        "get_device_state" -> R.string.tool_get_device_state
        "search_bills" -> R.string.tool_search_bills
        "search_todos" -> R.string.tool_search_todos
        "search_calendar_todos" -> R.string.tool_search_calendar_todos
        "search_memory_collections" -> R.string.tool_search_memory_collections
        "search_daily_events" -> R.string.tool_search_daily_events
        "search_flights" -> R.string.tool_search_flights
        "search_hotels" -> R.string.tool_search_hotels
        "search_trains" -> R.string.tool_search_trains
        "read_personal_item" -> R.string.tool_read_personal_item
        "summarize_bills" -> R.string.tool_summarize_bills
        "device_status" -> R.string.tool_device_status
        "set_device_state" -> R.string.tool_set_device_state
        "set_ringer_mode" -> R.string.tool_set_ringer_mode
        "set_do_not_disturb" -> R.string.tool_set_do_not_disturb
        "set_brightness" -> R.string.tool_set_brightness
        "screen_action" -> R.string.tool_screen_action
        "list_installed_apps" -> R.string.tool_list_installed_apps
        "inspect_app" -> R.string.tool_inspect_app
        "manage_app" -> R.string.tool_manage_app
        "manage_file" -> R.string.tool_manage_file
        "system_property" -> R.string.tool_system_property
        "display_control" -> R.string.tool_display_control
        "deep_launch" -> R.string.tool_deep_launch
        "package_control" -> R.string.tool_package_control
        "user_control" -> R.string.tool_user_control
        "appops_control" -> R.string.tool_appops_control
        "power_control" -> R.string.tool_power_control
        "ime_control" -> R.string.tool_ime_control
        "connect_wifi" -> R.string.tool_connect_wifi
        "set_timezone" -> R.string.tool_set_timezone
        "search_settings" -> R.string.tool_search_settings
        "dumpsys_diag" -> R.string.tool_dumpsys_diag
        "network_info" -> R.string.tool_network_info
        "wifi_credentials" -> R.string.tool_wifi_credentials
        "media_control" -> R.string.tool_media_control
        "set_volume" -> R.string.tool_set_volume
        "top_memory_apps" -> R.string.tool_top_memory_apps
        "top_storage_apps" -> R.string.tool_top_storage_apps
        "read_sms_code" -> R.string.tool_read_sms_code
        "recent_notifications" -> R.string.tool_recent_notifications
        "get_setting" -> R.string.tool_get_setting
        "set_setting" -> R.string.tool_set_setting
        "app_state_control" -> R.string.tool_app_state_control
        "get_logcat" -> R.string.tool_get_logcat
        "terminal" -> R.string.tool_terminal
        "run_command" -> R.string.tool_run_command
        "read_file" -> R.string.tool_read_file
        "write_file" -> R.string.tool_write_file
        "list_directory" -> R.string.tool_list_directory
        "stat_file" -> R.string.tool_stat_file
        "edit_file" -> R.string.tool_edit_file
        "glob_files" -> R.string.tool_glob_files
        "grep_files" -> R.string.tool_grep_files
        "eta_get_settings" -> R.string.tool_eta_get_settings
        "eta_set_settings" -> R.string.tool_eta_set_settings
        "eta_manage_models" -> R.string.tool_eta_manage_models
        "eta_manage_subagents" -> R.string.tool_eta_manage_subagents
        "eta_manage_history" -> R.string.tool_eta_manage_history
        "eta_configure_mcp" -> R.string.tool_eta_configure_mcp
        AgentToolGroupCatalog.USE_GROUP -> R.string.tool_eta_get_settings
        "eta_author_tool" -> R.string.tool_eta_author_tool
        "eta_list_recipes" -> R.string.tool_eta_list_recipes
        "semantic_search_media" -> R.string.tool_semantic_search_media
        "device_actions" -> R.string.tool_device_actions
        // OpenAI Responses 托管工具：provider 只透传协议类型，展示名在此统一本地化。
        "web_search_call" -> R.string.hosted_tool_web_search
        "file_search_call" -> R.string.hosted_tool_file_search
        "code_interpreter_call" -> R.string.hosted_tool_code_interpreter
        "computer_call" -> R.string.hosted_tool_computer
        "image_generation_call" -> R.string.hosted_tool_image_generation
        "mcp_call" -> R.string.hosted_tool_mcp
        else -> null
    }

    /** 工具图标；未登记的 id 回退 FallbackIcon。 */
    fun icon(toolId: String): ImageVector = when (toolId) {
        "start_virtual_session", "keep_virtual_result", "finish_virtual_session", "inspect_virtual_backend" -> Icons.Rounded.ScreenshotMonitor
        "observe" -> Icons.Rounded.DocumentScanner
        "observe_screen" -> Icons.Rounded.DocumentScanner
        "click" -> Icons.Rounded.AdsClick
        "tap" -> Icons.Rounded.AdsClick
        "tap_element" -> Icons.Rounded.AdsClick
        "tap_area" -> Icons.Rounded.MyLocation
        "long_press" -> Icons.Rounded.TouchApp
        "long_press_element" -> Icons.Rounded.TouchApp
        "swipe" -> Icons.Rounded.OpenWith
        "scroll" -> Icons.Rounded.SwapVert
        "scroll_element" -> Icons.Rounded.SwapVert
        "clipboard" -> Icons.Rounded.ContentPasteGo
        "paste_text" -> Icons.Rounded.ContentPasteGo
        "get_clipboard" -> Icons.Rounded.ContentPaste
        "set_clipboard" -> Icons.Rounded.ContentPaste
        "input_text" -> Icons.Rounded.Keyboard
        "type_text" -> Icons.Rounded.Keyboard
        "replace_text" -> Icons.Rounded.FindReplace
        "clear_text" -> Icons.AutoMirrored.Rounded.Backspace
        "wait" -> Icons.Rounded.Schedule
        "wait_text" -> Icons.Rounded.Schedule
        "wait_for_text" -> Icons.Rounded.Schedule
        "wait_for_package" -> Icons.Rounded.Schedule
        "search_apps" -> Icons.Rounded.Search
        "get_current_context" -> Icons.Rounded.Smartphone
        "open_app" -> Icons.Rounded.Apps
        "launch_app" -> Icons.Rounded.Apps
        "open_uri" -> Icons.AutoMirrored.Rounded.OpenInNew
        "browser_use" -> Icons.Rounded.Language
        "网页浏览" -> Icons.Rounded.Language
        "web_search", "web_search_call", "网页搜索" -> Icons.Rounded.TravelExplore
        "browser_read", "fetch_url" -> Icons.AutoMirrored.Rounded.MenuBook
        "browser_interact" -> Icons.Rounded.AdsClick
        "browser_screenshot" -> Icons.Rounded.ScreenshotMonitor
        "file_search", "file_search_call", "文件搜索" -> Icons.AutoMirrored.Rounded.ManageSearch
        "code_interpreter", "code_interpreter_call", "代码执行" -> Icons.Rounded.Terminal
        "computer", "computer_call", "计算机操作" -> Icons.Rounded.Computer
        "image_generation", "image_generation_call", "图像生成" -> Icons.Rounded.Image
        "mcp_call", "MCP 工具" -> Icons.Rounded.Extension
        "delegate_task" -> SubAgents
        "get_task_result" -> SubAgents
        "cancel_task" -> SubAgents
        "continue_task", "manage_agent_workspace" -> SubAgents
        "memory_get" -> Icons.Rounded.Psychology
        "memory_write" -> Icons.Rounded.Psychology
        "press_key" -> Icons.Rounded.KeyboardCommandKey
        "open_system_panel" -> Icons.Rounded.WebAsset
        "read_image" -> Icons.Rounded.Image
        "schedule_manage" -> Icons.Rounded.CalendarMonth
        "alarm_manage" -> Icons.Rounded.Alarm
        "set_timer" -> Icons.Rounded.Alarm
        "list_alarms", "list_active_timers" -> Icons.Rounded.Alarm
        "delete_alarm", "update_alarm" -> Icons.Rounded.Alarm
        "set_alarm_enabled", "update_alarm_time" -> Icons.Rounded.Alarm
        "create_calendar_event", "update_calendar_event", "delete_calendar_event" -> Icons.Rounded.CalendarMonth
        "create_calendar_events", "list_calendars", "read_calendar_event" -> Icons.Rounded.CalendarMonth
        "create_note", "read_note", "delete_note" -> Icons.AutoMirrored.Rounded.StickyNote2
        "get_flashlight", "set_flashlight" -> Icons.Rounded.FlashlightOn
        "get_sound_state", "set_sound_mode" -> Icons.AutoMirrored.Rounded.VolumeUp
        "get_hotspot", "set_hotspot" -> Icons.Rounded.Wifi
        "get_display_state", "set_screen_timeout" -> Icons.Rounded.Tune
        "get_device_state" -> Icons.Rounded.Smartphone
        "device_status" -> Icons.Rounded.Smartphone
        "set_device_state" -> Icons.Rounded.Smartphone
        "get_device_environment" -> Icons.Rounded.Smartphone
        "set_ringer_mode" -> Icons.AutoMirrored.Rounded.VolumeUp
        "set_do_not_disturb" -> Icons.Rounded.Notifications
        "set_brightness" -> Icons.Rounded.Tune
        "screen_action" -> Icons.Rounded.Bolt
        "list_installed_apps" -> Icons.Rounded.Apps
        "inspect_app" -> Icons.Rounded.Info
        "manage_app" -> Icons.Rounded.Apps
        "manage_file" -> Icons.Rounded.Folder
        "system_property" -> Icons.Rounded.Tune
        "display_control" -> Icons.Rounded.AspectRatio
        "deep_launch" -> Icons.Rounded.RocketLaunch
        "package_control" -> Icons.Rounded.Apps
        "user_control" -> Icons.Rounded.People
        "appops_control" -> Icons.Rounded.Lock
        "power_control" -> Icons.Rounded.BatteryChargingFull
        "ime_control" -> Icons.Rounded.Keyboard
        "connect_wifi" -> Icons.Rounded.Wifi
        "set_timezone" -> Icons.Rounded.Schedule
        "search_settings" -> Icons.AutoMirrored.Rounded.ManageSearch
        "dumpsys_diag" -> Icons.Rounded.MonitorHeart
        "network_info" -> Icons.Rounded.Wifi
        "wifi_credentials" -> Icons.Rounded.Wifi
        "media_control" -> Icons.Rounded.PlayArrow
        "text_to_speech" -> Icons.Rounded.RecordVoiceOver
        "set_volume" -> Icons.AutoMirrored.Rounded.VolumeUp
        "device_actions" -> Icons.Rounded.Bolt
        "top_memory_apps" -> Icons.Rounded.Layers
        "top_storage_apps" -> Icons.Rounded.Layers
        "read_sms_code" -> Icons.Rounded.Key
        "recent_notifications" -> Icons.Rounded.Notifications
        "search_notification_history" -> Icons.Rounded.Notifications
        "get_setting" -> Icons.Rounded.Settings
        "set_setting" -> Icons.Rounded.Settings
        "app_state_control" -> Icons.Rounded.AdminPanelSettings
        "get_logcat" -> Icons.Rounded.Description
        "get_current_location", "search_saved_places" -> Icons.Rounded.LocationOn
        "get_health_summary" -> Icons.Rounded.MonitorHeart
        "recent_app_activity", "app_usage_summary" -> Icons.Rounded.Insights
        "search_calendar_events" -> Icons.Rounded.CalendarMonth
        "search_contacts" -> Icons.Rounded.Contacts
        "search_call_history" -> Icons.Rounded.Phone
        "ask_user", "search_messages" -> Icons.Rounded.ChatBubble
        "search_media", "search_qq_chat_images", "search_wechat_chat_images" -> Icons.Rounded.Image
        "semantic_search_media" -> Icons.Rounded.TravelExplore
        "search_audio" -> Icons.Rounded.MusicNote
        "search_recordings", "search_coloros_recordings", "search_recording_summaries" -> Icons.Rounded.Mic
        "search_files" -> Icons.Rounded.FolderOpen
        "search_downloads" -> Icons.Rounded.Download
        "search_clipboard_history" -> Icons.Rounded.ContentPaste
        "search_coloros_notes" -> Icons.AutoMirrored.Rounded.StickyNote2
        "search_notes" -> Icons.AutoMirrored.Rounded.StickyNote2
        "search_coloros_memories" -> Icons.Rounded.Psychology
        "search_personal_orders" -> Icons.Rounded.ShoppingBag
        "search_bills", "summarize_bills" -> Icons.Rounded.ShoppingBag
        "search_todos" -> Icons.Rounded.EditNote
        "search_calendar_todos" -> Icons.Rounded.CalendarMonth
        "search_memory_collections" -> Icons.Rounded.Layers
        "search_daily_events" -> Icons.Rounded.History
        "search_flights", "search_trains" -> Icons.Rounded.TravelExplore
        "search_hotels" -> Icons.Rounded.Inventory2
        "read_personal_item" -> Icons.Rounded.Description
        "terminal" -> Icons.Rounded.Terminal
        "terminal_job" -> Icons.Rounded.Terminal
        "run_command" -> Icons.Rounded.Terminal
        "read_file" -> Icons.Rounded.Description
        "write_file" -> Icons.Rounded.EditNote
        "list_directory" -> Icons.Rounded.FolderOpen
        "stat_file" -> Icons.Rounded.Info
        "edit_file" -> Icons.Rounded.FindReplace
        "glob_files" -> Icons.Rounded.FolderOpen
        "grep_files" -> Icons.Rounded.Search
        "eta_get_settings" -> Icons.Rounded.Tune
        "eta_set_settings" -> Icons.Rounded.ManageAccounts
        "eta_manage_models" -> Icons.Rounded.SmartToy
        "eta_manage_subagents" -> SubAgents
        "eta_manage_history" -> Icons.Rounded.History
        "eta_configure_mcp" -> Icons.Rounded.Extension
        AgentToolGroupCatalog.USE_GROUP -> Icons.Rounded.Widgets
        "eta_author_tool" -> Icons.Rounded.AutoFixHigh
        "eta_list_recipes" -> Icons.Rounded.Inventory2
        "skills_list", "skills_list_curated" -> Icons.AutoMirrored.Rounded.MenuBook
        "skills_read", "skills_read_resource" -> Icons.Rounded.Description
        "skills_inspect_github" -> Icons.Rounded.Code
        "skills_install_from_github" -> Icons.Rounded.Download
        "skills_install_local" -> Icons.Rounded.FolderOpen
        else -> if (toolId.startsWith("mcp_")) {
            Icons.Rounded.Extension
        } else if (toolId.startsWith("recipe_")) {
            Icons.Rounded.AutoFixHigh
        } else {
            FallbackIcon
        }
    }

    /** 显示名：有本地化资源用资源，否则回退工具 id 原文。 */
    fun displayName(resources: Resources, toolId: String): String =
        nameRes(toolId)?.let { resources.getString(it) } ?: toolId
}
