package io.github.mangi.eta.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.agent.device.AgentTaskSurface

/**
 * 模块配置中枢。
 *
 * - Hook 进程（system_server / SystemUI / Google / 系统助手等）在模块加载时调用
 *   [attachRemote]，缓存框架提供的只读 [SharedPreferences]；之后所有拦截回调用 [isEnabled]
 *   读取当前进程持有的 remote preferences。
 * - Eta Runtime 自己消费的开关保存在 App 私有配置中，不依赖 Xposed Service。
 * - Hook 消费的开关通过 [remotePreferencesForUi] 写入 RemotePreferences；
 *   XposedService 未就绪时不提供本地假 fallback。
 *
 * 基于 libxposed API 102 的 [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 * 与 service 102 的 [XposedService.getRemotePreferences]，两端共用同一 group。
 */
internal object Prefs {

    /** 远程配置组名，UI 写入与 Hook 读取必须一致。 */
    const val GROUP = "eta_prefs"

    private const val LOCAL_AGENT_GROUP = "eta_agent_preferences"

    /** 回收站留存天数默认值。 */
    private const val DEFAULT_TRASH_RETENTION_DAYS = 30

    /** 所有功能开关 key。默认值按功能风险独立定义。 */
    object Keys {
        const val POWER_KEY_ASSISTANT_TARGET = "power_key_assistant_target"
        // 兼容旧版布尔协议；新 UI 不再写入，缺少三态配置时 true 仍表示 Gemini。
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        /** 系统助手唤起后的落点：键盘输入 / 语音识别，见 [AssistantEntryMode]。 */
        const val ASSISTANT_ENTRY_MODE = "assistant_entry_mode"
        const val HOTWORD_SELF_HEAL = "hotword_self_heal"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val LOCKSCREEN_VOICE_COMMAND = "lockscreen_voice_command"
        const val SCREEN_ON_VOICE_COMMAND = "screen_on_voice_command"
        const val AGENT_CUSTOM_MODEL = "agent_custom_model"
        const val AGENT_REQUIRE_PREFIX = "agent_require_prefix"
        const val AGENT_TERMINAL_TOOLS = "agent_terminal_tools"

        /**
         * 在 [AGENT_TERMINAL_TOOLS] 之上单独控制命令执行。关闭时仍可读写文件，但不能跑 shell。
         * 默认开启，保持历史「终端工具」开关的整体语义不变。
         */
        const val AGENT_SHELL_TOOLS = "agent_shell_tools"
        const val AGENT_BROWSER_TOOLS = "agent_browser_tools"

        /**
         * 统一分组（taxonomy）后的组开关。旧的细分开关
         * （[AGENT_DEVICE_DIRECT_TOOLS]/[AGENT_DEVICE_SENSITIVE_ACTION_TOOLS]/
         * [AGENT_DEVICE_SENSITIVE_READ_TOOLS]/[AGENT_PERSONAL_DATA_READ_TOOLS]/
         * [AGENT_DEVICE_STATE_READ_TOOLS]/[AGENT_SELF_SETTINGS_TOOLS]）仅用于一次性迁移，
         * 见 [ToolGroupPrefsMigration]；设置页与目录构建只读新 key。
         */
        const val AGENT_BASIC_TOOLS = "agent_basic_tools"
        /** 设备控制组：直达操作 + 写操作；细粒度用 [AGENT_DISABLED_TOOLS]。 */
        const val AGENT_DEVICE_TOOLS = "agent_device_tools"
        /** 个人数据组：个人内容读取 + 设备状态读取 + 媒体语义搜索（后者另有独立开关）。 */
        const val AGENT_PERSONAL_TOOLS = "agent_personal_tools"
        /**
         * Eta 自身与扩展组：自设置、MCP、自写配方、Skills 安装、记忆写入。
         * 与旧 [AGENT_SELF_SETTINGS_TOOLS] 同一原则：不出现在 AI 可见的设置列表里，
         * 模型不能自己关掉自己的配置能力。
         */
        const val AGENT_EXTENSIONS_TOOLS = "agent_extensions_tools"

        /**
         * 单工具禁用集：逗号分隔的工具名。组开关之下的细粒度控制，
         * 由工具清单页逐工具开关与旧档位迁移写入。字符串存储以便导出/恢复往返。
         */
        const val AGENT_DISABLED_TOOLS = "agent_disabled_tools"

        /**
         * 渐进加载模式（三档；对齐 ZCode「工具搜索」设置的语义）：
         * - [TOOL_LOADING_MODE_SEARCH]：按工具渐进：延迟组 + tools_search 关键词搜索，
         *   命中即解锁所属组（单工具粒度最省 token）；高频组经会话级原生集合仍走原生
         *   schema（见 [nativeGroups]），其余延迟组一律经 `tool_call` 转发；
         * - [TOOL_LOADING_MODE_GROUP]：按组渐进（最初行为）：延迟组只能经
         *   tools_use_group 整组打开，不下发 tools_search；
         * - [TOOL_LOADING_MODE_OFF]：发送完整工具目录（所有延迟组视为已打开、MCP 全量）。
         * 原来的 suffix 档（缓存优先）已并入 search/group：转发回执与原生组集合对三档
         * 生效，off 档保持全量。新建会话生效；当前会话在上下文压缩后生效——见
         * [resolveToolLoadingMode] 的会话锁定与 [unlockToolLoadingModeAtCompaction]。
         */
        const val AGENT_TOOL_LOADING_MODE = "agent_tool_loading_mode"
        const val TOOL_LOADING_MODE_SEARCH = "search"
        const val TOOL_LOADING_MODE_GROUP = "group"
        const val TOOL_LOADING_MODE_OFF = "off"

        /**
         * 已退役的 suffix 档值：只由 [toolLoadingMode] 的读时迁移映射到
         * [TOOL_LOADING_MODE_SEARCH]（搜索是它的超集：同样的转发回执 + 原生组集合，
         * 还多一个工具级搜索）；不回写，回滚旧版仍认识原值。
         */
        const val TOOL_LOADING_MODE_SUFFIX = "suffix"

        /** 当前会话锁定的模式与会话 id；由 [toolLoadingFullCatalog] 在会话首个 run 写入。 */
        const val TOOL_LOADING_MODE_ACTIVE = "agent_tool_loading_mode_active"
        const val TOOL_LOADING_MODE_CONVERSATION = "agent_tool_loading_mode_conversation"

        /**
         * 等待重新锁定的会话 id（压缩边界标记）；见 [unlockToolLoadingModeAtCompaction]。
         * 与上面两个键同样是单槽，不引新表。
         */
        const val TOOL_LOADING_MODE_RECHECK = "agent_tool_loading_mode_recheck"

        /**
         * 会话级原生工具组集合（search/group 档共用）；三者一起构成单槽快照，会话 id 不匹配即视为未初始化。
         * 与 [TOOL_LOADING_MODE_ACTIVE] 同样是「只记住当前会话」的既有模式，不引新表。
         */
        const val NATIVE_GROUPS_CONVERSATION = "agent_native_groups_conversation"
        const val NATIVE_GROUPS = "agent_native_groups"
        const val NATIVE_GROUPS_FROZEN = "agent_native_groups_frozen"

        /**
         * MCP 工具暴露方式（布尔）：true = 按 server 粒度渐进加载（每个 server 一个
         * 动态延迟组），false = 全量常驻（旧行为，最多 64 个 schema 每轮请求重发）。
         */
        const val AGENT_MCP_EXPOSURE_DEFERRED = "agent_mcp_exposure_deferred"

        /**
         * 已退役的字符串键（deferred / always），只由
         * [Prefs.mcpToolExposureDeferred] 的读时迁移读取一次；新写入一律不再碰它。
         */
        const val AGENT_MCP_EXPOSURE = "agent_mcp_exposure"
        const val MCP_EXPOSURE_DEFERRED = "deferred"
        const val MCP_EXPOSURE_ALWAYS = "always"

        /**
         * 工具调用审查模式（四档，对齐 ZCode 的权限模式光谱 + Eta 自己的智能审查）：
         * - [TOOL_REVIEW_MODE_READONLY]：只读——非低风险调用直接拒绝（对齐 plan）；
         * - [TOOL_REVIEW_MODE_STANDARD]：标准（默认）——中风险本会话确认一次，高/致命每次确认；
         * - [TOOL_REVIEW_MODE_SMART]：智能审查——中风险以上调用交给一个独立模型
         *   逐次判定 allow/deny（[AGENT_TOOL_REVIEW_ENGINE] 选择 LLM 或决策模型），
         *   模型失败时回退标准档的规则确认，绝不会因审查失效直接放行；
         * - [TOOL_REVIEW_MODE_OFF]：完全放行——不做任何确认（含高/致命），
         *   用户显式交出底线豁免的唯一档位。
         *
         * 严格（中风险逐次确认）与自动（中风险免确认）两档已并入标准，确认粒度收敛为一档；
         * 退役值只由 [toolReviewMode] 读时映射到标准，不回写（回滚旧版仍认识原值）。
         *
         * 每次调用实时读取，改设置立即生效（不同于渐进加载的新会话语义）：
         * 审查是安全边界，用户收紧时应立即拦住下一次调用。
         */
        const val AGENT_TOOL_REVIEW_MODE = "agent_tool_review_mode"
        const val TOOL_REVIEW_MODE_READONLY = "readonly"
        const val TOOL_REVIEW_MODE_STANDARD = "standard"
        const val TOOL_REVIEW_MODE_SMART = "smart"
        const val TOOL_REVIEW_MODE_OFF = "off"

        /**
         * 已退役的审查档值（strict / auto）：只由 [toolReviewMode] 的读时迁移映射到
         * [TOOL_REVIEW_MODE_STANDARD]；新写入一律不再产出这两个值。
         */
        const val TOOL_REVIEW_MODE_STRICT = "strict"
        const val TOOL_REVIEW_MODE_AUTO = "auto"

        /**
         * 智能审查档的引擎（二选一，各自独立配置）：
         * - [REVIEW_ENGINE_LLM]：独立 LLM 审查——普通对话模型经 [ModelFeatureCompletion]
         *   输出结构化 JSON（allow/deny + 理由），配置见 [AGENT_TOOL_REVIEW_LLM_PROVIDER_ID] 等；
         * - [REVIEW_ENGINE_DECISION]：decision 模型判断——复用判断模型的
         *   `/v1/systemone` 客户端（[AgentDecisionModelClient]）与它的 provider/model/端点配置，
         *   用一个 choice 题问 allow/deny，无需另配模型。
         */
        const val AGENT_TOOL_REVIEW_ENGINE = "agent_tool_review_engine"
        const val REVIEW_ENGINE_LLM = "llm"
        const val REVIEW_ENGINE_DECISION = "decision"

        /** LLM 审查引擎的 provider/model（与压缩模型同款 picker，缺省时回退到当前对话模型）。 */
        const val AGENT_TOOL_REVIEW_LLM_PROVIDER_ID = "agent_tool_review_llm_provider_id"
        const val AGENT_TOOL_REVIEW_LLM_MODEL_ID = "agent_tool_review_llm_model_id"
        /** LLM 审查引擎单次调用超时（毫秒）；过短会频繁回退，过长会卡住工具轮。 */
        const val AGENT_TOOL_REVIEW_TIMEOUT_MS = "agent_tool_review_timeout_ms"
        const val TOOL_REVIEW_TIMEOUT_DEFAULT_MS = 6000
        const val TOOL_REVIEW_TIMEOUT_MAX_MS = 20000

        /**
         * 屏幕观察与 GUI 操作总开关：observe_screen、点击/长按/滑动/滚动、文本输入、
         * wait 系列、press_key 与系统面板。关闭后这些工具既不声明也不执行，提示词同步移除
         * GUI 指引，避免为不存在的工具付固定 token。
         */
        const val AGENT_SCREEN_TOOLS = "agent_screen_tools"

        /**
         * 后台虚拟屏执行：跨应用 GUI 任务优先在受信任虚拟屏静默完成（launch_app/open_uri
         * 智能画布分流，present_to_user 破壁移交回主屏）。关闭后一律走物理主屏。
         */
        const val AGENT_VIRTUAL_DISPLAY_ENABLED = "agent_virtual_display_enabled"

        @Deprecated("迁移用；由 AGENT_DEVICE_TOOLS + AGENT_DISABLED_TOOLS 取代")
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"

        @Deprecated("迁移用；由 AGENT_PERSONAL_TOOLS + AGENT_DISABLED_TOOLS 取代")
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"

        @Deprecated("迁移用；由 AGENT_PERSONAL_TOOLS + AGENT_DISABLED_TOOLS 取代")
        const val AGENT_PERSONAL_DATA_READ_TOOLS = "agent_personal_data_read_tools"

        @Deprecated("迁移用；由 AGENT_PERSONAL_TOOLS + AGENT_DISABLED_TOOLS 取代")
        const val AGENT_DEVICE_STATE_READ_TOOLS = "agent_device_state_read_tools"

        @Deprecated("迁移用；由 AGENT_DEVICE_TOOLS + AGENT_DISABLED_TOOLS 取代")
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"

        /**
         * 是否启用「让模型读写 Eta 自身配置」的三个工具。
         * 只由用户在设置页决定：该 key 不出现在 AI 可见的设置列表里，模型不能自己关掉自己的配置能力。
         */
        @Deprecated("迁移用；由 AGENT_EXTENSIONS_TOOLS 取代")
        const val AGENT_SELF_SETTINGS_TOOLS = "agent_self_settings_tools"
        /**
         * @deprecated 媒体语义搜索（semantic_search_media）已没有独立开关：
         * 是否下发改由「个人数据」开关 + 单工具禁用集裁决，见
         * [ToolGroupPrefsMigration.migrateMediaSearchSwitchOnce] 的一次性迁移。
         * 键只为读旧值保留，不要再读写它。
         */
        @Deprecated("由 AGENT_DISABLED_TOOLS 取代；仅迁移读取")
        const val AGENT_MEDIA_SEARCH_TOOLS = "agent_media_search_tools"

        /**
         * 子代理委派开关。此前只要配了子代理就下发 delegate_task，
         * 用户无法单独关掉「把任务派给别的模型」这件事（它会产生额外计费调用）。
         * 默认开启；关闭后不再下发委派族工具，已有子任务仍可用 get_task_result 查看。
         */
        const val AGENT_SUBAGENT_TOOLS = "agent_subagent_tools"

        /**
         * [已退役] 危险操作逐次确认布尔开关：UI 已由 [AGENT_TOOL_REVIEW_MODE] 四档取代，
         * 新代码不再写它；键与默认值保留，供 [BOOLEAN_DEFAULTS] / [LOCAL_AGENT_KEYS]
         * 的远端默认值表与回滚兼容。
         */
        const val AGENT_CONFIRM_DANGEROUS_TOOLS = "agent_confirm_dangerous_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"
        const val AGENT_AUTO_COMPRESS_ENABLED = "agent_auto_compress_enabled"
        const val AGENT_COMPRESS_MODEL_PROVIDER_ID = "agent_compress_model_provider_id"
        const val AGENT_COMPRESS_MODEL_ID = "agent_compress_model_id"
        const val AGENT_COMPRESS_CUSTOM_MODEL_ENABLED = "agent_compress_custom_model_enabled"
        const val AGENT_COMPRESS_ENDPOINT_MODE = "agent_compress_endpoint_mode"

        // ── 判断模型（工具组路由）────────────────────────────────
        // 凭证不单独存储：复用 provider 体系，baseUrl / apiKey 都在
        // `model_providers` 行上，这里只记 providerId 与 modelId。
        //
        // M1 只落地配置与影子模式：总开关默认关、影子默认开，所有决策结果只写日志，
        // 不参与工具目录与提示词组装（收窄/粘性属 M2）。
        const val AGENT_DECISION_MODEL_ENABLED = "agent_decision_model_enabled"
        /** 影子模式：调用判断模型但只记录，不改变任何行为。 */
        const val AGENT_DECISION_SHADOW = "agent_decision_shadow"
        const val AGENT_DECISION_MODE = "agent_decision_mode"
        const val AGENT_DECISION_PROVIDER_ID = "agent_decision_provider_id"
        /** 主模型（建议 lingdt-decision-1.0）。 */
        const val AGENT_DECISION_MODEL_ID = "agent_decision_model_id"
        /** 可选升级模型（建议 lingdt-decision-1.0-27b）；空 = 不升级。 */
        const val AGENT_DECISION_ESCALATION_MODEL_ID = "agent_decision_escalation_model_id"
        /** 升级模型所属 provider；留空 = 复用判断模型 provider。 */
        const val AGENT_DECISION_ESCALATION_PROVIDER_ID = "agent_decision_escalation_provider_id"
        const val AGENT_DECISION_PROTOCOL = "agent_decision_protocol"
        /**
         * 自定义决策端点；空 = 从 provider.baseUrl 推导。
         *
         * 允许三种形态：完整 chat 端点（`.../v1/chat/completions`）、完整决策端点
         * （`.../v1/systemone`）、只到 `/v1` 或裸 host；归一化见
         * `AgentDecisionEndpoint.normalize`。
         */
        const val AGENT_DECISION_ENDPOINT_OVERRIDE = "agent_decision_endpoint_override"
        const val AGENT_DECISION_TIMEOUT_MS = "agent_decision_timeout_ms"
        const val AGENT_DECISION_FAILURE_POLICY = "agent_decision_failure_policy"
        /** 送判的最近轮数；0 = 只看当前输入，上限 10。 */
        const val AGENT_DECISION_HISTORY_TURNS = "agent_decision_history_turns"
        const val AGENT_DECISION_STICKY_TTL_TURNS = "agent_decision_sticky_ttl_turns"
        /** 混合模型路由开关（M3 起可开）。 */
        const val AGENT_DECISION_ESCALATION_ENABLED = "agent_decision_escalation_enabled"
        /**
         * 升级触发阈值（百分比，0 = 不按置信度升级）。
         *
         * 主模型 `tool_groups` 的置信度低于该值、且请求被判定需要工具时，才用升级模型复判。
         * 与 `AGENT_DECISION_ESCALATION_MODEL_ID` 一起生效；只存整数，避免浮点 prefs。
         */
        const val AGENT_DECISION_ESCALATION_CONFIDENCE_PCT = "agent_decision_escalation_confidence_pct"
        /**
         * control 模式下 `openableGroupLimit` 为空时，仍向模型下发 `tools_use_group`。
         *
         * 默认开：判断模型漏选（或判定为闲聊）时，模型至少能拿到解释性错误，
         * 而不是面对一个「工具不存在」的死胡同——后者会让它反复重试别的工具名。
         */
        const val AGENT_DECISION_EMPTY_GROUP_RESCUE = "agent_decision_empty_group_rescue"

        // 取值常量：设置页与运行时共用，避免两处硬编码字符串漂移。
        const val AGENT_DECISION_PROTOCOL_SYSTEMONE = "systemone"
        const val AGENT_DECISION_MODE_CONTROL = "control"
        const val AGENT_DECISION_MODE_ADVISE = "advise"
        const val AGENT_DECISION_FAILURE_OPEN = "fail_open"
        const val AGENT_DECISION_FAILURE_CLOSED = "fail_closed"

        const val AGENT_DECISION_TIMEOUT_DEFAULT_MS = 3000
        const val AGENT_DECISION_TIMEOUT_MAX_MS = 10_000
        const val AGENT_DECISION_HISTORY_TURNS_DEFAULT = 3
        const val AGENT_DECISION_HISTORY_TURNS_MAX = 10
        const val AGENT_DECISION_STICKY_TTL_DEFAULT = 2
        const val AGENT_DECISION_ESCALATION_CONFIDENCE_DEFAULT_PCT = 60
        const val AGENT_TTS_MODE = "agent_tts_mode"
        const val AGENT_TTS_MODEL_PROVIDER_ID = "agent_tts_model_provider_id"
        const val AGENT_TTS_MODEL_ID = "agent_tts_model_id"
        const val AGENT_TTS_VOICE = "agent_tts_voice"
        // 回复自动朗读分三层：总开关（是否自动朗读）→ 流式合成（输出同时按整句朗读，
        // 而不是等回复全部结束）→ 后台继续（离开应用后是否继续朗读）。
        // 细分开关只在总开关打开时生效，见 ReplyReadAloudTracker。
        const val AGENT_TTS_AUTO_READ = "agent_tts_auto_read"
        const val AGENT_TTS_STREAM_SYNTH = "agent_tts_stream_synth"
        const val AGENT_TTS_AUTO_READ_BACKGROUND = "agent_tts_auto_read_background"
        const val AGENT_VOICE_CONVERSATION_MODE = "agent_voice_conversation_mode"
        const val AGENT_VOICE_CONVERSATION_PROVIDER_ID = "agent_voice_conversation_provider_id"
        const val AGENT_VOICE_CONVERSATION_MODEL_ID = "agent_voice_conversation_model_id"
        const val AGENT_VOICE_CONVERSATION_VOICE = "agent_voice_conversation_voice"
        const val AGENT_VOICE_LAST_ENTRY = "agent_voice_last_entry"
        const val AGENT_VOICE_DOUBAO_PROVIDER_ID = "agent_voice_doubao_provider_id"
        const val AGENT_VOICE_DOUBAO_VOICE = "agent_voice_doubao_voice"
        const val AGENT_VOICE_DOUBAO_INSTRUCTIONS = "agent_voice_doubao_instructions"
        // 触感开关分三层：总开关（触控反馈 / 消息生成）→ 语义细分 → 后台补充项。
        // 细分开关只在所属总开关打开时生效，见 TouchHaptics 的 is*Enabled()。
        const val HAPTIC_TOUCH_FEEDBACK = "haptic_touch_feedback"
        const val HAPTIC_TAP = "haptic_tap"
        const val HAPTIC_LONG_PRESS = "haptic_long_press"
        const val HAPTIC_KEYBOARD = "haptic_keyboard"
        const val HAPTIC_MESSAGE_GENERATION = "haptic_message_generation"
        const val HAPTIC_TYPING = "haptic_typing"
        const val HAPTIC_TOOL_STEPS = "haptic_tool_steps"
        const val HAPTIC_REASONING_DONE = "haptic_reasoning_done"
        const val HAPTIC_BACKGROUND = "haptic_background"
        const val HAPTIC_BACKGROUND_REASONING = "haptic_background_reasoning"
        // 实时活动（灵动岛 / 流体云类通知）分三层：总开关（运行状态上岛）→ 完成横幅 → 前台抑制。
        // 细分开关的消费方各自尊重总开关：完成横幅见 AgentCompletionNotifier，
        // 前台抑制见 AgentExecutionService。
        const val LIVE_ACTIVITY_ENABLE = "live_activity_enable"
        const val LIVE_ACTIVITY_COMPLETION_BANNER = "live_activity_completion_banner"
        const val LIVE_ACTIVITY_FOREGROUND_SUPPRESS = "live_activity_foreground_suppress"
        // 回收站留存天数：字符串存储（"7"/"30"/"90"/"never"），与 eta_set_settings 的
        // CHOICE 写入路径共用一个格式；解析统一走 [trashRetentionDays]。
        const val TRASH_RETENTION_DAYS = "trash_retention_days"

        /**
         * 全部布尔开关及其默认值。
         *
         * 只收录**当前生效**的开关：已退役的 6 个工具细分开关不在这里，
         * [ToolGroupPrefsMigration] 直接按 `true` 读它们的旧值。把它们留在这张表里
         * 会让「非本地 Agent 键」的遍历（增强页历史快照）继续当成在用开关处理。
         */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to false,
            ASSISTANT_AUTO_CONFIG to false,
            HOTWORD_SELF_HEAL to false,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
            LOCKSCREEN_VOICE_COMMAND to false,
            SCREEN_ON_VOICE_COMMAND to false,
            AGENT_CUSTOM_MODEL to true,
            AGENT_REQUIRE_PREFIX to false,
            AGENT_TERMINAL_TOOLS to true,
            AGENT_SHELL_TOOLS to true,
            AGENT_BROWSER_TOOLS to true,
            AGENT_SCREEN_TOOLS to true,
            AGENT_VIRTUAL_DISPLAY_ENABLED to true,
            AGENT_BASIC_TOOLS to true,
            AGENT_DEVICE_TOOLS to true,
            AGENT_PERSONAL_TOOLS to true,
            AGENT_EXTENSIONS_TOOLS to true,
            AGENT_SUBAGENT_TOOLS to true,
            AGENT_CONFIRM_DANGEROUS_TOOLS to false,
            AGENT_THINKING_ENABLED to true,
            AGENT_AUTO_COMPRESS_ENABLED to false,
            AGENT_COMPRESS_CUSTOM_MODEL_ENABLED to false,
            AGENT_DECISION_MODEL_ENABLED to false,
            // 影子模式默认开：即使总开关被打开，也先只记录不生效。
            AGENT_DECISION_SHADOW to true,
            AGENT_DECISION_ESCALATION_ENABLED to false,
            // 默认开：control 漏选时保留 tools_use_group，让模型拿到解释性错误而不是死胡同。
            AGENT_DECISION_EMPTY_GROUP_RESCUE to true,
            AGENT_TTS_AUTO_READ to false,
            AGENT_TTS_STREAM_SYNTH to true,
            // 默认停止：与改动前的实际行为一致（进入后台即停止自动朗读）。
            AGENT_TTS_AUTO_READ_BACKGROUND to false,
            HAPTIC_TOUCH_FEEDBACK to true,
            HAPTIC_TAP to true,
            HAPTIC_LONG_PRESS to true,
            HAPTIC_KEYBOARD to true,
            HAPTIC_MESSAGE_GENERATION to true,
            HAPTIC_TYPING to true,
            HAPTIC_TOOL_STEPS to true,
            HAPTIC_REASONING_DONE to true,
            HAPTIC_BACKGROUND to true,
            HAPTIC_BACKGROUND_REASONING to true,
            LIVE_ACTIVITY_ENABLE to true,
            LIVE_ACTIVITY_COMPLETION_BANNER to true,
            // 前台收起常驻运行卡改变了现有视觉基线，默认关。
            LIVE_ACTIVITY_FOREGROUND_SUPPRESS to false,
        )

        /** 由 Eta Runtime 最终裁决、不要求 Xposed 框架在线的开关。 */
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_SHELL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_SCREEN_TOOLS,
            AGENT_VIRTUAL_DISPLAY_ENABLED,
            AGENT_BASIC_TOOLS,
            AGENT_DEVICE_TOOLS,
            AGENT_PERSONAL_TOOLS,
            AGENT_EXTENSIONS_TOOLS,
            AGENT_SUBAGENT_TOOLS,
            AGENT_CONFIRM_DANGEROUS_TOOLS,
            AGENT_THINKING_ENABLED,
            AGENT_AUTO_COMPRESS_ENABLED,
            AGENT_COMPRESS_CUSTOM_MODEL_ENABLED,
            AGENT_DECISION_MODEL_ENABLED,
            AGENT_DECISION_SHADOW,
            AGENT_DECISION_ESCALATION_ENABLED,
            AGENT_DECISION_EMPTY_GROUP_RESCUE,
            AGENT_TTS_AUTO_READ,
            AGENT_TTS_STREAM_SYNTH,
            AGENT_TTS_AUTO_READ_BACKGROUND,
            HAPTIC_TOUCH_FEEDBACK,
            HAPTIC_TAP,
            HAPTIC_LONG_PRESS,
            HAPTIC_KEYBOARD,
            HAPTIC_MESSAGE_GENERATION,
            HAPTIC_TYPING,
            HAPTIC_TOOL_STEPS,
            HAPTIC_REASONING_DONE,
            HAPTIC_BACKGROUND,
            HAPTIC_BACKGROUND_REASONING,
            LIVE_ACTIVITY_ENABLE,
            LIVE_ACTIVITY_COMPLETION_BANNER,
            LIVE_ACTIVITY_FOREGROUND_SUPPRESS,
        )
    }

    /** Hook 进程缓存的只读 remote preferences，由 ModuleMain 在 onModuleLoaded 注入。 */
    @Volatile
    private var remote: SharedPreferences? = null

    @Volatile
    private var localAgent: SharedPreferences? = null

    /** 读取 Agent 本地 Int 配置。 */
    fun getInt(key: String, default: Int): Int {
        return localAgent?.getInt(key, default) ?: default
    }

    /** 读取字符串配置。本地 Agent 配置优先，避免和只读 remote 重载抢同一参数。 */
    fun getString(key: String, default: String = ""): String {
        localAgent?.let { prefs ->
            if (prefs.contains(key)) return prefs.getString(key, default) ?: default
        }
        return remote?.getString(key, default) ?: default
    }

    /** 写入 Agent 本地 Int 配置。 */
    fun putInt(key: String, value: Int) {
        localAgent?.edit()?.putInt(key, value)?.apply()
    }

    /** 写入 Agent 本地 String 配置。 */
    fun putString(key: String, value: String) {
        localAgent?.edit()?.putString(key, value)?.apply()
    }

    /** 写入 Agent 本地 Boolean 配置。 */
    fun putBoolean(key: String, value: Boolean) {
        localAgent?.edit()?.putBoolean(key, value)?.apply()
    }

    /** App 进程调用：初始化不依赖 Xposed Service 的 Agent 配置。 */
    fun initLocal(context: Context) {
        if (localAgent == null) {
            synchronized(this) {
                if (localAgent == null) {
                    localAgent = context.applicationContext.getSharedPreferences(
                        LOCAL_AGENT_GROUP,
                        Context.MODE_PRIVATE,
                    )
                }
            }
        }
        // 一次性迁移必须在任何 Agent 偏好读取之前完成；固定标记保证只跑一次。
        AgentTaskSurface.migrateAskToForegroundOnce()
        ToolGroupPrefsMigration.runOnce(localAgent)
        ToolGroupPrefsMigration.migrateMediaSearchSwitchOnce(localAgent)
    }

    /** Hook 进程调用：缓存框架提供的只读 SharedPreferences。 */
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /** Hook 进程监听框架下发的配置变化；listener 必须由调用方在进程生命周期内强引用。 */
    fun registerRemoteListener(listener: SharedPreferences.OnSharedPreferenceChangeListener): Boolean {
        val preferences = remote ?: return false
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return true
    }

    /**
     * 读取布尔开关。remote 不可用（框架未注入或调用失败）时回退各功能自己的默认值；
     * 默认值与设置页展示保持一致。
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        val preferences = if (key in Keys.LOCAL_AGENT_KEYS) localAgent ?: remote else remote
        return preferences?.getBoolean(key, default) ?: default
    }


    /**
     * 系统助手唤起方式。只由 App 进程的入口浮窗消费，读取本地 Agent 配置；
     * 缺少配置时保持历史行为（键盘输入）。
     */
    fun assistantEntryMode(): AssistantEntryMode = assistantEntryMode(localAgent)

    fun assistantEntryMode(preferences: SharedPreferences?): AssistantEntryMode =
        AssistantEntryMode.resolve(
            runCatching { preferences?.getString(Keys.ASSISTANT_ENTRY_MODE, null) }.getOrNull(),
        )

    fun powerAssistantTarget(): PowerAssistantTarget = powerAssistantTarget(remote)

    fun powerAssistantTarget(preferences: SharedPreferences?): PowerAssistantTarget {
        val persistedValue = runCatching {
            preferences?.getString(Keys.POWER_KEY_ASSISTANT_TARGET, null)
        }.getOrNull()
        val legacyDefault = Keys.BOOLEAN_DEFAULTS.getValue(Keys.POWER_KEY_TAKEOVER)
        val legacyTakeover = runCatching {
            preferences?.getBoolean(Keys.POWER_KEY_TAKEOVER, legacyDefault)
        }.getOrNull() ?: legacyDefault
        return PowerAssistantTarget.resolve(persistedValue, legacyTakeover)
    }

    /**
     * UI 进程获取可写的 RemotePreferences。
     *
     * [XposedService.getRemotePreferences] 的 commit 会同步等待 binder 提交到 LSPosed
     * 数据库，失败返回 false；service 未就绪时返回 null，让 UI 保持不可写。
     */
    fun remotePreferencesForUi(service: XposedService?): SharedPreferences? =
        runCatching { service?.getRemotePreferences(GROUP) }.getOrNull()

    /** Eta 设置页与 Runtime 使用的本地 Agent 配置，不依赖 LSPosed。 */
    fun localAgentPreferences(): SharedPreferences? = localAgent

    fun exportAgentPreferences(): Map<String, String> {
        val prefs = localAgent ?: return emptyMap()
        return prefs.all.mapNotNull { (key, value) ->
            when (value) {
                is Boolean -> key to "b:$value"
                is Int -> key to "i:$value"
                is Long -> key to "l:$value"
                is String -> key to "s:$value"
                else -> null
            }
        }.toMap()
    }

    fun restoreAgentPreferences(values: Map<String, String>) {
        val prefs = requireNotNull(localAgent) { "Agent preferences 未初始化" }
        val editor = prefs.edit().clear()
        values.forEach { (key, encoded) ->
            if (key.isBlank() || encoded.length < 2 || encoded[1] != ':') return@forEach
            val payload = encoded.substring(2)
            when (encoded[0]) {
                'b' -> editor.putBoolean(key, payload.toBooleanStrictOrNull() ?: return@forEach)
                'i' -> editor.putInt(key, payload.toIntOrNull() ?: return@forEach)
                'l' -> editor.putLong(key, payload.toLongOrNull() ?: return@forEach)
                's' -> editor.putString(key, payload)
            }
        }
        check(editor.commit()) { "Agent preferences 恢复未落盘" }
        // 恢复会清空并重写全部本地标量，可能抹掉刚写的一次性标记。
        // 落盘后重新套用迁移：带标记的备份（迁移后导出）原样保留用户选择，
        // 无标记的旧备份若存过 ASK 则补做一次迁移。
        AgentTaskSurface.migrateAskToForegroundOnce()
        ToolGroupPrefsMigration.runOnce(localAgent)
        ToolGroupPrefsMigration.migrateMediaSearchSwitchOnce(localAgent)
    }

    /** 被用户单独禁用的工具名集合；组开关之下的细粒度控制。 */
    fun disabledTools(): Set<String> =
        getString(Keys.AGENT_DISABLED_TOOLS, "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun setToolDisabled(name: String, disabled: Boolean) {
        val updated = buildSet {
            addAll(disabledTools())
            if (disabled) add(name) else remove(name)
        }
        putString(Keys.AGENT_DISABLED_TOOLS, updated.sorted().joinToString(","))
    }

    /**
     * 渐进加载模式的用户偏好值；见 [Keys.AGENT_TOOL_LOADING_MODE]。
     *
     * 读时迁移：存量 suffix 档映射到 search——search 是它的超集（同样的 `tool_call`
     * 转发回执 + 会话级原生组集合，还多一个工具级搜索）。与 [toolReviewMode] 同一套
     * 约定：退役值不回写，回滚旧版仍认识原值。
     */
    fun toolLoadingMode(): String =
        when (val mode = getString(Keys.AGENT_TOOL_LOADING_MODE, Keys.TOOL_LOADING_MODE_SEARCH)) {
            Keys.TOOL_LOADING_MODE_SUFFIX -> Keys.TOOL_LOADING_MODE_SEARCH
            else -> mode
        }

    fun setToolLoadingMode(mode: String) {
        putString(Keys.AGENT_TOOL_LOADING_MODE, mode)
    }

    /**
     * 本会话锁定的渐进加载模式（三档，见 [Keys.AGENT_TOOL_LOADING_MODE]）。
     *
     * ZCode 的语义是「新建会话或恢复会话时生效，当前会话保持原设置」：
     * 首次在某个会话上 run 时把用户偏好锁定到该会话 id，之后同一会话一律用锁定值，
     * 只有新会话才重新读偏好。锁定值持久化在本地 Agent 配置里，进程重启不丢。
     *
     * 唯一的例外是压缩边界，见 [unlockToolLoadingModeAtCompaction]。
     */
    fun resolveToolLoadingMode(conversationId: String): String {
        val pendingRecheck = conversationId.isNotBlank() &&
            getString(Keys.TOOL_LOADING_MODE_RECHECK) == conversationId
        if (!pendingRecheck && getString(Keys.TOOL_LOADING_MODE_CONVERSATION) == conversationId) {
            return getString(Keys.TOOL_LOADING_MODE_ACTIVE, Keys.TOOL_LOADING_MODE_SEARCH)
        }
        val mode = toolLoadingMode()
        putString(Keys.TOOL_LOADING_MODE_ACTIVE, mode)
        putString(Keys.TOOL_LOADING_MODE_CONVERSATION, conversationId)
        putString(Keys.TOOL_LOADING_MODE_RECHECK, "")
        return mode
    }

    /**
     * 压缩边界：让本会话**下一个** run 重新读用户偏好，而不是继续用首个 run 锁定的档位。
     *
     * 档位在会话首个 run 锁死，那时历史是空的，无法判断这个会话会不会变长；而转发档的
     * 收益恰恰随历史长度增长（一次前缀重写＝整段历史按未缓存价重付）。压缩本来就要重付整段
     * 历史，此时换档只额外损失 tools+system 那几 k，比任何其它时机都便宜。
     *
     * 同时作废原生组快照：换档后旧集合不再适用，留在 search/group 档也该按压缩后的历史重新播种。
     * 只放开「用户已经改过的设置」，不自动升档——默认 search 的会话行为完全不变。
     * 本 run 不受影响：档位与原生组在 run 开始时就已取定。
     */
    fun unlockToolLoadingModeAtCompaction(conversationId: String) {
        if (conversationId.isBlank()) return
        if (getString(Keys.TOOL_LOADING_MODE_CONVERSATION) != conversationId) return
        putString(Keys.TOOL_LOADING_MODE_RECHECK, conversationId)
        putString(Keys.NATIVE_GROUPS_CONVERSATION, "")
    }

    /** 本会话是否发送完整工具目录（渐进加载 off）。 */
    fun toolLoadingFullCatalog(conversationId: String): Boolean =
        resolveToolLoadingMode(conversationId) == Keys.TOOL_LOADING_MODE_OFF

    /** 本会话是否下发 tools_search（仅按工具档；按组档走 tools_use_group 整组打开）。 */
    fun toolLoadingSearchEnabled(conversationId: String): Boolean =
        resolveToolLoadingMode(conversationId) == Keys.TOOL_LOADING_MODE_SEARCH

    /**
     * 本会话是否走「原生组集合 + 转发回执」的缓存优先路径（search/group 档共用，off 档为 false）。
     *
     * run 执行器与 AgentModelClient 各自内联同一判定并额外排除 preset；本函数是三档
     * 语义的命名锚点，供测试与将来的调用方使用。
     */
    fun toolLoadingSuffixMode(conversationId: String): Boolean =
        resolveToolLoadingMode(conversationId) != Keys.TOOL_LOADING_MODE_OFF

    /** 会话级原生工具组集合的快照；null = 本会话还没有初始化过。 */
    data class NativeGroupsSnapshot(val groups: Set<String>, val frozen: Boolean)

    /** 读取 [Keys.NATIVE_GROUPS] 单槽快照；会话 id 不匹配即视为未初始化。 */
    fun nativeGroups(conversationId: String): NativeGroupsSnapshot? {
        if (conversationId.isBlank()) return null
        if (getString(Keys.NATIVE_GROUPS_CONVERSATION) != conversationId) return null
        return NativeGroupsSnapshot(
            groups = getString(Keys.NATIVE_GROUPS)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet(),
            frozen = getString(Keys.NATIVE_GROUPS_FROZEN) == "1",
        )
    }

    /** 写入 [Keys.NATIVE_GROUPS] 单槽快照；[frozen] 一旦置真，本会话不再增长。 */
    fun setNativeGroups(conversationId: String, groups: Set<String>, frozen: Boolean) {
        if (conversationId.isBlank()) return
        putString(Keys.NATIVE_GROUPS_CONVERSATION, conversationId)
        putString(Keys.NATIVE_GROUPS, groups.sorted().joinToString(","))
        putString(Keys.NATIVE_GROUPS_FROZEN, if (frozen) "1" else "0")
    }

    /**
     * MCP 工具是否按 server 粒度渐进加载；false = 全量常驻（旧行为）。
     *
     * 布尔化后的读时迁移：新键优先；只有新键不存在时才读退役的字符串键，并把结果
     * 写回新键（此后不再读旧键）。挪用的先例见 [toolReviewMode] 的旧布尔迁移。
     * 本地没有旧值再查 remote（历史版本可能只落在这边），但 remote 不回写。
     */
    fun mcpToolExposureDeferred(): Boolean {
        localAgent?.let { prefs ->
            if (prefs.contains(Keys.AGENT_MCP_EXPOSURE_DEFERRED)) {
                return prefs.getBoolean(Keys.AGENT_MCP_EXPOSURE_DEFERRED, true)
            }
            if (prefs.contains(Keys.AGENT_MCP_EXPOSURE)) {
                val deferred = prefs.getString(Keys.AGENT_MCP_EXPOSURE, null) != Keys.MCP_EXPOSURE_ALWAYS
                prefs.edit().putBoolean(Keys.AGENT_MCP_EXPOSURE_DEFERRED, deferred).apply()
                return deferred
            }
        }
        remote?.let { prefs ->
            if (prefs.contains(Keys.AGENT_MCP_EXPOSURE_DEFERRED)) {
                return prefs.getBoolean(Keys.AGENT_MCP_EXPOSURE_DEFERRED, true)
            }
            if (prefs.contains(Keys.AGENT_MCP_EXPOSURE)) {
                return prefs.getString(Keys.AGENT_MCP_EXPOSURE, null) != Keys.MCP_EXPOSURE_ALWAYS
            }
        }
        return true
    }

    /** 只写新布尔键；旧字符串键保留但不再读写（回滚兼容）。 */
    fun setMcpToolExposureDeferred(deferred: Boolean) {
        localAgent?.edit()?.putBoolean(Keys.AGENT_MCP_EXPOSURE_DEFERRED, deferred)?.apply()
    }

    /**
     * 工具调用审查模式（见 [Keys.AGENT_TOOL_REVIEW_MODE]）。
     *
     * 两段读时迁移都只映射不回写（保留旧值，回滚旧版仍认识）：
     * - 退役的 strict / auto 字符串值：一律映射到标准（三档确认粒度已收敛为一档）；
     * - 旧「危险操作确认」布尔键已不再参与：开 = 标准（即默认）、关 = 旧映射的自动档
     *   也已并入标准，两向殊途同归，无需再读。
     * 都没存过则默认标准（旧行为的默认值就是开）。
     * 审查决策只发生在 App 进程（localAgent 已初始化）；Hook 进程读到默认标准即可。
     */
    fun toolReviewMode(): String {
        val prefs = localAgent ?: return Keys.TOOL_REVIEW_MODE_STANDARD
        if (!prefs.contains(Keys.AGENT_TOOL_REVIEW_MODE)) return Keys.TOOL_REVIEW_MODE_STANDARD
        return when (val stored = prefs.getString(Keys.AGENT_TOOL_REVIEW_MODE, null)) {
            Keys.TOOL_REVIEW_MODE_STRICT, Keys.TOOL_REVIEW_MODE_AUTO -> Keys.TOOL_REVIEW_MODE_STANDARD
            else -> stored ?: Keys.TOOL_REVIEW_MODE_STANDARD
        }
    }

    fun setToolReviewMode(mode: String) {
        putString(Keys.AGENT_TOOL_REVIEW_MODE, mode)
    }

    /** 智能审查档的引擎（LLM / 决策模型）；未知值回退 LLM。 */
    fun toolReviewEngine(): String =
        getString(Keys.AGENT_TOOL_REVIEW_ENGINE, Keys.REVIEW_ENGINE_LLM)
            .takeIf { it == Keys.REVIEW_ENGINE_DECISION } ?: Keys.REVIEW_ENGINE_LLM

    fun setToolReviewEngine(engine: String) {
        putString(Keys.AGENT_TOOL_REVIEW_ENGINE, engine)
    }

    /** LLM 审查引擎的 provider/model 记录 id；空表示回退到当前对话模型。 */
    fun toolReviewLlmProviderId(): String =
        getString(Keys.AGENT_TOOL_REVIEW_LLM_PROVIDER_ID)

    fun toolReviewLlmModelId(): String =
        getString(Keys.AGENT_TOOL_REVIEW_LLM_MODEL_ID)

    fun setToolReviewLlmModel(providerId: String, modelId: String) {
        putString(Keys.AGENT_TOOL_REVIEW_LLM_PROVIDER_ID, providerId)
        putString(Keys.AGENT_TOOL_REVIEW_LLM_MODEL_ID, modelId)
    }

    /** 审查模型单次调用超时（毫秒），钳制在 1s..[Keys.TOOL_REVIEW_TIMEOUT_MAX_MS]。 */
    fun toolReviewTimeoutMs(): Int =
        getInt(Keys.AGENT_TOOL_REVIEW_TIMEOUT_MS, Keys.TOOL_REVIEW_TIMEOUT_DEFAULT_MS)
            .coerceIn(1000, Keys.TOOL_REVIEW_TIMEOUT_MAX_MS)

    /** 关闭时压缩用当前对话模型；已选过自定义模型的旧配置视为开启。 */
    fun isCustomCompressModelEnabled(preferences: SharedPreferences? = localAgent): Boolean {
        val prefs = preferences ?: return false
        if (prefs.contains(Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED)) {
            return prefs.getBoolean(Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED, false)
        }
        return !prefs.getString(Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, null).isNullOrBlank() &&
            !prefs.getString(Keys.AGENT_COMPRESS_MODEL_ID, null).isNullOrBlank()
    }

    /**
     * 回收站留存天数；0 表示永不自动清理。非法存量值一律回落到默认 30 天。
     */
    fun trashRetentionDays(): Int = trashRetentionDays(getString(Keys.TRASH_RETENTION_DAYS, DEFAULT_TRASH_RETENTION_DAYS.toString()))

    fun trashRetentionDays(stored: String): Int = when (stored.trim().lowercase()) {
        "never", "0" -> 0
        else -> stored.trim().toIntOrNull()?.coerceIn(1, 3650) ?: DEFAULT_TRASH_RETENTION_DAYS
    }

    /**
     * 首次升级优先把已有 RemotePreferences 值迁入本地；之后本地值是事实源，并在框架
     * 可用时回写远端，让仍在目标进程中组装请求的 Hook 入口拿到一致的初始配置。
     */
    private val agentPreferenceReconciler = AgentPreferenceReconciler(
        Keys.LOCAL_AGENT_KEYS.associateWith(Keys.BOOLEAN_DEFAULTS::getValue),
    )

    fun reconcileAgentPreferences(service: XposedService?) {
        val local = localAgent ?: return
        val remotePreferences = remotePreferencesForUi(service) ?: return
        agentPreferenceReconciler.reconcile(requireNotNull(service), local, remotePreferences)
    }
}
