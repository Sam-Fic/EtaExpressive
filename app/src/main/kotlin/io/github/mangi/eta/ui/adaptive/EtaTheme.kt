package io.github.mangi.eta.ui.adaptive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.mangi.eta.data.model.GptSpeedMode

// 颜色一律直用 MaterialTheme.colorScheme（对标官方路径，不再设 etaXxx 转发别名）；
// 本文件只保留带语义判断的颜色访问器。

/**
 * GPT 速度档位的前景色（Material 3）。
 *
 * 只取官方 `colorScheme` 角色：快速＝`tertiary`、极速＝`error`、标准＝调用方原色。
 */
@Composable
internal fun etaGptSpeedColor(mode: GptSpeedMode, normal: Color): Color =
    when (mode) {
        GptSpeedMode.FAST -> MaterialTheme.colorScheme.tertiary
        GptSpeedMode.ULTRA_FAST -> MaterialTheme.colorScheme.error
        else -> normal
    }
