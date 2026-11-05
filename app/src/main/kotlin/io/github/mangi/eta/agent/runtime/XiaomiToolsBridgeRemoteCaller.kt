package io.github.mangi.eta.agent.runtime

import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.agent.xiaomi.XiaomiToolsBridgeProtocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Agent Runtime Service 持有的反向 Messenger 调用器。 */
internal class XiaomiToolsBridgeRemoteCaller(
    private val responseMessenger: Messenger,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) : AutoCloseable {
    private data class PendingCall(
        val latch: CountDownLatch = CountDownLatch(1),
        val result: AtomicReference<XiaomiToolsBridgeProtocol.CallResult?> = AtomicReference(),
    )

    private val closed = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<String, PendingCall>()

    fun call(
        target: Messenger?,
        request: XiaomiToolsBridgeProtocol.CallRequest,
    ): XiaomiToolsBridgeProtocol.CallResult {
        if (closed.get() || target == null) return unavailable(request.callId)
        val call = PendingCall()
        if (pending.putIfAbsent(request.callId, call) != null) {
            return XiaomiToolsBridgeProtocol.CallResult(
                callId = request.callId,
                status = XiaomiToolsBridgeProtocol.Status.INVALID_REQUEST,
                error = "ToolsBridge callId 重复",
            )
        }
        try {
            val message = Message.obtain(null, AgentRuntimeWire.MSG_XIAOMI_TOOLS_BRIDGE_CALL)
            message.replyTo = responseMessenger
            message.data = XiaomiToolsBridgeProtocol.callRequestBundle(request)
            target.send(message)
            if (!call.latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                return XiaomiToolsBridgeProtocol.CallResult(
                    callId = request.callId,
                    status = XiaomiToolsBridgeProtocol.Status.TIMEOUT,
                    error = "超级小爱 ToolsBridge 执行超时",
                )
            }
            return call.result.get() ?: unavailable(request.callId)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return XiaomiToolsBridgeProtocol.CallResult(
                callId = request.callId,
                status = XiaomiToolsBridgeProtocol.Status.UNAVAILABLE,
                error = "ToolsBridge 等待被中断",
            )
        } catch (_: Exception) {
            return unavailable(request.callId)
        } finally {
            pending.remove(request.callId, call)
        }
    }

    fun accept(result: XiaomiToolsBridgeProtocol.CallResult): Boolean {
        val call = pending[result.callId] ?: return false
        if (!call.result.compareAndSet(null, result)) return false
        call.latch.countDown()
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending.forEach { (callId, call) ->
            call.result.compareAndSet(null, unavailable(callId))
            call.latch.countDown()
        }
        pending.clear()
    }

    private fun unavailable(callId: String) = XiaomiToolsBridgeProtocol.CallResult(
        callId = callId,
        status = XiaomiToolsBridgeProtocol.Status.UNAVAILABLE,
        error = "超级小爱 ToolsBridge 连接不可用",
    )

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 45L
    }
}
