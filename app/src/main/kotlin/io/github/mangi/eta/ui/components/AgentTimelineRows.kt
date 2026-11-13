package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentIncrementalList
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.incrementalSnapshot
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isResumeAfterCompress
import io.github.mangi.eta.ui.model.isSteerSupplement
import androidx.compose.ui.unit.Dp

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
         * 组内夹进的纯文本模型回复、重连分割线、系统通知、可见的压缩分界、工具汇总
         * 芯片行与运行轨迹入口卡都不算连续列表成员：它们把工具调用断开，前后的步骤
         * 各自重新起段（见 [isSegmentBreak]），断点两侧取段末/段首外圆。
         */
        val isFirst: Boolean,
        val isLast: Boolean,
    ) : AgentTimelineRow {
        override val key: String get() = workStepRowKey(message.id)
    }
}

/**
 * 工作组里夹着的「断开件」：模型纯文本回复、重连分割线、系统通知、有可见分界行的
 * 上下文压缩标记，以及不带分段卡片容器壳的工具汇总芯片行与运行轨迹入口卡。它们要么
 * 渲染成裸文本 / 分割线 / 空行 / 标签行，要么是属于另一套圆角与配色的独立描边卡；
 * 连续分段列表在它们两侧断开，断点前后的步骤行各自取段末/段首的大圆角（外圆），
 * 不与断开件相接成小圆角。
 *
 * 零计数的压缩标记（`compactedCount <= 0`）不渲染任何内容（见 [ContextCompactedDivider]），
 * 不算断开件——否则两张相邻卡片之间会凭空出现一次看不见的断开。
 */
internal fun AgentChatMessageUi?.isSegmentBreak(): Boolean = when {
    this is AgentMessageUi -> content.isNotBlank()
    this is ErrorReconnectMessageUi -> true
    this is SystemNoticeMessageUi -> true
    this is ContextCompactedMessageUi -> compactedCount > 0
    this is ToolSummaryMessageUi -> true
    this is RunTraceMessageUi -> true
    else -> false
}

/**
 * 折叠组展开后摘要行是否与首个步骤行露缝相接（摘要成为组首）。首步若是断开件
 * （纯文本回复 / 重连分割线 / 系统通知 / 可见的压缩分界 / 工具汇总芯片行 /
 * 运行轨迹入口卡），列表在它前面就断开，摘要保持单独一条。
 */
internal fun AgentTimelineEntry.WorkProcess.summaryContinuesIntoSteps(): Boolean =
    !messages.firstOrNull().isSegmentBreak()

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
                isFirst = if (index == 0) !afterSummary else group.messages[index - 1].isSegmentBreak(),
                isLast = index == size - 1 || group.messages.getOrNull(index + 1).isSegmentBreak(),
            ),
        )
    }
}

/**
 * 「本轮回答出来，就把回答之前的内容收起来」：普通档收思考/工具工作组，激进档
 * 连并进来的中间回复一起（激进归组在 toTimelineEntries）；轮次边界与归组一致——
 * 真实用户消息开新轮，steer 补充与压缩后的 resume 只是续跑。
 *
 * 收起时机分轮处理：更早的轮次只要已出现过正文回复就收（生成过程中也保持收起，
 * 否则发新一轮时旧轮会被重新摊开）；最新一轮（[foldLatestTurn] = false 时）只在
 * 整轮停下后收——生成中的回复还会继续长、工作组也可能随后续工具调用重组，
 * 提前收会有「收了又摊开」的回跳，摊开看进度是刻意的。无正文回复的整轮不收。
 */
internal fun List<AgentTimelineEntry>.autoFoldedWorkKeys(
    enabled: Boolean,
    foldLatestTurn: Boolean,
): Set<String> {
    if (!enabled) return emptySet()
    // 最新一轮 = 最后一条真实用户消息之后的部分（没有用户消息则整表视为最新一轮）。
    var latestTurnStart = 0
    forEachIndexed { index, entry ->
        val message = (entry as? AgentTimelineEntry.Message)?.message
        if (message is UserMessageUi && !message.isSteerSupplement() &&
            !message.isResumeAfterCompress()
        ) {
            latestTurnStart = index + 1
        }
    }
    var pending: MutableList<String>? = null
    var folded: MutableSet<String>? = null
    // 含等待作答提问卡的组永不折叠：提问表单必须留在卡片外可见。正常时序下等人回答时
    // 整轮不会停下（isTurnSettled=false），折叠根本轮不到它；这里兜底的是停跑/恢复等
    // 极端时序下问题仍处 Waiting 的时刻——一旦作答/取消/中断即恢复可折叠。
    val holdVisible = HashSet<String>()
    forEachIndexed { index, entry ->
        when (entry) {
            is AgentTimelineEntry.Message -> {
                val message = entry.message
                when {
                    message is UserMessageUi && !message.isSteerSupplement() &&
                        !message.isResumeAfterCompress() -> pending = null
                    message is AgentMessageUi && message.content.isNotBlank() -> {
                        if (foldLatestTurn || index < latestTurnStart) {
                            pending?.let { keys ->
                                (folded ?: LinkedHashSet<String>().also { folded = it }).addAll(keys)
                            }
                        }
                        pending = null
                    }
                    else -> Unit
                }
            }
            is AgentTimelineEntry.WorkProcess -> {
                if (entry.messages.any { it is AgentQuestionMessageUi && it.status == AgentQuestionStatus.Waiting }) {
                    holdVisible += entry.key
                }
                (pending ?: ArrayList<String>().also { pending = it }) += entry.key
            }
        }
    }
    return folded.orEmpty() - holdVisible
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
 * 真正按用户气泡渲染的 user 轮。派发回报虽然身份仍是 [UserMessageUi]（模型输入/历史对齐要用），
 * 但展示层画成工具卡，不参与气泡分组（否则相邻气泡会跟卡片拼在一组里）。
 */
internal fun AgentChatMessageUi.isRenderedAsUserBubble(): Boolean =
    this is UserMessageUi &&
        !io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.isConsoleReceipt(content)

/**
 * 渲染层连续 User 气泡分组：两个相邻 User 气泡之间没有任何行（工作过程 / 正文 / 其它消息）即同组。
 *
 * 组边界只由相邻行决定：流式内容到达只会改变「自身→下一条」一侧，组不随内容跳变。
 */
internal fun List<AgentTimelineRow>.userBubblePosition(index: Int): UserBubblePosition {
    fun isUserBubble(row: AgentTimelineRow?): Boolean =
        (row as? AgentTimelineRow.Message)?.message?.isRenderedAsUserBubble() == true
    val stickyUp = index > 0 && isUserBubble(getOrNull(index - 1))
    val stickyDown = index < lastIndex && isUserBubble(getOrNull(index + 1))
    return when {
        stickyUp && stickyDown -> UserBubblePosition.GroupMid
        stickyUp -> UserBubblePosition.GroupLast
        stickyDown -> UserBubblePosition.GroupFirst
        else -> UserBubblePosition.Single
    }
}

/**
 * 相邻卡片行的接缝投影：分段卡片行（思考 / 工具 / 提问步骤、工作组摘要、派发回报卡）
 * 两两相邻时只有工作组缝量级的薄边——按「相邻工具卡相接处取小圆角」的原则，这类缝
 * 两侧都取内圆，并各出 1dp 薄边合成 2dp 组缝；与其余行（正文、分割线、芯片行、
 * 轨迹入口卡、真实用户气泡）之间维持轮内 / 轮间节奏不变。跨批次 / 跨组的相邻步骤卡、
 * 折叠摘要与相邻步骤卡同样落在这一条口径里，不再只对派发回报卡生效。
 *
 * 判定只看相邻行（与 [userBubblePosition] 同一判定法）：流式更新只改自身一侧，缝不随
 * 远端内容跳变。锚在行下方的页脚（[footerKeys]）会把该行与下一行隔开，那条缝不成立——
 * 行尾保持外圆收边，与工作组行锚页脚的节奏一致。
 */
internal data class ReceiptCardSeams(
    /** 上沿与相邻卡片行相接的行键集合：这些行上沿取内圆、上外距出 1dp。 */
    val seamAboveKeys: Set<String>,
    /** 下沿与相邻卡片行相接的行键集合：这些行下沿取内圆、下外距出 1dp。 */
    val seamBelowKeys: Set<String>,
    /** 渲染为回报卡的 Message 行键集合。 */
    val receiptRowKeys: Set<String>,
    /** 上一行是真实用户气泡的回报卡行键集合：上外距抬到回复起点半距（回复起点节奏）。 */
    val afterUserKeys: Set<String>,
    /**
     * 下一行是真实用户气泡（或本行是列表末行）的回报卡行键集合：下外距抬到基准半距，
     * 与下一轮的用户气泡合成 16dp 轮间间隙；无缝且下邻非气泡时保持 2dp 轮内薄边。
     */
    val beforeUserKeys: Set<String>,
) {
    fun hasSeamAbove(rowKey: String): Boolean = rowKey in seamAboveKeys
    fun hasSeamBelow(rowKey: String): Boolean = rowKey in seamBelowKeys

    /** 本行是否渲染为派发回报卡（Message 行工具卡）。 */
    fun isReceiptRow(rowKey: String): Boolean = rowKey in receiptRowKeys

    /**
     * 回报卡行的布局：分段位置由两侧缝推导（双缝 Middle / 仅上缝 Last / 仅下缝 First /
     * 无缝 Whole），上下外距按 回复起点 8dp / 组缝 1dp / 轮内薄边 2dp / 轮间基准 8dp 四档。
     * 非回报卡行返回 null。
     */
    fun receiptLayout(rowKey: String): ReceiptCardLayout? {
        if (rowKey !in receiptRowKeys) return null
        val above = hasSeamAbove(rowKey)
        val below = hasSeamBelow(rowKey)
        return ReceiptCardLayout(
            position = when {
                above && below -> SegmentedRowPosition.Middle
                above -> SegmentedRowPosition.Last
                below -> SegmentedRowPosition.First
                else -> SegmentedRowPosition.Whole
            },
            seamAbove = above,
            seamBelow = below,
            topPadding = when {
                rowKey in afterUserKeys -> AgentChatSpacing.RowBaseHalf
                above -> AgentChatSpacing.WorkSeamHalf
                else -> AgentChatSpacing.TurnInnerEdge
            },
            bottomPadding = when {
                below -> AgentChatSpacing.WorkSeamHalf
                rowKey in beforeUserKeys -> AgentChatSpacing.RowBaseHalf
                else -> AgentChatSpacing.TurnInnerEdge
            },
        )
    }
}

/** 回报卡行的分段位置与上下外距（由 [ReceiptCardSeams.receiptLayout] 推导）。 */
internal data class ReceiptCardLayout(
    val position: SegmentedRowPosition,
    /** 上沿与相邻卡片行相接：上沿取内圆、上外距出 1dp 薄边。 */
    val seamAbove: Boolean,
    /** 下沿与相邻卡片行相接：下沿取内圆、下外距出 1dp 薄边。 */
    val seamBelow: Boolean,
    val topPadding: Dp,
    val bottomPadding: Dp,
)

/**
 * 步骤行是否渲染成分段卡片表面：思考、工具与提问卡是；正文、系统通知、压缩分界、
 * 工具汇总芯片行与运行轨迹入口卡不是——它们没有分段卡片的容器壳，不能与相邻卡片
 * 拼出内圆缝（断开口径见 [isSegmentBreak]）。
 */
private fun AgentChatMessageUi.rendersSegmentedCardSurface(): Boolean = when (this) {
    is ThinkingMessageUi, is ToolActivityMessageUi, is AgentQuestionMessageUi -> true
    else -> false
}

/**
 * 计算 [ReceiptCardSeams]：卡片行 = 工作组摘要 / 分段卡片步骤 / 派发回报卡。任意两张
 * 相邻卡片行之间都构成一条接缝（不再要求其中一侧是回报卡），上行记入 seamBelowKeys、
 * 下行记入 seamAboveKeys；组内连续步骤本就按 [SegmentedRowPosition] 取内圆，重复的
 * 缝标记落到同一组数值上，不改变既有行距与形状。
 */
internal fun List<AgentTimelineRow>.receiptCardSeams(
    footerKeys: Set<String> = emptySet(),
): ReceiptCardSeams {
    fun isReceipt(row: AgentTimelineRow?): Boolean =
        row is AgentTimelineRow.Message &&
            row.message is UserMessageUi &&
            io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.isConsoleReceipt(row.message.content)

    fun isCardRow(row: AgentTimelineRow?): Boolean = when (row) {
        is AgentTimelineRow.WorkSummary -> true
        is AgentTimelineRow.WorkStep -> row.message.rendersSegmentedCardSurface()
        else -> isReceipt(row)
    }

    val seamAbove = mutableSetOf<String>()
    val seamBelow = mutableSetOf<String>()
    for (index in 0 until lastIndex) {
        val row = this[index]
        val next = this[index + 1]
        if (!isCardRow(row) || !isCardRow(next)) continue
        // 页脚锚在本行下方时，本行与下一行之间被按钮行隔开：缝不成立，行尾取外圆收边。
        if (row.key in footerKeys) continue
        seamBelow += row.key
        seamAbove += next.key
    }
    val receiptRows = mutableSetOf<String>()
    val afterUser = mutableSetOf<String>()
    val beforeUser = mutableSetOf<String>()
    forEachIndexed { index, row ->
        if (!isReceipt(row)) return@forEachIndexed
        receiptRows += row.key
        val previous = getOrNull(index - 1)
        if (previous is AgentTimelineRow.Message && previous.message.isRenderedAsUserBubble()) {
            afterUser += row.key
        }
        val next = getOrNull(index + 1)
        if (index == lastIndex ||
            (next is AgentTimelineRow.Message && next.message.isRenderedAsUserBubble())
        ) {
            beforeUser += row.key
        }
    }
    return ReceiptCardSeams(
        seamAboveKeys = seamAbove,
        seamBelowKeys = seamBelow,
        receiptRowKeys = receiptRows,
        afterUserKeys = afterUser,
        beforeUserKeys = beforeUser,
    )
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
