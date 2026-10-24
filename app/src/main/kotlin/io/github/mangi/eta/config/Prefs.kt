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
        const val AGENT_DEVICE_DIRECT_TOOLS = "agent_device_direct_tools"
        const val AGENT_DEVICE_SENSITIVE_READ_TOOLS = "agent_device_sensitive_read_tools"

        /**
         * 敏感读里的两档细分（都在 [AGENT_DEVICE_SENSITIVE_READ_TOOLS] 总闸之下）：
         * - [AGENT_PERSONAL_DATA_READ_TOOLS]：通讯录/短信/账单/健康/订单等个人信息；
         * - [AGENT_DEVICE_STATE_READ_TOOLS]：设置、系统状态、闹钟列表、位置等设备状态。
         * 都默认开启，保持历史行为；用户可以只关掉个人信息那一档。
         */
        const val AGENT_PERSONAL_DATA_READ_TOOLS = "agent_personal_data_read_tools"
        const val AGENT_DEVICE_STATE_READ_TOOLS = "agent_device_state_read_tools"
        const val AGENT_DEVICE_SENSITIVE_ACTION_TOOLS = "agent_device_sensitive_action_tools"
        /**
         * 是否启用「让模型读写 Eta 自身配置」的三个工具。
         * 只由用户在设置页决定：该 key 不出现在 AI 可见的设置列表里，模型不能自己关掉自己的配置能力。
         */
        const val AGENT_SELF_SETTINGS_TOOLS = "agent_self_settings_tools"
        /** 媒体语义搜索工具（semantic_search_media）：只做本机离线索引检索，默认关闭。 */
        const val AGENT_MEDIA_SEARCH_TOOLS = "agent_media_search_tools"

        /**
         * 子代理委派开关。此前只要配了子代理就下发 delegate_task，
         * 用户无法单独关掉「把任务派给别的模型」这件事（它会产生额外计费调用）。
         * 默认开启；关闭后不再下发委派族工具，已有子任务仍可用 get_task_result 查看。
         */
        const val AGENT_SUBAGENT_TOOLS = "agent_subagent_tools"

        /**
         * 危险操作逐次确认：重启、卸载应用、清数据、删除用户等不可逆操作先问用户。
         * 默认关闭，保持历史行为；打开后只作用于写操作，读类工具不受影响。
         */
        const val AGENT_CONFIRM_DANGEROUS_TOOLS = "agent_confirm_dangerous_tools"
        const val AGENT_THINKING_ENABLED = "agent_thinking_enabled"
        const val AGENT_RUNTIME_CONFIG_JSON = "agent_runtime_config_json"
        const val AGENT_AUTO_COMPRESS_ENABLED = "agent_auto_compress_enabled"
        const val AGENT_COMPRESS_MODEL_PROVIDER_ID = "agent_compress_model_provider_id"
        const val AGENT_COMPRESS_MODEL_ID = "agent_compress_model_id"
        const val AGENT_COMPRESS_CUSTOM_MODEL_ENABLED = "agent_compress_custom_model_enabled"
        const val AGENT_COMPRESS_ENDPOINT_MODE = "agent_compress_endpoint_mode"
        const val AGENT_TTS_MODE = "agent_tts_mode"
        const val AGENT_TTS_MODEL_PROVIDER_ID = "agent_tts_model_provider_id"
        const val AGENT_TTS_MODEL_ID = "agent_tts_model_id"
        const val AGENT_TTS_VOICE = "agent_tts_voice"
        // 回复自动朗读分两层：总开关（是否自动朗读）→ 流式合成（输出同时按整句朗读，
        // 而不是等回复全部结束）。细分开关只在总开关打开时生效，见 ReplyReadAloudTracker。
        const val AGENT_TTS_AUTO_READ = "agent_tts_auto_read"
        const val AGENT_TTS_STREAM_SYNTH = "agent_tts_stream_synth"
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
        // 回收站留存天数：字符串存储（"7"/"30"/"90"/"never"），与 eta_set_settings 的
        // CHOICE 写入路径共用一个格式；解析统一走 [trashRetentionDays]。
        const val TRASH_RETENTION_DAYS = "trash_retention_days"

        /** 全部布尔开关及其默认值。 */
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
            AGENT_DEVICE_DIRECT_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
            AGENT_PERSONAL_DATA_READ_TOOLS to true,
            AGENT_DEVICE_STATE_READ_TOOLS to true,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
            AGENT_SELF_SETTINGS_TOOLS to true,
            AGENT_MEDIA_SEARCH_TOOLS to false,
            AGENT_SUBAGENT_TOOLS to true,
            AGENT_CONFIRM_DANGEROUS_TOOLS to false,
            AGENT_THINKING_ENABLED to true,
            AGENT_AUTO_COMPRESS_ENABLED to false,
            AGENT_COMPRESS_CUSTOM_MODEL_ENABLED to false,
            AGENT_TTS_AUTO_READ to false,
            AGENT_TTS_STREAM_SYNTH to true,
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
        )

        /** 由 Eta Runtime 最终裁决、不要求 Xposed 框架在线的开关。 */
        val LOCAL_AGENT_KEYS: Set<String> = setOf(
            AGENT_TERMINAL_TOOLS,
            AGENT_SHELL_TOOLS,
            AGENT_BROWSER_TOOLS,
            AGENT_DEVICE_DIRECT_TOOLS,
            AGENT_DEVICE_SENSITIVE_READ_TOOLS,
            AGENT_PERSONAL_DATA_READ_TOOLS,
            AGENT_DEVICE_STATE_READ_TOOLS,
            AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
            AGENT_SELF_SETTINGS_TOOLS,
            AGENT_MEDIA_SEARCH_TOOLS,
            AGENT_SUBAGENT_TOOLS,
            AGENT_CONFIRM_DANGEROUS_TOOLS,
            AGENT_THINKING_ENABLED,
            AGENT_AUTO_COMPRESS_ENABLED,
            AGENT_COMPRESS_CUSTOM_MODEL_ENABLED,
            AGENT_TTS_AUTO_READ,
            AGENT_TTS_STREAM_SYNTH,
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
    }

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
