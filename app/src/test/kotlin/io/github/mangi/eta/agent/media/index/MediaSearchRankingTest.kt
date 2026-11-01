package io.github.mangi.eta.agent.media.index

import io.github.mangi.eta.data.db.MediaIndexEntity
import io.github.mangi.eta.data.db.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [MediaSearchService.rank] / [MediaSearchService.dot] 的纯函数单测：
 * 用构造向量验证点积降序排名、top-K 截断、kinds 过滤、维度不一致与损坏向量的跳过、
 * limit 越界时按 coerceIn(1, 100) 收敛。
 *
 * 向量归一化由嵌入层保证（见 EtaEmbeddingEngine），归一化后余弦相似度即点积，
 * 因此这里只验证「点积越大越靠前」的排名契约。Half 编解码依赖 Robolectric。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaSearchRankingTest {

    private fun row(
        sourceKey: String,
        vector: FloatArray,
        kind: MediaKind = MediaKind.IMAGE,
    ): MediaIndexEntity = MediaIndexTestFixtures.entity(
        sourceKey = sourceKey,
        vector = vector,
        kind = kind,
        vectorDim = vector.size,
    )

    @Test
    fun dotComputesInnerProduct() {
        assertEquals(32f, MediaSearchService.dot(floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f)), 1e-6f)
        // 正交为 0，负相关为负值。
        assertEquals(0f, MediaSearchService.dot(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6f)
        assertEquals(-2.5f, MediaSearchService.dot(floatArrayOf(-1f, 2f), floatArrayOf(2f, -0.25f)), 1e-6f)
    }

    @Test
    fun rankSortsByDescendingDotProduct() {
        val query = floatArrayOf(1f, 0f)
        val ranked = MediaSearchService.rank(
            query,
            listOf(
                row("low", floatArrayOf(0f, 1f)),
                row("high", floatArrayOf(1f, 0f)),
                row("neg", floatArrayOf(-1f, 0f)),
                row("mid", floatArrayOf(0.5f, 0.5f)),
            ),
        )
        assertEquals(listOf("high", "mid", "low", "neg"), ranked.map { it.first.sourceKey })
        assertEquals(listOf(1f, 0.5f, 0f, -1f), ranked.map { it.second })
    }

    @Test
    fun rankTruncatesToLimit() {
        val query = floatArrayOf(1f, 0f)
        val rows = listOf(
            row("high", floatArrayOf(1f, 0f)),
            row("mid", floatArrayOf(0.5f, 0.5f)),
            row("low", floatArrayOf(0f, 1f)),
        )
        val ranked = MediaSearchService.rank(query, rows, limit = 2)
        assertEquals(listOf("high", "mid"), ranked.map { it.first.sourceKey })
    }

    @Test
    fun rankFiltersByKindsAndKeepsAllWhenEmpty() {
        val query = floatArrayOf(1f, 0f)
        val rows = listOf(
            row("image-1", floatArrayOf(1f, 0f), MediaKind.IMAGE),
            row("video-1", floatArrayOf(0.9f, 0.1f), MediaKind.VIDEO),
            row("doc-1", floatArrayOf(0.8f, 0.2f), MediaKind.DOCUMENT),
        )
        assertEquals(
            listOf("image-1", "video-1"),
            MediaSearchService.rank(query, rows, kinds = setOf(MediaKind.IMAGE, MediaKind.VIDEO))
                .map { it.first.sourceKey },
        )
        // 空 kinds 表示不过滤。
        assertEquals(3, MediaSearchService.rank(query, rows).size)
    }

    @Test
    fun rankSkipsRowsWithMismatchedDimOrBrokenVector() {
        val query = floatArrayOf(1f, 0f)
        val ranked = MediaSearchService.rank(
            query,
            listOf(
                MediaIndexTestFixtures.entity(
                    sourceKey = "broken",
                    vectorDim = 2,
                    rawVector = ByteArray(3), // 奇数字节数：解码必然失败
                ),
                MediaIndexTestFixtures.entity(
                    sourceKey = "wide",
                    vector = floatArrayOf(1f, 0f, 0f),
                    vectorDim = 3,
                ),
                row("ok", floatArrayOf(1f, 0f)),
            ),
        )
        // 维度不一致与损坏 BLOB 都应被跳过，而不是抛异常或污染排名。
        assertEquals(listOf("ok"), ranked.map { it.first.sourceKey })
    }

    @Test
    fun rankCoercesLimitIntoOneAndAppliesNoUpperBound() {
        val query = floatArrayOf(1f, 0f)
        val rows = (0 until 105).map { index -> row("row-$index", floatArrayOf(1f, 0f)) }

        // limit <= 0 收敛到 1，仍至少返回 top-1。
        assertEquals(1, MediaSearchService.rank(query, rows, limit = 0).size)
        assertEquals(1, MediaSearchService.rank(query, rows, limit = -7).size)
        // 数量上限由调用方自负（GUI 传 UNLIMITED_RESULTS）：大 limit 不截断。
        assertEquals(105, MediaSearchService.rank(query, rows, limit = 1_000).size)
    }

    @Test
    fun rankReturnsEmptyForEmptyRows() {
        assertEquals(emptyList<Pair<MediaIndexEntity, Float>>(), MediaSearchService.rank(floatArrayOf(1f), emptyList()))
    }

    @Test
    fun rankOrdersByScoreRegardlessOfFrameTimeMs() {
        // frameTimeMs 是二段精排产物，不参与首段排名：带时刻的低分行仍排在高分行之后。
        val query = floatArrayOf(1f, 0f)
        val rows = listOf(
            MediaIndexTestFixtures.entity("with-moment", floatArrayOf(0.5f, 0.5f), frameTimeMs = 12_000L),
            MediaIndexTestFixtures.entity("plain", floatArrayOf(1f, 0f)),
        )
        assertEquals(listOf("plain", "with-moment"), MediaSearchService.rank(query, rows).map { it.first.sourceKey })
    }

    @Test
    fun hitOfCarriesFrameTimeMsForVideoMoments() {
        val context = RuntimeEnvironment.getApplication()
        val row = MediaIndexTestFixtures.entity(
            sourceKey = "/storage/emulated/0/DCIM/beach.mp4",
            vector = floatArrayOf(1f, 0f),
            kind = MediaKind.VIDEO,
            vectorDim = 2,
            frameTimeMs = 12_000L,
        )
        // 命中时刻随结果行带出，供 GUI 显示 01:23、工具输出 matched_at_ms。
        assertEquals(12_000L, MediaSearchService.hitOf(context, row, 0.83f).frameTimeMs)
        // 二段精排未执行/失败时字段为空，展示侧据此省略命中时刻。
        assertNull(MediaSearchService.hitOf(context, row.copy(frameTimeMs = null), 0.5f).frameTimeMs)
    }

    @Test
    fun annSwitchHintOnlyAboveThreshold() {
        // 仅「超过」阈值才提示切换 ANN；等于阈值仍按暴力检索正常工作。
        assertFalse(MediaSearchService.exceedsAnnSwitchThreshold(0))
        assertFalse(MediaSearchService.exceedsAnnSwitchThreshold(MediaSearchService.ANN_SWITCH_THRESHOLD))
        assertTrue(MediaSearchService.exceedsAnnSwitchThreshold(MediaSearchService.ANN_SWITCH_THRESHOLD + 1))
    }

    @Test
    fun statusApproachingAnnSwitchFollowsThreshold() {
        fun status(indexedCount: Int) = MediaSearchService.Status(
            modelDownloaded = true,
            modelId = EmbeddingModelCatalog.OMNIMODAL_ID,
            dim = 768,
            indexedCount = indexedCount,
            lastIndexedAt = null,
            enabledSources = 1,
            indexing = false,
        )
        assertFalse(status(MediaSearchService.ANN_SWITCH_THRESHOLD).approachingAnnSwitch)
        assertTrue(status(MediaSearchService.ANN_SWITCH_THRESHOLD + 1).approachingAnnSwitch)
    }
}
