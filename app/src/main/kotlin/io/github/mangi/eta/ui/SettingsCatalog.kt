package io.github.mangi.eta.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.icons.SubAgents
import io.github.mangi.eta.ui.navigation.AppRoute

/**
 * 设置项锚点键。
 *
 * 主页每一行用 [row] 的 `key` 登记自己在列表里的位置，搜索索引在这里取同一个键：
 * 搜索命中没有子页的行（开关 / 选择器 / 一次性动作）时回到主页并滚到这一行。
 * 两侧共用常量，改一处即可，不会像分开硬编码那样悄悄对不上。
 */
internal object SettingsKeys {
    // LLM 提供商
    const val MODEL_PROVIDER = "model_provider"
    const val MOBILE_ACTIONS = "mobile_actions"

    // 模型功能
    const val CONTEXT_COMPRESSION = "context_compression"
    const val AUXILIARY_VISION = "auxiliary_vision"
    const val TITLE_MODEL = "title_model"
    const val ERROR_RECONNECT = "error_reconnect"
    const val WEB_TOOLS_SETTINGS = "web_tools_settings"
    const val THINKING_DEFAULT = "thinking_default"

    // 扩展
    const val ASSISTANTS = "assistants"
    const val MEMORY = "memory"
    const val SKILLS = "skills"
    const val MCP_SERVERS = "mcp_servers"
    const val TOOL_RECIPES = "tool_recipes"
    const val SUB_AGENTS = "sub_agents"

    // 通用
    const val VOICE = "voice"
    const val APPEARANCE = "appearance"
    const val LANGUAGE = "language"
    const val USAGE_STATS = "usage_stats"
    const val DATA_BACKUP = "data_backup"
    const val TRASH_RETENTION = "trash_retention"
    const val HAPTICS = "haptics"

    // Agent 工具能力
    const val TOOLS = "tools"
    const val AGENT_TASK_PREFERENCE = "agent_task_preference"
    const val TOOL_BASIC = "tool_basic"
    const val TOOL_SCREEN = "tool_screen"
    const val TOOL_WEB = "tool_web"
    const val TOOL_TERMINAL = "tool_terminal"
    const val TOOL_SHELL = "tool_shell"
    const val TOOL_DEVICE = "tool_device"
    const val TOOL_PERSONAL = "tool_personal"
    const val TOOL_EXTENSIONS = "tool_extensions"
    const val TOOL_CONFIRM_DANGEROUS = "tool_confirm_dangerous"
    const val TOOL_SUBAGENT = "tool_subagent"
    const val TOOL_MEDIA_SEARCH = "tool_media_search"
    const val MEDIA_INDEX = "media_index"

    // 运行环境
    const val LINUX_ENVIRONMENT = "linux_environment"
    const val WORKSPACE = "workspace"

    // 系统助手接管
    const val SYSTEM_TAKEOVER = "system_takeover"

    // 诊断
    const val FILE_LOGGING = "file_logging"
    const val EXPORT_LOGS = "export_logs"
    const val CLEAR_LOGS = "clear_logs"

    // 权限
    const val PERMISSION_HEALTH = "permission_health"
    const val SYSTEM_ENHANCE = "system_enhance"
    const val ENFORCE_ACCESSIBILITY = "enforce_accessibility"
    const val OVERLAY_PERMISSION = "overlay_permission"
    const val ACCESSIBILITY_TOOLS = "accessibility_tools"

    // 关于
    const val ABOUT = "about"
}

/** 设置项的跳转目标。 */
internal sealed interface SettingsDestination {
    /** 有自己的子页，直接进栈。 */
    data class Route(val route: AppRoute) : SettingsDestination

    /** 系统「悬浮窗权限」页。 */
    data object OverlayPermission : SettingsDestination

    /** 系统「无障碍」设置页。 */
    data object AccessibilitySettings : SettingsDestination

    /**
     * 行内控件：开关 / 选择器 / 一次性动作，没有可跳转的子页。
     * 命中后回到设置主页并滚动聚焦到这一行。
     */
    data object Inline : SettingsDestination
}

/**
 * 设置项条目：主页与搜索页共用的唯一数据源。
 *
 * [keywords] 是同义词 / 别名 / 拼音表，只参与匹配、不显示——让「TTS」能搜到「语音」、
 * 「震动」能搜到「触感」。中英混排一份即可：两边用户各取所需，不必走 string resource
 * 的多语言分流。
 */
internal data class SettingsEntry(
    val key: String,
    val title: String,
    val summary: String?,
    val keywords: String,
    val icon: ImageVector,
    val group: String,
    val destination: SettingsDestination,
)

/**
 * 设置项总表：顺序与主页分组逐行一致。
 *
 * 主页按组渲染这些行，搜索页直接过滤同一份列表——新增设置项只要在这里加一条并在主页
 * 挂上同一个 [SettingsKeys] 常量，两处同时生效，不会再出现「主页有、搜索搜不到」。
 *
 * @param taskBackendInstalled 与主页同一判定：任务后端未安装时不收录「任务界面」入口。
 */
@Composable
internal fun settingsCatalog(taskBackendInstalled: Boolean?): List<SettingsEntry> {
    val groupLlmProviders = stringResource(R.string.settings_llm_providers)
    val groupModelFeatures = stringResource(R.string.settings_model_features)
    val groupContextExtensions = stringResource(R.string.settings_context_extensions)
    val groupGeneral = stringResource(R.string.settings_general)
    val groupToolCapabilities = stringResource(R.string.settings_tool_capabilities)
    val groupRuntimeEnvironment = stringResource(R.string.settings_runtime_environment)
    val groupSystemTakeover = stringResource(R.string.settings_system_takeover)
    val groupDiagnostics = stringResource(R.string.settings_diagnostics)
    val groupPermissions = stringResource(R.string.ui_permissions_560165)
    val groupAbout = stringResource(R.string.ui_about_bed172)
    return buildList {
        // ── LLM 提供商 ──────────────────────────────────────────
        add(
            SettingsEntry(
                key = SettingsKeys.MODEL_PROVIDER,
                title = stringResource(R.string.ui_model_provider_e8c7f5),
                summary = null,
                keywords = "provider model api key 供应商 大模型 接口 密钥 选模型 提供商 openai anthropic",
                icon = Icons.Rounded.Memory,
                group = groupLlmProviders,
                destination = SettingsDestination.Route(AppRoute.ModelProviders),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.MOBILE_ACTIONS,
                title = stringResource(R.string.mobile_actions_settings_title),
                summary = null,
                keywords = "mobile actions 端侧 动作 模型 设备操作 手机操作 ui 自动化 点击 滑动 gui agent",
                icon = Icons.Rounded.TouchApp,
                group = groupLlmProviders,
                destination = SettingsDestination.Route(AppRoute.MobileActionsSettings),
            ),
        )

        // ── 模型功能 ────────────────────────────────────────────
        add(
            SettingsEntry(
                key = SettingsKeys.CONTEXT_COMPRESSION,
                title = stringResource(R.string.route_context_compression),
                summary = null,
                keywords = "context compression 上下文 压缩 总结 token 长对话 裁剪",
                icon = Icons.Rounded.Compress,
                group = groupModelFeatures,
                destination = SettingsDestination.Route(AppRoute.ContextCompression),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.AUXILIARY_VISION,
                title = stringResource(R.string.auxiliary_vision_title),
                summary = null,
                keywords = "vision 视觉 看图 识图 图片理解 多模态 vlm 截图识别",
                icon = Icons.Rounded.RemoveRedEye,
                group = groupModelFeatures,
                destination = SettingsDestination.Route(AppRoute.AuxiliaryVision),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TITLE_MODEL,
                title = stringResource(R.string.title_model_title),
                summary = null,
                keywords = "title 标题 命名 会话名 自动起名 摘要模型",
                icon = Icons.Rounded.AutoAwesome,
                group = groupModelFeatures,
                destination = SettingsDestination.Route(AppRoute.TitleModel),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.ERROR_RECONNECT,
                title = stringResource(R.string.error_reconnect_title),
                summary = null,
                keywords = "error retry reconnect 报错 失败 重试 重连 超时 断线",
                icon = Icons.Rounded.Refresh,
                group = groupModelFeatures,
                destination = SettingsDestination.Route(AppRoute.ErrorReconnectSettings),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.WEB_TOOLS_SETTINGS,
                title = stringResource(R.string.web_tools_settings_title),
                summary = stringResource(R.string.tool_web_search_description),
                keywords = "web search browse 联网 搜索 网页 抓取 读取 浏览器 搜索引擎",
                icon = Icons.Rounded.TravelExplore,
                group = groupModelFeatures,
                destination = SettingsDestination.Route(AppRoute.WebToolsSettings),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.THINKING_DEFAULT,
                title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                summary = null,
                keywords = "thinking reasoning 深度思考 推理 思维链 慢思考 默认开启",
                icon = Icons.Rounded.Psychology,
                group = groupModelFeatures,
                destination = SettingsDestination.Inline,
            ),
        )

        // ── 扩展 ────────────────────────────────────────────────
        add(
            SettingsEntry(
                key = SettingsKeys.ASSISTANTS,
                title = stringResource(R.string.settings_assistants),
                summary = null,
                keywords = "assistant 助手 人设 角色 提示词 prompt 人格 分身",
                icon = Icons.Rounded.SmartToy,
                group = groupContextExtensions,
                destination = SettingsDestination.Route(AppRoute.Assistants()),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.MEMORY,
                title = stringResource(R.string.ui_memory_b55ff5),
                summary = null,
                keywords = "memory 记忆 MEMORY.md 长期记忆 记住 遗忘",
                icon = Icons.Rounded.Psychology,
                group = groupContextExtensions,
                destination = SettingsDestination.Route(AppRoute.Memory),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.SKILLS,
                title = stringResource(R.string.route_skills),
                summary = null,
                keywords = "skill skills 技能 插件 能力包 工作流",
                icon = Icons.Rounded.Extension,
                group = groupContextExtensions,
                destination = SettingsDestination.Route(AppRoute.Skills),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.MCP_SERVERS,
                title = stringResource(R.string.route_mcp_servers),
                summary = null,
                keywords = "mcp server 服务器 工具协议 外部工具 model context protocol",
                icon = Icons.Rounded.AccountTree,
                group = groupContextExtensions,
                destination = SettingsDestination.Route(AppRoute.McpServers),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_RECIPES,
                title = stringResource(R.string.settings_tool_recipes),
                summary = stringResource(R.string.settings_tool_recipes_summary),
                keywords = "recipe recipes 配方 自建工具 自定义工具 模型写工具 审计",
                icon = Icons.Rounded.Handyman,
                group = groupContextExtensions,
                destination = SettingsDestination.Route(AppRoute.ToolRecipes),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.SUB_AGENTS,
                title = stringResource(R.string.settings_subagent_title),
                summary = null,
                keywords = "subagent sub agent 子代理 子助手 分身 并行 委派",
                icon = SubAgents,
                group = groupContextExtensions,
                destination = SettingsDestination.Route(AppRoute.SubAgents),
            ),
        )

        // ── 通用 ────────────────────────────────────────────────
        add(
            SettingsEntry(
                key = SettingsKeys.VOICE,
                title = stringResource(R.string.voice_title),
                summary = null,
                keywords = "voice tts asr 语音 朗读 播报 音色 唤醒 识别 豆包 doubao mimo 语音对话 语速 离线包",
                icon = Icons.Rounded.RecordVoiceOver,
                group = groupGeneral,
                destination = SettingsDestination.Route(AppRoute.VoiceSettings),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.APPEARANCE,
                title = stringResource(R.string.appearance_title),
                summary = null,
                keywords = "appearance theme 外观 主题 深色 暗色 夜间 浅色 配色 动态取色 壁纸",
                icon = Icons.Rounded.Palette,
                group = groupGeneral,
                destination = SettingsDestination.Route(AppRoute.AppearanceSettings),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.LANGUAGE,
                title = stringResource(R.string.settings_language),
                summary = null,
                keywords = "language locale 语言 中文 英文 繁体 多语言 国际化 i18n",
                icon = Icons.Rounded.Language,
                group = groupGeneral,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.USAGE_STATS,
                title = stringResource(R.string.stats_page_title),
                summary = null,
                keywords = "usage stats 用量 统计 消耗 token 花费 计费 额度",
                icon = Icons.Rounded.BarChart,
                group = groupGeneral,
                destination = SettingsDestination.Route(AppRoute.UsageStats),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.DATA_BACKUP,
                title = stringResource(R.string.data_backup_title),
                summary = null,
                keywords = "backup restore 备份 恢复 导出 导入 迁移 数据",
                icon = Icons.Rounded.Description,
                group = groupGeneral,
                destination = SettingsDestination.Route(AppRoute.DataBackup),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TRASH_RETENTION,
                title = stringResource(R.string.settings_trash_retention_title),
                summary = null,
                keywords = "trash retention 回收站 保留 留存 天数 自动清理 软删除",
                icon = Icons.Rounded.DeleteSweep,
                group = groupGeneral,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.HAPTICS,
                title = stringResource(R.string.haptics_list),
                summary = null,
                keywords = "haptics vibration 震动 触感 反馈 马达 振动",
                icon = Icons.Rounded.Vibration,
                group = groupGeneral,
                destination = SettingsDestination.Route(AppRoute.Haptics),
            ),
        )

        // ── Agent 工具能力 ──────────────────────────────────────
        add(
            SettingsEntry(
                key = SettingsKeys.TOOLS,
                title = stringResource(R.string.settings_tools_list),
                summary = null,
                keywords = "tools 工具 清单 列表 开关 能力 权限 启用 停用",
                icon = Icons.Rounded.Dashboard,
                group = groupToolCapabilities,
                destination = SettingsDestination.Route(AppRoute.Tools),
            ),
        )
        if (taskBackendInstalled == true) {
            add(
                SettingsEntry(
                    key = SettingsKeys.AGENT_TASK_PREFERENCE,
                    title = stringResource(R.string.agent_task_surface_title),
                    summary = null,
                    keywords = "task 任务 后台 守护 虚拟屏 任务界面 偏好",
                    icon = Icons.Rounded.Layers,
                    group = groupToolCapabilities,
                    destination = SettingsDestination.Route(AppRoute.AgentTaskPreference),
                ),
            )
        }
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_BASIC,
                title = stringResource(R.string.settings_basic_tools),
                summary = stringResource(R.string.settings_basic_tools_summary),
                keywords = "basic 基础工具 会话 消息 对话 发送",
                icon = Icons.Rounded.Forum,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_SCREEN,
                title = stringResource(R.string.settings_screen_tools),
                summary = stringResource(R.string.settings_screen_tools_summary),
                keywords = "screen 屏幕 截图 界面 控件 无障碍操作 点击",
                icon = Icons.Rounded.TouchApp,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_WEB,
                title = stringResource(R.string.settings_web_tools),
                summary = stringResource(R.string.settings_web_tools_summary),
                keywords = "web browser 网页 联网 浏览 搜索 抓取",
                icon = Icons.Rounded.Language,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_TERMINAL,
                title = stringResource(R.string.settings_terminal_tools),
                summary = stringResource(R.string.settings_terminal_tools_summary),
                keywords = "terminal 终端 命令行 命令 控制台",
                icon = Icons.Rounded.Terminal,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_SHELL,
                title = stringResource(R.string.settings_shell_tools),
                summary = stringResource(R.string.settings_shell_tools_summary),
                keywords = "shell 脚本 命令 bash 执行",
                icon = Icons.Rounded.Terminal,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_DEVICE,
                title = stringResource(R.string.settings_device_tools),
                summary = stringResource(R.string.settings_device_tools_summary),
                keywords = "device 设备 手机 系统信息 电量 剪贴板 应用",
                icon = Icons.Rounded.Smartphone,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_PERSONAL,
                title = stringResource(R.string.settings_personal_tools),
                summary = stringResource(R.string.settings_personal_tools_summary),
                keywords = "personal 个人数据 通讯录 联系人 短信 日程 日历 隐私",
                icon = Icons.Rounded.Badge,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_EXTENSIONS,
                title = stringResource(R.string.settings_extensions_tools),
                summary = stringResource(R.string.settings_extensions_tools_summary),
                keywords = "extension 扩展 xposed hook 模块 lsposed 注入",
                icon = Icons.Rounded.Extension,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_CONFIRM_DANGEROUS,
                title = stringResource(R.string.settings_confirm_dangerous_tools),
                summary = stringResource(R.string.settings_confirm_dangerous_tools_summary),
                keywords = "dangerous confirm 危险 高危 确认 二次确认 安全 拦截",
                icon = Icons.Rounded.Warning,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_SUBAGENT,
                title = stringResource(R.string.settings_subagent_tools),
                summary = stringResource(R.string.settings_subagent_tools_summary),
                keywords = "subagent 子代理工具 委派 并行 分身工具",
                icon = Icons.Rounded.Groups,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_MEDIA_SEARCH,
                title = stringResource(R.string.settings_media_search_tools),
                summary = stringResource(R.string.settings_media_search_tools_summary),
                keywords = "media 媒体 图片 视频 相册 文件 语义检索 搜索",
                icon = Icons.Rounded.TravelExplore,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.MEDIA_INDEX,
                title = stringResource(R.string.media_settings_entry_title),
                summary = stringResource(R.string.media_settings_entry_summary),
                keywords = "media index 媒体 索引 语义 嵌入 embedding 相册 扫描 本地检索 向量",
                icon = Icons.Rounded.TravelExplore,
                group = groupToolCapabilities,
                destination = SettingsDestination.Route(AppRoute.MediaIndexSettings),
            ),
        )

        // ── 运行环境 ────────────────────────────────────────────
        add(
            SettingsEntry(
                key = SettingsKeys.LINUX_ENVIRONMENT,
                title = stringResource(R.string.ui_linux_tool_environment_314d22),
                summary = null,
                keywords = "linux 环境 容器 proot 发行版 debian ubuntu 安装 卸载",
                icon = Icons.Rounded.Inventory2,
                group = groupRuntimeEnvironment,
                destination = SettingsDestination.Route(AppRoute.LinuxEnvironment),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.WORKSPACE,
                title = stringResource(R.string.capability_workspace),
                summary = stringResource(R.string.capability_workspace_summary),
                keywords = "workspace 工作区 目录 文件夹 文件 沙箱 路径",
                icon = Icons.Rounded.Folder,
                group = groupRuntimeEnvironment,
                destination = SettingsDestination.Route(AppRoute.Workspace),
            ),
        )

        // ── 系统助手接管 ────────────────────────────────────────
        add(
            SettingsEntry(
                key = SettingsKeys.SYSTEM_TAKEOVER,
                title = stringResource(R.string.ui_system_assistant_takes_over_f46043),
                summary = stringResource(R.string.settings_system_takeover_summary),
                keywords = "assistant takeover 接管 系统助手 默认助手 gemini 厂商兼容 电源键 长按 一圈即搜 侧键",
                icon = Icons.Rounded.PowerSettingsNew,
                group = groupSystemTakeover,
                destination = SettingsDestination.Route(AppRoute.SystemTakeover),
            ),
        )

        // ── 诊断 ────────────────────────────────────────────────
        add(
            SettingsEntry(
                key = SettingsKeys.FILE_LOGGING,
                title = stringResource(R.string.settings_file_logging),
                summary = stringResource(R.string.settings_file_logging_summary),
                keywords = "log logging 日志 诊断 记录 调试 debug 排查",
                icon = Icons.Rounded.BugReport,
                group = groupDiagnostics,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.EXPORT_LOGS,
                title = stringResource(R.string.settings_export_logs),
                summary = null,
                keywords = "export 导出 日志 zip 分享 保存 反馈",
                icon = Icons.Rounded.Share,
                group = groupDiagnostics,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.CLEAR_LOGS,
                title = stringResource(R.string.settings_clear_logs),
                summary = null,
                keywords = "clear 清除 清空 删除 日志",
                icon = Icons.Rounded.DeleteSweep,
                group = groupDiagnostics,
                destination = SettingsDestination.Inline,
            ),
        )

        // ── 权限 ────────────────────────────────────────────────
        add(
            SettingsEntry(
                key = SettingsKeys.PERMISSION_HEALTH,
                title = stringResource(R.string.ui_permission_health_3048bb),
                summary = null,
                keywords = "permission 权限 检查 健康 授权 缺失",
                icon = Icons.Rounded.HealthAndSafety,
                group = groupPermissions,
                destination = SettingsDestination.Route(AppRoute.Permissions),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.SYSTEM_ENHANCE,
                title = stringResource(R.string.capability_enhancements),
                summary = null,
                keywords = "root enhance 增强 系统增强 lsposed 框架 hook 隐藏 指纹",
                icon = Icons.Rounded.Security,
                group = groupPermissions,
                destination = SettingsDestination.Route(AppRoute.SystemEnhance),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.ENFORCE_ACCESSIBILITY,
                title = stringResource(R.string.ui_enforce_accessibility_55e838),
                summary = null,
                keywords = "accessibility 无障碍 保活 强制 常驻 服务 被杀 自启",
                icon = Icons.Rounded.VerifiedUser,
                group = groupPermissions,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.OVERLAY_PERMISSION,
                title = stringResource(R.string.ui_floating_window_permissions_076b77),
                summary = null,
                keywords = "overlay 悬浮窗 权限 弹窗 上层显示 系统弹窗",
                icon = Icons.Rounded.Layers,
                group = groupPermissions,
                destination = SettingsDestination.OverlayPermission,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.ACCESSIBILITY_TOOLS,
                title = stringResource(R.string.ui_accessibility_enhancement_tools_8fd257),
                summary = null,
                keywords = "accessibility 无障碍 增强工具 系统设置 服务 开关",
                icon = Icons.Rounded.AccessibilityNew,
                group = groupPermissions,
                destination = SettingsDestination.AccessibilitySettings,
            ),
        )

        // ── 关于（固定收尾，保持整页最后一组）─────────────────────────
        add(
            SettingsEntry(
                key = SettingsKeys.ABOUT,
                title = stringResource(R.string.ui_about_bed172),
                summary = stringResource(R.string.app_name),
                keywords = "about 关于 版本 更新 开源 许可 license 作者 github 检查更新",
                icon = Icons.Rounded.Info,
                group = groupAbout,
                destination = SettingsDestination.Route(AppRoute.About),
            ),
        )
    }
}
