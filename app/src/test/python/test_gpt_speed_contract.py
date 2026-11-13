"""Static wiring guards only; these do not execute Kotlin or render Compose."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / "src/main/kotlin/io/github/mangi/eta"


def source(path):
    return (ROOT / path).read_text(encoding="utf-8")


def between(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


class GptSpeedContractTest(unittest.TestCase):
    def test_owner_action_is_connected_on_the_conversation_screen(self):
        root = source("ui/app/AgentAppRoot.kt")
        self.assertIn("AgentHomeAction.CycleGptSpeedMode -> agentState.cycleGptSpeedMode()", root)
        screen = source("ui/screens/home/AgentHomeScreen.kt")
        self.assertIn("gptSpeedMode = state.gptSpeedMode", screen)
        self.assertIn("CycleGptSpeedMode", screen)

    def test_ui_and_owner_share_binding_eligibility(self):
        picker = source("ui/model/AgentModelPickerUiState.kt")
        self.assertIn("supportsGptSpeedBinding(this, model)", picker)
        bar = source("ui/components/AgentChatInputBar.kt")
        self.assertIn("modelPickerState.selectedModel?.gptSpeedSupported == true", bar)
        state = source("ui/app/AgentAppState.kt")
        self.assertIn("supportsGptSpeedBinding(provider, model)", state)
        self.assertIn("supportsGptSpeedBinding(runProvider, runModel)", state)

    def test_only_model_id_decides_speed_eligibility(self):
        policy = source("data/model/GptSpeedMode.kt")
        self.assertIn("isGptSpeedModel(model.modelId)", policy)
        self.assertIn('startsWith("gpt-", ignoreCase = true)', policy)
        for gate in ("providerType", "endpointMode", "supportsImageGeneration", "outputModalities"):
            self.assertNotIn(gate, policy)

    def test_provider_updates_project_background_and_draft_before_picker(self):
        body = between(source("ui/app/AgentAppState.kt"), "private fun updateSelectionProviders(",
                       "private fun observeRuntimeSelection()")
        self.assertIn("conversationsById.toList().forEach", body)
        self.assertIn("val next = state.withCurrentGptSpeedBinding()", body)
        self.assertIn("next != state ||", body)
        self.assertIn("updateConversation(id, next, updateTimestamp = false)", body)
        self.assertIn("if (selectedConversationId == null) homeState = homeState.withCurrentGptSpeedBinding()", body)
        self.assertNotIn("conversationArchiveBusy", body)

    def test_run_snapshot_is_frozen_before_prepare_coroutine(self):
        state = source("ui/app/AgentAppState.kt")
        tail = state.split("val runConfig = GptSpeedModePolicy.snapshot(", 1)[1]
        self.assertLess(tail.index("rememberedGptSpeedMode(state.providerId, state.modelId)"), tail.index("scope.launch"))
        policy = source("ui/app/GptSpeedModePolicy.kt")
        self.assertIn("config.copy(", policy)
        self.assertIn("isGptSpeedModel(config.model)", policy)
        self.assertNotIn("supportsGptSpeedProtocol", policy)

    def test_tier_applies_after_custom_body_merge_in_both_builders(self):
        for path in ("agent/model/OpenAiChatCompletionsProvider.kt", "agent/model/ResponsesRequestBuilder.kt"):
            body = source(path)
            self.assertLess(body.index("mergeCustomBody"), body.index("GptServiceTier.apply"))

    def test_tier_mapping_and_null_passthrough(self):
        tier = source("agent/model/GptServiceTier.kt")
        self.assertIn("val mode = config.gptSpeedMode ?: return", tier)
        self.assertIn('request.opt("model") as? String ?: return', tier)
        self.assertIn("if (!isGptSpeedModel(actualModel)) return", tier)
        for mode, value in (("NORMAL", "default"), ("FAST", "fast"), ("ULTRA_FAST", "ultrafast")):
            self.assertIn(f'GptSpeedMode.{mode} -> "{value}"', tier)
        self.assertEqual(tier.count("request.put("), 1)

    def test_codex_uses_official_mapping_without_changing_relay_modes(self):
        tier = source("agent/model/GptServiceTier.kt")
        codex = between(
            tier,
            "if (ProviderConnectionProfile.forAuthMode(config.authMode) == ProviderConnectionProfile.OPENAI_CODEX)",
            "} else {",
        )
        self.assertIn("GptSpeedMode.NORMAL -> null", codex)
        self.assertIn('GptSpeedMode.FAST -> "priority"', codex)
        self.assertIn("GptSpeedMode.ULTRA_FAST -> throw AgentModelFailure", codex)
        self.assertNotIn('-> "ultrafast"', codex)
        self.assertIn('if (tier == null) request.remove("service_tier")', tier)

    def test_local_unsupported_codex_tier_bypasses_reconnect(self):
        retry = source("agent/model/AgentModelRetry.kt")
        self.assertIn("if (classified.code == GptServiceTier.UNSUPPORTED_CODEX_TIER) throw classified", retry)
        self.assertLess(retry.index("if (classified.code == GptServiceTier.UNSUPPORTED_CODEX_TIER)"),
                        retry.index('if (classified.code == "CONTEXT_WINDOW_EXCEEDED")'))

    def test_wire_has_symmetric_mode_transfer(self):
        wire = source("agent/runtime/AgentRuntimeWire.kt")
        self.assertIn('KEY_GPT_SPEED_MODE = "gpt_speed_mode"', wire)
        self.assertIn("request.config.gptSpeedMode?.let { putString(KEY_GPT_SPEED_MODE, it.name) }", wire)
        self.assertIn("GptSpeedMode.entries.firstOrNull { it.name == name }", wire)

    def test_speed_is_not_a_reasoning_preference_or_db_field(self):
        state = source("ui/app/AgentAppState.kt")
        body = between(state, "fun cycleGptSpeedMode()", "fun selectModel(")
        self.assertNotIn("updateReasoningEffort", body)
        self.assertNotIn("customBody", body)
        self.assertNotIn("rememberModelReasoningEffort", body)
        for path in ROOT.glob("data/db/*Entities.kt"):
            self.assertNotIn("gptSpeedMode", path.read_text(encoding="utf-8"))

    def test_long_press_gate_and_non_gpt_reset(self):
        bar = source("ui/components/AgentChatInputBar.kt")
        self.assertIn("onClick = openPicker", bar)
        self.assertIn("onLongClick = if (canLongPressSpeed) cycleSpeed else null", bar)
        self.assertLess(bar.index("gate.request()"), bar.index("latestOnCycle()"))
        self.assertIn("gate.finish(token)", bar)
        self.assertIn("val displayedColor = if (gptSpeedSupported) contentColor else normalColor", bar)
        self.assertIn("gate.reset()", bar)
        self.assertIn("progress.snapTo(0f)", bar)

    def test_animation_geometry_and_color_contract(self):
        animation = source("ui/components/ThinkingSpeedAnimation.kt")
        for literal in ("GptSpeedAnimationDurationMillis = 450", "GptSpeedRotationDegreesPerCycle = 360f",
                        "GptSpeedScaleStart = 1f", "GptSpeedScalePeak = 1.12f",
                        "Color(0xFFF2A7BD)", "Color(0xFF800020)"):
            self.assertIn(literal, animation)
        self.assertIn("if (current != 0) return 0", animation)
        self.assertIn("token == current", animation)


if __name__ == "__main__":
    unittest.main()
