package io.github.mangi.eta.ui.screens.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import io.github.mangi.eta.ui.model.MessageSearchHit

/** 查询历史分域键：消息搜索历史独立存储。 */
private const val HISTORY_KEY = "messages"

/**
 * 列表键：标题命中与正文命中不能撞键（标题命中没有消息 id）。
 */
private fun MessageSearchHit.listKey(): String =
    if (isTitleMatch) "title:$conversationId" else "$conversationId:$messageId"

/**
 * 独立搜索页：从顶栏溢出菜单「搜索记录」跳入，整页即搜索。
 *
 * 顶栏是「返回钮 + 透明底搜索框 + 清除按钮」（官方 [EtaSearchPage]），进入即自动聚焦弹键盘；
 * 未输入时有查询记录、无记录才显示官方 Empty state，不复制宿主页内容。检索是挂起调用
 * （会话可能尚未装载，需回落到存档扫描），所以结果由本页自己持有：零尺寸的副作用条目
 * 按搜索词重启检索，结果回来后触发列表重建；点按命中行即跳到对应消息并退出本页。
 *
 * 命中分两类：会话标题命中（展示标题本身，点开只切会话）与消息正文命中（展示命中片段，
 * 点开跳到那条消息）。命中词在结果行里高亮标出，标题、片段与摘要里的出现都算。
 */
@Composable
internal fun MessageSearchScreen(
    onSearch: suspend (String) -> List<MessageSearchHit>,
    onOpenHit: (MessageSearchHit) -> Unit,
    onBack: () -> Unit,
    showConversationTitle: Boolean = false,
) {
    var searchedQuery by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MessageSearchHit>>(emptyList()) }
    val context = LocalContext.current

    EtaSearchPage(
        searchPlaceholder = stringResource(R.string.search_history_hint),
        onBack = onBack,
        historyKey = HISTORY_KEY,
    ) { query ->
        // LazyListScope 不是 Composable 作用域：异步检索挂在零尺寸条目上，随搜索词重启。
        item(key = "message_search_effect") {
            LaunchedEffect(query) {
                searchedQuery = query
                results = onSearch(query)
            }
        }
        // 结果尚未对齐当前搜索词时不画列表，避免上一轮的命中短暂残留。
        if (searchedQuery == query) {
            if (results.isEmpty()) {
                item(key = "message_search_empty") {
                    ListEmptyState(title = stringResource(R.string.search_history_no_results))
                }
            } else {
                results.forEachIndexed { index, hit ->
                    item(key = hit.listKey()) {
                        EtaSegmentedItem(index = index, count = results.size) {
                            EtaClickableRow(
                                // 标题命中直接展示会话标题，正文命中展示命中片段。
                                title = if (hit.isTitleMatch) hit.conversationTitle else hit.snippet,
                                summary = if (showConversationTitle && !hit.isTitleMatch) {
                                    "${hit.conversationTitle} · ${hit.roleLabel}"
                                } else {
                                    hit.roleLabel
                                },
                                // 命中词在标题、片段与摘要里都标出来：一眼能看出为什么命中。
                                highlight = query,
                                // 点开命中就是「这个词有用」：与其它搜索页一致，落一次搜索记录。
                                onClick = {
                                    EtaSearchHistory.add(context, HISTORY_KEY, query)
                                    onOpenHit(hit)
                                },
                                modifier = Modifier.padding(
                                    start = EtaGroupHorizontalPadding,
                                    end = EtaGroupHorizontalPadding,
                                    bottom = if (index == results.lastIndex) {
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
}
