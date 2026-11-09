package io.github.mangi.eta.ui.components

import androidx.compose.foundation.lazy.LazyLayoutScrollScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * 一次连续的跳转滚动：上一条/下一条用户消息、长按跳到会话开头/末尾、回到底部精修。
 *
 * 总时长由 [ConversationTurnMotion] 钳住，速度不设上限；距离越远推进越快，
 * 时间一到直接 snap 到目标，超长会话也不会出现「滚十几秒」的动画。
 */
internal suspend fun LazyListState.animateToConversationTurn(targetIndex: Int) {
    require(targetIndex >= 0)
    scroll {
        val lazyScope = LazyLayoutScrollScope(this@animateToConversationTurn, this)
        if (lazyScope.itemCount == 0) return@scroll
        val target = targetIndex.coerceAtMost(lazyScope.itemCount - 1)
        val forward = target > firstVisibleItemIndex
        val sign = if (forward) 1f else -1f
        val motion = ConversationTurnMotion(layoutInfo.viewportSize.height.toFloat())
        // 只累计 scrollBy 真正消费掉的像素：估算高度滞后时欠消费不会让预算虚高。
        var travelledPx = 0f
        var previousFrame = withFrameNanos { it }
        // 完全无进度的连续帧数：测量被钳住或滚动标志陈旧时 scrollBy 会一直返回 0。
        // 「零进度 3 帧」才是停住；欠消费但仍在推进的帧不算（那是估算高度滞后，会自愈）。
        var edgeStreak = 0
        while (true) {
            val visible = layoutInfo.visibleItemsInfo.any { it.index == target }
            // Only an actually measured target may trigger deceleration. Do not settle at
            // estimated distances based on the heights of intermediate supplement bubbles.
            val remaining = if (visible) lazyScope.calculateDistanceTo(target).toFloat() else null
            if (remaining != null && (abs(remaining) <= 0.5f || remaining * sign < 0f)) {
                lazyScope.snapToItem(target)
                break
            }
            val frame = withFrameNanos { it }
            val elapsed = ((frame - previousFrame) / 1_000_000_000f)
                .coerceIn(0f, ConversationTurnMaxFrameSeconds)
            previousFrame = frame
            // 目标还没量到时按可见项平均高度粗估剩余；一旦量到，精确值接管预算重算。
            val distance = remaining?.let(::abs) ?: estimateDistanceToTarget(target)
            val step = motion.advance(elapsed, travelledPx, distance)
            if (step > 0f) {
                val delta = sign * step
                val consumed = scrollBy(delta)
                travelledPx += abs(consumed)
                // A small target may become visible and be crossed in this very measurement.
                // Correct before drawing this frame, never start a second reverse animation.
                val crossed = if (forward) firstVisibleItemIndex >= target else firstVisibleItemIndex < target
                if (crossed || (remaining != null && step >= abs(remaining))) {
                    lazyScope.snapToItem(target)
                    break
                }
                // 停滞判定只看「零进度」，不看欠消费：消费不足一帧只是估算高度还没校准，
                // 列报仍在推进时下一帧就恢复了，把这种帧数进放弃计数会让「跳到下一条
                // 用户消息 / 回到底部」停在半路任意位置（只滚一小截）。完全不动才是停住。
                edgeStreak = if (abs(consumed) < 0.5f) edgeStreak + 1 else 0
                if (edgeStreak >= 3) {
                    // 停住后用布局而不是消费量分辨两种情况：目标一侧贴住边界 = 真到底/到顶，
                    // 放弃即可；仍有越界余量 = 滚动被钳住（滚动标志陈旧等），scrollBy 会一直
                    // 返回 0，必须直接 snapToItem 定位到目标，否则按钮表现为原地不动或停半路。
                    if (!hasClampedScrollReserve(forward)) break // Actual list edge.
                    lazyScope.snapToItem(target)
                    break
                }
            }
            // 最后一帧先滚完再 snap；时间预算用尽一律收尾，不许动画随距离继续拉长。
            if (motion.budgetExhausted()) {
                lazyScope.snapToItem(target)
                break
            }
        }
    }
}

/**
 * 目标 item 尚未进入可见范围时的粗估剩余距离。只用布局里已经量到的项，
 * 不猜内容高度；误差会被目标可见后的精确 remaining 与总时长硬截止一起修正。
 */
private fun LazyListState.estimateDistanceToTarget(target: Int): Float {
    val info = layoutInfo
    val items = info.visibleItemsInfo
    val viewport = info.viewportSize.height.toFloat().coerceAtLeast(1f)
    if (items.isEmpty()) return viewport
    val averageItemPx = (items.sumOf { it.size }.toFloat() / items.size).coerceAtLeast(1f)
    val indexDelta = abs(target - firstVisibleItemIndex)
    return (indexDelta * averageItemPx - firstVisibleItemScrollOffset).coerceAtLeast(averageItemPx)
}

/** 滚动被钳位时布局侧仍有的越界余量：正向看尾部越静止线，反向看头部越起点线。 */
internal fun LazyListState.hasClampedScrollReserve(forward: Boolean): Boolean {
    val info = layoutInfo
    return if (forward) {
        val last = info.visibleItemsInfo.lastOrNull() ?: return false
        last.offset + last.size > info.viewportEndOffset - info.afterContentPadding + 1
    } else {
        val first = info.visibleItemsInfo.firstOrNull() ?: return false
        first.offset < info.viewportStartOffset - 1
    }
}
