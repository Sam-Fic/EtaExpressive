package io.github.mangi.eta.ui.screens.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.mangi.eta.agent.mobileactions.MobileActionsEngine
import io.github.mangi.eta.agent.mobileactions.MobileActionsModelCatalog
import io.github.mangi.eta.agent.mobileactions.MobileActionsModelStore
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextField
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.launch

/**
 * 端侧设备动作模型设置页：模型选择、双源下载、令牌、推理后端与试用入口。
 *
 * 下载/校验/删除全部交给 [MobileActionsModelStore]（哈希全量校验 + `.part` 原子落盘），
 * 本页只负责状态呈现与偏好切换。
 */
@Composable
internal fun MobileActionsSettingsScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { MobileActionsModelStore.initialize(context) }
    val state by MobileActionsModelStore.state.collectAsState()
    var backend by remember { mutableStateOf(MobileActionsModelStore.backend(context)) }
    var tokenDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var tokenDraft by remember { mutableStateOf("") }

    val sources = MobileActionsModelCatalog.sources
    val sourceLabels = sources.map { sourceLabel(it.id) }
    val mainGroupPadding = Modifier.padding(
        start = EtaGroupHorizontalPadding,
        end = EtaGroupHorizontalPadding,
        bottom = EtaSegmentedGroupSpacing,
    )

    EtaScaffoldPage(title = stringResource(R.string.mobile_actions_settings_title), onBack = onBack) {
        item(key = "mobile-actions-intro") {
            EtaText(
                text = stringResource(R.string.mobile_actions_intro),
                secondary = true,
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedTitleSpacing,
                ),
            )
        }

        item(key = "mobile-actions-model-group") {
            EtaSegmentedColumn(
                title = stringResource(R.string.mobile_actions_group_model),
                modifier = mainGroupPadding,
            ) {
                item(key = "model") {
                    // 纯信息行：模型规格不可改，副标题给出动作清单。
                    EtaClickableRow(
                        title = stringResource(R.string.mobile_actions_model_title),
                        summary = stringResource(R.string.mobile_actions_model_summary),
                        onClick = null,
                        showChevron = false,
                    )
                }
                item(key = "source") {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.mobile_actions_source_title),
                        items = sourceLabels,
                        selectedIndex = sources.indexOfFirst { it.id == state.source.id }.coerceAtLeast(0),
                        enabled = !state.downloading,
                        onItemSelected = { index ->
                            sources.getOrNull(index)?.let { source ->
                                MobileActionsModelStore.selectSource(context, source.id)
                            }
                        },
                    )
                }
                item(key = "token", visible = state.source.requiresToken) {
                    EtaClickableRow(
                        title = stringResource(R.string.mobile_actions_token_title),
                        summary = if (state.hasToken) {
                            stringResource(R.string.mobile_actions_token_saved)
                        } else {
                            stringResource(R.string.mobile_actions_token_summary)
                        },
                        enabled = !state.downloading,
                        onClick = {
                            tokenDraft = MobileActionsModelStore.token(context)
                            tokenDialog = true
                        },
                    )
                }
                item(key = "download") {
                    EtaClickableRow(
                        // 下载 / 取消合并为一行：标题随状态切换，状态与体积作为副标题。
                        title = stringResource(
                            if (state.downloading) {
                                R.string.mobile_actions_cancel_download
                            } else {
                                R.string.mobile_actions_download
                            },
                        ),
                        summary = modelStatusSummary(state),
                        onClick = {
                            if (state.downloading) {
                                MobileActionsModelStore.cancelDownload()
                            } else {
                                MobileActionsModelStore.download(context)
                            }
                        },
                        showChevron = false,
                        enabled = state.downloading || (!state.checking && !state.ready),
                    )
                }
                item(key = "delete", visible = state.ready) {
                    EtaClickableRow(
                        title = stringResource(R.string.mobile_actions_delete),
                        summary = stringResource(
                            R.string.mobile_actions_delete_summary,
                            MobileActionsModelStore.formatBytes(state.totalBytes),
                        ),
                        onClick = { deleteDialog = true },
                        showChevron = false,
                        destructive = true,
                    )
                }
            }
        }

        item(key = "mobile-actions-inference-group") {
            EtaSegmentedColumn(
                title = stringResource(R.string.mobile_actions_group_inference),
                modifier = mainGroupPadding,
            ) {
                item(key = "backend") {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.mobile_actions_backend_title),
                        summary = stringResource(R.string.mobile_actions_backend_summary),
                        items = listOf(
                            stringResource(R.string.mobile_actions_backend_auto),
                            stringResource(R.string.mobile_actions_backend_gpu),
                            stringResource(R.string.mobile_actions_backend_cpu),
                        ),
                        selectedIndex = BACKENDS.indexOf(backend).coerceAtLeast(0),
                        enabled = !state.downloading,
                        onItemSelected = { index ->
                            BACKENDS.getOrNull(index)?.let { selected ->
                                backend = selected
                                MobileActionsModelStore.setBackend(context, selected)
                                // 后端变更后释放旧引擎：下次运行按新后端重建。
                                scope.launch { MobileActionsEngine.release() }
                            }
                        },
                    )
                }
            }
        }

        item(key = "mobile-actions-open-group") {
            EtaSegmentedColumn(modifier = mainGroupPadding) {
                item(key = "open") {
                    EtaClickableRow(
                        title = stringResource(R.string.mobile_actions_open),
                        summary = stringResource(R.string.mobile_actions_open_summary),
                        enabled = state.ready,
                        onClick = { onNavigate(AppRoute.MobileActions) },
                    )
                }
            }
        }

        item(key = "mobile-actions-note") {
            EtaText(
                text = stringResource(R.string.mobile_actions_note),
                secondary = true,
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    top = 4.dp,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            )
        }
    }

    EtaDialog(
        show = tokenDialog,
        title = stringResource(R.string.mobile_actions_token_dialog_title),
        onDismissRequest = { tokenDialog = false },
        summary = stringResource(R.string.mobile_actions_token_dialog_hint),
        actions = {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_save),
                confirmEnabled = tokenDraft.isNotBlank(),
                onCancel = { tokenDialog = false },
                onConfirm = {
                    MobileActionsModelStore.setToken(context, tokenDraft)
                    tokenDialog = false
                },
            )
        },
    ) {
        EtaTextField(
            value = tokenDraft,
            onValueChange = { tokenDraft = it },
            label = stringResource(R.string.mobile_actions_token_field),
        )
    }

    EtaDialog(
        show = deleteDialog,
        title = stringResource(R.string.action_delete),
        onDismissRequest = { deleteDialog = false },
        summary = stringResource(R.string.mobile_actions_delete_message),
        actions = {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { deleteDialog = false },
                onConfirm = {
                    MobileActionsModelStore.delete(context)
                    // 引擎里还驻留着同一份权重：删文件时一并释放，避免内存白占。
                    scope.launch { MobileActionsEngine.release() }
                    deleteDialog = false
                },
            )
        },
    )
}

/** 后端顺序与 [MobileActionsModelStore.Backend] 声明顺序一致，供下拉行索引映射。 */
private val BACKENDS = listOf(
    MobileActionsModelStore.Backend.AUTO,
    MobileActionsModelStore.Backend.GPU,
    MobileActionsModelStore.Backend.CPU,
)

@Composable
private fun sourceLabel(id: MobileActionsModelCatalog.SourceId): String = when (id) {
    MobileActionsModelCatalog.SourceId.MODEL_SCOPE -> stringResource(R.string.mobile_actions_source_modelscope)
    MobileActionsModelCatalog.SourceId.HUGGING_FACE -> stringResource(R.string.mobile_actions_source_huggingface)
}

/** 下载行的副标题：状态 + 体积 / 进度 / 失败原因。 */
@Composable
private fun modelStatusSummary(state: MobileActionsModelStore.State): String {
    val total = MobileActionsModelStore.formatBytes(state.totalBytes)
    val failure = state.failure?.let { message ->
        stringResource(
            when (message) {
                MobileActionsModelStore.Failure.AUTH -> R.string.mobile_actions_error_auth
                MobileActionsModelStore.Failure.CHECKSUM -> R.string.mobile_actions_error_checksum
                MobileActionsModelStore.Failure.NO_SPACE -> R.string.mobile_actions_error_space
                MobileActionsModelStore.Failure.NETWORK -> R.string.mobile_actions_error_network
            },
        )
    }
    return when {
        state.checking -> "${stringResource(R.string.mobile_actions_status_checking)} · $total"
        state.downloading -> stringResource(
            R.string.mobile_actions_status_downloading,
            MobileActionsModelStore.formatBytes(state.downloadedBytes),
            total,
        )
        state.ready -> stringResource(R.string.mobile_actions_status_ready)
        failure != null -> failure
        else -> "${stringResource(R.string.mobile_actions_status_missing)} · $total"
    }
}
