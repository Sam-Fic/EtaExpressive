package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.model.AgentToolGroupCatalog
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 缓存优先档下配方经 `tool_call` 转发。
 *
 * 配方不属于任何延迟组（[io.github.mangi.eta.agent.model.AgentToolGroups.groupOfTool] 对它返回 null），
 * 所以转发入口必须单独认它：按配方**真实 schema** 校验参数，再交回 [AgentLocalTools.execute]，
 * 让 extensions 开关、Root 闸门、危险操作确认与敏感判定全部按内层真实名重跑。
 * 壳名字一旦漏进这些闸门，`tool_call` 就成了脱敏与确认的旁路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalToolsRecipeCallTest {
    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    @Before fun resetPreferences() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        // 执行侧的组闸门与单工具禁用集都读 Prefs；同 sandbox 的其它测试类可能留下旧值。
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        Prefs.initLocal(context)
    }

    /**
     * 落一份最小可执行配方：literal 步骤无副作用，参数只有一个必填 path。
     *
     * 必须经 [AgentToolRecipeStore.get] 写入：Store 是进程级单例，rootDir 在本类第一个
     * 用例创建实例时定型；直接往当前方法的临时 filesDir 写文件，Store 永远看不到
     * （Robolectric 每个测试方法换一个临时目录），forward 会误报 RECIPE_NOT_FOUND。
     */
    private fun writeRecipe(name: String) {
        val store = AgentToolRecipeStore.get(RuntimeEnvironment.getApplication())
        val saved = store.save(
            ToolRecipe(
                name = name,
                description = "测试配方",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "path",
                            JSONObject().put("type", "string").put("description", "路径"),
                        ),
                    )
                    .put("required", JSONArray().put("path")),
                steps = listOf(ToolRecipeStep.Literal("ok")),
                enabled = true,
            ),
        )
        check(saved is AgentToolRecipeStore.SaveResult.Ok) { "配方写入失败：$saved" }
    }

    private fun tools(
        admission: (String) -> ToolExecutionDecision = { ToolExecutionDecision.Allow },
    ): AgentLocalTools = AgentLocalTools(
        context = RuntimeEnvironment.getApplication() as Context,
        logger = NoOpLogger,
        browserRunId = "recipe-call-run",
        groupToolsViaCall = true,
        beforeToolExecution = admission,
    )

    private fun forward(tools: AgentLocalTools, name: String, arguments: JSONObject): JSONObject =
        JSONObject(
            tools.execute(
                AgentModelClient.ToolCall(
                    "forward-1",
                    AgentToolGroupCatalog.CALL,
                    JSONObject().put("name", name).put("arguments", arguments).toString(),
                ),
            ).content,
        )

    @Test fun argumentsAreValidatedAgainstTheRealRecipeSchema() {
        writeRecipe("sample_recipe")
        val tools = tools()
        try {
            // 配方不在 tools 里，主校验器看不到它；转发入口必须自己按配方 schema 校验。
            val result = forward(tools, "recipe_sample_recipe", JSONObject())
            assertEquals("INVALID_ARGUMENT", result.getString("code"))
            assertTrue(
                "应原样转达校验器的措辞",
                result.getString("message").contains("缺少必填字段 path"),
            )
        } finally {
            tools.close()
        }
    }

    @Test fun admissionGateSeesTheInnerRecipeName() {
        writeRecipe("sample_recipe")
        val seen = mutableListOf<String>()
        val tools = tools(admission = { name ->
            seen += name
            if (name == "recipe_sample_recipe") {
                ToolExecutionDecision.Reject("TEST_ADMISSION_DENIED", "本次未执行")
            } else {
                ToolExecutionDecision.Allow
            }
        })
        try {
            val result = forward(tools, "recipe_sample_recipe", JSONObject().put("path", "/sdcard"))
            assertEquals("TEST_ADMISSION_DENIED", result.getString("code"))
            // 闸门对壳与内层各跑一遍；最后一次必须是真实配方名，否则准入判定被旁路。
            assertEquals("recipe_sample_recipe", seen.last())
            assertTrue("壳名字也过了一遍闸门", AgentToolGroupCatalog.CALL in seen)
        } finally {
            tools.close()
        }
    }

    @Test fun unknownRecipeTellsTheModelHowToRecover() {
        val tools = tools()
        try {
            val result = forward(tools, "recipe_does_not_exist", JSONObject())
            assertEquals("RECIPE_NOT_FOUND", result.getString("code"))
            assertTrue(result.getString("message").contains("eta_list_recipes"))
        } finally {
            tools.close()
        }
    }

    @Test fun theForwardingShellIsNotASensitivityBypass() {
        // 配方结果本来就不入持久会话；套上 tool_call 壳后仍必须判为敏感。
        val outer = JSONObject().put("name", "recipe_sample_recipe").put("arguments", JSONObject()).toString()
        assertTrue(AgentSensitiveToolPolicy.isSensitive(AgentToolGroupCatalog.CALL, outer))
        assertEquals(
            "recipe_sample_recipe",
            AgentSensitiveToolPolicy.resolveName(AgentToolGroupCatalog.CALL, outer),
        )
    }

    @Test fun authorReceiptCarriesParametersAndCallViaInSuffixMode() {
        val context = RuntimeEnvironment.getApplication()
        val store = AgentToolRecipeStore.get(context)
        val suffix = AgentSelfAuthorTools(store, rootAvailable = { false }, recipesViaCall = true)
        val legacy = AgentSelfAuthorTools(store, rootAvailable = { false }, recipesViaCall = false)
        val args = JSONObject()
            .put("action", "create")
            .put("name", "receipt_recipe")
            .put(
                "recipe",
                JSONObject()
                    .put("name", "receipt_recipe")
                    .put("description", "回执测试")
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put(
                                    "city",
                                    JSONObject().put("type", "string").put("description", "城市"),
                                ),
                            )
                            .put("required", JSONArray().put("city")),
                    )
                    .put("steps", JSONArray().put(JSONObject().put("op", "literal").put("text", "ok"))),
            )

        val viaCall = JSONObject(suffix.execute(AgentSelfAuthorTools.AUTHOR_TOOL, args)!!)
        assertTrue(viaCall.getBoolean("ok"))
        assertEquals(AgentToolGroupCatalog.CALL, viaCall.getString("call_via"))
        // 缓存优先档不下发配方 schema，回执是模型唯一能拿到参数说明的地方。
        assertTrue(viaCall.getJSONObject("parameters").getJSONObject("properties").has("city"))
        assertTrue(viaCall.getString("note").contains(AgentToolGroupCatalog.CALL))
        assertFalse("不该再宣称它会成为原生工具", viaCall.getString("note").contains("作为 recipe_"))

        val listed = JSONObject(suffix.execute(AgentSelfAuthorTools.LIST_TOOLS, JSONObject())!!)
        assertEquals(AgentToolGroupCatalog.CALL, listed.getString("call_via"))
        assertTrue(listed.getString("note").contains(AgentToolGroupCatalog.CALL))

        // 其余档位配方仍是原生工具，回执不该把它们说成经转发调用。
        val legacyList = JSONObject(legacy.execute(AgentSelfAuthorTools.LIST_TOOLS, JSONObject())!!)
        assertEquals("direct", legacyList.getString("call_via"))
        assertFalse(legacyList.getString("note").contains(AgentToolGroupCatalog.CALL))
    }
}