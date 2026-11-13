"""Source wiring checks; runtime behavior is covered by AgentRecoveryContextTest."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / "src/main/kotlin/io/github/mangi/eta"

class RecoveryDispatchScopeContractTest(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text()

    def test_new_user_run_normalizes_only_historical_recovery(self):
        builder = self.read("agent/model/AgentPromptBuilder.kt")
        # 历史恢复归一化收敛进 appendHistoryMessage（逐条 put 前 forNewRun），调用点仍在用户消息之前。
        self.assertIn("?.let(AgentRecoveryContext::forNewRun)?.let { put(it) }", builder)
        self.assertLess(builder.index("appendHistoryForModel(history, messages)"),
                        builder.index("messages.put(AgentConversationCodec.userMessage(prompt, images))"))
        for path in ("agent/model/AgentLoop.kt", "agent/model/AgentModelRetry.kt"):
            self.assertNotIn("forNewRun", self.read(path))

    def test_active_run_keeps_transport_restriction(self):
        # 回放守卫已删除（dd7d02455）：重复 tool_call 改为普通调用，靠 tool_call/tool_result 配对保护。
        loop = self.read("agent/model/AgentLoop.kt")
        self.assertIn("private var localOnlyRecovery = AgentRecoveryContext.isActive(messages)", loop)
        self.assertIn("localOnlyRecovery = localOnlyRecovery || completedRound.reconnectLocalToolsOnly", loop)
        self.assertIn("if (localOnlyRecovery) next.copy(hostedWebSearchEnabled = false)", loop)
        payload = self.read("agent/model/AgentProvider.kt")
        self.assertIn('"previous_response_id", "conversation"', payload)
        self.assertIn('functions.put(JSONObject(item.toString()))', payload)

    def test_task_ownership_and_replacement_routing_are_unchanged(self):
        groups = self.read("agent/runtime/AgentChildTaskGroups.kt")
        self.assertIn('if (wantsReplacement) return replace(candidates, current, call, args)', groups)
        self.assertIn('val group = if (id.isNotBlank()) candidates.firstOrNull { owns(it, id) } else current', groups)
        self.assertIn('currentRunId == null || it.runId == currentRunId', groups)

if __name__ == "__main__":
    unittest.main()
