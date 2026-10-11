package io.github.mangi.eta.ui.markdown

import android.app.Application
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Counts display-list recordings, rather than GPU replays of a completed RenderNode. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownDrawLayersTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fractionalTailFramesAndAppendsDoNotRecordOrRecomposeCompletedBlocks() {
        val parser = DocumentGfmParserSession()
        val source = "Completed paragraph.\n\nTail has enough words for several frames."
        val initial = parser.parse(source, false).document
        val document = mutableStateOf(initial)
        val coordinator = SmoothTextRevealCoordinator().apply { pauseAnimationsAndCatchUp() }
        var completedCompositions = 0
        var completedDraws = 0
        var tailDraws = 0
        var tailTargetDraws = 0
        compose.setContent {
            val onCompletedComposition = remember { { completedCompositions++; Unit } }
            val onCompletedDraw = remember { { completedDraws++; Unit } }
            val onTailDraw = remember { { tailDraws++; Unit } }
            val onTailTargetDraw = remember { { tailTargetDraws++; Unit } }
            Box(Modifier.size(360.dp, 120.dp).testTag("frame")) {
                Column(Modifier.markdownDocumentDrawLayer()) {
                    document.value.blocks.forEachIndexed { index, block ->
                        key(block.offset) {
                            LayeredText(
                                block = block as MarkdownTextBlock,
                                coordinator = coordinator,
                                onComposition = if (index == 0) onCompletedComposition else NoOp,
                                onDraw = if (index == 0) onCompletedDraw else onTailDraw,
                                onTargetDraw = if (index == 0) NoOp else onTailTargetDraw,
                            )
                        }
                    }
                }
            }
        }
        capture()
        val tailKey = (initial.blocks.last() as MarkdownTextBlock).revealKey
        setProgress(coordinator, tailKey, 1.25f)
        capture()
        val stableCompositions = completedCompositions
        val stableDraws = completedDraws
        val cachedTargetDraws = tailTargetDraws
        val firstTailDraws = tailDraws

        // Fractional frames stay on one line: no target layout or composition is
        // needed. Only the tail's clip/alpha/text recording changes.
        for (progress in listOf(1.5f, 1.75f, 2f)) {
            setProgress(coordinator, tailKey, progress)
            capture()
        }
        compose.runOnIdle {
            assertTrue("tail clip must advance", tailDraws > firstTailDraws)
            assertEquals(stableCompositions, completedCompositions)
            assertEquals(stableDraws, completedDraws)
            assertTrue("only the tail text remains live inside the clip", tailTargetDraws > cachedTargetDraws)
        }

        val appended = parser.parse(source + " Appended network text.", false).document
        assertSame("parser preserves the completed block's composition identity", initial.blocks.first(), appended.blocks.first())
        compose.runOnIdle { document.value = appended }
        capture()
        compose.runOnIdle {
            assertEquals("network appends skip the completed composable", stableCompositions, completedCompositions)
            assertEquals("network appends keep the completed RenderNode", stableDraws, completedDraws)
            assertTrue("changed target text must invalidate its own cache", tailTargetDraws > cachedTargetDraws)
        }
    }

    @Test fun outerLayerStillHonorsPrefixAndFractionalGraphemeClip() {
        val coordinator = SmoothTextRevealCoordinator().apply { pauseAnimationsAndCatchUp() }
        val key = RevealBlockKey(0)
        var layout: TextLayoutResult? = null
        compose.setContent {
            val state = rememberSmoothTextRevealState(key, coordinator)
            Box(Modifier.size(300.dp, 60.dp).background(Color.White).testTag("frame")) {
                BasicText("HHHH", style = TextStyle(color = Color.Black, fontSize = 32.sp,
                    fontFamily = FontFamily.Monospace, textMotion = TextMotion.Animated),
                    modifier = Modifier.markdownBlockDrawLayer(cull = true).smoothTextReveal(state),
                    onTextLayout = { layout = it; state.onTextLayout("HHHH", it) })
            }
        }
        capture()
        setProgress(coordinator, key, 1.5f)
        val bitmap = compose.onNodeWithTag("frame").captureToImage().asAndroidBitmap()
        val textLayout = checkNotNull(layout)
        val inks = IntArray(4) { index ->
            val box = textLayout.getBoundingBox(index)
            val left = (box.left + box.width * 0.35f).toInt()
            val right = (box.right - box.width * 0.35f).toInt().coerceAtLeast(left + 1)
            var ink = 0
            for (y in box.top.toInt().coerceAtLeast(0) until box.bottom.toInt().coerceAtMost(bitmap.height)) {
                for (x in left until right.coerceAtMost(bitmap.width)) {
                    val pixel = bitmap.getPixel(x, y)
                    ink = maxOf(ink, 255 - minOf(AndroidColor.red(pixel), AndroidColor.green(pixel), AndroidColor.blue(pixel)))
                }
            }
            ink
        }
        assertTrue("prefix remains opaque", inks[0] >= 160)
        assertTrue("the next grapheme still fades fractionally", inks[1] in 40..(inks[0] - 40))
        assertTrue("outer RenderNode must not bypass the reveal clip", inks[2] <= 24 && inks[3] <= 24)
    }

    @Test fun repeatedRowDrawsReplayDocumentWithoutWalkingItsBlocks() {
        val rowTick = mutableIntStateOf(0)
        var rowDraws = 0
        var documentDraws = 0
        var blockDraws = 0
        compose.setContent {
            Column(Modifier.size(240.dp, 120.dp).testTag("frame").drawWithContent {
                rowTick.intValue
                rowDraws++
                drawContent()
            }) {
                Column(Modifier.markdownDocumentDrawLayer().drawWithContent {
                    documentDraws++
                    drawContent()
                }) {
                    repeat(20) {
                        BasicText("Completed block $it", modifier = Modifier
                            .markdownBlockDrawLayer(cull = true)
                            .drawWithContent { blockDraws++; drawContent() })
                    }
                }
            }
        }
        capture()
        val initialRows = rowDraws
        val initialDocuments = documentDraws
        val initialBlocks = blockDraws
        repeat(4) {
            compose.runOnIdle { rowTick.intValue++ }
            capture()
        }
        compose.runOnIdle {
            assertTrue(rowDraws > initialRows)
            assertEquals("row replays one document layer", initialDocuments, documentDraws)
            assertEquals("completed text recordings are not walked again", initialBlocks, blockDraws)
        }
    }

    private fun capture() { compose.onNodeWithTag("frame").captureToImage() }

    private fun setProgress(coordinator: SmoothTextRevealCoordinator, key: RevealBlockKey, progress: Float) {
        compose.runOnIdle {
            val record = records(coordinator).getValue(key)
            record.progress = progress
            checkNotNull(record.node).onRevealDataChanged()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun records(coordinator: SmoothTextRevealCoordinator): Map<RevealBlockKey, RevealRecord> {
        val field = SmoothTextRevealCoordinator::class.java.getDeclaredField("records")
        field.isAccessible = true
        return field.get(coordinator) as Map<RevealBlockKey, RevealRecord>
    }
}

private val NoOp: () -> Unit = {}

@Composable
private fun LayeredText(
    block: MarkdownTextBlock,
    coordinator: SmoothTextRevealCoordinator,
    onComposition: () -> Unit,
    onDraw: () -> Unit,
    onTargetDraw: () -> Unit,
) {
    SideEffect(onComposition)
    val state = rememberSmoothTextRevealState(block.revealKey, coordinator)
    BasicText(
        text = block.text,
        style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, textMotion = TextMotion.Animated),
        modifier = Modifier.markdownBlockDrawLayer(cull = true)
            .drawWithContent { onDraw(); drawContent() }
            .smoothTextReveal(state)
            .drawWithContent { onTargetDraw(); drawContent() },
        onTextLayout = { state.onTextLayout(block.text.text, it) },
    )
}
