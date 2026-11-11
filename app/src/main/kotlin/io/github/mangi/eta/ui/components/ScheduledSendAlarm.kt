package io.github.mangi.eta.ui.components

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.ComposerQueueStore
import io.github.mangi.eta.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 「定时发送」的本地调度：到点唤醒 [ScheduledSendReceiver] 弹出提醒，
 * 应用随后在合适时机把消息真正发出。
 */
internal object ScheduledSendAlarm {
    const val EXTRA_CONVERSATION_ID = "io.github.mangi.eta.extra.SCHEDULED_CONVERSATION_ID"
    private const val ACTION = "io.github.mangi.eta.action.SCHEDULED_SEND"

    fun schedule(
        context: Context,
        conversationId: String,
        text: String,
        dueAtMillis: Long,
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        // 非精确闹钟不需要 SCHEDULE_EXACT_ALARM 权限，误差在系统允许范围内。
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            dueAtMillis,
            pendingIntent(context, conversationId, text),
        )
    }

    fun cancel(context: Context, conversationId: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.cancel(pendingIntent(context, conversationId, null))
    }

    private fun pendingIntent(
        context: Context,
        conversationId: String,
        text: String?,
    ): PendingIntent {
        val intent = Intent(context, ScheduledSendReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_TEXT, text.orEmpty())
        }
        return PendingIntent.getBroadcast(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal const val EXTRA_TEXT = "io.github.mangi.eta.extra.SCHEDULED_TEXT"
}

/** 定时消息到点后的提醒广播。 */
internal class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val conversationId =
            intent.getStringExtra(ScheduledSendAlarm.EXTRA_CONVERSATION_ID) ?: return
        val text = intent.getStringExtra(ScheduledSendAlarm.EXTRA_TEXT).orEmpty()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.ssa_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ScheduledSendAlarm.EXTRA_CONVERSATION_ID, conversationId)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // 定时消息全文不上锁屏：锁屏通知默认可见，旁人可读内容。VISIBILITY_SECRET
            // 只影响锁屏呈现，解锁后的通知抽屉照常显示全文。
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
        runCatching { manager.notify(conversationId.hashCode(), notification) }
        // 到点即从队列移除；真正的发送由应用在目标会话处于前台时完成。
        val appContext = context.applicationContext
        runCatching {
            CoroutineScope(Dispatchers.IO).launch {
                ComposerQueueStore.clearScheduled(appContext, conversationId)
            }
        }
    }

    private companion object {
        const val CHANNEL_ID = "eta_scheduled_send"
    }
}
