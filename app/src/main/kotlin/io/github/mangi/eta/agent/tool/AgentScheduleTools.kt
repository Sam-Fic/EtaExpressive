package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.data.db.AgentAlarmEntity
import io.github.mangi.eta.data.db.AgentScheduleEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.AgentScheduleDao
import io.github.mangi.eta.data.db.AgentAlarmDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 日程与闹钟的模型工具。设计目标是一次调用办完事、结果自解释，避免模型反复试探：
 * - 创建 / 修改一律回显规范化后的完整条目（含 id），模型不需要再 list 确认；
 * - list 返回修改和删除所需的全部字段，省掉一次 get；
 * - delete 目标不存在时返回 ok=true + already_gone=true（幂等），模型不会重试；
 * - 所有失败都带具体原因和正确格式样例，杜绝「参数错了再猜一次」的循环；
 * - 时间解析失败时回显当前时间与可接受格式。
 */
internal class AgentScheduleTools(private val context: Context) {
    fun execute(name: String, args: JSONObject): String = when (name) {
        "schedule_manage" -> schedule(args)
        "alarm_manage" -> alarm(args)
        else -> fail("UNKNOWN_TOOL", "未知工具：$name")
    }

    private val scheduleDao: AgentScheduleDao get() = EtaDatabase.get(context).agentScheduleDao()
    private val alarmDao: AgentAlarmDao get() = EtaDatabase.get(context).agentAlarmDao()

    // ---------- 日程 ----------

    private fun schedule(args: JSONObject): String = when (val action = args.optString("action")) {
        "create" -> scheduleCreate(args)
        "update" -> scheduleUpdate(args)
        "delete" -> scheduleDelete(args)
        "list" -> scheduleList(args)
        else -> fail("UNKNOWN_ACTION", "action 只支持 create/update/delete/list，收到 \"$action\"")
    }

    private fun scheduleCreate(args: JSONObject): String {
        val title = args.optString("title").trim()
        if (title.isEmpty()) return fail("MISSING_TITLE", "title 不能为空，用一句话概括日程内容")
        val now = System.currentTimeMillis()
        val startAt = parseTimeArg(args.optString("start_time"), "start_time")
            ?: return fail("BAD_START_TIME", timeError("start_time"))
        val endAt = args.optString("end_time").takeIf { it.isNotBlank() && it != "null" }
            ?.let { parseTimeArg(it, "end_time") ?: return fail("BAD_END_TIME", timeError("end_time")) }
        if (endAt != null && endAt < startAt) {
            return fail("END_BEFORE_START", "end_time（${formatTime(endAt)}）早于 start_time（${formatTime(startAt)}），请交换或修正")
        }
        val reminder = args.optInt("reminder_minutes", DEFAULT_REMINDER_MINUTES)
        if (reminder !in -1..REMINDER_MAX_MINUTES) {
            return fail("BAD_REMINDER", "reminder_minutes 取值 -1（不提醒）到 $REMINDER_MAX_MINUTES（7 天），收到 $reminder")
        }
        val entity = AgentScheduleEntity(
            title = title.take(200),
            notes = args.optString("notes").take(2000),
            startAt = startAt,
            endAt = endAt,
            reminderMinutes = reminder,
            createdAt = now,
            updatedAt = now,
        )
        val id = scheduleDao.insert(entity)
        val saved = entity.copy(id = id)
        AgentScheduleAlarms.scheduleFor(context, saved)
        return ok(
            "schedule_manage",
            "create",
            scheduleJson(saved).put(
                "hint",
                if (startAt <= now) "开始时间已是过去，提醒不会触发，其余字段照常保存"
                else "已创建，id=$id，到点前 ${if (reminder < 0) "不" else "$reminder 分钟"}提醒",
            ),
        )
    }

    private fun scheduleUpdate(args: JSONObject): String {
        val id = args.optLong("id", -1L)
        val current = scheduleDao.byId(id)
            ?: return fail("NOT_FOUND", "id=$id 的日程不存在；调用 action=list 查看现有 id，不要猜测")
        val startAt = args.optString("start_time").takeIf { it.isNotBlank() && it != "null" }
            ?.let { parseTimeArg(it, "start_time") ?: return fail("BAD_START_TIME", timeError("start_time")) }
            ?: current.startAt
        val endAt = args.optString("end_time").takeIf { it.isNotBlank() && it != "null" }
            ?.let { parseTimeArg(it, "end_time") ?: return fail("BAD_END_TIME", timeError("end_time")) }
            ?: current.endAt
        if (endAt != null && endAt < startAt) {
            return fail("END_BEFORE_START", "end_time 早于 start_time，请修正")
        }
        val reminder = if (args.has("reminder_minutes")) args.optInt("reminder_minutes", Int.MIN_VALUE)
        else current.reminderMinutes
        if (reminder !in -1..REMINDER_MAX_MINUTES) {
            return fail("BAD_REMINDER", "reminder_minutes 取值 -1 到 $REMINDER_MAX_MINUTES")
        }
        val updated = current.copy(
            title = args.optString("title").takeIf { it.isNotBlank() }?.take(200) ?: current.title,
            notes = if (args.has("notes")) args.optString("notes").take(2000) else current.notes,
            startAt = startAt,
            endAt = endAt,
            reminderMinutes = reminder,
            updatedAt = System.currentTimeMillis(),
        )
        scheduleDao.update(updated)
        AgentScheduleAlarms.cancelFor(context, current)
        AgentScheduleAlarms.scheduleFor(context, updated)
        return ok("schedule_manage", "update", scheduleJson(updated))
    }

    private fun scheduleDelete(args: JSONObject): String {
        val id = args.optLong("id", -1L)
        val current = scheduleDao.byId(id)
            ?: return ok("schedule_manage", "delete", JSONObject().put("id", id).put("already_gone", true))
        AgentScheduleAlarms.cancelFor(context, current)
        scheduleDao.delete(id)
        return ok(
            "schedule_manage",
            "delete",
            JSONObject()
                .put("id", id)
                .put("already_gone", false)
                .put("title", current.title),
        )
    }

    private fun scheduleList(args: JSONObject): String {
        val includePast = args.optBoolean("include_past", false)
        val query = args.optString("query").trim().lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val items = scheduleDao.upcoming(if (includePast) 0 else now, limit)
            .asSequence()
            .filter { query.isEmpty() || it.title.lowercase(Locale.ROOT).contains(query) || it.notes.lowercase(Locale.ROOT).contains(query) }
            .take(limit)
            .toList()
        val payload = JSONObject()
            .put("count", items.size)
            .put("items", JSONArray().apply { items.forEach { put(scheduleJson(it)) } })
        if (items.isEmpty()) {
            payload.put(
                "hint",
                if (query.isEmpty()) "当前没有日程（用 action=create 创建）"
                else "没有匹配 \"$query\" 的日程；用 include_past=true 查看过去的日程",
            )
        }
        return ok("schedule_manage", "list", payload)
    }

    private fun scheduleJson(entity: AgentScheduleEntity): JSONObject = JSONObject()
        .put("id", entity.id)
        .put("title", entity.title)
        .put("notes", entity.notes)
        .put("start_time", formatTime(entity.startAt))
        .put("end_time", entity.endAt?.let(::formatTime) ?: JSONObject.NULL)
        .put("reminder_minutes", entity.reminderMinutes)

    // ---------- 闹钟 ----------

    private fun alarm(args: JSONObject): String = when (val action = args.optString("action")) {
        "create" -> alarmCreate(args)
        "update" -> alarmUpdate(args)
        "delete" -> alarmDelete(args)
        "list" -> alarmList(args)
        else -> fail("UNKNOWN_ACTION", "action 只支持 create/update/delete/list，收到 \"$action\"")
    }

    private fun alarmCreate(args: JSONObject): String {
        val parsed = parseAlarmTime(args) ?: return fail("BAD_TIME", alarmTimeError())
        val repeatDays = parseRepeatArg(args)
            ?: return fail("BAD_REPEAT_DAYS", "repeat_days 只接受 mon/tue/wed/thu/fri/sat/sun 或 1-7，多值用逗号分隔；\"daily\" 表示每天")
        val now = System.currentTimeMillis()
        val entity = AgentAlarmEntity(
            hour = parsed.first,
            minute = parsed.second,
            label = args.optString("label").take(100),
            repeatDays = AgentScheduleTime.formatRepeatDays(repeatDays),
            enabled = true,
            createdAt = now,
            updatedAt = now,
        )
        val id = alarmDao.insert(entity)
        val saved = entity.copy(id = id)
        val triggerAt = AgentScheduleAlarms.scheduleFor(context, saved)
        return ok(
            "alarm_manage",
            "create",
            alarmJson(saved, triggerAt).put(
                "hint",
                "闹钟已创建，id=$id；此后用本 id 修改或删除。时间 ${AgentScheduleTime.describeTime(parsed.first, parsed.second)}" +
                    if (repeatDays.isEmpty()) "，仅下一次" else "，重复 ${saved.repeatDays}",
            ),
        )
    }

    private fun alarmUpdate(args: JSONObject): String {
        val id = args.optLong("id", -1L)
        val current = alarmDao.byId(id)
            ?: return fail("NOT_FOUND", "id=$id 的闹钟不存在；调用 action=list 查看现有 id，不要猜测")
        // 时间可选：只改标签 / 重复 / 开关时保持原时间。
        val hasTime = args.optString("time").trim().let { it.isNotBlank() && it != "null" } ||
            args.has("hour") || args.has("minute")
        val parsed = if (hasTime) parseAlarmTime(args) ?: return fail("BAD_TIME", alarmTimeError())
        else current.hour to current.minute
        val repeatDays = args.optString("repeat_days").takeIf { it.isNotBlank() && it != "null" }
            ?.let { parseRepeatArg(args) ?: return fail("BAD_REPEAT_DAYS", "repeat_days 只接受 mon..sun 或 1-7；\"daily\" 表示每天") }
            ?: AgentScheduleTime.parseRepeatDays(current.repeatDays)
        val enabled = if (args.has("enabled")) args.optBoolean("enabled") else current.enabled
        val updated = current.copy(
            hour = parsed.first,
            minute = parsed.second,
            label = args.optString("label").takeIf { it.isNotBlank() }?.take(100) ?: current.label,
            repeatDays = AgentScheduleTime.formatRepeatDays(repeatDays),
            enabled = enabled,
            updatedAt = System.currentTimeMillis(),
        )
        alarmDao.update(updated)
        AgentScheduleAlarms.cancelFor(context, current)
        val triggerAt = if (updated.enabled) AgentScheduleAlarms.scheduleFor(context, updated) else 0L
        return ok("alarm_manage", "update", alarmJson(updated, triggerAt))
    }

    private fun alarmDelete(args: JSONObject): String {
        val id = args.optLong("id", -1L)
        val current = alarmDao.byId(id)
            ?: return ok("alarm_manage", "delete", JSONObject().put("id", id).put("already_gone", true))
        AgentScheduleAlarms.cancelFor(context, current)
        alarmDao.delete(id)
        return ok(
            "alarm_manage",
            "delete",
            JSONObject()
                .put("id", id)
                .put("already_gone", false)
                .put("label", current.label),
        )
    }

    private fun alarmList(args: JSONObject): String {
        val items = alarmDao.all()
        val payload = JSONObject()
            .put("count", items.size)
            .put(
                "items",
                JSONArray().apply {
                    items.forEach {
                        val triggerAt = if (it.enabled) {
                            AgentScheduleTime.nextTriggerAt(
                                it.hour,
                                it.minute,
                                AgentScheduleTime.parseRepeatDays(it.repeatDays),
                                System.currentTimeMillis(),
                            )
                        } else 0L
                        put(alarmJson(it, triggerAt))
                    }
                },
            )
        if (items.isEmpty()) payload.put("hint", "还没有闹钟（用 action=create 创建）")
        return ok("alarm_manage", "list", payload)
    }

    private fun alarmJson(entity: AgentAlarmEntity, nextTriggerAt: Long): JSONObject = JSONObject()
        .put("id", entity.id)
        .put("time", AgentScheduleTime.describeTime(entity.hour, entity.minute))
        .put("hour", entity.hour)
        .put("minute", entity.minute)
        .put("label", entity.label)
        .put("repeat_days", entity.repeatDays.ifEmpty { JSONObject.NULL })
        .put("enabled", entity.enabled)
        .put("next_trigger", if (nextTriggerAt > 0) formatTime(nextTriggerAt) else JSONObject.NULL)

    // ---------- 参数解析 ----------

    /** 闹钟时间：优先 "HH:mm"，也接受 hour/minute 数字。 */
    private fun parseAlarmTime(args: JSONObject): Pair<Int, Int>? {
        val time = args.optString("time").trim()
        if (time.isNotBlank() && time != "null") {
            Regex("""(\d{1,2}):(\d{2})""").matchEntire(time)?.let { match ->
                val hour = match.groupValues[1].toInt()
                val minute = match.groupValues[2].toInt()
                if (hour in 0..23 && minute in 0..59) return hour to minute
            }
            return null
        }
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", -1)
        if (hour in 0..23 && minute in 0..59) return hour to minute
        return null
    }

    private fun parseRepeatArg(args: JSONObject): Set<Int>? {
        val raw = args.optJSONArray("repeat_days")?.let { array ->
            (0 until array.length()).joinToString(",") { array.optString(it) }
        } ?: args.optString("repeat_days")
        if (raw.isBlank() || raw == "null") return emptySet()
        val parsed = AgentScheduleTime.parseRepeatDays(raw)
        // 全部 token 都无法解析时视为参数错误（空集合可能是合法的"仅一次"，也可能是纯噪声）。
        val tokens = raw.split(',', '、', ' ').filter { it.isNotBlank() }
        return if (tokens.isNotEmpty() && parsed.isEmpty()) null else parsed
    }

    private fun parseTimeArg(raw: String, field: String): Long? =
        AgentScheduleTime.parseTime(raw)

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(millis))

    private fun timeError(field: String): String =
        "$field 无法解析。当前设备时间 ${formatTime(System.currentTimeMillis())}；" +
            "可接受格式：\"2026-10-24T09:30\"、\"2026-10-24 09:30:00\"、\"2026-10-24\" 或 epoch 毫秒"

    private fun alarmTimeError(): String =
        "闹钟时间无法解析。可接受格式：time=\"07:30\"（24 小时制）或 hour=7 加 minute=30"

    private fun ok(tool: String, action: String, payload: JSONObject): String =
        JSONObject()
            .put("ok", true)
            .put("tool", tool)
            .put("action", action)
            .put("result", payload)
            .toString()

    private fun fail(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("error", code)
            .put("message", message)
            .toString()

    private companion object {
        const val DEFAULT_REMINDER_MINUTES = 15
        const val REMINDER_MAX_MINUTES = 7 * 24 * 60
    }
}
