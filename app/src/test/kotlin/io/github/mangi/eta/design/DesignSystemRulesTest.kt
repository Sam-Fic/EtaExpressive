package io.github.mangi.eta.design

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设计系统执法测试（零新依赖的文件扫描版；未来可升级为 Konsist 反射规则）。
 *
 * 对应 `docs/design-system.md` 的分层与规则。这里只放**当前为真**的约束——
 * 每条规则都必须此刻全绿，禁止「先写失败规则等修复」；存量债务用白名单钉住，
 * 白名单外新增即失败。
 */
class DesignSystemRulesTest {

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
        }
        val root = checkNotNull(dir) { "找不到仓库根目录（缺 settings.gradle.kts）" }
        root
    }

    /** 主源码（app/src/main/kotlin）下的全部 .kt 文件，相对路径 + 文本。 */
    private val mainSources: List<Pair<String, String>> by lazy {
        val dir = File(repoRoot, "app/src/main/kotlin")
        check(dir.isDirectory) { "缺主源码目录：$dir" }
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(repoRoot).invariantSeparatorsPath to it.readText() }
            .toList()
    }

    private fun violations(pattern: Regex, allowed: (String) -> Boolean = { false }): List<String> =
        mainSources.flatMap { (path, text) ->
            if (allowed(path)) {
                emptyList<String>()
            } else {
                text.lineSequence()
                    .withIndex()
                    .filter { pattern.containsMatchIn(it.value) }
                    .map { "$path:${it.index + 1}: ${it.value.trim()}" }
                    .toList()
            }
        }

    /**
     * R1：颜色字面量只允许出现在「定义颜色的地方」——主题工厂的强调色种子、移植
     * 主题（iOS 浏览器）的固定色板、浮窗覆盖层的固定色、Markdown 默认样式。
     * UI 组件一律引用官方 colorScheme 角色（eta* 取色助手），不得写字面量。
     */
    @Test
    fun `color literals stay inside color definition sites`() {
        val colorDefinitionSites = listOf(
            "app/src/main/kotlin/io/github/mangi/eta/agent/browser/ported/ui/theme/",
            "app/src/main/kotlin/io/github/mangi/eta/agent/overlay/AgentOverlayContent.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/AppearanceSettingsScreen.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/app/EtaColorSchemeFactory.kt",
            // success 语义色：动态配色没有稳定 success 角色，深浅两档色值与对比度在此固定。
            "app/src/main/kotlin/io/github/mangi/eta/ui/adaptive/EtaStatus.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/components/ThinkingSpeedAnimation.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/markdown/MarkdownInline.kt",
        )
        val hits = violations(
            Regex("""Color\(0x"""),
            allowed = { path -> colorDefinitionSites.any { path.removePrefix("/").startsWith(it) } },
        )
        assertTrue(
            "发现组件层硬编码颜色，请改用 MaterialTheme.colorScheme 角色；" +
                "确属「颜色的定义处」（主题工厂/固定色板）请把文件加入本测试白名单：\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /**
     * R2：工具元数据单一数据源。图标与显示名只允许在 ToolPresentation.kt 定义；
     * 迁移前的 iconForTool / toolDisplayNameResource 两张旧表不得在任何地方复活。
     */
    @Test
    fun `tool presentation metadata stays in ToolPresentation`() {
        val hits = violations(Regex("""\bfun\s+(iconForTool|toolDisplayNameResource)\b"""))
        assertTrue(
            "工具图标/显示名映射只能在 agent/tool/ToolPresentation.kt 维护：\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /**
     * R3：私有设计令牌 helper 不再扩散。令牌形的 `private fun chat…Color/Style/Shape/Border`
     * 只允许留在两个存量文件（白名单钉死）；新文件的排版走 ChatTypography.kt，
     * 颜色走 ui/adaptive 的 eta* 取色，尺寸走 EtaDimens。
     */
    @Test
    fun `private chat design helpers stay pinned to legacy files`() {
        val legacy = listOf(
            "app/src/main/kotlin/io/github/mangi/eta/ui/components/ChatMessageItem.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatBody.kt",
        )
        val hits = violations(
            Regex("""private\s+fun\s+chat[A-Za-z]*(Color|Style|Shape|Border)\b"""),
            allowed = { path -> path.removePrefix("/") in legacy },
        )
        assertTrue(
            "私有 chat* 设计助手只能在白名单文件中定义（新增请放 ChatTypography.kt / EtaDimens）：\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /**
     * R4：进度/加载指示器必须走封装。业务文件禁止直接实例化
     * `CircularProgressIndicator` / `LinearProgressIndicator` / wavy 变体 /
     * `LoadingIndicator` / `ContainedMorphLoadingIndicator`——一律通过
     * `ui/adaptive/EtaLoading.kt` 的 `EtaLoadingSlot` / `EtaProgressBar`，
     * 调用点只传 `progress: Float?`（null 不定型、非 null 确定型）。
     *
     * 白名单 = 封装自身、`ui/components` 里存量「状态标记」/同心圆、
     * browser 移植层与 Doubao 三步 stepper；这些按普查结论明确不迁。
     */
    @Test
    fun `progress and loading indicators go through EtaLoading wrappers`() {
        val wrapper = "app/src/main/kotlin/io/github/mangi/eta/ui/adaptive/EtaLoading.kt"
        val legacy = setOf(
            "app/src/main/kotlin/io/github/mangi/eta/ui/components/ContainedMorphLoadingIndicator.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatBody.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatComposerM3.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatInputBar.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentStatusCard.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/components/ChatImagePreview.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/components/ChatMessageItem.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/components/ConversationSidePaneScaffold.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/components/SpeechPlaybackButton.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/ConsoleScreen.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/SharedFoldersScreen.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/screens/virtualdisplay/VirtualDisplaySections.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/screens/virtualdisplay/VirtualDisplayStage.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/screens/browser/AgentBrowserScreen.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/screens/search/MediaSearchScreen.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/ContextCompressionDialogs.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/ContextCompressionSettingsScreen.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/DoubaoVoiceSettings.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/OfflineSpeechPackColumn.kt",
            "app/src/main/kotlin/io/github/mangi/eta/agent/overlay/AgentOverlayCapsule.kt",
            "app/src/main/kotlin/io/github/mangi/eta/agent/voice/EtaVoicePanel.kt",
        )
        val hits = violations(
            Regex(
                """\b(ContainedMorphLoadingIndicator|ContainedLoadingIndicator|""" +
                    """CircularProgressIndicator|LinearProgressIndicator|""" +
                    """CircularWavyProgressIndicator|LinearWavyProgressIndicator|LoadingIndicator)\s*\(""",
            ),
            allowed = { path ->
                val clean = path.removePrefix("/")
                clean == wrapper ||
                    clean in legacy ||
                    clean.startsWith("app/src/main/kotlin/io/github/mangi/eta/agent/browser/ported/")
            },
        )
        assertTrue(
            "禁止在业务文件直接实例化进度/加载指示器：请改用 EtaLoadingSlot / EtaProgressBar，" +
                "调用点只传 progress: Float?（null 不定型、非 null 确定型）；" +
                "确属明确不迁的存量请在白名单登记。详见 docs/design-system.md：\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    /**
     * R5：长按提示气泡只有一个出处。`TooltipBox` / `PlainTooltip` / `RichTooltip` /
     * `rememberTooltipState` 只能在 `ui/adaptive/EtaTooltips.kt` 调用，业务页面一律用
     * `EtaTooltip`（详见 docs/accessibility-tooltips.md）。
     */
    @Test
    fun `tooltip implementation stays in the adaptive wrapper`() {
        val wrapper = "app/src/main/kotlin/io/github/mangi/eta/ui/adaptive/EtaTooltips.kt"
        val hits = violations(
            Regex("""\b(TooltipBox|PlainTooltip|RichTooltip|rememberTooltipState)\s*\("""),
            allowed = { path -> path.removePrefix("/") == wrapper },
        )
        assertTrue(
            "长按提示只能通过 ui/adaptive/EtaTooltips.kt 的 EtaTooltip / EtaTooltipIconButton 提供，" +
                "业务页面禁止手写 TooltipBox / PlainTooltip —— 详见 docs/accessibility-tooltips.md：\n" +
                hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }
}
