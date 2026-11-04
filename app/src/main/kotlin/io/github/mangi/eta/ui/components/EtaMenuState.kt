package io.github.mangi.eta.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 锚点按钮 + 弹层的展开状态。
 *
 * 只保留开关状态与「刚关就点」的去抖守卫；弹层本身统一走官方 EtaMenuPopup
 * （Material 3 Expressive 的 DropdownMenuPopup），不再自带定位与进出场动画。
 */
@Stable
internal class EtaMenuState {
    var expanded by mutableStateOf(false)
        private set

    private var lastDismissUptimeMs = 0L

    fun onAnchorClick() {
        if (expanded) {
            dismiss()
            return
        }
        if (SystemClock.uptimeMillis() - lastDismissUptimeMs < MenuDismissGuardMs) {
            return
        }
        expanded = true
    }

    fun dismiss() {
        if (!expanded) return
        expanded = false
        lastDismissUptimeMs = SystemClock.uptimeMillis()
    }

    private companion object {
        /** 关闭弹层后 80ms 内的重复点击忽略：避免点击穿透把菜单立刻又打开。 */
        const val MenuDismissGuardMs = 80L
    }
}

@Composable
internal fun rememberEtaMenuState(): EtaMenuState = remember { EtaMenuState() }
