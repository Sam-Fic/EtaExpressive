package io.github.mangi.eta.agent.media.index

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException

/**
 * 媒体文件操作层：语义搜索结果的「批量移动 / 批量复制」。
 *
 * GUI（[io.github.mangi.eta.ui.screens.search.MediaSearchScreen]）与 AI 工具
 * `semantic_search_media` 共用本层，文件操作不内嵌进工具 schema 逻辑。
 *
 * 三种来源分支：
 * - MediaStore content uri（`content://media/...`）：移动重写 [MediaStore.MediaColumns.RELATIVE_PATH]
 *   并切换 [MediaStore.MediaColumns.IS_PENDING]；复制用输入流写入目标 collection 的新条目。
 *   旧式 `content://media/external/file/<id>` 退化到 DATA 绝对路径分支。
 * - SAF document uri（`content://.../document/...`）：同树内优先 renameDocument / moveDocument，
 *   不支持或跨树时用 createDocument + 流复制 + deleteDocument。
 * - 普通绝对路径：同卷 renameTo，失败回退「流复制 + 成功后删源」；复制直接流复制。
 *
 * **重名策略**：目标目录内同名时自动加 ` (1)` / ` (2)` 后缀，绝不覆盖已有文件。
 * MediaStore 分支在改 RELATIVE_PATH 前同样按目标目录内已存在的 DISPLAY_NAME 去重。
 *
 * **索引同步取舍**：move 成功后删除旧 source_key 的索引行与缩略图，但不原地改写 source_key。
 * 被移动到新的扫描范围内的条目由下一轮增量索引重建；不在扫描范围内的条目自然不再出现。
 * 这样避免同一条目同时存在路径形 / URI 形两个 source_key 导致检索重复。copy 不动索引，
 * 新副本交给下一轮增量扫描发现。
 */
internal object MediaFileOps {

    /** 批量操作类型。 */
    enum class Op(val wire: String) {
        MOVE("move"),
        COPY("copy"),
    }

    /** 文件操作错误码；GUI 与 AI 工具都按这些码区分失败原因。 */
    object ErrorCode {
        const val TARGET_REQUIRED = "TARGET_REQUIRED"
        const val TARGET_NOT_DIRECTORY = "TARGET_NOT_DIRECTORY"
        const val SOURCE_MISSING = "SOURCE_MISSING"
        const val PERMISSION_DENIED = "PERMISSION_DENIED"
        const val UNSUPPORTED_SOURCE = "UNSUPPORTED_SOURCE"
        const val CROSS_VOLUME_FAILED = "CROSS_VOLUME_FAILED"
        const val IO_ERROR = "IO_ERROR"
        const val NAME_CONFLICT = "NAME_CONFLICT"
    }

    /**
     * 一条待操作媒体：从检索结果构造（sourceKey + uri + path）。
     * [sourceKey] 是索引主键，move 成功后用它清理旧索引行。
     */
    data class MediaTarget(
        val sourceKey: String,
        val uri: String? = null,
        val path: String? = null,
    ) {
        /** 是否为 MediaStore 条目（含旧式 file 表 uri）。 */
        val isMediaStore: Boolean get() = uri?.startsWith("content://media/") == true

        companion object {
            /**
             * 构造目标：显式 uri / path 优先；缺省时按 sourceKey 的形态自解释
             * （`content://` 视为 uri，`/` 开头视为绝对路径）。三个字段都为空返回 null。
             */
            fun parse(sourceKey: String, uri: String? = null, path: String? = null): MediaTarget? {
                val rawKey = sourceKey.trim()
                val normalizedUri = uri?.trim()?.takeIf { it.isNotEmpty() }
                    ?: rawKey.takeIf { it.startsWith("content://") }
                val normalizedPath = path?.trim()?.takeIf { it.isNotEmpty() }
                    ?: rawKey.takeIf { it.startsWith("/") }
                val key = rawKey.ifEmpty { normalizedUri ?: normalizedPath ?: return null }
                return MediaTarget(sourceKey = key, uri = normalizedUri, path = normalizedPath)
            }

            /** 从检索命中构造；内容 URI 与路径都可能缺省。 */
            fun from(hit: MediaSearchService.Hit): MediaTarget =
                MediaTarget(sourceKey = hit.sourceKey, uri = hit.contentUri, path = hit.path)
        }
    }

    /** 目标文件夹：SAF tree uri 或绝对路径二选一。 */
    data class MediaDir(val treeUri: String? = null, val path: String? = null) {
        companion object {
            /** 解析用户/工具传入的目录：`content://` 视为 SAF tree，`/` 开头视为绝对路径，其余非法。 */
            fun parse(raw: String): MediaDir? {
                val value = raw.trim()
                return when {
                    value.isEmpty() -> null
                    value.startsWith("content://") -> MediaDir(treeUri = value)
                    value.startsWith("/") -> MediaDir(path = value)
                    else -> null
                }
            }
        }
    }

    /** 单次操作结果：成功带新 uri / 新路径（SAF 无文件系统路径时为 null），失败带错误码。 */
    sealed class OpResult {
        data class Ok(val newUri: String?, val newPath: String?) : OpResult()
        data class Fail(val code: String, val message: String) : OpResult()
    }

    /** 批量成功项。 */
    data class ItemSuccess(val sourceKey: String, val newUri: String?, val newPath: String?)

    /** 批量失败项：单项失败只记录，不中断其他条目。 */
    data class ItemFailure(val sourceKey: String, val code: String, val message: String)

    /** 批量汇总。 */
    data class BatchResult(
        val operation: Op,
        val succeeded: List<ItemSuccess>,
        val failed: List<ItemFailure>,
    ) {
        val total: Int get() = succeeded.size + failed.size
    }

    /** batch_act 的来源解析模式：显式 source_keys 优先，省略时按 query 现场检索。 */
    internal enum class BatchSourceMode { SOURCE_KEYS, QUERY }

    internal data class BatchSourceSpec(
        val mode: BatchSourceMode,
        val keys: List<String>,
        val query: String,
    )

    /**
     * batch_act 参数校验（纯函数）：source_keys 去空白去重后非空即用 keys；
     * 否则要求 query 非空；两者都缺省返回 null（调用方报 INVALID_ARGUMENT）。
     */
    internal fun parseBatchSourceSpec(sourceKeys: List<String>, query: String): BatchSourceSpec? {
        val keys = sourceKeys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (keys.isNotEmpty()) return BatchSourceSpec(BatchSourceMode.SOURCE_KEYS, keys, query.trim())
        val text = query.trim()
        if (text.isEmpty()) return null
        return BatchSourceSpec(BatchSourceMode.QUERY, emptyList(), text)
    }

    /**
     * 重名去重（纯逻辑）：[name] 已在 [taken] 中时依次尝试 `stem (1).ext`、`stem (2).ext` …
     * 以最后一个 `.` 拆主名与扩展名；隐藏文件（`.nomedia`）整体视为主名，后缀追加在末尾。
     * 不存在同名时原样返回，调用方据此保证「不覆盖已有文件」。
     */
    internal fun uniqueName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0
        val stem = if (hasExtension) name.substring(0, dot) else name
        val extension = if (hasExtension) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$stem ($index)$extension"
            if (candidate !in taken) return candidate
            index++
        }
    }

    /**
     * 移动单个媒体到 [targetDir]：成功后同步清理旧索引行与缩略图（见文件头注释的取舍）。
     * 失败不触碰索引。
     */
    suspend fun move(context: Context, source: MediaTarget, targetDir: MediaDir): OpResult {
        val result = runCatching { moveInternal(context, source, targetDir) }.getOrElse(::mapFailure)
        if (result is OpResult.Ok) purgeIndex(context, source.sourceKey)
        return result
    }

    /** 复制单个媒体到 [targetDir]：索引不动，新副本交给下一轮增量扫描。 */
    fun copy(context: Context, source: MediaTarget, targetDir: MediaDir): OpResult =
        runCatching { copyInternal(context, source, targetDir) }.getOrElse(::mapFailure)

    /**
     * 批量入口：逐项执行 [operation]，单项失败不中断（异常也折算成失败项），
     * 返回成功 / 失败清单供 GUI 汇总与 AI 结构化输出。
     */
    suspend fun runBatch(
        context: Context,
        sources: List<MediaTarget>,
        targetDir: MediaDir,
        operation: Op,
    ): BatchResult = runBatchOf(sources, operation) { source ->
        when (operation) {
            Op.MOVE -> move(context, source, targetDir)
            Op.COPY -> copy(context, source, targetDir)
        }
    }

    /**
     * 批量汇总核心（可注入执行函数，便于单测「失败不中断」）：
     * 逐项回调 [execute]，[OpResult.Ok] 进 succeeded、[OpResult.Fail] 与异常进 failed；
     * 仅向上传播协程取消。
     */
    internal suspend fun runBatchOf(
        sources: List<MediaTarget>,
        operation: Op,
        execute: suspend (MediaTarget) -> OpResult,
    ): BatchResult {
        val succeeded = mutableListOf<ItemSuccess>()
        val failed = mutableListOf<ItemFailure>()
        sources.forEach { source ->
            val result = try {
                execute(source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                OpResult.Fail(ErrorCode.IO_ERROR, failure.message ?: "操作失败")
            }
            when (result) {
                is OpResult.Ok -> succeeded += ItemSuccess(source.sourceKey, result.newUri, result.newPath)
                is OpResult.Fail -> failed += ItemFailure(source.sourceKey, result.code, result.message)
            }
        }
        return BatchResult(operation, succeeded, failed)
    }

    // ---------------------------------------------------------------- 分支调度

    private fun moveInternal(context: Context, source: MediaTarget, targetDir: MediaDir): OpResult {
        if (targetDir.treeUri == null && targetDir.path == null) {
            return OpResult.Fail(ErrorCode.TARGET_REQUIRED, "未选择目标文件夹")
        }
        val uri = source.uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        return when {
            source.isMediaStore && uri != null -> moveMediaStore(context, uri, targetDir)
            uri != null && isSafDocument(context, uri) -> moveSaf(context, uri, targetDir)
            source.path != null -> moveFile(context, File(source.path), targetDir)
            uri != null -> OpResult.Fail(ErrorCode.UNSUPPORTED_SOURCE, "不支持的源 URI：${source.uri}")
            else -> OpResult.Fail(ErrorCode.SOURCE_MISSING, "源文件不存在或不可访问")
        }
    }

    private fun copyInternal(context: Context, source: MediaTarget, targetDir: MediaDir): OpResult {
        if (targetDir.treeUri == null && targetDir.path == null) {
            return OpResult.Fail(ErrorCode.TARGET_REQUIRED, "未选择目标文件夹")
        }
        val uri = source.uri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        return when {
            source.isMediaStore && uri != null -> copyMediaStore(context, uri, targetDir)
            uri != null && isSafDocument(context, uri) -> copySaf(context, uri, targetDir)
            source.path != null -> copyFile(context, File(source.path), targetDir)
            uri != null -> OpResult.Fail(ErrorCode.UNSUPPORTED_SOURCE, "不支持的源 URI：${source.uri}")
            else -> OpResult.Fail(ErrorCode.SOURCE_MISSING, "源文件不存在或不可访问")
        }
    }

    private fun isSafDocument(context: Context, uri: Uri): Boolean =
        runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)

    /** 异常统一折算成错误码：权限类走 PERMISSION_DENIED，其余按 IO 失败。 */
    private fun mapFailure(failure: Throwable): OpResult = when (failure) {
        is SecurityException -> OpResult.Fail(ErrorCode.PERMISSION_DENIED, failure.message ?: "没有访问权限")
        is IOException -> OpResult.Fail(ErrorCode.IO_ERROR, failure.message ?: "读写失败")
        else -> OpResult.Fail(ErrorCode.IO_ERROR, failure.message ?: "操作失败")
    }

    // ---------------------------------------------------------------- MediaStore

    /** 旧式 file 表 uri（`content://media/external/file/<id>`）需要按 DATA 绝对路径处理。 */
    private fun isLegacyFileUri(uri: Uri): Boolean = uri.encodedPath?.contains("/file/") == true

    private fun mediaStoreId(uri: Uri): Long? = uri.lastPathSegment?.toLongOrNull()

    /** 源 uri 的集合基地址（去掉末尾 id），用于按同 collection 查询重名。 */
    private fun collectionUriOf(uri: Uri): Uri =
        uri.buildUpon().path(uri.pathSegments.dropLast(1).joinToString("/")).build()

    private data class MediaStoreMeta(val displayName: String, val mime: String)

    private fun mediaStoreMeta(context: Context, uri: Uri): MediaStoreMeta? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(0)?.takeIf { it.isNotBlank() }
                ?: fallbackName(uri)
            MediaStoreMeta(name, cursor.getString(1).orEmpty())
        }
    }.getOrNull()

    private fun dataPathOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()

    /**
     * 目标目录内已占用的 DISPLAY_NAME。[excludeId] 用于移动时排除源条目自身，
     * 避免把自己当成重名对象而加多余后缀。
     */
    private fun mediaStoreNames(
        context: Context,
        collection: Uri,
        relativePath: String,
        excludeId: Long?,
    ): Set<String> = runCatching {
        val selection = if (excludeId != null) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns._ID} != ?"
        } else {
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        }
        val args = if (excludeId != null) arrayOf(relativePath, excludeId.toString()) else arrayOf(relativePath)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            selection,
            args,
            null,
        )?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) cursor.getString(0)?.let(::add)
            }
        }
    }.getOrNull() ?: emptySet()

    /**
     * 绝对路径 → MediaStore RELATIVE_PATH（以 `/` 结尾）。
     * 只认主共享存储根；其余卷（SD 卡、应用私有目录）返回 null，由调用方回退到流复制。
     */
    private fun relativePathFor(dir: File): String? {
        val root = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()
            ?.trimEnd('/') ?: return null
        val path = dir.absolutePath.trimEnd('/')
        if (path == root) return ""
        if (!path.startsWith("$root/")) return null
        return path.removePrefix("$root/").let { if (it.isEmpty()) "" else "$it/" }
    }

    /** 按 MIME 选目标 collection；`external` 卷统一映射到 primary（插入语义一致）。 */
    private fun insertCollection(sourceUri: Uri, mime: String): Uri {
        val volume = sourceUri.pathSegments.getOrNull(1)
            ?.takeIf { it.isNotBlank() && it != "external" }
            ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
        return when {
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(volume)
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(volume)
            mime.startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(volume)
            else -> MediaStore.Files.getContentUri(volume)
        }
    }

    /**
     * MediaStore 移动：
     * 1. 旧式 file 表 uri 退化到 DATA 绝对路径（[moveFile]）；
     * 2. 目标是 SAF tree：createDocument 复制 + 删除源条目（跨 provider 无法重写路径）；
     * 3. 目标是主存储绝对路径：重写 RELATIVE_PATH 并切换 IS_PENDING（先 1 后 0），id 保持不变。
     */
    private fun moveMediaStore(context: Context, uri: Uri, targetDir: MediaDir): OpResult {
        if (isLegacyFileUri(uri)) {
            val path = dataPathOf(context, uri)
                ?: return OpResult.Fail(ErrorCode.UNSUPPORTED_SOURCE, "旧式媒体条目缺少文件路径")
            return moveFile(context, File(path), targetDir)
        }
        val meta = mediaStoreMeta(context, uri)
            ?: return OpResult.Fail(ErrorCode.SOURCE_MISSING, "媒体条目不存在或不可访问")
        if (targetDir.treeUri != null) {
            val copied = copyStreamToSaf(
                context,
                Uri.parse(targetDir.treeUri),
                meta.mime,
                meta.displayName,
            ) { openInput(context, uri) }
            if (copied is OpResult.Fail) return copied
            val copiedOk = copied as OpResult.Ok
            return finishMove(
                copied = copiedOk,
                deleteSource = { context.contentResolver.delete(uri, null, null) > 0 },
                rollback = { rollbackSaf(context, copiedOk.newUri) },
            )
        }
        val dir = targetDir.path?.let(::File)
            ?: return OpResult.Fail(ErrorCode.TARGET_REQUIRED, "未选择目标文件夹")
        ensureDirectory(dir)?.let { return it }
        val relativePath = relativePathFor(dir)
            ?: return OpResult.Fail(
                ErrorCode.CROSS_VOLUME_FAILED,
                "目标目录不在主存储内，MediaStore 无法原地移动",
            )
        val collection = collectionUriOf(uri)
        val name = uniqueName(meta.displayName, mediaStoreNames(context, collection, relativePath, mediaStoreId(uri)))
        val resolver = context.contentResolver
        // IS_PENDING=1 → 落定 → 0：保证路径切换期间其他应用不会读到半状态条目。
        val pendingValues = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val updated = try {
            resolver.update(uri, pendingValues, null, null)
        } catch (security: SecurityException) {
            return OpResult.Fail(ErrorCode.PERMISSION_DENIED, security.message ?: "没有修改该媒体条目的权限")
        }
        if (updated <= 0) return OpResult.Fail(ErrorCode.IO_ERROR, "无法更新媒体条目路径")
        val committed = try {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (security: SecurityException) {
            runCatching {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            return OpResult.Fail(ErrorCode.PERMISSION_DENIED, security.message ?: "没有提交该媒体条目的权限")
        }
        if (committed <= 0) return OpResult.Fail(ErrorCode.IO_ERROR, "无法提交媒体条目移动")
        return OpResult.Ok(uri.toString(), dataPathOf(context, uri) ?: File(dir, name).absolutePath)
    }

    /**
     * MediaStore 复制：
     * - 目标 SAF tree → createDocument + 流复制；
     * - 目标主存储绝对路径 → 在目标 collection insert 新条目（IS_PENDING=1）→ openOutputStream
     *   写入 → 清理 IS_PENDING=0；任一步失败回滚删除新条目；
     * - 目标不在主存储 → 直接写入普通文件（MediaStore 无法登记外部卷以外的路径）。
     */
    private fun copyMediaStore(context: Context, uri: Uri, targetDir: MediaDir): OpResult {
        val meta = mediaStoreMeta(context, uri)
            ?: return OpResult.Fail(ErrorCode.SOURCE_MISSING, "媒体条目不存在或不可访问")
        if (targetDir.treeUri != null) {
            return copyStreamToSaf(
                context,
                Uri.parse(targetDir.treeUri),
                meta.mime,
                meta.displayName,
            ) { openInput(context, uri) }
        }
        val dir = targetDir.path?.let(::File)
            ?: return OpResult.Fail(ErrorCode.TARGET_REQUIRED, "未选择目标文件夹")
        ensureDirectory(dir)?.let { return it }
        val relativePath = relativePathFor(dir)
            ?: return copyStreamToDirectory(context, dir, meta.displayName) { openInput(context, uri) }

        val resolver = context.contentResolver
        val collection = insertCollection(uri, meta.mime)
        val name = uniqueName(meta.displayName, mediaStoreNames(context, collection, relativePath, excludeId = null))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, meta.mime.ifBlank { "application/octet-stream" })
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val newUri = try {
            resolver.insert(collection, values)
        } catch (security: SecurityException) {
            return OpResult.Fail(ErrorCode.PERMISSION_DENIED, security.message ?: "没有新建媒体条目的权限")
        } ?: return OpResult.Fail(ErrorCode.IO_ERROR, "无法在目标文件夹新建媒体条目")
        return try {
            val input = openInput(context, uri) ?: throw IOException("无法读取源条目")
            input.use { source ->
                resolver.openOutputStream(newUri)?.use { output -> source.copyTo(output) }
                    ?: throw IOException("无法写入目标条目")
            }
            resolver.update(newUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
            OpResult.Ok(newUri.toString(), dataPathOf(context, newUri))
        } catch (security: SecurityException) {
            // 失败回滚：删除刚插入的 pending 条目，避免留下空壳。
            runCatching { resolver.delete(newUri, null, null) }
            OpResult.Fail(ErrorCode.PERMISSION_DENIED, security.message ?: "没有写入目标文件夹的权限")
        } catch (failure: Exception) {
            runCatching { resolver.delete(newUri, null, null) }
            OpResult.Fail(ErrorCode.IO_ERROR, failure.message ?: "复制失败")
        }
    }

    // ---------------------------------------------------------------- SAF

    private fun safMeta(context: Context, uri: Uri): Pair<String, String>? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: fallbackName(uri)
            name to cursor.getString(1).orEmpty()
        }
    }.getOrNull()

    /** 目标 tree 根目录下已占用的显示名，用于自动加后缀。 */
    private fun safChildNames(context: Context, treeUri: Uri, parentDocumentId: String): Set<String> =
        runCatching {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) cursor.getString(0)?.let(::add)
                }
            }
        }.getOrNull() ?: emptySet()

    /**
     * SAF 移动：
     * - 同树同目录：本质是改名，用所有 provider 都支持的 renameDocument；
     * - 同树跨目录：目标无重名时优先 moveDocument，provider 未实现则退化为复制 + 删源；
     * - 跨树 / 目标是普通目录：createDocument（或 File）流复制，成功后删源，删源失败回滚新副本。
     */
    private fun moveSaf(context: Context, uri: Uri, targetDir: MediaDir): OpResult {
        val meta = safMeta(context, uri)
            ?: return OpResult.Fail(ErrorCode.SOURCE_MISSING, "源文档不存在或不可访问")
        if (targetDir.treeUri == null) {
            val dir = targetDir.path?.let(::File)
                ?: return OpResult.Fail(ErrorCode.TARGET_REQUIRED, "未选择目标文件夹")
            ensureDirectory(dir)?.let { return it }
            val copied = copyStreamToDirectory(context, dir, meta.first) { openInput(context, uri) }
            if (copied is OpResult.Fail) return copied
            val copiedOk = copied as OpResult.Ok
            return finishMove(
                copied = copiedOk,
                deleteSource = { DocumentsContract.deleteDocument(context.contentResolver, uri) },
                rollback = { copiedOk.newPath?.let { path -> File(path).delete() } },
            )
        }
        val treeUri = Uri.parse(targetDir.treeUri)
        val targetTreeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return OpResult.Fail(ErrorCode.TARGET_REQUIRED, "目标不是有效的 SAF 文件夹")
        val sourceTreeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val sourceParentId = documentId?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }

        if (sourceTreeId == targetTreeId && sourceParentId != null) {
            // 同目录改名：排除自身后去重，避免把「移动到原目录」误判成重名。
            val taken = safChildNames(context, treeUri, targetTreeId) - meta.first
            val name = uniqueName(meta.first, taken)
            if (sourceParentId == targetTreeId) {
                if (name == meta.first) return OpResult.Ok(uri.toString(), null)
                val renamed = runCatching {
                    DocumentsContract.renameDocument(context.contentResolver, uri, name)
                }.getOrNull()
                if (renamed != null) return OpResult.Ok(renamed.toString(), null)
            }
            // 同树跨目录且目标无重名：尝试 provider 原生 moveDocument（API 24+，实现率不高）。
            if (name !in taken) {
                val moved = runCatching {
                    DocumentsContract.moveDocument(
                        context.contentResolver,
                        uri,
                        DocumentsContract.buildDocumentUriUsingTree(uri, sourceParentId),
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, targetTreeId),
                    )
                }.getOrNull()
                if (moved != null) return OpResult.Ok(moved.toString(), null)
            }
        }

        val copied = copyStreamToSaf(context, treeUri, meta.second, meta.first) { openInput(context, uri) }
        if (copied is OpResult.Fail) return copied
        val copiedOk = copied as OpResult.Ok
        return finishMove(
            copied = copiedOk,
            deleteSource = { DocumentsContract.deleteDocument(context.contentResolver, uri) },
            rollback = { rollbackSaf(context, copiedOk.newUri) },
        )
    }

    /** SAF 复制：目标 tree 用 createDocument，目标是普通目录用流写入。 */
    private fun copySaf(context: Context, uri: Uri, targetDir: MediaDir): OpResult {
        val meta = safMeta(context, uri)
            ?: return OpResult.Fail(ErrorCode.SOURCE_MISSING, "源文档不存在或不可访问")
        return if (targetDir.treeUri != null) {
            copyStreamToSaf(context, Uri.parse(targetDir.treeUri), meta.second, meta.first) {
                openInput(context, uri)
            }
        } else {
            val dir = targetDir.path?.let(::File)
                ?: return OpResult.Fail(ErrorCode.TARGET_REQUIRED, "未选择目标文件夹")
            copyStreamToDirectory(context, dir, meta.first) { openInput(context, uri) }
        }
    }

    private fun rollbackSaf(context: Context, uriString: String?) {
        uriString ?: return
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uriString)) }
    }

    /**
     * 「先复制、后删源」的移动收尾：删源失败时回滚已生成的新副本并报错，
     * 避免源与副本同时残留（用户会看到重复文件）。
     */
    private inline fun finishMove(
        copied: OpResult.Ok,
        deleteSource: () -> Boolean,
        rollback: () -> Unit,
    ): OpResult {
        val deleted = runCatching(deleteSource).getOrDefault(false)
        if (deleted) return copied
        runCatching(rollback)
        return OpResult.Fail(ErrorCode.IO_ERROR, "复制已完成，但无法删除源文件")
    }

    // ---------------------------------------------------------------- 普通文件

    /**
     * 普通文件移动：同卷 renameTo；失败（跨卷 / 被占用）退化为流复制 + 成功后删源。
     * 目标不存在时 mkdirs，创建失败报 IO_ERROR；目标是文件报 TARGET_NOT_DIRECTORY。
     */
    internal fun moveFile(context: Context, file: File, targetDir: MediaDir): OpResult {
        if (!file.exists() || !file.isFile) {
            return OpResult.Fail(ErrorCode.SOURCE_MISSING, "源文件不存在：${file.absolutePath}")
        }
        if (targetDir.treeUri != null) {
            val copied = copyFileToSaf(context, file, Uri.parse(targetDir.treeUri))
            if (copied is OpResult.Fail) return copied
            val copiedOk = copied as OpResult.Ok
            return finishMove(
                copied = copiedOk,
                deleteSource = { file.delete() },
                rollback = { rollbackSaf(context, copiedOk.newUri) },
            )
        }
        val dir = targetDir.path?.let(::File)
            ?: return OpResult.Fail(ErrorCode.TARGET_REQUIRED, "未选择目标文件夹")
        ensureDirectory(dir)?.let { return it }
        val sameDir = file.absoluteFile.parentFile?.absolutePath == dir.absoluteFile.absolutePath
        val name = uniqueName(file.name, dir.list().orEmpty().toSet() - if (sameDir) file.name else "")
        val dest = File(dir, name)
        if (dest.absolutePath == file.absolutePath) return OpResult.Ok(null, file.absolutePath)
        if (file.renameTo(dest)) return OpResult.Ok(null, dest.absolutePath)
        // 跨卷 rename 通常返回 false：流复制成功后删源；删源失败回滚副本。
        return try {
            copyStreams(file, dest)
            if (!file.delete()) {
                dest.delete()
                OpResult.Fail(ErrorCode.IO_ERROR, "复制已完成，但无法删除源文件")
            } else {
                OpResult.Ok(null, dest.absolutePath)
            }
        } catch (failure: Exception) {
            dest.delete()
            OpResult.Fail(
                ErrorCode.CROSS_VOLUME_FAILED,
                failure.message ?: "跨卷移动失败：${file.absolutePath}",
            )
        }
    }

    /** 普通文件复制：流复制到目标目录，同名自动加后缀，绝不覆盖。 */
    internal fun copyFile(context: Context, file: File, targetDir: MediaDir): OpResult {
        if (!file.exists() || !file.isFile) {
            return OpResult.Fail(ErrorCode.SOURCE_MISSING, "源文件不存在：${file.absolutePath}")
        }
        if (targetDir.treeUri != null) {
            return copyFileToSaf(context, file, Uri.parse(targetDir.treeUri))
        }
        val dir = targetDir.path?.let(::File)
            ?: return OpResult.Fail(ErrorCode.TARGET_REQUIRED, "未选择目标文件夹")
        return copyStreamToDirectory(context, dir, file.name) { file.inputStream() }
    }

    private fun copyFileToSaf(context: Context, file: File, treeUri: Uri): OpResult =
        copyStreamToSaf(
            context,
            treeUri,
            MediaSourceScanner.mimeForName(file.name) ?: "application/octet-stream",
            file.name,
        ) { file.inputStream() }

    // ---------------------------------------------------------------- 通用流复制

    /**
     * 写入普通目录：目录缺失先 mkdirs，同名自动加 ` (n)` 后缀。
     * 目标是已有文件而不是目录时报 TARGET_NOT_DIRECTORY。
     */
    private fun copyStreamToDirectory(
        context: Context,
        dir: File,
        name: String,
        openInput: () -> InputStream?,
    ): OpResult {
        ensureDirectory(dir)?.let { return it }
        val finalName = uniqueName(name, dir.list().orEmpty().toSet())
        val dest = File(dir, finalName)
        return try {
            openInput()?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return OpResult.Fail(ErrorCode.SOURCE_MISSING, "无法读取源文件")
            OpResult.Ok(null, dest.absolutePath)
        } catch (security: SecurityException) {
            dest.delete()
            OpResult.Fail(ErrorCode.PERMISSION_DENIED, security.message ?: "没有写入目标目录的权限")
        } catch (failure: Exception) {
            dest.delete()
            OpResult.Fail(ErrorCode.IO_ERROR, failure.message ?: "复制失败")
        }
    }

    /**
     * 写入 SAF 目录：目标 tree 根下按已占用名去重后 createDocument，
     * 新建失败或写入失败删除刚建的文档（不留半截文件）。
     */
    private fun copyStreamToSaf(
        context: Context,
        treeUri: Uri,
        mime: String,
        name: String,
        openInput: () -> InputStream?,
    ): OpResult {
        val parentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return OpResult.Fail(ErrorCode.TARGET_REQUIRED, "目标不是有效的 SAF 文件夹")
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        val taken = safChildNames(context, treeUri, parentId)
        val finalName = uniqueName(name, taken)
        val resolver = context.contentResolver
        val newUri = try {
            DocumentsContract.createDocument(
                resolver,
                parentUri,
                mime.ifBlank { "application/octet-stream" },
                finalName,
            )
        } catch (security: SecurityException) {
            return OpResult.Fail(ErrorCode.PERMISSION_DENIED, security.message ?: "没有在目标文件夹新建文件的权限")
        } catch (failure: Exception) {
            return OpResult.Fail(ErrorCode.IO_ERROR, failure.message ?: "无法在目标文件夹新建文件")
        } ?: return OpResult.Fail(ErrorCode.IO_ERROR, "目标文件夹不允许新建文件")
        return try {
            openInput()?.use { input ->
                resolver.openOutputStream(newUri)?.use { output -> input.copyTo(output) }
                    ?: throw IOException("无法写入目标文件")
            } ?: throw IOException("无法读取源文件")
            OpResult.Ok(newUri.toString(), null)
        } catch (security: SecurityException) {
            runCatching { DocumentsContract.deleteDocument(resolver, newUri) }
            OpResult.Fail(ErrorCode.PERMISSION_DENIED, security.message ?: "没有写入目标文件夹的权限")
        } catch (failure: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, newUri) }
            OpResult.Fail(ErrorCode.IO_ERROR, failure.message ?: "复制失败")
        }
    }

    private fun copyStreams(source: File, dest: File) {
        source.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** 目标目录校验：存在但不是目录 → TARGET_NOT_DIRECTORY；缺失 → mkdirs，失败 → IO_ERROR。 */
    private fun ensureDirectory(dir: File): OpResult.Fail? = when {
        dir.isDirectory -> null
        dir.exists() -> OpResult.Fail(ErrorCode.TARGET_NOT_DIRECTORY, "目标路径不是文件夹：${dir.absolutePath}")
        dir.mkdirs() || dir.isDirectory -> null
        else -> OpResult.Fail(ErrorCode.IO_ERROR, "无法创建目标文件夹：${dir.absolutePath}")
    }

    private fun openInput(context: Context, uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)

    /** 内容 URI 不可查询时的兜底显示名：解码末段（SAF document id 形如 `primary:DCIM/a.jpg`）。 */
    private fun fallbackName(uri: Uri): String =
        Uri.decode(uri.lastPathSegment.orEmpty())
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }
            ?: "file"

    // ---------------------------------------------------------------- 索引同步

    /**
     * 移动成功后的索引清理：删旧 source_key 的索引行 + 缩略图缓存。
     * 不原地改 source_key：目标位置可能不在扫描范围内，且 MediaStore uri / 绝对路径两种形态
     * 混合改写容易产生重复行；交给下一轮增量索引按新位置重建。
     */
    private suspend fun purgeIndex(context: Context, sourceKey: String) {
        if (sourceKey.isBlank()) return
        runCatching { EtaDatabase.get(context).mediaIndexDao().deleteBySourceKeys(listOf(sourceKey)) }
        deleteThumbnail(context, sourceKey)
    }

    /** 缩略图寻址统一交给 [MediaThumbnailStore]：库里存文件名，目录在持久区。 */
    private fun deleteThumbnail(context: Context, sourceKey: String) {
        MediaThumbnailStore.deleteForSourceKey(context, sourceKey, null)
    }
}
