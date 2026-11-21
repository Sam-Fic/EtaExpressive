package io.github.mangi.eta.ui

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.config.LiveActivityStyle
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedDropdownRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow

/**
 * 实时活动设置：运行状态上岛（执行通知卡提升为 Live Update，由各厂商桥接成岛）、
 * 提醒呈现方式（原生 / 小米超级岛 / ColorOS 流体云）与任务完成横幅、前台抑制。
 * 全部走本地 Agent 配置（[Prefs.Keys.LOCAL_AGENT_KEYS] 同库），消费方在通知链路上
 * 各自尊重总开关与呈现方式。
 */
@Composable
internal fun LiveActivitySettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 总开关提升到页级：细分开关的可用性跟着它走。Prefs 无观察流，行内自持状态即可。
    var masterEnabled by remember { mutableStateOf(Prefs.isEnabled(Prefs.Keys.LIVE_ACTIVITY_ENABLE)) }
    // 提醒呈现方式不随总开关置灰：取码提醒等通知类提醒独立于运行实时活动消费该值。
    var notifyStyle by remember { mutableStateOf(Prefs.liveActivityStyle()) }
    val notifyStyleItems = LiveActivityStyle.entries.map { style ->
        stringResource(
            when (style) {
                LiveActivityStyle.NATIVE -> R.string.live_activity_notify_style_native
                LiveActivityStyle.XIAOMI_ISLAND -> R.string.live_activity_notify_style_island
                LiveActivityStyle.COLOROS_FLUID -> R.string.live_activity_notify_style_fluid
            },
        )
    }
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
                item(key = "notify_style") {
                    EtaSegmentedDropdownRow(
                        title = stringResource(R.string.live_activity_notify_style_title),
                        summary = stringResource(R.string.live_activity_notify_style_summary),
                        icon = Icons.Rounded.Notifications,
                        items = notifyStyleItems,
                        selectedIndex = LiveActivityStyle.entries.indexOf(notifyStyle),
                        onItemSelected = { index ->
                            val style = LiveActivityStyle.entries.getOrNull(index)
                                ?: return@EtaSegmentedDropdownRow
                            if (putStringSync(
                                    prefs = Prefs.localAgentPreferences()
                                        ?: return@EtaSegmentedDropdownRow,
                                    key = Prefs.Keys.LIVE_ACTIVITY_STYLE,
                                    value = style.persistedValue,
                                )
                            ) {
                                notifyStyle = style
                            } else {
                                Toast.makeText(
                                    context.applicationContext,
                                    context.getString(R.string.settings_write_failed),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
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
