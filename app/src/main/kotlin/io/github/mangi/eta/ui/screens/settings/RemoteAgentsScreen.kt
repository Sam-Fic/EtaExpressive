package io.github.mangi.eta.ui.screens.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus
import io.github.mangi.eta.agent.remote.RemoteProtocols
import io.github.mangi.eta.agent.remote.RemoteServerConfig
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaFloatingToolbarConcentricPadding
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.EtaTooltipIconButton
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.adaptive.etaConnectedColor
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.TopBarIconButton
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.remote.RemoteCoordinator
import io.github.mangi.eta.ui.remote.remoteStatusLabel
import kotlinx.coroutines.launch

/**
 * 远程智能体设置页：局域网 agent 服务器的列表与删除。
 *
 * 配置即全部状态——保存后 [RemoteCoordinator] 监听的 DataStore 流会自动建立/重建连接，
 * 每行的状态点直接读协调器的实时连接状态。新增/编辑是独立子页
 * （[RemoteAgentEditorScreen]，表单 + 协议 + 测试连接一屏铺开），本页只保留
 * 列表、本地网络闸门行与删除确认。
 *
 * 版式与 MCP 服务器页一致：单组分段列表 + 组标题计数，空态用居中的 [ListEmptyState]
 * 承载，新增入口交给右下角 FAB（滚动时收起、回顶展开），列表里不再重复放「添加」行。
 */
@Composable
internal fun RemoteAgentsScreen(
    onBack: () -> Unit,
    coordinator: RemoteCoordinator,
    onOpenEditor: (String?) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val servers = coordinator.servers

    var deleteTarget by remember { mutableStateOf<RemoteServerConfig?>(null) }
    // 多选态与勾选集合：入口在顶栏（Checklist→Close 原位切换），长按行也可进入。
    // 批量删除 = 一次 setRemoteServers 整表覆写（数据层天然批量）。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    // 本地网络闸门：页面顶部常驻一行。它必须能自己刷新——用户去系统设置里改完回来，
    // 页面不能还显示旧的「被拦截」。
    var lanState by remember { mutableStateOf(LocalNetworkPermission.accessState(context)) }
    var lanInfoOpen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpandedState(listState)

    // 授权结果回来后刷新闸门状态。探测流程的「授予后接着跑」在编辑器子页里
    // 有它自己的 launcher（RemoteAgentEditorScreen）。
    val lanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        lanState = LocalNetworkPermission.accessState(context)
    }

    // 从系统设置返回时重读一次：用户完全可能在那边改过。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lanState = LocalNetworkPermission.accessState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun persist(updated: List<RemoteServerConfig>) {
        scope.launch { SettingsDataStore.setRemoteServers(updated) }
    }

    val allSelected = servers.isNotEmpty() && selectedIds.containsAll(servers.map { it.id })

    // 返回键优先退出多选，不退页面。
    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectionMode,
        onBackCompleted = {
            selectionMode = false
            selectedIds = emptySet()
        },
    )

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    EtaScaffoldPage(
        title = stringResource(R.string.route_remote_agents),
        onBack = onBack,
        listState = listState,
        // 多选态隐藏新增入口：批量操作栏占住底部，FAB 让位。
        floatingActionButton = if (!selectionMode) {
            {
                EtaExtendedFab(
                    text = stringResource(R.string.remote_agents_add),
                    icon = Icons.Rounded.Add,
                    expanded = fabExpanded,
                    onClick = { TouchHaptics.click(view); onOpenEditor(null) },
                )
            }
        } else {
            null
        },
        actions = {
            // 多选入口原位切换：Checklist→Close，退出并清空勾选。无服务器可管时禁用。
            TopBarIconButton(
                icon = if (selectionMode) Icons.Rounded.Close else Icons.Rounded.Checklist,
                contentDescription = stringResource(
                    if (selectionMode) R.string.action_cancel else R.string.media_action_multiselect,
                ),
                onClick = {
                    TouchHaptics.click(view)
                    if (selectionMode) exitSelection() else selectionMode = true
                },
                enabled = servers.isNotEmpty() || selectionMode,
                shapes = IconButtonDefaults.shapes(),
            )
        },
    ) {
        // 顶部常驻一行本地网络闸门状态。
        //
        // 之前这个状态只藏在「测试连接」的报告里——而它恰恰是「浏览器打得开、本应用超时」
        // 的唯一正确解释。用户不会为了排查先去跑一次诊断，所以它必须一眼可见。
        item(key = "local-network") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
                title = stringResource(R.string.remote_local_network_group),
            ) {
                item {
                    EtaClickableRow(
                        title = stringResource(R.string.remote_local_network_title),
                        summary = probeLocalNetworkSummary(lanState),
                        leading = { PreferenceIcon(icon = Icons.Rounded.Wifi) },
                        endActions = {
                            EtaText(
                                text = probeLocalNetworkStatus(lanState),
                                style = EtaTextStyle.Body,
                                color = probeLocalNetworkColor(lanState),
                            )
                        },
                        onClick = { lanInfoOpen = true },
                    )
                }
            }
        }
        item(key = "servers") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
                title = if (selectionMode) {
                    stringResource(R.string.remote_agents_configured_servers, servers.size) + " · " +
                        stringResource(R.string.media_selected_count, selectedIds.size)
                } else {
                    stringResource(R.string.remote_agents_configured_servers, servers.size)
                },
            ) {
                if (servers.isEmpty()) {
                    // 空态是「非行」内容，用分段容器承载；新增入口交给右下角 FAB，避免重复按钮。
                    item {
                        EtaSegmentedItemContainer {
                            ListEmptyState(
                                title = stringResource(R.string.remote_agents_empty),
                                summary = stringResource(R.string.remote_agents_empty_summary),
                            )
                        }
                    }
                } else {
                    servers.forEach { config ->
                        item(key = config.id) {
                            val status = coordinator.statusOf(config.id)
                            EtaClickableRow(
                                title = config.name,
                                summary = "${RemoteProtocols.displayNameOf(config.protocolId)} · " +
                                    "${config.host}:${config.port} · ${remoteStatusLabel(status)}",
                                onClick = {
                                    if (selectionMode) {
                                        selectedIds = if (config.id in selectedIds) {
                                            selectedIds - config.id
                                        } else {
                                            selectedIds + config.id
                                        }
                                    } else {
                                        onOpenEditor(config.id)
                                    }
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedIds = setOf(config.id)
                                    }
                                },
                                onLongClickLabel = stringResource(R.string.action_enter_selection),
                                // 行首是「服务器」类别图标（与 MCP 服务器页的 Dns 同族）；
                                // 连接状态属于状态标记，挪到行尾，不与类别图标抢行首槽。
                                leading = {
                                    Icon(
                                        imageVector = Icons.Rounded.Dns,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(EtaDimens.DenseIconSize),
                                    )
                                },
                                endActions = {
                                    if (selectionMode) {
                                        // 多选态：状态点与删除按钮让位，行尾只剩官方 Checkbox。
                                        Checkbox(
                                            checked = config.id in selectedIds,
                                            onCheckedChange = {
                                                selectedIds = if (config.id in selectedIds) {
                                                    selectedIds - config.id
                                                } else {
                                                    selectedIds + config.id
                                                }
                                            },
                                        )
                                    } else {
                                        // 连接状态圆点靠右、删除按钮最右，与「会话列表」尾槽节奏一致。
                                        Box(modifier = Modifier.padding(end = 8.dp)) {
                                            StatusDot(status)
                                        }
                                        // 破坏性删除改成纯图标按钮，与「会话列表」尾槽一致：
                                        // 官方 48dp IconButton + error 内容色 + 统一 Tooltip/无障碍包装。
                                        EtaTooltipIconButton(
                                            icon = Icons.Rounded.DeleteOutline,
                                            contentDescription = stringResource(R.string.action_delete),
                                            onClick = { deleteTarget = config },
                                            destructive = true,
                                            shapes = IconButtonDefaults.shapes(),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        item(key = "footer-note") {
            EtaText(
                text = stringResource(R.string.remote_agents_footer),
                style = EtaTextStyle.Secondary,
                modifier = Modifier.padding(horizontal = EtaGroupHorizontalPadding),
            )
        }
        item(key = "bottom_spacer", contentType = "spacer") {
            // 多选操作栏悬浮在底部时预留高度，避免遮挡最后一个列表项。
            Spacer(
                modifier = Modifier
                    .height(if (selectionMode) 88.dp else 24.dp)
                    .navigationBarsPadding(),
            )
        }
    }

    // 批量操作栏：官方 M3E HorizontalFloatingToolbar 纯图标药丸，底部居中悬浮。
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            // 批量选择栏的进退场：位移走 M3E Spatial（轻微过冲），透明度走 Effects（无过冲）。
            enter = slideInVertically(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            ) { it } + fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = slideOutVertically(
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            ) { it } + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            RemoteAgentSelectionBar(
                selectedCount = selectedIds.size,
                allSelected = allSelected,
                onSelectAll = {
                    TouchHaptics.click(view)
                    selectedIds = if (allSelected) {
                        emptySet()
                    } else {
                        servers.map { it.id }.toSet()
                    }
                },
                onDelete = {
                    TouchHaptics.click(view)
                    showBatchDeleteDialog = true
                },
            )
        }
    }

    // 批量删除二次确认：一次 setRemoteServers 整表覆写即批量删（数据层天然批量）。
    if (showBatchDeleteDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.remote_agents_delete_title),
            summary = stringResource(R.string.remote_agents_batch_delete_message, selectedIds.size),
            onDismissRequest = { showBatchDeleteDialog = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { showBatchDeleteDialog = false },
                    onConfirm = {
                        persist(servers.filterNot { it.id in selectedIds })
                        showBatchDeleteDialog = false
                        exitSelection()
                    },
                )
            },
        ) {}
    }

    deleteTarget?.let { target ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.remote_agents_delete_title),
            summary = stringResource(R.string.remote_agents_delete_message, target.name),
            onDismissRequest = { deleteTarget = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { deleteTarget = null },
                    onConfirm = {
                        persist(servers.filterNot { it.id == target.id })
                        deleteTarget = null
                    },
                )
            },
        ) {}
    }

    // 「本地网络访问」解释弹窗。
    //
    // 为什么要弹窗而不是直接跳系统设置：这个闸门在系统里**可能根本没有开关**——
    // targetSdk < 37 时是真机实测过的形态（权限页里找不到「本地网络」这一项，
    // 但系统照样拦包）。只甩一句「去设置里开」等于把用户送进一个空房间。
    if (lanInfoOpen) {
        val grantable = LocalNetworkPermission.canRequest(lanState)
        val dialogView = LocalView.current
        EtaDialog(
            show = true,
            title = stringResource(R.string.remote_local_network_dialog_title),
            onDismissRequest = { lanInfoOpen = false },
            actions = {
                // 不能运行时申请时（系统已经拒绝过一次）就不摆「授权」——
                // 那时系统不会再弹窗，点了只会没反应。正文里给了「打开权限页」的入口。
                if (grantable) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            TouchHaptics.click(dialogView)
                            lanInfoOpen = false
                            lanPermissionLauncher.launch(LocalNetworkPermission.PERMISSION)
                        },
                    ) {
                        androidx.compose.material3.Text(
                            stringResource(R.string.remote_local_network_action_grant),
                        )
                    }
                }
                androidx.compose.material3.TextButton(
                    onClick = {
                        TouchHaptics.click(dialogView)
                        lanInfoOpen = false
                    },
                ) {
                    androidx.compose.material3.Text(stringResource(R.string.action_close))
                }
            },
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                // 与 EtaDialog 正文槽同一行距档。
                verticalArrangement = Arrangement.spacedBy(EtaDimens.SpaceMd),
            ) {
                EtaText(
                    text = probeLocalNetworkDialogText(lanState),
                    style = EtaTextStyle.Secondary,
                )
                // 这一项在系统里的叫法不统一（小米上是「附近设备」），
                // 只说「本地网络访问」的话用户进去找不到。附上对照说明。
                EtaText(
                    text = stringResource(R.string.remote_local_network_dialog_naming),
                    style = EtaTextStyle.Footnote,
                )
                // 用户可能刚在系统里改完就回来，或者系统刚把它改回去——给一次就地复查。
                EtaTextButton(
                    text = stringResource(R.string.remote_local_network_recheck),
                    onClick = { lanState = LocalNetworkPermission.accessState(context) },
                )
                // 直接打开**本应用的权限列表**（不是应用信息页）：
                // 落地即可看到「附近设备」那一行，少一层、少一次找不着。
                EtaTextButton(
                    text = stringResource(R.string.remote_local_network_action_open_settings),
                    onClick = { openAppPermissionSettings(context) },
                )
            }
        }
    }
}

/**
 * 打开本应用的系统权限页。
 *
 * 优先用 APP_PERMISSIONS_SETTINGS 直接落到**权限列表**（SDK 里没有这个 action 的常量，
 * 只能写字面量；真机上 com.android.permissioncontroller 的 ManagePermissionsActivity
 * 认它）：这项权限在小米/红米上叫「附近设备」、在别处叫「本地网络」，用户从应用信息页进来还得再点一层「权限」，
 * 而且多半不认得那个名字。少一层就少一次找不到。
 * 系统不认这个 action 时退回应用信息页；两条都不通才是真的打不开。
 *
 * 同包的 [RemoteAgentEditorScreen]（诊断报告弹窗）也用它，所以是 internal 而非 private。
 */
internal fun openAppPermissionSettings(context: Context) {
    val packageUri = android.net.Uri.fromParts("package", context.packageName, null)
    val intents = listOf(
        android.content.Intent("android.settings.APP_PERMISSIONS_SETTINGS", packageUri)
            .putExtra("android.intent.extra.PACKAGE_NAME", context.packageName),
        android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            packageUri,
        ),
    )
    for (intent in intents) {
        val opened = runCatching {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) return
    }
}

/** 远程智能体页顶部那行的摘要。 */
@Composable
private fun probeLocalNetworkSummary(state: LocalNetworkPermission.AccessState): String = when (state) {
    LocalNetworkPermission.AccessState.GRANTED ->
        stringResource(R.string.remote_local_network_summary_granted)
    LocalNetworkPermission.AccessState.NOT_GRANTED ->
        stringResource(R.string.remote_local_network_summary_not_granted)
    LocalNetworkPermission.AccessState.DENIED ->
        stringResource(R.string.remote_local_network_summary_denied)
    LocalNetworkPermission.AccessState.NOT_REQUIRED ->
        stringResource(R.string.remote_local_network_summary_not_required)
    LocalNetworkPermission.AccessState.UNKNOWN ->
        stringResource(R.string.remote_local_network_summary_unknown)
}

/** 行尾的状态词。 */
@Composable
private fun probeLocalNetworkStatus(state: LocalNetworkPermission.AccessState): String = when (state) {
    LocalNetworkPermission.AccessState.GRANTED ->
        stringResource(R.string.remote_local_network_status_granted)
    LocalNetworkPermission.AccessState.NOT_GRANTED ->
        stringResource(R.string.remote_local_network_status_not_granted)
    LocalNetworkPermission.AccessState.DENIED ->
        stringResource(R.string.remote_local_network_status_denied)
    LocalNetworkPermission.AccessState.NOT_REQUIRED ->
        stringResource(R.string.remote_local_network_status_not_required)
    LocalNetworkPermission.AccessState.UNKNOWN ->
        stringResource(R.string.remote_local_network_status_unknown)
}

/** 行尾状态词的颜色：只有「会真的连不上」才配用错误色。 */
@Composable
private fun probeLocalNetworkColor(state: LocalNetworkPermission.AccessState): Color = when (state) {
    LocalNetworkPermission.AccessState.GRANTED,
    LocalNetworkPermission.AccessState.NOT_REQUIRED,
    LocalNetworkPermission.AccessState.UNKNOWN,
    -> LocalContentColor.current
    LocalNetworkPermission.AccessState.NOT_GRANTED -> MaterialTheme.colorScheme.primary
    LocalNetworkPermission.AccessState.DENIED -> MaterialTheme.colorScheme.error
}

/** 顶部那行点开后的说明。 */
@Composable
private fun probeLocalNetworkDialogText(state: LocalNetworkPermission.AccessState): String = when (state) {
    LocalNetworkPermission.AccessState.GRANTED ->
        stringResource(R.string.remote_local_network_dialog_granted)
    LocalNetworkPermission.AccessState.NOT_GRANTED ->
        stringResource(R.string.remote_local_network_dialog_not_granted)
    LocalNetworkPermission.AccessState.DENIED ->
        stringResource(R.string.remote_local_network_dialog_denied)
    LocalNetworkPermission.AccessState.NOT_REQUIRED ->
        stringResource(R.string.remote_local_network_dialog_not_required)
    LocalNetworkPermission.AccessState.UNKNOWN ->
        stringResource(R.string.remote_local_network_dialog_unknown)
}

@Composable
private fun StatusDot(status: RemoteConnectionStatus) {
    val color: Color = when (status) {
        // 已连接与抽屉、分类页共用同一成功语义绿，避免三处各写一种绿色。
        RemoteConnectionStatus.Connected -> etaConnectedColor()
        RemoteConnectionStatus.Connecting, is RemoteConnectionStatus.Reconnecting ->
            MaterialTheme.colorScheme.tertiary
        is RemoteConnectionStatus.Failed -> MaterialTheme.colorScheme.error
        RemoteConnectionStatus.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(EtaDimens.RemoteStatusDotSize)
            .clip(CircleShape)
            .background(color),
    )
}


/**
 * 远程设备多选批量操作栏：官方 M3E [HorizontalFloatingToolbar]（纯图标药丸），底部居中悬浮，
 * 与模型管理页（ProviderModelsTab.ModelSelectionBar）同款装配。
 * 动作序：全选（全不选）→ 删除（error 色标破坏性，执行前另有确认弹窗）；
 * 「取消」不落底栏，由顶栏多选入口原位切换（Checklist→Close）承载，已选数量并入分组标题。
 * 组件结论同 ChatSelectionBar 的 KDoc（同版本依赖既有调用核证）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RemoteAgentSelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            leadingContent = {},
            // 端盖同心：首末两枚 40dp 圆钮与药丸端头弧心对齐（环隙与上下等宽）。
            contentPadding = EtaFloatingToolbarConcentricPadding(
                IconButtonDefaults.smallContainerSize().width,
            ),
        ) {
            // 动作组：固定 8dp 间距的一行图标按钮（EtaIconButton 的 `Standard` 档裸图标）。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EtaIconButton(
                    icon = if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                    contentDescription = stringResource(
                        if (allSelected) R.string.media_action_deselect_all else R.string.media_action_select_all,
                    ),
                    onClick = onSelectAll,
                    size = EtaIconSize.S,
                    style = EtaButtonStyle.Standard,
                )
                // 删除是破坏性操作：图标用 error 色区分（官方裸 IconButton，容器/图标尺寸
                // 与 EtaIconButton 的 `Standard` + S 档一致），执行前另有确认弹窗。
                val deleteDescription = stringResource(R.string.action_delete)
                EtaTooltip(text = deleteDescription) {
                    androidx.compose.material3.IconButton(
                        onClick = onDelete,
                        enabled = selectedCount > 0,
                        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = deleteDescription,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                        )
                    }
                }
            }
        }
    }
}
