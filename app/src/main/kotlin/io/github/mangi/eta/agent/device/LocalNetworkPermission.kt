package io.github.mangi.eta.agent.device

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * 本地网络（LAN）访问权限的判定。
 *
 * ## 这个闸门有两条路，必须分开看
 *
 * Android 17（API 37）起局域网访问是运行时权限 `ACCESS_LOCAL_NETWORK`。它在本项目上
 * 表现出两种完全不同的失败形态，混为一谈会把用户引到错误的排查方向：
 *
 * 1. **尚未授予**（[AccessState.NOT_GRANTED]）——正常的运行时权限，`requestPermissions`
 *    能弹出系统对话框，用户点一下就好。targetSdk 提到 37 之后，刚升级安装时就处于这个状态。
 *
 * 2. **已被系统拒绝**（[AccessState.DENIED]）——权限位写着 `granted=true`，但同名
 *    app-op 是 `ignore`。真机实测（小米 2304FPN6DC / Android 17 / 当时 targetSdk 36，
 *    2026-11-01）：这种状态下**系统权限页里根本没有「本地网络」这一项**，用户无从自助；
 *    而发往 `192.168.x.x` 的包在**本机协议栈里被静默丢弃**——socket 永远停在 `SYN-SENT`，
 *    tcpdump 在线路上一个包都抓不到，连 iptables 都没有任何丢弃计数
 *    （包在 conntrack 之后、驱动之前就被吞了）。用户看到的只是「连接超时」，
 *    与「服务器不回应」完全无法区分。
 *
 * 同一台手机上的浏览器（另一个 uid）、adb shell（uid 2000）都正常，
 * 于是很容易得出「网络没问题、是 App 写错了」的结论——方向对了一半：
 * 网络确实没问题，但拦人的是系统，而且只有本应用被拦。
 *
 * 所以判定必须看 **app-op + 权限位两者**，不能只看其中一个。
 */
internal object LocalNetworkPermission {
    /**
     * 这个权限本身。targetSdk ≥ 37 后清单里必须有声明，否则请求会直接被系统拒掉。
     * 常量值是 `android.permission.ACCESS_LOCAL_NETWORK`，编译期常量，老设备上引用安全。
     */
    const val PERMISSION: String = Manifest.permission.ACCESS_LOCAL_NETWORK

    enum class AccessState {
        /** 系统版本低于 37，这个闸门根本不存在。 */
        NOT_REQUIRED,

        /** 明确放行。 */
        GRANTED,

        /** 权限位还没授予：正常运行时权限，申请能弹系统对话框。 */
        NOT_GRANTED,

        /**
         * 权限位已授予、但 app-op 被系统置为拒绝。
         *
         * 这种情况下局域网连接会被静默丢弃，而且**系统设置里没有对应的开关**，
         * 应用内也无法自行修改 app-op（`AppOpsManager.setMode` 需要系统签名）。
         */
        DENIED,

        /** 读不到（老系统 / op 名对不上）。调用方不要据此下结论。 */
        UNKNOWN,
    }

    /**
     * 内部 op 名。SDK 37 的 `AppOpsManager` 里**没有**这个常量（只导出了权限常量），
     * 所以只能写字面量；已用 `cmd appops get --uid … android:access_local_network`
     * 在真机上验证过它就是那个 op。
     */
    private const val OP_ACCESS_LOCAL_NETWORK = "android:access_local_network"

    fun accessState(context: Context): AccessState {
        if (Build.VERSION.SDK_INT < 37) return AccessState.NOT_REQUIRED

        val granted = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return AccessState.NOT_GRANTED

        val mode = runCatching {
            context.getSystemService(AppOpsManager::class.java)
                ?.unsafeCheckOpNoThrow(
                    OP_ACCESS_LOCAL_NETWORK,
                    Process.myUid(),
                    context.packageName,
                )
        }.getOrNull()

        return when (mode) {
            AppOpsManager.MODE_ALLOWED -> AccessState.GRANTED
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> AccessState.DENIED
            // MODE_DEFAULT 的语义就是「按权限位来」，权限已授予即放行。
            AppOpsManager.MODE_DEFAULT -> AccessState.GRANTED
            // 读不到 op：不能咬着说被拦，但权限确实在手，按放行处理。
            null -> AccessState.UNKNOWN
            else -> AccessState.GRANTED
        }
    }

    /**
     * 这个状态能不能靠「应用内申请」解决。
     *
     * 只有 [AccessState.NOT_GRANTED] 可以：那是普通运行时权限。
     * [AccessState.DENIED] 是 app-op 层的拒绝，`requestPermissions` 会因为权限位已授予
     * 而直接回调 granted、连对话框都不弹——所以 UI 上绝不能给它一个「授权」按钮，
     * 那会让用户以为点过了就好了。
     */
    fun canRequest(state: AccessState): Boolean = state == AccessState.NOT_GRANTED

    /**
     * 这个状态会不会让局域网连接直接失败。
     *
     * 两种状态都会：[NOT_GRANTED] 是还没授权、[DENIED] 是系统 app-op 拦着。
     * 判断「连不上是不是权限的锅」时只问这一个函数，别各自列 `when`——
     * 漏掉一种就会把责任推给服务器。
     */
    fun blocksConnection(state: AccessState): Boolean =
        state == AccessState.NOT_GRANTED || state == AccessState.DENIED

    /**
     * 这次连接的目标是不是「本地网络」。
     *
     * 只用来决定要不要把超时归因到权限上：公网地址被拦跟这个权限无关，
     * 硬套上去就是又一次替用户瞎猜。
     */
    fun isLanAddress(host: String): Boolean {
        val literal = host.trim().removePrefix("[").removeSuffix("]")
        val parts = literal.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val (a, b) = octets
        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }
}
