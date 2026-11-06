package io.github.mangi.eta.agent.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 岛参数 JSON 必须符合小米官方协议（param_v2 模版 1，安静胶囊）；思维流预览截断规则。
 * 字段值锚定 Number444 fork 的真机工作样本（小米 14 Ultra / HyperOS 3）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLiveIslandTest {

    @Test
    fun focusParamMatchesOfficialTemplate1() {
        val root = JSONObject(MiuiIslandFocus.buildFocusParam("思考中", "Eta", "io.github.mangi.eta", 1208))
        val v2 = root.getJSONObject("param_v2")
        assertEquals(1, v2.getInt("protocol"))
        assertEquals("io.github.mangi.eta1208", v2.getString("notifyId"))
        assertEquals(false, v2.getBoolean("islandFirstFloat"))
        assertEquals(true, v2.getBoolean("updatable"))
        assertEquals("思考中", v2.getString("ticker"))
        assertEquals(MiuiIslandFocus.PIC_KEY, v2.getString("tickerPic"))
        assertEquals("思考中", v2.getJSONObject("baseInfo").getString("title"))
        assertEquals("Eta", v2.getJSONObject("baseInfo").getString("content"))
        val island = v2.getJSONObject("param_island")
        val bigArea = island.getJSONObject("bigIslandArea").getJSONObject("imageTextInfoLeft")
        assertEquals(1, bigArea.getInt("type"))
        assertEquals(MiuiIslandFocus.PIC_KEY, bigArea.getJSONObject("picInfo").getString("pic"))
        assertEquals("思考中", bigArea.getJSONObject("textInfo").getString("title"))
        assertEquals(
            MiuiIslandFocus.PIC_KEY,
            island.getJSONObject("smallIslandArea").getJSONObject("picInfo").getString("pic"),
        )
    }

    @Test
    fun previewTakesLastNonBlankLineWithTailEllipsis() {
        assertEquals("hello", AgentRunStreamTracker.extractSingleLinePreview("first\nsecond\nhello"))
        assertEquals("", AgentRunStreamTracker.extractSingleLinePreview("   "))
        val preview = AgentRunStreamTracker.extractSingleLinePreview("x".repeat(100))
        assertEquals(43, preview.length) // "…" + 42
        assertTrue(preview.startsWith("…"))
    }

    @Test
    fun tailTruncatesWithEllipsisPrefix() {
        val tail = AgentRunStreamTracker.extractTail("y".repeat(400))
        assertEquals(321, tail.length) // "…" + 320
        assertTrue(tail.startsWith("…"))
        assertEquals("y".repeat(320), tail.drop(1))
        assertEquals(300, AgentRunStreamTracker.extractTail("y".repeat(300)).length)
    }

    @Test
    fun toolPreviewPrefersCommandAndCollapsesLines() {
        assertEquals("adb shell ls", AgentRunStreamTracker.formatToolPreview("adb\nshell ls", "args"))
        assertEquals("args", AgentRunStreamTracker.formatToolPreview(null, "args"))
        assertEquals("", AgentRunStreamTracker.formatToolPreview(null, "  "))
        val preview = AgentRunStreamTracker.formatToolPreview("z".repeat(50), "")
        assertEquals("z".repeat(42) + "…", preview)
    }
}
