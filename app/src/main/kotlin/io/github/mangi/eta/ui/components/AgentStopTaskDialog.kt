package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentChildRunControl
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions

/** The parent is already ending and its captured children are already paused before this opens. */
@Composable
internal fun AgentStopTaskDialog(
    onPauseChildren: () -> Unit,
    onStopChildren: () -> Unit,
    onDismiss: () -> Unit,
) {
    EtaDialog(
        show = true,
        title = stringResource(R.string.ast_title),
        onDismissRequest = onDismiss,
        summary = stringResource(R.string.ast_body),
        actions = {
            // 两个动作都不是「取消」：暂停语义与 onDismissRequest（等同暂停）一致，放取消位。
            EtaDialogActions(
                confirmText = stringResource(R.string.ast_stop),
                onConfirm = onStopChildren,
                cancelText = stringResource(R.string.ast_pause),
                onCancel = onPauseChildren,
            )
        },
    )
}

/**
 * Mount once in a visible conversation host, not on the old background-task management button.
 * Absence of this host never resolves a choice. A new turn does not invalidate an old event, and
 * confirming an old event never captures the current generation or replays a setting change.
 */
@Composable
internal fun AgentPendingChildStopDialog(ownerId: String?) {
    val pending by AgentChildRunControl.pendingSelections.collectAsState()
    val selection = pending.firstOrNull { choice ->
        ownerId != null && choice.targets.any { it.ownerId == ownerId }
    } ?: return
    AgentStopTaskDialog(
        onPauseChildren = { AgentChildRunControl.resolve(selection.eventId) },
        onStopChildren = { AgentChildRunControl.resolve(selection.eventId, stopChildren = true) },
        onDismiss = { AgentChildRunControl.resolve(selection.eventId) },
    )
}

/** Legacy snapshot value retained for callers migrating away from the removed range dialog. */
internal data class AgentStopSelection<T>(
    val ownerId: String,
    val runId: String?,
    val groupTarget: T?,
) {
    fun stillCurrent(ownerId: String?, runId: String?, groupTarget: T?): Boolean =
        this.ownerId == ownerId && this.runId == runId && this.groupTarget == groupTarget
}
