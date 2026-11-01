package io.github.mangi.eta.ui.remote

import io.github.mangi.eta.agent.remote.RemoteAgentEvent
import io.github.mangi.eta.agent.remote.RemoteContextUsage
import io.github.mangi.eta.agent.remote.RemoteLiveTurn
import io.github.mangi.eta.agent.remote.RemoteModelOption
import io.github.mangi.eta.agent.remote.RemotePart
import io.github.mangi.eta.agent.remote.RemotePrompt
import io.github.mangi.eta.agent.remote.RemoteSession
import io.github.mangi.eta.agent.remote.RemoteSessionInfo
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionOption
import io.github.mangi.eta.agent.question.AgentQuestionRequest
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.RemoteNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Switchable model list plus the session's current spec, from a `models` frame. */
data class RemoteModelsState(
    val current: String? = null,
    val options: List<RemoteModelOption> = emptyList(),
)

/** Session sidebar list plus the active path, from a `sessions` frame. */
data class RemoteSessionsState(
    val current: String? = null,
    val sessions: List<RemoteSessionInfo> = emptyList(),
)

/** Held input lines typed while a task run owns the remote session. */
data class RemoteHeldState(
    val texts: List<String> = emptyList(),
    val runActive: Boolean = false,
)

/**
 * Folds one remote connection's normalized events into an [AgentChatUiState], so the
 * existing chat screens render a remote agent unchanged.
 *
 * The holder mirrors pi's own `session-state.ts` invariant: committed history and the
 * in-flight live turn are maintained as the SAME model the snapshot serializes, so a
 * reconnect snapshot and the deltas that preceded it cannot disagree. Committed turns
 * are projected once by [RemoteMessageMapper]; the live turn is kept as a
 * [RemoteLiveTurn] and re-projected on each delta (cheap — it holds only the current
 * run's parts, never the whole transcript).
 *
 * All mutation happens on the adapter's callback thread, which [RemoteConnectionSupervisor]
 * already marshals to Main.immediate, so no extra synchronization is needed and the
 * emitted [StateFlow] is Compose-safe.
 *
 * Prompts, models, sessions and held lines are exposed on their own flows: the MVP
 * renders the transcript, and the prompt question-card + answer wiring, model/session
 * switchers and held chips land in stage 2 on top of these seams.
 */
internal class RemoteSessionStateHolder(
    private val scope: CoroutineScope,
) {
    private var session: RemoteSession? = null
    private var seq = 0

    private var committed: List<AgentChatMessageUi> = emptyList()
    private var liveTurn: RemoteLiveTurn? = null
    private var running = false
    private var modelLabel: String? = null
    private var contextUsage: RemoteContextUsage? = null

    /** In-progress answer for the live prompt; the card re-renders from it on each edit. */
    private var promptDraft: AgentQuestionAnswer? = null

    /** Images staged for the next send; uploaded to the server on attach, ids sent with the message. */
    private var pendingImages: List<PendingImageUi> = emptyList()

    /**
     * Compose-observable chat projection. The screens read this through a plain
     * `stateProvider` lambda during composition, so it must be snapshot state (not a
     * cold Flow) for a delta to trigger recomposition. Written only on Main.immediate.
     */
    var chatState by mutableStateOf(
        AgentChatUiState(
            messages = emptyList(),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
            isRemote = true,
            providerId = "remote",
        ),
    )
        private set

    private val _prompt = MutableStateFlow<RemotePrompt?>(null)
    val prompt: StateFlow<RemotePrompt?> = _prompt.asStateFlow()

    private val _models = MutableStateFlow(RemoteModelsState())
    val models: StateFlow<RemoteModelsState> = _models.asStateFlow()

    private val _sessions = MutableStateFlow(RemoteSessionsState())
    val sessions: StateFlow<RemoteSessionsState> = _sessions.asStateFlow()

    private val _held = MutableStateFlow(RemoteHeldState())
    val held: StateFlow<RemoteHeldState> = _held.asStateFlow()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Non-error `notify` frames, surfaced as snackbars by the coordinator. */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    /** Bind the live session used for outbound commands. Called once after the adapter opens it. */
    fun attach(session: RemoteSession) {
        this.session = session
    }

    fun onEvent(event: RemoteAgentEvent) {
        when (event) {
            is RemoteAgentEvent.Snapshot -> applySnapshot(event)
            is RemoteAgentEvent.RunStarted -> {
                running = true
                event.modelLabel?.let { modelLabel = it }
                liveTurn = RemoteLiveTurn()
                emit()
            }
            is RemoteAgentEvent.TextDelta -> { appendText(event.delta); emit() }
            is RemoteAgentEvent.TextEnd -> { textEnd(); emit() }
            is RemoteAgentEvent.ThinkingDelta -> { appendThinking(event.delta); emit() }
            is RemoteAgentEvent.ThinkingEnd -> { thinkingEnd(); emit() }
            is RemoteAgentEvent.ToolStart -> { toolStart(event.toolCallId, event.toolName, event.argsJson); emit() }
            is RemoteAgentEvent.ToolEnd -> {
                toolEnd(event.toolCallId, event.toolName, event.resultJson, event.isError, event.elapsedMs)
                emit()
            }
            is RemoteAgentEvent.RunFinished -> {
                val finalized = RemoteMessageMapper.mapLive(liveTurn, running = false)
                if (finalized.isNotEmpty()) committed += finalized
                liveTurn = null
                running = false
                event.contextUsage?.let { contextUsage = it }
                event.modelLabel?.let { modelLabel = it }
                emit()
            }
            is RemoteAgentEvent.RunFailed -> {
                liveTurn = null
                running = false
                committed += RemoteNoticeMessageUi(id = nextId("err"), text = event.message, isError = true)
                emit()
            }
            is RemoteAgentEvent.UserMessageEcho -> {
                reconcileOptimistic(event.text)
                committed += RemoteMessageMapper.mapUserTurn(nextId("u"), event.text, event.attachments)
                emit()
            }
            is RemoteAgentEvent.SystemNote -> {
                committed += RemoteNoticeMessageUi(id = nextId("note"), text = event.text, isError = false)
                emit()
            }
            is RemoteAgentEvent.Reset -> {
                committed = emptyList()
                liveTurn = null
                running = false
                contextUsage = null
                pendingImages = emptyList()
                setPrompt(null)
                _held.value = RemoteHeldState()
                // model is deliberately kept: it survives a /new on the server too.
                emit()
            }
            is RemoteAgentEvent.PromptPosted -> { setPrompt(event.prompt); emit() }
            is RemoteAgentEvent.PromptResolved ->
                if (_prompt.value?.id == event.id) { setPrompt(null); emit() }
            is RemoteAgentEvent.ModelsUpdated -> _models.value = RemoteModelsState(event.current, event.models)
            is RemoteAgentEvent.SessionsUpdated -> _sessions.value = RemoteSessionsState(event.current, event.sessions)
            is RemoteAgentEvent.HeldUpdated -> _held.value = RemoteHeldState(event.texts, event.runActive)
            is RemoteAgentEvent.Notify -> {
                if (event.level == "error") {
                    committed += RemoteNoticeMessageUi(id = nextId("notify"), text = event.message, isError = true)
                    emit()
                } else {
                    _notices.tryEmit(event.message)
                }
            }
            is RemoteAgentEvent.HistoryPage -> {
                committed = RemoteMessageMapper.mapTurns(event.turns) + committed
                emit()
            }
        }
    }

    /**
     * Optimistic send: insert a pending user bubble immediately, then hand the frame to
     * the socket. A false delivery (disconnected) rolls the bubble back and restores any
     * staged images; a real `user_message` echo replaces it in place (see [reconcileOptimistic]).
     * Staged image ids travel with the frame and are cleared on success.
     */
    fun sendMessage(text: String) {
        val body = text.trim()
        val images = pendingImages
        if (body.isEmpty() && images.isEmpty()) return
        val attachmentIds = images.map { it.id }
        val pendingId = nextId("pending")
        committed += UserMessageUi(id = pendingId, content = text, images = images.map { it.dataUrl })
        pendingImages = emptyList()
        emit()
        val target = session
        if (target == null) {
            committed = committed.filterNot { it.id == pendingId }
            pendingImages = images
            _notices.tryEmit("发送失败：连接尚未就绪")
            emit()
            return
        }
        scope.launch {
            val delivered = runCatching { target.sendMessage(text, attachmentIds) }.getOrDefault(false)
            if (!delivered) {
                committed = committed.filterNot { it.id == pendingId }
                pendingImages = images
                _notices.tryEmit("发送失败：与远程服务器的连接不可用")
                emit()
            }
        }
    }

    /** Stage an already-uploaded image (server id) for the next send; the composer renders it. */
    fun stagePendingImage(image: PendingImageUi) {
        pendingImages = pendingImages + image
        emit()
    }

    fun removePendingImage(id: String) {
        pendingImages = pendingImages.filterNot { it.id == id }
        emit()
    }

    /** Surface a one-line notice in the transcript (visible without a snackbar seam). */
    fun notice(text: String, isError: Boolean = false) {
        committed += RemoteNoticeMessageUi(id = nextId("notice"), text = text, isError = isError)
        emit()
    }

    /** Drop everything and wait for the next snapshot (used on manual disconnect). */
    fun clear() {
        committed = emptyList()
        liveTurn = null
        running = false
        contextUsage = null
        pendingImages = emptyList()
        setPrompt(null)
        _held.value = RemoteHeldState()
        emit()
    }

    private fun applySnapshot(event: RemoteAgentEvent.Snapshot) {
        committed = RemoteMessageMapper.mapTurns(event.turns)
        liveTurn = event.live
        running = event.running
        modelLabel = event.modelLabel
        contextUsage = event.contextUsage
        setPrompt(event.prompt)
        _held.value = RemoteHeldState(event.held, event.heldRunActive)
        emit()
    }

    /** Adopt a prompt (or clear it) and seed the draft so Submit always has a value. */
    private fun setPrompt(prompt: RemotePrompt?) {
        _prompt.value = prompt
        promptDraft = prompt?.let { defaultPromptDraft(it) }
    }

    private fun defaultPromptDraft(prompt: RemotePrompt): AgentQuestionAnswer {
        val recommended = prompt.recommended?.takeIf { it.isNotBlank() }
        return when {
            recommended != null ->
                AgentQuestionAnswer(kind = AgentQuestionAnswer.KIND_OTHER, otherText = recommended)
            prompt.actions.isNotEmpty() ->
                AgentQuestionAnswer(kind = AgentQuestionAnswer.KIND_OPTION, optionId = prompt.actions.first().value)
            else -> AgentQuestionAnswer(kind = AgentQuestionAnswer.KIND_OTHER)
        }
    }

    /** Card edit: remember the selection and re-project so the highlight updates. */
    fun updatePromptDraft(answer: AgentQuestionAnswer) {
        if (_prompt.value == null) return
        promptDraft = answer
        emit()
    }

    /**
     * Resolve the current draft to the wire value pi expects for `prompt_answer`:
     * an action's value for a chosen option, the free text for "other", or null to skip.
     */
    fun resolvePromptValue(questionId: String): String? {
        val prompt = _prompt.value ?: return null
        if (prompt.id != questionId) return null
        val draft = promptDraft
        return when (draft?.kind) {
            AgentQuestionAnswer.KIND_OPTION -> draft.optionId
            AgentQuestionAnswer.KIND_OTHER ->
                draft.otherText.ifBlank { prompt.recommended.orEmpty() }.ifBlank { null }
            else -> if (prompt.allowSkip) null else draft?.otherText?.ifBlank { prompt.recommended.orEmpty() }
        }
    }

    private fun reconcileOptimistic(text: String) {
        val last = committed.lastOrNull()
        if (last is UserMessageUi && last.id.contains("-pending-") && last.content == text) {
            committed = committed.dropLast(1)
        }
    }

    // ─── Live-turn folding (mirrors pi session-state.ts mutators) ──────────────

    private fun appendText(delta: String) {
        val current = liveTurn ?: RemoteLiveTurn()
        val parts = current.parts.toMutableList()
        val last = parts.lastOrNull()
        if (current.textOpen && last is RemotePart.Text) {
            parts[parts.lastIndex] = RemotePart.Text(last.text + delta)
        } else {
            parts += RemotePart.Text(delta)
        }
        liveTurn = RemoteLiveTurn(parts, textOpen = true)
    }

    private fun textEnd() {
        liveTurn = liveTurn?.copy(textOpen = false)
    }

    private fun appendThinking(delta: String) {
        val current = liveTurn ?: RemoteLiveTurn()
        val parts = current.parts.toMutableList()
        val last = parts.lastOrNull()
        if (last is RemotePart.Thinking && !last.done) {
            parts[parts.lastIndex] = RemotePart.Thinking(last.text + delta, done = false)
        } else {
            parts += RemotePart.Thinking(delta, done = false)
        }
        liveTurn = RemoteLiveTurn(parts, textOpen = false)
    }

    private fun thinkingEnd() {
        val current = liveTurn ?: return
        val parts = current.parts.toMutableList()
        val last = parts.lastOrNull()
        if (last is RemotePart.Thinking) parts[parts.lastIndex] = last.copy(done = true)
        liveTurn = current.copy(parts = parts)
    }

    private fun toolStart(toolCallId: String, toolName: String, argsJson: String?) {
        val current = liveTurn ?: RemoteLiveTurn()
        val parts = current.parts.toMutableList()
        parts += RemotePart.Tool(
            toolCallId = toolCallId,
            toolName = toolName,
            argsJson = argsJson,
            resultJson = null,
            isError = false,
            done = false,
            elapsedMs = null,
        )
        liveTurn = RemoteLiveTurn(parts, textOpen = false)
    }

    private fun toolEnd(
        toolCallId: String,
        toolName: String,
        resultJson: String?,
        isError: Boolean,
        elapsedMs: Long?,
    ) {
        val current = liveTurn ?: RemoteLiveTurn()
        val parts = current.parts.toMutableList()
        val index = parts.indexOfFirst { it is RemotePart.Tool && it.toolCallId == toolCallId }
        if (index >= 0) {
            val tool = parts[index] as RemotePart.Tool
            parts[index] = tool.copy(resultJson = resultJson, isError = isError, done = true, elapsedMs = elapsedMs)
        } else {
            parts += RemotePart.Tool(
                toolCallId = toolCallId,
                toolName = toolName,
                argsJson = null,
                resultJson = resultJson,
                isError = isError,
                done = true,
                elapsedMs = elapsedMs,
            )
        }
        liveTurn = current.copy(parts = parts)
    }

    private fun emit() {
        val base = committed + RemoteMessageMapper.mapLive(liveTurn, running)
        val prompt = _prompt.value
        val messages = if (prompt != null) base + questionMessage(prompt) else base
        chatState = AgentChatUiState(
            messages = messages,
            input = "",
            isStreaming = running,
            isWaitingForAnswer = prompt != null,
            thinkingEnabled = false,
            isRemote = true,
            providerId = "remote",
            modelId = modelLabel.orEmpty(),
            pendingImages = pendingImages,
            livePromptTokens = contextUsage?.tokens,
            contextHasStarted = contextUsage?.tokens != null,
        )
    }

    /** Project the live pi prompt onto the same question card the local ask_user flow uses. */
    private fun questionMessage(prompt: RemotePrompt): AgentQuestionMessageUi {
        val options = prompt.actions.map { AgentQuestionOption(id = it.value, label = it.label) }
        val draft = promptDraft
        val request = AgentQuestionRequest(
            questionId = prompt.id,
            conversationId = "",
            runId = prompt.id,
            toolCallId = prompt.id,
            title = prompt.question,
            question = prompt.question,
            options = options,
            recommendedOptionId = null,
            allowOther = true,
            allowDelegation = false,
            allowNote = false,
        )
        return AgentQuestionMessageUi(
            id = "rmsg-q-${prompt.id}",
            request = request,
            status = AgentQuestionStatus.Waiting,
            answer = null,
            selectedOptionId = draft?.optionId,
            answerKind = draft?.kind ?: AgentQuestionAnswer.KIND_OTHER,
            otherText = draft?.otherText ?: prompt.recommended.orEmpty(),
        )
    }

    private fun nextId(kind: String): String = "rmsg-$kind-${seq++}"
}
