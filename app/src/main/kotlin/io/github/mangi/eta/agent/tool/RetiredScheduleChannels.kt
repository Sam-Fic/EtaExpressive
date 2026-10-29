package io.github.mangi.eta.agent.tool

import android.app.NotificationManager
import android.content.Context

/**
 * Eta 私有日程/闹钟下线后的渠道清理。
 *
 * 通知渠道是系统级的：创建它的代码删掉后，渠道仍然留在系统通知设置里，
 * 用户会看到两个再也不会响的通知项。删除是幂等的，渠道不存在时系统直接忽略。
 */
internal object RetiredScheduleChannels {
    private val RETIRED = listOf(
        "eta_agent_schedule_reminder",
        "eta_agent_schedule_alarm",
    )

    fun cleanup(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        RETIRED.forEach(manager::deleteNotificationChannel)
    }
}
