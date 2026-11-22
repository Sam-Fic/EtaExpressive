// Adapted from OpenMinis/OpenMinis @ 4ef29002e88db1e20e462ec2ff46916e8a7dcb45.
// SPDX-License-Identifier: GPL-3.0-only
// Local/private integration; see third_party/openminis-browser/NOTICE.md.
package io.github.mangi.eta.agent.browser.ported.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.ported.browser.BrowserHistoryStore
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.components.EtaStatusPage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史面板（M3 Expressive）：官方 [ModalBottomSheet] + [TopAppBar] 页头，
 * 列表走项目分段列表套件（[EtaSegmentedColumn] / [EtaSegmentedRow]），
 * 按天分组，组标题用官方 `titleSmallEmphasized` + primary。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserHistorySheet(
    historyStore: BrowserHistoryStore,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Expanded,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    val resultsLabel = stringResource(R.string.om_browser_history_results)
    // 列表铺到面板底缘；导航栏高度补在内容末尾，滚动时条目从手势条下方透出。
    val navigationBarBottom =
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val entries = remember(searchQuery, historyStore.getEntries().size) {
        if (searchQuery.isBlank()) historyStore.groupedByDay()
        else mapOf(resultsLabel to historyStore.search(searchQuery))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            BrowserSheetTopBar(
                title = stringResource(R.string.om_browser_history_title),
                onDismiss = onDismiss,
                actions = {
                    TextButton(
                        onClick = { showClearConfirm = true },
                        enabled = historyStore.getEntries().isNotEmpty() && !showClearConfirm,
                    ) {
                        Text(
                            text = stringResource(R.string.om_browser_history_clear),
                            color = if (historyStore.getEntries().isNotEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                },
            )

            if (showClearConfirm) {
                // 清理确认在面板原位完成，避免在面板之上再叠一层弹窗。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.om_browser_history_clear_dialog_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.om_browser_history_clear_dialog_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showClearConfirm = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        TextButton(onClick = {
                            historyStore.clear()
                            showClearConfirm = false
                        }) {
                            Text(
                                text = stringResource(R.string.om_browser_history_clear),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            } else {

                // 搜索框：官方 OutlinedTextField（前导搜索图标，去掉自绘圆角与字阶覆盖）。
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.om_browser_history_search)) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Rounded.Search, contentDescription = null)
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                if (entries.values.flatten().isEmpty()) {
                    // 空态收敛到共享状态页 [EtaStatusPage]（图标随面板语义取 History）。
                    EtaStatusPage(
                        icon = Icons.Rounded.History,
                        title = stringResource(R.string.om_browser_history_empty_title),
                        summary = stringResource(R.string.om_browser_history_empty_subtitle),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = navigationBarBottom),
                    ) {
                        for ((dayLabel, dayEntries) in entries) {
                            if (dayEntries.isEmpty()) continue
                            item(key = "group_$dayLabel") {
                                EtaSegmentedColumn(
                                    title = dayLabel,
                                    modifier = Modifier.padding(
                                        start = EtaGroupHorizontalPadding,
                                        end = EtaGroupHorizontalPadding,
                                        bottom = EtaSegmentedGroupSpacing,
                                    ),
                                ) {
                                    dayEntries.forEach { entry ->
                                        item(key = entry.id) {
                                            EtaSegmentedRow(
                                                onClick = { onNavigate(entry.url) },
                                                modifier = Modifier.fillMaxWidth(),
                                                leadingContent = {
                                                    Icon(
                                                        imageVector = Icons.Rounded.History,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                },
                                                content = {
                                                    Text(
                                                        text = entry.title.ifEmpty { entry.domain },
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                supportingContent = {
                                                    Text(
                                                        text = entry.url,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                trailingContent = {
                                                    Text(
                                                        text = SimpleDateFormat("HH:mm", LocalConfiguration.current.locales[0])
                                                            .format(Date(entry.timestamp)),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item(key = "eta_bottom_spacer") {
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}
