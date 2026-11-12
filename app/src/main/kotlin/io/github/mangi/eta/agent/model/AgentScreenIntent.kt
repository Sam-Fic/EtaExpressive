package io.github.mangi.eta.agent.model

/**
 * 「看屏幕」意图的命中判定（系统语音助手弹窗链路专用）。
 *
 * 与 [AgentCalendarIntent] 同一套机制：提交与运行建立阶段用确定性正则判定用户指令是否
 * 依赖其当前手机屏幕内容——命中即自动选中入口截图（弹窗侧，"Current screen" 卡片弹出）
 * 并预开屏幕组 + 注入首轮 observe_screen 引导（运行侧）。判断模型开启时，
 * `needs_screen_context` 问题（AgentDecisionEvaluator）覆盖正则未命中的措辞，两条判定
 * 链路互为补充。
 *
 * 只在系统语音助手弹窗链路使用；主程序对话不做该判断（硬边界，用户定稿）。
 *
 * 误命中代价仅为多附一张截图（模型可忽略）；漏命中代价是多一次 observe_screen 往返——
 * 因此正则收紧到「内容动词 + 屏幕名词」近距离共现，控制/存图类意图刻意不命中。
 */
internal object AgentScreenIntent {

    /** 屏幕名词：中文简繁 + 英文常见短语。 */
    private const val SCREEN_NOUN =
        "屏幕|螢幕|熒幕|(?:my|the|this|current|whole|entire)\\s+screen\\b"

    /**
     * 内容动词/疑问词：屏幕必须是「被看/被读/被翻译/被整理/被添加进任务」的对象，
     * 而不是被控制的对象（调亮、截屏保存等在 [CONTROL] 里整句排除）。
     */
    private const val CONTENT_VERB =
        "看看|看一下|看下|看一?眼|瞧瞧|查看|读(?:一?下|出)?|识别|提取|总结(?:一?下)?|归纳" +
            "|翻译|翻譯|念(?:一?下|出来?)?|列出|罗列|羅列|告诉我|告訴我|什么|什麼|哪些|啥" +
            "|添加|新增" +
            "|what(?:'s| is)|read|translate|summarize|extract|recognize|look\\s+at|tell\\s+me\\s+what"

    /** 屏幕控制/系统动作语境：这些意图不依赖「看屏幕内容」，刻意整句排除。 */
    private const val CONTROL_CONTEXT =
        "调亮|调暗|亮度|息屏|亮屏|锁屏|录屏|旋转|护眼|省电|截(?:个|一张)?屏|截圖" +
            "|brightness|rotate|record\\s+the\\s+screen|lock\\s+the\\s+screen"

    /** 内容动词与屏幕名词的近距离共现：间隔 ≤16 字且不跨句（句末标点/换行即断）。 */
    private val CO_OCCURRENCE = Regex(
        "(?:(?:$CONTENT_VERB)[^。！？!?\\n]{0,16}(?:$SCREEN_NOUN))" +
            "|(?:(?:$SCREEN_NOUN)[^。！？!?\\n]{0,16}(?:$CONTENT_VERB))",
        RegexOption.IGNORE_CASE,
    )

    private val CONTROL = Regex(CONTROL_CONTEXT, RegexOption.IGNORE_CASE)

    /**
     * 本轮指令是否依赖「看一眼当前屏幕」。
     *
     * 判定口径：内容动词与屏幕名词近距离共现（两个方向都算——「看看我的屏幕上」「屏幕上
     * 是什么」）；先整句排除控制/存图语境（「把屏幕调亮，顺便看看时间」整句不命中——
     * 牺牲极罕见的组合，换取零误报）。
     */
    fun matches(prompt: String): Boolean {
        if (prompt.isBlank()) return false
        if (CONTROL.containsMatchIn(prompt)) return false
        return CO_OCCURRENCE.containsMatchIn(prompt)
    }
}
