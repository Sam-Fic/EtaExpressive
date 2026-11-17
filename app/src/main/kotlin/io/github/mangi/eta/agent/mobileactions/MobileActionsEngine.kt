package io.github.mangi.eta.agent.mobileactions

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import io.github.mangi.eta.R
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 端侧 FunctionGemma 推理封装（LiteRT-LM `Engine` + `Conversation`）。
 *
 * 关键约定：
 * - **系统提示词格式固定**：单段 `Content.Text`，内容顺序为「当前时间 → 星期 → 函数调用声明」，
 *   与 `google/mobile-actions` 训练集逐字一致；模型按此格式训练，改动会掉准确率，
 *   所以每次运行现取时间，不复用旧 Conversation。
 * - **工具由 LiteRT-LM 自动调用**：`MobileActionsToolSet` 里的函数体就是动作执行入口，
 *   模型拿到 `{"result":"success"}` 后再生成一句自然语言回复。
 * - **串行化**：一次只跑一个会话（[engineMutex] 覆盖「初始化 → 生成 → 关闭」全过程），
 *   既避免多线程争用同一原生引擎，也保证 [release] 不会在生成中途关引擎。
 * - **后端**：AUTO/GPU 先试 GPU，失败自动回退 CPU；CPU 只走 CPU。
 */
internal object MobileActionsEngine {
    /** 生成超时：270M 模型单轮通常 1-3 秒，留足余量防止卡死挂住互斥锁。 */
    private const val GENERATION_TIMEOUT_MS = 120_000L

    /** 空闲释放：与 embedding 引擎同口径，避免长期占用 300-500 MB。 */
    private const val IDLE_RELEASE_MS = 5 * 60 * 1000L

    private val engineMutex = Mutex()
    private val idleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var idleJob: Job? = null
    private var engine: Engine? = null
    private var enginePath: String? = null
    private var engineBackend: MobileActionsModelStore.Backend? = null

    sealed interface Event {
        /** 首次运行需要加载模型（数秒），用于展示进度文案。 */
        data object Preparing : Event

        /** 模型调用了一个动作，[outcome] 是真实执行结果。 */
        data class Action(val action: MobileActionsAction, val outcome: MobileActionsOutcome) : Event

        /** 模型生成的最终回复（可能为空，例如只触发了动作）。 */
        data class Reply(val text: String) : Event

        /** 加载或生成失败；[message] 已本地化。 */
        data class Failure(val message: String) : Event
    }

    /**
     * 执行一条自然语言指令，按发生顺序发出事件。
     *
     * 使用方在 Main 上收集即可；内部在单线程调度器上串行执行，并在取消时关闭会话
     * （不关闭引擎，下次运行可复用）。
     */
    fun run(context: Context, instruction: String): Flow<Event> = channelFlow {
        val app = context.applicationContext
        idleJob?.cancel()
        val file = MobileActionsModelStore.modelFile(app)
        io.github.mangi.eta.core.AndroidAgentLogger.info(
            "mobile-actions run: chars=${instruction.length} file=${file?.name ?: "null"} " +
                "backend=${MobileActionsModelStore.backend(app)}"
        )
        if (file == null) {
            io.github.mangi.eta.core.AndroidAgentLogger.warn("mobile-actions model file missing or size mismatch")
            send(Event.Failure(app.getString(R.string.mobile_actions_not_ready)))
            return@channelFlow
        }
        val outcomes = Collections.synchronizedList(
            mutableListOf<Pair<MobileActionsAction, MobileActionsOutcome>>(),
        )
        engineMutex.withLock {
            // 只有真正需要重新初始化时才提示「加载模型」，热启动直接显示执行中。
            val needsLoading = engine == null ||
                enginePath != file.path ||
                engineBackend != MobileActionsModelStore.backend(app)
            if (needsLoading) send(Event.Preparing)
            val ready = try {
                ensureEngineLocked(app, file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                io.github.mangi.eta.core.AndroidAgentLogger.warn(
                    "mobile-actions engine init failed: ${error.javaClass.name}: ${error.message}"
                )
                send(Event.Failure(failureText(app, error)))
                return@withLock
            }
            var reply = ""
            var collected = 0
            try {
                io.github.mangi.eta.core.AndroidAgentLogger.info("mobile-actions conversation create")
                ready.createConversation(
                    ConversationConfig(
                        systemInstruction = systemInstruction(),
                        // 贪心采样：函数调用要求同一指令稳定落到同一动作。
                        samplerConfig = SamplerConfig(
                            topK = 1,
                            topP = 1.0,
                            temperature = 0.0,
                            seed = 0,
                        ),
                        tools = listOf(
                            tool(
                                MobileActionsToolSet(app) { action, outcome ->
                                    outcomes.add(action to outcome)
                                },
                            ),
                        ),
                    ),
                ).use { conversation ->
                    val builder = StringBuilder()
                    withTimeout(GENERATION_TIMEOUT_MS) {
                        conversation.sendMessageAsync(instruction).collect { message ->
                            // 0.18.0 的 Message 没有 text 属性，文本在 Contents 的 Text 片段里。
                            val text = message.contents.contents
                                .filterIsInstance<Content.Text>()
                                .joinToString("") { it.text }
                            if (text.isNotBlank()) {
                                builder.append(text)
                                collected += 1
                            }
                        }
                    }
                    reply = builder.toString().trim()
                }
            } catch (timeout: TimeoutCancellationException) {
                io.github.mangi.eta.core.AndroidAgentLogger.warn("mobile-actions generation timeout")
                send(Event.Failure(app.getString(R.string.mobile_actions_error_timeout)))
                return@withLock
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                io.github.mangi.eta.core.AndroidAgentLogger.warn(
                    "mobile-actions generation failed: ${error.javaClass.name}: ${error.message}"
                )
                send(Event.Failure(failureText(app, error)))
                return@withLock
            }
            io.github.mangi.eta.core.AndroidAgentLogger.info(
                "mobile-actions done: chunks=$collected replyChars=${reply.length} actions=${outcomes.size}"
            )
            outcomes.forEach { (action, outcome) -> send(Event.Action(action, outcome)) }
            if (reply.isNotEmpty()) send(Event.Reply(reply))
        }
        scheduleIdleRelease()
    }.flowOn(Dispatchers.Default.limitedParallelism(1))

    /** 释放引擎占用的内存（约 300-500 MB）；下次运行按新配置重建。 */
    suspend fun release() {
        idleJob?.cancel()
        idleJob = null
        engineMutex.withLock { releaseLocked() }
    }

    /** 一轮结束后延迟释放；期间又有新指令会取消这次释放。 */
    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = idleScope.launch {
            delay(IDLE_RELEASE_MS)
            engineMutex.withLock { releaseLocked() }
        }
    }

    private suspend fun ensureEngineLocked(context: Context, file: File): Engine {
        val preferred = MobileActionsModelStore.backend(context)
        engine?.let { existing ->
            if (enginePath == file.path && engineBackend == preferred) return existing
        }
        releaseLocked()
        // AUTO 与 GPU 同链：GPU 不可用时按官方建议回退 CPU。
        val chain = when (preferred) {
            MobileActionsModelStore.Backend.CPU -> listOf(Backend.CPU())
            MobileActionsModelStore.Backend.AUTO,
            MobileActionsModelStore.Backend.GPU,
            -> listOf(Backend.GPU(), Backend.CPU())
        }
        var failure: Throwable? = null
        for (backend in chain) {
            val candidate = try {
                Engine(
                    EngineConfig(
                        modelPath = file.path,
                        backend = backend,
                        // 可写缓存目录显著缩短第二次加载时间。
                        cacheDir = context.cacheDir.path,
                    ),
                )
            } catch (error: Throwable) {
                failure = error
                continue
            }
            try {
                withContext(Dispatchers.IO) { candidate.initialize() }
            } catch (cancelled: CancellationException) {
                runCatching { candidate.close() }
                throw cancelled
            } catch (error: Throwable) {
                failure = error
                runCatching { candidate.close() }
                continue
            }
            engine = candidate
            enginePath = file.path
            engineBackend = preferred
            return candidate
        }
        throw failure ?: IllegalStateException("Mobile actions engine unavailable")
    }

    private fun releaseLocked() {
        runCatching { engine?.close() }
        engine = null
        enginePath = null
        engineBackend = null
    }

    /**
     * 逐字复刻 `google/mobile-actions` 训练集里的 developer 消息。
     *
     * 关键点（与 AI Edge Gallery 的写法不同，这里以训练数据为准）：
     * - **单段文本**：训练样本里 developer 消息只有一段，内容顺序是
     *   「当前时间 → 星期 → 函数调用声明」。该 checkpoint 自带的 jinja 模板会把
     *   `systemInstruction` 的**第一段**渲染在 `<start_function_declaration>` 之前、
     *   其余段渲染在之后；只给一段，才能让整段都落在工具声明之前，与训练分布一致。
     * - **星期用英文**：训练样本是 "Day of week is Wednesday"，用设备locale 会变成
     *   "星期三"，模型就认不出了。
     * - 时间每次现取：模型完全靠这一行把「明天下午三点」换算成绝对时间。
     */
    private fun systemInstruction(): Contents {
        val now = LocalDateTime.now()
        val dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        val dayOfWeek = now.format(DateTimeFormatter.ofPattern("EEEE", Locale.US))
        return Contents.of(
            Content.Text(
                "Current date and time given in YYYY-MM-DDTHH:MM:SS format: $dateTime\n" +
                    "Day of week is $dayOfWeek\n" +
                    "You are a model that can do function calling with the following functions",
            ),
        )
    }

    /** 错误文案里不带堆栈，只给可读分类，避免把原生错误码甩给用户。 */
    private fun failureText(context: Context, error: Throwable): String =
        context.getString(R.string.mobile_actions_error_generic, error.javaClass.simpleName)
}
