package io.github.mangi.eta.agent.runtime

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 外部入口（小布、超级小爱、电源键）连点时会一次性打进多个 IPC 请求，而 Runtime 过去对
 * 每个请求直接 `thread(...)`：线程数没有上限，冷启动被连续戳时更容易堆出大量线程。
 *
 * 这里给「请求解码/解码后的贴图与配置解析」这类短任务一个固定的有界池：
 * - 线程数固定，队列有界；队列满时 [rejectHandler] 立刻拿到拒绝，由调用方回明确的错误码，
 *   而不是无限排队后默默超时；
 * - 任务体本身已经会 `mainHandler.post` 回主线程，所以池内不涉及 UI 线程约束；
 * - 与 `pendingStartRequests` 的「同 runId 替换」语义兼容：替换发生在入队之前，
 *   被替换的旧请求由 [onReplaced] 就地关闭，不会再占一个池槽位。
 */
internal class AgentRequestExecutor(
    threads: Int = DEFAULT_THREADS,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    name: String = "agent-runtime-request",
) {
    class RejectedExecutionException(message: String) : IllegalStateException(message)

    private val counter = AtomicInteger()

    private val pool = ThreadPoolExecutor(
        threads,
        threads,
        0L,
        TimeUnit.MILLISECONDS,
        // 容量 0 用 SynchronousQueue：只做直接交接，没有 worker 可用就立即拒绝。
        if (queueCapacity <= 0) java.util.concurrent.SynchronousQueue() else LinkedBlockingQueue(queueCapacity),
        ThreadFactory { runnable ->
            Thread(runnable, "$name-${counter.incrementAndGet()}").apply { isDaemon = true }
        },
    )

    /**
     * 入队；饱和时同步回调 [onRejected]（由调用方关闭它持有的描述符并回明确错误），
     * 绝不让调用方无限等待。[name] 只用于线程命名。
     */
    fun execute(name: String, onRejected: () -> Unit = {}, task: () -> Unit) {
        try {
            pool.execute { task() }
        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            onRejected()
        }
    }

    /** 只用于关闭：不再接收新任务，但放完已入队的请求。 */
    fun shutdown() {
        pool.shutdown()
    }

    val queued: Int
        get() = pool.queue.size

    companion object {
        const val DEFAULT_THREADS = 3
        const val DEFAULT_QUEUE_CAPACITY = 16
    }
}
