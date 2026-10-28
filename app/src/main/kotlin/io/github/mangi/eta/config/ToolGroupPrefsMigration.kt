package io.github.mangi.eta.config

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentDeviceToolCatalog
import io.github.mangi.eta.agent.model.AgentToolGroups

/**
 * 工具分组 taxonomy 改版的一次性迁移：旧的 6 个细分开关折叠进 3 个新组开关，
 * 用户原来关掉的档位转成单工具禁用集，语义无损。
 *
 * - device 组 = 旧 direct || 旧 action；只关过其中一档时，把该档的工具写入禁用集。
 * - personal 组 = 旧敏感读总闸；两档细分（个人信息/设备状态）关过的同样写入禁用集。
 * - extensions 组 = 旧自设置开关。
 * - basic 组无前身，保持默认开启。
 *
 * 只在 App 进程跑（[Prefs.initLocal] 与恢复备份后）；Hook 进程通过 reconciler 同步到新值。
 */
internal object ToolGroupPrefsMigration {
    private const val MARKER = "tool_group_taxonomy_migrated_v1"

    // 这里是唯一还在读退役开关的地方，deprecation 警告对它没有意义。
    @Suppress("DEPRECATION")
    fun runOnce(prefs: SharedPreferences?) {
        prefs ?: return
        if (prefs.getBoolean(MARKER, false)) return
        val editor = prefs.edit()

        val direct = prefs.getBoolean(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS, true)
        val action = prefs.getBoolean(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS, true)
        editor.putBoolean(Prefs.Keys.AGENT_DEVICE_TOOLS, direct || action)

        val sensitiveRead = prefs.getBoolean(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS, true)
        val personalReads = prefs.getBoolean(Prefs.Keys.AGENT_PERSONAL_DATA_READ_TOOLS, true)
        val stateReads = prefs.getBoolean(Prefs.Keys.AGENT_DEVICE_STATE_READ_TOOLS, true)
        editor.putBoolean(Prefs.Keys.AGENT_PERSONAL_TOOLS, sensitiveRead)

        editor.putBoolean(
            Prefs.Keys.AGENT_EXTENSIONS_TOOLS,
            prefs.getBoolean(Prefs.Keys.AGENT_SELF_SETTINGS_TOOLS, true),
        )

        // 组开着但档位关过：把该档工具注入禁用集，保持「这些工具不可用」的旧语义。
        val disabled = mutableSetOf<String>()
        disabled += prefs.getString(Prefs.Keys.AGENT_DISABLED_TOOLS, "")
            .orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (direct && !action) disabled += AgentDeviceToolCatalog.sensitiveActionToolNames()
        if (action && !direct) disabled += AgentToolGroups.DEVICE_DIRECT_NAMES
        if (sensitiveRead && !personalReads) disabled += AgentDeviceToolCatalog.personalReadToolNames()
        if (sensitiveRead && !stateReads) disabled += AgentDeviceToolCatalog.deviceStateReadToolNames()
        if (disabled.isNotEmpty()) {
            editor.putString(Prefs.Keys.AGENT_DISABLED_TOOLS, disabled.sorted().joinToString(","))
        }

        editor.putBoolean(MARKER, true)
        editor.apply()
    }
}
