package io.github.mangi.eta.ui

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
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
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Lan
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
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionClient
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.model.ToolCapabilities
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
import io.github.mangi.eta.ui.components.TopBarIconButton
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.mangi.eta.ui.components.LanguagePreference
import io.github.mangi.eta.ui.components.TrashRetentionPreference
import io.github.mangi.eta.ui.components.SwitchPreference
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text as M3Text
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
import io.github.mangi.eta.ui.adaptive.LocalEtaSegmentedRowContainerColor
import io.github.mangi.eta.ui.adaptive.LocalEtaSegmentedRowContentColor
import androidx.compose.material3.Text as M3StatusText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme as M3MaterialTheme

/** 搜索命中后回到主页，聚焦行高亮的持续时间。 */
private const val SETTINGS_FOCUS_HIGHLIGHT_MS = 1600L

/** 位置表还没建好时的重试等待：一帧多一点，够列表完成首次组合。 */
private const val SETTINGS_FOCUS_RETRY_DELAY_MS = 48L

/**
 * 模块配置界面。
 *
 * 开关默认值由 [Prefs.Keys.BOOLEAN_DEFAULTS] 统一定义。Eta Runtime 自己消费的开关写入
 * App 本地配置；仅 Hook 消费的开关通过 RemotePreferences 提交到 LSPosed。
 *
 * 每行都挂一个 [SettingsKeys] 锚点键（[SettingsGroupRows.row] 的 `key`），搜索命中行内
 * 控件（开关 / 选择器 / 一次性动作，没有自己的子页）时由 [focusKey] 滚到那一行并短暂
 * 高亮——用户不必自己在长列表里找。
 */
@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    currentProviderId: String? = null,
    currentModelId: String? = null,
    focusKey: String? = null,
    onFocusConsumed: () -> Unit = {},
    /**
     * 宽屏 list-detail 左栏的持久选中行（[SettingsKeys] 锚点键）。
     *
     * 手机端不传，页面与原来逐字一致；宽屏传右栏当前二级页对应的行，选中态一直亮着，
     * 三级/四级页跳转也不改变它。与 [focusKey] 的短暂高亮是两套独立状态。
     */
    selectedKey: String? = null,
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
    val listState = rememberLazyListState()
    val anchors = remember { SettingsAnchorIndex() }
    var highlightedKey by remember { mutableStateOf<String?>(null) }
    // 审查档位提升到列表构建层：智能档专属的引擎/超时两行随切换即时显隐
    // （ToolReviewModeRow 与这里的 if 读写同一份 state）。
    var toolReviewMode by remember { mutableStateOf(Prefs.toolReviewMode()) }
    LaunchedEffect(focusKey) {
        val key = focusKey ?: return@LaunchedEffect
        try {
            // 位置表与本次 composition 同步构建，但首次进页偶尔赶在列表摆好之前：
            // 取不到就等一帧再试一次，取不到也不影响高亮。
            var index = anchors.positionOf(key)
            if (index == null) {
                delay(SETTINGS_FOCUS_RETRY_DELAY_MS)
                index = anchors.positionOf(key)
            }
            index?.let { listState.animateScrollToItem(it) }
            highlightedKey = key
            delay(SETTINGS_FOCUS_HIGHLIGHT_MS)
        } finally {
            // 页面提前离开（协程取消）也要收掉高亮并交还焦点，焦点键不会残留到下次进页。
            highlightedKey = null
            onFocusConsumed()
        }
    }
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
        listState = listState,
        listModifier = Modifier.streamDiagnosticMeasure("settings.lazy.measure").streamDiagnosticPlacement("settings.lazy.place").streamDiagnosticDraw("settings.lazy.draw"),
        actions = {
            // 搜索入口：大标题栏只保留这一个单搜索图标，点击进独立搜索页。
            TopBarIconButton(
                icon = Icons.Rounded.Search,
                contentDescription = stringResource(R.string.settings_search_hint),
                onClick = { onNavigate(AppRoute.SettingsSearch) },
                shapes = IconButtonDefaults.shapes(),
            )
        },
    ) {
            anchors.reset()
            // ── LLM 提供商 ──────────────────────────────────────────────
            settingsGroup(
                title = { stringResource(R.string.settings_llm_providers) },
                sectionModifier = Modifier.settingsSectionDiagnostics("agent"),
                anchors = anchors,
                highlightedKey = highlightedKey,
                selectedKey = selectedKey,
            ) {
                row(key = SettingsKeys.MODEL_PROVIDER) {
                    SettingsProviderEntry(currentProviderId, currentModelId) {
                        onNavigate(AppRoute.ModelProviders)
                    }
                }
                row(key = SettingsKeys.MOBILE_ACTIONS) {
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
                anchors = anchors,
                highlightedKey = highlightedKey,
                selectedKey = selectedKey,
            ) {
                row(key = SettingsKeys.CONTEXT_COMPRESSION) {
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

                row(key = SettingsKeys.DECISION_MODEL) {
                    EtaClickableRow(
                        title = stringResource(R.string.route_decision_model),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Psychology) },
                        onClick = { onNavigate(AppRoute.DecisionModel) },
                    )
                }

                row(key = SettingsKeys.AUXILIARY_VISION) {
                    EtaClickableRow(
                        title = stringResource(R.string.auxiliary_vision_title),
                        leading = { PreferenceIcon(icon = Icons.Rounded.RemoveRedEye) },
                        onClick = { onNavigate(AppRoute.AuxiliaryVision) },
                    )
                }

                row(key = SettingsKeys.TITLE_MODEL) {
                    EtaClickableRow(
                        title = stringResource(R.string.title_model_title),
                        leading = { PreferenceIcon(icon = Icons.Rounded.AutoAwesome) },
                        onClick = { onNavigate(AppRoute.TitleModel) },
                    )
                }

                row(key = SettingsKeys.ERROR_RECONNECT) {
                    EtaClickableRow(
                        title = stringResource(R.string.error_reconnect_title),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Refresh) },
                        onClick = { onNavigate(AppRoute.ErrorReconnectSettings) },
                    )
                }

                row(key = SettingsKeys.WEB_TOOLS_SETTINGS) {
                    EtaClickableRow(
                        title = stringResource(R.string.web_tools_settings_title),
                        leading = { PreferenceIcon(icon = Icons.Rounded.TravelExplore) },
                        onClick = { onNavigate(AppRoute.WebToolsSettings) },
                    )
                }

                row(key = SettingsKeys.THINKING_DEFAULT) {
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
                anchors = anchors,
                highlightedKey = highlightedKey,
                selectedKey = selectedKey,
            ) {
                row(key = SettingsKeys.ASSISTANTS) {
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

                row(key = SettingsKeys.MEMORY) {
                    EtaClickableRow(
                        title = stringResource(R.string.ui_memory_b55ff5),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Psychology) },
                        onClick = { onNavigate(AppRoute.Memory) },
                    )
                }

                row(key = SettingsKeys.SKILLS) {
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

                row(key = SettingsKeys.MCP_SERVERS) {
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

                row(key = SettingsKeys.TOOL_RECIPES) {
                    EtaClickableRow(
                        title = stringResource(R.string.settings_tool_recipes),
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Handyman,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ToolRecipes) },
                    )
                }

                row(key = SettingsKeys.SUB_AGENTS) {
                    EtaClickableRow(title = stringResource(R.string.settings_subagent_title),
                        leading = { PreferenceIcon(icon = SubAgents) },
                        onClick = { onNavigate(AppRoute.SubAgents) })
                }

                row(key = SettingsKeys.REMOTE_AGENTS) {
                    EtaClickableRow(title = stringResource(R.string.route_remote_agents),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Lan) },
                        onClick = { onNavigate(AppRoute.RemoteAgents) })
                }
            }

            // ── 通用 ────────────────────────────────────────────────────
            settingsGroup(
                title = { stringResource(R.string.settings_general) },
                sectionModifier = Modifier.settingsSectionDiagnostics("general"),
                anchors = anchors,
                highlightedKey = highlightedKey,
                selectedKey = selectedKey,
            ) {
                row(key = SettingsKeys.VOICE) {
                    EtaClickableRow(
                        title = stringResource(R.string.voice_title),
                        leading = { PreferenceIcon(icon = Icons.Rounded.RecordVoiceOver) },
                        onClick = { onNavigate(AppRoute.VoiceSettings) },
                    )
                }

                row(key = SettingsKeys.APPEARANCE) {
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

                row(key = SettingsKeys.LANGUAGE) {
                    LanguagePreference()
                }

                row(key = SettingsKeys.USAGE_STATS) {
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

                row(key = SettingsKeys.DATA_BACKUP) {
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

                row(key = SettingsKeys.TRASH_RETENTION) {
                    TrashRetentionPreference()
                }

                row(key = SettingsKeys.HAPTICS) {
                    EtaClickableRow(
                        title = stringResource(R.string.haptics_list),
                        leading = { PreferenceIcon(Icons.Rounded.Vibration) },
                        onClick = { onNavigate(AppRoute.Haptics) },
                    )
                }
            }

            // ── Agent 工具能力 ──────────────────────────────────────────
            // 分组开关**不在这里**：它们在「工具能力」页里，一张卡一个开关。
            // 设置首页只留入口和与安全/行为有关的开关——十几个能力开关平铺在这里，
            // 用户看不出「关掉这一组会失去什么」，还得和维护两处分组的一致性打交道。
            settingsGroup(
                title = { stringResource(R.string.settings_tool_capabilities) },
                sectionModifier = Modifier.settingsSectionDiagnostics("tools"),
                anchors = anchors,
                highlightedKey = highlightedKey,
                selectedKey = selectedKey,
            ) {
                row(key = SettingsKeys.TOOLS) {
                    EtaClickableRow(
                        title = stringResource(R.string.settings_tools_list),
                        // 组开关撤走后，不进页就不知道关了什么，这里给一行实时摘要。
                        summary = toolsCapabilitySummary(context, agentPrefs),
                        leading = { PreferenceIcon(Icons.Rounded.Dashboard) },
                        onClick = { onNavigate(AppRoute.Tools) },
                    )
                }

                // 下面几项不是能力分组，而是执行行为：关掉不会让某一组工具消失，
                // 只改变「怎么执行 / 允许执行到什么程度」。因此留在设置页，
                // 与工具页的卡片开关互不重复；被它们挡住的工具行会在工具页置灰并指到这里。
                row(key = SettingsKeys.TOOL_CONFIRM_DANGEROUS) {
                    ToolReviewModeRow(
                        title = stringResource(R.string.settings_tool_review_mode),
                        mode = toolReviewMode,
                        onModeChange = { toolReviewMode = it },
                    )
                }

                // 智能审查档的两个配置：引擎（LLM / 决策模型）与单次超时。
                // 只在智能档下注册这两行；其余档位没有模型裁决，显示出来只会误导。
                // 读的是提升到页面级的 toolReviewMode state，切换档位即时联动显隐。
                if (toolReviewMode == Prefs.Keys.TOOL_REVIEW_MODE_SMART) {
                    row(key = SettingsKeys.TOOL_REVIEW_ENGINE) {
                        ToolReviewEngineRow(
                            title = stringResource(R.string.settings_tool_review_engine_title),
                            summary = stringResource(R.string.settings_tool_review_engine_summary),
                        )
                    }

                    row(key = SettingsKeys.TOOL_REVIEW_TIMEOUT) {
                        ToolReviewTimeoutRow(
                            title = stringResource(R.string.settings_tool_review_timeout_title),
                        )
                    }
                }

                // 渐进加载与 MCP 暴露原本在工具页底部；它们同样是「怎么执行」的执行策略，
                // 与审查档位紧邻，放在主页这一组里才不用为了改一个开关再进子页。
                row(key = SettingsKeys.TOOL_LOADING_MODE) {
                    ToolLoadingModeRow(
                        title = stringResource(R.string.tools_loading_mode_title),
                    )
                }

                row(key = SettingsKeys.MCP_TOOL_EXPOSURE) {
                    // 老版本存的是字符串键（deferred / always）。这里先走一次读时迁移，
                    // 让下面 SwitchPref 首次读布尔新键时拿到的就是迁移后的真实值，
                    // 而不是「新键缺失 → 默认 true」。apply() 会同步更新内存副本。
                    remember { Prefs.mcpToolExposureDeferred() }
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.tools_mcp_deferred_title),
                        summary = stringResource(R.string.tools_mcp_deferred_summary),
                        summaryOff = stringResource(R.string.tools_mcp_deferred_summary_off),
                        key = Prefs.Keys.AGENT_MCP_EXPOSURE_DEFERRED,
                        icon = Icons.Rounded.Dns,
                    )
                }

                // Shell 与审查/加载策略同属「怎么执行」，但排在这两项之后：
                // 先定工具目录与暴露方式，再决定命令执行能力。
                row(key = SettingsKeys.TOOL_SHELL) {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_shell_tools),
                        summary = stringResource(R.string.settings_shell_tools_summary),
                        summaryOff = stringResource(R.string.settings_shell_tools_summary_off),
                        key = Prefs.Keys.AGENT_SHELL_TOOLS,
                        icon = Icons.Rounded.Code,
                    )
                }

                row(key = SettingsKeys.TOOL_SUBAGENT) {
                    SwitchPref(
                        context = context,
                        prefs = agentPrefs,
                        title = stringResource(R.string.settings_subagent_tools),
                        summary = stringResource(R.string.settings_subagent_tools_summary),
                        key = Prefs.Keys.AGENT_SUBAGENT_TOOLS,
                        icon = Icons.Rounded.Groups,
                    )
                }

                row(key = SettingsKeys.MEDIA_INDEX) {
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
                anchors = anchors,
                highlightedKey = highlightedKey,
                selectedKey = selectedKey,
            ) {
                // 入口始终可见：执行位置与虚拟屏视窗走进程内 VirtualDisplayController，
                // 只依赖 root/LSPosed，不再要求 Magisk 后端模块；模块仅决定网页预览与副屏恢复是否可用。
                row(key = SettingsKeys.AGENT_TASK_PREFERENCE) {
                    EtaClickableRow(
                        title = stringResource(R.string.agent_task_surface_title),
                        summary = stringResource(
                            AgentTaskSurface.settingsSummaryRes(AgentTaskSurface.stored()),
                        ),
                        leading = { PreferenceIcon(Icons.Rounded.DesktopWindows) },
                        onClick = { onNavigate(AppRoute.AgentTaskPreference) },
                    )
                }

                row(key = SettingsKeys.LINUX_ENVIRONMENT) {
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

                row(key = SettingsKeys.WORKSPACE) {
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
                anchors = anchors,
                highlightedKey = highlightedKey,
                selectedKey = selectedKey,
            ) {
                row(key = SettingsKeys.SYSTEM_TAKEOVER) {
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
                anchors = anchors,
                highlightedKey = highlightedKey,
                selectedKey = selectedKey,
            ) {
                row(key = SettingsKeys.FILE_LOGGING) {
                    SwitchPreference(
                        title = stringResource(R.string.settings_file_logging),
                        summary = stringResource(R.string.settings_file_logging_summary),
                        summaryOff = stringResource(R.string.settings_file_logging_summary_off),
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

                row(key = SettingsKeys.EXPORT_LOGS) {
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

                row(key = SettingsKeys.CLEAR_LOGS) {
                    EtaClickableRow(
                        title = stringResource(R.string.settings_clear_logs),
                        enabled = !exportingLogs && !clearingLogs,
                        leading = {
                            PreferenceIcon(icon = Icons.Rounded.DeleteSweep, destructive = true)
                        },
                        onClick = {
                            if (exportingLogs || clearingLogs) return@EtaClickableRow
                            showClearLogsDialog = true
                        },
                        destructive = true,
                    )
                }
            }

            // ── 权限 ────────────────────────────────────────────────────
            settingsGroup(
                title = { stringResource(R.string.ui_permissions_560165) },
                sectionModifier = Modifier.settingsSectionDiagnostics("permissions"),
                anchors = anchors,
                highlightedKey = highlightedKey,
                selectedKey = selectedKey,
            ) {
                row(key = SettingsKeys.PERMISSION_HEALTH) {
                    EtaClickableRow(
                        title = stringResource(R.string.ui_permission_health_3048bb),
                        leading = {
                            PreferenceIcon(icon = Icons.Rounded.HealthAndSafety)
                        },
                        onClick = { onNavigate(AppRoute.Permissions) },
                    )
                }

                row(key = SettingsKeys.SYSTEM_ENHANCE) {
                    EtaClickableRow(
                        title = stringResource(R.string.capability_enhancements),
                        leading = {
                            PreferenceIcon(icon = Icons.Rounded.Security)
                        },
                        onClick = { onNavigate(AppRoute.SystemEnhance) },
                    )
                }

                if (prefs != null || hasConnectedFramework) {
                    row(key = SettingsKeys.ENFORCE_ACCESSIBILITY) {
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
                anchors = anchors,
                highlightedKey = highlightedKey,
                selectedKey = selectedKey,
            ) {
                row(key = SettingsKeys.ABOUT) {
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

/**
 * 设置行在列表里的位置索引：把 [SettingsKeys] 常量映射到 LazyColumn 的 item 下标。
 *
 * 只在列表 DSL 构建阶段（不是 item 的 Composable 体内）推进游标——item 是懒加载的，
 * 真正组合的时机不确定，在那里计数会错位。每次重建列表前由调用方 [reset]，重建过程
 * 是幂等的，同一份布局得到同一份下标。
 */
internal class SettingsAnchorIndex {
    private val positions = HashMap<String, Int>()
    private var cursor = 0

    fun reset() {
        positions.clear()
        cursor = 0
    }

    /** 组标题等非行内容也占一个 item 位。 */
    fun skip() {
        cursor++
    }

    fun register(key: String?) {
        if (!key.isNullOrBlank()) positions[key] = cursor
        cursor++
    }

    fun positionOf(key: String): Int? = positions[key]
}

/** [settingsGroup] 的行作用域：注册进来的每一行都会成为独立的一条 LazyColumn item。 */
internal class SettingsGroupRows {
    internal data class Row(
        val key: String?,
        val content: @Composable () -> Unit,
    )

    internal val rows = mutableListOf<Row>()

    /**
     * 注册一行。不需要显示的行不要调用本方法，组内 index/count 会自动跳过它。
     *
     * @param key 该行的 [SettingsKeys] 锚点键；行内控件（开关 / 选择器 / 一次性动作）
     *   必须传，搜索命中后要靠它滚回这一行。有独立子页的跳转行也传，保持一致。
     */
    fun row(key: String? = null, content: @Composable () -> Unit) {
        rows.add(Row(key = key, content = content))
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
    anchors: SettingsAnchorIndex,
    highlightedKey: String?,
    /** 宽屏左栏常驻选中行；null 时（含所有手机端调用方）与原来完全一致。 */
    selectedKey: String? = null,
    content: SettingsGroupRows.() -> Unit,
) {
    val rows = SettingsGroupRows().apply(content).rows
    if (rows.isEmpty()) return
    anchors.skip()
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
    rows.forEachIndexed { index, row ->
        anchors.register(row.key)
        item {
            EtaSegmentedItem(index = index, count = rows.size) {
                Column(
                    modifier = sectionModifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = if (index == rows.lastIndex) EtaSegmentedGroupSpacing else EtaSegmentedGap,
                    ),
                ) {
                    SettingsRowHighlight(
                        highlighted = row.key != null && row.key == highlightedKey,
                        selected = row.key != null && row.key == selectedKey,
                        content = row.content,
                    )
                }
            }
        }
    }
}

/**
 * 行的容器色状态：搜索命中的短暂高亮 `secondaryContainer`、宽屏左栏的常驻选中
 * `tertiaryContainer`（与会话抽屉侧栏选中行同一套官方令牌），其余回到 `surfaceBright`。
 *
 * 容器色只在分段行自己绘制时生效（[LocalEtaSegmentedRowContainerColor]），所以高亮
 * 与行的分段形状严丝合缝，不会在外层叠出一块异色方块。亮底同时通过
 * [LocalEtaSegmentedRowContentColor] 把标题/摘要/行首图标/行尾箭头换成官方
 * `onXxxContainer`，否则深色主题的浅色前景在 `tertiaryContainer` 上几乎看不见。
 */
@Composable
private fun SettingsRowHighlight(
    highlighted: Boolean,
    selected: Boolean,
    content: @Composable () -> Unit,
) {
    val color by animateColorAsState(
        targetValue = when {
            highlighted -> M3MaterialTheme.colorScheme.secondaryContainer
            selected -> M3MaterialTheme.colorScheme.tertiaryContainer
            else -> M3MaterialTheme.colorScheme.surfaceBright
        },
        label = "settings_row_highlight",
    )
    // 容器色是动画中间色，无法反推前景色；这里显式给目标前景色，
    // 非高亮/非选中时传 null，行内容逐字沿用官方 ListItem 令牌。
    val contentColor = when {
        highlighted -> M3MaterialTheme.colorScheme.onSecondaryContainer
        selected -> M3MaterialTheme.colorScheme.onTertiaryContainer
        else -> null
    }
    CompositionLocalProvider(
        LocalEtaSegmentedRowContainerColor provides color,
        LocalEtaSegmentedRowContentColor provides contentColor,
        content = content,
    )
}

// ── 工具能力入口行的状态摘要 ────────────────────────────────────────────────

/** 影响工具能力入口摘要的全部键：7 个能力开关 + 外部闸门 + 单工具禁用集。 */
private val toolPreferenceKeys: Set<String> =
    ToolCapabilities.all.mapTo(mutableSetOf()) { it.switchKey } +
        ToolCapabilities.externalGates.mapTo(mutableSetOf()) { it.switchKey } +
        Prefs.Keys.AGENT_DISABLED_TOOLS

/**
 * 「工具能力」入口行的一行摘要。
 *
 * 分组开关全部搬进工具能力页之后，设置首页看不出「我到底关了什么」——用户得点进去逐个找。
 * 这里读同一批 Prefs 键给个结论，并挂监听实时刷新：在工具页改完开关返回，摘要立刻变。
 *
 * 措辞只陈述现状，不劝开也不劝关（与工具页、模型侧关闭说明的口径一致）。
 */
@Composable
private fun toolsCapabilitySummary(context: Context, prefs: SharedPreferences?): String {
    var revision by remember { mutableStateOf(0) }
    DisposableEffect(prefs) {
        val target = prefs ?: return@DisposableEffect onDispose {}
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in toolPreferenceKeys) revision++
        }
        target.registerOnSharedPreferenceChangeListener(listener)
        onDispose { target.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // revision 只用来让 remember 失效：真实取值一律从 Prefs 现读。
    return remember(revision, prefs) { buildToolsCapabilitySummary(context, prefs) }
}

private fun buildToolsCapabilitySummary(context: Context, prefs: SharedPreferences?): String {
    val off = ToolCapabilities.all.filter { capability ->
        val default = Prefs.Keys.BOOLEAN_DEFAULTS[capability.switchKey] ?: true
        !(prefs?.getBoolean(capability.switchKey, default)
            ?: Prefs.isEnabled(capability.switchKey))
    }
    val groups = when {
        off.isEmpty() -> context.getString(R.string.settings_tools_summary_all_enabled)
        off.size == ToolCapabilities.all.size ->
            context.getString(R.string.settings_tools_summary_all_off)
        else -> context.getString(
            R.string.settings_tools_summary_groups_off,
            off.size,
            off.joinToString("、") { context.getString(it.titleRes) },
        )
    }
    val disabledTools = Prefs.disabledTools().size
    if (disabledTools == 0) return groups
    return groups + "；" +
        context.getString(R.string.settings_tools_summary_tools_off, disabledTools)
}

// ── 工具调用审查模式（四档） ───────────────────────────────────────────────

/**
 * 审查模式选择行：只读 / 标准 / 智能审查 / 完全放行。
 *
 * 与同页「语言」一致，走标准 [EtaSegmentedDropdownRow]：点击行从按下位置弹出锚定
 * 下拉气泡菜单；选中项与尾标样式全部取组件默认值。
 * 档位由页面级 state 提升传入（[mode] / [onModeChange]）：智能档专属的引擎/超时
 * 两行跟着联动显隐。写入 Prefs 后每次工具调用实时读取（见 AgentDangerousOperationPolicy），
 * 改完立即生效，不像渐进加载那样等新会话。
 *
 * 行内摘要只描述当前档位的实际效果（跨档位的高风险提示并入同一句），
 * 各档位的一句话说明放在菜单项第二行。
 *
 * 轻触反馈由组件自己触发，不要在 [EtaSegmentedDropdownRow.onItemSelected] 里再写。
 */
@Composable
private fun ToolReviewModeRow(
    title: String,
    mode: String,
    onModeChange: (String) -> Unit,
) {
    val options = listOf(
        Prefs.Keys.TOOL_REVIEW_MODE_READONLY,
        Prefs.Keys.TOOL_REVIEW_MODE_STANDARD,
        Prefs.Keys.TOOL_REVIEW_MODE_SMART,
        Prefs.Keys.TOOL_REVIEW_MODE_OFF,
    )
    val labels = listOf(
        stringResource(R.string.settings_tool_review_mode_readonly),
        stringResource(R.string.settings_tool_review_mode_standard),
        stringResource(R.string.settings_tool_review_mode_smart),
        stringResource(R.string.settings_tool_review_mode_off),
    )
    // 当前档位的实际效果：高风险/致命操作的确认规则并进同一句，不单列脚注。
    val effects = listOf(
        stringResource(R.string.settings_tool_review_mode_readonly_effect),
        stringResource(R.string.settings_tool_review_mode_standard_effect),
        stringResource(R.string.settings_tool_review_mode_smart_effect),
        stringResource(R.string.settings_tool_review_mode_off_effect),
    )
    val hints = listOf(
        stringResource(R.string.settings_tool_review_mode_readonly_hint),
        stringResource(R.string.settings_tool_review_mode_standard_hint),
        stringResource(R.string.settings_tool_review_mode_smart_hint),
        stringResource(R.string.settings_tool_review_mode_off_hint),
    )
    val selectedIndex = options.indexOf(mode).coerceAtLeast(0)
    EtaSegmentedDropdownRow(
        title = title,
        // 审查档位是「执行策略」：与同组其余四行共用行首图标槽。
        icon = Icons.Rounded.Security,
        items = labels,
        selectedIndex = selectedIndex,
        onItemSelected = { index ->
            options.getOrNull(index)?.let { value ->
                if (mode != value) {
                    Prefs.setToolReviewMode(value)
                    onModeChange(value)
                }
            }
        },
        summary = effects[selectedIndex],
        itemSummaries = hints,
    )
}

/**
 * 智能审查档的引擎选择（LLM 审查 / 决策模型判断）。
 *
 * LLM 引擎的专用模型留给工具页/压缩模型 picker 配置；这里只选引擎与超时，
 * 未配专用模型时由 [AgentToolReviewCoordinator] 回退到当前对话模型。
 */
@Composable
private fun ToolReviewEngineRow(title: String, summary: String) {
    var engine by remember { mutableStateOf(Prefs.toolReviewEngine()) }
    val options = listOf(Prefs.Keys.REVIEW_ENGINE_LLM, Prefs.Keys.REVIEW_ENGINE_DECISION)
    val labels = listOf(
        stringResource(R.string.settings_tool_review_engine_llm),
        stringResource(R.string.settings_tool_review_engine_decision),
    )
    EtaSegmentedDropdownRow(
        title = title,
        icon = Icons.Rounded.Psychology,
        items = labels,
        selectedIndex = options.indexOf(engine).coerceAtLeast(0),
        onItemSelected = { index ->
            options.getOrNull(index)?.let { value ->
                if (engine != value) {
                    engine = value
                    Prefs.setToolReviewEngine(value)
                }
            }
        },
        description = summary,
    )
}

/** 审查模型单次调用的超时（1..20 秒）；改动立即生效，下一次调用即用新值。 */
@Composable
private fun ToolReviewTimeoutRow(title: String) {
    var timeoutMs by remember { mutableStateOf(Prefs.toolReviewTimeoutMs()) }
    val values = listOf(3000, 6000, 10000, 15000, 20000)
    val labels = values.map {
        stringResource(R.string.settings_tool_review_timeout_value, it / 1000)
    }
    EtaSegmentedDropdownRow(
        title = title,
        icon = Icons.Rounded.Timer,
        items = labels,
        selectedIndex = values.indexOf(timeoutMs).let { if (it < 0) 1 else it },
        onItemSelected = { index ->
            values.getOrNull(index)?.let { value ->
                if (timeoutMs != value) {
                    timeoutMs = value
                    Prefs.putInt(Prefs.Keys.AGENT_TOOL_REVIEW_TIMEOUT_MS, value)
                }
            }
        },
    )
}

/**
 * 工具渐进加载（三档）：与 [ToolReviewModeRow] 同族的下拉行。
 *
 * 档位语义与工具页一致：按工具 = tools_search 命中解锁所属组；按组 = 仅
 * tools_use_group 整组打开；关闭 = 下发完整工具目录。写入 [Prefs.setToolLoadingMode]，
 * 新建会话生效（当前会话由 run 侧的会话锁保持原设置）。
 *
 * 行内摘要只描述当前档位及生效时机，各档位的一句话说明放在菜单项第二行。
 */
@Composable
private fun ToolLoadingModeRow(
    title: String,
) {
    var mode by remember { mutableStateOf(Prefs.toolLoadingMode()) }
    val options = listOf(
        Prefs.Keys.TOOL_LOADING_MODE_SEARCH,
        Prefs.Keys.TOOL_LOADING_MODE_GROUP,
        Prefs.Keys.TOOL_LOADING_MODE_SUFFIX,
        Prefs.Keys.TOOL_LOADING_MODE_OFF,
    )
    val labels = listOf(
        stringResource(R.string.tools_loading_mode_search),
        stringResource(R.string.tools_loading_mode_group),
        stringResource(R.string.tools_loading_mode_suffix),
        stringResource(R.string.tools_loading_mode_off),
    )
    val effects = listOf(
        stringResource(R.string.tools_loading_mode_search_effect),
        stringResource(R.string.tools_loading_mode_group_effect),
        stringResource(R.string.tools_loading_mode_suffix_effect),
        stringResource(R.string.tools_loading_mode_off_effect),
    )
    val hints = listOf(
        stringResource(R.string.tools_loading_mode_search_hint),
        stringResource(R.string.tools_loading_mode_group_hint),
        stringResource(R.string.tools_loading_mode_suffix_hint),
        stringResource(R.string.tools_loading_mode_off_hint),
    )
    val selectedIndex = options.indexOf(mode).coerceAtLeast(0)
    EtaSegmentedDropdownRow(
        title = title,
        // 渐进加载是「工具目录怎么下发」：与同组其余行共用行首图标槽。
        icon = Icons.Rounded.Layers,
        items = labels,
        selectedIndex = selectedIndex,
        onItemSelected = { index ->
            options.getOrNull(index)?.let { value ->
                if (mode != value) {
                    mode = value
                    Prefs.setToolLoadingMode(value)
                }
            }
        },
        summary = effects[selectedIndex],
        itemSummaries = hints,
    )
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
    summaryOff: String? = null,
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
        summaryOff = summaryOff,
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
