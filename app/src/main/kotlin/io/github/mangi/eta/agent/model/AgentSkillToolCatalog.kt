package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentSkillToolCatalog {
    fun appendTo(
        tools: JSONArray,
        githubDiscovery: Boolean = false,
        githubInstall: Boolean = false,
    ) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "skills_list",
                    description = "List installed skills with id, name, description and capabilities. Use when the user asks what skills exist or whether one is installed.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Optional keyword to filter by skill id, name, or description.")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Max results to return, 1-200, default 50.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "skills_read",
                    description = "Read the full SKILL.md body of an installed skill by id, name or path. Use when a skill may be relevant but its body was not injected this turn.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "skillId",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "The skill's id, name, or SKILL.md path. Use skills_list first if unsure.")
                                )
                                .put(
                                    "maxChars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Max characters of body to return, 512-64000, default 16000.")
                                )
                        )
                        .put("required", JSONArray().put("skillId"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "skills_read_resource",
                    description = "Read a bounded UTF-8 text resource inside an enabled Skill, such as references/guide.md. Paths are relative to the Skill root; scripts are never executed.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "skillId",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "Installed Skill id, name, or SKILL.md path."),
                                )
                                .put(
                                    "relativePath",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 1_000)
                                        .put("description", "Safe path relative to the Skill root, for example references/guide.md."),
                                )
                                .put(
                                    "maxChars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 512)
                                        .put("maximum", 64_000)
                                        .put("description", "Maximum characters returned, default 16000."),
                                ),
                        )
                        .put(
                            "required",
                            JSONArray().put("skillId").put("relativePath"),
                        ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "skills_install_local",
                    description = "Install a Skill from a phone directory that holds exactly one SKILL.md (a downloaded folder, unzipped archive or workspace path). If it holds several, the result lists directories so one can be retried. Public storage needs all-files access. Installation never runs bundled scripts; the Skill is available next turn.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "directory",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 1_000)
                                        .put("description", "Absolute phone path, e.g. /sdcard/Download/my-skill, or a workspace path such as minis://workspace/my-skill."),
                                )
                                .put(
                                    "replaceExisting",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "True only when retrying one replaceable SKILL_CONFLICT for the same directory and id."),
                                )
                                .put(
                                    "expectedReplacementId",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "Required with replaceExisting; copy the exact id from the prior SKILL_CONFLICT."),
                                ),
                        )
                        .put("required", JSONArray().put("directory")),
                ),
            )
        if (githubDiscovery) appendGitHubDiscoveryTools(tools)
        if (githubInstall) appendGitHubInstallTool(tools)
    }

    private fun appendGitHubDiscoveryTools(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "skills_list_curated",
                    description = "List installable Skills from the public openai/skills catalog. Read-only. Use the returned commitSha as ref when installing so the inspected content cannot change.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject()),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "skills_inspect_github",
                    description = "Inspect a public GitHub repository and list every directory containing SKILL.md. Never installs. Use the returned commitSha as ref; if multiple candidates match, ask the user to choose exact paths.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "repository",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "Public owner/repository or https://github.com/... repository/tree/blob URL."),
                                )
                                .put(
                                    "ref",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200)
                                        .put("description", "Optional branch, tag or commit; defaults to the repository default branch."),
                                )
                                .put(
                                    "path",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 1_000)
                                        .put("description", "Optional repository-relative directory to narrow discovery."),
                                ),
                        )
                        .put("required", JSONArray().put("repository")),
                ),
            )
    }

    private fun appendGitHubInstallTool(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "skills_install_from_github",
                description = "Install selected Skill directories from a public GitHub repository. Paths must come from skills_inspect_github. For one replaceable user Skill conflict, retry the same repository, commitSha, path and id with replaceExisting=true; built-in Skills cannot be overwritten. Unsupported Apple/MiniS runtimes are rejected. Installation never runs scripts; Skills are available next turn.",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "repository",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 500)
                                    .put("description", "Public owner/repository or https://github.com/... repository/tree/blob URL."),
                            )
                            .put(
                                "ref",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 200)
                                    .put("description", "Optional branch, tag or commit; defaults to the repository default branch."),
                            )
                            .put(
                                "paths",
                                JSONObject()
                                    .put("type", "array")
                                    .put("description", "Exact repository-relative Skill roots returned by inspection.")
                                    .put(
                                        "items",
                                        JSONObject()
                                            .put("type", "string")
                                            .put("maxLength", 1_000),
                                    )
                                    .put("minItems", 1)
                                    .put("maxItems", 20)
                                    .put("uniqueItems", true),
                            )
                            .put(
                                "replaceExisting",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "True only when replaying one replaceable SKILL_CONFLICT; paths has one item and ref uses the conflict commitSha."),
                            )
                            .put(
                                "expectedReplacementId",
                                JSONObject()
                                    .put("type", "string")
                                    .put("maxLength", 500)
                                    .put("description", "Required with replaceExisting; copy the exact id from the prior SKILL_CONFLICT."),
                            ),
                    )
                    .put("required", JSONArray().put("repository").put("paths")),
            ),
        )
    }
}
