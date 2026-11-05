# 胶囊甩出物理动画 —— 实现说明（手感收敛 + 缩球顺滑）

## 结论

### 1) 新参数一览（旧值 → 新值）

| 参数 | 旧值 | 新值 | 作用与理由 |
| --- | --- | --- | --- |
| `MinFlingVelocityPxPerSec` | 260 | **400** | 「算轻放」的门槛。轻拖抬手通常在 100–300px/s，400（≈142dp/s @2.8125）把它们全部归零，只做原位/越界回弹。 |
| `GlideSeconds` | 0.24 | **0.14** | 速度→距离增益。同速度滑行距离 −42%；1000px/s 从 240px 降到 140px，3000px/s 从 720px 降到 420px，中速只滑一小段。 |
| `FlightDampingRatio` | 0.70 | **0.90** | 飞行段几乎临界，额外过冲从 ~14% 降到 ~0；行程只由投影落点决定，实机是「甩出去后沉下去、没有第二段滑行」。 |
| `FlightStiffness` | 520 | 520（不变） | 收尾时长仍约 0.25s，只改阻尼不改节奏，避免「沉」变成「拖」。 |
| `EdgeDampingRatio` | 0.50 | 0.50（不变） | 贴边「拐」这一下用户认可，调沉会把回弹抹平；越界回弹仍是本次唯一明显过冲的来源。 |
| `EdgeStiffness` | 520 | 520（不变） | 同上。 |
| `VelocityWindowMillis` / `VelocityStallMillis` | 120 / 60 | 不变 | 尾部 120ms 最小二乘拟合，末段 60ms 无采样判停顿。 |

两项叠加后同等速度的总行程约为旧版一半（增益 −42% + 去掉 14% 飞行过冲）。快速甩（约 8000px/s）投影仍达 >900px，照样能越过边缘、短暂出屏再拐回——核心行为保留，只是「轻轻一拖」不再飞。

### 2) 缩球形变不顺的根因

不是弹簧曲线的问题，也不是 `sharedBounds` 与窗口尺寸争用同一目标尺寸。根因是**窗口 surface 的那次「晚缩」落在飞行/回弹动画中途**：

- **触发时序**：`prepareCapsuleFling` 在 ACTION_UP 同步就把 `capsuleMinimized` 置 true（`AgentRuntimeService.kt:1997-2008`），Compose 随即在同一个调用栈里启动飞行（`AgentOverlayCapsule.kt:520-543`）。所以**缩球与甩出同帧开始**，不是先甩后缩，也不是先缩后甩。
- **`sharedBounds` 不参与窗口尺寸**：全文件只有两处 `sharedBounds`，都是「收起微缩镜 ↔ 展开舞台画布」这对 key（`AgentOverlayCapsule.kt:616-626`、`683-692`），形变发生在**已分配好的窗口内部**；缩球时这两端走 enter/exit 而不是配对 morph，与窗口尺寸无争用。
- **位置所有权只保护位置，不保护尺寸**：`applyCapsuleWindowBounds` 的 `if (capsulePointerCaptured || capsuleFlingActive || settleUntil)` 只决定 `position` 取值（`AgentRuntimeService.kt:1875-1882`）；`width/height` 无论是否在甩出都照常重算并写回（同一函数 `1868-1871`、`1889-1892`）。
- **时间轴错配**：内容宽度 morph 是 320ms tween（`AgentOverlayCapsule.kt:217-219`、`290-294`），窗口收拢则等 `measuredSize` 静默 150ms 后才上报（`AgentOverlayCapsule.kt:325-351`，`EtaMotion.CapsuleWindowSettleQuietMillis`）→ 实际收窗约在松手后 **470ms**。而飞行 ~0.25s + 贴边回弹 ~0.3s，这次「一次 3–4 倍的 surface 缓冲重分配 + 整窗重新测量」正好砸在回弹中段（`AgentRuntimeService.kt:1859-1898`）。
- 另有一条叠加成本：甩出期间位置是逐帧 `updateViewLayout`（`AgentRuntimeService.kt:2030-2037`），与上面那次大 resize 同属一帧序列。

### 3) 选的改法：甩出期间冻结「晚缩」，落定后再收

- `AgentOverlayCapsule.kt:309` 新增 `flingRunning` 状态，在启动甩出协程前置 true（`:524`），`try/finally` 在物理结束时置 false（`:535-538`，被打断／旋转同样解冻）。
- 「晚缩」上报的 `LaunchedEffect` 增加 `flingRunning` key，并在甩出期间直接 `return`（`:325-328`）：**甩出全程窗口保持松手前尺寸，内容在里面照常 morph**，物理落定后再由 150ms 静默触发一次收拢。
- 为什么不用「甩出开始即把形变吸附到位」：窗口不能在内容还没缩小时就收到球窗尺寸（272dp/368dp 内容会被窗口裁 320ms）；而直接 snap 会丢掉「收进收纳位」的确认感，与已有的触觉反馈不搭。
- 为什么保留「边飞边 morph」而不挪到落定后：球在飞行中收成球、贴边时已经是球，读起来是一个动作；挪到落定后再 morph 会变成「飞完再变」，像两件事。
- 收拢是一次性的、且发生在停稳后：窗口水平居中（`Box(fillMaxWidth, contentAlignment = TopCenter)`，`AgentOverlayCapsule.kt:420-426`），收窗时宿主保持中心锚定（`AgentRuntimeService.kt:1875-1882`），预期视觉上不可见。

**对「早涨晚缩」的影响面**：只给「晚缩」这一支加了前置条件（甩出期间不缩）；「涨即贴」那一支与常态展开/收起完全未改，`reportedSize`/`measuredSize`/150ms 静默/缩水回路防线都原样保留。回退方式：删掉 `flingRunning` 这个 key、`:328` 的提前返回、以及 `:524`/`:537` 两处赋值即可回到旧行为。

## 现状取证（改动前）

- 手势接在头部 `TitleDragSurface` 的 `pointerInteropFilter` 上（`AgentOverlayCapsule.kt:479-553`），用 `AgentOrbDragGesture` 判定 slop；位置不存于 Compose，逐帧回调宿主：
  - 拖动：`onDrag(dx, dy)` → `handleCapsuleDrag` 在 `overlayBounds` 内 1:1、越界按 0.35 橡皮筋（`AgentRuntimeService.kt:1962-1981`）。
  - 抬手：原实现是 `OverScroller.fling` + Choreographer 帧循环（已删除），**最终落位被 `coerceIn` 硬夹回屏幕**，所以越界减速过程根本不可见。真正缺的是「允许窗口可见地越界 + 贴边弹簧收尾」。
- 收起胶囊与展开容器共用同一套窗口与拖动路径（只有窗口尺寸不同），缩球是同一窗口的尺寸/内容 morph，所以两种形态不需要两套物理。

## 两种形态的行为

| 形态 | 甩出 | 缩球 |
| --- | --- | --- |
| 收起胶囊（272dp 宽） | 按整窗尺寸算边界，可横向越界约一个窗宽后回弹贴边；竖向可越过系统栏/导航条（`overlayBounds` 已扣安全区） | 大力横向甩（≥ 系统 fling 上限 45% 且横向占优）触发缩球，滑行与 320ms 形变同时进行，物理按 **球窗 80×68dp** 规划边界；窗口尺寸收拢改为等甩出结束后再做 |
| 展开容器（368dp 宽、高度随阶段变化） | 同上，窗口占位取松手时 `lp.width/height` | 同样可缩球；内容形变期间窗口绕动画中心对称收放，物理中心不漂移 |

坐标系用「中心 x + 顶边 y」：缩球时窗口宽度逐帧收窄，`left = center - 当前宽/2`（`AgentRuntimeService.kt:2030-2037`），所以贴边目标不会随形变平移。

## 越界上限与限制来源

- **越界上限**：`min(窗口尺寸 × 1.2, 屏宽 × 0.9)`（横向）/ `min(窗口尺寸 × 1.2, 屏高 × 0.35)`（纵向），「窗口尺寸」取**松手时的占位**（缩球则取球窗 80×68dp）。以 1080×2400、density 2.8125 为例：
  - 收起胶囊（272dp ≈ 765px）→ 横向上限 918px；整窗完全出屏只需中心越界约 383px，余量充足。
  - 展开容器（368dp ≈ 1035px）→ 横向上限 `min(1242, 972) = 972px`。
  - 缩球（80dp ≈ 225px）→ 横向上限 270px；完整出屏只需越界 113px。
  - 纵向：上限 `min(窗高 × 1.2, 840px)`；200px 级胶囊窗约 240px，足以完整移出上/下边。
- **限制来源**：越界上限是**主动物理约束**，不是 WindowManager 限制，用来压住「完全出屏后不可见的时长」。窗口层没有钳制：`applyCapsuleWindowPositionAt` 直接写 `lp.x/lp.y`（`AgentRuntimeService.kt:1901-1915`），胶囊 `LayoutParams` 用 `TOP|LEFT` + `FLAG_LAYOUT_IN_SCREEN` + `setFitInsetsTypes(0)` + 挖孔 ALWAYS（`AgentRuntimeService.kt:2052-2085`，关键项 2072-2075），无边界校正。

## 打断与手势冲突

- **甩出中再按下**：`ACTION_DOWN` 立即 `job.cancel()` 并把最后落点经 `onFlingFinish` 交还宿主（`AgentOverlayCapsule.kt:481-493`），位置不跳变；`finally` 同时解冻尺寸收拢。
- **继续拖**：越过 slop 才 `onDragStart()`（`AgentOverlayCapsule.kt:505`），宿主此刻夺回位置所有权并清甩出 flag（`AgentRuntimeService.kt:1945-1956`）。
- **点击**：未过 slop 仍是点击；`toggleCapsule` 会先清位置所有权并按当前尺寸钳回界内（`AgentRuntimeService.kt:1731-1735`），避免「甩出被打断后停在半屏外点一下」卡在界外。
- **甩出过程中不可点击**：窗口在动，按下即打断；点击语义只在按下不动、抬起时成立。
- **旋转/inset 变化**：`LaunchedEffect(configuration)` 取消动画并回调 `onFlingCancel`（`AgentOverlayCapsule.kt:396-403`），宿主清所有权并按新边界立即钳回（`AgentRuntimeService.kt:1940-1943`）；回弹段还会重新取一次 viewport。

## 可调参数在哪

手感参数集中在 `app/src/main/kotlin/io/github/mangi/eta/agent/overlay/CapsuleFlingPhysics.kt`：

- 速度：`VelocityWindowMillis = 120`、`VelocityStallMillis = 60`、`MinFlingVelocityPxPerSec = 400`、`GlideSeconds = 0.14`（第 26-39 行）。
- 弹簧：`FlightDampingRatio/Stiffness = 0.90/520`、`EdgeDampingRatio/Stiffness = 0.50/520`（第 46-55 行），`RestThresholdPx = 0.5`（第 58 行）。
- 越界：`OvershootWindowFactor = 1.2`、`OvershootScreenFactorX = 0.9`、`OvershootScreenFactorY = 0.35`（第 65-67 行）。
- 宿主侧收尾保护：`CAPSULE_FLING_SETTLE_GUARD_MS = 420L`（覆盖缩球形变尾段，`AgentRuntimeService.kt:2598`）、缩球阈值 `CAPSULE_BALL_FLING_FRACTION = 0.45`（第 2600 行）。
- 缩球时窗口收拢的冻结开关：`AgentOverlayCapsule.kt:309` 的 `flingRunning`（只在甩出期间为 true）。

调节建议：还想更「沉」就再加 `FlightDampingRatio`（0.95 接近临界）或继续减 `GlideSeconds`；觉得真甩都够不到边就加 `GlideSeconds`（每加 0.02 约多 16% 行程）；想更快收手加 `FlightStiffness`。

## 真机怎么验手感

1. **轻拖不滑**：按住头部慢慢拖 100px 停住再松手 → 应原地吸附（或只动几像素），完全不出现「轻轻一拖飞出去」。
2. **中速甩一段**：中速向左甩（约小臂长度/秒）→ 应滑出一段（约 200–400px）后停住；若不贴边，就停在原地不回弹。
3. **快甩出屏拐回**：从屏幕中部向右上快速甩 → 仍应短暂完全离屏，随后从最近边缘拐回、贴边时有一次小幅过冲再停住。
4. **快甩看缩球顺不顺**：快速横向甩出触发缩球 → 观察三件事：(a) 缩球过程中帧率是否连续、球是否绕目标中心收缩；(b) 贴边回弹时是否还有「突然顿一下／抖一下」（旧版在此处有一次窗口 surface 重分配）；(c) 落定后停靠位置是否与缩球前中心一致、无横移。
5. **展开容器**：展开成 ButtonGroup 后用同样动作甩 → 行应与收起态一致；缩球时若正在展开，展开→球的形变也不能与飞行打架。
6. **横竖屏/手势导航**：横屏与全屏手势导航下重复 3、4，贴边不压导航条或挖孔；旋转时若在甩出，应立刻停住并被钳回。
7. **边界回归**：甩到四个角，确认最终停在 `overlayBounds` 内；把胶囊拖出屏幕外再松手（橡皮筋段），确认能弹回。

## 无法静态确认的风险点

1. **完全出屏时是否继续合成/继续出帧**：窗口层没有坐标钳制（`lp.x/lp.y` 直接写，`1901-1915`），但窗口完全位于 Display 之外时 WMS/SurfaceFlinger/OEM（MIUI）是否仍当可见窗口、Compose frame clock 会不会被挂起，无法静态确认。若真机出现「出屏后动画停住、回来才继续」，把 `OvershootWindowFactor` 降到 ≤0.5 即可退化为部分越界。
2. **收窗「预期不可见」是推理，不是实测**：窗口水平居中 + 宿主中心锚定（`1875-1882`）推导出收窗不产生视觉位移，但若真机看到停稳后有一次横移/上下跳，需查 `Box` 布局与窗口取整（`roundToInt`）。
3. **速度来源是手指 raw 坐标**：越界段窗口有 0.35 橡皮筋，此时视觉速度 ≠ 手指速度；在屏幕外松手时甩出会略快于拖动视觉。
4. **系统 fling 上限按密度不同**：`scaledMaximumFlingVelocity` 由设备决定，缩球阈值是按它的比例（0.45）算的，不同设备触发力度略有差异。
5. **编译状态**：当前代码已通过 clean release 编译；未跑测试、未做真机手感验证。改动留在工作区，未提交。
