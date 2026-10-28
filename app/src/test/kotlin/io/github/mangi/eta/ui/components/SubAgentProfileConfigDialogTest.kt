package io.github.mangi.eta.ui.components

import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bounded draft UI regressions; owner transactions/memory implementation are tested by the data suite. */
@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "zh-rCN-w320dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class SubAgentProfileConfigDialogTest {
    @get:Rule val compose = createComposeRule()
    private val model = Model(id = "draft-record", modelId = "draft-api", displayName = "草稿测试模型")
    private val provider = OpenAiCompatibleProviderSetting(id = "draft-provider", name = "草稿测试提供商",
        baseUrl = "https://example.invalid", models = listOf(model))
    private val profile = SubAgentProfile("draft-existing", "草稿测试代理", providerId = provider.id, modelId = model.id)
    // Selection IDs deliberately differ from API IDs; aliases must not share the draft speed key.
    private val gptModel = model.copy(id = "draft-gpt-selection", modelId = "gpt-5", displayName = "GPT 草稿模型")
    private val gptAlias = gptModel.copy(id = "draft-gpt-alias")
    private val gptProvider = provider.copy(models = listOf(gptModel, gptAlias))
    private val gptProfile = profile.copy(modelId = gptModel.id)

    private class FailOncePreferences(private val real: SharedPreferences) : SharedPreferences by real {
        var failNext = false
        override fun edit(): SharedPreferences.Editor {
            val editor = real.edit()
            return object : SharedPreferences.Editor by editor {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor { editor.putString(key, value); return this }
                override fun remove(key: String?): SharedPreferences.Editor { editor.remove(key); return this }
                override fun commit(): Boolean = if (failNext) { failNext = false; false } else editor.commit()
            }
        }
    }

    @Test fun localFieldsAndNestedTierCancelDoNotWrite() {
        val fixture = SubAgentUiFixture(profiles = listOf(profile), providers = listOf(provider))
        val before = fixture.snapshot()
        val revision = fixture.repository.revision(fixture.owner).value
        val open = mutableStateOf(true)
        val session = requireNotNull(SubAgentProfileDraftSession.open(fixture.editor, profile))
        compose.setSubAgentContent(fixture) {
            if (open.value) SubAgentProfileConfigDialog(session, fixture.editor, listOf(provider), onDismiss = { open.value = false })
        }
        compose.onNode(hasClickAction() and hasText("草稿测试代理")).performClick() // 名称改为点行开重命名弹窗
        compose.onNodeWithContentDescription("名称").performTextReplacement("改名但取消")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithContentDescription("草稿任务分工").performScrollTo().performClick()
        compose.onNodeWithText("复杂任务").performClick()
        compose.runOnIdle { assertEquals(SubAgentTaskTier.COMPLEX, session.draft.tier) }
        compose.onNodeWithContentDescription("并行上限", substring = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("0 为不限，或输入正整数").performTextReplacement("0")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertFalse(open.value)
            assertEquals(before, fixture.snapshot())
            assertEquals(revision, fixture.repository.revision(fixture.owner).value)
        }
    }

    @Test fun gptSpeedDraftCancelChangesOnlySelectionKeyAndIsZeroWrite() {
        val key = SubAgentProfile.modelReasoningKey(gptProvider.id, gptModel.id)
        val aliasKey = SubAgentProfile.modelReasoningKey(gptProvider.id, gptAlias.id)
        val original = gptProfile.copy(gptSpeedByModel = mapOf(aliasKey to GptSpeedMode.ULTRA_FAST))
        lateinit var preferences: SharedPreferences
        val fixture = SubAgentUiFixture(profiles = listOf(original), providers = listOf(gptProvider),
            preferenceTransform = { it.also { value -> preferences = value } })
        val before = fixture.snapshot()
        val persistedBefore = preferences.all.toMap()
        val revision = fixture.repository.revision(fixture.owner).value
        val rememberedBefore = fixture.repository.modelDefaults(original)
        val open = mutableStateOf(true)
        val session = requireNotNull(SubAgentProfileDraftSession.open(fixture.editor, original))
        compose.setSubAgentContent(fixture) {
            if (open.value) SubAgentProfileConfigDialog(session, fixture.editor, listOf(gptProvider), onDismiss = { open.value = false })
        }
        compose.onNodeWithContentDescription("草稿 GPT 速度").performScrollTo().performClick()
        compose.onNodeWithText("快速", useUnmergedTree = true).assertExists()
        compose.runOnIdle {
            assertEquals(original.copy(gptSpeedByModel = original.gptSpeedByModel + (key to GptSpeedMode.FAST)), session.draft)
            assertEquals(before, fixture.snapshot())
            assertEquals(rememberedBefore, fixture.repository.modelDefaults(original))
        }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle {
            assertFalse(open.value)
            assertEquals(before, fixture.snapshot())
            assertEquals(revision, fixture.repository.revision(fixture.owner).value)
            assertEquals(persistedBefore, preferences.all)
            assertEquals(rememberedBefore, fixture.repository.modelDefaults(original))
            val restored = fixture.editor.changeProfileModel(original, ModelFeatureSelection(true, gptProvider.id, gptModel.id))
            assertEquals(GptSpeedMode.NORMAL, restored.gptSpeedForModel())
        }
    }

    @Test fun gptSpeedDraftConfirmSavesAndRestoresOnlyConfirmedSelection() {
        val aliasKey = SubAgentProfile.modelReasoningKey(gptProvider.id, gptAlias.id)
        val original = gptProfile.copy(gptSpeedByModel = mapOf(aliasKey to GptSpeedMode.ULTRA_FAST))
        val fixture = SubAgentUiFixture(profiles = listOf(original), providers = listOf(gptProvider))
        val before = fixture.snapshot()
        val open = mutableStateOf(true)
        val session = requireNotNull(SubAgentProfileDraftSession.open(fixture.editor, original))
        compose.setSubAgentContent(fixture) {
            if (open.value) SubAgentProfileConfigDialog(session, fixture.editor, listOf(gptProvider), onDismiss = { open.value = false })
        }
        compose.onNodeWithContentDescription("草稿 GPT 速度").performScrollTo().performClick()
        compose.onNodeWithContentDescription("草稿 GPT 速度").performClick()
        compose.runOnIdle {
            assertEquals(GptSpeedMode.ULTRA_FAST, session.draft.gptSpeedForModel())
            assertEquals(before, fixture.snapshot())
            assertNull(fixture.repository.modelDefaults(original))
        }
        compose.onNodeWithText("确认").assertIsEnabled().performClick()
        compose.waitUntil { !open.value }
        compose.runOnIdle {
            val saved = fixture.snapshot().profiles.single()
            assertEquals(session.draft, saved)
            assertEquals(GptSpeedMode.ULTRA_FAST, saved.gptSpeedForModel())
            assertEquals(GptSpeedMode.ULTRA_FAST, saved.gptSpeedByModel[aliasKey])
            assertEquals(GptSpeedMode.ULTRA_FAST, fixture.repository.modelDefaults(original)?.gptSpeed)
            val restored = fixture.editor.changeProfileModel(profile, ModelFeatureSelection(true, gptProvider.id, gptModel.id))
            assertEquals(GptSpeedMode.ULTRA_FAST, restored.gptSpeedForModel())
            val alias = fixture.editor.changeProfileModel(original, ModelFeatureSelection(true, gptProvider.id, gptAlias.id))
            assertEquals(GptSpeedMode.NORMAL, alias.gptSpeedForModel())
            assertNull(fixture.repository.modelDefaults(alias))
        }
    }

    @Test fun gptSpeedDraftRequiresEnabledRoleCompatibleActualGptBinding() {
        // A GPT-looking display name is not capability evidence.
        val fakeGpt = model.copy(displayName = "GPT-5")
        val catalog = mutableStateOf(listOf(provider.copy(models = listOf(gptModel, fakeGpt))))
        val fixture = SubAgentUiFixture(profiles = listOf(gptProfile), providers = listOf(gptProvider))
        val before = fixture.snapshot()
        val session = requireNotNull(SubAgentProfileDraftSession.open(fixture.editor, gptProfile))
        compose.setSubAgentContent(fixture) {
            SubAgentProfileConfigDialog(session, fixture.editor, catalog.value, onDismiss = {})
        }
        compose.onNodeWithContentDescription("草稿 GPT 速度").assertExists()
        compose.runOnIdle { session.selectModel(ModelFeatureSelection(true, provider.id, fakeGpt.id)) }
        compose.onNodeWithContentDescription("草稿 GPT 速度").assertDoesNotExist()
        compose.runOnIdle { session.selectModel(ModelFeatureSelection(true, gptProvider.id, gptModel.id)) }
        compose.onNodeWithContentDescription("草稿 GPT 速度").assertExists()
        compose.runOnIdle { catalog.value = listOf(gptProvider.copy(isEnabled = false)) }
        compose.onNodeWithContentDescription("草稿 GPT 速度").assertDoesNotExist()
        compose.runOnIdle { catalog.value = listOf(gptProvider.copy(models = listOf(gptModel.copy(isEnabled = false)))) }
        compose.onNodeWithContentDescription("草稿 GPT 速度").assertDoesNotExist()
        compose.runOnIdle { catalog.value = listOf(gptProvider); session.edit { it.copy(role = "image_generation") } }
        compose.onNodeWithContentDescription("草稿 GPT 速度").assertDoesNotExist()
        compose.runOnIdle { session.selectModel(ModelFeatureSelection(true, "", "")) }
        compose.onNodeWithContentDescription("草稿 GPT 速度").assertDoesNotExist()
        compose.runOnIdle { assertEquals(before, fixture.snapshot()) }
    }

    @Test fun failureKeepsGptSpeedDraftAndExplicitRetryThenSavedCloses() {
        lateinit var preferences: FailOncePreferences
        val fixture = SubAgentUiFixture(profiles = listOf(gptProfile), providers = listOf(gptProvider), preferenceTransform = {
            FailOncePreferences(it).also { wrapped -> preferences = wrapped }
        })
        val before = fixture.snapshot()
        val revision = fixture.repository.revision(fixture.owner).value
        val persistedBefore = preferences.all.toMap()
        val open = mutableStateOf(true)
        val session = requireNotNull(SubAgentProfileDraftSession.open(fixture.editor, gptProfile))
        compose.setSubAgentContent(fixture) {
            if (open.value) SubAgentProfileConfigDialog(session, fixture.editor, listOf(gptProvider), onDismiss = { open.value = false })
        }
        compose.onNode(hasClickAction() and hasText("草稿测试代理")).performClick()
        compose.onNodeWithContentDescription("名称").performTextReplacement("成功才关闭")
        compose.onNodeWithText("保存").performClick()
        compose.onNodeWithContentDescription("草稿 GPT 速度").performScrollTo().performClick()
        compose.runOnIdle { preferences.failNext = true }
        compose.onNodeWithText("确认").assertIsEnabled().performClick()
        compose.waitUntil { !session.submitting }
        compose.runOnIdle {
            assertTrue(open.value)
            assertEquals("成功才关闭", session.name)
            assertEquals(GptSpeedMode.FAST, session.draft.gptSpeedForModel())
            assertEquals(persistedBefore, preferences.all)
            // Even a successful best-effort rollback cannot lift the durability fence.
            assertThrows(IllegalStateException::class.java) { fixture.snapshot() }
            assertThrows(IllegalStateException::class.java) { fixture.repository.modelDefaults(gptProfile) }
            assertEquals(revision, fixture.repository.revision(fixture.owner).value)
        }
        compose.onNodeWithText("未保存：配置或模型已更改", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("重试恢复配置（保留草稿）").performScrollTo().performClick()
        compose.onNodeWithText("确认").assertIsEnabled()
        compose.runOnIdle {
            // Recovery restores the original config; it must not commit the retained draft.
            assertTrue(open.value)
            assertEquals("成功才关闭", session.name)
            assertEquals(GptSpeedMode.FAST, session.draft.gptSpeedForModel())
            assertEquals(before, fixture.snapshot())
            assertEquals(persistedBefore, preferences.all)
            assertNull(fixture.repository.modelDefaults(gptProfile))
            assertEquals(revision, fixture.repository.revision(fixture.owner).value)
        }
        compose.onNodeWithText("确认").assertIsEnabled().performClick()
        compose.waitUntil { !open.value }
        compose.runOnIdle {
            assertEquals("成功才关闭", fixture.snapshot().profiles.single().name)
            assertEquals(GptSpeedMode.FAST, fixture.snapshot().profiles.single().gptSpeedForModel())
            assertEquals(GptSpeedMode.FAST, fixture.repository.modelDefaults(gptProfile)?.gptSpeed)
        }
    }

    @Test fun ownerGateInvalidationDropsOpenDraftWithoutWriting() {
        val allowed = mutableStateOf(true)
        val fixture = SubAgentUiFixture(profiles = listOf(profile), providers = listOf(provider), canEdit = { allowed.value })
        val before = fixture.snapshot()
        val open = mutableStateOf(true)
        val session = requireNotNull(SubAgentProfileDraftSession.open(fixture.editor, profile))
        compose.setSubAgentContent(fixture) {
            if (open.value) SubAgentProfileConfigDialog(session, fixture.editor, listOf(provider), enabled = allowed.value,
                onDismiss = { open.value = false })
        }
        compose.onNode(hasClickAction() and hasText("草稿测试代理")).performClick()
        compose.onNodeWithContentDescription("名称").performTextReplacement("失效不写")
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle { allowed.value = false }
        compose.onNodeWithText("确认").assertDoesNotExist()
        compose.runOnIdle { assertFalse(open.value); assertEquals(before, fixture.snapshot()) }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h480dp")
    fun standaloneCompactDraftKeepsActionsVisibleWhileBodyAndNestedChoicesScroll() {
        val fixture = SubAgentUiFixture(profiles = listOf(profile), providers = listOf(provider))
        val before = fixture.snapshot()
        val revision = fixture.repository.revision(fixture.owner).value
        val open = mutableStateOf(true)
        val session = requireNotNull(SubAgentProfileDraftSession.open(fixture.editor, profile))
        // No Scaffold or parent Dialog: the same production form must own its window.
        compose.setSubAgentContent(fixture) {
            if (open.value) SubAgentProfileConfigDialog(session, fixture.editor, listOf(provider), onDismiss = { open.value = false })
        }
        compose.onNodeWithText("草稿测试代理").assertIsDisplayed() // 名称行：点开重命名弹窗
        compose.onNodeWithText("确认").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("取消").assertIsDisplayed()
        val actionsBeforeScroll = compose.onNodeWithText("取消").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("并行上限", substring = true).performScrollTo().assertIsDisplayed()
        assertEquals(actionsBeforeScroll, compose.onNodeWithText("取消").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("确认").assertIsDisplayed()
        compose.onNodeWithContentDescription("草稿任务分工").performScrollTo().performClick()
        // 任务分工已改为行内下拉菜单：选项在弹出层里，没有可滚动祖先，不走 performScrollTo。
        compose.onNodeWithText("复杂任务").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("草稿任务分工").assertIsDisplayed()
        compose.onNodeWithText("确认").assertIsDisplayed()
        compose.onNodeWithText("取消").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertFalse(open.value)
            assertEquals(before, fixture.snapshot())
            assertEquals(revision, fixture.repository.revision(fixture.owner).value)
        }
    }

    @Test fun existingParallelUsesActualOwnerAndOnlyExplicitEditCreatesChange() {
        val fixture = SubAgentUiFixture(profiles = listOf(profile), providers = listOf(provider))
        val pool = SubAgentParallelModel(provider.id, model.modelId)
        fixture.repository.update(fixture.owner) { it.copy(parallelLimits = mapOf(pool to 0)) }
        // A new editor snapshot is deliberately captured after the owner's actual limit changed.
        val editor = ConversationSubAgentEditor(fixture.owner, fixture.repository, { provider }) { true }
        val session = requireNotNull(SubAgentProfileDraftSession.open(editor, profile))
        session.bindParallel(resolveSubAgentProfileConfig(profile, listOf(provider)))
        assertEquals("0", session.parallelValue)
        assertNull(session.parallelChange())
        session.editParallel("2")
        assertEquals(SubAgentParallelLimitChange(pool, 2, 0), session.parallelChange())
        session.dismiss()
        assertEquals(0, fixture.snapshot().parallelLimit(pool))
    }
}
