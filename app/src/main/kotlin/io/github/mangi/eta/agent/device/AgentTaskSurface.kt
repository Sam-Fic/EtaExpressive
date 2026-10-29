package io.github.mangi.eta.agent.device

import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AgentTaskSurfaceMode(val wire: String, val labelRes: Int) {
    ASK("ask", R.string.agent_task_surface_ask),
    FOREGROUND("foreground", R.string.agent_task_surface_foreground),
    BACKGROUND("background", R.string.agent_task_surface_background);

    companion object {
        fun fromWire(value: String?): AgentTaskSurfaceMode =
            entries.firstOrNull { it.wire == value } ?: FOREGROUND

        /**
         * 模块是否安装都不能改写存储值。
         * 缺模块时把 BACKGROUND/ASK 收成 FOREGROUND 会静默改走前台；
         * 模块在场也不表示本阶段的副屏交接已经可用。
         */
        fun resolve(
            stored: AgentTaskSurfaceMode,
            @Suppress("UNUSED_PARAMETER") moduleInstalled: Boolean,
        ): AgentTaskSurfaceMode = stored
    }
}

/**
 * 前台直接在当前屏幕执行，后台走副屏。
 * ASK 在第一次界面操作前弹窗，由用户为本次 run 选定前台或后台；选定前界面工具一律拦下。
 * 设置入口仅在后端模块已安装时可见；不改变已保存的执行模式。
 */
internal object AgentTaskSurface {
    const val PREF_KEY = "agent_task_surface"

    /**
     * 一次性迁移标记：记录“每次询问 → 前台执行”的升级迁移已经跑过。
     * 标记名固定，不由版本号派生；迁移后用户重新选择 ASK/后台不会被再次改写。
     */
    const val ASK_MIGRATION_KEY = "agent_task_surface_ask_migrated"

    private val traditionalScreenGuiTools: Set<String> = setOf(
        "observe_screen",
        "wait",
        "wait_for_text",
        "wait_for_package",
        "input_text",
        "replace_text",
        "clear_text",
        "paste_text",
        "press_key",
        "tap",
        "tap_area",
        "tap_element",
        "long_press",
        "long_press_element",
        "swipe",
        "scroll",
        "scroll_element",
        "open_system_panel",
        "launch_app",
        "open_uri",
        // 直达失败时可能只打开时钟界面。
        "set_alarm",
        "set_timer",
    )

    fun moduleInstalled(): Boolean {
        val binary = File("/system/bin/vd")
        if (binary.isFile && binary.canExecute()) return true
        return File("/data/adb/modules/agent_mobile_use/module.prop").isFile
    }

    fun stored(): AgentTaskSurfaceMode =
        AgentTaskSurfaceMode.fromWire(Prefs.getString(PREF_KEY, AgentTaskSurfaceMode.FOREGROUND.wire))

    fun allowsPersist(@Suppress("UNUSED_PARAMETER") mode: AgentTaskSurfaceMode): Boolean = true

    fun save(mode: AgentTaskSurfaceMode) {
        if (!allowsPersist(mode)) throw VirtualDisplayHandoffNotReadyException()
        Prefs.putString(PREF_KEY, mode.wire)
    }

    /**
     * 升级时只跑一次的迁移：把升级前显式存过的合法 ASK 改成 FOREGROUND。
     *
     * - 只有本地存储值恰好是 "ask" 才改写；缺键、前台、后台都原样保留，但仍写入
     *   一次性标记，表示该安装已经检查过，避免用户以后重新选择 ASK 时才被迁移。
     * - 原始值直接从同一份本地配置读取；读不出来（类型异常）时本次不算检查成功，
     *   不写标记并返回 false，留待后续检查重试。
     * - 标记与值写在同一个 editor 里一次提交。commit() 返回 false 时不能宣告完成
     *   （内存可能已被改动但未确认持久化）；冷启动重载后若仍无标记，才会再尝试。
     * - 只在 [Prefs.initLocal] 与备份恢复之后触发，早于任何 `stored`/`effective` 读取；
     *   标记存在时直接返回，所以迁移后用户重新选择的 ASK/后台会被后续启动与升级保留。
     *
     * @return 本次是否确认完成；false 表示未确认落盘，不保证同进程立刻重试。
     */
    fun migrateAskToForegroundOnce(): Boolean {
        val prefs = Prefs.localAgentPreferences() ?: return false
        if (runCatching { prefs.getBoolean(ASK_MIGRATION_KEY, false) }.getOrDefault(false)) return true
        val stored = try {
            prefs.getString(PREF_KEY, null)
        } catch (_: Exception) {
            // 读不出原始值就无从判断是否旧 ASK，不能写完成标记。
            return false
        }
        val editor = prefs.edit().putBoolean(ASK_MIGRATION_KEY, true)
        if (stored == AgentTaskSurfaceMode.ASK.wire) {
            editor.putString(PREF_KEY, AgentTaskSurfaceMode.FOREGROUND.wire)
        }
        // 标记与值同一次提交；返回 false 时只能说未确认落盘，不能断言内存仍未改变。
        return editor.commit()
    }

    fun effective(): AgentTaskSurfaceMode = AgentTaskSurfaceMode.resolve(stored(), moduleInstalled())

    fun settingsEntryVisible(): Boolean = settingsEntryVisible(moduleInstalled(), stored())

    fun settingsEntryVisible(moduleInstalled: Boolean, @Suppress("UNUSED_PARAMETER") stored: AgentTaskSurfaceMode): Boolean =
        moduleInstalled

    fun settingsSummaryRes(stored: AgentTaskSurfaceMode): Int = when (stored) {
        AgentTaskSurfaceMode.FOREGROUND -> stored.labelRes
        AgentTaskSurfaceMode.BACKGROUND -> R.string.agent_task_surface_background_summary
        AgentTaskSurfaceMode.ASK -> R.string.agent_task_surface_ask_summary
    }

    /** 本次 run 是否还需要先问用户：只对普通屏幕操作走执行位置弹窗。 */
    fun needsSurfaceChoice(toolName: String): Boolean =
        isTraditionalScreenGuiTool(toolName)

    fun handoffPromptClause(named: (String) -> Boolean = { true }): String {
        val storedMode = runCatching { stored() }.getOrDefault(AgentTaskSurfaceMode.BACKGROUND)
        val installed = runCatching { moduleInstalled() }.getOrDefault(false)
        return handoffPromptClause(
            moduleInstalled = installed,
            stored = storedMode,
            named = named,
        )
    }

    /**
     * @param named 判断某个工具本轮是否真的在目录里。缺席的工具连名字都不能出现在提示里：
     * 模型照着提示调用它，会被校验器以「工具未在本次运行的能力目录中声明」拒掉，
     * 连续三次直接终止整轮——表现就是「第一次调用成功，后面全都调不了」。
     */
    fun handoffPromptClause(
        @Suppress("UNUSED_PARAMETER") moduleInstalled: Boolean,
        stored: AgentTaskSurfaceMode,
        named: (String) -> Boolean = { true },
    ): String {
        val background = backgroundClause(named)
        return selfAppClause(named) + when (stored) {
            AgentTaskSurfaceMode.FOREGROUND -> ""
            AgentTaskSurfaceMode.BACKGROUND -> background
            AgentTaskSurfaceMode.ASK ->
                "本次普通应用执行位置为“每次询问”：首次需要为非自身应用确定位置（如 ${askExamples(named)}）时，" +
                    "手机会弹窗请用户选择前台或后台；工具会等用户选完才返回，不要因为等待而重复调用。" +
                    "普通选择结果 task_surface_scope=ordinary_run 的 task_surface=foreground 表示普通应用之后在主屏操作；" +
                    "普通选择结果 task_surface=background 表示普通应用之后在后台副屏操作，并遵守：" + background +
                    "返回 TASK_SURFACE_CANCELLED 表示用户取消了这次操作，不要再调用普通应用界面工具；Eta自身例外不重置取消状态。"
        }
    }

    private fun askExamples(named: (String) -> Boolean): String {
        val examples = listOf("launch_app", "observe_screen", "tap").filter(named)
        return if (examples.isEmpty()) "首次界面操作" else examples.joinToString("、")
    }

    private fun selfAppClause(named: (String) -> Boolean): String {
        val open = if (named("launch_app")) "launch_app 精确" else "精确"
        return "仅当需要打开并操控Eta自身（io.github.mangi.eta）时，先直接 $open 自身包名：已解析自身目标在主屏前台打开，" +
            "随后仍针对Eta自身的界面操作是自身前台片段，不因ASK询问，不为自身调用 start_virtual_session，也不要用 ask_user 询问执行位置。" +
            "此例外不改基础偏好、不把整轮改成前台；打开其它应用仍按原ASK或后台选择执行。" +
            "task_surface_scope=self_app 的 task_surface=foreground 仅表示自身片段，base_task_surface 是普通应用基础位置。" +
            "SELF_APP_TARGET_LOST 表示未确认主屏仍为Eta，本次未执行；重新打开目标并重新观察，不重放旧坐标到其它应用或副屏。" +
            "普通URI、系统面板、HOME/RECENTS、闹钟/计时器不继承自身例外；已有副屏仍按原后台生命周期收尾，自身不是副屏交付任务。"
    }

    private fun backgroundClause(named: (String) -> Boolean): String {
        val openStep = when {
            named("launch_app") && named("observe_screen") -> "先 launch_app 精确包名、observe_screen 截图再坐标操作"
            named("launch_app") -> "先 launch_app 精确包名再坐标操作"
            named("observe_screen") -> "先 observe_screen 截图再坐标操作"
            else -> "先观察界面再坐标操作"
        }
        return "本次普通应用选择实验性后台副屏。除上述Eta自身片段外，其它应用GUI 不得回退主屏；" +
            "$openStep。节点、系统面板及不支持的工具会明确拒绝。" +
            (if (named("launch_app")) {
                "要切回本次副屏已打开的应用，直接对同一包名再次 launch_app（返回 reused=true），不要强制停止应用。"
            } else "") +
            (if (named("keep_virtual_result") && named("finish_virtual_session")) BACKGROUND_VIRTUAL_CLAUSE else "")
    }

    private const val BACKGROUND_VIRTUAL_CLAUSE =
            "任务完成前用 keep_virtual_result 标记交付任务，再 finish_virtual_session，只有返回 handedOff=true 且 released=true 才可声称交付完成。没有可交付结果时也要调用 finish_virtual_session，它会清理本次中间任务并关闭副屏（handedOff=false）。收尾失败保留副屏，禁止杀进程或用终端绕过关闭。提示用户期间不要从桌面启动或清理正在操作的应用。"

    fun useVirtualDisplay(): Boolean = useVirtualDisplay(stored())

    fun useVirtualDisplay(stored: AgentTaskSurfaceMode): Boolean = when (stored) {
        AgentTaskSurfaceMode.FOREGROUND -> false
        AgentTaskSurfaceMode.BACKGROUND -> true
        AgentTaskSurfaceMode.ASK -> throw VirtualDisplayHandoffNotReadyException()
    }

    /**
     * 非 GUI 工具立即返回，不读取 Prefs。
     * GUI 工具读取失败时拒绝，避免异常被当成前台放行。
     */
    fun blocksGuiTool(toolName: String): Boolean = blocksGuiTool(toolName, readMode = { stored() })

    fun blocksGuiTool(toolName: String, mode: AgentTaskSurfaceMode): Boolean =
        mode != AgentTaskSurfaceMode.FOREGROUND && isTraditionalScreenGuiTool(toolName)

    fun blocksGuiTool(toolName: String, readMode: () -> AgentTaskSurfaceMode): Boolean {
        if (!isTraditionalScreenGuiTool(toolName)) return false
        val mode = runCatching(readMode).getOrNull() ?: return true
        return blocksGuiTool(toolName, mode)
    }

    fun isTraditionalScreenGuiTool(toolName: String): Boolean =
        toolName.trim() in traditionalScreenGuiTools
}

/**
 * “每次询问”的待决选择。
 *
 * 工具线程在 [await] 里等用户选完；界面（弹窗 Activity 或应用内弹窗）读 [pending] 显示队首请求，
 * 再用 [answer] 回答。每个请求只对应一次 run，取消 run、超时或线程被中断时等待会结束并返回 null。
 */
internal object AgentTaskPrompt {
    data class Request(val id: String, val runId: String)

    private val lock = Any()
    private val queue = LinkedHashMap<String, Pair<Request, CompletableFuture<AgentTaskSurfaceMode?>>>()
    private val pendingState = MutableStateFlow<Request?>(null)
    /** 当前队首请求；弹窗只显示并回答它，回答后自动换成下一个。 */
    val pending: StateFlow<Request?> = pendingState.asStateFlow()

    private val hosts = AtomicInteger(0)
    private val hostVisibleState = MutableStateFlow(false)
    /** 独立弹窗 Activity 正在前台可见；此时应用内弹窗不再重复显示。 */
    val hostVisible: StateFlow<Boolean> = hostVisibleState.asStateFlow()

    /**
     * 阻塞当前工具线程直到用户选择。返回 null 表示取消（用户取消、run 已停止、超时或线程被中断）。
     * [show] 在请求入队后调用，用来拉起弹窗；失败不影响应用内弹窗。
     * 无论怎样结束，请求都会出队，弹窗观察到 [pending] 变化后自行关闭。
     */
    fun await(
        runId: String,
        cancelled: () -> Boolean,
        show: (Request) -> Unit = {},
        pollMillis: Long = POLL_MILLIS,
        timeoutMillis: Long = PROMPT_TIMEOUT_MILLIS,
    ): AgentTaskSurfaceMode? {
        val request = Request(UUID.randomUUID().toString(), runId)
        val answer = CompletableFuture<AgentTaskSurfaceMode?>()
        synchronized(lock) {
            queue[request.id] = request to answer
            publish()
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            runCatching { show(request) }
            while (true) {
                if (cancelled()) return null
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                // 超时按取消处理：用户没有选择。
                if (remaining <= 0) return null
                val chosen = try {
                    answer.get(minOf(pollMillis.coerceAtLeast(1), remaining), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    continue
                } catch (_: InterruptedException) {
                    // 还没做出选择就被中断：按取消处理，并保留中断标志。
                    Thread.currentThread().interrupt()
                    return null
                }
                return chosen?.takeIf { it != AgentTaskSurfaceMode.ASK }
            }
        } finally {
            synchronized(lock) {
                if (queue.remove(request.id) != null) publish()
            }
        }
    }

    /** 回答后立刻出队，弹窗马上换成下一个请求或关闭；重复回答同一请求无效。 */
    fun answer(requestId: String, mode: AgentTaskSurfaceMode?) {
        val future = synchronized(lock) {
            queue.remove(requestId)?.second?.also { publish() }
        }
        future?.complete(mode)
    }

    /**
     * 选完后等弹窗窗口退场，避免前台截图或点击落在弹窗上。
     * 被中断时只恢复中断标志并返回：已做出的选择不能因此变成取消。
     */
    fun awaitHostHidden(timeoutMillis: Long = HOST_HIDE_TIMEOUT_MILLIS) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            while (hosts.get() > 0 && System.nanoTime() < deadline) Thread.sleep(25)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** 弹窗变为可见（onStart/onResume）；调用方保证同一实例只计一次。 */
    fun hostStarted() {
        hostVisibleState.value = hosts.incrementAndGet() > 0
    }

    /** 弹窗不再可见（onStop/onDestroy）；与 [hostStarted] 成对调用。 */
    fun hostStopped() {
        hostVisibleState.value = hosts.updateAndGet { (it - 1).coerceAtLeast(0) } > 0
    }

    private fun publish() {
        pendingState.value = queue.values.firstOrNull()?.first
    }

    private const val POLL_MILLIS = 250L
    private const val PROMPT_TIMEOUT_MILLIS = 90_000L
    private const val HOST_HIDE_TIMEOUT_MILLIS = 1_500L
}
