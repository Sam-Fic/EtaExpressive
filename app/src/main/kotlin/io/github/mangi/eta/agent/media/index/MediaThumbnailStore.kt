package io.github.mangi.eta.agent.media.index

import android.content.Context
import android.util.Log
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 缩略图存储：目录、寻址、迁移与容量回收的唯一入口。
 *
 * 选址 [Context.getNoBackupFilesDir]（而非 `cacheDir`）：缩略图是派生数据但需要长期可用，
 * `cacheDir` 会被系统在低存储时优先回收，回收后索引行仍保留路径 → 「有行无图」的永久空白。
 * 不放 `filesDir` 是避免被 Auto Backup 计入（缩略图可由媒体重建；Eta 自己的备份归档仍显式导出）。
 *
 * 寻址方式：索引表 `thumb_path` 只存**文件名**（`SHA1(sourceKey).jpg`），读时按当前安装目录拼接，
 * 重装/换机/应用数据目录迁移后依然可解析；旧版本写入的绝对路径由 [ensureMigrated] 一次性归一。
 */
internal object MediaThumbnailStore {
    /** 与旧版 `cacheDir/media-thumbs` 同名，迁移时可直接搬运文件。 */
    const val DIRECTORY = "media-thumbs"

    /**
     * 空串哨兵：LRU 主动淘汰过的缩略图。渲染端据此区分「被回收」与「生成/解码失败」；
     * 索引自愈仍会重建它（单轮有上限、按档节流），所以不会长期缺失，容量上限也仍由 [trim] 维持。
     */
    const val EVICTED = ""

    /** 总量上限；单张 256px JPEG 约 10–25KB，192MiB 大致可覆盖数万条目。 */
    private const val MAX_BYTES = 192L * 1024 * 1024

    /** 淘汰到上限的 90%，避免每次索引都紧贴阈值反复淘汰。 */
    private const val TRIM_RATIO = 0.9

    /** 单轮索引最多尝试重建多少张缩略图，避免低耗档被大量缺失条目拖成全量重扫。 */
    const val MAX_REPAIRS_PER_RUN = 300

    private const val PREFS = "media_thumbnail_store"
    private const val KEY_MIGRATED = "migrated_v2"
    private const val TAG = "MediaThumbnailStore"

    /** 写入/搬运的临时后缀：先落临时文件再原子改名，中断不会留下「半张图」被当成可用缩略图。 */
    private const val TEMP_SUFFIX = ".tmp"

    private val migrationMutex = Mutex()

    fun directory(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, DIRECTORY).apply { mkdirs() }

    /** 缩略图命名：源 key 的 SHA-1，稳定且与旧版一致。 */
    fun fileNameFor(sourceKey: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(sourceKey.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".jpg"
    }

    /**
     * 把库里可能出现的任意形态归一成文件名：绝对路径、相对路径、Windows 反斜杠、纯文件名。
     * 空串哨兵与无法识别的形态返回 null（视为「没有缩略图」）。
     */
    fun fileNameOf(stored: String?): String? {
        val raw = stored?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
        return name.takeIf { it.isNotEmpty() && it.endsWith(".jpg", ignoreCase = true) }
    }

    /**
     * 解析成当前安装目录下的文件；值损坏或为空返回 null。
     *
     * 迁移尚未完成（或失败重试中）时，旧 `cacheDir/media-thumbs` 里的同名文件仍可作为**读路径**，
     * 保证老用户升级后立刻能显示缩略图；迁移搬完后旧目录被删，这里自然不再命中。
     */
    fun fileFor(context: Context, stored: String?): File? {
        val name = fileNameOf(stored) ?: return null
        val primary = File(directory(context), name)
        if (primary.isFile) return primary
        val legacy = File(File(context.applicationContext.cacheDir, DIRECTORY), name)
        return if (legacy.isFile) legacy else primary
    }

    /** 可用 = 文件存在且非空（0 字节视为写入中断）。 */
    fun isUsable(context: Context, stored: String?): Boolean {
        val file = fileFor(context, stored) ?: return false
        return file.isFile && file.length() > 0
    }

    /** 写入（覆盖同名）；返回文件名，失败返回 null 并留日志。先写 `.tmp` 再改名，避免中断留下半张图。 */
    fun write(context: Context, sourceKey: String, bytes: ByteArray): String? = runCatching {
        val name = fileNameFor(sourceKey)
        val dir = directory(context)
        val temp = File(dir, "$name$TEMP_SUFFIX")
        temp.writeBytes(bytes)
        check(temp.renameTo(File(dir, name))) { "缩略图改名失败" }
        name
    }.onFailure { failure ->
        Log.w(TAG, "缩略图写入失败 source=$sourceKey type=${failure.javaClass.simpleName}")
    }.getOrNull()

    /** 删除两个基目录下的同名文件：迁移未完成时旧副本也要清掉，避免残留孤儿。 */
    fun delete(context: Context, stored: String?) {
        val name = fileNameOf(stored) ?: return
        runCatching { File(directory(context), name).delete() }
        runCatching { File(File(context.applicationContext.cacheDir, DIRECTORY), name).delete() }
    }

    /** 删索引行上的缩略图；兜底再按 sourceKey 推一次文件名，清掉行里没记录到的孤儿文件。 */
    fun deleteForSourceKey(context: Context, sourceKey: String, stored: String?) {
        delete(context, stored)
        delete(context, fileNameFor(sourceKey))
    }

    /** 迁移只需执行一次；失败不置位，下次启动重试（迁移过程幂等，可中断）。 */
    suspend fun ensureMigrated(context: Context): Unit = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (prefs(app).getBoolean(KEY_MIGRATED, false)) return@withContext
        migrationMutex.withLock {
            if (prefs(app).getBoolean(KEY_MIGRATED, false)) return@withLock
            runCatching { migrate(app) }
                .onSuccess { prefs(app).edit().putBoolean(KEY_MIGRATED, true).apply() }
                .onFailure { failure ->
                    // 不置位：下次启动（或下一轮索引）继续尝试，不会把老缩略图永久丢在外挂目录。
                    Log.w(TAG, "缩略图存储迁移未完成，下次重试 type=${failure.javaClass.simpleName}")
                }
        }
    }

    /** 跨卷搬运的原子拷贝：先写 `.tmp` 再改名；失败返回 false（临时文件由下次迁移清理）。 */
    private fun copyAtomically(source: File, target: File): Boolean = runCatching {
        val temp = File(target.parentFile, target.name + TEMP_SUFFIX)
        source.copyTo(temp, overwrite = true)
        check(temp.renameTo(target)) { "临时文件改名失败" }
    }.isSuccess

    /**
     * 一次性迁移（幂等、可中断）：
     * 1. 旧 `cacheDir/media-thumbs` 的文件**尽量搬**到持久目录，目标已存在则丢弃旧副本（内容等价）；
     * 2. 索引行的 `thumb_path` 归一为文件名；文件即便已丢也保留文件名，交给增量索引自愈重建。
     */
    private suspend fun migrate(app: Context) {
        val dir = directory(app)
        val legacy = File(app.cacheDir, DIRECTORY)
        var moved = 0
        if (legacy.isDirectory) {
            legacy.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                // 上次中断留下的临时文件直接丢弃（正式文件要么完整存在、要么不存在）。
                if (file.name.endsWith(TEMP_SUFFIX)) {
                    file.delete()
                    return@forEach
                }
                val target = File(dir, file.name)
                if (!target.exists()) {
                    // 同卷 rename 是原子的；跨卷才退化为「写临时文件 + 改名」。
                    val ok = file.renameTo(target) || copyAtomically(file, target)
                    if (ok) moved++ else Log.w(TAG, "缩略图搬移失败 name=${file.name}")
                }
                if (target.exists()) file.delete()
            }
            legacy.delete()
        }

        val dao = EtaDatabase.get(app).mediaIndexDao()
        var normalized = 0
        dao.thumbRows().forEach { row ->
            val stored = row.thumbPath
            if (stored == EVICTED) return@forEach
            val name = fileNameOf(stored)
            if (name != stored) {
                dao.updateThumbPath(row.sourceKey, row.modelId, row.vectorDim, name)
                normalized++
            }
        }
        Log.i(TAG, "缩略图存储迁移完成 moved=$moved normalized=$normalized")
    }

    /**
     * 容量回收：超出上限时按最后修改时间淘汰最旧的缩略图，并把对应索引行标记为 [EVICTED]，
     * 保证不会留下「行里有路径、文件已删」的孤儿。返回淘汰数量。
     */
    suspend fun trim(context: Context): Int = withContext(Dispatchers.IO) {
        // 先保证寻址已归一：淘汰只按文件名标记索引行，若库里还是旧的绝对路径，
        // 就会留下「行里有路径、文件已删」的新孤儿。迁移失败时本轮不淘汰，下轮再试。
        ensureMigrated(context)
        if (!prefs(context).getBoolean(KEY_MIGRATED, false)) return@withContext 0
        val dir = directory(context)
        // 先清掉中断留下的临时文件，它们不参与容量统计，也不应留在目录里。
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) }?.forEach { it.delete() }
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg", ignoreCase = true) }
            ?: return@withContext 0
        var total = files.sumOf { it.length() }
        if (total <= MAX_BYTES) return@withContext 0
        val dao = EtaDatabase.get(context).mediaIndexDao()
        val target = (MAX_BYTES * TRIM_RATIO).toLong()
        var evicted = 0
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= target) return@forEach
            val size = file.length()
            if (!file.delete()) return@forEach
            total -= size
            evicted++
            dao.markThumbEvicted(file.name)
        }
        if (evicted > 0) Log.i(TAG, "缩略图 LRU 清理 evicted=$evicted 剩余=$total")
        evicted
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
