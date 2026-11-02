package io.github.mangi.eta.agent.voice.offline

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本机识别运行策略：只决定模型何时预加载、常驻多久，不影响识别引擎与凭据选择。
 *
 * 与 [OfflineSpeechPack] 共用 `offline_speech` 偏好文件，但键彼此独立；
 * 识别链路仍以「离线包已校验 + 语音对话启用 + 非云识别」为资格条件，
 * 不使用旧的 `enabled` 听写开关。
 */
internal object OfflineSpeechRuntimeSettings {
    data class Settings(
        val prewarmOnStart: Boolean = false,
        val idleReleaseMinutes: Int = DefaultIdleReleaseMinutes,
    )

    val idleReleaseOptions = listOf(5, 10, 30, 0)
    const val DefaultIdleReleaseMinutes = 10

    private val mutable = MutableStateFlow(Settings())
    val state = mutable.asStateFlow()

    private fun prefs(context: Context) =
        context.getSharedPreferences("offline_speech", Context.MODE_PRIVATE)

    fun load(context: Context) {
        val stored = prefs(context)
        val minutes = stored.getInt(KEY_IDLE_RELEASE, DefaultIdleReleaseMinutes)
            .takeIf { it in idleReleaseOptions } ?: DefaultIdleReleaseMinutes
        mutable.value = Settings(
            prewarmOnStart = stored.getBoolean(KEY_PREWARM_ON_START, false),
            idleReleaseMinutes = minutes,
        )
    }

    fun save(context: Context, value: Settings) {
        val minutes = value.idleReleaseMinutes
            .takeIf { it in idleReleaseOptions } ?: DefaultIdleReleaseMinutes
        prefs(context).edit()
            .putBoolean(KEY_PREWARM_ON_START, value.prewarmOnStart)
            .putInt(KEY_IDLE_RELEASE, minutes)
            .apply()
        mutable.value = value.copy(idleReleaseMinutes = minutes)
    }

    const val KEY_PREWARM_ON_START = "prewarm_on_start"
    const val KEY_IDLE_RELEASE = "idle_release_minutes"
}
