package io.github.mangi.eta.agent.tool

import java.util.Calendar
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 时间解析、重复日口径与下次触发时刻的纯逻辑回归。 */
class AgentScheduleTimeTest {
    private fun atMillis(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun parsesAcceptedTimeFormats() {
        // ISO T 分隔、空格分隔、纯日期与 epoch 毫秒，统一折算到同一时刻
        val base = atMillis(2026, 10, 24, 9, 30)
        assertEquals(base, AgentScheduleTime.parseTime("2026-10-24T09:30"))
        assertEquals(base, AgentScheduleTime.parseTime("2026-10-24 09:30:00"))
        assertEquals(atMillis(2026, 10, 24), AgentScheduleTime.parseTime("2026-10-24"))
        assertEquals(1_790_000_000_000L, AgentScheduleTime.parseTime("1790000000000"))
        assertEquals(base, AgentScheduleTime.parseTime("2026-10-24T09:30:00"))
    }

    @Test
    fun rejectsMalformedTime() {
        assertNull(AgentScheduleTime.parseTime("明天早上"))
        assertNull(AgentScheduleTime.parseTime("2026/10/24 09:30"))
        assertNull(AgentScheduleTime.parseTime(""))
        assertNull(AgentScheduleTime.parseTime("not-a-number"))
    }

    @Test
    fun parsesRepeatDayAliases() {
        assertEquals(emptySet<Int>(), AgentScheduleTime.parseRepeatDays(""))
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7), AgentScheduleTime.parseRepeatDays("daily"))
        assertEquals(setOf(6, 7), AgentScheduleTime.parseRepeatDays("sat,sun"))
        assertEquals(setOf(1, 5), AgentScheduleTime.parseRepeatDays("1,5"))
        // 数字口径 1=周一…7=周日；非法词被丢弃而不是报错
        assertEquals(setOf(3), AgentScheduleTime.parseRepeatDays("wed,nope"))
    }

    @Test
    fun computesNextTriggerForOnceAndRepeatAlarms() {
        // 现在=2026-10-22（周四）02:00
        val now = atMillis(2026, 10, 22, 2, 0)
        // 07:30 单次：今天已过点 → 明天同时刻
        assertEquals(atMillis(2026, 10, 23, 7, 30), AgentScheduleTime.nextTriggerAt(7, 30, emptySet(), now))
        // 01:00 单次：今天未到 → 今天
        assertEquals(atMillis(2026, 10, 22, 1, 0), AgentScheduleTime.nextTriggerAt(1, 0, emptySet(), now))
        // 周一三五重复（1,3,5）：周四之后最近的周五是 10-23
        assertEquals(atMillis(2026, 10, 23, 8, 0), AgentScheduleTime.nextTriggerAt(8, 0, setOf(1, 3, 5), now))
        // 每天重复：今天 02:00 整点已过分钟 → 明天 02:00
        assertEquals(atMillis(2026, 10, 23, 2, 0), AgentScheduleTime.nextTriggerAt(2, 0, setOf(1, 2, 3, 4, 5, 6, 7), now))
    }

    @Test
    fun dayOfWeekMatchesIsoConvention() {
        assertEquals(4, AgentScheduleTime.dayOfWeek(atMillis(2026, 10, 22))) // 周四
        assertEquals(7, AgentScheduleTime.dayOfWeek(atMillis(2026, 10, 25))) // 周日
        assertEquals(1, AgentScheduleTime.dayOfWeek(atMillis(2026, 10, 26))) // 周一
        assertTrue(TimeUnit.DAYS.toMillis(1) > 0)
    }
}
