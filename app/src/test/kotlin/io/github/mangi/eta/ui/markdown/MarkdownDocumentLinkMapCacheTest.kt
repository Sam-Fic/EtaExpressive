package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.*
import org.junit.Test

class MarkdownDocumentLinkMapCacheTest {
    /** 测试用中性样式：Default 已删除，颜色必须显式给定（与生产一致走显式传参）。 */
    private val testStyle = MarkdownInlineStyle(linkColor = Color.Red, codeBackground = Color.Gray)

    private fun assertCold(actual: DocumentSnapshot, source: String, complete: Boolean,
                           style: MarkdownInlineStyle = testStyle) {
        val expected = DocumentGfmParserSession().parse(source, complete, style)
        assertEquals(expected.originalSource, actual.originalSource)
        assertEquals(expected.renderedSource, actual.renderedSource)
        assertEquals(expected.isComplete, actual.isComplete)
        assertEquals(expected.document.inlineStyle, actual.document.inlineStyle)
        assertEquals(expected.document.blocks, actual.document.blocks)
        assertEquals(expected.document.revealKeys, actual.document.revealKeys)
    }

    @Test fun independentBlocksSurviveTerminalAndLateTail() {
        val parser = DocumentGfmParserSession()
        val source = "# Heading\n\nPlain **bold** and `code`.\n\n> Quote body.\n\n" +
            "- one\n- two\n\n```kotlin\nval n = 1\n```\n\n" +
            "| A | B |\n| --- | --- |\n| one | two |\n\nTail"
        val streaming = parser.parse(source, false, testStyle)
        val stable = streaming.document.blocks.dropLast(1)
        assertTrue(stable.any { it is MarkdownHeading })
        assertTrue(stable.any { it is MarkdownParagraph })
        assertTrue(stable.any { it is MarkdownQuote })
        assertTrue(stable.any { it is MarkdownList })
        assertTrue(stable.any { it is MarkdownCode })
        assertTrue(stable.any { it is MarkdownTable })
        val terminal = parser.parse(source, true, testStyle)
        stable.forEachIndexed { i, block -> assertSame(block, terminal.document.blocks[i]) }
        assertCold(terminal, source, true)
        val lateSource = source + " grows"
        val late = parser.parse(lateSource, true, testStyle)
        stable.forEachIndexed { i, block -> assertSame(block, late.document.blocks[i]) }
        assertNotSame(terminal.document.blocks.last(), late.document.blocks.last())
        assertCold(late, lateSource, true)
    }

    private fun firstLink(snapshot: DocumentSnapshot): String {
        val text = (snapshot.document.blocks[1] as MarkdownTextBlock).text
        return (text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url).url
    }
    private fun firstImage(snapshot: DocumentSnapshot): String {
        val text = (snapshot.document.blocks[2] as MarkdownTextBlock).text
        return text.getStringAnnotations(DOCUMENT_IMAGE_TAG, 0, text.length).single().item
    }

    @Test fun referencesAndImagesRefreshForNewAndRemovedLinkMaps() {
        val parser = DocumentGfmParserSession()
        val source = "Plain prefix.\n\n[guide][id]\n\n![alt][img]\n\nTail\n\n" +
            "[id]: https://example.test/one\n[img]: https://example.test/one.png\n"
        val streaming = parser.parse(source, false, testStyle)
        val terminal = parser.parse(source, true, testStyle)
        assertSame(streaming.document.blocks.first(), terminal.document.blocks.first())
        for (i in 1..2) assertNotSame(streaming.document.blocks[i], terminal.document.blocks[i])
        assertEquals("https://example.test/one", firstLink(terminal))
        assertEquals("https://example.test/one.png", firstImage(terminal))
        assertCold(terminal, source, true)
        val correctedSource = source.replace("example.test/one", "example.test/two")
        val corrected = parser.parse(correctedSource, true, testStyle)
        assertSame(terminal.document.blocks.first(), corrected.document.blocks.first())
        for (i in 1..2) assertNotSame(terminal.document.blocks[i], corrected.document.blocks[i])
        assertEquals("https://example.test/two", firstLink(corrected))
        assertEquals("https://example.test/two.png", firstImage(corrected))
        assertCold(corrected, correctedSource, true)
        val reopened = parser.parse(correctedSource, false, testStyle)
        for (i in 1..2) assertNotSame(corrected.document.blocks[i], reopened.document.blocks[i])
        assertCold(reopened, correctedSource, false)
    }

    @Test fun bracketsStayConservativeAndNestedReferencesRefresh() {
        for (body in listOf("Literal \\[brackets].", "> [guide][id]", "- [guide][id]",
                "| A | B |\n| --- | --- |\n| [guide][id] | cell |")) {
            val parser = DocumentGfmParserSession()
            val source = "$body\n\n[id]: https://example.test/one\n"
            val first = parser.parse(source, true, testStyle)
            val changedSource = source.replace("example.test/one", "example.test/two")
            val changed = parser.parse(changedSource, true, testStyle)
            assertNotSame(first.document.blocks.first(), changed.document.blocks.first())
            assertCold(changed, changedSource, true)
        }
    }

    @Test fun styleAndSourceChangesStillInvalidate() {
        val parser = DocumentGfmParserSession()
        val source = "Plain **bold** and `code`.\n\nTail"
        val first = parser.parse(source, true, testStyle)
        val style = testStyle.copy(codeBackground = Color.Green)
        val themed = parser.parse(source, true, style)
        assertNotSame(first.document.blocks.first(), themed.document.blocks.first())
        assertCold(themed, source, true, style)
        val correctedSource = source.replace("Plain", "Fixed")
        val corrected = parser.parse(correctedSource, true, style)
        assertNotSame(themed.document.blocks.first(), corrected.document.blocks.first())
        assertCold(corrected, correctedSource, true, style)
    }
}
