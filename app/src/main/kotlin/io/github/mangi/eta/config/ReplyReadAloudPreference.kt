package io.github.mangi.eta.config

import android.content.SharedPreferences

/**
 * 回复自动朗读开关，分三层：
 *
 * - 总开关 [autoRead]：回复生成后是否自动朗读（关闭时仍可手动点朗读按钮）；
 * - 细分开关 [streamSynthesis]：总开关打开时生效，回复输出的同时按已完成的整句开始
 *   朗读，而不是等回复全部结束后才开口；
 * - 后台继续 [continueInBackground]：总开关打开时生效，离开应用（Activity 进入后台）后
 *   是否继续朗读；关闭时沿用历史行为，进入后台立即停止自动朗读。
 *
 * 这些键都只存在于 App 私有配置（[Prefs.LOCAL_AGENT_KEYS]），不回退 remote preferences，
 * UI 写入与运行时读取走同一份默认值表，冷读与监听结果保持一致。
 */
internal object ReplyReadAloudPreference {

    /** 回复生成后是否自动朗读。 */
    val autoRead: Boolean get() = read(Prefs.Keys.AGENT_TTS_AUTO_READ)

    /** 自动朗读是否流式合成：边输出边按整句朗读。 */
    val streamSynthesis: Boolean get() = read(Prefs.Keys.AGENT_TTS_STREAM_SYNTH)

    /** 进入后台后是否继续自动朗读；关闭即「进后台立即停止」。 */
    val continueInBackground: Boolean get() = read(Prefs.Keys.AGENT_TTS_AUTO_READ_BACKGROUND)

    fun setAutoRead(enabled: Boolean) {
        Prefs.putBoolean(Prefs.Keys.AGENT_TTS_AUTO_READ, enabled)
    }

    fun setStreamSynthesis(enabled: Boolean) {
        Prefs.putBoolean(Prefs.Keys.AGENT_TTS_STREAM_SYNTH, enabled)
    }

    fun setContinueInBackground(enabled: Boolean) {
        Prefs.putBoolean(Prefs.Keys.AGENT_TTS_AUTO_READ_BACKGROUND, enabled)
    }

    private fun read(key: String): Boolean {
        val default = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(key)
        return Prefs.localAgentPreferences()?.getBoolean(key, default) ?: default
    }

    /**
     * 只监听总开关：流式合成在下一次快照时自然生效，不需要打断正在进行的朗读。
     * 返回的监听器由调用方在进程生命周期内强引用；未初始化本地配置时返回 null。
     */
    fun observeAutoRead(onChanged: (Boolean) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener? {
        val preferences = Prefs.localAgentPreferences() ?: return null
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.Keys.AGENT_TTS_AUTO_READ) onChanged(autoRead)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }
}
