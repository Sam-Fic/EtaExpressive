package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.media.AgentHistoryImageHydrator
import io.github.mangi.eta.agent.media.isVideoMedia
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

internal object AgentModelClient {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
    fun loadConfig(): ModelConfig {
        val runtimeJson = Prefs.getString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
        if (runtimeJson.isNotBlank()) {
            runCatching {
                json.decodeFromString<ModelConfig>(runtimeJson)
            }.getOrNull()?.let { runtime ->
                val thinkingAllowed = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
                val effort = if (thinkingAllowed) {
                    runtime.effectiveReasoningEffort
                } else {
                    ReasoningEffort.OFF
                }
                return runtime.copy(
                    terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                    browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
                    screenTools = Prefs.isEnabled(Prefs.Keys.AGENT_SCREEN_TOOLS),
                    deviceTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_TOOLS),
                    personalTools = Prefs.isEnabled(Prefs.Keys.AGENT_PERSONAL_TOOLS),
                    thinkingEnabled = effort.enablesReasoning,
                    reasoningEffort = effort,
                )
            }
        }
        return ModelConfig(
            providerId = "builtin-openai",
            providerName = "OpenAI",
            providerType = ProviderTypes.OPENAI_COMPATIBLE,
            providerSourceType = ProviderSourceRegistry.resolve(
                providerId = "builtin-openai",
                baseUrl = "https://api.openai.com/v1",
                providerType = ProviderTypes.OPENAI_COMPATIBLE,
            ),
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            model = "gpt-5.5",
            modelDisplayName = "GPT-5.5",
            systemPrompt = BuiltinProviders.DEFAULT_SYSTEM_PROMPT,
            terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
            screenTools = Prefs.isEnabled(Prefs.Keys.AGENT_SCREEN_TOOLS),
            deviceTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_TOOLS),
            personalTools = Prefs.isEnabled(Prefs.Keys.AGENT_PERSONAL_TOOLS),
            thinkingEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED),
            reasoningEffort = ReasoningEffort.fromLegacy(
                Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
            ),
        )
    }

    fun complete(
        config: ModelConfig,
        prompt: String,
        toolExecutor: ToolExecutor,
        images: List<ModelImage> = emptyList(),
        history: List<ConversationMessage> = emptyList(),
        provider: AgentProviderClient = ProviderClientFactory.getClient(config),
        runController: AgentRunController = AgentRunController(),
        skillContext: SkillContext = SkillContext.EMPTY,
        memoryContext: AgentMemoryContext = AgentMemoryContext.DISABLED,
        skillContextProvider: () -> SkillContext = { skillContext },
        memoryContextProvider: () -> AgentMemoryContext = { memoryContext },
        additionalTools: JSONArray = JSONArray(),
        /** 模型自建配方的 function schema；每轮重新读取，因此新建的配方下一轮即可调用。 */
        recipeTools: JSONArray = JSONArray(),
        capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
        /** 本次 run 已按需打开的工具域；由执行器在 tools_use_group 成功后累积。 */
        openedToolGroupsProvider: () -> Set<String> = { emptySet() },
        sessionId: String = java.util.UUID.randomUUID().toString(),
        onEvent: (AgentEvent) -> Unit = {},
        linuxEnvironmentLabelProvider: () -> String = { "Linux" },
        terminalSessionEnvironmentProvider: (String) -> String? = { null },
        terminalSessionIdentityProvider: (String) -> String? = { null },
        skipHistoryTrimming: Boolean = false,
        compactPolicy: AgentLoop.CompactPolicy = AgentLoop.CompactPolicy.Disabled,
        compactionArchive: AgentCompactionArchive? = null,
        turnId: String = java.util.UUID.randomUUID().toString(),
        calibratedInputTokens: Int? = null,
        allowUnmeasuredContextSend: Boolean = false,
        onHistorySnapshot: ((Int, List<ConversationMessage>) -> String)? = null,
        // The parent owner freezes this value; other model callers do not inherit its preference.
        interactiveModeEnabled: Boolean = false,
    ): ModelResponse.Text {
        config.validate()
        val initialCapabilities = capabilitiesProvider()
        // Never trim by individual message count/size here. Runtime compaction selects balanced
        // ranges and the loop pauses when the protected request cannot fit.
        val trimmedHistory = AgentTurnIdentity.migrate(history)
        val imageInput = config.supportsVision || ModelFeaturePreferences.visionEnabled()
        val outboundImages = images.filter { image ->
            if (image.isVideoMedia()) config.supportsVideo else imageInput
        }
        val outboundHistory = if (!config.supportsVision && imageInput) trimmedHistory else AgentHistoryImageHydrator.hydrateAll(
            history = trimmedHistory,
            supportsVision = imageInput,
            supportsVideo = config.supportsVideo,
        )
        // Provider hydration must never erase durable media in a read-only branch snapshot.
        // Match the exact sanitized DTO, not a bare turn id (supplements share turn ids).
        val snapshotMedia = if (onHistorySnapshot == null) null else runCatching {
            trimmedHistory.zip(outboundHistory).filter { (durable, _) ->
                AgentConversationCodec.persistedImageSources(durable).isNotEmpty()
            }.groupBy({ (_, outbound) -> AgentConversationCodec.durableMessage(AgentConversationCodec.toJsonObject(outbound)) },
                { (durable, _) -> durable })
        }.getOrNull()
        val delegationAvailable = AgentPromptBuilder.delegationToolsAvailable(additionalTools)
        // 委派的「结果解读规则」只在真的派发过之后才付 token：本 run 执行到 delegate_task 就翻开。
        // 续跑的会话要从历史里认出来，否则恢复后的第一轮会缺规则，而子任务可能正在后台跑。
        // 工具可能并发执行，用 AtomicBoolean 保证下一轮重建前缀时看得见。
        val delegationEngaged = java.util.concurrent.atomic.AtomicBoolean(
            outboundHistory.any { it.toolCallsJson.contains(DELEGATE_TASK_CALL_MARKER) },
        )
        val observingToolExecutor = ToolExecutor { call ->
            if (call.name == DELEGATE_TASK_NAME) delegationEngaged.set(true)
            toolExecutor.execute(call)
        }
        // 只在此处（真实主 run）从 Prefs 捕获；子代理/压缩模型不会走到这里，
        // 因此不会因为最小配置而误报「用户关闭了终端」。
        val capabilitySwitches = ToolCapabilitySwitches.capture()
        var systemCount = 0
        val messages = AgentPromptBuilder.buildInitialMessages(
            config,
            prompt,
            outboundImages,
            outboundHistory,
            skillContext,
            memoryContext,
            rootAvailable = initialCapabilities.rootAvailable,
            delegationAvailable = delegationAvailable,
            shellTools = initialCapabilities.shellTools,
            interactiveModeEnabled = interactiveModeEnabled,
            disabledCapabilities = capabilitySwitches.toDisabledState(),
            // 首轮通常还没有打开任何延迟组；仍走同一个 provider，保证提示与目录同源。
            openedToolGroups = openedToolGroupsProvider(),
            delegationEngaged = delegationEngaged.get(),
            onSystemCount = { systemCount = it },
        )
        var transcriptStartIndex = messages.length()
        fun toolsFor(
            capabilities: AgentToolCapabilities,
            memoryEnabled: Boolean = memoryContext.enabled,
        ): JSONArray {
            val tools = AgentToolCatalog.build(
                basicTools = Prefs.isEnabled(Prefs.Keys.AGENT_BASIC_TOOLS),
                terminalTools = config.terminalTools,
                browserTools = config.browserTools,
                screenTools = config.screenTools,
                deviceTools = config.deviceTools,
                personalTools = config.personalTools,
                extensionsTools = Prefs.isEnabled(Prefs.Keys.AGENT_EXTENSIONS_TOOLS),
                skillGitHubDiscovery = true,
                skillGitHubInstall = true,
                memoryTools = memoryEnabled,
                mediaSearchTools = Prefs.isEnabled(Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS),
                capabilities = capabilities,
                localWebSearch = !config.usesHostedWebSearch,
                openedToolGroups = openedToolGroupsProvider(),
                // 单工具禁用集：每轮重读，用户在两轮之间改工具清单页也能生效。
                disabledTools = Prefs.disabledTools(),
                // 命令执行是独立开关：关掉它仍可读写文件，但不能跑 shell。
                fileSystemTier = AgentFileSystemTier.of(
                    terminalTools = config.terminalTools,
                    shellTools = Prefs.isEnabled(Prefs.Keys.AGENT_SHELL_TOOLS),
                ),
            )
            for (index in 0 until additionalTools.length()) {
                tools.put(additionalTools.opt(index))
            }
            // 自进化配方工具放在最后：模型读完内置工具后，额外看到自己写的工具。
            for (index in 0 until recipeTools.length()) {
                tools.put(recipeTools.opt(index))
            }
            if (compactionArchive != null) tools.put(AgentCompactionArchive.tool())
            return tools
        }
        val tools = toolsFor(initialCapabilities)
        val traceFormatter = AgentTraceFormatter(
            linuxEnvironmentLabelProvider = linuxEnvironmentLabelProvider,
            terminalSessionEnvironmentProvider = terminalSessionEnvironmentProvider,
            terminalSessionIdentityProvider = terminalSessionIdentityProvider,
        )
        onEvent(
            AgentEvent.RunStarted(
                initialImages = images.size,
                initialImageBytes = images.sumOf { it.bytes },
                toolCount = tools.length(),
                terminalTools = config.terminalTools
            )
        )
        var promptRootAvailable = initialCapabilities.rootAvailable
        var lastRoundSignature: RoundCapabilitySignature? = null
        var lastRoundTools: JSONArray? = null
        val loop = AgentLoop(
            sessionId = sessionId,
            config = config,
            messages = messages,
            tools = tools,
            provider = provider,
            toolExecutor = observingToolExecutor,
            runController = runController,
            traceFormatter = traceFormatter,
            onEvent = onEvent,
            compactPolicy = compactPolicy,
            systemCountProvider = { systemCount },
            compactionArchive = compactionArchive,
            turnId = turnId,
            calibratedInputTokens = calibratedInputTokens,
            allowUnmeasuredContextSend = allowUnmeasuredContextSend,
            onHistoryCompacted = { transcriptStartIndex = messages.length() },
            onHistorySnapshot = onHistorySnapshot?.let { publish ->
                snapshot@ { round, snapshot ->
                    val media = snapshotMedia ?: return@snapshot ""
                    val restored = snapshot.map { message ->
                        val originals = media[message].orEmpty().distinct()
                        if (originals.size > 1) return@snapshot ""
                        originals.singleOrNull() ?: message
                    }
                    publish(round, restored)
                }
            },
            toolsForRound = { round ->
                // Round one has not executed a tool yet: the contexts and tools captured above
                // are already this request's inputs. Rebuild only after a tool can have changed
                // skills, memory or capabilities.
                if (round == 1) {
                    tools
                } else {
                    val capabilities = capabilitiesProvider()
                    val nextSkillContext = skillContextProvider()
                    val nextMemoryContext = memoryContextProvider()
                    // 每轮都重建系统消息与工具目录要付约 50 KB JSON 构造 + 逐工具过滤。
                    // 只有「能力快照 + 技能索引 + 记忆修订」都变了才真正重建；否则复用上一轮
                    // 的目录与系统消息（两者都只由这三项决定）。
                    val signature = RoundCapabilitySignature(
                        capabilities = capabilities,
                        skillIndex = nextSkillContext.installedSkills.map { it.id to it.enabled },
                        memoryRevision = nextMemoryContext.revision,
                        memoryEnabled = nextMemoryContext.enabled,
                        delegationAvailable = delegationAvailable,
                        delegationEngaged = delegationEngaged.get(),
                        interactiveModeEnabled = interactiveModeEnabled,
                        openedToolGroups = openedToolGroupsProvider(),
                        // 用户可能在两轮之间改开关：签名必须覆盖它，否则目录与系统提示不会刷新。
                        toolSwitches = ToolSwitchSnapshot.capture(),
                        // 关闭说明的内容同样随开关变化，必须一起参与判定。
                        capabilitySwitches = ToolCapabilitySwitches.capture(),
                    )
                    if (signature != lastRoundSignature) {
                        val systemMessages = AgentPromptBuilder.buildSystemMessages(
                            config, nextSkillContext, nextMemoryContext, capabilities.rootAvailable, delegationAvailable,
                            capabilities.shellTools,
                            interactiveModeEnabled = interactiveModeEnabled,
                            openedToolGroups = openedToolGroupsProvider(),
                            // 本轮之前是否已经派发过子任务：决定结果解读规则是否进前缀。
                            delegationEngaged = delegationEngaged.get(),
                            // 每轮重新捕获：用户可能在两轮之间改了开关。
                            disabledCapabilities = ToolCapabilitySwitches
                                .capture().toDisabledState(),
                        )
                        // 系统前缀的条数会随「本轮打开了哪些工具组」变化（指引块与组索引都是条件消息）。
                        // JSONArray.put(index, …) 是替换不是插入，按索引覆写会在前缀变长时盖掉历史与用户消息，
                        // 变短时又会留下上一轮的陈旧指引。这里整段重排，并同步移动两个以条数为基准的边界。
                        val previousSystemCount = systemCount
                        rewriteSystemPrefix(messages, systemMessages, previousSystemCount)
                        systemCount = systemMessages.length()
                        transcriptStartIndex += systemCount - previousSystemCount
                        promptRootAvailable = capabilities.rootAvailable
                        lastRoundTools = toolsFor(capabilities, nextMemoryContext.enabled)
                        lastRoundSignature = signature
                    }
                    requireNotNull(lastRoundTools)
                }
            },
        )
        val result = try {
            loop.run()
        } catch (throwable: Throwable) {
            loop.preserveIncompleteResponse()
            if (runController.isCancelled || throwable is AgentRunCancelledException) {
                AgentStoppedHistory.closePendingTools(messages, turnId)
                runController.takeStoppedSteering().forEach { supplement ->
                    messages.put(AgentSupplementMedia.userMessage(
                        AgentContextCompactor.steeringUserContent(supplement.text), supplement.imagesJson,
                    ).put(AgentTurnIdentity.JSON_KEY, turnId))
                }
                throw AgentRunCancelledException(
                    transcript = AgentConversationCodec.transcript(
                        messages, transcriptStartIndex, loop.sensitiveToolCallIdsSnapshot(),
                    ),
                    reasoningContent = loop.reasoningSnapshot(),
                )
            }
            throw AgentModelExecutionException(
                cause = throwable,
                reasoningContent = loop.reasoningSnapshot(),
                transcript = AgentConversationCodec.transcript(
                    messages,
                    transcriptStartIndex,
                    loop.sensitiveToolCallIdsSnapshot(),
                ),
            )
        }
        return ModelResponse.Text(
            content = result.content,
            reasoningContent = result.reasoningContent,
            transcript = AgentConversationCodec.transcript(
                messages,
                transcriptStartIndex,
                result.sensitiveToolCallIds,
            ),
        )
    }

    /**
     * 用新的系统前缀替换 messages 开头的 oldCount 条，其余消息原样保留（对象身份不变，
     * 因此已写入的 turn id 等标记不会丢）。JSONArray 没有「在头部插入」的操作，
     * 只能先摘出尾部再整体重建；从尾部往前 remove 才是 O(n)。
     */
    private fun rewriteSystemPrefix(messages: JSONArray, prefix: JSONArray, oldCount: Int) {
        val boundary = oldCount.coerceIn(0, messages.length())
        val tail = (boundary until messages.length()).map { index -> messages.getJSONObject(index) }
        for (index in messages.length() - 1 downTo 0) {
            messages.remove(index)
        }
        for (index in 0 until prefix.length()) {
            messages.put(prefix.getJSONObject(index))
        }
        tail.forEach(messages::put)
    }

    private fun ModelConfig.validate() {
        require(baseUrl.isNotBlank()) { "请先配置 API 地址" }
        when {
            authMode == ProviderAuthModes.API_KEY -> require(apiKey.isNotBlank()) { "请先配置 API Key" }
            ProviderAuthModes.isOAuth(authMode) -> {
                require(providerId.isNotBlank()) { "账号登录缺少服务商标识，请重新选择服务商" }
                require(apiKey.isEmpty()) { "账号登录配置不能包含 API Key" }
            }
            else -> error("不支持的服务商认证方式")
        }
        require(model.isNotBlank()) { "请先配置模型名" }
        require(
            reasoningCapabilities?.mandatory != true ||
                effectiveReasoningEffort != ReasoningEffort.OFF
        ) { "当前模型强制启用推理，不能选择 Off 或禁用思考权限" }
        if (extraBodyJson.isNotBlank()) {
            runCatching { JSONObject(extraBodyJson) }
                .getOrElse { throwable ->
                    error("额外请求体 JSON 无效：${throwable.message ?: throwable.javaClass.simpleName}")
                }
        }
    }

    fun buildUserHistoryMessage(
        text: String,
        images: List<ModelImage> = emptyList(),
        persistedImages: List<AgentConversationCodec.PersistedImage> = emptyList(),
    ): ConversationMessage =
        AgentConversationCodec.durableMessage(
            if (persistedImages.isNotEmpty()) {
                AgentConversationCodec.userPersistedImageMessage(text, persistedImages)
            } else {
                AgentConversationCodec.userMessage(text, images)
            },
        )

    internal fun summarizeOpenUriArguments(argumentsJson: String): String =
        AgentTraceFormatter().summarizeOpenUriArguments(argumentsJson)

    internal fun summarizeBrowserToolArguments(argumentsJson: String): String =
        AgentTraceFormatter().summarizeBrowserArguments(argumentsJson)

    internal fun summarizeToolResult(toolName: String, result: ToolResult): String =
        AgentTraceFormatter().summarizeResult(toolName, result)

    @Serializable
    data class ModelConfig(
        val providerId: String = "",
        val providerName: String = "",
        val providerType: String = ProviderTypes.OPENAI_COMPATIBLE,
        val providerSourceType: String = "",
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val modelDisplayName: String = "",
        val contextWindow: Int? = null,
        val summaryOutputLimit: Int? = null,
        val systemPrompt: String,
        val anthropicVersion: String = AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
        val openAiEndpointMode: String = OpenAiEndpointMode.CHAT_COMPLETIONS,
        val responsesStripReasoningStatus: Boolean = false,
        val hostedWebSearchEnabled: Boolean = false,
        val terminalTools: Boolean = false,
        val browserTools: Boolean = true,
        /** 屏幕观察与 GUI 操作总开关；false 时目录与提示词都不包含无障碍界面能力。 */
        val screenTools: Boolean = true,
        /** 设备控制组（直达 + 写操作）；细粒度差异由单工具禁用集承接。 */
        val deviceTools: Boolean = true,
        /** 个人数据组（个人内容 + 设备状态读取）。 */
        val personalTools: Boolean = false,
        val thinkingEnabled: Boolean = false,
        val reasoningEffort: ReasoningEffort? = null,
        val reasoningCapabilities: ModelReasoningCapabilities? = null,
        val extraBodyJson: String = "",
        val customHeaders: List<CustomHeader> = emptyList(),
        val customBody: List<CustomBody> = emptyList(),
        val sessionModelPattern: String = "^gpt-.*$",
        val sessionPathPattern: String = "/v1/responses",
        val sessionKeyField: String = "prompt_cache_key",
        val supportsVision: Boolean = false,
        val supportsVideo: Boolean = false,
        val assistantId: String = "",
        val gptSpeedMode: GptSpeedMode? = null,
        val errorReconnectPolicy: String = "none",
        val authMode: String = ProviderAuthModes.API_KEY,
    ) {
        val effectiveReasoningEffort: ReasoningEffort
            get() = reasoningEffort ?: ReasoningEffort.fromLegacy(thinkingEnabled)

        /** Responses 接口启用托管搜索时不注册本地 web_search，避免同名工具冲突。 */
        val usesHostedWebSearch: Boolean
            get() = hostedWebSearchEnabled && openAiEndpointMode == OpenAiEndpointMode.RESPONSES
    }

    /**
     * 决定「这一轮的系统消息与工具目录」的全部输入。
     *
     * AgentPromptBuilder.buildSystemMessages 只读这些值，toolsFor 只读其中的 capabilities 与
     * memory 开关，因此签名不变时上一轮的结果可以直接复用，省掉每轮 ~50 KB 的 JSON 重建与
     * AgentToolRequirements.project 的逐工具过滤。技能索引按 (id, enabled) 参与签名：
     * 正文变化不影响目录与系统提示里的索引行，只有启用状态与条目集合会。
     */
    internal data class RoundCapabilitySignature(
        val capabilities: io.github.mangi.eta.agent.tool.AgentToolCapabilities,
        val skillIndex: List<Pair<String, Boolean>>,
        val memoryRevision: String,
        val memoryEnabled: Boolean,
        val delegationAvailable: Boolean,
        /** 本 run 是否已派发过子任务；翻开后系统前缀多一条结果解读规则，必须参与重建判定。 */
        val delegationEngaged: Boolean = false,
        val interactiveModeEnabled: Boolean,
        val openedToolGroups: Set<String> = emptySet(),
        /** 决定域可用性的 Prefs 开关；运行中改开关必须让目录与提示重新生成。 */
        val toolSwitches: ToolSwitchSnapshot = ToolSwitchSnapshot.DEFAULT,
        /** 决定「已关闭能力」说明的完整开关快照；同样必须参与重建判定。 */
        val capabilitySwitches: ToolCapabilitySwitches? = null,
    )

    /**
     * 影响工具目录的 Prefs 开关快照。目录里凡是不走 config 的输入都必须参与签名，
     * 否则用户运行中改开关（组开关、shell 子开关、单工具禁用集）不会触发重建。
     */
    internal data class ToolSwitchSnapshot(
        val basicTools: Boolean,
        val screenTools: Boolean,
        val browserTools: Boolean,
        val terminalTools: Boolean,
        val shellTools: Boolean,
        val deviceTools: Boolean,
        val personalTools: Boolean,
        val extensionsTools: Boolean,
        val mediaSearchTools: Boolean,
        val disabledToolsDigest: String,
    ) {
        companion object {
            val DEFAULT = ToolSwitchSnapshot(
                basicTools = true,
                screenTools = true,
                browserTools = true,
                terminalTools = true,
                shellTools = true,
                deviceTools = true,
                personalTools = true,
                extensionsTools = true,
                mediaSearchTools = true,
                disabledToolsDigest = "",
            )

            fun capture() = ToolSwitchSnapshot(
                basicTools = Prefs.isEnabled(Prefs.Keys.AGENT_BASIC_TOOLS),
                screenTools = Prefs.isEnabled(Prefs.Keys.AGENT_SCREEN_TOOLS),
                browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
                terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                shellTools = Prefs.isEnabled(Prefs.Keys.AGENT_SHELL_TOOLS),
                deviceTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_TOOLS),
                personalTools = Prefs.isEnabled(Prefs.Keys.AGENT_PERSONAL_TOOLS),
                extensionsTools = Prefs.isEnabled(Prefs.Keys.AGENT_EXTENSIONS_TOOLS),
                mediaSearchTools = Prefs.isEnabled(Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS),
                disabledToolsDigest = Prefs.disabledTools().sorted().joinToString(","),
            )
        }
    }

    /**
     * 主 run 的完整工具开关快照，**只用于生成「已关闭能力」说明**。
     *
     * 与 [ToolSwitchSnapshot] 分开：那个只关心「组能否打开」，字段少、默认全开；
     * 这个要覆盖所有会让工具缺席的开关，因此必须在真实主 run 里从 Prefs 捕获，
     * 不能靠 ModelConfig 默认值推导——子代理与压缩模型的最小配置会让它误报
     * 「用户关闭了终端」。
     */
    internal data class ToolCapabilitySwitches(
        val basicTools: Boolean,
        val terminalTools: Boolean,
        val shellTools: Boolean,
        val browserTools: Boolean,
        val screenTools: Boolean,
        val deviceTools: Boolean,
        val personalTools: Boolean,
        val extensionsTools: Boolean,
        val mediaSearchTools: Boolean,
        val delegationTools: Boolean,
    ) {
        companion object {
            /**
             * 只从 Prefs 读取**用户设置**。刻意不接受 `config.*`：那是「Prefs 且入口权限」的
             * 生效值，入口收窄时同样为 false，据此生成关闭说明会向模型撒谎。
             */
            fun capture() = ToolCapabilitySwitches(
                basicTools = Prefs.isEnabled(Prefs.Keys.AGENT_BASIC_TOOLS),
                terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                shellTools = Prefs.isEnabled(Prefs.Keys.AGENT_SHELL_TOOLS),
                browserTools = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
                screenTools = Prefs.isEnabled(Prefs.Keys.AGENT_SCREEN_TOOLS),
                deviceTools = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_TOOLS),
                personalTools = Prefs.isEnabled(Prefs.Keys.AGENT_PERSONAL_TOOLS),
                extensionsTools = Prefs.isEnabled(Prefs.Keys.AGENT_EXTENSIONS_TOOLS),
                mediaSearchTools = Prefs.isEnabled(Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS),
                // 独立开关：不能用 delegationAvailable，那是「本轮是否真的下发了 delegate_task」，
                // 入口未附带委派工具时它为 false，会被误报成「用户关闭了子代理」。
                delegationTools = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_TOOLS),
            )
        }

        fun toDisabledState() = AgentToolGroups.ToolSwitchState(
            basicTools = basicTools,
            screenTools = screenTools,
            browserTools = browserTools,
            terminalTools = terminalTools,
            shellTools = shellTools,
            deviceTools = deviceTools,
            personalTools = personalTools,
            extensionsTools = extensionsTools,
            mediaSearchTools = mediaSearchTools,
            delegationTools = delegationTools,
        )
    }

    @Serializable
    data class ConversationMessage(
        val role: String,
        val content: String = "",
        val contentJson: String = "",
        val toolCallId: String = "",
        val reasoningContent: String = "",
        val toolCallsJson: String = "",
        val turnId: String = "",
        val responsesReasoningJson: String = "",
    )

    fun interface ToolExecutor {
        fun execute(toolCall: ToolCall): ToolResult
    }

    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String
    )

    data class ToolResult(
        val content: String,
        val images: List<ModelImage> = emptyList(),
        /**
         * 敏感结果仍会供当前 Agent loop 使用，但工具参数与原始结果不会进入持久会话。
         * 最终 assistant 自己组织的答复不受此标记影响。
         */
        val sensitive: Boolean = false,
    )

    /** 图片引用：入口侧可为本地 URI/路径，进入模型协议前必须解析为远程 URL 或 data URL。 */
    data class ModelImage(
        val reference: String,
        val mimeType: String,
        val bytes: Int,
        val width: Int? = null,
        val height: Int? = null,
        val source: String = "unknown"
    )

    sealed interface ModelResponse {
        data class Text(
            val content: String,
            val reasoningContent: String = "",
            val transcript: List<ConversationMessage> = emptyList(),
        ) : ModelResponse
    }

}

internal class AgentModelExecutionException(
    cause: Throwable,
    val reasoningContent: String,
    val transcript: List<AgentModelClient.ConversationMessage>,
) : RuntimeException(cause.message ?: cause.javaClass.simpleName, cause)

private const val DELEGATE_TASK_NAME = "delegate_task"

/** 历史里的 tool_calls JSON 片段；用来在续跑的会话上认出「已经派发过」。 */
private const val DELEGATE_TASK_CALL_MARKER = "\"$DELEGATE_TASK_NAME\""
