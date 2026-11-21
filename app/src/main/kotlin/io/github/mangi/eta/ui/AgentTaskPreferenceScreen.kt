package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRadioRow
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.WithoutPressRipple
import io.github.mangi.eta.ui.components.etaRippleConfiguration

/** 二级页骨架（EtaScaffoldPage）：大标题顶栏 + 执行位置单选组 + 虚拟屏实时画面入口。 */
@Composable
internal fun AgentTaskPreferenceScreen(
    onBack: () -> Unit,
    onOpenVirtualDisplay: () -> Unit = {},
) {
    var selected by remember { mutableStateOf(AgentTaskSurface.stored()) }
    // 只订阅去重后的「运行中 / 应用名 / 显示 ID」：实时帧以 6fps 更新时本页不跟着重组。
    // collectAsStateWithLifecycle() 的 StateFlow 重载自行读取当前快照作为初始值，
    // 组合期不直接调 StateFlow.value（会触发 StateFlowValueCalledInComposition）；
    // derivedStateOf 只在三个字段有变化时才产出新值，等价于原来的 map + distinctUntilChanged。
    val snapshot by VirtualDisplayController.snapshots.collectAsStateWithLifecycle()
    val display by remember {
        derivedStateOf { Triple(snapshot.active, snapshot.appName, snapshot.displayId) }
    }
    val (displayActive, displayApp, _) = display
    // 实时画面走进程内 VirtualDisplayController，不依赖 Magisk 后端模块；
    // 模块只影响虚拟屏页里的网页预览与副屏恢复，因此入口摘要只反映真实运行状态。
    val entrySummary = when {
        displayActive && displayApp.isNotBlank() ->
            stringResource(R.string.vd_screen_entry_running, displayApp)
        displayActive -> stringResource(R.string.vd_screen_entry_running_generic)
        else -> stringResource(R.string.vd_screen_entry_idle)
    }
    CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration()) {
        WithoutPressRipple {
            EtaScaffoldPage(
                title = stringResource(R.string.agent_task_surface_title),
                onBack = onBack,
            ) {
                item(key = "surface-mode") {
                    // KernelSU 式分段列表：一组的单选项用分段形状 + 表面底色表达，
                    // 不再用整块 Card 包一层列表。间距取官方 SegmentedGap。
                    EtaSegmentedColumn(
                        modifier = Modifier
                            .padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                bottom = EtaSegmentedGroupSpacing,
                            )
                            .selectableGroup(),
                        title = stringResource(R.string.agent_task_surface_group_title),
                    ) {
                        AgentTaskSurfaceMode.entries.forEach { mode ->
                            item(key = mode.name) {
                                val canSelect = AgentTaskSurface.allowsPersist(mode)
                                // 官方单选分段行：selected 变体自带 secondaryContainer 选中底色、
                                // 4dp→16dp 形状 morph、按压 morph 与 Role.RadioButton 语义，
                                // 不要退回 Modifier.selectable + 行尾 RadioButton 的手拼写法。
                                EtaSegmentedRadioRow(
                                    title = stringResource(mode.labelRes),
                                    summary = stringResource(mode.hintRes),
                                    selected = selected == mode,
                                    enabled = canSelect,
                                    onClick = {
                                        selected = mode
                                        AgentTaskSurface.save(mode)
                                    },
                                )
                            }
                        }
                    }
                }
                item(key = "virtual-display-entry") {
                    EtaSegmentedColumn(
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            bottom = EtaSegmentedGroupSpacing,
                        ),
                        title = stringResource(R.string.vd_screen_title),
                    ) {
                        item(key = "live-view") {
                            EtaClickableRow(
                                title = stringResource(R.string.vd_screen_entry_title),
                                summary = entrySummary,
                                leading = { PreferenceIcon(Icons.Rounded.DesktopWindows) },
                                onClick = onOpenVirtualDisplay,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 每项的执行说明。ASK 首次为其它应用确定位置时弹窗；Eta自身直接前台，不改普通应用选择。 */
private val AgentTaskSurfaceMode.hintRes: Int
    get() = when (this) {
        AgentTaskSurfaceMode.ASK -> R.string.agent_task_preference_mode_hint_ask
        AgentTaskSurfaceMode.FOREGROUND -> R.string.agent_task_preference_mode_hint_foreground
        AgentTaskSurfaceMode.BACKGROUND -> R.string.agent_task_preference_mode_hint_background
    }
