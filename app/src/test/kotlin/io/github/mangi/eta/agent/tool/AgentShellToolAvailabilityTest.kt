package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentPromptBuilder
import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.agent.skill.SkillContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime

/** Pure file-metadata tests; nothing is executed. Layout mirrors a real Debian rootfs. */
class AgentShellToolAvailabilityTest {
    @get:Rule val temp = TemporaryFolder()

    private fun executable(file: File) = file.apply {
        parentFile.mkdirs(); writeText("#!/bin/sh\n"); setExecutable(true)
    }
    private fun link(at: File, target: String) {
        at.parentFile.mkdirs(); Files.createSymbolicLink(at.toPath(), File(target).toPath())
    }

    private fun rootfs(): File = temp.newFolder("rootfs").also { root ->
        executable(File(root, "usr/bin/git"))
        executable(File(root, "usr/bin/gh"))
        // Absolute guest links, as installed by the Python/Node profiles.
        executable(File(root, "opt/eta/python/cpython-3.14.7-linux-aarch64-gnu/bin/python3.14"))
        link(File(root, "opt/eta/python/cpython-3.14-linux-aarch64-gnu"), "/opt/eta/python/cpython-3.14.7-linux-aarch64-gnu")
        link(File(root, "usr/local/bin/python3"), "/opt/eta/python/cpython-3.14-linux-aarch64-gnu/bin/python3.14")
        executable(File(root, "opt/eta/node/26.8.1/bin/node"))
        link(File(root, "usr/local/bin/node"), "/opt/eta/node/26.8.1/bin/node")
        // Relative link chain.
        executable(File(root, "opt/eta/node/26.8.1/lib/node_modules/npm/bin/npm-cli.js"))
        link(File(root, "opt/eta/node/26.8.1/bin/npm"), "../lib/node_modules/npm/bin/npm-cli.js")
        link(File(root, "usr/local/bin/npm"), "/opt/eta/node/26.8.1/bin/npm")
        executable(File(root, "usr/local/bin/uv"))
        // Dangling link must not count.
        link(File(root, "usr/local/bin/jadx"), "/opt/missing/jadx")
        // Non-executable file must not count.
        File(root, "usr/bin/curl").apply { parentFile.mkdirs(); writeText("x"); setExecutable(false) }
    }

    @Test
    fun resolvesAbsoluteAndRelativeGuestLinksInsideRootfs() {
        val root = rootfs()
        val snapshot = AgentShellToolAvailability.detect(root, "Debian", androidDirs = emptyList())
        assertTrue(snapshot.linuxReady)
        assertEquals(listOf("git", "gh", "python3", "uv", "node", "npm"), snapshot.linux)
        assertTrue(snapshot.android.isEmpty())
    }

    @Test
    fun linksCannotEscapeTheRootfs() {
        val outside = executable(File(temp.root, "host-only/bin/java"))
        val root = temp.newFolder("jail")
        link(File(root, "usr/bin/java"), "../../../../host-only/bin/java")
        link(File(root, "usr/bin/ssh"), outside.absolutePath)
        val snapshot = AgentShellToolAvailability.detect(root, "Debian", androidDirs = emptyList())
        assertFalse(snapshot.linux.contains("java"))
        assertFalse(snapshot.linux.contains("ssh"))
    }

    @Test
    fun linkLoopsAreRejected() {
        val root = temp.newFolder("loop")
        link(File(root, "usr/bin/git"), "/usr/bin/gh")
        link(File(root, "usr/bin/gh"), "/usr/bin/git")
        assertNull(AgentShellToolAvailability.resolveGuest(root, "/usr/bin/git"))
        assertTrue(AgentShellToolAvailability.detect(root, "Debian", emptyList()).linux.isEmpty())
    }

    /** Mirrors a root-unpacked chroot rootfs: SELinux denies readlink to the app for every link. */
    private val deniedToApp: (File) -> String? = { null }

    private fun realTargets(links: List<File>): Map<File, String> =
        links.associateWith { Files.readSymbolicLink(it.toPath()).toString() }

    @Test
    fun linksDeniedToTheAppAreResolvedThroughOneRootBatchPerLevel() {
        AgentShellToolAvailability.clearTargetCacheForTest()
        val root = rootfs()
        val batches = mutableListOf<List<File>>()
        val snapshot = AgentShellToolAvailability.detect(
            root, "Debian", emptyList(),
            privilegedLinkReader = { links -> batches += links; realTargets(links) },
            appLinkReader = deniedToApp,
        )
        assertEquals(listOf("git", "gh", "python3", "uv", "node", "npm"), snapshot.linux)
        // python3 needs two levels (entry link, then the versioned directory link).
        assertTrue(batches.toString(), batches.size in 2..3)
        val rootPath = root.canonicalPath
        batches.flatten().forEach { assertTrue(it.path, it.absoluteFile.parentFile.canonicalPath.startsWith(rootPath)) }
        assertEquals("each link is asked for at most once", batches.flatten().size, batches.flatten().toSet().size)
    }

    @Test
    fun deniedLinksWithoutRootStayUndetectedInsteadOfGuessed() {
        AgentShellToolAvailability.clearTargetCacheForTest()
        val snapshot = AgentShellToolAvailability.detect(rootfs(), "Debian", emptyList(), appLinkReader = deniedToApp)
        // Regular files are still found; symlinked tools are not reported as present.
        assertEquals(listOf("git", "gh", "uv"), snapshot.linux)
    }

    @Test
    fun failingRootReaderFallsBackToAppVisibleResultsWithoutRetryLoop() {
        AgentShellToolAvailability.clearTargetCacheForTest()
        var calls = 0
        val snapshot = AgentShellToolAvailability.detect(
            rootfs(), "Debian", emptyList(),
            privilegedLinkReader = { calls++; error("su unavailable") },
            appLinkReader = deniedToApp,
        )
        assertEquals(listOf("git", "gh", "uv"), snapshot.linux)
        assertEquals(1, calls)
    }

    @Test
    fun rootTargetsAreCachedUntilTheLinkChanges() {
        AgentShellToolAvailability.clearTargetCacheForTest()
        val root = rootfs()
        var calls = 0
        val reader: (List<File>) -> Map<File, String> = { calls++; realTargets(it) }
        AgentShellToolAvailability.detect(root, "Debian", emptyList(), privilegedLinkReader = reader, appLinkReader = deniedToApp)
        val first = calls
        val again = AgentShellToolAvailability.detect(root, "Debian", emptyList(), privilegedLinkReader = reader, appLinkReader = deniedToApp)
        assertEquals(first, calls)
        assertTrue(again.linux.contains("python3"))
    }

    @Test
    fun changedSymlinkInvalidatesRootCache() {
        AgentShellToolAvailability.clearTargetCacheForTest()
        val root = rootfs()
        var calls = 0
        val reader: (List<File>) -> Map<File, String> = { calls++; realTargets(it) }
        fun probe() = AgentShellToolAvailability.detect(root, "Debian", emptyList(),
            privilegedLinkReader = reader, appLinkReader = deniedToApp)
        assertTrue(probe().linux.contains("node"))
        val before = calls
        val entry = File(root, "usr/local/bin/node").toPath()
        val oldTime = Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS)
        Files.delete(entry)
        Files.createSymbolicLink(entry, File("/opt/not-installed/node").toPath())
        Files.getFileAttributeView(entry, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            .setTimes(FileTime.fromMillis(oldTime.toMillis() + 10_000), null, null)
        assertFalse(probe().linux.contains("node"))
        assertTrue(calls > before)
    }

    @Test
    fun cachedRootTargetsAreNotUsedWithoutRootReader() {
        AgentShellToolAvailability.clearTargetCacheForTest()
        val root = rootfs()
        AgentShellToolAvailability.detect(root, "Debian", emptyList(),
            privilegedLinkReader = ::realTargets, appLinkReader = deniedToApp)
        val snapshot = AgentShellToolAvailability.detect(root, "Debian", emptyList(), appLinkReader = deniedToApp)
        assertEquals(listOf("git", "gh", "uv"), snapshot.linux)
    }

    @Test
    fun rootReadlinkOutputRejectsIncompleteExtraAndMissingFields() {
        val links = listOf(File("/x/a"), File("/x/b"))
        for (raw in listOf("/a\u0000/b", "/a\u0000", "/a\u0000/b\u0000extra\u0000", "")) {
            assertTrue(AgentShellToolAvailability.parseRootReadlinkOutput(links, raw).isEmpty())
        }
        assertEquals(mapOf(links[0] to "/a\n", links[1] to "/b"),
            AgentShellToolAvailability.parseRootReadlinkOutput(links, "/a\n\u0000/b\u0000"))
    }

    @Test
    fun rootReadlinkScriptQuotesPathsAndOnlyReadsLinks() {
        val links = listOf(File("/data/x/it's here/bin/python3"), File("/data/x/bin/node"))
        val script = AgentShellToolAvailability.rootReadlinkScript(links)
        assertTrue(script, script.contains("'/data/x/it'\\''s here/bin/python3'"))
        assertTrue(script, script.contains("/system/bin/readlink -n -- \"\$p\""))
        assertTrue(script, script.contains("2>/dev/null || :"))
        assertFalse(script, script.contains("python3 --"))
        val parsed = AgentShellToolAvailability.parseRootReadlinkOutput(links, "/opt/py\u0000\u0000")
        assertEquals(mapOf(links[0] to "/opt/py"), parsed)
        assertTrue(AgentShellToolAvailability.parseRootReadlinkOutput(links, "/opt/py").isEmpty())
    }

    @Test
    fun androidToolsAreDetectedFromTheirOwnPathAndNotAssumedAbsent() {
        val bin = temp.newFolder("android-bin")
        executable(File(bin, "git"))
        executable(File(bin, "curl"))
        val snapshot = AgentShellToolAvailability.detect(null, "Alpine", listOf(bin.absolutePath))
        assertFalse(snapshot.linuxReady)
        assertTrue(snapshot.linux.isEmpty())
        assertEquals(listOf("git", "curl"), snapshot.android)
        val note = AgentShellToolAvailability.promptNote(snapshot)
        assertTrue(note, note.contains("Linux（Alpine）工具环境未就绪"))
        assertTrue(note, note.contains("Android 检测到：git、curl"))
    }

    @Test
    fun androidDirsKeepAbsolutePathEntriesAndFallbacks() {
        val dirs = AgentShellToolAvailability.androidDirs("/data/adb/ksu/bin:relative:/system/bin")
        assertEquals("/data/adb/ksu/bin", dirs.first())
        assertFalse(dirs.contains("relative"))
        assertEquals(1, dirs.count { it == "/system/bin" })
    }

    @Test
    fun promptNotePrefersLinuxButKeepsAndroidSystemCommandsOnAndroid() {
        val snapshot = AgentShellToolAvailability.detect(rootfs(), "Debian", emptyList())
        val note = AgentShellToolAvailability.promptNote(snapshot)
        assertTrue(note, note.contains("Linux（Debian）检测到：git、gh、python3、uv、node、npm"))
        assertTrue(note, note.contains("Android 检测到：无"))
        assertTrue(note, note.contains("两个环境都未检测到：pip3、java、jadx、apktool、curl、ssh"))
        assertTrue(note, note.contains("优先 terminal(environment=linux)"))
        assertTrue(note, note.contains("pm、am、cmd、dumpsys"))
        assertTrue(note, note.contains("未执行用户命令"))
        assertTrue(note, note.contains("未检出不等于未安装"))
    }

    @Test
    fun promptCarriesDetectionAndDropsHardAndroidFirstRule() {
        val snapshot = AgentShellToolAvailability.detect(rootfs(), "Debian", emptyList())
        val config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1", apiKey = "k", model = "m", systemPrompt = "",
            terminalTools = true, browserTools = false,
        )
        fun promptText(rootAvailable: Boolean, tools: AgentShellToolAvailability.Snapshot?): String {
            val messages = AgentPromptBuilder.buildSystemMessages(
                config = config,
                skillContext = SkillContext.EMPTY,
                memoryContext = AgentMemoryContext.DISABLED,
                rootAvailable = rootAvailable,
                shellTools = tools,
                // 终端组默认延迟；这里断言终端指引的措辞，因此视为已打开。
                openedToolGroups = setOf(AgentToolGroups.TERMINAL_GROUP.id),
            )
            return (0 until messages.length()).joinToString("\n") { messages.getJSONObject(it).optString("content") }
        }
        for (root in listOf(true, false)) {
            val text = promptText(root, snapshot)
            assertTrue(text, text.contains("本机命令检测"))
            assertTrue(text, text.contains("environment 按下方本机命令检测选择"))
            assertFalse(text, text.contains("首轮调用 terminal，action=open_and_exec，environment=android"))
            assertFalse(text, text.contains("未指定环境的命令使用 terminal 的 environment=android"))
            // Without a snapshot the prompt stays valid and carries no fabricated detection.
            assertFalse(promptText(root, null).contains("本机命令检测"))
        }
    }
}
