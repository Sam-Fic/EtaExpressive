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

    /**
     * 观察快照的配对说明，原先在每个节点工具的描述里各写一份，措辞容易漂移。
     * index / observation_id 必须出自同一次最近观察；观察过期需重新观察。
     */
    val observationPairing: String =
        "index and observation_id must come from the same most recent observe_screen; " +
            "if the observation has expired, observe again first."

    /** 无障碍前置条件的统一说明，避免每个工具各写一套措辞。 */
    val accessibilityRequired: String =
        "The runtime confirms Eta's accessibility service is connected before executing."

    /** 节点动作的完整配对要求：配对说明 + 前置条件。 */
    fun observationAction(verb: String): String =
        "$verb a UI node from a specific observation snapshot. $observationPairing $accessibilityRequired"

    /** observation_id 参数的标准描述：必须与同一批里的 index 出自同一次最近观察。 */
    val observationPairedWithIndex: String =
        "observation_id from the same most recent observe_screen as index."

    fun observationRef(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

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
