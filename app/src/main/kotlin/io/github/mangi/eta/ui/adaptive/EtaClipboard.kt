package io.github.mangi.eta.ui.adaptive

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

/**
 * 「复制到剪贴板」统一入口：官方新版 [LocalClipboard] API 的薄封装。
 *
 * 旧的 `LocalClipboardManager.setText` 已废弃，新版 `Clipboard.setClipEntry` 是
 * suspend 函数；这里代管协程作用域，调用方拿到的仍是普通回调——替换旧写法时
 * 只改取用方式，不必各自套 launch 样板。
 */
@Composable
internal fun rememberCopyToClipboard(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { text: String ->
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("eta", text)))
        }
        Unit
    }
}
