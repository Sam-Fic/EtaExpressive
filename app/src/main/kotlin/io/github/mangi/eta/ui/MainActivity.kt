package io.github.mangi.eta.ui

import android.content.Intent
import android.os.Parcelable
import android.os.Trace
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.live.AgentCompletionNotifier
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppRoot
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.PredictiveBackController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** 助手浮窗口述「去配置」请求令牌；AgentAppRoot 落地语音设置页后复位。 */
    private var openVoiceSettingsRequest by mutableStateOf(0)
    /** 完成横幅点击直达的会话 id；AgentAppRoot 消费后复位。 */
    private var liveTargetConversationId by mutableStateOf<String?>(null)
    private var inboundShareUris by mutableStateOf<List<String>>(emptyList())
    private var appliedPredictiveBackEnabled = true
    private var refreshRateWindowResumed = false
    private var previousPreferredRefreshRate: Float? = null
    private var requestedPreferredRefreshRate: Float? = null
    private val refreshRateRequest = Runnable { applyPreferredRefreshRate() }

    internal companion object {
        /** 主应用（本 Activity）是否在前台：助手唤醒据此决定应用内响应还是弹浮窗。 */
        @Volatile
        var isForeground = false
            private set

        /** 主应用前台唤醒的听写请求令牌：聊天输入区交给听写会话直接开录。 */
        private val inAppDictationRequests = MutableStateFlow(0)
        internal val inAppDictationRequest = inAppDictationRequests.asStateFlow()
        internal fun requestInAppDictation() {
            inAppDictationRequests.value += 1
        }

        /** 已消费的听写唤起令牌水位：消费它的 composer 实例随会话切换整树销毁重建
         *  （实例内 consumedStartToken 记账一并丢失），重建后的新实例据此播种，
         *  旧唤起令牌不再被当成新唤起重放（否则每次切会话都会误开听写）。
         *  与令牌同款单调递增；ComposerBody 消费时上报，取 max 防乱序回退。 */
        private val consumedInAppDictationRequests = MutableStateFlow(0)
        internal val consumedInAppDictationRequest = consumedInAppDictationRequests.asStateFlow()
        internal fun markInAppDictationConsumed(token: Int) {
            consumedInAppDictationRequests.update { maxOf(it, token) }
        }

        /** 主应用前台唤醒的聚焦请求令牌：聊天输入区聚焦输入框并弹键盘。 */
        private val inAppFocusRequests = MutableStateFlow(0)
        internal val inAppFocusRequest = inAppFocusRequests.asStateFlow()
        internal fun requestInAppFocus() {
            inAppFocusRequests.value += 1
        }

        /** 已消费的聚焦唤起令牌水位：语义同 consumedInAppDictationRequests，防切会话后旧令牌重放、重弹键盘。 */
        private val consumedInAppFocusRequests = MutableStateFlow(0)
        internal val consumedInAppFocusRequest = consumedInAppFocusRequests.asStateFlow()
        internal fun markInAppFocusConsumed(token: Int) {
            consumedInAppFocusRequests.update { maxOf(it, token) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        @Suppress("DEPRECATION")
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED,
        )
        updateAssistantVoiceSettingsRequest(intent)
        updateLiveTarget(intent)
        consumeShareIntent(intent)
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            appliedPredictiveBackEnabled = initialAppearance.predictiveBackEnabled
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = initialAppearance)

                LaunchedEffect(appearance.predictiveBackEnabled) {
                    val enabled = appearance.predictiveBackEnabled
                    if (enabled != appliedPredictiveBackEnabled &&
                        PredictiveBackController.apply(applicationInfo, enabled)
                    ) {
                        appliedPredictiveBackEnabled = enabled
                        recreateWithoutTransition()
                    }
                }

                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                    onResolvedDarkModeChange = ::updateSystemBars,
                ) {
                    AgentAppRoot(
                        openVoiceSettingsRequest = openVoiceSettingsRequest,
                        liveTargetConversationId = liveTargetConversationId,
                        inboundShareUris = inboundShareUris,
                        onInboundShareConsumed = { inboundShareUris = emptyList() },
                        onLiveTargetConsumed = { liveTargetConversationId = null },
                        onVoiceSettingsRequestConsumed = { openVoiceSettingsRequest = 0 },
                        // 与主题共用同一份 DataStore 快照：开关一改就重组 NavDisplay 的 builder，
                        // 不需要像预测性返回那样 recreate Activity。
                        swipeBackEnabled = appearance.swipeBackEnabled,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        refreshRateWindowResumed = true
        applyPreferredRefreshRate()
        // Retry when the decor is attached; never leave a request queued after pause.
        window.decorView.post(refreshRateRequest)
    }

    override fun onPause() {
        super.onPause()
        isForeground = false
        refreshRateWindowResumed = false
        window.decorView.removeCallbacks(refreshRateRequest)
        val previous = previousPreferredRefreshRate
        if (previous != null && window.attributes.preferredRefreshRate == requestedPreferredRefreshRate) {
            val attributes = window.attributes
            attributes.preferredRefreshRate = previous
            window.attributes = attributes
        }
        previousPreferredRefreshRate = null
        requestedPreferredRefreshRate = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateAssistantVoiceSettingsRequest(intent)
        updateLiveTarget(intent)
        consumeShareIntent(intent)
    }

    private fun applyPreferredRefreshRate() {
        if (!refreshRateWindowResumed || isFinishing || isDestroyed) return
        val preferredRefreshRate = highestSupportedRefreshRate(
            window.decorView.display?.supportedModes
                ?.map { mode -> mode.refreshRate }
                .orEmpty(),
        )
        if (preferredRefreshRate <= 0f) return

        val attributes = window.attributes
        if (attributes.preferredRefreshRate == preferredRefreshRate) return
        if (previousPreferredRefreshRate == null) {
            previousPreferredRefreshRate = attributes.preferredRefreshRate
        }
        requestedPreferredRefreshRate = preferredRefreshRate
        Trace.beginSection("Eta.refresh.request hz=$preferredRefreshRate")
        try {
            attributes.preferredRefreshRate = preferredRefreshRate
            window.attributes = attributes
        } finally {
            Trace.endSection()
        }
    }

    private fun consumeShareIntent(intent: Intent?) {
        val uris = inboundUris(intent)
        if (uris.isEmpty()) return
        uris.forEach { uri ->
            val flags = intent?.flags ?: 0
            if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
        inboundShareUris = uris.map { it.toString() }
        intent?.removeExtra(Intent.EXTRA_STREAM)
        intent?.data = null
    }

    private fun inboundUris(intent: Intent?): List<android.net.Uri> {
        if (intent == null) return emptyList()
        val fromStream = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.shareUriExtra())
            Intent.ACTION_SEND_MULTIPLE -> intent.shareUriListExtra()
            else -> emptyList()
        }
        val fromData = intent.data?.takeIf {
            intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND
        }
        return (fromStream + listOfNotNull(fromData)).distinct()
    }

    private fun Intent.shareUriExtra(): android.net.Uri? {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(Intent.EXTRA_STREAM) as? android.net.Uri
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.shareUriListExtra(): List<android.net.Uri> {
        val values: ArrayList<out Parcelable>? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)
        }
        return values.orEmpty().mapNotNull { it as? android.net.Uri }
    }

    /** 助手浮窗口述「去配置」：请求令牌自增，AgentAppRoot 据此落地语音设置页。 */
    private fun updateAssistantVoiceSettingsRequest(intent: Intent?) {
        if (intent?.action != EtaAssistantOverlayService.ACTION_OPEN_VOICE_SETTINGS) return
        openVoiceSettingsRequest++
    }

    /** 完成横幅点击直达：读会话 extra 后立即清除，避免 recreate 时重复触发。 */
    private fun updateLiveTarget(intent: Intent?) {
        val conversationId = intent?.getStringExtra(AgentCompletionNotifier.EXTRA_CONVERSATION_ID) ?: return
        intent.removeExtra(AgentCompletionNotifier.EXTRA_CONVERSATION_ID)
        if (conversationId.isNotBlank()) liveTargetConversationId = conversationId
    }

    private fun updateSystemBars(isDark: Boolean) {
        val style = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            )
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        window.decorView.post {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recreateWithoutTransition() {
        overridePendingTransition(0, 0)
        recreate()
        overridePendingTransition(0, 0)
    }
}
