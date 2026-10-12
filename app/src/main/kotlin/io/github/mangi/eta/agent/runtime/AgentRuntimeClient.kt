package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionReceipt
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Entry-side IPC client; no model execution or tool ownership here. */
internal class AgentRuntimeClient(private val context: Context, private val logger: AgentLogger) {
    sealed interface AttachOutcome {
        data class Completed(val result: AgentRuntimeWire.RunResult) : AttachOutcome
        data object NotActive : AttachOutcome
        data object Unavailable : AttachOutcome
    }
    sealed interface ActiveRunQuery {
        data class Known(val runIds: Set<String>) : ActiveRunQuery {
            val runId: String? get() = runIds.firstOrNull()
        }
        data object Unavailable : ActiveRunQuery
    }
    sealed interface CompletedRunsQuery {
        data class Known(val runs: List<AgentRuntimeWire.CompletedRun>) : CompletedRunsQuery
        data object Unavailable : CompletedRunsQuery
    }

    fun run(request: AgentRuntimeWire.RunRequest, onEvent: (AgentEvent) -> Unit): AgentRuntimeWire.RunResult =
        run(request, onEvent, isStopRequested = { false })

    fun run(request: AgentRuntimeWire.RunRequest, onEvent: (AgentEvent) -> Unit,
        isStopRequested: () -> Boolean,
        mainStopReason: () -> AgentChildControlPolicy.Reason? = { null },
    ): AgentRuntimeWire.RunResult {
        if (isStopRequested()) return AgentRuntimeWire.RunResult(request.runId, false, "", "已停止")
        val resultLatch = CountDownLatch(1)
        val resultRef = AtomicReference<AgentRuntimeWire.RunResult?>()
        val preparedImagesRef = AtomicReference<AgentRuntimeImageTransfer.PreparedImages?>()
        val preparedHistoryRef = AtomicReference<AgentRuntimeHistoryTransfer.PreparedHistory?>()
        val clientMessenger = Messenger(ClientHandler(onEvent,
            onResult = { result -> resultRef.set(result); resultLatch.countDown() },
            onRequestIngested = {
                preparedImagesRef.getAndSet(null)?.close()
                preparedHistoryRef.getAndSet(null)?.close()
            }).also { it.diagnosticRunId = request.runId })
        val lease = AgentRuntimeConnection.acquire(context, logger)
            ?: return AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 服务绑定失败")
        val serviceMessenger = lease.messenger
        val deathRecipient = IBinder.DeathRecipient {
            if (resultRef.get() == null) {
                resultRef.set(AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 服务连接已断开"))
                resultLatch.countDown()
            }
        }
        try {
            lease.binder.linkToDeath(deathRecipient, 0)
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_START_RUN)
            msg.replyTo = clientMessenger
            val preparedImages = AgentRuntimeImageTransfer.prepare(context, request.images)
            preparedImagesRef.set(preparedImages)
            val preparedHistory = AgentRuntimeHistoryTransfer.prepare(context, request.history)
            preparedHistoryRef.set(preparedHistory)
            msg.data = AgentRuntimeWire.toBundle(request, preparedImages.images, preparedHistory.descriptor)
            serviceMessenger.send(msg)
            if (isStopRequested()) {
                sendRequestedStop(serviceMessenger, request.runId, mainStopReason())
            }
            if (!awaitRunResult(resultLatch, isStopRequested)) {
                return AgentRuntimeWire.RunResult(request.runId, false, "", "已停止，但运行时未在限期内返回结果")
            }
            return resultRef.get() ?: AgentRuntimeWire.RunResult("", false, "", "Agent Runtime 未返回结果")
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching {
                if (isStopRequested()) {
                    sendRequestedStop(serviceMessenger, request.runId, mainStopReason())
                }
            }
            throw interrupted
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime start request failed: type=${throwable.safeLogType()}")
            return AgentRuntimeWire.RunResult(request.runId, false, "", when (throwable) {
                is AgentRuntimeWire.PayloadTooLargeException -> throwable.message
                is AgentRuntimeImageTransfer.ImageTransferException -> throwable.message
                else -> "Agent Runtime 请求发送失败（${throwable.safeLogType()}）"
            })
        } finally {
            preparedImagesRef.getAndSet(null)?.close()
            preparedHistoryRef.getAndSet(null)?.close()
            runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
            lease.close()
        }
    }

    private fun sendRequestedStop(
        messenger: Messenger,
        runId: String,
        mainReason: AgentChildControlPolicy.Reason?,
    ) {
        // Preserve the captured stop scope if termination races request delivery or interruption.
        val message = Message.obtain(null, AgentRuntimeStopDispatch.message(mainReason))
        message.data = AgentRuntimeWire.ackBundle(runId).apply {
            mainReason?.let { putString("child_stop_reason", it.name) }
        }
        messenger.send(message)
    }

    /** Entire captured run, INCLUDING detached or queued children of that run. Explicit destructive action only. */
    fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        withRuntimeMessenger(Unit) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
        }
    }

    /** Stops runs the UI no longer tracks, including a continuation whose binding was already dropped. */
    fun cancelConversation(conversationId: String) {
        if (conversationId.isBlank() || conversationId.length > 1024) return
        withRuntimeMessenger(Unit) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_CANCEL_CONVERSATION)
            msg.data = android.os.Bundle().apply { putString("conversation_id", conversationId) }
            serviceMessenger.send(msg)
        }
    }

    /** Stop the parent, freeze its children and retain a one-shot pause/stop choice if needed. */
    fun stopMainRun(runId: String): Boolean = stopMainRun(runId, AgentChildControlPolicy.Reason.USER_STOP)

    /** Settings must already be applied once by their owner, never saved as a dialog callback. */
    fun stopMainRun(runId: String, reason: AgentChildControlPolicy.Reason): Boolean {
        if (runId.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_STOP_MAIN_RUN)
            msg.data = AgentRuntimeWire.ackBundle(runId).apply { putString("child_stop_reason", reason.name) }
            serviceMessenger.send(msg)
            true
        }
    }

    fun steerRun(runId: String, text: String, requestId: String = "", imagesJson: String = "[]"): Boolean {
        if (runId.isBlank() || text.isBlank() || text.length > 64_000 || requestId.length > 128) return false
        if (runCatching { io.github.mangi.eta.agent.model.AgentSupplementMedia.persistedImages(imagesJson) }.isFailure) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_STEER_RUN)
            msg.data = AgentRuntimeWire.steerBundle(runId, text, requestId, imagesJson)
            serviceMessenger.send(msg)
            true
        }
    }
    fun pauseRun(runId: String) {
        if (runId.isBlank()) return
        withRuntimeMessenger(Unit) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_PAUSE_RUN)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
        }
    }
    fun resumeRun(runId: String) {
        if (runId.isBlank()) return
        withRuntimeMessenger(Unit) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESUME_RUN)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
        }
    }
    fun compactRun(runId: String, keepRecent: Int, compressModelConfig: AgentModelClient.ModelConfig? = null,
        childTaskId: String? = null): Boolean {
        if (runId.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_COMPACT_RUN)
            msg.data = AgentRuntimeWire.compactBundle(runId, keepRecent, compressModelConfig, childTaskId)
            val reply = AtomicReference<Boolean?>(null)
            val latch = CountDownLatch(1)
            if (childTaskId != null) msg.replyTo = Messenger(Handler(Looper.getMainLooper()) { response ->
                if (response.what == AgentRuntimeWire.MSG_COMPACT_RUN) {
                    reply.set(response.data.getBoolean("compact_accepted", false)); latch.countDown()
                }
                true
            })
            serviceMessenger.send(msg)
            if (childTaskId == null) true else latch.await(5, TimeUnit.SECONDS) && reply.get() == true
        }
    }
    /** Invoke off Main: its Messenger reply handler needs the main Looper to deliver the ACK. */
    fun submitQuestionAnswer(conversationId: String, runId: String, questionId: String,
        toolCallId: String, answer: AgentQuestionAnswer): AgentQuestionReceipt {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return AgentQuestionReceipt(false, "QUESTION_MAIN_THREAD", "请在后台提交回答")
        }
        val unavailable = AgentQuestionReceipt(false, "QUESTION_RUNTIME_UNAVAILABLE", "暂时无法连接运行时，请重试")
        val payload = runCatching {
            AgentRuntimeWire.questionAnswerBundle(conversationId, runId, questionId, toolCallId, answer)
        }.getOrElse { return AgentQuestionReceipt(false, "QUESTION_INVALID_ANSWER", "回答格式无效") }
        return withRuntimeMessenger(unavailable) { serviceMessenger ->
            val receipt = AtomicReference<AgentQuestionReceipt?>(null)
            val latch = CountDownLatch(1)
            val reply = Messenger(Handler(Looper.getMainLooper()) { response ->
                if (response.what == AgentRuntimeWire.MSG_QUESTION_ANSWER_RESPONSE &&
                    response.data.getString("question_id") == questionId) {
                    receipt.compareAndSet(null, AgentRuntimeWire.questionReceiptFromBundle(response.data))
                    latch.countDown()
                }
                true
            })
            serviceMessenger.send(Message.obtain(null, AgentRuntimeWire.MSG_QUESTION_ANSWER).apply {
                data = payload
                replyTo = reply
            })
            if (latch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) receipt.get() ?: unavailable
            else AgentQuestionReceipt(false, "QUESTION_ACK_TIMEOUT", "回答受理尚未确认，请查看问题状态后重试")
        }
    }

    fun queryQuestion(conversationId: String, runId: String, questionId: String, toolCallId: String):
        io.github.mangi.eta.agent.question.AgentQuestionSnapshot? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        val payload = runCatching { AgentRuntimeWire.questionQueryBundle(conversationId, runId, questionId, toolCallId) }.getOrNull() ?: return null
        return withRuntimeMessenger<io.github.mangi.eta.agent.question.AgentQuestionSnapshot?>(null) { service ->
            val snapshot = AtomicReference<io.github.mangi.eta.agent.question.AgentQuestionSnapshot?>(null)
            val latch = CountDownLatch(1)
            val reply = Messenger(Handler(Looper.getMainLooper()) { response ->
                if (response.what == AgentRuntimeWire.MSG_QUERY_QUESTION_RESPONSE &&
                    response.data.getString("conversation_id") == conversationId &&
                    AgentRuntimeWire.runIdFromBundle(response.data) == runId &&
                    response.data.getString("question_id") == questionId &&
                    response.data.getString("tool_call_id") == toolCallId) {
                    snapshot.set(AgentRuntimeWire.questionSnapshotFromBundle(response.data)); latch.countDown()
                }
                true
            })
            service.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_QUESTION).apply { data = payload; replyTo = reply })
            if (latch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) snapshot.get() else null
        }
    }

    /** Strict read-only IPC. Main accepts the descriptor; only the caller's IO thread decodes it. */
    fun queryHistory(conversationId: String, runId: String, snapshotId: String): AgentRuntimeSession.HistorySnapshot? {
        if (Looper.myLooper() == Looper.getMainLooper() || snapshotId.isBlank()) return null
        return withRuntimeMessenger<AgentRuntimeSession.HistorySnapshot?>(null) { service ->
            val queryId = java.util.UUID.randomUUID().toString()
            fun release() {
                runCatching { service.send(Message.obtain(null, AgentRuntimeWire.MSG_RELEASE_HISTORY).apply {
                    data = Bundle().apply { putString("history_query_id", queryId) }
                }) }
            }
            val lock = Any()
            var accepting = true
            var received: Bundle? = null
            val latch = CountDownLatch(1)
            val reply = Messenger(Handler(Looper.getMainLooper()) { response ->
                if (response.what == AgentRuntimeWire.MSG_QUERY_HISTORY_RESPONSE) {
                    val data = response.data
                    synchronized(lock) {
                        val matching = AgentRuntimeWire.runIdFromBundle(data) == runId &&
                            data.getString("history_snapshot_id") == snapshotId &&
                            data.getString("conversation_id") == conversationId &&
                            data.getString("history_query_id") == queryId
                        if (accepting && matching && received == null) { received = data; latch.countDown() }
                        else {
                            data.getParcelable(AgentRuntimeWire.KEY_HISTORY_FD, android.os.ParcelFileDescriptor::class.java)?.close()
                            if (matching) release()
                        }
                    }
                }
                true
            })
            try {
                service.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_HISTORY).apply {
                    data = AgentRuntimeWire.ackBundle(runId).apply {
                        putString("conversation_id", conversationId)
                        putString("history_snapshot_id", snapshotId)
                        putString("history_query_id", queryId)
                    }
                    replyTo = reply
                })
                latch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                val data = synchronized(lock) { accepting = false; received.also { received = null } }
                data?.let {
                    try {
                        if (!it.getBoolean("history_available")) null else AgentRuntimeSession.HistorySnapshot(
                            snapshotId, it.getInt("snapshot_round"), AgentRuntimeHistoryTransfer.readSnapshotFromBundle(it),
                        )
                    } finally { it.getParcelable(AgentRuntimeWire.KEY_HISTORY_FD, android.os.ParcelFileDescriptor::class.java)?.close() }
                }
            } finally {
                synchronized(lock) {
                    accepting = false
                    received?.getParcelable(AgentRuntimeWire.KEY_HISTORY_FD, android.os.ParcelFileDescriptor::class.java)?.close()
                    received = null
                }
                release()
            }
        }
    }

    fun ackResult(runId: String): Boolean {
        if (runId.isBlank()) return false
        return withRuntimeMessenger(false) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ACK_RESULT)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            serviceMessenger.send(msg)
            true
        }
    }
    fun drainCompletedRuns(): List<AgentRuntimeWire.CompletedRun> = when (val query = queryCompletedRuns()) {
        is CompletedRunsQuery.Known -> query.runs
        CompletedRunsQuery.Unavailable -> emptyList()
    }
    fun queryCompletedRuns(): CompletedRunsQuery {
        val resultLatch = CountDownLatch(1)
        val resultRef = AtomicReference<List<AgentRuntimeWire.CompletedRun>>(emptyList())
        val clientMessenger = Messenger(DrainHandler { results -> resultRef.set(results); resultLatch.countDown() })
        return withRuntimeMessenger<CompletedRunsQuery>(CompletedRunsQuery.Unavailable) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS)
            msg.replyTo = clientMessenger
            serviceMessenger.send(msg)
            if (resultLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) CompletedRunsQuery.Known(resultRef.get())
            else CompletedRunsQuery.Unavailable
        }
    }
    fun queryActiveRun(): ActiveRunQuery {
        val responseLatch = CountDownLatch(1)
        val runIdsRef = AtomicReference<Set<String>>(emptySet())
        val clientMessenger = Messenger(ActiveRunHandler { runIds -> runIdsRef.set(runIds); responseLatch.countDown() })
        return withRuntimeMessenger<ActiveRunQuery>(ActiveRunQuery.Unavailable) { serviceMessenger ->
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN)
            msg.replyTo = clientMessenger
            serviceMessenger.send(msg)
            if (!responseLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) ActiveRunQuery.Unavailable
            else ActiveRunQuery.Known(runIdsRef.get())
        }
    }
    fun attachRun(runId: String, onReplay: ((List<AgentEvent>) -> Unit)? = null,
        onEvent: (AgentEvent) -> Unit): AttachOutcome {
        if (runId.isBlank()) return AttachOutcome.NotActive
        val terminalLatch = CountDownLatch(1)
        val attachLatch = CountDownLatch(1)
        val attachedRef = AtomicReference<Boolean?>(null)
        val resultRef = AtomicReference<AgentRuntimeWire.RunResult?>()
        val clientMessenger = Messenger(AttachHandler(onReplay, onEvent,
            onAttachResponse = { attached ->
                attachedRef.set(attached); attachLatch.countDown()
                if (!attached) terminalLatch.countDown()
            }, onResult = { result ->
                resultRef.set(result); attachLatch.countDown(); terminalLatch.countDown()
            }).also { it.diagnosticRunId = runId })
        val lease = AgentRuntimeConnection.acquire(context, logger) ?: return AttachOutcome.Unavailable
        val deathRecipient = IBinder.DeathRecipient { attachLatch.countDown(); terminalLatch.countDown() }
        try {
            lease.binder.linkToDeath(deathRecipient, 0)
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN)
            msg.replyTo = clientMessenger
            msg.data = AgentRuntimeWire.ackBundle(runId)
            lease.messenger.send(msg)
            if (!attachLatch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return AttachOutcome.Unavailable
            terminalLatch.await()
            resultRef.get()?.let { return AttachOutcome.Completed(it) }
            return if (attachedRef.get() == false) AttachOutcome.NotActive else AttachOutcome.Unavailable
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return AttachOutcome.Unavailable
        } catch (throwable: Throwable) {
            logger.warn("Agent runtime attach failed: type=${throwable.safeLogType()}")
            return AttachOutcome.Unavailable
        } finally {
            runCatching { lease.binder.unlinkToDeath(deathRecipient, 0) }
            lease.close()
        }
    }

    private fun <T> withRuntimeMessenger(defaultValue: T, block: (Messenger) -> T): T {
        val lease = AgentRuntimeConnection.acquire(context, logger) ?: return defaultValue
        try { return block(lease.messenger) }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt(); return defaultValue }
        catch (throwable: Throwable) {
            logger.warn("Agent runtime service call failed: type=${throwable.safeLogType()}")
            return defaultValue
        } finally { lease.close() }
    }
    private class ClientHandler(private val onEvent: (AgentEvent) -> Unit,
        private val onResult: (AgentRuntimeWire.RunResult) -> Unit,
        private val onRequestIngested: () -> Unit) : Handler(Looper.getMainLooper()) {
        var diagnosticRunId: String? = null
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AgentRuntimeWire.MSG_EVENT -> {
                    val data = msg.data ?: return
                    withRuntimeDiagnosticEvent(diagnosticRunId, replay = false) {
                        measureRuntimeStreamStage("ipc.client.receive") {
                            recordDeliveryTiming(data, live = true)
                            val event = measureRuntimeStreamStage("ipc.client.decode.live") {
                                AgentRuntimeWire.eventFromBundle(data)
                            }
                            bindRuntimeDiagnosticEvent(event)
                            event?.let {
                                withRuntimeDecodedEvent(it) {
                                    measureRuntimeStreamStage("ipc.client.callback.live") { onEvent(it) }
                                }
                            }
                        }
                    }
                }
                AgentRuntimeWire.MSG_RESULT -> {
                    val data = msg.data ?: return
                    val result = runCatching { AgentRuntimeWire.runResultFromBundle(data) }.getOrElse { throwable ->
                        AgentRuntimeWire.RunResult(AgentRuntimeWire.runIdFromBundle(data), false, "",
                            "Agent Runtime 结果解析失败（${throwable.javaClass.simpleName}）")
                    }
                    onResult(result)
                }
                AgentRuntimeWire.MSG_REQUEST_INGESTED -> onRequestIngested()
            }
        }
    }
    private class DrainHandler(private val onResults: (List<AgentRuntimeWire.CompletedRun>) -> Unit) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
                onResults(AgentRuntimeWire.completedRunsFromBundle(msg.data ?: return))
        }
    }
    private class ActiveRunHandler(private val onResponse: (Set<String>) -> Unit) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
                onResponse(AgentRuntimeWire.runIdsFromBundle(msg.data ?: return))
        }
    }
    private class AttachHandler(onReplay: ((List<AgentEvent>) -> Unit)?, onEvent: (AgentEvent) -> Unit,
        onAttachResponse: (Boolean) -> Unit, onResult: (AgentRuntimeWire.RunResult) -> Unit) : Handler(Looper.getMainLooper()) {
        var diagnosticRunId: String? = null
        // Replay callbacks run at attach ACK/result, not while MSG_EVENT buffers replay history.
        // Keep delivery's null-onReplay fallback: invoke the original onEvent once per replay item.
        private val delivery = AgentRuntimeAttachDelivery(
            onReplay = { events ->
                if (onReplay != null) {
                    val attribution = io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
                        .attribution(diagnosticRunId, kind = "replayBatch", replay = true)
                    io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics.withAttribution(attribution) {
                        measureRuntimeStreamStage("ipc.attach.callback.replayBatch") { onReplay(events) }
                    }
                } else {
                    events.forEach { event ->
                        withRuntimeDecodedEvent(event) {
                            measureRuntimeStreamStage("ipc.attach.callback.replay") { onEvent(event) }
                        }
                    }
                }
            },
            onEvent = { event -> withRuntimeDecodedEvent(event) {
                measureRuntimeStreamStage("ipc.attach.callback.live") { onEvent(event) }
            } },
            onAttachResponse = onAttachResponse,
            onResult = onResult,
        )
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                AgentRuntimeWire.MSG_EVENT -> {
                    val data = msg.data ?: return
                    val live = delivery.isLive
                    withRuntimeDiagnosticEvent(diagnosticRunId, replay = !live) {
                        measureRuntimeStreamStage("ipc.attach.receive") {
                            recordDeliveryTiming(data, live = live)
                            val event = measureRuntimeStreamStage(
                                if (live) "ipc.attach.decode.live" else "ipc.attach.decode.replay",
                            ) { AgentRuntimeWire.eventFromBundle(data) }
                            bindRuntimeDiagnosticEvent(event)
                            event?.let(delivery::event)
                        }
                    }
                }
                AgentRuntimeWire.MSG_RESULT -> {
                    val data = msg.data ?: return
                    val result = runCatching { AgentRuntimeWire.runResultFromBundle(data) }.getOrElse { throwable ->
                        AgentRuntimeWire.RunResult(AgentRuntimeWire.runIdFromBundle(data), false, "",
                            "Agent Runtime 结果解析失败（${throwable.javaClass.simpleName}）")
                    }
                    delivery.result(result)
                }
                AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE ->
                    delivery.attachResponse(AgentRuntimeWire.attachRunSucceeded(msg.data ?: return))
            }
        }
    }
    internal companion object {
        fun recordDeliveryTiming(data: android.os.Bundle, live: Boolean) {
            StreamDeliveryTiming.delayNs(data.getLong(StreamDeliveryTiming.KEY, 0L),
                android.os.SystemClock.elapsedRealtimeNanos(), live)?.let {
                io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics.record("ipc.delta.delay", ns = it)
            }
        }
        const val RESPONSE_TIMEOUT_SECONDS = 8L
        const val RESULT_HEARTBEAT_SECONDS = 1L
        const val STOP_RESULT_GRACE_SECONDS = 15L

        /**
         * A run may legitimately take a long time, so only an explicit stop starts the grace
         * clock: an unresponsive runtime after a stop must still return control to the caller.
         */
        fun awaitRunResult(
            resultLatch: CountDownLatch,
            isStopRequested: () -> Boolean,
            heartbeatSeconds: Long = RESULT_HEARTBEAT_SECONDS,
            stopGraceSeconds: Long = STOP_RESULT_GRACE_SECONDS,
        ): Boolean {
            // A running run may legitimately take hours, so block without polling until a stop is
            // requested; only then does the bounded grace clock start.
            while (!isStopRequested()) {
                if (resultLatch.await(heartbeatSeconds, TimeUnit.SECONDS)) return true
            }
            var remainingGraceSeconds = stopGraceSeconds
            while (remainingGraceSeconds > 0) {
                if (resultLatch.await(heartbeatSeconds, TimeUnit.SECONDS)) return true
                remainingGraceSeconds -= heartbeatSeconds
            }
            return false
        }
    }
}
