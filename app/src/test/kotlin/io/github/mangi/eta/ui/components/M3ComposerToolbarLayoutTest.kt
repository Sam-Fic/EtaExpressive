package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.voice.VoiceModePhase
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 单行浮动工具栏输入区：有输入内容时左侧动作组收起、输入框展开、尾部发送按钮替代语音按钮；
 * 清空后动作组展开回来。断言用英文默认资源的内容描述定位官方图标按钮。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "w480dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class M3ComposerToolbarLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun keyboardCollapsesActionGroupAndContentSwapsVoiceForSendButton() {
        val field = TextFieldState()
        val voiceStateHolder = mutableStateOf(VoiceModeState())
        val imeVisible = mutableStateOf(false)
        compose.setComposerContent(field, voiceStateHolder, imeVisible = imeVisible)
        // 空输入：左侧三个入口（+ / 相机 / 模型与助手）都在，尾部还没有发送按钮。
        compose.onNodeWithContentDescription("Add attachment").assertExists()
        compose.onNodeWithContentDescription("Image").assertExists()
        compose.onNodeWithContentDescription("Model & assistant").assertExists()
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()
        val expandedActionsFieldWidth = fieldWidth()
        // 左侧三个按钮贴底：官方工具栏内容 Row 是顶对齐（Row 默认 Alignment.Top），
        // 必须显式 align，否则 40dp 按钮相对 56dp 输入框会偏高 8dp。
        // 单行（56dp）时贴底与竖直居中重合（上下各 8dp），故这里断言居中仍然成立。
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val idleFieldBounds = fieldBounds()
        // 空输入（未触发输入）时工具栏应保持单行高度：输入框 56dp + 上下各 8dp 内边距 = 72dp，
        // 外层 36dp 圆角恰好是胶囊；输入框距工具栏顶部因此必须是 8dp（公差 0.5px）。
        assertEquals(
            "空输入时输入框距工具栏顶部应为 8dp（工具栏保持 72dp 胶囊）",
            8f,
            idleFieldBounds.top,
            0.5f,
        )
        val addBounds = compose.onNodeWithContentDescription("Add attachment")
            .fetchSemanticsNode().boundsInRoot
        listOf("Add attachment", "Image", "Model & assistant").forEach { description ->
            val buttonBounds = compose.onNodeWithContentDescription(description)
                .fetchSemanticsNode().boundsInRoot
            assertEquals(
                "$description 单行时应与输入框竖直居中（贴底与居中在此高度下重合）",
                idleFieldBounds.center.y,
                buttonBounds.center.y,
                0.5f,
            )
        }
        // 同心（左侧端盖）：40dp 圆钮到工具栏左端与上下的间距应相等。
        // 外层胶囊高度 72dp → 半径 36dp，减去圆钮半径 20dp = 16dp 同心间距；
        // 工具栏顶边由垂直居中的输入框回推（输入框距工具栏上下各 8dp）。
        val barTop = idleFieldBounds.top - 8f
        assertEquals(
            "+ 按钮到工具栏上端与左端的间距应相等",
            addBounds.top - barTop,
            addBounds.left - root.left,
            0.5f,
        )
        assertEquals(
            "+ 按钮到工具栏左端的同心间距应为 16dp",
            16f,
            addBounds.left - root.left,
            0.5f,
        )

        // 输入文字但键盘收起：左侧动作组保持展开可见（未进入输入焦点），
        // 尾部语音入口让位给发送按钮；输入框宽度不变。
        compose.runOnIdle { field.setTextAndPlaceCursorAtEnd("hello") }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add attachment").assertExists()
        compose.onNodeWithContentDescription("Image").assertExists()
        compose.onNodeWithContentDescription("Model & assistant").assertExists()
        compose.onNodeWithContentDescription("Send").assertExists()
        assertEquals(
            "键盘收起时动作组不收起，输入框宽度不变",
            expandedActionsFieldWidth,
            fieldWidth(),
            0.5f,
        )

        // 键盘弹出且已有内容：左侧动作组收起，输入框展开占满，发送按钮替代语音按钮的位置。
        compose.runOnIdle { imeVisible.value = true }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add attachment").assertDoesNotExist()
        compose.onNodeWithContentDescription("Image").assertDoesNotExist()
        compose.onNodeWithContentDescription("Model & assistant").assertDoesNotExist()
        compose.onNodeWithContentDescription("Send").assertExists()
        assertTrue(
            "输入框展开后应比动作组可见时更宽",
            fieldWidth() > expandedActionsFieldWidth,
        )
        // 同心圆角布置：动作组收起后，输入框左右各贴工具栏 8dp 内边距（两端间距相等），
        // 这样外层胶囊（半径 = 高度/2）与内层胶囊（半径 = 高度/2 − 间距）才是同心关系。
        val fieldBounds = fieldBounds()
        assertEquals(
            "输入框距工具栏两端的间距应相等",
            fieldBounds.left - root.left,
            root.right - fieldBounds.right,
            0.5f,
        )
        // 同心圆角（尾部按钮）：40dp 圆形按钮到输入框上下与右端的间距应一致（8dp）。
        val sendBounds = compose.onNodeWithContentDescription("Send")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "尾部按钮到输入框右端与上下的间距应一致",
            (fieldBounds.height - sendBounds.height) / 2f,
            fieldBounds.right - sendBounds.right,
            0.5f,
        )

        // 键盘再次收起：动作组展开回来，输入框缩回动作组可见时的宽度。
        compose.runOnIdle { imeVisible.value = false }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add attachment").assertExists()
        assertEquals(
            "键盘收起后动作组展开，输入框宽度回到动作组可见时",
            expandedActionsFieldWidth,
            fieldWidth(),
            0.5f,
        )

        // 清空草稿：左侧动作组展开回来，发送按钮让位给语音按钮。
        compose.runOnIdle { field.setTextAndPlaceCursorAtEnd("") }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Add attachment").assertExists()
        compose.onNodeWithContentDescription("Send").assertDoesNotExist()

        // 多行草稿：输入框随内容长高（最多 6 行），工具栏只应比输入框高 2 × 8dp 内边距；
        // 输入框到工具栏顶部仍是 8dp（工具栏不会被官方补偿内边距撑成巨型胶囊）。
        compose.runOnIdle { field.setTextAndPlaceCursorAtEnd("1\n2") }
        compose.waitForIdle()
        val multiLineBounds = fieldBounds()
        assertTrue("两行草稿应高于单行 56dp", multiLineBounds.height > 60f)
        assertEquals(
            "多行时输入框距工具栏顶部仍应保持 8dp",
            8f,
            multiLineBounds.top,
            0.5f,
        )
        // 发送按钮固定在右下角：与内层 28dp 圆角同心（40dp 圆钮半径 20dp ⇒ 端距 8dp）。
        val multiLineSend = compose.onNodeWithContentDescription("Send")
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "多行时发送按钮距输入框右端应为 8dp",
            8f,
            multiLineBounds.right - multiLineSend.right,
            0.5f,
        )
        assertEquals(
            "多行时发送按钮距输入框底端应为 8dp",
            8f,
            multiLineBounds.bottom - multiLineSend.bottom,
            0.5f,
        )
        // 多行时左侧三个按钮必须与发送按钮同底（贴底下沉），而不是停在输入框竖直中间。
        listOf("Add attachment", "Image", "Model & assistant").forEach { description ->
            val buttonBounds = compose.onNodeWithContentDescription(description)
                .fetchSemanticsNode().boundsInRoot
            assertEquals(
                "多行时 $description 应与发送按钮同底（距输入框底端 8dp）",
                multiLineSend.bottom,
                buttonBounds.bottom,
                0.5f,
            )
        }

        // 语音进行中（合并后的语音对话）：整条工具栏切换为聆听条——输入框与动作组消失，
        // 出现实时转写文本与官方色调停止圆钮（“End voice conversation”）。
        compose.runOnIdle {
            voiceStateHolder.value = VoiceModeState(
                mode = VoiceEntryMode.UNIVERSAL,
                phase = VoiceModePhase.Listening,
                transcript = "你说",
            )
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("End voice conversation").assertExists()
    }

    /**
     * “无输入框 → 聆听条”的过渡必须与“有输入内容时动作组收起”同款：
     * 动作组按弹簧横向收起、聆听条以右端为锚展开，两段过渡连续。
     *
     * 曾经的实现用 `if (!voiceActive)` 把动作组整组硬切换掉，开始 / 结束语音时动作组
     * 瞬间消失 / 出现，胶囊宽度跳变；这里锁定住该回归——进入语音后的过渡中段，
     * 动作组必须仍在组合中（正在横向收起），而不是被立即移除。
     */
    @Test
    fun enteringVoiceModeAnimatesActionGroupInsteadOfHardCut() {
        val field = TextFieldState()
        val voiceStateHolder = mutableStateOf(VoiceModeState())
        compose.setComposerContent(field, voiceStateHolder)
        compose.onNodeWithContentDescription("Add attachment").assertExists()

        // 冻结时钟后切到语音态，逐帧推进到过渡中段（远未结束）。
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            voiceStateHolder.value = VoiceModeState(
                mode = VoiceEntryMode.UNIVERSAL,
                phase = VoiceModePhase.Listening,
            )
        }
        repeat(8) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }

        // 动作组此时必须仍在组合中（正在按弹簧横向收起），而不是像硬切换那样在语音态生效的
        // 下一帧就被整体移除。旧实现用 `if (!voiceActive) { … }` 直接换分支，会让这里立刻
        // 找不到节点：动作组瞬间消失、胶囊宽度跳变——正是“无输入框 → 聆听条”过渡不正确的根因。
        assertEquals(
            "进入语音的过渡中段，左侧动作组应仍在（正在收起），而非被硬切换移除",
            1,
            compose.onAllNodesWithContentDescription("Add attachment").fetchSemanticsNodes().size,
        )
        // 聆听条的停止圆钮也应已进入组合（AnimatedContent 交叉过渡中）。
        assertTrue(
            "进入语音的过渡中段，聆听条的停止圆钮应已就位",
            compose.onAllNodesWithContentDescription("End voice conversation")
                .fetchSemanticsNodes().isNotEmpty(),
        )

        // 推进到动画结束：动作组完全收走，输入框让位给聆听条。
        compose.mainClock.advanceTimeBy(2_000)
        assertEquals(
            "过渡结束后左侧动作组应完全收起",
            0,
            compose.onAllNodesWithContentDescription("Add attachment").fetchSemanticsNodes().size,
        )
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("End voice conversation").assertExists()
    }

    /**
     * 「+」按钮外观：空闲与展开态都保持无底板的纯图标（按下无涟漪、也不出现填充色块），
     * 展开菜单后图标旋转 45° 成为「×」。
     *
     * 这里对按钮容器像素取样：两种状态都应仍是工具栏底色，不应出现 primary 填充。
     */
    @Test
    fun addButtonKeepsFlatBackgroundAndRotatesWhenMenuExpands() {
        val field = TextFieldState()
        val voiceStateHolder = mutableStateOf(VoiceModeState())
        val primary = Color(0xFF123456)
        compose.setComposerContent(
            field = field,
            voiceStateHolder = voiceStateHolder,
            colorScheme = lightColorScheme(primary = primary),
        )
        val add = compose.onNodeWithContentDescription("Add attachment")
        add.assertExists()

        // 空闲态：容器透明，采样点仍是外层工具栏的 vibrant 底色，而不是 primary。
        val idlePixels = add.captureToImage().toPixelMap()
        val idleEdge = idlePixels[1, idlePixels.height / 2]
        assertTrue(
            "空闲的「+」按钮不应有填充背景（采样点 $idleEdge 不应等于 primary）",
            idleEdge != primary,
        )

        // 展开菜单：按钮仍是无底板图标，采样点与环境同色，不应变成 primary 色块。
        add.performClick()
        compose.waitForIdle()
        val openPixels = compose.onNodeWithContentDescription("Add attachment")
            .captureToImage().toPixelMap()
        val openEdge = openPixels[1, openPixels.height / 2]
        assertEquals(
            "展开菜单后「+」按钮不应出现填充背景（采样点 $openEdge 应与空闲态一致）",
            idleEdge,
            openEdge,
        )
        assertTrue("展开菜单后「+」按钮不应被 primary 填充", openEdge != primary)

        // 空闲与展开两种图标朝向不同：45° 旋转把「+」化成「×」，逐像素比较应存在差异。
        var diff = 0
        for (y in 0 until minOf(idlePixels.height, openPixels.height)) {
            for (x in 0 until minOf(idlePixels.width, openPixels.width)) {
                if (idlePixels[x, y] != openPixels[x, y]) diff++
            }
        }
        assertTrue("展开后的图标应发生旋转（逐像素应存在差异）", diff > 0)

        // 再次点击关闭菜单：回到透明空闲态。
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.waitForIdle()
        val closedPixels = compose.onNodeWithContentDescription("Add attachment")
            .captureToImage().toPixelMap()
        assertTrue(
            "收起菜单后「+」按钮应恢复透明背景",
            closedPixels[1, closedPixels.height / 2] != primary,
        )
    }

    /**
     * 「无输入框 → 聆听条」的衔接期间，胶囊必须**全程不透明**：底衬始终是不透明的实心胶囊，
     * 输入框与聆听条共用 `surfaceContainer` 底色，绝不能把外层 vibrant 工具栏的
     * `primaryContainer` 透出来。
     *
     * 回归背景：交叉淡化时输入框与聆听条同时只有部分 alpha，两层半透明源叠加后整体不满不透明，
     * 外层工具栏底色就会从中间透出，观感是"胶囊中间变色 / 发虚"。修复方式是在该槽位铺一层
     * 同形的实心底衬。这里对槽位像素取样验证。
     */
    @Test
    fun pillBackdropStaysOpaqueThroughVoiceCrossfade() {
        val field = TextFieldState()
        val voiceStateHolder = mutableStateOf(VoiceModeState())
        // 三色互不相同且对比强烈，任何混入工具栏底色的像素都会在 red 通道上暴露。
        val toolbarColor = Color(0xFFE80000)
        val inputPillColor = Color(0xFF0000E8)
        compose.setComposerContent(
            field = field,
            voiceStateHolder = voiceStateHolder,
            colorScheme = lightColorScheme(
                primaryContainer = toolbarColor,
                surfaceContainer = inputPillColor,
            ),
        )
        val slot = compose.onNodeWithTag("composer-pill-slot", useUnmergedTree = true)
        slot.assertExists()

        // 静止态：整块胶囊就是输入框底色。
        val idleSample = pillSample()
        assertEquals("静止态胶囊底色应为 surfaceContainer", inputPillColor, idleSample)

        // 冻结时钟，切到语音态并在过渡中逐帧取样。
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            voiceStateHolder.value = VoiceModeState(
                mode = VoiceEntryMode.UNIVERSAL,
                phase = VoiceModePhase.Listening,
            )
        }
        repeat(10) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val sample = pillSample()
            // 关键断言：过渡期间不得出现外层工具栏底色（primaryContainer 的红色）。
            // 只要底衬不透明，红通道就应恒为 0；一旦让工具栏底色透出，红通道立即 > 0。
            assertEquals(
                "胶囊衔接中不应透出外层工具栏底色（采样 $sample）",
                0f,
                sample.red,
                1f / 255f,
            )
        }

        // 过渡结束后应落到聆听条底色（与输入框同为 surfaceContainer）。
        compose.mainClock.advanceTimeBy(2_000)
        compose.mainClock.autoAdvance = true
        assertEquals("语音态胶囊底色应为 surfaceContainer", inputPillColor, pillSample())
    }

    /**
     * 「通话按钮 → 停止按钮」（进入语音）必须与「通话按钮 → 发送按钮」（输入文字）同款：
     * 同一槽位、同一枚按钮做**淡入淡出 + 0.8 缩放**的官方 Expressive 弹簧形变，
     * 而不是让整块胶囊各自淡入淡出（那样尾部按钮只是简单渐变、尺寸不变）。
     *
     * 语义节点尺寸不会反映 `graphicsLayer` 缩放，因此这里对尾部槽位截图，只统计停止圆钮
     * 容器色（`tertiaryContainer`）的像素横向跨度，并在过渡中逐帧测量：
     *  - 带缩放：圆钮自 0.8 倍起逐渐放大到 1.0 倍，必然出现“已可见但明显小于终值”的帧；
     *  - 纯交叉淡化：透明度是整圆均匀的，要么匹配不到、要么一出现就是满尺寸，不存在中间帧。
     * 于是“存在 0 < 跨度 < 终值”即可区分两者，且不依赖具体像素阈值。
     */
    @Test
    fun trailingButtonMorphsWithScaleInsteadOfPlainCrossfade() {
        val field = TextFieldState()
        val voiceStateHolder = mutableStateOf(VoiceModeState())
        // 停止圆钮取 tertiaryContainer，用高饱和绿把它与底衬 / Call 按钮在像素上分开。
        val stopColor = Color(0xFF00E800)
        compose.setComposerContent(
            field = field,
            voiceStateHolder = voiceStateHolder,
            colorScheme = lightColorScheme(tertiaryContainer = stopColor),
        )

        // 先进入语音并等动画结束，量出停止圆钮的“终值跨度”作为基准。
        compose.runOnIdle {
            voiceStateHolder.value = VoiceModeState(
                mode = VoiceEntryMode.DOUBAO_DUPLEX,
                phase = VoiceModePhase.Listening,
            )
        }
        compose.waitForIdle()
        val settledWidth = compose.stopButtonWidth(stopColor)
        assertTrue("停止圆钮静止态应可见", settledWidth > 0)

        // 退出语音、回到通话按钮，冻结时钟重新进入语音，逐帧采样。
        compose.runOnIdle { voiceStateHolder.value = VoiceModeState() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            voiceStateHolder.value = VoiceModeState(
                mode = VoiceEntryMode.DOUBAO_DUPLEX,
                phase = VoiceModePhase.Listening,
            )
        }
        var sawIntermediate = false
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            val width = compose.stopButtonWidth(stopColor)
            if (width > 0 && width < settledWidth) sawIntermediate = true
        }
        assertTrue(
            "进入语音时停止圆钮应带缩放形变（过渡中应出现比终值窄的中间帧）；" +
                "若只是交叉淡化，则圆钮一出现就是满尺寸",
            sawIntermediate,
        )
        compose.mainClock.autoAdvance = true
    }

    /**
     * 长按「助手与模型」入口：进入设置页的子代理配置页（预设组列表 / 预设详情）。
     *
     * 子代理配置只保留设置页一套页面，输入栏不再单独承载一套会话协作页。
     */
    @Test fun longPressOnModelPickerEntryOpensSubAgentSettingsPage() {
        val field = TextFieldState()
        val voiceStateHolder = mutableStateOf(VoiceModeState())
        var opened = 0
        compose.setComposerContent(field, voiceStateHolder, onOpenSubAgentSettings = { opened++ })
        compose.onNodeWithContentDescription("Model & assistant").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals("长按应进入设置页的子代理配置页", 1, opened) }
    }

    /**
     * 只统计停止圆钮容器色（[stopColor]）像素的横向跨度（px，@mdpi 下 1dp = 1px）。
     *
     * 透明度是整圆均匀的，因此纯淡入淡出只会给出 0 或满宽；带缩放才能给出中间宽度。
     * 容差 0.05 用于吸收抗锯齿与色值插值的少量误差。
     */
    private fun ComposeContentTestRule.stopButtonWidth(stopColor: Color): Int {
        val pixels = onNodeWithTag("composer-trailing-slot", useUnmergedTree = true)
            .captureToImage().toPixelMap()
        var lo = -1
        var hi = -1
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val c = pixels[x, y]
                val hit = kotlin.math.abs(c.red - stopColor.red) < 0.05f &&
                    kotlin.math.abs(c.green - stopColor.green) < 0.05f &&
                    kotlin.math.abs(c.blue - stopColor.blue) < 0.05f
                if (hit) {
                    if (lo < 0) lo = x
                    if (x > hi) hi = x
                }
            }
        }
        return if (lo < 0) 0 else hi - lo + 1
    }

    /**
     * 取胶囊槽位顶边中点附近的像素：位于实心底衬的平坦区（28dp 圆角只影响两端），
     * 同时避开了输入框内部的文本与控件（起始侧形变加载指示器此处关闭）。
     */
    private fun pillSample(): Color {
        val pixels = compose.onNodeWithTag("composer-pill-slot", useUnmergedTree = true)
            .captureToImage().toPixelMap()
        return pixels[pixels.width / 2, 1]
    }

    private fun ComposeContentTestRule.setComposerContent(
        field: TextFieldState,
        voiceStateHolder: MutableState<VoiceModeState>,
        imeVisible: MutableState<Boolean> = mutableStateOf(false),
        colorScheme: androidx.compose.material3.ColorScheme = lightColorScheme(),
        onOpenSubAgentSettings: () -> Unit = {},
    ) {
        setContent {
            MaterialExpressiveTheme(
                colorScheme = colorScheme,
                motionScheme = MotionScheme.expressive(),
            ) {
                Material3ComposerBody(
                    textFieldState = field,
                    focusRequester = remember { FocusRequester() },
                    imeVisible = imeVisible.value,
                    isEditingMessage = false,
                    isStreaming = false,
                    isPaused = false,
                    isCompressingContext = false,
                    canContinueDisconnected = false,
                    contextSendBlocked = false,
                    canSend = field.text.isNotBlank(),
                    usage = AgentContextUsageUi(contextTokens = null, contextWindow = null),
                    popupMaxHeight = { 360.dp },
                    onPositionedTopPx = {},
                    conversationId = null,
                    assistantId = "",
                    modelPickerState = AgentModelPickerUiState(),
                    reasoningEffort = ReasoningEffort.OFF,
                    availableReasoningEfforts = emptyList(),
                    gptSpeedMode = GptSpeedMode.NORMAL,
                    gptSpeedSupported = false,
                    voiceState = voiceStateHolder.value,
                    showMorphLoading = false,
                    pendingImages = emptyList(),
                    pendingFileReferences = emptyList(),
                    conversationMentions = ConversationMentionInputUi(),
                    onStartVoiceMode = {},
                    onStopVoiceMode = {},
                    onCancelMessageEdit = {},
                    onAttachImage = {},
                    onAttachVideo = {},
                    onAttachFiles = {},
                    onAttachFolder = {},
                    onAttachFilePath = {},
                    onEditAssistant = {},
                    onOpenSubAgentSettings = onOpenSubAgentSettings,
                    onAssistantSelected = {},
                    onModelSelected = { _, _ -> },
                    onReasoningEffortChange = {},
                    onCycleGptSpeedMode = {},
                    onSubmit = {},
                    onStop = {},
                    onContinue = {},
                    onAbortPausedRun = {},
                )
            }
        }
    }

    private fun fieldBounds() =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot

    private fun fieldWidth(): Float = fieldBounds().width
}
