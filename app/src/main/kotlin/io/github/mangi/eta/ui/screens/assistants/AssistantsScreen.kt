package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text

@Composable
internal fun AssistantsScreen(
    picker: Boolean,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    selectedAssistantId: String? = null,
    onSelectAssistant: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = selectedAssistantId ?: repositoryActiveId
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpandedState(listState)

    // 点行即选中该助手（浏览态与选择态一致）；编辑只收在行尾三点菜单里，不再由行点击进入编辑页。
    // 选择态（picker）选中后返回；浏览态停留在本页，以便直接看到对勾位置更新。
    val openProfile: (AssistantProfile) -> Unit = { profile ->
        if (onSelectAssistant != null) {
            onSelectAssistant(profile.id)
        } else {
            scope.launch {
                withContext(Dispatchers.IO) {
                    AssistantRepository.select(profile.id)
                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                }
            }
        }
        if (picker) onBack()
    }

    EtaScaffoldPage(
        title = stringResource(R.string.assistant_list_title),
        onBack = onBack,
        listState = listState,
        // 新建入口从顶栏图标钮换成 MCP 服务器页同款 FAB：滚动收起、回顶展开。
        floatingActionButton = {
            EtaExtendedFab(
                text = stringResource(R.string.assistant_add),
                icon = Icons.Rounded.Add,
                expanded = fabExpanded,
                onClick = {
                    TouchHaptics.click(LocalView.current)
                    scope.launch {
                        val created = withContext(Dispatchers.IO) {
                            AssistantRepository.create()
                        }
                        onNavigate(AppRoute.AssistantEdit(created.id))
                    }
                },
            )
        },
        actions = {
            // 搜索入口：大标题栏只保留这一个单搜索图标，点击进独立搜索页。
            M3IconButton(onClick = { onNavigate(AppRoute.AssistantSearch(picker = picker)) }) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.assistant_search),
                )
            }
        },
    ) {
        // 主页不过滤（query 恒空即全量），搜索交给独立搜索页。
        assistantItems(
            profiles = profiles,
            activeId = activeId,
            query = "",
            onOpen = openProfile,
            onCopy = { profile ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AssistantRepository.duplicate(profile.id)
                    }
                }
            },
            onEdit = { profile -> onNavigate(AppRoute.AssistantEdit(profile.id)) },
            onDelete = { profile -> deleteProfile = profile },
        )
    }

    deleteProfile?.let { profile ->
        AssistantDeleteDialog(
            profile = profile,
            canDelete = profiles.size > 1,
            onDismiss = { deleteProfile = null },
            onConfirm = {
                val target = profile
                deleteProfile = null
                scope.launch {
                    withContext(Dispatchers.IO) {
                        AssistantRepository.delete(target.id)
                        RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                    }
                }
            },
        )
    }
}

/**
 * 助手列表项：按搜索词过滤后的 KernelSU 式分段行 + 空态文案。
 * 助手主页（query 恒空即全量）与独立搜索页共用；行尾三点菜单提供「复制/编辑/删除」。
 */
internal fun LazyListScope.assistantItems(
    profiles: List<AssistantProfile>,
    activeId: String,
    query: String,
    onOpen: (AssistantProfile) -> Unit,
    onCopy: (AssistantProfile) -> Unit,
    onEdit: (AssistantProfile) -> Unit,
    onDelete: (AssistantProfile) -> Unit,
) {
    val needle = query.trim()
    val filtered = profiles.filter { profile ->
        needle.isBlank() || profile.name.contains(needle, ignoreCase = true)
    }
    filtered.forEachIndexed { index, profile ->
        item(key = profile.id) {
            EtaSegmentedItem(index = index, count = filtered.size) {
                AssistantRow(
                    profile = profile,
                    selected = profile.id == activeId,
                    onOpen = { onOpen(profile) },
                    onCopy = { onCopy(profile) },
                    onEdit = { onEdit(profile) },
                    onDelete = { onDelete(profile) },
                    modifier = Modifier.padding(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = if (index == filtered.lastIndex) 13.dp else 2.dp,
                    ),
                )
            }
        }
    }
    if (filtered.isEmpty()) {
        item(key = "empty") {
            M3Text(
                text = stringResource(R.string.assistant_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * 助手搜索框：官方 [OutlinedTextField]（只加搜索图标，形状/配色走官方默认）。
 * 供「助手与模型」弹窗等行内联搜索使用；独立搜索页走 [io.github.mangi.eta.ui.adaptive.EtaSearchPage]。
 */
@Composable
internal fun AssistantSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { M3Text(text = placeholder) },
        leadingIcon = { Icon(imageVector = Icons.Rounded.Search, contentDescription = null) },
    )
}
