package io.github.mangi.eta.agent.runtime

import org.junit.Test
import org.junit.Assert.*

class ChildTaskOrdinaryDispatchSelectionTest {
    private fun candidate(role: String, value: String) = ChildTaskConfigPolicy.Candidate(
        worker = ChildTaskConfigPolicy.WorkerKey("owner", "worker-1", role),
        availability = ChildTaskConfigPolicy.Availability.AVAILABLE,
        configurationRevision = value,
        configuration = value,
    )

    @Test fun changedRoleAndModelApplyToNewPlanWithoutChangingPreviousPlan() {
        val previous = ChildTaskOrdinaryDispatchSelection.plan(listOf(candidate("implementation", "old")))
        val current = listOf(candidate("review", "new"))
        val plan = ChildTaskOrdinaryDispatchSelection.plan(current)
        assertSame(current, plan.ordinary)
        assertSame(current, plan.replacement)
        assertEquals("review", plan.ordinary.single().worker.role)
        assertEquals("implementation", previous.ordinary.single().worker.role)
        assertEquals("old", previous.ordinary.single().configuration)
    }

    @Test fun unavailableCurrentSelectionDoesNotUsePreviousConfiguration() {
        val current = listOf(ChildTaskConfigPolicy.Candidate<String>(
            ChildTaskConfigPolicy.WorkerKey("owner", "worker-1", "review"),
            ChildTaskConfigPolicy.Availability.MODEL_UNAVAILABLE))
        val plan = ChildTaskOrdinaryDispatchSelection.plan(current)
        assertSame(current, plan.ordinary)
        assertNull(plan.ordinary.single().configuration)
    }

    @Test fun removedWorkersDoNotReappearInNewPlan() {
        val plan = ChildTaskOrdinaryDispatchSelection.plan(emptyList<ChildTaskConfigPolicy.Candidate<String>>())
        assertTrue(plan.ordinary.isEmpty())
        assertSame(plan.ordinary, plan.replacement)
    }
}
