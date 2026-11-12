package io.github.mangi.eta.ui

import android.content.SharedPreferences
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentCompressionEndpoint
import io.github.mangi.eta.config.AutoCompressPreference
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.rememberAutoCompressEnabled
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 上下文压缩设置页（KernelSU 式分组结构）：
 * ① 自动压缩开关组 ② 压缩模型组（开关 + 条件可见的选择行，M3 下带 spring 收缩动画）
 * ③ 请求接口组。每行带前导图标，两种主题族共用同一信息架构。
 *
 * 本页只做压缩相关配置；「立即压缩当前会话」入口在顶栏溢出菜单的「压缩会话」，
 * 压缩进行中的反馈由聊天流内的压缩指示器与 Toast 承担。
 */
@Composable
internal fun ContextCompressionSettingsScreen(
    onBack: () -> Unit,
) {
    val prefs = Prefs.localAgentPreferences()
    val autoCompressPreference = remember(prefs) { AutoCompressPreference(prefs) }
    val enabled by rememberAutoCompressEnabled(autoCompressPreference)
    var endpointMode by remember {
        mutableStateOf(
            AgentCompressionEndpoint.parse(prefs?.getString(Prefs.Keys.AGENT_COMPRESS_ENDPOINT_MODE, null)),
        )
    }

    val scope = rememberCoroutineScope()
    var selectedCompressModel by remember { mutableStateOf<AgentModelOptionUi?>(null) }
    var customModelEnabled by remember { mutableStateOf(Prefs.isCustomCompressModelEnabled(prefs)) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showMissingWindowDialog by remember { mutableStateOf(false) }
    var modelPickerState by remember { mutableStateOf(AgentModelPickerUiState()) }
    var isLoadingModels by remember { mutableStateOf(prefs != null) }

    LaunchedEffect(prefs) {
        prefs?.let { currentPrefs ->
            endpointMode = AgentCompressionEndpoint.parse(
                currentPrefs.getString(Prefs.Keys.AGENT_COMPRESS_ENDPOINT_MODE, null),
            )
            isLoadingModels = true
            selectedCompressModel = withContext(Dispatchers.IO) { readCompressModelSelection(currentPrefs) }
            modelPickerState = withContext(Dispatchers.IO) { buildCompressModelPickerState(currentPrefs) }
            customModelEnabled = Prefs.isCustomCompressModelEnabled(currentPrefs)
            isLoadingModels = false
        }
    }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                Prefs.Keys.AGENT_COMPRESS_ENDPOINT_MODE -> endpointMode = AgentCompressionEndpoint.parse(prefs?.getString(key, null))
                Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID,
                Prefs.Keys.AGENT_COMPRESS_MODEL_ID -> {
                    prefs?.let { currentPrefs ->
                        scope.launch {
                            selectedCompressModel = withContext(Dispatchers.IO) { readCompressModelSelection(currentPrefs) }
                            modelPickerState = withContext(Dispatchers.IO) { buildCompressModelPickerState(currentPrefs) }
                        }
                    }
                }
                Prefs.Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED -> {
                    customModelEnabled = Prefs.isCustomCompressModelEnabled(prefs)
                }
            }
        }
        prefs?.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs?.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.ui_context_compression_settings_title),
        onBack = onBack,
    ) {
        // ① 自动压缩组
        item(key = "auto_compress") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
            ) {
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.ui_auto_compress_context_title),
                        summary = stringResource(R.string.ui_auto_compress_context_summary),
                        checked = enabled,
                        enabled = autoCompressPreference.isWritable,
                        leading = { PreferenceIcon(icon = Icons.Rounded.Compress) },
                        onCheckedChange = { value ->
                            autoCompressPreference.setEnabled(value)
                        },
                    )
                }
            }
        }

        // ② 压缩模型组：自定义模型开关；开启后条件显示模型选择行
        item(key = "compress_model") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
            ) {
                item {
                EtaSwitchRow(
                    title = stringResource(R.string.ui_custom_compress_model_title),
                    summary = stringResource(R.string.ui_custom_compress_model_summary),
                    summaryOff = stringResource(R.string.ui_custom_compress_model_summary_off),
                    checked = customModelEnabled,
                        leading = { PreferenceIcon(icon = Icons.Rounded.SmartToy) },
                        onCheckedChange = { value ->
                            prefs?.edit()?.putBoolean(
                                Prefs.Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED,
                                value,
                            )?.apply()
                            customModelEnabled = value
                            if (value && selectedCompressModel != null &&
                                !selectedCompressModel.hasCompressContextWindow()
                            ) {
                                showMissingWindowDialog = true
                            }
                        },
                    )
                }

                // visible=false 时不渲染但仍参与首/末圆角计算；M3 下带 spring 收缩动画
                item(visible = customModelEnabled) {
                    EtaClickableRow(
                        title = stringResource(R.string.ui_compress_model_title),
                        summary = selectedCompressModel?.displayName
                            ?: stringResource(R.string.model_not_selected),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Memory) },
                        onClick = {
                            showModelDialog = true
                        },
                    )
                }
            }
        }

        // ③ 请求接口组（说明并入行内 summary）
        item(key = "compress_endpoint") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
            ) {
                item {
                    CompressionEndpointPreference(
                        selected = endpointMode,
                        enabled = prefs != null,
                        onSelect = { option ->
                            endpointMode = option
                            prefs?.edit()?.putString(Prefs.Keys.AGENT_COMPRESS_ENDPOINT_MODE, option)?.apply()
                        },
                    )
                }
            }
        }
    }

    CompressModelPickerDialog(
        state = modelPickerState,
        show = showModelDialog,
        isLoading = isLoadingModels,
        onDismiss = { showModelDialog = false },
        onModelSelected = { providerId, modelId ->
            prefs?.edit()?.apply {
                putString(Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, providerId)
                putString(Prefs.Keys.AGENT_COMPRESS_MODEL_ID, modelId)
            }?.apply()
            if (!modelPickerState.findCompressModel(providerId, modelId).hasCompressContextWindow()) {
                showMissingWindowDialog = true
            }
            scope.launch {
                prefs?.let { currentPrefs ->
                    selectedCompressModel = withContext(Dispatchers.IO) { readCompressModelSelection(currentPrefs) }
                    modelPickerState = withContext(Dispatchers.IO) { buildCompressModelPickerState(currentPrefs) }
                }
            }
            showModelDialog = false
        },
    )

    CompressModelMissingWindowDialog(
        show = showMissingWindowDialog,
        onDismiss = { showMissingWindowDialog = false },
    )
}
