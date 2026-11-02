package io.github.mangi.eta.agent.voice

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.agent.voice.tts.SpeechOutcome
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.agent.voice.tts.SpeechPlaybackListener
import io.github.mangi.eta.agent.voice.tts.SpeechPlaybackProfile
import io.github.mangi.eta.agent.voice.tts.SpeechSpeakableText
import io.github.mangi.eta.config.ReplyReadAloudPreference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 一条待朗读句子，连同它所属气泡的真实 id：owner 用真实 id，朗读进度按归一化段推进。 */
internal data class ReadAloudUtterance(val messageId: String, val text: String)

/**
 * 一次快照的朗读决策。播放侧只认这份计划，不再回读 [ReplyReadAloudTracker] 的内部状态，
 * 这样「该读什么」和「怎么排播」可以各自演化、各自单测。
 *
 * @param messageId 本批句子所属气泡的真实 id；null 表示本批没有归属。
 * @param segmentChanged 相对上一次派发换段了（工具调用之后模型另起一段文本）。
 * @param flush 丢弃队列里不属于 [messageId] 那一段的待朗读句子。
 * @param stop 立即收声：离开本会话，或用户按下了停止。
 */
internal data class ReadAloudPlan(
    val messageId: String?,
    val segmentChanged: Boolean = false,
    val flush: Boolean = false,
    val stop: Boolean = false,
    val utterances: List<ReadAloudUtterance> = emptyList(),
) {
    internal companion object {
        /** 当前批次作废：队列里上一段的剩余句子一并丢掉。 */
        internal fun dropped(messageId: String?, stop: Boolean = false) =
            ReadAloudPlan(messageId, segmentChanged = true, flush = true, stop = stop)
    }
}

/** 完全相同的快照才会命中短路：任何一项变了都要重新判断有没有新的整句。 */
private data class ReadAloudSnapshotKey(
    val conversationId: String?,
    val segment: String?,
    val textLength: Int,
    val streaming: Boolean,
    val streamSynthesis: Boolean,
    val runId: String?,
)

/**
 * 回复自动朗读的纯决策逻辑：只依赖 [VoiceChatSnapshot] 与开关，不接触 Android 组件，
 * 单测可直接驱动。调用方每收到一次聊天状态快照就调一次 [onSnapshot]，返回本次的朗读计划。
 *
 * 四条规则：
 * - 只有本次会话里现场生成的回复才自动朗读：打开历史会话、切换会话都不重读旧回复；
 * - 换段即抢读：新一轮文本段出现后，上一段没读的句子整批作废，朗读者永远盯着最新那段；
 * - 同一段只读一次，进度按「已派句数」推进，流式中丢帧的快照不会重复朗读；
 * - 中途接入（切进一个正在生成的会话）与用户停止，都静默到这一轮结束。
 */
internal class ReplyReadAloudTracker(
    /** 分句注入点：生产用 [SpeechSpeakableText.committedSentences]，单测用假实现以统计解析次数。 */
    private val sentencesOf: (markdown: String, finalized: Boolean) -> List<String> =
        SpeechSpeakableText::committedSentences,
) {

    /** 出现过进行中的回复（`isStreaming = true`）：区分「刚生成的回复」与「历史消息」。 */
    var armed: Boolean = false
        private set

    /**
     * 运行开始前就存在的最后一条回复。新运行的头几个快照还在盯着它（思考阶段尚无新回复），
     * 不能因为它出现在流式快照里就把旧回复读一遍。
     */
    var baselineSegment: String? = null
        private set

    /** 当前正在跟踪的段身份，以及它最近一次的真实气泡 id。 */
    var currentSegment: String? = null
        private set
    var currentId: String? = null
        private set

    /** 已朗读的句子数（按段累计）。 */
    var spokenCount: Int = 0
        private set

    /** 已读完（或确认无可朗读内容）的段。 */
    var finalizedSegment: String? = null
        private set

    /** 正在跟踪的会话与运行；null = 调用方没提供，退回只看流式位的旧判定。 */
    var conversationId: String? = null
        private set
    var runId: String? = null
        private set

    /** markdown 解析次数，仅供单测钉住短路与成本。 */
    var sentenceParseCount: Int = 0
        private set

    /** 被放弃的那一轮：切走会话、或半截接入。换轮才解除，切回来也不续读。 */
    private var abandonedRunId: String? = null

    /** 没有 runId 可用时的兜底：静默到本轮结束。 */
    private var abandonedUntilFinalized = false

    /** 用户点了停止：本轮剩余段全部静默，直到下一次武装（即下一轮开始）。 */
    private var suppressUntilArmed = false

    /** 上一次派发给播放层的段身份，用来判断「换段了」。 */
    private var dispatchedSegment: String? = null

    private var lastKey: ReadAloudSnapshotKey? = null

    fun onSnapshot(snapshot: VoiceChatSnapshot, streamSynthesis: Boolean): ReadAloudPlan {
        val id = snapshot.lastAgentId
        val segment = AssistantMessageId.segmentKey(id)
        val key = ReadAloudSnapshotKey(
            conversationId = snapshot.conversationId,
            segment = segment,
            textLength = snapshot.lastAgentText.length,
            streaming = snapshot.isStreaming,
            streamSynthesis = streamSynthesis,
            runId = snapshot.runId,
        )
        // 同一个快照被重复喂进来（丢帧合并、浮窗与应用盯着同一状态）：连 markdown 都不必再解析。
        if (key == lastKey) return ReadAloudPlan(id)

        gateConversation(snapshot)?.let { return it }

        val finalized = !snapshot.isStreaming
        observeRun(snapshot)
        if (!finalized) {
            if (!armed) {
                // 运行刚开始：此刻的最后一条回复是运行前就存在的，记为基线不读。
                armed = true
                baselineSegment = segment
                // 上一轮用户按过停止，到这一轮才解除静默。
                suppressUntilArmed = false
            }
        } else if (!armed) {
            // 没有进行中的运行：这是打开历史会话 / 切换会话带来的旧回复，不重读。
            lastKey = key
            return ReadAloudPlan(id)
        }
        // 运行结束的这一刻解除武装，避免空回复把武装状态泄漏到下一次会话切换。
        if (finalized) {
            armed = false
            abandonedUntilFinalized = false
        }
        if (silent(snapshot)) {
            lastKey = key
            // 静默轮次的剩余句子一并作废，不能留在队列里等下一次唤醒时开口。
            return ReadAloudPlan.dropped(snapshot.lastAgentId, stop = true)
        }
        if (id.isNullOrBlank() || segment == finalizedSegment || segment == baselineSegment) {
            lastKey = key
            return ReadAloudPlan(id)
        }
        if (segment != currentSegment) {
            currentSegment = segment
            currentId = id
            spokenCount = 0
        }
        currentId = id
        val text = snapshot.lastAgentText
        if (text.isBlank()) {
            if (finalized) finalizedSegment = segment
            lastKey = key
            return ReadAloudPlan(id)
        }
        // 关闭流式合成时，只有回复全部结束才开口；开启时按已提交的整句朗读。
        // TODO(C1)：这一档下工具调用之前的段到结束时已被换段丢弃，整段不会朗读；
        // 补法是「段闭合即读整段」，与上面「换段即丢弃」语义相反，需把快照升级为本轮段列表，另立一期。
        if (!finalized && !streamSynthesis) {
            lastKey = key
            return ReadAloudPlan(id)
        }
        if (text.length > SpeechSpeakableText.MAX_SOURCE_CHARS) {
            // 与手动朗读一致：超长回复不自动朗读，交给用户手动分段。
            if (finalized) finalizedSegment = segment
            lastKey = key
            return ReadAloudPlan(id)
        }
        sentenceParseCount++
        val ready = runCatching { sentencesOf(text, finalized) }.getOrDefault(emptyList())
        val fresh = ready.drop(spokenCount)
        spokenCount = ready.size
        if (finalized) finalizedSegment = segment
        lastKey = key
        // 首段没有「上一段」可言：只有真的从旧段挪到新段才算换段。
        val changed = dispatchedSegment != null && dispatchedSegment != segment
        dispatchedSegment = segment
        return ReadAloudPlan(
            messageId = id,
            segmentChanged = changed,
            // 换段即放弃上一段还没读出去的句子：这是「跳到最新一段」的判定侧。
            flush = changed,
            utterances = fresh.map { ReadAloudUtterance(id, it) },
        )
    }

    fun reset() {
        armed = false
        baselineSegment = null
        currentSegment = null
        currentId = null
        spokenCount = 0
        finalizedSegment = null
        conversationId = null
        runId = null
        abandonedRunId = null
        abandonedUntilFinalized = false
        suppressUntilArmed = false
        dispatchedSegment = null
        lastKey = null
        sentenceParseCount = 0
    }

    /** 用户主动停止当前回复的自动朗读：这一轮的剩余段都不再开口。 */
    fun markCurrentFinalized() {
        val segment = currentSegment ?: return
        finalizedSegment = segment
        suppressUntilArmed = true
        lastKey = null
    }

    /** 离开当前会话：本轮就地放弃，切回来也不把半截读完。 */
    fun abandonCurrentRun() {
        currentSegment?.let { finalizedSegment = it }
        runId?.let { abandonedRunId = it }
        abandonedUntilFinalized = runId == null
        armed = false
        dispatchedSegment = null
        lastKey = null
    }

    /**
     * 会话门禁：自动朗读只跟着「武装自己的那一场会话」走。
     *
     * - 没提供 conversationId 的调用方（旧路径、单测）不参与门禁；
     * - 首次观测 / 草稿会话拿到真实 id：直接认领新标识，不清空已有进度；
     * - 离开还在朗读的会话：放弃本轮并立即收声；
     * - 跟踪者已经空闲：只换标识，本快照丢弃（历史回复另有 `!armed && finalized` 兜着）。
     */
    private fun gateConversation(snapshot: VoiceChatSnapshot): ReadAloudPlan? {
        val incoming = snapshot.conversationId ?: return null
        val tracked = conversationId
        if (tracked == null) {
            conversationId = incoming
            return null
        }
        if (tracked == incoming) return null
        val active = armed || (currentSegment != null && currentSegment != finalizedSegment)
        if (active) {
            abandonCurrentRun()
            return ReadAloudPlan.dropped(snapshot.lastAgentId, stop = true)
        }
        reset()
        conversationId = incoming
        return if (snapshot.isStreaming) null else ReadAloudPlan.dropped(null)
    }

    /** 运行身份只在流式期间存在（结束时生产方给 null），所以 null 表示「继续用当前判定」。 */
    private fun observeRun(snapshot: VoiceChatSnapshot) {
        val incoming = snapshot.runId ?: return
        if (incoming == runId) return
        runId = incoming
        // 第一次看见这一轮时气泡里已经有本轮的成文内容 ⇒ 我们是在半截上接进来的，整轮不读。
        // 媒体直出会在开跑同一帧建好空气泡，靠 lastAgentText.isBlank() 区分，不受影响。
        if (snapshot.isStreaming &&
            AssistantMessageId.belongsToRun(snapshot.lastAgentId, incoming) &&
            snapshot.lastAgentText.isNotBlank()
        ) {
            abandonedRunId = incoming
        }
    }

    private fun silent(snapshot: VoiceChatSnapshot): Boolean {
        val rid = snapshot.runId
        val abandoned = abandonedRunId
        if (rid != null && abandoned != null && rid != abandoned) {
            abandonedRunId = null
        } else if (abandoned != null) {
            // 放弃的那一轮：流式期间靠 runId 命中；结束后生产方不再给 runId，
            // 但气泡 id 仍带着本轮 runId，整轮静默到底——切回来也不补读半截。
            if (rid == abandoned) return true
            if (rid == null && AssistantMessageId.belongsToRun(snapshot.lastAgentId, abandoned)) return true
        }
        if (abandonedUntilFinalized) return true
        return suppressUntilArmed
    }
}

/**
 * 回复自动朗读的进程级控制器：应用内聊天与助手浮窗都把 [VoiceChatSnapshot] 喂进来，
 * 由这里决定「是否读、读哪几句」。朗读复用 [SpeechPlayback]，音色/模型走朗读分区
 * （[SpeechPlaybackProfile.READ_ALOUD]）配置，与手动朗读、试听完全同一条播放通路。
 *
 * 播放所有权前缀见 [OWNER_PREFIX]：自动朗读不绑定某条可见消息（朗读的是整条回复，
 * 且可能在流式中就开始），因此不能被「回复不在可见列表里」的孤儿回收收掉。
 *
 * 决策与播放拆成两条协程：播放协程 `await` 一整句的时候，决策协程照常消化新快照并改写队列，
 * 所以「模型已经另起一段」不会排到上一段读完才看得见。
 */
internal object ReplyReadAloudController {

    /** Owner 前缀；一段一个 owner，[SpeechPlayback.stopOwned] 只收自己的播放。 */
    const val OWNER_PREFIX = "auto-read-"

    /**
     * 换段时是否立刻掐断正在朗读的那一句。
     * false（默认）= 当前这句读完再切下一段：句中断裂会啃掉半个字，代价是新段最多晚一句起步；
     * true = 立即收声抢占，新段马上开口。
     */
    private const val INTERRUPT_ON_SEGMENT_SWITCH = false

    /** 自打断的 stop reason：只有这两种取消算「我们自己换段」，其余取消 = 把扬声器让出去。 */
    private const val STOP_SEGMENT_SWITCH = "segment_switch"
    private const val STOP_YIELD = "auto_read_yield"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    /** 文本是全量快照，最新的必然正确，所以合并到一条即可。 */
    private val snapshots = Channel<VoiceChatSnapshot>(Channel.CONFLATED)

    /** 队列里有了新句子就唤醒播放协程；唤醒信号本身无内容，同样合并。 */
    private val work = Channel<Unit>(Channel.CONFLATED)
    private val queue = ReplyReadAloudQueue()
    private val tracker = ReplyReadAloudTracker()
    private var app: Context? = null
    private var decide: Job? = null
    private var play: Job? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun updateChat(context: Context, snapshot: VoiceChatSnapshot) {
        if (app == null) app = context.applicationContext
        if (!ReplyReadAloudPreference.autoRead) {
            // 关闭自动朗读：立刻停掉正在进行的自动朗读并解除武装。
            disable()
            return
        }
        ensureObserving()
        ensureLoops()
        snapshots.trySend(snapshot)
    }

    /** 关闭总开关 / 结束 Service 时调用：停掉自己的播放并清空进度。 */
    fun stop() {
        disable()
        shutdown()
    }

    /**
     * 用户点回复的朗读按钮停止自动朗读：这一轮视为已读完（换段、跑到结尾都不再开口），
     * 并收掉自己的播放。
     */
    fun stopCurrent() {
        tracker.markCurrentFinalized()
        queue.clear()
        SpeechPlayback.stopOwned("auto_read_stop") { it.startsWith(OWNER_PREFIX) }
    }

    /**
     * 会话切换的显式交接：生产方（浮窗 / 应用）各自离开时调用，避免只靠快照差值判定。
     * 只清自己的播放，手动朗读与语音对话不受影响。
     */
    fun abandonForConversationSwitch() {
        tracker.abandonCurrentRun()
        queue.clear()
        SpeechPlayback.stopOwned("conversation_switch") { it.startsWith(OWNER_PREFIX) }
    }

    /**
     * 应用切到后台时是否保留 [owner] 的播放：只有「自动朗读」且设置「后台继续朗读」打开才放行。
     * 手动朗读（owner = 消息 id）、试听与语音对话的 owner 一律返回 false，仍按原逻辑停止。
     * 由生命周期入口逐次读取，设置项改动即时生效，不需要重启应用。
     */
    fun keepsPlayingInBackground(owner: String?): Boolean =
        ReplyReadAloudPreference.continueInBackground && owner?.startsWith(OWNER_PREFIX) == true

    private fun ensureLoops() {
        if (decide?.isActive != true) decide = scope.launch { decideLoop() }
        if (play?.isActive != true) play = scope.launch { playLoop() }
    }

    private suspend fun decideLoop() {
        while (currentCoroutineContext().isActive) {
            val snapshot = snapshots.receive()
            if (!ReplyReadAloudPreference.autoRead) {
                disable()
                continue
            }
            apply(tracker.onSnapshot(snapshot, ReplyReadAloudPreference.streamSynthesis))
        }
    }

    /**
     * 只在主线程访问：队列与 [SpeechPlayback] 都是主线程语义，
     * 这里到 [playLoop] 之间没有挂起点，两者不会穿插。
     */
    private fun apply(plan: ReadAloudPlan) {
        if (plan.stop) {
            queue.clear()
            SpeechPlayback.stopOwned(STOP_YIELD) { it.startsWith(OWNER_PREFIX) }
        } else if (plan.flush) {
            queue.dropStale(plan.messageId)
            if (INTERRUPT_ON_SEGMENT_SWITCH) {
                SpeechPlayback.stopOwned(STOP_SEGMENT_SWITCH) { it.startsWith(OWNER_PREFIX) }
            }
        }
        if (plan.utterances.isNotEmpty()) {
            queue.enqueueAll(plan.utterances)
            work.trySend(Unit)
        }
    }

    private suspend fun playLoop() {
        while (currentCoroutineContext().isActive) {
            work.receive()
            playQueued()
        }
    }

    private suspend fun playQueued() {
        while (currentCoroutineContext().isActive) {
            val item = queue.peek() ?: return
            queue.take()
            val app = app ?: continue
            if (item.messageId.isBlank() || item.text.isBlank()) continue
            val activeOwner = SpeechPlayback.state.value.owner
            if (activeOwner != null && !activeOwner.startsWith(OWNER_PREFIX)) {
                // 用户手动朗读 / 语音对话 / Agent 工具占着扬声器：整批作废，等下一次快照唤醒。
                queue.clear()
                return
            }
            val completion = CompletableDeferred<SpeechOutcome>()
            SpeechPlayback.speak(
                app,
                OWNER_PREFIX + item.messageId,
                item.text,
                listener = object : SpeechPlaybackListener {
                    override fun onFinished(outcome: SpeechOutcome) {
                        completion.complete(outcome)
                    }
                },
                settings = SpeechPlaybackProfile.READ_ALOUD.snapshot(),
            )
            when (val outcome = completion.await()) {
                SpeechOutcome.Completed -> Unit
                is SpeechOutcome.Cancelled -> if (!isSelfStop(outcome.reason)) {
                    // 被打断（手动朗读、录音、失败）：放弃剩余句子，但协程留着等新段。
                    queue.clear()
                    return
                }
                is SpeechOutcome.Failed -> {
                    queue.clear()
                    return
                }
            }
        }
    }

    private fun isSelfStop(reason: String?): Boolean =
        reason == STOP_SEGMENT_SWITCH || reason == STOP_YIELD

    private fun disable() {
        tracker.reset()
        queue.clear()
        SpeechPlayback.stopOwned("auto_read_off") { it.startsWith(OWNER_PREFIX) }
    }

    private fun shutdown() {
        decide?.cancel()
        play?.cancel()
        decide = null
        play = null
    }

    /** 开关可能在设置页被关闭，而此时并没有新的快照进来：需要监听并及时收声。 */
    private fun ensureObserving() {
        if (preferenceListener != null) return
        val listener = ReplyReadAloudPreference.observeAutoRead { enabled ->
            // 写入方通常是主线程，但读取/停止播放只允许在主线程，保守地做一次线程切换。
            if (Looper.myLooper() == main.looper) {
                if (!enabled) disable()
            } else {
                main.post { if (!enabled) disable() }
            }
        } ?: return
        preferenceListener = listener
    }
}
