package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeArchiveEventEntity
import io.github.mangi.eta.data.db.RuntimeArchiveRunEntity
import io.github.mangi.eta.data.db.RuntimeRunDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

/**
 * Process-persistent archive for externally initiated runs that should later be
 * mirrored into the module's own chat history.
 *
 * Unlike [AgentRuntimeResultStore], entries here are not an entry-adapter retry
 * queue. They preserve the event trace so the first-party UI can reconstruct
 * thinking and tool activity that third-party assistant surfaces cannot show.
 */
internal object AgentRunArchiveStore {
    private const val MAX_ARCHIVED = 32
    private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L

    /**
     * 归档落库通知（进程内总线，值 = runId）。
     *
     * 归档只在 run 到终态时写一次，所以这一条通告同时就是「该 run 结束了」的信号。主程序
     * 正开着时靠它立刻重跑恢复，把思考 / 工具卡补进对话；只等 `ON_RESUME` 会漏掉「用户
     * 已经在主程序里、run 在浮窗里跑完」这一段——那一刻没有生命周期事件，归档落库后也
     * 没人重跑导入，对话会一直空到下一次切前台。
     *
     * 只发 id，内容由订阅方自己读库：总线不持有事件流，避免把大对象挂在进程常驻单例上。
     * Runtime 与 UI 同在主进程（清单里 [AgentRuntimeService] 未声明 `android:process`），
     * 进程内 SharedFlow 即可；跨进程场景由 `ON_RESUME` 兜底。
     */
    private val _archived = MutableSharedFlow<String>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val archived: SharedFlow<String> = _archived

    data class ArchivedRun(
        val handoff: AgentRuntimeWire.EntryHandoff,
        val events: List<AgentEvent>,
        val result: AgentRuntimeWire.RunResult,
        val createdAt: Long,
        val userImagePreviews: List<String> = emptyList(),
    )

    fun add(context: Context, run: ArchivedRun) {
        val appContext = context.applicationContext
        runBlocking(Dispatchers.IO) {
            val dao = EtaDatabase.get(appContext).runtimeRunDao()
            val compacted = run.copy(events = compactEvents(run.events))
            val archiveRunId = compacted.archiveRunId
            val before = dao.archivedRunHeaders().size
            dao.replaceArchivedRun(
                run = compacted.toEntity(archiveRunId),
                events = compacted.toEventEntities(archiveRunId),
            )
            val retained = prune(dao)
            // 只打计数：能看出归档是否被 32 条 / 7 天上限挤出，正文不进日志。
            AndroidAgentLogger.info(
                "Runtime archive add: run=$archiveRunId runs=${retained.size} " +
                    "pruned=${(before + 1 - retained.size).coerceAtLeast(0)}",
            )
            // 事务返回之后再发：订阅方收到就会读库，此刻必须已经能读到这条归档。
            if (!_archived.tryEmit(archiveRunId)) {
                AndroidAgentLogger.warn("Runtime archive notice dropped: run=$archiveRunId")
            }
        }
    }

    fun list(context: Context): List<ArchivedRun> {
        val appContext = context.applicationContext
        return runBlocking(Dispatchers.IO) {
            val dao = EtaDatabase.get(appContext).runtimeRunDao()
            val retained = prune(dao)
            AndroidAgentLogger.info("Runtime archive restore: runs=${retained.size}")
            retained.mapNotNull { run ->
                runCatching {
                    run.toDomain(loadArchiveEvents(dao, run.archiveRunId))
                }.getOrNull()
            }
        }
    }

    fun remove(context: Context, runId: String) {
        if (runId.isBlank()) return
        val appContext = context.applicationContext
        runBlocking(Dispatchers.IO) {
            val dao = EtaDatabase.get(appContext).runtimeRunDao()
            dao.deleteArchivedRun(runId)
            AndroidAgentLogger.info(
                "Runtime archive drop: run=$runId runs=${dao.archivedRunHeaders().size}",
            )
        }
    }

    private suspend fun prune(dao: RuntimeRunDao): List<RuntimeArchiveRunEntity> {
        val now = System.currentTimeMillis()
        val headers = dao.archivedRunHeaders()
        val retained = headers
            .filter { now - it.createdAt <= MAX_AGE_MS }
            .sortedBy { it.createdAt }
            .takeLast(MAX_ARCHIVED)
        val retainedIds = retained.mapTo(mutableSetOf()) { it.archiveRunId }
        headers
            .asSequence()
            .filterNot { it.archiveRunId in retainedIds }
            .forEach { run ->
                dao.deleteArchivedEvents(run.archiveRunId)
                dao.deleteArchivedRunByArchiveId(run.archiveRunId)
            }
        return retained
    }

    private val ArchivedRun.archiveRunId: String
        get() = result.runId.ifBlank { handoff.id }

    private fun ArchivedRun.toEntity(archiveRunId: String): RuntimeArchiveRunEntity =
        RuntimeArchiveRunEntity(
            archiveRunId = archiveRunId,
            runId = result.runId,
            handoffId = handoff.id,
            handoffSource = handoff.source,
            handoffPayload = handoff.payload,
            dismissEntrySurface = handoff.dismissEntrySurfaceOnForegroundOperation,
            ok = result.ok,
            content = result.content,
            error = result.error,
            reasoningContent = result.reasoningContent,
            transcriptJson = AgentConversationCodec.encodeTranscriptForStorage(result.transcript),
            userImagePreviewsJson = JSONArray(userImagePreviews).toString(),
            createdAt = createdAt,
            virtualDeliveryCompleted = result.virtualDeliveryCompleted,
        )

    private fun ArchivedRun.toEventEntities(archiveRunId: String): List<RuntimeArchiveEventEntity> =
        events.mapIndexed { index, event ->
            RuntimeArchiveEventEntity(
                archiveRunId = archiveRunId,
                sortIndex = index,
                eventJson = AgentEventJsonCodec.encodeForCheckpoint(event.withoutSensitiveToolDetails()).json,
            )
        }

    /**
     * 归档事件会被长期保留，并随「会话分析 JSON」导出；敏感工具的参数 / 结果 / 预览
     * 不能借这条路径绕过消息落库时的剔除。这里复用同一份 [AgentSensitiveToolPolicy]，
     * 不另造名单；命中后只清空明细，保留调用事实、状态与计数。
     */
    private fun AgentEvent.withoutSensitiveToolDetails(): AgentEvent = when (this) {
        is AgentEvent.ToolStarted ->
            if (AgentSensitiveToolPolicy.isSensitive(name)) copy(argumentsDetail = null) else this
        is AgentEvent.ToolFinished ->
            if (AgentSensitiveToolPolicy.isSensitive(name)) {
                copy(resultDetail = null, mediaPreviewsJson = "[]")
            } else {
                this
            }
        else -> this
    }

    private fun RuntimeArchiveRunEntity.toDomain(
        events: List<RuntimeArchiveEventEntity>,
    ): ArchivedRun? =
        runCatching {
            ArchivedRun(
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = handoffId,
                    source = handoffSource,
                    payload = handoffPayload,
                    dismissEntrySurfaceOnForegroundOperation = dismissEntrySurface,
                ),
                result = AgentRuntimeWire.RunResult(
                    runId = runId.ifBlank { archiveRunId },
                    ok = ok,
                    content = content,
                    error = error,
                    reasoningContent = reasoningContent,
                    virtualDeliveryCompleted = virtualDeliveryCompleted,
                    transcript = AgentConversationCodec.decodeTranscript(transcriptJson).ifEmpty {
                        if (!ok || content.isBlank()) return@ifEmpty emptyList()
                        listOf(
                            AgentModelClient.ConversationMessage(
                                role = "assistant",
                                content = content,
                                reasoningContent = reasoningContent,
                            )
                        )
                    },
                ),
                createdAt = createdAt,
                userImagePreviews = JSONArray(userImagePreviewsJson).let { previews ->
                    buildList {
                        for (index in 0 until previews.length()) {
                            previews.optString(index)
                                .takeIf { it.startsWith("data:image/") }
                                ?.let(::add)
                        }
                    }
                },
                events = events
                    .sortedBy { it.sortIndex }
                    .mapNotNull { event -> AgentEventJsonCodec.decode(event.eventJson) },
            )
        }.getOrNull()

    private suspend fun loadArchiveEvents(
        dao: RuntimeRunDao,
        archiveRunId: String,
    ): List<RuntimeArchiveEventEntity> = buildList {
        var offset = 0
        while (true) {
            val page = dao.archivedEvents(
                archiveRunId = archiveRunId,
                limit = ARCHIVE_EVENT_PAGE_SIZE,
                offset = offset,
            )
            if (page.isEmpty()) break
            addAll(page)
            offset += page.size
            if (page.size < ARCHIVE_EVENT_PAGE_SIZE) break
        }
    }

    private const val ARCHIVE_EVENT_PAGE_SIZE = 8

    private fun compactEvents(events: List<AgentEvent>): List<AgentEvent> {
        val compacted = mutableListOf<AgentEvent>()
        events.forEach { event ->
            if (
                event is AgentEvent.AssistantBlockDelta &&
                event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL
            ) {
                return@forEach
            }
            val previous = compacted.lastOrNull()
            val merged = when {
                previous is AgentEvent.AssistantBlockDelta &&
                    event is AgentEvent.AssistantBlockDelta &&
                    previous.round == event.round -> {
                    if (previous.kind == event.kind && previous.index == event.index) {
                        previous.copy(
                            delta = previous.delta + event.delta,
                            deltaChars = previous.deltaChars + event.deltaChars,
                        )
                    } else {
                        null
                    }
                }

                else -> null
            }
            if (merged != null) {
                compacted[compacted.lastIndex] = merged
            } else {
                compacted += event
            }
        }
        return compacted
    }
}
