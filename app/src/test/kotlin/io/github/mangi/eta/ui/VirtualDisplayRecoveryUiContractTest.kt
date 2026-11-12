package io.github.mangi.eta.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source contract: recovery is a single segmented group set, not two competing preview flows. */
class VirtualDisplayRecoveryUiContractTest {
    private val ui = File("src/main/kotlin/io/github/mangi/eta/ui")
    private val sections = File(ui, "screens/virtualdisplay/VirtualDisplaySections.kt")

    @Test fun modePreferencesHaveNoExplanationsOrRecoveryNavigation() {
        val source = File(ui, "AgentTaskPreferenceScreen.kt").readText()
        assertFalse(source.contains("onOpenRecovery"))
        assertFalse(source.contains("agent_task_surface_not_ready_hint"))
        assertFalse(source.contains("VirtualDisplayRecoveryControls("))
        assertTrue(source.contains("virtual-display-entry"))
        assertTrue(source.contains("onOpenVirtualDisplay"))
        assertFalse(source.contains("moduleInstalled != true"))
    }

    @Test fun inlineRecoveryHasWrappingActionsAndExplicitWebRevocation() {
        val source = sections.readText()
        assertEquals(3, Regex("""TouchHaptics\.click\(view\)""").findAll(source).count())
        // FlowRow 只剩网页预览动作区一处；恢复动作已改为分段行，不再换行。
        assertEquals(1, Regex("""FlowRow\(""").findAll(source).count())
        // maxLines = 1：网页预览两颗按钮 + 刷新状态按钮；分段行标题同控制分组不设 maxLines。
        assertEquals(3, Regex("""maxLines = 1""").findAll(source).count())
        assertTrue(source.contains("EtaSegmentedColumn("))
        assertTrue(source.contains("EtaSegmentedItemContainer {"))
        assertFalse(source.contains("Card("))
        assertFalse(source.contains("surfaceContainerHigh"))
        // 强制重置走标准确认对话框（destructive 确认钮），不再用按钮内联两段确认。
        assertTrue(source.contains("EtaDialog("))
        assertTrue(source.contains("EtaDialogActions("))
        assertTrue(source.contains("vd_recovery_force_reset_confirm"))
        assertFalse(source.contains("vd_recovery_force_reset_armed"))
        assertTrue(source.contains("destructive = true"))
        assertTrue(source.contains("!present -> R.string.vd_recovery_empty"))
        assertTrue(source.contains("VirtualDisplayWebPreview.openWithManualClose(context)"))
        assertFalse(source.contains("VirtualDisplayWebPreview.open(context)"))
        assertTrue(source.contains("vd_preview_open"))
        assertFalse(source.contains("vd_preview_control_open"))
        for (explanation in listOf("vd_recovery_explanation", "vd_recovery_scope", "vd_preview_note")) {
            assertFalse(source.contains(explanation))
        }
        assertTrue(source.contains("recover(context.applicationContext)"))
        assertFalse(source.contains("VirtualDisplayWebPreview.stop()"))
        assertTrue(source.contains("VirtualDisplayWebPreview.revoke(context)"))
        assertTrue(source.contains("vd_preview_revoke"))
        assertTrue(source.contains("val showWeb = installed == true || webPaired"))
        val previewButton = source.substringAfter("FlowRow(").substringBefore("R.string.vd_preview_open")
        assertFalse(previewButton.contains("optBoolean(\"present\")"))
        val leaving = source.substringAfter("DisposableEffect(Unit)").substringBefore("val snapshot")
        assertFalse(leaving.contains("VirtualDisplayWebPreview."))
    }
}
