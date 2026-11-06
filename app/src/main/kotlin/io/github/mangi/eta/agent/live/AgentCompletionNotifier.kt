package io.github.mangi.eta.agent.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.haptics.AppForeground

/**
 * 任务完成横幅：Agent run 终态的独立系统通知。
 *
 * 与执行卡（[io.github.mangi.eta.agent.runtime.AgentExecutionService]，LOW 渠道常驻）分开
 * 发布：独立 id 由会话派生，避免同 id 更新被系统静默合并——HyperOS / ColorOS 的岛对
 * 「新 id 首发」才渲染展开横幅。DEFAULT 渠道带声音振动，把终态提示出岛外。
 *
 * 前台不响：Eta 界面可见时用户正看着对话，结果就在眼前；未授权通知或开关关闭时静默跳过。
 */
internal object AgentCompletionNotifier {

    enum class TerminalKind { COMPLETED, FAILED, STOPPED }

    /** 点击横幅回跳会话的 extra；MainActivity 消费后直达该会话。 */
    const val EXTRA_CONVERSATION_ID = "io.github.mangi.eta.extra.LIVE_COMPLETION_CONVERSATION_ID"

    private const val CHANNEL = "eta_completion"
    /** tag 命名空间隔离：会话 hashCode 可能与本应用其他裸 id 通知（如定时发送）撞号。 */
    private const val NOTIFICATION_TAG = "eta_live_completion"
    private const val ID_BASE = 20000
    private const val ID_RANGE = 10000

    /** 发布一条完成横幅；conversationId 用于通知 id 派生与点击回跳。 */
    fun notifyTerminal(context: Context, conversationId: String, kind: TerminalKind, summary: String) {
        if (!Prefs.isEnabled(Prefs.Keys.LIVE_ACTIVITY_ENABLE)) return
        if (!Prefs.isEnabled(Prefs.Keys.LIVE_ACTIVITY_COMPLETION_BANNER)) return
        if (AppForeground.isForeground) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context, manager)
        val id = notificationId(conversationId)
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_CONVERSATION_ID, conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(kind.titleRes))
            .setContentIntent(open)
            .setAutoCancel(true)
        val text = summary.trim()
        if (text.isNotBlank()) {
            builder.setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
        }
        manager.notify(NOTIFICATION_TAG, id, builder.build())
    }

    /** 同一会话的终态共用一个 id：迟到重发只刷新横幅，不叠两张。 */
    private fun notificationId(conversationId: String): Int =
        ID_BASE + (conversationId.hashCode() and Int.MAX_VALUE) % ID_RANGE

    // ── 岛终态（小米私有）────────────────────────────────────────────
    // Number444 fork 真机定稿：岛「展开」只能跟着新 id 首发 alert 走（同 id 更新永不展开），
    // 且需频道在系统设置里开启「悬浮通知」。完成态在 eta_island 频道（静音 DEFAULT）用固定
    // 新 id 首发 + 焦点参数，HyperOS 渲染为岛展开，约 8 秒后自动撤销；失败/停止不展开、
    // 由执行卡安静收尾。持久横幅（notifyTerminal）并行发布，各司其职。非小米设备全程跳过。

    private const val ISLAND_CHANNEL = "eta_island"
    private const val ISLAND_NOTIFICATION_ID = 1208
    private const val ISLAND_DISMISS_DELAY_MS = 8_000L
    private val islandHandler = Handler(Looper.getMainLooper())
    private var islandDismiss: Runnable? = null

    /** 岛终态：仅 COMPLETED 上岛展开；前台也发（岛展开是扫一眼的形态，不算打扰）。 */
    fun notifyIslandTerminal(
        context: Context,
        kind: TerminalKind,
        conversationId: String,
        summary: String,
    ) {
        if (kind != TerminalKind.COMPLETED) return
        if (!Prefs.isEnabled(Prefs.Keys.LIVE_ACTIVITY_ENABLE)) return
        if (!MiuiIslandFocus.islandTerminalSupported()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        ensureIslandChannel(context, manager)
        val text = context.getString(kind.islandRes)
        val summaryText = summary.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        // requestCode 与执行卡裸开主页的 0 号位错开：extras 不参与 PendingIntent 相等性比较。
        val open = PendingIntent.getActivity(
            context, ISLAND_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_CONVERSATION_ID, conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, ISLAND_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setContentText(summaryText.ifBlank { context.getString(R.string.app_name) })
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            // 模板 17 的「查看」按钮语义：activity 型 action，官方规范点击后自动收起岛面板。
            .addAction(
                Notification.Action.Builder(null, context.getString(R.string.island_action_view), open).build(),
            )
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            // 与执行卡同一套 promoted 三件套：HyperOS 3 把它桥接为岛展开的首发通知。
            builder
                .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(text)
                .setRequestPromotedOngoing(true)
        }
        builder.extras.putBundle(
            "miui.focus.pics",
            MiuiIslandFocus.buildFocusPics(context, MiuiIslandFocus.launchIconBitmap(context)),
        )
        builder.extras.putString(
            "miui.focus.param",
            MiuiIslandFocus.buildFocusParam(
                text,
                // 大岛后置小字放结果摘要首行（系统自带超长压缩），空摘要退回应用名。
                summaryText.take(16).ifBlank { context.getString(R.string.app_name) },
                context.packageName,
                ISLAND_NOTIFICATION_ID,
            ),
        )
        manager.notify(ISLAND_NOTIFICATION_ID, builder.build())
        scheduleIslandDismiss(manager)
    }

    /** 新 run 开跑时立即撤掉上一单的岛终态与定时撤销（8 秒内开新任务不叠终态）。 */
    fun cancelIslandTerminal(context: Context) {
        islandDismiss?.let(islandHandler::removeCallbacks)
        islandDismiss = null
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ISLAND_NOTIFICATION_ID) }
    }

    private fun scheduleIslandDismiss(manager: NotificationManager) {
        islandDismiss?.let(islandHandler::removeCallbacks)
        val dismiss = Runnable {
            islandDismiss = null
            runCatching { manager.cancel(ISLAND_NOTIFICATION_ID) }
        }
        islandDismiss = dismiss
        islandHandler.postDelayed(dismiss, ISLAND_DISMISS_DELAY_MS)
    }

    private fun ensureIslandChannel(context: Context, manager: NotificationManager) {
        // 已存在时不重建：该频道在系统设置里的「悬浮通知」开关是岛展开的最终开关，
        // 且 Android 会复活已删频道的用户设置，同 id 重建无法复位。
        if (manager.getNotificationChannel(ISLAND_CHANNEL) != null) return
        val channel = NotificationChannel(
            ISLAND_CHANNEL,
            context.getString(R.string.island_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        // 已存在时不重建：Android 会保留用户改过的渠道设置，同 id 重建无法复位。
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.live_activity_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.setShowBadge(false)
        channel.enableVibration(true)
        manager.createNotificationChannel(channel)
    }

    private val TerminalKind.titleRes: Int
        get() = when (this) {
            TerminalKind.COMPLETED -> R.string.live_completion_done
            TerminalKind.FAILED -> R.string.live_completion_failed
            TerminalKind.STOPPED -> R.string.live_completion_stopped
        }

    private val TerminalKind.islandRes: Int
        get() = when (this) {
            TerminalKind.COMPLETED -> R.string.island_terminal_done
            TerminalKind.FAILED -> R.string.island_terminal_failed
            TerminalKind.STOPPED -> R.string.island_terminal_stopped
        }
}
