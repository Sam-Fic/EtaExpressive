package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isSteerSupplement

/** 以用户轮次为边界同步裁剪展示消息与模型上下文。 */
internal object AgentConversationRevisionReducer {
    data class Boundary(
        val userMessage: UserMessageUi,
        val userMessageIndex: Int,
        val historyPrefix: List<AgentModelClient.ConversationMessage>,
        val laterTurnCount: Int,
        val contextWasCompacted: Boolean,
        /** Proven history identity, independent of the next execution's runId and the UI id. */
        val logicalTurnId: String,
    )

    data class BranchPrefix(
        val messages: List<AgentChatMessageUi>,
        val history: List<AgentModelClient.ConversationMessage>,
    )

    /** Restore on a temporary copy first; null means the source must not be revised. */
    fun prepareForRevision(
        state: AgentChatUiState,
        targetMessageId: String,
        loadCheckpoint: (String) -> List<AgentModelClient.ConversationMessage>,
    ): AgentChatUiState? {
        val targetIndex = state.messages.indices.singleOrNull { state.messages[it].id == targetMessageId } ?: return null
        val anchor = (targetIndex downTo 0).firstOrNull {
            state.messages[it] is UserMessageUi ||
                (state.messages[it] is AgentMessageUi && (state.messages[it] as AgentMessageUi).content.isNotBlank())
        } ?: return null
        // Preserve the existing safe edit of a final supplement that was never sent to the model.
        val anchorMessage = state.messages[anchor]
        if (anchorMessage is UserMessageUi && anchorMessage.isSteerSupplement() &&
            historyMessageLocation(state, anchor) == AgentConversationRevisionArchive.Location.Missing &&
            boundary(state, anchorMessage.id) != null) return state
        return AgentConversationRevisionArchive.prepare(
            source = state,
            locate = { historyMessageLocation(it, anchor) },
            couldBeArchived = { candidate ->
                val summaryIndex = candidate.history.indexOfLast(AgentConversationRevisionArchive::isRevisionSummary)
                val targetUser = (anchor downTo 0).firstOrNull { candidate.messages[it] is UserMessageUi }
                val targetPayload = targetUser?.let { payload(candidate.messages[it] as UserMessageUi) }
                val matchingTail = targetPayload != null && (summaryIndex + 1 until candidate.history.size).any {
                    payload(candidate.history[it]) == targetPayload
                }
                summaryIndex >= 0 && (matchingTail || (0 until anchor).none { earlier ->
                    if (candidate.messages[earlier] !is UserMessageUi) false else {
                        val location = historyMessageLocation(candidate, earlier)
                        location is AgentConversationRevisionArchive.Location.Found && location.index > summaryIndex
                    }
                })
            },
            loadCheckpoint = loadCheckpoint,
        )
    }

    fun boundary(state: AgentChatUiState, targetMessageId: String,
                 allowUnconsumedSupplement: Boolean = true): Boundary? {
        val targetIndex = state.messages.indices.singleOrNull { state.messages[it].id == targetMessageId } ?: return null
        val userMessageIndex = (targetIndex downTo 0).firstOrNull { index ->
            state.messages[index] is UserMessageUi
        } ?: return null
        val userMessage = state.messages[userMessageIndex] as UserMessageUi
        val historyIndex = historyUserIndex(state, userMessageIndex)
        val laterUsers = state.messages.drop(userMessageIndex + 1).any { it is UserMessageUi }
        if (historyIndex == null && allowUnconsumedSupplement && userMessage.isSteerSupplement() &&
            historyMessageLocation(state, userMessageIndex) == AgentConversationRevisionArchive.Location.Missing) {
            // 停止时尚未写进历史的最后一条追加：它之后没有任何内容可被抹掉，
            // 以完整历史为前缀替换它是安全的；其它缺失的追加仍拒绝，避免误认成原问题。
            val owner = ownerRunId(userMessage.id)
            if (laterUsers || state.history.none { it.turnId == owner }) return null
            return Boundary(
                userMessage = userMessage,
                userMessageIndex = userMessageIndex,
                historyPrefix = completePrefix(state.history, state.history.size) ?: return null,
                laterTurnCount = 0,
                contextWasCompacted = false,
                logicalTurnId = owner,
            )
        }
        val laterTurnCount = state.messages.drop(userMessageIndex + 1).count {
            it is UserMessageUi && !it.isSteerSupplement()
        }
        // A summary/visible marker is not original history. Restore before calling this reducer.
        val verifiedIndex = historyIndex ?: return null
        val prefix = completePrefix(state.history, verifiedIndex) ?: return null
        return Boundary(
            userMessage = userMessage,
            userMessageIndex = userMessageIndex,
            historyPrefix = prefix,
            laterTurnCount = laterTurnCount,
            contextWasCompacted = false,
            logicalTurnId = state.history[verifiedIndex].turnId.ifBlank { ownerRunId(userMessage.id) },
        )
    }

    /**
     * 按操作栏分段删除：删掉目标所在的一段以及它下面的所有段，上面的段原样保留。
     * 删最后一段时只去掉这一段。模型上下文必须精确截断；已压缩的目标必须先
     * 在临时副本上 prepareForRevision，缺档时保持源会话不动。
     */
    fun deleteFromTurn(state: AgentChatUiState, targetMessageId: String): AgentChatUiState? {
        val segments = actionBarSegments(state.messages)
        if (state.messages.count { it.id == targetMessageId } != 1) return null
        val segment = segments.singleOrNull { it.ownerId == targetMessageId } ?: return null
        val messages = state.messages.take(segment.start)
        val history = historyBefore(state, segment.start) ?: return null
        return AgentConversationRevisionArchive.invalidateReceipt(state.copy(
            messages = messages,
            history = history,
            messageEdit = null,
        ))
    }

    /** 删除确认框用：目标段下面还会被一起删掉的段数。 */
    fun laterSegmentCount(state: AgentChatUiState, targetMessageId: String): Int? {
        val segments = actionBarSegments(state.messages)
        val index = segments.indexOfFirst { it.ownerId == targetMessageId }
        return if (index < 0) null else segments.size - 1 - index
    }

    internal data class ActionBarSegment(val start: Int, val endInclusive: Int, val ownerId: String)

    /** 与时间线上的操作栏同一套分界：用户气泡单独一段，回复在下一条用户消息或下一次收口处分段。 */
    internal fun actionBarSegments(messages: List<AgentChatMessageUi>): List<ActionBarSegment> {
        val segments = mutableListOf<ActionBarSegment>()
        var start = 0
        var owner: AgentChatMessageUi? = null
        var terminalIndex = -1
        fun flush(end: Int) {
            val current = owner
            if (current != null && end >= start) {
                segments += ActionBarSegment(start, end, current.id)
            }
            start = end + 1
            owner = null
            terminalIndex = -1
        }
        messages.forEachIndexed { index, message ->
            when (message) {
                is UserMessageUi -> {
                    if (index > start) flush(index - 1)
                    segments += ActionBarSegment(index, index, message.id)
                    start = index + 1
                }
                is AgentMessageUi -> {
                    if (message.content.isNotBlank()) {
                        if (terminalIndex >= 0) flush(terminalIndex)
                        owner = message
                    }
                }
                is io.github.mangi.eta.ui.model.ErrorReconnectMessageUi -> {
                    if (message.status == io.github.mangi.eta.ui.model.ErrorReconnectStatus.Failed ||
                        message.status == io.github.mangi.eta.ui.model.ErrorReconnectStatus.Stopped
                    ) {
                        if (owner == null) owner = message
                        terminalIndex = index
                    }
                }
                is SystemNoticeMessageUi -> {
                    if (message.code == SystemNoticeCode.ModelRetry) {
                        if (terminalIndex >= 0) flush(terminalIndex)
                    } else {
                        if (message.code != SystemNoticeCode.Completed || owner == null) owner = message
                        terminalIndex = index
                    }
                }
                else -> Unit
            }
        }
        if (owner != null) flush(messages.lastIndex)
        return segments
    }

    /** No UI reconstruction and no summary fallback: both sides of the cut need exact anchors. */
    private fun historyBefore(state: AgentChatUiState, cut: Int, validateRemoved: Boolean = true): List<AgentModelClient.ConversationMessage>? {
        val removedAnchor = (cut until state.messages.size).firstOrNull { isTextAnchor(state.messages[it]) }
        if (validateRemoved && removedAnchor != null && historyMessageLocation(state, removedAnchor) !is AgentConversationRevisionArchive.Location.Found) return null
        // A branch must locate its retained anchor; a later user's history is not evidence
        // that the assistant bubble retained before that user exists in model history.
        if (validateRemoved && cut < state.messages.size && state.messages[cut] is UserMessageUi) {
            val index = historyUserIndex(state, cut) ?: return null
            return completePrefix(state.history, index)
        }
        val keptAnchor = (cut - 1 downTo 0).firstOrNull { isTextAnchor(state.messages[it]) }
        if (keptAnchor == null) return if (removedAnchor == null) null else {
            val location = historyMessageLocation(state, removedAnchor) as? AgentConversationRevisionArchive.Location.Found ?: return null
            val index = location.index
            completePrefix(state.history, index)
        }
        val location = historyMessageLocation(state, keptAnchor) as? AgentConversationRevisionArchive.Location.Found ?: return null
        return completePrefix(state.history, location.index + 1)
    }

    private fun isTextAnchor(message: AgentChatMessageUi): Boolean = message is UserMessageUi ||
        (message is AgentMessageUi && message.content.isNotBlank())

    /** Extend only the retained assistant's contiguous tool batch, and reject incomplete known batches. */
    private fun completePrefix(
        history: List<AgentModelClient.ConversationMessage>, requestedEnd: Int,
    ): List<AgentModelClient.ConversationMessage>? {
        var end = requestedEnd
        if (end > 0 && history[end - 1].role == "assistant" && history[end - 1].toolCallsJson.isNotBlank()) {
            while (end < history.size && history[end].role == "tool") end++
        }
        var index = 0
        while (index < end) {
            val message = history[index]
            if (message.role == "assistant" && message.toolCallsJson.isNotBlank()) {
                val ids = try {
                    val calls = org.json.JSONArray(message.toolCallsJson)
                    (0 until calls.length()).map { calls.getJSONObject(it).getString("id").also { id ->
                        if (id.isBlank()) return null
                    } }
                } catch (_: Exception) { return null }
                var next = index + 1
                val results = mutableListOf<String>()
                while (next < end && history[next].role == "tool") results += history[next++].toolCallId
                if (ids.isNotEmpty() && (ids.size != ids.distinct().size || results.size != ids.size || results.toSet() != ids.toSet())) return null
                index = next
            } else index++
        }
        return history.take(end)
    }

    /** Branch includes precisely the target, not all later replies in the same run. */
    fun branchPrefix(state: AgentChatUiState, targetMessageId: String): BranchPrefix? {
        val targetIndex = state.messages.indices.singleOrNull { state.messages[it].id == targetMessageId } ?: return null
        val history = historyBefore(state, targetIndex + 1, validateRemoved = false) ?: return null
        return BranchPrefix(messages = state.messages.take(targetIndex + 1), history = history)
    }


    /** Branch copies prefix message ids with the new conversation id. The run id stays after the last colon. */
    private fun ownerRunId(userMessageId: String): String =
        userMessageId.substringAfterLast(':').removePrefix("user-").substringBefore("-supplement-")

    /** A branch rewrites cache paths in the bubble but not always in the stored model history. */
    private fun revisionComparableText(text: String): String =
        text.replace(Regex("/eta-chat-images/conv-[^/]+/"), "/eta-chat-images/conv/")

    private fun historyUserIndex(state: AgentChatUiState, uiIndex: Int): Int? =
        (historyMessageLocation(state, uiIndex) as? AgentConversationRevisionArchive.Location.Found)?.index

    /** Compatibility is considered only after the existing owner/legacy lookup is Missing. */
    private fun historyMessageLocation(state: AgentChatUiState, uiIndex: Int): AgentConversationRevisionArchive.Location {
        val owned = ownedHistoryMessageLocation(state, uiIndex)
        if (owned != AgentConversationRevisionArchive.Location.Missing || state.messages.getOrNull(uiIndex) !is UserMessageUi) return owned
        return mismatchedOwnerLocation(state, uiIndex)
    }

    /** turnId scopes a turn, not a message. Exact payload + complete occurrence alignment is required. */
    private fun ownedHistoryMessageLocation(state: AgentChatUiState, uiIndex: Int): AgentConversationRevisionArchive.Location {
        val missing = AgentConversationRevisionArchive.Location.Missing
        val ambiguous = AgentConversationRevisionArchive.Location.Ambiguous
        val message = state.messages.getOrNull(uiIndex) ?: return missing
        if (message is AgentMessageUi && message.content.isNotBlank()) {
            val userIndex = (uiIndex - 1 downTo 0).firstOrNull { state.messages[it] is UserMessageUi } ?: return missing
            val userLocation = historyMessageLocation(state, userIndex)
            if (userLocation !is AgentConversationRevisionArchive.Location.Found) return userLocation
            val nextUser = (userLocation.index + 1 until state.history.size).firstOrNull {
                state.history[it].role == "user" && !isHiddenContinuePrompt(state.history[it])
            } ?: state.history.size
            val expected = revisionComparableText(message.content.trim())
            val candidates = (userLocation.index + 1 until nextUser).filter {
                state.history[it].role == "assistant" && revisionComparableText(historyText(state.history[it])) == expected
            }
            if (candidates.isEmpty()) return missing
            val nextUiUser = (userIndex + 1 until state.messages.size).firstOrNull { state.messages[it] is UserMessageUi } ?: state.messages.size
            val peers = (userIndex + 1 until nextUiUser).filter {
                val reply = state.messages[it]
                reply is AgentMessageUi && revisionComparableText(reply.content.trim()) == expected
            }
            if (candidates.size != peers.size) return ambiguous
            return AgentConversationRevisionArchive.Location.Found(candidates[peers.indexOf(uiIndex)])
        }
        val user = message as? UserMessageUi ?: return missing
        val runId = ownerRunId(user.id)
        val expected = revisionComparableText(user.content.trim())
        val steering = revisionComparableText(AgentContextCompactor.steeringUserContent(user.content).trim())
        fun sameOwnedText(history: AgentModelClient.ConversationMessage, expectedText: String): Boolean {
            val text = revisionComparableText(historyText(history))
            return text == expectedText || (runId.isNotBlank() && history.turnId == runId &&
                (AgentRevisionRuntimeSuffix.matches(text, expectedText) ||
                    AgentRevisionMediaPayload.matches(history, user, ::revisionComparableText, expectedText)))
        }
        val hasSteering = state.history.any {
            it.role == "user" && (it.turnId == runId || it.turnId.isBlank()) &&
                sameOwnedText(it, steering)
        }
        val preferSteering = user.isSteerSupplement() && hasSteering
        fun matches(history: AgentModelClient.ConversationMessage): Boolean {
            if (history.role != "user" || AgentContextCompactor.isCompressionSummary(history)) return false
            val text = revisionComparableText(historyText(history))
            if (sameOwnedText(history, if (preferSteering) steering else expected)) return true
            // Only normalize attachment envelopes within a proven owner, never across turns.
            if (history.turnId != runId || user.isSteerSupplement()) return false
            val parsed = AgentFileReferencePromptCodec.parse(text)
            val ui = AgentFileReferencePromptCodec.parse(expected)
            return ui.request.isNotBlank() && parsed.request.trim() == ui.request.trim() && parsed.conversations == ui.conversations
        }
        val candidates = state.history.indices.filter { matches(state.history[it]) }
        val owned = candidates.filter { state.history[it].turnId == runId }
        val scoped = owned.ifEmpty { candidates.filter { state.history[it].turnId.isBlank() } }
        if (scoped.isEmpty()) return missing
        val peers = state.messages.indices.filter {
            val peer = state.messages[it]
            peer is UserMessageUi && revisionComparableText(peer.content.trim()) == expected &&
                (owned.isEmpty() || ownerRunId(peer.id) == runId) &&
                (if (preferSteering) peer.isSteerSupplement() else !peer.isSteerSupplement() || !hasSteering)
        }
        if (scoped.size != peers.size || uiIndex !in peers) return ambiguous
        return AgentConversationRevisionArchive.Location.Found(scoped[peers.indexOf(uiIndex)])
    }

    private data class RevisionPayload(val text: String, val media: List<Pair<String, String>>)

    // Compare real payload slots, not visibleRequest()/display text. Unknown multimodal parts
    // and incomplete attachment metadata are not evidence. Cross-owner aliases require exact
    // paths: a cache basename is not identity across conversations, even on an old branch.
    private fun payloadText(text: String): String = text.trim()

    private fun payload(user: UserMessageUi): RevisionPayload? {
        if (user.isSteerSupplement()) return null
        if (user.imageSources.isNotEmpty() && user.imageSources.size != user.images.size) return null
        val sources = user.imageSources.ifEmpty { user.images }
        val media = sources.mapIndexed { index, source ->
            if (source.isBlank()) return null
            val type = if (user.imageIsVideo.getOrNull(index) == true) "video" else "image"
            type to source.removePrefix("file://")
        }
        return RevisionPayload(payloadText(user.content), media).takeIf { it.text.isNotBlank() || it.media.isNotEmpty() }
    }

    private fun payload(message: AgentModelClient.ConversationMessage): RevisionPayload? {
        if (message.role != "user" || AgentContextCompactor.isCompressionSummary(message) ||
            AgentContextCompactor.isSteeringUserMessage(message) || message.toolCallsJson.isNotBlank() ||
            message.toolCallId.isNotBlank()) return null
        if (message.contentJson.isBlank()) return RevisionPayload(payloadText(message.content), emptyList())
            .takeIf { it.text.isNotBlank() }
        // Two competing content representations or extra unrecognized parts are ambiguous.
        if (message.content.isNotBlank()) return null
        return try {
            val parts = org.json.JSONArray(message.contentJson)
            var text: String? = null
            val media = mutableListOf<Pair<String, String>>()
            for (index in 0 until parts.length()) {
                val part = parts.getJSONObject(index)
                when (val type = part.getString("type")) {
                    "text" -> {
                        if (text != null) return null
                        text = part.getString("text")
                    }
                    "image_file", "video_file", "image_url", "video_url" -> {
                        val source = if (type.endsWith("_file")) part.getString("path")
                            else part.getJSONObject(type).getString("url")
                        if (source.isBlank()) return null
                        media += type.substringBefore('_') to source.removePrefix("file://")
                    }
                    else -> return null
                }
            }
            val textMessage = message.copy(content = text.orEmpty(), contentJson = "")
            if (AgentContextCompactor.isCompressionSummary(textMessage) || AgentContextCompactor.isSteeringUserMessage(textMessage)) return null
            RevisionPayload(payloadText(text.orEmpty()), media).takeIf { it.text.isNotBlank() || it.media.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    /** Old releases retained a UI owner but minted a new history turn on edit/regenerate.
     * Never repair history from UI: locate one existing payload, reject competing ownership
     * and crossed anchors, and let Boundary expose that history's logical identity.
     */
    private fun mismatchedOwnerLocation(state: AgentChatUiState, uiIndex: Int): AgentConversationRevisionArchive.Location {
        val missing = AgentConversationRevisionArchive.Location.Missing
        val ambiguous = AgentConversationRevisionArchive.Location.Ambiguous
        val user = state.messages[uiIndex] as UserMessageUi
        if (state.messages.map { it.id }.distinct().size != state.messages.size) return ambiguous
        val expected = payload(user) ?: return missing
        val owner = ownerRunId(user.id)
        val candidates = state.history.indices.filter { payload(state.history[it]) == expected }
        if (candidates.isEmpty()) return missing
        if (candidates.size != 1) return ambiguous
        val index = candidates.single()
        val turn = state.history[index].turnId
        if (turn.isBlank() || turn == owner) return missing
        if (state.history.any { it.turnId == owner }) return ambiguous
        // The archive can contain an identical earlier request. Verify the full history first.
        val summaryIndex = state.history.indexOfLast(AgentConversationRevisionArchive::isRevisionSummary)
        if (summaryIndex >= 0) return missing
        val users = state.messages.indices.filter { state.messages[it] is UserMessageUi }
        // Even different attachments cannot disambiguate repeated request bodies in this migration.
        val request = AgentFileReferencePromptCodec.parse(expected.text).request.trim()
        if (users.count {
                AgentFileReferencePromptCodec.parse(payloadText((state.messages[it] as UserMessageUi).content)).request.trim() == request
            } != 1) return ambiguous
        if (state.history.count { it.role == "user" &&
                AgentFileReferencePromptCodec.parse(payloadText(historyText(it))).request.trim() == request } != 1) return ambiguous
        if (users.any { it != uiIndex && ownerRunId(state.messages[it].id) in listOf(owner, turn) }) return ambiguous
        // A logical turn must be a contiguous block with exactly one ordinary user anchor.
        val turnIndices = state.history.indices.filter { state.history[it].turnId == turn }
        val ordinaryUsers = turnIndices.filter {
            val entry = state.history[it]
            entry.role == "user" &&
                !AgentContextCompactor.isSteeringUserMessage(
                    entry.copy(content = historyText(entry), contentJson = ""),
                )
        }
        if (turnIndices.last() - turnIndices.first() + 1 != turnIndices.size ||
            ordinaryUsers != listOf(index)) return ambiguous
        for (peerIndex in users) {
            if (peerIndex == uiIndex) continue
            val direct = ownedHistoryMessageLocation(state, peerIndex)
            if (direct == ambiguous) return ambiguous
            val peerHistoryIndex = (direct as? AgentConversationRevisionArchive.Location.Found)?.index ?: run {
                val peerPayload = payload(state.messages[peerIndex] as UserMessageUi)
                if (peerPayload == null) null else state.history.indices.filter { payload(state.history[it]) == peerPayload }.singleOrNull()
            }
            if (peerHistoryIndex == null && !(state.messages[peerIndex] as UserMessageUi).isSteerSupplement()) return ambiguous
            if (peerHistoryIndex != null && ((peerIndex < uiIndex) != (peerHistoryIndex < index) || peerHistoryIndex == index)) return ambiguous
        }
        return AgentConversationRevisionArchive.Location.Found(index)
    }

    private fun isHiddenContinuePrompt(message: AgentModelClient.ConversationMessage): Boolean =
        AgentContextCompactor.isSteeringUserMessage(message) &&
            !message.content.trimStart().startsWith(AgentContextCompactor.STEERING_USER_PREFIX)

    private fun historyText(message: AgentModelClient.ConversationMessage): String =
        message.content.ifBlank {
            runCatching {
                val parts = org.json.JSONArray(message.contentJson)
                (0 until parts.length()).mapNotNull { index ->
                    parts.optJSONObject(index)?.takeIf { it.optString("type") == "text" }?.optString("text")
                }.filter { it.isNotBlank() }.joinToString("\n")
            }.getOrDefault("")
        }.trim()

    fun outboundHistory(state: AgentChatUiState): List<AgentModelClient.ConversationMessage> {
        val edit = state.messageEdit ?: return state.history
        if (edit.preparedFromHistory != null && edit.preparedFromHistory != state.history) return state.history
        val prepared = state.copy(history = edit.preparedHistory ?: state.history)
        return boundary(prepared, edit.targetMessageId)?.historyPrefix ?: state.history
    }

    fun visibleMessagesForEdit(
        messages: List<AgentChatMessageUi>,
        targetMessageId: String?,
    ): List<AgentChatMessageUi> {
        if (targetMessageId == null) return messages
        val targetIndex = messages.indexOfFirst { it.id == targetMessageId }
        return if (targetIndex < 0) messages else messages.take(targetIndex + 1)
    }

    /**
     * 暂停/结束任务后，屏幕上已写出的助手正文必须进模型历史。
     * 否则下一轮请求看不到刚才的完整回答。
     */
    fun commitVisibleAssistantIntoHistory(
        history: List<AgentModelClient.ConversationMessage>,
        messages: List<AgentChatMessageUi>,
    ): List<AgentModelClient.ConversationMessage> {
        val lastUserIndex = messages.indexOfLast { message ->
            message is UserMessageUi
        }
        val partial = messages
            .drop((lastUserIndex + 1).coerceAtLeast(0))
            .filterIsInstance<AgentMessageUi>()
            .lastOrNull { it.content.isNotBlank() }
            ?: return history
        return historyWithTrailingPartial(history, partial)
    }

    fun historyWithTrailingPartial(
        history: List<AgentModelClient.ConversationMessage>,
        partial: AgentMessageUi,
    ): List<AgentModelClient.ConversationMessage> {
        val migrated = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(history)
        val partialMessage = AgentModelClient.ConversationMessage(
            role = "assistant",
            content = partial.content,
            turnId = migrated.lastOrNull { it.turnId.isNotBlank() }?.turnId.orEmpty(),
        )
        val last = migrated.lastOrNull()
        if (last?.role == "assistant" && last.content == partial.content) return migrated
        val extendsTrailingText = last?.role == "assistant" &&
            last.toolCallsJson.isBlank() && last.content.isNotBlank() && partial.content.startsWith(last.content)
        return if (extendsTrailingText) migrated.dropLast(1) + partialMessage else migrated + partialMessage
    }

}

internal fun AgentChatMessageUi.withId(id: String): AgentChatMessageUi = when (this) {
    is io.github.mangi.eta.ui.model.AgentQuestionMessageUi -> copy(id = id,
        status = if (status == io.github.mangi.eta.agent.question.AgentQuestionStatus.Waiting)
            io.github.mangi.eta.agent.question.AgentQuestionStatus.Interrupted else status, submitting = false)
    is UserMessageUi -> copy(id = id)
    is AgentMessageUi -> copy(id = id)
    is SystemNoticeMessageUi -> copy(id = id)
    is io.github.mangi.eta.ui.model.ErrorReconnectMessageUi -> copy(id = id)
    is ThinkingMessageUi -> copy(id = id)
    is RunTraceMessageUi -> copy(id = id)
    is ToolSummaryMessageUi -> copy(id = id)
    is ContextCompactedMessageUi -> copy(id = id)
    is ToolActivityMessageUi -> copy(id = id)
    is SuggestionChipsMessageUi -> copy(id = id)
    is io.github.mangi.eta.ui.model.RemoteNoticeMessageUi -> copy(id = id)
}

internal fun AgentChatMessageUi.rewritePaths(rewrite: (String) -> String): AgentChatMessageUi = when (this) {
    is UserMessageUi -> copy(
        content = io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec.rewriteReferencePaths(content, rewrite),
        imageSources = imageSources.map(rewrite),
        images = images.map(rewrite),
    )
    else -> this
}

internal fun AgentModelClient.ConversationMessage.rewritePaths(
    rewrite: (String) -> String,
): AgentModelClient.ConversationMessage =
    io.github.mangi.eta.agent.model.AgentConversationAttachmentRelocator.rewrite(this, rewrite)

