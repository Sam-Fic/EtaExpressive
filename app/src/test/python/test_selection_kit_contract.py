"""共享多选套件 EtaSelectionKit 的装配契约。

锁的用户可感知语义（一次锁死，替代各多选页的重复装配断言）：
- 底部操作栏是官方 M3E HorizontalFloatingToolbar 纯图标药丸（expanded + vibrant 容器
  primaryContainer/onPrimaryContainer，端盖同心 padding，leadingContent 传空）；
- 全选钮 SelectAll/Deselect 图标随 allSelected 原位自适应；
- 动作钮统一要求「已选 > 0」才可用，可再叠加页面级门控（busy/删除资格）；
- 破坏性动作（destructive）图标取 error 色，走官方裸 IconButton + Tooltip；
- 进退场 = 位移走 M3E Spatial（轻微过冲）、透明度走 Effects（无过冲），底部居中；
- 底部占位 88dp（多选态）/ 24dp（常态）+ 导航条避让；
- 顶栏与段标题两落点的多选入口都是 Checklist→Close 原位切换。
"""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
KIT = ROOT / "app/src/main/kotlin/io/github/mangi/eta/ui/adaptive/EtaSelectionKit.kt"


class SelectionKitContract(unittest.TestCase):
    def setUp(self):
        self.source = KIT.read_text(encoding="utf-8")

    def test_bar_is_official_floating_toolbar_vibrant_pill(self):
        source = self.source
        self.assertIn("HorizontalFloatingToolbar(", source)
        self.assertIn("expanded = true,", source)
        self.assertIn(
            "colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),", source)
        self.assertIn("leadingContent = {},", source)
        self.assertIn("EtaFloatingToolbarConcentricPadding(", source)
        self.assertIn("IconButtonDefaults.smallContainerSize().width", source)

    def test_select_all_button_adapts_icon(self):
        self.assertIn(
            "if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll",
            self.source,
        )
        self.assertIn("R.string.media_action_select_all", self.source)
        self.assertIn("R.string.media_action_deselect_all", self.source)
        # 全选钮只受整栏 enabled 门控，不要求已选 > 0。
        bar = self.source.split("fun EtaSelectionBar(", 1)[1]
        select_all = bar.split("actions.forEach", 1)[0]
        self.assertIn("onClick = onSelectAll,", select_all)
        self.assertIn("enabled = enabled,", select_all)

    def test_actions_require_selected_count_with_extra_gate(self):
        # 普通钮与破坏性钮的最终启用都叠加「已选 > 0」+ 页面级 action.enabled + 整栏 enabled。
        self.assertEqual(
            2,
            self.source.count("enabled && selectedCount > 0 && action.enabled"),
        )

    def test_destructive_action_is_error_tinted_bare_icon_button(self):
        self.assertIn("val destructive: Boolean = false", self.source)
        destructive = self.source.split("if (action.destructive)", 1)[1]
        self.assertIn("androidx.compose.material3.IconButton(", destructive)
        self.assertIn("tint = MaterialTheme.colorScheme.error,", destructive)
        self.assertIn("EtaTooltip(text = action.contentDescription)", destructive)

    def test_entry_toggles_checklist_close_in_place(self):
        # 顶栏（TopBarIconButton）与段标题（EtaIconButton）两落点同一套原位切换。
        self.assertEqual(
            2,
            self.source.count(
                "icon = if (selectionMode) Icons.Rounded.Close else Icons.Rounded.Checklist,"),
        )
        self.assertEqual(
            2,
            self.source.count(
                "if (selectionMode) R.string.action_cancel else R.string.media_action_multiselect,"),
        )

    def test_overlay_motion_spatial_without_effects_overshoot(self):
        self.assertIn("MaterialTheme.motionScheme.defaultSpatialSpec()", self.source)
        self.assertIn("MaterialTheme.motionScheme.fastSpatialSpec()", self.source)
        self.assertIn("MaterialTheme.motionScheme.defaultEffectsSpec()", self.source)
        self.assertIn("MaterialTheme.motionScheme.fastEffectsSpec()", self.source)
        self.assertIn("Alignment.BottomCenter", self.source)

    def test_bottom_spacer_88dp_in_selection_24dp_idle(self):
        self.assertIn("height(if (isSelecting) 88.dp else 24.dp)", self.source)
        self.assertIn(".navigationBarsPadding()", self.source)

    def test_action_slot_is_data_class_with_enabled_and_destructive(self):
        self.assertIn("data class EtaSelectionAction(", self.source)
        self.assertIn("val enabled: Boolean = true", self.source)
        self.assertIn("val destructive: Boolean = false", self.source)


if __name__ == "__main__":
    unittest.main()
