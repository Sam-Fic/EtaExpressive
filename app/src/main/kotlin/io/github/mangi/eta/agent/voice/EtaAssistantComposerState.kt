package io.github.mangi.eta.agent.voice

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.model.supportsGptSpeedBinding
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.MainAgentSpeedDefaultsRepository
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.GptSpeedModePolicy
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.toOutboundModelImage
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 入口浮层输入区的状态持有者：复用应用内（进程单例）的 Provider / 助手 / 模型仓库，
 * 但草稿与附件完全独立于 [io.github.mangi.eta.ui.app.AgentAppState]，不影响应用内当前会话。
 *
 * 覆盖：模型与助手选择、思考档位、GPT 速度档位、上下文用量圆环所需数据，以及
 * 图片 / 视频 / 文件 / 文件夹 / 路径附件。所有对外可见字段都是 Compose 可观察状态，
 * 直接驱动 [io.github.mangi.eta.ui.components.Material3ComposerBody]。
 */
internal class EtaAssistantComposerState(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    /** 仅按 provider 唯一 ID + model 选择 ID 记忆速度档位，与应用内同一份本机偏好。 */
    private val speedDefaults: MainAgentSpeedDefaultsRepository? by lazy {
        runCatching {
            Prefs.localAgentPreferences()?.let(::MainAgentSpeedDefaultsRepository)
        }.getOrNull()
    }

    var providers by mutableStateOf<List<ProviderSetting>>(emptyList())
        private set
    var selectedProviderId by mutableStateOf<String?>(null)
        private set
    var selectedModelId by mutableStateOf<String?>(null)
        private set
    var assistantId by mutableStateOf(AssistantRepository.activeId.value)
        private set

    var reasoningEffort by mutableStateOf(ReasoningEffort.OFF)
        private set
    var availableReasoningEfforts by mutableStateOf<List<ReasoningEffort>>(emptyList())
        private set
    var gptSpeedMode by mutableStateOf(GptSpeedMode.NORMAL)
        private set

    var pendingImages by mutableStateOf<List<PendingImageUi>>(emptyList())
        private set
    var pendingFileReferences by mutableStateOf<List<PendingFileReferenceUi>>(emptyList())
        private set

    /**
     * 模型选择器状态：按**已解析**的 provider / model 投影。
     *
     * `SettingsDataStore` 里存的选择可能为空、或指向已停用/别名不一致的模型；应用内是靠
     * `ProviderRepository.repairSelection()` + `ProviderSetting.selectedOrFirstModel()` 兜底的。
     * 浮层没有模型选择入口，必须走同一套兜底，否则 selectedModel 为 null 就直接发不出去。
     */
    val modelPickerState: AgentModelPickerUiState
        get() = AgentModelPickerProjector.project(
            providers,
            selectedProviderRecord()?.id ?: selectedProviderId,
            selectedModelRecord()?.id ?: selectedModelId,
        )

    val selectedModel get() = modelPickerState.selectedModel

    val gptSpeedSupported: Boolean get() = selectedModel?.gptSpeedSupported == true

    /** 浮层没有历史/回执，圆环只显示上下文窗口。 */
    val usage: AgentContextUsageUi
        get() = AgentContextUsageUi(contextTokens = null, contextWindow = selectedModel?.contextWindow)

    /**
     * 与应用内 `AgentChatInputBar.canSend` 同义：**模型可用，且草稿确实有可发送内容**。
     *
     * 「有内容」这一项是语音入口能否出现的关键：`resolveChatComposerSendMode` 里
     * `canStartNewSend -> "send"`，否则 `"idle"`，而输入区尾部槽位只有在 `sendMode == "idle"`
     * 且无内容时才组合语音入口。之前这里写成「模型已选中」，空草稿也返回 true，尾部就永远
     * 被发送按钮占住 —— 表现是「浮层没有语音对话」。
     */
    fun canSend(hasDraftContent: Boolean): Boolean =
        hasDraftContent && !modelPickerState.isChanging && selectedModel != null

    init {
        observeRepositories()
        refreshModelBindings()
        // 浮层可能从没走过应用内 AgentAppState 的初始化路径，这里补上运行时选择的两步前置：
        // 合并内置 provider、把存储的默认选择修成有效值（与 RuntimeConfigRepository.currentRuntimeConfig 一致）。
        scope.launch {
            runCatching { ProviderRepository.ensureBuiltInsMerged() }
            runCatching { ProviderRepository.repairSelection() }
            withContext(Dispatchers.Main) { refreshModelBindings() }
        }
    }

    private fun observeRepositories() {
        scope.launch {
            ProviderRepository.providersFlow().distinctUntilChanged().collect { list ->
                withContext(Dispatchers.Main) {
                    providers = list
                    refreshModelBindings()
                }
            }
        }
        scope.launch {
            SettingsDataStore.selectedProviderIdFlow().distinctUntilChanged().collect { id ->
                withContext(Dispatchers.Main) {
                    if (id != selectedProviderId) {
                        selectedProviderId = id
                        refreshModelBindings()
                    }
                }
            }
        }
        scope.launch {
            SettingsDataStore.selectedModelIdFlow().distinctUntilChanged().collect { id ->
                withContext(Dispatchers.Main) {
                    if (id != selectedModelId) {
                        selectedModelId = id
                        refreshModelBindings()
                    }
                }
            }
        }
        scope.launch {
            AssistantRepository.activeId.collect { id ->
                withContext(Dispatchers.Main) {
                    if (id != assistantId) {
                        assistantId = id
                        refreshModelBindings()
                    }
                }
            }
        }
    }

    /** 存储选择失效/为空时退到第一个启用的 provider，与 `ProviderRepository.repairSelection()` 同规则。 */
    fun selectedProviderRecord(): ProviderSetting? =
        providers.singleOrNull { it.id == selectedProviderId && it.isEnabled }
            ?: providers.firstOrNull { it.isEnabled }

    /** 存储模型失效时退到该 provider 记住的模型，再到排序最靠前的启用模型。 */
    fun selectedModelRecord(): Model? =
        selectedProviderRecord()?.selectedOrFirstModel(selectedModelId)

    fun assistantProfile(): AssistantProfile? =
        AssistantRepository.profiles.value.firstOrNull { it.id == assistantId }
            ?: AssistantRepository.active()

    private fun refreshModelBindings() {
        val provider = selectedProviderRecord()
        val model = selectedModelRecord()
        val capabilities = if (provider != null && model != null) {
            runCatching {
                RuntimeConfigRepository.buildRuntimeConfig(provider, model, assistantProfile())
            }.getOrNull()?.reasoningCapabilities
        } else {
            null
        }
        availableReasoningEfforts = capabilities?.selectableEfforts.orEmpty()
        val requested = model?.preferredReasoningEffort ?: ReasoningEffort.OFF
        reasoningEffort = capabilities?.normalize(requested) ?: ReasoningEffort.OFF
        gptSpeedMode = if (provider != null && model != null && supportsGptSpeedBinding(provider, model)) {
            runCatching { speedDefaults?.modeFor(provider.id, model.id) }.getOrNull() ?: GptSpeedMode.NORMAL
        } else {
            GptSpeedMode.NORMAL
        }
    }

    /**
     * 选择模型：与会话内的 `AgentAppState.selectModel` 语义一致，只把「当前选择」持久化到
     * `SettingsDataStore`（不触碰任何会话状态），随后刷新思考档位与速度档位。
     */
    fun selectModel(providerId: String, modelId: String) {
        val provider = providers.singleOrNull { it.id == providerId && it.isEnabled } ?: return
        val model = provider.models.singleOrNull { it.id == modelId && it.isEnabled } ?: return
        if (selectedProviderId == provider.id && selectedModelId == model.id) return
        selectedProviderId = provider.id
        selectedModelId = model.id
        refreshModelBindings()
        scope.launch(Dispatchers.IO) {
            runCatching { SettingsDataStore.setSelection(provider.id, model.id) }
        }
    }

    fun selectAssistant(id: String) {
        if (id.isBlank() || id == assistantId) return
        val profile = AssistantRepository.profiles.value.firstOrNull { it.id == id } ?: return
        assistantId = profile.id
        refreshModelBindings()
        scope.launch(Dispatchers.IO) {
            runCatching { AssistantRepository.select(profile.id) }
        }
    }

    fun updateReasoningEffort(effort: ReasoningEffort) {
        if (effort == reasoningEffort) return
        reasoningEffort = effort
        val provider = selectedProviderRecord() ?: return
        val model = selectedModelRecord() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val current = ModelRepository.modelsByProvider(provider.id).firstOrNull { it.id == model.id }
                    ?: return@runCatching
                if (current.preferredReasoningEffort == effort) return@runCatching
                ModelRepository.saveModel(provider.id, current.copy(preferredReasoningEffort = effort))
            }
        }
    }

    fun cycleGptSpeedMode() {
        val provider = selectedProviderRecord() ?: return
        val model = selectedModelRecord() ?: return
        if (!supportsGptSpeedBinding(provider, model)) return
        val next = gptSpeedMode.next()
        gptSpeedMode = next
        scope.launch(Dispatchers.IO) {
            runCatching { speedDefaults?.remember(provider, model, next) }
        }
    }

    // ---- 附件 ----

    fun attachImage(uri: String) {
        scope.launch {
            try {
                val image = AgentImageCodec.fromReference(appContext, uri, source = "user_attach")
                if (image == null) {
                    withContext(Dispatchers.Main) { toast(R.string.state_ui_unable_to_read_this_image_please_try_again_or_us_d94978) }
                    return@launch
                }
                val preview = AgentImageCodec.previewFromReference(appContext, image) ?: image
                val pending = PendingImageUi(
                    id = "img-${UUID.randomUUID()}",
                    // 发送使用首次读取后的稳定引用，不再依赖 ROM Photo Picker URI 的授权生命周期。
                    uri = image.reference,
                    dataUrl = preview.reference,
                    mimeType = image.mimeType,
                )
                withContext(Dispatchers.Main) { pendingImages = pendingImages + pending }
            } finally {
                releasePersistableUriPermission(uri)
            }
        }
    }

    fun attachVideo(uri: String) {
        scope.launch {
            try {
                val parsed = Uri.parse(uri)
                val attachment = AgentVideoCodec.importFromUri(appContext, parsed)
                if (attachment == null) {
                    withContext(Dispatchers.Main) { toast(R.string.state_ui_unable_to_read_this_video) }
                    return@launch
                }
                val pending = PendingImageUi(
                    id = "vid-${UUID.randomUUID()}",
                    uri = attachment.file.absolutePath,
                    dataUrl = attachment.thumbnail.reference,
                    mimeType = attachment.mimeType,
                    isVideo = true,
                    durationMs = attachment.durationMs.takeIf { it > 0L },
                    byteSize = attachment.bytes,
                )
                withContext(Dispatchers.Main) { pendingImages = pendingImages + pending }
            } finally {
                releasePersistableUriPermission(uri)
            }
        }
    }

    fun attachFiles(uris: List<String>) {
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            uris.map { uri ->
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.File,
                )
            }
        }
    }

    fun attachFolder(uri: String) {
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            listOf(
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.Directory,
                ),
            )
        }
    }

    fun attachFilePath(path: String) {
        resolveAndAttachFileReferences {
            listOf(AgentFileReferenceGateway(AndroidAgentLogger).resolveAbsolutePath(path))
        }
    }

    fun removePendingImage(id: String) {
        pendingImages = pendingImages.filterNot { it.id == id }
    }

    fun removePendingFileReference(id: String) {
        pendingFileReferences = pendingFileReferences.filterNot { it.id == id }
    }

    fun clearAttachments() {
        pendingImages = emptyList()
        pendingFileReferences = emptyList()
    }

    private fun resolveAndAttachFileReferences(
        resolver: () -> List<AgentFileReferenceGateway.Resolution>,
    ) {
        scope.launch(Dispatchers.IO) {
            val resolutions = runCatching { resolver() }.getOrElse { emptyList() }
            val references = resolutions.mapNotNull {
                (it as? AgentFileReferenceGateway.Resolution.Success)?.reference
            }
            val failures = resolutions.mapNotNull {
                (it as? AgentFileReferenceGateway.Resolution.Failure)?.error
            }
            withContext(Dispatchers.Main) {
                val existing = pendingFileReferences.mapTo(mutableSetOf()) { it.reference.absolutePath }
                val additions = references
                    .distinctBy { it.absolutePath }
                    .filter { existing.add(it.absolutePath) }
                    .map { reference ->
                        PendingFileReferenceUi(id = "file-${UUID.randomUUID()}", reference = reference)
                    }
                if (additions.isNotEmpty()) {
                    pendingFileReferences = pendingFileReferences + additions
                }
                val message = when {
                    failures.size == 1 && references.isEmpty() -> failures.single().userMessage
                    failures.isNotEmpty() -> appContext.resources.getQuantityString(
                        R.plurals.file_references_added_with_failures,
                        failures.size,
                        additions.size,
                        failures.size,
                    )
                    additions.isEmpty() -> appContext.getString(R.string.state_ui_the_selected_path_has_been_added_42b432)
                    else -> null
                }
                if (message != null) toast(message)
            }
        }
    }

    /** 组装运行时配置：与应用内发送路径一致（含工具开关、思考档位与 GPT 速度档位）。 */
    suspend fun buildRunConfig(): AgentModelClient.ModelConfig? {
        val provider = selectedProviderRecord() ?: return null
        val model = selectedModelRecord() ?: return null
        val base = runCatching {
            RuntimeConfigRepository.buildRuntimeConfig(provider, model, assistantProfile())
        }.getOrNull() ?: return null
        val permitted = if (Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)) {
            base.reasoningCapabilities?.normalize(reasoningEffort) ?: reasoningEffort
        } else {
            ReasoningEffort.OFF
        }
        return GptSpeedModePolicy.snapshot(
            base,
            gptSpeedMode,
            supportsGptSpeedBinding(provider, model),
        ).copy(
            errorReconnectPolicy = runCatching {
                SettingsDataStore.settings().errorReconnectPolicy.persistedValue
            }.getOrDefault(base.errorReconnectPolicy),
            terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
            screenTools = Prefs.isEnabled(Prefs.Keys.AGENT_SCREEN_TOOLS),
            deviceDirectTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            deviceSensitiveReadTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            deviceSensitiveActionTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            thinkingEnabled = permitted.enablesReasoning,
            reasoningEffort = permitted,
        )
    }

    /** 待发图片按选中模型的能力编码（不支持视频时降级为缩略图）。 */
    fun outboundImages(): List<AgentModelClient.ModelImage> {
        val supportsVideo = selectedModel?.supportsVideo == true
        return pendingImages.map { it.toOutboundModelImage(supportsVideo) }
    }

    private fun releasePersistableUriPermission(uri: String) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return
        if (parsed.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    parsed,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
    }

    private fun toast(resId: Int) {
        toast(appContext.getString(resId))
    }

    private val AgentFileReferenceGateway.Error.userMessage: String
        get() = when (this) {
            AgentFileReferenceGateway.Error.UnsupportedDocumentProvider ->
                appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.InvalidPath ->
                appContext.getString(R.string.state_ui_please_enter_a_valid_absolute_path_6afeb4)
            AgentFileReferenceGateway.Error.PathNotFound ->
                appContext.getString(R.string.state_ui_the_path_does_not_exist_or_is_no_longer_accessib_a9776e)
            AgentFileReferenceGateway.Error.UnsupportedFileType ->
                appContext.getString(R.string.state_ui_only_supports_normal_files_and_folders_4adea0)
            AgentFileReferenceGateway.Error.TypeMismatch ->
                appContext.getString(R.string.state_ui_the_selected_project_type_does_not_match_3a5c49)
            AgentFileReferenceGateway.Error.RootUnavailable ->
                appContext.getString(R.string.state_ui_root_is_not_available_and_the_path_cannot_be_ver_fc4c81)
            AgentFileReferenceGateway.Error.AccessDenied ->
                appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.ImportFailed ->
                appContext.getString(R.string.capability_import_failed)
            AgentFileReferenceGateway.Error.ImportTooLarge ->
                appContext.getString(R.string.capability_import_too_large)
            AgentFileReferenceGateway.Error.ValidationTimedOut ->
                appContext.getString(R.string.state_ui_path_verification_timed_out_please_try_again_703687)
        }
}
