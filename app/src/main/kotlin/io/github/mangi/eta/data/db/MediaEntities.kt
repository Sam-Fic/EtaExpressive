package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 媒体/文件类型：检索过滤与工具返回共用同一套取值。 */
internal enum class MediaKind(val wire: String) {
    IMAGE("image"),
    VIDEO("video"),
    AUDIO("audio"),
    DOCUMENT("document");

    companion object {
        fun from(wire: String?): MediaKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 已嵌入的媒体索引行。主键 `(source_key, model_id, vector_dim)`：
 * 换模型或换截断维度后新增行，旧向量保留直到重建清理，检索时按当前维度过滤。
 */
@Entity(
    tableName = "media_index",
    primaryKeys = ["source_key", "model_id", "vector_dim"],
    indices = [
        Index("kind"),
        Index("modified_at"),
    ],
)
internal data class MediaIndexEntity(
    /** 稳定标识：MediaStore 用 content URI，附件/工作区用绝对路径。 */
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "vector_dim") val vectorDim: Int,
    val kind: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val mime: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    /** 全文件 SHA-256：备份导出后重装重关联的依据。 */
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** 视频「时刻检索」的帧时间戳；P1 聚合向量下恒为 null。 */
    @ColumnInfo(name = "frame_time_ms") val frameTimeMs: Long? = null,
    @ColumnInfo(name = "thumb_path") val thumbPath: String? = null,
    /** L2 归一化后的 Float16 向量 BLOB。 */
    val vector: ByteArray,
    @ColumnInfo(name = "indexed_at") val indexedAt: Long,
)

/**
 * 用户确认过的扫描源。只有出现在本表且 enabled 的源才会被索引，
 * 首次启用前必须经过扫描范围确认弹窗（见「媒体搜索」设置页）。
 */
@Entity(tableName = "media_source")
internal data class MediaSourceEntity(
    @PrimaryKey val id: String,
    /** mediastore / attachment-cache / workspace / saf-tree / shared-folder */
    val type: String,
    /** MediaStore 集合 URI、SAF tree URI 或目录绝对路径。 */
    val uri: String,
    val label: String,
    val enabled: Boolean = false,
    @ColumnInfo(name = "last_scan_at") val lastScanAt: Long? = null,
    @ColumnInfo(name = "item_count") val itemCount: Int = 0,
)

/**
 * 缩略图寻址迁移/清理用的轻量投影：只取定位与缩略图列，避免把向量 BLOB 读进内存。
 * 注意 `thumb_path` 存的是**文件名**（见 MediaThumbnailStore），旧版绝对路径由迁移归一。
 */
internal data class MediaThumbRef(
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "vector_dim") val vectorDim: Int,
    @ColumnInfo(name = "thumb_path") val thumbPath: String?,
)
