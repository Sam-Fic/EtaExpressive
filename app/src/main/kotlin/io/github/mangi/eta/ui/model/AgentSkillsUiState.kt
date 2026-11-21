package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class AgentSkillsUiState(
    val skills: List<SkillItemUi> = emptyList(),
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val busySkillId: String? = null,
    /** 批量启停/删除进行中：与单条 busySkillId 一样整页门控，防并发写注册表。 */
    val isBatchBusy: Boolean = false,
    val replacement: SkillReplacementUi? = null,
    val notice: SkillNoticeUi? = null,
)

@Immutable
data class SkillReplacementUi(
    val id: String,
    val name: String,
)

@Immutable
data class SkillNoticeUi(
    val id: Long,
    val title: String,
    val message: String,
    val isError: Boolean,
)

@Immutable
data class SkillItemUi(
    val id: String,
    val name: String,
    val description: String,
    val source: String,
    val enabled: Boolean,
    val installed: Boolean,
    val capabilities: List<String>,
)

internal val SkillItemUi.canDeleteUserSkill: Boolean
    get() = installed && source == "user"
