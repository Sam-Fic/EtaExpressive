"""Source contracts for the chat viewport above the measured composer.

Run with Python unittest; no Android SDK is required. These guard the layout
wiring, not rendered Compose geometry, IME animation, or scroll behaviour.
"""
from pathlib import Path
import re
import unittest


def code_only(source):
    # Keep offsets stable while excluding comments and strings from assertions.
    return re.sub(
        r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"',
        lambda match: " " * len(match.group()),
        source,
        flags=re.DOTALL,
    )


def balanced_end(source, start, opening, closing):
    depth = 0
    for index in range(start, len(source)):
        if source[index] == opening:
            depth += 1
        elif source[index] == closing:
            depth -= 1
            if depth == 0:
                return index
    raise AssertionError(f"Unclosed {opening!r} at {start}")


def calls(source, name):
    for match in re.finditer(rf"\b{re.escape(name)}\s*\(", source):
        start = source.index("(", match.start())
        end = balanced_end(source, start, "(", ")")
        yield source[start + 1:end]


class AgentChatViewportContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = Path(__file__).resolve().parents[3]
        cls.source = code_only((
            cls.app / "src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatBody.kt"
        ).read_text(encoding="utf-8"))
        declaration = re.search(
            r"\bfun\s+AgentConversationMessages\s*\(", cls.source
        )
        if declaration is None:
            raise AssertionError("AgentConversationMessages declaration missing")
        start = cls.source.index("(", declaration.start())
        end = balanced_end(cls.source, start, "(", ")")
        cls.parameters = cls.source[start + 1:end]
        body_start = cls.source.index("{", end)
        body_end = balanced_end(cls.source, body_start, "{", "}")
        cls.messages = cls.source[body_start + 1:body_end]
        # Exclude the declaration, so only real call sites are inspected.
        cls.call_sites = cls.source[:declaration.start()] + cls.source[body_end + 1:]

    def test_measured_bottom_inset_reaches_messages_without_caller_padding(self):
        self.assertRegex(self.parameters, r"\bbottomInset\s*:\s*Dp\b")
        self.assertRegex(
            self.source,
            r"\bval\s+bottomPadding\s*=\s*innerPadding\.calculateBottomPadding\s*\(\s*\)",
        )
        message_calls = list(calls(self.call_sites, "AgentConversationMessages"))
        self.assertEqual(len(message_calls), 1, "Expected the Scaffold messages call")
        call = message_calls[0]
        self.assertRegex(call, r"\bbottomInset\s*=\s*bottomPadding\b")
        # The measured composer/IME inset is consumed inside the messages list,
        # never again by its caller. The composer surround is transparent, so
        # the caller no longer records a frosted backdrop layer.
        self.assertNotRegex(call, r"\.padding\s*\(")
        self.assertRegex(call, r"\.fillMaxSize\s*\(\s*\)")
        self.assertNotIn("layerBackdrop", call)

    def test_composer_floats_over_the_full_height_list(self):
        # The composer surround is transparent: the viewport is not shortened,
        # so messages remain visible around and behind the floating composer.
        boxes = [
            call for call in calls(self.messages, "Box")
            if re.search(r"\bmodifier\s*=\s*modifier\b", call)
        ]
        self.assertEqual(len(boxes), 1, "Expected one outer messages Box")
        head = boxes[0].split("{", 1)[0]
        self.assertNotRegex(head, r"\.padding\s*\(")
        self.assertRegex(head, r"\.clipToBounds\s*\(\s*\)")

    def test_following_output_lifts_the_tail_instead_of_clipping_it(self):
        # While following streamed output, the tail is lifted to the 14dp line and
        # also clipped there. Fast output can draw a new line past the measured
        # tail before the lift catches it; the clip keeps that line out of the composer.
        lists = list(calls(self.messages, "LazyColumn"))
        self.assertRegex(
            lists[0],
            r"val overflow = scrollState\.followTailOverflow\(\)",
        )
        self.assertRegex(lists[0], r"nextHeldTailLift\(")
        self.assertLess(lists[0].index('followTailOverflow()'), lists[0].index('nextHeldTailLift('))
        boxes = [
            call for call in calls(self.messages, "Box")
            if re.search(r"\bmodifier\s*=\s*modifier\b", call)
        ]
        draw = boxes[0]
        self.assertRegex(draw, r"if\s*\(\s*!shouldClipTail\s*\)")
        self.assertRegex(
            draw,
            r"size\.height\s*-\s*\(\s*bottomInset\s*\+\s*ConversationComposerGap\s*\)\.toPx\(\)",
        )
        self.assertRegex(draw, r"clipRect\s*\(\s*bottom\s*=\s*restLine")

    def test_inset_is_consumed_by_clip_list_padding_and_navigation(self):
        # Rest clip (remember key + value), follow clip, list padding, navigation.
        self.assertRegex(
            self.messages,
            r"remember\s*\(\s*bottomInset\s*\)\s*\{\s*ComposerRestClip\s*\(\s*bottomInset\s*\+",
        )
        self.assertRegex(
            self.messages,
            r"size\.height\s*-\s*\(\s*bottomInset\s*\+\s*ConversationComposerGap\s*\)",
        )
        self.assertRegex(self.messages, r"bottom\s*=\s*ConversationComposerGap\s*\+\s*bottomInset")
        # 导航钮组以 bottomInset 为基准抬到底栏之上，再对齐最后一轮页脚动作组的中线。
        self.assertRegex(self.messages, r"bottom\s*=\s*bottomInset\s*\+\s*TurnNavigationCenterAboveBottom")
        # The inset is a resting-space budget, not a reason to keep an idle
        # anchored conversation permanently clipped.
        clip_calls = list(calls(self.messages, "shouldClipChatTail"))
        self.assertEqual(len(clip_calls), 1)
        self.assertRegex(clip_calls[0], r"\bisStreaming\s*=\s*isStreaming\b")
        self.assertRegex(clip_calls[0], r"\bisBottomSettling\s*=\s*isBottomSettling\b")
        self.assertRegex(
            clip_calls[0],
            r"navigationActive\s*=\s*messageNavigationJob\s*!=\s*null\s*\|\|\s*scrollToMessageId\s*!=\s*null",
        )

    def test_lazy_column_rests_above_the_composer(self):
        lists = list(calls(self.messages, "LazyColumn"))
        self.assertEqual(len(lists), 1, "Expected one messages LazyColumn")
        paddings = list(calls(lists[0], "PaddingValues"))
        self.assertEqual(len(paddings), 1)
        # The resting line and the streaming clip line are the same constant.
        self.assertRegex(paddings[0], r"\bbottom\s*=\s*ConversationComposerGap\s*\+\s*bottomInset\b")
        self.assertRegex(self.source, r"private\s+val\s+ConversationComposerGap\s*=\s*14\.dp")

    def test_navigation_stays_above_composer(self):
        buttons = list(calls(self.messages, "ConversationTurnNavigationButton"))
        self.assertEqual(len(buttons), 1, "Expected the messages navigation button")
        self.assertRegex(
            buttons[0],
            r"\.align\s*\(\s*Alignment\.BottomEnd\s*\)\s*"
            r"\.padding\s*\(\s*bottom\s*=\s*bottomInset\s*\+\s*TurnNavigationCenterAboveBottom\s*-\s*"
            r"IconButtonDefaults\.smallContainerSize\(\)\.height\s*/\s*2\s*,\s*end\s*=\s*16\.dp(?:\s*\+\s*horizontalPadding)?\s*,?\s*\)",
        )
        self.assertRegex(self.source, r"private\s+val\s+TurnNavigationCenterAboveBottom\s*=\s*39\.dp")

    def test_composer_surround_is_transparent(self):
        bar_start = self.source.index("private fun AgentChatBottomBar(")
        bar = self.source[bar_start:bar_start + 4000]
        self.assertNotIn("colorScheme.surface)", bar.split("AgentChatInputBar(", 1)[0])
        self.assertNotIn("textureBlur", self.source)

    def test_idle_reanchoring_observes_applied_geometry_and_respects_scroll_owners(self):
        anchor_call = self.messages.index("AnchorChatTailOnViewportChange(")
        callback_start = self.messages.index("{", anchor_call)
        callback_end = balanced_end(self.messages, callback_start, "{", "}")
        callback = self.messages[callback_start + 1:callback_end]
        for guard in (
            "currentAnchor.value", "!currentStreaming.value", "!isBottomSettling",
            "!initialBottomPositionPending", "!pointerDown.value", "!currentDragging.value",
            "!isUserScrolling", "messageNavigationJob == null", "currentScrollTarget == null",
        ):
            self.assertIn(guard, callback)
        helper_start = self.source.index("internal fun AnchorChatTailOnViewportChange(")
        helper_end = self.source.index("private suspend fun snapListToBottom(", helper_start)
        helper = self.source[helper_start:helper_end]
        self.assertIn("rememberUpdatedState(canPosition)", helper)
        self.assertIn("LaunchedEffect(scrollState)", helper)
        self.assertIn("info.viewportEndOffset - info.afterContentPadding", helper)
        self.assertIn("previousRestLine != null && previousRestLine != restLine", helper)
        self.assertIn("changed && currentCanPosition()", helper)
        self.assertIn("snapListToBottom(scrollState, currentBottomIndex) { currentCanPosition() }", helper)
        self.assertNotIn("bottomInset", helper)

    def test_bottom_follow_retains_effective_viewport_formula(self):
        # The viewport is full height; subtract its measured composer/IME padding
        # to target the last text line above the floating composer.
        self.assertRegex(
            self.messages,
            r"\bviewportEnd\s*=\s*layoutInfo\.viewportEndOffset\s*"
            r"-\s*layoutInfo\.afterContentPadding\b",
        )

    def test_voice_panel_retains_small_inset_without_caller_padding(self):
        voice_source = code_only((
            self.app / "src/main/kotlin/io/github/mangi/eta/agent/voice/EtaVoicePanel.kt"
        ).read_text(encoding="utf-8"))
        message_calls = list(calls(voice_source, "AgentConversationMessages"))
        self.assertEqual(len(message_calls), 1, "Expected the voice panel messages call")
        self.assertRegex(message_calls[0], r"\bbottomInset\s*=\s*8\.dp\s*,")
        self.assertRegex(
            message_calls[0],
            r"\bmodifier\s*=\s*Modifier\.fillMaxSize\s*\(\s*\)\s*,?\s*$",
        )


if __name__ == "__main__":
    unittest.main()
