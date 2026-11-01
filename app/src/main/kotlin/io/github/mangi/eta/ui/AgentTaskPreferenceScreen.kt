package io.github.mangi.eta.ui
import io.github.mangi.eta.ui.components.etaRippleConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.components.WithoutPressRipple
import io.github.mangi.eta.ui.haptics.TouchHaptics

/** 二级页骨架（EtaScaffoldPage）：大标题顶栏 + KernelSU 式分段单选组与虚拟屏恢复入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentTaskPreferenceScreen(
    onBack: () -> Unit,
    onRecoveryWorkingChanged: (Boolean) -> Unit = {},
) {
    val moduleInstalled = rememberTaskBackendInstalled()
    var previouslyInstalled by remember { mutableStateOf(false) }
    var recoveryWorking by remember { mutableStateOf(false) }
    LaunchedEffect(moduleInstalled, recoveryWorking) {
        if (moduleInstalled == true) previouslyInstalled = true
        if (moduleInstalled == false && !recoveryWorking) onBack()
    }
    // A RESUMED recheck temporarily returns null. Do not dispose the browser's preview controls.
    if (!previouslyInstalled && moduleInstalled != true) return

    val view = LocalView.current
    var selected by remember { mutableStateOf(AgentTaskSurface.stored()) }
    CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration()) {
        WithoutPressRipple {
            EtaScaffoldPage(
                title = stringResource(R.string.agent_task_surface_title),
                // 骨架的返回钮不支持禁用态：恢复进行中用软门槛守住，不放行返回。
                onBack = { if (!recoveryWorking) onBack() },
            ) {
                item(key = "surface-mode") {
                    // KernelSU 式分段列表：一组的单选项用分段形状 + 表面底色表达，
                    // 不再用整块 Card 包一层列表。间距取官方 SegmentedGap。
                    Column(
                        modifier = Modifier
                            .padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                bottom = EtaSegmentedGroupSpacing,
                            )
                            .selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    ) {
                        AgentTaskSurfaceMode.entries.forEachIndexed { index, mode ->
                            val canSelect = AgentTaskSurface.allowsPersist(mode)
                            EtaSegmentedItem(index = index, count = AgentTaskSurfaceMode.entries.size) {
                                EtaSegmentedRow(
                                    choiceRow = true,
                                    modifier = Modifier.selectable(
                                        selected = selected == mode,
                                        enabled = canSelect,
                                        role = Role.RadioButton,
                                        onClick = {
                                            if (!canSelect) return@selectable
                                            TouchHaptics.click(view)
                                            selected = mode
                                            AgentTaskSurface.save(mode)
                                        },
                                    ),
                                    content = {
                                        Text(
                                            stringResource(mode.labelRes),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        // 字号 / 配色交给官方 supportingContent 槽位默认（bodyMedium + onSurfaceVariant）。
                                        Text(stringResource(mode.hintRes))
                                    },
                                    trailingContent = {
                                        RadioButton(
                                            selected = selected == mode,
                                            enabled = canSelect || selected == mode,
                                            onClick = null,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
                item(key = "virtual-display-recovery") {
                    VirtualDisplayRecoveryControls(
                        modifier = Modifier.padding(
                            start = EtaGroupHorizontalPadding,
                            end = EtaGroupHorizontalPadding,
                            bottom = EtaSegmentedGroupSpacing,
                        ),
                        onWorkingChanged = {
                            recoveryWorking = it
                            onRecoveryWorkingChanged(it)
                        },
                    )
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
