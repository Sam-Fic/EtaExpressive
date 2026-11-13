package io.github.mangi.eta.ui.navigation

import androidx.compose.runtime.snapshots.Snapshot
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlin.reflect.KClass
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * AppRoute 的具体类型集合。miuix 的 NavDisplay 按具体类型（而不是 sealed 基类）登记目标
 * （`entry<T>` 只精确匹配 T::class），所以返回栈里的每个条目都必须在容器里显式登记。
 *
 * 这里**不能**用 `AppRoute::class.java.permittedSubclasses` 这类运行时反射做登记：
 * R8 混淆下会静默失效。改为编译期显式登记，新增路由时需同步维护本表
 * （有单元测试对照 permittedSubclasses 防漏登）；容器兜底也用它判断
 * 「这个 key 有没有可渲染的 entry」。
 */
private val appRouteTypes: List<KClass<out AppRoute>> = listOf(
    AppRoute.Home::class,
    AppRoute.Browser::class,
    AppRoute.Terminal::class,
    AppRoute.Tools::class,
    AppRoute.AgentTaskPreference::class,
    AppRoute.VirtualDisplay::class,
    AppRoute.VirtualDisplayRecovery::class,
    AppRoute.Haptics::class,
    AppRoute.Skills::class,
    AppRoute.Permissions::class,
    AppRoute.SystemEnhance::class,
    AppRoute.Settings::class,
    AppRoute.SettingsDetailEmpty::class,
    AppRoute.SettingsSearch::class,
    AppRoute.SystemTakeover::class,
    AppRoute.About::class,
    AppRoute.AboutCredits::class,
    AppRoute.SpeechSettings::class,
    AppRoute.TtsSettings::class,
    AppRoute.VoiceSettings::class,
    AppRoute.DoubaoVoices::class,
    AppRoute.MimoVoices::class,
    AppRoute.AuxiliaryVision::class,
    AppRoute.TitleModel::class,
    AppRoute.ErrorReconnectSettings::class,
    AppRoute.LiveActivitySettings::class,
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
    AppRoute.McpServerAdd::class,
    AppRoute.ModelProviderDetail::class,
    AppRoute.ModelProviderAuthMethod::class,
    AppRoute.ModelProviderNew::class,
    AppRoute.ModelProviderModelEdit::class,
    AppRoute.ContextCompression::class,
    AppRoute.DecisionModel::class,
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
    AppRoute.ToolRecipeEdit::class,
    AppRoute.RemoteAgents::class,
    AppRoute.RemoteAgentEditor::class,
)

/** 显式登记表的类型集合（容器兜底用；O(1) 判重，避免每次重组线性扫描 66 项）。 */
private val appRouteTypeSet: Set<KClass<out AppRoute>> = appRouteTypes.toSet()

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
 * 容器兜底：该返回栈条目是否有可渲染的 entry。
 *
 * miuix 0.9.4 的 `NavEntryBuilder.build()` 对未登记的类型直接 `error(...)`（且 `entryProvider`
 * 是 internal，无法自己拼一个 fallback provider），所以宿主在渲染前先用它筛一遍，
 * 把不可渲染的条目挡在 NavDisplay 之外，交给最小错误页。
 */
internal fun isKnownAppRoute(key: NavKey): Boolean =
    key is AppRoute && key::class in appRouteTypeSet

/**
 * 用 miuix [NavBackStack]（`SnapshotStateList<NavKey>`）实现的路由栈，公开接口与旧实现保持一致。
 */
class AgentNavigator(
    private val backStack: NavBackStack,
) {
    fun push(route: AppRoute) {
        if (backStack.none { it == route }) {
            backStack.add(route)
        }
    }

    /**
     * 换掉栈顶 / 跳到已有的那一层。
     *
     * 目标已在栈里：截断到它成为栈顶（一次快照内完成，只播一段退场）。
     * 否则整栈清空再入栈：不能只替换栈顶——从设置 / 管理页打开助手会话时，否则会留下
     * Home → Chat 两层对话界面，返回时先弹掉 Chat 落回 Home，而不是直接走退出判定。
     */
    fun replace(route: AppRoute) {
        val existing = backStack.indexOfFirst { it == route }
        if (existing >= 0) {
            Snapshot.withMutableSnapshot {
                while (backStack.size > existing + 1) {
                    backStack.removeLastOrNull()
                }
            }
            return
        }

        if (backStack.isEmpty()) {
            backStack.add(route)
            return
        }
        // 一次快照内「清空 + 入栈」，避免组合期读到一帧空栈（NavDisplay 要求栈非空）。
        Snapshot.withMutableSnapshot {
            backStack.clear()
            backStack.add(route)
        }
    }

    /** 返回栈里是否还有上一级。 */
    fun canPop(): Boolean = backStack.size > 1

    fun pop(): Boolean {
        // 「有没有上一级」与「这次有没有弹成」分开判断，调用方据此决定是否走退出分支。
        if (!canPop()) {
            AndroidAgentLogger.info("nav.pop -> popped=false (at root)")
            return false
        }
        val stack = routes()
        val popped = backStack.removeLastOrNull() != null
        // 导航层级诊断：记录返回栈映射结果与出栈结果，用于排查应用内返回按钮的层级误判。
        AndroidAgentLogger.info(
            "nav.pop size=${stack.size}" +
                " [${stack.joinToString(separator = ",") { it::class.simpleName.orEmpty() }}]" +
                " -> popped=$popped",
        )
        return popped
    }

    fun popToHome() {
        if (backStack.firstOrNull() !is AppRoute.Home) {
            // 返回栈根不是 Home（历史栈或异常恢复）：整体重置为只有 Home。
            Snapshot.withMutableSnapshot {
                backStack.clear()
                backStack.add(AppRoute.Home)
            }
            return
        }
        Snapshot.withMutableSnapshot {
            while (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
        }
    }

    /**
     * 回到承载对话的页面（[AppRoute.Home]）。
     *
     * 搜索命中要「点开就落在对话里」：从管理聊天页跳进来的栈是
     * `Home → ManageChats → MessageSearch`，逐级 pop 会停在管理聊天页，
     * 所以这里一次性截断到承载对话的那一层。
     * 一次快照内截断，只播一段转场，不会出现逐级退场的多段动画。
     * 栈里没有承载对话的页面（异常栈）时退到首页。
     */
    fun popToConversationHost(): Boolean {
        val target = backStack.indexOfLast { it is AppRoute.Home }
        if (target < 0) {
            popToHome()
            return false
        }
        // 目标已经在栈顶：与 popBackStack(id, inclusive = false) 一样视为「没有弹出」。
        if (target == backStack.lastIndex) {
            AndroidAgentLogger.info("nav.popToConversationHost -> popped=false")
            return false
        }
        Snapshot.withMutableSnapshot {
            while (backStack.size > target + 1) {
                backStack.removeLastOrNull()
            }
        }
        AndroidAgentLogger.info("nav.popToConversationHost -> popped=true")
        return true
    }

    fun current(): AppRoute? = routes().lastOrNull()

    /** 返回栈里的路由（不含任何非 App 路由条目）。 */
    private fun routes(): List<AppRoute> = backStack.filterIsInstance<AppRoute>()
}
