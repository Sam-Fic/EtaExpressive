package io.github.mangi.eta.hook.system

import android.hardware.display.VirtualDisplayConfig
import android.os.Binder
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger

/**
 * 在 system_server 为 Eta 自身的后台虚拟屏授予受信任显示标志与任务迁移权限。
 *
 * 严格限制调用方包名与虚拟屏名称（EtaAgentVirtualDisplay），不对其他应用开放权限。
 */
internal object VirtualDisplaySystemHooks {

    private const val DMS_CLASS = "com.android.server.display.DisplayManagerService"
    private const val DMS_BINDER_CLASS = "com.android.server.display.DisplayManagerService\$BinderService"
    private const val ATMS_CLASS = "com.android.server.wm.ActivityTaskManagerService"

    private val activeEtaDisplayCreation = ThreadLocal.withInitial { false }

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "VirtualDisplay")
        return hooks.install {
            installDisplayManagerHooks(hooks, classLoader)
            installTaskMoveHooks(hooks, classLoader)
        }
    }

    private fun installDisplayManagerHooks(hooks: HookRegistrar, classLoader: ClassLoader) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        val dmsClass = HookSupport.findClassOrNull(classLoader, DMS_CLASS)
        val binderClass = HookSupport.findClassOrNull(classLoader, DMS_BINDER_CLASS)
        val createMethod = binderClass?.let { clazz ->
            HookSupport.findDeclaredMethods(clazz, makeAccessible = true) { method ->
                method.name == "createVirtualDisplay" &&
                    method.parameterCount >= 4 &&
                    method.parameterTypes[0] == VirtualDisplayConfig::class.java &&
                    method.parameterTypes[3] == String::class.java
            }.firstOrNull()
        }
        if (dmsClass == null || createMethod == null) {
            hooks.missing(
                id = "system.virtual-display-create",
                description = "DisplayManagerService.createVirtualDisplay",
                detail = "未找到 DisplayManagerService\$BinderService.createVirtualDisplay",
            )
            return
        }

        hooks.intercept(
            id = "system.virtual-display-create",
            executable = createMethod,
            description = "DisplayManagerService\$BinderService.createVirtualDisplay",
        ) { chain ->
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                !Prefs.isEnabled(Prefs.Keys.AGENT_VIRTUAL_DISPLAY_ENABLED)
            ) {
                return@intercept chain.proceed()
            }
            val args = chain.args.toMutableList()
            val config = args.getOrNull(0) as? VirtualDisplayConfig
            val packageName = args.getOrNull(3) as? String
            val upgraded = if (packageName == ModuleConfig.ETA_PACKAGE && config != null) {
                upgradeVirtualDisplayConfig(config)
            } else {
                null
            } ?: return@intercept chain.proceed()
            args[0] = upgraded
            activeEtaDisplayCreation.set(true)
            try {
                chain.proceed(args.toTypedArray())
            } finally {
                activeEtaDisplayCreation.set(false)
            }
        }

        val checkPermMethod = HookSupport.findDeclaredMethods(dmsClass, makeAccessible = true) { method ->
            method.name == "checkCallingPermission" &&
                method.parameterCount == 2 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1] == String::class.java &&
                method.returnType == Boolean::class.javaPrimitiveType
        }.firstOrNull()

        if (checkPermMethod != null) {
            hooks.intercept(
                id = "system.virtual-display-permission",
                executable = checkPermMethod,
                description = "DisplayManagerService.checkCallingPermission",
            ) { chain ->
                if (activeEtaDisplayCreation.get() == true) {
                    true
                } else {
                    chain.proceed()
                }
            }
        }
    }

    @android.annotation.SuppressLint("WrongConstant")
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun upgradeVirtualDisplayConfig(config: VirtualDisplayConfig): VirtualDisplayConfig? {
        if (config.name != CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME) return null
        return runCatching {
            VirtualDisplayConfig.Builder(
                config.name,
                config.width,
                config.height,
                config.densityDpi,
            ).apply {
                config.surface?.let(::setSurface)
                setFlags(config.flags or CanvasRoutingPolicy.TRUSTED_INTERACTIVE_FLAGS)
                if (config.requestedRefreshRate > 0f) {
                    setRequestedRefreshRate(config.requestedRefreshRate)
                }
            }.build()
        }.getOrDefault(config)
    }

    private fun installTaskMoveHooks(hooks: HookRegistrar, classLoader: ClassLoader) {
        val atmsClass = HookSupport.findClassOrNull(classLoader, ATMS_CLASS) ?: return
        val moveMethod = HookSupport.findMethod(
            atmsClass,
            "moveRootTaskToDisplay",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
        ) ?: return

        hooks.intercept(
            id = "system.virtual-display-move-task",
            executable = moveMethod,
            description = "ActivityTaskManagerService.moveRootTaskToDisplay",
        ) { chain ->
            val target = chain.thisObject
            val context = SystemServerContextResolver.resolve(target)
            val callingUid = Binder.getCallingUid()
            val isEtaCaller = context != null && runCatching {
                context.packageManager.getPackagesForUid(callingUid)
                    ?.any { it == ModuleConfig.ETA_PACKAGE } == true
            }.getOrDefault(false)
            if (!isEtaCaller) {
                return@intercept chain.proceed()
            }
            val token = Binder.clearCallingIdentity()
            try {
                chain.proceed()
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
    }
}
