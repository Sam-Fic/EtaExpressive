package io.github.mangi.eta.agent.media.index

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MediaIndexDao
import io.github.mangi.eta.data.db.MediaKind
import io.github.mangi.eta.data.db.MediaSourceEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 媒体索引的增量判断与清理逻辑单测。
 *
 * [MediaIndexer.runIndex] 与 Context/扫描器/引擎耦合，无法整体纯测；这里：
 * - 用假模型 + workspace 扫描源在纯 JVM 上驱动 runIndex 的可测分支：
 *   「(size, mtime) 未变跳过」「变更/新增进入嵌入分支被计数」「消失条目连同缩略图清理」；
 *   测试音频是垃圾字节，抽取阶段（MediaExtraction.audioWindows）即失败让 embedCandidate 返回 null，
 *   不触发 LiteRT 推理，但会证明它们没有被误判为「未变跳过」；
 * - 边界场景直接验证 DAO 契约（commitIndex 的原子 upsert + stale 删除）与
 *   「模型未就绪快速失败」「clear 保留扫描源配置」。
 * 成功嵌入计数（indexed）依赖真实推理，不在纯 JVM 覆盖范围。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaIndexIncrementalTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        MediaIndexTestFixtures.resetModelStore()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        // workspace 是固定扫描目录，清空保证候选集完全由本用例构造。
        MediaIndexTestFixtures.workspaceDir(context).deleteRecursively()
        // 缩略图目录也要清空：自愈分支会用「文件是否存在」决定跳过量。
        MediaThumbnailStore.directory(context).deleteRecursively()
        File(context.cacheDir, "media-thumbs").deleteRecursively()
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        MediaIndexTestFixtures.resetModelStore()
    }

    private fun dao(): MediaIndexDao = EtaDatabase.get(context).mediaIndexDao()

    private fun workspaceSource(): MediaSourceEntity = MediaSourceEntity(
        id = MediaSourceScanner.TYPE_WORKSPACE,
        type = MediaSourceScanner.TYPE_WORKSPACE,
        uri = "",
        label = "终端工作区",
        enabled = true,
    )

    @Test
    fun commitIndexUpsertsAndDeletesStaleAtomically() {
        runBlocking {
            val dao = dao()
            dao.upsertAll(
                listOf(
                    MediaIndexTestFixtures.entity("a", MediaIndexTestFixtures.axisVector(2, 0)),
                    MediaIndexTestFixtures.entity("b", MediaIndexTestFixtures.axisVector(2, 1), sizeBytes = 1_024L),
                ),
            )
            dao.commitIndex(
                upserts = listOf(
                    // 同主键（source_key, model_id, vector_dim）应替换而不是新增行。
                    MediaIndexTestFixtures.entity("b", MediaIndexTestFixtures.axisVector(2, 0), sizeBytes = 2_048L),
                    MediaIndexTestFixtures.entity("c", MediaIndexTestFixtures.axisVector(2, 1)),
                ),
                staleSourceKeys = listOf("a"),
            )
            assertEquals(setOf("b", "c"), dao.all().mapTo(mutableSetOf()) { it.sourceKey })
            assertEquals(2_048L, dao.findBySourceKey("b").single().sizeBytes)
            // 两段都为空时是 no-op，不能误删。
            dao.commitIndex(upserts = emptyList(), staleSourceKeys = emptyList())
            assertEquals(2, dao.all().size)
        }
    }

    @Test
    fun deleteBySourceKeysRemovesRowsAcrossModelsAndDims() {
        runBlocking {
            val dao = dao()
            val vector = MediaIndexTestFixtures.axisVector(2, 0)
            dao.upsertAll(
                listOf(
                    MediaIndexTestFixtures.entity("x", vector, modelId = "m1", vectorDim = 2),
                    MediaIndexTestFixtures.entity("x", vector, modelId = "m2", vectorDim = 2),
                    MediaIndexTestFixtures.entity("y", vector, modelId = "m1", vectorDim = 2),
                ),
            )
            dao.deleteBySourceKeys(listOf("x"))
            assertEquals(listOf("y"), dao.all().map { it.sourceKey })
        }
    }

    @Test
    fun indexFailsFastWhenModelMissingAndLeavesIdleState() {
        MediaIndexTestFixtures.resetModelStore()
        val failure = runCatching { runBlocking { MediaIndexer.index(context) } }.exceptionOrNull()
        // 模型未下载时不扫描、不入库，直接失败，避免索引出无向量的空行。
        assertTrue("应因模型未就绪快速失败，实际：$failure", failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("尚未下载"))
        assertFalse(MediaIndexer.isRunning())
    }

    @Test
    fun unchangedSizeAndMtimeAreSkippedWithoutTouchingVectors() {
        MediaIndexTestFixtures.installFakeModel(context)
        val file = MediaIndexTestFixtures.writeWorkspaceFile(context, "keep.png")
        // 缩略图必须存在且可解析，否则跳过分支会走自愈重建，计入 thumbFailed 而不是 skipped。
        val thumb = File(MediaThumbnailStore.directory(context), MediaThumbnailStore.fileNameFor(file.absolutePath))
            .apply { writeBytes(ByteArray(16)) }
        val before = MediaIndexTestFixtures.entity(
            sourceKey = file.absolutePath,
            vector = MediaIndexTestFixtures.axisVector(768, 0),
            kind = MediaKind.IMAGE,
            sizeBytes = file.length(),
            modifiedAt = file.lastModified(),
            // 旧版存的绝对路径：迁移会归一成文件名，可用性判断与跳过仍成立。
            thumbPath = thumb.absolutePath,
            indexedAt = 111L,
        )
        val result = runBlocking {
            dao().upsertAll(listOf(before))
            dao().upsertSource(workspaceSource())
            MediaIndexer.index(context)
        }
        assertEquals(0, result.indexed)
        assertEquals(1, result.skipped)
        assertEquals(0, result.failed)
        assertEquals(0, result.removed)
        // 未变条目不应重新嵌入：向量与 indexed_at 均保持原样。
        val after = runBlocking { dao().findBySourceKey(file.absolutePath).single() }
        assertEquals(111L, after.indexedAt)
        assertArrayEquals(before.vector, after.vector)
    }

    @Test
    fun unchangedImageWithMissingThumbnailIsRepairedWithoutReembedding() {
        MediaIndexTestFixtures.installFakeModel(context)
        // 垃圾字节图片：缩略图生成必然失败，用来验证自愈分支被触发（而不是被误判成纯跳过）。
        val file = MediaIndexTestFixtures.writeWorkspaceFile(context, "lost-thumb.png")
        val before = MediaIndexTestFixtures.entity(
            sourceKey = file.absolutePath,
            vector = MediaIndexTestFixtures.axisVector(768, 3),
            kind = MediaKind.IMAGE,
            sizeBytes = file.length(),
            modifiedAt = file.lastModified(),
            thumbPath = null,
            indexedAt = 111L,
        )
        val result = runBlocking {
            dao().upsertAll(listOf(before))
            dao().upsertSource(workspaceSource())
            MediaIndexer.index(context)
        }
        assertEquals(0, result.indexed)
        assertEquals(0, result.skipped)
        assertEquals(1, result.thumbFailed)
        // 自愈不重新嵌入：向量与 indexed_at 保持原样。
        val after = runBlocking { dao().findBySourceKey(file.absolutePath).single() }
        assertEquals(111L, after.indexedAt)
        assertArrayEquals(before.vector, after.vector)
    }

    @Test
    fun changedOrNewItemsAreReembeddedAndCounted() {
        MediaIndexTestFixtures.installFakeModel(context)
        // 变更与新增都会进入嵌入分支并计入 failed（垃圾音频抽取返回空窗口，不触发 LiteRT 推理）；
        // 若被误判成「未变跳过」，skipped 会变成 2，failed 为 0。
        val changed = MediaIndexTestFixtures.writeWorkspaceFile(context, "changed.wav")
        val fresh = MediaIndexTestFixtures.writeWorkspaceFile(context, "fresh.mp3")
        val result = runBlocking {
            dao().upsertAll(
                listOf(
                    MediaIndexTestFixtures.entity(
                        sourceKey = changed.absolutePath,
                        kind = MediaKind.AUDIO,
                        sizeBytes = changed.length() - 1L, // 与磁盘不一致：判定为内容变更
                        modifiedAt = changed.lastModified(),
                    ),
                ),
            )
            dao().upsertSource(workspaceSource())
            MediaIndexer.index(context)
        }
        assertEquals(0, result.skipped)
        assertEquals(2, result.failed)
        assertEquals(0, result.indexed)
        assertEquals(0, result.removed)
        // 嵌入失败不落库：旧向量行原样保留，新文件不产生空行，留待下次索引重试。
        val kept = runBlocking { dao().findBySourceKey(changed.absolutePath) }
        assertEquals(changed.length() - 1L, kept.single().sizeBytes)
        assertEquals(emptyList<String>(), runBlocking { dao().findBySourceKey(fresh.absolutePath) }.map { it.sourceKey })
    }

    @Test
    fun disappearedEntriesAreCleanedWithThumbnails() {
        MediaIndexTestFixtures.installFakeModel(context)
        val kept = MediaIndexTestFixtures.writeWorkspaceFile(context, "kept.png")
        // 缩略图统一放持久目录（旧版在 cacheDir，迁移后寻址为文件名）。
        val keptThumb = File(MediaThumbnailStore.directory(context), MediaThumbnailStore.fileNameFor(kept.absolutePath))
            .apply { writeBytes(ByteArray(16)) }
        val goneThumb = File(MediaThumbnailStore.directory(context), "gone-thumb.jpg").apply { writeBytes(ByteArray(16)) }
        val goneKey = File(MediaIndexTestFixtures.workspaceDir(context), "gone.png").absolutePath
        val result = runBlocking {
            dao().upsertAll(
                listOf(
                    MediaIndexTestFixtures.entity(
                        sourceKey = kept.absolutePath,
                        kind = MediaKind.IMAGE,
                        sizeBytes = kept.length(),
                        modifiedAt = kept.lastModified(),
                        thumbPath = keptThumb.name,
                    ),
                    MediaIndexTestFixtures.entity(
                        sourceKey = goneKey,
                        kind = MediaKind.IMAGE,
                        // 旧版绝对路径：迁移归一后仍应删除对应文件。
                        thumbPath = goneThumb.absolutePath,
                    ),
                ),
            )
            dao().upsertSource(workspaceSource())
            MediaIndexer.index(context)
        }
        assertEquals(1, result.removed)
        assertEquals(1, result.skipped)
        assertEquals(0, result.indexed)
        assertEquals(0, result.thumbFailed)
        // 消失条目的向量行与缩略图缓存一起清理。
        assertFalse(goneThumb.exists())
        assertEquals(listOf(kept.absolutePath), runBlocking { dao().all() }.map { it.sourceKey })
        // 扫描统计同步落库，供 index_status 展示。
        val source = runBlocking { dao().sourceById(MediaSourceScanner.TYPE_WORKSPACE) }
        assertEquals(1, source?.itemCount)
        assertTrue(source?.lastScanAt != null)
    }

    @Test
    fun clearEmptiesIndexButKeepsScanSources() {
        runBlocking {
            val dao = dao()
            dao.upsertAll(listOf(MediaIndexTestFixtures.entity("a", MediaIndexTestFixtures.axisVector(2, 0))))
            dao.upsertSource(workspaceSource())
            MediaIndexer.clear(context)
            assertEquals(emptyList<String>(), dao.all().map { it.sourceKey })
            // 只清索引，扫描源配置保留。
            assertEquals(listOf(MediaSourceScanner.TYPE_WORKSPACE), dao.sources().map { it.id })
        }
    }
}
