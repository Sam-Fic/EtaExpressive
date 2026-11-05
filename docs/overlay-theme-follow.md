# 浮窗（胶囊/球/按钮组/舞台）配色跟随主应用设置

## 结论

- **原来从哪来（一句话）**：`AgentRuntimeService.createOverlayComposeView` 自己拼了一套主题——`resources.configuration.uiMode` 判深浅 + 硬编码 `darkColorScheme()/lightColorScheme()` + `MotionScheme.expressive()`，只认系统深浅，完全不看 App 的强调色 / 配色风格 / 纯黑，且在建窗时算一次。
- **现在从哪来**：与 `MainActivity` 完全相同的一份——`AppearanceSettingsRepository.settingsFlow()` 实时订阅 + `AgentAppTheme(appearance, applyInterfaceScale = false)`；强调色种子、Spec2025 配色风格、深浅三态、纯黑、动态取色全部复用 `rememberEtaColorScheme`。
- **能否实时跟随**：能。DataStore 的 Flow 驱动重组，切主题/换色不用重建浮窗，也不用重启服务；「跟随系统」时由 `isSystemInDarkTheme()`（`LocalConfiguration`）跟随系统深浅。

## 主题真相源（行号）

- `MainActivity.kt:49-50` — `settingsFlow().collectAsState(initial = initialAppearance)`；`:62-66` — `AgentAppTheme(applyInterfaceScale = true, onResolvedDarkModeChange = ::updateSystemBars)`。
- `AgentAppTheme.kt:27-32` — `AppearanceThemeMode` 三态解析（SYSTEM → 系统深浅，LIGHT/DARK 强制）；`:33-38` — `rememberEtaColorScheme(accentColor, paletteStyle, isDark, pureBlackEnabled)`；`:44-47` — `MaterialExpressiveTheme(colorScheme, MotionScheme.expressive())`；`:49-58` — 界面缩放（浮窗传 `false` 即平台密度）；`:59-68` — 提供 `LocalAppearanceSettings / LocalContentColor / LocalDensity` 等。
- `EtaColorSchemeFactory.kt:46-59` — 取色入口；`:62-77` — 种子解析（SYSTEM 在 Android 12+ 取 `dynamicLight/DarkColorScheme(context).primary`，否则用预设种子）；`:80+` — 按 `AppearancePaletteStyle` 生成 Spec2025 `DynamicScheme`；`:144-158` — 纯黑把 `background/surface/surfaceContainer/surfaceDim` 压成 `Color.Black`。
- `AppearanceSettingsRepository.kt:7-10` — `settingsFlow()` / `settings()` 的唯一入口；`SettingsDataStore.appearanceSettingsFlow()` 是 DataStore 的冷流。
- 主题模式枚举 `AppearanceSettings.kt:46-53`（三态：SYSTEM/LIGHT/DARK）；纯黑开关 `:15`；界面缩放 `:19`。

## 改动清单

| 文件 | 行 | 改动 |
| --- | --- | --- |
| `agent/runtime/AgentRuntimeService.kt` | 2194-2215 | `createOverlayComposeView` 的 `setContent` 从自带 `MaterialTheme` 改为 `AppearanceSettingsRepository.settingsFlow().collectAsState(initial = null)` + `AgentAppTheme(applyInterfaceScale = false)`；首帧等数据流第一次发射，避免先闪一帧默认浅色。 |
| 同上 | 2198-2213 / 26-33 | 删除 `isNightMode()`（原 2503-2506）与随之失效的 import：`ExperimentalMaterial3ExpressiveApi`、`LocalContentColor`、`MaterialTheme`、`MotionScheme`、`darkColorScheme`、`lightColorScheme`、`CompositionLocalProvider`；新增 `collectAsState`、`AppearanceSettingsRepository`、`AgentAppTheme`。 |

一个工厂函数覆盖全部四个浮窗：orb `1596`、capsule `1644`、bubble `2142`、result card `2177`。没有改任何形状/间距/尺寸/动效时长；`applyInterfaceScale = false` 是刻意保持——浮窗窗口尺寸由宿主按平台 dp→px 计算，套用界面缩放会让内容超出窗口被裁。

## 硬编码颜色清查（逐个）

| 位置 | 颜色 | 处理 |
| --- | --- | --- |
| `agent/overlay/AgentOverlayContent.kt:279` | `Color.White.copy(alpha = 0.55f)` | **保留**：光球左侧高光，是与主题无关的镜面反射，深浅两套下都成立。 |
| `agent/overlay/AgentOverlayCapsule.kt` 全文件 | 无任何 `Color(0x…)` / `Color.White/Black` | 已全部走 token：底板 `surfaceContainerHighest`（`:917`、`:1191`）、舞台容器 `surfaceContainerHigh`（`:1045`）、边框 `outlineVariant`（`:1025`、`:1129`）、文字/图标/进度点/按钮组选中态都取 `MaterialTheme.colorScheme.*`，切主题即跟随。 |
| `agent/overlay/AgentOverlayOrb.kt`、`AgentOverlayBubble.kt`、`AgentResultCard.kt`、`AgentOverlayContent.kt` 其余部分 | 无硬编码字面量 | 全部走 token，同上。 |
| `ui/components/VirtualTouchFocus.kt` | 无硬编码 | 触摸高亮取 `MaterialTheme`，在胶囊舞台内显示，跟随主题。 |
| `ui/components/GestureIndicator.kt` | 硬编码蓝色 | **未改**：不在浮窗组合内，且全仓库无调用点（死代码），属另一条链路。 |

甩出/回弹期间没有自绘渐变或阴影：位移只改窗口坐标，视觉全部是 M3 组件（无 `Brush`/`shadow`/`graphicsLayer` 颜色），因此不存在需要单独跟随的渐变色。

## 暗色对比度

未发现不可读的组合：所有前景/背景都是 M3 成对角色（`onSurface`↔`surfaceContainer`、`onSurfaceVariant`↔`surfaceContainerHigh` 等），Spec2025 的 tone 映射自带对比保证。唯一偏弱的是深色下 `outlineVariant` 的舞台描边（`AgentOverlayCapsule.kt:1025`、`:1129`）——这是 M3 的刻意低对比分隔线，不是缺陷；若真机觉得舞台边界糊，建议只把这两处换成 `outline`，其余不动。本轮**未改**。

## 怎么验证

1. 应用外观页切「深色」→ 胶囊底板/文字/图标立即变暗，无需重启服务。
2. 换强调色（如 SYSTEM→紫/粉）或换配色风格 → 进度点、按钮组容器与选中态、球一起变。
3. 打开「纯黑」且处于深色 → 胶囊底板应压成纯黑（`surfaceContainer = Black`），嵌套按钮仍保留色阶。
4. 切「跟随系统」→ 在系统设置里开/关深色 → 胶囊跟随；再关掉系统深色确认能回到浅色。
5. 展开成按钮组、再缩成球：三种形态配色一致；舞台画布边框/遮罩、图标 tint 无残留浅色。
6. 若开了「界面缩放」，拉到最大/最小 → 胶囊**尺寸**不随缩放变化（`applyInterfaceScale = false`），但**颜色**照常跟随。

## 静态无法确认的风险

1. **首帧空白**：`initial = null` 意味着设置流第一次发射前不渲染内容（通常一帧内）。若 DataStore 首次读很慢，浮窗会短暂空白而不是闪错色；选择空白是有意的。
2. **SYSTEM 模式的实时性**：`isSystemInDarkTheme()` 读浮窗 `ComposeView` 的 `LocalConfiguration`，依赖 `ViewRootImpl` 派发 config change。预期跟随，但浮窗（非 Activity）在 OEM ROM 上是否每次都派发需真机验；若不跟随，可在 `onConfigurationChanged` 里 bump 一个 composition key 强制重组。
3. **SYSTEM 动态取色的 context 差异**：浮窗用 `overlayContext()`（AccessibilityService/Service context）调 `dynamicLight/DarkColorScheme(context)`，读的是系统 `android.R.color.system_accent1_*`；理论上与 Activity 相同，OEM 改资源时需对比主界面主色。
4. 按约定未编译、未跑测试、未真机验证；改动留在工作区未提交（叠加在甩出参数收敛 + 甩出期间冻结尺寸收拢两笔未提交改动之上）。
