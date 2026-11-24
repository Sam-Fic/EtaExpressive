// 唤醒即识别：一次识别结果同时供「预设排序」与「记取码」使用。
//
// 设计取舍：
// - 唤醒本来就有一段截屏采集的等待（chip 文案「正在准备当前屏幕」），识别挂进这段窗口，
//   不额外增加一次用户可感知的停顿；识别完成后才把屏幕上下文置为 AVAILABLE，
//   于是 chips 的首次亮相就是最终顺序，不会在用户眼前二次跳位。
// - 排序只等一个上限（[EtaScreenWakeOcr.RANKING_OCR_TIMEOUT_MS]）。超时先按已学习顺序
//   + 频次收敛出 chips，识别协程照常跑完并把结果写进缓存——用户随后点「记取码」仍命中。
// - 缓存只留在内存，按引用的哈希校验；屏幕文本不落盘。

package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService.UiNode
import io.github.mangi.eta.agent.pickup.PickupCodeExtractor
import io.github.mangi.eta.agent.pickup.ocr.PickupOcrEngine

/** 一次唤醒识别的产物：Pass1 结果（供两步引擎复用）+ 排序用的全文。 */
internal data class EtaScreenWakeOcrResult(
    val pass1: PickupOcrEngine.RecognizeResult,
    val fullText: String,
)

/**
 * 唤醒识别结果的进程内缓存（单槽）。
 *
 * 为什么按引用做键：屏幕上下文附件是「一次唤醒一次截图」的产物，引用本身已经唯一标识
 * 那一帧；同一引用再次查询即说明用户点的还是同一块屏幕。TTL 与引用双重校验，
 * 防止引用被复用或内存被长时间占用。
 *
 * 键只存引用的 SHA-256 而不是原串：附件引用是整段 base64 data URL（数百 KB），
 * 留在静态字段里等于把上一屏截图的内容钉在内存上。哈希既保持「同一帧」的判定力，
 * 也不持有图像数据。
 */
internal object EtaScreenWakeOcrCache {

    /** 缓存有效期：一次唤醒的交互窗口，超过即视为过期的屏幕。 */
    private const val TTL_MS = 60_000L

    private data class Entry(
        val referenceKey: String,
        val result: EtaScreenWakeOcrResult,
        val createdAtMs: Long,
    )

    @Volatile
    private var entry: Entry? = null

    fun put(
        reference: String,
        result: EtaScreenWakeOcrResult,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        entry = Entry(
            referenceKey = hashReference(reference),
            result = result,
            createdAtMs = nowMs,
        )
    }

    /** 命中且未过期才返回；过期顺手清空，避免长期占着识别结果。 */
    fun take(
        reference: String,
        nowMs: Long = System.currentTimeMillis(),
    ): EtaScreenWakeOcrResult? {
        val current = entry ?: return null
        if (current.referenceKey != hashReference(reference) ||
            nowMs - current.createdAtMs > TTL_MS
        ) {
            entry = null
            return null
        }
        return current.result
    }

    fun clear() {
        entry = null
    }

    private fun hashReference(reference: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(reference.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * 唤醒链路的文字获取入口，两层降级：
 *
 * 1. **无障碍树优先**：原生界面能直接读到文本，比 OCR 更准、更快，也不用解码位图。
 *    读屏本来就已经在一次截图里拿到了窗口树，成本是纯内存遍历。
 * 2. **OCR 兜底**：WebView、Canvas、图片、游戏这些拿不到节点文本的场景才走截图识别。
 *
 * 降级判据是「无障碍树里有效文字够不够」，而不是「有没有节点」：很多页面（播放器、
 * 相机取景）节点齐全但全是图标，那种情况仍要 OCR。
 */
internal object EtaScreenWakeOcr {

    /** 排序等待上限：超过就先用频次排序出 chips，识别继续跑。 */
    const val RANKING_OCR_TIMEOUT_MS = 900L

    /** 无障碍树被接受为排序输入的最小字数：太少说明只是标题栏或按钮文字。 */
    private const val MIN_ACCESSIBILITY_TEXT_LENGTH = 16

    /** 无障碍树最多遍历的节点数：与节点工具的常用上限一致，够覆盖一屏正文。 */
    private const val ACCESSIBILITY_NODE_LIMIT = 120

    /**
     * 读取当前屏幕的排序文本：无障碍树优先，不够再用 OCR。
     *
     * 取码缓存的口径：只有真的跑过 OCR 才写缓存。无障碍树命中时没有 Pass1 结果，
     * 用户随后点「记取码」会走它自己的全图识别（[PickupCodeExtractor] 里
     * existingPass1 为 null 即自行识别），功能正确但没有加速。
     *
     * 这个取舍是有意的：为了给取码预热而在排序阶段强制跑一次 OCR，等于把「包名/无障碍
     * 能判出来就不截图推理」的收益全部抵消——而绝大多数唤醒用户并不会点取码。
     */
    suspend fun screenTextForRanking(
        context: Context,
        reference: String,
        accessibilityText: String?,
    ): String? {
        accessibilityText
            ?.takeIf { it.trim().length >= MIN_ACCESSIBILITY_TEXT_LENGTH }
            ?.let { return it }

        // 无障碍树文字不够（WebView / Canvas / 图片页）才走 OCR。
        // 命中后写缓存供「记取码」复用——这正是「一次识别、两处复用」的落点。
        val pass1 = pass1FromReference(context, reference) ?: return accessibilityText
        EtaScreenWakeOcrCache.put(
            reference = reference,
            result = EtaScreenWakeOcrResult(pass1 = pass1, fullText = pass1.fullText),
        )
        return pass1.fullText
    }

    /**
     * 唤醒识别：对屏幕上下文附件**解码出的那张位图**跑 Pass1。
     *
     * 必须用解码后的位图而不是截屏原始帧：编码链路会按预算缩放（nativeScreenContext），
     * 后续「记取码」是解码同一份引用再裁剪的。只有在同一张位图上算出的框，坐标才对得上
     * 两步引擎的裁剪区（见 [PickupCodeExtractor.fromScreenContextReference] 的 existingPass1）。
     */
    suspend fun pass1FromReference(
        context: Context,
        reference: String,
    ): PickupOcrEngine.RecognizeResult? {
        val bitmap = PickupCodeExtractor.decodeReferenceBitmap(reference) ?: return null
        return try {
            PickupOcrEngine.getInstance(context).recognizeAsync(bitmap)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    /**
     * 从一份无障碍节点快照里抽出排序要用的两件事：可见文本、有无密码输入框。
     *
     * 文本只取可见且非空的节点：`queryNodes` 已按可见性裁剪，这里再去掉纯图标节点留下的
     * 空白与重复项（同一段文字常被父子节点各报一次，重复会让弱词重复计分）。
     *
     * 密码框是敏感页的判据之一：支付页往往只是宿主 App 里的 WebView 或弹层，包名识别不出来，
     * 但密码框是该类页面唯一可靠的共性特征。
     */
    fun screenSignalOf(nodes: List<UiNode>): EtaScreenSignal {
        // desc 也要收：图标按钮、无文字的卡片常用 contentDescription 承载语义，
        // 微信这类全是 ImageView 的聊天页如果只看 text 会读出一片空白。
        val texts = ArrayList<String>(nodes.size * 2)
        nodes.forEach { node ->
            texts += node.text
            texts += node.desc
        }
        return screenSignalOf(
            texts = texts,
            passwordFields = nodes.count { it.password },
        )
    }

    /**
     * 与 [screenSignalOf] 同口径的纯数据版本。
     *
     * 单独开一个不依赖 `UiNode` 的重载是给单测用的：`UiNode` 含 `android.graphics.Rect`
     * 字段，JVM 单测里构造它需要 Robolectric 或 mock，而这里要验证的只是「去重 + 取文本
     * + 认密码框」这段纯逻辑，没必要为此拉起 Android 运行时。
     */
    fun screenSignalOf(texts: List<String>, passwordFields: Int): EtaScreenSignal {
        val seen = LinkedHashSet<String>()
        texts.forEach { raw ->
            val text = raw.trim()
            if (text.isNotEmpty()) seen += text
        }
        return EtaScreenSignal(
            text = seen.joinToString(" "),
            hasPasswordField = passwordFields > 0,
        )
    }

    /** 节点快照的遍历上限，供调用方一次性查询用。 */
    fun accessibilityNodeLimit(): Int = ACCESSIBILITY_NODE_LIMIT
}

/**
 * 智能排序总开关。
 *
 * 与 [io.github.mangi.eta.agent.pickup.PickupCodeSettings] 同一取舍：自包含的子功能用
 * 独立 SharedPreferences 文件，不进全局 Prefs.Keys（那里要同步设置系统、导出/恢复与
 * 默认值表测试）。关闭后回退为固定顺序 + 历史频次，不读屏幕文字。
 */
internal object EtaScreenSmartRankingSettings {
    private const val PREFS_NAME = "assistant_screen_preset_prefs"

    private const val KEY_ENABLED = "smart_ranking_enabled"

    /** 默认开：功能入口即为此设计；关闭只影响排序，不影响取码与截图。 */
    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            enabled = prefs(context).getBoolean(KEY_ENABLED, true)
            loaded = true
        }
    }

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        enabled = value
        loaded = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/**
 * Eta 的界面语言是否中文。
 *
 * 翻译判据必须知道这件事：英文界面下用户看到的拉丁文本就是自己的语言，
 * 把它当「外文」会让人每次唤醒都看到翻译置顶。
 *
 * 判据取应用资源实际解析出的 locale（per-app 语言优先），而不是系统语言：
 * Eta 支持单独设置应用语言，用户可能把系统设成英文但应用保持中文。
 */
internal object EtaScreenInterfaceLocale {
    fun isChinese(context: Context): Boolean {
        // 用传入 context 自己的 resources，不要绕到 applicationContext：
        // 入口浮窗的 Service 已覆写 getResources() 按 per-app 语言派生，
        // 绕过去会退回进程启动时的 locale，per-app 中文 + 系统英文时就会误判。
        val language = runCatching {
            context.resources.configuration.locales
                .takeIf { !it.isEmpty }
                ?.get(0)
                ?.language
        }.getOrNull().orEmpty()
        return language.startsWith("zh", ignoreCase = true)
    }
}

/**
 * 一次唤醒从无障碍树里能读到的排序信号。
 *
 * 与 OCR 的区别：这些字段直接来自窗口树，零解码零推理，Native 界面下足够判断
 * 「这一屏是什么」，也是敏感页判据的来源。
 */
internal data class EtaScreenSignal(
    val text: String,
    val hasPasswordField: Boolean,
)
