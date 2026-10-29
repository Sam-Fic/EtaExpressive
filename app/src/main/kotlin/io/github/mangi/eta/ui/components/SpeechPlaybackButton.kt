package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.ReplyReadAloudController
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback

/**
 * 朗读动作（消息操作条官方标准按钮组的一个成员，见
 * [MessageActionButtonGroup]）：朗读中/自动朗读本条回复时显示停止键，准备中显示
 * M3E `LoadingIndicator`（官方默认形状序列变形 + 旋转，无容器圆底，直接取按钮内容色）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun speechMessageAction(
    messageId: String,
    text: String,
): MessageActionItem {
    val context = LocalContext.current
    val state by SpeechPlayback.state.collectAsState()
    // 自动朗读本条回复时（owner = 前缀 + 消息 id）同样点亮按钮：正在朗读 → 停止键。
    val autoOwner = ReplyReadAloudController.OWNER_PREFIX + messageId
    val autoReading = state.owner == autoOwner
    val active = state.owner == messageId || autoReading
    val description = stringResource(if (active) R.string.tts_stop else R.string.tts_read)
    return MessageActionItem(
        tooltip = description,
        // 官方双状态：正在朗读（含自动朗读本条回复）→ 按钮保持选中态。
        checked = active,
        enabled = !state.recording,
        onClick = {
            if (autoReading) {
                ReplyReadAloudController.stopCurrent()
            } else {
                SpeechPlayback.toggle(context, messageId, text)
            }
        },
    ) {
        if (active && state.preparing) {
            // M3E Loading Indicator：官方 `LoadingIndicator` 的 indeterminate 重载——
            // 一块在官方默认形状序列（圆/软星/三角/胶囊…）之间变形并旋转的图形，
            // 与输入区 morph 指示器同属 M3E 组件族。无容器圆底（containerColor
            // 走 Unspecified，不绘制背景），直接取按钮内容色，收敛到 XS 图标槽。
            LoadingIndicator(
                modifier = Modifier.requiredSize(IconButtonDefaults.extraSmallIconSize),
                color = LocalContentColor.current,
            )
        } else {
            Icon(
                imageVector = if (active) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = description,
                modifier = Modifier.requiredSize(IconButtonDefaults.extraSmallIconSize),
            )
        }
    }
}
