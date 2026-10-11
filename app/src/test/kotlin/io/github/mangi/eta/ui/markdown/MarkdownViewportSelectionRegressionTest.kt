package io.github.mangi.eta.ui.markdown

import android.app.Application
import android.content.ClipboardManager
import org.robolectric.RuntimeEnvironment
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.haptics.HapticSelectionContainer
import io.github.mangi.eta.ui.haptics.SelectionTestMagnifier
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalFoundationApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], shadows = [SelectionTestMagnifier::class])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownViewportSelectionRegressionTest {
    @get:Rule val compose = createComposeRule()
    private var oldMenu = false
    @Before fun setup() {
        oldMenu = ComposeFoundationFlags.isNewContextMenuEnabled
        ComposeFoundationFlags.isNewContextMenuEnabled = false
    }
    @After fun restore() { ComposeFoundationFlags.isNewContextMenuEnabled = oldMenu }

    @Test fun selectAllRetainsOffscreenParagraphsAndCopyAction() {
        val viewport = MarkdownDrawViewport()
        val selection = SelectionState()
        var selectAll: (() -> Unit)? = null
        var copy: (() -> Unit)? = null
        val toolbar = object : TextToolbar {
            override var status = TextToolbarStatus.Hidden
            override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
                                  onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
                copy = onCopyRequested
                selectAll = onSelectAllRequested
                status = TextToolbarStatus.Shown
            }
            override fun hide() { status = TextToolbarStatus.Hidden }
        }
        val paragraphs = (0..15).map { "Paragraph $it keeps all selectable words." }
        val document = DocumentGfmParserSession().parse(paragraphs.joinToString("\n\n"), true).document
        compose.setContent {
            MaterialTheme {
                SideEffect { viewport.updateEnabled(true) }
                CompositionLocalProvider(LocalMarkdownDrawViewport provides viewport, LocalTextToolbar provides toolbar) {
                    Box(Modifier.size(300.dp, 100.dp).onGloballyPositioned(viewport::updateCoordinates).clipToBounds()) {
                        HapticSelectionContainer(selectionState = selection) {
                            MarkdownContent(document, rememberMarkdownStyle(MarkdownTone.Answer),
                                Modifier.wrapContentHeight(unbounded = true, align = androidx.compose.ui.Alignment.Top))
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("全文选择").assertDoesNotExist()
        compose.onNodeWithText("返回浏览").assertDoesNotExist()
        compose.onNodeWithText(paragraphs.first(), useUnmergedTree = true).performTouchInput { longClick() }
        compose.runOnIdle {
            assertNotNull("platform selection offers Select all", selectAll)
            selectAll!!.invoke()
        }
        compose.runOnIdle {
            val selected = selection.selectedTexts.joinToString("\n") { it.text }
            paragraphs.forEach { assertTrue("missing $it in $selected", selected.contains(it)) }
            assertNotNull("full selection retains platform Copy", copy)
        }
        compose.onNodeWithText(paragraphs.last(), useUnmergedTree = true).assertExists()
        val clipboard = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
        compose.runOnIdle {
            clipboard.clearPrimaryClip()
            copy!!.invoke()
        }
        compose.waitUntil(5_000) { clipboard.primaryClip != null }
        compose.runOnIdle {
            assertEquals(paragraphs.joinToString("\n"),
                clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }
}
