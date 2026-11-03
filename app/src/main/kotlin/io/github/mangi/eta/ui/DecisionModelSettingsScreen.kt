package io.github.mangi.eta.ui

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.decision.AgentDecisionAnswer
import io.github.mangi.eta.agent.decision.AgentDecisionCache
import io.github.mangi.eta.agent.decision.AgentDecisionClientConfig
import io.github.mangi.eta.agent.decision.AgentDecisionDiagnostics
import io.github.mangi.eta.agent.decision.AgentDecisionEndpoint
import io.github.mangi.eta.agent.decision.AgentDecisionModelClient
import io.github.mangi.eta.agent.decision.AgentDecisionOutcome
import io.github.mangi.eta.agent.decision.AgentDecisionQuestion
import io.github.mangi.eta.agent.decision.AgentDecisionStickyStore
import io.github.mangi.eta.agent.decision.AgentDecisionTelemetry
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaNoticeBlockPadding
import io.github.mangi.eta.ui.adaptive.EtaNoticeText
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTextField
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.EtaTooltipIconButton
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 判断模型（工具组路由）设置页。
 *
 * 影子关闭时配置真正生效；影子打开时页面顶部明确标注「只记录不生效」。
 * 页面只读写 `Prefs`：运行时的 overlay 是 run 级内存态，不回写这里。
 *
 * 分组顺序固定为：总开关 → 运行模式 → 模型与端点 → 连通性 → 高级 → 诊断与遥测 → 清理。
 * 依赖关系只用禁用态与副标题表达，不隐藏行——用户始终能看见「有这项、为什么现在不能用」。
 */
@Composable
internal fun DecisionModelSettingsScreen(
    onBack: () -> Unit,
) {
    val prefs = Prefs.localAgentPreferences()
    val writable = prefs != null
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var enabled by remember { mutableStateOf(Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_MODEL_ENABLED)) }
    var shadow by remember { mutableStateOf(Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_SHADOW)) }
    var mode by remember {
        mutableStateOf(
            Prefs.getString(Prefs.Keys.AGENT_DECISION_MODE, Prefs.Keys.AGENT_DECISION_MODE_CONTROL),
        )
    }
    var failurePolicy by remember {
        mutableStateOf(
            Prefs.getString(Prefs.Keys.AGENT_DECISION_FAILURE_POLICY, Prefs.Keys.AGENT_DECISION_FAILURE_OPEN),
        )
    }
    var timeoutMs by remember {
        mutableStateOf(
            Prefs.getInt(Prefs.Keys.AGENT_DECISION_TIMEOUT_MS, Prefs.Keys.AGENT_DECISION_TIMEOUT_DEFAULT_MS),
        )
    }
    var historyTurns by remember {
        mutableStateOf(
            Prefs.getInt(
                Prefs.Keys.AGENT_DECISION_HISTORY_TURNS,
                Prefs.Keys.AGENT_DECISION_HISTORY_TURNS_DEFAULT,
            ),
        )
    }
    var endpointOverride by remember {
        mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_DECISION_ENDPOINT_OVERRIDE))
    }
    var providerId by remember {
        mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_DECISION_PROVIDER_ID).trim())
    }
    var modelId by remember {
        mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_DECISION_MODEL_ID).trim())
    }
    var providerBaseUrl by remember { mutableStateOf<String?>(null) }
    var escalationEnabled by remember {
        mutableStateOf(Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_ESCALATION_ENABLED))
    }
    var escalationModelId by remember {
        mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_DECISION_ESCALATION_MODEL_ID).trim())
    }
    var escalationProviderId by remember {
        mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_DECISION_ESCALATION_PROVIDER_ID).trim())
    }
    var confidencePct by remember {
        mutableStateOf(
            Prefs.getInt(
                Prefs.Keys.AGENT_DECISION_ESCALATION_CONFIDENCE_PCT,
                Prefs.Keys.AGENT_DECISION_ESCALATION_CONFIDENCE_DEFAULT_PCT,
            ),
        )
    }
    var emptyGroupRescue by remember {
        mutableStateOf(Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_EMPTY_GROUP_RESCUE))
    }
    var escalationPickerState by remember { mutableStateOf(AgentModelPickerUiState()) }
    var showEscalationDialog by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(AgentDecisionDiagnostics.last) }
    // 进程内统计（设置页是 App 级页面，拿不到「当前会话」，因此看全局汇总）。
    var telemetry by remember { mutableStateOf(AgentDecisionTelemetry.globalSnapshot()) }
    var modelPickerState by remember { mutableStateOf(AgentModelPickerUiState()) }
    var showModelDialog by remember { mutableStateOf(false) }
    var isLoadingModels by remember { mutableStateOf(writable) }
    // 连通性测试：用表单当前值直发一次最小判断请求，不经过 Router 的缓存/粘滞/统计。
    var connectivityResult by remember { mutableStateOf<DecisionConnectivityResult?>(null) }
    var connectivityRunning by remember { mutableStateOf(false) }
    var connectivityDetailsExpanded by remember { mutableStateOf(false) }
    // 诊断与遥测是只读区，默认折叠，避免默认占满整屏。
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    var showCleanupDialog by remember { mutableStateOf(false) }

    LaunchedEffect(prefs) {
        prefs?.let { currentPrefs ->
            // SharedPreferences 的 getString 返回 String?，这里补上默认值，保持与 Prefs 语义一致。
            mode = currentPrefs.getString(
                Prefs.Keys.AGENT_DECISION_MODE,
                Prefs.Keys.AGENT_DECISION_MODE_CONTROL,
            ) ?: Prefs.Keys.AGENT_DECISION_MODE_CONTROL
            failurePolicy = currentPrefs.getString(
                Prefs.Keys.AGENT_DECISION_FAILURE_POLICY,
                Prefs.Keys.AGENT_DECISION_FAILURE_OPEN,
            ) ?: Prefs.Keys.AGENT_DECISION_FAILURE_OPEN
            timeoutMs = currentPrefs.getInt(
                Prefs.Keys.AGENT_DECISION_TIMEOUT_MS,
                Prefs.Keys.AGENT_DECISION_TIMEOUT_DEFAULT_MS,
            )
            historyTurns = currentPrefs.getInt(
                Prefs.Keys.AGENT_DECISION_HISTORY_TURNS,
                Prefs.Keys.AGENT_DECISION_HISTORY_TURNS_DEFAULT,
            )
            isLoadingModels = true
            modelPickerState = withContext(Dispatchers.IO) {
                buildDecisionModelPickerState(currentPrefs)
            }
            isLoadingModels = false
            providerId = currentPrefs.getString(Prefs.Keys.AGENT_DECISION_PROVIDER_ID, null).orEmpty().trim()
            modelId = currentPrefs.getString(Prefs.Keys.AGENT_DECISION_MODEL_ID, null).orEmpty().trim()
            endpointOverride = currentPrefs.getString(Prefs.Keys.AGENT_DECISION_ENDPOINT_OVERRIDE, null).orEmpty()
            providerBaseUrl = loadProviderBaseUrl(providerId, modelId)
            escalationEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_ESCALATION_ENABLED)
            escalationModelId = currentPrefs.getString(Prefs.Keys.AGENT_DECISION_ESCALATION_MODEL_ID, null)
                .orEmpty().trim()
            escalationProviderId = currentPrefs.getString(Prefs.Keys.AGENT_DECISION_ESCALATION_PROVIDER_ID, null)
                .orEmpty().trim()
            confidencePct = currentPrefs.getInt(
                Prefs.Keys.AGENT_DECISION_ESCALATION_CONFIDENCE_PCT,
                Prefs.Keys.AGENT_DECISION_ESCALATION_CONFIDENCE_DEFAULT_PCT,
            )
            escalationPickerState = withContext(Dispatchers.IO) {
                buildDecisionModelPickerState(currentPrefs, escalation = true)
            }
            diagnostics = AgentDecisionDiagnostics.last
            telemetry = AgentDecisionTelemetry.globalSnapshot()
        }
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Prefs.Keys.AGENT_DECISION_MODEL_ENABLED ->
                    enabled = Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_MODEL_ENABLED)

                Prefs.Keys.AGENT_DECISION_SHADOW ->
                    shadow = Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_SHADOW)

                Prefs.Keys.AGENT_DECISION_MODE ->
                    mode = Prefs.getString(Prefs.Keys.AGENT_DECISION_MODE, Prefs.Keys.AGENT_DECISION_MODE_CONTROL)

                Prefs.Keys.AGENT_DECISION_FAILURE_POLICY ->
                    failurePolicy = Prefs.getString(
                        Prefs.Keys.AGENT_DECISION_FAILURE_POLICY,
                        Prefs.Keys.AGENT_DECISION_FAILURE_OPEN,
                    )

                Prefs.Keys.AGENT_DECISION_TIMEOUT_MS ->
                    timeoutMs = Prefs.getInt(
                        Prefs.Keys.AGENT_DECISION_TIMEOUT_MS,
                        Prefs.Keys.AGENT_DECISION_TIMEOUT_DEFAULT_MS,
                    )

                Prefs.Keys.AGENT_DECISION_HISTORY_TURNS ->
                    historyTurns = Prefs.getInt(
                        Prefs.Keys.AGENT_DECISION_HISTORY_TURNS,
                        Prefs.Keys.AGENT_DECISION_HISTORY_TURNS_DEFAULT,
                    )

                Prefs.Keys.AGENT_DECISION_PROVIDER_ID,
                Prefs.Keys.AGENT_DECISION_MODEL_ID -> {
                    providerId = Prefs.getString(Prefs.Keys.AGENT_DECISION_PROVIDER_ID).trim()
                    modelId = Prefs.getString(Prefs.Keys.AGENT_DECISION_MODEL_ID).trim()
                    prefs?.let { currentPrefs ->
                        scope.launch {
                            modelPickerState = withContext(Dispatchers.IO) {
                                buildDecisionModelPickerState(currentPrefs)
                            }
                            providerBaseUrl = loadProviderBaseUrl(providerId, modelId)
                        }
                    }
                }

                Prefs.Keys.AGENT_DECISION_ESCALATION_ENABLED ->
                    escalationEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_ESCALATION_ENABLED)

                Prefs.Keys.AGENT_DECISION_ESCALATION_PROVIDER_ID,
                Prefs.Keys.AGENT_DECISION_ESCALATION_MODEL_ID -> {
                    escalationProviderId = Prefs.getString(Prefs.Keys.AGENT_DECISION_ESCALATION_PROVIDER_ID).trim()
                    escalationModelId = Prefs.getString(Prefs.Keys.AGENT_DECISION_ESCALATION_MODEL_ID).trim()
                    prefs?.let { currentPrefs ->
                        scope.launch {
                            escalationPickerState = withContext(Dispatchers.IO) {
                                buildDecisionModelPickerState(currentPrefs, escalation = true)
                            }
                        }
                    }
                }

                Prefs.Keys.AGENT_DECISION_ESCALATION_CONFIDENCE_PCT ->
                    confidencePct = Prefs.getInt(
                        Prefs.Keys.AGENT_DECISION_ESCALATION_CONFIDENCE_PCT,
                        Prefs.Keys.AGENT_DECISION_ESCALATION_CONFIDENCE_DEFAULT_PCT,
                    )

                Prefs.Keys.AGENT_DECISION_EMPTY_GROUP_RESCUE ->
                    emptyGroupRescue = Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_EMPTY_GROUP_RESCUE)

                Prefs.Keys.AGENT_DECISION_ENDPOINT_OVERRIDE ->
                    endpointOverride = Prefs.getString(Prefs.Keys.AGENT_DECISION_ENDPOINT_OVERRIDE)
            }
        }
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs?.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val modeOptions = listOf(
        stringResource(R.string.decision_model_mode_control),
        stringResource(R.string.decision_model_mode_advise),
    )
    val failureOptions = listOf(
        stringResource(R.string.decision_model_failure_open),
        stringResource(R.string.decision_model_failure_closed),
    )
    // 逐项列出而不是 map { stringResource(...) }：Compose 只允许在 @Composable 上下文
    // 或内联 lambda 里读取资源，这里保持最直白的写法。
    val timeoutOptions = listOf(
        stringResource(R.string.decision_model_seconds_n, 1),
        stringResource(R.string.decision_model_seconds_n, 2),
        stringResource(R.string.decision_model_seconds_n, 3),
        stringResource(R.string.decision_model_seconds_n, 5),
        stringResource(R.string.decision_model_seconds_n, 10),
    )
    val turnOptions = listOf(
        stringResource(R.string.decision_model_turns_current_only),
        stringResource(R.string.decision_model_turns_n, 1),
        stringResource(R.string.decision_model_turns_n, 3),
        stringResource(R.string.decision_model_turns_n, 5),
        stringResource(R.string.decision_model_turns_n, 10),
    )
    val confidenceOptions = listOf(
        stringResource(R.string.decision_model_escalation_confidence_off),
        stringResource(R.string.decision_model_escalation_confidence_pct, 40),
        stringResource(R.string.decision_model_escalation_confidence_pct, 60),
        stringResource(R.string.decision_model_escalation_confidence_pct, 80),
    )

    // 端点预览：纯函数归一化，与运行时 `AgentDecisionEvaluator` 用的是同一份实现，
    // 所以这里显示的就是实际请求地址，不是「大概会拼成这样」。
    // 只看 override 也能预览：用户还没选 provider 但已经填了完整地址时，
    // 不该退化成「选择 provider 与模型后可预览」（那会让人误以为填的地址无效）。
    val endpointResolution = if (providerBaseUrl != null || endpointOverride.isNotBlank()) {
        AgentDecisionEndpoint.normalize(providerBaseUrl.orEmpty(), endpointOverride)
    } else {
        null
    }
    val endpointErrorRes = (endpointResolution as? AgentDecisionEndpoint.Resolution.Invalid)?.reason?.let { reason ->
        when (reason) {
            AgentDecisionEndpoint.REASON_SCHEME -> R.string.decision_model_endpoint_error_https
            AgentDecisionEndpoint.REASON_QUERY -> R.string.decision_model_endpoint_error_query
            AgentDecisionEndpoint.REASON_HOST -> R.string.decision_model_endpoint_error_host
            else -> R.string.decision_model_endpoint_error_malformed
        }
    }
    val endpointDisplay = when {
        endpointResolution is AgentDecisionEndpoint.Resolution.Success ->
            stringResource(R.string.decision_model_endpoint_resolved, endpointResolution.url)

        endpointErrorRes != null -> stringResource(endpointErrorRes)
        else -> stringResource(R.string.decision_model_endpoint_pending)
    }

    // 总开关关闭 = 本页所有配置都不生效；用统一闸门禁用，避免逐行重复判断。
    val controlsEnabled = writable && enabled
    val modelReady = providerId.isNotBlank() && modelId.isNotBlank()
    val missingCredentials = enabled && !shadow && !modelReady
    val modeSummary = buildString {
        append(stringResource(R.string.decision_model_mode_summary))
        if (shadow) {
            append(" · ")
            append(stringResource(R.string.decision_model_shadow_inline_note))
        }
    }
    // 最近一次判断的只读摘要；没有跑过就没有数据，不显示伪造的默认值。
    val diagnosticsSummary = diagnostics?.let { result ->
        stringResource(
            R.string.decision_model_diagnostics_summary,
            result.selected.joinToString(",").ifBlank { "-" },
            result.allowed.joinToString(",").ifBlank { "-" },
            result.disabledSections.joinToString(",").ifBlank { "-" },
            result.latencyMs,
            result.usage,
        )
    }
    val telemetrySummary = if (telemetry.decisions == 0 && telemetry.cacheHits == 0 && telemetry.degraded == 0) {
        stringResource(R.string.decision_model_telemetry_empty)
    } else {
        stringResource(
            R.string.decision_model_telemetry_summary,
            telemetry.decisions,
            telemetry.escalations,
            (telemetry.cacheHitRate * 100).roundToInt(),
            telemetry.degraded,
            telemetry.mismatches,
        )
    }
    val telemetryReasons = stringResource(
        R.string.decision_model_telemetry_reasons,
        decisionReasonText(telemetry.escalationReasons),
        decisionReasonText(telemetry.degradedReasons),
    )

    // 语义文案在组合期取好：`Modifier.semantics {}` 不是 @Composable 上下文，里面不能读资源。
    val switchOnText = stringResource(R.string.decision_model_switch_on)
    val switchOffText = stringResource(R.string.decision_model_switch_off)
    val expandedText = stringResource(R.string.decision_model_expanded)
    val collapsedText = stringResource(R.string.decision_model_collapsed)

    val groupPadding = Modifier.padding(
        start = EtaGroupHorizontalPadding,
        end = EtaGroupHorizontalPadding,
        bottom = EtaSegmentedGroupSpacing,
    )

    EtaScaffoldPage(title = stringResource(R.string.ui_decision_model_settings_title), onBack = onBack) {
        item(key = "decision_notice") {
            EtaNoticeText(
                title = when {
                    !enabled -> stringResource(R.string.decision_model_disabled_notice_title)
                    shadow -> stringResource(R.string.decision_model_shadow_notice_title)
                    else -> stringResource(R.string.decision_model_shadow_off_notice_title)
                },
                summary = when {
                    !enabled -> stringResource(R.string.decision_model_disabled_notice_summary)
                    shadow -> stringResource(R.string.decision_model_shadow_notice_summary)
                    else -> stringResource(R.string.decision_model_shadow_off_notice_summary)
                },
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            )
        }

        if (missingCredentials) {
            item(key = "decision_missing_credentials") {
                EtaNoticeText(
                    title = stringResource(R.string.decision_model_missing_credentials_title),
                    summary = stringResource(R.string.decision_model_missing_credentials_summary),
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = EtaSegmentedGroupSpacing,
                    ),
                )
            }
        }

        // ── 总开关 ────────────────────────────────────────────────────────
        item(key = "decision_master") {
            EtaSegmentedColumn(
                title = stringResource(R.string.decision_model_group_master),
                modifier = groupPadding,
            ) {
                item(key = "master_enabled") {
                    EtaSwitchRow(
                        title = stringResource(R.string.decision_model_enabled_title),
                        summary = stringResource(R.string.decision_model_enabled_summary),
                        checked = enabled,
                        enabled = writable,
                        leading = { PreferenceIcon(icon = Icons.Rounded.Psychology, enabled = enabled) },
                        modifier = Modifier.semantics {
                            stateDescription = if (enabled) switchOnText else switchOffText
                        },
                        onCheckedChange = { value ->
                            Prefs.putBoolean(Prefs.Keys.AGENT_DECISION_MODEL_ENABLED, value)
                            enabled = value
                        },
                    )
                }
                item(key = "master_release") {
                    // 「一键放行」= 直接关闭总开关（本实现选这一分支），不新增会话级 override：
                    // 语义简单、跨进程可预期，且用户能随时重新打开。
                    EtaClickableRow(
                        title = stringResource(R.string.decision_model_release_title),
                        summary = stringResource(R.string.decision_model_release_summary),
                        leading = { PreferenceIcon(icon = Icons.Rounded.PowerSettingsNew, enabled = controlsEnabled) },
                        enabled = controlsEnabled,
                        showChevron = false,
                        onClick = {
                            Prefs.putBoolean(Prefs.Keys.AGENT_DECISION_MODEL_ENABLED, false)
                            enabled = false
                        },
                    )
                }
            }
        }

        // ── 运行模式与影子 ────────────────────────────────────────────────
        item(key = "decision_mode") {
            EtaSegmentedColumn(
                title = stringResource(R.string.decision_model_group_mode),
                modifier = groupPadding,
            ) {
                item(key = "mode") {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.decision_model_mode_title),
                        summary = modeSummary,
                        description = stringResource(
                            R.string.decision_model_dropdown_description,
                            stringResource(R.string.decision_model_mode_title),
                            if (mode == Prefs.Keys.AGENT_DECISION_MODE_ADVISE) {
                                stringResource(R.string.decision_model_mode_advise)
                            } else {
                                stringResource(R.string.decision_model_mode_control)
                            },
                        ),
                        icon = Icons.Rounded.Psychology,
                        items = modeOptions,
                        selectedIndex = if (mode == Prefs.Keys.AGENT_DECISION_MODE_ADVISE) 1 else 0,
                        enabled = controlsEnabled,
                        onItemSelected = { index ->
                            val value = if (index == 1) {
                                Prefs.Keys.AGENT_DECISION_MODE_ADVISE
                            } else {
                                Prefs.Keys.AGENT_DECISION_MODE_CONTROL
                            }
                            Prefs.putString(Prefs.Keys.AGENT_DECISION_MODE, value)
                            mode = value
                        },
                    )
                }
                item(key = "shadow") {
                    EtaSwitchRow(
                        title = stringResource(R.string.decision_model_shadow_title),
                        summary = stringResource(R.string.decision_model_shadow_summary),
                        checked = shadow,
                        enabled = controlsEnabled,
                        leading = { PreferenceIcon(icon = Icons.Rounded.Visibility, enabled = controlsEnabled) },
                        modifier = Modifier.semantics {
                            stateDescription = if (shadow) switchOnText else switchOffText
                        },
                        onCheckedChange = { value ->
                            Prefs.putBoolean(Prefs.Keys.AGENT_DECISION_SHADOW, value)
                            shadow = value
                        },
                    )
                }
            }
        }

        // ── 模型与端点 ────────────────────────────────────────────────────
        item(key = "decision_model") {
            EtaSegmentedColumn(
                title = stringResource(R.string.decision_model_group_model),
                modifier = groupPadding,
            ) {
                item(key = "model") {
                    EtaClickableRow(
                        title = stringResource(R.string.decision_model_model_title),
                        summary = modelPickerState.selectedModel?.let { "${it.providerName} · ${it.displayName}" }
                            ?: stringResource(R.string.decision_model_not_selected),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Memory, enabled = controlsEnabled) },
                        enabled = controlsEnabled,
                        onClick = { showModelDialog = true },
                    )
                }
                item(key = "endpoint") {
                    EtaSegmentedTextField(
                        label = stringResource(R.string.decision_model_endpoint_title),
                        value = endpointOverride,
                        onValueChange = { value ->
                            Prefs.putString(Prefs.Keys.AGENT_DECISION_ENDPOINT_OVERRIDE, value)
                            endpointOverride = value
                        },
                        enabled = controlsEnabled,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.decision_model_endpoint_placeholder)) },
                        isError = endpointErrorRes != null,
                        supportingContent = {
                            Text(
                                text = endpointDisplay,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (endpointErrorRes != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                    )
                }
                item(key = "timeout") {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.decision_model_timeout_title),
                        summary = stringResource(R.string.decision_model_timeout_summary),
                        description = stringResource(
                            R.string.decision_model_dropdown_description,
                            stringResource(R.string.decision_model_timeout_title),
                            timeoutOptions[nearestIndex(TIMEOUT_VALUES, timeoutMs.coerceIn(500, 10_000))],
                        ),
                        icon = Icons.Rounded.Timer,
                        items = timeoutOptions,
                        selectedIndex = nearestIndex(TIMEOUT_VALUES, timeoutMs.coerceIn(500, 10_000)),
                        enabled = controlsEnabled,
                        onItemSelected = { index ->
                            val value = TIMEOUT_VALUES[index]
                            Prefs.putInt(Prefs.Keys.AGENT_DECISION_TIMEOUT_MS, value)
                            timeoutMs = value
                        },
                    )
                }
                item(key = "protocol") {
                    // 协议在 M1 只有一个实现；按只读行展示，避免给用户一个点了没反应的菜单。
                    EtaClickableRow(
                        title = stringResource(R.string.decision_model_protocol_title),
                        summary = stringResource(R.string.decision_model_protocol_summary),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Api) },
                        showChevron = false,
                        onClick = null,
                    )
                }
            }
        }

        // ── 连通性 ────────────────────────────────────────────────────────
        item(key = "decision_connectivity") {
            EtaSegmentedColumn(
                title = stringResource(R.string.decision_model_group_connectivity),
                modifier = groupPadding,
            ) {
                item(key = "connectivity_probe") {
                    EtaClickableRow(
                        title = stringResource(R.string.decision_model_connectivity_title),
                        summary = when {
                            connectivityRunning -> stringResource(R.string.decision_model_connectivity_running)
                            connectivityResult != null -> stringResource(R.string.decision_model_connectivity_done)
                            !modelReady -> stringResource(R.string.decision_model_connectivity_need_model)
                            else -> stringResource(R.string.decision_model_connectivity_summary)
                        },
                        enabled = controlsEnabled && modelReady && !connectivityRunning,
                        showChevron = false,
                        onClick = null,
                        endActions = {
                            // 纯图标动作：长按必须能看 Tooltip（见 accessibility-tooltips.md）。
                            EtaTooltipIconButton(
                                icon = Icons.Rounded.Speed,
                                contentDescription = stringResource(R.string.decision_model_connectivity_title),
                                tooltipText = if (connectivityRunning) {
                                    stringResource(R.string.decision_model_connectivity_running)
                                } else {
                                    stringResource(R.string.decision_model_connectivity_title)
                                },
                                disabledTooltipText = if (!modelReady) {
                                    stringResource(R.string.decision_model_connectivity_need_model)
                                } else {
                                    null
                                },
                                enabled = controlsEnabled && modelReady && !connectivityRunning,
                                onClick = {
                                    connectivityRunning = true
                                    connectivityDetailsExpanded = false
                                    scope.launch {
                                        connectivityResult = probeDecisionConnectivity(
                                            providerId = providerId,
                                            modelId = modelId,
                                            endpointOverride = endpointOverride,
                                            timeoutMs = timeoutMs,
                                        )
                                        connectivityRunning = false
                                    }
                                },
                            )
                        },
                    )
                }
                connectivityResult?.let { result ->
                    item(key = "connectivity_status") {
                        EtaClickableRow(
                            title = stringResource(
                                if (result.success) {
                                    R.string.decision_model_connectivity_ok_title
                                } else {
                                    R.string.decision_model_connectivity_fail_title
                                },
                            ),
                            summary = connectivityStatusLine(result),
                            leading = {
                                Icon(
                                    imageVector = if (result.success) {
                                        Icons.Rounded.CheckCircle
                                    } else {
                                        Icons.Rounded.ErrorOutline
                                    },
                                    contentDescription = null,
                                    tint = if (result.success) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            },
                            showChevron = false,
                            onClick = null,
                        )
                    }
                    item(key = "connectivity_details_toggle") {
                        EtaClickableRow(
                            title = stringResource(
                                if (connectivityDetailsExpanded) {
                                    R.string.decision_model_connectivity_details_hide
                                } else {
                                    R.string.decision_model_connectivity_details_show
                                },
                            ),
                            showChevron = true,
                            onClick = { connectivityDetailsExpanded = !connectivityDetailsExpanded },
                        )
                    }
                    item(key = "connectivity_details", visible = connectivityDetailsExpanded) {
                        EtaSegmentedItemContainer {
                            Column(modifier = Modifier.padding(EtaNoticeBlockPadding)) {
                                EtaText(
                                    text = stringResource(
                                        R.string.decision_model_connectivity_detail_endpoint,
                                        result.endpoint,
                                    ),
                                    style = EtaTextStyle.Footnote,
                                )
                                EtaText(
                                    text = stringResource(
                                        R.string.decision_model_connectivity_detail_model,
                                        result.model,
                                    ),
                                    style = EtaTextStyle.Footnote,
                                )
                                val errorText = result.detail.ifBlank { result.reason }
                                if (!result.success) {
                                    EtaText(
                                        text = stringResource(
                                            R.string.decision_model_connectivity_detail_error,
                                            errorText,
                                        ),
                                        style = EtaTextStyle.Footnote,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 高级 ──────────────────────────────────────────────────────────
        item(key = "decision_advanced") {
            EtaSegmentedColumn(
                title = stringResource(R.string.decision_model_group_advanced),
                modifier = groupPadding,
            ) {
                item(key = "escalation_enabled") {
                    EtaSwitchRow(
                        title = stringResource(R.string.decision_model_escalation_enabled_title),
                        summary = stringResource(R.string.decision_model_escalation_enabled_summary),
                        checked = escalationEnabled,
                        enabled = controlsEnabled,
                        leading = { PreferenceIcon(icon = Icons.Rounded.Psychology, enabled = controlsEnabled) },
                        modifier = Modifier.semantics {
                            stateDescription = if (escalationEnabled) switchOnText else switchOffText
                        },
                        onCheckedChange = { value ->
                            Prefs.putBoolean(Prefs.Keys.AGENT_DECISION_ESCALATION_ENABLED, value)
                            escalationEnabled = value
                        },
                    )
                }
                item(key = "escalation_model") {
                    EtaClickableRow(
                        title = stringResource(R.string.decision_model_escalation_model_title),
                        summary = if (!escalationEnabled) {
                            stringResource(R.string.decision_model_escalation_requires_enabled)
                        } else {
                            escalationPickerState.selectedModel?.displayName
                                ?: stringResource(R.string.decision_model_escalation_model_not_selected)
                        },
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Memory,
                                enabled = controlsEnabled && escalationEnabled,
                            )
                        },
                        enabled = controlsEnabled && escalationEnabled,
                        onClick = { showEscalationDialog = true },
                    )
                }
                item(key = "escalation_confidence") {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.decision_model_escalation_confidence_title),
                        summary = stringResource(R.string.decision_model_escalation_confidence_summary),
                        description = stringResource(
                            R.string.decision_model_dropdown_description,
                            stringResource(R.string.decision_model_escalation_confidence_title),
                            confidenceOptions[nearestIndex(CONFIDENCE_VALUES, confidencePct)],
                        ),
                        items = confidenceOptions,
                        selectedIndex = nearestIndex(CONFIDENCE_VALUES, confidencePct),
                        enabled = controlsEnabled && escalationEnabled,
                        onItemSelected = { index ->
                            val value = CONFIDENCE_VALUES[index]
                            Prefs.putInt(Prefs.Keys.AGENT_DECISION_ESCALATION_CONFIDENCE_PCT, value)
                            confidencePct = value
                        },
                    )
                }
                item(key = "failure_policy") {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.decision_model_failure_policy_title),
                        summary = stringResource(R.string.decision_model_failure_policy_summary),
                        description = stringResource(
                            R.string.decision_model_dropdown_description,
                            stringResource(R.string.decision_model_failure_policy_title),
                            failureOptions[if (failurePolicy == Prefs.Keys.AGENT_DECISION_FAILURE_CLOSED) 1 else 0],
                        ),
                        items = failureOptions,
                        selectedIndex = if (failurePolicy == Prefs.Keys.AGENT_DECISION_FAILURE_CLOSED) 1 else 0,
                        enabled = controlsEnabled,
                        onItemSelected = { index ->
                            val value = if (index == 1) {
                                Prefs.Keys.AGENT_DECISION_FAILURE_CLOSED
                            } else {
                                Prefs.Keys.AGENT_DECISION_FAILURE_OPEN
                            }
                            Prefs.putString(Prefs.Keys.AGENT_DECISION_FAILURE_POLICY, value)
                            failurePolicy = value
                        },
                    )
                }
                item(key = "history_turns") {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.decision_model_history_turns_title),
                        summary = stringResource(R.string.decision_model_history_turns_summary),
                        description = stringResource(
                            R.string.decision_model_dropdown_description,
                            stringResource(R.string.decision_model_history_turns_title),
                            turnOptions[nearestIndex(HISTORY_TURN_VALUES, historyTurns.coerceIn(0, 10))],
                        ),
                        items = turnOptions,
                        selectedIndex = nearestIndex(HISTORY_TURN_VALUES, historyTurns.coerceIn(0, 10)),
                        enabled = controlsEnabled,
                        onItemSelected = { index ->
                            val value = HISTORY_TURN_VALUES[index]
                            Prefs.putInt(Prefs.Keys.AGENT_DECISION_HISTORY_TURNS, value)
                            historyTurns = value
                        },
                    )
                }
                item(key = "empty_group_rescue") {
                    // 只影响控制模式：建议模式不接管枚举，影子模式不生效。
                    EtaSwitchRow(
                        title = stringResource(R.string.decision_model_empty_group_rescue_title),
                        summary = stringResource(R.string.decision_model_empty_group_rescue_summary),
                        checked = emptyGroupRescue,
                        enabled = controlsEnabled &&
                            mode == Prefs.Keys.AGENT_DECISION_MODE_CONTROL &&
                            !shadow,
                        leading = {
                            PreferenceIcon(
                                icon = Icons.Rounded.Build,
                                enabled = controlsEnabled &&
                                    mode == Prefs.Keys.AGENT_DECISION_MODE_CONTROL &&
                                    !shadow,
                            )
                        },
                        modifier = Modifier.semantics {
                            stateDescription = if (emptyGroupRescue) switchOnText else switchOffText
                        },
                        onCheckedChange = { value ->
                            Prefs.putBoolean(Prefs.Keys.AGENT_DECISION_EMPTY_GROUP_RESCUE, value)
                            emptyGroupRescue = value
                        },
                    )
                }
            }
        }

        // ── 诊断与遥测（只读，默认折叠）────────────────────────────────────
        item(key = "decision_diagnostics") {
            EtaSegmentedColumn(
                title = stringResource(R.string.decision_model_group_diagnostics),
                modifier = groupPadding,
            ) {
                item(key = "diagnostics_toggle") {
                    EtaClickableRow(
                        title = stringResource(R.string.decision_model_diagnostics_title),
                        summary = diagnosticsSummary
                            ?: stringResource(R.string.decision_model_diagnostics_empty),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Memory) },
                        showChevron = true,
                        modifier = Modifier.semantics {
                            stateDescription = if (diagnosticsExpanded) expandedText else collapsedText
                        },
                        onClick = { diagnosticsExpanded = !diagnosticsExpanded },
                    )
                }
                item(key = "diagnostics_body", visible = diagnosticsExpanded) {
                    EtaSegmentedItemContainer {
                        Column(modifier = Modifier.padding(EtaNoticeBlockPadding)) {
                            EtaText(
                                text = stringResource(R.string.decision_model_diagnostics_title),
                                style = EtaTextStyle.Title,
                            )
                            EtaText(
                                text = diagnosticsSummary
                                    ?: stringResource(R.string.decision_model_diagnostics_empty),
                                style = EtaTextStyle.Secondary,
                            )
                            EtaText(
                                text = stringResource(R.string.decision_model_telemetry_title),
                                style = EtaTextStyle.Title,
                                modifier = Modifier.padding(top = EtaDimens.SpaceMd),
                            )
                            EtaText(text = telemetrySummary, style = EtaTextStyle.Secondary)
                            EtaText(text = telemetryReasons, style = EtaTextStyle.Footnote)
                            EtaText(
                                text = stringResource(R.string.decision_model_status_summary),
                                style = EtaTextStyle.Footnote,
                                modifier = Modifier.padding(top = EtaDimens.SpaceMd),
                            )
                        }
                    }
                }
            }
        }

        // ── 清理 ──────────────────────────────────────────────────────────
        item(key = "decision_cleanup") {
            EtaSegmentedColumn(
                title = stringResource(R.string.decision_model_group_cleanup),
                modifier = groupPadding,
            ) {
                item(key = "cleanup") {
                    EtaClickableRow(
                        title = stringResource(R.string.decision_model_cleanup_title),
                        summary = stringResource(R.string.decision_model_cleanup_summary),
                        showChevron = false,
                        enabled = writable,
                        onClick = null,
                        endActions = {
                            // 破坏性动作走二次确认；图标按钮自带长按 Tooltip。
                            EtaTooltipIconButton(
                                icon = Icons.Rounded.DeleteSweep,
                                contentDescription = stringResource(R.string.decision_model_cleanup_title),
                                enabled = writable,
                                destructive = true,
                                onClick = { showCleanupDialog = true },
                            )
                        },
                    )
                }
            }
        }
    }

    if (showCleanupDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.decision_model_cleanup_confirm_title),
            summary = stringResource(R.string.decision_model_cleanup_confirm_summary),
            onDismissRequest = { showCleanupDialog = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.decision_model_cleanup_title),
                    destructive = true,
                    onCancel = { showCleanupDialog = false },
                    onConfirm = {
                        AgentDecisionCache.clear()
                        AgentDecisionStickyStore.clear()
                        AgentDecisionTelemetry.reset()
                        telemetry = AgentDecisionTelemetry.globalSnapshot()
                        showCleanupDialog = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.decision_model_cleanup_done),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            },
        )
    }

    CompressModelPickerDialog(
        state = modelPickerState,
        show = showModelDialog,
        isLoading = isLoadingModels,
        title = stringResource(R.string.decision_model_picker_title),
        onDismiss = { showModelDialog = false },
        onModelSelected = { providerId, modelId ->
            Prefs.putString(Prefs.Keys.AGENT_DECISION_PROVIDER_ID, providerId)
            Prefs.putString(Prefs.Keys.AGENT_DECISION_MODEL_ID, modelId)
            modelPickerState = modelPickerState.copy(
                selectedModel = modelPickerState.findCompressModel(providerId, modelId),
            )
            showModelDialog = false
        },
    )
    CompressModelPickerDialog(
        state = escalationPickerState,
        show = showEscalationDialog,
        isLoading = false,
        title = stringResource(R.string.decision_model_escalation_model_title),
        onDismiss = { showEscalationDialog = false },
        onModelSelected = { providerId, modelId ->
            Prefs.putString(Prefs.Keys.AGENT_DECISION_ESCALATION_PROVIDER_ID, providerId)
            Prefs.putString(Prefs.Keys.AGENT_DECISION_ESCALATION_MODEL_ID, modelId)
            escalationProviderId = providerId
            escalationModelId = modelId
            escalationPickerState = escalationPickerState.copy(
                selectedModel = escalationPickerState.findCompressModel(providerId, modelId),
            )
            showEscalationDialog = false
        },
    )
}

/**
 * 与压缩模型选择器共用 provider 列表；只把读写的键换成判断模型的键。
 * [escalation] = true 时读升级模型的键，provider 缺省回落到主模型 provider。
 */
private suspend fun buildDecisionModelPickerState(
    prefs: SharedPreferences,
    escalation: Boolean = false,
): AgentModelPickerUiState = buildCompressModelPickerState(
    selectedProviderId = if (escalation) {
        prefs.getString(Prefs.Keys.AGENT_DECISION_ESCALATION_PROVIDER_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: prefs.getString(Prefs.Keys.AGENT_DECISION_PROVIDER_ID, null)?.takeIf { it.isNotBlank() }
    } else {
        prefs.getString(Prefs.Keys.AGENT_DECISION_PROVIDER_ID, null)?.takeIf { it.isNotBlank() }
    },
    selectedModelId = prefs.getString(
        if (escalation) {
            Prefs.Keys.AGENT_DECISION_ESCALATION_MODEL_ID
        } else {
            Prefs.Keys.AGENT_DECISION_MODEL_ID
        },
        null,
    )?.takeIf { it.isNotBlank() },
)

/** 端点预览需要 provider 的 baseUrl；读不到时返回 null，页面显示「选择模型后预览」。 */
private suspend fun loadProviderBaseUrl(providerId: String, modelId: String): String? =
    withContext(Dispatchers.IO) {
        if (providerId.isBlank() || modelId.isBlank()) {
            null
        } else {
            RuntimeConfigRepository.configForProviderAndModel(providerId, modelId)?.baseUrl
        }
    }

private val TIMEOUT_VALUES = listOf(1_000, 2_000, 3_000, 5_000, 10_000)
private val HISTORY_TURN_VALUES = listOf(0, 1, 3, 5, 10)
/** 升级置信度阈值档位；0 = 不按置信度升级。 */
private val CONFIDENCE_VALUES = listOf(0, 40, 60, 80)

/** 存量值可能不在预设档位（例如旧配置写了 4 秒），展示时就近取一档，不擅自改写存储值。 */
private fun nearestIndex(values: List<Int>, value: Int): Int =
    values.indices.minByOrNull { kotlin.math.abs(values[it] - value) } ?: 0

/** 遥测里的原因分布压缩成一行；没有数据时用 `-` 占位。 */
private fun decisionReasonText(reasons: Map<String, Int>): String =
    reasons.entries
        .sortedByDescending { it.value }
        .joinToString(",") { "${it.key}:${it.value}" }
        .ifBlank { "-" }

/** 连通性测试结果；只用于展示，不写回任何偏好，也不进入 Router 的缓存/粘滞/统计。 */
private data class DecisionConnectivityResult(
    val success: Boolean,
    val reason: String,
    val httpStatus: Int?,
    val latencyMs: Long,
    val endpoint: String,
    val model: String,
    val answers: String,
    val detail: String,
    val atMillis: Long,
)

/**
 * 用设置页**当前表单值**直发一次最小判断请求。
 *
 * 与运行期链路解耦：不读 Router 缓存/粘滞，不写遥测；provider 行只用来取 baseUrl 与 key，
 * 请求里的 `model` 用 provider 行里的 API 模型名（设置页存的是记录 id）。
 */
private suspend fun probeDecisionConnectivity(
    providerId: String,
    modelId: String,
    endpointOverride: String,
    timeoutMs: Int,
): DecisionConnectivityResult {
    val atMillis = System.currentTimeMillis()
    if (providerId.isBlank() || modelId.isBlank()) {
        return DecisionConnectivityResult(
            success = false,
            reason = "missing_config",
            httpStatus = null,
            latencyMs = 0,
            endpoint = "-",
            model = "-",
            answers = "",
            detail = "",
            atMillis = atMillis,
        )
    }
    val runtime = withContext(Dispatchers.IO) {
        RuntimeConfigRepository.configForProviderAndModel(providerId, modelId)
    } ?: return DecisionConnectivityResult(
        success = false,
        reason = "config_missing",
        httpStatus = null,
        latencyMs = 0,
        endpoint = "-",
        model = modelId,
        answers = "",
        detail = "",
        atMillis = atMillis,
    )

    val endpoint = when (val resolution = AgentDecisionEndpoint.normalize(runtime.baseUrl, endpointOverride)) {
        is AgentDecisionEndpoint.Resolution.Success -> resolution.url
        is AgentDecisionEndpoint.Resolution.Invalid -> return DecisionConnectivityResult(
            success = false,
            reason = "invalid_endpoint",
            httpStatus = null,
            latencyMs = 0,
            endpoint = "invalid(${resolution.reason})",
            model = runtime.model,
            answers = "",
            detail = resolution.reason,
            atMillis = atMillis,
        )
    }

    val config = AgentDecisionClientConfig(
        baseUrl = endpoint,
        apiKey = runtime.apiKey,
        model = runtime.model,
        timeoutMs = timeoutMs.coerceIn(500, 10_000),
    )
    val state = JSONObject()
        .put("user_input", "open the browser and read the current page")
        .put("current_time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
    val questions = listOf(
        AgentDecisionQuestion.Noul(
            id = "connectivity_probe",
            instructions = "Decide whether the text describes an explicit request to run a tool.",
            trueCriterion = "The text asks to open a browser, read a page, or otherwise run a tool.",
            falseCriterion = "The text is chit-chat or otherwise needs no tool.",
        ),
    )

    return when (val outcome = AgentDecisionModelClient.complete(config, state, questions)) {
        is AgentDecisionOutcome.Success -> DecisionConnectivityResult(
            success = true,
            reason = "ok",
            httpStatus = outcome.response.httpStatus,
            latencyMs = outcome.response.latencyMs,
            endpoint = endpoint,
            model = runtime.model,
            answers = summarizeDecisionAnswers(outcome.response.answers),
            detail = "",
            atMillis = atMillis,
        )
        is AgentDecisionOutcome.Failure -> DecisionConnectivityResult(
            success = false,
            reason = outcome.reason,
            httpStatus = outcome.httpStatus,
            latencyMs = outcome.latencyMs,
            endpoint = endpoint,
            model = runtime.model,
            answers = "",
            detail = outcome.detail.ifBlank { outcome.errorDesc },
            atMillis = atMillis,
        )
    }
}

private fun summarizeDecisionAnswers(answers: Map<String, AgentDecisionAnswer>): String =
    answers.entries.joinToString(",") { (id, answer) ->
        val compact = when (answer) {
            is AgentDecisionAnswer.Noul -> String.format(Locale.US, "%.2f", answer.probability)
            is AgentDecisionAnswer.Choice -> "${answer.choice}(${String.format(Locale.US, "%.2f", answer.confidence)})"
            is AgentDecisionAnswer.MultiChoice -> answer.choices.sorted().joinToString("+")
            is AgentDecisionAnswer.Score -> String.format(Locale.US, "%.2f", answer.score)
        }
        "$id=$compact"
    }.ifBlank { "-" }

/**
 * 结果行只放「状态 + 耗时 + 时间」，URL/model/错误收进可展开详情，
 * 避免一次失败默认占满整屏。
 */
@Composable
private fun connectivityStatusLine(result: DecisionConnectivityResult): String {
    val clock = remember(result.atMillis) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(result.atMillis))
    }
    val firstLine = if (result.success) {
        stringResource(
            R.string.decision_model_connectivity_ok_line,
            result.httpStatus ?: 200,
            result.latencyMs.toInt(),
            result.answers,
        )
    } else {
        stringResource(
            R.string.decision_model_connectivity_fail_line,
            stringResource(connectivityReasonRes(result.reason)),
            result.latencyMs.toInt(),
        )
    }
    return firstLine + " · " + stringResource(R.string.decision_model_connectivity_time_line, clock)
}

/** 把客户端 reason 归一到可读分类；原始 reason 仍保留在 detail 里。 */
private fun connectivityReasonRes(reason: String): Int = when {
    reason == "http_401" -> R.string.decision_model_connectivity_reason_auth
    reason == "http_403" -> R.string.decision_model_connectivity_reason_forbidden
    reason == "http_404" -> R.string.decision_model_connectivity_reason_not_found
    reason == "timeout" -> R.string.decision_model_connectivity_reason_timeout
    reason == "network_error" -> R.string.decision_model_connectivity_reason_network
    reason == "invalid_json" || reason == "missing_error_code" ||
        reason.startsWith("error_code_") -> R.string.decision_model_connectivity_reason_protocol
    reason == "invalid_answer" -> R.string.decision_model_connectivity_reason_parse
    reason == "missing_config" || reason == "config_missing" ||
        reason == "invalid_endpoint" || reason == "missing_api_key" ||
        reason == "missing_model" || reason == "invalid_base_url" ->
        R.string.decision_model_connectivity_reason_config
    reason.startsWith("http_") -> R.string.decision_model_connectivity_reason_http
    else -> R.string.decision_model_connectivity_reason_other
}
