package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 文件路径到模型视觉输入的通用能力，不依赖任何个人数据 Provider。 */
internal object AgentFileVisionToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "read_image",
                description = "Read one image or video from a user-supplied path or a gallery content URI and provide it to the model as visual input. For video, one cover frame is extracted, so no terminal transcoding is needed first. Linux /workspace, /var/minis and minis:// paths map automatically to host files, so do not copy them to Android paths first. Call at most once per round; to view several, wait for the current result and observe it before reading the next one in a later round. This tool only lets the model see the frame; it never renders the image into the conversation. When the user asks to show, look at or preview an image, reference it in your reply body with Markdown image syntax ![description](absolute path).",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject().put(
                            "path",
                            JSONObject()
                                .put("type", "string")
                                .put("maxLength", 1_024)
                                .put("description", "Any absolute image or video path, file URI or gallery content URI; Linux /workspace and /var/minis paths are supported; local paths are read via Root, and video gets an automatic cover frame"),
                        ),
                    )
                    .put("required", JSONArray(listOf("path"))),
            ),
        )
    }
}
