package io.github.mangi.eta.agent.runtime

/**
 * Configuration selection only; never stops, resumes, claims or dispatches a task.
 *
 * Evidence must come from the owning child runtime, not tool arguments or a parent RunFailed event.
 * Callers retain the complete immutable snapshot for as long as a task/dispatch group is retained.
 * A successful successor decision is NOT a dispatch permit: the runtime must atomically recheck
 * the evidence and reserve its existing predecessor claim before enqueueing (including on retry).
 */
internal object ChildTaskConfigPolicy {
    data class WorkerKey(val ownerId: String, val workerId: String, val role: String) {
        init { require(ownerId.isNotBlank() && workerId.isNotBlank() && role.isNotBlank()) }
    }

    data class TaskKey(val worker: WorkerKey, val generationId: String, val taskId: String) {
        init { require(generationId.isNotBlank() && taskId.isNotBlank()) }
    }

    // Not data classes: default toString must not print configurations/credentials.
    class Snapshot<C : Any>(
        val worker: WorkerKey,
        val generationId: String,
        val configurationRevision: String,
        val configuration: C,
    ) {
        init { require(generationId.isNotBlank() && configurationRevision.isNotBlank()) }
    }

    enum class Availability(val reason: String) {
        AVAILABLE("The explicitly selected child configuration is available."),
        DELEGATION_DISABLED("Child delegation is disabled in the current user configuration."),
        WORKER_DISABLED("The selected child worker is disabled."),
        WORKER_REMOVED("The original child worker no longer exists; another worker is not a substitute."),
        SELECTION_MISSING("The selected child provider or model is missing."),
        PROVIDER_UNAVAILABLE("The explicitly selected provider is missing or disabled."),
        MODEL_UNAVAILABLE("The explicitly selected model is missing or disabled."),
        ROLE_INCOMPATIBLE("The selected model does not support this worker role."),
        CREDENTIALS_MISSING("The selected child configuration has no usable credentials."),
        ENDPOINT_MISSING("The selected child configuration has no endpoint."),
        MODEL_NAME_MISSING("The selected child configuration has no API model name."),
        SELECTION_CHANGED_DURING_RESOLUTION("The selected model changed while resolving; read the configuration again."),
        INVALID_CONFIGURATION("The selected child configuration is invalid; no alternative was selected."),
        RESOLUTION_FAILED("The selected child configuration could not be resolved; no alternative was selected."),
    }

    class Candidate<C : Any>(
        val worker: WorkerKey,
        val availability: Availability,
        val configurationRevision: String? = null,
        val configuration: C? = null,
    ) {
        init {
            require((availability == Availability.AVAILABLE) == (configuration != null))
            require(configuration == null || !configurationRevision.isNullOrBlank())
        }
    }

    enum class Source { NONE, FROZEN, CURRENT, EXPLICIT_SUCCESSOR }
    enum class Code(val reason: String) {
        ORIGINAL_CONFIGURATION_FROZEN("Retained child work uses its original model, reasoning and configuration snapshot."),
        CURRENT_CONFIGURATION_AVAILABLE("No retained child snapshot applies; use only this explicitly selected configuration."),
        EXPLICIT_SUCCESSOR_AVAILABLE("A stopped faulty child has a changed user configuration; a linked successor may be explicitly claimed."),
        FROZEN_CONFIGURATION_MISSING("Retained child work has no verified configuration snapshot; do not use the latest configuration instead."),
        OWNER_MISMATCH("Configuration and task belong to different owners."),
        WORKER_MISMATCH("A different worker must not inherit this child's configuration or failure evidence."),
        ROLE_MISMATCH("A different role must not inherit this child's configuration or failure evidence."),
        TASK_MISMATCH("Failure or handoff evidence does not refer to the exact predecessor task."),
        GENERATION_MISMATCH("Configuration or evidence belongs to a different child generation."),
        CHILD_EVIDENCE_REQUIRED("Read authoritative evidence for this child; a parent failure is not child failure evidence."),
        HANDOFF_NOT_READ("Read the current predecessor result/checkpoint before considering a successor."),
        STOP_NOT_CONFIRMED("The old child execution/preparation has not been confirmed stopped."),
        TASK_NOT_CONTINUABLE("Only a retained child at a supported pause boundary can continue; cancelled/finished tasks are not revived."),
        CHILD_NOT_FAULTY("A healthy, paused or parent-disconnected child cannot switch configuration via replace_task_id."),
        REPLACEMENT_NOT_SUPPORTED("The child runtime does not support replacement for this state."),
        SUCCESSOR_ALREADY_CLAIMED("A successor is already claimed or its dispatch outcome is unknown; do not dispatch again."),
        MEDIA_RESULT_UNCERTAIN("Paid media delivery/result is uncertain; do not replay it."),
        MEDIA_REPLAY_NOT_SUPPORTED("Automatic replacement of a media task is not supported, even after configuration edits."),
        WORKSPACE_REVIEW_REQUIRED("An implementation/workspace task requires the existing manual review and isolated handoff path, not direct replacement."),
        CONFIGURATION_UNCHANGED("The user has not changed this worker's relevant configuration."),
        NEW_CONFIGURATION_UNAVAILABLE("The explicitly selected new configuration is unavailable; do not silently choose another model."),
    }

    class Decision<C : Any> internal constructor(
        val source: Source,
        val code: Code,
        val configuration: C? = null,
        val predecessor: TaskKey? = null,
        val availability: Availability? = null,
    ) {
        val available: Boolean get() = configuration != null
        val reason: String get() = availability?.let { "${code.reason} ${it.reason}" } ?: code.reason
    }

    /** observationVersion must change when the task's status/result/stop evidence changes. */
    data class Evidence(
        val task: TaskKey,
        val taskRole: String,
        val status: String,
        val errorCode: String = "",
        val observationVersion: Long,
        val handoffReadVersion: Long? = null,
        val executionStopped: Boolean = false,
        val canReplace: Boolean = false,
        val successorClaimed: Boolean = false,
        val mediaResultUncertain: Boolean = false,
        val workspaceId: String? = null,
        val workspacePath: String = "",
    )

    /**
     * Select configuration for retained work, not new-task dispatch. Latest is deliberately lazy:
     * do not re-resolve credentials, reasoning defaults or model selections under an old task.
     * retainedTasks=true with no snapshot fails closed (e.g. incomplete restored state).
     */
    fun <C : Any> ordinary(
        worker: WorkerKey,
        frozen: Snapshot<C>?,
        retainedTasks: Boolean,
        latest: () -> Candidate<C>,
    ): Decision<C> {
        if (frozen != null) {
            mismatch(worker, frozen.worker)?.let { return denied(it) }
            return Decision(Source.FROZEN, Code.ORIGINAL_CONFIGURATION_FROZEN, frozen.configuration)
        }
        if (retainedTasks) return denied(Code.FROZEN_CONFIGURATION_MISSING)
        val candidate = latest()
        mismatch(worker, candidate.worker)?.let { return denied(it) }
        if (candidate.availability != Availability.AVAILABLE) return unavailable(candidate.availability)
        return Decision(Source.CURRENT, Code.CURRENT_CONFIGURATION_AVAILABLE, candidate.configuration)
    }

    /** Continue is always same-task/same-snapshot, never successor creation. */
    fun <C : Any> continuation(
        task: TaskKey,
        frozen: Snapshot<C>,
        evidence: Evidence?,
    ): Decision<C> {
        validate(task, frozen, evidence)?.let { return denied(it) }
        val observed = requireNotNull(evidence)
        if (observed.status != "awaiting_decision") return denied(Code.TASK_NOT_CONTINUABLE)
        if (observed.successorClaimed) return denied(Code.SUCCESSOR_ALREADY_CLAIMED)
        if (observed.mediaResultUncertain) return denied(Code.MEDIA_RESULT_UNCERTAIN)
        if (observed.taskRole in mediaRoles) return denied(Code.MEDIA_REPLAY_NOT_SUPPORTED)
        return Decision(Source.FROZEN, Code.ORIGINAL_CONFIGURATION_FROZEN, frozen.configuration)
    }

    /**
     * Called only for an explicit replace_task_id. The latest candidate must be the SAME stable
     * worker ID/role. Provider equality is neither necessary nor sufficient: changing the model,
     * reasoning, credentials or endpoint of that worker can be a relevant user configuration edit.
     * configurationRevision is derived from user settings, not parent status or refreshed tokens.
     */
    fun <C : Any> successor(
        predecessor: TaskKey,
        successorWorker: WorkerKey,
        successorRole: String,
        frozen: Snapshot<C>,
        evidence: Evidence?,
        latest: () -> Candidate<C>,
    ): Decision<C> {
        mismatch(predecessor.worker, successorWorker)?.let { return denied(it) }
        validate(predecessor, frozen, evidence)?.let { return denied(it) }
        val observed = requireNotNull(evidence)
        if (successorRole != observed.taskRole) return denied(Code.ROLE_MISMATCH)
        if (observed.successorClaimed) return denied(Code.SUCCESSOR_ALREADY_CLAIMED)
        if (observed.mediaResultUncertain) return denied(Code.MEDIA_RESULT_UNCERTAIN)
        if (observed.taskRole in mediaRoles) return denied(Code.MEDIA_REPLAY_NOT_SUPPORTED)
        // Preserve Coordinator's workspace_id != null restriction and the group-level isolation gate.
        if (observed.workspaceId != null || observed.workspacePath.isNotBlank() || observed.taskRole == "implementation")
            return denied(Code.WORKSPACE_REVIEW_REQUIRED)
        val supportedBlock = (observed.status == "awaiting_decision" && observed.errorCode == "SUB_AGENT_NO_PROGRESS") ||
            (observed.status == "cancelled" && observed.errorCode == "REPLACED_AFTER_BLOCK")
        if (observed.status != "failed" && !supportedBlock) return denied(Code.CHILD_NOT_FAULTY)
        if (!observed.canReplace) return denied(Code.REPLACEMENT_NOT_SUPPORTED)
        if (observed.handoffReadVersion == null || observed.handoffReadVersion != observed.observationVersion)
            return denied(Code.HANDOFF_NOT_READ)
        if (!observed.executionStopped) return denied(Code.STOP_NOT_CONFIRMED)
        val candidate = latest()
        mismatch(predecessor.worker, candidate.worker)?.let { return denied(it) }
        if (candidate.availability != Availability.AVAILABLE) return unavailable(candidate.availability)
        if (candidate.configurationRevision == frozen.configurationRevision) return denied(Code.CONFIGURATION_UNCHANGED)
        return Decision(Source.EXPLICIT_SUCCESSOR, Code.EXPLICIT_SUCCESSOR_AVAILABLE,
            candidate.configuration, predecessor, candidate.availability)
    }

    private val mediaRoles = setOf("image_generation", "video_generation")
    private fun mismatch(expected: WorkerKey, actual: WorkerKey): Code? = when {
        expected.ownerId != actual.ownerId -> Code.OWNER_MISMATCH
        expected.workerId != actual.workerId -> Code.WORKER_MISMATCH
        expected.role != actual.role -> Code.ROLE_MISMATCH
        else -> null
    }

    private fun <C : Any> validate(task: TaskKey, frozen: Snapshot<C>, evidence: Evidence?): Code? {
        mismatch(task.worker, frozen.worker)?.let { return it }
        if (task.generationId != frozen.generationId) return Code.GENERATION_MISMATCH
        if (evidence == null) return Code.CHILD_EVIDENCE_REQUIRED
        mismatch(task.worker, evidence.task.worker)?.let { return it }
        if (task.generationId != evidence.task.generationId) return Code.GENERATION_MISMATCH
        if (task.taskId != evidence.task.taskId) return Code.TASK_MISMATCH
        return null
    }

    private fun <C : Any> denied(code: Code): Decision<C> = Decision(Source.NONE, code)
    private fun <C : Any> unavailable(availability: Availability): Decision<C> =
        Decision(Source.NONE, Code.NEW_CONFIGURATION_UNAVAILABLE, availability = availability)
}
