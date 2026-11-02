package io.github.mangi.eta.agent.memory

import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryContextBuilderTest {
    @Test
    fun coreBudgetTracksWindowWithSafeUnknownFallback() {
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(null))
        assertEquals(8_000, AgentMemoryContextBuilder.coreBudgetChars(128_000))
        assertEquals(16_000, AgentMemoryContextBuilder.coreBudgetChars(256_000))
        assertEquals(62_500, AgentMemoryContextBuilder.coreBudgetChars(1_000_000))
        assertEquals(4_000, AgentMemoryContextBuilder.coreBudgetChars(16_000))
    }

    @Test
    fun injectsOnlyCoreSectionAndBoundedHeadingIndex() {
        val content = "# 核心记忆\n长期偏好\n## 关系\n家人\n# 详细背景\n不应自动注入"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals("# 核心记忆\n长期偏好\n## 关系\n家人", context.coreContent)
        assertFalse(context.coreContent.contains("不应自动注入"))
        assertEquals("# 核心记忆\n## 关系\n# 详细背景", context.headingIndex)
        assertFalse(context.coreTruncated)
        assertEquals(1, context.coreSectionCount)
        assertEquals(4, context.coreTotalLines)
        assertEquals(4, context.coreInjectedLines)
    }

    @Test
    fun mergesEveryCoreSectionInsteadOfOnlyTheFirst() {
        val content = "# 核心记忆\n偏好A\n# 其他\n不应注入\n# 核心记忆\n偏好B"
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals("# 核心记忆\n偏好A\n# 核心记忆\n偏好B", context.coreContent)
        assertFalse(context.coreContent.contains("不应注入"))
        assertEquals(2, context.coreSectionCount)
        assertEquals(4, context.coreTotalLines)
        assertFalse(context.coreTruncated)
    }

    @Test
    fun truncatesAtLineBoundaryAndReportsWhereToContinue() {
        val content = buildString {
            append("# 核心记忆")
            repeat(12) {
                append('\n').append("b".repeat(700))
            }
            append("\n# 详细背景\n不应自动注入")
        }
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertEquals(12, context.coreInjectedLines)
        assertEquals(13, context.coreTotalLines)
        assertTrue(context.coreTruncated)
        assertEquals(13, context.coreNextLine)
        // 不切半行：注入内容必须以完整的 700 字符行结尾，而不是被截断的片段。
        assertEquals("b".repeat(700), context.coreContent.lines().last())
        assertFalse(context.coreContent.contains("详细背景"))
    }

    @Test
    fun oversizedSingleLineIsNotCutMidLineButReportedForReRead() {
        val content = "# 核心记忆\n" + "a".repeat(10_000)
        val snapshot = snapshot(content)
        val context = AgentMemoryContextBuilder.build(snapshot, null)

        assertEquals("# 核心记忆", context.coreContent)
        assertTrue(context.coreTruncated)
        assertEquals(1, context.coreInjectedLines)
        assertEquals(2, context.coreNextLine)
        assertEquals(snapshot.revision, context.revision)
    }

    @Test
    fun detailsWithoutCoreHeadingAreIndexedButNotAutomaticallyInjected() {
        val context = AgentMemoryContextBuilder.build(
            snapshot("# 项目\n只应按需读取的细节"),
            256_000,
        )

        assertEquals("", context.coreContent)
        assertEquals("# 项目", context.headingIndex)
        assertEquals(0, context.coreSectionCount)
    }

    @Test
    fun headingWarningReportsDuplicatesAndTruncation() {
        val content = buildString {
            append("# 核心记忆\n偏好")
            repeat(100) { index ->
                append('\n').append("# 标题").append(index).append('x'.repeat(60))
            }
            append("\n# 标题5").append('x'.repeat(60))
        }
        val context = AgentMemoryContextBuilder.build(snapshot(content), 128_000)

        assertTrue(context.headingIndex.isNotBlank())
        assertTrue(context.headingWarning.contains("同名标题出现多次"))
        assertTrue(context.headingWarning.contains("标题索引已截断"))
    }

    private fun snapshot(content: String): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = "a".repeat(64),
        byteSize = content.toByteArray(Charsets.UTF_8).size,
        lineCount = content.lines().size,
    )
}
