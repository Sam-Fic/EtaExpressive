package io.github.mangi.eta.hook.xiaoai

import android.content.Context
import io.github.mangi.eta.agent.xiaomi.XiaomiToolsBridgeEndpoint
import io.github.mangi.eta.agent.xiaomi.XiaomiToolsBridgeProtocol
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 超级小爱进程内的反射宿主。任何目标类缺失都只降级这组工具。 */
internal object XiaoAiToolsBridgeHost : XiaomiToolsBridgeEndpoint {
    private const val TOOL_REGISTRY_CLASS = "com.aios.tools.core.ToolRegistry"

    private data class BridgeState(
        val instance: Any,
        val callTool: Method,
        val callbackInterface: Class<*>,
        val catalog: XiaomiToolsBridgeProtocol.Catalog,
        val logger: ModuleLogger,
    )

    private val state = AtomicReference<BridgeState?>()
    private val preparing = AtomicBoolean(false)

    fun prepare(
        context: Context,
        classLoader: ClassLoader,
        logger: ModuleLogger,
    ): XiaomiToolsBridgeProtocol.Catalog {
        state.get()?.let { return it.catalog }
        if (!preparing.compareAndSet(false, true)) return XiaomiToolsBridgeProtocol.Catalog.EMPTY
        try {
            val bridgeClass = TOOLS_BRIDGE_CLASS_CANDIDATES.firstNotNullOfOrNull { className ->
                runCatching { Class.forName(className, false, classLoader) }.getOrNull()
            } ?: return XiaomiToolsBridgeProtocol.Catalog.EMPTY
            val instance = bridgeClass.declaredFields
                .firstOrNull { field ->
                    Modifier.isStatic(field.modifiers) && field.type == bridgeClass
                }
                ?.let { field ->
                    field.isAccessible = true
                    field.get(null)
                }
                ?: return XiaomiToolsBridgeProtocol.Catalog.EMPTY
            val init = bridgeClass.methods.firstOrNull { method ->
                method.name == "init" &&
                    method.parameterTypes.contentEquals(arrayOf(Context::class.java))
            } ?: return XiaomiToolsBridgeProtocol.Catalog.EMPTY
            init.invoke(instance, context.applicationContext ?: context)

            val callTool = bridgeClass.methods.firstOrNull { method ->
                method.name == "callTool" &&
                    method.parameterCount == 4 &&
                    method.parameterTypes.take(3).all { it == String::class.java } &&
                    method.parameterTypes[3].isInterface
            } ?: return XiaomiToolsBridgeProtocol.Catalog.EMPTY
            val registry = bridgeClass.declaredFields
                .firstOrNull { field ->
                    Modifier.isStatic(field.modifiers) && field.type.name == TOOL_REGISTRY_CLASS
                }
                ?.let { field ->
                    field.isAccessible = true
                    field.get(null)
                }
                ?: return XiaomiToolsBridgeProtocol.Catalog.EMPTY
            val tools = registry.javaClass.getMethod("getAll").invoke(registry) as? Iterable<*>
                ?: return XiaomiToolsBridgeProtocol.Catalog.EMPTY
            val catalog = XiaomiToolsBridgeProtocol.Catalog(
                definitions = tools.mapNotNull(::definitionFromTool)
                    .distinctBy { it.modelName },
            )
            if (catalog.definitions.isEmpty()) return XiaomiToolsBridgeProtocol.Catalog.EMPTY

            val prepared = BridgeState(
                instance = instance,
                callTool = callTool,
                callbackInterface = callTool.parameterTypes[3],
                catalog = catalog,
                logger = logger,
            )
            state.set(prepared)
            logger.info("超级小爱 ToolsBridge 已就绪: tools=${catalog.definitions.size}")
            return catalog
        } catch (throwable: Throwable) {
            logger.warnThrottled("xiaoai_tools_bridge_prepare_failed") {
                "超级小爱 ToolsBridge 初始化失败，保持独立降级: type=${throwable.safeLogType()}"
            }
            return XiaomiToolsBridgeProtocol.Catalog.EMPTY
        } finally {
            preparing.set(false)
        }
    }

    fun catalog(): XiaomiToolsBridgeProtocol.Catalog =
        state.get()?.catalog ?: XiaomiToolsBridgeProtocol.Catalog.EMPTY

    override fun execute(
        request: XiaomiToolsBridgeProtocol.CallRequest,
        callback: (XiaomiToolsBridgeProtocol.CallResult) -> Unit,
    ) {
        val bridge = state.get()
        if (bridge == null) {
            callback(
                XiaomiToolsBridgeProtocol.CallResult(
                    callId = request.callId,
                    status = XiaomiToolsBridgeProtocol.Status.UNAVAILABLE,
                    error = "超级小爱 ToolsBridge 尚未就绪",
                ),
            )
            return
        }
        val definition = bridge.catalog.definition(request.modelName)
        if (definition == null) {
            callback(
                XiaomiToolsBridgeProtocol.CallResult(
                    callId = request.callId,
                    status = XiaomiToolsBridgeProtocol.Status.INVALID_REQUEST,
                    error = "工具未在白名单能力目录中声明",
                ),
            )
            return
        }
        val arguments = runCatching {
            XiaomiToolsBridgeProtocol.targetArguments(definition, request.argumentsJson)
        }.getOrElse { throwable ->
            callback(
                XiaomiToolsBridgeProtocol.CallResult(
                    callId = request.callId,
                    status = XiaomiToolsBridgeProtocol.Status.INVALID_REQUEST,
                    error = throwable.message ?: "工具参数无效",
                ),
            )
            return
        }

        val delivered = AtomicBoolean(false)
        val proxy = Proxy.newProxyInstance(
            bridge.callbackInterface.classLoader,
            arrayOf(bridge.callbackInterface),
        ) { proxyInstance, method, args ->
            when (method.name) {
                "onResult" -> {
                    if (delivered.compareAndSet(false, true)) {
                        callback(resultFromTarget(request.callId, args?.firstOrNull()))
                    }
                    null
                }
                "toString" -> "EtaXiaomiToolsBridgeCallback"
                "hashCode" -> System.identityHashCode(proxyInstance)
                "equals" -> proxyInstance === args?.firstOrNull()
                else -> null
            }
        }
        try {
            bridge.callTool.invoke(
                bridge.instance,
                definition.rawName,
                arguments,
                request.sessionId,
                proxy,
            )
        } catch (throwable: Throwable) {
            bridge.logger.warnThrottled("xiaoai_tools_bridge_call_failed_${definition.modelName}") {
                "超级小爱 ToolsBridge 调用失败: tool=${definition.modelName}, " +
                    "type=${throwable.safeLogType()}"
            }
            if (delivered.compareAndSet(false, true)) {
                callback(
                    XiaomiToolsBridgeProtocol.CallResult(
                        callId = request.callId,
                        status = XiaomiToolsBridgeProtocol.Status.ERROR,
                        error = "超级小爱 ToolsBridge 调用失败",
                    ),
                )
            }
        }
    }

    private fun definitionFromTool(tool: Any?): XiaomiToolsBridgeProtocol.Definition? {
        tool ?: return null
        val targetClass = tool.javaClass.name
        XiaomiToolsBridgeProtocol.exposureForTargetClass(targetClass) ?: return null
        val rawName = invokeString(tool, "getName") ?: return null
        val description = invokeString(tool, "getDescription").orEmpty()
        val parameters = invokeNoArgs(tool, "getParameters")?.toString() ?: return null
        return XiaomiToolsBridgeProtocol.definitionFromTarget(
            targetClassName = targetClass,
            rawName = rawName,
            description = description,
            parametersJson = parameters,
        )
    }

    private fun resultFromTarget(
        callId: String,
        target: Any?,
    ): XiaomiToolsBridgeProtocol.CallResult {
        if (target == null) {
            return XiaomiToolsBridgeProtocol.CallResult(
                callId = callId,
                status = XiaomiToolsBridgeProtocol.Status.ERROR,
                error = "ToolsBridge 未返回结果",
            )
        }
        val status = when (invokeNoArgs(target, "getStatus")?.toString()) {
            "SUCCESS" -> XiaomiToolsBridgeProtocol.Status.SUCCESS
            "PERMISSION_DENIED" -> XiaomiToolsBridgeProtocol.Status.PERMISSION_DENIED
            else -> XiaomiToolsBridgeProtocol.Status.ERROR
        }
        return XiaomiToolsBridgeProtocol.CallResult(
            callId = callId,
            status = status,
            result = invokeString(target, "getResult"),
            error = invokeString(target, "getError"),
            executionTimeMs = (invokeNoArgs(target, "getExecutionTimeMs") as? Number)
                ?.toLong()
                ?.coerceAtLeast(0L)
                ?: 0L,
        )
    }

    private fun invokeString(target: Any, methodName: String): String? =
        invokeNoArgs(target, methodName) as? String

    private fun invokeNoArgs(target: Any, methodName: String): Any? = runCatching {
        target.javaClass.getMethod(methodName).invoke(target)
    }.getOrNull()

    private val TOOLS_BRIDGE_CLASS_CANDIDATES = listOf(
        "jp0.f", // 超级小爱 8.0.17.3013 / 508000017
        "com.xiaomi.voiceassist.agent.tools.ToolsBridge",
    )
}
