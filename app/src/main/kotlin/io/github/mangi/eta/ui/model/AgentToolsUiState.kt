package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentToolsUiState(
    val capabilities: List<ToolCapabilityUi>,
)

/**
 * 工具页的一张卡 = 一个界面能力 = 一个开关。分组与顺序来自 [ToolCapabilities]，
 * 与设置页入口摘要读的是同一份定义。
 */
@Immutable
data class ToolCapabilityUi(
    val id: String,
    val title: String,
    /** 卡片头副标题：设置页撤掉组摘要后，这里是用户唯一能看到「这组装了什么」的地方。 */
    val summary: String,
    val tools: List<ToolItemUi>,
    /** 该能力的开关；每个能力恰好一个，不再有「两组共用一个键」。 */
    val switchKey: String,
    val switchEnabled: Boolean = true,
)

@Immutable
data class ToolItemUi(
    val id: String,
    val title: String,
    val summary: String,
    /** 单工具开关：false 表示用户已在工具清单页单独禁用它（见 [io.github.mangi.eta.config.Prefs.disabledTools]）。 */
    val enabled: Boolean = true,
    /**
     * 被外部闸门（设置页里的执行策略开关）挡住时的说明；非 null 时该行置灰且开关不可用。
     * 与 [enabled] 正交：外部闸门说了算，单工具开关此时没有意义。
     */
    val blockedReason: String? = null,
)
