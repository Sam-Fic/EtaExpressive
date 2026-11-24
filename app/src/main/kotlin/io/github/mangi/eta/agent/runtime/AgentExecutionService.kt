package io.github.mangi.eta.agent.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.live.AgentCompletionNotifier
import io.github.mangi.eta.agent.live.AgentRunStreamTracker
import io.github.mangi.eta.agent.live.IslandPalette
import io.github.mangi.eta.agent.live.MiuiIslandFocus
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.localizedText
import io.github.mangi.eta.config.LiveActivityStyle
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
        // AppForeground 槽位只支持一个消费方，这里链上结果卡的前台收起（见
        // AgentRuntimeService.dismissResultCardForAppForeground 的取证注释）。
        AppForeground.onVisibilityChanged = { visible ->
            onAppVisibilityChanged()
            // 回到前台就收掉结果卡：它是半屏悬浮窗，留着会吃掉下半屏触摸（界面能刷新但翻不动）。
            if (visible) AgentRuntimeService.dismissResultCardForAppForeground()
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.execution_channel), NotificationManager.IMPORTANCE_LOW),
        )
        // 过程岛与取码岛同档：HIGH 才能触发 islandFirstFloat 首浮；声音振动仍关闭。
        manager.createNotificationChannel(
            NotificationChannel(ISLAND_CHANNEL, getString(R.string.execution_island_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        ensureForeground()
    }

    private fun ensureForeground(force: Boolean = false) {
        if ((foregroundActive && !force) || startRejected) return
        leases.attachOwner(owner)
        try {
            startForeground(notificationId, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
        getSystemService(NotificationManager::class.java).notify(notificationId, notification())
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
        val openIntent = Intent(this, MainActivity::class.java).apply {
            runConversationId?.takeIf(String::isNotBlank)?.let {
                putExtra(AgentCompletionNotifier.EXTRA_CONVERSATION_ID, it)
            }
        }
        val open = PendingIntent.getActivity(
            this, REQUEST_OPEN_RUN, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, AgentExecutionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = MiuiIslandFocus.launchIconBitmap(this)
        // 运行卡与取码提醒共用同一「通知方式」语义：小米超级岛走模板 16 私有焦点路径；
        // ColorOS 流体云走纯官方 promoted 通道；原生档不申请任何实时活动形态。
        val liveActivityStyle = if (Prefs.isEnabled(Prefs.Keys.LIVE_ACTIVITY_ENABLE)) {
            Prefs.liveActivityStyle()
        } else {
            LiveActivityStyle.NATIVE
        }
        val useXiaomiIsland = status != null &&
            liveActivityStyle == LiveActivityStyle.XIAOMI_ISLAND &&
            MiuiIslandFocus.islandTerminalSupported()
        val useFluidCloud = status != null &&
            liveActivityStyle == LiveActivityStyle.COLOROS_FLUID &&
            Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA
        // 只有所选实时活动形态真正可用时才进 HIGH 岛渠道；关闭/不支持/原生档保留 LOW 普通通知。
        val channelId = if (useXiaomiIsland || useFluidCloud) ISLAND_CHANNEL else CHANNEL
        val palette = IslandPalette.AgentRunning
        val builder = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(palette.accentArgb)
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
            if (useXiaomiIsland) {
                // 模板 16 的标准四图与动作表都是 addExtras 的外层包装，禁止再 putBundle 套一层。
                // 私有焦点路径不得叠加 promoted 三件套，否则 HyperOS 忽略 miui.focus 退化成原生卡。
                val contentIcon = MiuiIslandFocus.taskTileIcon(this, palette)
                    ?: icon?.let(Icon::createWithBitmap)
                builder.addExtras(MiuiIslandFocus.buildTemplate16Pics(this, contentIcon))
                builder.addExtras(
                    MiuiIslandFocus.buildTemplate16Actions(
                        getString(R.string.live_supplement),
                        supplement,
                    ),
                )
                val hint = stream?.detail?.takeIf { streamActive && it.isNotBlank() }
                    ?: getString(R.string.app_name)
                builder.extras.putString(
                    "miui.focus.param",
                    MiuiIslandFocus.buildTaskFocusParam(
                        ticker = status.shortText(),
                        islandTitle = status.shortText(),
                        iconTextContent = getString(R.string.app_name),
                        hintTitle = hint,
                        actionTitle = getString(R.string.live_supplement),
                        targetPage = MainActivity::class.java.name,
                        palette = palette,
                    ),
                )
            }
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
        // 提升必须门控在「run 真在跑」上（与上方 Chronometer 的 streamActive 同口径）：
        // 跑完/暂停/停止后若仍挂着「不确定进度 + promoted ongoing」，系统会保留一个实时活动壳，
        // 用户点开的是系统渲染的实时活动展开面板（内容是本卡文案，不是 Eta 界面），
        // 那个面板本身不支持滚动，看起来就像「进了个不一样的界面且翻不动」。
        if (streamActive && useFluidCloud) {
            // promoted API 分层：ProgressStyle / setShortCriticalText 自 16.0（BAKLAVA）可用，
            // setRequestPromotedOngoing 是 16.1（BAKLAVA_1）新增——16.0 设备只带前两者也走
            // LiveUpdate 形态（T30），整档跳过会退化成普通通知（真机实证）。
            builder
                .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(status.shortText())
            if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                builder.setRequestPromotedOngoing(true)
            }
        } else if (streamActive && !useXiaomiIsland) {
            // 原生通知或设备不支持所选岛形态：思维流的思考/输出尾部摘要放进下拉展开卡。
            val expanded = stream?.expanded
            if (!expanded.isNullOrBlank()) builder.setStyle(Notification.BigTextStyle().bigText(expanded))
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
        private const val ISLAND_CHANNEL = "eta_execution_island"
        private const val NOTIFICATION_ID = 1107
        // extras 不参与 PendingIntent 相等性比较；独立请求码避免其他 MainActivity 通知覆盖会话目标。
        private const val REQUEST_OPEN_RUN = 1207
        private const val ACTION_STOP = "io.github.mangi.eta.action.STOP_USER_EXECUTION"
        private val leases = ExecutionLeaseRegistry()
        private val ownerSequence = AtomicLong()
        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var instance: AgentExecutionService? = null
        private var runStatus: AgentOverlayStatus? = null
        private var publishedStatusText: String? = null
        private var runStream: AgentRunStreamTracker.Snapshot? = null
        private var runConversationId: String? = null
        private var publishedStreamKey: String? = null
        private var nextNotificationId = NOTIFICATION_ID + 1
        @Volatile private var notificationId = NOTIFICATION_ID

        /**
         * 新 run 首发：HyperOS 只对新通知 id 展开焦点岛，同 id 更新不会重新展开。
         * 因此每次从空闲转入运行都换 id，并强制刷新前台服务通知；后续阶段仍用同 id 更新。
         * 新 run 同时记下会话目标，过程岛/通知点击后直达该会话。
         */
        fun beginRun(context: Context, conversationId: String) {
            mainHandler.post {
                if (runStatus == null) {
                    val previousId = notificationId
                    notificationId = nextNotificationId++
                    runStatus = AgentOverlayStatus.Preparing
                    runConversationId = conversationId
                    runStream = null
                    publishedStatusText = null
                    publishedStreamKey = null
                    AgentCompletionNotifier.cancelIslandTerminal(context)
                    runCatching {
                        context.getSystemService(NotificationManager::class.java).cancel(previousId)
                    }
                    MiuiIslandFocus.warmUpProbeAsync(context)
                }
                val service = instance
                if (service != null && service.foregroundActive) {
                    service.ensureForeground(force = runStatus != null)
                } else {
                    service?.refreshNotification()
                }
            }
        }

        /**
         * Agent run 的当前状态，供通知与状态栏胶囊展示；null 表示没有 Agent run 在跑。
         * 只在显示文字变化时刷新通知，避免流式事件把通知更新频率推到系统限流。
         */
        fun updateRunStatus(context: Context, status: AgentOverlayStatus?) {
            mainHandler.post {
                runStatus = status
                if (status == null) {
                    runConversationId = null
                    runStream = null
                    publishedStreamKey = null
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
