package io.github.mangi.eta.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 唤醒即排序的规则约束。
 *
 * 这些断言保护的是「可解释、不跳位、不被偶发一屏带偏、不在敏感页乱动」四条产品口径：
 * 信号按可信度分层且命中即停、无命中只相邻换一步、学习顺序不被本次信号写坏。
 */
class EtaScreenPresetRankingTest {

    private val fixedOrder = ScreenContextPreset.entries.toList()

    // ── 文本归一化 ────────────────────────────────────────────────

    @Test
    fun `normalize folds fullwidth digits and strips whitespace`() {
        // OCR 会把全角数字原样带出；不归一化就匹配不到半角关键词。
        assertEquals("取码a123", EtaScreenText.normalize("取码 Ａ１２３"))
        assertEquals("abc", EtaScreenText.normalize(" A B C "))
    }

    @Test
    fun `normalize is idempotent and keeps cjk`() {
        val once = EtaScreenText.normalize("取餐码 A123")
        assertEquals("取餐码a123", once)
        assertEquals(once, EtaScreenText.normalize(once))
    }

    // ── 上下文命中：关键词 ────────────────────────────────────────

    @Test
    fun `pickup keywords promote the pickup preset to the front`() {
        val ordered = EtaScreenPresetRanking.rank(
            learned = fixedOrder,
            screenText = "您的取餐码 A123 请在出餐口取餐",
            usage = emptyMap(),
            enabled = true,
        )
        assertEquals(ScreenContextPreset.PICKUP_CODE, ordered.first())
        // 其余保持既有相对顺序，不做整体重排。
        assertEquals(
            fixedOrder.filterNot { it == ScreenContextPreset.PICKUP_CODE },
            ordered.drop(1),
        )
    }

    @Test
    fun `single strong keyword is enough but single weak keyword is not`() {
        val strong = EtaScreenPresetHints.scores("您的取件码已到站")
        assertTrue((strong[ScreenContextPreset.PICKUP_CODE] ?: 0) >= EtaScreenPresetHints.HIT_MIN_SCORE)

        // 「外卖」只是弱词，单独一枚不达标：不能因为页面出现「外卖」就把取码提到首位。
        val weak = EtaScreenPresetHints.scores("外卖")
        assertTrue((weak[ScreenContextPreset.PICKUP_CODE] ?: 0) < EtaScreenPresetHints.HIT_MIN_SCORE)
    }

    @Test
    fun `fullwidth screen text still matches after normalization`() {
        // 这条钉住 E：不归一化时全角「Ａ」与半角关键词互不匹配。
        val scores = EtaScreenPresetHints.scores("取餐码　Ａ１２３")
        assertTrue((scores[ScreenContextPreset.PICKUP_CODE] ?: 0) >= EtaScreenPresetHints.HIT_MIN_SCORE)
    }

    @Test
    fun `calendar keywords promote the calendar preset`() {
        val ordered = EtaScreenPresetRanking.rank(
            learned = fixedOrder,
            screenText = "会议邀请：产品评审，开始时间 14:00，会议室 A",
            usage = emptyMap(),
            enabled = true,
        )
        assertEquals(ScreenContextPreset.ADD_CALENDAR, ordered.first())
    }

    @Test
    fun `no hit keeps the learned order untouched`() {
        val learned = listOf(
            ScreenContextPreset.ORGANIZE,
            ScreenContextPreset.TRANSLATE,
            ScreenContextPreset.ADD_CALENDAR,
            ScreenContextPreset.PICKUP_CODE,
        )
        val ordered = EtaScreenPresetRanking.rank(
            learned = learned,
            screenText = "今天天气不错",
            usage = mapOf(ScreenContextPreset.PICKUP_CODE to 1.0),
            enabled = true,
        )
        // 一次唤醒最多挪一个位置，且频次 1.0 达不到压倒性领先。
        assertEquals(learned, ordered)
    }

    // ── 翻译判据与界面语言（②） ─────────────────────────────────

    @Test
    fun `foreign text promotes translate in a chinese interface`() {
        val ordered = EtaScreenPresetRanking.rank(
            learned = fixedOrder,
            screenText = "Please review the attached document before the meeting tomorrow morning",
            usage = emptyMap(),
            enabled = true,
            interfaceIsChinese = true,
        )
        assertEquals(ScreenContextPreset.TRANSLATE, ordered.first())
    }

    @Test
    fun `latin text no longer promotes translate in a non chinese interface`() {
        // ② 的核心：英文界面下拉丁文本是用户自己的语言，不该被当成外文。
        val ordered = EtaScreenPresetRanking.rank(
            learned = fixedOrder,
            screenText = "Please review the attached document before the meeting tomorrow morning",
            usage = emptyMap(),
            enabled = true,
            interfaceIsChinese = false,
        )
        assertEquals(fixedOrder, ordered)
    }

    @Test
    fun `short latin code never promotes translate`() {
        val ordered = EtaScreenPresetRanking.rank(
            learned = fixedOrder,
            screenText = "单号 A1234",
            usage = emptyMap(),
            enabled = true,
            interfaceIsChinese = true,
        )
        assertEquals(fixedOrder, ordered)
    }

    // ── 前台包名先验（A） ────────────────────────────────────────

    @Test
    fun `package affinity wins without any screen text`() {
        // 包名判得出来时完全不需要 OCR：screenText 传 null 也能定序。
        val ordered = EtaScreenPresetRanking.rank(
            learned = fixedOrder,
            screenText = null,
            usage = emptyMap(),
            enabled = true,
            affinity = ScreenContextPreset.ADD_CALENDAR,
        )
        assertEquals(ScreenContextPreset.ADD_CALENDAR, ordered.first())
        assertEquals(
            fixedOrder.filterNot { it == ScreenContextPreset.ADD_CALENDAR },
            ordered.drop(1),
        )
    }

    @Test
    fun `package affinity outranks screen keywords`() {
        // 两层都命中时以包名为准：包名是更可靠的信号。
        val ordered = EtaScreenPresetRanking.rank(
            learned = fixedOrder,
            screenText = "取餐码 A123 出餐口",
            usage = emptyMap(),
            enabled = true,
            affinity = ScreenContextPreset.ORGANIZE,
        )
        assertEquals(ScreenContextPreset.ORGANIZE, ordered.first())
    }

    @Test
    fun `package affinity does not rewrite learned order`() {
        // 上下文信号只作用于本次，不写回学习顺序。
        assertEquals(
            fixedOrder,
            EtaScreenPresetRanking.learnedAfterWake(
                learned = fixedOrder,
                screenText = null,
                usage = mapOf(ScreenContextPreset.PICKUP_CODE to 5.0),
                enabled = true,
                affinity = ScreenContextPreset.PICKUP_CODE,
            ),
        )
    }

    @Test
    fun `package matching is prefix safe at dot boundaries`() {
        // me.ele 不应该匹到 me.electric 这类同前缀的无关包名。
        assertTrue(EtaScreenPresetAffinity.isSensitiveScreen("com.icbc.mobile", false))
        assertFalse(EtaScreenPresetAffinity.isSensitiveScreen("com.icbcx.fake", false))
    }

    // ── 敏感场景（F） ───────────────────────────────────────────

    @Test
    fun `payment and banking packages are sensitive`() {
        assertTrue(EtaScreenPresetAffinity.isSensitiveScreen("com.eg.android.AlipayGphone", false))
        assertTrue(EtaScreenPresetAffinity.isSensitiveScreen("com.cmbchina.ccd.pluto.cmbActivity", false))
        assertTrue(EtaScreenPresetAffinity.isSensitiveScreen("com.android.settings", false))
    }

    @Test
    fun `password field marks the screen sensitive even for a normal package`() {
        // 支付页常是宿主 App 里的 WebView：包名普通，只能靠密码框识别。
        assertTrue(EtaScreenPresetAffinity.isSensitiveScreen("com.tencent.mm", hasPasswordField = true))
        assertFalse(EtaScreenPresetAffinity.isSensitiveScreen("com.tencent.mm", hasPasswordField = false))
    }

    @Test
    fun `unknown or empty package is never sensitive`() {
        assertFalse(EtaScreenPresetAffinity.isSensitiveScreen(null, false))
        assertFalse(EtaScreenPresetAffinity.isSensitiveScreen("   ", false))
        assertFalse(EtaScreenPresetAffinity.isSensitiveScreen("com.example.app", false))
    }

    // ── 频次：收敛与直提（D） ───────────────────────────────────

    @Test
    fun `converge swaps at most one adjacent pair per wake`() {
        val usage = mapOf(
            ScreenContextPreset.PICKUP_CODE to 5.0,
            ScreenContextPreset.ORGANIZE to 4.0,
        )
        val converged = EtaScreenPresetOrdering.converge(fixedOrder, usage)
        assertEquals(fixedOrder.size, converged.size)
        assertEquals(fixedOrder.toSet(), converged.toSet())
        val moved = fixedOrder.indices.count { fixedOrder[it] != converged[it] }
        assertEquals(2, moved)
    }

    @Test
    fun `converge requires a clear lead to avoid oscillation`() {
        val order = listOf(
            ScreenContextPreset.TRANSLATE,
            ScreenContextPreset.ORGANIZE,
            ScreenContextPreset.ADD_CALENDAR,
        )
        val nearTie = EtaScreenPresetOrdering.converge(
            order,
            mapOf(
                ScreenContextPreset.TRANSLATE to 10.0,
                ScreenContextPreset.ORGANIZE to 11.0,
            ),
        )
        assertEquals(order, nearTie)

        val clearLead = EtaScreenPresetOrdering.converge(
            order,
            mapOf(
                ScreenContextPreset.TRANSLATE to 1.0,
                ScreenContextPreset.ORGANIZE to 5.0,
            ),
        )
        assertEquals(ScreenContextPreset.ORGANIZE, clearLead.first())
        assertEquals(ScreenContextPreset.TRANSLATE, clearLead[1])
    }

    @Test
    fun `dominant usage promotes straight to the front`() {
        // D：从末位爬到首位不该要求几十次唤醒，压倒性领先一次到位。
        val order = listOf(
            ScreenContextPreset.TRANSLATE,
            ScreenContextPreset.ADD_CALENDAR,
            ScreenContextPreset.ORGANIZE,
            ScreenContextPreset.PICKUP_CODE,
        )
        val promoted = EtaScreenPresetOrdering.promoteByUsage(
            order,
            mapOf(
                ScreenContextPreset.TRANSLATE to 2.0,
                ScreenContextPreset.PICKUP_CODE to 9.0,
            ),
        )
        assertEquals(ScreenContextPreset.PICKUP_CODE, promoted.first())
        assertEquals(order.size, promoted.size)
    }

    @Test
    fun `dominant usage respects the ratio threshold`() {
        val order = listOf(ScreenContextPreset.TRANSLATE, ScreenContextPreset.ORGANIZE)
        // 领先不足 2 倍：不直提，交给逐位收敛。
        assertEquals(
            order,
            EtaScreenPresetOrdering.promoteByUsage(
                order,
                mapOf(
                    ScreenContextPreset.TRANSLATE to 5.0,
                    ScreenContextPreset.ORGANIZE to 9.0,
                ),
            ),
        )
    }

    @Test
    fun `dominant usage needs an absolute floor when the head is cold`() {
        val order = listOf(ScreenContextPreset.TRANSLATE, ScreenContextPreset.ORGANIZE)
        // 首位从未被点（0 分）时，不能因为对手有 0.4 分就重排。
        assertEquals(
            order,
            EtaScreenPresetOrdering.promoteByUsage(
                order,
                mapOf(ScreenContextPreset.ORGANIZE to 0.4),
            ),
        )
    }

    @Test
    fun `learned order absorbs convergence but not keyword promotion`() {
        val usage = mapOf(ScreenContextPreset.PICKUP_CODE to 9.0)
        // 关键词命中：只作用于本次。
        assertEquals(
            fixedOrder,
            EtaScreenPresetRanking.learnedAfterWake(
                learned = fixedOrder,
                screenText = "您的取餐码 A123",
                usage = usage,
                enabled = true,
            ),
        )
        // 无信号 + 首位冷启动（0 分）+ 对手 9 分：越过绝对门槛，直提并写回。
        val coldHead = EtaScreenPresetRanking.learnedAfterWake(
            learned = fixedOrder,
            screenText = "今天天气不错",
            usage = usage,
            enabled = true,
        )
        assertEquals(ScreenContextPreset.PICKUP_CODE, coldHead.first())

        // 无信号 + 首位有分但不占优：真正的「压倒性领先」（2 倍）才算数。
        val dominant = EtaScreenPresetRanking.learnedAfterWake(
            learned = fixedOrder,
            screenText = "今天天气不错",
            usage = mapOf(
                ScreenContextPreset.TRANSLATE to 1.0,
                ScreenContextPreset.PICKUP_CODE to 3.0,
            ),
            enabled = true,
        )
        assertEquals(ScreenContextPreset.PICKUP_CODE, dominant.first())

        // 无信号 + 领先不足 2 倍：只允许逐位收敛一步，不能直提。
        val notDominant = EtaScreenPresetRanking.learnedAfterWake(
            learned = fixedOrder,
            screenText = "今天天气不错",
            usage = mapOf(
                ScreenContextPreset.TRANSLATE to 5.0,
                ScreenContextPreset.PICKUP_CODE to 9.0,
            ),
            enabled = true,
        )
        assertEquals(ScreenContextPreset.TRANSLATE, notDominant.first())
    }

    // ── 开关与边界 ───────────────────────────────────────────────

    @Test
    fun `disabled ranking leaves order untouched`() {
        val ordered = EtaScreenPresetRanking.rank(
            learned = fixedOrder,
            screenText = "您的取餐码 A123 请在出餐口取餐",
            usage = mapOf(ScreenContextPreset.PICKUP_CODE to 99.0),
            enabled = false,
            affinity = ScreenContextPreset.ADD_CALENDAR,
        )
        assertEquals(fixedOrder, ordered)
    }

    @Test
    fun `empty screen text and single-item order are safe`() {
        assertEquals(
            fixedOrder,
            EtaScreenPresetRanking.rank(fixedOrder, null, emptyMap(), enabled = true),
        )
        assertEquals(
            fixedOrder,
            EtaScreenPresetRanking.rank(fixedOrder, "   ", emptyMap(), enabled = true),
        )
        val single = listOf(ScreenContextPreset.ORGANIZE)
        assertEquals(single, EtaScreenPresetRanking.rank(single, "取餐码 A123", emptyMap(), true))
    }

    @Test
    fun `affinity outside the known set is ignored`() {
        // 防御：传入不在当前顺序表里的预设时不能凭空插入。
        val narrowed = listOf(ScreenContextPreset.TRANSLATE)
        assertEquals(
            narrowed,
            EtaScreenPresetRanking.rank(
                learned = narrowed,
                screenText = null,
                usage = emptyMap(),
                enabled = true,
                affinity = ScreenContextPreset.PICKUP_CODE,
            ),
        )
    }

    // ── 无障碍信号（B） ─────────────────────────────────────────

    @Test
    fun `signal text deduplicates and joins nodes`() {
        // 父子节点常各报一次同一段文字；重复会让弱词重复计分，必须去重。
        val signal = EtaScreenWakeOcr.screenSignalOf(
            texts = listOf("取餐码 A123", "取餐码 A123", "   ", "出餐口"),
            passwordFields = 0,
        )
        assertEquals("取餐码 A123 出餐口", signal.text)
        assertFalse(signal.hasPasswordField)
    }

    @Test
    fun `signal flags password fields`() {
        val signal = EtaScreenWakeOcr.screenSignalOf(
            texts = listOf("请输入支付密码"),
            passwordFields = 1,
        )
        assertTrue(signal.hasPasswordField)
    }

    @Test
    fun `empty node list yields an empty signal`() {
        val signal = EtaScreenWakeOcr.screenSignalOf(texts = emptyList(), passwordFields = 0)
        assertEquals("", signal.text)
        assertFalse(signal.hasPasswordField)
    }
}
