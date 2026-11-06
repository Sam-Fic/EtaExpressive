package io.github.mangi.eta.ui.haptics

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Display
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用是否有界面在主屏上可见。只数主屏上的 Activity，副屏锚点不算前台。
 * 没装上（单测或非主进程）时一律当作前台，不改变原有震动。
 */
internal object AppForeground : Application.ActivityLifecycleCallbacks {
    private val visible = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    @Volatile
    private var installed = false

    /**
     * 主屏可见性从无到有 / 从有到无的一次性槽位回调（如执行通知的前台抑制）。
     * 可能从任意线程触发，消费方自行切线程；只支持一个消费方。
     */
    @Volatile
    var onVisibilityChanged: ((Boolean) -> Unit)? = null

    private fun notifyVisibilityChanged(visibleNow: Boolean) {
        onVisibilityChanged?.invoke(visibleNow)
    }

    val isForeground: Boolean
        get() = !installed || visible.isNotEmpty()

    fun install(application: Application) {
        if (installed) return
        installed = true
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        val displayId = runCatching { activity.display?.displayId }.getOrNull() ?: Display.DEFAULT_DISPLAY
        if (displayId == Display.DEFAULT_DISPLAY && visible.add(System.identityHashCode(activity)) && visible.size == 1) {
            notifyVisibilityChanged(true)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        if (visible.remove(System.identityHashCode(activity)) && visible.isEmpty()) {
            notifyVisibilityChanged(false)
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        visible -= System.identityHashCode(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
