// 共享屏幕后端：单次截屏前台服务（取码管理页手动「截屏识别」行专用）。
// 用户经 MediaProjectionManager.createScreenCaptureIntent 授权后启动本服务；
// VirtualDisplay + ImageReader 取到第一帧即释放投影并停止，不常驻。
// Android 14+ 起 projection token 单次有效，每次截屏都要重新走授权弹窗。
// 授权结果必须由 Activity 接收（ActivityResult API），助手浮窗的服务路径
// 无法完成授权握手，因此本后端不进「屏幕截图方式」的下拉选项。

package io.github.mangi.eta.agent.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import io.github.mangi.eta.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AssistantProjectionService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: android.media.ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultData = intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())

        val manager = getSystemService(MediaProjectionManager::class.java)
        val current = runCatching { manager.getMediaProjection(intent.resultCodeFallback(), resultData) }
            .getOrNull()
        if (current == null) {
            publishResult(null)
            stopSelf()
            return START_NOT_STICKY
        }
        projection = current
        current.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    cleanup()
                }
            },
            mainHandler,
        )
        startCapture(current)
        return START_NOT_STICKY
    }

    private fun startCapture(current: MediaProjection) {
        val metrics = resources.displayMetrics
        val windowManager = getSystemService(WindowManager::class.java)
        val realMetrics = DisplayMetrics().also { rect ->
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(rect)
        }
        val width = realMetrics.widthPixels
        val height = realMetrics.heightPixels
        val dpi = metrics.densityDpi
        val reader = android.media.ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ listener ->
            val image = runCatching { listener.acquireLatestImage() }.getOrNull()
            if (image == null) return@setOnImageAvailableListener
            val bitmap = runCatching { image.toBitmap() }.getOrNull()
            image.close()
            if (bitmap != null) {
                // 取到首帧即收：撤掉监听防止后续帧重复投递。
                reader.setOnImageAvailableListener(null, null)
                publishResult(bitmap)
                stopSelf()
            }
        }, mainHandler)
        virtualDisplay = current.createVirtualDisplay(
            "eta_assistant_capture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler,
        )
        // ImageReader 首帧到达有延迟；超时兜底避免投影挂死。
        mainHandler.postDelayed({ finishWithoutFrame() }, CAPTURE_TIMEOUT_MS)
    }

    private fun android.media.Image.toBitmap(): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    private fun finishWithoutFrame() {
        if (lastResult.value != null) return
        publishResult(null)
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
    }

    /** MediaProjectionManager.getMediaProjection 的 resultCode 从 intent 里取。 */
    private fun Intent.resultCodeFallback(): Int =
        getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_OK)

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.pickup_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.pickup_projection_notification_title))
            .setOngoing(true)
            .build()
    }

    private fun publishResult(bitmap: Bitmap?) {
        lastResult.value = bitmap
    }

    companion object {
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RESULT_CODE = "result_code"
        private const val CHANNEL_ID = "pickup_projection"
        private const val NOTIFICATION_ID = 1301
        private const val CAPTURE_TIMEOUT_MS = 3_000L

        /** 最近一次截屏结果；null = 尚未完成。由调用方消费后 [clearResult] 清位。 */
        private val lastResult = MutableStateFlow<Bitmap?>(null)

        fun resultFlow(): StateFlow<Bitmap?> = lastResult

        fun clearResult() {
            lastResult.value?.recycle()
            lastResult.value = null
        }

        /** 发起单次共享屏幕截屏。调用方须先拿到用户授权的 resultData。 */
        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, AssistantProjectionService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            context.startForegroundService(intent)
        }
    }
}
