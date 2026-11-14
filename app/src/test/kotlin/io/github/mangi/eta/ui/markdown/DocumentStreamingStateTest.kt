package io.github.mangi.eta.ui.markdown

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Publication/target latch tests; real reveal/layout coverage lives in StreamingRowPinningTest. */
class DocumentStreamingStateTest {
    /**
     * 测试用中性样式：Default 已删除，颜色必须显式给定（与生产一致走显式传参）。
     * linkColor 用黑，让用例里 `copy(linkColor = Color.Red)` 是一次真实变更——
     * DocumentCache 的 key 与完成发布登记都按 MarkdownInlineStyle 等值组成，
     * 基准与变更色相同会把「换主题」塌成重复提交。
     */
    private val testStyle = MarkdownInlineStyle(
        linkColor = androidx.compose.ui.graphics.Color.Black,
        codeBackground = androidx.compose.ui.graphics.Color.Gray,
    )

    private suspend fun awaitSnapshot(state: DocumentStreamingState, source: String,
                                      previous: DocumentSnapshot? = null): DocumentSnapshot =
        withTimeout(5_000) {
            while (true) {
                val value = state.snapshot
                if (value != null && value !== previous && value.originalSource == source) return@withTimeout value
                delay(1)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    @Test fun publishedTargetSurvivesDuplicateSubmitAndWorkerRemount() = runBlocking {
        val state = DocumentStreamingState()
        val source = "Stable **answer**"
        val style = testStyle
        var worker = launch(start = CoroutineStart.UNDISPATCHED) { state.parseUpdates() }
        try {
            state.submit(source, false, style)
            val first = awaitSnapshot(state, source)
            state.acknowledgeLayout(first)
            assertTrue(state.markComplete(first))
            worker.cancelAndJoin()
            repeat(3) { state.submit(source, false, style) }
            worker = launch(start = CoroutineStart.UNDISPATCHED) { state.parseUpdates() }
            delay(100)
            assertSame(first, state.snapshot)
            assertSame(first, state.laidOutSnapshot)
            assertEquals(source, state.completedSourceFor(source, false, style))
            state.submit(source + " changed", false, style)
            state.submit(source, false, style)
            assertNull(state.completedSourceFor(source, false, style))
            assertFalse("Old layout must not complete a reopened target", state.markComplete(first))
            val reopened = awaitSnapshot(state, source, first)
            assertFalse(state.markComplete(reopened))
            state.acknowledgeLayout(reopened)
            assertTrue(state.markComplete(reopened))
        } finally { worker.cancelAndJoin() }
    }

    @Test fun unpublishedIdenticalTargetCanBeSubmittedAgainAfterCancellation() = runBlocking {
        val state = DocumentStreamingState()
        val source = "Still waiting for the parser"
        val style = testStyle
        state.submit(source, false, style)
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { state.parseUpdates() }
        cancelled.cancelAndJoin()
        assertNull("Worker consumed the target but did not publish", state.snapshot)
        state.submit(source, false, style)
        val worker = launch(start = CoroutineStart.UNDISPATCHED) { state.parseUpdates() }
        try {
            assertEquals(source, awaitSnapshot(state, source).originalSource)
        } finally { worker.cancelAndJoin() }
    }

    @Test fun terminalLinkResolutionRequiresExactPublicationLayout() = runBlocking {
        val state = DocumentStreamingState()
        val style = testStyle
        val source = "Stable prefix.\n\n[אבגדה][r]\n\n[r]: https://example.com/a"
        val worker = launch(start = CoroutineStart.UNDISPATCHED) { state.parseUpdates() }
        try {
            state.submit(source, true, style)
            val streaming = awaitSnapshot(state, source)
            assertFalse(streaming.isComplete)
            assertTrue(streaming.document.revealKeys.isNotEmpty())
            state.acknowledgeLayout(streaming)
            assertFalse(state.markComplete(streaming))
            state.submit(source, false, style)
            val terminal = awaitSnapshot(state, source, streaming)
            assertTrue(terminal.isComplete)
            val linked = terminal.document.blocks.filterIsInstance<MarkdownParagraph>()
                .single { it.text.text == "אבגדה" }
            assertEquals(1, linked.text.getLinkAnnotations(0, linked.text.length).size)
            state.acknowledgeLayout(streaming)
            assertNull(state.laidOutSnapshot)
            assertFalse(state.markComplete(terminal))
            assertNull(state.completedSourceFor(source, false, style))
            state.acknowledgeLayout(terminal)
            assertTrue(state.markComplete(terminal))
            val correctedSource = source.replace("example.com/a", "example.com/b")
            state.submit(correctedSource, false, style)
            assertFalse(state.markComplete(terminal))
            val corrected = awaitSnapshot(state, correctedSource, terminal)
            state.acknowledgeLayout(terminal)
            assertNull(state.laidOutSnapshot)
            assertFalse(state.markComplete(corrected))
            state.acknowledgeLayout(corrected)
            assertTrue(state.markComplete(corrected))
            assertEquals(correctedSource, state.completedSourceFor(correctedSource, false, style))
        } finally { worker.cancelAndJoin() }
    }

    @Test fun reusedTerminalPrefixStillRequiresNewSnapshotLayout() = runBlocking {
        val state = DocumentStreamingState()
        val style = testStyle
        val source = "Stable **prefix**.\n\nTail"
        val worker = launch(start = CoroutineStart.UNDISPATCHED) { state.parseUpdates() }
        try {
            state.submit(source, false, style)
            val first = awaitSnapshot(state, source)
            state.acknowledgeLayout(first)
            assertTrue(state.markComplete(first))
            val nextSource = source + " grows"
            state.submit(nextSource, false, style)
            val next = awaitSnapshot(state, nextSource, first)
            assertNotSame(first, next)
            assertSame(first.document.blocks.first(), next.document.blocks.first())
            assertFalse(state.markComplete(first))
            assertFalse(state.markComplete(next))
            assertNull(state.completedSourceFor(nextSource, false, style))
            state.acknowledgeLayout(next)
            assertTrue(state.markComplete(next))
            assertEquals(nextSource, state.completedSourceFor(nextSource, false, style))
        } finally { worker.cancelAndJoin() }
    }

    @Test fun oldCompletionCannotReleaseReopenedSameSourceOrCorrection() = runBlocking {
        val state = DocumentStreamingState()
        val style = testStyle
        val source = "[a]: https://example.com"
        val worker = launch(start = CoroutineStart.UNDISPATCHED) { state.parseUpdates() }
        try {
            state.submit(source, false, style)
            val first = awaitSnapshot(state, source)
            assertTrue(first.document.revealKeys.isEmpty())
            state.acknowledgeLayout(first)
            state.markComplete(first)
            assertEquals(source, state.completedSourceFor(source, false, style))
            state.submit(source, true, style)
            assertNull(state.completedSourceFor(source, false, style))
            val streaming = awaitSnapshot(state, source, first)
            state.submit(source, false, style)
            state.markComplete(first)
            assertNull(state.completedSourceFor(source, false, style))
            val final = awaitSnapshot(state, source, streaming)
            state.markComplete(final)
            assertNull("No completion before this exact publication layout", state.completedRevealSource)
            state.acknowledgeLayout(first)
            assertNull(state.laidOutSnapshot)
            state.acknowledgeLayout(final)
            state.markComplete(final)
            assertEquals(source, state.completedSourceFor(source, false, style))
            state.submit("[b]: https://example.org", false, style)
            assertNull(state.completedSourceFor(source, false, style))
            state.submit(source, false, style)
            assertNull("Returning to an old source is not returning to its completed publication",
                state.completedSourceFor(source, false, style))
        } finally { worker.cancelAndJoin() }
    }

    @Test fun themeChangeCannotCompleteUsingOldPreparedDocument() = runBlocking {
        val state = DocumentStreamingState()
        val source = "[a]: https://example.com"
        val original = testStyle
        val next = original.copy(linkColor = androidx.compose.ui.graphics.Color.Red)
        val worker = launch(start = CoroutineStart.UNDISPATCHED) { state.parseUpdates() }
        try {
            state.submit(source, false, original)
            val old = awaitSnapshot(state, source)
            state.acknowledgeLayout(old)
            state.markComplete(old)
            state.submit(source, false, next)
            state.markComplete(old)
            assertNull(state.completedSourceFor(source, false, next))
            val themed = awaitSnapshot(state, source, old)
            assertEquals(next, themed.document.inlineStyle)
            state.acknowledgeLayout(themed)
            state.markComplete(themed)
            assertEquals(source, state.completedSourceFor(source, false, next))
        } finally { worker.cancelAndJoin() }
    }

    @Test fun onlyExactCompletedDocumentSeedsStaticCache() = runBlocking {
        val state = DocumentStreamingState()
        val source = "Cache handoff **answer** [link][r]\n\n[r]: https://example.com/cache-handoff"
        val style = testStyle
        val changedStyle = style.copy(linkColor = androidx.compose.ui.graphics.Color.Red)
        val worker = launch(start = CoroutineStart.UNDISPATCHED) { state.parseUpdates() }
        try {
            assertNull(DocumentCache.get(source, style))
            state.submit(source, true, style)
            val streaming = awaitSnapshot(state, source)
            state.acknowledgeLayout(streaming)
            assertFalse(state.markComplete(streaming))
            assertNull(DocumentCache.get(source, style))
            state.submit(source, false, style)
            val complete = awaitSnapshot(state, source, streaming)
            assertFalse(state.markComplete(complete))
            assertNull(DocumentCache.get(source, style))
            state.acknowledgeLayout(complete)
            assertTrue(state.markComplete(complete))
            assertSame(complete.document, DocumentCache.get(source, style))
            assertNull(DocumentCache.get(source, changedStyle))
            state.submit(source, false, changedStyle)
            assertFalse(state.markComplete(complete))
            assertNull(DocumentCache.get(source, changedStyle))
            val themed = awaitSnapshot(state, source, complete)
            state.acknowledgeLayout(themed)
            assertTrue(state.markComplete(themed))
            assertSame(themed.document, DocumentCache.get(source, changedStyle))
            assertSame(complete.document, DocumentCache.get(source, style))
        } finally { worker.cancelAndJoin() }
    }

    @Test fun imagesRetainPreviewButAreNotPendingRevealRecords() {
        val document = DocumentGfmParserSession().parse("- ![a](https://example.com/a.png)", true, testStyle).document
        val item = (document.blocks.single() as MarkdownList).items.single()
        assertTrue(item.containsImage)
        assertNull(item.markerRevealKey)
        assertTrue(document.revealKeys.isEmpty())
        assertTrue(listMarkerVisible(true, item.markerRevealKey, emptySet(), item.containsImage))
        assertFalse(listMarkerVisible(true, null, emptySet(), false))
        val mixed = DocumentGfmParserSession().parse(
            "- ![a](https://example.com/a.png)\n\n  Later caption", true, testStyle).document
        val mixedItem = (mixed.blocks.single() as MarkdownList).items.single()
        assertTrue(mixedItem.containsImage)
        assertNull(mixedItem.markerRevealKey)
        assertTrue(listMarkerVisible(true, mixedItem.markerRevealKey, emptySet(), mixedItem.containsImage))
    }
}
