package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentTerminalToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "terminal",
                    description = "Manage terminal sessions on this device. environment=android runs Android " +
                        "system commands and root operations; environment=linux runs the Alpine or Debian " +
                        "environment chosen in settings (pass environment=linux and cwd=/workspace/<project> " +
                        "for Linux tools and project Git). Default android; run_command is Android-only and " +
                        "never enters Linux. Do not repeat a failed call in an unchanged environment. " +
                        "Apktool build needs an ARM64 AAPT2 runtime. Lifecycles: open_and_exec = one-shot; " +
                        "open = persistent session, then exec/close with session_id; async=true without " +
                        "session_id = long-running job, read with read_async_result by job_id; daemon_start = " +
                        "service that must survive the run (ports, panels, watchers), detached and logged to a " +
                        "file until daemon_stop or reboot, managed by daemon_list/daemon_logs/daemon_stop with " +
                        "task_id; close stops jobs and sessions.",
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
                                                .put("open")
                                                .put("exec")
                                                .put("open_and_exec")
                                                .put("read_async_result")
                                                .put("close")
                                                .put("daemon_start")
                                                .put("daemon_list")
                                                .put("daemon_logs")
                                                .put("daemon_stop")
                                        )
                                        .put("description", "Session/job lifecycle. open: create session. exec: run in session or cwd. open_and_exec: one-shot. read_async_result: async output by job_id. close: session_id or job_id. daemon_start: detached long-lived service, returns task_id. daemon_list: tasks with liveness. daemon_logs: tail a task log. daemon_stop: terminate and remove.")
                                )
                                .put(
                                    "identity",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("user").put("root"))
                                        .put("description", "Host identity. Android uses whatever is currently available; Linux uses user or root per backend. Simulated root inside PRoot grants no Android privileges.")
                                )
                                .put(
                                    "environment",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("android").put("linux"))
                                        .put("description", "android: native shell with BusyBox applets when available. linux: Alpine or Debian per settings. Default android.")
                                )
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Command to run in the selected environment. Required for exec/open_and_exec.")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Working directory. Default /data/local/tmp/eta (android) or /workspace (linux); relative paths use the same base. ~/ means /storage/emulated/0.")
                                )
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Timeout ms. Default 30000, max 180000.")
                                )
                                .put(
                                    "merge_stderr",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Append stderr to stdout in responses.")
                                )
                                .put(
                                    "session_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Session id from action=open; use with exec or close.")
                                )
                                .put(
                                    "job_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Async job id from async=true; use with read_async_result or close.")
                                )
                                .put(
                                    "task_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Daemon task id from daemon_start; use with daemon_logs or daemon_stop.")
                                )
                                .put(
                                    "async",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "Run in a separate background shell, return job_id immediately. Never combine with session_id; read output with read_async_result.")
                                )
                                .put(
                                    "offset_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "read_async_result: stdout start offset. Default 0.")
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "read_async_result: max stdout characters. Default 8000, max 16000.")
                                )
                                .put(
                                    "close_if_done",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "For read_async_result, remove the async job when it has completed.")
                                )
                        )
                        .put("required", JSONArray().put("action"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "run_command",
                    description = "Run a command only in the non-interactive Android Root shell; it never enters Debian or Alpine. Suited to Android system information, package management and local file inspection. Linux tooling and workspace Git operations must use terminal(environment=linux,cwd=/workspace/project); this tool does not accept environment. Every call is a new shell; do not run interactive or long-lived commands. If a command does not exist, do not retry it unchanged in the same environment, and never replay commands with side effects in another environment automatically.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "command",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Shell command to run; pipes and redirection are allowed.")
                                )
                                .put(
                                    "cwd",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "Working directory; default /data/local/tmp/eta. Relative paths resolve against it too; ~/ denotes /storage/emulated/0 in user storage.")
                                )
                                .put(
                                    "timeout_seconds",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Timeout in seconds from 1 to 180; default 30.")
                                )
                        )
                        .put("required", JSONArray().put("command"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "read_file",
                    description = "Read Android file contents; Linux /workspace, /var/minis and minis:// paths of the selected environment map automatically to host files. Suited to configs, logs and small text files; read large files in chunks with offset_bytes/max_bytes.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put(
                                    "offset_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Byte offset to start at; default 0.")
                                )
                                .put(
                                    "max_bytes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Maximum bytes to read from 1 to 262144; default 65536.")
                                )
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "write_file",
                    description = "Write an Android file, overwriting or appending; parent directories are created automatically. Use it for tasks that explicitly need file changes.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("content", JSONObject().put("type", "string"))
                                .put(
                                    "append",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "true appends, false overwrites; default false.")
                                )
                        )
                        .put("required", JSONArray().put("path").put("content"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "list_directory",
                    description = "List directory contents; Linux /workspace, /var/minis and minis:// paths of the selected environment map automatically to host directories. Defaults to /data/local/tmp/eta and outputs something like ls -l.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("path", JSONObject().put("type", "string"))
                                .put("show_hidden", JSONObject().put("type", "boolean"))
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Return between 1 and 200 lines; default 80.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "stat_file",
                    description = "Query the canonical path, type, size and revision of a known file or directory without reading its contents." +
                        "The returned revision is used by edit_file and for resuming paged reads.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            fileProperties()
                                .put("path", filePathSchema())
                        )
                        .put("required", JSONArray().put("path"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "edit_file",
                    description = "Exactly replace old_text in a UTF-8 file no larger than 512 KiB. It must match uniquely by default;" +
                        "if it is missing or matches in several places nothing is written, and you should re-read or add context. The old content is checked again before writing.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            fileProperties()
                                .put("path", filePathSchema())
                                .put(
                                    "old_text",
                                    fileTextSchema("Must match the old content in the file exactly and must not be empty.", 524288)
                                        .put("minLength", 1)
                                )
                                .put("new_text", fileTextSchema("Replacement text; an empty string deletes the matched span.", 524288))
                                .put(
                                    "replace_all",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", false)
                                )
                                .put("expected_revision", fileRevisionSchema())
                        )
                        .put("required", JSONArray().put("path").put("old_text").put("new_text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "glob_files",
                    description = "Recursively find files in the working directory by path pattern. Supports *, ? and **; hidden directories are skipped and directory symlinks are not followed." +
                        "Results are bounded; continue with the cursor unchanged, and do not claim every file was searched when partial=true.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            fileProperties()
                                .put("path", filePathSchema())
                                .put(
                                    "pattern",
                                    fileTextSchema("Pattern relative to the search directory, for example **/*.json.", 500)
                                        .put("minLength", 1)
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Maximum number of entries; default 80.")
                                        .put("minimum", 1)
                                        .put("maximum", 200)
                                )
                                .put("cursor", fileTextSchema("Opaque continuation cursor returned by the previous query; keep every other query condition unchanged.", 512))
                                .put(
                                    "include_hidden",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", false)
                                )
                        )
                        .put("required", JSONArray().put("pattern"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "grep_files",
                    description = "Search UTF-8 text in the working directory for literal text; returns paths, line numbers and matching lines. Case-sensitive by default;" +
                        "it scans in segments bounded by entry and byte budgets and resumes with the cursor; binary and inaccessible items are skipped and an incomplete range is reported.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            fileProperties()
                                .put("path", filePathSchema())
                                .put(
                                    "query",
                                    fileTextSchema("Literal text to find; not a regular expression.", 500)
                                        .put("minLength", 1)
                                )
                                .put("glob", fileTextSchema("File pattern relative to the directory; default **/*.", 500))
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "Maximum number of matches; default 50.")
                                        .put("minimum", 1)
                                        .put("maximum", 200)
                                )
                                .put("cursor", fileTextSchema("Continuation cursor returned by the previous query; keep every other query condition unchanged.", 512))
                                .put(
                                    "case_sensitive",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", true)
                                )
                                .put(
                                    "include_hidden",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("default", false)
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
    }

    /** 文件工具共用的环境、身份与工作目录；与终端保持同一命名空间。 */
    private fun fileProperties(): JSONObject = JSONObject()
        .put(
            "environment",
            JSONObject()
                .put("type", "string")
                .put("enum", JSONArray(listOf("android", "linux")))
                .put("description", "Environment the file path belongs to; default android. linux uses the distribution and backend selected in settings.")
        )
        .put(
            "identity",
            JSONObject()
                .put("type", "string")
                .put("enum", JSONArray(listOf("user", "root")))
                .put("description", "Optional host execution identity, the same as terminal; by default it is chosen from the environment and Root availability.")
        )
        .put(
            "cwd",
            fileTextSchema("Optional working directory; when omitted it shares the current environment's default workspace with terminal.", 4096)
        )

    private fun filePathSchema(): JSONObject =
        fileTextSchema("Absolute path, or a path relative to cwd; filesystem paths only, content URIs are rejected.", 4096)

    private fun fileRevisionSchema(): JSONObject =
        fileTextSchema("revision from a previous file or directory result; revisions from different environments or identities must not be mixed.", 256)

    private fun fileTextSchema(description: String, maxLength: Int): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("description", description)
            .put("maxLength", maxLength)

}
