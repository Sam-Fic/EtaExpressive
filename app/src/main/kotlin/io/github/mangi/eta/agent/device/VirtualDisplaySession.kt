package io.github.mangi.eta.agent.device

import android.content.Context
import android.content.SharedPreferences
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 后台 run 的 owner 副屏存在性：供顶部胶囊在 controller 未参与时仍能显示舞台。
 * 坐标单位是副屏像素，与 owner 的 input 契约一致。
 */
internal data class OwnerDisplayPresence(
    val runId: String,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val packageName: String,
    val appName: String,
)

/** Single-device owner. Recovery errors never authorize a second display. */
internal object VirtualDisplaySession {
    const val NOT_READY = "VIRTUAL_DISPLAY_HANDOFF_NOT_READY"
    private const val ADOPTED_CLEANUP_ONLY =
        "已恢复上一轮中断的副屏会话，当前只能清理；请先 finish_virtual_session，再 start_virtual_session 开始新的会话"
    private const val CLEANUP_REQUIRED_MESSAGE =
        "副屏会话当前不可操作：它恢复自上一轮中断的会话，只能执行清理。请先调用 finish_virtual_session；" +
            "若持续失败，请到「设置 - 虚拟屏」点击恢复/关闭，或退出后台虚拟屏模式后重试本操作"
    private const val UNSUPPORTED_GUI_MESSAGE =
        "该操作在后台副屏模式下不可用：后台模式不会触碰主屏。" +
            "请改用副屏支持的工具（observe_screen / tap / swipe / press_key(BACK|ENTER|PASTE)）；" +
            "launch_app / press_key 可显式传 display=main（仅在本会话刚在主屏启动的应用仍占前台时生效）；" +
            "或退出后台虚拟屏模式后重试"
    private const val UNSUPPORTED_KEY_MESSAGE =
        "副屏只接受 BACK / ENTER / PASTE 三个按键，HOME 等系统键不可用，且不会作用于主屏。" +
            "若需要回到主屏或修复主屏焦点，请到「设置 - 虚拟屏」点击恢复/关闭，或退出后台虚拟屏模式"
    private const val UNSUPPORTED_TEXT_MODE_MESSAGE =
        "副屏只支持追加输入（mode=append）；替换/清空等模式不可用。如需替换文本，请先清空输入框再追加"
    private class Session(var client: VirtualDisplayOwnerClient? = null, var phase: String = "starting") {
        /** 当前使用这块副屏的 app run；adoption 会转移到新的 run。 */
        var runId: String = ""
        /** owner 创建时的副屏几何；input 坐标按此换算。 */
        var width: Int = 0
        var height: Int = 0
        val kept = linkedSetOf<Int>()
        val packages = linkedMapOf<String, Set<Int>>()
        val observation = VirtualDisplayObservation()
        val previewExcludedPackages = linkedSetOf<String>()
        var closedRun = false
        var cleanupOnly = false
        /**
         * This run did not create this owner; it only adopted an earlier run's session (in memory
         * or from the persisted record) to clean it up. Finishing it must not consume this run's
         * single session slot: after a clean release the caller still needs a usable display.
         */
        var adopted = false
        var abortCleanupAttempted = false
        /**
         * 程序侧兜底终结已尝试过：闩死的 owner 只尝试一次，
         * 失败后复用同一份终态回执，不再向模型重复抛同类错误。
         */
        var autoTerminationAttempted = false
        var persisted = false
        var receipt: JSONObject? = null
        /** Shared across finish/onRunClosed for this run so automatic retries cannot multiply. */
        val handoffBudget = VirtualDisplayHandoffRetry.Budget()
        // Retain the pre-attempt baseline across reentry/adoption; never replace it with a new owner.
        var handoffState: VirtualDisplayHandoffRetry.OwnerState? = null
        var handoffSelection: Set<Int>? = null
        // Bounded history survives a held-session adoption; includes successful later attempts.
        val handoffAttempts = JSONArray()
        var handoffDiagnostics: VirtualDisplayHandoffDiagnostics.Failure? = null
        /** 放弃归位的兜底：一个 owner 会话只允许一次，且与 handoff 预算完全独立。 */
        var forcedReleaseUsed = false
        var forcedReleaseAttempted = false
        /** 主屏焦点实际变化后仅允许重置一次 handoff 预算。 */
        var focusBudgetResetUsed = false
        /** 本会话累计被主屏焦点前置条件干净拒绝的次数；兜底释放只认可这个证据。 */
        var focusRejections = 0
        /** reclaim 是否曾因“焦点任务不可移动”而被拒绝；该拒绝不消耗也不产生 handoff 预算。 */
        var reclaimFocusRefused = false
        var lastFocusUserId: Int? = null
        var lastFocusTaskId: Int? = null
        /** 最近一次 reclaim 的结果，写进日志与回执；不再静默丢弃。 */
        var reclaimCode: String? = null
        var reclaimCount: Int = 0
        var reclaimUncertain = false
    }
    private val sessions = linkedMapOf<String, Session>()
    /** 所有处于 active 的 owner 副屏，key 是正在使用它的 app runId。 */
    private val presenceState = MutableStateFlow<Map<String, OwnerDisplayPresence>>(emptyMap())
    val displayPresence: StateFlow<Map<String, OwnerDisplayPresence>> = presenceState.asStateFlow()
    private var recoveryContext: Context? = null
    private const val RECOVERY_PREFS = "virtual_display_owner_recovery"
    private const val ADOPTION_PROBE_TIMEOUT_MS = 3_000L
    private fun recoveryPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE)
    private fun publishPresence(s: Session, state: JSONObject? = null, packageName: String? = null) {
        val client = s.client
        if (client == null || s.phase != "active" || s.cleanupOnly || !s.persisted || s.runId.isBlank()) {
            clearPresence(s)
            return
        }
        val width = state?.optInt("width", 0)?.takeIf { it > 0 } ?: s.width
        val height = state?.optInt("height", 0)?.takeIf { it > 0 } ?: s.height
        if (width <= 0 || height <= 0) return
        s.width = width
        s.height = height
        val pkg = packageName?.takeIf { it.isNotBlank() }
            ?: s.packages.keys.lastOrNull()
            ?: sourcePackages(state).firstOrNull()
            ?: ""
        presenceState.value = presenceState.value + (s.runId to OwnerDisplayPresence(
            runId = s.runId,
            displayId = client.displayId,
            width = width,
            height = height,
            packageName = pkg,
            appName = appLabel(pkg),
        ))
    }
    private fun clearPresence(s: Session) {
        if (s.runId.isBlank() || !presenceState.value.containsKey(s.runId)) return
        presenceState.value = presenceState.value - s.runId
    }
    private fun sourcePackages(state: JSONObject?): List<String> {
        val packages = state?.optJSONArray("sourcePackages") ?: return emptyList()
        return (0 until packages.length()).mapNotNull { packages.optString(it).takeIf(String::isNotBlank) }
    }
    private fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return ""
        val context = recoveryContext ?: return pkg
        return runCatching {
            val manager = context.packageManager
            manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }
    private fun escapedTaskIds(state: OwnerResponse): Int =
        state.json?.optJSONArray("escapedTaskIds")?.length() ?: 0
    private fun bootId(): String? = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
            .takeIf { it.matches(Regex("[0-9a-fA-F-]{36}")) }
    }.getOrNull()
    /** Signal zero only: ESRCH proves the persisted owner process is gone; EPERM still owns a pid. */
    private fun ownerProcessGone(pid: Long): Boolean = try {
        android.system.Os.kill(pid.toInt(), 0)
        false
    } catch (ex: android.system.ErrnoException) {
        ex.errno == android.system.OsConstants.ESRCH
    }
    /** A reused display id remains unconfirmed, so only an absent id proves the display is gone. */
    private fun ownerDisplayGone(context: Context, displayId: Int): Boolean {
        val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?: return false
        return manager.getDisplay(displayId) == null
    }
    /** A null, closed, or unresponsive client can never hand off or release. */
    private fun ownerReachable(client: VirtualDisplayOwnerClient?): Boolean {
        if (client == null || !client.isAlive) return false
        return try { client.status(ADOPTION_PROBE_TIMEOUT_MS).ok }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        catch (_: Exception) { false }
    }
    private fun reply(ok: Boolean, code: String = "", detail: String = "") =
        JSONObject().put("ok",ok).put("error",code).put("message",detail)
    private fun body(response: OwnerResponse): JSONObject = response.json ?: reply(false,response.errorCode)
    private fun ids(value: JSONArray?): Set<Int>? = value?.let {
        VirtualDisplayRecoveryPolicy.taskIds((0 until it.length()).map(it::opt))
    }
    private fun flags(state: JSONObject): VirtualDisplayRecoveryPolicy.Flags? {
        val values = listOf("finishing", "handoffComplete", "releaseAttempted", "mutationUncertain", "sourceEmpty")
            .map { state.opt(it) }
        if (values.any { it !is Boolean }) return null
        return VirtualDisplayRecoveryPolicy.Flags(values[0] as Boolean, values[1] as Boolean,
            values[2] as Boolean, values[3] as Boolean, values[4] as Boolean)
    }
    private fun handoffState(c: VirtualDisplayOwnerClient, response: OwnerResponse): VirtualDisplayHandoffRetry.OwnerState? {
        val data = response.json ?: return null
        return VirtualDisplayHandoffEvidence.state(
            identity = VirtualDisplayHandoffRetry.OwnerIdentity(c.socketName, c.ownerPid, c.displayId, c.uniqueId),
            authenticatedConnection = c.isAlive,
            statusOk = response.ok && response.op == VirtualDisplayOwnerProtocol.OP_STATUS,
            flags = flags(data),
            retainedTaskIds = ids(data.optJSONArray("retainedTaskIds")),
            field = data::opt,
        )
    }
    private fun freshHandoffState(c: VirtualDisplayOwnerClient): VirtualDisplayHandoffRetry.OwnerState? =
        try { handoffState(c, c.status()) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); null }
        catch (_: Exception) { null }

    private fun fail(s: Session, code: String, detail: String = ""): JSONObject {
        s.handoffBudget.stop()
        s.phase = "uncertain"
        clearPresence(s)
        AndroidAgentLogger.warn(
            "Virtual display handoff terminal: conversation=- run=${s.runId.ifBlank { "-" }} " +
                "dispatch=- state=uncertain code=$code",
        )
        return VirtualDisplayHandoffDiagnostics.annotate(
            reply(false, code, detail).withHandoffAttempts(s), s.handoffDiagnostics, uncertain = true,
        ).also { s.receipt = it }
    }
    /**
     * finish 可能被重入。首次失败往往携带具体诊断（例如 owner 的 HANDOFF_PREFLIGHT_FAILED）；
     * 之后的重入不得把这份诊断降级成笼统的 RECOVERY_UNCERTAIN。
     */
    private fun failPreservingPrior(s: Session, code: String): JSONObject {
        s.handoffBudget.stop()
        val prior = s.receipt
        val priorCode = prior?.takeIf { !it.optBoolean("ok") }?.optString("error").orEmpty()
        if (code in setOf("RECOVERY_UNCERTAIN", "OWNER_STATE_UNKNOWN") && priorCode.isNotBlank()) {
            s.phase = "uncertain"
            AndroidAgentLogger.warn(
                "Virtual display handoff terminal: conversation=- run=${s.runId.ifBlank { "-" }} " +
                    "dispatch=- state=uncertain code=$priorCode",
            )
            return VirtualDisplayHandoffDiagnostics.preservedReceipt(prior!!)
        }
        return fail(s, code)
    }
    /** 只转发无歧义的安全细节；绝不回显 token、Intent 转储或数据 URI。 */
    private fun safeOwnerDetail(response: OwnerResponse): String {
        val raw = response.json?.opt("message") as? String ?: return ""
        val collapsed = raw.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim()
        if (collapsed.isEmpty() || collapsed.length > 180) return ""
        // Only the symbolic handoff phase/type and integer task tallies are allowed.
        if (!collapsed.matches(Regex("[A-Za-z0-9_:#.\\-]+:[A-Za-z0-9_$]+; ?moved=\\[[0-9, ]*\\] removed=\\[[0-9, ]*\\]"))) return ""
        return collapsed
    }
    private fun JSONObject.withHandoffAttempts(s: Session): JSONObject = apply {
        if (s.handoffAttempts.length() > 0) {
            put("handoffAttempts", JSONArray(s.handoffAttempts.toString()))
        }
        // reclaim 不再静默：失败码与回收计数随回执一起返回，便于区分“无法归位”与“归位成功”。
        s.reclaimCode?.let { put("reclaim_error", it) }
        if (s.reclaimCount > 0) put("reclaim_count", s.reclaimCount)
    }

    /** 只有“预算耗尽且未产生任何变更”的旧回执才会被这样解释，不会覆盖原始失败细节。 */
    private fun withPreservedReceiptExplanation(s: Session, receipt: JSONObject): JSONObject =
        VirtualDisplayHandoffDiagnostics.preservedReceipt(receipt).also { s.receipt = it }

    /**
     * 主屏焦点实际变化后，允许重置一次 handoff 预算。只对“干净停止”（无锚点、无移除、无变更）
     * 生效；budget.blocked（可能已产生变更）永不重置，避免把不确定变更重放一次。
     */
    private fun maybeResetHandoffBudgetAfterFocusChange(
        s: Session,
        observed: VirtualDisplayHandoffRetry.OwnerState,
    ) {
        val previousUser = s.lastFocusUserId
        val previousTask = s.lastFocusTaskId
        s.lastFocusUserId = observed.focusUserId
        s.lastFocusTaskId = observed.focusTaskId
        if (s.focusBudgetResetUsed || s.handoffBudget.blocked) return
        val user = observed.focusUserId ?: return
        val task = observed.focusTaskId ?: return
        if (previousUser == null || previousTask == null) return
        if (previousUser == user && previousTask == task) return
        if (!s.handoffBudget.resetAfterFocusChange()) return
        s.focusBudgetResetUsed = true
        AndroidAgentLogger.info(
            "Virtual display handoff budget reset after focus change: " +
                "$previousUser/$previousTask -> $user/$task"
        )
    }

    /**
     * 兜底释放的前置条件：必须是空选择清理、且本会话确实被主屏焦点连续拒绝过（>=2）。
     * 正常 handoff 预算耗尽，或 reclaim 因“焦点任务不可移动”被拒绝且该拒绝在多次 finish
     * 中重复出现，都算证据；普通失败永远不会触发。
     */
    private fun forcedReleaseEligible(s: Session): Boolean =
        s.kept.isEmpty() && s.focusRejections >= 2 &&
            (!s.handoffBudget.hasRemaining() || s.reclaimFocusRefused)

    /**
     * P0 兜底：空选择清理被连续拒绝时，放弃归位、只释放副屏。
     *
     * 不变量：不移动、不关闭任何主屏任务；每个保留 root 必须被证明为 live / gone / 同一 root
     * 位于 display 0；一个 owner 会话只尝试一次；与 handoff 预算完全独立。成功后 phase=finished，
     * 采用态（adopted）会释放本 run 的会话槽，可直接 start 新副屏。
     */
    private fun tryForcedRelease(
        s: Session,
        c: VirtualDisplayOwnerClient,
        observed: VirtualDisplayHandoffRetry.OwnerState,
        reason: String,
    ): JSONObject? {
        if (s.forcedReleaseUsed || s.kept.isNotEmpty()) return null
        val live = observed.liveTaskIds
        val gone = observed.goneTaskIds
        val escaped = if (live != null && gone != null) observed.retainedTaskIds - live - gone else null
        val decision = VirtualDisplayRecoveryPolicy.forcedReleaseDecision(
            VirtualDisplayRecoveryPolicy.ForcedReleaseInput(
                keptTaskIds = s.kept.toSet(),
                retainedTaskIds = observed.retainedTaskIds,
                liveTaskIds = live,
                goneTaskIds = gone,
                escapedTaskIds = escaped,
                escapedMainTaskIds = observed.escapedMainTaskIds,
                flags = observed.flags,
                forceReleaseAttempted = s.forcedReleaseAttempted ||
                    observed.forceReleaseAttempted == true,
            ),
        )
        if (decision is VirtualDisplayRecoveryPolicy.ForcedReleaseDecision.Blocked) {
            AndroidAgentLogger.warn("Virtual display forced release blocked: ${decision.code} reason=$reason")
            return null
        }
        val ctx = recoveryContext ?: run {
            AndroidAgentLogger.warn("Virtual display forced release skipped: no context")
            return null
        }
        s.forcedReleaseUsed = true
        s.forcedReleaseAttempted = true
        s.handoffBudget.stop() // 兜底与正常 handoff 预算彻底分开，失败也不再重试
        if (!runCatching {
                recoveryPrefs(ctx).edit()
                    .putString(VirtualDisplayRecoveryRecord.BARRIER, "forced_release_pending").commit()
            }.getOrDefault(false)
        ) return fail(s, "RECOVERY_STATE_UNWRITABLE")
        val authenticated = c.isAlive
        AndroidAgentLogger.info(
            "Virtual display forced release: reason=$reason retained=${observed.retainedTaskIds.size} " +
                "live=${live?.size ?: -1} escapedMain=${observed.escapedMainTaskIds?.size ?: -1}"
        )
        if (!authenticated) return fail(s, "RELEASE_UNCERTAIN")
        val released = try {
            c.release(force = true)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt(); return fail(s, "RELEASE_UNCERTAIN")
        } catch (_: Exception) {
            return fail(s, "RELEASE_UNCERTAIN")
        }
        if (!VirtualDisplayHandoffEvidence.released(
                observed.identity, authenticated,
                released.ok && released.op == VirtualDisplayOwnerProtocol.OP_RELEASE,
                { released.json?.opt(it) },
            )
        ) return fail(s, released.errorCode.ifBlank { "FORCED_RELEASE_FAILED" }, safeOwnerDetail(released))
        val receipt = clearReleased(ctx, s)
        val remainingIds = released.json?.optJSONArray("remainingOnMainTaskIds") ?: JSONArray()
        val remainingPackages = released.json?.optJSONArray("remainingOnMainPackages") ?: JSONArray()
        val remainingLabel = (0 until remainingIds.length()).joinToString(",") { remainingIds.optInt(it).toString() }
        val packageLabel = (0 until remainingPackages.length()).joinToString(", ") { remainingPackages.optString(it) }
        val remainingText = if (remainingIds.length() == 0) "没有已证明滞留在主屏的任务"
        else "任务 $remainingLabel${if (packageLabel.isBlank()) "" else "（$packageLabel）"} 仍在主屏"
        return receipt
            .put("forceRelease", true)
            .put("releaseOnly", true)
            .put("handedOff", false)
            .put("state", "finished")
            .put("remainingOnMainTaskIds", remainingIds)
            .put("remainingOnMainPackages", remainingPackages)
            .put("releasedSourceTaskIds", released.json?.optJSONArray("releasedSourceTaskIds") ?: JSONArray())
            .put("releasedSourcePackages", released.json?.optJSONArray("releasedSourcePackages") ?: JSONArray())
            .put(
                "forcedReleaseNote",
                "副屏已强制关闭（未执行归位）：$remainingText，不会被移回或关闭；" +
                    "本次清理不再重试，需要新副屏可直接重新调用 start_virtual_session",
            )
            .put("next_step", "如需继续使用后台副屏，直接调用 start_virtual_session 新开会话")
            .withHandoffAttempts(s).also { s.receipt = it }
    }

    private fun recordHandoff(s: Session, response: OwnerResponse, completed: Boolean) {
        val diagnostics = VirtualDisplayHandoffDiagnostics.sanitizeFailure(response.json)
        s.handoffDiagnostics = diagnostics
        val rawSamples = response.json?.optJSONArray("focusSamples")
        val samples = JSONArray()
        if (rawSamples != null) for (i in 0 until minOf(rawSamples.length(), 4)) {
            val token = rawSamples.opt(i) as? String ?: continue
            if (token.length <= 80 && token.matches(Regex("OK|Focus_[A-Z_]+"))) samples.put(token)
        }
        if (s.handoffAttempts.length() >= 6) s.handoffAttempts.remove(0)
        s.handoffAttempts.put(VirtualDisplayHandoffDiagnostics.annotate(JSONObject()
            .put("ok", completed)
            .put("error", if (completed) "" else VirtualDisplayHandoffDiagnostics.safeHandoffError(response.errorCode))
            .put("message", if (completed) "" else safeOwnerDetail(response))
            .put("focusSamples", samples), diagnostics))
    }

    private fun clearReleased(context: Context?, s: Session): JSONObject {
        s.phase = "finished"
        clearPresence(s)
        s.client?.close()
        val cleared = context != null && runCatching {
            recoveryPrefs(context).edit().clear().commit()
        }.getOrDefault(false)
        return if (cleared) reply(true).put("released", true)
        else reply(false, "RECOVERY_RECORD_CLEAR_FAILED").put("released", true)
    }
    /** Only a cleanly released session this run merely adopted frees its slot for a fresh start. */
    private fun freesRunSlot(s: Session): Boolean {
        val receipt = s.receipt ?: return false
        return s.adopted && receipt.optBoolean("ok") && receipt.optBoolean("released")
    }
    @Synchronized fun start(context: Context, runId: String, createIfMissing: Boolean = true): JSONObject {
        recoveryContext = context.applicationContext
        if (runId.isBlank()) return reply(false, "RUN_ID_REQUIRED")
        sessions[runId]?.let { s ->
            if (s.phase == "finished") {
                // A session this run merely adopted was cleaned up, not used: the owner is
                // released and the recovery record cleared, so the slot is free for the display
                // this caller actually wants. Keep SESSION_FINISHED for this run's own session.
                if (!freesRunSlot(s)) return reply(false, "SESSION_FINISHED")
                sessions.remove(runId)
            } else if (s.autoTerminationAttempted) {
                // 程序侧兜底已给出终态结论：直接复用，不再把 cleanup_only 反复抛给模型。
                return s.receipt ?: reply(false, "RECOVERY_REQUIRED").put("phase", s.phase)
            } else {
                // A cancelled run can still select delivery tasks and finish through the owner.
                // Do not restore GUI access or reset this run's automatic retry budget.
                // An empty kept set stays empty: recovery must not promote every launched task.
                if (VirtualDisplayRecoveryPolicy.canRecoverExistingRun(s.phase, s.closedRun)) {
                    s.cleanupOnly = true
                    return reply(true).put("recovered", true).put("cleanup_only", true)
                        .put("phase", s.phase).put("message", ADOPTED_CLEANUP_ONLY)
                }
                return if (s.phase == "active") reply(true).put("phase", s.phase)
                else reply(false, "RECOVERY_REQUIRED").put("phase", s.phase)
            }
        }
        val others = sessions.values.filter { it.phase != "finished" }
        if (others.any { !it.closedRun } || others.size > 1) return reply(false, "VIRTUAL_SESSION_BUSY")
        if (others.size == 1) {
            val s = others.single()
            sessions.entries.removeAll { it.value === s }
            if (ownerReachable(s.client)) {
                s.cleanupOnly = true
                s.adopted = true
                clearPresence(s)
                s.runId = runId
                sessions[runId] = s
                // 程序侧兜底：原 run 已结束，若 owner 已被中断的 launch/handoff 闩死，
                // finish 永远只会 REFUSE。直接终结 owner，再继续创建新副屏，
                // 而不是把 cleanup_only 反复抛给模型。
                val latched = s.client?.let {
                    VirtualDisplayRecoveryPolicy.ownerTerminallyLatched(freshHandoffState(it)?.flags)
                } == true
                if (latched && !s.autoTerminationAttempted) {
                    val terminated = terminateLatchedOwner(context, s, "adopted_latched")
                    if (!createIfMissing || !terminated.optBoolean("ok")) return terminated
                } else {
                    // A deliberate later run gets a fresh clean-rejection budget, never mutation replay.
                    s.handoffBudget.reset()
                    return reply(true).put("recovered", true).put("cleanup_only", true)
                        .put("phase", s.phase).put("message", ADOPTED_CLEANUP_ONLY)
                }
            } else {
                // Adopting an unreachable owner leaves cleanup impossible forever. Forget it and let
                // the persisted record re-authenticate the owner, or prove it stale, instead.
                s.client?.close()
            }
        }
        var recoveryStage = "record_read"
        val saved = try { recoveryPrefs(context) } catch (ex: Exception) {
            val e = VirtualDisplayRecoveryException.classify(recoveryStage, ex)
            return reply(false, e.code).put("recovery_stage", e.stage)
        }
        if (!createIfMissing && runCatching { saved.all.isEmpty() }.getOrDefault(false)) return reply(false, "NO_VIRTUAL_SESSION")
        val s = Session()
        s.runId = runId
        sessions[runId] = s
        val boot = bootId() ?: return fail(s, "BOOT_ID_UNAVAILABLE")
        var staleCleared = false
        try {
            val fields = saved.all
            if (fields.isNotEmpty() && VirtualDisplayRecoveryRecord.previousBoot(fields["boot"], boot)) {
                recoveryStage = "record_write"
                if (!saved.edit().clear().commit()) return fail(s, "RECOVERY_STATE_UNWRITABLE")
            } else if (fields.isNotEmpty()) {
                recoveryStage = "record_decode"
                val record = VirtualDisplayRecoveryRecord.decode(fields)
                val sameBoot = record.boot == boot
                if (VirtualDisplayRecoveryPolicy.staleOwnerGone(
                        sameBoot = sameBoot,
                        processGone = ownerProcessGone(record.pid),
                        displayGone = ownerDisplayGone(context, record.displayId),
                    )) {
                    // A dead owner cannot mutate the display it no longer owns. Keeping the record
                    // would block this device until reboot, so discard it before any barrier check.
                    recoveryStage = "record_write"
                    if (!saved.edit().clear().commit()) return fail(s, "RECOVERY_STATE_UNWRITABLE")
                    staleCleared = true
                    AndroidAgentLogger.warn("Virtual display recovery discarded a stale owner record")
                } else {
                    if (!sameBoot) throw VirtualDisplayRecoveryException(recoveryStage, "RECOVERY_BOOT_MISMATCH")
                    if (record.mutationBarrier != null) {
                        // 落盘 barrier 表示上一次进程在 release 前后中断，owner 状态不可重放。
                        // 程序侧直接终结这个残留 owner 并继续开新副屏，而不是让每个新 run
                        // 永久收到 MUTATION_UNCERTAIN_NO_REPLAY。
                        val terminated = terminateLatchedOwner(context, s, "recovery_barrier")
                        if (!createIfMissing || !terminated.optBoolean("ok")) return terminated
                        sessions.remove(runId)
                        return startFreshOwner(context, runId, saved, boot)
                    }
                    recoveryStage = "connect"
                    val c = VirtualDisplayOwnerClient.reconnectChecked(AndroidAgentLogger, record.socket, record.pid,
                        record.displayId, record.uniqueId, record.token, record.run)
                    s.client = c
                    recoveryStage = "status"
                    val state = c.status()
                    recoveryStage = "handoff_state"
                    val observed = handoffState(c, state)
                        ?: throw VirtualDisplayRecoveryException(recoveryStage, "RECOVERY_HANDOFF_STATE_INVALID")
                    // 程序侧兜底：上一轮中断留下的闩死态没有合法 finish 路径，直接终结 owner 后
                    // 继续开新副屏，避免模型反复收到 CLEANUP_REQUIRED / RECOVERY_UNCERTAIN。
                    if (VirtualDisplayRecoveryPolicy.ownerTerminallyLatched(observed.flags)) {
                        val terminated = terminateLatchedOwner(context, s, "recovery_latched")
                        if (!createIfMissing || !terminated.optBoolean("ok")) return terminated
                        sessions.remove(runId)
                        return startFreshOwner(context, runId, saved, boot)
                    }
                    val registered = observed.retainedTaskIds
                    recoveryStage = "selection"
                    // A missing kept field is an empty delivery selection, not "every retained task".
                    val restored = record.kept?.let { ids(JSONArray(it))
                        ?: throw VirtualDisplayRecoveryException(recoveryStage, "RECOVERY_SELECTION_INVALID") } ?: emptySet()
                    if (!registered.containsAll(restored)) throw VirtualDisplayRecoveryException(recoveryStage, "RECOVERY_SELECTION_INVALID")
                    s.kept.addAll(restored)
                    s.persisted = true
                    s.cleanupOnly = true
                    s.closedRun = true
                    s.adopted = true
                    s.phase = "held"
                    return body(state).put("recovered", true).put("cleanup_only", true)
                        .put("run_id", runId).put("message", ADOPTED_CLEANUP_ONLY)
                }
            }
        } catch (ex: Exception) {
            val e = VirtualDisplayRecoveryException.classify(recoveryStage, ex)
            AndroidAgentLogger.warn("Virtual display recovery stage=${e.stage} code=${e.code} field=${e.field} type=${e.exceptionType}")
            // 已认证连上 owner、但状态读取/选择校验失败：owner 确实仍占用副屏，且不存在合法
            // finish 路径。程序侧先终结这个残留 owner 再继续开新副屏，而不是把同一个恢复错误
            // 反复抛给模型。未连上（client == null）时身份不可信，不自动终结。
            if (s.client != null && e.stage in setOf("status", "handoff_state", "selection")) {
                val terminated = terminateLatchedOwner(context, s, "recovery_${e.stage}")
                if (!createIfMissing || !terminated.optBoolean("ok")) return terminated
                sessions.remove(runId)
                return startFreshOwner(context, runId, saved, boot)
            }
            // This block authenticates and reads only; it has not sent a handoff or release.
            s.phase = "held"; s.closedRun = true; s.cleanupOnly = true; s.adopted = true
            return reply(false, e.code).put("recovery_stage", e.stage).put("recovery_field", e.field)
                .put("mutation_uncertain", false).put("automatic_retry_allowed", false).also { s.receipt = it }
        }
        // Manual recovery must never create a display merely to close it.
        if (!createIfMissing) {
            sessions.remove(runId)
            return reply(false, "NO_VIRTUAL_SESSION").put("stale_cleared", staleCleared)
        }
        return startFreshOwner(context, runId, saved, boot)
    }
    /** Read only app-owned metadata; never treat the unrelated port-3070 daemon as this owner. */
    @Synchronized fun recoveryStatus(context: Context): JSONObject {
        return try {
            val pending = sessions.values.filter { it.phase != "finished" }
            val saved = recoveryPrefs(context)
            if (pending.isEmpty() && saved.all.isEmpty()) return reply(true).put("present", false)
            val s = pending.singleOrNull()
            val busy = pending.any { !it.closedRun }
            reply(true).put("present", true).put("manager", "eta_owner")
                .put("displayId", s?.client?.displayId ?: saved.getInt("display", -1))
                .put("phase", s?.phase ?: "recovery_pending")
                .put("busy", busy).put("recoverable", !busy && pending.size <= 1)
                .put("lastError", s?.receipt?.optString("error").orEmpty())
                .put("lastDetail", s?.receipt?.optString("message").orEmpty())
        } catch (_: Exception) { reply(false, "RECOVERY_STATE_UNREADABLE") }
    }

    /** Lists only the authenticated current owner, not Android's unrelated displays. */
    @Synchronized fun previewDisplays(context: Context): VirtualDisplayPreviewHttpServer.Displays {
        val result = previewRead(context, null, capture = false)
        return when (result.error) {
            "NO_VIRTUAL_SESSION" -> VirtualDisplayPreviewHttpServer.Displays()
            "" -> VirtualDisplayPreviewHttpServer.Displays(listOf(
                VirtualDisplayPreviewHttpServer.Display(result.displayId, result.uniqueId, result.phase)))
            else -> VirtualDisplayPreviewHttpServer.Displays(error = result.error)
        }
    }

    /** A snapshot only: no start/adoption, GUI observation contract, handoff or release. */
    @Synchronized fun previewFrame(
        context: Context,
        selected: VirtualDisplayPreviewHttpServer.Identity? = null,
    ): VirtualDisplayPreviewHttpServer.Frame = previewRead(context, selected, capture = true)

    /** Called under the Session monitor. A reconnect is borrowed, never installed/adopted. */
    private fun previewRead(
        context: Context,
        selected: VirtualDisplayPreviewHttpServer.Identity?,
        capture: Boolean,
    ): VirtualDisplayPreviewHttpServer.Frame {
        fun denied(code: String) = VirtualDisplayPreviewHttpServer.Frame(error = code)
        if (selected != null && !VirtualDisplayPreviewHttpServer.validIdentity(selected))
            return denied("PREVIEW_IDENTITY_INVALID")
        val pending = sessions.values.filter { it.phase != "finished" }
        if (pending.size > 1) return denied("OWNER_STATE_UNKNOWN")
        val session = pending.singleOrNull()
        var borrowed = false
        var client = session?.client?.takeIf { it.isAlive }
        try {
            if (client == null) {
                val saved = recoveryPrefs(context)
                if (saved.all.isEmpty()) return denied(if (session == null) "NO_VIRTUAL_SESSION" else "RECOVERY_UNCERTAIN")
                val boot = bootId() ?: return denied("BOOT_ID_UNAVAILABLE")
                if (saved.getString("boot", null) != boot) return denied("RECOVERY_UNCERTAIN")
                client = VirtualDisplayOwnerClient.reconnect(AndroidAgentLogger,
                    saved.getString("socket", "").orEmpty(), saved.getLong("pid", -1),
                    saved.getInt("display", -1), saved.getString("unique", "").orEmpty(),
                    saved.getString("token", "").orEmpty(), saved.getString("run", "").orEmpty())
                    ?: return denied("RECOVERY_UNCERTAIN")
                borrowed = true
            }
            val owner = client ?: return denied("RECOVERY_UNCERTAIN")
            val identity = VirtualDisplayPreviewHttpServer.Identity(owner.displayId, owner.uniqueId)
            if (!VirtualDisplayPreviewHttpServer.validIdentity(identity)) return denied("OWNER_STATE_UNKNOWN")
            if (selected != null && selected != identity) return denied("PREVIEW_DISPLAY_GONE")
            fun matchesOwner(response: OwnerResponse): Boolean {
                val state = response.json ?: return false
                return response.ok && state.opt("displayId") == identity.displayId &&
                    state.optString("uniqueId") == identity.uniqueId
            }
            fun sourcePackages(response: OwnerResponse): Set<String>? {
                if (!matchesOwner(response)) return null
                val packages = response.json?.optJSONArray("sourcePackages") ?: return null
                val result = linkedSetOf<String>()
                for (i in 0 until packages.length()) {
                    val pkg = packages.opt(i) as? String ?: return null
                    if (pkg.isBlank()) return null
                    result.add(pkg)
                }
                return result
            }
            val state = owner.status()
            if (!matchesOwner(state)) return denied("OWNER_STATE_UNKNOWN")
            val phase = session?.phase ?: "recovery_pending"
            if (!capture) return VirtualDisplayPreviewHttpServer.Frame(
                displayId = identity.displayId, uniqueId = identity.uniqueId, phase = phase)
            val excluded = session?.previewExcludedPackages.orEmpty() + context.packageName
            val before = sourcePackages(state) ?: return denied("SCREEN_CONTENT_UNKNOWN")
            if (before.isEmpty()) return denied("NO_FRAME")
            if (before.any { it in excluded }) return denied("SCREENSHOT_EXCLUDED_PACKAGE")
            val shot = owner.snapshot()
            if (!shot.ok) return denied("NO_FRAME")
            val afterState = owner.status()
            if (!matchesOwner(afterState)) return denied("OWNER_STATE_UNKNOWN")
            val after = sourcePackages(afterState) ?: return denied("SCREEN_CONTENT_UNKNOWN")
            if (after != before || after.any { it in excluded }) return denied("SCREEN_CONTENT_CHANGED")
            val data = shot.json ?: return denied("FRAME_INVALID")
            val encoded = data.optString("data")
            if (data.opt("displayId") != owner.displayId || data.optString("format") != "png" ||
                encoded.length !in 1..(VirtualDisplayPreviewHttpServer.MAX_FRAME_BYTES * 4 / 3 + 16))
                return denied("FRAME_INVALID")
            val png = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            if (!VirtualDisplayPreviewHttpServer.validPng(png)) return denied("FRAME_INVALID")
            return VirtualDisplayPreviewHttpServer.Frame(png, identity.displayId, phase, uniqueId = identity.uniqueId)
        } catch (_: Exception) {
            return denied("PREVIEW_UNAVAILABLE")
        } finally {
            // Closing a borrowed read connection never releases the display or its owner.
            if (borrowed) client?.close()
        }
    }

    private val manualCloser = VirtualDisplayManualClose()

    @Synchronized fun prepareManualClose(context: Context, selected: VirtualDisplayPreviewHttpServer.Identity): VirtualDisplayManualClose.Result =
        manualCloseOperation(context, selected, null)

    @Synchronized fun commitManualClose(context: Context, selected: VirtualDisplayPreviewHttpServer.Identity, nonce: String): VirtualDisplayManualClose.Result =
        manualCloseOperation(context, selected, nonce)

    /** Entire prepare/commit, including evidence and journal, is under the Session monitor. */
    private fun manualCloseOperation(context: Context, selected: VirtualDisplayPreviewHttpServer.Identity, nonce: String?): VirtualDisplayManualClose.Result {
        fun blocked(code: String) = VirtualDisplayManualClose.Result("blocked", code)
        if (!VirtualDisplayPreviewHttpServer.validIdentity(selected)) return blocked("PREVIEW_IDENTITY_INVALID")
        var borrowed = false
        var client: VirtualDisplayOwnerClient? = null
        var attempted = false
        var stage = "record_read"
        try {
            val saved = recoveryPrefs(context)
            val fields = saved.all
            if (fields.isEmpty()) return blocked("NO_VIRTUAL_SESSION")
            stage = "record_decode"
            val record = VirtualDisplayRecoveryRecord.decode(fields)
            if (record.displayId != selected.displayId || record.uniqueId != selected.uniqueId) return blocked("PREVIEW_DISPLAY_GONE")
            val currentBoot = bootId() ?: return blocked("BOOT_ID_UNAVAILABLE")
            if (record.boot != currentBoot) return blocked("RECOVERY_BOOT_MISMATCH")
            val pending = sessions.values.filter { it.phase != "finished" }
            if (pending.size > 1) return blocked("OWNER_STATE_UNKNOWN")
            val session = pending.singleOrNull()
            if (pending.any { !it.closedRun }) return blocked("ACTIVE_AGENT_OWNER")
            if (record.mutationBarrier != null) return blocked("MUTATION_UNCERTAIN_NO_REPLAY")
            stage = "connect"
            client = session?.client?.takeIf { it.isAlive }
            if (client == null) {
                client = VirtualDisplayOwnerClient.reconnectChecked(AndroidAgentLogger, record.socket, record.pid,
                    record.displayId, record.uniqueId, record.token, record.run)
                borrowed = true
            }
            val owner = requireNotNull(client)
            if (owner.ownerPid != record.pid || owner.socketName != record.socket ||
                owner.displayId != record.displayId || owner.uniqueId != record.uniqueId) return blocked("RECOVERY_OWNER_IDENTITY_MISMATCH")
            stage = "status"
            val backend = object : VirtualDisplayManualClose.Backend {
                override fun evidence(): VirtualDisplayManualClose.Evidence {
                    val nowRecord = VirtualDisplayRecoveryRecord.decode(saved.all)
                    val state = owner.status()
                    val data = state.json
                    val observed = handoffState(owner, state)
                    val sameRecord = nowRecord.key() == record.key() && nowRecord.token == record.token && nowRecord.run == record.run
                    val retainedIds = observed?.retainedTaskIds
                    val liveIds = observed?.liveTaskIds
                    val goneIds = observed?.goneTaskIds
                    val escapedCount = if (retainedIds != null && liveIds != null && goneIds != null)
                        (retainedIds - liveIds - goneIds).size else null
                    return VirtualDisplayManualClose.Evidence(
                        record.key(), bootId(), observed != null && sameRecord,
                        sessions.values.any { !it.closedRun && it.phase != "finished" },
                        data?.opt("sourceState") as? String, data?.opt("sourceTaskCount") as? Int,
                        data?.opt("sourceEmpty") as? Boolean, retainedIds?.size,
                        data?.opt("finishing") as? Boolean, data?.opt("handoffComplete") as? Boolean,
                        data?.opt("releaseAttempted") as? Boolean, data?.opt("mutationUncertain") as? Boolean,
                        nowRecord.mutationBarrier != null || (session?.handoffState != null &&
                            session.receipt?.opt("mutation_uncertain") == true),
                        liveIds?.size, goneIds?.size, escapedCount,
                    )
                }
                override fun markAttempt(): Boolean {
                    val now = VirtualDisplayRecoveryRecord.decode(saved.all)
                    if (now.key() != record.key() || now.token != record.token || now.run != record.run || now.mutationBarrier != null) return false
                    val persisted = saved.edit().putString(VirtualDisplayRecoveryRecord.BARRIER, "manual_release_pending").commit()
                    if (persisted) { attempted = true; session?.handoffBudget?.stop() }
                    return persisted
                }
                /** One empty-taskIds cleanup. No retry; a false result must not release. */
                override fun cleanup(): Boolean {
                    val before = handoffState(owner, owner.status()) ?: return false
                    if (!VirtualDisplayHandoffRetry.freshStateAllowsCleanup(before, before)) return false
                    val handoff = owner.handoff(mapOf("taskIds" to JSONArray()))
                    val completed = VirtualDisplayHandoffEvidence.completed(
                        authenticatedConnection = owner.isAlive && handoff.op == VirtualDisplayOwnerProtocol.OP_HANDOFF,
                        responseOk = handoff.ok,
                        selectedIds = emptySet(),
                        retainedIds = before.retainedTaskIds,
                        keptIds = ids(handoff.json?.optJSONArray("keptTaskIds")),
                        removedIds = ids(handoff.json?.optJSONArray("removedTaskIds")),
                        field = { handoff.json?.opt(it) },
                        goneIds = ids(handoff.json?.optJSONArray("goneTaskIds")),
                    )
                    if (session != null) recordHandoff(session, handoff, completed)
                    return completed
                }
                override fun release(): Boolean = owner.release().ok
                override fun confirmGone(): Boolean {
                    val manager = context.getSystemService(android.hardware.display.DisplayManager::class.java) ?: return false
                    repeat(40) {
                        if (bootId() != record.boot) return false
                        val originalDisplayGone = manager.getDisplay(record.displayId) == null // Reused IDs remain unconfirmed.
                        // Signal zero is a read-only existence check, never a process termination.
                        val originalProcessGone = try {
                            android.system.Os.kill(record.pid.toInt(), 0); false
                        } catch (ex: android.system.ErrnoException) {
                            ex.errno == android.system.OsConstants.ESRCH
                        }
                        if (originalDisplayGone && originalProcessGone) return true
                        Thread.sleep(50)
                    }
                    return false
                }
                override fun clearConfirmed(): Boolean {
                    val now = VirtualDisplayRecoveryRecord.decode(saved.all)
                    if (now.key() != record.key() || now.token != record.token || now.run != record.run ||
                        now.mutationBarrier != "manual_release_pending") return false
                    val cleared = saved.edit().clear().commit()
                    if (cleared) {
                        session?.let {
                            it.phase = "finished"
                            clearPresence(it)
                            it.receipt = reply(true).put("released", true).put("manual_close", true)
                        }
                        owner.close()
                    }
                    return cleared
                }
            }
            return if (nonce == null) manualCloser.prepare(backend) else manualCloser.close(nonce, backend)
        } catch (ex: Exception) {
            val failure = VirtualDisplayRecoveryException.classify(stage, ex)
            AndroidAgentLogger.warn("Virtual display manual close stage=${failure.stage} code=${failure.code} type=${failure.exceptionType}")
            return if (attempted) VirtualDisplayManualClose.Result("closed_unconfirmed", "RELEASE_UNCONFIRMED") else blocked(failure.code)
        } finally {
            if (borrowed) client?.close()
        }
    }

    /** Called only by the settings recovery button, not by automatic run cleanup. */
    @Synchronized fun recoverAndFinishManually(context: Context): JSONObject {
        val state = recoveryStatus(context)
        if (!state.optBoolean("ok")) return state
        if (!state.optBoolean("present")) return reply(false, "NO_VIRTUAL_SESSION")
        if (!state.optBoolean("recoverable")) return reply(false, "VIRTUAL_SESSION_BUSY")
        // A failed reconnect may leave only an in-memory placeholder. Keep the persisted
        // capability intact, but allow the next explicit attempt to authenticate it again.
        val previous = sessions.entries.singleOrNull { it.value.phase != "finished" }
        if (previous != null && previous.value.client == null) sessions.remove(previous.key)
        val runId = "manual-vd-recovery-" + java.util.UUID.randomUUID()
        try {
            val restored = start(context, runId, createIfMissing = false)
            if (!restored.optBoolean("ok")) {
                if (restored.optBoolean("stale_cleared"))
                    return reply(true).put("released", true).put("stale_owner_gone", true)
                        .put("handedOff", false)
                return restored
            }
            return finish(runId, context)
        } finally {
            // There is no Agent run to close this manual operation. Even failed start/finish
            // must leave recovery available instead of permanently reporting BUSY.
            sessions[runId]?.let { it.closedRun = true; it.cleanupOnly = true }
        }
    }

    /**
     * 设置页「强制重置」：用户显式点按后的最后出口，逐级降级并照实上报。
     * 一级：向仍在线且已认证的 owner 发 force release，任务账目核验由 owner 侧强制执行，
     * 已证明滞留主屏的逃逸任务被明确容忍；二级：owner 拒绝或失联时以 root 终结其进程，
     * 副屏随进程被框架回收——与 owner 自行崩溃走同一条 stale 自愈路径。全程不移动任何
     * 任务，拆屏后任务的去向与重启一次完全等价。只有 owner 确认死亡才作废记录与内存
     * 会话（凭证不存在，旧会话无重放路径）；未完成时原样保留，按 FORCE_RESET_INCOMPLETE
     * 报失败，既有恢复路径继续可用。
     */
    @Synchronized fun forceReset(context: Context): JSONObject {
        recoveryContext = context.applicationContext
        val ctx = recoveryContext ?: return reply(false, "RECOVERY_STATE_UNWRITABLE")
        val saved = try { recoveryPrefs(ctx).all } catch (_: Exception) { emptyMap<String, Any?>() }
        val pending = sessions.values.filter { it.phase != "finished" }
        if (saved.isEmpty() && pending.isEmpty()) return reply(false, "NO_VIRTUAL_SESSION")
        // 连接元数据优先取落盘记录，缺字段时回退内存 client 的已知值。
        fun fromClient(value: (VirtualDisplayOwnerClient) -> String): String? =
            pending.firstNotNullOfOrNull { s -> s.client?.let(value) }?.takeIf { it.isNotBlank() }
        val socketName = (saved["socket"] as? String).takeUnless { it.isNullOrBlank() }
            ?: fromClient { it.socketName }.orEmpty()
        val pid = (saved["pid"] as? Long) ?: pending.firstNotNullOfOrNull { it.client?.ownerPid } ?: 0L
        val displayId = (saved["display"] as? Int) ?: pending.firstNotNullOfOrNull { it.client?.displayId } ?: -1
        val uniqueId = (saved["unique"] as? String).takeUnless { it.isNullOrBlank() }
            ?: fromClient { it.uniqueId }.orEmpty()
        val token = (saved["token"] as? String).takeUnless { it.isNullOrBlank() }
            ?: fromClient { it.recoveryToken }.orEmpty()
        val recordRun = (saved["run"] as? String).orEmpty()
        AndroidAgentLogger.warn(
            "Virtual display force reset: conversation=- run=${recordRun.ifBlank { "-" }} dispatch=- " +
                "stage=begin display=$displayId pid=$pid",
        )
        val client = pending.firstNotNullOfOrNull { s -> s.client?.takeIf { it.isAlive } }
            ?: reconnectForReset(socketName, pid, displayId, uniqueId, token, recordRun)
        // 只读证据：owner 视角的任务账目，尽力而为，拿不到也不阻断重置。
        val state = client?.let { c -> runCatching { c.status() }.getOrNull() }
        val evidence = JSONObject()
        for (name in listOf("retainedTaskIds", "liveTaskIds", "goneTaskIds", "escapedTaskIds")) {
            state?.json?.optJSONArray(name)?.let { evidence.put(name, it) }
        }
        // 一级：认证 force release；未确认就记录失败码，便于现场诊断后降级到 root 档。
        var releasedOk = false
        var releaseError = ""
        var remainingOnMain: JSONArray? = null
        if (client != null) {
            val released = runCatching { client.release(force = true) }.getOrNull()
            if (released == null) {
                releaseError = "RELEASE_NO_RESPONSE"
            } else {
                val confirmed = VirtualDisplayHandoffEvidence.released(
                    identity = VirtualDisplayHandoffRetry.OwnerIdentity(
                        client.socketName, client.ownerPid, client.displayId, client.uniqueId,
                    ),
                    authenticatedConnection = true,
                    responseOk = released.ok && released.op == VirtualDisplayOwnerProtocol.OP_RELEASE,
                    field = { released.json?.opt(it) },
                )
                if (confirmed) {
                    releasedOk = true
                    remainingOnMain = released.json?.optJSONArray("remainingOnMainTaskIds")
                } else {
                    releaseError = released.errorCode.takeIf { it.isNotBlank() } ?: "RELEASE_UNCONFIRMED"
                }
            }
        }
        // 二级：root 终结 owner。ESRCH 才算死；su 失败或权限被撤时按未终结照实上报。
        var killedOwner = false
        if (!releasedOk && pid > 0 && !ownerProcessGone(pid)) {
            runCatching { RootSu.process("kill $pid").start().waitFor() }
            killedOwner = awaitProcessGone(pid)
        }
        val processGone = releasedOk || killedOwner || pid <= 0 || ownerProcessGone(pid)
        val displayGone = if (displayId > 0) awaitDisplayGone(ctx, displayId) else false
        // 收尾只在 owner 确认死亡（或已优雅释放）时进行：绝不能清掉记录却留下一个无人
        // 管理的活 owner。未完成时完整保留记录与内存会话，既有恢复路径继续可用。
        var recordCleared = false
        if (processGone) {
            sessions.values.filter { it.phase != "finished" }.forEach { s ->
                s.phase = "finished"
                clearPresence(s)
                runCatching { s.client?.close() }
            }
            sessions.clear()
            recordCleared = runCatching { recoveryPrefs(ctx).edit().clear().commit() }.getOrDefault(false)
        }
        val tier = when {
            releasedOk -> "graceful_release"
            killedOwner -> "owner_terminated"
            processGone -> "owner_gone"
            else -> "incomplete"
        }
        val remainingText = when {
            !releasedOk -> ""
            remainingOnMain == null || remainingOnMain.length() == 0 -> "没有已证明滞留主屏的任务"
            else -> "任务已按原位留在主屏"
        }
        val note = buildString {
            append(
                when {
                    releasedOk -> "副屏已强制释放（未执行归位）"
                    killedOwner -> "owner 进程已终结，副屏由框架回收"
                    processGone -> "owner 已不在线，副屏记录已清除"
                    else -> "强制重置未完全确认，请重试或重启设备后重新发起"
                },
            )
            if (remainingText.isNotBlank()) append("：").append(remainingText)
            if (!processGone && releaseError.isNotBlank()) append("（release：$releaseError）")
            append("；需要新副屏可直接重新调用 start_virtual_session")
            if (!displayGone && displayId > 0) append("；注意：副屏消失未确认（display $displayId）")
        }
        if (releaseError.isNotBlank()) AndroidAgentLogger.warn(
            "Virtual display force reset: conversation=- run=${recordRun.ifBlank { "-" }} dispatch=- " +
                "stage=graceful_release code=$releaseError",
        )
        AndroidAgentLogger.warn(
            "Virtual display force reset: conversation=- run=${recordRun.ifBlank { "-" }} dispatch=- " +
                "stage=done tier=$tier killedOwner=$killedOwner displayGone=$displayGone " +
                "recordCleared=$recordCleared",
        )
        val receipt = if (processGone) reply(true) else reply(false, "FORCE_RESET_INCOMPLETE")
        return receipt.put("forcedReset", true).put("released", releasedOk)
            .put("tier", tier).put("killedOwner", killedOwner).put("displayGone", displayGone)
            .put("recordCleared", recordCleared).put("evidence", evidence).put("message", note)
            .also { r ->
                if (releaseError.isNotBlank()) r.put("releaseError", releaseError)
                remainingOnMain?.let { r.put("remainingOnMainTaskIds", it) }
            }
    }

    /**
     * 程序侧兜底终结闩死的 owner。
     *
     * 触发前提：owner 已被中断的 launch/handoff 闩死，finish 只剩 REFUSE，不存在合法的
     * 归位/释放路径。此时复用设置页的分级强制重置（认证 force release，失败后以 root
     * 终结 owner 进程），全程不移动任务、不重放任何变更；成功则清空恢复记录，失败只保留
     * 一份终态回执。只尝试一次，避免模型或后续 run 反复收到同类不确定错误。
     */
    private fun terminateLatchedOwner(context: Context, s: Session, reason: String): JSONObject {
        s.autoTerminationAttempted = true
        s.handoffBudget.stop()
        AndroidAgentLogger.warn(
            "Virtual display auto termination: conversation=- run=${s.runId.ifBlank { "-" }} " +
                "dispatch=- reason=$reason phase=${s.phase}",
        )
        val reset = runCatching { forceReset(context) }.getOrElse {
            reply(false, "FORCE_RESET_INCOMPLETE").put("tier", "incomplete")
        }
        if (reset.optBoolean("ok")) {
            clearPresence(s)
            runCatching { s.client?.close() }
            s.client = null
            s.phase = "finished"
            val receipt = reply(
                true,
                detail = "上一轮中断残留的副屏会话已由程序自动终结（${reset.optString("tier")}），" +
                    "任务不会被移动；需要副屏可直接重新调用 start_virtual_session",
            ).put("released", true).put("handedOff", false).put("autoTerminated", true)
                .put("tier", reset.optString("tier"))
                .put("recordCleared", reset.optBoolean("recordCleared"))
            // forceReset 成功后会清空 sessions：把本 run 的终态回执放回槽位，
            // 让 deliveryReceipt 与重入的 finish 仍能读到事实结论。
            sessions[s.runId] = s
            AndroidAgentLogger.info(
                "Virtual display auto termination done: run=${s.runId.ifBlank { "-" }} " +
                    "tier=${reset.optString("tier")} recordCleared=${reset.optBoolean("recordCleared")}",
            )
            return receipt.also { s.receipt = it }
        }
        s.phase = "uncertain"
        val receipt = reply(
            false,
            "VIRTUAL_SESSION_STUCK",
            "上一轮中断残留的副屏 owner 无法自动终结，程序已停止重试，不会再重复报同一错误。" +
                "如需继续，请到「设置 - 虚拟屏」执行强制重置，或重启设备后再发起新的副屏会话",
        ).put("autoTerminated", false).put("mutation_uncertain", true)
            .put("automatic_retry_allowed", false).put("tier", reset.optString("tier"))
            .withHandoffAttempts(s)
        sessions[s.runId] = s
        return receipt.also { s.receipt = it }
    }

    /** 自愈后（或首次）创建全新 owner；不会复用被终结的会话。 */
    private fun startFreshOwner(
        context: Context, runId: String, saved: SharedPreferences, boot: String,
    ): JSONObject {
        sessions.remove(runId)
        val s = Session()
        s.runId = runId
        sessions[runId] = s
        return when (val started = VirtualDisplayOwnerClient.start(context, AndroidAgentLogger)) {
            is OwnerStartResult.Failed -> fail(s, started.errorCode)
            is OwnerStartResult.Ready -> {
                val c = started.client
                s.client = c
                s.persisted = runCatching { saved.edit().putString("socket", c.socketName)
                    .putLong("pid", c.ownerPid).putInt("display", c.displayId)
                    .putString("unique", c.uniqueId).putString("token", c.recoveryToken)
                    .putString("run", runId).putString("boot", boot).commit() }.getOrDefault(false)
                if (!s.persisted) return fail(s, "RECOVERY_STATE_UNWRITABLE")
                val state = c.status()
                if (handoffState(c, state) == null) fail(s, "OWNER_STATE_UNKNOWN")
                else {
                    s.phase = "active"
                    val started = body(state)
                    s.width = started.optInt("width", 0)
                    s.height = started.optInt("height", 0)
                    publishPresence(s, started)
                    started.put("run_id", runId)
                }
            }
        }
    }

    /** 强制重置专用的有界重连；失败只降级到 root 档，不抛恢复异常。 */
    private fun reconnectForReset(
        socketName: String, pid: Long, displayId: Int, uniqueId: String, token: String, runId: String,
    ): VirtualDisplayOwnerClient? {
        if (socketName.isBlank() || token.isBlank()) return null
        return VirtualDisplayOwnerClient.reconnect(
            AndroidAgentLogger, socketName, pid, displayId, uniqueId, token,
            runId.ifBlank { "manual-vd-force-reset" },
        )
    }

    /** 有界轮询等待 root kill 生效；线程被中断时按未终结处理。 */
    private fun awaitProcessGone(pid: Long, timeoutMs: Long = 2_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!ownerProcessGone(pid)) {
            if (System.currentTimeMillis() >= deadline) return false
            try { Thread.sleep(150) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return false
            }
        }
        return true
    }

    private fun awaitDisplayGone(context: Context, displayId: Int, timeoutMs: Long = 4_000L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!ownerDisplayGone(context, displayId)) {
            if (System.currentTimeMillis() >= deadline) return false
            try { Thread.sleep(150) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); return false
            }
        }
        return true
    }

    @Synchronized fun keep(runId: String, args: JSONObject): JSONObject {
        val s = sessions[runId] ?: return reply(false, "NO_VIRTUAL_SESSION")
        if (s.abortCleanupAttempted) return reply(false, "SESSION_NOT_ACTIVE")
        if (s.phase != "active" && !s.cleanupOnly) return reply(false, "SESSION_NOT_ACTIVE")
        val wanted = linkedSetOf<Int>()
        if (args.has("task_ids")) wanted.addAll(ids(args.optJSONArray("task_ids"))
            ?: return reply(false, "INVALID_TASK_IDS"))
        val packages = mutableListOf<String>()
        if (args.has("package_name")) packages.add(args.getString("package_name"))
        args.optJSONArray("packages")?.let { a -> for (i in 0 until a.length()) packages.add(a.getString(i)) }
        for (pkg in packages) wanted.addAll(s.packages[pkg] ?: return reply(false, "PACKAGE_NOT_SESSION_OWNED"))
        if (wanted.isEmpty()) return reply(false, "NO_DELIVERY_TASKS")
        val owner = s.client ?: return reply(false, "RECOVERY_UNCERTAIN")
        // 被系统挪走的任务先归位，才能作为可交付的 live 任务；归位不确定时不写交付选择。
        val pre = runCatching { owner.status() }.getOrNull()
        if (pre?.ok == true && escapedTaskIds(pre) > 0) {
            val reclaim = runCatching { owner.reclaim() }.getOrNull()
            if (reclaim != null && !reclaim.ok && reclaim.errorCode == "RECLAIM_UNCERTAIN")
                return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        }
        val state = runCatching { owner.status() }.getOrNull() ?: return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        if (!state.ok) return body(state)
        val data = body(state)
        val known = ids(data.optJSONArray("retainedTaskIds")) ?: return reply(false, "OWNER_TASKS_UNKNOWN")
        if (!known.containsAll(wanted)) return reply(false, "TASK_NOT_SESSION_OWNED")
        // A missing liveTaskIds field stays on the retained-only check. A present field must parse
        // and contain every selected id; gone or escaped ids are not live.
        if (data.has("liveTaskIds")) {
            val live = ids(data.optJSONArray("liveTaskIds")) ?: return reply(false, "OWNER_STATE_UNKNOWN")
            VirtualDisplayHandoffRetry.nonLiveKeepCode(live, wanted)?.let { return reply(false, it) }
        }
        val next = s.kept + wanted
        val context = recoveryContext ?: return reply(false, "RECOVERY_STATE_UNWRITABLE")
        if (!runCatching { recoveryPrefs(context).edit().putString("kept", JSONArray(next).toString()).commit() }.getOrDefault(false))
            return fail(s, "RECOVERY_STATE_UNWRITABLE")
        s.kept.addAll(wanted)
        return reply(true).put("kept_task_ids", JSONArray(s.kept))
    }
    /** Result finalization queries only this run; it must not adopt another conversation. */
    @Synchronized fun deliveryReceipt(runId: String): JSONObject? = sessions[runId]?.receipt
    @Synchronized fun holdOnCancelledRun(runId: String) {
        sessions[runId]?.let { s ->
            s.closedRun = true
            if (s.phase == "active") {
                s.phase = "held"
                clearPresence(s)
            }
        }
    }
    @Synchronized fun finish(runId: String, context: Context? = null): JSONObject {
        val ctx = context ?: recoveryContext
        if (sessions[runId] == null) {
            if (ctx == null) return reply(false, "NO_VIRTUAL_SESSION")
            val hasRecord = runCatching { recoveryPrefs(ctx).all.isNotEmpty() }.getOrDefault(true)
            val held = sessions.values.any { it.closedRun && it.phase != "finished" }
            if (!hasRecord && !held) return reply(false, "NO_VIRTUAL_SESSION")
            val recovered = start(ctx, runId, createIfMissing = false)
            if (!recovered.optBoolean("ok")) {
                // A proven-dead owner has no display left to release and no task to hand off.
                if (recovered.optBoolean("stale_cleared"))
                    return reply(true).put("released", true).put("stale_owner_gone", true)
                        .put("handedOff", false)
                return recovered
            }
        }
        val s = sessions[runId] ?: return reply(false, "NO_VIRTUAL_SESSION")
        if (s.phase == "finished") return s.receipt?.let {
            if (it.opt("ok") == false) withPreservedReceiptExplanation(s, it) else it
        } ?: reply(true).put("already_finished", true).put("released", true)
        // 程序侧兜底：owner 已被中断的 launch/handoff 闩死时，finish 没有合法的归位/释放路径；
        // 直接由程序终结 owner。只尝试一次，失败后复用同一份终态回执，不再反复报同类错误。
        if (ctx != null && !s.autoTerminationAttempted) {
            val client = s.client
            val latched = when {
                client != null -> VirtualDisplayRecoveryPolicy.ownerTerminallyLatched(freshHandoffState(client)?.flags)
                // 运行已结束且没有可用连接：交给程序侧终结残留 owner，避免设备被永久占用。
                s.persisted && s.closedRun -> true
                else -> false
            }
            if (latched) return terminateLatchedOwner(ctx, s, "finish_latched")
        }
        // An ambiguous attempt must not reach either handoff OR release, even after adoption.
        if (s.handoffBudget.blocked || Thread.currentThread().isInterrupted)
            return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        val c = s.client ?: return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        // 每次 finish 重新记录 reclaim 结果，避免旧错误码被回放进新回执。
        s.reclaimCode = null
        s.reclaimCount = 0
        s.reclaimUncertain = false
        // 被系统挪到主屏的任务先尝试归位；归位结果不确定时不再继续 handoff。
        val pre = runCatching { c.status() }.getOrNull()
        if (pre?.ok == true && escapedTaskIds(pre) > 0) {
            val reclaim = runCatching { c.reclaim() }.getOrNull()
            s.reclaimCode = reclaim?.errorCode?.takeIf { it.isNotBlank() }
            s.reclaimCount = reclaim?.json?.optInt("reclaimed", 0) ?: 0
            s.reclaimUncertain = reclaim?.errorCode == "RECLAIM_UNCERTAIN"
            // reclaim 因“焦点任务不能被移动”而拒绝，同样属于焦点阻挡的一次干净拒绝；
            // 它不产生任何移动，也不会把风险带进 handoff 预算。
            if (s.reclaimCode?.startsWith("focus ") == true) {
                s.reclaimFocusRefused = true
                s.focusRejections++
            }
            AndroidAgentLogger.info(
                "Virtual display reclaim: conversation=- run=${runId.ifBlank { "-" }} dispatch=- " +
                    "escaped=${escapedTaskIds(pre)} code=${s.reclaimCode ?: "-"} " +
                    "reclaimed=${s.reclaimCount} uncertain=${s.reclaimUncertain}"
            )
            if (s.reclaimUncertain) return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        }
        val observed = freshHandoffState(c) ?: return failPreservingPrior(s, "OWNER_STATE_UNKNOWN")
        maybeResetHandoffBudgetAfterFocusChange(s, observed)
        val marked = s.kept.toSet()
        val baseline = s.handoffState
        val f = observed.flags
        val action = VirtualDisplayRecoveryPolicy.finishAction(f)
        // A marked task that the system already removed cannot be delivered; deliver the rest.
        // Escaped (moved elsewhere / identity changed) marked tasks still fail closed below.
        val goneDelivery = if (action == VirtualDisplayRecoveryPolicy.Action.HANDOFF)
            observed.goneTaskIds?.let { marked.intersect(it) }.orEmpty() else emptySet()
        val frozen = marked - goneDelivery
        val directRelease = action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && frozen.isEmpty() &&
            f.sourceEmpty && observed.retainedTaskIds.isEmpty()
        val cleanup = action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && frozen.isEmpty() && !directRelease
        // reclaim 被“焦点任务不可移动”拒绝后，逃逸任务仍在主屏。此时空选择 cleanup 的 handoff
        // 必然在“残留 owned task”处失败，而它已经启动锚点、删除了源盘其它任务，会变成不可重放的
        // uncertain。不要发起它：保持干净可重试，连续拒绝后再走独立的 release-only 兜底。
        val escapedObserved = if (observed.liveTaskIds != null && observed.goneTaskIds != null)
            observed.retainedTaskIds - observed.liveTaskIds - observed.goneTaskIds else null
        if (cleanup && s.reclaimFocusRefused && (escapedObserved == null || escapedObserved.isNotEmpty())) {
            if (forcedReleaseEligible(s)) {
                tryForcedRelease(s, c, observed, "reclaim_focus_refused")?.let { return it }
            }
            s.phase = "handoff_pending"
            return reply(
                false,
                "FOCUS_BLOCKED_CLEANUP",
                "主屏焦点占用了逃逸任务，清理无法在不移动它的情况下归位；本次未发起任何移动。" +
                    "请等焦点离开该任务后再次调用 finish_virtual_session；若持续失败，将只关闭副屏并把任务留在主屏",
            ).withHandoffAttempts(s).also { s.receipt = it }
        }
        if (action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && frozen.isNotEmpty()) {
            // Never drop an escaped id out of the selection to make delivery succeed.
            VirtualDisplayHandoffRetry.frozenDeliveryCode(
                observed.liveTaskIds, observed.goneTaskIds, observed.retainedTaskIds, frozen,
            )?.let { return reply(false, it) }
        }
        if (baseline != null) {
            val fresh = if (cleanup) VirtualDisplayHandoffRetry.freshStateAllowsCleanup(baseline, observed)
                else VirtualDisplayHandoffRetry.freshStateAllowsRetry(baseline, observed, frozen)
            if (s.handoffSelection != frozen || !fresh) {
                // 状态已不再“干净”时不允许重试 handoff；但若此前已连续被焦点拒绝、预算耗尽且没有
                // 任何交付承诺，仍可走独立的 release-only 兜底（它不重放任何移动）。
                if (cleanup && forcedReleaseEligible(s)) {
                    tryForcedRelease(s, c, observed, "focus_state_not_fresh")?.let { return it }
                }
                return failPreservingPrior(s, "OWNER_STATE_UNKNOWN")
            }
        }
        if (action == VirtualDisplayRecoveryPolicy.Action.REFUSE)
            return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        val releaseOnly = action == VirtualDisplayRecoveryPolicy.Action.RELEASE_ONLY
        // A completed cleanup-only handoff (possibly from an earlier attempt or process) delivered
        // nothing. Unknown provenance never claims delivery.
        val priorCleanupOnly = releaseOnly &&
            runCatching { c.status().json?.opt("handoffCleanupOnly") }.getOrNull() != false
        var handedOff = releaseOnly && !priorCleanupOnly
        var cleanedUp = releaseOnly && priorCleanupOnly
        var removedForReceipt = emptySet<Int>()
        var goneForReceipt = emptySet<Int>()
        if (action == VirtualDisplayRecoveryPolicy.Action.HANDOFF && (frozen.isNotEmpty() || cleanup)) {
            // Frozen selection: retries never widen it; compare with the pre-attempt baseline.
            // Cleanup uses an empty taskIds list and does not count as delivery.
            val before = baseline ?: observed
            val allowed = if (cleanup) VirtualDisplayHandoffRetry.freshStateAllowsCleanup(before, observed)
                else VirtualDisplayHandoffRetry.freshStateAllowsRetry(before, observed, frozen)
            if (!allowed) {
                if (cleanup && forcedReleaseEligible(s)) {
                    tryForcedRelease(s, c, observed, "focus_state_not_fresh")?.let { return it }
                }
                return failPreservingPrior(s, "OWNER_STATE_UNKNOWN")
            }
            if (!s.handoffBudget.hasRemaining()) {
                // Same round: still a FAILURE, never another handoff/release or a budget reset.
                // The status above is fresh; cached diagnostics alone cannot keep this pending.
                if (forcedReleaseEligible(s)) {
                    tryForcedRelease(s, c, observed, "focus_budget_exhausted")?.let { return it }
                }
                if (s.phase == "handoff_pending" && s.receipt?.opt("ok") == false)
                    return withPreservedReceiptExplanation(s, s.receipt!!)
                return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
            }
            s.handoffState = before
            s.handoffSelection = frozen
            s.phase = "finishing"
            var acceptedRemoved: Set<Int>? = null
            var acceptedGone: Set<Int>? = null
            val outcome = VirtualDisplayHandoffRetry.run(
                budget = s.handoffBudget,
                handoff = {
                    val handoff = c.handoff(mapOf("taskIds" to if (cleanup) JSONArray() else JSONArray(frozen)))
                    val keptIds = ids(handoff.json?.optJSONArray("keptTaskIds"))
                    val removedIds = ids(handoff.json?.optJSONArray("removedTaskIds"))
                    val goneIds = ids(handoff.json?.optJSONArray("goneTaskIds"))
                    val completed = VirtualDisplayHandoffEvidence.completed(
                        authenticatedConnection = c.isAlive && handoff.op == VirtualDisplayOwnerProtocol.OP_HANDOFF,
                        responseOk = handoff.ok,
                        selectedIds = frozen,
                        retainedIds = before.retainedTaskIds,
                        keptIds = keptIds,
                        removedIds = removedIds,
                        field = { handoff.json?.opt(it) },
                        goneIds = goneIds,
                    )
                    recordHandoff(s, handoff, completed)
                    if (completed) {
                        acceptedRemoved = removedIds
                        acceptedGone = goneIds
                        VirtualDisplayHandoffRetry.Attempt.Completed
                    } else VirtualDisplayHandoffEvidence.refused(
                        authenticatedConnection = c.isAlive && handoff.op == VirtualDisplayOwnerProtocol.OP_HANDOFF,
                        responseOk = handoff.ok,
                        code = handoff.errorCode.ifBlank { "HANDOFF_UNCERTAIN" },
                        safeDetail = safeOwnerDetail(handoff),
                        field = { handoff.json?.opt(it) },
                    )
                },
                verifyFresh = {
                    val after = freshHandoffState(c)
                    if (cleanup) VirtualDisplayHandoffRetry.freshStateAllowsCleanup(before, after)
                    else VirtualDisplayHandoffRetry.freshStateAllowsRetry(before, after, frozen)
                },
                delay = { millis -> Thread.sleep(millis) },
            )
            when (outcome) {
                VirtualDisplayHandoffRetry.Outcome.HandedOff -> {
                    if (cleanup) {
                        cleanedUp = true
                        removedForReceipt = acceptedRemoved.orEmpty()
                        goneForReceipt = acceptedGone.orEmpty()
                    } else handedOff = true
                }
                is VirtualDisplayHandoffRetry.Outcome.Stopped -> {
                    // Do not send a PROVEN read-only rejection through fail(), which marks
                    // ambiguity. Unknown/unauthenticated/post-anchor outcomes remain uncertain.
                    val failure = outcome.failure
                    s.phase = outcome.phase
                    s.focusRejections += outcome.focusRejections
                    if (outcome.cleanPreflight && outcome.focusRejections > 0) {
                        s.handoffBudget.markCleanFocusStop()
                    }
                    // 空选择清理被连续 focus 拒绝（例如工作资料占前台）：只释放，不归位。
                    if (cleanup && outcome.cleanPreflight && forcedReleaseEligible(s)) {
                        tryForcedRelease(s, c, observed, "focus_rejected_${s.focusRejections}x")
                            ?.let { return it }
                    }
                    val receipt = if (failure != null) reply(false,
                        VirtualDisplayHandoffDiagnostics.safeHandoffError(failure.code), failure.detail).withHandoffAttempts(s)
                        else s.receipt?.let(VirtualDisplayHandoffDiagnostics::preservedReceipt)
                            ?: reply(false, "HANDOFF_UNCERTAIN").withHandoffAttempts(s)
                    return VirtualDisplayHandoffDiagnostics.annotate(
                        receipt, s.handoffDiagnostics, uncertain = !outcome.cleanPreflight,
                    ).also { s.receipt = it }
                }
            }
        }
        // Re-read identity and phase before release; never replay an uncertain release or handoff.
        // Cleanup still finishes the owner handoff phase, but the receipt handedOff flag stays false.
        val beforeRelease = freshHandoffState(c)
        val latest = beforeRelease?.flags
        val handoffPhaseDone = cleanedUp || handedOff
        if (Thread.currentThread().isInterrupted || beforeRelease == null || latest == null ||
            beforeRelease.identity != observed.identity || beforeRelease.retainedTaskIds != observed.retainedTaskIds ||
            !latest.sourceEmpty || latest.releaseAttempted || latest.mutationUncertain ||
            latest.finishing != handoffPhaseDone || latest.handoffComplete != handoffPhaseDone)
            return fail(s, "RELEASE_UNCERTAIN")
        s.handoffBudget.stop() // No second release, even if its response is lost or malformed.
        // A successful release stops the owner; post-response isAlive is not transport proof.
        val authenticatedReleaseConnection = c.isAlive
        if (!authenticatedReleaseConnection) return fail(s, "RELEASE_UNCERTAIN")
        val releaseContext = ctx ?: return fail(s, "RECOVERY_STATE_UNWRITABLE")
        if (!runCatching { recoveryPrefs(releaseContext).edit()
                .putString(VirtualDisplayRecoveryRecord.BARRIER, "automatic_release_pending").commit() }.getOrDefault(false))
            return fail(s, "RECOVERY_STATE_UNWRITABLE")
        val released = try { c.release() }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return fail(s, "RELEASE_UNCERTAIN") }
            catch (_: Exception) { return fail(s, "RELEASE_UNCERTAIN") }
        if (!VirtualDisplayHandoffEvidence.released(observed.identity, authenticatedReleaseConnection,
                released.ok && released.op == VirtualDisplayOwnerProtocol.OP_RELEASE, { released.json?.opt(it) }))
            return fail(s, released.errorCode.ifBlank { "RELEASE_UNCERTAIN" }, safeOwnerDetail(released))
        val receipt = clearReleased(ctx, s).put("handedOff", handedOff)
        if (goneDelivery.isNotEmpty()) receipt.put("goneDeliveryTaskIds", JSONArray(goneDelivery))
        if (cleanedUp) {
            receipt.put("cleanedUp", true)
                .put("removedTaskIds", JSONArray(removedForReceipt))
                .put("goneTaskIds", JSONArray(goneForReceipt))
        }
        AndroidAgentLogger.info(
            "Virtual display handoff terminal: conversation=- run=${runId.ifBlank { "-" }} dispatch=- " +
                "state=finished handedOff=$handedOff cleanedUp=$cleanedUp",
        )
        return receipt.withHandoffAttempts(s).also { s.receipt = it }
    }
    /** Finalize only this failed/cancelled run, never adopt a different run's recovery owner. */
    @Synchronized fun onRunAborted(context: Context, runId: String): JSONObject? {
        val s = sessions[runId] ?: return null
        s.closedRun = true
        AndroidAgentLogger.info(
            "Virtual display handoff terminal: conversation=- run=${runId.ifBlank { "-" }} dispatch=- state=aborted",
        )
        if (s.phase == "finished") return s.receipt
            ?: reply(false, "ABORT_CLEANUP_UNVERIFIED").put("released", false)
        if (s.abortCleanupAttempted) return s.receipt
            ?: reply(false, "ABORT_CLEANUP_UNVERIFIED").put("released", false)
        // start() can bind a held/recovered owner from another run to this runId. Do not
        // erase that owner's marks or remove its tasks merely because this later run failed.
        // 程序侧兜底：收养会话被闩死时没有合法清理路径，先终结 owner，
        // 避免调用方反复收到 RECOVERY_REQUIRED。
        if (s.cleanupOnly && !s.autoTerminationAttempted && s.client?.let {
                VirtualDisplayRecoveryPolicy.ownerTerminallyLatched(freshHandoffState(it)?.flags)
            } == true)
            return terminateLatchedOwner(context, s, "abort_cleanup_only")
        if (s.cleanupOnly) return reply(false, "RECOVERY_REQUIRED").put("released", false)
        s.abortCleanupAttempted = true
        // A failed/ambiguous previous handoff must not be replayed or get a different selection.
        if (s.handoffBudget.blocked || s.handoffState != null || s.handoffSelection != null)
            return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        if (!s.persisted) return fail(s, "RECOVERY_STATE_UNWRITABLE")
        val c = s.client
        if (c == null) {
            // 运行已结束且没有可用连接：交给程序侧终结残留 owner，避免设备被永久占用。
            return terminateLatchedOwner(context, s, "abort_no_client")
        }
        // 归位失败会让后来的清理因“任务已逃逸”而失败；归位结果不确定时直接保持恢复态。
        val pre = runCatching { c.status() }.getOrNull()
        if (pre?.ok == true && escapedTaskIds(pre) > 0) {
            val reclaim = runCatching { c.reclaim() }.getOrNull()
            s.reclaimCode = reclaim?.errorCode?.takeIf { it.isNotBlank() }
            s.reclaimCount = reclaim?.json?.optInt("reclaimed", 0) ?: 0
            s.reclaimUncertain = reclaim?.errorCode == "RECLAIM_UNCERTAIN"
            AndroidAgentLogger.info(
                "Virtual display reclaim: conversation=- run=${runId.ifBlank { "-" }} dispatch=- " +
                    "escaped=${escapedTaskIds(pre)} code=${s.reclaimCode ?: "-"} " +
                    "reclaimed=${s.reclaimCount} uncertain=${s.reclaimUncertain}",
            )
            if (s.reclaimUncertain) return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        }
        val observed = freshHandoffState(c) ?: return failPreservingPrior(s, "OWNER_STATE_UNKNOWN")
        val f = observed.flags
        if (f.finishing || f.handoffComplete || f.releaseAttempted || f.mutationUncertain) {
            // 程序侧兜底：闩死态没有合法清理路径；仅完成 handoff 的空源盘仍交给 finish 正常释放。
            if (VirtualDisplayRecoveryPolicy.ownerTerminallyLatched(f))
                return terminateLatchedOwner(context, s, "abort_latched")
            return failPreservingPrior(s, "RECOVERY_UNCERTAIN")
        }
        // Explicit keep is a delivery selection, not authorization to deliver an unfinished run.
        // Persist empty selection before cleanup so process recovery cannot re-promote it.
        val cleared = runCatching { recoveryPrefs(context).edit()
            .putString("kept", "[]").commit() }.getOrDefault(false)
        if (!cleared) return fail(s, "RECOVERY_STATE_UNWRITABLE")
        s.kept.clear()
        s.cleanupOnly = true
        s.phase = "held"
        return try { finish(runId, context) }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(s, "AUTO_CLEANUP_INTERRUPTED")
        } catch (_: Exception) { fail(s, "AUTO_CLEANUP_FAILED") }
    }

    /** Called once when the owning run closes; explicit delivery choices are preserved. */
    @Synchronized fun onRunClosed(context: Context, runId: String) {
        val s = sessions[runId] ?: return
        s.closedRun = true
        if (s.phase == "finished") return
        // 被系统挪回主屏的任务先归位，否则会被当成“不在副屏”而在结束清理时移除。
        val client = s.client
        if (client != null) {
            val pre = runCatching { client.status() }.getOrNull()
            if (pre?.ok == true && escapedTaskIds(pre) > 0) runCatching { client.reclaim() }
        }
        if (s.kept.isEmpty() && !s.cleanupOnly) {
            // Only tasks this run launched and that status still shows as live are delivery tasks.
            // An unknown live inventory must not promote every package task.
            val live = s.client?.let { freshHandoffState(it)?.liveTaskIds }
            if (live != null) {
                val owned = s.packages.values.flatten().toSet()
                val promoted = owned.intersect(live)
                // Persist before use, like keep(): a restart must not turn this delivery into cleanup.
                if (promoted.isNotEmpty()) {
                    val persisted = runCatching { recoveryPrefs(context).edit()
                        .putString("kept", JSONArray(promoted).toString()).commit() }.getOrDefault(false)
                    if (!persisted) {
                        s.receipt = reply(false, "RECOVERY_STATE_UNWRITABLE")
                        AndroidAgentLogger.warn("Virtual display auto-finish failed; recovery retained")
                        return
                    }
                    s.kept.addAll(promoted)
                }
            }
        }
        if (s.client == null) {
            // 运行已结束且没有可用连接：交给程序侧终结残留 owner，而不是留下永久占用。
            if (s.persisted) {
                s.receipt = terminateLatchedOwner(context, s, "run_closed_no_client")
                return
            }
            failPreservingPrior(s, "RECOVERY_UNCERTAIN")
            return
        }
        s.receipt = runCatching { finish(runId, context) }.getOrElse { fail(s, "AUTO_FINISH_FAILED") }
        if (!s.receipt!!.optBoolean("ok")) AndroidAgentLogger.warn("Virtual display auto-finish failed; recovery retained")
    }
    @Synchronized fun executeGui(context: Context,runId: String,tool: String,args: JSONObject, excludedPackages: Set<String> = emptySet()): AgentModelClient.ToolResult {
        fun text(obj: JSONObject)=AgentModelClient.ToolResult(obj.put("tool",tool).put("display","virtual").toString())
        if(tool !in setOf("observe_screen","launch_app","wait","tap","tap_area","swipe","long_press","press_key","paste_text","input_text"))return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY",UNSUPPORTED_GUI_MESSAGE))
        if(tool=="press_key" && args.optString("button") !in setOf("BACK","ENTER","PASTE"))return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY",UNSUPPORTED_KEY_MESSAGE)
            .put("requested_button", args.optString("button").take(24)).put("supported_buttons", JSONArray(listOf("BACK","ENTER","PASTE")))
            .put("alternative", "设置 - 虚拟屏：恢复/关闭副屏；或退出后台虚拟屏模式"))
        if(tool=="input_text" && args.optString("mode","append")!="append")return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY",UNSUPPORTED_TEXT_MODE_MESSAGE))
        if(tool=="launch_app" && (args.optString("package_name").isBlank() || args.optString("package_name") in excludedPackages))return text(reply(false,"PACKAGE_NOT_ALLOWED"))
        if(sessions[runId]==null && tool !in setOf("launch_app","observe_screen"))return text(reply(false,"NO_VIRTUAL_SESSION"))
        if(sessions[runId]==null){
            val created=start(context,runId)
            if(!created.optBoolean("ok"))return text(created)
            // 首次尝试就自动接管了上一轮会话：先明确告诉模型下一步，避免再调用一次 start 才发现。
            if(created.optBoolean("cleanup_only"))
                return text(reply(false,"VIRTUAL_SESSION_CLEANUP_REQUIRED",CLEANUP_REQUIRED_MESSAGE)
                    .put("cleanup_only", true).put("phase", created.optString("phase")))
        }
        val s=sessions[runId]?:return text(reply(false,"NO_VIRTUAL_SESSION"))
        s.previewExcludedPackages.addAll(excludedPackages)
        if(s.phase!="active" || s.cleanupOnly || !s.persisted) {
            // 程序侧兜底：收养的会话被闩死时先终结 owner，再让调用方重新 start；
            // 只返回一次结论，不再让模型反复收到同类 CLEANUP_REQUIRED。
            if(s.cleanupOnly && !s.autoTerminationAttempted && s.client?.let {
                    VirtualDisplayRecoveryPolicy.ownerTerminallyLatched(freshHandoffState(it)?.flags)
                } == true) {
                val terminated=terminateLatchedOwner(context,s,"gui_cleanup_required")
                return text(if(terminated.optBoolean("ok"))
                    reply(false,"VIRTUAL_SESSION_RESET",
                        "上一轮中断残留的副屏会话已由程序自动清理，请重新调用 start_virtual_session 开始新的副屏会话")
                        .put("autoTerminated", true).put("phase","finished")
                else terminated)
            }
            return text(reply(false,"VIRTUAL_SESSION_CLEANUP_REQUIRED",CLEANUP_REQUIRED_MESSAGE)
                .put("cleanup_only", s.cleanupOnly).put("phase", s.phase))
        }
        val c=s.client!!
        try {
            if(tool=="observe_screen") {
                //观察失败不得保留上一帧的有效坐标：先作废，成功登记新帧后才能再次输入。
                s.observation.invalidate()
                var visible=c.status()
                if(!visible.ok)return text(body(visible))
                // 厂商显示策略可能把任务挪回主屏；先归位，归位失败则不发布任何坐标。
                if(escapedTaskIds(visible)>0) {
                    val reclaim=c.reclaim()
                    if(!reclaim.ok)return text(reply(false,"TASK_ESCAPED",reclaim.errorCode))
                    visible=c.status()
                    if(!visible.ok)return text(body(visible))
                }
                val packages=body(visible).optJSONArray("sourcePackages")
                if(packages==null)return text(reply(false,"SCREEN_CONTENT_UNKNOWN"))
                // An empty owner display may otherwise return a mirrored main-display frame or a
                // blank surface. Never publish coordinates for content the virtual display does not own.
                if(packages.length()==0)return text(reply(false,"NO_VISIBLE_CONTENT","副屏当前没有可观察的内容，请先启动目标应用"))
                if((0 until packages.length()).any { packages.getString(it) in excludedPackages })return text(reply(false,"SCREENSHOT_EXCLUDED_PACKAGE"))
                publishPresence(s, body(visible))
                val shot=c.snapshot();if(!shot.ok)return text(body(shot))
                val data=body(shot);val encoded=data.optString("data")
                val frameWidth=data.optInt("width",0);val frameHeight=data.optInt("height",0)
                if(encoded.isBlank() || frameWidth<=0 || frameHeight<=0)return text(reply(false,"NO_FRAME"))
                val capture=try {
                    VirtualDisplayScreenCapture.capture(encoded,frameWidth,frameHeight)
                } catch(e:Exception) {
                    return text(reply(false,"FRAME_ENCODE_FAILED",e.javaClass.simpleName))
                }
                s.observation.record(capture.contract)
                data.remove("data");data.remove("width");data.remove("height");data.remove("bytes");data.remove("format")
                return AgentModelClient.ToolResult(
                    data.put("tool",tool)
                        .put("screen",JSONObject().put("width",capture.contract.screenWidth).put("height",capture.contract.screenHeight))
                        .put("screenshot",JSONObject().put("width",capture.contract.screenshotWidth)
                            .put("height",capture.contract.screenshotHeight)
                            .put("mime_type",capture.image.mimeType)
                            .put("bytes",capture.image.bytes))
                        .put("coordinate_contract",capture.contract.toContractJson())
                        .put("ui_nodes",JSONArray())
                        .put("note","虚拟屏截图坐标默认按 screenshot 空间映射到副屏像素；screen 空间显式直通；仅截图模式，不支持节点操作")
                        .toString(),
                    listOf(capture.image))
            }
            if(tool=="launch_app") {
                val pkg=args.optString("package_name")
                if(pkg in excludedPackages)return text(reply(false,"SCREENSHOT_EXCLUDED_PACKAGE"))
                if(pkg.isBlank())return text(reply(false,"PACKAGE_NAME_REQUIRED","先 search_apps 获取精确包名"))
                val component=context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
                    ?:return text(reply(false,"APP_NOT_LAUNCHABLE"))
                val before=c.status();if(!before.ok)return text(body(before))
                val previous=body(before).optJSONArray("retainedTaskIds")?:JSONArray()
                val prior=(0 until previous.length()).map { previous.getInt(it) }.toSet()
                s.observation.invalidate()
                val launched=c.launch(component)
                if(launched.ok) {
                    val payload=body(launched)
                    // reused=true switches back to this session's existing task. current-prior is
                    // empty, so do not replace the package's recorded task ids with that diff.
                    if(payload.opt("reused")!=true) {
                        val ids=payload.optJSONArray("taskIds")?:return text(reply(false,"LAUNCH_TASKS_UNKNOWN"))
                        val current=(0 until ids.length()).map{ids.getInt(it)}.toSet()
                        s.packages[pkg]=(s.packages[pkg]?:emptySet())+(current-prior)
                    }
                    //新任务意味着画面已变，必须重新观察后才能用坐标。
                    s.observation.invalidate()
                    publishPresence(s, null, pkg)
                }
                return text(body(launched))
            }
            if(tool=="wait") {Thread.sleep(args.optLong("duration_ms",1000).coerceIn(100,30000));return text(reply(true))}
            if (tool in setOf("tap", "tap_area", "swipe", "long_press")) {
                s.observation.require()
                val live = c.status()
                if (!live.ok) {
                    s.observation.invalidate()
                    return text(reply(false,"VIRTUAL_FRAME_UNKNOWN"))
                }
                // 任务被系统挪回主屏时，副屏上的坐标输入没有落点：先归位，并要求重新观察。
                if (escapedTaskIds(live) > 0) {
                    val reclaim = c.reclaim()
                    s.observation.invalidate()
                    if (!reclaim.ok) return text(reply(false,"TASK_ESCAPED",reclaim.errorCode))
                    return text(reply(false,"RECLAIMED_REOBSERVE","应用已被系统挪走，已移回副屏；请重新观察后再操作"))
                }
                s.observation.validateFrame(body(live).optInt("width", 0), body(live).optInt("height", 0))
            }
            val fields=linkedMapOf<String,Any?>()
            val space=args.optString("coordinate_space").takeIf { it.isNotBlank() }
            when(tool) {
                "tap","tap_area" -> {
                    val point=if(tool=="tap") {
                        s.observation.resolve(space,args.getInt("x"),args.getInt("y"))
                    } else {
                        //tap_area：两个端点都必须有效，中心取映射后两端点的中点。
                        s.observation.resolveArea(space,args.getInt("x1"),args.getInt("y1"),args.getInt("x2"),args.getInt("y2"))
                    }
                    fields.putAll(mapOf("kind" to "tap","x" to point.x,"y" to point.y))
                }
                "swipe","long_press" -> {
                    val x1Key=if(tool=="swipe")"x1" else "x";val y1Key=if(tool=="swipe")"y1" else "y"
                    val first=s.observation.resolve(space,args.getInt(x1Key),args.getInt(y1Key))
                    val second=if(tool=="swipe") s.observation.resolve(space,args.getInt("x2"),args.getInt("y2")) else first
                    fields.putAll(mapOf("kind" to "swipe","x1" to first.x,"y1" to first.y,"x2" to second.x,"y2" to second.y,"durationMs" to args.optInt("duration_ms",500).coerceIn(100,3000)))
                }
                "press_key" -> {
                    val key=mapOf("BACK" to 4,"ENTER" to 66,"PASTE" to 279)[args.getString("button")]
                        ?:return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY",UNSUPPORTED_KEY_MESSAGE))
                    fields.putAll(mapOf("kind" to "key","keyCode" to key))
                }
                "paste_text","input_text" -> {
                    if(tool=="input_text" && args.optString("mode","append")!="append")return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY",UNSUPPORTED_TEXT_MODE_MESSAGE))
                    s.observation.require()
                    val value=args.getString("text");require(value.length<=20000)
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("",value))
                    fields.putAll(mapOf("kind" to "key","keyCode" to 279))
                }
                else -> return text(reply(false,"UNSUPPORTED_ON_VIRTUAL_DISPLAY","不会回退到主屏操作"))
            }
            val input = c.input(fields)
            if (input.errorCode in setOf("VIRTUAL_FRAME_CHANGED", "VIRTUAL_FRAME_UNKNOWN")) s.observation.invalidate()
            return text(body(input))
        }catch(e:VirtualDisplayCoordinateRejection){return text(reply(false,e.code,e.message.orEmpty()))}
        catch(e:Exception){return text(reply(false,"VIRTUAL_OPERATION_FAILED",e.javaClass.simpleName))}
    }
    /** 胶囊展开时抓取一帧实况；截图失败只返回 null，绝不改变会话状态。 */
    fun capturePreview(runId: String): ImageBitmap? {
        // 抓帧较慢，锁外进行，避免拖住 agent 的 observe_screen / 输入。
        val c = synchronized(this) {
            val s = sessions[runId] ?: return null
            if (s.phase != "active" || s.cleanupOnly || !s.persisted) return null
            s.client
        } ?: return null
        val shot = try { c.snapshot(timeoutMillis = 10_000L) } catch (_: Exception) { return null }
        if (!shot.ok) return null
        val encoded = body(shot).optString("data")
        if (encoded.isBlank()) return null
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    private fun stageTarget(runId: String): Pair<Session, VirtualDisplayOwnerClient>? {
        val s = sessions[runId] ?: return null
        if (s.phase != "active" || s.cleanupOnly || !s.persisted || s.width <= 0 || s.height <= 0) return null
        return (s to (s.client ?: return null))
    }
    /** 任务被系统挪走时先归位；返回非空表示本次手势不应投递。 */
    private fun reclaimBeforeGesture(c: VirtualDisplayOwnerClient, runId: String): JSONObject? {
        val state = runCatching { c.status() }.getOrNull()
        if (state == null || !state.ok || escapedTaskIds(state) == 0) return null
        val reclaim = runCatching { c.reclaim() }.getOrNull()
        if (reclaim == null) {
            AndroidAgentLogger.info(
                "Virtual display reclaim: conversation=- run=${runId.ifBlank { "-" }} dispatch=- " +
                    "escaped=${escapedTaskIds(state)} code=NO_RESPONSE reclaimed=0 uncertain=false",
            )
            return reply(false, "RECLAIM_FAILED")
        }
        AndroidAgentLogger.info(
            "Virtual display reclaim: conversation=- run=${runId.ifBlank { "-" }} dispatch=- " +
                "escaped=${escapedTaskIds(state)} code=${reclaim.errorCode.ifBlank { "-" }} " +
                "reclaimed=${reclaim.json?.optInt("reclaimed", 0) ?: 0} uncertain=false",
        )
        return if (reclaim.ok) null else body(reclaim)
    }
    /** 胶囊画布手势：归一化坐标按副屏像素换算后直接投递给 owner。 */
    @Synchronized fun dispatchUserTap(runId: String, nx: Float, ny: Float, longPress: Boolean): JSONObject {
        val (s, c) = stageTarget(runId) ?: return reply(false, "SESSION_NOT_ACTIVE")
        reclaimBeforeGesture(c, runId)?.let { return it }
        val x = (nx.coerceIn(0f, 1f) * (s.width - 1)).roundToInt().coerceIn(0, s.width - 1)
        val y = (ny.coerceIn(0f, 1f) * (s.height - 1)).roundToInt().coerceIn(0, s.height - 1)
        val fields = if (longPress) {
            linkedMapOf<String, Any?>("kind" to "swipe", "x1" to x, "y1" to y, "x2" to x, "y2" to y,
                "durationMs" to 650)
        } else {
            linkedMapOf<String, Any?>("kind" to "tap", "x" to x, "y" to y)
        }
        return body(c.input(fields))
    }
    @Synchronized fun dispatchUserSwipe(
        runId: String, sx: Float, sy: Float, ex: Float, ey: Float, durationMs: Int,
    ): JSONObject {
        val (s, c) = stageTarget(runId) ?: return reply(false, "SESSION_NOT_ACTIVE")
        reclaimBeforeGesture(c, runId)?.let { return it }
        fun px(n: Float, size: Int) =
            (n.coerceIn(0f, 1f) * (size - 1)).roundToInt().coerceIn(0, size - 1)
        return body(c.input(linkedMapOf(
            "kind" to "swipe",
            "x1" to px(sx, s.width), "y1" to px(sy, s.height),
            "x2" to px(ex, s.width), "y2" to px(ey, s.height),
            "durationMs" to durationMs.coerceIn(100, 3000),
        )))
    }
    fun onRunStarted(): Nothing = throw VirtualDisplayHandoffNotReadyException()
    fun onRunFinished(): Nothing = throw VirtualDisplayHandoffNotReadyException()
    fun engage(): Nothing = throw VirtualDisplayHandoffNotReadyException()
    fun keep(packageName: String): Nothing = throw VirtualDisplayHandoffNotReadyException()
}
internal class VirtualDisplayHandoffNotReadyException : IllegalStateException(VirtualDisplaySession.NOT_READY)
