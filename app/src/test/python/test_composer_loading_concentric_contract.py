"""输入框左下角圆钮的落位契约：编辑态取消钮 / 生成中加载指示器共用一个同心槽。

两者原先都不在胶囊里：取消按钮在工具栏 Row 里被 EtaTooltip 吃掉了 align（停在左上），
加载指示器挂在官方 TextField 的 leadingIcon 槽（只跟第一行、垂直顶对齐，输入框多行
长高后留在左上角），而发送按钮在共享容器里按 BottomEnd 贴底，基线错开。
两者必须和发送按钮同处那个共享容器，用 BottomStart 对齐，各让出同一个
ComposerConcentricGap（8dp）端距（内层圆角 28 − 圆钮半径 20）；官方 leadingIcon 槽
只留同宽占位，避免多行文本伸进左下圆钮区域；语音进行中胶囊换成聆听条（起始侧另有
麦克风状态圆钮），两枚圆钮都必须让位；编辑态优先于加载指示器，取消编辑不能被盖住。
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]
KOTLIN = ROOT / "src/main/kotlin/io/github/mangi/eta"


def read(path):
    return path.read_text(encoding="utf-8")


class ComposerLoadingConcentricContractTest(unittest.TestCase):
    def setUp(self):
        self.composer = read(KOTLIN / "ui/components/AgentChatComposerM3.kt")

    def test_indicator_sits_in_the_shared_bottom_start_slot(self):
        slot = self.composer.split("// 起始侧槽：", 1)[1]
        slot = slot.split("// 尾部按钮槽：", 1)[0]
        # 外层工具栏内容是 RowScope：这里必须用无 receiver 的 AnimatedContent，
        # AnimatedVisibility 会被解析成 RowScope 成员重载并触发 DSL_SCOPE_VIOLATION。
        self.assertIn("AnimatedContent(", slot)
        self.assertNotIn("AnimatedVisibility(", slot)
        # 语音态 / 口述态的聆听条起始侧已有麦克风状态圆钮，不能再叠一枚加载指示器。
        self.assertIn("targetState = leadingState", slot)
        self.assertIn("conversationActive || dictating || dictationNotice -> ComposerLeading.NONE", slot)
        self.assertIn("isEditingMessage -> ComposerLeading.CANCEL", slot)
        self.assertIn("showMorphLoading -> ComposerLeading.LOADING", slot)
        # 编辑态优先：取消编辑不能被生成中加载指示器盖住。
        self.assertLess(
            slot.index("isEditingMessage -> ComposerLeading.CANCEL"),
            slot.index("showMorphLoading -> ComposerLeading.LOADING"),
        )
        # 两枚圆钮共用同一个槽位与同一套端距。
        self.assertIn("M3ComposerCancelButton(", slot)
        self.assertIn('testTag("composer-cancel-slot")', slot)
        self.assertIn('testTag("composer-leading-slot")', slot)
        # 出现 / 消失只做淡入淡出，默认尺寸动画会把胶囊端盖裁一刀。
        self.assertIn(".using(null)", slot)
        self.assertIn("Alignment.BottomStart", slot)
        self.assertIn(
            "padding(start = ComposerConcentricGap, bottom = ComposerConcentricGap)",
            slot,
        )
        self.assertIn("ContainedMorphLoadingIndicator(", slot)
        self.assertIn('testTag("composer-leading-slot")', slot)
        # 与右下发送 / 停止圆钮同尺寸（官方 40dp 圆钮）。
        self.assertIn("IconButtonDefaults.smallContainerSize().width", slot)

    def test_trailing_slot_uses_the_same_concentric_gap_and_baseline(self):
        trailing = self.composer.split("// 尾部按钮槽：", 1)[1]
        trailing = trailing.split("AnimatedContent(", 1)[0]
        self.assertIn("Alignment.BottomEnd", trailing)
        # 端距动画化：bottom / end 两条 animateDpAsState 的正常态 / 收尾态 / 失败提示态
        # 都保持同一 8dp 同心端距（与起始侧槽对称），仅听写态（✓ 胶囊移出胶囊、贴住
        # 工具栏内容区末端）归零；0↔8dp 过冲的负值必须先 coerce 再进 padding。
        self.assertEqual(4, trailing.count("-> ComposerConcentricGap"))
        self.assertEqual(2, trailing.count("dictating -> 0.dp"))
        self.assertIn("trailingBottomSafe = trailingBottom.coerceAtLeast(0.dp)", trailing)
        self.assertIn("trailingEndSafe = trailingEnd.coerceAtLeast(0.dp)", trailing)
        self.assertIn(".padding(end = trailingEndSafe, bottom = trailingBottomSafe)", trailing)

    def test_cancel_button_no_longer_sits_in_the_toolbar_row(self):
        # 编辑态分支只让动作组整组不组合，取消按钮不再占工具栏 Row 的宽度。
        # （语音对话 / 口述态同样收起动作组：聆听条与口述流光占据同一左侧区域。）
        self.assertIn("if (!isEditingMessage || conversationActive || dictating) {", self.composer)
        # 全文件里只应有「函数定义 + 共享槽里那一处调用」两个出现点。
        self.assertEqual(2, self.composer.count("M3ComposerCancelButton("))
        slot = self.composer.split("// 起始侧槽：", 1)[1]
        slot = slot.split("// 尾部按钮槽：", 1)[0]
        self.assertIn("M3ComposerCancelButton(", slot)

    def test_leading_icon_slot_only_reserves_width(self):
        # 编辑态取消钮也要占位，否则多行原文会压到左下圆钮上。
        self.assertIn(
            "reserveLeadingSlot = showMorphLoading || isEditingMessage",
            self.composer,
        )
        field = self.composer.split("private fun M3ComposerTextField(", 1)[1]
        field = field.split("private fun M3VoiceBar(", 1)[0]
        leading = field.split("leadingIcon = if (reserveLeadingSlot)", 1)[1]
        leading = leading.split("trailingIcon =", 1)[0]
        self.assertIn("Spacer(", leading)
        # 占位宽度 = 8dp 同心间距 + 40dp 圆钮 = 官方槽的 48dp 最小触控区，
        # 文字起始位置与改动前一致，多行文本的每一行也不会压到圆钮上。
        self.assertIn(
            "ComposerConcentricGap + IconButtonDefaults.smallContainerSize().width",
            leading,
        )
        self.assertNotIn("ContainedMorphLoadingIndicator(", leading)


if __name__ == "__main__":
    unittest.main()
