package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenuItem
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.mangi.eta.ui.model.ConversationMention
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.agent.voice.VoiceModePhase
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.config.Prefs
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.ui.screens.assistants.AssistantPickerDialog
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.liveContextUsage
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import io.github.mangi.eta.ui.model.SkillSlashMention
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import kotlin.math.roundToInt
import android.content.Intent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider as M3Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.text.style.TextOverflow
import io.github.mangi.eta.data.repository.ComposerQueueStore
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaCircularProgress
import io.github.mangi.eta.ui.adaptive.EtaConnectedGroupRow
import io.github.mangi.eta.ui.adaptive.etaGptSpeedColor
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaOnTertiaryContainer
import io.github.mangi.eta.ui.adaptive.etaOnSurface
import io.github.mangi.eta.ui.adaptive.etaPrimary
import io.github.mangi.eta.ui.adaptive.etaTertiaryContainer
import io.github.mangi.eta.ui.adaptive.etaSurfaceContainer
import io.github.mangi.eta.ui.adaptive.etaSurfaceContainerHigh
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.adaptive.EtaDimens

/** 图标按钮的图标弱化透明度（与官方 StandardIconButtonTokens.DisabledOpacity 同值）。 */
private const val DisabledIconAlpha = 0.38f

/**
 * Agent 输入器始终保持同一空间结构，聚焦、输入和执行过程只改变状态，不搬动操作入口。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AgentChatInputBar(
    input: String,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    modelPickerState: AgentModelPickerUiState,
    history: List<AgentModelClient.ConversationMessage>,
    billedContextTokens: Int? = null,
    projectedContextTokens: Int? = null,
    billedHistoryTokens: Int? = null,
    requestOverheadTokens: Int = 0,
    measuredContextTokens: Int? = null,
    contextDisplayPolicy: io.github.mangi.eta.ui.model.ContextDisplayPolicy = io.github.mangi.eta.ui.model.ContextDisplayPolicy(),
    billedOverheadTokens: Int? = null,
    activeRunContextWindow: Int? = null,
    autoCompressEnabled: Boolean,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    isCompressingContext: Boolean = false,
    showMorphLoading: Boolean = false,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi = ConversationMentionInputUi(),
    /** 流式期间短按发送排队的消息；显示在输入栏上方的胶囊里。 */
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String) -> Unit = {},
    isEditingMessage: Boolean,
    collaborationConversationId: String? = null,
    assistantId: String = "",
    voiceState: VoiceModeState = VoiceModeState(),
    onStartVoiceMode: (VoiceEntryMode) -> Unit = {},
    onStopVoiceMode: () -> Unit = {},
    editHasLaterTurns: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    onAbortPausedRun: () -> Unit = {},
    isPaused: Boolean = false,
    canContinueDisconnected: Boolean = false,
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenAssistants: () -> Unit,
    /** 长按「助手与模型」按钮：进入设置页的子代理配置页（预设组列表 / 预设详情）。 */
    onOpenSubAgentSettings: () -> Unit = {},
    onAssistantSelected: (String) -> Unit = {},
    /** GPT 速度档位（真值来自 owner 的 uiState，UI 不本地记忆）。 */
    gptSpeedMode: GptSpeedMode = GptSpeedMode.NORMAL,
    /** 长按速度图标时派发给 owner 的循环切换事件。 */
    onCycleGptSpeedMode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Commit count only: no snapshot writes, no callbacks retained, no CPU-time claim.
    SideEffect { StreamPerformanceDiagnostics.record("chat.input.compose") }
    val focusRequester = remember { FocusRequester() }
    val textFieldState = draftField ?: rememberTextFieldState(initialText = input)
    var wasEditingMessage by remember { mutableStateOf(isEditingMessage) }
    val draftText = textFieldState.text.toString()
    val skillAppContext = LocalContext.current.applicationContext
    val assistantProfiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    // The index walk takes a cross-process file lock and recursively scans the skills
    // root, so it must not run inside a composition on the main thread. Empty until the
    // first background result lands; the mention panel simply offers nothing until then.
    val availableSkills by produceState<List<SkillIndexEntry>>(
        initialValue = emptyList(),
        assistantId, skillAppContext, assistantProfiles, repositoryActiveId,
    ) {
        value = withContext(Dispatchers.IO) {
            val profile = assistantProfiles.firstOrNull { it.id == assistantId }
                ?: assistantProfiles.firstOrNull { it.id == repositoryActiveId }
                ?: AssistantRepository.active()
            val enabledIds = profile.enabledSkillIds.toSet()
            SkillRuntime.createIndexService(skillAppContext)
                .listSkillsForManagement()
                .filter { it.installed && it.id in enabledIds }
                .filter { SkillCompatibilityChecker.evaluate(it).available }
        }
    }
    val supportsVision = modelPickerState.selectedModel?.supportsVision == true
    val supportsVideo = modelPickerState.selectedModel?.supportsVideo == true
    // 只有“实际选中模型”的 modelId 是 GPT 速度模型、端点仍是文本 OpenAI 且非图像/视频生成时，
    // 思考图标才具备长按切速度语义。非 GPT 立即走普通原色分支，不给长按语义，也不做旋转/缩放。
    val gptSpeedSupported = modelPickerState.selectedModel?.gptSpeedSupported == true
    val liveUsage = remember(contextDisplayPolicy, billedContextTokens, modelPickerState.selectedModel,
        activeRunContextWindow) {
        // 圆环只显示真实云端回执：没有实测回执时不投影任何本地估算。
        liveContextUsage(
            billedContextTokens = billedContextTokens,
            selectedModel = modelPickerState.selectedModel,
            contextDisplayPolicy = contextDisplayPolicy,
            activeRunContextWindow = activeRunContextWindow,
        )
    }
    // The budget only serves manual send blocking. Skip all local history work
    // when the existing policy cannot block; the cloud-only ring stays independent.
    val contextSendBlocked = contextSendBlocked(measuredContextTokens, autoCompressEnabled) {
        val historyTokenCount = remember(history) { history.sumOf { io.github.mangi.eta.agent.model.AgentContextBudget.countMessage(it) } }
        val localHistoryTokenCount = remember(history, supportsVision, supportsVideo) {
            io.github.mangi.eta.agent.model.AgentRequestTokenEstimate.history(history, supportsVision, supportsVideo)
        }
        val sendBudget = remember(historyTokenCount, localHistoryTokenCount, draftText, pendingImages, pendingFileReferences,
            conversationMentions.pending, modelPickerState.selectedModel, billedContextTokens,
            billedHistoryTokens, requestOverheadTokens, billedOverheadTokens, projectedContextTokens, activeRunContextWindow) {
            io.github.mangi.eta.ui.model.compressionContextUsage(
                history = emptyList(), currentInput = draftText, pendingImages = pendingImages,
                selectedModel = modelPickerState.selectedModel, historyTokenCount = historyTokenCount,
                localHistoryTokenCount = localHistoryTokenCount,
                pendingFileReferences = pendingFileReferences, pendingConversationMentions = conversationMentions.pending,
                billedContextTokens = billedContextTokens, requestOverheadTokens = requestOverheadTokens,
                billedHistoryTokens = billedHistoryTokens, billedOverheadTokens = billedOverheadTokens,
                projectedContextTokens = projectedContextTokens,
                activeRunContextWindow = activeRunContextWindow,
            )
        }
        sendBudget
    }
    val compressionSendBlocked = isCompressingContext
    val canSend = !modelPickerState.isChanging && modelPickerState.selectedModel != null && !contextSendBlocked && !compressionSendBlocked && (
        textFieldState.text.isNotBlank() ||
            pendingImages.isNotEmpty() ||
            (pendingFileReferences.isNotEmpty() || conversationMentions.pending.isNotEmpty())
        )
    val density = LocalDensity.current
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    // Deliberately NOT snapshot state. The composer reports its window Y position on every
    // layout, and `imePadding` moves it on every frame of the keyboard show/hide animation.
    // Holding that in mutableIntStateOf made this whole input bar — text field, mention
    // panels, attachment strips — recompose once per keyboard frame, which is the stutter
    // felt when tapping into the field. Nothing observable is written here now, so moving
    // the composer costs no recomposition at all.
    val inputContainerTopPx = remember { intArrayOf(0) }
    // Read on demand, at the moment a popup is composed, so the value is current rather than
    // captured at this composable's last recomposition.
    val popupMaxHeight: () -> Dp = remember(density, statusBarTopPx) {
        {
            with(density) { (inputContainerTopPx[0] - statusBarTopPx).coerceAtLeast(0).toDp() }
                .minus(ChatInputPopupMargin * 2)
                .coerceAtLeast(50.dp)
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val drawerBlocksIme = LocalConversationDrawerBlocksIme.current
    // 键盘（IME）可见性：键盘收起时左侧动作组必须保持可见（见 Material3ComposerBody）。
    val imeVisible = rememberImeVisible()
    LaunchedEffect(isEditingMessage) {
        // 编辑模式切换由业务状态驱动；日常输入使用会话拥有的 TextFieldState，
        // 不让每个字符触发消息列表和 Markdown 重组。
        if (draftField == null && (isEditingMessage || wasEditingMessage)) {
            textFieldState.setTextAndPlaceCursorAtEnd(input)
        }
        if (isEditingMessage && !wasEditingMessage && !drawerBlocksIme) {
            showChatInputIme(focusRequester, keyboard, view)
        }
        wasEditingMessage = isEditingMessage
    }

    CompositionLocalProvider(LocalChatInputFocusRequester provides focusRequester) {
    Column(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        // Material 3 Expressive：输入器所有进出出场动效统一取官方 motionScheme 规格。
        // 淡入淡出只变透明度，属 Effects（临界阻尼、无过冲）；高度展开收缩属 Spatial。
        val fadeEnterSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
            MaterialTheme.motionScheme.defaultEffectsSpec()
        val fadeExitSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
            MaterialTheme.motionScheme.fastEffectsSpec()
        val growSpec: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntSize> =
            MaterialTheme.motionScheme.defaultSpatialSpec()
        val shrinkSpec: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntSize> =
            MaterialTheme.motionScheme.defaultSpatialSpec()
        val shrinkTallSpec: androidx.compose.animation.core.FiniteAnimationSpec<androidx.compose.ui.unit.IntSize> =
            MaterialTheme.motionScheme.defaultSpatialSpec()
        val mentionQuery = ConversationMention.queryAtCursor(draftText, textFieldState.selection.end)
            .takeIf { textFieldState.selection.collapsed }
        val skillQuery = SkillSlashMention.queryAtCursor(draftText, textFieldState.selection.end)
            .takeIf { textFieldState.selection.collapsed }
        SkillSlashMentionPanel(
            skills = availableSkills,
            query = skillQuery?.query,
            selectedIds = SkillSlashMention.selectedIds(draftText),
            onSelect = { skill ->
                val query = skillQuery
                if (query != null) {
                    val end = textFieldState.selection.end
                    textFieldState.edit {
                        replace(query.start, end, SkillSlashMention.token(skill.id))
                        selection = TextRange(query.start + SkillSlashMention.token(skill.id).length)
                    }
                    focusRequester.requestFocus()
                }
            },
        )
        ConversationMentionPanel(
            state = conversationMentions,
            query = mentionQuery?.query,
            onSelect = { id ->
                val query = mentionQuery
                if (query != null && conversationMentions.onAttach(id)) {
                    val end = textFieldState.selection.end
                    textFieldState.edit {
                        replace(query.start, end, "")
                        selection = TextRange(query.start)
                    }
                    focusRequester.requestFocus()
                }
            },
        )
        AnimatedVisibility(
            visible = pendingFileReferences.isNotEmpty(),
            enter = fadeIn(fadeEnterSpec),
            exit = fadeOut(fadeExitSpec) + shrinkVertically(shrinkTallSpec),
        ) {
            PendingFileReferenceStrip(
                references = pendingFileReferences,
                onRemoveReference = onRemoveFileReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = pendingImages.isNotEmpty(),
            enter = fadeIn(fadeEnterSpec),
            exit = fadeOut(fadeExitSpec) + shrinkVertically(shrinkTallSpec),
        ) {
            PendingImageStrip(
                images = pendingImages,
                onRemoveImage = onRemoveImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = isEditingMessage,
            enter = fadeIn(fadeEnterSpec),
            exit = fadeOut(fadeExitSpec) + shrinkVertically(shrinkSpec),
        ) {
            M3Text(
                text = if (editHasLaterTurns) {
                    stringResource(R.string.chat_edit_replace_later)
                } else {
                    stringResource(R.string.chat_edit_replace_message)
                },
                style = etaTextStyle(EtaType.Secondary),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = contextSendBlocked,
            enter = fadeIn(fadeEnterSpec),
            exit = fadeOut(fadeExitSpec) + shrinkVertically(shrinkSpec),
        ) {
            M3Text(
                text = stringResource(R.string.context_window_send_blocked),
                style = etaTextStyle(EtaType.Secondary),
                color = StatusError,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = compressionSendBlocked,
            enter = fadeIn(fadeEnterSpec),
            exit = fadeOut(fadeExitSpec) + shrinkVertically(shrinkSpec),
        ) {
            M3Text(
                text = stringResource(R.string.compress_conversation_in_progress),
                style = etaTextStyle(EtaType.Secondary),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
        }

        // Material 3 Expressive 输入区：单行浮动工具栏（+ / 相机 / 助手与模型二合一 / 胶囊输入框，
        // 输入框尾部内嵌语音或发送按钮）。
        Material3ComposerBody(
            textFieldState = textFieldState,
            focusRequester = focusRequester,
            interactionBlocked = drawerBlocksIme,
            imeVisible = imeVisible,
            isEditingMessage = isEditingMessage,
            isStreaming = isStreaming,
            isPaused = isPaused,
            isCompressingContext = isCompressingContext,
            canContinueDisconnected = canContinueDisconnected,
            contextSendBlocked = contextSendBlocked,
            canSend = canSend,
            usage = liveUsage,
            popupMaxHeight = popupMaxHeight,
            onPositionedTopPx = { inputContainerTopPx[0] = it },
            conversationId = collaborationConversationId,
            assistantId = assistantId,
            modelPickerState = modelPickerState,
            reasoningEffort = reasoningEffort,
            availableReasoningEfforts = availableReasoningEfforts,
            gptSpeedMode = gptSpeedMode,
            gptSpeedSupported = gptSpeedSupported,
            voiceState = voiceState,
            showMorphLoading = showMorphLoading,
            pendingImages = pendingImages,
            pendingFileReferences = pendingFileReferences,
            conversationMentions = conversationMentions,
            queuedMessages = queuedMessages,
            onSteerQueuedMessage = onSteerQueuedMessage,
            onRecallQueuedMessage = onRecallQueuedMessage,
            onStartVoiceMode = onStartVoiceMode,
            onStopVoiceMode = onStopVoiceMode,
            onCancelMessageEdit = onCancelMessageEdit,
            onAttachImage = onAttachImage,
            onAttachVideo = onAttachVideo,
            onAttachFiles = onAttachFiles,
            onAttachFolder = onAttachFolder,
            onAttachFilePath = onAttachFilePath,
            onEditAssistant = onEditAssistant,
            onOpenModelProviders = onOpenModelProviders,
            onOpenAssistants = onOpenAssistants,
            onOpenSubAgentSettings = onOpenSubAgentSettings,
            onAssistantSelected = onAssistantSelected,
            onModelSelected = onModelSelected,
            onReasoningEffortChange = onReasoningEffortChange,
            onCycleGptSpeedMode = onCycleGptSpeedMode,
            onSubmit = onSubmit,
            onStop = onStop,
            onContinue = onContinue,
            onAbortPausedRun = onAbortPausedRun,
        )
    }
    }

}

/**
 * 思考强度图标：单击打开旧的思考强度弹窗；当实际选中模型是 GPT 速度模型时，
 * 长按在原位循环切换速度档位（NORMAL → FAST → ULTRA_FAST → NORMAL）。
 *
 * 颜色始终由 owner 传入的 [gptSpeedMode] 决定（非 GPT 立即回到普通原色）；
 * 旋转/缩放只是“一次长按正好一圈”的手势反馈，颜色与业务真值都不本地记忆。
 */
@Composable
internal fun ThinkingEffortChip(
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    enabled: Boolean,
    gptSpeedMode: GptSpeedMode = GptSpeedMode.NORMAL,
    gptSpeedSupported: Boolean = false,
    onCycleGptSpeedMode: () -> Unit = {},
    onEffortChange: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val showPicker = remember { mutableStateOf(false) }
    val active = effort != ReasoningEffort.OFF
    val pickerEnabled = enabled && options.isNotEmpty()
    LaunchedEffect(pickerEnabled) {
        if (!pickerEnabled) showPicker.value = false
    }

    val normalColor = if (active) {
        etaPrimary()
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 颜色来自 owner 的 gptSpeedMode：非 GPT（gptSpeedSupported=false）直接回落普通原色，
    // 且最终 tint 当帧恢复原色，不保存旧档位。
    // Material 3 Expressive 的变色与旋转动效取官方 motionScheme：颜色属 Effects（无过冲），
    // 旋转属 Spatial（轻微过冲）。
    val speedFadeSpec: androidx.compose.animation.core.FiniteAnimationSpec<Color> =
        MaterialTheme.motionScheme.defaultEffectsSpec()
    val speedSpinSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> =
        MaterialTheme.motionScheme.defaultSpatialSpec()
    val contentColor by animateColorAsState(
        targetValue = if (!gptSpeedSupported) {
            normalColor
        } else {
            // Material 3：档位色取官方 colorScheme 角色（快速=tertiary、极速=error），不写死品牌色。
            etaGptSpeedColor(gptSpeedMode, normalColor)
        },
        animationSpec = speedFadeSpec,
        label = "thinking_content",
    )

    val displayedColor = if (gptSpeedSupported) contentColor else normalColor
    val canLongPressSpeed = gptSpeedSupported && enabled
    val interactive = pickerEnabled || canLongPressSpeed
    // Material 3 Expressive 取官方图标按钮尺寸令牌。
    val thinkingIconSize = etaOfficialIconButtonIconSize(ChatInputActionSize)
    val context = LocalContext.current
    val progress = remember { Animatable(0f) }
    val gate = remember { GptSpeedAnimationGate() }
    val scope = rememberCoroutineScope()

    // 用 rememberUpdatedState 固定回调身份：pointerInput 的 key 不随重组改变，
    // 避免长按手势被反复重启，也不让重组触发额外旋转。
    val pickerEnabledState = rememberUpdatedState(pickerEnabled)
    val canLongPressState = rememberUpdatedState(canLongPressSpeed)
    val gptSpeedModeState = rememberUpdatedState(gptSpeedMode)
    val contextState = rememberUpdatedState(context)
    val latestOnCycle by rememberUpdatedState(onCycleGptSpeedMode)

    val openPicker: () -> Unit = remember {
        { if (pickerEnabledState.value) showPicker.value = true }
    }
    val cycleSpeed: () -> Unit = remember {
        {
            // 动画中拒绝新的长按：不排队、不累计圈数，避免截断当前一圈或产生过期切换。
            if (canLongPressState.value) {
                val token = gate.request()
                if (token != 0) {
                    val nextMode = gptSpeedModeState.value.next()
                    latestOnCycle()
                    Toast.makeText(
                        contextState.value,
                        gptSpeedToastMessage(nextMode),
                        Toast.LENGTH_SHORT,
                    ).show()
                    scope.launch {
                        try {
                            progress.snapTo(0f)
                            progress.animateTo(
                                targetValue = 1f,
                                animationSpec = speedSpinSpec,
                            )
                        } finally {
                            // 携带自己的 token：被取消的旧动画不会关掉随后新动画的门禁。
                            gate.finish(token)
                        }
                    }
                }
            }
        }
    }

    // 模型切到非 GPT：立即清动画（取消进行中的一圈并复位），最终 tint 当帧恢复原色。
    LaunchedEffect(gptSpeedSupported) {
        if (!gptSpeedSupported) {
            gate.reset()
            progress.snapTo(0f)
        }
    }

    val contentDescription = if (gptSpeedSupported) {
        gptSpeedChipDescription(
            stringResource(R.string.chat_reasoning_effort, stringResource(effort.labelRes)),
            gptSpeedMode,
        )
    } else {
        stringResource(R.string.chat_reasoning_effort, stringResource(effort.labelRes))
    }

    ChatInputNonFocusableIconButton(
        onClick = openPicker,
        contentDescription = contentDescription,
        onLongClick = if (canLongPressSpeed) cycleSpeed else null,
        longClickLabel = gptSpeedLongPressLabel(),
        // 不可用时交给官方按钮的 disabled 状态（容器色、内容色、涟漪一起走官方定义）。
        enabled = interactive,
        modifier = modifier,
    ) {
        // 官方图标替代自绘 ic_atom：AutoAwesome（星形）语义为 AI 生成/思考，与项目"思考深度"图标一致。
        val atomIcon = Icons.Rounded.AutoAwesome
        val atomModifier = Modifier
            .size(thinkingIconSize)
            .then(
                if (gptSpeedSupported) {
                    Modifier.graphicsLayer {
                        rotationZ = gptSpeedRotationDegrees(progress.value)
                        val scale = gptSpeedScale(progress.value)
                        scaleX = scale
                        scaleY = scale
                    }
                } else {
                    Modifier
                },
            )
        if (!interactive) {
            // 官方禁用内容色即最终色，不再叠加本地透明度。
            Icon(imageVector = atomIcon, contentDescription = null, modifier = atomModifier)
        } else {
            Icon(
                imageVector = atomIcon,
                contentDescription = null,
                modifier = atomModifier,
                tint = if (interactive) {
                    displayedColor
                } else {
                    displayedColor.copy(alpha = DisabledIconAlpha)
                },
            )
        }
    }
    ThinkingEffortPickerDialog(
        show = showPicker.value && pickerEnabled,
        effort = effort,
        options = options,
        onDismiss = { showPicker.value = false },
        onEffortChange = onEffortChange,
    )
}

/**
 * 思考深度选择弹窗：一个“当前档位”的大标题 + 官方离散滑块 + 档位标签。
 *
 * 官方 `Slider`（离散 steps + 官方刻度/停止指示器）、官方 `SliderDefaults` 配色、
 * `MaterialTheme` 排版与色角色；选中值的展示用官方 `headlineMediumEmphasized`
 * （Expressive 强调字阶）。
 *
 * 交互：点击 / 拖动落在最近的离散档位，跨档位时触发一次轻触反馈，
 * 档位变化即时回调 [onEffortChange]。
 */
@Composable
internal fun ThinkingEffortPickerDialog(
    show: Boolean,
    effort: ReasoningEffort,
    options: List<ReasoningEffort>,
    onDismiss: () -> Unit,
    onEffortChange: (ReasoningEffort) -> Unit,
    description: String? = null,
) {
    if (options.isEmpty()) return
    val view = LocalView.current
    val selectedIndex = options.indexOf(effort).coerceAtLeast(0)
    var sliderValue by remember { mutableFloatStateOf(selectedIndex.toFloat()) }
    var lastHapticIndex by remember { mutableIntStateOf(selectedIndex) }
    val latestEffort by rememberUpdatedState(effort)
    val latestOptions by rememberUpdatedState(options)
    val latestOnEffortChange by rememberUpdatedState(onEffortChange)
    LaunchedEffect(show, selectedIndex, options) {
        if (show) {
            sliderValue = selectedIndex.toFloat()
            lastHapticIndex = selectedIndex
        }
    }
    val previewIndex = sliderValue.roundToInt().coerceIn(0, options.lastIndex)
    val preview = options[previewIndex]
    val maxIndex = (options.size - 1).toFloat().coerceAtLeast(0f)

    // 官方 Slider 自己处理拖动 / 点击 / 吸附（它已经按刻度对齐，且轨道两端有拇指半宽的
    // 留白，自绘坐标层反而会错位）。这里只接收“当前值”和“结束”两个信号。
    val onScrub: (Float) -> Unit = { raw ->
        val coerced = raw.coerceIn(0f, maxIndex)
        sliderValue = coerced
        val index = coerced.roundToInt().coerceIn(0, latestOptions.lastIndex)
        if (index != lastHapticIndex) {
            lastHapticIndex = index
            TouchHaptics.click(view)
        }
    }
    val onCommit: () -> Unit = {
        val index = sliderValue.roundToInt().coerceIn(0, latestOptions.lastIndex)
        val next = latestOptions[index]
        if (next != latestEffort) latestOnEffortChange(next)
    }

    EtaDialog(
        show = show,
        title = stringResource(R.string.reasoning_picker_title),
        onDismissRequest = onDismiss,
        actions = {
            M3TextButton(onClick = onDismiss) {
                M3Text(text = stringResource(R.string.action_close))
            }
        },
    ) {
        M3ThinkingEffortPickerBody(
            description = description,
            preview = preview,
            options = options,
            sliderValue = sliderValue.coerceIn(0f, maxIndex),
            maxIndex = maxIndex,
            onScrub = onScrub,
            onCommit = onCommit,
        )
    }
}

/**
 * Material 3 Expressive：官方组件 + 官方令牌。
 *
 * 滑块直接用官方 `Slider`（离散 `steps`）的拖动 / 点击 / 吸附行为，不再叠加自绘坐标层：
 * 官方实现已按刻度对齐，且对轨道两端做了“拇指半宽”的留白，自绘的线性映射会与其错位。
 * 这里只把「当前值」与「拖动结束」两个信号回传给父层统一做触感与业务回调。
 */
@Composable
private fun M3ThinkingEffortPickerBody(
    description: String?,
    preview: ReasoningEffort,
    options: List<ReasoningEffort>,
    sliderValue: Float,
    maxIndex: Float,
    onScrub: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    // 官方离散滑块状态：steps = 档位数 − 2（首尾落在 trackRange 两端）。
    val state = rememberSliderState(
        value = sliderValue,
        steps = (options.size - 2).coerceAtLeast(0),
        trackRange = 0f..maxIndex,
    )
    // 外部（重新打开弹窗 / 复位）改变档位时同步到官方状态；拖动过程中不回灌，避免与手势抢状态。
    LaunchedEffect(sliderValue) {
        if (!state.isDragging && state.value != sliderValue) state.value = sliderValue
    }
    val on = preview != ReasoningEffort.OFF
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        description?.let {
            M3Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        // AI 思考语义的星形图标，颜色跟随官方强调色 / 中性色。
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            modifier = Modifier
                .padding(top = 8.dp)
                .size(IconButtonDefaults.largeIconSize),
            tint = if (on) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        // 当前档位：Expressive 强调字阶（headlineMediumEmphasized），比 title 更大更醒目。
        M3Text(
            text = stringResource(preview.labelRes),
            style = MaterialTheme.typography.headlineMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        if (options.size > 1) {
            M3Slider(
                state = state,
                // 官方 Slider 只回调、不写 state：这里就地回写（onScrub 内已做档位吸附），
                // 再通知父层更新大字预览与触感。
                onValueChange = { raw ->
                    if (state.value != raw) state.value = raw
                    onScrub(state.value)
                },
                onValueChangeFinished = onCommit,
                // 供回归测试定位官方滑块（见 ThinkingEffortPickerThemeTest）。
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("thinking-effort-slider"),
            )
            // 离散滑块的官方做法：两端各标一个边界文案，让人一眼知道可选范围。
            Row(modifier = Modifier.fillMaxWidth()) {
                M3Text(
                    text = stringResource(options.first().labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                M3Text(
                    text = stringResource(options.last().labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 单档位：没有可拖动轨道，只给一条官方容器的静态说明条。
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                M3Text(
                    text = stringResource(preview.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}

internal fun discreteSliderIndexForTap(x: Float, width: Float, count: Int): Int {
    if (count <= 1 || width <= 0f) return 0
    val fraction = (x / width).coerceIn(0f, 1f)
    return (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
}

/**
 * 横向跟随 Chip，竖向则避开整个输入面板；默认下拉定位只会避开 Chip 自身。
 */
@Composable
internal fun PendingImageStrip(
    images: List<PendingImageUi>,
    onRemoveImage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        images.forEach { image ->
            Box(
                modifier = Modifier
                    .size(60.dp)
                    // 同心：图框圆角半径 = 「×」圆钮半径（18/2 = 9dp）+ 端距（4dp）= 13dp，
                    // 圆角弧心与圆钮圆心重合（与输入胶囊端盖同一套“半径差 = 间距”的同心规则）。
                    .clip(RoundedCornerShape(13.dp))
                    .background(etaSurfaceContainer()),
            ) {
                rememberDataUrlBitmap(image.dataUrl, fallback = image.uri)?.let { bitmap ->
                    ChatClickableImage(
                        source = image.uri,
                        bitmap = bitmap,
                        contentDescription = stringResource(
                            if (image.isVideo) R.string.chat_video_preview else R.string.chat_image_preview,
                        ),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (image.isVideo) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.62f))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        M3Text(
                            text = AgentVideoCodec.formatDuration(image.durationMs ?: 0L),
                            style = etaTextStyle(EtaType.Secondary),
                            color = Color.White,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(EtaDimens.DenseIconSize),
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        // 第三容器色（用户指定）：与前景 onTertiaryContainer 配对，保证「×」可读。
                        .background(etaTertiaryContainer())
                        .clickable { onRemoveImage(image.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ui_remove_image_089db3),
                        modifier = Modifier.size(EtaDimens.MicroIconSize),
                        tint = etaOnTertiaryContainer(),
                    )
                }
            }
        }
    }
}


@Composable
internal fun AssistantPickerButton(
    enabled: Boolean,
    selectedAssistantId: String = "",
    onEditAssistant: (String) -> Unit,
    onAssistantSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    containerShape: androidx.compose.ui.graphics.Shape? = null,
    containerColor: androidx.compose.ui.graphics.Color? = null,
    containerSize: Dp = ChatInputActionSize,
    connectedShapes: androidx.compose.material3.ToggleButtonShapes? = null,
) {
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = selectedAssistantId.ifBlank { repositoryActiveId }
    var showPicker by remember { mutableStateOf(false) }
    val assistant = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    // Material 3 Expressive 用官方图标尺寸令牌（调用方给 56dp 时即 medium 图标尺寸）。
    val avatarSize = etaOfficialIconButtonIconSize(containerSize)
    LaunchedEffect(enabled) {
        if (!enabled) showPicker = false
    }
    ChatInputNonFocusableIconButton(
        onClick = { if (enabled) showPicker = true },
        contentDescription = stringResource(R.string.assistant_selector),
        enabled = enabled,
        modifier = modifier,
        containerShape = containerShape,
        containerColor = containerColor,
        containerSize = containerSize,
        connectedShapes = connectedShapes,
    ) {
        Box(modifier = Modifier.size(avatarSize)) {
            AssistantAvatar(
                assistant = assistant,
                size = avatarSize,
                // 头像是图片内容而非容器，官方图标按钮的 disabled 着色不覆盖它，保留弱化。
                modifier = Modifier.graphicsLayer(alpha = if (enabled) 1f else DisabledIconAlpha),
            )
        }
    }
    AssistantPickerDialog(
        show = showPicker && enabled,
        onDismiss = { showPicker = false },
        onSelect = { id ->
            showPicker = false
            onAssistantSelected(id)
        },
        onEdit = { id ->
            showPicker = false
            onEditAssistant(id)
        },
        selectedAssistantId = activeId,
    )
}

internal fun resolveChatComposerSendMode(
    isStreaming: Boolean,
    isPaused: Boolean,
    hasSteerContent: Boolean,
    canStartNewSend: Boolean,
    isCompressingContext: Boolean = false,
    canContinueDisconnected: Boolean = false,
): String = when {
    // 压缩进行中禁止追加/续写：有待发内容时显示灰色发送键，点击只提示。
    // 没有内容时，暂停后显示继续；未暂停的流式仍可停止。
    isCompressingContext && hasSteerContent -> "blocked"
    isCompressingContext && isPaused -> "continue"
    isCompressingContext && isStreaming -> "stop"
    isCompressingContext -> "idle"
    (isStreaming || isPaused) && hasSteerContent -> "send"
    isPaused -> "continue"
    isStreaming -> "stop"
    // Terminal failure: Continue creates a new logical turn, never resumes the failed one.
    canContinueDisconnected && hasSteerContent -> "send"
    canContinueDisconnected -> "continue"
    canStartNewSend -> "send"
    else -> "idle"
}

@Composable
internal fun VoiceEntryButton(
    interactionBlocked: Boolean,
    resetKey: Any?,
    voiceState: VoiceModeState,
    onStartVoiceMode: (VoiceEntryMode) -> Unit,
    onStopVoiceMode: () -> Unit,
    /** 非空时用该图标渲染一个色调圆形按钮（Material 3 Expressive 外观），行为与语音入口一致。 */
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    /**
     * 空闲态是否也用 `FilledIconButton`（official primary/onPrimary）渲染。
     *
     * 新输入栏把语音入口放进输入框尾部，与发送按钮共用一个位置，属于“主要动作”；
     * 打开后空闲态与通话态同为填充色（图标区分状态），与参考稿一致。
     */
    idleFilled: Boolean = false,
    /**
     * 语音进行中是否把入口图标换成挂断图标（`CallEnd`）。
     *
     * 复合输入区（M3 胶囊）的语音入口在语音进行中会被整块替换成聆听条，挂断钮由聆听条提供；
     * 这个实例只剩交叉淡出的那一两帧。若仍跟着 `voiceState.active` 切图标，退场瞬间会先闪出
     * 挂断图标（“躺平的电话”）再消失。传 false 只冻结图标外观，点击行为不变（仍是结束语音）。
     */
    swapIconWhenActive: Boolean = true,
) {
    val view = LocalView.current
    val context = LocalContext.current
    var picker by remember { mutableStateOf(false) }
    var lastSelected by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_VOICE_LAST_ENTRY)) }
    fun rememberMode(mode: VoiceEntryMode) {
        lastSelected = mode.wireValue
        Prefs.putString(Prefs.Keys.AGENT_VOICE_LAST_ENTRY, mode.wireValue)
    }
    var pendingMode by remember { mutableStateOf<VoiceEntryMode?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val mode = pendingMode
        pendingMode = null
        if (granted && mode != null && io.github.mangi.eta.agent.voice.VoiceEntryPolicy.enabled(
                io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.value, mode)) onStartVoiceMode(mode)
        else if (!granted && mode != null) Toast.makeText(context, R.string.speech_permission_denied, Toast.LENGTH_LONG).show()
    }
    fun startMode(mode: VoiceEntryMode) {
        if (!io.github.mangi.eta.agent.voice.VoiceEntryPolicy.enabled(
                io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.value, mode)) return
        rememberMode(mode)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onStartVoiceMode(mode)
        } else {
            pendingMode = mode
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val config by io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.collectAsState()
    val modes = io.github.mangi.eta.agent.voice.VoiceEntryPolicy.modes(config)
    val canChoose = io.github.mangi.eta.agent.voice.VoiceEntryPolicy.canChoose(config)
    val directMode = io.github.mangi.eta.agent.voice.VoiceEntryPolicy.directMode(config, lastSelected)
    val directLabel = when (directMode) {
        VoiceEntryMode.DICTATION -> R.string.voice_mode_universal
        VoiceEntryMode.UNIVERSAL -> R.string.voice_mode_universal
        VoiceEntryMode.DOUBAO_DUPLEX -> R.string.voice_mode_doubao
        null -> R.string.voice_mode_choose
    }
    LaunchedEffect(Unit) { io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.load(context) }
    LaunchedEffect(modes) {
        if (!canChoose) picker = false
        if (pendingMode !in modes) pendingMode = null
        if (voiceState.mode != null && voiceState.mode !in modes) onStopVoiceMode()
    }
    LaunchedEffect(resetKey, interactionBlocked) {
        pendingMode = null
        picker = false
    }
    val active = voiceState.active || voiceState.error != null
    val choose: (() -> Unit)? = if (canChoose) ({ picker = true }) else null
    Box {
    if (icon != null) {
        // Material 3 Expressive：官方图标按钮。进行中用 FilledIconButton（官方默认
        // primary/onPrimary），空闲用 FilledTonalIconButton（官方默认
        // secondaryContainer/onSecondaryContainer）；形状取官方 filledShape（整圆），
        // 容器尺寸取官方 smallContainerSize()（与主页 / 侧边栏按钮统一为 Small 档），
        // 内容色交给 LocalContentColor。
        val onVoiceClick: () -> Unit = {
            TouchHaptics.click(view)
            when {
                active -> onStopVoiceMode()
                directMode != null -> startMode(directMode)
                modes.size > 1 -> picker = true
            }
        }
        val voiceIcon: @Composable () -> Unit = {
            val showsStop = active && swapIconWhenActive
            Icon(
                imageVector = if (showsStop) Icons.Rounded.CallEnd else icon,
                contentDescription = context.getString(
                    if (active) R.string.voice_mode_stop else directLabel,
                ),
                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
            )
        }
        // 只有「可选模式」存在时才挂长按宿主，否则直接透传图标。
        val voiceContent: @Composable () -> Unit = if (choose == null) {
            voiceIcon
        } else {
            {
                ChatInputLongPressSlot(
                    enabled = !interactionBlocked && !active,
                    onLongPress = choose,
                    longClickLabel = context.getString(directLabel),
                    content = voiceIcon,
                )
            }
        }
        val voiceModifier = Modifier.size(IconButtonDefaults.smallContainerSize())
        if (active || idleFilled) {
            FilledIconButton(
                onClick = onVoiceClick,
                modifier = voiceModifier,
                enabled = !interactionBlocked,
                content = voiceContent,
            )
        } else {
            FilledTonalIconButton(
                onClick = onVoiceClick,
                modifier = voiceModifier,
                enabled = !interactionBlocked,
                content = voiceContent,
            )
        }
    } else {
        // Material 3 Expressive：没有显式图标的语音入口（通话进行中 / 免提模式）改用官方
        // IconButton 承担命中区、官方形状与官方涟漪；长按切换语音模式用官方按钮内容里的
        // ChatInputLongPressSlot 承载（官方图标按钮没有 onLongClick 槽位）。
        val onVoiceClick: () -> Unit = {
            TouchHaptics.click(view)
            when {
                active -> onStopVoiceMode()
                directMode != null -> startMode(directMode)
                modes.size > 1 -> picker = true
            }
        }
        val longPress = if (!active) choose?.let { callback ->
            { TouchHaptics.longPress(view); callback() }
        } else {
            null
        }
        // 官方图标按钮没有 onClickLabel，内容是变形加载指示器也没有图标可播报，
        // 因此点击文案只能在 semantics 里显式给出（只描述，不消费）。
        // 长按语义由 ChatInputLongPressSlot 自己用 onLongClick key 声明（子节点，父为 null
        // 时合并进来）。不要再在按钮 modifier 上补一份：同 key 父非空会赢，会把共享助手的
        // 语义整份遮掉。
        val voiceModifier = Modifier
            .size(IconButtonDefaults.smallContainerSize())
            .then(
                if (modes.isNotEmpty() || active) {
                    Modifier.semantics {
                        contentDescription = context.getString(
                            if (active) R.string.voice_mode_stop else directLabel,
                        )
                    }
                } else {
                    Modifier
                }
            )
        val indicatorSlot: @Composable () -> Unit = {
            ContainedMorphLoadingIndicator(
                indicatorSize = if (active) 40.dp else 24.dp,
                animate = active,
            )
        }
        val content: @Composable () -> Unit = if (longPress != null) {
            {
                ChatInputLongPressSlot(
                    enabled = !interactionBlocked,
                    onLongPress = longPress,
                    longClickLabel = context.getString(directLabel),
                    content = indicatorSlot,
                )
            }
        } else {
            indicatorSlot
        }
        if (active) {
            FilledIconButton(
                onClick = onVoiceClick,
                modifier = voiceModifier,
                enabled = !interactionBlocked,
                content = content,
            )
        } else {
            FilledTonalIconButton(
                onClick = onVoiceClick,
                modifier = voiceModifier,
                enabled = !interactionBlocked,
                content = content,
            )
        }
    }
    VoiceEntryPickerDialog(
        show = picker && canChoose,
        modes = modes,
        onDismiss = { picker = false },
        onSelect = { mode ->
            picker = false
            pendingMode = null
            if (!interactionBlocked && io.github.mangi.eta.agent.voice.VoiceEntryPolicy.enabled(
                    io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.state.value, mode)) {
                TouchHaptics.click(view)
                // 选中即开始：听写已并入语音对话，这里只剩会话 / 实时通话两种入口。
                startMode(mode)
            }
        },
    )
    }
}

@Composable
private fun VoiceEntryPickerDialog(
    show: Boolean,
    modes: List<VoiceEntryMode>,
    onDismiss: () -> Unit,
    onSelect: (VoiceEntryMode) -> Unit,
) {
    // Material 3 Expressive 用官方 DropdownMenuPopup + DropdownMenuGroup + DropdownMenuItem
    // 的默认样式（组容器 surfaceContainerLow 圆角、Level2 阴影、48dp 行高、labelLarge）。
    EtaMenuPopup(
        expanded = show,
        onDismissRequest = onDismiss,
    ) {
        EtaMenuGroup(index = 0, count = 1) {
            // 语音输入与语音对话合并为一个入口：菜单只保留「语音对话」和「豆包实时通话」。
            val entries = listOf(
                Triple(VoiceEntryMode.UNIVERSAL, Icons.Rounded.RecordVoiceOver, R.string.voice_mode_universal),
                Triple(VoiceEntryMode.DOUBAO_DUPLEX, Icons.Rounded.GraphicEq, R.string.voice_mode_doubao),
            ).filter { it.first in modes }
            entries.forEachIndexed { index, (mode, icon, label) ->
                // 形状走 EtaMenuItemShape：多项按位置取 leading/middle/trailing，单项取 medium。
                DropdownMenuItem(
                    text = { M3Text(stringResource(label)) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    shape = EtaMenuItemShape(index, entries.size),
                    onClick = {
                        onDismiss()
                        onSelect(mode)
                    },
                )
            }
        }
    }
}

@Composable
internal fun VoiceModeStatusPanel(
    state: VoiceModeState,
    onStop: () -> Unit,
) {
    val phase = when {
        state.error != null -> state.error
        state.phase == VoiceModePhase.Connecting -> stringResource(R.string.voice_mode_connecting)
        state.phase == VoiceModePhase.Listening -> stringResource(R.string.voice_mode_listening)
        state.phase == VoiceModePhase.Transcribing -> stringResource(R.string.voice_mode_transcribing)
        state.phase == VoiceModePhase.Thinking -> stringResource(R.string.voice_mode_thinking)
        state.phase == VoiceModePhase.Speaking -> stringResource(R.string.voice_mode_speaking)
        else -> stringResource(R.string.voice_mode_open)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (state.mode == VoiceEntryMode.DOUBAO_DUPLEX) {
                    Icons.Rounded.GraphicEq
                } else {
                    Icons.Rounded.RecordVoiceOver
                },
                contentDescription = null,
                modifier = Modifier.size(EtaDimens.DenseIconSize),
                tint = if (state.error == null) etaPrimary() else StatusError,
            )
            M3Text(
                text = phase,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
                style = etaTextStyle(EtaType.Body),
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Stop,
                    contentDescription = stringResource(R.string.voice_mode_stop),
                    modifier = Modifier.size(EtaDimens.StatusIconSize),
                )
            }
        }
        val detail = listOfNotNull(
            state.transcript.takeIf { it.isNotBlank() }?.let {
                stringResource(R.string.voice_recognized_text, it)
            },
            state.reply.takeIf { it.isNotBlank() }?.let {
                stringResource(R.string.voice_reply_text, it)
            },
        ).joinToString("\n")
        if (detail.isNotBlank()) {
            VoiceTranscript(detail, resetKey = state.mode)
        }
    }
}

@Composable
private fun VoiceTranscript(detail: String, resetKey: Any?) {
    val scroll = rememberScrollState()
    var follow by remember(resetKey) { mutableStateOf(true) }
    var userGesture by remember(resetKey) { mutableStateOf(false) }
    val connection = remember(scroll, resetKey) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    userGesture = true
                    follow = false
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && userGesture) follow = !scroll.canScrollForward
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (userGesture) {
                    follow = !scroll.canScrollForward
                    userGesture = false
                }
                return Velocity.Zero
            }
        }
    }
    LaunchedEffect(scroll, resetKey) {
        snapshotFlow { Triple(scroll.maxValue, follow, scroll.isScrollInProgress) }.collectLatest { (end, enabled, moving) ->
            if (enabled && !moving && end > 0) scroll.scrollTo(end)
        }
    }
    M3Text(
        text = detail,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = etaTextStyle(EtaType.Secondary),
        modifier = Modifier.padding(start = 30.dp, end = 8.dp, bottom = 4.dp)
            .heightIn(max = 160.dp).nestedScroll(connection).verticalScroll(scroll),
    )
}
