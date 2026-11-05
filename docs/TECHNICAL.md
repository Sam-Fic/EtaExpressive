# Eta 技术索引

本文件只做入口索引，不放实现说明；强制规则见 [`docs/RULES.md`](RULES.md)。除特别注明外，路径均相对 `app/src/main/kotlin/io/github/mangi/eta/`。

## 进程与入口

- Hook 入口：`ModuleMain.kt`；按进程过滤后 `detach()`，只负责生命周期与安装汇总。
- App 运行时：`agent/runtime/AgentRuntimeService.kt`；主进程持有会话、工具与流式结果。
- 数字助理进程：`:voice`、`:voice_session`、`:recognition` 只初始化偏好；浮窗与键盘输入在 `agent/voice/EtaAssistantOverlayService.kt`。

## Hook 与日志

- 注册与诊断：`core/HookRegistrar.kt`；每域稳定 ID、`PROTECTIVE`、统一优先级，结果分 `INSTALLED/MISSING/FAILED/SKIPPED`。
- 日志后端：`core/AgentLogger.kt`；App 与 Runtime 走 logcat，Hook 走 Xposed 日志，业务不得直调 `android.util.Log`。
- Release 裁剪：`app/proguard-rules.pro` 是唯一事实来源；只裁 Eta 自有 `DEBUG`，禁止记录 Prompt、密钥、命令、路径与正文。

## 数字助理

- 系统入口：Manifest 注册 `VoiceInteractionService`、`VoiceInteractionSessionService`、`RecognitionService`；当前不执行 ASR/TTS。
- 浮窗：`agent/voice/EtaAssistantOverlayService.kt` + `agent/overlay/`；全屏 edge-to-edge，提交文本交 `AgentRuntimeClient`。

## 系统服务 Hook

- 电源键与默认助理：`hook/system/`、`hook/hyperos/`、`hook/breeno/`；三态目标为小布 / Gemini / Eta，默认不改小布，失败立即回退。
- 无障碍保护：`hook/system/` + `agent/accessibility/`；默认关闭，只维护 Eta 自身，断连最多三轮并冷却，ADB 开关 `eta_accessibility_protection_enabled`。
- 一圈即搜：`hook/system/`、`hook/colordirect/`、`hook/google/`；ContextualSearch 补齐、机型伪装与资格补齐属不可关闭的底层依赖。

## 外部应用适配

- 小布记忆：`hook/aimemory/`；进程内只读 Provider 桥按固定查询执行，仅接受 UID 0，桥不可用时才走 Root 快照。
- 超级小爱：`hook/xiaoai/`；包名锁定 `com.miui.voiceassist`，三条系统桥均能力探测、自降级，结果走原生卡片与 TTS。
- Google App：`hook/google/` + `systemizer/GoogleAppSystemizerInstaller.kt`；资格伪装仅限 Google 进程，系统化需用户在设置页确认。

## 配置与实时生效

- Runtime 配置：`config/` + `data/repository/`；默认思考、浏览、设备直达、敏感读取与终端工具在请求及每次工具执行前读取。
- Hook 配置：`EtaApp` + `core/HookRegistrar.kt`；经 `XposedService.getRemotePreferences()` 写 LSPosed，服务未连接时开关不可改，Hook 回调按当前值执行。

## 导航与界面

- 根导航：`ui/app/AgentAppRoot.kt` + `ui/navigation/AppRoute.kt`；miuix `NavDisplay` + `NavBackStack`（`AppRoute : NavKey`），禁止引入 `androidx.navigation`（见 `docs/RULES.md` §3.3）。
- 外观：`ui/adaptive/` + `ui/app/`；`eta_settings` DataStore 统一解析跟随系统、明暗、Monet、强调色与纯黑，浮窗同源且不应用界面缩放。
- 设计基线：`ui/adaptive/EtaLoading.kt`、`EtaDimens.kt`、`EtaStatus.kt`、`EtaTooltips.kt`；尺寸、状态色、Tooltip 与 Loading 选型见 `docs/RULES.md` §2。

## 个人数据与文件

- 个人数据直达：`agent/context/`、`agent/device/`、`agent/tool/`；固定 Provider、投影字段与排序，不暴露任意 URI / 表名 / SQL，结果不持久化进会话。
- 文件视觉：`agent/tool/AgentImageTools.kt` + `agent/media/`；Root 读取到临时缓存，回合结束即删，每轮最多一次 `read_image`。

## 内置浏览器

- `agent/browser/`（含 `ported/browser/`）；共享离屏 WebView，可加载 JS、操作元素与截图，不做额外 URL/DNS/重定向拦截；外部链接仍走 `open_uri`。

## 终端、Linux 与下载

- 终端：`agent/terminal/` + `ui/screens/terminal/`；分 `android`、Alpine musl、Debian glibc 三环境，守护任务与 PTY / 块式终端各有独立会话。
- 断点续传：`core/ResumableDownload.kt`；`.part` + `.meta` 分离、响应头二次校验、校验器不一致即重下，细则见 `docs/RULES.md` §3.2。
- 版本与依赖：直接依赖以 `gradle/libs.versions.toml` 为准；Compose foundation/ui/animation 由 material3 传递解析，不在 toml 直接声明。

## 长期记忆与 Thinking

- 长期记忆：`agent/memory/AgentMemoryContext.kt` + `ui/screens/memory/AgentMemoryScreen.kt`；单一私有 `MEMORY.md`，按需注入，上限 1 MiB，`AtomicFile` 原子更新。
- Thinking Effort：`agent/model/ProviderReasoning.kt` + `agent/model/ResponsesRequestBuilder.kt` + `ui/model/`；远端元数据优先，其次内置目录与家族规则，兼容旧 `thinkingEnabled` 布尔。

## 聊天流式渲染

- 状态合并：`ui/app/AgentAppState.kt`；SSE 文本增量按 50ms 合并，思考与工具事件立即刷新。
- 显现：`ui/markdown/SmoothTextReveal.kt` + `ui/components/SmoothTextReveal.kt`；`DrawModifierNode` 按字素裁剪已排版文本，自适应 48–240 字素/秒，稳定后恢复完整 Markdown 与选择。
- 输入器：`ui/components/AgentChatInputBar.kt` + `ui/components/AgentChatComposerM3.kt`；state-based `TextFieldState` 在组件内持有编辑缓冲。

## 功耗与预期行为

- 功耗：`hook/` + `agent/`；不轮询、不保活 Google 进程、热路径只保留已验证 Hook，高频成功路径用 `DEBUG` 并由 R8 裁剪。
- 预期行为：目标为小布时保持厂商原逻辑；Eta 已是默认助理时长按电源键打开全屏浮窗并聚焦输入；未成为默认且自动设置关闭时直接回退小布。
