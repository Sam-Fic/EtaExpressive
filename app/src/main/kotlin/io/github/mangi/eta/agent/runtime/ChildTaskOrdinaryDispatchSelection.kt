package io.github.mangi.eta.agent.runtime

/** New tasks use this run's current settings; existing task IDs keep their owning group's snapshot. */
internal object ChildTaskOrdinaryDispatchSelection {
    data class Plan<C : Any>(
        val ordinary: List<ChildTaskConfigPolicy.Candidate<C>>,
        val replacement: List<ChildTaskConfigPolicy.Candidate<C>>,
    )

    fun <C : Any> plan(current: List<ChildTaskConfigPolicy.Candidate<C>>): Plan<C> =
        Plan(current, current)
}
