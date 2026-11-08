package io.github.mangi.eta.ui.adaptive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics

/**
 * 自适应弹窗。正文由调用方提供，动作行经 [actions] 走 AlertDialog 的官方 buttons 槽。
 *
 * 动作行必须放 buttons 槽而非 text 槽：text 槽自带 `AlertDialogDefaults.textPadding`
 * 的 24dp 底部内边距，叠加上容器的 24dp 后会让按钮下方留白翻倍；buttons 槽只有容器的
 * 24dp 内边距，这才是官方留白。
 */
@Composable
fun EtaDialog(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        icon = icon?.let { image ->
            {
                // 尺寸与配色沿用官方 AlertDialog 的 icon 槽位默认令牌。
                androidx.compose.material3.Icon(
                    imageVector = image,
                    contentDescription = null,
                )
            }
        },
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(EtaDimens.SpaceMd)) {
                summary?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                content()
            }
        },
        shape = MaterialTheme.shapes.extraLarge,
        // 官方 buttons 槽：位置与内边距全部交给 AlertDialog，调用方无需再自绘 Row。
        confirmButton = { actions?.invoke() },
    )
}

/**
 * 全屏页面式表面：内容放进独立窗口，内部照常使用 [EtaScaffold] / [EtaScaffoldPage]。
 *
 * 用于内容体量已属目的地、但状态又无法序列化进路由的编辑器（草稿会话、owner 票据等）；
 * 窗口占满整屏，返回键交给 [onDismissRequest]，不再有底部弹层的拖拽与遮罩语义。
 */
@Composable
fun EtaPageSurface(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // 主题把 android:windowAnimationStyle 置为 @null，窗口本身是瞬现的，
        // 所以进出场只能由内容自己做：从 0.94 缩放 + 淡入落位（官方 Expressive 规格）。
        // 退场仍由窗口直接销毁——Dialog 关闭时协程作用域随之取消，无法在窗口外继续播完。
        val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
        val motionScheme = MaterialTheme.motionScheme
        AnimatedVisibility(
            visibleState = visibleState,
            enter = fadeIn(motionScheme.defaultEffectsSpec()) +
                scaleIn(
                    initialScale = 0.94f,
                    animationSpec = motionScheme.defaultSpatialSpec(),
                ),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                content = content,
            )
        }
    }
}

/**
 * KernelSU `ExpressiveDialog` 对应物：AlertDialog + `shapes.extraLarge`，
 * 参数与参考实现一致，颜色槽位沿用 `AlertDialogDefaults`。
 */
@Composable
fun EtaExpressiveDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.extraLarge,
    containerColor: androidx.compose.ui.graphics.Color = AlertDialogDefaults.containerColor,
    iconContentColor: androidx.compose.ui.graphics.Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: androidx.compose.ui.graphics.Color = AlertDialogDefaults.titleContentColor,
    textContentColor: androidx.compose.ui.graphics.Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

/** KernelSU `ExpressiveConfirmDialog` 对应物：标题 + 可选正文 + 确认/取消文本按钮。 */
@Composable
fun EtaExpressiveConfirmDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    dismissText: String? = null,
) {
    EtaExpressiveDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = message?.let { { Text(it) } },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText) }
        },
        dismissButton = dismissText?.let {
            { TextButton(onClick = onDismiss) { Text(it) } }
        },
    )
}

/** 弹窗底部按钮行（取消 / 确认）。 */
@Composable
fun EtaDialogActions(
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    cancelText: String? = null,
    cancelEnabled: Boolean = true,
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
) {
    val view = LocalView.current
    // KernelSU（ExpressiveDialog）写法：动作右对齐、一律 TextButton（AlertDialog 的官方
    // 动作形态——没有整宽填充按钮），破坏性确认以 error 色文案区分，不加按钮底色。
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm, Alignment.End),
    ) {
        TextButton(
            onClick = {
                TouchHaptics.click(view)
                onCancel()
            },
            enabled = cancelEnabled,
        ) {
            Text(text = cancelText ?: stringResource(R.string.action_cancel))
        }
        TextButton(
            onClick = {
                TouchHaptics.click(view)
                onConfirm()
            },
            enabled = confirmEnabled,
        ) {
            Text(
                text = confirmText,
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

/**
 * 自适应单行输入框。
 *
 * 直通官方 `OutlinedTextField` 的 value 重载（[isError] 与 [singleLine] 原样透传）。
 * 签名取证：androidx.compose.material3:material3-android 1.5.0-alpha29 sources jar
 * （gradle caches `material3-android-1.5.0-alpha29-sources.jar`）`OutlinedTextField.kt:314`
 * 的 String 重载——`:328 isError: Boolean = false`、`:332 singleLine: Boolean = false`，
 * 错误态由官方 `defaultErrorSemantics` 挂语义（`:355`）。
 */
@Composable
fun EtaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(text = label) },
        singleLine = singleLine,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
    )
}
