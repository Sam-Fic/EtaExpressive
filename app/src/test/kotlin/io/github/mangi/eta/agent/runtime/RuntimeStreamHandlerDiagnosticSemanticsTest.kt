package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.Handler
import android.os.Message
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Direct Handler tests: callback semantics with the UI-session gate off, not Binder/perf tests. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RuntimeStreamHandlerDiagnosticSemanticsTest {
    @Test fun disabledAdapterReturnsOnceAndPropagatesTheOriginalFailure() {
        assertFalse(StreamPerformanceDiagnostics.enabled)
        var calls = 0
        val expected = Any()
        assertSame(expected, measureRuntimeStreamStage("ipc.client.callback.live") { calls++; expected })
        assertEquals(1, calls)
        val failure = IllegalStateException("test failure")
        assertFailure(failure) {
            measureRuntimeStreamStage("ipc.client.callback.live") { calls++; throw failure }
        }
        assertEquals(2, calls)
    }

    @Test fun clientInvokesDecodedCallbackOnceAndIgnoresUnknownEvents() {
        assertFalse(StreamPerformanceDiagnostics.enabled)
        val events = mutableListOf<AgentEvent>()
        val handler = clientHandler(events::add)
        val event = round(1)
        handler.handleMessage(eventMessage(event))
        handler.handleMessage(Message.obtain().apply { what = AgentRuntimeWire.MSG_EVENT })
        assertEquals(listOf(event), events)
    }

    @Test fun clientCallbackFailureIsNotSwallowedRetriedOrWrapped() {
        val failure = IllegalStateException("client callback")
        var calls = 0
        val handler = clientHandler { calls++; throw failure }
        assertFailure(failure) { handler.handleMessage(eventMessage(round(1))) }
        assertEquals(1, calls)
    }

    @Test fun attachStillBuffersThenPublishesOneReplayBatchBeforeLiveCallback() {
        val calls = mutableListOf<String>()
        val replay = mutableListOf<List<AgentEvent>>()
        val handler = attachHandler(
            onReplay = { replay += it; calls += "replay" },
            onEvent = { calls += "event-${(it as AgentEvent.RoundStarted).round}" },
            onAttach = { calls += "attached-$it" },
        )
        handler.handleMessage(eventMessage(round(1)))
        handler.handleMessage(eventMessage(round(2)))
        assertEquals(emptyList<String>(), calls)
        handler.handleMessage(attachMessage())
        handler.handleMessage(attachMessage())
        handler.handleMessage(eventMessage(round(3)))
        assertEquals(listOf(listOf(round(1), round(2))), replay)
        assertEquals(listOf("replay", "attached-true", "event-3"), calls)
    }

    @Test fun attachLegacyReplayAndLiveFailuresPropagateOnceWithoutChangingAckOrder() {
        val failure = IllegalStateException("attach callback")
        var calls = 0
        var acks = 0
        val handler = attachHandler(onReplay = null,
            onEvent = { calls++; throw failure }, onAttach = { acks++ })
        handler.handleMessage(eventMessage(round(1)))
        assertEquals(0, calls)
        assertFailure(failure) { handler.handleMessage(attachMessage()) }
        assertEquals(1, calls)
        assertEquals(0, acks)
        // Delivery was already LIVE before the replay callback, as before instrumentation.
        assertFailure(failure) { handler.handleMessage(eventMessage(round(2))) }
        assertEquals(2, calls)
    }

    @Test fun attachReplayBatchFailurePropagatesOnceWithoutInvokingAckCallback() {
        val failure = IllegalStateException("replay callback")
        var replays = 0
        var acks = 0
        val handler = attachHandler(onReplay = { replays++; throw failure },
            onEvent = { fail("Unexpected live event") }, onAttach = { acks++ })
        handler.handleMessage(eventMessage(round(1)))
        assertFailure(failure) { handler.handleMessage(attachMessage()) }
        assertEquals(1, replays)
        assertEquals(0, acks)
    }

    // Keep production Handler visibility/API unchanged; invoke the original handlers directly.
    // ClientHandler 构造签名随入口工具执行/小米桥接入扩到 8 参；本测试只驱动 MSG_EVENT，
    // 其余协作者传空实现（logger 用动态代理防 NPE）。
    private fun clientHandler(onEvent: (AgentEvent) -> Unit): Handler = newHandler(
        "ClientHandler",
        arrayOf(
            Function1::class.java,
            Function1::class.java,
            Function0::class.java,
            io.github.mangi.eta.agent.model.AgentModelClient.ToolExecutor::class.java,
            java.util.concurrent.ExecutorService::class.java,
            AgentLogger::class.java,
            io.github.mangi.eta.agent.xiaomi.XiaomiToolsBridgeEndpoint::class.java,
            Function1::class.java,
        ),
        onEvent, { _: AgentRuntimeWire.RunResult -> }, { },
        null, null, noopLogger(), { _: android.os.Bundle -> },
    )

    private fun noopLogger(): AgentLogger = java.lang.reflect.Proxy.newProxyInstance(
        AgentLogger::class.java.classLoader,
        arrayOf(AgentLogger::class.java),
    ) { _, _, _ -> null } as AgentLogger

    private fun attachHandler(onReplay: ((List<AgentEvent>) -> Unit)?, onEvent: (AgentEvent) -> Unit,
        onAttach: (Boolean) -> Unit): Handler = newHandler(
        "AttachHandler", arrayOf(Function1::class.java, Function1::class.java,
            Function1::class.java, Function1::class.java),
        onReplay, onEvent, onAttach, { _: AgentRuntimeWire.RunResult -> },
    )

    private fun newHandler(name: String, types: Array<Class<*>>, vararg args: Any?): Handler =
        Class.forName("io.github.mangi.eta.agent.runtime.AgentRuntimeClient\$$name")
            .getDeclaredConstructor(*types).apply { isAccessible = true }.newInstance(*args) as Handler

    private fun eventMessage(event: AgentEvent) = Message.obtain().apply {
        what = AgentRuntimeWire.MSG_EVENT
        data = AgentRuntimeWire.eventToBundle(event)
    }

    private fun attachMessage() = Message.obtain().apply {
        what = AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE
        data = AgentRuntimeWire.attachRunResponseBundle("test-run", true)
    }

    private fun round(index: Int) = AgentEvent.RoundStarted(index, index)

    private fun assertFailure(expected: Throwable, block: () -> Unit) {
        try { block(); fail("Expected callback failure") }
        catch (actual: Throwable) { assertSame(expected, actual) }
    }
}
