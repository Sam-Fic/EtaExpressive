package io.github.mangi.eta.agent.media.index

import io.github.mangi.eta.data.db.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MediaSearchService.applyMinScore] 的纯函数单测：匹配度阈值过滤。
 *
 * 覆盖：0（及负值）不过滤、低于阈值被过滤、边界含等于（score == minScore 保留）、
 * 过滤不改变原有排序。批量删除依赖 Context / ContentResolver / 文件系统，
 * 不属纯函数，状态机逻辑不在此测（只测过滤）。
 */
class MediaSearchThresholdTest {

    private fun hit(sourceKey: String, score: Float): MediaSearchService.Hit = MediaSearchService.Hit(
        sourceKey = sourceKey,
        kind = MediaKind.IMAGE,
        displayName = sourceKey,
        mime = "image/jpeg",
        score = score,
        contentUri = null,
        path = "/storage/emulated/0/$sourceKey.jpg",
        sizeBytes = 0L,
        modifiedAt = 0L,
        durationMs = null,
        width = null,
        height = null,
        thumbPath = null,
    )

    @Test
    fun zeroThresholdFiltersNothing() {
        val hits = listOf(hit("a", 0.9f), hit("b", 0.05f), hit("c", 0f))
        // 0 表示不过滤：连 0 分命中也保留。
        assertEquals(hits, MediaSearchService.applyMinScore(hits, { it.score }, 0f))
        // 负值同样不过滤（调用方可能传 -0.01 之类的越界值）。
        assertEquals(hits, MediaSearchService.applyMinScore(hits, { it.score }, -1f))
    }

    @Test
    fun dropsHitsBelowThreshold() {
        val hits = listOf(hit("a", 0.9f), hit("b", 0.3f), hit("c", 0.55f))
        assertEquals(
            listOf("a", "c"),
            MediaSearchService.applyMinScore(hits, { it.score }, 0.55f).map { it.sourceKey },
        )
    }

    @Test
    fun keepsHitExactlyAtThreshold() {
        // 边界含等于：恰好等于阈值的命中保留。
        val hits = listOf(hit("eq", 0.55f), hit("below", 0.549f))
        assertEquals(
            listOf("eq"),
            MediaSearchService.applyMinScore(hits, { it.score }, 0.55f).map { it.sourceKey },
        )
    }

    @Test
    fun keepsOrderWhenFiltering() {
        // 过滤只删行、不重排：入参保持 rank 给出的降序顺序，输出仍是同一顺序。
        val hits = listOf(hit("a", 0.9f), hit("e", 0.7f), hit("c", 0.5f), hit("b", 0.2f), hit("d", 0.1f))
        assertEquals(
            listOf("a", "e", "c"),
            MediaSearchService.applyMinScore(hits, { it.score }, 0.4f).map { it.sourceKey },
        )
    }

    @Test
    fun returnsEmptyWhenEverythingBelowThreshold() {
        val hits = listOf(hit("a", 0.1f), hit("b", 0.2f))
        assertEquals(emptyList<MediaSearchService.Hit>(), MediaSearchService.applyMinScore(hits, { it.score }, 0.9f))
        assertEquals(
            emptyList<MediaSearchService.Hit>(),
            MediaSearchService.applyMinScore(emptyList<MediaSearchService.Hit>(), { it.score }, 0.5f),
        )
    }
}
