package io.github.mangi.eta.ui
import io.github.mangi.eta.ui.components.etaRippleConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.ui.components.WithoutPressRipple
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaTopBarBackButton

/** Material 3 骨架：小标题顶栏 + 返回；内容可滚动并沿用已有页面的宽屏限宽。 */
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
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.surface,
                // 与全应用同口径：底部不垫导航栏，滚动内容铺到屏幕底缘，导航栏高度补在内容末尾。
                contentWindowInsets = WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                stringResource(R.string.agent_task_surface_title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            EtaTopBarBackButton(
                                onClick = {
                                    TouchHaptics.click(view)
                                    if (!recoveryWorking) onBack()
                                },
                                enabled = !recoveryWorking,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .horizontalCutoutPadding(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 640.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            // 导航栏高度补在滚动内容末尾，页面本身铺到屏幕底缘。
                            .navigationBarsPadding()
                            .padding(
                                PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 12.dp,
                                    bottom = 32.dp,
                                ),
                            ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // KernelSU 式分段列表：一组的单选项用分段形状 + 表面底色表达，
                        // 不再用整块 Card 包一层列表。间距取官方 SegmentedGap。
                        Column(
                            modifier = Modifier.selectableGroup(),
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
                        VirtualDisplayRecoveryControls(onWorkingChanged = {
                            recoveryWorking = it
                            onRecoveryWorkingChanged(it)
                        })
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
