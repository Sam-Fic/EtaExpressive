"""Guards for search-hit behaviour: jump into the conversation + highlight the hit word."""
import pathlib
import unittest

APP = pathlib.Path(__file__).resolve().parents[3]
SRC = APP / "src/main/kotlin/io/github/mangi/eta"


class MessageSearchJumpHighlightContractTest(unittest.TestCase):
    def test_hit_click_pops_back_to_the_conversation_host(self):
        root = (SRC / "ui/app/AgentAppRoot.kt").read_text(encoding="utf-8")
        block = root[root.index("entry<AppRoute.MessageSearch>"):
                    root.index("entry<AppRoute.ConversationTokenUsage>")]
        hit = block[block.index("onOpenHit = { hit ->"):block.index("onBack = ::popRoute")]
        # 命中点开后要回到对话本体，而不是只退一层停在管理聊天页。
        self.assertIn("popToConversation()", hit)
        self.assertNotIn("popRoute()", hit)

    def test_pop_to_conversation_helper_uses_the_navigator(self):
        root = (SRC / "ui/app/AgentAppRoot.kt").read_text(encoding="utf-8")
        block = root[root.index("fun popToConversation() {"):root.index("val interceptExitBack")]
        self.assertIn("navigator.popToConversationHost()", block)

    def test_navigator_can_pop_to_home_or_chat(self):
        navigator = (SRC / "ui/navigation/AgentNavigator.kt").read_text(encoding="utf-8")
        self.assertIn("fun popToConversationHost()", navigator)
        block = navigator[navigator.index("fun popToConversationHost()"):]
        self.assertIn("route is AppRoute.Home || route is AppRoute.Chat", block)
        # miuix 版本按 index 一次截断（一次快照内 removeLastOrNull），只播一段转场。
        self.assertIn("backStack.size > target + 1", block)

    def test_both_chat_hosts_receive_the_scroll_target(self):
        root = (SRC / "ui/app/AgentAppRoot.kt").read_text(encoding="utf-8")
        # 首页与独立会话页都要接收滚动锚点，命中才能落在那条消息上。
        self.assertGreaterEqual(
            root.count("scrollToMessageId = agentState.pendingScrollToMessageId"),
            2,
        )
        self.assertGreaterEqual(
            root.count("onScrollToMessageConsumed = agentState::consumePendingScrollToMessage"),
            2,
        )

    def test_search_rows_highlight_the_query(self):
        page = (SRC / "ui/screens/chat/MessageSearchScreen.kt").read_text(encoding="utf-8")
        self.assertIn("highlight = query", page)

    def test_clickable_row_renders_the_highlighted_text(self):
        rows = (SRC / "ui/adaptive/EtaRows.kt").read_text(encoding="utf-8")
        block = rows[rows.index("fun EtaClickableRow("):]
        self.assertIn("highlight: String? = null", block)
        self.assertIn("highlightedText(title, highlight)", block)
        self.assertIn("highlightedText(it, highlight)", block)


if __name__ == "__main__":
    unittest.main()
