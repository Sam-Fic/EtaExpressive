package io.github.mangi.eta.ui.screens.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.formatTokenCount
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import io.github.mangi.eta.ui.screens.stats.formatCacheHitRate

/**
 * 当前会话的 token 用量页。
 *
 * 从顶栏溢出菜单「Token usage」跳入；数据由调用方按当前会话实时投影，所以页面留在返回栈上
 * 也会随对话继续增长。行样式与统计页的指标行一致（标签 + 尾部数值的分段行），
 * 无用量时走共享空态组件。
 */
@Composable
internal fun ConversationTokenUsageScreen(
    usage: ConversationTokenUsageUi,
    onBack: () -> Unit,
) {
    EtaScaffoldPage(
        title = stringResource(R.string.action_token_usage),
        onBack = onBack,
    ) {
        if (!usage.hasUsage) {
            item(key = "empty") {
                ListEmptyState(
                    title = stringResource(R.string.token_usage_empty),
                    modifier = Modifier.padding(bottom = 13.dp),
                )
            }
        } else {
            item(key = "usage") {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                ) {
                    item(key = "context") {
                        // 上下文是「最近一轮请求的输入」，不是各轮累加；后者会被前缀重发放大多倍。
                        TokenUsageRow(
                            label = stringResource(R.string.token_usage_context),
                            value = formatTokenCount(usage.lastInputTokens),
                        )
                    }
                    item(key = "fresh-input") {
                        TokenUsageRow(
                            label = stringResource(R.string.stats_page_fresh_input_tokens),
                            value = formatTokenCount(usage.freshInputTokens),
                        )
                    }
                    item(key = "output") {
                        TokenUsageRow(
                            label = stringResource(R.string.stats_page_output_tokens),
                            value = formatTokenCount(usage.outputTokens),
                        )
                    }
                    item(key = "cached") {
                        TokenUsageRow(
                            label = stringResource(R.string.stats_page_cached_tokens),
                            value = formatTokenCount(usage.cachedTokens),
                        )
                    }
                    // 缓存写入只有 Anthropic 一类 provider 会回报；OpenAI 兼容接口永远是 0，
                    // 一行恒为 0 的指标只是噪音，没有该项时就整行不显示。
                    if (usage.cacheCreationTokens > 0) {
                        item(key = "cache-writes") {
                            TokenUsageRow(
                                label = stringResource(R.string.stats_page_cache_creation_tokens),
                                value = formatTokenCount(usage.cacheCreationTokens),
                            )
                        }
                    }
                    item(key = "cache-rate") {
                        TokenUsageRow(
                            label = stringResource(R.string.stats_model_cache_hit_rate),
                            value = formatCacheHitRate(usage.cachedTokens, usage.inputTokens),
                        )
                    }
                    item(key = "total") {
                        TokenUsageRow(
                            label = stringResource(R.string.token_usage_total),
                            value = formatTokenCount(usage.totalTokens),
                        )
                    }
                }
            }
        }
    }
}

/** 标签 / 数值指标行：非交互，用分段行呈现（与统计页指标行同款）。 */
@Composable
private fun TokenUsageRow(label: String, value: String) {
    EtaSegmentedRow(
        content = { Text(text = label, style = MaterialTheme.typography.bodyLarge) },
        trailingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}
