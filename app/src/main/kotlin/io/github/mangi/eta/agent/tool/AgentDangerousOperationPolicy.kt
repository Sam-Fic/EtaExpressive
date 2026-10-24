package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.config.Prefs

/**
 * 危险操作的逐次确认。
 *
 * 此前只有两种状态：整类能力开着，模型就直接执行；或者整类关掉。像重启设备、卸载应用、
 * 清空数据、删除用户这类**不可逆**操作，中间没有「问问我再做」这一档。
 *
 * 这里复用已有的 [ToolExecutionDecision.Reject]：命中危险清单且用户开启了确认时，
 * 拒绝本次调用并回一个明确的可确认错误，由上层弹窗向用户确认；用户同意后模型重新发起
 * 同一个调用即可通过（确认结果只作用于一次调用，不会留下长期「已批准」状态）。
 *
 * 只在**写操作**上生效：读类工具即使名字相似也不该被打扰。
 */
internal object AgentDangerousOperationPolicy {
    const val CONFIRM_CODE = "USER_CONFIRMATION_REQUIRED"

    /**
     * 不可逆或影响面较大的操作。刻意保持短清单：噪声太多会让用户习惯性点通过，
     * 反而降低确认的价值。
     */
    private val dangerousTools = setOf(
        "power_control",        // 重启/关机
        "manage_app",           // 卸载、清除数据
        "package_control",      // 停用系统应用、卸载系统更新
        "user_control",         // 删除用户（清空其全部数据）
        "manage_file",          // 删除/移动设备文件
        "appops_control",       // 底层权限管控
        "set_timezone",         // 系统时区
        "connect_wifi",         // 会写入 Wi-Fi 密码
        "set_device_state",     // 飞行模式/移动数据等连通性开关
    )

    /** 这些动作显然无害（查看/恢复默认），不该被确认打断。 */
    private val safeActions = setOf(
        "list", "current", "get", "reset", "reset_resolution", "reset_density", "reset_overscan",
    )

    fun isDangerous(toolName: String, argumentsJson: String): Boolean {
        val name = toolName.trim().lowercase()
        if (name !in dangerousTools) return false
        val action = runCatching {
            org.json.JSONObject(argumentsJson.ifBlank { "{}" }).optString("action").trim().lowercase()
        }.getOrDefault("")
        return action !in safeActions
    }

    /**
     * 需要用户确认时返回拒绝决策；否则返回 null（交回调用方继续原有的准入检查）。
     * [confirmationEnabled] 由用户开关决定；关闭时保持历史行为（直接执行）。
     */
    fun decision(
        toolName: String,
        argumentsJson: String,
        confirmationEnabled: Boolean = Prefs.isEnabled(Prefs.Keys.AGENT_CONFIRM_DANGEROUS_TOOLS),
    ): ToolExecutionDecision.Reject? {
        if (!confirmationEnabled) return null
        if (!isDangerous(toolName, argumentsJson)) return null
        return ToolExecutionDecision.Reject(
            code = CONFIRM_CODE,
            message = "这个操作不可逆或影响较大，需要用户确认。已暂停本次调用，未做任何改动；" +
                "请向用户说明将要做什么并取得同意，然后重新发起同一个调用；不要换其他工具绕过确认。",
        )
    }
}
