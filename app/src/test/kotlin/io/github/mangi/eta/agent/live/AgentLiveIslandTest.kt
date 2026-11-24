package io.github.mangi.eta.agent.live

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.pickup.PickupCodeCompleteReceiver
import io.github.mangi.eta.agent.pickup.PickupCodeNotifier
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.config.LiveActivityStyle
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.MainActivity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.time.Duration
import kotlin.math.pow

/**
 * 小米超级岛统一使用 param_v2 模板 16；运行卡按通知方式在私有焦点与官方 promoted 间互斥分流。
 * 字段值锚定 badnng/Hyper-pick-up-code 的模板 16 真机工作样本；思维流预览截断规则。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLiveIslandTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Prefs.initLocal(context)
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        Prefs.initLocal(context)
        ShadowBuild.reset()
        ShadowBuild.setBrand("Xiaomi")
        ShadowBuild.setManufacturer("Xiaomi")
        setExecutionState(null, null)
        AgentCompletionNotifier.cancelIslandTerminal(context)
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @After
    fun tearDown() {
        setExecutionState(null, null)
        AgentCompletionNotifier.cancelIslandTerminal(context)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        ShadowBuild.reset()
    }

    @Test
    fun pickupFocusParamMatchesTemplate16() {
        val v2 = JSONObject(
            MiuiIslandFocus.buildPickupFocusParam(
                ticker = "取码 1234",
                islandTitle = "1234",
                iconTextContent = "示例餐厅",
                hintTitle = "请及时取餐",
                actionTitle = "已取",
                targetPage = MainActivity::class.java.name,
                palette = IslandPalette.PickupFood,
            ),
        ).getJSONObject("param_v2")
        assertEquals(3, v2.getInt("protocol"))
        assertEquals("pickup", v2.getString("business"))
        assertFalse(v2.has("notifyId"))
        assertTrue(v2.getBoolean("enableFloat"))
        assertTrue(v2.getBoolean("isShowNotification"))
        assertTrue(v2.getBoolean("islandFirstFloat"))
        assertEquals(MiuiIslandFocus.PIC_PROFILE, v2.getString("aodPic"))
        assertEquals(2, v2.getJSONObject("param_island").getInt("islandProperty"))
        assertEquals("1234", v2.getJSONObject("iconTextInfo").getString("title"))
        assertEquals("示例餐厅", v2.getJSONObject("iconTextInfo").getString("content"))
        assertEquals("请及时取餐", v2.getJSONObject("hintInfo").getString("title"))
        assertPalette(v2, IslandPalette.PickupFood)
        assertEquals(MiuiIslandFocus.ACT_HINT_V2, v2.getJSONObject("hintInfo").getJSONObject("actionInfo").getString("action"))
        assertEquals(MainActivity::class.java.name, v2.getJSONObject("smallWindowInfo").getString("targetPage"))
    }

    @Test
    fun taskFocusParamUsesSameTemplate16WithAgentBusiness() {
        val v2 = JSONObject(
            MiuiIslandFocus.buildTaskFocusParam(
                ticker = "思考中",
                islandTitle = "思考中",
                iconTextContent = "Eta",
                hintTitle = "正在整理任务步骤",
                actionTitle = "补充",
                targetPage = MainActivity::class.java.name,
                palette = IslandPalette.AgentRunning,
            ),
        ).getJSONObject("param_v2")
        assertEquals(3, v2.getInt("protocol"))
        assertEquals("agent_task", v2.getString("business"))
        val smallArea = v2.getJSONObject("param_island").getJSONObject("smallIslandArea")
        assertEquals("思考中", smallArea.getJSONObject("imageTextInfoRight").getJSONObject("textInfo").getString("title"))
        assertEquals("正在整理任务步骤", v2.getJSONObject("hintInfo").getString("title"))
        assertEquals("补充", v2.getJSONObject("hintInfo").getJSONObject("actionInfo").getString("actionTitle"))
        assertPalette(v2, IslandPalette.AgentRunning)
    }

    @Test
    fun semanticPalettesAreDistinctAndReadable() {
        val palettes = listOf(
            IslandPalette.AgentRunning,
            IslandPalette.AgentCompleted,
            IslandPalette.AgentFailed,
            IslandPalette.AgentStopped,
            IslandPalette.PickupFood,
            IslandPalette.PickupDrink,
            IslandPalette.PickupExpress,
        )
        assertEquals(palettes.size, palettes.map { it.accentArgb }.toSet().size)
        assertEquals(palettes.size, palettes.map { it.containerArgb }.toSet().size)
        palettes.forEach { palette ->
            assertTrue(
                "按钮白字对比度不足：${palette.accentHex}",
                contrastRatio(palette.accentArgb, 0xFFFFFFFF.toInt()) >= 4.5,
            )
            assertTrue(
                "深色图形对比度不足：${palette.contentDarkHex}",
                contrastRatio(palette.containerArgb, palette.contentDarkArgb) >= 4.5,
            )
            assertTrue(
                "浅色主题提示对比度不足：${palette.contentLightHex}",
                contrastRatio(palette.containerArgb, palette.contentLightArgb) >= 4.5,
            )
        }
    }

    @Test
    fun template16ExtrasUseSingleWrapper() {
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val pics = MiuiIslandFocus.buildTemplate16Pics(context, null)
        assertEquals(1, pics.size())
        val innerPics = requireNotNull(pics.getBundle("miui.focus.pics"))
        assertTrue(innerPics.containsKey(MiuiIslandFocus.PIC_PROFILE))
        assertTrue(innerPics.containsKey(MiuiIslandFocus.PIC_ISLAND))
        assertTrue(innerPics.containsKey(MiuiIslandFocus.PIC_LARGE_V2))
        assertTrue(innerPics.containsKey(MiuiIslandFocus.PIC_HINT_CONTENT_V2))

        val actions = MiuiIslandFocus.buildTemplate16Actions("补充", open)
        assertEquals(1, actions.size())
        val innerActions = requireNotNull(actions.getBundle("miui.focus.actions"))
        assertTrue(innerActions.containsKey(MiuiIslandFocus.ACT_HINT_V2))
    }

    @Test
    fun executionUsesTemplate16WithoutPromotedOnXiaomi() {
        Prefs.putLiveActivityStyle(LiveActivityStyle.XIAOMI_ISLAND)
        val notification = executionNotification(AgentOverlayStatus.Reasoning)
        val param = notification.extras.getString("miui.focus.param")
        assertNotNull(param)
        val v2 = JSONObject(requireNotNull(param)).getJSONObject("param_v2")
        assertEquals(3, v2.getInt("protocol"))
        assertEquals("agent_task", v2.getString("business"))
        assertEquals(IslandPalette.AgentRunning.accentArgb, notification.color)
        assertPalette(v2, IslandPalette.AgentRunning)
        assertTrue(notification.extras.containsKey("miui.focus.pics"))
        assertTrue(notification.extras.containsKey("miui.focus.actions"))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertNull(notification.shortCriticalText)
        assertEquals(3, notification.actions.size)
        assertEquals("eta_execution_island", notification.channelId)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(notification.channelId).importance,
        )
        val pics = requireNotNull(notification.extras.getBundle("miui.focus.pics"))
        val taskIcon = requireNotNull(pics.getParcelable(MiuiIslandFocus.PIC_ISLAND, Icon::class.java))
        assertEquals(Icon.TYPE_BITMAP, taskIcon.type)
        assertEquals(taskIcon, pics.getParcelable(MiuiIslandFocus.PIC_PROFILE, Icon::class.java))
    }

    @Test
    fun newRunUsesFreshNotificationIdSoProcessIslandCanExpand() {
        Prefs.putLiveActivityStyle(LiveActivityStyle.XIAOMI_ISLAND)
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        val service = controller.get()
        try {
            val idleId = shadowOf(service).lastForegroundNotificationId
            AgentExecutionService.beginRun(context, "conversation-one")
            shadowOf(Looper.getMainLooper()).idle()
            val firstRunId = shadowOf(service).lastForegroundNotificationId
            assertNotEquals(idleId, firstRunId)
            val firstRun = requireNotNull(shadowOf(service).lastForegroundNotification)
            assertNotNull(firstRun.extras.getString("miui.focus.param"))
            val firstOpen = shadowOf(requireNotNull(firstRun.contentIntent)).savedIntent
            assertEquals(MainActivity::class.java.name, firstOpen.component?.className)
            assertEquals(
                "conversation-one",
                firstOpen.getStringExtra(AgentCompletionNotifier.EXTRA_CONVERSATION_ID),
            )
            assertEquals(context.getString(R.string.live_pause), firstRun.actions[0].title.toString())
            assertEquals(context.getString(R.string.live_supplement), firstRun.actions[1].title.toString())
            assertEquals(context.getString(R.string.execution_stop), firstRun.actions[2].title.toString())
            assertEquals(
                AgentRuntimeService::class.java.name,
                shadowOf(firstRun.actions[0].actionIntent).savedIntent.component?.className,
            )
            assertEquals(
                AgentRuntimeService::class.java.name,
                shadowOf(firstRun.actions[1].actionIntent).savedIntent.component?.className,
            )
            assertEquals(
                AgentExecutionService::class.java.name,
                shadowOf(firstRun.actions[2].actionIntent).savedIntent.component?.className,
            )

            setExecutionState(null, null)
            AgentExecutionService.beginRun(context, "conversation-two")
            shadowOf(Looper.getMainLooper()).idle()
            val secondRunId = shadowOf(service).lastForegroundNotificationId
            assertNotEquals(firstRunId, secondRunId)
            val secondOpen = shadowOf(
                requireNotNull(requireNotNull(shadowOf(service).lastForegroundNotification).contentIntent),
            ).savedIntent
            assertEquals(
                "conversation-two",
                secondOpen.getStringExtra(AgentCompletionNotifier.EXTRA_CONVERSATION_ID),
            )
            assertNull(shadowOf(service.getSystemService(NotificationManager::class.java)).getNotification(firstRunId))
        } finally {
            setExecutionState(null, null)
            controller.destroy()
        }
    }

    @Test
    fun generatingAnswerKeepsProcessIslandAndStreamHint() {
        Prefs.putLiveActivityStyle(LiveActivityStyle.XIAOMI_ISLAND)
        val stream = AgentRunStreamTracker.Snapshot(
            phase = AgentRunStreamTracker.Phase.GENERATING,
            detail = "正在生成回答",
            expanded = "正在生成回答",
            startedAtElapsedRealtime = 1L,
            showChronometer = false,
        )
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        try {
            setExecutionState(AgentOverlayStatus.GeneratingAnswer, stream)
            val method = AgentExecutionService::class.java.declaredMethods.single { it.name == "notification" }
            method.isAccessible = true
            val notification = method.invoke(controller.get()) as Notification
            val v2 = JSONObject(requireNotNull(notification.extras.getString("miui.focus.param")))
                .getJSONObject("param_v2")
            val smallArea = v2.getJSONObject("param_island").getJSONObject("smallIslandArea")
            val title = smallArea.getJSONObject("imageTextInfoRight").getJSONObject("textInfo")
                .getString("title")
            assertEquals(context.getString(R.string.execution_chip_answering), title)
            assertEquals("正在生成回答", v2.getJSONObject("hintInfo").getString("title"))
        } finally {
            setExecutionState(null, null)
            controller.destroy()
        }
    }

    @Test
    fun executionUsesOnlyOfficialPromotedStyleForFluidCloud() {
        Prefs.putLiveActivityStyle(LiveActivityStyle.COLOROS_FLUID)
        val notification = executionNotification(AgentOverlayStatus.Reasoning)
        assertNull(notification.extras.getString("miui.focus.param"))
        assertFalse(notification.extras.containsKey("miui.focus.pics"))
        assertFalse(notification.extras.containsKey("miui.focus.actions"))
        assertNotNull(notification.shortCriticalText)
    }

    @Test
    fun executionNativeStyleAndDisabledMasterDoNotPromote() {
        Prefs.putLiveActivityStyle(LiveActivityStyle.NATIVE)
        assertNativeExecution(executionNotification(AgentOverlayStatus.Reasoning))

        Prefs.putLiveActivityStyle(LiveActivityStyle.COLOROS_FLUID)
        Prefs.putBoolean(Prefs.Keys.LIVE_ACTIVITY_ENABLE, false)
        assertNativeExecution(executionNotification(AgentOverlayStatus.Reasoning))
    }

    @Test
    fun executionFallsBackToNativeOnNonXiaomiDevice() {
        ShadowBuild.setBrand("google")
        ShadowBuild.setManufacturer("Google")
        Prefs.putLiveActivityStyle(LiveActivityStyle.XIAOMI_ISLAND)
        assertNativeExecution(executionNotification(AgentOverlayStatus.Reasoning))
    }

    @Test
    fun completionUsesTemplate16WithoutPromotedOnXiaomi() {
        Prefs.putLiveActivityStyle(LiveActivityStyle.XIAOMI_ISLAND)
        AgentCompletionNotifier.notifyIslandTerminal(
            context,
            AgentCompletionNotifier.TerminalKind.COMPLETED,
            "conversation",
            "任务完成",
        )
        val notification = requireNotNull(islandNotification())
        val param = notification.extras.getString("miui.focus.param")
        assertNotNull(param)
        val v2 = JSONObject(requireNotNull(param)).getJSONObject("param_v2")
        assertEquals(3, v2.getInt("protocol"))
        assertEquals("agent_task", v2.getString("business"))
        assertEquals(IslandPalette.AgentCompleted.accentArgb, notification.color)
        assertPalette(v2, IslandPalette.AgentCompleted)
        assertTrue(notification.extras.containsKey("miui.focus.pics"))
        assertTrue(notification.extras.containsKey("miui.focus.actions"))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertNull(notification.shortCriticalText)
        val pics = requireNotNull(notification.extras.getBundle("miui.focus.pics"))
        val taskIcon = requireNotNull(pics.getParcelable(MiuiIslandFocus.PIC_ISLAND, Icon::class.java))
        assertEquals(Icon.TYPE_BITMAP, taskIcon.type)
        val open = shadowOf(requireNotNull(notification.contentIntent)).savedIntent
        assertEquals(
            "conversation",
            open.getStringExtra(AgentCompletionNotifier.EXTRA_CONVERSATION_ID),
        )
        val view = requireNotNull(
            notification.actions.singleOrNull { it.title.toString() == context.getString(R.string.island_action_view) },
        )
        assertEquals(
            "conversation",
            shadowOf(view.actionIntent).savedIntent.getStringExtra(AgentCompletionNotifier.EXTRA_CONVERSATION_ID),
        )
    }

    @Test
    fun completionIslandStaysUntilExplicitlyCancelled() {
        Prefs.putLiveActivityStyle(LiveActivityStyle.XIAOMI_ISLAND)
        AgentCompletionNotifier.notifyIslandTerminal(
            context,
            AgentCompletionNotifier.TerminalKind.COMPLETED,
            "conversation",
            "任务完成",
        )
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertNotNull(islandNotification())

        AgentCompletionNotifier.cancelIslandTerminal(context)
        assertNull(islandNotification())
    }

    @Test
    fun completionFluidCloudUsesOnlyOfficialPromotedStyle() {
        Prefs.putLiveActivityStyle(LiveActivityStyle.COLOROS_FLUID)
        AgentCompletionNotifier.notifyIslandTerminal(
            context,
            AgentCompletionNotifier.TerminalKind.COMPLETED,
            "conversation",
            "任务完成",
        )
        val notification = requireNotNull(islandNotification())
        assertNull(notification.extras.getString("miui.focus.param"))
        assertFalse(notification.extras.containsKey("miui.focus.pics"))
        assertFalse(notification.extras.containsKey("miui.focus.actions"))
        assertNotNull(notification.shortCriticalText)
    }

    private fun assertNativeExecution(notification: Notification) {
        assertEquals(IslandPalette.AgentRunning.accentArgb, notification.color)
        assertNull(notification.extras.getString("miui.focus.param"))
        assertFalse(notification.extras.containsKey("miui.focus.pics"))
        assertFalse(notification.extras.containsKey("miui.focus.actions"))
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        assertNull(notification.shortCriticalText)
        assertEquals("eta_execution", notification.channelId)
    }

    private fun executionNotification(status: AgentOverlayStatus): Notification {
        val controller = Robolectric.buildService(AgentExecutionService::class.java).create()
        return try {
            setExecutionState(status, null)
            val method = AgentExecutionService::class.java.declaredMethods.single { it.name == "notification" }
            method.isAccessible = true
            method.invoke(controller.get()) as Notification
        } finally {
            setExecutionState(null, null)
            controller.destroy()
        }
    }

    private fun setExecutionState(
        status: AgentOverlayStatus?,
        stream: AgentRunStreamTracker.Snapshot?,
        conversationId: String? = null,
    ) {
        AgentExecutionService::class.java.getDeclaredField("runStatus")
            .apply { isAccessible = true }.set(null, status)
        AgentExecutionService::class.java.getDeclaredField("runStream")
            .apply { isAccessible = true }.set(null, stream)
        AgentExecutionService::class.java.getDeclaredField("runConversationId")
            .apply { isAccessible = true }.set(null, conversationId)
    }

    private fun islandNotification(): Notification? =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.singleOrNull()

    @Test
    fun pickupIslandUsesOrderTypePaletteEverywhere() {
        Prefs.putLiveActivityStyle(LiveActivityStyle.XIAOMI_ISLAND)
        val manager = context.getSystemService(NotificationManager::class.java)
        val cases = listOf(
            "餐食" to IslandPalette.PickupFood,
            "饮品" to IslandPalette.PickupDrink,
            "快递" to IslandPalette.PickupExpress,
        )
        cases.forEach { (orderType, palette) ->
            val code = "code-$orderType"
            PickupCodeNotifier.notifyNewCode(context, code, orderType, "示例品牌", null)
            val notification = requireNotNull(shadowOf(manager).getNotification(code.hashCode()))
            assertEquals(palette.accentArgb, notification.color)
            val v2 = JSONObject(requireNotNull(notification.extras.getString("miui.focus.param")))
                .getJSONObject("param_v2")
            assertPalette(v2, palette)
            val pics = requireNotNull(notification.extras.getBundle("miui.focus.pics"))
            assertEquals(
                Icon.TYPE_BITMAP,
                requireNotNull(pics.getParcelable(MiuiIslandFocus.PIC_ISLAND, Icon::class.java)).type,
            )
        }
    }

    @Test
    fun nativePickupUsesOrderTypeAccent() {
        Prefs.putLiveActivityStyle(LiveActivityStyle.NATIVE)
        val manager = context.getSystemService(NotificationManager::class.java)
        val cases = listOf(
            "餐食" to IslandPalette.PickupFood,
            "饮品" to IslandPalette.PickupDrink,
            "快递" to IslandPalette.PickupExpress,
            "未知品类" to IslandPalette.PickupFood,
        )
        cases.forEach { (orderType, palette) ->
            val code = "native-$orderType"
            PickupCodeNotifier.notifyNewCode(context, code, orderType, null, null)
            assertEquals(palette.accentArgb, requireNotNull(shadowOf(manager).getNotification(code.hashCode())).color)
        }
    }

    @Test
    fun pickupNotificationOpensListWhileDoneActionStaysBroadcast() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val cases = listOf(
            LiveActivityStyle.NATIVE to "pickup-open-native",
            LiveActivityStyle.XIAOMI_ISLAND to "pickup-open-island",
            LiveActivityStyle.COLOROS_FLUID to "pickup-open-fluid",
        )
        cases.forEach { (style, code) ->
            manager.cancelAll()
            Prefs.putLiveActivityStyle(style)
            PickupCodeNotifier.notifyNewCode(context, code, "餐食", "示例品牌", null)
            val notification = requireNotNull(shadowOf(manager).getNotification(code.hashCode()))
            val open = shadowOf(requireNotNull(notification.contentIntent)).savedIntent
            assertEquals(MainActivity::class.java.name, open.component?.className)
            assertTrue(open.getBooleanExtra(PickupCodeNotifier.EXTRA_OPEN_PICKUP_CODES, false))

            when (style) {
                LiveActivityStyle.NATIVE -> {
                    val done = notification.actions.single()
                    assertEquals(context.getString(R.string.pickup_action_mark_done), done.title.toString())
                    assertPickupDoneAction(done, code)
                }
                LiveActivityStyle.XIAOMI_ISLAND -> {
                    val actions = requireNotNull(notification.extras.getBundle("miui.focus.actions"))
                    val done = requireNotNull(
                        actions.getParcelable(MiuiIslandFocus.ACT_HINT_V2, Notification.Action::class.java),
                    )
                    assertEquals(context.getString(R.string.pickup_island_action_done), done.title.toString())
                    assertPickupDoneAction(done, code)
                }
                LiveActivityStyle.COLOROS_FLUID -> {
                    val done = notification.actions.single {
                        it.title.toString() == context.getString(R.string.pickup_action_mark_done)
                    }
                    assertPickupDoneAction(done, code)
                    val view = notification.actions.single {
                        it.title.toString() == context.getString(R.string.island_action_view)
                    }
                    assertTrue(
                        shadowOf(view.actionIntent).savedIntent
                            .getBooleanExtra(PickupCodeNotifier.EXTRA_OPEN_PICKUP_CODES, false),
                    )
                }
            }
        }
    }

    @Test
    fun notificationTargetExtrasAreConsumedOnceAndRepeatPickupClicksAdvanceToken() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val update = MainActivity::class.java.getDeclaredMethod("updateNotificationTarget", Intent::class.java)
            .apply { isAccessible = true }
        val pickupToken = MainActivity::class.java.getDeclaredMethod("getOpenPickupCodesRequest")
            .apply { isAccessible = true }
        val conversationTarget = MainActivity::class.java.getDeclaredMethod("getLiveTargetConversationId")
            .apply { isAccessible = true }

        val pickupIntent = Intent().putExtra(PickupCodeNotifier.EXTRA_OPEN_PICKUP_CODES, true)
        update.invoke(activity, pickupIntent)
        assertFalse(pickupIntent.hasExtra(PickupCodeNotifier.EXTRA_OPEN_PICKUP_CODES))
        assertEquals(1, pickupToken.invoke(activity))
        update.invoke(activity, pickupIntent)
        assertEquals(1, pickupToken.invoke(activity))
        update.invoke(activity, Intent().putExtra(PickupCodeNotifier.EXTRA_OPEN_PICKUP_CODES, true))
        assertEquals(2, pickupToken.invoke(activity))

        val conversationIntent = Intent()
            .putExtra(AgentCompletionNotifier.EXTRA_CONVERSATION_ID, "conversation-target")
        update.invoke(activity, conversationIntent)
        assertFalse(conversationIntent.hasExtra(AgentCompletionNotifier.EXTRA_CONVERSATION_ID))
        assertEquals("conversation-target", conversationTarget.invoke(activity))
    }

    private fun assertPickupDoneAction(action: Notification.Action, code: String) {
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(PickupCodeCompleteReceiver::class.java.name, intent.component?.className)
        assertEquals(PickupCodeCompleteReceiver.ACTION, intent.action)
        assertEquals(code, intent.getStringExtra(PickupCodeCompleteReceiver.EXTRA_CODE))
        assertFalse(intent.getBooleanExtra(PickupCodeNotifier.EXTRA_OPEN_PICKUP_CODES, false))
    }

    private fun assertPalette(v2: JSONObject, palette: IslandPalette) {
        val hint = v2.getJSONObject("hintInfo")
        assertEquals(palette.contentLightHex, hint.getString("colorContent"))
        assertEquals(palette.contentDarkHex, hint.getString("colorContentDark"))
        assertEquals(palette.containerHex, hint.getString("colorContentBg"))
        val action = hint.getJSONObject("actionInfo")
        assertEquals(palette.accentHex, action.getString("actionBgColor"))
        assertEquals(palette.accentHex, action.getString("actionBgColorDark"))
    }

    private fun contrastRatio(firstArgb: Int, secondArgb: Int): Double {
        fun luminance(argb: Int): Double {
            fun linearize(channel: Int): Double {
                val value = channel / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            val red = linearize((argb shr 16) and 0xFF)
            val green = linearize((argb shr 8) and 0xFF)
            val blue = linearize(argb and 0xFF)
            return 0.2126 * red + 0.7152 * green + 0.0722 * blue
        }
        val first = luminance(firstArgb)
        val second = luminance(secondArgb)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }

    @Test
    fun previewTakesLastNonBlankLineWithTailEllipsis() {
        assertEquals("hello", AgentRunStreamTracker.extractSingleLinePreview("first\nsecond\nhello"))
        assertEquals("", AgentRunStreamTracker.extractSingleLinePreview("   "))
        val preview = AgentRunStreamTracker.extractSingleLinePreview("x".repeat(100))
        assertEquals(43, preview.length) // "…" + 42
        assertTrue(preview.startsWith("…"))
    }

    @Test
    fun tailTruncatesWithEllipsisPrefix() {
        val tail = AgentRunStreamTracker.extractTail("y".repeat(400))
        assertEquals(321, tail.length) // "…" + 320
        assertTrue(tail.startsWith("…"))
        assertEquals("y".repeat(320), tail.drop(1))
        assertEquals(300, AgentRunStreamTracker.extractTail("y".repeat(300)).length)
    }

    @Test
    fun toolPreviewPrefersCommandAndCollapsesLines() {
        assertEquals("adb shell ls", AgentRunStreamTracker.formatToolPreview("adb\nshell ls", "args"))
        assertEquals("args", AgentRunStreamTracker.formatToolPreview(null, "args"))
        assertEquals("", AgentRunStreamTracker.formatToolPreview(null, "  "))
        val preview = AgentRunStreamTracker.formatToolPreview("z".repeat(50), "")
        assertEquals("z".repeat(42) + "…", preview)
    }
}
