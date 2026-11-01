package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.MentionedConversation
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import java.io.File

internal data class ConversationMentionQuery(
    val start: Int,
    val query: String,
)

internal object ConversationMention {
    const val MAX_TRANSCRIPT_CHARS = 240_000
    /** Snapshot file cap. The prompt itself does not carry this text. */
    const val SNAPSHOT_MAX_CHARS = 1_500_000
    const val MAX_RESULTS = 8
    const val MAX_ATTACHED = 3
    const val MAX_TOTAL_CHARS = 480_000

    fun queryAtCursor(text: String, cursor: Int): ConversationMentionQuery? {
        val index = cursor.coerceIn(0, text.length)
        val before = text.substring(0, index)
        val at = before.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && (before[at - 1] in 'a'..'z' || before[at - 1] in 'A'..'Z' ||
            before[at - 1].isDigit() || before[at - 1] in "._%+-/@")) return null
        val query = before.substring(at + 1)
        if (query.any { it == '\n' }) return null
        return ConversationMentionQuery(start = at, query = query)
    }

    fun candidates(
        conversations: List<ConversationSummaryUi>,
        query: String,
        excludeId: String?,
        alreadyAttached: Set<String>,
        limit: Int = MAX_RESULTS,
    ): List<ConversationSummaryUi> {
        val needle = query.trim()
        return conversations.asSequence()
            .filter { it.id != excludeId && it.id !in alreadyAttached }
            .filter { needle.isEmpty() || matches(it, needle) }
            .sortedByDescending { it.updatedAtMillis }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    fun matches(conversation: ConversationSummaryUi, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return conversation.title.contains(needle, ignoreCase = true) ||
            conversation.preview.contains(needle, ignoreCase = true)
    }

    const val OMISSION_MARKER = "\n\n[已截取：中间记录已省略，保留开头与最近记录]\n\n"

    fun transcript(
        messages: List<AgentChatMessageUi>,
        maxChars: Int = MAX_TRANSCRIPT_CHARS,
        filesDir: File? = null,
        conversationId: String? = null,
        toolEvidence: ConversationToolEvidence? = null,
        toolFiles: MutableList<String>? = null,
    ): String {
        if (maxChars <= 0) return ""
        val toolDetailsDirectory = filesDir?.let { prepareToolDetailsDirectory(it, conversationId) }
        val chunks = messages.mapNotNull { formatMessage(it, toolDetailsDirectory, toolEvidence, toolFiles) }
        if (chunks.isEmpty()) return ""
        val joined = chunks.joinToString("\n\n")
        if (joined.length <= maxChars) return joined
        val keep = (maxChars - OMISSION_MARKER.length).coerceAtLeast(0)
        if (keep == 0) return OMISSION_MARKER.trim().take(maxChars)
        val headBudget = (keep / 2).coerceAtLeast(1)
        val tailBudget = (keep - headBudget).coerceAtLeast(1)
        return (joined.take(headBudget) + OMISSION_MARKER + joined.takeLast(tailBudget)).take(maxChars)
    }

    internal const val TOOL_DETAILS_DIRECTORY = "快照缓存/tools"

    private fun prepareToolDetailsDirectory(filesDir: File, conversationId: String?): File? {
        val token = sanitizeFileToken(conversationId.orEmpty().ifBlank { "conversation" })
        val directory = File(TerminalPrivateStorage.workspace(filesDir), "$TOOL_DETAILS_DIRECTORY/$token/${java.util.UUID.randomUUID()}")
        return runCatching { directory.mkdirs(); directory.takeIf { it.isDirectory } }.getOrNull()
    }

    internal fun toolActivityDetails(message: ToolActivityMessageUi): String = buildString {
        append("Evidence: summary only / 仅有摘要；原始参数与结果未在此文件中恢复。\n")
        message.argumentsSummary.trim().takeIf { it.isNotEmpty() }?.let {
            append("Arguments summary:\n").append(it).append('\n')
        }
        message.command?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("Command:\n").append(it).append('\n')
        }
        message.resultSummary?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("Result summary:\n").append(it).append('\n')
        }
    }.trim()

    private fun writeToolDetailsFile(directory: File, message: ToolActivityMessageUi, details: String): File? {
        val file = File(directory, "${sanitizeFileToken(message.id)}.txt")
        return runCatching {
            file.writeText(details)
            file.takeIf { it.isFile }
        }.getOrNull()
    }

    private fun sanitizeFileToken(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "tool" }

    private fun formatToolActivity(
        message: ToolActivityMessageUi,
        toolDetailsDirectory: File?,
        toolEvidence: ConversationToolEvidence?,
        toolFiles: MutableList<String>?,
    ): String = buildString {
        append("Tool ${message.toolName}: ${message.status.name}")
        val original = toolEvidence?.original(message.id)
        val hasSummary = message.argumentsSummary.isNotBlank() || !message.command.isNullOrBlank() || !message.resultSummary.isNullOrBlank()
        val details = when {
            original != null -> original.details()
            hasSummary -> toolActivityDetails(message)
            else -> ""
        }
        val detailsFile = if (details.isNotEmpty() && toolDetailsDirectory != null) {
            writeToolDetailsFile(toolDetailsDirectory, message, details)
        } else {
            null
        }
        if (detailsFile != null) {
            toolFiles?.add("${detailsFile.absolutePath}\t${detailsFile.length()}\t${message.toolName}")
            append("\nDetails file: ").append(detailsFile.absolutePath)
            append("\nBytes: ").append(detailsFile.length())
            if (original != null) append("\nComplete stored tool record. Read with read_file until EOF; one page is not the whole tool.")
            else append("\nStored history has no full record for this call; the file is only the UI summary.")
        } else if (details.isNotEmpty()) {
            append('\n').append(if (original != null) original.details() else toolActivityDetails(message))
        }
        if (message.imageCount > 0) {
            append("\nImages: ").append(message.imageCount)
        }
    }

    fun remainingTranscriptBudget(already: List<PendingConversationMentionUi>): Int =
        (MAX_TOTAL_CHARS - already.sumOf { it.transcript.length }).coerceAtLeast(0)

    /** Full snapshot for on-demand reads. Returns null when the directory cannot be created. */
    fun writeSnapshot(filesDir: File, conversationId: String, transcript: String): File? {
        if (transcript.isBlank()) return null
        val token = sanitizeFileToken(conversationId.ifBlank { "conversation" })
        val directory = File(TerminalPrivateStorage.workspace(filesDir), "$TOOL_DETAILS_DIRECTORY/$token")
        if (!runCatching { directory.mkdirs(); directory.isDirectory }.getOrDefault(false)) return null
        val file = File(directory, "snapshot.txt")
        return runCatching {
            file.writeText(transcript)
            file.takeIf { it.isFile && it.length() > 0L }
        }.getOrNull()
    }

    /** One line per tool file: path, bytes, tool name. Not a summary of the tool output. */
    fun writeToolIndex(snapshot: File, toolFiles: List<String>): File? {
        if (toolFiles.isEmpty()) return null
        val index = File(snapshot.parentFile, "tools-index.txt")
        return runCatching {
            index.writeText(toolFiles.joinToString("\n"))
            index.takeIf { it.isFile }
        }.getOrNull()
    }

    private fun formatMessage(
        message: AgentChatMessageUi,
        toolDetailsDirectory: File?,
        toolEvidence: ConversationToolEvidence?,
        toolFiles: MutableList<String>?,
    ): String? {
        return when (message) {
        is UserMessageUi -> {
            if (message.isResumeAfterCompress()) return null
            val parsed = AgentFileReferencePromptCodec.parse(message.content)
            buildString {
                if (parsed.conversations.isNotEmpty()) {
                    append("User mentioned conversations: ")
                    append(parsed.conversations.joinToString { it.title })
                    append('\n')
                }
                if (parsed.references.isNotEmpty()) {
                    append("User attached files: ")
                    append(parsed.references.joinToString { it.displayName })
                    append('\n')
                }
                val body = parsed.request.trim()
                if (body.isEmpty() && parsed.conversations.isEmpty() && parsed.references.isEmpty()) return null
                append("User: ")
                append(body.ifBlank { "(attachment only)" })
            }
        }
        is AgentMessageUi -> message.content.trim().takeIf { it.isNotEmpty() }?.let { "Assistant: $it" }
        is ThinkingMessageUi -> message.content.trim().takeIf { it.isNotEmpty() }?.let { "Thinking: $it" }
        is ToolSummaryMessageUi -> message.tools.takeIf { it.isNotEmpty() }?.let { "Tools: ${it.joinToString()}" }
        is AgentQuestionMessageUi -> buildString {
            append("Question: ").append(message.request.title).append(" — ").append(message.request.question)
            val answer = message.answer
            if (message.status == io.github.mangi.eta.agent.question.AgentQuestionStatus.Answered && answer != null) {
                append("\nUser answer: ").append(io.github.mangi.eta.agent.question.AgentQuestionCodec.resultJson(message.request, answer))
            } else append("\nQuestion status: ").append(message.status.name)
        }
        is ToolActivityMessageUi -> formatToolActivity(message, toolDetailsDirectory, toolEvidence, toolFiles)
        is ContextCompactedMessageUi -> {
            val summary = message.summary.trim()
            if (summary.isEmpty()) "Context compressed (${message.compactedCount} messages)"
            else "Context compressed (${message.compactedCount} messages): $summary"
        }
        is SystemNoticeMessageUi -> "System status: ${message.code.name}"
        is ErrorReconnectMessageUi -> "Connection status: ${message.status.wireValue} (${formatReconnectElapsed(message.elapsedMs)})"
        is RunTraceMessageUi, is SuggestionChipsMessageUi, is RemoteNoticeMessageUi -> null
    }
    }
}

internal fun List<PendingConversationMentionUi>.toMentionedConversations(): List<MentionedConversation> =
    map {
        MentionedConversation(
            id = it.conversationId,
            title = it.title,
            transcript = "",
            snapshotPath = it.snapshotPath,
            toolsIndexPath = it.toolsIndexPath,
        )
    }

/** One composer-scoped controller, passed explicitly through both Home and Chat screens. */
internal data class ConversationMentionInputUi(
    val conversations: List<ConversationSummaryUi> = emptyList(),
    val currentConversationId: String? = null,
    val pending: List<PendingConversationMentionUi> = emptyList(),
    val onAttach: (String) -> Boolean = { false },
    val onRemove: (String) -> Unit = {},
)
