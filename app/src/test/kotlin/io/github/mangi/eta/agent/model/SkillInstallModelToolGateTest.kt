package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInstallModelToolGateTest {
    @Test
    fun `github skill tools follow the extensions group, never prompt keywords`() {
        listOf(
            "总结这个仓库",
            "列出可安装的 Skills",
            "帮我安装 GitHub 上的 openai-docs Skill",
            "\$skill-installer linear",
            "翻译这句话：install this Skill",
        ).forEach { prompt ->
            // 第一轮：基础读取常驻，任何提示词都能列出/读取已安装的 Skills。
            val resident = CapturingProvider()
            complete(prompt, resident)
            assertTrue(prompt, "skills_list_curated" in resident.toolNames)
            assertTrue(prompt, "skills_read" in resident.toolNames)
            // GitHub 发现/安装属扩展组，默认延迟：不按关键词出现，也不因关键词缺席。
            assertTrue(prompt, "skills_inspect_github" !in resident.toolNames)

            val opened = CapturingProvider()
            complete(prompt, opened, setOf(AgentToolGroups.EXTENSIONS_GROUP.id))
            assertTrue(prompt, "skills_inspect_github" in opened.toolNames)
            assertTrue(prompt, "skills_install_from_github" in opened.toolNames)
        }
    }

    private fun complete(
        prompt: String,
        provider: CapturingProvider,
        openedToolGroups: Set<String> = emptySet(),
    ) {
        AgentModelClient.complete(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
                browserTools = false,
            ),
            prompt = prompt,
            toolExecutor = AgentModelClient.ToolExecutor {
                error("不应执行工具")
            },
            provider = provider,
            openedToolGroupsProvider = { openedToolGroups },
        )
    }

    private class CapturingProvider : AgentProviderClient {
        override val id: String = "capturing"
        override val capabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = false,
            streamingToolCalls = false,
            imageInput = false,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )
        var toolNames: Set<String> = emptySet()

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            toolNames = request.tools.toolNames()
            return ProviderResponse(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", "完成")
                    .put("finish_reason", "stop"),
            )
        }

        private fun JSONArray.toolNames(): Set<String> =
            (0 until length()).mapTo(mutableSetOf()) { index ->
                getJSONObject(index).getJSONObject("function").getString("name")
            }
    }
}
