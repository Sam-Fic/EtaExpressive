package io.github.mangi.eta.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import io.github.mangi.eta.agent.terminal.AnsiSgr
import io.github.mangi.eta.agent.terminal.SgrStyle

/** 中性样式到 Compose 的映射；dim 以透明度衰减表达。 */
internal fun SgrStyle.toSpanStyle(): SpanStyle {
    val fgColor = fg?.let { Color(it) }?.let { if (dim) it.copy(alpha = it.alpha * 0.6f) else it }
    return SpanStyle(
        color = fgColor ?: Color.Unspecified,
        background = bg?.let { Color(it) } ?: Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )
}

/**
 * 终端输出的 ANSI 转义渲染：SGR 颜色与字重/斜体/下划线转为 [AnnotatedString] span，
 * 其余控制序列（光标寻址、OSC 等）在块式界面无法表达，直接丢弃。
 * `\r` 按终端语义处理为行覆盖（进度条只保留最后一次刷新）。
 * 流式输出可能在任意位置截断序列；不完整的尾部序列丢弃，下次整段重解析自然恢复。
 */
internal fun ansiToAnnotatedString(text: String): AnnotatedString {
    if (!text.contains(ESC) && !text.contains('\r')) return AnnotatedString(text)
    val out = StringBuilder(text.length)
    val spans = mutableListOf<Triple<Int, Int, SpanStyle>>()
    var style = SgrStyle.PLAIN
    var runStart = 0

    fun flushRun() {
        if (!style.isPlain && out.length > runStart) {
            spans += Triple(runStart, out.length, style.toSpanStyle())
        }
        runStart = out.length
    }

    var i = 0
    // \r 只记录行起点；其后出现新文本才覆盖该行（CRLF 换行不会被误删）。
    // 行起点跟着 out 的写入位置走，不再每次 \r 都 lastIndexOf 回扫——进度条类输出
    // （apt / curl / docker pull）每行都有 \r，回扫会退化成 O(n²)，整段在组合期卡死。
    var lineStart = 0
    var pendingOverwriteFrom = -1

    fun applyPendingOverwrite() {
        if (pendingOverwriteFrom < 0) return
        val overwriteFrom = pendingOverwriteFrom
        pendingOverwriteFrom = -1
        if (out.length > overwriteFrom) {
            out.setLength(overwriteFrom)
            while (spans.isNotEmpty() && spans.last().first >= overwriteFrom) {
                spans.removeAt(spans.lastIndex)
            }
            if (spans.isNotEmpty() && spans.last().second > overwriteFrom) {
                val last = spans.removeAt(spans.lastIndex)
                spans += Triple(last.first, overwriteFrom, last.third)
            }
        }
        runStart = out.length
        // 覆盖后行起点回到被截断处，后续 \r 仍然只回退到本行开头。
        lineStart = out.length
    }

    while (i < text.length) {
        when (text[i]) {
            ESC -> {
                flushRun()
                val result = consumeEscape(text, i, style)
                style = result.style
                i = result.next
            }
            '\r' -> {
                flushRun()
                pendingOverwriteFrom = lineStart
                i++
            }
            '\n' -> {
                pendingOverwriteFrom = -1
                out.append('\n')
                lineStart = out.length
                i++
            }
            else -> {
                applyPendingOverwrite()
                out.append(text[i])
                i++
            }
        }
    }
    flushRun()

    return buildAnnotatedString {
        append(out.toString())
        spans.forEach { (start, end, spanStyle) -> addStyle(spanStyle, start, end) }
    }
}

/** 剥离全部转义序列后的纯文本（复制、日志展示等场景）。 */
internal fun ansiPlainText(text: String): String = ansiToAnnotatedString(text).text

/**
 * 终端块的渲染窗口：整段输出喂给一个 Text 时排版成本随长度线性增长，
 * 流式期间每次刷新都全量重排。渲染只取尾部窗口（终端语义：最新输出才重要），
 * 排版成本与输出总长解耦；完整输出仍由 store 持有，复制不受影响。
 */
internal const val TERMINAL_RENDER_WINDOW_CHARS = 8_000

/**
 * 尾部渲染窗口。窗口起点对齐到换行之后——解析器遇到起点处被截断的转义序列
 * 会把余下内容整个吞掉，所以与 store 层 64K 截断用同一策略：丢掉第一行残片。
 * 窗口内没有换行（单行超长输出）时只能按字符截取，起点可能落在序列中间，
 * 边界条件与 store 层截断一致。
 */
internal fun terminalRenderWindow(text: String, limit: Int = TERMINAL_RENDER_WINDOW_CHARS): String {
    if (text.length <= limit) return text
    val tail = text.takeLast(limit)
    val firstBreak = tail.indexOf('\n')
    // 换行是尾字符时截取会得到空串，仍按字符截取兜底。
    return if (firstBreak in 0 until tail.length - 1) tail.substring(firstBreak + 1) else tail
}

private const val ESC = '\u001B'

private class EscapeResult(val style: SgrStyle, val next: Int)

/** 消费一个转义序列；不完整序列直接消费到文本末尾。 */
private fun consumeEscape(text: String, start: Int, style: SgrStyle): EscapeResult {
    val kind = text.getOrNull(start + 1) ?: return EscapeResult(style, text.length)
    return when (kind) {
        '[' -> {
            var j = start + 2
            while (j < text.length && text[j] !in '@'..'~') j++
            if (j >= text.length) return EscapeResult(style, text.length)
            val newStyle = if (text[j] == 'm') {
                AnsiSgr.apply(text.substring(start + 2, j), style)
            } else {
                style
            }
            EscapeResult(newStyle, j + 1)
        }
        ']' -> {
            var j = start + 2
            while (j < text.length) {
                if (text[j] == '\u0007') {
                    j++
                    break
                }
                if (text[j] == ESC && j + 1 < text.length && text[j + 1] == '\\') {
                    j += 2
                    break
                }
                j++
            }
            EscapeResult(style, j)
        }
        '(', ')', '#', '%' -> EscapeResult(style, (start + 3).coerceAtMost(text.length))
        else -> EscapeResult(style, start + 2)
    }
}
