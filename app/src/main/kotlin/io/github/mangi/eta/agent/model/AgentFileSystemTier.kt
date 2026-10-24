package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs

/**
 * 文件系统与 Shell 的三档权限。
 *
 * 过去 [Prefs.Keys.AGENT_TERMINAL_TOOLS] 一个开关同时管「读文件」和「写任意文件 / 跑 shell」，
 * 想给助手看日志就必须同时给出写盘与任意命令执行，粒度太粗。
 *
 * 三档由两个独立的布尔开关表达（不用枚举存储，避免迁移与并发写入）：
 * - `AGENT_TERMINAL_TOOLS`：读写文件 + 执行命令的总闸（历史开关，保持原语义）；
 * - `AGENT_SHELL_TOOLS`：在总闸之上再单独允许执行命令。
 *
 * 组合结果：
 * | terminal | shell | 档位 |
 * |---|---|---|
 * | off | * | [Disabled]（文件工具与 run_command/terminal 全部不下发） |
 * | on | off | [ReadWrite]（可读写文件，不可执行命令） |
 * | on | on | [ReadWriteShell]（原来「终端工具」的整体语义） |
 *
 * 只读档在目录层面由 `readOnlyFileTools` 表达：即使用户只想要只读，也用同一个总闸的
 * 关闭 + 单独开只读来实现；[ReadOnly] 保留给未来的显式只读授权。
 */
internal object AgentFileSystemTier {
    enum class Tier(val id: String) {
        /** 完全关闭：文件工具与命令执行都不下发。 */
        DISABLED("disabled"),

        /** 只读：仅下发读取类文件工具。 */
        READ_ONLY("read_only"),

        /** 读写文件，但不能执行命令。 */
        READ_WRITE("read_write"),

        /** 读写文件 + 执行命令（租户/容器内的 shell）。 */
        READ_WRITE_SHELL("read_write_shell"),
    }

    /** 会执行命令的工具；只有 [Tier.READ_WRITE_SHELL] 才下发。 */
    val shellTools: Set<String> = setOf("run_command", "terminal")

    /** 会写盘或改文件的工具；[Tier.READ_ONLY] 时不下发。 */
    val writeTools: Set<String> = setOf("write_file", "edit_file")

    /** 只读文件工具；[Tier.DISABLED] 以外的档位都下发。 */
    val readTools: Set<String> = setOf(
        "read_file", "list_directory", "stat_file", "glob_files", "grep_files", "read_image",
    )

    val allFileTools: Set<String> get() = readTools + writeTools + shellTools

    fun of(terminalTools: Boolean, shellTools: Boolean): Tier = when {
        !terminalTools -> Tier.DISABLED
        shellTools -> Tier.READ_WRITE_SHELL
        else -> Tier.READ_WRITE
    }

    /** 从当前首选项读取；执行侧每次调用重新求值，与既有工具开关的行为一致。 */
    fun current(): Tier = of(
        terminalTools = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
        shellTools = Prefs.isEnabled(Prefs.Keys.AGENT_SHELL_TOOLS),
    )

    fun allowsFileTools(tier: Tier): Boolean = tier != Tier.DISABLED

    fun allowsWrite(tier: Tier): Boolean = tier == Tier.READ_WRITE || tier == Tier.READ_WRITE_SHELL

    fun allowsShell(tier: Tier): Boolean = tier == Tier.READ_WRITE_SHELL

    /** 该档位是否应当下发某个文件/命令工具。 */
    fun publishes(tier: Tier, toolName: String): Boolean = when (toolName) {
        in shellTools -> allowsShell(tier)
        in writeTools -> allowsWrite(tier)
        in readTools -> allowsFileTools(tier)
        else -> true
    }
}
