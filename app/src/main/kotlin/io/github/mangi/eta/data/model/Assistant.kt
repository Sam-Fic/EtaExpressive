package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

internal object AssistantDefaults {
    val ENABLED_SKILL_IDS: List<String> = listOf(
        "self-improving-agent",
        "skill-creator",
        "skill-installer",
    )
}

@Serializable
data class AssistantProfile(
    val id: String,
    val name: String,
    val prompt: String,
    val avatarFileName: String? = null,
    val createdAt: Long = 0L,
    val memoryEnabled: Boolean = true,
    val enabledSkillIds: List<String> = AssistantDefaults.ENABLED_SKILL_IDS,
    /**
     * 按助手的工具开关（组 id → 是否启用）。
     *
     * 记忆与技能早已是按助手的，工具却是全局的：同一台设备上无法表达
     * 「给这个助手终端，另一个不给」。缺失的组回落到全局开关，
     * 因此老档案不需要迁移，用户没动过的组行为不变。
     */
    val toolGroupSwitches: Map<String, Boolean> = emptyMap(),
) {
    /** 该组对这个助手是否启用；未设置时回落到 [fallback]（通常为全局开关）。 */
    fun toolGroupEnabled(groupId: String, fallback: Boolean): Boolean =
        toolGroupSwitches[groupId] ?: fallback
}

internal object AssistantPrompt {
    const val DEFAULT_ID = "default"
    const val DEFAULT_NAME = "Eta"

    const val DEFAULT_BODY =
        "**先做事，少客套。** 不要用「好的！」「很高兴为你效劳」开头，直接帮用户把事情做完。\n" +
            "\n" +
            "**要有立场。** 可以不同意、可以有偏好，也可以觉得有些事有趣、有些事无聊。\n" +
            "\n" +
            "**先行动，再提问。** 能查的先查，带答案回来，而不是先抛一堆问题。"

    const val EMPTY_PROMPT =
        "你是 Eta，运行在 Android 设备上的 AI 助手。" +
            "你可以回答问题、与用户交流，也可以通过当前可用的工具了解设备情况并执行操作。" +
            "回答使用用户的语言，简洁、直接、自然。"

    fun identity(name: String): String {
        val safe = name.trim().ifBlank { DEFAULT_NAME }
        return "你是 $safe，运行在 Android 设备上的 AI 助手。" +
            "你可以回答问题、与用户交流，也可以通过当前可用的工具了解设备情况并执行操作。" +
            "回答使用用户的语言，简洁、直接、自然。"
    }

    /**
     * 内置调度台身份：种子在 AssistantRepository，删除闸在同仓库；
     * 工具目录与执行拦截都以 [DISPATCHER_ID] 判定，改 id 等于迁移协议。
     * 提示词即行为律（只调度不执行、先判去向、brief 自包含、不轮询、一次一问、如实汇报）。
     */
    const val DISPATCHER_ID = "builtin-dispatcher"
    const val DISPATCHER_NAME = "调度台"

    val DISPATCHER_PROMPT: String = """
        你在「调度台」工作：你是调度者，不是执行者。
        - 需要动手的活（读屏幕、跑命令、写文件、操作应用、长输出检索）一律用 task_dispatch 派发给工作会话，不要自己执行。一句话能答、无需动手的，直接答。
        - 派发前先判断去向：用 conversations_list 与 conversation_history 了解现有会话的主题与状态；主题一致的接续派发（传 conversation_id），没有合适的会话就新建（new_title）。宁可新建，也不把不相关的事塞进同一个会话。
        - brief 必须自包含：目标、验收标准、涉及的上下文、约束——不要假设工作会话看得到我们的对话。origin_text 保留用户原话。
        - 不要轮询工作会话状态，完成通知会自动送达；多个独立任务在同一轮并行派发。
        - 需要用户决策时用 ask_user，一次只问一个具体问题；其余时刻保持安静。
        - 回报到达后只做汇总与决策（继续派发、接续、或向用户汇报），不原文转述长输出。如实汇报：失败报失败，未完成标未完成。
    """.trimIndent()

    fun build(name: String, prompt: String): String {
        val body = prompt.trim()
        if (body.isEmpty()) return identity(name)
        return identity(name) + "\n\n人格设定：\n" + body
    }
}

internal object AssistantStorage {
    fun id(raw: String): String {
        require(raw.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) { "助手 ID 无效" }
        return raw
    }
}

