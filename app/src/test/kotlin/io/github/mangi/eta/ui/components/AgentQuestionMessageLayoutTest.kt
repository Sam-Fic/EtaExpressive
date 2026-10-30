package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionOption
import io.github.mangi.eta.agent.question.AgentQuestionRequest
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Measures the real ChatMessageItem entry, not a manually padded card fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentQuestionMessageLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun questionSurfaceKeepsMessageInsetsBeforeAndAfterSubmission() {
        val message = mutableStateOf(AgentQuestionMessageUi("question-layout", AgentQuestionRequest(
            questionId = "question", conversationId = "conversation", runId = "run", toolCallId = "call",
            title = "Layout choice", question = "Which layout should be used?",
            options = listOf(AgentQuestionOption("a", "First", "First description"),
                AgentQuestionOption("b", "Second", "Second description")),
            allowOther = false, allowDelegation = false, allowNote = false)))
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Column(Modifier.width(340.dp).testTag("message-host").verticalScroll(rememberScrollState())) {
                    ChatMessageItem(message = message.value, actions = ChatMessageActions(), showBrowserShortcut = false)
                }
            }
        }
        fun assertInsets() {
            val host = compose.onNodeWithTag("message-host").fetchSemanticsNode().boundsInRoot
            // 量分段行本体（视觉卡片）：外层 Column 满宽，16dp 消息边距由
            // AgentWorkSegmentedBubble 施加在行容器上。
            val row = compose.onNode(hasClickAction() and hasText("Layout choice", substring = true))
                .fetchSemanticsNode().boundsInRoot
            val inset = with(compose.density) { 16.dp.toPx() }
            assertEquals(inset, row.left - host.left, 0.5f)
            assertEquals(inset, host.right - row.right, 0.5f)
        }
        assertInsets()
        compose.onNodeWithText("Which layout should be used?").assertExists()
        compose.runOnIdle {
            message.value = message.value.copy(status = AgentQuestionStatus.Answered,
                answer = AgentQuestionAnswer("option", "b"))
        }
        assertInsets()
        compose.onNodeWithText("Which layout should be used?").assertDoesNotExist()
        compose.onNode(hasClickAction() and hasText("Layout choice", substring = true)).performClick()
        assertInsets()
        compose.onNodeWithText("Which layout should be used?").assertExists()
        val summary = RuntimeEnvironment.getApplication().getString(R.string.question_selected, "Second")
        compose.onAllNodesWithText(summary, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
}
