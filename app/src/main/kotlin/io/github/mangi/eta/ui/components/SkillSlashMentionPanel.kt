package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.model.SkillSlashMention
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as M3Text

@Composable
internal fun SkillSlashMentionPanel(
    skills: List<SkillIndexEntry>,
    query: String?,
    selectedIds: Set<String>,
    onSelect: (SkillIndexEntry) -> Unit,
) {
    // 候选面板随 / 查询出现/收起；退场期间沿用上一次查询的候选，避免列表先消失再淡出。
    var lastQuery by remember { mutableStateOf<String?>(null) }
    if (query != null) lastQuery = query
    val shownQuery = query ?: lastQuery
    AnimatedVisibility(
        visible = query != null,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
            expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
            shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()),
    ) {
    if (shownQuery == null) return@AnimatedVisibility
    val candidates = SkillSlashMention.candidates(skills, shownQuery, selectedIds)
    // M3 Expressive：官方 Surface 承担面板底板（官方 shapes.medium + surfaceContainerHigh），
    // 候选行改用官方可交互 ListItem——容器形状、内容间距、涟漪、Role 与禁用处理全部由官方组件提供，
    // 排版只用 MaterialTheme.typography，文字色只用官方 onSurface / onSurfaceVariant 角色。
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        val itemColors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
        if (candidates.isEmpty()) {
            // 空态同样用官方 ListItem 承载，内边距取官方 ListItemDefaults.ContentPadding。
            ListItem(
                colors = itemColors,
                content = {
                    M3Text(
                        text = stringResource(R.string.ssm_no_match),
                        style = etaTextStyle(EtaType.Secondary),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                items(candidates, key = { it.id }) { skill ->
                    ListItem(
                        onClick = { onSelect(skill) },
                        colors = itemColors,
                        content = {
                            M3Text(
                                text = skill.name.ifBlank { skill.id },
                                style = etaTextStyle(EtaType.Body),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            M3Text(
                                text = "/skill:${skill.id} · ${skill.description.ifBlank { stringResource(R.string.ssm_no_description) }}",
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
}
