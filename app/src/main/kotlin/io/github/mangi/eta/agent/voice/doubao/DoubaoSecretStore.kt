package io.github.mangi.eta.agent.voice.doubao

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.mangi.eta.core.AndroidAgentLogger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 豆包语音凭据的静态加密存储：Keystore AES/GCM，AAD 绑定槽位名。
 *
 * 与 [VoiceCatalogSecretStore] 同一套密码学形态；区别在于那里一个 prefs 文件只存一枚
 * catalog SK（键名固定 `secret`），这里一个文件按槽位存多枚 key。Keystore 别名独立，
 * 互不影响（不动 SK 那条已上线路径）。明文永不落盘：读不到 / 解不开按 null 处理，
 * 调用方回落到「未配置」，用户重填即可。
 *
 * 槽位名沿用 prefs 里的历史键名（[SLOT_KEYS]）：备份导出注入与恢复收编都按同名对齐，
 * 恢复端把明文写回 prefs 后由 [DoubaoVoiceConfig.load] 的迁移逻辑读出入库并移除明文。
 */
internal class DoubaoSecretStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    /** 读取一个槽位；未配置或解密失败返回 null。不带 [PREFIX] 的值（无 Keystore 环境
     *  的降级明文 / 旧格式）原样返回。 */
    @Synchronized
    fun read(slot: String): String? {
        val encoded = preferences.getString(slot, null) ?: return null
        if (!encoded.startsWith(PREFIX)) return encoded.takeIf { it.isNotBlank() }
        return runCatching {
            val payload = Base64.decode(encoded.substring(PREFIX.length), Base64.NO_WRAP)
            require(payload.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES),
            )
            cipher.updateAAD(slot.toByteArray(Charsets.UTF_8))
            cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES)
                .toString(Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 写入一个槽位；blank 等价删除（与旧版「存空串即清除」语义一致）。
     *  加密失败（无 Keystore 的环境，如 Robolectric 单测）退化为明文落盘并打 warn：
     *  功能可用性优先，此时该槽与旧版行为一致。 */
    @Synchronized
    fun save(slot: String, value: String) {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            clear(slot)
            return
        }
        val encoded = runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(slot.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        }.getOrElse {
            AndroidAgentLogger.warn("Doubao voice secret encryption unavailable: ${it.javaClass.simpleName}")
            normalized
        }
        check(
            preferences.edit()
            .putString(slot, encoded)
            .commit()
        ) { "语音凭据保存失败" }
    }

    @Synchronized
    fun clear(slot: String) {
        check(preferences.edit().remove(slot).commit()) { "语音凭据删除失败" }
    }

    /**
     * 备份恢复后对齐槽位：快照里没带的槽位清掉（对齐旧版「没带密钥就清 prefs 键」的
     * 语义）；带了的槽位保持——对应明文此时已写进 prefs，等 [DoubaoVoiceConfig.load]
     * 的迁移逻辑收编进加密存储。快照里根本没有 `doubao_voice` 文件时不调用本方法，
     * 本机槽位原样保留（与旧版「旧备份没带该文件就保留本机设置」语义一致）。
     */
    @Synchronized
    fun syncSlotsWithBackup(presentKeys: Set<String>) {
        for (slot in SLOT_KEYS) {
            if (slot !in presentKeys && preferences.contains(slot)) clear(slot)
        }
    }

    /**
     * 备份导出用：解密后的明文按 [io.github.mangi.eta.data.repository.BackupPrefsFiles]
     * 的 `s:` 前缀编码返回，仅供勾选「包含 API Key」的导出注入快照（zip 里的明文与
     * 旧版行为一致，受同一开关控制）。恢复端写回 prefs 后重新加密入库。
     */
    @Synchronized
    fun exportForBackup(): Map<String, String> =
        SLOT_KEYS.mapNotNull { slot ->
            read(slot)?.let { slot to "s:$it" }
        }.toMap()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    internal companion object {
        /** prefs 历史键名即槽位名（AAD 同名绑定）。 */
        const val SLOT_ASR_KEY = "asr_key"
        private const val PREFIX = "enc1."
        const val SLOT_CLONE_KEY = "clone_key"
        const val SLOT_CONVERSATION_ASR_KEY = "voice_conversation_asr_key"
        val SLOT_KEYS = listOf(SLOT_ASR_KEY, SLOT_CLONE_KEY, SLOT_CONVERSATION_ASR_KEY)

        private const val PREFERENCES_NAME = "doubao_voice_secret"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "eta_doubao_voice_secret_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
