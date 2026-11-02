package io.github.mangi.eta.agent.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskSurfaceModeTest {
    @Test
    fun missingModuleDoesNotSilentlyRewriteBackgroundOrAskToForeground() {
        assertEquals(
            AgentTaskSurfaceMode.BACKGROUND,
            AgentTaskSurfaceMode.resolve(AgentTaskSurfaceMode.BACKGROUND, moduleInstalled = false),
        )
        assertEquals(
            AgentTaskSurfaceMode.ASK,
            AgentTaskSurfaceMode.resolve(AgentTaskSurfaceMode.ASK, moduleInstalled = false),
        )
        assertEquals(
            AgentTaskSurfaceMode.BACKGROUND,
            AgentTaskSurfaceMode.resolve(AgentTaskSurfaceMode.BACKGROUND, moduleInstalled = true),
        )
    }

    @Test
    fun foregroundStaysForegroundWithOrWithoutLegacyModule() {
        assertEquals(
            AgentTaskSurfaceMode.FOREGROUND,
            AgentTaskSurfaceMode.resolve(AgentTaskSurfaceMode.FOREGROUND, moduleInstalled = false),
        )
        assertEquals(
            AgentTaskSurfaceMode.FOREGROUND,
            AgentTaskSurfaceMode.resolve(AgentTaskSurfaceMode.FOREGROUND, moduleInstalled = true),
        )
        assertFalse(AgentTaskSurface.useVirtualDisplay(AgentTaskSurfaceMode.FOREGROUND))
    }

    @Test
    fun backgroundAndAskRefuseInsteadOfUsingForeground() {
        listOf(AgentTaskSurfaceMode.ASK).forEach { mode ->
            val error = assertThrows(VirtualDisplayHandoffNotReadyException::class.java) {
                AgentTaskSurface.useVirtualDisplay(mode)
            }
            assertEquals(VirtualDisplaySession.NOT_READY, error.message)
            assertTrue(error is IllegalStateException)
        }
    }

    @Test
    fun askWaitsForAnswerAndClearsPendingRequest() {
        val shown = mutableListOf<String>()
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<AgentTaskSurfaceMode?> {
                AgentTaskPrompt.await("run-ask", cancelled = { false }, show = { shown += it.id }, pollMillis = 10)
            }
            val deadline = System.currentTimeMillis() + 2_000
            while (AgentTaskPrompt.pending.value == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
            val request = requireNotNull(AgentTaskPrompt.pending.value)
            assertEquals("run-ask", request.runId)
            assertEquals(listOf(request.id), shown)
            AgentTaskPrompt.answer(request.id, AgentTaskSurfaceMode.BACKGROUND)
            assertEquals(AgentTaskSurfaceMode.BACKGROUND, result.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(null, AgentTaskPrompt.pending.value)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun askCancelReturnsNullAndNeverPicksAskItself() {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val cancelled = worker.submit<AgentTaskSurfaceMode?> {
                AgentTaskPrompt.await("run-stop", cancelled = { stop.get() }, pollMillis = 10)
            }
            val deadline = System.currentTimeMillis() + 2_000
            while (AgentTaskPrompt.pending.value == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
            stop.set(true)
            assertEquals(null, cancelled.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(null, AgentTaskPrompt.pending.value)

            val asked = worker.submit<AgentTaskSurfaceMode?> {
                AgentTaskPrompt.await("run-ask-ask", cancelled = { false }, pollMillis = 10)
            }
            val again = System.currentTimeMillis() + 2_000
            while (AgentTaskPrompt.pending.value == null && System.currentTimeMillis() < again) Thread.sleep(5)
            AgentTaskPrompt.answer(requireNotNull(AgentTaskPrompt.pending.value).id, AgentTaskSurfaceMode.ASK)
            assertEquals(null, asked.get(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun askTimeoutReturnsNullAndDequeuesRequest() {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val seen = java.util.concurrent.atomic.AtomicReference<AgentTaskPrompt.Request?>(null)
            val result = worker.submit<AgentTaskSurfaceMode?> {
                AgentTaskPrompt.await(
                    "run-timeout",
                    cancelled = { false },
                    show = { seen.set(it) },
                    pollMillis = 10,
                    timeoutMillis = 150,
                )
            }
            assertEquals(null, result.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("run-timeout", seen.get()?.runId)
            // 超时后请求出队，弹窗据此自行关闭；迟到的回答无效。
            assertEquals(null, AgentTaskPrompt.pending.value)
            AgentTaskPrompt.answer(requireNotNull(seen.get()).id, AgentTaskSurfaceMode.FOREGROUND)
            assertEquals(null, AgentTaskPrompt.pending.value)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun concurrentRequestsQueueAndPendingAdvancesToNextAfterAnswer() {
        val worker = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = worker.submit<AgentTaskSurfaceMode?> {
                AgentTaskPrompt.await("run-first", cancelled = { false }, pollMillis = 10)
            }
            val deadline = System.currentTimeMillis() + 2_000
            while (AgentTaskPrompt.pending.value == null && System.currentTimeMillis() < deadline) Thread.sleep(5)
            val firstRequest = requireNotNull(AgentTaskPrompt.pending.value)
            assertEquals("run-first", firstRequest.runId)

            val second = worker.submit<AgentTaskSurfaceMode?> {
                AgentTaskPrompt.await("run-second", cancelled = { false }, pollMillis = 10)
            }
            // 第二个请求入队后队首仍是第一个。
            Thread.sleep(50)
            assertEquals(firstRequest, AgentTaskPrompt.pending.value)

            AgentTaskPrompt.answer(firstRequest.id, AgentTaskSurfaceMode.FOREGROUND)
            assertEquals(AgentTaskSurfaceMode.FOREGROUND, first.get(2, java.util.concurrent.TimeUnit.SECONDS))
            val secondRequest = requireNotNull(AgentTaskPrompt.pending.value)
            assertEquals("run-second", secondRequest.runId)

            AgentTaskPrompt.answer(secondRequest.id, AgentTaskSurfaceMode.BACKGROUND)
            assertEquals(AgentTaskSurfaceMode.BACKGROUND, second.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(null, AgentTaskPrompt.pending.value)
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun awaitHostHiddenSwallowsInterruptButKeepsFlag() {
        AgentTaskPrompt.hostStarted()
        try {
            Thread.currentThread().interrupt()
            AgentTaskPrompt.awaitHostHidden(timeoutMillis = 2_000)
            assertTrue(Thread.interrupted())
        } finally {
            AgentTaskPrompt.hostStopped()
        }
        assertFalse(AgentTaskPrompt.hostVisible.value)
    }

    @Test
    fun cancelledChoiceIsRememberedAndNeverAsksAgain() {
        val gate = AgentTaskSurfaceChoiceGate()
        val asks = java.util.concurrent.atomic.AtomicInteger(0)
        assertEquals(null, gate.resolve { asks.incrementAndGet(); null })
        assertTrue(gate.cancelled)
        assertEquals(null, gate.resolve { asks.incrementAndGet(); AgentTaskSurfaceMode.FOREGROUND })
        assertEquals(1, asks.get())
    }

    @Test
    fun parallelCallsQueueBehindFirstCancel() {
        val gate = AgentTaskSurfaceChoiceGate()
        val asks = java.util.concurrent.atomic.AtomicInteger(0)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = worker.submit<AgentTaskSurfaceMode?> {
                gate.resolve {
                    asks.incrementAndGet()
                    entered.countDown()
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS)
                    null
                }
            }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val second = worker.submit<AgentTaskSurfaceMode?> {
                gate.resolve { asks.incrementAndGet(); AgentTaskSurfaceMode.BACKGROUND }
            }
            Thread.sleep(50)
            release.countDown()
            assertEquals(null, first.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(null, second.get(2, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, asks.get())
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun chosenSurfaceIsReusedWithoutAskingAgain() {
        val gate = AgentTaskSurfaceChoiceGate()
        val asks = java.util.concurrent.atomic.AtomicInteger(0)
        assertEquals(AgentTaskSurfaceMode.BACKGROUND, gate.resolve { asks.incrementAndGet(); AgentTaskSurfaceMode.BACKGROUND })
        assertEquals(AgentTaskSurfaceMode.BACKGROUND, gate.resolve { asks.incrementAndGet(); null })
        assertFalse(gate.cancelled)
        assertEquals(1, asks.get())
    }

    @Test
    fun onlyTraditionalScreenToolsNeedASurfaceChoice() {
        listOf("launch_app", "observe_screen", "tap")
            .forEach { assertTrue(it, AgentTaskSurface.needsSurfaceChoice(it)) }
        listOf("read_file", "terminal", "browser_use", "search_apps", "inspect_virtual_backend",
            "start_virtual_session", "finish_virtual_session", "keep_virtual_result")
            .forEach { assertFalse(it, AgentTaskSurface.needsSurfaceChoice(it)) }
    }

    @Test
    fun everyModeCanBeSavedWithItsOwnSummary() {
        assertTrue(AgentTaskSurface.allowsPersist(AgentTaskSurfaceMode.FOREGROUND))
        assertTrue(AgentTaskSurface.allowsPersist(AgentTaskSurfaceMode.BACKGROUND))
        assertTrue(AgentTaskSurface.allowsPersist(AgentTaskSurfaceMode.ASK))
        assertEquals(
            AgentTaskSurfaceMode.FOREGROUND.labelRes,
            AgentTaskSurface.settingsSummaryRes(AgentTaskSurfaceMode.FOREGROUND),
        )
        assertEquals(
            io.github.mangi.eta.R.string.agent_task_surface_background_summary,
            AgentTaskSurface.settingsSummaryRes(AgentTaskSurfaceMode.BACKGROUND),
        )
        assertEquals(
            io.github.mangi.eta.R.string.agent_task_surface_ask_summary,
            AgentTaskSurface.settingsSummaryRes(AgentTaskSurfaceMode.ASK),
        )
    }

    @Test
    fun selfAppPromptIsScopedInEveryModeWithoutSelectingOrdinaryForeground() {
        AgentTaskSurfaceMode.entries.forEach { mode ->
            listOf(true, false).forEach { installed ->
                val text = AgentTaskSurface.handoffPromptClause(installed, mode)
                assertTrue(text.contains("io.github.mangi.eta"))
                assertTrue(text.contains("不因ASK询问"))
                assertTrue(text.contains("不把整轮改成前台"))
                assertTrue(text.contains("task_surface_scope=self_app"))
                assertTrue(text.contains("已有副屏仍按原后台生命周期收尾"))
                assertFalse(text.contains("表示之后都在主屏直接操作"))
            }
        }
    }

    @Test
    fun backgroundPromptRequiresVerifiedExplicitFinish() {
        assertTrue(AgentTaskSurface.useVirtualDisplay(AgentTaskSurfaceMode.BACKGROUND))
        val text=AgentTaskSurface.handoffPromptClause(true,AgentTaskSurfaceMode.BACKGROUND)
        assertTrue(text.contains("keep_virtual_result"))
        assertTrue(text.contains("finish_virtual_session"))
        assertTrue(text.contains("失败保留副屏"))
        assertTrue(text.contains("不得回退主屏"))
        assertFalse(text.contains("进程退出后自动关闭"))
        val ask = AgentTaskSurface.handoffPromptClause(true, AgentTaskSurfaceMode.ASK)
        assertTrue(ask.contains("task_surface=foreground"))
        assertTrue(ask.contains("task_surface=background"))
        assertTrue(ask.contains("TASK_SURFACE_CANCELLED"))
        assertTrue(ask.contains("finish_virtual_session"))
        assertFalse(ask.contains("尚未支持"))
    }

    @Test
    fun backgroundAndAskBlockScreenGuiButForegroundAndOffscreenStayOpen() {
        val blocked = listOf(
            "press_key",
            "scroll",
            "scroll_element",
            "input_text",
            "replace_text",
            "clear_text",
            "paste_text",
            "tap",
            "swipe",
            "observe_screen",
            "wait",
            "wait_for_text",
            "wait_for_package",
            "open_system_panel",
            "launch_app",
            "open_uri",
            "set_alarm",
            "set_timer",
        )
        val open = listOf(
            "inspect_virtual_backend",
            "read_file",
            "browser_use",
            "search_apps",
            "keep_virtual_result",
        )
        listOf(AgentTaskSurfaceMode.BACKGROUND, AgentTaskSurfaceMode.ASK).forEach { mode ->
            blocked.forEach { tool ->
                assertTrue("$tool@$mode", AgentTaskSurface.blocksGuiTool(tool, mode))
            }
            open.forEach { tool ->
                assertFalse("$tool@$mode", AgentTaskSurface.blocksGuiTool(tool, mode))
            }
        }
        blocked.forEach { tool ->
            assertFalse(tool, AgentTaskSurface.blocksGuiTool(tool, AgentTaskSurfaceMode.FOREGROUND))
        }
        open.forEach { tool ->
            assertFalse(tool, AgentTaskSurface.blocksGuiTool(tool, AgentTaskSurfaceMode.FOREGROUND))
        }
    }

    @Test
    fun nonGuiToolsSkipSurfaceReadAndGuiReadErrorsFailClosed() {
        assertFalse(AgentTaskSurface.blocksGuiTool("read_file") { error("prefs") })
        assertFalse(AgentTaskSurface.blocksGuiTool("inspect_virtual_backend") { error("prefs") })
        assertFalse(AgentTaskSurface.blocksGuiTool("browser_use") { error("prefs") })
        assertTrue(AgentTaskSurface.blocksGuiTool("press_key") { error("prefs") })
        assertTrue(AgentTaskSurface.blocksGuiTool("scroll") { error("prefs") })
        assertTrue(AgentTaskSurface.blocksGuiTool("input_text") { error("prefs") })
        assertFalse(AgentTaskSurface.blocksGuiTool("press_key") { AgentTaskSurfaceMode.FOREGROUND })
        assertFalse(AgentTaskSurface.blocksGuiTool("read_file"))
        assertFalse(AgentTaskSurface.blocksGuiTool("inspect_virtual_backend"))
        assertFalse(AgentTaskSurface.blocksGuiTool("browser_use"))
    }
}
