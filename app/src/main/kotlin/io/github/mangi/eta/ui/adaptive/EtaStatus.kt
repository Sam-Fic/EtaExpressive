package io.github.mangi.eta.ui.adaptive

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * 运行状态语义档位（L1 设计令牌）。
 *
 * Material 3 Expressive 的 colorScheme 没有 success / running 语义角色，各处自行
 * `when` 映射就会漂移（ChatMessageItem 的工具状态、AgentStatusCard 的运行状态本已
 * 各写一套）。到官方角色的映射规则只此一处：Running → `primary`，Success → 固定的
 * 绿色成功令牌（见 [etaConnectedColor]），Warning → `tertiary`，Failed → `error`，
 * Unknown → `onSurfaceVariant`。
 *
 * 强调度的降档（alpha）属于排版选择，不属于语义，留给调用方自行处理。
 */
enum class EtaStatus {
    Running,
    Success,
    Warning,
    Failed,
    Unknown,
}

/**
 * 已连接 / 成功的绿色语义色。
 *
 * 动态配色没有稳定的 success 角色：dynamic primary / tertiary 会随壁纸变成蓝、紫或
 * 橙色，无法承担「在线」语义，因此这里固定取 Material Theme Builder 绿色种子生成的
 * 可读档：浅色 `#386A20`（对白底约 6.6:1），深色 `#9CD67D`（对深色表面约 11:1）。
 *
 * 深浅判定看主题 [MaterialTheme.colorScheme.surface] 的亮度，而不是系统深色开关，
 * 这样应用内强制浅色 / 深色 / 纯黑模式都能取到对应的可读色。
 */
@Composable
fun etaConnectedColor(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) Color(0xFF9CD67D) else Color(0xFF386A20)
}

/** 状态前景色：图标、小字等前景元素使用。 */
@Composable
fun EtaStatus.foregroundColor(): Color = when (this) {
    EtaStatus.Running -> MaterialTheme.colorScheme.primary
    EtaStatus.Success -> etaConnectedColor()
    EtaStatus.Warning -> MaterialTheme.colorScheme.tertiary
    EtaStatus.Failed -> MaterialTheme.colorScheme.error
    EtaStatus.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
}

