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
                   'layout.measuredTailOverflow()', 'resolveStrictBottomFollowTarget(',
                   'snapFollowScrollStep(', 'scrollState.scroll {', 'scrollBy(currentStep)']
        positions = [code.index(anchor) for anchor in anchors]
        self.assertEqual(sorted(positions), positions)
        # 严格贴底：本帧实测差额就是唯一目标，一次滚满，不留跨帧速度曲线。
        self.assertIn(
            'remainingDistancePx = resolveStrictBottomFollowTarget(remainingDistancePx, latestOverflow)', code)
        self.assertIn('val step = snapFollowScrollStep(remainingDistancePx, remainingDistancePx)', code)
        self.assertNotIn('motion.step(', code)
        self.assertNotIn('catchUpPx', code)
        self.assertNotIn('BottomFollowMotion()', code)
        self.assertNotIn('followTailOverflow()', code)
        # 亚像素残量必须归零，否则控制器会在同一个不到 1px 的差额上逐帧空转。
        self.assertIn('if (step <= 0f) {', code)
        self.assertIn('remainingDistancePx = 0f', code)

    def test_recovery_keeps_user_and_navigation_interrupt_guards(self):
        code = self.controller
        # 滚动追平的两处守卫都含同一组中断：用户滚动 / 手指按住（长按选字）/ 消息导航。
        self.assertEqual(2, code.count(
            'if (!shouldFollowBottom || isUserScrolling || pointerDown.value || messageNavigationJob != null)'))
        scroll = code.split('scrollState.scroll {', 1)[1]
        self.assertLess(scroll.index(
            'if (!isUserScrolling && messageNavigationJob == null && shouldFollowBottom)'),
            scroll.index('scrollBy(currentStep)'))

    def test_touch_hold_freezes_follow_and_lift_together(self):
        self.assertIn('val pointerDown = remember { mutableStateOf(false) }', self.body)
        self.assertIn('if (!shouldFollowBottom) return@snapshotFlow InactiveBottomFollowLayout', self.body)
        self.assertNotIn('if (!shouldFollowBottom || pointerDown.value) return@snapshotFlow', self.body)
        self.assertIn('pointerDown = pointerDown.value,', self.body)
        self.assertIn('pointerDown -> heldPx.coerceAtLeast(0)', self.body)
        self.assertIn('finally {\n                        pointerDown.value = false', self.body)
        # 绘制层一次补满，冻结条件为「真实滚动 / 手指按住 / 导航」，不再有缓动预算；
        # 按住期间上提停在当前 heldPx（nextHeldTailLift 的 pointerDown 分支不累积），
        # 溢出由裁剪层兜住，松手后滚动控制器按实测差额一次追平。
        lift = self.body.split('.then(if (shouldLiftTail) {', 1)[1].split('} else {', 1)[0]
        self.assertIn('val overflow = scrollState.followTailOverflow()', lift)
        self.assertIn('nextHeldTailLift(', lift)
        self.assertIn('pointerDown = isUserScrolling || pointerDown.value || messageNavigationJob != null', lift)
        self.assertNotIn('maxRisePx', lift)
        self.assertNotIn('tailLiftMotion', lift)

    def test_easing_motion_is_not_used_by_the_follow_pipeline(self):
        self.assertNotIn('val tailLiftMotion = remember { createTailLiftMotion() }', self.body)
        self.assertNotIn('bottomSnapUntilNanos', self.body)
        self.assertNotIn('EXPANSION_BOTTOM_SNAP_NANOS', self.body)
        self.assertNotIn('motion.step(followDistancePx, frameNanos, densityScale)', self.body)

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
