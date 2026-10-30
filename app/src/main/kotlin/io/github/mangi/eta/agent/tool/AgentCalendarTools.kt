package io.github.mangi.eta.agent.tool

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.CalendarPermissions
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 日程（系统日历）写入工具：创建 / 修改 / 删除，直接调用系统日历 Provider。
 *
 * 设计约束（与 [AgentPersonalDataTools] 的读取侧一致）：
 * - 创建日程优先用应用自身 ContentResolver（持有 WRITE_CALENDAR 时），失败再退回 Root `content insert`；
 *   修改/删除优先走 Root `content update/delete`，无 Root 时改用 CalendarContract + READ/WRITE_CALENDAR
 *   运行时权限直接读写，二者都不可用才降级为预填日历界面；
 * - 修改/删除按 event_id 或标题定位；匹配多个时返回候选列表而不是猜一个，避免模型反复重试；
 * - 每次写人后都回读校验（read-after-write），不把"命令已发出"当成"已生效"；
 * - 所有失败都带可执行的错误码与下一步提示，模型据此修正一次或如实告知用户，不诱发循环。
 */
internal class AgentCalendarTools(
    private val context: Context,
    private val root: BoundedRootCommandExecutor,
    private val rootAvailable: () -> Boolean,
) {
    fun execute(name: String, args: JSONObject): AgentModelClient.ToolResult? = when (name) {
        "create_calendar_event" -> create(args)
        "create_calendar_events" -> createMany(args)
        "update_calendar_event" -> update(args)
        "delete_calendar_event" -> delete(args)
        "list_calendars" -> listCalendars()
        "read_calendar_event" -> readCalendarEvent(args)
        else -> null
    }

    // ---------------------------------------------------------------- 查询

    /** 列出当前用户可写的日历：创建日程时必须选真实 calendar_id，不能猜。 */
    private fun listCalendars(): AgentModelClient.ToolResult {
        val rows = query(
            uri = CALENDARS_URI,
            projection = listOf("_id", "calendar_displayName", "account_name", "calendar_access_level", "visible"),
            where = "visible=1",
            sort = null,
        ) ?: return calendarAccessError()
        val calendars = JSONArray()
        rows.forEach { row ->
            val id = row.optLongValue("_id") ?: return@forEach
            val access = row.optLongValue("calendar_access_level") ?: 0L
            if (id <= 0L || access < ACCESS_CONTRIBUTOR) return@forEach
            calendars.put(
                JSONObject()
                    .put("calendar_id", id.toString())
                    .put("name", row.optString("calendar_displayName").ifBlank { "未命名日历" })
                    .put("account", row.optString("account_name"))
                    .put("read_only", access < ACCESS_OWNER),
            )
        }
        return ok(
            JSONObject()
                .put("tool", "list_calendars")
                .put("calendars", calendars)
                .put("count", calendars.length())
                .put(
                    "summary",
                    if (calendars.length() == 0) "没有可写入的日历；请先在日历应用中创建一个日历"
                    else "共有 ${calendars.length()} 个可写入的日历",
                ),
        )
    }

    /** 按原始 event_id 读取实时事件和提醒；不接受 DMP 文档 ID。 */
    private fun readCalendarEvent(args: JSONObject): AgentModelClient.ToolResult {
        val eventId = args.optLongValue("event_id")
            ?: return error("INVALID_ARGUMENT", "请提供原始 event_id；可先用 search_calendar_events 查找")
        if (eventId <= 0L) return error("INVALID_ARGUMENT", "event_id 必须是正整数")
        val row = queryEventRow(eventId)
            ?: return error("EVENT_NOT_FOUND", "没有找到 id=$eventId 的日程；它可能已被删除，请用 search_calendar_events 重新查找")
        if ((row.optLongValue("deleted") ?: 0L) == 1L) {
            return error("EVENT_NOT_FOUND", "id=$eventId 的日程已被删除；请用 search_calendar_events 重新查找")
        }
        val reminders = query(
            uri = REMINDERS_URI,
            projection = listOf("minutes", "method"),
            where = "event_id=$eventId",
            sort = null,
        ) ?: emptyList()
        val allDay = (row.optLongValue("allDay") ?: 0L) == 1L
        return ok(
            JSONObject()
                .put("tool", "read_calendar_event")
                .put(
                    "event",
                    JSONObject()
                        .put("event_id", eventId)
                        .put("title", row.optString("title"))
                        .put("start_local", CalendarEventFormats.formatSchedule(row.optLongValue("dtstart") ?: 0L, allDay))
                        .put("end_local", CalendarEventFormats.formatSchedule(row.optLongValue("dtend") ?: 0L, allDay))
                        .put("all_day", allDay)
                        .put("description", row.optString("description"))
                        .put("location", row.optString("eventLocation"))
                        .put("repeat", row.optString("rrule")),
                )
                .put(
                    "reminders",
                    JSONArray().also { array ->
                        reminders.forEach { reminder ->
                            array.put(
                                JSONObject()
                                    .put("minutes", reminder.optLongValue("minutes"))
                                    .put("method", reminder.optLongValue("method")),
                            )
                        }
                    },
                ),
        )
    }

    private fun calendarAccessError(): AgentModelClient.ToolResult =
        if (!rootAvailable() && !CalendarPermissions.granted(context, write = false)) {
            error("CALENDAR_PERMISSION_REQUIRED", "未获得日历读取权限；请到权限页授予日历权限后重试")
        } else {
            error("CALENDAR_PROVIDER_UNAVAILABLE", "系统日历暂时不可访问；请稍后重试")
        }

    // ---------------------------------------------------------------- 创建

    private fun create(args: JSONObject): AgentModelClient.ToolResult {
        val title = args.optString("title").trim()
        if (title.isEmpty()) return error("INVALID_ARGUMENT", "title 不能为空")
        if (title.length > MAX_TITLE_CHARS) return error("INVALID_ARGUMENT", "title 最多 $MAX_TITLE_CHARS 字")
        val startParsed = CalendarEventFormats.parseScheduleTime(args.optString("start").trim())
            ?: return error("INVALID_ARGUMENT", "start 格式无效：请使用 YYYY-MM-DDTHH:MM（如 2026-10-24T15:30）或 YYYY-MM-DD")
        val endParsed = args.optString("end").trim().takeIf { it.isNotEmpty() }?.let {
            CalendarEventFormats.parseScheduleTime(it)
                ?: return error("INVALID_ARGUMENT", "end 格式无效：请使用与 start 相同的格式")
        }
        val allDay = if (args.has("all_day")) args.optBoolean("all_day") else startParsed.dateOnly
        val description = args.optString("description").trim()
        val location = args.optString("location").trim()
        val reminderMinutes = args.optInt("reminder_minutes", DEFAULT_REMINDER_MINUTES)
            .coerceIn(0, MAX_REMINDER_MINUTES)
        val repeatDays = args.optStringList("repeat_days")
        val rrule = CalendarEventFormats.buildRrule(args.optString("repeat").trim().takeIf { it.isNotEmpty() }, repeatDays)

        val startMillis = if (allDay) CalendarEventFormats.utcMidnightMillis(startParsed)
        else CalendarEventFormats.localMillis(startParsed)
        val endMillis = when {
            endParsed != null ->
                if (allDay) CalendarEventFormats.utcMidnightMillis(endParsed)
                else CalendarEventFormats.localMillis(endParsed)
            allDay -> startMillis + DAY_MILLIS
            else -> startMillis + HOUR_MILLIS
        }
        if (endMillis <= startMillis) return error("INVALID_ARGUMENT", "结束时间必须晚于开始时间")

        if (!rootAvailable() && !CalendarPermissions.granted(context, write = true)) return uiFallback(
            title = title,
            startMillis = startMillis,
            endMillis = endMillis,
            allDay = allDay,
            description = description,
            location = location,
            rrule = rrule,
            note = "未获得日历读写权限",
        )

        val requestedCalendarId = args.optLongValue("calendar_id")
        val calendar = resolveCalendar(requestedCalendarId)
            ?: return if (requestedCalendarId != null) {
                error("CALENDAR_NOT_WRITABLE", "calendar_id=$requestedCalendarId 的日历不存在或只读；请先用 list_calendars 确认可写日历")
            } else {
                error("NO_WRITABLE_CALENDAR", "系统日历里没有可写入的日历；请先在日历应用中创建一个日历")
            }
        val watermark = maxEventId()
            ?: return error("CALENDAR_PROVIDER_UNAVAILABLE", "系统日历暂时不可访问")
        val requiredBinds = buildList {
            add(Bind("calendar_id", 'l', calendar.id.toString()))
            add(Bind("title", 's', title))
            add(Bind("dtstart", 'l', startMillis.toString()))
            add(Bind("dtend", 'l', endMillis.toString()))
            add(Bind("eventTimezone", 's', if (allDay) UTC_ZONE else TimeZone.getDefault().id))
            add(Bind("allDay", 'i', if (allDay) "1" else "0"))
            add(Bind("hasAlarm", 'i', if (reminderMinutes > 0) "1" else "0"))
        }
        // 描述/地点/重复规则是可选字段：它们最长，也最容易带换行、URL 等厂家 Provider 不接受的内容。
        // 所以完整参数写不进去时退一步只写必填字段，至少让日程本身落库，并如实说明缺了什么。
        val optionalBinds = buildList {
            if (description.isNotEmpty()) add(Bind("description", 's', description))
            if (location.isNotEmpty()) add(Bind("eventLocation", 's', location))
            rrule?.let { add(Bind("rrule", 's', it)) }
        }
        val payloads = buildList {
            add(requiredBinds + optionalBinds)
            if (optionalBinds.isNotEmpty()) add(requiredBinds)
        }
        // 写入身份依次尝试：应用自身优先（受支持的第三方写入路径），Root 兜底。
        // 同一轮里两种身份都写不进去时，再降级为只写必填字段重试一次。
        val attempts = mutableListOf<ProviderResult>()
        var eventId: Long? = null
        var degraded = false
        payloadLoop@ for ((payloadIndex, payload) in payloads.withIndex()) {
            for (identity in insertIdentities()) {
                val insert = if (identity == IDENTITY_APP) insertViaApp(EVENTS_URI, payload) else insertViaRoot(EVENTS_URI, payload)
                attempts += insert
                if (!insert.ok || PersonalDataContentParser.hasProviderFailure(insert.stdout, insert.stderr)) continue
                // 命令成功不代表已落库：按 title 精确回读，并用写入前的最大 id 作为水位。
                val row = queryEvents(
                    where = "deleted=0 AND title='${CalendarEventFormats.escapeSqlString(title)}'",
                    sort = "_id DESC",
                    limit = 1,
                ) ?: return error("CALENDAR_PROVIDER_UNAVAILABLE", "系统日历暂时不可访问")
                val id = row.firstOrNull()?.let { it.optLongValue("_id") }
                if (id != null && id > watermark) {
                    eventId = id
                    degraded = payloadIndex > 0
                    break@payloadLoop
                }
            }
        }
        if (eventId == null) {
            val paths = attempts.joinToString("→") { it.path }
            val echo = providerEchoSuffix(attempts.last())
            return if (attempts.none { it.ok }) {
                // Provider 直接返回 null（写入未被接受，且 `content insert` 仍以退出码 0 结束）。
                // 这种情况下只有日历应用自己的同步身份能落库，改走预填界面让用户一键保存。
                uiFallback(
                    title = title,
                    startMillis = startMillis,
                    endMillis = endMillis,
                    allDay = allDay,
                    description = description,
                    location = location,
                    rrule = rrule,
                    note = "系统日历拒绝了直接写入（写入身份=$paths$echo）",
                )
            } else {
                // 写入命令「成功」但读不回来：目标日历可能拒绝了落库（如厂商同步日历只读），
                // 而不是校验缺陷。必须给出可执行的下一步，否则模型只会原样重试。
                // 附上写入身份与 provider 回显，否则下一次仍然只能靠猜。
                error(
                    "CALENDAR_INSERT_UNVERIFIED",
                    "写入命令已执行（写入身份=$paths）但回读不到新日程，目标日历「${calendar.name}」可能拒绝了写入。" +
                        insertUnverifiedAdvice() +
                        echo,
                )
            }
        }
        val warnings = mutableListOf<String>()
        if (degraded) {
            // 降级写入成功后，再把描述/地点/重复规则补回去：Provider 有时拒绝「带描述的新建」，
            // 却能接受对已存在日程的更新。补写失败时如实告知，不假装完整。
            if (!supplementEvent(eventId, optionalBinds)) {
                warnings += "描述/地点/重复规则未能写入：日程已按标题与时间创建，请让用户核对并手动补充这些信息"
            }
        }
        if (reminderMinutes > 0 && !insertReminder(eventId, reminderMinutes)) {
            warnings += "提前提醒写入失败：日程已创建，但可能不会弹出通知"
        }
        return ok(
            JSONObject()
                .put("tool", "create_calendar_event")
                .put("event_id", eventId)
                .put("calendar", calendar.name)
                .put("title", title)
                .put("start_local", CalendarEventFormats.formatSchedule(startMillis, allDay))
                .put("end_local", CalendarEventFormats.formatSchedule(endMillis, allDay))
                .put("all_day", allDay)
                .put("repeat", CalendarEventFormats.repeatLabel(rrule))
                .put("reminder_minutes", reminderMinutes)
                .put("summary", CalendarEventFormats.summary("已创建", title, startMillis, endMillis, allDay, reminderMinutes, rrule))
                .also { payload -> if (warnings.isNotEmpty()) payload.put("warnings", JSONArray(warnings)) },
        )
    }

    /**
     * 批量创建同一日历中的多条日程：先整体校验，再逐条写入并各自读回。
     * 中途失败时停止后续写入，报告已创建与失败项，提示先查询而不是原样重试。
     */
    private fun createMany(args: JSONObject): AgentModelClient.ToolResult {
        calendarWriteDenied()?.let { return it }
        val events = args.optJSONArray("events")
            ?: return error("INVALID_ARGUMENT", "events 必须是数组，每项包含 title 和 start")
        if (events.length() !in 1..MAX_BATCH_EVENTS) {
            return error("INVALID_ARGUMENT", "events 数量必须在 1 到 $MAX_BATCH_EVENTS 之间")
        }
        val calendarId = args.optLongValue("calendar_id")
        val specs = mutableListOf<JSONObject>()
        for (index in 0 until events.length()) {
            val raw = events.optJSONObject(index)
                ?: return error("INVALID_ARGUMENT", "events[$index] 必须是对象")
            val spec = JSONObject(raw.toString())
            if (calendarId != null) spec.put("calendar_id", calendarId)
            validateBatchEvent(spec, index)?.let { return error("INVALID_ARGUMENT", it) }
            specs += spec
        }
        val calendar = resolveCalendar(calendarId)
            ?: return if (calendarId != null) {
                error("CALENDAR_NOT_WRITABLE", "calendar_id=$calendarId 的日历不存在或只读；请先用 list_calendars 确认可写日历")
            } else {
                error("NO_WRITABLE_CALENDAR", "系统日历里没有可写入的日历；请先在日历应用中创建一个日历")
            }

        val created = JSONArray()
        var failed: JSONObject? = null
        for ((index, spec) in specs.withIndex()) {
            val payload = runCatching { JSONObject(create(spec).content) }.getOrNull()
            if (payload?.optBoolean("ok") == true) {
                created.put(
                    JSONObject()
                        .put("index", index)
                        .put("event_id", payload.optLong("event_id"))
                        .put("title", payload.optString("title"))
                        .put("start_local", payload.optString("start_local")),
                )
                continue
            }
            failed = JSONObject()
                .put("index", index)
                .put("title", spec.optString("title"))
                .put("code", payload?.optString("code").orEmpty().ifBlank { "CALENDAR_INSERT_FAILED" })
                .put("message", payload?.optString("message").orEmpty().ifBlank { "系统日历拒绝了这条日程" })
            break
        }
        if (failed == null) {
            return ok(
                JSONObject()
                    .put("tool", "create_calendar_events")
                    .put("calendar", calendar.name)
                    .put("created", created)
                    .put("count", created.length())
                    .put("summary", "已在日历「${calendar.name}」创建 ${created.length()} 条日程"),
            )
        }
        val payload = JSONObject()
            .put("ok", false)
            .put("tool", "create_calendar_events")
            .put("calendar", calendar.name)
            .put("created", created)
            .put("failed", failed)
            .put(
                "code",
                if (created.length() > 0) "CALENDAR_BATCH_PARTIAL" else failed!!.optString("code"),
            )
            .put(
                "message",
                if (created.length() > 0) {
                    "已创建 ${created.length()} 条，第 ${failed!!.optInt("index")} 条失败；本次已停止后续写入，" +
                        "请先用 search_calendar_events 确认已创建内容，只补建失败项，不要重复整批"
                } else {
                    failed!!.optString("message")
                },
            )
        return AgentModelClient.ToolResult(payload.toString())
    }

    /** 批量写入前的纯参数校验；返回 null 表示可写入。 */
    private fun validateBatchEvent(spec: JSONObject, index: Int): String? {
        if (spec.optString("title").trim().isEmpty()) return "events[$index].title 不能为空"
        if (spec.optString("title").trim().length > MAX_TITLE_CHARS) {
            return "events[$index].title 最多 $MAX_TITLE_CHARS 字"
        }
        CalendarEventFormats.parseScheduleTime(spec.optString("start").trim())
            ?: return "events[$index].start 格式无效：请使用 YYYY-MM-DDTHH:MM 或 YYYY-MM-DD"
        val endText = spec.optString("end").trim()
        if (endText.isNotEmpty()) {
            CalendarEventFormats.parseScheduleTime(endText)
                ?: return "events[$index].end 格式无效：请使用与 start 相同的格式"
        }
        return null
    }

    /**
     * 写入被 Provider 拒绝（返回 null）时退化为打开日历预填界面；
     * 返回 ok=false，让模型如实告知用户去确认保存，而不是谎称已创建。
     */
    private fun uiFallback(
        title: String,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        description: String,
        location: String,
        rrule: String?,
        note: String,
    ): AgentModelClient.ToolResult {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
            if (description.isNotEmpty()) putExtra(CalendarContract.Events.DESCRIPTION, description)
            if (location.isNotEmpty()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
            rrule?.let { putExtra(CalendarContract.Events.RRULE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        return if (launched) {
            error(
                "CALENDAR_UI_FALLBACK",
                "$note；已打开日历并预填事件信息，请在日历应用中确认保存" +
                    "（提前提醒需在应用里手动设置）。不要重复调用本工具。",
            )
        } else {
            error("CALENDAR_UNAVAILABLE", "$note；且没有可处理日历写入的应用，本次未创建日程")
        }
    }

    // ---------------------------------------------------------------- 修改

    private fun update(args: JSONObject): AgentModelClient.ToolResult {
        calendarWriteDenied()?.let { return it }
        val resolution = resolveEvent(args)
        if (resolution is EventResolution.Failed) return resolution.result
        resolution as EventResolution.Found
        val eventId = resolution.id
        val current = resolution.row
        val oldStart = current.optLongValue("dtstart") ?: 0L
        val oldEnd = current.optLongValue("dtend") ?: 0L
        val oldAllDay = (current.optLongValue("allDay") ?: 0L) == 1L

        val startParsed = args.optString("start").trim().takeIf { it.isNotEmpty() }?.let {
            CalendarEventFormats.parseScheduleTime(it)
                ?: return error("INVALID_ARGUMENT", "start 格式无效：请使用 YYYY-MM-DDTHH:MM 或 YYYY-MM-DD")
        }
        val endParsed = args.optString("end").trim().takeIf { it.isNotEmpty() }?.let {
            CalendarEventFormats.parseScheduleTime(it)
                ?: return error("INVALID_ARGUMENT", "end 格式无效：请使用与 start 相同的格式")
        }
        var newAllDay = oldAllDay
        if (args.has("all_day")) newAllDay = args.optBoolean("all_day")
        else if (startParsed != null && startParsed.dateOnly) newAllDay = true

        var newStart = oldStart
        if (startParsed != null) {
            newStart = if (newAllDay) CalendarEventFormats.utcMidnightMillis(startParsed)
            else CalendarEventFormats.localMillis(startParsed)
        } else if (newAllDay != oldAllDay) {
            newStart = if (newAllDay) CalendarEventFormats.utcMidnightFromMillis(oldStart, oldAllDay)
            else CalendarEventFormats.localMidnightFromMillis(oldStart, oldAllDay)
        }
        var newEnd = oldEnd
        if (endParsed != null) {
            newEnd = if (newAllDay) CalendarEventFormats.utcMidnightMillis(endParsed)
            else CalendarEventFormats.localMillis(endParsed)
        } else if (startParsed != null || newAllDay != oldAllDay) {
            newEnd = if (newAllDay) newStart + DAY_MILLIS
            else newStart + (oldEnd - oldStart).coerceAtLeast(HOUR_MILLIS)
        }
        if (newEnd <= newStart) return error("INVALID_ARGUMENT", "结束时间必须晚于开始时间")

        val binds = mutableListOf<Bind>()
        val changed = mutableListOf<String>()
        if (args.has("title")) {
            val newTitle = args.optString("title").trim()
            if (newTitle.isEmpty()) return error("INVALID_ARGUMENT", "title 不能为空；修改标题请直接传新标题")
            binds += Bind("title", 's', newTitle)
            changed += "title"
        }
        if (startParsed != null || newAllDay != oldAllDay) {
            binds += Bind("dtstart", 'l', newStart.toString())
            binds += Bind("dtend", 'l', newEnd.toString())
            binds += Bind("eventTimezone", 's', if (newAllDay) UTC_ZONE else TimeZone.getDefault().id)
            changed += "start"
        } else if (endParsed != null) {
            binds += Bind("dtend", 'l', newEnd.toString())
            changed += "end"
        }
        if (newAllDay != oldAllDay) {
            binds += Bind("allDay", 'i', if (newAllDay) "1" else "0")
            changed += "all_day"
        }
        if (args.has("description")) {
            binds += Bind("description", 's', args.optString("description").trim())
            changed += "description"
        }
        if (args.has("location")) {
            binds += Bind("eventLocation", 's', args.optString("location").trim())
            changed += "location"
        }
        if (args.has("repeat") || args.has("repeat_days")) {
            val rrule = CalendarEventFormats.buildRrule(
                args.optString("repeat").trim().takeIf { it.isNotEmpty() },
                args.optStringList("repeat_days"),
            )
            binds += if (rrule == null) Bind("rrule", 'n', "") else Bind("rrule", 's', rrule)
            changed += "repeat"
        }
        val reminderMinutes = if (args.has("reminder_minutes")) {
            args.optInt("reminder_minutes", 0).coerceIn(0, MAX_REMINDER_MINUTES).also {
                binds += Bind("hasAlarm", 'i', if (it > 0) "1" else "0")
                changed += "reminder"
            }
        } else {
            null
        }
        if (binds.isEmpty()) {
            return ok(
                JSONObject()
                    .put("tool", "update_calendar_event")
                    .put("event_id", eventId)
                    .put("changed", JSONArray())
                    .put("summary", "没有提供要修改的字段，日程保持不变"),
            )
        }

        val update = contentUpdate("$EVENTS_URI/$eventId", binds)
        if (!update.ok || PersonalDataContentParser.hasProviderFailure(update.stdout, update.stderr)) {
            return error("CALENDAR_UPDATE_FAILED", "系统日历拒绝了这次修改；请稍后重试或改用日历应用手动修改")
        }
        if (reminderMinutes != null) {
            deleteReminders(eventId)
            if (reminderMinutes > 0 && !insertReminder(eventId, reminderMinutes)) {
                return ok(
                    JSONObject()
                        .put("tool", "update_calendar_event")
                        .put("event_id", eventId)
                        .put("changed", JSONArray(changed))
                        .put("warnings", JSONArray().put("提前提醒写入失败：日程已修改，但可能不会弹出通知"))
                        .put("summary", "日程已修改，但提前提醒写入失败"),
                )
            }
        }
        val verified = queryEventRow(eventId)
            ?: return error("CALENDAR_PROVIDER_UNAVAILABLE", "系统日历暂时不可访问，无法校验修改结果")
        val mismatch = verifyEvent(verified, binds)
        if (mismatch != null) {
            return error("CALENDAR_UPDATE_NOT_APPLIED", "修改未生效：$mismatch；请回读确认后再决定是否重试")
        }
        return ok(
            JSONObject()
                .put("tool", "update_calendar_event")
                .put("event_id", eventId)
                .put("changed", JSONArray(changed))
                .put("title", verified.optString("title"))
                .put("start_local", CalendarEventFormats.formatSchedule(verified.optLongValue("dtstart") ?: 0L, (verified.optLongValue("allDay") ?: 0L) == 1L))
                .put("summary", "已修改日程「${verified.optString("title")}」：" + changed.joinToString("、")),
        )
    }

    // ---------------------------------------------------------------- 删除

    private fun delete(args: JSONObject): AgentModelClient.ToolResult {
        calendarWriteDenied()?.let { return it }
        val resolution = resolveEvent(args)
        if (resolution is EventResolution.Failed) return resolution.result
        resolution as EventResolution.Found
        val eventId = resolution.id
        val title = resolution.row.optString("title")
        val startMillis = resolution.row.optLongValue("dtstart") ?: 0L
        val allDay = (resolution.row.optLongValue("allDay") ?: 0L) == 1L
        val delete = contentDelete("$EVENTS_URI/$eventId")
        if (!delete.ok || PersonalDataContentParser.hasProviderFailure(delete.stdout, delete.stderr)) {
            return error("CALENDAR_DELETE_FAILED", "系统日历拒绝了这次删除；请稍后重试或改用日历应用手动删除")
        }
        val row = queryEventRow(eventId)
        val removed = row == null || (row.optLongValue("deleted") ?: 0L) == 1L
        if (!removed) {
            return error("CALENDAR_DELETE_NOT_APPLIED", "删除后仍能读到该日程，未生效；请回读确认后再决定是否重试")
        }
        return ok(
            JSONObject()
                .put("tool", "delete_calendar_event")
                .put("event_id", eventId)
                .put("title", title)
                .put("start_local", CalendarEventFormats.formatSchedule(startMillis, allDay))
                .put("summary", "已删除日程「$title」（${CalendarEventFormats.formatSchedule(startMillis, allDay)}）"),
        )
    }

    // ---------------------------------------------------------------- 定位与校验

    private sealed interface EventResolution {
        data class Found(val id: Long, val row: JSONObject) : EventResolution
        data class Failed(val result: AgentModelClient.ToolResult) : EventResolution
    }

    /**
     * 定位日程：event_id 优先；否则用 match_title（可加 match_date 限定那一天）。
     * 匹配多个时返回候选列表，让模型带 event_id 重试一次，而不是替它猜。
     */
    private fun resolveEvent(args: JSONObject): EventResolution {
        val eventId = args.optLongValue("event_id")
        if (eventId != null) {
            val row = queryEventRow(eventId)
                ?: return EventResolution.Failed(error("EVENT_NOT_FOUND", "没有找到 id=$eventId 的日程；它可能已被删除，请用 search_calendar_events 重新查找"))
            if ((row.optLongValue("deleted") ?: 0L) == 1L) {
                return EventResolution.Failed(error("EVENT_NOT_FOUND", "id=$eventId 的日程已被删除；请用 search_calendar_events 重新查找"))
            }
            return EventResolution.Found(eventId, row)
        }
        val title = args.optString("match_title").trim()
        if (title.isEmpty()) {
            return EventResolution.Failed(error("INVALID_ARGUMENT", "请提供 event_id，或提供 match_title 按标题定位日程"))
        }
        val conditions = mutableListOf("deleted=0", likeClause("title", title))
        val matchDate = args.optString("match_date").trim()
        if (matchDate.isNotEmpty()) {
            val parsed = CalendarEventFormats.parseScheduleTime(matchDate)
                ?: return EventResolution.Failed(error("INVALID_ARGUMENT", "match_date 格式应为 YYYY-MM-DD"))
            val dayStart = CalendarEventFormats.localMillis(parsed)
            conditions += "dtstart>=$dayStart AND dtstart<${dayStart + DAY_MILLIS}"
        }
        val rows = queryEvents(where = conditions.joinToString(" AND "), sort = "dtstart DESC", limit = 10)
            ?: return EventResolution.Failed(error("CALENDAR_PROVIDER_UNAVAILABLE", "系统日历暂时不可访问"))
        return when (rows.size) {
            0 -> EventResolution.Failed(
                error(
                    "EVENT_NOT_FOUND",
                    "没有找到标题包含「$title」的日程；可用 search_calendar_events 换关键词查找，" +
                        "或用 create_calendar_event 新建",
                ),
            )
            1 -> EventResolution.Found(rows[0].optLongValue("_id") ?: 0L, rows[0])
            else -> EventResolution.Failed(
                error(
                    "EVENT_AMBIGUOUS",
                    "找到 ${rows.size} 个标题包含「$title」的日程；请从候选里取 event_id 后重试一次，不要重复同样的调用",
                    candidates = JSONArray().also { array ->
                        rows.forEach { row ->
                            array.put(
                                JSONObject()
                                    .put("event_id", row.optLongValue("_id"))
                                    .put("title", row.optString("title"))
                                    .put(
                                        "start_local",
                                        CalendarEventFormats.formatSchedule(
                                            row.optLongValue("dtstart") ?: 0L,
                                            (row.optLongValue("allDay") ?: 0L) == 1L,
                                        ),
                                    ),
                            )
                        }
                    },
                ),
            )
        }
    }

    /** 回读校验：把本次 bind 的字段与库里的实际值逐个比对；空串与 null 视为相等。 */
    private fun verifyEvent(actual: JSONObject, binds: List<Bind>): String? {
        for (bind in binds) {
            val current = actual.fieldString(bind.name)
            if (bind.type == 'n') {
                if (!current.isNullOrEmpty()) return "字段 ${bind.name} 期望已清除，实际为 $current"
                continue
            }
            val normalized = current ?: ""
            if (normalized != bind.value) {
                return "字段 ${bind.name} 期望为 ${bind.value.ifEmpty { "空" }}，实际为 ${normalized.ifEmpty { "空" }}"
            }
        }
        return null
    }

    private data class CalendarTarget(val id: Long, val name: String)

    private fun resolveCalendar(requestedId: Long? = null): CalendarTarget? {
        if (requestedId != null) {
            val rows = query(
                uri = CALENDARS_URI,
                projection = listOf("_id", "calendar_displayName", "calendar_access_level"),
                where = "_id=$requestedId",
                sort = null,
            ) ?: return null
            val row = rows.firstOrNull() ?: return null
            val access = row.optLongValue("calendar_access_level") ?: 0L
            if (access < ACCESS_CONTRIBUTOR) return null
            return CalendarTarget(
                id = requestedId,
                name = row.optString("calendar_displayName").ifBlank { "系统日历" },
            )
        }
        val rows = query(
            uri = CALENDARS_URI,
            projection = listOf("_id", "calendar_displayName", "isPrimary", "visible", "calendar_access_level"),
            where = "visible=1",
            sort = null,
        ) ?: return null
        val candidates = rows.filter {
            (it.optLongValue("_id") ?: 0L) > 0L &&
                (it.optLongValue("calendar_access_level") ?: 0L) >= ACCESS_CONTRIBUTOR
        }
        val best = candidates.firstOrNull { (it.optLongValue("isPrimary") ?: 0L) == 1L }
            ?: candidates.maxByOrNull { (it.optLongValue("calendar_access_level") ?: 0L).toInt() }
            ?: return null
        return CalendarTarget(
            id = best.optLongValue("_id") ?: 0L,
            name = best.optString("calendar_displayName").ifBlank { "系统日历" },
        )
    }

    /**
     * 回读失败后的下一步。
     *
     * 只有一个可写日历时，「换一个日历重试」根本不存在，模型照旧提示只会原样重试
     * （职业规划大赛会话就多烧了一轮）。此时直接收敛到手动路径。
     */
    private fun insertUnverifiedAdvice(): String =
        if (writableCalendarCount() <= 1) {
            "系统里只有这一个可写日历，换日历重试没有意义；请先用 search_calendar_events 确认不存在，" +
                "然后不要再调用本工具，直接告知用户系统日历拒绝了写入，并建议在日历应用中手动新建" +
                "（可以帮用户打开日历应用）"
        } else {
            "先用 search_calendar_events 确认是否已存在；确认不存在时用 list_calendars 换一个" +
                "可写日历（calendar_id）再试一次，不要原样重试"
        }

    private fun writableCalendarCount(): Int {
        val rows = query(
            uri = CALENDARS_URI,
            projection = listOf("_id", "calendar_access_level"),
            where = "visible=1",
            sort = null,
        ) ?: return 0
        return rows.count {
            (it.optLongValue("_id") ?: 0L) > 0L &&
                (it.optLongValue("calendar_access_level") ?: 0L) >= ACCESS_CONTRIBUTOR
        }
    }

    // ---------------------------------------------------------------- Provider 命令

    private fun queryEvents(where: String?, sort: String?, limit: Int): List<JSONObject>? =
        query(uri = EVENTS_URI, projection = EVENT_PROJECTION, where = where, sort = sort, limit = limit)

    private fun queryEventRow(eventId: Long): JSONObject? =
        query(uri = "$EVENTS_URI/$eventId", projection = EVENT_PROJECTION, where = null, sort = null, limit = 1)
            ?.firstOrNull()

    private fun maxEventId(): Long? =
        query(uri = EVENTS_URI, projection = listOf("_id"), where = null, sort = "_id DESC", limit = 1)
            ?.firstOrNull()
            ?.optLongValue("_id")

    private data class ProviderResult(
        val ok: Boolean,
        val stdout: String = "",
        val stderr: String = "",
        /** 实际执行的写入身份：app=应用自身 ContentResolver，root=Root content CLI，none=不可用。 */
        val path: String = "",
    )

    private fun query(
        uri: String,
        projection: List<String>,
        where: String?,
        sort: String?,
        limit: Int? = null,
    ): List<JSONObject>? {
        if (!rootAvailable()) return queryViaResolver(uri, projection, where, sort, limit)
        val command = buildString {
            append("content query --uri ").append(shellQuote(uri))
            append(" --projection ").append(shellQuote(projection.joinToString(":")))
            where?.let { append(" --where ").append(shellQuote(it)) }
            sort?.let { append(" --sort ").append(shellQuote(it)) }
        }
        val result = root.execute(command, timeoutMillis = QUERY_TIMEOUT_MS, maxOutputBytes = MAX_OUTPUT_BYTES)
        if (!result.ok || PersonalDataContentParser.hasProviderFailure(result.stdout, result.stderr)) return null
        return PersonalDataContentParser.parseRows(result.stdout, projection)
            .let { rows -> limit?.let { rows.take(it) } ?: rows }
    }

    /** 无 Root 时直接查询 CalendarContract；未授予 READ_CALENDAR 时返回 null，由上层给出错误。 */
    private fun queryViaResolver(
        uri: String,
        projection: List<String>,
        where: String?,
        sort: String?,
        limit: Int?,
    ): List<JSONObject>? {
        if (!CalendarPermissions.granted(context, write = false)) return null
        val rows = runCatching {
            context.contentResolver.query(Uri.parse(uri), projection.toTypedArray(), where, null, sort)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            JSONObject().also { row ->
                                projection.forEachIndexed { index, column ->
                                    if (!cursor.isNull(index)) row.put(column, cursor.getString(index))
                                }
                            },
                        )
                    }
                }
            }
        }.getOrNull() ?: return null
        return limit?.let { rows.take(it) } ?: rows
    }

    /**
     * 创建日程可用的写入身份，按优先级排列：应用自身（持有 WRITE_CALENDAR 时）、Root。
     * 厂商同步日历上 Root `content insert` 可能被 Provider 静默丢弃（退出码 0、也回显 URI，
     * 但库里没有这条记录），应用身份才是受支持的第三方写入路径；两者都不可用时为空。
     */
    private fun insertIdentities(): List<String> = buildList {
        if (CalendarPermissions.granted(context, write = true)) add(IDENTITY_APP)
        if (rootAvailable()) add(IDENTITY_ROOT)
    }

    private fun insertViaApp(uri: String, binds: List<Bind>): ProviderResult = runCatching {
        context.contentResolver.insert(Uri.parse(uri), binds.toContentValues())
    }.fold(
        onSuccess = { inserted ->
            if (inserted != null) ProviderResult(true, stdout = inserted.toString(), path = IDENTITY_APP)
            else ProviderResult(false, stderr = "ContentResolver.insert 返回 null（Provider 未接受）", path = IDENTITY_APP)
        },
        onFailure = { ProviderResult(false, stderr = it.message.orEmpty(), path = IDENTITY_APP) },
    )

    private fun insertViaRoot(uri: String, binds: List<Bind>): ProviderResult {
        val command = buildString {
            append("content insert --uri ").append(shellQuote(uri))
            binds.forEach { append(" --bind ").append(shellQuote("${it.name}:${it.type}:${it.value}")) }
        }
        val result = root.execute(command, timeoutMillis = QUERY_TIMEOUT_MS, maxOutputBytes = MAX_OUTPUT_BYTES)
        // `content insert` 在 Provider 返回 null 时仍打印 "null" 并以退出码 0 结束，
        // 只看退出码会把「被拒绝」误当成「已执行、只是回读不到」。只有拿到 content:// URI 才算被接受。
        if (result.ok && !result.stdout.contains("content://")) {
            return ProviderResult(
                ok = false,
                stdout = result.stdout,
                stderr = result.stderr.ifBlank { "content insert 未返回 URI，Provider 拒绝了写入" },
                path = IDENTITY_ROOT,
            )
        }
        return result.toProviderResult()
    }

    /** 写入失败时附上 provider 回显（截断），否则下一次仍然只能靠猜。 */
    private fun providerEchoSuffix(result: ProviderResult): String {
        val echo = (result.stdout.trim() + " " + result.stderr.trim()).trim().take(200)
        return if (echo.isNotEmpty()) "；provider=$echo" else ""
    }

    /** 提醒等附属写入：应用身份优先，不可用或直接被拒时退回 Root。 */
    private fun contentInsert(uri: String, binds: List<Bind>): ProviderResult {
        if (CalendarPermissions.granted(context, write = true)) {
            val viaApp = insertViaApp(uri, binds)
            if (viaApp.ok || !rootAvailable()) return viaApp
        }
        if (!rootAvailable()) {
            return ProviderResult(false, stderr = "CALENDAR_PERMISSION_REQUIRED", path = IDENTITY_NONE)
        }
        return insertViaRoot(uri, binds)
    }

    private fun contentUpdate(uri: String, binds: List<Bind>): ProviderResult {
        if (CalendarPermissions.granted(context, write = true)) {
            val viaApp = try {
                context.contentResolver.update(Uri.parse(uri), binds.toContentValues(), null, null)
                ProviderResult(true, path = IDENTITY_APP)
            } catch (e: Exception) {
                ProviderResult(false, stderr = e.message.orEmpty(), path = IDENTITY_APP)
            }
            if (viaApp.ok || !rootAvailable()) return viaApp
        }
        if (!rootAvailable()) {
            return ProviderResult(false, stderr = "CALENDAR_PERMISSION_REQUIRED", path = IDENTITY_NONE)
        }
        val command = buildString {
            append("content update --uri ").append(shellQuote(uri))
            binds.forEach { append(" --bind ").append(shellQuote("${it.name}:${it.type}:${it.value}")) }
        }
        return root.execute(command, timeoutMillis = QUERY_TIMEOUT_MS, maxOutputBytes = MAX_OUTPUT_BYTES).toProviderResult()
    }

    private fun contentDelete(uri: String, where: String? = null): ProviderResult {
        if (!rootAvailable()) {
            if (!CalendarPermissions.granted(context, write = true)) {
                return ProviderResult(false, stderr = "CALENDAR_PERMISSION_REQUIRED")
            }
            return runCatching {
                context.contentResolver.delete(Uri.parse(uri), where, null)
                ProviderResult(true)
            }.getOrElse { ProviderResult(false, stderr = it.message.orEmpty()) }
        }
        val command = buildString {
            append("content delete --uri ").append(shellQuote(uri))
            where?.let { append(" --where ").append(shellQuote(it)) }
        }
        return root.execute(command, timeoutMillis = QUERY_TIMEOUT_MS, maxOutputBytes = MAX_OUTPUT_BYTES).toProviderResult()
    }

    private fun BoundedRootCommandExecutor.Result.toProviderResult(): ProviderResult =
        ProviderResult(ok, stdout, stderr, path = "root")

    private fun List<Bind>.toContentValues(): ContentValues = ContentValues().also { values ->
        for (bind in this) {
            when (bind.type) {
                'n' -> values.putNull(bind.name)
                'i' -> values.put(bind.name, bind.value.toIntOrNull() ?: 0)
                'l' -> values.put(bind.name, bind.value.toLongOrNull() ?: 0L)
                'f' -> values.put(bind.name, bind.value.toFloatOrNull() ?: 0f)
                'd' -> values.put(bind.name, bind.value.toDoubleOrNull() ?: 0.0)
                'b' -> values.put(bind.name, bind.value.toBoolean())
                else -> values.put(bind.name, bind.value)
            }
        }
    }

    /** 降级写入后补齐可选字段；没有要补的字段视为成功。 */
    private fun supplementEvent(eventId: Long, optionalBinds: List<Bind>): Boolean {
        if (optionalBinds.isEmpty()) return true
        val result = contentUpdate("$EVENTS_URI/$eventId", optionalBinds)
        return result.ok && !PersonalDataContentParser.hasProviderFailure(result.stdout, result.stderr)
    }

    private fun insertReminder(eventId: Long, minutes: Int): Boolean {
        val binds = listOf(
            Bind("event_id", 'l', eventId.toString()),
            Bind("minutes", 'i', minutes.toString()),
            Bind("method", 'i', "1"),
        )
        val result = contentInsert(REMINDERS_URI, binds)
        return result.ok && !PersonalDataContentParser.hasProviderFailure(result.stdout, result.stderr)
    }

    private fun deleteReminders(eventId: Long) {
        contentDelete(REMINDERS_URI, where = "event_id=$eventId")
    }

    /** 无 Root 且未授予 WRITE_CALENDAR 时，修改/删除直接拒绝；正常路径由能力投影提前隐藏。 */
    private fun calendarWriteDenied(): AgentModelClient.ToolResult? =
        if (!rootAvailable() && !CalendarPermissions.granted(context, write = true)) {
            error(
                "CALENDAR_PERMISSION_REQUIRED",
                "未获得日历读写权限，本次未修改；请在权限页授予日历权限，或改用日历应用手动修改",
            )
        } else {
            null
        }

    // ---------------------------------------------------------------- 小工具

    private data class Bind(val name: String, val type: Char, val value: String)

    private fun likeClause(column: String, keyword: String): String =
        "LOWER($column) LIKE LOWER('%${CalendarEventFormats.escapeLike(keyword).replace("'", "''")}%') ESCAPE '\\'"

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun JSONObject.optStringList(name: String): List<String>? =
        optJSONArray(name)?.let { array -> (0 until array.length()).map { array.optString(it) } }
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }

    private fun JSONObject.optLongValue(name: String): Long? = when (val value = opt(name)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun JSONObject.fieldString(name: String): String? = when (val value = opt(name)) {
        null -> null
        is Number -> if (value.toDouble() == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    private fun ok(payload: JSONObject): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(payload.put("ok", true).toString())

    private fun error(code: String, message: String, candidates: JSONArray? = null): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .also { payload -> candidates?.let { payload.put("candidates", it) } }
                .toString(),
        )

    private companion object {
        const val EVENTS_URI = "content://com.android.calendar/events"
        const val REMINDERS_URI = "content://com.android.calendar/reminders"
        const val CALENDARS_URI = "content://com.android.calendar/calendars"
        const val UTC_ZONE = "UTC"
        const val DAY_MILLIS = 24L * 60 * 60 * 1_000
        const val HOUR_MILLIS = 60L * 60 * 1_000
        const val DEFAULT_REMINDER_MINUTES = 10
        const val MAX_REMINDER_MINUTES = 20_160
        const val MAX_BATCH_EVENTS = 30
        const val MAX_TITLE_CHARS = 200
        const val ACCESS_CONTRIBUTOR = 500L
        const val ACCESS_OWNER = 700L
        const val QUERY_TIMEOUT_MS = 15_000L
        const val MAX_OUTPUT_BYTES = 256 * 1024
        const val IDENTITY_APP = "app"
        const val IDENTITY_ROOT = "root"
        const val IDENTITY_NONE = "none"
        /**
         * 回读校验比对的每一列都必须出现在这里，否则 [verifyEvent] 永远读到 null，
         * 把「写入其实成功」判成 CALENDAR_UPDATE_NOT_APPLIED（eventTimezone / hasAlarm 曾因此必失败）。
         */
        val EVENT_PROJECTION = listOf(
            "_id", "title", "dtstart", "dtend", "allDay", "deleted", "rrule", "description", "eventLocation",
            "eventTimezone", "hasAlarm",
        )
    }
}

/** 日程工具的纯函数格式助手：不依赖 Android，可直接做单元测试。 */
internal object CalendarEventFormats {

    private val DATE_ONLY = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$")
    private val DATE_TIME = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})[Tt ](\\d{1,2}):(\\d{2})(?::(\\d{2}))?$")
    private val BYDAY_CODE = mapOf(
        "mon" to "MO", "tue" to "TU", "wed" to "WE", "thu" to "TH",
        "fri" to "FR", "sat" to "SA", "sun" to "SU",
    )
    private val BYDAY_ORDER = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    data class ParsedScheduleTime(
        val year: Int,
        val month: Int,
        val day: Int,
        val hour: Int?,
        val minute: Int?,
        val second: Int?,
    ) {
        val dateOnly: Boolean get() = hour == null
    }

    /** 接受 YYYY-MM-DDTHH:MM[:SS]、YYYY-MM-DD HH:MM 与纯日期；非法返回 null。 */
    fun parseScheduleTime(input: String): ParsedScheduleTime? {
        val text = input.trim()
        DATE_ONLY.matchEntire(text)?.let { match ->
            val (year, month, day) = match.destructured
            return ParsedScheduleTime(year.toInt(), month.toInt(), day.toInt(), null, null, null)
                .takeIf { validDate(it.year, it.month, it.day) }
        }
        DATE_TIME.matchEntire(text)?.let { match ->
            val (year, month, day, hour, minute) = match.destructured
            val second = match.groupValues.getOrNull(6)?.toIntOrNull() ?: 0
            if (hour.toInt() > 23 || minute.toInt() > 59 || second > 59) return null
            return ParsedScheduleTime(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt(), second)
                .takeIf { validDate(it.year, it.month, it.day) }
        }
        return null
    }

    private fun validDate(year: Int, month: Int, day: Int): Boolean {
        if (year < 1970 || year > 2100 || month !in 1..12 || day < 1) return false
        return day <= daysInMonth(year, month)
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        else -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    }

    /** 设备本地时间的纪元毫秒。 */
    fun localMillis(time: ParsedScheduleTime): Long =
        Calendar.getInstance().apply {
            clear()
            set(time.year, time.month - 1, time.day, time.hour ?: 0, time.minute ?: 0, time.second ?: 0)
        }.timeInMillis

    /** 全天日程用的 UTC 零点纪元毫秒。 */
    fun utcMidnightMillis(time: ParsedScheduleTime): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(time.year, time.month - 1, time.day, 0, 0, 0)
        }.timeInMillis

    /**  timed 毫秒转成“那一天的 UTC 零点”（改全天时用）。 */
    fun utcMidnightFromMillis(millis: Long, wasAllDay: Boolean): Long {
        if (wasAllDay) return millis
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return utcMidnightMillis(
            ParsedScheduleTime(
                calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH),
                null, null, null,
            ),
        )
    }

    /** 全天毫秒转成“那一天的本地零点”（改定时日程时用）。 */
    fun localMidnightFromMillis(millis: Long, wasAllDay: Boolean): Long {
        if (!wasAllDay) return millis
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
        return localMillis(
            ParsedScheduleTime(
                calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH),
                0, 0, 0,
            ),
        )
    }

    fun formatSchedule(millis: Long, allDay: Boolean): String {
        val pattern = if (allDay) "yyyy-MM-dd" else "yyyy-MM-dd'T'HH:mm"
        val zone = if (allDay) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        return SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(Date(millis))
    }

    fun buildRrule(repeat: String?, days: List<String>?): String? = when (repeat?.lowercase(Locale.ROOT)) {
        null, "", "none" -> null
        "daily" -> "FREQ=DAILY"
        "weekly" -> {
            val codes = days?.map { it.trim().lowercase(Locale.ROOT) }
                ?.filter { it in BYDAY_CODE }
                ?.distinct()
                ?.sortedBy { BYDAY_ORDER.indexOf(it) }
                ?.map { BYDAY_CODE.getValue(it) }
            if (codes.isNullOrEmpty()) "FREQ=WEEKLY" else "FREQ=WEEKLY;BYDAY=" + codes.joinToString(",")
        }
        "monthly" -> "FREQ=MONTHLY"
        "yearly" -> "FREQ=YEARLY"
        else -> null
    }

    fun repeatLabel(rrule: String?): String {
        if (rrule.isNullOrBlank()) return "不重复"
        val freq = Regex("FREQ=([A-Z]+)").find(rrule)?.groupValues?.get(1)
        val byday = Regex("BYDAY=([A-Z,]+)").find(rrule)?.groupValues?.get(1)
        return when (freq) {
            "DAILY" -> "每天"
            "WEEKLY" -> if (byday != null) {
                "每" + byday.split(",").joinToString("") { "周" + dayName(it) }
            } else {
                "每周"
            }
            "MONTHLY" -> "每月"
            "YEARLY" -> "每年"
            else -> "重复"
        }
    }

    private fun dayName(code: String): String = when (code) {
        "MO" -> "一"
        "TU" -> "二"
        "WE" -> "三"
        "TH" -> "四"
        "FR" -> "五"
        "SA" -> "六"
        "SU" -> "日"
        else -> code
    }

    fun summary(
        prefix: String,
        title: String,
        startMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        reminderMinutes: Int,
        rrule: String?,
    ): String = buildString {
        append(prefix).append("日程「").append(title).append("」：")
        if (allDay) {
            append(formatSchedule(startMillis, true)).append(" 全天")
        } else {
            append(formatSchedule(startMillis, false)).append(" 至 ").append(formatSchedule(endMillis, false))
        }
        rrule?.let { append("，").append(repeatLabel(it)).append("重复") }
        append(if (reminderMinutes > 0) "，提前 $reminderMinutes 分钟提醒" else "，不提醒")
    }

    fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun escapeSqlString(value: String): String = value.replace("'", "''")
}
