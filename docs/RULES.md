# Eta 强制规则

只放可执行、可判定的硬规矩。不记录实现叙事、时间轴与行号；行号随代码漂移，引用一律用文件 + 符号。

## 1. 组件与 API 取证

### 1.1 必须取证

1. 首次引入组件或库，或不确定组件家族、内置组合槽、该用哪个变体。
2. 要写参数名、默认值、枚举档位、lambda 形参或自定义类型（如 `progress: () -> Float`、`Stroke`）。
3. 要依赖边界行为：测量、溢出、裁剪、约束异常（如 `maxWidth must be >= minWidth`）。
4. 为崩溃、异常布局或错误默认值定因。
5. 判断「有没有内置动画 / 内置尺寸 / 内置槽」这类只能证伪的问题；不能只按文件名找类。
6. 升级依赖版本后要沿用旧结论——版本一变，旧结论作废。

纯业务逻辑、已由本仓封装层定型的调用、仅传 `Modifier` / `onClick` 这类语义稳定的参数，不必取证；但组件选型仍属第 1 条。

### 1.2 取证顺序

1. **官方在线文档**（`m3.material.io`、`developer.android.com`）：定语义、选型与推荐用法。网页正文常抓不到，且讲的是最新版本，不能证明本仓版本的实现。
2. **版本一致的 sources jar**：定真实签名、默认值、KDoc、内置组合槽与设计令牌。版本以本仓依赖解析结果为准（`gradle/libs.versions.toml` 加传递依赖）。
3. **AAR 解包 + `javap`**：sources 缺失，或要确认默认值、常量与负面结论（「确实没有别的分支」）时使用。Windows 下必须完整解包 AAR → `classes.jar` 再 `javap`。

三层冲突时，以本仓依赖解析出的源码 / 字节码为准。

### 1.3 结论元数据

取证结论写进调用处 KDoc，或本节末尾的速查。至少包含：

- 库坐标 + 版本；
- 证据文件路径（sources jar 解包目录或 AAR 路径）；
- 类名 / 函数名（行号可选，升级后必须复核）；
- 关键代码片段（几行即可）。

不合格写法：「官方文档说要这样」「M3 默认就是这样」「我记得参数叫 xxx」。

### 1.4 禁止做法

1. 凭记忆写参数名或默认值——alpha 库改名频繁。
2. 拿 HEAD 或「最新稳定版」源码当依赖版本。
3. 只看文件名找类——多个类常合并进 `*Kt`（如 `SegmentedListItem` 在 `ListItemKt`）。
4. 把「官方默认」当「此处默认」——同一参数在工厂默认、专用变体、调用点可能不同。
5. 用 `-Xours` / `-Xtheirs` 一把梭解决冲突，连他人的取证结论一起抹掉。
6. 看到类被 R8 裁掉就以为必崩——`usage.txt` 里的合成 lambda / 内联后方法属正常。
7. 只读 `commonMain` 或只读 `androidMain`——KMP 同一个类可能两边都有实现。
8. 把 `@sample` 名字当成看过实现——samples 不在 sources jar 里。

### 1.5 复用工具链

```powershell
# 拉取脚本默认清单（升级依赖后必须同步脚本内的模块清单）
powershell -ExecutionPolicy Bypass -File tools\fetch-maven-sources.ps1

# 只拉指定坐标
powershell -ExecutionPolicy Bypass -File tools\fetch-maven-sources.ps1 -Modules androidx.compose.material3:material3:<version>

# 重新下载并重新解包
powershell -ExecutionPolicy Bypass -File tools\fetch-maven-sources.ps1 -Refresh
```

- 解包目录：`~/.gradle/sources/<group>_<name>_<version>/`，索引在 `index.tsv`；先查索引再按符号搜索。
- 脚本必须保持 **UTF-8 with BOM**；PowerShell 5.1 会把无 BOM 的 UTF-8 当 GBK 读，破坏中文与数组。
- KMP 库分 `commonMain/` 与 `androidMain/`，先读 `commonMain`，平台特有行为再看 `androidMain`。
- Android Studio 不会自动关联，需在类文件页手动 `Choose Sources…` 指向解包目录或 sources jar。
- **`GRADLE_USER_HOME` 含中文路径会导致 Kotlin 测试 worker 起不来**：改用 ASCII 路径的 junction，不要直接指向中文目录。

## 2. 设计护栏

R1–R5 由 `DesignSystemRulesTest` 执法；规则变更先改测试，再改本文。

1. **R1 颜色字面量**只允许出现在颜色的定义处（白名单钉死）。
2. **R2 工具元数据单源**：图标与显示名只在 `ToolPresentation` 维护，旧映射表不得复活。
3. **R3 私有令牌 helper**：`private fun chat…Color/Style/Shape/Border` 只允许留在白名单，新文件禁止。
4. **R4 进度/加载指示器**必须走 `EtaLoadingSlot` / `EtaProgressBar`，业务文件禁止直接实例化任何指示器。
5. **R5 Tooltip 单源**：`TooltipBox` / `PlainTooltip` / `RichTooltip` / `rememberTooltipState` 只允许在 `EtaTooltips` 实现内部调用。

### 2.1 状态色

固定映射：`Running → primary`、`Success → etaConnectedColor()`、`Warning → tertiary`、`Failed → error`、`Unknown → onSurfaceVariant`。**`Success` 禁止写成 `primary`**；强调度降档用 alpha，不改语义。

### 2.2 Tooltip

`EtaTooltip` 的实际签名是 `(text, modifier, enabled, content)`，**没有 `anchor` 参数**；气泡位置由内部固定为 `Above`。业务页面只用 `EtaTooltip` / `EtaTooltipIconButton`；文案走 `stringResource`，禁止硬编码中文。

长按触发时长取系统 `ViewConfiguration.longPressTimeoutMillis`（默认约 500ms，可被系统设置改变），不要硬编码。

### 2.3 尺寸令牌

Loading 相关尺寸只能取 `EtaDimens`：`StatusIconSize=16`、`DenseIconSize=20`、`InlineLoadingSize=18`、`RowLeadingLoadingSize=24`、`BlockLoadingSize=28`。不要在文件里定义私有尺寸常量。

### 2.4 Loading 选型

1. 有分母（下载 / 索引 / 安装 / 传输）→ wavy 确定型：`EtaProgressBar(progress)` 或 `EtaLoadingSlot(progress)`。
2. 无分母（登录 / 连接 / 保存 / 同步）→ `EtaLoadingSlot(progress = null)`；块级用 `EtaLoadingSlot(contained = true)`。
3. 可能 50ms 完成的短操作 → `EtaLoadingGate` / `rememberEtaLoadingShown`（显示延迟 120ms、最短可见 400ms）。
4. 系统通知 → `Notification.Builder.setProgress`。

调用点只传 `progress: Float?` 一个判据；不得在业务文件各自判断组件。

### 2.5 按压形状 morph

官方 Expressive 按下行为 = 涟漪 + 形状 morph + （ButtonGroup 内）挤宽；plain 重载没有 morph。morph 终值：Button / IconButton 按下 = CornerSmall（8dp 圆角，ButtonSmallTokens / SmallIconButtonTokens.PressedContainerShape）；ToggleButton 按下 = RoundedCornerShape(6dp)（defaultToggleButtonShapes 硬编码）。时长 DefaultEffects ≈170ms。

1. **容器型按钮直用时必须传官方 `shapes`**：Button / FilledTonalButton / OutlinedButton / ElevatedButton → `ButtonDefaults.shapes()`；IconButton 家族 → `IconButtonDefaults.shapes()`；ToggleButton / IconToggleButton → `ToggleButtonDefaults.shapesFor(ButtonDefaults.MinHeight)`；相连位置 → `ButtonGroupDefaults.connected*ButtonShapes()` 或 `etaConnectedButtonShapes(position)`。
2. **TextButton 豁免**：无容器，morph 不可见，plain 重载即规范。
3. **聊天输入区豁免**（用户决定）：composer 全家圆形钮保持全圆不 morph——圆角同心设计依赖全圆形。
4. 相连 toggle 的选中态形状由 `connected*ButtonShapes()` 的 `checkedShape` 自带，勿自行拼装。

## 3. 行为红线

### 3.1 内置助手

- `default` 与 `builtin-dispatcher` **禁止删除**（`AssistantPrompt.isBuiltIn` 判定，删除接口必须拦截）。
- `builtin-dispatcher` **禁止改名**，id 与名称均锁定；提示词、记忆、技能、工具、头像等字段可正常编辑。
- 默认助手与调度台缺失时**启动补种**；已存在的用户编辑不得覆盖。

### 3.2 断点续传

- `.part` 与 `.meta` 分离存放；半截文件不得改名成正式文件。
- 校验通过才原子改名，并删除 `.meta`。
- 续传必须做响应头二次校验：`ETag` / `Last-Modified` / `Content-Range` 起点与总长，任一与 `.meta` 不符即丢弃断点重下。
- 弱 ETag 不参与 `If-Range`；不能只靠 `If-Range`（服务端可能忽略或回整包）。
- 取消不删 `.part`；只有显式删除模型 / 缓存淘汰才清理断点。

### 3.3 导航

- 容器固定为 miuix `NavDisplay` + `NavBackStack`（`AppRoute : NavKey`）；**不得再引入 `androidx.navigation`**。
- 被覆盖条目仅在 1 层内保持组合（`opaqueDepth = 1`）。
- 一次性副作用不得挂在 entry：回到窗口内时 `LaunchedEffect(Unit)` 会重跑。

### 3.4 胶囊动画与窗口

- 主轴 `CapsuleExpandDurationMillis = 320ms`，禁止弹簧长尾。
- 豁免项：窗沿 clearance 的 `defaultSpatialSpec`；外层入场 / 整窗退场 scale（一次性，非 morph 轴）；状态文字 `SizeTransform` 的 `snap()`；仅 alpha / 颜色的 effects spec 保持官方 `defaultEffectsSpec` / `fastEffectsSpec`。**不得表述为「所有轴一律 320ms」**。
- 窗口尺寸「早涨晚缩」：涨立即上报目标 + 6px；缩等静默 150ms 后上报；禁止逐帧改窗。
- 胶囊窗口必须带 `FLAG_LAYOUT_NO_LIMITS`，否则甩不出屏幕。

### 3.5 浮窗主题

- 四个浮窗统一走 `AppearanceSettingsRepository.settingsFlow()` + `AgentAppTheme(applyInterfaceScale = false)`，不得自带深浅或配色判断。
- 浮窗窗口尺寸由宿主按平台 dp→px 计算；不得应用界面缩放，避免内容超出窗口被裁。
