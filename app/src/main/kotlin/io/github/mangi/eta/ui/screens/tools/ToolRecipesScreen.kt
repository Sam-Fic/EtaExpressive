package io.github.mangi.eta.ui.screens.tools

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolRecipeStore
import io.github.mangi.eta.agent.tool.ToolRecipe
import io.github.mangi.eta.agent.tool.ToolRecipeShareException
import io.github.mangi.eta.agent.tool.ToolRecipeShareFormat
import io.github.mangi.eta.agent.tool.ToolRecipeStep
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.adaptive.EtaPageSurface
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.material3.Text as M3Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

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
    fun save(recipe: ToolRecipe): Boolean =
        when (val result = store.save(recipe)) {
            is AgentToolRecipeStore.SaveResult.Ok -> {
                refresh(); true
            }

            is AgentToolRecipeStore.SaveResult.Failed -> false
        }

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
    var editing by remember { mutableStateOf<ToolRecipe?>(null) }
    var deleteTarget by remember { mutableStateOf<ToolRecipe?>(null) }
    var shareTarget by remember { mutableStateOf<ToolRecipe?>(null) }
    var importReport by remember { mutableStateOf<String?>(null) }

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
        floatingActionButton = {
            EtaExtendedFab(
                text = stringResource(R.string.recipe_import),
                icon = Icons.Rounded.Add,
                expanded = fabExpanded,
                onClick = {
                    TouchHaptics.click(view)
                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
            )
        },
        actions = {
            M3IconButton(
                onClick = {
                    TouchHaptics.click(view)
                    scope.launch(Dispatchers.IO) { runtime.refresh() }
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.action_refresh),
                )
            }
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
                title = stringResource(R.string.recipe_list_title, recipes.size),
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
                    recipes
                        .sortedBy { it.name }
                        .forEach { recipe ->
                            item(key = recipe.name) {
                                RecipeRow(
                                    recipe = recipe,
                                    rootNeeded = recipe.name in rootNeeding,
                                    onToggle = { enabled ->
                                        scope.launch(Dispatchers.IO) {
                                            if (!runtime.setEnabled(recipe.name, enabled)) {
                                                importReport = resources.getString(R.string.recipe_toggle_failed)
                                            }
                                        }
                                    },
                                    onEdit = { editing = recipe },
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
    }

    editing?.let { recipe ->
        RecipeEditPage(
            recipe = recipe,
            // Root 依赖按完整配方图算（组合链上游也要 Root），与列表页同一份快照。
            rootNeeded = recipe.name in rootNeeding,
            onDismiss = { editing = null },
            onSave = { updated ->
                scope.launch(Dispatchers.IO) {
                    val ok = runtime.save(updated)
                    importReport = if (ok) {
                        resources.getString(R.string.recipe_saved, updated.name)
                    } else {
                        resources.getString(R.string.recipe_save_failed)
                    }
                }
                editing = null
            },
        )
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
 * 行本体刻意不可点：进入编辑页只能走行尾溢出菜单的「编辑」。配方行只在「看一眼前三行」
 * 之外没有别的语义，整行承担跳转容易在滑动或点开关时误触。
 */
@Composable
private fun RecipeRow(
    recipe: ToolRecipe,
    rootNeeded: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    EtaSegmentedRow(
        modifier = Modifier.fillMaxWidth(),
        // 整行不承接点击：编辑入口只剩行尾溢出菜单，避免误触直接进编辑页。
        onClick = null,
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
            M3Text(
                text = recipe.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (recipe.description.isNotBlank()) {
                    // 与技能行一致：摘要最多两行，完整描述在编辑页里看。
                    M3Text(
                        text = recipe.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecipeOverflowMenu(
                    recipeName = recipe.name,
                    onEdit = onEdit,
                    onShare = onShare,
                    onDelete = onDelete,
                )
                RecipeSwitch(checked = recipe.enabled, onToggle = onToggle)
            }
        },
    )
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

/**
 * 配方编辑页：审计 + 启停 + 改描述。
 *
 * 版式对齐兄弟页（KernelSU 式分段列表 + M3 大标题）：
 * - **启用置顶**：开关是这份配方最要紧的状态，放在第一屏第一张卡，用户不必滚动去找；
 * - 「它做什么」是可编辑的描述，紧随其后——模型靠这段文字决定什么时候调用这个工具；
 * - 步骤与入参是只读的审计信息，逐条分段行展示：前导图标区分操作类型，命令原样透出；
 * - 「工具信息」给出模型侧工具名与 Root 依赖，审这份配方到底做了什么时要看这两项。
 *
 * 保存挂在顶栏动作位（与助手编辑页同款），返回键即取消：没有改动时保存键置灰，
 * 避免一次空保存把配方版本号顶上去。
 */
@Composable
private fun RecipeEditPage(
    recipe: ToolRecipe,
    rootNeeded: Boolean,
    onDismiss: () -> Unit,
    onSave: (ToolRecipe) -> Unit,
) {
    var description by remember { mutableStateOf(recipe.description) }
    var enabled by remember { mutableStateOf(recipe.enabled) }
    val dirty = description != recipe.description || enabled != recipe.enabled
    val parameters = remember(recipe.parameters) { recipeParameters(recipe.parameters) }
    EtaPageSurface(onDismissRequest = onDismiss) {
        EtaScaffold(
            title = stringResource(R.string.recipe_edit_title, recipe.name),
            onBack = onDismiss,
            modifier = Modifier.fillMaxSize().imePadding(),
            actions = {
                // 官方 TextButton（自带涟漪与禁用态）；返回键即取消，不再另摆一条底栏动作。
                M3TextButton(
                    onClick = { onSave(recipe.copy(description = description, enabled = enabled)) },
                    enabled = dirty,
                ) {
                    M3Text(text = stringResource(R.string.action_save))
                }
            },
        ) { padding, scrollConnection, _ ->
            Column(
                verticalArrangement = Arrangement.spacedBy(EtaSegmentedGroupSpacing),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .nestedScroll(scrollConnection)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = EtaGroupHorizontalPadding)
                    .padding(bottom = EtaSegmentedGroupSpacing),
            ) {
                // ① 启用置顶：这一页最要紧的状态就是「模型还能不能调用它」。
                EtaSegmentedColumn {
                    item {
                        EtaSwitchRow(
                            title = stringResource(R.string.recipe_enable),
                            summary = buildString {
                                append(stringResource(R.string.recipe_enable_summary))
                                if (rootNeeded) {
                                    append('\n')
                                    append(stringResource(R.string.recipe_root_warning))
                                }
                            },
                            checked = enabled,
                            onCheckedChange = { enabled = it },
                            leading = {
                                Icon(
                                    imageVector = Icons.Rounded.Build,
                                    contentDescription = null,
                                    tint = if (enabled) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                        )
                    }
                }

                // ② 它做什么：模型靠这段描述决定什么时候调用这个工具，所以它可编辑。
                //    走官方 OutlinedTextField：自带描边圆角与浮动标签，直接落在页面背景上。
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { M3Text(text = stringResource(R.string.recipe_description)) },
                    placeholder = { M3Text(text = stringResource(R.string.recipe_description_hint)) },
                    minLines = 2,
                )

                // ③ 步骤：逐条分段行。原先把全部步骤塞进一个容器用小字排版，
                //    命令一长就被挤成不可读的一坨；这里一条一行，摘要原样透出。
                EtaSegmentedColumn(
                    title = stringResource(R.string.recipe_steps_section, recipe.steps.size),
                ) {
                    recipe.steps.forEachIndexed { index, step ->
                        item(key = "step-$index") {
                            EtaSegmentedRow(
                                modifier = Modifier.fillMaxWidth(),
                                leadingContent = {
                                    Icon(
                                        imageVector = recipeStepIcon(step),
                                        contentDescription = null,
                                    )
                                },
                                content = {
                                    M3Text(
                                        text = stringResource(R.string.recipe_step_title, index + 1),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                supportingContent = {
                                    M3Text(
                                        text = recipeStepLabel(step),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                }

                // ④ 入参：模型调用时要填的实参，写清类型与是否必填。
                if (parameters.isNotEmpty()) {
                    EtaSegmentedColumn(
                        title = stringResource(R.string.recipe_params_section, parameters.size),
                    ) {
                        parameters.forEach { parameter ->
                            item(key = "param-${parameter.name}") {
                                EtaSegmentedRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Rounded.Tune,
                                            contentDescription = null,
                                        )
                                    },
                                    content = {
                                        M3Text(
                                            text = parameter.name,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                        )
                                    },
                                    supportingContent = {
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            M3Text(
                                                text = stringResource(
                                                    R.string.recipe_param_meta,
                                                    parameter.type,
                                                    stringResource(
                                                        if (parameter.required) {
                                                            R.string.recipe_param_required
                                                        } else {
                                                            R.string.recipe_param_optional
                                                        },
                                                    ),
                                                ),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            parameter.description?.let { note ->
                                                M3Text(
                                                    text = note,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // ⑤ 工具信息：模型侧叫什么名字、要不要 Root——审这份配方时必看两项。
                EtaSegmentedColumn(title = stringResource(R.string.recipe_info_section)) {
                    item {
                        EtaSegmentedRow(
                            modifier = Modifier.fillMaxWidth(),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Rounded.Link,
                                    contentDescription = null,
                                )
                            },
                            content = {
                                M3Text(
                                    text = stringResource(R.string.recipe_model_name),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            supportingContent = {
                                M3Text(
                                    text = recipe.modelToolName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                    item {
                        EtaSegmentedRow(
                            modifier = Modifier.fillMaxWidth(),
                            leadingContent = {
                                Icon(
                                    imageVector = Icons.Rounded.Shield,
                                    contentDescription = null,
                                    tint = if (rootNeeded) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            content = {
                                M3Text(
                                    text = stringResource(R.string.recipe_root_dependence),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            supportingContent = {
                                M3Text(
                                    text = stringResource(
                                        if (rootNeeded) {
                                            R.string.recipe_state_root
                                        } else {
                                            R.string.recipe_state_no_root
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 步骤图标：按 op 选一个能一眼分辨类型的图标。
 *
 * 步骤类型是白名单，穷举即可；新增 op 时在这里补一个图标，不要退化成同一个。
 */
private fun recipeStepIcon(step: ToolRecipeStep): ImageVector = when (step) {
    is ToolRecipeStep.HttpRequest -> Icons.Rounded.Public
    is ToolRecipeStep.RootShell -> Icons.Rounded.Terminal
    is ToolRecipeStep.SettingsRead, is ToolRecipeStep.SettingsWrite -> Icons.Rounded.Settings
    is ToolRecipeStep.FileRead, is ToolRecipeStep.FileWrite -> Icons.Rounded.FolderOpen
    is ToolRecipeStep.ContentQuery -> Icons.Rounded.Search
    is ToolRecipeStep.Compose -> Icons.Rounded.AccountTree
    is ToolRecipeStep.Literal -> Icons.Rounded.Description
}

/** 配方入参的一行摘要：名字、类型、是否必填、参数说明。 */
private data class RecipeParameter(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String?,
)

/**
 * 从配方的 parameters schema 抽出可读的入参列表。
 *
 * schema 由模型写就，字段可能缺失或类型不对：解析一律容错，拿不到就当成空列表，
 * 页面照常渲染其余部分，不因为一段坏 schema 打不开整个编辑页。
 */
private fun recipeParameters(parameters: JSONObject): List<RecipeParameter> {
    val properties = parameters.optJSONObject("properties") ?: return emptyList()
    val required = buildSet<String> {
        val array = parameters.optJSONArray("required") ?: return@buildSet
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }
    return properties.keys().asSequence().map { name ->
        val spec = properties.optJSONObject(name)
        RecipeParameter(
            name = name,
            type = spec?.optString("type")?.takeIf { it.isNotBlank() } ?: "string",
            required = name in required,
            description = spec?.optString("description")?.takeIf { it.isNotBlank() },
        )
    }.toList()
}

@Composable
private fun recipeStepLabel(step: ToolRecipeStep): String = when (step) {
    is ToolRecipeStep.HttpRequest ->
        stringResource(R.string.recipe_step_http, step.method, step.url)

    is ToolRecipeStep.RootShell ->
        stringResource(R.string.recipe_step_root, step.command)

    is ToolRecipeStep.SettingsRead ->
        stringResource(R.string.recipe_step_settings_read, step.namespace, step.key)

    is ToolRecipeStep.SettingsWrite ->
        stringResource(R.string.recipe_step_settings_write, step.namespace, step.key)

    is ToolRecipeStep.FileRead ->
        stringResource(R.string.recipe_step_file_read, step.path)

    is ToolRecipeStep.FileWrite ->
        stringResource(R.string.recipe_step_file_write, step.path)

    is ToolRecipeStep.ContentQuery ->
        stringResource(R.string.recipe_step_content, step.uri)

    is ToolRecipeStep.Compose ->
        stringResource(R.string.recipe_step_compose, step.recipe)

    is ToolRecipeStep.Literal ->
        stringResource(R.string.recipe_step_literal)
}

/** 分享文件名：带配方名与时间戳，便于在文件管理器里认出来。 */
private fun recipeShareFileName(name: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "$name-$stamp.${ToolRecipeShareFormat.FILE_SUFFIX}"
}
