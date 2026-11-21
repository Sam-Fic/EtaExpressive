// 系统助手屏幕识别的截图后端选择（系统接管设置页读写，本地 Agent 配置存储）。
// 入口读屏的主源是 Eta 无障碍服务的 takeScreenshotOfWindow（agent/accessibility），
// 不依赖本枚举；这里只管理「无障碍取不到帧时的兜底后端」：自动按 root → Shizuku
// 顺序探测（root / Shizuku 后端移植自 badnng/Hyper-pick-up-code，AGPL-3.0-only，
// 合规登记见 docs/THIRD_PARTY_NOTICES.md）。共享屏幕（MediaProjection 授权）只在
// 取码管理页的手动「截屏识别」行使用（授权结果必须由 Activity 接收），不经本枚举。

package io.github.mangi.eta.agent.capture

/** 截图后端：无障碍兜底方式。AUTO 按 root → Shizuku 顺序探测可用性。 */
enum class AssistantScreenshotBackend {
    AUTO,
    ROOT,
    SHIZUKU,
}
