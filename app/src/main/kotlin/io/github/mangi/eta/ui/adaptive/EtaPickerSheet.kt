package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * 长列表选择器的统一底部面板：标题行、可选搜索框、调用方列表与可选动作区。
 * 关闭走面板下拉 / 点遮罩 / 返回键，不放右上角关闭按钮。
 * 面板不抢占半展开态；列表应有自己的高度上限与滚动，短列表保持内容高度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EtaPickerSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    query: String? = null,
    onQueryChange: ((String) -> Unit)? = null,
    searchPlaceholder: String = "",
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 12.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
            }
            if (onQueryChange != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query.orEmpty(),
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(searchPlaceholder) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                Spacer(Modifier.height(8.dp))
            }
            content()
            if (actions != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    content = actions,
                )
            }
        }
    }
}

/**
 * 枚举型单选底部面板：标题 + 可选说明 + KernelSU 式分段单选列表，点选即提交并关闭。
 *
 * 与「标题模型」「离线语音下载源」等处同一套写法；各设置页不再为枚举选择单独实现弹窗。
 */
@Composable
internal fun EtaChoiceSheet(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onSelected: (Int) -> Unit,
    summary: String? = null,
) {
    EtaPickerSheet(title = title, onDismiss = onDismiss) {
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
        ) {
            EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                items.forEachIndexed { index, label ->
                    item(key = index) {
                        EtaSegmentedRadioRow(
                            title = label,
                            selected = index == selectedIndex,
                            onClick = { onSelected(index) },
                        )
                    }
                }
            }
        }
    }
}
