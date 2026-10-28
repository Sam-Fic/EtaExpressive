package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTerminalContractRegressionTest {
    private val formatter = AgentTraceFormatter()

    @Test
    fun runCommandContractIsAndroidOnlyAndLinuxRequiresExplicitTerminalEnvironment() {
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = false,
            openedToolGroups = AgentToolGroups.allIds,
        )
        val runCommand = tools.function("run_command")
        val terminal = tools.function("terminal")
        val runDescription = runCommand.getString("description")
        val terminalDescription = terminal.getString("description")

        assertTrue(runDescription.contains("Android"))
        assertTrue(runDescription.contains("shell"))
        assertTrue(runDescription.contains("Debian"))
        assertTrue(runDescription.contains("Alpine"))
        assertTrue(runDescription.contains("terminal(environment=linux,cwd=/workspace/"))
        assertFalse(runDescription.contains("Linux command pipeline"))
        assertTrue(terminalDescription.contains("environment=linux"))
        assertTrue(terminalDescription.contains("Default android"))
        assertEquals(
            listOf("android", "linux"),
            terminal.getJSONObject("parameters").getJSONObject("properties")
                .getJSONObject("environment").getJSONArray("enum").let { array ->
                    (0 until array.length()).map(array::getString)
                },
        )
        assertFalse(runCommand.getJSONObject("parameters").getJSONObject("properties").has("environment"))
    }

    @Test
    fun zeroExitWithStderrWarnsWithoutMarkingTerminalFailureOrHidingStdout() {
        for (tool in listOf("terminal", "run_command")) {
            val result = terminalResult(tool, exitCode = 0, stdout = "done", stderr = "git: warning")
            val summary = formatter.summarizeResult(tool, result)

            assertTrue(formatter.isSuccessResult(result))
            assertTrue(summary.startsWith("执行完成"))
            assertTrue(summary.contains("done"))
            assertTrue(summary.contains("警告"))
            assertTrue(summary.contains("git: warning"))
            assertFalse(summary.startsWith("失败"))
        }
    }

    @Test
    fun exit127OverridesOkTrueAndPreservesBothOutputStreams() {
        for (tool in listOf("terminal", "run_command")) {
            val result = terminalResult(
                tool,
                exitCode = 127,
                stdout = "preexisting output",
                stderr = "git: not found",
            )
            val summary = formatter.summarizeResult(tool, result)

            assertFalse(formatter.isSuccessResult(result))
            assertTrue(summary.startsWith("失败 · 退出码 127"))
            assertTrue(summary.contains("preexisting output"))
            assertTrue(summary.contains("git: not found"))
        }
    }

    @Test
    fun zeroExitWithoutStderrStaysOrdinarySuccessAndNonTerminalUsesOkFlag() {
        val success = terminalResult("run_command", exitCode = 0, stdout = "ready", stderr = "")
        assertTrue(formatter.isSuccessResult(success))
        assertEquals("执行完成\nready", formatter.summarizeResult("run_command", success))

        val nonTerminal = AgentModelClient.ToolResult(
            """{"tool":"tap","ok":true,"exit_code":127}""",
        )
        val nonTerminalFailure = AgentModelClient.ToolResult(
            """{"tool":"tap","ok":false,"exit_code":0,"code":"DENIED"}""",
        )
        assertTrue(formatter.isSuccessResult(nonTerminal))
        assertEquals("完成", formatter.summarizeResult("tap", nonTerminal))
        assertFalse(formatter.isSuccessResult(nonTerminalFailure))
        assertEquals("失败 · code=DENIED", formatter.summarizeResult("tap", nonTerminalFailure))
    }

    @Test
    fun terminalStderrWarningPreviewRemainsBounded() {
        val stderr = (1..20).joinToString("\n") { "line-$it-${"x".repeat(100)}" }
        val result = terminalResult("run_command", exitCode = 0, stdout = "", stderr = stderr)
        val summary = formatter.summarizeResult("run_command", result)

        assertTrue(summary.startsWith("执行完成"))
        assertTrue(summary.contains("警告"))
        assertTrue(summary.endsWith("…"))
        assertTrue(summary.length < stderr.length)
    }

    private fun terminalResult(
        tool: String,
        exitCode: Int,
        stdout: String,
        stderr: String,
    ): AgentModelClient.ToolResult = AgentModelClient.ToolResult(
        JSONObject()
            .put("tool", tool)
            .put("ok", true)
            .put("exit_code", exitCode)
            .put("stdout", stdout)
            .put("stderr", stderr)
            .toString(),
    )

    private fun JSONArray.function(name: String): JSONObject =
        (0 until length())
            .asSequence()
            .map { getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == name }
}
