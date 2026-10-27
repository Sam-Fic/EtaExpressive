package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentBrowserToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "browser_use",
                description = "Operate Eta's offscreen multi-tab browser (max 3 tabs per session, ids 0-2; " +
                    "never borrows pages from other sessions) without switching to an external browser. " +
                    "One call = one action. Manage tabs with new_tab/close_tab/list_tabs; tab_id defaults " +
                    "to the current tab. navigate accepts a URL, domain, search phrase, /workspace, " +
                    "/var/minis or minis://. Identities: desktop_chrome, mobile_chrome. get_cookies returns " +
                    "only a summary plus /var/minis/offloads/env_cookies_xxx.sh; plaintext never enters the " +
                    "conversation. Typical flow: navigate, then get_readable for Markdown or find_elements/" +
                    "get_backbone for structure. Selectors follow the current DOM (a search box may be " +
                    "textarea/contenteditable). If scroll_and_collect returns 0, check structure and filters " +
                    "before concluding the page is empty. go_back/go_forward step history one entry at a time. " +
                    "Use open_uri only to hand a URI to an external app.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "Action to perform.")
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
                            .put("tab_id", JSONObject().put("type", "integer").put("description", "Tab id from list_tabs or a prior result; an unknown id never switches to another tab."))
                            .put("url", JSONObject().put("type", "string").put("description", "Target for navigate/fetch; navigate also accepts a domain or search phrase."))
                            .put("selector", JSONObject().put("type", "string").put("description", "CSS selector (click, type, hover, get_text, find_elements, scroll, wait_for_selector)."))
                            .put("text", JSONObject().put("type", "string").put("description", "Text for type. Tool-only; never shown in the run summary."))
                            .put("submit", JSONObject().put("type", "boolean").put("description", "type: submit the form after entering. Default false."))
                            .put("coordinate_x", JSONObject().put("type", "integer").put("description", "Viewport X for click/type/hover; pair with coordinate_y."))
                            .put("coordinate_y", JSONObject().put("type", "integer").put("description", "Viewport Y for click/type/hover; pair with coordinate_x."))
                            .put("amount", JSONObject().put("type", "integer").put("description", "Pixels scroll moves."))
                            .put("direction", JSONObject().put("type", "string").put("enum", JSONArray().put("up").put("down")).put("description", "scroll direction."))
                            .put("offset", JSONObject().put("type", "integer").put("description", "Text start offset for get_readable/get_text. Default 0."))
                            .put("max_chars", JSONObject().put("type", "integer").put("description", "Max characters for get_readable/get_text."))
                            .put("read_image", JSONObject().put("type", "boolean").put("description", "screenshot: attach the image for the model to view. Default true."))
                            .put("full_page", JSONObject().put("type", "boolean").put("description", "screenshot: capture the full page. Default false; height is capped."))
                            .put("timeout_ms", JSONObject().put("type", "integer").put("description", "Timeout ms (navigate, wait_for_selector, wait_for_dom_stable)."))
                            .put("timeout", JSONObject().put("type", "integer").put("description", "wait_for_dom_stable timeout ms; timeout_ms also accepted."))
                            .put("script", JSONObject().put("type", "string").put("description", "execute_js script; runs in an async function, so await/return work."))
                            .put("user_agent", JSONObject().put("type", "string").put("enum", JSONArray().put("desktop_chrome").put("mobile_chrome")).put("description", "set_user_agent identity."))
                            .put("max_depth", JSONObject().put("type", "integer").put("description", "get_backbone max DOM depth. Default 5."))
                            .put("viewport_width", JSONObject().put("type", "integer").put("description", "set_viewport width (>= 320)."))
                            .put("viewport_height", JSONObject().put("type", "integer").put("description", "set_viewport height (>= 320)."))
                            .put("reset", JSONObject().put("type", "boolean").put("description", "set_viewport: true restores the current UA default viewport."))
                            .put("item_selector", JSONObject().put("type", "string").put("description", "Item selector for scroll_and_collect."))
                            .put("scroll_count", JSONObject().put("type", "integer").put("description", "scroll_and_collect steps. Default 5."))
                            .put("keywords", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "Keyword filter for get_cookies/scroll_and_collect."))
                            .put("fuzzy", JSONObject().put("type", "boolean").put("description", "get_cookies: true = name contains all keywords, false = exact match on any. Default true."))
                            .put("cookies", JSONObject().put("type", "string").put("description", "set_cookies: JSON array string; each item needs name and value.")),
                    )
                    .put("required", JSONArray().put("action")),
            ),
        )
    }
}
