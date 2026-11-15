package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.SessionGatewayRule
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object RuntimeConfigRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun selectedProviderIdFlow() = SettingsDataStore.selectedProviderIdFlow()

    fun selectedModelIdFlow() = SettingsDataStore.selectedModelIdFlow()

    suspend fun selectedProvider(): ProviderSetting? {
        val settings = ProviderRepository.repairSelection()
        return settings.selectedProviderId?.let { ProviderRepository.providerById(it) }
    }

    suspend fun setSelectedProviderId(id: String?) {
        val settings = SettingsDataStore.settings()
        val provider = id?.let { ProviderRepository.providerById(it) }
            ?.takeIf { it.isEnabled }
        val activeModel = provider
            ?.takeIf { it.id == settings.selectedProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedModelId && it.isEnabled }
        val rememberedModelId = provider?.let {
            SettingsDataStore.selectedModelIdForProvider(it.id)
        }
        val model = activeModel ?: provider?.selectedOrFirstModel(rememberedModelId)
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun setSelectedModelId(id: String?) {
        val provider = id?.let { ProviderRepository.providerByModelId(it) }
            ?.takeIf { it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == id && it.isEnabled }
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun setSelectedModelIdIfPresent(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val provider = ProviderRepository.providerByModelId(id)?.takeIf { it.isEnabled } ?: return false
        val model = provider.models.firstOrNull { it.id == id && it.isEnabled } ?: return false
        setSelectedModelId(model.id)
        return true
    }

    suspend fun currentRuntimeConfig(): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        val settings = ProviderRepository.repairSelection()
        val provider = settings.selectedProviderId?.let { ProviderRepository.providerById(it) } ?: return null
        val model = provider.selectedOrFirstModel(settings.selectedModelId) ?: return null
        return buildRuntimeConfig(provider, model, AssistantRepository.active()).copy(
            errorReconnectPolicy = settings.errorReconnectPolicy.persistedValue,
        )
    }

    suspend fun oauthRuntimeConfigFor(
        providerId: String,
        authMode: String,
        modelName: String,
    ): AgentModelClient.ModelConfig? {
        if (!ProviderAuthModes.isOAuth(authMode)) return null
        val provider = ProviderRepository.providerById(providerId)
            ?.takeIf { it.isEnabled && it.authMode == authMode } ?: return null
        val model = provider.models.firstOrNull { it.isEnabled && it.modelId == modelName } ?: return null
        return buildRuntimeConfig(provider, model)
    }

    suspend fun syncToRemotePreferences(service: XposedService?): Boolean {
        val prefs = Prefs.remotePreferencesForUi(service) ?: return false
        val config = currentRuntimeConfig() ?: return clearRuntimeConfig(prefs)
        return writeRuntimeConfig(prefs, config)
    }

    /**
     * 启动级兜底：合并内置 provider、修复失效选择、把运行时配置同步到 LSPosed 远端。
     *
     * 必须自持 [withContext]：链条里的 [ProviderRepository.allProviders] 在 suspend 路径
     * 上按调用方上下文执行 `toDomain()`（每个 provider 一次 [ProviderSecretCipher.decrypt]
     * Keystore binder 往返），`syncToRemotePreferences` 末端又是 RemotePreferences 的
     * `commit()` 同步 binder 写。调用方普遍是页面 `LaunchedEffect`（主线程），不加这个
     * 切换时整条链落在转场动画窗口里——真机 trace 实证同款链路曾造成转场起步帧连续
     * D 状态与 binder 往返（SettingsScreen 已先挪 IO，此处收敛到唯一出口）。
     */
    suspend fun ensureDefaults(service: XposedService?) = withContext(Dispatchers.IO) {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.repairSelection()
        syncToRemotePreferences(service)
    }

    fun runtimeConfigJson(config: AgentModelClient.ModelConfig): String =
        json.encodeToString(
            if (config.authMode == ProviderAuthModes.API_KEY) config else config.copy(apiKey = ""),
        )

    fun buildRuntimeConfig(
        provider: ProviderSetting,
        model: Model,
        assistant: io.github.mangi.eta.data.model.AssistantProfile? = null,
    ): AgentModelClient.ModelConfig {
        io.github.mangi.eta.data.model.RemovedProviderPolicy.requireSupported(provider)
        val systemPrompt = assistant?.let {
            io.github.mangi.eta.data.model.AssistantPrompt.build(it.name, it.prompt)
        }?.ifBlank { BuiltinProviders.DEFAULT_SYSTEM_PROMPT }
            ?: BuiltinProviders.DEFAULT_SYSTEM_PROMPT
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val profile = io.github.mangi.eta.data.provider.ProviderConnectionProfile.forAuthMode(provider.authMode)
        val endpointMode = profile?.endpointMode ?: when (provider) {
            is OpenAiCompatibleProviderSetting -> provider.endpointMode
            is CustomProviderSetting -> provider.endpointMode
            is AnthropicProviderSetting -> ""
        }
        val inferOpenAiCatalog = sourceType == io.github.mangi.eta.data.model.ProviderSourceTypes.CUSTOM &&
            endpointMode == OpenAiEndpointMode.RESPONSES
        val reasoningCapabilities = ReasoningCapabilityResolver.resolve(
            sourceType = if (inferOpenAiCatalog) {
                io.github.mangi.eta.data.model.ProviderSourceTypes.OPENAI
            } else {
                sourceType
            },
            model = model,
            inferExactCatalogModel = inferOpenAiCatalog,
        )
        val reasoningEffort = reasoningCapabilities?.normalize(
            model.preferredReasoningEffort ?: ReasoningEffort.OFF,
        ) ?: ReasoningEffort.OFF
        val sessionRule = SessionGatewayRule.decode(provider.sessionGatewayJson)
        return AgentModelClient.ModelConfig(
            assistantId = assistant?.id.orEmpty(),
            providerId = provider.id,
            providerName = provider.name,
            providerType = profile?.providerType ?: provider.runtimeProviderType,
            providerSourceType = sourceType,
            baseUrl = profile?.baseUrl ?: provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim()
                .takeIf { provider.authMode == io.github.mangi.eta.data.model.ProviderAuthModes.API_KEY }
                .orEmpty(),
            authMode = provider.authMode,
            model = model.modelId.trim(),
            modelDisplayName = model.displayName.trim(),
            contextWindow = model.effectiveContextWindow,
            systemPrompt = systemPrompt,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
            openAiEndpointMode = endpointMode,
            responsesStripReasoningStatus = provider.responsesStripReasoningStatus,
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
            thinkingEnabled = reasoningEffort.enablesReasoning,
            reasoningEffort = reasoningEffort,
            reasoningCapabilities = reasoningCapabilities,
            customHeaders = provider.customHeaders + model.customHeaders,
            customBody = provider.customBody + model.customBody,
            sessionModelPattern = sessionRule.modelPattern,
            sessionPathPattern = sessionRule.pathPattern,
            sessionKeyField = sessionRule.safeKeyField(),
            supportsVision = model.supportsVision,
            supportsVideo = model.supportsVideo,
        )
    }

    private fun writeRuntimeConfig(
        prefs: SharedPreferences,
        config: AgentModelClient.ModelConfig,
    ): Boolean =
        runCatching {
            prefs.edit()
                .putString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON, runtimeConfigJson(config))
                .commit()
        }.getOrDefault(false)

    private fun clearRuntimeConfig(prefs: SharedPreferences): Boolean =
        runCatching {
            prefs.edit()
                .remove(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
                .commit()
        }.getOrDefault(false)

    internal suspend fun configForProviderAndModel(
        providerId: String,
        modelId: String,
        assistant: io.github.mangi.eta.data.model.AssistantProfile? = null,
    ): AgentModelClient.ModelConfig? {
        val provider = ProviderRepository.providerById(providerId)?.takeIf { it.isEnabled } ?: return null
        val model = provider.models.firstOrNull { it.id == modelId && it.isEnabled } ?: return null
        return buildRuntimeConfig(provider, model, assistant).copy(
            errorReconnectPolicy = SettingsDataStore.settings().errorReconnectPolicy.persistedValue,
        )
    }
}
