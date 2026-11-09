package io.github.mangi.eta.ui.components

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.agent.voice.VoiceModePhase
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ComposerQueueStore
import io.github.mangi.eta.ui.adaptive.EtaMenuDivider
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.remote.RemoteComposerStatus
import io.github.mangi.eta.ui.remote.remoteFailureText
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import java.time.LocalTime
import java.util.Calendar
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 键盘（IME）可见性，供 [Material3ComposerBody] 决定左侧动作组的收起 / 展开。
 *
 * 只在起落翻转时写入离散状态：IME inset 在键盘动画期间逐帧变化，若直接在输入区组合里读取
 * 会让整条输入栏按帧重组；这里改由 [snapshotFlow] 在组合外观察，仅把布尔结果写回状态。
 */
@Composable
internal fun rememberImeVisible(): Boolean {
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(density) {
        snapshotFlow { imeInsets.getBottom(density) > 0 }.collect { visible = it }
    }
    return visible
}

/**
 * Material 3 Expressive 输入区：官方浮动工具栏（Floating Toolbar）里的单行布局。
 *
 * 结构（自左向右）：
 * 1. 「+」主操作（官方 `FilledIconButton`，primary／onPrimary）→ 附件与发送选项菜单
 *    （定时发送 / 保存草稿 / 分享、拍照 / 视频 / 文件 / 文件夹 / 输入路径）。
 * 2. 图库（官方 Standard 图标按钮，`Icons.Rounded.PhotoLibrary`）→ 直接打开系统图片选择器。
 * 3. 「助手与模型」二合一入口（官方 Standard 图标按钮）→ 合并选择弹窗
 *    （助手 / 模型两个页签，底部为思考档位与上下文用量）。
 * 4. 胶囊输入框（官方 Filled 文本框，`surfaceContainer` 容器、固定 `extraLarge` 28dp 圆角），
 *    单行时 56dp 高即胶囊；多行时是圆角矩形（圆角不变），语音 / 发送按钮固定在右下角、
 *    生成中的加载指示器固定在左下角，两者同基线并与内层圆角保持同心（端距 8dp = 28 − 20）。
 *    键盘弹出且已有输入内容（文字 / 图片 / 文件 / 引用）时
 *    左侧动作组按官方 Expressive 弹簧规格收起、输入框展开占满，发送 / 停止按钮替代语音按钮的位置；
 *    键盘收起（未进入输入焦点）时动作组始终保持展开可见，方便随时再加号、再选图或切换助手与模型。
 *
 * 同心圆角：内层半径 = 外层半径 − 间距。外层固定 36dp（28 + 8，单行 72dp 高时即胶囊），
 * 内层固定 28dp，尾部 40dp 圆钮端距 8dp，三者两两满足规则；不用官方 CornerFull，
 * 否则输入框长高后外层会被拉成巨型胶囊、内外圆角失去同心关系。
 *
 * 整个工具栏容器、容器色（官方 vibrant 变体：`primaryContainer` / `onPrimaryContainer`）、
 * 高度、内边距与进出场弹簧动效全部取官方 `HorizontalFloatingToolbar` 默认令牌；可点区域一律为官方 Material 3 组件
 * （`FilledIconButton` / `IconButton` / `FilledTonalIconButton` / `OutlinedIconButton`），
 * 菜单用 Material 3 Expressive 写法（官方 `DropdownMenuPopup` + `DropdownMenuGroup` +
 * `DropdownMenuItem`），尺寸取 `IconButtonDefaults` 令牌，
 * 进出场动效一律取 `MaterialTheme.motionScheme` 的 Expressive 弹簧规格。
 *
 * 编辑态：胶囊左下角出现取消按钮（与右下发送按钮同心对称），左侧动作组整组收起。
 */
@Composable
internal fun Material3ComposerBody(
    textFieldState: TextFieldState,
    focusRequester: FocusRequester,
    /**
     * 键盘（IME）是否可见。由调用方通过 [rememberImeVisible] 提供；键盘收起（未进入输入焦点）
     * 时左侧动作组必须保持可见，即便已有输入内容。
     */
    imeVisible: Boolean,
    isEditingMessage: Boolean,
    isStreaming: Boolean,
    isPaused: Boolean,
    isCompressingContext: Boolean,
    canContinueDisconnected: Boolean,
    contextSendBlocked: Boolean,
    canSend: Boolean,
    usage: AgentContextUsageUi,
    /**
     * Popup height budget, deferred.
     *
     * Reading the input bar's window position during composition made the whole bar
     * recompose on every frame of the keyboard show/hide animation. Invoking this lambda
     * at popup-composition time keeps the value current without that cost.
     */
    popupMaxHeight: () -> Dp,
    onPositionedTopPx: (Int) -> Unit,
    /** 输入工具栏（渐变板的锚点胶囊）每次尺寸变化上报自身高度，供底栏渐变板定位顶盖圆心。 */
    onToolbarHeightPx: (Int) -> Unit = {},
    conversationId: String?,
    assistantId: String,
    /**
     * 当前会话所属助手的名字，用于空输入框的提示语（「交给 X 去完成」）。
     *
     * 调用方已经收着助手档案列表，故由调用方解析后传入；留空表示调用方拿不到名字
     * （例如语音浮层不关心助手），此时退回不带名字的通用文案。
     */
    assistantName: String = "",
    modelPickerState: AgentModelPickerUiState,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    gptSpeedMode: GptSpeedMode,
    gptSpeedSupported: Boolean,
    /**
     * 是否显示左侧动作组里的「助手与模型」按钮及其选择弹窗。
     *
     * 入口浮层不需要它：浮层不承载助手/模型切换，发送一律用当前全局默认选择。
     */
    showModelPicker: Boolean = true,
    voiceState: VoiceModeState,
    showMorphLoading: Boolean,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi,
    /** 流式期间短按发送暂存的排队消息；渲染在输入栏上方的可撤回胶囊里。 */
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String) -> Unit = {},
    onRemoveQueuedMessage: (String) -> Unit = {},
    onStartVoiceMode: (VoiceEntryMode) -> Unit,
    onStopVoiceMode: () -> Unit,
    onCancelMessageEdit: () -> Unit,
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onEditAssistant: (String) -> Unit,
    /** 从「模型与助手」弹窗进入模型配置页；入口浮层不显示该弹窗，默认空实现。 */
    onOpenModelProviders: () -> Unit = {},
    /** 从「模型与助手」弹窗进入助手管理页；入口浮层不显示该弹窗，默认空实现。 */
    onOpenAssistants: () -> Unit = {},
    /**
     * 「+」菜单「去搜索」：进入媒体语义搜索页挑本机媒体再添加到会话。
     * null（入口浮层）表示没有去处，菜单不渲染该行。
     */
    onOpenMediaSearch: (() -> Unit)? = null,
    /**
     * 长按「助手与模型」按钮：进入设置页的子代理配置页（预设组列表 / 预设详情）。
     *
     * 子代理配置只保留设置页一套页面（列表 + 详情两条独立路由），输入栏不再单独承载
     * 一套会话协作页；入口浮层没有设置页可去，默认空实现。
     */
    onOpenSubAgentSettings: () -> Unit = {},
    onAssistantSelected: (String) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCycleGptSpeedMode: () -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onAbortPausedRun: () -> Unit,
) {
    // 远程重连 / 失败：整颗输入框胶囊让位给状态胶囊——外形不变（36dp 同心圆角、官方
    // ContainerSize 高），整颗换成状态色，提示语顶替输入框，麦克风圆位由 LoadingIndicator
    // （重连中）/ 重试圆钮（失败）接管。队列条一并让位：断线期间本来就发不出去。
    // 草稿按会话键存于 ComposerQueueStore / coordinator 草稿表，恢复连接后原样回来。
    val remoteComposerStatus = io.github.mangi.eta.ui.remote.LocalRemoteComposerStatus.current

    // 草稿输入栏的整棵子树：断线期间由外层 AnimatedContent 整颗替换成状态胶囊，
    // 两条分支用同一条 Expressive 淡入淡出（尺寸同步收放、不裁切），不再硬切。
    @Composable
    fun DraftComposerBody() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var attachMenuExpanded by remember { mutableStateOf(false) }
    var showScheduleDialog by remember { mutableStateOf(false) }
    var showPickerSheet by remember { mutableStateOf(false) }
    val actions = rememberAgentAttachmentActions(
        onAttachImage = onAttachImage,
        onAttachVideo = onAttachVideo,
        onAttachFiles = onAttachFiles,
        onAttachFolder = onAttachFolder,
        onAttachFilePath = onAttachFilePath,
    )

    val draftText = textFieldState.text.toString()
    val hasContent = draftText.isNotBlank() ||
        pendingImages.isNotEmpty() ||
        pendingFileReferences.isNotEmpty() ||
        conversationMentions.pending.isNotEmpty()
    val sendMode = resolveChatComposerSendMode(
        isStreaming = isStreaming,
        isPaused = isPaused,
        canContinueDisconnected = canContinueDisconnected,
        hasSteerContent = hasContent,
        canStartNewSend = canSend,
        isCompressingContext = isCompressingContext,
    )
    val conversationKey = conversationId.orEmpty()
    // 「模型与助手」入口在生成中保持可点：运行配置按轮冻结，改选只登记待生效绑定，本轮不变。
    // 编辑态输入区承载的是另一条消息，才收起入口。
    val pickerSheetEnabled = !isEditingMessage

    // 重新进入该会话时，如果输入框还是空的，就恢复上次「保存草稿」存下的内容。
    LaunchedEffect(conversationKey) {
        if (conversationKey.isBlank()) return@LaunchedEffect
        if (textFieldState.text.isNotBlank()) return@LaunchedEffect
        val saved = ComposerQueueStore.draftFlow(context, conversationKey).first()
        if (saved.isNotBlank() && textFieldState.text.isBlank()) {
            textFieldState.setTextAndPlaceCursorAtEnd(saved)
        }
    }
    // 进入编辑态时收起弹窗：此时输入框承载的是另一条消息，改选没有意义。
    // 压缩中不收起——面板仍可点开查看，只是改选会被 owner 拒绝并给出提示。
    LaunchedEffect(pickerSheetEnabled) {
        if (!pickerSheetEnabled) showPickerSheet = false
    }
    // 输入栏进出场动效统一取官方 Expressive 弹簧规格（与输入区其它动效同一来源）。
    // 淡化属 Effects（临界阻尼、无过冲），宽度展开收缩属 Spatial（轻微过冲）。
    val enterSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()
    val exitSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.fastEffectsSpec()
    val growSpec: FiniteAnimationSpec<IntSize> = MaterialTheme.motionScheme.defaultSpatialSpec()
    val shrinkSpec: FiniteAnimationSpec<IntSize> = MaterialTheme.motionScheme.defaultSpatialSpec()

    // 尾部按钮（Call ⇄ Send ⇄ Stop）形变规格：与“输入文字后 Call→Send”同款，
    // 即淡入淡出 + 0.8 缩放；透明度走 Effects，缩放走 Spatial。
    val trailingFadeSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()
    val trailingScaleSpec: FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec()
    // 尾部槽位是否显示发送 / 停止 / 继续（而非语音入口）。
    // 有内容时让位给发送按钮；没有内容但生成 / 压缩中时保留停止按钮。
    val trailingShowsSend = hasContent || sendMode != "idle"

    // 「点按转写接管输入」的两段式：点击当帧先落字并停掉语音，等下一步重组把输入框换回来后
    // 再请求焦点 / 拉起键盘。若在点击当帧直接 requestFocus，胶囊里还是聆听条，
    // FocusRequester 尚未挂到输入框上，请求会落空。
    // 用自增令牌而不是布尔值作 key：effect 内部不再回写 key，避免写到一半被自身重启取消。
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var transcriptTakeOverToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(transcriptTakeOverToken) {
        if (transcriptTakeOverToken == 0) return@LaunchedEffect
        showChatInputIme(focusRequester, keyboard, view)
    }

    // 「转写接管」到键盘真正弹出之间的空窗：此时 imeVisible 还是 false，若照常按
    // “键盘未弹出”判据展开左侧动作组，胶囊会先冒出一排入口按钮、等键盘弹出后再收起，
    // 出现两段宽度跳变。这段窗口内直接按“已进入输入态”排版（动作组保持收起），
    // 键盘可见后立刻归还给常规判据——此后的收起 / 展开行为完全不变。
    var transcriptTakeOverAwaitingIme by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, transcriptTakeOverAwaitingIme) {
        if (transcriptTakeOverAwaitingIme && imeVisible) transcriptTakeOverAwaitingIme = false
    }
    // 兜底：极少数键盘最终没弹出来的场景（无软键盘、浮层窗口拿不到 IME inset），
    // 不能让动作组一直缺席，超时后归还常规判据。
    LaunchedEffect(transcriptTakeOverAwaitingIme) {
        if (!transcriptTakeOverAwaitingIme) return@LaunchedEffect
        delay(1_200)
        transcriptTakeOverAwaitingIme = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onPositionedTopPx(it.positionInWindow().y.toInt()) },
    ) {
        val voiceActive = voiceState.active || voiceState.error != null
        M3ComposerQueueStrip(
            items = queuedMessages,
            canSteerNow = isStreaming && !isPaused,
            onSteer = onSteerQueuedMessage,
            onRecall = onRecallQueuedMessage,
            onRemove = onRemoveQueuedMessage,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
        )
        HorizontalFloatingToolbar(
            expanded = true,
            // 官方 vibrant 变体：容器 primaryContainer、内容 onPrimaryContainer
            // （与参考稿“淡色底 + 深色图标”的关系一致）。
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            // 同心圆角：外层固定半径 = 内层 28dp（extraLarge）+ 8dp 间距 = 36dp。
            // 不能用官方 CornerFull：输入框多行长高后 CornerFull 会把外壳拉成巨型胶囊，
            // 内外圆角就不再满足“半径差 = 间距”。单行（72dp 高）时 36dp 恰是胶囊，外观不变。
            shape = RoundedCornerShape(ComposerOuterCornerRadius),
            // 关键：官方 minimumInteractiveBalancedPadding 只在“两侧都没有 leading/trailing
            // 内容”时才生效；它会读取内容里 MinimumInteractive*AlignmentLine（含位置偏移的大值）
            // 当补偿内边距，把整条工具栏撑高——空输入时被撑到 ~350dp、输入中无动作组时更夸张到整屏。
            // 官方本意是给 IconButton 组成的内容补平衡留白，本输入区布局完全显式（间距 + weight），
            // 因此显式传一个空的 leadingContent，让这组补偿归零（官方参数语义，非 hack）。
            leadingContent = {},
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { onToolbarHeightPx(it.height) },
        ) {
            // 左侧动作组：编辑态整组不组合——取消按钮已挪进胶囊左下角的共享槽
            // （见 composer_leading），胶囊直接撑满工具栏，不再由按钮占一段宽度。
            // 关键：语音态不能硬切换分支——否则开始 / 结束语音时动作组会瞬间消失 / 出现，
            // 胶囊宽度发生跳变，这正是“无输入框 → 聆听条”过渡不正确的原因。
            // 这里让语音态与“输入内容时动作组收起”共用同一条 AnimatedVisibility 弹簧：动作组向左收起、
            // 聆听条以右端为锚连续变宽，两段过渡完全同款（结束后反向展开）。
            //
            // 取消按钮原来放这里时踩了两个坑：① `align(Alignment.Bottom)` 传给
            // M3ComposerCancelButton 里的 EtaTooltip 内容，parentData 落在 Tooltip 内部布局上，
            // 对工具栏 Row 完全不生效，按钮于是停在上方、不与发送按钮同基线；
            // ② 水平端距按外层 36dp 圆角取 16dp、垂直却按内层 28dp 圆角取 8dp，混了两套同心规则。
            // 挪进胶囊共享槽后，start / bottom 都用 8dp，双层同心（8 + 28 = 36）由同一个 Box 保证。
            if (!isEditingMessage || voiceActive) {
                // 语音进行中时左侧动作组收起；键盘弹出且已有输入内容（文字 / 图片 / 文件 / 引用）时
                // 也收起，把宽度让给胶囊；键盘收起（未进入输入焦点）时动作组始终展开可见。
                AnimatedVisibility(
                    visible = !voiceActive &&
                        !transcriptTakeOverAwaitingIme &&
                        (!hasContent || !imeVisible),
                    // 同上：整组圆钮贴底，与尾部发送按钮同一条基线（距底端 8dp）。
                    // 单行时与竖直居中等价；多行输入框长高后整组跟随下沉贴底，不再停在中间。
                    modifier = Modifier
                        .align(Alignment.Bottom)
                        .padding(bottom = ComposerConcentricGap),
                    enter = fadeIn(enterSpec) +
                        expandHorizontally(growSpec, expandFrom = Alignment.Start),
                    exit = fadeOut(exitSpec) +
                        shrinkHorizontally(shrinkSpec, shrinkTowards = Alignment.Start),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 组首间距：第一个 40dp 圆形按钮距工具栏左端 = 官方内容内边距 8dp
                        // + 本间距 8dp = 16dp，与圆钮到上下的间距（(72 − 40)/2 = 16dp）相等，
                        // 即 36dp 外层半径 − 20dp 圆钮半径 = 16dp 的同心间距——端盖才是同心的；
                        // 只有官方内边距时圆钮会贴左端 8dp，与上下 16dp 不同心。
                        Spacer(modifier = Modifier.width(ComposerConcentricGap))
                        M3ComposerAddButton(
                            actions = actions,
                            expanded = attachMenuExpanded,
                            onExpandedChange = { attachMenuExpanded = it },
                            onScheduleSend = { showScheduleDialog = true },
                            onOpenMediaSearch = onOpenMediaSearch,
                            onSaveDraft = {
                                scope.launch {
                                    ComposerQueueStore.saveDraft(context, conversationKey, draftText)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.composer_draft_saved),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                            onShare = { shareDraft(context, draftText) },
                        )
                        Spacer(modifier = Modifier.width(ComposerToolbarGap))
                        M3ComposerPickImageButton(actions = actions)
                        // 「助手与模型」按钮连同它前面的间距一起隐藏：动作组收起/展开时
                        // 胶囊端盖的同心间距只依赖单个 ComposerToolbarGap，留一个空的间距会多出一段。
                        if (showModelPicker) {
                            Spacer(modifier = Modifier.width(ComposerToolbarGap))
                            M3ComposerPickerButton(
                                enabled = pickerSheetEnabled,
                                onClick = { showPickerSheet = true },
                                onOpenSubAgentSettings = onOpenSubAgentSettings,
                            )
                        }
                        // 组尾间距放进动画组：收起后输入框左右各贴工具栏 8dp 内边距
                        // （与上下间距一致），两个胶囊端盖保持同心。
                        Spacer(modifier = Modifier.width(ComposerToolbarGap))
                    }
                }
            }

            // 胶囊内容：正常输入框 ⇄ 聆听条（语音进行中）。
            // 这里只做交叉淡化，并显式关掉 AnimatedContent 自带的尺寸动画（`.using(null)`）：
            // 胶囊宽度不在这里动画，而是由左侧动作组的弹簧收起 / 展开驱动——本槽位是 Row 里的
            // weight(1f)，动作组收窄多少，胶囊左端就往左长出多少，右端始终贴住工具栏内边距，
            // 天然就是“以右端为锚向左展开 / 向右收回”，与“输入内容时动作组收起”是同一条弹簧。
            // 反过来，只要留下尺寸动画（更不用说再叠加横向 expandHorizontally / shrinkHorizontally），
            // 过渡期间必然出现两层矩形裁切：sizeTransform 默认 clip=true 按“动画尺寸”裁一次，
            // 子内容自身的 changeSize 也默认 clip=true 再裁一次；而尺寸动画的宽度总是滞后于
            // weight 槽位的真实宽度，内容于是被塞进一个更窄的矩形里——两端 28dp 圆角就是这样
            // 被切成平口的（“展开过程中两头有裁切”）。宽度改由布局驱动后不再有任何裁切。
            //
            // 槽位底衬（关键）：交叉淡化期间两份内容（输入框 ⇄ 聆听条，同为 surfaceContainer）
            // 同时只有部分 alpha，两层半透明源叠加后整体仍不满不透明，外层 vibrant 工具栏的
            // primaryContainer 会从中间透出来——就是"胶囊衔接中间出现透明度变化、看着发虚/变色"。
            // 这里在本槽位先铺一层同形（28dp 圆角）不透明底，前景怎么交叉淡化，下面始终是一块
            // 实心底，胶囊全程不透明，不会混入工具栏底色。
            val pillBackdropColor = MaterialTheme.colorScheme.surfaceContainer
            // 胶囊与尾部按钮的共享容器：胶囊内容（输入框 ⇄ 聆听条）只做交叉淡化，
            // 尾部按钮（Call ⇄ 发送 ⇄ 停止）单独用同一条弹簧形变——两者互不牵连。
            Box(modifier = Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = voiceActive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(pillBackdropColor, MaterialTheme.shapes.extraLarge)
                        // 供回归测试对“胶囊衔接”期间的像素取样（见 M3ComposerToolbarLayoutTest）。
                        .testTag("composer-pill-slot"),
                    // 内容宽度恒等于槽位宽度，横向对齐实际不参与；万一某帧两份内容尺寸不等，
                    // 也保持右端对齐，与“以右端为锚”的方向一致。
                    contentAlignment = Alignment.CenterEnd,
                    transitionSpec = {
                        (fadeIn(enterSpec).togetherWith(fadeOut(exitSpec))).using(null)
                    },
                    label = "composer_pill",
                ) { listening ->
                    if (listening) {
                        // 聆听条：麦克风状态圆钮 + 实时转写与阶段文案（末端停止圆钮由下面的
                        // 尾部按钮槽提供，见 composer_trailing）。
                        // 转写可点按：识别出文字但还没提交时，一点就退出语音、把这段话原样放进
                        // 输入框并拉起键盘，用户可以在识别结果上接着改，而不是重新说一遍。
                        // 只在 Listening 阶段放行：进入 Thinking / Speaking 后这句话已经送给模型
                        // （实时语音模式的转写还会挂到本轮结束），再点回输入框会变成重复发送。
                        M3VoiceBar(
                            state = voiceState,
                            onTranscriptTap = if (voiceState.phase == VoiceModePhase.Listening) {
                                {
                                    textFieldState.setTextAndPlaceCursorAtEnd(voiceState.transcript)
                                    // 先按输入态排版，等键盘真正可见再恢复常规判据（见上方 awaiting 标记）。
                                    transcriptTakeOverAwaitingIme = true
                                    onStopVoiceMode()
                                    transcriptTakeOverToken++
                                }
                            } else {
                                null
                            },
                        )
                    } else {
                        M3ComposerTextField(
                            textFieldState = textFieldState,
                            focusRequester = focusRequester,
                            isStreaming = isStreaming,
                            // 左下角有 40dp 圆钮（编辑态取消 / 生成中加载）时留 48dp 起始占位。
                            reserveLeadingSlot = showMorphLoading || isEditingMessage,
                            assistantName = assistantName,
                        )
                    }
                }

                // 起始侧槽：编辑态「取消编辑」圆钮 / 生成中形变加载指示器，共用胶囊左下角。
                // 与尾部发送 / 停止圆钮完全对称：40dp 圆钮贴住胶囊左下角，start / bottom 各让出
                // 8dp 同心端距（内层 28dp 圆角 − 圆钮半径 20dp = 8dp；再加外层工具栏 8dp 内边距，
                // 圆心距最外端盖 8 + 28 = 36dp = 外层圆角半径，内外两层圆角同时同心）。
                //
                // 两者原先都不在这里：取消按钮在工具栏 Row 里被 EtaTooltip 吃掉了 align，
                // 加载指示器在官方 leadingIcon 槽里只跟第一行、垂直顶对齐，多行长高后都停在
                // 左上角。挪到本共享容器后用 BottomStart 对齐，无论输入框多高都跟随下沉贴底。
                // 语音进行中胶囊换成聆听条（起始侧是麦克风状态圆钮），两者都必须让位。
                // 编辑态优先：取消编辑必须一眼可见、一键可点，不能被加载指示器盖住。
                // 用 AnimatedContent 而不是 AnimatedVisibility：外层工具栏内容是 RowScope，
                // 在 Box 里直接调 AnimatedVisibility 会被解析成 RowScope 的成员重载并触发
                // DSL_SCOPE_VIOLATION；与尾部槽同款写法还能共用同一条交叉淡化规格。
                // `.using(null)` 关掉默认尺寸动画：出现 / 消失时只做淡入淡出，
                // 不让 0 → 40dp 的尺寸变化把胶囊端盖裁一刀。
                val leadingState = when {
                    voiceActive -> ComposerLeading.NONE
                    isEditingMessage -> ComposerLeading.CANCEL
                    showMorphLoading -> ComposerLeading.LOADING
                    else -> ComposerLeading.NONE
                }
                AnimatedContent(
                    targetState = leadingState,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = ComposerConcentricGap, bottom = ComposerConcentricGap),
                    transitionSpec = {
                        (fadeIn(enterSpec).togetherWith(fadeOut(exitSpec))).using(null)
                    },
                    label = "composer_leading",
                ) { leading ->
                    when (leading) {
                        ComposerLeading.CANCEL -> M3ComposerCancelButton(
                            onClick = onCancelMessageEdit,
                            modifier = Modifier.testTag("composer-cancel-slot"),
                        )
                        ComposerLeading.LOADING -> ContainedMorphLoadingIndicator(
                            indicatorSize = IconButtonDefaults.smallContainerSize().width,
                            modifier = Modifier.testTag("composer-leading-slot"),
                        )
                        ComposerLeading.NONE -> Unit
                    }
                }

                // 尾部按钮槽：Call（语音入口）⇄ 发送 / 停止 / 继续 ⇄ 停止（语音进行中）。
                // 关键：必须放在上面 AnimatedContent 之外。若留在胶囊内容里，进入语音时它只会跟着
                // 整块胶囊一起交叉淡化，“通话按钮 → 停止按钮”就退化成简单渐变；移到这里后它与
                // “输入文字 Call→Send”共用同一条官方 Expressive 缩放 + 淡入淡出弹簧，形变一致。
                val trailingState = when {
                    voiceActive -> ComposerTrailing.VOICE_STOP
                    trailingShowsSend -> ComposerTrailing.SEND
                    else -> ComposerTrailing.VOICE_ENTRY
                }
                // 发送动作与“上下文已满”提示：与旧实现一致，发送成功后清掉本机保存的草稿副本。
                val sendDraft: () -> Unit = {
                    val text = textFieldState.text.toString()
                    scope.launch { ComposerQueueStore.saveDraft(context, conversationKey, "") }
                    onSubmit(text)
                }
                val blockedNotice: () -> Unit = {
                    Toast.makeText(
                        context,
                        context.getString(R.string.context_window_send_blocked),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        // 同心端距：40dp 圆钮半径 20dp 与内层 28dp 圆角相差 8dp。
                        .padding(end = ComposerConcentricGap, bottom = ComposerConcentricGap)
                        // 供回归测试对“尾部按钮形变”期间的像素取样（见 M3ComposerToolbarLayoutTest）。
                        .testTag("composer-trailing-slot"),
                ) {
                    AnimatedContent(
                        targetState = trailingState,
                        transitionSpec = {
                            // 与“输入文字后 Call→Send”同款：淡入淡出 + 0.8 缩放的官方弹簧。
                            (fadeIn(trailingFadeSpec) +
                                scaleIn(trailingScaleSpec, initialScale = 0.8f))
                                .togetherWith(
                                    fadeOut(trailingFadeSpec) +
                                        scaleOut(trailingScaleSpec, targetScale = 0.8f),
                                )
                        },
                        label = "composer_trailing",
                    ) { trailing ->
                        when (trailing) {
                            // 语音进行中：参考稿的粉色圆钮取官方 tertiaryContainer 角色。
                            ComposerTrailing.VOICE_STOP -> M3VoiceStopButton(onStop = onStopVoiceMode)
                            // 生成中发送：正文先排入上方队列，结束后自动发出；
                            // 想立刻送进当前运行则点队列项的闪电图标立即插话。
                            ComposerTrailing.SEND -> M3ComposerSendButton(
                                sendMode = sendMode,
                                isPaused = isPaused,
                                onSend = sendDraft,
                                onStop = onStop,
                                onContinue = onContinue,
                                onAbortPausedRun = onAbortPausedRun,
                                onBlocked = blockedNotice,
                            )
                            ComposerTrailing.VOICE_ENTRY -> VoiceEntryButton(
                                resetKey = isStreaming to isEditingMessage,
                                voiceState = voiceState,
                                onStartVoiceMode = onStartVoiceMode,
                                onStopVoiceMode = onStopVoiceMode,
                                // 尾部是麦克风图标的填充圆形按钮，直接对应「语音对话」入口。
                                icon = Icons.Rounded.Mic,
                                idleFilled = true,
                                // 进入语音时本入口会被换成上面的停止钮（同一槽位形变）；形变期间
                                // 它仍以麦克风外观缩放淡出。若跟着 voiceState.active 换成挂断图标，
                                // 退场瞬间会先闪一下“躺平的电话”。点击行为不受影响。
                                swapIconWhenActive = false,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showScheduleDialog) {
        M3ScheduleSendDialog(
            onDismiss = { showScheduleDialog = false },
            onConfirm = { dueAtMillis ->
                showScheduleDialog = false
                ScheduledSendAlarm.schedule(context, conversationKey, draftText, dueAtMillis)
                scope.launch {
                    ComposerQueueStore.scheduleSend(context, conversationKey, draftText, dueAtMillis)
                }
                val label = java.text.DateFormat.getTimeInstance(
                    java.text.DateFormat.SHORT,
                ).format(java.util.Date(dueAtMillis))
                Toast.makeText(
                    context,
                    context.getString(R.string.composer_scheduled_toast, label),
                    Toast.LENGTH_SHORT,
                ).show()
            },
        )
    }

    M3ComposerPickerSheet(
        // 语音浮层不显示选择按钮，弹窗也一并屏蔽（点不到入口，这里只是兜底）。
        show = showPickerSheet && showModelPicker,
        onDismiss = { showPickerSheet = false },
        assistantId = assistantId,
        onEditAssistant = onEditAssistant,
        onOpenModelProviders = onOpenModelProviders,
        onOpenAssistants = onOpenAssistants,
        onAssistantSelected = onAssistantSelected,
        modelPickerState = modelPickerState,
        isStreaming = isStreaming,
        isPaused = isPaused,
        isCompressingContext = isCompressingContext,
        onModelSelected = onModelSelected,
        usage = usage,
        contextSendBlocked = contextSendBlocked,
        popupMaxHeight = popupMaxHeight,
        reasoningEffort = reasoningEffort,
        availableReasoningEfforts = availableReasoningEfforts,
        gptSpeedMode = gptSpeedMode,
        gptSpeedSupported = gptSpeedSupported,
        onReasoningEffortChange = onReasoningEffortChange,
        onCycleGptSpeedMode = onCycleGptSpeedMode,
    )
    }

    // 状态切换的规格必须在 Composable 作用域取一次：transitionSpec 不是 @Composable。
    val composerMotionScheme = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = remoteComposerStatus,
        transitionSpec = {
            (fadeIn(composerMotionScheme.defaultEffectsSpec()) togetherWith
                fadeOut(composerMotionScheme.fastEffectsSpec()))
                .using(SizeTransform(clip = false))
        },
        label = "composerStatus",
    ) { status ->
        if (status != null) {
            RemoteStatusComposerPill(
                status = status,
                onPositionedTopPx = onPositionedTopPx,
                onToolbarHeightPx = onToolbarHeightPx,
            )
        } else {
            DraftComposerBody()
        }
    }
}

/**
 * 胶囊尾部按钮的三种形态：语音入口（`Call`）⇄ 发送 / 停止 / 继续 ⇄ 语音进行中的停止。
 *
 * 三者共用同一枚叠放在胶囊右下角的按钮槽位，切换走同一条官方 Expressive 弹簧的
 * 缩放 + 淡入淡出形变——这样“通话按钮 → 发送按钮”（输入文字）与“通话按钮 → 停止按钮”
 * （进入语音）就是同一套动效，而不是让整块胶囊各自淡入淡出。
 */
private enum class ComposerTrailing { VOICE_ENTRY, SEND, VOICE_STOP }

/** 胶囊左下角起始侧槽的三种状态：空 / 编辑态取消 / 生成中加载。同一位置互斥，编辑态优先。 */
private enum class ComposerLeading { NONE, CANCEL, LOADING }

/**
 * 断线期间的状态胶囊（远程重连 / 失败）：与正常输入框外层**同一外形**——
 * [ComposerOuterCornerRadius] 圆角、直径（72dp）高——整颗换成状态色（重连中
 * secondaryContainer、失败 errorContainer）。提示语占输入框的位置；两端圆位同径（40dp）：
 * 重连中放官方 ContainedLoadingIndicator（圆容器转圈），失败换重试圆钮、
 * 左端盖给「服务器设置」入口。
 *
 * 高度不能用官方 `FloatingToolbarDefaults.ContainerSize`（64dp）：正常态外层是
 * 56dp 内层输入框 + 上下各 8dp 内容内边距 = 72dp，取 64dp 会让整块矮 8dp、
 * 36dp 圆角被短边裁到 32dp，切换时输入栏占位跳一下、圆位也跟着横移 4dp。
 *
 * 圆位的同心规则与正常输入框**不同**：正常态是双层（外层 36dp 圆角 + 8dp 内边距 →
 * 内层 28dp 输入框），40dp 圆钮在内层里按 [ComposerConcentricGap]（28 − 20 = 8dp）内缩，
 * 圆心距最外层端盖 8 + 8 + 20 = 36dp。本胶囊是单层，没有那一层内边距，
 * 端距必须是 36 − 20 = 16dp；照搬内层的 8dp 会让圆心只距端盖 28dp。
 * 圆位垂直居中：圆心距上下左右端盖都是 36dp，四个圆角同时同心、左右两枚圆位同基线，
 * 重试 / 转圈圆钮与正常态发送圆钮落在同一个绝对位置。
 *
 * 测量回调（[onPositionedTopPx] / [onToolbarHeightPx]）照常上报，布局消费方不感知这次替换。
 */
@Composable
private fun RemoteStatusComposerPill(
    status: RemoteComposerStatus,
    onPositionedTopPx: (Int) -> Unit,
    onToolbarHeightPx: (Int) -> Unit,
) {
    val failed = status.status is RemoteConnectionStatus.Failed
    val title: String
    val reason: String?
    when (val s = status.status) {
        is RemoteConnectionStatus.Reconnecting -> {
            title = stringResource(R.string.remote_banner_reconnecting, status.serverName, s.attempt)
            reason = s.kind?.let { remoteFailureText(it, s.detail) }
        }
        is RemoteConnectionStatus.Failed -> {
            title = stringResource(R.string.remote_banner_failed, status.serverName)
            reason = remoteFailureText(s.kind, s.detail)
        }
        // 供应处已按重连/失败过滤；真到达这里就别渲染状态胶囊（防御性空退出）。
        else -> return
    }
    // 与正常输入框外层同形：72dp 高、36dp 圆角（不用官方 ContainerSize = 64dp，
    // 那会把圆角裁到 32dp 并让整块矮 8dp）。
    val pillHeight = ComposerOuterCornerRadius * 2
    val buttonSize = IconButtonDefaults.smallContainerSize().width
    val pillRadius = minOf(ComposerOuterCornerRadius, pillHeight / 2f)
    // 端盖同心端距 = 36 − 20 = 16dp，与正常态发送圆钮相对最外层端盖的
    // 8dp 外框内边距 + 8dp 内层内缩 + 20dp 半径 = 36dp 完全同位。
    // 不能换成 ComposerConcentricGap：那是双层结构里「内层 28 − 20」的值，
    // 单层胶囊套用它就会少一层 8dp，圆钮贴到端盖外圈去。
    val concentricInset = pillRadius - buttonSize / 2f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { onPositionedTopPx(it.positionInWindow().y.toInt()) }
            .onSizeChanged { onToolbarHeightPx(it.height) },
        shape = RoundedCornerShape(ComposerOuterCornerRadius),
        color = if (failed) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (failed) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(pillHeight),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.width(concentricInset))
                if (failed) {
                    // 左端盖同心：40dp 图标钮 + 16dp 内缩，与右侧圆位同一套几何、同一条中线。
                    val settingsDescription = stringResource(R.string.remote_banner_settings)
                    EtaTooltip(text = settingsDescription) {
                        IconButton(
                            onClick = status.onOpenSettings,
                            modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = settingsDescription,
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // 尾部给 40dp 圆位 + 同心端距 + 4dp 文字间隙让位（16 + 40 + 4 = 60dp）。
                        // 圆钮左缘距右端 16 + 40 = 56dp，文字右缘再退 4dp 才不贴上去。
                        .padding(end = concentricInset + buttonSize + ComposerToolbarGap),
                ) {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    reason?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // 状态胶囊尾部同心圆位：与左端盖的设置图标共用垂直中线（CenterEnd），
            // 端距按 36dp 圆角算 = 16dp。原先用 BottomEnd + 8dp 贴底：上留白 16dp /
            // 下留白 8dp，按钮明显偏下、与左端盖错开基线，圆心也够不到端盖弧心。
            // 重连中的转圈指示器与重试钮共用这个槽，切换状态时圆位不跳动。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = concentricInset),
            ) {
                if (failed) {
                    val retryDescription = stringResource(R.string.remote_banner_retry)
                    EtaTooltip(text = retryDescription) {
                        FilledIconButton(
                            onClick = status.onRetry,
                            modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = retryDescription,
                            )
                        }
                    }
                } else {
                    // 圆容器色取 surfaceContainer（与输入框槽底色同档）、指示器 primary：
                    // 在状态色胶囊上读作「原来圆钮的位置在转圈」。
                    ContainedMorphLoadingIndicator(
                        indicatorSize = IconButtonDefaults.smallContainerSize().width,
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        indicatorColor = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 工具栏内图标按钮之间的间距（官方浮动工具栏的容器间隔令牌值，4dp）。 */
private val ComposerToolbarGap = 4.dp

/**
 * 外层工具栏胶囊与内层输入框胶囊的同心间距（= 官方浮动工具栏内容内边距 8dp）。
 *
 * 同心规则：内层圆角半径 = 外层圆角半径 − 间距。外层取固定 36dp（见 [ComposerOuterCornerRadius]）、
 * 内层取官方 `extraLarge` 28dp，单行时外层 72dp 高 ⇒ 36dp 恰为胶囊；多行长高后两者半径不变，
 * 依旧满足 36 − 28 = 8dp；尾部与起始侧的 40dp 圆钮同理都需要 8dp 端距。
 */
private val ComposerConcentricGap = 8.dp

/** 外层浮动工具栏的固定圆角半径：内层 28dp（官方 extraLarge）+ 同心间距 8dp。 */
private val ComposerOuterCornerRadius = 36.dp

/**
 * 空输入框的提示语：名字跟着「当前这个会话到底是谁在干活」走。
 *
 * 本机会话取当前助手的名字（默认助手就叫 Eta，所以默认长相不变）；远程会话取用户在
 * 远程智能体配置里起的名字——那里的助手由服务器固定，本机既改不了也换不了，
 * 提示语再写死 Eta 就等于在跟用户说这个会话归 Eta 管，与事实不符。
 *
 * [remoteAgentName] 为 `null` 表示本机；空串表示「在远程上但没起名字」，
 * 此时退回不带名字的通用文案，而不是拿本机助手名顶替（那会指错人）。
 */
@Composable
private fun composerHintText(
    isStreaming: Boolean,
    remoteAgentName: String?,
    assistantName: String,
): String {
    val displayName = (remoteAgentName ?: assistantName).trim()
    return when {
        isStreaming && displayName.isNotEmpty() ->
            stringResource(R.string.chat_eta_working_named, displayName)
        isStreaming -> stringResource(R.string.chat_eta_working)
        displayName.isNotEmpty() -> stringResource(R.string.chat_input_hint_named, displayName)
        else -> stringResource(R.string.chat_input_hint)
    }
}

/**
 * 胶囊输入框：官方 Filled 文本框（无 label，仅 placeholder，容器 `surfaceContainer`，
 * 指示线透明，圆角固定 28dp＝官方 `extraLarge`，不改大小）。
 * 生成中的形变加载指示器叠在输入框左下角、语音／发送按钮叠在右下角，两枚 40dp 圆钮
 * 同基线、同端距，关于输入框两端对称；本体都在共享容器里，不放进官方图标槽。
 *
 * 圆钮位置：单行（56dp）时贴底与竖直居中重合，端距都是 (56 − 40)/2 = 8dp；
 * 多行长高后两枚圆钮随 BottomStart / BottomEnd 一起下沉贴底，与内层 28dp 圆角保持同心
 * （40dp 圆形半径 20dp，28 − 20 = 8dp 即为端距）。文本不压到圆钮：左下有圆钮时起始侧
 * 由官方 leadingIcon 槽留 48dp 占位、尾部由 trailingIcon 槽预留同宽横向空间。
 */
@Composable
private fun M3ComposerTextField(
    textFieldState: TextFieldState,
    focusRequester: FocusRequester,
    isStreaming: Boolean,
    /** 胶囊左下角是否有 40dp 圆钮；有则起始侧留同宽占位，避免多行文字压到圆钮上。 */
    reserveLeadingSlot: Boolean,
    /** 本机会话当前助手的名字；远程会话由 `LocalRemoteAgentName` 覆盖它。 */
    assistantName: String,
) {
    TextField(
        state = textFieldState,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        textStyle = etaTextStyle(EtaType.Body),
        placeholder = {
            Text(
                text = composerHintText(
                    isStreaming = isStreaming,
                    remoteAgentName = io.github.mangi.eta.ui.remote.LocalRemoteAgentName.current,
                    assistantName = assistantName,
                ),
                style = etaTextStyle(EtaType.Body),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        lineLimits = TextFieldLineLimits.MultiLine(
            minHeightInLines = 1,
            maxHeightInLines = 6,
        ),
        // 圆角固定为官方 extraLarge（28dp），单行 56dp 高即胶囊；多行时是圆角矩形，
        // 与外层固定 36dp 仍满足“半径差 = 间距”（36 − 28 = 8dp）。
        shape = MaterialTheme.shapes.extraLarge,
        // 胶囊左下角的共享槽（composer_leading）里放的是编辑态取消钮或生成中加载指示器，
        // 与右下发送 / 停止圆钮同一条基线、同一套同心端距。这里只在官方 leadingIcon 槽留
        // 同宽占位：圆钮是「8dp 同心间距 + 40dp 圆钮」＝48dp，正好等于官方槽的
        // minimumInteractiveComponentSize；占位保持整列宽度不变，多行文本的每一行都不会
        // 伸进左下角圆钮的区域，文字起始位置也与改动前完全一致。
        leadingIcon = if (reserveLeadingSlot) {
            {
                Spacer(
                    modifier = Modifier.width(
                        ComposerConcentricGap + IconButtonDefaults.smallContainerSize().width,
                    ),
                )
            }
        } else {
            null
        },
        // 尾部槽仅预留与按钮同宽的横向空间（48dp 触控区由官方槽自身保证），
        // 按钮本体在共享容器里以右下角对齐叠放（见 composer_trailing），多行长高时贴住右下角。
        trailingIcon = {
            Spacer(modifier = Modifier.size(IconButtonDefaults.smallContainerSize()))
        },
        colors = TextFieldDefaults.colors(
            // 内层胶囊取官方 surfaceContainer：与外层 primaryContainer 工具栏形成两层表面。
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            // 参考稿没有描边，指示线取官方透明值。
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * 语音进行中的“聆听条”（参考稿第二图）：vibrant 外胶囊里的单枚内胶囊——
 * 起始侧麦克风状态圆钮（指示，非按钮）、中间实时转写与阶段文案。
 *
 * 几何与正常态一致：内胶囊固定 28dp 圆角、56dp 最小高；麦克风圆钮（半径 20dp）距端盖
 * 8dp（28 − 20 的同心间距）。听写时中间显示实时转写，无转写时显示阶段文案；
 * 通话模式图标为 GraphicEq，其余为 RecordVoiceOver。结束后由调用方切回正常输入区。
 *
 * 末尾的停止圆钮**不在这里**：它由胶囊右下角的共享尾部槽位（见 composer_trailing）提供，
 * 从而能与“输入文字 Call→Send”走同一条弹簧形变。这里只预留出同宽的尾部内边距。
 */
@Composable
private fun M3VoiceBar(
    state: VoiceModeState,
    /**
     * 已识别但尚未提交的转写被点按时的接管动作：调用方负责停掉语音、把转写原样放进
     * 输入框并把键盘焦点交还用户继续编辑。传 null 时转写不可点按。
     */
    onTranscriptTap: (() -> Unit)? = null,
) {
    val view = LocalView.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TextFieldDefaults.MinHeight)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = ComposerConcentricGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 起始侧麦克风状态圆钮：正常聆听用主题 primary 角色；仅在报错场景（如麦克风被占用）
        // 才切到官方 M3 的 error 角色对（`error` / `onError`，红底白图标）——全项目的“红”
        // 都走这一对令牌，随深浅色主题自适应。颜色随 error 状态以动画过渡更自然，故用
        // animateColorAsState 包一层。
        val micCircleColor by animateColorAsState(
            targetValue = if (state.error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "voice_mic_circle",
        )
        val micIconColor by animateColorAsState(
            targetValue = if (state.error != null) {
                MaterialTheme.colorScheme.onError
            } else {
                MaterialTheme.colorScheme.onPrimary
            },
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            label = "voice_mic_icon",
        )
        Surface(
            shape = CircleShape,
            color = micCircleColor,
            modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (state.mode == VoiceEntryMode.DOUBAO_DUPLEX) {
                        Icons.Rounded.GraphicEq
                    } else {
                        Icons.Rounded.RecordVoiceOver
                    },
                    contentDescription = null,
                    modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                    tint = micIconColor,
                )
            }
        }
        val textColor = if (state.error != null) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        }
        val transcript = state.transcript.takeIf { it.isNotBlank() }
        if (transcript != null) {
            // 转写本身可点按：识别出文字但还没提交时，一点就把这段话交给键盘接着改。
            // 语音未识别出内容时（只有阶段文案）没有可编辑的东西，不做成可点。
            VoiceLiveTranscript(
                text = transcript,
                color = textColor,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
                    .then(
                        if (onTranscriptTap != null) {
                            Modifier.clickable {
                                TouchHaptics.click(view)
                                onTranscriptTap()
                            }
                        } else {
                            Modifier
                        },
                    ),
            )
        } else {
            Text(
                text = voicePhaseText(state),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                style = etaTextStyle(EtaType.Body),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 为共享的停止圆钮预留同宽空间（圆钮本体在 composer_trailing 里叠放在右下角）。
        Spacer(modifier = Modifier.size(IconButtonDefaults.smallContainerSize()))
    }
}

/**
 * 语音对话的单行实时转写。
 *
 * 识别结果持续改写时，文本本身会越来越长；这里不换行也不省略，而是让可视窗口平滑地
 * 追到最新转写的尾部——用户始终能看到自己最后说的内容。滚动距离按固定速度换算时长
 * （约 1000dp/s），连续分片到达时会不断接续新的目标，而不是每次瞬移到末尾；转写被
 * 清空（发送后切到思考 / 播报阶段）时本组件退出组合，下一轮从起点重新开始。
 *
 * 视口固定跟着尾部走，于是**左端**永远是被视口切掉一半的旧文字——转写越长，左边被
 * 「挤走」得越明显，右端却始终是刚说出的新内容。这里只在左端挂一条 DstIn 淡出带
 * （与语音助手气泡的屏幕上下文 chip 行同一套做法，见 `ScrollEdgeFade.kt`）：
 * 蒙版削的是文字自身的 alpha，底下露出的自然是胶囊底色。
 * 右端不能淡——那里是用户正要读的当前识别结果，淡掉反而看不清。
 */
@Composable
private fun VoiceLiveTranscript(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.maxValue }
            .distinctUntilChanged()
            .collectLatest { end ->
                val distance = abs(end - scroll.value)
                if (distance == 0) return@collectLatest
                val duration = with(density) { distance.toDp().value.toInt() }.coerceIn(80, 360)
                scroll.animateScrollTo(
                    value = end,
                    animationSpec = tween(durationMillis = duration, easing = LinearEasing),
                )
            }
    }
    Text(
        text = text.replace('\n', ' '),
        modifier = modifier
            // 淡出带必须挂在 horizontalScroll 之前（外层），蒙版坐标系才是视口坐标。
            // 只淡左端：右端是刚说出的内容，必须完整可读。
            .horizontalScrollEdgeFade(
                scrollState = scroll,
                fadeWidth = VoiceTranscriptEdgeFadeWidth,
                edges = ScrollEdgeFadeEdges.Start,
            )
            .horizontalScroll(scroll)
            .testTag("voice-live-transcript"),
        style = etaTextStyle(EtaType.Body),
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

/**
 * 语音实时转写左端的淡出带宽度。
 *
 * 比通用默认（24dp）窄：这里淡掉的是**半个字**——转写单行、字号是 Body，
 * 24dp 会连着吃掉一整字多一点，看着像少了字；12dp 刚好把被切一半的那字化开。
 */
private val VoiceTranscriptEdgeFadeWidth = 12.dp

/**
 * 语音进行中的停止圆钮：参考稿的粉色圆钮取官方 `tertiaryContainer` / `onTertiaryContainer` 角色。
 *
 * 与尾部其它形态（Call / 发送）同尺寸同形状，由 composer_trailing 槽位负责切换动效，
 * 因此这里只画按钮本身，不带任何进出场动画。
 */
@Composable
private fun M3VoiceStopButton(onStop: () -> Unit) {
    val view = LocalView.current
    val stopDescription = stringResource(R.string.voice_mode_stop)
    EtaTooltip(text = stopDescription) {
        FilledTonalIconButton(
            onClick = {
                TouchHaptics.click(view)
                onStop()
            },
            modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Rounded.Stop,
                contentDescription = stopDescription,
                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
            )
        }
    }
}

/** 语音阶段文案（自 VoiceModeStatusPanel 提取，供聆听条复用同一套措辞）。 */
@Composable
private fun voicePhaseText(state: VoiceModeState): String = when {
    state.error != null -> state.error
    state.phase == VoiceModePhase.Connecting -> stringResource(R.string.voice_mode_connecting)
    state.phase == VoiceModePhase.Listening -> stringResource(R.string.voice_mode_listening)
    state.phase == VoiceModePhase.Transcribing -> stringResource(R.string.voice_mode_transcribing)
    state.phase == VoiceModePhase.Thinking -> stringResource(R.string.voice_mode_thinking)
    state.phase == VoiceModePhase.Speaking -> stringResource(R.string.voice_mode_speaking)
    else -> stringResource(R.string.voice_mode_open)
}

/**
 * 编辑态下的“取消编辑”按钮：位于胶囊左下角的共享起始侧槽，与右下发送按钮对称同心，官方
 * 色调图标按钮（尺寸/图标/水波纹/按压形变均为官方默认），容器色取官方
 * `tertiaryContainer` / `onTertiaryContainer` 角色（覆盖官方 tonal 默认的 secondaryContainer）。
 *
 * 位置由调用方的 `Alignment.BottomStart` + 8dp 端距决定，[modifier] 只用于挂测试标签等
 * 附加修饰：不要再往里传 `align`——本按钮外面包着 [EtaTooltip]，parentData 会落在
 * Tooltip 的内部布局上，对上层 Row / Box 不生效，按钮会停在左上角。
 */
@Composable
private fun M3ComposerCancelButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val cancelDescription = stringResource(R.string.ui_cancel_edit_c698df)
    EtaTooltip(text = cancelDescription) {
        FilledTonalIconButton(
            onClick = {
                TouchHaptics.click(view)
                onClick()
            },
            modifier = modifier.size(IconButtonDefaults.smallContainerSize()),
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = cancelDescription,
                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
            )
        }
    }
}

/**
 * 「+」主操作按钮：无底板的标准图标（只有图标、按下无涟漪 / 色块），展开附件与发送选项菜单。
 * 点击展开菜单：定时发送 / 存草稿 / 分享，以及拍照 / 视频 / 文件 / 文件夹 / 输入路径 /
 * 去搜索（媒体语义搜索）等附件项（附件动作由共享的 `rememberAgentAttachmentActions` 提供，
 * 去搜索由 [onOpenMediaSearch] 导航）。
 *
 * 菜单展开时「+」同时旋转 45° 成为「×」（关闭语义），收起时转回，旋转取官方 Expressive
 * 弹簧规格；图标本身是同一个 `Icons.Rounded.Add`，旋转由 `graphicsLayer` 承担。
 *
 * 菜单用 Material 3 Expressive 写法：官方 `DropdownMenuPopup` 承载弹层，整张菜单只用一个
 * 官方分组，按「对话操作」「附件来源」分两段、中间用 [EtaMenuDivider] 分开；
 * 行形状仍取官方 `MenuDefaults` 令牌。
 */
@Composable
private fun M3ComposerAddButton(
    actions: AgentAttachmentActions,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onScheduleSend: () -> Unit,
    onSaveDraft: () -> Unit,
    onShare: () -> Unit,
    onOpenMediaSearch: (() -> Unit)? = null,
) {
    // 展开态把「+」转到 45°：视觉上成为「×」，与菜单“关闭”动作一致。
    val iconRotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "composer_add_icon_rotation",
    )
    val view = LocalView.current
    val context = LocalContext.current
    // 菜单分两段：对话操作 / 附件来源，中间用分割线。条目用「文案资源 + 前置图标 + 动作」描述，
    // 渲染时行形状按官方 `MenuDefaults` 令牌取（首项 leading、末项 trailing）。
    val conversationEntries = listOf(
        ComposerMenuEntry(R.string.composer_schedule_send, Icons.Rounded.Schedule, onScheduleSend),
        ComposerMenuEntry(R.string.composer_save_draft, Icons.Rounded.Save, onSaveDraft),
        ComposerMenuEntry(R.string.composer_share, Icons.Rounded.Share, onShare),
    )
    val attachmentEntries = buildList {
        add(ComposerMenuEntry(R.string.attachment_take_photo, Icons.Rounded.PhotoCamera, actions.takePhoto))
        add(ComposerMenuEntry(R.string.attachment_video, Icons.Rounded.Videocam, actions.pickVideo))
        add(ComposerMenuEntry(R.string.attachment_file, Icons.Rounded.Description, actions.pickFiles))
        add(ComposerMenuEntry(R.string.attachment_folder, Icons.Rounded.FolderOpen, actions.pickFolder))
        add(ComposerMenuEntry(R.string.attachment_enter_path, Icons.Rounded.Link, actions.inputPath))
        // 「去搜索」：媒体语义搜索页挑本机图片/视频/文件，多选后添加到当前会话输入区；
        // 入口浮层没有导航去处（null），不渲染该行。
        onOpenMediaSearch?.let { handler ->
            add(ComposerMenuEntry(R.string.media_action_open_search, Icons.Rounded.ImageSearch, handler))
        }
    }
    Box {
        // 这里刻意不走官方 `IconButton`（它带 `ripple` 水波纹，按下会浮出一层高亮），
        // 也不用任何容器色：改为无底板的圆形点击区 + `clickable(indication = null)`，
        // 按下既没有涟漪也没有色块，只剩图标本身的 45° 旋转反馈。
        Box(
            modifier = Modifier
                .size(IconButtonDefaults.smallContainerSize())
                .clip(CircleShape)
                .focusProperties { canFocus = false }
                .semantics {
                    role = Role.Button
                    contentDescription = context.getString(R.string.ui_add_attachment_dba9e8)
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    TouchHaptics.click(view)
                    onExpandedChange(!expanded)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier
                    .size(IconButtonDefaults.smallIconSize)
                    .graphicsLayer { rotationZ = iconRotation },
            )
        }
        EtaMenuPopup(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            // 整张菜单共用一个圆角容器：对话操作与附件来源之间用分割线分开。
            // 行形状按整张菜单的全局位置取（首行 leading、末行 trailing），
            // 分割线不重新起一段，末行的按下高亮才会和容器底角同心。
            val itemCount = conversationEntries.size + attachmentEntries.size
            EtaMenuGroup(index = 0, count = 1) {
                conversationEntries.forEachIndexed { index, entry ->
                    DropdownMenuItem(
                        text = { Text(stringResource(entry.labelRes)) },
                        leadingIcon = { Icon(entry.icon, contentDescription = null) },
                        shape = EtaMenuItemShape(index, itemCount),
                        onClick = {
                            onExpandedChange(false)
                            entry.action()
                        },
                    )
                }
                EtaMenuDivider()
                attachmentEntries.forEachIndexed { index, entry ->
                    DropdownMenuItem(
                        text = { Text(stringResource(entry.labelRes)) },
                        leadingIcon = { Icon(entry.icon, contentDescription = null) },
                        shape = EtaMenuItemShape(conversationEntries.size + index, itemCount),
                        onClick = {
                            onExpandedChange(false)
                            entry.action()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 图库按钮：官方 Standard 图标按钮，点击直接打开系统图片选择器（插入图片）。
 *
 * 图标用 `Icons.Rounded.PhotoLibrary`（图库语义）：入口是从图库选图，不是拍照。
 */
@Composable
private fun M3ComposerPickImageButton(actions: AgentAttachmentActions) {
    ChatInputNonFocusableIconButton(
        onClick = { actions.pickImage() },
        contentDescription = stringResource(R.string.attachment_image),
        containerSize = IconButtonDefaults.smallContainerSize().width,
    ) {
        Icon(
            imageVector = Icons.Rounded.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
}

/**
 * 「助手与模型」二合一入口：官方 Standard 图标按钮，点击打开合并选择弹窗
 * （助手 / 模型两个页签，底部为思考档位与上下文用量）。
 *
 * 长按进入设置页的子代理配置页（预设组列表 / 预设详情）：子代理配置只保留设置页
 * 一套页面，输入栏不再单独承载一套协作页。
 */
@Composable
private fun M3ComposerPickerButton(
    enabled: Boolean,
    onClick: () -> Unit,
    onOpenSubAgentSettings: () -> Unit,
) {
    ChatInputNonFocusableIconButton(
        onClick = onClick,
        contentDescription = stringResource(R.string.composer_picker_title),
        onLongClick = onOpenSubAgentSettings,
        enabled = enabled,
        containerSize = IconButtonDefaults.smallContainerSize().width,
    ) {
        Icon(
            imageVector = Icons.Rounded.Layers,
            contentDescription = null,
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
}

/** 「+」菜单条目：文案资源 + 前置图标 + 点击动作。 */
private data class ComposerMenuEntry(
    val labelRes: Int,
    val icon: ImageVector,
    val action: () -> Unit,
)

/** 发送按钮按下时的绘制缩放：只收视觉，不缩触控区。 */
private const val ComposerSendPressScale = 0.9f

/**
 * 发送 / 停止 / 继续 按钮：按状态直接选用官方图标按钮
 * （停止＝`FilledTonalIconButton`、发送/继续＝`FilledIconButton`、
 * 上下文已满＝`OutlinedIconButton`、空闲＝禁用的 `IconButton`）。
 *
 * 形状、容器色、内容色、水波纹与按压形变全部使用各组件的官方默认值，不传 `shape` / `colors`。
 * 官方图标按钮没有长按槽位（M3 仅 `ListItem` 暴露 `onLongClick`），因此“长按中止暂停中的运行”
 * 通过官方 `Modifier.semantics` 的 `onLongClick` 动作提供。
 */
@Composable
private fun M3ComposerSendButton(
    sendMode: String,
    isPaused: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit,
    onAbortPausedRun: () -> Unit,
    onBlocked: () -> Unit,
) {
    val view = LocalView.current
    val description = when (sendMode) {
        "stop" -> stringResource(R.string.chat_stop)
        "continue" -> stringResource(R.string.chat_continue)
        else -> stringResource(R.string.chat_send)
    }
    // 按压收缩：只叠一层绘制期缩放，交出去时有「按下去」的手感；
    // 形状、容器色、水波纹与按压形变仍取官方默认值。
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) ComposerSendPressScale else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "composer_send_press_scale",
    )
    val content: @Composable () -> Unit = {
        Icon(
            imageVector = when (sendMode) {
                "stop" -> Icons.Rounded.StopCircle
                "continue" -> Icons.Rounded.PlayArrow
                else -> Icons.AutoMirrored.Rounded.Send
            },
            contentDescription = description,
            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
        )
    }
    val click: () -> Unit = {
        TouchHaptics.click(view)
        when (sendMode) {
            "blocked" -> onBlocked()
            "stop" -> onStop()
            "continue" -> onContinue()
            "send" -> onSend()
        }
    }
    val abortModifier = if (sendMode == "stop" || (sendMode == "continue" && isPaused)) {
        Modifier.semantics {
            onLongClick(label = description) {
                TouchHaptics.longPress(view)
                onAbortPausedRun()
                true
            }
        }
    } else {
        Modifier
    }
    val modifier = Modifier
        .size(IconButtonDefaults.smallContainerSize())
        .graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
        }
        .then(abortModifier)

    when (sendMode) {
        "stop" -> FilledTonalIconButton(
            onClick = click,
            modifier = modifier,
            interactionSource = interactionSource,
            content = content,
        )
        "continue", "send" -> FilledIconButton(
            onClick = click,
            modifier = modifier,
            interactionSource = interactionSource,
            content = content,
        )
        "blocked" -> OutlinedIconButton(
            onClick = click,
            modifier = modifier,
            interactionSource = interactionSource,
            content = content,
        )
        else -> IconButton(
            onClick = click,
            modifier = modifier,
            enabled = false,
            interactionSource = interactionSource,
            content = content,
        )
    }
}

/**
 * 输入栏上方的排队消息胶囊：每条显示正文摘要。
 * 点击正文把该条合并回主输入框继续编辑；生成中点击闪电图标立即插话送进当前运行；
 * 点击关闭图标把该条直接丢弃。
 */
@Composable
private fun M3ComposerQueueStrip(
    items: List<QueuedMessageUi>,
    canSteerNow: Boolean,
    onSteer: (String) -> Unit,
    onRecall: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    AnimatedVisibility(
        visible = items.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("composer-queue-item"),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.content,
                            style = etaTextStyle(EtaType.Secondary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                // 点击正文 = 撤回队列并合并回输入框继续编辑。
                                .clickable {
                                    TouchHaptics.click(view)
                                    onRecall(item.id)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                        if (canSteerNow) {
                            val steerDescription = stringResource(R.string.chat_queue_steer_now)
                            EtaTooltip(text = steerDescription) {
                                IconButton(
                                    onClick = {
                                        TouchHaptics.click(view)
                                        onSteer(item.id)
                                    },
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .size(IconButtonDefaults.smallContainerSize()),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Bolt,
                                        contentDescription = steerDescription,
                                        modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                                    )
                                }
                            }
                        }
                        val removeDescription = stringResource(R.string.chat_queue_remove)
                        EtaTooltip(text = removeDescription) {
                            IconButton(
                                onClick = {
                                    TouchHaptics.click(view)
                                    onRemove(item.id)
                                },
                                modifier = Modifier
                                    .padding(end = 4.dp)
                                    .size(IconButtonDefaults.smallContainerSize()),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = removeDescription,
                                    modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 选择发送时间的对话框（官方 `AlertDialog` + `TextButton` + `TimePicker`）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun M3ScheduleSendDialog(
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val now = remember { LocalTime.now() }
    val timeState = rememberTimePickerState(
        initialHour = now.hour,
        initialMinute = now.minute,
        is24Hour = true,
    )
    EtaDialog(
        show = true,
        title = stringResource(R.string.composer_schedule_title),
        onDismissRequest = onDismiss,
        content = { TimePicker(state = timeState) },
        actions = {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_confirm),
                onCancel = onDismiss,
                onConfirm = {
                    val calendar = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, timeState.hour)
                        set(Calendar.MINUTE, timeState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                        if (timeInMillis <= System.currentTimeMillis()) {
                            add(Calendar.DAY_OF_MONTH, 1)
                        }
                    }
                    onConfirm(calendar.timeInMillis)
                },
            )
        },
    )
}

/** 通过系统分享面板分享当前草稿。 */
private fun shareDraft(context: Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, null))
    }
}
