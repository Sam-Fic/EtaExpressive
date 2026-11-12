package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject

/**
 * 模型自写工具的接口工具。
 *
 * 提供两个动作：
 * - [AUTHOR_TOOL] `eta_author_tool`：新建/更新/删除/启停一份配方
 * - [LIST_TOOLS] `eta_list_recipes`：列出已有配方
 *
 * 关键约束（写进 schema 描述里，也是模型最容易踩的坑）：
 * - 配方是**声明式步骤**，不是代码；步骤类型是白名单
 * - 新建/更新后**下一轮**才可调用（当前这一轮调用会拿到 UNKNOWN_TOOL）；
 *   缓存优先档下它永远不会成为原生工具，而是经 `tool_call` 转发，参数说明由回执给出
 * - 名字冲突时返回候选列表让模型选，不要自己猜
 * - 配方不能组合自己、不能形成环
 */
internal class AgentSelfAuthorTools(
    private val store: AgentToolRecipeStore,
    private val rootAvailable: () -> Boolean = { false },
    /**
     * 缓存优先档：配方 schema 不进 `tools`，改经 `tool_call` 转发。
     * 回执必须自带参数说明与调用方式，否则模型拿不到任何可照着调用的文档。
     */
    private val recipesViaCall: Boolean = false,
) {
    companion object {
        const val AUTHOR_TOOL = io.github.mangi.eta.agent.model.AgentSelfSettingsToolCatalog.AUTHOR_TOOL
        const val LIST_TOOLS = io.github.mangi.eta.agent.model.AgentSelfSettingsToolCatalog.LIST_RECIPES

        /** 名字命中多个时最多返回的候选数。 */
        private const val MAX_CANDIDATES = 10
    }

    /** 配方的调用方式：缓存优先档经固定入口转发，其余档位是原生工具。 */
    private val callVia: String
        get() = if (recipesViaCall) io.github.mangi.eta.agent.model.AgentToolGroupCatalog.CALL else "direct"

    fun execute(name: String, args: JSONObject): String? = when (name) {
        AUTHOR_TOOL -> author(args)
        LIST_TOOLS -> list(args)
        else -> null
    }

    /**
     * 列出配方。
     *
     * @param args 可选 `name` 过滤
     */
    private fun list(args: JSONObject): String {
        val filter = args.optString("name").trim()
        val all = store.all()
        val rootNeeding = store.rootDependentRecipes(all)
        val matched = if (filter.isBlank()) {
            all
        } else {
            all.filter { it.name.equals(filter, ignoreCase = true) }
        }
        if (filter.isNotBlank() && matched.isEmpty()) {
            val candidates = store.candidates(filter)
            if (candidates.isNotEmpty()) {
                return ambiguous(filter, candidates)
            }
            return error("RECIPE_NOT_FOUND", "没有名为「$filter」的配方")
        }
        val items = JSONArray()
        matched.forEach { items.put(ToolRecipeSummary.of(it, rootNeeding)) }
        return JSONObject()
            .put("ok", true)
            .put("action", "list")
            .put("count", matched.size)
            .put("total", all.size)
            .put("recipes", items)
            .put("call_via", callVia)
            .put(
                "note",
                if (recipesViaCall) {
                    "已启用的配方经 $callVia 调用：name 填 tool_name，arguments 按每条的 parameters 填写；它们不会作为原生工具出现"
                } else {
                    "已启用的配方会在下一轮请求里作为 recipe_<name> 工具出现；新建后本轮还不能调用它"
                },
            )
            .toString()
    }

    /**
     * 新建 / 更新 / 删除 / 启停配方。
     *
     * @param args.action create / update / delete / enable / disable
     */
    private fun author(args: JSONObject): String {
        val action = args.optString("action").trim().lowercase()
        return when (action) {
            "create" -> create(args)
            "update" -> update(args)
            "delete" -> delete(args)
            "enable" -> setEnabled(args, true)
            "disable" -> setEnabled(args, false)
            else -> error(
                "INVALID_ARGUMENT",
                "action 必须是 create / update / delete / enable / disable",
            )
        }
    }

    private fun create(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isBlank()) {
            return error("INVALID_ARGUMENT", "create 需要提供 name")
        }
        if (store.exists(name)) {
            val candidates = store.candidates(name)
            return ambiguous(name, candidates)
        }
        return persist(args, isCreate = true)
    }

    private fun update(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isBlank()) {
            return error("INVALID_ARGUMENT", "update 需要提供 name")
        }
        val existing = store.find(name)
            ?: return error("RECIPE_NOT_FOUND", "没有名为「$name」的配方；先用 list 看看有哪些")
        return persist(args, isCreate = false, existing = existing)
    }

    private fun delete(args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isBlank()) {
            return error("INVALID_ARGUMENT", "delete 需要提供 name")
        }
        if (!store.exists(name)) {
            return error("RECIPE_NOT_FOUND", "没有名为「$name」的配方")
        }
        // 删除前先看谁在组合它，避免留下悬空引用。
        val dependents = store.all().filter { it.name != name && name in it.composedRecipes }
        val removed = store.delete(name)
        if (!removed) {
            return error("RECIPE_DELETE_FAILED", "删除失败：$name")
        }
        return JSONObject()
            .put("ok", true)
            .put("action", "delete")
            .put("name", name)
            .put("removed_from_model", true)
            .apply {
                if (dependents.isNotEmpty()) {
                    put(
                        "warning",
                        "这些配方组合了「$name」，现在会失败，请更新它们：${dependents.joinToString(", ")}",
                    )
                }
            }
            .put("note", "该配方已从下一轮的模型工具列表中移除")
            .toString()
    }

    private fun setEnabled(args: JSONObject, enabled: Boolean): String {
        val name = args.optString("name").trim()
        if (name.isBlank()) {
            return error("INVALID_ARGUMENT", "${if (enabled) "enable" else "disable"} 需要提供 name")
        }
        if (!store.exists(name)) {
            return error("RECIPE_NOT_FOUND", "没有名为「$name」的配方")
        }
        val updated = store.setEnabled(name, enabled)
            ?: return error("RECIPE_SAVE_FAILED", "更新失败：$name")
        return JSONObject()
            .put("ok", true)
            .put("action", if (enabled) "enable" else "disable")
            .put("name", updated.name)
            .put("enabled", updated.enabled)
            .put(
                "note",
                if (enabled) {
                    "该配方将在下一轮作为 recipe_${updated.name} 工具出现"
                } else {
                    "该配方已从下一轮的模型工具列表中移除"
                },
            )
            .toString()
    }

    /**
     * 校验并保存配方。
     *
     * 这里做三件模型看不到但很关键的事：
     * 1. 组合环检测
     * 2. 组合目标的 Root 需求推导（用于无 Root 时对模型隐藏）
     * 3. 参数占位符与 schema 的一致性检查（避免模型写了永远填不上的模板）
     */
    private fun persist(args: JSONObject, isCreate: Boolean, existing: ToolRecipe? = null): String {
        val definition = args.optJSONObject("recipe")
            ?: return error(
                "INVALID_ARGUMENT",
                "需要提供 recipe 对象，含 name / description / parameters / steps",
            )
        val all = store.all()
        val existingNames = all.map { it.name }.toMutableSet()

        when (val parsed = ToolRecipeParser.parse(
            raw = definition,
            existingNames = existingNames,
            nowMillis = System.currentTimeMillis(),
        )) {
            is ToolRecipeParser.ParseResult.Invalid -> return error(parsed.code, parsed.message)
            is ToolRecipeParser.ParseResult.Ok -> {
                val recipe = parsed.value

                // 环检测：把新配方加进图里再查一次。
                val prospective = (all.filter { it.name != recipe.name } + recipe)
                store.detectCycle(prospective)?.let { cycle ->
                    return error(
                        "RECIPE_CYCLE",
                        "组合关系成环：${cycle.joinToString(" → ")}；请去掉其中一条组合边",
                    )
                }

                // 组合目标必须存在（parser 已查，这里兜底）。
                recipe.composedRecipes.forEach { composed ->
                    if (composed !in existingNames) {
                        return error("RECIPE_NOT_FOUND", "组合目标「$composed」不存在")
                    }
                }

                // 占位符必须在 parameters 里声明，否则模板永远填不上。
                val declared = recipe.parameters.optJSONObject("properties")?.keys()?.asSequence()?.toSet()
                    ?: emptySet()
                val used = recipe.steps.flatMap { step ->
                    when (step) {
                        is ToolRecipeStep.HttpRequest -> ToolRecipeTemplate.placeholders(step.url) +
                            ToolRecipeTemplate.placeholders(step.body.orEmpty()) +
                            step.headers.values.flatMap { ToolRecipeTemplate.placeholders(it) }
                        is ToolRecipeStep.RootShell -> ToolRecipeTemplate.placeholders(step.command)
                        is ToolRecipeStep.SettingsRead -> ToolRecipeTemplate.placeholders(step.key)
                        is ToolRecipeStep.SettingsWrite -> ToolRecipeTemplate.placeholders(step.key) +
                            ToolRecipeTemplate.placeholders(step.value)
                        is ToolRecipeStep.FileRead -> ToolRecipeTemplate.placeholders(step.path)
                        is ToolRecipeStep.FileWrite -> ToolRecipeTemplate.placeholders(step.path) +
                            ToolRecipeTemplate.placeholders(step.content)
                        is ToolRecipeStep.ContentQuery -> ToolRecipeTemplate.placeholders(step.uri)
                        is ToolRecipeStep.Compose -> emptySet()
                        is ToolRecipeStep.Literal -> ToolRecipeTemplate.placeholders(step.text)
                    }
                }.toSet()
                val undeclared = used - declared
                if (undeclared.isNotEmpty()) {
                    return error(
                        "RECIPE_UNDECLARED_PARAMETER",
                        "步骤里用到这些占位符，但 parameters 没声明：${undeclared.joinToString(", ")}；" +
                            "请把它们加进 parameters.properties",
                    )
                }

                // 需要 Root 的配方在无 Root 环境下不执行，只保存但标记。
                val needsRoot = store.rootDependentRecipes(prospective).contains(recipe.name) || recipe.requiresRoot

                val saved = when (val result = store.save(recipe)) {
                    is AgentToolRecipeStore.SaveResult.Failed -> return error(result.code, result.message)
                    is AgentToolRecipeStore.SaveResult.Ok -> result.recipe
                }

                return JSONObject()
                    .put("ok", true)
                    .put("action", if (isCreate) "create" else "update")
                    .put("name", saved.name)
                    .put("tool_name", saved.modelToolName)
                    .put("version", saved.version)
                    .put("requires_root", needsRoot)
                    .put("enabled", saved.enabled)
                    // 缓存优先档不下发配方 schema，回执是模型唯一能拿到参数说明的地方。
                    .put("parameters", saved.parameters)
                    .put("call_via", callVia)
                    .put(
                        "note",
                        buildString {
                            append("已保存。")
                            if (needsRoot && !rootAvailable()) {
                                append("该配方需要 Root，当前设备没有 Root 授权，因此不可调用。")
                            } else if (recipesViaCall) {
                                append(
                                    "下一轮起用 $callVia 调用：name=${saved.modelToolName}，" +
                                        "arguments 按 parameters 填写；它不会作为原生工具出现，本轮还不能调用。",
                                )
                            } else {
                                append("它会在下一轮请求中作为 ${saved.modelToolName} 工具出现；本轮还不能调用。")
                            }
                        },
                    )
                    .toString()
            }
        }
    }

    /** 名字命中多个时返回候选，让模型自己选，不替它猜。 */
    private fun ambiguous(name: String, candidates: List<ToolRecipe>): String {
        val items = JSONArray()
        candidates.take(MAX_CANDIDATES).forEach { recipe ->
            items.put(
                JSONObject()
                    .put("name", recipe.name)
                    .put("tool_name", recipe.modelToolName)
                    .put("description", recipe.description)
                    .put("enabled", recipe.enabled),
            )
        }
        return JSONObject()
            .put("ok", false)
            .put("code", "RECIPE_AMBIGUOUS")
            .put("message", "「$name」匹配到多个配方或已存在；用下面的确切 name 再调一次，不要重复同样的调用")
            .put("candidates", items)
            .toString()
    }

    private fun error(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()
}
