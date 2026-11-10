package io.github.mangi.eta.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemoryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun utf8LimitIsMeasuredInBytesAndFailedWritePreservesOldFile() {
        val store = store()
        val original = store.replaceAll("安全内容")

        assertEquals(12, original.byteSize)
        assertThrows(AgentMemoryException::class.java) {
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES + 1))
        }

        assertEquals(original, store.snapshot())
        assertEquals(
            AgentMemoryStore.MAX_FILE_BYTES,
            store.replaceAll("a".repeat(AgentMemoryStore.MAX_FILE_BYTES)).byteSize,
        )
    }

    @Test
    fun supportsPagingCaseInsensitiveSearchAndLineMetadata() {
        val store = store()
        store.replaceAll("# 核心记忆\n喜欢 Kotlin\n## 项目\nEta Agent\n其他")

        val page = store.read(startLine = 3, maxChars = 20)
        assertEquals(3, page.startLine)
        assertTrue(page.content.startsWith("3: ## 项目"))
        assertTrue(page.hasMore)

        val search = store.read(query = "eta agent", maxChars = 200)
        assertEquals(1, search.matchedLines)
        assertTrue(search.content.contains("4: Eta Agent"))
        assertTrue(search.content.contains("3: ## 项目"))
        assertTrue(search.content.contains("5: 其他"))
        assertFalse(search.hasMore)
    }

    @Test
    fun mutationsAreAtomicRevisionCheckedAndCanDeleteOrClear() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n旧偏好\n## 项目\n旧项目")

        val replaced = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = initial.revision,
                startLine = 2,
                endLine = 2,
                content = "新偏好",
            ),
        ) as AgentMemoryWriteResult.Success
        assertEquals("# 核心记忆\n新偏好\n## 项目\n旧项目", replaced.snapshot.content)

        val conflict = store.mutate(
            AgentMemoryMutation.Append(initial.revision, "## 冲突追加"),
        ) as AgentMemoryWriteResult.Conflict
        assertEquals(replaced.snapshot.revision, conflict.snapshot.revision)
        assertFalse(store.snapshot().content.contains("冲突追加"))

        val appended = store.mutate(
            AgentMemoryMutation.Append(replaced.snapshot.revision, "## 新章节\n内容"),
        ) as AgentMemoryWriteResult.Success
        val deleted = store.mutate(
            AgentMemoryMutation.ReplaceRange(
                revision = appended.snapshot.revision,
                startLine = 4,
                endLine = 4,
                content = "",
            ),
        ) as AgentMemoryWriteResult.Success
        assertFalse(deleted.snapshot.content.contains("旧项目"))

        val cleared = store.mutate(
            AgentMemoryMutation.Clear(deleted.snapshot.revision),
        ) as AgentMemoryWriteResult.Success
        assertEquals("", cleared.snapshot.content)
        assertEquals(0, cleared.snapshot.byteSize)
        assertEquals(0, cleared.snapshot.lineCount)
    }

    @Test
    fun replaceSectionReadsAndRewritesWholeSectionByHeading() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n旧偏好\n## 项目\n旧项目\n# 其他\n保留")

        val section = store.readSection("项目", maxChars = 200)
        assertEquals("## 项目", section.section)
        assertEquals(3, section.startLine)
        assertEquals(4, section.endLine)
        assertTrue(section.content.contains("旧项目"))

        val replaced = store.mutate(
            AgentMemoryMutation.ReplaceSection(
                revision = initial.revision,
                section = "## 项目",
                content = "## 项目\n新项目",
            ),
        ) as AgentMemoryWriteResult.Success
        assertTrue(replaced.changed)
        assertEquals("# 核心记忆\n旧偏好\n## 项目\n新项目\n# 其他\n保留", replaced.snapshot.content)

        val deleted = store.mutate(
            AgentMemoryMutation.ReplaceSection(
                revision = replaced.snapshot.revision,
                section = "项目",
                content = "",
            ),
        ) as AgentMemoryWriteResult.Success
        // 第一个 ReplaceSection 只动了「## 项目」节，「旧偏好」从未被改写，
        // 删除该节后应原样保留（此前误写作「新偏好」）。
        assertEquals("# 核心记忆\n旧偏好\n# 其他\n保留", deleted.snapshot.content)
    }

    @Test
    fun replaceSectionRejectsMissingOrAmbiguousHeadings() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n# 项目\nA\n# 工作\n# 项目\nB")

        assertThrows(AgentMemoryException::class.java) {
            store.mutate(AgentMemoryMutation.ReplaceSection(initial.revision, "不存在", "x"))
        }
        val ambiguous = assertThrows(AgentMemoryException::class.java) {
            store.mutate(AgentMemoryMutation.ReplaceSection(initial.revision, "项目", "x"))
        }
        assertEquals("MEMORY_SECTION_AMBIGUOUS", ambiguous.code)
    }

    @Test
    fun replaceSectionRequiresHeadingLineForLevelOneSection() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n旧偏好")

        val failure = assertThrows(AgentMemoryException::class.java) {
            store.mutate(AgentMemoryMutation.ReplaceSection(initial.revision, "核心记忆", "只有正文"))
        }
        assertEquals("MEMORY_SECTION_HEADING_REQUIRED", failure.code)
    }

    @Test
    fun appendRejectsDuplicateLevelOneHeadingAndReportsUnchangedWrites() {
        val store = store()
        val initial = store.replaceAll("# 核心记忆\n旧偏好\n# 项目\n旧项目")

        val failure = assertThrows(AgentMemoryException::class.java) {
            store.mutate(AgentMemoryMutation.Append(initial.revision, "# 核心记忆\n重复"))
        }
        assertEquals("MEMORY_DUPLICATE_HEADING", failure.code)
        assertEquals(initial, store.snapshot())

        val unchanged = store.mutate(
            AgentMemoryMutation.ReplaceSection(initial.revision, "项目", "# 项目\n旧项目"),
        ) as AgentMemoryWriteResult.Success
        assertFalse(unchanged.changed)
        assertEquals(initial.content, unchanged.snapshot.content)
    }

    private fun store(): AgentMemoryStore = AgentMemoryStore(temporaryFolder.newFolder())
}
