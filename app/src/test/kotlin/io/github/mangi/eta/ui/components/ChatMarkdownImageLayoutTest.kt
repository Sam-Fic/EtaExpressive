package io.github.mangi.eta.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentMessageUi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = io.github.mangi.eta.EtaApp::class, sdk = [36], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatMarkdownImageLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val imageDescription get() = RuntimeEnvironment.getApplication().getString(R.string.chat_image_preview)
    private fun image(name: String, width: Int, height: Int): String {
        val file = File(RuntimeEnvironment.getApplication().cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(190,215,232))
        // Colored edges make cropped/overflowing previews obvious in the captured regression view.
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply { color = Color.rgb(30,80,120) }
        canvas.drawRect(0f,0f,width.toFloat(),8f,paint)
        canvas.drawRect(0f,height-8f,width.toFloat(),height.toFloat(),paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        return file.absolutePath
    }
    private fun show(content: androidx.compose.runtime.State<AgentMessageUi>, open: (String)->Unit = {}) {
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                CompositionLocalProvider(LocalOpenChatImagePreview provides open) {
                    Column(Modifier.width(360.dp).background(androidx.compose.ui.graphics.Color.White).testTag("message")
                        .verticalScroll(rememberScrollState())) {
                        ChatMessageItem(content.value, androidx.compose.runtime.remember { ChatMessageActions() }, false, modifier=Modifier.fillMaxWidth(),
                            showCopyAction=false, isPaused=true)
                    }
                }
            }
        }
    }
    /**
     * 图片解码跑在 Dispatchers.IO 上（ChatImageBytes.load 是阻塞 IO）。语义节点出现只说明
     * bitmap 已 set，**不代表解码协程已经退出**；此时若直接结束用例，Composition 销毁与
     * 后台线程的最后一次布局会竞争，Robolectric 下会随机崩在
     * CalledFromWrongThreadException / SlotTable 越界。
     *
     * 因此除了等节点数量，还要再等一拍（waitForIdle）让已排队的重组与布局收敛，
     * 并让出一次线程让 IO 协程有机会彻底结束。
     */
    private fun waitForImages(count: Int) {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription(imageDescription,useUnmergedTree=true).fetchSemanticsNodes().size == count
        }
        compose.waitForIdle()
        // 已排队的重组/布局落定后再确认一次，避免在「刚 set、尚未布局」的窗口里取边界。
        compose.waitUntil(15_000) {
            compose.onAllNodesWithContentDescription(imageDescription,useUnmergedTree=true)
                .fetchSemanticsNodes().size == count
        }
        compose.waitForIdle()
    }
    private fun textBounds(text: String): Rect = compose.onNodeWithText(text,substring=true,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot

    @Test fun captionsAndImagesHaveSeparateBoundsAndImageClickStillOpensOriginal() {
        val portrait=image("portrait-regression.png",180,320)
        val landscape=image("landscape-regression.png",320,180)
        val content=mutableStateOf(AgentMessageUi(id="image-layout",content="9:16  \n![generated]($portrait)\n\n16:9  \n![generated]($landscape)\n\n图片后的完整说明",renderMarkdown=true))
        var opened=""
        show(content) { opened=it }
        waitForImages(2)
        val images=compose.onAllNodesWithContentDescription(imageDescription,useUnmergedTree=true).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }
        val first=images[0].boundsInRoot; val second=images[1].boundsInRoot
        assertTrue("caption must be above portrait",textBounds("9:16").bottom <= first.top)
        assertTrue("portrait must end before next caption",first.bottom <= textBounds("16:9").top)
        assertTrue("second caption must be above landscape",textBounds("16:9").bottom <= second.top)
        assertTrue("landscape cannot cover following text",second.bottom <= textBounds("图片后的完整说明").top)
        val firstMeasured=compose.onAllNodesWithContentDescription(imageDescription,useUnmergedTree=true)[0].getUnclippedBoundsInRoot()
        val secondMeasured=compose.onAllNodesWithContentDescription(imageDescription,useUnmergedTree=true)[1].getUnclippedBoundsInRoot()
        // Use actual layout bounds, not the scroll viewport's clipped bounds.
        assertTrue("portrait preview must reserve its bounded height",(firstMeasured.bottom - firstMeasured.top) >= 319.dp && (firstMeasured.bottom - firstMeasured.top) <= 321.dp)
        assertTrue("landscape preview must also be bounded",(secondMeasured.bottom - secondMeasured.top) > 0.dp && (secondMeasured.bottom - secondMeasured.top) <= 321.dp)
        assertTrue("not a tiny inline thumbnail",(firstMeasured.right - firstMeasured.left) >= 300.dp)
        compose.onAllNodesWithContentDescription(imageDescription,useUnmergedTree=true)[0].performClick()
        compose.runOnIdle { assertEquals(portrait,opened) }
        val out=File("build/reports/tests/testDebugUnitTest/image-caption-layout.png")
        out.parentFile.mkdirs()
        out.outputStream().use { compose.onNodeWithTag("message").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
    }

    @Test fun repeatedImageAndInlineFollowingTextRemainVisibleInOrder() {
        val source=image("same-image-regression.png",320,100)
        val content=mutableStateOf(AgentMessageUi(id="same-image",content="第一段 ![one]($source) 中间文字 ![two]($source) 末尾说明",renderMarkdown=true))
        show(content); waitForImages(2)
        val images=compose.onAllNodesWithContentDescription(imageDescription,useUnmergedTree=true).fetchSemanticsNodes().sortedBy { it.boundsInRoot.top }
        assertTrue(textBounds("第一段").bottom <= images[0].boundsInRoot.top)
        assertTrue(images[0].boundsInRoot.bottom <= textBounds("中间文字").top)
        assertTrue(textBounds("中间文字").bottom <= images[1].boundsInRoot.top)
        assertTrue(images[1].boundsInRoot.bottom <= textBounds("末尾说明").top)
    }

    @Test fun streamingImageClosureAndCompletionDoNotCoverOrDuplicateCaptions() {
        val source=image("streaming-image-regression.png",240,120)
        val content=mutableStateOf(AgentMessageUi(id="stream-image",content="流式标签\n![generated](",isStreaming=true,renderMarkdown=true))
        show(content)
        // First close the image while the live renderer is active, then finalize the message.
        compose.runOnIdle { content.value=content.value.copy(content="流式标签\n![generated]($source)\n\n结束说明") }
        waitForImages(1)
        val liveImage=compose.onNodeWithContentDescription(imageDescription,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue(textBounds("流式标签").bottom <= liveImage.top)
        compose.runOnIdle { content.value=content.value.copy(isStreaming=false) }
        waitForImages(1)
        val image=compose.onNodeWithContentDescription(imageDescription,useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertTrue(textBounds("流式标签").bottom <= image.top)
        assertTrue(image.bottom <= textBounds("结束说明").top)
        compose.onAllNodesWithText("流式标签",substring=true,useUnmergedTree=true).assertCountEquals(1)
    }
}
