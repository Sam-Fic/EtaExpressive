package io.github.mangi.eta.agent.device

import org.json.JSONArray

/** Pure adapter for the existing v1 owner wire contract; never opens a connection. */
internal object VirtualDisplayHandoffEvidence {
    /**
     * Authentication comes from the peer-verified client and its token-authenticated requests,
     * NOT an invented response field. OwnerProtocol.fail includes v/op/ok/error/message but no
     * displayId, uniqueId, or sideEffectsAttempted. The error code is the owner's side-effect
     * boundary signal; a fresh authenticated status must independently corroborate it.
     */
    fun refused(
        authenticatedConnection: Boolean,
        responseOk: Boolean,
        code: String,
        safeDetail: String,
        field: (String) -> Any?,
    ): VirtualDisplayHandoffRetry.Attempt.Refused = VirtualDisplayHandoffRetry.Attempt.Refused(
        code, safeDetail,
        authenticated = authenticatedConnection && !responseOk && envelope(field, "handoff", false) &&
            field("error") == code &&
            // Sanitization is for diagnostics only; malformed wire detail never gains authority.
            field("message") == safeDetail,
    )

    /**
     * Handoff success has task tallies, but no display identity fields in v1.
     * [goneIds] is the response goneTaskIds set. Null is not "none": omission fails closed.
     * Cleanup mode is an empty selection with cleanupOnly=true and an empty kept set.
     * Non-empty delivery stays strict and rejects cleanupOnly=true.
     */
    fun completed(
        authenticatedConnection: Boolean,
        responseOk: Boolean,
        selectedIds: Set<Int>,
        retainedIds: Set<Int>,
        keptIds: Set<Int>?,
        removedIds: Set<Int>?,
        field: (String) -> Any?,
        goneIds: Set<Int>?,
    ): Boolean {
        if (!authenticatedConnection || !responseOk || !envelope(field, "handoff", true)) return false
        if (field("handedOff") != true || field("sourceEmpty") != true) return false
        if (retainedIds.any { it <= 0 } || !retainedIds.containsAll(selectedIds) || goneIds == null) return false
        val notSelected = retainedIds - selectedIds
        if (!notSelected.containsAll(goneIds)) return false
        if (selectedIds.isEmpty()) {
            if (keptIds?.isEmpty() != true || field("cleanupOnly") != true) return false
        } else if (field("cleanupOnly") == true || keptIds != selectedIds) {
            return false
        }
        return removedIds == notSelected - goneIds
    }

    fun state(
        identity: VirtualDisplayHandoffRetry.OwnerIdentity,
        authenticatedConnection: Boolean,
        statusOk: Boolean,
        flags: VirtualDisplayRecoveryPolicy.Flags?,
        retainedTaskIds: Set<Int>?,
        field: (String) -> Any?,
    ): VirtualDisplayHandoffRetry.OwnerState? {
        if (!authenticatedConnection || !statusOk || !identity.isValid() ||
            flags == null || retainedTaskIds == null || retainedTaskIds.any { it <= 0 }) return null
        if (!envelope(field, "status", true) || field("ready") != true || field("session") != "active" ||
            field("displayId") != identity.displayId || field("uniqueId") != identity.uniqueId) return null
        if (field("finishing") != flags.finishing || field("handoffComplete") != flags.handoffComplete ||
            field("releaseAttempted") != flags.releaseAttempted || field("mutationUncertain") != flags.mutationUncertain ||
            field("sourceEmpty") != flags.sourceEmpty) return null
        val count = field("sourceTaskCount") as? Int ?: return null
        if (count < 0 || flags.sourceEmpty != (count == 0) ||
            field("sourceState") != (if (count == 0) "empty" else "occupied")) return null
        // Omitted live/gone stays unknown. A present but illegal inventory fails the whole status.
        val live = when (val read = readIds(field("liveTaskIds"))) {
            IdRead.Missing -> null
            IdRead.Illegal -> return null
            is IdRead.Present -> read.ids
        }
        val gone = when (val read = readIds(field("goneTaskIds"))) {
            IdRead.Missing -> null
            IdRead.Illegal -> return null
            is IdRead.Present -> read.ids
        }
        if (live != null && !retainedTaskIds.containsAll(live)) return null
        if (gone != null && !retainedTaskIds.containsAll(gone)) return null
        if (live != null && gone != null && live.intersect(gone).isNotEmpty()) return null
        if (live != null && count < live.size) return null
        // 逃逸且已证明仍在主屏的 root 只能是保留集合的子集，且不能同时是 live/gone。
        val escapedMain = when (val read = readIds(field("escapedMainTaskIds"))) {
            IdRead.Missing -> null
            IdRead.Illegal -> return null
            is IdRead.Present -> read.ids
        }
        if (escapedMain != null) {
            if (!retainedTaskIds.containsAll(escapedMain)) return null
            if (live != null && live.intersect(escapedMain).isNotEmpty()) return null
            if (gone != null && gone.intersect(escapedMain).isNotEmpty()) return null
        }
        // 焦点证据是只读诊断：不可读只能是 null，绝不会被当成某个 user。
        val focusReadable = field("focusReadable") == true
        val focusUserId = (field("focusUserId") as? Int)?.takeIf { focusReadable && it >= 0 }
        val focusTaskId = (field("focusTaskId") as? Int)?.takeIf { focusReadable && it > 0 }
        val forcedRelease = field("forceReleaseAttempted") as? Boolean
        return VirtualDisplayHandoffRetry.OwnerState(
            identity, true, flags, retainedTaskIds.toSet(), count, live, gone,
            escapedMainTaskIds = escapedMain,
            focusUserId = focusUserId,
            focusTaskId = focusTaskId,
            forceReleaseAttempted = forcedRelease,
        )
    }

    /** The owner exits after release, so authenticate the request connection, not post-reply liveness. */
    fun released(
        identity: VirtualDisplayHandoffRetry.OwnerIdentity,
        authenticatedConnection: Boolean,
        responseOk: Boolean,
        field: (String) -> Any?,
    ): Boolean = authenticatedConnection && responseOk && identity.isValid() && envelope(field, "release", true) &&
        field("released") == true && field("displayId") == identity.displayId && field("uniqueId") == identity.uniqueId

    private sealed interface IdRead {
        object Missing : IdRead
        object Illegal : IdRead
        class Present(val ids: Set<Int>) : IdRead
    }

    private fun readIds(raw: Any?): IdRead = when (raw) {
        null -> IdRead.Missing
        is List<*> -> VirtualDisplayRecoveryPolicy.taskIds(raw)?.let { IdRead.Present(it) } ?: IdRead.Illegal
        is JSONArray -> {
            val values = (0 until raw.length()).map { raw.opt(it) }
            VirtualDisplayRecoveryPolicy.taskIds(values)?.let { IdRead.Present(it) } ?: IdRead.Illegal
        }
        else -> IdRead.Illegal
    }

    private fun envelope(field: (String) -> Any?, op: String, ok: Boolean): Boolean =
        field("v") == 1 && field("op") == op && field("ok") == ok
}
