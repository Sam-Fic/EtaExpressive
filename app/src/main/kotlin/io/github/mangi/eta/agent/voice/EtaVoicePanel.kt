package io.github.mangi.eta.agent.voice

import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CancelPresentation
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.AgentConversationMessages
import io.github.mangi.eta.ui.components.ChatImagePreviewHost
import io.github.mangi.eta.ui.components.ChatInputPopupMargin
import io.github.mangi.eta.ui.components.Material3ComposerBody
import io.github.mangi.eta.ui.components.PendingFileReferenceStrip
import io.github.mangi.eta.ui.components.PendingImageStrip
import io.github.mangi.eta.ui.components.collectPreviewableChatImages
import io.github.mangi.eta.ui.components.rememberDataUrlBitmap
import io.github.mangi.eta.ui.components.rememberImeVisible
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import kotlin.coroutines.resume
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import io.github.mangi.eta.ui.adaptive.EtaDimens

internal enum class EtaVoicePhase {
    READY,
    PROCESSING,
    ERROR,
}

internal data class EtaVoiceUiState(
    val messages: List<AgentChatMessageUi> = emptyList(),
    val phase: EtaVoicePhase = EtaVoicePhase.READY,
    val status: EtaVoiceStatus = EtaVoiceStatus.InputRequest,
    val screenContext: EtaScreenContextUiState = EtaScreenContextUiState(),
)

internal sealed interface EtaVoiceStatus {
    data object InputRequest : EtaVoiceStatus
    data object WaitingForAnswer : EtaVoiceStatus
    data object Reasoning : EtaVoiceStatus
    data object Completed : EtaVoiceStatus
    data class RunningTool(val name: String) : EtaVoiceStatus
    data class Failed(val detail: String?) : EtaVoiceStatus
    data object Stopped : EtaVoiceStatus
}

internal enum class EtaScreenContextPhase {
    CAPTURING,
    AVAILABLE,
    UNAVAILABLE,
    CONSUMED,
}

internal data class EtaScreenContextUiState(
    val phase: EtaScreenContextPhase = EtaScreenContextPhase.CONSUMED,
    val previewDataUrl: String? = null,
    val selected: Boolean = false,
)

internal object EtaScreenContextStateReducer {
    fun select(
        state: EtaScreenContextUiState,
        enabled: Boolean,
        hasAttachment: Boolean,
    ): EtaScreenContextUiState =
        if (enabled && hasAttachment && state.phase == EtaScreenContextPhase.AVAILABLE) {
            state.copy(selected = true)
        } else {
            state
        }

    fun remove(
        state: EtaScreenContextUiState,
        enabled: Boolean,
    ): EtaScreenContextUiState =
        if (enabled && state.selected) state.copy(selected = false) else state

    fun consume(): EtaScreenContextUiState = EtaScreenContextUiState(
        phase = EtaScreenContextPhase.CONSUMED,
    )
}

/**
 * 当前屏幕的快捷预设。
 *
 * 一次点击就等价于「带上屏幕截图 + 发送这句话」，省掉先点「添加当前屏幕」再打字的两步；
 * 自定义问题仍然可以走原来的屏幕 chip。点击时若屏幕已采好但未选中，入口服务会自动选中。
 */
internal enum class ScreenContextPreset(
    @StringRes val labelRes: Int,
    @StringRes val promptRes: Int,
    val icon: ImageVector,
) {
    TRANSLATE(
        labelRes = R.string.voice_screen_preset_translate,
        promptRes = R.string.voice_screen_preset_translate_prompt,
        icon = Icons.Rounded.Translate,
    ),
    ADD_CALENDAR(
        labelRes = R.string.voice_screen_preset_calendar,
        promptRes = R.string.voice_screen_preset_calendar_prompt,
        icon = Icons.Rounded.CalendarMonth,
    ),
    ORGANIZE(
        labelRes = R.string.voice_screen_preset_organize,
        promptRes = R.string.voice_screen_preset_organize_prompt,
        icon = Icons.Rounded.Summarize,
    ),
}

/**
 * 内容面板的高度档位：半屏 / 全屏两档。
 *
 * 只允许在这两档之间切换，不提供任意中间高度：拖拽跟手，松手按位置与惯性吸附到
 * 最近一档；档位切换动画统一走官方 M3E 空间弹簧（见 [AssistantPanel]）。
 */
internal enum class AssistantSheetMode {
    HALF,
    FULL,
}

/**
 * 半屏档高度占可用高度的比例；全屏档即可用高度的 100%。
 * 可用高度 = 整窗高度 − 顶部余量 − 状态栏 − 导航栏（见 [EtaVoicePanel]）。
 */
private const val AssistantHalfSheetFraction = 0.5f

/** 官方底部面板顶部圆角（M3 `BottomSheetDefaults` 同款 28dp）。 */
private val AssistantSheetTopCorner = 28.dp

/** 屏幕上下文 chip 行两端淡出带的宽度，与抽屉胶囊行同档（24dp）。 */
private val ScreenContextChipEdgeFadeWidth = 24.dp

/**
 * 入口弹出时的上移距离：内容面板从下方这个距离平移到位（M3E 弹簧）。
 */
private val AssistantEntryTranslation = 56.dp

/**
 * 退场时在整窗高度之上额外多推出去的余量：保证动画结束时连边角都不残留，
 * 而不是滑到一半等窗口被移除时「闪现消失」。
 */
private val AssistantExitOvershoot = 48.dp

/**
 * 退场动画时长：与 [EtaAssistantOverlayService] 里移除浮窗前等待的时长对应，
 * 反向平移回去、用普通非线性曲线（不用弹簧）。
 */
private const val AssistantExitDurationMillis = 220

/** 半屏档继续下拉超过该距离即关闭浮层（下拉关闭阈值）。 */
private val AssistantDismissThreshold = 92.dp

/** 全屏档继续上拉超过该距离即把对话交接回主程序（交接阈值）。 */
private val AssistantHandoffThreshold = 72.dp

/** 列表上滑时第二段交接的阈值（pre-scroll 阶段整段消费）。 */
private val AssistantDirectHandoffThreshold = 48.dp

/** 甩动速度阈值：快速下滑关闭、快速上滑展开，超过即按惯性翻档。 */
private val AssistantFlingVelocity = 900.dp

/**
 * 空态（仅浮动输入胶囊）时输入区下方渐变压暗的**最小**高度。实际高度跟随输入区
 * 实测高度（见 [AssistantInputBackdropExtraHeight]）：输入区早已不是单行，而是
 * 「屏幕上下文 chip + M3E 浮动工具栏（+ 附件预览条 / 语音聆听条）」。
 */
private val AssistantInputBackdropHeight = 104.dp

/** 渐变压暗在输入区实测高度之上再多出一小截，让顶端透明段落在输入框上方可见。 */
private val AssistantInputBackdropExtraHeight = 16.dp

@Composable
internal fun EtaVoicePanel(
    state: EtaVoiceUiState,
    textFieldState: TextFieldState,
    composer: EtaAssistantComposerState,
    voiceState: VoiceModeState,
    inputFocusRequestKey: Int,
    canOpenConversation: Boolean,
    exitRequested: Boolean,
    composerConversationId: String?,
    onStartVoiceMode: (VoiceEntryMode) -> Unit,
    onStopVoiceMode: () -> Unit,
    onScreenContextSelect: () -> Unit,
    onScreenContextRemove: () -> Unit,
    onScreenContextPreset: (ScreenContextPreset) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversation: () -> Unit,
    onQuestionDraftChanged: (String, String, io.github.mangi.eta.agent.question.AgentQuestionAnswer) -> Unit,
    onSubmitQuestionAnswer: (String, String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val panelEntry = remember { Animatable(0f) }
    val exitProgress = remember { Animatable(0f) }
    // 整窗高度（px）：退场要按它把内容完全推出屏幕，动画结束时任何内容都不再可见。
    var windowHeightPx by remember { mutableIntStateOf(0) }

    // 弹出：内容面板走 M3E 弹簧（纯平移入场），全程不掺透明度变化。
    // 底部面板在 M3E 里属于「中等元素」，取 defaultSpatialSpec（轻微过冲回弹）；
    // 透明度 / 颜色一律走 Effects 弹簧（临界阻尼、无过冲），见下方压暗层。
    // 弹簧规格须在组合期读取。
    val panelEntrySpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) {
        panelEntry.animateTo(
            targetValue = 1f,
            animationSpec = panelEntrySpec,
        )
    }

    // 退场：点空白处 / 返回键 / 交接时，面板**整体向下平移出屏**（压暗层同步淡出）。
    // 与入场相反，这里用普通非线性曲线而不用弹簧；位移量按整窗高度 + 余量计算，
    // 保证动画结束时所有内容都已经滑出屏幕，而不是走一半等窗口被移除时「闪现消失」。
    // 全程只有位移、不做淡出。
    LaunchedEffect(exitRequested) {
        if (!exitRequested) return@LaunchedEffect
        exitProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = AssistantExitDurationMillis,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    LaunchedEffect(inputFocusRequestKey) {
        if (inputFocusRequestKey < 0 || state.phase == EtaVoicePhase.PROCESSING) return@LaunchedEffect
        // 入口浮窗刚 addView 时还没有窗口焦点，此时 requestFocus / keyboard.show 会被系统
        // 忽略；等焦点真正到窗口后再让输入框聚焦并唤键盘，否则只会在键盘入射动画播放到
        // 一半时被系统收回，看起来就是「键盘闪现一下又浮起」。
        awaitWindowFocus(view)
        repeat(2) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
            ViewCompat.getWindowInsetsController(view)?.show(WindowInsetsCompat.Type.ime())
            delay(48)
        }
    }

    // Bottom Sheet 压暗层：官方 M3 scrim 角色 @ 32%（模态底部面板标准规格），与主程序
    // 同一取色链。只在有消息（面板可见）时压暗用户界面；空态仅一枚浮动输入胶囊，不遮挡。
    // 退场时随面板退场一起淡出，避免窗口被移除时压暗「闪现消失」。
    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
    val scrimAlpha by animateFloatAsState(
        targetValue = if (exitRequested || state.messages.isEmpty()) 0f else 1f,
        // 压暗层只变透明度，走 M3E Effects 弹簧（临界阻尼、无过冲），
        // 不掺 Spatial 的过冲，避免压暗层在淡入末尾「闪一下」。
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "assistant_scrim",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { windowHeightPx = it.height },
    ) {
        // 压暗 + 点击空白处关闭：绘制在内容面板之下，只透出面板上下的用户界面区域。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(color = scrimColor, alpha = scrimAlpha) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // 入场 / 退场只做位移，不做透明度渐变：移动过程中输入框始终是不透明的，
                // 不会在位移途中把下层用户界面透出来。
                .graphicsLayer {
                    translationY = if (exitRequested) {
                        // 退场：一次性把内容推过窗口下缘（再加一段余量），完全离开屏幕。
                        exitProgress.value * (windowHeightPx + with(density) {
                            AssistantExitOvershoot.toPx()
                        })
                    } else {
                        // 入场：M3E 弹簧从下方一小段距离平移到位。
                        (1f - panelEntry.value) * with(density) { AssistantEntryTranslation.toPx() }
                    }
                },
        ) {
            val imeBottom = WindowInsets.ime.getBottom(density)
            val navigationBottom = WindowInsets.navigationBars.getBottom(density)
            val statusTop = WindowInsets.statusBars.getTop(density)
            val bottomInset = max(imeBottom, navigationBottom)
            val imeOverlap = (imeBottom - navigationBottom).coerceAtLeast(0)
            val maxContentHeightPx = with(density) {
                (maxHeight - 88.dp).toPx() - statusTop - navigationBottom
            }.coerceAtLeast(with(density) { 220.dp.toPx() })
            // 图片预览宿主：与应用内（AgentChatBody）同款。消息里的图片与输入区待发缩略图
            // 都依赖 LocalOpenChatImagePreview，没有宿主时点击没有任何反应。
            ChatImagePreviewHost(
                gallery = collectPreviewableChatImages(state.messages, composer.pendingImages),
                onAttachImageToConversation = composer::attachImage,
                onAttachVideoToConversation = composer::attachVideo,
            ) {
                AssistantPanel(
                    state = state,
                    textFieldState = textFieldState,
                    composer = composer,
                    voiceState = voiceState,
                    focusRequester = focusRequester,
                    canOpenConversation = canOpenConversation,
                    maxContentHeightPx = maxContentHeightPx,
                    bottomInsetPx = bottomInset,
                    imeOverlapPx = imeOverlap,
                    composerConversationId = composerConversationId,
                    onStartVoiceMode = onStartVoiceMode,
                    onStopVoiceMode = onStopVoiceMode,
                    onScreenContextSelect = onScreenContextSelect,
                    onScreenContextRemove = onScreenContextRemove,
                    onScreenContextPreset = onScreenContextPreset,
                    onSubmit = {
                        keyboard?.hide()
                        onSubmit(it)
                    },
                    onStop = onStop,
                    onClose = onClose,
                    onOpenConversation = onOpenConversation,
                    onQuestionDraftChanged = onQuestionDraftChanged,
                    onSubmitQuestionAnswer = onSubmitQuestionAnswer,
                )
            }
        }
    }
}

/**
 * 等宿主 View 拿到窗口焦点。入口浮窗从 `addView` 到可接收输入之间有一小段空白期，
 * 这期间对输入框的任何聚焦 / 弹键盘请求都会被系统丢弃；超时兜底，避免窗口始终不
 * 聚焦时把整次键盘请求挂住。
 */
private suspend fun awaitWindowFocus(
    view: View,
    timeoutMillis: Long = 600L,
) {
    if (view.hasWindowFocus()) return
    suspendCancellableCoroutine { continuation ->
        val observer = view.viewTreeObserver
        val listener = object : ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                if (!hasWindowFocus || !continuation.isActive) return
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(this)
                continuation.resume(Unit)
            }
        }
        observer.addOnWindowFocusChangeListener(listener)
        view.postDelayed({
            if (continuation.isActive) {
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
                continuation.resume(Unit)
            }
        }, timeoutMillis)
        continuation.invokeOnCancellation {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
        }
    }
}

@Composable
private fun BoxScope.AssistantPanel(
    state: EtaVoiceUiState,
    textFieldState: TextFieldState,
    composer: EtaAssistantComposerState,
    voiceState: VoiceModeState,
    focusRequester: FocusRequester,
    canOpenConversation: Boolean,
    maxContentHeightPx: Float,
    bottomInsetPx: Int,
    imeOverlapPx: Int,
    composerConversationId: String?,
    onStartVoiceMode: (VoiceEntryMode) -> Unit,
    onStopVoiceMode: () -> Unit,
    onScreenContextSelect: () -> Unit,
    onScreenContextRemove: () -> Unit,
    onScreenContextPreset: (ScreenContextPreset) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversation: () -> Unit,
    onQuestionDraftChanged: (String, String, io.github.mangi.eta.agent.question.AgentQuestionAnswer) -> Unit,
    onSubmitQuestionAnswer: (String, String) -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 两档高度：半屏 / 全屏。拖拽跟手，松手按位置与惯性吸附到最近一档，
    // 不存在任意中间高度；档位切换统一走官方 M3E 空间弹簧。
    val halfSheetHeightPx = maxContentHeightPx * AssistantHalfSheetFraction
    var sheetMode by remember { mutableStateOf(AssistantSheetMode.HALF) }
    var draggedHeightPx by remember { mutableStateOf<Float?>(null) }
    var dismissPullPx by remember { mutableFloatStateOf(0f) }
    var handoffPullPx by remember { mutableFloatStateOf(0f) }
    var directHandoffPullPx by remember { mutableFloatStateOf(0f) }
    var thresholdHapticSent by remember { mutableStateOf(false) }
    var handoffRunning by remember { mutableStateOf(false) }
    var keepBottomAnchored by remember { mutableStateOf(true) }
    // 空态输入区下方渐变压暗的实测取材：输入区实测高度（含自身内边距），
    // 输入区长高 / 缩短时渐变同步变高。绘制阶段读取，只触发重绘不触发重组。
    var composerHeightPx by remember { mutableFloatStateOf(0f) }
    val handoffThresholdPx = with(density) { AssistantHandoffThreshold.toPx() }
    val directHandoffThresholdPx = with(density) { AssistantDirectHandoffThreshold.toPx() }
    val dismissThresholdPx = with(density) { AssistantDismissThreshold.toPx() }
    val flingVelocityPx = with(density) { AssistantFlingVelocity.toPx() }
    val hasMessages = state.messages.isNotEmpty()
    val targetHeightPx = if (hasMessages) {
        when (sheetMode) {
            AssistantSheetMode.HALF -> halfSheetHeightPx
            AssistantSheetMode.FULL -> maxContentHeightPx
        }
    } else {
        0f
    }
    // 高度唯一来源：[Animatable]。拖拽时 snapTo 直接写当前值（跟手），松手时
    // animateTo 从**当前手指位置**续接到目标档 —— 不会先跳回旧档位再播放。
    // 弹簧规格须在组合期读取（MaterialTheme.motionScheme 是 @Composable getter）。
    val sheetHeight = remember { Animatable(0f) }
    val sheetHeightSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    // 面板底与消息显现都只联动透明度：按 M3E 分类取 Effects 弹簧（临界阻尼、无过冲），
    // 入场用 default、收起用 fast，避免在大块面板上出现 alpha 过冲。
    val sheetEffectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val sheetBackgroundAlpha = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = sheetEffectsSpec,
        label = "assistant_sheet_background",
    )
    val messageRevealProgress = animateFloatAsState(
        targetValue = if (hasMessages) 1f else 0f,
        animationSpec = if (hasMessages) {
            sheetEffectsSpec
        } else {
            MaterialTheme.motionScheme.fastEffectsSpec()
        },
        label = "assistant_message_reveal",
    )
    val sheetHeightPx = draggedHeightPx ?: sheetHeight.value
    val visibleSheetHeightPx = sheetHeightPx.coerceAtMost(
        (maxContentHeightPx - imeOverlapPx).coerceAtLeast(0f),
    )
    val handoffReady = canOpenConversation &&
        (handoffPullPx >= handoffThresholdPx ||
            directHandoffPullPx >= directHandoffThresholdPx)

    /**
     * 吸附到目标高度：从 [sheetHeight] 的当前值（即手指松手位置）开始续接播放，
     * 并把松手瞬间的甩动速度作为初速度，运动状态与手指连贯、无跳跃。
     */
    fun settleSheet(target: Float, velocityY: Float = 0f) {
        scope.launch {
            sheetHeight.animateTo(
                targetValue = target,
                animationSpec = sheetHeightSpec,
                initialVelocity = -velocityY,
            )
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) keepBottomAnchored = true
    }
    // 首条消息出现（面板从无到有）：从 0 长起到当前档位。
    LaunchedEffect(hasMessages) {
        if (hasMessages && draggedHeightPx == null) {
            settleSheet(targetHeightPx)
        }
    }
    LaunchedEffect(handoffReady) {
        if (handoffReady && !thresholdHapticSent) {
            thresholdHapticSent = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else if (!handoffReady) {
            thresholdHapticSent = false
        }
    }

    fun triggerHandoff(velocityY: Float = 0f) {
        if (handoffRunning || !canOpenConversation) return
        handoffRunning = true
        // 交接动画期间先吸附到全屏档，避免交接途中高度掉回半屏。
        sheetMode = AssistantSheetMode.FULL
        draggedHeightPx = null
        settleSheet(maxContentHeightPx, velocityY)
        scope.launch {
            delay(120)
            onOpenConversation()
        }
    }

    /**
     * 拖拽跟手：按当前基准高度与手指位移请求新高度（上拉变大、下拉变小），
     * 同时刷新「半屏档继续下拉（关闭）」与「全屏档继续上拉（交接）」的越界量。
     * 高度直接写 [sheetHeight] 当前值（不播放动画），松手后由 [settleSheet] 从此处续接。
     */
    fun dragBy(deltaY: Float): Float {
        if (handoffRunning || state.messages.isEmpty()) return 0f
        val base = draggedHeightPx ?: sheetHeight.value
        val requested = base - deltaY
        val clamped = requested.coerceIn(0f, maxContentHeightPx)
        draggedHeightPx = clamped
        dismissPullPx = (halfSheetHeightPx - requested).coerceAtLeast(0f)
        handoffPullPx = (requested - maxContentHeightPx).coerceAtLeast(0f)
        scope.launch { sheetHeight.snapTo(clamped) }
        return deltaY
    }

    /**
     * 松手：先判「继续上拉交接 / 继续下拉关闭」，其余按位置与惯性在两档之间吸附。
     * 吸附一律从松手位置续接（见 [settleSheet]），不再有「跳回原位再播放」。
     * 速度判据只对面板自身拖拽生效，避免消息列表的甩动被误判成翻档 / 关闭。
     */
    fun finishDrag(velocityY: Float = 0f) {
        if (state.messages.isEmpty()) return
        val wasDragging = draggedHeightPx != null
        val current = draggedHeightPx ?: sheetHeight.value
        draggedHeightPx = null
        val dismissPull = dismissPullPx
        val handoffPull = handoffPullPx
        dismissPullPx = 0f
        handoffPullPx = 0f
        directHandoffPullPx = 0f
        val flingUp = wasDragging && velocityY <= -flingVelocityPx
        val flingDown = wasDragging && velocityY >= flingVelocityPx
        val pastMidpoint = current >= (halfSheetHeightPx + maxContentHeightPx) / 2f
        when {
            // 全屏档继续上拉：交接回主程序（或快速上甩）。
            handoffPull >= handoffThresholdPx ||
                (flingUp && current >= maxContentHeightPx * 0.98f) ->
                triggerHandoff(velocityY)
            // 半屏档继续下拉：关闭浮层（或快速下甩）。
            dismissPull >= dismissThresholdPx ||
                (flingDown && current < halfSheetHeightPx) ->
                onClose()
            pastMidpoint || flingUp -> {
                sheetMode = AssistantSheetMode.FULL
                settleSheet(maxContentHeightPx, velocityY)
            }
            else -> {
                sheetMode = AssistantSheetMode.HALF
                settleSheet(halfSheetHeightPx, velocityY)
            }
        }
    }

    val dragByState = rememberUpdatedState<(Float) -> Float>(::dragBy)
    val finishDragState = rememberUpdatedState<(Float) -> Unit>(::finishDrag)
    val nestedScrollConnection = remember(
        halfSheetHeightPx,
        maxContentHeightPx,
        canOpenConversation,
        listState,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val current = draggedHeightPx ?: sheetHeight.value
                if (
                    available.y < 0f &&
                    canOpenConversation &&
                    current >= maxContentHeightPx * 0.98f
                ) {
                    directHandoffPullPx += -available.y
                    if (directHandoffPullPx >= directHandoffThresholdPx) {
                        triggerHandoff()
                    }
                    // 第二段上滑由父容器在 pre-scroll 阶段完整消费，避免列表或
                    // overscroll 先截走事件后，接管手势永远达不到阈值。
                    return Offset(0f, available.y)
                }
                // 未到全屏时上滑先长高、到顶后下滑先收缩（半屏档继续下滑即下拉关闭），
                // 两档之外不消费，交还列表自身滚动。
                val shouldResize = (available.y < 0f && current < maxContentHeightPx) ||
                    (available.y > 0f && current > halfSheetHeightPx && !listState.canScrollBackward)
                return if (shouldResize) {
                    Offset(0f, dragByState.value(available.y))
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y == 0f) return Offset.Zero
                val current = draggedHeightPx ?: sheetHeight.value
                val atUpperEdge = available.y < 0f && current >= maxContentHeightPx * 0.98f
                val atLowerEdge = available.y > 0f && current <= halfSheetHeightPx
                return if (atUpperEdge || atLowerEdge) {
                    Offset(0f, dragByState.value(available.y))
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                finishDragState.value(available.y)
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                finishDragState.value(available.y)
                return Velocity.Zero
            }
        }
    }

    val bottomInset = with(density) { bottomInsetPx.toDp() }
    val messageRevealOffsetPx = with(density) { 12.dp.toPx() }
    // 官方底部面板形状：顶部 28dp 圆角（M3 BottomSheetDefaults 同款）。
    val sheetShape = RoundedCornerShape(
        topStart = AssistantSheetTopCorner,
        topEnd = AssistantSheetTopCorner,
    )
    // 面板底色：官方 surfaceContainer 角色（与主程序聊天背景同一取色、同一条动态取色链），
    // 全不透明。有消息时整块面板自消息区到屏幕底沿同色衔接，不叠任何渐变。
    val sheetSurface = MaterialTheme.colorScheme.surfaceContainer
    // 空态渐变：与模态压暗同色（官方 scrim 角色 @ 32%），Brush 只建一次、按帧复用。
    val inputBackdropColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
    val inputBackdropBrush = remember(inputBackdropColor) {
        Brush.verticalGradient(colors = listOf(Color.Transparent, inputBackdropColor))
    }
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth(),
        shape = sheetShape,
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    // 空态（仅浮动输入胶囊）：输入区下方渐变压暗，把胶囊「压」在用户界面上；
                    // 有消息时被上方不透明面板底色完全盖住，并随底色淡入同步淡出，
                    // 不会重现「输入框下面一块灰」的断裂带。
                    val backdropFade = 1f - sheetBackgroundAlpha.value
                    if (backdropFade > 0f) {
                        val scrimHeightPx = maxOf(
                            AssistantInputBackdropHeight.toPx(),
                            composerHeightPx + AssistantInputBackdropExtraHeight.toPx(),
                        )
                        drawRect(
                            brush = inputBackdropBrush,
                            topLeft = Offset(x = 0f, y = size.height - scrimHeightPx),
                            size = Size(size.width, scrimHeightPx),
                            alpha = backdropFade,
                        )
                    }
                    // 有消息（Bottom Sheet 可见）：整块面板只用 surfaceContainer 实色，
                    // 自消息区到屏幕底沿同色衔接。
                    drawRect(color = sheetSurface, alpha = sheetBackgroundAlpha.value)
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { visibleSheetHeightPx.toDp() })
                    .nestedScroll(nestedScrollConnection),
            ) {
                // 手势条只在有消息（面板可见）时出现；空态只有一枚浮动输入胶囊。
                if (hasMessages) {
                    DragHandle(
                        modifier = Modifier.pointerInput(halfSheetHeightPx, maxContentHeightPx) {
                            detectVerticalDragGestures(
                                onDragStart = { draggedHeightPx = sheetHeight.value },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    dragBy(dragAmount)
                                },
                                onDragEnd = { finishDrag() },
                                onDragCancel = { finishDrag() },
                            )
                        },
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .graphicsLayer {
                            alpha = messageRevealProgress.value
                            translationY = (1f - messageRevealProgress.value) * messageRevealOffsetPx
                        },
                ) {
                    if (hasMessages) {
                        AgentConversationMessages(
                            visibleMessages = state.messages,
                            scrollState = listState,
                            isStreaming = state.phase == EtaVoicePhase.PROCESSING,
                            bottomInset = 8.dp,
                            keepBottomAnchored = keepBottomAnchored,
                            onBottomAnchorChanged = { keepBottomAnchored = it },
                            onQuestionDraftChanged = onQuestionDraftChanged,
                            onSubmitQuestionAnswer = onSubmitQuestionAnswer,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            AssistantComposer(
                state = state,
                textFieldState = textFieldState,
                composer = composer,
                voiceState = voiceState,
                focusRequester = focusRequester,
                composerConversationId = composerConversationId,
                onStartVoiceMode = onStartVoiceMode,
                onStopVoiceMode = onStopVoiceMode,
                onScreenContextSelect = onScreenContextSelect,
                onScreenContextRemove = onScreenContextRemove,
                onScreenContextPreset = onScreenContextPreset,
                onSubmit = onSubmit,
                onStop = onStop,
                modifier = Modifier
                    .onSizeChanged { composerHeightPx = it.height.toFloat() }
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = bottomInset + 10.dp,
                    ),
            )
        }
    }
}

@Composable
private fun AssistantComposer(
    state: EtaVoiceUiState,
    textFieldState: TextFieldState,
    composer: EtaAssistantComposerState,
    voiceState: VoiceModeState,
    focusRequester: FocusRequester,
    composerConversationId: String?,
    onStartVoiceMode: (VoiceEntryMode) -> Unit,
    onStopVoiceMode: () -> Unit,
    onScreenContextSelect: () -> Unit,
    onScreenContextRemove: () -> Unit,
    onScreenContextPreset: (ScreenContextPreset) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val processing = state.phase == EtaVoicePhase.PROCESSING
    // 尾部按钮槽只在一个条件下会腾出语音入口：sendMode == "idle" 且无内容。sendMode 由
    // canSend 推导，所以 canSend 必须和应用内一样把「草稿有可发送内容」算进去，
    // 否则空草稿也判定为可发送，发送按钮会一直占住尾部，语音入口永远不出现。
    val hasDraftContent = textFieldState.text.isNotBlank() ||
        composer.pendingImages.isNotEmpty() ||
        composer.pendingFileReferences.isNotEmpty()
    val density = LocalDensity.current
    // Not snapshot state: the panel reports its position on every layout, and the IME moves
    // it during the keyboard animation. Observing that here would recompose the panel per
    // keyboard frame; the value is read on demand when the popup is composed instead.
    val inputContainerTopPx = remember { intArrayOf(0) }
    // 「助手与模型」选择弹窗向上展开的最大高度：到状态栏为止，与应用内输入区同一套算法
    // （浮窗没有抽屉，直接用窗口内容顶到状态栏的距离）。
    // 状态栏顶部同样在组合期读取一次：`WindowInsets.statusBars` 是 @Composable getter，
    // 不能放进延迟到弹窗组合时才执行的 lambda 里。
    val statusBarTopPx = WindowInsets.statusBars.getTop(density)
    val thinkingPopupMaxHeight: () -> Dp = remember(density, statusBarTopPx) {
        {
            with(density) {
                (inputContainerTopPx[0] - statusBarTopPx).coerceAtLeast(0).toDp()
            }.minus(ChatInputPopupMargin * 2)
                .coerceAtLeast(50.dp)
        }
    }

    // 输入区进出场动效统一取与应用内输入区同一套官方 Expressive 弹簧规格。
    // 按 M3E 分类：淡入淡出是透明度，走 Effects（临界阻尼、无过冲）；
    // 附件条的高度展开收缩是尺寸变化，走 Spatial（轻微过冲）。
    val fadeEnterSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()
    val fadeExitSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.fastEffectsSpec()
    val shrinkTallSpec: FiniteAnimationSpec<IntSize> = MaterialTheme.motionScheme.defaultSpatialSpec()
    // 快捷预设只在屏幕已采好、且输入区还是空的时候出现：一点即发，不抢用户正在打的草稿。
    // 语音进行中也保留（点预设会先退出语音再发送），与「添加当前屏幕」同排常驻。
    val showScreenPresets = state.screenContext.phase == EtaScreenContextPhase.AVAILABLE &&
        !hasDraftContent
    Column(modifier = modifier) {
        ScreenContextAttachment(
            state = state.screenContext,
            enabled = !processing,
            showPresets = showScreenPresets,
            onSelect = onScreenContextSelect,
            onRemove = onScreenContextRemove,
            onPreset = onScreenContextPreset,
        )
        if (state.screenContext.phase != EtaScreenContextPhase.CONSUMED) {
            Spacer(Modifier.height(8.dp))
        }
        // 附件预览条：与应用内输入区一致，文件引用在上、图片 / 视频在下，都渲染在输入框之上；
        // 进出场也与应用内同款（淡入淡出 + 纵向展开/收缩）。
        // （`Material3ComposerBody` 自身不渲染预览条，需由调用方提供。）
        AnimatedVisibility(
            visible = composer.pendingFileReferences.isNotEmpty(),
            enter = fadeIn(fadeEnterSpec),
            exit = fadeOut(fadeExitSpec) + shrinkVertically(shrinkTallSpec),
        ) {
            PendingFileReferenceStrip(
                references = composer.pendingFileReferences,
                onRemoveReference = composer::removePendingFileReference,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        AnimatedVisibility(
            visible = composer.pendingImages.isNotEmpty(),
            enter = fadeIn(fadeEnterSpec),
            exit = fadeOut(fadeExitSpec) + shrinkVertically(shrinkTallSpec),
        ) {
            PendingImageStrip(
                images = composer.pendingImages,
                onRemoveImage = composer::removePendingImage,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Material3ComposerBody(
            textFieldState = textFieldState,
            focusRequester = focusRequester,
            imeVisible = rememberImeVisible(),
            // 入口浮层没有「编辑消息」与「暂停运行」，这些槽位按不适用处理。
            isEditingMessage = false,
            isStreaming = processing,
            isPaused = false,
            isCompressingContext = false,
            canContinueDisconnected = false,
            contextSendBlocked = false,
            canSend = composer.canSend(hasDraftContent),
            usage = composer.usage,
            popupMaxHeight = thinkingPopupMaxHeight,
            onPositionedTopPx = { inputContainerTopPx[0] = it },
            // 传入浮窗会话 key，避免空 key 触发「保存草稿 / 定时发送」的额外副作用。
            conversationId = composerConversationId,
            assistantId = composer.assistantId,
            modelPickerState = composer.modelPickerState,
            reasoningEffort = composer.reasoningEffort,
            availableReasoningEfforts = composer.availableReasoningEfforts,
            gptSpeedMode = composer.gptSpeedMode,
            gptSpeedSupported = composer.gptSpeedSupported,
            // 入口浮层不放「助手与模型」按钮：这里没有模型切换的上下文，发送走当前全局默认选择。
            showModelPicker = false,
            voiceState = voiceState,
            showMorphLoading = processing,
            pendingImages = composer.pendingImages,
            pendingFileReferences = composer.pendingFileReferences,
            conversationMentions = ConversationMentionInputUi(),
            onStartVoiceMode = onStartVoiceMode,
            onStopVoiceMode = onStopVoiceMode,
            onCancelMessageEdit = {},
            onAttachImage = composer::attachImage,
            onAttachVideo = composer::attachVideo,
            onAttachFiles = composer::attachFiles,
            onAttachFolder = composer::attachFolder,
            onAttachFilePath = composer::attachFilePath,
            // 浮层没有助手编辑器入口，也不承载协作设置。
            onEditAssistant = {},
            onAssistantSelected = composer::selectAssistant,
            onModelSelected = composer::selectModel,
            onReasoningEffortChange = composer::updateReasoningEffort,
            onCycleGptSpeedMode = composer::cycleGptSpeedMode,
            onSubmit = onSubmit,
            onStop = onStop,
            // 入口浮层不支持「继续 / 中止暂停运行」。
            onContinue = {},
            onAbortPausedRun = {},
        )
    }
}

/**
 * 屏幕上下文操作行：「添加当前屏幕」与快捷预设排在同一行，横向滚动避免窄屏或大字号
 * 把最后一枚 chip 挤出屏幕。所有 chip 共用 [ScreenContextActionChip] 的外观。
 */
@Composable
private fun ScreenContextActionRow(
    state: EtaScreenContextUiState,
    enabled: Boolean,
    showPresets: Boolean,
    showAddChip: Boolean,
    onSelect: () -> Unit,
    onPreset: (ScreenContextPreset) -> Unit,
) {
    // 展开 / 收起是尺寸变化，走 Spatial；淡入淡出走 Effects。规格在组合期先读好。
    val fadeEnterSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()
    val fadeExitSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.fastEffectsSpec()
    val sizeSpec: FiniteAnimationSpec<IntSize> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val scrollState = rememberScrollState()
    // 两端淡出带：滚动到中间时，被视口切一半的 chip 会留下硬切边。浮窗背景是透明的，
    // 不能像抽屉那样盖一层同色渐变，这里用 DstIn 蒙版直接淡化内容本身（见下方 drawWithContent）。
    // 只在对应方向真的还有内容可滚时才出现，停在两端时不会把完整可见的 chip 罩暗。
    val edgeFadeSpec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    val startFadeWidth by animateDpAsState(
        targetValue = if (scrollState.canScrollBackward) ScreenContextChipEdgeFadeWidth else 0.dp,
        animationSpec = edgeFadeSpec,
        label = "screenContextChipFadeStart",
    )
    val endFadeWidth by animateDpAsState(
        targetValue = if (scrollState.canScrollForward) ScreenContextChipEdgeFadeWidth else 0.dp,
        animationSpec = edgeFadeSpec,
        label = "screenContextChipFadeEnd",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // DstIn 蒙版只应作用在这一行的内容上，先隔离到自己的离屏层，别擦到下层压暗或用户界面。
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val start = startFadeWidth.toPx()
                if (start > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black),
                            startX = 0f,
                            endX = start,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
                val end = endFadeWidth.toPx()
                if (end > 0f) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startX = size.width - end,
                            endX = size.width,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AnimatedVisibility(
            visible = showAddChip,
            enter = fadeIn(fadeEnterSpec) + expandHorizontally(sizeSpec),
            exit = fadeOut(fadeExitSpec) + shrinkHorizontally(sizeSpec),
        ) {
            AddScreenContextChip(
                state = state,
                enabled = enabled,
                onSelect = onSelect,
            )
        }
        AnimatedVisibility(
            visible = showPresets,
            enter = fadeIn(fadeEnterSpec) + expandHorizontally(sizeSpec),
            exit = fadeOut(fadeExitSpec) + shrinkHorizontally(sizeSpec),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScreenContextPreset.entries.forEach { preset ->
                    ScreenContextActionChip(
                        label = stringResource(preset.labelRes),
                        enabled = enabled,
                        onClick = { onPreset(preset) },
                        icon = {
                            Icon(
                                imageVector = preset.icon,
                                contentDescription = null,
                                modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
        }
    }
}

/** 「添加当前屏幕」chip：文案与图标随采集进度变化，外观与快捷预设完全一致。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddScreenContextChip(
    state: EtaScreenContextUiState,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    ScreenContextActionChip(
        label = when (state.phase) {
            EtaScreenContextPhase.CAPTURING -> stringResource(R.string.voice_screen_preparing)
            EtaScreenContextPhase.AVAILABLE -> stringResource(R.string.voice_screen_add)
            EtaScreenContextPhase.UNAVAILABLE -> stringResource(R.string.voice_screen_unavailable)
            EtaScreenContextPhase.CONSUMED -> ""
        },
        enabled = state.phase == EtaScreenContextPhase.AVAILABLE && enabled,
        onClick = onSelect,
        icon = {
            when (state.phase) {
                EtaScreenContextPhase.CAPTURING -> LoadingIndicator(
                    modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                    color = LocalContentColor.current,
                )
                EtaScreenContextPhase.AVAILABLE -> Icon(
                    imageVector = Icons.Rounded.DesktopWindows,
                    contentDescription = null,
                    modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                )
                EtaScreenContextPhase.UNAVAILABLE -> Icon(
                    imageVector = Icons.Rounded.CancelPresentation,
                    contentDescription = null,
                    modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                )
                EtaScreenContextPhase.CONSUMED -> Unit
            }
        },
    )
}

/**
 * 屏幕上下文 chip 的统一外观：官方 [SuggestionChip]，但把默认透明底换成实色容器。
 *
 * 浮窗外层是透明的，官方默认的透明底、以及禁用态 `onSurface.copy(alpha = 0.38f)`
 * 都会把下层用户界面透出来；这里容器与前景都用实色（禁用态前景再合成一次底色兜底）。
 */
@Composable
private fun ScreenContextActionChip(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val disabledContainer = scheme.surfaceContainerHigh
    val disabledContent = scheme.onSurfaceVariant.compositeOver(disabledContainer)
    SuggestionChip(
        onClick = onClick,
        enabled = enabled,
        // 实色底不需要描边，去掉官方默认的 outline 边线。
        border = null,
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = scheme.secondaryContainer,
            labelColor = scheme.onSecondaryContainer,
            iconContentColor = scheme.onSecondaryContainer,
            disabledContainerColor = disabledContainer,
            disabledLabelColor = disabledContent,
            disabledIconContentColor = disabledContent,
        ),
        // Chip 家族默认不合并子节点语义，显式合并后读屏才能把提示语当按钮名播报。
        modifier = Modifier.semantics(mergeDescendants = true) { },
        icon = icon,
        label = {
            M3Text(
                text = label,
                maxLines = 1,
                softWrap = false,
            )
        },
    )
}

@Composable
private fun ScreenContextAttachment(
    state: EtaScreenContextUiState,
    enabled: Boolean,
    showPresets: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onPreset: (ScreenContextPreset) -> Unit,
) {
    if (state.phase == EtaScreenContextPhase.CONSUMED) return
    // 选中后预览卡从操作行上方展开：高度变化走 Spatial，透明度走 Effects。
    val fadeSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val sizeSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    Column {
        AnimatedVisibility(
            visible = state.selected,
            enter = fadeIn(fadeSpec) + expandVertically(sizeSpec),
            exit = fadeOut(fadeSpec) + shrinkVertically(sizeSpec),
        ) {
            Column {
                SelectedScreenContext(
                    previewDataUrl = state.previewDataUrl,
                    enabled = enabled,
                    onRemove = onRemove,
                )
                // 有预设行跟在下面时才需要这一档间距，避免收起预设后多出一段留白。
                if (showPresets) Spacer(Modifier.height(8.dp))
            }
        }
        ScreenContextActionRow(
            state = state,
            enabled = enabled,
            showPresets = showPresets,
            // 选中屏幕后 chip 让位给上面的预览卡，操作行只剩快捷预设。
            showAddChip = !state.selected,
            onSelect = onSelect,
            onPreset = onPreset,
        )
    }
}

@Composable
private fun SelectedScreenContext(
    previewDataUrl: String?,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    val previewBitmap = previewDataUrl?.let { rememberDataUrlBitmap(it) }
    // 取色与主程序一致：全部走官方 M3 角色（surfaceContainerHigh 卡片底、onSurface /
    // onSurfaceVariant 前景、outline 禁用态），深浅色与动态取色自动适配。
    val scheme = MaterialTheme.colorScheme
    val titleColor = scheme.onSurface
    val summaryColor = scheme.onSurfaceVariant
    val removeColor = if (enabled) scheme.onSurfaceVariant else scheme.outline
    Row(
        modifier = Modifier
            .height(60.dp)
            .background(scheme.surfaceContainerHigh, RoundedCornerShape(15.dp))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        previewBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.voice_screen_preview),
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(11.dp)),
                contentScale = ContentScale.Crop,
            )
        }
        Column(
            modifier = Modifier.padding(start = 8.dp, end = 8.dp),
        ) {
            M3Text(
                text = stringResource(R.string.voice_screen_title),
                color = titleColor,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                maxLines = 1,
            )
            M3Text(
                text = stringResource(R.string.voice_screen_context_summary),
                color = summaryColor,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                maxLines = 1,
            )
        }
        IconButton(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.voice_screen_remove),
                modifier = Modifier.size(EtaDimens.StatusIconSize),
                tint = removeColor,
            )
        }
    }
}

@Composable
private fun DragHandle(modifier: Modifier = Modifier) {
    // 官方底部面板手势条：onSurfaceVariant @ 40%（M3 BottomSheetDefaults.DragHandle 同款取色），
    // 走主题调色板，深浅色与动态取色自动适配，与主程序保持一致。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

