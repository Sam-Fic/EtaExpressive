package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.auth.CodexOAuth
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.repository.ProviderCredentialRepository
import java.io.IOException
import kotlin.reflect.KClass
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 凭据仅在请求发送前解析；401 恢复止于响应头，不重放已经开始的模型流。 */
internal object AuthorizedProviderHttp {
    fun <T> execute(
        client: OkHttpClient,
        providerId: String,
        authMode: String,
        request: Request,
        controller: AgentRunController,
        accessToken: (String, String, AgentRunController, String?) -> String =
            { id, mode, run, rejected -> ProviderCredentialRepository.accessToken(id, mode, run, rejected) },
        consume: (Response) -> T,
    ): T {
        val profile = ProviderConnectionProfile.forAuthMode(authMode)
        profile?.requireRequestTarget(request.url)
        val httpClient = if (profile == null) client else client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        var rejectedToken: String? = null
        var refreshed = false
        while (true) {
            controller.throwIfCancelled()
            val token = profile?.let { accessToken(providerId, authMode, controller, rejectedToken) }
            val authorized = if (token == null) request else request.newBuilder()
                .header("Authorization", "Bearer $token")
                .apply {
                    if (profile == ProviderConnectionProfile.OPENAI_CODEX) {
                        header("ChatGPT-Account-Id", CodexOAuth.accountId(token))
                    }
                }
                .build()
            val call = httpClient.newCall(authorized)
            val binding = controller.register(cancel = call::cancel)
            try {
                controller.throwIfCancelled()
                call.execute().use { response ->
                    controller.throwIfCancelled()
                    if (profile != null && response.code == 401 && !refreshed) {
                        rejectedToken = token
                        refreshed = true
                    } else {
                        if (profile != null && response.code == 401) {
                            val failure = AgentModelFailure.http(response.code, response.peekBody(16_384).string())
                            if (failure.code != "HTTP_401") throw failure
                            throw AgentModelFailure(
                                "OAUTH_UNAUTHORIZED", false, "订阅登录已失效，请重新登录服务商账户",
                            )
                        }
                        return consume(response)
                    }
                }
            } catch (failure: Throwable) {
                controller.throwIfCancelled()
                throw failure
            } finally {
                binding.close()
            }
        }
    }

    /**
     * SSE 收集器需要自行持有 Call 生命周期，因此这里以 Call.Factory 形式提供同样的
     * 凭据注入与一次性 401 恢复；流一旦开始输出就不会被重放。
     */
    fun callFactory(
        client: OkHttpClient,
        providerId: String,
        authMode: String,
        controller: AgentRunController,
        accessToken: (String, String, AgentRunController, String?) -> String =
            { id, mode, run, rejected -> ProviderCredentialRepository.accessToken(id, mode, run, rejected) },
    ): Call.Factory {
        val profile = ProviderConnectionProfile.forAuthMode(authMode) ?: return client
        val httpClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        return Call.Factory { request ->
            AuthorizedCall(httpClient, profile, providerId, authMode, controller, request, accessToken)
        }
    }

    private class AuthorizedCall(
        private val client: OkHttpClient,
        private val profile: ProviderConnectionProfile,
        private val providerId: String,
        private val authMode: String,
        private val controller: AgentRunController,
        private val original: Request,
        private val accessToken: (String, String, AgentRunController, String?) -> String,
    ) : Call {
        private val lock = Any()
        private val pendingEventListeners = mutableListOf<EventListener>()
        private var current: Call? = null
        private var currentToken: String? = null
        private var executed = false
        private var canceled = false

        override fun request(): Request = synchronized(lock) { current?.request() } ?: original

        override fun execute(): Response {
            synchronized(lock) {
                check(!executed) { "Already Executed" }
                executed = true
            }
            controller.throwIfCancelled()
            var rejected: String? = null
            while (true) {
                val call = prepare(rejected)
                val binding = controller.register(cancel = call::cancel)
                try {
                    val response = call.execute()
                    if (response.code == 401 && rejected == null) {
                        rejected = currentToken
                        response.close()
                        continue
                    }
                    return response
                } finally {
                    binding.close()
                }
            }
        }

        override fun enqueue(responseCallback: Callback) {
            synchronized(lock) {
                check(!executed) { "Already Executed" }
                executed = true
            }
            enqueueAttempt(responseCallback, rejected = null)
        }

        private fun enqueueAttempt(responseCallback: Callback, rejected: String?) {
            controller.throwIfCancelled()
            val call = prepare(rejected)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = responseCallback.onFailure(call, e)

                override fun onResponse(call: Call, response: Response) {
                    if (response.code == 401 && rejected == null) {
                        response.close()
                        enqueueAttempt(responseCallback, currentToken)
                        return
                    }
                    responseCallback.onResponse(call, response)
                }
            })
        }

        private fun prepare(rejected: String?): Call {
            controller.throwIfCancelled()
            profile.requireRequestTarget(original.url)
            val token = accessToken(providerId, authMode, controller, rejected)
            val authorized = original.newBuilder()
                .header("Authorization", "Bearer $token")
                .apply {
                    if (profile == ProviderConnectionProfile.OPENAI_CODEX) {
                        header("ChatGPT-Account-Id", CodexOAuth.accountId(token))
                    }
                }
                .build()
            val call = client.newCall(authorized)
            synchronized(lock) {
                current = call
                currentToken = token
                // 排队的监听按注册顺序补；补完就清掉，避免重试时重复注册到新 call 上。
                pendingEventListeners.forEach(call::addEventListener)
                pendingEventListeners.clear()
            }
            return call
        }

        override fun cancel() {
            synchronized(lock) {
                canceled = true
                current?.cancel()
            }
        }

        override fun isExecuted(): Boolean = synchronized(lock) { executed }

        override fun isCanceled(): Boolean = synchronized(lock) { canceled }

        // OkHttp 5 把 Timeout 从 okhttp3 移到了 okio 包，返回类型跟着变。
        // 还没有底层 call 时给 NONE：它是官方的“永不超时”实例，不是自己 new 出来的空壳。
        override fun timeout(): okio.Timeout =
            synchronized(lock) { current?.timeout() } ?: okio.Timeout.NONE

        // OkHttp 5 的 Call 新增了这组委托（4 个 tag 重载 + 事件监听器），都要透传给真正的 call。
        // 请求发出前还没有 current，查询退回原请求：Call 的 tag 本来就由 Request 的 tag 播种。
        override fun <T : Any> tag(type: KClass<T>): T? =
            synchronized(lock) { current?.tag(type) } ?: original.tag(type)

        override fun <T> tag(type: Class<out T>): T? =
            synchronized(lock) { current?.tag(type) } ?: original.tag(type)

        // compute 重载在 Request 上没有对应 API，无 current 时按官方语义自己算一份：
        // 算出来的不写回 Request（Call 的 tag 集只增不减、且不反向污染 Request），
        // 只保证本次调用拿到同一个值。
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
            synchronized(lock) { current?.tag(type, computeIfAbsent) } ?: original.tag(type) ?: computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
            synchronized(lock) { current?.tag(type, computeIfAbsent) } ?: original.tag(type) ?: computeIfAbsent()

        // 监听器必须在 execute() 之前注册（OkHttp 的正常用法），那时还没有底层 call，
        // 所以先排队，prepare 造出真 call 时再补上——直接转给 current 会静默丢掉监听。
        override fun addEventListener(eventListener: EventListener) {
            synchronized(lock) {
                val call = current
                if (call == null) pendingEventListeners += eventListener else call.addEventListener(eventListener)
            }
        }

        public override fun clone(): Call = AuthorizedCall(
            client, profile, providerId, authMode, controller, original, accessToken,
        )
    }
}
