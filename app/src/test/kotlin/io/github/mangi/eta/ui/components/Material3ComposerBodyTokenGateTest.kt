package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Material3ComposerBody 唤起令牌消费闸门（`AgentChatComposerM3` 的
 * dictationStartRequest / initialConsumedStartToken / consumedStartToken 记账）的回归护栏。
 *
 * 锁的用户可感知行为（41336b3e7 切会话误入听写修复 + 助手弹窗唤起即听写）：
 * - 浮窗轨「重建即重放」是刻意语义：不播种（initialConsumedStartToken=0）时，重建的面板
 *   首帧就按听写态排版（onDictationActiveChange(true) 的首帧桥，弹窗第一帧即流光胶囊）；
 * - 主应用轨「播种后不重放」：消费过的令牌经进程级水位播种给重建实例后，同一令牌不得再
 *   触发唤起——否则每次切会话都会误开听写；
 * - 播种后的新令牌照常逐次消费并上报（onDictationStartRequestConsumed 恰好一次/令牌）；
 * - 面板在屏时再次唤起（令牌递增）逐次重开听写；
 * - 语音对话入口（CONVERSATION）只记账不进听写态：唤起令牌只服务口述入口。
 *
 * 测试环境未配置识别引擎：ComposerDictation.start() 走「未配置」分支只置提示条，
 * 不碰麦克风（DoubaoSecretStore 在无 Keystore 环境显式退化），因此 active(false)
 * 的回落是确定性的，可安全断言。
 *
 * 不用 NATIVE 图形模式：本测试只断言回调序列，不采样像素；NATIVE 的 nativeruntime
 * DLL 在中文用户名机器的 temp 路径下会 GBK 乱码加载失败（Windows-only 环境失败清单），
 * shadow 模式下 createComposeRule 的 effect/回调照常运转。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w480dp-h900dp-mdpi")
class Material3ComposerBodyTokenGateTest {
    @get:Rule val compose = createComposeRule()

    /** 浮窗轨：不播种消费水位，重建实例把旧令牌当新唤起重放——首帧即听写态 + 恰好消费一次。 */
    @Test
    fun floatingTrackWithoutSeedReplaysTokenDeliberately() {
        val consumed = mutableListOf<Int>()
        val activeStates = mutableListOf<Boolean>()
        compose.setComposerContent(
            dictationStartRequest = 1,
            initialConsumedStartToken = 0,
            voiceEntry = ComposerVoiceEntry.DICTATION,
            onDictationStartRequestConsumed = { consumed.add(it) },
            onDictationActiveChange = { activeStates.add(it) },
        )
        compose.waitForIdle()
        assertEquals("令牌恰好消费一次", listOf(1), consumed)
        assertEquals("首帧桥直接进听写态（弹窗第一帧即流光胶囊）", true, activeStates.first())
        assertEquals(
            "未配置引擎时不滞留聆听态（提示条替代，active 回落 false）",
            false,
            activeStates.last(),
        )
    }

    /** 主应用轨核心回归：进程级水位播种后，重建实例不得把已消费令牌当新唤起重放。 */
    @Test
    fun seededInstanceDoesNotReplayConsumedToken() {
        val consumed = mutableListOf<Int>()
        val activeStates = mutableListOf<Boolean>()
        compose.setComposerContent(
            dictationStartRequest = 2,
            initialConsumedStartToken = 2,
            voiceEntry = ComposerVoiceEntry.DICTATION,
            onDictationStartRequestConsumed = { consumed.add(it) },
            onDictationActiveChange = { activeStates.add(it) },
        )
        compose.waitForIdle()
        assertEquals("已消费令牌不得再触发唤起回调", emptyList<Int>(), consumed)
        assertEquals("首帧不得进听写态（切会话误入听写回归）", listOf(false), activeStates)
    }

    /** 播种只挡旧令牌：水位之上的新唤起照常消费并上报。 */
    @Test
    fun seededInstanceStillConsumesNewerToken() {
        val consumed = mutableListOf<Int>()
        val activeStates = mutableListOf<Boolean>()
        compose.setComposerContent(
            dictationStartRequest = 3,
            initialConsumedStartToken = 2,
            voiceEntry = ComposerVoiceEntry.DICTATION,
            onDictationStartRequestConsumed = { consumed.add(it) },
            onDictationActiveChange = { activeStates.add(it) },
        )
        compose.waitForIdle()
        assertEquals("新令牌恰好消费一次", listOf(3), consumed)
        assertEquals("新唤起首帧进听写态", true, activeStates.first())
    }

    /** 面板在屏时再次唤起：令牌递增逐次消费（再按电源键的意图就是再录一句）。 */
    @Test
    fun repeatedWakeConsumesEachTokenOnce() {
        val consumed = mutableListOf<Int>()
        val request = mutableStateOf(1)
        compose.setComposerContent(
            dictationStartRequestState = request,
            initialConsumedStartToken = 0,
            voiceEntry = ComposerVoiceEntry.DICTATION,
            onDictationStartRequestConsumed = { consumed.add(it) },
            onDictationActiveChange = {},
        )
        compose.waitForIdle()
        compose.runOnIdle { request.value = 2 }
        compose.waitForIdle()
        assertEquals("两次唤起各消费一次、不重复不遗漏", listOf(1, 2), consumed)
    }

    /** 语音对话入口只记账不进听写态：消费闸门与入口语义正交。 */
    @Test
    fun conversationEntryRecordsTokenWithoutDictation() {
        val consumed = mutableListOf<Int>()
        val activeStates = mutableListOf<Boolean>()
        compose.setComposerContent(
            dictationStartRequest = 1,
            initialConsumedStartToken = 0,
            voiceEntry = ComposerVoiceEntry.CONVERSATION,
            onDictationStartRequestConsumed = { consumed.add(it) },
            onDictationActiveChange = { activeStates.add(it) },
        )
        compose.waitForIdle()
        assertEquals("令牌仍记账上报", listOf(1), consumed)
        assertEquals("语音对话入口不进听写态", listOf(false), activeStates)
    }

    private fun ComposeContentTestRule.setComposerContent(
        field: TextFieldState = TextFieldState(),
        dictationStartRequest: Int = 0,
        dictationStartRequestState: androidx.compose.runtime.State<Int>? = null,
        initialConsumedStartToken: Int = 0,
        voiceEntry: ComposerVoiceEntry = ComposerVoiceEntry.CONVERSATION,
        onDictationStartRequestConsumed: ((Int) -> Unit)? = null,
        onDictationActiveChange: (Boolean) -> Unit = {},
    ) {
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
                    canSend = field.text.isNotBlank(),
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
                    voiceEntry = voiceEntry,
                    dictationStartRequest = dictationStartRequestState?.value ?: dictationStartRequest,
                    initialConsumedStartToken = initialConsumedStartToken,
                    onDictationStartRequestConsumed = onDictationStartRequestConsumed,
                    onDictationActiveChange = onDictationActiveChange,
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
