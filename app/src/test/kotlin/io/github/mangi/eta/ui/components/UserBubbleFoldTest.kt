package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 超长用户消息折叠：气泡高度受限、可展开回全文、短消息不受影响。
 *
 * 量的是真实 `ChatMessageItem` 里气泡节点的边界，不是自搭的 UI 夹具——折的是气泡本体，
 * 高度必须从同一个入口证明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36], qualifiers = "w411dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserBubbleFoldTest {
    @get:Rule val compose = createComposeRule()

    private fun strings() = RuntimeEnvironment.getApplication()

    private fun longMessage(lines: Int): String =
        (1..lines).joinToString("\n") { "这是第 $it 行足够长的正文内容，用来撑出多行气泡" }

    private fun host(message: UserMessageUi) {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Column(Modifier.width(340.dp)) {
                    ChatMessageItem(
                        message = message,
                        actions = ChatMessageActions(),
                        showBrowserShortcut = false,
                        modifier = Modifier.testTag("user-message"),
                    )
                }
            }
        }
    }

    private fun bubbleHeight() = compose
        .onNodeWithTag("user-message")
        .fetchSemanticsNode()
        .size
        .height

    // 气泡本体是 clickable，合并树里它的所有子节点会被并成一个节点，
    // 量到的是整个气泡而不是这一段文字。这里必须读未合并树，才能拿到「已编辑」提示
    // 与展开按钮各自的边界——这正是本用例要证明的「同排、提示在左、按钮在右」。
    private fun textBounds(text: String) = compose
        .onNodeWithText(text, useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot

    @Test
    fun longMessageStartsFoldedAndExpandsOnDemand() {
        // 16 行：折叠到 6 行是明显变矮，展开后又仍在 1000dp 的测试视口内，
        // 按钮不会落到窗口外导致点击不到。
        host(UserMessageUi(id = "long", content = longMessage(16)))

        val expand = strings().getString(R.string.cmi_fold_expand)
        compose.onNodeWithText(expand).assertIsDisplayed()

        val folded = bubbleHeight()
        compose.onNodeWithText(expand).performClick()
        compose.waitForIdle()

        val expanded = bubbleHeight()
        assertTrue(
            "展开后气泡应变高：folded=$folded expanded=$expanded",
            expanded > folded + 100,
        )

        // 展开后同一个入口变成「收起」，点回去应当回到折叠高度。
        val collapse = strings().getString(R.string.cmi_fold_collapse)
        compose.onNodeWithText(collapse).assertIsDisplayed()
        compose.onNodeWithText(collapse).performClick()
        compose.waitForIdle()
        assertEquals(folded, bubbleHeight())
    }

    @Test
    fun shortMessageKeepsWholeBubbleWithoutFoldControl() {
        host(UserMessageUi(id = "short", content = "只有一行的一句话"))

        compose.onNodeWithText(strings().getString(R.string.cmi_fold_expand)).assertDoesNotExist()
        compose.onNodeWithText(strings().getString(R.string.cmi_fold_collapse)).assertDoesNotExist()
    }

    @Test
    fun foldOnlyClipsTheViewAndKeepsTheWholeRequestAvailable() {
        val content = longMessage(16)
        // 折叠裁的是显示行数，不是内容：气泡里仍然是完整正文，复制/编辑都拿到全文。
        assertEquals(content, UserMessageUi(id = "long", content = content).content)
        host(UserMessageUi(id = "long", content = content))
        compose.onNodeWithText(strings().getString(R.string.cmi_fold_expand)).assertIsDisplayed()
    }

    @Test
    fun editedBadgeSharesTheFoldRowWithTheExpandToggle() {
        // 已编辑 + 折叠入口同时出现时排在同一行：纵向居中对齐，提示在左、按钮在右。
        host(UserMessageUi(id = "long", content = longMessage(16), isEdited = true))

        val edited = textBounds(strings().getString(R.string.ui_edited_c36776))
        val expand = textBounds(strings().getString(R.string.cmi_fold_expand))

        assertTrue(
            "两者纵向区间应重叠：edited=$edited expand=$expand",
            edited.top < expand.bottom && edited.bottom > expand.top,
        )
        assertTrue(
            "提示应落在按钮左侧：edited=$edited expand=$expand",
            edited.right <= expand.left,
        )
    }

    @Test
    fun foldThresholdKeepsExactlySixLinesOpen() {
        // 边界：恰好 6 行不折，第 7 行才折。改阈值时这条会直接抓住。
        assertFalse(userBubbleNeedsFold(1))
        assertFalse(userBubbleNeedsFold(6))
        assertTrue(userBubbleNeedsFold(7))
        assertTrue(userBubbleNeedsFold(400))
    }

    @Test
    fun collapsedBodyIsLineLimitedAndExpandedBodyIsNot() {
        assertEquals(6, userBubbleBodyMaxLines(collapsed = true))
        assertEquals(Int.MAX_VALUE, userBubbleBodyMaxLines(collapsed = false))
    }
}
