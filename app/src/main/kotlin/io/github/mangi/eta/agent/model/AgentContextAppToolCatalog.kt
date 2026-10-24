package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 上下文、应用入口与屏幕观察工具 schema。 */
internal object AgentContextAppToolCatalog {
    fun appendTo(tools: JSONArray) {
        for ((name,description) in listOf(
            "start_virtual_session" to "Start the background virtual-display session for this run; repeated calls do not create another, and failure does not fall back to the main screen.",
            "finish_virtual_session" to "Hand the marked delivery tasks over to the main screen in the background. With no marked tasks it cleans up this run's intermediate tasks and closes the virtual display without delivering (handedOff=false, released=true). Always check handedOff and released; on failure do not kill processes and do not claim the delivery succeeded."
        )) tools.put(AgentToolSchema.function(name=name,description=description,parameters=JSONObject().put("type","object").put("properties",JSONObject())))
        tools
            .put(
                AgentToolSchema.function(
                    name = "get_current_context",
                    description = "Get the phone's current time, timezone and last known location; call it whenever now, today, tomorrow or the current location matters.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_apps",
                    description = "Search installed Android apps on the phone; returns app names and package names. Call this first when you are unsure of a package name before opening an app.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "App name or package name fragment, for example QQ, WeChat, com.tencent")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Whether system apps are included; default false")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Return between 1 and 20 results; default 10")
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "launch_app",
                    description = "Launch an installed Android app. Prefer package_name; a bare app name allows fuzzy matching, and multiple matches return candidates without launching. An app already open in this run's virtual display switches back to its original task (reused=true). When the resolved target is Eta itself (io.github.mangi.eta) it opens in the main screen foreground without triggering ASK; later Eta GUI segments are also self-foreground and do not change the ASK/background preference of other apps. Never start a virtual display for Eta itself or ask where to execute.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Exact Android package name, for example com.tencent.mobileqq")
                                )
                                .put(
                                    "app_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "App display name, for example QQ")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "inspect_virtual_backend",
                    description = "Run a read-only Root backend check with the in-app probe without starting a virtual display. A successful query does not grant authorisation; mutations_enabled and session_authenticated are always false, and it never migrates, restores or closes apps.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "keep_virtual_result",
                    description = "Mark the tasks this virtual-display session should deliver. Prefer task_ids; you may also select by packages launched in this run. This only marks them: you must then call finish_virtual_session to verify the migration and the close.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("task_ids", JSONObject().put("type","array").put("items",JSONObject().put("type","integer")).put("description","Exact task id returned by a launch tool in this run"))
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Exact package name of one final app")
                                )
                                .put(
                                    "packages",
                                    JSONObject()
                                        .put("type", "array")
                                        .put("items", JSONObject().put("type", "string"))
                                        .put("description", "Exact package names of several final apps")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_uri",
                    description = "Hand a known-valid URI explicitly to an external Android app, such as https, tel, geo or an app deep link. It is not for reading or interacting with web pages. Never invent URIs.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "A known-valid URI the system can handle")
                                )
                        )
                        .put("required", JSONArray().put("uri"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "observe_screen",
                    description = "Observe the current phone screen. By default it returns only the foreground app, screen size, observation_id and visible UI nodes, without a screenshot. Set include_screenshot=true explicitly when nodes are empty, when the target cannot be uniquely identified, when the UI is mostly visual content such as Canvas/maps/images/QR codes, or when the task depends on colour, imagery or spatial layout; when adding a screenshot keep include_ui_tree=true so the same new observation refreshes both nodes and observation_id, and never mix a new screenshot with old nodes. Node actions must carry the observation_id of that same observation. When the tree is truncated but node semantics are still usable, raise max_nodes to 120 and retry instead of requesting a screenshot just because of truncation.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "include_screenshot",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_SCREENSHOT)
                                        .put("description", "Whether to attach the current screen image for the model; default false. Enable it explicitly only when UI nodes are insufficient for the task.")
                                )
                                .put(
                                    "include_ui_tree",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", AgentScreenObservationContract.DEFAULT_INCLUDE_UI_TREE)
                                        .put("description", "Whether to return the UI node list; default true")
                                )
                                .put(
                                    "max_nodes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentScreenObservationContract.MIN_MAX_NODES)
                                        .put("maximum", AgentScreenObservationContract.MAX_MAX_NODES)
                                        .put("default", AgentScreenObservationContract.DEFAULT_MAX_NODES)
                                        .put("description", "Return between 1 and 120 UI nodes; default 60")
                                )
                        )
                )
            )
    }
}
