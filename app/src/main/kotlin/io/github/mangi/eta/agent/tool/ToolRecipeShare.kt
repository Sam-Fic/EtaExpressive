package io.github.mangi.eta.agent.tool

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配方的分享包格式与编解码。
 *
 * 分享是自进化成果离开设备的唯一途径，因此这里把它当成**可导入的不可信输入**处理：
 * - 编码只输出配方本身（name / description / parameters / steps），不含版本时间戳
 * - 解码复用 [ToolRecipeParser] 的同一套白名单校验，分享的配方不会绕过安全检查
 * - 组合目标必须已经存在于目标设备，否则整份分享包拒绝导入（避免导入一堆悬空引用）
 * - 名字冲突默认拒绝，让用户决定是否覆盖，而不是静默替换掉已有配方
 *
 * 包格式：
 * ```
 * {
 *   "format": "eta-tool-recipe",
 *   "schemaVersion": 1,
 *   "recipes": [ { ...recipe... }, ... ]
 * }
 * ```
 */
internal object ToolRecipeShareFormat {
    const val FORMAT = "eta-tool-recipe"
    const val SCHEMA_VERSION = 1

    /** 单份分享包最多携带的配方数，避免一个文件塞进整个配方库。 */
    const val MAX_RECIPES = 32

    /** 单个分享包的文本上限（约 512 KiB）：防止分享文件大到无法通过 Intent 传递。 */
    const val MAX_PAYLOAD_CHARS = 512 * 1024

    /** 分享文件的建议后缀，用于系统保存面板。 */
    const val FILE_SUFFIX = "eta-recipes.json"

    /** 单份配方的分享条目——只含可迁移部分，不含本机状态（enabled / version / 时间戳）。 */
    fun entryOf(recipe: ToolRecipe): JSONObject =
        JSONObject()
            .put("name", recipe.name)
            .put("description", recipe.description)
            .put("parameters", JSONObject(recipe.parameters.toString()))
            .put("steps", ToolRecipeExport.stepsOf(recipe))

    /**
     * 把若干配方编码成分享包文本。
     *
     * 组合目标不在分享列表里的，会在 [missingCompositionTargets] 里被报出来——
     * 调用方决定是提示用户补选，还是仍按单份导出（导入时才会失败）。
     */
    fun encode(recipes: List<ToolRecipe>): String {
        require(recipes.isNotEmpty()) { "没有可分享的配方" }
        require(recipes.size <= MAX_RECIPES) { "一次最多分享 $MAX_RECIPES 份配方" }
        val entries = JSONArray()
        recipes.forEach { entries.put(entryOf(it)) }
        val payload = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("recipes", entries)
            .toString()
        require(payload.length <= MAX_PAYLOAD_CHARS) { "分享内容超过 ${MAX_PAYLOAD_CHARS} 字符" }
        return payload
    }

    /** 分享包里引用了但自身不在包内的配方名（导入到别的设备时很可能缺失）。 */
    fun missingCompositionTargets(recipes: List<ToolRecipe>): Set<String> {
        val names = recipes.map { it.name }.toSet()
        return recipes.flatMap { it.composedRecipes }.filterNot { it in names }.toSet()
    }

    /**
     * 解析分享包，返回可导入的配方。
     *
     * 失败一律抛 [ToolRecipeShareException]，带可读原因；不返回半份结果。
     */
    fun decode(raw: String): List<ToolRecipe> {
        if (raw.length > MAX_PAYLOAD_CHARS) {
            throw ToolRecipeShareException("分享内容过大（超过 ${MAX_PAYLOAD_CHARS} 字符）")
        }
        val document = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw ToolRecipeShareException("分享内容不是合法 JSON")
        if (document.optString("format") != FORMAT) {
            throw ToolRecipeShareException("这不是 Eta 配方分享文件（format 不是 $FORMAT）")
        }
        val version = document.optInt("schemaVersion", 0)
        if (version != SCHEMA_VERSION) {
            throw ToolRecipeShareException("不支持的配方分享版本：$version")
        }
        val entries = document.optJSONArray("recipes")
            ?: throw ToolRecipeShareException("分享文件缺少 recipes 列表")
        if (entries.length() == 0) {
            throw ToolRecipeShareException("分享文件里没有配方")
        }
        if (entries.length() > MAX_RECIPES) {
            throw ToolRecipeShareException("分享文件包含过多配方（超过 $MAX_RECIPES 份）")
        }
        val now = System.currentTimeMillis()
        val parsed = mutableListOf<ToolRecipe>()
        val seen = mutableSetOf<String>()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index)
                ?: throw ToolRecipeShareException("第 ${index + 1} 份配方不是对象")
            when (val result = ToolRecipeParser.parse(entry, existingNames = seen, nowMillis = now)) {
                is ToolRecipeParser.ParseResult.Invalid ->
                    throw ToolRecipeShareException("第 ${index + 1} 份配方无效：${result.message}")

                is ToolRecipeParser.ParseResult.Ok -> {
                    // 分享的配方一律以「停用」导入：新工具先让用户看过再交给模型，避免陌生代码自动生效。
                    parsed += result.value.copy(enabled = false)
                    seen += result.value.name
                }
            }
        }
        return parsed
    }
}

/** 配方分享/导入的失败原因，消息可直接展示给用户。 */
internal class ToolRecipeShareException(message: String) : IllegalArgumentException(message)

/**
 * 配方的跨设备迁移。
 *
 * 与备份（整机快照）分开：备份走 [EtaBackupRepository] 的既有归档通道，
 * 这里只负责「把选中的配方搬出去 / 搬进来」，因此可以单独校验、单独确认。
 */
internal object ToolRecipeExport {

    /** 把配方列表序列化成步骤 JSON（与存储层同一份结构，避免两套序列化漂移）。 */
    fun stepsOf(recipe: ToolRecipe): JSONArray = JSONArray().also { array ->
        recipe.steps.forEach { array.put(stepJson(it)) }
    }

    private fun stepJson(step: ToolRecipeStep): JSONObject = when (step) {
        is ToolRecipeStep.HttpRequest -> JSONObject()
            .put("op", step.op)
            .put("url", step.url)
            .put("method", step.method)
            .put("headers", JSONObject(step.headers.toMap()))
            .apply { step.body?.let { put("body", it) } }
            .put("body_is_json", step.bodyIsJson)

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
    }
}

/** 配方目录的定位与大小统计，供设置页与备份界面复用同一口径。 */
internal object ToolRecipePaths {

    /** 配方目录：与 [AgentToolRecipeStore] 的落盘位置一致（`filesDir/tool_recipes`）。 */
    fun directory(context: android.content.Context): File =
        File(context.filesDir, ToolRecipeConstants.DIRECTORY_NAME)

    /**
     * 备份归档里的配方条目前缀。
     *
     * 配方**只能**靠 Eta 自己的备份离开设备：本应用 `android:allowBackup="false"`，
     * 系统级备份不会带走 `filesDir/tool_recipes`，分享包又只限单批 32 份。
     */
    const val ENTRY_PREFIX = "recipes/"

    /**
     * 可备份 / 可统计的配方文件。
     *
     * 只认「文件名符合 [ToolRecipeConstants.TOOL_NAME_PATTERN] 的 `.json`」：
     * 目录里可能有 Store 写坏留下的 `.tmp` 或用户手放的杂文件，这些不该进备份包。
     */
    fun files(context: android.content.Context): List<File> =
        directory(context).listFiles { file ->
            file.isFile && file.name.endsWith(".json") &&
                ToolRecipeConstants.TOOL_NAME_PATTERN.matches(file.name.removeSuffix(".json"))
        }?.sortedBy { it.name } ?: emptyList()

    /** 当前配方数量（含停用），不触发反序列化以外的额外开销。 */
    fun count(context: android.content.Context): Int = files(context).size

    /** 配方占用体积，供备份界面提示「备份会带走多少数据」。 */
    fun bytes(context: android.content.Context): Long = files(context).sumOf { it.length() }
}
