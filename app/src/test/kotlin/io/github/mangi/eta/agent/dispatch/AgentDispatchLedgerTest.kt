package io.github.mangi.eta.agent.dispatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调度债权账本的状态机契约：notify-once、忙时排队、终态幂等。
 * 不变量与 AgentDispatchLedger 头注释一一对应，这里逐条钉住。
 */
class AgentDispatchLedgerTest {

    private fun request(id: String, conversationId: String = "conv-1") =
        AgentDispatchLedger.DispatchRequest(
            dispatchId = id,
            conversationId = conversationId,
            brief = "brief $id",
            originText = "origin $id",
        )

    @Test
    fun idleTargetStartsImmediatelyAndSettlesOnce() {
        val ledger = AgentDispatchLedger()
        val admission = ledger.admit(request("d1"), conversationBusy = false)
        assertTrue(admission is AgentDispatchLedger.Admission.Start)
        assertNotNull(ledger.markArmed("d1", "run-1", 1L))

        val settlement = ledger.settleRun("run-1", AgentDispatchLedger.Outcome.COMPLETED, detail = "")
        assertNotNull(settlement)
        assertEquals("d1", settlement!!.claim.request.dispatchId)
        assertNull(settlement.next)
        // notify-once：同一 run 再次结算不产生通知。
        assertNull(ledger.settleRun("run-1", AgentDispatchLedger.Outcome.COMPLETED, detail = ""))
        assertEquals(0, ledger.armedCount)
    }

    @Test
    fun busyTargetQueuesAndDrainsOnSettle() {
        val ledger = AgentDispatchLedger()
        assertTrue(ledger.admit(request("d1"), conversationBusy = true) is AgentDispatchLedger.Admission.Queued)
        val second = ledger.admit(request("d2"), conversationBusy = true)
        assertTrue(second is AgentDispatchLedger.Admission.Queued)
        assertEquals(2, (second as AgentDispatchLedger.Admission.Queued).queueDepth)

        // 忙时 markArmed 未知 id（还没排到）返回 null，调用方不得起 run。
        assertNull(ledger.markArmed("d2", "run-2", 1L))

        // 排队项被拉出后武装。
        val next = ledger.drain("conv-1")
        assertEquals("d1", next?.dispatchId)
        assertNotNull(ledger.markArmed("d1", "run-1", 2L))

        val settlement = ledger.settleRun("run-1", AgentDispatchLedger.Outcome.COMPLETED, detail = "")
        assertEquals("d2", settlement?.next?.dispatchId)
    }

    @Test
    fun readmittingArmedDispatchIsIdempotent() {
        val ledger = AgentDispatchLedger()
        assertTrue(ledger.admit(request("d1"), conversationBusy = false) is AgentDispatchLedger.Admission.Start)
        assertNotNull(ledger.markArmed("d1", "run-1", 1L))
        // 重入受理返回 Start，但 markArmed 返回已存在的 claim，调用方据此跳过起 run。
        assertTrue(ledger.admit(request("d1"), conversationBusy = false) is AgentDispatchLedger.Admission.Start)
        val claim = ledger.markArmed("d1", "run-other", 2L)
        assertEquals("run-1", claim?.runId)
        assertEquals(1, ledger.armedCount)
    }

    @Test
    fun unknownRunSettlesSilently() {
        val ledger = AgentDispatchLedger()
        assertNull(ledger.settleRun("run-unknown", AgentDispatchLedger.Outcome.FAILED, detail = "boom"))
    }

    @Test
    fun cancelConversationDropsQueuedOnly() {
        val ledger = AgentDispatchLedger()
        assertTrue(ledger.admit(request("d1", "conv-1"), conversationBusy = true) is AgentDispatchLedger.Admission.Queued)
        assertTrue(ledger.admit(request("d2", "conv-2"), conversationBusy = true) is AgentDispatchLedger.Admission.Queued)

        val dropped = ledger.cancelConversation("conv-1")
        assertEquals(listOf("d1"), dropped.map { it.dispatchId })
        // 已丢弃的不再被 drain 交出。
        assertNull(ledger.drain("conv-1"))
        assertEquals(listOf("d2"), ledger.drain("conv-2")?.let { listOf(it.dispatchId) })
    }

    @Test
    fun pendingWorkTracksArmedAndQueued() {
        val ledger = AgentDispatchLedger()
        assertTrue(ledger.admit(request("d1"), conversationBusy = true) is AgentDispatchLedger.Admission.Queued)
        assertTrue(ledger.hasPendingWork("conv-1"))
        assertTrue(ledger.admit(request("d2", "conv-2"), conversationBusy = false) is AgentDispatchLedger.Admission.Start)
        ledger.markArmed("d2", "run-2", 1L)
        assertTrue(ledger.hasPendingWork("conv-2"))
        ledger.settleRun("run-2", AgentDispatchLedger.Outcome.STOPPED, detail = "")
        assertTrue(!ledger.hasPendingWork("conv-2"))
    }

    @Test
    fun restoreHandsOutForRecovery() {
        val ledger = AgentDispatchLedger()
        // 重启对账通道：restore 后 markArmed 可受理（与 drain 交出同态）。
        ledger.restore(request("d1"))
        assertNotNull(ledger.markArmed("d1", "run-1", 1L))
        val settlement = ledger.settleRun("run-1", AgentDispatchLedger.Outcome.COMPLETED, detail = "")
        assertEquals("d1", settlement?.claim?.request?.dispatchId)
    }

    @Test
    fun emptyBriefIsRejected() {
        val ledger = AgentDispatchLedger()
        val admission = ledger.admit(
            AgentDispatchLedger.DispatchRequest("d1", "conv-1", brief = "   ", originText = ""),
            conversationBusy = false,
        )
        assertTrue(admission is AgentDispatchLedger.Admission.Rejected)
    }
}
