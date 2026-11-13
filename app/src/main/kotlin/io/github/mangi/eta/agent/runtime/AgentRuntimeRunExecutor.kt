package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Messenger
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionCoordinator
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.config.InteractiveModePreference
import io.github.mangi.eta.agent.delegation.*
import io.github.mangi.eta.agent.decision.AgentDecisionMismatch
import io.github.mangi.eta.agent.decision.AgentDecisionRouter
import io.github.mangi.eta.agent.browser.ChildBrowserSession
import io.github.mangi.eta.agent.dispatch.AgentDispatchPayload
import io.github.mangi.eta.agent.dispatch.AgentDispatchRuntime
import io.github.mangi.eta.agent.model.AgentCalendarIntent
import io.github.mangi.eta.agent.model.AgentDispatchToolCatalog
import io.github.mangi.eta.agent.model.AgentScreenIntent
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.agent.model.XiaomiHostToolCatalog
import io.github.mangi.eta.agent.model.XiaomiUiAgentToolCatalog
import io.github.mangi.eta.agent.xiaomi.XiaomiToolsBridgeProtocol
import io.github.mangi.eta.agent.xiaomi.XiaomiToolsBridgeRemoteExecutor
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpToolExecutor
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentDangerousOperationPolicy
import io.github.mangi.eta.agent.tool.AgentRiskAssessment
import io.github.mangi.eta.agent.tool.AgentToolReviewCoordinator
import io.github.mangi.eta.agent.tool.AgentToolReviewVerdict
import io.github.mangi.eta.agent.tool.AssistantToolSwitches
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.PendingSkillConflictCapabilityParser
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.agent.voice.AgentVoiceToolPresets
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Blocking execution of one parent. A child group is a separate owner, not a controller binding. */
internal class AgentRuntimeRunExecutor(
    context: Context,
    private val entryToolTarget: Messenger? = null,
    private val currentPermissions: () -> AgentRuntimePolicy.Permissions,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val guiControl: GuiControlCoordinator,
    private val onAcceptedEvent: (AgentEvent, EntrySurfaceGuard?) -> Unit,
    private val persistArtifacts: (AgentRuntimeWire.RunRequest, AgentRuntimeWire.RunResult, List<AgentEvent>) -> Unit,
    private val xiaomiToolsBridgeInvoker: ((
        XiaomiToolsBridgeProtocol.CallRequest,
    ) -> XiaomiToolsBridgeProtocol.CallResult)? = null,
    private val hostToolExecutor: AgentHostToolExecutor? = null,
) {
    data class Outcome(
        val result: AgentRuntimeWire.RunResult,
        val entrySurfaceGuard: EntrySurfaceGuard?,
        val completedRequest: AgentRuntimeWire.RunRequest? = null,
        val response: AgentModelClient.ModelResponse.Text? = null,
        val shouldUpdateHost: Boolean,
    )
    private val appContext = context.applicationContext

    fun execute(session: AgentRuntimeSession, request: AgentRuntimeWire.RunRequest): Outcome {
        val runController = session.controller
        // 关联行：让诊断 zip 能把 run 对回会话与派发；缺失字段统一写 "-"，不打印正文。
        val dispatchPayload = AgentDispatchPayload.from(request.handoff?.payload)
        AndroidAgentLogger.info(
            "Agent run start: conversation=${(dispatchPayload?.conversationId ?: request.effectiveModelSessionId).ifBlank { "-" }} " +
                "run=${request.runId.ifBlank { "-" }} dispatch=${dispatchPayload?.dispatchId ?: "-"}",
        )
        val archivedEvents = mutableListOf<AgentEvent>()
        var entrySurfaceGuard: EntrySurfaceGuard? = null
        var localTools: AgentLocalTools? = null
        var deliveryFailure: String? = null
        var cleanupFailure: String? = null
        var modelCompleted = false
        var virtualDeliveryCompleted = false
        var toolsBinding: AgentRunController.ResourceBinding? = null
        var guiRun: GuiControlCoordinator.RunControl? = null
        var toolsOwner: AgentChildToolOwnership? = null
        var groupGeneration: String? = null
        var replacementGeneration: String? = null
        val registeredChildGenerations = mutableListOf<String>()
        val childContextSink = AtomicReference<((SubAgentContextStats) -> Unit)?>(null)
        // “每次询问”选前台后补给服务的 ToolStarted 来源。
        val foregroundReplay = AgentForegroundReplay()
        val childSessionId = request.effectiveModelSessionId
        // 按助手的工具开关：只能收紧。全局关掉的能力不会被助手配置重新打开。
        val assistantTools = AssistantRepository.currentProfile(request.assistantId)
        val allowBrowser = AssistantToolSwitches.allows(
            assistantTools, AssistantToolSwitches.WEB, request.config.browserTools)
        val allowTerminal = AssistantToolSwitches.allows(
            assistantTools, AssistantToolSwitches.TERMINAL, request.config.terminalTools)
        val allowDevice = AssistantToolSwitches.allows(
            assistantTools, AssistantToolSwitches.DEVICE, request.config.deviceTools)
        val allowPersonal = AssistantToolSwitches.allows(
            assistantTools, AssistantToolSwitches.PERSONAL, request.config.personalTools)
        val allowScreen = AssistantToolSwitches.allows(
            assistantTools, AssistantToolSwitches.SCREEN, request.config.screenTools)
        // 小米入口工具：只有带 replyTo 反向通道且入口探测到工具的 run 才参与下发与路由。
        // 三档风险映射对齐 AgentDeviceToolCatalog：direct/action=设备工具，read=个人数据。
        val enabledEntryTools = if (entryToolTarget == null) {
            emptySet()
        } else {
            XiaomiUiAgentToolCatalog.enabledToolNames(
                availableTools = request.entryTools,
                directTools = allowDevice && request.config.deviceTools,
                sensitiveReadTools = allowPersonal && request.config.personalTools,
                sensitiveActionTools = allowDevice && request.config.deviceTools,
            )
        }
        // Parent-only prompt policy, frozen for this run rather than re-read between rounds.
        val interactiveModeEnabled = InteractiveModePreference.read()
        var response: AgentModelClient.ModelResponse.Text? = null
        var cancelled = false
        var checkpointRecorder: AgentRunCheckpointRecorder? = null
        var unownedSkillRoot: java.io.File? = null
        val timing = AgentRunTiming(AndroidAgentLogger)
        childContextSink.set { stats ->
            if (!session.isTerminal) acceptEvent(session, AgentEvent.ChildContextUpdated(stats),
                archivedEvents, entrySurfaceGuard, checkpointRecorder)
        }
        session.childCompactor = { taskId, keep, model ->
            AgentChildTaskGroups.requestCompact(childSessionId, taskId, keep, model)
        }
        var result = try {
            runController.throwIfCancelled()
            checkpointRecorder = AgentRunCheckpointRecorder.create(appContext, request)
            entrySurfaceGuard = EntrySurfaceGuard.from(request.handoff, AndroidAgentLogger) {
                EtaAssistantOverlayService.dismissForForegroundOperation(appContext)
            }
            val skillIndexService = SkillRuntime.createIndexService(appContext)
            val skillLoader = SkillRuntime.createLoader(appContext)
            val skillResourceReader = SkillRuntime.createResourceReader(appContext)
            val skillPackageInstaller = SkillRuntime.createPackageInstaller(appContext)
            val githubSkillSource = PublicGitHubSkillSource(appContext.cacheDir, AgentHttpClient.client)
            val assistant = requireNotNull(AssistantRepository.currentProfile(request.assistantId)) { "任务所属助手不存在，请重新发起任务" }
            val enabledSkillIds = assistant.enabledSkillIds.toSet()
            // Every skill mutation invalidates the shared index, so a per-run forced rescan
            // would only re-walk the skill roots on the first-request path.
            val (runSkillsRoot, runSkillEntries) = SkillRuntime.createRunSkills(appContext, assistant.id,
                skillIndexService.listSkillsForManagement()
                    .filter { it.installed && it.id in enabledSkillIds }
                    .filter { SkillCompatibilityChecker.evaluate(it).available })
            unownedSkillRoot = runSkillsRoot
            val skillContext = SkillContext(installedSkills = runSkillEntries)
            val memoryEnabled = AgentMemoryRepository.isEnabled(assistant.id)
            // 调度工具是调度台身份的专属能力：闸门在身份，不在全局开关。
            val dispatchEnabled = assistant.id == io.github.mangi.eta.data.model.AssistantPrompt.DISPATCHER_ID
            val memoryContext = if (memoryEnabled) runCatching {
                AgentMemoryContextBuilder.build(AgentMemoryRepository.snapshot(assistant.id), request.config.contextWindow)
            }.getOrElse { throwable ->
                AndroidAgentLogger.warnThrottled("agent_memory_context_failed") { "Agent memory context unavailable: type=${throwable.safeLogType()}" }
                AgentMemoryContextBuilder.empty(request.config.contextWindow)
            } else AgentMemoryContext.DISABLED
            val pendingSkillConflict = PendingSkillConflictCapabilityParser.parse(request.history)
            val mcpSnapshot = runBlocking {
                runCatching { McpRunSnapshot.load() }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_mcp_snapshot_failed") { "MCP tool snapshot unavailable: type=${throwable.safeLogType()}" }
                    McpRunSnapshot.EMPTY
                }
            }
            val xiaomiToolsBridgeCatalog = if (xiaomiToolsBridgeInvoker == null) {
                XiaomiToolsBridgeProtocol.Catalog.EMPTY
            } else {
                XiaomiToolsBridgeProtocol.catalogFromJson(
                    request.xiaomiToolsBridgeCatalogJson,
                )
            }
            val xiaomiToolsBridgeExecutor = xiaomiToolsBridgeInvoker
                ?.takeUnless { xiaomiToolsBridgeCatalog.definitions.isEmpty() }
                ?.let { invokeRemote ->
                    XiaomiToolsBridgeRemoteExecutor(
                        catalog = xiaomiToolsBridgeCatalog,
                        sessionId = request.runId,
                        riskEnabled = { risk ->
                            when (risk) {
                                XiaomiToolsBridgeProtocol.Risk.DIRECT ->
                                    allowDevice && request.config.deviceTools && currentPermissions().deviceTools
                                XiaomiToolsBridgeProtocol.Risk.SENSITIVE_READ ->
                                    allowPersonal && request.config.personalTools && currentPermissions().personalTools
                                XiaomiToolsBridgeProtocol.Risk.SENSITIVE_ACTION ->
                                    allowDevice && request.config.deviceTools && currentPermissions().deviceTools
                            }
                        },
                        invokeRemote = invokeRemote,
                    )
                }
            val runSurface = session.taskSurfaceMode
            // 副屏工具只在明确选择后台后暴露；ASK 首轮由执行位置弹窗决定后续轮次。
            val runVirtualDisplay = {
                session.taskSurfaceMode == io.github.mangi.eta.agent.device.AgentTaskSurfaceMode.BACKGROUND
            }
            // 渐进加载模式在会话首个 run 锁定（对齐 ZCode「新建会话生效，当前会话保持」），
            // 压缩边界例外：见下面 onEvent 里的 unlockToolLoadingModeAtCompaction。
            // search = 延迟组 + tools_search 命中解锁；group = 仅 tools_use_group 整组打开；
            // off = 发送完整工具目录。三档的布尔量一一对应：fullCatalog = off、
            // searchEnabled = search、suffix（转发 + 原生组集合）= 非 off。
            val toolLoadingMode = runCatching {
                Prefs.resolveToolLoadingMode(request.effectiveModelSessionId)
            }.getOrDefault(Prefs.Keys.TOOL_LOADING_MODE_SEARCH)
            val toolLoadingFullCatalog = toolLoadingMode == Prefs.Keys.TOOL_LOADING_MODE_OFF
            // 日程任务预热：运行建立阶段按本轮指令做确定性判定，所有入口同一条链路。
            // 显式快捷功能预设优先；命中关键词时要求个人数据开关开着，且日历读写权限或 root
            // 至少有一个可用，否则不预热（避免绕过开关/权限，也避免下发空目录）。
            val voicePreset = AgentVoiceToolPresets.resolve(request.toolPreset)
            val calendarIntentHit = voicePreset == null && AgentCalendarIntent.matches(request.prompt)
            val calendarGroupUsable = calendarIntentHit && allowPersonal && runCatching {
                AgentToolCapabilities.capture(appContext)
            }.getOrNull()?.let { it.rootAvailable || it.calendarReadable || it.calendarWritable } == true
            // 「添加日程」快速路径：语音 chip 显式命中，或主界面指令里有明确的写入动词。
            // 只读/识别类请求（如「识别截图中的日程」）不进快速路径，也不预热写入目录。
            // 前置要求个人数据开关真的开着：否则目录会被投影清空、complete 退化回普通对话，
            // 而这里已经压低了思考档，就会出现「完整常驻提示 + 关思考」的错配。
            val calendarToolsEnabled = allowPersonal && request.config.personalTools
            val calendarFastTask = calendarToolsEnabled && (
                voicePreset?.fastPath == true ||
                    (calendarGroupUsable && AgentCalendarIntent.writeIntent(request.prompt))
            )
            val presetToolNames: Set<String>? = when {
                calendarFastTask -> AgentVoiceToolPresets.calendarFastToolNames
                else -> voicePreset?.toolNames
                    ?: AgentCalendarIntent.toolNames.takeIf { calendarGroupUsable }
            }
            val presetOpenedGroups: Set<String>? = voicePreset?.openedGroups
                ?: AgentCalendarIntent.openedGroups.takeIf { calendarGroupUsable }
            val presetRun = presetToolNames != null
            // 日程相关预设（含关键词命中）在请求发起这一刻取真实系统时间，跨轮复用同一份，
            // 让模型直接换算「明天下午三点」，不必先调取时工具。
            val presetRunAt = if (voicePreset?.injectCurrentTime == true || calendarGroupUsable) {
                java.time.Instant.now()
            } else {
                null
            }
            // 命中日程但目录实际为空（工具被单独禁用等）时安全退化回普通对话；
            // 纯对话预设（翻译/整理）保持零工具，不退化。
            val presetFallbackWhenEmpty = voicePreset?.injectCurrentTime == true || calendarGroupUsable
            // 这里只表达会话档位；预设运行时由目录层按 preset 非空自行关闭 tools_search。
            // 这样命中日程但目录为空退化回普通对话时，仍保留原有的搜索能力。
            val toolLoadingSearchEnabled = toolLoadingMode == Prefs.Keys.TOOL_LOADING_MODE_SEARCH
            // 缓存优先转发：search/group 档延迟组工具都经固定入口 tool_call 转发，
            // 目录在会话内（原生组集合冻结的意义上）字节恒定；off 档保持全量原生 schema。
            // preset 运行不参与：模型侧 suffixMode 按 presetToolNames 排除（中途退化回
            // 普通对话时也保持 false），这里同步排除，两侧的目录与回执语义逐项一致。
            val toolLoadingSuffixMode =
                toolLoadingMode != Prefs.Keys.TOOL_LOADING_MODE_OFF && !presetRun
            val mcpDeferred = runCatching { Prefs.mcpToolExposureDeferred() }.getOrDefault(true)
            val mcpProjection: AgentModelClient.McpToolProjection? =
                if (mcpDeferred && !toolLoadingFullCatalog) mcpSnapshot else null
            val mcpTools = if (mcpProjection == null) {
                JSONArray().also(mcpSnapshot::appendModelTools)
            } else {
                // deferred 暴露：schema 由 AgentModelClient 每轮按已打开 server 追加。
                JSONArray()
            }
            // tools_search 的评分语料：目录重建时填入内置工具描述，MCP 描述在快照就绪后并入。
            val toolSearchIndex = java.util.concurrent.ConcurrentHashMap<String, String>()
            if (mcpProjection != null) toolSearchIndex.putAll(mcpSnapshot.toolDescriptions())
            // 缓存优先转发（search/group 档）：tool_call 壳会落到 AgentLocalTools，而外层
            // 路由按工具名分派、够不到 MCP 执行器，因此必须把它与 schema 渲染一起注入，
            // 转发才能在按真实 schema 校验后把调用交给它。
            val mcpExecutor = McpToolExecutor(mcpSnapshot)
            // 手机操作权按 run 登记：状态变化经 GuiControlChanged 投送给服务，run 结束即释放。
            guiRun = guiControl.openRun(request.runId, runController) { status ->
                acceptEvent(session, AgentEvent.GuiControlChanged(status), archivedEvents, entrySurfaceGuard, checkpointRecorder)
            }
            // 判断模型 overlay 在本轮 run 内只算一次；执行器通过 provider 读它，
            // 用来把「被决策收窄」与「用户关闭」区分成不同错误来源。
            val decisionPlanRef = java.util.concurrent.atomic.AtomicReference<
                io.github.mangi.eta.agent.decision.AgentDecisionPlan?>(null)
            // 智能审查档的模型裁决闭包：捕获本次 run 的用户请求与配置（这里才拿得到
            // ModelConfig / 控制器 / 会话上下文），随 confirm 闭包逐次传给策略——
            // 曾挂在进程级 var 上，多 run 并发时以最后一次注入为准，别的 run 会拿错
            // 裁决上下文。失败由策略回退规则确认，绝不直接放行。
            val smartReviewForRun: (String, String, AgentRiskAssessment) -> AgentToolReviewVerdict? =
                { name, arguments, assessment ->
                    val recentRequest = request.history
                        .asReversed()
                        .firstOrNull { it.role == "user" && it.content.isNotBlank() }
                        ?.content
                        ?.take(1500)
                        ?: request.prompt.take(1500)
                    AgentToolReviewCoordinator.review(
                        toolName = name,
                        argumentsJson = arguments,
                        riskLevel = assessment.level.name,
                        riskReasons = assessment.reasons,
                        conversationContext = recentRequest,
                        fallbackModelConfig = request.config,
                        controller = runController,
                        sessionId = request.effectiveModelSessionId,
                    )
                }
            val executor = AgentLocalTools(
                context = appContext, logger = AndroidAgentLogger, browserRunId = request.runId,
                guiControl = guiRun,
                browserConversationId = request.handoff?.takeIf {
                    it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE
                }?.let { AgentUiHandoffPayload.from(it.payload).conversationId }.orEmpty(),
                agentConversationId = request.effectiveModelSessionId,
                decisionAllowedGroups = { decisionPlanRef.get()?.openableGroupLimit },
                // 决策闲聊态逃生通道：目录（AgentToolCatalog.groupDiscoveryUnrestricted）与
                // 提示（组索引）都按用户开关全集宣传了可打开的组，执行侧闸门必须同步放行，
                // 否则模型照索引打开组会被拒——三处读同一个 plan 字段，保证目录、提示、
                // 执行一致。用户组开关仍是执行侧的独立上限（toolGroupGate）。
                decisionDiscoveryUnrestricted = { decisionPlanRef.get()?.chatOnly == true },
                // 与 AgentModelClient.suffixMode 对齐：preset 排除必须两侧一致，否则预设运行
                // 目录发原生 schema、执行器却按转发出回执（含中途退化回普通对话的路径——
                // 模型侧的 suffixMode 用原始 presetToolNames 判定，同样保持 false）。
                groupToolsViaCall = toolLoadingSuffixMode && !presetRun,
                frozenSurface = runSurface,
                chooseSurface = { toolName, toolsClosed ->
                    // 先写会话再等弹窗退场；返回值取自会话，工具侧与会话保持一致。
                    AgentTaskSurfaceChoice.choose(
                        session = session,
                        await = {
                            io.github.mangi.eta.agent.device.AgentTaskPrompt.await(
                                runId = request.runId,
                                cancelled = { toolsClosed() || runController.isCancelled },
                                show = { prompt -> io.github.mangi.eta.ui.AgentTaskSurfaceDialogActivity.launch(appContext, prompt.id) },
                            )
                        },
                        awaitHostHidden = { io.github.mangi.eta.agent.device.AgentTaskPrompt.awaitHostHidden() },
                        // 选前台前 ToolStarted 已发出但服务没处理：只补给服务做悬浮窗与前台记录，不进对话记录和回放。
                        onForegroundResolved = {
                            onAcceptedEvent(foregroundReplay.startedEvent(toolName), entrySurfaceGuard)
                        },
                    )
                },
                browserToolsEnabled = { allowBrowser && currentPermissions().browserTools },
                screenToolsEnabled = { allowScreen && currentPermissions().screenTools },
                terminalToolsEnabled = { allowTerminal && currentPermissions().terminalTools },
                deviceToolsEnabled = { allowDevice && currentPermissions().deviceTools },
                personalToolsEnabled = { allowPersonal && currentPermissions().personalTools },
                virtualDisplayEnabled = { currentPermissions().virtualDisplay },
                memoryToolsEnabled = { AgentMemoryRepository.isEnabled(assistant.id) },
                screenshotExcludedPackages = { entrySurfaceGuard?.consumeScreenshotExcludedPackages().orEmpty() },
                // 危险操作逐次确认：未确认时拒绝本次调用，用户同意后重发即可通过。
                // runId/conversationId 进闸门的待确认槽与授权键（按 run 等确认、按会话授权）。
                confirmDangerousOperation = { toolName, argumentsJson ->
                    AgentDangerousOperationPolicy.decision(
                        toolName,
                        argumentsJson,
                        runId = request.runId,
                        conversationId = request.effectiveModelSessionId,
                        review = smartReviewForRun,
                    )
                },
                // 自建配方的 Root shell 步骤同样要过命令启发式，避免用配方名绕过确认。
                confirmRecipeDangerousOperation = { toolName, argumentsJson, shellCommands ->
                    AgentDangerousOperationPolicy.decisionForRecipe(
                        toolName,
                        argumentsJson,
                        shellCommands,
                        runId = request.runId,
                        conversationId = request.effectiveModelSessionId,
                        review = smartReviewForRun,
                    )
                },
                beforeToolExecution = { toolName ->
                    val requiresAccessibility = AgentToolRequirements.requiresAccessibility(toolName)
                    if (!requiresAccessibility && !AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(toolName)) {
                        ToolExecutionDecision.Allow
                    } else {
                        val accessibility = if (requiresAccessibility) AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext) else null
                        when {
                            accessibility != null && !accessibility.available -> ToolExecutionDecision.Reject(accessibility.code, accessibility.message)
                            entrySurfaceGuard?.dismissOnce() == false -> ToolExecutionDecision.Reject("ENTRY_SURFACE_NOT_READY", "入口窗口关闭未完成；本次工具未执行，请勿在当前任务中重复调用")
                            else -> ToolExecutionDecision.Allow
                        }
                    }
                },
                skillIndexService = skillIndexService, skillLoader = skillLoader,
                skillResourceReader = skillResourceReader, githubSkillSource = githubSkillSource,
                skillPackageInstaller = skillPackageInstaller,
                runAvailableSkillIds = skillContext.installedSkills.mapTo(mutableSetOf()) { it.id },
                runSkillEntries = skillContext.installedSkills, memoryAssistantId = assistant.id,
                runSkillsRoot = runSkillsRoot, pendingSkillConflict = pendingSkillConflict,
                dynamicToolGroups = { if (mcpProjection != null) mcpSnapshot.deferredGroups() else emptyList() },
                // MCP 组的回执文档与参数校验同源：快照按单组渲染 schema，与目录追加同一份。
                mcpToolSchemas = { groupId -> JSONArray().also { mcpSnapshot.appendTools(it, setOf(groupId)) } },
                mcpToolExecutor = { call -> mcpExecutor.execute(call) },
                toolSearchDescriptions = { toolSearchIndex },
                toolSearchEnabled = { toolLoadingSearchEnabled },
            )
            localTools = executor
            unownedSkillRoot = null
            val entryExecutor = entryToolTarget
                ?.takeIf { enabledEntryTools.isNotEmpty() }
                ?.let { target ->
                    AgentRuntimeEntryToolExecutor(
                        target = target,
                        allowedTools = enabledEntryTools,
                        directToolsEnabled = {
                            allowDevice && request.config.deviceTools && currentPermissions().deviceTools
                        },
                        sensitiveReadToolsEnabled = {
                            allowPersonal && request.config.personalTools && currentPermissions().personalTools
                        },
                        sensitiveActionToolsEnabled = {
                            allowDevice && request.config.deviceTools && currentPermissions().deviceTools
                        },
                        logger = AndroidAgentLogger,
                        beforeToolExecution = { toolName ->
                            // 小米工具同样可能拉起前台界面：先让入口浮层收起再放行。
                            if (AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(toolName) &&
                                entrySurfaceGuard?.dismissOnce() == false
                            ) {
                                ToolExecutionDecision.Reject(
                                    code = "ENTRY_SURFACE_NOT_READY",
                                    message = "入口窗口尚未确认关闭；本次工具未执行，请勿在当前任务中重复调用",
                                )
                            } else {
                                ToolExecutionDecision.Allow
                            }
                        },
                    )
                }
            val routingExecutor = RoutingToolExecutor(local = executor, mcp = mcpExecutor)
            val ownership = AgentChildToolOwnership { routingExecutor.close() }
            toolsOwner = ownership
            toolsBinding = runController.register {
                entryExecutor?.close()
                xiaomiToolsBridgeExecutor?.close()
                ownership.release()
            }
            timing.preparationFinished(skillContext.installedSkills.size)

            // Retain all exact-selection candidates, including explicit unavailable reasons.
            // This never re-resolves a retained child's healthy configuration for continue.
            val ownerKey = SubAgentConfigKey.Conversation(request.effectiveModelSessionId)
            val childConfig = ConversationSubAgentPreferences().snapshot(ownerKey)
            val childCandidates = runBlocking { ChildWorkerConfigResolver.resolve(childSessionId, childConfig, parentConfig = request.config) }
            val childDispatchPlan = ChildTaskOrdinaryDispatchSelection.plan(childCandidates)
            val workspaceEnvironment = LinuxEnvironmentSettingsRepository.current(appContext).wireName
            val childWorkspace = if (allowTerminal && currentPermissions().terminalTools) SubAgentWorkspace(
                appContext, executor, ownerId = childSessionId, initialEnvironment = workspaceEnvironment,
                environment = { when (LinuxEnvironmentSettingsRepository.current(appContext)) {
                    LinuxDistribution.ALPINE -> "alpine"
                    LinuxDistribution.DEBIAN -> "debian"
                } },
                legacyIds = { project -> AgentChildTaskGroups.ownedWorkspaceIds(childSessionId, project, workspaceEnvironment) },
            ) else null
            fun createChildGroup(candidates: List<ChildTaskConfigPolicy.Candidate<ChildWorkerConfigResolver.Configuration>>): String? {
                val configuredChildren = AgentChildWorkerAvailability.configuredChildren(candidates)
                val childModels = configuredChildren.map { it.second }
                if (childModels.isEmpty()) return null
                val frozenParallelLimits = childModels.map { childConfig.parallelLimit(SubAgentParallelModel(it.providerId, it.model)) }
                // Retain BEFORE construction, which allocates scheduler and pool leases. Transfer only after registration.
                val childLease = checkNotNull(ownership.retain()) { "父任务已终止，无法创建子任务" }
                var childOwnershipTransferred = false
                var children: SubAgentCoordinator? = null
                try {
                    val workspace = childWorkspace
                    // Each invocation/continuation owns its own ephemeral browser; never forward it
                    // to the parent's AgentLocalTools (which is bound to the parent's browser page).
                    fun runTextChild(config: AgentModelClient.ModelConfig, prompt: String,
                        controller: AgentRunController, project: String, id: String?, writable: Boolean,
                        progress: (AgentEvent) -> Unit = {}): String {
                        val browser = if (io.github.mangi.eta.agent.browser.ChildBrowserPolicy.sessionAllowed(
                                allowBrowser && currentPermissions().browserTools, controller.childBrowserAccess.wire))
                            ChildBrowserSession(appContext, controller) { allowBrowser && currentPermissions().browserTools }
                        else null
                        try {
                            if (id != null) {
                                val backend = requireNotNull(workspace)
                                return SubAgentRunner.run(config, prompt, SubAgentWorkspace.childTools(writable),
                                    backend.childExecutor(project, id, writable, controller), controller,
                                    workspaceMode = true, writable = writable, sessionId = childSessionId,
                                    onProgress = progress, browserExecutor = browser?.executor)
                            }
                            // 子代理没有「按需打开工具组」的回合，因此这里直接把所有组视为已打开，
                            // 再由 SubAgentTools 的只读白名单决定最终下发什么（双重 fail-closed）。
                            val readTools = SubAgentTools.filter(AgentToolCatalog.build(
                                terminalTools = allowTerminal && currentPermissions().terminalTools,
                                browserTools = false, // Installed separately with a task-owned executor.
                                screenTools = false, // Children never get Android GUI tools.
                                deviceTools = allowDevice && currentPermissions().deviceTools,
                                personalTools = allowPersonal && currentPermissions().personalTools,
                                memoryTools = memoryEnabled,
                                capabilities = AgentToolCapabilities.capture(appContext).copy(virtualDisplay = runVirtualDisplay()),
                                openedToolGroups = AgentToolGroups.allIds,
                                disabledTools = Prefs.disabledTools()))
                            return SubAgentRunner.run(config, prompt, readTools, executor, controller,
                                sessionId = childSessionId, onProgress = progress, browserExecutor = browser?.executor)
                        } finally {
                            browser?.release()
                        }
                    }
                    var generationForCallback: String? = null
                    val poolScope = "${request.effectiveModelSessionId}:${request.runId}:${UUID.randomUUID()}"
                    children = SubAgentCoordinator(childModels,
                        roles = configuredChildren.map { it.first.role },
                        workerIds = configuredChildren.map { it.first.id },
                        workerNames = configuredChildren.map { it.first.name },
                        workerModelIds = configuredChildren.map { it.first.modelId },
                        modelParallelLimits = frozenParallelLimits,
                        poolScope = poolScope,
                        allowTimeoutContinuation = true,
                        diagnostics = SubAgentDiagnostics(request.runId),
                        workspace = workspace,
                        onTaskChanged = { generationForCallback?.let(AgentChildTaskGroups::onTaskChanged) },
                        prepareManualCompactor = { config -> io.github.mangi.eta.agent.model.AgentCompressionEndpoint.apply(
                            AgentRuntimePolicy.forCompression(config),
                            Prefs.localAgentPreferences()?.getString(Prefs.Keys.AGENT_COMPRESS_ENDPOINT_MODE, null)) },
                        executeImageChild = { config, prompt, controller, imageOptions -> SubAgentMediaRunner.run(
                            appContext, childSessionId, config, prompt, controller, video = false, imageOptions = imageOptions) },
                        executeVideoChild = { config, prompt, controller -> SubAgentMediaRunner.run(
                            appContext, childSessionId, config, prompt, controller, video = true) },
                        onContext = { stats -> childContextSink.get()?.invoke(stats) },
                        executeObservedChild = { config, prompt, controller, project, id, writable, progress ->
                            runTextChild(config, prompt, controller, project, id, writable, progress)
                        },
                        executeWorkspaceChild = { config, prompt, controller, project, id, writable ->
                            runTextChild(config, prompt, controller, project, id, writable)
                        },
                    ) { config, prompt, controller ->
                        runTextChild(config, prompt, controller, "", null, false)
                    }
                    val registered = AgentChildTaskGroups.register(appContext, request.effectiveModelSessionId, request.runId, children,
                        releaseTools = childLease::close, workspaceEnvironment = workspaceEnvironment,
                        workers = AgentChildWorkerAvailability.workers(candidates))
                    if (registered == null) error("无法启动子代理前台执行服务，请返回 Eta 后重试")
                    childOwnershipTransferred = true
                    generationForCallback = registered
                    registeredChildGenerations.add(registered)
                    AgentChildRunControl.registered(session, childSessionId, registered)
                    runController.throwIfCancelled()
                    return registered
                } finally {
                    if (!childOwnershipTransferred) {
                        try { children?.close() }
                        finally { childLease.close() }
                    }
                }
            }
            // New tasks use this run's current settings and coordinator. Existing task IDs still
            // route to their original groups; replacement keeps its separate authorization checks.
            groupGeneration = createChildGroup(childDispatchPlan.ordinary)
            replacementGeneration = if (childDispatchPlan.ordinary === childDispatchPlan.replacement) groupGeneration
                else createChildGroup(childDispatchPlan.replacement)
            val toolChildren = AgentChildWorkerAvailability.configuredChildren(
                if (groupGeneration != null) childDispatchPlan.ordinary else childDispatchPlan.replacement)
            // 独立的子代理开关：关闭后不再下发委派族工具（它会产生额外计费的模型调用），
            // 但已有子任务仍可通过发现类工具查看，不会被静默丢弃。
            val subAgentToolsAllowed = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_TOOLS)
            if (subAgentToolsAllowed && (groupGeneration != null || replacementGeneration != null)) {
                SubAgentTools.appendTo(mcpTools, toolChildren.mapIndexed { i, (slot, model) ->
                    SubAgentPreferences.workerDescription(slot, i + 1, model,
                        childConfig.parallelLimit(SubAgentParallelModel(model.providerId, model.model)))
                }, workspaceEnabled = childWorkspace != null)
            } else if (subAgentToolsAllowed) {
                ExistingChildTaskTools.appendTo(mcpTools)
            }
            // 小米三桥的工具 schema：能力集随请求注入，目录按开关与风险分组收敛；
            // 实际执行路由在下方委托链，授权复查在各执行器内部。
            XiaomiUiAgentToolCatalog.appendTo(mcpTools, enabledEntryTools)
            xiaomiToolsBridgeCatalog.visibleDefinitions(
                directTools = allowDevice && request.config.deviceTools,
                sensitiveReadTools = allowPersonal && request.config.personalTools,
                sensitiveActionTools = allowDevice && request.config.deviceTools,
            ).forEach { definition -> mcpTools.put(definition.asModelTool()) }
            XiaomiHostToolCatalog.appendTo(
                tools = mcpTools,
                capabilities = request.hostCapabilities,
                directTools = allowDevice && request.config.deviceTools,
                sensitiveActionTools = allowDevice && request.config.deviceTools,
            )
            val questionCoordinator = AgentQuestionCoordinator(runController) { event ->
                acceptEvent(session, event, archivedEvents, entrySurfaceGuard, checkpointRecorder)
            }
            session.questionCoordinator = questionCoordinator
            val delegatedExecutor = AgentModelClient.ToolExecutor { call ->
                runController.throwIfCancelled()
                if (call.name == "ask_user") {
                    val question = AgentQuestionCodec.parseArguments(call.argumentsJson,
                        conversationId = request.effectiveModelSessionId,
                        runId = request.runId, toolCallId = call.id,
                        questionId = "question-${UUID.randomUUID()}", createdAtMillis = System.currentTimeMillis())
                    val answer = try { questionCoordinator.awaitAnswer(question) }
                    catch (failure: Exception) {
                        runController.throwIfCancelled()
                        throw io.github.mangi.eta.agent.question.AgentQuestionInterruptedException(failure)
                    }
                    if (answer == null) {
                        runController.throwIfCancelled()
                        throw io.github.mangi.eta.agent.question.AgentQuestionInterruptedException()
                    }
                    // 危险操作的确认走「模型自己用 ask_user 问」：用户明确同意时登记一次性授权，
                    // 让随后重发的同一个调用能够通过闸门；其它回答不构成同意。
                    // runId 选定本 run 的待确认槽——并发 run 各问各的确认，不得互相代答。
                    runCatching {
                        io.github.mangi.eta.agent.tool.AgentDangerousConfirmationGate.onUserAnsweredScoped(
                            answeredKind = answer.kind,
                            optionId = answer.optionId,
                            runId = request.runId,
                        )
                    }
                    AgentModelClient.ToolResult(AgentQuestionCodec.resultJson(question, answer).toString())
                } else if (call.name == "manage_agent_workspace") {
                    val backend = childWorkspace
                    val payload = try { AgentWorkspaceAccessPolicy.execute(
                        argumentsJson = call.argumentsJson,
                        requestAllowsTerminal = allowTerminal,
                        runtimeAllowsTerminal = currentPermissions().terminalTools,
                        backendAvailable = backend != null,
                        ownsWorkspace = { project, id ->
                            requireNotNull(backend).ownsWorkspace(project, id)
                        },
                        backendOperation = { workspaceRequest ->
                            requireNotNull(backend).operation(
                                workspaceRequest.project, workspaceRequest.action, workspaceRequest.workspaceId,
                                org.json.JSONObject().put("offset", workspaceRequest.offset).put("limit", workspaceRequest.limit),
                            )
                        },
                    ) } catch (_: io.github.mangi.eta.agent.delegation.WorkspaceOwnershipException) {
                        runController.throwIfCancelled()
                        org.json.JSONObject().put("ok", false).put("code", "WORKSPACE_OWNERSHIP_STORE_UNAVAILABLE")
                            .put("shell_executed", false).put("message", "工作区归属账本不可用，未执行 shell。")
                    }
                    AgentModelClient.ToolResult(payload.toString(), sensitive = true)
                } else if (call.name in SubAgentTools.names) {
                    if (!Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_TOOLS)) {
                        // 关掉开关后旧调用不能因为「当时下发过」继续创建新子任务；
                        // 但查看/收尾已有任务仍然允许，避免把在跑的子任务变成孤儿。
                        val readOnly = call.name in setOf("get_task_result", "cancel_task")
                        if (!readOnly) {
                            AgentModelClient.ToolResult(
                                org.json.JSONObject()
                                    .put("ok", false)
                                    .put("code", "SUBAGENT_TOOLS_DISABLED")
                                    .put("message", "子代理委派已在设置中关闭；已有子任务可用 get_task_result 查看或 cancel_task 停止。")
                                    .toString(),
                                sensitive = true,
                            )
                        } else {
                            AgentChildTaskGroups.execute(childSessionId, groupGeneration, call,
                                currentRunId = request.runId, replacementGeneration = replacementGeneration)
                        }
                    } else {
                        // Even with no new coordinator, explicit continue adopts into THIS run.
                        AgentChildTaskGroups.execute(childSessionId, groupGeneration, call,
                            currentRunId = request.runId, replacementGeneration = replacementGeneration)
                    }
                } else if (call.name in AgentDispatchToolCatalog.TOOL_NAMES) {
                    // 调度台派发族：不进 AgentLocalTools，直接走协调器（只调度、不执行）。
                    val coordinator = AgentDispatchRuntime.coordinator
                    if (!dispatchEnabled || coordinator == null) {
                        AgentModelClient.ToolResult(
                            org.json.JSONObject()
                                .put("ok", false)
                                .put("code", "DISPATCH_TOOLS_UNAVAILABLE")
                                .put("message", "调度工具只在「调度台」身份下可用")
                                .toString(),
                            sensitive = true,
                        )
                    } else {
                        AgentModelClient.ToolResult(
                            coordinator.executeTool(
                                name = call.name,
                                argumentsJson = call.argumentsJson,
                                orchestratorConversationId = request.effectiveModelSessionId,
                                orchestratorSession = session,
                                orchestratorConfig = request.config,
                            ).toString(),
                        )
                    }
                } else if (call.name in enabledEntryTools && entryExecutor != null) {
                    entryExecutor.execute(call)
                } else if (xiaomiToolsBridgeExecutor != null && call.name in xiaomiToolsBridgeExecutor.toolNames) {
                    xiaomiToolsBridgeExecutor.execute(call)
                } else if (hostToolExecutor != null && call.name in hostToolExecutor.toolNames) {
                    hostToolExecutor.execute(call)
                } else routingExecutor.execute(call)
            }
            val compactPolicy = runBlocking { AgentCompressionPolicy.resolve(request.config) }
            val promptWithChildHandoff = AgentChildTaskHandoff.appendToPrompt(
                AgentChildWorkerAvailability.appendToPrompt(request.prompt, childDispatchPlan), childSessionId)
            runController.throwIfCancelled()
            // M2 判断模型：在首个模型请求之前同步取一次结论，组装 run 级 overlay。
            // 影子模式 / 总开关关闭时返回 null（前者只写日志），行为完全保持现状。
            val effectiveConfig = request.config.copy(
                screenTools = allowScreen && request.config.screenTools,
                browserTools = allowBrowser && request.config.browserTools,
                terminalTools = allowTerminal && request.config.terminalTools,
                deviceTools = allowDevice && request.config.deviceTools,
                personalTools = allowPersonal && request.config.personalTools,
            ).let { base ->
                if (!calendarFastTask) return@let base
                // 快捷任务的目标、时间口径与失败口径都已由专用短规则讲死，不需要长链推理；
                // 把思考档压到最低是这类任务最大的一段提速（曾观测到 4400 reasoning token
                // 全花在给互相冲突的常驻规则做仲裁上）。模型强制推理时退到 LOW——
                // ModelConfig.validate 会拒绝强制推理模型上的 OFF。
                val mandatoryReasoning = base.reasoningCapabilities?.mandatory == true
                base.copy(
                    thinkingEnabled = mandatoryReasoning,
                    reasoningEffort = if (mandatoryReasoning) ReasoningEffort.LOW else ReasoningEffort.OFF,
                )
            }
            // 日程预热运行不跑判断模型：工具集已由预设/关键词确定，再收窄只会让模型看不到工具。
            // 唤起即屏幕上下文（硬边界：仅助手弹窗 run，主程序不做该判断）：判断模型判定或指令
            // 正则命中「需要看屏幕」且消息未附图时，预开屏幕组 + 注入首轮 observe_screen 引导，
            // 模型第一轮就能看到当前屏幕，省一次渐进解锁往返。预设运行（日程 chip/关键词）已按
            // chip 语义带图或收窄工具，不参与该判定。
            val popupRun = request.handoff?.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
            var screenLookHint = popupRun && !presetRun && request.images.isEmpty() && allowScreen &&
                AgentScreenIntent.matches(request.prompt)
            var decisionPlan = if (presetRun) {
                null
            } else {
                AgentDecisionRouter.route(request, effectiveConfig, AndroidAgentLogger)
            }
            if (decisionPlan?.needsScreenContext == true && request.images.isEmpty()) {
                screenLookHint = true
            }
            val screenLookOpenedGroups = if (screenLookHint) {
                setOf(AgentToolGroups.SCREEN_GROUP.id)
            } else {
                emptySet()
            }
            if (screenLookHint && decisionPlan != null &&
                AgentToolGroups.SCREEN_GROUP.id !in decisionPlan.openedGroups
            ) {
                decisionPlan = decisionPlan.copy(
                    openedGroups = decisionPlan.openedGroups + AgentToolGroups.SCREEN_GROUP.id,
                )
            }
            decisionPlanRef.set(decisionPlan)
            // M4：只观测「决策没选、却实际执行」的延迟组；降级/续跑 overlay 内部会自动跳过。
            val decisionMismatch = decisionPlan?.let { plan ->
                AgentDecisionMismatch.Tracker(plan, request.effectiveModelSessionId, request.runId)
            }
            // 降级提示走独立事件：只影响对话展示，不改变本轮 overlay，也不进入归档/恢复轨迹。
            decisionPlan?.failureReason?.let { reason ->
                runCatching {
                    acceptEvent(
                        session,
                        AgentEvent.DecisionFallback(
                            policy = if (decisionPlan.openableGroupLimit?.isEmpty() == true) {
                                "fail_closed"
                            } else {
                                "fail_open"
                            },
                            reason = reason,
                        ),
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                }
            }
            val completedResponse = AgentModelClient.complete(
                // 助手级收窄必须同时作用于目录与执行：只收窄执行会让模型看到一批
                // 调用后必然被拒的工具，白付 schema token 还误导规划。
                config = effectiveConfig,
                // 决策 overlay：收窄 allowed / 预开组 / 闲聊关段都在 complete 内部读取。
                decision = decisionPlan,
                // 日程预设：非 null 时目录只保留这些工具（空集 = 零工具纯对话）；
                // 预开组只用于提示里的能力段落，不影响普通对话。
                presetToolNames = presetToolNames,
                presetOpenedGroups = presetOpenedGroups,
                // 预设运行发起时刻：日程预设/关键词命中时非 null，用于在提示里注入当前本地时间。
                presetRunAt = presetRunAt,
                // 命中日程但日历目录实际为空 → 安全退回普通对话（不动纯对话预设）。
                presetFallbackWhenEmpty = presetFallbackWhenEmpty,
                // 唤起即屏幕上下文：屏幕组已预开（上面并入 plan/打开组），再注入首轮
                // observe_screen 引导——模型一步到位带截图观察，省一次解锁往返。
                screenObservationRule = screenLookHint,
                // 日程快速路径：专用短提示 + 最低思考档 + 有界轮次，目录退化时由 complete 自动失效。
                calendarFastTask = calendarFastTask,
                fastPathMaxRounds = AgentVoiceToolPresets.CALENDAR_FAST_MAX_ROUNDS,
                sessionId = request.effectiveModelSessionId,
                capabilitiesProvider = { AgentToolCapabilities.captureForRound(appContext).copy(virtualDisplay = runVirtualDisplay()) },
                // 延迟工具域：tools_use_group 成功后由执行器累积，下一轮重建目录时生效。
                // 屏幕上下文命中时并上屏幕组：非预设 run 的目录首轮即含 observe_screen。
                openedToolGroupsProvider = { executor.openedToolGroups() + screenLookOpenedGroups },
                prompt = promptWithChildHandoff, toolExecutor = delegatedExecutor, images = request.images,
                history = request.history, skipHistoryTrimming = true,
                compactionArchive = io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, request.effectiveModelSessionId),
                turnId = request.effectiveTurnId, runController = runController,
                calibratedInputTokens = request.calibratedInputTokens,
                allowUnmeasuredContextSend = request.allowUnmeasuredContextSend,
                skillContext = skillContext, memoryContext = memoryContext,
                dispatchTools = dispatchEnabled,
                skillContextProvider = {
                    check(AssistantRepository.currentProfile(assistant.id) != null) { "任务所属助手已删除" }
                    SkillContext(installedSkills = executor.currentSkillEntries())
                },
                memoryContextProvider = {
                    val current = requireNotNull(AssistantRepository.currentProfile(assistant.id)) { "任务所属助手已删除" }
                    if (!AgentMemoryRepository.isEnabled(current.id)) AgentMemoryContext.DISABLED
                    else runCatching { AgentMemoryContextBuilder.build(AgentMemoryRepository.snapshot(current.id), request.config.contextWindow) }
                        .getOrElse { AgentMemoryContextBuilder.empty(request.config.contextWindow) }
                },
                additionalTools = mcpTools,
                // MCP 渐进加载投影：非 null 时 schema 按已打开 server 逐轮追加；
                // 搜索语料出口与「发送完整目录」模式随 progressive loading 一起生效。
                mcpProjection = mcpProjection,
                searchIndex = toolSearchIndex,
                toolLoadingFullCatalog = toolLoadingFullCatalog,
                toolLoadingSearchEnabled = toolLoadingSearchEnabled,
                toolLoadingSuffixMode = toolLoadingSuffixMode,
                // 自进化配方：每轮重新读取，已启用的配方即时出现在模型工具列表里。
                // 必须是 provider：模型可能在 run 中途新建配方，固定快照会让它本轮就缺席。
                recipeToolsProvider = {
                    JSONArray().also { array ->
                        executor.recipeStore.schemas(rootAvailable = io.github.mangi.eta.agent.device.RootAccess.isGranted)
                            .forEach { array.put(it) }
                    }
                },
                linuxEnvironmentLabelProvider = { when (LinuxEnvironmentSettingsRepository.current(appContext)) {
                    LinuxDistribution.ALPINE -> "Alpine"
                    LinuxDistribution.DEBIAN -> "Debian"
                } },
                terminalSessionEnvironmentProvider = executor::terminalSessionEnvironment,
                terminalSessionIdentityProvider = executor::terminalSessionIdentity,
                compactPolicy = compactPolicy,
                onHistorySnapshot = session::publishHistorySnapshot,
                interactiveModeEnabled = interactiveModeEnabled,
                onEvent = { event ->
                    timing.accept(event)
                    foregroundReplay.accept(event)
                    decisionMismatch?.accept(event)
                    // 压缩边界：让下一个 run 重新读用户的档位设置。只认真正的摘要压缩——
                    // 修剪超长工具结果不算，否则设备任务里几乎每轮都会触发一次换档判定。
                    if (event is AgentEvent.ContextCompacted &&
                        event.applied && !event.blocked && !event.pruningOnly
                    ) {
                        runCatching { Prefs.unlockToolLoadingModeAtCompaction(request.effectiveModelSessionId) }
                    }
                    acceptEvent(session, event, archivedEvents, entrySurfaceGuard, checkpointRecorder)
                },
            )
            response = completedResponse
            modelCompleted = true
            AgentRuntimeWire.RunResult(runId = request.runId, ok = true, content = completedResponse.content,
                reasoningContent = completedResponse.reasoningContent, transcript = completedResponse.transcript)
        } catch (throwable: Throwable) {
            cancelled = runController.isCancelled || throwable is AgentRunCancelledException ||
                throwable is java.util.concurrent.CancellationException || throwable is InterruptedException
            val modelFailure = throwable as? AgentModelExecutionException
            val message = if (cancelled) "已停止" else throwable.message ?: throwable.javaClass.simpleName
            // This catch is after the retry loop has given up, not a ModelRetryScheduled event.
            // Freeze while the original generation is still attached, before terminal delivery.
            if (AgentParentNetworkFailure.isFinal(throwable, cancelled)) {
                AgentChildRunControl.terminate(session, AgentChildControlPolicy.Reason.FINAL_NETWORK_FAILURE)
            }
            if (cancelled) AndroidAgentLogger.info("Agent runtime stopped") else {
                val requestFailure = modelFailure?.cause as? AgentModelFailure
                AndroidAgentLogger.error("Agent runtime failed: type=${throwable.safeLogType()}, " +
                    "model_code=${requestFailure?.code.orEmpty()}, cause_type=${requestFailure?.cause?.safeLogType().orEmpty()}, " +
                    "detail=${(requestFailure?.message ?: throwable.message).orEmpty().take(600)}")
                AgentErrorReconnectTerminal.failureEvent(archivedEvents, throwable, request.config.apiKey)?.let {
                    runCatching { acceptEvent(session, it, archivedEvents, entrySurfaceGuard, checkpointRecorder) }
                }
                val event = AgentEvent.RunFailed(message)
                runCatching { acceptEvent(session, event, archivedEvents, entrySurfaceGuard, checkpointRecorder) }
                    .onFailure { checkpointFailure ->
                        AndroidAgentLogger.error("Agent runtime failure checkpoint failed: type=${checkpointFailure.safeLogType()}")
                        session.emit(event)
                    }
            }
            if (throwable is Error || throwable is java.util.concurrent.CancellationException) throw throwable
            AgentRuntimeWire.RunResult(runId = request.runId, ok = false, content = "", error = message,
                reasoningContent = modelFailure?.reasoningContent ?: (throwable as? AgentRunCancelledException)?.reasoningContent.orEmpty(),
                transcript = modelFailure?.transcript ?: (throwable as? AgentRunCancelledException)?.transcript.orEmpty())
        } finally {
            if (modelCompleted && !cancelled && !runController.isCancelled) {
                // A normal final/supplemental reply seals only parent control. Children retain
                // their execution and tool/service leases across detach until they finish.
                // SUCCESS never pauses, cancels or automatically resumes a child task.
                AgentChildRunControl.terminate(session, AgentChildControlPolicy.Reason.SUCCESS)
                try {
                    val receipt = localTools?.completeVirtualDelivery()
                    virtualDeliveryCompleted = session.taskSurfaceMode == io.github.mangi.eta.agent.device.AgentTaskSurfaceMode.BACKGROUND &&
                        receipt != null && receipt.opt("ok") == true && receipt.opt("handedOff") == true && receipt.opt("released") == true
                    if (receipt != null && (!receipt.optBoolean("ok") || receipt.opt("released") != true)) {
                        deliveryFailure = receipt.optString("error", "AUTO_FINISH_FAILED")
                    }
                } catch (_: Exception) { deliveryFailure = "AUTO_FINISH_FAILED" }
            } else {
                // No automatic delivery after provider failure or user stop. Owner IPC is not
                // attached to the cancelled controller; this path verifies cleanup or reports it.
                cleanupFailure = io.github.mangi.eta.agent.device.VirtualDisplayAbortCleanup.failureCode {
                    localTools?.cleanupAbortedVirtualSession()
                }
            }
            session.childCompactor = null
            childContextSink.set(null)
            registeredChildGenerations.forEach(AgentChildTaskGroups::detach)
            runCatching { toolsBinding?.close() }
            runCatching { toolsOwner?.release() }
            runCatching { guiRun?.close() }
            unownedSkillRoot?.let { root -> runCatching { SkillRuntime.releaseRunSkills(appContext, root) } }
        }
        deliveryFailure?.let { code ->
            val message = appContext.getString(R.string.runtime_delivery_incomplete, code)
            result = result.copy(ok = false, content = result.content + "\n\n" + message, error = message)
        }
        cleanupFailure?.let { code ->
            val message = appContext.getString(R.string.runtime_cleanup_unconfirmed, code)
            result = result.copy(ok = false, content = listOf(result.content, message).filter { it.isNotBlank() }.joinToString("\n\n"),
                error = listOfNotNull(result.error?.takeIf { it.isNotBlank() }, message).joinToString("\n"))
        }
        result = result.copy(virtualDeliveryCompleted = result.ok && virtualDeliveryCompleted && !cancelled && !runController.isCancelled)
        val completedRequest = runCatching { snapshotRequest(request) }.getOrElse { throwable ->
            AndroidAgentLogger.error("Agent runtime request snapshot failed: type=${throwable.safeLogType()}")
            request
        }
        val committed = session.complete(result) { terminal ->
            runCatching { checkpointRecorder?.seal() }.onFailure { throwable ->
                AndroidAgentLogger.error("Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}")
            }
            runCatching { persistArtifacts(completedRequest, terminal, archivedEvents) }.onFailure { throwable ->
                AndroidAgentLogger.error("Agent runtime artifact persistence failed: type=${throwable.safeLogType()}")
            }
        }
        return Outcome(session.terminalResult ?: result, entrySurfaceGuard, completedRequest.takeIf { committed },
            response.takeIf { committed && session.terminalResult?.ok == true }, committed)
    }

    // All stages below start inside this synchronized method: executor monitor acquisition is
    // excluded. checkpoint.accept includes the recorder call (and its own monitor, if contended).
    @Synchronized private fun acceptEvent(session: AgentRuntimeSession, event: AgentEvent,
        archivedEvents: MutableList<AgentEvent>, entrySurfaceGuard: EntrySurfaceGuard?,
        checkpointRecorder: AgentRunCheckpointRecorder?) {
        if (event is AgentEvent.QuestionRequested || event is AgentEvent.QuestionResolved) {
            AgentQuestionEventPublisher.publish(session, event) {
                checkpointRecorder?.let { recorder ->
                    measureRuntimeStreamStage("runtime.checkpoint.accept") { recorder.accept(event) }
                }
            }
        } else {
            if (!session.emit(event) {
                checkpointRecorder?.let { recorder ->
                    measureRuntimeStreamStage("runtime.checkpoint.accept") { recorder.accept(event) }
                }
            }) return
        }
        // 操作权状态只做投送与检查点，不属于会话轨迹，不能进入归档。
        if (event !is AgentEvent.GuiControlChanged && event !is AgentEvent.DecisionFallback) archivedEvents += event
        if (event is AgentEvent.ModelRetryScheduled) AndroidAgentLogger.warn("Agent runtime event: ${event.toLogLine()}")
        else if (event !is AgentEvent.AssistantBlockDelta) AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
        runCatching { onAcceptedEvent(event, entrySurfaceGuard) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_event_projection_failed") {
                "Agent runtime event projection failed: type=${throwable.safeLogType()}"
            }
        }
    }
}
