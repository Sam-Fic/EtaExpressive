package io.github.mangi.eta.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.AppUpdateRepository
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
    const val DECISION_MODEL = "decision_model"
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
    const val REMOTE_AGENTS = "remote_agents"

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
    const val TOOL_SHELL = "tool_shell"
    const val TOOL_CONFIRM_DANGEROUS = "tool_confirm_dangerous"
    const val TOOL_LOADING_MODE = "tool_loading_mode"
    const val MCP_TOOL_EXPOSURE = "mcp_tool_exposure"
    const val TOOL_REVIEW_ENGINE = "tool_review_engine"
    const val TOOL_REVIEW_TIMEOUT = "tool_review_timeout"
    const val TOOL_SUBAGENT = "tool_subagent"
    const val MEDIA_INDEX = "media_index"

    // 运行环境
    const val LINUX_ENVIRONMENT = "linux_environment"
    const val WORKSPACE = "workspace"
    const val LIVE_ACTIVITY = "live_activity"

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
    /** 破坏性设置项（清理、清除、删除、移除、重置等）：搜索结果的标题与图标取官方 `error` 角色色。 */
    val destructive: Boolean = false,
)

/**
 * 设置项总表：顺序与主页分组逐行一致。
 *
 * 主页按组渲染这些行，搜索页直接过滤同一份列表——新增设置项只要在这里加一条并在主页
 * 挂上同一个 [SettingsKeys] 常量，两处同时生效，不会再出现「主页有、搜索搜不到」。
 */
@Composable
internal fun settingsCatalog(): List<SettingsEntry> {
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
                key = SettingsKeys.DECISION_MODEL,
                title = stringResource(R.string.route_decision_model),
                summary = null,
                keywords = "decision model routing 判断模型 工具组 路由 systemone 影子模式",
                icon = Icons.Rounded.Psychology,
                group = groupModelFeatures,
                destination = SettingsDestination.Route(AppRoute.DecisionModel),
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
                summary = null,
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
        add(
            SettingsEntry(
                key = SettingsKeys.REMOTE_AGENTS,
                title = stringResource(R.string.route_remote_agents),
                summary = null,
                keywords = "remote agent 远程 远端 智能体 智慧体 局域网 lan pi opencode 电脑 同步 远程对话 远程控制 webui",
                icon = Icons.Rounded.Lan,
                group = groupContextExtensions,
                destination = SettingsDestination.Route(AppRoute.RemoteAgents),
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
                // 分组开关搬进工具能力页后，这里要接住原来的搜索词：用户搜「设备控制」
                // 「日历」「命令执行」仍然是找这页，落到同一个入口比散落 9 条结果更好用。
                keywords = "tools 工具 清单 列表 开关 能力 权限 启用 停用 " +
                    "基础交互 屏幕 网页 浏览器 终端 命令执行 shell 设备控制 个人数据 " +
                    "扩展 日历 日程 通讯录 媒体搜索",
                icon = Icons.Rounded.Dashboard,
                group = groupToolCapabilities,
                destination = SettingsDestination.Route(AppRoute.Tools),
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_CONFIRM_DANGEROUS,
                title = stringResource(R.string.settings_tool_review_mode),
                summary = stringResource(R.string.settings_tool_review_mode_summary),
                keywords = "dangerous confirm 危险 高危 确认 审查 只读 标准 智能审查 完全放行 不审查 安全 拦截 review permission unrestricted",
                icon = Icons.Rounded.Warning,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        // 智能审查档专属：非智能档时主页不渲染这两行，搜索也不该命中不存在的锚点。
        if (Prefs.toolReviewMode() == Prefs.Keys.TOOL_REVIEW_MODE_SMART) {
            add(
                SettingsEntry(
                    key = SettingsKeys.TOOL_REVIEW_ENGINE,
                    title = stringResource(R.string.settings_tool_review_engine_title),
                    summary = stringResource(R.string.settings_tool_review_engine_summary),
                    keywords = "smart review 智能审查 LLM 决策模型 decision 引擎 engine 模型审查 逐次判定",
                    icon = Icons.Rounded.Psychology,
                    group = groupToolCapabilities,
                    destination = SettingsDestination.Inline,
                ),
            )
            add(
                SettingsEntry(
                    key = SettingsKeys.TOOL_REVIEW_TIMEOUT,
                    title = stringResource(R.string.settings_tool_review_timeout_title),
                    summary = stringResource(R.string.settings_tool_review_engine_summary),
                    keywords = "review timeout 审查 超时 超时时间 模型调用 秒",
                    icon = Icons.Rounded.Timer,
                    group = groupToolCapabilities,
                    destination = SettingsDestination.Inline,
                ),
            )
        }
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_LOADING_MODE,
                title = stringResource(R.string.tools_loading_mode_title),
                summary = stringResource(R.string.tools_loading_mode_summary),
                keywords = "progressive 渐进 工具加载 tools_search tools_use_group 按工具 按组 关闭 延迟 搜索 转发 缓存",
                icon = Icons.Rounded.Layers,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.MCP_TOOL_EXPOSURE,
                title = stringResource(R.string.tools_mcp_deferred_title),
                summary = stringResource(R.string.tools_mcp_deferred_summary),
                keywords = "mcp 按需 延迟 deferred 常驻 全量 schema 服务器 server",
                icon = Icons.Rounded.Dns,
                group = groupToolCapabilities,
                destination = SettingsDestination.Inline,
            ),
        )
        add(
            SettingsEntry(
                key = SettingsKeys.TOOL_SHELL,
                title = stringResource(R.string.settings_shell_tools),
                summary = stringResource(R.string.settings_shell_tools_summary),
                keywords = "shell command 命令 执行 终端 run_command terminal 安全",
                icon = Icons.Rounded.Code,
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
                key = SettingsKeys.AGENT_TASK_PREFERENCE,
                title = stringResource(R.string.agent_task_surface_title),
                summary = null,
                keywords = "task execution surface 执行位置 前台 后台 虚拟屏 副屏 实时画面 任务",
                icon = Icons.Rounded.DesktopWindows,
                group = groupRuntimeEnvironment,
                destination = SettingsDestination.Route(AppRoute.AgentTaskPreference),
            ),
        )
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
        add(
            SettingsEntry(
                key = SettingsKeys.LIVE_ACTIVITY,
                title = stringResource(R.string.live_activity_settings_title),
                summary = stringResource(R.string.live_activity_settings_summary),
                keywords = "live activity island 灵动岛 流体云 超级岛 实时活动 状态栏 通知 完成横幅",
                icon = Icons.Rounded.NotificationsActive,
                group = groupRuntimeEnvironment,
                destination = SettingsDestination.Route(AppRoute.LiveActivitySettings),
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
                destructive = true,
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
        // 与主页「关于」行同一份文案：分组标题已经是「关于」，行标题再用同一资源的话，
        // 搜索结果的「摘要 · 分组」拼接会把「关于」渲染两遍。
        val context = LocalContext.current
        val versionName = remember { AppUpdateRepository.currentVersionName(context) }
        add(
            SettingsEntry(
                key = SettingsKeys.ABOUT,
                title = stringResource(R.string.app_name),
                summary = stringResource(
                    R.string.update_current_version,
                    versionName.ifBlank { stringResource(R.string.update_unknown_version) },
                ),
                keywords = "about 关于 版本 更新 开源 许可 license 作者 github 检查更新",
                icon = Icons.Rounded.Info,
                group = groupAbout,
                destination = SettingsDestination.Route(AppRoute.About),
            ),
        )
    }
}
