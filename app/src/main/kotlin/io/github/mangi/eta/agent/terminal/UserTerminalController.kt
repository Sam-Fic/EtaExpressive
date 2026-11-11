package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import kotlin.concurrent.thread

/**
 * 用户手动终端的会话控制器：多个常驻 shell 会话并存，每个会话的 cwd 与环境变量跨命令保持。
 *
 * 与面向模型的 [RootShellTerminalController] 分层独立：
 * - 无执行超时——命令何时结束由用户决定（“停止”终止对应会话）；
 * - 输出经 onDelta 流式回调，不走模型工具的 JSON 合同与截断策略；
 * - 生命周期归属 App 级 UI 状态，不随单次 run 回收。
 *
 * 进程启动、所有权识别与进程树终止复用 [ShellProcessSupervisor]；会话内启动的后台子进程
 * 随会话存活，会话退出时由 launcher 清场，与 AI 终端语义一致。
 */
internal class UserTerminalController(
    private val logger: AgentLogger,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val processSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(),
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
    private val onSessionExit: (String) -> Unit = {},
) : AutoCloseable {

    private companion object {
        const val DEFAULT_CWD = "/data/local/tmp/eta"
        const val LINUX_DEFAULT_CWD = "/workspace"
        const val MAX_SESSIONS = 6
        const val STREAM_MAX_BYTES = 1024 * 1024
        const val POLL_INTERVAL_MS = 50L
        const val SETUP_TIMEOUT_MS = 5_000L
    }

    sealed interface OpenResult {
        data class Ready(
            val sessionId: String,
            val environment: TerminalEnvironment,
            val cwd: String,
        ) : OpenResult

        data class Failed(val code: String, val message: String) : OpenResult
    }

    /** exitCode 为 null 表示会话中断（停止或进程死亡），命令没有正常返回退出码。 */
    data class ExecResult(
        val exitCode: Int?,
        val cwd: String,
        val sessionClosed: Boolean,
        val interrupted: Boolean,
    )

    data class SessionInfo(
        val id: String,
        val environment: TerminalEnvironment,
        val cwd: String,
        val alive: Boolean,
    )

    private val sessionLock = Any()
    private val sessions = LinkedHashMap<String, Session>()
    private var nextSessionNumber = 0

    fun listSessions(): List<SessionInfo> = synchronized(sessionLock) {
        sessions.map { (id, session) ->
            SessionInfo(
                id = id,
                environment = session.environment,
                cwd = session.cwd,
                alive = !session.closed && session.process.isAlive,
            )
        }
    }

    fun sessionAlive(sessionId: String): Boolean = synchronized(sessionLock) {
        sessions[sessionId]?.let { !it.closed && it.process.isAlive } == true
    }

    /** 新建会话；已有会话保持存活。identity 预留给无 root 环境的降级与测试。 */
    fun openSession(
        environment: TerminalEnvironment,
        cwd: String? = null,
        identity: String? = null,
    ): OpenResult {
        val environmentRootfsPath = rootfsPath(environment)
        val selectedIdentity = identity ?: if (environment.isLinux) {
            TerminalRuntime.defaultIdentity(environment, environmentRootfsPath)
        } else if (rootAvailable()) "root" else "user"
        synchronized(sessionLock) {
            pruneDeadSessionsLocked()
            if (sessions.size >= MAX_SESSIONS) {
                return OpenResult.Failed("SESSION_LIMIT_REACHED", "会话数量已达上限")
            }
            if (selectedIdentity != "root" && selectedIdentity != "user") {
                return OpenResult.Failed("INVALID_ARGUMENT", "identity 仅支持 root/user")
            }
            if (environment.isLinux && selectedIdentity != "root" && LinuxEnvironmentPaths.backendOf(environmentRootfsPath) != LinuxExecutionBackend.PROOT) {
                return OpenResult.Failed("LINUX_ENVIRONMENT_REQUIRES_ROOT", "Linux 工具环境仅支持 root identity")
            }
            if (selectedIdentity == "root" && !rootAvailable()) return OpenResult.Failed("ROOT_REQUIRED", "Root 授权不可用")
            if (environment.isLinux &&
                !LinuxEnvironmentPaths.rootfsReady(environmentRootfsPath)
            ) {
                return OpenResult.Failed("LINUX_ENVIRONMENT_NOT_READY", "Linux 工具环境尚未安装")
            }
            val safeCwd = cwd?.takeIf { it.isNotBlank() } ?: if (environment.isLinux) LINUX_DEFAULT_CWD else TerminalRuntime.workspace(selectedIdentity)
            var startFailure = "stage=unknown"
            val process = processSupervisor.startShellProcess(
                identity = selectedIdentity,
                command = null,
                mergeStderr = false,
                environment = environment,
                linuxRootfsPath = environmentRootfsPath,
                linuxSharedMounts = if (environment.isLinux) {
                    linuxSharedMountsProvider()
                } else {
                    emptyList()
                },
                onStartFailure = { startFailure = it },
            ) ?: return OpenResult.Failed("PROCESS_START_FAILED", "无法启动终端进程 ($startFailure)")
            val newSession = Session(
                identity = selectedIdentity,
                environment = environment,
                cwd = safeCwd,
                process = process,
                stdout = ByteArrayOutputCollector(),
                stderr = ByteArrayOutputCollector(),
            )
            newSession.stdoutThread = thread(name = "user-terminal-stdout", isDaemon = true) {
                process.inputStream.use { input -> newSession.stdout.readFrom(input, STREAM_MAX_BYTES) }
            }
            newSession.stderrThread = thread(name = "user-terminal-stderr", isDaemon = true) {
                process.errorStream.use { input -> newSession.stderr.readFrom(input, STREAM_MAX_BYTES) }
            }
            val sessionId = "s${++nextSessionNumber}"
            newSession.waiterThread = thread(name = "user-terminal-waiter", isDaemon = true) {
                runCatching { process.waitFor() }
                newSession.closed = true
                processSupervisor.retireExitedProcess(process)
                onSessionExit(sessionId)
            }
            if (!processSupervisor.transferActiveProcess(process) { sessions[sessionId] = newSession }) {
                processSupervisor.terminateProcessTree(process)
                return OpenResult.Failed("TERMINAL_CLOSED", "终端控制器已关闭")
            }

            val setup = buildString {
                if (environment == TerminalEnvironment.ANDROID && safeCwd == TerminalRuntime.workspace(selectedIdentity)) {
                    append("mkdir -p ${shellQuote(safeCwd)} && ")
                }
                // 用户工具的安装器常把 PATH 写进 profile；常驻会话不是 login shell，这里显式补齐。
                append("[ -f /etc/profile ] && . /etc/profile; ")
                append("[ -f \"${'$'}HOME/.profile\" ] && . \"${'$'}HOME/.profile\"; ")
                append("cd ${shellQuote(safeCwd)} && export TERM=dumb NO_COLOR=1")
            }
            val setupResult = execInternal(newSession, setup, SETUP_TIMEOUT_MS, null)
            if (setupResult.exitCode != 0 || setupResult.timedOut) {
                closeSessionLocked(sessionId)
                logger.warn("User terminal action=open outcome=failed environment=${environment.wireName}")
                return OpenResult.Failed("SESSION_OPEN_FAILED", "终端会话初始化失败")
            }
            newSession.cwd = setupResult.cwd ?: safeCwd
            newSession.stdout.clear()
            newSession.stderr.clear()
            logger.info("User terminal action=open outcome=succeeded environment=${environment.wireName}")
            return OpenResult.Ready(sessionId, environment, newSession.cwd)
        }
    }

    /**
     * 在指定常驻会话中执行命令并流式回调输出。阻塞当前线程，调用方需在 IO 线程使用。
     * 无超时；用户停止或 shell 死亡（如执行 exit）时返回 sessionClosed=true。
     */
    fun exec(sessionId: String, command: String, onDelta: (text: String, isStderr: Boolean) -> Unit): ExecResult {
        val trimmed = command.trim()
        val current = synchronized(sessionLock) { sessions[sessionId] }
            ?: return ExecResult(
                exitCode = null,
                cwd = DEFAULT_CWD,
                sessionClosed = true,
                interrupted = false,
            )
        if (current.identity == "root" && !rootAvailable()) {
            stopSession(sessionId)
            return ExecResult(null, current.cwd, sessionClosed = true, interrupted = true)
        }
        if (trimmed.isBlank()) {
            return ExecResult(
                exitCode = null,
                cwd = current.cwd,
                sessionClosed = false,
                interrupted = false,
            )
        }
        val result = execInternal(current, trimmed, timeoutMs = null, onDelta = onDelta)
        val interrupted = current.stopRequested
        val outcome = when {
            result.sessionClosed -> if (interrupted) "interrupted" else "closed"
            result.exitCode == 0 -> "succeeded"
            else -> "failed"
        }
        val logMessage =
            "User terminal action=exec outcome=$outcome environment=${current.environment.wireName} " +
                "exitCode=${result.exitCode} commandChars=${trimmed.length}"
        if (result.exitCode == 0) {
            logger.info(logMessage)
        } else {
            logger.warn(logMessage)
        }
        return ExecResult(
            exitCode = result.exitCode,
            cwd = result.cwd ?: current.cwd,
            sessionClosed = result.sessionClosed,
            interrupted = interrupted,
        )
    }

    /** 终止指定会话及其进程组；该会话的后续 exec 前由调用方重新 openSession。 */
    fun stopSession(sessionId: String) {
        synchronized(sessionLock) {
            sessions[sessionId]?.stopRequested = true
            closeSessionLocked(sessionId)
        }
    }

    /**
     * 会话运行期间向 stdin 追加输入（模拟真实终端的键入），前台进程与后续 shell 命令都能读到。
     * 返回 false 表示会话已不可用。
     */
    fun writeInput(sessionId: String, text: String): Boolean {
        val current = synchronized(sessionLock) { sessions[sessionId] } ?: return false
        if (current.closed || !current.process.isAlive) return false
        if (current.identity == "root" && !rootAvailable()) return false
        return runCatching {
            synchronized(current.stdinLock) {
                current.process.outputStream.write(text.toByteArray(Charsets.UTF_8))
                current.process.outputStream.flush()
            }
        }.isSuccess
    }

    override fun close() {
        processSupervisor.beginClosing()
        synchronized(sessionLock) {
            sessions.keys.toList().forEach { closeSessionLocked(it) }
        }
    }

    private fun closeSessionLocked(sessionId: String) {
        val current = sessions.remove(sessionId) ?: return
        current.closed = true
        runCatching { current.process.outputStream.close() }
        processSupervisor.terminateAndReap(current.process)
        runCatching { current.stdoutThread.join(500) }
        runCatching { current.stderrThread.join(500) }
        runCatching { current.waiterThread.join(500) }
        processSupervisor.unregisterProcess(current.process)
    }

    /** 回收已退出的会话槽位，避免死会话占用并发上限。 */
    private fun pruneDeadSessionsLocked() {
        val deadIds = sessions.filterValues { it.closed || !it.process.isAlive }.keys.toList()
        deadIds.forEach { closeSessionLocked(it) }
    }

    private fun defaultCwd(environment: TerminalEnvironment): String =
        if (environment.isLinux) LINUX_DEFAULT_CWD else DEFAULT_CWD

    private fun rootfsPath(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    private data class InternalResult(
        val exitCode: Int?,
        val cwd: String?,
        val timedOut: Boolean,
        val sessionClosed: Boolean,
    )

    private fun execInternal(
        session: Session,
        command: String,
        timeoutMs: Long?,
        onDelta: ((text: String, isStderr: Boolean) -> Unit)?,
    ): InternalResult {
        synchronized(session.execLock) {
            if (session.closed || !session.process.isAlive) {
                return InternalResult(
                    exitCode = null,
                    cwd = session.cwd,
                    timedOut = false,
                    sessionClosed = true,
                )
            }
            val marker = SessionStatusProtocol.newMarker()
            // 单逻辑行协议：状态 printf 不进 stdin，交互式命令读 stdin 不会吃掉标记。
            val commandBlock = SessionStatusProtocol.commandLine(marker, command) + "\n"
            runCatching {
                synchronized(session.stdinLock) {
                    session.process.outputStream.write(commandBlock.toByteArray(Charsets.UTF_8))
                    session.process.outputStream.flush()
                }
            }.getOrElse {
                session.closed = true
                return InternalResult(null, session.cwd, timedOut = false, sessionClosed = true)
            }

            val deadline = timeoutMs?.let { System.currentTimeMillis() + it }
            val markerNeedle = "$marker:"
            // 增量读取：每轮只看新到的字节。原来每 50ms 把整段（上限 1MB）缓冲拷一遍再全量
            // 扫 marker，大输出命令下等于每秒上百 MB 的垃圾分配与扫描，主线程直接被 GC 拖死。
            // 从当前水位起算：上一条命令超时留下的残余输出不能混进这一条。
            var stdoutOffset = session.stdout.sizeBytes()
            var stderrOffset = session.stderr.sizeBytes()
            // 尚未冲出的 stdout 文本。状态行残片可能跨轮到达（50ms 轮询会随机截断），扣在这里。
            val pending = StringBuilder()
            while (true) {
                // 输出缓冲越过上限后，读取线程仍会排空管道、但把后续字节全部丢掉，
                // 状态行也在其中：exec 就会永远等一个不可能到达的 marker（CI 上表现为
                // 整个 verify job 被超时取消）。主动腾空缓冲并重置水位，让截断之后
                // 到达的字节（含状态行）重新可收集；正文允许丢失，协议不能死。
                if (session.stdout.isTruncated()) {
                    session.stdout.clear()
                    stdoutOffset = 0
                }
                val stdoutDelta = session.stdout.newTextSince(stdoutOffset)
                if (stdoutDelta.second.isNotEmpty()) {
                    stdoutOffset = stdoutDelta.first
                    pending.append(stdoutDelta.second)
                }
                val stderrDelta = session.stderr.newTextSince(stderrOffset)
                if (stderrDelta.second.isNotEmpty()) {
                    stderrOffset = stderrDelta.first
                    onDelta?.invoke(stderrDelta.second, true)
                }
                // 先判状态行再发增量：状态行完整到达后（含结尾换行）不再是"尾部未完成行"，
                // 若先发出增量会把 marker 整行推给 UI。同时要求状态行以换行结束，
                // 避免按半行解析出截断的 cwd。
                val markerStart = findMarkerLineStart(pending, markerNeedle)
                val markerLineEnd = if (markerStart >= 0) pending.indexOf("\n", markerStart) else -1
                val status = if (markerLineEnd >= 0) {
                    SessionStatusProtocol.parseStatusLine(
                        pending.substring(markerStart, markerLineEnd),
                        marker,
                    )
                } else {
                    null
                }
                if (status != null) {
                    // 状态行已到达：只冲出 marker 之前的正文，末尾空白裁掉。
                    val content = pending.substring(0, markerStart).trimEnd()
                    if (content.isNotEmpty()) onDelta?.invoke(content, false)
                    pending.setLength(0)
                    session.stdout.clear()
                    session.stderr.clear()
                    val newCwd = status.cwd ?: session.cwd
                    session.cwd = newCwd
                    return InternalResult(status.exitCode, newCwd, timedOut = false, sessionClosed = false)
                }
                if (session.closed || !session.process.isAlive) {
                    // 会话已结束：残余输出原样冲出，不做状态行过滤。
                    if (pending.isNotEmpty()) {
                        onDelta?.invoke(pending.toString(), false)
                        pending.setLength(0)
                    }
                    return InternalResult(null, session.cwd, timedOut = false, sessionClosed = true)
                }
                // stdout 读到 EOF 后读取线程结束，管道不可能再产出状态行；此时进程对象
                // 仍可能报 alive（setsid 包装层尚未回收），继续轮询就是死等。按会话结束返回，
                // 让调用方能走"停止/重开"路径，而不是把 exec 线程永久钉住。
                if (!session.stdoutThread.isAlive) {
                    if (pending.isNotEmpty()) onDelta?.invoke(pending.toString(), false)
                    return InternalResult(null, session.cwd, timedOut = false, sessionClosed = true)
                }
                if (deadline != null && System.currentTimeMillis() >= deadline) {
                    return InternalResult(null, session.cwd, timedOut = true, sessionClosed = false)
                }
                flushStdoutPending(pending, markerNeedle, onDelta)
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * marker 行在 [pending] 中的起始下标（指向 marker 本身，不含行首换行）；未出现返回 -1。
     * 行首可以是缓冲区起点，也可以是换行符之后——状态行的前导换行可能已经被上一轮冲出去。
     */
    private fun findMarkerLineStart(pending: CharSequence, markerNeedle: String): Int {
        var index = pending.indexOf(markerNeedle)
        while (index > 0 && pending[index - 1] != '\n') {
            val next = pending.indexOf(markerNeedle, index + 1)
            if (next <= index) return -1
            index = next
        }
        return index
    }

    /**
     * 把 [pending] 中已确定不是状态行的部分冲给调用方。
     * 尾部未完成行若是 marker 前缀（50ms 轮询随机截断），连同它前面的换行一起扣住，
     * 否则下一轮就找不到 marker 行的行首了。
     */
    private fun flushStdoutPending(
        pending: StringBuilder,
        markerNeedle: String,
        onDelta: ((String, Boolean) -> Unit)?,
    ) {
        if (pending.isEmpty()) return
        var end = pending.length
        val tailStart = pending.lastIndexOf("\n") + 1
        if (tailStart < end) {
            val tail = pending.substring(tailStart)
            if (markerNeedle.startsWith(tail) || tail.startsWith(markerNeedle)) {
                // 换行本身也留着：marker 行首必须是换行后的位置或缓冲区起点。
                end = if (tailStart > 0) tailStart - 1 else 0
            }
        }
        if (end <= 0) return
        if (onDelta != null) onDelta(pending.substring(0, end), false)
        pending.delete(0, end)
    }

    private class Session(
        val identity: String,
        val environment: TerminalEnvironment,
        var cwd: String,
        val process: Process,
        val stdout: ByteArrayOutputCollector,
        val stderr: ByteArrayOutputCollector,
    ) {
        val execLock = Any()

        /** exec 的命令行写入与运行期的用户输入写入共用同一把锁，避免字节交错。 */
        val stdinLock = Any()

        @Volatile
        var closed: Boolean = false

        @Volatile
        var stopRequested: Boolean = false

        lateinit var stdoutThread: Thread
        lateinit var stderrThread: Thread
        lateinit var waiterThread: Thread
    }
}
