package io.github.mangi.eta.ui.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.TravelExplore
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.agent.tool.ToolPresentation
import io.github.mangi.eta.ui.model.projectToolGroups
import io.github.mangi.eta.ui.model.toolCardRequirement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolCatalogUiTest {
    @Test
    fun everyRuntimeToolAndDisplayedCardHasASpecificIcon() {
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceTools = true,
            personalTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
            openedToolGroups = AgentToolGroups.allIds,
        )
        val runtimeNames = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }
        val cardIds = buildToolsState(RuntimeEnvironment.getApplication()).groups
            .flatMap { it.tools }.map { it.id }
        (runtimeNames + cardIds).distinct().forEach { name ->
            assertNotEquals(name, Icons.Rounded.Build, ToolPresentation.icon(name))
        }
    }

    @Test
    fun hostedSearchNamesAndDynamicMcpNamesUseTheirToolIcons() {
        assertEquals(Icons.Rounded.TravelExplore, ToolPresentation.icon("网页搜索"))
        assertEquals(ToolPresentation.icon("网页搜索"), ToolPresentation.icon("web_search"))
        assertEquals(Icons.Rounded.Language, ToolPresentation.icon("browser_use"))
        assertEquals(Icons.Rounded.Extension, ToolPresentation.icon("mcp_server_search_012345"))
        assertEquals(Icons.Rounded.Build, ToolPresentation.icon("unknown_tool"))
    }

    @Test
    fun subAgentActionsHaveTheirOwnIconDistinctFromMemory() {
        val icon = io.github.mangi.eta.ui.icons.SubAgents
        listOf("delegate_task", "get_task_result", "cancel_task", "manage_agent_workspace").forEach {
            assertEquals(icon, ToolPresentation.icon(it))
            assertNotEquals(ToolPresentation.icon("memory_get"), ToolPresentation.icon(it))
        }
        // SubAgents 已有意改为官方 AccountTree 别名（见 ui/icons/SubAgents.kt 文档注释）。
        assertEquals(Icons.Rounded.AccountTree, icon)
    }

    @Test
    fun everyDisplayedCardHasExplicitMetadataAndKeepsItsOriginalOrder() {
        val groups = buildToolsState(RuntimeEnvironment.getApplication()).groups
        val allCards = groups.flatMap { it.tools }
        allCards.forEach { toolCardRequirement(it.id) }
        assertEquals(allCards.size, allCards.map { it.id }.distinct().size)
        assertEquals(groups, projectToolGroups(groups, true, false, false))
        assertEquals(groups, projectToolGroups(groups, false, true, true))

        val ordinaryCards = projectToolGroups(groups, false, false, false).flatMap { it.tools }
        assertTrue(ordinaryCards.isNotEmpty())
        assertEquals(allCards.filter { card ->
            val requirement = toolCardRequirement(card.id)
            requirement.rootRequirement != RootRequirement.REQUIRED && !requirement.colorOs
        }, ordinaryCards)
    }
}
