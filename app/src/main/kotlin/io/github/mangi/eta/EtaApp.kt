package io.github.mangi.eta

import android.app.Application
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.VirtualDisplayWebPreview
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppFileLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.LauncherIconSync
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 模块 UI 进程的 Application。
 *
 * 在进程启动时注册 [XposedServiceHelper] 监听器，框架会通过 XposedProvider 推送 binder，
 * 随后 UI 即可拿到 [XposedService] 写入 RemotePreferences，跨进程同步到各 hook 进程。
 *
 * UI 侧通过 [XposedService] 写入 RemotePreferences。
 */
class EtaApp : Application(), XposedServiceHelper.OnServiceListener {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val subAgentConfigurationLifecycle by lazy {
        val store = io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences()
        SubAgentConfigurationResetLifecycle(
            beginMaintenance = { io.github.mangi.eta.agent.runtime.AgentExecutionService.beginBackupMaintenance() },
            recoverInterruptedRestoreBeforeWork = {
                io.github.mangi.eta.data.repository.EtaBackupRepository.recoverInterruptedImport(
                    this@EtaApp, maintenanceAlreadyHeld = true,
                )
            },
            recoverConfigurationDurability = { store.recoverDurability() },
            resetLegacyConfigurationOnce = { store.resetLegacyConfigurationOnce() },
            isConfigurationResetComplete = { store.isConfigurationResetComplete() },
            endMaintenance = { io.github.mangi.eta.agent.runtime.AgentExecutionService.endBackupMaintenance() },
        )
    }

    /** Explicit retry after a failed startup gate; never resets a child process or a live app on demand. */
    internal suspend fun initializeSubAgentConfigurationBeforeWork(): Boolean =
        subAgentConfigurationLifecycle.initializeBeforeWork(
            AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName),
        )

    interface ServiceStateListener {
        fun onServiceStateChanged(service: XposedService?)
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.initLocal(this)
        io.github.mangi.eta.agent.tool.RetiredScheduleChannels.cleanup(this)
        io.github.mangi.eta.ui.haptics.AppForeground.install(this)
        if (!AppProcessPolicy.shouldInitializeFullRuntime(Application.getProcessName(), packageName)) {
            return
        }
        TerminalRuntime.initialize(this)
        RootAccess.initialize(this)
        SettingsDataStore.init(this)
        runBlocking(Dispatchers.IO) {
            io.github.mangi.eta.ui.components.StreamDiagnosticControl.initialize(this@EtaApp)
        }
        AppFileLogger.install(this)
        io.github.mangi.eta.ui.components.ComposeSystemTrace.install()
        AgentMemoryRepository.init(this)
        // 运行时工具权限需要同步读取全局记忆开关；先同步一次快照，再由下面的 collector 持续更新。
        runBlocking(Dispatchers.IO) {
            runCatching { SettingsDataStore.settings().memoryEnabled }
                .onSuccess(AgentMemoryRepository::setGloballyEnabled)
        }
        ProviderRepository.init(this)
        AssistantRepository.init(this)
        McpServerRepository.init(this)
        runBlocking(Dispatchers.IO) {
            runCatching {
                initializeSubAgentConfigurationBeforeWork()
            }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Startup backup recovery/sub-agent reset failed: type=${throwable.safeLogType()}"
                )
                // Never initialize work admission after incomplete recovery or an unconfirmed reset.
                throw throwable
            }
        }
        // Complete legacy accounting migration before any UI/runtime can issue a new request.
        runBlocking(Dispatchers.IO) {
            io.github.mangi.eta.data.repository.UsageStatsRepository.initializeConversationUsage(this@EtaApp)
        }
        applicationScope.launch {
            runCatching {
                LauncherIconSync.apply(this@EtaApp, AppearanceSettingsRepository.settings())
            }
            runCatching { SettingsDataStore.incrementLaunchCount() }
            runCatching {
                SettingsDataStore.fileLoggingEnabledFlow().collect { enabled ->
                    AppFileLogger.setEnabled(enabled)
                }
            }
        }
        applicationScope.launch {
            runCatching {
                SettingsDataStore.memoryEnabledFlow().collect { enabled ->
                    AgentMemoryRepository.setGloballyEnabled(enabled)
                }
            }
        }
        // 媒体语义索引自动更新：完整运行时初始化之后再跑（启动开关 + 重排定时闹钟）。
        applicationScope.launch {
            runCatching {
                io.github.mangi.eta.agent.media.index.MediaIndexAutoUpdate.onAppLaunch(this@EtaApp)
            }
        }
        val predictiveBackEnabled = runBlocking(Dispatchers.IO) {
            AppearanceSettingsRepository.settings().predictiveBackEnabled
        }
        PredictiveBackController.apply(applicationInfo, predictiveBackEnabled)
        // Restore only an explicit, device-local web pairing, never create a virtual display.
        applicationScope.launch {
            runCatching { VirtualDisplayWebPreview.restore(this@EtaApp) }
                .onFailure { throwable ->
                    AndroidAgentLogger.warn("Virtual display web preview restore failed: type=${throwable.safeLogType()}")
                }
        }
        XposedServiceHelper.registerListener(this)
        applicationScope.launch {
            LinuxEnvironmentSettingsRepository.initialize(this@EtaApp)
            runCatching {
                SkillRuntime.createIndexService(this@EtaApp).listInstalledSkills()
            }.onFailure { throwable ->
                AndroidAgentLogger.warn(
                    "Agent skill index prewarm failed: type=${throwable.safeLogType()}"
                )
            }
        }
    }

    override fun onServiceBind(service: XposedService) {
        serviceInstance = service
        Prefs.reconcileAgentPreferences(service)
        dispatch(service)
    }

    override fun onServiceDied(service: XposedService) {
        // 只有当前持有的 service 死亡时才清空并派发 null；
        // 多 framework 场景下死掉的可能是已被替换的旧实例，无需影响 UI。
        if (serviceInstance === service) {
            serviceInstance = null
            dispatch(null)
        }
    }

    companion object {
        @Volatile
        var serviceInstance: XposedService? = null
            private set

        private val listeners = CopyOnWriteArraySet<ServiceStateListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        fun addServiceStateListener(listener: ServiceStateListener, notifyImmediately: Boolean) {
            listeners.add(listener)
            if (notifyImmediately) {
                dispatchTo(listener, serviceInstance)
            }
        }

        fun removeServiceStateListener(listener: ServiceStateListener) {
            listeners.remove(listener)
        }

        private fun dispatch(service: XposedService?) {
            listeners.forEach { dispatchTo(it, service) }
        }

        private fun dispatchTo(listener: ServiceStateListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceStateChanged(service)
            } else {
                mainHandler.post {
                    if (listeners.contains(listener)) {
                        listener.onServiceStateChanged(service)
                    }
                }
            }
        }
    }
}
