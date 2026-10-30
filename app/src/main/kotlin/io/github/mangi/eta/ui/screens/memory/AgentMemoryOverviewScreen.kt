package io.github.mangi.eta.ui.screens.memory

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.components.AssistantAvatar
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 记忆总览：一个全局总开关，加上所有助手的记忆开关入口。
 *
 * 具体记忆内容仍然保存在各助手自己的 MEMORY.md；
 * 这里只负责一站式开关管理，并作为进入单个助手编辑页的入口。
 */
@Composable
internal fun AgentMemoryOverviewScreen(
    onBack: () -> Unit,
    onOpenAssistant: (String) -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val profiles by AssistantRepository.profiles.collectAsState()
    val enabledFlow = remember { AgentMemoryRepository.enabledFlow() }
    val globalEnabled by enabledFlow.collectAsState(
        initial = AgentMemoryRepository.isGloballyEnabled(),
    )
    val listState = rememberLazyListState()

    fun persistGlobal(enabled: Boolean) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AgentMemoryRepository.persistGloballyEnabled(enabled) }
            }
            if (result.isFailure) {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun persistAssistant(assistantId: String, enabled: Boolean) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AgentMemoryRepository.setEnabled(enabled, assistantId) }
            }
            if (result.isFailure) {
                Toast.makeText(
                    context.applicationContext,
                    context.getString(R.string.settings_write_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.ui_memory_b55ff5),
        onBack = onBack,
        listState = listState,
    ) {
        item(key = "note") {
            EtaText(
                text = stringResource(R.string.memory_overview_note),
                style = EtaTextStyle.Secondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
            )
        }

        item(key = "global") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                title = stringResource(R.string.memory_global_group),
            ) {
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.memory_global_switch_title),
                        summary = stringResource(R.string.memory_global_switch_summary),
                        checked = globalEnabled,
                        leading = {
                            // 官方默认几何（24dp + ItemBetweenSpace 12dp）；着色统一设置页 onSurfaceVariant。
                            Icon(
                                imageVector = Icons.Rounded.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onCheckedChange = ::persistGlobal,
                    )
                }
            }
        }

        item(key = "assistants") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                title = stringResource(R.string.memory_assistants_group),
            ) {
                profiles.forEach { profile ->
                    item(key = profile.id) {
                        EtaClickableRow(
                            title = profile.name,
                            summary = stringResource(
                                if (profile.memoryEnabled) R.string.memory_assistant_row_enabled
                                else R.string.memory_assistant_row_disabled,
                            ),
                            leading = { AssistantAvatar(assistant = profile, size = 32.dp) },
                            // 行本体点进助手记忆子页；箭头用来暗示「这一行可进入下一级」，
                            // 避免开关成了唯一可辨认的行尾元素。
                            showChevron = true,
                            onClick = { onOpenAssistant(profile.id) },
                            endActions = {
                                EtaSwitch(
                                    checked = profile.memoryEnabled,
                                    onCheckedChange = { enabled ->
                                        TouchHaptics.toggle(view, enabled)
                                        persistAssistant(profile.id, enabled)
                                    },
                                    // 行尾现在是「开关 + 箭头」两个元素，补一点间距免得贴在一起。
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
