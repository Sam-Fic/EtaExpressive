package io.github.mangi.eta.agent.tool

import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配方存储与运行时注册表。
 *
 * 配方以 JSON 文件落盘在 `filesDir/tool_recipes/<name>.json`，因此：
 * - 设备重启、App 更新后仍然存在（自进化成果不丢失）
 * - 用户可以直接查看、编辑、删除（可审计）
 * - 不需要数据库迁移
 *
 * 注册表负责把「已启用的配方」投影成模型可见的 function schema，并在每轮请求前
 * 重新读取，因此模型创建配方后，**下一轮**就能调用它。
 */
internal class AgentToolRecipeStore(
    private val rootDir: File,
) {
    private val lock = ReentrantReadWriteLock()
    private var cache: Map<String, ToolRecipe>? = null

    private val recipesDir: File get() = rootDir

    /**
     * 丢弃内存缓存，下一轮读磁盘。
     *
     * 备份恢复直接改 `tool_recipes/` 目录、绕过了本 Store，必须显式失效掉缓存：
     * 否则 agent 那一侧仍拿着旧配方表，模型看不到刚恢复出来的工具。
     */
    fun invalidate() {
        lock.write { cache = null }
    }

    /** 全部配方（含未启用）。 */
    fun all(): List<ToolRecipe> {
        lock.read {
            return loadLocked().values.toList()
        }
    }

    /** 已启用的配方。 */
    fun enabled(): List<ToolRecipe> = all().filter { it.enabled }

    /** 按名字取配方。 */
    fun find(name: String): ToolRecipe? = all().firstOrNull { it.name == name }

    /** 名字是否已存在（大小写不敏感，避免模型侧近似重名）。 */
    fun exists(name: String): Boolean = all().any { it.name.equals(name, ignoreCase = true) }

    /** 名字模糊匹配到的候选（用于歧义时给模型列表而不是猜）。 */
    fun candidates(like: String): List<ToolRecipe> {
        val needle = like.trim().lowercase()
        if (needle.isBlank()) return emptyList()
        return all().filter {
            it.name.lowercase() == needle ||
                it.name.lowercase().contains(needle) ||
                it.description.lowercase().contains(needle)
        }
    }

    /**
     * 保存一份配方。同名覆盖视为「更新」，版本号递增。
     *
     * @return 保存结果：成功返回配方，失败返回错误码与原因。
     */
    fun save(recipe: ToolRecipe): SaveResult = lock.write {
        persistLocked(recipe, previous = loadLocked()[recipe.name])
    }

    /**
     * 改名：写到新名字的文件、删掉旧文件，并把其它配方里指向旧名的 [ToolRecipeStep.Compose]
     * 引用一并改成新名——否则组合链在改名那一刻就断了，而模型只会看到一个「配方不存在」。
     *
     * @param from 旧名字（身份键，等于旧文件名）。
     * @param updated 改名后的完整配方；可以顺带带上同一次编辑里的描述/启用状态，
     *                避免「改名 + 保存」落盘两次、版本号跳两格。
     *
     * 顺序刻意是「先写新文件，再删旧文件，最后改引用」：中途失败最多留下多余副本，
     * 不会把配方本身弄丢。引用改写失败要如实返回错误——静默留下断掉的 compose 比改名失败更糟。
     */
    fun rename(from: String, updated: ToolRecipe): SaveResult {
        lock.write {
            val all = loadLocked()
            val current = all[from]
                ?: return SaveResult.Failed("RECIPE_NOT_FOUND", "配方「$from」已不存在")
            val to = updated.name
            if (to == from) return persistLocked(updated, previous = current)
            // 名字会变成文件名：校验必须在落盘之前做，不能让 `../` 这类名字逃出配方目录。
            if (!ToolRecipeConstants.TOOL_NAME_PATTERN.matches(to)) {
                return SaveResult.Failed(
                    "INVALID_RECIPE_NAME",
                    "配方名必须是 3 到 40 位小写字母/数字/下划线，且以字母开头；收到「$to」",
                )
            }
            if (to in ToolRecipeReservedNames.RESERVED) {
                return SaveResult.Failed("RESERVED_RECIPE_NAME", "配方名「$to」与内置能力冲突")
            }
            // 大小写不敏感地查重，否则模型侧会看到两个只差大小写的工具。
            if (all.keys.any { it != from && it.equals(to, ignoreCase = true) }) {
                return SaveResult.Failed("RECIPE_NAME_TAKEN", "已有同名配方「$to」")
            }
            val renamed = when (val written = persistLocked(updated, previous = current)) {
                is SaveResult.Failed -> return written
                is SaveResult.Ok -> written.recipe
            }
            fileFor(from).takeIf { it.exists() }?.delete()
            cache = null
            var staleReferences = 0
            all.values
                .filter { it.name != from && from in it.composedRecipes }
                .forEach { dependent ->
                    val rewired = dependent.copy(
                        steps = dependent.steps.map { step ->
                            if (step is ToolRecipeStep.Compose && step.recipe == from) {
                                step.copy(recipe = to)
                            } else {
                                step
                            }
                        },
                    )
                    if (persistLocked(rewired, previous = dependent) is SaveResult.Failed) {
                        staleReferences++
                    }
                }
            return if (staleReferences == 0) {
                SaveResult.Ok(renamed)
            } else {
                SaveResult.Failed(
                    "RECIPE_REFERENCE_STALE",
                    "已改名为「$to」，但有 $staleReferences 份引用它的配方没能一起更新",
                )
            }
        }
    }

    /**
     * 落盘一份配方，调用方必须已持有写锁。
     *
     * @param previous 同一次改动前的旧版本：版本号与创建时间从它继承，改名时传旧名字那份。
     * @param enforceLimit 新增条目是否受 [ToolRecipeConstants.MAX_STORED_RECIPES] 限制。
     *                     改名会先写新文件再删旧文件，中途条目数会短暂 +1，不能按上限拦下来。
     */
    private fun persistLocked(
        recipe: ToolRecipe,
        previous: ToolRecipe?,
        enforceLimit: Boolean = true,
    ): SaveResult {
        if (enforceLimit && previous == null && loadLocked().size >= ToolRecipeConstants.MAX_STORED_RECIPES) {
            return SaveResult.Failed(
                "RECIPE_LIMIT_REACHED",
                "设备上已有 ${ToolRecipeConstants.MAX_STORED_RECIPES} 份配方；" +
                    "先停用或删除用不上的，再创建新的，不要反复尝试",
            )
        }
        val toPersist = recipe.copy(
            version = (previous?.version ?: 0) + 1,
            createdAtMillis = previous?.createdAtMillis ?: recipe.createdAtMillis,
            updatedAtMillis = System.currentTimeMillis(),
        )
        val file = fileFor(toPersist.name)
        val payload = serialize(toPersist)
        val written = runCatching {
            recipesDir.mkdirs()
            val temp = File(recipesDir, "${toPersist.name}.json.tmp")
            temp.writeText(payload.toString())
            if (file.exists()) file.delete()
            temp.renameTo(file)
        }.isSuccess && file.exists()
        if (!written) {
            return SaveResult.Failed("RECIPE_SAVE_FAILED", "配方写入磁盘失败：${toPersist.name}")
        }
        cache = null
        return SaveResult.Ok(toPersist)
    }

    /** 删除配方。 */
    fun delete(name: String): Boolean {
        lock.write {
            val file = fileFor(name)
            val removed = !file.exists() || file.delete()
            cache = null
            return removed
        }
    }

    /** 启用或停用配方。 */
    fun setEnabled(name: String, enabled: Boolean): ToolRecipe? {
        val current = find(name) ?: return null
        return when (val result = save(current.copy(enabled = enabled))) {
            is SaveResult.Ok -> result.recipe
            is SaveResult.Failed -> null
        }
    }

    /**
     * 把已启用的配方投影成模型可见的 function schema。
     *
     * 无 Root 时，需要 Root 的配方不会出现在列表里——沿用 Eta 现有的
     * 「无 Root 就对模型隐藏」范式，避免模型必然失败地调用。
     */
    fun schemas(rootAvailable: Boolean): List<JSONObject> {
        val allRecipes = all()
        val rootNeeding = rootDependentRecipes(allRecipes)
        return allRecipes
            .filter { it.enabled }
            .filter { rootAvailable || it.name !in rootNeeding }
            // 工具列表有上限：优先保留最近更新的配方，避免老配方把新工具挤掉。
            .sortedByDescending { it.updatedAtMillis }
            .take(ToolRecipeConstants.MAX_VISIBLE_RECIPES)
            .map { it.toSchema() }
    }

    /**
     * 计算「组合链上需要 Root」的配方名集合。
     *
     * 若 A 组合了 B，而 B 里有 root_shell，则 A 也需要 Root。
     * 组合图可能有环，按可达性做带访问集的遍历。
     */
    fun rootDependentRecipes(recipes: List<ToolRecipe>): Set<String> {
        val byName = recipes.associateBy { it.name }
        val directRoot = recipes.filter { it.requiresRoot }.map { it.name }.toSet()
        // 自身含 root_shell 的配方也在集合内；再沿组合链把上游一并纳入。
        val result = directRoot.toMutableSet()
        recipes.forEach { recipe ->
            val visited = mutableSetOf<String>()
            val stack = ArrayDeque<String>()
            stack.addAll(recipe.composedRecipes)
            while (stack.isNotEmpty()) {
                val current = stack.removeLast()
                if (!visited.add(current)) continue
                if (current in directRoot) {
                    result += recipe.name
                    break
                }
                byName[current]?.composedRecipes?.forEach { stack.addLast(it) }
            }
        }
        return result
    }

    /**
     * 校验组合链是否成环。
     *
     * @return 成环时返回涉及的配方名链，否则返回 null。
     */
    fun detectCycle(recipes: List<ToolRecipe>): List<String>? {
        val byName = recipes.associateBy { it.name }
        recipes.forEach { recipe ->
            val path = mutableListOf<String>()
            val onPath = mutableSetOf<String>()
            if (findCycle(recipe.name, byName, path, onPath)) return path.toList()
        }
        return null
    }

    private fun findCycle(
        name: String,
        byName: Map<String, ToolRecipe>,
        path: MutableList<String>,
        onPath: MutableSet<String>,
    ): Boolean {
        if (onPath.contains(name)) return true
        val recipe = byName[name] ?: return false
        onPath.add(name)
        path.add(name)
        recipe.composedRecipes.forEach { composed ->
            if (findCycle(composed, byName, path, onPath)) return true
        }
        path.removeAt(path.size - 1)
        onPath.remove(name)
        return false
    }

    private fun loadLocked(): Map<String, ToolRecipe> {
        cache?.let { return it }
        val loaded = linkedMapOf<String, ToolRecipe>()
        val dir = recipesDir
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    val parsed = runCatching { parseStored(file) }.getOrNull()
                    if (parsed != null) loaded[parsed.name] = parsed
                }
        }
        cache = loaded
        return loaded
    }

    private fun parseStored(file: File): ToolRecipe? {
        val raw = JSONObject(file.readText())
        val name = raw.optString("name").trim()
        if (!ToolRecipeConstants.TOOL_NAME_PATTERN.matches(name)) return null
        val steps = raw.optJSONArray("steps") ?: return null
        val parsedSteps = mutableListOf<ToolRecipeStep>()
        for (index in 0 until steps.length()) {
            val step = parsedStep(steps.optJSONObject(index) ?: continue) ?: return null
            parsedSteps += step
        }
        return ToolRecipe(
            name = name,
            description = raw.optString("description"),
            parameters = raw.optJSONObject("parameters")
                ?: JSONObject().put("type", "object").put("properties", JSONObject()),
            steps = parsedSteps,
            enabled = raw.optBoolean("enabled", true),
            version = raw.optInt("version", 1),
            createdAtMillis = raw.optLong("created_at_ms", 0L),
            updatedAtMillis = raw.optLong("updated_at_ms", 0L),
        )
    }

    /** 反序列化单个步骤；结构不认识的返回 null，让整份配方作废而不是半可用。 */
    private fun parsedStep(raw: JSONObject): ToolRecipeStep? = when (raw.optString("op")) {
        ToolRecipeStep.OP_HTTP_REQUEST -> ToolRecipeStep.HttpRequest(
            url = raw.optString("url"),
            method = raw.optString("method", "GET"),
            headers = raw.optJSONObject("headers")?.let { headers ->
                headers.keys().asSequence().associateWith { headers.optString(it) }
            } ?: emptyMap(),
            body = raw.optString("body").takeIf { raw.has("body") },
            bodyIsJson = raw.optBoolean("body_is_json", false),
        )

        ToolRecipeStep.OP_ROOT_SHELL -> ToolRecipeStep.RootShell(
            command = raw.optString("command"),
            timeoutMillis = raw.optLong("timeout_ms", 15_000L),
        )

        ToolRecipeStep.OP_SETTINGS_READ -> ToolRecipeStep.SettingsRead(
            namespace = raw.optString("namespace", "system"),
            key = raw.optString("key"),
        )

        ToolRecipeStep.OP_SETTINGS_WRITE -> ToolRecipeStep.SettingsWrite(
            namespace = raw.optString("namespace", "system"),
            key = raw.optString("key"),
            value = raw.optString("value"),
        )

        ToolRecipeStep.OP_FILE_READ -> ToolRecipeStep.FileRead(
            path = raw.optString("path"),
            maxBytes = raw.optInt("max_bytes", 64 * 1024),
        )

        ToolRecipeStep.OP_FILE_WRITE -> ToolRecipeStep.FileWrite(
            path = raw.optString("path"),
            content = raw.optString("content"),
            append = raw.optBoolean("append", false),
        )

        ToolRecipeStep.OP_CONTENT_QUERY -> ToolRecipeStep.ContentQuery(
            uri = raw.optString("uri"),
            projection = raw.optString("projection").takeIf { raw.has("projection") },
            selection = raw.optString("selection").takeIf { raw.has("selection") },
            limit = raw.optInt("limit", 50),
        )

        ToolRecipeStep.OP_COMPOSE -> ToolRecipeStep.Compose(
            recipe = raw.optString("recipe"),
            passOutput = raw.optBoolean("pass_output", true),
        )

        ToolRecipeStep.OP_LITERAL -> ToolRecipeStep.Literal(raw.optString("text"))
        else -> null
    }

    private fun serialize(recipe: ToolRecipe): JSONObject {
        val steps = JSONArray()
        recipe.steps.forEach { step ->
            steps.put(
                when (step) {
                    is ToolRecipeStep.HttpRequest -> {
                        val headers = JSONObject()
                        step.headers.forEach { (key, value) -> headers.put(key, value) }
                        JSONObject()
                            .put("op", step.op)
                            .put("url", step.url)
                            .put("method", step.method)
                            .put("headers", headers)
                            .apply { step.body?.let { put("body", it) } }
                            .put("body_is_json", step.bodyIsJson)
                    }

                    is ToolRecipeStep.RootShell -> JSONObject()
                        .put("op", step.op)
                        .put("command", step.command)
                        .put("timeout_ms", step.timeoutMillis)

                    is ToolRecipeStep.SettingsRead -> JSONObject()
                        .put("op", step.op)
                        .put("namespace", step.namespace)
                        .put("key", step.key)

                    is ToolRecipeStep.SettingsWrite -> JSONObject()
                        .put("op", step.op)
                        .put("namespace", step.namespace)
                        .put("key", step.key)
                        .put("value", step.value)

                    is ToolRecipeStep.FileRead -> JSONObject()
                        .put("op", step.op)
                        .put("path", step.path)
                        .put("max_bytes", step.maxBytes)

                    is ToolRecipeStep.FileWrite -> JSONObject()
                        .put("op", step.op)
                        .put("path", step.path)
                        .put("content", step.content)
                        .put("append", step.append)

                    is ToolRecipeStep.ContentQuery -> JSONObject()
                        .put("op", step.op)
                        .put("uri", step.uri)
                        .apply { step.projection?.let { put("projection", it) } }
                        .apply { step.selection?.let { put("selection", it) } }
                        .put("limit", step.limit)

                    is ToolRecipeStep.Compose -> JSONObject()
                        .put("op", step.op)
                        .put("recipe", step.recipe)
                        .put("pass_output", step.passOutput)

                    is ToolRecipeStep.Literal -> JSONObject()
                        .put("op", step.op)
                        .put("text", step.text)
                },
            )
        }
        return JSONObject()
            .put("name", recipe.name)
            .put("description", recipe.description)
            .put("parameters", recipe.parameters)
            .put("steps", steps)
            .put("enabled", recipe.enabled)
            .put("version", recipe.version)
            .put("created_at_ms", recipe.createdAtMillis)
            .put("updated_at_ms", recipe.updatedAtMillis)
    }

    private fun fileFor(name: String): File = File(recipesDir, "$name.json")

    companion object {
        @Volatile
        private var instance: AgentToolRecipeStore? = null

        /**
         * 进程级唯一实例。
         *
         * 管理页与 agent 运行期必须共用同一份缓存：否则用户在设置页停用/删除配方后，
         * agent 那一侧仍拿着自己的旧缓存，模型会继续看到已被用户收回的工具。
         *
         * @param context 任意 Context，内部只用 applicationContext 取 filesDir。
         */
        fun get(context: android.content.Context): AgentToolRecipeStore =
            instance ?: synchronized(this) {
                instance ?: AgentToolRecipeStore(
                    File(context.applicationContext.filesDir, ToolRecipeConstants.DIRECTORY_NAME),
                ).also { instance = it }
            }

        /**
         * 外部改写了配方目录（备份恢复）后的缓存失效。
         *
         * 取不到实例说明进程还没读过配方，不在这里创建实例——下次 [get] 自然会读盘。
         */
        fun invalidateCache() {
            synchronized(this) { instance }?.invalidate()
        }
    }

    /** 保存结果。 */
    sealed interface SaveResult {
        data class Ok(val recipe: ToolRecipe) : SaveResult
        data class Failed(val code: String, val message: String) : SaveResult
    }
}
