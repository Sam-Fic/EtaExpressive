package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 屏幕手势与节点交互工具 schema。 */
internal object AgentGestureToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "tap",
                    description = "Tap a coordinate. Prefer tap_element when nodes are available; when locating from a screenshot use coordinate_space=normalized (0-999), and when coordinates come from ui_nodes use screen.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_area",
                    description = "Tap the centre of a rectangle. Prefer this tool for large buttons, big list rows and visible text areas. The coordinate space is the same as tap; coordinate_space is required.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_element",
                    description = "Tap a UI node from a specific observation snapshot. index and observation_id must come from the same most recent observe_screen; if the observation has expired, observe again first. The runtime confirms Eta's accessibility service is connected before executing.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "UI node index returned by the same observe_screen.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "observation_id from the same most recent observe_screen as index.")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press",
                    description = "Long-press a coordinate. Prefer long_press_element when nodes are available. The coordinate space is the same as tap; coordinate_space is required.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Long-press duration from 300 to 3000; default 800")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press_element",
                    description = "Long-press a UI node from a specific observation snapshot. index and observation_id must come from the same most recent observe_screen; if the observation has expired, observe again first. The runtime confirms Eta's accessibility service is connected before executing.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "UI node index returned by the same observe_screen.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "observation_id from the same most recent observe_screen as index.")
                                )
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Long-press duration from 300 to 3000; default 800")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "swipe",
                    description = "Drag from one coordinate to another (finger direction), for precise gestures such as sliders, carousels and maps. To browse a list use scroll. The coordinate space is the same as tap; coordinate_space is required.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Swipe duration from 100 to 2000; default 500")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2").put("coordinate_space"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll",
                    description = "Scroll the main list of the current screen in a content-browsing direction: down shows content below, up shows content above, and left/right likewise. When the result has moved=false and at_boundary=true the list is at its end; change direction or stop. When the page has several lists, use scroll_element to pick the target.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                )
                                .put(
                                    "amount",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("page").put("small"))
                                        .put("description", "page moves about one screen (default); small reveals only a little new content, for nudging a target item into view."),
                                )
                        )
                        .put("required", JSONArray().put("direction"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll_element",
                    description = "Scroll a scrollable UI node from a specific observation snapshot in a content-browsing direction: down shows content below, up shows content above, left shows content to the left, right shows content to the right. index and observation_id must come from the same most recent observe_screen; if the observation has expired, observe again first. The runtime confirms Eta's accessibility service is connected before executing.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Scrollable UI node index returned by the same observe_screen.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "observation_id from the same most recent observe_screen as index.")
                                )
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                        .put("description", "Content-browsing direction; down shows content below, up shows content above.")
                                )
                                .put(
                                    "amount",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("page").put("small"))
                                        .put("description", "page moves about one screen (default); small reveals only a little new content, for nudging a target item into view."),
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id").put("direction"))
                )
            )
    }
}
