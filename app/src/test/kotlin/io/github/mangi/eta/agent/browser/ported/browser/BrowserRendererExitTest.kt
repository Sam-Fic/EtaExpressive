package io.github.mangi.eta.agent.browser.ported.browser

import android.content.Context
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserRendererExitTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun detail(crashed: Boolean) = object : RenderProcessGoneDetail() {
        override fun didCrash() = crashed
        override fun rendererPriorityAtExit() = WebView.RENDERER_PRIORITY_IMPORTANT
    }

    @Test fun eachAssociatedClientHandlesCrashAndKillAndDisposesOnlyOnce() = runBlocking {
        for (crashed in listOf(true, false)) {
            val views = List(3) { CountingWebView(context) }
            val managers = views.mapIndexed { index, view ->
                BrowserUseManager(view, researchMode = index > 0, childInteractive = index == 2)
            }
            val owners = views.map { view -> FrameLayout(context).also { it.addView(view) } }
            var removed = 0
            managers.forEach { it.onRendererGone = { removed++ } }
            views.forEachIndexed { i, view ->
                assertTrue(view.webViewClient.onRenderProcessGone(view, detail(crashed)))
                assertEquals(0, owners[i].childCount)
                assertFalse(managers[i].isLoading.value)
                assertTrue(view.webViewClient.onRenderProcessGone(view, detail(crashed)))
                managers[i].destroy()
                assertTrue(shadowOf(view).wasDestroyCalled())
                assertEquals(1, view.destroys)
                val result = managers[i].execute(BrowserActionInput(BrowserAction.GET_PAGE_INFO))
                assertFalse(result.success)
                assertTrue(result.text.contains("BROWSER_RENDERER_GONE"))
            }
            assertEquals(3, removed)
        }
    }

    @Test fun rendererExitCompletesPendingNavigationAndAsyncReplies() {
        val view = WebView(context)
        val manager = BrowserUseManager(view)
        val navigation = CompletableDeferred<Unit>()
        val asyncJs = CompletableDeferred<String>()
        setField(manager, "navigationDeferred", navigation)
        setField(manager, "asyncJsDeferred", asyncJs)
        assertTrue(view.webViewClient.onRenderProcessGone(view, detail(true)))
        assertTrue(navigation.isCancelled)
        assertTrue(asyncJs.isCancelled)
        assertFalse(manager.canGoBack.value)
        assertFalse(manager.canGoForward.value)
        manager.destroy()
    }

    @Test fun inFlightActionFailsWithoutCancellingCallerOrReplaying() = runBlocking {
        val view = CountingWebView(context)
        val manager = BrowserUseManager(view)
        val parent = SupervisorJob()
        val scope = CoroutineScope(parent + Dispatchers.Main.immediate)
        val action = scope.async { manager.execute(BrowserActionInput(BrowserAction.NAVIGATE, url = "https://example.com")) }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(action.isCompleted)
        assertTrue(view.webViewClient.onRenderProcessGone(view, detail(true)))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(action.isCompleted)
        val result = action.await()
        assertFalse(result.success)
        assertTrue(result.text.contains("outcome is unknown"))
        assertTrue(parent.isActive)
        assertEquals(1, view.navigations)
        scope.cancel()
    }

    @Test fun poolRemovesOnlyFailedInstanceAndAllowsFreshTabWithoutReload() {
        val pool = BrowserTabPool(context)
        val first = pool.ensureTabForUI()
        val oldView = first.manager.webView
        assertTrue(oldView.webViewClient.onRenderProcessGone(oldView, detail(true)))
        assertTrue(pool.tabs.value.isEmpty())
        val fresh = pool.ensureTabForUI()
        assertNotSame(oldView, fresh.manager.webView)
        assertEquals("", fresh.manager.currentURL.value)
        assertTrue(oldView.webViewClient.onRenderProcessGone(oldView, detail(true)))
        assertSame(fresh.manager, pool.tabs.value.single().manager)
        pool.destroy()
    }

    private class CountingWebView(context: Context) : WebView(context) {
        var destroys = 0
        var navigations = 0
        override fun destroy() { destroys++; super.destroy() }
        override fun loadUrl(url: String) { navigations++; super.loadUrl(url) }
    }

    private fun setField(manager: BrowserUseManager, name: String, value: Any) {
        BrowserUseManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
    }
}
