package io.github.mangi.eta.agent.overlay

import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.agent.runtime.AgentEvent

/**
 * Decides when the system-level operation overlay should become visible.
 *
 * Chat, reasoning, shell diagnostics, file reads, skill reads and app search
 * all have good homes in the main conversation UI. The global overlay is
 * reserved for tools that actively inspect or drive the foreground Android
 * interface. Tool names alone cannot distinguish foreground and virtual runs;
 * runtime callers must also pass the originating session's frozen surface mode.
 */
internal object AgentOverlayVisibilityPolicy {
    /**
     * 前台驾驶沿用光球/气泡；后台任务只有虚拟屏真正激活时才允许显示顶部胶囊。
     * 后台运行尚未创建副屏时保持完全安静，避免仅凭“选了后台模式”就弹出浮窗。
     */
    fun allowsOverlay(
        taskSurfaceMode: AgentTaskSurfaceMode,
        virtualDisplayActive: Boolean = false,
    ): Boolean = taskSurfaceMode == AgentTaskSurfaceMode.FOREGROUND || virtualDisplayActive

    /** 运行开始时：前台，或还没选的“每次询问”（可能选前台），都先准备好悬浮窗状态。 */
    fun mayUseOverlay(
        taskSurfaceMode: AgentTaskSurfaceMode,
        virtualDisplayActive: Boolean = false,
    ): Boolean = taskSurfaceMode != AgentTaskSurfaceMode.BACKGROUND || virtualDisplayActive

    fun shouldRevealFor(
        event: AgentEvent,
        taskSurfaceMode: AgentTaskSurfaceMode,
        virtualDisplayActive: Boolean = false,
    ): Boolean = allowsOverlay(taskSurfaceMode, virtualDisplayActive) && shouldRevealFor(event)

    fun shouldDismissEntrySurfaceFor(event: AgentEvent, taskSurfaceMode: AgentTaskSurfaceMode): Boolean =
        allowsOverlay(taskSurfaceMode) && shouldDismissEntrySurfaceFor(event)

    fun shouldShowResultCard(
        taskSurfaceMode: AgentTaskSurfaceMode,
        hasExecutedForegroundTool: Boolean,
        virtualDisplayActive: Boolean = false,
    ): Boolean = allowsOverlay(taskSurfaceMode, virtualDisplayActive) && hasExecutedForegroundTool

    fun shouldRevealFor(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.AssistantBlockStart ->
            event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL &&
                event.name.isForegroundDrivingTool()
        is AgentEvent.AssistantBlockEnd ->
            event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL &&
                event.name.isForegroundDrivingTool()
        is AgentEvent.AssistantReceived -> event.toolNames.any { it.isForegroundDrivingTool() }
        is AgentEvent.ToolStarted -> event.name.isForegroundDrivingTool()
        is AgentEvent.ToolFinished -> event.name.isForegroundOperationTool()
        is AgentEvent.ToolImagesAttached -> event.toolName.isForegroundOperationTool()
        else -> false
    }

    fun shouldDismissEntrySurfaceFor(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.AssistantBlockStart ->
            event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL &&
                event.name.requiresEntrySurfaceDismissal()
        is AgentEvent.AssistantBlockEnd ->
            event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL &&
                event.name.requiresEntrySurfaceDismissal()
        is AgentEvent.AssistantReceived -> event.toolNames.any { it.requiresEntrySurfaceDismissal() }
        is AgentEvent.ToolStarted -> event.name.requiresEntrySurfaceDismissal()
        is AgentEvent.ToolFinished -> event.name.requiresEntrySurfaceDismissal()
        is AgentEvent.ToolImagesAttached -> event.toolName.requiresEntrySurfaceDismissal()
        else -> false
    }

    internal fun isForegroundOperationTool(name: String?): Boolean =
        name.isForegroundOperationTool()

    internal fun requiresEntrySurfaceDismissal(name: String?): Boolean =
        name.requiresEntrySurfaceDismissal()

    internal fun shouldRecordForegroundExecution(
        event: AgentEvent,
        entrySurfaceReady: Boolean,
        taskSurfaceMode: AgentTaskSurfaceMode = AgentTaskSurfaceMode.FOREGROUND,
    ): Boolean =
        allowsOverlay(taskSurfaceMode) &&
            entrySurfaceReady &&
            event is AgentEvent.ToolStarted &&
            event.name.isForegroundOperationTool()

    private fun String?.isForegroundOperationTool(): Boolean =
        this?.trim()?.lowercase() in foregroundOperationTools

    private fun String?.isForegroundDrivingTool(): Boolean =
        this?.trim()?.lowercase() in foregroundDrivingTools

    private fun String?.requiresEntrySurfaceDismissal(): Boolean =
        this?.trim()?.lowercase() in entrySurfaceDismissalTools

    private val foregroundDrivingTools = setOf(
        "launch_app",
        "open_uri",
        "tap",
        "tap_area",
        "tap_element",
        "long_press",
        "long_press_element",
        "swipe",
        "scroll",
        "scroll_element",
        "input_text",
        "type_text",
        "replace_text",
        "clear_text",
        "paste_text",
        "press_key",
        "open_system_panel",
        // 会拉起系统界面（设置面板 / 安装器 / 卸载器 / 电源菜单）的工具同样需要浮层避让。
        "set_device_state",
        "manage_app",
        "screen_action",
    )

    private val foregroundOperationTools = setOf(
        "observe_screen",
        *foregroundDrivingTools.toTypedArray(),
    )

    /**
     * 需要撤销入口浮层（聊天输入面）的工具：要么 Eta 在前台驾驶界面，要么把用户交给
     * 系统应用/系统界面去完成确认（闹钟、日历预填、设置面板、安装/卸载界面、电源菜单）。
     * 后者不揭示操作浮层——那是用户的手动确认环节，不是 Eta 在驾驶。
     */
    private val entrySurfaceDismissalTools =
        foregroundOperationTools + setOf(
            "set_alarm",
            "set_timer",
            "create_calendar_event",
            "set_device_state",
            "manage_app",
            "screen_action",
            // 免 Root 降级路径会打开系统界面让用户确认：Wi-Fi 面板、输入法选择器、日期时间设置。
            "connect_wifi",
            "ime_control",
            "set_timezone",
        )
}
