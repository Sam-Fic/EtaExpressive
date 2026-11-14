package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentSummaryFormatTest {
    private fun summary(names: List<String> = AgentSummaryFormat.SUMMARY_SECTIONS) =
        "[对话摘要]\n" + names.joinToString("\n") { "## $it\n- (none)" }
    @Test fun everyMissingSectionDuplicateReorderUnknownAndEmptyFailsClosed() {
        val headings = AgentSummaryFormat.SUMMARY_SECTIONS
        headings.indices.forEach { index ->
            assertNull(AgentSummaryFormat.coerceSummary(summary(headings.filterIndexed { i, _ -> i != index })))
        }
        assertNull(AgentSummaryFormat.coerceSummary(summary(headings + headings.last())))
        assertNull(AgentSummaryFormat.coerceSummary(summary(headings.reversed())))
        assertNull(AgentSummaryFormat.coerceSummary(summary() + "\n## Extra\n- x"))
        assertNull(AgentSummaryFormat.coerceSummary(summary().replace("## Next Step", "## Next Step invented")))
        assertNull(AgentSummaryFormat.coerceSummary(summary().replace("## Current Work\n- (none)", "## Current Work")))
        assertNull(AgentSummaryFormat.coerceSummary("## Current Work\n- 已完成"))
    }
    @Test fun supportedChineseAliasesAndOuterFenceStillWork() {
        val aliases = listOf("主要请求与意图", "关键技术概念", "文件与代码", "错误与修复", "待办工作", "当前工作", "下一步", "关键上下文", "关键实体与线索")
        assertEquals(summary(), AgentSummaryFormat.coerceSummary("```markdown\n" + summary(aliases) + "\n```"))
    }
    @Test fun fencedCodeHeadingsAndNestedSectionsAreContentNotSchema() {
        val body = summary().replace("## Files and Code\n- (none)", "## Files and Code\n```markdown\n## Current Work\ncode\n```\n### Source snippet\n- detail")
        val normal = AgentSummaryFormat.coerceSummary(body)
        assertNotNull(normal); assertTrue(normal!!.contains("## Current Work\ncode"))
        assertTrue(normal.contains("### Source snippet"))
        assertNull(AgentSummaryFormat.coerceSummary(summary() + "\n```\nunclosed"))
    }
}
