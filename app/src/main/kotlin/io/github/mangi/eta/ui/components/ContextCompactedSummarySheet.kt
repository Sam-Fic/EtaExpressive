package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.rememberCopyToClipboard
import io.github.mangi.eta.ui.adaptive.rememberEtaSheetState
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.delay

/**
 * 只读摘要/诊断全文面板。
 *
 * 面板外壳、遮罩、拖拽把手、圆角、容器色与底部安全区全部来自官方 [ModalBottomSheet]；
 * 顶部操作为官方 [IconButton]，分隔线为官方 [HorizontalDivider]，正文为官方可滚动可选择 [Text]。
 * 关闭走面板下拉 / 点遮罩 / 返回键，不在右上角放关闭按钮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextCompactedSummarySheet(
    summary: String,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.context_compacted_summary_title),
    compressorLabel: String = "",
) {
    // 修剪态的 compressorLabel 是协议标记（见 AgentEvent.PRUNING_ONLY_COMPRESSOR_LABEL），
    // 这里按当前语言解析为展示文案；其余值是调用方传入的「供应商 · 模型」，原样展示。
    val subtitle = when (compressorLabel) {
        AgentEvent.PRUNING_ONLY_COMPRESSOR_LABEL -> stringResource(R.string.context_pruning_label)
        else -> compressorLabel.trim()
    }
    val view = LocalView.current
    @Suppress("DEPRECATION")
    val copyToClipboard = rememberCopyToClipboard()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberEtaSheetState(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val copyDescription = stringResource(
                    if (copied) R.string.copy_copied else R.string.ui_copy_4edd1d,
                )
                EtaTooltip(text = copyDescription) {
                    IconButton(
                        onClick = {
                            TouchHaptics.click(view)
                            @Suppress("DEPRECATION")
                            copyToClipboard(summary)
                            copied = true
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                            contentDescription = copyDescription,
                            tint = if (copied) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp).align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}