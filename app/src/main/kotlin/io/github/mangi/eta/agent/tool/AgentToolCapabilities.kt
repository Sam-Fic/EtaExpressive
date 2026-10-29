package io.github.mangi.eta.agent.tool

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.agent.device.AgentNotificationHistoryService
import io.github.mangi.eta.agent.device.RootAccess
import java.util.Locale
import org.json.JSONArray

/** 每轮冻结的运行条件；不包含用户开关，也不触发授权请求。 */
internal data class AgentToolCapabilities(
    val rootAvailable: Boolean,
    val lsposedAvailable: Boolean = false,
    val accessibilityAvailable: Boolean = true,
    val accessibilityRecoveryAvailable: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val usageAllowed: Boolean = true,
    val locationAllowed: Boolean = true,
    val calendarReadable: Boolean = true,
    val calendarWritable: Boolean = true,
    val colorOs: Boolean = true,
    val virtualDisplay: Boolean = false,
    /** Per-round read-only command detection; null when unknown (tests, detection failure). */
    val shellTools: AgentShellToolAvailability.Snapshot? = null,
) {
    fun unavailableCode(name: String): String? {
        val requirement = AgentToolRequirements.find(name) ?: return "UNKNOWN_TOOL"
        if (requirement.rootRequirement == RootRequirement.REQUIRED && !rootAvailable) return "ROOT_REQUIRED"
        if (requirement.lsposedRequirement == LsposedRequirement.REQUIRED && !lsposedAvailable) return "LSPOSED_REQUIRED"
        if (requirement.colorOs && !colorOs) return "DEVICE_UNSUPPORTED"
        if (requirement.accessibility && !(virtualDisplay && rootAvailable && io.github.mangi.eta.agent.device.AgentTaskSurface.isTraditionalScreenGuiTool(name)) && !accessibilityAvailable && !accessibilityRecoveryAvailable) {
            return "ACCESSIBILITY_UNAVAILABLE"
        }
        return when (requirement.systemAccess) {
            ToolSystemAccess.CALENDAR_READ -> if (rootAvailable || calendarReadable) null else "CALENDAR_PERMISSION_REQUIRED"
            ToolSystemAccess.CALENDAR_WRITE -> if (rootAvailable || calendarWritable) null else "CALENDAR_PERMISSION_REQUIRED"
            ToolSystemAccess.NONE -> null
            ToolSystemAccess.NOTIFICATIONS -> if (notificationsAllowed ||
                (rootAvailable && (name == "recent_notifications" ||
                    (name == "search_personal_orders" && colorOs)))
            ) null else "NOTIFICATION_ACCESS_REQUIRED"
            ToolSystemAccess.USAGE -> if (usageAllowed) null else "APP_USAGE_ACCESS_REQUIRED"
            ToolSystemAccess.LOCATION -> if (locationAllowed) null else "LOCATION_PERMISSION_REQUIRED"
        }
    }

    /**
     * 单个工具此刻能否真的出现在下发的目录里。
     *
     * 目录投影与 tools_use_group 的打开回执必须用同一判定，否则回执会承诺一批
     * 永远不会出现的工具名，模型照着调用只会连续被拒。
     */
    fun canDeliver(name: String): Boolean {
        if (!virtualDisplay && name in VIRTUAL_DISPLAY_TOOLS) return false
        return unavailableCode(name) == null
    }

    fun project(tools: JSONArray): JSONArray {
        val rootProjected = AgentToolRequirements.project(tools, rootAvailable)
        return JSONArray().also { visible ->
            for (index in 0 until rootProjected.length()) {
                val tool = rootProjected.getJSONObject(index)
                val name = tool.getJSONObject("function").getString("name")
                if (canDeliver(name)) visible.put(tool)
            }
        }
    }

    companion object {
        private val VIRTUAL_DISPLAY_TOOLS = setOf(
            "start_virtual_session",
            "keep_virtual_result",
            "finish_virtual_session",
            "inspect_virtual_backend",
        )
        fun isColorOsDevice(): Boolean = Build.MANUFACTURER.lowercase(Locale.ROOT) in
            setOf("oppo", "oneplus", "realme")

        fun capture(context: Context): AgentToolCapabilities = AgentToolCapabilities(
            rootAvailable = RootAccess.isGranted,
            lsposedAvailable = EtaApp.serviceInstance != null,
            accessibilityAvailable = AgentAccessibilityService.isAvailable(),
            accessibilityRecoveryAvailable = EtaApp.serviceInstance != null &&
                AccessibilityProtectionClient.isEnabled(context),
            notificationsAllowed = AgentNotificationHistoryService.isEnabled(context),
            usageAllowed = AgentPersonalContextTools.hasUsageAccess(context),
            locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED &&
                (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED),
            colorOs = isColorOsDevice(),
            calendarReadable = io.github.mangi.eta.agent.device.CalendarPermissions.granted(context, write = false),
            calendarWritable = io.github.mangi.eta.agent.device.CalendarPermissions.granted(context, write = true),
        )

        /**
         * Capabilities for an agent round, including read-only command detection. Performs file
         * metadata IO, so call it only from the runtime worker thread, never from UI composition.
         */
        fun captureForRound(context: Context): AgentToolCapabilities =
            capture(context).copy(shellTools = AgentShellToolAvailability.detect(context))
    }
}
