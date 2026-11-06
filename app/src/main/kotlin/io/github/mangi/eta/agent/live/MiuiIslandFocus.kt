package io.github.mangi.eta.agent.live

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import io.github.mangi.eta.R
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 小米超级岛 / 焦点通知（miui.focus）桥接参数。
 *
 * 协议与字段值取自 Number444 fork 在小米 14 Ultra（HyperOS 3 / Android 16）上的全链路真机
 * 测定（T1–T35，见其 docs/ISLAND-EXPANSION-INVESTIGATION.md）：
 * - 上岛三件套 = 焦点参数 + ongoing + requestPromotedOngoing + shortCriticalText，任意重要性
 *   频道均可上岛为胶囊；其中纯 LiveUpdate（无焦点参数）在 Android 16+ 已可桥接上岛（T30），
 *   所以执行卡只在 Android 16 以下的旧版焦点协议路径注入本类参数；
 * - 「展开」只能跟着新 id 首发 alert 走（同 id 更新永不展开），且需频道在系统设置里开启
 *   「悬浮通知」——完成态的岛展开见 [AgentCompletionNotifier] 的岛终态通道；
 * - 胶囊状态文案按官方规范 ≤4 个中文字，由调用方用既有 shortText 类文案保证。
 *
 * 非小米设备全程不注入，退化为普通通知。
 */
internal object MiuiIslandFocus {
    /** 焦点图片在 extras 与 param JSON 里必须同名的 key（MiShare 真机工作样本）。 */
    const val PIC_KEY = "miui.focus.pic_eta_island"

    /** 探测是跨进程查询，串行后台队列承载；单线程即可（频率 = 应用启动 + 每次 run 开始）。 */
    private val probeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "miui-focus-probe").apply { isDaemon = true }
    }

    @Volatile private var legacyFocusProtocol: Boolean? = null

    /**
     * 官方 param_v2 模版 1（大岛 A 区图文组件 1 + B 区空，小岛仅图标，安静胶囊不自动展开）。
     *
     * 字段值锚定真机工作样本（MiShare，Number444 fork 测定）：baseInfo.type=2 与模板库 PDF
     * 的推荐搭配（文本组件1=type1 + large 识别图）不同，能用勿改；timeout/islandTimeout/
     * expandedTime 的精确语义官方文档未解释，同为未验证样本值。
     */
    fun buildFocusParam(text: String, content: String, packageName: String, notificationId: Int): String =
        JSONObject().apply {
            put("param_v2", JSONObject().apply {
                put("protocol", 1)
                put("business", "agent_task")
                // updatable=true 时 notifyId 必填，缺了系统直接按普通通知处理
                put("notifyId", packageName + notificationId)
                put("islandFirstFloat", false)
                put("enableFloat", false)
                put("updatable", true)
                put("reopen", "reopen")
                put("filterWhenNoPermission", false)
                put("timeout", 720)
                // OS2 状态栏焦点信息
                put("ticker", text)
                put("tickerPic", PIC_KEY)
                // 息屏显示
                put("aodTitle", text)
                // 焦点通知内容（官方示例：type=2 + colorTitle）
                put("baseInfo", JSONObject().apply {
                    put("type", 2)
                    put("title", text)
                    put("content", content)
                    put("colorTitle", "#3482FF")
                })
                put("param_island", JSONObject().apply {
                    put("islandProperty", 1)
                    put("islandPriority", 1)
                    put("islandOrder", true)
                    put("islandTimeout", 10)
                    put("expandedTime", 5)
                    put("bigIslandArea", JSONObject().apply {
                        put("imageTextInfoLeft", JSONObject().apply {
                            put("type", 1)
                            put("picInfo", JSONObject().apply {
                                put("type", 1)
                                put("pic", PIC_KEY)
                            })
                            put("textInfo", JSONObject().apply {
                                put("title", text)
                                put("content", content)
                                put("narrowFont", false)
                                put("showHighlightColor", false)
                            })
                        })
                    })
                    put("smallIslandArea", JSONObject().apply {
                        put("picInfo", JSONObject().apply {
                            put("type", 1)
                            put("pic", PIC_KEY)
                        })
                    })
                })
            })
        }.toString()

    /** 焦点图片 extras；largeIcon 为空时退回应用小图标，保证小岛区域始终有图。 */
    fun buildFocusPics(context: Context, largeIcon: Bitmap?): Bundle {
        val icon = largeIcon?.let(Icon::createWithBitmap)
            ?: Icon.createWithResource(context, R.drawable.ic_notification)
        return Bundle().apply { putParcelable(PIC_KEY, icon) }
    }

    /** 小米系机型判定：超级岛是小米私有形态，其他厂商（ColorOS 流体云等）只走 promoted 通道。 */
    fun isXiaomiDevice(): Boolean =
        Build.BRAND.contains("xiaomi", ignoreCase = true) ||
            Build.BRAND.contains("redmi", ignoreCase = true) ||
            Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)

    /**
     * 执行卡焦点参数是否适用：仅 Android 16 以下（16+ 由 promoted-ongoing 桥接，无需私有参数）
     * 且旧版焦点通知协议已启用。结果取进程缓存——探测是跨进程查询（耗时），禁止在主线程同步等。
     */
    fun paramsApplicable(): Boolean =
        isXiaomiDevice() && Build.VERSION.SDK_INT < 36 && cachedLegacyFocusProtocol() == true

    /** 岛终态通道是否可用：Android 16+ 小米走 promoted 桥接即可，旧版需焦点协议已启用。 */
    fun islandTerminalSupported(): Boolean =
        isXiaomiDevice() && (Build.VERSION.SDK_INT >= 36 || cachedLegacyFocusProtocol() == true)

    /**
     * 后台重探旧版焦点协议：应用启动（EtaApp）预热一次，此后每次 run 开始重探——
     * 用户可能随时在系统设置里开关「焦点通知」权限，进程内不能久缓存探测结果。
     */
    fun warmUpProbeAsync(context: Context) {
        if (Build.VERSION.SDK_INT >= 36 || !isXiaomiDevice()) return
        val appContext = context.applicationContext
        probeExecutor.execute { legacyFocusProtocol = probeFocusProtocol(appContext) }
    }

    /**
     * 桌面图标位图（跟随 [io.github.mangi.eta.ui.LauncherIconSync] 的动态图标）；
     * 供岛卡图片区使用，官方建议 ≥88px，失败返回 null 由调用方退回小图标。
     */
    fun launchIconBitmap(context: Context): Bitmap? {
        val name = io.github.mangi.eta.ui.LauncherIconSync.currentIconResName(context) ?: return null
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (id == 0) return null
        val drawable = context.getDrawable(id) ?: return null
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }

    /** 探测结果缓存；null = 尚未探测完成。 */
    private fun cachedLegacyFocusProtocol(): Boolean? = legacyFocusProtocol

    /** 系统已启用焦点通知协议（1=OS1 2=OS2 3=OS3）且本包已被授予焦点权限（官方查询接口）。 */
    private fun probeFocusProtocol(context: Context): Boolean = runCatching {
        val focusProtocol = Settings.System.getInt(
            context.contentResolver,
            "notification_focus_protocol",
            0,
        )
        if (focusProtocol < 1) return@runCatching false
        val uri = Uri.parse("content://miui.statusbar.notification.public")
        val extras = Bundle().apply { putString("package", context.packageName) }
        val result = context.contentResolver.call(uri, "canShowFocus", null, extras)
        result?.getBoolean("canShowFocus", false) == true
    }.getOrDefault(false)
}
