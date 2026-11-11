package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatScrollPolicyTest {
    @Test
    fun networkCompletionKeepsFollowingUntilRenderedTailSettles() {
        assertTrue(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun draggingInterruptsCompletionFollowing() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = true,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun completionDoesNotPullReaderBackFromHistory() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = false,
                isUserDragging = false,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun completedContentExpansionDoesNotFollowBottom() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun anchoredTailRemainsClippedAfterFastCompletion() {
        assertTrue(
            shouldClipChatTail(
                isStreaming = false,
                isBottomSettling = true,
                keepBottomAnchored = true,
                isUserScrolling = false,
                isUserDragging = false,
                navigationActive = false,
            )
        )
    }

    @Test
    fun idleAnchoredConversationDoesNotHideContentBehindTransparentComposer() {
        assertFalse(shouldClipChatTail(
            isStreaming = false,
            isBottomSettling = false,
            keepBottomAnchored = true,
            isUserScrolling = false,
            isUserDragging = false,
            navigationActive = false,
        ))
    }

    @Test
    fun streamingStillClipsButHistoryNeverDoes() {
        assertTrue(shouldClipChatTail(
            isStreaming = true,
            isBottomSettling = false,
            keepBottomAnchored = true,
            isUserScrolling = false,
            isUserDragging = false,
            navigationActive = false,
        ))
        assertFalse(shouldClipChatTail(
            isStreaming = true,
            isBottomSettling = true,
            keepBottomAnchored = false,
            isUserScrolling = false,
            isUserDragging = false,
            navigationActive = false,
        ))
    }

    @Test
    fun manualScrollingReleasesComposerClip() {
        assertFalse(
            shouldClipChatTail(
                isStreaming = true,
                isBottomSettling = false,
                keepBottomAnchored = true,
                isUserScrolling = true,
                isUserDragging = false,
                navigationActive = false,
            )
        )
        assertFalse(
            shouldClipChatTail(
                isStreaming = true,
                isBottomSettling = false,
                keepBottomAnchored = true,
                isUserScrolling = false,
                isUserDragging = true,
                navigationActive = false,
            )
        )
    }

    @Test
    fun messageNavigationReleasesComposerClip() {
        assertFalse(
            shouldClipChatTail(
                isStreaming = true,
                isBottomSettling = false,
                keepBottomAnchored = true,
                isUserScrolling = false,
                isUserDragging = false,
                navigationActive = true,
            )
        )
    }

    @Test
    fun coveredRouteReleasesComposerClipWhileStreaming() {
        assertFalse(
            shouldClipChatTail(
                isStreaming = true,
                isBottomSettling = false,
                keepBottomAnchored = true,
                isUserScrolling = false,
                isUserDragging = false,
                navigationActive = false,
                routeCovered = true,
            )
        )
        assertTrue(
            shouldClipChatTail(
                isStreaming = true,
                isBottomSettling = false,
                keepBottomAnchored = true,
                isUserScrolling = false,
                isUserDragging = false,
                navigationActive = false,
                routeCovered = false,
            )
        )
    }

    @Test
    fun streamingTailGrowthFollowsBottom() {
        assertTrue(
            resolveBottomFollowEnabled(
                isStreaming = true,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun completedConversationDoesNotUseStreamingInitialJump() {
        assertFalse(
            shouldRequestInitialBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun completedConversationSnapsToBottomOnOpen() {
        assertTrue(
            shouldSnapConversationToBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                hasItems = true,
            )
        )
    }

    @Test
    fun emptyConversationDoesNotSnapToBottom() {
        assertFalse(
            shouldSnapConversationToBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                hasItems = false,
            )
        )
    }

    @Test
    fun scrollToMessageSkipsConversationBottomSnap() {
        assertFalse(
            shouldSnapConversationToBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                hasItems = true,
                scrollToMessageId = "msg-1",
            )
        )
    }

    @Test
    fun conversationBottomSnapAlignsLastItemWithoutAnimation() {
        assertEquals(
            BottomFollowDecision(scrollByPx = -120),
            resolveConversationBottomSnap(
                bottomItemIndex = 8,
                lastVisibleIndex = 8,
                lastVisibleBottom = 880,
                viewportEnd = 1000,
            ),
        )
    }

    @Test
    fun conversationBottomSnapRequestsLastItemWhenNotVisible() {
        assertEquals(
            BottomFollowDecision(requestIndex = 8),
            resolveConversationBottomSnap(
                bottomItemIndex = 8,
                lastVisibleIndex = 2,
                lastVisibleBottom = 400,
                viewportEnd = 1000,
            ),
        )
    }

    @Test
    fun streamingConversationRequestsInitialBottom() {
        assertTrue(
            shouldRequestInitialBottom(
                isStreaming = true,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun contentGrowthDoesNotDisableBottomFollowing() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = false,
                isAtBottom = false,
            )
        )
    }

    @Test
    fun draggingAwayFromBottomDisablesFollowing() {
        assertFalse(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = true,
                isAtBottom = false,
            )
        )
    }

    @Test
    fun draggingWhileAtBottomDisablesFollowing() {
        assertFalse(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = true,
                isAtBottom = true,
            )
        )
    }

    @Test
    fun tinyDragAtBottomDoesNotResumeFollowing() {
        assertFalse(
            resolveKeepBottomAnchored(
                current = false,
                isUserDragging = false,
                isAtBottom = true,
                hasLeftBottom = false,
            )
        )
    }

    @Test
    fun landingOnBottomAfterUserScrollEnablesFollowing() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = false,
                isUserDragging = false,
                isAtBottom = true,
                hasLeftBottom = true,
            )
        )
    }

    @Test
    fun reachingBottomEnablesFollowingAgain() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = false,
                isUserDragging = false,
                isAtBottom = true,
                hasLeftBottom = true,
            )
        )
    }

    @Test
    fun streamingScrollableListDoesNotPinFromBottom() {
        assertFalse(shouldPinConversationToBottom(isStreaming = true, isScrollable = true))
        assertTrue(shouldPinConversationToBottom(isStreaming = true, isScrollable = false))
        assertTrue(shouldPinConversationToBottom(isStreaming = false, isScrollable = true))
    }

    @Test
    fun growingTailOnlyScrollsByTheOverflowDistance() {
        assertEquals(
            BottomFollowDecision(scrollByPx = 24),
            resolveBottomFollowDecision(
                enabled = true,
                bottomItemIndex = 8,
                sentinelBottom = 1024,
                viewportEnd = 1000,
                lastVisibleIndex = 8,
            ),
        )
    }

    @Test
    fun missingBottomSentinelUsesBoundedSmoothFollow() {
        assertEquals(
            BottomFollowDecision(scrollByPx = 1000),
            resolveBottomFollowDecision(
                enabled = true,
                bottomItemIndex = 8,
                sentinelBottom = null,
                viewportEnd = 1000,
                lastVisibleIndex = 6,
            ),
        )
    }

    @Test
    fun disabledFollowingNeverMovesTheList() {
        assertEquals(
            BottomFollowDecision(),
            resolveBottomFollowDecision(
                enabled = false,
                bottomItemIndex = 8,
                sentinelBottom = 1100,
                viewportEnd = 1000,
                lastVisibleIndex = 8,
            ),
        )
    }

    @Test fun hiddenSentinelUsesViewportBoundAndNeverRequestsAnIndex() {
        val decision = resolveBottomFollowDecision(true, 20, null, 600, 4, viewportSizePx = 800)
        assertEquals(BottomFollowDecision(scrollByPx = 800), decision)
        assertEquals(null, decision.requestIndex)
    }

    @Test fun reachedPhysicalEndStopsEvenWithStaleOverflow() {
        assertEquals(BottomFollowDecision(), resolveBottomFollowDecision(true, 20, 900, 600, 20, canScrollForward = false))
    }

    @Test fun emptyListDoesNotStartFollowing() {
        assertEquals(BottomFollowDecision(), resolveBottomFollowDecision(true, 0, null, 600, null))
    }

    @Test fun initialPositionWaitsForContentButAbandonsOnUserNavigation() {
        assertFalse(InitialBottomPosition(0, true, true, false).ready)
        assertFalse(InitialBottomPosition(8, false, true, false).ready)
        assertTrue(InitialBottomPosition(8, true, true, false).shouldPosition)
        assertTrue(InitialBottomPosition(0, false, true, true).ready)
        assertFalse(InitialBottomPosition(8, true, true, true).shouldPosition)
        assertFalse(InitialBottomPosition(8, true, false, false).shouldPosition)
    }

    @Test fun tallSingleItemKeepsPublishingDistanceAfterEachViewport() {
        // Emulate snapshotFlow's equality gate while a 4-screen item stays at
        // the same index. No new network text arrives during the entire drain.
        val motion = BottomFollowMotion()
        var position = 0f
        val end = 3200f
        var pending = 0f
        var previous: BottomFollowLayout? = null
        var requests = 0
        for (frame in 0..1800) {
            val remaining = end - position
            val layout = BottomFollowLayout(
                enabled = true, bottomItemIndex = 8,
                sentinelBottom = if (remaining <= 800f) (800f + remaining).toInt() else null,
                viewportEnd = 800, lastVisibleIndex = if (remaining <= 800f) 8 else 7,
                viewportSizePx = 800, canScrollForward = remaining > 0f,
                lastVisibleOffset = -position.toInt(),
            )
            if (layout != previous) {
                previous = layout
                val decision = resolveBottomFollowDecision(layout.enabled, layout.bottomItemIndex,
                    layout.sentinelBottom, layout.viewportEnd, layout.lastVisibleIndex,
                    layout.viewportSizePx, layout.canScrollForward)
                assertEquals(null, decision.requestIndex)
                pending = decision.scrollByPx.toFloat()
                requests++
            }
            val moved = motion.step(pending, frame * 16_666_667L, 1f)
            position += moved
            pending = (pending - moved).coerceAtLeast(0f)
        }
        assertTrue("fallback must remain live beyond the first viewport", position >= end - 1.1f)
        assertTrue(requests > 10)
    }

    @Test
    fun followLagLiftsTailBackToRestLine() {
        assertEquals(FollowTailLag(37f), resolveFollowTailLag(following = true, tailOverflowPx = 37))
    }

    @Test
    fun followLagIgnoresTailAtOrAboveRestLine() {
        assertEquals(FollowTailLag.None, resolveFollowTailLag(following = true, tailOverflowPx = 0))
        assertEquals(FollowTailLag.None, resolveFollowTailLag(following = true, tailOverflowPx = -12))
    }

    @Test
    fun manualScrollNeitherLiftsNorClips() {
        assertEquals(FollowTailLag.None, resolveFollowTailLag(following = false, tailOverflowPx = 80))
        assertEquals(FollowTailLag.None, resolveFollowTailLag(following = false, tailOverflowPx = null))
    }

    @Test
    fun invisibleTailFallsBackToRestLineClip() {
        val lag = resolveFollowTailLag(following = true, tailOverflowPx = null)
        assertTrue(lag.unknown)
        assertEquals(0f, lag.liftPx, 0f)
    }

    @Test
    fun tailBottomFallsBackToTheLastContentItemWhenTheSentinelIsPushedOut() {
        // 哨兵可见时直接用哨兵。
        assertEquals(900, resolveTailBottomPx(900, 11, 900, totalItems = 12))
        // 展开把哨兵挤出可视区，最后一段内容（倒数第二项）还可见：用它的下沿。
        assertEquals(1400, resolveTailBottomPx(null, 10, 1400, totalItems = 12))
        // 看到的只是更靠上的内容：尾部位置未知。
        assertNull(resolveTailBottomPx(null, 9, 1400, totalItems = 12))
        assertNull(resolveTailBottomPx(null, null, null, totalItems = 12))
        assertNull(resolveTailBottomPx(null, 0, 100, totalItems = 1))
    }

    @Test
    fun userInputScrollWithoutAFingerDownDoesNotCountAsUserScrolling() {
        assertTrue(isUserScrollGesture(pointerDown = true))
        assertFalse(isUserScrollGesture(pointerDown = false))
    }

    @Test
    fun compactStackKeepsOnlyClassAndMethodNames() {
        val frames = arrayOf(
            StackTraceElement("self.Frame", "skipped", null, 1),
            StackTraceElement("kotlin.coroutines.Continuation", "resume", null, 1),
            StackTraceElement("a.b.C\$1", "onPreScroll", null, 1),
            StackTraceElement("x.Y", "run secret", null, 1),
        )
        assertEquals("a.b.C\$1.onPreScroll<x.Y.run", compactStack(frames))
    }

    @Test
    fun drawnTailOverflowIsMeasuredAfterTheLift() {
        assertEquals(0, resolveTailDrawnOverflow(1200, 1000, 200))
        assertEquals(150, resolveTailDrawnOverflow(1150, 1000, 0))
        assertEquals(-20, resolveTailDrawnOverflow(980, 1000, 0))
        assertNull(resolveTailDrawnOverflow(null, 1000, 0))
    }

    @Test
    fun expansionGrowsFromTheBottomOnlyWhenTheBottomStaysPut() {
        // 跟底上提：尾部停在静止线。
        assertTrue(resolveExpansionHoldsBottom(following = true, arrangedToBottom = false, listScrollable = true))
        // 不满一屏贴底排列：列表往上长。
        assertTrue(resolveExpansionHoldsBottom(following = false, arrangedToBottom = true, listScrollable = false))
        // 满屏但没在跟底：首个可见项不动，内容往下长。
        assertFalse(resolveExpansionHoldsBottom(following = false, arrangedToBottom = true, listScrollable = true))
        assertFalse(resolveExpansionHoldsBottom(following = false, arrangedToBottom = false, listScrollable = false))
    }

    @Test
    fun tailLaggingIsNotDetachedFromBottom() {
        assertEquals(ChatBottomProximity.Anchored, resolveChatBottomProximity(true, null))
        // 工具卡片展开 / 快速输出：尾部还在测量窗口里，只是落到静止线下方。
        assertEquals(ChatBottomProximity.Lagging, resolveChatBottomProximity(false, 240))
        assertEquals(ChatBottomProximity.Lagging, resolveChatBottomProximity(false, -40))
        // 尾部彻底离开测量窗口：用户翻到了上方。
        assertEquals(ChatBottomProximity.Detached, resolveChatBottomProximity(false, null))
    }

    @Test
    fun touchWhileTailIsLaggingKeepsTheBottomAnchor() {
        // 纯轻触（点工具卡片、长按选字）不产生滚动事件：isUserScrolling 为 false，
        // 闩锁置不上，锚定也不解除。旧实现把 pointerDown 当拖动才是死锁来源。
        // 注意 atBottom 现在只认真底部，锚定保持靠的是「else 沿用 current」这一支。
        val gesture = resolveChatBottomAnchorGesture(
            proximity = ChatBottomProximity.Lagging,
            isUserScrolling = false,
            pointerDown = true,
        )
        assertFalse(gesture.dragging)
        assertFalse(gesture.atBottom)
        assertTrue(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = gesture.dragging,
                isAtBottom = gesture.atBottom,
                // 无滚动事件 → 闩锁不动（保持 false）。
                hasLeftBottom = false,
            ),
        )
    }

    @Test
    fun userScrollDuringLaggingReleasesTheAnchor() {
        // 生成中上翻、距离还没超过尾部那一行：几何仍是 Lagging，但滚动事件是真的。
        val gesture = resolveChatBottomAnchorGesture(
            proximity = ChatBottomProximity.Lagging,
            isUserScrolling = true,
            pointerDown = true,
        )
        assertFalse(gesture.dragging)
        assertFalse(gesture.atBottom)
        // 闩锁条件：dragging 或真实滚动手势，且不在真底部。
        val latch = (gesture.dragging || true) && !gesture.atBottom
        assertTrue(latch)
        // 手指还按着：解锚定。
        assertFalse(
            resolveKeepBottomAnchored(true, gesture.dragging, gesture.atBottom, hasLeftBottom = latch),
        )
        // 松手后停在半路（仍是 Lagging）：不能凭 current 一路沿用 true 把人拽回末尾。
        val released = resolveChatBottomAnchorGesture(
            proximity = ChatBottomProximity.Lagging,
            isUserScrolling = false,
            pointerDown = false,
        )
        assertFalse(
            resolveKeepBottomAnchored(false, released.dragging, released.atBottom, hasLeftBottom = latch),
        )
        // 用户自己滑回真底部：重锚并清闩锁。
        val back = resolveChatBottomAnchorGesture(ChatBottomProximity.Anchored, false, false)
        assertTrue(
            resolveKeepBottomAnchored(false, back.dragging, back.atBottom, hasLeftBottom = latch),
        )
    }

    @Test
    fun fastStreamingWithoutGestureNeverReleasesTheAnchor() {
        val gesture = resolveChatBottomAnchorGesture(ChatBottomProximity.Lagging, false, false)
        assertTrue(
            resolveKeepBottomAnchored(true, gesture.dragging, gesture.atBottom),
        )
    }

    @Test
    fun scrollingIntoHistoryDetachesAndStaysDetached() {
        val dragging = resolveChatBottomAnchorGesture(ChatBottomProximity.Detached, true, true)
        assertTrue(dragging.dragging)
        assertFalse(dragging.atBottom)
        assertFalse(
            resolveKeepBottomAnchored(true, dragging.dragging, dragging.atBottom, hasLeftBottom = true),
        )
        // 松手后停在中间段：跟底不能把用户拽回去。
        val idle = resolveChatBottomAnchorGesture(ChatBottomProximity.Detached, false, false)
        assertFalse(
            resolveKeepBottomAnchored(false, idle.dragging, idle.atBottom, hasLeftBottom = true),
        )
        // 用户自己滑回底部才恢复。
        val back = resolveChatBottomAnchorGesture(ChatBottomProximity.Anchored, false, false)
        assertTrue(
            resolveKeepBottomAnchored(false, back.dragging, back.atBottom, hasLeftBottom = true),
        )
    }

    @Test
    fun staleLeftBottomFlagRecoversWhenTailIsOnlyLagging() {
        // 真底部（Anchored）这一帧即重锚、闩锁清掉；只是 Lagging（尾部还量得到但不在静止线）
        // 时闩锁继续把人停在半路——这正是「生成中上翻不被拽回」要的行为。
        assertTrue(resolveKeepBottomAnchored(false, false, true, hasLeftBottom = true))
        assertFalse(resolveKeepBottomAnchored(false, false, false, hasLeftBottom = true))
    }

    @Test
    fun expansionReleaseIsNotOverruledByLagging() {
        // 用户主动展开卡片（钉住标题）：没有 hasLeftBottom，没在真底部就不该把锚定拉回来。
        assertFalse(resolveKeepBottomAnchored(false, false, true, hasLeftBottom = false))
    }

    @Test
    fun edgeGestureStillDetachesWithoutPreScroll() {
        // 手势从输入区边缘起手时 onPreScroll 可能收不到，pointerDown 兜底仍要生效。
        val gesture = resolveChatBottomAnchorGesture(ChatBottomProximity.Detached, false, true)
        assertTrue(gesture.dragging)
        assertFalse(gesture.atBottom)
    }
}
