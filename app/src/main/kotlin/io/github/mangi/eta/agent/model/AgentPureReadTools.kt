package io.github.mangi.eta.agent.model

/**
 * 同一工具批次里可以并行执行的幂等只读工具。
 *
 * 判据刻意保守：不改变设备状态、不依赖主屏独占、不推进任何会话/任务状态、结果与调用顺序无关。
 * 明确排除的情况：
 * - 任何 GUI/屏幕/输入类工具（要占用主屏与 surfaceRouteLock，必须串行）；
 * - [io.github.mangi.eta.agent.question.AgentQuestionCoordinator.TOOL_NAME] 与委派/子任务类
 *   （会挂起或推进任务状态）；
 * - 会写入设置的 `eta_set_settings`、会修改文件的编辑类工具；
 * - 任何 `mcp_` / `recipe_` 工具：外部服务的语义未知，不能假设幂等。
 */
internal object AgentPureReadTools {
    private val names = setOf(
        // 工作区与文件读取（只读、带 revision 语义）
        "read_file",
        "list_directory",
        "search_files",
        "file_info",
        // 公开网络读取
        "web_search",
        "fetch_url",
        // 记忆与技能读取
        "memory_get",
        "skills_list",
        "skills_read",
        "skills_read_resource",
        // 只有真正会等网络/磁盘的工具才值得并行；get_current_context、device_status 这类
        // 本地廉价查询并行只会增加线程开销与调度不确定性，保持串行。
        "list_alarms",
        "list_active_timers",
        "get_setting",
        "read_clipboard_history",
    )

    fun isPureRead(name: String): Boolean {
        val normalized = name.trim().lowercase()
        if (normalized.startsWith("mcp_") || normalized.startsWith("recipe_")) return false
        return normalized in names
    }

    /**
     * 该批次里可以并行的调用 index。少于两个候选或同批次存在独占工具时不并行：
     * 并行只对「整批都是幂等只读」有意义，混合批次保持原有串行语义。
     */
    fun parallelIndices(names: List<String>): Set<Int> {
        if (names.size < 2) return emptySet()
        if (!names.all(::isPureRead)) return emptySet()
        return names.indices.toSet()
    }
}
