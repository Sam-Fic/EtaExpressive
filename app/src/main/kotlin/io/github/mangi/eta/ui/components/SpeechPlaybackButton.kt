package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.ReplyReadAloudController
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.ui.adaptive.EtaConnectedIconAction
import io.github.mangi.eta.ui.adaptive.EtaDimens

/**
 * 朗读开关按钮。`index`/`count` 传入时渲染进连体按钮组：官方 ToggleButton
 * 承担容器（secondaryContainer 配色与连体形状，见 [io.github.mangi.eta.ui.adaptive.EtaConnectedIconAction]）；
 * 单独使用时走官方 IconButton 默认档。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SpeechPlaybackButton(
    messageId: String,
    text: String,
    index: Int = -1,
    count: Int = 0,
) {
    val context = LocalContext.current
    val state by SpeechPlayback.state.collectAsState()
    // 自动朗读本条回复时（owner = 前缀 + 消息 id）同样点亮按钮：正在朗读 → 停止键。
    val autoOwner = ReplyReadAloudController.OWNER_PREFIX + messageId
    val autoReading = state.owner == autoOwner
    val active = state.owner == messageId || autoReading
    val description = stringResource(if (active) R.string.tts_stop else R.string.tts_read)
    if (count > 0) {
        EtaConnectedIconAction(
            onClick = {
                if (autoReading) {
                    ReplyReadAloudController.stopCurrent()
                } else {
                    SpeechPlayback.toggle(context, messageId, text)
                }
            },
            enabled = !state.recording,
            index = index,
            count = count,
        ) {
            if (active && state.preparing) {
                // M3E Loading Indicator：官方 `LoadingIndicator` 的 indeterminate 重载——
                // 一块在官方默认形状序列（圆/软星/三角/胶囊…）之间变形并旋转的图形，
                // 与输入区 morph 指示器同属 M3E 组件族。无容器圆底（containerColor
                // 走 Unspecified，不绘制背景），直接取按钮内容色，缩进 20dp 图标槽；
                // 官方默认 48dp 容器被外层 size 收敛，与实际渲染尺寸无关。
                LoadingIndicator(
                    modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                    color = LocalContentColor.current,
                )
            } else {
                Icon(
                    imageVector = if (active) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = description,
                    modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                )
            }
        }
        return
    }
    // 官方 IconButton（无尺寸覆盖，走官方默认档），配色只引用官方 colorScheme 角色。
    androidx.compose.material3.IconButton(
        modifier = Modifier.semantics { contentDescription = description },
        onClick = {
            if (autoReading) {
                ReplyReadAloudController.stopCurrent()
            } else {
                SpeechPlayback.toggle(context, messageId, text)
            }
        },
        enabled = !state.recording,
    ) {
        if (active && state.preparing) {
            LoadingIndicator(
                modifier = Modifier.size(EtaDimens.StatusIconSize),
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Icon(
                imageVector = if (active) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = null,
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
