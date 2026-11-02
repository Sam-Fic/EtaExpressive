package io.github.mangi.eta.agent.tool

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 自进化配方的纯逻辑测试。
 *
 * 覆盖三件最容易出错的事：
 * 1. 占位符插值的转义（防注入）
 * 2. 配方校验（名字、步骤白名单、参数声明）
 * 3. 组合链的 Root 推导与环检测
 */
@RunWith(RobolectricTestRunner::class)
class ToolRecipeTest {

    @Test
    fun placeholdersAreCollected() {
        val names = ToolRecipeTemplate.placeholders("https://x/{{city}}/y/{{ unit }}")
        assertEquals(setOf("city", "unit"), names)
    }

    @Test
    fun urlInterpolationPercentEncodesValues() {
        val result = ToolRecipeTemplate.interpolateUrl(
            "https://api.test/?q={{query}}",
            mapOf("query" to "a b&c=d"),
        )
        // & 与 = 必须被编码，否则会破坏 URL 结构。
        assertFalse("URL 里不该出现裸 & 或 =", result.contains("&c") || result.contains("=d"))
        assertTrue(result.startsWith("https://api.test/?q="))
    }

    @Test
    fun urlInterpolationKeepsMissingPlaceholderVisible() {
        val result = ToolRecipeTemplate.interpolateUrl("https://api.test/{{missing}}", emptyMap())
        // 缺失时保留原样，让执行器能报「缺参数」而不是拼出坏 URL。
        assertEquals("https://api.test/{{missing}}", result)
    }

    @Test
    fun shellInterpolationQuotesAndEscapes() {
        val result = ToolRecipeTemplate.interpolateShell(
            "echo {{text}}",
            mapOf("text" to "it's; rm -rf /"),
        )
        // 单引号包裹后，内部单引号按 POSIX 惯例转义，命令无法被拆开。
        assertTrue(result.startsWith("echo '"))
        assertTrue(result.endsWith("'"))
        assertTrue("内部单引号必须转义", result.contains("'\\''"))
    }

    @Test
    fun shellQuoteWrapsPlainValue() {
        assertEquals("'abc'", ToolRecipeTemplate.shellQuote("abc"))
    }

    @Test
    fun parserAcceptsMinimalRecipe() {
        val raw = JSONObject()
            .put("name", "fetch_weather")
            .put("description", "查询天气")
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("city", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("city")),
            )
            .put(
                "steps",
                org.json.JSONArray().put(
                    JSONObject().put("op", "http_request").put("url", "https://wttr.in/{{city}}"),
                ),
            )

        val parsed = ToolRecipeParser.parse(raw)
        assertTrue(parsed is ToolRecipeParser.ParseResult.Ok)
        val recipe = (parsed as ToolRecipeParser.ParseResult.Ok<ToolRecipe>).value
        assertEquals("fetch_weather", recipe.name)
        assertEquals("recipe_fetch_weather", recipe.modelToolName)
        assertFalse(recipe.requiresRoot)
    }

    @Test
    fun parserRejectsBadName() {
        val raw = JSONObject()
            .put("name", "Bad-Name")
            .put("description", "x")
            .put("steps", org.json.JSONArray().put(JSONObject().put("op", "literal").put("text", "a")))

        val parsed = ToolRecipeParser.parse(raw)
        assertTrue(parsed is ToolRecipeParser.ParseResult.Invalid)
        assertEquals("INVALID_RECIPE_NAME", (parsed as ToolRecipeParser.ParseResult.Invalid).code)
    }

    @Test
    fun parserRejectsUnknownStepOp() {
        val raw = JSONObject()
            .put("name", "danger_tool")
            .put("description", "x")
            .put("steps", org.json.JSONArray().put(JSONObject().put("op", "eval_code").put("code", "rm -rf /")))

        val parsed = ToolRecipeParser.parse(raw)
        assertTrue(parsed is ToolRecipeParser.ParseResult.Invalid)
        assertEquals("UNKNOWN_STEP_OP", (parsed as ToolRecipeParser.ParseResult.Invalid).code)
    }

    @Test
    fun parserRejectsRequiredParameterNotDeclared() {
        val raw = JSONObject()
            .put("name", "needs_arg")
            .put("description", "x")
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("other", JSONObject().put("type", "string")))
                    .put("required", org.json.JSONArray().put("a")),
            )
            .put(
                "steps",
                org.json.JSONArray().put(JSONObject().put("op", "http_request").put("url", "https://x/{{a}}")),
            )

        val parsed = ToolRecipeParser.parse(raw)
        assertTrue(parsed is ToolRecipeParser.ParseResult.Invalid)
        assertEquals("INVALID_RECIPE", (parsed as ToolRecipeParser.ParseResult.Invalid).code)
    }

    @Test
    fun parserRejectsUnsupportedParameterType() {
        val raw = JSONObject()
            .put("name", "bad_type")
            .put("description", "x")
            .put(
                "parameters",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject().put("a", JSONObject().put("type", "function"))),
            )
            .put("steps", org.json.JSONArray().put(JSONObject().put("op", "literal").put("text", "x")))

        val parsed = ToolRecipeParser.parse(raw)
        assertTrue(parsed is ToolRecipeParser.ParseResult.Invalid)
        assertEquals("INVALID_RECIPE", (parsed as ToolRecipeParser.ParseResult.Invalid).code)
    }

    @Test
    fun parserRejectsSelfComposition() {
        val raw = JSONObject()
            .put("name", "loop_tool")
            .put("description", "x")
            .put(
                "steps",
                org.json.JSONArray().put(JSONObject().put("op", "compose").put("recipe", "loop_tool")),
            )

        val parsed = ToolRecipeParser.parse(raw, existingNames = setOf("loop_tool"))
        assertTrue(parsed is ToolRecipeParser.ParseResult.Invalid)
        assertEquals("RECIPE_CYCLE", (parsed as ToolRecipeParser.ParseResult.Invalid).code)
    }

    @Test
    fun rootRequirementPropagatesThroughComposition() {
        val base = recipe(
            name = "base_root",
            steps = listOf(ToolRecipeStep.RootShell("id")),
        )
        val middle = recipe(
            name = "middle",
            steps = listOf(ToolRecipeStep.Compose("base_root")),
        )
        val top = recipe(
            name = "top",
            steps = listOf(ToolRecipeStep.Compose("middle")),
        )

        val rootNeeding = AgentToolRecipeStore(tempDir()).rootDependentRecipes(listOf(base, middle, top))
        assertTrue("base 需要 Root", "base_root" in rootNeeding)
        assertTrue("组合了 base 的 middle 也需要 Root", "middle" in rootNeeding)
        assertTrue("间接组合的 top 同样需要 Root", "top" in rootNeeding)
    }

    @Test
    fun cycleDetectionFindsLoop() {
        val a = recipe("a", listOf(ToolRecipeStep.Compose("b")))
        val b = recipe("b", listOf(ToolRecipeStep.Compose("a")))

        val cycle = AgentToolRecipeStore(tempDir()).detectCycle(listOf(a, b))
        assertNotNull("a → b → a 应当被检出", cycle)
    }

    @Test
    fun cycleDetectionPassesForAcyclicGraph() {
        val a = recipe("a", listOf(ToolRecipeStep.Compose("b")))
        val b = recipe("b", listOf(ToolRecipeStep.Compose("c")))
        val c = recipe("c", listOf(ToolRecipeStep.Literal("x")))

        assertEquals(null, AgentToolRecipeStore(tempDir()).detectCycle(listOf(a, b, c)))
    }

    @Test
    fun storePersistsAndReloadsRecipe() {
        val dir = tempDir()
        val store = AgentToolRecipeStore(dir)
        val recipe = recipe("saved_tool", listOf(ToolRecipeStep.Literal("hello")))

        val saved = store.save(recipe)
        assertTrue(saved is AgentToolRecipeStore.SaveResult.Ok)

        // 新实例模拟进程重启，配方应当仍在。
        val reopened = AgentToolRecipeStore(dir)
        val loaded = reopened.find("saved_tool")
        assertNotNull("配方应当落盘并在重开后仍在", loaded)
        assertEquals("hello", (loaded!!.steps.first() as ToolRecipeStep.Literal).text)
    }

    @Test
    fun renameMovesFileAndRewiresComposeReferences() {
        val dir = tempDir()
        val store = AgentToolRecipeStore(dir)
        val base = recipe("base_tool", listOf(ToolRecipeStep.Literal("hello")))
        store.save(base)
        store.save(recipe("top_tool", listOf(ToolRecipeStep.Compose("base_tool"))))

        val renamed = store.rename("base_tool", base.copy(name = "renamed_tool"))
        assertTrue("改名应当成功：$renamed", renamed is AgentToolRecipeStore.SaveResult.Ok)

        // 旧文件必须消失，新文件必须落盘——名字就是文件名。
        assertEquals(null, store.find("base_tool"))
        val after = AgentToolRecipeStore(dir).find("renamed_tool")
        assertNotNull("改名后应当能用新名字读回来", after)
        // 版本号连续递增，改名不该把历史清零。
        assertEquals(2, after!!.version)

        // 引用它的配方必须被一起改写，否则组合链在改名那一刻就断了。
        val dependent = store.find("top_tool")
        assertNotNull(dependent)
        assertEquals(
            "renamed_tool",
            (dependent!!.steps.first() as ToolRecipeStep.Compose).recipe,
        )
    }

    @Test
    fun renameRejectsTakenName() {
        val store = AgentToolRecipeStore(tempDir())
        store.save(recipe("first_tool", listOf(ToolRecipeStep.Literal("x"))))
        store.save(recipe("second_tool", listOf(ToolRecipeStep.Literal("y"))))

        val result = store.rename("first_tool", recipe("second_tool", listOf(ToolRecipeStep.Literal("x"))))
        assertTrue(result is AgentToolRecipeStore.SaveResult.Failed)
        assertEquals("RECIPE_NAME_TAKEN", (result as AgentToolRecipeStore.SaveResult.Failed).code)
        // 失败不该留下半成品：原名字还在。
        assertNotNull(store.find("first_tool"))
    }

    @Test
    fun renameRejectsNameThatIsNotAPlainIdentifier() {
        val store = AgentToolRecipeStore(tempDir())
        val base = recipe("base_tool", listOf(ToolRecipeStep.Literal("x")))
        store.save(base)

        // 名字会直接拼成文件名，`../` 必须在落盘前被拦下来。
        val result = store.rename("base_tool", base.copy(name = "../escaped"))
        assertTrue(result is AgentToolRecipeStore.SaveResult.Failed)
        assertEquals("INVALID_RECIPE_NAME", (result as AgentToolRecipeStore.SaveResult.Failed).code)
        assertNotNull(store.find("base_tool"))
    }

    @Test
    fun schemasHideRootRecipesWhenRootUnavailable() {
        val dir = tempDir()
        val store = AgentToolRecipeStore(dir)
        store.save(recipe("plain_tool", listOf(ToolRecipeStep.Literal("x"))))
        store.save(recipe("root_tool", listOf(ToolRecipeStep.RootShell("id"))))

        val withRoot = store.schemas(rootAvailable = true).map { it.getJSONObject("function").getString("name") }
        val withoutRoot = store.schemas(rootAvailable = false).map { it.getJSONObject("function").getString("name") }

        assertTrue("recipe_plain_tool" in withRoot)
        assertTrue("recipe_root_tool" in withRoot)
        assertTrue("recipe_plain_tool" in withoutRoot)
        assertFalse("无 Root 时需要 Root 的配方应隐藏", "recipe_root_tool" in withoutRoot)
    }

    @Test
    fun disabledRecipeIsNotExposed() {
        val store = AgentToolRecipeStore(tempDir())
        store.save(recipe("off_tool", listOf(ToolRecipeStep.Literal("x"))).copy(enabled = false))

        val names = store.schemas(rootAvailable = true).map { it.getJSONObject("function").getString("name") }
        assertFalse("recipe_off_tool" in names)
    }

    @Test
    fun argumentsFlattenKeepsOnlyPresentKeys() {
        val args = JSONObject()
            .put("city", "北京")
            .put("count", 3)
            .put("flag", true)

        val flat = ToolRecipeArguments.flatten(args)
        assertEquals("北京", flat["city"])
        assertEquals("3", flat["count"])
        assertEquals("true", flat["flag"])
        assertFalse(flat.containsKey("missing"))
    }

    private fun recipe(name: String, steps: List<ToolRecipeStep>): ToolRecipe = ToolRecipe(
        name = name,
        description = "测试配方",
        parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
        steps = steps,
    )

    @Test
    fun storeIsProcessWideSoUiAndAgentSeeTheSameRecipes() {
        // 管理页停用/删除配方后，agent 侧必须立刻看到：两个实例会各持一份缓存，
        // 模型就会继续调用已被用户收回的工具。
        val context = RuntimeEnvironment.getApplication()
        val first = AgentToolRecipeStore.get(context)
        val second = AgentToolRecipeStore.get(context)

        assertTrue("必须返回同一实例", first === second)
        assertEquals(
            "管理页与 agent 必须落在同一目录",
            ToolRecipePaths.directory(context).absolutePath,
            File(context.filesDir, ToolRecipeConstants.DIRECTORY_NAME).absolutePath,
        )
    }

    @Test
    fun shareRoundTripPreservesSteps() {
        val original = recipe(
            name = "shared_tool",
            steps = listOf(
                ToolRecipeStep.HttpRequest(
                    url = "https://api.test/{{q}}",
                    method = "POST",
                    headers = mapOf("X-Test" to "1"),
                    body = "{\"q\":\"{{q}}\"}",
                    bodyIsJson = true,
                ),
                ToolRecipeStep.Literal("done"),
            ),
        ).copy(description = "分享用配方", enabled = true, version = 9)

        val payload = ToolRecipeShareFormat.encode(listOf(original))
        val decoded = ToolRecipeShareFormat.decode(payload)

        assertEquals(1, decoded.size)
        val restored = decoded.single()
        assertEquals("shared_tool", restored.name)
        assertEquals("分享用配方", restored.description)
        // 分享的是「配方本身」，不带本机状态：导入一律停用，版本与时间戳重置。
        assertFalse("导入的配方必须默认停用", restored.enabled)
        assertEquals(1, restored.version)
        val http = restored.steps.first() as ToolRecipeStep.HttpRequest
        assertEquals("POST", http.method)
        assertEquals("https://api.test/{{q}}", http.url)
        assertEquals("{\"q\":\"{{q}}\"}", http.body)
        assertTrue(http.bodyIsJson)
        assertEquals("1", http.headers["X-Test"])
    }

    @Test
    fun shareRejectsUnknownFormatAndVersion() {
        val wrongFormat = JSONObject().put("format", "other").put("schemaVersion", 1)
            .put("recipes", org.json.JSONArray())
            .toString()
        val wrongVersion = JSONObject().put("format", ToolRecipeShareFormat.FORMAT)
            .put("schemaVersion", 99)
            .put("recipes", org.json.JSONArray())
            .toString()

        assertThrows(ToolRecipeShareException::class.java) { ToolRecipeShareFormat.decode(wrongFormat) }
        assertThrows(ToolRecipeShareException::class.java) { ToolRecipeShareFormat.decode(wrongVersion) }
    }

    @Test
    fun shareRejectsPackageWithInvalidStep() {
        // 分享文件是不可信输入：步骤 op 不在白名单里必须整份拒绝，不能半可用。
        val payload = JSONObject()
            .put("format", ToolRecipeShareFormat.FORMAT)
            .put("schemaVersion", ToolRecipeShareFormat.SCHEMA_VERSION)
            .put(
                "recipes",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("name", "bad_tool")
                        .put("description", "坏配方")
                        .put("steps", org.json.JSONArray().put(JSONObject().put("op", "run_arbitrary_code"))),
                ),
            )
            .toString()

        val failure = runCatching { ToolRecipeShareFormat.decode(payload) }.exceptionOrNull()
        assertTrue("坏步骤必须被拒绝", failure is ToolRecipeShareException)
    }

    @Test
    fun shareDetectsCompositionTargetOutsideThePackage() {
        val inner = recipe("inner_tool", listOf(ToolRecipeStep.Literal("x")))
        val outer = recipe("outer_tool", listOf(ToolRecipeStep.Compose("inner_tool")))

        // 两份一起导出：组合目标在包内，无缺失。
        assertTrue(ToolRecipeShareFormat.missingCompositionTargets(listOf(inner, outer)).isEmpty())
        // 只导出外层：组合目标缺失，导入到其它设备会失败。
        assertEquals(setOf("inner_tool"), ToolRecipeShareFormat.missingCompositionTargets(listOf(outer)))
    }

    @Test
    fun importedRecipesAreImmediatelyUsableAfterSave() {
        val dir = tempDir()
        val store = AgentToolRecipeStore(dir)
        val payload = ToolRecipeShareFormat.encode(
            listOf(recipe("migrated_tool", listOf(ToolRecipeStep.Literal("hi")))),
        )

        val imported = ToolRecipeShareFormat.decode(payload)
        imported.forEach { recipe ->
            assertTrue(store.save(recipe) is AgentToolRecipeStore.SaveResult.Ok)
        }

        val reloaded = AgentToolRecipeStore(dir).find("migrated_tool")
        assertNotNull("导入的配方应落盘并可被 agent 侧读取", reloaded)
        assertFalse("导入默认停用，需用户确认后才交给模型", reloaded!!.enabled)
    }

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "eta-recipe-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }
}
