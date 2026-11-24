package io.github.mangi.eta.agent.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import io.github.mangi.eta.R
import io.github.mangi.eta.config.LiveActivityStyle
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.haptics.AppForeground

/**
 * 任务完成横幅：Agent run 终态的独立系统通知。
 *
 * 与执行卡（[io.github.mangi.eta.agent.runtime.AgentExecutionService]）分开
 * 发布：独立 id 由会话派生，避免同 id 更新被系统静默合并——HyperOS / ColorOS 的岛对
 * 「新 id 首发」才渲染展开横幅。DEFAULT 渠道带声音振动，把终态提示出岛外。
 *
 * 前台不响：Eta 界面可见时用户正看着对话，结果就在眼前；未授权通知或开关关闭时静默跳过。
 */
internal object AgentCompletionNotifier {
    private const val TAG = "AgentCompletionNotifier"

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
            .setColor(kind.palette.accentArgb)
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

    // ── 子会话提问横幅 ────────────────────────────────────────────────

    /**
     * 派发子会话调用 ask_user 后的「等你回答」横幅：与终态横幅同一渠道、同一 id 派生，
     * 因此随后的终态横幅会原地覆盖它；RESOLVED 时由 [cancelQuestionWaiting] 主动撤销。
     * 开关复用完成横幅档（同一「完成后提醒」意图，不另设设置面）；前台静默——
     * 用户正看着界面，调度台卡片与抽屉角标就是提醒本体。
     */
    fun notifyQuestionWaiting(context: Context, conversationId: String, summary: String) {
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
            .setContentTitle(context.getString(R.string.live_question_waiting_title))
            .setContentIntent(open)
            .setAutoCancel(true)
        val text = summary.trim()
        if (text.isNotBlank()) {
            builder.setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
        }
        manager.notify(NOTIFICATION_TAG, id, builder.build())
    }

    /** 子会话提问已回答/取消/中断：撤掉等待横幅（与等待横幅同 tag+id）。 */
    fun cancelQuestionWaiting(context: Context, conversationId: String) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(NOTIFICATION_TAG, notificationId(conversationId))
        }
    }

    // ── 岛终态（实时活动提醒）──────────────────────────────────────
    // Number444 fork 真机定稿：岛「展开」只能跟着新 id 首发 alert 走（同 id 更新永不展开），
    // 且需频道在系统设置里开启「悬浮通知」。完成态在 eta_island 频道（静音 DEFAULT）用固定
    // 新 id 首发，HyperOS 渲染为岛展开；不设定时撤销，直到用户清除或下一次任务替换。
    // 失败/停止不展开、由执行卡安静收尾。持久横幅（notifyTerminal）并行发布，各司其职。呈现方式（应用级设置）门控：
    // 原生档不上岛；超级岛档要求小米岛可用；流体云档走纯官方 promoted 通道（Android 16+）。

    private const val ISLAND_CHANNEL = "eta_island"
    private const val ISLAND_NOTIFICATION_ID = 1208

    /** 岛终态：仅 COMPLETED 上岛展开；前台也发（岛展开是扫一眼的形态，不算打扰）。 */
    fun notifyIslandTerminal(
        context: Context,
        kind: TerminalKind,
        conversationId: String,
        summary: String,
    ) {
        if (kind != TerminalKind.COMPLETED) return
        if (!Prefs.isEnabled(Prefs.Keys.LIVE_ACTIVITY_ENABLE)) return
        // 呈现方式门控（应用级设置，实时活动页）：原生=不上岛；超级岛=小米可用性；
        // 流体云=官方 promoted 通道（Android 16+），各厂商自行桥接成自家实时活动形态。
        val style = Prefs.liveActivityStyle()
        when (style) {
            LiveActivityStyle.NATIVE -> return
            LiveActivityStyle.XIAOMI_ISLAND ->
                if (!MiuiIslandFocus.islandTerminalSupported()) {
                    Log.w(TAG, "岛终态跳过：岛不可用（小米判定/焦点协议探测未过）style=$style")
                    return
                }
            LiveActivityStyle.COLOROS_FLUID ->
                // 纯 LiveUpdate 形态自 Android 16.0（BAKLAVA）起即可被厂商桥接（T30）。
                if (Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.BAKLAVA) {
                    Log.w(TAG, "岛终态跳过：低于 Android 16.0 style=$style")
                    return
                }
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        ensureIslandChannel(context, manager)
        val text = context.getString(kind.islandRes)
        val summaryText = summary.trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        // requestCode 与执行卡会话入口错开：extras 不参与 PendingIntent 相等性比较。
        val open = PendingIntent.getActivity(
            context, ISLAND_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_CONVERSATION_ID, conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val palette = IslandPalette.AgentCompleted
        val builder = Notification.Builder(context, ISLAND_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(palette.accentArgb)
            .setContentTitle(text)
            .setContentText(summaryText.ifBlank { context.getString(R.string.app_name) })
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            // 「查看」使用 activity 型 action，点击后回到对应会话并自动收起岛面板。
            .addAction(
                Notification.Action.Builder(null, context.getString(R.string.island_action_view), open).build(),
            )
        // promoted API 分层：ProgressStyle / setShortCriticalText 自 16.0（BAKLAVA）可用，
        // setRequestPromotedOngoing 是 16.1（BAKLAVA_1）新增——16.0 设备只带前两者也走
        // LiveUpdate 形态（T30），整档跳过会退化成普通通知（真机实证）。
        if (style == LiveActivityStyle.COLOROS_FLUID &&
            Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA
        ) {
            // 流体云档只走官方 promoted 通道，参数留给厂商自家桥接。
            builder
                .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(text)
            if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                builder.setRequestPromotedOngoing(true)
            }
        }
        // 小米超级岛档使用与取码一致的模板 16；不得叠加 promoted 三件套，
        // 否则 HyperOS 走 Live Update 管线并忽略 miui.focus 参数。
        if (style == LiveActivityStyle.XIAOMI_ISLAND) {
            val contentIcon = MiuiIslandFocus.taskTileIcon(context, palette)
            builder.addExtras(MiuiIslandFocus.buildTemplate16Pics(context, contentIcon))
            builder.addExtras(
                MiuiIslandFocus.buildTemplate16Actions(
                    context.getString(R.string.island_action_view),
                    open,
                ),
            )
            builder.extras.putString(
                "miui.focus.param",
                MiuiIslandFocus.buildTaskFocusParam(
                    ticker = text,
                    islandTitle = text,
                    iconTextContent = context.getString(R.string.app_name),
                    // 大岛后置小字放结果摘要首行，空摘要退回应用名。
                    hintTitle = summaryText.take(16).ifBlank { context.getString(R.string.app_name) },
                    actionTitle = context.getString(R.string.island_action_view),
                    targetPage = MainActivity::class.java.name,
                    palette = palette,
                ),
            )
        }
        manager.notify(ISLAND_NOTIFICATION_ID, builder.build())
    }

    /** 新 run 开跑时立即撤掉上一单的岛终态，避免与新任务的过程岛并存。 */
    fun cancelIslandTerminal(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ISLAND_NOTIFICATION_ID) }
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

    private val TerminalKind.palette: IslandPalette
        get() = when (this) {
            TerminalKind.COMPLETED -> IslandPalette.AgentCompleted
            TerminalKind.FAILED -> IslandPalette.AgentFailed
            TerminalKind.STOPPED -> IslandPalette.AgentStopped
        }
}
