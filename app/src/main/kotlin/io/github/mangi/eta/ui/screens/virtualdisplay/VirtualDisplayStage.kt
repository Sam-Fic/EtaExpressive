package io.github.mangi.eta.ui.screens.virtualdisplay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaStatusDot
import io.github.mangi.eta.ui.components.ContainedMorphLoadingIndicator
import io.github.mangi.eta.ui.components.VirtualTouchFocusLayer
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 虚拟屏舞台：实时帧、状态浮标、空态/准备中/移交蒙层，以及接管后的点按/滑动注入。
 *
 * 触控焦点在原有水波纹之上叠加 Material 3 Expressive 的 `MaterialShapes` 奇异形状：
 * 点按、长按、滑动头各取固定调色板，序列号取模保证可复现；形状路径在组合期预计算，
 * 绘制阶段只做位移/缩放/旋转，避免 30fps 预览下每帧重建 Path。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun VirtualDisplayLiveStage(
    released: Boolean,
    handingOff: Boolean,
    onTakeoverChange: (Boolean) -> Unit,
    onTap: (x: Float, y: Float, longPress: Boolean) -> Unit,
    onSwipe: (x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by VirtualDisplayController.snapshots.collectAsStateWithLifecycle()
    val active = snapshot.active
    val frame = snapshot.frame
    val takeover = snapshot.userControlling && active
    val materializing = snapshot.materializingToMain || handingOff
    val appLabel = snapshot.appName.ifBlank { stringResource(R.string.vd_screen_unknown_app) }
    val stageDescription = stringResource(R.string.vd_screen_stage_description, appLabel)
    val takeoverHint = stringResource(R.string.vd_screen_stage_takeover_hint)
    val interactionSource = remember { MutableInteractionSource() }
    val view = LocalView.current
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    // 接管状态切换时画框描边颜色与粗细同段位渐渡，避免边界瞬跳。
    val frameBorderColor by animateColorAsState(
        targetValue = if (takeover) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(220),
        label = "vd_stage_border_color",
    )
    val frameBorderWidth by animateDpAsState(
        targetValue = if (takeover) 2.dp else 1.dp,
        animationSpec = tween(220),
        label = "vd_stage_border_width",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(EtaDimens.SpaceMd),
            contentAlignment = Alignment.Center,
        ) {
            // 实时画面、准备中、空态三态之间交叉淡化，首帧到达或副屏结束都不再硬切。
            val stageMode = when {
                active && frame != null -> StageMode.Live
                active -> StageMode.Preparing
                else -> StageMode.Empty
            }
            AnimatedContent(
                targetState = stageMode,
                contentAlignment = Alignment.Center,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(initialScale = 0.98f)) togetherWith
                        (fadeOut(tween(160)) + scaleOut(targetScale = 0.99f)) using
                        SizeTransform(clip = false) { _, _ -> tween(220) }
                },
                modifier = Modifier.fillMaxSize(),
                label = "vd_stage_mode",
            ) { mode ->
                if (mode == StageMode.Live) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val frameShape = MaterialTheme.shapes.large
                        val gestures = if (takeover) {
                            Modifier.pointerInput(snapshot.displayId, active) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val downTime = down.uptimeMillis
                                    var last = down.position
                                    var lastTime = downTime
                                    var travel = Offset.Zero
                                    var swipe = false
                                    var longPressed = false
                                    var upTime = downTime

                                    fun normalized(offset: Offset): Pair<Float, Float> {
                                        val width = size.width.coerceAtLeast(1)
                                        val height = size.height.coerceAtLeast(1)
                                        return (offset.x / width).coerceIn(0f, 1f) to
                                            (offset.y / height).coerceIn(0f, 1f)
                                    }

                                    down.consume()
                                    while (true) {
                                        val remaining = viewConfiguration.longPressTimeoutMillis - (lastTime - downTime)
                                        val event = if (!swipe && !longPressed && remaining > 0L) {
                                            withTimeoutOrNull(remaining) {
                                                awaitPointerEvent(PointerEventPass.Main)
                                            }
                                        } else {
                                            awaitPointerEvent(PointerEventPass.Main)
                                        }
                                        if (event == null) {
                                            // 手指静止到长按阈值：先注入长按，再继续吞掉本次手势直到抬起。
                                            longPressed = true
                                            val point = normalized(down.position)
                                            currentOnTap(point.first, point.second, true)
                                            while (true) {
                                                val rest = awaitPointerEvent(PointerEventPass.Main)
                                                val change = rest.changes.firstOrNull { it.id == down.id } ?: break
                                                change.consume()
                                                last = change.position
                                                lastTime = change.uptimeMillis
                                                if (!change.pressed) {
                                                    upTime = change.uptimeMillis
                                                    break
                                                }
                                            }
                                            break
                                        }
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        // Main pass 内消费移动：子节点先于父滚动容器，LazyColumn 不会抢纵向拖动。
                                        change.consume()
                                        last = change.position
                                        lastTime = change.uptimeMillis
                                        if (!change.pressed) {
                                            upTime = change.uptimeMillis
                                            break
                                        }
                                        travel += change.positionChange()
                                        if (!swipe && travel.getDistance() > viewConfiguration.touchSlop) {
                                            swipe = true
                                        }
                                    }
                                    if (swipe) {
                                        val start = normalized(down.position)
                                        val end = normalized(last)
                                        currentOnSwipe(
                                            start.first,
                                            start.second,
                                            end.first,
                                            end.second,
                                            (upTime - downTime).toInt().coerceIn(160, 1_500),
                                        )
                                    } else if (!longPressed) {
                                        val point = normalized(last)
                                        currentOnTap(point.first, point.second, false)
                                    }
                                }
                            }
                        } else {
                            // 未接管：只点一下夺取控制；不消费拖动，页面照常滚动。
                            Modifier.clickable(
                                enabled = active,
                                onClickLabel = takeoverHint,
                                indication = null,
                                interactionSource = interactionSource,
                            ) {
                                TouchHaptics.click(view)
                                onTakeoverChange(true)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxHeight(0.94f)
                                .aspectRatio(snapshot.aspectRatio, matchHeightConstraintsFirst = true)
                                .clip(frameShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .border(
                                    BorderStroke(
                                        width = frameBorderWidth,
                                        color = frameBorderColor,
                                    ),
                                    frameShape,
                                )
                                .then(gestures)
                                .semantics { contentDescription = stageDescription },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (frame != null) {
                                Image(
                                    bitmap = frame,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds,
                                )
                            }
                            VirtualTouchFocusLayer(
                                effect = snapshot.touchEffect,
                                modifier = Modifier.matchParentSize(),
                            )
                            AnimatedVisibility(
                                visible = materializing,
                                enter = fadeIn(tween(180)),
                                exit = fadeOut(tween(160)),
                            ) {
                                HandoffOverlay()
                            }
                        }

                        StatusPill(
                            text = listOf(
                                appLabel,
                                stringResource(
                                    if (snapshot.userControlling) {
                                        R.string.vd_screen_state_controlling
                                    } else {
                                        R.string.vd_screen_state_live
                                    },
                                ),
                            ).joinToString(" · "),
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = EtaDimens.SpaceSm),
                        )
                    }
                } else {
                    StagePlaceholder(
                        preparing = mode == StageMode.Preparing,
                        released = released && mode == StageMode.Empty,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** 舞台三态：Live 只在拿到首帧后出现，Preparing 为窗口就绪前，Empty 为未启动/已释放。 */
private enum class StageMode { Live, Preparing, Empty }

@Composable
private fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm),
        ) {
            EtaStatusDot(active = true)
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StagePlaceholder(
    preparing: Boolean,
    released: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(EtaDimens.SpaceLg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (preparing) {
            ContainedMorphLoadingIndicator()
            Spacer(Modifier.height(EtaDimens.SpaceMd))
            Text(
                text = stringResource(R.string.vd_screen_state_preparing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.ScreenshotMonitor,
                contentDescription = null,
                modifier = Modifier.size(EtaDimens.EmptyStateGraphicIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(EtaDimens.SpaceLg))
            Text(
                text = stringResource(
                    if (released) R.string.vd_screen_released_title else R.string.vd_screen_empty_title,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(EtaDimens.SpaceSm))
            Text(
                text = stringResource(
                    if (released) R.string.vd_screen_released_summary else R.string.vd_screen_empty_summary,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HandoffOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.86f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(EtaDimens.SpaceMd),
        ) {
            ContainedMorphLoadingIndicator()
            Text(
                text = stringResource(R.string.vd_screen_handoff_working),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}

