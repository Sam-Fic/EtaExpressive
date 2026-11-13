"""Production-wiring contracts; direct Kotlin tests cover lease lifetime behavior."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/agent/runtime'


class ChildToolOwnershipLeaseContract(unittest.TestCase):
    def test_groups_receive_the_same_idempotent_lease_as_failure_cleanup(self):
        executor = (ROOT / 'AgentRuntimeRunExecutor.kt').read_text()
        factory = executor.split('fun createChildGroup(', 1)[1].split('// Frozen configuration', 1)[0]
        self.assertIn('val childLease = checkNotNull(ownership.retain())', factory)
        self.assertLess(factory.index('ownership.retain()'), factory.index('SubAgentCoordinator(childModels'))
        self.assertIn('releaseTools = childLease::close', factory)
        self.assertIn('finally { childLease.close() }', factory)
        self.assertNotIn('releaseTools = { ownership.release() }', factory)
        self.assertNotIn('AgentChildToolOwnership.releaseChild', factory)

    def test_parent_cancel_and_finally_keep_the_same_parent_release(self):
        executor = (ROOT / 'AgentRuntimeRunExecutor.kt').read_text()
        # 取消回调先关入口/桥接执行器，再释放与 finally 路径同一份 ownership。
        self.assertIn('toolsBinding = runController.register {', executor)
        self.assertIn('entryExecutor?.close()\n                xiaomiToolsBridgeExecutor?.close()\n                ownership.release()', executor)
        self.assertIn('runCatching { toolsOwner?.release() }', executor)
        self.assertIn('registeredChildGenerations.forEach(AgentChildTaskGroups::detach)', executor)

    def test_both_configuration_groups_remain_distinct_when_needed(self):
        executor = (ROOT / 'AgentRuntimeRunExecutor.kt').read_text()
        self.assertIn('groupGeneration = createChildGroup(childDispatchPlan.ordinary)', executor)
        self.assertIn('else createChildGroup(childDispatchPlan.replacement)', executor)
        self.assertIn('currentRunId = request.runId', executor)
        self.assertIn('replacementGeneration = replacementGeneration', executor)

    def test_owner_tracks_exact_leases_and_refuses_new_groups_after_parent_release(self):
        owner = (ROOT / 'AgentChildToolOwnership.kt').read_text()
        self.assertIn('mutableSetOf<ChildLease>()', owner)
        self.assertIn('if (closed || !parentHeld) return null', owner)
        self.assertIn('if (!children.remove(lease)) return', owner)
        self.assertIn('if (parentHeld || children.isNotEmpty() || closed) return false', owner)
        self.assertNotIn('ThreadLocal', owner)
        self.assertNotIn('childHeld', owner)

    def test_registry_invokes_release_token_without_thread_local_role(self):
        registry = (ROOT / 'AgentChildTaskGroups.kt').read_text()
        self.assertIn('release = group.releaseTools.also { group.releaseTools = null }', registry)
        self.assertIn('release?.let { runCatching { it() } }', registry)
        self.assertNotIn('AgentChildToolOwnership.releaseChild', registry)


if __name__ == '__main__':
    unittest.main()
