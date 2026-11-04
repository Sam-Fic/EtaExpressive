package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.media.index.Float16VectorCodec
import io.github.mangi.eta.agent.media.index.MediaIndexTestFixtures
import io.github.mangi.eta.agent.media.index.MediaSourceScanner
import io.github.mangi.eta.agent.media.index.MediaThumbnailStore
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MediaIndexDao
import io.github.mangi.eta.data.db.MediaIndexEntity
import io.github.mangi.eta.data.db.MediaKind
import io.github.mangi.eta.data.db.MediaSourceEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 备份里的媒体语义索引（embedding 向量）导出/导入单测。
 *
 * 覆盖：导出→导入往返（含 Float16 向量与缩略图路径）、includeEmbeddings 开关、
 * records.jsonl 与清单的一致性校验、重装后按 contentHash / (sizeBytes, modifiedAt) 重关联、
 * 导入失败时 previous-media-index.jsonl 整表精确回滚（事务后失败的内联回滚 + 启动恢复入口）。
 *
 * 归档构造与导出导入均走公开入口（EtaBackupRepository.export / import / inspect /
 * recoverInterruptedImport）；索引行用 MediaIndexTestFixtures 构造，避免真实推理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class EtaBackupMediaIndexTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val json = Json { encodeDefaults = true }

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        File(context.filesDir, "backup-restore").deleteRecursively()
        // 记忆存储与 .memory-locks 下的 .deleted 标记跨用例残留会让后续导出抛「助手已删除」；整棵目录一并清掉。
        File(context.filesDir, "memory").deleteRecursively()
        AgentExecutionService.endBackupMaintenance()
        Prefs.initLocal(context)
        val prefs = requireNotNull(Prefs.localAgentPreferences())
        check(prefs.edit().clear().commit())
        check(ConversationSubAgentPreferences(prefs).resetLegacyConfigurationOnce())
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        // 单例缓存（rootDir/stores）必须按本用例的 filesDir 重新绑定，否则会读到上个用例留下的
        // .deleted 标记，导出随即抛「助手已删除，记忆操作未执行」。
        AgentMemoryRepository.resetForTests(context)
        AssistantRepository.init(context)
        McpServerRepository.init(context)
        MediaIndexTestFixtures.resetModelStore()
        MediaIndexTestFixtures.installFakeModel(context)
        // 候选集（重关联扫描）与导出内容必须完全由本用例构造。
        MediaIndexTestFixtures.workspaceDir(context).deleteRecursively()
        // 缩略图目录已迁到持久区；旧的 cacheDir 目录一并清掉，避免跨用例残留。
        MediaThumbnailStore.directory(context).deleteRecursively()
        File(context.cacheDir, "media-thumbs").deleteRecursively()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "backup-restore").deleteRecursively()
        // 用例写入的 .deleted 标记同样要清掉，避免后续用例（同沙箱复用 filesDir）读到残留。
        File(context.filesDir, "memory").deleteRecursively()
        AgentExecutionService.endBackupMaintenance()
        MediaIndexTestFixtures.resetModelStore()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    private fun dao(): MediaIndexDao = EtaDatabase.get(context).mediaIndexDao()

    // ---------- 归档构造 ----------

    private fun mediaDocument(
        count: Int,
        modelId: String = MediaIndexTestFixtures.FAKE_MODEL_ID,
        vectorDim: Int = 768,
        includeLinuxEnvironment: Boolean = false,
    ) = EtaBackupDocument(
        exportedAt = 0L,
        mediaIndex = EtaBackupMediaIndex(modelId = modelId, vectorDim = vectorDim, count = count),
        includeLinuxEnvironment = includeLinuxEnvironment,
    )

    /** records.jsonl 单行：归档行不带切片（modelId/vectorDim 由清单统一给出）。 */
    private fun recordLine(
        sourceKey: String,
        vector: FloatArray,
        kind: String = MediaKind.IMAGE.wire,
        displayName: String = sourceKey.substringAfterLast('/'),
        mime: String = "image/png",
        sizeBytes: Long = 4_096L,
        modifiedAt: Long = 1_700_000_000_000L,
        contentHash: String = "",
        durationMs: Long? = null,
        width: Int? = null,
        height: Int? = null,
        frameTimeMs: Long? = null,
        thumbPath: String? = null,
        indexedAt: Long = 1_700_000_000_001L,
    ): String = json.encodeToString(
        EtaBackupMediaRecord.serializer(),
        EtaBackupMediaRecord(
            sourceKey = sourceKey,
            kind = kind,
            displayName = displayName,
            mime = mime,
            sizeBytes = sizeBytes,
            modifiedAt = modifiedAt,
            contentHash = contentHash,
            durationMs = durationMs,
            width = width,
            height = height,
            frameTimeMs = frameTimeMs,
            thumbPath = thumbPath,
            indexedAt = indexedAt,
            vector = Base64.encodeToString(Float16VectorCodec.encode(vector), Base64.NO_WRAP),
        ),
    )

    /** 回滚快照行：自带 modelId/vectorDim，供整表（含其它切片）精确还原。 */
    private fun snapshotLine(row: MediaIndexEntity): String = json.encodeToString(
        EtaBackupMediaRecord.serializer(),
        EtaBackupMediaRecord(
            sourceKey = row.sourceKey,
            modelId = row.modelId,
            vectorDim = row.vectorDim,
            kind = row.kind,
            displayName = row.displayName,
            mime = row.mime,
            sizeBytes = row.sizeBytes,
            modifiedAt = row.modifiedAt,
            contentHash = row.contentHash,
            durationMs = row.durationMs,
            width = row.width,
            height = row.height,
            frameTimeMs = row.frameTimeMs,
            thumbPath = row.thumbPath,
            indexedAt = row.indexedAt,
            vector = Base64.encodeToString(Float16VectorCodec.encode(Float16VectorCodec.decode(row.vector)), Base64.NO_WRAP),
        ),
    )

    private fun archive(
        document: EtaBackupDocument,
        records: List<String>? = null,
        extra: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val writer = BackupZipWriter(zip)
            writer.text(EtaBackupDocument.MANIFEST_NAME, json.encodeToString(EtaBackupDocument.serializer(), document))
            records?.let { lines ->
                writer.stream(EtaBackupMediaIndex.ENTRY) { stream ->
                    val buffered = stream.bufferedWriter(Charsets.UTF_8)
                    lines.forEach { line -> buffered.append(line); buffered.newLine() }
                    buffered.flush()
                }
            }
            extra.forEach { (name, bytes) -> writer.stream(name) { it.write(bytes) } }
            zip.finish()
        }
        return output.toByteArray()
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val entries = linkedMapOf<String, ByteArray>()
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
            entries
        }

    private fun manifestOf(entries: Map<String, ByteArray>): EtaBackupDocument =
        json.decodeFromString(
            EtaBackupDocument.serializer(),
            entries.getValue(EtaBackupDocument.MANIFEST_NAME).toString(Charsets.UTF_8),
        )

    private fun recordsOf(entries: Map<String, ByteArray>): List<EtaBackupMediaRecord> =
        entries.getValue(EtaBackupMediaIndex.ENTRY).toString(Charsets.UTF_8)
            .lineSequence().filter { it.isNotBlank() }
            .map { json.decodeFromString(EtaBackupMediaRecord.serializer(), it) }
            .toList()

    // ---------- 断言与杂项 ----------

    private val rowOrder = compareBy<MediaIndexEntity>({ it.sourceKey }, { it.modelId }, { it.vectorDim })

    private fun sortedRows(rows: List<MediaIndexEntity>): List<MediaIndexEntity> = rows.sortedWith(rowOrder)

    private fun assertRowEquals(expected: MediaIndexEntity, actual: MediaIndexEntity) {
        assertEquals(expected.sourceKey, actual.sourceKey)
        assertEquals(expected.modelId, actual.modelId)
        assertEquals(expected.vectorDim, actual.vectorDim)
        assertEquals(expected.kind, actual.kind)
        assertEquals(expected.displayName, actual.displayName)
        assertEquals(expected.mime, actual.mime)
        assertEquals(expected.sizeBytes, actual.sizeBytes)
        assertEquals(expected.modifiedAt, actual.modifiedAt)
        assertEquals(expected.contentHash, actual.contentHash)
        assertEquals(expected.durationMs, actual.durationMs)
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        assertEquals(expected.frameTimeMs, actual.frameTimeMs)
        assertEquals(expected.thumbPath, actual.thumbPath)
        assertEquals(expected.indexedAt, actual.indexedAt)
        // ByteArray 是引用相等，必须逐字节比。
        assertArrayEquals(expected.vector, actual.vector)
    }

    private fun assertRowsEqual(expected: List<MediaIndexEntity>, actual: List<MediaIndexEntity>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (left, right) -> assertRowEquals(left, right) }
    }

    private fun halfExactVector(dim: Int = 768): FloatArray =
        // 0.125 的整数倍在半精度下精确，Float16 往返后必须逐值相等。
        FloatArray(dim) { index -> ((index % 21) - 10) * 0.125f }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun enableWorkspaceSource() {
        dao().upsertSource(
            MediaSourceEntity(
                id = MediaSourceScanner.TYPE_WORKSPACE,
                type = MediaSourceScanner.TYPE_WORKSPACE,
                uri = "",
                label = "终端工作区",
                enabled = true,
            ),
        )
    }

    // ---------- 1. 往返 ----------

    @Test
    fun exportThenImportRoundTripsEveryFieldAndHalfPrecisionVectors() = runBlocking {
        val thumb = File(MediaThumbnailStore.directory(context), "thumb-backup.jpg")
            .apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val fullVector = halfExactVector()
        val full = MediaIndexTestFixtures.entity(
            sourceKey = "content://media/external/images/media/42",
            vector = fullVector,
            kind = MediaKind.IMAGE,
            mime = "image/png",
            sizeBytes = 4_096L,
            modifiedAt = 1_700_000_000_000L,
            contentHash = "a".repeat(64),
            indexedAt = 1_700_000_000_002L,
        ).copy(durationMs = 1_234L, width = 640, height = 480, frameTimeMs = 77L)
        val video = MediaIndexTestFixtures.entity(
            sourceKey = "content://media/external/video/media/7",
            vector = MediaIndexTestFixtures.axisVector(768, 5),
            kind = MediaKind.VIDEO,
            mime = "video/mp4",
            sizeBytes = 8_192L,
            modifiedAt = 1_700_000_000_500L,
            contentHash = "b".repeat(64),
            // 新格式存文件名：导入后按当前安装目录重新拼接。
            thumbPath = thumb.name,
            indexedAt = 1_700_000_000_501L,
        )
        dao().upsertAll(listOf(full, video))

        val output = ByteArrayOutputStream()
        val summary = EtaBackupRepository.export(context, output, EtaBackupExportOptions(includeEmbeddings = true))
        assertEquals(2, summary.mediaIndexCount)
        assertEquals(MediaIndexTestFixtures.FAKE_MODEL_ID, summary.mediaIndexModelId)
        assertEquals(768, summary.mediaIndexDim)

        val bytes = output.toByteArray()
        val entries = readZip(bytes)
        assertTrue(entries.containsKey(EtaBackupMediaIndex.ENTRY))
        assertTrue("缩略图应随备份导出", entries.containsKey(EtaBackupMediaIndex.THUMBS_PREFIX + thumb.name))
        val meta = requireNotNull(manifestOf(entries).mediaIndex)
        assertEquals(MediaIndexTestFixtures.FAKE_MODEL_ID, meta.modelId)
        assertEquals(768, meta.vectorDim)
        assertEquals(2, meta.count)
        assertEquals(EtaBackupMediaIndex.ENTRY, meta.payloadEntry)

        // 归档行逐字段与向量编码一致，且归档行不带切片（modelId/vectorDim 归清单）。
        val records = recordsOf(entries).associateBy { it.sourceKey }
        assertEquals(setOf(full.sourceKey, video.sourceKey), records.keys)
        listOf(full, video).forEach { row ->
            val record = requireNotNull(records[row.sourceKey])
            assertNull(record.modelId)
            assertNull(record.vectorDim)
            assertEquals(row.kind, record.kind)
            assertEquals(row.displayName, record.displayName)
            assertEquals(row.mime, record.mime)
            assertEquals(row.sizeBytes, record.sizeBytes)
            assertEquals(row.modifiedAt, record.modifiedAt)
            assertEquals(row.contentHash, record.contentHash)
            assertEquals(row.durationMs, record.durationMs)
            assertEquals(row.width, record.width)
            assertEquals(row.height, record.height)
            assertEquals(row.frameTimeMs, record.frameTimeMs)
            assertEquals(row.thumbPath, record.thumbPath)
            assertEquals(row.indexedAt, record.indexedAt)
            assertArrayEquals(row.vector, Base64.decode(record.vector, Base64.NO_WRAP))
        }

        val preview = EtaBackupRepository.inspect(ByteArrayInputStream(bytes))
        assertEquals(2, preview.mediaIndexCount)
        assertEquals(MediaIndexTestFixtures.FAKE_MODEL_ID, preview.mediaIndexModelId)
        assertEquals(768, preview.mediaIndexDim)

        dao().clearAll()
        val imported = EtaBackupRepository.import(context, ByteArrayInputStream(bytes))
        assertEquals(2, imported.mediaIndexCount)
        // 向量是半精度存储：往返后 Float16 编码必须逐字节一致，解码值也逐维相等。
        assertRowsEqual(sortedRows(listOf(full, video)), sortedRows(dao().all()))
        val restoredFull = dao().findBySourceKey(full.sourceKey).single()
        assertTrue(Float16VectorCodec.decode(restoredFull.vector).contentEquals(fullVector))
    }

    @Test
    fun importNormalizesLegacyAbsoluteThumbPathsAndDropsThumbsMissingFromArchive() = runBlocking {
        val legacyName = "legacy-thumb.jpg"
        val bytes = archive(
            mediaDocument(count = 2),
            listOf(
                // 旧备份存绝对路径（Android cacheDir 形态）：导入时必须归一成文件名。
                recordLine(
                    "/a.png", MediaIndexTestFixtures.axisVector(768, 1),
                    thumbPath = "/data/user/0/io.github.mangi.eta/cache/media-thumbs/$legacyName",
                ),
                // Windows 反斜杠路径，且归档里没有这个文件：置空交给增量索引重建。
                recordLine(
                    "/b.png", MediaIndexTestFixtures.axisVector(768, 2),
                    thumbPath = "C:\\Users\\me\\AppData\\Local\\Temp\\windows-thumb.jpg",
                ),
            ),
            extra = listOf(EtaBackupMediaIndex.THUMBS_PREFIX + legacyName to byteArrayOf(1, 2, 3)),
        )
        EtaBackupRepository.import(context, ByteArrayInputStream(bytes))

        val rows = dao().all().associateBy { it.sourceKey }
        assertEquals(legacyName, requireNotNull(rows["/a.png"]).thumbPath)
        assertNull("归档未携带的缩略图应置空，交给重建", requireNotNull(rows["/b.png"]).thumbPath)
    }

    @Test
    fun exportWithoutEmbeddingsOmitsMediaIndexEntryAndManifestMetadata() = runBlocking {
        dao().upsertAll(listOf(MediaIndexTestFixtures.entity("keep", MediaIndexTestFixtures.axisVector(768, 0))))
        val output = ByteArrayOutputStream()
        val summary = EtaBackupRepository.export(context, output, EtaBackupExportOptions(includeEmbeddings = false))
        assertEquals(0, summary.mediaIndexCount)
        assertNull(summary.mediaIndexModelId)
        assertEquals(0, summary.mediaIndexDim)

        val bytes = output.toByteArray()
        val entries = readZip(bytes)
        assertTrue("includeEmbeddings=false 时不应有 media-index 条目", entries.keys.none { it.startsWith("media-index/") })
        assertNull(manifestOf(entries).mediaIndex)
        assertEquals(0, EtaBackupRepository.inspect(ByteArrayInputStream(bytes)).mediaIndexCount)
    }

    // ---------- 5. 默认值与体积预估 ----------

    @Test
    fun includeEmbeddingsDefaultsToTrueAndEstimateIsRowsTimesDimTimesTwo() = runBlocking {
        assertTrue(EtaBackupExportOptions().includeEmbeddings)
        dao().upsertAll(
            listOf(
                MediaIndexTestFixtures.entity("k1", MediaIndexTestFixtures.axisVector(768, 0)),
                MediaIndexTestFixtures.entity("k2", MediaIndexTestFixtures.axisVector(768, 1)),
                MediaIndexTestFixtures.entity("k3", MediaIndexTestFixtures.axisVector(768, 2)),
                // 其它切片不计入当前切片预估。
                MediaIndexTestFixtures.entity(
                    "k4", MediaIndexTestFixtures.axisVector(512, 0),
                    modelId = "other-model", vectorDim = 512,
                ),
            ),
        )
        assertEquals(3L * 768L * 2L, EtaBackupRepository.mediaIndexEstimateBytes(context))
    }

    // ---------- 2. 一致性校验 ----------

    @Test
    fun recordsLineCountMismatchWithManifestFailsBeforeAnyWrite() = runBlocking {
        val existing = MediaIndexTestFixtures.entity("existing", MediaIndexTestFixtures.axisVector(768, 0))
        dao().upsertAll(listOf(existing))
        val bytes = archive(
            mediaDocument(count = 3),
            listOf(
                recordLine("/a.png", MediaIndexTestFixtures.axisVector(768, 1)),
                recordLine("/b.png", MediaIndexTestFixtures.axisVector(768, 2)),
            ),
        )
        val failure = runCatching {
            EtaBackupRepository.import(context, ByteArrayInputStream(bytes))
        }.exceptionOrNull()
        assertTrue("应因行数与清单不一致拒绝，实际：$failure", failure != null && "行数与清单不一致" in failure.message.orEmpty())
        val preview = runCatching { EtaBackupRepository.inspect(ByteArrayInputStream(bytes)) }.exceptionOrNull()
        assertTrue("预览也应拒绝，实际：$preview", preview != null && "行数与清单不一致" in preview.message.orEmpty())
        assertRowsEqual(listOf(existing), sortedRows(dao().all()))
    }

    @Test
    fun vectorDimMismatchWithManifestFailsValidation() = runBlocking {
        val bytes = archive(
            mediaDocument(count = 1),
            // 4 维向量与清单声明的 768 不一致。
            listOf(recordLine("/a.png", MediaIndexTestFixtures.axisVector(4, 0))),
        )
        val failure = runCatching {
            EtaBackupRepository.import(context, ByteArrayInputStream(bytes))
        }.exceptionOrNull()
        assertTrue("应因向量维度与清单不一致拒绝，实际：$failure", failure != null && "向量维度与清单不一致" in failure.message.orEmpty())
        assertEquals(emptyList<String>(), dao().all().map { it.sourceKey })
    }

    @Test
    fun duplicateSourceKeyFailsValidation() = runBlocking {
        val bytes = archive(
            mediaDocument(count = 2),
            listOf(
                recordLine("/dup.png", MediaIndexTestFixtures.axisVector(768, 1)),
                recordLine("/dup.png", MediaIndexTestFixtures.axisVector(768, 2)),
            ),
        )
        val failure = runCatching {
            EtaBackupRepository.import(context, ByteArrayInputStream(bytes))
        }.exceptionOrNull()
        assertTrue("应因 sourceKey 重复拒绝，实际：$failure", failure != null && "sourceKey 重复" in failure.message.orEmpty())
        assertEquals(emptyList<String>(), dao().all().map { it.sourceKey })
    }

    // ---------- 3. 重关联 ----------

    @Test
    fun contentHashRelinkMovesVectorToNewSourceKeyWhileExistingFileStays() = runBlocking {
        // 大小不同：避免同内容文件互相干扰哈希候选。
        val moved = MediaIndexTestFixtures.writeWorkspaceFile(context, "moved.png", bytes = 8_192)
        val kept = MediaIndexTestFixtures.writeWorkspaceFile(context, "kept.png", bytes = 9_000)
        enableWorkspaceSource()
        val lines = listOf(
            // 重装后旧 sourceKey 消失，仅 contentHash 相同（mtime 不同）：向量写回新 key。
            recordLine(
                "/data/old/moved.png", MediaIndexTestFixtures.axisVector(768, 3),
                contentHash = sha256Of(moved), sizeBytes = moved.length(), modifiedAt = 5L,
            ),
            // sourceKey 仍在且文件存在：保持原样。
            recordLine(
                kept.absolutePath, MediaIndexTestFixtures.axisVector(768, 4),
                contentHash = sha256Of(kept), sizeBytes = kept.length(), modifiedAt = kept.lastModified(),
            ),
        )
        EtaBackupRepository.import(context, ByteArrayInputStream(archive(mediaDocument(count = 2), lines)))

        val rows = dao().all().associateBy { it.sourceKey }
        assertNull("旧 key 不应残留", rows["/data/old/moved.png"])
        assertArrayEquals(
            Float16VectorCodec.encode(MediaIndexTestFixtures.axisVector(768, 3)),
            requireNotNull(rows[moved.absolutePath]).vector,
        )
        assertArrayEquals(
            Float16VectorCodec.encode(MediaIndexTestFixtures.axisVector(768, 4)),
            requireNotNull(rows[kept.absolutePath]).vector,
        )
    }

    @Test
    fun sizeAndModifiedAtFallbackRelinkWhenHashIsBlank() = runBlocking {
        val clip = MediaIndexTestFixtures.writeWorkspaceFile(context, "clip.mp4", bytes = 12_288, modifiedAt = 1_700_000_200_000L)
        enableWorkspaceSource()
        val lines = listOf(
            // contentHash 为空：退化为 (sizeBytes, modifiedAt) 兜底匹配。
            recordLine(
                "/data/old/clip.mp4", MediaIndexTestFixtures.axisVector(768, 6),
                kind = MediaKind.VIDEO.wire, mime = "video/mp4",
                sizeBytes = clip.length(), modifiedAt = clip.lastModified(),
            ),
        )
        EtaBackupRepository.import(context, ByteArrayInputStream(archive(mediaDocument(count = 1), lines)))

        val row = dao().all().single()
        assertEquals(clip.absolutePath, row.sourceKey)
        assertArrayEquals(Float16VectorCodec.encode(MediaIndexTestFixtures.axisVector(768, 6)), row.vector)
    }

    @Test
    fun eachCandidateIsClaimedAtMostOnceAndUnmatchedRowsKeepOriginalKey() = runBlocking {
        val only = MediaIndexTestFixtures.writeWorkspaceFile(context, "only.png", bytes = 8_192)
        enableWorkspaceSource()
        val hash = sha256Of(only)
        val first = "/data/old/first.png"
        val second = "/data/old/second.png"
        val third = "/data/old/third.png"
        val lines = listOf(
            recordLine(first, MediaIndexTestFixtures.axisVector(768, 1), contentHash = hash, sizeBytes = only.length(), modifiedAt = 3L),
            // 与第一行指向同一文件：候选已被认领，只能保留原行待补齐。
            recordLine(second, MediaIndexTestFixtures.axisVector(768, 2), contentHash = hash, sizeBytes = only.length(), modifiedAt = 3L),
            // 哈希与 (size, mtime) 都配不上：同样保留原行。
            recordLine(third, MediaIndexTestFixtures.axisVector(768, 3), contentHash = "0".repeat(64), sizeBytes = 12_345L, modifiedAt = 1L),
        )
        EtaBackupRepository.import(context, ByteArrayInputStream(archive(mediaDocument(count = 3), lines)))

        val rows = dao().all().associateBy { it.sourceKey }
        assertEquals(setOf(only.absolutePath, second, third), rows.keys)
        assertArrayEquals(
            Float16VectorCodec.encode(MediaIndexTestFixtures.axisVector(768, 1)),
            requireNotNull(rows[only.absolutePath]).vector,
        )
        assertArrayEquals(
            Float16VectorCodec.encode(MediaIndexTestFixtures.axisVector(768, 2)),
            requireNotNull(rows[second]).vector,
        )
        assertArrayEquals(
            Float16VectorCodec.encode(MediaIndexTestFixtures.axisVector(768, 3)),
            requireNotNull(rows[third]).vector,
        )
    }

    // ---------- 4. 回滚 ----------

    @Test
    fun failedImportAfterCommitRestoresWholeMediaIndexFromPreviousSnapshot() = runBlocking {
        val a = MediaIndexTestFixtures.entity("a-key", MediaIndexTestFixtures.axisVector(768, 0), contentHash = "a".repeat(64))
        val b = MediaIndexTestFixtures.entity("b-key", MediaIndexTestFixtures.axisVector(768, 1), contentHash = "b".repeat(64))
        val foreign = MediaIndexTestFixtures.entity(
            "c-key", MediaIndexTestFixtures.axisVector(512, 0),
            modelId = "other-model", vectorDim = 512,
        )
        dao().upsertAll(listOf(a, b, foreign))
        val before = sortedRows(dao().all())

        // 备份行覆盖 a-key（换向量）并新增 d-key：回滚必须把整表还原到导入前，
        // 否则要么 a-key 向量被改写，要么 d-key 残留，要么其它切片丢失。
        val lines = listOf(
            recordLine("a-key", MediaIndexTestFixtures.axisVector(768, 7), contentHash = "a".repeat(64)),
            recordLine("d-key", MediaIndexTestFixtures.axisVector(768, 8)),
        )
        // 事务提交后才还原 Linux 环境：用无效后端名制造「中途失败」，逼出
        // previous-media-index.jsonl 的整表精确回滚（此时 Room 事务已提交，无法代劳）。
        val bytes = archive(
            mediaDocument(count = 2, includeLinuxEnvironment = true),
            lines,
            extra = listOf("linux/environments/bogus/debian.tar" to "junk".toByteArray()),
        )
        val failure = runCatching {
            EtaBackupRepository.import(context, ByteArrayInputStream(bytes))
        }.exceptionOrNull()
        assertTrue("应因 Linux 后端无效失败，实际：$failure", failure != null && "Linux 后端无效" in failure.message.orEmpty())

        assertRowsEqual(before, sortedRows(dao().all()))
        assertFalse(File(context.filesDir, "backup-restore").exists())
        assertFalse(AgentExecutionService.backupMaintenance)
    }

    @Test
    fun startupRecoveryRestoresMediaIndexFromPreviousSnapshotExactly() = runBlocking {
        val a = MediaIndexTestFixtures.entity("a-key", MediaIndexTestFixtures.axisVector(768, 0))
        val foreign = MediaIndexTestFixtures.entity(
            "c-key", MediaIndexTestFixtures.axisVector(512, 0),
            modelId = "other-model", vectorDim = 512,
        )
        // 模拟「导入已写入媒体行、恢复日志尚未提交」的中断状态：new-key 是导入残留。
        dao().upsertAll(listOf(MediaIndexTestFixtures.entity("new-key", MediaIndexTestFixtures.axisVector(768, 9))))
        val operation = File(context.filesDir, "backup-restore").apply { mkdirs() }
        File(operation, "previous.json")
            .writeText(json.encodeToString(EtaBackupDocument.serializer(), EtaBackupDocument(exportedAt = 0L)))
        File(operation, "previous-media-index.jsonl")
            .writeText(listOf(snapshotLine(a), snapshotLine(foreign)).joinToString("\n") + "\n")
        File(operation, "journal.json").writeText("[]")

        EtaBackupRepository.recoverInterruptedImport(context)

        // 整表精确还原：new-key 被清掉，其它切片（other-model/512）也逐字段逐字节还原。
        assertRowsEqual(sortedRows(listOf(a, foreign)), sortedRows(dao().all()))
        assertFalse(operation.exists())
        assertFalse(AgentExecutionService.backupMaintenance)
    }
}
