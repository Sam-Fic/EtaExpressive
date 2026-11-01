package io.github.mangi.eta.ui.screens.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Wifi
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
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.agent.remote.RemoteConnectionProbe
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus
import io.github.mangi.eta.agent.remote.RemoteProtocols
import io.github.mangi.eta.agent.remote.RemoteServerAddress
import io.github.mangi.eta.agent.remote.RemoteServerConfig
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaTextField
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.remote.RemoteCoordinator
import io.github.mangi.eta.ui.remote.remoteFailureText
import io.github.mangi.eta.ui.remote.remoteStatusLabel
import kotlinx.coroutines.launch

/**
 * 远程智能体设置页：局域网 agent 服务器的增删改。
 *
 * 配置即全部状态——保存后 [RemoteCoordinator] 监听的 DataStore 流会自动建立/重建连接，
 * 每行的状态点直接读协调器的实时连接状态，无需单独的「测试连接」按钮。
 * 页面底部明示 LAN 明文风险：令牌只拦陌生连接，不加密流量。
 *
 * 版式与 MCP 服务器页一致：单组分段列表 + 组标题计数，空态用居中的 [ListEmptyState]
 * 承载，新增入口交给右下角 FAB（滚动时收起、回顶展开），列表里不再重复放「添加」行。
 */
@Composable
internal fun RemoteAgentsScreen(
    onBack: () -> Unit,
    coordinator: RemoteCoordinator,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val servers = coordinator.servers

    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    // 协议（远程智能体类型）：存在配置里的是稳定 id，不是界面上的名字。
    var selectedProtocolId by remember { mutableStateOf(RemoteProtocols.DEFAULT_ID) }
    var protocolPickerOpen by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<RemoteServerConfig?>(null) }
    var probe by remember { mutableStateOf<ProbeUiState>(ProbeUiState.Idle) }

    // 本地网络闸门：页面顶部常驻一行。它必须能自己刷新——用户去系统设置里改完回来，
    // 页面不能还显示旧的「被拦截」。
    var lanState by remember { mutableStateOf(LocalNetworkPermission.accessState(context)) }
    var lanInfoOpen by remember { mutableStateOf(false) }
    /** 权限申请被打断的那次探测参数；授予后接着跑，不用用户再点一遍。 */
    var pendingProbe by remember { mutableStateOf<Triple<String, Int, String>?>(null) }

    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpandedState(listState)

    /**
     * 「地址」输入的实时解析结果。
     *
     * 用户手里通常是 pi-remote 给的完整 WebUI 链接（带 `?token=`），直接粘进
     * 主机字段会被拼成 `ws://http://…` 这种废地址——主机名被当成 "http"，
     * 于是永远连不上，界面上还只是一句没有原因的「连接失败」。这里边输边解析，
     * 把端口和令牌自动回填，用户不必自己拆链接。
     */
    val parsedAddress = RemoteServerAddress.parse(host)

    fun openNew() {
        editingId = null
        name = ""
        host = ""
        port = "8800"
        token = ""
        selectedProtocolId = RemoteProtocols.DEFAULT_ID
        formError = null
        probe = ProbeUiState.Idle
        editorOpen = true
    }

    fun openEdit(config: RemoteServerConfig) {
        editingId = config.id
        name = config.name
        host = config.host
        port = config.port.toString()
        token = config.authToken
        selectedProtocolId = config.protocolId
        formError = null
        probe = ProbeUiState.Idle
        editorOpen = true
    }

    /** 地址字段变化：能解析出端口/令牌就顺手回填，其余保持用户填的原样。 */
    fun onAddressChanged(raw: String) {
        host = raw
        formError = null
        probe = ProbeUiState.Idle
        val parsed = RemoteServerAddress.parse(raw) ?: return
        parsed.port?.let { port = it.toString() }
        parsed.token?.let { token = it }
    }

    /** 真正跑探测，只认参数，不管权限。 */
    val runProbeNow: suspend (Triple<String, Int, String>) -> Unit = { (h, p, t) ->
        probe = ProbeUiState.Running
        probe = ProbeUiState.Done(RemoteConnectionProbe.run(context, h, p, t))
    }

    /**
     * 授权结果回来：先刷新闸门状态，再接着跑被打断的那次探测。
     *
     * 被拒时**不跑**——那样只会产出一份和「服务器不回应」无法区分的超时报告，
     * 用户会照着它去查电脑那一侧，方向全错。
     */
    val lanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        lanState = LocalNetworkPermission.accessState(context)
        val args = pendingProbe
        pendingProbe = null
        if (args != null && !LocalNetworkPermission.blocksConnection(lanState)) {
            scope.launch { runProbeNow(args) }
        }
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

    /**
     * 跑一次分层连接诊断。
     *
     * 「连不上」本身没有信息量：这个按钮把链路拆成 TCP → HTTP → WebSocket 三层，
     * 逐层告诉用户断在哪一环，并额外做一次「强制走 Wi-Fi 源地址」的对照——
     * 默认路由不通、绑 Wi-Fi 就通时，就能当场认定是系统把应用流量切到了移动数据。
     */
    fun runProbe() {
        val address = RemoteServerAddress.parse(host)
        if (address == null) {
            formError = context.getString(
                if (host.isBlank()) R.string.remote_agents_error_host
                else R.string.remote_agents_error_host_invalid,
            )
            return
        }
        val portNumber = port.trim().toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) {
            formError = context.getString(R.string.remote_agents_error_port)
            return
        }
        val effectiveToken = token.trim().ifEmpty { address.token.orEmpty() }
        formError = null
        val args = Triple(address.host, portNumber, effectiveToken)

        // 目标是局域网、权限又还没授予：先把授权要过来，授予后自动接着测。
        // 不这么做的话，用户看到的是一次毫无意义的「连接超时」，
        // 而真正该做的是在系统弹窗上点一下允许。
        val state = LocalNetworkPermission.accessState(context)
        lanState = state
        if (LocalNetworkPermission.canRequest(state) && LocalNetworkPermission.isLanAddress(address.host)) {
            pendingProbe = args
            lanPermissionLauncher.launch(LocalNetworkPermission.PERMISSION)
            return
        }
        scope.launch { runProbeNow(args) }
    }

    fun persist(updated: List<RemoteServerConfig>) {
        scope.launch { SettingsDataStore.setRemoteServers(updated) }
    }

    fun save() {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            formError = context.getString(R.string.remote_agents_error_name)
            return
        }
        val address = RemoteServerAddress.parse(host)
        if (address == null) {
            formError = context.getString(
                if (host.isBlank()) R.string.remote_agents_error_host
                else R.string.remote_agents_error_host_invalid,
            )
            return
        }
        val portNumber = port.trim().toIntOrNull()
        if (portNumber == null || portNumber !in 1..65535) {
            formError = context.getString(R.string.remote_agents_error_port)
            return
        }
        val id = editingId ?: java.util.UUID.randomUUID().toString()
        val config = RemoteServerConfig(
            id = id,
            name = trimmedName,
            // 存解析后的裸主机名，别把用户粘进来的整条链接当主机名存下去。
            host = address.host,
            port = portNumber,
            // 令牌输入框优先；没填就用链接里带的那个。
            authToken = token.trim().ifEmpty { address.token.orEmpty() },
            // 老配置升上来时 protocolId 已经是默认值；编辑时不认识的 id 也照原样存回去，
            // 不能偷偷改写成 pi——那会让「这台服务器本版本不支持」变成「连不上」。
            protocolId = selectedProtocolId,
        )
        val updated = if (editingId == null) {
            servers + config
        } else {
            servers.map { if (it.id == editingId) config else it }
        }
        persist(updated)
        editorOpen = false
    }

    EtaScaffoldPage(
        title = stringResource(R.string.route_remote_agents),
        onBack = onBack,
        listState = listState,
        floatingActionButton = {
            EtaExtendedFab(
                text = stringResource(R.string.remote_agents_add),
                icon = Icons.Rounded.Add,
                expanded = fabExpanded,
                onClick = { TouchHaptics.click(view); openNew() },
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
                title = stringResource(R.string.remote_agents_configured_servers, servers.size),
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
                                onClick = { openEdit(config) },
                                leading = { StatusDot(status) },
                                endActions = {
                                    EtaTextButton(
                                        text = stringResource(R.string.action_delete),
                                        destructive = true,
                                        onClick = { deleteTarget = config },
                                    )
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
    }

    if (editorOpen) {
        EtaDialog(
            show = true,
            title = stringResource(
                if (editingId == null) R.string.remote_agents_new_title else R.string.remote_agents_edit_title,
            ),
            onDismissRequest = { editorOpen = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_save),
                    onCancel = { editorOpen = false },
                    onConfirm = { save() },
                )
            },
        ) {
            EtaTextField(
                value = name,
                onValueChange = { name = it; formError = null },
                label = stringResource(R.string.remote_agents_field_name),
            )
            EtaTextField(
                value = host,
                onValueChange = { onAddressChanged(it) },
                label = stringResource(R.string.remote_agents_field_host),
            )
            EtaText(
                text = stringResource(R.string.remote_agents_field_host_hint),
                style = EtaTextStyle.Footnote,
            )
            endpointPreview(parsedAddress, port, token)?.let { endpoint ->
                EtaText(
                    text = stringResource(R.string.remote_agents_resolved_endpoint, endpoint),
                    style = EtaTextStyle.Footnote,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            EtaTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit); formError = null },
                label = stringResource(R.string.remote_agents_field_port),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            EtaTextField(
                value = token,
                onValueChange = { token = it; formError = null },
                label = stringResource(R.string.remote_agents_field_token),
            )
            // 协议行：只有一个已适配协议时是一条静态信息（点开只会看到一行，白点一次）；
            // 等第二个协议接进 RemoteProtocolRegistry，这里自动变成可点的选择器。
            // 当前配置的协议不认识时也要能点——那是用户唯一的改法。
            val protocolOptions = RemoteProtocols.selectable()
            val protocolKnown = protocolOptions.any { it.id == selectedProtocolId }
            val protocolLabel = stringResource(R.string.remote_agents_field_protocol) +
                " · " + RemoteProtocols.displayNameOf(selectedProtocolId)
            if (protocolOptions.size > 1 || !protocolKnown) {
                EtaTextButton(
                    text = protocolLabel,
                    onClick = { protocolPickerOpen = true },
                )
            } else {
                EtaText(text = protocolLabel, style = EtaTextStyle.Secondary)
            }
            if (!protocolKnown) {
                EtaText(
                    text = stringResource(R.string.remote_agents_protocol_unsupported, selectedProtocolId),
                    style = EtaTextStyle.Footnote,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            EtaTextButton(
                text = stringResource(
                    if (probe is ProbeUiState.Running) R.string.remote_probe_running
                    else R.string.remote_probe_test,
                ),
                enabled = probe !is ProbeUiState.Running,
                onClick = { runProbe() },
            )
            formError?.let { error ->
                EtaText(
                    text = error,
                    style = EtaTextStyle.Footnote,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    // 协议选择：只列真的能连的协议（selectable 已按注册表过滤），
    // 免得出现一个点了还是连不上的选项。
    if (protocolPickerOpen) {
        val pickerView = LocalView.current
        EtaDialog(
            show = true,
            title = stringResource(R.string.remote_agents_protocol_title),
            onDismissRequest = { protocolPickerOpen = false },
            actions = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        TouchHaptics.click(pickerView)
                        protocolPickerOpen = false
                    },
                ) {
                    androidx.compose.material3.Text(stringResource(R.string.action_close))
                }
            },
        ) {
            EtaSegmentedColumn {
                RemoteProtocols.selectable().forEach { option ->
                    item(key = option.id) {
                        EtaSegmentedRow(
                            selected = option.id == selectedProtocolId,
                            choiceRow = true,
                            onClick = {
                                selectedProtocolId = option.id
                                formError = null
                                protocolPickerOpen = false
                            },
                            content = {
                                androidx.compose.material3.Text(
                                    text = option.displayName,
                                )
                            },
                        )
                    }
                }
            }
        }
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

    // 诊断结果单独开一个弹窗：编辑框本来就塞了五个字段，再塞十来行结果会被裁掉。
    val probeReport = (probe as? ProbeUiState.Done)?.report
    if (probeReport != null) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.remote_probe_title),
            onDismissRequest = { probe = ProbeUiState.Idle },
            actions = {
                // 只有「关闭」一个动作：结果是一次性的只读报告，
                // 摆两个功能相同的按钮（取消/关闭）只会让人犹豫点哪个。
                val view = LocalView.current
                // 唯一值得多给一个按钮的情况：系统拦了本地网络。
                // 那时用户必须去系统设置里放行，光看文字还得自己找菜单。
                if (probeReport.lanBlockedBySystem) {
                    val context = LocalContext.current
                    androidx.compose.material3.TextButton(
                        onClick = {
                            TouchHaptics.click(view)
                            openAppPermissionSettings(context)
                        },
                    ) {
                        androidx.compose.material3.Text(stringResource(R.string.remote_probe_open_settings))
                    }
                }
                androidx.compose.material3.TextButton(
                    onClick = {
                        TouchHaptics.click(view)
                        probe = ProbeUiState.Idle
                    },
                ) {
                    androidx.compose.material3.Text(stringResource(R.string.action_close))
                }
            },
        ) {
            // 结果有十几行，手机上一屏放不下；限高 + 可滚动，
            // 免得 AlertDialog 把最后几行（恰恰是排查建议）裁掉。
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                // 与 EtaDialog 正文槽的行距保持一致：现在整份报告是它的一个子项，
                // 拿不到外层那个 spacedBy(12.dp) 了，得自己补上。
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProbeReport(probeReport)
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
 */
private fun openAppPermissionSettings(context: Context) {
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

/**
 * 诊断状态。
 *
 * 只在这一个弹窗里用：诊断是**临时**的，不进配置、不进协调器——
 * 用户测完要知道的是「地址对不对、卡在哪一层」，而不是多一条服务器记录。
 */
private sealed interface ProbeUiState {
    data object Idle : ProbeUiState
    data object Running : ProbeUiState
    data class Done(val report: RemoteConnectionProbe.Report) : ProbeUiState
}

/**
 * 诊断结果。
 *
 * 结论在最上面（用户先看结论），下面是逐层明细，最后是排查清单。
 * 「默认路由不通、强制走 Wi-Fi 就通」这一条单独拎出来——它是「系统把应用流量
 * 切到移动数据」的指纹，也是唯一能当场自证的一处。
 */
@Composable
private fun ProbeReport(report: RemoteConnectionProbe.Report) {
    val tcpLabel = stringResource(R.string.remote_probe_step_tcp)
    val tcpWifiLabel = stringResource(R.string.remote_probe_step_tcp_wifi)
    val httpLabel = stringResource(R.string.remote_probe_step_http)
    val wsLabel = stringResource(R.string.remote_probe_step_ws)

    val rows = buildList<Pair<String, RemoteConnectionProbe.Step>> {
        add(tcpLabel to report.tcp)
        report.tcpViaWifi?.let { add(tcpWifiLabel to it) }
        // 前一层就断了的话 HTTP/WS 一定是「未执行」，写出来只占位置、挡视线。
        if (report.http !is RemoteConnectionProbe.Step.Skipped) add(httpLabel to report.http)
        if (report.websocket !is RemoteConnectionProbe.Step.Skipped) add(wsLabel to report.websocket)
    }
    val firstFailed = rows.firstOrNull { it.second is RemoteConnectionProbe.Step.Failed }

    // 结论的优先级就是排查顺序：
    //   ⓪ 系统不许本应用访问局域网 → 包在本机就被吞了，下面全部无从谈起（必须排第一）；
    //   ① 换 Wi-Fi 才通 → 路由问题；
    //   ② 内核把这条连接从别的网段发出去了 → 被 VPN/移动数据改道，包压根没往服务器走；
    //   ③ ARP 解析到 MAC → 机器就在局域网里，包送到了它网卡上，是它自己没应答；
    //   ④ ARP 解析不到 → 这个 IP 在局域网里没人认领；
    //   ⑤ 发对了（源地址与服务器同网段）却没回应 → 链路或对端；
    //   ⑥ 其余情况退回到「断在哪一层」。
    val conclusion = when {
        // 实测：被系统拦时 socket 停在 SYN-SENT、线路上一个包都没有，
        // 于是网关照样有应答、ARP 照样读不到——所有下游判据都会指向「服务器不回应」。
        // 这一条必须压在最上面，否则整份报告会把人引去电脑那侧白抓半天包。
        report.lanBlockedBySystem -> stringResource(R.string.remote_probe_conclusion_lan_blocked)
        report.wifiRouteWorkaround -> stringResource(R.string.remote_probe_conclusion_wifi)
        firstFailed == null -> stringResource(R.string.remote_probe_conclusion_ok)
        report.routeDiverted -> stringResource(
            R.string.remote_probe_conclusion_diverted,
            (report.serverRoute as? RemoteConnectionProbe.Route.Chosen)?.source ?: "—",
            report.serverHost,
        )
        report.hostArp == RemoteConnectionProbe.Arp.Complete -> stringResource(
            R.string.remote_probe_conclusion_host_on_lan,
            report.serverHost,
            report.serverPort,
        )
        report.hostArp == RemoteConnectionProbe.Arp.Incomplete ||
            report.hostArp == RemoteConnectionProbe.Arp.Missing -> stringResource(
            R.string.remote_probe_conclusion_host_missing,
            report.serverHost,
        )
        report.lanFineHostSilent -> stringResource(
            R.string.remote_probe_conclusion_host_silent,
            report.serverHost,
            report.serverPort,
        )
        report.brokenAtTcp && report.gatewayReach == RemoteConnectionProbe.Reach.Silent ->
            stringResource(R.string.remote_probe_conclusion_lan_broken)
        else -> stringResource(
            R.string.remote_probe_conclusion_failed,
            firstFailed.first,
            remoteFailureText((firstFailed.second as RemoteConnectionProbe.Step.Failed).kind),
        )
    }

    EtaText(
        text = conclusion,
        style = EtaTextStyle.Body,
        color = if (report.wifiRouteWorkaround || firstFailed == null) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
    )
    // 逐层明细紧跟在结论下面。这四行才是「断在哪一层」，之前排在环境信息之后，
    // 结果最关键的「强制走 Wi-Fi 对照」被挤到折叠线以下，用户以为它压根没跑。
    rows.forEach { (label, step) ->
        EtaText(text = probeStepText(label, step), style = EtaTextStyle.Footnote)
    }
    // 系统闸门必须紧跟明细：它一旦是「被拦截」，下面那些「网关有应答 / ARP 读不到」
    // 全都不再有意义——继续往下读只会把人往错的方向带。
    EtaText(
        text = probeLocalNetworkText(report.localNetwork),
        style = EtaTextStyle.Footnote,
        color = if (report.lanBlockedBySystem) {
            MaterialTheme.colorScheme.error
        } else {
            LocalContentColor.current
        },
    )
    // 出口路径：内核实际选中的源地址。这一行把「包到底有没有朝服务器发」变成观测，
    // 而不是让用户在 VPN / 移动数据 / 防火墙之间瞎猜。
    EtaText(text = routeLine(report), style = EtaTextStyle.Footnote)
    // 以下两行只在 TCP 没通时才有意义：TCP 通了就已经证明路是通的，
    // 此时再断言「网关有应答」属于推断而非观测，不写上去。
    if (report.brokenAtTcp) {
        EtaText(text = probeGatewayText(report), style = EtaTextStyle.Footnote)
        EtaText(text = probeArpText(report.hostArp, report.serverHost), style = EtaTextStyle.Footnote)
    }
    // 环境信息放最后：它是背景，不是结论。
    EtaText(
        text = stringResource(R.string.remote_probe_network, probeTransportText(report.transport)),
        style = EtaTextStyle.Footnote,
    )
    if (report.vpnActive) {
        EtaText(text = stringResource(R.string.remote_probe_vpn_active), style = EtaTextStyle.Footnote)
    }
    // 网卡行已经带着 IP，再单列一行「本机 IPv4」纯属重复占位。
    EtaText(
        text = stringResource(
            R.string.remote_probe_interfaces,
            report.interfaces.joinToString(", ").ifEmpty { "—" },
        ),
        style = EtaTextStyle.Footnote,
    )
    EtaText(
        text = stringResource(R.string.remote_probe_server, report.serverHost, report.serverPort),
        style = EtaTextStyle.Footnote,
    )
    EtaText(
        text = when (report.sameSubnet) {
            true -> stringResource(R.string.remote_probe_same_subnet_yes)
            false -> stringResource(R.string.remote_probe_same_subnet_no)
            null -> stringResource(R.string.remote_probe_same_subnet_unknown)
        },
        style = EtaTextStyle.Footnote,
    )
    EtaText(
        text = stringResource(R.string.remote_probe_checklist),
        style = EtaTextStyle.Footnote,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 出口路径那一行。
 *
 * 内核为这条连接选中的源地址是**观测**，不是推断：它直接说明包打算从哪个网卡出去。
 * 与服务器同网段就排除了「流量被改道」，不同网段就基本可以认定被改道了。
 */
@Composable
private fun routeLine(report: RemoteConnectionProbe.Report): String =
    when (val route = report.serverRoute) {
        is RemoteConnectionProbe.Route.Chosen -> stringResource(
            R.string.remote_probe_route_chosen,
            route.iface ?: "—",
            route.source,
        )
        is RemoteConnectionProbe.Route.Unavailable -> stringResource(
            R.string.remote_probe_route_unavailable,
            route.detail,
        )
    }

/**
 * ARP 那一行。
 *
 * 这是整份诊断里最硬的一条：防火墙丢的是 IP 包，ARP 在链路层，机器活着就一定会回。
 * 所以「有 MAC 却没回应」和「没有 MAC」把排查方向指向完全相反的两边。
 * 读不到 ARP 表时**照样写出来**——消失了用户会以为这一条没测，而不是测不了。
 */
@Composable
private fun probeArpText(arp: RemoteConnectionProbe.Arp, host: String): String = when (arp) {
    RemoteConnectionProbe.Arp.Complete -> stringResource(R.string.remote_probe_arp_complete, host)
    RemoteConnectionProbe.Arp.Incomplete -> stringResource(R.string.remote_probe_arp_incomplete, host)
    RemoteConnectionProbe.Arp.Missing -> stringResource(R.string.remote_probe_arp_missing, host)
    RemoteConnectionProbe.Arp.Unknown -> stringResource(R.string.remote_probe_arp_unknown, host)
}

/**
 * 系统闸门那一行：Android 16+ 是否允许本应用访问局域网。
 *
 * 这一行是「浏览器打得开、本应用超时」的**唯一正确解释**：
 * 被拦时包在本机协议栈里就被丢掉，路线、网关、对端全都正常，
 * 所以任何往对端查的方向都是死路。
 */
@Composable
private fun probeLocalNetworkText(state: LocalNetworkPermission.AccessState): String = when (state) {
    LocalNetworkPermission.AccessState.GRANTED ->
        stringResource(R.string.remote_probe_local_network_granted)
    LocalNetworkPermission.AccessState.NOT_GRANTED ->
        stringResource(R.string.remote_probe_local_network_not_granted)
    LocalNetworkPermission.AccessState.DENIED ->
        stringResource(R.string.remote_probe_local_network_denied)
    LocalNetworkPermission.AccessState.NOT_REQUIRED ->
        stringResource(R.string.remote_probe_local_network_not_required)
    LocalNetworkPermission.AccessState.UNKNOWN ->
        stringResource(R.string.remote_probe_local_network_unknown)
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

/** 活动网络类型 → 本地化名称。 */
@Composable
private fun probeTransportText(transport: RemoteConnectionProbe.Transport): String = when (transport) {    RemoteConnectionProbe.Transport.Wifi -> stringResource(R.string.remote_probe_network_wifi)
    RemoteConnectionProbe.Transport.Cellular -> stringResource(R.string.remote_probe_network_cellular)
    RemoteConnectionProbe.Transport.Ethernet -> stringResource(R.string.remote_probe_network_ethernet)
    RemoteConnectionProbe.Transport.Vpn -> stringResource(R.string.remote_probe_network_vpn)
    RemoteConnectionProbe.Transport.Other -> stringResource(R.string.remote_probe_network_other)
    RemoteConnectionProbe.Transport.None -> stringResource(R.string.remote_probe_network_none)
}

/**
 * 网关那一行。
 *
 * 「有应答 / 无应答」是整份诊断里最能定性的一行：
 * 有应答 = 局域网这条路本身是通的，问题在电脑那一侧。
 */
@Composable
private fun probeGatewayText(report: RemoteConnectionProbe.Report): String {
    val gateway = report.gateway
    return when {
        gateway.isNullOrBlank() -> stringResource(R.string.remote_probe_gateway_unknown)
        report.gatewayReach == RemoteConnectionProbe.Reach.Reachable ->
            stringResource(R.string.remote_probe_gateway_reachable, gateway)
        else -> stringResource(R.string.remote_probe_gateway_silent, gateway)
    }
}

/** 单层结果 → 一行文字。 */
@Composable
private fun probeStepText(
    label: String,
    step: RemoteConnectionProbe.Step,
): String = when (step) {
    is RemoteConnectionProbe.Step.Ok -> stringResource(R.string.remote_probe_step_ok, label)
    is RemoteConnectionProbe.Step.Failed -> stringResource(
        R.string.remote_probe_step_failed,
        label,
        remoteFailureText(step.kind, step.detail),
    )
    is RemoteConnectionProbe.Step.Skipped -> stringResource(R.string.remote_probe_step_skipped, label)
}

/**
 * 展示最终会连的地址，让用户确认「我填的确实被理解成了这个」。
 * 令牌只显示占位符——真正的值在令牌输入框里已经能看见。
 */
private fun endpointPreview(
    address: RemoteServerAddress.Parsed?,
    port: String,
    token: String,
): String? {
    val parsed = address ?: return null
    val portNumber = port.trim().toIntOrNull() ?: return null
    val host = if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host
    val suffix = if (token.trim().isNotEmpty()) "?token=****" else ""
    return "ws://$host:$portNumber/ws$suffix"
}

@Composable
private fun StatusDot(status: RemoteConnectionStatus) {
    val color: Color = when (status) {
        RemoteConnectionStatus.Connected -> MaterialTheme.colorScheme.primary
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
