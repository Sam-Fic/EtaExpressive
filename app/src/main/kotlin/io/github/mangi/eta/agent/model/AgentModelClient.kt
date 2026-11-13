package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.media.AgentHistoryImageHydrator
import io.github.mangi.eta.agent.media.isVideoMedia
import io.github.mangi.eta.agent.decision.AgentDecisionPlan
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
        /** 调度台身份才为 true：目录追加派发工具（见 AgentDispatchAssistant / AgentDispatchToolCatalog）。 */
        dispatchTools: Boolean = false,
        skillContextProvider: () -> SkillContext = { skillContext },
        memoryContextProvider: () -> AgentMemoryContext = { memoryContext },
        additionalTools: JSONArray = JSONArray(),
        /**
         * 模型自建配方的 function schema；**每轮重新读取**，因此新建的配方下一轮即可调用。
         * 必须是 provider：配方由模型在 run 中途创建，固定快照会让它在本 run 内永远缺席。
         */
        recipeToolsProvider: () -> JSONArray = { JSONArray() },
        capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
        /** 本次 run 已按需打开的工具域；由执行器在 tools_use_group 成功后累积。 */
        openedToolGroupsProvider: () -> Set<String> = { emptySet() },
        /**
         * MCP 渐进加载投影；null = always 暴露（[additionalTools] 已含全部 MCP schema）。
         * 非 null 时 [additionalTools] 应不含 MCP 工具，schema 由每轮目录重建按
         * 已打开 server 追加，动态延迟组同步进目录与组索引。
         */
        mcpProjection: McpToolProjection? = null,
        /**
         * tools_search 的评分语料出口；每轮目录重建时清空重填（ withheld 过滤前的
         * 全量「开关允许」目录）。null = 调用方不需要工具级搜索。
         */
        searchIndex: MutableMap<String, String>? = null,
        /**
         * 渐进加载模式（对齐 ZCode「工具搜索」设置的 auto/off 两档）：
         * true = 关闭渐进加载——所有延迟组视为已打开、MCP 全量，即发送完整工具目录。
         * 由调用方在**会话首个 run** 决定并锁定（见 Prefs.toolLoadingFullCatalog），
         * 当前会话保持原设置，新建会话或上下文压缩后才重读用户偏好。
         */
        toolLoadingFullCatalog: Boolean = false,
        /**
         * 是否下发 tools_search（渐进加载按工具档）。按组档为 false：模型只能经
         * tools_use_group 整组打开；off 档下本参数无意义（目录已全量）。
         */
        toolLoadingSearchEnabled: Boolean = true,
        /**
         * 缓存优先转发（search/group 档）：延迟组工具不进 `tools`，改用固定入口
         * [AgentToolGroupCatalog.CALL] 转发，工具说明作为组加载回执进入历史尾部；
         * 同时启用会话级原生组集合（见 [resolveNativeGroups]）。off 档传 false，
         * 与 [toolLoadingFullCatalog]、预设运行互斥（调用方按会话锁定后传入）。
         */
        toolLoadingSuffixMode: Boolean = false,
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
        /**
         * 判断模型的 run 级 overlay（M2）。null = 不接管：目录、提示、枚举全部保持现状。
         * 只读本参数，不写回 Prefs；跨轮通过 [initialOpenedGroups] 与每轮重建保持一致。
         */
        decision: AgentDecisionPlan? = null,
        /**
         * 语音快捷功能预设：非 null 时目录只保留这些工具（空集 = 零工具纯对话）。
         * 与 [decision] 互斥：预设运行在 Runtime 侧不产生决策 overlay。
         */
        presetToolNames: Set<String>? = null,
        /**
         * 预设运行视为已打开的延迟组；非 null 时替代历史/决策推出来的初始组集合。
         * 只影响目录与提示里的组可用性，不改变后续轮次的工具调用恢复。
         */
        presetOpenedGroups: Set<String>? = null,
        /**
         * 预设运行发起时刻；非 null 时在系统提示里注入该时刻的本地时间、时区与
         * 相对日期换算口径（语音「创建屏幕日程」免去取时间往返）。
         */
        presetRunAt: java.time.Instant? = null,
        /**
         * 命中日历关键词（非显式零工具预设）时置 true：若预设工具在能力投影后实际为空，
         * 本轮退回普通对话（正常目录 + 正常渐进加载），而不是退化成零工具纯对话。
         */
        presetFallbackWhenEmpty: Boolean = false,
        /**
         * 唤起即屏幕上下文（助手弹窗 run）：true 时在系统提示注入「先 observe_screen 带截图
         * 看屏幕」的首轮规则。调用方已保证仅弹窗链路、消息未附图、且屏幕组已预开。
         */
        screenObservationRule: Boolean = false,
        /**
         * 「添加日程」快速路径：用一条专用短系统约束替换全部常驻提示，并在超轮次后
         * 清空工具目录强制收束。仅在预设目录确实生效时为 true（退化回普通对话时自动关闭）。
         */
        calendarFastTask: Boolean = false,
        /** 快速路径允许的模型请求轮数；仅 [calendarFastTask] 生效时读取。 */
        fastPathMaxRounds: Int = Int.MAX_VALUE,
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
        // 闲聊态关段只在 control 成功时由 router 置位，prompt builder 直接据此裁剪。
        val trimChatSections = decision?.chatOnly == true
        // 结果解读规则默认不进系统前缀：本 run 首次派发时随 delegate_task 回执下发，落在历史
        // 尾部（＝后缀），前缀字节因此不随派发变化。三种情况要把它放回前缀，且都发生在缓存
        // 尚未建立或本来就作废的时刻：
        //  - 续跑：持久历史里已有派发标记，但回执正文已脱敏，规则只能从第一轮起常驻；
        //  - 压缩：回执被摘要替换掉，同上（压缩本身已重写整段历史）；
        //  - 闲聊态：调度规则整段被裁掉，派发后两段一起补回，沿用旧行为。
        // 工具可能并发执行，用 AtomicBoolean 保证下一轮重建前缀时看得见。
        val delegationResultRuleInPrefix = java.util.concurrent.atomic.AtomicBoolean(
            outboundHistory.any { it.toolCallsJson.contains(DELEGATE_TASK_CALL_MARKER) },
        )
        val delegationResultRuleDelivered = java.util.concurrent.atomic.AtomicBoolean(false)
        val observingToolExecutor = ToolExecutor { call ->
            val result = toolExecutor.execute(call)
            if (call.name != DELEGATE_TASK_NAME) return@ToolExecutor result
            if (trimChatSections) {
                delegationResultRuleInPrefix.set(true)
                return@ToolExecutor result
            }
            if (delegationResultRuleInPrefix.get()) return@ToolExecutor result
            // 同一批并行派发只附一份规则；CAS 抢输的调用照常返回原回执。
            if (!delegationResultRuleDelivered.compareAndSet(false, true)) return@ToolExecutor result
            result.withDelegationResultRule()
        }
        // 只在此处（真实主 run）从 Prefs 捕获；子代理/压缩模型不会走到这里，
        // 因此不会因为最小配置而误报「用户关闭了终端」。
        val capabilitySwitches = ToolCapabilitySwitches.capture()
        var systemCount = 0
        // 首轮的目录与提示必须同源：提示只点名目录里真实存在的工具，
        // 否则模型照着提示调用一个没下发的工具，会被校验器连续拒三次并终止整轮。
        //
        // 每个 run 的执行器状态都是新的，但会话历史是共享的：历史里已经出现过某个延迟组的
        // 工具调用时，模型看得见它、也理应还能调用它。不把这一组并进来，用户每发一条新消息
        // 都要先白付一轮 tools_use_group（真实会话里反复发生），模型也会因为「刚调过却报未声明」
        // 而怀疑参数没有送达。
        // 动态延迟组（MCP server）：run 内冻结，目录、索引、解锁与恢复四处共用。
        val mcpGroups = mcpProjection?.deferredGroups().orEmpty()
        // 跨 run 恢复的三个来源：历史工具名反推（静态组 + MCP server 组）、
        // 压缩边界标记（工具调用被压缩掉时的兜底）、决策模型预开。
        fun historyOpenedGroups(): Set<String> {
            val historyNames = historyToolNames(outboundHistory)
            return AgentToolGroups.deferredGroupsOf(historyNames, mcpGroups) +
                mcpProjection?.openedGroupIdsFrom(historyNames).orEmpty() +
                AgentToolGroups.openedGroupsFromMarkers(outboundHistory.map { it.content }) +
                // 决策预开：control 下 allowed 全集、advise 下只预开 selected∪sticky∪历史保护。
                decision?.openedGroups.orEmpty() +
                // off 模式（发送完整工具目录）：所有延迟组视为已打开，决策枚举随之失效。
                if (toolLoadingFullCatalog) {
                    AgentToolGroups.deferredGroups.mapTo(mutableSetOf()) { it.id } +
                        mcpGroups.mapTo(mutableSetOf()) { it.id }
                } else {
                    emptySet()
                }
        }
        // 缓存优先转发：与会话锁定互斥——off 档已下发完整目录，预设运行自己管工具集。
        val suffixMode = toolLoadingSuffixMode && !toolLoadingFullCatalog && presetToolNames == null
        // control 成功/fail-closed 时限制 tools_use_group 的枚举；null 表示不接管。
        val decisionOpenableLimit = decision?.openableGroupLimit
        /** 用户开关闸门：与目录构建同一套映射，不含决策收窄——那是 allowed 的职责。 */
        fun userSwitchAllowed(id: String): Boolean = when (id) {
            AgentToolGroups.SCREEN_GROUP.id -> config.screenTools
            AgentToolGroups.WEB_GROUP.id -> config.browserTools
            AgentToolGroups.TERMINAL_GROUP.id -> config.terminalTools
            AgentToolGroups.DEVICE_GROUP.id -> config.deviceTools
            AgentToolGroups.CALENDAR_GROUP.id -> config.personalTools
            AgentToolGroups.PERSONAL_GROUP.id -> config.personalTools
            AgentToolGroups.DEVICE_STATE_GROUP.id -> config.personalTools
            AgentToolGroups.EXTENSIONS_GROUP.id -> Prefs.isEnabled(Prefs.Keys.AGENT_EXTENSIONS_TOOLS)
            else -> false
        }
        /** 预开/增长只认用户开关与决策收窄，和目录构建用同一套组闸门。 */
        fun nativeGroupAllowed(id: String): Boolean {
            if (decisionOpenableLimit != null && id !in decisionOpenableLimit) return false
            return userSwitchAllowed(id)
        }
        // 词面预测预开：只用于决策真正锁死工具发现的两类场景——决策降级（failureReason）与
        // 收窄到空集（闲聊误判 / fail-closed）。这两类下模型既没有组索引也没有可用枚举，
        // 用户话里的强信号词是最后一条自救通道。
        // 常规 control 收窄不需要它：sticky 关键词新增（AgentDecisionStickyStore）已把
        // 强信号组带回 allowed 并预开；决策缺席时更不能开——否则「看看文件」这类请求
        // 会被弱词「文件」静默打开终端组（AgentDeferredToolGroupLoopTest 的既有约定）。
        val literalPredictedGroups = if (presetToolNames == null && !toolLoadingFullCatalog &&
            decision != null &&
            (decision.failureReason != null || decision.openableGroupLimit?.isEmpty() == true)
        ) {
            AgentToolGroups.predictFromText(prompt, ::userSwitchAllowed)
        } else {
            emptySet()
        }
        // 预设运行由 Runtime 显式给出组状态：不读历史/决策推出来的组，避免把无关组带进来。
        // 常规 run 必须并上 openedToolGroupsProvider()：调用方（会话恢复）在第 1 轮就依赖它
        // 下发延迟组工具——只读 historyOpenedGroups 会让第 1 轮目录缺组，模型调用即被拒，
        // 第 2 轮才恢复（toolsForRound 里 provider 与 initial 的并集就是这个教训，见下）。
        // 词面预测并入（见 literalPredictedGroups）：决策误判闲聊时，用户话里的强信号词
        // 仍能把组带进第一轮目录。
        var initialOpenedGroups =
            presetOpenedGroups ?: (openedToolGroupsProvider() + historyOpenedGroups() + literalPredictedGroups)
        // 会话级原生组集合：run 内冻结，只在「前缀还小」的窗口里允许把历史用过的组升进来。
        // 升级会让 tools 变一次、缓存重写一次；前缀小的时候这笔重写很便宜，所以用字符预算
        // 而不是轮数来控制这个窗口。
        val nativeGroups = if (!suffixMode) null else resolveNativeGroups(
            sessionId = sessionId,
            prompt = prompt,
            history = outboundHistory,
            historyGroups = historyOpenedGroups() + literalPredictedGroups,
            decisionGroups = decision?.openedGroups.orEmpty(),
            allowed = ::nativeGroupAllowed,
            approxChars = prompt.length + outboundHistory.sumOf { it.content.length + it.toolCallsJson.length },
        )
        // 决策闲聊态（chatOnly）逃生通道：发现面按用户开关全集渲染。
        // 判断模型把请求判成闲聊时（openableGroupLimit=空集 + 组索引被裁），模型必须仍能
        // 经 tools_use_group / tools_search 发现并打开它需要的组——见 AgentToolCatalog
        // 的 groupDiscoveryUnrestricted KDoc（2026-11-14 日志取证的死锁链）。
        val discoveryUnrestricted = decision?.chatOnly == true
        fun toolsFor(
            capabilities: AgentToolCapabilities,
            memoryEnabled: Boolean = memoryContext.enabled,
            recipeTools: JSONArray = JSONArray(),
            openedToolGroups: Set<String> = initialOpenedGroups,
            /** 预设工具名；null = 常规目录。退化回普通对话时传 null 重建。 */
            preset: Set<String>? = presetToolNames,
        ): JSONArray {
            searchIndex?.clear()
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
                dispatchTools = dispatchTools,
                capabilities = capabilities,
                localWebSearch = !config.usesHostedWebSearch,
                openedToolGroups = openedToolGroups,
                // 决策收窄后的可打开组；null 时不限制（advise / fail-open）。
                openableGroupLimit = decisionOpenableLimit,
                // 决策判闲聊：发现面不按决策收窄（用户开关仍是上限），模型可自救开组。
                groupDiscoveryUnrestricted = discoveryUnrestricted,
                // control 收窄到空集时是否仍保留 tools_use_group（默认开，见 Prefs）。
                allowEmptyGroupTool = Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_EMPTY_GROUP_RESCUE),
                // 单工具禁用集：每轮重读，用户在两轮之间改工具清单页也能生效。
                disabledTools = Prefs.disabledTools(),
                // 命令执行是独立开关：关掉它仍可读写文件，但不能跑 shell。
                fileSystemTier = AgentFileSystemTier.of(
                    terminalTools = config.terminalTools,
                    shellTools = Prefs.isEnabled(Prefs.Keys.AGENT_SHELL_TOOLS),
                ),
                // 动态延迟组（MCP server）与搜索语料出口：见 AgentToolCatalog.build。
                dynamicGroups = mcpProjection?.deferredGroups().orEmpty(),
                searchIndex = searchIndex,
                toolSearchEnabled = toolLoadingSearchEnabled,
                // 预设运行：豁免延迟折叠并跳过 tools_search / tools_use_group。
                presetToolNames = preset,
                // 缓存优先档：只有会话级原生组的工具进目录，其余改由 tool_call 转发。
                nativeGroups = nativeGroups,
            )
            for (index in 0 until additionalTools.length()) {
                tools.put(additionalTools.opt(index))
            }
            // MCP 渐进加载：schema 按已打开 server 追加；off 模式下 openedToolGroups
            // 已含全部 server 组，等价全量。always 暴露模式走上面的 additionalTools。
            // 缓存优先档下 MCP 也不进 tools：快照能按单组渲染 schema（appendTools 只传
            // 该组 id），执行侧据此出回执文档并校验参数，再显式路由到 MCP 执行器。
            // 打开 server 因此不再改写前缀——这是转发路径最后一个机制性抖动源。
            if (mcpProjection != null && !suffixMode) {
                mcpProjection.appendTools(tools, openedToolGroups)
            }
            // 自进化配方工具放在最后：模型读完内置工具后，额外看到自己写的工具。
            // 缓存优先档例外：配方 schema 也不进 tools——模型每新建/停用一份配方都会改写前缀，
            // 整段缓存作废。改经 tool_call 转发，参数说明随 eta_author_tool / eta_list_recipes
            // 回执进入历史尾部；执行侧按配方真实 schema 校验后交回同一条 execute 链路。
            if (!suffixMode) {
                for (index in 0 until recipeTools.length()) {
                    tools.put(recipeTools.opt(index))
                }
            }
            if (compactionArchive != null) tools.put(AgentCompactionArchive.tool())
            // 目录构建之后追加的 MCP / 配方 / 压缩档案也要按预设收口，
            // 否则一次「纯对话」快捷运行仍会看到与本次任务无关的工具。
            return preset?.let { AgentToolCatalog.retainOnly(tools, it) } ?: tools
        }
        var effectivePresetTools = presetToolNames
        // 配方只读一次：首轮目录与首轮签名必须来自同一份快照，否则第 2 轮会误判为「配方变了」。
        var initialRecipeTools = recipeToolsProvider()
        var initialTools = toolsFor(initialCapabilities, recipeTools = initialRecipeTools)
        if (presetFallbackWhenEmpty && presetToolNames != null && initialTools.length() == 0) {
            // 命中日程但日历组在能力投影后实际为空（权限/开关/单工具禁用）：
            // 退回普通对话——正常目录、正常渐进加载与判断模型，而不是零工具纯对话。
            effectivePresetTools = null
            initialOpenedGroups = openedToolGroupsProvider() + historyOpenedGroups()
            initialTools = toolsFor(
                initialCapabilities,
                recipeTools = initialRecipeTools,
                openedToolGroups = initialOpenedGroups,
                preset = null,
            )
        }
        val tools = initialTools
        // 退化后不再注入时间，也不再宣传日历能力。
        val effectivePresetRunAt = if (effectivePresetTools != null) presetRunAt else null
        // 目录被能力投影清空而退回普通对话时，快速路径必须同步失效：常驻规则要恢复，
        // 否则会出现「专用短提示 + 完整通用目录」的错配。
        val fastTaskActive = calendarFastTask && effectivePresetTools != null
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
            // 缓存优先档按会话级原生组渲染：组索引与各组指引因此在会话内恒定，
            // 模型中途打开的组（含 MCP server）不再改动 system 文本。
            openedToolGroups = if (suffixMode) {
                nativeGroups.orEmpty()
            } else {
                initialOpenedGroups
            },
            groupCallsViaToolCall = suffixMode,
            delegationResultRuleInPrefix = delegationResultRuleInPrefix.get(),
            // 提示点名的工具必须真的在目录里：缺席的工具连名字都不出现在提示里。
            callableTools = AgentToolCatalog.toolNames(tools),
            onSystemCount = { systemCount = it },
            trimChatSections = trimChatSections,
            openableGroupLimit = decisionOpenableLimit,
            groupDiscoveryUnrestricted = discoveryUnrestricted,
            dynamicToolGroups = mcpGroups,
            presetRunAt = effectivePresetRunAt,
            screenObservationRule = screenObservationRule,
            calendarFastTask = fastTaskActive,
        )
        var transcriptStartIndex = messages.length()
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
        // 首轮签名在建 loop 之前就固定：否则第 2 轮会无条件重建一次目录与系统消息，
        // 而重建时重新采集的能力快照一旦与本轮请求不同，system 字节就变、缓存白丢。
        fun roundSignatureOf(recipeTools: JSONArray, openedGroups: Set<String>) = RoundCapabilitySignature(
            capabilities = initialCapabilities,
            skillIndex = skillContext.installedSkills.map { it.id to it.enabled },
            memoryRevision = memoryContext.revision,
            memoryEnabled = memoryContext.enabled,
            delegationAvailable = delegationAvailable,
            delegationResultRuleInPrefix = delegationResultRuleInPrefix.get(),
            interactiveModeEnabled = interactiveModeEnabled,
            openedToolGroups = if (suffixMode) nativeGroups.orEmpty() else openedGroups,
            // 缓存优先档下配方不进 tools，指纹恒为空，避免新建配方触发整段前缀重建。
            recipeRevision = if (suffixMode) "" else recipeTools.toString().hashCode().toString(),
            toolSwitches = ToolSwitchSnapshot.capture(),
            capabilitySwitches = capabilitySwitches,
        )
        var lastRoundSignature: RoundCapabilitySignature? = roundSignatureOf(initialRecipeTools, initialOpenedGroups)
        var lastRoundTools: JSONArray? = initialTools
        val loop = AgentLoop(
            sessionId = sessionId,
            maxRounds = if (fastTaskActive) fastPathMaxRounds else Int.MAX_VALUE,
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
            onHistoryCompacted = { pruningOnly ->
                transcriptStartIndex = messages.length()
                // 只有摘要压缩需要把结果解读规则放回前缀：它把保留尾之外的回执连正文一起
                // 替换成摘要，规则随之丢失；压缩本身已重写整段历史，这一次前缀变化不额外
                // 损失缓存。修剪不能翻：delegate_task 回执恒为敏感结果（SubAgentCoordinator
                // 的 execute 单出口；AgentChildTaskGroups 路由层的 error/list/replace 出口
                // 同样标 sensitive，错误与参数非法路径也不例外），敏感结果被修剪跳过
                // （pruneOversizedToolResults 的 sensitiveToolCallIds 过滤），规则不可能丢；
                // 此时翻旗只会让 tools+system 在历史最长的时刻整段重写，恰是本机制要避免的事。
                if (!pruningOnly) delegationResultRuleInPrefix.set(true)
            },
            // 压缩边界标记按动态组恢复被压缩掉的 MCP 调用。
            dynamicToolGroupsProvider = { mcpGroups },
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
                    // 配方表每轮重读：模型可能在本 run 中途新建或停用配方。
                    val recipeTools = recipeToolsProvider()
                    // 必须并上 run 启动时恢复的组：执行器只累积本 run 内打开的组，
                    // 若这里只读执行器，第二个 round 就会把「历史里恢复的组」重新摘掉——
                    // 前缀缩短 7.9k、整组工具消失，随后模型调用即被拒（真实日志里出现过）。
                    val nextOpenedGroups = openedToolGroupsProvider() + initialOpenedGroups
                    // 每轮都重建系统消息与工具目录要付约 50 KB JSON 构造 + 逐工具过滤。
                    // 只有「能力快照 + 技能索引 + 记忆修订」都变了才真正重建；否则复用上一轮
                    // 的目录与系统消息（两者都只由这三项决定）。
                    val signature = RoundCapabilitySignature(
                        capabilities = capabilities,
                        skillIndex = nextSkillContext.installedSkills.map { it.id to it.enabled },
                        memoryRevision = nextMemoryContext.revision,
                        memoryEnabled = nextMemoryContext.enabled,
                        delegationAvailable = delegationAvailable,
                        delegationResultRuleInPrefix = delegationResultRuleInPrefix.get(),
                        interactiveModeEnabled = interactiveModeEnabled,
                        // 缓存优先档用会话级原生组：任何组（含 MCP server）开/关都不改变
                        // 目录，也就不该触发重建。
                        openedToolGroups = if (suffixMode) nativeGroups.orEmpty() else nextOpenedGroups,
                        // 配方集合变了必须重建目录，否则新建的配方在这一轮仍然缺席。
                        // 缓存优先档下配方不进 tools，变更也就不该触发重建：tool_call 在调用时
                        // 直接读 store，新建的配方下一轮即可用，不需要目录为它让路。
                        recipeRevision = if (suffixMode) "" else recipeTools.toString().hashCode().toString(),
                        // 用户可能在两轮之间改开关：签名必须覆盖它，否则目录与系统提示不会刷新。
                        toolSwitches = ToolSwitchSnapshot.capture(),
                        // 关闭说明的内容同样随开关变化，必须一起参与判定。
                        capabilitySwitches = ToolCapabilitySwitches.capture(),
                    )
                    if (signature != lastRoundSignature) {
                        // 目录先算：提示只允许点名目录里真实存在的工具。
                        val nextTools = toolsFor(
                            capabilities = capabilities,
                            memoryEnabled = nextMemoryContext.enabled,
                            recipeTools = recipeTools,
                            openedToolGroups = nextOpenedGroups,
                            preset = effectivePresetTools,
                        )
                        val systemMessages = AgentPromptBuilder.buildSystemMessages(
                            config, nextSkillContext, nextMemoryContext, capabilities.rootAvailable, delegationAvailable,
                            capabilities.shellTools,
                            interactiveModeEnabled = interactiveModeEnabled,
                            // 缓存优先档按会话级原生组渲染 system：任何组开/关都不改文本。
                            openedToolGroups = if (suffixMode) nativeGroups.orEmpty() else nextOpenedGroups,
                            groupCallsViaToolCall = suffixMode,
                            // 结果解读规则是否常驻前缀：本 run 首次派发走回执，续跑/压缩后才置位。
                            delegationResultRuleInPrefix = delegationResultRuleInPrefix.get(),
                            // 每轮重新捕获：用户可能在两轮之间改了开关。
                            disabledCapabilities = ToolCapabilitySwitches
                                .capture().toDisabledState(),
                            // 缺席的工具连名字都不出现在提示里，避免模型调一个必然被拒的工具。
                            callableTools = AgentToolCatalog.toolNames(nextTools),
                            // 决策 overlay 在本 run 内不变：每轮重建继续沿用同一裁剪与枚举上限。
                            trimChatSections = trimChatSections,
                            openableGroupLimit = decisionOpenableLimit,
                            groupDiscoveryUnrestricted = discoveryUnrestricted,
                            dynamicToolGroups = mcpGroups,
                            // 预设运行的时间口径跨轮稳定：复用 run 建立时捕获的同一份。
                            presetRunAt = effectivePresetRunAt,
                            // 快速路径跨轮同样只保留专用短规则，目录重建不能把它换回常驻段。
                            calendarFastTask = fastTaskActive,
                        )
                        // 系统前缀的条数会随「本轮打开了哪些工具组」变化（指引块与组索引都是条件消息）。
                        // JSONArray.put(index, …) 是替换不是插入，按索引覆写会在前缀变长时盖掉历史与用户消息，
                        // 变短时又会留下上一轮的陈旧指引。这里整段重排，并同步移动两个以条数为基准的边界。
                        val previousSystemCount = systemCount
                        rewriteSystemPrefix(messages, systemMessages, previousSystemCount)
                        systemCount = systemMessages.length()
                        transcriptStartIndex += systemCount - previousSystemCount
                        promptRootAvailable = capabilities.rootAvailable
                        lastRoundTools = nextTools
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

    /**
     * 历史里出现过的工具名。
     *
     * 脱敏只替换 arguments，`function.name` 仍然保留，所以敏感工具的组也能被识别出来。
     */
    private fun historyToolNames(history: List<ConversationMessage>): Set<String> = buildSet {
        history.forEach { message ->
            val raw = message.toolCallsJson
            if (raw.isBlank()) return@forEach
            val calls = runCatching { JSONArray(raw) }.getOrNull() ?: return@forEach
            for (index in 0 until calls.length()) {
                val function = calls.optJSONObject(index)?.optJSONObject("function") ?: continue
                val name = function.optString("name").takeIf { it.isNotBlank() } ?: continue
                // 缓存优先档的历史里，真实工具名在 tool_call 的参数里；只取外层名会让
                // 「本会话已经用过的组」读不出来，原生集合就永远长不大。
                add(AgentSensitiveToolPolicy.resolveName(name, function.optString("arguments")))
            }
        }
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
        /** 结果解读规则是否常驻系统前缀；置位后前缀多一条消息，必须参与重建判定。 */
        val delegationResultRuleInPrefix: Boolean = false,
        val interactiveModeEnabled: Boolean,
        /**
         * 决定「哪些延迟组下发原生 schema」的集合。
         *
         * 渐进加载档传本轮已打开的组；缓存优先档传会话级原生组集合——
         * 后者在 run 内不随开组变化，因此开组不会再触发整段前缀重建。
         */
        val openedToolGroups: Set<String> = emptySet(),
        /** 配方集合的指纹；模型在本 run 内新建/停用配方时必须触发目录重建。 */
        val recipeRevision: String = "",
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
            delegationTools = delegationTools,
        )
    }

    /**
     * 缓存优先转发（search/group 档）的会话级原生组集合。
     *
     * 集合决定「哪些延迟组下发原生 schema」。它必须在 run 开始前就固定：run 中途变一次，
     * `tools` 就变一次，而 `tools` 排在 system 与历史之前，会连带整段前缀失效。
     *
     * 唯一的增长窗口是「前缀还小的时候」：重写成本与前缀大小成正比，早期升级换到的是
     * 高频组的原生 schema 质量，前缀变大后冻结，新组一律经 `tool_call` 以后缀形式追加。
     */
    private fun resolveNativeGroups(
        sessionId: String,
        prompt: String,
        history: List<ConversationMessage>,
        historyGroups: Set<String>,
        decisionGroups: Set<String>,
        allowed: (String) -> Boolean,
        approxChars: Int,
    ): Set<String> {
        val staticDeferred = AgentToolGroups.deferredGroups.mapTo(mutableSetOf()) { it.id }
        // MCP 组刻意不进原生集合：升原生 = schema 回到 `tools`，但 MCP 的渲染、参数校验与
        // 执行只有快照这一份来源，等于要重建整套目录侧通路。收益（省一次回执）远小于风险
        // （双源漂移），且 MCP 打开本就不改写前缀（appendTools 抑制 + 回执转发），没有抖动
        // 要解决。MCP 重的会话因此每次调用都付间接层，真机对拍时单独量一组。
        val withinBudget = approxChars <= NATIVE_GROUP_GROWTH_CHAR_BUDGET
        // 历史里已经真正用过的组最可信：它比关键词预测稳，也比预测更不需要解释。
        val growable = (historyGroups + decisionGroups).filterTo(mutableSetOf()) { it in staticDeferred }
        val snapshot = Prefs.nativeGroups(sessionId)
        val seed = if (withinBudget) {
            AgentToolGroups.predictFromText(
                prompt + "\n" + history.asReversed().asSequence()
                    .filter { it.role == "user" }
                    .take(2)
                    .joinToString("\n") { it.content },
                allowed,
            ) + growable
        } else {
            // 恢复来的长会话没有快照（单槽被别的会话占掉）：只能用历史反推的组兜底。
            growable
        }
        val groups = when {
            snapshot == null -> seed
            snapshot.frozen || !withinBudget -> snapshot.groups
            else -> snapshot.groups + growable
        }.filterTo(mutableSetOf()) { it in staticDeferred && allowed(it) }
        val frozen = snapshot?.frozen == true || !withinBudget
        if (snapshot == null || snapshot.groups != groups || snapshot.frozen != frozen) {
            Prefs.setNativeGroups(sessionId, groups, frozen)
        }
        return groups
    }

    /** 「新组升级进原生集合」的前缀字符预算，约等于 8k token。 */
    private const val NATIVE_GROUP_GROWTH_CHAR_BUDGET = 32_000

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

    /**
     * MCP 工具的渐进加载投影。MCP 工具不在 [AgentToolCatalog] 的静态目录里，
     * 延迟化由快照在目录重建的各环节外挂：
     * - 目录/索引/枚举需要「每个 server 一个动态延迟组」（[deferredGroups]）；
     * - 目录组装时只追加已打开 server 的 schema（[appendTools]）；
     * - tools_search 的评分语料（[toolDescriptions]）与跨 run 恢复（[openedGroupIdsFrom]）。
     * run 内冻结；always 暴露模式不用它（工具照旧全量追加）。
     */
    interface McpToolProjection {
        fun deferredGroups(): List<AgentToolGroup>

        /** [openedGroupIds] 为 null 表示全量（always 模式或「所有组已打开」）。 */
        fun appendTools(destination: JSONArray, openedGroupIds: Set<String>?)

        fun toolDescriptions(): Map<String, String>

        fun openedGroupIdsFrom(toolNames: Collection<String>): Set<String>
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

/** 结果解读规则在派发回执里的字段名；不在脱敏放行清单里，因此不会进持久会话。 */
private const val DELEGATION_RESULT_RULE_KEY = "result_rules"

/**
 * 把结果解读规则挂到 `delegate_task` 回执上，让它落在历史尾部而不是系统前缀。
 *
 * 回执是 JSON，直接拼文本会破坏结构；解析不出对象时（错误回执可能是纯文本）退化为追加。
 * `sensitive` 与图片原样保留：规则只影响当前 run 的模型可见历史，持久化仍按脱敏走。
 */
private fun AgentModelClient.ToolResult.withDelegationResultRule(): AgentModelClient.ToolResult {
    val rule = AgentPromptBuilder.DELEGATION_RESULT_RULE
    val merged = runCatching { JSONObject(content).put(DELEGATION_RESULT_RULE_KEY, rule).toString() }
        .getOrElse { content + "\n\n" + rule }
    return copy(content = merged)
}
