package io.github.mangi.eta.agent.tool

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.data.db.AgentAlarmEntity
import io.github.mangi.eta.data.db.AgentScheduleEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.MainActivity
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Agent 日程提醒与闹钟的本地调度。所有到点触发都由本对象注册：
 * - 日程提醒用 setAndAllowWhileIdle（提醒级精度足够，无需精确闹钟许可）；
 * - 闹钟用 setAlarmClock（任意 API 级别都精确且无需 SCHEDULE_EXACT_ALARM 许可，
 *   系统状态栏会显示闹钟图标，行为与用户预期一致）。
 * 删除条目后保留的 PendingIntent 触发时查不到数据，会直接跳过——不需要逐条取消。
 */
internal object AgentScheduleAlarms {
    const val EXTRA_KIND = "io.github.mangi.eta.extra.AGENT_SCHEDULE_KIND"
    const val EXTRA_ID = "io.github.mangi.eta.extra.AGENT_SCHEDULE_ID"
    const val KIND_ALARM = "alarm"
    const val KIND_REMINDER = "reminder"

    internal const val CHANNEL_REMINDER = "eta_agent_schedule_reminder"
    internal const val CHANNEL_ALARM = "eta_agent_schedule_alarm"

    fun scheduleFor(context: Context, schedule: AgentScheduleEntity) {
        if (schedule.reminderMinutes < 0) return
        val triggerAt = schedule.startAt - TimeUnit.MINUTES.toMillis(schedule.reminderMinutes.toLong())
        // 提醒时刻已过去（或 past 日程被 update 带回）时不补发，避免过时打扰。
        if (triggerAt <= System.currentTimeMillis()) return
        scheduleAt(context, KIND_REMINDER, schedule.id, triggerAt)
    }

    fun cancelFor(context: Context, schedule: AgentScheduleEntity) {
        getAlarmManager(context).cancel(pendingIntent(context, KIND_REMINDER, schedule.id))
    }

    /** 计算并注册闹钟的下一次触发；返回触发时间（毫秒）。 */
    fun scheduleFor(context: Context, alarm: AgentAlarmEntity): Long {
        val triggerAt = AgentScheduleTime.nextTriggerAt(
            hour = alarm.hour,
            minute = alarm.minute,
            repeatDays = AgentScheduleTime.parseRepeatDays(alarm.repeatDays),
            now = System.currentTimeMillis(),
        )
        scheduleAt(context, KIND_ALARM, alarm.id, triggerAt)
        return triggerAt
    }

    fun cancelFor(context: Context, alarm: AgentAlarmEntity) {
        getAlarmManager(context).cancel(pendingIntent(context, KIND_ALARM, alarm.id))
    }

    /** 开机或升级后恢复：重排所有未来日程提醒与启用中的闹钟。 */
    fun restoreAll(context: Context) {
        val database = EtaDatabase.get(context)
        val now = System.currentTimeMillis()
        runCatching {
            database.agentScheduleDao().upcoming(now, Int.MAX_VALUE).forEach { scheduleFor(context, it) }
        }
        runCatching {
            database.agentAlarmDao().enabled().forEach { scheduleFor(context, it) }
        }
    }

    private fun scheduleAt(context: Context, kind: String, id: Long, triggerAt: Long) {
        val manager = getAlarmManager(context)
        val operation = pendingIntent(context, kind, id)
        when (kind) {
            KIND_ALARM -> manager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAt, mainActivityPending(context)),
                operation,
            )
            else -> manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        }
    }

    private fun pendingIntent(context: Context, kind: String, id: Long): PendingIntent {
        val intent = Intent(context, AgentScheduleAlertReceiver::class.java)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            (kind to id).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun mainActivityPending(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun getAlarmManager(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val reminder = NotificationChannel(
            CHANNEL_REMINDER,
            context.getString(R.string.agent_schedule_channel_reminder),
            NotificationManager.IMPORTANCE_HIGH,
        )
        reminder.description = context.getString(R.string.agent_schedule_channel_reminder_desc)
        manager.createNotificationChannel(reminder)
        val alarm = NotificationChannel(
            CHANNEL_ALARM,
            context.getString(R.string.agent_schedule_channel_alarm),
            NotificationManager.IMPORTANCE_HIGH,
        )
        alarm.description = context.getString(R.string.agent_schedule_channel_alarm_desc)
        alarm.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        manager.createNotificationChannel(alarm)
    }
}

/** 到点触发：条目已被删除 / 修改后，按最新数据行事；查不到就静默跳过。 */
internal class AgentScheduleAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra(AgentScheduleAlarms.EXTRA_KIND) ?: return
        val id = intent.getLongExtra(AgentScheduleAlarms.EXTRA_ID, -1L)
        if (id < 0) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val appContext = context.applicationContext
                AgentScheduleAlarms.ensureChannels(appContext)
                val database = EtaDatabase.get(appContext)
                when (kind) {
                    AgentScheduleAlarms.KIND_ALARM -> {
                        val alarm = database.agentAlarmDao().byId(id) ?: return@launch
                        if (alarm.enabled) {
                            notify(
                                context,
                                alarmKind = true,
                                notificationId = id.toInt(),
                                title = alarm.label.ifBlank {
                                    appContext.getString(R.string.agent_schedule_default_alarm_title)
                                },
                                text = AgentScheduleTime.describeTime(alarm.hour, alarm.minute) +
                                    if (alarm.repeatDays.isBlank()) "" else " ${alarm.repeatDays}",
                            )
                            // 单次闹钟触发后自动停用，list 不再返回过期条目；重复闹钟注册下一次。
                            if (AgentScheduleTime.parseRepeatDays(alarm.repeatDays).isEmpty()) {
                                database.agentAlarmDao()
                                    .update(alarm.copy(enabled = false, updatedAt = System.currentTimeMillis()))
                            } else {
                                AgentScheduleAlarms.scheduleFor(appContext, alarm)
                            }
                        }
                    }
                    AgentScheduleAlarms.KIND_REMINDER -> {
                        val schedule = database.agentScheduleDao().byId(id) ?: return@launch
                        notify(
                            context,
                            alarmKind = false,
                            notificationId = (id + Int.MAX_VALUE / 2).toInt(),
                            title = schedule.title,
                            text = schedule.notes,
                        )
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun notify(
        context: Context,
        alarmKind: Boolean,
        notificationId: Int,
        title: String,
        text: String,
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(
            context,
            if (alarmKind) AgentScheduleAlarms.CHANNEL_ALARM else AgentScheduleAlarms.CHANNEL_REMINDER,
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.take(80))
            .setContentText(
                text.take(120).ifBlank {
                    context.getString(R.string.agent_schedule_default_alarm_title)
                },
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(if (alarmKind) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        runCatching { manager.notify(notificationId, notification) }
    }
}

/** 开机补排：系统重启会清掉 AlarmManager 注册，恢复未来日程与启用中的闹钟。 */
internal class AgentScheduleBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                AgentScheduleAlarms.restoreAll(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}

/** 日程 / 闹钟的时间解析与重复规则计算。纯逻辑，可单测。 */
internal object AgentScheduleTime {
    private val ISO_DATETIME = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(Z)?""")
    private val ISO_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    /**
     * 解析日程时间。接受 epoch 毫秒、ISO 8601（yyyy-MM-ddTHH:mm[:ss][Z]）、
     * "yyyy-MM-dd HH:mm[:ss]"、纯日期（当天 00:00）。无时区一律按设备本地时区。
     * 返回 null 时由调用方给出格式样例，避免模型反复试错。
     */
    fun parseTime(raw: String, now: Long = System.currentTimeMillis()): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return it }
        ISO_DATETIME.matchEntire(text)?.let { match ->
            val year = match.groupValues[1].toInt()
            val month = match.groupValues[2].toInt() - 1
            val day = match.groupValues[3].toInt()
            val hour = match.groupValues[4].toInt()
            val minute = match.groupValues[5].toInt()
            val second = match.groupValues[6].ifEmpty { "0" }.toInt()
            val calendar = Calendar.getInstance()
            calendar.clear()
            calendar.set(year, month, day, hour, minute, second)
            calendar.set(Calendar.MILLISECOND, 0)
            var millis = calendar.timeInMillis
            if (match.groupValues[7] == "Z") {
                // 显式 UTC：按本地解释出的伪值再减去本地偏移，还原真实时刻。
                val offset = Calendar.getInstance().run {
                    clear()
                    set(year, month, day, hour, minute, second)
                    get(Calendar.ZONE_OFFSET) + get(Calendar.DST_OFFSET)
                }
                millis -= offset
            }
            return millis
        }
        ISO_DATE.matchEntire(text)?.let { match ->
            val calendar = Calendar.getInstance()
            calendar.clear()
            calendar.set(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt() - 1,
                match.groupValues[3].toInt(),
                0,
                0,
                0,
            )
            calendar.set(Calendar.MILLISECOND, 0)
            return calendar.timeInMillis
        }
        return null
    }

    /** 解析重复星期：mon..sun（大小写不敏感）或 1..7；"daily"/"每天"=每天；空=单次。 */
    fun parseRepeatDays(raw: String): Set<Int> {
        val text = raw.trim().lowercase(Locale.ROOT)
        if (text.isEmpty()) return emptySet()
        if (text == "daily" || text == "每天") return setOf(1, 2, 3, 4, 5, 6, 7)
        return text.split(',', '、', ' ')
            .mapNotNull { token ->
                when (token.trim()) {
                    "mon" -> 1; "tue" -> 2; "wed" -> 3; "thu" -> 4
                    "fri" -> 5; "sat" -> 6; "sun" -> 7
                    else -> token.trim().toIntOrNull()?.takeIf { it in 1..7 }
                }
            }.toSet()
    }

    fun formatRepeatDays(days: Set<Int>): String = days.sorted().joinToString(",")

    /** 下一次触发时刻：今天已过就顺延到明天；带重复星期时取下一个匹配日。 */
    fun nextTriggerAt(hour: Int, minute: Int, repeatDays: Set<Int>, now: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = now
        calendar.set(Calendar.HOUR_OF_DAY, hour)
        calendar.set(Calendar.MINUTE, minute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        var candidate = calendar.timeInMillis
        if (repeatDays.isEmpty()) {
            if (candidate <= now) candidate += TimeUnit.DAYS.toMillis(1)
            return candidate
        }
        var guard = 0
        while (candidate <= now || dayOfWeek(candidate) !in repeatDays) {
            candidate += TimeUnit.DAYS.toMillis(1)
            if (++guard > 400) break
        }
        return candidate
    }

    /** 统一星期口径：1=周一 … 7=周日。 */
    fun dayOfWeek(millis: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = millis
        return ((calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1
    }

    fun describeTime(hour: Int, minute: Int): String = "%02d:%02d".format(Locale.ROOT, hour, minute)
}
