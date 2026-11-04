package io.github.mangi.eta.ui.app

import android.view.Choreographer
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavBackStackEntry
import io.github.mangi.eta.ui.adaptive.EtaMotion
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal const val NAVIGATION_ACTIVITY_STALE_NS = 200_000_000L

/**
 * 转场时长，与下面的转场动画保持一致，用来确定导航活跃的采样窗口。
 *
 * 与 [EtaMotion.NavigationFallbackDurationMillis] 共用同一个来源：侧栏跟随收起的时长也从
 * 这里推导，避免出现「侧栏按 475ms、页面按 300ms」的两份魔数错配。
 */
private val NAVIGATION_TRANSITION_DURATION_MS = EtaMotion.NavigationFallbackDurationMillis.toInt()
private val NAVIGATION_TRANSITION_WINDOW_NS = NAVIGATION_TRANSITION_DURATION_MS * 1_000_000L

/** A draw that stopped updating must not keep the covered chat live. */
internal fun navigationActivityIsCurrent(flag: Boolean, stampNs: Long, nowNs: Long, staleNs: Long): Boolean =
    flag && nowNs - stampNs < staleNs

/**
 * Samples the navigation transition. The sampler only stores atomics and a frame callback publishes the
 * boolean when it actually changes, and treats a frozen stamp as idle so the last frame cannot leave the
 * chat running.
 */
internal class ChatNavigationActivityTracker(
    private val onChanged: (Boolean) -> Unit,
    private val staleNs: Long = NAVIGATION_ACTIVITY_STALE_NS,
    private val nowNs: () -> Long = System::nanoTime,
    private val poster: (Choreographer.FrameCallback) -> Unit = { callback ->
        Choreographer.getInstance().postFrameCallback(callback)
    },
    private val remover: (Choreographer.FrameCallback) -> Unit = { callback ->
        Choreographer.getInstance().removeFrameCallback(callback)
    },
) {
    private val moving = AtomicBoolean(false)
    private val stampNs = AtomicLong(0L)
    private val ticking = AtomicBoolean(false)
    private var published: Boolean? = null
    private val callback: Choreographer.FrameCallback = Choreographer.FrameCallback {
        val now = nowNs()
        if (moving.get() && !navigationActivityIsCurrent(true, stampNs.get(), now, staleNs)) {
            moving.set(false)
        }
        val active = moving.get()
        if (active != published) {
            published = active
            onChanged(active)
        }
        if (active) {
            poster(callback)
        } else {
            ticking.set(false)
        }
    }

    fun observeFrame(active: Boolean) {
        moving.set(active)
        stampNs.set(nowNs())
        if (active) ensureTicking()
    }

    fun cancel() {
        moving.set(false)
        if (ticking.compareAndSet(true, false)) remover(callback)
    }

    private fun ensureTicking() {
        if (ticking.compareAndSet(false, true)) poster(callback)
    }
}

/**
 * NavHost 的转场与导航活跃采样。转场几何沿用原来的默认值：新页从侧边整屏推入，
 * 被盖住的页只做四分之一屏的视差并淡到 0.9。
 *
 * 预测性返回手势期间由 NavHost 逐帧 seek 转场进度，默认几何是 scaleOut(0.7f)，看起来是页面往内缩；
 * 这里让预测态直接复用 [popEnterTransition]/[popExitTransition] 的几何，手势拖动即右平移，提交后无跳变。
 */
internal class ChatNavigationActivityTransition(
    val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition,
    val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition,
    val popEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition,
    val popExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition,
    val predictivePopEnterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition,
    val predictivePopExitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition,
    private val tracker: ChatNavigationActivityTracker,
) {
    constructor(rtl: Boolean, tracker: ChatNavigationActivityTracker) : this(
        enterTransition = {
            slideInHorizontally(animationSpec = tween(NAVIGATION_TRANSITION_DURATION_MS)) { fullWidth ->
                if (rtl) -fullWidth else fullWidth
            }
        },
        exitTransition = {
            slideOutHorizontally(animationSpec = tween(NAVIGATION_TRANSITION_DURATION_MS)) { fullWidth ->
                if (rtl) fullWidth / 4 else -fullWidth / 4
            } + fadeOut(
                animationSpec = tween(NAVIGATION_TRANSITION_DURATION_MS),
                targetAlpha = 0.9f,
            )
        },
        popEnterTransition = {
            slideInHorizontally(animationSpec = tween(NAVIGATION_TRANSITION_DURATION_MS)) { fullWidth ->
                if (rtl) fullWidth / 4 else -fullWidth / 4
            } + fadeIn(
                animationSpec = tween(NAVIGATION_TRANSITION_DURATION_MS),
                initialAlpha = 0.9f,
            )
        },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(NAVIGATION_TRANSITION_DURATION_MS)) { fullWidth ->
                if (rtl) -fullWidth else fullWidth
            }
        },
        predictivePopEnterTransition = { _ ->
            slideInHorizontally(animationSpec = tween(NAVIGATION_TRANSITION_DURATION_MS)) { fullWidth ->
                if (rtl) fullWidth / 4 else -fullWidth / 4
            } + fadeIn(
                animationSpec = tween(NAVIGATION_TRANSITION_DURATION_MS),
                initialAlpha = 0.9f,
            )
        },
        predictivePopExitTransition = { _ ->
            slideOutHorizontally(animationSpec = tween(NAVIGATION_TRANSITION_DURATION_MS)) { fullWidth ->
                if (rtl) -fullWidth else fullWidth
            }
        },
        tracker = tracker,
    )

    fun cancel() = tracker.cancel()

    /**
     * 一次导航的转场期间逐帧标记活跃；采样结束后由 tracker 的过期看门狗把它收敛成不活跃。
     */
    suspend fun sampleWhileAnimating(windowNs: Long = NAVIGATION_TRANSITION_WINDOW_NS) {
        var startNs = 0L
        var started = false
        while (true) {
            val frameNs = withFrameNanos { it }
            if (!started) {
                startNs = frameNs
                started = true
            }
            tracker.observeFrame(true)
            if (frameNs - startNs >= windowNs) break
        }
    }
}

/**
 * 转场几何与导航活跃采样；[onActivityChanged] 只在活跃状态真正变化时回调。
 */
@Composable
internal fun chatNavigationActivityTransition(
    onActivityChanged: (Boolean) -> Unit,
): ChatNavigationActivityTransition {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val publish = rememberUpdatedState(onActivityChanged)
    val tracker = remember { ChatNavigationActivityTracker(onChanged = { active -> publish.value(active) }) }
    return remember(rtl) { ChatNavigationActivityTransition(rtl, tracker) }
}
