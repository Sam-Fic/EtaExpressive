// 取餐码提醒通知：呈现方式跟随应用级「通知方式」设置（实时活动页，LiveActivityStyle）：
// 原生通知 / 小米超级岛（miui.focus 模板 16 焦点参数）/ ColorOS 流体云（纯官方 promoted 通道）。
// 三档形态均移植自 badnng/Hyper-pick-up-code（AGPL-3.0-only）
//   com.Badnng.moe.helper.NotificationHelper / SuperIslandHelper：岛档 = 模板 16 岛路径
//   （MiuiIslandFocus.buildTemplate16* 三件构造器，HyperOS 4 不渲染模板 1 安静胶囊参数），
//   流体云档 = 参考的非岛设备 promoted 路径；合规登记见 docs/THIRD_PARTY_NOTICES.md。

package io.github.mangi.eta.agent.pickup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.live.IslandPalette
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
    // v2：旧 pickup_code_island 渠道已建为 DEFAULT，已建渠道的重要性不可回改（同 id
    // 重建无效）；模板 16 的 islandFirstFloat 首浮需要警报级通道（参考实现岛通道
    // promoted_live_update_channel 即 IMPORTANCE_HIGH），换新 id 保证新旧装机都拿到 HIGH。
    private const val ISLAND_CHANNEL = "pickup_code_island_v2"

    /** 通知本体与「查看」按钮点击后打开取码列表；MainActivity 一次性消费。 */
    const val EXTRA_OPEN_PICKUP_CODES = "io.github.mangi.eta.extra.OPEN_PICKUP_CODES"
    private const val REQUEST_OPEN_PICKUP_CODES = 1209

    private fun openIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN_PICKUP_CODES,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_PICKUP_CODES, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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
        val style = Prefs.liveActivityStyle()
        when (style) {
            LiveActivityStyle.NATIVE -> notifyNative(appContext, manager, code, orderType, brandName)
            LiveActivityStyle.XIAOMI_ISLAND -> {
                val supported = MiuiIslandFocus.islandTerminalSupported()
                if (!supported) {
                    Log.w(TAG, "岛不可用（小米判定/焦点协议探测未过），落回原生：style=$style")
                }
                if (supported) {
                    runCatching { notifyLive(appContext, manager, code, orderType, brandName, pickupLocation, injectFocusParams = true) }
                        .onFailure { Log.w(TAG, "超级岛提醒失败，落回原生", it) }
                        .onSuccess { return }
                }
                notifyNative(appContext, manager, code, orderType, brandName)
            }
            LiveActivityStyle.COLOROS_FLUID -> {
                // 纯 LiveUpdate 形态自 Android 16.0（BAKLAVA）起即可被厂商桥接（T30），
                // 不必等 16.1 的 requestPromotedOngoing。
                if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA) {
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
        val open = openIntent(context)
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
            .setColor(orderTypeVisuals(orderType).palette.accentArgb)
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

    // ── 实时活动提醒（超级岛 / 流体云分形态）────────────────────────
    // 两档形态分叉（2026-11-22 按参考实现 SuperIslandHelper 逐字段定稿）：
    // - 超级岛档 = 澎湃记岛路径原样：promoted 三件套**不带**（promoted 管线与 miui.focus
    //   焦点参数互斥——参考在岛设备上显式关 requestPromotedOngoing，sendTestNotification
    //   的 `!isDeviceSupported` 分支实证；带着三件套发布 = HyperOS 走 Live Update 管线，
    //   焦点参数被无视退化成原生卡，即真机病根）、通知本体不挂 action（按钮走岛面
    //   actions bundle）、不锁 ongoing。岛面文案映射：大岛标题 = 码值本身（一眼看码），
    //   提示区 = 取餐位置优先、否则按类型给固定提示，岛面按钮 = 已取（完成广播）。
    // - 流体云档 = 纯官方 promoted 通道（无焦点参数），带锁 ongoing + 本体 action +
    //   promoted API 分层（ProgressStyle/shortCriticalText 自 16.0，requestPromotedOngoing
    //   自 16.1；16.0 只带前两者也走 LiveUpdate 形态，整档跳过会退化成普通通知）。
    // 两档 id = code.hashCode()（参考 per-order id 口径）：每个码独立首发位（新码 = 新
    // id = 必定再展开），通知与岛**常驻**直到完成/删除——撤卡统一由仓库层联动
    // （[PickupCodeRepository] 各变更路径调 [cancelCode]，参考 cancelNotification 口径）。

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
        val open = openIntent(context)
        // 与原生分支同一个广播语义（同 intent 自然复用同一个 PendingIntent）。
        val doneIntent = PendingIntent.getBroadcast(
            context,
            code.hashCode(),
            Intent(context, PickupCodeCompleteReceiver::class.java)
                .setAction(PickupCodeCompleteReceiver.ACTION)
                .putExtra(PickupCodeCompleteReceiver.EXTRA_CODE, code),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val visuals = orderTypeVisuals(orderType)
        val palette = visuals.palette
        val builder = Notification.Builder(context, ISLAND_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(palette.accentArgb)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_REMINDER)
        if (injectFocusParams) {
            // 超级岛档：extras 三件 = 模板 16 标准四图 + 岛面已取按钮 + 焦点参数。
            // 前两件构造器返回参考样式的外层包装（{"miui.focus.pics": 内层}），必须走
            // addExtras 合并进 extras（参考即 addExtras）——用 putBundle 会
            // 多套一层，systemui 在 extras["miui.focus.actions"] 下读不到动作表，
            // 岛面按钮整颗消失、岛图退化成应用图标（2026-11-22 真机实证）。
            // 内容图 = 品类语义圆砖（图形与颜色都区分餐食/饮品/快递），失败退桌面图标。
            val contentIcon = MiuiIslandFocus.typeTileIcon(context, visuals.glyphRes, palette)
                ?: MiuiIslandFocus.launchIconBitmap(context)?.let(Icon::createWithBitmap)
            builder.addExtras(MiuiIslandFocus.buildTemplate16Pics(context, contentIcon))
            builder.addExtras(
                MiuiIslandFocus.buildTemplate16Actions(
                    context.getString(R.string.pickup_island_action_done),
                    doneIntent,
                ),
            )
            builder.extras.putString(
                "miui.focus.param",
                // 不截断：码值与提示都是短串，take(16) 会切掉长取件码的数字尾巴。
                MiuiIslandFocus.buildPickupFocusParam(
                    ticker = content,
                    islandTitle = code,
                    iconTextContent = title,
                    hintTitle = islandHint(context, orderType, pickupLocation),
                    actionTitle = context.getString(R.string.pickup_island_action_done),
                    targetPage = MainActivity::class.java.name,
                    palette = palette,
                ),
            )
        } else {
            // 流体云档：纯官方 promoted 通道，厂商（ColorOS/HyperOS 等）自行桥接。
            builder
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                    Notification.Action.Builder(null, context.getString(R.string.pickup_action_mark_done), doneIntent).build(),
                )
                .addAction(
                    Notification.Action.Builder(null, context.getString(R.string.island_action_view), open).build(),
                )
            if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA) {
                builder
                    .setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
                    .setShortCriticalText(code)
                if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                    builder.setRequestPromotedOngoing(true)
                }
            }
        }
        manager.notify(code.hashCode(), builder.build())
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

    /** 品类图形与色板同一映射，避免新增品类时只换图忘记换色。 */
    private data class PickupVisuals(val glyphRes: Int, val palette: IslandPalette)

    /** orderType 是 DB 数据值「餐食/饮品/快递」；未知值按餐食回退。 */
    private fun orderTypeVisuals(orderType: String): PickupVisuals =
        when (orderType) {
            "快递" -> PickupVisuals(R.drawable.ic_pickup_type_express, IslandPalette.PickupExpress)
            "饮品" -> PickupVisuals(R.drawable.ic_pickup_type_drink, IslandPalette.PickupDrink)
            else -> PickupVisuals(R.drawable.ic_pickup_type_food, IslandPalette.PickupFood)
        }

    private fun ensureIslandChannel(context: Context, manager: NotificationManager) {
        if (manager.getNotificationChannel(ISLAND_CHANNEL) != null) return
        // HIGH：islandFirstFloat 首浮的通道档位对齐参考实现；声音振动仍关（岛是环境形态）。
        val channel = NotificationChannel(
            ISLAND_CHANNEL,
            context.getString(R.string.pickup_island_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        )
        channel.setSound(null, null)
        channel.enableVibration(false)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    /**
     * 撤掉某码的提醒通知（原生与岛共用 id = code.hashCode() 单槽，重复撤是无害 no-op）。
     * 由 [PickupCodeRepository] 的完成/删除/编辑路径联动调用，保证岛与原生提醒常驻到
     * 记录消失为止（参考澎湃记 cancelNotification(orderId)）。
     */
    fun cancelCode(context: Context, code: String) {
        runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.cancel(code.hashCode())
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
                // 未命中未完成记录也兜底撤卡（记录可能已被其他路径处理掉）：岛/原生共用此 id。
                PickupCodeNotifier.cancelCode(context, code)
            }
            onDone()
        }
    }
}
