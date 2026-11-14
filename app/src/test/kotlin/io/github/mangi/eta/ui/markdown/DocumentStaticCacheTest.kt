package io.github.mangi.eta.ui.markdown

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class DocumentStaticCacheTest {
    /**
     * 测试用中性样式：Default 已删除，颜色必须显式给定（与生产一致走显式传参）。
     * linkColor 用黑，让用例里 `copy(linkColor = Color.Red)` 是一次真实变更——
     * DocumentCache 的 key 按 MarkdownInlineStyle 等值组成，基准与变更色相同会把
     * 两个 key 塌成一个，缓存互相覆盖。
     */
    private val testStyle = MarkdownInlineStyle(linkColor = Color.Black, codeBackground = Color.Gray)

    @get:Rule val compose = createComposeRule()

    @Test fun completedStreamIsAvailableOnFirstStaticCompositionAndRemount() = runBlocking {
        val state = DocumentStreamingState()
        val source = "Static handoff **prepared** answer"
        val style = testStyle
        val worker = launch(start = CoroutineStart.UNDISPATCHED) { state.parseUpdates() }
        try {
            state.submit(source, false, style)
            val snapshot = withTimeout(5_000) {
                while (state.snapshot == null) delay(1)
                checkNotNull(state.snapshot)
            }
            state.acknowledgeLayout(snapshot)
            assertTrue(state.markComplete(snapshot))
            val mounted = mutableStateOf(true)
            val seen = mutableListOf<MarkdownDocument?>()
            compose.setContent {
                if (mounted.value) {
                    val document = rememberStaticMarkdownDocument(source, style).value
                    SideEffect { seen += document }
                }
            }
            compose.runOnIdle {
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it === snapshot.document })
                mounted.value = false
            }
            compose.runOnIdle { seen.clear(); mounted.value = true }
            compose.runOnIdle {
                assertTrue(seen.isNotEmpty())
                assertTrue(seen.all { it === snapshot.document })
            }
        } finally { worker.cancelAndJoin() }
    }

    @Test fun contentAndStyleChangesNeverExposePreviousDocument() {
        val firstSource = "First cached **document**"
        val secondSource = "Second cached **document**"
        val coldSource = "Uncached third **document**"
        val style = testStyle
        val changedStyle = style.copy(linkColor = Color.Red)
        val first = DocumentGfmParserSession().parse(firstSource, true, style).document
        val second = DocumentGfmParserSession().parse(secondSource, true, style).document
        val themed = DocumentGfmParserSession().parse(secondSource, true, changedStyle).document
        DocumentCache.put(firstSource, style, first)
        DocumentCache.put(secondSource, style, second)
        DocumentCache.put(secondSource, changedStyle, themed)
        val target = mutableStateOf(firstSource to style)
        val seen = mutableListOf<MarkdownDocument?>()
        compose.setContent {
            val (source, inline) = target.value
            val document = rememberStaticMarkdownDocument(source, inline).value
            SideEffect { seen += document }
        }
        compose.runOnIdle {
            assertSame(first, seen.first())
            seen.clear(); target.value = secondSource to style
        }
        compose.runOnIdle {
            assertSame(second, seen.first())
            assertTrue(seen.all { it === second })
            seen.clear(); target.value = secondSource to changedStyle
        }
        compose.runOnIdle {
            assertSame(themed, seen.first())
            assertTrue(seen.all { it === themed })
            assertNull(DocumentCache.get(coldSource, changedStyle))
            seen.clear(); target.value = coldSource to changedStyle
        }
        compose.waitUntil(5_000) {
            compose.waitForIdle()
            seen.any { it != null }
        }
        compose.runOnIdle {
            assertTrue(seen.none { it === first || it === second || it === themed })
            val parsed = checkNotNull(seen.last())
            assertEquals(changedStyle, parsed.inlineStyle)
            assertEquals(DocumentGfmParserSession().parse(coldSource, true, changedStyle).document.blocks,
                parsed.blocks)
        }
    }

    @Test fun rawCitationSourceHitsTheCompletedCleanDocument() {
        val raw = "Citation handoff [[1]](https://example.com) **body**"
        val clean = NumericCitationMarkup.strip(raw)
        assertNotEquals(raw, clean)
        val style = testStyle
        val prepared = DocumentGfmParserSession().parse(clean, true, style).document
        DocumentCache.put(clean, style, prepared)
        val seen = mutableListOf<MarkdownDocument?>()
        compose.setContent {
            val document = rememberStaticMarkdownDocument(raw, style).value
            SideEffect { seen += document }
        }
        compose.runOnIdle {
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { it === prepared })
        }
    }

    @Test fun staticCacheStillEvictsLeastRecentlyUsedEntriesAt96Documents() {
        val style = testStyle
        val document = DocumentGfmParserSession().parse("Cache capacity", true, style).document
        repeat(96) { DocumentCache.put("capacity-test-$it", style, document) }
        assertSame(document, DocumentCache.get("capacity-test-0", style))
        DocumentCache.put("capacity-test-96", style, document)
        assertSame(document, DocumentCache.get("capacity-test-0", style))
        assertNull(DocumentCache.get("capacity-test-1", style))
        assertSame(document, DocumentCache.get("capacity-test-96", style))
    }
}
