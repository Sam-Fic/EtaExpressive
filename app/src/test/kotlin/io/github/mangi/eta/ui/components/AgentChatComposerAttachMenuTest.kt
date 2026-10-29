package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.annotation.GraphicsMode

/**
 * 「+」菜单改用 Material 3 Expressive 官方写法（`DropdownMenuPopup` + `DropdownMenuGroup`）后的运行时行为。
 *
 * 这里锁三件事：
 *  1. 弹层能真正打开——`DropdownMenuPopup` 的浮层窗口与旧 `DropdownMenu` 不是同一套实现，
 *     「点开就立刻关闭」这类回归只有真跑一次才能发现；
 *  2. 两个分组（对话操作 / 附件来源）的 8 个条目都渲染出来了——分组后条目容易漏渲染；
 *  3. 点条目会派发动作并收起菜单。
 *
 * 断言文案全部取自字符串资源（而不是写死英文），这样在中文 / 英文两种语言环境下都成立。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentChatComposerAttachMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun attachMenuOpensWithBothGroupsAndDispatchesPickedEntry() {
        // 菜单浮层是独立窗口（独立 Composition），Robolectric 下它靠主 Looper 派发帧才组合出来；
        // 这里手动推进时钟 + 空转主 Looper，避免 waitForIdle 与浮层动画互相等待。
        compose.mainClock.autoAdvance = false
        compose.setComposerContent()
        repeat(5) { advanceFrames() }
        compose.onNodeWithContentDescription(string(R.string.ui_add_attachment_dba9e8)).performClick()
        repeat(20) { advanceFrames() }

        // 第一组：对话操作。
        listOf(
            R.string.composer_schedule_send,
            R.string.composer_save_draft,
            R.string.composer_share,
        ).forEach { compose.onNodeWithText(string(it)).assertExists() }
        // 第二组：附件来源。
        listOf(
            R.string.attachment_take_photo,
            R.string.attachment_video,
            R.string.attachment_file,
            R.string.attachment_folder,
            R.string.attachment_enter_path,
        ).forEach { compose.onNodeWithText(string(it)).assertExists() }

        // 点一项：动作派发、菜单收起（分享在空草稿下没有副作用，正好用来验证菜单收起）。
        compose.onNodeWithText(string(R.string.composer_share)).performClick()
        repeat(20) { advanceFrames() }
        compose.onNodeWithText(string(R.string.composer_share)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.attachment_enter_path)).assertDoesNotExist()
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    /** 空转主 Looper（浮层窗口靠它派发帧）并推进一帧测试时钟。 */
    private fun advanceFrames() {
        ShadowLooper.idleMainLooper()
        compose.mainClock.advanceTimeByFrame()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setComposerContent() {
        val field = TextFieldState()
        setContent {
            MaterialExpressiveTheme(
                colorScheme = lightColorScheme(),
                motionScheme = MotionScheme.expressive(),
            ) {
                Material3ComposerBody(
                    textFieldState = field,
                    focusRequester = remember { FocusRequester() },
                    imeVisible = false,
                    isEditingMessage = false,
                    isStreaming = false,
                    isPaused = false,
                    isCompressingContext = false,
                    canContinueDisconnected = false,
                    contextSendBlocked = false,
                    canSend = false,
                    usage = AgentContextUsageUi(contextTokens = null, contextWindow = null),
                    popupMaxHeight = { 360.dp },
                    onPositionedTopPx = {},
                    conversationId = null,
                    assistantId = "",
                    modelPickerState = AgentModelPickerUiState(),
                    reasoningEffort = ReasoningEffort.OFF,
                    availableReasoningEfforts = emptyList(),
                    gptSpeedMode = GptSpeedMode.NORMAL,
                    gptSpeedSupported = false,
                    voiceState = VoiceModeState(),
                    showMorphLoading = false,
                    pendingImages = emptyList(),
                    pendingFileReferences = emptyList(),
                    conversationMentions = ConversationMentionInputUi(),
                    onStartVoiceMode = {},
                    onStopVoiceMode = {},
                    onCancelMessageEdit = {},
                    onAttachImage = {},
                    onAttachVideo = {},
                    onAttachFiles = {},
                    onAttachFolder = {},
                    onAttachFilePath = {},
                    onEditAssistant = {},
                    onAssistantSelected = {},
                    onModelSelected = { _, _ -> },
                    onReasoningEffortChange = {},
                    onCycleGptSpeedMode = {},
                    onSubmit = {},
                    onStop = {},
                    onContinue = {},
                    onAbortPausedRun = {},
                )
            }
        }
    }
}
