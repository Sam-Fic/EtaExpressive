package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.AppUpdateOffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 更新提醒的进程级状态。
 *
 * 更新弹窗只在用户「完全冷重启」（进程新建）后检查一次：Activity 因外观切换 `recreate()`、
 * 退到后台再回来、从桌面重新打开已有的 Activity，都不应该再弹一次。
 * 状态放在进程单例而不是 Composable 的 `remember` 或 Activity 级 ViewModel，
 * 才能在 Activity 重建时保持「这次启动已经问过了」的语义。
 */
internal object AppUpdateCoordinator {
    private val _offer = MutableStateFlow<AppUpdateOffer?>(null)

    /** 待展示的更新；null 表示当前没有需要打扰用户的更新。 */
    val offer: StateFlow<AppUpdateOffer?> = _offer.asStateFlow()

    private val startupCheckStarted = AtomicBoolean(false)

    // 进程级作用域：Activity 重建/退出不能取消这次检查，否则 recreate 会重新发起一遍。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 冷启动自动检查：整个进程生命周期内只真正发起一次。
     *
     * 检查失败只记日志，静默放弃——更新检查失败不该打扰用户。
     */
    fun checkOnceOnStartup(context: Context) {
        if (!startupCheckStarted.compareAndSet(false, true)) return
        val application = context.applicationContext
        scope.launch {
            // 启动阶段主线程与网络都很忙，推迟一点再发请求。
            delay(STARTUP_CHECK_DELAY_MS)
            AppUpdateRepository.checkForUpdate(application)
                .onFailure { failure ->
                    AndroidAgentLogger.warn("App update check failed: type=${failure.safeLogType()}")
                }
                .getOrNull()
                ?.let { offer ->
                    _offer.value = offer
                    // 标记“已提醒”，同一版本 24 小时内不再自动弹。
                    AppUpdateRepository.markPrompted(offer)
                }
        }
    }

    /** 用户关掉弹窗（稍后 / 忽略 / 已下载）；本次启动不再自动展示。 */
    fun dismiss() {
        _offer.value = null
    }

    /** 仅测试使用：重置进程级状态。 */
    internal fun resetForTests() {
        _offer.value = null
        startupCheckStarted.set(false)
    }

    private const val STARTUP_CHECK_DELAY_MS = 800L
}
