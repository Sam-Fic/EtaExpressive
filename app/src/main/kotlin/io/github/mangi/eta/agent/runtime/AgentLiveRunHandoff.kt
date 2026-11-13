package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 外部入口（系统语音浮窗）把**仍在运行**的 run 交给应用内主程序的进程内交接通道。
 *
 * 关闭浮窗只结束它的 UI 承载，run 继续在 Runtime 里推进；此前没有任何应用内 UI 在订阅它，
 * 用户只能等 run 终态后靠归档一次性看到全过程。这里在关闭浮窗时发布一次「这一单归你了」，
 * 主程序收到后走与「挂接已存在的 run」完全相同的通路（attach → 事件投影 → 终态落库），
 * 把增量实时画进会话，浮窗里没看完的内容不再凭空消失。
 *
 * 只发身份与首轮文本，事件走 Runtime 的 attach 回放 / 实时流：总线不复制事件，也不持有
 * 会话状态。Runtime 与 UI 同在主进程（清单里 [AgentRuntimeService] 未声明 `android:process`）。
 *
 * `replay` 保留最近两条：主程序的 `AgentAppState` 可能晚于浮窗关闭才构造（用户先关浮窗、
 * 再打开应用）。订阅方必须用「该 run 是否仍活跃」判定丢弃过期通告，否则会拿旧单去占位。
 * 条数刻意压到 2——通知带用户预览图（data URL），常驻太多会在进程里占住几 MB。
 */
internal object AgentLiveRunHandoff {

    /** 一次交接：runId + 首轮用户输入 + 展示所需档位（与归档载荷同构，便于两条路对齐）。 */
    data class Notice(
        val runId: String,
        val source: String,
        val payload: AgentExternalArchivePayload,
        val userImagePreviews: List<String>,
        val createdAt: Long,
    )

    private val _notices = MutableSharedFlow<Notice>(
        replay = 2,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val notices: SharedFlow<Notice> = _notices

    fun publish(notice: Notice) {
        if (notice.runId.isBlank()) return
        val accepted = _notices.tryEmit(notice)
        AndroidAgentLogger.info(
            "live run handoff: run=${notice.runId} conversation=${notice.payload.conversationKey} " +
                "emitted=$accepted",
        )
        if (!accepted) {
            AndroidAgentLogger.warn("live run handoff dropped: run=${notice.runId}")
        }
    }
}
