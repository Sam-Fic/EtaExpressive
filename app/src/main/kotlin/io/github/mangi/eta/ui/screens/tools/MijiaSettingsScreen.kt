package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mijia.MijiaAuthState
import io.github.mangi.eta.agent.mijia.MijiaCloudClient
import io.github.mangi.eta.agent.mijia.MijiaDevice
import io.github.mangi.eta.agent.mijia.MijiaHomeInfo
import io.github.mangi.eta.agent.mijia.MijiaRoomInfo
import io.github.mangi.eta.agent.mijia.MijiaSpecParser
import io.github.mangi.eta.agent.mijia.MijiaStore
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaStatus
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.foregroundColor
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.TopBarIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 米家直连设置页（核心逻辑取自 yooma1/Eta666，界面按本仓 M3 分段列表口径重写）：
 * - 小米账号 OAuth2 授权：整屏授权页是独立路由 [io.github.mangi.eta.ui.screens.tools.MijiaOAuthScreen]，
 *   拦截回调 code 经 [MijiaStore.pendingOAuthCode] 回传本页换 Token，失败可退「外部浏览器 + 粘贴回调」；
 * - 授权后同步家庭/房间/设备到本地缓存，支持按家庭/房间筛选与关键词搜索；
 * - 设备行快切电源；收藏（置顶）设备由 [MijiaStore] 持久化。
 * 小布语音直通（yooma1 的方案 A）依赖其 BreenoTapProvider 桥，本仓无对应架构，不移植；
 * 语音控制经 Agent 的 mijia_control 工具（方案 B）已覆盖。
 */
@Composable
internal fun MijiaSettingsScreen(
    onBack: () -> Unit,
    onOpenOAuth: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    // IO/存储统一用 application context；用户可见文案必须走 Activity 上下文——应用语言
    // 经平台 per-app locale 分发到 Activity 的 Resources，applicationContext.getString
    // 仍取默认 locale（状态行会变成英文）。
    val context = LocalContext.current.applicationContext
    val uiContext = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf(false) }
    var toolEnabled by remember { mutableStateOf(false) }
    var authState by remember { mutableStateOf<MijiaAuthState?>(null) }
    var homes by remember { mutableStateOf<List<MijiaHomeInfo>>(emptyList()) }
    var devices by remember { mutableStateOf<List<MijiaDevice>>(emptyList()) }
    var favoriteDids by remember { mutableStateOf<List<String>>(emptyList()) }

    var syncing by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var exchangingToken by remember { mutableStateOf(false) }

    var selectedHomeId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRoomId by rememberSaveable { mutableStateOf<String?>(null) }

    val devicePowerStates = remember { mutableStateMapOf<String, Boolean>() }
    val togglingDids = remember { mutableStateMapOf<String, Boolean>() }

    fun reloadLocalState() {
        toolEnabled = MijiaStore.isEnabled(context)
        authState = MijiaStore.loadAuth(context)
        homes = MijiaStore.loadHomes(context)
        devices = MijiaStore.loadDevices(context)
        favoriteDids = MijiaStore.favoriteDids(context)
        if (selectedHomeId != null && homes.none { it.homeId == selectedHomeId }) {
            selectedHomeId = null
            selectedRoomId = null
        }
    }

    fun triggerSync(reasonHint: String? = null) {
        if (syncing) return
        syncing = true
        errorMessage = null
        if (reasonHint != null) statusMessage = reasonHint
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                MijiaCloudClient.syncHomesAndDevices(context)
            }
            syncing = false
            res.onSuccess { (syncedHomes, syncedDevices) ->
                homes = syncedHomes
                devices = syncedDevices
                authState = MijiaStore.loadAuth(context)
                favoriteDids = MijiaStore.favoriteDids(context)
                statusMessage = uiContext.getString(R.string.mijia_sync_success, syncedHomes.size, syncedDevices.size)
            }.onFailure { err ->
                authState = MijiaStore.loadAuth(context)
                // 失败文案带原始错误详情：裸 err.message（如 API 错误串）缺上下文难排查。
                errorMessage = uiContext.getString(
                    R.string.mijia_sync_failed,
                    err.message ?: uiContext.getString(R.string.mijia_unknown_error),
                )
            }
        }
    }

    fun handleQuickPowerToggle(device: MijiaDevice) {
        if (togglingDids[device.did] == true) return
        togglingDids[device.did] = true
        val targetOn = devicePowerStates[device.did]?.not()
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                MijiaCloudClient.toggleDevicePower(context, device, targetOn)
            }
            togglingDids.remove(device.did)
            res.onSuccess { newState ->
                devicePowerStates[device.did] = newState
                statusMessage = uiContext.getString(
                    if (newState) R.string.mijia_power_on_result else R.string.mijia_power_off_result,
                    device.name,
                )
                errorMessage = null
            }.onFailure { err ->
                errorMessage = uiContext.getString(
                    R.string.mijia_power_toggle_failed,
                    device.name,
                    err.message ?: uiContext.getString(R.string.mijia_unknown_error),
                )
            }
        }
    }

    fun handleToggleFavorite(device: MijiaDevice) {
        scope.launch {
            val isAlreadyFav = device.did in favoriteDids
            if (!isAlreadyFav && favoriteDids.size >= MijiaStore.MAX_FAVORITES) {
                errorMessage = uiContext.getString(R.string.mijia_favorites_limit, MijiaStore.MAX_FAVORITES)
                return@launch
            }
            withContext(Dispatchers.IO) {
                MijiaStore.toggleFavorite(context, device.did)
            }
            favoriteDids = MijiaStore.favoriteDids(context)
        }
    }

    fun handleInterceptedOAuthCode(code: String) {
        // 取走总线上的 code：避免重组/返回后重复消费同一次授权。
        MijiaStore.consumeOAuthCode()
        exchangingToken = true
        errorMessage = null
        statusMessage = uiContext.getString(R.string.mijia_code_received)
        scope.launch {
            val tokenRes = withContext(Dispatchers.IO) {
                MijiaCloudClient.exchangeCodeForToken(context, code)
            }
            exchangingToken = false
            tokenRes.onSuccess { newAuth ->
                authState = newAuth
                if (!toolEnabled) {
                    toolEnabled = true
                    MijiaStore.setEnabled(context, true)
                }
                statusMessage = uiContext.getString(
                    R.string.mijia_auth_success,
                    newAuth.uid.ifBlank { uiContext.getString(R.string.mijia_auth_bound) },
                )
                triggerSync()
            }.onFailure { err ->
                authState = MijiaStore.loadAuth(context)
                errorMessage = uiContext.getString(
                    R.string.mijia_token_exchange_failed,
                    err.message ?: uiContext.getString(R.string.mijia_unknown_error),
                )
            }
        }
    }

    // OAuth 授权页（独立路由）弹回后的 code 总线：StateFlow 回放保证设置页被导航覆盖
    // 销毁后返回时，重新收集仍能拿到授权页留下的 code。
    LaunchedEffect(Unit) {
        MijiaStore.pendingOAuthCode.collect { code ->
            if (!code.isNullOrBlank()) handleInterceptedOAuthCode(code)
        }
    }

    val currentHome = homes.firstOrNull { it.homeId == selectedHomeId }
    val availableRooms: List<MijiaRoomInfo> = remember(homes, currentHome) {
        if (currentHome != null) {
            currentHome.rooms
        } else {
            homes.flatMap { it.rooms }.distinctBy { it.roomId }
        }
    }
    val favoriteDeviceList = remember(devices, favoriteDids) {
        val byDid = devices.associateBy { it.did }
        favoriteDids.mapNotNull { byDid[it] }
    }
    val filteredDevices = remember(devices, selectedHomeId, selectedRoomId, favoriteDids) {
        devices.filter { dev ->
            val matchHome = selectedHomeId == null || dev.homeId == selectedHomeId
            val matchRoom = selectedRoomId == null || dev.roomId == selectedRoomId
            matchHome && matchRoom
        }.sortedMijiaList(favoriteDids)
    }

    MijiaSettingsScreenContent(
        onBack = onBack,
        loaded = loaded,
        toolEnabled = toolEnabled,
        authState = authState,
        homes = homes,
        availableRooms = availableRooms,
        selectedHomeId = selectedHomeId,
        selectedRoomId = selectedRoomId,
        favoriteDeviceList = favoriteDeviceList,
        filteredDevices = filteredDevices,
        allDevices = devices,
        favoriteDids = favoriteDids,
        syncing = syncing,
        exchangingToken = exchangingToken,
        statusMessage = statusMessage,
        errorMessage = errorMessage,
        devicePowerStates = devicePowerStates,
        togglingDids = togglingDids,
        onSelectHome = { homeId ->
            selectedHomeId = homeId
            selectedRoomId = null
        },
        onSelectRoom = { selectedRoomId = it },
        onToolEnabledChange = { enabled ->
            toolEnabled = enabled
            MijiaStore.setEnabled(context, enabled)
            statusMessage = uiContext.getString(
                if (enabled) R.string.mijia_tool_enabled else R.string.mijia_tool_disabled,
            )
        },
        onLogin = onOpenOAuth,
        onOpenSearch = onOpenSearch,
        onSync = { triggerSync(uiContext.getString(R.string.mijia_syncing)) },
        onLogout = {
            MijiaStore.clearAuth(context, null)
            authState = MijiaStore.loadAuth(context)
            statusMessage = uiContext.getString(R.string.mijia_logged_out)
            errorMessage = null
        },
        onQuickPowerToggle = ::handleQuickPowerToggle,
        onToggleFavorite = ::handleToggleFavorite,
        onFirstLoad = {
            withContext(Dispatchers.IO) { reloadLocalState() }
            loaded = true
        },
    )
}

/** 分段列表分组的统一页边距与组间距（引用项目自适应令牌，不手写数值）。 */
private fun mijiaGroupModifier(): Modifier = Modifier.padding(
    start = EtaGroupHorizontalPadding,
    end = EtaGroupHorizontalPadding,
    bottom = EtaSegmentedGroupSpacing,
)

/** 设备列表统一排序：常用置顶 → 在线优先 → 房间 → 名称。设置页与搜索页共用。 */
internal fun List<MijiaDevice>.sortedMijiaList(favoriteDids: List<String>): List<MijiaDevice> =
    sortedWith(
        compareByDescending<MijiaDevice> { it.did in favoriteDids }
            .thenByDescending { it.online }
            .thenBy { it.roomName }
            .thenBy { it.name },
    )

@Composable
private fun MijiaSettingsScreenContent(
    onBack: () -> Unit,
    loaded: Boolean,
    toolEnabled: Boolean,
    authState: MijiaAuthState?,
    homes: List<MijiaHomeInfo>,
    availableRooms: List<MijiaRoomInfo>,
    selectedHomeId: String?,
    selectedRoomId: String?,
    favoriteDeviceList: List<MijiaDevice>,
    filteredDevices: List<MijiaDevice>,
    allDevices: List<MijiaDevice>,
    favoriteDids: List<String>,
    syncing: Boolean,
    exchangingToken: Boolean,
    statusMessage: String?,
    errorMessage: String?,
    devicePowerStates: MutableMap<String, Boolean>,
    togglingDids: Map<String, Boolean>,
    onSelectHome: (String?) -> Unit,
    onSelectRoom: (String?) -> Unit,
    onToolEnabledChange: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onOpenSearch: () -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    onQuickPowerToggle: (MijiaDevice) -> Unit,
    onToggleFavorite: (MijiaDevice) -> Unit,
    onFirstLoad: suspend () -> Unit,
) {
    LaunchedEffect(Unit) { onFirstLoad() }

    MijiaDevicePowerFetcher(filteredDevices, devicePowerStates)

    // 退出授权的破坏性确认收进弹窗（判断模型「清理」行同款），行外只留入口。
    var showLogoutDialog by remember { mutableStateOf(false) }

    EtaScaffoldPage(
        title = stringResource(R.string.mijia_title),
        onBack = onBack,
        listModifier = Modifier.imePadding(),
        actions = {
            // 搜索入口与可筛内容同在：未授权且无设备时不出按钮，避免空页多一个死动作。
            // 独立搜索页设计（设置搜索页同款）：页内不常驻搜索框，整页即搜索。
            if (authState?.isAuthorized == true || allDevices.isNotEmpty()) {
                TopBarIconButton(
                    icon = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.mijia_search_action),
                    onClick = onOpenSearch,
                )
            }
            if (authState?.isAuthorized == true) {
                TopBarIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.mijia_sync_action),
                    enabled = !syncing && !exchangingToken,
                    onClick = onSync,
                )
            }
        },
    ) {
        if (!loaded) return@EtaScaffoldPage

        item(key = "capability") {
            // 组说明文案走兄弟页同款 EtaText(Secondary)，不用 NoticeText（那是「异常说明」的口气）。
            // 水平内缩与分组卡对齐（WebTools 说明块 / MediaIndex 脚注同款 16dp），下接组间距节奏。
            EtaText(
                text = stringResource(R.string.mijia_capability_note),
                style = EtaTextStyle.Secondary,
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            )
        }

        item(key = "tool_switch") {
            EtaSegmentedColumn(modifier = mijiaGroupModifier()) {
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.mijia_tool_switch_title),
                        summary = stringResource(R.string.mijia_tool_switch_summary_on),
                        summaryOff = stringResource(R.string.mijia_tool_switch_summary_off),
                        checked = toolEnabled,
                        onCheckedChange = onToolEnabledChange,
                    )
                }
            }
        }

        item(key = "auth_card") {
            EtaSegmentedColumn(
                modifier = mijiaGroupModifier(),
                title = stringResource(R.string.mijia_auth_group_title),
            ) {
                item {
                    EtaClickableRow(
                        title = if (authState?.isAuthorized == true) {
                            stringResource(
                                R.string.mijia_auth_authorized_title,
                                authState.nickname.ifBlank { stringResource(R.string.mijia_account_default_name) },
                            )
                        } else {
                            stringResource(R.string.mijia_auth_login_title)
                        },
                        summary = if (authState?.isAuthorized == true) {
                            stringResource(
                                R.string.mijia_auth_summary_uid_server,
                                authState.uid.ifBlank { "-" },
                                authState.cloudServer,
                            )
                        } else {
                            stringResource(R.string.mijia_auth_login_summary)
                        },
                        leading = {
                            PreferenceIcon(icon = Icons.Rounded.Login, enabled = !exchangingToken)
                        },
                        onClick = onLogin,
                        enabled = !exchangingToken,
                    )
                }
                if (authState?.isAuthorized == true) {
                    item {
                        EtaClickableRow(
                            title = stringResource(R.string.mijia_auth_logout),
                            leading = {
                                // 破坏性行：图标与标题同取官方 error 角色色；同步/换票期间
                                // 行禁用，图标再落回官方禁用档（onSurface × 0.38）。
                                PreferenceIcon(
                                    icon = Icons.Rounded.Logout,
                                    enabled = !syncing && !exchangingToken,
                                    destructive = true,
                                )
                            },
                            // 破坏性动作行：点击只开确认弹窗，真正退出由弹窗确认触发。
                            onClick = { showLogoutDialog = true },
                            enabled = !syncing && !exchangingToken,
                            destructive = true,
                        )
                    }
                }
            }
        }

        item(key = "status_lines") {
            Column(
                modifier = Modifier.padding(bottom = EtaSegmentedGroupSpacing),
                verticalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm),
            ) {
                statusMessage?.let {
                    MijiaStatusLine(it, EtaStatus.Success)
                }
                errorMessage?.let {
                    MijiaStatusLine(it, EtaStatus.Failed)
                }
            }
        }

        if (authState?.isAuthorized == true || allDevices.isNotEmpty()) {
            // 筛选栏只剩家庭/房间下拉：至少有一个可筛下拉才出组，避免空分组卡。
            if (homes.size > 1 || availableRooms.isNotEmpty()) {
                item(key = "filter_bar") {
                    MijiaFilterBar(
                        homes = homes,
                        rooms = availableRooms,
                        selectedHomeId = selectedHomeId,
                        selectedRoomId = selectedRoomId,
                        onSelectHome = onSelectHome,
                        onSelectRoom = onSelectRoom,
                    )
                }
            }

            if (favoriteDeviceList.isNotEmpty()) {
                item(key = "fav_grid") {
                    MijiaFavoriteRow(
                        devices = favoriteDeviceList,
                        powerStates = devicePowerStates,
                        togglingDids = togglingDids,
                        favoriteDids = favoriteDids,
                        onTogglePower = onQuickPowerToggle,
                        onToggleFavorite = onToggleFavorite,
                    )
                }
            }

            item(key = "device_list") {
                // 设备列表是「一整组分段卡」：标题用 EtaSegmentedColumn 的 title 参数
                // （兄弟页 McpServers 同款），行们平铺在同一个组内，首末自动接管外圆角。
                // 不用每行单独包一层 EtaSegmentedColumn——那会变成一行一卡。
                EtaSegmentedColumn(
                    modifier = mijiaGroupModifier(),
                    title = run {
                        val onlineCount = filteredDevices.count { it.online }
                        stringResource(R.string.mijia_device_group_title, onlineCount, filteredDevices.size)
                    },
                ) {
                    if (syncing && allDevices.isEmpty()) {
                        item {
                            EtaSegmentedItemContainer {
                                ListEmptyState(title = stringResource(R.string.mijia_loading_devices))
                            }
                        }
                    } else if (filteredDevices.isEmpty()) {
                        item {
                            EtaSegmentedItemContainer {
                                ListEmptyState(
                                    title = stringResource(
                                        if (allDevices.isEmpty()) R.string.mijia_empty_devices else R.string.mijia_no_match,
                                    ),
                                    summary = stringResource(R.string.mijia_empty_devices_hint),
                                )
                            }
                        }
                    } else {
                        filteredDevices.forEach { device ->
                            item(key = device.did) {
                                MijiaDeviceRow(
                                    device = device,
                                    isFavorite = device.did in favoriteDids,
                                    powerState = devicePowerStates[device.did],
                                    toggling = togglingDids[device.did] == true,
                                    onTogglePower = { onQuickPowerToggle(device) },
                                    onToggleFavorite = { onToggleFavorite(device) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 破坏性动作对话框二次确认兜底（判断模型「清理」行同款）：[MijiaStore.clearAuth]
    // 只清令牌与账号信息，家庭/设备/设置保留，但之后需重新授权才能同步或控制设备。
    if (showLogoutDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.mijia_auth_logout_confirm_title),
            summary = stringResource(R.string.mijia_auth_logout_confirm_summary),
            onDismissRequest = { showLogoutDialog = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.mijia_auth_logout),
                    destructive = true,
                    onCancel = { showLogoutDialog = false },
                    onConfirm = {
                        showLogoutDialog = false
                        onLogout()
                    },
                )
            },
        )
    }
}

/** 设备行电源状态未知时，后台读一次主电源属性回填（快切按钮据它决定开/关方向）。设置页与搜索页共用。 */
@Composable
internal fun MijiaDevicePowerFetcher(
    devices: List<MijiaDevice>,
    powerStates: MutableMap<String, Boolean>,
) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(devices) {
        val pending = devices.filter { it.online && powerStates[it.did] == null }.take(30)
        if (pending.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            pending.forEach { device ->
                val spec = runCatching {
                    MijiaSpecParser.fetchAndParse(context, device.urn).getOrNull()
                }.getOrNull() ?: return@forEach
                val powerProp = spec.primaryPowerProp(preferredSiid = device.splitSiid) ?: return@forEach
                val value = MijiaCloudClient.getProps(
                    context,
                    listOf(Triple(device.did, powerProp.siid, powerProp.piid)),
                ).getOrNull()?.get("${powerProp.siid}.${powerProp.piid}")
                val bool = when (value) {
                    is Boolean -> value
                    is Number -> value.toInt() != 0
                    is String -> value.equals("true", ignoreCase = true) || value == "1"
                    else -> null
                }
                if (bool != null) powerStates[device.did] = bool
            }
        }
    }
}

/**
 * 状态行（图标 + 小字）：设置页状态反馈与 OAuth 授权页错误条共用。
 * 颜色走 [EtaStatus.foregroundColor]（§2.1 映射）；文案由调用方给出。
 */
@Composable
internal fun MijiaStatusLine(
    text: String,
    status: EtaStatus,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(EtaDimens.SpaceXs),
        // 与页面 16dp 内容列对齐；上下留白由调用方按场景补。
        modifier = modifier.padding(horizontal = EtaGroupHorizontalPadding),
    ) {
        Icon(
            imageVector = if (status == EtaStatus.Success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(EtaDimens.StatusIconSize),
            tint = status.foregroundColor(),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = status.foregroundColor(),
        )
    }
}

/** 收藏（常用）卡的固定宽度：单用途几何值，就近私有命名（不进 EtaDimens——只此一处用）。 */
private val MijiaFavoriteCardWidth = 168.dp

@Composable
private fun MijiaFavoriteRow(
    devices: List<MijiaDevice>,
    powerStates: Map<String, Boolean>,
    togglingDids: Map<String, Boolean>,
    favoriteDids: List<String>,
    onTogglePower: (MijiaDevice) -> Unit,
    onToggleFavorite: (MijiaDevice) -> Unit,
) {
    LazyRow(
        // 底部补组间距：与后一个分组（全屋设备）保持 13dp 节奏。
        modifier = Modifier.padding(bottom = EtaSegmentedGroupSpacing),
        horizontalArrangement = Arrangement.spacedBy(EtaSegmentedGroupSpacing),
        contentPadding = PaddingValues(horizontal = EtaGroupHorizontalPadding),
    ) {
        items(devices, key = { it.did }) { device ->
            MijiaFavoriteCard(
                device = device,
                isFavorite = device.did in favoriteDids,
                powerState = powerStates[device.did],
                toggling = togglingDids[device.did] == true,
                onTogglePower = { onTogglePower(device) },
                onToggleFavorite = { onToggleFavorite(device) },
            )
        }
    }
}

@Composable
private fun MijiaFavoriteCard(
    device: MijiaDevice,
    isFavorite: Boolean,
    powerState: Boolean?,
    toggling: Boolean,
    onTogglePower: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    EtaSegmentedColumn(modifier = Modifier.width(MijiaFavoriteCardWidth)) {
        item {
            // 标题行不可点击；收藏走行尾独立星标按钮（仅按钮区域可点），与设备行同款。
            EtaClickableRow(
                title = device.name,
                summary = device.roomName.ifBlank { stringResource(R.string.mijia_default_room) } +
                    if (device.online) "" else " · ${stringResource(R.string.mijia_offline)}",
                showChevron = false,
                endActions = {
                    IconButton(
                        onClick = onToggleFavorite,
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                            contentDescription = if (isFavorite) {
                                stringResource(R.string.mijia_unfavorite)
                            } else {
                                stringResource(R.string.mijia_favorite)
                            },
                            tint = if (isFavorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )
        }
        item {
            // 电源快切：整行点击切换（星标已移至标题行行尾，不在本行）。
            EtaSegmentedRow(
                leadingContent = {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(EtaDimens.DenseIconSize),
                        tint = if (powerState == true) {
                            EtaStatus.Success.foregroundColor()
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                onClick = onTogglePower,
                enabled = device.online && !toggling,
            ) {
                Text(
                    text = when {
                        toggling -> stringResource(R.string.mijia_power_toggling)
                        powerState == true -> stringResource(R.string.mijia_power_on)
                        powerState == false -> stringResource(R.string.mijia_power_off)
                        else -> stringResource(R.string.mijia_power)
                    },
                )
            }
        }
    }
}

@Composable
private fun MijiaFilterBar(
    homes: List<MijiaHomeInfo>,
    rooms: List<MijiaRoomInfo>,
    selectedHomeId: String?,
    selectedRoomId: String?,
    onSelectHome: (String?) -> Unit,
    onSelectRoom: (String?) -> Unit,
) {
    // 分组容器直接用统一组边距（含组间 13dp），不再套一层单子 Column。
    // 关键词搜索已移入独立搜索页（MijiaSearchScreen），这里只留家庭/房间下拉。
    EtaSegmentedColumn(modifier = mijiaGroupModifier()) {
        if (homes.size > 1) {
            item {
                // stringResource 必须在 item 的 @Composable 内容里取：DSL 作用域体不是组合上下文。
                val homeItems = listOf(stringResource(R.string.mijia_filter_all_homes)) + homes.map { it.homeName }
                EtaSegmentedDropdownRow(
                    title = stringResource(R.string.mijia_filter_home),
                    items = homeItems,
                    selectedIndex = homes.indexOfFirst { it.homeId == selectedHomeId } + 1,
                    onItemSelected = { index ->
                        onSelectHome(if (index <= 0) null else homes[index - 1].homeId)
                    },
                )
            }
        }
        if (rooms.isNotEmpty()) {
            item {
                val roomItems = listOf(stringResource(R.string.mijia_filter_all_rooms)) + rooms.map { it.roomName }
                EtaSegmentedDropdownRow(
                    title = stringResource(R.string.mijia_filter_room),
                    items = roomItems,
                    selectedIndex = rooms.indexOfFirst { it.roomId == selectedRoomId } + 1,
                    onItemSelected = { index ->
                        onSelectRoom(if (index <= 0) null else rooms[index - 1].roomId)
                    },
                )
            }
        }
    }
}

@Composable
internal fun MijiaDeviceRow(
    device: MijiaDevice,
    isFavorite: Boolean,
    powerState: Boolean?,
    toggling: Boolean,
    onTogglePower: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 行平铺在外层设备组的 EtaSegmentedColumn 内（McpServersScreen 同款）：
    // 每行不再自行包一层分组列——那会一行一卡并把整组缩进加倍。
    // 行本身不可点击；收藏走行尾独立星标按钮（仅按钮区域可点），电源走开关。
    EtaSegmentedRow(
        modifier = modifier,
        supportingContent = listOfNotNull(
            device.roomName.ifBlank { null },
            device.model.ifBlank { null },
            if (device.online) null else stringResource(R.string.mijia_offline),
        ).joinToString(" · ").ifBlank { null }?.let { { Text(text = it) } },
        leadingContent = { PreferenceIcon(icon = Icons.Rounded.Hub) },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // 尾槽元素统一 12dp、开关永远最右（EtaDimens.TrailingElementGap 约定）。
                horizontalArrangement = Arrangement.spacedBy(EtaDimens.TrailingElementGap),
            ) {
                IconButton(
                    onClick = onToggleFavorite,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                        contentDescription = if (isFavorite) {
                            stringResource(R.string.mijia_unfavorite)
                        } else {
                            stringResource(R.string.mijia_favorite)
                        },
                        tint = if (isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                EtaSwitch(
                    checked = powerState == true,
                    onCheckedChange = { onTogglePower() },
                    enabled = device.online && !toggling && powerState != null,
                )
            }
        },
    ) {
        Text(text = device.name)
    }
}
