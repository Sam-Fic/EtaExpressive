package io.github.mangi.eta.agent.mcp

import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolGroup
import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal class McpRunTool(
    val modelName: String,
    val server: McpServerSetting,
    val definition: McpToolDefinition,
    val bearerToken: String?,
)

/**
 * 一次 run 使用冻结的 MCP 工具目录，设置变更从下一次 run 生效。
 *
 * 渐进加载（deferred 暴露模式）下它同时充当 [AgentModelClient.McpToolProjection]：
 * 每个「有可用工具」的 server 折叠成一个动态延迟组（id = `mcp:<serverId>`），
 * 未打开的 server 只在组索引里占一行，schema 按 server 粒度经 tools_use_group /
 * tools_search 打开后下一轮才进目录——对齐内置工具组的 next_turn 语义。
 */
internal class McpRunSnapshot(
    val tools: List<McpRunTool>,
) : AgentModelClient.McpToolProjection {
    private val byModelName = tools.associateBy(McpRunTool::modelName)

    fun resolve(modelName: String): McpRunTool? = byModelName[modelName]

    private val toolsByServer: Map<String, List<McpRunTool>> = tools.groupBy { it.server.id }

    override fun deferredGroups(): List<AgentToolGroup> =
        toolsByServer.map { (serverId, serverTools) ->
            val server = serverTools.first().server
            AgentToolGroup(
                id = AgentToolGroups.mcpGroupId(serverId),
                summary = buildString {
                    append("MCP 服务器「").append(server.name).append("」的 ")
                        .append(serverTools.size).append(" 个工具：")
                    append(serverTools.take(MCP_SUMMARY_TOOL_LIMIT).joinToString("、") { it.definition.name })
                    if (serverTools.size > MCP_SUMMARY_TOOL_LIMIT) append(" 等")
                },
                toolNames = serverTools.map { it.modelName }.toSet(),
                label = "MCP 服务器「${server.name}」",
            )
        }

    override fun appendTools(destination: JSONArray, openedGroupIds: Set<String>?) {
        val projected = if (openedGroupIds == null) {
            tools
        } else {
            // 只追加「所属 server 组已打开」的工具；组 id 与 deferredGroups() 同一映射，
            // 写错一侧会导致「打开成功但工具永远不出现」。
            tools.filter { AgentToolGroups.mcpGroupId(it.server.id) in openedGroupIds }
        }
        projected.forEach { tool ->
            val description = buildString {
                append("MCP 服务器「").append(tool.server.name).append("」提供的工具")
                tool.definition.description.trim().takeIf { it.isNotBlank() }?.let {
                    append("。 ").append(it)
                }
            }
            destination.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.modelName)
                            .put("description", description)
                            .put("parameters", JSONObject(tool.definition.inputSchemaJson)),
                    ),
            )
        }
    }

    override fun toolDescriptions(): Map<String, String> =
        tools.associate { it.modelName to it.definition.description }

    override fun openedGroupIdsFrom(toolNames: Collection<String>): Set<String> =
        tools.filter { it.modelName in toolNames }
            .mapTo(mutableSetOf()) { AgentToolGroups.mcpGroupId(it.server.id) }

    fun appendModelTools(destination: JSONArray) {
        appendTools(destination, openedGroupIds = null)
    }

    companion object {
        /** 组索引一行的工具名摘录上限：索引的价值在短，全量名字交给搜索。 */
        private const val MCP_SUMMARY_TOOL_LIMIT = 6

        val EMPTY = McpRunSnapshot(emptyList())
        private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val refreshing: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun appendCachedModelTools(
            destination: JSONArray,
            servers: List<McpServerSetting>,
        ) {
            var count = 0
            servers.forEach { server ->
                server.activeTools.forEach { tool ->
                    if (count >= MAX_RUN_TOOLS) return
                    val description = buildString {
                        append("MCP 服务器「").append(server.name).append("」提供的工具")
                        tool.description.trim().takeIf { it.isNotBlank() }?.let {
                            append("。 ").append(it)
                        }
                    }
                    runCatching {
                        destination.put(
                            JSONObject()
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", modelToolName(server.id, tool.name))
                                        .put("description", description)
                                        .put("parameters", JSONObject(tool.inputSchemaJson)),
                                ),
                        )
                        count++
                    }
                }
            }
        }

        suspend fun load(): McpRunSnapshot {
            val now = System.currentTimeMillis()
            val configured = McpServerRepository.enabledServers().map { server ->
                server to McpServerRepository.bearerToken(server.id)
            }
            // Only a server that has never produced a catalog needs the network before this run.
            // Discovering several such servers sequentially would add their round trips end to end.
            val undiscovered = configured.filter { (server, _) ->
                needsRefresh(server, now) && server.tools.isEmpty()
            }
            val discovered = if (undiscovered.isEmpty()) emptyMap() else coroutineScope {
                undiscovered.map { (server, token) ->
                    async(Dispatchers.IO) {
                        server.id to runCatching {
                            McpServerManager.discover(server, token).also {
                                McpServerRepository.update(it)
                            }
                        }.getOrNull()
                    }
                }.awaitAll().toMap()
            }
            val projected = mutableListOf<McpRunTool>()
            configured.forEach { (configuredServer, bearerToken) ->
                // An expired cache stays valid for THIS frozen run; refresh it for the next one
                // instead of putting an MCP round trip in front of the model request.
                if (needsRefresh(configuredServer, now) && configuredServer.tools.isNotEmpty()) {
                    scheduleRefresh(configuredServer.id)
                }
                val server = discovered[configuredServer.id] ?: configuredServer
                for (tool in server.activeTools) {
                    if (projected.size >= MAX_RUN_TOOLS) break
                    projected += McpRunTool(
                        modelName = modelToolName(server.id, tool.name),
                        server = server,
                        definition = tool,
                        bearerToken = bearerToken,
                    )
                }
            }
            return McpRunSnapshot(projected)
        }

        private fun needsRefresh(server: McpServerSetting, now: Long): Boolean =
            server.lastProtocolVersion == null ||
                server.lastProtocolVersion == McpProtocolMode.LATEST &&
                (server.toolsExpireAt == null || server.toolsExpireAt <= now)

        private fun scheduleRefresh(serverId: String) {
            if (!refreshing.add(serverId)) return
            refreshScope.launch {
                try {
                    McpServerManager.refresh(serverId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Best effort: the frozen run keeps the cached catalog either way.
                } finally {
                    refreshing.remove(serverId)
                }
            }
        }

        private fun modelToolName(serverId: String, toolName: String): String {
            val serverPart = serverId.filter(Char::isLetterOrDigit).take(8).ifBlank { "server" }
            val normalized = toolName
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .trim('_')
                .take(30)
                .ifBlank { "tool" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$serverId\u0000$toolName".toByteArray())
                .take(4)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return "mcp_${serverPart}_${normalized}_$digest"
        }

        private const val MAX_RUN_TOOLS = 64
    }
}

internal class McpToolExecutor(
    private val snapshot: McpRunSnapshot,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    private val lifecycleLock = Any()
    private val clients = mutableMapOf<String, McpHttpClient>()
    private var closed = false

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val tool = snapshot.resolve(toolCall.name) ?: return failure(
            code = "UNKNOWN_MCP_TOOL",
            message = "MCP 工具未在本次运行中启用",
        )
        val arguments = runCatching { JSONObject(toolCall.argumentsJson.ifBlank { "{}" }) }
            .getOrElse { return failure("INVALID_ARGUMENT", "MCP 工具参数不是 JSON object") }
        return runCatching {
            val client = synchronized(lifecycleLock) {
                if (closed) return failure("MCP_EXECUTOR_CLOSED", "MCP 工具执行器已关闭")
                clients[tool.server.id] ?: McpHttpClient(
                    server = tool.server,
                    bearerToken = tool.bearerToken,
                ).also { clients[tool.server.id] = it }
            }
            adaptResult(tool, client.callTool(tool.definition, arguments))
        }.getOrElse {
            failure("MCP_CALL_FAILED", "MCP 工具调用失败")
        }
    }

    fun contains(toolName: String): Boolean = snapshot.resolve(toolName) != null

    override fun close() {
        val closing = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            clients.values.toList().also { clients.clear() }
        }
        closing.forEach { runCatching { it.close() } }
    }

    private fun adaptResult(tool: McpRunTool, result: JSONObject): AgentModelClient.ToolResult {
        val resultType = result.optString("resultType")
        if (resultType == "input_required") {
            return failure(
                code = "MCP_INPUT_REQUIRED_UNSUPPORTED",
                message = "当前版本暂不支持 MCP 工具在执行中请求补充输入",
            )
        }
        if (tool.server.lastProtocolVersion == McpProtocolMode.LATEST && resultType != "complete") {
            return failure(
                code = "MCP_RESULT_TYPE_UNSUPPORTED",
                message = "MCP 工具返回了不支持的结果类型",
            )
        }
        val content = result.optJSONArray("content") ?: JSONArray()
        val textItems = JSONArray()
        val images = mutableListOf<AgentModelClient.ModelImage>()
        var textBytes = 0
        var imageBytes = 0
        var truncated = false
        var omittedItems = 0
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            when (item.optString("type")) {
                "text" -> {
                    val text = item.optString("text")
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (remaining > 0) {
                        val bounded = text.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < text.length) truncated = true
                    } else if (text.isNotEmpty()) {
                        truncated = true
                    }
                }
                "image" -> {
                    val mimeType = item.optString("mimeType")
                    val data = item.optString("data")
                    if (!mimeType.startsWith("image/") || data.isBlank()) {
                        omittedItems += 1
                        continue
                    }
                    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT).size }.getOrNull()
                    if (bytes == null || bytes <= 0 || imageBytes + bytes > MAX_IMAGE_BYTES) {
                        omittedItems += 1
                        continue
                    }
                    images += AgentModelClient.ModelImage(
                        reference = "data:$mimeType;base64,$data",
                        mimeType = mimeType,
                        bytes = bytes,
                        source = "mcp",
                    )
                    imageBytes += bytes
                }
                "resource" -> {
                    val resource = item.optJSONObject("resource")
                    val resourceText = resource?.optString("text").orEmpty()
                    val remaining = MAX_TEXT_BYTES - textBytes
                    if (resourceText.isNotBlank() && remaining > 0) {
                        val bounded = resourceText.takeUtf8Bytes(remaining)
                        textItems.put(bounded)
                        textBytes += bounded.toByteArray().size
                        if (bounded.length < resourceText.length) truncated = true
                    } else if (resource?.has("blob") == true || resourceText.isNotBlank()) {
                        omittedItems += 1
                    }
                }
                "resource_link" -> {
                    val uri = item.optString("uri")
                    if (uri.isNotBlank()) {
                        val label = item.optString("name").ifBlank { item.optString("title") }
                        val rendered = if (label.isBlank()) uri else "$label: $uri"
                        val remaining = MAX_TEXT_BYTES - textBytes
                        if (remaining > 0) {
                            val bounded = rendered.takeUtf8Bytes(remaining)
                            textItems.put(bounded)
                            textBytes += bounded.toByteArray().size
                            if (bounded.length < rendered.length) truncated = true
                        } else {
                            truncated = true
                        }
                    } else {
                        omittedItems += 1
                    }
                }
                "audio" -> omittedItems += 1
                else -> omittedItems += 1
            }
        }
        val payload = JSONObject()
            .put("ok", !result.optBoolean("isError", false))
            .put("server", tool.server.name)
            .put("tool", tool.definition.name)
            .put("content", textItems)
        result.opt("structuredContent")?.takeUnless { it == JSONObject.NULL }?.let { structured ->
            val encoded = structured.toString()
            if (encoded.toByteArray().size <= MAX_STRUCTURED_BYTES) {
                payload.put("structured_content", structured)
            } else {
                truncated = true
            }
        }
        if (truncated) payload.put("truncated", true)
        if (omittedItems > 0) payload.put("omitted_items", omittedItems)
        if (result.optBoolean("isError", false)) payload.put("code", "MCP_TOOL_ERROR")
        return AgentModelClient.ToolResult(
            content = payload.toString(),
            images = images,
            sensitive = true,
        )
    }

    private fun failure(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = true,
        )

    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        if (toByteArray().size <= maxBytes) return this
        var low = 0
        var high = length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (substring(0, middle).toByteArray().size <= maxBytes) low = middle else high = middle - 1
        }
        return substring(0, low)
    }

    private companion object {
        const val MAX_TEXT_BYTES = 64 * 1024
        const val MAX_STRUCTURED_BYTES = 64 * 1024
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    }
}

internal class RoutingToolExecutor(
    private val local: AgentModelClient.ToolExecutor,
    private val mcp: McpToolExecutor,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult =
        if (mcp.contains(toolCall.name)) mcp.execute(toolCall) else local.execute(toolCall)

    override fun close() {
        runCatching { mcp.close() }
        (local as? AutoCloseable)?.let { runCatching { it.close() } }
    }
}
