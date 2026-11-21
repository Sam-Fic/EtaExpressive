// SimpleRule 模板引擎的行为钉子：模板编译、标签变体、尾缀动作词、内置标签兜底。
// 全部为纯 JVM 用例：android.util.Log 在 JVM 侧抛 Stub 被 runCatching 吞掉，
// SimpleRuleRuntime.recognizeCurrent 不触任何 Android API。

package io.github.mangi.eta.agent.pickup

import io.github.mangi.eta.agent.pickup.rules.SimpleBrandRule
import io.github.mangi.eta.agent.pickup.rules.SimpleRuleCategory
import io.github.mangi.eta.agent.pickup.rules.SimpleRulePack
import io.github.mangi.eta.agent.pickup.rules.SimpleRuleRuntime
import io.github.mangi.eta.agent.pickup.rules.SimpleRuleSource
import io.github.mangi.eta.agent.pickup.rules.SimpleTemplateRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleRuleRuntimeTest {

    private fun packWith(vararg templates: String): SimpleRulePack = SimpleRulePack(
        name = "test-pack",
        brands = listOf(
            SimpleBrandRule(
                id = "test-brand",
                category = SimpleRuleCategory.EXPRESS,
                name = "快递",
                keywords = listOf("取件", "快递"),
                templates = templates.map { SimpleTemplateRule(template = it) },
            ),
        ),
    )

    private fun codes(text: String): List<String> =
        SimpleRuleRuntime.recognizeCurrent(text, SimpleRuleSource.TEXT).map { it.code }

    @Test
    fun `模板命中基础取件码`() {
        SimpleRuleRuntime.replace(packWith("取件码{{code:alnum:1-40}}"))
        assertEquals(listOf("7-5-5009"), codes("您的快递已到菜鸟驿站，取件码7-5-5009，请及时领取"))
    }

    @Test
    fun `取货码取件码标签变体互通`() {
        SimpleRuleRuntime.replace(packWith("取件码{{code:alnum:1-40}}"))
        assertEquals(listOf("A-2-7261"), codes("凭取货码A-2-7261前往丰巢柜取件"))
    }

    @Test
    fun `尾缀动作词被剪掉`() {
        SimpleRuleRuntime.replace(packWith("取件码{{code:alnum:1-40}}"))
        // 码值字符类排除汉字，捕获到「868642到」，由 trimTrailingActionWords 修剪。
        assertEquals(listOf("868642"), codes("凭取件码868642到美晨通讯第1组货架领取"))
    }

    @Test
    fun `location 占位符提取地点`() {
        SimpleRuleRuntime.replace(packWith("取件码{{code:alnum:1-40}}，位于{{location}}"))
        val match = SimpleRuleRuntime.recognizeCurrent(
            "取件码7-5-5009，位于菜鸟驿站3号柜",
            SimpleRuleSource.TEXT,
        ).single()
        assertEquals("7-5-5009", match.code)
        assertEquals("菜鸟驿站3号柜", match.location)
    }

    @Test
    fun `空规则包走内置标签兜底`() {
        SimpleRuleRuntime.replace(SimpleRulePack.empty())
        // 内置标签规则不依赖用户配置：出现「取件码」标签即抓后面紧邻的码值。
        val result = codes("取件码 868642 到驿站自提")
        assertTrue(result.isNotEmpty())
        assertEquals("868642", result.first())
    }

    @Test
    fun `同品牌同处重复命中被收敛`() {
        // 两条模板对同一段文本各命中一次：同品牌且起点几乎重合 → 只留一个。
        SimpleRuleRuntime.replace(
            packWith("取件码{{code:alnum:1-40}}", "取件码{{code:digits:1-40}}"),
        )
        assertTrue(codes("取件码868642到驿站").size == 1)
    }
}
