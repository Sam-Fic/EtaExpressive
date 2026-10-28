package io.github.mangi.eta.data.datastore

import io.github.mangi.eta.EtaApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「忽略此版本」的持久化状态：冷启动会不会再弹一次完全取决于它。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
class UpdateReminderSettingsTest {
    @Test
    fun dismissedVersionRoundTrips() = runBlocking {
        val before = SettingsDataStore.updateDismissedVersion()
        try {
            SettingsDataStore.setUpdateDismissedVersion("5.3.16")
            assertEquals("5.3.16", SettingsDataStore.updateDismissedVersion())

            // 空字符串表示清空忽略状态。
            SettingsDataStore.setUpdateDismissedVersion("")
            assertEquals("", SettingsDataStore.updateDismissedVersion())
        } finally {
            SettingsDataStore.setUpdateDismissedVersion(before)
        }
    }

    @Test
    fun dismissingVersionDoesNotResetOtherSettings() = runBlocking {
        val before = SettingsDataStore.settings()
        val lastPromptBefore = SettingsDataStore.updateLastPrompt()
        try {
            SettingsDataStore.updateSettings { it.copy(memoryEnabled = false) }
            SettingsDataStore.setUpdateDismissedVersion("5.3.16")

            val after = SettingsDataStore.settings()
            assertEquals(false, after.memoryEnabled)
            assertEquals("5.3.16", SettingsDataStore.updateDismissedVersion())
        } finally {
            SettingsDataStore.updateSettings { before }
            SettingsDataStore.setUpdateDismissedVersion("")
            SettingsDataStore.setUpdateLastPrompt(
                lastPromptBefore.first,
                lastPromptBefore.second,
            )
        }
    }

    @Test
    fun lastPromptRoundTrips() = runBlocking {
        val before = SettingsDataStore.updateLastPrompt()
        try {
            val now = System.currentTimeMillis()
            SettingsDataStore.setUpdateLastPrompt("5.3.16", now)
            assertEquals("5.3.16" to now, SettingsDataStore.updateLastPrompt())

            // 空版本 + 0 时间表示清空提醒节流。
            SettingsDataStore.setUpdateLastPrompt("", 0L)
            assertEquals("" to 0L, SettingsDataStore.updateLastPrompt())
        } finally {
            SettingsDataStore.setUpdateLastPrompt(before.first, before.second)
        }
    }
}
