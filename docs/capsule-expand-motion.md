# 胶囊展开 / 收起：单一时间轴说明

> 2026-11 · 对应改动全部在 `AgentOverlayCapsule.kt`（未编译、未提交）。
> 本轮 **ADB 离线**（`adb devices` 为空表），下面全部是静态推导，没有真机观测。

## 一、结论

主轴只有一条：`EtaMotion.CapsuleExpandDurationMillis`（320ms，`FastOutSlowInEasing`）。
下列**布局轴**现在全部同相、同一时刻收尾（不再有任何一条弹簧在主轴之后继续跑）：

| 层 | 位置 | 容器/属性 | 时间轴 |
| --- | --- | --- | --- |
| 1 | L292 | `Surface` 宽度 `targetCapsuleWidth` | 320 tween |
| 2 | L241 | 展开进度 `expansion`（`translationY -6dp → 0`） | 320 tween |
| 3 | L659 / L663 | 正文高度 `expandVertically` / `shrinkVertically` | 320 tween |
| 4 | L648 / L715 | 舞台画布 ↔ 微缩镜 `sharedBounds.boundsTransform` | 320 tween |
| 5 | L625 / L629 | 微缩镜容器 `expandHorizontally` / `shrinkHorizontally` | **本轮改为** 320 tween |
| 6 | L689 | 正文三态容器 `AnimatedContent` 的 `SizeTransform` | **本轮改为** 320 tween |
| 7 | L840 | 暂停/继续钮 `AnimatedContent` 的 `SizeTransform` | **本轮改为** 320 tween |

只改 alpha / 颜色的效果层保持官方 `defaultEffectsSpec`（ζ1.0/1600，实收 ≈170ms）与
`fastEffectsSpec`（ζ1.0/3800，实收 ≈110ms）：都**短于** 320ms，先于布局轴结束，不会留下尾巴。

## 二、根因（按证据强度排序）

### 1. 末尾抽搐的直接来源：窗口尺寸上报的「晚缩 / 晚涨」

- 涨：L318-332 目标变大立即上报；
- 缩：L333-368 等 `measuredSize` 静默 `CapsuleWindowSettleQuietMillis=150ms` 再上报；
- 宿主：`AgentRuntimeService.kt` L1849-1894 `applyCapsuleWindowBounds()`，一次 `updateViewLayout` 会重分配 surface。

问题在于**分析目标与实测恒差几个 px**：目标按 `448dp` 一次取整，实测是逐节点取整后求和
（`56dp→158px`、`8dp→23px`、`328dp→923px`、`40dp→113px`，各自取整后再相加），
所以主轴结束（t=320ms）后 ~150ms 必然还有一次「要么涨 1~3px、要么缩很多」的
`updateViewLayout`，正好落在动画刚静止的瞬间 → 实机就是末尾那一下抽搐。
那 1~3px 的差额原本还意味着卡片底缘（正好在三个按钮下方）被窗口裁掉 1~3px，
再晚 150ms 才补齐——按钮区看着"还在动/抽"也来自这一处。
收起方向的这次收拢本身是「晚缩」设计需要的，但它落在 320ms 之后，
那时若还有别的弹簧没收完，就会被这一下排布放大。

### 2. 微缩镜容器用的 spatial 弹簧比主轴多跑 ~120ms

改前 L613-620（当前 L618-633）：

```kotlin
enter = expandHorizontally(animationSpec = motionScheme.defaultSpatialSpec(), expandFrom = Alignment.End)
exit  = shrinkHorizontally(animationSpec = motionScheme.fastSpatialSpec(), shrinkTowards = Alignment.End)
```

- `defaultSpatialSpec` = `spring(ζ0.8, stiffness 380)`：实收 ≈440ms，1.5% 过冲；
- `fastSpatialSpec` = `spring(ζ0.6, stiffness 800)`：实收 ≈400ms，**9.5% 过冲**。

这个 `AnimatedVisibility` 的宽度就是 `sharedBounds` 的源/目标矩形。morph 走 320ms tween，
容器却跑到 440/400ms，且 `fastSpatial` 的过冲会把宽度压过 0 再被约束夹住：

- 收起：morph 在 320ms 交接，微缩镜本体还要再长 120ms → 末尾抽一下；
- 展开：镜身显隐结束后，header 权重列还在继续变宽，状态文字在主轴之后继续重排。

### 3. 正文三态容器的 `SizeTransform` 用的是 440ms 弹簧（假设 1 / 3 的「第二条轴」）

改前 L683（旧 `bodySizeSpatial`，当前 L689）：`SizeTransform(clip = false) { _, _ -> bodySizeSpatial }`，
`bodySizeSpatial = motionScheme.defaultSpatialSpec<IntSize>()`（≈440ms，1.5% 过冲）。

它只在 `bodyMode` 切换（补充输入 / 舞台 / 控制）时跑，**不随 `expanded` 变**
（`bodyMode` 只取决于 `virtualDisplay.active` 与 `supplementMode`），所以：

- 若舞台在展开之前就已激活（有微缩镜）→ 这条轴不跑，它不是这次观感的主因；
- 若舞台恰在展开瞬间激活 → 容器高度由这条 440ms 弹簧驱动（40dp → 376dp）。
  此时舞台画布由 `sharedBounds` morph 领跑、看起来正常，**最底部的按钮组只能等
  容器最后那段减速长尾揭开**，并且弹簧的 1.5% 过冲会在收尾时把高度顶一下再回来。
  这正是「舞台段正常、按钮段慢 + 末尾抽搐」的分布。

### 4. 已排除：M3 `ButtonGroup` 内部没有第二套动画

反编译 `material3-android:1.5.0-alpha29`（AAR → `classes.jar` → `javap`）：

- `ButtonGroupKt` 只有一个普通 `Layout` + `NonAdaptiveButtonGroupMeasurePolicy`，
  形参里出现 `MotionSchemeKeyTokens.FAST_SPATIAL` 与 `waitUntil`，没有
  `AnimatedVisibility` / `AnimatedContent` / `SizeTransform`；
- `EnlargeOnPressNode` 只收 `interactionSource.interactions`（按压），用
  FAST_SPATIAL 做「按压加宽、邻居等量压缩」，展开期间不触发；
- `ButtonGroupDefaults` 里也没有尺寸动画。

所以「按钮组内部自带进场动画」不成立。按钮区真实的第二时长只有两类：
上面第 3 条，以及**我们自己**在中间钮里套的 `AnimatedContent(SizeTransform 默认规格)`（见第三节）。

## 三、为什么「舞台段不慢、按钮段慢」

- 舞台画布是共享元素：320ms tween 全程画在 `SharedTransitionLayout` 的 overlay 里，
  **不受 `expandVertically` 裁切影响**，可见运动从第 0 帧就开始，看起来匀速、利落。
- 按钮组没有共享元素待遇，只能等正文高度的裁切边扫到它。高度轴一旦被 440ms 弹簧驱动
  （第二节第 3 条），按钮就落在那段减速长尾里。
- 即使高度轴是 320ms tween，`FastOutSlowInEasing` 在 t≈199ms 时已长到 89.4%，
  「按钮组 + 底部 padding」是最后 10%（约 121ms）；这 121ms 里别的层都已经停了，
  只有它还在动 → 观感就是「前半段好了，按钮段还在慢慢动」。

## 四、改动一览（改前 → 改后）

| # | 位置 | 改前 | 改后 |
| --- | --- | --- | --- |
| 1 | L318-332 + L333-368 尺寸上报 | 涨 = 精确分析目标；落定 = 静默 150ms 后无条件上报 | 涨 = 目标 + 6px 余量（底缘不被裁）；落定只在差量 > 6px 时排布 |
| 2 | L618-633 微缩镜容器 | `expandHorizontally(defaultSpatial)` / `shrinkHorizontally(fastSpatial)` | `expandHorizontally(expandSizeSpec)` / `shrinkHorizontally(expandSizeSpec)` |
| 3 | L398-401 + L689 正文三态容器 | `SizeTransform { _, _ -> bodySizeSpatial }`（defaultSpatial ≈440ms） | `SizeTransform { _, _ -> expandSizeSpec }`（320ms） |
| 4 | L778-782 + L840 暂停钮 | `SizeTransform(clip = false)`（Compose 默认弹簧 ≈350ms+） | `SizeTransform(clip = false) { _, _ -> actionSizeSpec }`（320ms，与本组/主轴同源） |

`actionSizeSpec` 是 `CapsuleActions` 内的局部值，时长直接取 `EtaMotion.CapsuleExpandDurationMillis`，
**没有新增任何时长常量或第二套时长体系**。

## 五、为什么这些层不会互相踩

1. 7 条布局轴全部是 320ms 定长 tween，同相起止；没有弹簧 = 没有过冲、没有「主轴结束后还在动」。
2. 收尾只有一个来源：窗口尺寸只在「状态目标级」变化时落一次 `updateViewLayout`，
   取整级的 1~3px 差量一律不再排布，因此不会在 t≈470ms 再排一次 surface。
3. alpha / 颜色层（170ms / 110ms）比布局轴短，先结束，永远不可能是最后动的那一层。
4. `sharedBounds` 与微缩镜容器、正文高度共用同一 spec，morph 交接时目标矩形已经静止。

## 六、回退

只动了 `AgentOverlayCapsule.kt` 一个文件的 5 处 hunk（外加一行 `kotlin.math.abs` import）：

```powershell
git checkout -- app/src/main/kotlin/io/github/mangi/eta/agent/overlay/AgentOverlayCapsule.kt
```

逐条还原：①涨的支路去掉 `+ CapsuleReportGrowTolerancePx`；②删掉落定分支的容差判断
（恢复无条件上报）；③微缩镜容器的两个 spec 还原成
`motionScheme.defaultSpatialSpec()` / `motionScheme.fastSpatialSpec()`；④正文三态
`SizeTransform` 还原成 `bodySizeSpatial`（并在 L398-401 注释处恢复
`val bodySizeSpatial = motionScheme.defaultSpatialSpec<IntSize>()`）；
⑤中间钮 `SizeTransform` 去掉 `{ _, _ -> actionSizeSpec }`（并删掉 `actionSizeSpec`）。

## 七、未做真机验证 · 现场最小采集方案

**必须如实标注：本轮 `adb devices` 空表，上面所有结论都是静态推导，未在真机上看过一帧。**

若改后仍复现，按下面最小插桩抓「4 帧 + 1 条排布日志」：

1. 在 `AgentOverlayCapsule.kt` 的 `reportContentSize(...)` 里加：
   `Log.d("CapsuleMotion", "t=${SystemClock.uptimeMillis()} report=${widthPx}x${heightPx}")`
2. 在 `AgentRuntimeService.applyCapsuleWindowBounds()` 的 `updateViewLayout` 前加：
   `Log.d("CapsuleMotion", "t=${SystemClock.uptimeMillis()} window=${width}x${height} at $x,$y")`
3. 采集：
   - `adb logcat -c && adb logcat -s CapsuleMotion`，点一次展开、一次收起；
   - 期望：一次展开只有 **1 行** report（t≈0，目标 + 6px）；收起只有 **1 行**（t≈470ms 一次收拢）；
     若展开在 t≈470 还有第二行，说明容差判断没兜住（把差量与日志对照即可）。
   - `adb shell screenrecord --time-limit 6 /sdcard/expand.mp4 && adb pull /sdcard/expand.mp4`
     逐帧看：t/320ms 处是否还有像素在动（按钮组底部 / header 镜位 / 卡片底缘）。
   - `adb shell dumpsys gfxinfo io.github.mangi.eta framestats`：看这 320ms 窗口里有没有 >16ms 的帧，
     若抽搐帧与上面日志的 t≈470ms 对齐，即证实第 1 条。
4. 判别「按钮段」到底是谁在动：
   - 若只有卡片底缘 1~3px 在动 → 第 1 条（窗口尺寸）；改后应消失。
   - 若中间钮/整组在横向重新居中 → 第 3/4 条（`SizeTransform`）；改后应消失。
   - 若按钮是被裁切边自上而下扫出来的「擦除感」→ 属于主轴裁切编排本身，
     下一步只能动编排（把按钮移出裁切、或让高度轴先于宽度轴收），
     但那会引入第二条时长，按约定本轮不做，先以采集结果为准。
