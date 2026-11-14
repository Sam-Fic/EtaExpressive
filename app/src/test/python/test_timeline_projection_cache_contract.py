"""Cache wiring only: terminal ordering, row policy and scrolling remain authoritative."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class TimelineProjectionCacheContract(unittest.TestCase):
    def test_cache_is_used_at_the_existing_measured_projection_call(self):
        text = (ROOT / 'ui/components/AgentChatBody.kt').read_text()
        # 两个投影缓存：AgentChatBody 的主投影 + AgentConversationMessages 的独立兜底，
        # 都按「激进与否」翻转入缓存键。
        self.assertEqual(2, text.count('AgentTimelineProjectionCache('))
        self.assertEqual(1, text.count('val timelineProjection = remember(aggressiveFold) {'))
        self.assertEqual(1, text.count('val standaloneTimelineProjection = remember(aggressiveFold) {'))
        self.assertEqual(1, text.count('timelineProjection.project(visibleMessages)'))
        self.assertEqual(1, text.count('standaloneTimelineProjection.project(visibleMessages)'))
        # 档位必须参与投影结果的缓存键：缓存实例按档位重建，但结果若只认 messages 引用，
        # 档位切换后会继续返回旧实例算出的旧结构。语音浮窗的 state.messages 引用长期不变，
        # 不会像主列表那样靠换会话自愈，表现为卡片分组/接缝停在旧档。
        self.assertIn('val timelineEntries = remember(visibleMessages, aggressiveFold)', text)
        self.assertIn('measure("timeline.project", visibleMessages.size.toLong()) { timelineProjection.project(visibleMessages) }', text)

    def test_supplied_entries_bypass_standalone_cache_and_projection(self):
        text = (ROOT / 'ui/components/AgentChatBody.kt').read_text()
        scaffold = text[text.index('internal fun AgentChatScaffold('):text.index('internal fun AgentConversationMessages(')]
        self.assertIn('timelineEntries: List<AgentTimelineEntry>', scaffold)
        self.assertIn('timelineEntries = timelineEntries,', scaffold)
        fallback = text.split('val projectedTimelineEntries =', 1)[1].split('val timelineRowsProjection', 1)[0]
        self.assertTrue(fallback.lstrip().startswith('timelineEntries ?: run {'))
        self.assertIn('val standaloneTimelineProjection = remember(aggressiveFold) {', fallback)
        # 独立兜底（语音浮窗路径）同样把档位放进结果缓存键。
        self.assertIn('remember(visibleMessages, aggressiveFold)', fallback)
        self.assertEqual(1, fallback.count('standaloneTimelineProjection.project(visibleMessages)'))

    def test_row_projection_reads_entries_folds_and_overrides(self):
        text = (ROOT / 'ui/components/AgentChatBody.kt').read_text()
        self.assertIn('remember(timelineEntries.size, isCompressingContext, isWaitingForCompression, hasCompactingChildContext)', text)
        self.assertIn('val hasCompactingChildContext = remember(childContexts) { childContexts.any { it.isCompacting } }', text)
        self.assertIn('val timelineRowsProjection = remember { AgentTimelineRowsCache() }', text)
        self.assertIn('val timelineRows = remember(projectedTimelineEntries, foldedWorkKeys, workExpansionOverrides) {', text)
        self.assertIn('timelineRowsProjection.project(', text)
        # 折叠分轮：更早的轮次只要出现过正文回复，生成过程中也保持收起；
        # 最新一轮等整轮停下（isTurnSettled）再收，避免「收了又摊开」的回跳。
        # 折叠键与工作组键集合共用一份增量投影，流式正文追加不再重启清理 effect。
        self.assertIn('val foldedWorkKeysCache = remember { AgentFoldedWorkKeysCache() }', text)
        self.assertIn('foldedWorkKeysCache.project(', text)
        self.assertIn('foldLatestTurn = isTurnSettled', text)
        self.assertIn('val foldedWorkKeys = foldedWorkProjection.folded', text)
        self.assertIn('LaunchedEffect(foldedWorkProjection.activeGroupKeys)', text)
        # Row-only policy reduction must not become a run-state or footer reduction.
        derived_call = text.split('val timelineDerived = timelineDerivedCache.project(', 1)[1].split('\n    )', 1)[0]
        self.assertIn('isStreaming = isStreaming', derived_call)
        self.assertIn('isPaused = isPaused', derived_call)
        self.assertIn('isCompressingContext = isCompressingContext', derived_call)

    def test_derived_cache_reuses_structure_only_on_certified_streaming_delta(self):
        text = (ROOT / 'ui/components/AgentTimelineDerivedCache.kt').read_text()
        self.assertEqual(1, text.count('singleReplacementFrom(previous)'))
        self.assertIn('isStructureOnlyDelta(oldRow.message, newRow.message)', text)
        self.assertIn('patchOwner(oldRow.message, newRow.message)', text)
        # 结构结果原样复用；只有持在 value 里的消息对象必须逐 token 换引用。
        self.assertIn('val footers = if (hasFooterOwner)', text)
        self.assertIn('val reveals = if (hasRevealAnswer)', text)
        self.assertIn('old.content.isBlank() == current.content.isBlank()', text)
        # 全量投影仍逐份调用权威算法，不重写判定口径。
        for token in ('rows.userBubblePosition(index)', 'rows.turnFooters(',
                      'rows.receiptCardSeams(footerKeys = turnFooters.keys)',
                      'rows.lazyUserMessageIndices()'):
            self.assertIn(token, text)

    def test_cache_has_a_reference_guard_and_the_unchanged_full_fallback(self):
        text = (ROOT / 'ui/components/AgentTimelineProjectionCache.kt').read_text()
        for token in ('it.toTimelineEntries()', 'previous.size == messages.size', 'old === current',
                      'old !is AgentMessageUi', 'current !is AgentMessageUi', 'old.id != current.id',
                      'messages.toList()', 'fullProjection(input)', 'input[sourceIndex] !== message',
                      'byId.put(message.id, index) != null', 'mapping[index] < 0'):
            self.assertIn(token, text)
        for token in ('LaunchedEffect', 'mutableStateOf', 'rememberSaveable', 'toLazyTimelineRows(',
                      'substring(', 'hashCode(', 'content =='):
            self.assertNotIn(token, text)
