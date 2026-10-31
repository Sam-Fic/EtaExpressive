package io.github.mangi.eta.agent.voice

/**
 * 自动朗读的待播句子队列。
 *
 * 只认「句子属于哪一段」：模型在工具调用之后另起新段时，上一段还没播出去的句子要整批作废，
 * 播放器才能立刻跳到最新一段。抽成独立纯类是为了让丢弃语义可以在纯 JVM 单测里钉住，
 * 不必拉起 [io.github.mangi.eta.agent.voice.tts.SpeechPlayback] 这条真实播放链路。
 *
 * 非线程安全：与 [ReplyReadAloudController] 的决策/播放协程一样，约定只在主线程访问。
 */
internal class ReplyReadAloudQueue {

    private val items = ArrayDeque<ReadAloudUtterance>()

    val size: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()

    fun enqueueAll(utterances: List<ReadAloudUtterance>) {
        utterances.forEach(items::add)
    }

    /** 队首但不取出：播放器要先确认这一段还没有被更新的段落顶掉。 */
    fun peek(): ReadAloudUtterance? = items.firstOrNull()

    fun take(): ReadAloudUtterance? = if (items.isEmpty()) null else items.removeFirst()

    /**
     * 丢弃不属于 [keepMessageId] 那一段的句子，返回丢弃条数。
     * 比较用归一化段身份，断流续接换 id 不算换段。null 等价 [clear]。
     */
    fun dropStale(keepMessageId: String?): Int {
        if (keepMessageId == null) return clear()
        val keep = AssistantMessageId.segmentKey(keepMessageId)
        var dropped = 0
        val retained = ArrayDeque<ReadAloudUtterance>(items.size)
        items.forEach { item ->
            if (AssistantMessageId.segmentKey(item.messageId) == keep) {
                retained.addLast(item)
            } else {
                dropped++
            }
        }
        items.clear()
        items.addAll(retained)
        return dropped
    }

    fun clear(): Int {
        val dropped = items.size
        items.clear()
        return dropped
    }

    fun segments(): Set<String?> = items.mapTo(mutableSetOf()) { AssistantMessageId.segmentKey(it.messageId) }
}
