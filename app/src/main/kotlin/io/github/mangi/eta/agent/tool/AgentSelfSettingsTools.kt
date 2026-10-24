package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.config.ReplyReadAloudPreference
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 读写 Eta 自身开关：应用内配置，与 Android 系统设置无关。
 *
 * 只暴露 Eta Runtime 自己消费的开关（[Prefs.Keys.LOCAL_AGENT_KEYS] 与长期记忆总开关）。
 * 电源键接管、手势、热词自愈等由 Hook 进程从远端配置读取，写进本地不会生效，因此不开放。
 */
internal class AgentSelfSettingsTools {

    private enum class Area(val id: String, val label: String) {
        TOOLS("tools", "工具"),
        SPEECH("speech", "语音"),
        CHAT("chat", "对话"),
        MEMORY("memory", "记忆"),
        HAPTICS("haptics", "触感"),
    }

    private enum class Kind { SWITCH, CHOICE }

    private class EtaSetting(
        val key: String,
        val label: String,
        val kind: Kind,
        val area: Area,
        val options: List<String> = emptyList(),
        val note: String = "",
        /** CHOICE 未写入时的默认值；留空回落到 options.first()。 */
        val default: String = "",
    )

    /**
     * 工具开关不进入本表即时生效的例外：它们在每次工具调用时重新求值，
     * 所以当前任务里的下一次调用就会用新值。
     */
    private val TOOL_KEYS = setOf(
        Prefs.Keys.AGENT_TERMINAL_TOOLS,
        Prefs.Keys.AGENT_BROWSER_TOOLS,
        Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
        Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
        Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
        Prefs.Keys.AGENT_SELF_SETTINGS_TOOLS,
        Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS,
    )

    private val settings: List<EtaSetting> = listOf(
        EtaSetting(Prefs.Keys.AGENT_TERMINAL_TOOLS, "终端工具", Kind.SWITCH, Area.TOOLS),
        EtaSetting(Prefs.Keys.AGENT_BROWSER_TOOLS, "浏览器工具", Kind.SWITCH, Area.TOOLS),
        EtaSetting(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS, "设备直达工具", Kind.SWITCH, Area.TOOLS),
        EtaSetting(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS, "敏感信息读取", Kind.SWITCH, Area.TOOLS),
        EtaSetting(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS, "敏感设备操作", Kind.SWITCH, Area.TOOLS),
        EtaSetting(Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS, "媒体语义搜索", Kind.SWITCH, Area.TOOLS),
        EtaSetting(Prefs.Keys.AGENT_TTS_AUTO_READ, "回复自动朗读", Kind.SWITCH, Area.SPEECH),
        EtaSetting(
            Prefs.Keys.AGENT_TTS_STREAM_SYNTH, "边生成边朗读", Kind.SWITCH, Area.SPEECH,
            note = "需要先打开回复自动朗读",
        ),
        EtaSetting(Prefs.Keys.AGENT_TTS_MODE, "朗读引擎", Kind.CHOICE, Area.SPEECH, listOf("cloud", "system")),
        EtaSetting(
            Prefs.Keys.AGENT_VOICE_CONVERSATION_MODE, "语音对话引擎", Kind.CHOICE,
            Area.SPEECH, listOf("cloud", "system"),
        ),
        EtaSetting(Prefs.Keys.AGENT_THINKING_ENABLED, "思考模式", Kind.SWITCH, Area.CHAT),
        EtaSetting(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED, "上下文自动压缩", Kind.SWITCH, Area.CHAT),
        EtaSetting(
            Prefs.Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED, "压缩使用独立模型", Kind.SWITCH, Area.CHAT,
            note = "需要先打开上下文自动压缩",
        ),
        EtaSetting(
            Prefs.Keys.TRASH_RETENTION_DAYS, "回收站留存天数", Kind.CHOICE, Area.CHAT,
            listOf("7", "30", "90", "never"), default = "30",
            note = "对话移入回收站超过留存天数后自动彻底清理；never 表示不自动清理",
        ),
        EtaSetting(MEMORY_ENABLED, "长期记忆", Kind.SWITCH, Area.MEMORY),
        EtaSetting(Prefs.Keys.HAPTIC_TOUCH_FEEDBACK, "触感总开关", Kind.SWITCH, Area.HAPTICS),
        EtaSetting(Prefs.Keys.HAPTIC_TAP, "点击触感", Kind.SWITCH, Area.HAPTICS),
        EtaSetting(Prefs.Keys.HAPTIC_LONG_PRESS, "长按触感", Kind.SWITCH, Area.HAPTICS),
        EtaSetting(Prefs.Keys.HAPTIC_KEYBOARD, "键盘触感", Kind.SWITCH, Area.HAPTICS),
        EtaSetting(Prefs.Keys.HAPTIC_MESSAGE_GENERATION, "生成触感", Kind.SWITCH, Area.HAPTICS),
        EtaSetting(Prefs.Keys.HAPTIC_TYPING, "打字机触感", Kind.SWITCH, Area.HAPTICS),
        EtaSetting(Prefs.Keys.HAPTIC_TOOL_STEPS, "工具步骤触感", Kind.SWITCH, Area.HAPTICS),
        EtaSetting(Prefs.Keys.HAPTIC_REASONING_DONE, "思考完成触感", Kind.SWITCH, Area.HAPTICS),
        EtaSetting(Prefs.Keys.HAPTIC_BACKGROUND, "后台触感", Kind.SWITCH, Area.HAPTICS),
        EtaSetting(Prefs.Keys.HAPTIC_BACKGROUND_REASONING, "后台思考触感", Kind.SWITCH, Area.HAPTICS),
    )

    fun list(): String {
        val items = JSONArray()
        settings.forEach { setting ->
            val item = JSONObject()
                .put("key", setting.key)
                .put("label", setting.label)
                .put("area", setting.area.id)
                .put("value", read(setting))
            if (setting.options.isNotEmpty()) {
                item.put("options", JSONArray().also { array -> setting.options.forEach(array::put) })
            }
            if (setting.note.isNotBlank()) item.put("note", setting.note)
            items.put(item)
        }
        val areas = JSONArray()
        Area.entries.forEach { area ->
            areas.put(JSONObject().put("id", area.id).put("label", area.label))
        }
        return JSONObject()
            .put("ok", true)
            .put("settings", items)
            .put("areas", areas)
            .toString()
    }

    fun set(args: JSONObject): String {
        val raw = args.optJSONArray("settings")
            ?: return error("INVALID_ARGUMENT", "settings 必须是数组")
        if (raw.length() == 0) return error("INVALID_ARGUMENT", "settings 不能为空")
        val results = JSONArray()
        var failed = 0
        for (index in 0 until raw.length()) {
            val entry = raw.optJSONObject(index)
            if (entry == null) {
                failed++
                results.put(
                    JSONObject()
                        .put("ok", false)
                        .put("code", "INVALID_ARGUMENT")
                        .put("message", "第 ${index + 1} 项不是对象"),
                )
                continue
            }
            val key = entry.optString("key").trim()
            val setting = settings.firstOrNull { it.key == key }
            if (setting == null) {
                failed++
                results.put(
                    JSONObject()
                        .put("ok", false)
                        .put("key", key)
                        .put("code", "UNKNOWN_SETTING")
                        .put("message", "未知设置项；先用 eta_get_settings 查看可用项"),
                )
                continue
            }
            val requested = entry.optString("value").trim()
            val normalized = normalize(setting, requested)
            if (normalized == null) {
                failed++
                results.put(
                    JSONObject()
                        .put("ok", false)
                        .put("key", key)
                        .put("code", "INVALID_VALUE")
                        .put(
                            "message",
                            if (setting.kind == Kind.SWITCH) "开关只接受 on 或 off" else "可选值：${setting.options.joinToString(" / ")}",
                        ),
                )
                continue
            }
            val before = read(setting)
            // 安全边界：工具开关是用户的安全决策。模型可以关掉它、让能力变窄，
            // 但不能把用户关掉的开关重新打开——否则一个仍在运行、持有本工具的模型
            // 可以先关掉终端的开关再自己开回来，然后立刻使用终端，全程没有确认。
            // （执行侧每次都重读 Prefs，所以这确实是一条可被利用的提权路径。）
            if (setting.key in TOOL_KEYS && normalized == "on" && before == "off") {
                failed++
                results.put(
                    JSONObject()
                        .put("ok", false)
                        .put("key", key)
                        .put("code", "USER_CONSENT_REQUIRED")
                        .put("from", before)
                        .put("to", normalized)
                        .put(
                            "message",
                            "工具开关不能由模型重新打开：这属于用户的安全决策。" +
                                "请让用户自己在设置里打开后继续；不要重复调用本工具尝试绕过。",
                        ),
                )
                continue
            }
            val written = write(setting, normalized)
            if (!written) {
                failed++
                results.put(
                    JSONObject()
                        .put("ok", false)
                        .put("key", key)
                        .put("code", "WRITE_FAILED")
                        .put("message", "写入失败，设置未改变"),
                )
                continue
            }
            results.put(
                JSONObject()
                    .put("ok", true)
                    .put("key", key)
                    .put("label", setting.label)
                    .put("from", before)
                    .put("to", normalized)
                    .put("effect", if (setting.key in TOOL_KEYS) "next_tool_call" else "next_reply"),
            )
        }
        return JSONObject()
            .put("ok", failed == 0)
            .put("changed", raw.length() - failed)
            .put("results", results)
            .put("note", "改动已保存。effect 为 next_reply 的项从下一次回复生效，next_tool_call 的项在本次任务的下一次工具调用即生效。")
            .toString()
    }

    private fun read(setting: EtaSetting): String = when (setting.kind) {
        Kind.SWITCH -> if (readSwitch(setting.key)) "on" else "off"
        Kind.CHOICE -> readChoice(setting)
    }

    private fun readSwitch(key: String): Boolean = when (key) {
        MEMORY_ENABLED -> runBlocking(Dispatchers.IO) { SettingsDataStore.settings().memoryEnabled }
        Prefs.Keys.AGENT_TTS_AUTO_READ -> ReplyReadAloudPreference.autoRead
        Prefs.Keys.AGENT_TTS_STREAM_SYNTH -> ReplyReadAloudPreference.streamSynthesis
        else -> localPreferences()?.getBoolean(key, Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: false) ?: false
    }

    private fun readChoice(setting: EtaSetting): String {
        val stored = localPreferences()?.getString(setting.key, null).orEmpty()
        return stored.ifBlank { setting.default.ifBlank { setting.options.first() } }
    }

    /** 返回规范化后的取值，无法解析时返回 null。 */
    private fun normalize(setting: EtaSetting, requested: String): String? = when (setting.kind) {
        Kind.SWITCH -> when (requested.lowercase()) {
            "on", "true", "1", "yes", "开", "开启", "打开" -> "on"
            "off", "false", "0", "no", "关", "关闭" -> "off"
            else -> null
        }
        Kind.CHOICE -> requested.takeIf { it in setting.options }
    }

    private fun write(setting: EtaSetting, value: String): Boolean {
        when (setting.kind) {
            Kind.SWITCH -> {
                val enabled = value == "on"
                when (setting.key) {
                    MEMORY_ENABLED -> runBlocking(Dispatchers.IO) { SettingsDataStore.setMemoryEnabled(enabled) }
                    Prefs.Keys.AGENT_TTS_AUTO_READ -> ReplyReadAloudPreference.setAutoRead(enabled)
                    Prefs.Keys.AGENT_TTS_STREAM_SYNTH -> ReplyReadAloudPreference.setStreamSynthesis(enabled)
                    else -> if (!commitLocal(setting.key, enabled)) return false
                }
            }
            Kind.CHOICE -> if (!commitLocal(setting.key, value)) return false
        }
        if (setting.key in Prefs.Keys.LOCAL_AGENT_KEYS) {
            // 本地值是事实源，写回远端让 Hook 进程拿到一致配置；框架离线时静默跳过。
            runCatching { Prefs.reconcileAgentPreferences(EtaApp.serviceInstance) }
        }
        return true
    }

    private fun commitLocal(key: String, value: Boolean): Boolean {
        val preferences = localPreferences() ?: return false
        return runCatching { preferences.edit().putBoolean(key, value).commit() }.getOrDefault(false)
    }

    private fun commitLocal(key: String, value: String): Boolean {
        val preferences = localPreferences() ?: return false
        return runCatching { preferences.edit().putString(key, value).commit() }.getOrDefault(false)
    }

    private fun localPreferences() = Prefs.localAgentPreferences()

    private fun error(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private companion object {
        /** 长期记忆总开关不在 SharedPreferences，走 DataStore 的 `memory_enabled`。 */
        const val MEMORY_ENABLED = "memory_enabled"
    }
}
