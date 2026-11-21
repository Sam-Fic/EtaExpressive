package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * 声明取餐码/取件码工具。
 *
 * 数据来源有三条：通知监听自动拦截（Eta 的通知历史链路）、助手面板「记取餐码」预设
 * 的本地 PP-OCR 识别、用户手动录入。本工具是 Agent 侧的读写口：保存对话里提到的码、
 * 查询「我的取餐码是什么」、标完成、删除。
 *
 * 与 `search_personal_orders`（ColorOS 小布记忆库的订单检索）互补：那边查系统记忆，
 * 这边查 Eta 自己的取餐码记录；两者互不重叠。
 */
internal object AgentPickupCodeToolCatalog {
    const val PICKUP_CODE = "pickup_code"

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = PICKUP_CODE,
                description = "Manage the user's pickup codes (food pickup codes and parcel pickup codes). " +
                    "Codes are captured automatically from delivery notifications and the assistant's " +
                    "screen-recognition preset; this tool is the single read/write entry for the agent. " +
                    "action=save stores a code: code is required, order_type is one of 餐食/饮品/快递, " +
                    "brand_name and pickup_location are optional. Saving is idempotent for unfinished duplicates: " +
                    "if the same code is already pending, the existing record is returned instead of a new row. " +
                    "action=search finds pending and completed records by code/brand/location keyword (use it " +
                    "before saving when the user mentions a code, to avoid duplicates). " +
                    "action=list returns pending records newest first; action=history returns completed records. " +
                    "action=complete marks a record done by id; action=delete removes one by id. " +
                    "When asked \"我的取餐码是什么\", prefer action=list and read out the codes.",
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
                                            .put("save")
                                            .put("search")
                                            .put("list")
                                            .put("history")
                                            .put("complete")
                                            .put("delete"),
                                    )
                                    .put("description", "Required. What to do."),
                            )
                            .put(
                                "code",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 64)
                                    .put("description", "action=save requires this: the pickup code itself. " +
                                        "action=search optional: keyword for code matching."),
                            )
                            .put(
                                "order_type",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("餐食").put("饮品").put("快递"))
                                    .put("description", "action=save only; default 餐食."),
                            )
                            .put(
                                "brand_name",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 64)
                                    .put("description", "action=save only: brand such as 美团外卖 / 顺丰. " +
                                        "action=search optional: keyword for brand matching."),
                            )
                            .put(
                                "pickup_location",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 200)
                                    .put("description", "action=save only: pickup location such as a locker or counter. " +
                                        "action=search optional: keyword for location matching."),
                            )
                            .put(
                                "id",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "action=complete/delete requires this: record id from a previous result."),
                            )
                            .put(
                                "query",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 200)
                                    .put("description", "action=search: free keyword over code/brand/location."),
                            )
                            .put(
                                "limit",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("minimum", 1)
                                    .put("maximum", 50)
                                    .put("description", "Max results for search/list/history (default 20)."),
                            ),
                    )
                    .put("required", JSONArray().put("action")),
            ),
        )
    }
}
