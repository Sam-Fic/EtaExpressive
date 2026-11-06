package io.github.mangi.eta.agent.live

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.agent.runtime.AgentEvent

/**
 * 思维流：把 run 事件折叠成通知卡正文预览与展开摘要（取自 liyunkkk fork 的
 * AgentExecutionNotificationTracker，适配本仓事件模型）。
 *
 * 与浮层状态（AgentOverlayStatus）的分工：那边是语义阶段（驱动岛短标题与快捷按钮），
 * 这边承载流式文本——思考/输出的末行预览（卡片正文）、320 字尾部（下拉展开卡）、
 * 工具显示名 + 输入预览。工具名不复刻 fork 的硬编码映射，直接走本仓单一数据源
 * [toolDisplayName]（ToolPresentation）。同阶段发射按 1s 节流，阶段切换立即上屏。
 */
internal class AgentRunStreamTracker(
    private val context: Context,
    private val handler: Handler,
    private val onStateChanged: (Snapshot) -> Unit,
) {
    enum class Phase { PREPARING, THINKING, GENERATING, TOOL }

    data class Snapshot(
        val phase: Phase,
        val detail: String,
        val expanded: String?,
        val startedAtElapsedRealtime: Long,
        val showChronometer: Boolean,
    )

    private var currentPhase: Phase? = null
    private var phaseStartedAt = 0L
    private var lastUpdateTime = 0L
    private var pendingUpdate: Runnable? = null
    private val thinkingBuffer = StringBuilder()
    private val generatingBuffer = StringBuilder()

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.RunStarted -> begin(Phase.PREPARING)
            is AgentEvent.RoundStarted ->
                if (currentPhase == null || currentPhase == Phase.PREPARING) begin(Phase.THINKING)
            is AgentEvent.AssistantBlockStart -> when (event.kind) {
                AgentEvent.AssistantBlockKind.THINKING -> {
                    currentPhase = Phase.THINKING
                    thinkingBuffer.clear()
                    phaseStartedAt = SystemClock.elapsedRealtime()
                    emitNow(snapshot(Phase.THINKING))
                }
                AgentEvent.AssistantBlockKind.TEXT -> {
                    currentPhase = Phase.GENERATING
                    generatingBuffer.clear()
                    phaseStartedAt = SystemClock.elapsedRealtime()
                    emitNow(snapshot(Phase.GENERATING))
                }
                else -> {}
            }
            is AgentEvent.AssistantBlockDelta -> when (event.kind) {
                AgentEvent.AssistantBlockKind.THINKING -> {
                    thinkingBuffer.append(event.delta)
                    emitLater(snapshot(Phase.THINKING))
                }
                AgentEvent.AssistantBlockKind.TEXT -> {
                    generatingBuffer.append(event.delta)
                    emitLater(snapshot(Phase.GENERATING))
                }
                else -> {}
            }
            is AgentEvent.ToolStarted -> emitTool(
                name = toolDisplayName(context.resources, event.name),
                command = event.command,
                argsPreview = event.argsPreview,
            )
            is AgentEvent.HostedToolStarted -> emitTool(
                name = toolDisplayName(context.resources, event.name),
                command = null,
                argsPreview = "",
            )
            is AgentEvent.RunFinished, is AgentEvent.RunFailed -> reset()
            else -> {}
        }
    }

    fun reset() {
        cancelPending()
        currentPhase = null
        phaseStartedAt = 0L
        thinkingBuffer.clear()
        generatingBuffer.clear()
    }

    // ── 内部实现 ────────────────────────────────────────────────────────

    private fun begin(phase: Phase) {
        currentPhase = phase
        phaseStartedAt = SystemClock.elapsedRealtime()
        thinkingBuffer.clear()
        generatingBuffer.clear()
        emitNow(snapshot(phase))
    }

    private fun emitTool(name: String, command: String?, argsPreview: String) {
        currentPhase = Phase.TOOL
        phaseStartedAt = SystemClock.elapsedRealtime()
        val expanded = buildString {
            append(context.getString(R.string.island_phase_tool, name))
            val commandText = command?.takeIf { it.isNotBlank() }
            val previewText = argsPreview.takeIf { it.isNotBlank() }
            when {
                commandText != null -> {
                    append("\n$ ")
                    append(commandText.trim())
                }
                previewText != null -> {
                    append("\n")
                    append(previewText.trim())
                }
            }
        }
        emitNow(
            Snapshot(
                phase = Phase.TOOL,
                detail = formatToolPreview(command, argsPreview)
                    .ifBlank { context.getString(R.string.island_phase_tool_generic) },
                expanded = expanded,
                startedAtElapsedRealtime = phaseStartedAt,
                showChronometer = true,
            ),
        )
    }

    private fun snapshot(phase: Phase): Snapshot {
        val detail = when (phase) {
            Phase.THINKING -> extractSingleLinePreview(thinkingBuffer)
                .ifBlank { context.getString(R.string.island_phase_thinking_detail) }
            Phase.GENERATING -> extractSingleLinePreview(generatingBuffer)
                .ifBlank { context.getString(R.string.island_phase_generating_detail) }
            else -> ""
        }
        val expanded = when (phase) {
            Phase.THINKING -> extractTail(thinkingBuffer)
            Phase.GENERATING -> extractTail(generatingBuffer)
            else -> null
        }
        // 输出阶段正在高速增长，计时反而抢视线；思考/工具才显示呼吸计时。
        return Snapshot(phase, detail, expanded, phaseStartedAt, showChronometer = phase != Phase.GENERATING)
    }

    private fun emitNow(state: Snapshot) {
        cancelPending()
        lastUpdateTime = SystemClock.elapsedRealtime()
        onStateChanged(state)
    }

    private fun emitLater(state: Snapshot) {
        val elapsed = SystemClock.elapsedRealtime() - lastUpdateTime
        if (elapsed >= MIN_UPDATE_INTERVAL_MS) {
            cancelPending()
            lastUpdateTime = SystemClock.elapsedRealtime()
            onStateChanged(state)
            return
        }
        if (pendingUpdate != null) return
        val pendingPhase = state.phase
        val runnable = Runnable {
            pendingUpdate = null
            lastUpdateTime = SystemClock.elapsedRealtime()
            // 阶段已切换时丢弃过期快照（切换路径会用 emitNow 覆盖）。
            if (currentPhase == pendingPhase) onStateChanged(state)
        }
        pendingUpdate = runnable
        handler.postDelayed(runnable, MIN_UPDATE_INTERVAL_MS - elapsed)
    }

    private fun cancelPending() {
        pendingUpdate?.let(handler::removeCallbacks)
        pendingUpdate = null
    }

    companion object {
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L
        private const val MAX_SINGLE_LINE_CHARS = 42
        private const val MAX_EXPANDED_CHARS = 320

        /** 取流式缓冲的最后一个非空行，超长尾部截断（与滚动日志的阅读方向一致）。 */
        internal fun extractSingleLinePreview(buffer: CharSequence, maxLength: Int = MAX_SINGLE_LINE_CHARS): String {
            val text = buffer.trim().toString()
            if (text.isEmpty()) return ""
            val lastLine = text.lines().lastOrNull { it.isNotBlank() } ?: text
            val clean = lastLine.replace("\n", " ").replace("\r", " ").trim()
            return if (clean.length > maxLength) "…" + clean.takeLast(maxLength) else clean
        }

        /** 展开卡摘要：保留缓冲尾部 [maxLength] 字符。 */
        internal fun extractTail(buffer: CharSequence, maxLength: Int = MAX_EXPANDED_CHARS): String {
            val text = buffer.trim().toString()
            return if (text.length > maxLength) "…" + text.takeLast(maxLength) else text
        }

        /** 工具输入预览：命令优先，其次参数摘要；压成单行并截断。 */
        internal fun formatToolPreview(command: String?, argsPreview: String): String {
            val raw = if (!command.isNullOrBlank()) command.trim() else argsPreview.trim()
            if (raw.isBlank()) return ""
            val clean = raw.replace("\n", " ").replace("\r", " ").trim()
            return if (clean.length > MAX_SINGLE_LINE_CHARS) clean.take(MAX_SINGLE_LINE_CHARS) + "…" else clean
        }
    }
}
