package io.github.mangi.eta.ui.adaptive

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import io.github.mangi.eta.ui.components.ContainedMorphLoadingIndicator
import kotlinx.coroutines.delay

/**
 * 加载 / 进度指示器的统一入口。
 *
 * 唯一判据是 [progress]：
 * - `null`：不确定型，走 M3E [LoadingIndicator]（需要圆底时走 contained morph）；
 * - 非 null：确定型，走 M3E Expressive 的 wavy 变体。
 *
 * 调用点不要再自行决定用 `LoadingIndicator` 还是 `CircularProgressIndicator`。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EtaLoadingSlot(
    progress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = EtaDimens.StatusIconSize,
    contained: Boolean = false,
) {
    if (progress != null) {
        CircularWavyProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier.size(size),
        )
    } else if (contained) {
        ContainedMorphLoadingIndicator(
            modifier = modifier,
            indicatorSize = size,
        )
    } else {
        LoadingIndicator(modifier = modifier.size(size))
    }
}

/**
 * 行内确定型进度条：M3E Expressive [LinearWavyProgressIndicator]。
 *
 * 只接受确定进度；没有分母的场景请改用 [EtaLoadingSlot] 的不确定型分支，
 * 不要画一条永远在跑的假进度。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EtaProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    LinearWavyProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * 短操作加载门控（状态版）：返回当前是否应该显示指示器。
 *
 * 可见后先等 [EtaMotion.LoadingShowDelayMillis]，真正慢的操作才显示；
 * 一旦显示，至少保留 [EtaMotion.LoadingMinVisibleMillis]，避免闪一下又消失。
 * 需要在「加载中」与「空态」之间切换时用它，避免指示器与空态同时出现。
 *
 * [failVisible] 为 true 时视为操作已失败：跳过最短可见滞留立即收起指示器，
 * 让错误反馈马上顶上（400ms 的「还在转」错觉会盖过错误本身）。
 */
@Composable
fun rememberEtaLoadingShown(
    visible: Boolean,
    showAfterMillis: Long = EtaMotion.LoadingShowDelayMillis,
    minVisibleMillis: Long = EtaMotion.LoadingMinVisibleMillis,
    failVisible: Boolean = false,
): Boolean {
    var shown by remember { mutableStateOf(false) }
    var shownAtMillis by remember { mutableLongStateOf(0L) }

    LaunchedEffect(visible, failVisible) {
        if (visible) {
            delay(showAfterMillis)
            shownAtMillis = SystemClock.uptimeMillis()
            shown = true
            // 保持窗口由下一次 visible=false 的分支消费；这里只负责把“已显示”撑满。
            delay(minVisibleMillis)
        } else if (shown) {
            // 失败旁路：失败态不等最短可见时长，指示器立即收起。
            if (!failVisible) {
                val elapsed = SystemClock.uptimeMillis() - shownAtMillis
                if (elapsed < minVisibleMillis) {
                    delay(minVisibleMillis - elapsed)
                }
            }
            shown = false
        }
    }

    return shown
}

/**
 * 短操作加载门控：可见后先等 [EtaMotion.LoadingShowDelayMillis]，真正慢的操作才显示；
 * 一旦显示，至少保留 [EtaMotion.LoadingMinVisibleMillis]，避免闪一下又消失。
 *
 * 适用于本地图片解码、本地目录/统计首载这类“通常几十毫秒”的操作。
 * [failVisible] 的失败旁路语义同 [rememberEtaLoadingShown]。
 */
@Composable
fun EtaLoadingGate(
    visible: Boolean,
    showAfterMillis: Long = EtaMotion.LoadingShowDelayMillis,
    minVisibleMillis: Long = EtaMotion.LoadingMinVisibleMillis,
    failVisible: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (rememberEtaLoadingShown(visible, showAfterMillis, minVisibleMillis, failVisible)) {
        content()
    }
}
