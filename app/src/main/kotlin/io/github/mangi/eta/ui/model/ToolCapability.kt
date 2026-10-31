package io.github.mangi.eta.ui.model

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.AgentFileSystemTier
import io.github.mangi.eta.agent.model.AgentMediaSearchToolCatalog
import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.config.Prefs

/**
 * 界面侧的工具能力（capability）：**一张卡 = 一个开关**。
 *
 * 与 [AgentToolGroups] 是两个不同的关心点，不要合并：
 * - [AgentToolGroups] 面向模型，按 token 经济把工具拆细（日历、设备状态各有独立延迟组，
 *   一次日程任务不必背整组设备写工具）；只有模型看得见。
 * - 这里面向用户，按心智合并（日历、设备状态都属「个人数据」）。设置页与工具页**只**消费这份定义。
 *
 * 为什么必须有这一层：两者曾经强行 1:1（`ToolGroupSwitch.keyFor(groupId)`），
 * 结果是 9 个组只有 7 个真实开关——`calendar` 与 `device` 共用 `agent_device_tools`，
 * `device_state` 与 `personal` 共用 `agent_personal_tools`。用户关掉「日历」那张卡，
 * 「设备控制」也会跟着关。界面分组和模型分组本来就该分开。
 *
 * 不变量（由 `ToolCapabilityTaxonomyTest` 钉住）：[ToolCapabilities.all] 的 `groupIds`
 * 必须**恰好一次**铺满 [AgentToolGroups.groups]，且 `switchKey` 两两不同。
 */
@Immutable
internal data class ToolCapability(
    /** 稳定 id：LazyColumn item key、日志、测试。不是模型侧组 id。 */
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val icon: ImageVector,
    /** 该能力唯一的开关；写进的是 [Prefs.Keys.LOCAL_AGENT_KEYS] 里的键。 */
    val switchKey: String,
    /** 覆盖的模型组 id；顺序即卡内分组顺序（决定工具在卡里的先后）。 */
    val groupIds: List<String>,
    /** 组内子开关；有则工具页卡片里多一行开关。 */
    val subSwitch: ToolSubSwitch? = null,
)

/**
 * 组内子开关：不属于分组粒度，但确实会整类屏蔽组里的工具（shell 屏蔽命令执行、
 * 媒体搜索屏蔽 semantic_search_media）。放在所属卡片内部，而不是设置页外面——
 * 它是这张卡的细节，不该和能力开关并列。
 */
@Immutable
internal data class ToolSubSwitch(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val switchKey: String,
    /** 被它直接门禁的工具；关闭时这些工具行置灰，避免「开关关着、工具行却亮着」。 */
    val gatedTools: Set<String>,
)

/**
 * 界面能力注册表。增删这里必须同步 `ToolCapabilityTaxonomyTest` 的覆盖断言——
 * 它的作用就是拦住「新加一个模型组，忘了给界面归属」这类漂移。
 */
internal object ToolCapabilities {
    val BASIC: ToolCapability = ToolCapability(
        id = "basic",
        titleRes = R.string.settings_basic_tools,
        summaryRes = R.string.settings_basic_tools_summary,
        icon = Icons.Rounded.Forum,
        switchKey = Prefs.Keys.AGENT_BASIC_TOOLS,
        groupIds = listOf(AgentToolGroups.BASIC_GROUP.id),
    )

    val SCREEN: ToolCapability = ToolCapability(
        id = "screen",
        titleRes = R.string.settings_screen_tools,
        summaryRes = R.string.settings_screen_tools_summary,
        icon = Icons.Rounded.TouchApp,
        switchKey = Prefs.Keys.AGENT_SCREEN_TOOLS,
        groupIds = listOf(AgentToolGroups.SCREEN_GROUP.id),
    )

    val WEB: ToolCapability = ToolCapability(
        id = "web",
        titleRes = R.string.settings_web_tools,
        summaryRes = R.string.settings_web_tools_summary,
        icon = Icons.Rounded.Language,
        switchKey = Prefs.Keys.AGENT_BROWSER_TOOLS,
        groupIds = listOf(AgentToolGroups.WEB_GROUP.id),
    )

    val TERMINAL: ToolCapability = ToolCapability(
        id = "terminal",
        titleRes = R.string.settings_terminal_tools,
        summaryRes = R.string.settings_terminal_tools_summary,
        icon = Icons.Rounded.Terminal,
        switchKey = Prefs.Keys.AGENT_TERMINAL_TOOLS,
        groupIds = listOf(AgentToolGroups.TERMINAL_GROUP.id),
        subSwitch = ToolSubSwitch(
            id = "shell",
            titleRes = R.string.settings_shell_tools,
            summaryRes = R.string.settings_shell_tools_summary,
            switchKey = Prefs.Keys.AGENT_SHELL_TOOLS,
            gatedTools = AgentFileSystemTier.shellTools,
        ),
    )

    val DEVICE: ToolCapability = ToolCapability(
        id = "device",
        titleRes = R.string.settings_device_tools,
        summaryRes = R.string.settings_device_tools_summary,
        icon = Icons.Rounded.Smartphone,
        switchKey = Prefs.Keys.AGENT_DEVICE_TOOLS,
        groupIds = listOf(AgentToolGroups.DEVICE_GROUP.id),
    )

    /**
     * 个人数据：个人内容 + 设备状态读取 + 日程读写。
     *
     * 三个模型组共用一个开关是**有意的**：它们读写的都是「我的东西」，用户不会为了
     * 建一条日程去理解「设备控制」和「个人数据」的区别。分组顺序决定卡片里的行序：
     * 先个人内容，再设备状态，最后日程——日历写在 `toolItemCatalog` 里排在 device 段，
     * 不按 [groupIds] 顺序分组的话会跑到卡片最前面。
     */
    val PERSONAL: ToolCapability = ToolCapability(
        id = "personal",
        titleRes = R.string.settings_personal_tools,
        summaryRes = R.string.settings_personal_tools_summary,
        icon = Icons.Rounded.Badge,
        switchKey = Prefs.Keys.AGENT_PERSONAL_TOOLS,
        groupIds = listOf(
            AgentToolGroups.PERSONAL_GROUP.id,
            AgentToolGroups.DEVICE_STATE_GROUP.id,
            AgentToolGroups.CALENDAR_GROUP.id,
        ),
        subSwitch = ToolSubSwitch(
            id = "media_search",
            titleRes = R.string.settings_media_search_tools,
            summaryRes = R.string.settings_media_search_tools_summary,
            switchKey = Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS,
            gatedTools = setOf(AgentMediaSearchToolCatalog.SEARCH_MEDIA),
        ),
    )

    val EXTENSIONS: ToolCapability = ToolCapability(
        id = "extensions",
        titleRes = R.string.settings_extensions_tools,
        summaryRes = R.string.settings_extensions_tools_summary,
        icon = Icons.Rounded.Extension,
        switchKey = Prefs.Keys.AGENT_EXTENSIONS_TOOLS,
        groupIds = listOf(AgentToolGroups.EXTENSIONS_GROUP.id),
    )

    /** 工具页与设置页摘要的唯一顺序来源。 */
    val all: List<ToolCapability> = listOf(BASIC, SCREEN, WEB, TERMINAL, DEVICE, PERSONAL, EXTENSIONS)

    fun forGroup(groupId: String): ToolCapability? = all.firstOrNull { groupId in it.groupIds }
}
