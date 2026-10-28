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
    private const val AUTO_CHECK_INTERVAL_MS = 30_000L
    private const val MAX_APK_BYTES = 120L * 1024L * 1024L

    fun currentVersionName(context: Context): String =
        runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                .versionName
        }.getOrNull().orEmpty()

    suspend fun checkForUpdate(context: Context, force: Boolean = false): Result<AppUpdateOffer?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val current = currentVersionName(context)
                if (!force) {
                    val lastCheck = SettingsDataStore.updateLastCheckAt()
                    if (lastCheck > 0L && System.currentTimeMillis() - lastCheck < AUTO_CHECK_INTERVAL_MS) {
                        return@runCatching null
                    }
                }
                val offer = fetchLatest()
                SettingsDataStore.setUpdateLastCheckAt(System.currentTimeMillis())
                if (offer == null) return@runCatching null
                val newer = AppVersion.isNewer(offer.versionName, current) ||
                    AppVersion.isNewer(offer.tagName, current)
                if (!newer) {
                    return@runCatching null
                }
                val dismissed = SettingsDataStore.updateDismissedVersion()
                val remote = AppVersion.normalize(offer.versionName).ifBlank {
                    AppVersion.normalize(offer.tagName)
                }
                // 用户选择「忽略此版本」后，无论是自动检查还是冷启动检查都不再提醒；
                // 仅当发布更新的版本时才重新弹出。手动检查同样遵循忽略状态。
                if (remote.isNotBlank() && remote == AppVersion.normalize(dismissed)) {
                    return@runCatching null
                }
                offer
            }
        }

    suspend fun dismissVersion(version: String) {
        val normalized = AppVersion.normalize(version)
        if (normalized.isNotBlank()) {
            SettingsDataStore.setUpdateDismissedVersion(normalized)
        }
        SettingsDataStore.setUpdateLastCheckAt(System.currentTimeMillis())
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
            ?: "eta-${AppVersion.normalize(offer.versionName).ifBlank { "update" }}.apk"
        val dir = File(context.applicationContext.cacheDir, "updates").apply { mkdirs() }
        val destination = File(dir, name)
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .get()
            .build()
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
                        if (total > MAX_APK_BYTES) {
                            destination.delete()
                            error("安装包过大")
                        }
                        output.write(buffer, 0, read)
                        onProgress?.invoke(total, declared)
                    }
                }
            }
        }
        if (!destination.isFile || destination.length() <= 0L) {
            destination.delete()
            error("下载失败")
        }
        destination
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
