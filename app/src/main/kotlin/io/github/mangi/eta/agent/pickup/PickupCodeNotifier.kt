// 取餐码提醒通知：呈现方式跟随应用级「通知方式」设置（实时活动页，LiveActivityStyle）：
// 原生通知 / 小米超级岛（miui.focus 焦点参数）/ ColorOS 流体云（纯官方 promoted 通道）。
// 原生分支与岛分支的形态移植自 badnng/Hyper-pick-up-code（AGPL-3.0-only）
//   com.Badnng.moe.helper.NotificationHelper / SuperIslandHelper；
// 岛协议复用 Eta 既有的 agent/live/MiuiIslandFocus（同一套 miui.focus param_v2 协议，
// 与 AgentCompletionNotifier 的岛终态通道同构），合规登记见 docs/THIRD_PARTY_NOTICES.md。

package io.github.mangi.eta.agent.pickup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.live.MiuiIslandFocus
import io.github.mangi.eta.config.LiveActivityStyle
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.PickupCodeRepository
import io.github.mangi.eta.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object PickupCodeNotifier {
    private const val TAG = "PickupCodeNotifier"
    private const val CHANNEL = "pickup_code"
    private const val ISLAND_CHANNEL = "pickup_code_island"
    private const val ISLAND_NOTIFICATION_ID = 1209
    private const val ISLAND_DISMISS_DELAY_MS = 8_000L

    private val islandHandler = Handler(Looper.getMainLooper())
    private var islandDismiss: Runnable? = null

    /**
     * 新取餐码提醒：按应用级「通知方式」（实时活动页）分发呈现；
     * 所选方式设备不支持时自动落回原生通知。
     */
    fun notifyNewCode(
        context: Context,
        code: String,
        orderType: String,
        brandName: String?,
        pickupLocation: String?,
    ) {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        when (Prefs.liveActivityStyle()) {
            LiveActivityStyle.NATIVE -> notifyNative(appContext, manager, code, orderType, brandName)
            LiveActivityStyle.XIAOMI_ISLAND -> {
                if (MiuiIslandFocus.islandTerminalSupported()) {
                    runCatching { notifyLive(appContext, manager, code, orderType, brandName, pickupLocation, injectFocusParams = true) }
                        .onFailure { Log.w(TAG, "超级岛提醒失败，落回原生", it) }
                        .onSuccess { return }
                }
                notifyNative(appContext, manager, code, orderType, brandName)
            }
            LiveActivityStyle.COLOROS_FLUID -> {
                if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                    runCatching { notifyLive(appContext, manager, code, orderType, brandName, pickupLocation, injectFocusParams = false) }
                        .onFailure { Log.w(TAG, "流体云提醒失败，落回原生", it) }
                        .onSuccess { return }
                }
                notifyNative(appContext, manager, code, orderType, brandName)
            }
        }
    }

    // ── 原生通知 ─────────────────────────────────────────────────────

    private fun notifyNative(
        context: Context,
        manager: NotificationManager,
        code: String,
        orderType: String,
        brandName: String?,
    ) {
        ensureChannel(context, manager)
        val open = PendingIntent.getActivity(
            context,
            code.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val doneIntent = PendingIntent.getBroadcast(
            context,
            code.hashCode(),
            Intent(context, PickupCodeCompleteReceiver::class.java)
                .setAction(PickupCodeCompleteReceiver.ACTION)
                .putExtra(PickupCodeCompleteReceiver.EXTRA_CODE, code),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = brandName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.pickup_notification_title)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(code)
            .setStyle(Notification.BigTextStyle().bigText(code))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.pickup_action_mark_done),
                    doneIntent,
                ).build(),
            )
            .build()
        manager.notify(code.hashCode(), notification)
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL) != null) return
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.pickup_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.enableVibration(true)
        manager.createNotificationChannel(channel)
    }

    // ── 实时活动提醒（超级岛 / 流体云共用形态）────────────────────
    // 与 AgentCompletionNotifier.notifyIslandTerminal 同构：固定新 id 首发 +
    // promoted 三件套（含真机定稿的 indeterminate ProgressStyle），岛展开约 8 秒后
    // 自动撤销。岛面呈现对齐澎湃记 SuperIslandHelper：大岛标题 = 码值本身（一眼看码），
    // 后置小字 = 取餐提示（取餐位置优先，否则按类型给固定提示）；岛面动作按钮
    // （hintInfo.actionInfo）是模板 16 的字段，本仓真机定稿走模板 1，不引入未验证
    // 的协议值，「标记完成」挂在通知 action 上。miui.focus 焦点参数只在超级岛档注入
    // （[injectFocusParams]）；流体云档走纯官方 promoted 通道，厂商自行桥接。

    private fun notifyLive(
        context: Context,
        manager: NotificationManager,
        code: String,
        orderType: String,
        brandName: String?,
        pickupLocation: String?,
        injectFocusParams: Boolean,
    ) {
        ensureIslandChannel(context, manager)
        val title = brandName?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.pickup_notification_title)
        val content = context.getString(R.string.pickup_island_content, code)
        val open = PendingIntent.getActivity(
            context,
            ISLAND_NOTIFICATION_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 与原生分支同一个广播语义（同 intent 自然复用同一个 PendingIntent）。
        val doneIntent = PendingIntent.getBroadcast(
            context,
            code.hashCode(),
            Intent(context, PickupCodeCompleteReceiver::class.java)
                .setAction(PickupCodeCompleteReceiver.ACTION)
                .putExtra(PickupCodeCompleteReceiver.EXTRA_CODE, code),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, ISLAND_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .addAction(
                Notification.Action.Builder(null, context.getString(R.string.pickup_action_mark_done), doneIntent).build(),
            )
            .addAction(
                Notification.Action.Builder(null, context.getString(R.string.island_action_view), open).build(),
            )
        if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
            builder
                .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                .setShortCriticalText(code)
                .setRequestPromotedOngoing(true)
        }
        if (injectFocusParams) {
            builder.extras.putBundle(
                "miui.focus.pics",
                MiuiIslandFocus.buildFocusPics(context, MiuiIslandFocus.launchIconBitmap(context)),
            )
            builder.extras.putString(
                "miui.focus.param",
                // 不截断：码值与提示都是短串，take(16) 会切掉长取件码的数字尾巴
                //（模板 1 的 content 槽超长由系统压缩，参照岛终态的注释口径）。
                MiuiIslandFocus.buildFocusParam(
                    code,
                    islandHint(context, orderType, pickupLocation),
                    context.packageName,
                    ISLAND_NOTIFICATION_ID,
                ),
            )
        }
        manager.notify(ISLAND_NOTIFICATION_ID, builder.build())
        scheduleIslandDismiss(manager)
    }

    /**
     * 岛面后置小字提示（澎湃记 pickupHintTitle 口径）：取餐位置优先原样展示，
     * 否则快递提示核对取件码、饮品提示大堂/荧幕叫号、其余提示及时取餐。
     */
    private fun islandHint(context: Context, orderType: String, pickupLocation: String?): String =
        pickupLocation?.takeIf { it.isNotBlank() }
            ?: when (orderType) {
                "快递" -> context.getString(R.string.pickup_island_hint_express)
                "饮品" -> context.getString(R.string.pickup_island_hint_drink)
                else -> context.getString(R.string.pickup_island_hint_food)
            }

    private fun ensureIslandChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(ISLAND_CHANNEL) != null) return
        val channel = NotificationChannel(
            ISLAND_CHANNEL,
            context.getString(R.string.pickup_island_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
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

    /** 立即撤掉岛面卡槽（固定 id 单槽）并清掉定时撤销；「标记完成」联动调用。 */
    fun cancelIslandSlot(context: Context) {
        islandDismiss?.let(islandHandler::removeCallbacks)
        islandDismiss = null
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(ISLAND_NOTIFICATION_ID)
        }
    }
}

/** 通知上的「标记完成」action：按码值把未完成记录标完成并撤掉该码的通知。 */
internal class PickupCodeCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        val pendingResult = goAsync()
        io.github.mangi.eta.agent.pickup.PickupCodeCompleteWorker.complete(context, code) {
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION = "io.github.mangi.eta.action.PICKUP_CODE_MARK_DONE"
        const val EXTRA_CODE = "code"
    }
}

private object PickupCodeCompleteWorker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun complete(context: Context, code: String, onDone: () -> Unit) {
        scope.launch {
            runCatching {
                val repository = PickupCodeRepository(context.applicationContext)
                repository.findUnfinishedByCode(code)?.let { repository.markCompleted(it.id) }
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(code.hashCode())
                // 岛面固定单槽展示的最新码也可能是刚标完的这个：一并撤掉，不留已完成码的岛卡。
                PickupCodeNotifier.cancelIslandSlot(context)
            }
            onDone()
        }
    }
}
