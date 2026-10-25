package io.github.mangi.eta.agent.tool

import android.content.Context
import android.app.ActivityOptions
import android.view.Display
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import java.io.File
import android.os.SystemClock
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.device.DeviceControlUnavailableException
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.model.AgentMediaSearchToolCatalog
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.agent.web.AgentWebTools
import io.github.mangi.eta.agent.web.WebHttpTransport
import io.github.mangi.eta.data.repository.WebToolsSettingsRepository
import kotlinx.coroutines.runBlocking
import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.agent.model.AgentSelfSettingsToolCatalog
import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.agent.model.AgentToolGroup
import io.github.mangi.eta.agent.model.AgentToolGroupCatalog
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.GestureIndicator
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.agent.skill.SkillArchiveInspectionResult
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillIndexService
import io.github.mangi.eta.agent.skill.SkillInstallErrorCode
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillLoader
import io.github.mangi.eta.agent.skill.SkillPackageInstaller
import io.github.mangi.eta.agent.skill.SkillParser
import io.github.mangi.eta.agent.skill.SkillResourceReader
import io.github.mangi.eta.agent.skill.SkillRunAuthorization
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.SkillResourceReadResult
import io.github.mangi.eta.agent.skill.GitHubSkillRepositoryParser
import io.github.mangi.eta.agent.skill.GitHubSkillInspection
import io.github.mangi.eta.agent.skill.GitHubSkillRepository
import io.github.mangi.eta.agent.skill.GitHubSkillSourceException
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentPaths
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.agent.voice.tts.AGENT_SPEECH_OWNER
import io.github.mangi.eta.agent.voice.tts.AgentSpeechReport
import io.github.mangi.eta.agent.voice.tts.AgentSpeechStatus
import io.github.mangi.eta.agent.voice.tts.AGENT_SPEECH_START_TIMEOUT_MS
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.data.model.AssistantPrompt
import io.github.mangi.eta.data.repository.AgentMemoryException
import io.github.mangi.eta.data.repository.AgentMemoryMutation
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

internal class AgentLocalTools(
    private val context: Context,
    private val logger: AgentLogger,
    private val browserRunId: String = java.util.UUID.randomUUID().toString(),
    private val browserConversationId: String = "",
    private val browserToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS)
    },
    private val terminalToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS)
    },
    private val deviceDirectToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS)
    },
    private val deviceSensitiveReadToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS)
    },
    private val deviceSensitiveActionToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS)
    },
    private val selfSettingsToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_SELF_SETTINGS_TOOLS)
    },
    private val mediaSearchToolsEnabled: () -> Boolean = {
        Prefs.isEnabled(Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS)
    },
    private val memoryToolsEnabled: (() -> Boolean)? = null,
    private val screenshotExcludedPackages: () -> Set<String> = { emptySet() },
    private val screenObservationProvider: (
        (AgentScreenObservationContract.Options) -> RootShellDeviceController.Observation
    )? = null,
    private val beforeToolExecution: (String) -> ToolExecutionDecision = {
        ToolExecutionDecision.Allow
    },
    /**
     * 危险操作的逐次确认。与 [beforeToolExecution] 分开：前者是权限/准入（不可协商），
     * 后者是「这件事要不要先问用户」，两者失败时的错误码与恢复方式都不同。
     */
    private val confirmDangerousOperation: (String, String) -> ToolExecutionDecision? = { _, _ -> null },
    private val skillIndexService: SkillIndexService? = null,
    private val skillLoader: SkillLoader? = null,
    private val skillResourceReader: SkillResourceReader? = null,
    private val githubSkillSource: PublicGitHubSkillSource? = null,
    private val skillPackageInstaller: SkillPackageInstaller? = null,
    runAvailableSkillIds: Set<String> = emptySet(),
    runSkillEntries: List<SkillIndexEntry> = emptyList(),
    memoryAssistantId: String = AssistantPrompt.DEFAULT_ID,
    private val runSkillsRoot: File? = null,
    pendingSkillConflict: PendingSkillConflictCapability? = null,
    private val rootAvailable: () -> Boolean = { RootAccess.isGranted },
    frozenSurface: io.github.mangi.eta.agent.device.AgentTaskSurfaceMode = runCatching {
        io.github.mangi.eta.agent.device.AgentTaskSurface.stored()
    }.getOrDefault(io.github.mangi.eta.agent.device.AgentTaskSurfaceMode.ASK),
    /**
     * “每次询问”时由运行时弹窗问用户，阻塞到选完；返回 null 表示取消或超时。
     * toolName 是触发选择的工具；cancelled 是本工具执行器自己的关闭状态，等待期间要一起检查。
     */
    private val chooseSurface: ((toolName: String, cancelled: () -> Boolean) -> io.github.mangi.eta.agent.device.AgentTaskSurfaceMode?)? = null,
    private val selfForegroundPackage: () -> String? = {
        AgentAccessibilityService.current()?.currentMainDisplayPackageName()
    },
    /** 当前会话 id，用于把子代理组应用到本会话。 */
    private val agentConversationId: String = "",
) : AgentModelClient.ToolExecutor, AutoCloseable {

    /** ASK 只在第一次界面操作前问一次，之后整轮 run 都用选定的位置。 */
    @Volatile
    var runSurface: io.github.mangi.eta.agent.device.AgentTaskSurfaceMode = frozenSurface
        private set
    /** 本次回复只问一次：选定后复用，取消或超时后记住，不再弹窗。 */
    private val surfaceChoice = io.github.mangi.eta.agent.device.AgentTaskSurfaceChoiceGate()
    private val backgroundSurface: Boolean
        get() = runSurface == io.github.mangi.eta.agent.device.AgentTaskSurfaceMode.BACKGROUND
    private val virtualLifecycle = setOf("start_virtual_session", "keep_virtual_result", "finish_virtual_session")
    private val selfSurface = AgentSelfAppSurface(context.packageName)
    private val surfaceRouteLock = java.util.concurrent.locks.ReentrantLock()
    private fun virtualRouted(name: String, route: AgentSelfAppSurface.Route) = backgroundSurface &&
        (name in virtualLifecycle || (route == AgentSelfAppSurface.Route.BASE &&
            io.github.mangi.eta.agent.device.AgentTaskSurface.isTraditionalScreenGuiTool(name)))
    private val closed = AtomicBoolean(false)
    /**
     * 本次 run 里已按需打开的工具域。AgentModelClient 在下一轮重建目录时读取它，
     * 与技能安装的 next_turn 语义一致：打开后最早下一轮生效。
     */
    private val openedToolGroups = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 供宿主在每轮重建目录时读取。 */
    fun openedToolGroups(): Set<String> = openedToolGroups.toSet()
    private val deviceController = RootShellDeviceController(logger, {
        val excluded = screenshotExcludedPackages()
        if (selfSurface.active && runCatching(selfForegroundPackage).getOrNull() == context.packageName) {
            excluded - context.packageName
        } else excluded
    }, rootAvailable)
    private val rootCommandExecutor = BoundedRootCommandExecutor(logger, rootAvailable = rootAvailable)
    private val structuredDeviceTools = AgentStructuredDeviceTools(
        context = context,
        logger = logger,
        root = rootCommandExecutor,
        rootAvailable = rootAvailable,
    )
    private val imageTools = AgentImageTools(
        context,
        rootCommandExecutor,
        rootAvailable,
        conversationId = browserConversationId,
    )
    /** 网页搜索与静态读取使用独立匿名请求，不共享浏览器会话和 Cookie。 */
    private val webTools = AgentWebTools(
        transport = WebHttpTransport(AgentHttpClient.client),
        localNetworkAccess = { LocalNetworkPermission.accessState(context).name.lowercase(Locale.ROOT) },
        runtimeConfig = { runBlocking { WebToolsSettingsRepository.resolveRuntimeConfig(context) } },
    )
    private val selfSettingsTools = AgentSelfSettingsTools()
    private val selfModelTools = AgentSelfModelTools(context)
    private val selfSubAgentTools = AgentSelfSubAgentTools(agentConversationId)
    private val selfHistoryTools = AgentSelfHistoryTools(context, agentConversationId)
    private val selfMcpTools = AgentSelfMcpTools(context)
    private val mediaSearchTools = AgentMediaSearchTools(context)
    /** 自进化底座：模型可自行创建声明式配方（工具），配方即时注册为可调用工具。 */
    // 与设置页共用进程级唯一 store：用户停用/删除配方后，agent 侧立即看到，不会继续暴露已收回的工具。
    internal val recipeStore = AgentToolRecipeStore.get(context)
    private val recipeExecutor = AgentToolRecipeExecutor(
        context = context,
        root = rootCommandExecutor,
        store = recipeStore,
        rootAvailable = rootAvailable,
    )
    private val selfAuthorTools = AgentSelfAuthorTools(recipeStore, rootAvailable)
    private val terminalController = RootShellTerminalController(
        processSupervisor = io.github.mangi.eta.agent.terminal.ShellProcessSupervisor(skillsDirectoryProvider = { runSkillsRoot }),
        logger = logger,
        rootAvailable = rootAvailable,
        resolveReadPath = { path ->
            io.github.mangi.eta.agent.terminal.LinuxGuestPathResolver.resolveForApp(context, path)
        },
        linuxRootfsPath = AlpineEnvironmentPaths.rootfsDir(context).absolutePath,
        linuxRootfsPathProvider = { environment ->
            environment.linuxDistribution?.let { distribution ->
                LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
            }
        },
        detachedSupervisor = DetachedTaskSupervisor(
            skillsDirectoryProvider = { runSkillsRoot },
            logger = logger,
            recordsFile = DetachedTaskSupervisor.defaultRecordsFile(context),
            linuxRootfsPath = AlpineEnvironmentPaths.rootfsDir(context).absolutePath,
            linuxRootfsPathProvider = { environment ->
                environment.linuxDistribution?.let { distribution ->
                    LinuxEnvironmentPaths.rootfsDir(context, distribution).absolutePath
                }
            },
            linuxSharedMountsProvider = { SharedFolderMounts.current() },
        ),
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
        selectedLinuxEnvironmentProvider = {
            LinuxEnvironmentSettingsRepository.current(context).terminalEnvironment
        },
    )
    private val publishedObservation = AtomicReference(PublishedObservation())
    private val runAvailableSkillIds = runAvailableSkillIds
        .mapTo(mutableSetOf(), SkillParser::normalizeSkillLookup)
    private val runSkillEntries = runSkillEntries
    private val memoryAssistantId = memoryAssistantId
    private val mutatedSkillIds = ConcurrentHashMap.newKeySet<String>()
    private val skillTreeMutationUncertain = AtomicBoolean(false)
    private val pendingSkillConflict = AtomicReference(pendingSkillConflict)
    private val inspectedGitHubSnapshots =
        ConcurrentHashMap<String, GitHubInspectionSnapshot>()

    /** Successful completion may deliver; abort finalization below only cleans up. */
    fun completeVirtualDelivery(): JSONObject? {
        if (!backgroundSurface) return null
        io.github.mangi.eta.agent.device.VirtualDisplaySession.onRunClosed(context, browserRunId)
        return io.github.mangi.eta.agent.device.VirtualDisplaySession.deliveryReceipt(browserRunId)
    }

    fun cleanupAbortedVirtualSession(): JSONObject? {
        if (!backgroundSurface) return null
        return io.github.mangi.eta.agent.device.VirtualDisplaySession.onRunAborted(context, browserRunId)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        io.github.mangi.eta.agent.device.VirtualDisplaySession.holdOnCancelledRun(browserRunId)
        publishedObservation.set(PublishedObservation())
        AgentBrowserSession.interruptAgentAction(browserRunId)
        webTools.close()
        terminalController.interruptAll()
        kotlin.concurrent.thread(name = "agent-skill-view-release", isDaemon = true) {
            terminalController.closeAll()
            runSkillsRoot?.let {
                runCatching { SkillRuntime.releaseRunSkills(context, it, retain = terminalController.hasRunningOwnedDaemons()) }
            }
        }
        rootCommandExecutor.close()
        githubSkillSource?.close()
        inspectedGitHubSnapshots.clear()
        ForegroundExclusiveGate.release(browserRunId)
    }

    fun terminalSessionEnvironment(sessionId: String): String? =
        terminalController.sessionEnvironmentWireName(sessionId)

    fun terminalSessionIdentity(sessionId: String): String? =
        terminalController.sessionIdentity(sessionId)

    /**
     * 域的开关闸门：与 AgentToolCatalog / AgentPromptBuilder 使用同一套映射。
     * 打开域不放宽任何权限——这里只说「这个域此刻是否允许存在」，
     * 域内工具在下一轮仍要各自通过 Prefs 与 AgentToolCapabilities。
     */
    private fun toolGroupGate(group: AgentToolGroup): Boolean = when (group.id) {
        AgentToolGroups.SELF_SETTINGS_GROUP.id -> selfSettingsToolsEnabled()
        AgentToolGroups.DEVICE_ACTION_GROUP.id -> deviceSensitiveActionToolsEnabled()
        AgentToolGroups.DEVICE_TAIL_READ_GROUP.id -> deviceSensitiveReadToolsEnabled()
        else -> false
    }

    /**
     * 打开一个延迟工具域。目录与系统提示只会宣传「开关允许且尚未打开」的域，
     * 这里再按同一份判定复查：模型重放旧的 tools_use_group 调用也不能打开已关闭的域。
     */
    private fun useToolGroup(args: JSONObject): String {
        val requested = args.optString("group").trim()
        val availability = AgentToolGroups.availability(openedToolGroups, ::toolGroupGate)
        val known = AgentToolGroups.openableIds(availability)
        val group = AgentToolGroups.group(requested)
        if (group == null || !group.deferredByDefault) {
            return AgentToolGroupCatalog.unknownGroup(requested, known)
        }
        // 开关关闭的域不允许打开：否则会成功返回 next_turn，而域内工具永远不会出现。
        if (requested !in availability.openable && requested !in availability.opened) {
            return errorResult(
                "TOOL_GROUP_DISABLED",
                "该工具域已在设置中关闭，无法打开；请让用户先在设置里启用对应能力。",
            )
        }
        openedToolGroups += group.id
        return AgentToolGroupCatalog.result(group)
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val gui = io.github.mangi.eta.agent.device.AgentTaskSurface.isTraditionalScreenGuiTool(toolCall.name) ||
            toolCall.name in virtualLifecycle
        if (!gui) return executeOnSurface(toolCall, AgentSelfAppSurface.Route.BASE)
        try {
            surfaceRouteLock.lockInterruptibly()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return textResult(errorResult("RUN_CLOSED", "屏幕操作等待已中断，本次未执行"))
        }
        try {
            if (closed.get()) return textResult(errorResult("RUN_CLOSED", "任务已关闭"))
            val args = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }.getOrElse {
                return textResult(errorResult("INVALID_ARGUMENT", "工具参数不是合法 JSON"))
            }
            val prepared = if (toolCall.name == "launch_app") runCatching { prepareLaunchApp(args) }.getOrElse {
                selfSurface.leave()
                publishedObservation.set(PublishedObservation())
                return textResult(errorResult("APP_RESOLUTION_FAILED", "应用目标解析失败，本次未启动"))
            } else null
            if (prepared?.error != null) {
                selfSurface.leave()
                publishedObservation.set(PublishedObservation())
                return textResult(prepared.error)
            }
            val wasSelf = selfSurface.active
            val route = selfSurface.route(
                toolCall.name,
                launchPackage = prepared?.app?.packageName?.takeIf {
                    prepared?.intent?.component?.packageName == it
                },
                button = args.optString("button"),
                waitPackage = args.optString("package_name").trim(),
            )
            if (wasSelf != selfSurface.active || toolCall.name == "launch_app") {
                publishedObservation.set(PublishedObservation())
            }
            var chosenNow: io.github.mangi.eta.agent.device.AgentTaskSurfaceMode? = null
            if (route == AgentSelfAppSurface.Route.BASE &&
                runSurface == io.github.mangi.eta.agent.device.AgentTaskSurfaceMode.ASK &&
                io.github.mangi.eta.agent.device.AgentTaskSurface.needsSurfaceChoice(toolCall.name)
            ) {
                // Reject occupied main-screen access before opening a potentially blocking ASK UI.
                foregroundAdmissionError(ForegroundExclusiveGate.checkAvailability(browserRunId) { closed.get() })
                    ?.let { return it }
                val outcome = resolveAskedSurface(toolCall.name)
                if (outcome == null) {
                    return textResult(
                        if (closed.get()) errorResult("RUN_CLOSED", "任务已关闭")
                        else errorResult("TASK_SURFACE_CANCELLED",
                            "用户没有选择或已取消执行位置，本次未执行，本次回复内不会再询问；不要再调用普通应用界面工具"),
                    )
                }
                if (outcome.second) chosenNow = outcome.first
            }
            // Pin the resolved package for both routes; never re-resolve a display name after admission.
            val resolvedCall = prepared?.app?.let {
                toolCall.copy(argumentsJson = JSONObject(args.toString()).put("package_name", it.packageName).toString())
            } ?: toolCall
            val result = executeOnSurface(resolvedCall, route, prepared)
            if (route == AgentSelfAppSurface.Route.SELF_LAUNCH) {
                selfSurface.launchedSelf(runCatching { JSONObject(result.content).optBoolean("ok") }.getOrDefault(false))
            }
            if (route != AgentSelfAppSurface.Route.BASE) {
                // An action may navigate away. Never replay it on another display.
                if (route == AgentSelfAppSurface.Route.SELF_GUI &&
                    !selfSurface.validateForeground(runCatching(selfForegroundPackage).getOrNull())) {
                    publishedObservation.set(PublishedObservation())
                }
                val note = runCatching {
                    JSONObject(result.content)
                        .put("task_surface", "foreground")
                        .put("task_surface_scope", "self_app")
                        .put("base_task_surface", runSurface.wire)
                        .put("self_app_segment_active", selfSurface.active)
                        .put("self_app_segment_lost", selfSurface.lost)
                        .put("surface_note", "仅Eta自身前台片段；其它应用仍按基础ASK/后台偏好执行")
                        .toString()
                }.getOrDefault(result.content)
                return result.copy(content = note)
            }
            return chosenNow?.let { withSurfaceNote(result, it) } ?: result
        } finally {
            surfaceRouteLock.unlock()
        }
    }

    /** 返回选定的位置，以及是否是这次调用刚选的（需要告诉模型）。 */
    private fun resolveAskedSurface(toolName: String): Pair<io.github.mangi.eta.agent.device.AgentTaskSurfaceMode, Boolean>? {
        if (closed.get()) return null
        var askedNow = false
        val chosen = surfaceChoice.resolve {
            askedNow = true
            try {
                chooseSurface?.invoke(toolName) { closed.get() }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            } catch (_: RuntimeException) {
                null
            }
        } ?: return null
        // 并行调用在门里排队：只有真正弹窗选出结果的那一次才告诉模型。
        runSurface = chosen
        return chosen to askedNow
    }

    private fun withSurfaceNote(
        result: AgentModelClient.ToolResult,
        mode: io.github.mangi.eta.agent.device.AgentTaskSurfaceMode,
    ): AgentModelClient.ToolResult {
        val surface = mode.wire
        val note = runCatching { JSONObject(result.content).put("task_surface", surface).put("task_surface_scope", "ordinary_run").toString() }
            .getOrElse { "{\"task_surface\":\"$surface\"}\n" + result.content }
        return result.copy(content = note)
    }

    private fun executeOnSurface(
        toolCall: AgentModelClient.ToolCall,
        route: AgentSelfAppSurface.Route,
        prepared: PreparedLaunch? = null,
    ): AgentModelClient.ToolResult {
        val handoffBlocksGui = runCatching {
            route == AgentSelfAppSurface.Route.BASE &&
                io.github.mangi.eta.agent.device.AgentTaskSurface.blocksGuiTool(toolCall.name, runSurface) && !backgroundSurface
        }.getOrDefault(true)
        if (handoffBlocksGui) {
            return textResult(errorResult(io.github.mangi.eta.agent.device.VirtualDisplaySession.NOT_READY, "副屏交接未就绪，本次未执行；请在设置改为前台"))
        }
        if (virtualRouted(toolCall.name, route) || !ForegroundExclusiveGate.shouldSerialize(toolCall.name)) {
            return executeInternal(toolCall, route, prepared)
        }
        foregroundAdmissionError(ForegroundExclusiveGate.acquire(browserRunId) { closed.get() })
            ?.let { return it }
        return executeInternal(toolCall, route, prepared)
    }

    private fun foregroundAdmissionError(admission: ForegroundExclusiveGate.Admission): AgentModelClient.ToolResult? =
        when (admission) {
            ForegroundExclusiveGate.Admission.CLOSED ->
                textResult(errorResult("RUN_CLOSED", "任务已关闭或屏幕控制身份无效，本次未执行"))
            ForegroundExclusiveGate.Admission.BUSY -> textResult(
                JSONObject(errorResult("FOREGROUND_BUSY",
                    "另一个会话尚未结束屏幕控制，本次操作已拒绝且未执行；不会排队或抢占。" +
                        "请直接向用户说明原因，不要自动等待、重试或改用 Shell 绕过；待该会话结束后由用户重新发起。"))
                    .put("executed", false)
                    .put("retryable", false)
                    .put("queued", false)
                    .toString(),
            )
            ForegroundExclusiveGate.Admission.ACQUIRED -> null
        }

    private fun executeInternal(
        toolCall: AgentModelClient.ToolCall,
        route: AgentSelfAppSurface.Route,
        prepared: PreparedLaunch? = null,
    ): AgentModelClient.ToolResult =
        runCatching {
            if (closed.get()) return@runCatching textResult(errorResult("RUN_CLOSED", "任务已关闭"))
            if (AssistantRepository.isReady() && AssistantRepository.currentProfile(memoryAssistantId) == null) {
                terminalController.interruptAll()
                terminalController.stopOwnedDaemons()
                return@runCatching textResult(errorResult("ASSISTANT_DELETED", "任务所属助手已删除，工具未执行"))
            }
            val args = JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
            if (AgentToolRequirements.find(toolCall.name) != null &&
                AgentToolRequirements.rootDenied(toolCall.name, args, rootAvailable())
            ) {
                return@runCatching textResult(errorResult("ROOT_REQUIRED", "此操作需要 Root 授权，本次未执行"))
            }
            if (virtualRouted(toolCall.name, route)) {
                if (!rootAvailable()) return@runCatching textResult(errorResult("ROOT_REQUIRED", "后台副屏需要 Root"))
                return@runCatching when(toolCall.name) {
                    "start_virtual_session" -> textResult(io.github.mangi.eta.agent.device.VirtualDisplaySession.start(context,browserRunId).toString())
                    "keep_virtual_result" -> textResult(io.github.mangi.eta.agent.device.VirtualDisplaySession.keep(browserRunId,args).toString())
                    "finish_virtual_session" -> textResult(io.github.mangi.eta.agent.device.VirtualDisplaySession.finish(browserRunId, context).toString())
                    else -> io.github.mangi.eta.agent.device.VirtualDisplaySession.executeGui(context,browserRunId,toolCall.name,args,screenshotExcludedPackages())
                }
            }
            if (toolCall.name in virtualLifecycle) return@runCatching textResult(errorResult("BACKGROUND_MODE_REQUIRED", "当前任务不是后台模式"))
            deviceToolPermissionError(toolCall.name)?.let { return@runCatching it }
            memoryToolPermissionError(toolCall.name)?.let { return@runCatching it }
            confirmDangerousOperation(toolCall.name, toolCall.argumentsJson).let { decision ->
                when (decision) {
                    null, ToolExecutionDecision.Allow -> Unit
                    is ToolExecutionDecision.Reject ->
                        return@runCatching textResult(errorResult(code = decision.code, message = decision.message))
                }
            }
            when (val decision = beforeToolExecution(toolCall.name)) {
                ToolExecutionDecision.Allow -> Unit
                is ToolExecutionDecision.Reject -> {
                    if (decision.code.startsWith("ACCESSIBILITY_")) publishedObservation.set(PublishedObservation())
                    return@runCatching textResult(
                        errorResult(
                            code = decision.code,
                            message = decision.message,
                        ),
                    )
                }
            }
            if (route == AgentSelfAppSurface.Route.SELF_GUI &&
                !selfSurface.validateForeground(runCatching(selfForegroundPackage).getOrNull())) {
                publishedObservation.set(PublishedObservation())
                return@runCatching textResult(errorResult(
                    "SELF_APP_TARGET_LOST",
                    "未确认主屏焦点仍为Eta自身，本次未执行；请重新打开Eta，或按原偏好打开其它应用并重新观察，勿重放旧坐标",
                ))
            }
            // 自进化配方工具：名字是动态注册的 `recipe_<name>`，必须在静态 when 之前拦截。
            if (toolCall.name.startsWith(ToolRecipeConstants.TOOL_PREFIX)) {
                return@runCatching textResult(executeRecipeTool(toolCall.name, args))
            }
            when (toolCall.name) {
                "get_current_context" -> textResult(DeviceContextTool.current(context))
                "text_to_speech" -> textResult(textToSpeech(args))
                "search_apps" -> textResult(searchApps(args))
                "launch_app" -> textResult(launchApp(prepared ?: prepareLaunchApp(args), route == AgentSelfAppSurface.Route.SELF_LAUNCH))
                "inspect_virtual_backend" -> textResult(io.github.mangi.eta.agent.device.VirtualDisplayBackendBridge.inspect(context).toString())
                "keep_virtual_result" -> textResult(keepVirtualResult(args))
                "open_uri" -> textResult(openUri(args))
                "browser_use" -> browserUse(args, toolCall.id)
                "web_search", "fetch_url" -> {
                    if (!browserToolsEnabled()) textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页搜索、读取与浏览器工具"))
                    else textResult(webTools.execute(toolCall.name, args))
                }
                "observe_screen" -> observeScreen(args)
                "tap" -> afterAction(tap(args))
                "tap_area" -> afterAction(tapArea(args))
                "tap_element" -> afterAction(tapElement(args))
                "long_press" -> afterAction(longPress(args))
                "long_press_element" -> afterAction(longPressElement(args))
                "swipe" -> afterAction(swipe(args))
                "scroll" -> afterAction(deviceController.scroll(args.optString("direction"), args.optString("amount")))
                "scroll_element" -> afterAction(scrollElement(args))
                "type_text" -> afterAction(typeText(args))
                "input_text" -> afterAction(inputText(args))
                "replace_text" -> afterAction(replaceText(args))
                "clear_text" -> afterAction(clearText(args))
                "set_clipboard" -> textResult(setClipboard(args))
                "get_clipboard" -> textResult(getClipboard())
                "paste_text" -> afterAction(pasteText(args))
                "press_key" -> afterAction(deviceController.pressKey(args.optString("button")))
                "wait" -> textResult(deviceController.waitMs(args.optInt("duration_ms", 1_000)))
                "wait_for_text" -> textResult(waitForText(args))
                "wait_for_package" -> textResult(waitForPackage(args))
                "open_system_panel" -> textResult(deviceController.openSystemPanel(args.optString("panel")))
                in DEVICE_TOOL_NAMES ->
                    structuredDeviceTools.execute(toolCall.name, args)
                        ?: textResult(errorResult("UNKNOWN_TOOL", "未知设备工具"))
                "read_image" -> fileVisionTool(toolCall.name) { imageTools.readImage(args) }
                "terminal" -> textResult(terminalTool(toolCall.name) { terminal(args) })
                "run_command" -> textResult(terminalTool(toolCall.name) { runCommand(args) })
                "read_file" -> textResult(terminalTool(toolCall.name) { readFile(args) })
                "write_file" -> textResult(terminalTool(toolCall.name) { writeFile(args) })
                "list_directory" -> textResult(terminalTool(toolCall.name) { listDirectory(args) })
                "edit_file", "stat_file", "glob_files", "grep_files" ->
                    textResult(terminalTool(toolCall.name) { terminalController.fileTool(toolCall.name, args) })
                "memory_get" -> textResult(memoryGet(args))
                "memory_write" -> textResult(memoryWrite(args))
                "skills_list" -> textResult(skillsList(args))
                "skills_read" -> textResult(skillsRead(args))
                "skills_read_resource" -> textResult(skillsReadResource(args))
                "skills_list_curated" -> textResult(skillsListCurated())
                "skills_inspect_github" -> textResult(skillsInspectGitHub(args))
                "skills_install_from_github" -> textResult(skillsInstallFromGitHub(args))
                "skills_install_local" -> textResult(skillsInstallLocal(args))
                AgentToolGroupCatalog.USE_GROUP -> textResult(useToolGroup(args))
                AgentSelfSettingsToolCatalog.GET_SETTINGS -> textResult(selfSettingsTool { selfSettingsTools.list() })
                AgentSelfSettingsToolCatalog.SET_SETTINGS -> textResult(selfSettingsTool { selfSettingsTools.set(args) })
                AgentSelfSettingsToolCatalog.MANAGE_MODELS -> textResult(selfSettingsTool { selfModelTools.execute(args) })
                AgentSelfSettingsToolCatalog.MANAGE_SUBAGENTS -> textResult(selfSettingsTool { selfSubAgentTools.execute(args) })
                AgentSelfSettingsToolCatalog.MANAGE_HISTORY -> textResult(selfSettingsTool { selfHistoryTools.execute(args) })
                AgentSelfSettingsToolCatalog.CONFIGURE_MCP -> textResult(selfSettingsTool { selfMcpTools.execute(args) })
                AgentMediaSearchToolCatalog.SEARCH_MEDIA -> textResult(mediaSearchTool { mediaSearchTools.execute(args) })
                AgentSelfAuthorTools.AUTHOR_TOOL -> textResult(selfAuthorTool { selfAuthorTools.execute(AgentSelfAuthorTools.AUTHOR_TOOL, args) ?: "" })
                AgentSelfAuthorTools.LIST_TOOLS -> textResult(selfAuthorTool { selfAuthorTools.execute(AgentSelfAuthorTools.LIST_TOOLS, args) ?: "" })
                else -> textResult(
                    errorResult(
                        code = "UNKNOWN_TOOL",
                        message = "未知工具：${toolCall.name}"
                    )
                )
            }
        }.getOrElse { throwable ->
            textResult(
                errorResult(
                    code = when (throwable) {
                        is InvalidToolArgumentException -> "INVALID_ARGUMENT"
                        is DeviceControlUnavailableException -> "ACCESSIBILITY_UNAVAILABLE"
                        else -> "TOOL_ERROR"
                    },
                    message = throwable.message ?: throwable.javaClass.simpleName
                )
            )
        }.let { result ->
            if (result.sensitive || !AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
                result
            } else {
                result.copy(sensitive = true)
            }
        }

    /**
     * 执行一份自进化配方。
     *
     * 配方是模型自己创建的，因此这里把「自建工具」视为敏感操作：
     * 结果不写入持久会话，且必须落在敏感操作组开关的授权范围内。
     */
    private fun executeRecipeTool(toolName: String, args: JSONObject): String {
        if (!deviceSensitiveActionToolsEnabled()) {
            return errorResult("DEVICE_SENSITIVE_ACTION_TOOLS_DISABLED", "请先允许敏感设备操作后再使用自建工具")
        }
        val recipeName = toolName.removePrefix(ToolRecipeConstants.TOOL_PREFIX)
        val recipe = recipeStore.find(recipeName)
            ?: return errorResult(
                "RECIPE_NOT_FOUND",
                "配方「$recipeName」不存在或已被删除；调用 eta_list_recipes 看看现有哪些，不要重复同样的调用",
            )
        if (!recipe.enabled) {
            return errorResult("RECIPE_DISABLED", "配方「$recipeName」已停用；用 eta_author_tool enable 启用它")
        }
        // 需要 Root 的配方在无 Root 时不可执行（工具层本就不会暴露，这里是兜底）。
        if (!rootAvailable() && store_RequiresRoot(recipe)) {
            return errorResult("ROOT_REQUIRED", "该配方需要 Root 权限；未授权前不要重复调用本工具")
        }
        return recipeExecutor.execute(recipe, args)
    }

    /** 配方（含组合链）是否需要 Root——需要完整配方图才能推导组合链上的 Root 需求。 */
    private fun store_RequiresRoot(recipe: ToolRecipe): Boolean {
        val all = recipeStore.all()
        return recipeStore.rootDependentRecipes(all).contains(recipe.name) || recipe.requiresRoot
    }

    /** 自写工具的统一门禁：关闭开关后，模型重放旧的写配方调用也直接拒绝。 */
    private fun selfAuthorTool(block: () -> String): String {
        if (!selfSettingsToolsEnabled()) {
            return errorResult("SELF_SETTINGS_TOOLS_DISABLED", "应用自身配置工具已关闭，本次未执行")
        }
        return block()
    }

    private fun deviceToolPermissionError(
        toolName: String,
    ): AgentModelClient.ToolResult? {
        val error = when {
            toolName in DEVICE_DIRECT_TOOL_NAMES && !deviceDirectToolsEnabled() ->
                "DEVICE_DIRECT_TOOLS_DISABLED" to "请先启用设备直达工具"
            toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES && !deviceSensitiveReadToolsEnabled() ->
                "DEVICE_SENSITIVE_READ_TOOLS_DISABLED" to "请先允许读取敏感设备信息"
            toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES && !deviceSensitiveActionToolsEnabled() ->
                "DEVICE_SENSITIVE_ACTION_TOOLS_DISABLED" to "请先允许敏感设备操作"
            else -> null
        } ?: return null
        return AgentModelClient.ToolResult(
            content = errorResult(error.first, error.second),
            sensitive = toolName in DEVICE_SENSITIVE_READ_TOOL_NAMES ||
                toolName in DEVICE_SENSITIVE_ACTION_TOOL_NAMES,
        )
    }

    /** 自身配置工具的统一门禁：开关从设置页关闭后，即使模型重放旧的工具调用也直接拒绝。 */
    private fun selfSettingsTool(block: () -> String): String {
        if (!selfSettingsToolsEnabled()) {
            return errorResult("SELF_SETTINGS_TOOLS_DISABLED", "应用自身配置工具已关闭，本次未执行")
        }
        return block()
    }

    /** 媒体语义搜索工具的统一门禁：关闭开关后模型重放旧调用也直接拒绝。 */
    private fun mediaSearchTool(block: () -> String): String {
        if (!mediaSearchToolsEnabled()) {
            return errorResult("MEDIA_SEARCH_TOOLS_DISABLED", "媒体语义搜索工具已关闭，本次未执行")
        }
        return block()
    }

    /**
     * 文件/命令工具的档位复查。目录侧已按档位过滤，执行侧再判一次：
     * 用户可能在两轮之间改了开关，旧调用不能因为「当时下发过」就继续执行。
     */
    private fun fileSystemTierError(toolName: String): String? {
        val tier = io.github.mangi.eta.agent.model.AgentFileSystemTier.current()
        return when {
            !io.github.mangi.eta.agent.model.AgentFileSystemTier.allowsFileTools(tier) ->
                "TERMINAL_TOOLS_DISABLED"
            !io.github.mangi.eta.agent.model.AgentFileSystemTier.publishes(tier, toolName) &&
                toolName in io.github.mangi.eta.agent.model.AgentFileSystemTier.shellTools ->
                "SHELL_TOOLS_DISABLED"
            !io.github.mangi.eta.agent.model.AgentFileSystemTier.publishes(tier, toolName) ->
                "WRITE_TOOLS_DISABLED"
            else -> null
        }
    }

    private fun terminalTool(toolName: String = "", block: () -> String): String {
        liveSkillEntries()
        if (skillAuthorizationChanged.get() || skillTreeMutationUncertain.get()) return errorResult("NEXT_TURN_REQUIRED", "本轮技能授权已撤销，终端会话已关闭；请开启下一轮任务")
        if (!terminalToolsEnabled()) {
            return errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具")
        }
        // 总闸已开，但用户可能单独关掉了命令执行或写盘；旧调用不能因为「当时下发过」继续执行。
        if (toolName.isNotBlank()) fileSystemTierError(toolName)?.let { code ->
            return errorResult(
                code,
                if (code == "SHELL_TOOLS_DISABLED") "命令执行已单独关闭；仍可使用读写文件的工具"
                else "写文件工具已关闭；只能读取文件",
            )
        }
        return block()
    }

    private fun fileVisionTool(toolName: String = "", block: () -> AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        if (!terminalToolsEnabled()) {
            return textResult(errorResult("TERMINAL_TOOLS_DISABLED", "请先启用终端/文件工具"))
        }
        if (toolName.isNotBlank()) fileSystemTierError(toolName)?.let { code ->
            return textResult(errorResult(code, "当前文件权限档位不允许该工具"))
        }
        return block()
    }

    private fun memoryToolPermissionError(toolName: String): AgentModelClient.ToolResult? {
        if (toolName !in MEMORY_TOOL_NAMES || (memoryToolsEnabled?.invoke() ?: AgentMemoryRepository.isEnabled(memoryAssistantId))) return null
        return AgentModelClient.ToolResult(
            content = errorResult("MEMORY_DISABLED", "当前助手未启用记忆"),
            sensitive = true,
        )
    }

    private fun memoryGet(args: JSONObject): String = try {
        val result = AgentMemoryRepository.read(
            query = args.optString("query").takeIf(String::isNotBlank),
            startLine = args.optInt("start_line", 1),
            maxChars = args.optInt("max_chars", 12_000),
            assistantId = memoryAssistantId,
        )
        JSONObject()
            .put("ok", true)
            .put("revision", result.snapshot.revision)
            .put("bytes", result.snapshot.byteSize)
            .put("line_count", result.snapshot.lineCount)
            .put("start_line", result.startLine ?: JSONObject.NULL)
            .put("end_line", result.endLine ?: JSONObject.NULL)
            .put("matched_lines", result.matchedLines)
            .put("has_more", result.hasMore)
            .put("content", result.content)
            .toString()
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆读取失败")
    }

    private fun memoryWrite(args: JSONObject): String = try {
        val revision = args.getString("revision")
        val mutation = when (args.getString("mode")) {
            "replace_range" -> AgentMemoryMutation.ReplaceRange(
                revision = revision,
                startLine = args.getInt("start_line"),
                endLine = args.getInt("end_line"),
                content = args.getString("content"),
            )
            "append" -> AgentMemoryMutation.Append(
                revision = revision,
                content = args.getString("content"),
            )
            "clear" -> AgentMemoryMutation.Clear(revision)
            else -> error("不支持的记忆写入模式")
        }
        when (val result = AgentMemoryRepository.mutate(mutation, memoryAssistantId)) {
            is AgentMemoryWriteResult.Success -> JSONObject()
                .put("ok", true)
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
            is AgentMemoryWriteResult.Conflict -> JSONObject()
                .put("ok", false)
                .put("code", "MEMORY_CONFLICT")
                .put("message", "记忆已发生变化，请先调用 memory_get 获取最新内容")
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
        }
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆写入失败")
    }

    private fun browserUse(args: JSONObject, toolCallId: String): AgentModelClient.ToolResult {
        if (!browserToolsEnabled()) {
            return textResult(errorResult("BROWSER_TOOLS_DISABLED", "请先启用网页浏览工具"))
        }
        val result = AgentBrowserSession.execute(
            context = context,
            args = args,
            runId = browserRunId,
            toolCallId = toolCallId,
            conversationId = browserConversationId,
        )
        return AgentModelClient.ToolResult(
            content = result.content,
            images = result.images.map { image ->
                AgentModelClient.ModelImage(
                    reference = image.dataUrl,
                    mimeType = image.mimeType,
                    bytes = image.bytes,
                    width = image.width,
                    height = image.height,
                    source = "agent_browser",
                )
            },
        )
    }

    private fun observeScreen(args: JSONObject): AgentModelClient.ToolResult {
        publishedObservation.set(PublishedObservation())
        val startedAt = SystemClock.elapsedRealtime()
        val options = AgentScreenObservationContract.resolve(args)
        val observation = screenObservationProvider?.invoke(options)
            ?: deviceController.observe(
                includeScreenshot = options.includeScreenshot,
                includeUiTree = options.includeUiTree,
                maxNodes = options.maxNodes,
            )
        publishedObservation.set(
            PublishedObservation(
                elements = observation.elementObservation,
                coordinateSpace = observation.coordinateSpace,
            ),
        )
        logger.debug {
            "Agent local tool action=observe_screen outcome=completed " +
                "observation=${observation.elementObservation?.id} " +
                "nodes=${observation.elementObservation?.nodes?.size ?: 0} " +
                "image=${observation.image?.bytes ?: 0} elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "coordinate=${observation.coordinateSpace?.summary()}"
        }
        return AgentModelClient.ToolResult(
            content = observation.content,
            images = listOfNotNull(observation.image)
        )
    }

    /**
     * 成功的 GUI 动作附带一次轻量观察：只读 UI 树、不截图，节点数减半。
     * 模型据此确认动作生效并直接用新的 observation_id 继续操作，不必再单独 observe_screen；
     * 失败或结果未知的动作不附带，保持"先重新观察"的既有约束。
     */
    private fun afterAction(raw: String): AgentModelClient.ToolResult {
        val result = runCatching { JSONObject(raw) }.getOrNull()
        if (result == null || !result.optBoolean("ok")) return textResult(raw)
        val before = publishedObservation.get().elements
        val after = runCatching {
            screenObservationProvider?.invoke(AFTER_ACTION_OPTIONS)
                ?: deviceController.observe(
                    includeScreenshot = false,
                    includeUiTree = true,
                    maxNodes = AFTER_ACTION_OPTIONS.maxNodes,
                )
        }.getOrElse { throwable ->
            logger.debug { "Agent local tool after-action observation failed: type=${throwable.javaClass.simpleName}" }
            return textResult(raw)
        }
        val elements = after.elementObservation ?: return textResult(raw)
        // 新快照取代动作前的快照：旧 index 在界面变化后本就不可靠，继续保留只会让模型误用。
        publishedObservation.set(publishedObservation.get().copy(elements = elements))
        result.put("after", AgentAfterActionSummary.build(before, elements))
        return textResult(result.toString())
    }

    private fun tap(args: JSONObject): String {
        val point = convertPoint(
            x = args.optInt("x"),
            y = args.optInt("y"),
            coordinateSpace = args.optString("coordinate_space")
        )
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
        showTap(point.x, point.y)
        return deviceController.tap(point.x, point.y)
    }

    private fun tapArea(args: JSONObject): String {
        val x1 = args.optInt("x1")
        val y1 = args.optInt("y1")
        val x2 = args.optInt("x2")
        val y2 = args.optInt("y2")
        val coordinateSpace = args.optString("coordinate_space")
        val first = convertPoint(x1, y1, coordinateSpace)
        val second = convertPoint(x2, y2, coordinateSpace)
        val point = ScreenPoint(
            x = ((first.x.toLong() + second.x.toLong()) / 2L).toInt(),
            y = ((first.y.toLong() + second.y.toLong()) / 2L).toInt(),
        )
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
        showTap(point.x, point.y)
        return deviceController.tap(point.x, point.y)
    }

    private fun tapElement(args: JSONObject): String {
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        val result = deviceController.tapElement(observation, index)
        if (result.isOkJson()) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.TAP)
            showTap(node.centerX, node.centerY)
        }
        return result
    }

    private fun longPressElement(args: JSONObject): String {
        val index = args.optInt("index", -1)
        val observation = requireElementObservation(args) ?: return observationError(args)
        val node = observation.nodes.firstOrNull { it.index == index }
        val durationMs = args.optInt("duration_ms", 800)
        if (node == null) {
            return errorResult("INVALID_NODE_INDEX", "观察快照中不存在节点 index=$index")
        }
        val result = deviceController.longPressElement(observation, index, durationMs)
        if (result.isOkJson()) {
            AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
            showLongPress(node.centerX, node.centerY, durationMs)
        }
        return result
    }

    private fun longPress(args: JSONObject): String {
        val point = convertPoint(
            x = args.optInt("x"),
            y = args.optInt("y"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val durationMs = args.optInt("duration_ms", 800)
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.LONG_PRESS)
        showLongPress(point.x, point.y, durationMs)
        return deviceController.longPress(point.x, point.y, durationMs)
    }

    private fun swipe(args: JSONObject): String {
        val start = convertPoint(
            x = args.optInt("x1"),
            y = args.optInt("y1"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val end = convertPoint(
            x = args.optInt("x2"),
            y = args.optInt("y2"),
            coordinateSpace = args.optString("coordinate_space")
        )
        val durationMs = args.optInt("duration_ms", 500)
        AgentHapticFeedback.perform(context, AgentHapticFeedback.Type.SWIPE)
        showSwipe(start.x, start.y, end.x, end.y, durationMs)
        return deviceController.swipe(
            start.x,
            start.y,
            end.x,
            end.y,
            durationMs
        )
    }

    private fun scrollElement(args: JSONObject): String {
        val observation = requireElementObservation(args) ?: return observationError(args)
        return deviceController.scrollElement(
            observation = observation,
            index = args.optInt("index", -1),
            direction = args.optString("direction"),
            amount = args.optString("amount"),
        )
    }

    private fun inputText(args: JSONObject): String {
        val text = args.optString("text")
        if (text.length > 1_000) {
            return errorResult("TEXT_TOO_LONG", "input_text 最多支持 1000 个字符")
        }
        return when (args.optString("mode", "append").lowercase(Locale.ROOT)) {
            "replace" -> replaceText(args)
            "paste" -> pasteText(args)
            else -> deviceController.inputText(text)
        }
    }

    /**
     * 统一的文本输入：replace（默认）整体替换为 text，text 为空即清空；append 在光标处插入。
     * 指定 index 时先确认节点来自最近一次观察；不指定时作用于当前输入焦点。
     * submit=true 在写入成功后按输入法回车，用于搜索、发送等提交动作。
     */
    private fun typeText(args: JSONObject): String {
        val text = args.optString("text")
        val mode = args.optString("mode", "replace").trim().lowercase(Locale.ROOT).ifBlank { "replace" }
        val written = when (mode) {
            "replace" -> replaceText(args)
            "append" -> {
                if (text.isEmpty()) return errorResult("INVALID_ARGUMENT", "append 模式的 text 不能为空")
                if (args.optNullableInt("index") != null) {
                    return errorResult("INVALID_ARGUMENT", "append 只作用于当前输入焦点；要写入指定输入框请用 mode=replace")
                }
                // 长文本走选区插入加粘贴回退，比逐字键入更稳定。
                if (text.length > TYPE_TEXT_INCREMENTAL_CHARS) pasteText(args) else deviceController.inputText(text)
            }
            else -> return errorResult("INVALID_ARGUMENT", "mode 只能是 replace 或 append")
        }
        val json = runCatching { JSONObject(written) }.getOrNull() ?: return written
        json.put("tool", "type_text").put("mode", mode)
        if (!json.optBoolean("ok") || !args.optBoolean("submit")) return json.toString()
        val submitted = runCatching { JSONObject(deviceController.pressKey("ENTER")) }.getOrNull()
        json.put("submitted", submitted?.optBoolean("ok") == true)
        if (submitted?.optBoolean("ok") != true) {
            // 文本已写入但提交失败：整体仍算成功，避免模型重复输入；由 submitted=false 提示单独补按回车或点按钮。
            json.put("submit_error", submitted?.optString("code").orEmpty().ifBlank { "SUBMIT_FAILED" })
        }
        return json.toString()
    }

    private fun replaceText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.replaceText(
            text = args.optString("text"),
            index = index,
            observation = observation,
        )
    }

    private fun clearText(args: JSONObject): String {
        val index = args.optNullableInt("index")
        val observation = if (index != null) {
            requireElementObservation(args) ?: return observationError(args)
        } else {
            null
        }
        return deviceController.clearText(index = index, observation = observation)
    }

    private fun setClipboard(args: JSONObject): String =
        deviceController.clipboardSet(requireContext(), args.optString("text"))

    private fun getClipboard(): String =
        deviceController.clipboardGet(requireContext())

    private fun pasteText(args: JSONObject): String =
        deviceController.pasteText(args.optString("text"))

    private fun waitForText(args: JSONObject): String =
        deviceController.waitForText(
            text = args.optString("text"),
            timeoutMs = args.optInt("timeout_ms", 10_000),
            includeDesc = args.optBoolean("include_desc", true),
            matchMode = args.optString("match", "contains")
        )

    private fun waitForPackage(args: JSONObject): String =
        deviceController.waitForPackage(
            packageName = args.optString("package_name"),
            timeoutMs = args.optInt("timeout_ms", 10_000)
        )

    /**
     * 坐标系必须显式声明。旧版按"上一次观察是否带截图"隐式切换默认值，模型无从得知，
     * 经常把截图像素当成屏幕像素；缺省时直接报参数错误，让模型在下一步改正。
     */
    private fun convertPoint(x: Int, y: Int, coordinateSpace: String): ScreenPoint {
        val space = publishedObservation.get().coordinateSpace
        val requestedSpace = coordinateSpace.trim().lowercase(Locale.ROOT)
        if (requestedSpace.isBlank()) {
            throw InvalidToolArgumentException(
                "缺少 coordinate_space：看截图定位用 normalized（0–999），坐标来自 ui_nodes 用 screen",
            )
        }
        if (requestedSpace == "normalized") {
            if (x !in 0..NORMALIZED_MAX || y !in 0..NORMALIZED_MAX) {
                throw InvalidToolArgumentException("normalized 坐标必须在 0–$NORMALIZED_MAX 之间：($x,$y)")
            }
            val (width, height) = space?.let { it.screenWidth to it.screenHeight }
                ?: deviceController.screenDimensions()
            return ScreenPoint(
                x = (x.toLong() * (width - 1) / NORMALIZED_MAX).toInt(),
                y = (y.toLong() * (height - 1) / NORMALIZED_MAX).toInt(),
            )
        }
        if (requestedSpace == "screen") {
            val (width, height) = space?.let { it.screenWidth to it.screenHeight }
                ?: deviceController.screenDimensions()
            if (x !in 0 until width || y !in 0 until height) {
                throw InvalidToolArgumentException(
                    "屏幕坐标超出范围：($x,$y) not in ${width}x$height",
                )
            }
            return ScreenPoint(x, y)
        }
        if (requestedSpace != "screenshot") {
            throw InvalidToolArgumentException("coordinate_space 只能是 normalized、screen 或 screenshot")
        }
        if (space == null) {
            throw InvalidToolArgumentException(
                "最近一次观察没有附图，没有 screenshot 坐标系；看截图定位请用 normalized，或先带截图重新观察",
            )
        }
        val point = runCatching { space.fromScreenshot(x, y) }
            .getOrElse { throwable ->
                throw InvalidToolArgumentException(
                    throwable.message ?: "截图坐标超出范围",
                )
            }
        return ScreenPoint(point.x, point.y)
    }

    private fun searchApps(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "query 不能为空")
        }
        val includeSystem = args.optBoolean("include_system", false)
        val limit = args.optInt("limit", 10).coerceIn(1, 20)
        val apps = findAppsByName(query, includeSystem).take(limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_apps")
            .put("query", query)
            .put("apps", apps.toJsonArray())
            .toString()
    }


    private fun keepVirtualResult(@Suppress("UNUSED_PARAMETER") args: JSONObject): String =
        errorResult(
            io.github.mangi.eta.agent.device.VirtualDisplaySession.NOT_READY,
            "虚拟副屏交接尚未就绪；只读阶段不支持保留、恢复或关闭应用，本次未执行",
        )

    private data class PreparedLaunch(val app: AppInfo? = null, val intent: Intent? = null, val error: String? = null)

    private fun prepareLaunchApp(args: JSONObject): PreparedLaunch {
        val packageName = args.optString("package_name").trim().ifBlank { null }
        val appName = args.optString("app_name").trim().ifBlank { null }

        val app = if (packageName != null) {
            findAppByPackage(packageName) ?: AppInfo(packageName = packageName, appName = appName ?: packageName)
        } else {
            if (appName == null) {
                return PreparedLaunch(error = errorResult("INVALID_ARGUMENT", "package_name 和 app_name 至少提供一个"))
            }
            val matches = findAppsByName(appName, includeSystem = false)
            val exactMatches = matches.filter { it.appName.equals(appName, ignoreCase = true) }
            when {
                exactMatches.size == 1 -> exactMatches.single()
                matches.size == 1 -> matches.single()
                matches.isEmpty() -> return PreparedLaunch(error = errorResult(
                    code = "APP_NOT_FOUND",
                    message = "未找到应用：$appName"
                ))
                else -> return PreparedLaunch(error = JSONObject()
                    .put("ok", false)
                    .put("code", "AMBIGUOUS_APP")
                    .put("message", "匹配到多个应用，请指定 package_name")
                    .put("candidates", matches.take(10).toJsonArray())
                    .toString())
            }
        }

        val context = requireContext()
        val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launchIntent == null) {
            return PreparedLaunch(error = errorResult(
                code = "APP_NOT_LAUNCHABLE",
                message = "应用不可启动或未安装：${app.packageName}"
            ))
        }
        return PreparedLaunch(app, launchIntent)
    }

    private fun launchApp(prepared: PreparedLaunch, selfLaunch: Boolean): String {
        prepared.error?.let { return it }
        val app = checkNotNull(prepared.app)
        val launchIntent = Intent(checkNotNull(prepared.intent))
        val context = requireContext()
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (selfLaunch) {
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
            context.startActivity(launchIntent, options.toBundle())
        } else {
            context.startActivity(launchIntent)
        }
        logger.info("Agent local tool action=launch_app outcome=started")
        return JSONObject()
            .put("ok", true)
            .put("tool", "launch_app")
            .put("app_name", app.appName)
            .put("package_name", app.packageName)
            .toString()
    }

    private fun openUri(args: JSONObject): String {
        val uriText = args.optString("uri").trim()
        if (uriText.isBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 不能为空")
        }
        val uri = Uri.parse(uriText)
        if (uri.scheme.isNullOrBlank()) {
            return errorResult("INVALID_ARGUMENT", "uri 缺少 scheme")
        }
        val context = requireContext()
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!HookSupport.resolvesActivity(context, intent)) {
            return errorResult("NO_ACTIVITY", "没有应用可以处理该 URI")
        }
        context.startActivity(intent)
        logger.info("Agent local tool action=open_uri outcome=started")
        return JSONObject()
            .put("ok", true)
            .put("tool", "open_uri")
            .put("scheme", uri.scheme?.lowercase(Locale.ROOT))
            .also { result ->
                if (uri.scheme.equals("https", true)) {
                    result.put("display_uri", uriText)
                }
            }
            .toString()
    }

    private fun runCommand(args: JSONObject): String =
        terminalController.runCommand(
            command = args.optString("command"),
            cwd = args.optString("cwd").ifBlank { null },
            timeoutSeconds = args.optInt("timeout_seconds", 30)
        )

    private fun terminal(args: JSONObject): String {
        return terminalController.terminalAction(
            action = args.optString("action", "open_and_exec"),
            command = args.optString("command"),
            cwd = args.optString("cwd").ifBlank { null },
            timeoutMs = args.optInt("timeout_ms", 30_000),
            identity = args.optString("identity"),
            mergeStderr = args.optBoolean("merge_stderr", false),
            sessionId = args.optString("session_id").ifBlank { null },
            jobId = args.optString("job_id").ifBlank { null },
            async = args.optBoolean("async", false),
            offsetChars = args.optInt("offset_chars", 0),
            maxChars = args.optInt("max_chars", 8_000),
            closeIfDone = args.optBoolean("close_if_done", false),
            environment = args.optString("environment", "android"),
            taskId = args.optString("task_id").ifBlank { null },
        )
    }

    private fun readFile(args: JSONObject): String =
        terminalController.readFile(
            path = args.optString("path"),
            offsetBytes = args.optInt("offset_bytes", 0),
            maxBytes = args.optInt("max_bytes", 65_536)
        )

    private fun writeFile(args: JSONObject): String =
        terminalController.writeFile(
            path = args.optString("path"),
            content = args.optString("content"),
            append = args.optBoolean("append", false)
        )

    private fun listDirectory(args: JSONObject): String =
        terminalController.listDirectory(
            path = args.optString("path"),
            showHidden = args.optBoolean("show_hidden", false),
            limit = args.optInt("limit", 80)
        )

    private fun findAppByPackage(packageName: String): AppInfo? =
        installedLauncherApps().firstOrNull { it.packageName == packageName }

    private fun findAppsByName(query: String, includeSystem: Boolean): List<AppInfo> {
        val normalizedQuery = query.normalized()
        return installedLauncherApps()
            .asSequence()
            .filter { includeSystem || !it.isSystemApp }
            .mapNotNull { app ->
                val score = app.matchScore(query, normalizedQuery)
                if (score == Int.MAX_VALUE) null else score to app
            }
            .sortedWith(compareBy<Pair<Int, AppInfo>> { it.first }.thenBy { it.second.appName })
            .map { it.second }
            .toList()
    }

    private fun AppInfo.matchScore(rawQuery: String, normalizedQuery: String): Int {
        val normalizedName = appName.normalized()
        val normalizedPackage = packageName.normalized()
        return when {
            packageName.equals(rawQuery, ignoreCase = true) -> 0
            appName.equals(rawQuery, ignoreCase = true) -> 1
            normalizedName == normalizedQuery -> 2
            normalizedPackage.contains(normalizedQuery) -> 3
            normalizedName.contains(normalizedQuery) -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun installedLauncherApps(): List<AppInfo> {
        val context = requireContext()
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(0L)
        )
        val apps = linkedMapOf<String, AppInfo>()
        resolveInfos.forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@forEach
            val applicationInfo = activityInfo.applicationInfo ?: return@forEach
            val packageName = applicationInfo.packageName ?: return@forEach
            val appName = resolveInfo.loadLabel(packageManager).toString().trim()
                .takeIf { it.isNotBlank() }
                ?: packageName
            apps.putIfAbsent(
                packageName,
                AppInfo(
                    packageName = packageName,
                    appName = appName,
                    isSystemApp = applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                )
            )
        }
        return apps.values.toList()
    }

    private fun requireContext(): Context =
        AgentAppContext.resolve()
            ?: error("无法获取 Android 进程 Context")

    private fun List<AppInfo>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { app ->
                array.put(
                    JSONObject()
                        .put("app_name", app.appName)
                        .put("package_name", app.packageName)
                        .put("is_system_app", app.isSystemApp)
                )
            }
        }

    private fun String.normalized(): String =
        trim().lowercase(Locale.ROOT)

    private fun String.isOkJson(): Boolean =
        runCatching { JSONObject(this).optBoolean("ok", false) }.getOrDefault(false)

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun requireElementObservation(
        args: JSONObject,
    ): RootShellDeviceController.ElementObservation? {
        val current = publishedObservation.get().elements ?: return null
        val requestedId = args.optString("observation_id").trim()
        return current.takeIf {
            ObservationReferencePolicy.validate(current.id, requestedId) ==
                ObservationReferencePolicy.Status.MATCH
        }
    }

    private fun observationError(args: JSONObject): String {
        val current = publishedObservation.get().elements
        val requestedId = args.optString("observation_id").trim()
        return when (ObservationReferencePolicy.validate(current?.id, requestedId)) {
            ObservationReferencePolicy.Status.NO_OBSERVATION ->
                errorResult("NO_OBSERVATION", "请先调用 observe_screen 获取 UI 节点")
            ObservationReferencePolicy.Status.ID_REQUIRED -> errorResult(
                "OBSERVATION_ID_REQUIRED",
                "节点动作必须携带同一次 observe_screen 返回的 observation_id",
            )
            ObservationReferencePolicy.Status.STALE -> errorResult(
                "STALE_OBSERVATION",
                "observation_id=$requestedId 已过期；当前为 ${current?.id}，请重新观察屏幕",
            )
            ObservationReferencePolicy.Status.MATCH -> errorResult(
                "OBSERVATION_ERROR",
                "观察快照状态异常，请重新观察屏幕",
            )
        }
    }


    private val skillAuthorizationChanged = AtomicBoolean(false)
    private val skillAuthorizationLock = Any()
    @Volatile private var lastConfirmedSkillEntries: List<SkillIndexEntry> = runSkillEntries

    fun currentSkillEntries(): List<SkillIndexEntry> = if (skillTreeMutationUncertain.get()) emptyList() else
        liveSkillEntries().filter { runSkillsRoot != null || SkillParser.normalizeSkillLookup(it.id) !in mutatedSkillIds }

    private fun liveSkillEntries(): List<SkillIndexEntry> {
        if (runSkillsRoot == null) {
            return runSkillEntries.filter { File(it.skillFilePath).isFile }
        }
        synchronized(skillAuthorizationLock) {
            val decision = skillAuthorizationDecision()
            val (entries, confirmed) = SkillRunAuthorization.apply(
                snapshot = runSkillEntries,
                idOf = { it.id },
                stillPresent = { File(it.skillFilePath).isFile },
                decision = decision,
                previous = lastConfirmedSkillEntries,
            )
            if (confirmed) {
                val revoked = runSkillEntries.map { it.id }.toSet() - entries.map { it.id }.toSet()
                if (revoked.isNotEmpty() && skillAuthorizationChanged.compareAndSet(false, true)) {
                    terminalController.interruptAll()
                    terminalController.stopOwnedDaemons()
                }
                if (revoked.isNotEmpty()) {
                    SkillRuntime.pruneRunSkills(context, runSkillsRoot, entries.map { it.id }.toSet())
                }
                lastConfirmedSkillEntries = entries
            }
            return entries
        }
    }

    private fun skillAuthorizationDecision(): SkillRunAuthorization.Decision {
        val profileResult = runCatching {
            if (!AssistantRepository.isReady()) error("assistant repository unavailable")
            AssistantRepository.currentProfile(memoryAssistantId)
        }
        val installedResult = runCatching {
            skillIndexService?.listSkillsForManagement(forceRefresh = false)
                ?.filter { it.installed }
                ?.map { it.id }
                ?.toSet()
        }
        return SkillRunAuthorization.decide(
            repositoryReady = AssistantRepository.isReady(),
            profileLookupFailed = profileResult.isFailure,
            profileEnabledIds = profileResult.getOrNull()?.enabledSkillIds?.toSet(),
            installedLookupFailed = installedResult.isFailure || (runSkillsRoot != null && skillIndexService == null),
            installedIds = installedResult.getOrNull(),
        )
    }

    private fun resolveRunSkill(skillId: String): SkillIndexEntry? {
        val normalized = SkillParser.normalizeSkillLookup(skillId)
        if (normalized.isBlank()) return null
        val entries = liveSkillEntries()
        return entries.firstOrNull { SkillParser.normalizeSkillLookup(it.id) == normalized }
            ?: entries.firstOrNull { SkillParser.normalizeSkillLookup(it.name) == normalized }
            ?: entries.firstOrNull { skillId == it.rootPath || skillId == it.skillFilePath ||
                skillId == "/var/minis/skills/${it.id}" || skillId == "/var/minis/skills/${it.id}/SKILL.md" }
    }

    // ==================== Skills tools ====================

    private fun skillsList(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val query = args.optString("query").trim().lowercase()
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val entries = liveSkillEntries()
            .filter { entry -> isVisibleInCurrentRun(entry.id) }
            .filter { entry ->
                if (query.isBlank()) true
                else listOf(entry.id, entry.name, entry.description, entry.skillFilePath, entry.rootPath)
                    .any { it.lowercase().contains(query) }
            }
            .take(limit)
        val items = JSONArray()
        entries.forEach { entry ->
            val capabilities = JSONArray()
            if (entry.hasScripts) capabilities.put("scripts")
            if (entry.hasReferences) capabilities.put("references")
            if (entry.hasAssets) capabilities.put("assets")
            if (entry.hasEvals) capabilities.put("evals")
            items.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("description", entry.description)
                    .put("enabled", entry.enabled)
                    .put("source", entry.source)
                    .put("rootPath", "/var/minis/skills/${entry.id}")
                    .put("skillFilePath", "/var/minis/skills/${entry.id}/SKILL.md")
                    .put("capabilities", capabilities)
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", entries.size)
            .put("items", items)
            .toString()
    }

    private fun skillsRead(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val loader = skillLoader
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能加载器未初始化")
        val skillId = args.optString("skillId").trim()
        if (skillId.isBlank()) return errorResult("MISSING_PARAM", "缺少 skillId")
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = resolveRunSkill(skillId)
            ?: return if (SkillParser.normalizeSkillLookup(skillId) in mutatedSkillIds) nextTurnRequired(skillId) else errorResult("NOT_FOUND", "未找到 skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compat = SkillCompatibilityChecker.evaluate(entry)
        if (!compat.available) return errorResult("INCOMPATIBLE", compat.reason ?: "当前环境不可用")
        val resolved = loader.load(entry, "agent 主动读取 skill")
            ?: return errorResult("READ_FAILED", "读取 SKILL.md 失败：${entry.skillFilePath}")
        val body = if (resolved.bodyMarkdown.length <= maxChars) {
            resolved.bodyMarkdown
        } else {
            resolved.bodyMarkdown.take(maxChars) + "\n..."
        }
        val references = JSONArray()
        resolved.loadedReferences.forEach { references.put(it) }
        val frontmatter = JSONObject()
        resolved.frontmatter.forEach { (k, v) -> frontmatter.put(k, v) }
        return JSONObject()
            .put("ok", true)
            .put("id", entry.id)
            .put("name", entry.name)
            .put("description", entry.description)
            .put("rootPath", entry.rootPath)
            .put("skillFilePath", entry.skillFilePath)
            .put("scriptsDir", resolved.scriptsDir ?: JSONObject.NULL)
            .put("assetsDir", resolved.assetsDir ?: JSONObject.NULL)
            .put("references", references)
            .put("frontmatter", frontmatter)
            .put("bodyMarkdown", body)
            .toString()
    }

    private fun skillsReadResource(args: JSONObject): String {
        if (skillTreeMutationUncertain.get()) return nextTurnRequired("Skill 树")
        val indexService = skillIndexService
            ?: return errorResult("SKILLS_UNAVAILABLE", "技能服务未初始化")
        val reader = skillResourceReader
            ?: return errorResult("SKILLS_UNAVAILABLE", "Skill 资源读取器未初始化")
        val skillId = args.getString("skillId").trim()
        val relativePath = args.getString("relativePath").trim()
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = resolveRunSkill(skillId)
            ?: return if (SkillParser.normalizeSkillLookup(skillId) in mutatedSkillIds) nextTurnRequired(skillId) else errorResult("NOT_FOUND", "未找到已启用 Skill：$skillId")
        if (!isVisibleInCurrentRun(entry.id)) return nextTurnRequired(entry.id)
        val compatibility = SkillCompatibilityChecker.evaluate(entry)
        if (!compatibility.available) {
            return errorResult(
                "INCOMPATIBLE",
                compatibility.reason ?: "当前环境不可用",
            )
        }
        val dataRead = relativePath.startsWith("data/") && runSkillsRoot != null
        val readEntry = if (dataRead) entry.copy(rootPath = File(runSkillsRoot!!.parentFile, "skill-data/${entry.id}").canonicalPath) else entry
        return when (val result = reader.readText(readEntry, if (dataRead) relativePath.removePrefix("data/") else relativePath)) {
            is SkillResourceReadResult.Success -> {
                val truncated = result.text.length > maxChars
                val visibleText = if (truncated) {
                    result.text.take(maxChars).let { prefix ->
                        if (prefix.lastOrNull()?.isHighSurrogate() == true) {
                            prefix.dropLast(1)
                        } else {
                            prefix
                        }
                    }
                } else {
                    result.text
                }
                JSONObject()
                    .put("ok", true)
                    .put("skillId", entry.id)
                    .put("relativePath", result.relativePath)
                    .put("text", visibleText)
                    .put("truncated", truncated)
                    .put("totalChars", result.text.length)
                    .toString()
            }
            is SkillResourceReadResult.Failure -> errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private fun skillsListCurated(): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val inspection = source.listCurated()
            rememberInspection(
                repository = GitHubSkillRepositoryParser.parse(inspection.repository),
                inspection = inspection,
                rememberDefault = true,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInspectGitHub(args: JSONObject): String {
        val source = githubSkillSource
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "GitHub Skill 服务未初始化")
        return skillSourceResult {
            val repository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = args.optString("path").takeIf { args.has("path") },
            )
            val inspection = source.inspect(repository)
            rememberInspection(
                repository = repository,
                inspection = inspection,
                rememberDefault = repository.ref == null,
            )
            inspectionResult(inspection)
        }
    }

    private fun skillsInstallFromGitHub(args: JSONObject): String {
        val replaceExisting = args.optBoolean("replaceExisting", false)
        return skillSourceResult {
            val requestedRepository = GitHubSkillRepositoryParser.resolve(
                repository = args.getString("repository"),
                explicitRef = args.optString("ref").takeIf { args.has("ref") },
                explicitPath = null,
            )
            val pathsJson = args.getJSONArray("paths")
            val selectedPaths = (0 until pathsJson.length()).map { index ->
                GitHubSkillRepositoryParser.normalizeRelativePath(pathsJson.getString(index))
            }
            if (replaceExisting && selectedPaths.size != 1) {
                return@skillSourceResult errorResult(
                    "SKILL_REPLACE_SCOPE_TOO_BROAD",
                    "一次只能替换一个 Skill 路径；请逐个重试",
                )
            }
            val expectedReplacementId = args.optString("expectedReplacementId").trim()
            val repository = if (replaceExisting) {
                validateReplacementReplay(
                    requestedRepository = requestedRepository,
                    selectedPaths = selectedPaths,
                    expectedReplacementId = expectedReplacementId,
                )?.let { return@skillSourceResult it }
                requestedRepository.copy(ref = pendingSkillConflict.get()!!.commitSha)
            } else {
                val snapshot = inspectedGitHubSnapshots[
                    inspectionKey(requestedRepository.slug, requestedRepository.ref)
                ] ?: return@skillSourceResult errorResult(
                    "SKILL_INSPECTION_REQUIRED",
                    "安装前必须在本轮先检查同一仓库与 ref 的 Skill 候选",
                )
                val invalidSelection = selectedPaths.firstOrNull {
                    it !in snapshot.candidatesByPath
                }
                if (invalidSelection != null) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查返回的候选中：$invalidSelection",
                    )
                }
                val snapshotPrefix = snapshot.prefix
                if (
                    snapshotPrefix != null &&
                    selectedPaths.any {
                        it != snapshotPrefix && !it.startsWith("$snapshotPrefix/")
                    }
                ) {
                    return@skillSourceResult errorResult(
                        "INVALID_SKILL_SELECTION",
                        "所选路径不在本轮检查的目录范围内",
                    )
                }
                requestedRepository.copy(ref = snapshot.commitSha)
            }
            val prefix = requestedRepository.path?.takeUnless { it == "." }
            if (
                prefix != null &&
                selectedPaths.any { it != prefix && !it.startsWith("$prefix/") }
            ) {
                return@skillSourceResult errorResult(
                    "INVALID_SKILL_SELECTION",
                    "所选路径不在 GitHub URL 指定目录内",
                )
            }
            val source = githubSkillSource
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "GitHub Skill 服务未初始化",
                )
            val installer = skillPackageInstaller
                ?: return@skillSourceResult errorResult(
                    "SKILL_INSTALLER_UNAVAILABLE",
                    "Skill 安装器未初始化",
                )
            source.downloadArchive(repository).use { archive ->
                if (closed.get()) {
                    return@skillSourceResult errorResult(
                        "SKILL_INSTALL_CANCELLED",
                        "Skill 安装已取消，未提交文件",
                    )
                }
                val result = installer.installRepositoryZip(
                    openStream = { archive.file.inputStream() },
                    selectedPaths = selectedPaths,
                    replaceUserSkills = replaceExisting,
                    expectedReplacementIds = if (replaceExisting) {
                        setOf(expectedReplacementId)
                    } else {
                        emptySet()
                    },
                    isCancelled = closed::get,
                )
                installResult(
                    result = result,
                    repository = archive.repository,
                    ref = archive.ref,
                    commitSha = archive.commitSha,
                    selectedPaths = selectedPaths,
                )
            }
        }
    }

    /**
     * 安装手机本地目录中的一个 Skill。目录必须只包含一个 SKILL.md；
     * 发现多个时返回各自目录，由模型选定目录后重试。
     */
    private fun skillsInstallLocal(args: JSONObject): String {
        val installer = skillPackageInstaller
            ?: return errorResult("SKILL_INSTALLER_UNAVAILABLE", "Skill 安装器未初始化")
        val requestedDirectory = args.optString("directory").trim()
        if (requestedDirectory.isEmpty()) {
            throw InvalidToolArgumentException("directory is required")
        }
        val replaceExisting = args.optBoolean("replaceExisting", false)
        val expectedReplacementId = args.optString("expectedReplacementId").trim()
        if (!replaceExisting && expectedReplacementId.isNotEmpty()) {
            return errorResult("INVALID_SELECTION", "仅替换现有用户 Skill 时可指定 expectedReplacementId")
        }
        // minis://、/workspace 与 /var/minis 先还原成宿主路径，其余手机路径原样使用。
        val resolvedDirectory = runCatching {
            io.github.mangi.eta.agent.terminal.LinuxGuestPathResolver
                .resolveForApp(context, requestedDirectory)
        }.getOrDefault(requestedDirectory)
        localSkillStorageError(resolvedDirectory)?.let { code ->
            return errorResult(
                code,
                "读取手机公共存储需要「所有文件访问」权限，请先在系统设置中授予后再重试",
            )
        }
        val directory = File(resolvedDirectory)
        if (!directory.isDirectory) {
            return errorResult("LOCAL_DIRECTORY_NOT_FOUND", "找不到目录：$requestedDirectory")
        }
        if (!directory.canRead()) {
            return errorResult("LOCAL_DIRECTORY_NOT_READABLE", "无法读取目录：$requestedDirectory")
        }
        val candidates = when (val inspection = installer.inspectLocalDirectory(directory, closed::get)) {
            is SkillArchiveInspectionResult.Failure ->
                return errorResult(inspection.error.code.name, inspection.error.message)
            is SkillArchiveInspectionResult.Success -> inspection.candidates
        }
        if (candidates.size > 1) {
            val listed = JSONArray()
            candidates.forEach { candidate ->
                listed.put(
                    JSONObject()
                        .put("id", candidate.id)
                        .put("name", candidate.name)
                        .put("description", candidate.description)
                        .put("directory", localCandidateDirectory(resolvedDirectory, candidate.relativePath)),
                )
            }
            return JSONObject()
                .put("ok", false)
                .put("code", "MULTIPLE_SKILLS_FOUND")
                .put("message", "该目录包含 ${candidates.size} 个 Skill；请从 candidates 中选择一个目录，作为 directory 重试")
                .put("directory", resolvedDirectory)
                .put("candidates", listed)
                .toString()
        }
        val candidate = candidates.single()
        if (replaceExisting && expectedReplacementId != candidate.id) {
            return errorResult(
                "INVALID_SELECTION",
                "expectedReplacementId 与目录中的 Skill 不一致：${candidate.id}",
            )
        }
        val result = installer.installLocalDirectory(
            directory = directory,
            replaceUserSkill = replaceExisting,
            expectedReplacementId = expectedReplacementId.takeIf { replaceExisting },
            isCancelled = closed::get,
        )
        return localInstallResult(result, resolvedDirectory)
    }

    /** 公共存储目录需要「所有文件访问」权限；应用私有目录与工作区路径不需要。 */
    private fun localSkillStorageError(resolvedDirectory: String): String? {
        val publicish = resolvedDirectory.startsWith("/storage/") ||
            resolvedDirectory.startsWith("/sdcard") ||
            resolvedDirectory.startsWith("/mnt/sdcard")
        return if (publicish && !Environment.isExternalStorageManager()) "STORAGE_ACCESS_REQUIRED" else null
    }

    private fun localCandidateDirectory(root: String, relativePath: String): String {
        val base = root.trimEnd('/')
        return if (relativePath.isBlank() || relativePath == ".") base else "$base/$relativePath"
    }

    private fun localInstallResult(result: SkillInstallResult, directory: String): String = when (result) {
        is SkillInstallResult.Success -> {
            pendingSkillConflict.set(null)
            val installed = JSONArray()
            result.installed.forEach { skill ->
                mutatedSkillIds += SkillParser.normalizeSkillLookup(skill.id)
                installed.put(
                    JSONObject()
                        .put("id", skill.id)
                        .put("name", skill.name),
                )
            }
            val enableResult = runCatching {
                AssistantRepository.enableSkills(result.installed.map { it.id }, memoryAssistantId)
            }
            JSONObject()
                .put("ok", true)
                .put("source", "local")
                .put("directory", directory)
                .put("installed", installed)
                .put("available", if (enableResult.isSuccess) "next_turn" else "installed_not_enabled")
                .put("enabled", enableResult.isSuccess)
                .put("enableError", if (enableResult.isSuccess) JSONObject.NULL else "ASSISTANT_ENABLE_FAILED")
                .put("scriptsExecuted", false)
                .put("message", if (enableResult.isSuccess) "Skill 已安装并启用，新版本从下一轮任务开始可用；本轮已批准的旧快照不变，安装过程未执行脚本"
                    else "Skill 文件已安装，但未能为本轮所属助手启用；请在该助手设置中检查。安装过程未执行脚本")
                .toString()
        }
        is SkillInstallResult.Conflict -> {
            val conflicts = JSONArray()
            result.conflicts.forEach { conflict ->
                conflicts.put(
                    JSONObject()
                        .put("id", conflict.id)
                        .put("name", conflict.name)
                        .put("replaceAllowed", conflict.replaceAllowed),
                )
            }
            val replaceable = result.conflicts.singleOrNull()?.takeIf { it.replaceAllowed }
            JSONObject()
                .put("ok", false)
                .put("code", "SKILL_CONFLICT")
                .put("message", "Skill 已存在；可替换的单个用户 Skill 可用相同 directory 加上 replaceExisting=true 与 expectedReplacementId 重试，内置 Skill 不可覆盖")
                .put("source", "local")
                .put("directory", directory)
                .put("expectedReplacementId", replaceable?.id ?: JSONObject.NULL)
                .put("conflicts", conflicts)
                .toString()
        }
        is SkillInstallResult.Failure -> {
            if (result.error.code == SkillInstallErrorCode.COMMIT_FAILED) {
                skillTreeMutationUncertain.set(true)
            }
            errorResult(result.error.code.name, result.error.message)
        }
    }

    private fun inspectionResult(
        inspection: io.github.mangi.eta.agent.skill.GitHubSkillInspection,
    ): String {
        val installedIds = skillIndexService
            ?.listSkillsForManagement()
            .orEmpty()
            .filter { it.installed }
            .mapTo(mutableSetOf()) { SkillParser.normalizeSkillLookup(it.id) }
        val items = JSONArray()
        inspection.candidates.forEach { candidate ->
            items.put(
                JSONObject()
                    .put("name", candidate.name)
                    .put("path", candidate.path)
                    .put(
                        "installed",
                        SkillParser.normalizeSkillLookup(candidate.name) in installedIds,
                    ),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("repository", inspection.repository)
            .put("ref", inspection.ref)
            .put("commitSha", inspection.commitSha)
            .put("prefix", inspection.prefix ?: JSONObject.NULL)
            .put("count", inspection.candidates.size)
            .put("items", items)
            .toString()
    }

    private fun rememberInspection(
        repository: GitHubSkillRepository,
        inspection: GitHubSkillInspection,
        rememberDefault: Boolean,
    ) {
        val snapshot = GitHubInspectionSnapshot(
            commitSha = inspection.commitSha,
            prefix = inspection.prefix,
            candidatesByPath = inspection.candidates.associate { it.path to it.name },
        )
        inspectedGitHubSnapshots[inspectionKey(repository.slug, repository.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.ref)] = snapshot
        inspectedGitHubSnapshots[inspectionKey(repository.slug, inspection.commitSha)] = snapshot
        if (rememberDefault) {
            inspectedGitHubSnapshots[inspectionKey(repository.slug, null)] = snapshot
        }
    }

    private fun inspectionKey(repository: String, ref: String?): String =
        "${repository.lowercase(Locale.ROOT)}@${ref.orEmpty()}"

    private fun validateReplacementReplay(
        requestedRepository: GitHubSkillRepository,
        selectedPaths: List<String>,
        expectedReplacementId: String,
    ): String? {
        val pending = pendingSkillConflict.get() ?: return errorResult(
            "SKILL_REPLACE_CAPABILITY_REQUIRED",
            "没有可供精确重放的 Skill 冲突",
        )
        if (
            !requestedRepository.slug.equals(pending.repository, ignoreCase = true) ||
            requestedRepository.ref != pending.commitSha ||
            selectedPaths.singleOrNull() != pending.selectedPath ||
            expectedReplacementId != pending.expectedReplacementId
        ) {
            return errorResult(
                "SKILL_REPLACE_CAPABILITY_MISMATCH",
                "覆盖参数必须精确重放冲突结果中的仓库、commitSha、路径与 Skill ID",
            )
        }
        return null
    }

    private fun isVisibleInCurrentRun(skillId: String): Boolean {
        val normalized = SkillParser.normalizeSkillLookup(skillId)
        return currentSkillEntries().any { SkillParser.normalizeSkillLookup(it.id) == normalized }
    }

    private fun nextTurnRequired(skillId: String): String = errorResult(
        "NEXT_TURN_REQUIRED",
        "Skill $skillId 在本轮已安装或变更，将从下一轮对话开始可用",
    )

    private fun installResult(
        result: SkillInstallResult,
        repository: String,
        ref: String,
        commitSha: String,
        selectedPaths: List<String>,
    ): String = when (result) {
        is SkillInstallResult.Success -> {
            pendingSkillConflict.set(null)
            val installed = JSONArray()
            result.installed.forEach { skill ->
                mutatedSkillIds += SkillParser.normalizeSkillLookup(skill.id)
                installed.put(
                    JSONObject()
                        .put("id", skill.id)
                        .put("name", skill.name),
                )
            }
            val enableResult = runCatching {
                AssistantRepository.enableSkills(result.installed.map { it.id }, memoryAssistantId)
            }
            JSONObject()
                .put("ok", true)
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("installed", installed)
                .put("available", if (enableResult.isSuccess) "next_turn" else "installed_not_enabled")
                .put("enabled", enableResult.isSuccess)
                .put("enableError", if (enableResult.isSuccess) JSONObject.NULL else "ASSISTANT_ENABLE_FAILED")
                .put("scriptsExecuted", false)
                .put("message", if (enableResult.isSuccess) "Skill 已安装并启用，新版本从下一轮任务开始可用；本轮已批准的旧快照不变，安装过程未执行脚本"
                    else "Skill 文件已安装，但未能为本轮所属助手启用；请在该助手设置中检查。安装过程未执行脚本")
                .toString()
        }
        is SkillInstallResult.Conflict -> {
            val conflicts = JSONArray()
            result.conflicts.forEach { conflict ->
                conflicts.put(
                    JSONObject()
                        .put("id", conflict.id)
                        .put("name", conflict.name)
                        .put("replaceAllowed", conflict.replaceAllowed),
                )
            }
            pendingSkillConflict.set(
                result.conflicts.singleOrNull()
                    ?.takeIf { it.replaceAllowed && selectedPaths.size == 1 }
                    ?.let { conflict ->
                        PendingSkillConflictCapability(
                            repository = repository,
                            commitSha = commitSha,
                            selectedPath = selectedPaths.single(),
                            expectedReplacementId = conflict.id,
                            expectedReplacementName = conflict.name,
                        )
                    },
            )
            JSONObject()
                .put("ok", false)
                .put("code", "SKILL_CONFLICT")
                .put("message", "Skill 已存在；可替换的单个用户 Skill 可按返回参数直接重试，内置 Skill 不可覆盖")
                .put("repository", repository)
                .put("ref", ref)
                .put("commitSha", commitSha)
                .put("selectedPaths", JSONArray(selectedPaths))
                .put("conflicts", conflicts)
                .toString()
        }
        is SkillInstallResult.Failure -> {
            if (result.error.code == SkillInstallErrorCode.COMMIT_FAILED) {
                skillTreeMutationUncertain.set(true)
            }
            errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    private inline fun skillSourceResult(block: () -> String): String = try {
        block()
    } catch (failure: GitHubSkillSourceException) {
        errorResult(failure.code, failure.message ?: "GitHub Skill 请求失败")
    }

    private fun textToSpeech(args: JSONObject): String {
        val text = args.optString("text").trim()
        if (text.isBlank()) {
            throw InvalidToolArgumentException("text is required")
        }
        if (text.length > 8_000) {
            throw InvalidToolArgumentException("text is too long")
        }
        if (SpeechPlayback.state.value.recording) {
            return errorResult("SPEECH_BUSY", "正在录音，无法朗读")
        }
        val report = AgentSpeechReport()
        val main = android.os.Looper.getMainLooper()
        android.os.Handler(main).post {
            SpeechPlayback.speak(context, AGENT_SPEECH_OWNER, text, listener = report)
        }
        // Callbacks arrive on main; waiting there would only time out.
        if (main.isCurrentThread) return AgentSpeechStatus.Pending.toJson().toString()
        // Cloud synthesis can take seconds; report what actually happened instead of assuming playback.
        return report.await(AGENT_SPEECH_START_TIMEOUT_MS).toJson().toString()
    }

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private fun textResult(content: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content)

    private data class ScreenPoint(val x: Int, val y: Int)

    private class InvalidToolArgumentException(message: String) : IllegalArgumentException(message)

    private data class PublishedObservation(
        val elements: RootShellDeviceController.ElementObservation? = null,
        val coordinateSpace: RootShellDeviceController.CoordinateSpace? = null,
    )

    private data class GitHubInspectionSnapshot(
        val commitSha: String,
        val prefix: String?,
        val candidatesByPath: Map<String, String>,
    )

    private fun showTap(x: Int, y: Int) {
        GestureIndicator.showTap(context, x, y)
    }

    private fun showLongPress(x: Int, y: Int, durationMs: Int) {
        GestureIndicator.showLongPress(context, x, y, durationMs)
    }

    private fun showSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        GestureIndicator.showSwipe(context, x1, y1, x2, y2, durationMs)
    }

    private data class AppInfo(
        val packageName: String,
        val appName: String,
        val isSystemApp: Boolean = false
    )

    private companion object {
        val DEVICE_DIRECT_TOOL_NAMES = setOf(
            "schedule_manage",
            "alarm_manage",
            "set_timer",
            "device_status",
            "inspect_app",
            "network_info",
            "top_memory_apps",
            "top_storage_apps",
            "media_control",
            "set_volume",
            "set_ringer_mode",
            "set_sound_mode",
        )
        val DEVICE_SENSITIVE_READ_TOOL_NAMES = setOf(
            "get_setting",
            "wifi_credentials",
            "recent_notifications",
            "search_notification_history",
            "recent_app_activity",
            "app_usage_summary",
            "get_current_location",
            "get_device_environment",
            "list_alarms",
            "list_active_timers",
            "search_clipboard_history",
            "get_health_summary",
            "read_sms_code",
            "get_logcat",
            "search_media",
            "search_audio",
            "search_recordings",
            "search_files",
            "search_calendar_events",
            "list_calendars",
            "read_calendar_event",
            "read_note",
            "get_flashlight",
            "get_hotspot",
            "get_display_state",
            "get_sound_state",
            "get_device_state",
            "search_contacts",
            "search_call_history",
            "search_messages",
            "search_downloads",
            "search_coloros_notes",
            "search_notes",
            "search_coloros_recordings",
            "search_recording_summaries",
            "search_coloros_memories",
            "search_saved_places",
            "search_personal_orders",
            // ColorOS 个人数据历史索引（DMP）：只读索引查询与引用读取。
            "search_bills",
            "search_todos",
            "search_calendar_todos",
            "search_memory_collections",
            "search_daily_events",
            "search_flights",
            "search_hotels",
            "search_trains",
            "read_personal_item",
            "summarize_bills",
            "search_qq_chat_images",
            "search_wechat_chat_images",
            "list_installed_apps",
            // Root 诊断读取：输出包含系统内部状态，原始结果不进入持久会话。
            "search_settings",
            "dumpsys_diag",
        )
        val DEVICE_SENSITIVE_ACTION_TOOL_NAMES = setOf(
            "set_setting",
            "set_device_state",
            "app_state_control",
            "create_calendar_event",
            "create_calendar_events",
            "update_calendar_event",
            "delete_calendar_event",
            "create_note",
            "delete_note",
            "set_alarm_enabled",
            "update_alarm_time",
            "set_flashlight",
            "set_hotspot",
            "set_screen_timeout",
            "delete_alarm",
            "update_alarm",
            "set_do_not_disturb",
            "set_brightness",
            "screen_action",
            "manage_app",
            "manage_file",
            "system_property",
            "display_control",
            "deep_launch",
            "package_control",
            "user_control",
            "appops_control",
            "power_control",
            "ime_control",
            "connect_wifi",
            "set_timezone",
        )
        val DEVICE_TOOL_NAMES =
            DEVICE_DIRECT_TOOL_NAMES + DEVICE_SENSITIVE_READ_TOOL_NAMES +
                DEVICE_SENSITIVE_ACTION_TOOL_NAMES
        val MEMORY_TOOL_NAMES = setOf("memory_get", "memory_write")
    }
}

private val AFTER_ACTION_OPTIONS = AgentScreenObservationContract.Options(
    includeScreenshot = false,
    includeUiTree = true,
    maxNodes = 30,
)

/** 归一化坐标上界：0–999，与主流 GUI 模型的输出习惯一致。 */
private const val NORMALIZED_MAX = 999

/** type_text append 超过该长度时改走选区插入 + 粘贴回退，比逐字键入更稳定。 */
private const val TYPE_TEXT_INCREMENTAL_CHARS = 200
