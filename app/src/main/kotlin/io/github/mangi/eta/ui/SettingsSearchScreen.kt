package io.github.mangi.eta.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaSearchHistory
import io.github.mangi.eta.ui.adaptive.EtaSearchPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.navigation.AppRoute

/** 查询历史分域键：设置搜索历史独立存储。 */
private const val HISTORY_KEY = "settings"

/** 查询串的分词分隔符：连续空白都算一个分隔。 */
private val SEARCH_WHITESPACE = Regex("\\s+")

/** 一条命中结果：[order] 是原目录顺序（同分时的稳定次序），[score] 越小越靠前。 */
private data class SettingsHit(
    val order: Int,
    val score: Int,
    val entry: SettingsEntry,
)

/**
 * 多词 AND 打分：查询按空白拆成若干词，每个词都要命中才算匹配。
 *
 * 命中位置按权重累加——标题 0 < 同义词 1 < 摘要 2 < 分组 3——所以「震动」既能命中标题
 * 「震动反馈」，也能命中同义词表里的「触感」，只是排在后面。返回 null 表示该条目不匹配。
 */
private fun settingsSearchScore(entry: SettingsEntry, tokens: List<String>): Int? {
    var total = 0
    for (token in tokens) {
        val weight = when {
            entry.title.contains(token, ignoreCase = true) -> 0
            entry.keywords.contains(token, ignoreCase = true) -> 1
            entry.summary?.contains(token, ignoreCase = true) == true -> 2
            entry.group.contains(token, ignoreCase = true) -> 3
            else -> return null
        }
        total += weight
    }
    return total
}

/**
 * 独立搜索页：从设置主页右上角的单搜索图标跳入，整页即搜索。
 *
 * 顶栏是「返回钮 + 透明底搜索框 + 清除按钮」（官方 [EtaSearchPage]），进入即自动聚焦弹键盘；
 * 未输入时有搜索记录列表、无记录才显示官方 Empty state，不复制主页内容。
 *
 * 索引不再单独硬编码，直接用主页同一份 [settingsCatalog]：主页加一行，这里就能搜到，
 * 不会出现「主页有、搜索搜不到」。行内控件（开关 / 选择器 / 一次性动作）没有自己的子页，
 * 命中后交给 [onFocusInSettings] 回到主页滚动聚焦到那一行。
 * 点按结果会把查询词记入搜索历史。
 */
@Composable
internal fun SettingsSearchScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    onFocusInSettings: (String) -> Unit,
) {
    // 与主页同一判定：任务后端未安装时不收录「任务界面」入口。
    val taskBackendInstalled = rememberTaskBackendInstalled()
    val entries = settingsCatalog(taskBackendInstalled = taskBackendInstalled)

    EtaSearchPage(
        searchPlaceholder = stringResource(R.string.settings_search_hint),
        onBack = onBack,
        historyKey = HISTORY_KEY,
    ) { query ->
        // LazyListScope 不是 Composable 作用域，过滤与排序直接内联计算。
        val tokens = query.trim().split(SEARCH_WHITESPACE).filter { it.isNotBlank() }
        val matched = if (tokens.isEmpty()) {
            emptyList()
        } else {
            entries.mapIndexedNotNull { index, entry ->
                settingsSearchScore(entry, tokens)?.let { SettingsHit(index, it, entry) }
            }.sortedWith(compareBy<SettingsHit> { it.score }.thenBy { it.order })
        }
        // 高亮取最长的那个词：多词查询时它是最有信息量的那一段。
        val highlightTerm = tokens.maxByOrNull { it.length }.orEmpty()
        if (matched.isEmpty()) {
            item(key = "empty") {
                ListEmptyState(title = stringResource(R.string.settings_search_no_matches))
            }
        } else {
            matched.forEachIndexed { index, hit ->
                item(key = hit.entry.key) {
                    EtaSegmentedItem(index = index, count = matched.size) {
                        EtaClickableRow(
                            title = hit.entry.title,
                            // 摘要与所属分组一起给，结果里始终看得出「这行在哪个分区」。
                            summary = listOfNotNull(hit.entry.summary, hit.entry.group)
                                .joinToString(" · "),
                            leading = { PreferenceIcon(icon = hit.entry.icon) },
                            highlight = highlightTerm,
                            onClick = {
                                EtaSearchHistory.add(context, HISTORY_KEY, query)
                                when (val destination = hit.entry.destination) {
                                    is SettingsDestination.Route -> onNavigate(destination.route)
                                    SettingsDestination.OverlayPermission ->
                                        openOverlayPermissionSettings(context)
                                    SettingsDestination.AccessibilitySettings ->
                                        openAccessibilitySettings(context)
                                    SettingsDestination.Inline ->
                                        onFocusInSettings(hit.entry.key)
                                }
                            },
                            modifier = Modifier.padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                bottom = if (index == matched.lastIndex) {
                                    EtaSegmentedGroupSpacing
                                } else {
                                    EtaSegmentedGap
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

// ── 系统设置意图：与主页对应行的行为一致 ─────────────────────────────────────────

private fun openOverlayPermissionSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
