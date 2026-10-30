package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionOption
import io.github.mangi.eta.agent.question.AgentQuestionRequest
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.app.AgentQuestionProjection
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real card semantics, measured text and clicks. These tests do not exercise runtime transport. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentQuestionCardCompactTest {
    @get:Rule val compose = createComposeRule()
    private val message = mutableStateOf(fixture())
    private val drafts = mutableListOf<AgentQuestionAnswer>()
    private var submissions = 0

    @Test fun recommendationIsNotSelectionAndTheWholeRowIsAccessible() {
        show()
        option("First choice").assertIsNotSelected().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(260.dp)
        option("Second choice").assertIsNotSelected()
        compose.onNodeWithText(text(R.string.question_recommended)).assertExists()
        compose.onNodeWithText(text(R.string.question_submit)).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1) // note is no longer separately folded
        option("Second choice").performTouchInput { click(Offset(width - 2f, height / 2f)) }
        option("Second choice").assertIsSelected()
        option("First choice").assertIsNotSelected()
        compose.onNodeWithText(text(R.string.question_submit)).assertIsEnabled()
        compose.runOnIdle { assertEquals("b", drafts.single().optionId); assertEquals(0, submissions) }
    }

    @Test fun wholeCardFoldKeepsNoteAndSelectionWithoutIndependentDisclosures() {
        show()
        field(R.string.question_note).performTextInput("Keep this condition")
        option("Second choice").performClick()
        header().performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Choose the next step.").assertDoesNotExist()
        assertCompactSummary()
        compose.runOnIdle { assertEquals("Keep this condition", message.value.note); assertEquals(0, submissions) }
        header().performClick()
        field(R.string.question_note).assertTextContains("Keep this condition")
        option("Second choice").assertIsSelected()
        compose.onNodeWithText(text(R.string.question_show_full_text)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.question_note_added)).assertDoesNotExist()
    }

    @Test fun otherDelegationAndNoteKeepTheirExistingAnswerSemantics() {
        show(fixture().copy(note = "Additional context"))
        option(text(R.string.question_other)).performClick()
        compose.onNodeWithText(text(R.string.question_submit)).assertIsNotEnabled()
        field(R.string.question_other_hint).performTextInput("A custom choice")
        compose.onNodeWithText(text(R.string.question_submit)).assertIsEnabled()
        compose.runOnIdle {
            val answer = AgentQuestionProjection.draftAnswer(message.value)
            assertEquals(AgentQuestionAnswer("other", otherText = "A custom choice", note = "Additional context"), answer)
            assertTrue(AgentQuestionCodec.validateAnswer(message.value.request, answer).accepted)
        }
        option(text(R.string.question_delegate)).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText(text(R.string.question_delegate_hint), useUnmergedTree = true).assertExists()
        compose.runOnIdle {
            val answer = AgentQuestionProjection.draftAnswer(message.value)
            assertEquals(AgentQuestionAnswer("delegate", note = "Additional context"), answer)
            assertTrue(AgentQuestionCodec.validateAnswer(message.value.request, answer).accepted)
            assertEquals("A custom choice", message.value.otherText)
            assertEquals(0, submissions)
        }
        option("First choice").performClick()
        compose.runOnIdle {
            assertEquals(AgentQuestionAnswer("option", "a", note = "Additional context"),
                AgentQuestionProjection.draftAnswer(message.value))
        }
        option(text(R.string.question_other)).performClick()
        field(R.string.question_other_hint).assertTextContains("A custom choice")
    }

    @Test fun disallowedOptionalAnswersDoNotCreateControls() {
        val initial = fixture()
        show(initial.copy(request = initial.request.copy(allowOther = false, allowDelegation = false, allowNote = false)))
        compose.onAllNodes(role(Role.RadioButton)).assertCountEquals(2)
        compose.onNodeWithText(text(R.string.question_other)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.question_delegate)).assertDoesNotExist()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }

    @Test fun authoritativeAnswerCollapsesThenShowsOnlyOneSubmittedChoiceAndNote() {
        show(fixture().copy(selectedOptionId = "a", note = "Uncommitted note"))
        val authoritative = AgentQuestionAnswer("option", "b", note = "Submitted condition")
        compose.runOnIdle { message.value = message.value.copy(status = AgentQuestionStatus.Answered, answer = authoritative) }
        header().assertExists().assertHeightIsAtLeast(48.dp)
        assertCompactSummary()
        compose.onNodeWithText("Decision title").assertDoesNotExist()
        assertNoForm()
        header().performClick()
        compose.onNodeWithText("Decision title").assertExists()
        compose.onNodeWithText("Choose the next step.").assertExists()
        compose.onNodeWithText("Second description").assertExists()
        compose.onNodeWithText("First description").assertDoesNotExist()
        compose.onAllNodesWithText(text(R.string.question_selected, "Second choice"), useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Submitted condition").assertExists()
        compose.onNodeWithText("Uncommitted note").assertDoesNotExist()
        assertNoForm()
        header().performClick()
        compose.onNodeWithText("Decision title").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, submissions); assertTrue(drafts.isEmpty()) }
    }

    @Test fun missingHistoricalAnswerNeverFallsBackToRestoredDraftFields() {
        show(fixture().copy(status = AgentQuestionStatus.Answered, answer = null,
            selectedOptionId = "a", answerKind = "other", otherText = "Unsent custom choice", note = "Unsent note"))
        header().performClick()
        compose.onNodeWithText("Decision title").assertExists()
        compose.onNodeWithText("Unsent custom choice", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Unsent note").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.question_selected, "First choice")).assertDoesNotExist()
        assertNoForm()
        compose.runOnIdle { assertTrue(drafts.isEmpty()); assertEquals(0, submissions) }
    }

    @Test fun cancelledAndInterruptedCollapseAndNeverPresentADraftAsAnAnswer() {
        show(fixture().copy(selectedOptionId = "a", note = "Unsent condition"))
        for (status in listOf(AgentQuestionStatus.Cancelled, AgentQuestionStatus.Interrupted)) {
            compose.runOnIdle { message.value = message.value.copy(status = status) }
            assertCompactSummary()
            compose.onNodeWithText("Decision title").assertDoesNotExist()
            assertNoForm()
            header().performClick()
            compose.onNodeWithText("Decision title").assertExists()
            compose.onNodeWithText("Unsent condition").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.question_selected, "First choice")).assertDoesNotExist()
            assertNoForm()
        }
        compose.runOnIdle { assertEquals(0, submissions) }
    }

    @Test fun historicalOtherAndDelegationAreReadableWithoutInputFields() {
        show(fixture().copy(status = AgentQuestionStatus.Answered,
            answer = AgentQuestionAnswer("other", otherText = "Custom answer", note = "Custom note")))
        header().performClick()
        val otherSummary = text(R.string.question_selected, text(R.string.question_other) + " Custom answer")
        compose.onAllNodesWithText(otherSummary, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Custom note").assertExists()
        assertNoForm()
        compose.runOnIdle { message.value = message.value.copy(answer = AgentQuestionAnswer("delegate")) }
        compose.onAllNodesWithText(text(R.string.question_selected, text(R.string.question_delegate)), useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onNodeWithText(text(R.string.question_delegate_hint)).assertExists()
        assertNoForm()
    }

    @Test fun longQuestionDescriptionsAndDelegationScopeNeverRequireSeparateExpansion() {
        val initial = fixture()
        val body = (1..9).joinToString("\n") { "Question condition $it" }
        val description = (1..5).joinToString("\n") { "Option condition $it" }
        show(initial.copy(request = initial.request.copy(question = body,
            options = listOf(initial.request.options[0].copy(description = description), initial.request.options[1]))))
        assertNoVisualOverflow(body)
        assertEquals(9, layout(body).lineCount)
        assertNoVisualOverflow(description)
        assertEquals(5, layout(description).lineCount)
        assertNoVisualOverflow(text(R.string.question_delegate_hint))
        compose.onNodeWithText(text(R.string.question_show_full_text)).assertDoesNotExist()
        header().performClick()
        compose.onNodeWithText(body).assertDoesNotExist()
        header().performClick()
        assertNoVisualOverflow(body)
        assertNoVisualOverflow(description)
        compose.runOnIdle { assertEquals(0, submissions) }
    }

    @Test fun disclosureIsIsolatedByEveryQuestionOwnerFieldNotUiMessageId() {
        val initial = fixture()
        val request = initial.request
        show(initial.copy(selectedOptionId = "a"), updateSubmitting = false)
        for (next in listOf(request.copy(conversationId = "other-conversation"), request.copy(runId = "other-run"),
            request.copy(toolCallId = "other-call"), request.copy(questionId = "other-question"))) {
            compose.runOnIdle { message.value = initial.copy(request = request, selectedOptionId = "a") }
            header().performClick()
            compose.onNodeWithText(request.question).assertDoesNotExist()
            compose.runOnIdle { message.value = initial.copy(request = next, selectedOptionId = "a") }
            compose.onNodeWithText(request.question).assertExists() // waiting owner starts expanded
            compose.onNodeWithText(text(R.string.question_submit)).assertIsEnabled().performClick()
        }
        compose.runOnIdle { assertEquals(4, submissions) }
    }

    @Test fun expandedHistoryDoesNotLeakIntoAnotherOwnerOrAnotherTerminalStatus() {
        show(fixture().copy(status = AgentQuestionStatus.Cancelled))
        header().performClick()
        compose.onNodeWithText("Decision title").assertExists()
        compose.runOnIdle { message.value = message.value.copy(request = message.value.request.copy(toolCallId = "another-call")) }
        compose.onNodeWithText("Decision title").assertDoesNotExist()
        header().performClick()
        compose.runOnIdle { message.value = message.value.copy(status = AgentQuestionStatus.Answered, answer = AgentQuestionAnswer("option", "b")) }
        compose.onNodeWithText("Decision title").assertDoesNotExist()
        assertNoForm()
    }

    @Test fun fastReverseToggleKeepsFormAndNeverQueriesBottomAnchor() {
        // 标准气泡统一了展开方向后，卡片不再读取底部锚定状态（旧实现按锚定分方向，
        // 每次点击查询一次）；这里反向钉住这个负向契约，防止读取悄悄回来。
        var anchorQueries = 0
        show(anchorQuery = { anchorQueries++; true })
        compose.runOnIdle { assertEquals(0, anchorQueries) }
        compose.mainClock.autoAdvance = false
        header().performClick()
        compose.mainClock.advanceTimeBy(32)
        header().performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Choose the next step.").assertExists()
        compose.runOnIdle { assertEquals(0, anchorQueries); assertEquals(0, submissions) }
    }

    @Test fun submittingDisablesFormAndExplicitRetryRemainsPossibleAfterRejection() {
        show(fixture().copy(selectedOptionId = "a"))
        compose.onNodeWithText(text(R.string.question_submit)).performClick()
        compose.runOnIdle { assertEquals(1, submissions) }
        compose.onNodeWithText(text(R.string.question_submitting)).assertIsNotEnabled()
        compose.onNodeWithText("Choose the next step.").assertExists()
        compose.onAllNodes(role(Role.RadioButton)).assertCountEquals(4)
        option("Second choice").assertIsNotEnabled()
        field(R.string.question_note).assertIsNotEnabled()
        compose.runOnIdle { message.value = message.value.copy(submitting = false, error = "Try again") }
        compose.onNodeWithText("Try again").assertExists()
        field(R.string.question_note).assertIsEnabled()
        compose.onNodeWithText(text(R.string.question_submit)).assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, submissions) }
    }

    @Test fun submittingDisablesOtherAnswerAndNoteUntilRetry() {
        show(fixture().copy(answerKind = "other", otherText = "Custom answer", note = "Keep note"))
        compose.onNodeWithText(text(R.string.question_submit)).performClick()
        field(R.string.question_other_hint).assertIsNotEnabled().assertTextContains("Custom answer")
        field(R.string.question_note).assertIsNotEnabled().assertTextContains("Keep note")
        compose.runOnIdle {
            assertEquals(1, submissions)
            assertTrue(drafts.isEmpty())
            message.value = message.value.copy(submitting = false, error = "Try again")
        }
        field(R.string.question_other_hint).assertIsEnabled().performTextReplacement("Revised answer")
        field(R.string.question_note).assertIsEnabled().performTextReplacement("Revised note")
        compose.runOnIdle {
            assertEquals("Revised answer", message.value.otherText)
            assertEquals("Revised note", message.value.note)
            assertEquals("other", message.value.answerKind)
        }
    }

    @Test fun coalescedFastFailureWithUnchangedErrorDoesNotLatchForm() {
        show(fixture().copy(selectedOptionId = "a", error = "Same failure"), updateSubmitting = false)
        val click = compose.onNodeWithText(text(R.string.question_submit)).fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {
            click()
            val before = message.value
            message.value = before.copy(submitting = true, error = null)
            message.value = before
        }
        compose.onNodeWithText(text(R.string.question_submit)).assertIsEnabled().performClick()
        option("Second choice").assertIsEnabled()
        compose.runOnIdle { assertEquals(2, submissions) }
    }

    private fun show(initial: AgentQuestionMessageUi = fixture(), updateSubmitting: Boolean = true, anchorQuery: () -> Boolean = { false }) {
        message.value = initial
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Column(Modifier.width(340.dp).verticalScroll(rememberScrollState())) {
                    CompositionLocalProvider(LocalExpansionHoldsBottom provides anchorQuery) {
                    AgentQuestionCard(message.value, Modifier.testTag(CARD), onDraftChanged = { answer ->
                        drafts += answer
                        message.value = message.value.copy(answerKind = answer.kind, selectedOptionId = answer.optionId,
                            otherText = answer.otherText, note = answer.note)
                    }, onSubmit = {
                        submissions++
                        if (updateSubmitting) message.value = message.value.copy(submitting = true)
                    })
                    }
                }
            }
        }
    }

    private fun assertCompactSummary() {
        val height = compose.onNodeWithTag(CARD).fetchSemanticsNode().boundsInRoot.height
        assertTrue("A terminal card should be a single compact row", height <= with(compose.density) { 64.dp.toPx() })
    }

    private fun assertNoForm() {
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onAllNodes(role(Role.RadioButton)).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.question_submit)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.question_submitting)).assertDoesNotExist()
    }

    private fun role(value: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, value)
    private fun option(label: String) = compose.onNode(hasText(label) and role(Role.RadioButton))
    // 标题行是官方 SegmentedListItem(onClick)：整行可点但没有 Role.Button，
    // 用「可点击 + 含标题文本」匹配展开行本身。
    private fun header() = compose.onNode(hasClickAction() and hasText("Decision title", substring = true))
    private fun field(id: Int) = compose.onNodeWithContentDescription(text(id))
    private fun text(id: Int, vararg args: Any): String = RuntimeEnvironment.getApplication().getString(id, *args)

    private fun assertNoVisualOverflow(value: String) {
        val result = layout(value)
        val diagnostic = "text=$value; size=${result.size}; paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}; " +
            "constraints=${result.layoutInput.constraints}; lines=${result.lineCount}; " +
            "maxLines=${result.layoutInput.maxLines}; softWrap=${result.layoutInput.softWrap}; overflow=${result.layoutInput.overflow}; " +
            "heightOverflow=${result.didOverflowHeight}; " +
            "lastBottom=${result.getLineBottom(result.lineCount - 1)}"
        // 不再检查 didOverflowWidth/lastEnd：ListItem supporting 槽位存在两次测量
        // （size 与换行宽度不一致）的框架测量假阳性。真实契约由下面三项把守：
        // 行数与内容匹配、无任何一行被省略、无垂直裁切；叠加调用方的
        // 「question_show_full_text 必须不存在」断言，展开态必然完整渲染。
        assertFalse(diagnostic, result.didOverflowHeight)
        repeat(result.lineCount) { assertFalse("$diagnostic line $it ellipsized", result.isLineEllipsized(it)) }
    }

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue(it(results))
        }
        return results.single()
    }

    private fun fixture() = AgentQuestionMessageUi("same-ui-slot", AgentQuestionRequest(
        questionId = "question", conversationId = "conversation", runId = "run", toolCallId = "call",
        title = "Decision title", question = "Choose the next step.",
        options = listOf(AgentQuestionOption("a", "First choice", "First description"),
            AgentQuestionOption("b", "Second choice", "Second description")), recommendedOptionId = "a"))

    private companion object { const val CARD = "compact-question-card" }
}
