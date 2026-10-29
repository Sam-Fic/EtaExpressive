"""Static contracts for ThinkingRow viewport recovery wiring."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
COMPONENTS = ROOT / "main" / "kotlin" / "io" / "github" / "mangi" / "eta" / "ui" / "components"
CHAT = (COMPONENTS / "ChatMessageItem.kt").read_text(encoding="utf-8")
BODY = (COMPONENTS / "AgentChatBody.kt").read_text(encoding="utf-8")
RECOVERY = (COMPONENTS / "BottomFollowViewportRecovery.kt").read_text(encoding="utf-8")


class ThinkingExpansionViewportContractTest(unittest.TestCase):
    def test_thinking_row_reports_toggle_before_mutating_target(self):
        self.assertIn("onThinkingToggle: ((String, Boolean) -> Unit)? = null", CHAT)
        self.assertIn("onToggle = onThinkingToggle", CHAT)
        callback = CHAT.split("private fun ThinkingRow(", 1)[1].split("// ── 工具调用", 1)[0]
        self.assertIn("onToggle?.invoke(message.id, !expanded)", callback)
        self.assertLess(
            callback.index("onToggle?.invoke(message.id, !expanded)"),
            callback.index("expanded = !expanded"),
        )

    def test_parent_thinking_toggle_releases_the_bottom_anchor(self):
        self.assertIn("onThinkingToggle = if (message is ThinkingMessageUi)", BODY)
        start = BODY.index("val onThinkingRowToggle:")
        end = BODY.index("    Box(", start)
        window = BODY[start:end]
        self.assertIn("if (willExpand) onBottomAnchorChanged(false)", window)

    def test_both_message_and_work_step_report_the_message_id(self):
        message = BODY.split("is AgentTimelineRow.Message -> {", 1)[1].split("is AgentTimelineRow.WorkStep -> {", 1)[0]
        work = BODY.split("is AgentTimelineRow.WorkStep -> {", 1)[1].split("turnFooters[entry.key]", 1)[0]
        self.assertIn("onThinkingToggle = if (message is ThinkingMessageUi)", message)
        self.assertIn("{ _, willExpand -> onThinkingRowToggle(entry.key, willExpand) }", message)
        self.assertIn("onThinkingToggle = if (message is ThinkingMessageUi)", work)
        self.assertIn("{ _, willExpand -> onThinkingRowToggle(message.id, willExpand) }", work)

    def test_normal_follow_rechecks_fresh_overflow_inside_its_scroll_owner(self):
        scroll = BODY.split("scrollState.scroll {", 1)[1].split("if (consumedStep > 0f)", 1)[0]
        self.assertIn("resolveFollowScrollStepAfterRecovery(", scroll)
        self.assertIn("scrollState.layoutInfo.measuredTailOverflow()", scroll)
        self.assertLess(scroll.index("resolveFollowScrollStepAfterRecovery("), scroll.index("scrollBy(currentStep)"))
        self.assertNotIn("scrollBy(step)", scroll)

    def test_active_capture_not_snap_timer_owns_unknown_tail_and_parent_close_cancels_children(self):
        consumer = BODY.split("val expansionOwnsViewport =", 1)[1].split("val step = if (snapping)", 1)[0]
        self.assertIn("viewportRecovery.hasActiveExpansion(canOwnWorkExpansionViewport())", consumer)
        self.assertIn("expansionOwnsViewport && latestOverflow == null", consumer)
        self.assertIn("continue", consumer)
        self.assertNotIn("\n                reset()", consumer)
        self.assertIn("remainingDistancePx = latestOverflow.coerceAtLeast(0).toFloat()", consumer)
        self.assertIn("fun hasActiveExpansion(canOwnViewport: Boolean)", RECOVERY)
        self.assertIn("canOwnViewport && System.nanoTime() < expansion.expiresAtNanos", RECOVERY)

    def test_recovery_uses_the_row_key_and_next_visible_anchor(self):
        self.assertIn("fun beginThinkingExpansion(", RECOVERY)
        self.assertIn("workExpansion = WorkExpansion(rowKey, setOf(rowKey), anchor.key, anchor.offset, expiresAtNanos)", RECOVERY)
        self.assertIn("val row = items.firstOrNull { it.key == rowKey }", RECOVERY)
        self.assertIn("val anchor = items.firstOrNull { it.index > row.index }", RECOVERY)
        self.assertIn("resolveWorkExpansionViewportStep", RECOVERY)
        self.assertIn("viewportRecovery.recover(", BODY)
        self.assertIn("canRecoverExpansion = canOwnWorkExpansionViewport", BODY)


if __name__ == "__main__":
    unittest.main()
