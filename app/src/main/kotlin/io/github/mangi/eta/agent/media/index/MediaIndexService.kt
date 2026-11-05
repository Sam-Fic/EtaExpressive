package io.github.mangi.eta.agent.media.index

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 媒体索引后台服务：把 [MediaIndexer.index] 的长任务搬进前台服务，
 * 索引不再依赖设置页协程存活——退到后台、熄屏后仍持局部唤醒锁跑完。
 *
 * 生命周期：start → 建渠道/起前台 → 取唤醒锁 → 跑索引（按 [MediaIndexer.state] 刷通知）→
 * 成功/失败/取消后放唤醒锁、stopForeground(REMOVE)、stopSelf。
 * 索引按存储的 [IndexMode] 档位启动（[EmbeddingModelStore.indexMode]），
 * 自动更新路径（[MediaIndexAutoUpdate]）可经 [start] 的 mode 参数覆盖为低耗档；
 * 通知摘要带档位名（如「低耗后台 · 索引中…」）。
 */
internal class MediaIndexService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var indexJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundActive = false
    private var publishedText: String? = null
    private var publishedPercent: Int = -1

    /** 本轮启动时锁定的索引档位；通知摘要展示用，进行中切档不改变本轮行为。 */
    private var mode: IndexMode = IndexMode.DEFAULT

    override fun onCreate() {
        super.onCreate()
        instance = this
        mode = EmbeddingModelStore.indexMode(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.media_index_channel), NotificationManager.IMPORTANCE_LOW),
        )
        ensureForeground()
    }

    private fun ensureForeground() {
        if (foregroundActive) return
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            foregroundActive = true
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("Media index service foreground failed: type=${failure.safeLogType()}")
            finish()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 停止：取消索引协程并收尾；MediaIndexer 的 finally 会把 running 复位。
            indexJob?.cancel()
            indexJob = null
            finish()
            return START_NOT_STICKY
        }
        ensureForeground()
        if (indexJob?.isActive == true) {
            // 幂等：索引已在跑时重复启动只补一次通知。
            refreshNotification()
            return START_NOT_STICKY
        }
        // 档位：显式覆盖（自动更新固定低耗档）优先，否则按存储的索引模式偏好；
        // 本轮跑的是启动时刻的档位，之后切档下一轮才生效。
        mode = intent?.getStringExtra(EXTRA_MODE)?.let { IndexMode.from(it) }
            ?: EmbeddingModelStore.indexMode(applicationContext)
        acquireWakeLock()
        publishedText = null
        indexJob = scope.launch {
            // 进度观察随任务起停：只在通知文本变化时刷新，避开系统通知限流。
            val watcher = launch { MediaIndexer.state.collect { refreshNotification() } }
            try {
                MediaIndexer.index(applicationContext, mode)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                AndroidAgentLogger.warn("Media index failed: type=${failure.safeLogType()}")
            } finally {
                watcher.cancel()
            }
            // 先摘 Job 引用再收尾：finish() 的守卫靠 indexJob 判活跃，
            // 在协程体内部直接调会被判成「新一轮已接手」而跳过撤通知，导致通知常驻。
            indexJob = null
            finish()
        }
        refreshNotification()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        // 兜底放锁：异常销毁路径也不泄漏唤醒锁（release 幂等）。
        releaseWakeLock()
        super.onDestroy()
    }

    /** 收尾（幂等）：放唤醒锁 → 撤前台通知 → stopSelf；新一轮索引已接手时不动。 */
    private fun finish() {
        if (indexJob?.isActive == true) return
        releaseWakeLock()
        if (foregroundActive) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            // 兜底：个别 ROM 上 notify() 刷过同 ID 后 stopForeground 撤不干净，
            // 再显式 cancel 一次，确保索引结束/停止后通知不残留。
            runCatching {
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
        }
        stopSelf()
    }

    /** 局部唤醒锁：熄屏/退后台仍占 CPU 跑完；30 分钟兜底超时防泄漏。 */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        lock.setReferenceCounted(false)
        lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLock = lock
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null
        if (lock.isHeld) runCatching { lock.release() }
    }

    private fun refreshNotification() {
        val text = statusText()
        val percent = progressPercent()
        if (text == publishedText && percent == publishedPercent) return
        publishedText = text
        publishedPercent = percent
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    /** 通知进度百分比：已处理 / 总数；总量未就绪时为 -1（通知不画进度条）。 */
    private fun progressPercent(): Int {
        val progress = MediaIndexer.state.value
        return if (progress.total > 0) {
            (progress.processed.toFloat() / progress.total * 100).toInt().coerceIn(0, 100)
        } else {
            -1
        }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MediaIndexService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val percent = progressPercent()
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.media_index_notification_title))
            .setContentText(statusText())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.media_index_stop), stop).build())
        // 与 AppUpdateService 口径一致：有百分比时画系统 determinate 进度，0% 不画避免空条。
        if (percent > 0) {
            builder.setProgress(100, percent, false)
        }
        return builder.build()
    }

    /**
     * 进行中摘要：档位名 + 按 processed/total 展示并带上新增/跳过统计
     * （如「低耗后台 · 索引中… 3/20 · 新增 1 · 跳过 2」）；还没拿到总量时退回「索引中…」。
     */
    private fun statusText(): String {
        val progress = MediaIndexer.state.value
        val detail = if (progress.total > 0) {
            getString(
                R.string.media_index_notification_progress,
                progress.processed,
                progress.total,
                progress.indexed,
                progress.skipped,
            )
        } else {
            getString(R.string.media_index_running)
        }
        return getString(mode.labelRes) + " · " + detail
    }

    companion object {
        private const val CHANNEL = "eta_media_index"
        private const val NOTIFICATION_ID = 1108
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_MEDIA_INDEX"
        private const val EXTRA_MODE = "io.github.mangi.eta.extra.MEDIA_INDEX_MODE"
        private const val WAKE_LOCK_TAG = "eta:media-index"
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        @Volatile private var instance: MediaIndexService? = null

        /**
         * 启动后台索引（幂等）：已在跑就不再下发启动，服务内 onStartCommand 还有第二道判重。
         * [mode] 覆盖本轮档位（[MediaIndexAutoUpdate] 自动更新固定传 [IndexMode.POWER_SAVER]）；
         * null = 按存储的索引模式偏好（[EmbeddingModelStore.indexMode]）。
         */
        fun start(context: Context, mode: IndexMode? = null) {
            if (MediaIndexer.isRunning() || instance?.indexJob?.isActive == true) return
            val intent = Intent(context, MediaIndexService::class.java)
            if (mode != null) intent.putExtra(EXTRA_MODE, mode.wire)
            context.applicationContext.startForegroundService(intent)
        }

        /** 停止后台索引：复用通知停止按钮的 ACTION_STOP PendingIntent，后台态也能送达。 */
        fun stop(context: Context) {
            val stop = PendingIntent.getService(
                context, 1, Intent(context, MediaIndexService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching { stop.send() }
                .onFailure { AndroidAgentLogger.warn("Media index stop failed: type=${it.safeLogType()}") }
        }
    }
}
