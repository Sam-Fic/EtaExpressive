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
 * 生产路径统一使用澎湃记模板 16（protocol 3）：旧模板 1 在 HyperOS 4 上会被
 * FocusPlugin 判定为「is not focus notification」，通知只剩普通卡或原生 Live Update。
 * 模板 16 结构逐字段取自 badnng/Hyper-pick-up-code SuperIslandHelper
 * （AGPL-3.0-only，合规登记见 docs/THIRD_PARTY_NOTICES.md）。
 *
 * 小米超级岛与官方 promoted ongoing 是互斥渲染路径：带 miui.focus 的通知不得再带
 * ProgressStyle / shortCriticalText / requestPromotedOngoing，否则 HyperOS 走 Live Update
 * 管线并忽略焦点参数。非小米设备不注入本类参数，由调用方选择原生通知或官方实时活动。
 */
internal object MiuiIslandFocus {
    /** 探测是跨进程查询，串行后台队列承载；单线程即可（频率 = 应用启动 + 每次 run 开始）。 */
    private val probeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "miui-focus-probe").apply { isDaemon = true }
    }

    @Volatile private var legacyFocusProtocol: Boolean? = null

    // ── 模板 16 岛参数 ────────────────────────────────────────────────

    /** 模板 16 的四个标准 pic key（参考 buildPicsBundle 原样）。 */
    const val PIC_PROFILE = "miui.focus.pic_profile"
    const val PIC_ISLAND = "miui.focus.pic_island"
    const val PIC_LARGE_V2 = "miui.focus.pic_large_v2"
    const val PIC_HINT_CONTENT_V2 = "miui.focus.pic_hint_content_v2"

    /** 模板 16 hintInfo 岛面动作按钮在 miui.focus.actions bundle 里的 key（参考原样）。 */
    const val ACT_HINT_V2 = "miui.focus.action_hint_v2"

    /**
     * 取码岛参数：模板 16 的取码投影。
     * ticker/aodTitle=「取码 xxx」、islandTitle=码值、iconTextInfo.content=品牌、
     * hintInfo.title=取餐提示、actionTitle=标记完成、targetPage=应用主页。
     */
    fun buildPickupFocusParam(
        ticker: String,
        islandTitle: String,
        iconTextContent: String,
        hintTitle: String,
        actionTitle: String,
        targetPage: String,
        palette: IslandPalette,
    ): String = buildTemplate16Param(
        business = "pickup",
        ticker = ticker,
        islandTitle = islandTitle,
        iconTextContent = iconTextContent,
        hintTitle = hintTitle,
        actionTitle = actionTitle,
        targetPage = targetPage,
        palette = palette,
    )

    /**
     * 普通任务岛参数：与取码共用同一模板 16 解析结构，仅替换业务标识与文案槽位。
     * 运行卡把阶段短文案放大小岛，把流式预览/应用名放提示区；「补充」是岛面主按钮，
     * 暂停与停止仍保留为标准 Notification.Action，供通知栏与支持多按钮的厂商渲染。
     */
    fun buildTaskFocusParam(
        ticker: String,
        islandTitle: String,
        iconTextContent: String,
        hintTitle: String,
        actionTitle: String,
        targetPage: String,
        palette: IslandPalette,
    ): String = buildTemplate16Param(
        business = "agent_task",
        ticker = ticker,
        islandTitle = islandTitle,
        iconTextContent = iconTextContent,
        hintTitle = hintTitle,
        actionTitle = actionTitle,
        targetPage = targetPage,
        palette = palette,
    )

    /**
     * 模板 16 唯一 JSON 实现：protocol 3、islandFirstFloat/enableFloat=true、
     * isShowNotification=true、islandProperty=2、四个标准 pic key、hintInfo 动作钮与
     * smallWindowInfo.targetPage 均保持参考实现字段值；business 只区分调用方业务语义。
     */
    private fun buildTemplate16Param(
        business: String,
        ticker: String,
        islandTitle: String,
        iconTextContent: String,
        hintTitle: String,
        actionTitle: String,
        targetPage: String,
        palette: IslandPalette,
    ): String = JSONObject().apply {
        put("param_v2", JSONObject().apply {
            put("protocol", 3)
            put("business", business)
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
                put("colorContent", palette.contentLightHex)
                put("colorContentDark", palette.contentDarkHex)
                put("colorContentBg", palette.containerHex)
                put("picContent", PIC_HINT_CONTENT_V2)
                put("actionInfo", JSONObject().apply {
                    put("action", ACT_HINT_V2)
                    put("actionTitle", actionTitle)
                    put("actionTitleColor", "#FFFFFF")
                    put("actionTitleColorDark", "#FFFFFF")
                    put("actionBgColor", palette.accentHex)
                    put("actionBgColorDark", palette.accentHex)
                })
            })
        })
    }.toString()

    /**
     * 模板 16 的 miui.focus.pics：四个标准 key 齐备（参考 buildPicsBundle 移植）——
     * 左侧新图文/岛面/提示区共用调用方给的内容图，右上角识别图形组件 1 放桌面应用图标。
     *
     * 返回的是参考样式**外层包装**（{"miui.focus.pics": 内层图集}）：调用方必须用
     * `Builder.addExtras(本返回值)` 合并（参考即 addExtras）；若用 extras.putBundle 挂
     * 会双层嵌套，systemui 读不到图集。岛面按钮的 [buildTemplate16Actions] 同规。
     */
    fun buildTemplate16Pics(context: Context, contentIcon: Icon?): Bundle {
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
     * 场景内容图：语义深色圆砖 + 同色板浅色图形。底色与提示卡 colorContentBg 同源，
     * 在岛面明暗两态都可读；108px 满足官方 ≥88px 建议。图形为 vector drawable
     * 离线渲染（通知路径无 Compose 组合），失败返回 null 由调用方退回桌面图标/小图标。
     */
    fun typeTileIcon(context: Context, glyphRes: Int, palette: IslandPalette): Icon? = runCatching {
        val size = 108
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.containerArgb }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        val glyph = context.getDrawable(glyphRes) ?: return@runCatching null
        glyph.mutate().setTint(palette.contentDarkArgb)
        val inset = (size * 0.18f).toInt()
        glyph.setBounds(inset, inset, size - inset, size - inset)
        glyph.draw(canvas)
        Icon.createWithBitmap(bitmap)
    }.getOrNull()

    /** 任务过程/完成岛共用的 AI 语义图，按运行/完成态换色，不退回桌面应用图标。 */
    fun taskTileIcon(context: Context, palette: IslandPalette): Icon? =
        typeTileIcon(context, R.drawable.ic_task_type_agent, palette)

    /**
     * 模板 16 的 miui.focus.actions：hintInfo 岛面主按钮（ACT_HINT_V2）。
     * 取码传标记完成广播，普通任务传补充输入动作。
     *
     * 返回**外层包装**（{"miui.focus.actions": 内层动作表}），必须配 `addExtras` 合并；
     * putBundle 双层嵌套 = 岛面按钮整颗不渲染（真机实证）。
     */
    fun buildTemplate16Actions(actionTitle: String, pendingIntent: PendingIntent): Bundle =
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
     * 小米超级岛是否可用：Android 16+ 使用模板 16，旧版需焦点通知协议已启用。
     * 结果取进程缓存——探测是跨进程查询（耗时），禁止在主线程同步等。
     */
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
