package io.github.mangi.eta.data.db

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
 * provider API key 的静态加密（Keystore AES/GCM）。
 *
 * 本类不持有任何文件状态：密文就存在 `model_providers.api_key` 列里，Keystore 密钥
 * 不可导出。与 [VoiceCatalogSecretStore]（语音域）同一套密码学形态，密钥别名独立。
 *
 * 值格式版本化，表结构不变：密文带 [PREFIX] 前缀落库。
 * - 读到带前缀 → 解密；解不开（换机 / 清过 Keystore）按「未配置」返回空串，用户重填——
 *   宁可丢 key，也不把残缺密文当明文发出去。
 * - 读到不带前缀 → 旧版明文原样返回，存量行免迁移即可读；下次落库（toEntity）或
 *   启动重加密（ProviderRepository.reencryptLegacyApiKeys）时转为密文。
 * - 加密失败（无 Keystore 的环境，如 Robolectric 单测）退化为原样存储并打 warn：
 *   功能可用性优先，此时该列与旧版行为一致。
 */
internal object ProviderSecretCipher {
    private const val PREFIX = "enc1."
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "eta_provider_secret_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    /** AAD 绑定列语义，防止密文被挪作他用。 */
    private const val AAD = "model_providers.api_key"

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isBlank()) return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        }.getOrElse {
            AndroidAgentLogger.warn("Provider api key encryption unavailable: ${it.javaClass.simpleName}")
            plain
        }
    }

    fun decrypt(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        return runCatching {
            val payload = Base64.decode(value.substring(PREFIX.length), Base64.NO_WRAP)
            require(payload.size > GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, payload, 0, GCM_IV_BYTES),
            )
            cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
            cipher.doFinal(payload, GCM_IV_BYTES, payload.size - GCM_IV_BYTES).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

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
}
