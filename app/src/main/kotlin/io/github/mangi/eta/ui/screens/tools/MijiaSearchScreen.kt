package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mijia.MijiaCloudClient
import io.github.mangi.eta.agent.mijia.MijiaDevice
import io.github.mangi.eta.agent.mijia.MijiaStore
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSearchHistory
import io.github.mangi.eta.ui.adaptive.EtaSearchPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaStatus
import io.github.mangi.eta.ui.components.ListEmptyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 查询历史分域键：米家设备搜索历史独立存储。 */
private const val MIJIA_SEARCH_HISTORY_KEY = "mijia"

/** 查询串的分词分隔符：连续空白都算一个分隔。 */
private val SEARCH_WHITESPACE = Regex("\\s+")

/**
 * 米家设备独立搜索页（`AppRoute.MijiaSearch`）——设置搜索页同款设计：
 * 从米家直连页右上角的单搜索图标跳入，整页即搜索（官方 [EtaSearchPage]，进入即聚焦）；
 * 未输入时展示查询历史，结果行复用设置页的 [MijiaDeviceRow]（星标/电源就地可操作），
 * 设备数据直接读 [MijiaStore] 本地缓存，与设置页互不共享状态。
 */
@Composable
internal fun MijiaSearchScreen(onBack: () -> Unit) {
    // IO/存储用 application context；用户可见文案走 Activity 上下文（per-app locale）。
    val context = LocalContext.current.applicationContext
    val uiContext = LocalContext.current
    val scope = rememberCoroutineScope()

    var devices by remember { mutableStateOf<List<MijiaDevice>>(emptyList()) }
    var favoriteDids by remember { mutableStateOf<List<String>>(emptyList()) }
    val devicePowerStates = remember { mutableStateMapOf<String, Boolean>() }
    val togglingDids = remember { mutableStateMapOf<String, Boolean>() }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            devices = MijiaStore.loadDevices(context)
            favoriteDids = MijiaStore.favoriteDids(context)
        }
    }

    fun handleTogglePower(device: MijiaDevice) {
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

    MijiaDevicePowerFetcher(devices, devicePowerStates)

    EtaSearchPage(
        searchPlaceholder = stringResource(R.string.mijia_search_label),
        onBack = onBack,
        historyKey = MIJIA_SEARCH_HISTORY_KEY,
    ) { query ->
        // LazyListScope 不是 Composable 作用域，过滤与排序直接内联计算（SettingsSearchScreen 同款）。
        // 多词 AND：每个词都要命中设备任一可搜字段。
        val tokens = query.trim().lowercase().split(SEARCH_WHITESPACE).filter { it.isNotBlank() }
        val matched = devices
            .filter { dev ->
                tokens.all { token ->
                    dev.name.lowercase().contains(token) ||
                        dev.roomName.lowercase().contains(token) ||
                        dev.homeName.lowercase().contains(token) ||
                        dev.model.lowercase().contains(token) ||
                        dev.category.lowercase().contains(token)
                }
            }
            .sortedMijiaList(favoriteDids)

        statusMessage?.let {
            item(key = "status") {
                MijiaStatusLine(it, EtaStatus.Success, modifier = Modifier.padding(bottom = EtaSegmentedGroupSpacing))
            }
        }
        errorMessage?.let {
            item(key = "error") {
                MijiaStatusLine(it, EtaStatus.Failed, modifier = Modifier.padding(bottom = EtaSegmentedGroupSpacing))
            }
        }

        if (devices.isEmpty()) {
            // 非整页零态走分段卡片做底板（全仓空态两档约定）。
            item(key = "empty") {
                EtaSegmentedItemContainer(
                    modifier = Modifier.padding(horizontal = EtaGroupHorizontalPadding),
                ) {
                    ListEmptyState(
                        title = stringResource(R.string.mijia_empty_devices),
                        summary = stringResource(R.string.mijia_empty_devices_hint),
                    )
                }
            }
        } else if (matched.isEmpty()) {
            item(key = "empty") {
                EtaSegmentedItemContainer(
                    modifier = Modifier.padding(horizontal = EtaGroupHorizontalPadding),
                ) {
                    ListEmptyState(title = stringResource(R.string.mijia_no_match))
                }
            }
        } else {
            matched.forEachIndexed { index, device ->
                item(key = device.did) {
                    EtaSegmentedItem(index = index, count = matched.size) {
                        MijiaDeviceRow(
                            device = device,
                            isFavorite = device.did in favoriteDids,
                            powerState = devicePowerStates[device.did],
                            toggling = togglingDids[device.did] == true,
                            onTogglePower = { handleTogglePower(device) },
                            onToggleFavorite = { handleToggleFavorite(device) },
                            modifier = Modifier.padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                bottom = if (index == matched.lastIndex) {
                                    EtaSegmentedGroupSpacing
                                } else {
                                    EtaSegmentedGap
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}
