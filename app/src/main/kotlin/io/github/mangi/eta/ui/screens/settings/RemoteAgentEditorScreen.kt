package io.github.mangi.eta.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import io.github.mangi.eta.agent.remote.RemoteProtocols
import io.github.mangi.eta.agent.remote.RemoteServerAddress
import io.github.mangi.eta.agent.remote.RemoteServerConfig
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextField
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.TopBarIconButton
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.remote.RemoteCoordinator
import io.github.mangi.eta.ui.remote.remoteFailureText
import kotlinx.coroutines.launch

/**
 * 远程服务器编辑子页（原「添加/编辑」弹窗的页面化）。
 *
 * 路由 [io.github.mangi.eta.ui.navigation.AppRoute.RemoteAgentEditor]：[serverId]
 * 为 null 是新建，否则编辑既有配置。弹窗时期每个字段挤在一层、协议选择器和诊断
 * 报告又各占一层弹窗；页面化之后表单、协议、诊断一屏铺开。
 *
 * 保存即返回（对话框语义的保留）：校验通过后写回 [SettingsDataStore] 的全量列表，
 * [RemoteCoordinator] 监听同一条流自动重建连接，然后出栈。保存动作等写入完成才
 * 返回——rememberCoroutineScope 绑定本页组合，先出栈再写会被取消。
 *
 * 诊断（测试连接）整体从列表页搬来：分层报告、LAN 权限申请与「授予后接着跑」的
 * 逻辑不变；列表页保留它自己的闸门行与授权入口（openAppPermissionSettings 与
 * 本文件同包共用）。
 */
@Composable
internal fun RemoteAgentEditorScreen(
    serverId: String?,
    onBack: () -> Unit,
    coordinator: RemoteCoordinator,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val servers = coordinator.servers

    // 新建模式开局即视为已灌值（默认端口 8800）；编辑模式等镜像列表回放到位后灌一次。
    var seeded by rememberSaveable { mutableStateOf(serverId == null) }
    var name by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("8800") }
    var token by rememberSaveable { mutableStateOf("") }
    // 协议（远程智能体类型）：存在配置里的是稳定 id，不是界面上的名字。
    var selectedProtocolId by rememberSaveable { mutableStateOf(RemoteProtocols.DEFAULT_ID) }
    var formError by remember { mutableStateOf<String?>(null) }
    var probe by remember { mutableStateOf<ProbeUiState>(ProbeUiState.Idle) }
    var saving by remember { mutableStateOf(false) }
    /** pi-remote 安装说明弹窗：协议提示与诊断报告都从这里打开。 */
    var installHelpOpen by rememberSaveable { mutableStateOf(false) }

    // 本地网络闸门状态：探测前的授权判断要用；从系统设置回来也要刷新。
    var lanState by remember { mutableStateOf(LocalNetworkPermission.accessState(context)) }
    /** 权限申请被打断的那次探测参数；授予后接着跑，不用用户再点一遍。 */
    var pendingProbe by remember { mutableStateOf<ProbeArgs?>(null) }

    // 编辑模式：配置由镜像列表异步回放（进程重建时路由先于 DataStore 恢复）。
    // seeded 守卫保证只灌一次，之后用户改的字段不会再被覆盖。
    LaunchedEffect(serverId, servers) {
        if (!seeded) {
            servers.firstOrNull { it.id == serverId }?.let { config ->
                name = config.name
                host = config.host
                port = config.port.toString()
                token = config.authToken
                selectedProtocolId = config.protocolId
                seeded = true
            }
        }
    }
    // 配置已被删（列表非空却找不到这个 id）：给一行守卫说明，不让用户对着空表单
    // 保存出一条重复记录；返回交给顶栏返回键。
    val missing = serverId != null && servers.isNotEmpty() &&
        !seeded && servers.none { it.id == serverId }

    /**
     * 「地址」输入的实时解析结果。
     *
     * 用户手里通常是 pi-remote / opencode 给的完整链接（带 `?token=`），直接粘进
     * 主机字段会被拼成 `ws://http://…` 这种废地址。这里边输边解析，把端口和令牌
     * 自动回填，用户不必自己拆链接。
     */
    val parsedAddress = RemoteServerAddress.parse(host)

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
    val runProbeNow: suspend (ProbeArgs) -> Unit = { args ->
        probe = ProbeUiState.Running
        probe = ProbeUiState.Done(
            RemoteConnectionProbe.run(context, args.host, args.port, args.token, args.protocolId),
        )
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
     * 「连不上」本身没有信息量：这个动作把链路拆成 TCP → HTTP → 长连接三层，
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
        val args = ProbeArgs(address.host, portNumber, effectiveToken, selectedProtocolId)

        // 目标是局域网、权限又还没授予：先把授权要过来，授予后自动接着测。
        val state = LocalNetworkPermission.accessState(context)
        lanState = state
        if (LocalNetworkPermission.canRequest(state) && LocalNetworkPermission.isLanAddress(address.host)) {
            pendingProbe = args
            lanPermissionLauncher.launch(LocalNetworkPermission.PERMISSION)
            return
        }
        scope.launch { runProbeNow(args) }
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
        val id = serverId ?: java.util.UUID.randomUUID().toString()
        val config = RemoteServerConfig(
            id = id,
            name = trimmedName,
            // 存解析后的裸主机名，别把用户粘进来的整条链接当主机名存下去。
            host = address.host,
            port = portNumber,
            // 令牌/密码输入框优先；没填就用链接里带的那个。
            authToken = token.trim().ifEmpty { address.token.orEmpty() },
            // 老配置升上来时 protocolId 已经是默认值；编辑时不认识的 id 也照原样存回去，
            // 不能偷偷改写成 pi——那会让「这台服务器本版本不支持」变成「连不上」。
            protocolId = selectedProtocolId,
        )
        val updated = if (serverId == null) {
            servers + config
        } else {
            servers.map { if (it.id == serverId) config else it }
        }
        saving = true
        scope.launch {
            // 写完再出栈：本页的 coroutineScope 随组合销毁，先 pop 后写等于没写。
            SettingsDataStore.setRemoteServers(updated)
            onBack()
        }
    }

    val listState = rememberLazyListState()
    val protocolOptions = RemoteProtocols.selectable()
    val protocolKnown = protocolOptions.any { it.id == selectedProtocolId }

    EtaScaffold(
        title = stringResource(
            if (serverId == null) R.string.remote_agents_new_title else R.string.remote_agents_edit_title,
        ),
        onBack = onBack,
        actions = {
            // M3：保存走顶栏图标按钮（KernelSU 模板编辑器同款），页面底部留给表单。
            // 顶栏动作统一走 TopBarIconButton（自带 Tooltip + 触觉 + 官方默认 shapes）。
            TopBarIconButton(
                icon = Icons.Rounded.Save,
                contentDescription = stringResource(R.string.action_save),
                enabled = !saving && !missing,
                onClick = ::save,
            )
        },
    ) { paddingValues, scrollConnection, sidePadding ->
        if (missing) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
            ) {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(
                        start = sidePadding + EtaGroupHorizontalPadding,
                        end = sidePadding + EtaGroupHorizontalPadding,
                        top = 8.dp,
                    ),
                ) {
                    item {
                        EtaSegmentedItemContainer {
                            ListEmptyState(
                                title = stringResource(R.string.remote_agents_server_missing),
                            )
                        }
                    }
                }
            }
            return@EtaScaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // 低层 Scaffold 只把顶栏 Insets 传进来，输入法 Insets 由列表自行消费。
                .imePadding()
                .nestedScroll(scrollConnection),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                start = sidePadding,
                end = sidePadding,
            ),
            overscrollEffect = null,
        ) {
            item(key = "form") {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = EtaSegmentedGroupSpacing,
                    ),
                ) {
                    item {
                        EtaTextField(
                            value = name,
                            onValueChange = { name = it; formError = null },
                            label = stringResource(R.string.remote_agents_field_name),
                        )
                    }
                    item {
                        EtaTextField(
                            value = host,
                            onValueChange = { onAddressChanged(it) },
                            label = stringResource(R.string.remote_agents_field_host),
                        )
                    }
                    item {
                        EtaTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit); formError = null },
                            label = stringResource(R.string.remote_agents_field_port),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    item {
                        EtaTextField(
                            value = token,
                            onValueChange = { token = it; formError = null },
                            label = stringResource(R.string.remote_agents_field_token),
                        )
                    }
                }
            }
            // 提示与预览放在分段组之间（分段容器外的普通脚注），与列表页尾注同一版式。
            item(key = "host-hints") {
                Column(
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = EtaSegmentedGroupSpacing,
                    ),
                    verticalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm),
                ) {
                    EtaText(
                        text = stringResource(R.string.remote_agents_field_host_hint),
                        style = EtaTextStyle.Footnote,
                    )
                    endpointPreview(selectedProtocolId, parsedAddress, port, token)?.let { endpoint ->
                        EtaText(
                            text = stringResource(R.string.remote_agents_resolved_endpoint, endpoint),
                            style = EtaTextStyle.Footnote,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    formError?.let { error ->
                        EtaText(
                            text = error,
                            style = EtaTextStyle.Footnote,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            item(key = "protocol") {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = EtaSegmentedGroupSpacing,
                    ),
                ) {
                    item {
                        // 协议只有两个取值，用行内下拉而不是两行单选：设置页里
                        // 「语言 / 长按电源键」都是这个交互，选中值在行尾显示。
                        EtaSegmentedDropdownRow(
                            title = stringResource(R.string.remote_agents_field_protocol),
                            items = protocolOptions.map { it.displayName },
                            selectedIndex = protocolOptions.indexOfFirst { it.id == selectedProtocolId },
                            onItemSelected = { index ->
                                val option = protocolOptions.getOrNull(index)
                                    ?: return@EtaSegmentedDropdownRow
                                selectedProtocolId = option.id
                                formError = null
                                probe = ProbeUiState.Idle
                            },
                            // pi 不是开箱可连的：电脑端要先装 pi-remote 插件，
                            // 这一行让用户在「选协议」这一刻就知道少了哪一步。
                            summary = if (selectedProtocolId == RemoteProtocols.PI_ID) {
                                stringResource(R.string.remote_agents_hint_pi_summary)
                            } else {
                                null
                            },
                        )
                    }
                    if (selectedProtocolId == RemoteProtocols.PI_ID) {
                        item(key = "install-help") {
                            EtaClickableRow(
                                title = stringResource(R.string.remote_agents_install_help),
                                // 轻触反馈由行组件自己触发，这里不要再调一次。
                                onClick = { installHelpOpen = true },
                            )
                        }
                    }
                }
            }
            // 协议说明跟在选择之后（而不是夹在表单和协议之间），读起来是「选了谁 → 它要什么」。
            if (selectedProtocolId == RemoteProtocols.OPENCODE_ID) {
                item(key = "protocol-hint") {
                    EtaText(
                        text = stringResource(R.string.remote_agents_hint_opencode),
                        style = EtaTextStyle.Footnote,
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            bottom = EtaSegmentedGroupSpacing,
                        ),
                    )
                }
            }
            if (!protocolKnown) {
                item(key = "protocol-unknown") {
                    EtaText(
                        text = stringResource(R.string.remote_agents_protocol_unsupported, selectedProtocolId),
                        style = EtaTextStyle.Footnote,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            bottom = EtaSegmentedGroupSpacing,
                        ),
                    )
                }
            }
            item(key = "probe") {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = EtaSegmentedGroupSpacing,
                    ),
                    title = stringResource(R.string.remote_probe_title),
                ) {
                    item {
                        EtaClickableRow(
                            title = stringResource(
                                if (probe is ProbeUiState.Running) R.string.remote_probe_running
                                else R.string.remote_probe_test,
                            ),
                            onClick = if (probe is ProbeUiState.Running) {
                                null
                            } else {
                                { TouchHaptics.click(view); runProbe() }
                            },
                            showChevron = false,
                        )
                    }
                }
            }
        }
    }

    // 诊断结果单独开一个弹窗：报告有十几行，限高 + 可滚动，免得被裁掉。
    val probeReport = (probe as? ProbeUiState.Done)?.report
    if (probeReport != null) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.remote_probe_title),
            onDismissRequest = { probe = ProbeUiState.Idle },
            actions = {
                // 只有「关闭」一个动作：结果是一次性的只读报告，
                // 摆两个功能相同的按钮（取消/关闭）只会让人犹豫点哪个。
                // 唯一值得多给一个按钮的情况：系统拦了本地网络——
                // 那时用户必须去系统设置里放行，光看文字还得自己找菜单。
                if (probeReport.lanBlockedBySystem) {
                    TextButton(
                        onClick = {
                            TouchHaptics.click(view)
                            openAppPermissionSettings(context)
                        },
                    ) {
                        androidx.compose.material3.Text(stringResource(R.string.remote_probe_open_settings))
                    }
                }
                // pi 的 /ws 返回 404：最可能是电脑端没装 pi-remote（或没跑 /remote）。
                // 这时「安装步骤」比「关闭」更有用，直接给出去。
                if (selectedProtocolId == RemoteProtocols.PI_ID && probeReport.streamNotFound) {
                    TextButton(
                        onClick = {
                            TouchHaptics.click(view)
                            probe = ProbeUiState.Idle
                            installHelpOpen = true
                        },
                    ) {
                        androidx.compose.material3.Text(stringResource(R.string.remote_agents_install_help))
                    }
                }
                TextButton(
                    onClick = {
                        TouchHaptics.click(view)
                        probe = ProbeUiState.Idle
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
                // 与 EtaDialog 正文槽的行距保持一致：现在整份报告是它的一个子项，
                // 拿不到外层那个 spacedBy 了，得自己补上。
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ProbeReport(probeReport, selectedProtocolId)
            }
        }
    }

    // pi-remote 安装说明：协议行的「安装步骤」与诊断报告的 404 提示都落到这里。
    if (installHelpOpen) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.remote_agents_install_title),
            onDismissRequest = { installHelpOpen = false },
            actions = {
                TextButton(
                    onClick = {
                        TouchHaptics.click(view)
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PI_REMOTE_URL)))
                    },
                ) {
                    androidx.compose.material3.Text(
                        stringResource(R.string.remote_agents_install_open_github),
                    )
                }
                TextButton(
                    onClick = {
                        TouchHaptics.click(view)
                        installHelpOpen = false
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EtaText(
                    text = stringResource(R.string.remote_agents_install_step_1),
                    style = EtaTextStyle.Footnote,
                )
                EtaText(
                    text = stringResource(R.string.remote_agents_install_step_2),
                    style = EtaTextStyle.Footnote,
                )
                EtaText(
                    text = stringResource(R.string.remote_agents_install_step_3),
                    style = EtaTextStyle.Footnote,
                )
                EtaText(
                    text = stringResource(R.string.remote_agents_install_step_4),
                    style = EtaTextStyle.Footnote,
                )
                EtaText(
                    text = stringResource(R.string.remote_agents_install_step_5),
                    style = EtaTextStyle.Footnote,
                )
            }
        }
    }
}

/** pi-remote 插件仓库：安装步骤以仓库 README 为准，App 内只给入口。 */
private const val PI_REMOTE_URL = "https://github.com/Sam-Fic/pi-remote"

/**
 * 诊断状态。
 *
 * 只在这一个页面里用：诊断是**临时**的，不进配置、不进协调器——
 * 用户测完要知道的是「地址对不对、卡在哪一层」，而不是多一条服务器记录。
 */
private sealed interface ProbeUiState {
    data object Idle : ProbeUiState
    data object Running : ProbeUiState
    data class Done(val report: RemoteConnectionProbe.Report) : ProbeUiState
}

/** 一次诊断的全部输入。探测要穿过权限申请的中断，所以得是可传递、可回放的值。 */
private data class ProbeArgs(
    val host: String,
    val port: Int,
    val token: String,
    val protocolId: String,
)

/**
 * 诊断结果。
 *
 * 结论在最上面（用户先看结论），下面是逐层明细，最后是排查清单。
 * 「默认路由不通、强制走 Wi-Fi 就通」这一条单独拎出来——它是「系统把应用流量
 * 切到移动数据」的指纹，也是唯一能当场自证的一处。
 */
@Composable
private fun ProbeReport(report: RemoteConnectionProbe.Report, protocolId: String) {
    val tcpLabel = stringResource(R.string.remote_probe_step_tcp)
    val tcpWifiLabel = stringResource(R.string.remote_probe_step_tcp_wifi)
    val httpLabel = stringResource(R.string.remote_probe_step_http)
    // 长连接层随协议而变：pi 是 WebSocket 握手，OpenCode 是 SSE 事件流。
    val streamLabel = if (protocolId == RemoteProtocols.OPENCODE_ID) {
        stringResource(R.string.remote_probe_step_sse)
    } else {
        stringResource(R.string.remote_probe_step_ws)
    }

    val rows = buildList<Pair<String, RemoteConnectionProbe.Step>> {
        add(tcpLabel to report.tcp)
        report.tcpViaWifi?.let { add(tcpWifiLabel to it) }
        // 前一层就断了的话 HTTP/长连接一定是「未执行」，写出来只占位置、挡视线。
        if (report.http !is RemoteConnectionProbe.Step.Skipped) add(httpLabel to report.http)
        if (report.stream !is RemoteConnectionProbe.Step.Skipped) add(streamLabel to report.stream)
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
    // pi 的 /ws 404 不是「连不上」：TCP/HTTP 已经通了，缺的是电脑端那一侧
    // 的 pi-remote 端点。把这条单独写出来，否则用户会照着「连接失败」去查网络。
    if (protocolId == RemoteProtocols.PI_ID && report.streamNotFound) {
        EtaText(
            text = stringResource(R.string.remote_probe_pi_not_installed),
            style = EtaTextStyle.Footnote,
            color = MaterialTheme.colorScheme.error,
        )
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

/** 活动网络类型 → 本地化名称。 */
@Composable
private fun probeTransportText(transport: RemoteConnectionProbe.Transport): String = when (transport) {
    RemoteConnectionProbe.Transport.Wifi -> stringResource(R.string.remote_probe_network_wifi)
    RemoteConnectionProbe.Transport.Cellular -> stringResource(R.string.remote_probe_network_cellular)
    RemoteConnectionProbe.Transport.Ethernet -> stringResource(R.string.remote_probe_network_ethernet)
    RemoteConnectionProbe.Transport.Vpn -> stringResource(R.string.remote_probe_network_vpn)
    RemoteConnectionProbe.Transport.Other -> stringResource(R.string.remote_probe_network_other)
    RemoteConnectionProbe.Transport.None -> stringResource(R.string.remote_probe_network_none)
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

/**
 * 展示最终会连的地址，让用户确认「我填的确实被理解成了这个」。
 * 令牌只显示占位符——真正的值在令牌输入框里已经能看见。
 *
 * 两种协议的入口不一样：pi 是 WebSocket 端点 `/ws`（令牌走查询参数），
 * OpenCode 是 REST 基址 `/api`（密码走 HTTP Basic，地址上看不到）。
 */
private fun endpointPreview(
    protocolId: String,
    address: RemoteServerAddress.Parsed?,
    port: String,
    token: String,
): String? {
    val parsed = address ?: return null
    val portNumber = port.trim().toIntOrNull() ?: return null
    val host = if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host
    return when (protocolId) {
        RemoteProtocols.OPENCODE_ID -> "http://$host:$portNumber/api"
        else -> {
            val suffix = if (token.trim().isNotEmpty()) "?token=****" else ""
            "ws://$host:$portNumber/ws$suffix"
        }
    }
}
