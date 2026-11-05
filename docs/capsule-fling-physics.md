# 胶囊甩出物理动画 —— 实现说明

## 结论

- **实现方式**：松手后由 Compose 侧 `Animatable<Offset, AnimationVector2D>`（以“窗口水平中心 x + 顶边 y”为坐标）依次跑两段真弹簧：
  1. **飞行段**：目标 = 起点 + 松手速度 × 0.24s（可为屏外），初速度 = 松手速度，`spring(dampingRatio = 0.70, stiffness = 520, visibilityThreshold = 0.5px)`；
  2. **贴边回弹段**：飞行落点越界时，以当前速度接 `spring(dampingRatio = 0.50, stiffness = 520)`，目标 = 最近边缘内侧（该轴贴边、未越界轴不动）。
  没有 tween、没有硬夹紧、没有两段 tween 拼接。
- **速度取样**：自建环形采样（最多 24 点），抬手时对最近 120ms 做最小二乘直线拟合；抬手前 60ms 无新采样即判“停顿”，速度归零，只走越界回弹；低于 260px/s（≈92dp/s）视为轻放，不滑行。
- **越界上限**：`min(窗口尺寸 × 1.2, 屏宽 × 0.9)`（横向）/ `min(窗口尺寸 × 1.2, 屏高 × 0.35)`（纵向），这里说的“窗口尺寸”是**松手时的占位**（缩球则取球窗 80×68dp）。以 1080×2400、density 2.8125 为例：
  - 收起胶囊（272dp ≈ 765px 宽）→ 横向上限 918px；而整窗完全出屏只需中心越界约 383px，所以**可以完整出屏**，余量充足。
  - 展开容器（368dp ≈ 1035px 宽）→ 横向上限 `min(1242, 972) = 972px`，同样可完整出屏。
  - 缩球（80dp ≈ 225px）→ 横向上限 270px，完整出屏只需越界 113px，同样够。
  - 纵向：上限 `min(窗高 × 1.2, 840px)`；对 200px 级胶囊窗约 240px，足以完整移出上/下边。
- **限制来源**：越界上限不是 WindowManager 的硬限制（见下），而是本功能主动设的物理上限，用来约束“完全出屏后不可见的时长”。窗口层本身没有钳制：`applyCapsuleWindowPositionAt` 直接写 `lp.x/lp.y`（`AgentRuntimeService.kt:1901-1915`），胶囊 `LayoutParams` 用 `TOP|LEFT` + `FLAG_LAYOUT_IN_SCREEN` + `setFitInsetsTypes(0)` + 挖孔 ALWAYS（`AgentRuntimeService.kt:2052-2085`，关键项在 2072-2075），没有边界校正。

## 现状取证（改动前）

- 手势接在头部 `TitleDragSurface` 的 `pointerInteropFilter` 上（`AgentOverlayCapsule.kt:472-544`），用 `AgentOrbDragGesture` 判定 slop；位置不存于 Compose，逐帧回调宿主：
  - 拖动：`onDrag(dx, dy)` → `handleCapsuleDrag` 在 `overlayBounds` 内 1:1、越界按 0.35 橡皮筋（`AgentRuntimeService.kt:1962-1981`）。
  - 抬手：原实现是 `OverScroller.fling` + Choreographer 帧循环（本次已删除），**最终落位被 `coerceIn` 硬夹回屏幕**，所以越界减速过程根本不可见，表现为“到边即停”。注意：工作区此前的版本其实已有 `OverScroller` 惯性与 56dp over 余量，真正缺的是“允许窗口可见地越界 + 贴边弹簧收尾”。
- 收起胶囊与展开容器共用同一套窗口与拖动路径（只有窗口尺寸不同），缩球是同一窗口的尺寸/内容 morph，所以两种形态不需要两套物理。

## 两种形态的行为

| 形态 | 甩出 | 缩球 |
| --- | --- | --- |
| 收起胶囊（272dp 宽） | 按整窗尺寸算边界，可横向越界约一个窗宽后回弹贴边；竖向可越过系统栏/导航条（`overlayBounds` 已扣安全区） | 大力横向甩（≥ 系统 fling 上限 45% 且横向占优）触发缩球，滑行与 320ms 形变同时进行，物理按 **球窗 80×68dp** 规划边界 → 球收完正好贴边 |
| 展开容器（368dp 宽、高度随阶段变化） | 同上，窗口占位取松手时 `lp.width/height` | 同样可缩球；内容形变期间窗口绕动画中心对称收放，物理中心不漂移 |

坐标系用“中心 x + 顶边 y”：缩球时窗口宽度逐帧收窄，`left = center - 当前宽/2`（`AgentRuntimeService.kt:2030-2037`），所以贴边目标不会随形变平移。

## 打断与手势冲突

- **甩出中再按下**：`ACTION_DOWN` 立即 `job.cancel()` 并把最后落点经 `onFlingFinish` 交还宿主（`AgentOverlayCapsule.kt:473-484`），位置不跳变。
- **继续拖**：越过 slop 才 `onDragStart()`（`AgentOverlayCapsule.kt:495-500`），宿主此刻夺回位置所有权并清甩出 flag（`AgentRuntimeService.kt:1945-1956`）。
- **点击**：未过 slop 仍是点击；`toggleCapsule` 会先清位置所有权并按当前尺寸钳回界内（`AgentRuntimeService.kt:1731-1735`），避免“甩出被打断后停在半屏外点一下”卡在界外。纯点击也不再残留 `capsulePointerCaptured`（改动前每次 DOWN 都会置 true 且点击后无人清）。
- **甩出过程中不可点击**：窗口在动，按下即打断；点击语义只在按下不动、抬起时成立。
- **旋转/inset 变化**：Compose 侧 `LaunchedEffect(configuration)` 取消动画并回调 `onFlingCancel`（`AgentOverlayCapsule.kt:388-395`），宿主清所有权并按新边界立即钳回（`AgentRuntimeService.kt:1940-1943`）；回弹段还会重新取一次 viewport。

## 可调参数在哪

全部在新文件 `app/src/main/kotlin/io/github/mangi/eta/agent/overlay/CapsuleFlingPhysics.kt`：

- 速度：`VelocityWindowMillis = 120`、`VelocityStallMillis = 60`、`MinFlingVelocityPxPerSec = 260`、`GlideSeconds = 0.24`（第 26-38 行）。
- 弹簧：`FlightDampingRatio/Stiffness = 0.70/520`、`EdgeDampingRatio/Stiffness = 0.50/520`（第 45-53 行），`RestThresholdPx = 0.5`（第 56 行）。
- 越界：`OvershootWindowFactor = 1.2`、`OvershootScreenFactorX = 0.9`、`OvershootScreenFactorY = 0.35`（第 63-65 行）。
- 宿主侧收尾保护：`CAPSULE_FLING_SETTLE_GUARD_MS = 420L`（覆盖缩球形变尾段，`AgentRuntimeService.kt:2598`）、缩球阈值 `CAPSULE_BALL_FLING_FRACTION = 0.45`（第 2600 行）。

手感调节建议：想更“甩”就降 `EdgeDampingRatio`（0.4 左右过冲更明显）或加 `GlideSeconds`；想更快收手就加 `Stiffness` 或减 `GlideSeconds`；想更少出屏就降 `OvershootWindowFactor`。

## 真机怎么验手感

1. **慢拖轻放**：按住头部慢慢拖 100px 左右停住再松手 → 应无惯性滑行（或只滑动一点点），越界时平滑弹回，不应出现速度快照。
2. **快速甩向右上角**：从屏幕中部向右上快速划 → 胶囊先按速度飞向右上，可短暂完全离开屏幕；随后从屏外拐回、贴住右边（竖向取最近边），贴边时有一次小幅过冲再停住。
3. **甩出后立刻再按**：快速甩向左边后在回弹途中按下 → 胶囊应停在当前像素位置并可直接拖动，无瞬移；若按下后不动直接抬起（点击），胶囊应被钳回最近边缘并切换形态。
4. **缩球停靠**：在收起胶囊上向右大力甩（速度明显大于慢拖）→ 触觉一记、内容收成球、球沿速度方向滑出并贴右边停靠；慢拖后松手不应缩球。
5. **展开容器**：展开成 ButtonGroup 后用同样动作甩 → 行为应与收起态一致，且停靠后展开/收起不跳位。
6. **横竖屏/手势导航**：横屏与全屏手势导航下重复 2、4，贴边不应压到导航条或挖孔；旋转过程中若有甩出，应立刻停住并被钳回。
7. **边界回归**：甩到四个角，确认最终一定停在 `overlayBounds` 内；把胶囊拖出屏幕外再松手（橡皮筋段），确认能弹回。

## 无法静态确认的风险点

1. **完全出屏时是否继续合成/继续出帧**：窗口层没有任何坐标钳制（`lp.x/lp.y` 直接写，见 1900-1914），但窗口完全位于 Display 之外时，WMS/SurfaceFlinger/OEM（MIUI）是否仍把它当作可见窗口、Compose 的 frame clock 会不会被挂起，无法静态确认。若真机上出现“出屏后动画停住、回来才继续”，退回方案是把 `OvershootWindowFactor` 降到 ≤ 0.5（只做部分越界，不做全出屏），其余代码不用改。
2. **`Animatable.animateTo(initialVelocity = …)` 的 API 形态**：按 `androidx.compose.animation.core` 1.13-alpha01 的既有签名实现（`animateTo(targetValue, animationSpec, initialVelocity, block)`），未编译验证。
3. **速度来源是手指 raw 坐标**：越界段窗口有 0.35 橡皮筋，此时视觉速度 ≠ 手指速度；在屏幕外松手时甩出会略快于拖动视觉。若真机觉得“脱手突然加速”，可在 `prepareCapsuleFling` 里按是否越界乘 0.35。
4. **系统 fling 上限按密度不同**：`scaledMaximumFlingVelocity` 由设备决定，缩球阈值是按它的比例（0.45）算的，不同设备触发力度会略有差异。
5. **未编译/未跑测试**：按项目约定只做静态修改，所有行号基于当前工作区。改动未提交。
