package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentIncrementalList
import io.github.mangi.eta.ui.model.incrementalSnapshot
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isResumeAfterCompress

/**
 * Flat lazy-list rows：工作过程组不再有组头卡片，思考 / 工具步骤各自成为
 * 一条独立的可展开 SegmentedListItem 气泡行（无嵌套）。
 */
internal sealed interface AgentTimelineRow {
    val key: String
    data class Message(val message: AgentChatMessageUi) : AgentTimelineRow {
        override val key: String get() = message.id
    }
    data class WorkStep(
        val groupKey: String,
        val message: AgentChatMessageUi,
    ) : AgentTimelineRow {
        override val key: String get() = "work-step:${message.id}"
    }
}

internal fun List<AgentTimelineEntry>.toLazyTimelineRows(): List<AgentTimelineRow> = buildList {
    forEach { entry ->
        when (entry) {
            is AgentTimelineEntry.Message -> add(AgentTimelineRow.Message(entry.message))
            is AgentTimelineEntry.WorkProcess -> entry.messages.forEach { message ->
                add(AgentTimelineRow.WorkStep(entry.key, message))
            }
        }
    }
}

/**
 * Keep the exact row policy and work groups from the full projection. Assistant
 * rows and same-key/step-ID work payloads can be patched at their existing slots.
 * Changes to policy or structure fall back.
 */
internal class AgentTimelineRowsCache(
    private val fullProjection: (List<AgentTimelineEntry>) -> List<AgentTimelineRow> =
        { entries -> entries.toLazyTimelineRows() },
) {
    private var source: List<AgentTimelineEntry>? = null
    private var rows: List<AgentTimelineRow> = emptyList()
    private var entryToRow: IntArray? = null
    private var groupRowStart: IntArray? = null

    fun project(entries: List<AgentTimelineEntry>): List<AgentTimelineRow> {
        val previous = source
        val mapping = entryToRow
        if (previous != null && mapping != null && groupRowStart != null && previous.size == entries.size) {
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
        val result = fullProjection(input).incrementalSnapshot()
        source = input
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
     * full projection when the row count holds.
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
        for (i in old.messages.indices) if (old.messages[i].id != current.messages[i].id) return null
        val oldCount = (if (changed + 1 < starts.size) starts[changed + 1] else rows.size) - start
        if (oldCount != current.messages.size) return null
        var updated = rows.incrementalSnapshot()
        current.messages.forEachIndexed { offset, message ->
            val nextRow = AgentTimelineRow.WorkStep(current.key, message)
            val previousRow = updated[start + offset]
            if (nextRow.key != previousRow.key) return null
            if (previousRow is AgentTimelineRow.WorkStep && previousRow.message === message) return@forEachIndexed
            updated = updated.replacing(start + offset, nextRow)
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
                is AgentTimelineEntry.WorkProcess -> entry.messages.forEach { message ->
                    val step = rows.getOrNull(row) as? AgentTimelineRow.WorkStep
                    if (step == null || step.groupKey != entry.key || step.message !== message) return null
                    row++
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
    is AgentTimelineRow.WorkStep -> message.id == id
}

/** Terminal notices must not hide an assistant that is still revealing text. */
internal fun hasPendingAssistantReveal(
    messages: List<AgentChatMessageUi>,
    isPending: (AgentMessageUi) -> Boolean,
): Boolean = (messages.lastOrNull { it is AgentMessageUi && it.content.isNotBlank() }
    as? AgentMessageUi)?.let(isPending) == true
