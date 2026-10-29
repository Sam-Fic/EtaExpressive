package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.markdown.NumericCitationMarkup
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import kotlinx.coroutines.delay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox as M3TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState

/**
 * 官方 M3 Expressive 提示气泡：`TooltipBox` + `PlainTooltip` + `rememberTooltipState`，
 * 位置与配色全部取官方 `TooltipDefaults`，不自定义尺寸或颜色。
 *
 * 注意：官方 `TooltipBox` 会把外部传入的 modifier 落在内部 WrappedAnchor（根 Box 无
 * modifier），因此它不能承担「ButtonGroup 直接子项」的角色——标准组里要挂
 * `Modifier.animateWidth(...)` 时，应把本组件放进按钮内容里（见
 * `MessageActionButtonGroup`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FooterTooltipBox(
    text: String,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val state = rememberTooltipState()
    M3TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { M3Text(text = text, style = etaTextStyle(EtaType.Caption)) } },
        state = state,
        enableUserInput = enabled,
        content = content,
    )
}

/**
 * 连体按钮组统一配色与图标动作收编进 `ui/adaptive/EtaExpressiveKit`
 * （[io.github.mangi.eta.ui.adaptive.EtaConnectedIconAction] / `etaConnectedGroupColors`），
 * 消息操作条本体走官方标准 `ButtonGroup`（见 `MessageActionButtonGroup`），
 * 本文件只保留消息操作条的组装。
 */

/** Renders the original projection owner; never manufactures a callback message. */
@Composable
internal fun AgentTurnFooter(
    message: AgentChatMessageUi,
    actions: ChatMessageActions,
    revealPending: Boolean,
    messageActionsEnabled: Boolean,
    branchEnabled: Boolean,
    speechPreface: String = "",
    isRunActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (revealPending || isRunActive) return
    val content = when (message) {
        is AgentMessageUi -> {
            if (message.isStreaming || message.content.isBlank()) return
            message.content
        }
        is io.github.mangi.eta.ui.model.ErrorReconnectMessageUi -> errorReconnectLabel(message)
        is SystemNoticeMessageUi -> {
            val label = stringResource(when (message.code) {
                SystemNoticeCode.Stopped -> R.string.system_notice_stopped
                SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
                SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
                SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
                SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
                SystemNoticeCode.Completed -> R.string.system_notice_completed
            })
            if (message.code == SystemNoticeCode.RuntimeFailed) label
            else listOfNotNull(label, message.detail?.takeIf(String::isNotBlank)).joinToString("\n\n")
        }
        else -> return
    }
    AgentMessageActionRow(
        messageId = message.id,
        content = content,
        allowSpeech = message is AgentMessageUi,
        speechPreface = speechPreface,
        generatedAtMillis = (message as? AgentMessageUi)?.generatedAtMillis,
        showMessageActions = true,
        messageActionsEnabled = messageActionsEnabled,
        branchEnabled = branchEnabled,
        onDelete = { actions.onDeleteMessage(message.id) },
        onRegenerate = { actions.onRegenerateMessage(message.id) },
        onBranch = { actions.onBranchMessage(message.id) },
        modifier = modifier
            .testTag("turn-footer:${message.id}")
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Shared by detached turn footers and standalone message rendering. */
@Composable
internal fun AgentMessageActionRow(
    messageId: String,
    content: String,
    allowSpeech: Boolean,
    speechPreface: String,
    generatedAtMillis: Long?,
    showMessageActions: Boolean,
    messageActionsEnabled: Boolean,
    branchEnabled: Boolean,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
    modifier: Modifier = Modifier,
    showCopyAction: Boolean = true,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val view = LocalView.current
    var copied by remember(messageId) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    val speechContent = remember(speechPreface, content) {
        listOf(NumericCitationMarkup.strip(speechPreface).trim(), NumericCitationMarkup.strip(content))
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }
    // 动作回调只定义一次。
    val onCopyClick: () -> Unit = {
        TouchHaptics.click(view)
        @Suppress("DEPRECATION")
        clipboardManager.setText(AnnotatedString(content))
        copied = true
    }
    val onBranchClick: () -> Unit = { TouchHaptics.click(view); onBranch() }
    val onRegenerateClick: () -> Unit = { TouchHaptics.click(view); onRegenerate() }
    val onDeleteClick: () -> Unit = { TouchHaptics.click(view); onDelete() }
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // M3 Expressive：官方标准按钮组 —— MessageActionButtonGroup（ButtonGroup +
        // customItem + 官方 animateWidth），按压扩展自身宽度、压缩相邻按钮；
        // 尺寸/形状/内边距全走官方 ToggleButton 默认（ExtraSmall 32dp 胶囊，按下变方角）。
        val copyDescription = stringResource(if (copied) R.string.copy_copied else R.string.copy_answer)
        val copyTooltip = stringResource(R.string.ui_copy_4edd1d)
        val branchLabel = stringResource(R.string.ui_branch_conversation)
        val regenerateTooltip = stringResource(R.string.ui_regenerate_2e1905)
        val regenerateDescription = stringResource(R.string.ui_regenerate_reply_84a7d9)
        val deleteTooltip = stringResource(R.string.ui_delete_3755f5)
        val deleteDescription = stringResource(R.string.ui_delete_this_conversation_3f351b)
        val groupActions = buildList {
            if (showCopyAction) {
                add(MessageActionItem(
                    tooltip = copyTooltip,
                    onClick = onCopyClick,
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = copyDescription,
                        modifier = Modifier.requiredSize(IconButtonDefaults.extraSmallIconSize),
                    )
                })
            }
            if (allowSpeech) {
                add(speechMessageAction(messageId, speechContent))
            }
            if (showMessageActions) {
                add(MessageActionItem(
                    tooltip = branchLabel,
                    enabled = branchEnabled,
                    onClick = onBranchClick,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.CallSplit,
                        contentDescription = branchLabel,
                        modifier = Modifier.requiredSize(IconButtonDefaults.extraSmallIconSize),
                    )
                })
                add(MessageActionItem(
                    tooltip = regenerateTooltip,
                    enabled = messageActionsEnabled,
                    onClick = onRegenerateClick,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = regenerateDescription,
                        modifier = Modifier.requiredSize(IconButtonDefaults.extraSmallIconSize),
                    )
                })
                add(MessageActionItem(
                    tooltip = deleteTooltip,
                    enabled = messageActionsEnabled,
                    onClick = onDeleteClick,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = deleteDescription,
                        modifier = Modifier.requiredSize(IconButtonDefaults.extraSmallIconSize),
                    )
                })
            }
        }
        MessageActionButtonGroup(actions = groupActions)
        if (LocalAppearanceSettings.current.messageTimestampsEnabled) {
            val timestamp = generatedAtMillis?.takeIf { it > 0L }
            if (timestamp != null) {
                val zone = java.time.ZoneId.systemDefault()
                val label = remember(timestamp, zone) { formatMessageTimestamp(timestamp, zone) }
                M3Text(
                    text = label,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    style = etaTextStyle(EtaType.Caption),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}
