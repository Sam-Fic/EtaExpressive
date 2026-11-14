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

### 2.3 尺寸与圆角令牌

1. Loading 相关尺寸只能取 `EtaDimens`：`StatusIconSize=16`、`DenseIconSize=20`、`InlineLoadingSize=18`、`RowLeadingLoadingSize=24`、`BlockLoadingSize=28`。不要在文件里定义私有尺寸常量。
2. 圆角先对照 `EtaDimens` 的圆角索引找同语义值（索引只记符号与出处，值就近归属：分段列表 `EtaSegmentedList`、连接式按钮组 `EtaExpressiveKit`）；确实全新才就近命名。同心圆角规则：外层容器圆角 = 内层圆角 + 两层间距。不新增一次性圆角字面量。

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
3. **聊天输入区豁免**（用户决定）：composer 全家圆形钮保持全圆不 morph——圆角同心设计依赖全圆形。例外（用户 2026-11-11 决定）：口述态 × / ✓ 传官方 `IconButtonDefaults.shapes()` 参与按压 morph——听写态为 40×56 竖直胶囊（非圆钮），失败提示态 × 为 40×40 同心圆钮也保留 morph（用户点名 ×/✓）；静息 CornerFull 不变，按下压向 CornerSmall。morph 钮自身的 mic 圆形态（触发语音入口，40×40 圆）不例外：plain 重载、无按压 morph。其余 composer 圆钮（含提示态齿轮）仍豁免不 morph。豁免同时涵盖自制按压视觉：发送/停止/继续圆钮的 0.9 按压缩放已按用户同日决定移除，按下只留官方涟漪。
4. 相连 toggle 的选中态形状由 `connected*ButtonShapes()` 的 `checkedShape` 自带，勿自行拼装。

### 2.6 资源文案末尾标点

1. `res/values*/strings.xml` 的用户可见文案按**单句**写：句末不加 `。` / `.` / `：`；句内标点照常。简体、繁体、英文三套同时遵守。
2. 例外只影响末句标点，不动句内标点：
   - 多句段落式说明（≥2 句），如远程连接排查、错误诊断、权限引导类长文案，保留末句句号；
   - 以 `1.` `2.` 这类编号开头的分条步骤，编号点与末句句号都保留；
   - `etc.`、URL、路径、文件名、版本号中的点不是句末标点，保持原样；
   - 带格式占位符（`%1$s`、`%2$d`）不改变判定，仍按句数决定末尾是否加标点。
3. 适用范围是全局资源规范（`strings.xml` 及 `speech.xml` / `tts.xml` 等同类用户可见资源），不是只针对某一组预设；新增或改写任意语言的条目都要遵守。
4. `DesignSystemRulesTest` 只执法 R1–R5，**没有**字符串标点检查；本条靠评审自查，不新增测试。

### 2.7 透明度弱化

「禁用 / 未授权 / 不可用」内容的弱化统一用 `EtaAlphas.DisabledContent`（0.38，官方禁用态规范），不得散落字面量；非禁用语义出现的 0.38 属巧合值，不引用本令牌。

### 2.8 动效规格

1. 官方 `MaterialTheme.motionScheme`（spatial / effects 弹簧）优先；官方没有的项目自有时间轴只在 `EtaMotion` 登记单一出处（每个值带成文理由），不得在他处写第二份。
2. 页面级导航转场（含不能路由的页内子页的手补转场）规格见 §3.3；`EtaMotion.NavigationFallbackDurationMillis` 是转场时长单一出处。

### 2.9 顶栏动作

顶栏（大标题栏右上角）动作按钮**一律走 `ui/components/TopBarIconButton.kt`**（唯一实现，内部含 tooltip、触觉、破坏色），禁止手搓 `IconButton` + `Icon`；顶栏动作一律纯图标不用文字按钮，破坏性动作传 `destructive = true`。二级页「保存」放顶栏动作位，返回即取消（不弹确认）。

### 2.10 滚动截断淡出

滚动区边缘淡出统一走 `ui/components/ScrollEdgeFade.kt`，修饰符放在滚动修饰符**之前**。两条硬性实现约束（组件内部已落实，自绘类似效果时必须同样遵守）：

1. **DstIn 蒙版必须落在独立离屏纹理**（`CompositingStrategy.Offscreen`）：共画布会把 DstIn 削到窗底透出纯黑。
2. 盖板类渐变（浮在内容之上的渐隐层）必须把 DstIn 下沉到内容层，不能只削盖板自身。

### 2.11 UI 文案与产品名

用户可见 UI 文案**禁止写死「Eta」字样**：统一用 `_named` 后缀的文案资源 + `LocalRemoteAgentName`（`ui/remote/`）在组合期解析产品名，保证远端智能体可自定义名称时文案跟随。

### 2.12 宽屏判定

宽屏 / 双栏布局的宽度阈值判定**单一出处 `ui/layout/AdaptivePageLayout.kt` 的 `WideScreenMinWidth`（600dp）**；任何页面不得自设断点或用 `BoxWithConstraints` 自判宽屏。设置页宽屏形态为标准 list-detail（左栏原 `SettingsScreen`、右栏独立 `NavDisplay`，默认栈底 = 模型提供方页），左右栏共用同一套 entry 注册，禁止复制 entry 列表。

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
- **整屏子页必须做成路由**：静态内容（无表单草稿、owner 票据等不可序列化状态）禁止用 `EtaPageSurface` 或页内布尔开关承载；`EtaPageSurface` 只留给状态无法序列化进路由的编辑器。新增路由按既有五处登记（`AppRoute` 声明、`AgentAppRoot` entry、`AgentNavigator` 显式表、`AgentAppShell` titleForRoute、`FrameDiagnosticRoute`），由 `test_app_route_entry_parity_contract.py` 防漏登。
- **确实不能路由的页内子页（如浏览器设置与浏览器共用 `BrowserTabPool`），手补转场必须逐项对齐 NavDisplay 程序化转场**；缺任何一项就是可感知的「页内侧滑感」：
  1. 位移与进度统一 `tween(EtaMotion.NavigationFallbackDurationMillis, NavProgrammaticEasing)`；禁止 M3E Spatial 弹簧（自带过冲、时长不定）。
  2. **顶页全程不透明**：给顶页加 fadeIn / fadeOut 会在运动中透出底页。
  3. **effects 层两样（转场几何之外的正交层，缺一不可）**：被盖页全屏黑 scrim，alpha = `NavDisplayEffects.Default.dimAmount`(0.5) × coverProgress，画在进入页之下；进入页运动期间 leading 角圆角裁切，半径取 `rememberNavSystemCornerRadius()`，仅转场进度在开区间内生效（起止满屏时圆角与物理屏角重合，除裁切不可见）。
  4. `AnimatedContent` 变体按方向逐格对齐：推入 = 新页整屏不透明推入 + 旧页 1/4 视差淡到 0.9；弹出 = 旧页整屏不透明滑出**且置顶层**（`ContentTransform.targetContentZIndex = -1f`；此版本没有 `targetContentOnTop`）+ 被盖页从视差位复位淡入。
  5. 被盖页固定 1/4 视差 + alpha 淡到 0.9；所有随进度变化的读取走 `graphicsLayer` 延迟块，逐帧零重组。
  6. 底部 `ModalBottomSheet` 与系统弹窗动画属 Sheet / 弹窗语义，不适用本条。
- 样板：`BrowserSheet.kt`（AnimatedVisibility 版）、`DoubaoVoiceSettings.kt`（AnimatedContent 版）。

### 3.3.1 边缘横滑返回（miuix）

页面级横滑返回手势用 miuix `NavBackStack` 的 `entry<T>(swipeDismiss = direction)` 能力，**不自研手势层**。方向参数必须在 builder 外读成普通值再传入（builder 内读 State 会破坏快照一致性）。Home 页固定 `swipeDismiss = None`。外观设置里的 `appearance_swipe_back_enabled` 开关走 DataStore，不得被 API 版本门槛包住（低版本也要能关）。

### 3.4 胶囊动画与窗口

- 主轴 `CapsuleExpandDurationMillis = 320ms`，禁止弹簧长尾。
- 豁免项：窗沿 clearance 的 `defaultSpatialSpec`；外层入场 / 整窗退场 scale（一次性，非 morph 轴）；状态文字 `SizeTransform` 的 `snap()`；仅 alpha / 颜色的 effects spec 保持官方 `defaultEffectsSpec` / `fastEffectsSpec`。**不得表述为「所有轴一律 320ms」**。
- 窗口尺寸「早涨晚缩」：涨立即上报目标 + 6px；缩等静默 150ms 后上报；禁止逐帧改窗。
- 胶囊窗口必须带 `FLAG_LAYOUT_NO_LIMITS`，否则甩不出屏幕。

### 3.5 浮窗主题

- 四个浮窗统一走 `AppearanceSettingsRepository.settingsFlow()` + `AgentAppTheme(applyInterfaceScale = false)`，不得自带深浅或配色判断。
- 浮窗窗口尺寸由宿主按平台 dp→px 计算；不得应用界面缩放，避免内容超出窗口被裁。
