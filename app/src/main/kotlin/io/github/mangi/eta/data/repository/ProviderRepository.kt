package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ProviderSecretCipher
import io.github.mangi.eta.data.db.ProviderWithModelsSeed
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.db.toModelEntities
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.RemovedProviderPolicy
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.model.withApiKey
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.model.withSortOrder
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object ProviderRepository {
    /** 配置与模型共用修改锁，避免旧连接的目录在认证方式切换后落库。 */
    internal val mutationMutex = Mutex()

    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        // Rebind on initialization: Robolectric recreates Application between tests.
        // Keeping the first Application would send OAuth cleanup to stale preferences
        // while the database singleton already belongs to the current application.
        applicationContext = context.applicationContext
        ProviderCredentialRepository.init(context)
        // 旧版 Codex 登录把令牌明文保存在此，迁移后一次性清除。
        runCatching {
            context.getSharedPreferences("eta_oauth_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    /**
     * [toDomain] 的映射段必须挂在 flowOn 的上游：Room 内部的 flowOn 只包住查询本身，
     * 其后的映射按收集方上下文执行——设置页用 `collectAsState` 在主线程收集，
     * 不加这个 flowOn 的话每个 provider 的 [ProviderSecretCipher.decrypt]（Keystore
     * binder 往返）会全部落在主线程重组里（真机 trace 实证：入栈转场窗口
     * ~15 笔同步 keystore2 事务）。
     */
    fun providersFlow(): Flow<List<ProviderSetting>> =
        dao().providersFlow().map { providers ->
            providers
                .map { it.toDomain().withSpeechCatalog() }
                .filterNot { RemovedProviderPolicy.isRemoved(it) }
                .sortedBy(ProviderSetting::sortOrder)
        }.flowOn(Dispatchers.Default)

    fun settingsFlow(): Flow<Settings> =
        SettingsDataStore.settingsFlow()

    suspend fun settings(): Settings =
        SettingsDataStore.settings()

    suspend fun allProviders(): List<ProviderSetting> =
        dao().providers()
            .map { it.toDomain().withSpeechCatalog() }
            .filterNot { RemovedProviderPolicy.isRemoved(it) }
            .sortedBy(ProviderSetting::sortOrder)

    suspend fun providerById(id: String): ProviderSetting? =
        dao().providerById(id)?.toDomain()?.withSpeechCatalog()?.takeUnless { RemovedProviderPolicy.isRemoved(it) }

    suspend fun providerByModelId(modelId: String): ProviderSetting? =
        dao().providerByModelId(modelId)?.toDomain()?.takeUnless { RemovedProviderPolicy.isRemoved(it) }

    suspend fun addProvider(provider: ProviderSetting): ProviderSetting {
        val nextOrder = (allProviders().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val added = provider.withSortOrder(nextOrder)
        replaceProvider(added)
        // 不自动切换到新添加的 provider，保持当前选择不变。
        return added
    }

    suspend fun updateProvider(provider: ProviderSetting) {
        RemovedProviderPolicy.requireSupported(provider)
        mutationMutex.withLock {
            val previous = requireNotNull(providerById(provider.id)) { "Provider 不存在" }
            if (previous.authMode != provider.authMode) {
                forgetCredentials(provider.id)
            }
            require(dao().updateProvider(provider.toEntity()) == 1) { "Provider 不存在" }
        }
        repairSelection()
    }

    internal suspend fun replaceModels(providerId: String, models: List<Model>) {
        val provider = requireNotNull(providerById(providerId)) { "Provider 不存在" }
        dao().replaceModels(
            providerId = providerId,
            models = provider.withModels(models).toModelEntities(),
        )
    }

    suspend fun deleteProvider(id: String) {
        val deleted = mutationMutex.withLock {
            val provider = providerById(id) ?: return@withLock false
            if (provider.isBuiltIn) return@withLock false
            forgetCredentials(id)
            dao().deleteProvider(id)
            true
        }
        if (!deleted) return
        SettingsDataStore.clearSelectedModelIdForProvider(id)
        repairSelection()
    }

    suspend fun deleteProviders(ids: Collection<String>) {
        ids.distinct().forEach { deleteProvider(it) }
    }

    suspend fun copyProvider(id: String): ProviderSetting? {
        val source = providerById(id) ?: return null
        val nextOrder = (allProviders().maxOfOrNull { it.sortOrder } ?: -1) + 1
        val copy = source.deepCopy(
            id = newId(),
            name = "${source.name} 副本",
            sortOrder = nextOrder,
            builtIn = false,
        )
        replaceProvider(copy)
        // 复制 provider 时不自动切换当前模型。
        return copy
    }

    suspend fun resetBuiltIn(id: String) {
        val builtIn = BuiltinProviders.providerById(id) ?: return
        val current = providerById(id)
        forgetCredentials(id)
        val restored = seedOfficialModelsIfEmpty(
            current
            ?.let { existing ->
                // 只有 API Key 认证的配置才把旧 apiKey 带回来；OAuth 账号的 apiKey 是占位，
                // 不能当凭据复制。takeIf 的 it 是 apiKey（String），所以继续要显式命名外层。
                val apiKey = existing.apiKey
                    .takeIf { existing.authMode == ProviderAuthModes.API_KEY }
                    .orEmpty()
                builtIn.withApiKey(apiKey).withSortOrder(existing.sortOrder)
            }
            ?: builtIn
        )
        replaceProvider(restored)
        repairSelection()
    }

    /** Runs on startup and backup restore. Does not contact any OAuth endpoint. */
    private suspend fun removeRetiredProviders() {
        dao().providers().forEach { record ->
            val provider = record.provider
            if (RemovedProviderPolicy.isRemoved(provider.baseUrl, provider.endpointMode, provider.authMode)) {
                forgetCredentials(provider.id)
                dao().deleteProvider(provider.id)
                SettingsDataStore.clearSelectedModelIdForProvider(provider.id)
            }
        }
    }

    @Volatile
    private var legacyApiKeysReencrypted = false

    /**
     * 启动级兜底：把仍以明文落库的 API key 行重写为密文（[ProviderSecretCipher]）。
     *
     * 常规写入在 toEntity 落库时即加密；这条兜底覆盖两个绕过 toEntity 的入口——
     * 老版本升级的存量行、备份恢复的直插实体（replaceAll 直接写 entity）。
     * providers 表只有个位数行，首轮扫描很轻，完成后置位跳过后续调用。
     */
    private suspend fun reencryptLegacyApiKeys() {
        if (legacyApiKeysReencrypted) return
        runCatching {
            val dao = dao()
            var dirty = false
            dao.providers().forEach { record ->
                val provider = record.provider
                val key = provider.apiKey
                if (provider.authMode == ProviderAuthModes.API_KEY && key.isNotBlank() &&
                    !ProviderSecretCipher.isEncrypted(key)
                ) {
                    dao.updateProvider(provider.copy(apiKey = ProviderSecretCipher.encrypt(key)))
                    dirty = true
                }
            }
            legacyApiKeysReencrypted = true
            if (dirty) AndroidAgentLogger.warn("Provider api keys re-encrypted at rest")
        }
    }

    suspend fun ensureBuiltInsMerged() {
        removeRetiredProviders()
        reencryptLegacyApiKeys()
        val current = allProviders()
        val catalogIds = BuiltinProviders.PROVIDERS.mapTo(mutableSetOf()) { it.id }
        val stale = current.filter { it.isBuiltIn && it.id !in catalogIds }
        stale.forEach { provider ->
            dao().deleteProvider(provider.id)
            SettingsDataStore.clearSelectedModelIdForProvider(provider.id)
        }
        val remaining = if (stale.isEmpty()) current else allProviders()
        if (remaining.isEmpty()) {
            if (BuiltinProviders.PROVIDERS.isNotEmpty()) {
                insertProviders(BuiltinProviders.PROVIDERS.map(::seedOfficialModelsIfEmpty))
            }
            repairSelection()
            return
        }

        val existingIds = remaining.mapTo(mutableSetOf()) { it.id }
        val missing = BuiltinProviders.PROVIDERS.filterNot { it.id in existingIds }
        if (missing.isNotEmpty()) {
            insertProviders(missing.map(::seedOfficialModelsIfEmpty))
        }
        repairSelection()
    }

    suspend fun repairSelection(): Settings {
        val providers = allProviders()
        val settings = SettingsDataStore.settings()
        val selectedProvider = providers.firstOrNull { it.id == settings.selectedProviderId && it.isEnabled }
            ?: providers.firstOrNull { it.isEnabled }
        val activeModel = selectedProvider
            ?.takeIf { it.id == settings.selectedProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedModelId && it.isEnabled }
        val rememberedModelId = selectedProvider?.let {
            SettingsDataStore.selectedModelIdForProvider(it.id)
        }
        val selectedModel = activeModel ?: selectedProvider?.selectedOrFirstModel(rememberedModelId)
        val repaired = settings.copy(
            selectedProviderId = selectedProvider?.id,
            selectedModelId = selectedModel?.id,
        )
        SettingsDataStore.setSelection(repaired.selectedProviderId, repaired.selectedModelId)
        return repaired
    }

    fun newId(): String = UUID.randomUUID().toString()

    private suspend fun forgetCredentials(providerId: String) = withContext(Dispatchers.IO) {
        ProviderCredentialRepository.forget(providerId)
    }

    private fun dao() =
        EtaDatabase.get(appContext()).providerDao()

    internal fun context(): Context = appContext()

    private fun appContext(): Context {
        check(::applicationContext.isInitialized) {
            "ProviderRepository.init(context) must be called in Application.onCreate()"
        }
        return applicationContext
    }

    private suspend fun replaceProvider(provider: ProviderSetting) {
        RemovedProviderPolicy.requireSupported(provider)
        dao().replaceProvider(
            provider = provider.toEntity(),
            models = provider.toModelEntities(),
        )
    }

    private suspend fun insertProviders(providers: List<ProviderSetting>) {
        dao().insertProvidersWithModels(
            providers.map { provider ->
                ProviderWithModelsSeed(
                    provider = provider.toEntity(),
                    models = provider.toModelEntities(),
                )
            }
        )
    }

    private fun seedOfficialModelsIfEmpty(provider: ProviderSetting): ProviderSetting {
        if (provider.models.isNotEmpty() || ProviderAuthModes.isOAuth(provider.authMode)) return provider
        val seededModels = OfficialModelCatalog.modelsForProvider(provider)
        return if (seededModels.isEmpty()) provider else provider.withModels(seededModels)
    }

    private fun ProviderSetting.deepCopy(
        id: String,
        name: String,
        sortOrder: Int,
        builtIn: Boolean,
    ): ProviderSetting {
        val copiedModels = models.mapIndexed { index, model ->
            model.copy(id = newId(), isBuiltIn = builtIn, sortOrder = index)
        }
        return when (this) {
            is OpenAiCompatibleProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )

            is AnthropicProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )

            is CustomProviderSetting -> copy(
                id = id,
                name = name,
                sortOrder = sortOrder,
                isBuiltIn = builtIn,
                models = copiedModels,
            )
        }
    }

    private fun ProviderSetting.withSpeechCatalog(): ProviderSetting =
        withModels(SpeechSynthesisModels.mergeCatalog(this))
}
