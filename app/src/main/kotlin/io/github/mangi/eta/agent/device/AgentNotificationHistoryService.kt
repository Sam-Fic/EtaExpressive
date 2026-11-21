package io.github.mangi.eta.agent.device

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.mangi.eta.agent.pickup.PickupCodeNotificationObserver
import io.github.mangi.eta.data.repository.NotificationHistoryRepository

class AgentNotificationHistoryService : NotificationListenerService() {
    private val repository by lazy { NotificationHistoryRepository(this) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        record(sbn)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectedService = this
        activeNotifications?.forEach(::record)
    }

    override fun onListenerDisconnected() {
        if (connectedService === this) connectedService = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (connectedService === this) connectedService = null
        super.onDestroy()
    }

    private fun record(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        repository.record(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            text = text,
            subText = subText,
            postedAt = sbn.postTime,
        )
        // 取餐码自动拦截：fire-and-forget，通知历史照旧入库，两链路互不阻塞。
        // 很多外卖/快递通知把正文放在 EXTRA_BIG_TEXT，这里只为拦截链路多取一份。
        PickupCodeNotificationObserver.onPosted(
            context = applicationContext,
            packageName = sbn.packageName,
            title = title,
            text = text,
            subText = listOfNotNull(
                subText,
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            ).filter { it.isNotBlank() }.joinToString("\n").ifBlank { null },
        )
    }

    companion object {
        @Volatile
        private var connectedService: AgentNotificationHistoryService? = null

        internal fun currentNotifications(): Array<StatusBarNotification>? {
            val service = connectedService ?: return null
            return try {
                service.activeNotifications
            } catch (_: RuntimeException) {
                null
            }
        }

        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
                ?: return false
            return manager.isNotificationListenerAccessGranted(
                ComponentName(context, AgentNotificationHistoryService::class.java),
            )
        }
    }
}
