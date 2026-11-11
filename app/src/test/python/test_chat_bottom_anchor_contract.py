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
        # 闩锁必须同时认真实滚动手势：只有 dragging 时，Lagging 段上翻（dragging 恒 false）
        # 永远置不上闩锁，松手后严格贴底照旧把人拽回末尾。
        self.assertIn('(dragging || userScrolling) && !atBottom', self.state_machine)
        # userScrolling 必须进快照：只读外层状态时「Lagging 段开始滚动」前后三元组不变，
        # distinctUntilChanged 会吞掉这一帧，闩锁置不上。
        self.assertIn('userScrolling = isUserScrolling', self.state_machine)
        # 旧判据：手指按着就算拖动 + 只用一个 atBottom 布尔，流式途中一次轻触即死锁。
        self.assertNotIn('dragging = isUserScrolling || pointerDown.value', self.state_machine)
        self.assertNotIn('atBottom = scrollState.isConversationAtBottom()', self.state_machine)

    def test_only_true_bottom_counts_as_at_bottom_for_anchor(self):
        helper = BODY.split('internal fun resolveChatBottomAnchorGesture(', 1)[1].split(
            'internal fun resolveKeepBottomAnchored(', 1)[0]
        self.assertIn('val detached = proximity == ChatBottomProximity.Detached', helper)
        # 拖动仍只认 Detached：Lagging 段纯轻触不产生滚动手势，不能解锚定（旧 bug 的解）。
        self.assertIn('dragging = detached && (isUserScrolling || pointerDown)', helper)
        # 只有停在静止线才算「在底部」：Lagging 可能是用户上翻未出尾部测量窗。
        self.assertIn('atBottom = proximity == ChatBottomProximity.Anchored', helper)
        self.assertNotIn('atBottom = !detached', helper)

    def test_leaving_bottom_without_returning_stops_following(self):
        policy = BODY.split('internal fun resolveKeepBottomAnchored(', 1)[1].split(
            'internal fun shouldPinConversationToBottom(', 1)[0]
        # 缺这一支时中途上翻会一路沿用 current（true），锚定解不掉。
        self.assertIn('hasLeftBottom -> false', policy)
        # 滑回真底部仍要重锚。
        self.assertIn('isAtBottom && (current || hasLeftBottom) -> true', policy)
        self.assertLess(policy.index('isAtBottom && (current || hasLeftBottom) -> true'),
                        policy.index('hasLeftBottom -> false'))

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
        # 按住冻结（长按选字期间被选中的文本不能动）与速度曲线无关，是允许的。
        self.assertIn('isUserScrolling || pointerDown.value || messageNavigationJob != null', controller)
        self.assertIn('if (!shouldFollowBottom) return@snapshotFlow InactiveBottomFollowLayout', BODY)
        self.assertNotIn('tailLiftMotion', BODY)
        self.assertNotIn('bottomSnapUntilNanos', BODY)

    def test_draw_lift_compensates_the_whole_deficit_in_the_layout_frame(self):
        lift = BODY.split('.then(if (shouldLiftTail) {', 1)[1].split('} else {', 1)[0]
        self.assertIn('val overflow = scrollState.followTailOverflow()', lift)
        self.assertIn('nextHeldTailLift(', lift)
        self.assertIn('pointerDown = isUserScrolling || pointerDown.value || messageNavigationJob != null', lift)
        self.assertNotIn('maxRisePx', lift)
        self.assertNotIn('tailLiftMotion', lift)

    def test_strict_target_policy_is_a_pure_function(self):
        step = (COMPONENTS / 'BottomFollowViewportStep.kt').read_text(encoding='utf-8')
        self.assertIn('internal fun resolveStrictBottomFollowTarget(', step)
        self.assertIn('measuredOverflowPx?.coerceAtLeast(0)?.toFloat()', step)
        self.assertIn('?: queuedPx.coerceAtLeast(0f)', step)


if __name__ == '__main__':
    unittest.main()
