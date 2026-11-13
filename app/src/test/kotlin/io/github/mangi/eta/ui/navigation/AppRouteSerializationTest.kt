package io.github.mangi.eta.ui.navigation

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class AppRouteSerializationTest {
    @Test
    fun allRoutesRoundTripWithNavJsonConfiguration() {
        val routes = listOf<AppRoute>(
            AppRoute.Home,
            AppRoute.Browser,
            AppRoute.Terminal,
            AppRoute.Tools,
            AppRoute.Haptics,
            AppRoute.Skills,
            AppRoute.Permissions,
            AppRoute.SystemEnhance,
            AppRoute.Settings,
            AppRoute.AppearanceSettings,
            AppRoute.DataBackup,
            AppRoute.Memory,
            AppRoute.AssistantMemory("assistant"),
            AppRoute.LinuxEnvironment,
            AppRoute.SharedFolders,
            AppRoute.SharedFolderPicker,
            AppRoute.Workspace,
            AppRoute.DoubaoVoices,
            AppRoute.MimoVoices,
            AppRoute.LinuxFiles("alpine"),
            AppRoute.ModelProviders,
            AppRoute.McpServers,
            AppRoute.McpServerDetail("mcp-server"),
            AppRoute.ToolRecipes,
            AppRoute.ModelProviderDetail("provider"),
            AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible),
            AppRoute.ModelProviderNew(NewProviderType.Anthropic),
            AppRoute.ModelProviderNew(NewProviderType.DoubaoSpeech),
            AppRoute.ModelProviderNew(NewProviderType.CompatibleSpeech),
            AppRoute.SubAgents,
            AppRoute.SubAgentPresetDetail("preset"),
            AppRoute.McpServerAdd,
            AppRoute.ModelProviderModelEdit("provider", "model", true),
            AppRoute.ModelProviderModelEdit("provider", "", false),
            AppRoute.ToolRecipeEdit("recipe"),
        )
        val json = Json { ignoreUnknownKeys = true }

        val encoded = json.encodeToString(routes)

        assertEquals(routes, json.decodeFromString<List<AppRoute>>(encoded))
    }
}
