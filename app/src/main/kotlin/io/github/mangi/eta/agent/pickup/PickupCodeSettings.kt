// 取餐码功能的私有设置。
// 走独立 SharedPreferences 文件而不是 config/Prefs 全局键空间：取餐码是自包含
// 子功能，其开关（拦截）只在管理页与通知层读写，进全局 Prefs.Keys 需要同步
// 设置系统与契约测试，得不偿失。
// 截图后端与通知呈现方式不在此存储：它们是应用级能力——截图后端上收
// config/Prefs 本地 Agent 配置（Keys.ASSISTANT_SCREENSHOT_BACKEND），
// 通知呈现方式上收 Keys.LIVE_ACTIVITY_STYLE（实时活动页「通知方式」）。

package io.github.mangi.eta.agent.pickup

import android.content.Context
import android.content.SharedPreferences

internal object PickupCodeSettings {
    private const val PREFS_NAME = "pickup_code_prefs"

    private const val KEY_INTERCEPTION_ENABLED = "interception_enabled"

    /** 「记取码」chip 走纯本地 OCR（不经模型对话）；默认关=识别结果交由模型确认保存。 */
    private const val KEY_LOCAL_OCR_ONLY = "local_ocr_only"

    /** 通知监听自动拦截总开关（默认开：功能入口即为此设计）。 */
    @Volatile
    var interceptionEnabled: Boolean = true
        private set

    /** 「记取码」chip 纯本地识别开关（澎湃记同口径：识别→入库→提醒零模型往返）。 */
    @Volatile
    var localOcrOnly: Boolean = false
        private set

    /** 磁盘设置是否已加载进内存（后台链路读值前先 [ensureLoaded]）。 */
    @Volatile
    private var loaded = false

    /** 后台链路（通知观察者）读值前的惰性加载；UI 打开管理页时也会显式刷新。 */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        load(context)
    }

    fun load(context: Context) {
        val prefs = prefs(context)
        synchronized(this) {
            interceptionEnabled = prefs.getBoolean(KEY_INTERCEPTION_ENABLED, true)
            localOcrOnly = prefs.getBoolean(KEY_LOCAL_OCR_ONLY, false)
            loaded = true
        }
    }

    fun setInterceptionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_INTERCEPTION_ENABLED, enabled).apply()
        interceptionEnabled = enabled
    }

    fun setLocalOcrOnly(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOCAL_OCR_ONLY, enabled).apply()
        localOcrOnly = enabled
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
