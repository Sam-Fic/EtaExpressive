# 判断模型驱动的上下文与工具组路由（规划）

> **本文为规划文档，尚未实现。** 落地前请先确认文末「开放问题」，并按 M1→M4 顺序推进。

## 已定事项

以下为已拍板的决策，实施时不再重新讨论：

1. **判断模型复用现有 provider 体系**：用户在设置里新建自定义 provider（baseUrl + apiKey 走既有 Room 列与既有 UI），判断模型只记 providerId / modelId。
2. **默认模式为 control（完全管控）**：模型只能打开决策选中的组，`tools_use_group` 的枚举按 allowed 收窄。
3. **延迟不作为约束**：run 开始时**同步前置等待**决策结果，超时仅作容错（默认 3s，可配置），失败 fail-open。
4. **模型参数量可配置且允许混合**：主模型 + 可选升级模型（4B 为主，命中条件时升级 27B）。
5. **只收窄不放宽**：`allowed ⊆ 用户开关允许集`，用户开关是唯一上限；决策结果**不回写 Prefs**。
6. **M1 影子模式默认开启**一段时间（只记录不生效），用于真实流量校准。

## 推荐方案摘要

每个用户回合开始时，同步调用一次判断模型（System One 协议，`/v1/systemone`），得到「是否需要工具 + 工具组多选 + 主组 + 风险等级」。决策结果作为**本次 run 的内存级 overlay**，只做两件事：

- **收窄可见性**：`allowed = gate 允许集 ∩ 决策选中集`，决定下发哪些组 schema、注入哪些提示段（技能索引、记忆、组索引）；
- **预开组**：把决策选中的组直接加入 `openedToolGroups`，省掉模型首轮的 `tools_use_group` 往返。

能力边界、风险分级、确认闸门全部保持原样，判断模型不参与权限判定。多轮继承用「用户开关 > 历史保护 > 粘性组 > 本轮决策」四级优先级。决策失败（超时 / 非 200 / `errorCode != 0` / 解析失败 / key 缺失）一律 fail-open，回退到现有全量分组行为。

---

## 1. 配置与设置入口

**相对上一版变更**：不再有两个候选存储方案，统一复用 provider 体系；新增「决策协议」标记与混合模型配置。

### 1.1 入口

| 位置 | 做法 | 参考 |
|---|---|---|
| 设置主页新增一行「判断模型」 | 仿「上下文压缩」既有形态：`SettingsKeys` 加常量、`SettingsCatalog` 登记行、指向新 `AppRoute.DecisionModel` | `ui/SettingsCatalog.kt:62`、`ui/SettingsCatalog.kt:199-205` |
| 新页面 `DecisionModelSettingsScreen` | 结构照搬压缩设置页的 provider/model 选择器与 pref 监听 | `ui/ContextCompressionSettingsScreen.kt:76-117` |

### 1.2 配置项

| prefs key | 默认值 | 含义 |
|---|---|---|
| `agent_decision_model_enabled` | `false` | 总开关；false 时完全走现状 |
| `agent_decision_shadow` | `true` | 影子模式：只记录决策，不改变行为；M1 期默认开 |
| `agent_decision_mode` | `control` | `control` / `advise` |
| `agent_decision_provider_id` | 空 | 复用 `model_providers.id`，baseUrl 与 apiKey 都在该 provider 上 |
| `agent_decision_model_id` | 空 | 主模型（建议 `lingdt-decision-1.0`） |
| `agent_decision_escalation_model_id` | 空 | 可选升级模型（建议 `lingdt-decision-1.0-27b`）；空 = 不升级 |
| `agent_decision_protocol` | `systemone` | `systemone` / `openai_chat`；当前只有前者可用，见 §2 |
| `agent_decision_timeout_ms` | `3000` | 同步等待上限 |
| `agent_decision_failure_policy` | `fail_open` | `fail_open` / `fail_closed`（不建议） |
| `agent_decision_history_turns` | `3` | 送判的最近轮数；`0` = 只看当前输入；上限 10（见 §4） |
| `agent_decision_sticky_ttl_turns` | `2` | 粘性组存活轮数 |
| `agent_decision_escalation_enabled` | `false` | 混合路由开关（M3 起可开） |

命名沿用 `agent_*` 前缀与压缩模型的先例（`config/Prefs.kt:138-140`）。新增默认值集中在 `PreferredPrefs` 与 `isEnabled` 风格方法中（`config/Prefs.kt:206`、`config/Prefs.kt:421-425`）。

### 1.3 凭证存储

- provider 的 API key 存在 Room `model_providers.api_key` 列（`data/db/ProviderEntities.kt:37-38`），设置页既有 UI 已负责写入，判断模型**不新增密钥字段**。
- OAuth 令牌走 Keystore 加密文件（`agent/auth/ProviderCredentialStorage.kt`）；判断模型用 API Key 模式，不涉及。
- **不新增 DB 列**：`agent_decision_protocol` 放在 prefs，避免为一次实验性接入做迁移。

### 1.4 端点推导（待确认）

文档中的调用形如 `<MAAS_GATEWAY>/v1/systemone`，而 provider 的 `baseUrl` 在 Eta 内通常已含 `/v1`。实现时必须做一次规范化（`baseUrl` 去掉尾部 `/`，若以 `/v1` 结尾则拼 `/systemone`，否则拼 `/v1/systemone`），并对结果做白名单校验（只允许 https）。**具体存法需按现有 provider 新建页的实际写入规则确认**，见开放问题。

---

## 2. 契约核对结论（本节为本轮实测证据）

**相对上一版变更**：从「待确认」变为已确认，并据此选定方案 B。

### 2.1 文档证据

- MaaS 平台文档明确提供 OpenAI 兼容入口 `POST https://maas-api.antdigital.com/v1/chat/completions`（`https://maas.antdigital.com/docs` 平台介绍）。
- 《LingDT-Decision 调用指南》只文档化 `POST /v1/systemone`，请求体为 `state`（string / object / array）+ `questions`（1–32 个）+ 可选 `options.dag`；四种题型 `choice` / `noul` / `score` / `multi-choice`；`options.dag.edges` 的 key 是下游问题 ID，`depends` 是上游列表；成功返回 `answers`（平铺）+ `layers[]`（DAG 才有）+ `usage` + `errorCode`；参数错误为 **HTTP 200 + `errorCode: 1`**，无 key 为 HTTP 401（`https://maas.antdigital.com/docs/lingdt-decision-api-guide`）。
- 旁证：阿里云百炼把同类决策模型暴露在 `/compatible-mode/v1/systemone`——即「兼容模式」下仍是独立的 `systemone` 路由，而不是 `chat/completions`（`https://help.aliyun.com/zh/model-studio/decision-model-api`）。

### 2.2 实测证据（2026-11-03）

| 请求 | 结果 |
|---|---|
| `POST /v1/chat/completions`，`model=lingdt-decision-1.0`，`messages=[{role:user,...}]` | **HTTP 400** `{"error":{"message":"Protocol conversion is not supported","type":"protocol_conversion_not_support"}}` |
| `POST /v1/systemone`，同一 key、同一 model，`state` 对象 + `noul` 问题 | **`errorCode: 0`**，`answers.needs_tools.noul = 0.0088`，`usage = 57 in / 0 out` |

结论：**OpenAI 兼容层无法承载 decisions 语义**。不是「缺参数」，而是网关拒绝协议转换（`protocol_conversion_not_support`），因此 `response_format` / `json_schema` 这条路不成立（决策模型不产出文本，只产出带类型的概率答案，聊天协议没有对应载体）。

### 2.3 采用方案 B：独立决策 client + 复用 provider 凭证

```
ProviderConfig(providerId) ──► baseUrl + apiKey（既有 Room 列）
        │
        └──► AgentDecisionModelClient（新增，仅实现 /v1/systemone 契约）
                 ├─ buildPayload(state, questions, options.dag)
                 ├─ 校验 errorCode / answers 类型 / 概率范围
                 └─ 解析为 AgentDecisionResult
```

**适配层复杂度（需计入排期）**：

- 新增一个 HTTP client（OkHttp 已有依赖），不复用 `AgentModelClient` 的 chat/responses 分支（`agent/model/AgentModelClient.kt:460` 的 `ModelConfig` 是聊天语义）。
- 需要独立的结果模型与解析器：`ChoiceAnswer` / `MultiChoiceAnswer` / `ScoreAnswer` / `NoulAnswer`，**逐字段校验**（`answers[qid].type` 与请求一致、`choice ∈ criteria`、`noul ∈ [0,1]`、`score ∈ [0, 等级数-1]`、`choices ⊆ 声明标签`）。
- 无流式需求（决策是一次前向），因此不需 SSE 解析，也不需 `RoundCapabilitySignature` 那套轮内重建逻辑。
- 未来若 MaaS 真的开放决策模型的 chat 兼容层，只需把 `agent_decision_protocol` 切到 `openai_chat` 并加一条转换实现，上层（`AgentDecisionRouter`）不动。

---

## 3. 运行时架构（同步前置等待）

**相对上一版变更**：去掉「并行预取 + 软超时 + 第二轮回填」的全部复杂度。

### 3.1 调用时序（每个用户回合一次）

```
用户发送 ──► Run 开始
   │
   ├─ 收集 state：当前输入 + 最近 N 轮文本摘要 + gate 允许的组枚举 + 上轮粘性组
   ├─ 【同步】POST /v1/systemone   （超时 agent_decision_timeout_ms，默认 3s）
   │     失败 ──► fail-open：allowed = gate 全集，sticky 保留，记 warn
   │
   ├─ 计算 allowed = gate ∩ decision.selected（∪ 历史保护组，见 §4）
   ├─ 预开组：openedToolGroups += allowed ∩ decision.selected
   ├─ 组装系统提示（按 allowed 决定注入哪些段，见 §5）
   ├─ 组装工具目录（只含 basic + 已打开组，见 §5）
   └─► 首个模型请求
```

### 3.2 为什么同步前置是更好的选择

- **首轮即生效**：不存在「第一轮按保守集、第二轮才应用决策」的语义分裂，用户看到的一次回答就是最终行为。
- **实现简单**：无需并发编排、无需超时后回填、无需处理「决策晚到但轮次已推进」的竞态。
- **提示词只组装一次**：避免 run 中途改 system 前缀（`agent/model/AgentModelClient.kt` 的 `rewriteSystemPrefix` 每轮重排，会反复打掉 prompt cache）。
- **延迟可控**：4B flat 档实测端到端 0.62–1.02s，27B 1.8–5.1s（上轮实验，含网络）。同步等待只影响首 token，对整体任务时长可忽略（已定事项 3）。

### 3.3 state 内容

| 字段 | 内容 | 保护 |
|---|---|---|
| `user_input` | 当前用户输入原文 | — |
| `recent_turns` | 最近 N 轮 user/assistant **文本**（截断为每条 ≤300 字） | 不含工具返回原文、不含图片、不含 thinking |
| `available_groups` | gate 允许的 8 个延迟组 id + 一行描述 | 约束模型只能在此枚举内选 |
| `sticky_groups` | 上轮仍在 TTL 内的组 | 允许模型沿用或主动放弃 |
| `is_tool_loop_continuation` | 是否同一 run 的续轮（本设计恒为 false，仅预留） | — |

### 3.4 超时与降级

| 场景 | 行为 | 日志级别 |
|---|---|---|
| 超时（> `timeout_ms`） | fail-open，allowed = gate 全集 | warn |
| HTTP 非 200 | fail-open | warn（记录状态码与响应体首 200 字） |
| `errorCode != 0` | fail-open | warn（记录 `errorDesc`） |
| `answers` 缺失 / 类型不符 / 概率越界 | fail-open | warn |
| provider 未配置 / key 为空 | 不发起请求，fail-open | info（每会话仅一次） |
| `failure_policy=closed` | control 下退化为「仅 basic 常住 + 全部延迟组关闭」 | error（UI 提示「判断模型不可用，已进入保守模式」） |

### 3.5 混合模型路由（4B / 27B）

**相对上一版新增**。

- **配置**：`agent_decision_model_id` = 主模型（4B），`agent_decision_escalation_model_id` = 升级模型（27B），`agent_decision_escalation_enabled` 开关。
- **判定层位置：决策后、组装提示之前**（一次 run 内最多两次模型调用）。不用「决策前关键词分类器」——关键词只能猜意图，而决策结果自带概率与风险等级，更可靠。
- **升级触发条件（任一命中即升级）**：

| 条件 | 阈值（初值，M3 调参） | 依据 |
|---|---|---|
| risk 判定为 `confirm_destructive` | score 最高档 | 高风险操作值得更准 |
| 多选置信度低 | `confidence < 0.7` | 4B 实测 multi-choice confidence 0.57–0.74 |
| 选中 device 或 calendar | 任一命中 | 4B 已知弱区（#19 翻转、#13 多勾） |
| 输入含破坏性动词 | 删除 / 清空 / 转账 / 支付 / 格式化 / 取消 | 廉价前置词表，仅作补充 |

- **额外延迟预算**：仅升级路径 +0.5–1.5s（27B 在 4B 之后串行）；非升级路径 0。若升级后结论与主模型一致，直接采用升级结果，不做仲裁。
- **不做双向投票**：两个模型同时跑的延迟翻倍且收益不明，M3 先做单向升级。

---

## 4. 两种控制模式

**相对上一版变更**：默认改 control，并补失败体验与一键放行。

### 4.1 control vs advise

| | control（默认） | advise |
|---|---|---|
| 谁决定可见组 | `allowed = gate ∩ 决策`；`tools_use_group` 的 enum 用 allowed 替换 `availability.openable`（`agent/model/AgentToolGroups.kt:253`、`agent/model/AgentToolGroups.kt:430-447`） | 启动时预开决策选中的组，`tools_use_group` 仍可打开 gate 允许的任意组 |
| 大模型自加载 | 只能打开 allowed 内、且决策未选中的组 | 可打开 gate 全集 |
| 误判代价 | 能力缺失（需放行） | 仅少一次预开，多一轮往返 |
| 适用 | 省 token 优先、输入集中 | 兼容优先、开放探索 |

### 4.2 control 模式的失败体验设计

误判导致能力缺失时，必须**让用户看见并且一键恢复**，不能静默。

1. **执行层提示（给模型）**：`tools_use_group` 请求的组不在 allowed 内时，返回与现有「未开启」区分开的文案：
   - 决策收窄：`本次运行未启用「<组名>」；如需使用，可让用户在设置中切换到「建议模式」，或点按界面上的「本次允许」。`
   - 用户关闭：沿用现有「用户已在设置中关闭」文案（`agent/model/AgentToolGroups.kt:335` 附近）。
2. **界面提示（给用户）**：当一次 run 出现「决策收窄且模型请求该组」时，在输入区上方出现一次性 chip：`判断模型本次未启用：日历、设备　[本次允许]　[去设置]`。
3. **一键放行语义**：
   - 点「本次允许」→ 写**会话级** override（内存，不进 Prefs），`allowed = gate 全集`，随后**重放当前用户输入**（新 run，保留对话历史）。
   - 文案必须提示副作用：`已执行的动作不会回滚`；若该 run 已有写操作执行成功，则不自动重放，改为「下一轮起生效」。
4. **区分「模型判错」与「模型不可用」**：fail-open 时 UI 显示 `判断模型不可用，已按全量放行`（info，不出现 chip），避免用户误以为能力被主动关掉。

### 4.3 advise 模式的机制

- 不加新工具：`tools_use_group` 已是成熟的自加载机制（`agent/tool/AgentLocalTools.kt:357-373`），新增 `enable_tool_group` 会造出第二条真相。
- 预开优先级：决策选中的组直接加入 `openedToolGroups`（`agent/tool/AgentLocalTools.kt:193-196`）。
- **TTL/回收**：run 内不回收（沿用现有 run 级生命周期）；跨 run 由 sticky TTL 控制（§5）。

---

## 5. 多轮继承与粘性

**相对上一版变更**：历史可见范围变为配置项并加上限；继承优先级收紧为四级。

### 5.1 代码事实（必须遵守）

1. 历史中出现过某组工具 → 该组被并回 `initialOpenedGroups`（`agent/model/AgentModelClient.kt:162-163`），每轮重建再并一次（`agent/model/AgentModelClient.kt:282`）。
2. 因此**关组不会让历史 tool call 失效**；模型重放旧调用时仍能通过校验。
3. 但「打开过、却从未成功调用组内工具」的组不会进历史 → 下一回合自动丢失。这是现状的真实缺口，也是引入 sticky 的直接理由。
4. 用户关闭开关时该组从 `opened` 剔除（`agent/model/AgentToolGroups.kt:260-266`），调用会得到不可补救的「未启用」提示（`agent/model/AgentModelClient.kt` 的 `undeclaredReason` 路径）。**决策不得改变这个优先级**。

### 5.2 继承优先级（高 → 低）

```
1. 用户开关 gate（唯一上限，决策不可突破）
2. 历史保护：历史中出现过工具调用的组（决策不得移除）
3. 粘性组 sticky：上轮决策选中且未过期（TTL 递减，命中重置）
4. 本轮决策选中
```

实现落点：在 `AgentModelClient.complete` 的 `initialOpenedGroups` 计算处（`agent/model/AgentModelClient.kt:162-163`）注入「决策 allowed」与「sticky」，保持既有 order 不变，只在最后做一次 `∩ allowed`，且对第 2 类豁免。

### 5.3 sticky 规则

- 存储：按 `conversationId` 的内存 map（进程重启丢失）；M4 再评估是否落 DB。
- TTL：默认 2 轮，`agent_decision_sticky_ttl_turns` 可配。
- 清零条件：当前输入与 sticky 组无任何词面关联，且本轮决策明确不选这些组 → 立即清零（避免「一直开着」白占 schema）。
- 上限：sticky 最多 3 个组，超出按最近使用淘汰。

### 5.4 历史窗口与隐私

| `agent_decision_history_turns` | 行为 |
|---|---|
| `0` | 只发当前输入，多轮连续性完全依赖 sticky 与历史保护 |
| `1–3`（默认 3） | 最近 N 轮文本摘要，每条截断 300 字 |
| `4–10` | 同上，但总字符数超 4000 时从最旧开始丢弃 |
| `>10` | 钳制为 10（UI 不让填更大） |

- **不提供 full-history 选项**：只提供轮数，避免把整段对话（含工具结果）发往第三方。
- **隐私提示位置**：设置页该输入框下方常驻说明：`判断模型会读取最近 N 轮对话文字摘要（不含工具返回原文、图片与思考过程），用于保持工具组连续。设为 0 则只发送当前输入。`
- 首次开启总开关时弹一次确认（含「对话内容将发送至该 provider」）。

---

## 6. 提示词分层改造

**相对上一版变更**：明确每段归属与常驻基线，并标注实现落点。

| 段 | 落点 | 可否被决策关闭 | 说明 |
|---|---|---|---|
| 核心人格 `config.systemPrompt` | `agent/model/AgentPromptBuilder.kt:187-188` | 不可 | 身份基线 |
| 子代理规则段 | `agent/model/AgentPromptBuilder.kt:190-193` | 可（已有 `delegationAvailable`） | 纯闲聊无需派发 |
| 视觉/身份/能力说明 | `agent/model/AgentPromptBuilder.kt:194-235` | 不可（其中设备/日程指引可随组） | 静默省略会让模型谎称无能力 |
| 记忆段 | `agent/model/AgentPromptBuilder.kt:487` | 可 | token 大头之一 |
| 技能索引段 | `agent/model/AgentPromptBuilder.kt:488` | 可 | 每条技能一行 |
| 延迟组索引段 | `agent/model/AgentPromptBuilder.kt:494-496` | 可 | `AgentToolGroups.indexMessage`（`agent/model/AgentToolGroups.kt:384`）一行/组 |
| 已关闭能力说明段 | `agent/model/AgentPromptBuilder.kt:504-508` | **不可** | 源码注释已明确：静默省略会让模型答「我没有这个能力」 |

**最省常驻基线（闲聊态）**：人格 + 基础交互 + 已关闭能力说明（+ 可选一行组索引）。**不含**记忆、技能索引、8 组索引、任何延迟组 schema。

**量级与记账口径**：固定开销按「系统消息 + 工具 JSON 全文」估算（`agent/model/AgentRequestOverhead.kt:15-74`），目录与提示必须同源构建（同文件 26-63 行注释）。闲聊基线预计为现状的 30%–50%（记忆段与技能索引是大头），**具体数字由 M1 用 `AgentRequestOverhead.estimate` 打点实测**，不写死进验收。

**风险**：段集合变化会改变 system 前缀，破坏 prompt cache（`agent/model/AgentPromptBuilder.kt:483-486` 的 Anthropic 边界）。因此决策**每用户回合一次**，不在 run 内反复改段。

---

## 7. 安全不变式

**相对上一版变更**：补充 control 默认下的具体落点。

1. 决策只影响**可见性**（下发哪些 schema、注入哪些段），不影响**可执行性**。
2. 风险分级与确认闸门在执行前照旧：`AgentRiskAssessment.scope / requiresConfirmation`（`agent/tool/AgentRiskPolicy.kt:26-35`）与危险操作确认闸门，决策结果**不参与** `assess()`。
3. 决策输出只允许「组 id 集合 + 主组标签 + 风险分档」；**不得**输出工具名、参数或「跳过确认」。
4. `allowed ⊆ gate 允许集`：决策永远不能打开用户关掉的组（`agent/tool/AgentLocalTools.kt:338-351` 的 `toolGroupGate` 是第一道闸）。
5. 历史保护优先于决策：历史出现过工具调用的组不因决策移除（避免校验器连续拒绝）。
6. 决策失败 fail-open；`fail_closed` 仅显式配置，且必须有 UI 警示。
7. 决策结果**不回写 Prefs**，纯 run 级内存态；设置页永远是唯一真相。

---

## 8. 可观测设计

**相对上一版变更**：明确字段清单与「影子模式」输出。

对齐现有日志接口（`core/AgentLogger.kt`，`debug/info/warn/error` + 文件落盘）：

| 字段 | 内容 |
|---|---|
| `run_id` / `conversation_id` | 归因 |
| `mode` / `shadow` | control / advise；是否影子 |
| `model` / `escalated` | 主模型；是否升级、升级原因 |
| `state_chars` / `history_turns` | 送判材料规模（**不记录全文**） |
| `http_status` / `error_code` / `error_desc` | 失败归因 |
| `latency_ms` | 同步等待耗时 |
| `selected_groups` / `allowed` / `sticky` / `opened_after` | 决策与最终生效集合 |
| `fallback_reason` | fail-open 原因 |
| `shadow_would_be` | 影子模式下「若生效将收窄到」 |

级别：成功 `debug`；失败/降级 `warn`；未配置 `info`（每会话一次）。影子模式额外输出一行对比（现状组 vs 决策组），便于离线统计准确率。

---

## 9. 分阶段落地

**相对上一版变更**：control 为默认，M2 成为主路径，验收与回滚拆细到子开关。

### M1 配置 + 影子模式（`shadow=true`, `enabled=true`）

做：设置页、prefs、`AgentDecisionModelClient`、结果解析与校验、影子日志；不改变任何行为。

验收：
- 19 条既有用例 + 真实会话上，影子日志可稳定产出；
- `AgentRequestOverhead.estimate` 打点产出「现状 vs 闲聊基线」的 token 数字；
- 零行为差异（开关关掉与打开但影子时，工具目录与 prompt 完全一致）。

回滚：`agent_decision_model_enabled=false`（默认即关）。

### M2 control 主路径（拆三个子开关，逐级放量）

| 子阶段 | 内容 | 验收 | 单独回滚开关 |
|---|---|---|---|
| M2.1 提示段裁剪 | 仅按聊天/非聊天决定是否注入记忆、技能索引、组索引 | 闲聊固定开销下降 ≥40%（实测）；闲聊场景无「我没有这个能力」类回答 | `agent_decision_trim_sections=false` |
| M2.2 allowed 收窄 | `tools_use_group` enum 按 allowed 收窄；预开组 | 组选择准确率 ≥ 现状（19 案等效 ≥17/19）；误关率 < 5%（人工抽检 50 条） | `agent_decision_narrow_groups=false` |
| M2.3 粘性继承 | 四级优先级 + sticky TTL | 「只开未用」场景下一回合仍可用；历史 tool call 重放 0 失败 | `agent_decision_sticky=0`（等效关闭） |

整体验收：失败/超时 100% fail-open；control 下的「一键放行」路径手工验证通过；对抗集（闹钟/日程）无双勾误关。

回滚：`agent_decision_mode=advise` 或总开关关。

### M3 混合模型 + advise

做：升级条件（§3.5）、advise 模式、差异化文案。

验收：
- 升级路径只在预期条件触发（抽样 100 条，误升级 < 10%）；
- 4B 单独 19 案 ≥ 17/19，升级后 ≥ 18/19；
- advise 模式下一轮内自开组成功率不低于现状。

回滚：`agent_decision_escalation_enabled=false`；`agent_decision_mode=control`。

### M4 缓存与降级优化

做：决策结果缓存（key = 输入哈希 + available_groups 集合 + sticky）；sticky 持久化评估；诊断面板。

验收：缓存命中率有报表；缓存命中时决策耗时 < 10ms；降级路径在断网/错 key/错模型名下均有正确 UI。

回滚：`agent_decision_cache_enabled=false`（新增开关）。

---

## 10. 测试计划

**单测**
- `allowed = gate ∩ decision` 集合运算：未知组、空集、全选、重复项。
- 继承优先级：历史保护 vs 决策移除的冲突；sticky TTL 递减/重置/清零/上限淘汰。
- 降级矩阵：超时、非 200、`errorCode != 0`、`answers` 类型不符、`choice` 越界、`noul ∉ [0,1]`、key 缺失。
- 提示段开关：闲聊态不含记忆/技能/组索引；「已关闭能力说明」始终存在。
- 历史窗口：0 / 3 / 10 / 超限钳制；总字符超 4000 的截断顺序。

**集成**
- 决策超时（注入 5s 延迟）→ 整体仅多等 `timeout_ms`，随后 fail-open 正常回答。
- 组继承：run1 开 A 组但未调用工具 → 同会话 run2 仍开（sticky）。
- 历史回放：历史含 A 组调用、本轮决策收窄到不含 A → 校验器不报错、不终止整轮。
- 一键放行：重放路径与「已有写操作则仅下轮生效」分支。
- token 对比：`AgentRequestOverhead.estimate` 前后差值断言。
- key 缺失 / 错误 model 名 / 错误 baseUrl → UI 提示正确，run 不崩。

**手工验证**
- 设置页新建 MaaS provider → 选为判断模型 → 影子日志出现。
- 中英混杂输入、超长历史（触发裁剪）、用户中途关组开关。
- 断网、401（错误 key）、400（模型名不存在）。

---

## 11. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 决策延迟叠加首 token | 4B 0.6–1.0s、27B 1.8–5.1s | 已接受（已定事项 3）；升级路径可控 |
| 前缀变化打掉 prompt cache | token 收益被抵消 | 每用户回合一次；M2 用真实账单验证净值 |
| 4B 在 device/calendar 上误判 | 能力缺失或多开组 | 升级到 27B；或加确定性「24 小时规则」兜底（M2 可选） |
| control 误判导致能力缺失 | 体验差 | 一键放行 + 明确文案（§4.2） |
| 非 OpenAI 兼容协议 | 供应商锁定、适配成本 | 协议层抽象 `agent_decision_protocol`，新供应商只写转换器 |
| 判断模型成为单点故障 | run 无法开始 | 默认 fail-open；超时 3s 上限 |
| 历史外发第三方 | 隐私 | 只发文本摘要、不含工具结果/图片/思考；轮数上限 10；首次开启确认 |

## 开放问题（需拍板）

1. **provider baseUrl 的实际存法**：Eta 新建 provider 时是否已写入 `/v1` 后缀？直接决定 `/v1/systemone` 的拼接与规范化实现。
2. **升级触发阈值**：§3.5 的 `confidence < 0.7`、risk=destructive、device/calendar 命中三条是否都要？M3 需用样本调参。
3. **sticky 是否需要跨进程持久化**：M2 先内存（进程重启丢失），是否可接受？
4. **一键放行是否允许重放已执行动作**：建议「有写操作则仅下轮生效」，是否同意？
5. **升级模型是否复用同一 provider**：建议是（同一 baseUrl/key），请确认。
6. **是否要在 M2 内置确定性时间规则兜底**（24 小时 → 闹钟 / 超过 → 日程），以弥补 4B 在 #19 类输入上的翻转。
7. **影子期时长**：默认开多久（1 周 / 2 周 / 达到 X 条样本）再切 control？
8. **决策日志是否需要进入对话分析导出**（`EtaConversationAnalysisFormat` 已有 `openedGroups` 载体），便于线上复盘。
