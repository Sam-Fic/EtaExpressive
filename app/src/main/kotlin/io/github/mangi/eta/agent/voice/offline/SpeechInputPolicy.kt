package io.github.mangi.eta.agent.voice.offline

internal object SpeechInputPolicy {
    fun visible(generating: Boolean, enabled: Boolean) = generating || enabled
    fun canEnable(ready: Boolean, checking: Boolean, downloading: Boolean) = ready && !checking && !downloading

    /**
     * 总时长从开麦时刻算起；「一直没检测到语音」的 8 秒从引擎就绪后算起，
     * 避免冷启动装载模型吃掉用户本就不多的说话时间。
     */
    fun timedOut(totalElapsedMs: Long, sinceReadyMs: Long, hasText: Boolean) =
        totalElapsedMs >= 60_000 || (!hasText && sinceReadyMs >= 8_000)
}
