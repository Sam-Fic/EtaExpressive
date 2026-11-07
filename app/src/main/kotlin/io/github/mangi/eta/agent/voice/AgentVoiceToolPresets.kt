package io.github.mangi.eta.agent.voice

/**
 * 语音浮窗「屏幕快捷功能」的预设工具集。
 *
 * 三个快捷功能要做什么在点击时就已确定，因此这次运行不需要渐进加载
 * （`tools_search` / `tools_use_group`），也不交给判断模型收窄组：
 * Runtime 直接把预设里的工具 schema 一次性下发，模型拿到即可调用。
 *
 * 预设只决定「本次下发哪些工具」，不绕过用户的组开关与运行时权限：
 * 开关关掉或权限缺失时，对应工具仍会被目录发射器/能力投影过滤。
 * 普通对话（包括先加屏幕截图再自由提问）不会带预设 id，仍走应用内同一套配置。
 */
internal object AgentVoiceToolPresets {
    /** 翻译屏幕：截图已附在用户消息里，纯对话，不下发工具。 */
    const val SCREEN_TRANSLATE = "screen_translate"

    /** 创建屏幕日程：只下发日程创建链，截图已附在用户消息里。 */
    const val SCREEN_CALENDAR = "screen_calendar"

    /** 整理屏幕内容：截图已附在用户消息里，纯对话，不下发工具。 */
    const val SCREEN_ORGANIZE = "screen_organize"

    /**
     * 创建日程的最小闭环：单条新建与批量新建。
     *
     * 截图直接给出事项，当前时间由 Runtime 在系统提示里注入（[Preset.injectCurrentTime]），
     * 因此不需要 `get_current_context` 的取时间往返，也不配任何读取屏幕的工具。
     */
    private val CALENDAR_CREATE: Set<String> = setOf(
        "create_calendar_event",
        "create_calendar_events",
    )

    /** 需要把请求发起时刻的真实系统时间注入提示的预设（免去取时间的一次工具往返）。 */
    private val timeInjected: Set<String> = setOf(SCREEN_CALENDAR)

    /** 预设 id → 本次运行可见的全部工具（空集合 = 纯对话，零工具）。 */
    private val toolSets: Map<String, Set<String>> = mapOf(
        SCREEN_TRANSLATE to emptySet(),
        SCREEN_CALENDAR to CALENDAR_CREATE,
        SCREEN_ORGANIZE to emptySet(),
    )

    /**
     * 预设 id → 本次运行视为「已打开」的延迟组。
     *
     * 只预开日历组，让提示词补上日程写入规则；其它组全部保持关闭，
     * 既不下发相关 schema，也不会在系统提示里宣传对应能力。
     */
    private val openedGroups: Map<String, Set<String>> = mapOf(
        SCREEN_TRANSLATE to emptySet(),
        SCREEN_CALENDAR to setOf("calendar"),
        SCREEN_ORGANIZE to emptySet(),
    )

    /** 一次快捷功能运行要下发的工具与预开组。 */
    internal data class Preset(
        val toolNames: Set<String>,
        val openedGroups: Set<String>,
        /**
         * 是否在系统提示里注入请求发起时刻的本地时间、时区与相对日期换算口径。
         * 日历预设据此省掉「先取当前时间」的一次工具往返。
         */
        val injectCurrentTime: Boolean = false,
    )

    /** 解析预设 id；未知 id（包括普通对话的空串）返回 null，保持原有行为。 */
    fun resolve(id: String): Preset? {
        val tools = toolSets[id] ?: return null
        return Preset(
            toolNames = tools,
            openedGroups = openedGroups[id].orEmpty(),
            injectCurrentTime = id in timeInjected,
        )
    }
}
