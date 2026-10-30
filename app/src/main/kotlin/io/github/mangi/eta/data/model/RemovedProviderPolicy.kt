package io.github.mangi.eta.data.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Tombstone only: reject old configurations/backups, never make a network request. */
internal object RemovedProviderPolicy {
    private const val MESSAGE = "该提供商接入已移除，请选择其他提供商"

    fun isRemoved(baseUrl: String, endpointMode: String = "", authMode: String = ""): Boolean {
        val host = baseUrl.trim().toHttpUrlOrNull()?.host.orEmpty()
        return endpointMode.trim().equals("antigravity", ignoreCase = true) ||
            authMode.trim().equals("oauth_antigravity", ignoreCase = true) ||
            authMode.trim().equals(ProviderAuthModes.REMOVED, ignoreCase = true) ||
            host == "cloudcode-pa.googleapis.com" ||
            host == "daily-cloudcode-pa.googleapis.com" ||
            host == "daily-cloudcode-pa.sandbox.googleapis.com"
    }

    fun isRemoved(provider: ProviderSetting): Boolean = isRemoved(
        provider.baseUrl,
        when (provider) {
            is OpenAiCompatibleProviderSetting -> provider.endpointMode
            is CustomProviderSetting -> provider.endpointMode
            is AnthropicProviderSetting -> ""
        },
        provider.authMode,
    )

    fun requireSupported(baseUrl: String, endpointMode: String = "", authMode: String = "") {
        require(!isRemoved(baseUrl, endpointMode, authMode)) { MESSAGE }
    }

    fun requireSupported(provider: ProviderSetting) {
        require(!isRemoved(provider)) { MESSAGE }
    }
}
