package io.github.mangi.eta.ui

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRadioRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

@Composable
internal fun ErrorReconnectSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val policyFlow = remember { SettingsDataStore.errorReconnectPolicyFlow().map { it as ErrorReconnectPolicy? } }
    val loadedPolicy by policyFlow.collectAsState(initial = null)
    val policy = loadedPolicy ?: ErrorReconnectPolicy.DEFAULT
    var saving by remember { mutableStateOf(false) }

    EtaScaffoldPage(
        title = stringResource(R.string.error_reconnect_title),
        onBack = onBack,
    ) {
        item(key = "error_reconnect_policy") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
                title = stringResource(R.string.error_reconnect_duration),
            ) {
                ErrorReconnectPolicyOptions(
                    selectedPolicy = policy,
                    enabled = loadedPolicy != null && !saving,
                    onSelect = { selected ->
                        if (loadedPolicy != null && !saving && selected != loadedPolicy) {
                            saving = true
                            scope.launch {
                                try {
                                    SettingsDataStore.setErrorReconnectPolicy(selected)
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    Toast.makeText(
                                        context,
                                        R.string.error_reconnect_save_failed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * 重连策略选项组：注册进分段列，每个选项独立一条目，自行拿分段形状
 * （KernelSU `SegmentedRadioItem` 式的单选列表）。
 *
 * Material 3：前导 RadioButton + 标题 + 摘要说明，整行可选。
 *
 * A single global policy: no provider/model selector or per-model configuration.
 */
internal fun EtaSegmentedScope.ErrorReconnectPolicyOptions(
    selectedPolicy: ErrorReconnectPolicy,
    onSelect: (ErrorReconnectPolicy) -> Unit,
    enabled: Boolean = true,
) {
    ErrorReconnectPolicy.entries.forEach { policy ->
        item(key = policy.name) {
            EtaSegmentedRadioRow(
                title = stringResource(errorReconnectPolicyLabel(policy)),
                summary = stringResource(errorReconnectPolicyHint(policy)),
                selected = selectedPolicy == policy,
                enabled = enabled,
                onClick = {
                    onSelect(policy)
                },
            )
        }
    }
}

@StringRes
internal fun errorReconnectPolicyLabel(policy: ErrorReconnectPolicy): Int = when (policy) {
    ErrorReconnectPolicy.NONE -> R.string.error_reconnect_none
    ErrorReconnectPolicy.WINDOW_30S -> R.string.error_reconnect_window_30s
    ErrorReconnectPolicy.WINDOW_1M -> R.string.error_reconnect_window_1m
    ErrorReconnectPolicy.WINDOW_5M -> R.string.error_reconnect_window_5m
    ErrorReconnectPolicy.CONTINUOUS -> R.string.error_reconnect_continuous
}

@StringRes
private fun errorReconnectPolicyHint(policy: ErrorReconnectPolicy): Int = when (policy) {
    ErrorReconnectPolicy.NONE -> R.string.error_reconnect_hint_none
    ErrorReconnectPolicy.WINDOW_30S -> R.string.error_reconnect_hint_30s
    ErrorReconnectPolicy.WINDOW_1M -> R.string.error_reconnect_hint_1m
    ErrorReconnectPolicy.WINDOW_5M -> R.string.error_reconnect_hint_5m
    ErrorReconnectPolicy.CONTINUOUS -> R.string.error_reconnect_hint_continuous
}
