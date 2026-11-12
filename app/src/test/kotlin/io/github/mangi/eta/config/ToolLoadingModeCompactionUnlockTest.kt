package io.github.mangi.eta.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 档位会话锁的压缩边界例外。
 *
 * 档位在会话首个 run 锁死，那时历史是空的；而转发档的收益随历史长度增长。
 * 压缩本来就要重付整段历史，因此在那里放开锁、让下一个 run 重读用户偏好，代价最小。
 * 这里钉住「只放开用户已经改过的设置」：不自动升档，默认 search 的会话行为不变。
 *
 * 同时钉住存量 suffix 偏好的读时迁移：它映射到 search（超集档），不回写。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolLoadingModeCompactionUnlockTest {
    @Before fun resetPreferences() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        // localAgent 是进程级单例，initLocal 只在 null 时赋值：同 sandbox 的其它测试类
        // 可能留下旧实例与旧值。按 Prefs 自己持有的那份清空，再重跑一次迁移补回标记。
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        Prefs.initLocal(context)
    }

    @Test fun modeStaysLockedUntilACompactionBoundaryThenRelocksOnce() {
        assertEquals(Prefs.Keys.TOOL_LOADING_MODE_SEARCH, Prefs.resolveToolLoadingMode("conv-a"))
        Prefs.setToolLoadingMode(Prefs.Keys.TOOL_LOADING_MODE_GROUP)
        // 没有压缩边界：改了设置也不生效，会话锁语义不变。
        assertEquals(Prefs.Keys.TOOL_LOADING_MODE_SEARCH, Prefs.resolveToolLoadingMode("conv-a"))

        Prefs.unlockToolLoadingModeAtCompaction("conv-a")
        assertEquals(Prefs.Keys.TOOL_LOADING_MODE_GROUP, Prefs.resolveToolLoadingMode("conv-a"))

        // 标记只消费一次：重新锁死，之后改设置不再立刻生效。
        Prefs.setToolLoadingMode(Prefs.Keys.TOOL_LOADING_MODE_OFF)
        assertEquals(Prefs.Keys.TOOL_LOADING_MODE_GROUP, Prefs.resolveToolLoadingMode("conv-a"))
    }

    @Test fun aLegacySuffixPreferenceResolvesToSearch() {
        // 存量 suffix 偏好：读时映射到 search（转发底座 + 工具级搜索的超集档），不回写。
        Prefs.setToolLoadingMode(Prefs.Keys.TOOL_LOADING_MODE_SUFFIX)
        assertEquals(Prefs.Keys.TOOL_LOADING_MODE_SEARCH, Prefs.toolLoadingMode())
        assertEquals(Prefs.Keys.TOOL_LOADING_MODE_SEARCH, Prefs.resolveToolLoadingMode("conv-a"))
        assertEquals(
            "迁移不回写：存储里仍是原值，回滚旧版仍认识它",
            Prefs.Keys.TOOL_LOADING_MODE_SUFFIX,
            Prefs.getString(Prefs.Keys.AGENT_TOOL_LOADING_MODE),
        )
        // 会话锁同样落 search：run 侧按三档语义取布尔量。
        Prefs.unlockToolLoadingModeAtCompaction("conv-a")
        assertEquals(Prefs.Keys.TOOL_LOADING_MODE_SEARCH, Prefs.resolveToolLoadingMode("conv-a"))
        assertTrue(Prefs.toolLoadingSuffixMode("conv-a"))
        assertFalse(Prefs.toolLoadingFullCatalog("conv-a"))
        assertTrue(Prefs.toolLoadingSearchEnabled("conv-a"))
    }

    @Test fun compactionInvalidatesTheFrozenNativeGroupSnapshot() {
        Prefs.resolveToolLoadingMode("conv-a")
        Prefs.setNativeGroups("conv-a", setOf("terminal"), frozen = true)
        assertEquals(setOf("terminal"), Prefs.nativeGroups("conv-a")?.groups)
        assertEquals(true, Prefs.nativeGroups("conv-a")?.frozen)

        Prefs.unlockToolLoadingModeAtCompaction("conv-a")
        // 快照作废，下一个 run 按压缩后的历史重新播种，而不是沿用冻结的旧集合。
        assertNull(Prefs.nativeGroups("conv-a"))
    }

    @Test fun unlockIgnoresOtherConversationsAndBlankIds() {
        Prefs.resolveToolLoadingMode("conv-a")
        Prefs.setNativeGroups("conv-a", setOf("terminal"), frozen = true)
        Prefs.setToolLoadingMode(Prefs.Keys.TOOL_LOADING_MODE_GROUP)

        Prefs.unlockToolLoadingModeAtCompaction("conv-b")
        Prefs.unlockToolLoadingModeAtCompaction("")

        assertEquals(Prefs.Keys.TOOL_LOADING_MODE_SEARCH, Prefs.resolveToolLoadingMode("conv-a"))
        assertEquals(setOf("terminal"), Prefs.nativeGroups("conv-a")?.groups)
    }

    @Test fun aNewConversationStillReadsThePreferenceImmediately() {
        Prefs.resolveToolLoadingMode("conv-a")
        Prefs.setToolLoadingMode(Prefs.Keys.TOOL_LOADING_MODE_GROUP)
        assertEquals(Prefs.Keys.TOOL_LOADING_MODE_GROUP, Prefs.resolveToolLoadingMode("conv-b"))
    }

    @Test fun anotherConversationRunningDoesNotStrandThePendingUnlock() {
        Prefs.resolveToolLoadingMode("conv-a")
        Prefs.unlockToolLoadingModeAtCompaction("conv-a")
        // 期间用户切到别的会话跑了一轮：单槽锁被 conv-b 占走，标记也被它清掉。
        assertEquals(Prefs.Keys.TOOL_LOADING_MODE_SEARCH, Prefs.resolveToolLoadingMode("conv-b"))
        Prefs.setToolLoadingMode(Prefs.Keys.TOOL_LOADING_MODE_GROUP)
        // conv-a 回来时仍须读到新偏好，不能因为标记被挤掉而卡在旧档位。
        assertEquals(Prefs.Keys.TOOL_LOADING_MODE_GROUP, Prefs.resolveToolLoadingMode("conv-a"))
    }
}