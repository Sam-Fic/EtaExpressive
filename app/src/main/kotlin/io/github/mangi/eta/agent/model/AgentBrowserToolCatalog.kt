package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentBrowserToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "browser_use",
                description = "Operate Eta's offscreen multi-tab browser (at most 3 tabs per session, numbered only 0, 1 and 2, never borrowing pages from other sessions); it does not switch to an external browser. One call performs exactly one action. Supports new_tab, close_tab and list_tabs; tab_id selects a tab and defaults to the current tab. navigate accepts a full URL, a domain, a search phrase, /workspace, /var/minis or minis://. desktop_chrome and mobile_chrome are available. get_cookies returns only a summary plus /var/minis/offloads/env_cookies_xxx.sh; plaintext never enters the conversation. Usually navigate first, then extract Markdown with get_readable, or inspect structure with find_elements / get_backbone. Selectors follow the current DOM; a search box may be a textarea or contenteditable rather than an input. If scroll_and_collect returns 0 items, check the structure and the keyword filter before concluding the page is empty. go_back/go_forward step through the history index one entry at a time. Use open_uri when a URI must be handed explicitly to an external app.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "The single browser action performed by this call.")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("new_tab")
                                            .put("close_tab")
                                            .put("list_tabs")
                                            .put("navigate")
                                            .put("get_readable")
                                            .put("get_text")
                                            .put("find_elements")
                                            .put("click")
                                            .put("type")
                                            .put("scroll")
                                            .put("screenshot")
                                            .put("get_page_info")
                                            .put("go_back")
                                            .put("go_forward")
                                            .put("reload")
                                            .put("wait_for_selector")
                                            .put("execute_js")
                                            .put("get_backbone")
                                            .put("hover")
                                            .put("fetch")
                                            .put("get_cookies")
                                            .put("set_cookies")
                                            .put("set_user_agent")
                                            .put("set_viewport")
                                            .put("scroll_and_collect")
                                            .put("wait_for_dom_stable"),
                                    ),
                            )
                            .put("tab_id", JSONObject().put("type", "integer").put("description", "Target tab id, from list_tabs or a previous result; if the tab does not exist it will not silently switch to another tab."))
                            .put("url", JSONObject().put("type", "string").put("description", "Target for navigate or fetch; navigate also accepts a domain or a search phrase."))
                            .put("selector", JSONObject().put("type", "string").put("description", "CSS selector used by click, type, hover, get_text, find_elements, scroll or wait_for_selector."))
                            .put("text", JSONObject().put("type", "string").put("description", "Text for type to enter. It is sent only to the tool and never shown in the run summary."))
                            .put("submit", JSONObject().put("type", "boolean").put("description", "Whether type submits its form after entering text; default false."))
                            .put("coordinate_x", JSONObject().put("type", "integer").put("description", "Viewport X coordinate for click, type or hover; used together with coordinate_y."))
                            .put("coordinate_y", JSONObject().put("type", "integer").put("description", "Viewport Y coordinate for click, type or hover; used together with coordinate_x."))
                            .put("amount", JSONObject().put("type", "integer").put("description", "Number of pixels scroll moves."))
                            .put("direction", JSONObject().put("type", "string").put("enum", JSONArray().put("up").put("down")).put("description", "Scroll direction for scroll."))
                            .put("offset", JSONObject().put("type", "integer").put("description", "Starting offset of the text returned by get_readable or get_text; default 0."))
                            .put("max_chars", JSONObject().put("type", "integer").put("description", "Maximum characters returned by get_readable or get_text."))
                            .put("read_image", JSONObject().put("type", "boolean").put("description", "Whether screenshot attaches the image for the model to view directly; default true."))
                            .put("full_page", JSONObject().put("type", "boolean").put("description", "Whether screenshot captures the full page where possible; default false. Height is capped."))
                            .put("timeout_ms", JSONObject().put("type", "integer").put("description", "Timeout in milliseconds for navigate, wait_for_selector or wait_for_dom_stable."))
                            .put("timeout", JSONObject().put("type", "integer").put("description", "Timeout in milliseconds for wait_for_dom_stable; timeout_ms is also accepted."))
                            .put("script", JSONObject().put("type", "string").put("description", "Script for execute_js to run. It executes inside an async function, so await and return are supported."))
                            .put("user_agent", JSONObject().put("type", "string").put("enum", JSONArray().put("desktop_chrome").put("mobile_chrome")).put("description", "Browser identity for set_user_agent."))
                            .put("max_depth", JSONObject().put("type", "integer").put("description", "Maximum DOM depth for get_backbone; default 5."))
                            .put("viewport_width", JSONObject().put("type", "integer").put("description", "Viewport width for set_viewport, at least 320."))
                            .put("viewport_height", JSONObject().put("type", "integer").put("description", "Viewport height for set_viewport, at least 320."))
                            .put("reset", JSONObject().put("type", "boolean").put("description", "With set_viewport, true restores the default viewport for the current UA."))
                            .put("item_selector", JSONObject().put("type", "string").put("description", "CSS selector scroll_and_collect uses to collect items."))
                            .put("scroll_count", JSONObject().put("type", "integer").put("description", "Number of scroll steps for scroll_and_collect; default 5."))
                            .put("keywords", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Keyword filter for get_cookies or scroll_and_collect."))
                            .put("fuzzy", JSONObject().put("type", "boolean").put("description", "get_cookies keyword matching: true requires the name to contain all keywords, false matches any keyword exactly; default true."))
                            .put("cookies", JSONObject().put("type", "string").put("description", "JSON array string for set_cookies; each item must contain at least name and value.")),
                    )
                    .put("required", JSONArray().put("action")),
            ),
        )
    }
}
