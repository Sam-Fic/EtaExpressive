package io.github.mangi.eta.hook.xiaoai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.XiaomiUiAgentToolCatalog
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 运行在 com.miui.voiceassist UID 内的 UIAgent App Tool Provider 客户端。
 *
 * 不引用或打包小米私有 SDK；只使用目标 APK 已发布的 AIDL wire contract。
 */
internal class XiaoAiUiAgentBridge(
    context: Context,
    private val logger: AgentLogger,
    classLoader: ClassLoader,
) : AgentModelClient.ToolExecutor, AutoCloseable {
    private val appContext = context.applicationContext ?: context
    private val closed = AtomicBoolean(false)
    private val binder = AtomicReference<IBinder?>()
    private val bindLock = Any()
    private val nextRequestId = AtomicLong(1L)
    private var bound = false
    private var connectLatch: CountDownLatch? = null

    val availableTools: Set<String> = discoverAvailableTools(
        context = appContext,
        classLoader = classLoader,
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder.set(service)
            synchronized(bindLock) {
                connectLatch?.countDown()
                connectLatch = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder.set(null)
        }

        override fun onNullBinding(name: ComponentName) {
            resetBinding()
        }

        override fun onBindingDied(name: ComponentName) {
            resetBinding()
        }
    }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (closed.get()) return error("XIAOMI_UIAGENT_CLOSED", "小米 UIAgent 桥已关闭")
        if (toolCall.name !in availableTools || toolCall.name !in XiaomiUiAgentToolCatalog.modelToolNames) {
            return error("XIAOMI_UIAGENT_TOOL_UNAVAILABLE", "当前超级小爱没有提供工具：${toolCall.name}")
        }
        val arguments = runCatching {
            JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
        }.getOrElse {
            return error("INVALID_ARGUMENT", "小米 UIAgent 参数不是有效的 JSON 对象")
        }
        XiaomiUiAgentToolCatalog.validate(toolCall.name, arguments)?.let { issue ->
            return error(issue.code, issue.message)
        }
        val target = ensureBinder()
            ?: return error("XIAOMI_UIAGENT_BIND_FAILED", "无法连接超级小爱 UIAgent 服务")
        val requestId = nextRequestId.getAndIncrement()
        val request = XiaomiUiAgentRpc.buildRequest(requestId, toolCall.name, arguments)
        val response = transact(target, request)
            ?: return error("XIAOMI_UIAGENT_TRANSACT_FAILED", "超级小爱 UIAgent 调用失败")
        return XiaomiUiAgentRpc.parseResponse(
            toolName = toolCall.name,
            arguments = arguments,
            responseJson = response,
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        binder.set(null)
        synchronized(bindLock) {
            connectLatch?.countDown()
            connectLatch = null
            if (bound) {
                runCatching { appContext.unbindService(connection) }
                bound = false
            }
        }
    }

    private fun ensureBinder(): IBinder? {
        binder.get()?.takeIf(IBinder::isBinderAlive)?.let { return it }
        if (closed.get() || availableTools.isEmpty()) return null
        val latch: CountDownLatch
        synchronized(bindLock) {
            binder.get()?.takeIf(IBinder::isBinderAlive)?.let { return it }
            if (closed.get()) return null
            latch = connectLatch ?: CountDownLatch(1).also { connectLatch = it }
            if (!bound) {
                bound = runCatching {
                    appContext.bindService(
                        Intent(APP_TOOL_ACTION).setComponent(SERVICE_COMPONENT),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                }.getOrElse { throwable ->
                    logger.warn(
                        "XiaoAi UIAgent bind failed: type=${throwable.safeLogType()}",
                    )
                    false
                }
                if (!bound) {
                    connectLatch = null
                    latch.countDown()
                }
            }
        }
        return try {
            if (!latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("XiaoAi UIAgent bind timed out")
                resetBinding()
                null
            } else {
                binder.get()?.takeIf(IBinder::isBinderAlive)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun resetBinding() {
        binder.set(null)
        synchronized(bindLock) {
            connectLatch?.countDown()
            connectLatch = null
            if (bound) runCatching { appContext.unbindService(connection) }
            bound = false
        }
    }

    private fun transact(target: IBinder, request: String): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AIDL_DESCRIPTOR)
            data.writeString(request)
            if (!target.transact(TRANSACTION_HANDLE_REQUEST, data, reply, 0)) return null
            reply.readException()
            reply.readString()
        } catch (throwable: Throwable) {
            logger.warn("XiaoAi UIAgent transact failed: type=${throwable.safeLogType()}")
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun error(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
        )

    internal companion object {
        private const val TARGET_PACKAGE = "com.miui.voiceassist"
        private const val SERVICE_CLASS = "com.xiaomi.voiceassistant.UIAgentServiceForOSBot"
        private const val APP_TOOLS_ASSET = "app_tools.json"
        private const val APP_TOOL_ACTION = "com.aios.osbot.action.APP_TOOL_PROVIDER"
        private const val APP_TOOL_PERMISSION = "com.aios.osbot.permission.BIND_APP_TOOL"
        private const val AIDL_DESCRIPTOR = "com.aios.apptoolsdk.aidl.IAppToolProvider"
        private const val TRANSACTION_HANDLE_REQUEST = 1
        private const val BIND_TIMEOUT_SECONDS = 10L
        private val SERVICE_COMPONENT = ComponentName(TARGET_PACKAGE, SERVICE_CLASS)

        fun discoverAvailableTools(context: Context, classLoader: ClassLoader): Set<String> {
            if (context.packageName != TARGET_PACKAGE) return emptySet()
            val service = runCatching {
                context.packageManager.resolveService(
                    Intent(APP_TOOL_ACTION).setComponent(SERVICE_COMPONENT),
                    0,
                )?.serviceInfo
            }.getOrNull() ?: return emptySet()
            if (
                service.packageName != TARGET_PACKAGE ||
                service.name != SERVICE_CLASS ||
                service.permission != APP_TOOL_PERMISSION
            ) {
                return emptySet()
            }
            if (runCatching { Class.forName(SERVICE_CLASS, false, classLoader) }.isFailure) {
                return emptySet()
            }
            val asset = runCatching {
                context.assets.open(APP_TOOLS_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull() ?: return emptySet()
            return availableToolNamesFromAsset(asset)
        }

        fun availableToolNamesFromAsset(assetJson: String): Set<String> = runCatching {
            val entries = JSONArray(assetJson)
            buildSet {
                for (index in 0 until entries.length()) {
                    val entry = entries.optJSONObject(index) ?: continue
                    if (entry.optString("class") != SERVICE_CLASS) continue
                    val name = entry.optString("name")
                    if (name in XiaomiUiAgentToolCatalog.providerToolNames) add(name)
                }
            }
        }.getOrDefault(emptySet())
    }
}

internal object XiaomiUiAgentRpc {
    fun buildRequest(id: Long, toolName: String, arguments: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", "tools/call")
            .put(
                "params",
                JSONObject()
                    .put("name", toolName)
                    .put("arguments", arguments),
            )
            .toString()

    fun parseResponse(
        toolName: String,
        arguments: JSONObject,
        responseJson: String,
    ): AgentModelClient.ToolResult {
        val response = runCatching { JSONObject(responseJson) }.getOrElse {
            return error("XIAOMI_UIAGENT_INVALID_RESPONSE", "超级小爱 UIAgent 返回了无效响应")
        }
        response.optJSONObject("error")?.let { rpcError ->
            return error(
                code = "XIAOMI_UIAGENT_RPC_ERROR",
                message = rpcError.optString("message").ifBlank { "UIAgent RPC 执行失败" },
            )
        }
        val result = response.optJSONObject("result")
            ?: return error("XIAOMI_UIAGENT_INVALID_RESPONSE", "超级小爱 UIAgent 响应缺少 result")
        val content = result.optJSONArray("content")
        val text = buildString {
            if (content != null) {
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    if (item.optString("type") != "text") continue
                    if (isNotEmpty()) append('\n')
                    append(item.optString("text"))
                }
            }
        }
        val sensitive = XiaomiUiAgentToolCatalog.isSensitiveCall(toolName, arguments)
        if (result.optBoolean("isError", false)) {
            return error(
                code = "XIAOMI_UIAGENT_TOOL_ERROR",
                message = text.ifBlank { "超级小爱 UIAgent 工具执行失败" },
                sensitive = sensitive,
            )
        }
        val value = runCatching { JSONTokener(text).nextValue() }
            .getOrNull()
            .takeIf { it is JSONObject || it is JSONArray }
            ?: text
        return AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", true)
                .put("source", "xiaomi_uiagent")
                .put("tool", toolName)
                .put("result", value)
                .toString(),
            sensitive = sensitive,
        )
    }

    private fun error(
        code: String,
        message: String,
        sensitive: Boolean = false,
    ): AgentModelClient.ToolResult = AgentModelClient.ToolResult(
        content = JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message.take(2_000))
            .toString(),
        sensitive = sensitive,
    )
}
