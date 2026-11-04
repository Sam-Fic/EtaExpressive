package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeInFlightEventEntity
import io.github.mangi.eta.data.db.RuntimeInFlightRunEntity
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 在途 UI run 的进程持久化日志。
 *
 * 它不保存 Provider 配置、API Key、工具调用参数增量或原始工具结果，只保存 UI 已可见的
 * 参数摘要、终端命令与结果摘要。
 */
internal object AgentRunCheckpointStore {
    data class Checkpoint(
        val runId: String,
        val ownerInstanceId: String,
        val handoff: AgentRuntimeWire.EntryHandoff,
        val events: List<AgentEvent>,
        val createdAt: Long,
        val updatedAt: Long,
        val recoveryIncomplete: Boolean = false,
        val skippedEventCount: Int = 0,
    )

    fun start(
        context: Context,
        request: AgentRuntimeWire.RunRequest,
        ownerInstanceId: String = AgentRuntimeProcessIdentity.id,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val handoff = request.handoff ?: return false
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return false
        val runId = request.runId.takeIf(String::isNotBlank) ?: return false
        runBlocking(Dispatchers.IO) {
            val dao = EtaDatabase.get(context.applicationContext).runtimeRunDao()
            dao.replaceInFlightRun(
                RuntimeInFlightRunEntity(
                    runId = runId,
                    ownerInstanceId = ownerInstanceId,
                    handoffId = handoff.id,
                    handoffSource = handoff.source,
                    handoffPayload = handoff.payload,
                    dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            AndroidAgentLogger.info(
                "Runtime checkpoint start: run=$runId active=${dao.inFlightRunHeaders().size}",
            )
        }
        return true
    }

    fun append(
        context: Context,
        runId: String,
        sortIndex: Int,
        event: AgentEvent,
        now: Long = System.currentTimeMillis(),
    ) {
        val diagnose = StreamPerformanceDiagnostics.enabled
        val attribution = if (diagnose) StreamPerformanceDiagnostics.captureAttribution() else null
        try {
            runBlocking(Dispatchers.IO) {
                // Keep DAO acquisition before encoding, as in the original call expression.
                val dao = EtaDatabase.get(context.applicationContext).runtimeRunDao()
                val encoded = StreamPerformanceDiagnostics.withAttribution(attribution) {
                    StreamPerformanceDiagnostics.measure("runtime.checkpoint.encode") {
                        AgentEventJsonCodec.encodeForCheckpoint(
                            event,
                            completeJson = AgentEventJsonCodec.encode(event),
                        )
                    }
                }
                val entity = RuntimeInFlightEventEntity(
                    runId = runId,
                    sortIndex = sortIndex,
                    eventJson = encoded.json,
                )
                val started = if (diagnose) System.nanoTime() else 0L
                try {
                    dao.appendInFlightEvent(
                        event = entity,
                        updatedAt = now,
                        recoveryIncomplete = encoded.degraded,
                    )
                } finally {
                    if (diagnose) {
                        val elapsed = System.nanoTime() - started
                        StreamPerformanceDiagnostics.withAttribution(attribution) {
                            StreamPerformanceDiagnostics.record(
                                "runtime.checkpoint.write",
                                elapsed,
                                encoded.json.toByteArray(Charsets.UTF_8).size.toLong(),
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Checkpoint persistence is best-effort and must not fail the active run.
        }
    }

    /** 返回所有未确认 run；事件按小批次读取，避免 Room @Relation 批量填充 CursorWindow。 */
    fun list(context: Context): List<Checkpoint> = try {
        runBlocking(Dispatchers.IO) {
            val dao = EtaDatabase.get(context.applicationContext).runtimeRunDao()
            dao.inFlightRunHeaders().map { stored ->
                runCatching {
                    val events = buildList {
                        var offset = 0
                        while (true) {
                            val page = dao.inFlightEvents(
                                runId = stored.runId,
                                limit = CHECKPOINT_EVENT_PAGE_SIZE,
                                offset = offset,
                            )
                            if (page.isEmpty()) break
                            addAll(page)
                            offset += page.size
                            if (page.size < CHECKPOINT_EVENT_PAGE_SIZE) break
                        }
                    }
                    var skipped = 0
                    val decoded = events
                        .sortedBy { it.sortIndex }
                        .mapNotNull { row ->
                            AgentEventJsonCodec.decode(row.eventJson).also {
                                if (it == null) skipped++
                            }
                        }
                    val incomplete = stored.recoveryIncomplete || skipped > 0 || events.any {
                        AgentEventJsonCodec.isCheckpointDegraded(it.eventJson)
                    }
                    if (incomplete && !stored.recoveryIncomplete) {
                        dao.markInFlightRecoveryIncomplete(stored.runId)
                    }
                    Checkpoint(
                        runId = stored.runId,
                        ownerInstanceId = stored.ownerInstanceId,
                        handoff = stored.toHandoff(),
                        events = decoded,
                        createdAt = stored.createdAt,
                        updatedAt = stored.updatedAt,
                        recoveryIncomplete = incomplete,
                        skippedEventCount = skipped,
                    )
                }.getOrElse {
                    // A malformed/legacy row must not hide other runs or crash recovery.
                    runCatching { dao.markInFlightRecoveryIncomplete(stored.runId) }
                    Checkpoint(
                        runId = stored.runId,
                        ownerInstanceId = stored.ownerInstanceId,
                        handoff = stored.toHandoff(),
                        events = emptyList(),
                        createdAt = stored.createdAt,
                        updatedAt = stored.updatedAt,
                        recoveryIncomplete = true,
                        skippedEventCount = 0,
                    )
                }
            }
        }.also { checkpoints ->
            AndroidAgentLogger.info(
                "Runtime checkpoint restore: runs=${checkpoints.size} " +
                    "incomplete=${checkpoints.count { it.recoveryIncomplete }}",
            )
        }
    } catch (_: Exception) {
        AndroidAgentLogger.warn("Runtime checkpoint restore failed: runs=0")
        emptyList()
    }

    private fun RuntimeInFlightRunEntity.toHandoff() = AgentRuntimeWire.EntryHandoff(
        id = handoffId,
        source = handoffSource,
        payload = handoffPayload,
        dismissEntrySurfaceOnForegroundOperation = dismissEntrySurface,
    )

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        runBlocking(Dispatchers.IO) {
            val dao = EtaDatabase.get(context.applicationContext).runtimeRunDao()
            dao.deleteInFlightRun(runId)
            AndroidAgentLogger.info(
                "Runtime checkpoint drop: run=$runId active=${dao.inFlightRunHeaders().size}",
            )
        }
    }

    private const val CHECKPOINT_EVENT_PAGE_SIZE = 8

}
