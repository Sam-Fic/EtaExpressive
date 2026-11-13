package io.github.mangi.eta.hook.hyperos

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.hook.system.AssistantBinding
import io.github.mangi.eta.hook.system.AssistantManager
import io.github.mangi.eta.hook.system.assistantBindingFor

internal object HyperOsPowerHooks {
    /** 去重窗口：同一次长按可能同时命中 triggerFunction 与 launchVoiceAssistant 两条路径。 */
    @Volatile
    private var lastInterceptUptime = 0L

    fun install(module: XposedModule, logger: ModuleLogger, loader: ClassLoader): HookInstallation {
        val hooks = HookRegistrar(module, logger, "HyperOsPower")
        return hooks.install {
            val type = HookSupport.findClassOrNull(loader, ModuleConfig.MIUI_SHORTCUT_ACTIONS_UTILS_CLASS)
            if (type == null) {
                skipped("hyperos.power-shortcut", "ShortCutActionsUtils", "当前系统未提供 HyperOS 快捷动作分发器")
                return@install
            }
            val contextField = HookSupport.findField(type, "mContext")
            if (contextField == null || !Context::class.java.isAssignableFrom(contextField.type)) {
                missing("hyperos.power-shortcut", "ShortCutActionsUtils.mContext", "HyperOS: 快捷动作分发器缺少 Context")
                return@install
            }
            val parameters = arrayOf(String::class.java, String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType!!)
            for (signature in listOf(parameters, parameters + String::class.java)) {
                val method = HookSupport.findMethod(type, "triggerFunction", *signature)
                val id = "hyperos.power-shortcut-${signature.size}"
                if (method == null || method.returnType != Boolean::class.javaPrimitiveType) {
                    missing(id, "ShortCutActionsUtils.triggerFunction/${signature.size}", "HyperOS: 快捷动作分发签名 ${signature.size} 不匹配")
                    continue
                }
                intercept(id, method, "ShortCutActionsUtils.triggerFunction/${signature.size}") { chain ->
                    val function = chain.getArg(0) as? String
                    val shortcut = chain.getArg(1) as? String
                    // 同时匹配功能与来源，不能把关机菜单、SOS 或其他快捷动作改成助理。
                    if (!HyperOsPowerPolicy.isAssistantShortcut(function, shortcut)) {
                        return@intercept chain.proceed()
                    }
                    val context = contextField.get(chain.getThisObject()) as? Context
                    val configuredTarget = Prefs.powerAssistantTarget()
                    // OEM 语义在 HyperOS 上收敛：系统默认助理已被校正为 Eta/Gemini 时按该目标路由。
                    val target = if (context != null) {
                        resolveHyperOsAssistantTarget(context, configuredTarget)
                    } else {
                        configuredTarget
                    }
                    if (!shouldInterceptHyperOsVoiceAssistant(function, shortcut, target)) {
                        return@intercept chain.proceed()
                    }
                    if (context == null) {
                        hooks.logger.warnThrottled("hyperos_shortcut_missing_context") {
                            "HyperOS 电源键路由缺少 mContext，回退原逻辑"
                        }
                        return@intercept chain.proceed()
                    }
                    val binding = assistantBindingFor(target) ?: return@intercept chain.proceed()
                    if (!HookSupport.isPackageInstalled(context, binding.packageName)) {
                        hooks.logger.warnThrottled("hyperos_shortcut_${target.persistedValue}_missing") {
                            "HyperOS 电源键目标 ${binding.displayName} 未安装，阻止回退超级小爱"
                        }
                        // 失败不回退：ROM 的默认路径会把电源键交给超级小爱。
                        return@intercept false
                    }

                    val now = SystemClock.uptimeMillis()
                    if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
                        hooks.logger.debug { "HyperOSShortcut: 命中去重窗口，吞掉重复触发" }
                        return@intercept true
                    }

                    val launched = AssistantManager.showAssistantSession(
                        context = context,
                        target = target,
                        logger = hooks.logger,
                        source = "HyperOSShortcut",
                        logFailures = false,
                    ) || tryStartHyperOsAssistantActivity(
                        context = context,
                        target = target,
                        binding = binding,
                        logger = hooks.logger,
                    )
                    if (!launched) {
                        hooks.logger.warnThrottled("hyperos_shortcut_${target.persistedValue}_launch_failed") {
                            "HyperOS 电源键无法启动 ${binding.displayName}，阻止回退超级小爱"
                        }
                        return@intercept false
                    }

                    markLaunchSuccess(now)
                    performHyperOsShortcutHaptic(
                        shortcutActions = chain.getThisObject(),
                        function = function.orEmpty(),
                        shortcut = shortcut.orEmpty(),
                        requested = chain.getArg(3) as? Boolean ?: false,
                        // 4 参签名没有 effectKey 参数，只有 5 参签名带。
                        effectKey = if (signature.size >= 5) chain.getArg(4) as? String else null,
                        logger = hooks.logger,
                    )
                    hooks.logger.debug { "HyperOSShortcut: 已把长按电源键路由到 ${binding.displayName}" }
                    true
                }
            }

            // 部分 HyperOS 的电源键路径会穿过 triggerFunction 的优化调用，上面的精确入口
            // 收不到回调；launchVoiceAssistant 是真正写死并启动小爱的最终出口。
            // 仅当用户已选择 Eta/Gemini 时接管，OEM 模式仍完整保留原行为。
            val launchVoiceAssistantMethod = HookSupport.findMethod(
                type,
                "launchVoiceAssistant",
                String::class.java,
                Bundle::class.java,
            )
            if (launchVoiceAssistantMethod == null) {
                missing(
                    id = "hyperos.voice-launch",
                    description = "ShortCutActionsUtils.launchVoiceAssistant",
                    detail = "HyperOS: 未找到 launchVoiceAssistant(String,Bundle) 终态出口",
                )
                return@install
            }
            intercept(
                id = "hyperos.voice-launch",
                executable = launchVoiceAssistantMethod,
                description = "ShortCutActionsUtils.launchVoiceAssistant",
            ) { chain ->
                val context = HookSupport.getFieldValue(chain.getThisObject(), "mContext") as? Context
                    ?: return@intercept chain.proceed()
                val target = resolveHyperOsAssistantTarget(context, Prefs.powerAssistantTarget())
                if (!shouldInterceptHyperOsVoiceAssistantLaunch(target)) {
                    return@intercept chain.proceed()
                }
                val binding = assistantBindingFor(target) ?: return@intercept chain.proceed()
                if (!HookSupport.isPackageInstalled(context, binding.packageName)) {
                    hooks.logger.warnThrottled("hyperos_voice_launch_${target.persistedValue}_missing") {
                        "HyperOS 助理目标 ${binding.displayName} 未安装，阻止回退超级小爱"
                    }
                    return@intercept false
                }

                val now = SystemClock.uptimeMillis()
                if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
                    hooks.logger.debug { "HyperOSVoiceLaunch: 命中去重窗口，吞掉重复触发" }
                    return@intercept true
                }
                val launched = AssistantManager.showAssistantSession(
                    context = context,
                    target = target,
                    logger = hooks.logger,
                    source = "HyperOSVoiceLaunch",
                    logFailures = false,
                ) || tryStartHyperOsAssistantActivity(
                    context = context,
                    target = target,
                    binding = binding,
                    logger = hooks.logger,
                )
                if (!launched) {
                    hooks.logger.warnThrottled("hyperos_voice_launch_${target.persistedValue}_failed") {
                        "HyperOS 助理出口无法启动 ${binding.displayName}，阻止回退超级小爱"
                    }
                    return@intercept false
                }

                markLaunchSuccess(now)
                hooks.logger.debug {
                    "HyperOSVoiceLaunch: 已把小爱启动出口路由到 ${binding.displayName}, " +
                        "shortcut=${chain.getArg(0) as? String}"
                }
                true
            }
        }
    }

    private fun resolveHyperOsAssistantTarget(
        context: Context,
        configuredTarget: PowerAssistantTarget,
    ): PowerAssistantTarget {
        if (configuredTarget != PowerAssistantTarget.OEM) return configuredTarget
        return when {
            AssistantManager.isAssistantConfigured(context, PowerAssistantTarget.ETA) ->
                PowerAssistantTarget.ETA
            AssistantManager.isAssistantConfigured(context, PowerAssistantTarget.GEMINI) ->
                PowerAssistantTarget.GEMINI
            else -> PowerAssistantTarget.OEM
        }
    }

    private fun tryStartHyperOsAssistantActivity(
        context: Context,
        target: PowerAssistantTarget,
        binding: AssistantBinding,
        logger: ModuleLogger,
    ): Boolean {
        val actions = when (target) {
            PowerAssistantTarget.OEM -> return false
            PowerAssistantTarget.ETA -> listOf(Intent.ACTION_ASSIST)
            PowerAssistantTarget.GEMINI -> listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND)
        }
        return actions.any { action ->
            val intent = Intent(action).apply {
                setPackage(binding.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (!runCatching { HookSupport.resolvesActivity(context, intent) }.getOrDefault(false)) {
                return@any false
            }
            runCatching {
                context.startActivity(intent)
                true
            }.getOrElse { throwable ->
                logger.warnThrottled("hyperos_shortcut_${target.persistedValue}_${action}_failed") {
                    "HyperOSShortcut: $action 启动 ${binding.displayName} 失败，" +
                        "type=${throwable.safeLogType()}"
                }
                false
            }
        }
    }

    /** 成功接管后补发原生震感，保持长按的物理反馈与未接管时一致。 */
    private fun performHyperOsShortcutHaptic(
        shortcutActions: Any,
        function: String,
        shortcut: String,
        requested: Boolean,
        effectKey: String?,
        logger: ModuleLogger,
    ) {
        if (!requested || effectKey.isNullOrBlank()) return
        val method = HookSupport.findMethod(
            shortcutActions.javaClass,
            "triggerHapticFeedback",
            Boolean::class.javaPrimitiveType!!,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
            String::class.java,
        ) ?: return
        runCatching {
            method.invoke(shortcutActions, true, shortcut, function, true, effectKey)
        }.onFailure { throwable ->
            logger.warnThrottled("hyperos_shortcut_haptic_failed") {
                "HyperOSShortcut: 原生震感补发失败，type=${throwable.safeLogType()}"
            }
        }
    }

    private fun markLaunchSuccess(uptime: Long) {
        lastInterceptUptime = uptime
    }

    internal fun shouldInterceptHyperOsVoiceAssistant(
        function: String?,
        shortcut: String?,
        target: PowerAssistantTarget,
    ): Boolean = target != PowerAssistantTarget.OEM &&
        HyperOsPowerPolicy.isAssistantShortcut(function, shortcut)

    internal fun shouldInterceptHyperOsVoiceAssistantLaunch(
        target: PowerAssistantTarget,
    ): Boolean = target != PowerAssistantTarget.OEM
}
