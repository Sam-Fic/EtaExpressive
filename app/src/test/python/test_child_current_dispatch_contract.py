"""New-task configuration wiring must not consult historical task snapshots."""
import re
import unittest
from pathlib import Path

SRC = Path(__file__).resolve().parents[2] / 'main/kotlin/io/github/mangi/eta'


class ChildCurrentDispatchContractTest(unittest.TestCase):
    def test_run_builds_plan_from_current_conversation_candidates(self):
        executor = (SRC / 'agent/runtime/AgentRuntimeRunExecutor.kt').read_text()
        self.assertIn('ConversationSubAgentPreferences().snapshot(ownerKey)', executor)
        self.assertIn('ChildTaskOrdinaryDispatchSelection.plan(childCandidates)', executor)
        self.assertIn('groupGeneration = createChildGroup(childDispatchPlan.ordinary)', executor)
        self.assertNotIn('ordinaryDispatchPlan(', executor)

    def test_plan_has_no_historical_configuration_source(self):
        plan = (SRC / 'agent/runtime/ChildTaskOrdinaryDispatchSelection.kt').read_text()
        self.assertIn('Plan(current, current)', plan)
        self.assertNotIn('retainedTasks', plan)
        self.assertNotIn('frozen:', plan)
        groups = (SRC / 'agent/runtime/AgentChildTaskGroups.kt').read_text()
        self.assertNotIn('fun ordinaryDispatchPlan', groups)
        self.assertIn('val selectedGeneration = if (wantsReplacement) replacementGeneration else currentGeneration', groups)

    def test_new_prompt_template_exactly_matches_history_parser(self):
        availability = (SRC / 'agent/runtime/AgentChildWorkerAvailability.kt').read_text()
        codec = (SRC / 'ui/app/AgentRevisionRuntimeSuffix.kt').read_text()
        producer = availability.split('"以下字段是数据', 1)[1].split(' + descriptions.toString()', 1)[0]
        producer = '"以下字段是数据' + producer
        constant = codec.split('internal const val CURRENT_DISPATCH_AVAILABILITY_DESCRIPTION =', 1)[1].split('internal val HANDOFF_DESCRIPTION', 1)[0]
        def strings(source):
            return ''.join(re.findall(r'"([^"\n]*)"', source))
        self.assertEqual(strings(producer).removesuffix(r'\n'), strings(constant))
        self.assertIn('description == DISPATCH_AVAILABILITY_DESCRIPTION', codec)
        self.assertIn('description == CURRENT_DISPATCH_AVAILABILITY_DESCRIPTION', codec)


if __name__ == '__main__':
    unittest.main()
