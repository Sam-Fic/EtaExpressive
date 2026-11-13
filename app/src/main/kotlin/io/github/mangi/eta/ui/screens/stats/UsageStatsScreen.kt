package io.github.mangi.eta.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.repository.ModelUsageModelUi
import io.github.mangi.eta.data.repository.ModelUsageProviderUi
import io.github.mangi.eta.data.repository.ModelUsageSnapshot
import io.github.mangi.eta.data.repository.UsageStatsRepository
import io.github.mangi.eta.data.repository.UsageStatsSnapshot
import io.github.mangi.eta.data.repository.formatStatCount
import io.github.mangi.eta.data.repository.formatTokenCount
import io.github.mangi.eta.data.repository.heatmapAlpha
import io.github.mangi.eta.data.repository.heatmapQuartiles
import io.github.mangi.eta.ui.adaptive.EtaExpandChevron
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaLoadingGate
import io.github.mangi.eta.ui.adaptive.EtaLoadingSlot
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaTrailingChevron
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.horizontalScrollEdgeFade
import io.github.mangi.eta.ui.haptics.TouchHaptics
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.toShape

private enum class UsageStatsTab { Overview, Models }

@Composable
internal fun UsageStatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var stats by remember { mutableStateOf(UsageStatsSnapshot(isLoading = true)) }
    var selectedTab by remember { mutableStateOf(UsageStatsTab.Overview) }

    LaunchedEffect(Unit) {
        stats = withContext(Dispatchers.IO) {
            runCatching { UsageStatsRepository.load(context) }
                .getOrElse { UsageStatsSnapshot(isLoading = false) }
        }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.stats_page_title),
        onBack = onBack,
    ) {
        item(key = "tabs") {
            UsageStatsTabs(
                selected = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = EtaSegmentedGroupSpacing),
            )
        }
        if (stats.isLoading) {
            item(key = "loading") {
                // 统计首载通常几十毫秒：门控 120ms 后再显示，避免整页闪一下。
                EtaLoadingGate(visible = true) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        EtaLoadingSlot(
                            progress = null,
                            contained = true,
                            size = EtaDimens.BlockLoadingSize,
                        )
                    }
                }
            }
        } else if (selectedTab == UsageStatsTab.Models) {
            item(key = "model-usage") {
                ModelUsagePane(
                    usage = stats.modelUsage,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        } else {
            item(key = "heatmap") {
                HeatmapGroup(conversationsPerDay = stats.conversationsPerDay)
            }
            item(key = "stats") {
                OverviewStatsGroup(stats = stats)
            }
        }
    }
}

/**
 * 概览 / 模型视图切换。
 *
 * 走官方单选分段按钮 [SingleChoiceSegmentedButtonRow] + [SegmentedButton]
 * （形状取官方 [SegmentedButtonDefaults]，配色/行高/字阶均使用官方默认值）。
 */
@Composable
private fun UsageStatsTabs(
    selected: UsageStatsTab,
    onSelect: (UsageStatsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = listOf(
        stringResource(R.string.stats_tab_overview) to UsageStatsTab.Overview,
        stringResource(R.string.stats_tab_models) to UsageStatsTab.Models,
    )
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        tabs.forEachIndexed { index, (label, tab) ->
            SegmentedButton(
                selected = selected == tab,
                onClick = { onSelect(tab) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
            ) {
                M3Text(text = label)
            }
        }
    }
}

/** 说明文字档位：图例 / 次要说明 / 主要数值。 */
private enum class StatsTextTier { Legend, Secondary, Primary }

/**
 * 统计页的说明文字。
 *
 * 走官方字阶（labelMedium / bodyMedium / bodyLarge）与官方颜色角色。
 */
@Composable
private fun StatsLabel(
    text: String,
    modifier: Modifier = Modifier,
    tier: StatsTextTier = StatsTextTier.Secondary,
    maxLines: Int = Int.MAX_VALUE,
) {
    M3Text(
        text = text,
        modifier = modifier,
        style = when (tier) {
            StatsTextTier.Legend -> MaterialTheme.typography.labelMedium
            StatsTextTier.Secondary -> MaterialTheme.typography.bodyMedium
            StatsTextTier.Primary -> MaterialTheme.typography.bodyLarge
        },
        color = when (tier) {
            StatsTextTier.Legend -> MaterialTheme.colorScheme.onSurfaceVariant
            StatsTextTier.Secondary -> MaterialTheme.colorScheme.onSurfaceVariant
            StatsTextTier.Primary -> MaterialTheme.colorScheme.onSurface
        },
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 热力图刻度小字。热力图是两条分支共享的绘制路径，
 * 字号由调用方给定（数据可视化刻度，不走字阶）。
 */
@Composable
private fun HeatmapCaption(
    text: String,
    fontSize: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    M3Text(
        text = text,
        modifier = modifier,
        fontSize = fontSize,
        // 行高与字号一致：不继承环境字阶的大行高，避免 CJK 回退字体度量
        // 把小字挤出格子、与相邻内容重叠或看起来被裁切。
        lineHeight = fontSize,
        color = color,
        maxLines = maxLines,
    )
}

/** 概览页热力图分组：组标题 + 共享表面容器里的热力图与图例。 */
@Composable
private fun HeatmapGroup(conversationsPerDay: Map<LocalDate, Int>) {
    EtaSegmentedColumn(
        modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
        title = stringResource(R.string.stats_page_heatmap_title),
    ) {
        item {
            EtaSegmentedItemContainer {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val cellShapes = heatmapCellShapes()
                    ChatHeatmap(
                        conversationsPerDay = conversationsPerDay,
                        cellShapes = cellShapes,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatsLabel(
                            text = stringResource(R.string.stats_page_heatmap_less),
                            tier = StatsTextTier.Legend,
                            maxLines = 1,
                        )
                        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                            HeatmapCell(
                                alpha = alpha,
                                shape = cellShapes[heatShapeIndex(alpha)],
                                sizeDp = 10,
                            )
                        }
                        StatsLabel(
                            text = stringResource(R.string.stats_page_heatmap_more),
                            tier = StatsTextTier.Legend,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 概览页统计分组：每项一行（前导图标 + 名称 + 数值），替代原来的双列卡片。 */
@Composable
private fun OverviewStatsGroup(stats: UsageStatsSnapshot) {
    EtaSegmentedColumn(modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing)) {
        item(key = "conversations") {
            StatValueRow(
                icon = Icons.Rounded.Forum,
                label = stringResource(R.string.stats_page_total_conversations),
                value = formatCurrentLifetime(
                    current = formatStatCount(stats.currentConversations.toLong()),
                    lifetime = formatStatCount(stats.lifetimeConversations.toLong()),
                ),
            )
        }
        item(key = "messages") {
            StatValueRow(
                icon = Icons.Rounded.ChatBubbleOutline,
                label = stringResource(R.string.stats_page_total_messages),
                value = formatCurrentLifetime(
                    current = formatStatCount(stats.currentMessages.toLong()),
                    lifetime = formatStatCount(stats.lifetimeMessages.toLong()),
                ),
            )
        }
        item(key = "fresh-input") {
            StatValueRow(
                icon = Icons.Rounded.Keyboard,
                label = stringResource(R.string.stats_page_fresh_input_tokens),
                value = formatCurrentLifetime(
                    current = formatTokenCount(stats.currentFreshInputTokens),
                    lifetime = formatTokenCount(stats.lifetimeFreshInputTokens),
                ),
            )
        }
        item(key = "output") {
            StatValueRow(
                icon = Icons.AutoMirrored.Rounded.Notes,
                label = stringResource(R.string.stats_page_output_tokens),
                value = formatCurrentLifetime(
                    current = formatTokenCount(stats.currentOutputTokens),
                    lifetime = formatTokenCount(stats.lifetimeOutputTokens),
                ),
            )
        }
        item(key = "cached", visible = stats.lifetimeCachedTokens > 0) {
            StatValueRow(
                icon = Icons.Rounded.Bolt,
                label = stringResource(R.string.stats_page_cached_tokens),
                value = formatCurrentLifetime(
                    current = formatTokenCount(stats.currentCachedTokens),
                    lifetime = formatTokenCount(stats.lifetimeCachedTokens),
                ),
            )
        }
        item(key = "cache-writes") {
            StatValueRow(
                icon = Icons.Rounded.Add,
                label = stringResource(R.string.stats_page_cache_creation_tokens),
                value = formatCurrentLifetime(
                    current = formatTokenCount(stats.currentCacheCreationTokens),
                    lifetime = formatTokenCount(stats.lifetimeCacheCreationTokens),
                ),
            )
        }
        item(key = "launches") {
            StatValueRow(
                icon = Icons.Rounded.RocketLaunch,
                label = stringResource(R.string.stats_page_launch_count),
                value = formatStatCount(stats.launchCount.toLong()),
            )
        }
    }
}

/** 概览统计行：前导图标 + 名称 + 尾部数值。 */
@Composable
private fun StatValueRow(icon: ImageVector, label: String, value: String) {
    EtaSegmentedRow(
        leadingContent = { PreferenceIcon(icon = icon) },
        content = { M3Text(text = label) },
        trailingContent = {
            M3Text(
                text = value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun ModelUsagePane(
    usage: ModelUsageSnapshot,
    modifier: Modifier = Modifier,
) {
    var expandedModels by remember { mutableStateOf(emptySet<String>()) }
    var startBound by remember { mutableStateOf(UsageTimeBound()) }
    var endBound by remember { mutableStateOf(UsageTimeBound()) }
    val filtered = remember(usage, startBound, endBound) {
        usage.filtered(startBound.toMillis(endOfBound = false), endBound.toMillis(endOfBound = true))
    }
    Column(modifier = modifier) {
        UsageFilterGroup(
            start = startBound,
            end = endBound,
            onStartChange = { startBound = it },
            onEndChange = { endBound = it },
            onClear = {
                startBound = UsageTimeBound()
                endBound = UsageTimeBound()
            },
        )
        if (filtered.providers.isEmpty()) {
            ListEmptyState(
                title = stringResource(
                    if (startBound.isSet || endBound.isSet) {
                        R.string.stats_model_filter_empty
                    } else {
                        R.string.stats_model_empty
                    },
                ),
                modifier = Modifier.padding(bottom = EtaSegmentedGroupSpacing),
            )
        } else {
            EtaSegmentedColumn(
                modifier = Modifier.padding(bottom = EtaSegmentedGroupSpacing),
                title = stringResource(R.string.stats_model_total_title),
            ) {
                snapshotMetrics(filtered).forEach { (labelRes, value) ->
                    item(key = labelRes) {
                        MetricRow(label = stringResource(labelRes), value = value)
                    }
                }
            }
            filtered.providers.forEach { provider ->
                ProviderUsageGroup(
                    provider = provider,
                    expandedModels = expandedModels,
                    onToggle = { key ->
                        expandedModels = if (key in expandedModels) {
                            expandedModels - key
                        } else {
                            expandedModels + key
                        }
                    },
                )
            }
        }
    }
}

private enum class UsageBoundSide { Start, End }

/** 时间筛选分组：头部展开行 + 条件可见的起止行 / 预设 / 清除行。 */
@Composable
private fun UsageFilterGroup(
    start: UsageTimeBound,
    end: UsageTimeBound,
    onStartChange: (UsageTimeBound) -> Unit,
    onEndChange: (UsageTimeBound) -> Unit,
    onClear: () -> Unit,
) {
    val view = LocalView.current
    var expanded by remember { mutableStateOf(false) }
    var pickerSide by remember { mutableStateOf<UsageBoundSide?>(null) }
    var selectedPreset by remember { mutableStateOf<UsageFilterPreset?>(null) }
    val matchedPreset = remember(start, end, selectedPreset) {
        matchingUsageFilterPreset(start, end, LocalDate.now(), preferred = selectedPreset)
    }
    val summary = filterSummaryText(start, end, matchedPreset)
        ?: stringResource(R.string.stats_model_filter_collapsed)
    val titleText = stringResource(R.string.stats_model_filter_title)

    EtaSegmentedColumn(modifier = Modifier.padding(bottom = EtaSegmentedGroupSpacing)) {
        item(key = "header") {
            // M3：官方可点击分段行承担展开/收起，替掉「Row + clickable(无涟漪)」。
            EtaSegmentedRow(
                onClick = {
                    expanded = !expanded
                },
                content = { M3Text(text = titleText) },
                supportingContent = {
                    M3Text(
                        text = summary,
                        maxLines = 1,
                    )
                },
                trailingContent = {
                    EtaExpandChevron(expanded = expanded)
                },
            )
        }
        item(key = "start", visible = expanded) {
            UsageBoundRow(
                label = stringResource(R.string.stats_model_filter_start),
                value = formatUsageDate(start)
                    ?: stringResource(R.string.stats_model_filter_datetime_placeholder),
                onClick = {
                    TouchHaptics.click(view)
                    pickerSide = UsageBoundSide.Start
                },
            )
        }
        item(key = "end", visible = expanded) {
            UsageBoundRow(
                label = stringResource(R.string.stats_model_filter_end),
                value = formatUsageDate(end)
                    ?: stringResource(R.string.stats_model_filter_datetime_placeholder),
                onClick = {
                    TouchHaptics.click(view)
                    pickerSide = UsageBoundSide.End
                },
            )
        }
        item(key = "presets", visible = expanded) {
            EtaSegmentedItemContainer {
                UsageFilterPresetChips(
                    selected = matchedPreset,
                    onSelect = { preset ->
                        selectedPreset = preset
                        val (from, to) = usageFilterPresetRange(preset, LocalDate.now())
                        onStartChange(UsageTimeBound.from(from))
                        onEndChange(UsageTimeBound.from(to))
                    },
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        item(key = "clear", visible = expanded && (start.isSet || end.isSet)) {
            // 破坏性操作用 error 色强调，与其他界面的删除行一致。
            EtaSegmentedRow(
                onClick = {
                    selectedPreset = null
                    onClear()
                },
                content = {
                    M3Text(
                        text = stringResource(R.string.stats_model_filter_clear),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
    UsageDateTimePickerDialog(
        show = pickerSide != null,
        title = stringResource(R.string.stats_model_filter_datetime_title),
        current = if (pickerSide == UsageBoundSide.End) end else start,
        endOfBound = pickerSide == UsageBoundSide.End,
        onDismiss = { pickerSide = null },
        onConfirm = { picked ->
            selectedPreset = null
            if (pickerSide == UsageBoundSide.End) onEndChange(picked) else onStartChange(picked)
            pickerSide = null
        },
        onClear = {
            selectedPreset = null
            if (pickerSide == UsageBoundSide.End) onEndChange(UsageTimeBound()) else onStartChange(UsageTimeBound())
            pickerSide = null
        },
    )
}

/** 起止日期行：点击打开日期选择器，当前值显示在 supporting。 */
@Composable
private fun UsageBoundRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    EtaSegmentedRow(
        onClick = { onClick() },
        content = { M3Text(text = label) },
        supportingContent = {
            M3Text(
                text = value,
            )
        },
        trailingContent = {
            EtaTrailingChevron()
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsageFilterPresetChips(
    selected: UsageFilterPreset?,
    onSelect: (UsageFilterPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = listOf(
        UsageFilterPreset.Today to R.string.stats_model_filter_preset_today,
        UsageFilterPreset.Last7Days to R.string.stats_model_filter_preset_7d,
        UsageFilterPreset.ThisWeek to R.string.stats_model_filter_preset_week,
        UsageFilterPreset.Last30Days to R.string.stats_model_filter_preset_30d,
        UsageFilterPreset.ThisMonth to R.string.stats_model_filter_preset_month,
    )
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (preset, labelRes) ->
            UsageBoundChip(
                text = stringResource(labelRes),
                selected = selected == preset,
                onClick = { onSelect(preset) },
            )
        }
    }
}

/**
 * 时间筛选胶囊。
 *
 * 走官方 [FilterChip]（自带选中态、涟漪与语义，形状/配色/字阶均为官方默认值）。
 */
@Composable
private fun UsageBoundChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { M3Text(text = text) },
        modifier = modifier,
    )
}

// localized formatter 提为惰性单例：DateTimeFormatter 不可变且线程安全，
// filterSummaryText / 日期 chip 每次重组都调 formatUsageDate，现场创建纯浪费。
private val UsageDateFormatter: DateTimeFormatter by lazy {
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
}

private fun formatUsageDate(bound: UsageTimeBound): String? {
    val date = bound.date ?: return null
    return date.format(UsageDateFormatter)
}

@Composable
private fun filterSummaryText(
    start: UsageTimeBound,
    end: UsageTimeBound,
    preset: UsageFilterPreset?,
): String? {
    val presetRes = when (preset) {
        UsageFilterPreset.Today -> R.string.stats_model_filter_preset_today
        UsageFilterPreset.Last7Days -> R.string.stats_model_filter_preset_7d
        UsageFilterPreset.ThisWeek -> R.string.stats_model_filter_preset_week
        UsageFilterPreset.Last30Days -> R.string.stats_model_filter_preset_30d
        UsageFilterPreset.ThisMonth -> R.string.stats_model_filter_preset_month
        null -> null
    }
    if (presetRes != null) return stringResource(presetRes)
    val startText = formatUsageDate(start)
    val endText = formatUsageDate(end)
    return when {
        startText != null && endText != null -> "$startText – $endText"
        startText != null -> startText
        endText != null -> endText
        else -> null
    }
}

/**
 * 汇总指标（标签 → 已格式化数值）。
 *
 * The value excludes cache reads and writes. The hit rate divides by the
 * full prompt, so it is not labelled "input".
 */
private fun snapshotMetrics(snapshot: ModelUsageSnapshot): List<Pair<Int, String>> = listOf(
    R.string.stats_page_token_total to formatTokenCount(snapshot.totalTokens),
    R.string.stats_page_fresh_input_tokens to formatTokenCount(snapshot.totalFreshInputTokens),
    R.string.stats_page_output_tokens to formatTokenCount(snapshot.totalOutputTokens),
    R.string.stats_page_cached_tokens to formatTokenCount(snapshot.totalCachedTokens),
    R.string.stats_page_cache_creation_tokens to formatTokenCount(snapshot.totalCacheCreationTokens),
    R.string.stats_model_cache_hit_rate to formatCacheHitRate(snapshot.totalCachedTokens, snapshot.totalInputTokens),
)

/** 单个模型的展开指标（标签 → 已格式化数值）。 */
private fun modelMetricDetails(model: ModelUsageModelUi): List<Pair<Int, String>> = listOf(
    R.string.stats_page_token_total to formatTokenCount(model.totalTokens),
    R.string.stats_page_fresh_input_tokens to formatTokenCount(model.freshInputTokens),
    R.string.stats_page_output_tokens to formatTokenCount(model.outputTokens),
    R.string.stats_page_cached_tokens to formatTokenCount(model.cachedTokens),
    R.string.stats_page_cache_creation_tokens to formatTokenCount(model.cacheCreationTokens),
    R.string.stats_model_cache_hit_rate to formatCacheHitRate(model.cachedTokens, model.inputTokens),
    R.string.stats_model_daily_avg to formatTokenCount(model.dailyAverageTokens),
    R.string.stats_model_conversation_avg to formatTokenCount(model.conversationAverageTokens),
    R.string.stats_model_conversations to formatStatCount(model.conversationCount.toLong()),
    R.string.stats_model_active_days to formatStatCount(model.activeDays.toLong()),
)

/** 标签 / 数值指标行：非交互，用分段行呈现。 */
@Composable
private fun MetricRow(label: String, value: String) {
    EtaSegmentedRow(
        content = { M3Text(text = label) },
        trailingContent = {
            M3Text(
                text = value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/** 单个提供商分组：组标题为提供商名，模型行可展开，展开指标行用 visible 参与 spring 动画。 */
@Composable
private fun ProviderUsageGroup(
    provider: ModelUsageProviderUi,
    expandedModels: Set<String>,
    onToggle: (String) -> Unit,
) {
    EtaSegmentedColumn(
        modifier = Modifier.padding(bottom = EtaSegmentedGroupSpacing),
        title = provider.name,
    ) {
        provider.models.forEach { model ->
            val key = "${provider.id}/${model.id}"
            val expanded = key in expandedModels
            item(key = key) {
                EtaSegmentedRow(
                    onClick = {
                        onToggle(key)
                    },
                    content = {
                        M3Text(text = model.displayName)
                    },
                    trailingContent = {
                        EtaExpandChevron(expanded = expanded)
                    },
                )
            }
            modelMetricDetails(model).forEach { (labelRes, value) ->
                item(key = "$key-$labelRes", visible = expanded) {
                    MetricRow(label = stringResource(labelRes), value = value)
                }
            }
        }
    }
}

@Composable
private fun ChatHeatmap(conversationsPerDay: Map<LocalDate, Int>, cellShapes: List<Shape>) {
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)
    val numWeeks = 53
    val (q1, q2, q3) = heatmapQuartiles(conversationsPerDay.values)
    val cellSize = 11.dp
    val cellSpacing = 2.dp
    val monthLabelHeight = 14.dp
    val dowLabels = listOf(
        "",
        stringResource(R.string.stats_page_dow_mon),
        "",
        stringResource(R.string.stats_page_dow_wed),
        "",
        stringResource(R.string.stats_page_dow_fri),
        "",
    )
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            modifier = Modifier.width(12.dp),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
        ) {
            Spacer(Modifier.height(monthLabelHeight + 2.dp))
            dowLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label.isNotEmpty()) {
                        HeatmapCaption(
                            text = label,
                            fontSize = 8.sp,
                            color = onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                // 两端淡出带：横向滚到中间时，被视口切一半的格子与月份标签会留一条
                // 生硬的竖直截断边，用 DstIn 蒙版把切开的部分擦掉。
                .horizontalScrollEdgeFade(scrollState, HeatmapEdgeFadeWidth)
                .horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    val weekStart = startSunday.plusDays((weekIdx * 7).toLong())
                    val labelDate = (0..6)
                        .map { weekStart.plusDays(it.toLong()) }
                        .firstOrNull { it.dayOfMonth == 1 }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(monthLabelHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (labelDate != null) {
                            HeatmapCaption(
                                text = if (labelDate.monthValue == 1) {
                                    labelDate.year.toString()
                                } else {
                                    labelDate.month.getDisplayName(TextStyle.SHORT, LocalConfiguration.current.locales[0])
                                },
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                fontSize = 9.sp,
                                color = onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                // 末尾留白：最右侧月份标签（如“10月”）会溢出自身网格列宽度，
                // 留出空间让它完整落在水平滚动视口内，不被右边缘裁切。
                Spacer(Modifier.width(16.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    Column(verticalArrangement = Arrangement.spacedBy(cellSpacing)) {
                        for (dow in 0..6) {
                            val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                            val isFuture = date.isAfter(today)
                            val count = if (isFuture) 0 else (conversationsPerDay[date] ?: 0)
                            val alpha = heatmapAlpha(count, q1, q2, q3, isFuture)
                            HeatmapCell(
                                alpha = alpha,
                                shape = cellShapes[heatShapeIndex(alpha)],
                                sizeDp = cellSize.value.toInt(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 热力图滚动区两端的淡出带宽：约一格半（格子 11dp + 间距 2dp）。 */
private val HeatmapEdgeFadeWidth = 16.dp

/** 热力图色块：非交互数据可视化，颜色只取主题角色，形状随用量档位热闹递增。 */
@Composable
private fun HeatmapCell(alpha: Float, shape: Shape, sizeDp: Int) {
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val color = when {
        alpha < 0f -> surfaceVariant.copy(alpha = 0.3f)
        alpha == 0f -> surfaceVariant
        else -> primary.copy(alpha = alpha)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(shape)
            .background(color),
    )
}

/**
 * 热力图五档形状：用量少 → 多，取 M3 官方表达式形状库热闹度递增的五枚：
 * 方块（安静底色）→ 四叶饼干 → 太阳（八段浅波）→ 花朵（八瓣）→ 爆开（十二尖刺）。
 * 格子只有 11dp，优先挑剪影在小尺寸仍可辨的形状；非对称与朝向形（箭头、心形等）不参与。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun heatmapCellShapes(): List<Shape> = listOf(
    MaterialShapes.Square.toShape(),
    MaterialShapes.Cookie4Sided.toShape(),
    MaterialShapes.Sunny.toShape(),
    MaterialShapes.Flower.toShape(),
    MaterialShapes.Burst.toShape(),
)

/** 用量档位 → 形状阶梯下标：空档/未来日与「少」同用第 0 档的安静方块。 */
private fun heatShapeIndex(alpha: Float): Int = when {
    alpha <= 0f -> 0
    alpha <= 0.25f -> 1
    alpha <= 0.5f -> 2
    alpha <= 0.75f -> 3
    else -> 4
}

private fun formatCurrentLifetime(current: String, lifetime: String): String =
    if (current == lifetime) current else "$current / $lifetime"
