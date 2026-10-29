package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentWorkProcessCardDrawTest {
    @get:Rule val compose = createComposeRule()

    @Test fun joinedSlicesMatchWholeCardWithoutHorizontalSeams() {
        val split = mutableStateOf(false)
        val dark = mutableStateOf(false)
        val density = mutableStateOf(1f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density.value)) {
                MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
                    Column(Modifier.width(240.dp).background(Color.Magenta).testTag("card-frame")) {
                        if (split.value) {
                            for (part in listOf(WorkProcessCardPart.First, WorkProcessCardPart.Middle, WorkProcessCardPart.Last)) {
                                WorkProcessCardSlice(part) { Box(Modifier.height(32.dp)) }
                            }
                        } else {
                            WorkProcessCardSlice(WorkProcessCardPart.Whole) {
                                Column { repeat(3) { Box(Modifier.height(32.dp)) } }
                            }
                        }
                    }
                }
            }
        }
        for (darkMode in listOf(false, true)) {
            for (scale in listOf(1f, 1.3f)) {
                compose.runOnIdle { dark.value = darkMode; density.value = scale; split.value = false }
                compose.waitForIdle()
                val whole = compose.onNodeWithTag("card-frame").captureToImage().asAndroidBitmap()
                compose.runOnIdle { split.value = true }
                compose.waitForIdle()
                val joined = compose.onNodeWithTag("card-frame").captureToImage().asAndroidBitmap()
                assertEquals(whole.width, joined.width)
                assertEquals(whole.height, joined.height)
                for (y in 0 until whole.height) for (x in 0 until whole.width) {
                    val a = whole.getPixel(x, y)
                    val b = joined.getPixel(x, y)
                    for (shift in listOf(0, 8, 16, 24)) {
                        val difference = abs(((a ushr shift) and 255) - ((b ushr shift) and 255))
                        assertTrue("seam x=$x y=$y dark=$darkMode density=$scale delta=$difference", difference <= 3)
                    }
                }
            }
        }
    }

    @Test fun retainedSlicesUpdateCornersAfterExpandAndAppend() {
        val stepCount = mutableStateOf(0)
        val identities = mutableMapOf<Int, Any>()
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Column(Modifier.width(240.dp)) {
                    Column(Modifier.background(Color.Magenta).testTag("reference")) {
                        WorkProcessCardSlice(WorkProcessCardPart.Whole) {
                            Column { repeat(stepCount.value + 1) { Box(Modifier.height(32.dp)) } }
                        }
                    }
                    Column(Modifier.background(Color.Magenta).testTag("retained")) {
                        repeat(stepCount.value + 1) { index ->
                            key(index) {
                                val identity = remember { Any() }
                                SideEffect { identities[index] = identity }
                                val part = when {
                                    stepCount.value == 0 -> WorkProcessCardPart.Whole
                                    index == 0 -> WorkProcessCardPart.First
                                    index == stepCount.value -> WorkProcessCardPart.Last
                                    else -> WorkProcessCardPart.Middle
                                }
                                WorkProcessCardSlice(part) { Box(Modifier.height(32.dp)) }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        var previous = identities.toMap()
        for (count in 0..3) {
            compose.runOnIdle { stepCount.value = count }
            compose.waitForIdle()
            previous.forEach { (index, identity) -> assertSame(identity, identities[index]) }
            previous = identities.toMap()
            val a = compose.onNodeWithTag("reference").captureToImage().asAndroidBitmap()
            val b = compose.onNodeWithTag("retained").captureToImage().asAndroidBitmap()
            assertEquals(a.width, b.width)
            assertEquals(a.height, b.height)
            for (y in 0 until a.height) for (x in 0 until a.width) {
                for (shift in listOf(0, 8, 16, 24)) {
                    assertTrue(abs(((a.getPixel(x, y) ushr shift) and 255) -
                        ((b.getPixel(x, y) ushr shift) and 255)) <= 3)
                }
            }
            assertEquals(Color.Magenta.toArgb(), b.getPixel(0, 0))
            assertTrue(b.getPixel(b.width / 2, b.height / 2) != Color.Magenta.toArgb())
        }
    }

    @Test fun thousandStepsStillComposeOnlyViewportRows() {
        val active = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        val tools = List(1_000) { index ->
            io.github.mangi.eta.ui.model.ToolActivityMessageUi(
                id = "tool-$index", toolName = "terminal",
                status = io.github.mangi.eta.ui.model.ToolActivityStatusUi.Success,
                argumentsSummary = "step-$index", command = "command-$index", resultSummary = "result-$index",
            )
        }
        val groups = tools.toTimelineEntries()
        val rows = groups.toLazyTimelineRows()
        val steps = rows.filterIsInstance<AgentTimelineRow.WorkStep>()
        var scrollToEnd: (() -> Unit)? = null
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                val state = rememberLazyListState()
                val scope = rememberCoroutineScope()
                scrollToEnd = { scope.launch { state.scrollToItem(steps.lastIndex) }; Unit }
                LazyColumn(Modifier.size(240.dp, 200.dp), state = state) {
                    items(steps, key = { it.key }) { row ->
                        DisposableEffect(row.key) {
                            active.add(row.key)
                            seen.add(row.key)
                            onDispose { active.remove(row.key) }
                        }
                        val part = WorkProcessCardPart.Middle
                        WorkProcessCardSlice(part) { Box(Modifier.height(32.dp)) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(active.size in 1..30)
            assertTrue(seen.size in 1..30)
            checkNotNull(scrollToEnd).invoke()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(steps.last().key in active)
            assertTrue(active.size in 1..30)
            assertTrue(seen.size in 1..60)
        }
    }
}
