package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCapabilityProjectionTest {
    private fun card(id: String) = ToolItemUi(id, id, id)

    private fun capability(id: String, tools: List<ToolItemUi>) = ToolCapabilityUi(
        id = id,
        title = id,
        summary = id,
        tools = tools,
        switchKey = Prefs.Keys.AGENT_DEVICE_TOOLS,
    )

    @Test fun currentDeviceRetainsPartialCapabilitiesAndHidesRootOnlyTools() {
        val capabilities = listOf(
            capability("device", listOf(card("terminal"), card("wifi_credentials"), card("observe_screen"))),
        )
        assertEquals(listOf("terminal", "observe_screen"),
            projectToolCapabilities(capabilities, false, false, true).single().tools.map { it.id })
        assertEquals(capabilities, projectToolCapabilities(capabilities, true, false, true))
        assertEquals(capabilities, projectToolCapabilities(capabilities, false, true, true))
    }

    @Test fun viewingAllDoesNotMutateSourceOrChangeToolOrder() {
        val capabilities = listOf(capability("root", listOf(card("wifi_credentials"))))
        assertTrue(projectToolCapabilities(capabilities, false, false, false).isEmpty())
        assertEquals(capabilities, projectToolCapabilities(capabilities, true, false, false))
        assertTrue(projectToolCapabilities(capabilities, false, false, false).isEmpty())
    }

    @Test fun browserCardsReferToTheRealBrowserTool() {
        listOf("browser_use", "browser_read", "browser_interact", "browser_screenshot").forEach { id ->
            assertEquals("browser_use", actualToolName(id))
            assertEquals(AgentToolRequirements.find("browser_use"), toolCardRequirement(id))
        }
    }

    @Test fun ordinaryPermissionsDoNotHideDiscoverableTools() {
        assertTrue(visibleOnCurrentDevice("observe_screen", false, false))
        assertTrue(visibleOnCurrentDevice("search_notification_history", false, false))
        assertEquals(RootRequirement.PARTIAL, toolCardRequirement("terminal").rootRequirement)
        assertFalse(visibleOnCurrentDevice("search_coloros_memories", true, false))
    }

    @Test fun missingOrdinaryPermissionOpensPermissionsBeforePartialRootBenefits() {
        val capabilities = AgentToolCapabilities(rootAvailable = false, notificationsAllowed = false)
        assertEquals(AgentToolsAction.OpenPermissions, toolCardAction("recent_notifications", capabilities))
        assertEquals(AgentToolsAction.OpenEnhancements, toolCardAction("terminal", capabilities))
        assertEquals(AgentToolsAction.OpenBrowser, toolCardAction("browser_read", capabilities))
        assertEquals(AgentToolsAction.OpenWebToolsSettings, toolCardAction("web_search", capabilities))
        assertEquals(AgentToolsAction.OpenWebToolsSettings, toolCardAction("fetch_url", capabilities))
        assertEquals(AgentToolsAction.OpenEnhancements,
            toolCardAction("search_coloros_memories", capabilities.copy(rootAvailable = true, colorOs = false)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownCardCannotSilentlyAcquireCapabilityDefaults() {
        toolCardRequirement("unknown")
    }
}
