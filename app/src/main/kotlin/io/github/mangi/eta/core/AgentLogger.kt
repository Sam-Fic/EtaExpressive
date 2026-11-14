package io.github.mangi.eta.core

import android.util.Log

internal interface AgentLogger {
    /**
     * 记录仅用于开发期诊断的信息。
     *
     * supplier 只能构造诊断文本，不能承担程序正确性依赖的副作用；Release 构建会删除整次调用。
     */
    fun debug(message: () -> String)

    fun info(message: String)
    fun warn(message: String)
    fun error(message: String, throwable: Throwable? = null)
}

internal object AndroidAgentLogger : AgentLogger {
    private val logThrottle = LogThrottle()

    // 诊断日志不允许把异常抛给调用方：无 mock 的 JUnit 环境里 android.util.Log 会直接抛
    // RuntimeException（ProviderSecretCipher 的 Keystore 降级 warn 即因此炸穿测试），
    // 写 logcat 失败只吞掉，AppFileLogger 侧由自身 enabled 门禁兜底。
    private fun logcat(write: () -> Unit) {
        runCatching(write)
    }

    override fun debug(message: () -> String) {
        val text = message()
        logcat { Log.d(ModuleConfig.TAG, text) }
        AppFileLogger.debug(text)
    }

    override fun info(message: String) {
        logcat { Log.i(ModuleConfig.TAG, message) }
        AppFileLogger.info(message)
    }

    override fun warn(message: String) {
        logcat { Log.w(ModuleConfig.TAG, message) }
        AppFileLogger.warn(message)
    }

    fun warnThrottled(
        key: String,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS,
        message: () -> String
    ) {
        if (logThrottle.shouldLog("warn:$key", windowMs)) {
            warn(message())
        }
    }

    override fun error(message: String, throwable: Throwable?) {
        logcat {
            if (throwable == null) {
                Log.e(ModuleConfig.TAG, message)
            } else {
                Log.e(ModuleConfig.TAG, message, throwable)
            }
        }
        AppFileLogger.error(message, throwable)
    }

    fun errorThrottled(
        key: String,
        throwable: Throwable? = null,
        windowMs: Long = ModuleConfig.HOT_PATH_LOG_WINDOW_MS,
        message: () -> String
    ) {
        if (logThrottle.shouldLog("error:$key", windowMs)) {
            error(message(), throwable)
        }
    }
}
