package io.github.mangi.eta.agent.tool

import java.util.Locale

/**
 * shell 命令的风险启发式。
 *
 * 这不是沙箱，只是**执行前的确认触发器**：目标是把「一条命令可能删光数据/提权/覆盖系统」
 * 这类情形识别出来，让用户有机会说不。启发式的边界写在这里，便于后续维护：
 *
 * 能识别：
 * - 引号内的字符串按字面处理，`;` `&&` `||` `|` 换行拆成独立命令逐条判定；
 * - `sudo` / `doas` / `env` / `busybox` / `nohup` 等前缀会被剥掉再看真正的命令；
 * - `sh -c "..."` / `eval "..."` 会递归判定内部命令（有深度上限）；
 * - `rm` 的 `-r/-R/--recursive`、通配符、`$变量`、根目录目标；
 * - `dd`、`mkfs*`、`fdisk`、`parted`、`wipefs`、`shutdown`、`reboot`；
 * - `git reset --hard`、`git clean -f*`、`git push --force`、`git checkout -- .`；
 * - `chmod -R`、`chown -R`、`find -delete`、`xargs rm`、`truncate`、`shred`；
 * - SQL 的 `DROP TABLE/DATABASE`、`TRUNCATE`、无 `WHERE` 的 `DELETE FROM`；
 * - `pm uninstall/clear/disable`、`settings delete`、`content delete`；
 * - 输出重定向 `>` 覆盖写（`>>` 追加不算）、对 `/dev/block`、`/proc`、`/sys` 下设备节点的写入；
 * - `curl ... | sh`、`wget ... | sh` 这类远程脚本执行。
 *
 * 识别不了：
 * - 变量在运行时才展开的真实路径（因此只要破坏性命令的目标带 `$` 就升级为致命）；
 * - 通过 `alias`、脚本文件、`python -c`、`adb shell` 间接执行的逻辑；
 * - 命令替换 `$(...)` / 反引号的实际输出（判定为「不确定的高风险」）；
 * - 引号不配对等无法切分的情形（同样按高风险处理，宁可多问一次）。
 *
 * 结论：低风险不打断、中风险本会话确认一次、高风险每次确认、致命风险必须逐次确认。
 */
internal object AgentShellCommandRisk {

    private const val MAX_COMMAND_CHARS = 8_000
    private const val MAX_DEPTH = 3

    /** 只改变执行身份、不改变命令内容的前缀。 */
    private val PREFIX_WRAPPERS = setOf(
        "sudo", "doas", "env", "busybox", "toybox", "nohup", "time", "command", "stdbuf", "nice", "ionice",
    )

    private val SHELLS = setOf("sh", "bash", "ash", "zsh", "dash", "mksh", "ksh")

    private val ENV_ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=")

    /** 这些目录（或其本身）被递归删除时视为致命：一次误删就没有系统可用。 */
    private val ROOT_TARGETS = setOf(
        "/", "/system", "/vendor", "/boot", "/data", "/data/data", "/data/system", "/data/adb",
        "/storage", "/storage/emulated", "/storage/emulated/0", "/sdcard", "/proc", "/sys",
    )

    private val DROP_SQL = listOf(
        Regex("\\bdrop\\s+(table|database|schema|view|index)\\b"),
        Regex("\\btruncate\\s+(table|database)\\b"),
    )

    private val DELETE_FROM = Regex("\\bdelete\\s+from\\b")
    private val WHERE = Regex("\\bwhere\\b")

    fun analyze(command: String): AgentRiskAssessment {
        val raw = command.trim()
        if (raw.isEmpty()) return AgentRiskAssessment(AgentRiskLevel.LOW)
        if (raw.length > MAX_COMMAND_CHARS) {
            return AgentRiskAssessment(AgentRiskLevel.HIGH, listOf("命令过长，无法完整静态分析"))
        }
        // 配方里的 `{{参数}}` 占位符要到执行时才替换，实际命令无法静态确认。
        if (raw.contains("{{")) {
            return AgentRiskAssessment(AgentRiskLevel.HIGH, listOf("命令含运行时才展开的占位符，实际执行内容无法确认"))
        }
        return analyzeInner(raw, depth = 0)
    }

    private fun analyzeInner(raw: String, depth: Int): AgentRiskAssessment {
        if (depth > MAX_DEPTH) {
            return AgentRiskAssessment(AgentRiskLevel.HIGH, listOf("命令嵌套过深，实际执行内容无法确认"))
        }
        var level = AgentRiskLevel.LOW
        val reasons = mutableListOf<String>()
        val raise: (AgentRiskLevel, String) -> Unit = { candidate, reason ->
            if (candidate > level) level = candidate
            if (reason !in reasons) reasons += reason
        }

        val (segments, balanced) = splitSegments(raw)
        // 引号不配对时切分结果不可信，按高风险处理，避免「藏」在引号里的第二段命令被漏判。
        if (!balanced) raise(AgentRiskLevel.HIGH, "引号不完整，无法静态确认命令范围")
        // 命令替换/反引号的真实内容要等运行时才知道，一律升级为高风险。
        if (raw.contains("\$(") || raw.contains('`')) {
            raise(AgentRiskLevel.HIGH, "包含命令替换或反引号，实际执行内容无法静态确认")
        }

        val words = segments.map { effectiveTokens(tokenize(it)) }
        for (index in words.indices) {
            val tokens = words[index]
            if (tokens.isEmpty()) continue
            val rawSegment = segments[index]
            val command = tokens.first().substringAfterLast('/').lowercase(Locale.ROOT)
            val arguments = tokens.drop(1)

            when {
                command in SHELLS && arguments.size >= 2 && arguments.first().lowercase(Locale.ROOT) == "-c" -> {
                    raiseNested(raise, arguments.drop(1).joinToString(" "), depth)
                }

                command == "eval" && arguments.isNotEmpty() -> {
                    raiseNested(raise, arguments.joinToString(" "), depth)
                }

                command == "rm" -> raiseRm(raise, arguments)
                command == "su" -> {
                    // `su -c 'cmd'` 剥掉 -c；`su 0 cmd` 这种「用户 id + 命令」形式要跳过 id。
                    val flagIndex = arguments.indexOfFirst { it == "-c" }
                    val target =
                        if (flagIndex >= 0) {
                            arguments.drop(flagIndex + 1)
                        } else {
                            arguments.dropWhile { it.startsWith("-") || it.toIntOrNull() != null }
                        }
                    raiseNested(raise, target.joinToString(" "), depth)
                }
                command == "rmdir" -> raise(AgentRiskLevel.MEDIUM, "删除目录")
                command == "mv" -> {
                    if (writesSystemPartition(arguments)) {
                        raise(AgentRiskLevel.HIGH, "移动文件到系统分区")
                    } else {
                        raise(AgentRiskLevel.MEDIUM, "移动或覆盖文件")
                    }
                }
                command in setOf("cp", "ln", "touch", "tee", "install") ->
                    if (writesSystemPartition(arguments)) raise(AgentRiskLevel.HIGH, "写入系统分区文件")
                command == "chmod" -> {
                    if (hasRecursiveFlag(arguments)) {
                        raise(AgentRiskLevel.HIGH, "递归修改文件权限")
                    } else if (arguments.any { it == "777" }) {
                        raise(AgentRiskLevel.MEDIUM, "把文件权限改为 777")
                    }
                }
                command == "chown" -> {
                    if (hasRecursiveFlag(arguments)) raise(AgentRiskLevel.HIGH, "递归修改文件所有者")
                }
                command == "dd" -> raise(AgentRiskLevel.FATAL, "dd 直接按块写入磁盘，会不可逆地覆盖数据")
                command.startsWith("mkfs") || command == "mke2fs" ->
                    raise(AgentRiskLevel.FATAL, "格式化文件系统，目标分区上的数据会全部丢失")
                command in setOf("fdisk", "parted", "wipefs", "sgdisk") ->
                    raise(AgentRiskLevel.FATAL, "修改磁盘分区表，可能让整个存储不可用")
                command == "fastboot" && arguments.any { it in setOf("flash", "erase") } ->
                    raise(AgentRiskLevel.FATAL, "刷写或擦除分区，数据不可恢复")
                command in setOf("reboot", "shutdown", "poweroff", "halt") ->
                    raise(AgentRiskLevel.FATAL, "重启或关机：当前任务与未保存现场会中断")
                command == "git" -> raiseGit(raise, arguments)
                command == "pm" -> {
                    // 用非空字符串匹配，避免把 `String?` 直接放进 `in Set<String>` 的类型推断。
                    val sub = arguments.firstOrNull()?.lowercase(Locale.ROOT).orEmpty()
                    when {
                        sub in setOf("uninstall", "clear", "disable") ->
                            raise(AgentRiskLevel.HIGH, "pm 卸载/清数据/停用应用")
                        sub == "grant" || sub == "revoke" ->
                            raise(AgentRiskLevel.MEDIUM, "变更应用运行时权限")
                        sub.startsWith("uninstall") || sub.startsWith("clear") || sub.startsWith("disable") ->
                            raise(AgentRiskLevel.HIGH, "pm 卸载/清数据/停用应用")
                    }
                }
                command == "settings" -> {
                    when (arguments.firstOrNull()?.lowercase(Locale.ROOT)) {
                        "put" -> raise(AgentRiskLevel.MEDIUM, "写入系统设置项")
                        "delete" -> raise(AgentRiskLevel.HIGH, "删除系统设置项")
                    }
                }
                command == "content" && arguments.firstOrNull()?.lowercase(Locale.ROOT) == "delete" ->
                    raise(AgentRiskLevel.MEDIUM, "删除 Provider 中的数据行")
                command in setOf("mount", "umount") -> raise(AgentRiskLevel.MEDIUM, "挂载或卸载文件系统")
                command == "find" -> {
                    if (arguments.any { it == "-delete" }) raise(AgentRiskLevel.HIGH, "find -delete 批量删除文件")
                    if (arguments.any { it == "-exec" } && arguments.any { it == "rm" }) {
                        raise(AgentRiskLevel.HIGH, "find -exec rm 批量删除文件")
                    }
                }
                command == "xargs" && arguments.any { it == "rm" } -> raise(AgentRiskLevel.HIGH, "xargs rm 批量删除文件")
                command in setOf("truncate", "shred", "srm") ->
                    raise(AgentRiskLevel.HIGH, "截断或粉碎文件内容，原数据不可恢复")
                command in setOf("curl", "wget") && isPipedToShell(words, index) ->
                    raise(AgentRiskLevel.HIGH, "把远程内容直接交给 shell 执行")
            }

            redirectionRisk(rawSegment)?.let { (candidate, reason) -> raise(candidate, reason) }
        }

        // SQL 语句可能藏在 sqlite3/mysql 的参数里：对整条命令做一次静态匹配。
        val lowered = raw.lowercase(Locale.ROOT)
        if (DROP_SQL.any { it.containsMatchIn(lowered) }) {
            raise(AgentRiskLevel.FATAL, "SQL DROP/TRUNCATE 会不可逆地删除表或库")
        }
        if (DELETE_FROM.containsMatchIn(lowered)) {
            if (!WHERE.containsMatchIn(lowered)) {
                raise(AgentRiskLevel.FATAL, "不带 WHERE 的 DELETE FROM 会清空整张表")
            } else {
                raise(AgentRiskLevel.MEDIUM, "删除数据库中的匹配行")
            }
        }

        return AgentRiskAssessment(level, reasons)
    }

    private fun raiseNested(
        raise: (AgentRiskLevel, String) -> Unit,
        nested: String,
        depth: Int,
    ) {
        if (nested.isBlank()) return
        val inner = analyzeInner(nested, depth + 1)
        if (inner.level == AgentRiskLevel.LOW) return
        raise(inner.level, inner.reasons.firstOrNull() ?: "嵌套命令含高风险操作")
    }

    private fun raiseRm(raise: (AgentRiskLevel, String) -> Unit, arguments: List<String>) {
        val flags = arguments.filter { it.startsWith("-") }
        val targets = arguments.filterNot { it.startsWith("-") }
        val recursive = flags.any {
            it == "--recursive" || (it.startsWith("-") && (it.contains('r') || it.contains('R')))
        }
        val force = flags.any { it == "--force" || (it.startsWith("-") && it.contains('f')) }
        val dynamicTarget = targets.any { target ->
            target.contains('*') || target.contains('?') || target.contains('[') || target.contains('$')
        }
        val systemRoot = targets.any { normalizeTarget(it) in ROOT_TARGETS }
        when {
            recursive && systemRoot ->
                raise(AgentRiskLevel.FATAL, "递归删除系统或存储根目录，会清空整棵目录树")
            recursive && dynamicTarget ->
                raise(AgentRiskLevel.FATAL, "递归删除的通配/变量目标在运行时才展开，可能清空意料之外的目录")
            recursive -> raise(AgentRiskLevel.HIGH, "递归删除目录树")
            dynamicTarget && force -> raise(AgentRiskLevel.HIGH, "强制批量删除（含通配符）")
            dynamicTarget -> raise(AgentRiskLevel.MEDIUM, "批量删除（含通配符）")
            else -> raise(AgentRiskLevel.MEDIUM, "删除文件")
        }
    }

    private fun raiseGit(raise: (AgentRiskLevel, String) -> Unit, arguments: List<String>) {
        val subcommand = arguments.firstOrNull()?.lowercase(Locale.ROOT) ?: return
        val rest = arguments.drop(1).map { it.lowercase(Locale.ROOT) }
        when {
            subcommand == "reset" && rest.any { it == "--hard" } ->
                raise(AgentRiskLevel.HIGH, "git reset --hard 会丢弃所有未提交改动")
            subcommand == "clean" && rest.any { it.startsWith("-") && it.contains('f') } ->
                raise(AgentRiskLevel.HIGH, "git clean -f 会删除所有未跟踪文件")
            subcommand == "push" && rest.any { it == "--force" || it == "-f" } ->
                raise(AgentRiskLevel.HIGH, "git push --force 会覆盖远端提交历史")
            subcommand == "checkout" && rest.any { it == "--" || it == "." } ->
                raise(AgentRiskLevel.HIGH, "git checkout -- . 会丢弃工作区改动")
            subcommand == "restore" && rest.contains(".") ->
                raise(AgentRiskLevel.HIGH, "git restore . 会丢弃工作区改动")
            subcommand == "branch" && rest.any { it == "-d" || it == "-D" || it == "--delete" } ->
                raise(AgentRiskLevel.MEDIUM, "删除本地分支")
            subcommand == "stash" && rest.any { it == "drop" || it == "clear" } ->
                raise(AgentRiskLevel.MEDIUM, "丢弃 git stash 里的改动")
        }
    }

    /**
     * 输出重定向：`>>` 是追加不判；`>` 覆盖写按目标位置分级。
     * `2>` / `>&` / `/dev/null` 这类无害写法直接跳过。
     */
    private fun redirectionRisk(segment: String): Pair<AgentRiskLevel, String>? {
        var quote = '\u0000'
        var index = 0
        while (index < segment.length) {
            val char = segment[index]
            when {
                quote != '\u0000' -> if (char == quote) quote = '\u0000'
                char == '\'' || char == '"' -> quote = char
                char == '>' -> {
                    if (index + 1 < segment.length && segment[index + 1] == '>') {
                        index += 2
                        continue
                    }
                    if (index > 0 && segment[index - 1] == '&') {
                        index += 1
                        continue
                    }
                    val target = segment.substring(index + 1)
                        .trimStart()
                        .takeWhile { !it.isWhitespace() && it != ';' && it != '|' && it != '&' }
                        .trim('"', '\'')
                    when {
                        target.isEmpty() -> Unit
                        target.startsWith("/dev/null") || target.startsWith("/dev/stdout") ||
                            target.startsWith("/dev/stderr") || target.startsWith("&") -> Unit
                        target.startsWith("/dev/block") || target.startsWith("/dev/mtd") ||
                            target.startsWith("/proc/") || target.startsWith("/sys/") ->
                            return AgentRiskLevel.FATAL to "向系统设备节点 $target 覆盖写，可能损坏系统"
                        target.startsWith("/system") || target.startsWith("/vendor") ->
                            return AgentRiskLevel.HIGH to "覆盖写系统分区文件 $target"
                        else -> return AgentRiskLevel.MEDIUM to "用 > 覆盖写 $target，原内容会被清空"
                    }
                }
            }
            index += 1
        }
        return null
    }

    private fun isPipedToShell(segments: List<List<String>>, index: Int): Boolean {
        val next = segments.getOrNull(index + 1) ?: return false
        if (next.isEmpty()) return false
        val nextCommand = next.first().substringAfterLast('/').lowercase(Locale.ROOT)
        return nextCommand in SHELLS || nextCommand in setOf("python", "python3", "perl", "ruby")
    }

    private fun hasRecursiveFlag(arguments: List<String>): Boolean =
        arguments.any {
            it == "--recursive" || (it.startsWith("-") && !it.startsWith("--") && (it.contains('R') || it.contains('r')))
        }

    /** 写入 /system、/vendor 等只读分区属于系统级变更，即使单文件也要确认。 */
    private fun writesSystemPartition(arguments: List<String>): Boolean =
        arguments.any { it.startsWith("/system") || it.startsWith("/vendor") || it.startsWith("/boot") }

    /** 比较前去掉引号、末尾斜杠与末尾的 `*` 通配，让 `/sdcard/` 与带通配的写法都归到 `/sdcard`。 */
    private fun normalizeTarget(raw: String): String {
        var value = raw.trim().trim('"', '\'')
        while (value.length > 1 && value.endsWith('/')) value = value.dropLast(1)
        if (value.endsWith("/*")) value = value.dropLast(2)
        if (value.length > 1 && value.endsWith('/')) value = value.dropLast(1)
        return value
    }

    /** 按 `;`、换行、`&&`、`||`、`|` 切段；引号内的分隔符不切。返回 (段列表, 引号是否配对)。 */
    private fun splitSegments(raw: String): Pair<List<String>, Boolean> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        var quote = '\u0000'
        var escaped = false
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                    index += 1
                }
                char == '\\' && quote != '\'' -> {
                    current.append(char)
                    escaped = true
                    index += 1
                }
                quote != '\u0000' -> {
                    if (char == quote) quote = '\u0000'
                    current.append(char)
                    index += 1
                }
                char == '\'' || char == '"' -> {
                    quote = char
                    current.append(char)
                    index += 1
                }
                char == ';' || char == '\n' || char == '|' || char == '&' -> {
                    segments += current.toString()
                    current.setLength(0)
                    val doubled = index + 1 < raw.length && raw[index + 1] == char && char != ';'
                    index += if (doubled) 2 else 1
                    // 单个 `&` 只作后台运行符；这里不区分，切段后仍会逐条判定，不会漏判。
                }
                else -> {
                    current.append(char)
                    index += 1
                }
            }
        }
        segments += current.toString()
        return segments.filter { it.isNotBlank() } to (quote == '\u0000')
    }

    /** 去引号的空白切词；引号里的空格不切。 */
    private fun tokenize(segment: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quote = '\u0000'
        var escaped = false
        for (char in segment) {
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' && quote != '\'' -> escaped = true
                quote != '\u0000' -> {
                    if (char == quote) quote = '\u0000' else current.append(char)
                }
                char == '\'' || char == '"' -> quote = char
                char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.setLength(0)
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /** 剥掉环境变量赋值与 sudo/env/busybox 之类前缀，拿到真正执行的命令。 */
    private fun effectiveTokens(input: List<String>): List<String> {
        var tokens = input
        var guard = 0
        while (tokens.isNotEmpty() && guard++ < 8) {
            val head = tokens.first()
            val lower = head.lowercase(Locale.ROOT)
            when {
                ENV_ASSIGNMENT.containsMatchIn(head) -> tokens = tokens.drop(1)
                lower in PREFIX_WRAPPERS -> {
                    tokens = tokens.drop(1)
                    while (tokens.isNotEmpty() && tokens.first().startsWith("-")) {
                        val flag = tokens.first()
                        tokens = tokens.drop(1)
                        // sudo/doas 的 -u/-g/-p 带一个值：跳过值，别把它当成命令名。
                        if ((lower == "sudo" || lower == "doas") && flag in setOf("-u", "-g", "-p", "--user", "--group")) {
                            tokens = tokens.drop(1)
                        }
                    }
                }
                else -> return tokens
            }
        }
        return tokens
    }
}
