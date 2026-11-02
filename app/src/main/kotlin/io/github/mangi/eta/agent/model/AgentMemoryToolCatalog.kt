package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.repository.AgentMemoryStore
import org.json.JSONArray
import org.json.JSONObject

/** 声明持久记忆的有界读取与原子局部更新工具。 */
internal object AgentMemoryToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "memory_get",
                    description = "Read persistent cross-conversation memory from MEMORY.md. Core memory and the heading index are already provided at run start, so call this only to inspect details, answer what is remembered, or refresh after a conflict. Prefer section to fetch one whole section by heading; use query for just-in-time retrieval; use start_line/max_chars only for paged reading.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "section",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Heading text from the heading index (the leading '#' is optional). Returns that whole section with line numbers. Takes precedence over query/start_line."),
                                )
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "Optional case-insensitive text to search for in the full memory file; matched sections are returned with context."),
                                )
                                .put(
                                    "start_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "1-based first line for paged reading when section and query are omitted; default 1."),
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentMemoryStore.MIN_READ_CHARS)
                                        .put("maximum", AgentMemoryStore.MAX_READ_CHARS)
                                        .put("description", "Maximum returned characters; default 12000, maximum 32000."),
                                ),
                        ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "memory_write",
                    description = "Atomically update persistent MEMORY.md. Store only durable cross-conversation facts, preferences, relationships, and ongoing project context; never store secrets, credentials, verification codes, or transient requests. Keep '# 核心记忆' concise and correct stale facts. Mindset: prefer replace_section over append — appending a heading that already exists is rejected, because a duplicated '# 核心记忆' silently drops content from the automatically injected core memory. Use the revision supplied in the run-start memory context or the latest memory_get result.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("replace_section")
                                                .put("replace_range")
                                                .put("append")
                                                .put("clear"),
                                        )
                                        .put(
                                            "description",
                                            "replace_section replaces (or deletes) one whole section by heading; " +
                                                "replace_range edits inclusive 1-based lines; append adds a brand-new section " +
                                                "(its heading must not already exist); clear removes all memory.",
                                        ),
                                )
                                .put(
                                    "revision",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("minLength", 64)
                                        .put("maxLength", 64)
                                        .put("description", "Exact SHA-256 revision from the run-start memory context or latest memory_get result."),
                                )
                                .put(
                                    "section",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Required for replace_section; the heading text to replace (leading '#' optional). Must match exactly one heading, otherwise the error lists the candidates."),
                                )
                                .put(
                                    "start_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "Required for replace_range; inclusive 1-based first line."),
                                )
                                .put(
                                    "end_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "Required for replace_range; inclusive 1-based last line."),
                                )
                                .put(
                                    "content",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", AgentMemoryStore.MAX_WRITE_CONTENT_CHARS)
                                        .put(
                                            "description",
                                            "Markdown to write, at most 3500 characters. For replace_section pass the whole new section and keep its heading line " +
                                                "(an empty content deletes the section); for replace_range an empty content deletes those lines.",
                                        ),
                                ),
                        )
                        .put("required", JSONArray().put("mode").put("revision")),
                ),
            )
    }
}
