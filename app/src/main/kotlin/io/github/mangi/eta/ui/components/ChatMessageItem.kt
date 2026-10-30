package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.markdown.LocalPreparedMarkdownBlock
import io.github.mangi.eta.ui.markdown.PreparedMarkdownBlock
import io.github.mangi.eta.ui.markdown.PreparedMarkdownSpec
import io.github.mangi.eta.ui.markdown.ChatSelectableText
import android.graphics.BitmapFactory
import android.icu.text.ListFormatter
import android.util.Base64
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.mangi.eta.ui.haptics.HapticSelectionContainer
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.MarkdownState
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.adaptive.EtaConnectedGroupRow
import io.github.mangi.eta.ui.adaptive.EtaConnectedIconAction
import io.github.mangi.eta.ui.adaptive.etaOnSurface
import io.github.mangi.eta.ui.adaptive.etaOnSurfaceVariant
import io.github.mangi.eta.ui.adaptive.etaPrimary
import io.github.mangi.eta.ui.adaptive.etaSurfaceContainerHigh
import io.github.mangi.eta.ui.adaptive.foregroundColor
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.browser.BrowserSessionSnapshot
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.overlay.toolDisplayName
import io.github.mangi.eta.agent.tool.ToolPresentation
import io.github.mangi.eta.ui.markdown.NumericCitationMarkup
import io.github.mangi.eta.ui.markdown.markdownRenderCacheKey
import io.github.mangi.eta.ui.markdown.StreamingGfmParserSession
import io.github.mangi.eta.ui.markdown.StreamingGfmSnapshot
import io.github.mangi.eta.ui.markdown.nextStreamingSnapshot
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectStatus
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isVideoAt
import io.github.mangi.eta.ui.model.durationMsAt
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.ui.model.fullImageSourceAt
import io.github.mangi.eta.ui.model.visibleFileReferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMElementTypes.TABLE
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CHECK_BOX
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.IconButtonDefaults

// ── M3 Expressive 外观适配 ────────────────────────────────────────────────
//
// 下面这组小助手统一从官方 MaterialTheme 取色与排版：一律走官方
// MaterialTheme.colorScheme 角色与 typography 档位。

/** 卡片类容器的底色：用官方 surface 角色。 */
@Composable
private fun chatSurfaceColor(): Color = MaterialTheme.colorScheme.surface

/** 嵌套容器底色：用官方 surfaceContainer 角色。 */
@Composable
private fun chatSurfaceContainerColor(): Color = MaterialTheme.colorScheme.surfaceContainer

/**
 * 会话页的背景色（壳层 Scaffold 的 containerColor）。
 *
 * 气泡底是半透明的 primary，靠它把「半透明底」合成成实际显示色；
 * 与 `AgentAppShell` 的回页背景保持同一个官方角色，渐隐才不欠色。
 */
@Composable
private fun chatPageBackgroundColor(): Color = MaterialTheme.colorScheme.surfaceContainer

/** 发丝分隔线：用官方 outlineVariant 角色。 */
@Composable
private fun chatDividerColor(alpha: Float = 1f): Color =
    MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha)

/** 卡片外框：走官方 shapes.medium（12dp）与 outlineVariant 描边。 */
@Composable
private fun chatCardShape(): Shape = MaterialTheme.shapes.medium

@Composable
private fun chatCardBorder(alpha: Float = 1f): BorderStroke =
    BorderStroke(0.5.dp, chatDividerColor(alpha))

/** 用户消息里图片附件的圆角：四角由 [userImageCorners] 按横排位置给出。 */
private fun chatMediaShape(corners: CornerRadii): Shape = RoundedCornerShape(
    corners.topStart,
    corners.topEnd,
    corners.bottomEnd,
    corners.bottomStart,
)

// 排版访问器（chatBodyStyle / chatSecondaryStyle / chatTertiaryStyle / chatQuaternaryStyle）
// 已收敛到同包的 ChatTypography.kt，本文件不再私藏。

/**
 * 发丝分隔线。
 *
 * 用官方 `HorizontalDivider`：厚度、颜色与内容内边距全部取
 * `DividerDefaults` 默认值，不再自绘 0.5dp 细线。
 */
@Composable
private fun ChatHairlineDivider(alpha: Float = 0.45f, modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier)
}

/**
 * 返回 true 前先让出一帧。用于点击展开：点击那一帧只重组标题并启动展开动画，
 * 正文的组合与文字测量放到下一帧。[deferOneFrame] 只在进入组合时读取一次。
 */
@Composable
internal fun rememberDeferredBody(deferOneFrame: Boolean): Boolean {
    var ready by remember { mutableStateOf(!deferOneFrame) }
    if (!ready) {
        LaunchedEffect(Unit) {
            withFrameNanos { }
            ready = true
        }
    }
    return ready
}

/**
 * 预览图解码不在组合里做：滚动预取和展开时同步解码会直接占用那一帧。
 * 命中缓存时立即返回，否则先返回 null（调用方占位尺寸固定），后台解码后再刷新。
 */
@Composable
internal fun rememberDataUrlBitmap(
    dataUrl: String,
    fallback: String? = null,
): ImageBitmap? {
    val context = LocalContext.current
    val cached = remember(dataUrl, fallback) {
        ChatPreviewBitmapCache.get(dataUrl) ?: fallback?.let(ChatPreviewBitmapCache::get)
    }
    val loaded = produceState(initialValue = cached, dataUrl, fallback, context) {
        if (cached != null) {
            value = cached
            return@produceState
        }
        if (dataUrl.isBlank() && fallback.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bitmap = decodeDataUrlBitmap(dataUrl)
                ?: fallback?.let(::decodeDataUrlBitmap)
                ?: loadPreviewBitmap(context, dataUrl)
                ?: fallback?.takeIf { it != dataUrl }?.let { loadPreviewBitmap(context, it) }
            bitmap?.also { ChatPreviewBitmapCache.put(dataUrl, it) }
        }
    }
    return loaded.value ?: cached
}

private fun loadPreviewBitmap(context: android.content.Context, source: String): ImageBitmap? {
    if (source.isBlank()) return null
    return ChatImageBytes.load(context, source)?.bitmap
        ?: decodeDataUrlBitmap(source)
        ?: AgentVideoCodec.fileFromSource(source)?.let { file ->
            ChatImageBytes.load(context, AgentVideoCodec.previewThumbnail(file, source).reference)?.bitmap
        }
}

private fun decodeDataUrlBitmap(dataUrl: String): ImageBitmap? {
    if (!dataUrl.startsWith("data:image/", ignoreCase = true)) return null
    val base64 = dataUrl.substringAfter("base64,", "")
    if (base64.isBlank()) return null
    return runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

/**
 * 等待首个文本片段时的反馈。圆底变形指示器，对齐 RikkaHub 的 ContainedLoadingIndicator。
 */
@Composable
fun AITypingIndicator(modifier: Modifier = Modifier) {
    if (LocalAppearanceSettings.current.morphLoadingIndicator) {
        ContainedMorphLoadingIndicator(modifier = modifier)
    }
}

/**
 * 只有正在执行的状态才持有无限动画。历史思考和工具条目保持静态，避免长会话里
 * 每个已完成节点都持续产生帧时钟与状态更新。
 */
@Composable
private fun rememberActivePulse(
    active: Boolean,
    label: String,
): () -> Float {
    // A settled, fully covered chat must not drive a frame clock behind another page.
    // The cycle starts at the static value, so resuming on swipe-back never jumps.
    val settledCover = isChatSettledCovered(LocalChatRouteCovered.current, LocalChatTransitionActive.current)
    if (!active || settledCover) return StaticPulseAlpha
    val transition = rememberInfiniteTransition(label = label)
    val alpha = transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.58f,
        animationSpec = infiniteRepeatable(
            // 循环动画需要 DurationBasedAnimationSpec，官方 motionScheme 只提供有限时长规格，
            // 因此这里沿用项目原有的 tween 循环节奏（不属于组件外观参数）。
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "${label}_alpha",
    )
    // 只把读取交给 graphicsLayer 的绘制块：每帧的透明度变化只重画图标，
    // 不再让整行思考、工具或工作过程标题跟着重组。
    return remember(alpha) { { alpha.value } }
}

private val StaticPulseAlpha: () -> Float = { 1f }

@Stable
internal class ChatMessageActions {
    var onQuestionDraftChanged: (String, String, io.github.mangi.eta.agent.question.AgentQuestionAnswer) -> Unit by mutableStateOf({ _, _, _ -> })
    var onSubmitQuestionAnswer: (String, String) -> Unit by mutableStateOf({ _, _ -> })
    var onSuggestionClick: (String) -> Unit by mutableStateOf<(String) -> Unit>({})
    var onRunTraceClick: () -> Unit by mutableStateOf<() -> Unit>({})
    var onOpenBrowser: () -> Unit by mutableStateOf<() -> Unit>({})
    var onEditMessage: (String) -> Unit by mutableStateOf<(String) -> Unit>({})
    var onDeleteMessage: (String) -> Unit by mutableStateOf<(String) -> Unit>({})
    var onRegenerateMessage: (String) -> Unit by mutableStateOf<(String) -> Unit>({})
    var onBranchMessage: (String) -> Unit by mutableStateOf<(String) -> Unit>({})
}

/**
 * 点开时这一行的下沿会不会被钉住：跟底输出时尾部停在静止线，或内容不满一屏贴底。
 * 钉住时展开从下沿长出、收起收向下沿，内容在屏幕上不动，只有标签移动；
 * 没钉住时标签不动，内容向下长。只在点击回调里调用，不参与组合。
 */
internal val LocalExpansionHoldsBottom = staticCompositionLocalOf<() -> Boolean> { { false } }

/**
 * 展开前由宿主解除底部锚定。展开/收起一律钉住标题，面板向屏幕下方生长，
 * 因此展开动作必须先释放跟底/静止线裁剪，否则面板会被裁掉不可见。
 */
internal val LocalChatAnchorRelease = staticCompositionLocalOf<() -> Unit> { {} }


@Composable
internal fun ChatMessageItem(
    message: AgentChatMessageUi,
    actions: ChatMessageActions,
    showBrowserShortcut: Boolean,
    modifier: Modifier = Modifier,
    retainedStreamingState: StreamingMarkdownState? = null,
    showCopyAction: Boolean = true,
    showMessageActions: Boolean = false,
    messageActionsEnabled: Boolean = true,
    branchEnabled: Boolean = true,
    isEditing: Boolean = false,
    justSent: Boolean = false,
    isPaused: Boolean = false,
    enableLivePreview: Boolean = true,
    speechPreface: String = "",
    userBubblePosition: UserBubblePosition = UserBubblePosition.Single,
    onThinkingToggle: ((String, Boolean) -> Unit)? = null,
    /** 本行在连续分段列表（同组步骤）里的位置，传给思考 / 工具气泡。 */
    segmentedPosition: SegmentedRowPosition = SegmentedRowPosition.Whole,
) {
    when (message) {
        is io.github.mangi.eta.ui.model.AgentQuestionMessageUi -> AgentQuestionCard(message, modifier,
            onDraftChanged = { draft -> actions.onQuestionDraftChanged(message.request.conversationId, message.request.questionId, draft) },
            onSubmit = { actions.onSubmitQuestionAnswer(message.request.conversationId, message.request.questionId) })
        is UserMessageUi -> UserMessageBubble(
            message = message,
            actionsEnabled = messageActionsEnabled,
            branchEnabled = branchEnabled,
            isEditing = isEditing,
            justSent = justSent,
            position = userBubblePosition,
            onEdit = { actions.onEditMessage(message.id) },
            onDelete = { actions.onDeleteMessage(message.id) },
            onBranch = { actions.onBranchMessage(message.id) },
            modifier = modifier,
        )
        is AgentMessageUi -> AgentMessageBlock(
            message = message,
            allowSpeech = true,
            speechPreface = speechPreface,
            retainedStreamingState = retainedStreamingState,
            showCopyAction = showCopyAction,
            showMessageActions = showMessageActions,
            messageActionsEnabled = messageActionsEnabled,
            branchEnabled = branchEnabled,
            isPaused = isPaused,
            onDelete = { actions.onDeleteMessage(message.id) },
            onRegenerate = { actions.onRegenerateMessage(message.id) },
            onBranch = { actions.onBranchMessage(message.id) },
            modifier = modifier,
        )
        is ErrorReconnectMessageUi -> ErrorReconnectDivider(message, modifier)
        is SystemNoticeMessageUi -> if (message.code == SystemNoticeCode.RuntimeFailed) {
            ErrorReconnectDivider(
                ErrorReconnectMessageUi(
                    id = message.id, runId = "", reconnectId = "legacy:${message.id}", round = 0,
                    status = ErrorReconnectStatus.Failed, reasonDetail = message.detail.orEmpty(), isReconnect = false,
                ), modifier,
            )
        } else if (message.code == SystemNoticeCode.Completed) {
            TaskCompletedDivider(modifier)
        } else AgentMessageBlock(
            message = AgentMessageUi(
                id = message.id,
                content = buildString {
                    append(
                        stringResource(
                            when (message.code) {
                                SystemNoticeCode.Stopped -> R.string.system_notice_stopped
                                SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
                                SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
                                SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
                                SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
                                SystemNoticeCode.Completed -> R.string.system_notice_completed
                            },
                        ),
                    )
                    message.detail?.takeIf(String::isNotBlank)?.let { detail ->
                        append("\n\n")
                        append(detail)
                    }
                },
                renderMarkdown = false,
            ),
            retainedStreamingState = null,
            showCopyAction = showCopyAction,
            showMessageActions = showMessageActions,
            messageActionsEnabled = messageActionsEnabled,
            branchEnabled = branchEnabled,
            isPaused = isPaused,
            onDelete = { actions.onDeleteMessage(message.id) },
            onRegenerate = { actions.onRegenerateMessage(message.id) },
            onBranch = { actions.onBranchMessage(message.id) },
            modifier = modifier,
        )
        is ThinkingMessageUi -> ThinkingRow(
            message = message,
            retainedStreamingState = retainedStreamingState,
            modifier = modifier,
            isPaused = isPaused,
            onToggle = onThinkingToggle,
            position = segmentedPosition,
        )
        is RunTraceMessageUi -> RunTraceRow(message = message, onClick = actions.onRunTraceClick, modifier = modifier)
        is ToolActivityMessageUi -> ToolActivityInline(
            message = message,
            onOpenBrowser = actions.onOpenBrowser,
            showBrowserShortcut = showBrowserShortcut,
            enableLivePreview = enableLivePreview,
            modifier = modifier,
            position = segmentedPosition,
        )
        is ToolSummaryMessageUi -> ToolSummaryInline(message = message, modifier = modifier)
        is ContextCompactedMessageUi -> ContextCompactedDivider(message = message, modifier = modifier)
        is SuggestionChipsMessageUi -> SuggestionChipsRow(message = message, onSuggestionClick = actions.onSuggestionClick, modifier = modifier)
    }
}

// ── 用户消息：轻盈美观气泡 ──────────────────────────────────────────────

/**
 * User 气泡在连续用户消息组内的位置（渲染层分组：相邻 User 气泡之间无任何内容即同组，
 * 见 [userBubblePosition]）。
 */
internal enum class UserBubblePosition {
    /** 独立单条：右上圆角、右下直角 + 尾巴。 */
    Single,

    /** 组首：右上圆角、右下接缝角。 */
    GroupFirst,

    /** 组中：右上、右下均为接缝角。 */
    GroupMid,

    /** 组末：右上接缝角、右下直角 + 尾巴。 */
    GroupLast,
}

/** 接缝侧小圆角（组内 User 气泡相接角与尾巴底角共用）。 */
private val UserBubbleInnerCorner = 2.dp

/**
 * 组尾气泡右下角尾巴的横向凸出宽度。气泡节点会比本体宽出这一段，让尾巴落在节点边界内，
 * 按压涟漪才能覆盖到它；本体文字宽度不因此改变（右侧内边距同步加上这段）。
 */
private val UserBubbleTailWidth = 5.dp

/**
 * 气泡正文的左右内边距。正文列宽预算与真实 padding 共用它，两处不能各写一份。
 */
private val UserBubbleTextPadding = 18.dp

/** 气泡普通角半径（附件行复用同档圆角）。 */
private val UserBubbleCornerRadius = 24.dp

/**
 * 附件行与气泡相接的接缝角，同时也用于相邻两张图片之间：取设置页分段条目内圆角同档（4dp）。
 * 相接的两侧同时收成这一档，大角不变。
 */
private val UserBubbleAttachmentSeamCorner = 4.dp

/** 附件行与气泡之间、以及附件行彼此之间的接缝缝宽：取设置页分段条目间距同档（2dp）。 */
private val UserBubbleAttachmentGap = 2.dp

/** 用户消息（气泡本体与附件行）的宽度上限：占聊天区宽度的比例。 */
private const val UserBubbleWidthFraction = 0.82f

/**
 * 用户气泡正文折叠阈值（行）：超过这么多行才收起，一两句话的普通消息保持原样。
 *
 * 判定用的是[正文自身的排版行数][androidx.compose.ui.text.TextMeasurer.measure]，
 * 不是字符数：同样一段字在手机和宽屏上的行数差一倍，按字符数折会在宽屏上白折。
 */
private const val UserBubbleFoldLineLimit = 6

/**
 * 折叠态正文底部的渐隐带高度：约一行半正文。
 *
 * 被 [UserBubbleFoldLineLimit] 切掉的最后一行在这儿化进气泡底色，不靠省略号——
 * 长消息的末尾本来就没有「该被省略」的内容，硬插一个「…」反而像数据被截断了。
 */
private val UserBubbleFoldFadeHeight = 40.dp

/**
 * 正文该不该收起：实测行数超过 [UserBubbleFoldLineLimit] 才折。
 *
 * 抽成纯函数是为了把边界钉在单测里（恰好 6 行不折、 7 行折），顺手避开
 * `lineCount > limit` 这种差值条件在调用处被反复手写的风险。
 */
internal fun userBubbleNeedsFold(lineCount: Int): Boolean = lineCount > UserBubbleFoldLineLimit

/** 折叠态实际下发的 maxLines；展开态不限行。 */
internal fun userBubbleBodyMaxLines(collapsed: Boolean): Int =
    if (collapsed) UserBubbleFoldLineLimit else Int.MAX_VALUE

/**
 * 发送入场：气泡从输入框方向轻上浮 10dp 并放大 0.96→1。
 * 只动绘制层（透明度 / 缩放 / 位移），不改高度——列表跟底锚定，高度一动就会推走滚动位置。
 */
private val UserBubbleEntranceRise = 10.dp
private const val UserBubbleEntranceScale = 0.96f

/** 图片附件单张边长（正方形）；一组图里每张一样大。 */
private val UserBubbleImageSide = 100.dp

/** 图片组缩到小于这个边长就不再缩，改为横向滚动。 */
private val UserBubbleImageMinSide = 64.dp

/** 四角半径（用户气泡与图片附件共用）。 */
private data class CornerRadii(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
)

/** User 气泡四角半径：外侧大圆角、接缝侧小圆角、带尾巴的角为直角。 */
private fun userBubbleCorners(position: UserBubblePosition): CornerRadii = when (position) {
    UserBubblePosition.Single -> CornerRadii(
        topStart = UserBubbleCornerRadius,
        topEnd = UserBubbleCornerRadius,
        bottomEnd = 0.dp,
        bottomStart = UserBubbleCornerRadius,
    )

    UserBubblePosition.GroupFirst -> CornerRadii(
        topStart = UserBubbleCornerRadius,
        topEnd = UserBubbleCornerRadius,
        bottomEnd = UserBubbleInnerCorner,
        bottomStart = UserBubbleCornerRadius,
    )

    UserBubblePosition.GroupMid -> CornerRadii(
        topStart = UserBubbleCornerRadius,
        topEnd = UserBubbleInnerCorner,
        bottomEnd = UserBubbleInnerCorner,
        bottomStart = UserBubbleCornerRadius,
    )

    UserBubblePosition.GroupLast -> CornerRadii(
        topStart = UserBubbleCornerRadius,
        topEnd = UserBubbleInnerCorner,
        bottomEnd = 0.dp,
        bottomStart = UserBubbleCornerRadius,
    )
}

/**
 * 用户消息里横排图片的四角：整排当成一条横向的分段条。
 *
 * - 最左一张的左侧（上、下）保持大圆角；
 * - 中间被夹住的图片四角全收成接缝小圆角，上边下边都收；
 * - 最右一张的右上角是大圆角——顶边永远算自由边，收角只收在下面接着的东西那一侧
 *   （跟文件行的 [sentFileChipShape] 同一套规则）；
 *   右下角则看下面是否接着文件行 / 气泡，接着就收成接缝，没接才是大圆角。
 */
private fun userImageCorners(
    index: Int,
    count: Int,
    connectsBelow: Boolean,
): CornerRadii {
    val isFirst = index == 0
    val isLast = index == count - 1
    val seam = UserBubbleAttachmentSeamCorner
    return CornerRadii(
        topStart = if (isFirst) UserBubbleCornerRadius else seam,
        topEnd = if (isLast) UserBubbleCornerRadius else seam,
        bottomEnd = if (isLast && !connectsBelow) UserBubbleCornerRadius else seam,
        bottomStart = if (isFirst) UserBubbleCornerRadius else seam,
    )
}

/**
 * 右下角尾巴的独立闭合路径：沿气泡右缘向上突的小竖条。右上角 1/4 圆角、右下角小圆角与接缝一致，
 * 顶边平直、左缘贴气泡右缘、底缘贴气泡底边，不叠入主体（避免半透明背景两次绘制产生色差）。
 *
 * [bodyWidth] 是气泡本体的宽度；尾巴落在 `bodyWidth..bodyWidth + UserBubbleTailWidth`。
 */
private fun Density.userBubbleTailPath(bodyWidth: Float, h: Float): Path {
    val w = bodyWidth
    val tw = UserBubbleTailWidth.toPx()
    val th = 8.dp.toPx()
    val rTop = 4.dp.toPx()
    val rBot = UserBubbleInnerCorner.toPx()
    return Path().apply {
        moveTo(w, h)
        lineTo(w + tw - rBot, h)
        quadraticTo(w + tw, h, w + tw, h - rBot)
        lineTo(w + tw, h - th + rTop)
        quadraticTo(w + tw, h - th, w + tw - rTop, h - th)
        lineTo(w, h - th)
        close()
    }
}

/**
 * 气泡的裁剪形状：圆角矩形本体 + 组尾时右下角那条尾巴（两段互不重叠的闭合轮廓）。
 *
 * 节点的宽度刻意比本体多出尾巴那一段（见 [UserBubbleTailWidth]），所以尾巴整块都在节点边界内，
 * 按压涟漪由这个形状裁切后能完整覆盖尾巴，不会留下没被高亮的一角。
 */
private class UserBubbleShape(
    private val corners: CornerRadii,
    private val withTail: Boolean,
) : Shape {
    private val rounded = RoundedCornerShape(
        corners.topStart,
        corners.topEnd,
        corners.bottomEnd,
        corners.bottomStart,
    )

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (!withTail) return rounded.createOutline(size, layoutDirection, density)
        val bodyWidth = size.width - with(density) { UserBubbleTailWidth.toPx() }
        val body = rounded.createOutline(
            size = Size(bodyWidth, size.height),
            layoutDirection = layoutDirection,
            density = density,
        ) as Outline.Rounded
        val path = Path().apply {
            addRoundRect(body.roundRect)
            addPath(with(density) { userBubbleTailPath(bodyWidth, size.height) })
        }
        return Outline.Generic(path)
    }
}

/**
 * 气泡的整体轮廓：圆角矩形，组尾时右下角再接上那条小尾巴，拼成一条连续闭合的路径。
 *
 * 编辑态的描边必须沿这条轮廓走。只描圆角矩形的话，右下角的尾巴整条没被描到（描边缺口），
 * 圆角矩形的右缘还会在尾巴内部留下一条多余的竖线——因为尾巴是从右缘往外凸的，
 * 那一段右缘在合并后的轮廓里属于内部。
 */
private fun DrawScope.userBubbleOutlinePath(
    corners: CornerRadii,
    withTail: Boolean,
): Path {
    // 节点比本体宽出尾巴那一段（见 UserBubbleTailWidth），轮廓先回到本体宽度再往外画尾巴。
    val w = if (withTail) size.width - UserBubbleTailWidth.toPx() else size.width
    val h = size.height
    val maxR = minOf(w, h) / 2f
    val ts = corners.topStart.toPx().coerceAtMost(maxR)
    val te = corners.topEnd.toPx().coerceAtMost(maxR)
    val bs = corners.bottomStart.toPx().coerceAtMost(maxR)
    val be = corners.bottomEnd.toPx().coerceAtMost(maxR)
    val tw = UserBubbleTailWidth.toPx()
    val th = 8.dp.toPx()
    val rTop = 4.dp.toPx()
    val rBot = UserBubbleInnerCorner.toPx()
    val tailTop = h - th
    return Path().apply {
        // 顺时针一圈：上缘 → 右上角 → 右缘（组尾时绕进尾巴）→ 下缘 → 左下角 → 左缘 → 左上角。
        moveTo(ts, 0f)
        lineTo(w - te, 0f)
        if (te > 0f) {
            arcTo(Rect(w - te * 2f, 0f, w, te * 2f), -90f, 90f, false)
        } else {
            lineTo(w, 0f)
        }
        if (withTail) {
            lineTo(w, tailTop)
            lineTo(w + tw - rTop, tailTop)
            if (rTop > 0f) {
                arcTo(Rect(w + tw - rTop * 2f, tailTop, w + tw, tailTop + rTop * 2f), -90f, 90f, false)
            } else {
                lineTo(w + tw, tailTop)
            }
            lineTo(w + tw, h - rBot)
            if (rBot > 0f) {
                arcTo(Rect(w + tw - rBot * 2f, h - rBot * 2f, w + tw, h), 0f, 90f, false)
            } else {
                lineTo(w + tw, h)
            }
        } else {
            lineTo(w, h - be)
            if (be > 0f) {
                arcTo(Rect(w - be * 2f, h - be * 2f, w, h), 0f, 90f, false)
            } else {
                lineTo(w, h)
            }
        }
        lineTo(bs, h)
        if (bs > 0f) {
            arcTo(Rect(0f, h - bs * 2f, bs * 2f, h), 90f, 90f, false)
        } else {
            lineTo(0f, h)
        }
        lineTo(0f, ts)
        if (ts > 0f) {
            arcTo(Rect(0f, 0f, ts * 2f, ts * 2f), 180f, 90f, false)
        } else {
            lineTo(0f, 0f)
        }
        close()
    }
}

@Composable
private fun UserMessageBubble(
    message: UserMessageUi,
    actionsEnabled: Boolean,
    branchEnabled: Boolean,
    isEditing: Boolean,
    justSent: Boolean,
    position: UserBubblePosition,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBranch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(message.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }
    val diagnosticRow = LocalStreamDiagnosticRow.current
    SideEffect {
        StreamPerformanceDiagnostics.record("render.userBubble.compose")
    }
    val visiblePrompt = remember(message.content) {
        StreamPerformanceDiagnostics.measure("render.userPrompt.parse") {
            AgentFileReferencePromptCodec.parse(message.content)
        }
    }
    val visibleFiles = remember(visiblePrompt.references, message.images, message.imageSources) {
        message.visibleFileReferences(visiblePrompt.references)
    }
    val copyText = visiblePrompt.request.ifBlank {
        visiblePrompt.conversations.joinToString(" ") { "@${it.title}" }
    }
    val view = LocalView.current

    // 发送入场：首帧停在「未落定」，随后按官方规格落定；历史消息一开始就是落定态，不播。
    val entrance = remember { MutableTransitionState(initialState = !justSent) }
    SideEffect { entrance.targetState = true }
    val entranceTransition = rememberTransition(entrance, label = "user_bubble_entrance")
    // 淡化属 Effects（无过冲），缩放与上浮属 Spatial（轻微过冲）。
    val entranceAlpha by entranceTransition.animateFloat(
        transitionSpec = { MaterialTheme.motionScheme.defaultEffectsSpec() },
        label = "user_bubble_entrance_alpha",
    ) { landed -> if (landed) 1f else 0f }
    val entranceScale by entranceTransition.animateFloat(
        transitionSpec = { MaterialTheme.motionScheme.defaultSpatialSpec() },
        label = "user_bubble_entrance_scale",
    ) { landed -> if (landed) 1f else UserBubbleEntranceScale }
    val entranceRise by entranceTransition.animateDp(
        transitionSpec = { MaterialTheme.motionScheme.defaultSpatialSpec() },
        label = "user_bubble_entrance_rise",
    ) { landed -> if (landed) 0.dp else UserBubbleEntranceRise }

    // 组尾（含单条）承载右下角尾巴，并独占可展开的操作行；避免多个空操作行常驻。
    val isGroupTail = position == UserBubblePosition.Single || position == UserBubblePosition.GroupLast
    var actionsRevealed by rememberSaveable(message.id) { mutableStateOf(false) }
    // 长正文的展开态：每条消息各自记住用户的选择，滚动回收/旋屏后仍是同一档。
    var userTextExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val bubbleBg = etaPrimary().copy(alpha = 0.18f)
    val hasBubbleBody = visiblePrompt.request.isNotBlank() ||
        visiblePrompt.conversations.isNotEmpty() ||
        message.isEdited
    // 附件行（图片 / 文件行）紧贴气泡上方时，相接的右侧角一起收小，串成整条右侧接缝；
    // 纯文字消息保持原样，附件行左侧一律保持气泡同档大圆角。
    val joinsBubble = hasBubbleBody && (message.images.isNotEmpty() || visibleFiles.isNotEmpty())
    // 图片行下面还接着东西（文件行或气泡）时，最右一张图的右下角也要收，接缝才连得上。
    val imageRowConnectsBelow = visibleFiles.isNotEmpty() || hasBubbleBody

    Column(
        modifier = modifier
            // 入场只动绘制层：高度不参与，跟底锚定的滚动位置因此不会被推走。
            // 这层必须包住外圈留白：气泡尾巴是按「节点比本体宽一段」画到内容右缘之外的，
            // 半透明合成会给该层开离屏缓冲，缓冲尺寸只到内容右缘，尾巴就在动画期间被裁掉。
            .graphicsLayer {
                alpha = entranceAlpha
                scaleX = entranceScale
                scaleY = entranceScale
                translationY = entranceRise.toPx()
                // 缩放锚在气泡右下角（尾巴那一侧），放大量正好朝发送方向展开。
                transformOrigin = TransformOrigin(1f, 1f)
            }
            .streamDiagnosticMeasure("render.userBubble.measure", diagnosticRow)
            .streamDiagnosticDraw("render.userBubble.draw", diagnosticRow)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        // 附件行：图片与文件移出气泡，镜像气泡对齐（右对齐、同 0.82 宽度上限）。
        if (message.images.isNotEmpty() || visibleFiles.isNotEmpty()) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (hasBubbleBody) UserBubbleAttachmentGap else 0.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                // Column 的 content lambda 里 BoxWithConstraintsScope 会被 DslMarker 挡住，
                // 宽度先落到普通值再往下传。
                val attachmentMaxWidth = maxWidth * UserBubbleWidthFraction
                val chatContentWidth = maxWidth
                // Column 不设总宽上限：图片行要能占满整条聊天区（两端都是整图），
                // 文件行自己再按 0.82 收，气泡本体另有 0.82 上限。
                Column(
                    horizontalAlignment = Alignment.End,
                ) {
                    if (message.images.isNotEmpty()) {
                        val imageCount = message.images.size
                        val gapTotal = UserBubbleAttachmentGap * (imageCount - 1)
                        // 视口用整条聊天区宽度（不再套 0.82 上限）；图片等比缩到刚好放得下——
                        // 每张一样大，整组两端都是完整图；图多到缩到下限还放不下，才横向滚动。
                        val fitSide = (chatContentWidth - gapTotal) / imageCount
                        val scrollsHorizontally = fitSide < UserBubbleImageMinSide
                        val imageSide = if (scrollsHorizontally) {
                            UserBubbleImageMinSide
                        } else {
                            fitSide.coerceAtMost(UserBubbleImageSide)
                        }
                        val stripWidth = (imageSide * imageCount + gapTotal)
                            .coerceAtMost(chatContentWidth)
                        val carouselState = rememberCarouselState { imageCount }
                        // 它首帧会停在右端（第一张被切掉），固定从第一张开始。
                        LaunchedEffect(imageCount) { carouselState.scrollToItem(0) }
                        HorizontalUncontainedCarousel(
                            state = carouselState,
                            itemWidth = imageSide,
                            modifier = Modifier
                                .requiredWidth(stripWidth)
                                .height(imageSide)
                                .clipToBounds(),
                            // carousel 的间距是「两侧各留一半」，视觉间隙 = 2 × itemSpacing。
                            itemSpacing = UserBubbleAttachmentGap / 2,
                            contentPadding = PaddingValues(0.dp),
                        ) { index ->
                            val dataUrl = message.images[index]
                            val bitmap = rememberDataUrlBitmap(
                                dataUrl.ifBlank { message.fullImageSourceAt(index) },
                                fallback = message.fullImageSourceAt(index),
                            )
                            val mediaShape = chatMediaShape(
                                userImageCorners(
                                    index = index,
                                    count = imageCount,
                                    connectsBelow = imageRowConnectsBelow,
                                ),
                            )
                            Box(
                                modifier = Modifier
                                    .size(imageSide)
                                    .clip(mediaShape)
                                    .background(chatSurfaceContainerColor()),
                            ) {
                                if (bitmap != null) {
                                    ChatClickableImage(
                                        source = message.fullImageSourceAt(index),
                                        bitmap = bitmap,
                                        contentDescription = stringResource(
                                            if (message.isVideoAt(index)) {
                                                R.string.chat_video_preview
                                            } else {
                                                R.string.chat_image_preview
                                            },
                                        ),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                if (message.isVideoAt(index)) {
                                    Icon(
                                        imageVector = Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .size(FloatingActionButtonDefaults.MediumIconSize),
                                    )
                                    Text(
                                        text = AgentVideoCodec.formatDuration(message.durationMsAt(index) ?: 0L),
                                        style = chatTertiaryStyle(),
                                        color = Color.White,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(8.dp),
                                    )
                                }
                            }
                        }
                    }
                    if (visibleFiles.isNotEmpty()) {
                        SentFileReferenceFlow(
                            references = visibleFiles,
                            modifier = Modifier
                                // 文件行自己收在 0.82 内（图片行不受这条限制）。
                                .widthIn(max = attachmentMaxWidth)
                                .padding(
                                    top = if (message.images.isNotEmpty()) UserBubbleAttachmentGap else 0.dp,
                                ),
                            largeCorner = UserBubbleCornerRadius,
                            seamCorner = UserBubbleAttachmentSeamCorner,
                            connectsBelow = joinsBubble,
                        )
                    }
                }
            }
        }

        // 气泡本体：半透明 primary 底、按组内位置的连续圆角、右对齐、0.82 宽度上限。
        if (hasBubbleBody) {
            // 组尾的尾巴凸在气泡本体右侧。为了让按压涟漪也覆盖尾巴，这里把气泡节点的测量宽度
            // 放宽尾巴那一段（本体宽度、文字换行、这一行在列表里的位置都不变），尾巴因此落进
            // 节点边界内；右下角的内边距同步补上这一段，文字宽度保持原样。
            val bubbleTailWidth = if (isGroupTail) UserBubbleTailWidth else 0.dp
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isGroupTail) {
                            Modifier.layout { measurable, constraints ->
                                val widened = constraints.maxWidth + bubbleTailWidth.roundToPx()
                                val placeable = measurable.measure(
                                    constraints.copy(minWidth = widened, maxWidth = widened),
                                )
                                layout(constraints.maxWidth, placeable.height) {
                                    placeable.placeRelative(0, 0)
                                }
                            }
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                val corners = if (joinsBubble) {
                    userBubbleCorners(position).copy(topEnd = UserBubbleAttachmentSeamCorner)
                } else {
                    userBubbleCorners(position)
                }
                // 裁剪形状：圆角本体 + 尾巴，涟漪与背景都按这条形状裁切。
                val bubbleShape: Shape = remember(corners, isGroupTail) {
                    UserBubbleShape(corners, withTail = isGroupTail)
                }
                val bubbleSurface = Modifier
                    .clip(bubbleShape)
                    .background(bubbleBg)
                // 描边色必须在组合期取好：`drawWithContent` 的 lambda 跑在 DrawScope 里，
                // 那不是 @Composable 作用域，直接读 `MaterialTheme.colorScheme` 会编译不过。
                val bubbleStrokeColor = MaterialTheme.colorScheme.primary
                // 正文折叠：先用正文自身的排版（同一字阶、同一可用宽度）预算「最多 7 行」，
                // 超过 6 行才收起。行数在组合期就定好、首帧直接是收起态；若改成等
                // onTextLayout 回填，长消息会先以全文高度出现一帧，把整列顶上去再缩回来。
                val bodyTextWidth =
                    ((maxWidth - bubbleTailWidth) * UserBubbleWidthFraction - UserBubbleTextPadding * 2)
                        .coerceAtLeast(0.dp)
                val textMeasurer = rememberTextMeasurer()
                val bodyStyle = MaterialTheme.typography.bodyLarge
                val pageBackground = chatPageBackgroundColor()
                val bodyTextWidthPx = with(LocalDensity.current) { bodyTextWidth.roundToPx() }
                // 量尺也要进 key：字体解析器 / 密度变化时 rememberTextMeasurer 会换实例，
                // 只按文案与宽度缓存会拿到旧排版。
                val bodyFoldable = remember(visiblePrompt.request, bodyStyle, bodyTextWidthPx, textMeasurer) {
                    visiblePrompt.request.isNotBlank() && textMeasurer.measure(
                        text = AnnotatedString(visiblePrompt.request),
                        style = bodyStyle,
                        // 多量一行就够判断「会不会超过上限」，长正文不会在这里被整段排版一遍。
                        maxLines = UserBubbleFoldLineLimit + 1,
                        constraints = Constraints(maxWidth = bodyTextWidthPx),
                    ).lineCount.let(::userBubbleNeedsFold)
                }
                val bodyCollapsed = bodyFoldable && !userTextExpanded
                // 展开 / 收起的高度动画：Text 一帧就切到目标行数，外层按动画高度裁剪，
                // 行是逐段露出来的。展开取 spatial 默认规格（与工具卡同款轻过冲），收起更快一档。
                val foldHeightSpec = if (userTextExpanded) {
                    MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntSize>()
                } else {
                    MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntSize>()
                }
                // 裁切要挂在动画外层，动画期间才能按当前可见高度截断正文；非折叠正文不参与。
                val foldMotion = if (bodyFoldable) {
                    Modifier
                        .clipToBounds()
                        .animateContentSize(animationSpec = foldHeightSpec)
                } else {
                    Modifier
                }
                // 渐隐带要落在气泡的实际底色上：气泡底是 primary 18% 的半透明色，
                // 直接叠同色会把底部压深一道；先合成到页面底色上再渐隐才不留边。
                val bubbleFill = remember(bubbleBg, pageBackground) {
                    bubbleBg.compositeOver(pageBackground)
                }
                val foldFade = if (bodyCollapsed) {
                    Modifier.drawWithContent {
                        drawContent()
                        // 大字号下正文可能不足一行高，渐隐带不能伸到节点上边界之外。
                        val fadeHeight = UserBubbleFoldFadeHeight.toPx().coerceAtMost(size.height)
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(bubbleFill.copy(alpha = 0f), bubbleFill),
                                startY = size.height - fadeHeight,
                                endY = size.height,
                            ),
                        )
                    }
                } else {
                    Modifier
                }
                val releaseAnchor = LocalChatAnchorRelease.current
                val bubbleModifier = Modifier
                    // 节点宽度上限 = 本体上限 + 尾巴；本体宽度仍受 0.82 约束。
                    .widthIn(
                        max = (maxWidth - bubbleTailWidth) * UserBubbleWidthFraction + bubbleTailWidth,
                    )
                    // 编辑态描边：整条轮廓（含右下角尾巴）一次描完，且必须挂在 clip 之外，
                    // 否则描边会被气泡形状裁掉、尾巴那一段永远缺一块。
                    .then(
                        if (isEditing) {
                            Modifier.drawWithContent {
                                drawContent()
                                drawPath(
                                    path = userBubbleOutlinePath(corners, isGroupTail),
                                    color = bubbleStrokeColor,
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                        } else {
                            Modifier
                        },
                    )
                    .then(bubbleSurface)
                    // 展开 / 收起操作行的按压区只覆盖气泡本身：接在 clip 之后，官方涟漪
                    // （LocalIndication）会一起被裁到气泡形状上，不再是整行宽的直角高亮块。
                    .then(
                        if (isGroupTail) {
                            Modifier.clickable { actionsRevealed = !actionsRevealed }
                        } else {
                            Modifier
                        },
                    )
                    .padding(
                        start = UserBubbleTextPadding,
                        end = UserBubbleTextPadding + bubbleTailWidth,
                        top = 12.dp,
                        bottom = 12.dp,
                    )
                Column(modifier = bubbleModifier) {
                    visiblePrompt.conversations.forEach { mention ->
                        Text(
                            text = "@${mention.title}" + if (mention.transcript.contains("[已截取：")) stringResource(R.string.cmi_transcript_truncated) else stringResource(R.string.cmi_transcript_full),
                            style = chatSecondaryStyle(),
                            color = etaPrimary(),
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    if (visiblePrompt.request.isNotBlank()) {
                        HapticSelectionContainer {
                            Text(
                                text = visiblePrompt.request,
                                modifier = Modifier
                                    .streamDiagnosticMeasure("render.userText.measure", diagnosticRow)
                                    .streamDiagnosticDraw("render.userText.draw", diagnosticRow)
                                    .then(foldMotion)
                                    .then(foldFade),
                                style = bodyStyle,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Start,
                                // 折叠只裁显示，不动内容：复制、编辑、发给模型仍用完整正文。
                                maxLines = userBubbleBodyMaxLines(collapsed = bodyCollapsed),
                            )
                        }
                    }
                    // 折叠入口与「已编辑」提示同排：提示贴左、展开/收起贴右，垂直居中对齐，
                    // 两者同时出现时不额外占一行。只有提示时仍按原来的说明位（窄行 + 顶部留白）。
                    if (bodyFoldable || message.isEdited) {
                        Row(
                            modifier = if (bodyFoldable) {
                                // 折叠态正文本来就顶到宽度上限，铺满才能把按钮推到气泡右缘。
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier.padding(top = 4.dp)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (message.isEdited) {
                                Text(
                                    text = stringResource(R.string.ui_edited_c36776),
                                    style = chatTertiaryStyle(),
                                    color = etaOnSurfaceVariant(),
                                )
                            }
                            if (bodyFoldable) {
                                Spacer(Modifier.weight(1f))
                                // 展开 / 收起：官方 TextButton 自带最小触控高度、涟漪与主色标签。
                                // 展开会往下长出正文，先解除跟底锚定，尾部才不会被静止线裁掉。
                                TextButton(
                                    onClick = {
                                        TouchHaptics.click(view)
                                        if (!userTextExpanded) releaseAnchor()
                                        userTextExpanded = !userTextExpanded
                                    },
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (userTextExpanded) {
                                                R.string.cmi_fold_collapse
                                            } else {
                                                R.string.cmi_fold_expand
                                            },
                                        ),
                                        style = chatSecondaryStyle(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // 操作行：仅组尾承载，点气泡先展开，再点气泡收起。
        if (isGroupTail) {
            AnimatedVisibility(
                visible = actionsRevealed,
                enter = expandVertically(animationSpec = MaterialTheme.motionScheme.fastSpatialSpec()) +
                    fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                exit = shrinkVertically(animationSpec = MaterialTheme.motionScheme.fastSpatialSpec()) +
                    fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
            ) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Expressive：与 AI 消息操作行同款的官方连体按钮组（KernelSU 模式），
                    // 尺寸统一 ToggleButtonSize.Small，配色 secondaryContainer/onSecondaryContainer。
                    val copyTooltip = stringResource(R.string.ui_copy_4edd1d)
                    val editTooltip = stringResource(R.string.ui_edit_a7f814)
                    val branchTooltip = stringResource(R.string.ui_branch_conversation)
                    val deleteTooltip = stringResource(R.string.ui_delete_3755f5)
                    val groupActions: List<@Composable (Int, Int) -> Unit> = buildList {
                        add { index, count ->
                            FooterTooltipBox(copyTooltip, true) {
                                EtaConnectedIconAction(
                                    onClick = {
                                        TouchHaptics.click(view)
                                        @Suppress("DEPRECATION")
                                        clipboardManager.setText(AnnotatedString(copyText))
                                        copied = true
                                    },
                                    enabled = true,
                                    index = index,
                                    count = count,
                                ) {
                                    Icon(
                                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                        contentDescription = stringResource(
                                            if (copied) R.string.copy_copied else R.string.ui_copy_4edd1d,
                                        ),
                                        modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                                    )
                                }
                            }
                        }
                        add { index, count ->
                            FooterTooltipBox(editTooltip, actionsEnabled) {
                                EtaConnectedIconAction(
                                    onClick = {
                                        TouchHaptics.click(view)
                                        onEdit()
                                    },
                                    enabled = actionsEnabled,
                                    index = index,
                                    count = count,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = editTooltip,
                                        modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                                    )
                                }
                            }
                        }
                        add { index, count ->
                            FooterTooltipBox(branchTooltip, branchEnabled) {
                                EtaConnectedIconAction(
                                    onClick = {
                                        TouchHaptics.click(view)
                                        onBranch()
                                    },
                                    enabled = branchEnabled,
                                    index = index,
                                    count = count,
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.CallSplit,
                                        contentDescription = branchTooltip,
                                        modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                                    )
                                }
                            }
                        }
                        add { index, count ->
                            FooterTooltipBox(deleteTooltip, actionsEnabled) {
                                EtaConnectedIconAction(
                                    onClick = {
                                        TouchHaptics.click(view)
                                        onDelete()
                                    },
                                    enabled = actionsEnabled,
                                    index = index,
                                    count = count,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Delete,
                                        contentDescription = deleteTooltip,
                                        modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                                    )
                                }
                            }
                        }
                    }
                    EtaConnectedGroupRow {
                        groupActions.forEachIndexed { index, action -> action(index, groupActions.size) }
                    }
                }
            }
        }
    }
}

// ── Agent 结果 ───────────────────────────────────────────────────────

/** A pause belongs to the live run. A finished answer whose exact content has already
 * revealed must not reparse its terminal document as streaming while another row pauses. */
internal fun answerFollowsRunPause(
    runPaused: Boolean,
    messageStreaming: Boolean,
    revealedContent: String?,
    content: String,
): Boolean = runPaused && (messageStreaming || revealedContent != content)

@Composable
private fun AgentMessageBlock(
    message: AgentMessageUi,
    allowSpeech: Boolean = false,
    speechPreface: String = "",
    retainedStreamingState: StreamingMarkdownState?,
    showCopyAction: Boolean,
    showMessageActions: Boolean,
    messageActionsEnabled: Boolean,
    branchEnabled: Boolean,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit = {},
    modifier: Modifier = Modifier,
    isPaused: Boolean = false,
) {
    val keepStreamingMarkdown = message.isStreaming || retainedStreamingState != null
    val displayContent = remember(message.content) {
        StreamPerformanceDiagnostics.measure("markdown.citation.strip", message.content.length.toLong()) {
            NumericCitationMarkup.strip(message.content)
        }
    }
    // Completion belongs to an exact source revision. Late text must not inherit
    // the previous revision's true flag while its parser/reveal effect catches up.
    var streamingRevealComplete by remember(message.id, message.content) {
        mutableStateOf(!keepStreamingMarkdown ||
            (!message.isStreaming && retainedStreamingState?.revealedContent == message.content))
    }
    LaunchedEffect(message.isStreaming) {
        if (message.isStreaming) streamingRevealComplete = false
    }
    // 渲染会话由列表层按 message.id 持有，item 滚出视口被销毁后滑回时复用同一
    // 会话；没有外部持有者时（如嵌套条目）退回组合内 remember，行为与之前一致。
    val streamingState = if (keepStreamingMarkdown) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    LaunchedEffect(retainedStreamingState, streamingRevealComplete, message.content, message.isStreaming) {
        retainedStreamingState?.revealedContent = message.content.takeIf {
            streamingRevealComplete && !message.isStreaming
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (message.content.isBlank() && message.isStreaming) {
            AITypingIndicator(
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            HapticSelectionContainer {
                when {
                    // 流式会话一旦建立就不要切到 StableMarkdown：暂停继续和生成结束
                    // 都会让 isStreaming 翻转，整棵 Markdown 重挂会闪一帧。
                    streamingState != null -> {
                        StreamingMarkdown(
                            state = streamingState,
                            content = displayContent,
                            isStreaming = message.isStreaming,
                            animateInitialContent = !message.isStreaming &&
                                displayContent.isNotEmpty() &&
                                streamingState.documentState.snapshot == null &&
                                streamingState.revealedContent == null,
                            isPaused = answerFollowsRunPause(isPaused, message.isStreaming,
                                retainedStreamingState?.revealedContent, message.content),
                            onRevealCompleteChange = { streamingRevealComplete = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    message.renderMarkdown -> {
                        StableMarkdown(
                            content = displayContent,
                            modifier = completedContentDrawLayer(Modifier.fillMaxWidth(), true),
                        )
                    }
                    message.content.isNotBlank() -> {
                        Text(
                            text = message.content,
                            style = chatBodyStyle(),
                            color = etaOnSurface(),
                            modifier = completedContentDrawLayer(Modifier, true),
                        )
                    }
                }
            }
        }

        if (
            showCopyAction &&
            !message.isStreaming &&
            message.content.isNotBlank() &&
            (!keepStreamingMarkdown || streamingRevealComplete)
        ) {
            AgentMessageActionRow(
                messageId = message.id,
                content = message.content,
                allowSpeech = allowSpeech,
                speechPreface = speechPreface,
                generatedAtMillis = message.generatedAtMillis,
                showMessageActions = showMessageActions,
                messageActionsEnabled = messageActionsEnabled,
                branchEnabled = branchEnabled,
                onDelete = onDelete,
                onRegenerate = onRegenerate,
                onBranch = onBranch,
            )
        }
    }
}

/** 当前点开的推理把分帧组合进度和 loading 回退记进同一个点击窗口。 */
private val LocalToggleProbe = staticCompositionLocalOf<ToggleProbeRef?> { null }

/** Both ordinary answers and thinking use upstream prepared-document rendering. */
@Composable
private fun StableMarkdown(
    content: String, modifier: Modifier = Modifier,
    tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
    progressive: Boolean = false,
) {
    val bodyTraceMount = remember { nextChatBodyTraceMount() }
    SideEffect { traceChatBodyRun("md", bodyTraceMount) }
    io.github.mangi.eta.ui.markdown.DocumentStaticMarkdown(
        content, modifier,
        if (tone == ChatMarkdownTone.Answer) io.github.mangi.eta.ui.markdown.MarkdownTone.Answer
        else io.github.mangi.eta.ui.markdown.MarkdownTone.Thinking,
    )
}

@Composable
private fun StreamingMarkdown(
    state: StreamingMarkdownState, content: String, isStreaming: Boolean,
    animateInitialContent: Boolean = false, isPaused: Boolean = false,
    onRevealCompleteChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
) {
    io.github.mangi.eta.ui.markdown.DocumentStreamingMarkdown(
        state = state.documentState, content = content, isStreaming = isStreaming,
        animateInitialContent = animateInitialContent, isPaused = isPaused,
        onRevealCompleteChange = onRevealCompleteChange, modifier = modifier,
        tone = if (tone == ChatMarkdownTone.Answer) io.github.mangi.eta.ui.markdown.MarkdownTone.Answer
            else io.github.mangi.eta.ui.markdown.MarkdownTone.Thinking,
    )
}

// Legacy AST renderer is retained only for isolated compatibility tests during migration.
// It is not called by AgentMessageBlock, ThinkingRow or either production wrapper above.
@Composable
private fun LegacyStableMarkdown(
    content: String,
    modifier: Modifier = Modifier,
    tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
    markdownState: MarkdownState = rememberCompletedMarkdownState(content),
    progressive: Boolean = false,
) {
    val bodyTraceMount = remember { nextChatBodyTraceMount() }
    SideEffect { traceChatBodyRun("md", bodyTraceMount) }
    val components = remember { chatMarkdownComponents() }
    CompletedMarkdownStateHost(markdownState) {
        Markdown(
            markdownState = markdownState,
            colors = chatMarkdownColors(tone),
            typography = chatMarkdownTypography(tone),
            padding = chatMarkdownPadding(),
            dimens = chatMarkdownDimens(),
            components = components,
            modifier = modifier,
            loading = {
                SideEffect { traceChatBodyRun("md.phase.loading", bodyTraceMount) }
                LocalToggleProbe.current?.let { ref ->
                    SideEffect { StreamPerformanceDiagnostics.probeEvent(ref.token, "markdown", "state=loading chars=${content.length}") }
                }
                // 保留与最终正文接近的高度，避免历史消息异步解析完成后越界绘制。
                Text(
                    text = content,
                    style = chatMarkdownBodyStyle(tone),
                    color = chatMarkdownTextColor(tone),
                    modifier = it,
                )
            },
            error = {
                SideEffect { traceChatBodyRun("md.phase.error", bodyTraceMount) }
                Text(
                    text = content,
                    style = chatMarkdownBodyStyle(tone),
                    color = chatMarkdownTextColor(tone),
                    modifier = it,
                )
            },
            success = { state, successComponents, successModifier ->
                SideEffect { traceChatBodyRun("md.phase.success", bodyTraceMount) }
                CacheCompletedMarkdownSuccess(content, markdownState, state)
                ChatMarkdownDocument(
                    root = state.node,
                    content = state.content,
                    components = successComponents,
                    modifier = successModifier,
                    progressive = progressive,
                )
            },
        )
    }
}

/**
 * 流式渲染会话，按 message.id 提升到 LazyColumn 外层持有。
 *
 * 流式 item 滚出视口后组合会被销毁，裸 remember 会让解析基线、打字机进度和最新
 * 快照全部丢失；滑回时整段已生成内容会重新全量解析，并从头重放显现动画。会话
 * 与组合解耦后，item 重建只是重新挂接效果，渲染进度原样保留。
 */
@Stable
internal class StreamingMarkdownState {
    val documentState = io.github.mangi.eta.ui.markdown.DocumentStreamingState()
    var revealedContent by mutableStateOf<String?>(null)
    val parserSession = StreamingGfmParserSession()
    val revealCoordinator = SmoothTextRevealCoordinator().apply { pauseAnimationsAndCatchUp() }
    val restoreState = StreamingMarkdownRestoreState()
    val parseTargets = Channel<StreamingMarkdownTarget>(Channel.CONFLATED)
    val acceptedContent = arrayOf("")
    var snapshot by mutableStateOf<StreamingGfmSnapshot?>(null)
    var completedRevealSource by mutableStateOf<String?>(null)
    val compositionProgress = ProgressiveMarkdownCompositionState()
}

@Composable
private fun LegacyStreamingMarkdown(
    state: StreamingMarkdownState,
    content: String,
    isStreaming: Boolean,
    animateInitialContent: Boolean = false,
    isPaused: Boolean = false,
    onRevealCompleteChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tone: ChatMarkdownTone = ChatMarkdownTone.Answer,
) {
    val parserSession = state.parserSession
    val typography = chatMarkdownTypography(tone)
    val inlineCode = typography.inlineCode.copy(
        background = etaSurfaceContainerHigh().copy(alpha = 0.6f),
    ).toSpanStyle()
    val uriHandler = LocalUriHandler.current
    val preparedListener = remember(uriHandler) {
        LinkInteractionListener { link ->
            (link as? LinkAnnotation.Url)?.url?.let { url ->
                try { uriHandler.openUri(url) } catch (_: Throwable) { /* Same non-fatal link boundary. */ }
            }
        }
    }
    val preparedAnnotator = remember { markdownAnnotator() }
    val renderSpec = remember(typography, inlineCode, preparedListener, preparedAnnotator) {
        PreparedMarkdownSpec(typography, inlineCode, typography.textLink, preparedListener, preparedAnnotator)
    }
    val revealCoordinator = state.revealCoordinator
    val compositionProgress = state.compositionProgress
    val components = remember(revealCoordinator) {
        chatMarkdownComponents(
            revealCoordinator = revealCoordinator,
            suppressEmptyListMarkers = true,
        )
    }
    val parseTargets = state.parseTargets
    val acceptedContent = state.acceptedContent
    val currentRevealCompleteCallback by rememberUpdatedState(onRevealCompleteChange)
    val snapshot = state.snapshot
    val currentContent by rememberUpdatedState(content)
    // Pausing is not a terminal parser target. Lifecycle restore and user pause
    // share a single animation gate; parent effects must not resume before layout.
    val parseAsStreaming = isStreaming || isPaused
    val currentIsStreaming by rememberUpdatedState(parseAsStreaming)
    val currentPaused by rememberUpdatedState(isPaused)
    val restoreGeneration = state.restoreState.generation
    val view = LocalView.current
    val routeCovered = LocalChatRouteCovered.current
    val transitionActive = LocalChatTransitionActive.current
    val routeCoveredNow = rememberUpdatedState(routeCovered)
    // A lazy-row disposal is not an activity pause. Keep only unfinished output
    // mounted so its parser, reveal clock and frame-coupled haptics survive a fling.
    // The pin keeps composition, not the viewport position or measured height.
    KeepActiveStreamingRow(
        shouldKeepStreamingRow(
            content = content,
            isStreaming = isStreaming,
            isPaused = isPaused,
            completedRevealSource = state.completedRevealSource,
        ),
    )

    LifecycleResumeEffect(state) {
        val animateExisting = animateInitialContent && !currentPaused && currentContent.isNotEmpty()
        if (state.restoreState.begin(
                currentContent,
                live = currentIsStreaming && !currentPaused,
                animateExisting = animateExisting,
            )
        ) {
            // A new message may already contain a whole network batch when first composed,
            // including a batch whose block has already ended. Do not classify that first
            // batch as restored history and reveal it all at once.
            revealCoordinator.resumeAnimationsWithoutCatchingUp()
        } else {
            revealCoordinator.restoreHistoryThrough(currentContent.length)
        }
        onPauseOrDispose {
            // 回调不在组合里，读进入回调前记住的最新值。半遮住时导航把聊天降到
            // STARTED，页面还在组合里，继续逐字打。真正离开前台才追平，避免回来补播。
            if (routeCoveredNow.value) {
                state.restoreState.holdCovered()
            } else {
                state.restoreState.pause()
                revealCoordinator.pauseAnimationsAndCatchUp()
            }
        }
    }

    // 停稳且完全盖住才停住显现，而且不追平。横滑、松手回弹期间露出的边继续逐字打。
    val settledCover = routeCovered && !transitionActive
    val animationsAllowed = !settledCover && (
        state.restoreState.animationsAllowed(isPaused) || (routeCovered && transitionActive)
    )
    // Content is deliberately not a key. A streaming delta must not re-run the gate decision:
    // while the restore baseline is still pending, animationsAllowed is false, and every delta
    // would catch the reveal up to the newest text. That drains the pending records, the frame
    // clock parks on its wakeup channel, and the typewriter plus its haptics stop for the rest
    // of the message. Only a real gate change may move the coordinator.
    LaunchedEffect(revealCoordinator, animationsAllowed, isPaused, settledCover) {
        if (animationsAllowed) revealCoordinator.resumeAnimationsWithoutCatchingUp()
        else if (settledCover) revealCoordinator.holdAnimations()
        else if (!isPaused) revealCoordinator.pauseAnimationsAndCatchUp()
    }

    // An explicit user pause does keep following new text, because nothing will animate it later.
    // The restore baseline case must not take this path: there the reveal has to stay pending.
    LaunchedEffect(revealCoordinator, isPaused, content) {
        if (isPaused) revealCoordinator.restoreHistoryThrough(content.length)
    }

    LaunchedEffect(revealCoordinator, view) {
        // Drive feedback in the same frame as visible text, including the final drain.
        revealCoordinator.setOnRevealAdvanced {
            io.github.mangi.eta.ui.haptics.StreamingHaptics.onVisibleAdvance(view)
        }
        try {
            revealCoordinator.runFrameClock()
        } finally {
            revealCoordinator.setOnRevealAdvanced(null)
        }
    }

    LaunchedEffect(content, parseAsStreaming, renderSpec) {
        val previousContent = acceptedContent[0]
        if (!content.startsWith(previousContent)) {
            // 会话恢复或上游纠正内容时，让解析会话重新建立文档基线。
            acceptedContent[0] = ""
        }
        acceptedContent[0] = content
        StreamPerformanceDiagnostics.record("markdown.target", value = content.length.toLong())
        parseTargets.trySend(
            StreamingMarkdownTarget(
                content = content,
                isStreaming = parseAsStreaming,
                renderSpec = renderSpec,
            )
        )
        if (parseAsStreaming) {
            currentRevealCompleteCallback(false)
        }
    }

    LaunchedEffect(parserSession, parseTargets) {
        var target = parseTargets.receive()
        while (true) {
            while (true) {
                val newerTarget = parseTargets.tryReceive().getOrNull() ?: break
                StreamPerformanceDiagnostics.record("markdown.coalesced")
                target = newerTarget
            }

            val parsed = withContext(Dispatchers.Default) {
                // Restore only this synchronous parse slice, never across suspension.
                StreamPerformanceDiagnostics.withAttribution(target.diagnosticAttribution) {
                    StreamPerformanceDiagnostics.record("markdown.queueWait", System.nanoTime() - target.queuedAtNs)
                    StreamPerformanceDiagnostics.measure("markdown.parse", target.content.length.toLong()) {
                        parserSession.parse(
                            source = target.content,
                            isComplete = !target.isStreaming,
                            renderSpec = target.renderSpec,
                        )
                    }
                }
            }

            val newerTarget = parseTargets.tryReceive().getOrNull()
            if (newerTarget != null) {
                StreamPerformanceDiagnostics.record("markdown.superseded")
                target = newerTarget
                continue
            }

            val publishTarget = target
            StreamPerformanceDiagnostics.withAttribution(publishTarget.diagnosticAttribution) {
                StreamPerformanceDiagnostics.measure("markdown.publishBlock", publishTarget.content.length.toLong()) {
                    nextStreamingSnapshot(state.snapshot, parsed)?.let { published ->
                        StreamPerformanceDiagnostics.record("markdown.targetToPublish", System.nanoTime() - publishTarget.queuedAtNs)
                        StreamPerformanceDiagnostics.record("markdown.publish", value = published.originalSource.length.toLong())
                        state.compositionProgress.acceptSource(published.originalSource)
                        state.snapshot = published
                    }
                }
            }
            if (target.isStreaming) {
                delay(STREAMING_PARSE_PUBLISH_INTERVAL_MS)
            }
            target = parseTargets.receive()
        }
    }

    LaunchedEffect(content, parseAsStreaming, snapshot?.originalSource, snapshot?.isComplete, revealCoordinator, compositionProgress.publication) {
        val currentSnapshot = snapshot
        if (!isStreamingMarkdownTargetComplete(
                content = content,
                isStreaming = parseAsStreaming,
                snapshotContent = currentSnapshot?.originalSource,
                snapshotComplete = currentSnapshot?.isComplete == true,
            )
        ) {
            currentRevealCompleteCallback(false)
            return@LaunchedEffect
        }

        // Parsing alone is not composition. A terminal long document may still
        // be admitted over several frames; no pin release before that frontier.
        snapshotFlow { compositionProgress.composedSource }
            .first { it == currentSnapshot?.originalSource }
        // Drain can be momentarily true between two successive block layouts.
        // Require another layout frame after each drain before declaring completion.
        do {
            withFrameNanos { }
            if (!revealCoordinator.drained.value) {
                revealCoordinator.drained.filter { it }.first()
            }
            withFrameNanos { }
        } while (!revealCoordinator.drained.value)
        if (isStreamingMarkdownTargetComplete(
                content = currentContent,
                isStreaming = currentIsStreaming,
                snapshotContent = currentSnapshot?.originalSource,
                snapshotComplete = currentSnapshot?.isComplete == true,
            )
        ) {
            // Release the lazy pin only after this exact terminal revision has
            // parsed, laid out for a frame and drained. A transient drained=true
            // before onTextLayout must never release a still-pending row.
            state.completedRevealSource = currentContent
            currentRevealCompleteCallback(true)
        }
    }

    snapshot?.let { parsed ->
        val imageTransformer = rememberStreamingMarkdownImageTransformer(parsed)
        Markdown(
            state = parsed.state,
            annotator = preparedAnnotator,
            colors = chatMarkdownColors(tone),
            typography = chatMarkdownTypography(tone),
            padding = chatMarkdownPadding(),
            dimens = chatMarkdownDimens(),
            components = components,
            imageTransformer = imageTransformer,
            animations = markdownAnimations(animateTextSize = { this }),
            modifier = modifier.onGloballyPositioned {
                StreamPerformanceDiagnostics.record("markdown.layout", value = it.size.height.toLong())
                // 恢复基线对应的 AST 真正排版后才开放增量动画，解析耗时不受帧数限制。
                if (state.restoreState.completeLayout(
                        generation = restoreGeneration,
                        renderedContent = parsed.originalSource,
                        currentContent = currentContent,
                    )
                ) {
                    if (state.restoreState.animationsAllowed(currentPaused)) {
                        revealCoordinator.resumeAnimationsAfterCatchUp()
                    }
                }
            },
            success = { state, successComponents, successModifier ->
                StreamingGfmSuccess(
                    state = state,
                    preparedBlocks = parsed.preparedBlocks.takeIf { parsed.renderSpec == renderSpec },
                    components = successComponents,
                    revealCoordinator = revealCoordinator,
                    compositionProgress = compositionProgress,
                    compositionSource = parsed.originalSource,
                    compositionPublication = compositionProgress.publication,
                    modifier = successModifier,
                )
            },
        )
    }
}

/**
 * 顶层节点以源码位置和语法类型作为稳定身份。完整重解析只替换真正发生类型变化的
 * 当前块，前面已经稳定的段落、表格和代码块不会因新 chunk 到达而重新挂载。
 */
@Composable
private fun StreamingGfmSuccess(
    state: State.Success,
    preparedBlocks: List<PreparedMarkdownBlock>? = null,
    components: MarkdownComponents,
    revealCoordinator: SmoothTextRevealCoordinator,
    compositionProgress: ProgressiveMarkdownCompositionState,
    compositionSource: String,
    compositionPublication: Int,
    modifier: Modifier = Modifier,
) {
    val activeRevealBlocks = remember(state.node) {
        state.revealBlockKeys()
    }
    SideEffect {
        revealCoordinator.retainBlocks(activeRevealBlocks)
    }

    ChatMarkdownDocument(
        root = state.node,
        content = state.content,
        preparedBlocks = preparedBlocks,
        components = components,
        revealCoordinator = revealCoordinator,
        compositionProgress = compositionProgress,
        compositionSource = compositionSource,
        compositionPublication = compositionPublication,
        modifier = modifier,
    )
}

/**
 * 空行只负责切分 Markdown 块，不直接占据布局高度；可见块之间按语义分配留白，
 * 避免统一 block padding 让标题、正文、列表和表格失去层级。
 */
@Composable
private fun ChatMarkdownDocument(
    root: ASTNode,
    content: String,
    components: MarkdownComponents,
    modifier: Modifier = Modifier,
    revealCoordinator: SmoothTextRevealCoordinator? = null,
    progressive: Boolean = false,
    preparedBlocks: List<PreparedMarkdownBlock>? = null,
    compositionProgress: ProgressiveMarkdownCompositionState? = null,
    compositionSource: String? = null,
    compositionPublication: Int = 0,
) {
    val bodyTraceMount = remember { nextChatBodyTraceMount() }
    val diagnosticRow = LocalStreamDiagnosticRow.current
    SideEffect { traceChatBodyRun("md.doc", bodyTraceMount) }
    val blocks = remember(root, preparedBlocks) {
        preparedBlocks?.map { it.node } ?: topLevelMarkdownBlocks(root)
    }
    // 用户点击展开长文档时，把整篇的组合与文字测量分摊到连续几帧，避免首帧一次性
    // 构建全部 AnnotatedString 并测量全文。只在进入组合时决定一次，历史滚入可视区
    // 的已展开内容仍一次到位，不会在滚动途中改变高度。
    // Streaming also spreads a long first composition. The reveal clock still
    // shows only the current tail, so later blocks stay unmeasured until then.
    val progressiveAtEntry = remember { progressive || revealCoordinator != null }
    val composedBlockLimit = if (progressiveAtEntry) {
        val streamingReveal = revealCoordinator != null
        val firstBudget = if (streamingReveal) STREAMING_PROGRESSIVE_FIRST_FRAME_CHARS else PROGRESSIVE_FIRST_FRAME_CHARS
        val frameBudget = if (streamingReveal) STREAMING_PROGRESSIVE_FRAME_CHARS else PROGRESSIVE_FRAME_CHARS
        val lengths = remember(blocks) { blocks.map { (it.endOffset - it.startOffset).coerceAtLeast(0) } }
        // 不用 blocks 当 key。流式增量每次都是新列表，重新记住会把已显示的块清掉，看起来整页闪。
        // 上限只增不减；新块到了，由下面的长度变化继续往前排。
        // The owner survives lazy-row disposal. Restoring already composed blocks
        // prevents a long history row shrinking to the first 480 chars on reentry.
        // Only the block count is retained; width/font changes still remeasure.
        var limit by remember(compositionProgress, compositionProgress?.generation) {
            mutableIntStateOf(
                compositionProgress?.initialLimit(lengths, firstBudget, streamingReveal)
                    ?: nextProgressiveBlockLimit(lengths, 0, firstBudget, mustAdvance = streamingReveal),
            )
        }
        SideEffect { compositionProgress?.recordLimit(limit, lengths.size, compositionSource, compositionPublication) }
        val probeRef = LocalToggleProbe.current
        LaunchedEffect(lengths.size, compositionProgress?.generation) {
            probeRef?.let { ref ->
                StreamPerformanceDiagnostics.probeEvent(
                    ref.token, "progressive", "blocks=$limit/${lengths.size} chars=${lengths.sum()}",
                )
            }
            while (limit < lengths.size) {
                withFrameNanos { }
                limit = nextProgressiveBlockLimit(lengths, limit, frameBudget)
                probeRef?.let { ref ->
                    StreamPerformanceDiagnostics.probeEvent(ref.token, "progressive", "blocks=$limit/${lengths.size}")
                }
            }
        }
        limit
    } else {
        Int.MAX_VALUE
    }
    val startedRevealKeys = rememberStartedRevealKeys(revealCoordinator)
    val nextRevealKey = remember(blocks, startedRevealKeys) {
        blocks.mapNotNull { it.firstRevealBlockKey() }
            .firstOrNull { it !in startedRevealKeys }
    }
    val lastVisibleStartOffset = remember(blocks, startedRevealKeys, nextRevealKey, revealCoordinator) {
        blocks.lastOrNull { node ->
            streamingMarkdownBlockVisible(
                coordinatorActive = revealCoordinator != null,
                firstRevealKey = node.firstRevealBlockKey(),
                startedRevealKeys = startedRevealKeys,
                nextRevealKey = nextRevealKey,
            )
        }?.startOffset
    }
    val density = LocalDensity.current
    var previousVisibleType: IElementType? = null
    // Inclusive document-container range; never add it to the inner block spans.
    Column(
        modifier
            .streamDiagnosticMeasure("render.measure", diagnosticRow)
            .streamDiagnosticDraw("render.draw", diagnosticRow),
    ) {
        blocks.forEachIndexed { index, node ->
            if (index >= composedBlockLimit) return@forEachIndexed
            val revealKey = node.firstRevealBlockKey()
            val visible = streamingMarkdownBlockVisible(
                coordinatorActive = revealCoordinator != null,
                firstRevealKey = revealKey,
                startedRevealKeys = startedRevealKeys,
                nextRevealKey = nextRevealKey,
            )
            if (!visible) return@forEachIndexed
            val gap = with(density) {
                markdownBlockSpacing(previousVisibleType, node.type).toDp()
            }
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            previousVisibleType = node.type
            key(node.startOffset, node.type.name) {
                val preparedBlock = preparedBlocks?.getOrNull(index)
                val content = preparedBlock?.source ?: content
                val freeze = revealCoordinator != null &&
                    shouldFreezeStreamingMarkdownBlock(node.startOffset, lastVisibleStartOffset) &&
                    (preparedBlock == null || preparedBlock.cacheKey.referenceSource == null)
                // Freeze used to remount MarkdownElement and complete in-block reveal
                // via onDetach. Keep that catch-up without discarding the renderer.
                LaunchedEffect(freeze) {
                    if (freeze) {
                        revealCoordinator?.completeAttachedRecordsIn(node.startOffset, node.endOffset)
                    }
                }
                // preparedBlock.source is the whole document. Key the capture on this
                // block's own text so a longer tail does not recapture finished blocks.
                // Spec stays in the key so a theme change still refreshes them.
                val blockSource = preparedBlock?.cacheKey?.source
                val pinned = if (freeze && blockSource != null) {
                    remember(blockSource, preparedBlock.spec) { Triple(node, content, preparedBlock) }
                } else {
                    null
                }
                val renderNode = pinned?.first
                    ?: if (preparedBlock != null) node else rememberFrozenMarkdownInput(node, freeze)
                val renderContent = pinned?.second
                    ?: if (preparedBlock != null) content else rememberFrozenMarkdownInput(content, freeze)
                FrozenMarkdownElement(
                    node = renderNode,
                    components = components,
                    content = renderContent,
                    freeze = freeze,
                    preparedBlock = pinned?.third ?: preparedBlock,
                    diagnosticAttribution = remember(diagnosticRow, index, node.startOffset, node.type.name) {
                        StreamPerformanceDiagnostics.blockAttribution(
                            diagnosticRow, index, node.type.name, node.endOffset - node.startOffset)
                    },
                )
            }
        }
    }
}


@Composable
private fun FrozenMarkdownElement(
    node: ASTNode,
    components: MarkdownComponents,
    content: String,
    freeze: Boolean,
    diagnosticAttribution: StreamDiagnosticAttribution? = null,
    preparedBlock: PreparedMarkdownBlock? = null,
) {
    // A plain graphics layer still redraws when its parent records. Completed
    // blocks keep one layer and switch to an offscreen texture once they stop
    // changing, so the tail does not re-issue their text commands. The layer
    // stays mounted: inserting it only after freeze would remount the block.
    // Content above the retained-texture limit stays a plain layer; clipping
    // it would change the visible text. The tail is never cached.
    var retainedHeightPx by remember { mutableIntStateOf(0) }
    val retainTexture = freeze && retainedHeightPx in 1..MAX_RETAINED_LAYER_HEIGHT_PX
    Box(Modifier.graphicsLayer(
            compositingStrategy = if (retainTexture) {
                CompositingStrategy.Offscreen
            } else {
                CompositingStrategy.Auto
            },
        )
        .onSizeChanged { retainedHeightPx = it.height }
        .streamDiagnosticMeasure(if (freeze) "markdown.stable.measure" else "markdown.tail.measure", diagnosticAttribution)
        .drawWithContent {
            StreamPerformanceDiagnostics.withRenderAttribution(diagnosticAttribution) {
                StreamPerformanceDiagnostics.measure("markdown.blockDraw") {
                    if (StreamPerformanceDiagnostics.enabled) {
                        StreamPerformanceDiagnostics.measure(
                            if (freeze) "markdown.stable.draw" else "markdown.tail.draw",
                        ) { drawContent() }
                    } else {
                        drawContent()
                    }
                }
            }
        }) {
        // Keep one MarkdownElement call site. Freeze still pins the same node/content
        // the old branch remembered, so completed blocks do not remount (onForgotten)
        // while tail, typography and theme continue to follow live inputs.
        // Block text, not the whole document. A longer tail keeps this capture;
        // a real correction or theme change refreshes it.
        val blockSource = preparedBlock?.cacheKey?.source ?: content
        val pinned = if (freeze) remember(blockSource, preparedBlock?.spec) { Triple(node, content, preparedBlock) } else null
        val frozenNode = pinned?.first ?: node
        val frozenContent = pinned?.second ?: content
        val providedBlock = if (freeze) {
            pinned?.third?.takeIf { it.node === frozenNode && it.source === frozenContent }
        } else {
            preparedBlock
        }
        CompositionLocalProvider(LocalPreparedMarkdownBlock provides providedBlock) {
            MarkdownElement(
                node = frozenNode,
                components = components,
                content = frozenContent,
                includeSpacer = false,
            )
        }
    }
}

internal fun shouldFreezeStreamingMarkdownBlock(
    blockStartOffset: Int,
    tailStartOffset: Int?,
): Boolean = tailStartOffset != null && blockStartOffset != tailStartOffset

private const val STREAMING_PARSE_PUBLISH_INTERVAL_MS = 90L
private const val PROGRESSIVE_FIRST_FRAME_CHARS = 240
private const val PROGRESSIVE_FRAME_CHARS = 400
private const val STREAMING_PROGRESSIVE_FIRST_FRAME_CHARS = 480
private const val STREAMING_PROGRESSIVE_FRAME_CHARS = 900

/**
 * 从 [current] 开始按字符预算继续纳入顶层块；返回值不超过块数。
 * [mustAdvance] 为 true 时每次至少前进一块，保证单个超长块也能在有限帧内完成；
 * 为 false 时（点击那一帧）超预算的首块留到下一帧。
 */
internal fun nextProgressiveBlockLimit(
    blockLengths: List<Int>,
    current: Int,
    charBudget: Int,
    mustAdvance: Boolean = true,
): Int {
    var limit = current.coerceIn(0, blockLengths.size)
    var used = 0
    while (limit < blockLengths.size) {
        val length = blockLengths[limit]
        if (used + length > charBudget && (used > 0 || !mustAdvance)) break
        used += length
        limit++
    }
    return limit
}

internal fun streamingMarkdownBlockVisible(
    coordinatorActive: Boolean,
    firstRevealKey: RevealBlockKey?,
    startedRevealKeys: Set<RevealBlockKey>,
    nextRevealKey: RevealBlockKey?,
): Boolean {
    if (!coordinatorActive) return true
    if (firstRevealKey == null) return true
    if (firstRevealKey in startedRevealKeys) return true
    return firstRevealKey == nextRevealKey
}

internal fun topLevelMarkdownBlocks(root: ASTNode): List<ASTNode> =
    root.children.filterNot { node -> node.type == MarkdownTokenTypes.EOL }

internal fun markdownBlockSpacing(previous: IElementType?, current: IElementType): TextUnit {
    if (previous == null) return 0.sp
    if (previous.isMarkdownHeading() && current.isMarkdownHeading()) return 12.sp
    if (current.isMarkdownHeading()) {
        return if (current == MarkdownElementTypes.ATX_1 ||
            current == MarkdownElementTypes.SETEXT_1 ||
            current == MarkdownElementTypes.ATX_2 ||
            current == MarkdownElementTypes.SETEXT_2
        ) {
            24.sp
        } else {
            20.sp
        }
    }
    if (previous.isMarkdownHeading()) return 10.sp
    if (previous.isMarkdownParagraph() && current.isMarkdownParagraph()) return 16.sp
    if (previous.isMarkdownStructuredBlock() || current.isMarkdownStructuredBlock()) return 16.sp
    return 14.sp
}

private fun IElementType.isMarkdownHeading(): Boolean = when (this) {
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1,
    MarkdownElementTypes.SETEXT_2,
    -> true

    else -> false
}

private fun IElementType.isMarkdownParagraph(): Boolean =
    this == MarkdownElementTypes.PARAGRAPH || this == MarkdownTokenTypes.TEXT

private fun IElementType.isMarkdownStructuredBlock(): Boolean = when (this) {
    MarkdownElementTypes.ORDERED_LIST,
    MarkdownElementTypes.UNORDERED_LIST,
    MarkdownElementTypes.BLOCK_QUOTE,
    MarkdownElementTypes.CODE_BLOCK,
    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.IMAGE,
    MarkdownTokenTypes.HORIZONTAL_RULE,
    TABLE,
    -> true

    else -> false
}

internal data class StreamingMarkdownTarget(
    val content: String,
    val isStreaming: Boolean,
    val renderSpec: PreparedMarkdownSpec? = null,
    val queuedAtNs: Long = System.nanoTime(),
    // Captured at enqueue; null stays unknown when no reliable run mapping exists.
    val diagnosticAttribution: StreamDiagnosticAttribution? = StreamPerformanceDiagnostics.captureAttribution(),
)

internal fun streamingMarkdownBatchSize(backlogChars: Int): Int = when {
    backlogChars >= 384 -> 96
    backlogChars >= 160 -> 64
    backlogChars >= 64 -> 40
    else -> 24
}

internal fun streamingMarkdownBatchEnd(
    content: String,
    start: Int,
    maxGraphemes: Int,
): Int {
    return AppendOnlyGraphemeIndex().apply { update(content) }.endAfter(start, maxGraphemes)
}

// ── Markdown 样式：克制的聊天排版，标题只作强调不作页面标题 ─────────────

private enum class ChatMarkdownTone {
    Answer,
    Thinking,
}

@Composable
private fun chatMarkdownTypography(tone: ChatMarkdownTone) = markdownTypography(
    h1 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 20.sp else 15.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 28.sp else 22.sp,
        fontWeight = FontWeight.Bold,
    ),
    h2 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 18.sp else 15.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 26.sp else 22.sp,
        fontWeight = FontWeight.Bold,
    ),
    h3 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 17.sp else 15.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 25.sp else 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    h4 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 16.sp else 15.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 26.sp else 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    h5 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 16.sp else 15.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 26.sp else 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    h6 = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 16.sp else 15.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 26.sp else 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    text = chatMarkdownBodyStyle(tone),
    paragraph = chatMarkdownBodyStyle(tone),
    ordered = chatMarkdownBodyStyle(tone),
    bullet = chatMarkdownBodyStyle(tone),
    list = chatMarkdownBodyStyle(tone),
    quote = chatSecondaryStyle().copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 16.sp else 14.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 26.sp else 22.sp,
        color = chatMarkdownTextColor(ChatMarkdownTone.Thinking),
    ),
    code = TextStyle(
        fontSize = if (tone == ChatMarkdownTone.Answer) 13.sp else 12.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 20.sp else 18.sp,
        fontFamily = FontFamily.Monospace,
        color = chatMarkdownTextColor(tone),
    ),
    inlineCode = chatMarkdownBodyStyle(tone).copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 14.sp else 13.sp,
        fontFamily = FontFamily.Monospace,
    ),
    table = chatSecondaryStyle().copy(
        fontSize = if (tone == ChatMarkdownTone.Answer) 14.sp else 13.sp,
        lineHeight = if (tone == ChatMarkdownTone.Answer) 21.sp else 19.sp,
        color = chatMarkdownTextColor(tone),
    ),
    textLink = TextLinkStyles(
        style = SpanStyle(
            color = etaPrimary(),
            fontWeight = FontWeight.Medium,
        ),
    ),
)

@Composable
private fun chatMarkdownBodyStyle(tone: ChatMarkdownTone) =
    if (tone == ChatMarkdownTone.Answer) {
        chatBodyStyle().copy(
            fontSize = 16.sp,
            lineHeight = 26.sp,
            color = chatMarkdownTextColor(tone),
        )
    } else {
        // 与工具展开详情共用同一档位（chatExpandedBodyStyle 为唯一出处）。
        chatExpandedBodyStyle()
    }

@Composable
private fun chatMarkdownTextColor(tone: ChatMarkdownTone): Color =
    if (tone == ChatMarkdownTone.Answer) {
        etaOnSurface()
    } else {
        etaOnSurfaceVariant()
    }

@Composable
private fun chatMarkdownColors(tone: ChatMarkdownTone) = markdownColor(
    text = chatMarkdownTextColor(tone),
    // 代码块与表格的底色、描边由自定义组件绘制，这里只保留行内代码底色与分隔线。
    codeBackground = chatSurfaceColor(),
    inlineCodeBackground = etaSurfaceContainerHigh().copy(alpha = 0.6f),
    dividerColor = chatDividerColor(0.5f),
    tableBackground = Color.Transparent,
)

@Composable
private fun chatMarkdownDimens() = markdownDimens(
    dividerThickness = 0.5.dp,
    codeBackgroundCornerSize = 10.dp,
    blockQuoteThickness = 3.dp,
)

@Composable
private fun chatMarkdownPadding() = markdownPadding(
    // 顶层块由 ChatMarkdownDocument 按语义分配留白，库的统一前置间距保持关闭。
    block = 0.dp,
    list = 3.dp,
    listItemTop = 3.dp,
    listItemBottom = 3.dp,
    listIndent = 14.dp,
    codeBlock = PaddingValues(horizontal = 13.dp, vertical = 11.dp),
    blockQuote = PaddingValues(horizontal = 12.dp),
    blockQuoteText = PaddingValues(vertical = 4.dp),
    blockQuoteBar = PaddingValues.Absolute(left = 2.dp, top = 4.dp, right = 0.dp, bottom = 4.dp),
)

private fun chatMarkdownComponents(
    revealCoordinator: SmoothTextRevealCoordinator? = null,
    suppressEmptyListMarkers: Boolean = false,
) = markdownComponents(
    text = { model ->
        if (revealCoordinator == null) {
            MarkdownText(
                content = model.node.getUnescapedTextInNode(model.content),
                node = model.node,
                style = model.typography.text,
            )
        } else {
            ChatRevealRawText(model, revealCoordinator)
        }
    },
    paragraph = { model ->
        if (model.node.containsMarkdownImage()) {
            ChatMarkdownImageParagraph(
                content = model.content,
                node = model.node,
                style = model.typography.paragraph,
            )
        } else {
            ChatRevealMarkdownText(
                model = model,
                style = model.typography.paragraph,
                revealCoordinator = revealCoordinator,
            )
        }
    },
    image = { model ->
        ChatMarkdownImage(
            content = model.content,
            node = model.node,
        )
    },
    inlineImage = { model ->
        ChatMarkdownImage(
            content = model.content,
            node = model.node,
            sourceOverride = model.content,
            fillPlaceholder = true,
        )
    },
    orderedList = { model ->
        ChatMarkdownList(
            model = model,
            ordered = true,
            revealCoordinator = revealCoordinator,
            suppressEmptyMarker = suppressEmptyListMarkers,
        )
    },
    unorderedList = { model ->
        ChatMarkdownList(
            model = model,
            ordered = false,
            revealCoordinator = revealCoordinator,
            suppressEmptyMarker = suppressEmptyListMarkers,
        )
    },
    heading1 = { ChatHeadingBlock(it, it.typography.h1, revealCoordinator = revealCoordinator) },
    heading2 = { ChatHeadingBlock(it, it.typography.h2, revealCoordinator = revealCoordinator) },
    heading3 = { ChatHeadingBlock(it, it.typography.h3, revealCoordinator = revealCoordinator) },
    heading4 = { ChatHeadingBlock(it, it.typography.h4, revealCoordinator = revealCoordinator) },
    heading5 = { ChatHeadingBlock(it, it.typography.h5, revealCoordinator = revealCoordinator) },
    heading6 = { ChatHeadingBlock(it, it.typography.h6, revealCoordinator = revealCoordinator) },
    setextHeading1 = {
        ChatHeadingBlock(
            it,
            it.typography.h1,
            setext = true,
            revealCoordinator = revealCoordinator,
        )
    },
    setextHeading2 = {
        ChatHeadingBlock(
            it,
            it.typography.h2,
            setext = true,
            revealCoordinator = revealCoordinator,
        )
    },
    codeFence = { model ->
        val revealState = if (revealCoordinator != null) {
            rememberSmoothTextRevealState(
                key = RevealBlockKey(model.node.startOffset),
                coordinator = revealCoordinator,
            )
        } else {
            null
        }
        val startedKeys = rememberStartedRevealKeys(revealCoordinator)
        MarkdownCodeFence(model.content, model.node, style = model.typography.code) { code, language, style ->
            ChatCodeBlock(
                code = code,
                language = language,
                style = style,
                revealState = revealState,
                showChrome = revealCoordinator == null ||
                    RevealBlockKey(model.node.startOffset) in startedKeys,
            )
        }
    },
    codeBlock = { model ->
        val revealState = if (revealCoordinator != null) {
            rememberSmoothTextRevealState(
                key = RevealBlockKey(model.node.startOffset),
                coordinator = revealCoordinator,
            )
        } else {
            null
        }
        val startedKeys = rememberStartedRevealKeys(revealCoordinator)
        MarkdownCodeBlock(model.content, model.node, style = model.typography.code) { code, language, style ->
            ChatCodeBlock(
                code = code,
                language = language,
                style = style,
                revealState = revealState,
                showChrome = revealCoordinator == null ||
                    RevealBlockKey(model.node.startOffset) in startedKeys,
            )
        }
    },
    table = { model ->
        ChatMarkdownTable(
            content = model.content,
            node = model.node,
            style = model.typography.table,
            revealCoordinator = revealCoordinator,
        )
    },
    blockQuote = { model ->
        ChatBlockQuote(model)
    },
)

/**
 * 流式列表不能直接使用库的默认实现：默认实现会立即绘制 marker，而正文还在显现动画中。
 * 这里把每一项作为稳定的组合单元，并让 marker 与该项首个正文块共享开始时机。
 */
@Composable
private fun ChatMarkdownList(
    model: MarkdownComponentModel,
    ordered: Boolean,
    revealCoordinator: SmoothTextRevealCoordinator?,
    suppressEmptyMarker: Boolean,
    depth: Int = model.listDepth,
) {
    val components = LocalMarkdownComponents.current
    val padding = LocalMarkdownPadding.current
    val items = remember(model.node) {
        model.node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
    }
    if (items.isEmpty()) return

    val startedRevealKeys = rememberStartedRevealKeys(revealCoordinator)
    val initialListNumber = items.first()
        .getUnescapedTextInNode(model.content)
        .takeWhile(Char::isDigit)
        .toIntOrNull()
        ?: 1

    Column(
        modifier = Modifier.padding(
            start = padding.listIndent * depth,
            top = padding.list,
            bottom = padding.list,
        ),
    ) {
        items.forEachIndexed { index, item ->
            key(item.startOffset, item.type.name) {
                val firstRevealKey = remember(item) { item.firstRevealBlockKey() }
                val checkboxNode = remember(item) {
                    item.children.firstOrNull { child -> child.type == CHECK_BOX }
                }
                val markerVisible = streamingListMarkerVisible(
                    coordinatorActive = suppressEmptyMarker,
                    firstRevealKey = firstRevealKey,
                    startedRevealKeys = startedRevealKeys,
                    containsImage = item.containsMarkdownImage(),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .streamingListItemLayout(visible = firstRevealKey == null || markerVisible)
                        .semantics { isTraversalGroup = true }
                        .padding(
                            top = padding.listItemTop,
                            bottom = padding.listItemBottom,
                        ),
                ) {
                    Box(
                        modifier = Modifier.graphicsLayer(
                            // 隐藏 marker 但保留它的测量宽度，避免正文横向跳动。
                            alpha = if (markerVisible) 1f else 0f,
                        ),
                    ) {
                        if (checkboxNode != null) {
                            components.checkbox(
                                MarkdownComponentModel(
                                    content = model.content,
                                    node = checkboxNode,
                                    typography = model.typography,
                                ),
                            )
                        } else if (ordered) {
                            Text(
                                text = "${initialListNumber + index}.",
                                style = model.typography.ordered.copy(
                                    color = etaPrimary(),
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        } else {
                            // Compose 单行 Text 在默认 Trim.Both 下忽略 lineHeight，行框即字体自然行高；
                            // marker 必须与正文同 fontSize/lineHeight 才能共享度规对齐，
                            // 层级差异只通过字形与颜色表达。
                            val bulletDepth = depth % 3
                            Text(
                                text = when (bulletDepth) {
                                    0 -> "•"
                                    1 -> "◦"
                                    else -> "▪"
                                },
                                style = model.typography.bullet.copy(
                                    color = if (bulletDepth == 2) {
                                        etaOnSurfaceVariant()
                                    } else {
                                        etaPrimary()
                                    },
                                ),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column {
                        item.children.forEach { child ->
                            when (child.type) {
                                MarkdownElementTypes.ORDERED_LIST -> {
                                    ChatMarkdownList(
                                        model = MarkdownComponentModel(
                                            content = model.content,
                                            node = child,
                                            typography = model.typography,
                                        ),
                                        ordered = true,
                                        revealCoordinator = revealCoordinator,
                                        suppressEmptyMarker = suppressEmptyMarker,
                                        depth = depth + 1,
                                    )
                                }

                                MarkdownElementTypes.UNORDERED_LIST -> {
                                    ChatMarkdownList(
                                        model = MarkdownComponentModel(
                                            content = model.content,
                                            node = child,
                                            typography = model.typography,
                                        ),
                                        ordered = false,
                                        revealCoordinator = revealCoordinator,
                                        suppressEmptyMarker = suppressEmptyMarker,
                                        depth = depth + 1,
                                    )
                                }

                                else -> MarkdownElement(
                                    node = child,
                                    components = components,
                                    content = model.content,
                                    includeSpacer = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberStartedRevealKeys(
    coordinator: SmoothTextRevealCoordinator?,
): Set<RevealBlockKey> = if (coordinator == null) {
    emptySet()
} else {
    coordinator.started.collectAsState().value
}

internal fun streamingListMarkerVisible(
    coordinatorActive: Boolean,
    firstRevealKey: RevealBlockKey?,
    startedRevealKeys: Set<RevealBlockKey>,
    containsImage: Boolean,
): Boolean = !coordinatorActive ||
    firstRevealKey?.let(startedRevealKeys::contains) == true ||
    (firstRevealKey == null && containsImage)

@Composable
private fun ChatRevealRawText(
    model: MarkdownComponentModel,
    revealCoordinator: SmoothTextRevealCoordinator,
) {
    val prepared = LocalPreparedMarkdownBlock.current?.text(model.node, null)
    val text = prepared ?: remember(markdownRenderCacheKey(model.content, model.node)) {
        StreamPerformanceDiagnostics.measure("markdown.annotated.raw", (model.node.endOffset - model.node.startOffset).toLong()) {
            AnnotatedString(model.node.getUnescapedTextInNode(model.content))
        }
    }
    ChatRevealAnnotatedText(
        text = text,
        node = model.node,
        sourceContent = model.content,
        style = model.typography.text,
        revealCoordinator = revealCoordinator,
    )
}

@Composable
private fun ChatRevealMarkdownText(
    model: MarkdownComponentModel,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator?,
    modifier: Modifier = Modifier,
    contentChildType: IElementType? = null,
) {
    val annotatorSettings = annotatorSettings()
    val contentNode = remember(model.node, contentChildType) {
        contentChildType?.let(model.node::findChildOfType) ?: model.node
    }
    val prepared = LocalPreparedMarkdownBlock.current?.text(contentNode, style.toSpanStyle())
    val text = prepared ?: remember(markdownRenderCacheKey(model.content, contentNode), style, annotatorSettings) {
        StreamPerformanceDiagnostics.measure("markdown.annotated.build", (contentNode.endOffset - contentNode.startOffset).toLong()) {
            buildAnnotatedString {
                pushStyle(style.toSpanStyle())
                buildMarkdownAnnotatedString(
                    content = model.content,
                    node = contentNode,
                    annotatorSettings = annotatorSettings,
                )
                pop()
            }
        }
    }
    if (revealCoordinator == null) {
        ChatSelectableText(text = text, style = style, modifier = modifier)
        return
    }
    ChatRevealAnnotatedText(
        text = text,
        node = model.node,
        sourceContent = model.content,
        style = style,
        revealCoordinator = revealCoordinator,
        modifier = modifier,
    )
}

@Composable
private fun ChatRevealAnnotatedText(
    text: AnnotatedString,
    node: ASTNode,
    sourceContent: String,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator,
    modifier: Modifier = Modifier,
) {
    val revealState = rememberSmoothTextRevealState(
        key = RevealBlockKey(node.startOffset),
        coordinator = revealCoordinator,
    )
    ChatSelectableText(
        text = text,
        modifier = modifier.smoothTextReveal(revealState),
        style = style.copy(textMotion = TextMotion.Animated),
        onTextLayout = { layoutResult ->
            revealState.onTextLayout(text.text, layoutResult)
        },
    )
}

/**
 * 标题自身只负责文字样式；与相邻块的距离由文档级排版统一决定。
 */
@Composable
private fun ChatHeadingBlock(
    model: MarkdownComponentModel,
    style: TextStyle,
    setext: Boolean = false,
    revealCoordinator: SmoothTextRevealCoordinator? = null,
) {
    val contentChildType = if (setext) {
        MarkdownTokenTypes.SETEXT_CONTENT
    } else {
        MarkdownTokenTypes.ATX_CONTENT
    }
    if (model.node.containsMarkdownImage()) {
        ChatMarkdownImageParagraph(
            content = model.content,
            node = model.node.findChildOfType(contentChildType) ?: model.node,
            style = style,
            modifier = Modifier.semantics { heading() },
        )
    } else {
        ChatRevealMarkdownText(
            model = model,
            style = style,
            revealCoordinator = revealCoordinator,
            contentChildType = contentChildType,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/**
 * 代码块：顶栏显示语言标签并提供一键复制，正文等宽字体、超出横向滚动。
 */
@Composable
private fun ChatCodeBlock(
    code: String,
    language: String?,
    style: TextStyle,
    revealState: SmoothTextRevealState? = null,
    showChrome: Boolean = true,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    val codeModifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .then(
            if (showChrome) Modifier.padding(horizontal = 13.dp, vertical = 11.dp)
            else Modifier,
        )
        .let { base ->
            if (revealState != null) base.smoothTextReveal(revealState) else base
        }
    val codeText: @Composable () -> Unit = {
        Text(
            text = code,
            style = if (revealState != null) {
                style.copy(textMotion = TextMotion.Animated)
            } else {
                style
            },
            color = etaOnSurface(),
            modifier = codeModifier,
            onTextLayout = revealState?.let { state ->
                { layoutResult -> state.onTextLayout(code, layoutResult) }
            },
        )
    }
    val codeBlockBody: @Composable () -> Unit = {
        if (showChrome) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 13.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language?.takeIf { it.isNotBlank() } ?: "code",
                    style = chatTertiaryStyle(),
                    color = etaOnSurfaceVariant(),
                    modifier = Modifier.weight(1f),
                )
                ChatIconAction(
                    onClick = {
                        @Suppress("DEPRECATION")
                        clipboardManager.setText(AnnotatedString(code))
                        copied = true
                    },
                    minWidth = 28.dp,
                    minHeight = 28.dp,
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check
                            else Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(
                            if (copied) R.string.copy_copied else R.string.copy_code,
                        ),
                        modifier = Modifier.size(EtaDimens.MicroIconSize),
                        tint = if (copied) {
                            etaPrimary()
                        } else {
                            etaOnSurfaceVariant().copy(alpha = 0.8f)
                        },
                    )
                }
            }
            ChatHairlineDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp))
        }
        // Selection registers the whole code block on every layout. A growing
        // fence does that on each publish, so it stays a plain Text until done.
        if (revealState == null) HapticSelectionContainer { codeText() } else codeText()
    }
    // 带框代码块用官方 Surface（官方形状令牌 + outlineVariant 描边）；
    // 无框（inline）路径保持原有的 clip + 背景 + 描边组合。
    if (showChrome) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = chatCardShape(),
            color = MaterialTheme.colorScheme.surface,
            border = chatCardBorder(),
        ) {
            Column(content = { codeBlockBody() })
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        content = { codeBlockBody() },
    )
}

private val ChatTableCellWidth = 112.dp

/**
 * 表格：细描边容器 + 表头浅底加粗 + 行间发丝分隔线；列宽不足时整体横向滚动。
 */
@Composable
private fun ChatMarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
    revealCoordinator: SmoothTextRevealCoordinator? = null,
) {
    val headerCells = remember(node) {
        node.findChildOfType(HEADER)?.children?.filter { it.type == CELL }.orEmpty()
    }
    val bodyRows = remember(node) {
        node.children.filter { it.type == ROW }
            .map { row -> row.children.filter { it.type == CELL } }
    }
    if (headerCells.isEmpty()) return

    val borderColor = chatDividerColor(0.5f)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        val tableWidth = ChatTableCellWidth * headerCells.size
        val scrollable = maxWidth <= tableWidth
        Column(
            modifier = (if (scrollable) {
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .requiredWidth(tableWidth)
            } else {
                Modifier.fillMaxWidth()
            })
                .clip(chatCardShape())
                .border(0.5.dp, borderColor, chatCardShape())
                .background(chatSurfaceColor()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(etaSurfaceContainerHigh().copy(alpha = 0.45f))
                    .height(IntrinsicSize.Max),
            ) {
                headerCells.forEach { cell ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) {
                        ChatMarkdownTableCell(
                            content = content,
                            cell = cell,
                            style = style.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            revealCoordinator = revealCoordinator,
                        )
                    }
                }
            }
            val tableTail = if (revealCoordinator == null) null else bodyRows.asReversed().firstOrNull { row ->
                row.any { cell -> !cell.containsMarkdownImage() }
            }
            bodyRows.forEach { rowCells ->
                val rowSettled = revealCoordinator == null || rowCells !== tableTail
                ChatHairlineDivider(0.6f, Modifier.fillMaxWidth())
                Row(modifier = completedContentDrawLayer(Modifier.fillMaxWidth(), rowSettled)) {
                    rowCells.forEach { cell ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            ChatMarkdownTableCell(
                                content = content,
                                cell = cell,
                                style = style,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                revealCoordinator = revealCoordinator,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMarkdownTableCell(
    content: String,
    cell: ASTNode,
    style: TextStyle,
    maxLines: Int,
    overflow: TextOverflow,
    revealCoordinator: SmoothTextRevealCoordinator?,
) {
    if (revealCoordinator == null || cell.containsMarkdownImage()) {
        MarkdownTableBasicText(
            content = content,
            cell = cell,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
        )
        return
    }

    val annotatorSettings = annotatorSettings()
    val prepared = LocalPreparedMarkdownBlock.current?.text(cell, style.toSpanStyle())
    val text = prepared ?: remember(markdownRenderCacheKey(content, cell), style, annotatorSettings) {
        StreamPerformanceDiagnostics.measure("markdown.annotated.cell", (cell.endOffset - cell.startOffset).toLong()) {
            buildAnnotatedString {
                pushStyle(style.toSpanStyle())
                buildMarkdownAnnotatedString(
                    content = content,
                    node = cell,
                    annotatorSettings = annotatorSettings,
                )
                pop()
            }
        }
    }
    val revealState = rememberSmoothTextRevealState(
        key = RevealBlockKey(cell.startOffset),
        coordinator = revealCoordinator,
    )
    Text(
        text = text,
        style = style.copy(textMotion = TextMotion.Animated),
        color = etaOnSurface(),
        maxLines = maxLines,
        overflow = overflow,
        modifier = Modifier.smoothTextReveal(revealState),
        onTextLayout = { layoutResult ->
            revealState.onTextLayout(text.text, layoutResult)
        },
    )
}

/**
 * 引用块：圆角浅色竖条 + 弱化文字。
 * 库默认实现把竖条颜色绑死在 quote 文字颜色上，无法分别控制，因此竖条自绘；
 * 子节点仍交给 ambient components，流式显现与嵌套引用行为不变。
 */
@Composable
private fun ChatBlockQuote(model: MarkdownComponentModel) {
    val components = LocalMarkdownComponents.current
    val padding = LocalMarkdownPadding.current
    val dimens = LocalMarkdownDimens.current
    val a11yLabels = LocalMarkdownA11yLabels.current
    val barColor = etaPrimary().copy(alpha = 0.4f)
    val emptyLineHeight = with(LocalDensity.current) {
        model.typography.quote.lineHeight.takeOrElse { 22.sp }.toDp()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = a11yLabels.blockquote }
            .drawBehind {
                val thickness = dimens.blockQuoteThickness.toPx()
                val x = padding.blockQuoteBar
                    .calculateStartPadding(LayoutDirection.Ltr).toPx() + thickness / 2
                drawLine(
                    color = barColor,
                    strokeWidth = thickness,
                    start = Offset(x, padding.blockQuoteBar.calculateTopPadding().toPx()),
                    end = Offset(
                        x,
                        size.height - padding.blockQuoteBar.calculateBottomPadding().toPx(),
                    ),
                    cap = StrokeCap.Round,
                )
            }
            .padding(padding.blockQuote),
    ) {
        model.node.children.forEach { child ->
            key(child.startOffset) {
                when (child.type) {
                    MarkdownElementTypes.BLOCK_QUOTE -> ChatBlockQuote(
                        MarkdownComponentModel(
                            content = model.content,
                            node = child,
                            typography = model.typography,
                        ),
                    )

                    MarkdownTokenTypes.EOL -> Spacer(Modifier.height(emptyLineHeight))

                    else -> MarkdownElement(
                        node = child,
                        components = components,
                        content = model.content,
                        includeSpacer = false,
                    )
                }
            }
        }
    }
}

private fun ASTNode.containsMarkdownImage(): Boolean =
    type == MarkdownElementTypes.IMAGE || children.any { child -> child.containsMarkdownImage() }

/** 找到列表项中首个会被显现协调器管理的块，marker 以它作为显示时机。 */
private fun ASTNode.firstRevealBlockKey(): RevealBlockKey? = when (type) {
    MarkdownTokenTypes.TEXT -> RevealBlockKey(startOffset)

    MarkdownElementTypes.PARAGRAPH,
    MarkdownElementTypes.ATX_1,
    MarkdownElementTypes.ATX_2,
    MarkdownElementTypes.ATX_3,
    MarkdownElementTypes.ATX_4,
    MarkdownElementTypes.ATX_5,
    MarkdownElementTypes.ATX_6,
    MarkdownElementTypes.SETEXT_1,
    MarkdownElementTypes.SETEXT_2,
    -> if (!containsMarkdownImage()) RevealBlockKey(startOffset) else null

    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.CODE_BLOCK,
    -> RevealBlockKey(startOffset)

    TABLE -> children.asSequence()
        .flatMap { it.depthFirstSequence() }
        .firstOrNull { it.type == CELL && !it.containsMarkdownImage() }
        ?.let { RevealBlockKey(it.startOffset) }

    MarkdownElementTypes.IMAGE,
    MarkdownTokenTypes.EOL,
    MarkdownTokenTypes.HORIZONTAL_RULE,
    -> null

    else -> children.asSequence().mapNotNull(ASTNode::firstRevealBlockKey).firstOrNull()
}

private fun ASTNode.depthFirstSequence(): Sequence<ASTNode> = sequence {
    yield(this@depthFirstSequence)
    children.forEach { child -> yieldAll(child.depthFirstSequence()) }
}

private fun State.Success.revealBlockKeys(): Set<RevealBlockKey> = buildSet {
    node.children.forEach { child -> collectRevealBlockKeys(child) }
}

private fun MutableSet<RevealBlockKey>.collectRevealBlockKeys(node: ASTNode) {
    when (node.type) {
        MarkdownTokenTypes.TEXT -> add(RevealBlockKey(node.startOffset))

        MarkdownElementTypes.PARAGRAPH,
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
        MarkdownElementTypes.SETEXT_1,
        MarkdownElementTypes.SETEXT_2,
        -> if (!node.containsMarkdownImage()) add(RevealBlockKey(node.startOffset))

        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.CODE_BLOCK,
        -> add(RevealBlockKey(node.startOffset))

        TABLE -> collectTableCellRevealKeys(node)

        MarkdownElementTypes.IMAGE,
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.HORIZONTAL_RULE,
        -> Unit

        else -> node.children.forEach { child -> collectRevealBlockKeys(child) }
    }
}

private fun MutableSet<RevealBlockKey>.collectTableCellRevealKeys(node: ASTNode) {
    if (node.type == CELL) {
        if (!node.containsMarkdownImage()) add(RevealBlockKey(node.startOffset))
        return
    }
    node.children.forEach { child -> collectTableCellRevealKeys(child) }
}

// ── 思考过程 ─────────────────────────────────────────────────────────

@Composable
private fun ThinkingRow(
    message: ThinkingMessageUi,
    retainedStreamingState: StreamingMarkdownState?,
    modifier: Modifier = Modifier,
    isPaused: Boolean = false,
    onToggle: ((String, Boolean) -> Unit)? = null,
    position: SegmentedRowPosition = SegmentedRowPosition.Whole,
) {
    val bodyTraceMount = remember { nextChatBodyTraceMount() }
    SideEffect { traceChatBodyRun("thinking", bodyTraceMount) }
    var expanded by rememberSaveable(message.id) { mutableStateOf(!message.collapsed) }
    var manuallyExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    // 仅本次组合内由点击触发的展开才分帧组合正文；不跨配置变更保存。
    var expandedByTap by remember(message.id) { mutableStateOf(false) }
    val toggleProbeRef = remember(message.id) { ToggleProbeRef() }
    // 思考结束后立即切换为与完成态回答相同的稳定 Markdown。工具执行期间 App 可能
    // 处于后台，不能让旧思考保留显现债务，回来后在新回答旁边补播整段内容。
    val streamingState = if (message.isStreaming) {
        retainedStreamingState ?: remember(message.id) { StreamingMarkdownState() }
    } else {
        null
    }
    LaunchedEffect(message.isStreaming) {
        if (manuallyExpanded) return@LaunchedEffect
        expanded = message.isStreaming
    }

    val pulseAlpha = rememberActivePulse(
        active = message.isStreaming && !isPaused,
        label = "thinking_pulse",
    )

    // 跟工具行一样，点标题展开/收起。展开前由宿主解除底部锚定（标题钉在原位，
    // 面板向屏幕下方生长）；正文里的链接和长按选择先拿到手势，不会被这里吃掉。
    val toggle: () -> Unit = {
        onToggle?.invoke(message.id, !expanded)
        manuallyExpanded = true
        expandedByTap = !expanded
        expanded = !expanded
        toggleProbeRef.token = StreamPerformanceDiagnostics.markToggle("thinking", expanded)
        StreamPerformanceDiagnostics.probeEvent(
            toggleProbeRef.token,
            "item",
            "chars=${message.content.length} streaming=${message.isStreaming}",
        )
    }
    val thinkingTitle = if (message.isStreaming) {
        stringResource(R.string.reasoning_in_progress)
    } else {
        message.elapsedSeconds?.takeIf { it > 0 }?.let { seconds ->
            pluralStringResource(R.plurals.reasoning_completed_seconds, seconds, seconds)
        } ?: stringResource(R.string.reasoning_completed)
    }
    // 展开后的详情子条目：流式期间用增量 Markdown，结束后切稳定 Markdown。
    val thinkingDetails: (@Composable AnimatedVisibilityScope.() -> Unit)? =
        if (message.content.isBlank()) {
            null
        } else {
            {
                HapticSelectionContainer(
                    modifier = retainDrawLayerWhenIdle(enabled = streamingState == null)
                        .toggleProbe(toggleProbeRef, "content"),
                ) {
                    if (streamingState != null) {
                        StreamingMarkdown(
                            state = streamingState,
                            content = message.content,
                            isStreaming = message.isStreaming,
                            isPaused = isPaused,
                            onRevealCompleteChange = {},
                            tone = ChatMarkdownTone.Thinking,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        androidx.compose.runtime.CompositionLocalProvider(LocalToggleProbe provides toggleProbeRef) {
                            StableMarkdown(
                                content = message.content,
                                tone = ChatMarkdownTone.Thinking,
                                modifier = Modifier.fillMaxWidth(),
                                progressive = expandedByTap,
                            )
                        }
                    }
                }
            }
        }
    AgentWorkSegmentedBubble(
        leading = {
            // 裸 Icon（24dp + 官方 ItemBetweenSpace 12dp），常态着色与设置页统一
            // onSurfaceVariant；流式进行中是特殊情况，用 primary 单独着色
            // （呼吸透明度是动效反馈）。
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = if (message.isStreaming && !isPaused) etaPrimary() else etaOnSurfaceVariant(),
                modifier = Modifier.graphicsLayer { alpha = if (message.isStreaming && !isPaused) pulseAlpha() else 1f },
            )
        },
        title = thinkingTitle,
        titleColor = if (message.isStreaming && !isPaused) etaPrimary() else null,
        modifier = modifier,
        expanded = expanded,
        onToggle = toggle,
        details = thinkingDetails,
        expandAnimationModifier = Modifier.toggleProbe(toggleProbeRef, "visible"),
        position = position,
    )
}

// ── 工作组摘要（激进折叠档） ─────────────────────────────────────────

/**
 * 激进折叠档收起的工作组摘要：一条「已完成 N 个步骤」的 SegmentedListItem 气泡。
 * 收起时整组只留这一行（本轮只剩用户消息与最后一条回复）；点开后步骤行以兄弟行
 * 出现在列表里（由 AgentChatBody 的投影切换），不再是卡中卡。
 */
@Composable
internal fun AgentWorkProcessSummary(
    group: AgentTimelineEntry.WorkProcess,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    isPaused: Boolean = false,
    onToggle: () -> Unit,
) {
    val messages = group.messages
    val running = messages.any { message ->
        (message is ThinkingMessageUi && message.isStreaming) ||
            (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running)
    }
    val toolCount = messages.count { it is ToolActivityMessageUi }
    // 激进档摘要可能没有工具调用、只有被并进来的中间回复，摘要据此读作「N 条中间回复」。
    val replyCount = messages.count { it is AgentMessageUi && it.content.isNotBlank() }
    // 已作答的提问卡同样会被并进来：只有问答的组不该顶着工具锤子图标。
    val questionCount = messages.count { it is io.github.mangi.eta.ui.model.AgentQuestionMessageUi }
    val runningTool = if (running) {
        messages.lastOrNull { message ->
            message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running
        } as? ToolActivityMessageUi
    } else null
    val runningToolTitle = runningTool?.argumentsSummary?.takeIf { it.isNotBlank() }
        ?: runningTool?.let { toolDisplayName(it.toolName) }
    val view = LocalView.current
    // 收起态用一行列出本组实际跑过的工具名（去重、本地化列表连接）：
    // 折叠时也能看清「干了什么」，不必先展开。展开态与运行中都不加这行。
    val stepToolsLabel = if (running || expanded) {
        null
    } else {
        val names = messages.mapNotNull { message ->
            (message as? ToolActivityMessageUi)?.toolName
        }
            .distinct()
            .map { toolDisplayName(view.context.resources, it) }
        if (names.isEmpty()) {
            null
        } else {
            ListFormatter.getInstance(view.context.resources.configuration.locales[0])
                .format(names)
        }
    }
    val headerLabel = when {
        running && toolCount > 0 -> pluralStringResource(
            R.plurals.work_processing_step,
            toolCount,
            toolCount,
        ) + (runningToolTitle?.let { " · $it" } ?: "")
        running -> stringResource(R.string.work_analyzing)
        toolCount > 0 -> pluralStringResource(
            R.plurals.work_completed_steps,
            toolCount,
            toolCount,
        ) + if (replyCount > 0) {
            " · " + pluralStringResource(R.plurals.work_intermediate_replies, replyCount, replyCount)
        } else {
            ""
        }
        replyCount > 0 -> pluralStringResource(
            R.plurals.work_intermediate_replies,
            replyCount,
            replyCount,
        )
        else -> stringResource(R.string.work_completed)
    }
    AgentWorkSegmentedBubble(
        leading = {
            // 裸 Icon（24dp + 官方 ItemBetweenSpace 12dp），常态着色与设置页统一
            // onSurfaceVariant；运行中是特殊情况，用 primary 单独着色。
            Icon(
                imageVector = when {
                    runningTool != null -> ToolPresentation.icon(runningTool.toolName)
                    running -> Icons.Rounded.AutoAwesome
                    toolCount == 0 && (replyCount > 0 || questionCount > 0) -> Icons.Rounded.QuestionAnswer
                    else -> Icons.Rounded.Build
                },
                contentDescription = null,
                tint = if (running && !isPaused) etaPrimary() else etaOnSurfaceVariant(),
            )
        },
        title = headerLabel,
        titleColor = if (running && !isPaused) etaPrimary() else null,
        supporting = stepToolsLabel,
        modifier = modifier,
        expanded = expanded,
        onToggle = onToggle,
        // 折叠态是单独一条（外圆）；展开后步骤行接在下面，摘要行成为组首（下沿内圆）——
        // 但首步若是纯文本回复，列表断开，摘要仍是单独一条。
        position = if (expanded && group.summaryContinuesIntoSteps()) {
            SegmentedRowPosition.First
        } else {
            SegmentedRowPosition.Whole
        },
    )
}

// ── 工具调用：优雅极简时间线 ─────────────────────────────────────────


/**
 * 展开和收起的时长、缓动不变，只换生长方向：下沿被钉住时从下沿长出，
 * 让已经排好的内容停在原处、由标签往上让开；否则从上沿往下长。
 *
 * 时长与缓动统一取官方 `motionScheme` 规格。
 */
@Composable
internal fun tailDetailsEnter(fromBottom: Boolean): androidx.compose.animation.EnterTransition {
    // 面板始终在标题下方、自上而下展开（expandFrom=Top）。标题行位置纹丝不动，
    // 也不再因锚定方向让面板内容自下而上滑入产生位移感；动画规格取官方 motionScheme。
    // 透明度属 Effects（无过冲），高度展开属 Spatial（轻微过冲）。
    return fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec<Float>()) + expandVertically(
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntSize>(),
        expandFrom = Alignment.Top,
    )
}


/**
 * 展开或收起还在进行时，内容尺寸每一帧都在变，绘制命令必须重录。
 * 停在展开之后，把内容画进一张与屏幕同分辨率的离屏纹理。
 * 之后滑动只移动这张纹理，渲染线程不再重放正文的文字命令。
 * 文字、选择和流式更新都不变；内容变化时纹理会重画。
 * enabled 同时承接内容完成条件，不能再在同一边界叠 completedContentDrawLayer。
 * 高于 [MAX_RETAINED_LAYER_HEIGHT_PX] 的内容不缓存，避免纹理被裁切。
 */
private const val MAX_RETAINED_LAYER_HEIGHT_PX = 8192

@Composable
internal fun AnimatedVisibilityScope.retainDrawLayerWhenIdle(enabled: Boolean = true): Modifier {
    val settled = transition.currentState == EnterExitState.Visible &&
        transition.targetState == EnterExitState.Visible
    // 带代码块的 graphicsLayer 每次重组都是新实例，Compose 会作废纹理并重录。
    // 工具行运行时整行都在重组，离屏纹理因此每帧失效。这里用稳定参数，内容不变就不重录。
    // 过高的内容超过纹理上限就会被裁切，宁可不缓存。
    var heightPx by remember { mutableIntStateOf(0) }
    val cache = enabled && settled && heightPx in 1..MAX_RETAINED_LAYER_HEIGHT_PX
    return Modifier
        .onSizeChanged { heightPx = it.height }
        // 保留同一个绘制层节点，只切换合成策略；展开终态或高度越界时不插拔正文绘制层。
        .graphicsLayer(
            compositingStrategy = if (cache) {
                CompositingStrategy.Offscreen
            } else {
                CompositingStrategy.Auto
            },
        )
}

@Composable
internal fun tailDetailsExit(toBottom: Boolean): androidx.compose.animation.ExitTransition {
    // 与进入动画对称，向顶部收起（面板往标题方向收），标题行保持不动。
    return shrinkVertically(
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntSize>(),
        shrinkTowards = Alignment.Top,
    ) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec<Float>())
}

/**
 * 工具状态标记：四种状态统一为 16dp 图标语法，不再混用「圆点 + 文字」。
 *
 * - 运行中：M3E `LoadingIndicator`（形状变形）——与输入区、朗读按钮同一套 loading
 *   语言，比闪烁圆点更明确；行内不再重复「执行中」三个字，
 * - 成功：低饱和度对勾。成功是常态，不抢注意力；
 * - 失败：error 色图标 + 「失败」小字。失败是唯一保留文字的状态——托管工具失败时
 *   没有原因文案，只靠图标读不出含义；
 * - 未知（重载后残留的执行中行）：中性 dash 图标，不播报术语。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolStatusIndicator(status: ToolActivityStatusUi) {
    val runningLabel = stringResource(R.string.tool_status_running)
    // 状态切换动效取官方 motionScheme 规格：透明度走 Effects（无过冲），缩放走 Spatial。
    val statusTransition: ContentTransform =
        (fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec<Float>()) +
            scaleIn(MaterialTheme.motionScheme.defaultSpatialSpec<Float>(), initialScale = 0.86f))
            .togetherWith(
                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec<Float>()) +
                    scaleOut(
                        MaterialTheme.motionScheme.fastSpatialSpec<Float>(),
                        targetScale = 0.86f,
                    )
            )
    AnimatedContent(
        targetState = status,
        transitionSpec = { statusTransition },
        label = "tool_status",
    ) { current ->
        when (current) {
            // 图标槽只有 16dp，官方默认 48dp 容器被外层 size 收敛；
            // contentDescription 补上被去掉的「执行中」文案，TalkBack 不退回裸进度条。
            ToolActivityStatusUi.Running -> LoadingIndicator(
                modifier = Modifier
                    .size(16.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = runningLabel
                    },
                color = MaterialTheme.colorScheme.primary,
            )
            // 成功是常态：低饱和的 onSurfaceVariant，不抢注意力。
            ToolActivityStatusUi.Success -> Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = stringResource(R.string.tool_status_success),
                modifier = Modifier.size(EtaDimens.StatusIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ToolActivityStatusUi.Failed -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 文字已承载标签，图标不再重复播报。错误用官方 error 角色。
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(EtaDimens.StatusIconSize),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.tool_status_failed),
                    // 官方尾部辅助文字令牌：ItemTrailingSupportingTextFont = labelSmall。
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ToolActivityStatusUi.Unknown -> Icon(
                imageVector = Icons.Rounded.Remove,
                contentDescription = stringResource(R.string.tool_status_unknown),
                modifier = Modifier.size(EtaDimens.StatusIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToolActivityInline(
    message: ToolActivityMessageUi,
    onOpenBrowser: () -> Unit,
    showBrowserShortcut: Boolean,
    enableLivePreview: Boolean = true,
    modifier: Modifier = Modifier,
    position: SegmentedRowPosition = SegmentedRowPosition.Whole,
) {
    val bodyTraceMount = remember { nextChatBodyTraceMount() }
    SideEffect { traceChatBodyRun("tool", bodyTraceMount) }
    var isExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    // 仅本次组合内由点击触发的展开才把命令与结果推迟一帧；不跨配置变更保存。
    var expandedByTap by remember(message.id) { mutableStateOf(false) }
    val toggleProbeRef = remember(message.id) { ToggleProbeRef() }
    // 只有「当前浏览器」卡片订阅实时会话快照，避免每个工具行都跟随快照重组
    val browserSnapshot = if (showBrowserShortcut) {
        AgentBrowserSession.snapshots.collectAsState().value
    } else {
        null
    }

    val title = message.argumentsSummary.ifBlank { toolDisplayName(message.toolName) }
    // 展开区至少要有一样真东西可看：命令、参数、结果、工具读到的图片，或浏览器入口。
    // 全都为空的行保持不可展开（箭头一并隐藏），不做「点开只有一句话」的空交互。
    val hasDetails = !message.command.isNullOrBlank() ||
        !message.resultSummary.isNullOrBlank() ||
        !message.argumentsDetail.isNullOrBlank() ||
        !message.resultDetail.isNullOrBlank() ||
        message.mediaPreviews.isNotEmpty() ||
        message.detailsWithheld ||
        showBrowserShortcut
    val browserSubtitle = browserSnapshot?.let { snapshot ->
        when {
            snapshot.isLoading ->
                stringResource(R.string.tool_browser_loading, snapshot.progress)
            snapshot.host.isNotBlank() && snapshot.title.isNotBlank() ->
                "${snapshot.host} · ${snapshot.title}"
            snapshot.host.isNotBlank() -> snapshot.host
            else -> null
        }
    }
    // 工具读回/生成的图片张数：截图、读图、图像生成都会带回 imageCount，
    // 折叠行里点明「这步其实处理了 N 张图」，避免用户以为工具什么都没看到。
    val imageCountLabel = message.imageCount.takeIf { it > 0 }?.let { count ->
        pluralStringResource(R.plurals.overlay_images_read, count, count)
    }
    // 失败原因直接显示在折叠行，不必展开卡片；剥离去重「失败」前缀与日志用的 code= 尾巴
    val failureSubtitle = if (message.status == ToolActivityStatusUi.Failed) {
        val failedLabel = stringResource(R.string.trace_failed)
        message.resultSummary
            ?.lineSequence()?.firstOrNull()
            ?.removePrefix("$failedLabel · ")
            ?.substringBefore(" · code=")
            ?.takeIf { it.isNotBlank() && it != failedLabel }
    } else {
        null
    }

    val toolRowSettled = message.status != ToolActivityStatusUi.Running
    // 展开前解除底部锚定，标题钉在原位、面板向屏幕下方生长可见。
    val releaseAnchor = LocalChatAnchorRelease.current
    val toggle: () -> Unit = {
        if (!isExpanded) releaseAnchor()
        expandedByTap = !isExpanded
        isExpanded = !isExpanded
        toggleProbeRef.token = StreamPerformanceDiagnostics.markToggle("tool", isExpanded)
        StreamPerformanceDiagnostics.probeEvent(
            toggleProbeRef.token,
            "item",
            "status=${message.status} commandChars=${message.command?.length ?: 0} " +
                "resultChars=${message.resultSummary?.length ?: 0} browser=$showBrowserShortcut",
        )
    }
    // 展开后的详情子条目：命令、参数、结果、工具读到的图片与浏览器入口。
    val toolDetails: (@Composable AnimatedVisibilityScope.() -> Unit)? = if (!hasDetails) {
        null
    } else {
        {
            // 命令块本身是 surfaceContainerHighest 的官方 Surface，无需再套面板底色，
            // 层级依旧清晰：气泡 surfaceContainer → 命令井 surfaceContainerHighest。
            HapticSelectionContainer(modifier = Modifier.toggleProbe(toggleProbeRef, "content")) {
                val bodyReady = rememberDeferredBody(deferOneFrame = expandedByTap)
                if (bodyReady) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 敏感工具的原文不落库：重开会话后只剩一句「完成」时，说清楚为什么。
                        if (message.detailsWithheld &&
                            message.argumentsDetail.isNullOrBlank() &&
                            message.resultDetail.isNullOrBlank()
                        ) {
                            Text(
                                text = stringResource(R.string.tool_detail_sensitive),
                                style = chatExpandedBodyStyle(),
                            )
                        }
                        if (!message.command.isNullOrBlank()) {
                            ToolCommandBlock(
                                command = message.command,
                                context = message.argumentsSummary,
                            )
                        }
                        // 完整调用参数：工具真正收到的字段。命令块已经把命令原文
                        // 摆在上面时，参数里不再重复这一项（由 formatter 去掉）。
                        message.argumentsDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                            ToolDetailBlock(
                                text = detail,
                                label = stringResource(R.string.tool_detail_arguments),
                                copyLabel = stringResource(R.string.tool_detail_copy_arguments),
                            )
                        }
                        if (message.mediaPreviews.isNotEmpty()) {
                            ToolMediaStrip(sources = message.mediaPreviews)
                        }
                        if (!message.resultSummary.isNullOrBlank() || !message.resultDetail.isNullOrBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.ui_result_0a2c91),
                                    style = chatQuaternaryStyle(),
                                    color = etaOnSurfaceVariant(),
                                )
                                // 摘要是一行结论，完整返回内容在下面：模型当时读到的就是那份文本。
                                // 有完整内容时摘要只留首行，避免终端输出的预览行重复一遍。
                                message.resultSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                                    val summaryLine = if (message.resultDetail.isNullOrBlank()) {
                                        summary
                                    } else {
                                        summary.lineSequence().firstOrNull().orEmpty()
                                    }
                                    if (summaryLine.isNotBlank()) {
                                        HapticSelectionContainer {
                                            Text(
                                                text = summaryLine,
                                                style = chatExpandedBodyStyle(),
                                            )
                                        }
                                    }
                                }
                                message.resultDetail?.takeIf { it.isNotBlank() }?.let { detail ->
                                    ToolDetailBlock(
                                        text = detail,
                                        // 「结果」标题就在上面，这里不再重复一遍。
                                        label = null,
                                        copyLabel = stringResource(R.string.tool_detail_copy_result),
                                    )
                                }
                                // 图片张数：结果摘要往往只描述「看到了什么」，
                                // 不说明带回了多少张图，这里补一行计数。
                                if (imageCountLabel != null) {
                                    Text(
                                        text = imageCountLabel,
                                        style = chatQuaternaryStyle(),
                                        color = etaOnSurfaceVariant(),
                                    )
                                }
                            }
                        }
                        if (showBrowserShortcut) {
                            browserSnapshot?.takeIf { it.available }?.let { snapshot ->
                                BrowserPagePreview(
                                    snapshot = snapshot,
                                    enableLivePreview = enableLivePreview,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(onClick = onOpenBrowser) {
                                    Text(
                                        text = stringResource(R.string.ui_open_current_browser_58358e),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    AgentWorkSegmentedBubble(
        leading = {
            // 裸 Icon（官方默认几何）；成功/未知常态统一 onSurfaceVariant——
            // 与设置页行首图标同色，不抢注意力；运行中用 primary、失败用
            // 官方 error 角色单独着色。
            Icon(
                imageVector = ToolPresentation.icon(message.toolName),
                contentDescription = null,
                tint = when (message.status) {
                    ToolActivityStatusUi.Success -> etaOnSurfaceVariant()
                    ToolActivityStatusUi.Running -> etaPrimary()
                    ToolActivityStatusUi.Failed -> MaterialTheme.colorScheme.error
                    ToolActivityStatusUi.Unknown -> etaOnSurfaceVariant()
                },
            )
        },
        title = title,
        titleColor = if (message.status == ToolActivityStatusUi.Running) etaPrimary() else null,
        modifier = modifier.let { completedContentDrawLayer(it, toolRowSettled) },
        // 副标题优先级：失败原因 > 浏览器实时状态 > 图片张数；失败原因用官方 error 角色着色。
        supporting = failureSubtitle ?: browserSubtitle ?: imageCountLabel,
        supportingColor = failureSubtitle?.let { MaterialTheme.colorScheme.error },
        trailingStatus = { ToolStatusIndicator(message.status) },
        expanded = isExpanded,
        onToggle = if (hasDetails) toggle else null,
        details = toolDetails,
        expandAnimationModifier = Modifier.toggleProbe(toggleProbeRef, "visible"),
        position = position,
    )
}

/**
 * 浏览器工具的实时页面预览：迷你地址条 + 当前视口截图。
 *
 * 截图只在页面加载中或内容稳定后的低频节拍刷新；组合销毁即停止，
 * 不做后台轮询。截图不可用时退化为图标占位。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BrowserPagePreview(
    snapshot: BrowserSessionSnapshot,
    modifier: Modifier = Modifier,
    enableLivePreview: Boolean = true,
) {
    var preview by remember(snapshot.url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(snapshot.url, snapshot.isLoading, snapshot.isUserControlling, enableLivePreview) {
        if (snapshot.isUserControlling || !enableLivePreview) return@LaunchedEffect
        while (true) {
            val image = withContext(Dispatchers.IO) {
                AgentBrowserSession.capturePreview()?.let { decodeDataUrlBitmap(it.dataUrl) }
            }
            if (image != null) preview = image
            delay(if (snapshot.isLoading) 1_200L else 4_000L)
        }
    }

    val previewBody: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 加载中用 M3E LoadingIndicator（morph 变形，与朗读按钮、工具行同一套
            // loading 语言）；稳定后仍是一个静态状态点（直径取官方 CircularStrokeWidth
            // 轨道厚度）。原先这里裸放默认 40dp 的 CircularProgressIndicator，跟在
            // Caption 级域名后面明显失重，收敛进 16dp 图标槽。
            if (snapshot.isLoading) {
                LoadingIndicator(
                    modifier = Modifier.size(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(ProgressIndicatorDefaults.CircularStrokeWidth)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = snapshot.host.ifBlank { snapshot.displayUrl },
                style = chatTertiaryStyle(),
                color = etaOnSurfaceVariant(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val image = preview
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = stringResource(R.string.tool_browser_preview),
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Language,
                    contentDescription = null,
                    modifier = Modifier.size(EtaDimens.DenseIconSize),
                    tint = chatDividerColor(),
                )
            }
        }
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            if (snapshot.title.isNotBlank()) {
                Text(
                    text = snapshot.title,
                    style = chatExpandedBodyStyle(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (snapshot.displayUrl.isNotBlank()) {
                Text(
                    text = snapshot.displayUrl,
                    style = chatTertiaryStyle(),
                    color = etaOnSurfaceVariant().copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    // 官方 Surface 承担预览底板（官方 shapes.medium + surfaceContainer 角色）。
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column { previewBody() }
    }
}

@Composable
private fun ToolCommandBlock(
    command: String,
    context: String,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(command) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    val commandBody: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.ifBlank { stringResource(R.string.shell_command) },
                style = chatTertiaryStyle(),
                color = etaOnSurfaceVariant(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ChatIconAction(
                onClick = {
                    @Suppress("DEPRECATION")
                    clipboardManager.setText(AnnotatedString(command))
                    copied = true
                },
                minWidth = 28.dp,
                minHeight = 28.dp,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check
                        else Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(
                        if (copied) R.string.copy_copied else R.string.copy_command,
                    ),
                    modifier = Modifier.size(EtaDimens.StatusIconSize),
                    tint = if (copied) {
                        etaPrimary()
                    } else {
                        etaOnSurfaceVariant().copy(alpha = 0.8f)
                    },
                )
            }
        }
        ChatHairlineDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        HapticSelectionContainer {
            Text(
                text = command,
                style = chatExpandedBodyStyle(monospace = true),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
    // 官方 Surface 承担命令块容器。内容必须包 Column——Surface 的
    // content 不是 ColumnScope，标题行/分割线/命令文本会被当 Box 子级叠在同一位置
    // （此前命令文字压住上下文标题的根因）。底色用 surfaceContainerHighest 与
    // 展开面板（surfaceContainerHigh）拉开色阶层级，形状用官方 small 档、不再描边。
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        content = { Column { commandBody() } },
    )
}

/**
 * 工具卡片展开区的通用文本块：调用参数与完整返回内容共用。
 *
 * 文本按原样展示（等宽、可选中、可复制），高度到上限后在块内滚动——
 * 展开都点了，就不该再截成一行省略号。超过上限的部分由格式化层截断并注明。
 */
@Composable
private fun ToolDetailBlock(
    text: String,
    copyLabel: String,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_400)
            copied = false
        }
    }

    val detailBody: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!label.isNullOrBlank()) {
                Text(
                    text = label,
                    style = chatTertiaryStyle(),
                    color = etaOnSurfaceVariant(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            ChatIconAction(
                onClick = {
                    clipboardManager.setText(AnnotatedString(text))
                    copied = true
                },
                minWidth = 28.dp,
                minHeight = 28.dp,
            ) {
                Icon(
                    imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                    contentDescription = if (copied) stringResource(R.string.copy_copied) else copyLabel,
                    modifier = Modifier.size(EtaDimens.StatusIconSize),
                    tint = if (copied) etaPrimary() else etaOnSurfaceVariant().copy(alpha = 0.8f),
                )
            }
        }
        ChatHairlineDivider(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
        HapticSelectionContainer {
            Text(
                text = text,
                style = chatExpandedBodyStyle(monospace = true),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = ToolDetailMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        content = { Column { detailBody() } },
    )
}

private val ToolDetailMaxHeight = 280.dp

/**
 * 工具读到的图片：一张卡片最多三张缩略图，点击走聊天图片的全屏预览。
 * 这些是工具当次真正送进模型的图（截图、读图、网页截图），不是缩略占位。
 */
@Composable
private fun ToolMediaStrip(
    sources: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.tool_detail_images),
            style = chatQuaternaryStyle(),
            color = etaOnSurfaceVariant(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            sources.forEach { source ->
                ToolMediaThumbnail(source = source)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolMediaThumbnail(
    source: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(source) {
        bitmap = withContext(Dispatchers.IO) {
            ChatImageBytes.load(context, source)?.bitmap
        }
    }
    Box(
        modifier = modifier
            .size(96.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            ChatClickableImage(
                source = source,
                bitmap = image,
                contentDescription = stringResource(R.string.chat_image_preview),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            LoadingIndicator(
                modifier = Modifier.size(16.dp),
                color = etaOnSurfaceVariant(),
            )
        }
    }
}

// ── Run trace：轻量入口行 ─────────────────────────────────────────────
@Composable
private fun RunTraceRow(
    message: RunTraceMessageUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val body: @Composable RowScope.() -> Unit = {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            modifier = Modifier.size(EtaDimens.StatusIconSize),
            tint = etaPrimary(),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.ui_available_capacity_743337),
            style = chatSecondaryStyle(),
            color = etaOnSurface(),
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(EtaDimens.StatusIconSize),
            tint = etaOnSurfaceVariant().copy(alpha = 0.7f),
        )
    }
    // 官方 Surface(onClick) 承担可点区域、官方形状（shapes.medium）、
    // surface 底色、outlineVariant 描边与官方涟漪。
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = chatCardBorder(0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { body() }
    }
}

// ── 工具摘要 ──────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolSummaryInline(
    message: ToolSummaryMessageUi,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.tools.forEach { tool ->
            val chipBody: @Composable () -> Unit = {
                Icon(
                    imageVector = ToolPresentation.icon(tool),
                    contentDescription = null,
                    // 与卡片内其他前导图标统一为 20dp。
                    modifier = Modifier.size(EtaDimens.DenseIconSize),
                    tint = etaPrimary()
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = toolDisplayName(tool),
                    style = chatTertiaryStyle(),
                    color = etaOnSurfaceVariant()
                )
            }
            // 官方 Surface 承担只读标签容器（官方形状、官方 colorScheme 角色与描边）。
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    content = { chipBody() },
                )
            }
        }
    }
}

// ── 上下文压缩分界 ─────────────────────────────────────────────────────

@Composable
private fun ContextCompactedDivider(
    message: ContextCompactedMessageUi,
    modifier: Modifier = Modifier,
) {
    // Zero-count maintenance markers keep token accounting, but have no user-facing notice.
    // This also hides pruning notices saved by older versions.
    if (message.compactedCount <= 0) return
    val view = LocalView.current
    var showSummary by remember { mutableStateOf(false) }
    val labelColor = etaOnSurfaceVariant()
    val summaryAction = stringResource(R.string.context_compacted_summary_action)
    val countLabel = pluralStringResource(
        R.plurals.context_compacted_messages,
        message.compactedCount,
        message.compactedCount,
    )
    val openSummary: () -> Unit = {
        TouchHaptics.click(view)
        showSummary = true
    }
    val labelBody: @Composable () -> Unit = {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(EtaDimens.MicroIconSize),
            tint = labelColor,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = countLabel,
            style = chatTertiaryStyle(),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    // 官方 HorizontalDivider 画两侧线条（官方 DividerDefaults），
    // 官方 Surface(onClick) 承担中间可点的摘要标签（官方形状与涟漪）。
    // contentDescription 保留，但只放「动作提示」，不放条数：官方 Surface(onClick) 链尾是
    // `childSemantics()`，会把内容的 Text 合并进同一节点；ContentDescription 与 Text 是
    // 两个不同的 SemanticsPropertyKey，分别落到 AccessibilityNodeInfo 的两个字段，
    // TalkBack 会**两串都念**而非互相顶替。所以 cd 必须与子文本**互补**（"查看摘要" +
    // "压紧了 N 条"），一旦把条数也抄进 cd 就会重复播报。
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Surface(
            onClick = openSummary,
            modifier = Modifier.semantics { contentDescription = summaryAction },
            shape = MaterialTheme.shapes.extraSmall,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row { labelBody() }
        }
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
    if (showSummary) {
        ContextCompactedSummarySheet(
            summary = message.summary,
            compressorLabel = message.compressorLabel,
            onDismiss = { showSummary = false },
        )
    }
}

// ── 建议语 ────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionChipsRow(
    message: SuggestionChipsMessageUi,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message.prompts.forEach { prompt ->
            // 官方 SuggestionChip 正是「可点的建议语」，容器形状、描边、
            // 内边距、涟漪与禁用态全部走官方默认值。
            // Chip 家族在 alpha29 里不合并子节点语义（只有 `role = Role.Button`），
            // 所以显式 mergeDescendants，让提示语作为按钮自身的播报文本被读一次。
            SuggestionChip(
                onClick = { onSuggestionClick(prompt) },
                modifier = Modifier.semantics(mergeDescendants = true) { },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(SuggestionChipDefaults.IconSize),
                    )
                },
                label = { Text(prompt) },
            )
        }
    }
}

// ── 辅助 ──────────────────────────────────────────────────────────────
