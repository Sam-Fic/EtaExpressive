@file:android.annotation.SuppressLint("LocalContextGetResourceValueCall")

package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.components.TopBarIconButton
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.agent.voice.tts.CloudSpeechSynthesizer
import io.github.mangi.eta.agent.voice.tts.SpeechPlaybackFailure
import io.github.mangi.eta.data.model.withId
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.navigation.NewProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text as M3Text

@Composable
internal fun ModelProviderDetailScreen(
    providerId: String? = null,
    newType: NewProviderType? = null,
    authMode: String = ProviderAuthModes.DEFAULT,
    onBack: () -> Unit,
    currentModelId: String? = null,
    onSelectCurrentModel: ((String) -> Unit)? = null,
    // 「模型」tab 的编辑 / 新建模型入口：整屏路由 push（AppRoute.ModelProviderModelEdit）。
    onEditModel: ((String, Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    var createdId by remember { mutableStateOf<String?>(null) }
    val effectiveId = providerId ?: createdId
    val provider = remember(providers, effectiveId) {
        effectiveId?.let { id -> providers.firstOrNull { it.id == id } }
    }
    // context 进 remember key：语言变化时草稿名随资源语言重建。
    val draft = remember(newType, authMode, context) {
        when (newType) {
            NewProviderType.OpenAiCompatible -> when (authMode) {
                ProviderAuthModes.OPENAI_CHATGPT -> CustomProviderSetting(
                    id = ProviderRepository.newId(),
                    name = context.getString(R.string.provider_codex_name),
                    baseUrl = ProviderConnectionProfile.OPENAI_CODEX.baseUrl,
                    endpointMode = ProviderConnectionProfile.OPENAI_CODEX.endpointMode,
                    authMode = ProviderAuthModes.OPENAI_CHATGPT,
                )
                ProviderAuthModes.XAI_GROK -> CustomProviderSetting(
                    id = ProviderRepository.newId(),
                    name = context.getString(R.string.provider_grok_name),
                    baseUrl = ProviderConnectionProfile.XAI_GROK.baseUrl,
                    endpointMode = ProviderConnectionProfile.XAI_GROK.endpointMode,
                    authMode = ProviderAuthModes.XAI_GROK,
                    sourceType = ProviderSourceTypes.XAI,
                )
                else -> CustomProviderSetting(
                    id = "",
                    name = "",
                    baseUrl = "",
                    endpointMode = OpenAiEndpointMode.CHAT_COMPLETIONS,
                )
            }
            NewProviderType.Anthropic -> AnthropicProviderSetting(
                id = "",
                name = "",
                baseUrl = "https://api.anthropic.com",
            )
            NewProviderType.DoubaoSpeech -> CustomProviderSetting(
                id = "",
                name = context.getString(R.string.tts_source_doubao),
                baseUrl = "https://openspeech.bytedance.com",
                sourceType = ProviderSourceTypes.DOUBAO_SPEECH,
                models = SpeechSynthesisModels.catalogModels(
                    CustomProviderSetting(
                        id = "draft",
                        name = context.getString(R.string.tts_source_doubao),
                        baseUrl = "https://openspeech.bytedance.com",
                        sourceType = ProviderSourceTypes.DOUBAO_SPEECH,
                    ),
                ),
            )
            // Restore old navigation state through the ordinary provider form.
            NewProviderType.CompatibleSpeech -> CustomProviderSetting(
                id = "", name = context.getString(R.string.provider_compatible_speech_name), baseUrl = "",
            )
            null -> null
        }
    }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    if (provider == null && draft == null) {
        EtaScaffoldPage(
            title = stringResource(R.string.route_provider_details),
            onBack = onBack,
        ) {
            item(key = "missing_provider") {
                Column(
                    modifier = Modifier.fillParentMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    M3Text(
                        text = stringResource(R.string.ui_provider_does_not_exist_83cee6),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ProviderPrimaryAction(text = stringResource(R.string.ui_return_11d024), onClick = onBack)
                }
            }
        }
        return
    }

    val initial = provider ?: draft!!
    val isNew = provider == null
    var currentTab by remember { mutableIntStateOf(0) }
    var configDraft by rememberSaveable(
        initial.id,
        stateSaver = ProviderConfigDraftSaver,
    ) {
        mutableStateOf(ProviderConfigDraft.from(initial))
    }
    val title = if (isNew) context.getString(R.string.page_create_new_provider_36cab9) else initial.name

    // 保存相关状态与逻辑提升到页面级：M3 下保存按钮是顶栏图标按钮（KernelSU 模板
    // 编辑器同款），需要在 Scaffold 顶栏处组合，而不能留在配置列表内部。
    val bodiesResult = remember(configDraft.bodies) { parseProviderBodies(configDraft.bodies) }
    var saveStatus by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var creationCommitted by remember { mutableStateOf(false) }
    val onSaveProvider: () -> Unit = {
        TouchHaptics.click(view)
        val validationError = validateProviderDraft(context, configDraft)
        if (validationError != null) {
            saveStatus = context.getString(R.string.provider_error, validationError)
        } else {
            scope.launch {
                isSaving = true
                try {
                    if (ProviderAuthModes.isOAuth(configDraft.authMode) &&
                        withContext(Dispatchers.IO) {
                            ProviderCredentialRepository.account(initial.id, configDraft.authMode)
                        } == null
                    ) {
                        saveStatus = context.getString(R.string.provider_error, context.getString(R.string.provider_oauth_need_sign_in))
                        return@launch
                    }
                    val built = buildUpdatedProvider(
                        source = initial,
                        name = configDraft.name,
                        baseUrl = configDraft.baseUrl,
                        apiKey = configDraft.apiKey,
                        authMode = configDraft.authMode,
                        isEnabled = configDraft.isEnabled,
                        endpointMode = configDraft.endpointMode,
                        responsesStripReasoningStatus = configDraft.responsesStripReasoningStatus,
                        hostedWebSearchEnabled = configDraft.hostedWebSearchEnabled,
                        anthropicVersion = configDraft.anthropicVersion,
                        customHeaders = configDraft.headers.map { it.header },
                        customBody = bodiesResult.getOrThrow(),
                        balanceOption = configDraft.balanceOption,
                        sessionGatewayJson = configDraft.encodedSessionGateway(),
                    )
                    if (isNew) {
                        val newId = initial.id.ifBlank { ProviderRepository.newId() }
                        val toSave = built.withId(newId).let { saved ->
                            when {
                                SpeechSynthesisModels.isSpeechOnlyProvider(saved) && saved.models.none { SpeechSynthesisModels.matches(it) } ->
                                    saved.withModels(SpeechSynthesisModels.mergeCatalog(saved))
                                else -> saved
                            }
                        }
                        // 登录可能在保存前已落库，避免同一连接被重复插入。
                        val existing = ProviderRepository.providerById(toSave.id)
                        val added = if (existing == null) ProviderRepository.addProvider(toSave) else toSave
                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                        saveStatus = context.getString(
                            if (existing == null) R.string.capability_provider_created else R.string.capability_provider_saved,
                        )
                        creationCommitted = true
                        createdId = added.id
                    } else {
                        ProviderRepository.updateProvider(built)
                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                        saveStatus = context.getString(R.string.capability_provider_saved)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    saveStatus = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_save_failed),
                    )
                } finally {
                    isSaving = false
                }
            }
        }
    }

    EtaScaffold(
        title = title,
        onBack = onBack,
        actions = {
            // 保存走顶栏图标按钮（KernelSU 模板编辑器同款），不再占用页面底部。
            TopBarIconButton(
                icon = Icons.Rounded.Save,
                contentDescription = stringResource(
                    if (isNew) R.string.page_create_fcbd09 else R.string.page_save_configuration_817af1,
                ),
                onClick = onSaveProvider,
                enabled = !isSaving && !creationCommitted,
                shapes = IconButtonDefaults.shapes(),
            )
        },
    ) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            if (!isNew) {
                ProviderTabs(
                    tabs = listOf(
                        context.getString(R.string.page_configuration_d7d7ce),
                        context.getString(R.string.page_model_98fd0c),
                    ),
                    selectedIndex = currentTab,
                    onSelect = {
                        TouchHaptics.click(view)
                        currentTab = it
                    },
                    modifier = Modifier.padding(
                        start = sidePadding + EtaGroupHorizontalPadding,
                        end = sidePadding + EtaGroupHorizontalPadding,
                        top = 8.dp,
                        bottom = EtaSegmentedGroupSpacing,
                    ),
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    0 -> ProviderConfigTab(
                        provider = initial,
                        draft = configDraft,
                        onDraftChange = { configDraft = it },
                        scope = scope,
                        isNew = isNew,
                        scrollConnection = scrollBehavior,
                        contentSidePadding = sidePadding,
                        onDeleted = onBack,
                        saveStatus = saveStatus,
                        isSaving = isSaving,
                        onSave = onSaveProvider,
                        saveCommitted = creationCommitted,
                    )
                    1 -> if (!isNew) {
                        ProviderModelsTab(
                            provider = initial,
                            scope = scope,
                            scrollConnection = scrollBehavior,
                            contentSidePadding = sidePadding,
                            currentModelId = currentModelId,
                            onSelectCurrent = onSelectCurrentModel,
                            onEditModel = onEditModel,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 配置 / 模型视图切换。
 *
 * 与「Usage stats」页同款：官方单选分段按钮 [SingleChoiceSegmentedButtonRow] +
 * [SegmentedButton]（形状取官方 [SegmentedButtonDefaults]，配色/行高/字阶均使用官方默认值）。
 */
@Composable
private fun ProviderTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeIndex = selectedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        tabs.forEachIndexed { index, label ->
            SegmentedButton(
                selected = safeIndex == index,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
            ) {
                M3Text(text = label)
            }
        }
    }
}

@Composable
private fun ProviderConfigTab(
    provider: ProviderSetting,
    draft: ProviderConfigDraft,
    onDraftChange: (ProviderConfigDraft) -> Unit,
    scope: CoroutineScope,
    isNew: Boolean,
    scrollConnection: NestedScrollConnection,
    contentSidePadding: Dp,
    onDeleted: () -> Unit,
    saveStatus: String?,
    isSaving: Boolean,
    onSave: () -> Unit,
    saveCommitted: Boolean,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var headersExpanded by rememberSaveable { mutableStateOf(false) }
    var bodiesExpanded by rememberSaveable { mutableStateOf(false) }
    val bodiesResult = remember(draft.bodies) { parseProviderBodies(draft.bodies) }
    var gatewayExpanded by rememberSaveable { mutableStateOf(false) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    // 保存状态由页面级持有（顶栏图标按钮触发），这里只读展示；
    // 测试连接与删除/重置仍由本页的忙碌标记驱动。
    var status by remember { mutableStateOf<String?>(null) }
    var localBusy by remember { mutableStateOf(false) }
    // 只恢复中断提示；回调监听、验证码和授权 URL 随登录会话销毁。
    var loginInProgress by rememberSaveable(provider.id) { mutableStateOf(false) }
    var pendingInterruption by remember(provider.id) { mutableStateOf(loginInProgress) }
    val loginStore = remember(provider.id, scope) { ProviderLoginStore(provider.id, scope) }
    val loginState by loginStore.state.collectAsState()
    var browserError by remember(loginState.prompt) { mutableStateOf<String?>(null) }
    val openBrowser: () -> Unit = {
        loginState.prompt?.let { browserError = openProviderAccountLink(context, it.authorizationUrl) }
    }
    DisposableEffect(loginStore) {
        onDispose { loginStore.close() }
    }
    LaunchedEffect(loginStore, draft.authMode, provider.authMode) {
        loginStore.load(draft.authMode, interrupted = pendingInterruption)
        pendingInterruption = false
    }
    LaunchedEffect(loginState.phase) {
        loginInProgress = loginState.phase == ProviderLoginPhase.Saving ||
            loginState.phase == ProviderLoginPhase.Authorizing
    }
    LaunchedEffect(loginState.prompt) {
        val prompt = loginState.prompt
        if (prompt != null && prompt.userCode == null) openBrowser()
    }
    val isWorking = isSaving || localBusy || loginState.isBusy

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // 低层 Scaffold 只负责把顶栏 Insets 传给调用方，输入法 Insets 由列表自行消费。
            .imePadding()
            .nestedScroll(scrollConnection),
        contentPadding = PaddingValues(
            start = contentSidePadding,
            end = contentSidePadding,
        ),
        overscrollEffect = null,
    ) {
        // 连接配置拆成两组：表单体独立成段（对应参考实现的 SegmentedItemContainer），
        // 行（端点模式 / Web 搜索 / 测试连接）各自独立成段，条目间 2dp 缝隙。
        item(key = "connection_form") {
            EtaSegmentedColumn(
                modifier = ProviderSectionGroupPadding,
                title = stringResource(R.string.ui_connection_configuration_7d057b),
            ) {
                item {
                    // 文本输入自带描边圆角，不再在其下垫一层分段容器底板。
                    val oauth = ProviderAuthModes.isOAuth(draft.authMode)
                    val accountMode = providerAccountAuthMode(provider)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ProviderField(
                            value = draft.name,
                            onValueChange = { onDraftChange(draft.copy(name = it)) },
                            label = stringResource(R.string.ui_name_1be7ae),
                        )
                        if (!oauth) {
                            ProviderField(
                                value = draft.baseUrl,
                                onValueChange = { onDraftChange(draft.copy(baseUrl = it)) },
                                label = stringResource(R.string.provider_base_url),
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        when {
                            oauth -> ProviderAccountControls(
                                state = loginState,
                                authMode = draft.authMode,
                                enabled = !isWorking,
                                browserError = browserError,
                                onOpenBrowser = openBrowser,
                                onManageUsage = {
                                    browserError = openProviderAccountLink(
                                        context,
                                        if (draft.authMode == ProviderAuthModes.OPENAI_CHATGPT) {
                                            "https://chatgpt.com/codex/settings/usage"
                                        } else {
                                            "https://grok.com/settings"
                                        },
                                    )
                                },
                                onLogin = {
                                    val validationError = validateProviderDraft(context, draft)
                                    if (validationError != null) {
                                        status = context.getString(R.string.provider_error, validationError)
                                    } else {
                                        loginStore.login(draft.toProvider(provider))
                                    }
                                },
                                onDisconnect = loginStore::disconnect,
                                onCancel = loginStore::cancel,
                            )
                            else -> ProviderField(
                                value = draft.apiKey,
                                onValueChange = { onDraftChange(draft.copy(apiKey = it)) },
                                label = stringResource(R.string.provider_auth_api_key),
                                modifier = Modifier.padding(top = 12.dp),
                                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    ProviderIconAction(
                                        icon = if (apiKeyVisible) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                                        contentDescription = if (apiKeyVisible) context.getString(R.string.page_hide_bb0e7e) else context.getString(R.string.page_show_71b677),
                                        onClick = {
                                            TouchHaptics.click(view)
                                            apiKeyVisible = !apiKeyVisible
                                        },
                                    )
                                },
                            )
                        }
                        if (provider is AnthropicProviderSetting) {
                            ProviderField(
                                value = draft.anthropicVersion,
                                onValueChange = { onDraftChange(draft.copy(anthropicVersion = it)) },
                                label = "anthropic-version",
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        if (accountMode != null) {
                            EtaSegmentedDropdownRow(
                                title = stringResource(R.string.provider_auth_mode_title),
                                items = listOf(
                                    stringResource(R.string.provider_auth_api_key),
                                    stringResource(R.string.provider_auth_mode_account),
                                ),
                                selectedIndex = if (oauth) 1 else 0,
                                onItemSelected = { index ->
                                    onDraftChange(
                                        draft.withAuthMode(
                                            if (index == 0) ProviderAuthModes.API_KEY else accountMode,
                                            provider,
                                        ),
                                    )
                                },
                                enabled = !isWorking,
                            )
                        }
                    }
                }
            }
        }

        val speechOnly = SpeechSynthesisModels.isSpeechOnlyProvider(provider)
        item(key = "connection_rows") {
            EtaSegmentedColumn(modifier = ProviderSectionGroupPadding) {
                val showProtocolRows = provider !is AnthropicProviderSetting && !speechOnly &&
                    !ProviderAuthModes.isOAuth(draft.authMode)
                item(visible = showProtocolRows) {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.ui_endpoint_mode_3c8546),
                        summary = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) {
                            context.getString(R.string.page_using_typed_items_with_semantic_streaming_events_f9c906)
                        } else {
                            context.getString(R.string.page_use_standard_chat_completions_ee4b1a)
                        },
                        items = listOf("Chat Completions API", "Responses API"),
                        selectedIndex = if (draft.endpointMode == OpenAiEndpointMode.RESPONSES) 1 else 0,
                        onItemSelected = { selectedIndex ->
                            onDraftChange(
                                draft.copy(
                                    endpointMode = if (selectedIndex == 1) {
                                        OpenAiEndpointMode.RESPONSES
                                    } else {
                                        OpenAiEndpointMode.CHAT_COMPLETIONS
                                    },
                                ),
                            )
                        },
                    )
                }
                item(visible = showProtocolRows && draft.endpointMode == OpenAiEndpointMode.RESPONSES) {
                    EtaSwitchRow(
                        title = stringResource(R.string.ui_server_side_web_search_ddb8e0),
                        summary = stringResource(R.string.ui_allows_the_model_to_call_web_searches_provided_by_th_2f752f),
                        checked = draft.hostedWebSearchEnabled,
                        onCheckedChange = {
                            onDraftChange(draft.copy(hostedWebSearchEnabled = it))
                        },
                    )
                }
                item {
                    ProviderClickableRow(
                        title = stringResource(R.string.ui_test_connection_10b7d8),
                        summary = testStatus,
                        enabled = !isWorking,
                        showChevron = false,
                        onClick = {
                            TouchHaptics.click(view)
                            val validationError = validateProviderDraft(context, draft)
                            if (validationError != null) {
                                testStatus = context.getString(R.string.provider_error, validationError)
                                return@ProviderClickableRow
                            }
                            scope.launch {
                                localBusy = true
                                testStatus = context.getString(R.string.page_testing_f43705)
                                try {
                                    testStatus = testConnection(
                                        context,
                                        buildUpdatedProvider(
                                            source = provider,
                                            name = draft.name,
                                            baseUrl = draft.baseUrl,
                                            apiKey = draft.apiKey,
                                            authMode = draft.authMode,
                                            isEnabled = draft.isEnabled,
                                            endpointMode = draft.endpointMode,
                                            responsesStripReasoningStatus = draft.responsesStripReasoningStatus,
                                            hostedWebSearchEnabled = draft.hostedWebSearchEnabled,
                                            anthropicVersion = draft.anthropicVersion,
                                            customHeaders = draft.headers.map { it.header },
                                            customBody = bodiesResult.getOrThrow(),
                                            balanceOption = draft.balanceOption,
                                            sessionGatewayJson = draft.encodedSessionGateway(),
                                        )
                                    )
                                } finally {
                                    localBusy = false
                                }
                            }
                        },
                    )
                }
            }
        }

        providerSessionGateway(
            modelPattern = draft.sessionModelPattern,
            pathPattern = draft.sessionPathPattern,
            retention = draft.sessionRetention,
            keySource = draft.sessionKeySource,
            keyField = draft.sessionKeyField,
            expanded = gatewayExpanded,
            onExpandedChange = { gatewayExpanded = it },
            onChange = { model, path, retention, source, field ->
                onDraftChange(draft.copy(
                    sessionModelPattern = model,
                    sessionPathPattern = path,
                    sessionRetention = retention,
                    sessionKeySource = source,
                    sessionKeyField = field,
                ))
            },
        )

        providerHeadersEditor(
            headers = draft.headers,
            expanded = headersExpanded,
            onExpandedChange = { headersExpanded = it },
            onHeadersChange = { onDraftChange(draft.copy(headers = it)) },
        )

        providerBodiesEditor(
            bodies = draft.bodies,
            expanded = bodiesExpanded,
            onExpandedChange = { bodiesExpanded = it },
            onBodiesChange = { onDraftChange(draft.copy(bodies = it)) },
        )

        item(key = "preferences_and_prompt") {
            EtaSegmentedColumn(
                modifier = ProviderSectionGroupPadding,
                title = stringResource(R.string.ui_preferences_and_strategies_2abd3c),
            ) {
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.ui_enable_this_provider_683a76),
                        checked = draft.isEnabled,
                        onCheckedChange = { onDraftChange(draft.copy(isEnabled = it)) },
                    )
                }
            }
        }

        item(key = "balance_option") {
            EtaSegmentedColumn(
                modifier = ProviderSectionGroupPadding,
                title = stringResource(R.string.ui_balance_section_title),
            ) {
                ProviderBalanceOptionFields(
                    balanceOption = draft.balanceOption,
                    onBalanceOptionChange = { onDraftChange(draft.copy(balanceOption = it)) },
                    provider = buildUpdatedProvider(
                        source = provider,
                        name = draft.name,
                        baseUrl = draft.baseUrl,
                        apiKey = draft.apiKey,
                        authMode = draft.authMode,
                        isEnabled = draft.isEnabled,
                        endpointMode = draft.endpointMode,
                        responsesStripReasoningStatus = draft.responsesStripReasoningStatus,
                        hostedWebSearchEnabled = draft.hostedWebSearchEnabled,
                        anthropicVersion = draft.anthropicVersion,
                        customHeaders = draft.headers.map { it.header },
                        customBody = bodiesResult.getOrElse { provider.customBody },
                        balanceOption = draft.balanceOption,
                        sessionGatewayJson = draft.encodedSessionGateway(),
                    ),
                )
            }
        }

        item(key = "actions") {
            // M3：保存按钮已移到顶栏图标（KernelSU 模板编辑器同款），底部只留结果提示。
            val combinedStatus = saveStatus ?: status
            combinedStatus?.let { message ->
                M3Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (message.startsWith(context.getString(R.string.page_fail_3e3c80))) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 4.dp, bottom = 12.dp),
                )
            }
        }

        if (!isNew) {
            item(key = "danger_zone") {
                val onDangerClick = {
                    TouchHaptics.click(view)
                    if (provider.isBuiltIn) showResetDialog = true else showDeleteDialog = true
                }
                val dangerLabel = if (provider.isBuiltIn) {
                    context.getString(R.string.page_reset_built_in_configuration_35b6ec)
                } else {
                    context.getString(R.string.ui_remove_provider_9f848f)
                }
                // KernelSU 设置页「卸载」同款：分段行 + Delete 图标 + 官方 error 角色色，
                // 不再用大块卡片抢占视觉重心。
                EtaSegmentedColumn(
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = EtaSegmentedGroupSpacing,
                    ),
                ) {
                    item {
                        EtaSegmentedRow(
                            onClick = if (isWorking) null else onDangerClick,
                            modifier = Modifier.fillMaxWidth(),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            content = {
                                M3Text(
                                    text = dangerLabel,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }
        }

        item(key = "bottom_spacer") {
            Spacer(
                modifier = Modifier
                    .height(24.dp)
                    .navigationBarsPadding(),
            )
        }
    }

    if (showDeleteDialog) {
        val confirmLabel = if (isWorking) {
            context.getString(R.string.page_deleting_6f941d)
        } else {
            context.getString(R.string.ui_delete_3755f5)
        }
        val dialogTitle = stringResource(R.string.ui_remove_provider_9f848f)
        val dialogSummary = stringResource(R.string.provider_delete_summary, provider.name)
        val onDismissDelete = { if (!isWorking) showDeleteDialog = false }
        val onCancelDelete = { showDeleteDialog = false }
        val onConfirmDelete: () -> Unit = {
            scope.launch {
                localBusy = true
                try {
                    ProviderRepository.deleteProvider(provider.id)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    showDeleteDialog = false
                    onDeleted()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    status = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_delete_failed),
                    )
                    showDeleteDialog = false
                } finally {
                    localBusy = false
                }
            }
        }
        // 自适应弹窗：M3 → 官方 AlertDialog + TextButton。
        EtaDialog(
            show = true,
            title = dialogTitle,
            summary = dialogSummary,
            onDismissRequest = onDismissDelete,
            actions = {
                EtaDialogActions(
                    confirmText = confirmLabel,
                    cancelEnabled = !isWorking,
                    confirmEnabled = !isWorking,
                    destructive = true,
                    onCancel = onCancelDelete,
                    onConfirm = onConfirmDelete,
                )
            },
        ) {
        }
    }

    if (showResetDialog) {
        val confirmLabel = if (isWorking) {
            context.getString(R.string.page_resetting_616090)
        } else {
            context.getString(R.string.page_reset_3d8134)
        }
        val dialogTitle = stringResource(R.string.page_reset_built_in_configuration_35b6ec)
        val dialogSummary = stringResource(R.string.provider_reset_summary, provider.name)
        val onDismissReset = { if (!isWorking) showResetDialog = false }
        val onCancelReset = { showResetDialog = false }
        val onConfirmReset: () -> Unit = {
            scope.launch {
                localBusy = true
                try {
                    ProviderRepository.resetBuiltIn(provider.id)
                    ProviderRepository.providerById(provider.id)?.let {
                        onDraftChange(ProviderConfigDraft.from(it))
                    }
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    status = context.getString(R.string.page_reset_a0cc65)
                    showResetDialog = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    status = context.getString(
                        R.string.provider_error,
                        throwable.message ?: context.getString(R.string.provider_reset_failed),
                    )
                    showResetDialog = false
                } finally {
                    localBusy = false
                }
            }
        }
        // 自适应弹窗：M3 → 官方 AlertDialog + TextButton。
        EtaDialog(
            show = true,
            title = dialogTitle,
            summary = dialogSummary,
            onDismissRequest = onDismissReset,
            actions = {
                EtaDialogActions(
                    confirmText = confirmLabel,
                    cancelEnabled = !isWorking,
                    confirmEnabled = !isWorking,
                    onCancel = onCancelReset,
                    onConfirm = onConfirmReset,
                )
            },
        ) {
        }
    }
}

private suspend fun testConnection(
    context: android.content.Context,
    provider: ProviderSetting,
): String {
    io.github.mangi.eta.data.model.RemovedProviderPolicy.requireSupported(provider)
    if (SpeechSynthesisModels.isSpeechOnlyProvider(provider) &&
        !SpeechSynthesisModels.isCompatibleSpeechProvider(provider)) {
        return runCatching { CloudSpeechSynthesizer().test(provider.apiKey) }.getOrElse { throwable ->
            context.getString(
                R.string.provider_error,
                (throwable as? SpeechPlaybackFailure)?.message ?: context.getString(R.string.mpd_speech_api_unavailable),
            )
        }
    }
    return RemoteModelFetcher.fetch(provider)
        .map { context.resources.getQuantityString(R.plurals.provider_models_fetched, it.size, it.size) }
        .getOrElse { throwable ->
            context.getString(
                R.string.provider_error,
                (throwable as? io.github.mangi.eta.agent.auth.ProviderAuthException)?.message
                    ?: throwable.message ?: throwable.javaClass.simpleName,
            )
        }
}
