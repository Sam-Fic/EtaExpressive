package io.github.mangi.eta.hook.xiaoai

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.CancellationSignal
import android.os.IBinder
import android.os.IInterface
import android.os.ParcelFileDescriptor
import io.github.mangi.eta.agent.model.XiaomiHostToolCatalog
import io.github.mangi.eta.agent.runtime.AgentHostAttachment
import io.github.mangi.eta.agent.runtime.AgentHostBridge
import io.github.mangi.eta.agent.runtime.AgentHostCall
import io.github.mangi.eta.agent.runtime.AgentHostCapabilities
import io.github.mangi.eta.agent.runtime.AgentHostEvent
import io.github.mangi.eta.agent.runtime.AgentHostResult
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.safeLogType
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Xiaomi-only privileged capabilities. All framework/vendor classes are resolved from the target process. */
internal class XiaomiHostBridge(
    context: Context,
    private val classLoader: ClassLoader,
    private val logger: AgentLogger,
) : AgentHostBridge {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val externalAgent = ExternalAgentConnection(appContext, classLoader, logger)

    override val capabilities: Set<String> = buildSet {
        if (isPackageInstalled(AIASST_VISION_PACKAGE) && hasPlatformAppFunctions()) {
            add(AgentHostCapabilities.AIASST_VISION_APP_FUNCTIONS)
        }
        if (externalAgent.isSupported) add(AgentHostCapabilities.EXTERNAL_AGENT)
    }

    override fun execute(
        call: AgentHostCall,
        onEvent: (AgentHostEvent) -> Unit,
    ): AgentHostResult {
        if (closed.get()) return failure(call, "HOST_BRIDGE_CLOSED", "小米宿主桥已关闭")
        return when (call.toolName) {
            XiaomiHostToolCatalog.OPEN_AI_SUBTITLES ->
                executeAppFunction(call, FUNCTION_OPEN_AI_SUBTITLES)
            XiaomiHostToolCatalog.OPEN_CONVERSATION_TRANSLATION ->
                executeAppFunction(call, FUNCTION_OPEN_CONVERSATION_TRANSLATION)
            XiaomiHostToolCatalog.OPEN_SIMULTANEOUS_INTERPRETATION ->
                executeAppFunction(call, FUNCTION_OPEN_SIMULTANEOUS_INTERPRETATION)
            XiaomiHostToolCatalog.OPEN_TEXT_TRANSLATION ->
                executeAppFunction(call, FUNCTION_OPEN_TEXT_TRANSLATION)
            XiaomiHostToolCatalog.EXTERNAL_AGENT ->
                externalAgent.execute(call, onEvent)
            else -> failure(call, "UNKNOWN_HOST_TOOL", "未知小米宿主工具")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        externalAgent.close()
    }

    private fun executeAppFunction(call: AgentHostCall, functionId: String): AgentHostResult {
        if (AgentHostCapabilities.AIASST_VISION_APP_FUNCTIONS !in capabilities) {
            return failure(call, "APP_FUNCTION_UNAVAILABLE", "当前设备不支持 AiasstVision App Functions")
        }
        return runCatching {
            val managerClass = Class.forName(APP_FUNCTION_MANAGER_CLASS)
            val requestClass = Class.forName(EXECUTE_REQUEST_CLASS)
            val builderClass = Class.forName(EXECUTE_REQUEST_BUILDER_CLASS)
            val outcomeReceiverClass = Class.forName(OUTCOME_RECEIVER_CLASS)
            val manager = Context::class.java
                .getMethod("getSystemService", Class::class.java)
                .invoke(appContext, managerClass)
                ?: error("AppFunctionManager 不可用")
            val request = builderClass
                .getConstructor(String::class.java, String::class.java)
                .newInstance(AIASST_VISION_PACKAGE, functionId)
                .let { builder -> builderClass.getMethod("build").invoke(builder) }
            val latch = CountDownLatch(1)
            val response = AtomicReference<Any?>()
            val error = AtomicReference<Any?>()
            val receiver = Proxy.newProxyInstance(
                outcomeReceiverClass.classLoader,
                arrayOf(outcomeReceiverClass),
            ) { proxy, method, args ->
                when (method.name) {
                    "onResult" -> {
                        response.set(args?.firstOrNull())
                        latch.countDown()
                        null
                    }
                    "onError" -> {
                        error.set(args?.firstOrNull())
                        latch.countDown()
                        null
                    }
                    "toString" -> "EtaAiasstVisionOutcomeReceiver"
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> args?.firstOrNull() === proxy
                    else -> null
                }
            }
            val cancellation = CancellationSignal()
            managerClass.getMethod(
                "executeAppFunction",
                requestClass,
                Executor::class.java,
                CancellationSignal::class.java,
                outcomeReceiverClass,
            ).invoke(manager, request, Executor { runnable -> runnable.run() }, cancellation, receiver)
            if (!latch.await(APP_FUNCTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                cancellation.cancel()
                return failure(call, "APP_FUNCTION_TIMEOUT", "AiasstVision App Function 执行超时", true)
            }
            error.get()?.let { throwable ->
                val code = invokeNumber(throwable, "getErrorCode")?.toInt()
                val message = invokeString(throwable, "getErrorMessage")
                    .ifBlank { invokeString(throwable, "getMessage") }
                    .ifBlank { "AiasstVision App Function 执行失败" }
                return failure(
                    call,
                    code?.let { "APP_FUNCTION_$it" } ?: "APP_FUNCTION_ERROR",
                    message,
                )
            }
            val resultDocument = response.get()?.let { invokeNoArgs(it, "getResultDocument") }
            AgentHostResult(
                callId = call.callId,
                ok = true,
                payload = JSONObject()
                    .put("package", AIASST_VISION_PACKAGE)
                    .put("function_id", functionId)
                    .put("result", resultDocument?.let(::documentToJson) ?: JSONObject())
                    .toString(),
            )
        }.getOrElse { throwable ->
            logger.warn("AiasstVision App Function failed: type=${throwable.safeLogType()}")
            failure(call, "APP_FUNCTION_ERROR", throwable.message ?: "AiasstVision App Function 执行失败")
        }
    }

    private fun documentToJson(document: Any): JSONObject {
        val output = JSONObject()
            .put("schema_type", invokeString(document, "getSchemaType"))
            .put("id", invokeString(document, "getId"))
        val properties = JSONObject()
        val names = invokeNoArgs(document, "getPropertyNames") as? Iterable<*>
        names?.forEach { nameValue ->
            val name = nameValue?.toString() ?: return@forEach
            val value = runCatching {
                document.javaClass.getMethod("getProperty", String::class.java).invoke(document, name)
            }.getOrNull()
            properties.put(name, jsonValue(value))
        }
        return output.put("properties", properties)
    }

    private fun jsonValue(value: Any?): Any = when {
        value == null -> JSONObject.NULL
        value.javaClass.isArray -> JSONArray().also { array ->
            repeat(ReflectArray.getLength(value)) { index ->
                array.put(jsonValue(ReflectArray.get(value, index)))
            }
        }
        value.javaClass.name == GENERIC_DOCUMENT_CLASS -> documentToJson(value)
        value is ByteArray -> JSONObject().put("bytes", value.size)
        value is Number || value is Boolean || value is String -> value
        else -> value.toString()
    }

    private fun hasPlatformAppFunctions(): Boolean = runCatching {
        Class.forName(APP_FUNCTION_MANAGER_CLASS)
        Class.forName(EXECUTE_REQUEST_BUILDER_CLASS)
    }.isSuccess

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        appContext.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess

    private fun failure(
        call: AgentHostCall,
        code: String,
        message: String,
        retryable: Boolean = false,
    ) = AgentHostResult(
        callId = call.callId,
        ok = false,
        payload = "{}",
        errorCode = code,
        errorMessage = message,
        retryable = retryable,
    )

    private class ExternalAgentConnection(
        private val context: Context,
        private val classLoader: ClassLoader,
        private val logger: AgentLogger,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val service = AtomicReference<Any?>()
        private val connectLatch = AtomicReference(CountDownLatch(1))
        private val bound = AtomicBoolean(false)
        private val sessionIds = ConcurrentHashMap.newKeySet<String>()

        val isSupported: Boolean = runCatching {
            Class.forName(EXTERNAL_SERVICE_STUB_CLASS, false, classLoader)
            Class.forName(EXTERNAL_CALLBACK_CLASS, false, classLoader)
            Class.forName(ATTACHMENT_CLASS, false, classLoader)
        }.isSuccess

        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val resolved = runCatching {
                    val stub = Class.forName(EXTERNAL_SERVICE_STUB_CLASS, false, classLoader)
                    stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                }.getOrNull()
                service.set(resolved)
                connectLatch.get().countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                service.set(null)
                bound.set(false)
            }

            override fun onBindingDied(name: ComponentName) {
                onServiceDisconnected(name)
            }

            override fun onNullBinding(name: ComponentName) {
                service.set(null)
                connectLatch.get().countDown()
            }
        }

        fun execute(
            call: AgentHostCall,
            onEvent: (AgentHostEvent) -> Unit,
        ): AgentHostResult {
            if (!isSupported) return failure(call, "EXTERNAL_AGENT_UNAVAILABLE", "当前超级小爱没有 External Agent SDK")
            val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }
                .getOrElse { return failure(call, "INVALID_ARGUMENT", "参数 JSON 无效") }
            return when (args.optString("action")) {
                "probe" -> probe(call)
                "open_session" -> openSession(call, args)
                "submit" -> submit(call, args, onEvent)
                "close_session" -> closeSession(call, args.optString("session_id"))
                else -> failure(call, "INVALID_ARGUMENT", "action 必须是 probe/open_session/submit/close_session")
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            val current = service.get()
            if (current != null) {
                sessionIds.toList().forEach { id -> invokeClose(current, id) }
            }
            sessionIds.clear()
            if (bound.getAndSet(false)) runCatching { context.unbindService(connection) }
            service.set(null)
        }

        private fun probe(call: AgentHostCall): AgentHostResult {
            val connected = ensureConnected() != null
            val version = runCatching {
                val info = context.packageManager.getPackageInfo(HOST_PACKAGE, 0)
                JSONObject()
                    .put("version_name", info.versionName)
                    .put("version_code", info.longVersionCode)
            }.getOrDefault(JSONObject())
            return AgentHostResult(
                callId = call.callId,
                ok = connected,
                payload = JSONObject()
                    .put("connected", connected)
                    .put("host_package", HOST_PACKAGE)
                    .put("host", version)
                    .put("interface", JSONObject()
                        .put("open_session", true)
                        .put("submit", true)
                        .put("close_session_cancel", true)
                        .put("text_delta", true)
                        .put("reasoning_delta", true)
                        .put("tool_event", true)
                        .put("tts_event", true)
                        .put("input_attachments", true)
                        .put("result_attachments", true))
                    .toString(),
                errorCode = if (connected) null else "EXTERNAL_AGENT_CONNECT_FAILED",
                errorMessage = if (connected) null else "无法连接超级小爱 ExternalAgentService",
                retryable = !connected,
            )
        }

        private fun openSession(call: AgentHostCall, args: JSONObject): AgentHostResult {
            val current = ensureConnected()
                ?: return failure(call, "EXTERNAL_AGENT_CONNECT_FAILED", "无法连接超级小爱 ExternalAgentService", true)
            val metaInput = args.optJSONObject("app_meta") ?: JSONObject()
            val meta = JSONObject()
                .put("appName", metaInput.nonBlankString("app_name", "Eta"))
                .put("locale", metaInput.nonBlankString("locale", Locale.getDefault().toLanguageTag()))
                .put("context", metaInput.optString("context"))
                .put("tag", metaInput.nonBlankString("tag", "eta"))
                .put("targetPackage", metaInput.nonBlankString("target_package", HOST_PACKAGE))
                .put("chatId", metaInput.optString("chat_id"))
                .put("bizId", metaInput.nonBlankString("biz_id", DEFAULT_BIZ_ID))
                .put("featureId", metaInput.nonBlankString("feature_id", DEFAULT_FEATURE_ID))
            val sessionId = runCatching {
                current.javaClass.getMethod(
                    "openSession",
                    String::class.java,
                    Boolean::class.javaPrimitiveType,
                ).invoke(current, meta.toString(), args.optBoolean("tts_enabled", false)) as? String
            }.getOrNull().orEmpty()
            if (sessionId.isBlank() || sessionId.startsWith("error:")) {
                val code = sessionId.substringAfter("error:", "OPEN_SESSION_FAILED")
                return failure(call, code, "External Agent 会话创建失败", code == "CTA_NOT_ACCEPTED")
            }
            sessionIds += sessionId
            return AgentHostResult(
                callId = call.callId,
                ok = true,
                payload = JSONObject()
                    .put("session_id", sessionId)
                    .put("tts_enabled", args.optBoolean("tts_enabled", false))
                    .put("app_meta", meta)
                    .toString(),
            )
        }

        private fun submit(
            call: AgentHostCall,
            args: JSONObject,
            onEvent: (AgentHostEvent) -> Unit,
        ): AgentHostResult {
            val sessionId = args.optString("session_id").trim()
            if (sessionId.isBlank()) return failure(call, "INVALID_ARGUMENT", "submit 缺少 session_id")
            val current = ensureConnected()
                ?: return failure(call, "EXTERNAL_AGENT_CONNECT_FAILED", "无法连接超级小爱 ExternalAgentService", true)
            val requestJson = args.optString("request_json").trim().ifBlank {
                JSONObject()
                    .put("type", "message")
                    .put("text", args.optString("text"))
                    .toString()
            }
            val callbackClass = Class.forName(EXTERNAL_CALLBACK_CLASS, false, classLoader)
            val attachmentClass = Class.forName(ATTACHMENT_CLASS, false, classLoader)
            val callbackResult = AtomicReference<AgentHostResult?>()
            val latch = CountDownLatch(1)
            val binder = Binder()
            lateinit var callbackProxy: Any
            callbackProxy = Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass),
            ) { proxy, method, values ->
                val callbackSession = values?.getOrNull(0)?.toString().orEmpty()
                when (method.name) {
                    "asBinder" -> binder
                    "onTextDelta" -> {
                        onEvent(event(call, "text_delta", callbackSession, values?.getOrNull(1)))
                        null
                    }
                    "onReasoningDelta" -> {
                        onEvent(event(call, "reasoning_delta", callbackSession, values?.getOrNull(1)))
                        null
                    }
                    "onToolEvent" -> {
                        onEvent(event(call, "tool_event", callbackSession, values?.getOrNull(1)))
                        null
                    }
                    "onTtsEvent" -> {
                        onEvent(event(call, "tts_event", callbackSession, values?.getOrNull(1)))
                        null
                    }
                    "onComplete" -> {
                        val payload = values?.getOrNull(1)?.toString().orEmpty()
                        val returned = (values?.getOrNull(2) as? Iterable<*>)
                            ?.mapNotNull(::duplicateReturnedAttachment)
                            .orEmpty()
                        callbackResult.set(
                            AgentHostResult(
                                callId = call.callId,
                                ok = true,
                                payload = JSONObject()
                                    .put("session_id", callbackSession)
                                    .put("result", parseJsonValue(payload))
                                    .toString(),
                                attachments = returned,
                            ),
                        )
                        latch.countDown()
                        null
                    }
                    "onError" -> {
                        val errorJson = values?.getOrNull(1)?.toString().orEmpty()
                        val parsed = runCatching { JSONObject(errorJson) }.getOrDefault(JSONObject())
                        callbackResult.set(
                            failure(
                                call,
                                parsed.optString("code", "EXTERNAL_AGENT_ERROR"),
                                parsed.optString("message", errorJson.ifBlank { "External Agent 执行失败" }),
                                parsed.optBoolean("retryable", false),
                            ),
                        )
                        latch.countDown()
                        null
                    }
                    "toString" -> "EtaExternalAgentCallback"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === values?.firstOrNull()
                    else -> null
                }
            }
            binder.attachInterface(callbackProxy as IInterface, EXTERNAL_CALLBACK_DESCRIPTOR)
            val targetAttachments = mutableListOf<Any>()
            val targetDescriptors = mutableListOf<ParcelFileDescriptor>()
            try {
                call.attachments.forEach { attachment ->
                    val descriptor = attachment.fileDescriptor
                        ?: error("External Agent 输入附件缺少文件描述符")
                    val duplicate = ParcelFileDescriptor.dup(descriptor.fileDescriptor)
                    val target = try {
                        attachmentClass.getMethod(
                            "fromFd",
                            String::class.java,
                            String::class.java,
                            ParcelFileDescriptor::class.java,
                        ).invoke(null, attachment.name, attachment.mimeType, duplicate)
                    } catch (throwable: Throwable) {
                        runCatching { duplicate.close() }
                        throw throwable
                    }
                    targetDescriptors += duplicate
                    targetAttachments += target
                }
                current.javaClass.getMethod(
                    "submit",
                    String::class.java,
                    String::class.java,
                    List::class.java,
                    callbackClass,
                ).invoke(current, sessionId, requestJson, targetAttachments, callbackProxy)
                if (!latch.await(SUBMIT_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    invokeClose(current, sessionId)
                    targetDescriptors.forEach { descriptor -> runCatching { descriptor.close() } }
                    sessionIds.remove(sessionId)
                    return failure(call, "EXTERNAL_AGENT_TIMEOUT", "External Agent 执行超时并已取消", true)
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                invokeClose(current, sessionId)
                targetDescriptors.forEach { descriptor -> runCatching { descriptor.close() } }
                sessionIds.remove(sessionId)
                return failure(call, "EXTERNAL_AGENT_CANCELLED", "External Agent 已取消")
            } catch (throwable: Throwable) {
                targetDescriptors.forEach { descriptor -> runCatching { descriptor.close() } }
                return failure(call, "EXTERNAL_AGENT_SUBMIT_FAILED", throwable.message ?: "External Agent 提交失败", true)
            }
            return callbackResult.get()
                ?: failure(call, "EXTERNAL_AGENT_NO_RESULT", "External Agent 未返回终态", true)
        }

        private fun closeSession(call: AgentHostCall, sessionId: String): AgentHostResult {
            if (sessionId.isBlank()) return failure(call, "INVALID_ARGUMENT", "close_session 缺少 session_id")
            val current = ensureConnected()
                ?: return failure(call, "EXTERNAL_AGENT_CONNECT_FAILED", "无法连接超级小爱 ExternalAgentService", true)
            invokeClose(current, sessionId)
            sessionIds.remove(sessionId)
            return AgentHostResult(
                callId = call.callId,
                ok = true,
                payload = JSONObject()
                    .put("session_id", sessionId)
                    .put("closed", true)
                    .put("active_request_cancelled", true)
                    .toString(),
            )
        }

        private fun ensureConnected(): Any? {
            service.get()?.let { return it }
            if (closed.get()) return null
            synchronized(connection) {
                service.get()?.let { return it }
                if (!bound.get()) {
                    connectLatch.set(CountDownLatch(1))
                    val intent = Intent(EXTERNAL_AGENT_ACTION).setPackage(HOST_PACKAGE)
                    val accepted = runCatching {
                        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                    }.getOrDefault(false)
                    if (!accepted) return null
                    bound.set(true)
                }
            }
            return try {
                connectLatch.get().await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                service.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
        }

        private fun invokeClose(current: Any, sessionId: String) {
            runCatching {
                current.javaClass.getMethod("closeSession", String::class.java).invoke(current, sessionId)
            }.onFailure { throwable ->
                logger.warn("External Agent close failed: type=${throwable.safeLogType()}")
            }
        }

        private fun duplicateReturnedAttachment(raw: Any?): AgentHostAttachment? {
            if (raw == null) return null
            val name = readField(raw, "name")?.toString().orEmpty().ifBlank { "attachment" }
            val mimeType = readField(raw, "mimeType")?.toString().orEmpty()
                .ifBlank { "application/octet-stream" }
            val uri = readField(raw, "uri")?.toString()?.takeIf { it.isNotBlank() }
            val descriptor = raw.javaClass.declaredFields
                .firstOrNull { ParcelFileDescriptor::class.java.isAssignableFrom(it.type) }
                ?.let { field ->
                    field.isAccessible = true
                    field.get(raw) as? ParcelFileDescriptor
                }
            val duplicate = descriptor?.let { source ->
                runCatching { ParcelFileDescriptor.dup(source.fileDescriptor) }.getOrNull()
            } ?: uri?.let { value ->
                runCatching { context.contentResolver.openFileDescriptor(android.net.Uri.parse(value), "r") }
                    .getOrNull()
            }
            runCatching { descriptor?.close() }
            return AgentHostAttachment(
                name = name,
                mimeType = mimeType,
                uri = uri,
                fileDescriptor = duplicate,
            )
        }

        private fun readField(target: Any, name: String): Any? = runCatching {
            target.javaClass.getField(name).get(target)
        }.getOrNull()

        private fun event(call: AgentHostCall, type: String, sessionId: String, payload: Any?) =
            AgentHostEvent(call.callId, type, sessionId, payload?.toString().orEmpty())

        private fun failure(
            call: AgentHostCall,
            code: String,
            message: String,
            retryable: Boolean = false,
        ) = AgentHostResult(
            callId = call.callId,
            ok = false,
            payload = "{}",
            errorCode = code,
            errorMessage = message,
            retryable = retryable,
        )

        private fun parseJsonValue(raw: String): Any =
            raw.trim().takeIf { it.isNotEmpty() }?.let { value ->
                runCatching { JSONObject(value) }.getOrNull()
                    ?: runCatching { JSONArray(value) }.getOrNull()
                    ?: value
            } ?: ""

        private fun JSONObject.nonBlankString(name: String, fallback: String): String =
            optString(name).trim().ifBlank { fallback }

        private companion object {
            const val CONNECT_TIMEOUT_SECONDS = 8L
            const val SUBMIT_TIMEOUT_MINUTES = 10L
            const val DEFAULT_BIZ_ID = "eta"
            const val DEFAULT_FEATURE_ID = "external_agent"
        }
    }

    private companion object {
        const val AIASST_VISION_PACKAGE = "com.xiaomi.aiasst.vision"
        const val HOST_PACKAGE = "com.miui.voiceassist"
        const val APP_FUNCTION_MANAGER_CLASS = "android.app.appfunctions.AppFunctionManager"
        const val EXECUTE_REQUEST_CLASS = "android.app.appfunctions.ExecuteAppFunctionRequest"
        const val EXECUTE_REQUEST_BUILDER_CLASS =
            "android.app.appfunctions.ExecuteAppFunctionRequest\$Builder"
        const val OUTCOME_RECEIVER_CLASS = "android.os.OutcomeReceiver"
        const val GENERIC_DOCUMENT_CLASS = "android.app.appsearch.GenericDocument"
        const val APP_FUNCTION_TIMEOUT_SECONDS = 30L
        const val EXTERNAL_AGENT_ACTION = "com.aios.osbot.action.EXTERNAL_AGENT"
        const val EXTERNAL_SERVICE_STUB_CLASS =
            "com.aios.apptoolsdk.aidl.IExternalAgentService\$Stub"
        const val EXTERNAL_CALLBACK_CLASS =
            "com.aios.apptoolsdk.aidl.IExternalAgentCallback"
        const val EXTERNAL_CALLBACK_DESCRIPTOR =
            "com.aios.apptoolsdk.aidl.IExternalAgentCallback"
        const val ATTACHMENT_CLASS = "com.aios.apptoolsdk.aidl.Attachment"
        const val FUNCTION_PREFIX =
            "com.xiaomi.aiasst.vision.cn.appfunctions.TranslationAppFunctions#"
        const val FUNCTION_OPEN_AI_SUBTITLES = "${FUNCTION_PREFIX}openAiSubtitles"
        const val FUNCTION_OPEN_CONVERSATION_TRANSLATION =
            "${FUNCTION_PREFIX}openConversationTranslation"
        const val FUNCTION_OPEN_SIMULTANEOUS_INTERPRETATION =
            "${FUNCTION_PREFIX}openSimultaneousInterpretation"
        const val FUNCTION_OPEN_TEXT_TRANSLATION = "${FUNCTION_PREFIX}openTextTranslation"

        fun invokeNoArgs(target: Any, methodName: String): Any? = runCatching {
            target.javaClass.getMethod(methodName).invoke(target)
        }.getOrNull()

        fun invokeString(target: Any, methodName: String): String =
            invokeNoArgs(target, methodName)?.toString().orEmpty()

        fun invokeNumber(target: Any, methodName: String): Number? =
            invokeNoArgs(target, methodName) as? Number
    }
}
