package io.github.mangi.eta.ui.screens.tools

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.ToggleOff
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolRecipeStore
import io.github.mangi.eta.agent.tool.ToolRecipe
import io.github.mangi.eta.agent.tool.ToolRecipeShareException
import io.github.mangi.eta.agent.tool.ToolRecipeShareFormat
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaFloatingToolbarConcentricPadding
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.TopBarIconButton
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.navigation.AppRoute
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text as M3Text
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配方运行时——设置页与管理页共用的唯一数据源。
 *
 * 管理页与 agent 运行期必须看到同一份配方，因此这里持有唯一的 [io.github.mangi.eta.agent.tool.AgentToolRecipeStore]；
 * 用 StateFlow 暴露，管理页的启停/删除即时反映到模型工具列表。
 */
internal class ToolRecipeRuntime private constructor(context: Context) {
    private val store = AgentToolRecipeStore.get(context.applicationContext)

    /** 当前配方快照；每次写入后重新发射。 */
    val recipes = kotlinx.coroutines.flow.MutableStateFlow<List<ToolRecipe>>(emptyList())

    /** 每次落盘/删除后调用，刷新快照。 */
    fun refresh() {
        recipes.value = store.all()
    }

    fun all(): List<ToolRecipe> = store.all()

    fun find(name: String): ToolRecipe? = store.find(name)

    /** 组合链上需要 Root 的配方名集合——推导需要完整配方图，所以按快照整体算。 */
    fun rootDependentNames(): Set<String> = store.rootDependentRecipes(all())

    fun setEnabled(name: String, enabled: Boolean): Boolean =
        store.setEnabled(name, enabled)?.also { refresh() } != null

    fun delete(name: String): Boolean = store.delete(name).also { refresh() }

    /** 覆盖保存（导入 / 编辑共用）：不做存在性检查，由调用方决定。 */
    fun save(recipe: ToolRecipe): AgentToolRecipeStore.SaveResult =
        store.save(recipe).also { refresh() }

    /**
     * 保存编辑结果。
     *
     * @param original 打开编辑页时的快照——名字变了就走 [AgentToolRecipeStore.rename]，
     *                 顺带把其它配方里的 compose 引用改掉；否则组合链会在改名后指向不存在的配方。
     */
    fun save(original: ToolRecipe, updated: ToolRecipe): AgentToolRecipeStore.SaveResult =
        (if (updated.name == original.name) {
            store.save(updated)
        } else {
            store.rename(original.name, updated)
        }).also { refresh() }

    companion object {
        @Volatile
        private var instance: ToolRecipeRuntime? = null

        fun get(context: Context): ToolRecipeRuntime =
            instance ?: synchronized(this) {
                instance ?: ToolRecipeRuntime(context).also { it.refresh() }
            }
    }
}

/**
 * 自建工具管理页。
 *
 * 这个页面存在的理由：配方是模型自己写、自己调用的能力，用户必须能
 * - **审计**：每条配方到底做了什么（步骤一览）
 * - **接管**：随时停用 / 启用，不让模型继续调用
 * - **编辑**：改描述、改步骤（改坏了的配方由模型自己修，用户也能直接修）
 * - **分享**：导出/导入，让自进化成果离开这台设备
 */
@Composable
internal fun ToolRecipesScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val runtime = remember(context) { ToolRecipeRuntime.get(context) }
    val recipes by runtime.recipes.collectAsState()
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpandedState(listState)
    // Root 依赖要按完整配方图算，跟着同一份快照刷新即可，不必再 collect 一遍同一个 Flow。
    val rootNeeding = remember(recipes) { runtime.rootDependentNames() }
    var deleteTarget by remember { mutableStateOf<ToolRecipe?>(null) }
    var shareTarget by remember { mutableStateOf<ToolRecipe?>(null) }
    var importReport by remember { mutableStateOf<String?>(null) }
    // 多选态与勾选集合：入口在顶栏（Checklist→Close 原位切换），长按行也可进入。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }

    val shareLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val recipe = shareTarget
        shareTarget = null
        if (uri == null || recipe == null) return@rememberLauncherForActivityResult
        scope.launch {
            val message = runCatching {
                val payload = withContext(Dispatchers.IO) { ToolRecipeShareFormat.encode(listOf(recipe)) }
                context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
                    ?: error(resources.getString(R.string.recipe_export_failed))
                resources.getString(R.string.recipe_exported, recipe.name)
            }.getOrElse { throwable ->
                throwable.message ?: resources.getString(R.string.recipe_export_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    // 可操作全集 = 当前排序后的全部配方；批量启停按勾选集合的当前状态取反。
    val sortedRecipes = remember(recipes) { recipes.sortedBy { it.name } }
    val allSelected = sortedRecipes.isNotEmpty() &&
        selectedIds.containsAll(sortedRecipes.map { it.name })
    val allEnabled = sortedRecipes.isNotEmpty() && sortedRecipes.all { it.enabled }

    // 返回键优先退出多选，不退页面。
    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectionMode,
        onBackCompleted = {
            selectionMode = false
            selectedIds = emptySet()
        },
    )

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val report = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.decodeToString()
                        ?: error(resources.getString(R.string.recipe_import_failed))
                    val imported = ToolRecipeShareFormat.decode(raw)
                    // 导入的配方一律停用：先让用户看过，再决定是否交给模型。
                    imported.forEach { recipe ->
                        runtime.save(recipe.copy(enabled = false))
                    }
                    resources.getString(R.string.recipe_imported, imported.size)
                }.getOrElse { throwable ->
                    when (throwable) {
                        is ToolRecipeShareException -> throwable.message ?: resources.getString(R.string.recipe_import_invalid)
                        else -> throwable.message ?: resources.getString(R.string.recipe_import_failed)
                    }
                }
            }
            importReport = report
        }
    }

    EtaScaffoldPage(
        title = stringResource(R.string.route_tool_recipes),
        onBack = onBack,
        listState = listState,
        // 导入入口从「分享与迁移」卡片换成 MCP 服务器页同款 FAB：滚动收起、回顶展开。
        // 多选态隐藏：批量操作栏占住底部，FAB 让位。
        floatingActionButton = if (!selectionMode) {
            {
                EtaExtendedFab(
                    text = stringResource(R.string.recipe_import),
                    icon = Icons.Rounded.Add,
                    expanded = fabExpanded,
                    onClick = {
                        TouchHaptics.click(view)
                        importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                )
            }
        } else {
            null
        },
        actions = {
            TopBarIconButton(
                icon = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.action_refresh),
                onClick = { scope.launch(Dispatchers.IO) { runtime.refresh() } },
                shapes = IconButtonDefaults.shapes(),
            )
            // 多选入口原位切换：Checklist→Close，退出并清空勾选。无配方可管时禁用。
            TopBarIconButton(
                icon = if (selectionMode) Icons.Rounded.Close else Icons.Rounded.Checklist,
                contentDescription = stringResource(
                    if (selectionMode) R.string.action_cancel else R.string.media_action_multiselect,
                ),
                onClick = {
                    TouchHaptics.click(view)
                    if (selectionMode) exitSelection() else selectionMode = true
                },
                enabled = recipes.isNotEmpty() || selectionMode,
                shapes = IconButtonDefaults.shapes(),
            )
        },
    ) {
        item(key = "summary") {
            // 标题行与页题、分段头重复，已删除；只保留说明文字，直接排在页面背景上。
            M3Text(
                text = stringResource(R.string.recipe_page_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
            )
        }
        item(key = "recipes") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
                title = if (selectionMode) {
                    stringResource(R.string.recipe_list_title, recipes.size) + " · " +
                        stringResource(R.string.media_selected_count, selectedIds.size)
                } else {
                    stringResource(R.string.recipe_list_title, recipes.size)
                },
            ) {
                if (recipes.isEmpty()) {
                    item {
                        EtaSegmentedItemContainer {
                            ListEmptyState(
                                title = stringResource(R.string.recipe_empty_title),
                                summary = stringResource(R.string.recipe_empty_summary),
                            )
                        }
                    }
                } else {
                    // 按名字排序：启停会刷新 updatedAtMillis，按更新时间排会让行在切换开关后跳位。
                    sortedRecipes.forEach { recipe ->
                        item(key = recipe.name) {
                            RecipeRow(
                                recipe = recipe,
                                rootNeeded = recipe.name in rootNeeding,
                                selectionMode = selectionMode,
                                checked = recipe.name in selectedIds,
                                onClick = {
                                    if (selectionMode) {
                                        selectedIds = if (recipe.name in selectedIds) {
                                            selectedIds - recipe.name
                                        } else {
                                            selectedIds + recipe.name
                                        }
                                    }
                                    // 非多选态整行不响应（与改造前一致：行本体不可点）。
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedIds = setOf(recipe.name)
                                    }
                                },
                                onToggle = { enabled ->
                                    scope.launch(Dispatchers.IO) {
                                        if (!runtime.setEnabled(recipe.name, enabled)) {
                                            importReport = resources.getString(R.string.recipe_toggle_failed)
                                        }
                                    }
                                },
                                onEdit = {
                                    // 编辑是整屏路由子页（RULES §3.3），进出场交给 NavDisplay。
                                    onNavigate(AppRoute.ToolRecipeEdit(recipe.name))
                                },
                                onShare = {
                                    shareTarget = recipe
                                    shareLauncher.launch(recipeShareFileName(recipe.name))
                                },
                                onDelete = { deleteTarget = recipe },
                            )
                        }
                    }
                }
            }
        }
        item(key = "bottom_spacer", contentType = "spacer") {
            // 多选操作栏悬浮在底部时预留高度，避免遮挡最后一个列表项。
            Spacer(
                modifier = Modifier
                    .height(if (selectionMode) 88.dp else 24.dp)
                    .navigationBarsPadding(),
            )
        }
    }

    // 批量操作栏：官方 M3E HorizontalFloatingToolbar 纯图标药丸，底部居中悬浮。
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            // 批量选择栏的进退场：位移走 M3E Spatial（轻微过冲），透明度走 Effects（无过冲）。
            enter = slideInVertically(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            ) { it } + fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = slideOutVertically(
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            ) { it } + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            RecipeSelectionBar(
                selectedCount = selectedIds.size,
                allSelected = allSelected,
                allEnabled = allEnabled,
                onSelectAll = {
                    TouchHaptics.click(view)
                    selectedIds = if (allSelected) {
                        emptySet()
                    } else {
                        sortedRecipes.map { it.name }.toSet()
                    }
                },
                onToggleEnabled = {
                    TouchHaptics.click(view)
                    // 以第一个选中项的目标状态为准：有停用 → 全部启用；否则全部停用。
                    val target = sortedRecipes
                        .filter { it.name in selectedIds }
                        .any { !it.enabled }
                    scope.launch(Dispatchers.IO) {
                        selectedIds.forEach { name ->
                            val current = sortedRecipes
                                .firstOrNull { it.name == name }?.enabled ?: return@forEach
                            if (current != target && !runtime.setEnabled(name, target)) {
                                importReport = resources.getString(R.string.recipe_toggle_failed)
                            }
                        }
                    }
                },
                onDelete = {
                    TouchHaptics.click(view)
                    showBatchDeleteDialog = true
                },
            )
        }
    }

    // 批量删除二次确认：口径与单条删除一致（删除后模型不再能调用，无法撤销）。
    if (showBatchDeleteDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.recipe_delete_title),
            summary = stringResource(R.string.recipe_batch_delete_message, selectedIds.size),
            onDismissRequest = { showBatchDeleteDialog = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { showBatchDeleteDialog = false },
                    onConfirm = {
                        scope.launch(Dispatchers.IO) {
                            selectedIds.forEach { name -> runtime.delete(name) }
                        }
                        showBatchDeleteDialog = false
                        exitSelection()
                    },
                )
            },
        ) {
        }
    }

    deleteTarget?.let { recipe ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.recipe_delete_title),
            summary = stringResource(R.string.recipe_delete_confirm, recipe.name),
            onDismissRequest = { deleteTarget = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { deleteTarget = null },
                    onConfirm = {
                        scope.launch(Dispatchers.IO) { runtime.delete(recipe.name) }
                        deleteTarget = null
                    },
                )
            },
        ) {
        }
    }

    importReport?.let { message ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.recipe_notice_title),
            summary = message,
            onDismissRequest = { importReport = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_ok),
                    onCancel = { importReport = null },
                    onConfirm = { importReport = null },
                )
            },
        ) {
        }
    }
}

/**
 * 单条配方行：与「技能」「MCP 工具」等兄弟页同款官方分段行——
 * 前导图标 + 名称 + 描述/状态摘要 + 行尾（溢出菜单、开关）。
 *
 * 不再手搓内边距与最小行高：行距、左右留白、摘要缩进全交给
 * [EtaSegmentedRow]（官方 `SegmentedListItem` 令牌），页面之间才对得齐。
 *
 * 行本体刻意不可点：进入编辑页只能走行尾溢出菜单的「编辑」。配方行只是列表里的一个摘要
 * （名称一行、描述两行），整行承担跳转容易在滑动或点开关时误触。
 */
@Composable
private fun RecipeRow(
    recipe: ToolRecipe,
    rootNeeded: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    EtaSegmentedRow(
        modifier = Modifier.fillMaxWidth(),
        // 多选态整行承接勾选切换；常规态不承接点击（编辑入口只剩行尾溢出菜单，避免误触）。
        onClick = if (selectionMode) onClick else null,
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(R.string.action_enter_selection),
        // 行尾开关属于「开关类」，保持官方内边距，不参与动作行的纵向补偿。
        choiceRow = true,
        leadingContent = {
            Icon(
                imageVector = Icons.Rounded.Build,
                contentDescription = null,
                tint = if (recipe.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        content = {
            // 名称限一行：配方名是模型写的标识符，可以很长（device_health_snapshot 就够两行），
            // 一行能把同一屏里能看几条配方的节奏保住。完整名字不用在这里想办法塞下——
            // 行尾溢出菜单里有「编辑」，编辑页顶栏就是完整名称。
            M3Text(
                text = recipe.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (recipe.description.isNotBlank()) {
                    // 描述限两行：原文由模型写就，几十上百字很常见，不限行会让每条配方吃掉大半屏。
                    // 想看全文走行尾溢出菜单的「编辑」，编辑页的「它做什么」输入框里是完整描述。
                    // 与技能页 SkillSwitchRow 同一口径（bodyMedium + onSurfaceVariant + 2 行省略）。
                    M3Text(
                        text = recipe.description,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                M3Text(
                    text = recipeStateLine(recipe.steps.size, rootNeeded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            if (selectionMode) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onClick() },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RecipeOverflowMenu(
                        recipeName = recipe.name,
                        onEdit = onEdit,
                        onShare = onShare,
                        onDelete = onDelete,
                    )
                    RecipeSwitch(checked = recipe.enabled, onToggle = onToggle)
                }
            }
        },
    )
}

/**
 * 配方多选批量操作栏：官方 M3E [HorizontalFloatingToolbar]（纯图标药丸），底部居中悬浮，
 * 与模型管理页（ProviderModelsTab.ModelSelectionBar）同款装配。
 * 动作序：全选（全不选）→ 批量启停 → 删除（error 色标破坏性，执行前另有确认弹窗）；
 * 「取消」不落底栏，由顶栏多选入口原位切换（Checklist→Close）承载，已选数量并入分组标题。
 * 组件结论同 ChatSelectionBar 的 KDoc（同版本依赖既有调用核证）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecipeSelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    allEnabled: Boolean,
    onSelectAll: () -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            leadingContent = {},
            // 端盖同心：首末两枚 40dp 圆钮与药丸端头弧心对齐（环隙与上下等宽）。
            contentPadding = EtaFloatingToolbarConcentricPadding(
                IconButtonDefaults.smallContainerSize().width,
            ),
        ) {
            // 动作组：固定 8dp 间距的一行图标按钮（EtaIconButton 的 `Standard` 档裸图标）。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EtaIconButton(
                    icon = if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                    contentDescription = stringResource(
                        if (allSelected) R.string.media_action_deselect_all else R.string.media_action_select_all,
                    ),
                    onClick = onSelectAll,
                    size = EtaIconSize.S,
                    style = EtaButtonStyle.Standard,
                )
                // 批量启停：按当前勾选集合的状态自适应图标（有停用 → 全部启用）。
                EtaIconButton(
                    icon = if (allEnabled) Icons.Rounded.ToggleOff else Icons.Rounded.ToggleOn,
                    contentDescription = stringResource(
                        if (allEnabled) R.string.recipe_disable else R.string.recipe_enable,
                    ),
                    onClick = onToggleEnabled,
                    enabled = selectedCount > 0,
                    size = EtaIconSize.S,
                    style = EtaButtonStyle.Standard,
                )
                // 删除是破坏性操作：图标用 error 色区分（官方裸 IconButton，容器/图标尺寸
                // 与 EtaIconButton 的 `Standard` + S 档一致），执行前另有确认弹窗。
                val deleteDescription = stringResource(R.string.action_delete)
                EtaTooltip(text = deleteDescription) {
                    M3IconButton(
                        onClick = onDelete,
                        enabled = selectedCount > 0,
                        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = deleteDescription,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 配方状态摘要：Root 依赖 · 步骤数。
 *
 * 启停状态不重复写成文字——行尾开关已经承载它，兄弟页的开关行也都不再复述。
 */
@Composable
private fun recipeStateLine(stepCount: Int, rootNeeded: Boolean): String = buildString {
    if (rootNeeded) {
        append(stringResource(R.string.recipe_state_root))
        append(" · ")
    }
    append(stringResource(R.string.recipe_steps_count, stepCount))
}

/** 配方行尾部溢出菜单：编辑 / 分享 / 删除收进一个菜单，避免与开关抢位置。 */
@Composable
private fun RecipeOverflowMenu(
    recipeName: String,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val view = LocalView.current
    Box {
        M3IconButton(
            onClick = {
                TouchHaptics.click(view)
                expanded = true
            },
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreHoriz,
                contentDescription = stringResource(R.string.recipe_more_named, recipeName),
            )
        }
        EtaMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            EtaMenuGroup(index = 0, count = 1) {
                // 与助手行、文件操作菜单同一套：条目一律带前导图标，破坏性项图标与文案同取 error 色。
                DropdownMenuItem(
                    text = { M3Text(text = stringResource(R.string.action_edit)) },
                    shape = EtaMenuItemShape(index = 0, count = 3),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                        )
                    },
                    onClick = {
                        expanded = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { M3Text(text = stringResource(R.string.recipe_share)) },
                    shape = EtaMenuItemShape(index = 1, count = 3),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                        )
                    },
                    onClick = {
                        expanded = false
                        onShare()
                    },
                )
                DropdownMenuItem(
                    text = {
                        M3Text(
                            text = stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    shape = EtaMenuItemShape(index = 2, count = 3),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        expanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** 配方行的开关：停用/启用。开关本身不弹确认——它是可逆操作，且行内即时反映。 */
@Composable
private fun RecipeSwitch(
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val view = LocalView.current
    EtaSwitch(
        checked = checked,
        onCheckedChange = { enabled ->
            TouchHaptics.click(view)
            onToggle(enabled)
        },
    )
}

private fun recipeShareFileName(name: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "$name-$stamp.${ToolRecipeShareFormat.FILE_SUFFIX}"
}
