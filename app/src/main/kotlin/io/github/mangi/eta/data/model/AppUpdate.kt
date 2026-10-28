package io.github.mangi.eta.data.model

internal data class AppUpdateOffer(
    val versionName: String,
    val tagName: String,
    val notes: String,
    val htmlUrl: String,
    val apkUrl: String? = null,
    val apkName: String? = null,
) {
    /**
     * 归一化版本号：用于「忽略此版本」的持久化比较与弹窗去重。
     *
     * release 的 `name` 可能是 `5.3.16 (2026-10-29)`、`Eta 5.3.16` 这类带前后缀的字符串，
     * 所以不能只比较原始字符串。
     */
    val normalizedVersion: String
        get() = AppVersion.normalize(versionName).ifBlank { AppVersion.normalize(tagName) }
}

internal object AppVersion {
    /** 版本号本体：字符串里第一段形如 `1`、`5.2`、`5.2.3` 的数字。 */
    private val VERSION_PATTERN = Regex("""\d+(?:\.\d+)*""")

    fun normalize(value: String): String =
        VERSION_PATTERN.find(value.trim())?.value?.trim('.') ?: ""

    fun isNewer(remote: String, local: String): Boolean {
        val remoteParts = parts(remote)
        val localParts = parts(local)
        if (remoteParts.isEmpty()) return false
        if (localParts.isEmpty()) return true
        val size = maxOf(remoteParts.size, localParts.size)
        for (index in 0 until size) {
            val remotePart = remoteParts.getOrElse(index) { 0 }
            val localPart = localParts.getOrElse(index) { 0 }
            if (remotePart != localPart) return remotePart > localPart
        }
        return false
    }

    /**
     * 用户已忽略 [ignoredVersion] 后，[remoteVersion] 是否应该继续静默。
     *
     * 忽略的语义是“这个版本以及更低的版本都不再提醒”，所以忽略 5.3.16 后 5.3.17 仍会提示。
     * 任一版本号无法解析时返回 false（宁可提示，也不要因为解析失败静默掉真实更新）。
     */
    fun isIgnoredBy(remoteVersion: String, ignoredVersion: String): Boolean {
        val remote = normalize(remoteVersion)
        val ignored = normalize(ignoredVersion)
        if (remote.isBlank() || ignored.isBlank()) return false
        return !isNewer(remote, ignored)
    }

    private fun parts(value: String): List<Int> =
        normalize(value)
            .split('.')
            .filter { it.isNotEmpty() }
            .map { it.toIntOrNull() ?: 0 }
}
