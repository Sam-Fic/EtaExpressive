"""断线状态胶囊（重连 / 失败）两端圆位的同心落位契约。

状态胶囊是**单层**容器，且必须与正常输入框外层同形（72dp 高、36dp 圆角）：正常态
外层 = 56dp 内层输入框 + 上下各 8dp 内容内边距。用官方 ContainerSize（64dp）会矮 8dp、
把 36dp 圆角裁到 32dp，切换时输入栏占位跳一下、圆位也横移 4dp。

它也不像正常输入框那样有「外层 + 8dp 内边距 + 内层 28dp 输入框」两层，因此圆位不能
沿用内层的 ComposerConcentricGap（8dp = 28 − 20）：照搬会少一层内边距，圆心只距端盖
28dp；再配上 BottomEnd 贴底，就成了上留白 16dp / 下留白 8dp，重试钮明显偏下，也与
左端盖垂直居中的设置图标错开一条基线。

正确落位：高度 = 外圆角直径（72dp），端距 = 36 − 20 = 16dp，两端圆位都垂直居中
（CenterEnd / CenterVertically），圆心距上下左右端盖都是 36dp，四个圆角同时同心，
且与正常态发送圆钮落在同一个绝对位置。重连中的转圈指示器与失败时的重试钮共用同一槽。
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]
KOTLIN = ROOT / "src/main/kotlin/io/github/mangi/eta"


def read(path):
    return path.read_text(encoding="utf-8")


class ComposerStatusPillConcentricContractTest(unittest.TestCase):
    def setUp(self):
        composer = read(KOTLIN / "ui/components/AgentChatComposerM3.kt")
        self.pill = composer.split("private fun RemoteStatusComposerPill(", 1)[1]
        self.pill = self.pill.split("/** 工具栏内图标按钮之间的间距", 1)[0]

    def test_pill_matches_the_normal_composer_outer_geometry(self):
        # 64dp 的官方 ContainerSize 会让胶囊比正常输入框矮 8dp、圆角被裁到 32dp。
        self.assertIn("val pillHeight = ComposerOuterCornerRadius * 2", self.pill)
        self.assertIn(".height(pillHeight)", self.pill)
        self.assertNotIn(
            ".height(FloatingToolbarDefaults.ContainerSize)",
            self.pill,
        )

    def test_inset_derives_from_the_actual_single_layer_radius(self):
        self.assertIn(
            "val buttonSize = IconButtonDefaults.smallContainerSize().width",
            self.pill,
        )
        self.assertIn("val pillRadius = minOf(", self.pill)
        self.assertIn("ComposerOuterCornerRadius,", self.pill)
        self.assertIn("pillHeight / 2f", self.pill)
        self.assertIn("val concentricInset = pillRadius - buttonSize / 2f", self.pill)
        # 单层胶囊不许再套用「内层 28dp 圆角」算出来的 8dp 端距。
        self.assertNotIn("ComposerConcentricGap * 2 +", self.pill)
        self.assertNotIn(
            "padding(end = ComposerConcentricGap, bottom = ComposerConcentricGap)",
            self.pill,
        )

    def test_trailing_button_is_centered_and_uses_the_same_inset(self):
        self.assertIn("Alignment.CenterEnd", self.pill)
        self.assertNotIn("Alignment.BottomEnd", self.pill)
        self.assertIn("padding(end = concentricInset)", self.pill)
        # 转圈指示器与重试钮共用同一槽，切换状态圆位不跳。
        self.assertIn("FilledIconButton(", self.pill)
        self.assertIn("ContainedMorphLoadingIndicator(", self.pill)

    def test_both_end_caps_share_one_baseline_and_one_inset(self):
        self.assertIn(
            "Spacer(modifier = Modifier.width(concentricInset))",
            self.pill,
        )
        self.assertIn("verticalAlignment = Alignment.CenterVertically", self.pill)
        # 文字让位宽度 = 同心端距 + 40dp 圆位 + 4dp 间隙 = 60dp；
        # 圆钮左缘距右端 56dp，文字右缘再退 4dp 才不贴上去。
        self.assertIn(
            "padding(end = concentricInset + buttonSize + ComposerToolbarGap)",
            self.pill,
        )


if __name__ == "__main__":
    unittest.main()
