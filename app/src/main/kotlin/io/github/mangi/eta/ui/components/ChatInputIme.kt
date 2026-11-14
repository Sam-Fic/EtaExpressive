package io.github.mangi.eta.ui.components

import android.app.Activity
import android.view.View
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

internal val LocalChatInputFocusRequester = staticCompositionLocalOf<FocusRequester?> { null }

// Dynamic: only IME readers recompose when the drawer opens or closes, not the whole page.
internal val LocalConversationDrawerBlocksIme = compositionLocalOf { false }

internal fun hideChatInputIme(
    focusManager: FocusManager,
    keyboard: SoftwareKeyboardController?,
    view: View,
) {
    focusManager.clearFocus(force = true)
    keyboard?.hide()
    (view.context as? Activity)?.window?.let { window ->
        WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
    }
}

internal suspend fun showChatInputIme(
    requester: FocusRequester?,
    keyboard: SoftwareKeyboardController?,
    view: View,
) {
    repeat(3) {
        withFrameNanos { }
        runCatching { requester?.requestFocus() }
        keyboard?.show()
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.ime())
        }
        delay(48)
    }
}

/**
 * Chat action control that never takes text focus, so tapping it cannot dismiss the IME.
 *
 * Material 3 Expressive renders the official icon button family — [IconButton],
 * [FilledTonalIconButton] or [FilledIconButton]. Container shape, ripple, container
 * colors, disabled colors and sizes are all supplied by the library: [containerShape],
 * [containerColor] and [containerSize] are forwarded to the official component. When the
 * caller passes the official `secondaryContainer` / `primary` container color, the tonal /
 * filled variant is used so the official defaults apply untouched.
 *
 * **有意不传 `shapes`（M3E 按压 morph），设计如此**（用户 2026-11-14 确认）：聊天输入栏
 * 的图标钮走静态 `shape` 重载，按压不做圆→方圆角形变，保持输入栏工具行的视觉安静；
 * 这是 RULES §2.5 之外的一处明确豁免，审查时不要当成违规"修复"，后人也不要加回
 * `shapes = IconButtonDefaults.shapes()`。
 */
@Composable
internal fun ChatInputNonFocusableIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String = stringResource(R.string.cim_long_press_label),
    containerShape: Shape? = null,
    containerColor: Color? = null,
    contentColor: Color? = null,
    containerSize: Dp = ChatInputActionSize,
    connectedShapes: ToggleButtonShapes? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    // 没有长按时直接透传内容：长按宿主会多出一个布局节点和一个语义节点，
    // 而绝大多数输入栏按钮并不需要它。居中由官方组件自己的 Box 负责。
    val buttonContent: @Composable () -> Unit = if (onLongClick == null) {
        content
    } else {
        {
            ChatInputLongPressSlot(
                enabled = enabled,
                onLongPress = onLongClick,
                longClickLabel = longClickLabel,
                content = content,
            )
        }
    }
    val buttonModifier = modifier
        .size(containerSize)
        .focusProperties { canFocus = false }
        .semantics {
            this.role = Role.Button
            if (contentDescription != null) {
                this.contentDescription = contentDescription
            }
        }
    val click: () -> Unit = {
        TouchHaptics.click(view)
        onClick()
    }
    if (connectedShapes != null) {
        // 官方连体按钮组：M3 Expressive ToggleButton + ButtonGroupDefaults 连体形状组，
        // 自带官方按压形变动画。checked 恒为 false（菜单触发按钮没有选中态），
        // 点击经 onCheckedChange 回调转成一次 click。
        val tone = containerColor ?: MaterialTheme.colorScheme.secondaryContainer
        val toneContent = contentColor ?: MaterialTheme.colorScheme.onSecondaryContainer
        ToggleButton(
            checked = false,
            onCheckedChange = { click() },
            modifier = buttonModifier,
            enabled = enabled,
            buttonSize = ToggleButtonSize.Small,
            shapes = connectedShapes,
            // 连体组是固定 40dp 容器的图标按钮，官方文字按钮的默认水平内边距会挤压内容，
            // 置零后由官方 Row 自身居中。
            contentPadding = PaddingValues(0.dp),
            colors = ToggleButtonDefaults.colors(
                containerColor = tone,
                contentColor = toneContent,
                checkedContainerColor = tone,
                checkedContentColor = toneContent,
            ),
            content = { buttonContent() },
        )
        return
    }
    when {
        // Standard icon button: transparent container, official shape and ripple.
        containerColor == null -> IconButton(
            onClick = click,
            modifier = buttonModifier,
            enabled = enabled,
            shape = containerShape ?: IconButtonDefaults.standardShape,
            content = buttonContent,
        )
        // Official tonal default is secondaryContainer / onSecondaryContainer.
        containerColor == MaterialTheme.colorScheme.secondaryContainer -> FilledTonalIconButton(
            onClick = click,
            modifier = buttonModifier,
            enabled = enabled,
            shape = containerShape ?: IconButtonDefaults.filledShape,
            content = buttonContent,
        )
        // Official filled default is primary / onPrimary.
        containerColor == MaterialTheme.colorScheme.primary -> FilledIconButton(
            onClick = click,
            modifier = buttonModifier,
            enabled = enabled,
            shape = containerShape ?: IconButtonDefaults.filledShape,
            content = buttonContent,
        )
        // Any other container color still comes from the color scheme; only the
        // container / content slots are overridden, everything else stays official.
        // contentColor 透传，使调用方能随容器色一起驱动图标内容色（如输入框「+」按钮的
        // 透明 ↔ primary 渐变），未传时仍回落到官方 LocalContentColor 隐式值。
        else -> IconButton(
            onClick = click,
            modifier = buttonModifier,
            enabled = enabled,
            shape = containerShape ?: IconButtonDefaults.standardShape,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor ?: LocalContentColor.current,
            ),
            content = buttonContent,
        )
    }
}

/**
 * Official icon size that matches the official container size the caller asked for.
 *
 * Resolves all five official tiers — XS `extraSmallContainerSize()` / `extraSmallIconSize`,
 * S, M, L and XL — by taking the largest tier that still fits, so an arbitrary size
 * degrades to the nearest smaller official icon instead of a hard-coded value:
 * XS 32/20, S 40/24, M 56/24, L 96/32, XL 136/40.
 *
 * Sizes come from [IconButtonDefaults] only — no local dp literals.
 */
internal fun etaOfficialIconButtonIconSize(containerSize: Dp): Dp = when {
    containerSize >= IconButtonDefaults.extraLargeContainerSize().width -> IconButtonDefaults.extraLargeIconSize
    containerSize >= IconButtonDefaults.largeContainerSize().width -> IconButtonDefaults.largeIconSize
    containerSize >= IconButtonDefaults.mediumContainerSize().width -> IconButtonDefaults.mediumIconSize
    containerSize >= IconButtonDefaults.smallContainerSize().width -> IconButtonDefaults.smallIconSize
    else -> IconButtonDefaults.extraSmallIconSize
}

/**
 * Long-press host for the official Material 3 buttons.
 *
 * The official icon buttons expose no long-press slot, so the detector lives inside the
 * button content: it observes the pointer **without consuming it**, leaving the official
 * click, ripple, shape and disabled handling in charge and never swallowing a tap.
 */
@Composable
internal fun ChatInputLongPressSlot(
    enabled: Boolean,
    onLongPress: (() -> Unit)?,
    longClickLabel: String,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val latestOnLongPress by rememberUpdatedState(onLongPress)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (enabled && onLongPress != null) {
                    Modifier.pointerInput(Unit) {
                        detectChatInputLongPress { TouchHaptics.longPress(view); latestOnLongPress?.invoke() }
                    }
                } else {
                    Modifier
                },
            )
            .semantics {
                if (onLongPress != null) {
                    this.onLongClick(label = longClickLabel) {
                        TouchHaptics.longPress(view)
                        latestOnLongPress?.invoke()
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}

/**
 * Long press that runs alongside an official button's own click detection.
 *
 * 长按时长与 touch slop 仍由平台 `viewConfiguration` 决定，但**不能**用
 * `detectTapGestures`：它会消费 down 事件，而本槽位挂在官方按钮的内容里（子节点，
 * Main pass 先于父节点收到事件），down 被吃掉后外层官方按钮收不到手势，点击与
 * 按压态 / 涟漪全部失效。这里只观察时间线与位移：快速抬起（点击）时一个事件都不
 * 消费，让外层正常响应；长按成立后才吞掉剩余事件，阻止外层把同一手势误判为点击。
 */
private suspend fun PointerInputScope.detectChatInputLongPress(onLongPress: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        val slop = viewConfiguration.touchSlop
        var liftedEarly = false
        var movedPastSlop = false
        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    liftedEarly = true
                    break
                }
                if ((change.position - down.position).getDistance() > slop) {
                    movedPastSlop = true
                    break
                }
            }
        }
        if (liftedEarly || movedPastSlop) return@awaitEachGesture
        // 长按成立：触发回调后吞掉剩余事件直到抬起，外层按钮此时只会看到被取消的手势。
        onLongPress()
        do {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Capture whether the IME is visible, then restore it after a non-focusable menu window appears.
 */
@Composable
internal fun rememberKeepImeWhenOpeningMenu(): () -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = LocalChatInputFocusRequester.current
    val view = LocalView.current
    val restoreToken = remember { mutableIntStateOf(0) }
    val shouldRestore = remember { mutableStateOf(false) }
    val token = restoreToken.intValue
    // IME 可见性走离散布尔（snapshotFlow 在组合外观察，见 rememberImeVisible）：键盘动画期间
    // 逐帧变化的是 inset 数值，在组合期直接读取会让本函数每帧重启、返回的回调每帧换新实例。
    val imeVisible = rememberImeVisible()

    LaunchedEffect(token) {
        if (token == 0 || !shouldRestore.value) return@LaunchedEffect
        showChatInputIme(requester, keyboard, view)
    }

    return remember(imeVisible, keyboard, requester, view) {
        {
            shouldRestore.value = imeVisible
            restoreToken.intValue += 1
        }
    }
}
