package io.github.mangi.eta.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.mangi.eta.agent.web.WebToolsRuntimeConfig
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.DeepSeekWebConfig
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.WebToolsCredentials
import io.github.mangi.eta.data.model.WebToolsSettings
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object WebToolsSettingsRepository {
    private const val PREFS_NAME = "eta_web_tools_secrets"
    private const val KEY_CREDENTIALS = "credentials"
    private const val KEYSTORE_ALIAS = "eta_web_tools_credentials_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun settingsFlow(): Flow<WebToolsSettings> = SettingsDataStore.webToolsSettingsFlow()

    suspend fun settings(): WebToolsSettings = SettingsDataStore.webToolsSettings()

    suspend fun save(settings: WebToolsSettings) = SettingsDataStore.setWebToolsSettings(settings)

    suspend fun credentials(context: Context): WebToolsCredentials = withContext(Dispatchers.IO) {
        val encoded = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CREDENTIALS, null) ?: return@withContext WebToolsCredentials()
        try {
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            json.decodeFromString<WebToolsCredentials>(
                cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8),
            )
        } catch (_: Exception) {
            throw WebToolsCredentialsUnavailable()
        }
    }

    suspend fun saveCredentials(context: Context, credentials: WebToolsCredentials) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.iv + cipher.doFinal(json.encodeToString(credentials).toByteArray(Charsets.UTF_8))
        check(
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CREDENTIALS, Base64.getEncoder().encodeToString(encrypted))
                .commit(),
        ) {
            "网页工具凭据保存失败"
        }
    }

    /**
     * 备份导出：解密当前凭据为明文键值（受「包含 API Key」开关控制，见调用方）。
     *
     * 只解密、不改本机状态；解密失败（KeyStore 异常等）按「没有可导出的凭据」处理，
     * 宁可让用户导入后重填，也不让备份操作因凭据问题整体失败。
     */
    suspend fun exportForBackup(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        val credentials = runCatching { credentials(context) }.getOrNull() ?: return@withContext emptyMap()
        buildMap {
            if (credentials.deepSeekApiKey.isNotBlank()) put("deepSeekApiKey", credentials.deepSeekApiKey)
            if (credentials.customSearchApiKey.isNotBlank()) put("customSearchApiKey", credentials.customSearchApiKey)
            if (credentials.customFetchApiKey.isNotBlank()) put("customFetchApiKey", credentials.customFetchApiKey)
        }
    }

    /**
     * 备份导入：把快照写回 Keystore 加密存储。
     *
     * 只在备份确实携带凭据段时调用（空表 = 备份没带或已脱敏，必须保留本机，见清单 KDoc）。
     * 缺失的键视为「备份里就是空」，与用户在设置里手动清空同义；加密失败直接抛，
     * 不留下静默的半份凭据。
     */
    suspend fun restoreFromBackup(context: Context, snapshot: Map<String, String>) = withContext(Dispatchers.IO) {
        saveCredentials(
            context,
            WebToolsCredentials(
                deepSeekApiKey = snapshot["deepSeekApiKey"].orEmpty().trim(),
                customSearchApiKey = snapshot["customSearchApiKey"].orEmpty().trim(),
                customFetchApiKey = snapshot["customFetchApiKey"].orEmpty().trim(),
            ),
        )
    }

    /** 查找模型提供商中已配置的 DeepSeek API Key 与 Base URL，供网页工具零配置复用。 */
    suspend fun deepSeekProviderCredentials(): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val builtin = ProviderRepository.providerById(BuiltinProviders.DEEPSEEK_ID)
                ?.takeIf { it.apiKey.isNotBlank() }
            val provider = builtin ?: ProviderRepository.allProviders().firstOrNull {
                ProviderSourceRegistry.resolve(it) == ProviderSourceTypes.DEEPSEEK && it.apiKey.isNotBlank()
            }
            provider?.let { it.apiKey.trim() to it.baseUrl.trim() }
        }.getOrNull()
    }

    suspend fun resolveRuntimeConfig(context: Context): WebToolsRuntimeConfig = withContext(Dispatchers.IO) {
        val settings = runCatching { settings() }.getOrDefault(WebToolsSettings())
        val credentials = runCatching { credentials(context) }.getOrDefault(WebToolsCredentials())
        val providerDeepSeek = deepSeekProviderCredentials()
        val deepSeekApiKey = credentials.deepSeekApiKey.trim().ifEmpty {
            providerDeepSeek?.first.orEmpty()
        }
        val configuredDeepSeekUrl = settings.deepSeek.baseUrl.trim()
        val deepSeekBaseUrl = when {
            configuredDeepSeekUrl.isNotEmpty() && configuredDeepSeekUrl != DeepSeekWebConfig.DEFAULT_BASE_URL ->
                configuredDeepSeekUrl
            providerDeepSeek != null && providerDeepSeek.second.isNotEmpty() ->
                providerDeepSeek.second
            configuredDeepSeekUrl.isNotEmpty() -> configuredDeepSeekUrl
            else -> DeepSeekWebConfig.DEFAULT_BASE_URL
        }
        WebToolsRuntimeConfig(
            searchBackend = settings.searchBackend,
            fetchBackend = settings.fetchBackend,
            deepSeekBaseUrl = deepSeekBaseUrl,
            deepSeekApiKey = deepSeekApiKey,
            customSearch = settings.customSearch,
            customSearchApiKey = credentials.customSearchApiKey.trim(),
            customFetch = settings.customFetch,
            customFetchApiKey = credentials.customFetchApiKey.trim(),
        )
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEYSTORE_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }
}

internal class WebToolsCredentialsUnavailable : Exception("网页工具凭据无法解密，请重新填写并保存")
