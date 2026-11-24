package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "zh-rCN-w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubAgentMaterialControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedMenuRowUsesOfficialSelectableItemWithCheckLeadingIcon() {
        var clicked = false
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Column(Modifier.width(220.dp)) {
                    SubAgentSelectionItem("复杂任务", true, 0, 2) { clicked = true }
                    SubAgentSelectionItem("简单任务", false, 1, 2) { }
                }
            }
        }
        compose.onNode(isSelectable() and hasText("复杂任务")).assertIsSelected()
        compose.onNode(isSelectable() and hasText("简单任务")).assertIsNotSelected()
        // 官方 SelectableDropdownMenuItem 承担选中态：对勾前导图标是装饰性的，不再挂无障碍标签。
        compose.onNodeWithContentDescription("当前分工").assertDoesNotExist()
        // 选中容器色由官方组件从主题取，不再是自绘的 tertiaryContainer：只锁定「选中行与未选中行底色不同」。
        val selected = compose.onNode(isSelectable() and hasText("复杂任务")).captureToImage().toPixelMap()
        val other = compose.onNode(isSelectable() and hasText("简单任务")).captureToImage().toPixelMap()
        assertNotEquals(
            other[other.width - 12, other.height / 2],
            selected[selected.width - 12, selected.height / 2],
        )
        compose.onNode(isSelectable() and hasText("复杂任务")).performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }

    @Test fun externalSettingsShowsDisabledTierOnlyForImplementation() {
        val role = mutableStateOf("implementation")
        compose.setContent {
            MaterialTheme {
                SubAgentProfileRow(SubAgentProfile("test", "测试代理", role = role.value), emptyList(), settings = true)
            }
        }
        for (next in listOf("implementation", "review", "image_generation", "video_generation", "implementation")) {
            compose.runOnIdle { role.value = next }
            if (next == "implementation") {
                compose.onNodeWithContentDescription("设置 测试代理 的任务分工").assertExists().assertIsNotEnabled()
                compose.onNodeWithText("任务分工").assertExists()
                compose.onNodeWithText("未设置分工").assertExists()
            } else {
                compose.onNodeWithContentDescription("设置 测试代理 的任务分工").assertDoesNotExist()
                compose.onNodeWithText("任务分工").assertDoesNotExist()
                compose.onNodeWithText("未设置分工").assertDoesNotExist()
            }
            compose.onNodeWithContentDescription("为 测试代理 选择职责").assertExists()
            compose.onNodeWithContentDescription("设置测试代理并行上限").assertExists().assertIsNotEnabled()
        }
    }

    @Test fun settingsTierMenuClosesOnRoleChangeAndDisableAndRejectsRetainedSelection() {
        val profile = SubAgentProfile("test", "测试代理", tier = SubAgentTaskTier.COMPLEX)
        val fixture = SubAgentUiFixture(profiles = listOf(profile))
        val enabled = mutableStateOf(true)
        compose.setSubAgentContent(fixture) {
            val current = (fixture.editor.observe() as? SubAgentEditorState.Loaded)?.config?.profiles?.single() ?: profile
            SubAgentProfileRow(current, emptyList(), enabled = enabled.value, settings = true)
        }
        compose.onNodeWithContentDescription("设置 测试代理 的任务分工").performClick()
        val retainedSelection = compose.onNode(isSelectable() and hasText("简单任务")).captureSelectableOnClick()
        compose.runOnIdle { fixture.editor.updateProfile(profile.id) { it.withRole("review") } }
        compose.onNodeWithText("简单任务").assertDoesNotExist()
        compose.onNodeWithContentDescription("设置 测试代理 的任务分工").assertDoesNotExist()
        compose.runOnIdle {
            val before = fixture.snapshot()
            retainedSelection()
            assertEquals(before, fixture.snapshot())
            fixture.editor.updateProfile(profile.id) { it.withRole("implementation") }
        }
        compose.onNodeWithContentDescription("设置 测试代理 的任务分工").assert(hasText("未设置分工")).performClick()
        val disabledSelection = compose.onNode(isSelectable() and hasText("常规任务")).captureSelectableOnClick()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithText("常规任务").assertDoesNotExist()
        compose.onNodeWithContentDescription("设置 测试代理 的任务分工").assertIsNotEnabled()
        compose.runOnIdle {
            val before = fixture.snapshot()
            val revision = fixture.repository.revision(fixture.owner).value
            disabledSelection()
            assertEquals(before, fixture.snapshot())
            assertEquals(revision, fixture.repository.revision(fixture.owner).value)
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun SemanticsNodeInteraction.captureSelectableOnClick(): () -> Unit {
        // A semantics click wraps an attached Modifier node (including click sound).
        // Retain the actual selectable callback to test stale application choices.
        val element = fetchSemanticsNode().layoutInfo.getModifierInfo()
            .map { it.modifier }
            .single { it.javaClass.name == "androidx.compose.foundation.selection.SelectableElement" }
        return element.javaClass.getDeclaredField("onClick")
            .apply { isAccessible = true }
            .get(element) as () -> Unit
    }

    @Test fun nonImplementationConversationLabelHasNoTierMenu() {
        val role = mutableStateOf("review")
        compose.setContent {
            MaterialTheme { SubAgentProfileRow(SubAgentProfile("test", "测试代理", role = role.value), emptyList()) }
        }
        for (next in listOf("review", "image_generation", "video_generation")) {
            compose.runOnIdle { role.value = next }
            compose.onNodeWithText("测试代理").assertHasClickAction()
            compose.onNodeWithContentDescription("设置 测试代理 的任务分工").assertDoesNotExist()
            compose.onNodeWithText("未设置分工").assertDoesNotExist()
        }
    }

    @Test fun selectedTierIsMarkedAndDisabledControlClosesMenu() {
        val enabled = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                SubAgentTaskTierButton("执行", SubAgentTaskTier.COMPLEX, enabled.value, {}, Modifier.width(200.dp))
            }
        }
        compose.onNode(hasText("执行") and hasText("复杂任务")).performClick()
        compose.onNode(isSelectable() and hasText("复杂任务")).assertIsSelected()
        compose.onNodeWithContentDescription("当前分工").assertDoesNotExist()
        compose.runOnIdle { enabled.value = false }
        compose.onNode(isSelectable() and hasText("复杂任务")).assertDoesNotExist()
        compose.onNode(hasText("执行") and hasText("复杂任务")).assertIsNotEnabled()
    }
    @Test fun modelPickerGroupsByProviderAndCommitsOnlyOnConfirm() {
        var selectedModelId: String? = null
        var cleared = false
        var dismissed = false
        val model = io.github.mangi.eta.ui.model.AgentModelOptionUi("m", "p", "测试提供商", "openai", "m", "测试模型", 10000)
        val nextModel = io.github.mangi.eta.ui.model.AgentModelOptionUi("m-next", "p-next", "下一提供商", "openai", "m-next", "下一模型", 10000)
        val picker = io.github.mangi.eta.ui.model.AgentModelPickerUiState(
            providerGroups = listOf(
                io.github.mangi.eta.ui.model.AgentModelProviderGroupUi("p", "测试提供商", "openai", listOf(model)),
                io.github.mangi.eta.ui.model.AgentModelProviderGroupUi("p-next", "下一提供商", "openai", listOf(nextModel)),
            ),
            selectedModel = model)
        val resources = org.robolectric.RuntimeEnvironment.getApplication().resources
        val confirmText = resources.getString(io.github.mangi.eta.R.string.action_confirm)
        val cancelText = resources.getString(io.github.mangi.eta.R.string.action_cancel)
        val noneText = resources.getString(io.github.mangi.eta.R.string.tts_none)
        compose.setContent {
            MaterialTheme {
                io.github.mangi.eta.ui.TtsModelPickerDialog(
                    picker, true, { dismissed = true }, { _, id -> selectedModelId = id }, "选择模型",
                    onClearSelection = { cleared = true })
            }
        }
        // 提供商作分组标题平铺；模型为常显单选行，当前绑定模型默认选中。
        compose.onNodeWithText("测试提供商").assertExists()
        compose.onNodeWithText("下一提供商").assertExists()
        compose.onNode(isSelectable() and hasText("测试模型")).assertIsSelected()
        compose.onNode(hasText(noneText)).assertExists()
        // 点选其它模型仅暂存，确认后才提交。
        compose.onNode(hasText("下一模型")).performClick()
        compose.runOnIdle { assertNull(selectedModelId) }
        compose.onNodeWithText(confirmText).performClick()
        compose.runOnIdle { assertEquals("m-next", selectedModelId) }
        // 取消不提交也不清除。
        selectedModelId = null
        compose.onNode(hasText(noneText)).performClick()
        compose.onNodeWithText(cancelText).performClick()
        compose.runOnIdle {
            assertNull(selectedModelId)
            assertTrue(dismissed)
            assertTrue(!cleared)
        }
    }

    @Test fun settingsRowsStackInOrderWithDropdownValuesAtTheTrailingEdge() {
        val profile = SubAgentProfile("test", "测试代理", tier = SubAgentTaskTier.COMPLEX)
        val fixture = SubAgentUiFixture(profiles = listOf(profile))
        compose.setSubAgentContent(fixture) {
            MaterialTheme {
                SubAgentProfileRow(profile, emptyList(), settings = true)
            }
        }
        val label = compose.onNodeWithText("职责", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("实现", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        // 行内下拉行：取值与标题同行排布（trailing 槽位居右），不再是标题下方的 supportingContent。
        assertTrue("value must share the row with its headline",
            value.top < label.bottom && value.bottom > label.top)
        assertTrue("value must sit right of the headline", value.left > label.left)
        val model = compose.onNodeWithContentDescription("测试代理 模型").fetchSemanticsNode().boundsInRoot
        val role = compose.onNodeWithContentDescription("为 测试代理 选择职责").fetchSemanticsNode().boundsInRoot
        val tier = compose.onNodeWithContentDescription("设置 测试代理 的任务分工").assertIsEnabled().fetchSemanticsNode().boundsInRoot
        assertTrue(role.top >= model.bottom)
        assertTrue(tier.top >= role.bottom)
        val thinking = compose.onNodeWithContentDescription("调整 测试代理 的推理强度").fetchSemanticsNode().boundsInRoot
        assertTrue(thinking.top >= tier.bottom)
        val parallel = compose.onNodeWithContentDescription("设置测试代理并行上限").assertIsNotEnabled().fetchSemanticsNode().boundsInRoot
        assertTrue(parallel.top >= thinking.bottom)
        compose.onNodeWithText("设置各提供商模型并行上限").assertDoesNotExist()
        compose.onNodeWithText("任务分工").assertExists()
        compose.onNodeWithText("复杂任务").assertExists()
    }

    @Test fun settingsRowUsesHeadlineAndSupportingInk() {
        val ink = Color(0xFF202124)
        val secondary = Color(0xFF5F6368)
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(onSurface = ink, onSurfaceVariant = secondary, primary = Color.Blue)) {
                SubAgentSettingsRow("模型", "grok-4.6", Icons.Rounded.AccountTree,
                    "测试代理模型", badge = "与", onClick = {})
            }
        }
        val label = compose.onNodeWithText("模型", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val value = compose.onNodeWithText("grok-4.6", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("value must be the supporting line", value.top >= label.bottom)
        val pixels = compose.onNodeWithContentDescription("测试代理模型").captureToImage().toPixelMap()
        var inkCount = 0
        var secondaryCount = 0
        var primaryCount = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            when (pixels[x, y]) {
                ink -> inkCount++
                secondary -> secondaryCount++
                Color.Blue -> primaryCount++
            }
        }
        // 标题/图标走正文色，取值与尾部箭头走次要色；页面主色不应出现在设置行里。
        assertTrue(inkCount > 40)
        assertTrue("supporting value/chevron must use onSurfaceVariant", secondaryCount > 20)
        assertEquals(0, primaryCount)
    }

    @Test fun providerBadgeSitsOnTheTrailingEdge() {
        compose.setContent {
            MaterialTheme {
                SubAgentSettingsRow("模型", "grok-4.6", Icons.Rounded.AccountTree,
                    "测试代理模型", badge = "与", onClick = {})
            }
        }
        val badge = compose.onNodeWithText("与", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val model = compose.onNodeWithText("grok-4.6", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("模型", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(badge.left > label.right)
        assertTrue("badge belongs to the trailing group, after the supporting value", badge.left > model.right)
        val row = compose.onNodeWithContentDescription("测试代理模型").fetchSemanticsNode().boundsInRoot
        assertTrue(badge.top >= row.top && badge.bottom <= row.bottom)
    }

    @Test fun compactMenuWrapsToLongestLabelInsteadOfFixedWidth() {
        compose.setContent {
            MaterialTheme {
                SubAgentTaskTierButton("执行", SubAgentTaskTier.COMPLEX, true, {}, compact = true)
            }
        }
        compose.onNodeWithContentDescription("设置执行任务分工").performClick()
        val selected = compose.onNode(isSelectable() and hasText("复杂任务")).fetchSemanticsNode().boundsInRoot
        val other = compose.onNode(isSelectable() and hasText("简单任务")).fetchSemanticsNode().boundsInRoot
        assertTrue("menu stayed oversized: ${selected.width}", selected.width < 200f)
        assertTrue(kotlin.math.abs(selected.width - other.width) < 2f)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h480dp")
    fun settingsAddActionStaysVisibleWhileAgentListScrolls() {
        val fixture = SubAgentUiFixture()
        val group = fixture.createPreset()
        compose.setSubAgentContent(fixture) {
            MaterialTheme { SubAgentPresetRouteHost(fixture) }
        }
        compose.onNodeWithText("添加子代理组", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("编辑子代理组 ${group.name}").performClick()
        compose.onNodeWithText("设置各提供商模型并行上限").assertDoesNotExist()
        // M3E 下新增入口是右下角 FAB（EtaExtendedFab），随列表滚动常驻可见。
        compose.onNodeWithText("添加子代理", useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        // 折叠后的 FAB 隐藏文字，图标以 contentDescription 承载标签，入口仍常驻可见。
        compose.onNodeWithContentDescription("添加子代理").assertIsDisplayed()
    }

    @Test fun boundParallelRowsObserveTheSharedModelLimit() {
        val provider = "parallel-ui-${java.util.UUID.randomUUID()}"
        val a = SubAgentProfile("parallel-a", "并发甲", providerId = provider, modelId = "record-a")
        val b = SubAgentProfile("parallel-b", "并发乙", providerId = provider, modelId = "record-b")
        val fixture = SubAgentUiFixture(profiles = listOf(a, b))
        val config = io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(
            providerId = provider, providerName = "已用提供商", baseUrl = "https://example.invalid", apiKey = "test",
            model = "bound-api", systemPrompt = "")
        compose.setSubAgentContent(fixture) { MaterialTheme { Column {
            SubAgentParallelLimitRow(a, config)
            SubAgentParallelLimitRow(b, config)
        } } }
        compose.onAllNodesWithText("不限", useUnmergedTree = true).assertCountEquals(0)
        compose.runOnIdle {
            assertTrue(fixture.editor.saveParallelLimit(a.id, provider, a.modelId, "bound-api", 0)
                is io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences.WriteResult.Saved)
        }
        compose.onAllNodesWithText("不限", useUnmergedTree = true).assertCountEquals(2)
        compose.runOnIdle {
            val current = fixture.snapshot()
            assertEquals(0, current.parallelLimit(io.github.mangi.eta.agent.delegation.SubAgentParallelModel(provider, "bound-api")))
            assertEquals(1, current.parallelLimit(io.github.mangi.eta.agent.delegation.SubAgentParallelModel(provider, "record-a")))
            assertEquals(1, current.parallelLimit(io.github.mangi.eta.agent.delegation.SubAgentParallelModel(provider, "unused-api")))
        }
    }

    @Test fun parallelRowDisablesWithoutOpeningADialog() {
        val profile = SubAgentProfile("dialog-test", "测试代理", providerId = "provider", modelId = "record")
        val config = mutableStateOf(io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(
            providerId = "provider", baseUrl = "https://example.invalid", apiKey = "test", model = "first-api", systemPrompt = ""))
        val enabled = mutableStateOf(true)
        val fixture = SubAgentUiFixture(profiles = listOf(profile))
        compose.setSubAgentContent(fixture) { MaterialTheme { SubAgentParallelLimitRow(profile, config.value, enabled.value) } }
        compose.onNodeWithContentDescription("设置测试代理并行上限").assertIsEnabled()
        compose.runOnIdle { config.value = config.value.copy(model = "second-api") }
        compose.onNodeWithContentDescription("设置测试代理并行上限").assertIsEnabled()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithContentDescription("设置测试代理并行上限").assertIsNotEnabled()
    }

}
