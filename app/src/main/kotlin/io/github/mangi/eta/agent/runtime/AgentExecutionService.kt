package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.live.AgentCompletionNotifier
import io.github.mangi.eta.agent.live.AgentRunStreamTracker
import io.github.mangi.eta.agent.live.MiuiIslandFocus
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.haptics.AppForeground
import java.util.concurrent.atomic.AtomicLong

/** 只在用户任务存活期间持有前台执行生命周期；进程被系统停止后不重放任务。 */
internal class AgentExecutionService : Service() {
    private val stopQueue = ExecutionStopQueue { failure ->
        AndroidAgentLogger.warn("Execution task stop failed: type=${failure.safeLogType()}")
    }
    private val owner = ownerSequence.incrementAndGet()
    private var foregroundActive = false
    @Volatile private var startRejected = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        leases.attachOwner(owner)
        // 「前台收起运行卡」的翻转钩子：Eta 可见性变化时重估常驻卡（见 refreshNotification）。
        AppForeground.onVisibilityChanged = { onAppVisibilityChanged() }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.execution_channel), NotificationManager.IMPORTANCE_LOW),
        )
        ensureForeground()
    }

    private fun ensureForeground() {
        if (foregroundActive || startRejected) return
        leases.attachOwner(owner)
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            foregroundActive = true
        } catch (failure: RuntimeException) {
            startRejected = true
            AndroidAgentLogger.warn("Execution service foreground failed: type=${failure.safeLogType()}")
            stopTasks(startFailed = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTasks()
        } else {
            ensureForeground()
            refreshNotification()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            AppForeground.onVisibilityChanged = null
        }
        // 销毁时同样收回本服务拥有的任务。回收在独立有界工作线程上完成，不阻塞 Main。
        stopQueue.close(leases.drainOwner(owner))
        super.onDestroy()
    }

    private fun stopTasks(startFailed: Boolean = false) {
        val callbacks = leases.drain(startFailed)
        stopQueue.submit(callbacks) {
            mainHandler.post { if (instance === this) refreshNotification() }
        }
    }

    private fun refreshNotification() {
        if (leases.closeOwnerIfIdle(owner)) {
            foregroundActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (suppressionActive()) {
            // 前台抑制：用户正看着 Eta，撤下常驻卡；租约仍在，回后台时经 AppForeground 回调重新升前。
            if (foregroundActive) {
                foregroundActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            return
        }
        ensureForeground()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    /** 「前台收起运行卡」开关 + Eta 界面可见；只在还有活动租约时有意义。 */
    private fun suppressionActive(): Boolean =
        Prefs.isEnabled(Prefs.Keys.LIVE_ACTIVITY_FOREGROUND_SUPPRESS) && AppForeground.isForeground

    private fun notification(): Notification {
        val status = runStatus
        val stream = runStream
        // 暂停/停止时思维流预览已过期：正文回落到状态文案、呼吸计时停走（本地卡有暂停键，fork 没有）。
        val streamActive = status != null &&
            status != AgentOverlayStatus.Paused &&
            status != AgentOverlayStatus.Stopping
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = MiuiIslandFocus.launchIconBitmap(this)
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.execution_title))
            .setContentText(
                stream?.detail?.takeIf { streamActive && it.isNotBlank() }
                    ?: status?.localizedText(resources)
                    ?: if (leases.executingSessionCount() == 0) getString(R.string.execution_summary_idle) else getString(R.string.execution_summary, leases.executingSessionCount())
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (status != null) {
            // 暂停/继续回跳 Runtime 服务：目标 run 是浮层归属的那个（卡片状态正是它的投影）。
            val paused = status == AgentOverlayStatus.Paused
            // start 面能力令牌经 startActionIntent 注入：通知按钮是本服务 start 面的
            // 唯一外部触发点，伪造不出带当前令牌的 intent（见 AgentRuntimeService）。
            val toggle = PendingIntent.getService(
                this, 2,
                AgentRuntimeService.startActionIntent(this, AgentRuntimeService.ACTION_ISLAND_TOGGLE_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    getString(if (paused) R.string.live_resume else R.string.live_pause),
                    toggle,
                ).build(),
            )
            // 岛「补充」键（Number444 fork 真机验证链路）：展开胶囊直进补充输入模式。
            // RemoteInput 在岛上无映射（T32 测定），插入走悬浮窗输入 fallback。
            val supplement = PendingIntent.getService(
                this, 3,
                AgentRuntimeService.startActionIntent(this, AgentRuntimeService.ACTION_ISLAND_SUPPLEMENT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.live_supplement),
                    supplement,
                ).build(),
            )
        }
        builder.addAction(Notification.Action.Builder(null, getString(R.string.execution_stop), stop).build())
        if (icon != null) builder.setLargeIcon(icon)
        // 只有 Agent run 在跑时才申请提升为 Live Update（状态栏胶囊与锁屏）；终端会话、Linux 启动等长驻任务不属于“正在进行、需要持续关注”的活动。
        if (streamActive && stream?.showChronometer == true && stream.startedAtElapsedRealtime > 0L) {
            // 思维流呼吸计时（liyunkkk fork）：系统原生 Chronometer，零唤醒展示当前阶段耗时。
            builder
                .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - stream.startedAtElapsedRealtime))
                .setShowWhen(true)
                .setUsesChronometer(true)
        }
        if (status != null && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            builder
                .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(status.shortText())
                .setRequestPromotedOngoing(true)
        } else if (streamActive) {
            // 旧系统没有提升样式，思维流的思考/输出尾部摘要放进下拉展开卡。
            val expanded = stream?.expanded
            if (!expanded.isNullOrBlank()) builder.setStyle(Notification.BigTextStyle().bigText(expanded))
        }
        // Android 16 以下的小米焦点协议路径：参数让执行卡渲染为「超级岛胶囊」（协议见 MiuiIslandFocus）。
        if (status != null && Prefs.isEnabled(Prefs.Keys.LIVE_ACTIVITY_ENABLE) && MiuiIslandFocus.paramsApplicable()) {
            builder.addExtras(
                Bundle().apply {
                    putBundle("miui.focus.pics", MiuiIslandFocus.buildFocusPics(this@AgentExecutionService, icon))
                    putString(
                        "miui.focus.param",
                        MiuiIslandFocus.buildFocusParam(
                            status.shortText(), getString(R.string.app_name), packageName, NOTIFICATION_ID,
                        ),
                    )
                },
            )
        }
        return builder.build()
    }

    /** 状态栏胶囊约 7 个字符可完整显示；更长时只取动作，工具名留在展开态。 */
    private fun AgentOverlayStatus.shortText(): String = when (this) {
        is AgentOverlayStatus.RunningTool, is AgentOverlayStatus.HostedToolRunning -> getString(R.string.execution_chip_running)
        AgentOverlayStatus.Paused -> getString(R.string.overlay_paused)
        AgentOverlayStatus.Stopping -> getString(R.string.overlay_stopping)
        AgentOverlayStatus.GeneratingAnswer, AgentOverlayStatus.PreparingAnswer -> getString(R.string.execution_chip_answering)
        else -> getString(R.string.overlay_reasoning)
    }

    companion object {
        private const val CHANNEL = "eta_execution"
        private const val NOTIFICATION_ID = 1107
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null
        private var runStatus: AgentOverlayStatus? = null
        private var publishedStatusText: String? = null
        private var runStream: AgentRunStreamTracker.Snapshot? = null
        private var publishedStreamKey: String? = null

        /**
         * Agent run 的当前状态，供通知与状态栏胶囊展示；null 表示没有 Agent run 在跑。
         * 只在显示文字变化时刷新通知，避免流式事件把通知更新频率推到系统限流。
         */
        fun updateRunStatus(context: Context, status: AgentOverlayStatus?) {
            mainHandler.post {
                val previous = runStatus
                runStatus = status
                if (status == null) {
                    runStream = null
                    publishedStreamKey = null
                } else if (previous == null) {
                    // 新 run 开跑：撤掉上一单的岛终态残留，并后台预热小米焦点协议探测。
                    AgentCompletionNotifier.cancelIslandTerminal(context)
                    MiuiIslandFocus.warmUpProbeAsync(context)
                }
                val text = status?.localizedText(context.resources)
                if (text == publishedStatusText) return@post
                publishedStatusText = text
                instance?.takeIf { it.foregroundActive }?.refreshNotification()
            }
        }

        /**
         * 思维流快照上卡（正文预览与展开摘要）；null 清空。节流在 [AgentRunStreamTracker]（1s），
         * 这里只在「阶段+预览」变化时重建通知，避免流式 delta 把更新频率推到系统限流。
         */
        fun updateRunStream(context: Context, snapshot: AgentRunStreamTracker.Snapshot?) {
            mainHandler.post {
                runStream = snapshot
                val key = snapshot?.let { "${it.phase.name}/${it.detail}" }.orEmpty()
                if (key == publishedStreamKey) return@post
                publishedStreamKey = key
                instance?.takeIf { it.foregroundActive }?.refreshNotification()
            }
        }

        @Volatile var backupMaintenance: Boolean = false
            private set

        fun refreshIcon() {
            mainHandler.post { instance?.refreshNotification() }
        }

        @Synchronized fun beginBackupMaintenance() {
            check(!backupMaintenance && leases.count() == 0) { "请先停止 Agent 任务并关闭终端会话，再备份或恢复" }
            backupMaintenance = true
        }

        @Synchronized fun endBackupMaintenance() { backupMaintenance = false }

        /** 必须从有效的用户入口取得引用，再创建会话或子进程；失败时调用方不启动任务。 */
        @Synchronized fun acquire(
            context: Context,
            id: String,
            allowBoundFallback: Boolean = false,
            countsAsExecutingSession: Boolean = true,
            onStop: () -> Unit,
        ): Boolean {
            if (backupMaintenance || instance?.startRejected == true) return false
            if (!leases.acquire(id, allowBoundFallback, countsAsExecutingSession, onStop)) return true
            return try {
                context.applicationContext.startForegroundService(Intent(context, AgentExecutionService::class.java))
                true
            } catch (failure: RuntimeException) {
                leases.release(id)
                AndroidAgentLogger.warn("Execution service start rejected: type=${failure.safeLogType()}")
                false
            }
        }

        fun release(id: String) {
            leases.release(id)
            mainHandler.post { instance?.refreshNotification() }
        }

        /** AppForeground 可见性翻转：抑制开关打开时在前台撤卡、回后台升前。可能来自任意线程，转主线程处理。 */
        fun onAppVisibilityChanged() {
            mainHandler.post { instance?.refreshNotification() }
        }
    }
}
