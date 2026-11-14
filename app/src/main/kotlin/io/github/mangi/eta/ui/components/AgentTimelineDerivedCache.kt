package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentIncrementalList
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isSteerSupplement

/**
 * 时间线行的全部派生结果，一次产出、整体复用。
 *
 * 这些投影原先各自 `remember(timelineRows)`：流式正文每到一个 token，行的快照引用就变，
 * 八份投影全部重扫整条时间线；而它们只读行的 key、类型、消息 id 与「正文是否为空」，
 * 流式追加正文时结果逐字不变。这里把它们收进一个缓存：结构未变时原样复用，
 * 只替换必须跟着正文更新的消息对象引用。
 *
 * 快路径凭证来自 [AgentIncrementalList.singleReplacementFrom]：上游
 * `AgentTimelineRowsCache` 的助手正文分支用 `replacing()` 产出行快照，hint 一直带到这里。
 * 工作卡片补丁、多槽替换等无 hint 的路径一律走全量投影——频率低，且不能凭猜测复用。
 */
internal data class AgentTimelineDerived(
    /** 用户消息 id → 连续用户气泡的组内位置。 */
    val userBubblePositions: Map<String, UserBubblePosition>,
    /** 工作组行 key → 上一行是否真实用户气泡。 */
    val workRowFollowsUser: Map<String, Boolean>,
    /** lazy 行 key → 该轮操作栏持有的原始消息对象（正文会逐 token 更新）。 */
    val turnFooters: Map<String, AgentChatMessageUi>,
    /** 最终回复 id 集合，朗读范围判据。 */
    val finalResultMessageIds: Set<String>,
    /** 相邻分段卡片行的接缝。 */
    val receiptSeams: ReceiptCardSeams,
    /** lazy 行 key → 该页脚负责等待显现完成的回复列表（正文会逐 token 更新）。 */
    val footerRevealMessages: Map<String, List<AgentMessageUi>>,
    /** 「上一轮 / 下一轮」导航的 user 行下标。 */
    val userMessageTargets: List<Int>,
    /** 激进折叠收起、未展开的工作组里不可见的回复 id。 */
    val foldedReplyIds: Set<String>,
)

private data class FooterFlags(
    val isStreaming: Boolean,
    val isPaused: Boolean,
    val isCompressingContext: Boolean,
)

internal class AgentTimelineDerivedCache {
    private var previousRows: List<AgentTimelineRow>? = null
    private var flags: FooterFlags? = null
    private var derived: AgentTimelineDerived? = null

    fun project(
        rows: List<AgentTimelineRow>,
        isStreaming: Boolean,
        isPaused: Boolean,
        isCompressingContext: Boolean,
    ): AgentTimelineDerived {
        val currentFlags = FooterFlags(isStreaming, isPaused, isCompressingContext)
        val previous = previousRows
        val last = derived
        // 运行态三兄弟会改变 turnFooters 的收尾策略，必须参与快路径凭证。
        if (previous != null && last != null && flags == currentFlags && previous.size == rows.size) {
            val changed = (rows as? AgentIncrementalList<AgentTimelineRow>)?.singleReplacementFrom(previous)
            if (changed != null) {
                val oldRow = previous[changed]
                val newRow = rows[changed]
                if (oldRow is AgentTimelineRow.Message && newRow is AgentTimelineRow.Message &&
                    oldRow.message is AgentMessageUi && newRow.message is AgentMessageUi &&
                    isStructureOnlyDelta(oldRow.message, newRow.message)
                ) {
                    val patched = last.patchOwner(oldRow.message, newRow.message)
                    previousRows = rows
                    derived = patched
                    return patched
                }
            }
        }
        val result = fullProjection(rows, currentFlags)
        previousRows = rows
        flags = currentFlags
        derived = result
        return result
    }

    /**
     * 只放行「同一条助手消息、正文非空性未翻转」的单槽替换。
     *
     * 八份投影对助手正文的读取仅有 `content.isNotBlank()`（turnFooters 挑 owner、
     * footerRevealMessages 收答案、autoFoldedWorkKeys 判轮次已有回复）。其余判定都要求
     * UserMessageUi / WorkSummary / WorkStep，助手行替换不会触及。空白翻转会改变轮次
     * 折叠与页脚归属，必须回退全量。
     */
    private fun isStructureOnlyDelta(old: AgentMessageUi, current: AgentMessageUi): Boolean =
        old.id == current.id &&
            old.content.isBlank() == current.content.isBlank() &&
            old.isStreaming == current.isStreaming

    /** 结构结果全部复用，只把持在 value 里的旧消息对象换成新对象。 */
    private fun AgentTimelineDerived.patchOwner(
        old: AgentMessageUi,
        current: AgentMessageUi,
    ): AgentTimelineDerived {
        val hasFooterOwner = turnFooters.values.any { it === old }
        val footers = if (hasFooterOwner) {
            turnFooters.mapValues { (_, owner) -> if (owner === old) current else owner }
        } else turnFooters
        val hasRevealAnswer = footerRevealMessages.values.any { answers -> answers.any { it === old } }
        val reveals = if (hasRevealAnswer) {
            footerRevealMessages.mapValues { (_, answers) ->
                if (answers.any { it === old }) answers.map { if (it === old) current else it } else answers
            }
        } else footerRevealMessages
        return if (footers === turnFooters && reveals === footerRevealMessages) this
        else copy(turnFooters = footers, footerRevealMessages = reveals)
    }

    private fun fullProjection(
        rows: List<AgentTimelineRow>,
        flags: FooterFlags,
    ): AgentTimelineDerived {
        val userBubblePositions = buildMap {
            rows.forEachIndexed { index, row ->
                val user = (row as? AgentTimelineRow.Message)?.message as? UserMessageUi
                    ?: return@forEachIndexed
                put(user.id, rows.userBubblePosition(index))
            }
        }
        val workRowFollowsUser = buildMap {
            rows.forEachIndexed { index, row ->
                if (row is AgentTimelineRow.WorkSummary || row is AgentTimelineRow.WorkStep) {
                    val prev = rows.getOrNull(index - 1) as? AgentTimelineRow.Message
                    put(row.key, prev?.message?.isRenderedAsUserBubble() == true)
                }
            }
        }
        val turnFooters = rows.turnFooters(
            isStreaming = flags.isStreaming,
            isCompressingContext = flags.isCompressingContext,
            isPaused = flags.isPaused,
        )
        val finalResultMessageIds = turnFooters.values.mapTo(mutableSetOf()) { it.id }
        val receiptSeams = rows.receiptCardSeams(footerKeys = turnFooters.keys)
        val footerRevealMessages = buildMap<String, List<AgentMessageUi>> {
            val answers = mutableListOf<AgentMessageUi>()
            rows.forEach { row ->
                val message = (row as? AgentTimelineRow.Message)?.message
                if (message is UserMessageUi && !message.isSteerSupplement()) answers.clear()
                if (message is AgentMessageUi && message.content.isNotBlank()) answers.add(message)
                if (row.key in turnFooters) {
                    put(row.key, answers.toList())
                    answers.clear()
                }
            }
        }
        val userMessageTargets = rows.lazyUserMessageIndices()
        val foldedReplyIds = buildSet {
            rows.forEach { row ->
                if (row is AgentTimelineRow.WorkSummary && !row.expanded) {
                    row.group.messages.forEach { message ->
                        if (message is AgentMessageUi) add(message.id)
                    }
                }
            }
        }
        return AgentTimelineDerived(
            userBubblePositions = userBubblePositions,
            workRowFollowsUser = workRowFollowsUser,
            turnFooters = turnFooters,
            finalResultMessageIds = finalResultMessageIds,
            receiptSeams = receiptSeams,
            footerRevealMessages = footerRevealMessages,
            userMessageTargets = userMessageTargets,
            foldedReplyIds = foldedReplyIds,
        )
    }
}

/**
 * 折叠键与「当前存在的工作组键」共享一份投影。后者原本是
 * `LaunchedEffect(projectedTimelineEntries)` 里每 token 一次的 filterIsInstance + mapTo，
 * 用它作 effect key 后，只有工作组结构变化才需要清理失效的展开覆盖。
 */
internal data class AgentFoldedWorkKeys(
    val folded: Set<String>,
    val activeGroupKeys: Set<String>,
)

internal class AgentFoldedWorkKeysCache {
    private var previous: List<AgentTimelineEntry>? = null
    private var enabled: Boolean? = null
    private var foldLatestTurn: Boolean? = null
    private var result: AgentFoldedWorkKeys? = null

    fun project(
        entries: List<AgentTimelineEntry>,
        enabled: Boolean,
        foldLatestTurn: Boolean,
    ): AgentFoldedWorkKeys {
        val previousEntries = previous
        val last = result
        if (previousEntries != null && last != null && this.enabled == enabled &&
            this.foldLatestTurn == foldLatestTurn && previousEntries.size == entries.size
        ) {
            val changed = (entries as? AgentIncrementalList<AgentTimelineEntry>)
                ?.singleReplacementFrom(previousEntries)
            if (changed != null) {
                val old = (previousEntries[changed] as? AgentTimelineEntry.Message)?.message as? AgentMessageUi
                val current = (entries[changed] as? AgentTimelineEntry.Message)?.message as? AgentMessageUi
                // 折叠判据只读助手正文的非空性；工作组键集合也不受助手正文替换影响。
                if (old != null && current != null && old.id == current.id &&
                    old.content.isBlank() == current.content.isBlank()
                ) {
                    previous = entries
                    return last
                }
            }
        }
        val folded = entries.autoFoldedWorkKeys(enabled, foldLatestTurn)
        val activeGroupKeys = entries.filterIsInstance<AgentTimelineEntry.WorkProcess>()
            .mapTo(mutableSetOf()) { it.key }
        val next = AgentFoldedWorkKeys(folded, activeGroupKeys)
        previous = entries
        this.enabled = enabled
        this.foldLatestTurn = foldLatestTurn
        result = next
        return next
    }
}
