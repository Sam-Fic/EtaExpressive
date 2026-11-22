package io.github.mangi.eta.agent.live

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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

    // ── 取码岛参数（澎湃记模板 16 移植）──────────────────────────────
    // 模板 1（buildFocusParam）是执行卡安静胶囊形态：protocol 1 + islandFirstFloat=false
    // （「别浮出来」）+ 非标准 pic key。HyperOS 4 的焦点解析器不把它渲染成岛——2026-11-22
    // 真机实证（id 1209 已发、FocusPlugin 判定 is not focus notification）整条通知退化成
    // 原生 Live Update 卡。取码提醒要的是「浮出来的岛」，改用澎湃记在岛设备上的真机定稿
    // 模板 16，逐字段移植（badnng/Hyper-pick-up-code SuperIslandHelper，AGPL-3.0-only，
    // 合规登记见 docs/THIRD_PARTY_NOTICES.md）。

    /** 模板 16 的四个标准 pic key（参考 buildPicsBundle 原样）。 */
    const val PIC_PROFILE = "miui.focus.pic_profile"
    const val PIC_ISLAND = "miui.focus.pic_island"
    const val PIC_LARGE_V2 = "miui.focus.pic_large_v2"
    const val PIC_HINT_CONTENT_V2 = "miui.focus.pic_hint_content_v2"

    /** 模板 16 hintInfo 岛面动作按钮在 miui.focus.actions bundle 里的 key（参考原样）。 */
    const val ACT_HINT_V2 = "miui.focus.action_hint_v2"

    /**
     * 取码岛参数：官方 param_v2 模板 16（新图文组件 + 识别图形组件 1 + 按钮组件 5），
     * 字段值全部锚定参考实现原样——business="pickup"、无 notifyId、showSmallIcon=false、
     * 岛面配色常量与 actionInfo 蓝底白字，不做「顺手优化」。与模板 1 的关键差异：
     * protocol 3、islandFirstFloat/enableFloat=true、isShowNotification=true、
     * islandProperty=2、四个标准 pic key、hintInfo 岛面动作钮、smallWindowInfo.targetPage。
     *
     * 文案槽位映射（参考 buildTemplateSixteenJson 实参 → 本仓）：
     * ticker/aodTitle=「取码 xxx」、islandTitle/iconTextInfo.title=码值本身（一眼看码）、
     * iconTextInfo.content=品牌（空退通用标题）、hintInfo.title=取餐提示（位置优先）、
     * actionTitle=标记完成、targetPage=应用主页。
     */
    fun buildPickupFocusParam(
        ticker: String,
        islandTitle: String,
        iconTextContent: String,
        hintTitle: String,
        actionTitle: String,
        targetPage: String,
    ): String = JSONObject().apply {
        put("param_v2", JSONObject().apply {
            put("protocol", 3)
            put("business", "pickup")
            put("updatable", true)
            put("ticker", ticker)
            put("enableFloat", true)
            put("showSmallIcon", false)
            put("isShowNotification", true)
            put("islandFirstFloat", true)
            put("aodTitle", ticker)
            put("aodPic", PIC_PROFILE)
            put("picInfo", JSONObject().apply {
                put("pic", PIC_LARGE_V2)
                put("type", 1)
            })
            put("smallWindowInfo", JSONObject().apply {
                put("targetPage", targetPage)
            })
            put("param_island", JSONObject().apply {
                put("islandProperty", 2)
                put("islandOrder", false)
                put("dismissIsland", false)
                put("needCloseAnimation", true)
                put("bigIslandArea", JSONObject().apply {
                    put("imageTextInfoLeft", JSONObject().apply {
                        put("type", 1)
                        put("picInfo", JSONObject().apply {
                            put("type", 1)
                            put("pic", PIC_ISLAND)
                            put("loop", false)
                            put("autoplay", false)
                            put("number", 0)
                        })
                    })
                    put("textInfo", JSONObject().apply {
                        put("frontTitle", "")
                        put("title", islandTitle)
                        put("content", "")
                        put("showHighlightColor", false)
                        put("narrowFont", false)
                    })
                })
                put("smallIslandArea", JSONObject().apply {
                    put("imageTextInfoRight", JSONObject().apply {
                        put("type", 6)
                        put("textInfo", JSONObject().apply {
                            put("title", islandTitle)
                            put("showHighlightColor", false)
                        })
                        put("picInfo", JSONObject().apply {
                            put("type", 1)
                            put("pic", PIC_ISLAND)
                        })
                    })
                })
            })
            put("iconTextInfo", JSONObject().apply {
                put("title", islandTitle)
                put("content", iconTextContent)
                put("colorTitle", "#111111")
                put("colorTitleDark", "#FFFFFF")
                put("colorContent", "#666666")
                put("colorContentDark", "#AAAAAA")
                put("animIconInfo", JSONObject().apply {
                    put("type", 3)
                    put("src", PIC_PROFILE)
                })
            })
            put("hintInfo", JSONObject().apply {
                put("type", 1)
                put("title", hintTitle)
                put("colorTitle", "#000000")
                put("colorTitleDark", "#FFFFFF")
                put("colorContent", "#FFB300")
                put("colorContentDark", "#FFC44D")
                put("colorContentBg", "#3A3325")
                put("picContent", PIC_HINT_CONTENT_V2)
                put("actionInfo", JSONObject().apply {
                    put("action", ACT_HINT_V2)
                    put("actionTitle", actionTitle)
                    put("actionTitleColor", "#FFFFFF")
                    put("actionTitleColorDark", "#FFFFFF")
                    put("actionBgColor", "#3482FF")
                    put("actionBgColorDark", "#3482FF")
                })
            })
        })
    }.toString()

    /**
     * 模板 16 的 miui.focus.pics：四个标准 key 齐备（参考 buildPicsBundle 移植）——
     * 左侧新图文/岛面/提示区共用调用方给的内容图（品类圆砖，缺图退应用小图标），右上角
     * 识别图形组件 1 按模板规范放桌面应用图标。
     *
     * 返回的是参考样式**外层包装**（{"miui.focus.pics": 内层图集}）：调用方必须用
     * `Builder.addExtras(本返回值)` 合并（参考即 addExtras）；若用 extras.putBundle 挂
     * 会双层嵌套，systemui 读不到图集。岛面按钮的 [buildPickupFocusActions] 同规。
     */
    fun buildPickupFocusPics(context: Context, contentIcon: Icon?): Bundle {
        val content = contentIcon ?: Icon.createWithResource(context, R.drawable.ic_notification)
        val appIcon = Icon.createWithResource(context, R.mipmap.ic_launcher)
        val pics = Bundle().apply {
            putParcelable(PIC_PROFILE, content)
            putParcelable(PIC_ISLAND, content)
            putParcelable(PIC_LARGE_V2, appIcon)
            putParcelable(PIC_HINT_CONTENT_V2, content)
        }
        return Bundle().apply { putBundle("miui.focus.pics", pics) }
    }

    /**
     * 品类内容图：暗底圆砖 + 琥珀品类图形。配色与模板 16 提示卡同源（colorContentBg
     * #3A3325 / colorContentDark #FFC44D），在岛面明暗两态都可读；108px 满足官方
     * ≥88px 建议。图形为 vector drawable 离线渲染（通知路径无 Compose 组合），
     * 失败返回 null 由调用方退回桌面图标/小图标。
     */
    fun typeTileIcon(context: Context, glyphRes: Int): Icon? = runCatching {
        val size = 108
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3325.toInt() }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        val glyph = context.getDrawable(glyphRes) ?: return@runCatching null
        glyph.mutate().setTint(0xFFFFC44D.toInt())
        val inset = (size * 0.18f).toInt()
        glyph.setBounds(inset, inset, size - inset, size - inset)
        glyph.draw(canvas)
        Icon.createWithBitmap(bitmap)
    }.getOrNull()

    /**
     * 模板 16 的 miui.focus.actions：hintInfo 岛面按钮（ACT_HINT_V2，标记完成广播）。
     * 参考真机定稿：岛通知本体不挂 Notification.Action，按钮只走这个 bundle。
     *
     * 返回**外层包装**（{"miui.focus.actions": 内层动作表}），必须配 `addExtras` 合并；
     * putBundle 双层嵌套 = 岛面按钮整颗不渲染（真机实证）。
     */
    fun buildPickupFocusActions(actionTitle: String, pendingIntent: PendingIntent): Bundle =
        Bundle().apply {
            putBundle(
                "miui.focus.actions",
                Bundle().apply {
                    putParcelable(
                        ACT_HINT_V2,
                        Notification.Action.Builder(null, actionTitle, pendingIntent).build(),
                    )
                },
            )
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
