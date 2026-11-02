package io.github.mangi.eta.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.model.ConversationMention
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as M3Text

@Composable
internal fun ConversationMentionPanel(
    state: ConversationMentionInputUi,
    query: String?,
    onSelect: (String) -> Unit,
) {
    if (state.pending.isNotEmpty()) {
        // M3 Expressive：已引用会话是只读容器，官方 Surface 承担形状（shapes.medium）与
        // surfaceContainerHigh 底色；移除动作走官方 IconButton（容器尺寸取官方
        // IconButtonDefaults.smallContainerSize()，图标取官方 smallIconSize，涟漪与
        // disabled 状态全部由官方组件提供）。文字色只用官方 primary / onSurfaceVariant 角色。
        val mentionScrollState = rememberScrollState()
        Row(
            modifier = Modifier.fillMaxWidth()
                // 两端截断淡出带：引用行横向滚动时，被视口切一半的胶囊用 DstIn 蒙版擦掉。
                .horizontalScrollEdgeFade(mentionScrollState)
                .horizontalScroll(mentionScrollState)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.pending.forEach { mention ->
                Surface(
                    modifier = Modifier.widthIn(max = 280.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        M3Text(
                            text = "@${mention.title}",
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(ListItemDefaults.ContentPadding),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.primary,
                            style = etaTextStyle(EtaType.Secondary),
                        )
                        val removeDescription =
                            stringResource(R.string.cmp_remove_mention, mention.title)
                        EtaTooltip(text = removeDescription) {
                            IconButton(
                                onClick = { state.onRemove(mention.id) },
                                modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = removeDescription,
                                    modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    if (query == null) return
    val candidates = ConversationMention.candidates(state.conversations, query, state.currentConversationId,
        state.pending.map { it.conversationId }.toSet())
    // M3 Expressive：官方 Surface 承担面板底板，候选行改用官方可交互 ListItem——
    // 形状、内容间距、涟漪、Role 与排版档位全部由官方组件提供。
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        val itemColors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        val limitReached = state.pending.size >= ConversationMention.MAX_ATTACHED
        if (limitReached || candidates.isEmpty()) {
            ListItem(
                colors = itemColors,
                content = {
                    M3Text(
                        text = if (limitReached) {
                            stringResource(R.string.cmp_max_mentions)
                        } else {
                            stringResource(R.string.cmp_no_match)
                        },
                        style = etaTextStyle(EtaType.Secondary),
                    )
                },
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                items(candidates, key = { it.id }) { candidate ->
                    ListItem(
                        onClick = { onSelect(candidate.id) },
                        colors = itemColors,
                        content = {
                            M3Text(
                                text = "@${candidate.title}",
                                style = etaTextStyle(EtaType.Body),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            M3Text(
                                text = listOf(candidate.preview, candidate.timeLabel, if (candidate.isActiveRun) stringResource(R.string.cmp_running) else "")
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                style = etaTextStyle(EtaType.Secondary),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}
