package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 声明 Eta 自身配置的可读写工具。
 *
 * 目标是「运行本助手的 App 自己」：模型配置、语音、工具开关、触感。
 * Android 系统设置由 `get_setting` / `set_setting` 负责，两者互不重叠。
 */
internal object AgentSelfSettingsToolCatalog {
    const val GET_SETTINGS = "eta_get_settings"
    const val SET_SETTINGS = "eta_set_settings"
    const val MANAGE_MODELS = "eta_manage_models"
    const val MANAGE_SUBAGENTS = "eta_manage_subagents"
    const val MANAGE_HISTORY = "eta_manage_history"
    const val CONFIGURE_MCP = "eta_configure_mcp"
    const val AUTHOR_TOOL = "eta_author_tool"
    const val LIST_RECIPES = "eta_list_recipes"

    /**
     * 低频管理工具：可由 [AgentToolGroups] 折叠成按需打开的域。
     * 读/改配置这两个常驻工具不在此列。
     */
    fun manageTools(): Set<String> =
        setOf(MANAGE_MODELS, MANAGE_SUBAGENTS, MANAGE_HISTORY, CONFIGURE_MCP)

    /**
     * 下发自设置工具。[withheld] 里的工具不下发（由 [AgentToolGroups] 折叠成按需打开的域）。
     */
    fun appendTo(tools: JSONArray, withheld: Set<String> = emptySet()) {
        val emitted = JSONArray()
        appendAll(emitted)
        for (index in 0 until emitted.length()) {
            val tool = emitted.getJSONObject(index)
            val name = tool.optJSONObject("function")?.optString("name").orEmpty()
            if (name in withheld) continue
            tools.put(tool)
        }
    }

    private fun appendAll(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = GET_SETTINGS,
                    description = "Read the configuration of Eta, the assistant app you are running: every switch you can change, its current value, and the meaning of each choice. Call this before changing anything so you report the real current value instead of guessing. The active model is not included; use $MANAGE_MODELS for models.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                        .put("additionalProperties", false),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = SET_SETTINGS,
                    description = "Change switches of Eta, the assistant app you are running; this never touches Android system settings. Pass one or more pairs of key and value. Keys and accepted values come from $GET_SETTINGS: use \"on\" or \"off\" for switches, and the exact option for a choice. Only change what the user actually asked for, and state the result in your reply. A tool availability switch applies to the next tool call in this run; every other change applies from the next reply, not to the answer you are writing now.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "settings",
                                JSONObject()
                                    .put("type", "array")
                                    .put("maxItems", 24)
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "object")
                                            .put(
                                                "properties",
                                                JSONObject()
                                                    .put(
                                                        "key",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put("maxLength", 64)
                                                            .put("description", "Setting key from $GET_SETTINGS."),
                                                    )
                                                    .put(
                                                        "value",
                                                        JSONObject()
                                                            .put("type", "string")
                                                            .put("maxLength", 64)
                                                            .put("description", "New value: \"on\" or \"off\" for a switch, otherwise one of its listed options."),
                                                    ),
                                            )
                                            .put("required", JSONArray().put("key").put("value")),
                                    ),
                            ),
                        )
                        .put("required", JSONArray().put("settings")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = MANAGE_MODELS,
                    description = "Configure the models and providers of Eta, the assistant app you are running. " +
                        "action=list returns every provider with its models and the current selection and needs no other field. " +
                        "select sets the model used for chat (default), text-to-speech (tts), voice conversation (voice) or auto compression (compress). " +
                        "save_provider creates a provider when the name or base_url is new and otherwise updates that provider; models lists the model ids to keep and api_key is stored as a secret. " +
                        "save_model changes one existing model; only pass the fields the user asked about, and use auto to hand a capability back to whatever the provider reports. " +
                        "delete_model removes one model and delete_provider removes a provider you added, never a built-in one. " +
                        "fetch_models reads the provider's own model list to refresh the stored models and report the new ones it found. " +
                        "Every change applies from the next reply, not to the request you are answering now.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("list")
                                                .put("select")
                                                .put("save_provider")
                                                .put("delete_provider")
                                                .put("save_model")
                                                .put("delete_model")
                                                .put("fetch_models"),
                                        )
                                        .put("description", "Required. What to do with a provider or a model."),
                                )
                                .put(
                                    "target",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("chat").put("tts").put("voice").put("compress"))
                                        .put("description", "select only: which feature owns the model. Default chat."),
                                )
                                .put(
                                    "provider",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Provider id or displayed name. Required by every action except list, which may omit it."),
                                )
                                .put(
                                    "model",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Model id or displayed name. Required by save_model and delete_model; select may omit it to keep the provider's current model."),
                                )
                                .put(
                                    "name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)
                                        .put("description", "save_provider only: displayed provider name, also used to find an existing provider to update."),
                                )
                                .put(
                                    "base_url",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "save_provider only: OpenAI compatible endpoint, for example https://api.example.com/v1."),
                                )
                                .put(
                                    "api_key",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "save_provider only: secret key for that endpoint. Omit it to keep the stored one, pass an empty string to clear it."),
                                )
                                .put(
                                    "models",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("maxItems", 60)
                                        .put("items", JSONObject().put("type", "string").put("maxLength", 200))
                                        .put("description", "save_provider only: model ids to keep on that provider. Missing ones are created; existing ones are left alone."),
                                )
                                .put(
                                    "is_enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "save_provider only: false hides the provider from the model picker without deleting it."),
                                )
                                .put(
                                    "display_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "save_model only: the name shown for this model."),
                                )
                                .put(
                                    "enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "save_model only: false hides the model from the model picker without deleting it."),
                                )
                                .put(
                                    "vision",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("on").put("off").put("auto"))
                                        .put("description", "save_model only: force whether this model can read images; auto uses the provider's answer."),
                                )
                                .put(
                                    "reasoning",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("on").put("off").put("auto"))
                                        .put("description", "save_model only: force whether this model can think before answering; auto uses the provider's answer."),
                                )
                                .put(
                                    "context_window",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", 10_000_000)
                                        .put("description", "save_model only: context length in tokens, used for session clipping and context budgeting."),
                                )
                                .put(
                                    "reasoning_effort",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("off")
                                                .put("default")
                                                .put("minimal")
                                                .put("low")
                                                .put("medium")
                                                .put("high")
                                                .put("xhigh")
                                                .put("max"),
                                        )
                                        .put("description", "save_model only: how much thinking the model does by default. Only useful when the model supports reasoning."),
                                )
                                .put(
                                    "add_new",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "fetch_models only: also store the new models it found. Default false, which only refreshes the ones already stored."),
                                ),
                        )
                        .put("required", JSONArray().put("action")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = MANAGE_SUBAGENTS,
                    description = "Create and manage the sub-agent groups of Eta, the assistant app you are running. " +
                        "A group is a reusable preset of sub-agents that the main agent delegates to with delegate_task, and it can be applied to a conversation. " +
                        "action=list returns every group with its sub-agents and which group this conversation applied, and needs no other field. " +
                        "create_group, rename_group, delete_group and set_delegation manage a group itself, where set_delegation turns its automatic delegation on or off. " +
                        "add_agent, update_agent and remove_agent manage the sub-agents inside one group. " +
                        "apply copies a group onto this conversation. " +
                        "Only change what the user actually asked for, and state the result in your reply. " +
                        "Every change applies from the next reply, not to the answer you are writing now; editing a group does not change conversations that already applied it.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("list")
                                                .put("create_group")
                                                .put("rename_group")
                                                .put("delete_group")
                                                .put("set_delegation")
                                                .put("add_agent")
                                                .put("update_agent")
                                                .put("remove_agent")
                                                .put("apply"),
                                        )
                                        .put("description", "Required. What to do with a group or one of its sub-agents."),
                                )
                                .put(
                                    "group",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "Group id or displayed name. Required by every action except list and create_group."),
                                )
                                .put(
                                    "name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "create_group and rename_group only: the group's name, which is the new name when renaming."),
                                )
                                .put(
                                    "agent",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "update_agent and remove_agent only: the sub-agent's id or displayed name."),
                                )
                                .put(
                                    "agent_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "add_agent and update_agent only: the sub-agent's displayed name."),
                                )
                                .put(
                                    "role",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray().put("implementation").put("review").put("image_generation").put("video_generation"),
                                        )
                                        .put("description", "add_agent and update_agent only: what the sub-agent does. Default implementation."),
                                )
                                .put(
                                    "provider",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "add_agent and update_agent only: provider id or displayed name; pass it together with model to bind the sub-agent to a model."),
                                )
                                .put(
                                    "model",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "add_agent and update_agent only: model id or displayed name on that provider."),
                                )
                                .put(
                                    "enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "set_delegation: the group's automatic delegation. add_agent and update_agent: whether this sub-agent is enabled; a new sub-agent starts enabled."),
                                )
                                .put(
                                    "task_tier",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("simple").put("regular").put("complex"))
                                        .put("description", "add_agent and update_agent only: task tier for role=implementation; ignored by other roles."),
                                )
                                .put(
                                    "reasoning",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("off")
                                                .put("default")
                                                .put("minimal")
                                                .put("low")
                                                .put("medium")
                                                .put("high")
                                                .put("xhigh")
                                                .put("max"),
                                        )
                                        .put("description", "add_agent and update_agent only: reasoning effort for this sub-agent; pass an empty string to clear it."),
                                )
                                .put(
                                    "image_resolution",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("low").put("medium").put("high").put("ultra"))
                                        .put("description", "add_agent and update_agent only: resolution for role=image_generation; pass an empty string to clear it."),
                                ),
                        )
                        .put("required", JSONArray().put("action")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = MANAGE_HISTORY,
                    description = "Browse and organize the chat history of Eta, the assistant app you are running: its folders and its saved conversations. " +
                        "action=list_folders returns every folder with how many conversations it holds and needs no other field. " +
                        "action=list_conversations returns the stored conversations, pinned ones first and then newest first. " +
                        "action=search_conversations finds conversations by their title or by the text inside their messages; each hit reports match=title for a title hit and match=message for a message hit, and a title hit carries no message id. " +
                        "create_folder, rename_folder and delete_folder manage folders; deleting a folder keeps its conversations and moves them back to the ungrouped list. " +
                        "rename_conversation, move_conversation, set_pinned and delete_conversation change one conversation, where set_pinned sets the value rather than toggling it. " +
                        "Deleting a conversation moves it to the trash and keeps its content; restore_conversation brings it back, while delete_conversation_forever wipes it for good and only works inside the trash; empty_trash wipes every trashed conversation at once. " +
                        "conversation accepts an id or a title, or the special value current for the conversation you are answering in; folder accepts an id or a name, or none for the ungrouped list. " +
                        "When a conversation id or title matches more than one conversation the call fails and lists the candidates instead of guessing. " +
                        "Only delete_conversation_forever and empty_trash are irreversible; never call them unless the user clearly asked for a permanent wipe. " +
                        "Only change what the user actually asked for, and state the result in your reply; every change shows up in the history list right away.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("list_folders")
                                                .put("list_conversations")
                                                .put("search_conversations")
                                                .put("create_folder")
                                                .put("rename_folder")
                                                .put("delete_folder")
                                                .put("rename_conversation")
                                                .put("move_conversation")
                                                .put("set_pinned")
                                                .put("delete_conversation")
                                                .put("restore_conversation")
                                                .put("delete_conversation_forever")
                                                .put("empty_trash"),
                                        )
                                        .put("description", "Required. What to do with a folder or a conversation."),
                                )
                                .put(
                                    "folder",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "Folder id or name; also the target of move_conversation. Pass none to mean the ungrouped list."),
                                )
                                .put(
                                    "name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)
                                        .put("description", "create_folder, rename_folder and rename_conversation only: the new name."),
                                )
                                .put(
                                    "conversation",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Conversation id or title; pass current for the conversation you are answering in."),
                                )
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)
                                        .put("description", "search_conversations only: text to look for in conversation titles or inside the messages."),
                                )
                                .put(
                                    "pinned",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "set_pinned only: true pins the conversation to the top, false unpins it."),
                                )
                                .put(
                                    "trashed",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "list_conversations only: true lists the trash instead of the normal history."),
                                ),
                        )
                        .put("required", JSONArray().put("action")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = CONFIGURE_MCP,
                    description = "Configure the MCP servers of Eta, the assistant app you are running: the remote tool servers " +
                        "it connects to and which of their tools are offered. " +
                        "action=list returns every server with its endpoint, state and tools, and needs no other field. " +
                        "add connects a new server: pass name and url, and, only when the endpoint needs a key, a token together with authorization=bearer. " +
                        "update changes one existing server; only pass the fields the user asked about. " +
                        "delete removes a server together with its stored key, and refresh reconnects one server to read its current tool list. " +
                        "set_tool turns a single tool of a server on or off, while enable_all or tools chooses which tools a new or updated server offers. " +
                        "server accepts an id, a displayed name or the exact url. " +
                        "Keys are stored as secrets and are never echoed back, so after a change report the server name rather than the key. " +
                        "Only change what the user actually asked for, and state the result in your reply. " +
                        "Every change applies from the next reply, not to the answer you are writing now.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "action",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("list")
                                                .put("add")
                                                .put("update")
                                                .put("delete")
                                                .put("refresh")
                                                .put("set_tool"),
                                        )
                                        .put("description", "Required. What to do with a server or one of its tools."),
                                )
                                .put(
                                    "server",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Server id, displayed name or exact url. Required by every action except list and add."),
                                )
                                .put(
                                    "name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 120)
                                        .put("description", "add and update only: the name shown for this server."),
                                )
                                .put(
                                    "url",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "add and update only: the MCP endpoint, for example https://example.com/mcp."),
                                )
                                .put(
                                    "token",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "add and update only: secret key for that endpoint. Omit it to keep the stored one, pass an empty string to clear it."),
                                )
                                .put(
                                    "authorization",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("none").put("bearer"))
                                        .put("description", "add and update only: bearer sends the token as an Authorization header. Default none, or bearer when a non-empty token is passed."),
                                )
                                .put(
                                    "protocol",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("auto").put("latest").put("legacy"))
                                        .put("description", "add and update only: which MCP revision to speak. Default auto, which negotiates."),
                                )
                                .put(
                                    "is_enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "add and update only: false keeps the server stored but stops offering its tools."),
                                )
                                .put(
                                    "enable_all",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "add and update only: true offers every tool the server reports."),
                                )
                                .put(
                                    "tools",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("maxItems", 60)
                                        .put("items", JSONObject().put("type", "string").put("maxLength", 200))
                                        .put("description", "add and update only: exact tool names to offer. A name the server does not report fails the call."),
                                )
                                .put(
                                    "tool",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "set_tool only: the tool name or title to turn on or off."),
                                )
                                .put(
                                    "enabled",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "set_tool only: true offers the tool, false hides it. Default true."),
                                ),
                        )
                        .put("required", JSONArray().put("action")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = AUTHOR_TOOL,
                    description = "Write yourself a new tool: a recipe is a declarative list of fixed steps, not code. " +
                        "A recipe you save appears as a callable recipe_<name> tool in your NEXT request, so create it, " +
                        "then call it on a later turn. " +
                        "action=create needs name, description, parameters and steps; action=update changes an existing one; " +
                        "action=delete removes it; action=enable and action=disable turn it on or off. " +
                        "The recipe object is {name, description, parameters, steps}. " +
                        "parameters is a JSON Schema object: {type:\"object\", properties:{<arg>:{type:\"string\",description:\"...\"}}, required:[...]}. " +
                        "steps is a list, each step is {op:..., ...}, and op is one of: " +
                        "http_request {url, method?, headers?, body?, body_is_json?}; " +
                        "root_shell {command, timeout_ms?} (needs Root); " +
                        "settings_read {namespace, key}; settings_write {namespace, key, value}; " +
                        "file_read {path, max_bytes?}; file_write {path, content, append?}; " +
                        "content_query {uri, projection?, selection?, limit?} (needs Root); " +
                        "compose {recipe, pass_output?} to call another recipe; literal {text}. " +
                        "namespace is system, secure or global. " +
                        "Any {{arg}} placeholder in a step is filled from the tool call arguments; put every placeholder you use in parameters, " +
                        "otherwise the recipe is rejected. " +
                        "name must be 3 to 40 lowercase letters, digits or underscores and must not collide with an existing recipe. " +
                        "When a name already exists you get the candidate list back: pick the exact name and call again once, do not repeat the same call. " +
                        "A recipe must not compose itself, and composing must not form a loop. " +
                        "Write the recipe only when the user asked for a capability the built-in tools cannot provide, " +
                        "and tell the user the new tool name in your reply.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put(
                                        "enum",
                                        JSONArray().put("create").put("update").put("delete").put("enable").put("disable"),
                                    )
                                    .put("description", "Required. What to do with a recipe."),
                            ).put(
                                "name",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 40)
                                    .put(
                                        "description",
                                        "Recipe name: 3 to 40 lowercase letters, digits or underscores, starting with a letter. " +
                                            "The tool it becomes is recipe_<name>.",
                                    ),
                            ).put(
                                "recipe",
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "description",
                                        "create and update only: the full recipe definition " +
                                            "{name, description, parameters, steps}. Send every field, not a patch.",
                                    )
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put(
                                                "name",
                                                JSONObject()
                                                    .put("type", "string")
                                                    .put("maxLength", 40)
                                                    .put("description", "Recipe name, same rules as the name field above."),
                                            )
                                            .put(
                                                "description",
                                                JSONObject()
                                                    .put("type", "string")
                                                    .put("maxLength", 1200)
                                                    .put("description", "What the new tool does, in one or two sentences."),
                                            )
                                            .put(
                                                "parameters",
                                                JSONObject()
                                                    .put("type", "object")
                                                    .put(
                                                        "description",
                                                        "JSON Schema of the arguments: " +
                                                            "{type:\"object\", properties:{arg:{type,description}}, required:[arg]}.",
                                                    ),
                                            )
                                            .put(
                                                "steps",
                                                JSONObject()
                                                    .put("type", "array")
                                                    .put("maxItems", 24)
                                                    .put(
                                                        "description",
                                                        "Ordered steps. Each step is {op:...} where op is one of " +
                                                            "http_request, root_shell, settings_read, settings_write, " +
                                                            "file_read, file_write, content_query, compose, literal.",
                                                    ),
                                            ),
                                    )
                                    .put("required", JSONArray().put("name").put("description").put("parameters").put("steps")),
                            ),
                        )
                        .put("required", JSONArray().put("action")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = LIST_RECIPES,
                    description = "List the recipes you wrote earlier: their name, what they do, whether they are enabled, " +
                        "whether they need Root, their arguments and their steps. " +
                        "Pass name to see one recipe in full, or leave it out to see them all. " +
                        "Call this before updating a recipe, so you change the one you mean instead of guessing.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "name",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 40)
                                    .put("description", "Optional: exact recipe name. Omit to list all of them."),
                            ),
                        ),
                ),
            )
    }
}
