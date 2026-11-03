package io.github.mangi.eta.ui

import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.LocalContentColor
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
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.decision.AgentDecisionCache
import io.github.mangi.eta.agent.decision.AgentDecisionDiagnostics
import io.github.mangi.eta.agent.decision.AgentDecisionEndpoint
import io.github.mangi.eta.agent.decision.AgentDecisionStickyStore
import io.github.mangi.eta.agent.decision.AgentDecisionTelemetry
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaNoticeText
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTextField
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 判断模型（工具组路由）设置页。
 *
 * M2 起影子关闭时配置真正生效；影子打开时页面顶部明确标注「只记录不生效」。
 * 页面只读写 `Prefs`：运行时的 overlay 是 run 级内存态，不回写这里。
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
    var escalationPickerState by remember { mutableStateOf(AgentModelPickerUiState()) }
    var showEscalationDialog by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(AgentDecisionDiagnostics.last) }
    // M4：进程内统计（设置页是 App 级页面，拿不到「当前会话」，因此看全局汇总）。
    var telemetry by remember { mutableStateOf(AgentDecisionTelemetry.globalSnapshot()) }
    var modelPickerState by remember { mutableStateOf(AgentModelPickerUiState()) }
    var showModelDialog by remember { mutableStateOf(false) }
    var isLoadingModels by remember { mutableStateOf(writable) }

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
    val missingCredentials = enabled && !shadow && (providerId.isBlank() || modelId.isBlank())
    // 最近一次决策的只读摘要；没有跑过就没有数据，不显示伪造的默认值。
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
    // M4：本进程内的决策统计；命中率/漏判率都由快照计算，不在这里重复口径。
    val hasTelemetry = telemetry.decisions + telemetry.cacheHits + telemetry.degraded > 0
    val telemetrySummary = if (!hasTelemetry) {
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

    EtaScaffoldPage(title = stringResource(R.string.ui_decision_model_settings_title), onBack = onBack) {
        item(key = "decision_notice") {
            EtaNoticeText(
                title = if (shadow) {
                    stringResource(R.string.decision_model_shadow_notice_title)
                } else {
                    stringResource(R.string.decision_model_shadow_off_notice_title)
                },
                summary = if (shadow) {
                    stringResource(R.string.decision_model_shadow_notice_summary)
                } else {
                    stringResource(R.string.decision_model_shadow_off_notice_summary)
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

        item(key = "decision_enabled") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.decision_model_enabled_title),
                        summary = stringResource(R.string.decision_model_enabled_summary),
                        checked = enabled,
                        enabled = writable,
                        leading = { PreferenceIcon(icon = Icons.Rounded.Psychology) },
                        onCheckedChange = { value ->
                            Prefs.putBoolean(Prefs.Keys.AGENT_DECISION_MODEL_ENABLED, value)
                            enabled = value
                        },
                    )
                }
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.decision_model_shadow_title),
                        summary = stringResource(R.string.decision_model_shadow_summary),
                        checked = shadow,
                        enabled = writable,
                        leading = { PreferenceIcon(icon = Icons.Rounded.Visibility) },
                        onCheckedChange = { value ->
                            Prefs.putBoolean(Prefs.Keys.AGENT_DECISION_SHADOW, value)
                            shadow = value
                        },
                    )
                }
                item {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.decision_model_mode_title),
                        summary = stringResource(R.string.decision_model_mode_summary),
                        icon = Icons.Rounded.Psychology,
                        items = modeOptions,
                        selectedIndex = if (mode == Prefs.Keys.AGENT_DECISION_MODE_ADVISE) 1 else 0,
                        enabled = writable,
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
            }
        }

        item(key = "decision_model") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                item {
                    EtaClickableRow(
                        title = stringResource(R.string.decision_model_model_title),
                        summary = modelPickerState.selectedModel?.displayName
                            ?: stringResource(R.string.decision_model_not_selected),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Memory) },
                        enabled = writable,
                        onClick = { showModelDialog = true },
                    )
                }
            }
        }

        item(key = "decision_escalation") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.decision_model_escalation_enabled_title),
                        summary = stringResource(R.string.decision_model_escalation_enabled_summary),
                        checked = escalationEnabled,
                        enabled = writable,
                        leading = { PreferenceIcon(icon = Icons.Rounded.Psychology) },
                        onCheckedChange = { value ->
                            Prefs.putBoolean(Prefs.Keys.AGENT_DECISION_ESCALATION_ENABLED, value)
                            escalationEnabled = value
                        },
                    )
                }
                item {
                    EtaClickableRow(
                        title = stringResource(R.string.decision_model_escalation_model_title),
                        summary = escalationPickerState.selectedModel?.displayName
                            ?: stringResource(R.string.decision_model_escalation_model_not_selected),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Memory) },
                        enabled = writable && escalationEnabled,
                        onClick = { showEscalationDialog = true },
                    )
                }
                item {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.decision_model_escalation_confidence_title),
                        summary = stringResource(R.string.decision_model_escalation_confidence_summary),
                        items = confidenceOptions,
                        selectedIndex = nearestIndex(CONFIDENCE_VALUES, confidencePct),
                        enabled = writable && escalationEnabled,
                        onItemSelected = { index ->
                            val value = CONFIDENCE_VALUES[index]
                            Prefs.putInt(Prefs.Keys.AGENT_DECISION_ESCALATION_CONFIDENCE_PCT, value)
                            confidencePct = value
                        },
                    )
                }
            }
        }

        item(key = "decision_endpoint") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                item {
                    EtaSegmentedTextField(
                        label = stringResource(R.string.decision_model_endpoint_title),
                        value = endpointOverride,
                        onValueChange = { value ->
                            Prefs.putString(Prefs.Keys.AGENT_DECISION_ENDPOINT_OVERRIDE, value)
                            endpointOverride = value
                        },
                        enabled = writable,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.decision_model_endpoint_placeholder)) },
                        isError = endpointErrorRes != null,
                        supportingContent = {
                            Text(
                                text = endpointDisplay,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (endpointErrorRes != null) MaterialTheme.colorScheme.error
                                else LocalContentColor.current.copy(alpha = 0.7f),
                            )
                        },
                    )
                }
            }
        }

        item(key = "decision_runtime") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            ) {
                item {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.decision_model_timeout_title),
                        summary = stringResource(R.string.decision_model_timeout_summary),
                        icon = Icons.Rounded.Timer,
                        items = timeoutOptions,
                        selectedIndex = nearestIndex(TIMEOUT_VALUES, timeoutMs.coerceIn(500, 10_000)),
                        enabled = writable,
                        onItemSelected = { index ->
                            val value = TIMEOUT_VALUES[index]
                            Prefs.putInt(Prefs.Keys.AGENT_DECISION_TIMEOUT_MS, value)
                            timeoutMs = value
                        },
                    )
                }
                item {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.decision_model_failure_policy_title),
                        summary = stringResource(R.string.decision_model_failure_policy_summary),
                        items = failureOptions,
                        selectedIndex = if (failurePolicy == Prefs.Keys.AGENT_DECISION_FAILURE_CLOSED) 1 else 0,
                        enabled = writable,
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
                item {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.decision_model_history_turns_title),
                        summary = stringResource(R.string.decision_model_history_turns_summary),
                        items = turnOptions,
                        selectedIndex = nearestIndex(HISTORY_TURN_VALUES, historyTurns.coerceIn(0, 10)),
                        enabled = writable,
                        onItemSelected = { index ->
                            val value = HISTORY_TURN_VALUES[index]
                            Prefs.putInt(Prefs.Keys.AGENT_DECISION_HISTORY_TURNS, value)
                            historyTurns = value
                        },
                    )
                }
                item {
                    // 协议在 M1 只有一个实现；按只读行展示，避免给用户一个点了没反应的菜单。
                    EtaClickableRow(
                        title = stringResource(R.string.decision_model_protocol_title),
                        summary = stringResource(R.string.decision_model_protocol_summary),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Api) },
                        showChevron = false,
                        onClick = null,
                    )
                }
                item {
                    // 「一键放行」= 直接关闭总开关（本实现选这一分支）。
                    // 不新增会话级 override：语义简单、跨进程可预期，且用户能随时重新打开。
                    // 下一轮 run 的 route() 会看到 enabled=false，直接返回 null（全量放行）。
                    EtaClickableRow(
                        title = stringResource(R.string.decision_model_release_title),
                        summary = stringResource(R.string.decision_model_release_summary),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Visibility) },
                        enabled = writable && enabled,
                        onClick = {
                            Prefs.putBoolean(Prefs.Keys.AGENT_DECISION_MODEL_ENABLED, false)
                            enabled = false
                        },
                    )
                }
            }
        }

        item(key = "decision_diagnostics") {
            // 只读诊断：数据来自最近一次 decision.route（内存态），不读日志文件、不触发新请求。
            EtaNoticeText(
                title = stringResource(R.string.decision_model_diagnostics_title),
                summary = diagnosticsSummary
                    ?: stringResource(R.string.decision_model_diagnostics_empty),
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            )
        }

        item(key = "decision_telemetry") {
            // M4：进程内统计，只读；与上一块的「最近一次」并列展示，样式复用既有 EtaNoticeText。
            EtaNoticeText(
                title = stringResource(R.string.decision_model_telemetry_title),
                summary = "$telemetrySummary\n$telemetryReasons",
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            )
        }

        item(key = "decision_cleanup") {
            EtaClickableRow(
                title = stringResource(R.string.decision_model_cleanup_title),
                summary = stringResource(R.string.decision_model_cleanup_summary),
                leading = { PreferenceIcon(icon = Icons.Rounded.DeleteSweep) },
                onClick = {
                    AgentDecisionCache.clear()
                    AgentDecisionStickyStore.clear()
                    AgentDecisionTelemetry.reset()
                    telemetry = AgentDecisionTelemetry.globalSnapshot()
                    Toast.makeText(context, R.string.decision_model_cleanup_done, Toast.LENGTH_SHORT).show()
                },
            )
        }

        item(key = "decision_logs") {
            EtaNoticeText(
                title = stringResource(R.string.decision_model_status_title),
                summary = stringResource(R.string.decision_model_status_summary),
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            )
        }
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
