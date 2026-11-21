package io.github.mangi.eta.ui.screens.tools

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mijia.MijiaCloudClient
import io.github.mangi.eta.agent.mijia.MijiaStore
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTextField
import io.github.mangi.eta.ui.adaptive.EtaStatus
import io.github.mangi.eta.ui.components.TopBarIconButton
import java.util.concurrent.atomic.AtomicBoolean

/** 小米 OAuth 回调跳转前缀（homeassistant.local 为上游 yooma1 约定的占位回调域）。 */
private const val MIJIA_OAUTH_REDIRECT_PREFIX = "http://homeassistant.local:8123"

/**
 * 米家 OAuth2 整屏授权路由页（`AppRoute.MijiaOAuth`）。
 *
 * 按 docs/RULES.md §3.3 做成路由（McpServerAddScreen 同款）：页面没有不可序列化入参
 * （授权 URL 由 context 派生），不满足 EtaPageSurface 的豁免口径。授权完成后把回调 code
 * 写入 [MijiaStore.pendingOAuthCode] 并弹栈，由设置页统一换 Token 与同步设备——
 * 换取进度与结果文案都落在设置页的状态行上。
 *
 * 页面动作走顶栏 [TopBarIconButton]（§2.9）：清 Cookie 重载、外部浏览器 / 粘贴回调；
 * 返回键先回退网页历史，到头再退出授权页。
 */
@Composable
internal fun MijiaOAuthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val authorizeUrl = remember { MijiaCloudClient.buildAuthorizeUrl(context, skipConfirm = false) }
    val consumed = remember { AtomicBoolean(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var showPasteDialog by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // WebView 世代号：渲染进程崩溃销毁后自增，换 key 重建整个 AndroidView（factory 只跑一次）。
    var webViewEpoch by remember { mutableStateOf(0) }

    fun onCodeCaptured(code: String) {
        MijiaStore.publishOAuthCode(code)
        onBack()
    }

    fun tryConsumeRedirectUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (!url.startsWith(MIJIA_OAUTH_REDIRECT_PREFIX, ignoreCase = true)) return false
        val code = MijiaCloudClient.parseRedirectCode(url)
        if (!code.isNullOrBlank()) {
            if (consumed.compareAndSet(false, true)) {
                onCodeCaptured(code)
            }
            return true
        }
        return false
    }

    fun reloadWithCleanCookies() {
        pageError = null
        clearWebViewCookies()
        val view = webViewRef
        if (view == null) {
            // 渲染进程已崩溃销毁：重建整个 WebView——AndroidView 的 factory 只跑一次，
            // 靠自增世代号换 key 让组合重建。
            webViewEpoch++
        } else {
            view.apply {
                clearCache(true)
                clearHistory()
                loadUrl(MijiaCloudClient.buildAuthorizeUrl(context, skipConfirm = false))
            }
        }
    }

    BackHandler(enabled = true) {
        val view = webViewRef
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            onBack()
        }
    }

    // WebView 初始底色取主题背景（Int）：深色模式不再白屏闪烁（无颜色字面量，R1 安全）。
    val webBackground = MaterialTheme.colorScheme.background.toArgb()

    EtaScaffold(
        title = stringResource(R.string.mijia_oauth_title),
        onBack = onBack,
        modifier = Modifier.fillMaxSize(),
        actions = {
            TopBarIconButton(
                icon = Icons.Rounded.DeleteSweep,
                contentDescription = stringResource(R.string.mijia_oauth_clear_reload),
                onClick = ::reloadWithCleanCookies,
            )
            TopBarIconButton(
                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = stringResource(R.string.mijia_oauth_open_external),
                onClick = { showPasteDialog = true },
            )
        },
        bottomBar = {
            pageError?.let { err ->
                MijiaStatusLine(
                    err,
                    EtaStatus.Warning,
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(vertical = EtaDimens.SpaceSm),
                )
            }
        },
    ) { paddingValues, _, _ ->
        key(webViewEpoch) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = paddingValues.calculateTopPadding()),
                factory = { viewContext ->
                    FrameLayout(viewContext).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(webBackground)
                        val webView = createOAuthWebView(
                            viewContext,
                            authorizeUrl,
                            backgroundColor = webBackground,
                            onError = { pageError = it },
                            consumeRedirect = ::tryConsumeRedirectUrl,
                            onCreated = { webViewRef = it },
                            onRendererGone = { webViewRef = null },
                        )
                        addView(webView)
                    }
                },
                onRelease = { container ->
                    runCatching {
                        container.removeAllViews()
                        webViewRef?.stopLoading()
                        webViewRef?.destroy()
                        webViewRef = null
                    }
                },
            )
        }
    }

    if (showPasteDialog) {
        MijiaManualOAuthPasteDialog(
            authorizeUrl = authorizeUrl,
            onSubmitCode = ::onCodeCaptured,
            onDismiss = { showPasteDialog = false },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { view ->
                runCatching {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.stopLoading()
                    view.destroy()
                }
            }
            webViewRef = null
        }
    }
}

/**
 * 小米 OAuth 登录 WebView：标准移动端 Chrome UA，拦截回调 URL 提取 code。
 *
 * [backgroundColor] 传主题背景色（调用方从 colorScheme 换算），避免深色模式下
 * WebView 初始白屏；进度与 URL 状态不外抛（页面内无消费方）。
 * [onRendererGone] 渲染进程崩溃后由调用方清空引用（本函数作用域外）。
 */
@SuppressLint("SetJavaScriptEnabled", "WebViewClientOnReceivedSslError")
private fun createOAuthWebView(
    context: android.content.Context,
    authorizeUrl: String,
    backgroundColor: Int,
    onError: (String?) -> Unit,
    consumeRedirect: (String?) -> Boolean,
    onCreated: (WebView) -> Unit,
    onRendererGone: () -> Unit,
): WebView = WebView(context).apply {
    onCreated(this)
    layoutParams = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    setBackgroundColor(backgroundColor)
    setLayerType(View.LAYER_TYPE_NONE, null)
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.loadsImagesAutomatically = true
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    settings.safeBrowsingEnabled = false
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    // 使用标准移动端 Chrome UA，避免小米登录前端拒绝自定义客户端 UA
    settings.userAgentString =
        "Mozilla/5.0 (Linux; Android 16; RMX5200) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val target = request?.url?.toString()
            if (consumeRedirect(target)) return true
            if (!target.isNullOrBlank() &&
                !target.startsWith("http://", ignoreCase = true) &&
                !target.startsWith("https://", ignoreCase = true)
            ) {
                // 拦截 miaccount:// 等自定义协议，防止跳到错误页
                return true
            }
            return false
        }

        @Deprecated("Deprecated in Java")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            if (consumeRedirect(url)) return true
            if (!url.isNullOrBlank() &&
                !url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) {
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (consumeRedirect(url)) {
                view?.stopLoading()
                return
            }
            onError(null)
            super.onPageStarted(view, url, favicon)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?,
        ) {
            if (consumeRedirect(request?.url?.toString())) return
            if (request?.isForMainFrame == true) {
                onError(
                    context.getString(
                        R.string.mijia_web_error_hint,
                        error?.description ?: context.getString(R.string.mijia_web_error_unknown),
                    ),
                )
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(
            view: WebView?,
            handler: SslErrorHandler?,
            error: SslError?,
        ) {
            // 小米登录链路存在自签中间页：照 yooma1 上游做法放行，换取 OAuth 成功率。
            handler?.proceed()
        }

        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?,
        ): Boolean {
            // 渲染进程崩溃：死 WebView 立即销毁（BrowserUseManager 同款契约——
            // 每个匿名 WebViewClient 必须处理），提示用户经顶栏「清 Cookie 重载」重建；
            // 调用方清空引用后，reloadWithCleanCookies 会换 key 重建整页。
            runCatching {
                (view?.parent as? ViewGroup)?.removeView(view)
                view?.destroy()
            }
            onRendererGone()
            onError(context.getString(R.string.mijia_web_renderer_gone))
            return true
        }
    }
    loadUrl(authorizeUrl)
}

private fun clearWebViewCookies() {
    runCatching {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            removeAllCookies(null)
            flush()
        }
    }
}

/** 外部浏览器授权兜底：系统浏览器登录后粘贴回调 URL / code。 */
@Composable
private fun MijiaManualOAuthPasteDialog(
    authorizeUrl: String,
    onSubmitCode: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    EtaDialog(
        show = true,
        title = stringResource(R.string.mijia_paste_title),
        summary = stringResource(R.string.mijia_paste_summary, MIJIA_OAUTH_REDIRECT_PREFIX),
        onDismissRequest = onDismiss,
        // 动作行走官方 buttons 槽（EtaDialog KDoc：text 槽叠按钮会让底部留白翻倍）。
        actions = {
            EtaDialogActions(
                confirmText = stringResource(R.string.mijia_paste_confirm),
                onCancel = onDismiss,
                onConfirm = {
                    val raw = inputText.trim()
                    val code = when {
                        raw.startsWith("http://", ignoreCase = true) ||
                            raw.startsWith("https://", ignoreCase = true) -> {
                            MijiaCloudClient.parseRedirectCode(raw)
                                ?: runCatching { Uri.parse(raw).getQueryParameter("code") }.getOrNull()
                        }
                        raw.contains("code=") -> {
                            raw.substringAfter("code=").substringBefore("&").trim()
                        }
                        else -> raw
                    }
                    if (code.isNullOrBlank()) {
                        localError = context.getString(R.string.mijia_paste_parse_error)
                    } else {
                        onSubmitCode(code)
                    }
                },
            )
        },
    ) {
        EtaClickableRow(
            title = stringResource(R.string.mijia_paste_open_browser),
            // 拉起系统浏览器是瞬时动作，不给导航箭头。
            showChevron = false,
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                }.onFailure {
                    localError = context.getString(
                        R.string.mijia_paste_browser_error,
                        it.message ?: context.getString(R.string.mijia_unknown_error),
                    )
                }
            },
        )
        EtaSegmentedTextField(
            value = inputText,
            onValueChange = {
                inputText = it
                localError = null
            },
            label = stringResource(R.string.mijia_paste_field_label),
            singleLine = false,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        localError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
