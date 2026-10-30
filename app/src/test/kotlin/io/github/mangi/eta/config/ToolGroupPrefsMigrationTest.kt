package io.github.mangi.eta.config

import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import io.github.mangi.eta.agent.model.AgentDeviceToolCatalog
import io.github.mangi.eta.agent.model.AgentToolGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * 分组 taxonomy 改版的一次性迁移：6 个细分开关折叠成 3 个组开关，
 * 用户原来关掉的档位必须变成单工具禁用集——**语义无损**是这里唯一的标准。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@Suppress("DEPRECATION")
class ToolGroupPrefsMigrationTest {
    private fun prefs(): SharedPreferences =
        ApplicationProvider.getApplicationContext<android.app.Application>()
            .getSharedPreferences("migration-test-${UUID.randomUUID()}", android.content.Context.MODE_PRIVATE)

    private fun SharedPreferences.disabled(): Set<String> =
        getString(Prefs.Keys.AGENT_DISABLED_TOOLS, "")!!.split(',')
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun SharedPreferences.group(key: String): Boolean = getBoolean(key, true)

    @Test
    fun untouchedInstallationGetsEveryNewGroupEnabledAndNoDisabledTools() {
        val prefs = prefs()
        ToolGroupPrefsMigration.runOnce(prefs)
        listOf(
            Prefs.Keys.AGENT_BASIC_TOOLS,
            Prefs.Keys.AGENT_DEVICE_TOOLS,
            Prefs.Keys.AGENT_PERSONAL_TOOLS,
            Prefs.Keys.AGENT_EXTENSIONS_TOOLS,
        ).forEach { assertTrue("$it 应默认开启", prefs.group(it)) }
        assertTrue(prefs.disabled().isEmpty())
        assertTrue(prefs.getBoolean("tool_group_taxonomy_migrated_v1", false))
    }

    @Test
    fun nullPreferencesIsANoOp() {
        ToolGroupPrefsMigration.runOnce(null)
    }

    @Test
    fun deviceGroupIsTheUnionOfDirectAndWriteTiers() {
        val both = prefs().apply {
            edit().putBoolean(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS, true)
                .putBoolean(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS, true).apply()
        }
        ToolGroupPrefsMigration.runOnce(both)
        assertTrue(both.group(Prefs.Keys.AGENT_DEVICE_TOOLS))
        assertTrue(both.disabled().isEmpty())

        // 只关写操作：组仍开着，写操作那批工具进禁用集。
        val writeOff = prefs().apply {
            edit().putBoolean(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS, false).apply()
        }
        ToolGroupPrefsMigration.runOnce(writeOff)
        assertTrue(writeOff.group(Prefs.Keys.AGENT_DEVICE_TOOLS))
        assertEquals(AgentDeviceToolCatalog.sensitiveActionToolNames(), writeOff.disabled())

        // 只关直达：对称地注入直达那批。
        val directOff = prefs().apply {
            edit().putBoolean(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS, false).apply()
        }
        ToolGroupPrefsMigration.runOnce(directOff)
        assertTrue(directOff.group(Prefs.Keys.AGENT_DEVICE_TOOLS))
        assertEquals(AgentToolGroups.DEVICE_DIRECT_NAMES, directOff.disabled())

        // 两档都关过：整组关闭，此时再注入禁用集是多余的（组都不下发了）。
        val allOff = prefs().apply {
            edit().putBoolean(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS, false)
                .putBoolean(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS, false).apply()
        }
        ToolGroupPrefsMigration.runOnce(allOff)
        assertEquals(false, allOff.group(Prefs.Keys.AGENT_DEVICE_TOOLS))
        assertTrue(allOff.disabled().isEmpty())
    }

    @Test
    fun personalGroupFollowsTheSensitiveReadMasterAndKeepsTierLosses() {
        val masterOff = prefs().apply {
            edit().putBoolean(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS, false).apply()
        }
        ToolGroupPrefsMigration.runOnce(masterOff)
        assertEquals(false, masterOff.group(Prefs.Keys.AGENT_PERSONAL_TOOLS))
        // 总闸已关，档位差异无从表达，也不该污染禁用集（用户日后重开总闸应恢复原样）。
        assertTrue(masterOff.disabled().isEmpty())

        val readsOff = prefs().apply {
            edit().putBoolean(Prefs.Keys.AGENT_PERSONAL_DATA_READ_TOOLS, false)
                .putBoolean(Prefs.Keys.AGENT_DEVICE_STATE_READ_TOOLS, false).apply()
        }
        ToolGroupPrefsMigration.runOnce(readsOff)
        assertTrue(readsOff.group(Prefs.Keys.AGENT_PERSONAL_TOOLS))
        assertEquals(
            AgentDeviceToolCatalog.personalReadToolNames() +
                AgentDeviceToolCatalog.deviceStateReadToolNames() +
                // 日程读已并入日历组（跟随设备开关），关掉旧档位时仍要一并禁用。
                AgentDeviceToolCatalog.calendarReadToolNames(),
            readsOff.disabled(),
        )
    }

    @Test
    fun extensionsGroupInheritsTheSelfSettingsSwitch() {
        val off = prefs().apply {
            edit().putBoolean(Prefs.Keys.AGENT_SELF_SETTINGS_TOOLS, false).apply()
        }
        ToolGroupPrefsMigration.runOnce(off)
        assertEquals(false, off.group(Prefs.Keys.AGENT_EXTENSIONS_TOOLS))
    }

    @Test
    fun existingDisabledToolsSurviveAndTheResultIsNormalized() {
        val prefs = prefs().apply {
            edit().putString(Prefs.Keys.AGENT_DISABLED_TOOLS, " terminal , wait ")
                .putBoolean(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS, false).apply()
        }
        ToolGroupPrefsMigration.runOnce(prefs)
        val disabled = prefs.disabled()
        assertTrue(disabled.containsAll(setOf("terminal", "wait")))
        assertEquals(
            AgentDeviceToolCatalog.sensitiveActionToolNames() + setOf("terminal", "wait"),
            disabled,
        )
        // 写回的字符串必须已排序去重，否则每次迁移/导出都会改变顺序。
        val stored = prefs.getString(Prefs.Keys.AGENT_DISABLED_TOOLS, "")!!.split(',')
        assertEquals(stored.sorted(), stored)
        assertEquals(stored.size, stored.toSet().size)
    }

    @Test
    fun runningTwiceDoesNotReapplyTheLegacyTiers() {
        val prefs = prefs().apply {
            edit().putBoolean(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS, false).apply()
        }
        ToolGroupPrefsMigration.runOnce(prefs)
        // 用户迁移后又自己打开了那批工具：旧档位值仍是 false，重跑绝不能把它们再关回去。
        prefs.edit().putString(Prefs.Keys.AGENT_DISABLED_TOOLS, "").apply()
        ToolGroupPrefsMigration.runOnce(prefs)
        assertTrue(prefs.disabled().isEmpty())
    }
}
