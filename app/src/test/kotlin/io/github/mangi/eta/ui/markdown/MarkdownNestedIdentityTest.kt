package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class MarkdownNestedIdentityTest {
    @Test fun growingListKeepsPrefixItemsAndNestedLeaves() {
        val parser = DocumentGfmParserSession()
        val source = "- Stable **first**\n- Outer\n  - Nested stable\n  - Tail"
        val first = parser.parse(source, false, MarkdownInlineStyle(linkColor = Color.Red, codeBackground = Color.Gray)).document.blocks.single() as MarkdownList
        val nextSource = source + " growing"
        val next = parser.parse(nextSource, false, MarkdownInlineStyle(linkColor = Color.Red, codeBackground = Color.Gray)).document
        val list = next.blocks.single() as MarkdownList
        assertSame(first.items.first(), list.items.first())
        assertNotSame(first.items.last(), list.items.last())
        assertSame(first.items.last().blocks.first(), list.items.last().blocks.first())
        val beforeNested = first.items.last().blocks.filterIsInstance<MarkdownList>().single()
        val afterNested = list.items.last().blocks.filterIsInstance<MarkdownList>().single()
        assertSame(beforeNested.items.first(), afterNested.items.first())
        assertNotSame(beforeNested.items.last(), afterNested.items.last())
        val cold = DocumentGfmParserSession().parse(nextSource, false, MarkdownInlineStyle(linkColor = Color.Red, codeBackground = Color.Gray)).document
        assertEquals(cold.blocks, next.blocks)
        assertEquals(cold.revealKeys, next.revealKeys)
    }

    @Test fun editsLinksAndStyleStillMatchColdParser() {
        val sources = listOf(
            "- [ ] Task\n- [label][ref]\n\n[ref]: https://example.com/a",
            "- [x] Task\n- [label][ref]\n\n[ref]: https://example.com/b",
            "1. First\n\n2. Second\n\n   > Quoted\n   > tail",
            "1. First\n\n2. Second\n\n   > Quoted\n   > tail extended",
        )
        val parser = DocumentGfmParserSession()
        for (complete in listOf(false, true)) for (source in sources) {
            val warm = parser.parse(source, complete, MarkdownInlineStyle(linkColor = Color.Red, codeBackground = Color.Gray)).document
            val cold = DocumentGfmParserSession().parse(source, complete, MarkdownInlineStyle(linkColor = Color.Red, codeBackground = Color.Gray)).document
            assertEquals(cold.blocks, warm.blocks)
            assertEquals(cold.revealKeys, warm.revealKeys)
        }
        val style = MarkdownInlineStyle(linkColor = Color.Red, codeBackground = Color.Gray)
        val warm = parser.parse(sources[1], true, style).document
        assertEquals(DocumentGfmParserSession().parse(sources[1], true, style).document.blocks, warm.blocks)
    }
}
