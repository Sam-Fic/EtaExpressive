package io.github.mangi.eta.ui.screens.tools

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mangi.eta.agent.mijia.MijiaAuthState
import io.github.mangi.eta.agent.mijia.MijiaCloudClient
import io.github.mangi.eta.agent.mijia.MijiaDevice
import io.github.mangi.eta.agent.mijia.MijiaHomeInfo
import io.github.mangi.eta.agent.mijia.MijiaRoomInfo
import io.github.mangi.eta.agent.mijia.MijiaStore
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaNoticeText
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTextField
import io.github.mangi.eta.ui.adaptive.EtaPageSurface
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaStatus
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.foregroundColor
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.TopBarIconButton
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 米家直连设置页（核心逻辑取自 yooma1/Eta666，界面按本仓 M3 分段列表口径重写）：
 * - 小米账号 OAuth2 授权：内嵌 WebView 拦截回调换 Token，失败可退「外部浏览器 + 粘贴回调」；
 * - 授权后同步家庭/房间/设备到本地缓存，支持按家庭/房间筛选与关键词搜索；
 * - 设备行快切电源；收藏（置顶）设备由 [MijiaStore] 持久化。
 * 小布语音直通（yooma1 的方案 A）依赖其 BreenoTapProvider 桥，本仓无对应架构，不移植；
 * 语音控制经 Agent 的 mijia_control 工具（方案 B）已覆盖。
 */
@Composable
internal fun MijiaSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
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

    var showOAuthDialog by remember { mutableStateOf(false) }
    var showManualPasteDialog by remember { mutableStateOf(false) }
    var exchangingToken by remember { mutableStateOf(false) }

    var selectedHomeId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRoomId by rememberSaveable { mutableStateOf<String?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

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
                statusMessage = "已同步 ${syncedHomes.size} 个家庭、${syncedDevices.size} 台设备"
            }.onFailure { err ->
                authState = MijiaStore.loadAuth(context)
                errorMessage = err.message ?: "同步家庭与设备失败"
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
                statusMessage = "${device.name} 已${if (newState) "开启" else "关闭"}"
                errorMessage = null
            }.onFailure { err ->
                errorMessage = "${device.name} 操作失败：${err.message ?: "未知错误"}"
            }
        }
    }

    fun handleToggleFavorite(device: MijiaDevice) {
        scope.launch {
            val isAlreadyFav = device.did in favoriteDids
            if (!isAlreadyFav && favoriteDids.size >= MijiaStore.MAX_FAVORITES) {
                errorMessage = "常用设备最多收藏 ${MijiaStore.MAX_FAVORITES} 个"
                return@launch
            }
            withContext(Dispatchers.IO) {
                MijiaStore.toggleFavorite(context, device.did)
            }
            favoriteDids = MijiaStore.favoriteDids(context)
        }
    }

    fun handleInterceptedOAuthCode(code: String) {
        showOAuthDialog = false
        showManualPasteDialog = false
        exchangingToken = true
        errorMessage = null
        statusMessage = "已获取授权码，正在换取小米账号 Token…"
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
                statusMessage = "授权成功（UID: ${newAuth.uid.ifBlank { "已绑定" }}），正在同步设备…"
                triggerSync()
            }.onFailure { err ->
                authState = MijiaStore.loadAuth(context)
                errorMessage = "Token 换取失败：${err.message ?: "未知错误"}"
            }
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
    val filteredDevices = remember(devices, selectedHomeId, selectedRoomId, searchQuery, favoriteDids) {
        val q = searchQuery.trim().lowercase()
        devices.filter { dev ->
            val matchHome = selectedHomeId == null || dev.homeId == selectedHomeId
            val matchRoom = selectedRoomId == null || dev.roomId == selectedRoomId
            val matchQuery = q.isEmpty() ||
                dev.name.lowercase().contains(q) ||
                dev.roomName.lowercase().contains(q) ||
                dev.homeName.lowercase().contains(q) ||
                dev.model.lowercase().contains(q) ||
                dev.category.lowercase().contains(q)
            matchHome && matchRoom && matchQuery
        }.sortedWith(
            compareByDescending<MijiaDevice> { it.did in favoriteDids }
                .thenByDescending { it.online }
                .thenBy { it.roomName }
                .thenBy { it.name },
        )
    }

    if (showManualPasteDialog) {
        MijiaManualOAuthPasteDialog(
            authorizeUrl = MijiaCloudClient.buildAuthorizeUrl(context, skipConfirm = false),
            onSubmitCode = ::handleInterceptedOAuthCode,
            onDismiss = { showManualPasteDialog = false },
        )
    }

    MijiaOAuthWebViewOverlay(
        show = showOAuthDialog,
        onCodeIntercepted = ::handleInterceptedOAuthCode,
        onOpenManualPaste = {
            showOAuthDialog = false
            showManualPasteDialog = true
        },
        onDismiss = { showOAuthDialog = false },
    )

    MijiaSettingsScreenContent(
        onBack = onBack,
        loaded = loaded,
        toolEnabled = toolEnabled,
        authState = authState,
        homes = homes,
        availableRooms = availableRooms,
        selectedHomeId = selectedHomeId,
        selectedRoomId = selectedRoomId,
        searchQuery = searchQuery,
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
        onSearchChange = { searchQuery = it },
        onToolEnabledChange = { enabled ->
            toolEnabled = enabled
            MijiaStore.setEnabled(context, enabled)
            statusMessage = if (enabled) "已启用 mijia_control 工具" else "已关闭 mijia_control 工具"
        },
        onLogin = { showOAuthDialog = true },
        onSync = { triggerSync("正在同步米家家庭与设备…") },
        onLogout = {
            MijiaStore.clearAuth(context, null)
            authState = MijiaStore.loadAuth(context)
            statusMessage = "已退出小米账号授权"
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
    searchQuery: String,
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
    onSearchChange: (String) -> Unit,
    onToolEnabledChange: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onSync: () -> Unit,
    onLogout: () -> Unit,
    onQuickPowerToggle: (MijiaDevice) -> Unit,
    onToggleFavorite: (MijiaDevice) -> Unit,
    onFirstLoad: suspend () -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(Unit) { onFirstLoad() }

    MijiaDevicePowerFetcher(filteredDevices, devicePowerStates)

    EtaScaffoldPage(
        title = "米家直连",
        onBack = onBack,
        listModifier = Modifier.imePadding(),
        actions = {
            if (authState?.isAuthorized == true) {
                TopBarIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = "同步设备",
                    enabled = !syncing && !exchangingToken,
                    onClick = onSync,
                )
            }
        },
    ) {
        if (!loaded) return@EtaScaffoldPage

        item(key = "capability") {
            // 组说明文案走兄弟页同款 EtaText(Secondary)，不用 NoticeText（那是「异常说明」的口气）。
            EtaText(
                text = "启用后 Agent 与子代理可直接查询/控制全屋米家设备；关闭时不向模型下发该工具。",
                style = EtaTextStyle.Secondary,
                modifier = Modifier.padding(bottom = EtaSegmentedGroupSpacing),
            )
        }

        item(key = "tool_switch") {
            EtaSegmentedColumn(modifier = mijiaGroupModifier()) {
                item {
                    EtaSwitchRow(
                        title = "启用米家直连工具",
                        summary = if (toolEnabled) {
                            "Agent 可调用 mijia_control 查询与控制米家设备"
                        } else {
                            "关闭时 mijia_control 工具不注入模型工具栏"
                        },
                        checked = toolEnabled,
                        onCheckedChange = onToolEnabledChange,
                    )
                }
            }
        }

        item(key = "auth_card") {
            EtaSegmentedColumn(
                modifier = mijiaGroupModifier(),
                title = "小米账号授权",
            ) {
                item {
                    EtaClickableRow(
                        title = if (authState?.isAuthorized == true) {
                            "已授权：${authState.nickname.ifBlank { "小米账号" }}"
                        } else {
                            "登录小米账号"
                        },
                        summary = if (authState?.isAuthorized == true) {
                            "UID ${authState.uid.ifBlank { "-" }} · ${authState.cloudServer}"
                        } else {
                            "OAuth2 授权后自动同步家庭与设备"
                        },
                        leading = { PreferenceIcon(icon = Icons.Rounded.Login) },
                        onClick = onLogin,
                        enabled = !exchangingToken,
                    )
                }
                if (authState?.isAuthorized == true) {
                    item {
                        EtaClickableRow(
                            title = "退出授权",
                            leading = { PreferenceIcon(icon = Icons.Rounded.Logout) },
                            onClick = onLogout,
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
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
            item(key = "filter_bar") {
                MijiaFilterBar(
                    homes = homes,
                    rooms = availableRooms,
                    selectedHomeId = selectedHomeId,
                    selectedRoomId = selectedRoomId,
                    searchQuery = searchQuery,
                    onSelectHome = onSelectHome,
                    onSelectRoom = onSelectRoom,
                    onSearchChange = onSearchChange,
                )
            }

            if (favoriteDeviceList.isNotEmpty()) {
                item(key = "fav_grid") {
                    // LazyRow 自带水平留白：不套 mijiaGroupModifier（会双层 padding）。
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
                        "全屋设备（在线 $onlineCount / 共 ${filteredDevices.size} 台）"
                    },
                ) {
                    if (syncing && allDevices.isEmpty()) {
                        item {
                            EtaSegmentedItemContainer {
                                ListEmptyState(title = "正在从米家云端同步家庭与设备列表…")
                            }
                        }
                    } else if (filteredDevices.isEmpty()) {
                        item {
                            EtaSegmentedItemContainer {
                                ListEmptyState(
                                    title = if (allDevices.isEmpty()) "暂未同步到米家设备" else "没有匹配的设备",
                                    summary = "登录授权后点击顶栏同步",
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
}

/** 设备行电源状态未知时，后台读一次主电源属性回填（快切按钮据它决定开/关方向）。 */
@Composable
private fun MijiaDevicePowerFetcher(
    devices: List<MijiaDevice>,
    powerStates: MutableMap<String, Boolean>,
) {
    val context = LocalContext.current.applicationContext
    androidx.compose.runtime.LaunchedEffect(devices) {
        val pending = devices.filter { it.online && powerStates[it.did] == null }.take(30)
        if (pending.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            pending.forEach { device ->
                val spec = runCatching {
                    io.github.mangi.eta.agent.mijia.MijiaSpecParser.fetchAndParse(context, device.urn).getOrNull()
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

@Composable
private fun MijiaStatusLine(text: String, status: EtaStatus) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Icon(
            imageVector = if (status == EtaStatus.Success) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = status.foregroundColor(),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = status.foregroundColor(),
        )
    }
}

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
        horizontalArrangement = Arrangement.spacedBy(EtaSegmentedGroupSpacing),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = EtaGroupHorizontalPadding),
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
    EtaSegmentedColumn(modifier = Modifier.width(168.dp)) {
        item {
            EtaClickableRow(
                title = device.name,
                summary = device.roomName.ifBlank { "默认房间" } +
                    if (device.online) "" else " · 离线",
                enabled = true,
                onClick = onToggleFavorite,
            )
        }
        item {
            EtaSegmentedRow(
                leadingContent = {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (powerState == true) {
                            EtaStatus.Success.foregroundColor()
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                        contentDescription = if (isFavorite) "取消常用" else "设为常用",
                        modifier = Modifier.size(20.dp),
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = onTogglePower,
                enabled = device.online && !toggling,
            ) {
                Text(
                    text = when {
                        toggling -> "切换中…"
                        powerState == true -> "已开启"
                        powerState == false -> "已关闭"
                        else -> "电源"
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
    searchQuery: String,
    onSelectHome: (String?) -> Unit,
    onSelectRoom: (String?) -> Unit,
    onSearchChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = EtaGroupHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(EtaSegmentedGroupSpacing),
    ) {
        EtaSegmentedColumn {
            item {
                EtaSegmentedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    label = "搜索设备",
                    singleLine = true,
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            if (homes.size > 1) {
                val homeItems = listOf("全部分家") + homes.map { it.homeName }
                item {
                    EtaSegmentedDropdownRow(
                        title = "家庭",
                        items = homeItems,
                        selectedIndex = homes.indexOfFirst { it.homeId == selectedHomeId } + 1,
                        onItemSelected = { index ->
                            onSelectHome(if (index <= 0) null else homes[index - 1].homeId)
                        },
                    )
                }
            }
            if (rooms.isNotEmpty()) {
                val roomItems = listOf("全部房间") + rooms.map { it.roomName }
                item {
                    EtaSegmentedDropdownRow(
                        title = "房间",
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
}

@Composable
private fun MijiaDeviceRow(
    device: MijiaDevice,
    isFavorite: Boolean,
    powerState: Boolean?,
    toggling: Boolean,
    onTogglePower: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    EtaSegmentedColumn(modifier = Modifier.padding(horizontal = EtaGroupHorizontalPadding)) {
        item {
            EtaSegmentedRow(
                supportingContent = listOfNotNull(
                    device.roomName.ifBlank { null },
                    device.model.ifBlank { null },
                    if (device.online) null else "离线",
                ).joinToString(" · ").ifBlank { null }?.let { { Text(text = it) } },
                leadingContent = { PreferenceIcon(icon = Icons.Rounded.Hub) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                            contentDescription = if (isFavorite) "取消常用" else "设为常用",
                            modifier = Modifier
                                .size(40.dp)
                                .padding(8.dp),
                            tint = if (isFavorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        EtaSwitch(
                            checked = powerState == true,
                            onCheckedChange = { onTogglePower() },
                            enabled = device.online && !toggling && powerState != null,
                        )
                    }
                },
                onClick = onToggleFavorite,
            ) {
                Text(text = device.name)
            }
        }
    }
}

// ── OAuth WebView 授权 ───────────────────────────────────────────────────────

private const val MIJIA_OAUTH_REDIRECT_PREFIX = "http://homeassistant.local:8123"

@Composable
private fun MijiaOAuthWebViewOverlay(
    show: Boolean,
    onCodeIntercepted: (String) -> Unit,
    onOpenManualPaste: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    val context = LocalContext.current
    val authorizeUrl = remember { MijiaCloudClient.buildAuthorizeUrl(context, skipConfirm = false) }
    val consumed = remember { AtomicBoolean(false) }
    var currentUrl by remember { mutableStateOf(authorizeUrl) }
    var loadProgress by remember { mutableStateOf(10) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = true) {
        val view = webViewRef
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            onDismiss()
        }
    }

    fun tryConsumeRedirectUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (!url.startsWith(MIJIA_OAUTH_REDIRECT_PREFIX, ignoreCase = true)) return false
        val code = MijiaCloudClient.parseRedirectCode(url)
        if (!code.isNullOrBlank()) {
            if (consumed.compareAndSet(false, true)) {
                onCodeIntercepted(code)
            }
            return true
        }
        return false
    }

    // 全屏页面式表面（EtaPageSurface，兄弟 SubAgentProfileConfigDialog 同款）：
    // 独立窗口 + 0.94 缩放入场，返回键先回退网页历史。不用手接 Dialog + 手写顶栏。
    EtaPageSurface(onDismissRequest = onDismiss) {
        EtaScaffold(
            title = "小米账号 OAuth2 授权",
            onBack = onDismiss,
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                pageError?.let { err ->
                    MijiaStatusLine(err, EtaStatus.Warning)
                }
            },
        ) { paddingValues, _, sidePadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = sidePadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EtaSegmentedRow(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            pageError = null
                            clearWebViewCookies()
                            webViewRef?.apply {
                                clearCache(true)
                                clearHistory()
                                loadUrl(MijiaCloudClient.buildAuthorizeUrl(context, skipConfirm = false))
                            }
                        },
                    ) {
                        Text(
                            text = "清 Cookie 重载",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                    EtaSegmentedRow(
                        modifier = Modifier.weight(1f),
                        onClick = onOpenManualPaste,
                    ) {
                        Text(
                            text = "外部浏览器 / 粘贴回调",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { viewContext ->
                        FrameLayout(viewContext).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            setBackgroundColor(android.graphics.Color.WHITE)
                            val webView = createOAuthWebView(
                                viewContext,
                                authorizeUrl,
                                onProgress = { loadProgress = it },
                                onUrlChanged = { currentUrl = it },
                                onError = { pageError = it },
                                consumeRedirect = ::tryConsumeRedirectUrl,
                                onCreated = { webViewRef = it },
                            )
                            addView(webView)
                        }
                    },
                    onRelease = { container ->
                        runCatching {
                            container.removeAllViews()
                            webViewRef?.stopLoading()
                            webViewRef?.destroy()
                            webViewRef = null
                        }
                    },
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { view ->
                runCatching {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.stopLoading()
                    view.destroy()
                }
            }
            webViewRef = null
        }
    }
}

/** 小米 OAuth 登录 WebView：标准移动端 Chrome UA，拦截回调 URL 提取 code。 */
@SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
private fun createOAuthWebView(
    context: android.content.Context,
    authorizeUrl: String,
    onProgress: (Int) -> Unit,
    onUrlChanged: (String) -> Unit,
    onError: (String?) -> Unit,
    consumeRedirect: (String?) -> Boolean,
    onCreated: (WebView) -> Unit,
): WebView = WebView(context).apply {
    onCreated(this)
    layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    setBackgroundColor(android.graphics.Color.WHITE)
    setLayerType(View.LAYER_TYPE_NONE, null)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.loadsImagesAutomatically = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    settings.safeBrowsingEnabled = false
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    // 使用标准移动端 Chrome UA，避免小米登录前端拒绝自定义客户端 UA
    settings.userAgentString =
        "Mozilla/5.0 (Linux; Android 16; RMX5200) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgress(newProgress)
        }
    }

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val target = request?.url?.toString()
            if (consumeRedirect(target)) return true
            if (!target.isNullOrBlank() &&
                !target.startsWith("http://", ignoreCase = true) &&
                !target.startsWith("https://", ignoreCase = true)
            ) {
                // 拦截 miaccount:// 等自定义协议，防止跳到错误页
                return true
            }
            return false
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            if (consumeRedirect(url)) return true
            if (!url.isNullOrBlank() &&
                !url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) {
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (!url.isNullOrBlank()) onUrlChanged(url)
            if (consumeRedirect(url)) {
                view?.stopLoading()
                return
            }
            onError(null)
            super.onPageStarted(view, url, favicon)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (consumeRedirect(request?.url?.toString())) return
            if (request?.isForMainFrame == true) {
                onError("加载提示：${error?.description ?: "网络异常"}")
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?,
        ) {
            // 小米登录链路存在自签中间页：照 yooma1 上游做法放行，换取 OAuth 成功率。
            handler?.proceed()
        }
    }
    loadUrl(authorizeUrl)
}

private fun clearWebViewCookies() {
    runCatching {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            removeAllCookies(null)
            flush()
        }
    }
}

@Composable
private fun MijiaManualOAuthPasteDialog(
    authorizeUrl: String,
    onSubmitCode: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    EtaDialog(
        show = true,
        title = "外部浏览器授权 / 粘贴回调",
        summary = "在系统浏览器登录小米账号；跳转到 $MIJIA_OAUTH_REDIRECT_PREFIX/?code=…（即使显示无法访问）后，" +
            "复制地址栏完整链接粘贴到下方即可。",
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EtaClickableRow(
                title = "在系统浏览器中打开授权页",
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    }.onFailure { localError = "无法拉起系统浏览器：${it.message}" }
                },
            )
            EtaSegmentedTextField(
                value = inputText,
                onValueChange = {
                    inputText = it
                    localError = null
                },
                label = "粘贴回调 URL 或 code",
                singleLine = false,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            localError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            EtaDialogActions(
                confirmText = "换取 Token",
                onCancel = onDismiss,
                onConfirm = {
                    val raw = inputText.trim()
                    val code = when {
                        raw.startsWith("http://", ignoreCase = true) ||
                            raw.startsWith("https://", ignoreCase = true) -> {
                            MijiaCloudClient.parseRedirectCode(raw)
                                ?: runCatching { Uri.parse(raw).getQueryParameter("code") }.getOrNull()
                        }
                        raw.contains("code=") -> {
                            raw.substringAfter("code=").substringBefore("&").trim()
                        }
                        else -> raw
                    }
                    if (code.isNullOrBlank()) {
                        localError = "未能从输入中解析出有效 code，请复制完整回调地址"
                    } else {
                        onSubmitCode(code)
                    }
                },
            )
        }
    }
}
