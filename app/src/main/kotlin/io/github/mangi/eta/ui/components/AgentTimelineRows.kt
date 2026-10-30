package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentIncrementalList
import io.github.mangi.eta.ui.model.incrementalSnapshot
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isResumeAfterCompress
import io.github.mangi.eta.ui.model.isSteerSupplement

/**
 * Flat lazy-list rows：思考 / 工具步骤各自成为一条独立的可展开 SegmentedListItem
 * 气泡行（无嵌套）。激进档被折叠的工作组只出一条 [WorkSummary] 摘要行，
 * 手动展开后摘要行 + 步骤行一起出现（兄弟行，不是卡中卡）。
 */
/** 步骤行的 lazy key：折叠退场登记与投影行共用这一份出处。 */
internal fun workStepRowKey(messageId: String): String = "work-step:$messageId"

internal sealed interface AgentTimelineRow {
    val key: String
    data class Message(val message: AgentChatMessageUi) : AgentTimelineRow {
        override val key: String get() = message.id
    }

    /**
     * 被激进折叠档收起的工作组摘要（「已完成 N 个步骤」）。[expanded] 为 true 时
     * 表示用户手动展开——摘要行保留，步骤行紧随其后。
     *
     * [group] 可能是相邻多个批次的合并组（见 [toLazyTimelineRows]）：同一轮超过
     * 单个批次上限的步骤在折叠后仍只出一张摘要，计数也按整轮合并。
     */
    data class WorkSummary(
        val group: AgentTimelineEntry.WorkProcess,
        val expanded: Boolean,
    ) : AgentTimelineRow {
        override val key: String get() = group.key
    }

    data class WorkStep(
        val groupKey: String,
        val message: AgentChatMessageUi,
        /**
         * 本行在组内可见分段列表中的位置（连续工具/思考调用是设置页「同组项」样式）。
         * 组内夹进的纯文本模型回复不算列表成员：它把工具调用断开，前后的步骤各自重新起段
         * （见 [isSegmentTextBreak]）。
         */
        val isFirst: Boolean,
        val isLast: Boolean,
    ) : AgentTimelineRow {
        override val key: String get() = workStepRowKey(message.id)
    }
}

/** 工作组里夹着的模型纯文本回复：连续分段列表在它的两侧断开。 */
internal fun AgentChatMessageUi?.isSegmentTextBreak(): Boolean =
    this is AgentMessageUi && content.isNotBlank()

/**
 * 折叠组展开后摘要行是否与首个步骤行露缝相接（摘要成为组首）。首步若是纯文本回复，
 * 列表在它前面就断开，摘要保持单独一条。
 */
internal fun AgentTimelineEntry.WorkProcess.summaryContinuesIntoSteps(): Boolean =
    !messages.firstOrNull().isSegmentTextBreak()

internal fun List<AgentTimelineEntry>.toLazyTimelineRows(
    foldedGroups: Set<String> = emptySet(),
    expandedGroups: Set<String> = emptySet(),
): List<AgentTimelineRow> = buildList {
    val entries = this@toLazyTimelineRows
    var index = 0
    while (index < entries.size) {
        when (val entry = entries[index]) {
            is AgentTimelineEntry.Message -> {
                add(AgentTimelineRow.Message(entry.message))
                index++
            }
            is AgentTimelineEntry.WorkProcess -> {
                if (entry.key !in foldedGroups) {
                    addWorkSteps(entry)
                    index++
                } else {
                    // 同一轮的工作过程会按批次上限切成多个相邻组。折叠后它们是同一张卡片，
                    // 只应出一条摘要（点开再一起展开）；其它位置的分组不算同轮，不合并。
                    var end = index + 1
                    while (end < entries.size) {
                        val next = entries[end] as? AgentTimelineEntry.WorkProcess ?: break
                        if (next.key !in foldedGroups) break
                        end++
                    }
                    val group = if (end == index + 1) entry else AgentTimelineEntry.WorkProcess(
                        key = entry.key,
                        messages = entries.subList(index, end)
                            .flatMap { (it as AgentTimelineEntry.WorkProcess).messages },
                    )
                    val expanded = group.key in expandedGroups
                    add(AgentTimelineRow.WorkSummary(group, expanded = expanded))
                    if (expanded) addWorkSteps(group, afterSummary = true)
                    index = end
                }
            }
        }
    }
}

private fun MutableList<AgentTimelineRow>.addWorkSteps(
    group: AgentTimelineEntry.WorkProcess,
    afterSummary: Boolean = false,
) {
    val size = group.messages.size
    group.messages.forEachIndexed { index, message ->
        add(
            AgentTimelineRow.WorkStep(
                group.key, message,
                // 展开后的首步与摘要行同段（摘要承担组首圆角），除非它紧跟纯文本回复。
                isFirst = if (index == 0) !afterSummary else group.messages[index - 1].isSegmentTextBreak(),
                isLast = index == size - 1 || group.messages.getOrNull(index + 1).isSegmentTextBreak(),
            ),
        )
    }
}

/**
 * 「本轮回答出来，就把回答之前的内容收起来」：普通档收思考/工具工作组，激进档
 * 连并进来的中间回复一起（激进归组在 toTimelineEntries）；轮次边界与归组一致——
 * 真实用户消息开新轮，steer 补充与压缩后的 resume 只是续跑。
 * 只在整轮停下后生效（调用方负责传入 isTurnSettled）。
 */
internal fun List<AgentTimelineEntry>.autoFoldedWorkKeys(enabled: Boolean): Set<String> {
    if (!enabled) return emptySet()
    var pending: MutableList<String>? = null
    var folded: MutableSet<String>? = null
    forEach { entry ->
        when (entry) {
            is AgentTimelineEntry.Message -> {
                val message = entry.message
                when {
                    message is UserMessageUi && !message.isSteerSupplement() &&
                        !message.isResumeAfterCompress() -> pending = null
                    message is AgentMessageUi && message.content.isNotBlank() -> {
                        pending?.let { keys ->
                            (folded ?: LinkedHashSet<String>().also { folded = it }).addAll(keys)
                        }
                        pending = null
                    }
                    else -> Unit
                }
            }
            is AgentTimelineEntry.WorkProcess ->
                (pending ?: ArrayList<String>().also { pending = it }) += entry.key
        }
    }
    return folded.orEmpty()
}

/**
 * Keep the exact row policy and work groups from the full projection. Assistant
 * rows and same-key/step-ID work payloads can be patched at their existing slots.
 * Changes to policy or structure fall back.
 */
internal class AgentTimelineRowsCache(
    private val fullProjection: (
        List<AgentTimelineEntry>,
        Set<String>,
        Set<String>,
    ) -> List<AgentTimelineRow> = { entries, foldedGroups, expandedGroups ->
        entries.toLazyTimelineRows(foldedGroups, expandedGroups)
    },
) {
    private var source: List<AgentTimelineEntry>? = null
    private var folded: Set<String> = emptySet()
    private var expandedGroups: Set<String> = emptySet()
    private var rows: List<AgentTimelineRow> = emptyList()
    private var entryToRow: IntArray? = null
    private var groupRowStart: IntArray? = null

    fun project(
        entries: List<AgentTimelineEntry>,
        foldedGroups: Set<String> = emptySet(),
        expandedGroups: Set<String> = emptySet(),
    ): List<AgentTimelineRow> {
        val previous = source
        val mapping = entryToRow
        if (previous != null && mapping != null && groupRowStart != null && previous.size == entries.size &&
            folded == foldedGroups && expandedGroups == this.expandedGroups
        ) {
            val changed = (entries as? AgentIncrementalList<AgentTimelineEntry>)?.singleReplacementFrom(previous)
            if (changed != null) {
                val old = (previous[changed] as? AgentTimelineEntry.Message)?.message as? AgentMessageUi
                val current = (entries[changed] as? AgentTimelineEntry.Message)?.message as? AgentMessageUi
                if (old != null && current != null && old.id == current.id && old.isStreaming && current.isStreaming &&
                    current.content.startsWith(old.content) && mapping[changed] >= 0
                ) {
                    rows = rows.incrementalSnapshot().replacing(mapping[changed], AgentTimelineRow.Message(current))
                    source = entries
                    return rows
                }
                patchWorkGroup(previous, entries, changed)?.let { patched ->
                    rows = patched
                    source = entries
                    return rows
                }
            }
            if (changed == null) {
                val changedIndices = ArrayList<Int>(1)
                var changedWorkGroup = -1
                var compatible = true
                for (index in entries.indices) {
                    val old = previous[index]
                    val current = entries[index]
                    if (old === current || old.hasSamePayloadReferences(current)) continue
                    if (old is AgentTimelineEntry.WorkProcess && current is AgentTimelineEntry.WorkProcess && changedWorkGroup < 0) {
                        changedWorkGroup = index
                        continue
                    }
                    val oldMessage = (old as? AgentTimelineEntry.Message)?.message as? AgentMessageUi
                    val newMessage = (current as? AgentTimelineEntry.Message)?.message as? AgentMessageUi
                    if (oldMessage == null || newMessage == null || oldMessage.id != newMessage.id || mapping[index] < 0) {
                        compatible = false
                        break
                    }
                    changedIndices += index
                }
                // A full/unhinted entry snapshot can still be one ordinary work
                // payload delta. Certify the unchanged entries before patching it.
                if (compatible && changedWorkGroup >= 0 && changedIndices.isEmpty()) {
                    patchWorkGroup(previous, entries, changedWorkGroup)?.let { patched ->
                        rows = patched
                        source = entries.incrementalSnapshot()
                        return rows
                    }
                }
                if (compatible && changedWorkGroup < 0) {
                    if (changedIndices.isEmpty()) {
                        source = entries.incrementalSnapshot()
                        return rows
                    }
                    val updated = rows.toMutableList()
                    changedIndices.forEach { index ->
                        updated[mapping[index]] = AgentTimelineRow.Message((entries[index] as AgentTimelineEntry.Message).message)
                    }
                    source = if (entries is AgentIncrementalList<AgentTimelineEntry>) entries else entries.toList()
                    rows = updated.incrementalSnapshot()
                    return rows
                }
            }
        }
        val input = entries.incrementalSnapshot()
        val result = fullProjection(input, foldedGroups, expandedGroups).incrementalSnapshot()
        source = input
        folded = foldedGroups
        this.expandedGroups = expandedGroups
        rows = result
        entryToRow = mapAssistantRows(input, result)
        groupRowStart = if (entryToRow == null) null else mapGroupRows(input, result)
        return result
    }

    // Full timeline projection recreates entry wrappers, even when their payload
    // objects are unchanged. Compare only references, never historical text bodies.
    private fun AgentTimelineEntry.hasSamePayloadReferences(other: AgentTimelineEntry): Boolean = when (this) {
        is AgentTimelineEntry.Message -> other is AgentTimelineEntry.Message && message === other.message
        is AgentTimelineEntry.WorkProcess -> other is AgentTimelineEntry.WorkProcess && key == other.key &&
            messages.size == other.messages.size && messages.indices.all { messages[it] === other.messages[it] }
    }

    /**
     * A work group whose step IDs are unchanged (thinking text grows, a tool finishes)
     * re-projects only its own steps. Group key is unchanged, so the result equals the
     * full projection when the row count holds. Groups carrying a summary row (folded /
     * manually expanded) take the full projection instead — folds change rarely.
     */
    private fun patchWorkGroup(
        previous: List<AgentTimelineEntry>,
        entries: List<AgentTimelineEntry>,
        changed: Int,
    ): List<AgentTimelineRow>? {
        val starts = groupRowStart ?: return null
        val old = previous[changed] as? AgentTimelineEntry.WorkProcess ?: return null
        val current = entries[changed] as? AgentTimelineEntry.WorkProcess ?: return null
        val start = starts[changed]
        if (start < 0 || old.key != current.key || old.messages.size != current.messages.size) return null
        if (rows.getOrNull(start) is AgentTimelineRow.WorkSummary) return null
        for (i in old.messages.indices) if (old.messages[i].id != current.messages[i].id) return null
        val oldCount = (if (changed + 1 < starts.size) starts[changed + 1] else rows.size) - start
        if (oldCount != current.messages.size) return null
        var updated = rows.incrementalSnapshot()
        current.messages.forEachIndexed { offset, message ->
            val previousRow = updated[start + offset]
            if (previousRow !is AgentTimelineRow.WorkStep) return null
            if (previousRow.message.id != message.id) return null
            // 位置标记只随组结构变化；补丁路径里行数与步骤 ID 都没变，原样保留。
            if (previousRow.message === message) return@forEachIndexed
            updated = updated.replacing(start + offset, previousRow.copy(message = message))
        }
        return updated.withoutReplacementHint()
    }

    /** First row of every entry; null when rows are not one contiguous run per entry. */
    private fun mapGroupRows(entries: List<AgentTimelineEntry>, rows: List<AgentTimelineRow>): IntArray? {
        val starts = IntArray(entries.size)
        var row = 0
        entries.forEachIndexed { index, entry ->
            starts[index] = row
            when (entry) {
                is AgentTimelineEntry.Message -> {
                    if ((rows.getOrNull(row) as? AgentTimelineRow.Message)?.message !== entry.message) return null
                    row++
                }
                is AgentTimelineEntry.WorkProcess -> {
                    // 折叠/手动展开的组以摘要行打头，步骤行紧随其后（或被折叠隐藏）。
                    val summary = rows.getOrNull(row) as? AgentTimelineRow.WorkSummary
                    if (summary != null && summary.group.key == entry.key) row++
                    if (summary == null || summary.expanded) {
                        entry.messages.forEach { message ->
                            val step = rows.getOrNull(row) as? AgentTimelineRow.WorkStep
                            if (step == null || step.groupKey != entry.key || step.message !== message) return null
                            row++
                        }
                    }
                }
            }
        }
        return if (row == rows.size) starts else null
    }

    private fun mapAssistantRows(entries: List<AgentTimelineEntry>, rows: List<AgentTimelineRow>): IntArray? {
        val seen = HashSet<String>()
        val assistantSlots = HashMap<String, Int>()
        entries.forEachIndexed { index, entry ->
            when (entry) {
                is AgentTimelineEntry.Message -> {
                    if (!seen.add(entry.message.id)) return null
                    if (entry.message is AgentMessageUi) assistantSlots[entry.message.id] = index
                }
                is AgentTimelineEntry.WorkProcess -> entry.messages.forEach {
                    if (!seen.add(it.id)) return null
                }
            }
        }
        // Duplicate lazy keys must not become an ambiguous mapping either.
        if (rows.map { it.key }.toSet().size != rows.size) return null
        val mapping = IntArray(entries.size) { -1 }
        rows.forEachIndexed { rowIndex, row ->
            val message = (row as? AgentTimelineRow.Message)?.message as? AgentMessageUi
                ?: return@forEachIndexed
            val entryIndex = assistantSlots[message.id] ?: return null
            if ((entries[entryIndex] as AgentTimelineEntry.Message).message !== message || mapping[entryIndex] >= 0) return null
            mapping[entryIndex] = rowIndex
        }
        assistantSlots.values.forEach { if (mapping[it] < 0) return null }
        return mapping
    }
}

internal fun List<AgentTimelineRow>.lazyUserMessageIndices(): List<Int> = mapIndexedNotNull { index, row ->
    val user = (row as? AgentTimelineRow.Message)?.message as? UserMessageUi
    index.takeIf { user != null && !user.isResumeAfterCompress() }
}

/**
 * 渲染层连续 User 气泡分组：两个相邻 User 气泡之间没有任何行（工作过程 / 正文 / 其它消息）即同组。
 *
 * 组边界只由相邻行决定：流式内容到达只会改变「自身→下一条」一侧，组不随内容跳变。
 */
internal fun List<AgentTimelineRow>.userBubblePosition(index: Int): UserBubblePosition {
    fun isUserBubble(row: AgentTimelineRow?): Boolean =
        (row as? AgentTimelineRow.Message)?.message is UserMessageUi
    val stickyUp = index > 0 && isUserBubble(getOrNull(index - 1))
    val stickyDown = index < lastIndex && isUserBubble(getOrNull(index + 1))
    return when {
        stickyUp && stickyDown -> UserBubblePosition.GroupMid
        stickyUp -> UserBubblePosition.GroupLast
        stickyDown -> UserBubblePosition.GroupFirst
        else -> UserBubblePosition.Single
    }
}

internal fun AgentTimelineRow.containsMessageId(id: String): Boolean = when (this) {
    is AgentTimelineRow.Message -> message.id == id
    // 折叠组的消息不可见：滚动定位落到摘要行上。
    is AgentTimelineRow.WorkSummary -> key == id || (!expanded && group.messages.any { it.id == id })
    is AgentTimelineRow.WorkStep -> message.id == id
}

/** Terminal notices must not hide an assistant that is still revealing text. */
internal fun hasPendingAssistantReveal(
    messages: List<AgentChatMessageUi>,
    isPending: (AgentMessageUi) -> Boolean,
): Boolean = (messages.lastOrNull { it is AgentMessageUi && it.content.isNotBlank() }
    as? AgentMessageUi)?.let(isPending) == true
