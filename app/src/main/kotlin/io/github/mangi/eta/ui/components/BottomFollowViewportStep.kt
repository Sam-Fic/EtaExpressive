package io.github.mangi.eta.ui.components

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Preserve an existing row's pre-expansion top from fresh layout evidence. If that key is not
 * measured yet, a measured row known to precede it gives only a LOWER bound on the displacement.
 * No preceding evidence means no scroll; null is never converted into a guessed tail distance.
 */
internal fun resolveWorkExpansionViewportStep(
    anchorOffsetPx: Int,
    measuredAnchorOffsetPx: Int?,
    measuredPrecedingBottomPx: Int?,
): Float {
    val observed = measuredAnchorOffsetPx ?: measuredPrecedingBottomPx ?: return 0f
    return (observed.toLong() - anchorOffsetPx.toLong()).coerceAtLeast(0L).toFloat()
}

/**
 * A queued follow target predates post-layout expansion recovery. Cap the actual scroll by
 * fresh measured overflow so the two owners cannot consume the same distance twice. Unknown
 * tail geometry retains the existing controller fallback unless an explicit expansion owner
 * is recovering it from measured rows. It never invents another target here.
 */
internal fun resolveFollowScrollStepAfterRecovery(
    plannedStepPx: Float,
    measuredOverflowPx: Int?,
    expansionOwnsViewport: Boolean = false,
): Float {
    if (!plannedStepPx.isFinite() || plannedStepPx <= 0f) return 0f
    val overflow = measuredOverflowPx ?: return if (expansionOwnsViewport) 0f else plannedStepPx
    return min(plannedStepPx, overflow.coerceAtLeast(0).toFloat())
}

/** Authorization for the bounded explicit expansion only; not a general idle follow mode. */
internal fun resolveWorkExpansionViewportOwnership(
    keepBottomAnchored: Boolean,
    initialBottomPositionPending: Boolean,
    pointerDown: Boolean,
    isUserDragging: Boolean,
    isUserScrolling: Boolean,
    navigationActive: Boolean,
): Boolean = keepBottomAnchored && !initialBottomPositionPending && !pointerDown &&
    !isUserDragging && !isUserScrolling && !navigationActive

/**
 * 严格贴底的滚动目标：以本帧实测的尾部差额为唯一依据，一次滚满，不留跨帧残量。
 *
 * 旧实现把队列目标交给加速度曲线逐帧消化，快速输出时滚动常年落后一截，只能靠绘制层
 * 上提掩盖；上提累积超过尾部留白就会整列掉下去，也就是肉眼看到的「列表在追底部」。
 * 现在实测差额优先，只有尾部暂时测不到（哨兵被挤出测量窗口）才沿用队列里的整屏估算。
 * 实测为 0 表示已经贴住，返回 0 让控制器回到等待下一次布局变化。
 */
internal fun resolveStrictBottomFollowTarget(
    queuedPx: Float,
    measuredOverflowPx: Int?,
): Float = measuredOverflowPx?.coerceAtLeast(0)?.toFloat()
    ?: queuedPx.coerceAtLeast(0f)

/**
 * Resolves how far the auto-follow controller should scroll the chat viewport for a single step.
 *
 * The controller already glides the viewport by [smoothStepPx]. That smooth motion is sized for
 * the drawn content, but when the drawn tail overflows the real draw buffer the smooth step can be
 * far smaller than the true deficit, so the very bottom stays clipped for many frames. This helper
 * only tops the smooth step up to the part of the measured overflow that the after-content padding
 * buffer cannot absorb. Whatever still fits inside the buffer keeps moving smoothly.
 *
 * The forced top-up is itself capped by [maxCatchUpPx]: with the default of "unbounded" the whole
 * deficit is repaid in a single step (the historic one-frame jump), while callers that pass a
 * per-frame budget turn the catch-up into a smooth glide across several frames. The plain smooth
 * step is never throttled by that cap.
 *
 * The result never exceeds the measured overflow, so a caller that keeps its existing bounds checks
 * cannot be asked to scroll past the real deficit.
 *
 * This is a pure function: it performs no scrolling, does not touch velocity and keeps no state.
 * Gesture / navigation / should-follow guards stay with the caller.
 *
 * @param smoothStepPx the normal per-frame smooth follow distance in px. Negative values contribute
 *   no movement.
 * @param measuredOverflowPx the measured tail overflow past the viewport in px, or `null` when the
 *   exact tail is not known yet. When unknown we deliberately do not invent an estimated jump and
 *   only return the normal smooth step.
 * @param afterContentPaddingPx the padding drawn after the content, i.e. the draw buffer that can
 *   hold overflow without visible clipping. Zero or negative values contribute no budget.
 * @param maxCatchUpPx per-frame cap for the forced compensation in px; non-positive values disable
 *   the compensation entirely (smooth motion only), non-finite values keep it unbounded.
 * @return the distance in px to scroll this step; never negative and never larger than the measured
 *   overflow.
 */
internal fun resolveBottomFollowViewportStep(
    smoothStepPx: Float,
    measuredOverflowPx: Int?,
    afterContentPaddingPx: Int,
    maxCatchUpPx: Float = Float.MAX_VALUE,
): Float {
    val smooth = max(smoothStepPx, 0f)
    val overflow = measuredOverflowPx ?: return smooth
    val distance = max(overflow, 0)
    val budget = max(afterContentPaddingPx, 0)
    val requiredCompensation = max(distance - budget, 0).toFloat()
    val catchUp = if (maxCatchUpPx.isFinite()) max(maxCatchUpPx, 0f) else Float.MAX_VALUE
    val compensation = min(requiredCompensation, catchUp)
    return min(distance.toFloat(), max(smooth, compensation))
}

/**
 * 单帧追赶预算：内容一次性长高超出绘制缓冲时，强制补偿不再一帧滚完，
 * 而是按固定速度逐帧补齐，让视口平滑追上而不是瞬跳。
 *
 * 帧间隔按 32ms 封顶，与 [BottomFollowMotion] 同一约定：掉帧或长时间静止后
 * 不会把积压的时间一次性补成一大段跳动。速度随 density 缩放，与列表内其余
 * 跟底速度保持同一量纲。
 */
private const val FollowCatchUpSpeedPxPerSecond = 1600f

internal fun followCatchUpBudgetPx(elapsedSeconds: Float, density: Float): Float {
    if (!density.isFinite() || density <= 0f) return 0f
    val dt = elapsedSeconds.coerceIn(0f, 0.032f)
    return FollowCatchUpSpeedPxPerSecond * density * dt
}

/**
 * LazyList 的滚动偏移是整数像素，平滑步长却是小数。
 * 小数滚动和绘制上提用的整数超出量对不齐时，卡片底边会来回跳大约 1 像素。
 * 这里只滚动整像素；不足 1 像素但确实还有距离时前进 1 像素，避免停在描边外侧。
 */
internal fun snapFollowScrollStep(stepPx: Float, remainingPx: Float): Float {
    if (!stepPx.isFinite() || !remainingPx.isFinite() || stepPx <= 0f || remainingPx <= 0f) return 0f
    val whole = stepPx.roundToInt().toFloat().coerceIn(0f, remainingPx)
    return if (whole == 0f && remainingPx >= 1f) 1f else whole
}
