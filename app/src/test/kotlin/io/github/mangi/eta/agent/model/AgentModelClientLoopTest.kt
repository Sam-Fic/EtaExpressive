package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelClientLoopTest {
    @Test fun historyBoundaryIsPublishedBeforeEventsAndOnlyAfterCompleteToolBatch() {
        val snapshots = mutableListOf<List<AgentModelClient.ConversationMessage>>()
        val provider = ScriptedProvider(
            assistant(content = "doing work", finishReason = "tool_calls", toolCalls = listOf(
                toolCall("one", "get_current_context", "{}"), toolCall("two", "get_current_context", "{}"))),
            assistant(content = "done", finishReason = "stop"))
        var finished = 0
        AgentModelClient.complete(config = modelConfig(), prompt = "task", provider = provider, turnId = "turn",
            onHistorySnapshot = { round, history ->
                assertEquals(round - 1, snapshots.size)
                if (round == 2) assertEquals(2, finished)
                snapshots += history
                "snapshot-$round"
            },
            onEvent = { event -> if (event is AgentEvent.RoundStarted) {
                assertEquals("snapshot-${event.round}", event.historySnapshotId)
                assertEquals(event.round, snapshots.size)
            } },
            toolExecutor = AgentModelClient.ToolExecutor { finished++; AgentModelClient.ToolResult("result") })
        assertEquals(2, snapshots.size)
        assertEquals(listOf("user"), snapshots.first().map { it.role })
        assertEquals(listOf("user", "assistant", "tool", "tool"), snapshots.last().map { it.role })
        assertEquals(setOf("one", "two"), snapshots.last().filter { it.role == "tool" }.map { it.toolCallId }.toSet())
        assertEquals(setOf("turn"), snapshots.last().map { it.turnId }.toSet())
    }

    @Test fun snapshotRestoresDurableMediaAfterProviderHydration() {
        val user = AgentModelClient.ConversationMessage("user", contentJson =
            """[{"type":"text","text":"old media"},{"type":"image_file","path":"/nonexistent/image.jpg","mime":"image/jpeg","name":"image"},{"type":"video_file","path":"/nonexistent/video.mp4","mime":"video/mp4","name":"video"}]""",
            turnId = "old")
        var captured: List<AgentModelClient.ConversationMessage>? = null
        AgentModelClient.complete(config = modelConfig().copy(supportsVision = false), prompt = "task",
            history = listOf(user), provider = ScriptedProvider(assistant(content = "done", finishReason = "stop")),
            onHistorySnapshot = { _, history -> captured = history; "snapshot" },
            toolExecutor = AgentModelClient.ToolExecutor { error("unexpected tool") })
        assertEquals(user, captured!!.first())
        assertEquals(listOf("/nonexistent/image.jpg", "/nonexistent/video.mp4"),
            AgentConversationCodec.persistedImageSources(captured!!.first()))
    }

    @Test fun failingSnapshotPublisherCannotAbortTheOriginalRun() {
        val provider = ScriptedProvider(assistant(content = "done", finishReason = "stop"))
        val tokens = mutableListOf<String>()
        val result = AgentModelClient.complete(config = modelConfig(), prompt = "task", provider = provider,
            onHistorySnapshot = { _, _ -> error("snapshot unavailable") },
            onEvent = { event -> if (event is AgentEvent.RoundStarted) tokens += event.historySnapshotId },
            toolExecutor = AgentModelClient.ToolExecutor { error("unexpected tool") })
        assertEquals("done", result.content)
        assertEquals(listOf(""), tokens)
        assertEquals(1, provider.requests.size)
    }

    @Test fun completeForwardsExplicitUnknownContextPermission() {
        val prompt = "中".repeat(190_000) // >272k local tokens, safely below the stored-character cap.
        assertTrue(AgentContextBudget.countTokens(prompt) > 272_000)
        for (allowed in listOf(false, true)) {
            val controller = AgentRunController()
            val provider = ScriptedProvider(assistant(content = "done", finishReason = "stop"))
            val run = {
                AgentModelClient.complete(
                    config = modelConfig().copy(contextWindow = 272_000), prompt = prompt,
                    provider = provider, runController = controller,
                    allowUnmeasuredContextSend = allowed,
                    onEvent = { event ->
                        if (event is AgentEvent.ContextCompacted && event.blocked) controller.cancel()
                    },
                    toolExecutor = AgentModelClient.ToolExecutor { error("Unexpected tool") },
                )
            }
            if (allowed) {
                assertEquals("done", run().content)
                assertEquals(1, provider.requests.size)
                assertEquals(prompt, provider.requests.single()
                    .getJSONObject(provider.requests.single().length() - 1).getString("content"))
            } else {
                assertThrows(io.github.mangi.eta.agent.runtime.AgentRunCancelledException::class.java) { run() }
                assertTrue(provider.requests.isEmpty())
            }
        }
    }

    @Test fun stoppingMidBatchKeepsCompletedResultsAndMarksUnconfirmedCalls() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(assistant(finishReason = "tool_calls", toolCalls = listOf(
            toolCall("done", "get_current_context", "{}"),
            toolCall("unknown", "get_current_context", "{}"),
        )))
        val executed = mutableListOf<String>()
        val failure = assertThrows(io.github.mangi.eta.agent.runtime.AgentRunCancelledException::class.java) {
            AgentModelClient.complete(config = modelConfig(), prompt = "task", provider = provider,
                runController = controller, turnId = "logical-turn",
                toolExecutor = AgentModelClient.ToolExecutor { call ->
                    executed += call.id
                    controller.steer("accepted supplement")
                    controller.cancel()
                    AgentModelClient.ToolResult("confirmed result")
                })
        }
        assertEquals(listOf("done"), executed)
        assertEquals("confirmed result", failure.transcript.single { it.toolCallId == "done" }.content)
        assertTrue(failure.transcript.single { it.toolCallId == "unknown" }.content.contains("STOPPED_OUTCOME_UNKNOWN"))
        assertTrue(failure.transcript.last().content.contains("accepted supplement"))
        assertTrue(failure.transcript.all { it.turnId == "logical-turn" })
    }

    @get:org.junit.Rule val timeout = org.junit.rules.Timeout.seconds(45)
    @Test fun stoppedPartialTextAndSupplementSurviveWithoutReplayingTools() {
        val controller = AgentRunController()
        val provider = object : AgentProviderClient {
            override val id = "stop-partial"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "visible partial"))
                runController.steer("review this")
                runController.cancel()
                runController.throwIfCancelled()
                error("unreachable")
            }
        }
        val failure = assertThrows(io.github.mangi.eta.agent.runtime.AgentRunCancelledException::class.java) {
            AgentModelClient.complete(config = modelConfig(), prompt = "task", provider = provider,
                runController = controller, turnId = "one-turn",
                toolExecutor = AgentModelClient.ToolExecutor { error("must not execute") })
        }
        assertEquals("visible partial", failure.transcript.first().content)
        assertTrue(failure.transcript.last().content.contains("review this"))
        assertEquals(setOf("one-turn"), failure.transcript.map { it.turnId }.toSet())
    }

    @Test
    fun eachRoundUsesOneCapabilitySnapshotForDeclarationValidationAndPrompt() {
        var root = true
        var captures = 0
        val executed = mutableListOf<String>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                // 用带引号的 JSON 字面量精确匹配工具名：裸子串 "app_state_control" 不会误命中
                // 任何其他工具（set_setting 已升级为免 Root 的 PARTIAL，无 Root 时仍在目录中）。
                assertTrue(request.tools.toString().contains("\"app_state_control\""))
                assertTrue(request.messages.toString().contains("相关应用私有文件与数据库"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("first", "get_current_context", "{}")))
            },
            { request, _ ->
                assertFalse(request.tools.toString().contains("\"app_state_control\""))
                assertFalse(request.messages.toString().contains("相关应用私有文件与数据库"))
                assertTrue(request.messages.toString().contains("identity=user"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("stale", "terminal", "{\"action\":\"open\",\"identity\":\"root\"}"),
                ))
            },
            { request, _ ->
                assertTrue(request.messages.toString().contains("INVALID_TOOL_ARGUMENTS"))
                assistant(content = "完成", finishReason = "stop")
            },
        ))
        AgentModelClient.complete(
            config = modelConfig().copy(terminalTools = true, deviceTools = true),
            prompt = "开始",
            provider = provider,
            capabilitiesProvider = {
                captures++
                AgentToolCapabilities(rootAvailable = root)
            },
            // 设备控制与终端组默认折叠：这里要先打开它们，才能观察到「Root 消失后工具离开目录」。
            openedToolGroupsProvider = {
                setOf(AgentToolGroups.DEVICE_GROUP.id, AgentToolGroups.TERMINAL_GROUP.id)
            },
            toolExecutor = AgentModelClient.ToolExecutor {
                executed += it.id
                root = false
                AgentModelClient.ToolResult("{\"ok\":true}")
            },
        )
        assertEquals(listOf("first"), executed)
        // 首轮复用初始快照，只有工具可能改变能力之后（第 2/3 轮）才重新捕获。
        assertEquals(3, captures)
    }

    @Test
    fun openingAToolGroupMidRunGrowsTheSystemPrefixWithoutTouchingHistory() {
        val opened = mutableSetOf<String>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                assertFalse(request.messages.toString().contains("日程与闹钟一律用系统那套"))
                assistant(finishReason = "tool_calls",
                    toolCalls = listOf(toolCall("call-1", "get_current_context", "{}")))
            },
            { request, _ ->
                assertTrue(request.messages.toString().contains("日程与闹钟一律用系统那套"))
                assistant(content = "完成", finishReason = "stop")
            },
        ))

        val result = AgentModelClient.complete(
            config = modelConfig().copy(deviceTools = true),
            prompt = "现在",
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "旧问题"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "旧回答"),
            ),
            provider = provider,
            openedToolGroupsProvider = { opened.toSet() },
            toolExecutor = AgentModelClient.ToolExecutor {
                // 等价于 tools_use_group 成功：设备控制组从下一轮起同时进入目录与系统提示。
                opened += AgentToolGroups.DEVICE_GROUP.id
                AgentModelClient.ToolResult("{\"ok\":true}")
            },
        )

        val first = provider.requests[0]
        val second = provider.requests[1]
        assertEquals(second.systemMessages(), first.systemMessages() + 1)
        // 系统前缀变长时历史必须整体后移：JSONArray.put(index, …) 是替换，
        // 按索引覆写会把第一条历史消息盖成系统消息。
        listOf("旧问题", "旧回答", "现在").forEach { text ->
            assertTrue("$text 必须还在第二轮请求里", second.toString().contains(text))
        }
        assertEquals(
            listOf("user", "assistant", "user", "assistant", "tool"),
            second.roles().dropWhile { it == "system" },
        )
        // transcript 起点同样按条数计，前缀变长后必须跟着移动，否则会把用户消息重复写回历史。
        assertEquals(listOf("assistant", "tool", "assistant"), result.transcript.map { it.role })
    }

    @Test
    fun textOnlyRunReturnsIncrementalTranscript() {
        val provider = ScriptedProvider(
            assistant(content = "完成", finishReason = "stop")
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "当前问题",
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "旧问题"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "旧回答"),
            ),
            toolExecutor = AgentModelClient.ToolExecutor { error("不应调用工具") },
            provider = provider,
        )

        assertEquals("完成", result.content)
        assertEquals(listOf("assistant"), result.transcript.map { it.role })
        assertEquals("完成", result.transcript.single().content)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun toolBatchFeedsResultsBackInSourceOrder() {
        val provider = ScriptedProvider(
            assistant(
                content = "先执行",
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "get_current_context", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
                reasoning = "需要两个结果",
            ),
            assistant(content = "已完成", finishReason = "stop"),
        )
        val executed = mutableListOf<String>()

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                AgentModelClient.ToolResult(
                    JSONObject()
                        .put("ok", true)
                        .put("call", call.id)
                        .toString()
                )
            },
            provider = provider,
        )

        assertEquals(listOf("call-1", "call-2"), executed)
        assertEquals("需要两个结果", result.reasoningContent)
        assertEquals(
            listOf("assistant", "tool", "tool", "assistant"),
            result.transcript.map { it.role },
        )
        assertEquals(
            listOf("assistant", "tool", "tool"),
            provider.requests[1].roleSuffix(3),
        )
        assertEquals("call-1", provider.requests[1].getJSONObjectFromEnd(2).getString("tool_call_id"))
        assertEquals("call-2", provider.requests[1].getJSONObjectFromEnd(1).getString("tool_call_id"))
        assertEquals(
            "需要两个结果",
            provider.requests[1].getJSONObjectFromEnd(3).getString("reasoning_content"),
        )
    }

    @Test
    fun interruptedToolDraftPreservesTextAndSteeringWithoutToolFailureOrReplay() {
        val events = mutableListOf<AgentEvent>()
        val executed = mutableListOf<String>()
        val provider = ScriptedProvider(responses = listOf(
            { _, controller ->
                controller.steer("new instruction")
                interruptedAssistantMessage("before ", "")
            },
            { request, _ ->
                assertTrue(request.messages.toString().contains("before"))
                assertTrue(request.messages.toString().contains("new instruction"))
                assertFalse(request.messages.toString().contains("INVALID_TOOL_ARGUMENTS"))
                assertFalse(request.messages.toString().contains("tool_calls"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("fresh", "get_current_context", "{}")))
            },
            { _, _ -> assistant(content = "done", finishReason = "stop") },
        ))
        AgentModelClient.complete(config = modelConfig(), prompt = "task", provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                AgentModelClient.ToolResult("ok")
            }, onEvent = events::add)
        assertEquals(listOf("fresh"), executed)
        assertEquals(listOf("fresh"), events.filterIsInstance<AgentEvent.ToolStarted>().map { it.toolCallId })
        assertEquals(3, provider.requests.size)
    }

    @Test
    fun pauseResumeAndSupplementKeepTheOriginalLogicalTurn() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(responses = listOf(
            { _, control ->
                val binding = control.register(interruptible = true) {}
                try {
                    control.pause()
                    control.resume()
                } finally { binding.close() }
                interruptedAssistantMessage("first ", "")
            },
            { _, control ->
                val binding = control.register(interruptible = true) {}
                try {
                    control.pause()
                    control.steer("supplement")
                } finally { binding.close() }
                interruptedAssistantMessage("second ", "")
            },
            { _, _ -> assistant(content = "done", finishReason = "stop") },
        ))
        val result = AgentModelClient.complete(
            config = modelConfig(), prompt = "original task", turnId = "original-turn",
            provider = provider, runController = controller,
            toolExecutor = AgentModelClient.ToolExecutor { error("No tools expected") },
        )
        assertEquals(3, provider.requests.size)
        assertTrue(result.transcript.isNotEmpty())
        assertEquals(setOf("original-turn"), result.transcript.map { it.turnId }.toSet())
        assertTrue(result.transcript.any { it.role == "user" && it.content.contains("supplement") })
        assertTrue(result.transcript.any { it.content.contains("first") })
        assertFalse(controller.isPaused)
        assertFalse(controller.hasPendingSteering)
    }

    @Test
    fun steeringWaitsForWholeToolBatchWithoutCancellingResources() {
        val controller = AgentRunController()
        val cancelledResources = AtomicInteger(0)
        controller.register { cancelledResources.incrementAndGet() }
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "get_current_context", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
            ),
            assistant(content = "已按补充完成", finishReason = "stop"),
        )
        val executed = mutableListOf<String>()

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                if (call.id == "call-1") controller.steer("改用第二种方案")
                AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
            },
            provider = provider,
            runController = controller,
        )

        assertEquals(listOf("call-1", "call-2"), executed)
        assertEquals(0, cancelledResources.get())
        assertFalse(controller.hasPendingSteering)
        assertEquals("已按补充完成", result.content)
        assertEquals(
            listOf("assistant", "tool", "tool", "user"),
            provider.requests[1].roleSuffix(4),
        )
        assertTrue(
            provider.requests[1]
                .getJSONObjectFromEnd(1)
                .getString("content")
                .contains("改用第二种方案")
        )
    }

    @Test
    fun streamingSteerInterruptsCurrentRequestAndKeepsPartialText() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(
            responses = listOf(
                { _, runController ->
                    val interrupted = CountDownLatch(1)
                    runController.register(interruptible = true) { interrupted.countDown() }
                    val worker = thread(name = "loop-steer-interrupt-test", isDaemon = true) {
                        runController.steer("改成短篇，两百字就够")
                    }
                    try {
                        assertTrue(interrupted.await(1, TimeUnit.SECONDS))
                    } finally {
                        worker.join(1_000)
                    }
                    assistant(content = "从前有座山，山里有座庙。", finishReason = "stop")
                },
                { request, _ ->
                    assertTrue(
                        request.messages.getJSONObject(request.messages.length() - 1)
                            .getString("content")
                            .contains("改成短篇")
                    )
                    assistant(content = "好，改成两百字。", finishReason = "stop")
                },
            )
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "写5000字小说",
            toolExecutor = AgentModelClient.ToolExecutor { error("不应调用工具") },
            provider = provider,
            runController = controller,
        )

        assertEquals("好，改成两百字。", result.content)
        assertEquals(listOf("assistant", "user", "assistant"), result.transcript.map { it.role })
        assertEquals("从前有座山，山里有座庙。", result.transcript.first().content)
        assertFalse(controller.hasPendingSteering)
    }

    @Test
    fun steeringAfterTextResponsePreservesThatAssistantTurn() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    controller.steer("再补充一项")
                    assistant(content = "第一段回答", finishReason = "stop")
                },
                { _, _ -> assistant(content = "最终回答", finishReason = "stop") },
            )
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { error("不应调用工具") },
            provider = provider,
            runController = controller,
        )

        assertEquals("最终回答", result.content)
        assertEquals(
            listOf("assistant", "user", "assistant"),
            result.transcript.map { it.role },
        )
        assertEquals("第一段回答", result.transcript.first().content)
    }

    @Test
    fun truncatedToolCallIsReportedWithoutExecution() {
        listOf("length", "max_tokens").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "terminal", "{\"command\":\"rm -")),
                ),
                assistant(content = "已重新规划", finishReason = "stop"),
            )
            var executed = false
            val events = mutableListOf<AgentEvent>()

            val result = AgentModelClient.complete(
                config = modelConfig(),
                prompt = "执行任务",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executed = true
                    AgentModelClient.ToolResult("unexpected")
                },
                provider = provider,
                onEvent = events::add,
            )

            assertFalse(executed)
            assertEquals("已重新规划", result.content)
            val toolResult = provider.requests[1].getJSONObjectFromEnd(1)
            assertEquals("tool", toolResult.getString("role"))
            assertTrue(toolResult.getString("content").contains("TRUNCATED_TOOL_CALL"))
            val toolStarted = events.filterIsInstance<AgentEvent.ToolStarted>().single()
            assertFalse(toolStarted.argsPreview.contains("rm -"))
        }
    }

    @Test
    fun malformedCallsAndUniqueIdsReceiveStableTerminalResults() {
        val malformedCalls = JSONArray()
            .put(toolCall("first", "get_current_context", "{}"))
            .put(toolCall("second", "get_current_context", "{}"))
            .put("not-an-object")
        val firstResponse = assistant(content = "", finishReason = "tool_calls")
            .put("tool_calls", malformedCalls)
        val provider = ScriptedProvider(
            firstResponse,
            assistant(content = "recovered", finishReason = "stop"),
        )
        val executed = mutableListOf<Pair<String, String>>()

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "开始",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id to call.name
                AgentModelClient.ToolResult(JSONObject().put("ok", false).toString())
            },
            provider = provider,
        )

        assertEquals(
            listOf(
                "first" to "get_current_context",
                "second" to "get_current_context",
            ),
            executed,
        )
        val secondRequest = provider.requests[1]
        assertEquals(
            listOf("first", "second", "tool_call_2"),
            secondRequest
                .getJSONObject(secondRequest.length() - 4)
                .getJSONArray("tool_calls")
                .let { calls -> (0 until calls.length()).map { calls.getJSONObject(it).getString("id") } },
        )
        assertEquals(
            listOf("first", "second", "tool_call_2"),
            (3 downTo 1).map { offset ->
                secondRequest.getJSONObjectFromEnd(offset).getString("tool_call_id")
            },
        )
    }

    @Test
    fun duplicateRawToolIdsAreStabilizedAndBothCallsExecute() {
        val provider = ScriptedProvider(assistant(content = "", finishReason = "tool_calls")
            .put("tool_calls", JSONArray()
                .put(toolCall("duplicate", "get_current_context", "{}"))
                .put(toolCall("duplicate", "get_current_context", "{}"))),
            assistant(content = "完成", finishReason = "stop"))
        val executed = mutableListOf<String>()
        AgentModelClient.complete(config = modelConfig(), prompt = "开始", provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                executed += it.id
                AgentModelClient.ToolResult("{}")
            })
        assertEquals(listOf("duplicate", "duplicate_1"), executed)
        assertEquals(2, provider.requests.size)
    }

    @Test
    fun imageObservationsFollowEveryToolResultInTheBatch() {
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall("call-1", "observe_screen", "{}"),
                    toolCall("call-2", "get_current_context", "{}"),
                ),
            ),
            assistant(content = "看到了", finishReason = "stop"),
        )

        val result = AgentModelClient.complete(
            config = modelConfig(),
            prompt = "观察",
            // 屏幕组默认折叠；这里断言的是工具图片如何回灌，与分层加载无关。
            openedToolGroupsProvider = { setOf(AgentToolGroups.SCREEN_GROUP.id) },
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = if (call.id == "call-1") {
                        listOf(
                            AgentModelClient.ModelImage(
                                reference = "data:image/png;base64,AA==",
                                mimeType = "image/png",
                                bytes = 1,
                            )
                        )
                    } else {
                        emptyList()
                    },
                )
            },
            provider = provider,
        )

        assertEquals(
            listOf("assistant", "tool", "tool", "user"),
            provider.requests[1].roleSuffix(4),
        )
        assertFalse(result.transcript.any { it.contentJson.contains("base64") })
        assertFalse(result.transcript.any { it.contentJson.contains("未写入持久会话") })
    }

    @Test
    fun toolScreenshotIsConsumedByExactlyOneModelRequest() {
        val screenshot = "data:image/png;base64,c2NyZWVu"
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe", "observe_screen", "{}")),
            ),
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("tap", "tap", "{\"x\":10,\"y\":20}")),
            ),
            assistant(content = "完成", finishReason = "stop"),
        )

        val result = AgentModelClient.complete(
            config = modelConfig().copy(supportsVision = true),
            prompt = "观察后点击",
            openedToolGroupsProvider = { setOf(AgentToolGroups.SCREEN_GROUP.id) },
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = if (call.name == "observe_screen") {
                        listOf(
                            AgentModelClient.ModelImage(
                                reference = screenshot,
                                mimeType = "image/png",
                                bytes = 6,
                                source = "screen",
                            )
                        )
                    } else {
                        emptyList()
                    },
                )
            },
            provider = provider,
        )

        assertFalse(provider.requests[0].toString().contains(screenshot))
        assertTrue(provider.requests[1].toString().contains(screenshot))
        assertFalse(provider.requests[2].toString().contains(screenshot))
        assertFalse(result.transcript.any { it.contentJson.contains(screenshot) })
    }

    @Test
    fun newerToolImageReplacesThePreviousTransientObservation() {
        val firstImage = "data:image/png;base64,Zmlyc3Q="
        val secondImage = "data:image/png;base64,c2Vjb25k"
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe-1", "observe_screen", "{}")),
            ),
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("observe-2", "observe_screen", "{}")),
            ),
            assistant(content = "完成", finishReason = "stop"),
        )
        var observationIndex = 0

        AgentModelClient.complete(
            config = modelConfig().copy(supportsVision = true),
            prompt = "连续观察",
            openedToolGroupsProvider = { setOf(AgentToolGroups.SCREEN_GROUP.id) },
            toolExecutor = AgentModelClient.ToolExecutor {
                val reference = if (observationIndex++ == 0) firstImage else secondImage
                AgentModelClient.ToolResult(
                    content = JSONObject().put("ok", true).toString(),
                    images = listOf(
                        AgentModelClient.ModelImage(
                            reference = reference,
                            mimeType = "image/png",
                            bytes = 6,
                            source = "screen",
                        )
                    ),
                )
            },
            provider = provider,
        )

        assertTrue(provider.requests[1].toString().contains(firstImage))
        assertFalse(provider.requests[2].toString().contains(firstImage))
        assertTrue(provider.requests[2].toString().contains(secondImage))
    }

    @Test
    fun missingRequiredArgumentsNeverReachDeviceExecutor() {
        val provider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(toolCall("call-1", "tap", "{}")),
            ),
            assistant(content = "已修正", finishReason = "stop"),
        )
        var executed = false

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "点击",
            toolExecutor = AgentModelClient.ToolExecutor {
                executed = true
                AgentModelClient.ToolResult("unexpected")
            },
            provider = provider,
        )

        assertFalse(executed)
        assertTrue(
            provider.requests[1]
                .getJSONObjectFromEnd(1)
                .getString("content")
                .contains("INVALID_TOOL_ARGUMENTS")
        )
    }

    @Test
    fun contradictoryStopReasonNeverExecutesToolCalls() {
        listOf("stop").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "tap", "{\"x\":1,\"y\":2}")),
                ),
                assistant(content = "已安全结束", finishReason = "stop"),
            )
            var executed = false

            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "开始",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executed = true
                    AgentModelClient.ToolResult("unexpected")
                },
                provider = provider,
            )

            assertFalse(executed)
            assertTrue(
                provider.requests[1]
                    .getJSONObjectFromEnd(1)
                    .getString("content")
                    .contains("UNEXPECTED_TOOL_CALL")
            )
        }
    }

    @Test
    fun refusalEndsRunWithoutReplayingPartialAssistantOrExecutingTools() {
        listOf("content_filter", "refusal").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    content = "半截回复",
                    reasoning = "半截思考",
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "tap", "{\"x\":1,\"y\":2}")),
                ).put(
                    "stop_details",
                    JSONObject().put("type", "refusal").put("category", "cyber").put("explanation", "服务商说明文本"),
                ),
                assistant(content = "不应再请求", finishReason = "stop"),
            )
            var executed = false

            val failure = assertThrows(AgentModelExecutionException::class.java) {
                AgentModelClient.complete(
                    config = modelConfig(),
                    prompt = "开始",
                    toolExecutor = AgentModelClient.ToolExecutor {
                        executed = true
                        AgentModelClient.ToolResult("unexpected")
                    },
                    provider = provider,
                )
            }

            assertFalse(executed)
            assertEquals(1, provider.requests.size)
            val cause = failure.cause as AgentModelFailure
            assertEquals("MODEL_CONTENT_FILTER", cause.code)
            assertFalse(cause.retryable)
            assertTrue(cause.message.orEmpty().contains("服务商说明文本"))
            assertTrue(failure.transcript.none { it.role == "assistant" })
        }
    }

    @Test
    fun normalToolStopAliasesExecuteValidatedCalls() {
        listOf("tool_calls", "tool_use").forEach { finishReason ->
            val provider = ScriptedProvider(
                assistant(
                    finishReason = finishReason,
                    toolCalls = listOf(toolCall("call-1", "get_current_context", "{}")),
                ),
                assistant(content = "完成", finishReason = "stop"),
            )
            var executions = 0

            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "开始",
                toolExecutor = AgentModelClient.ToolExecutor {
                    executions += 1
                    AgentModelClient.ToolResult("{\"ok\":true}")
                },
                provider = provider,
            )

            assertEquals(1, executions)
        }
    }

    @Test
    fun providerFailureCarriesCompletedToolTranscriptForSafeRecovery() {
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    assistant(
                        finishReason = "tool_calls",
                        reasoning = "先检查状态",
                        toolCalls = listOf(
                            toolCall("call-1", "get_current_context", "{}")
                        ),
                    )
                },
                { _, _ -> error("provider disconnected") },
            )
        )

        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(
                config = modelConfig(),
                prompt = "开始",
                toolExecutor = AgentModelClient.ToolExecutor {
                    AgentModelClient.ToolResult("{\"ok\":true}")
                },
                provider = provider,
            )
        }

        assertEquals(listOf("assistant", "tool"), failure.transcript.map { it.role })
        assertEquals("先检查状态", failure.reasoningContent)
    }

    @Test
    fun loopContinuesPastFormerLocalLimitsUntilProviderFinishes() {
        val toolRounds = 257
        val responses = List<(ProviderRequest, AgentRunController) -> JSONObject>(toolRounds) { index ->
            { _, _ ->
                assistant(
                    finishReason = "tool_calls",
                    toolCalls = listOf(toolCall("call-$index", "get_current_context", "{}")),
                )
            }
        } + listOf<(ProviderRequest, AgentRunController) -> JSONObject>(
            { _, _ -> assistant(content = "完成", finishReason = "stop") }
        )
        val provider = ScriptedProvider(responses)
        var executions = 0
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("开始"))

        val result = AgentLoop(
            config = modelConfig(),
            messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                executions += 1
                AgentModelClient.ToolResult(JSONObject().put("ok", true).toString())
            },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
        ).run()

        assertEquals("完成", result.content)
        assertEquals(toolRounds, executions)
        assertEquals(toolRounds + 1, provider.requests.size)
    }

    @Test
    fun retryPreservesToolResultsAndImagesWithoutReplayingToolsOrFailedReasoning() {
        val requests = mutableListOf<String>()
        val sessions = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        var executions = 0
        val provider = object : AgentProviderClient by ScriptedProvider(emptyList()) {
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                requests += request.messages.toString()
                sessions += request.sessionId
                onEvent(ProviderEvent.RequestStarted)
                return when (requests.size) {
                    1 -> ProviderResponse(assistant(
                        finishReason = "tool_calls",
                        reasoning = "先观察",
                        toolCalls = listOf(toolCall("observe-1", "get_current_context", "{}")),
                    ))
                    2 -> {
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "失败的思考"))
                        throw java.net.SocketTimeoutException("timeout")
                    }
                    else -> ProviderResponse(assistant(content = "完成", finishReason = "stop", reasoning = "观察成功"))
                }
            }
        }
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("开始"))
        val loop = AgentLoop(
            config = modelConfig().copy(supportsVision = true, errorReconnectPolicy = "continuous"), messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                executions++
                AgentModelClient.ToolResult(
                    content = "观察结果",
                    images = listOf(AgentModelClient.ModelImage("data:image/png;base64,dGVzdA==", "image/png", 4)),
                )
            },
            runController = AgentRunController(), traceFormatter = AgentTraceFormatter(),
            onEvent = events::add, modelRetry = AgentModelRetry { _, _ -> },
            sessionId = "conversation-retry",
        )
        val result = loop.run()
        assertEquals(1, executions)
        assertEquals(3, requests.size)
        assertEquals(List(3) { "conversation-retry" }, sessions)
        val retriedHistory = JSONArray(requests[2])
        assertEquals(requests[1], JSONArray().apply {
            for (index in 0 until retriedHistory.length() - 1) {
                val message = retriedHistory.getJSONObject(index)
                if (!AgentRecoveryContext.isActive(JSONArray().put(message))) put(message)
            }
        }.toString())
        assertTrue(requests[2].contains("Continue the interrupted task"))
        assertTrue(requests[2].contains("data:image/png"))
        assertFalse(messages.toString().contains("data:image/png"))
        assertFalse(messages.toString().contains("半截"))
        assertEquals("先观察观察成功", result.reasoningContent)
        assertEquals(listOf(1, 2, 3), events.filterIsInstance<AgentEvent.RoundStarted>().map { it.round })
        assertEquals(2, events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().first().round)
        assertEquals(1, events.filterIsInstance<AgentEvent.ModelRetryScheduled>().size)
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolStarted>().size)
    }

    @Test
    fun insufficientRequestWindowBlocksInsteadOfSilentlyTrimmingHistory() {
        for (legacySkipFlag in listOf(false, true)) {
            val controller = AgentRunController()
            val provider = ScriptedProvider(assistant(content = "must not run", finishReason = "stop"))
            var blocked = false
            assertThrows(io.github.mangi.eta.agent.runtime.AgentRunCancelledException::class.java) {
                AgentModelClient.complete(
                    config = modelConfig().copy(contextWindow = 100),
                    prompt = "current question",
                    history = listOf(AgentModelClient.ConversationMessage("user", "protected history")),
                    toolExecutor = AgentModelClient.ToolExecutor { error("No tools") },
                    provider = provider, runController = controller,
                    skipHistoryTrimming = legacySkipFlag,
                    onEvent = { event ->
                        if (event is AgentEvent.ContextCompacted && event.blocked) {
                            blocked = true
                            controller.cancel()
                        }
                    },
                )
            }
            assertTrue(blocked)
            assertTrue(provider.requests.isEmpty())
        }
    }

    private class ScriptedProvider(
        private val responses: List<(ProviderRequest, AgentRunController) -> JSONObject>,
    ) : AgentProviderClient {
        constructor(vararg responses: JSONObject) : this(
            responses.map { response -> { _, _ -> response } }
        )

        override val id: String = "scripted"
        override val capabilities: ProviderCapabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )

        val requests = mutableListOf<JSONArray>()
        val requestConfigs = mutableListOf<AgentModelClient.ModelConfig>()
        private var index = 0

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            requests += JSONArray(request.messages.toString())
            requestConfigs += request.config
            onEvent(ProviderEvent.RequestStarted)
            AgentWireRequestEstimate.publish(
                AgentWireRequestEstimate.previewBody(request.config, request.messages, request.tools),
                AgentWireRequestEstimate.endpoint(request.config), request, onEvent)
            val response = responses.getOrNull(index)
                ?: error("缺少第 ${index + 1} 个 scripted response")
            index += 1
            val payload = response(request, runController)
            val usageJson = payload.optJSONObject("usage")
            if (usageJson != null) {
                onEvent(
                    ProviderEvent.Usage(
                        AgentTokenUsage(
                            contextTokens = usageJson.optInt("total_tokens").takeIf { usageJson.has("total_tokens") },
                            inputTokens = usageJson.optInt("prompt_tokens").takeIf { usageJson.has("prompt_tokens") },
                            outputTokens = usageJson.optInt("completion_tokens").takeIf { usageJson.has("completion_tokens") },
                        ),
                    ),
                )
            }
            return ProviderResponse(payload)
        }
    }

    @Test
    fun compactRunsAfterToolBatchBeforeNextProviderRequest() {
        val events = mutableListOf<AgentEvent>()
        var compactCalls = 0
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("call-1", "get_current_context", "{}")),
                        promptTokens = 95_000,
                    )
                },
                { _, _ -> assistant(content = "完成", finishReason = "stop", promptTokens = 20) },
            )
        )
        val history = (1..6).flatMap { n ->
            listOf(
                AgentConversationCodec.userTextMessage("u$n"),
                AgentConversationCodec.assistantHistoryMessage(
                    assistant(content = "a$n", finishReason = "stop"),
                    emptyList(),
                ),
            )
        }
        val messages = org.json.JSONArray()
        history.forEach { messages.put(it) }
        messages.put(AgentConversationCodec.userTextMessage("现在"))

        val result = AgentLoop(
            config = modelConfig(),
            messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                AgentModelClient.ToolResult(org.json.JSONObject().put("ok", true).toString())
            },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = events::add,
            compactPolicy = AgentLoop.CompactPolicy(
                enabled = true,
                contextWindow = 100_000,
                keepRecentMessages = 2,
                compressModelConfig = modelConfig(),
            ),
            compactHistory = { source, policy ->
                compactCalls += 1
                listOf(
                    AgentModelClient.ConversationMessage(
                        role = "system",
                        content = AgentContextCompactor.SUMMARY_PREFIX_ZH + "\n摘要",
                    ),
                ) + source.drop(requireNotNull(policy.keepStartOverride))
            },
        ).run()

        assertEquals("完成", result.content)
        assertEquals(1, compactCalls)
        assertEquals(1, events.filterIsInstance<AgentEvent.ContextCompactionStarted>().size)
        val compacted = events.filterIsInstance<AgentEvent.ContextCompacted>().single()
        assertTrue(compacted.applied)
        val second = provider.requests[1]
        val secondContents = (0 until second.length()).map { second.getJSONObject(it).optString("content") }
        assertTrue(secondContents.any { it.contains("摘要") || it.contains("对话摘要") })
        assertFalse(secondContents.contains("u1"))
        assertTrue((0 until second.length()).any { second.getJSONObject(it).optString("role") == "tool" })
        val projections = events.filterIsInstance<AgentEvent.UsageReceived>().filter { it.projected }
        assertEquals(listOf(1, 2), projections.map { it.round })
        val secondConfig = provider.requestConfigs[1]
        val secondBody = AgentWireRequestEstimate.previewBody(secondConfig, second,
            AgentToolCatalog.build(terminalTools = false, browserTools = false))
        assertEquals(AgentWireRequestEstimate.measure(secondBody,
            AgentWireRequestEstimate.endpoint(secondConfig)).tokens, projections.last().usage.inputTokens)
    }

    @Test
    fun forcedCompactRunsOnFirstRoundWithoutSplittingRun() {
        val events = mutableListOf<AgentEvent>()
        var compactCalls = 0
        val controller = AgentRunController()
        controller.requestCompact(keepRecentMessages = 1)
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ -> assistant(content = "完成", finishReason = "stop", promptTokens = 20) },
            )
        )
        val history = (1..4).flatMap { n ->
            listOf(
                AgentConversationCodec.userTextMessage("u$n"),
                AgentConversationCodec.assistantHistoryMessage(
                    assistant(content = "a$n", finishReason = "stop"),
                    emptyList(),
                ),
            )
        }
        val messages = org.json.JSONArray()
        history.forEach { messages.put(it) }
        messages.put(AgentConversationCodec.userTextMessage("现在"))

        val result = AgentLoop(
            config = modelConfig(),
            messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                AgentModelClient.ToolResult(org.json.JSONObject().put("ok", true).toString())
            },
            runController = controller,
            traceFormatter = AgentTraceFormatter(),
            onEvent = events::add,
            compactPolicy = AgentLoop.CompactPolicy(
                enabled = false,
                contextWindow = 128_000,
                keepRecentMessages = 2,
                compressModelConfig = modelConfig(),
            ),
            compactHistory = { source, policy ->
                compactCalls += 1
                assertEquals(1, policy.keepRecentMessages)
                listOf(
                    AgentModelClient.ConversationMessage(
                        role = "system",
                        content = AgentContextCompactor.SUMMARY_PREFIX_ZH + "\n摘要",
                    ),
                ) + source.drop(requireNotNull(policy.keepStartOverride))
            },
        ).run()

        assertEquals("完成", result.content)
        assertEquals(1, compactCalls)
        assertEquals(1, provider.requests.size)
        val contents = (0 until provider.requests[0].length()).map {
            provider.requests[0].getJSONObject(it).optString("content")
        }
        assertTrue(contents.any { it.contains("摘要") || it.contains("对话摘要") })
        assertTrue(contents.contains("现在") || contents.any { it.contains("现在") })
        assertFalse(contents.contains("u1"))
    }

    @Test
    fun compactDuringFinalResponseDoesNotGenerateAnotherReplyOrChangeThinking() {
        val controller = AgentRunController()
        val provider = ScriptedProvider(
            responses = listOf(
                { _, ctrl ->
                    ctrl.requestCompact(keepRecentMessages = 1)
                    assistant(content = "前文", finishReason = "stop", promptTokens = 80)
                },
                { _, _ -> assistant(content = "续写", finishReason = "stop", promptTokens = 20) },
            )
        )
        val history = (1..4).flatMap { n ->
            listOf(
                AgentConversationCodec.userTextMessage("u$n"),
                AgentConversationCodec.assistantHistoryMessage(
                    assistant(content = "a$n", finishReason = "stop"),
                    emptyList(),
                ),
            )
        }
        val messages = org.json.JSONArray()
        history.forEach { messages.put(it) }
        messages.put(AgentConversationCodec.userTextMessage("现在"))

        val result = AgentLoop(
            config = modelConfig().copy(
                thinkingEnabled = true,
                reasoningEffort = ReasoningEffort.HIGH,
            ),
            messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                AgentModelClient.ToolResult(org.json.JSONObject().put("ok", true).toString())
            },
            runController = controller,
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
            compactPolicy = AgentLoop.CompactPolicy(
                enabled = false,
                contextWindow = 128_000,
                keepRecentMessages = 2,
                compressModelConfig = modelConfig(),
            ),
            compactHistory = { source, policy ->
                listOf(
                    AgentModelClient.ConversationMessage(
                        role = "system",
                        content = AgentContextCompactor.SUMMARY_PREFIX_ZH + "\n摘要",
                    ),
                ) + source.drop(requireNotNull(policy.keepStartOverride))
            },
        ).run()

        assertEquals("前文", result.content)
        assertEquals(1, provider.requestConfigs.size)
        assertTrue(provider.requestConfigs[0].thinkingEnabled)
        assertEquals(ReasoningEffort.HIGH, provider.requestConfigs[0].reasoningEffort)
        assertFalse((0 until messages.length()).any {
            messages.getJSONObject(it).optString("content") == AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT
        })
        assertFalse(controller.requestCompact()) // run has sealed its maintenance inlet

    }

    @Test
    fun pauseKeepsPartialAssistantInSameTurnThenContinues() {
        val controller = AgentRunController()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val paused = CountDownLatch(1)
        val provider = ScriptedProvider(
            responses = listOf(
                { _, runController ->
                    val stream = java.util.concurrent.atomic.AtomicInteger()
                    runController.register(interruptible = true) { stream.incrementAndGet() }
                    runController.pause()
                    paused.countDown()
                    assistant(content = "已经写到一半", finishReason = "stop")
                },
                { request, _ ->
                    val contents = (0 until request.messages.length()).map {
                        request.messages.getJSONObject(it).optString("content")
                    }
                    assertTrue(contents.any { it.contains("已经写到一半") })
                    assertTrue(contents.any { it.contains(AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT) })
                    assistant(content = "接着写完", finishReason = "stop")
                },
            )
        )
        val worker = thread(name = "pause-continue-loop") {
            started.countDown()
            runCatching {
                AgentModelClient.complete(
                    config = modelConfig(),
                    prompt = "写一篇长文",
                    provider = provider,
                    runController = controller,
                    toolExecutor = AgentModelClient.ToolExecutor { error("不应调用工具") },
                )
            }.exceptionOrNull()?.let(failure::set)
            finished.countDown()
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(paused.await(2, TimeUnit.SECONDS))
            assertFalse(finished.await(200, TimeUnit.MILLISECONDS))
            controller.resume()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(null, failure.get()?.toString(), failure.get()?.message)
            assertEquals(2, provider.requests.size)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun pauseAfterCompleteToolCallsExecutesThemOnResume() {
        val controller = AgentRunController()
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val paused = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val toolsStarted = AtomicInteger()
        val provider = ScriptedProvider(
            responses = listOf(
                { _, runController ->
                    runController.register(interruptible = true) {}
                    runController.pause()
                    paused.countDown()
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("call-1", "get_current_context", "{}")),
                    )
                },
                { request, _ ->
                    val roles = (0 until request.messages.length()).map {
                        request.messages.getJSONObject(it).optString("role")
                    }
                    assertTrue(roles.contains("tool"))
                    assertFalse((0 until request.messages.length()).any {
                        request.messages.getJSONObject(it).optString("content") ==
                            AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT
                    })
                    assistant(content = "已根据上下文继续", finishReason = "stop")
                },
            )
        )
        val worker = thread(name = "pause-tool-loop") {
            started.countDown()
            runCatching {
                AgentModelClient.complete(
                    config = modelConfig(),
                    prompt = "查一下时间",
                    provider = provider,
                    runController = controller,
                    toolExecutor = AgentModelClient.ToolExecutor {
                        toolsStarted.incrementAndGet()
                        AgentModelClient.ToolResult("{\"now\":\"2026-09-17\"}")
                    },
                )
            }.exceptionOrNull()?.let(failure::set)
            finished.countDown()
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(paused.await(2, TimeUnit.SECONDS))
            assertFalse(finished.await(200, TimeUnit.MILLISECONDS))
            assertEquals(0, toolsStarted.get())
            controller.resume()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(null, failure.get()?.toString(), failure.get()?.message)
            assertEquals(1, toolsStarted.get())
            assertEquals(2, provider.requests.size)
        } finally {
            controller.cancel()
            worker.join(1_000)
        }
    }

    @Test
    fun usageLessToolRoundsDoNotReopenInitialProjectionBoundary() {
        val events = mutableListOf<AgentEvent>()
        val provider = ScriptedProvider(listOf(
            { _, _ -> assistant(finishReason = "tool_calls",
                toolCalls = listOf(toolCall("no-bill", "get_current_context", "{}"))) },
            { _, _ -> assistant(content = "done", finishReason = "stop") },
        ))
        AgentLoop(config = modelConfig(),
            messages = JSONArray().put(AgentConversationCodec.userTextMessage("task")),
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("{}") },
            runController = AgentRunController(), traceFormatter = AgentTraceFormatter(),
            onEvent = events::add, compactPolicy = AgentLoop.CompactPolicy.Disabled).run()
        val usage = events.filterIsInstance<AgentEvent.UsageReceived>()
        assertEquals(2, provider.requests.size)
        assertEquals(listOf(1), usage.map { it.round })
        assertTrue(usage.all { it.projected })
    }

    @Test
    fun reportsOnlyProviderUsageAcrossToolResults() {

        val events = mutableListOf<AgentEvent>()
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("call-1", "get_current_context", "{}")),
                        promptTokens = 135_880,
                    )
                },
                { _, _ -> assistant(content = "完成", finishReason = "stop", promptTokens = 137_865) },
            )
        )
        val messages = org.json.JSONArray()
        messages.put(AgentConversationCodec.userTextMessage("核对账单"))

        AgentLoop(
            config = modelConfig(),
            messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                AgentModelClient.ToolResult("{\"now\":\"2026-09-12\"}")
            },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = events::add,
            compactPolicy = AgentLoop.CompactPolicy.Disabled,
        ).run()

        val usageEvents = events.filterIsInstance<AgentEvent.UsageReceived>().filterNot { it.projected }
        assertEquals(2, provider.requests.size)
        assertEquals(2, usageEvents.size)
        assertEquals(listOf(1, 2), usageEvents.map { it.round })
        assertEquals(listOf(135_880, 137_865), usageEvents.map { it.usage.inputTokens })
        assertEquals(listOf(1), events.filterIsInstance<AgentEvent.UsageReceived>().filter { it.projected }.map { it.round })
        assertEquals(1, events.filterIsInstance<AgentEvent.ToolFinished>().size)
    }

    @Test
    fun compactSkipsWhenBilledOccupancyIsBelowThreshold() {
        var compactCalls = 0
        val provider = ScriptedProvider(
            responses = listOf(
                { _, _ ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("call-1", "get_current_context", "{}")),
                        promptTokens = 98_263,
                    )
                },
                { _, _ -> assistant(content = "完成", finishReason = "stop", promptTokens = 98_263) },
            )
        )
        val history = (1..6).flatMap { n ->
            listOf(
                AgentConversationCodec.userTextMessage("u$n"),
                AgentConversationCodec.assistantHistoryMessage(
                    assistant(content = "a$n", finishReason = "stop"),
                    emptyList(),
                ),
            )
        }
        val messages = org.json.JSONArray()
        history.forEach { messages.put(it) }
        messages.put(AgentConversationCodec.userTextMessage("现在"))

        val result = AgentLoop(
            config = modelConfig(),
            messages = messages,
            tools = AgentToolCatalog.build(terminalTools = false, browserTools = false),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor {
                AgentModelClient.ToolResult(org.json.JSONObject().put("ok", true).toString())
            },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = {},
            compactPolicy = AgentLoop.CompactPolicy(
                enabled = true,
                contextWindow = 500_000,
                keepRecentMessages = 2,
                compressModelConfig = modelConfig(),
            ),
            compactHistory = { source, policy ->
                compactCalls += 1
                source
            },
        ).run()

        assertEquals("完成", result.content)
        assertEquals(0, compactCalls)
        assertEquals(2, provider.requests.size)
    }

    @Test fun malformedDelegationIsRepairedWithoutExecutingOrReplayingSiblingTools() {
        val events = mutableListOf<AgentEvent>()
        val executed = mutableListOf<String>()
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false).also {
            io.github.mangi.eta.agent.delegation.SubAgentTools.appendTo(it, listOf("worker"))
        }
        val provider = ScriptedProvider(listOf(
            { _, _ -> assistant(finishReason = "tool_calls", toolCalls = listOf(
                toolCall("bad", "delegate_task", "{}"), toolCall("valid", "get_current_context", "{}"))) },
            { request, _ ->
                assertTrue(request.messages.toString().contains("DELEGATION_ARGUMENT_REPAIR"))
                assertTrue(request.messages.toString().contains("task_created"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("repaired", "delegate_task", "{\"task\":\"check evidence\"}")))
            },
            { _, _ -> assistant(content = "完成", finishReason = "stop") },
        ))
        AgentLoop(modelConfig(), JSONArray().put(AgentConversationCodec.userTextMessage("检查")), tools, provider,
            AgentModelClient.ToolExecutor { call -> executed += call.id; AgentModelClient.ToolResult("{\"ok\":true}") },
            AgentRunController(), AgentTraceFormatter(), { events += it }).run()
        assertEquals(listOf("valid", "repaired"), executed)
        assertEquals(1, events.filterIsInstance<AgentEvent.ModelRetryScheduled>().count { it.reasonCode == "DELEGATION_ARGUMENT_REPAIR" })
        assertFalse(events.filterIsInstance<AgentEvent.ToolStarted>().any { it.toolCallId == "bad" })
    }

    @Test fun repeatedMalformedDelegationDisablesOnlyNewDelegationAfterTwoRepairRounds() {
        val events = mutableListOf<AgentEvent>()
        val tools = JSONArray().also { io.github.mangi.eta.agent.delegation.SubAgentTools.appendTo(it, listOf("worker")) }
        val provider = ScriptedProvider(listOf(
            { _, _ -> assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("bad1", "delegate_task", "{}"))) },
            { _, _ -> assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("bad2", "delegate_task", "{}"))) },
            { _, _ -> assistant(finishReason = "tool_calls", toolCalls = listOf(toolCall("bad3", "delegate_task", "{}"))) },
            { request, _ ->
                val names = (0 until request.tools.length()).map { request.tools.getJSONObject(it).getJSONObject("function").getString("name") }
                assertFalse("delegate_task" in names)
                assertTrue("get_task_result" in names)
                assertTrue(request.messages.toString().contains("DELEGATION_ARGUMENT_REPAIR_EXHAUSTED"))
                assistant(content = "主代理接手", finishReason = "stop")
            },
        ))
        AgentLoop(modelConfig(), JSONArray().put(AgentConversationCodec.userTextMessage("检查")), tools, provider,
            AgentModelClient.ToolExecutor { error("Malformed calls must never execute") }, AgentRunController(), AgentTraceFormatter(),
            { events += it }, toolsForRound = { tools }).run()
        assertEquals(2, events.filterIsInstance<AgentEvent.ModelRetryScheduled>().size)
        assertEquals(listOf("bad3"), events.filterIsInstance<AgentEvent.ToolStarted>().map { it.toolCallId })
    }

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
            browserTools = false,
        )

    private fun assistant(
        content: String = "",
        finishReason: String,
        toolCalls: List<JSONObject> = emptyList(),
        reasoning: String = "",
        promptTokens: Int? = null,
    ): JSONObject =
        JSONObject()
            .put("role", "assistant")
            .put("content", content)
            .put("reasoning_content", reasoning)
            .put("finish_reason", finishReason)
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put("tool_calls", JSONArray(toolCalls))
                }
                if (promptTokens != null) {
                    message.put(
                        "usage",
                        JSONObject()
                            .put("prompt_tokens", promptTokens)
                            .put("completion_tokens", 1)
                            .put("total_tokens", promptTokens + 1),
                    )
                }
            }

    private fun toolCall(
        id: String,
        name: String,
        arguments: String,
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("arguments", arguments),
            )

    private fun JSONArray.roleSuffix(count: Int): List<String> =
        ((length() - count) until length()).map { index ->
            getJSONObject(index).getString("role")
        }

    private fun JSONArray.roles(): List<String> =
        (0 until length()).map { index -> getJSONObject(index).getString("role") }

    private fun JSONArray.systemMessages(): Int = roles().count { it == "system" }

    private fun JSONArray.getJSONObjectFromEnd(offset: Int): JSONObject =
        getJSONObject(length() - offset)
}
