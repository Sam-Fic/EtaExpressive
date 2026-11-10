package io.github.mangi.eta.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source contract for the in-app virtual display viewer and its expressive touch focus. */
class VirtualDisplayViewerContractTest {
    private val ui = File("src/main/kotlin/io/github/mangi/eta/ui")
    private val screen = File(ui, "screens/virtualdisplay/VirtualDisplayScreen.kt").readText()
    private val stage = File(ui, "screens/virtualdisplay/VirtualDisplayStage.kt").readText()
    // 触控焦点的 M3E 奇异形状绘制已收敛到独立层组件（原内联在 Stage 里）。
    private val touchFocus = File(ui, "components/VirtualTouchFocus.kt").readText()

    @Test fun viewerOwnsLifecycleHandoffCloseAndASingleBackHandler() {
        for (text in listOf(
            "setStageExpanded(true)",
            "setStageExpanded(false)",
            "setUserControlling(",
            "handoffToMainDisplay(",
            "VirtualDisplayController.release(",
            "collectAsStateWithLifecycle",
            "EtaScaffold(",
        )) {
            assertTrue("missing $text", screen.contains(text))
        }
        assertEquals(1, Regex("""BackHandler\(""").findAll(screen).count())
    }

    @Test fun stageInjectsGesturesAndDrawsExpressiveShapes() {
        for (text in listOf(
            "awaitEachGesture",
            "ContentScale.FillBounds",
            "aspectRatio(",
            "VirtualTouchFocusLayer",
            "ExperimentalMaterial3ExpressiveApi",
        )) {
            assertTrue("missing $text", stage.contains(text))
        }
        // 奇异形状的预计算与绘制（MaterialShapes + toPath）在触控焦点层组件里。
        for (text in listOf("MaterialShapes.", "toPath(")) {
            assertTrue("missing $text", touchFocus.contains(text))
        }
        assertFalse(stage.contains("Color(0x"))
        assertFalse(stage.contains("CircularProgressIndicator()"))
    }

    @Test fun navigatorRegistersTheViewerRoute() {
        val navigator = File(ui, "navigation/AgentNavigator.kt").readText()
        assertTrue(navigator.contains("AppRoute.VirtualDisplay::class"))
    }
}
