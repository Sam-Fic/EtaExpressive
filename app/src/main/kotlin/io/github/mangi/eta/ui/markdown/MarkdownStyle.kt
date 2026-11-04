package io.github.mangi.eta.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaTextStyle

/** 正文与思考共用同一套渲染器，只在字号、颜色与留白上区分层级。 */
internal enum class MarkdownTone { Answer, Thinking }

/** 单级标题的排版规格：字号 / 行高 / 字重。 */
internal data class MarkdownHeadingSpec(
    val fontSize: TextUnit,
    val lineHeight: TextUnit,
    val weight: FontWeight,
)

/** 行级排版规格：字号 / 行高（代码块、表格，不涉字重）。 */
internal data class MarkdownTextSpec(val fontSize: TextUnit, val lineHeight: TextUnit)

/**
 * 聊天 Markdown 的统一档位表：mikepenz 栈（ChatMessageItem.chatMarkdownTypography）与
 * 本地栈（[rememberMarkdownStyle]）共用的唯一真相源；此前两处各抄一份、Thinking 的
 * h1-h2 字重已经分叉。
 *
 * 层级规则：字重只看级别（h1-h2 Bold、h3 起 SemiBold），字号 / 行高只看语气；
 * 标题只是段落强调，不是页面标题。
 */
internal object MarkdownTypeScale {
    /** Answer 语气 h1..h6：20/28、18/26、17/25、16/26 ×3。 */
    val answerHeadings = listOf(
        MarkdownHeadingSpec(20.sp, 28.sp, FontWeight.Bold),
        MarkdownHeadingSpec(18.sp, 26.sp, FontWeight.Bold),
        MarkdownHeadingSpec(17.sp, 25.sp, FontWeight.SemiBold),
        MarkdownHeadingSpec(16.sp, 26.sp, FontWeight.SemiBold),
        MarkdownHeadingSpec(16.sp, 26.sp, FontWeight.SemiBold),
        MarkdownHeadingSpec(16.sp, 26.sp, FontWeight.SemiBold),
    )

    /** Thinking 语气 h1..h6：全部 15/22，字重规则同 Answer。 */
    val thinkingHeadings = listOf(
        MarkdownHeadingSpec(15.sp, 22.sp, FontWeight.Bold),
        MarkdownHeadingSpec(15.sp, 22.sp, FontWeight.Bold),
        MarkdownHeadingSpec(15.sp, 22.sp, FontWeight.SemiBold),
        MarkdownHeadingSpec(15.sp, 22.sp, FontWeight.SemiBold),
        MarkdownHeadingSpec(15.sp, 22.sp, FontWeight.SemiBold),
        MarkdownHeadingSpec(15.sp, 22.sp, FontWeight.SemiBold),
    )

    /** 按语气取标题档位表。 */
    fun headings(answer: Boolean): List<MarkdownHeadingSpec> =
        if (answer) answerHeadings else thinkingHeadings

    /** 代码块字号/行高：Answer 13/20，Thinking 12/18。 */
    fun code(answer: Boolean): MarkdownTextSpec =
        if (answer) MarkdownTextSpec(13.sp, 20.sp) else MarkdownTextSpec(12.sp, 18.sp)

    /** 表格字号/行高：Answer 14/21，Thinking 13/19。 */
    fun table(answer: Boolean): MarkdownTextSpec =
        if (answer) MarkdownTextSpec(14.sp, 21.sp) else MarkdownTextSpec(13.sp, 19.sp)
}

/**
 * 一次渲染所需的全部样式。按主题与语气整体记忆，块级组件只读取不再推导，
 * 避免每个段落各自拼一遍 TextStyle。
 */
@Immutable
internal class MarkdownStyle(
    val tone: MarkdownTone,
    val body: TextStyle,
    private val headings: List<TextStyle>,
    val code: TextStyle,
    val table: TextStyle,
    val textColor: Color,
    val secondaryColor: Color,
    val markerColor: Color,
    val dividerColor: Color,
    val codeBackground: Color,
    val tableHeaderBackground: Color,
    val inline: MarkdownInlineStyle,
) {
    fun withInline(next: MarkdownInlineStyle): MarkdownStyle = MarkdownStyle(
        tone, body, headings, code, table, textColor, secondaryColor, markerColor,
        dividerColor, codeBackground, tableHeaderBackground, next,
    )
    fun heading(level: Int): TextStyle = headings[(level - 1).coerceIn(0, headings.lastIndex)]

    /** 引用块内的弱化样式：文字退到次要色，结构与字号不变。 */
    fun muted(): MarkdownStyle = MarkdownStyle(
        tone = tone,
        body = body.copy(color = secondaryColor),
        headings = headings.map { it.copy(color = secondaryColor) },
        code = code,
        table = table.copy(color = secondaryColor),
        textColor = secondaryColor,
        secondaryColor = secondaryColor,
        markerColor = markerColor,
        dividerColor = dividerColor,
        codeBackground = codeBackground,
        tableHeaderBackground = tableHeaderBackground,
        inline = inline,
    )
}

@Composable
internal fun rememberMarkdownStyle(tone: MarkdownTone): MarkdownStyle {
    // 从官方 colorScheme 取值（浮层上下文也始终有 MaterialTheme 提供者）。
    val onSurface = MaterialTheme.colorScheme.onSurface
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainerHighest
    val body1 = etaTextStyle(EtaType.Body)
    val body2 = etaTextStyle(EtaType.Secondary)
    // 主题值在 remember 之外读取：主题切换时重组并重建样式。
    return remember(tone, onSurface, secondaryColor, primary, outline, surfaceContainer, body1, body2) {
        val answer = tone == MarkdownTone.Answer
        val codeSpec = MarkdownTypeScale.code(answer)
        val tableSpec = MarkdownTypeScale.table(answer)
        val textColor = if (answer) onSurface else secondaryColor
        val body = (if (answer) body1 else body2).copy(
            lineHeight = if (answer) ANSWER_LINE_HEIGHT_SP.sp else THINKING_LINE_HEIGHT_SP.sp,
            color = textColor,
        )
        // 聊天里的标题只是段落强调，不是页面标题：字重只看级别，字号/行高看语气；
        // 档位表与 ChatMessageItem 的 mikepenz 栈同源（MarkdownTypeScale）。
        val headings = MarkdownTypeScale.headings(answer).map {
            body.copy(fontSize = it.fontSize, lineHeight = it.lineHeight, fontWeight = it.weight)
        }
        MarkdownStyle(
            tone = tone,
            body = body,
            headings = headings,
            code = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = codeSpec.fontSize,
                lineHeight = codeSpec.lineHeight,
                color = textColor,
            ),
            table = body.copy(fontSize = tableSpec.fontSize, lineHeight = tableSpec.lineHeight),
            textColor = textColor,
            secondaryColor = secondaryColor,
            markerColor = if (answer) onSurface.copy(alpha = 0.55f) else secondaryColor,
            dividerColor = outline.copy(alpha = 0.6f),
            codeBackground = surfaceContainer,
            tableHeaderBackground = surfaceContainer,
            inline = MarkdownInlineStyle(
                linkColor = primary,
                codeBackground = onSurface.copy(alpha = 0.07f),
            ),
        )
    }
}

/**
 * 相邻块之间的留白。源码中的空行只用于切分块，不直接占据高度；可见间距按语义分配：
 * 段落之间约半行，标题前更宽以开启新段落，标题后收紧使其贴近所属内容。
 */
internal fun markdownBlockGap(previous: MarkdownBlock?, current: MarkdownBlock, tone: MarkdownTone): TextUnit {
    if (previous == null) return 0.sp
    val gap = when {
        current is MarkdownHeading && previous is MarkdownHeading -> 8f
        current is MarkdownHeading -> if (current.level <= 2) 22f else 18f
        previous is MarkdownHeading -> 8f
        previous is MarkdownParagraph && current is MarkdownParagraph -> 12f
        else -> 14f
    }
    val scale = if (tone == MarkdownTone.Answer) 1f else THINKING_GAP_SCALE
    return (gap * scale).sp
}

/** 紧凑列表项内部（例如段落后紧跟子列表）只保留很小的间距。 */
internal fun markdownCompactGap(previous: MarkdownBlock?, tone: MarkdownTone): TextUnit =
    if (previous == null) 0.sp else if (tone == MarkdownTone.Answer) 4.sp else 3.sp

internal const val ANSWER_LINE_HEIGHT_SP = 26
internal const val THINKING_LINE_HEIGHT_SP = 22
private const val THINKING_GAP_SCALE = 0.75f
