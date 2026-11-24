package io.github.mangi.eta.config

/**
 * 需要实时活动形态的提醒（任务完成岛提醒、取码提醒等）的呈现方式。
 * 只由 App 进程的通知链路消费，因此存本地 Agent 配置，不依赖 LSPosed。
 *
 * - [NATIVE]：普通通知，不注入任何实时活动形态；
 * - [XIAOMI_ISLAND]：miui.focus 模板 16 私有焦点路径（HyperOS 超级岛，含 Android 16
 *   以下的 legacy 焦点协议路径）；仅小米且岛可用时生效，否则落回原生；不叠加 promoted；
 * - [COLOROS_FLUID]：纯官方 promoted-ongoing 通道（Android 16+），由厂商各自桥接——
 *   ColorOS 15+ 即流体云，HyperOS 上等效超级岛胶囊，其他厂商尽力渲染。
 *   不注入 miui.focus 私有参数，所以非小米设备也是一等选项；与小米超级岛路径互斥。
 *
 * 默认 [XIAOMI_ISLAND]：与既有岛终态的默认行为一致；非小米设备上各消费方
 * 的可用性门控会自然落回 [NATIVE]。
 */
internal enum class LiveActivityStyle(
    val persistedValue: String,
) {
    NATIVE("native"),
    XIAOMI_ISLAND("xiaomi_island"),
    COLOROS_FLUID("coloros_fluid"),
    ;

    companion object {
        fun resolve(persistedValue: String?): LiveActivityStyle =
            entries.firstOrNull { it.persistedValue == persistedValue } ?: XIAOMI_ISLAND
    }
}
