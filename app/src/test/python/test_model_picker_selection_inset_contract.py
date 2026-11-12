"""Source guards only; no Kotlin build, pixel capture or Android UI execution."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
UI = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui'


class ModelPickerSegmentedContractTest(unittest.TestCase):
    def setUp(self):
        self.source = (UI / 'TtsModelPickerDialog.kt').read_text(encoding='utf-8')

    def test_dialog_uses_segmented_radio_rows_grouped_by_provider(self):
        dialog = self.source.split('internal fun TtsModelPickerDialog', 1)[1].split('internal fun ModelPickerRadioRow', 1)[0]
        # 按提供商分组平铺：提供商作分段列标题，模型为组内分段单选行。
        self.assertIn('EtaSegmentedColumn(', dialog)
        self.assertIn('title = group.providerName', dialog)
        self.assertIn('item(key = "${model.providerId}/${model.id}")', dialog)
        self.assertIn('EtaSegmentedRadioRow(', self.source)
        # 去掉折叠：不再出现展开/收起箭头或 expanded 状态。
        self.assertNotIn('ExpandMore', dialog)
        self.assertNotIn('ExpandLess', dialog)
        self.assertNotIn('expanded', dialog)

    def test_selection_is_pending_until_confirm_and_cancel_dismisses(self):
        dialog = self.source.split('internal fun TtsModelPickerDialog', 1)[1].split('internal fun ModelPickerRadioRow', 1)[0]
        self.assertIn('var pendingModelId by remember', dialog)
        self.assertIn('onModelSelected(pendingProviderId.orEmpty(), pendingModelId.orEmpty())', dialog)
        self.assertIn('onClearSelection?.invoke()', dialog)
        # 面板动作区由共享的 EtaPickerSheet 承载：确认按钮受待确认状态控制，取消直接关闭。
        self.assertIn('EtaPickerSheet(', dialog)
        self.assertIn('enabled = confirmEnabled', dialog)
        self.assertIn('TextButton(onClick = onDismiss)', dialog)
        self.assertIn('R.string.action_confirm', dialog)
        self.assertIn('R.string.action_cancel', dialog)
        # 点击模型只暂存待确认状态，不直接提交。
        self.assertNotIn('onClick = { TouchHaptics.click(view); onModelSelected', dialog)

    def test_sheet_headers_have_no_close_icon(self):
        # 面板一律靠下拉 / 点遮罩 / 返回键关闭，不在右上角放关闭按钮。
        for relative in ('adaptive/EtaPickerSheet.kt',
                         'components/AgentChatModelControls.kt',
                         'components/ItemDescriptionDialog.kt',
                         'components/ContextCompactedSummarySheet.kt',
                         'screens/assistants/AssistantPickerDialogs.kt'):
            with self.subTest(file=relative):
                text = (UI / relative).read_text(encoding='utf-8')
                self.assertNotIn('Icons.Rounded.Close', text)
        sheet = (UI / 'adaptive/EtaPickerSheet.kt').read_text(encoding='utf-8')
        self.assertNotIn('IconButton', sheet)
        self.assertNotIn('R.string.action_close', sheet)
        # 短文弹窗仍用文字「关闭」按钮，面板本身不得再放关闭图标。
        description = (UI / 'components/ItemDescriptionDialog.kt').read_text(encoding='utf-8')
        sheet_body = description.split('private fun ItemDescriptionSheet', 1)[1]
        self.assertNotIn('IconButton', sheet_body)
        self.assertNotIn('R.string.action_close', sheet_body)

    def test_actual_subagent_entry_enables_highlight(self):
        entry = (UI / 'components/SubAgentProfileRow.kt').read_text(encoding='utf-8')
        picker = entry.split('if (usable && modelPicker)', 1)[1].split('if (usable && thinkingPicker', 1)[0]
        self.assertIn('TtsModelPickerDialog(models', picker)
        self.assertIn('highlightSelection = true', picker)


if __name__ == '__main__':
    unittest.main()
