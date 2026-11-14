package io.github.mangi.eta.ui.markdown

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.haptics.HapticSelectionContainer
import io.github.mangi.eta.ui.haptics.SelectionTestMagnifier
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h900dp-mdpi", shadows = [SelectionTestMagnifier::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownSelectionContentUpdateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun contentUpdateShowsLatestTextAndKeepsBodyMounted() {
        val selection = SelectionState()
        val original = "Original answer."
        val latest = listOf("Updated answer.", "New paragraph.")
        val document = mutableStateOf(DocumentGfmParserSession().parse(original, true, MarkdownInlineStyle(linkColor = Color.Red, codeBackground = Color.Gray)).document)
        var creates = 0
        var disposes = 0
        val identities = mutableListOf<Any>()
        compose.setContent {
            val currentDocument = document.value
            MaterialTheme {
                Box(Modifier.size(300.dp, 140.dp)) {
                    HapticSelectionContainer(selectionState = selection) {
                        val identity = remember { Any() }
                        SideEffect { identities.add(identity) }
                        DisposableEffect(Unit) {
                            creates++
                            onDispose { disposes++ }
                        }
                        MarkdownContent(currentDocument, rememberMarkdownStyle(MarkdownTone.Answer),
                            Modifier.wrapContentHeight(Alignment.Top, unbounded = true))
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(original, useUnmergedTree = true).assertExists()
        compose.runOnIdle {
            assertEquals(listOf(original), selection.getSelectableTexts().map { it.text })
            document.value = DocumentGfmParserSession().parse(latest.joinToString("\n\n"), true, MarkdownInlineStyle(linkColor = Color.Red, codeBackground = Color.Gray)).document
        }
        compose.waitForIdle()
        latest.forEach { compose.onNodeWithText(it, useUnmergedTree = true).assertExists() }
        compose.onNodeWithText(original, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(latest, selection.getSelectableTexts().map { it.text })
            assertEquals(1, creates)
            assertEquals(0, disposes)
            assertTrue(identities.size >= 2)
            assertTrue(identities.all { it === identities.first() })
        }
    }
}
