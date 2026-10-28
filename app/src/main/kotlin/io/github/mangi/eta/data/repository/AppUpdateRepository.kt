package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppUpdateOffer
import io.github.mangi.eta.data.model.AppVersion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

internal object AppUpdateRepository {
    const val FILE_PROVIDER_AUTHORITY = "io.github.mangi.eta.fileprovider"
    const val LATEST_RELEASE_URL = "https://api.github.com/repos/Sam-Fic/Eta/releases/latest"
    private const val MAX_APK_BYTES = 120L * 1024L * 1024L

    /**
     * 同一版本的自动提醒间隔。
     *
     * 用户点「稍后」后，一天之内多次冷启动不应该反复被同一个版本拦一次；
     * 过了这个窗口自动检查才会再提。手动检查与更高版本不受影响。
     */
    private const val PROMPT_INTERVAL_MS = 24L * 60L * 60L * 1000L

    fun currentVersionName(context: Context): String =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName
        }.getOrNull().orEmpty()

    /**
     * 检查更新。
     *
     * 自动检查（冷启动）不要传 `respectIgnored = false`：用户点了「忽略此版本」之后，
     * 只应该在发布更高版本时再提醒。用户在关于页主动检查时传 `false`，否则会
     * 因为命中忽略列表或提醒节流而错误地提示「已是最新版本」。
     */
    suspend fun checkForUpdate(
        context: Context,
        respectIgnored: Boolean = true,
    ): Result<AppUpdateOffer?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val current = currentVersionName(context)
                val offer = fetchLatest()
                if (offer == null) return@runCatching null
                val newer = AppVersion.isNewer(offer.versionName, current) ||
                    AppVersion.isNewer(offer.tagName, current)
                if (!newer) {
                    return@runCatching null
                }
                if (respectIgnored && isIgnored(offer)) {
                    return@runCatching null
                }
                if (respectIgnored && recentlyPrompted(offer)) {
                    return@runCatching null
                }
                offer
            }
        }

    /** 同一版本在 [PROMPT_INTERVAL_MS] 内已经提醒过。 */
    private suspend fun recentlyPrompted(offer: AppUpdateOffer): Boolean {
        val (version, promptedAt) = SettingsDataStore.updateLastPrompt()
        if (promptedAt <= 0L) return false
        if (AppVersion.normalize(version) != offer.normalizedVersion) return false
        return System.currentTimeMillis() - promptedAt < PROMPT_INTERVAL_MS
    }

    /** 记住“这个版本刚刚提醒过”，用于 [recentlyPrompted] 的节流。 */
    suspend fun markPrompted(offer: AppUpdateOffer) {
        SettingsDataStore.setUpdateLastPrompt(offer.normalizedVersion, System.currentTimeMillis())
    }

    /**
     * 该 offer 是否已被用户忽略。
     *
     * 忽略是“不高于已忽略版本都不再提醒”，所以用户忽略 5.3.16 后，5.3.17 仍会提示。
     */
    private suspend fun isIgnored(offer: AppUpdateOffer): Boolean =
        AppVersion.isIgnoredBy(
            remoteVersion = offer.normalizedVersion,
            ignoredVersion = SettingsDataStore.updateDismissedVersion(),
        )

    /**
     * 用户选择「忽略此版本」：持久化后，自动检查不再提示该版本及更低版本。
     */
    suspend fun dismissVersion(version: String) {
        val normalized = AppVersion.normalize(version)
        if (normalized.isNotBlank()) {
            SettingsDataStore.setUpdateDismissedVersion(normalized)
            // 忽略本身就意味着长期静默，不必再占着“刚刚提醒过”的标记。
            SettingsDataStore.setUpdateLastPrompt("", 0L)
        }
    }

    /**
     * 下载 APK，支持进度回调。下载完成后返回文件路径。
     * 进度回调在 IO 线程上调用，UI 线程请通过其他机制（如 StateFlow/LiveData）接收。
     */
    suspend fun downloadApk(
        context: Context,
        offer: AppUpdateOffer,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val url = offer.apkUrl?.takeIf { it.startsWith("http") }
            ?: error("没有可下载的安装包")
        val name = offer.apkName
            ?.substringAfterLast('/')
            ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "eta-${offer.normalizedVersion.ifBlank { "update" }}.apk"
        val dir = File(context.applicationContext.cacheDir, "updates").apply { mkdirs() }
        val destination = File(dir, name)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .get()
            .build()
        try {
            AgentHttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("下载失败 HTTP ${response.code}")
                }
                val declared = response.body.contentLength()
                if (declared > MAX_APK_BYTES) error("安装包过大")
                destination.outputStream().use { output ->
                    response.body.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_APK_BYTES) error("安装包过大")
                            output.write(buffer, 0, read)
                            onProgress?.invoke(total, declared)
                        }
                    }
                }
            }
            if (!destination.isFile || destination.length() <= 0L) error("下载失败")
            destination
        } catch (failure: Throwable) {
            // 半截 APK 不能留在缓存目录：下次下载会误命中损坏文件。
            destination.delete()
            throw failure
        }
    }

    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openReleasePage(context: Context, offer: AppUpdateOffer) {
        val url = offer.htmlUrl.ifBlank { offer.apkUrl }.orEmpty()
        if (url.isBlank()) return
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun fetchLatest(): AppUpdateOffer? {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        return AgentHttpClient.client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                error("检查更新失败 HTTP ${response.code}")
            }
            AppUpdateParser.parseLatestRelease(body)
        }
    }
}
