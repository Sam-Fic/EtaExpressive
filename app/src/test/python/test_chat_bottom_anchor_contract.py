"""贴底锚定的静态契约：尾部滞后不能被当成用户翻到上方、折叠后要还回锚定。"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[4]
COMPONENTS = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/components'
BODY = (COMPONENTS / 'AgentChatBody.kt').read_text(encoding='utf-8')
ITEM = (COMPONENTS / 'ChatMessageItem.kt').read_text(encoding='utf-8')
QUESTION = (COMPONENTS / 'AgentQuestionCard.kt').read_text(encoding='utf-8')


class ChatBottomAnchorContractTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.state_machine = BODY.split('var hasLeftBottom by remember', 1)[1].split(
            'var isBottomSettling by remember', 1)[0]

    def test_follow_state_uses_three_way_bottom_proximity(self):
        self.assertIn('proximity = scrollState.chatBottomProximity()', self.state_machine)
        self.assertIn('dragging = gesture.dragging', self.state_machine)
        self.assertIn('atBottom = gesture.atBottom', self.state_machine)
        # 旧判据：手指按着就算拖动 + 只用一个 atBottom 布尔，流式途中一次轻触即死锁。
        self.assertNotIn('dragging = isUserScrolling || pointerDown.value', self.state_machine)
        self.assertNotIn('atBottom = scrollState.isConversationAtBottom()', self.state_machine)

    def test_tail_lagging_is_treated_as_still_at_bottom(self):
        helper = BODY.split('internal fun resolveChatBottomAnchorGesture(', 1)[1].split(
            'internal fun resolveKeepBottomAnchored(', 1)[0]
        self.assertIn('val detached = proximity == ChatBottomProximity.Detached', helper)
        self.assertIn('dragging = detached && (isUserScrolling || pointerDown)', helper)
        self.assertIn('atBottom = !detached', helper)

    def test_three_way_policy_separates_lagging_from_detached(self):
        policy = BODY.split('internal enum class ChatBottomProximity', 1)[1].split(
            '/** 传给锚定策略的两个布尔', 1)[0]
        self.assertIn('isAtBottom -> ChatBottomProximity.Anchored', policy)
        self.assertIn('tailOverflowPx != null -> ChatBottomProximity.Lagging', policy)
        self.assertIn('else -> ChatBottomProximity.Detached', policy)

    def test_proximity_reads_layout_only_once(self):
        helper = BODY.split('private fun LazyListState.chatBottomProximity()', 1)[1].split(
            'private fun LazyListState.isConversationAtBottom()', 1)[0]
        self.assertIn('val items = info.visibleItemsInfo', helper)
        self.assertIn('resolveTailBottomPx(', helper)
        self.assertIn('BottomAnchorTolerancePx', helper)

    def test_collapsing_a_card_restores_the_bottom_anchor(self):
        toggle = BODY.split('val onThinkingRowToggle:', 1)[1].split('    Box(', 1)[0]
        self.assertIn('if (willExpand) onBottomAnchorChanged(false)', toggle)
        self.assertIn(
            'else if (!hasLeftBottom && !isUserScrolling) onBottomAnchorChanged(true)', toggle)

    def test_anchor_release_reports_expand_and_collapse(self):
        self.assertIn('staticCompositionLocalOf<(Boolean) -> Unit>', ITEM)
        self.assertIn('LocalChatAnchorRelease provides { willExpand ->', BODY)
        self.assertIn('releaseAnchor(!userTextExpanded)', ITEM)
        self.assertIn('releaseAnchor(!isExpanded)', ITEM)
        self.assertIn('releaseAnchor(!expanded)', QUESTION)

    def test_follow_is_a_strict_anchor_not_a_smooth_chase(self):
        """严格贴底：本帧全额补偿，不留任何「滚动慢慢追上来」的速度曲线。"""
        controller = BODY.split(
            'LaunchedEffect(scrollState, bottomFollowDecisions, densityScale) {', 1
        )[1].split('// 输入器悬浮', 1)[0]
        self.assertIn('resolveStrictBottomFollowTarget(', controller)
        self.assertIn('val step = snapFollowScrollStep(remainingDistancePx, remainingDistancePx)', controller)
        self.assertNotIn('BottomFollowMotion()', controller)
        self.assertNotIn('motion.step(', controller)
        self.assertNotIn('pointerDown.value', controller)
        self.assertIn('if (!shouldFollowBottom) return@snapshotFlow InactiveBottomFollowLayout', BODY)
        self.assertNotIn('tailLiftMotion', BODY)
        self.assertNotIn('bottomSnapUntilNanos', BODY)

    def test_draw_lift_compensates_the_whole_deficit_in_the_layout_frame(self):
        lift = BODY.split('.then(if (shouldLiftTail) {', 1)[1].split('} else {', 1)[0]
        self.assertIn('val overflow = scrollState.followTailOverflow()', lift)
        self.assertIn('nextHeldTailLift(', lift)
        self.assertIn('pointerDown = isUserScrolling || messageNavigationJob != null', lift)
        self.assertNotIn('maxRisePx', lift)
        self.assertNotIn('tailLiftMotion', lift)

    def test_strict_target_policy_is_a_pure_function(self):
        step = (COMPONENTS / 'BottomFollowViewportStep.kt').read_text(encoding='utf-8')
        self.assertIn('internal fun resolveStrictBottomFollowTarget(', step)
        self.assertIn('measuredOverflowPx?.coerceAtLeast(0)?.toFloat()', step)
        self.assertIn('?: queuedPx.coerceAtLeast(0f)', step)


if __name__ == '__main__':
    unittest.main()
