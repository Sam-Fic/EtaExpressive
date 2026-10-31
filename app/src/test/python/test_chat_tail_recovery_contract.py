from pathlib import Path
import unittest


COMPONENTS = Path(__file__).resolve().parents[4] / 'app/src/main/kotlin/io/github/mangi/eta/ui/components'


class ChatTailRecoveryContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.body = (COMPONENTS / 'AgentChatBody.kt').read_text()
        cls.item = (COMPONENTS / 'ChatMessageItem.kt').read_text()
        cls.controller = cls.body.split(
            'LaunchedEffect(scrollState, bottomFollowDecisions, densityScale) {', 1
        )[1].split('// 输入器悬浮', 1)[0]

    def test_recovery_uses_fresh_uncapped_layout_before_real_scroll(self):
        code = self.controller
        anchors = ['withFrameNanos', 'val layout = scrollState.layoutInfo',
                   'layout.measuredTailOverflow()', 'snapFollowScrollStep(',
                   'scrollState.scroll {', 'scrollBy(currentStep)']
        positions = [code.index(anchor) for anchor in anchors]
        self.assertEqual(sorted(positions), positions)
        # 超出绘制缓冲的差额并进同一条速度曲线，追赶本身也是先加速后减速。
        self.assertIn('catchUpPx = latestOverflow', code)
        self.assertIn('(it - layout.afterContentPadding).coerceAtLeast(0)', code)
        self.assertIn('val followDistancePx = maxOf(remainingDistancePx, catchUpPx.toFloat())', code)
        self.assertIn('motion.step(followDistancePx, frameNanos, densityScale)', code)
        self.assertNotIn('followTailOverflow()', code)

    def test_recovery_keeps_user_and_navigation_interrupt_guards(self):
        code = self.controller
        self.assertEqual(2, code.count(
            'if (!shouldFollowBottom || pointerDown.value || isUserScrolling || messageNavigationJob != null)'))
        scroll = code.split('scrollState.scroll {', 1)[1]
        self.assertLess(scroll.index(
            'if (!pointerDown.value && !isUserScrolling && messageNavigationJob == null && shouldFollowBottom)'),
            scroll.index('scrollBy(currentStep)'))

    def test_touch_hold_is_observable_and_freezes_only_follow_motion(self):
        self.assertIn('val pointerDown = remember { mutableStateOf(false) }', self.body)
        self.assertIn('if (!shouldFollowBottom || pointerDown.value) return@snapshotFlow InactiveBottomFollowLayout', self.body)
        self.assertIn('pointerDown = pointerDown.value,', self.body)
        self.assertIn('pointerDown -> heldPx.coerceAtLeast(0)', self.body)
        self.assertIn('finally {\n                        pointerDown.value = false', self.body)

    def test_drawing_stays_capped_but_recovery_measurement_does_not(self):
        drawing = self.body.split('private fun LazyListState.followTailOverflow(): Int? {', 1)[1]
        drawing = drawing.split('private fun LazyListLayoutInfo.measuredTailOverflow', 1)[0]
        self.assertIn('coerceAtMost(info.afterContentPadding)', drawing)
        raw = self.body.split('private fun LazyListLayoutInfo.measuredTailOverflow(): Int? {', 1)[1]
        raw = raw.split('private data class TailBreachSample', 1)[0]
        self.assertIn('bottom - (viewportEndOffset - afterContentPadding)', raw)
        self.assertNotIn('coerceAtMost', raw)

    def test_pending_list_height_gate_wraps_padding_and_preserves_unmanaged_nodes(self):
        block = self.item.split('private fun ChatMarkdownList(', 1)[1]
        block = block.split('private fun rememberStartedRevealKeys(', 1)[0]
        row = block.split('Row(', 1)[1]
        self.assertIn('streamingListItemLayout(visible = firstRevealKey == null || markerVisible)', row)
        self.assertLess(row.index('.streamingListItemLayout('), row.index('.padding('))
        self.assertIn('alpha = if (markerVisible) 1f else 0f', row)


    def test_follow_step_is_snapped_to_whole_pixels_and_card_ink_stays_inside(self):
        code = self.controller
        call = code.index('snapFollowScrollStep(')
        self.assertLess(call, code.index('scrollState.scroll {', call))
        self.assertLess(call, code.index('scrollBy(currentStep)', call))
        reveal = (COMPONENTS / 'SmoothTextReveal.kt').read_text()
        draw = reveal.split('override fun ContentDrawScope.draw() {', 1)[1].split('private fun ContentDrawScope.drawInsideMeasuredHeight', 1)[0]
        self.assertIn('clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height)', draw)
        card = (COMPONENTS / 'AgentWorkProcessCard.kt').read_text()
        # 卡片改用官方 clip(shape) 收边：旧 borderPath 自绘裁切会把贴边文字切掉，属有 bug 的实现。
        self.assertIn('.clip(shape)', card)
        self.assertNotIn('clipPath(borderPath) { this@onDrawWithContent.drawContent() }', card)
        self.assertNotIn('clipPath(fillPath) { this@onDrawWithContent.drawContent() }', card)

    def test_rest_line_clip_is_a_layer_above_the_follow_translation(self):
        box = self.body.split('输入器悬浮在会话之上', 1)[1].split('LazyColumn(', 1)[0]
        self.assertIn('compositingStrategy = CompositingStrategy.Offscreen', box)
        self.assertIn('shape = restClip', box)
        self.assertLess(box.index('shape = restClip'), box.index('.drawWithContent'))
        self.assertIn('clipRect(bottom = restLine)', box)

if __name__ == '__main__':
    unittest.main()
