package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 文本输入、等待与系统操作工具 schema。 */
internal object AgentTextSystemToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "type_text",
                    description = "Write text into an input field; the result is read back to verify. mode=replace (the default) replaces the whole field with text, and an empty text clears it;" +
                        "mode=append inserts at the current cursor. With index it writes to that editable node without tapping first; without index it writes to the currently focused input." +
                        "submit=true presses the IME enter key after writing, for search and send; when the result has submitted=false the text was written but not submitted, so tap the send button instead of typing it again." +
                        "Chinese text, emoji and long text can be passed directly; no clipboard detour is needed.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string").put("maxLength", 4_000))
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("replace").put("append"))
                                        .put("description", "replace replaces the whole field (default); append inserts at the cursor.")
                                )
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Optional editable node index; only valid with replace, and it requires the observation_id of the same observation.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required when index is given; must come from the same most recent observation as index.")
                                )
                                .put(
                                    "submit",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Press the IME enter key to submit after writing; default false.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "input_text",
                    description = "Type up to 1000 characters into the input field that actually holds input focus. mode=append (the default) inserts at the cursor or replaces the selection; unreadable fields such as password boxes refuse reconstruction, so provide the full value with replace_text.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 1_000)
                                        .put("description", "Text to type, at most 1000 characters; the accessibility service must confirm the real input focus.")
                                )
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("append").put("replace").put("paste"))
                                        .put("description", "append types at the cursor or replaces the selection, replace substitutes the text, and paste uses the clipboard path; all three modes are limited to 1000 characters. Default append.")
                                )
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "With mode=replace you may give an editable node index; it requires the observation_id of the same observe_screen.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required when mode=replace is used with index; it must come from the same most recent observe_screen as index.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "replace_text",
                    description = "Replace the text of the focused input field or of a given editable node with the supplied content. When index is given, index and observation_id must come from the same most recent observe_screen; if the observation has expired, observe again first. The accessibility service must be enabled.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 4_000),
                                )
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Optional editable node index from the most recent observe_screen; when given it requires the observation_id of that same observation, and when omitted the focused input field is used.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required when index is given and must come from the same most recent observe_screen; omit it when no index is given.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "clear_text",
                    description = "Clear the focused input field or a given editable node. When index is given, index and observation_id must come from the same most recent observe_screen; if the observation has expired, observe again first. The accessibility service must be enabled.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Optional editable node index from the most recent observe_screen; when given it requires the observation_id of that same observation, and when omitted the focused input field is used.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Required when index is given and must come from the same most recent observe_screen; omit it when no index is given.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "set_clipboard",
                    description = "Write text to the system clipboard. Useful for preparing long text, Chinese text, emoji or special characters for pasting.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 20_000),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "get_clipboard",
                    description = "Read system clipboard text. Android version or background restrictions may make the read fail.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "paste_text",
                    description = "After confirming the real input focus, type long text into the current selection; fall back to clipboard pasting only when the target does not support direct insertion. Without focus the clipboard is not overwritten; for unreadable fields such as password boxes, provide the full value with replace_text.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 20_000),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "press_key",
                    description = "Press a system key or trigger a global action. BACK/HOME/RECENTS/NOTIFICATIONS/QUICK_SETTINGS prefer accessibility global actions; ENTER prefers the IME enter key.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "button",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("BACK")
                                                .put("HOME")
                                                .put("ENTER")
                                                .put("RECENTS")
                                                .put("PASTE")
                                                .put("NOTIFICATIONS")
                                                .put("QUICK_SETTINGS")
                                        )
                                )
                        )
                        .put("required", JSONArray().put("button"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait",
                    description = "Wait for a while so animations, network loads or page transitions can finish. Do not use it in place of the verifiable waits in wait_for_text/wait_for_package.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Wait duration from 100 to 30000; default 1000.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_text",
                    description = "Wait until the given text or description appears on the current screen; useful after a tap to confirm the page arrived, a list loaded or a dialog appeared.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Maximum wait from 500 to 60000; default 10000.")
                                )
                                .put(
                                    "include_desc",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Whether content-desc is matched too; default true.")
                                )
                                .put(
                                    "match",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("contains").put("exact").put("prefix").put("regex"))
                                        .put("description", "Matching mode; default contains.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_package",
                    description = "Wait until the given Android package is in the foreground; useful after launch_app/open_uri to confirm the target app opened.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("package_name", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Maximum wait from 500 to 60000; default 10000.")
                                )
                        )
                        .put("required", JSONArray().put("package_name"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_system_panel",
                    description = "Open the notification shade or the quick settings panel.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "panel",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("notifications").put("quick_settings"))
                                )
                        )
                        .put("required", JSONArray().put("panel"))
                )
            )
    }
}
