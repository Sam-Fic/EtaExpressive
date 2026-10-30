package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptBuilderTest {
    @Test
    fun newUserRunReplacesHistoricalRecoveryRestrictionWithoutDroppingToolHistory() {
        val recovery = JSONArray().also(AgentRecoveryContext::append).getJSONObject(0)
        val history = listOf(
            AgentConversationCodec.fromJsonObject(recovery),
            AgentModelClient.ConversationMessage(role = "user", content = "old task"),
            AgentModelClient.ConversationMessage(role = "tool", content = "committed", toolCallId = "old-call"),
        )
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig("", terminalTools = false, browserTools = false),
            prompt = "new independent task", images = emptyList(), history = history,
            skillContext = SkillContext.EMPTY,
        )
        assertFalse(AgentRecoveryContext.isActive(messages))
        assertTrue(messages.toString().contains("[Eta previous recovery safety]"))
        assertTrue(messages.toString().contains("old-call"))
        assertTrue(messages.toString().contains("committed"))
        assertEquals("new independent task", messages.getJSONObject(messages.length() - 1).getString("content"))
        assertTrue(AgentRecoveryContext.isActive(JSONArray().put(recovery)))
    }

    @Test
    fun generatedSystemPromptDoesNotInjectModelIdentity() {
        for (providerPrompt in listOf("自定义回答风格", "")) {
            val config = modelConfig(providerPrompt, terminalTools = false, browserTools = false)
                .copy(model = "provider/model-a", modelDisplayName = "显示名称")
            for (modelId in listOf(config.model, "provider/model-b", "gpt-6-astra")) {
                val messages = AgentPromptBuilder.buildSystemMessages(
                    config = config.copy(model = modelId),
                    skillContext = SkillContext.EMPTY,
                    memoryContext = AgentMemoryContext.DISABLED,
                    rootAvailable = false,
                )

                val contents = messages.systemContents()
                assertTrue(contents.any { it.contains("以系统提示中的助手人格为准") })
                assertFalse(contents.any { it.contains("当前配置的模型：") })
                assertFalse(contents.any { it.contains("询问所用模型时按当前配置") })
                assertFalse(contents.any { it.contains("模型名称可能是服务商别名") })
                assertFalse(contents.any { it.contains(modelId) || it.contains(config.modelDisplayName) })
                if (providerPrompt.isNotBlank()) {
                    assertEquals(providerPrompt, messages.getJSONObject(0).getString("content"))
                }
            }
        }
    }

    @Test
    fun visionCapabilityWarningRemainsWithoutModelIdentity() {
        for (vision in listOf(false, true)) {
            val contents = AgentPromptBuilder.buildSystemMessages(
                config = modelConfig("", terminalTools = false, browserTools = false)
                    .copy(model = "provider-routing-only", supportsVision = vision),
                skillContext = SkillContext.EMPTY,
                memoryContext = AgentMemoryContext.DISABLED,
                rootAvailable = false,
            ).systemContents()
            assertEquals(!vision, contents.any { it.contains("当前模型未启用图片输入") })
            assertFalse(contents.any { it.contains("provider-routing-only") })
        }
    }


    @Test
    fun screenSwitchDropsGuiInstructionsAndKeepsCoreGuidance() {
        val config = modelConfig("", terminalTools = false, browserTools = false)
        // 本文件断言的是提示内容，与分层加载无关：所有组一律视为已打开，
        // 否则延迟组的指引根本不会出现，断言会全部落空。
        val on = AgentPromptBuilder.buildSystemMessages(
            config = config.copy(screenTools = true),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = false,
            openedToolGroups = AgentToolGroups.allIds,
        ).systemContents()
        val off = AgentPromptBuilder.buildSystemMessages(
            config = config.copy(screenTools = false),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = false,
            openedToolGroups = AgentToolGroups.allIds,
        ).systemContents()

        assertTrue(on.any { it.contains("需要看屏幕时") })
        assertFalse(off.any { it.contains("需要看屏幕时") })
        assertFalse(off.any { it.contains("observe_screen") })
        assertTrue(off.any { it.contains("用户询问你的身份时") })
        assertTrue(off.any { it.contains("get_current_context") })
        assertTrue(off.sumOf { it.length } < on.sumOf { it.length })
    }

    @Test
    fun guidanceFollowsTheGroupThatMakesItActionable() {
        // 常驻提示里每条可执行指令都点名了某个工具组。组没打开时那条指令无从执行，
        // 留着只是白付 token（副屏交接约 390、Root 只读检查约 130、习惯分析约 70）。
        fun contents(
            terminalTools: Boolean = false,
            personalTools: Boolean = false,
            rootAvailable: Boolean = false,
            opened: Set<String> = emptySet(),
        ) = AgentPromptBuilder.buildSystemMessages(
            config = modelConfig("", terminalTools = terminalTools, browserTools = false)
                .copy(personalTools = personalTools),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = rootAvailable,
            openedToolGroups = opened,
        ).systemContents()

        // 副屏与自身交接规则：全部指令都指向屏幕组工具。
        val screenClosed = contents()
        assertFalse(screenClosed.any { it.contains("task_surface_scope=self_app") })
        assertFalse(screenClosed.any { it.contains("start_virtual_session") })
        val screenOpened = contents(opened = setOf(AgentToolGroups.SCREEN_GROUP.id))
        assertTrue(screenOpened.any { it.contains("task_surface_scope=self_app") })

        // Root 只读检查：没有终端/文件工具在场时它是空承诺；有终端才给。
        assertFalse(contents(rootAvailable = true).any { it.contains("相关应用私有文件与数据库") })
        val rootWithShell = contents(
            terminalTools = true,
            rootAvailable = true,
            opened = setOf(AgentToolGroups.TERMINAL_GROUP.id),
        )
        assertTrue(rootWithShell.any { it.contains("相关应用私有文件与数据库") })
        // 没有 Root 时的否定说法始终在场，与终端组无关：那是不许绕路的约束。
        assertTrue(contents(rootAvailable = false).any { it.contains("不要尝试 su") })

        // 习惯分析：跟着个人数据组走。
        assertFalse(contents().any { it.contains("不根据零散记录断言用户的性格") })
        val personal = contents(
            personalTools = true,
            opened = setOf(AgentToolGroups.PERSONAL_GROUP.id),
        )
        assertTrue(personal.any { it.contains("不根据零散记录断言用户的性格") })
    }

    @Test
    fun messagesKeepSystemHistoryAndCurrentImageInputInStableOrder() {
        val image = AgentModelClient.ModelImage(
            reference = "data:image/png;base64,AA==",
            mimeType = "image/png",
            bytes = 1,
        )
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig(
                systemPrompt = "自定义系统约束",
                terminalTools = true,
                browserTools = false,
            ),
            prompt = "当前问题",
            images = listOf(image),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "旧问题"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "旧回答"),
            ),
            skillContext = SkillContext.EMPTY,
            rootAvailable = true,
            openedToolGroups = AgentToolGroups.allIds,
        )

        assertEquals(
            listOf("system", "system", "system", "system", "system", "system", "user", "assistant", "user"),
            messages.roles(),
        )
        assertEquals("自定义系统约束", messages.getJSONObject(0).getString("content"))
        assertTrue(messages.systemContents().any { it.contains("只有系统保护后端可用时才会请求有限重绑") })
        assertTrue(messages.systemContents().any { it.contains("不要改用坐标或 Shell 重放") })
        assertTrue(messages.systemContents().any { it.contains("通用 GUI 工具完成输入和点击发送") })
        assertTrue(messages.systemContents().any { it.contains("不追加二次确认") })
        assertTrue(messages.systemContents().any { it.contains("立即调用工具") })
        assertTrue(messages.systemContents().any { it.contains("不要先输出计划、解释或中间进度") })
        assertTrue(messages.systemContents().any { it.contains("不要为了展示思考而拆成多个回合") })
        assertFalse(messages.systemContents().any { it.contains("默认优先并行委派多个") })
        assertFalse(messages.systemContents().any { it.contains("本轮已公开子代理") })
        assertFalse(messages.systemContents().any { it.contains("同文件写冲突") })
        assertTrue(messages.systemContents().any { it.contains("不要例行调用 observe_screen") })
        assertTrue(messages.systemContents().any { it.contains("读取或汇总屏幕信息") })
        assertTrue(messages.systemContents().any { it.contains("确认最终结果") })
        assertTrue(messages.systemContents().any { it.contains("后续操作依赖特定文本或应用出现") })
        assertFalse(messages.systemContents().any { it.contains("点击或打开应用后优先用 wait_for_text") })
        assertTrue(messages.systemContents().any { it.contains("只读取 UI 树，不附截图") })
        assertTrue(messages.systemContents().any { it.contains("include_screenshot=true") })
        assertTrue(messages.systemContents().any { it.contains("保持 include_ui_tree=true") })
        assertTrue(messages.systemContents().any { it.contains("禁止把新截图与旧节点混用") })
        assertTrue(messages.systemContents().any { it.contains("不要仅因截断请求截图") })
        assertTrue(messages.systemContents().any { it.contains("主动调用当前已公开的只读工具获取证据") })
        assertTrue(messages.systemContents().any { it.contains("工具已向你公开表示对应能力已由用户开启") })
        assertTrue(messages.systemContents().any { it.contains("从多个相关来源按时间和代表性取样") })
        assertTrue(messages.systemContents().any { it.contains("系统记忆") })
        // 所有组（含扩展组）都已打开时，没有专用工具读取数据应优先自写配方，
        // 而不是让模型用 Shell 逐轮试探格式；终端分支此时刻意让位。
        assertTrue(messages.systemContents().any { it.contains("优先用 eta_author_tool 写一个配方") })
        assertTrue(messages.systemContents().any { it.contains("不要用 Shell 逐轮试探文件格式或数据库 schema") })
        assertTrue(messages.systemContents().any { it.contains("合法且克制的 GitHub Flavored Markdown") })
        assertTrue(messages.systemContents().any { it.contains("不用整句粗体冒充标题") })
        assertTrue(messages.systemContents().any { it.contains("表格前后留空行") })
        assertTrue(messages.systemContents().any { it.contains("只给出文件名、路径文字或用表格列出文件都不算展示") })
        assertTrue(messages.systemContents().any { it.contains("同样用 Markdown 链接语法 [文件名](绝对路径) 给出") })
        assertTrue(messages.getJSONObject(2).getString("content").contains("open_and_exec"))
        assertTrue(messages.getJSONObject(2).getString("content").contains("同一轮模型回复最多调用一次 read_image"))
        assertTrue(messages.getJSONObject(2).getString("content").contains("调用 read_image 只是让你自己看到画面"))
        assertTrue(messages.getJSONObject(2).getString("content").contains("read_image 可直接读取 Linux 的 /workspace"))
        assertTrue(messages.getJSONObject(2).getString("content").contains("再在下一轮调用下一张"))
        assertFalse(messages.systemContents().any { it.contains("网页浏览、读取") })
        assertTrue(messages.systemContents().any { it.contains("持久记忆已关闭") })
        assertTrue(messages.systemContents().any { it.contains("当前助手未开启 Skills") })
        assertEquals("旧问题", messages.getJSONObject(6).getString("content"))
        assertEquals("旧回答", messages.getJSONObject(7).getString("content"))

        val currentContent = messages.getJSONObject(8).getJSONArray("content")
        assertEquals("当前问题", currentContent.getJSONObject(0).getString("text"))
        assertEquals(
            image.reference,
            currentContent.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun legacyOnDeviceActionCallIsStrippedWithoutBreakingOtherToolPairs() {
        val mixedCalls = """
            [
              {"id":"call_retired","type":"function","function":{"name":"device_actions","arguments":"{\"instruction\":\"turn on the flashlight\"}"}},
              {"id":"call_alarm","type":"function","function":{"name":"set_alarm","arguments":"{\"hour\":7,\"minute\":0}"}}
            ]
        """.trimIndent()
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig("", terminalTools = false, browserTools = false),
            prompt = "继续",
            images = emptyList(),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "开灯并设闹钟"),
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "",
                    toolCallsJson = mixedCalls,
                ),
                AgentModelClient.ConversationMessage(
                    role = "tool",
                    toolCallId = "call_retired",
                    content = """{"ok":true}""",
                ),
                AgentModelClient.ConversationMessage(
                    role = "tool",
                    toolCallId = "call_alarm",
                    content = """{"ok":true}""",
                ),
                AgentModelClient.ConversationMessage(role = "assistant", content = "完成"),
            ),
            skillContext = SkillContext.EMPTY,
            rootAvailable = false,
        )

        val serialized = messages.toString()
        assertFalse(serialized.contains("device_actions"))
        assertFalse(serialized.contains("turn on the flashlight"))
        assertEquals(
            listOf("system", "system", "system", "system", "user", "assistant", "tool", "assistant", "user"),
            messages.roles(),
        )
        val remainingCalls = messages.getJSONObject(5).getJSONArray("tool_calls")
        assertEquals(1, remainingCalls.length())
        assertEquals(
            "set_alarm",
            remainingCalls.getJSONObject(0).getJSONObject("function").getString("name"),
        )
        assertEquals("call_alarm", messages.getJSONObject(6).getString("tool_call_id"))
    }

    @Test
    fun legacyOnDeviceActionOnlyTurnDisappearsFromModelContext() {
        val retiredCall = """
            [{"id":"call_retired","type":"function","function":{"name":"device_actions","arguments":"{\"instruction\":\"turn on the flashlight\"}"}}]
        """.trimIndent()
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig("", terminalTools = false, browserTools = false),
            prompt = "继续",
            images = emptyList(),
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "打开手电筒"),
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "",
                    toolCallsJson = retiredCall,
                ),
                AgentModelClient.ConversationMessage(
                    role = "tool",
                    toolCallId = "call_retired",
                    content = """{"ok":true}""",
                ),
                AgentModelClient.ConversationMessage(role = "assistant", content = "已打开"),
            ),
            skillContext = SkillContext.EMPTY,
            rootAvailable = false,
        )

        assertFalse(messages.toString().contains("device_actions"))
        assertEquals(
            listOf("system", "system", "system", "system", "user", "assistant", "user"),
            messages.roles(),
        )
        assertEquals("已打开", messages.getJSONObject(5).getString("content"))
    }

    @Test
    fun delegationRuleIsASeparateMessageAheadOfOperationalGuidance() {
        fun systems(delegation: Boolean, engaged: Boolean) = AgentPromptBuilder.buildSystemMessages(
            config = modelConfig("", terminalTools = true, browserTools = false),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext.DISABLED,
            rootAvailable = false,
            delegationAvailable = delegation,
            delegationEngaged = engaged,
            openedToolGroups = AgentToolGroups.allIds,
        )
        val without = systems(delegation = false, engaged = false)
        assertFalse(without.systemContents().any { it.contains("本轮已公开子代理") })
        // 结果解读规则跟着委派走：没有子代理时它一条也不该出现，即使 engaged 被误置为 true。
        assertEquals(without.length(), systems(delegation = false, engaged = true).length())

        val messages = systems(delegation = true, engaged = false)
        val contents = messages.systemContents()
        val delegationAt = contents.indexOfFirst { it.contains("本轮已公开子代理") }
        val screenAt = contents.indexOfFirst { it.contains("需要看屏幕时") }
        assertTrue(delegationAt >= 0 && screenAt > delegationAt)
        val rule = contents[delegationAt]
        assertTrue(rule.contains("不能据此把源码阅读也留在主代理"))
        assertTrue(rule.contains("多文件调查不是琐碎任务"))
        assertTrue(rule.contains("必须在同一轮并行调用 delegate_task"))
        assertFalse(rule.contains("需要看屏幕时"))
        assertEquals(without.length() + 1, messages.length())
        assertTrue(contents.any { it.contains("不要用本条终端要求") })
        assertFalse(contents.any { it.contains("默认优先并行委派多个") })

        // 派发之前不付「结果解读」的 token：后台继续语义、分页、验收与错误码一条都不进前缀。
        for (clause in RESULT_RULE_PROBES) {
            assertFalse("派发前不该出现：$clause", contents.any { it.contains(clause) })
        }

        val engagedMessages = systems(delegation = true, engaged = true)
        assertEquals(messages.length() + 1, engagedMessages.length())
        val engagedContents = engagedMessages.systemContents()
        val resultAt = engagedContents.indexOfFirst { it.contains("已经派出的子代理适用以下结果规则") }
        assertEquals(delegationAt + 1, resultAt)
        val resultRule = engagedContents[resultAt]
        RESULT_RULE_PROBES.forEach { assertTrue("结果规则缺：$it", resultRule.contains(it)) }
        // 调度规则本身不因派发而改变，否则前缀会每轮漂移、prompt 缓存全部失效。
        assertEquals(rule, engagedContents[delegationAt])
        assertFalse(resultRule.contains("本轮已公开子代理"))
        assertTrue(engagedContents.indexOfFirst { it.contains("需要看屏幕时") } > resultAt)
    }

    @Test
    fun browserAndSkillMessagesAreConditionalAndStructurallyComplete() {
        val skill = SkillIndexEntry(
            id = "screen-audit",
            name = "屏幕审计",
            description = "  检查屏幕\n并输出   结论  ",
            rootPath = "/skills/screen-audit",
            skillFilePath = "/skills/screen-audit/SKILL.md",
            hasScripts = true,
            hasReferences = false,
            hasAssets = true,
            hasEvals = false,
        )
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig(
                systemPrompt = "",
                terminalTools = false,
                browserTools = true,
            ),
            prompt = "读取网页",
            images = emptyList(),
            history = emptyList(),
            skillContext = SkillContext(installedSkills = listOf(skill)),
            openedToolGroups = AgentToolGroups.allIds,
        )

        // 系统提示的条数随工具族增加而变（网页搜索/正文读取是一段独立提示），
        // 这里只钉住「除最后一条 user 外全是 system」这一结构约束。
        assertEquals("user", messages.roles().last())
        assertTrue(messages.roles().dropLast(1).all { it == "system" })
        assertTrue(messages.roles().size >= 4)
        val systemContents = messages.systemContents()
        assertTrue(systemContents.any { it.contains("browser_use") })
        assertTrue(systemContents.any { it.contains("desktop_chrome") })
        assertTrue(systemContents.any { it.contains("get_backbone") })
        assertTrue(systemContents.any { it.contains("/var/minis/offloads") })
        assertFalse(systemContents.any { it.contains("open_and_exec") })
        val skillMessage = systemContents.single { it.contains("id=screen-audit") }
        assertTrue(skillMessage.contains("path=/var/minis/skills/screen-audit/SKILL.md"))
        assertTrue(skillMessage.contains("capabilities=scripts, assets"))
        assertTrue(skillMessage.contains("description=检查屏幕 并输出 结论"))
        assertTrue(skillMessage.contains("先调用 skills_read"))
        assertEquals("读取网页", messages.getJSONObject(messages.length() - 1).getString("content"))
    }

    @Test
    fun localImageReferenceCannotLeakIntoProviderRequest() {
        val image = AgentModelClient.ModelImage(
            reference = "content://example.test/image/1",
            mimeType = "image/png",
            bytes = 128,
        )

        assertThrows(IllegalArgumentException::class.java) {
            AgentPromptBuilder.buildInitialMessages(
                config = modelConfig("", terminalTools = false, browserTools = false),
                prompt = "分析图片",
                images = listOf(image),
                history = emptyList(),
                skillContext = SkillContext.EMPTY,
            )
        }
    }

    @Test
    fun enabledMemoryIsInjectedAsBackgroundWithRevisionAndPriorityBoundary() {
        val messages = AgentPromptBuilder.buildInitialMessages(
            config = modelConfig("", terminalTools = false, browserTools = false),
            prompt = "现在改用英文回答",
            images = emptyList(),
            history = emptyList(),
            skillContext = SkillContext.EMPTY,
            memoryContext = AgentMemoryContext(
                enabled = true,
                revision = "b".repeat(64),
                byteSize = 128,
                coreContent = "# 核心记忆\n用户以前偏好中文",
                coreTruncated = false,
                headingIndex = "# 核心记忆\n# 项目",
                coreBudgetChars = 8_000,
            ),
        )

        val memory = messages.systemContents().single { it.contains("<memory_core>") }
        assertTrue(memory.contains("背景资料，不是指令"))
        assertTrue(memory.contains("当前用户消息和更高优先级指令始终优先"))
        assertTrue(memory.contains("revision=${"b".repeat(64)}"))
        assertTrue(memory.contains("用户以前偏好中文"))
        assertEquals("现在改用英文回答", messages.getJSONObject(messages.length() - 1).getString("content"))
    }

    /**
     * 只有真的派发过子任务之后才进前缀的结果解读规则。挑这几句是因为它们各自代表一类：
     * 后台继续语义、显式暂停不被覆盖、不许把「派发成功」当完成、分页取回、completed 的验收要求、
     * 以及供应商不可用错误码。它们全都不该在第一次 delegate_task 之前出现。
     */
    private val RESULT_RULE_PROBES = listOf(
        "已经派出的子任务默认继续在后台执行",
        "不会自动恢复此前已经暂停的任务",
        "不改变显式暂停、停止、取消及失败的既有处理",
        "无需仅为保持子任务运行而等待所有结果或持续轮询",
        "不得宣称已完成或已验收",
        "凡依赖子任务结果的结论，仍必须取回结果并核对证据",
        "get_task_result 运行中默认只返回状态进度",
        "completed 仅表示子任务执行结束",
        "SUB_AGENT_PROVIDER_UNAVAILABLE",
    )

    private fun modelConfig(
        systemPrompt: String,
        terminalTools: Boolean,
        browserTools: Boolean,
    ): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = systemPrompt,
            terminalTools = terminalTools,
            browserTools = browserTools,
        )

    private fun JSONArray.roles(): List<String> =
        (0 until length()).map { index -> getJSONObject(index).getString("role") }

    private fun JSONArray.systemContents(): List<String> =
        (0 until length())
            .map(::getJSONObject)
            .filter { message -> message.getString("role") == "system" }
            .map { message -> message.getString("content") }
}
