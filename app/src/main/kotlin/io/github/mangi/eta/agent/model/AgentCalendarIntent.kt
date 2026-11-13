package io.github.mangi.eta.agent.model

/**
 * 日程任务的运行级命中判定。
 *
 * 所有入口（应用内对话、系统语音浮窗、其它共用运行链路的入口）都在**运行建立阶段**
 * 用本轮用户指令做一次确定性判定，不交给模型决定是否加载日历工具。命中后本轮直接
 * 下发整个日历组并注入当前时间，跳过渐进加载与判断模型收窄。
 *
 * 关键词集中定义在这里，扩展只改这一处；[toolNames] 直接取组定义，避免与
 * [AgentToolGroups.CALENDAR_GROUP] 漂移。
 */
internal object AgentCalendarIntent {
    /** 整个日历组：查询、读取、新建、批量新建、修改、删除。 */
    val toolNames: Set<String> = AgentToolGroups.CALENDAR_GROUP.toolNames

    /** 日历组打开：命中运行一次到位，不需要 tools_use_group 解锁。 */
    val openedGroups: Set<String> = setOf(AgentToolGroups.CALENDAR_GROUP.id)

    /**
     * 直接的日程名词：出现即判定为日程任务。
     *
     * 英文的 `schedule` 只在名词短语（my/the/next schedule）里算命中；作为动词时
     * 必须带日历宾语（见 [CALENDAR_VERB_PHRASE]），避免 "schedule the build" 误命中。
     */
    private val CALENDAR_NOUN = Regex(
        "日程|日历|会议|约会|行程|议程|calendar|agenda|itinerary|appointment|meeting" +
            "|(?:my|the|this|next|today's|tomorrow's)\\s+schedule\\b",
        RegexOption.IGNORE_CASE,
    )

    /** 英文里必须带日历宾语的动词短语，避免 "schedule the build" 这类误命中。 */
    private val CALENDAR_VERB_PHRASE = Regex(
        "schedule\\s+(?:a|an|the)?\\s*(?:meeting|event|appointment|call|dinner|trip)\\b" +
            "|add\\s+.{0,40}?to\\s+(?:my\\s+)?calendar\\b" +
            "|put\\s+.{0,40}?on\\s+(?:my\\s+)?calendar\\b",
        RegexOption.IGNORE_CASE,
    )

    /** 相对/绝对时间表达；用于给「安排」「提醒」这类泛词加上下文约束。 */
    private val TIME_TOKEN = Regex(
        "今天|明天|后天|昨天|前天|今晚|明早|下周|本周|下周[一二三四五六日天]|周[一二三四五六日天]" +
            "|星期[一二三四五六日天]|\\d{1,2}[点:：]|\\d{1,2}\\s*[点时]" +
            "|\\d{1,2}月\\d{1,2}[日号]|\\d{4}-\\d{1,2}-\\d{1,2}|早上|上午|中午|下午|晚上" +
            "|today|tomorrow|tonight|next\\s+week|\\d{1,2}\\s*(?:am|pm)\\b|\\d{1,2}:\\d{2}",
        RegexOption.IGNORE_CASE,
    )

    /** 泛词「安排」：只有同时出现时间表达才算日程，避免「安排一下文件」被误命中。 */
    private val ARRANGE = Regex("安排")

    /** 「提醒」：默认属于提醒/闹钟类任务，只有带明确时间且不是闹钟语境才归入日程。 */
    private val REMIND = Regex("提醒(?:我|一下|下)?|remind\\s+me", RegexOption.IGNORE_CASE)

    /** 闹钟/叫醒语境：没有日程名词时应交给闹钟类任务，不预热日历工具。 */
    private val ALARM_CONTEXT = Regex("闹钟|起床|叫醒|打铃|alarm|wake\\s+me|wake-up", RegexOption.IGNORE_CASE)

    /**
     * 明确的写入意图。只有出现这些动词，才把日程类请求判成「创建任务」并走快速路径。
     * 刻意不含「安排」「提醒」这类泛词：它们由 [ARRANGE] / [REMIND] 配合时间表达命中，
     * 但不足以证明用户要写日历，误开快速路径会把普通问答压成单轮写入。
     */
    private val CALENDAR_WRITE_INTENT = Regex(
        "添加|加到|加进|加入|新增|新建|创建|写入|记到|记入|录入|登记" +
            "|add\\b|create\\b|schedule\\b|book\\b",
        RegexOption.IGNORE_CASE,
    )

    /** 纯识别/整理语境：没有写入动词时不预热日历写入目录，也不注入命令式的时间口径。 */
    private val SCREEN_RECOGNITION_INTENT = Regex(
        "识别|提取|提炼|总结|整理|有没有(?:日程|日历|会议|活动)|哪些(?:日程|会议|活动)",
    )

    /**
     * 本轮指令是否属于日程任务。
     *
     * 判定口径：
     * - 命中日程名词或带日历宾语的动词短语 → 是；
     * - 「安排」必须搭配时间表达（「安排一下明天下午的评审」是，「安排这个文件」否）；
     * - 「提醒我」必须搭配明确时间，且不是闹钟/叫醒语境（「提醒我明天下午三点交报告」是，
     *   「提醒我明早七点起床」归闹钟类，不命中）。
     */
    fun matches(prompt: String): Boolean {
        if (prompt.isBlank()) return false
        val hasTime = TIME_TOKEN.containsMatchIn(prompt)
        val hit = CALENDAR_NOUN.containsMatchIn(prompt) ||
            CALENDAR_VERB_PHRASE.containsMatchIn(prompt) ||
            (ARRANGE.containsMatchIn(prompt) && hasTime) ||
            (REMIND.containsMatchIn(prompt) && hasTime && !ALARM_CONTEXT.containsMatchIn(prompt))
        if (!hit) return false
        // 「识别截图中的日程」「整理一下日程」是只读任务：没有写入动词时不预热日历组，
        // 也不下发「按当前时间换算并调用日程工具」这类命令式口径。有写入动词时
        // （「识别…并添加到日历」）仍照常命中。
        return CALENDAR_WRITE_INTENT.containsMatchIn(prompt) ||
            !SCREEN_RECOGNITION_INTENT.containsMatchIn(prompt)
    }

    /** 是否为明确的「添加日程」任务：决定要不要启用专用短提示与最低思考档。 */
    fun writeIntent(prompt: String): Boolean =
        matches(prompt) && CALENDAR_WRITE_INTENT.containsMatchIn(prompt)
}
