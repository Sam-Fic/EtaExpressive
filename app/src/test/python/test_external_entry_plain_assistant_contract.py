"""No-build source contracts: external voice/assistant entries never run as the dispatcher.

The dispatcher identity ("只调度不执行") is only valid for the main-UI dispatch console
session and the runs it spawns. System voice assistant, XiaoAi and Breeno entries have no
conversation context, so inheriting the global active assistant would silently turn a
one-line ordinary chat into a task_dispatch round. These contracts pin the fallback to a
plain assistant at all three layers (repository / composer / runtime service).
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SRC = ROOT / "app/src/main/kotlin/io/github/mangi/eta"


def method(text, name):
    match = re.search(r"^    (?:private |override )?(?:suspend )?fun " + re.escape(name) + r"\(", text, re.M)
    assert match, name
    end = re.search(r"^    (?:private |override )?(?:suspend )?fun ", text[match.end():], re.M)
    return text[match.start():match.end() + end.start()] if end else text[match.start():]


class ExternalEntryPlainAssistantContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repository = (SRC / "data/repository/AssistantRepository.kt").read_text()
        cls.composer = (SRC / "agent/voice/EtaAssistantComposerState.kt").read_text()
        cls.service = (SRC / "agent/runtime/AgentRuntimeService.kt").read_text()
        cls.app = (SRC / "ui/app/AgentAppState.kt").read_text()

    def test_single_repository_rule_excludes_dispatcher(self):
        body = method(self.repository, "plainActiveId")
        self.assertIn("active().id", body)
        self.assertIn("AssistantPrompt.DISPATCHER_ID", body)
        self.assertIn("AssistantPrompt.DEFAULT_ID", body)
        self.assertIn('it.id != AssistantPrompt.DISPATCHER_ID', body)
        self.assertIn("fun plainActive(): AssistantProfile", self.repository)

    def test_voice_composer_starts_and_stays_on_a_plain_assistant(self):
        self.assertIn("var assistantId by mutableStateOf(AssistantRepository.plainActiveId())", self.composer)
        self.assertIn("AssistantRepository.plainActiveId()", self.composer)
        body = method(self.composer, "assistantProfile")
        self.assertIn("AssistantPrompt.DISPATCHER_ID", body)
        self.assertIn("AssistantRepository.plainActive()", body)
        self.assertNotIn("AssistantRepository.active()", body)
        select = method(self.composer, "selectAssistant")
        self.assertIn("id == AssistantPrompt.DISPATCHER_ID", select)

    def test_runtime_normalizes_external_entries_before_start_run(self):
        start = method(self.service, "startRun")
        self.assertIn("withPlainAssistantForExternalEntry()", start)
        body = method(self.service, "AgentRuntimeWire.RunRequest.withPlainAssistantForExternalEntry")
        # Hard boundary: main UI dispatch console and its spawned runs keep their real identity.
        self.assertIn("AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE", body)
        self.assertIn("AgentRuntimeWire.ETA_DISPATCH_HANDOFF_SOURCE", body)
        self.assertIn("AssistantPrompt.DISPATCHER_ID", body)
        # All three identity carriers must be rewritten together.
        self.assertIn("assistantId = plain.id", body)
        self.assertIn("config = config.copy(", body)
        self.assertIn("systemPrompt = AssistantPrompt.build(plain.name, plain.prompt)", body)

    def test_main_ui_new_conversation_shares_the_same_rule(self):
        body = method(self.app, "currentBoundAssistantId")
        self.assertIn("AssistantRepository.plainActiveId()", body)
        self.assertNotIn("DISPATCHER_ID", body)


if __name__ == "__main__":
    unittest.main()
