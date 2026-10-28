package io.github.mangi.eta.data.repository

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
import io.github.mangi.eta.data.model.AppUpdateOffer
import io.github.mangi.eta.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 应用更新后台下载服务：前台服务 + 进度通知。
 *
 * 下载过程中会展示一个 Ongoing 通知，显示下载进度与取消按钮；
 * 下载完成后通知变为可点状态，点击后打开安装界面。
 * 服务支持熄屏/退后台继续下载（局部唤醒锁）。
 */
internal class AppUpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var downloadJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundActive = false

    // 当前下载状态
    private var currentVersionName: String = ""
    private var currentProgress: Long = 0L
    private var currentTotal: Long = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.update_channel), NotificationManager.IMPORTANCE_LOW),
        )
        ensureForeground()
    }

    private fun ensureForeground() {
        if (foregroundActive) return
        try {
            startForeground(NOTIFICATION_ID, downloadingNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            foregroundActive = true
        } catch (failure: RuntimeException) {
            AndroidAgentLogger.warn("AppUpdate service foreground failed: type=${failure.safeLogType()}")
            finish()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            downloadJob?.cancel()
            downloadJob = null
            finish()
            return START_NOT_STICKY
        }
        // 获取 offer 序列化数据
        val versionName = intent?.getStringExtra(EXTRA_VERSION_NAME).orEmpty()
        val apkUrl = intent?.getStringExtra(EXTRA_APK_URL).orEmpty()
        val apkName = intent?.getStringExtra(EXTRA_APK_NAME).orEmpty()
        val htmlUrl = intent?.getStringExtra(EXTRA_HTML_URL).orEmpty()
        val notes = intent?.getStringExtra(EXTRA_NOTES).orEmpty()
        currentVersionName = intent?.getStringExtra(EXTRA_CURRENT_VERSION).orEmpty()

        if (versionName.isBlank() || apkUrl.isBlank()) {
            finish()
            return START_NOT_STICKY
        }

        ensureForeground()
        if (downloadJob?.isActive == true) {
            refreshNotification()
            return START_NOT_STICKY
        }

        val offer = AppUpdateOffer(
            versionName = versionName,
            tagName = versionName,
            notes = notes,
            htmlUrl = htmlUrl,
            apkUrl = apkUrl,
            apkName = apkName.ifBlank { null },
        )

        currentProgress = 0L
        currentTotal = 0L
        this@AppUpdateService.currentVersionName = versionName
        acquireWakeLock()
        downloadJob = scope.launch {
            try {
                val file = AppUpdateRepository.downloadApk(
                    context = this@AppUpdateService,
                    offer = offer,
                    onProgress = { bytesRead, totalBytes ->
                        currentProgress = bytesRead
                        currentTotal = totalBytes
                        refreshNotification()
                    },
                )
                // 下载完成，显示完成通知
                showCompletedNotification()
                // 启动安装
                AppUpdateRepository.installApk(this@AppUpdateService, file)
                finish()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                AndroidAgentLogger.warn("App update download failed: type=${failure.safeLogType()}")
                showFailedNotification(failure.message ?: getString(R.string.update_download_failed))
                finish()
            } finally {
                releaseWakeLock()
                downloadJob = null
            }
        }
        refreshNotification()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun finish() {
        releaseWakeLock()
        if (foregroundActive) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            runCatching {
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
        }
        stopSelf()
    }

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
        if (!foregroundActive) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, downloadingNotification())
    }

    private fun downloadingNotification(): Notification {
        val text = if (currentTotal > 0) {
            getString(
                R.string.update_downloading_progress,
                formatBytes(currentProgress),
                formatBytes(currentTotal),
            )
        } else {
            getString(R.string.update_downloading)
        }

        val progress = if (currentTotal > 0) {
            (currentProgress.toFloat() / currentTotal * 100).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AppUpdateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.update_downloading_title, currentVersionName))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.update_stop_download), stop).build())

        if (progress > 0) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    private fun showCompletedNotification() {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.update_download_complete))
            .setContentText(getString(R.string.update_click_to_install))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun showFailedNotification(message: String) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.update_download_failed))
            .setContentText(message)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun formatBytes(bytes: Long): String {
        val unit = 1024
        var b = bytes.toDouble()
        val units = arrayOf("B", "KB", "MB", "GB")
        var i = 0
        while (b >= unit && i < units.size - 1) {
            b /= unit
            i++
        }
        return "${String.format("%.1f", b)} ${units[i]}"
    }

    companion object {
        private const val CHANNEL = "eta_app_update"
        private const val NOTIFICATION_ID = 1109
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_APP_UPDATE"
        private const val EXTRA_VERSION_NAME = "io.github.mangi.eta.extra.VERSION_NAME"
        private const val EXTRA_TAG_NAME = "io.github.mangi.eta.extra.TAG_NAME"
        private const val EXTRA_NOTES = "io.github.mangi.eta.extra.NOTES"
        private const val EXTRA_HTML_URL = "io.github.mangi.eta.extra.HTML_URL"
        private const val EXTRA_APK_URL = "io.github.mangi.eta.extra.APK_URL"
        private const val EXTRA_APK_NAME = "io.github.mangi.eta.extra.APK_NAME"
        private const val EXTRA_CURRENT_VERSION = "io.github.mangi.eta.extra.CURRENT_VERSION"
        private const val WAKE_LOCK_TAG = "eta:app-update"
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L

        @Volatile private var instance: AppUpdateService? = null

        /**
         * 启动后台下载服务（幂等）：已在下载中则不重复启动。
         */
        fun startDownload(context: Context, offer: AppUpdateOffer, currentVersion: String) {
            val intent = Intent(context, AppUpdateService::class.java).apply {
                putExtra(EXTRA_VERSION_NAME, offer.versionName)
                putExtra(EXTRA_TAG_NAME, offer.tagName)
                putExtra(EXTRA_NOTES, offer.notes)
                putExtra(EXTRA_HTML_URL, offer.htmlUrl)
                putExtra(EXTRA_APK_URL, offer.apkUrl)
                putExtra(EXTRA_APK_NAME, offer.apkName)
                putExtra(EXTRA_CURRENT_VERSION, currentVersion)
            }
            context.applicationContext.startForegroundService(intent)
        }

        /** 停止后台下载 */
        fun stopDownload(context: Context) {
            val stop = PendingIntent.getService(
                context, 1, Intent(context, AppUpdateService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching { stop.send() }
        }
    }
}
