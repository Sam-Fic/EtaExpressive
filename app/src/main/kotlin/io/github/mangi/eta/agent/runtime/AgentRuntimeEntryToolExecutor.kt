package io.github.mangi.eta.agent.runtime

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.XiaomiUiAgentToolCatalog
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

/** 在 Runtime 进程中把受控工具调用转发回本次运行的入口进程。 */
internal class AgentRuntimeEntryToolExecutor(
    private val target: Messenger,
    private val allowedTools: Set<String>,
    private val directToolsEnabled: () -> Boolean,
    private val sensitiveReadToolsEnabled: () -> Boolean,
    private val sensitiveActionToolsEnabled: () -> Boolean,
    private val logger: AgentLogger,
    private val beforeToolExecution: (String) -> ToolExecutionDecision = {
        ToolExecutionDecision.Allow
    },
) : AgentModelClient.ToolExecutor, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val nextCallId = AtomicLong(1L)
    private val pendingCalls = CopyOnWriteArraySet<PendingCall>()

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (closed.get()) return error("ENTRY_TOOL_BRIDGE_CLOSED", "入口工具桥已关闭")
        if (toolCall.name !in allowedTools) {
            return error("ENTRY_TOOL_NOT_AVAILABLE", "当前入口没有提供工具：${toolCall.name}")
        }
        val arguments = runCatching {
            JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
        }.getOrElse {
            return error("INVALID_ARGUMENT", "入口工具参数不是有效的 JSON 对象")
        }
        XiaomiUiAgentToolCatalog.validate(toolCall.name, arguments)?.let { issue ->
            return error(issue.code, issue.message)
        }
        XiaomiUiAgentToolCatalog.authorizationIssue(
            name = toolCall.name,
            arguments = arguments,
            directTools = directToolsEnabled(),
            sensitiveReadTools = sensitiveReadToolsEnabled(),
            sensitiveActionTools = sensitiveActionToolsEnabled(),
        )?.let { issue ->
            return error(issue.code, issue.message)
        }
        when (val decision = beforeToolExecution(toolCall.name)) {
            ToolExecutionDecision.Allow -> Unit
            is ToolExecutionDecision.Reject -> return error(decision.code, decision.message)
        }

        val callId = "entry-${nextCallId.getAndIncrement()}"
        val pending = PendingCall()
        pendingCalls += pending
        val responseMessenger = Messenger(
            object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(message: Message) {
                    if (message.what != AgentRuntimeWire.MSG_ENTRY_TOOL_RESULT) return
                    val (resultCallId, result) = runCatching {
                        AgentRuntimeWire.entryToolResultFromBundle(message.data ?: return)
                    }.getOrElse { throwable ->
                        logger.warn(
                            "Entry tool result rejected: type=${throwable.safeLogType()}",
                        )
                        return
                    }
                    if (resultCallId != callId) return
                    pending.result.compareAndSet(null, result)
                    pending.latch.countDown()
                }
            }
        )

        return try {
            val request = Message.obtain(null, AgentRuntimeWire.MSG_ENTRY_TOOL_REQUEST).apply {
                replyTo = responseMessenger
                data = AgentRuntimeWire.entryToolCallToBundle(
                    AgentRuntimeWire.EntryToolCall(
                        callId = callId,
                        name = toolCall.name,
                        argumentsJson = arguments.toString(),
                    )
                )
            }
            target.send(request)
            if (!pending.latch.await(ENTRY_TOOL_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                error("ENTRY_TOOL_TIMEOUT", "小米 UIAgent 工具执行超时")
            } else if (closed.get()) {
                error("ENTRY_TOOL_CANCELLED", "入口工具调用已取消")
            } else {
                val result = pending.result.get()
                    ?: error("ENTRY_TOOL_NO_RESULT", "入口进程没有返回工具结果")
                if (
                    result.sensitive ||
                    XiaomiUiAgentToolCatalog.isSensitiveCall(toolCall.name, arguments)
                ) {
                    result.copy(sensitive = true)
                } else {
                    result
                }
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            error("ENTRY_TOOL_CANCELLED", "入口工具等待被中断")
        } catch (throwable: Throwable) {
            logger.warn("Entry tool request failed: type=${throwable.safeLogType()}")
            error("ENTRY_TOOL_BRIDGE_ERROR", "入口工具调用失败（${throwable.safeLogType()}）")
        } finally {
            pendingCalls -= pending
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingCalls.forEach { it.latch.countDown() }
        pendingCalls.clear()
    }

    private fun error(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )

    private class PendingCall(
        val latch: CountDownLatch = CountDownLatch(1),
        val result: AtomicReference<AgentModelClient.ToolResult?> = AtomicReference(),
    )

    private companion object {
        const val ENTRY_TOOL_TIMEOUT_MINUTES = 6L
    }
}
