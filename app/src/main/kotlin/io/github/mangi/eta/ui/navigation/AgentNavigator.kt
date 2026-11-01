package io.github.mangi.eta.ui.navigation

import android.annotation.SuppressLint
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.toRoute
import kotlin.reflect.KClass

/**
 * AppRoute 的具体类型集合。NavHost 是按具体类型（而不是 sealed 基类）注册目标的，
 * 所以把返回栈条目还原成路由时必须用具体类型的序列化器。
 *
 * 这里**不能**用 `AppRoute::class.java.permittedSubclasses` 或
 * [androidx.navigation.NavDestination.hasRoute] 这类运行时反射/序列化器哈希：
 * R8 混淆下二者会静默失效（映射结果为空），应用内返回按钮因此把任何页面
 * 误判成根页面。改为编译期显式登记 + destination.route 字符串匹配，
 * 新增路由时需同步维护本表（有单元测试对照 permittedSubclasses 防漏登）。
 */
private val appRouteTypes: List<KClass<out AppRoute>> = listOf(
    AppRoute.Home::class,
    AppRoute.Chat::class,
    AppRoute.Browser::class,
    AppRoute.Terminal::class,
    AppRoute.Tools::class,
    AppRoute.AgentTaskPreference::class,
    AppRoute.VirtualDisplayRecovery::class,
    AppRoute.Haptics::class,
    AppRoute.Skills::class,
    AppRoute.Permissions::class,
    AppRoute.SystemEnhance::class,
    AppRoute.Settings::class,
    AppRoute.SettingsSearch::class,
    AppRoute.SystemTakeover::class,
    AppRoute.About::class,
    AppRoute.SpeechSettings::class,
    AppRoute.TtsSettings::class,
    AppRoute.VoiceSettings::class,
    AppRoute.DoubaoVoices::class,
    AppRoute.MimoVoices::class,
    AppRoute.AuxiliaryVision::class,
    AppRoute.TitleModel::class,
    AppRoute.ErrorReconnectSettings::class,
    AppRoute.WebToolsSettings::class,
    AppRoute.SubAgents::class,
    AppRoute.SubAgentPresetDetail::class,
    AppRoute.VoiceModeSettings::class,
    AppRoute.AppearanceSettings::class,
    AppRoute.DataBackup::class,
    AppRoute.Memory::class,
    AppRoute.AssistantMemory::class,
    AppRoute.LinuxEnvironment::class,
    AppRoute.SharedFolders::class,
    AppRoute.SharedFolderPicker::class,
    AppRoute.Workspace::class,
    AppRoute.LinuxFiles::class,
    AppRoute.ModelProviders::class,
    AppRoute.ModelProviderSearch::class,
    AppRoute.McpServers::class,
    AppRoute.McpServerDetail::class,
    AppRoute.ModelProviderDetail::class,
    AppRoute.ModelProviderAuthMethod::class,
    AppRoute.ModelProviderNew::class,
    AppRoute.ContextCompression::class,
    AppRoute.Assistants::class,
    AppRoute.AssistantSearch::class,
    AppRoute.AssistantEdit::class,
    AppRoute.UsageStats::class,
    AppRoute.ManageChats::class,
    AppRoute.ManageFolders::class,
    AppRoute.ConversationTrash::class,
    AppRoute.ConversationTrashSearch::class,
    AppRoute.MessageSearch::class,
    AppRoute.ConversationTokenUsage::class,
    AppRoute.SubAgentStatus::class,
    AppRoute.TerminalDaemonTasks::class,
    AppRoute.MediaSearch::class,
    AppRoute.MediaIndexSettings::class,
    AppRoute.MobileActionsSettings::class,
    AppRoute.MobileActions::class,
    AppRoute.ToolRecipes::class,
    AppRoute.RemoteAgents::class,
    AppRoute.RemoteAgentEditor::class,
)

/** 模式串（destination.route）→ 路由类型；导航目标是复用的，所以只需解析一次。 */
private val routeTypesByPattern = mutableMapOf<String, KClass<out AppRoute>?>()

/** 按模式串匹配 [AppRoute] 具体类型：全等（无参路由）或 `序列名/` 前缀（带参路由）。 */
private fun matchRouteType(routePattern: String): KClass<out AppRoute>? =
    appRouteTypes.firstOrNull { kClass ->
        val qualifiedName = kClass.qualifiedName ?: return@firstOrNull false
        routePattern == qualifiedName || routePattern.startsWith("$qualifiedName/")
    }

/** 供单元测试对照显式路由表与 sealed 子类元数据。 */
internal val appRouteTypesForTest: List<KClass<out AppRoute>> get() = appRouteTypes

/** 供单元测试复用模式串匹配逻辑（不做返回栈反序列化）。 */
internal fun routePatternOf(route: AppRoute, pattern: () -> String): KClass<out AppRoute>? =
    matchRouteType(pattern())

/**
 * 把返回栈条目的 destination.route 模式串匹配到 [AppRoute] 具体类型。
 *
 * 模式串是 `序列名`（无参路由）或 `序列名/{参数}`（带参路由）；类名受 proguard
 * keep 规则保护，所以拿 [KClass.qualifiedName] 做前缀匹配即可，
 * 不依赖 hasRoute 的序列化器哈希契约。不是 App 路由（例如导航图本身）时返回 null。
 */
internal fun NavBackStackEntry.toAppRoute(): AppRoute? {
    val routePattern = destination.route.orEmpty()
    if (routePattern.isEmpty()) return null
    val type = routeTypesByPattern.getOrPut(routePattern) { matchRouteType(routePattern) }
        ?: return null
    return toRoute(type)
}

/**
 * 用 androidx [NavController] 实现的路由栈，公开接口与旧实现保持一致。
 */
class AgentNavigator(
    private val navController: NavController,
) {
    fun push(route: AppRoute) {
        if (routes().none { it == route }) {
            navController.navigate(route)
        }
    }

    // NavController.currentBackStack 是 RestrictedApi：导航库没有公开「观察完整返回栈」的
    // Flow 替代，这里只读取快照做路由定位，抑制并记录理由。
    @SuppressLint("RestrictedApi")
    fun replace(route: AppRoute) {
        val entries = navController.currentBackStack.value
        val existing = entries.indexOfFirst { it.toAppRoute() == route }
        if (existing >= 0) {
            while (entries.size > existing + 1) {
                if (!navController.popBackStack()) break
            }
            return
        }

        val topEntry = entries.lastOrNull { it.toAppRoute() != null }
        if (topEntry == null) {
            navController.navigate(route)
            return
        }
        // 换掉栈顶：一次导航里弹出旧页再入栈，避免出现中间态。
        navController.navigate(route) {
            popUpTo(topEntry.destination.id) { inclusive = true }
        }
    }

    fun pop(): Boolean {
        val stack = routes()
        val popped = stack.size > 1 && navController.popBackStack()
        // 导航层级诊断：记录返回栈映射结果与出栈结果，用于排查应用内返回按钮的层级误判。
        io.github.mangi.eta.core.AndroidAgentLogger.info(
            "nav.pop size=${stack.size}" +
                " [${stack.joinToString(separator = ",") { it::class.simpleName.orEmpty() }}]" +
                " -> popped=$popped",
        )
        return popped
    }

    fun popToHome() {
        if (routes().firstOrNull() !is AppRoute.Home) {
            // 返回栈根不是 Home（历史栈或异常恢复）：整体重置为只有 Home。
            navController.navigate(AppRoute.Home) {
                popUpTo(navController.graph.id) { inclusive = true }
            }
            return
        }
        while (routes().size > 1) {
            if (!navController.popBackStack()) break
        }
    }

    /**
     * 回到承载对话的页面（[AppRoute.Home] 或 [AppRoute.Chat]）。
     *
     * 搜索命中要「点开就落在对话里」：从管理聊天页跳进来的栈是
     * `Home → ManageChats → MessageSearch`，逐级 pop 会停在管理聊天页，
     * 所以这里一次性弹回承载对话的那一层（栈里有 Home 和 Chat 时取更靠上的那个）。
     * 栈里没有承载对话的页面（异常栈）时退到首页。
     */
    @SuppressLint("RestrictedApi")
    fun popToConversationHost(): Boolean {
        val entries = navController.currentBackStack.value
        val target = entries.indexOfLast { entry ->
            val route = entry.toAppRoute()
            route is AppRoute.Home || route is AppRoute.Chat
        }
        if (target < 0) {
            popToHome()
            return false
        }
        // 一次 popBackStack 弹到目标 destination：只播一次转场，不会出现逐级退场的多段动画。
        val popped = navController.popBackStack(entries[target].destination.id, inclusive = false)
        io.github.mangi.eta.core.AndroidAgentLogger.info(
            "nav.popToConversationHost -> popped=$popped",
        )
        return popped
    }

    fun current(): AppRoute? = routes().lastOrNull()

    /** 返回栈里的路由（不含导航图条目）。 */
    @SuppressLint("RestrictedApi")
    private fun routes(): List<AppRoute> = navController.currentBackStack.value.mapNotNull { it.toAppRoute() }
}
