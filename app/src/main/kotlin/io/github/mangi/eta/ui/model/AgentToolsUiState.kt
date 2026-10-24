package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentToolsUiState(
    val groups: List<ToolGroupUi>,
)

@Immutable
data class ToolGroupUi(
    val id: String,
    val title: String,
    val tools: List<ToolItemUi>,
    /** 该组对应的全局开关；null 表示没有单一开关可提供（见 [ToolGroupSwitch]）。 */
    val switchKey: String? = null,
    /** 开关当前状态；仅在 [switchKey] 非空时有意义。 */
    val switchEnabled: Boolean = true,
)

@Immutable
data class ToolItemUi(
    val id: String,
    val title: String,
    val summary: String,
)
