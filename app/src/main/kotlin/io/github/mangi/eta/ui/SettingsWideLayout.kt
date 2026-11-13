package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.layout.LocalPageContentWidth
import io.github.mangi.eta.ui.layout.SettingsSideRailMaxWidth
import io.github.mangi.eta.ui.layout.SettingsSideRailWidthFraction
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/**
 * 宽屏（≥600dp）设置页的 co-planar 双栏宿主。
 *
 * [left] 是**与手机端完全相同的设置主页**（十组全部渲染、自带顶栏与搜索入口），
 * [right] 是承载二级/三级设置页的独立导航栈宿主。主返回栈始终停在 `AppRoute.Settings`，
 * 点子页只压右栏栈，因此左栏既不消失也不重建、滚动位置与页内状态都不变。
 *
 * 右栏容器 [Modifier.clipToBounds]：转场层用 graphicsLayer 平移，Compose 默认不裁剪子节点，
 * 不裁会让被覆盖层 / 入场层溢出到左栏（用户实测到的「下级页面动画盖住左侧栏」）。
 *
 * 取证（AGENTS.md / RULES.md §1）：
 * - 语义：M3「Scaffold › Panes」——"Co-planar: Two side-by-side panes. To stay accessible,
 *   persistent utilities like tool panels should be co-planar with primary content."；
 *   "Floating: A small pane displays above larger panes. Temporary tasks should remain
 *   floating regardless of breakpoint, such as a dialog." 设置是持久目的地，不是临时任务。
 *   Android 官方「构建列表详情布局」把「应用偏好设置划分为类别列表 + 详细信息窗格」
 *   列为 list-detail 的用例；本实现把详情窗格扩成独立返回栈，三级页也留在右栏。
 * - 版本：本仓没有 AndroidX material3 adaptive 系列依赖（`gradle/libs.versions.toml`
 *   无 adaptive 条目）；宽屏判定与尺寸令牌全部来自 `ui/layout/AdaptivePageLayout.kt`
 *   （`rememberIsWideScreen()` 600dp、`SettingsSideRailMaxWidth` 360dp / 0.46）。
 * - 签名：`BoxWithConstraints`/`Row`/`weight`/`width`/`clipToBounds` 均为 Compose 既有 API；
 *   `LocalPageContentWidth` 是本仓 `staticCompositionLocalOf<Dp?>`，两栏各自按被挤窄后的
 *   实际宽度发布，列表内容限宽才不会按整窗宽度算错。
 * - 右栏嵌套导航：miuix-nav-android 0.9.4 sources jar 的
 *   `commonMain/top/yukonga/miuix/kmp/nav/core/NavDisplay.kt:961-970` 公开重载签名是
 *   `NavDisplay(backStack, modifier, onBack, transition, effects, content)`；
 *   同文件 `:847-856` 明确处理 nested display 的返回事件仲裁，`:1030` 有
 *   `require(backStack.isNotEmpty())`，故右栏栈必须带一个可渲染的栈底。
 */
@Composable
internal fun SettingsWideHost(
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val leftWidth = minOf(SettingsSideRailMaxWidth, maxWidth * SettingsSideRailWidthFraction)
        Row(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(modifier = Modifier.width(leftWidth).fillMaxHeight()) {
                CompositionLocalProvider(LocalPageContentWidth provides maxWidth) {
                    left()
                }
            }
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 直角右栏：把转场的 graphicsLayer 平移裁在本栏内，不越界盖住左栏。
                    .clipToBounds(),
            ) {
                CompositionLocalProvider(LocalPageContentWidth provides maxWidth) {
                    right()
                }
            }
        }
    }
}

/**
 * 打开宽屏设置页时右栏默认显示的第一个子设置页。
 *
 * 对应设置主页第一组第一行「模型提供商」（`SettingsKeys.MODEL_PROVIDER` →
 * `AppRoute.ModelProviders`），也就是手机端列表里第一个有子页的设置项。
 */
internal val SettingsDetailFirstRoute: AppRoute = AppRoute.ModelProviders

/**
 * 左栏跳转行的锚点键：路由类型 → [SettingsKeys] 常量。
 *
 * 宽屏下右栏停在某个二级页时，左栏要持久高亮那一行；三级/四级页仍按右栏栈底
 * （`settingsDetailStack.firstOrNull()`）取，所以选中态不会跟着深层跳转乱跳。
 * 用 `KClass` 而不是路由实例比较：`AppRoute.Assistants(picker = …)` 这类带参路由
 * 换参数后仍属于同一行。清单与 `SettingsCatalog.kt` 的
 * `SettingsDestination.Route` 一一对应，新增设置项时必须同步补一行。
 */
private val settingsDetailAnchorKeys: Map<KClass<out AppRoute>, String> = mapOf(
    AppRoute.ModelProviders::class to SettingsKeys.MODEL_PROVIDER,
    AppRoute.MobileActionsSettings::class to SettingsKeys.MOBILE_ACTIONS,
    AppRoute.ContextCompression::class to SettingsKeys.CONTEXT_COMPRESSION,
    AppRoute.DecisionModel::class to SettingsKeys.DECISION_MODEL,
    AppRoute.AuxiliaryVision::class to SettingsKeys.AUXILIARY_VISION,
    AppRoute.TitleModel::class to SettingsKeys.TITLE_MODEL,
    AppRoute.ErrorReconnectSettings::class to SettingsKeys.ERROR_RECONNECT,
    AppRoute.WebToolsSettings::class to SettingsKeys.WEB_TOOLS_SETTINGS,
    AppRoute.MijiaSettings::class to SettingsKeys.MIJIA_SETTINGS,
    AppRoute.Assistants::class to SettingsKeys.ASSISTANTS,
    AppRoute.Memory::class to SettingsKeys.MEMORY,
    AppRoute.Skills::class to SettingsKeys.SKILLS,
    AppRoute.McpServers::class to SettingsKeys.MCP_SERVERS,
    AppRoute.ToolRecipes::class to SettingsKeys.TOOL_RECIPES,
    AppRoute.SubAgents::class to SettingsKeys.SUB_AGENTS,
    AppRoute.RemoteAgents::class to SettingsKeys.REMOTE_AGENTS,
    AppRoute.VoiceSettings::class to SettingsKeys.VOICE,
    AppRoute.AppearanceSettings::class to SettingsKeys.APPEARANCE,
    AppRoute.UsageStats::class to SettingsKeys.USAGE_STATS,
    AppRoute.DataBackup::class to SettingsKeys.DATA_BACKUP,
    AppRoute.Haptics::class to SettingsKeys.HAPTICS,
    AppRoute.Tools::class to SettingsKeys.TOOLS,
    AppRoute.MediaIndexSettings::class to SettingsKeys.MEDIA_INDEX,
    AppRoute.AgentTaskPreference::class to SettingsKeys.AGENT_TASK_PREFERENCE,
    AppRoute.LinuxEnvironment::class to SettingsKeys.LINUX_ENVIRONMENT,
    AppRoute.Workspace::class to SettingsKeys.WORKSPACE,
    AppRoute.LiveActivitySettings::class to SettingsKeys.LIVE_ACTIVITY,
    AppRoute.SystemTakeover::class to SettingsKeys.SYSTEM_TAKEOVER,
    AppRoute.Permissions::class to SettingsKeys.PERMISSION_HEALTH,
    AppRoute.SystemEnhance::class to SettingsKeys.SYSTEM_ENHANCE,
    AppRoute.About::class to SettingsKeys.ABOUT,
)

internal fun settingsAnchorKeyForRoute(route: AppRoute?): String? =
    route?.let { settingsDetailAnchorKeys[it::class] }

/**
 * 右栏专用转场：保留官方 `MiuixDefault` 的层级压深（下层 1/4 视差），但整段动画都关在右栏里。
 *
 * `d <= 0`：入场层从本栏右缘滑入（像素对齐，沿用官方公式）。
 * `d > 0`：被覆盖层向左视差 1/4 栏宽 —— 这就是「下层压深」的位移成分。
 * 官方公式还会把下层 alpha 衰减到 0.9，这里**刻意不抄**：本应用 windowBackground 是透明的，
 * 右栏背后就是主界面，一旦下层半透明就会淡淡漏出主界面内容（用户实测）。位移不变、
 * 全程不透明，压深照样成立，而且视觉上更干净。
 * 官方公式会让下层左缘越过右栏左边界，双栏里会盖到左栏上，因此由
 * [SettingsWideHost] 的 `clipToBounds()` 把越界部分裁掉：压深效果保留，左栏不受影响。
 * 右栏是直角 pane，圆角动画与整屏 scrim 另由 [SettingsDetailPaneEffects] 关掉。
 *
 * 取证：miuix-nav-android 0.9.4 sources jar
 * `commonMain/top/yukonga/miuix/kmp/nav/transition/NavTransitions.kt` 的 `MiuixDefault`
 * 与私有 `coverProgress(d) = d.coerceIn(0f, 1f)`（私有函数不能引用，这里内联同一表达式）；
 * `navGraphicsTransition(opaqueDepth, dismissDirection, motion, scrim, block)` 构造函数见
 * 同目录 `NavTransition.kt`（`block` 的 receiver 是 `GraphicsLayerScope`，不写 `alpha`
 * 时保持默认 1f）；`NavTransitionScope.layoutSize/layoutDirection/relativeDepth`
 * 见 `NavTransitionScope.kt`。
 */
internal val SettingsDetailPaneTransition = navGraphicsTransition(opaqueDepth = 1f) { scope ->
    val d = scope.relativeDepth
    val width = scope.layoutSize.width.toFloat()
    val rtl = scope.layoutDirection == LayoutDirection.Rtl
    if (d <= 0f) {
        translationX = ((if (rtl) -1f else 1f) * (-d).coerceIn(0f, 1f) * width)
            .roundToInt().toFloat()
    } else {
        // 官方 MiuixDefault 的覆盖段只取位移：下层压深 = 1/4 视差，不叠 alpha 衰减。
        // 越出右栏左缘的部分由宿主 clipToBounds 裁切，绝不盖到左栏。
        translationX = (if (rtl) 1f else -1f) * d.coerceIn(0f, 1f) * width * 0.25f
    }
}

/**
 * 右栏 effects：直角 pane，不要系统圆角裁切动画，也不要整屏 scrim。
 *
 * `NavDisplayEffects.None` = `enableCornerClip=false / dimAmount=0f / 不拦输入`。
 * 取证：miuix-nav-android 0.9.4 sources jar
 * `commonMain/top/yukonga/miuix/kmp/nav/core/NavDisplayEffects.kt` 的 companion `None`；
 * 圆角裁切由 `NavDisplay.kt:875-895` 的 `clipModifier` 按 `enableCornerClip` 开关。
 */
internal val SettingsDetailPaneEffects: NavDisplayEffects = NavDisplayEffects.None

/**
 * 瞬时换页转场：左栏点选二级页、搜索层压/退栈时用。
 *
 * `NavTransitions.None` 是官方预设：`navGraphicsTransition(opaqueDepth = 1f)` 且
 * transform 留空、「identity transform, instant swap」，见
 * `miuix-nav-android-0.9.4-sources.jar!/commonMain/.../NavTransitions.kt`。
 * 只在这些瞬时换页期间生效，三级页的 push/pop 立刻切回 [SettingsDetailPaneTransition]；
 * 整栈替换另由右栏 display 的 `key` 重建兜底，连 leaving 层的收敛都不跑。
 */
internal val SettingsDetailInstantTransition = NavTransitions.None

/**
 * 右栏兜底空态：栈被异常恢复成空时的占位，正常进入设置页不会出现（默认显示第一个子页）。
 *
 * 只用官方 M3 文本与颜色令牌，不新建形状或尺寸；`Surface` 与整页骨架同用
 * `surfaceContainer`，避免两栏之间出现色块断层。
 */
@Composable
internal fun SettingsDetailEmptyPane(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.settings_detail_empty_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
