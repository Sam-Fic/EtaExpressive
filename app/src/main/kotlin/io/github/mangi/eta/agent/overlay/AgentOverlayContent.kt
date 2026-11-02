package io.github.mangi.eta.agent.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import android.view.MotionEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick as semanticsClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.components.verticalScrollEdgeFade

// 无语义 success 色，沿用项目既有值；失败色走主题 error
private val SuccessColor = Color(0xFF34C759)

private const val SupplementExitDelayMs = 380L

@Composable
internal fun phaseAccent(phase: AgentOverlayPhase): Color = when (phase) {
    AgentOverlayPhase.RUNNING -> MaterialTheme.colorScheme.primary
    AgentOverlayPhase.PAUSED -> Color(0xFFFF9F0A)
    AgentOverlayPhase.FINISHED -> SuccessColor
    AgentOverlayPhase.FAILED -> MaterialTheme.colorScheme.error
}

/**
 * 助手光球窗口：默认显示在屏幕右侧中下，可拖动；点击展开/收起小气泡。
 * 独立小窗口（WRAP_CONTENT），不遮挡页面操作。
 */
@Composable
internal fun AgentOverlayOrb(
    phase: AgentOverlayPhase,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val clickLabel = stringResource(if (collapsed) R.string.overlay_expand else R.string.overlay_collapse)
    val orbLabel = stringResource(R.string.app_name)

    LaunchedEffect(Unit) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        // 光球是浮层里的「英雄时刻」：缩放走 M3E Spatial（轻微过冲回弹），
        // 透明度走 Effects（临界阻尼、无过冲）。
        enter = scaleIn(
            initialScale = 0.5f,
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        ) + fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = scaleOut(
            targetScale = 0.5f,
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        ) + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
    ) {
        // 点击直接交给 Service 侧 toggle，不在 Compose 协程作用域里做延迟动作，
        // 避免 scope 取消导致浮层残留。
        AssistantOrb(
            phase = phase,
            clickLabel = clickLabel,
            orbLabel = orbLabel,
            onClick = onToggleCollapse,
            onDragStart = onDragStart,
            onDrag = onDrag,
        )
    }
}

/**
 * 助手光球：外层径向光晕 + 实心球体 + 高光点。
 * 运行中光晕呼吸，暂停/完成/失败静止，颜色随阶段变化。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AssistantOrb(
    phase: AgentOverlayPhase,
    modifier: Modifier = Modifier,
    clickLabel: String,
    orbLabel: String,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
) {
    // 阶段切换时颜色渐渡，避免光球在一帧内换色。
    val accent by animateColorAsState(phaseAccent(phase), tween(240), label = "orb_accent")
    // Read pulse only during drawing; 20 Hz is enough for a 2.8-second breathing cycle.
    val pulse = rememberAgentOrbPulse(phase)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val gesture = remember(touchSlop) { AgentOrbDragGesture(touchSlop) }
    val activationKey = remember { AgentOrbActivationKey() }
    // Screen coordinates stay stable while WindowManager moves this small window.
    // One recognizer owns the physical stream: dragging never toggles the controls.
    val tapModifier = Modifier
        .semantics {
            role = Role.Button
            contentDescription = orbLabel
            semanticsClick(label = clickLabel) { onClick(); true }
        }
        .onKeyEvent { event ->
            if (event.key == Key.Enter || event.key == Key.NumPadEnter ||
                event.key == Key.DirectionCenter || event.key == Key.Spacebar
            ) {
                when (event.type) {
                    KeyEventType.KeyDown -> activationKey.down(event.key.hashCode())
                    KeyEventType.KeyUp -> if (activationKey.up(event.key.hashCode())) onClick()
                    else -> Unit
                }
                true
            } else false
        }
        .onFocusChanged { if (!it.isFocused) activationKey.cancel() }
        .focusable()
        .pointerInteropFilter { event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gesture.begin(event.getPointerId(0), event.rawX, event.rawY)
                    onDragStart()
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    if (event.pointerCount != 1) gesture.cancel()
                    else {
                        gesture.move(event.getPointerId(0), event.rawX, event.rawY)?.let {
                            onDrag(it.x, it.y)
                        }
                        if (event.actionMasked == MotionEvent.ACTION_UP &&
                            gesture.finish(event.getPointerId(0))
                        ) onClick()
                    }
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_POINTER_UP -> gesture.cancel()
            }
            true
        }
    Box(
        modifier = modifier
            .then(tapModifier)
            .size(56.dp)
            .drawWithCache {
                val outer = size.minDimension
                val center = Offset(outer / 2f, outer / 2f)
                val ballRadius = outer * 0.3f
                // Brushes depend only on size/theme, not on each breathing frame.
                val halo = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.5f), Color.Transparent),
                    center = center,
                    radius = outer / 2f,
                )
                val ball = Brush.radialGradient(
                    colors = listOf(accent, accent.copy(alpha = 0.8f)),
                    center = Offset(center.x - ballRadius * 0.3f, center.y - ballRadius * 0.3f),
                    radius = ballRadius,
                )
                onDrawBehind {
                    drawCircle(brush = halo, alpha = pulse.value)
                    drawCircle(brush = ball, radius = ballRadius, center = center)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.55f),
                        radius = ballRadius * 0.3f,
                        center = Offset(center.x - ballRadius * 0.32f, center.y - ballRadius * 0.38f),
                    )
                }
            }
    )
}

/**
 * 运行时小气泡窗口：WRAP_CONTENT，跟随光球，窗口外触摸穿透。
 * 一句话状态 + 动作按钮 + 可展开补充输入。结束时由 Service 撤掉、改显结果卡片。
 */
@Composable
internal fun AgentOverlayBubble(
    state: AgentOverlayState,
    onCollapse: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSupplementModeChange: (Boolean) -> Unit,
    onSupplement: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        visible = true
    }

    var supplementMode by remember { mutableStateOf(false) }
    var supplementText by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun enterSupplementMode() {
        onSupplementModeChange(true)
        supplementMode = true
    }

    fun exitSupplementMode() {
        onSupplementModeChange(false)
        supplementMode = false
        supplementText = ""
    }

    fun closeSupplementMode() {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            delay(80)
            exitSupplementMode()
        }
    }

    fun submitSupplement() {
        val text = supplementText.trim()
        if (text.isBlank()) return
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        scope.launch {
            delay(80)
            exitSupplementMode()
            onSupplement(text)
        }
    }

    val accent = phaseAccent(state.phase)
    val statusText = state.status.localizedText()
    val statusColor by animateColorAsState(
        targetValue = if (state.phase == AgentOverlayPhase.RUNNING) accent else Color(0xFFFF9F0A),
        animationSpec = tween(240),
        label = "bubble_status_color",
    )

    AnimatedVisibility(
        visible = visible,
        // 气泡从光球一侧长出：缩放（Spatial，轻微过冲）+ 淡入（Effects，无过冲）。
        enter = scaleIn(
            initialScale = 0.5f,
            transformOrigin = TransformOrigin(1f, 0.5f),
            animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        ) + fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = scaleOut(
            targetScale = 0.5f,
            transformOrigin = TransformOrigin(1f, 0.5f),
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        ) + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 136.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                // 一句话状态
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    lineHeight = 16.sp,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(6.dp))

                AnimatedVisibility(
                    visible = supplementMode,
                    enter = expandVertically(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    ) + fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                    exit = shrinkVertically(
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    ) + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                ) {
                    SupplementInput(
                        value = supplementText,
                        onValueChange = { supplementText = it },
                        onCancel = ::closeSupplementMode,
                        onSend = ::submitSupplement,
                    )
                }

                AnimatedVisibility(
                    visible = !supplementMode,
                    enter = expandVertically(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                    ) + fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                    exit = shrinkVertically(
                        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    ) + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    ) {
                        IconButton(
                            onClick = ::enterSupplementMode,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = stringResource(R.string.overlay_supplement),
                                modifier = Modifier.size(EtaDimens.StatusIconSize),
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (state.phase == AgentOverlayPhase.RUNNING) {
                            IconButton(
                                onClick = onPause,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = stringResource(R.string.overlay_pause),
                                    modifier = Modifier.size(EtaDimens.StatusIconSize),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        } else if (state.phase == AgentOverlayPhase.PAUSED) {
                            IconButton(
                                onClick = onResume,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(R.string.overlay_resume),
                                    modifier = Modifier.size(EtaDimens.StatusIconSize),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        IconButton(
                            onClick = onStop,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Stop,
                                contentDescription = stringResource(R.string.action_stop),
                                modifier = Modifier.size(EtaDimens.StatusIconSize),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SupplementInput(
    value: String,
    onValueChange: (String) -> Unit,
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        delay(180)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm),
    ) {
        // 官方 M3 TextField：实色容器 + 无指示线；圆角取官方 extraLarge（28dp），
        // 与胶囊外圈 36dp 保持 8dp 间距的同心关系。
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 112.dp)
                .focusRequester(focusRequester),
            shape = MaterialTheme.shapes.extraLarge,
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = {
                Text(
                    text = stringResource(R.string.overlay_supplement_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            maxLines = 4,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.action_cancel))
            }
            Spacer(modifier = Modifier.width(EtaDimens.SpaceSm))
            TextButton(onClick = onSend, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.overlay_send))
            }
        }
    }
}

/**
 * 结束时半屏结果卡片窗口：Markdown 渲染完整结果，可滚动，底部对齐。
 * 窗口本身已由 Service 定为半屏尺寸，此处填满窗口。
 */
@Composable
internal fun AgentResultCard(
    state: AgentOverlayState,
    onClose: () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    val isFailed = state.phase == AgentOverlayPhase.FAILED
    val dotColor = phaseAccent(state.phase)
    val statusText = state.status.localizedText()
    val statusLabel = stringResource(
        if (isFailed) R.string.overlay_substatus_failed else R.string.overlay_substatus_finished,
    )
    val content = state.detailText.ifBlank { statusText }
    val textColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
    ) {
        AnimatedVisibility(
            visible = visible,
            // 结果卡从底部滑入：位移走 M3E Spatial（轻微过冲），透明度走 Effects。
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            ) + fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            ) + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .shadow(8.dp, MaterialTheme.shapes.medium),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    // 状态行降级为圆点 + 灰色小字，关闭用幽灵图标，视觉重心留给内容
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        // 关闭直接交给 Service，不经 Compose 协程延迟
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.action_close),
                                modifier = Modifier.size(EtaDimens.StatusIconSize),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Markdown 结果，可滚动
                    val markdownState = rememberMarkdownState(content = content, retainState = true)
                    val typography = markdownTypography(
                        h1 = TextStyle(fontSize = MaterialTheme.typography.titleMedium.fontSize, fontWeight = FontWeight.Bold, color = textColor),
                        h2 = TextStyle(fontSize = MaterialTheme.typography.titleSmall.fontSize, fontWeight = FontWeight.Bold, color = textColor),
                        h3 = TextStyle(fontSize = MaterialTheme.typography.bodyLarge.fontSize, fontWeight = FontWeight.Bold, color = textColor),
                        text = TextStyle(fontSize = MaterialTheme.typography.bodyLarge.fontSize, lineHeight = 22.sp, color = textColor),
                        paragraph = TextStyle(fontSize = MaterialTheme.typography.bodyLarge.fontSize, lineHeight = 22.sp, color = textColor),
                        ordered = TextStyle(fontSize = MaterialTheme.typography.bodyLarge.fontSize, lineHeight = 22.sp, color = textColor),
                        bullet = TextStyle(fontSize = MaterialTheme.typography.bodyLarge.fontSize, lineHeight = 22.sp, color = textColor),
                        list = TextStyle(fontSize = MaterialTheme.typography.bodyLarge.fontSize, lineHeight = 22.sp, color = textColor),
                        code = TextStyle(
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            fontFamily = FontFamily.Monospace,
                            color = textColor,
                        ),
                    )
                    // 结果区限高 280dp 后纵向滚动：内容超出时被上下边缘硬切。
                    // 底部挂一条淡出带告诉用户「下面还有」，与工具详情、横向滚动截断同一套做法。
                    val resultScrollState = rememberScrollState()
                    Markdown(
                        markdownState = markdownState,
                        typography = typography,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            // 淡出带挂在 verticalScroll 之前（外层），蒙版坐标系才是视口坐标。
                            .verticalScrollEdgeFade(resultScrollState)
                            .verticalScroll(resultScrollState),
                        loading = {
                            Text(text = content, color = textColor, fontSize = MaterialTheme.typography.bodyLarge.fontSize, modifier = it)
                        },
                        error = {
                            Text(text = content, color = textColor, fontSize = MaterialTheme.typography.bodyLarge.fontSize, modifier = it)
                        },
                    )
                }
            }
        }
    }
}
