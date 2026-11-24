// 屏幕快捷预设的智能排序（唤醒即排序）。
//
// 为什么不是「加权打分」：预设 chip 是强肌肉记忆控件，用户必须能猜到「它为什么上来了」。
// 因此排序信号按「可信度从高到低」分层，任一层命中就停，不叠加加权：
//   1. 前台包名（[EtaScreenPresetAffinity]）：一次查询、零 OCR，最可靠；
//   2. 屏幕文字关键词（[EtaScreenPresetHints]）：包名判不出来时才看这一层；
//   3. 历史频次（[EtaScreenPresetUsage]）：前面都没命中时按使用习惯缓慢收敛。
//
// 排序结果只在 chips 首次亮相前定好（见 EtaVoicePanel 的 presetRankingSettled 闸门），
// 不在用户眼前二次跳位。
//
// 「添加屏幕」不参与排序：它在 chips 行里由调用方固定在最前，本文件只排快捷预设。

package io.github.mangi.eta.agent.voice

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max

/**
 * 文本归一化：全角转半角、统一小写、去掉所有空白。
 *
 * 关键词匹配必须走归一化后的文本：截图 OCR 会把「Ａ１２３」「A 123」这类写法原样带出，
 * 而品牌名混英文、全角括号在原样 `contains` 下都会漏。归一化只影响匹配，不改变展示。
 */
internal object EtaScreenText {
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        val builder = StringBuilder(raw.length)
        raw.forEach { char ->
            val code = char.code
            when {
                // 全角 ASCII（！到 ～）压回半角。
                code in 0xFF01..0xFF5E -> builder.append((code - 0xFEE0).toChar())
                // 全角空格按空白处理。
                char == '\u3000' -> Unit
                char.isWhitespace() -> Unit
                else -> builder.append(char)
            }
        }
        return builder.toString().lowercase(Locale.ROOT)
    }
}

/**
 * 屏幕文字 → 预设命中打分。
 *
 * 规则刻意保持「词表 + 一个文本形状」的简单形态：
 * - 强词（[STRONG_SCORE]）：页面主题就是这件事，单独命中即可达标；
 * - 弱词（[WEAK_SCORE]）：只是陪衬字段，需要同组两枚以上才达标；
 * - 翻译没有稳定中文词表（要翻的正是外文），改用「字母数量 + 字母占比」的形状判据。
 */
internal object EtaScreenPresetHints {

    /** 强词权重：单枚命中即达到 [HIT_MIN_SCORE]。 */
    private const val STRONG_SCORE = 3

    /** 弱词权重：需要两枚以上才达标。 */
    private const val WEAK_SCORE = 1

    /** 判为「命中」的最低分：与强词权重对齐——一枚强词即可，两枚弱词也可以。 */
    const val HIT_MIN_SCORE = 2

    /** 翻译判据：拉丁字母数量与字母占比的双阈值。 */
    private const val TRANSLATE_MIN_LETTERS = 20
    private const val TRANSLATE_MIN_LETTER_RATIO = 0.55f

    /** CJK 起点：统一表意区之前的表音字母才算「外文字母」。 */
    private const val CJK_START = 0x2E80

    /** 强词表：页面主题词（原文，便于维护；匹配用的是归一化后的 [strongNormalized]）。 */
    private val strongKeywords: Map<ScreenContextPreset, List<String>> = mapOf(
        ScreenContextPreset.PICKUP_CODE to listOf(
            "取餐码", "取件码", "取货码", "待取件", "出餐口", "菜鸟驿站", "快递柜", "丰巢", "取码",
        ),
        ScreenContextPreset.ADD_CALENDAR to listOf(
            "日程", "日程安排", "预约成功", "报名成功", "活动时间", "会议邀请", "会议通知",
        ),
        ScreenContextPreset.ORGANIZE to listOf(
            "摘要", "要点", "正文", "纪要", "笔记要点",
        ),
    )

    /** 弱词表：陪衬字段，按同组累计计分。 */
    private val weakKeywords: Map<ScreenContextPreset, List<String>> = mapOf(
        ScreenContextPreset.PICKUP_CODE to listOf(
            "取餐", "取件", "待取", "外卖", "快递", "骑手", "餐号", "叫号", "驿站", "请取", "取货",
        ),
        ScreenContextPreset.ADD_CALENDAR to listOf(
            "会议室", "开始时间", "结束时间", "星期", "主办", "签到", "地点", "时间",
        ),
        ScreenContextPreset.ORGANIZE to listOf(
            "全文", "章节", "阅读", "整理", "内容",
        ),
    )

    /**
     * 归一化后的词表，进程内只算一次。
     *
     * 词表是静态的，每次打分都重新 normalize 一遍纯属浪费——唤醒路径上任何一点
     * 主线程外的重复工作都会摊到 chips 的亮相时间上。
     */
    private val strongNormalized: Map<ScreenContextPreset, List<String>> =
        strongKeywords.mapValues { (_, words) -> words.map(EtaScreenText::normalize) }

    private val weakNormalized: Map<ScreenContextPreset, List<String>> =
        weakKeywords.mapValues { (_, words) -> words.map(EtaScreenText::normalize) }

    /**
     * 各预设的原始得分；未命中任何词的预设不出现在结果里（等同 0 分）。
     *
     * [interfaceIsChinese] 只影响翻译判据：界面语言不是中文时，拉丁文本就是用户自己的
     * 语言，不该被当成「外文」置顶翻译——否则英文用户看任何英文应用都会一直看到翻译。
     */
    fun scores(text: String, interfaceIsChinese: Boolean = true): Map<ScreenContextPreset, Int> {
        if (text.isBlank()) return emptyMap()
        val normalized = EtaScreenText.normalize(text)
        if (normalized.isBlank()) return emptyMap()
        val result = linkedMapOf<ScreenContextPreset, Int>()
        ScreenContextPreset.entries.forEach { preset ->
            var score = 0
            strongNormalized[preset].orEmpty().forEach { keyword ->
                if (normalized.contains(keyword)) score += STRONG_SCORE
            }
            weakNormalized[preset].orEmpty().forEach { keyword ->
                if (normalized.contains(keyword)) score += WEAK_SCORE
            }
            if (preset == ScreenContextPreset.TRANSLATE &&
                interfaceIsChinese &&
                looksLikeForeignText(normalized)
            ) {
                score += HIT_MIN_SCORE
            }
            if (score > 0) result[preset] = score
        }
        return result
    }

    /**
     * 外文正文判据：字母够多、且字母在「字母 + 数字 + 汉字」里占绝对多数。
     * 只看占比会把「取码 A1234」这类短码误判成外文，所以数量与占比都必须满足。
     */
    private fun looksLikeForeignText(normalized: String): Boolean {
        var letters = 0
        var effective = 0
        normalized.forEach { char ->
            when {
                char.isLetter() && char.code < CJK_START -> {
                    letters++
                    effective++
                }
                char.isLetter() || char.isDigit() -> effective++
            }
        }
        if (letters < TRANSLATE_MIN_LETTERS) return false
        return letters.toFloat() / max(effective, 1) >= TRANSLATE_MIN_LETTER_RATIO
    }
}

/**
 * 前台包名 → 预设的高置信度关联。
 *
 * 一层「先验 + 学习」：
 * - 内置表覆盖常见外卖、快递、日历、浏览器、笔记类应用，首次使用即有判断力；
 * - 表外应用靠 [record] 学习：用户在某包名下点过某预设，就记一次关联，
 *   累计到 [LEARNED_HIT_COUNT] 次后该包名直接提拔这个预设。
 *
 * 为什么不只用 OCR：包名一次查询零成本、零误差，而且不受开屏动画、弹窗遮罩影响。
 * 它判得出来的场景根本不需要截图识别——这也是唤醒延迟的主要优化点。
 */
internal object EtaScreenPresetAffinity {

    /** 内置表：包名 → 预设。 */
    private val builtIn: List<Pair<String, ScreenContextPreset>> = listOf(
        // 外卖 / 快递 / 自提
        "com.sankuai.meituan" to ScreenContextPreset.PICKUP_CODE,
        "me.ele" to ScreenContextPreset.PICKUP_CODE,
        "com.cainiao.wireless" to ScreenContextPreset.PICKUP_CODE,
        "com.jingdong.app.mall" to ScreenContextPreset.PICKUP_CODE,
        "com.taobao.taobao" to ScreenContextPreset.PICKUP_CODE,
        "com.sf.activity" to ScreenContextPreset.PICKUP_CODE,
        // 日程 / 会议
        "com.android.calendar" to ScreenContextPreset.ADD_CALENDAR,
        "com.google.android.calendar" to ScreenContextPreset.ADD_CALENDAR,
        "com.coloros.calendar" to ScreenContextPreset.ADD_CALENDAR,
        "com.miui.calendar" to ScreenContextPreset.ADD_CALENDAR,
        "com.meizu.calendar" to ScreenContextPreset.ADD_CALENDAR,
        "com.alibaba.android.rimet" to ScreenContextPreset.ADD_CALENDAR,
        "com.ss.android.lark" to ScreenContextPreset.ADD_CALENDAR,
        "com.tencent.wework" to ScreenContextPreset.ADD_CALENDAR,
        "com.microsoft.teams" to ScreenContextPreset.ADD_CALENDAR,
        "com.microsoft.office.outlook" to ScreenContextPreset.ADD_CALENDAR,
        // 微信既可能是约时间也常是长文转发，按「日程」优先：会议/约会信息在聊天里出现得更多。
        "com.tencent.mm" to ScreenContextPreset.ADD_CALENDAR,
        // 长文 / 阅读 → 整理
        "com.tencent.weread" to ScreenContextPreset.ORGANIZE,
        "com.ss.android.article.news" to ScreenContextPreset.ORGANIZE,
        "com.zhihu.android" to ScreenContextPreset.ORGANIZE,
        "com.coloros.note" to ScreenContextPreset.ORGANIZE,
        "com.miui.notes" to ScreenContextPreset.ORGANIZE,
        "com.tencent.qqmail" to ScreenContextPreset.ORGANIZE,
        // 浏览器 / 翻译
        "com.android.chrome" to ScreenContextPreset.TRANSLATE,
        "com.microsoft.emmx" to ScreenContextPreset.TRANSLATE,
        "com.tencent.mtt" to ScreenContextPreset.TRANSLATE,
        "com.UCMobile" to ScreenContextPreset.TRANSLATE,
        "com.google.android.apps.translate" to ScreenContextPreset.TRANSLATE,
    )

    /**
     * 敏感场景：支付、银行、系统设置。这些页面上屏幕文本等于「用户正在做什么」，
     * 一律不参与排序，回退固定顺序，且不学习、不记频次。
     */
    private val sensitivePrefixes: List<String> = listOf(
        "com.eg.android.AlipayGphone",
        "com.unionpay",
        "com.icbc",
        "com.ccb",
        "com.abchina",
        "com.bankcomm",
        "com.cmbchina",
        "com.chinamworld.main",
        "com.antfortune.wealth",
        "com.hexin.plat.android",
        "com.android.settings",
        "com.android.vending", // Play 商店含支付与账号
    )

    /** 学习命中阈值：同一包名下点过这么多次，才认为关联稳定。 */
    private const val LEARNED_HIT_COUNT = 3

    private const val PREFS_NAME = "assistant_screen_preset_prefs"

    private const val KEY_PREFIX = "affinity_"

    /**
     * 敏感页判定：命中包名表，或页面上存在密码输入框。
     *
     * 为什么还要看密码框：支付页常常只是宿主 App 里的一个 WebView / 弹层，包名仍是
     * 电商或聊天应用，光看包名识别不出来。密码框是这类页面唯一可靠的共性特征。
     */
    fun isSensitiveScreen(packageName: String?, hasPasswordField: Boolean): Boolean {
        if (hasPasswordField) return true
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return false
        return sensitivePrefixes.any { matches(pkg, it) }
    }

    /** 该包名对应的预设：内置表优先，其次学习值。返回 null 表示判不出来。 */
    fun affinity(context: Context, packageName: String?): ScreenContextPreset? {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return null
        builtIn.firstOrNull { matches(pkg, it.first) }?.let { return it.second }

        // 学习值：取计数最高的那个（并列时按枚举顺序，保证结果稳定）。
        val prefs = prefs(context)
        var best: ScreenContextPreset? = null
        var bestCount = 0
        ScreenContextPreset.entries.forEach { preset ->
            val hit = readLearned(prefs, preset)
            if (hit.packageName != pkg || hit.count < LEARNED_HIT_COUNT) return@forEach
            if (hit.count > bestCount) {
                best = preset
                bestCount = hit.count
            }
        }
        return best
    }

    /** 记一次「该包名用了该预设」，供表外应用逐步接管。 */
    fun record(
        context: Context,
        packageName: String?,
        preset: ScreenContextPreset,
    ) {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return
        val prefs = prefs(context)
        synchronized(this) {
            val hit = readLearned(prefs, preset)
            // 关联只跟最后一个包名绑定：换包名即从头计数，
            // 避免把一个应用的使用习惯带到一个全新应用上。
            val next = if (hit.packageName == pkg) hit.count + 1 else 1
            prefs.edit()
                .putString(KEY_PREFIX + preset.name, "${next.coerceAtMost(LEARNED_HIT_COUNT * 2)}|$pkg")
                .apply()
        }
    }

    /** 包名匹配：全等或以 `前缀.` 开头。前缀直接 startsWith 会把 me.ele 匹到 me.electric。 */
    private fun matches(packageName: String, prefix: String): Boolean =
        packageName == prefix || packageName.startsWith("$prefix.")

    private fun readLearned(
        prefs: SharedPreferences,
        preset: ScreenContextPreset,
    ): Learned {
        val raw = prefs.getString(KEY_PREFIX + preset.name, null) ?: return Learned(0, null)
        val parts = raw.split('|')
        return Learned(
            count = parts.getOrNull(0)?.toIntOrNull() ?: 0,
            packageName = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
        )
    }

    private data class Learned(val count: Int, val packageName: String?)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * 预设顺序求解（纯函数，便于单测）。
 *
 * 三条规则拆成独立函数，任一唤醒只让其中一条真正改变顺序：
 * - [promote]：上下文命中时只把得分最高的一个提到首位，其余保持既有相对顺序；
 *   同分取原有顺序靠前者，保证同一屏重复唤醒结果完全一致。
 * - [converge]：无命中时只在相邻两位之间做一次「领先足够多才换」的交换；
 *   没有 [USAGE_LEAD_RATIO] 的领先量就不换，避免两个几乎等频的预设来回互换。
 * - [promoteByUsage]：某预设频次达到首位 [DOMINANT_RATIO] 倍时直接提到首位。
 *   逐位收敛在 4 个预设、1.5 倍领先下要从末位爬到首位需要很多次唤醒，
 *   用户感受不到「在学习」；这条补齐规则让重度偏好一次到位。
 */
internal object EtaScreenPresetOrdering {

    /** 频次交换的领先倍数：后一位要领先前一位这么多倍才允许前移一格。 */
    private const val USAGE_LEAD_RATIO = 1.5

    /** 压倒性领先倍数：达到即跳过逐位收敛，直接提到首位。 */
    private const val DOMINANT_RATIO = 2.0

    fun promote(
        current: List<ScreenContextPreset>,
        scores: Map<ScreenContextPreset, Int>,
    ): List<ScreenContextPreset> {
        if (current.size <= 1) return current
        var best: ScreenContextPreset? = null
        var bestScore = 0
        current.forEach { preset ->
            val score = scores[preset] ?: 0
            if (score >= EtaScreenPresetHints.HIT_MIN_SCORE && score > bestScore) {
                best = preset
                bestScore = score
            }
        }
        val promoted = best ?: return current
        if (promoted == current.first()) return current
        return listOf(promoted) + current.filterNot { it == promoted }
    }

    fun converge(
        current: List<ScreenContextPreset>,
        usage: Map<ScreenContextPreset, Double>,
    ): List<ScreenContextPreset> {
        if (current.size <= 1) return current
        for (index in 0 until current.lastIndex) {
            val front = current[index]
            val back = current[index + 1]
            val frontScore = usage[front] ?: 0.0
            val backScore = usage[back] ?: 0.0
            if (backScore > 0.0 && backScore > frontScore * USAGE_LEAD_RATIO) {
                return current.toMutableList().apply {
                    this[index] = back
                    this[index + 1] = front
                }
            }
        }
        return current
    }

    /**
     * 压倒性频次直接提到首位。仅对「不在首位且频次达到首位 [DOMINANT_RATIO] 倍」的
     * 预设生效，且一次只提一个（频次最高者），不整体重排。
     */
    fun promoteByUsage(
        current: List<ScreenContextPreset>,
        usage: Map<ScreenContextPreset, Double>,
    ): List<ScreenContextPreset> {
        if (current.size <= 1) return current
        val headScore = usage[current.first()] ?: 0.0
        var best: ScreenContextPreset? = null
        var bestScore = 0.0
        current.drop(1).forEach { preset ->
            val score = usage[preset] ?: 0.0
            // 首位为 0 时要求对手 > 0 且达到绝对门槛，避免冷启动就乱动顺序。
            val dominant = if (headScore <= 0.0) score >= 2.0 else score >= headScore * DOMINANT_RATIO
            if (dominant && score > bestScore) {
                best = preset
                bestScore = score
            }
        }
        val promoted = best ?: return current
        return listOf(promoted) + current.filterNot { it == promoted }
    }
}

/**
 * 本次唤醒的最终顺序。
 *
 * 信号优先级：包名关联 > 屏幕文字关键词 > 历史频次。命中前一层就不再往下判，
 * 所以「包名已判出来」时用户完全不必等 OCR。
 */
internal object EtaScreenPresetRanking {

    fun rank(
        learned: List<ScreenContextPreset>,
        screenText: String?,
        usage: Map<ScreenContextPreset, Double>,
        enabled: Boolean,
        affinity: ScreenContextPreset? = null,
        interfaceIsChinese: Boolean = true,
    ): List<ScreenContextPreset> {
        val base = learned.ifEmpty { ScreenContextPreset.entries.toList() }
        if (!enabled) return base

        // 第一层：前台包名。判得出来就直接用，不看屏幕文字。
        affinity?.takeIf { base.contains(it) }?.let { preset ->
            return if (preset == base.first()) base else listOf(preset) + base.filterNot { it == preset }
        }

        // 第二层：屏幕文字关键词。
        val scores = screenText?.takeIf { it.isNotBlank() }
            ?.let { EtaScreenPresetHints.scores(it, interfaceIsChinese) }
            .orEmpty()
        val promoted = EtaScreenPresetOrdering.promote(base, scores)
        if (promoted != base) return promoted

        // 第三层：历史频次。
        return EtaScreenPresetOrdering.promoteByUsage(promoted, usage)
    }

    /**
     * 无上下文命中时把收敛结果写回学习顺序；有上下文信号时不动学习顺序。
     *
     * 上下文信号（包名或关键词）只作用于本次：偶发的一屏取码页不该把取码长期钉在首位。
     */
    fun learnedAfterWake(
        learned: List<ScreenContextPreset>,
        screenText: String?,
        usage: Map<ScreenContextPreset, Double>,
        enabled: Boolean,
        affinity: ScreenContextPreset? = null,
        interfaceIsChinese: Boolean = true,
    ): List<ScreenContextPreset> {
        val base = learned.ifEmpty { ScreenContextPreset.entries.toList() }
        if (!enabled) return base
        if (affinity != null && base.contains(affinity)) return base
        val scores = screenText?.takeIf { it.isNotBlank() }
            ?.let { EtaScreenPresetHints.scores(it, interfaceIsChinese) }
            .orEmpty()
        if (scores.values.any { it >= EtaScreenPresetHints.HIT_MIN_SCORE }) return base
        // 无信号时按频次收敛：先看是否压倒性领先，否则逐位挪一格。
        val dominant = EtaScreenPresetOrdering.promoteByUsage(base, usage)
        if (dominant != base) return dominant
        return EtaScreenPresetOrdering.converge(base, usage)
    }
}

/**
 * 已学习顺序的持久化。
 *
 * 与 [io.github.mangi.eta.agent.pickup.PickupCodeSettings] 同一取舍：自包含子功能用独立
 * SharedPreferences 文件，不进全局 Prefs.Keys 键空间（那里要同步设置系统、导出/恢复）。
 * 存枚举名而不是下标：新增预设时旧值仍可解析，解析不出的项按固定表补齐。
 */
internal object EtaScreenPresetOrderStore {

    private const val PREFS_NAME = "assistant_screen_preset_prefs"

    private const val KEY_LEARNED_ORDER = "learned_order"

    fun read(context: Context): List<ScreenContextPreset> {
        val raw = prefs(context).getString(KEY_LEARNED_ORDER, null) ?: return defaultOrder()
        val persisted = raw.split(',')
            .mapNotNull { name -> ScreenContextPreset.entries.firstOrNull { it.name == name } }
        return normalize(persisted)
    }

    fun write(context: Context, order: List<ScreenContextPreset>) {
        val normalized = normalize(order)
        if (normalized == defaultOrder()) {
            // 回到默认顺序就当没学过，免得旧值一直留在库里干扰诊断。
            prefs(context).edit().remove(KEY_LEARNED_ORDER).apply()
            return
        }
        prefs(context).edit()
            .putString(KEY_LEARNED_ORDER, normalized.joinToString(",") { it.name })
            .apply()
    }

    /** 补齐缺失项并去重：顺序表必须恰好覆盖全部预设各一次。 */
    private fun normalize(order: List<ScreenContextPreset>): List<ScreenContextPreset> {
        val seen = LinkedHashSet<ScreenContextPreset>()
        order.forEach { seen += it }
        ScreenContextPreset.entries.forEach { seen += it }
        return seen.toList()
    }

    private fun defaultOrder(): List<ScreenContextPreset> = ScreenContextPreset.entries.toList()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * 预设使用频次（带时间衰减），只用于「无上下文命中」时的低频换位。
 *
 * 衰减口径是 EMA：每次点击先按 [DECAY_TAU_MS] 指数衰减旧分，再加 1。这样「上周天天用、
 * 这周不用」的预设会自然让位，不需要额外的清理任务。
 */
internal object EtaScreenPresetUsage {

    private const val PREFS_NAME = "assistant_screen_preset_prefs"

    private const val KEY_PREFIX = "usage_"

    /** 衰减时间常数：7 天；一周不点，旧分衰减到约 1/e。 */
    private const val DECAY_TAU_MS = 7L * 24L * 60L * 60L * 1000L

    /** 总分上限：防止长期重度使用把某一位永久钉死（衰减后仍可被追上）。 */
    private const val MAX_SCORE = 40.0

    /** 记一次点击（读改写在同一把锁内完成，避免并发点击互相覆盖）。 */
    fun record(
        context: Context,
        preset: ScreenContextPreset,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val prefs = prefs(context)
        synchronized(this) {
            val (score, lastMs) = read(prefs, preset)
            val decayed = decay(score, lastMs, nowMs) + 1.0
            prefs.edit().putString(
                KEY_PREFIX + preset.name,
                "${decayed.coerceAtMost(MAX_SCORE)}|$nowMs",
            ).apply()
        }
    }

    /** 读出当前衰减后的频次分；未记录过的预设不出现在结果里（等同 0）。 */
    fun scores(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): Map<ScreenContextPreset, Double> {
        val prefs = prefs(context)
        val result = linkedMapOf<ScreenContextPreset, Double>()
        ScreenContextPreset.entries.forEach { preset ->
            val (score, lastMs) = read(prefs, preset)
            if (score > 0.0) result[preset] = decay(score, lastMs, nowMs)
        }
        return result
    }

    private fun decay(score: Double, lastMs: Long, nowMs: Long): Double {
        if (score <= 0.0 || lastMs <= 0L) return score
        val elapsed = (nowMs - lastMs).coerceAtLeast(0L)
        return score * exp(-elapsed.toDouble() / DECAY_TAU_MS)
    }

    private fun read(
        prefs: SharedPreferences,
        preset: ScreenContextPreset,
    ): Pair<Double, Long> {
        val raw = prefs.getString(KEY_PREFIX + preset.name, null) ?: return 0.0 to 0L
        val parts = raw.split('|')
        val score = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0
        val lastMs = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        return score to lastMs
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
