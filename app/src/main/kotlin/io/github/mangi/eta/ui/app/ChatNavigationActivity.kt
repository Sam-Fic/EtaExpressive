package io.github.mangi.eta.ui.app

import android.view.Choreographer
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavBackStackEntry
import io.github.mangi.eta.ui.adaptive.EtaMotion
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal const val NAVIGATION_ACTIVITY_STALE_NS = 200_000_000L

/**
 * 导航活跃采样窗口（纳秒）。
 *
 * 转场已改用官方 `defaultSpatialSpec` 弹簧（没有固定时长），因此窗口不能写死：
 * 直接按同一根弹簧的收敛时间（到 0.1% 误差）+ 两帧余量推导。
 * 推导比实际观感略长是安全的——窗口偏长只意味着「转场结束后多看几帧」，窗口偏短则会让
 * `navigationActive` 提前变 false，页面还在动就把聊天帧时钟冻住。
 */
private fun navigationWindowNs(slideSpec: FiniteAnimationSpec<IntOffset>): Long =
    (EtaMotion.springSettleMillis(slideSpec) + EtaMotion.FrameMarginMillis) * 1_000_000L

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
 * 时长改由官方 `MotionScheme` 提供：位移取 `defaultSpatialSpec`（空间弹簧），
 * 透明度取 `defaultEffectsSpec`——两条通道的动力学本来就不同，用同一个 tween 会显得「一起刹车」。
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
    private val windowNs: Long,
) {
    constructor(
        rtl: Boolean,
        slideSpec: FiniteAnimationSpec<IntOffset>,
        fadeSpec: FiniteAnimationSpec<Float>,
        tracker: ChatNavigationActivityTracker,
    ) : this(
        enterTransition = {
            slideInHorizontally(animationSpec = slideSpec) { fullWidth ->
                if (rtl) -fullWidth else fullWidth
            }
        },
        exitTransition = {
            slideOutHorizontally(animationSpec = slideSpec) { fullWidth ->
                if (rtl) fullWidth / 4 else -fullWidth / 4
            } + fadeOut(
                animationSpec = fadeSpec,
                targetAlpha = 0.9f,
            )
        },
        popEnterTransition = {
            slideInHorizontally(animationSpec = slideSpec) { fullWidth ->
                if (rtl) fullWidth / 4 else -fullWidth / 4
            } + fadeIn(
                animationSpec = fadeSpec,
                initialAlpha = 0.9f,
            )
        },
        popExitTransition = {
            slideOutHorizontally(animationSpec = slideSpec) { fullWidth ->
                if (rtl) -fullWidth else fullWidth
            }
        },
        predictivePopEnterTransition = { _ ->
            slideInHorizontally(animationSpec = slideSpec) { fullWidth ->
                if (rtl) fullWidth / 4 else -fullWidth / 4
            } + fadeIn(
                animationSpec = fadeSpec,
                initialAlpha = 0.9f,
            )
        },
        predictivePopExitTransition = { _ ->
            slideOutHorizontally(animationSpec = slideSpec) { fullWidth ->
                if (rtl) -fullWidth else fullWidth
            }
        },
        tracker = tracker,
        windowNs = navigationWindowNs(slideSpec),
    )

    fun cancel() = tracker.cancel()

    /**
     * 一次导航的转场期间逐帧标记活跃；采样结束后由 tracker 的过期看门狗把它收敛成不活跃。
     */
    suspend fun sampleWhileAnimating(windowNs: Long = this.windowNs) {
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
    val motionScheme = MaterialTheme.motionScheme
    // 位移与透明度各自的官方规格：空间弹簧负责位移，effect 弹簧负责淡入淡出。
    val slideSpec: FiniteAnimationSpec<IntOffset> = motionScheme.defaultSpatialSpec()
    val fadeSpec: FiniteAnimationSpec<Float> = motionScheme.defaultEffectsSpec()
    val publish = rememberUpdatedState(onActivityChanged)
    val tracker = remember { ChatNavigationActivityTracker(onChanged = { active -> publish.value(active) }) }
    return remember(rtl, slideSpec, fadeSpec) {
        ChatNavigationActivityTransition(rtl, slideSpec, fadeSpec, tracker)
    }
}
