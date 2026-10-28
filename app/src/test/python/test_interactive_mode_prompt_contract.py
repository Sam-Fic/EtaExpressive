"""Exact source-wiring guards; dynamic behavior is covered by AgentInteractiveModePromptTest.

This file intentionally does not modify the independent preference/menu contract or infer
runtime behavior from source checks. The Kotlin tests capture real multi-round requests.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


def code_mask(text):
    """Keep offsets while ignoring literals/comments in balanced Kotlin call matching."""
    pattern = r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(pattern, lambda match: ''.join('\n' if c == '\n' else ' ' for c in match.group()), text)


def argument_groups(text, expression):
    masked = code_mask(text)
    groups = []
    for match in re.finditer(expression + r'\s*\(', masked):
        opening = match.end() - 1
        depth = 1
        closing = opening + 1
        while depth and closing < len(masked):
            if masked[closing] == '(':
                depth += 1
            elif masked[closing] == ')':
                depth -= 1
            closing += 1
        if depth:
            raise AssertionError('Unbalanced Kotlin call/signature')
        groups.append((match.start(), masked[opening + 1:closing - 1]))
    return groups


class InteractiveModePromptContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.builder = (ROOT / 'agent/model/AgentPromptBuilder.kt').read_text()
        cls.client = (ROOT / 'agent/model/AgentModelClient.kt').read_text()
        cls.overhead = (ROOT / 'agent/model/AgentRequestOverhead.kt').read_text()
        cls.runtime = (ROOT / 'agent/runtime/AgentRuntimeRunExecutor.kt').read_text()
        cls.child = (ROOT / 'agent/delegation/SubAgentRunner.kt').read_text()

    def assert_forwarded_once(self, arguments):
        self.assertEqual(1, len(re.findall(
            r'\binteractiveModeEnabled\s*=\s*interactiveModeEnabled\b', arguments)))
        self.assertEqual(2, len(re.findall(r'\binteractiveModeEnabled\b', arguments)))
        self.assertNotIn('InteractiveModePreference', arguments)

    def test_false_is_the_declared_default_for_pure_prompt_builders_and_client(self):
        for text, name in ((self.builder, 'buildInitialMessages'),
                           (self.builder, 'buildSystemMessages'), (self.client, 'complete')):
            signatures = argument_groups(text, r'\bfun\s+' + name)
            self.assertEqual(1, len(signatures))
            self.assertRegex(signatures[0][1], r'interactiveModeEnabled\s*:\s*Boolean\s*=\s*false\s*,')
        self.assertNotIn('InteractiveModePreference', code_mask(self.builder))
        self.assertNotIn('InteractiveModePreference', code_mask(self.client))

    def test_every_production_prompt_call_explicitly_forwards_the_frozen_value(self):
        # Exact inventory catches a newly added call missing the parameter, not just one nearby match.
        expected = {
            'agent/model/AgentPromptBuilder.kt': ['buildSystemMessages'],
            'agent/model/AgentModelClient.kt': ['buildInitialMessages', 'buildSystemMessages'],
            'agent/model/AgentRequestOverhead.kt': ['buildSystemMessages'],
        }
        actual = {}
        expression = r'\b(?:AgentPromptBuilder\.)?(buildInitialMessages|buildSystemMessages)'
        for path in ROOT.rglob('*.kt'):
            text = path.read_text()
            masked = code_mask(text)
            calls = []
            for position, arguments in argument_groups(text, expression):
                if re.search(r'\bfun\s*$', masked[:position]):
                    continue
                method = re.match(expression, masked[position:]).group(1)
                calls.append(method)
                self.assert_forwarded_once(arguments)
            if calls:
                actual[path.relative_to(ROOT).as_posix()] = calls
        self.assertEqual(expected, actual)
        rebuild = self.client.split('toolsForRound = {', 1)[1]
        self.assertEqual(1, len(argument_groups(rebuild, r'AgentPromptBuilder\.buildSystemMessages')))
        self.assertNotIn('InteractiveModePreference', rebuild)

    def test_parent_runtime_reads_once_at_entry_and_only_main_complete_receives_it(self):
        runtime = code_mask(self.runtime)
        reads = argument_groups(self.runtime, r'InteractiveModePreference\.read')
        self.assertEqual(1, len(reads))
        self.assertRegex(runtime, r'val\s+interactiveModeEnabled\s*=\s*InteractiveModePreference\.read\(\)')
        self.assertGreater(reads[0][0], runtime.index('fun execute('))
        self.assertLess(reads[0][0], runtime.index('var result = try'))
        self.assertLess(reads[0][0], runtime.index('fun runTextChild('))
        parent_calls = argument_groups(self.runtime, r'AgentModelClient\.complete')
        self.assertEqual(1, len(parent_calls))
        self.assert_forwarded_once(parent_calls[0][1])
        self.assertEqual(3, len(re.findall(r'\binteractiveModeEnabled\b', runtime)))
        child_calls = argument_groups(self.runtime, r'SubAgentRunner\.run')
        self.assertEqual(2, len(child_calls))
        for _, arguments in child_calls:
            self.assertNotIn('interactiveModeEnabled', arguments)
            self.assertNotIn('InteractiveModePreference', arguments)
        child_setup = runtime.split('fun runTextChild(', 1)[1].split('val questionCoordinator =', 1)[0]
        self.assertNotIn('interactiveModeEnabled', child_setup)
        self.assertNotIn('InteractiveModePreference', child_setup)
        self.assertNotIn('InteractiveModePreference', code_mask(self.child))
        self.assertNotIn('interactiveModeEnabled', code_mask(self.child))
        self.assertNotIn('AgentPromptBuilder', code_mask(self.child))
        self.assertIn('return AgentLoop(', self.child)

    def test_overhead_reads_once_per_estimate_and_forwards_real_prompt_policy(self):
        signatures = argument_groups(self.overhead, r'\bfun\s+estimate')
        self.assertEqual(1, len(signatures))
        self.assertRegex(signatures[0][1],
                         r'interactiveModeEnabled\s*:\s*Boolean\s*=\s*InteractiveModePreference\.read\(\)\s*,\s*$')
        self.assertEqual(1, len(argument_groups(self.overhead, r'InteractiveModePreference\.read')))
        calls = argument_groups(self.overhead, r'AgentPromptBuilder\.buildSystemMessages')
        self.assertEqual(1, len(calls))
        self.assert_forwarded_once(calls[0][1])
        self.assertIn('AgentWireRequestEstimate.previewBody(config, systemMessages, tools)', self.overhead)

    def test_switch_is_prompt_only_and_catalog_question_contract_stays_unconditional(self):
        for text in (self.client, self.overhead, self.runtime):
            calls = argument_groups(text, r'AgentToolCatalog\.build')
            self.assertEqual(1, len(calls))
            for _, arguments in calls:
                self.assertNotIn('interactiveModeEnabled', arguments)
                self.assertNotIn('InteractiveModePreference', arguments)
        client_tools = self.client.split('fun toolsFor(', 1)[1].split('val tools = toolsFor(initialCapabilities)', 1)[0]
        overhead_tools = self.overhead.split('val tools = AgentToolCatalog.build(', 1)[1].split('onProtocolPreview?.let', 1)[0]
        for tool_assembly in (client_tools, overhead_tools):
            self.assertNotIn('interactiveModeEnabled', code_mask(tool_assembly))
            self.assertNotIn('InteractiveModePreference', code_mask(tool_assembly))
        catalog = (ROOT / 'agent/model/AgentToolCatalog.kt').read_text()
        # ask_user follows the basic-interaction group switch, never the interactive-mode switch.
        self.assertRegex(catalog, r'if \(basicTools\) AgentQuestionToolCatalog\.appendTo\(emitted\)')
        self.assertNotIn('interactiveModeEnabled', catalog)
        self.assertNotIn('InteractiveModePreference', catalog)
        question = (ROOT / 'agent/model/AgentQuestionToolCatalog.kt').read_text()
        self.assertIn('const val NAME = "ask_user"', question)
        self.assertNotIn('interactiveModeEnabled', question)
        self.assertNotIn('InteractiveModePreference', question)
        whitelist = (ROOT / 'agent/delegation/SubAgentTools.kt').read_text().split(
            'private val readOnly = setOf(', 1)[1].split('fun allows(', 1)[0]
        self.assertNotIn('"ask_user"', whitelist)
        for text in (self.builder, self.client, self.overhead):
            self.assertNotIn('AgentTaskSurface.save', text)
            self.assertNotIn('Prefs.putBoolean', text)
        self.assertEqual(1, self.builder.count('interactiveModePromptClause(interactiveModeEnabled)'))
        # The clause closes the base guidance message: screen/terminal guidance now lives in
        # group-conditional blocks, so the tail before it is the conditional's `else ""`.
        self.assertIn('} else "") +\n                    interactiveModePromptClause(interactiveModeEnabled)', self.builder)


if __name__ == '__main__':
    unittest.main()
