"""Source wiring contracts, not Android touch/Compose behavior tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3]
KOTLIN = ROOT / "src/main/kotlin/io/github/mangi/eta/agent"


class AgentOrbDragContractTest(unittest.TestCase):
    def test_ball_has_no_press_indication_but_keeps_click_semantics_and_pulse(self):
        content = (KOTLIN / "overlay/AgentOverlayContent.kt").read_text()
        orb = content.split("private fun AssistantOrb(", 1)[1].split("internal fun AgentOverlayBubble(", 1)[0]
        self.assertNotIn("clickable", orb)
        self.assertNotIn("indication", orb)
        self.assertIn("semanticsClick(label = clickLabel) { onClick(); true }", orb)
        self.assertIn("rememberAgentOrbPulse(phase)", orb)
        self.assertIn("KeyEventType.KeyUp", orb)
        self.assertIn("contentDescription = orbLabel", orb)
        self.assertIn("activationKey.up(event.key.hashCode())", orb)
        self.assertIn("onFocusChanged { if (!it.isFocused) activationKey.cancel() }", orb)

    def test_single_raw_pointer_stream_owns_drag_and_tap(self):
        content = (KOTLIN / "overlay/AgentOverlayContent.kt").read_text()
        self.assertIn("LocalViewConfiguration.current.touchSlop", content)
        self.assertIn("gesture.begin(event.getPointerId(0), event.rawX, event.rawY)", content)
        self.assertIn("gesture.move(event.getPointerId(0), event.rawX, event.rawY)", content)
        self.assertIn("gesture.finish(event.getPointerId(0))", content)
        # 9fef7758a 起取消分支为多行（先停 dragging 再作废手势），钉住分支存在即可。
        self.assertIn("if (event.pointerCount != 1) {", content)
        self.assertIn("MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN", content)
        self.assertIn("onDragStart()", content)
        self.assertNotIn("detectDragGestures", content)

    def test_callbacks_are_wired_to_existing_small_orb_window(self):
        runtime = (KOTLIN / "runtime/AgentRuntimeService.kt").read_text()
        self.assertIn("onDragStart = ::beginOrbDrag", runtime)
        self.assertIn("onDrag = ::handleDrag", runtime)
        self.assertIn("orbDragStart.x + dx, orbDragStart.y + dy", runtime)
        self.assertNotIn("lp.x += dx", runtime)
        orb_params = runtime.split("private fun orbLayoutParams()", 1)[1].split("private fun bubbleLayoutParams()", 1)[0]
        self.assertIn("WRAP_CONTENT", orb_params)
        self.assertIn("FLAG_NOT_TOUCH_MODAL", orb_params)
        self.assertIn("FLAG_NOT_FOCUSABLE", orb_params)
        self.assertNotIn("FLAG_LAYOUT_NO_LIMITS", orb_params)
        self.assertIn("gravity = Gravity.LEFT or Gravity.TOP", orb_params)
        self.assertIn("setFitInsetsTypes(0)", orb_params)
        self.assertIn("LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS", orb_params)

    def test_window_reflow_and_bubble_follow_use_measured_safe_geometry(self):
        runtime = (KOTLIN / "runtime/AgentRuntimeService.kt").read_text()
        self.assertIn("WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()", runtime)
        self.assertIn("override fun onConfigurationChanged(newConfig: Configuration)", runtime)
        self.assertEqual(2, runtime.count("addOnLayoutChangeListener"))
        self.assertIn("val orbWidth = orb.width.takeIf", runtime)
        self.assertIn("val bubbleWidth = bubble.width.takeIf", runtime)
        self.assertIn("AgentOrbPlacement.bubble(", runtime)
        self.assertIn("wm.updateViewLayout(bubble, bubbleLp)", runtime)

    def test_drag_threshold_and_cancel_are_sticky_and_geometry_has_no_end_gravity(self):
        helper = (KOTLIN / "overlay/AgentOrbDrag.kt").read_text()
        self.assertIn("dx * dx + dy * dy > slopSquared", helper)
        self.assertIn("val click = pointerId == id && !dragging", helper)
        self.assertIn("pointerId = null", helper)
        self.assertIn("position.x.coerceIn", helper)
        self.assertIn("right + width <= bounds.right", helper)
        self.assertNotIn("android.", helper)


if __name__ == "__main__":
    unittest.main()
