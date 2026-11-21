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
 * 判定口径按召回优先：误命中代价仅为多附一张截图（模型可忽略），漏命中代价是多一次
 * observe_screen 往返。
 * - 出现屏幕名词（屏幕 / 螢幕 / my screen 等）即命中——用户说到「屏幕」几乎总是指当前
 *   屏幕内容，不再要求与内容动词共现（「整理屏幕内容」「屏幕上的日程加进日历」都算）；
 * - 没有屏幕名词时，可视指代（这个 / 这页 / 上面 / 截图 / this page）与内容动作
 *   （看 / 翻译 / 整理 / 什么 / translate）近距离共现也算命中；
 * - 屏幕控制/系统动作语境（调亮、截屏、锁屏等）整句排除。
 */
internal object AgentScreenIntent {

    /** 屏幕名词：中文简繁 + 英文常见短语。 */
    private const val SCREEN_NOUN =
        "屏幕|螢幕|熒幕|(?:my|the|this|current|whole|entire)\\s+screen\\b"

    /**
     * 可视指代：话语把「要看的东西」指向当前画面，但没有直说「屏幕」。
     *
     * 刻意不含「这里 / 这块」这类过于泛化的词，避免把代码、地点等非屏幕对象算进来。
     */
    private const val DEICTIC =
        "这个|這個|这段|這段|这张|這張|这页|這頁|这上面|這上面|上面|图中|圖中|图里|圖裡" +
            "|截图|截圖|图片|圖片|界面|页面|頁面" +
            "|(?:this|the\\s+(?:image|screenshot|page|picture))\\b"

    /** 内容动作/疑问词：对可视内容做看、读、翻译、整理或询问。 */
    private const val CONTENT_ACTION =
        "看|瞧|读|讀|念|翻译|翻譯|整理|梳理|总结|總結|概括|汇总|匯總|提炼|提煉|识别|識別" +
            "|提取|列出|罗列|羅列|说说|說說|讲讲|講講|描述|告诉|告訴|什么|什麼|啥|哪些" +
            "|read|translate|summarize|summarise|extract|describe|look\\s+at|tell\\s+me\\s+what" +
            "|what(?:'s| is)"

    /** 指代与内容动作的近距离共现：间隔 ≤16 字且不跨句（句末标点/换行即断）。 */
    private val DEICTIC_ACTION = Regex(
        "(?:(?:$DEICTIC)[^。！？!?\\n]{0,16}(?:$CONTENT_ACTION))" +
            "|(?:(?:$CONTENT_ACTION)[^。！？!?\\n]{0,16}(?:$DEICTIC))",
        RegexOption.IGNORE_CASE,
    )

    private val SCREEN = Regex(SCREEN_NOUN, RegexOption.IGNORE_CASE)

    /** 屏幕控制/系统动作语境：这些意图不依赖「看屏幕内容」，刻意整句排除。 */
    private const val CONTROL_CONTEXT =
        "调亮|调暗|亮度|息屏|亮屏|锁屏|录屏|旋转|护眼|省电|截(?:个|一张)?屏" +
            "|brightness|rotate|record\\s+the\\s+screen|lock\\s+the\\s+screen"

    private val CONTROL = Regex(CONTROL_CONTEXT, RegexOption.IGNORE_CASE)

    /**
     * 本轮指令是否依赖「看一眼当前屏幕」。
     *
     * 先整句排除控制/存图语境（「把屏幕调亮，顺便看看时间」整句不命中——牺牲极罕见的
     * 组合，换取零误报），再按「屏幕名词直接命中 / 可视指代 + 内容动作共现」判定。
     */
    fun matches(prompt: String): Boolean {
        if (prompt.isBlank()) return false
        if (CONTROL.containsMatchIn(prompt)) return false
        // 屏幕名词是强信号：说到「屏幕」几乎总是指当前屏幕内容，不再要求内容动词共现。
        if (SCREEN.containsMatchIn(prompt)) return true
        return DEICTIC_ACTION.containsMatchIn(prompt)
    }
}
