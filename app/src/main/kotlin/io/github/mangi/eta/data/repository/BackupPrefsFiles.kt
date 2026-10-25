package io.github.mangi.eta.data.repository

import android.content.Context

/**
 * 备份里的「独立 SharedPreferences 文件」快照。
 *
 * 语音、浏览器这类配置不在 [io.github.mangi.eta.data.datastore.SettingsDataStore] 的 DataStore 里，
 * 各自躺在独立的 prefs 文件中，换机同样会丢。这里统一搬它们：
 * 值按 `b: / i: / l: / f: / s:` 前缀编码，与 `Prefs.exportAgentPreferences` 保持同一约定。
 *
 * 安全边界：
 * - 只认调用方显式列出的文件名。[restore] 收到备份里其它文件名一律忽略，避免越权改写别的 prefs；
 * - StringSet 不搬（本项目未使用该类型）；遇到未知类型跳过该键，不猜格式；
 * - 这里不碰任何 KeyStore 加密的凭据（如 `VoiceCatalogSecretStore` 的 SK），密钥不可跨设备迁移。
 */
internal object BackupPrefsFiles {

    /** 语音：豆包 ASR / 克隆配置、离线语音包开关与选型、音色 catalog 的 key id 与 project。 */
    val VOICE = listOf("doubao_voice", "offline_speech", "voice_catalog_id")

    /**
     * 语音配置里以**明文**保存的密钥（豆包 ASR / 克隆 Key）。
     *
     * 它们和提供商 API Key 同等敏感，受「包含 API Key」开关统一控制：
     * 不勾时从快照里剔除，导入后语音配置还在，只是密钥要重填。
     * 非密钥项（开关、资源 ID、音色 id）照常搬。
     */
    val VOICE_SECRET_KEYS = setOf("asr_key", "clone_key", "voice_conversation_asr_key")

    /**
     * 浏览器：User-Agent、视口、空闲超时。
     *
     * 登录态与 Cookie 在 WebView 自己的存储里，不在这个文件，因此搬它不会带走任何凭据。
     */
    val BROWSER = listOf("browser_prefs")

    /**
     * 读取指定 prefs 文件的全量键值；空文件不进快照。
     *
     * [omitKeys] 里的键直接跳过，用于把明文密钥挡在包外（见 [VOICE_SECRET_KEYS]）。
     */
    fun capture(
        context: Context,
        names: Collection<String>,
        omitKeys: Set<String> = emptySet(),
    ): Map<String, Map<String, String>> {
        val snapshot = linkedMapOf<String, Map<String, String>>()
        for (name in names) {
            val values = linkedMapOf<String, String>()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (key in omitKeys) return@forEach
                val encoded = when (value) {
                    is Boolean -> "b:$value"
                    is Int -> "i:$value"
                    is Long -> "l:$value"
                    is Float -> "f:$value"
                    is String -> "s:$value"
                    else -> null
                }
                if (encoded != null) values[key] = encoded
            }
            if (values.isNotEmpty()) snapshot[name] = values
        }
        return snapshot
    }

    /**
     * 用快照整体替换指定 prefs 文件。
     *
     * 先 clear 再写回，保证「恢复到备份那一刻」；解码失败的单个键会被丢弃，
     * 不会把半截数据写进配置。落盘用 commit，失败直接抛，不留下静默的半份配置。
     */
    fun restore(context: Context, snapshot: Map<String, Map<String, String>>, names: Collection<String>) {
        for (name in names) {
            val values = snapshot[name] ?: continue
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for ((key, encoded) in values) {
                if (key.isBlank() || encoded.length < 2 || encoded[1] != ':') continue
                val payload = encoded.substring(2)
                when (encoded[0]) {
                    'b' -> editor.putBoolean(key, payload.toBooleanStrictOrNull() ?: continue)
                    'i' -> editor.putInt(key, payload.toIntOrNull() ?: continue)
                    'l' -> editor.putLong(key, payload.toLongOrNull() ?: continue)
                    'f' -> editor.putFloat(key, payload.toFloatOrNull() ?: continue)
                    's' -> editor.putString(key, payload)
                }
            }
            check(editor.commit()) { "备份中的配置未能写入：$name" }
        }
    }
}
