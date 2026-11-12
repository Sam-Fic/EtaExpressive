package io.github.mangi.eta.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import io.github.mangi.eta.core.AndroidAgentLogger

/** 系统选择器透明桥接 Activity 读取的请求码（由 [EtaAssistantOverlayResultRegistry] 生成）。 */
internal const val EXTRA_REQUEST_CODE = "io.github.mangi.eta.agent.voice.extra.REQUEST_CODE"

/** 系统选择器透明桥接 Activity 读取的目标 Intent（真正的选择器 Intent）。 */
internal const val EXTRA_TARGET_INTENT = "io.github.mangi.eta.agent.voice.extra.TARGET_INTENT"

/**
 * 浮窗选择器的结果桥：Service 窗口不能 `startActivityForResult`，由自定义
 * [ActivityResultRegistry] 把结果转交给这里暂存的注册表，`rememberLauncherForActivityResult`
 * 再从注册表拿到回调。
 *
 * 注册表在浮窗生命周期内由 [EtaAssistantOverlayService] 设置 / 清除，避免持有过期窗口。
 */
internal object EtaAssistantOverlayPickerBridge {
    @Volatile
    var registry: ActivityResultRegistry? = null

    /**
     * 选择器会话结束信号：跳板收到结果（含取消）或被销毁时触发一次。服务用它从
     * 「让位穿透」状态恢复浮窗（详见 [EtaAssistantOverlayService.beginPickerYield]）。
     */
    @Volatile
    var sessionEndListener: (() -> Unit)? = null
}

/**
 * 透明、无界面的系统选择器跳板 Activity。
 *
 * `TYPE_APPLICATION_OVERLAY` 窗口没有可用的 `startActivityForResult`，这里用一个不带 UI 的
 * Activity 代为发起真正的选择器 Intent，收到结果后再转发给浮窗注册表并立即结束自己。
 * 不设 `noHistory`：配置变更（旋转）时系统会重建，这里直接结束以免残留半透明空页。
 */
internal class EtaAssistantOverlayPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 重建（如旋转）时不重放旧请求，直接结束。
        if (savedInstanceState != null) {
            finish()
            return
        }
        val requestCode = intent?.getIntExtra(EXTRA_REQUEST_CODE, -1) ?: -1
        val target = intent?.getParcelableExtra(EXTRA_TARGET_INTENT, Intent::class.java)
        if (requestCode < 0 || target == null) {
            finish()
            return
        }
        runCatching {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            startActivityForResult(target, requestCode)
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_picker_launch_failed") {
                "Eta assistant overlay picker startActivityForResult failed: " +
                    "type=${throwable.javaClass.simpleName}"
            }
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 先把结果派发给注册表（组合内的 launcher 回调），再发会话结束信号——
        // 服务据此把让位中的浮窗恢复回来，顺序保证恢复时附件已就位。
        EtaAssistantOverlayPickerBridge.registry?.dispatchResult(requestCode, resultCode, data)
        EtaAssistantOverlayPickerBridge.sessionEndListener?.invoke()
        runCatching {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        finish()
    }

    override fun onDestroy() {
        // 兜底：跳板没走到 onActivityResult（进程被杀等）也要让服务从让位态恢复。
        EtaAssistantOverlayPickerBridge.sessionEndListener?.invoke()
        super.onDestroy()
    }
}

/**
 * 浮窗（Service 窗口）使用的 [ActivityResultRegistry]：不依赖宿主的 Activity，
 * [onLaunch] 通过 [EtaAssistantOverlayPickerActivity] 跳板发起系统选择器。
 *
 * 同时实现 [ActivityResultRegistryOwner]，便于直接作为
 * `LocalActivityResultRegistryOwner` 提供给 Compose 树。
 */
internal class EtaAssistantOverlayResultRegistry(
    context: Context,
) : ActivityResultRegistry(), ActivityResultRegistryOwner {
    private val appContext = context.applicationContext

    override val activityResultRegistry: ActivityResultRegistry
        get() = this

    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        val launched = runCatching {
            val target = contract.createIntent(appContext, input)
            // 后台 Service 发起 Activity 可能被系统“后台启动 Activity”策略丢弃；
            // 这里尽力而为，失败仅记录，不额外构建 PendingIntent 机制。
            appContext.startActivity(
                Intent(appContext, EtaAssistantOverlayPickerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_REQUEST_CODE, requestCode)
                    .putExtra(EXTRA_TARGET_INTENT, target),
            )
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_picker_trampoline_failed") {
                "Eta assistant overlay picker trampoline launch failed: " +
                    "type=${throwable.javaClass.simpleName}"
            }
        }.isSuccess
        if (!launched) {
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_picker_dropped") {
                "Eta assistant overlay picker launch dropped (background activity start?)"
            }
        }
    }
}
