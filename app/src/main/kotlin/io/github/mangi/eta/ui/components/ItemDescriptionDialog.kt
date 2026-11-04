package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton

/** 超过该长度或行数的说明改为面板阅读；短文仍保留弹窗的轻量决策语义。 */
private const val DescriptionSheetMinChars = 320
private const val DescriptionSheetMinLines = 8

@Composable
internal fun ItemDescriptionDialog(
    title: String,
    description: String,
    onDismiss: () -> Unit,
    summary: String? = null,
) {
    val prefersSheet = description.length >= DescriptionSheetMinChars ||
        description.count { it == '\n' } >= DescriptionSheetMinLines
    if (prefersSheet) {
        ItemDescriptionSheet(title, description, summary, onDismiss)
        return
    }
    EtaDialog(
        show = true,
        title = title,
        summary = summary?.takeIf(String::isNotBlank),
        onDismissRequest = onDismiss,
        actions = {
            // 官方文字按钮：形状、行高与配色均取官方默认值。
            M3TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                M3Text(text = stringResource(R.string.action_close))
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EtaText(
                text = description,
                style = EtaTextStyle.Secondary,
                modifier = Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDescriptionSheet(
    title: String,
    description: String,
    summary: String?,
    onDismiss: () -> Unit,
) {
    // 长说明按阅读面处理：整高面板 + 可选择正文，避免在 360dp 的弹窗里反复滚动。
    // 关闭走面板下拉 / 点遮罩 / 返回键，不放右上角关闭按钮。
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 官方新版面板状态：只允许 Hidden / Expanded 两态，等价于旧的 skipPartiallyExpanded。
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                M3Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                summary?.takeIf(String::isNotBlank)?.let {
                    M3Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider()
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    M3Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}
