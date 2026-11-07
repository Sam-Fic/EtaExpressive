package io.github.mangi.eta.agent.device

/** Pure decisions: absent evidence never authorizes mutation replay. */
internal object VirtualDisplayRecoveryPolicy {
    data class Flags(val finishing: Boolean, val handoffComplete: Boolean,
        val releaseAttempted: Boolean, val mutationUncertain: Boolean, val sourceEmpty: Boolean)
    enum class Action { HANDOFF, RELEASE_ONLY, REFUSE }
    fun finishAction(f: Flags?): Action = when {
        f == null || f.mutationUncertain || f.releaseAttempted -> Action.REFUSE
        f.handoffComplete && f.finishing && f.sourceEmpty -> Action.RELEASE_ONLY
        f.handoffComplete || f.finishing -> Action.REFUSE
        else -> Action.HANDOFF
    }

    /**
     * 程序侧兜底判定：owner 已处于无法通过 handoff/release 正常收口的终止态
     * （中断的 launch/handoff 留下 finishing / mutationUncertain / releaseAttempted 闩锁）。
     * 这类会话继续调用 finish 只会得到 REFUSE，应由程序终结 owner 进程。
     * flags == null（状态未知）不算：它可能只是瞬时读取失败，仍需保留恢复路径。
     */
    fun ownerTerminallyLatched(f: Flags?): Boolean =
        f != null && finishAction(f) == Action.REFUSE

    /** 兜底释放的只读输入。任何未知或不一致都视为阻塞，不做猜测。 */
    data class ForcedReleaseInput(
        val keptTaskIds: Set<Int>,
        val retainedTaskIds: Set<Int>,
        val liveTaskIds: Set<Int>?,
        val goneTaskIds: Set<Int>?,
        val escapedTaskIds: Set<Int>?,
        val escapedMainTaskIds: Set<Int>?,
        val flags: Flags,
        val forceReleaseAttempted: Boolean,
    )

    sealed interface ForcedReleaseDecision {
        object Allowed : ForcedReleaseDecision
        data class Blocked(val code: String) : ForcedReleaseDecision
    }

    /**
     * P0 兜底：放弃归位的“只释放”仅在空选择清理、且每个保留 root 都能被证明
     * （live / gone / 同一 root 位于 display 0）时成立。逃逸位置未经证明、已有交付承诺、
     * 或存在半完成的 handoff 时一律阻塞；不确定时永远拒绝。
     */
    fun forcedReleaseDecision(input: ForcedReleaseInput): ForcedReleaseDecision = when {
        input.retainedTaskIds.isEmpty() -> ForcedReleaseDecision.Blocked("NOTHING_TO_RECLAIM")
        input.keptTaskIds.isNotEmpty() -> ForcedReleaseDecision.Blocked("DELIVERY_PENDING")
        input.forceReleaseAttempted -> ForcedReleaseDecision.Blocked("FORCE_RELEASE_ALREADY_ATTEMPTED")
        input.flags.mutationUncertain -> ForcedReleaseDecision.Blocked("MUTATION_UNCERTAIN")
        input.flags.releaseAttempted -> ForcedReleaseDecision.Blocked("RELEASE_ALREADY_ATTEMPTED")
        input.flags.handoffComplete || input.flags.finishing ->
            ForcedReleaseDecision.Blocked("PARTIAL_HANDOFF")
        input.liveTaskIds == null || input.goneTaskIds == null || input.escapedTaskIds == null ->
            ForcedReleaseDecision.Blocked("INVENTORY_UNKNOWN")
        input.escapedMainTaskIds == null -> ForcedReleaseDecision.Blocked("ESCAPED_PLACEMENT_UNKNOWN")
        input.escapedTaskIds != input.escapedMainTaskIds ->
            ForcedReleaseDecision.Blocked("ESCAPED_PLACEMENT_UNPROVEN")
        !input.retainedTaskIds.containsAll(input.liveTaskIds) ->
            ForcedReleaseDecision.Blocked("INVENTORY_INCONSISTENT")
        !input.retainedTaskIds.containsAll(input.goneTaskIds) ->
            ForcedReleaseDecision.Blocked("INVENTORY_INCONSISTENT")
        (input.retainedTaskIds - input.liveTaskIds - input.goneTaskIds - input.escapedTaskIds).isNotEmpty() ->
            ForcedReleaseDecision.Blocked("INVENTORY_INCONSISTENT")
        else -> ForcedReleaseDecision.Allowed
    }
    /**
     * Recovery is cleanup-only; it never makes an interrupted run GUI-active again.
     * Eligibility does not authorize handoff/release: finish must revalidate fresh owner evidence
     * and the existing retry budget, including for a clean handoff_pending session.
     */
    fun canRecoverExistingRun(phase: String, closedRun: Boolean): Boolean =
        closedRun && phase in setOf("active", "held", "uncertain", "handoff_pending")

    /**
     * The persisted owner record may only be discarded when this boot still matches and both
     * the recorded process and its display are gone. A reused pid or display id alone is never
     * evidence of absence, and a dead owner cannot mutate a display it no longer owns.
     */
    fun staleOwnerGone(sameBoot: Boolean, processGone: Boolean, displayGone: Boolean): Boolean =
        sameBoot && processGone && displayGone

    fun taskIds(raw: List<*>): Set<Int>? {
        val ids = linkedSetOf<Int>()
        for (value in raw) {
            val id = value as? Int ?: return null
            if (id <= 0 || !ids.add(id)) return null
        }
        return ids
    }
}
