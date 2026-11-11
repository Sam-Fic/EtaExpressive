package io.github.mangi.eta.ui.screens.virtualdisplay

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.rememberTaskBackendInstalled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用内虚拟屏视窗：实时画面、状态、接管、移交主屏、关闭，以及网页预览与副屏恢复。
 *
 * 不持有 ViewModel：`VirtualDisplayController` 是进程单例，旋转/重建后状态自然恢复。
 * 页面只订阅去重后的轻量状态，实时帧由舞台自己按 30fps 消费，避免整页跟着重组。
 */
@Composable
internal fun VirtualDisplayScreen(
    onBack: () -> Unit,
    rootAvailable: () -> Boolean = { RootAccess.isGranted },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    // 页面只消费轻量状态：实时帧 30fps 更新时本页不跟着重组。
    // collectAsStateWithLifecycle() 的 StateFlow 重载自行读取当前快照作为初始值，
    // 组合期不直接调 StateFlow.value（会触发 StateFlowValueCalledInComposition）；
    // derivedStateOf 用 data class 的相等性去重，等价于原来的 map + distinctUntilChanged。
    val snapshot by VirtualDisplayController.snapshots.collectAsStateWithLifecycle()
    val status by remember { derivedStateOf { VirtualDisplayStatus.from(snapshot) } }

    var sawActive by remember { mutableStateOf(status.active) }
    var released by remember { mutableStateOf(false) }
    LaunchedEffect(status.active) {
        if (status.active) {
            sawActive = true
            released = false
        } else if (sawActive) {
            released = true
        }
    }

    // 实时画面只在页面可见时升频；离开页面成对复位，避免外部浏览器预览期间仍按 30fps 分配预览图。
    LaunchedEffect(Unit) { VirtualDisplayController.setStageExpanded(true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> VirtualDisplayController.setStageExpanded(true)
                Lifecycle.Event.ON_PAUSE -> {
                    VirtualDisplayController.setStageExpanded(false)
                    VirtualDisplayController.setUserControlling(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            VirtualDisplayController.setStageExpanded(false)
            VirtualDisplayController.setUserControlling(false)
        }
    }

    var handingOff by remember { mutableStateOf(false) }
    var releasing by remember { mutableStateOf(false) }
    var recoveryWorking by remember { mutableStateOf(false) }
    var showCloseConfirm by remember { mutableStateOf(false) }
    val busy = handingOff || releasing || recoveryWorking

    val moduleInstalled = rememberTaskBackendInstalled()
    var previouslyInstalled by remember { mutableStateOf(false) }
    LaunchedEffect(moduleInstalled, busy) {
        if (moduleInstalled == true) previouslyInstalled = true
        if (moduleInstalled == false && previouslyInstalled && !busy) onBack()
    }

    fun handoff() {
        if (busy || !status.active) return
        handingOff = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    VirtualDisplayController.handoffToMainDisplay(context, rootAvailable)
                }.getOrDefault(false)
            }
            if (!ok) {
                Toast.makeText(context, R.string.vd_screen_handoff_failed, Toast.LENGTH_LONG).show()
            }
            handingOff = false
            onBack()
        }
    }

    fun releaseDisplay() {
        if (busy) return
        releasing = true
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { VirtualDisplayController.release(context, rootAvailable) }
            }
            releasing = false
            showCloseConfirm = false
            onBack()
        }
    }

    BackHandler(enabled = busy) { /* 动作进行中不放弃页面，避免半途退出。 */ }

    EtaScaffold(
        title = stringResource(R.string.vd_screen_title),
        onBack = { if (!busy) onBack() },
    ) { paddingValues, scrollConnection, sidePadding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            if (maxWidth >= 600.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            top = paddingValues.calculateTopPadding(),
                            start = sidePadding,
                            end = sidePadding,
                        )
                        .navigationBarsPadding(),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(0.46f)
                            .fillMaxHeight()
                            .padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaDimens.SpaceSm,
                                top = EtaDimens.SpaceMd,
                                bottom = EtaDimens.SpaceLg,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        VirtualDisplayLiveStage(
                            released = released,
                            handingOff = handingOff,
                            onTakeoverChange = { VirtualDisplayController.setUserControlling(it) },
                            onTap = { x, y, longPress ->
                                VirtualDisplayController.dispatchUserTapOnCanvas(
                                    x, y, longPress, rootAvailable,
                                )
                            },
                            onSwipe = { x1, y1, x2, y2, durationMs ->
                                VirtualDisplayController.dispatchUserSwipeOnCanvas(
                                    x1, y1, x2, y2, durationMs, rootAvailable,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = EtaDimens.VirtualDisplayStageMaxHeight),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .weight(0.54f)
                            .fillMaxHeight()
                            .nestedScroll(scrollConnection),
                    ) {
                        virtualDisplaySectionItems(
                            status = status,
                            released = released,
                            enabled = !busy,
                            handoffEnabled = rootAvailable(),
                            onTakeoverChange = { VirtualDisplayController.setUserControlling(it) },
                            onHandoff = ::handoff,
                            onClose = { showCloseConfirm = true },
                            onRecoveryWorkingChanged = { recoveryWorking = it },
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection),
                    contentPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding(),
                        start = sidePadding,
                        end = sidePadding,
                    ),
                ) {
                    item(key = "stage") {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = EtaGroupHorizontalPadding,
                                    end = EtaGroupHorizontalPadding,
                                    bottom = EtaSegmentedGroupSpacing,
                                ),
                        ) {
                            val stageHeight = minOf(
                                EtaDimens.VirtualDisplayStageMaxHeight,
                                maxWidth * 1.15f,
                            )
                            VirtualDisplayLiveStage(
                                released = released,
                                handingOff = handingOff,
                                onTakeoverChange = { VirtualDisplayController.setUserControlling(it) },
                                onTap = { x, y, longPress ->
                                    VirtualDisplayController.dispatchUserTapOnCanvas(
                                        x, y, longPress, rootAvailable,
                                    )
                                },
                                onSwipe = { x1, y1, x2, y2, durationMs ->
                                    VirtualDisplayController.dispatchUserSwipeOnCanvas(
                                        x1, y1, x2, y2, durationMs, rootAvailable,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().height(stageHeight),
                            )
                        }
                    }
                    virtualDisplaySectionItems(
                        status = status,
                        released = released,
                        enabled = !busy,
                        handoffEnabled = rootAvailable(),
                        onTakeoverChange = { VirtualDisplayController.setUserControlling(it) },
                        onHandoff = ::handoff,
                        onClose = { showCloseConfirm = true },
                        onRecoveryWorkingChanged = { recoveryWorking = it },
                    )
                }
            }
        }
    }

    EtaDialog(
        show = showCloseConfirm,
        title = stringResource(R.string.vd_screen_close_confirm_title),
        onDismissRequest = { if (!releasing) showCloseConfirm = false },
        actions = {
            EtaDialogActions(
                confirmText = stringResource(R.string.vd_screen_close),
                onCancel = { if (!releasing) showCloseConfirm = false },
                onConfirm = { releaseDisplay() },
                confirmEnabled = !releasing,
                destructive = true,
            )
        },
    ) {
        Text(
            text = stringResource(R.string.vd_screen_close_confirm_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 状态 / 控制 / 网页与恢复分组：窄屏列表与宽屏右栏共用同一份构建逻辑。 */
private fun LazyListScope.virtualDisplaySectionItems(
    status: VirtualDisplayStatus,
    released: Boolean,
    enabled: Boolean,
    handoffEnabled: Boolean,
    onTakeoverChange: (Boolean) -> Unit,
    onHandoff: () -> Unit,
    onClose: () -> Unit,
    onRecoveryWorkingChanged: (Boolean) -> Unit,
) {
    item(key = "status") {
        VirtualDisplayStatusSection(
            status = status,
            released = released,
            modifier = Modifier.virtualDisplaySectionPadding(),
        )
    }
    item(key = "control") {
        VirtualDisplayControlSection(
            status = status,
            onTakeoverChange = onTakeoverChange,
            onHandoff = onHandoff,
            onClose = onClose,
            modifier = Modifier.virtualDisplaySectionPadding(),
            enabled = enabled,
            handoffEnabled = handoffEnabled,
        )
    }
    item(key = "service") {
        VirtualDisplayRecoverySection(
            modifier = Modifier.virtualDisplaySectionPadding(),
            onWorkingChanged = onRecoveryWorkingChanged,
        )
    }
    item(key = "bottom") {
        Spacer(Modifier.height(24.dp).navigationBarsPadding())
    }
}

private fun Modifier.virtualDisplaySectionPadding(): Modifier = padding(
    start = EtaGroupHorizontalPadding,
    end = EtaGroupHorizontalPadding,
    bottom = EtaSegmentedGroupSpacing,
)
