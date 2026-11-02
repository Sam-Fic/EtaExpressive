# Tooltip 与无障碍规范

本文是「长按 Tooltip + 无障碍语义」的唯一规则出处。代码细则由 `DesignSystemRulesTest` 的 R5 执法（改规则先改测试，再改文档）。

## 1. 什么时候必须有长按 Tooltip

| 控件 | Tooltip | 说明 |
|---|---|---|
| 纯图标按钮（无可见文字） | **必须** | 图标语义不自明，例如「更多」「历史」「复制」 |
| 图标 + 无可读文字的入口 | **必须** | 图标承担全部含义的入口 |
| 被 `maxLines`/`Ellipsis` 截断的标签 | **必须** | 长按可看全文 |
| 开关、滑块、复选行 | 不需要 | 状态由 `stateDescription` / 整行文案表达 |
| 已有完整可见文字的动作按钮 | 不需要 | 避免与按钮文字重复 |
| 纯装饰图标 | 不需要 | `contentDescription = null` |

例外：长按已被其它动作占用（如输入区语音按钮的长按切模式）时，不要再叠 Tooltip，改用 `contentDescription` 表达；同一行多个图标按钮各自包裹，不要做「整行一个 Tooltip」。

Tooltip 是**补充**信息，不能成为唯一信息来源：控件必须同时具备可读的 `contentDescription`（或可见标签）。

## 2. 文案规则

- 写**动作结果**，不写控件形态：`删除会话`，不写 `垃圾桶按钮`。
- 禁用态写**原因**：`当前会话运行中，结束后可删除`；未禁用时不写状态。
- 选中/激活态由 `stateDescription` 表达：`已开启` / `已关闭` / `朗读中`，不写进 Tooltip 文案。
- 单行、尽量 ≤ 16 个汉字；超出时用短句 + 句号，不换行堆叠。
- 文案一律走 `stringResource`，禁止硬编码中文；新增文案放 `res/values*/strings_accessibility.xml`（避免与正在修改的 `strings.xml` 冲突）。

## 3. 交互规则

- 触发：**长按**（500ms 由 M3 `TooltipBox` 默认），点击不弹 Tooltip。
- 触觉：长按由 `TooltipBox` 走 `LocalHapticFeedback(LongPress)`，统一被 `ProvideEtaHapticFeedback` 接管到 `TouchHaptics.longPress`；业务代码不要自己再震一次。
- 显示时长/位置：全部取 `TooltipDefaults`，不自定义颜色、字号、圆角。
- Tooltip 不拦截点击；控件本身仍可正常点按。

## 4. 语义规则

- `contentDescription` = 可见文字缺失时的**唯一标签**；与 Tooltip 文案默认同源，避免两套说法。
- `Icon` 作为按钮内容时只保留一份描述：描述放在按钮语义或图标上，二者不要重复。
- 装饰性图标（与相邻文字重复、纯氛围图形）一律 `contentDescription = null`。
- 一个可点击容器只暴露一个语义节点；容器已有 `contentDescription` 时，子图标置 `null`。
- 开关/滑块：用 `Modifier.semantics { stateDescription = ... }`，必要时补 `Role.Switch`；行点击与开关点击不要产生两个独立动作。
- 自绘 `Box + clickable`：必须显式 `role = Role.Button` 与 `contentDescription`；触控目标 ≥ 48dp（M3 `IconButton` 已自带，自绘容器需自行保证）。
- 被截断文字的 `Text` 用 `Modifier.semantics { contentDescription = 全文 }` 或 Tooltip 提供全文。

## 5. 代码规范

- 业务页面**禁止**直接写 `TooltipBox` / `PlainTooltip` / 自定义 `Popup` 提示（R5 执法）。
- 统一入口：`io.github.mangi.eta.ui.adaptive`
  - `EtaTooltip(text, enabled, anchor) { content }`：通用包裹，用于任意已有控件。
  - `EtaTooltipIconButton(icon, contentDescription, onClick, ...)`：纯图标按钮一行搞定 Tooltip + 语义 + 点击触觉。
  - `Modifier.etaTooltipSemantics(contentDescription, stateDescription, role)`：自绘可点击容器补语义。
- 懒加载列表里的 Tooltip 包裹成本可忽略；同一行多个图标按钮各自包裹，不要做「整行一个 Tooltip」。

示例：

```kotlin
EtaTooltipIconButton(
    icon = Icons.Rounded.Delete,
    contentDescription = stringResource(R.string.action_delete),
    onClick = onDelete,
    destructive = true,
)

// 已有控件只补 Tooltip
EtaTooltip(text = stringResource(R.string.action_more)) {
    IconButton(onClick = onMore) {
        Icon(Icons.Rounded.MoreVert, contentDescription = null)
    }
}
```

## 6. 检查清单

- [ ] 纯图标按钮有 Tooltip 且有 `contentDescription`
- [ ] 图标按钮触控目标 ≥ 48dp
- [ ] 截断标签可长按看全文
- [ ] 开关/滑块有 `stateDescription`
- [ ] 装饰图标 `contentDescription = null`
- [ ] 没有业务页面直写 `TooltipBox` / `PlainTooltip`
- [ ] 新增文案在 `strings_accessibility.xml`
