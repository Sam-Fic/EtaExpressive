# Eta 技术索引

本文件只做入口索引，不放实现说明；强制规则见 [`docs/RULES.md`](RULES.md)。除特别注明外，路径均相对 `app/src/main/kotlin/io/github/mangi/eta/`。

## 进程与入口

- Hook 入口：`ModuleMain.kt`；按进程过滤后 `detach()`，只负责生命周期与安装汇总。
- App 运行时：`agent/runtime/AgentRuntimeService.kt`；主进程持有会话、工具与流式结果。
- 数字助理进程：`:voice`、`:voice_session`、`:recognition` 只初始化偏好；浮窗与键盘输入在 `agent/voice/EtaAssistantOverlayService.kt`。
- 运行时细节：会话、工具与后台任务的行为依据见 `docs/AGENT_RUNTIME.md`。

## Hook 与日志

- 注册与诊断：`core/HookRegistrar.kt`；每域稳定 ID、`PROTECTIVE`、统一优先级，结果分 `INSTALLED/MISSING/FAILED/SKIPPED`。
- 日志后端：`core/AgentLogger.kt`；App 与 Runtime 走 logcat，Hook 走 Xposed 日志，业务不得直调 `android.util.Log`。
- Release 裁剪：`app/proguard-rules.pro` 是唯一事实来源；只裁 Eta 自有 `DEBUG`，禁止记录 Prompt、密钥、命令、路径与正文。
- 阶段诊断：`agent/runtime/` + `core/`；单调时钟，每次 attempt 独立预算但总时限 120s，失败日志先于取消检查，不记提示词/正文/凭据/URL。
- 工具调用诊断：`ToolCallDiag`；明细 128 条、关键阶段 64 条、单行 3968 字符；`get_task_result` 默认首 4000 UTF-16 单位并按 offset 分页。

## 数字助理

- 系统入口：Manifest 注册 `VoiceInteractionService`、`VoiceInteractionSessionService`、`RecognitionService`；当前不执行 ASR/TTS。
- 浮窗：`agent/voice/EtaAssistantOverlayService.kt` + `agent/overlay/`；全屏 edge-to-edge，提交文本交 `AgentRuntimeClient`。
- 豆包 ASR：`agent/voice/doubao/`；v3 `bigmodel_async` 16k PCM16 二进制协议，音色按 API Key 指纹隔离，仅状态 2/4、model_type 4/5 的 ICL 2.0 可朗读，无效音色明确失败且不回退，SK 用 Keystore AES-GCM 加密、AK 作 AAD。
- MiMo 个人音色：`agent/voice/mimo/MimoPersonalVoices.kt`；只存本机参考音频、不建云端训练，data URI + wav、`stream=false`，样本缺失即失败、不换公共音色。
- 流式事件：`agent/voice/`；只接受非空字符串，delta 追加、done 全文替换、空 done 不清空已有输出，日志不记正文/key/response_id。
- 子代理：`agent/` + `data/repository/`；配置用稳定 UUID，`delegate_task` 的 role/agent_id 必须与 worker 同指；并行限额按「提供商 + API 模型名」共享，排队不耗预算；文本/压缩/图片预算 180s、视频 600s，用尽进 `awaiting_decision` 由 `continue_task` 继续；工作区 64 KiB，拒 `..`、Git 元数据与链接，合并需基线未变；自动压缩约 80% 窗口。
- 项目选择：`agent/voice/` + `ui/components/`；IAM `ListProjects` 固定 GET/签名、`Limit=100` 最多 20 页，分页不完整不得冒充完整，`HasPermission=false` 不可选；菜单统一用 M3 `DropdownMenu`（16dp 圆角、112–280dp），不另造弹出层。

## 系统服务 Hook

- 电源键与默认助理：`hook/system/`、`hook/hyperos/`、`hook/breeno/`；三态目标为小布 / Gemini / Eta，默认不改小布，失败立即回退。
- 无障碍保护：`hook/system/` + `agent/accessibility/`；默认关闭，只维护 Eta 自身，断连最多三轮并冷却，ADB 开关 `eta_accessibility_protection_enabled`。
- 一圈即搜：`hook/system/`、`hook/colordirect/`、`hook/google/`；ContextualSearch 补齐、机型伪装与资格补齐属不可关闭的底层依赖。

## 外部应用适配

- 小布记忆：`hook/aimemory/`；进程内只读 Provider 桥按固定查询执行，仅接受 UID 0，桥不可用时才走 Root 快照。
- 超级小爱：`hook/xiaoai/`；包名锁定 `com.miui.voiceassist`，三条系统桥均能力探测、自降级，结果走原生卡片与 TTS。
- Google App：`hook/google/` + `systemizer/GoogleAppSystemizerInstaller.kt`；资格伪装仅限 Google 进程，系统化需用户在设置页确认。
- HyperOS 入口：真机验证与未闭环边界见 `docs/HYPEROS_SYSTEM_ENTRY.md`。

## 虚拟显示

- 协议：`vd/` + runtime；root `app_process` 单显示，abstract socket + uid + token 双认证、单连接、64 KiB 行限，am/input 走 argv；release 须副屏确认为空且不 kill task。
- 只读探针：固定 `mode=read_only/ready=false`，`timeout -k 1s 8s` 包裹，R8 保留 `ReadOnlyProbe`，rootCount 必须大于 0。
- 验证边界：远程校验只证明 JDK25 编译 + JVM 单测 + debug APK，不证明 root/app_process/真机。

## 配置与实时生效

- Runtime 配置：`config/` + `data/repository/`；默认思考、浏览、设备直达、敏感读取与终端工具在请求及每次工具执行前读取。
- Hook 配置：`EtaApp` + `core/HookRegistrar.kt`；经 `XposedService.getRemotePreferences()` 写 LSPosed，服务未连接时开关不可改，Hook 回调按当前值执行。
- 缓存断点：`agent/model/AnthropicPromptCaching.kt`；默认 1h，最多 4 个断点（工具尾部、指令边界、system 尾部、末条 user）；设置只取 5m/1h/none，只有 `cache_read>0` 才算命中。
- 写入约束：`config/` + `data/`；SharedPreferences `commit=false` 不代表已持久化，必须做脏数据阻断；全局 revision 不是 owner 编辑锁；新格式严格校验，坏数据在覆盖前拒绝。
- 子代理配置：`data/repository/`；owner 间隔离，草稿用独立 token，Room 保存成功后才 `confirmBoundDraft`；并行池 key 按 owner 作用域，不互相 resize。

## 导航与界面

- 根导航：`ui/app/AgentAppRoot.kt` + `ui/navigation/AppRoute.kt`；miuix `NavDisplay` + `NavBackStack`（`AppRoute : NavKey`），禁止引入 `androidx.navigation`（见 `docs/RULES.md` §3.3）。
- 外观：`ui/adaptive/` + `ui/app/`；`eta_settings` DataStore 统一解析跟随系统、明暗、Monet、强调色与纯黑，浮窗同源且不应用界面缩放。
- 设计基线：`ui/adaptive/EtaLoading.kt`、`EtaDimens.kt`、`EtaStatus.kt`、`EtaTooltips.kt`；尺寸、状态色、Tooltip 与 Loading 选型见 `docs/RULES.md` §2。
- 消息跳转：`ui/`；箭头按用户消息定位，短按上/下一条提问，长按顶部/底部，12dp 防抖，边界隐藏；每次手势只触发一次触感，且不与内建触感叠加。
- 输入草稿：`ui/`；按 conversationId 占用槽位，新会话首次迁移，发送后仅在文字仍等于提交内容时清空，返回流式会话不清空。
- 录音触感：关闭 `combinedClickable` 内建触感，只由业务回调调用一次 `TouchHaptics.longPress`。

## 个人数据与文件

- 个人数据直达：`agent/context/`、`agent/device/`、`agent/tool/`；固定 Provider、投影字段与排序，不暴露任意 URI / 表名 / SQL，结果不持久化进会话。
- 文件视觉：`agent/tool/AgentImageTools.kt` + `agent/media/`；Root 读取到临时缓存，回合结束即删，每轮最多一次 `read_image`；图片端点契约见 `docs/image-parameters.md`。
- 媒体语义搜索：`agent/media/index/` + `MediaSearchService`；索引必须用 `task: search result`、查询必须用 `task: search query`，Float16/768 维/L2 归一化，备份 schema 5 用 contentHash 重关联；细节见 `docs/media-semantic-search.md`。

## 模型与用量

- 模型特性：`agent/model/`；辅助视觉默认关闭且只列可读图模型，观察原位替换、不重复调用、失败不伪造不回退；标题默认首轮一次、可开每轮刷新，手工命名后不覆盖。
- 用量：按 requestId 去重、先存后派发；输入含缓存不重复计、输出已含推理，Anthropic 缓存字段在 provider 边界归一，`conversationTotalsV1` 只加差值、不因裁剪减少。
- 思考与路由：`agent/model/`；路由方案、实测与开放问题见 `docs/decision-model-routing.md`。

## 内置浏览器

- `agent/browser/`（含 `ported/browser/`）；共享离屏 WebView，可加载 JS、操作元素与截图，不做额外 URL/DNS/重定向拦截；外部链接仍走 `open_uri`。

## 构建与测试

- 契约测试：`app/src/test/python/`；对 Kotlin 源码做文本级结构断言，源码结构与断言必须同步演进（见 `AGENTS.md` 工程纪律）。
- 发版与 CI：版本号规则、tag 校验、草稿发布流程见 `docs/RELEASE.md`；workflow 为 `.github/workflows/android-release.yml`。
- 组件与 API 取证工具链（sources jar 拉取、AAR 解包）见 `docs/RULES.md` §1.5。

## 终端、Linux 与下载

- 终端：`agent/terminal/` + `ui/screens/terminal/`；分 `android`、Alpine musl、Debian glibc 三环境，守护任务与 PTY / 块式终端各有独立会话。
- 断点续传：`core/ResumableDownload.kt`；`.part` + `.meta` 分离、响应头二次校验、校验器不一致即重下，细则见 `docs/RULES.md` §3.2。
- 版本与依赖：直接依赖以 `gradle/libs.versions.toml` 为准；Compose foundation/ui/animation 由 material3 传递解析，不在 toml 直接声明。
- 打包细节：PTY/PRoot 打包与重建流程见 `docs/TERMINAL_NATIVE.md`；Rootless 能力矩阵见 `docs/ROOTLESS_SUPPORT.md`。

## 长期记忆与 Thinking

- 长期记忆：`agent/memory/AgentMemoryContext.kt` + `ui/screens/memory/AgentMemoryScreen.kt`；单一私有 `MEMORY.md`，按需注入，上限 1 MiB，`AtomicFile` 原子更新。
- Thinking Effort：`agent/model/ProviderReasoning.kt` + `agent/model/ResponsesRequestBuilder.kt` + `ui/model/`；远端元数据优先，其次内置目录与家族规则，兼容旧 `thinkingEnabled` 布尔。

## 聊天流式渲染

- 状态合并：`ui/app/AgentAppState.kt`；SSE 文本增量按 50ms 合并，思考与工具事件立即刷新。
- 显现：`ui/markdown/SmoothTextReveal.kt` + `ui/components/SmoothTextReveal.kt`；`DrawModifierNode` 按字素裁剪已排版文本，自适应 48–240 字素/秒，稳定后恢复完整 Markdown 与选择。
- 输入器：`ui/components/AgentChatInputBar.kt` + `ui/components/AgentChatComposerM3.kt`；state-based `TextFieldState` 在组件内持有编辑缓冲。
- Turn 生命周期：turnId 与 provider round 分离；停止先取消资源再经 outbox 提交；失败为终态，Continue 换新 runId + turnId，修订失配即 fail-closed。
- 用户气泡折叠：超过 6 行才折，`TextMeasurer(maxLines=7)` 在组合期预算；渐隐先 `compositeOver`；展开前释放跟底锚定，状态按消息 id 保存。

## 功耗与预期行为

- 功耗：`hook/` + `agent/`；不轮询、不保活 Google 进程、热路径只保留已验证 Hook，高频成功路径用 `DEBUG` 并由 R8 裁剪。
- 预期行为：目标为小布时保持厂商原逻辑；Eta 已是默认助理时长按电源键打开全屏浮窗并聚焦输入；未成为默认且自动设置关闭时直接回退小布。
- 流诊断：`tools/stream-diagnostics` + runtime；仅文件日志且前台会话时采集，按 admission snapshot 切窗口，span 时长不可加总，不落业务内容。
- 帧关联：`FrameSpanCorrelation.kt`；半开区间并集不证明因果，512 慢帧环 + 2048 保护，单帧最多 48 重叠，`evidenceComplete=notClaimed`。
