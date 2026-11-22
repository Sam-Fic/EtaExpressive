// 系统助手屏幕识别的统一兜底截屏入口。
// root / Shizuku 后端移植自 badnng/Hyper-pick-up-code（AGPL-3.0-only）
//   com.Badnng.moe.helper.RootHelper / ShizukuScreenshotHelper（screencap -p 读原始字节流）。
// 合规登记见 docs/THIRD_PARTY_NOTICES.md「Hyper-pick-up-code」一节。

package io.github.mangi.eta.agent.capture

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.mangi.eta.agent.device.RootSu
import io.github.mangi.eta.config.Prefs
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * 无障碍截屏的兜底统一入口：按「屏幕截图方式」设置产出当前屏幕 Bitmap。
 * 调用方（入口浮窗的屏幕上下文采集）在自己的后台协程里调用；返回 null 表示
 * 所选后端不可用，调用方保持 UNAVAILABLE 态。
 */
internal object AssistantScreenshotCapture {
    private const val TAG = "AssistantScreenshotCapture"
    private const val CAPTURE_TIMEOUT_MS = 6_000L

    fun capture(): Bitmap? = when (Prefs.assistantScreenshotBackend()) {
        AssistantScreenshotBackend.ROOT -> captureRoot()
        AssistantScreenshotBackend.SHIZUKU -> captureShizuku()
        AssistantScreenshotBackend.AUTO -> captureRoot() ?: captureShizuku()
    }

    /** root 后端：`screencap -p` 经 RootSu 读原始 stdout 字节流。 */
    private fun captureRoot(): Bitmap? {
        if (!io.github.mangi.eta.agent.device.RootAccess.isGranted) return null
        return runCatching {
            RootSu.process("screencap -p").start().useProcess { process ->
                if (process.waitFor(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    BitmapFactory.decodeStream(process.inputStream)
                } else {
                    process.destroyForcibly()
                    Log.w(TAG, "root 截屏超时")
                    null
                }
            }
        }.onFailure { Log.w(TAG, "root 截屏失败", it) }
            .getOrNull()
    }

    /**
     * Shizuku 后端：`screencap -p` 经 Shizuku shell 特权进程执行。
     *
     * `Shizuku.newProcess` 在 shizuku-api 13.1.5 里是 private，须反射调用
     * （与澎湃记移植源 ShizukuScreenshotHelper 同一做法，返回的
     * ShizukuRemoteProcess 是 java.lang.Process 子类）。
     */
    private fun captureShizuku(): Bitmap? {
        if (!isShizukuAvailable()) return null
        return runCatching {
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            val process = newProcess.invoke(null, arrayOf("screencap", "-p"), null, null)
                as java.lang.Process
            process.useProcess { remote ->
                if (remote.waitFor(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    BitmapFactory.decodeStream(remote.inputStream)
                } else {
                    remote.destroyForcibly()
                    Log.w(TAG, "Shizuku 截屏超时")
                    null
                }
            }
        }.onFailure { Log.w(TAG, "Shizuku 截屏失败", it) }
            .getOrNull()
    }

    /** Shizuku 服务连通且已授予 shell 权限。 */
    fun isShizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Shizuku 服务是否可达（未授权也算可达，用于展示「去授权」入口）。 */
    fun isShizukuReachable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
}

/** 进程执行完关闭三条流（不依赖 Process 是否实现 AutoCloseable）。 */
private inline fun <T> Process.useProcess(block: (Process) -> T): T = try {
    block(this)
} finally {
    runCatching { inputStream.close() }
    runCatching { errorStream.close() }
    runCatching { outputStream.close() }
}
