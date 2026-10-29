"""Source wiring guards; runtime layer/height behavior is covered by RetainedCardDrawLayerTest.

These do not measure RenderThread/GPU frame times or replace on-device visual/selection checks.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/components'


class ChatDrawLayerDedupContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.chat = (SOURCE / 'ChatMessageItem.kt').read_text()
        cls.layer = cls.chat.split(
            'internal fun AnimatedVisibilityScope.retainDrawLayerWhenIdle(', 1
        )[1].split('internal fun tailDetailsExit(', 1)[0]
        cls.tool = cls.chat.split('private fun ToolActivityInline(', 1)[1].split(
            'private fun BrowserPagePreview(', 1
        )[0]

    def test_thinking_selection_uses_one_jointly_gated_layer(self):
        self.assertIn(
            'HapticSelectionContainer(\n'
            '                    modifier = retainDrawLayerWhenIdle(enabled = streamingState == null)\n'
            '                        .toggleProbe(toggleProbeRef, "content"),',
            self.chat,
        )
        self.assertNotIn('completedContentDrawLayer(retainDrawLayerWhenIdle(', self.chat)
        self.assertIn('enabled: Boolean = true', self.layer)
        self.assertIn('transition.currentState == EnterExitState.Visible', self.layer)
        self.assertIn('transition.targetState == EnterExitState.Visible', self.layer)
        self.assertIn(
            'val cache = enabled && settled && heightPx in 1..MAX_RETAINED_LAYER_HEIGHT_PX',
            self.layer,
        )

    def test_single_layer_and_height_observer_are_unconditional(self):
        self.assertIn('private const val MAX_RETAINED_LAYER_HEIGHT_PX = 8192', self.chat)
        self.assertEqual(1, self.layer.count('.onSizeChanged { heightPx = it.height }'))
        self.assertEqual(1, self.layer.count('.graphicsLayer('))
        self.assertIn('return Modifier\n        .onSizeChanged', self.layer)
        self.assertIn('CompositingStrategy.Offscreen', self.layer)
        self.assertIn('CompositingStrategy.Auto', self.layer)
        self.assertNotIn('return if', self.layer)
        self.assertNotIn('.then(', self.layer)
        self.assertNotIn('graphicsLayer {', self.layer)  # keep the stable parameter-form element

    def test_tool_details_render_inside_the_segmented_child_item(self):
        details = self.tool.split('val toolDetails', 1)[1]
        self.assertNotIn('retainDrawLayerWhenIdle(', details)
        # 详情子条目由 AgentWorkSegmentedBubble 提供（官方 segmentedShapes 外壳），
        # 不再自绘 miuix 时代的 squircleSurface 面板，也不再有 WorkCardExpandedBody。
        self.assertNotIn('WorkCardExpandedBody', details)
        self.assertNotIn('squircleSurface(', details)
        self.assertNotIn('MiuixTheme', self.chat)
        self.assertNotIn('tailDetailsEnter', self.tool)
        self.assertIn('HapticSelectionContainer(modifier = Modifier.toggleProbe(toggleProbeRef, "content"))', details)
        self.assertIn('HapticSelectionContainer {', details)
        # 留帧层只有一层，包在气泡本身上（完成后的工具行离屏缓存）。
        self.assertEqual(1, details.count('completedContentDrawLayer('))
        self.assertIn('.let { completedContentDrawLayer(it, toolRowSettled) }', details)


if __name__ == '__main__':
    unittest.main()
