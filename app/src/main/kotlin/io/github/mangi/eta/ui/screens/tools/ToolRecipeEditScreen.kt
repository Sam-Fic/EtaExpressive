package io.github.mangi.eta.ui.screens.tools

import android.content.res.Resources
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolRecipeStore
import io.github.mangi.eta.agent.tool.ToolRecipe
import io.github.mangi.eta.agent.tool.ToolRecipeConstants
import io.github.mangi.eta.agent.tool.ToolRecipeReservedNames
import io.github.mangi.eta.agent.tool.ToolRecipeStep
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.components.TopBarIconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 配方编辑整屏路由页（`AppRoute.ToolRecipeEdit`）。
 *
 * 按 `docs/RULES.md` §3.3 做成路由（配方名即身份键，[ToolRecipeRuntime.find] 可同步加载），
 * 进出场交给 miuix `NavDisplay` 程序化转场，不再走 `EtaPageSurface` 窗口。
 *
 * 打开时取一次快照，草稿不跟流重置（原 editing 状态同语义）；保存成功 Toast 后返回，
 * 失败 Toast 失败原因并留在本页——草稿不丢，可改名重试（原实现失败即关页丢草稿）。
 * 配方被并发删除时直接退栈，不渲染空壳。
 */
@Composable
internal fun ToolRecipeEditScreen(
    recipeName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val runtime = remember(context) { ToolRecipeRuntime.get(context) }
    val recipe = remember(recipeName) { runtime.find(recipeName) }
    // Root 依赖按完整配方图算（组合链上游也要 Root），与列表页同一份快照口径。
    val rootNeeding = remember(recipeName) { runtime.rootDependentNames() }
    // 改名查重要用同一份快照：编辑页里就地能判出「已有同名」，不必等落盘失败。
    val takenNames = remember(recipeName) { runtime.all().map { it.name }.toSet() }
    LaunchedEffect(recipe) {
        if (recipe == null) onBack()
    }
    recipe?.let { current ->
        RecipeEditPage(
            recipe = current,
            rootNeeded = current.name in rootNeeding,
            takenNames = takenNames,
            onDismiss = onBack,
            onSave = { updated ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runtime.save(current, updated) }
                    when (result) {
                        is AgentToolRecipeStore.SaveResult.Ok -> {
                            Toast.makeText(
                                context,
                                resources.getString(R.string.recipe_saved, result.recipe.name),
                                Toast.LENGTH_SHORT,
                            ).show()
                            onBack()
                        }

                        is AgentToolRecipeStore.SaveResult.Failed ->
                            Toast.makeText(
                                context,
                                recipeSaveFailureText(resources, result.code),
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                }
            },
        )
    }
}

/**
 * 配方编辑页：审计 + 启停 + 改名 + 改描述。
 *
 * 版式对齐兄弟页（KernelSU 式分段列表 + M3 大标题）：
 * - **启用置顶**：开关是这份配方最要紧的状态，放在第一屏第一张卡，用户不必滚动去找；
 * - **名称**：列表行把名字压成一行会截断，这里给出完整名字并允许改。名字是身份键
 *   （文件名 + 模型侧工具名 `recipe_<name>` + 其它配方的 compose 引用），所以校验放在落盘前；
 * - 「它做什么」是可编辑的描述，紧随其后——模型靠这段文字决定什么时候调用这个工具；
 * - 步骤与入参是只读的审计信息，逐条分段行展示：前导图标区分操作类型，命令原样透出；
 * - 「工具信息」给出模型侧工具名与 Root 依赖，审这份配方到底做了什么时要看这两项。
 *
 * 保存挂在顶栏动作位（`Icons.Rounded.Save` 图标按钮，与远程智能体编辑页同款），返回键即取消：
 * 没有改动时保存键置灰，避免一次空保存把配方版本号顶上去。
 */
@Composable
private fun RecipeEditPage(
    recipe: ToolRecipe,
    rootNeeded: Boolean,
    takenNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (ToolRecipe) -> Unit,
) {
    var name by rememberSaveable(recipe.name) { mutableStateOf(recipe.name) }
    var description by rememberSaveable(recipe.name) { mutableStateOf(recipe.description) }
    var enabled by rememberSaveable(recipe.name) { mutableStateOf(recipe.enabled) }
    // 名字用 trim 后的值参与校验与保存：粘贴带来的首尾空白不该算进配方名。
    val trimmedName = name.trim()
    val nameError = recipeNameError(trimmedName, recipe.name, takenNames)
    val dirty = trimmedName != recipe.name || description != recipe.description || enabled != recipe.enabled
    val parameters = remember(recipe.parameters) { recipeParameters(recipe.parameters) }
    EtaScaffold(
        title = stringResource(R.string.recipe_edit_title, recipe.name),
        onBack = onDismiss,
        modifier = Modifier.fillMaxSize().imePadding(),
        actions = {
            // 保存走顶栏图标按钮（远程智能体编辑页、助手编辑页同款）：文字按钮在
            // 大标题旁显得像正文，图标按钮与返回箭头左右对称，也不挤占标题宽度。
            // 名字不合法时保存置灰：坏名字会被当成文件名，拦住比落盘后改正便宜。
            TopBarIconButton(
                icon = Icons.Rounded.Save,
                contentDescription = stringResource(R.string.action_save),
                onClick = {
                    onSave(
                        recipe.copy(
                            name = trimmedName,
                            description = description,
                            enabled = enabled,
                        ),
                    )
                },
                enabled = dirty && nameError == null,
                shapes = IconButtonDefaults.shapes(),
            )
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

            // ② 名称：列表行限一行会截断，这里既显示完整名字也允许改。
            //    等宽字体——名字是标识符，不是自然语言；单行输入框横向滚动看全整串。
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { M3Text(text = stringResource(R.string.recipe_name)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                supportingText = {
                    M3Text(
                        text = nameError ?: stringResource(
                            R.string.recipe_name_hint,
                            ToolRecipeConstants.TOOL_PREFIX + trimmedName,
                        ),
                    )
                },
                isError = nameError != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next,
                ),
            )

            // ③ 它做什么：模型靠这段描述决定什么时候调用这个工具，所以它可编辑。
            //    走官方 OutlinedTextField：自带描边圆角与浮动标签，直接落在页面背景上。
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                modifier = Modifier.fillMaxWidth(),
                label = { M3Text(text = stringResource(R.string.recipe_description)) },
                placeholder = { M3Text(text = stringResource(R.string.recipe_description_hint)) },
                minLines = 2,
            )

            // ④ 步骤：逐条分段行。原先把全部步骤塞进一个容器用小字排版，
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
                                )
                            },
                            supportingContent = {
                                M3Text(
                                    text = recipeStepLabel(step),
                                )
                            },
                        )
                    }
                }
            }

            // ⑤ 入参：模型调用时要填的实参，写清类型与是否必填。
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

            // ⑥ 工具信息：模型侧叫什么名字、要不要 Root——审这份配方时必看两项。
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
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * 改名时的就地校验，返回给输入框当 supporting text；`null` 表示这个名字可以用。
 *
 * 规则与 [ToolRecipeConstants.TOOL_NAME_PATTERN] 同源（模型写配方时走的就是它），
 * 判据一条不放宽：名字会直接变成文件名，`../` 一类逃逸只能在这里拦。
 */
@Composable
private fun recipeNameError(name: String, currentName: String, takenNames: Set<String>): String? = when {
    name == currentName -> null
    !ToolRecipeConstants.TOOL_NAME_PATTERN.matches(name) ->
        stringResource(R.string.recipe_name_invalid)

    name in ToolRecipeReservedNames.RESERVED -> stringResource(R.string.recipe_name_reserved)

    // 与 store 的查重口径一致：大小写不敏感，且不算自己。
    takenNames.any { it != currentName && it.equals(name, ignoreCase = true) } ->
        stringResource(R.string.recipe_name_taken)

    else -> null
}

/**
 * 保存失败的错误码 → 文案。
 *
 * 错误码由 [AgentToolRecipeStore] 产出，文案只在这里按语言取；取不到的一律落到通用失败文案。
 * 页面已经就地校验过一遍，走到这里说明是并发改同名 / 磁盘写失败这类兜底情况。
 */
private fun recipeSaveFailureText(resources: Resources, code: String): String = when (code) {
    "INVALID_RECIPE_NAME" -> resources.getString(R.string.recipe_name_invalid)
    "RESERVED_RECIPE_NAME" -> resources.getString(R.string.recipe_name_reserved)
    "RECIPE_NAME_TAKEN" -> resources.getString(R.string.recipe_name_taken)
    "RECIPE_REFERENCE_STALE" -> resources.getString(R.string.recipe_rename_partial)
    else -> resources.getString(R.string.recipe_save_failed)
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
