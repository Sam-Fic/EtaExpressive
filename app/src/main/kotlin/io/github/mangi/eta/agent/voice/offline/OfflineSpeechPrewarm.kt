package io.github.mangi.eta.agent.voice.offline

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 把「离线包是否就绪」「语音对话是否使用本机识别」「运行策略」三路状态合流，
 * 决定是否预加载/保留本地模型。识别进行中不会强行卸载，交给缓存延迟释放。
 */
internal object OfflineSpeechPrewarm : ComponentCallbacks2 {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var installed = false
    private var lastPrewarmOnStart = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        OfflineSpeechRuntimeSettings.load(app)
        DoubaoVoiceConfig.load(app)
        app.registerComponentCallbacks(this)

        // 未开启启动预加载时不在每次启动都校验 237 MB 模型；真正说话时链路仍会初始化离线包。
        lastPrewarmOnStart = OfflineSpeechRuntimeSettings.state.value.prewarmOnStart
        if (lastPrewarmOnStart) OfflineSpeechPack.initialize(app)

        scope.launch {
            combine(
                OfflineSpeechPack.state,
                DoubaoVoiceConfig.state,
                OfflineSpeechRuntimeSettings.state,
            ) { pack, voice, runtime -> Triple(pack, voice, runtime) }
                .distinctUntilChanged()
                .collect { (pack, voice, runtime) ->
                    if (runtime.prewarmOnStart && !lastPrewarmOnStart) {
                        OfflineSpeechPack.initialize(app)
                    }
                    lastPrewarmOnStart = runtime.prewarmOnStart

                    OfflineSpeechSession.applyDemand(
                        app,
                        model = demand(pack, voice),
                        prewarm = runtime.prewarmOnStart,
                    )
                }
        }
    }

    override fun onTrimMemory(level: Int) {
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        ) {
            OfflineSpeechSession.invalidate("trim_memory_$level")
        }
    }

    /**
     * 识别结束或取消后重新评估需求：识别期间挂起的模型切换/失效（invalidate 只挂起）
     * 在这里补做预加载；不依赖下一次状态变化才恢复常驻。
     */
    fun refresh(context: Context) {
        val app = context.applicationContext
        OfflineSpeechSession.applyDemand(
            app,
            model = demand(OfflineSpeechPack.state.value, DoubaoVoiceConfig.state.value),
            prewarm = OfflineSpeechRuntimeSettings.state.value.prewarmOnStart,
        )
    }

    /**
     * 只有「校验完成且就绪」的模型才值得常驻；重新校验 / 下载 / 切换期间先释放，
     * 避免继续读取可能已被备份恢复替换的旧目录。旧听写开关 `enabled` 不参与判定。
     */
    private fun demand(
        pack: OfflineSpeechPack.State,
        voice: DoubaoVoiceConfig.Config,
    ): SpeechModelCatalog.Model? =
        pack.model.takeIf {
            pack.ready && !pack.checking && !pack.downloading &&
                voice.conversationEnabled && !voice.conversationCloudAsr
        }

    override fun onLowMemory() {
        OfflineSpeechSession.invalidate("low_memory")
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit
}
