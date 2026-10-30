package io.github.mangi.eta.agent.voice.doubao

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.voice.VoiceDiagnostics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface VoicePreviewPlayer {
    fun prepare(url: String, ready: () -> Unit, completed: () -> Unit,
        error: (Int, Int) -> Unit, buffering: (Int) -> Unit)
    fun start()
    fun position(): Int
    fun duration(): Int
    fun release()
}

internal class AndroidVoicePreviewPlayer : VoicePreviewPlayer {
    private val player = MediaPlayer()
    override fun prepare(url: String, ready: () -> Unit, completed: () -> Unit,
        error: (Int, Int) -> Unit, buffering: (Int) -> Unit) {
        player.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        player.setOnPreparedListener { ready() }
        player.setOnCompletionListener { completed() }
        player.setOnErrorListener { _, what, extra -> error(what, extra); true }
        player.setOnBufferingUpdateListener { _, percent -> buffering(percent) }
        player.setDataSource(url)
        player.prepareAsync()
    }
    override fun start() = player.start()
    override fun position() = player.currentPosition
    override fun duration() = player.duration
    override fun release() {
        player.setOnPreparedListener(null)
        player.setOnCompletionListener(null)
        player.setOnErrorListener(null)
        player.setOnBufferingUpdateListener(null)
        player.release()
    }
}

/** Main-thread owner; stale callbacks can neither start playback nor clear a newer selection. */
internal class PersonalVoicePreview(
    private val factory: () -> VoicePreviewPlayer = { AndroidVoicePreviewPlayer() },
    private val traceFactory: () -> VoiceDiagnostics = { VoiceDiagnostics("personal-preview") },
    private val after: (Long, () -> Unit) -> (() -> Unit) = { delayMs, action ->
        val handler = Handler(Looper.getMainLooper())
        val task = Runnable { action() }
        handler.postDelayed(task, delayMs)
        val cancel: () -> Unit = { handler.removeCallbacks(task) }
        cancel
    },
) {
    data class State(val account: String = "", val id: String = "", val loading: Boolean = false, val error: String? = null) {
        val active get() = id.isNotEmpty()
        fun matches(account: String, id: String) = active && this.account == account && this.id == id
    }
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var player: VoicePreviewPlayer? = null
    private var trace: VoiceDiagnostics? = null
    private var generation = 0
    private var draining = false
    private var cancelDrain: (() -> Unit)? = null

    internal companion object {
        // Some Android output paths report completion before their queued tail is audible.
        // This is a bounded release grace period, not a seek/replay or a substitute for missing audio.
        fun drainDelayMs(duration: Int, position: Int): Long {
            val remaining = if (duration > 0 && position >= 0) (duration.toLong() - position).coerceIn(0L, 2000L) else 0L
            return remaining + 150L
        }
    }

    fun toggle(account: String, id: String, url: String) {
        if (mutable.value.matches(account, id)) { stop(1); return }
        stop(2)
        if (!url.startsWith("https://")) return
        val token = ++generation
        val diagnostic = traceFactory()
        trace = diagnostic
        mutable.value = State(account, id, loading = true)
        diagnostic.mark("preview.prepare")
        try {
            val owned = factory()
            player = owned
            owned.prepare(url,
                ready = {
                    if (token == generation && player === owned) {
                        diagnostic.mark("preview.ready", "durationMs" to safeDuration(owned))
                        try {
                            owned.start()
                            if (token == generation && player === owned) {
                                mutable.value = State(account, id)
                                diagnostic.mark("preview.start")
                            }
                        } catch (_: Exception) { fail(token, -2, 0) }
                    }
                },
                completed = {
                    if (token == generation && player === owned) {
                        val duration = safeDuration(owned)
                        val position = safePosition(owned)
                        diagnostic.mark("preview.complete", "durationMs" to duration, "positionMs" to position,
                            "early" to if (duration > 0 && position >= 0 && duration - position > 500) 1 else 0)
                        if (!draining) {
                            draining = true
                            val delayMs = drainDelayMs(duration, position)
                            diagnostic.mark("preview.drain", "delayMs" to delayMs)
                            cancelDrain = after(delayMs) {
                                if (token == generation && player === owned) {
                                    diagnostic.mark("preview.drained", "positionMs" to safePosition(owned))
                                    stop(3)
                                }
                            }
                        }
                    }
                },
                error = { what, extra -> fail(token, what, extra) },
                buffering = { percent ->
                    if (token == generation) diagnostic.mark("preview.buffer", "percent" to percent, sampled = true)
                },
            )
        } catch (_: Exception) { fail(token, -1, 0) }
    }

    private fun fail(token: Int, what: Int, extra: Int) {
        if (token != generation) return
        trace?.mark("preview.error", "what" to what, "extra" to extra)
        stop(4)
        mutable.value = State(error = "试听播放失败，请查询音色状态后重试")
    }

    // 0 leave screen; 1 explicit stop; 2 switch voice; 3 completion; 4 error; 5 remove voice.
    fun stop(reason: Int = 0) {
        generation++
        cancelDrain?.invoke()
        cancelDrain = null
        draining = false
        val owned = player
        player = null
        if (owned != null) {
            trace?.mark("preview.stop", "reason" to reason,
                "positionMs" to safePosition(owned), "durationMs" to safeDuration(owned))
            runCatching { owned.release() }
        }
        trace?.finish()
        trace = null
        mutable.value = State()
    }
    private fun safePosition(player: VoicePreviewPlayer) = runCatching { player.position() }.getOrDefault(-1)
    private fun safeDuration(player: VoicePreviewPlayer) = runCatching { player.duration() }.getOrDefault(-1)
}
