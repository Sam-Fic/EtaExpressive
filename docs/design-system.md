# 设计系统（Material 3 Expressive）

本文是 UI 层的分层与规则出处。代码里的细则由 `app/src/test/kotlin/io/github/mangi/eta/design/DesignSystemRulesTest.kt`（零依赖架构测试）执法——改规则先改那里，文档同步。

## 分层

| 层 | 位置 | 内容 | 规则 |
|---|---|---|---|
| L0 | `androidx.compose.material3` | 官方组件与令牌 | 唯一允许直接引用的官方源 |
| L1 | `ui/adaptive` | 设计令牌：`eta*` 取色、`etaTextStyle(EtaType)` 字阶、`EtaDimens` 尺寸/间距、`EtaStatus` 状态语义色、`EtaExpressiveKit`（EtaIconButton / 连接按钮组 / 进度环） | 官方没有但项目反复出现的值，收在这里 |
| L1.5 | `ui/components/ChatTypography.kt` | 聊天语境排版别名（chatBody/Secondary/Tertiary/QuaternaryStyle） | 全部转包 `etaTextStyle`，不自定义字号 |
| L2 | `ui/components` | 业务组件（WorkCard* 披露卡、ContainedMorphLoadingIndicator、SpeechPlaybackButton…） | 不得私藏设计令牌 helper；跨文件要用就提升到 L1 |
| L3 | `ui/screens`、`agent/*/ui` 等 | 页面与业务 | 同上 |

`agent/tool/ToolPresentation.kt` 是工具 UI 元数据（图标 + 显示名）的单一数据源，UI 与浮窗都查它；注意它与 `agent/model/AgentToolCatalog`（运行时 schema 注册表）不是一回事。

## 颜色

- 组件层一律引用官方 `MaterialTheme.colorScheme` 角色（`ui/adaptive` 的 `etaPrimary()` / `etaOnSurfaceVariant()` 等）。
- 颜色字面量只允许出现在「颜色的定义处」：主题工厂强调色种子（`EtaColorSchemeFactory`）、外观设置色板（`AppearanceSettingsScreen`）、移植主题（`agent/browser/ported/ui/theme`）、浮窗固定色（`AgentOverlayContent`）、Markdown 默认样式、GPT 速度档色板。清单由架构测试 R1 钉死。
- M3E 没有 success 语义角色：状态 → 角色的映射只此一处（`EtaStatus.foregroundColor()`）：Running/Success → `primary`，Failed → `error`，Unknown → `onSurfaceVariant`。强调度的降档（alpha）不属于语义，留给调用方。

## Loading 选型表

| 场景 | 组件 |
|---|---|
| 按钮内 / 行内小槽（≤24dp，有相邻内容） | `LoadingIndicator`（M3E morph，无容器圆底；需 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`） |
| 确定进度环（有 progress 值） | `CircularProgressIndicator` / `CircularWavyProgressIndicator`（显式传 `modifier.size`） |
| 块级 / 空态 / 整卡（独立展示） | `ContainedMorphLoadingIndicator`（封装自带 OptIn） |

- `LoadingIndicator` 内部固定 `.size(48.dp)`，会被链最外层 `Modifier.size()` 收敛——小尺寸可用，外层必须给尺寸。
- 禁止裸 `CircularProgressIndicator()`（默认 40dp 直接放行内槽，会顶爆图标位——架构测试 R4）。
- 状态标记（工具行、AgentStatusCard）：运行中用 `LoadingIndicator`，成功/失败/中断用图标，四种状态统一 16dp 图标语法。

## 尺寸与间距

- 官方令牌优先：`IconButtonDefaults.extraSmallIconSize`、`ButtonDefaults.IconSize`、`ProgressIndicatorDefaults.CircularStrokeWidth`…
- 官方没有的从 `EtaDimens` 取（`StatusIconSize`、`SmallRingStroke`、间距阶 SpaceXs/Sm/Md/Lg）。不要在文件里定义私有尺寸常量。

## 规则与执法

架构测试（`DesignSystemRulesTest`）当前钉住的规则：

1. **R1 颜色字面量**只允许出现在颜色定义处（白名单）。
2. **R2 工具元数据单源**：`iconForTool` / `toolDisplayNameResource` 旧表不得复活，只改 `ToolPresentation`。
3. **R3 私有令牌 helper**：`private fun chat…Color/Style/Shape/Border` 只允许留在白名单（ChatMessageItem、AgentChatBody 存量），新文件禁止。
4. **R4 禁裸默认尺寸进度指示器**。
5. **R5 Tooltip 单源**：`TooltipBox` / `PlainTooltip` / `RichTooltip` / `rememberTooltipState` 只能在 `ui/adaptive/EtaTooltips.kt` 调用；业务页面用 `EtaTooltip` / `EtaTooltipIconButton`，规则见 [accessibility-tooltips.md](accessibility-tooltips.md)。

白名单 = 存量债务钉死：把文件加进白名单需要 review，逐个迁移后删行。

## 后续方向

- 接入 Konsist（需网络拉依赖）后把文件扫描升级为反射规则：按包结构校验分层依赖（L3 不得 import L2 私有符号、`agent/` 不得依赖 `ui/`）。
- material3 的 `import … as M3*` 别名导入是既有风格（约三十个文件），约定为「同一文件内一种组件只用一种命名」；新文件优先裸名或 `M3` 前缀，不做一次性全量迁移。
- 间距令牌（`EtaDimens.Space*`）新代码先用起来，存量字面量渐进收敛。
