package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow

/**
 * 实时活动设置：运行状态上岛（执行通知卡提升为 Live Update，由各厂商桥接成岛）与
 * 任务完成横幅、前台抑制。三个开关都走本地 Agent 配置（[Prefs.Keys.LOCAL_AGENT_KEYS]），
 * 消费方在通知链路上各自尊重总开关。
 */
@Composable
internal fun LiveActivitySettingsScreen(onBack: () -> Unit) {
    // 总开关提升到页级：细分开关的可用性跟着它走。Prefs 无观察流，行内自持状态即可。
    var masterEnabled by remember { mutableStateOf(Prefs.isEnabled(Prefs.Keys.LIVE_ACTIVITY_ENABLE)) }
    EtaScaffoldPage(
        title = stringResource(R.string.live_activity_settings_title),
        onBack = onBack,
    ) {
        item(key = "live_activity_switches") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
                title = stringResource(R.string.live_activity_group_title),
            ) {
                item(key = "enable") {
                    EtaSwitchRow(
                        title = stringResource(R.string.live_activity_enable_title),
                        summary = stringResource(R.string.live_activity_enable_summary),
                        checked = masterEnabled,
                        onCheckedChange = { value ->
                            masterEnabled = value
                            Prefs.putBoolean(Prefs.Keys.LIVE_ACTIVITY_ENABLE, value)
                        },
                    )
                }
                item(key = "completion") {
                    var checked by remember {
                        mutableStateOf(Prefs.isEnabled(Prefs.Keys.LIVE_ACTIVITY_COMPLETION_BANNER))
                    }
                    EtaSwitchRow(
                        title = stringResource(R.string.live_completion_enable_title),
                        summary = stringResource(R.string.live_completion_enable_summary),
                        checked = checked,
                        enabled = masterEnabled,
                        onCheckedChange = { value ->
                            checked = value
                            Prefs.putBoolean(Prefs.Keys.LIVE_ACTIVITY_COMPLETION_BANNER, value)
                        },
                    )
                }
                item(key = "suppress") {
                    var checked by remember {
                        mutableStateOf(Prefs.isEnabled(Prefs.Keys.LIVE_ACTIVITY_FOREGROUND_SUPPRESS))
                    }
                    EtaSwitchRow(
                        title = stringResource(R.string.live_suppress_enable_title),
                        summary = stringResource(R.string.live_suppress_enable_summary),
                        checked = checked,
                        enabled = masterEnabled,
                        onCheckedChange = { value ->
                            checked = value
                            Prefs.putBoolean(Prefs.Keys.LIVE_ACTIVITY_FOREGROUND_SUPPRESS, value)
                        },
                    )
                }
            }
        }
    }
}
