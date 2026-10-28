package io.github.mangi.eta.ui

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Psychology
import io.github.mangi.eta.ui.icons.SubAgents
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AppFileLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AppUpdateRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.EnhancementSettingsHistory
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import io.github.mangi.eta.ui.components.streamDiagnosticMeasure
import io.github.mangi.eta.ui.components.streamDiagnosticPlacement
import io.github.mangi.eta.ui.components.streamDiagnosticDraw
import io.github.mangi.eta.ui.components.settingsSectionDiagnostics
import io.github.mangi.eta.ui.components.WithoutPressRipple
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.navigation.AppRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.mangi.eta.ui.components.LanguagePreference
import io.github.mangi.eta.ui.components.TrashRetentionPreference
import io.github.mangi.eta.ui.components.SwitchPreference
import androidx.compose.material3.Text as M3Text
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
import androidx.compose.material3.Text as M3StatusText
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.MaterialTheme as M3MaterialTheme

/**
 * 模块配置界面。
 *
 * 开关默认值由 [Prefs.Keys.BOOLEAN_DEFAULTS] 统一定义。Eta Runtime 自己消费的开关写入
 * App 本地配置；仅 Hook 消费的开关通过 RemotePreferences 提交到 LSPosed。
 */
@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    currentProviderId: String? = null,
    currentModelId: String? = null,
) {
    // Read only attach/detach generation so idle sections rebuild observer identities.
    StreamPerformanceDiagnostics.sessionGeneration.longValue
    // Counts only successfully applied compositions; never writes snapshot state.
    SideEffect {
        if (StreamPerformanceDiagnostics.enabled) {
            StreamPerformanceDiagnostics.record("settings.composition")
        }
    }
    val coroutineScope = rememberCoroutineScope()
    val taskBackendInstalled = rememberTaskBackendInstalled()
    val enhancementHistory = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    var hasConnectedFramework by remember { mutableStateOf(enhancementHistory.hasConnected) }
    val appSettings by remember { SettingsDataStore.settingsFlow() }.collectAsState(
        initial = io.github.mangi.eta.data.model.Settings(),
    )
    var exportingLogs by remember { mutableStateOf(false) }
    var clearingLogs by remember { mutableStateOf(false) }
    var showClearLogsDialog by remember { mutableStateOf(false) }
    val exportLogsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            exportingLogs = true
            try {
                val output = withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)
                        ?: error(context.getString(R.string.settings_export_logs_open_failed))
                }
                val count = withContext(Dispatchers.IO) {
                    output.use { AppFileLogger.export(it) }
                }
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_export_logs_success, count),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                Toast.makeText(
                    context.applicationContext,
                    throwable.message?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.settings_export_logs_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                exportingLogs = false
            }
        }
    }

    // 强制无障碍开关状态：从系统设置返回时（ON_RESUME）刷新。
    var accessibilityProtectionEnabled by remember {
        mutableStateOf(StreamPerformanceDiagnostics.measure("settings.permission.protection.initial") { AccessibilityProtectionClient.isEnabled(context) })
    }
    var accessibilityProtectionPending by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityProtectionEnabled =
                    StreamPerformanceDiagnostics.measure("settings.permission.protection.resume") { AccessibilityProtectionClient.isEnabled(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    val initialPreferences = remember {
        val service = EtaApp.serviceInstance
        val initialPrefs = StreamPerformanceDiagnostics.measure("settings.prefs.initial") {
            Prefs.remotePreferencesForUi(service)
        }
        InitialServicePreferenceSnapshot(service, initialPrefs)
    }
    var prefs by remember { mutableStateOf(initialPreferences.initialValueForUi()) }
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    DisposableEffect(Unit) {
        // Only the immediate notification may reuse the successful initial read.
        // A failed initial read retries, and every subsequent service event refreshes.
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = initialPreferences.resolve(service) { currentService ->
                    StreamPerformanceDiagnostics.measure("settings.prefs.refresh") {
                        Prefs.remotePreferencesForUi(currentService)
                    }
                }
                prefs?.let { connected ->
                    StreamPerformanceDiagnostics.measure("settings.prefs.capture") {
                        enhancementHistory.captureConnected(connected)
                    }
                    hasConnectedFramework = true
                }
                StreamPerformanceDiagnostics.measure("settings.prefs.reconcile") {
                    Prefs.reconcileAgentPreferences(service)
                }
                coroutineScope.launch {
                    RuntimeConfigRepository.ensureDefaults(service)
                }
            }
        }
        StreamPerformanceDiagnostics.measure("settings.service.subscribe") {
            EtaApp.addServiceStateListener(listener, notifyImmediately = true)
        }
        onDispose { EtaApp.removeServiceStateListener(listener) }
    }

    WithoutPressRipple {
    EtaScaffoldPage(
        title = stringResource(R.string.ui_set_up_7debf9),
        onBack = onBack,
        modifier = Modifier
            .streamDiagnosticMeasure("settings.root.measure")
            .streamDiagnosticDraw("settings.root.draw"),
        topBarModifier = Modifier.streamDiagnosticMeasure("settings.topbar.measure"),
        listModifier = Modifier.streamDiagnosticMeasure("settings.lazy.measure").streamDiagnosticPlacement("settings.lazy.place").streamDiagnosticDraw("settings.lazy.draw"),
        actions = {
            // 搜索入口：大标题栏只保留这一个单搜索图标，点击进独立搜索页。
            M3IconButton(onClick = { onNavigate(AppRoute.SettingsSearch) }) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.settings_search_hint),
                )
            }
        },
    ) {
            // ── LLM 提供商 ──────────────────────────────────────────────
            settingsGroup(
                title = { stringResource(R.string.settings_llm_providers) },
                sectionModifier = Modifier.settingsSectionDiagnostics("agent"),
            ) {
                row {
                    SettingsProviderEntry(currentProviderId, currentModelId) {
                        onNavigate(AppRoute.ModelProviders)
                    }
                }
                row {
                    EtaClickableRow(
                        title = stringResource(R.string.mobile_actions_settings_title),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.TouchApp,
                            )
                        },
                        onClick = { onNavigate(AppRoute.MobileActionsSettings) },
                    )
                }
            }

            settingsGroup(
                title = { stringResource(R.string.settings_model_features) },
                sectionModifier = Modifier.settingsSectionDiagnostics("model_features"),
            ) {
                row {
                    EtaClickableRow(
                        title = stringResource(R.string.route_context_compression),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Compress,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ContextCompression) },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.auxiliary_vision_title),
                        leading = { PreferenceIcon(icon = Icons.Rounded.RemoveRedEye) },
                        onClick = { onNavigate(AppRoute.AuxiliaryVision) },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.title_model_title),
                        leading = { PreferenceIcon(icon = Icons.Rounded.AutoAwesome) },
                        onClick = { onNavigate(AppRoute.TitleModel) },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.error_reconnect_title),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Refresh) },
                        onClick = { onNavigate(AppRoute.ErrorReconnectSettings) },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.web_tools_settings_title),
                        leading = { PreferenceIcon(icon = Icons.Rounded.TravelExplore) },
                        onClick = { onNavigate(AppRoute.WebToolsSettings) },
                    )
                }

                row {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.ui_deep_thinking_enabled_by_default_c032d6),
                        key = Prefs.Keys.AGENT_THINKING_ENABLED,
                        icon = Icons.Rounded.Psychology,
                    )
                }
            }

            // ── 扩展 ────────────────────────────────────────────
            settingsGroup(
                title = { stringResource(R.string.settings_context_extensions) },
                sectionModifier = Modifier.settingsSectionDiagnostics("context_extensions"),
            ) {
                row {
                    EtaClickableRow(
                        title = stringResource(R.string.settings_assistants),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.SmartToy,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Assistants()) },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.ui_memory_b55ff5),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Psychology) },
                        onClick = { onNavigate(AppRoute.Memory) },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.route_skills),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Extension,
                            )
                        },
                        onClick = { onNavigate(AppRoute.Skills) },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.route_mcp_servers),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.AccountTree,
                            )
                        },
                        onClick = { onNavigate(AppRoute.McpServers) },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.settings_tool_recipes),
                        summary = stringResource(R.string.settings_tool_recipes_summary),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Handyman,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ToolRecipes) },
                    )
                }

                row {
                    EtaClickableRow(title = stringResource(R.string.settings_subagent_title),
                        leading = { PreferenceIcon(icon = SubAgents) },
                        onClick = { onNavigate(AppRoute.SubAgents) })
                }
            }

            // ── 通用 ────────────────────────────────────────────────────
            settingsGroup(
                title = { stringResource(R.string.settings_general) },
                sectionModifier = Modifier.settingsSectionDiagnostics("general"),
            ) {
                row {
                    EtaClickableRow(
                        title = stringResource(R.string.voice_title),
                        leading = { PreferenceIcon(icon = Icons.Rounded.RecordVoiceOver) },
                        onClick = { onNavigate(AppRoute.VoiceSettings) },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.appearance_title),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Palette,
                            )
                        },
                        onClick = { onNavigate(AppRoute.AppearanceSettings) },
                    )
                }

                row {
                    LanguagePreference()
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.stats_page_title),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.BarChart,
                            )
                        },
                        onClick = { onNavigate(AppRoute.UsageStats) },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.data_backup_title),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Description,
                            )
                        },
                        onClick = { onNavigate(AppRoute.DataBackup) },
                    )
                }

                row {
                    TrashRetentionPreference()
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.haptics_list),
                        leading = { PreferenceIcon(Icons.Rounded.Vibration) },
                        onClick = { onNavigate(AppRoute.Haptics) },
                    )
                }
            }

            // ── Agent 工具能力 ──────────────────────────────────────────
            settingsGroup(
                title = { stringResource(R.string.settings_tool_capabilities) },
                sectionModifier = Modifier.settingsSectionDiagnostics("tools"),
            ) {
                row {
                    EtaClickableRow(
                        title = stringResource(R.string.settings_tools_list),
                        leading = { PreferenceIcon(Icons.Rounded.Dashboard) },
                        onClick = { onNavigate(AppRoute.Tools) },
                    )
                }

                if (taskBackendInstalled == true) {
                    row {
                        EtaClickableRow(
                            title = stringResource(R.string.agent_task_surface_title),
                            leading = { PreferenceIcon(Icons.Rounded.Layers) },
                            onClick = { onNavigate(AppRoute.AgentTaskPreference) },
                        )
                    }
                }

                // 七个分组开关与模型侧的延迟工具组、工具清单页的分节是同一份定义
                // （AgentToolGroups）。关掉一组＝这组工具既不下发也不执行，
                // 单个工具的取舍在「工具清单」页里做。
                row {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_basic_tools),
                        summary = stringResource(R.string.settings_basic_tools_summary),
                        key = Prefs.Keys.AGENT_BASIC_TOOLS,
                        icon = Icons.Rounded.Forum,
                    )
                }

                row {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_screen_tools),
                        summary = stringResource(R.string.settings_screen_tools_summary),
                        key = Prefs.Keys.AGENT_SCREEN_TOOLS,
                        icon = Icons.Rounded.TouchApp,
                    )
                }

                row {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_web_tools),
                        summary = stringResource(R.string.settings_web_tools_summary),
                        key = Prefs.Keys.AGENT_BROWSER_TOOLS,
                        icon = Icons.Rounded.Language,
                    )
                }

                row {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_terminal_tools),
                        summary = stringResource(R.string.settings_terminal_tools_summary),
                        key = Prefs.Keys.AGENT_TERMINAL_TOOLS,
                        icon = Icons.Rounded.Terminal,
                    )
                }

                row {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_shell_tools),
                        summary = stringResource(R.string.settings_shell_tools_summary),
                        key = Prefs.Keys.AGENT_SHELL_TOOLS,
                        icon = Icons.Rounded.Terminal,
                    )
                }

                row {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_device_tools),
                        summary = stringResource(R.string.settings_device_tools_summary),
                        key = Prefs.Keys.AGENT_DEVICE_TOOLS,
                        icon = Icons.Rounded.Smartphone,
                    )
                }

                row {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_personal_tools),
                        summary = stringResource(R.string.settings_personal_tools_summary),
                        key = Prefs.Keys.AGENT_PERSONAL_TOOLS,
                        icon = Icons.Rounded.Badge,
                    )
                }

                row {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_extensions_tools),
                        summary = stringResource(R.string.settings_extensions_tools_summary),
                        key = Prefs.Keys.AGENT_EXTENSIONS_TOOLS,
                        icon = Icons.Rounded.Extension,
                    )
                }

                row {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_confirm_dangerous_tools),
                        summary = stringResource(R.string.settings_confirm_dangerous_tools_summary),
                        key = Prefs.Keys.AGENT_CONFIRM_DANGEROUS_TOOLS,
                        icon = Icons.Rounded.Warning,
                    )
                }

                row {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_subagent_tools),
                        summary = stringResource(R.string.settings_subagent_tools_summary),
                        key = Prefs.Keys.AGENT_SUBAGENT_TOOLS,
                        icon = Icons.Rounded.Groups,
                    )
                }

                row {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_media_search_tools),
                        summary = stringResource(R.string.settings_media_search_tools_summary),
                        key = Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS,
                        icon = Icons.Rounded.TravelExplore,
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.media_settings_entry_title),
                        summary = stringResource(R.string.media_settings_entry_summary),
                        leading = { PreferenceIcon(Icons.Rounded.TravelExplore) },
                        onClick = { onNavigate(AppRoute.MediaIndexSettings) },
                    )
                }
            }

            // ── 运行环境 ────────────────────────────────────────────────
            settingsGroup(
                title = { stringResource(R.string.settings_runtime_environment) },
                sectionModifier = Modifier.settingsSectionDiagnostics("runtime"),
            ) {
                row {
                    EtaClickableRow(
                        title = stringResource(R.string.ui_linux_tool_environment_314d22),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Inventory2,
                            )
                        },
                        onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.capability_workspace),
                        summary = stringResource(R.string.capability_workspace_summary),
                        leading = { PreferenceIcon(Icons.Rounded.Folder) },
                        onClick = { onNavigate(AppRoute.Workspace) },
                    )
                }
            }

            // ── 系统助手接管 ──────────────────────────────────────────────
            settingsGroup(
                title = { stringResource(R.string.settings_system_takeover) },
                sectionModifier = Modifier.settingsSectionDiagnostics("system_takeover"),
            ) {
                row {
                    EtaClickableRow(
                        title = stringResource(R.string.ui_system_assistant_takes_over_f46043),
                        summary = stringResource(R.string.settings_system_takeover_summary),
                        leading = { PreferenceIcon(Icons.Rounded.PowerSettingsNew) },
                        onClick = { onNavigate(AppRoute.SystemTakeover) },
                    )
                }
            }

            settingsGroup(
                title = { stringResource(R.string.settings_diagnostics) },
                sectionModifier = Modifier.settingsSectionDiagnostics("diagnostics"),
            ) {
                row {
                    SwitchPreference(
                        title = stringResource(R.string.settings_file_logging),
                        summary = stringResource(R.string.settings_file_logging_summary),
                        checked = appSettings.fileLoggingEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                SettingsDataStore.setFileLoggingEnabled(enabled)
                            }
                        },
                        startAction = {
                            PreferenceIcon(icon = Icons.Rounded.BugReport)
                        },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.settings_export_logs),
                        summary = if (exportingLogs) {
                            stringResource(R.string.settings_export_logs_working)
                        } else {
                            null
                        },
                        enabled = !exportingLogs && !clearingLogs,
                        leading = {
                            PreferenceIcon(icon = Icons.Rounded.Share)
                        },
                        onClick = {
                            if (exportingLogs || clearingLogs) return@EtaClickableRow
                            if (!AppFileLogger.hasLogs()) {
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.settings_export_logs_empty),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@EtaClickableRow
                            }
                            exportLogsLauncher.launch(defaultDiagnosticLogFileName(context))
                        },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.settings_clear_logs),
                        enabled = !exportingLogs && !clearingLogs,
                        leading = {
                            PreferenceIcon(icon = Icons.Rounded.DeleteSweep)
                        },
                        onClick = {
                            if (exportingLogs || clearingLogs) return@EtaClickableRow
                            showClearLogsDialog = true
                        },
                    )
                }
            }

            // ── 权限 ────────────────────────────────────────────────────
            settingsGroup(
                title = { stringResource(R.string.ui_permissions_560165) },
                sectionModifier = Modifier.settingsSectionDiagnostics("permissions"),
            ) {
                row {
                    EtaClickableRow(
                        title = stringResource(R.string.ui_permission_health_3048bb),
                        leading = {
                            PreferenceIcon(icon = Icons.Rounded.HealthAndSafety)
                        },
                        onClick = { onNavigate(AppRoute.Permissions) },
                    )
                }

                row {
                    EtaClickableRow(
                        title = stringResource(R.string.capability_enhancements),
                        leading = {
                            PreferenceIcon(icon = Icons.Rounded.Security)
                        },
                        onClick = { onNavigate(AppRoute.SystemEnhance) },
                    )
                }

                if (prefs != null || hasConnectedFramework) {
                    row {
                        SwitchPreference(
                            title = stringResource(R.string.ui_enforce_accessibility_55e838),
                            checked = accessibilityProtectionEnabled,
                            onCheckedChange = { enabled ->
                                if (accessibilityProtectionPending) {
                                    return@SwitchPreference
                                }
                                accessibilityProtectionPending = true
                                AccessibilityProtectionClient.setEnabled(
                                    context = context,
                                    enabled = enabled,
                                ) { result ->
                                    accessibilityProtectionPending = false
                                    accessibilityProtectionEnabled = result.enabled
                                    val failureMessage = when (result.status) {
                                        AccessibilityProtectionClient.ControlStatus.APPLIED -> null
                                        AccessibilityProtectionClient.ControlStatus.UNAVAILABLE ->
                                        context.getString(R.string.accessibility_protection_unavailable)
                                        AccessibilityProtectionClient.ControlStatus.REJECTED ->
                                        context.getString(R.string.accessibility_protection_rejected)
                                    }
                                    if (failureMessage != null) {
                                        Toast.makeText(
                                            context.applicationContext,
                                            failureMessage,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            },
                            startAction = {
                                PreferenceIcon(
                                    icon = Icons.Rounded.VerifiedUser,
                                    enabled = prefs != null && !accessibilityProtectionPending,
                                )
                            },
                            enabled = prefs != null && !accessibilityProtectionPending,
                        )
                    }
                }
            }

            // ── 关于（固定收尾，保持整页最后一组）─────────────────────────
            settingsGroup(
                title = { stringResource(R.string.ui_about_bed172) },
                sectionModifier = Modifier,
            ) {
                row {
                    val versionName = remember { AppUpdateRepository.currentVersionName(context) }
                    EtaClickableRow(
                        title = stringResource(R.string.app_name),
                        summary = stringResource(
                            R.string.update_current_version,
                            versionName.ifBlank { stringResource(R.string.update_unknown_version) },
                        ),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Info,
                            )
                        },
                        onClick = { onNavigate(AppRoute.About) },
                    )
                }
            }
        }
    }

        if (showClearLogsDialog) {
            EtaDialog(
                show = true,
                title = stringResource(R.string.settings_clear_logs_confirm_title),
                summary = stringResource(R.string.settings_clear_logs_confirm_summary),
                onDismissRequest = {
                    if (!clearingLogs) showClearLogsDialog = false
                },
                actions = {
                    EtaDialogActions(
                        confirmText = if (clearingLogs) {
                            stringResource(R.string.settings_export_logs_working)
                        } else {
                            stringResource(R.string.settings_clear_logs)
                        },
                        destructive = true,
                        cancelEnabled = !clearingLogs,
                        confirmEnabled = !clearingLogs,
                        onCancel = { showClearLogsDialog = false },
                        onConfirm = {
                            if (clearingLogs) return@EtaDialogActions
                            clearingLogs = true
                            coroutineScope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        AppFileLogger.clear()
                                        io.github.mangi.eta.agent.voice.doubao.DoubaoDiagnostics.clear()
                                    }
                                    Toast.makeText(
                                        context.applicationContext,
                                        context.getString(R.string.settings_clear_logs_done),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    showClearLogsDialog = false
                                } catch (throwable: Throwable) {
                                    if (throwable is CancellationException) throw throwable
                                    Toast.makeText(
                                        context.applicationContext,
                                        throwable.message?.takeIf { it.isNotBlank() }
                                            ?: context.getString(R.string.settings_export_logs_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } finally {
                                    clearingLogs = false
                                }
                            }
                        },
                    )
                },
            ) {
            }
        }
}

// ── 逐行懒加载的设置分组 ─────────────────────────────────────────────────────

/** [settingsGroup] 的行作用域：注册进来的每一行都会成为独立的一条 LazyColumn item。 */
internal class SettingsGroupRows {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    /** 注册一行。不需要显示的行不要调用本方法，组内 index/count 会自动跳过它。 */
    fun row(content: @Composable () -> Unit) {
        rows.add(content)
    }
}

/**
 * 逐行懒加载的设置分组：组标题一条 item，组内每行各一条 item。
 *
 * 旧写法把整组塞进单条 `item { EtaSegmentedColumn(…) }`：LazyColumn 的复用单位是 item，
 * 一个 9 行分组进入视口时要一次性组合并测量整组，fling 每到一组就掉一次帧；而且 DSL 版
 * 分段列会给每行挂 spring 显隐动画、3 个 `animateDpAsState` 与 `graphicsLayer` 裁剪层，
 * 静态设置页根本没有会隐藏的行，这些开销是逐行的纯浪费。拆成逐行 item 之后，每帧只组合
 * 滚动新露出的那一两行；行形状由 [EtaSegmentedItem] 按组内 index/count 注入，与原先
 * 动画收敛后的静态结果一致，不再有任何动画状态与图层。
 *
 * 间距沿用分段列表令牌：组标题下 8dp、行间 2dp、组末 13dp、左右页边距 16dp；
 * `sectionModifier` 由调用方传入（`Modifier.settingsSectionDiagnostics("…")`），逐行采集
 * settings.section 边界，保持帧诊断契约要求的 9 个字面量标签。
 */
internal fun LazyListScope.settingsGroup(
    title: @Composable () -> String,
    sectionModifier: Modifier,
    content: SettingsGroupRows.() -> Unit,
) {
    val rows = SettingsGroupRows().apply(content).rows
    if (rows.isEmpty()) return
    item {
        M3Text(
            text = title(),
            style = M3MaterialTheme.typography.titleSmallEmphasized,
            color = M3MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(
                start = EtaGroupHorizontalPadding,
                end = EtaGroupHorizontalPadding,
                bottom = EtaSegmentedTitleSpacing,
            ),
        )
    }
    rows.forEachIndexed { index, rowContent ->
        item {
            EtaSegmentedItem(index = index, count = rows.size) {
                Column(
                    modifier = sectionModifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = if (index == rows.lastIndex) EtaSegmentedGroupSpacing else EtaSegmentedGap,
                    ),
                ) {
                    rowContent()
                }
            }
        }
    }
}

// ── 带图标的布尔开关 ─────────────────────────────────────────────────────────

/**
 * 单个布尔开关：状态随 [prefs]/[key] 变化重读，切换时同步写入。
 *
 * 配置来源由调用方按能力边界传入。Hook 开关仍可能因 LSPosed 未连接而禁用；Agent
 * Runtime 开关始终使用 App 本地配置。
 */
@Composable
internal fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: ImageVector,
) {
    val enabled = prefs != null
    val history = remember(context.applicationContext) { EnhancementSettingsHistory(context) }
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: history.checked(key, default))
    }
    DisposableEffect(prefs, key) {
        val targetPrefs = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changedPrefs, changedKey ->
            if (changedKey == key) {
                checked = changedPrefs.getBoolean(key, default)
            }
        }
        targetPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { targetPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = { value ->
            // 同步提交；RemotePreferences.commit() 失败（binder 提交失败）时回滚 UI 状态，
            // 避免 UI 显示已切换而 hook 进程实际未收到。
            val targetPrefs = prefs ?: return@SwitchPreference
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
                history.recordCommittedBoolean(key, value)
                if (key in Prefs.Keys.LOCAL_AGENT_KEYS) {
                    Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
                }
            } else {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        startAction = {
            PreferenceIcon(icon = icon, enabled = enabled)
        },
        enabled = enabled,
    )
}

/**
 * 同步写入布尔值。RemotePreferences 的 [commit] 先更新本进程 map 再同步等待 binder 提交，
 * 失败（binder RemoteException）返回 false 但本进程 map 已被改写——此时 hook 进程收不到新值。
 * 返回是否提交成功，供调用方决定是否更新 UI。
 */
// ── 行尾状态文字（授权 / 启用 / 链接名等）：按语义取官方配色 ─────────────────────

@Composable
internal fun SettingsStatusText(text: String, error: Boolean = false, emphasized: Boolean = false) {
    M3StatusText(
        text = text,
        style = M3MaterialTheme.typography.bodyMedium,
        color = when {
            error -> M3MaterialTheme.colorScheme.error
            emphasized -> M3MaterialTheme.colorScheme.primary
            else -> M3MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

internal fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

internal fun putStringSync(
    prefs: SharedPreferences,
    key: String,
    value: String
): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

internal fun PowerAssistantTarget.displayName(context: Context): String =
    when (this) {
        PowerAssistantTarget.OEM -> context.getString(R.string.power_assistant_system_default)
        PowerAssistantTarget.GEMINI -> "Gemini"
        PowerAssistantTarget.ETA -> "Eta"
    }

private fun defaultDiagnosticLogFileName(context: Context): String =
    context.getString(R.string.settings_diag_log_file_name, SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()))

@Composable
private fun SettingsProviderEntry(
    currentProviderId: String?,
    currentModelId: String?,
    onClick: () -> Unit,
) {
    // Provider / Model 选中状态展示
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val storedProviderId by remember { RuntimeConfigRepository.selectedProviderIdFlow() }
        .collectAsState(initial = null)
    val storedModelId by remember { RuntimeConfigRepository.selectedModelIdFlow() }
        .collectAsState(initial = null)
    val selectedProviderId = currentProviderId?.takeIf { it.isNotBlank() } ?: storedProviderId
    val selectedModelId = currentModelId?.takeIf { it.isNotBlank() } ?: storedModelId
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = selectedProvider?.let { provider ->
        "${provider.name} / ${selectedModel?.displayName ?: stringResource(R.string.settings_model_not_selected)}"
    } ?: stringResource(R.string.settings_not_configured)

    EtaClickableRow(
        title = stringResource(R.string.ui_model_provider_e8c7f5),
        summary = providerSummary,
        leading = {
            PreferenceIcon(
                icon = Icons.Rounded.Memory,
            )
        },
        onClick = onClick,
    )
}
