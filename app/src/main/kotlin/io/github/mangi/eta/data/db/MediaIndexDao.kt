package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
internal interface MediaIndexDao {
    @Upsert
    suspend fun upsertAll(items: List<MediaIndexEntity>)

    /** 检索候选集：只取当前模型与截断维度下的行，避免跨模型/跨维度误比。 */
    @Query("SELECT * FROM media_index WHERE model_id = :modelId AND vector_dim = :vectorDim")
    suspend fun listForModel(modelId: String, vectorDim: Int): List<MediaIndexEntity>

    @Query("SELECT * FROM media_index WHERE source_key = :sourceKey")
    suspend fun findBySourceKey(sourceKey: String): List<MediaIndexEntity>

    @Query("SELECT * FROM media_index")
    suspend fun all(): List<MediaIndexEntity>

    @Query("SELECT source_key FROM media_index WHERE model_id = :modelId AND vector_dim = :vectorDim")
    suspend fun sourceKeysForModel(modelId: String, vectorDim: Int): List<String>

    @Query("SELECT COUNT(*) FROM media_index WHERE model_id = :modelId AND vector_dim = :vectorDim")
    suspend fun countForModel(modelId: String, vectorDim: Int): Int

    @Query("SELECT MAX(indexed_at) FROM media_index")
    suspend fun lastIndexedAt(): Long?

    @Query("DELETE FROM media_index WHERE source_key IN (:sourceKeys)")
    suspend fun deleteBySourceKeys(sourceKeys: List<String>)

    @Query("DELETE FROM media_index WHERE model_id = :modelId AND vector_dim = :vectorDim")
    suspend fun deleteForModel(modelId: String, vectorDim: Int)

    @Query("DELETE FROM media_index")
    suspend fun clearAll()

    /** 一次索引收尾：写入增量结果并清理已消失的项，保证原子性。 */
    @Transaction
    suspend fun commitIndex(
        upserts: List<MediaIndexEntity>,
        staleSourceKeys: List<String>,
    ) {
        if (upserts.isNotEmpty()) upsertAll(upserts)
        if (staleSourceKeys.isNotEmpty()) deleteBySourceKeys(staleSourceKeys)
    }

    @Query("SELECT * FROM media_source")
    suspend fun sources(): List<MediaSourceEntity>

    @Query("SELECT * FROM media_source WHERE id = :id")
    suspend fun sourceById(id: String): MediaSourceEntity?

    @Upsert
    suspend fun upsertSource(source: MediaSourceEntity)

    @Query("UPDATE media_source SET item_count = :itemCount, last_scan_at = :lastScanAt WHERE id = :id")
    suspend fun updateSourceScan(id: String, itemCount: Int, lastScanAt: Long)

    @Query("DELETE FROM media_source WHERE id = :id")
    suspend fun deleteSource(id: String)

    /**
     * 缩略图迁移用：只取四列，避免把向量 BLOB 全表读进内存。
     * 注意这里返回**所有模型/维度**的行——缩略图文件名与模型无关，迁移必须一次覆盖干净。
     */
    @Query("SELECT source_key, model_id, vector_dim, thumb_path FROM media_index WHERE thumb_path IS NOT NULL")
    suspend fun thumbRows(): List<MediaThumbRef>

    @Query(
        "UPDATE media_index SET thumb_path = :thumbPath " +
            "WHERE source_key = :sourceKey AND model_id = :modelId AND vector_dim = :vectorDim",
    )
    suspend fun updateThumbPath(
        sourceKey: String,
        modelId: String,
        vectorDim: Int,
        thumbPath: String?,
    )

    /** LRU 淘汰后把同名缩略图的索引行标记为空串哨兵（渲染端走原图回退，索引端不再重建）。 */
    @Query("UPDATE media_index SET thumb_path = '' WHERE thumb_path = :name")
    suspend fun markThumbEvicted(name: String)
}
