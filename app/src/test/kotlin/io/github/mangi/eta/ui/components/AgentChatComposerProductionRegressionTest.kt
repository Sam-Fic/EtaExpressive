package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AppFileLogger
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.*
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Scaffold -> BottomBar -> InputBar boundary. Not an imitation of the input widget. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w480dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentChatComposerProductionRegressionTest {
    @get:Rule val compose = createComposeRule()
    private val session = StreamPerformanceDiagnostics.Session()
    private lateinit var loggerEnabled: AtomicBoolean
    private var oldLoggerEnabled = false
    private var oldDiagnosticAllowed = false
    private val draft = TextFieldState("draft🙂", TextRange(2, 4))
    private val model = AgentModelPickerUiState(selectedModel = AgentModelOptionUi(
        "m", "p", "Provider", "openai", "model", "Model", 4096,
    ))
    private val mentions = ConversationMentionInputUi()
    private val history = mutableStateOf(listOf(AgentModelClient.ConversationMessage("user", "first")))
    private val measured = mutableStateOf<Int?>(null)
    private val auto = mutableStateOf(true)
    private val dark = mutableStateOf(false)
    private val width = mutableStateOf(360.dp)
    private val fontScale = mutableStateOf(1f)
    private val streaming = mutableStateOf(false)
    private val paused = mutableStateOf(false)
    private val visible = mutableStateOf<List<AgentChatMessageUi>>(emptyList())
    private val callback = mutableStateOf<(String) -> Unit>({ calls += "old:$it" })
    private val calls = mutableListOf<String>()
    private var appliedHistory = ""

    @Before fun init() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        // Enable bounded counters only. Do NOT install logger, open files or launch logcat.
        loggerEnabled = AppFileLogger::class.java.getDeclaredField("enabled")
            .apply { isAccessible = true }.get(null) as AtomicBoolean
        oldLoggerEnabled = loggerEnabled.getAndSet(true)
        oldDiagnosticAllowed = StreamDiagnosticControl.allowed
        StreamDiagnosticControl.update(null)
        StreamPerformanceDiagnostics.publishSession(session)
    }

    @After fun close() {
        StreamPerformanceDiagnostics.publishSession(null)
        loggerEnabled.set(oldLoggerEnabled)
        StreamDiagnosticControl.update(if (oldDiagnosticAllowed) "0" else "1")
    }

    @Test fun unrelatedHistorySkipsRealInputAndReenablingBudgetUsesLatestHistory() {
        show()
        compose.waitForIdle()
        val originalText = draft.text.toString()
        val originalSelection = draft.selection
        Assert.assertTrue(takeCount() > 0) // probe is live, not a constant-zero false positive
        for (index in 1..8) {
            compose.runOnIdle { history.value = listOf(AgentModelClient.ConversationMessage("user", "new-$index")) }
            compose.waitForIdle()
            compose.runOnIdle {
                Assert.assertEquals("new-$index", appliedHistory)
                Assert.assertEquals(originalText, draft.text.toString())
                Assert.assertEquals(originalSelection, draft.selection)
            }
            Assert.assertEquals(0L, takeCount())
        }
        compose.runOnIdle { auto.value = false }
        compose.waitForIdle(); clearCount()
        compose.runOnIdle { history.value = listOf(AgentModelClient.ConversationMessage("user", "unmeasured-latest")) }
        compose.waitForIdle()
        Assert.assertEquals(0L, takeCount())
        // Non-null ZERO must enable the old budget, not use an obsolete empty history.
        compose.runOnIdle {
            history.value = listOf(AgentModelClient.ConversationMessage("user", "x".repeat(40000)))
            measured.value = 0
        }
        compose.waitForIdle()
        Assert.assertTrue(takeCount() > 0)
        compose.onNodeWithContentDescription(text(R.string.chat_send)).assertIsNotEnabled()
        compose.runOnIdle { Assert.assertTrue(calls.isEmpty()) }
        compose.runOnIdle { history.value = emptyList() }
        compose.waitForIdle()
        Assert.assertTrue(takeCount() > 0)
        clickSend()
        compose.runOnIdle { Assert.assertEquals(listOf("old:$originalText"), calls); auto.value = true }
        compose.waitForIdle(); clearCount()
        compose.runOnIdle { history.value = listOf(AgentModelClient.ConversationMessage("user", "again")) }
        compose.waitForIdle()
        Assert.assertEquals(0L, takeCount())
    }

    @Test fun callbacksDraftAndRequiredVisualStateStayLiveAcrossHistoryChanges() {
        show(); compose.waitForIdle(); clearCount()
        compose.runOnIdle {
            callback.value = { calls += "new:$it" }
            dark.value = true; width.value = 300.dp; fontScale.value = 1.15f
        }
        compose.waitForIdle()
        Assert.assertTrue(takeCount() > 0)
        compose.runOnIdle { history.value = listOf(AgentModelClient.ConversationMessage("user", "new history")) }
        compose.waitForIdle()
        compose.runOnIdle { draft.edit { replace(0, length, "latest🙂"); selection = TextRange(1, 3) } }
        compose.waitForIdle()
        clickSend()
        compose.runOnIdle {
            Assert.assertEquals(listOf("new:latest🙂"), calls)
            Assert.assertEquals(TextRange(1, 3), draft.selection)
            draft.edit { replace(0, length, ""); selection = TextRange.Zero }
            streaming.value = true
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(text(R.string.chat_stop)).performClick()
        compose.runOnIdle {
            Assert.assertEquals("stop", calls.last())
            streaming.value = false
            visible.value = listOf(SystemNoticeMessageUi("failed", SystemNoticeCode.RuntimeFailed))
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(text(R.string.chat_continue), substring = true).performClick()
        compose.runOnIdle { Assert.assertEquals("continue", calls.last()) }
    }

    private fun takeCount(): Long {
        var count = -1L
        compose.runOnIdle { count = session.snapshot(false).stats["chat.input.compose"]?.count ?: 0L }
        return count
    }
    private fun clearCount() { takeCount() }
    private fun clickSend() = compose.onNodeWithContentDescription(text(R.string.chat_send)).performClick()
    private fun text(id: Int) = RuntimeEnvironment.getApplication().getString(id)

    private fun show() = compose.setContent {
        MaterialTheme(colorScheme = if (dark.value) darkColorScheme() else lightColorScheme()) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                val currentHistory = history.value
                SideEffect { appliedHistory = currentHistory.lastOrNull()?.content.orEmpty() }
                Box(Modifier.width(width.value)) {
                    AgentChatScaffold(
                        visibleMessages = visible.value, timelineEntries = emptyList(), hasMessages = visible.value.isNotEmpty(),
                        scrollState = rememberLazyListState(), input = "", draftField = draft,
                        modelPickerState = model, history = currentHistory,
                        measuredContextTokens = measured.value, autoCompressEnabled = auto.value,
                        isStreaming = streaming.value, isPaused = paused.value,
                        reasoningEffort = ReasoningEffort.MEDIUM, availableReasoningEfforts = emptyList(),
                        pendingImages = emptyList(), pendingFileReferences = emptyList(), conversationMentions = mentions, messageEdit = null,
                        keepBottomAnchored = true, onBottomAnchorChanged = {},
                        onSubmit = callback.value, onReasoningEffortChange = {}, onModelSelected = { _, _ -> },
                        onStop = { calls += "stop" }, onContinue = { calls += "continue" },
                        onAttachImage = {}, onAttachVideo = {}, onRemoveImage = {}, onAttachFiles = {},
                        onAttachFolder = {}, onAttachFilePath = {}, onRemoveFileReference = {},
                        onEditMessage = {}, onCancelMessageEdit = {}, onDeleteMessage = {}, onRegenerateMessage = {},
                        onSuggestionClick = {}, onRunTraceClick = {}, onOpenBrowser = {}, onEditAssistant = {},
                        onOpenModelProviders = {}, onOpenAssistants = {},
                        currentBrowserMessageId = null,
                    )
                }
            }
        }
    }
}
