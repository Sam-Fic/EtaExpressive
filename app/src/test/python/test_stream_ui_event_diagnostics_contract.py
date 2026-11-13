"""Bounded source wiring contracts for UI stream diagnostics only.

No Android/Gradle dependency. These assert labels, gates and callback/order wiring;
not device timings, rendered performance, bytecode allocations or runtime equivalence.
"""
from pathlib import Path
import re
import unittest


KOTLIN = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


def masked(source):
    # Stable offsets let structural assertions ignore braces in comments/strings.
    return re.sub(
        r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"',
        lambda match: ' ' * len(match.group()), source, flags=re.DOTALL,
    )


def end_at(source, start, opening, closing):
    depth = 0
    for index in range(start, len(source)):
        if source[index] == opening:
            depth += 1
        elif source[index] == closing:
            depth -= 1
            if depth == 0:
                return index
    raise AssertionError(f'Unclosed {opening} at {start}')


def function(source, name):
    code = masked(source)
    match = re.search(rf'\bfun\s+(?:<[^>]*>\s+)?{re.escape(name)}\s*\(', code)
    if match is None:
        raise AssertionError(f'Missing function {name}')
    opening = code.index('(', match.start())
    params_end = end_at(code, opening, '(', ')')
    start = code.index('{', params_end)
    return source[start + 1:end_at(code, start, '{', '}')]


def timed_block(source, label):
    match = re.search(rf'StreamUiEventDiagnostics\.measure\("{re.escape(label)}"', source)
    if match is None:
        raise AssertionError(f'Missing timing {label}')
    code = masked(source)
    opening = code.index('(', match.start())
    params_end = end_at(code, opening, '(', ')')
    start = code.index('{', params_end)
    return source[start + 1:end_at(code, start, '{', '}')]


def ordered(test, source, *tokens):
    positions = [source.index(token) for token in tokens]
    test.assertEqual(positions, sorted(positions), tokens)
    test.assertEqual(len(positions), len(set(positions)), tokens)


class StreamUiEventDiagnosticsContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = (KOTLIN / 'ui/app/AgentAppState.kt').read_text(encoding='utf-8')
        cls.helper = (KOTLIN / 'ui/app/StreamUiEventDiagnostics.kt').read_text(encoding='utf-8')
        cls.events = (KOTLIN / 'agent/runtime/AgentEvent.kt').read_text(encoding='utf-8')

    def test_exhaustive_event_mapping_uses_only_constant_non_sensitive_labels(self):
        mapping = function(self.helper, 'eventStage')
        groups = {
            'AssistantBlockStart': {'start.text', 'start.thinking', 'start.toolCall'},
            'AssistantBlockDelta': {'delta.text', 'delta.thinking', 'delta.toolCall'},
            'AssistantBlockEnd': {'end.text', 'end.thinking', 'end.toolCall'},
            'RunStarted': {'runStarted'}, 'GuiControlChanged': {'guiControl'},
            'DecisionFallback': {'decisionFallback'}, 'RoundStarted': {'roundStarted'},
            'ModelRetryScheduled': {'modelRetry'}, 'ErrorReconnectChanged': {'reconnect'},
            'ProviderRequestStarted': {'providerRequest'},
            'ProviderResponseStarted': {'providerResponse'},
            'AssistantReceived': {'assistantReceived'}, 'ChildContextUpdated': {'childContext'},
            'UsageReceived': {'usage.projected', 'usage.receipt'},
            'UserSupplementReceived': {'supplement'},
            'DispatchCreated': {'dispatchCreated'},
            'ToolStarted': {'toolStarted'}, 'ToolFinished': {'toolFinished'},
            'HostedToolStarted': {'hostedToolStarted'}, 'HostedToolFinished': {'hostedToolFinished'},
            'ToolImagesAttached': {'toolImages'}, 'AutoCompactWaiting': {'compactWaiting'},
            'ContextCompactionStarted': {'compactStarted'}, 'ContextCompacted': {'compacted'},
            'RunFinished': {'runFinished'}, 'RunFailed': {'runFailed'},
            'QuestionRequested': {'questionRequested'}, 'QuestionResolved': {'questionResolved'},
        }
        self.assertEqual(set(re.findall(r'\bdata class (\w+)\(', self.events)), set(groups))
        branches = list(re.finditer(r'^        is AgentEvent\.(\w+) ->', mapping, re.MULTILINE))
        self.assertEqual([m.group(1) for m in branches], list(groups))
        for index, branch in enumerate(branches):
            stop = branches[index + 1].start() if index + 1 < len(branches) else len(mapping)
            labels = set(re.findall(r'"ui\.event\.([A-Za-z.]+)"', mapping[branch.start():stop]))
            self.assertEqual(labels, groups[branch.group(1)])
        # Only finite enum/boolean discriminants may be read; never names/IDs/payloads.
        self.assertEqual(set(re.findall(r'(?<![\w.])event\.(\w+)', mapping)), {'kind', 'projected'})
        self.assertNotIn('$', mapping)
        self.assertNotRegex(masked(mapping), r'\belse\s*->|javaClass|::class|toLogLine|toString')

    def test_new_stage_family_is_bounded_to_46_and_has_no_mutable_registry(self):
        event_labels = set(re.findall(r'"(ui\.event\.[A-Za-z.]+)"', self.helper))
        stages = set(re.findall(r'StreamUiEventDiagnostics\.measure\("([^"]+)"', self.app))
        stages.update(re.findall(r'diagnosticStage = "([^"]+)"', self.app))
        expected = {
            'ui.flush.timer', 'ui.flush.blockSwitch', 'ui.flush.nonDelta',
            'ui.messages.transform', 'ui.messages.normalize', 'ui.messages.publish',
            'ui.conversation.route', 'ui.conversation.waiting',
            'ui.conversation.owner', 'ui.conversation.publish', 'ui.summaries.refresh',
        }
        self.assertEqual(stages, expected)
        self.assertEqual(len(event_labels), 35)
        self.assertLessEqual(len(event_labels | stages), 46)
        self.assertNotRegex(masked(self.helper), r'\bvar\b|mutableMapOf|mutableSetOf|ThreadLocal')
        for api in ('System.nanoTime', 'AndroidAgentLogger', 'Trace.', 'record(', 'note('):
            self.assertNotIn(api, masked(self.helper))

    def test_new_wrappers_inline_the_disabled_path_before_label_selection(self):
        measure = function(self.helper, 'measure')
        event = function(self.helper, 'measureEvent')
        self.assertRegex(self.helper, r'inline fun <T> measure\(')
        self.assertRegex(self.helper, r'inline fun <T> measureEvent\(')
        self.assertEqual(self.helper.count('crossinline block: () -> T'), 3)
        self.assertIn('if (stage == null || !StreamPerformanceDiagnostics.enabled) return block()', measure)
        self.assertIn('if (!StreamPerformanceDiagnostics.enabled) return block()', event)
        ordered(self, measure, '!StreamPerformanceDiagnostics.enabled', 'StreamPerformanceDiagnostics.measure(')
        ordered(self, event, '!StreamPerformanceDiagnostics.enabled', 'eventStage(event)')
        for wrapper in (measure, event):
            self.assertEqual(wrapper.count('return block()'), 1)
            self.assertEqual(wrapper.count('{ block() }'), 1)

    def test_original_event_and_messages_aggregates_keep_exactly_one_callback(self):
        event = function(self.app, 'applyRunEvent')
        self.assertIn('"ui.runEvent"', event)
        self.assertIn('if (event is AgentEvent.AssistantBlockDelta) 1L else 0L', event)
        ordered(self, event, '"ui.runEvent"', 'StreamUiEventDiagnostics.measureEvent(event)',
                'applyRunEventNow(runId, event, persistSupplement, replaying)')
        self.assertEqual(event.count('applyRunEventNow('), 1)
        messages = function(self.app, 'updateMessages')
        self.assertIn('"ui.messages.apply", state.messages.size.toLong()', messages)
        ordered(self, messages, '"ui.messages.apply"', '"ui.messages.transform"',
                'transform(state.messages)', 'if (normalizeTerminalOrder)',
                '"ui.messages.normalize"', 'runReplayBatch.normalize(runId, projected)',
                '"ui.messages.publish"', 'updateConversationProjected(')
        for callback in ('transform(state.messages)', 'runReplayBatch.normalize(', 'updateConversationProjected('):
            self.assertEqual(messages.count(callback), 1)
        self.assertIn('state = state.copy(messages = nextMessages)', messages)
        self.assertIn('recomputeWaitingQuestion = recomputeWaitingQuestion', messages)
        self.assertNotRegex(masked(messages), r'==|!=|\.map\b|\.filter\b|\.count\b|\.forEach\b|\.any\b')
        delta = function(self.app, 'applyRunEventBody').split('is AgentEvent.AssistantBlockDelta ->', 1)[1].split(
            'is AgentEvent.AssistantBlockEnd ->', 1)[0]
        self.assertIn('normalizeTerminalOrder = false', delta)
        self.assertIn('recomputeWaitingQuestion = false', delta)

    def test_run_event_budget_waits_for_next_frame_once_a_drain_is_scheduled(self):
        enqueue = function(self.app, 'enqueueRunEventBudgeted')
        ordered(self, enqueue, '.offer(event)', 'if (runId !in runEventBudgetCallbacks)',
                'drainRunEventBudget(runId, force = false)')
        self.assertIn('Choreographer.getInstance().postFrameCallback(callback)',
                      function(self.app, 'scheduleRunEventBudgetDrain'))

    def test_pending_flush_reasons_do_not_reorder_or_duplicate_applications(self):
        enqueue = function(self.app, 'enqueueRunEventNow')
        ordered(self, enqueue, 'runEventCoalescer.append(runId, event)', '"ui.flush.blockSwitch"',
                'applyRunEvent(runId, ready)', 'scheduleRunDeltaFlush(runId)',
                'flushPendingRunDelta(runId, diagnosticStage = "ui.flush.nonDelta")',
                'applyRunEvent(runId, event)')
        self.assertEqual(enqueue.count('runEventCoalescer.append('), 1)
        self.assertEqual(enqueue.count('applyRunEvent(runId, ready)'), 1)
        self.assertEqual(enqueue.count('applyRunEvent(runId, event)'), 1)
        flush = function(self.app, 'flushPendingRunDelta')
        self.assertRegex(self.app, r'fun flushPendingRunDelta\(\s*runId: String,\s*diagnosticStage: String\? = null,\s*drainQueuedEvents: Boolean = true,?\s*\)')
        ordered(self, flush, 'drainRunEventBudget(runId, force = true)',
                'runEventFlushJobs.remove(runId)?.cancel()',
                'runEventCoalescer.flush(runId)?.let', 'StreamUiEventDiagnostics.measure(diagnosticStage)',
                'applyRunEvent(runId, event)')
        self.assertEqual(flush.count('runEventCoalescer.flush('), 1)
        self.assertEqual(flush.count('applyRunEvent('), 1)
        result = function(self.app, 'applyRunResult')
        self.assertIn('flushPendingRunDelta(runId)', result)
        self.assertNotIn('diagnosticStage', result)
        ordered(self, result, 'flushPendingRunDelta(runId)', 'runJobs.remove(runId)', 'updateRunTrace(runId)')
        self.assertIn('flushPendingRunDelta(runId)', function(self.app, 'restoreRunEvents'))

    def test_timer_clocks_are_gated_without_changing_delay_or_job_order(self):
        timer = function(self.app, 'scheduleRunDeltaFlush')
        self.assertIn('val scheduledAtNs = if (StreamPerformanceDiagnostics.enabled) System.nanoTime() else null', timer)
        self.assertIn('if (scheduledAtNs != null && StreamPerformanceDiagnostics.enabled)', timer)
        self.assertEqual(timer.count('System.nanoTime()'), 2)
        ordered(self, timer, 'if (runEventFlushJobs[runId]?.isActive == true) return',
                'runEventFlushJobs[runId] = scope.launch', 'delay(STREAM_UI_UPDATE_INTERVAL_MS)',
                'runEventFlushJobs.remove(runId)', '"ui.flush"',
                'flushPendingRunDelta(', 'diagnosticStage = "ui.flush.timer",', 'drainQueuedEvents = false')
        self.assertEqual(timer.count('delay('), 1)

    def test_conversation_substages_keep_the_existing_scan_and_projection_order(self):
        update = function(self.app, 'updateConversationProjected')
        ordered(self, update, 'val previous = conversationState(conversationId)',
                '"ui.conversation.route"', 'contextRouteSignature(state)',
                'val ownerContext = ownerContexts[conversationId]', 'ownerContext?.projection()',
                'if (recomputeWaitingQuestion)', '"ui.conversation.waiting"',
                'AgentQuestionProjection.hasWaiting(projected.messages)', 'ownerContext.roster()',
                '"ui.conversation.publish"', 'conversationsById =',
                'conversationCreatedAt =', 'conversationUpdatedAt =', 'homeState = current',
                'if (previous?.isStreaming != state.isStreaming)', 'refreshConversationSummaries()')
        for callback in ('contextRouteSignature(', 'ownerContext?.projection()',
                         'AgentQuestionProjection.hasWaiting(', 'ownerContext.roster()'):
            self.assertEqual(update.count(callback), 1)
        self.assertEqual(update.count('"ui.conversation.owner"'), 2)
        route = timed_block(update, 'ui.conversation.route')
        self.assertIn('if (modelChanged) runConversationIds.filterValues', route)
        waiting = timed_block(update, 'ui.conversation.waiting')
        self.assertIn('projected.copy(isWaitingForAnswer = AgentQuestionProjection.hasWaiting(projected.messages))', waiting)
        # The original conditional filter is the sole collection scan besides hasWaiting.
        self.assertEqual(re.findall(r'\.(filterValues|map|filter|count|any|forEach)\b', masked(update)),
                         ['filterValues', 'forEach'])
        self.assertNotRegex(masked(update), r'\.messages\s*(?:==|===|!=)|(?:==|===|!=)\s*\w+\.messages')

    def test_summary_refresh_is_measured_once_after_apply_and_keeps_replay_gate(self):
        trace = function(self.app, 'updateRunTrace')
        ordered(self, trace, 'updateMessages(runId, transform = transform)', 'refreshConversationSummaries()')
        summaries = function(self.app, 'refreshConversationSummaries')
        ordered(self, summaries, 'if (runReplayBatch.isActive) return', '"ui.summaries.refresh"',
                'refreshConversationSummariesNow()')
        self.assertEqual(summaries.count('refreshConversationSummariesNow()'), 1)
        original = function(self.app, 'refreshConversationSummariesNow')
        ordered(self, original, 'conversationsById.entries', '.sortedWith(', '.map {',
                'summaries.filterForFolder(selectedFolderId)', 'conversationPaneState =')
        self.assertNotIn('StreamUiEventDiagnostics', original)


if __name__ == '__main__':
    unittest.main()
