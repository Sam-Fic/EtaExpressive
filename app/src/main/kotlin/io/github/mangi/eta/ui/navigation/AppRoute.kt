package io.github.mangi.eta.ui.navigation

import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data object Browser : AppRoute

    @Serializable
    data object Terminal : AppRoute

    @Serializable
    data object Tools : AppRoute

    @Serializable
    data object AgentTaskPreference : AppRoute

    @Serializable
    // 虚拟屏实时视窗：入口在 AgentTaskPreference，不在设置主页单独出现。
    data object VirtualDisplay : AppRoute

    @Serializable
    // Deserialization compatibility only; renders AgentTaskPreference, with no separate entry in UI.
    data object VirtualDisplayRecovery : AppRoute

    @Serializable
    data object Haptics : AppRoute

    @Serializable
    data object Skills : AppRoute

    @Serializable
    data object Permissions : AppRoute

    @Serializable
    data object SystemEnhance : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object SettingsSearch : AppRoute

    @Serializable
    data object SystemTakeover : AppRoute

    @Serializable
    data object About : AppRoute

    /** 关于页「参考与致谢」子页；静态列表没有不可序列化状态，独立路由才能复用 miuix `NavDisplay` 的入栈/出栈转场。 */
    @Serializable
    data object AboutCredits : AppRoute

    @Serializable
    data object SpeechSettings : AppRoute

    @Serializable
    data object TtsSettings : AppRoute

    @Serializable
    data object WebToolsSettings : AppRoute

    @Serializable
    data object VoiceSettings : AppRoute

    /** 「我的声音」内的豆包音色子页；独立路由才能复用 miuix `NavDisplay` 的入栈/出栈转场。 */
    @Serializable
    data object DoubaoVoices : AppRoute

    /** 「我的声音」内的 Mimo 音色子页。 */
    @Serializable
    data object MimoVoices : AppRoute

    @Serializable
    data object AuxiliaryVision : AppRoute

    @Serializable
    data object TitleModel : AppRoute

    @Serializable
    data object ErrorReconnectSettings : AppRoute

    /** 实时活动设置：运行状态上岛、完成横幅、前台抑制。 */
    @Serializable
    data object LiveActivitySettings : AppRoute

    @Serializable
    data object SubAgents : AppRoute

    /** 子代理预设组详情：组内子代理配置。独立路由才能获得与兄弟页面一致的入栈/出栈转场。 */
    @Serializable
    data class SubAgentPresetDetail(val presetId: String) : AppRoute

    @Serializable
    data object VoiceModeSettings : AppRoute

    @Serializable
    data object AppearanceSettings : AppRoute

    @Serializable
    data object DataBackup : AppRoute

    @Serializable
    data object Memory : AppRoute

    /** 单个助手的 MEMORY.md 编辑页；从记忆总览进入，独立于当前会话助手。 */
    @Serializable
    data class AssistantMemory(val assistantId: String) : AppRoute

    @Serializable
    data object LinuxEnvironment : AppRoute

    @Serializable
    data object SharedFolders : AppRoute

    /** 共享文件夹目录选择页；作为独立路由入栈，保存后出栈由列表页重新读取配置。 */
    @Serializable
    data object SharedFolderPicker : AppRoute

    @Serializable
    data object Workspace : AppRoute

    @Serializable
    data class LinuxFiles(val distribution: String) : AppRoute

    @Serializable
    data object ModelProviders : AppRoute

    @Serializable
    data object ModelProviderSearch : AppRoute

    @Serializable
    data object McpServers : AppRoute

    @Serializable
    data class McpServerDetail(val serverId: String) : AppRoute

    /** MCP 添加服务器整屏子页（表单 + 连接测试），走 NavDisplay 路由转场（RULES §3.3）。 */
    @Serializable
    data object McpServerAdd : AppRoute

    @Serializable
    data class ModelProviderDetail(val providerId: String) : AppRoute

    @Serializable
    data class ModelProviderAuthMethod(val providerType: NewProviderType) : AppRoute

    @Serializable
    data class ModelProviderNew(
        val providerType: NewProviderType,
        val authMode: String = "api_key",
    ) : AppRoute

    /**
     * Provider 模型编辑页（Provider 详情「模型」tab 的编辑 / 添加自定义模型入口）。
     *
     * [modelId] 是模型的仓库行标识（Model.id），区别于 API 模型名（Model.modelId）；
     * 新建自定义模型时 [isNew] 为 true、[modelId] 传空串，页面自建占位模型。
     */
    @Serializable
    data class ModelProviderModelEdit(
        val providerId: String,
        val modelId: String,
        val isNew: Boolean,
    ) : AppRoute

    @Serializable
    data object ContextCompression : AppRoute

    /** 判断模型（工具组路由）设置页；M1 阶段只展示配置与影子说明。 */
    @Serializable
    data object DecisionModel : AppRoute

    @Serializable
    data class Assistants(val picker: Boolean = false) : AppRoute

    @Serializable
    data class AssistantSearch(val picker: Boolean = false) : AppRoute

    @Serializable
    data class AssistantEdit(val assistantId: String) : AppRoute

    @Serializable
    data object UsageStats : AppRoute

    @Serializable
    data object ManageChats : AppRoute

    /** 分类管理页：新建 / 重命名 / 删除文件夹（会话分类）；从抽屉的「管理分类」入口进入。 */
    @Serializable
    data object ManageFolders : AppRoute

    /** 回收站：已软删除会话的恢复与彻底清理。 */
    @Serializable
    data object ConversationTrash : AppRoute

    /** 回收站站内搜索子页；只匹配回收站会话的标题。 */
    @Serializable
    data object ConversationTrashSearch : AppRoute

    /** [conversationScoped] 为 true 时只检索当前会话（首页顶栏入口），否则跨全部会话。 */
    @Serializable
    data class MessageSearch(val conversationScoped: Boolean = false) : AppRoute

    @Serializable
    data object ConversationTokenUsage : AppRoute

    @Serializable
    data object SubAgentStatus : AppRoute

    /** 守护任务列表页；终端块视图与控制台视图共用。 */
    @Serializable
    data object TerminalDaemonTasks : AppRoute

    /** 本地多模态语义检索页（EmbeddingGemma 2）。 */
    @Serializable
    data object MediaSearch : AppRoute

    /** 媒体语义搜索设置页：模型下载、扫描范围确认与索引构建。 */
    @Serializable
    data object MediaIndexSettings : AppRoute

    /** 端侧设备动作模型设置页：双源下载、令牌、推理后端。 */
    @Serializable
    data object MobileActionsSettings : AppRoute

    /** 端侧设备动作试用页：指令 → 本地函数调用 → 真实执行。 */
    @Serializable
    data object MobileActions : AppRoute

    /**
     * 自建工具（配方）管理页：查看 / 启停 / 编辑 / 删除模型给自己写的工具。
     *
     * 配方是模型自进化的成果，用户必须能审计、接管与收回，因此它需要独立页面，
     * 而不是只活在对话里。
     */
    @Serializable
    data object ToolRecipes : AppRoute

    /**
     * 配方编辑整屏子页：[recipeName] 是配方名（身份键 = 文件名 + 模型侧工具名）。
     * 走 NavDisplay 路由转场（RULES §3.3），改名落盘由页面读快照后走 rename。
     */
    @Serializable
    data class ToolRecipeEdit(val recipeName: String) : AppRoute

    /** 远程智能体设置页：局域网 agent 服务器的增删改与连接状态。 */
    @Serializable
    data object RemoteAgents : AppRoute

    /**
     * 远程服务器编辑子页：[serverId] 为 null 是新建，否则编辑既有配置。
     * 独立路由才能复用 miuix `NavDisplay` 的入栈/出栈转场——编辑器内容（表单 + 协议 +
     * 诊断）已塞不进一个弹窗。
     */
    @Serializable
    data class RemoteAgentEditor(val serverId: String? = null) : AppRoute

    /**
     * 宽屏设置页右栏的兜底空态。
     *
     * 平板横屏下设置主页常驻左栏，右栏默认显示第一个子设置页；miuix `NavDisplay` 要求
     * 栈不能为空（`NavDisplay.kt:1030` 的 `require(backStack.isNotEmpty())`），所以当右栏栈
     * 被异常恢复成空时，用这个可渲染的占位补一项。它不对应任何用户可见的设置项，
     * 正常导航不会 push 它。
     */
    @Serializable
    data object SettingsDetailEmpty : AppRoute
}

@Serializable
enum class NewProviderType { OpenAiCompatible, Anthropic, DoubaoSpeech, CompatibleSpeech }
