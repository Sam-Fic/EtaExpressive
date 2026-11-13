package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.components.FrameDiagnosticPage
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.NewProviderType
import org.junit.Assert.*
import org.junit.Test

class FrameDiagnosticRouteTest {
    @Test fun settingsAndSubPagesHaveDistinctStableLabels() {
        assertEquals(FrameDiagnosticPage.Settings, AppRoute.Settings.frameDiagnosticPage())
        assertEquals(FrameDiagnosticPage.SubAgents, AppRoute.SubAgents.frameDiagnosticPage())
        assertEquals(FrameDiagnosticPage.McpServerAdd, AppRoute.McpServerAdd.frameDiagnosticPage())
        assertEquals(FrameDiagnosticPage.ModelProviderModelEdit, AppRoute.ModelProviderModelEdit("p", "m", true).frameDiagnosticPage())
        assertEquals(FrameDiagnosticPage.ToolRecipeEdit, AppRoute.ToolRecipeEdit("recipe").frameDiagnosticPage())
    }

    @Test fun routeArgumentsNeverReachDiagnosticLabels() {
        val secret = "private-id-token-url-name"
        val routes = listOf(
            AppRoute.LinuxFiles(secret), AppRoute.McpServerDetail(secret),
            AppRoute.ModelProviderDetail(secret), AppRoute.AssistantEdit(secret),
            AppRoute.ModelProviderNew(NewProviderType.OpenAiCompatible, secret),
            AppRoute.ModelProviderModelEdit("provider", secret, true),
            AppRoute.ToolRecipeEdit(secret),
        )
        routes.forEach { route ->
            assertFalse(route.frameDiagnosticPage().name.contains(secret))
            assertNotEquals(FrameDiagnosticPage.Unknown, route.frameDiagnosticPage())
        }
        assertEquals(AppRoute.ModelProviderDetail("a").frameDiagnosticPage(),
            AppRoute.ModelProviderDetail("b").frameDiagnosticPage())
        assertEquals(AppRoute.ModelProviderModelEdit("a", "b", true).frameDiagnosticPage(),
            AppRoute.ModelProviderModelEdit("c", "d", false).frameDiagnosticPage())
        assertEquals(AppRoute.ToolRecipeEdit("a").frameDiagnosticPage(),
            AppRoute.ToolRecipeEdit("b").frameDiagnosticPage())
    }

    @Test fun legacyRoutesDescribeTheScreenActuallyRendered() {
        assertEquals(FrameDiagnosticPage.AgentTaskPreference, AppRoute.VirtualDisplayRecovery.frameDiagnosticPage())
        listOf(AppRoute.SpeechSettings, AppRoute.TtsSettings, AppRoute.VoiceModeSettings).forEach {
            assertEquals(FrameDiagnosticPage.VoiceSettings, it.frameDiagnosticPage())
        }
    }
}
