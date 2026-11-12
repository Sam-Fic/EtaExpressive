package io.github.mangi.eta.ui.screens.mcp

import org.junit.Assert.assertEquals
import org.junit.Test

/** JSON 导入解析器的结构用例：URL 合法性由 UI 层走提交前同款 `validateMcpEndpoint` 门槛，这里不测。 */
class McpServerJsonImportTest {
    @Test
    fun parsesWrappedEntryWithNameKeyAndBearerToken() {
        val result = parseMcpServerJson(
            """
            {
              "mcpServers": {
                "context7": {
                  "url": "https://mcp.example.com/mcp",
                  "headers": { "Authorization": "Bearer sk-abc123" }
                }
              }
            }
            """.trimIndent(),
        )
        assertEquals(
            McpServerJsonImport.Success("context7", "https://mcp.example.com/mcp", "sk-abc123"),
            result,
        )
    }

    @Test
    fun parsesBareObjectWithLowercaseAuthorizationHeader() {
        val result = parseMcpServerJson(
            """
            { "name": "my-server", "url": "https://mcp.example.com/sse",
              "headers": { "authorization": "plain-token" } }
            """.trimIndent(),
        )
        assertEquals(
            McpServerJsonImport.Success("my-server", "https://mcp.example.com/sse", "plain-token"),
            result,
        )
    }

    @Test
    fun takesFirstEntryWhenWrapperHoldsSeveral() {
        val result = parseMcpServerJson(
            """
            { "mcpServers": {
                "first": { "url": "https://first.example.com/mcp" },
                "second": { "url": "https://second.example.com/mcp" }
              } }
            """.trimIndent(),
        )
        assertEquals(
            McpServerJsonImport.Success("first", "https://first.example.com/mcp", null),
            result,
        )
    }

    @Test
    fun headersWithoutAuthorizationYieldNullToken() {
        val result = parseMcpServerJson(
            """{ "mcpServers": { "x": { "url": "https://mcp.example.com/mcp",
               "headers": { "X-Api-Key": "v1" } } } }""",
        )
        assertEquals(
            McpServerJsonImport.Success("x", "https://mcp.example.com/mcp", null),
            result,
        )
    }

    @Test
    fun stdioEntryIsReportedUnsupported() {
        val result = parseMcpServerJson(
            """{ "mcpServers": { "local": { "command": "npx", "args": ["-y", "@x/pkg"] } } }""",
        )
        assertEquals(
            McpServerJsonImport.Failure(McpJsonImportError.UNSUPPORTED_STDIO),
            result,
        )
    }

    @Test
    fun missingUrlReportsMissingUrl() {
        val result = parseMcpServerJson("""{ "mcpServers": { "x": { "headers": {} } } }""")
        assertEquals(
            McpServerJsonImport.Failure(McpJsonImportError.MISSING_URL),
            result,
        )
    }

    @Test
    fun malformedTextReportsMalformed() {
        assertEquals(
            McpServerJsonImport.Failure(McpJsonImportError.MALFORMED),
            parseMcpServerJson("not json{"),
        )
        assertEquals(
            McpServerJsonImport.Failure(McpJsonImportError.MALFORMED),
            parseMcpServerJson("""["an", "array"]"""),
        )
        assertEquals(
            McpServerJsonImport.Failure(McpJsonImportError.MALFORMED),
            parseMcpServerJson("   "),
        )
    }

    @Test
    fun blankEntryNameFallsBackToNullSoUserFillsIt() {
        val result = parseMcpServerJson("""{ "mcpServers": { "  ": { "url": "https://mcp.example.com/mcp" } } }""")
        assertEquals(
            McpServerJsonImport.Success(null, "https://mcp.example.com/mcp", null),
            result,
        )
    }
}
