package io.github.mangi.eta.ui.model

import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.agent.question.AgentQuestionAnswer

sealed interface AgentHomeAction {
    data class ContextTaskSelected(val taskId: String?) : AgentHomeAction
    data class ReasoningEffortChanged(val effort: ReasoningEffort) : AgentHomeAction
    data class ModelSelected(val modelId: String, val providerId: String = "") : AgentHomeAction

    /**
     * 循环切换 GPT 速度档位（NORMAL → FAST → ULTRA_FAST → NORMAL）。
     * 临时真值由 AppState 路持有，不持久化，UI 只负责派发，不本地假切状态。
     */
    data object CycleGptSpeedMode : AgentHomeAction
    data class SubmitMessage(val text: String) : AgentHomeAction
    data object StopRun : AgentHomeAction
    data object ContinueRun : AgentHomeAction
    data object AbortPausedRun : AgentHomeAction
    data class AssistantSelected(val id: String) : AgentHomeAction
    data class ImageAttached(val uri: String) : AgentHomeAction
    data class VideoAttached(val uri: String) : AgentHomeAction
    data class RemoveImage(val id: String) : AgentHomeAction
    data class FilesAttached(val uris: List<String>) : AgentHomeAction
    data class FolderAttached(val uri: String) : AgentHomeAction
    data class FilePathAttached(val path: String) : AgentHomeAction
    data class RemoveFileReference(val id: String) : AgentHomeAction
    /** 排队消息立即插话送入当前运行。 */
    data class SteerQueuedMessage(val id: String) : AgentHomeAction
    /** 排队消息撤回到主输入框继续编辑。 */
    data class RecallQueuedMessage(val id: String) : AgentHomeAction
    data class EditMessage(val id: String) : AgentHomeAction
    data object CancelMessageEdit : AgentHomeAction
    data class DeleteMessage(val id: String) : AgentHomeAction
    data class RegenerateMessage(val id: String) : AgentHomeAction
    data class BranchMessage(val id: String) : AgentHomeAction
    data class QuestionDraftChanged(val conversationId: String, val questionId: String, val answer: AgentQuestionAnswer) : AgentHomeAction
    data class SubmitQuestionAnswer(val conversationId: String, val questionId: String) : AgentHomeAction
    data object OpenTools : AgentHomeAction
    data object OpenSkills : AgentHomeAction
    data object OpenPermissions : AgentHomeAction
    data object OpenSystemEnhance : AgentHomeAction
    data object OpenSettings : AgentHomeAction
    data object OpenBrowser : AgentHomeAction
    data class EditAssistant(val id: String) : AgentHomeAction
    data object OpenModelProviders : AgentHomeAction
    data object OpenAssistants : AgentHomeAction
    /**
     * 长按输入栏「助手与模型」按钮：进入设置页的子代理配置页（预设组列表 / 预设详情），
     * 不再单独承载一套会话协作页。
     */
    data object OpenSubAgentSettings : AgentHomeAction
    data object ExpandRunTrace : AgentHomeAction
}

sealed interface PermissionHealthAction {
    data class OpenItemAction(val itemId: String) : PermissionHealthAction
    data object NavigateBack : PermissionHealthAction
}

sealed interface AgentChatAction {
    data class ContextTaskSelected(val taskId: String?) : AgentChatAction
    data object NavigateBack : AgentChatAction
    data class ReasoningEffortChanged(val effort: ReasoningEffort) : AgentChatAction
    data class ModelSelected(val modelId: String, val providerId: String = "") : AgentChatAction

    /** 循环切换 GPT 速度档位；真值由 AppState 路持有，UI 仅派发。 */
    data object CycleGptSpeedMode : AgentChatAction
    data class SubmitMessage(val text: String) : AgentChatAction
    data object StopRun : AgentChatAction
    data object ContinueRun : AgentChatAction
    data object AbortPausedRun : AgentChatAction
    data class AssistantSelected(val id: String) : AgentChatAction
    data object OpenBrowser : AgentChatAction
    data class EditAssistant(val id: String) : AgentChatAction
    data object OpenModelProviders : AgentChatAction
    data object OpenAssistants : AgentChatAction
    /**
     * 长按输入栏「助手与模型」按钮：进入设置页的子代理配置页（预设组列表 / 预设详情），
     * 不再单独承载一套会话协作页。
     */
    data object OpenSubAgentSettings : AgentChatAction
    data class ImageAttached(val uri: String) : AgentChatAction
    data class VideoAttached(val uri: String) : AgentChatAction
    data class RemoveImage(val id: String) : AgentChatAction
    data class FilesAttached(val uris: List<String>) : AgentChatAction
    data class FolderAttached(val uri: String) : AgentChatAction
    data class FilePathAttached(val path: String) : AgentChatAction
    data class RemoveFileReference(val id: String) : AgentChatAction
    data class SteerQueuedMessage(val id: String) : AgentChatAction
    data class RecallQueuedMessage(val id: String) : AgentChatAction
    data class EditMessage(val id: String) : AgentChatAction
    data object CancelMessageEdit : AgentChatAction
    data class DeleteMessage(val id: String) : AgentChatAction
    data class RegenerateMessage(val id: String) : AgentChatAction
    data class BranchMessage(val id: String) : AgentChatAction
    data class QuestionDraftChanged(val conversationId: String, val questionId: String, val answer: AgentQuestionAnswer) : AgentChatAction
    data class SubmitQuestionAnswer(val conversationId: String, val questionId: String) : AgentChatAction
}

sealed interface AgentToolsAction {
    data object OpenEnhancements : AgentToolsAction
    data object OpenPermissions : AgentToolsAction
    data object NavigateBack : AgentToolsAction
    data object OpenBrowser : AgentToolsAction
    data object OpenWebToolsSettings : AgentToolsAction

    /** 工具页卡片旁的内联开关：就地收紧或恢复某个能力域。 */
    data class ToggleGroup(val key: String, val enabled: Boolean) : AgentToolsAction

    /** 单个工具的开关：组开关之下的细粒度取舍，落在 [io.github.mangi.eta.config.Prefs] 的禁用集里。 */
    data class ToggleTool(val name: String, val enabled: Boolean) : AgentToolsAction
}

sealed interface AgentSkillsAction {
    data object NavigateBack : AgentSkillsAction
    data class ImportZip(val uri: String) : AgentSkillsAction
    data object ConfirmZipReplacement : AgentSkillsAction
    data object CancelZipReplacement : AgentSkillsAction
    data object DismissNotice : AgentSkillsAction
    data class ToggleSkill(val skillId: String, val enabled: Boolean) : AgentSkillsAction
    data class DeleteSkill(val skillId: String) : AgentSkillsAction
    data class ReinstallBuiltin(val skillId: String) : AgentSkillsAction
}

sealed interface AgentSystemEnhanceAction {
    data object NavigateBack : AgentSystemEnhanceAction
    data object RequestRoot : AgentSystemEnhanceAction
    data object RefreshRoot : AgentSystemEnhanceAction
}

sealed interface AgentMemoryAction {
    data object NavigateBack : AgentMemoryAction
    data class ToggleEnabled(val enabled: Boolean) : AgentMemoryAction
    data class DraftChanged(val content: String) : AgentMemoryAction
    data object Save : AgentMemoryAction
    data object Clear : AgentMemoryAction
    data object DismissNotice : AgentMemoryAction
}
