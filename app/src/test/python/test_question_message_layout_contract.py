"""Source wiring guards only: no Kotlin compile, real-device rendering or ripple capture."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/components'


class QuestionMessageLayoutContractTest(unittest.TestCase):
    """问答卡与思考/工具行共用 AgentWorkSegmentedBubble 官方分段气泡外壳后的结构守卫。

    外壳几何（16dp 消息留白）、展开动画与展开/收起 stateDescription 语义由气泡统一持有；
    问答卡只负责表单、提交闸门与权威历史回执。"""
    def setUp(self):
        self.card = (SOURCE / 'AgentQuestionCard.kt').read_text()
        self.bubble = (SOURCE / 'AgentWorkSegmentedBubble.kt').read_text()
        self.entry = (SOURCE / 'ChatMessageItem.kt').read_text()

    def test_bubble_shell_owns_the_single_message_gutter(self):
        # 消息留白（左右 16dp）由共享气泡壳持有，问答卡与思考/工具行同宽对齐。
        self.assertIn('padding(horizontal = 16.dp)', self.bubble)
        # 卡片不再自带任何外壳/留白：无 Surface 容器、无 padding 调用，整卡委托给气泡。
        self.assertNotRegex(self.card, r'(?<![A-Za-z])Surface\(')
        self.assertNotIn('padding(', self.card)
        self.assertIn('AgentWorkSegmentedBubble(', self.card)
        self.assertIn('position = position,', self.card)
        # 单条卡用组位置默认值 Whole（与连续工具/思考组的 First/Middle/Last 相区分）。
        self.assertIn('position: SegmentedRowPosition = SegmentedRowPosition.Whole', self.card)
        self.assertIn('question-card-surface', self.card)
        branch = self.entry.split('is io.github.mangi.eta.ui.model.AgentQuestionMessageUi ->', 1)[1].split('is UserMessageUi ->', 1)[0]
        self.assertNotIn('padding(', branch)  # no duplicated gutter
        self.assertIn('actions.onQuestionDraftChanged(message.request.conversationId, message.request.questionId, draft)', branch)
        self.assertIn('actions.onSubmitQuestionAnswer(message.request.conversationId, message.request.questionId)', branch)

    def test_expansion_animation_owned_by_shared_bubble(self):
        # 展开/收起动画只有气泡一处实现：官方 AnimatedVisibility + 无过冲标准弹簧。
        self.assertEqual(1, self.bubble.count('AnimatedVisibility('))
        self.assertIn('enter = expandVertically(StandardExpandSpec)', self.bubble)
        self.assertIn('exit = shrinkVertically(StandardExpandSpec)', self.bubble)
        self.assertNotIn('tween(', self.card)  # do not invent a similar but different animation
        # 卡片把展开态、切换回调与详情槽交给气泡，不另立动画。
        self.assertIn('expanded = expanded,', self.card)
        self.assertIn('onToggle = toggle,', self.card)
        self.assertIn('details = questionDetails,', self.card)
        # 点击当刻解除底部锚定（标题钉在原位、内容向下生长可见），展开方向交给标准气泡。
        self.assertIn('val releaseAnchor = LocalChatAnchorRelease.current', self.card)
        toggle = self.card.split('val toggle: () -> Unit = {', 1)[1].split('\n    }', 1)[0]
        self.assertIn('releaseAnchor(!expanded)', toggle)
        self.assertIn('expanded = !expanded', toggle)
        # 详情子条目在官方 AnimatedVisibilityScope 里组合，保留离屏绘制层（与思考/工具行同款）。
        self.assertIn('@Composable AnimatedVisibilityScope.() -> Unit', self.card)
        self.assertIn('.then(retainDrawLayerWhenIdle())', self.card)

    def test_authoritative_status_not_submit_latch_controls_default_disclosure(self):
        self.assertIn('key(AgentQuestionProjection.messageId(message.request))', self.card)
        self.assertIn('var expanded by remember(message.status) { mutableStateOf(waiting) }', self.card)
        self.assertNotIn('remember(message.submitting)', self.card)
        submit = self.card.split('Button(', 1)[1].split(') {', 1)[0]
        self.assertIn('if (editable && valid) onSubmit()', submit)
        self.assertNotIn('expanded', submit)
        # 容器型按钮直用必须传官方 shapes（AGENTS §2.5 按压 morph）。
        self.assertIn('shapes = ButtonDefaults.shapes()', self.card)
        self.assertIn('val editable = !message.submitting', self.card)
        self.assertIn('AgentQuestionCodec.validateAnswer(request, displayed).accepted', self.card)

    def test_no_nested_disclosures_or_clipped_body(self):
        for obsolete in ('detailsExpanded', 'fullTextExpanded', 'noteExpanded', 'QuestionDisclosureRow',
                         'QuestionDescription', 'question_show_full_text', 'question_note_added'):
            self.assertNotIn(obsolete, self.card)
        body = self.card.split('val questionDetails', 1)[1]
        self.assertNotIn('TextOverflow.Ellipsis', body)
        self.assertIn('maxLines = Int.MAX_VALUE', body)
        # 正文与历史详情与思考/工具行同一档展开正文字号，不另设字号。
        self.assertIn('chatExpandedBodyStyle()', self.card)

    def test_official_material_components_own_press_feedback(self):
        # M3-only：整卡由官方 ListItem / RadioButton / Button / Badge 组成，按压涟漪、形状形变
        # 与语义由官方组件自带，不再手拼 clickable(indication = null) 或压制涟漪的包裹层。
        for component in ('ListItem(', 'RadioButton(', 'Button(', 'Badge('):
            self.assertIn(component, self.card)
        self.assertIn('selectableGroup()', self.card)
        # 单选圆点不抢命中区：官方 RadioButton 只作指示，onClick = null 由父行接管。
        self.assertIn('onClick = null', self.card)
        self.assertNotIn('WithoutPressRipple', self.card)
        self.assertNotIn('LocalRippleConfiguration', self.card)
        self.assertNotIn('indication = null', self.card)

    def test_history_uses_only_authoritative_answer_and_one_summary(self):
        self.assertIn('message.answer.takeIf { message.status == AgentQuestionStatus.Answered }', self.card)
        history = self.card.split('private fun QuestionReadOnlyDetails', 1)[1].split('private fun QuestionHistoryOption', 1)[0]
        for draft in ('message.selectedOptionId', 'message.otherText', 'message.note'):
            self.assertNotIn(draft, history)
        self.assertEqual(1, self.card.count('questionSummary(message)?.let'))
        self.assertIn('if (answer == null)', history)
        self.assertIn('answer?.note?.takeIf', history)
        self.assertNotIn('onDraftChanged', history)
        self.assertNotIn('onSubmit', history)
        # 历史项是官方展示型 ListItem：selected 语义由本层补（官方展示型重载不设）。
        option = self.card.split('private fun QuestionHistoryOption', 1)[1]
        self.assertIn('Modifier.semantics { this.selected = selected }', option)

    def test_optional_controls_still_respect_request_capabilities(self):
        self.assertIn('if (request.allowOther)', self.card)
        self.assertIn('if (request.allowDelegation)', self.card)
        self.assertIn('if (request.allowNote) QuestionTextField', self.card)
        # 官方单选 ListItem(selected/onClick) 提供整行命中与 Role.RadioButton 语义。
        self.assertRegex(self.card, r'RadioButton\(\s*selected = selected,\s*onClick = null')
        self.assertIn('.heightIn(min = 48.dp)', self.card)
        self.assertIn('option.id == request.recommendedOptionId', self.card)
        self.assertIn('displayed.kind == "option" && displayed.optionId == option.id', self.card)
        self.assertNotRegex(self.card, r'selectedOptionId\s*=\s*request.recommendedOptionId')

    def test_dangerous_confirm_options_render_destructive(self):
        # 危险操作确认（与 AgentDangerousConfirmationGate 协议一致）用 error 容器档，
        # 与普通选项在视觉上分开；普通选中档仍走 secondaryContainer。
        self.assertIn('DANGEROUS_CONFIRM_OPTION_IDS', self.card)
        self.assertIn('"confirm_once"', self.card)
        self.assertIn('"confirm_session"', self.card)
        self.assertIn('MaterialTheme.colorScheme.errorContainer', self.card)
        self.assertIn('MaterialTheme.colorScheme.secondaryContainer', self.card)
        self.assertIn('RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.error)', self.card)

    def test_monet_semantic_colors_and_whole_card_semantics(self):
        # M3-only：颜色全部走 MaterialTheme.colorScheme / 官方默认，不自取语义色、不写死 hex。
        self.assertIn('MaterialTheme.colorScheme.', self.card)
        self.assertNotRegex(self.card, r'Color\(0x')
        self.assertNotIn('tonalElevation', self.card)
        # 展开箭头与展开/收起 stateDescription 由气泡统一持有（官方 Sample 口径）。
        self.assertIn('stateDescription = if (expanded) expandedStateDescription else collapsedStateDescription', self.bubble)
        self.assertIn('R.string.work_state_expanded', self.bubble)
        self.assertIn('R.string.work_state_collapsed', self.bubble)
        self.assertIn('EtaExpandChevron(', self.bubble)
        # 已回答回执的标题单行省略：完整标题与问题正文都在展开后的详情里。
        self.assertIn('titleMaxLines = 1', self.card)


if __name__ == '__main__':
    unittest.main()
