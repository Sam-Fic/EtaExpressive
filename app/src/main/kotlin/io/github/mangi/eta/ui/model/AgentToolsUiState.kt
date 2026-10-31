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
    /** 组内子开关（shell / 媒体搜索）；显示在卡片头之后、工具行之前。 */
    val subSwitch: ToolSubSwitchUi? = null,
)

@Immutable
data class ToolSubSwitchUi(
    val id: String,
    val title: String,
    val summary: String,
    val switchKey: String,
    val enabled: Boolean = true,
    /** 被它门禁的工具；关闭时这些行置灰。 */
    val gatedTools: Set<String> = emptySet(),
)

@Immutable
data class ToolItemUi(
    val id: String,
    val title: String,
    val summary: String,
    /** 单工具开关：false 表示用户已在工具清单页单独禁用它（见 [io.github.mangi.eta.config.Prefs.disabledTools]）。 */
    val enabled: Boolean = true,
)
