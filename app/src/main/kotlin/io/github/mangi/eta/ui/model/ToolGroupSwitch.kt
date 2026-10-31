package io.github.mangi.eta.ui.model

import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.config.Prefs

/**
 * 模型组 → 用户开关的映射。
 *
 * **开关的单位是能力（[ToolCapabilities]），不是模型组**：[io.github.mangi.eta.agent.model.AgentToolGroups]
 * 为了省 token 把工具拆得比用户心智更细，多个组可能归同一个开关（日历、设备状态都归「个人数据」）。
 * 这里不再自己维护第二份 `when`，一律委托 [ToolCapabilities.forGroup]，否则界面和模型侧迟早
 * 出现「卡片写个人数据、目录按设备判」的漂移。
 */
internal object ToolGroupSwitch {
    /** 模型组所属能力的开关；未知组（已退役的展示分组）返回 null。 */
    fun keyFor(groupId: String): String? = ToolCapabilities.forGroup(groupId)?.switchKey

    /** 该分组是否还能提供开关；未知组不渲染开关行。 */
    fun showsInGroup(groupId: String): Boolean = keyFor(groupId) != null

    fun isEnabled(key: String): Boolean = Prefs.isEnabled(key)

    /**
     * 写开关。与设置页的 `SwitchPref` 走同一个原语（`putBooleanSync` → `commit()`），
     * 两处写入的落盘语义与失败可见性因此完全一致：以前这里用 `apply()`，写失败也不报，
     * 用户下次进页面才发现开关弹回去了。
     *
     * @return 是否提交成功；失败时调用方从 Prefs 重读即可把开关弹回原位。
     */
    fun setEnabled(key: String, enabled: Boolean): Boolean {
        val prefs = Prefs.localAgentPreferences() ?: return false
        val committed = io.github.mangi.eta.ui.putBooleanSync(prefs, key, enabled)
        if (committed) {
            runCatching { Prefs.reconcileAgentPreferences(EtaApp.serviceInstance) }
        }
        return committed
    }
}
