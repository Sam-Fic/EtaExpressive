package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 外部入口（小布、超级小爱、电源键）连点会一次性打进多个 IPC 请求。Runtime 过去对每个请求
 * 直接建线程，线程数没有上限。这里钉住两条：并发受限，且饱和时立刻回拒绝而不是无限排队。
 */
class AgentRequestExecutorTest {
    @Test fun boundsConcurrentWorkersAndRejectsWhenSaturated() {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val rejected = AtomicInteger()
        val executor = AgentRequestExecutor(threads = 2, queueCapacity = 1)

        // 占用两个 worker，并让它们停在 release 上。
        repeat(2) {
            executor.execute(name = "test") {
                val now = running.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, now) }
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                running.decrementAndGet()
            }
        }
        assertTrue("两个 worker 必须已经启动", started.await(5, TimeUnit.SECONDS))

        // 队列容量 1：第三个进入队列，第四个必须被拒绝。
        executor.execute(name = "test") { }
        executor.execute(name = "test", onRejected = { rejected.incrementAndGet() }) { }

        assertEquals(1, rejected.get())
        assertTrue("并发不能超过配置的线程数", peak.get() <= 2)
        release.countDown()
        executor.shutdown()
    }

    @Test fun rejectedWorkNeverRunsAndIsReportedSynchronously() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val ran = AtomicInteger()
        val executor = AgentRequestExecutor(threads = 1, queueCapacity = 0)

        executor.execute(name = "test") {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        var rejectedSynchronously = false
        executor.execute(name = "test", onRejected = { rejectedSynchronously = true }) {
            ran.incrementAndGet()
        }
        assertTrue("饱和必须就地回调，不能排队", rejectedSynchronously)
        assertEquals(0, ran.get())

        release.countDown()
        executor.shutdown()
    }
}
