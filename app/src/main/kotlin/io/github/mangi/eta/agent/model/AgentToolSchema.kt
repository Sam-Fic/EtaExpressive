package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentToolSchema {
    fun coordinateSpace(): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("enum", JSONArray().put("normalized").put("screen").put("screenshot"))
            .put(
                "description",
                "Required. normalized (recommended): x and y are 0-999 proportions of the whole screen; " +
                    "use it when working from a screenshot, since image scaling does not affect it. " +
                    "screen: real screen pixels; ui_nodes center/bounds already use this space. " +
                    "screenshot: original pixels of the most recent attachment; use it only when you are " +
                    "sure you are looking at an unscaled image.",
            )

    fun function(
        name: String,
        description: String,
        parameters: JSONObject,
    ): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", parameters),
            )
}
