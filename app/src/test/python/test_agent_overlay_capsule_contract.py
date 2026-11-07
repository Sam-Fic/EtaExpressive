"""Top-capsule overlay contracts: virtual-display surface, clearance and locales."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
MAIN = ROOT / "src/main/kotlin/io/github/mangi/eta"
RES = ROOT / "src/main/res"
STRINGS = {
    "en": RES / "values/strings.xml",
    "zh-Hans": RES / "values-b+zh+Hans/strings.xml",
    "zh-Hant": RES / "values-b+zh+Hant/strings.xml",
}
CAPSULE_KEYS = (
    "overlay_capsule_toggle",
    "overlay_virtual_canvas_auto",
    "overlay_virtual_canvas_takeover",
    "overlay_virtual_takeover",
    "overlay_virtual_continue",
    "overlay_virtual_fullscreen",
)


class AgentOverlayCapsuleContractTest(unittest.TestCase):
    def test_capsule_uses_m3_tokens_and_shares_the_touch_focus_layer(self):
        source = (MAIN / "agent/overlay/AgentOverlayCapsule.kt").read_text()
        self.assertIn("MaterialTheme.colorScheme", source)
        self.assertIn("VirtualDisplayStageSection", source)
        self.assertIn("VirtualTouchFocusLayer", source)
        self.assertIn("onTakeOverVirtualDisplay", source)
        self.assertNotIn("miuix", source.lower())

    def test_capsule_position_has_a_single_writer(self):
        """位置只由 CapsuleFlingPhysics 的帧循环写入：宿主只投影，手势不碰窗口。"""
        physics = (MAIN / "agent/overlay/CapsuleFlingPhysics.kt").read_text()
        capsule = (MAIN / "agent/overlay/AgentOverlayCapsule.kt").read_text()
        service = (MAIN / "agent/runtime/AgentRuntimeService.kt").read_text()
        # 帧循环全仓唯一：物理文件里只有定义、组合文件里只有调用，且必须 try/finally
        # 包住——中途被取消也要解除「甩出冻结晚缩」。
        self.assertEqual(1, physics.count("suspend fun runCapsuleMotion("))
        self.assertEqual(1, capsule.count("runCapsuleMotion("))
        self.assertIn("} finally {", capsule)
        # 手势与逐帧路径不得现取屏幕边界（IPC 成本 + 两个写入方的起点）；注释提及不限。
        self.assertNotIn(".currentWindowMetrics", capsule)
        self.assertNotIn("overlayBounds(", capsule)
        # 宿主侧所有权让渡机制与橡皮筋（参考实现是硬钳）已整体删除。
        self.assertNotIn("capsulePointerCaptured", service)
        self.assertNotIn("capsuleFlingSettleUntil", service)
        self.assertNotIn("CAPSULE_OVERDRAG_RESISTANCE", service)
        self.assertNotIn("clearCapsuleFlingState", service)
        # 运动状态只认内容盒：无窗口外衬字段、无窗口高度积分。
        self.assertNotIn("gutterPx", physics)
        self.assertNotIn("heightTargetPx", physics)
        self.assertNotIn("ownsPosition", physics)

    def test_service_binds_capsule_only_when_virtual_display_is_active(self):
        source = (MAIN / "agent/runtime/AgentRuntimeService.kt").read_text()
        self.assertIn("bindVirtualDisplayOverlaySession", source)
        self.assertIn("showCapsuleOverlay", source)
        self.assertIn("removeCapsuleWindow", source)
        self.assertIn("AgentOverlayClearance.attach", source)
        self.assertIn("capsuleWindowWidthPx", source)
        self.assertIn("CAPSULE_STAGE_HEIGHT_DP", source)
        self.assertIn("setStageExpanded", source)
        # 收起态必须让出触摸，否则无障碍/Root 手势会被胶囊窗口截走。
        self.assertIn("FLAG_NOT_TOUCHABLE", source)

    def test_main_display_gestures_yield_the_capsule(self):
        source = (MAIN / "agent/tool/AgentLocalTools.kt").read_text()
        self.assertIn("AgentOverlayClearance.around", source)
        self.assertIn("withCapsuleClearance", source)
        self.assertIn("CanvasRoutingPolicy.isVirtualDisplay(displayId)) gesture()", source)

    def test_capsule_copy_exists_in_every_locale(self):
        for locale, path in STRINGS.items():
            root = ET.parse(path).getroot()
            names = {node.attrib["name"] for node in root.findall("string")}
            for key in CAPSULE_KEYS:
                self.assertIn(key, names, locale)


if __name__ == "__main__":
    unittest.main()
