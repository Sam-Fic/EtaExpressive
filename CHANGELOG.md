# Changelog

All notable changes to **EtaExpressive** are documented here. Entries are grouped per release and follow the [Keep a Changelog](https://keepachangelog.com/) spirit. Each version maps to a `git` tag on `Sam-Fic/EtaExpressive`.

<!-- Ordered newest-first; generated from git tag history. -->

## [v5.5.0] - 2026-11-14

v5.4.1 之后 70 个提交的功能批：新增米家直连、管理列表批量多选、宽屏设置 list-detail、
前缀缓存归因落库，工具调用审查收敛为四档；许可证由 PolyForm Noncommercial 1.0.0
改为 GPL-3.0；三项安全加固。

### Added

- feat(mijia): 米家直连工具与设置页（取自 yooma1/Eta666），顺带修复电源键拦截两处遗留

- feat(ui): 七个管理列表页统一多选批量操作，模型列表多选入口不贴屏幕右缘，媒体搜索
  多选工具栏补进退场动画

- feat(settings): 宽屏设置页 list-detail——左栏常驻设置主页，右栏独立返回栈承载
  二级/三级页

- feat(usage): 前缀缓存归因随账单落库并进入会话导出；工具目录缓存优先档（suffix）
  与「看屏幕」意图、缓存命中率占位

- feat(context): 归档历史支持 query 聚焦检索 + 摘要标注压缩覆盖范围；摘要请求禁调
  工具保前缀缓存，新增关键实体与线索段

- feat(agent): 工具加载并档——suffix 并入 search/group，转发回执与原生组集合三档底座

- feat(dispatch): 派发 run 盲区收口——ASK 档豁免走副屏，操作权等待与排队单同步调度台；
  子会话 ask_user 等待同步——调度台等待横幅/角标 + 系统通知提示

- feat(live): 超级岛「补充」键——岛按钮展开胶囊直进补充输入

- feat(tools-ui/mcp-ui/providers-ui): 配方编辑、MCP server 添加、模型编辑三个整屏页
  路由化，列表页瘦身

- feat(voice): 流光胶囊——流动速度双通道、深色音量增亮与色散仅深色

### Changed

- 许可证由 PolyForm Noncommercial 1.0.0 改为 GPL-3.0（README / README_EN 同步）

- fix(agent): 工具调用审查五档收敛为四档（strict/auto 并入标准，新增完全放行档）；
  判断提示补执行类意图判据，含糊时偏向需要工具；决策闲聊误判不再锁死工具发现
  （渐进加载自救通道）；delegate 回答带自决指引——按最合理默认推进

- refactor(compress): 「压缩会话」立即压缩不进设置页——菜单直连压缩，设置页只留配置

- refactor(voice): 语音入口收敛普通助手身份 · 日程快路径 · 删除上拉交接与独立对话页；
  移除 pickerYield 参数并简化让位逻辑

- refactor(ui): 应用壳/导航/组件批量调整——溢出菜单与路由接线、SubAgent 行共享箭头、
  状态卡语言上下文防泄漏、终端与虚拟屏细节；远程智能体编辑页保存钮改走
  TopBarIconButton；ChatIconAction 补官方 shapes 按压 morph

- docs(rules): 增补顶栏动作、滚动截断淡出、UI 文案命名、宽屏判定与边缘横滑返回规则；
  补 sherpa-onnx/OpenMinis 等致谢与第三方声明，参考与致谢补入 yooma1/Eta666（米家
  直连来源）；README 结构与文案润色；新增发版流程文档，工程纪律与构建测试入口入
  AGENTS/TECHNICAL

### Security

- fix(security): provider 与豆包密钥静态加密，备份链路同步适配；虚拟屏强制重置
  root kill 前先验 owner 身份并过 root 门禁；运行时服务 start 面加能力令牌门禁，
  网络与通知杂项收紧

### Fixed

- fix(voice): 切会话误入听写——唤起令牌重放改进程级消费水位播种/上报，聚焦令牌同修；
  浮窗关闭把在跑的 run 交接给主程序实时续显；归档轨迹先于 outbox 折叠导入，修复
  语音浮窗关闭后中间过程丢失；浮窗投影缓存补档位 key · 圆角令牌化 · 提交回底共享；
  系统选择器让位——附件/权限跳板隐身穿透浮窗，主应用前台唤醒在应用内响应；
  悬浮窗提交回底、「+」菜单地板

- fix(chat): 口述 ✓ 改「停录等尾」收尾；落字同帧交接，尾部槽升级 DICTATION 真 morph

- fix(analysis): 导入保留回合结构——runId 重映射进导出文档并还原

- fix(workspace): 文件写路径加 O_NOFOLLOW symlink 逃逸硬化，桌面端退化兼容

- fix(backup): 备份完整性收口——MCP/网页工具密钥受开关控制，模型选型进包

- fix(live): 岛「补充」键接线三处缺陷

- fix(ui): 配色规范审查修复——预览映射并轨官方工厂、删速度档品牌色死代码、Markdown
  行内样式强制显式取色；UI/UX 评审落地——硬编码文案外化、远程重命名反馈、触摸目标
  收敛、加载失败旁路、暂停态提示；相邻工具卡片圆角——接缝投影泛化与断开件补齐；
  语音设置页默认落「朗读」分节；离线语音包下载进度改用 EtaProgressBar（M3E wavy）；
  米家设置页对齐兄弟页口径；任务画布选择弹窗确认按钮补 M3E shapes 按压 morph

### Performance

- perf(chat): 流式正文增长不再重算八份全时间线投影

- perf(ui): 消除动画与高频路径上的逐帧重组与重复分配

- perf(providers): provider 列表映射段挂 flowOn 上游——Keystore 解密移出主线程重组

- perf(a11y): 强制无障碍状态读 SP 缓存——Settings.Global 的 binder 往返挪出组合期
  主线程

- perf(voice): 唤起即听写预热——配置 IO 预载与解密 memo

## [v5.4.1] - 2026-11-11

首个实际发布的 5.4.x：`v5.4.0` 标签已推送，但那轮发版 CI 被 75 分钟超时取消，
草稿都未建成，因此 `releases/latest` 一直停在 v5.3.20。本版含 v5.4.0 的全部变更，
并在其之上追加 3 个提交；更新检查从 v5.3.20 升到本版。

### Fixes

- fix(chat): 生成期上翻阅读不再被严格贴底拽回——锚定只认真贴底状态（`Anchored`），
  加 `hasLeftBottom` 闩锁避免松手又被贴回，`ChatListFollowState` 补 `userScrolling`
  字段以免 Lagging 段起手那一帧被 `distinctUntilChanged` 吞掉

- fix(chat): 悬浮窗补语音唤醒入口与熄屏收起；删掉按消息数强制重锚，重锚改挂提交动作，
  口述听写按启动令牌延迟开录

- fix(ci): 常驻 shell 用例不再无限期挂死流水线——控制器补输出缓冲截断清空与 stdout
  读线程 EOF 两处物理退出，13 个用例各带 30 秒上限，job 上限从误加的 75 分钟
  降回 45 分钟（正常一轮约 16 分钟）

- fix(ui): 清掉 4 项 lint Error——两处组合期读 `StateFlow.value` 改 StateFlow 重载 +
  `derivedStateOf` 去重，未用作用域的 `BoxWithConstraints` 改 `Box`，简/繁中文补
  `mobile_actions_continue_download` 翻译

### Build

- fix(ci): 虚拟屏契约断言 `maxLines = 1` 计数与源码同步（4→5），其余断言全量复核

- fix(ci): 数据库上古 v5 降级用例清理改走 `Context.deleteDatabase`，种子 helper 补
  `onDowngrade` 重建，消除同 worker 里 WAL 把 v40 写回的偶发失败

- fix(ci): 常驻 shell 在 runner 上偶发失联，加仅对 `TestTimedOutException` 生效的
  3 次重试，`RuleChain` 把临时目录放内层保证每次重建

## [v5.4.0] - 2026-11-11

### Features

- feat(xiaoai): 接入小爱 8.x 系统桥三线与 HyperOS 电源键终态接管

- feat(dispatch): 调度智能体内核——工单派发、债权回报与调度台主页；派发回报落成工具卡与回执卡，支持暂停后积压、恢复再并入，并为调度台添加独立全量会话池

- feat(live): Agent 后台运行时上岛显示进度并支持完成横幅，新增实时活动设置页

- feat(agent): 工具调用审查模式——只读/严格/标准/智能/自动档位取代旧布尔开关，智能档支持 LLM 与决策模型双引擎逐次裁决

- feat(agent): 工具渐进加载三档 + tools_search 关键词搜索 + MCP 按 server 延迟成组

- feat(agent): 判断模型决策内核与运行入口决策路由、不匹配追踪、提示词关段与工具目录收窄；新增判断模型设置页与连通性测试

- feat(agent): 虚拟屏实时视窗与顶部胶囊，动画无缝、触控焦点形状 morph；补焦点见证与 owner 交接闭环，副屏恢复入口按 root 放行并新增三级强制重置

- feat(agent): 语音屏幕快捷功能与日程任务运行级工具直发

- feat(security): 为危险操作增加分级风险判定与确认闸门

- feat(voice): 语音入口全面转口述听写——流光胶囊接实时音量与启动预热、说完停顿自动发送、AGSL 微色散；系统语音助手弹窗接入新版听写，助手弹窗落座自动开录

- feat(voice): 本机识别模型常驻并支持先录音后加载；离线语音包断点续传，断网或取消后从断点继续下载

- feat(media): 媒体语义搜索结果新增宫格视图并落盘视图偏好；「添加到会话」+ composer「去搜索」入口；视频命中行预览图改用命中时刻帧

- feat(ui): 全屏图片预览新增照片浏览器式缩略图条

- feat(tts): 新增后台继续朗读偏好与开关

- feat(folders): 管理文件夹升级为管理分类，并聚合远程设备入口

- feat(memory): 支持按标题读写章节、合并多段核心记忆、分页续读提示与写入 changed 确认机制

- feat(diagnostics): 导出/备份补齐完整性，并收紧凭据与敏感工具明细

- feat(appearance): 主题模式改为四态按钮组，用量热力图改用表达式形状阶梯

- feat(overlay): 悬浮胶囊改用官方漂浮容器阴影，支持甩出越界并按物理回弹到边缘

### Fixes

- fix(agent): 会话恢复后第 1 轮目录重新并入调用方给出的已打开延迟组，修复恢复会话首轮工具调用被拒

- fix(ui): 长按选字期间冻结严格跟底与上提，选中不会再被自动滚动打断；松手后按实测差额一次追平

- fix(media): 高分屏截图 PNG 超附件字节闸门时退回位图解码路径，不再把截图工具炸成异常

- fix(db): 修复 v39 结构漂移导致的冷启动闪退，并收紧迁移失败策略

- fix(voice): 浮窗拆窗与熄屏必收声，实时通话纳入进程级麦克风锁；开麦 native 阻塞与离线引擎装载不再拖住互斥锁/取消

- fix(agent): 只有「先跑后断」才标注重连；归一化工具调用参数中的字符串化 JSON；决策请求改用 provider 的 API 模型名

- fix(dispatch): 补上忙时队列的终态排空，早失败路径不再悬挂债权

- fix(vd): 副屏恢复屏障终结终态残留 owner 后重建；可见性屏障子任务 id 0 不再视为非法；owner 交接按 presentIds 精确判定

- fix(overlay): 修复胶囊窗口逐帧缩水与展开窄于按钮组闪退，甩出期间冻结窗口尺寸收拢

- fix(composer): 修复弹簧动画负值导致的崩溃；口述自动发送门禁现算，空框口述不再误退回编辑

- fix(ui): 对话流严格贴底，跳转动画改总时长封顶并采用官方缓动曲线；侧栏松手收敛改恒定减速度梯形

- fix: 修复纯黑模式在非深色主题下仍生效的问题

- fix(remote): 发送后清空远程会话输入草稿，交付失败回填原文

- fix(backup): 记忆导入失败时补偿恢复旧快照

- fix(i18n): 补齐繁体缺失文案与续传百分比占位符；屏幕预设提示词改为指向随消息附带的截图

### Performance

- perf(media): 检索流式分片首屏即渲染 + 视频时刻异步精排 + 引擎预热

### Refactor

- refactor(nav): 导航容器从 NavHost 换到 miuix NavDisplay，移除 androidx.navigation 依赖并为 miuix 补 keep 规则

- refactor(ui): 颜色/透明度令牌单源化、弹窗面板按钮收敛项目唯一实现、状态色收敛 EtaStatus 单源、加载指示收口 EtaLoading

- refactor(ui): 弹层/浮窗/页面过渡统一到官方 MotionScheme

- refactor(overlay): 胶囊形变与物理并入单一帧循环，展开动画布局轴并到单一主轴消除收尾抽搐

### Build

- chore(build): 升级 Kotlin Gradle 插件至 2.4.20；收敛 Compose 与 ui-test 版本到版本目录，避免传递依赖静默抬版

- fix(ci): 提高单测 worker 堆，消除 Robolectric 与进程类用例共用 512MB 堆的停滞

## [v5.3.20] - 2026-11-02

### Features

- feat(remote): 远程智能体支持连接局域网服务器，含管理与连接诊断；升为侧栏胶囊，断线提示并入输入框状态胶囊

- feat(remote): 远程协议目录化——注册表单点接入、可选方法给语义默认、协议名进服务器配置行；接入 OpenCode v2 作为第二远程协议

- feat(remote): 服务器编辑独立成页（表单 + 协议 + 连接诊断，入栈转场）

- feat(remote): 自动重连到上限判死，胶囊重试钮与点会话行触发复活

- feat(agent): 对话生成中可切换模型/助手，仅下一轮生效；回复自动朗读的会话与运行身份判定

- feat(media-search): 检索服务加索引代数与三级缓存，匹配度阈值下推为检索时参数；切分类走本地筛选不再重搜

- feat(ui): 滚动截断统一用 DstIn 蒙版淡出并修复主对话流黑带；使用统计热力图补边缘淡出

### Fixes

- fix(agent): 日程门禁从「设备控制」迁到「个人数据」

- fix(ui): 多选勾选行走官方 checked 变体，恢复选中形状 morph 动画

- fix(ui): 提问卡并入工作组分段列表，相接圆角随相邻行取内圆

- fix(ui): 重连分割线断开组内分段列表，断点两侧工具卡取段末/段首大圆角

- fix(agent): 修复 CI 编译报错——补 GuiControlChanged 分支、clearText displayId 与准入会话声明

### Performance

- perf(media): 模型文件校验加指纹缓存，消除每次 embed 与状态查询的全量 SHA-256

### Refactor

- refactor(ui): 间距节奏全库收敛与结构统一，列表行字阶与配色交给官方槽位默认

- refactor(tools): 工具能力收拢为「一张卡一个开关」，分组开关移出设置页

- refactor(ui): 用户气泡图片条改普通横滚加两端淡出，退役非收纳轮播

### Build

- build(ci): 单测 worker 堆提到 2GB、Gradle daemon 提到 3GB，解决 Robolectric 与 PTY 用例共用 512MB 堆导致的无限期停滞

## [v5.3.19] - 2026-10-31

### Features

- feat(ui): 主页输入条接入排队消息移除

- feat(ui): 分段行支持长按无障碍标签（会话/多选入口）；工具组加载提示改用专有文案

- feat(ui): 排队消息可直接移除：输入条队列项带关闭按钮

- feat(agent): 会话展示分页数据层：按 sort_index 窗口加载与边界保存，未加载历史不被覆写

- feat(agent): 标题支持每轮刷新与手改保护；history 工具失败文案改走资源本地化

- feat(agent): 运行准入与 GUI 操作权互斥；launch_app/open_uri 智能画布分流，新增 present_to_user 主屏移交

- feat(agent): 后台虚拟屏基建：三条创建通道、画布路由策略与 GUI 工具按屏操作

- feat(ui): 工具分组改可展开分段卡（M3 expand & collapse）

- feat(ui): 媒体搜索批量操作改官方浮动工具栏，阈值移入底部弹层


### Fixes

- fix(terminal): 控制台输出改流式 UTF-8 解码，PTY 读块劈开多字节字符不再乱码

- fix(ui): Systemizer 安装失败文案改走资源本地化

- fix(ui): Linux 环境页状态读盘移出组合期，校正前按检查中呈现；发行版下拉补定位摘要

- fix(i18n): 繁体资源文件用语校对（权限/侦测/处理程序等）

- fix(ui): 设置页说明收进行内摘要、语音面板与图框同心圆角，三语零散文案校对

- fix(ui): Linux 环境安装改 App 级作用域，离开页面不再中断安装

- fix(ui): 浏览器工具栏滚动桥差量放 consumed 位，修复顶栏不跟随滚动

- fix(ui): 回答折叠分轮处理：旧轮生成中保持收起，最新轮停下才收

- fix(terminal): 会话创建与回收移出会话锁，store 状态表加锁，消除 ANR


### Performance

- perf(ui): 终端块只渲染尾部窗口，排版成本与输出总长解耦；补终端长按无障碍标签

- perf(ui): AnsiText 行覆盖不再全串回扫，修进度条输出 O(n²) 卡顿

- perf(terminal): 终端输出改增量读取，目录列表一次 stat 批量出


### Refactor

- refactor(ui): 媒体索引设置页说明文案收进行内摘要

- refactor(ui): 问答卡迁到官方 SegmentedListItem，拆除旧工作卡外壳


### Build

- build: 更新 .gitignore 忽略临时文件和工作目录



## [v5.3.18] - 2026-10-31

### Features

- feat(agent): 被关闭的工具组声明为「不可用」，阻断误重试

- feat(ui): 跳转按钮组整组 3dp 投影，轮廓合成一条路径随形变同步

- feat(ui): 摘要行 supporting 显隐用连续行高 morph 无缝伸缩

- feat(ui): 朗读按钮传递 checked=active（官方 ToggleButton 选中态）

- feat(ui): ConnectedGroup 选中态配色 + 分段列表配色覆盖 + contentColorFor 配对

- feat(ui): 摘要行展开态 tertiaryContainer + 图标颜色跟随 + 编辑态 checked

- feat(ui): 折叠组展开/收起 = 行内 clip+淡变揭示

- feat(ui): 分段气泡核心升级——行高 morph、支持片淡入、clip 揭示、退场键出处

- feat: 添加通用展开正文文本样式并统一复用

- feat(ui): 摘要行首步为纯文本回复时保持单独一条

- feat(ui): 时间线投影在组内纯文本回复处断开连续分段列表

- feat(agent): 浏览器面板接入页面滚动监听与无障碍服务探测

- feat(ui): 思考与工具步骤接入分段气泡，恢复激进折叠的聊天流接线

- feat(ui): 时间线投影层支持步骤扁平行与激进折叠摘要行

- feat(ui): 新增设置页同款的分段列表可展开气泡组件

- feat(ui): 分组开关关闭时禁用该组工具的独立开关

- feat(ui): 新增官方 M3 Expressive 标准按钮组的消息操作条组件

- feat(关于页): 添加作者主页入口

- feat(search): 统一各搜索页的搜索记录，抽屉搜索改为跳全文搜索页

- feat(runtime): 在聊天回复和 GUI 执行期间保持屏幕常亮


### Fixes

- fix(agent): 日历写入应用身份优先、Root 兜底，可选字段降级补齐

- fix(agent): 日历组补齐读工具，写入失败不再指向组外工具

- fix(ui): About 页改用 about_app_name 并适配 fork 文案

- fix(terminal): 一次性 Shell 启动失败带回阶段摘要

- fix(tools): 日程与设备状态组补齐开关映射

- fix(ui): 分段行选中态按选中底色配对全部内容色

- fix(ui): 侧栏按系统返回键改为缓动收拢

- fix(ui): 顶栏/底栏渐变板两段式绘制，空态 IME 重组收敛

- fix(ui): 恢复普通档「回答后折叠」——对照旧版回归

- fix(ui): 相连按钮组图标胶囊与纯文字胶囊等高

- fix(ui): 侧栏可见时不再禁用语音对话入口与输入框焦点

- fix(ui): 空态形状键盘收放改由 IME inset 曲线驱动，矮窗口整组隐藏

- fix(ui): 编译验证修复

- fix(ui): 侧栏首排胶囊行恢复 40dp 行高，与首页顶栏中线对齐

- fix(test): 让摘取过来的三处测试对上本地 API

- fix(settings): 补齐系统接管页的分组锚点入参

- fix: 补齐摘取提交依赖的两处本地缺口

- fix(agent): stop highly repetitive visible answers

- fix(agent): use current configuration for new child dispatch

- fix(chat): reuse a stopped reconnect and cancel deleted runs

- fix(browser): contain renderer exits without crashing the app

- fix(terminal): surface startup diagnostics across callers

- fix(agent): scope recovery restrictions to the owning run and allow independent dispatch

- fix(runtime): keep result IO off main and isolate concurrent request queues

- fix(chat): show continue when compaction is paused

- fix(context): resolve revision archive from compaction history

- fix(context): preserve recoverable history across chained compaction

- fix(terminal): 修复 Debian 安装中断后的恢复与报错

- fix(model): 修复 Chat 工具调用 ID 冲突与历史配对

- fix(anthropic): 发送 input_schema 时移除顶层 oneOf/anyOf/allOf

- fix(terminal): 修复子进程提前退出时的输入管道异常

- fix(markdown): 修复公式预处理遇到围栏空行时的死循环


### Performance

- perf(chat): park running pulse while chat is settled-covered

- perf(markdown): give streamed document blocks their own display lists

- perf(chat): reuse terminal order for reconnect marker updates


### Refactor

- refactor(ui): SubAgent 设置页改用统一分段行组件

- refactor(ui): 设置家族列表行首图标收敛官方默认几何

- refactor(ui): 聊天输入区的模型/子代理选择器精简

- refactor(ui): 会话侧栏底部浮动工具栏改为 FAB 组

- refactor(ui): 连体按钮组推广到全部操作条，MessageActionButtonGroup 退役

- refactor(ui): 抽屉日期分组标题改为「昨天 · 4」内联计数并与设置页分类标题同款

- refactor(ui): 侧边栏文件夹胶囊取消长按弹出菜单

- refactor(ui): 思考与工具调用气泡接入官方 SegmentedListItem 可展开组件

- refactor(ui): 用户消息操作行接入官方标准按钮组

- refactor(ui): 助手回复操作条与朗读动作接入官方标准按钮组


### Build

- build(deps): 锁定 org.jetbrains:markdown 0.7.16


### Docs

- docs: 删除 AGENTS.md 并添加到 .gitignore

- docs: 补充 AGENTS.md 工作约定


### Other

- test(agent): 对齐工具结果入历史限长与预算实测

- style(agent): 去掉模型与数据层用户可见文案末尾的句号

- style(ui): 去掉界面硬编码文案末尾的句号

- style(res): 去掉三语字符串资源末尾的句号

- test(ui): 折叠合并组/文本断开/连续列表投影契约

- test(ui): 组内文本回复断开连续列表的投影契约断言

- style(ui): 聊天工作行图标收敛官方默认几何并统一常态着色

- style(ui): 分段气泡全面对齐官方默认值——审计收尾

- test(ui): 分段气泡与激进折叠的契约断言与设置文案同步

- style(ui): 页面级提示语统一为 EtaText Secondary 档（bodyMedium + onSurfaceVariant）

- style(ui): 消息操作条按钮内容内边距 8dp 收窄到 4dp

- style(ui): 主页顶栏按钮改用侧栏顶部胶囊同款配色 token

- 设置搜索改为与主页共用同一份目录，消除搜不到的死角



## [v5.3.17] - 2026-10-29

### Features

- feat(agent): 个人数据组按数据来源拆开，配方工具提到扩展组首位

- feat(conversation): 导出里统计延迟工具组的真实使用频次

- feat(conversation): 会话分析导出与导入

- feat: add 'Download in background' option to update dialog

- feat: add AppUpdateService for background download

- feat(strings): add update background download resources


### Fixes

- fix(ui): 修复侧边栏长按菜单图标在深色模式下不可见

- fix(agent): 补齐敏感清单里漏登记的个人数据入口

- fix(ui): Token 用量页区分当前上下文与累计请求

- fix(agent): 延迟工具组跨 run 恢复并补齐日程读回校验

- fix(agent): 工具缺席时给出可执行的补救方式

- fix(agent): 提示只点名本轮目录里真实存在的工具

- fix(agent): 配方表每轮重读，新建的配方下一轮即可调用


### Performance

- perf(agent): 提示按组付费，并删除 Eta 私有日程/闹钟工具

- perf(agent): 工具改为 7 组统一分层加载，常驻前缀从 19k 降到 6.3k token


### Refactor

- refactor(ui): 工具清单行内展示完整说明，删掉重复的「查看说明」弹窗

- refactor: add progress callback to AppUpdateRepository.downloadApk


### Build

- build: 发版 5.3.17（versionCode 2026102901）


### Other

- test(agent): 延迟工具组中途打开后，组内工具下一轮可调用

- chore: ignore local install.sh script



## [v5.3.16] - 2026-10-28

### Features

- feat(ui): 工具配方页补充步骤、参数与工具信息分区

- feat(agent): 新增日程与闹钟管理工具

- feat: 为技能开关行添加切换回调处理

- feat(backup-ui): 备份页改为类别勾选 + 一对导入导出按钮

- feat(backup): 备份改为分类勾选，补齐配方与网页工具配置，密钥默认脱敏

- feat(prompt): 关闭工具开关时补一条关闭说明，区分「用户关闭」与「入口收窄」

- feat(tools): 危险操作确认改由模型用 ask_user 提问，配套一次性授权闸门

- feat(tools): 危险操作逐次确认

- feat(subagent): 子代理委派加独立开关

- feat(assistants): 按助手的工具开关，与记忆/技能同级且只能收紧

- feat(tools): 敏感读拆成个人信息与设备状态两档

- feat(ui): 工具页分组加内联开关，就地收紧能力域

- feat(tools): 文件与命令拆成三档权限，命令执行可单独关闭

- feat(tools): 低频管理工具折叠成按需打开的域，每轮少约 2.9k token

- feat(voice): 点按识别文字接管为键盘输入

- feat(chat): 超长用户消息气泡折叠，避免长文撑满聊天区

- feat(ui): 图片预览直接分享与保存原始文件

- feat(agent): 接入日历免 Root 权限模型与上游工具族

- feat(agent): 补充新工具的调用 schema

- feat(agent): 移植上游工具实现层

- feat(ui): 工具卡片展开后展示完整调用细节与图片

- feat(ui): 服务商账号登录面板与 Codex/Grok 登录入口

- feat(runtime): 账号登录配置改由本进程解密并按连接校验

- feat(model): 订阅凭据注入与 Codex/Grok 请求协议适配

- feat(provider): 接入加密凭据门面与远端模型目录同步

- feat(auth): 以加密凭据与登录会话替换明文 OAuth 存储

- feat(tools): 配方管理页——审计、启停、编辑、分享导出与备份

- feat(agent): 新增网页搜索与正文读取工具（web_search / fetch_url）

- feat(ui): 平板横屏侧栏改为整块平移并排，并补回右滑手势

- feat(ui): 新增浮动工具栏端盖同心内边距助手，应用至浏览器与抽屉工具栏


### Fixes

- fix(release): 修复发版门禁——补默认文案、webTools 快照与子代理 UI 测试适配

- fix(security): wifi_credentials 默认只返回网络名，密码改为显式请求

- fix(ui): 统一子代理组自动委派行的卡片底板

- fix(db): 修复日程与闹钟迁移的版本冲突与索引校验

- fix(agent): 修复日程闹钟工具提交遗留的编译错误

- fix: 修复 AssistantsScreen 中点击事件的视图引用

- fix(i18n): 补全 zh-Hans/zh-Hant 缺失的 13 条工具设置串

- fix(voice): 屏幕截图按原生分辨率无损编码，聊天留档预览提到 1600px

- fix(ui): 图片解码协程销毁后不再写 state，消除 Markdown 布局测试的偶发崩溃

- fix(tools): 域可用性统一判定，开关关闭的域不再被宣传或打开

- fix(settings): 工具开关只能由用户放宽，模型不得重新打开

- fix(ui): 子代理组「本会话已应用」标记被优先级吞掉，折叠行测试改读未合并树

- fix(retry): 重连区分永久与暂时性错误，退避支持 jitter 与 Retry-After

- fix(subagent): 补上长按「助手与模型」的路由与回归测试

- fix(title): 标题生成挪到首轮回复后，结合问题与助手回答

- fix(title): 重写标题生成提示词，写死素材/指令边界并去除标题前缀

- fix(voice): 入口浮窗先取真实外观再建窗，避免深色用户首帧闪白；预设行加两端淡出

- fix(voice): 朗读范围与激进折叠的界面展示对齐

- fix(voice): 转写接管输入时跳过键盘未弹出态的胶囊收缩

- fix(voice): 助理入口忽略重复 SHOW 避免入场动画重播

- fix: 清理 16 个 lint error 与 24 个测试失败，main 恢复全绿

- fix(provider): 修复 OkHttp 5 迁移遗留，让 main 重新可编译

- fix(ui): 抽屉首排搜索框与管理会话按钮高度对齐胶囊行

- fix(ui): 补 EtaTextButton 的 ui.components 转发，修复 main 无法编译

- fix(agent): 闹钟工具接入系统时钟 ContentProvider，修复 MIUI 假成功与 schema 不兼容

- fix: 修复暂停后会话列表运行态显示异常

- fix(ui): 图片预览与终端键栏浮动工具栏改用端盖同心内边距

- fix(chat): User 气泡组尾尾巴纳入节点宽度使涟漪完整覆盖

- fix(chat): 图片分享统一落 chat-files 缓存避免 FileProvider 闪退

- fix(ui): 壳层与抽屉/终端底部手势条适配

- fix(ui): 设置类页面底部手势条适配

- fix(ui): 浏览器面板/页底部手势条适配


### Performance

- perf(agent): 按能力裁剪工具与提示词，补齐 GUI 总开关

- perf(build): 融合上游安装包瘦身——仅打包 ARM64 并压缩 DEX

- perf(tools): 折叠设备写操作与冷门查询，压缩 browser_use/terminal 文案

- perf(prompt): 能力快照不变时复用上一轮的工具目录与系统消息

- perf(loop): 幂等只读工具批次并行执行，事件与历史仍按模型顺序

- perf(runtime): 请求解码改走有界线程池，饱和时回明确错误

- perf(tools): 模型侧工具描述改为英文，每轮固定前缀少约 6.5k token

- perf(loop): 历史字符数与词元数改为增量维护，媒体裁剪不再整段深拷贝


### Refactor

- refactor: 统一子代理设置弹窗与预设组详情页的行卡片形态

- refactor: 将底部选择面板替换为内联下拉选择器

- refactor(subagent): 子代理枚举选择统一改为底部单选面板

- refactor(tools): 观察配对与无障碍前置说明收拢到 AgentToolSchema

- refactor(loop): 抽出 RoundContext 快照，纯机械拆分请求准备段

- refactor(subagent): 子代理配置从会话内协作弹窗迁移到设置页预设组

- refactor(assistant): 屏幕预设与「添加当前屏幕」合并为一行 chip

- refactor(ui): 语音入口按钮改用麦克风图标

- refactor(ui): 离线语音模型选择改用 EtaPickerSheet

- refactor(ui): 语音聆听胶囊统一使用 surfaceContainer 底色

- refactor(ui): 精简设置页条目并重命名震动反馈

- refactor(agent): 远端模型拉取统一使用供应商凭据

- refactor(provider): 统一订阅认证方式常量并新增 Codex/Grok 连接档案

- refactor(ui): 移除组尾消息气泡尾巴的绘制逻辑


### Build

- build: 发版 5.3.16（versionCode 2026102801）


### Docs

- docs: 更新 README 及替换界面截图，调整预览内容

- docs(runtime): 危险操作确认走 ask_user 的一次性授权机制

- docs(runtime): 记录工具权限分层、逐次确认与按助手开关

- docs(runtime): 记录工具域分层、只读并行、重连分流与有界请求池

- docs: 同步子代理预设组配置说明到设置页模型


### Other

- ui(assistants,folders): 新建入口从顶栏图标钮换成 MCP 页同款 FAB

- ui(recipes): 删除与页题重复的说明标题行

- ui(tools): 分组开关行改用标准分段开关行，组标题只渲染一次

- ui(skills): ZIP 导入入口从安装卡片换成 MCP 页同款 FAB

- ui(recipes): 导入配方入口从分段卡片换成 MCP 页同款 FAB

- ui(memory): 记忆概览行恢复箭头并补尾间距

- ui(media): 多选入口从顶栏图标钮迁到「找到 X 项」同行文字钮

- style(ui): 补齐菜单与分段行的收尾一致性

- style(ui): 菜单气泡按类型分档，多段菜单改用分割线

- style(tools): 配方页行布局改回官方分段行

- style(chat): 用户气泡展开收起补上高度动画

- style(chat): 会话底部跟随改为缓入缓出，发送气泡轻上浮入场

- style(ui): 删除动作统一取 error 色，技能行恢复三点溢出菜单

- style: 调整会话侧边栏顶部内边距以对齐首页顶栏中线

- Merge remote-tracking branch 'origin/main'

- test(provider): 补充订阅登录、请求协议与远端模型同步测试

- chore(auth): 引入 nimbus-jose-jwt 用于订阅登录 ID Token 校验

- Merge branch 'feat/self-evolve-tool-recipes'

- test(tools): 补齐网页搜索与正文读取卡片的入口动作断言



## [v5.3.15] - 2026-10-23

### Features

- feat(chat): 图片预览改浮动工具栏，支持分享与添加到对话

- feat(ui): 抽屉改浮动工具栏并新增文件夹管理页

- feat(agent): 补齐日程/闹钟/系统控制/Root 接管与模型自进化工具链

- feat(assistant): 唤醒落点可配置，补齐屏幕预设与全局动效规格

- feat(memory): 新增全局记忆总开关与一站式总览

- feat(voice): 离线识别默认改用 SenseVoice 并接入 Silero VAD 断句


### Fixes

- fix(ui): 行内下拉行禁用即收起，设置行断言跟上重构后布局

- fix(agent): 闹钟工具改为系统标准实现优先，去掉 ColorOS 硬编码

- fix(agent): 修复 CI 门禁暴露的单测与 lint 回归

- fix(ui): 聆听条麦克风圆钮仅在报错态才显示红底白图标

- fix(agent): 修复配方与闹钟工具层从未编译过的类型错误

- fix(会话): 激进档整轮折叠补齐，已作答提问卡并入同一张卡片

- fix(chat): 强化回复中图片与文件链接的提示词，并让链接可辨识

- fix: 隔离端侧动作模型与对话模型上下文

- fix: 媒体索引结束后前台通知不消失

- fix: 修复语音态胶囊衔接时的半透明发虚问题


### Performance

- perf(chat): 流式文字改为到达即显现，去掉打字机排队等待


### Refactor

- refactor(subagent): 草稿模型选择改为底部选择面板

- refactor(subagent): 预设组详情页子代理设置行改为行内下拉菜单

- refactor(ui): 硬编码图标尺寸统一替换为 EtaDimens 与官方令牌

- refactor(settings): 系统助手接管页选择行改为行内下拉菜单

- refactor(subagent): 草稿表单思考与默认分辨率改为行内下拉菜单

- refactor(subagent): 草稿表单职责与任务分工改为行内下拉菜单

- refactor(ui): 顶栏会话名改用官方 SplitButton 并启用胶囊形变


### Docs

- docs: 在 README 中添加 KernelSU 参考项目并移除旧社区链接


### Other

- test(subagent): 任务分工下拉弹层选项不再执行 performScrollTo



## [v5.3.14] - 2026-10-22

### Features

- feat(backup): 模型备份自动发现端侧模型目录

- feat(nav): 新增豆包/Mimo 音色、子代理详情、共享文件夹选择器路由

- feat(会话): 回答后收起升级为三档分级，激进档整轮只留一张卡片

- feat(chat): 回答出现后自动收起本轮思考与工具步骤

- feat(agent): 端侧设备动作模型接入工具与设置页

- feat(chat): AI 回复支持展示图片，覆盖工作区与在线来源

- feat(media): 检索页支持批量移动/复制

- feat(media): 检索工具支持阈值偏好与批量移动/复制

- feat(media): 新增批量移动/复制文件操作

- feat(ui): 宽屏侧栏固定宽度并排，二级页内容列居中限宽

- feat(ui): 抽出宽屏内容列限宽工具


### Fixes

- fix(browser): 设置子页改页内转场，补齐与 NavHost 一致的入栈/出栈

- fix(media): 目录名集合读取改走 getOrNull，查询为 null 时同样回退空集合

- fix(ui): 图片组改用横向轮播，超出下限才横向滚动

- fix: 修掉 media 批量操作与用户气泡的主源码编译错误

- fix(security): OAuth 登录页禁用混合内容

- fix(security): 网络安全配置默认禁明文，仅放行本地回环

- fix(ui): 修正文件 chip 圆角逻辑并统一图片网格尺寸


### Performance

- perf(chat): 跟底上提逐帧限速，消除生成换行时的瞬移


### Refactor

- refactor(chat): 输入区工具栏改两级结构，跟底上提换缓动曲线

- refactor(subagent): 子代理设置改预设详情页，拆分配置弹窗与共享 fixture

- refactor(voice): 语音设置改个人音色子页结构，统一表单样式与触感

- refactor(i18n): 硬编码 UI 文案统一改为字符串资源


### Build

- build: 发版 5.3.14（versionCode 2026102201）

- build: release 签名校验改用纯值，兼容配置缓存


### Other

- test: 适配 i18n 重构后的断言口径

- chore(ui): 共享文件夹选择器改路由打开，抽屉胶囊行加滚动淡出

- test(chat): 覆盖逐字淡入的并行重叠



## [v5.3.13] - 2026-10-21

### Features

- feat(media): 媒体语义索引自动更新与检索页批量操作

- feat(agent): add MCP self-config and local skill install tools


### Fixes

- fix(ui): 气泡描边色提到组合期取，修掉 @Composable 调用编译错误

- fix(browser): 标签栏权重夹进正数，修掉点「打开浏览器」闪退

- fix(ui): 回收站主列表补水平内边距，与设置页保持一致

- fix(ci): 修复 verify 作业 8 个单测失败与 4 处 lint 错误


### Performance

- perf(agent): 缩短发送到模型首包的本地等待


### Build

- build: 发版 5.3.13（versionCode 2026102102）


### Docs

- docs: rename project display name to EtaExpressive in READMEs

- docs(readme): remove QQ group and Telegram references

- docs(readme): add acknowledgements, fork changelog, and update links

- docs(release): 发版文档跟上当前工作流


### Other

- test(ci): 诊断日志用例改用自有开关流，避开 DataStore 投递挂死

- test: 对齐能力快照复用与无底板「+」按钮的新行为

- test(ci): 放宽子代理诊断日志用例的设置投递等待窗口

- test(ci): 消除 EtaModelBackupTest 恢复用例的时序敏感断言



## [v5.3.12] - 2026-10-21

### Features

- feat(app): 接入回收站、语义检索与搜索的应用层接线

- feat(ui): 新增 EtaPickerSheet 统一选择面板

- feat(i18n): 补齐回收站、语义检索与搜索相关多语言文案

- feat(ui): 模型与助手选择器可直接进入配置

- feat(chats): 会话列表支持全部/未分类筛选，搜索命中标题与正文

- feat(backup): 备份支持模型文件与媒体索引

- feat(media): 本地媒体语义索引与检索

- feat(trash): 回收站留存天数接入模型可改设置

- feat(trash): 会话删除改为进回收站

- feat(i18n): 运行时通知、@会话占位与速度标签文案改为字符串资源

- feat(i18n): 语音、TTS 与提供商表单文案改为字符串资源

- feat(i18n): 工具轨迹摘要文案改为字符串资源

- feat(i18n): 新增 localizedText 取值辅助，无 Context 时回退中文基准

- feat(chat): 流式文字逐字渐变淡入并轻微上浮

- feat(chat): 跟底追赶分帧限速，回底与初始定位改平滑收尾

- feat(voice): 接入 sherpa-onnx 离线语音运行时，支持模型与下载源选择

- feat(tools): 新增模型自调用工具 eta_manage_subagents 与 eta_manage_history

- feat(ui): 浏览器标签栏改官方相连按钮组 Morph 胶囊

- feat(ui): EtaDialog 新增 actions 参数，操作行迁至官方 confirmButton 槽

- feat(ui): 自定义请求头编辑器补示例提示文案


### Fixes

- fix(compress): 手动压缩接口模式改读统一压缩接口配置

- fix(ui): 轮次跳转长按只保留一次触感

- fix(tools): 自身配置工具密钥字段说明明确为 secret

- fix(ui): 补齐 skills_* 工具图标

- fix(i18n): 日期与音色时长文案改用组合期 locale 与定点小数

- fix(ui): 折叠 FAB 补无障碍标签，导航 RestrictedApi 登记抑制理由

- fix(oauth): 登录页返回改走 OnBackPressedDispatcher

- fix(input): 输入区长按补触感反馈

- fix(ui): 流式块冻结补帧不再用 remember 做副作用

- fix(ui): 用户消息图片与文件附件圆角接入气泡接缝


### Performance

- perf(chat): 流式文字淡入时长固定并按帧合层


### Refactor

- refactor(settings): 系统接管页分组不再整组隐藏

- refactor(voice): 语音、TTS 与上下文压缩改官方组件

- refactor(ui): 设置与终端等页面改官方组件

- refactor(browser): 浏览器面板改官方组件并转为独立全屏页

- refactor(ui): 子代理档案与协作面板改官方组件

- refactor(ui): 下拉菜单统一到官方 M3 Expressive 渲染

- refactor(ui): 基础组件补充底栏、全屏表面与装饰形状

- refactor(settings): 设置主页重组为工具能力/运行环境/系统接管分组

- refactor(ui): 顶栏与终端弹窗改为路由页面并统一接线

- refactor(ui): 子代理协作面板与草稿弹窗改分段列表

- refactor(ui): 助手列表点行即选中，编辑收进行尾菜单

- refactor(nav): 移除横移返回手势开关，补齐预测性返回转场

- refactor(ui): 子代理组操作改就地下拉菜单，移除底部操作面板


### Build

- build: 发版 5.3.12（versionCode 2026102101）

- build: 发版 5.3.11（versionCode 2026101901）

- build: 关闭两条与发版方案/主 Activity 生命周期不符的 lint 检查


### Other

- test(ui): 子代理分工行断言跟上官方 SelectableDropdownMenuItem

- test: 补齐媒体开关与数据库 v35 的断言

- test: 契约测试跟上回收站与数据库版本变化

- chore(repo): 忽略本地临时截图目录

- style(ui): 终端运行后端行把当前值摆到行尾

- style(ui): 助手编辑页输入字段改官方 OutlinedTextField

- style(ui): 提供商连接配置表单不再垫分段容器底板

- style(ui): 空状态建议色块排成规整 2×2 网格并按真实面积归一等大

- test(app): 启动水合异步化后手动驱动会话状态

- test(tools): 同步工具目录与图标断言

- test(config): 覆盖新增偏好默认值

- test(ui): 触感用例给 RecordingView 挂父容器以适配 Compose 1.13 ripple

- test(ui): AnimatedVisibility 1.13 官方语义下暂挂两则逐帧断言

- test(ui): 更新 Compose 1.13 语义基线与穿梭焦点断言

- test(conversation): 同步消息卡片与工作步骤重构后的断言

- test(subagent): 同步子代理 M3E 重构后的语义与布局断言

- style(browser): 对齐 JSON 解析兜底分支缩进



## [v5.3.10] - 2026-10-18

### Features

- feat(tools): 新增「让模型读写 Eta 自身配置」工具组

- feat(tts): 回复自动朗读（含流式合成）与顶栏语音设置快捷入口

- feat(search): 设置页接入搜索，独立搜索页支持查询历史

- feat(terminal): M3E redesign of block terminal and console UI

- feat(tts): 朗读按钮加载态改用 M3E LoadingIndicator

- feat(assistants): 助手三点菜单项增加前置图标

- feat(ui): mark picker selection with a trailing check\n\n- Add ModelPickerCheckRow next to ModelPickerRadioRow: segmented row without\n  the leading radio button, trailing check shown on the selected row only.\n- AssistantRow: replace the "Current" label with the same trailing check.

- feat(search): 助手与供应商搜索改为独立搜索页

- feat(appearance): 界面缩放滑杆在 100% 处显示刻度标记

- feat(haptics): add group section headers to haptics settings

- feat(launcher): 启用 F6F7F9 启动图标

- feat: 为 EtaScaffold 添加自定义标题内容插槽

- feat(brand): 启动图标更换为上游 Mangi-11/Eta 的四色图标

- feat(ui): 会话轮次跳转改为一对连体按钮，滚动时亮出

- feat(ui): 空状态建议色块改用 Clover4Leaf / Ghostish / Bun / Pill 并轻微旋转

- feat(ui): 多行输入时左侧动作组贴底，与发送按钮对齐

- feat(ui): 输入区支持多图选择，键盘收起时保留左侧动作组

- feat(ui): 设置与列表页迁移 M3E 分段行（模型选择器平铺、管理页删除确认、强调档按钮）

- feat(voice): 语音入口浮层输入区接入共享 M3 组件，新增系统选择器桥桩页

- feat(ui): 空态建议词改按家族与设备能力抽取

- feat(ui): 新增 EtaType 统一字阶，会话区改走语义档位

- feat(theme): M3 Expressive 配色接入外观设置取色链，并统一提供触感反馈

- feat(ui): 聆听条与正常输入区之间补上弹簧过渡动画

- feat(ui): 语音进行时输入区切换为聆听条

- feat(runtime): 融合上游的 Agent 运行状态 Live Update（状态栏胶囊与锁屏）

- feat(agent): 融合上游 GUI 工具改进（坐标归一化 / 小幅滚动 / 节点状态 / 动作后界面）

- feat(ui): rebuild the M3 chat composer as a single-row floating toolbar

- feat(ui): scrim gradients for the chat shell and pin the composer controls

- feat(ui): rebuild the work-process cards on the question-card pattern

- feat(i18n): complete en/zh-Hans/zh-Hant resources and declare locale config

- feat(theme): add Material 3 Expressive theme family


### Fixes

- fix(appearance): 纯黑背景黑化范围扩展到页面背景角色

- fix(appearance): 纯黑背景开关在浅色主题下隐藏

- fix(navigation): R8 混淆下导航路由解析与返回判定失效

- fix(search): 搜索历史列表对齐设置主页分组规格

- fix(ui): 补齐 ToolPresentation 引用的 tool_eta_* 工具名字符串

- fix(haptics): use EtaSegmentedColumn title for group spacing parity

- fix(brand): 补提交旧前景 PNG 的删除

- fix(ui): 修复侧栏抽屉编译错误（密度读取、反射 setter 空值、泛型推断）

- fix(ui): 聆听条过渡改用横向展开/收起，与输入内容时的动画同款

- fix(ui): 聆听条停止圆钮改用 tertiaryContainer

- fix(anthropic): 拒答或内容过滤时丢弃半截回复并结束运行

- fix(stats): keep heatmap captions inside their own grid cell


### Performance

- perf(browser): 地址栏加载与呼吸动画改到图层内驱动

- perf(startup): 会话水合移出主线程，首页编辑态记忆化派生历史


### Refactor

- refactor(browser): 浏览器弹窗体系重构

- refactor(voice): 语音面板重构与朗读链路收尾

- refactor(picker): 模型选择弹窗移除按服务商展开分组的状态

- refactor(ui): 设计系统分层落地——令牌单源化与 loading 选型表统一

- refactor(assistants): 用三点菜单替换助手列表左滑操作

- refactor: use official segmented button for provider detail tabs

- refactor(theme): 移除 miuix 依赖与兼容层，仅保留 Material 3（阶段 5）

- refactor(brand): 图标颜色设置收敛为仅背景色

- refactor(nav): 导航改用 androidx.navigation-compose（阶段 4）

- refactor(ui): 补齐剩余页面的 Material 3 迁移（阶段 3-4）

- refactor(ui): 设置页、Provider、输入栏与语音面板迁移到 Material 3（阶段 3-3）

- refactor(ui): 各业务页面迁移到 Material 3（阶段 3-2）

- refactor(ui): 组件层迁移到 Material 3（阶段 3-1）

- refactor(providers): 远程模型选择弹窗改用自适应弹窗与分段列表

- refactor(ui): 自适应层扁平化，仅保留 Material 3 实现（阶段 2）

- refactor(about): 移除频道/群/中转站/打赏入口并改用 Sam-Fic 仓库

- refactor(theme): 配色引擎 M3 化并移除主题族设置（阶段 1）

- refactor(pet): 移除鲸鱼女仆（WhaleMaid）宠物功能

- refactor(AgentChatBody): 移除按钮组与通话圆钮同心的水平偏移计算

- refactor(AgentChatInputBar): 修改待处理图片条的圆角和关闭按钮样式

- refactor(haptics): 触感反馈改走系统预定义质感，设置拆成语义分项开关

- refactor(ui): drop card containers around descriptive notice text

- refactor(theme): route Material 3 color picks through adaptive accessors

- refactor(agent): drop the tool replay guard

- refactor(ui): align settings screens with Material 3 and route copy through resources


### Build

- build: 发版 5.3.10（versionCode 2026101801）

- build: 接入 baselineprofile 基准配置文件

- build: add .tmp_navsrc to gitignore


### Other

- test: 契约与设计规则测试同步语音设置入口

- chore(i18n): 修正英文文案并统一中英文术语与格式

- Remove off-state hint row from model feature settings

- test: 测试断言迁移为 M3-only（阶段 6）

- chore(brand): 清理猫品牌残留资源与死代码

- test(components): InputPopupPositionProvider 断言改为 M3-only

- chore(update): 更新渠道 APK 命名由 daiyu 前缀改为 eta

- chore(brand): 显示名由「代鱼」统一改回上游名称 Eta

- style(ui): 空状态建议色块改用 Cookie6Sided/Cookie4Sided/Circle 组合

- style(mcp): 移除空态里「Tap to add a server」提示

- style(subagent): 子代理分组空态改用分段容器承载文案

- style(ui): 为对话页脚添加16dp右侧内边距

- test: 契约测试同步本次重构

- ci: Eta Build 只在发版标签与手动触发时运行

- ci: 融合上游工作流（PR Check 与 Eta Build）

- chore: ignore the assistant working directory and local inspection report

- chore(build): bump AGP to 9.4.0 and raise the Kotlin daemon heap

- test: update UI contract tests for the reworked settings screens



## [v5.3.9] - 2026-10-12

### Fixes

- fix(chat): reuse a stopped reconnect and cancel deleted runs

- fix(context): resolve revision archive from compaction history

- fix(chat): show continue when compaction is paused

- fix(context): preserve recoverable history across chained compaction

- fix(ui): restore swipe reveal haptics and resize menus after IME

- fix(browser): contain renderer exits without crashing the app

- fix(agent): use current configuration for new child dispatch

- fix(selection): preserve short conversation placement during touch

- fix(markdown): restore direct long-press selection without mode buttons

- fix(markdown): invalidate placement after layer-backed document movement

- fix(build): accept byte-identical patched Compose consumer copies

- fix(test): use Android-compatible class loader synchronization

- fix(agent): explain invalid result paging across archived tasks

- fix(markdown): preserve canonical reveal paths and narrow link cache invalidation

- fix(agent): let children continue after normal parent completion

- fix(claude): preserve native conversation affinity across process restarts

- fix(drawer): keep section keys unique across midnight

- fix(chat): invalidate prepared markdown styles on theme value changes

- fix(chat): carry reveal frame budgets and pump document test hosts

- fix(runtime): preserve closed admission after stopped group retirement

- fix(chat): retain active reveal rows and composition progress across scroll

- fix(chat): draw settled-line fallback with an explicit receiver

- fix(chat): stop freezing row height during a finger scroll

- fix(chat): keep message positions while deferring scroll remeasure

- fix(haptics): keep output ticks after the current reply scrolls away

- fix(chat): keep the typewriter running while the list scrolls

- fix(chat): import the coroutine active flag used while scrolling

- fix(chat): stop streaming layout from clearing visible blocks

- fix(pet): size speech bubbles to their text

- fix(haptics): stop output vibration as soon as chat is covered

- fix(haptics): keep streaming ticks while a covered chat is frozen

- fix(nav): compile the covered-chat gate and keep its clip flag

- fix(diagnostics): qualify column size and align Codex child test

- fix(oauth): align Codex speed tiers with official wire mapping

- fix(build): remove invalid BoxScope member import

- fix(chat): withdraw regressing navigation gate and selection reparenting

- fix: stop chat management gestures from blocking vertical scrolling

- fix(chat): let the visibility gate actually pause hidden reveals

- fix: keep chat management scrolling while preserving swipe back

- fix(diag): import settings list placement observer

- fix: map ChatGPT subscription speed to Codex service tiers

- fix: restore chat-management swipe back and omit OAuth GPT service tiers


### Performance

- perf(chat): reuse terminal order for reconnect marker updates

- perf(markdown): give streamed document blocks their own display lists

- perf(chat): park running pulse while chat is settled-covered

- perf(ui): memoize route signature and isolate drawer redraws

- perf(chat): reuse completed documents and shorten diagnostic locks

- perf(chat): reduce semantics and markdown churn and hold follow during touch

- perf(compose): memoize semantics transparency with mutation invalidation

- perf(markdown): place visible blocks with explicit full-selection fallback

- perf(compose): cache semantics bounds within geometry sorting

- perf(markdown): skip offscreen text drawing while retaining selection

- perf(markdown): extend reveal path cache across catch-up jumps

- perf(chat): move chat icon actions off the miuix squircle layer

- perf(chat): stop paying for invisible icon-button layers

- perf(chat): key row projection by its real inputs and patch unhinted work deltas

- perf(chat): patch work payloads and live prefaces without a full timeline pass

- perf(chat): cut streaming scroll hot paths without touching reveal

- perf(chat): blit finished typewriter lines from a layer

- perf(chat): clip finished typewriter lines as rectangles

- perf(chat): shrink the streaming prefetch while a finger is scrolling

- perf(chat): lift the message list only while following the tail

- perf(ui): keep streaming work off the first fling

- perf(chat): spread the first streaming layout and clip reveal by line

- perf(chat): park a settled covered typewriter without catching up

- perf(nav): freeze settled covered chat without stopping the swipe typewriter

- perf(chat): restore safe timeline append and load avatars off main

- perf(chat): narrow route invalidation and reuse snapshot transformer

- perf(ui): reduce three-page observation and diagnostic overhead

- perf(chat): keep live output during navigation and skip streaming selection


### Refactor

- refactor(chat): add transparent icon action without an offscreen mask

- refactor(chat): route answers and thinking through upstream document renderer


### Other

- test(context): import revision archive in compaction coverage

- revert(haptics): keep output vibration disabled while chat is covered

- test(ui): resize popup frames through Robolectric display

- Agent implementation

- Agent implementation

- test(markdown): observe document state during composition

- test(markdown): drain host work while awaiting cold document

- test(markdown): assert inner document height instead of constrained wrapper

- test(markdown): observe placement callbacks rather than child relayout

- test(markdown): observe viewport gate changes during composition

- Agent implementation

- test(chat): expect in-place patching for thinking and tool payloads

- Revert "share one squircle mask per document"

- test(chat): keep the reentry capture viewport non-zero

- test(chat): pump the first retained-document reentry layout

- test(chat): pump pinned-row host frames and measure natural reentry height

- test(model): keep Codex speed assertions inside the test class

- diag(ui): record settings, chat management, and drawer list frames



## [v5.3.8] - 2026-10-08

### Features

- feat(pet): add an optional whale maid overlay

- feat: make foreground agent orb draggable without press ripple

- feat: add provider custom body settings

- feat: add sub-agent preset cards with conversation-local editing

- feat: unify voice settings and isolate conversation configuration

- feat: add slash skill picker to chat input

- feat: show unread completion ring on finished conversations

- feat: add structured ask_user tool runtime and question card foundation

- feat: implement timed global error reconnect and inline diagnostic markers

- feat: add global error reconnect settings below title model

- feat: wire global error reconnect policy and chat lifecycle on main


### Fixes

- fix(chat): preserve viewport on work-step thinking expansion

- fix(usage): defer a stream's opening receipt until the request ends

- fix(ui): keep covered typing and the top-bar blur while scrolling

- fix(chat): read the covered-route flag inside the scaffold

- fix(pet): update the drag offset without a suspending animation

- fix(pet): drag the whale maid without redrawing its sprite

- fix(pet): mark the in-app whale maid host composable

- fix(pet): keep the whale maid inside Eta when global display is off

- fix(pet): keep the whale maid still when its bubble appears

- fix(pet): keep the whale maid visible at the screen edge

- fix(pet): restore the whale maid position before its first layout

- fix(pet): keep the whale maid inside Eta unless global display is on

- fix(pet): drop the settings size slider and drag by the sprite

- fix(pet): stop echoing the conversation title

- fix(pet): keep the whale maid sprite square

- fix(pet): idle every pose and place the expand chevron by the switch

- fix(pet): stop eating and fade the speech bubble

- fix(chat): call the completed draw layer as a normal function

- fix(chat): keep the retained layer height limit inside its function

- fix(chat): move the completed draw layer out of the message file

- fix(chat): keep the completed draw layer as a file-level function

- fix(chat): declare the completed draw-layer helper before its callers

- fix(diag): accept a missing schedstat sample

- fix(runtime): wait for queued checkpoint writes before seal or discard

- fix(ui): refresh frozen markdown blocks when their text changes

- fix(conversations): retain original identities on message ID collisions

- fix(reconnect): persist safe recovery and migrate legacy defaults

- fix(test): remove invalid Compose assertion import

- fix: reuse initial settings preferences and trace retest boundaries

- fix: reject cross-session screen control instead of queueing

- fix: reduce settings page stream jank hot paths

- fix: share frame work budgets and preserve incremental stream snapshots

- fix: reject paused attempt replies without an active HTTP resource

- fix: freeze reconnect budget and progress while paused

- fix: gate tail diagnostics and stabilize draw observer elements

- fix: give each child dispatch group its own tool lease

- fix: remove invalid Preferences member imports

- fix: restore durable usage accounting and atomic backup metadata

- fix: page child reports and preserve late request diagnostics

- fix: order stop watchdog expiry behind runtime decode FIFO

- fix: enable native Anthropic prompt caching

- fix: isolate log write gate and attribute jank to pages

- fix: restore trailing work expansion during streaming

- fix: isolate logger application binding in Robolectric tests

- fix: chunk oversized diagnostic writes

- fix: use deterministic log file moves

- fix: rotate oversized log records once

- fix: stabilize log rotation and preset dialogs

- fix: stabilize diagnostics rotation and preset editing

- fix: settle streaming footer and settings rendering

- fix: serialize diagnostic log rotation

- fix: use run state for footer projection

- fix: import dialog semantics modifier

- fix: preserve streaming work expansion

- fix: stabilize revision and streaming UI state

- fix: stabilize sub-agent preset editor recomposition

- fix: update ST API join link

- fix: keep runtime diagnostics disabled path inline

- fix: branch previous completed replies without exposing active turn actions

- fix: keep question text at available width and await preview listener exit

- fix: branch active conversations from read-only request snapshots

- fix: reduce model selection highlight inset to 1dp

- fix: slightly inset model selection highlight from dividers

- fix: unify rounded input styling and voice settings haptics

- fix: align question cards with step layout and animations

- fix: preserve conversation update entry point for regression fixtures

- fix: match persisted media revisions and copy long archive closures

- fix: remove mention panel descriptions

- fix: locate revision targets with runtime prompt suffixes

- fix: recover missing legacy branch archive dependencies

- fix: keep question submission state store-owned

- fix: filter nullable selected conversation from completion markers

- fix: hide model retry notices from chat timeline

- fix: keep agent alive after exhausted tool repair and align CI tests

- fix: use supported JSON conversion in reconnect test

- fix: retain shared menu switch imports

- fix: complete reconnect markers and retain local tools during recovery

- fix: let task surface settings own execution routing

- fix: import steering supplement identity helper

- fix: persist stable conversation turn identities

- fix: stabilize thinking expansion viewport

- fix: compact interactive question card

- fix: keep interactive mode menu open

- fix: use Android-compatible JSON key iteration in reconnect tests

- fix: retry disconnected models until reconnect deadline

- fix: wire interactive mode menu toggle

- fix: preserve runtime stop and reconnect behavior

- fix: preserve child stop choices and transport wake order

- fix: align durable question answer shape with coordinator

- fix: harden question cancellation and runtime reconciliation foundation

- fix: preserve reconnect tails and summary interruption during recovery

- fix: retain terminal reconnect updates after manual stop and seal


### Performance

- perf(diagnostics): batch main-thread schedstat reads

- perf(ui): keep a covered chat live and skip top-bar capture while scrolling

- perf(nav): keep secondary pages off the streaming chat snapshot

- perf(chat): stop sampling tail overflow while the user is scrolling

- perf(chat): reuse completed text, table rows, and finished tool rows while scrolling

- perf(markdown): retain completed blocks as offscreen textures

- perf(reveal): resume the typewriter from the pending block

- perf(runtime): stop waiting for checkpoint IO on the streaming caller

- perf(ui): pin finished markdown blocks by their own text

- perf(ui): pin frozen markdown blocks across parse instances

- perf(ui): freeze covered chat snapshots while settings is current

- perf(ui): freeze streaming markdown without remounting completed blocks

- perf(ui): isolate unused composer history and deduplicate confirmed preference sync

- perf(markdown): prepare text off-main and reuse parsed blocks

- perf: skip no-op streaming message publication

- perf: move the usage ledger off settings preferences

- perf: require live streaming slots for preface reuse and update cache contract

- perf: treat raw null array slots as non-canonical in ledger fast path

- perf: keep canonicalizing round-trip for non-integral ledger numbers

- perf: avoid redundant JSON round-trip in model usage writes

- perf: decode conversation usage off the UI collector

- perf: reuse timeline projection for same-slot assistant payload updates

- perf: stabilize plain streaming Markdown image transformer safely

- perf: preserve frozen Markdown renderer inputs across stream snapshots

- perf: skip unused input-bar context budget computation

- perf: isolate streaming subscriptions and reuse chat tail projections

- perf: reduce full-history work during streaming deltas


### Docs

- docs: remove obsolete allocation-sampling claim

- docs: use verified Perfetto v58 query command syntax


### Other

- test(chat): deliver instantaneous lazy growth before first-draw sampling

- test(chat): observe first-draw readiness and expose sampling failures

- test(chat): retain measured thinking growth across viewport recovery

- test(chat): keep the covered-hold case inside the restore test class

- test(app): build the reflected run-result arguments without array projection

- test(app): supply the optional pet reaction flag when invoking run completion

- diag(chat): time AgentChatBody composition separately from its message count

- test(diag): use the frame receiver class when checking the uncovered split

- test(diag): keep the frame callback class name literal in the gap split

- test(diag): keep the new gap stages and exercise them above the slow-message floor

- test(diag): expect the main-thread callback to carry both uncovered spans

- diag(stream): split uncovered main-thread time around measured work

- diag(stream): name the largest measured stage on each main-thread message

- diag(stream): split main-thread wait into run queue and blocked

- test(runtime): seal the checkpoint before reading coalesced events

- test(runtime): expect checkpoint discard to drain queued writes first

- test: align safe recovery contracts and complete terminal usage fixture

- test(markdown): preserve inline code padding in production assertions

- Fix oversized runtime event recovery rows

- Improve streaming frame workload and diagnostics

- Scope foreground exception to Eta self-app GUI operations

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- diag: make frame attribution and capture boundaries observable

- test: respect held maintenance gate during journal recovery

- diag: retain frame evidence and attribute row/block layout

- Agent implementation

- Agent implementation

- test: point stop-seal wiring contract at the decode-ordered watchdog barrier

- test: pass isStreaming in summary cache thinking fixtures

- diagnostics: wire content commit, list measure/place and markdown document spans

- Agent implementation

- Agent implementation

- Agent implementation

- diagnostics: expose frame residuals and main dispatch coverage

- test: verify layout optimizations with native graphics and code-only safety checks

- Agent implementation

- Agent implementation

- Agent implementation

- test: integrate reviewed admission and lazy note contracts

- test: scope diagnostic privacy assertions to values rather than labels

- Agent implementation

- test: integrate reviewed numeric stream diagnostic contracts

- Agent implementation

- test: compare all v2 emitter fields with independent export golden

- diagnostics: accept shared boundary prefix for all v2 records

- diagnostics: integrate reviewed admission lifecycle fixes

- diagnostics: calibrate exporter to final admission snapshot schema

- diagnostics: integrate device profiling and safe analysis kit

- diagnostics: integrate bounded collector and attribution

- Merge remote-tracking branch 'origin/main' into HEAD

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- test: exercise canonical fast path with integer-only ledger fixture

- Agent implementation

- test: preserve long reflow fixture after late reference coverage

- test: use native text measurement for Markdown geometry checks

- test: initialize current reference definitions in Markdown fixture

- test: fix link inspection and retain plain menu callbacks

- test: allow task-tier settings while keeping compact card clean

- Agent implementation

- diag: add opt-in main-thread Compose function tracing

- test: reuse initial disabled logger state without awaiting duplicate emission

- test: expose diagnostic rotation failure evidence

- test: use DpRect bounds for compact card assertion

- test: update viewport height contract

- test: compare collaboration card height in pixels

- test: avoid matching event labels as payload access

- diag: bound stream diagnostic stages

- Agent implementation

- Agent implementation

- Agent implementation

- revert: withdraw unverified streaming optimizations after reported jank regression

- test: respect durability fence before explicit draft recovery

- Agent implementation

- Agent implementation

- Agent implementation

- test: verify preset application CAS inside confirmed speed update

- test: supply required thinking flag for backup fixtures

- Agent implementation

- Agent implementation

- Agent implementation

- Merge remote-tracking branch 'origin/main' into integrate/subagent-model-dialog

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- test: remove ineffective preset dialog clock workaround

- test: stabilize fast revision completion and preset dialog clock

- test: follow shared completed footer projection in streaming contract

- Merge remote-tracking branch 'origin/main' into feat/slash-skills

- test: provide app theme for sub-agent preset settings

- Merge remote-tracking branch 'origin/main' into feat/slash-skills

- test: locate disabled controls without editable actions and diagnose overflow

- Agent implementation

- test: align scroll monitor contract with projected timeline

- Fix compression resume ID in timeline projection test

- Agent implementation

- Merge remote-tracking branch 'origin/main'

- Agent implementation

- test: close completion marker test assertion and class

- test: align exhausted argument message with reconnect policy

- test: align persistence and tool catalog contracts

- Agent implementation

- test: align reconnect cancellation and failure expectations

- Agent implementation

- Agent implementation

- Agent implementation

- chore: bump error reconnect release to 5.3.7



## [v5.3.6] - 2026-10-03

### Fixes

- fix: preserve tool evidence provenance and reject malformed orphan envelopes

- fix: compact orphaned historical tool results as read-only evidence


### Other

- test: align orphan recovery expectations and canonical replay fixtures



## [v5.3.5] - 2026-10-03

### Features

- feat: integrate GPT speed gesture and reset all invalid bindings

- feat: stage GPT speed request snapshot and binding policy

- feat: default text child browsers to frozen full access

- feat: wire V2419A native text takeover and explicit consent

- feat: add disabled V2419A text-only model bridge

- feat: persist virtual display web pairing


### Fixes

- fix: allow GPT speed controls based on model identity only

- fix: recover persisted turn identity for conversation revisions

- fix(chat): persist explicit new conversations before the first message

- fix: require a retained history anchor when branching conversations

- fix(ui): recover pending placement-phase tail lift and regression tests

- fix: require verified Git artifacts before implementation completion

- fix: bound the full child download lifecycle with owner-bound permits

- fix: align stopped child replacement and workspace recovery metadata

- fix: clarify child dispatch and fail-closed recovery paths

- fix: share bounded archive verification and tool reference validation

- fix: keep conversation controls responsive while stopping runs

- fix: preserve retained checkpoint archives in conversation branches

- fix: validate restored archive schema and scope boundaries

- fix: label unmeasured first-turn context as 0k

- fix: keep receipt bindings immutable on runtime reattachment

- fix: preserve first-pending display and revoke stale receipt labels

- fix: invalidate stale learned ratios on corrected usage

- fix: integrate receipt-backed context display and reliable compression

- fix: keep unverified seeds below the complete-request safety bound

- fix: calibrate first-request context estimate per provider/model

- fix: preserve first-request projection boundaries and align provider fixtures

- fix: bind wire byte diagnostics to each serialized request body

- fix: finish single-ring wire estimate integration and diagnostics

- fix: restore cloud-anchored next-request context forecasts

- fix: scope compression send blocking to its conversation

- fix: fence vivo dispatch and add bounded gate diagnostics

- fix: avoid suspend default lambdas in child config resolver

- fix: invalidate preview before assistant and configuration changes

- fix: bind vivo remote candidates before outbound

- fix: restore measured context ring after cloud receipt

- fix: bound browser javascript callbacks

- fix: capture vivo typed requests across mapper contexts

- fix: project next cloud context usage in ring

- fix: arm vivo bridge before vendor onCreate

- fix: expose one-shot output bound to top-level shell

- fix: prevent compaction latch loss and terminal output OOM

- fix: preserve vivo cross-loader reflection names in release

- fix: fence vivo cancellation and revoke stale process consent

- fix: 保留远端改动并修复工具输出收起输入法

- fix: preserve research URL rejection in Unit-returning loader

- fix: enable isolated research browsers for text sub-agents

- fix: 缓存读取虚高只按回执自身总量和窗口判定，不再对比本地估算

- fix: 缓存读取超过整个请求的回执直接丢弃，不进圆环也不触发压缩


### Docs

- docs: record GPT speed landing on local main and integration checks

- docs: record GPT speed candidate review and untested boundaries

- docs: align prompt validation with immediate receipt adoption


### Other

- test: align GPT speed revision checks with model-only eligibility

- test: initialize completed recovery in revision transaction fixture

- merge: add persistent GPT speed controls to child agent settings and cards

- test: guard child GPT speed integration and document review

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- revert(ui): undo accidentally restored placement-phase tail lift

- Agent implementation

- merge: integrate reviewed GPT speed mode with current main

- test(ui): synchronize follow layer transitions deterministically

- test: integrate reviewed async and child-delivery fixture fixes

- Agent implementation

- Agent implementation

- Agent implementation

- test: supply required UI state fields in archive fixtures

- merge: integrate reviewed child browser and delivery recovery fixes

- Agent implementation

- test: cover child browser grant lifecycle and cookie output filtering

- Agent implementation

- Agent implementation

- Agent implementation

- test: correct one-time task preference migration assertions

- Agent implementation

- Merge remote-tracking branch 'origin/main'

- remove: retire complete vivo assistant takeover chain

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- test: distinguish historical bills from the current context receipt

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- test: exercise live calibration and revoked request receipts

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Merge remote-tracking branch 'origin/main'

- Agent implementation

- Merge remote-tracking branch 'delivery/main' into vivo/text-bridge

- test(vivo): supply required system prompt in diagnostic fixtures

- Agent implementation

- Revert "fix: restore cloud-anchored next-request context forecasts"

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- test: allow absent cloud usage in context diagnostics

- Agent implementation

- Agent implementation

- Agent implementation

- Merge remote-tracking branch 'origin/main'

- Agent implementation

- test: align web preview contracts with persistent pairing

- test: 修正虚高回执用例的发送上限期望，子代理通知计数等待异步投递

- subagent: 工作树大文件可分段读取、搜索和精确替换，输出截断失败单独归类



## [v5.3.4] - 2026-10-01

### Features

- feat: 每次询问在第一次操作手机前弹窗选前台或后台

- feat: isolate conversation child settings and supervise detached tasks [skip ci]

- feat: persist verified virtual delivery and show one inline completion notice


### Fixes

- fix: 删除消息按操作栏分段连带删除下面的段，压缩过的会话也能删

- fix: 继承的估算锚点不再拒收本轮首张云端回执

- fix: 每次询问不再卡住或反复弹窗

- fix: 每次询问的任务开始时先保留悬浮窗入口

- fix(compress): 别的会话在压缩时，结束回复的会话不再卡在“正在压缩”

- fix: 任务偏好页恢复横滑返回

- fix(providers): preserve ordered reasoning and compatible usage receipts

- fix(chat): scope reasoning order and pending tails to provider requests

- fix(chat): admit resumed reasoning at carried block index

- fix(chat): preserve resumed visible reasoning blocks

- fix(stats): 补齐兼容接口遗漏的用量回执

- fix(chat): gate late reasoning by event order

- fix(chat): prevent terminal reasoning backfill after streamed answers

- fix(ui): recover measured tail overflow before draw without replacing animations

- fix(ui): retain completed markdown parses across row reentry

- fix(ui): update chat tail layer after list measurement

- fix(ui): let layout pinning own streaming follow

- fix(tts): keep agent speech across pause and report real playback outcome

- fix(ui): pin streaming output during layout

- fix(ui): end the followed list at the rest line

- fix(ui): keep the chat tail lifted when a frame cannot measure it

- fix(ui): rasterize the followed list into the rest-line clip

- fix: 同一轮更正到 80% 以下时取消自动压缩

- fix: 圆环到 80% 就排队，等这次输出结束再自动压缩

- fix(ui): clip the followed chat layer above the composer

- fix(ui): retain detail card graphics layer across transitions

- fix(ui): 离屏纹理不再因重组每帧失效

- fix(ui): 展开绘制层修饰符挂到可见性作用域上

- fix(agent): abort repetitive reasoning without retrying degenerate streams

- fix(vd): verify cleanup-only finalization after failed or cancelled runs

- fix(ui): stop the thinking card edge from bobbing over its text

- fix(ui): 滑动和惯性期间保持最高帧率，不再减速时降到 60 掉帧

- fix: 删除消息只去掉本条操作栏到上一条操作栏之间的内容

- fix(tts): Agent 朗读工具不再被当成孤儿播放立即停掉

- fix(vd): 按审查修正副屏清理与复用的卡死路径

- fix(vd-session): 未标记交付时清理并关闭副屏，恢复不再自动把全部任务当交付

- fix(vd-owner): handoff 结束时按最终快照上报 goneTaskIds，残留任务一律失败

- fix(vd-owner): 副屏切回本会话已开任务，支持空选择清理并区分已消失任务

- fix(ui): prevent blank list growth and recover clipped chat tail

- fix(ui): restore idle composer clip after leaving bottom follow

- fix(context): accept cloud receipt growth without local veto

- fix(ui): keep fast streamed chat above composer

- fix: 自动压缩只看云端实测，和会话圆环同一个数

- fix: 滚动诊断不再引用后声明的 shouldFollowBottom

- fix: 新 run 第一轮用会话上一张云端回执决定自动压缩

- fix: 手指没按下的 UserInput 滚动不再关掉跟底

- fix: 主线程消息日志不再把 nanoTime 0 当成没有起点

- fix: 重生成补落盘路径时同步界面消息；token 用例跟上路径行

- fix: 聊天附件只在本会话内解析，视觉模型也拿到落盘路径

- fix: 请求头删除按钮的震动改到可组合作用域外读取

- fix: 下一轮请求不再因为 1MB 传输上限截掉上文

- fix: 普通发消息不再把上下文圆环改成估算

- fix: 补回按会话分池后丢掉的浏览器快照发布

- fix: 展开回调不在点击闭包里读取 CompositionLocal

- fix: 把 @Composable 放回 ChatMessageItem

- fix: 分支和编辑能对上补充消息的原始上下文

- fix: 流式事件回到主线程再改当前会话，避免输出画到另一个标题下

- fix: resume a thinking pause by continuing the thought, not the whole turn

- fix: close three send/pause/supplement gaps found in review

- fix: align idle compaction with the in-run boundary and stop dropping manual requests

- fix: stop calling LocalSocket.isClosed on reconnected owner clients

- fix: keep a real recent tail when compaction pressure comes from overhead

- fix: make the usage panel numbers reconcilable

- fix: stop rendering an unmeasured context as 0K / 0.0%

- fix: qualify the session seal timeout constant in its test

- fix: only drop turn protection when the checkpoint is genuinely too large

- fix: bound the runtime waits that could never end

- fix: let the UI leave the stopping state without a runtime result

- fix: count Anthropic cache tokens in the context window

- fix: coalesce Anthropic tool results and validate request pairing

- fix: probe protected Linux symlinks with bounded root metadata reads

- fix: protect supervision traces and migrate conversation-scoped regression fixtures

- fix: normalize nullable legacy task tier before parsing

- fix: normalize nullable legacy reasoning preference before parsing

- fix: finalize recovery controls layout and lifecycle recovery guards

- fix: preserve cloud context usage across tool-only pruning [skip ci]

- fix: refine context estimates without drifting cloud calibration [skip ci]

- fix: bound persistence and retain process-owned chat sessions [skip ci]

- fix: restore dual context metering while preserving usage safeguards [skip ci]

- fix: bound conversation residency and preserve unloaded history [skip ci]

- fix: restore continuous work-process cards without losing lazy rows

- fix: reconcile merged usage tests and reject stale compacted run bills

- fix: resolve merged telemetry declarations and preserve usage provenance

- fix: show boundary estimates and restore measured usage

- fix: recover exact tool-envelope rejections with constrained requests

- fix: restore measured context usage and persist scoped cloud receipts

- fix: fence provider callbacks per attempt and test late-usage isolation

- fix: initialize recovered cloud scopes and correct concurrent stop guard

- fix: scope UI cloud receipts to runs requests models and revised history

- fix: publish cloud usage promptly and preserve request-scoped child metering

- fix: merge cloud partial usage within each model request only

- fix: inspect output in all Responses HTTP error statuses before retry

- fix: bound tool envelope correction and reject ambiguous response replay

- fix: gate compression and context telemetry on measured cloud usage

- fix: ignore projected UI usage and invalidate measured occupancy after pruning

- fix: scope preview CORS capabilities to read and close routes

- fix: unblock close during frame stalls and use public display absence checks

- fix: add verified web manual close and preserve read-only owner recovery

- fix: skip acknowledged transcript encoding and revoke preview on page exit

- fix: capture virtual display frames on demand and finish verified owner release

- fix: invalidate cached backend state before resume probe

- fix: guard recovery lifecycle and check backend off main thread

- fix: integrate context pressure recovery and bounded summary retries

- fix: recover pending hard-pressure and summary output budget repairs

- fix: recover detached runtime subscriptions without false terminal UI

- fix: preview existing authenticated virtual displays without recreating owners

- fix: import assistant message timeline type

- fix: aggregate agent work steps and add vd recovery preview

- fix: stabilize virtual handoff preflight and terminal reasoning

- fix: keep balance indicator in neutral theme color

- fix: fail closed on owner recovery and surface auto-delivery failures

- fix: handle detached owner process handle

- fix: place recovery token accessor in client body

- fix: compile owner recovery client

- fix: recover and auto-finish virtual display sessions

- fix: allow multi-activity task handoff

- fix: auto-finish owned virtual display tasks

- fix: prove virtual display task occupancy conservatively

- fix: avoid nonlocal return bytecode in streamed list

- fix: bound virtual task diagnostics and stabilize selection test

- fix: use GFM cell token in reveal policy test

- fix: render agent task preference as a full settings page

- fix: keep streamed chat above composer and reveal blocks smoothly


### Performance

- perf(chat): disable expansion header click ripples

- perf(ui): request peak refresh while the main window is resumed

- perf(ui): retain chat rows around the viewport

- perf(ui): keep card expansion off the tap frame and decode previews off main

- perf(ui): 展开内容停稳后画进离屏纹理，滑动不再重放文字命令

- perf(ui): 展开停稳后把正文留在绘制层，滑动不再每帧重录

- perf(ui): 滑动帧率改为每帧随绘制层投票，并记录开关诊断

- perf: 主线程长消息里标出已打点阶段的耗时

- perf: 展开打点同时记录动画形状和掉帧来源

- perf: 展开打点只记回调类名，换会话时收掉旧窗口

- perf: 呼吸动画只重画图标；展开点击后逐帧打点

- perf: avoid repeated full-history encoding during transcript trimming

- perf: compose expanded tool steps as independent lazy chat rows


### Other

- test: 删除分段用例沿用现有会话构造

- balance: 余额动画移到独立文件，静态指示器保持无定时逻辑

- balance: 余额变化时顶栏播放扣费动画

- subagent: 子代理工具失败时带上状态和能做的下一步

- chat: 推理展开后点正文也能收起

- subagent: 替换条件和工作区不可用的说明按实际行为写

- subagent: 子代理工具出错时告诉模型下一步怎么改

- test: 默认设置表补上后台震动两个开关

- haptics: 新增后台震动和推理过程震动开关

- balance: 余额预设加 Sub2API 和 DeepSeek

- haptics: 推理已完成时轻震一次

- Agent implementation

- test(perf): trace chat owner and body execution

- test(ui): invalidate first-draw recorder and drive the headless host draw

- test(ui): flush Android draw while preserving first-frame probes

- Revert "fix(ui): update chat tail layer after list measurement"

- test(ui): auto-synchronize follow toggles and restore manual clock

- test(ui): yield to host while awaiting follow recomposition

- test(ui): await committed follow transitions without disturbing layout

- test(ui): import pointer click extension for tail regression

- diag(ui): trace idle chat scrolling independently of streaming

- test(ui): check bottom-anchored card animation frame by frame

- Merge branch 'fix/agent-tts-survives-pause'

- Revert "fix(ui): pin streaming output during layout"

- Revert "fix(ui): end the followed list at the rest line"

- Revert "fix(ui): let layout pinning own streaming follow"

- vd: 中间任务已经消失时不再拦住副屏释放

- test: 视口检查计入静止裁剪的输入框间距

- haptic: 切到暂停会话时停掉原来那条的震动

- test(ui): synchronize card exit with Android layout and disposal

- test(ui): wait for interrupted card transition to settle

- ui: 图标确定按钮改成居中的莫奈长条

- revert: 版本号改回 5.3.4，离屏纹理改动保留

- ui: 去掉图标确定按钮的说明

- Revert "fix(ui): 滑动和惯性期间保持最高帧率，不再减速时降到 60 掉帧"

- Revert "perf(ui): 滑动帧率改为每帧随绘制层投票，并记录开关诊断"

- Agent implementation

- Agent implementation

- ui: 压缩完成提示的会话标题最多显示 3 字

- ui: 压缩完成提示里的会话标题最多显示 12 字

- ui: 虚拟屏完成改为 Toast，不再在会话底部标已完成

- ui: 切换会话后仍提示压缩完成

- notify: 通知图标改成同一只猫

- icon: 按原图留白和笔触重做桌面图标

- ui: 会话列表按创建顺序固定，运行中不再互相换位

- icon: 改图标先预览，确定后才换桌面

- icon: 预览和桌面使用同一张图标，点按可切换

- test(vd): finish_virtual_session 仅 root 目录可见，契约测试改用 rooted catalog

- icon: 换上作者提供的猫咪图标

- ui: 外观里可以改图标的浅色、暗色和猫咪颜色

- test(ui): measure collapsed list rows without semantics bounds

- Revert "fix(ui): restore idle composer clip after leaving bottom follow"

- ui: 右上角会话用量按上游四桶计算

- stats: OpenAI 兼容接口单独记录缓存创建

- icon: 启动图标换成提供的头像

- ui: 下沿展开时尾部哨兵被挤出也不再掉下去；越线进输入框的诊断

- ui: 标签被推上去时展开从下沿长出，不再先往下长再被追回

- haptic: 展开侧栏或离开页面也不停

- ui: 删除消息后保留仍有效的云端上下文

- haptic: 当前会话退到后台后继续震动

- ui: 去掉 @会话芯片上的快照字数

- Revert "ui: 快速出字时把越过静止线的内容裁在离屏层里"

- test: 会话存储用例跟上模型历史另存文件

- haptic: 只有当前聊天页的会话才震动

- ui: 展开动画不再因输出中滑动而跳过

- ui: 输出中滑动时展开直接到位

- ui: 补上提供商页缺少的点击震动

- ui: 展开时不再把整页会话画进离屏缓冲

- ui: 恢复工具和推理从上沿展开

- ui: 快速出字时把越过静止线的内容裁在离屏层里

- test: 跟上数据库 31、租约计数和工具证据文件

- test: 子代理工作区检查跟上数据库 31

- ui: 跟底输出时把越过静止线的文字裁掉

- ui: 模型提供商列表的按钮接入震动

- ui: 提供商页增加会话 ID 网关卡片

- ui: 远端模型改为勾选后再添加

- ui: 在启动 Kimi Web 上增加子代理状态窗口

- ui: 只有执行中的子代理回到上下文统计

- ui: 模型选择器顶部加搜索条

- ui: 子代理继续执行后重新出现在上下文统计

- ui: @会话里的工具改为按需读取完整记录

- ui: @会话只放引用，正文改由模型按需读取

- model: 同一会话固定 prompt_cache_key

- ui: 侧栏圆点和置顶对调，通知按会话数计正在执行

- backup: 整库导出按会话写入，不再受 8MB 整库快照限制

- ui: 管理会话时不再跟着执行中的会话换位置

- test: 用量断言改成模型账本口径

- browser: 按会话隔开标签，编号回收为 0 到 2

- ci: 只保留带单元测试的构建工作流

- ci: 不再安装已删除的 Android SDK tools 包

- ui: 底部工具展开后等尾部停稳再恢复上提

- ui: 恢复展开只淡入、滑动不再逐帧重排

- revert: 撤回把展开动画改成防掉帧的几笔改动

- ui: 展开动画始终播放，不因滑动取消

- ui: 恢复工具和推理的展开动画，滑动时立即到位

- ui: 展开工具和推理不再播放动画

- stats: 单独显示缓存创建，并在用量第一行加总计

- stats: 对话统计改用模型用量账本，Anthropic 缓存创建计入缓存

- ui: 展开工具或推理时滑动不再逐帧重排

- ci: 推送到 main 不再自动编译

- ui: 前台工具标签出现时不再被打字振动间隔丢掉

- Merge branch 'fix/vd-launch-photo-picker-identity'

- Merge branch 'fix/composer-follow-gap'

- vd: 同一任务里叠了系统相册时不再拦住启动其它应用

- ui: 回到后台后生成震动继续

- ui: 展开最底部工具时不再被跟底上提弹一下

- ui: 跟底输出时把未追上的尾部在绘制层上提，卡片底边与输入框保留 14dp 间距

- Merge branch 'fix/child-lifecycle-integration'

- ui: 跟底输出的裁剪线下移到静止停靠线（输入框上方 14dp），卡片外框跳动不再越线

- ui: 输入器悬浮回透明四周，跟底输出时把正文裁在输入框上沿

- ui: 输入框上方间距移到裁剪区外，流式正文不再压进间距贴到输入框

- Revert "ui: 正文视口在布局阶段扣一次输入器高度（消除白色空白带，输入框周围仍透明）"

- ui: 正文视口在布局阶段扣一次输入器高度（消除白色空白带，输入框周围仍透明）

- Revert "ui: 流式输出时正文不再越过输入框（底栏高度改在布局/绘制阶段读取）"

- Revert "voice: 适配 composerViewport 的延迟 inset 参数"

- Revert "test: 语音面板契约适配延迟 inset"

- chat: 停止后最后一条未入历史的追加消息可编辑（以完整历史为前缀，不抹除内容）

- chat: 压缩中有草稿时显示灰色发送键，点击提示正在压缩

- test: 语音面板契约适配延迟 inset

- voice: 适配 composerViewport 的延迟 inset 参数

- ui: 流式输出时正文不再越过输入框（底栏高度改在布局/绘制阶段读取）

- 统计口径：不可能的账单不入账，消息数不再计入空占位行

- 更新双计量契约：占用分母改用在飞行 run 的窗口，并要求可信性判断前置

- 运行中改上限不再影响在飞行中的 run 的占用口径

- 修正溢出用例：无锚点时极端账单只做边界收敛，不参与增量判定

- 按增量一致性而非绝对倍数判定回执，撤回未校准余量

- 只接受可信回执作为窗口占用，并为未校准的发送留出余量

- Retry empty OUTPUT_LIMIT responses instead of failing the run

- ui: stop replies at the composer top edge

- ui: fix review findings on the Material task preference page

- Agent implementation

- ui: make the composer surround transparent

- Spread tapped thinking expansion over frames and skip path clip on middle card slices

- Match environment rule to detection availability; pin gate test markers

- Stop streaming deltas from draining the reveal queue

- Resolve gate source path from either module or repo root

- Assert the haptic gate ignores window focus structurally

- Fix prompt assertion to use named builder arguments

- Keep streaming haptics alive across hosts and window focus

- Detect shell tools per round and pick environment from evidence

- Route ordinary delegation only to live child generations

- Record guarded tool result code in ToolCallDiag

- Preserve checkpoint event semantics while deferring derived work

- Agent implementation

- Make disconnect regressions close TCP and expose CI test progress

- Use portable structural JSON assertions in Responses regressions

- Fix diagnostic metadata compilation and anonymous call correlation

- Trace tool-call provenance with bounded opt-in diagnostics

- Bound invalid tool repair and reject incomplete Responses calls

- Avoid blocking pause checks under child task monitor

- Fix unit test imports for existing JUnit and Compose dependencies

- Fix retained child dispatch and footer ownership

- Integrate child lifecycle controls and owner context telemetry for CI

- Agent implementation

- Agent implementation

- Agent implementation

- test: await delivered diagnostic events before checking lifecycle and redaction

- test: fix Robolectric application access and missing assertion import

- Agent implementation

- Integrate final guarded draft recovery candidate

- Agent implementation

- Integrate reviewed candidate for final bootstrap recovery safeguard

- Agent implementation

- Merge completed supervision and horizontal controls with cloud usage preservation

- Agent implementation

- Merge supervised child lifecycle with current context-estimation fixes

- Agent implementation

- Agent implementation

- merge: preserve latest cloud usage fixes with full virtual display delivery

- merge: deliver virtual display UI runtime and memory fixes

- test: keep post-compaction fixture smaller and bound transport tests

- merge: preserve cloud receipts and virtual delivery across both baselines [skip ci]

- merge: preserve built baseline and reviewed UI fixes [skip ci]

- Agent implementation

- wip: preserve interrupted tool envelope recovery implementation

- wip: preserve cloud context UI draft for completion

- wip: use only provider occupancy for automatic compaction decisions

- wip: preserve existing virtual overlay draft for verification

- Agent implementation

- Agent implementation

- Agent implementation

- ui: move recovery into backend-gated task preferences as Material page

- test: compare preserved JSON receipts structurally, not key ordering

- test: assert preserved receipt freshness and fail-closed boundary

- Agent implementation

- wip: preserve drafted UI memory lifetime bounds

- Agent implementation

- Agent implementation

- wip: preserve handoff diagnostics parser draft

- Agent implementation

- Agent implementation

- wip: preserve drafted safe background handoff and regression tests

- ci: retain release R8 mapping for verified crash diagnosis

- Agent implementation

- Agent implementation

- Agent implementation

- wip: stage preserved runtime draft for final review

- Agent implementation

- wip: preserve incremental handoff evidence and recovery fixes

- wip: stage preserved handoff draft for final review

- wip: stage preserved compression draft for final review

- Agent implementation

- Agent implementation

- Agent implementation

- ci: validate virtual display preview gateway

- revert: restore v5.3.3 UI while retaining virtual display and balance

- test: import assertThrows in handoff diagnostics regression

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Agent implementation

- Keep chat content visible and classify virtual task inventory

- Agent implementation

- Accept SDK 37 V2 signer certificate output while enforcing exact fingerprint

- Compare balance query configuration instead of unrelated provider timestamps

- Keep coroutine regression test methods compatible with JUnit void contract

- Fix balance concurrency regression tests and enforce original APK signing

- Integrate balance lifecycle refresh and visible stale state at both entry points

- Merge virtual display integration and balance refresh fixes into main

- Agent implementation

- Add isolated remote validation for balance refresh fix

- Reject unsupported virtual request variants before creating an owner

- Handle empty sessions and freeze root-only VD capability routing

- Add transient handoff cover and icons required by remote catalog test

- Run isolated VD candidate validation on its dedicated remote branch

- Keep authenticated idle sessions and atomically expire queued owner actions

- Wire experimental background GUI and explicit handoff through authenticated owner

- Implement bounded task handoff candidate and correct root display creation

- Bound IPC reads and response writes, cancel pending timed-out mutations

- Preserve root VD owner candidate for review, handoff still missing

- Fix restored screen imports and remove dormant legacy launch route

- Correct restored VD copy to avoid automatic shutdown promises

- Restore archived VD settings and guarded read-only entrypoints without legacy shell routing



## [v5.3.3] - 2026-09-23

### Features

- feat(ui): redesign subagent controls with Material styling

- feat: add configurable media agents and targeted context compaction

- feat(ui): select child models on tap and lock collaboration while running

- feat(agent): configure child reasoning depth

- feat(agent): auto-compact children and refresh collaboration dialog

- feat(agent): add role-based delegation and isolated project worktrees

- feat(agent): show child task status and main-agent review in tool traces

- feat(agent): add scoped read-only sub-agent delegation and model settings

- feat(tts): add a dedicated CosyVoice/MOSS speech provider like Doubao


### Fixes

- fix: preserve compaction state and image parameters; navigate user messages

- fix: recover task IDs after compaction and disambiguate model picker test

- fix(ui): reserve dialog action space on small screens

- fix(ui): consume touches inside locked collaboration dialog

- fix(stats): persist lifetime conversation usage across all request roles

- fix(ui): remove model usage accounting note

- fix(stats): record model usage per request across all agent roles

- fix(agent): rely on picker speech filtering for role models

- fix(tts): use ordinary providers and verified relay voice IDs

- fix(agent): redact delegated context before execution and filter child entry tools

- fix(agent): forward conversation collaboration scope and verify child loop

- fix(tts): map CosyVoice and MOSS-TTSD instead of OpenAI Alloy voices

- fix(voice): use the read-aloud picker chrome for realtime call voices

- fix(chat): do not replay fade-in when a streamed answer settles

- fix(voice): keep trailing ASR punctuation during duplex two-pass updates


### Other

- Smooth streaming bottom-follow acceleration and remove repeated jumps

- Agent implementation

- Preserve model reasoning choices and streamline streaming UI and usage display

- Agent implementation

- Keep delegated role and project when a provider event is partial.

- Pass the executing-session flag through the execution service.

- Count only sessions that are actually executing in the notification.

- Name the running-task notification 代鱼 instead of Eta.

- Keep the provider-outage sentence inside the delegation prompt.

- Give text sub-agents six minutes and surface provider outages.

- Prepare implementation worktrees before delegate_task returns.

- Match image output assertions and avoid the idle dialog tests.

- Label generated image output as resolution, not size.

- Show only the actual size and ratio after image generation.

- Keep parallel-limit dialog tests idle under Robolectric.

- Make parallel delegation a hard rule and record missing frame time.

- Move parallel limit controls below each subagent's reasoning setting

- Align collaboration guide with shared limits and continuation states

- Log child worker exceptions with safe HTTP status metadata

- Add privacy-safe subagent diagnostics and release 5.3.3

- Unify image resolution tiers and add verified Grok and model concurrency controls

- Wire direct media stop controls to cancellable network requests

- Use DpRect edges for image layout assertions

- Show queued child tasks and task list summaries accurately

- Add endpoint-aware thinking settings for media subagents

- Support natural image settings and bounded image batches

- Render chat image captions as separate bounded blocks

- Plan image requests by endpoint protocol and verify native outputs

- Isolate stream diagnostics failures and retain accessible chip hit area

- Fix streaming delivery and generalize image parameters

- Refine sub-agent floating add button and compact switches

- Fix sub-agent menu size and anchor

- Polish sub-agent settings controls

- chore: bump version to 5.3.2

- test(agent): cover initial usage estimates and four child slots



## [v5.3.1] - 2026-09-20

### Features

- feat(chat): navigate turns with directional single-haptic arrow gestures

- feat(appearance): show persisted reply completion timestamps and simplify ASR hints

- feat(settings): add auxiliary vision and title models under model features


### Fixes

- fix(chat): preserve continuous animated turn transitions past supplements

- fix(chat): jump directly between turns without intermediate message stops

- fix(markdown): let native selection own link long presses

- fix(chat): open local file links and test Markdown link selection

- fix(haptics): emit one vibration for voice long press

- fix(voice): require recognized speech before interrupting duplex playback

- fix(responses): reconcile sole terminal text after identity changes

- fix(chat): preserve per-conversation input drafts across navigation

- fix(composer): center voice indicator independently of reasoning button

- fix(chat): distinguish missing revision history from real compaction

- fix(compaction): retain latest occupancy while manual summary is pending


### Docs

- docs: record successful draft and timestamp build verification


### Other

- chore: bump version to 5.3.1



## [v5.3.0] - 2026-09-20

### Features

- feat(voice): add MiMo reference voices and separate personal voice entries

- feat(voice): independent modes, unified menus and scrollable transcripts; show cache hit rate

- feat(tts): group personal voices under a dedicated section label

- feat(voice): guide slot discovery with selectable cards and training availability

- feat(settings): simplify voice setup and support long shell commands without input cap

- feat(voice): add bounded redacted Doubao request diagnostics and copy panel

- feat(voice): import purchased voices with signed paginated catalog lookup

- feat(voice): add independent Doubao ASR and persistent personal voice cloning

- feat(chat): add bounded streaming pipeline and window frame diagnostics

- feat(duplex): isolate realtime settings and add official voice picker

- feat(voice): add correlated privacy-safe pipeline diagnostics

- feat(mentions): spill tool details into workspace files for @conversation

- feat(mentions): include tool arguments and results in conversation snapshots

- feat(providers): add opt-in Responses reasoning status compatibility

- feat(voice): add universal and SeedDuplex voice modes

- feat(tts): add a text_to_speech agent tool

- feat(tts): adapt RikkaHub vendor speech engines

- feat(tts): pick Doubao voices from the official catalog

- feat(tts): add a dedicated Doubao Voice provider preset

- feat(tts): add Doubao Voice HTTP and Ark Seedance

- feat(tts): add cancellable system and cloud reply playback

- feat(chat): mention full conversation snapshots and keep both ends

- feat(chat): reference other conversations with scoped read-only snapshots

- feat(chat): bind assistants to conversations


### Fixes

- fix(selection): preserve native selection gestures and guard link navigation

- fix(chat): hide duplicate file cards for media previews

- fix(voice): remember model voices and separate mode selection from start

- fix(prompt): stop injecting configured model identity

- fix(voice): remember last entry mode and reserve long press for switching

- fix(voice): unify Material menus and add preview state and project picker

- fix(ui): constrain voice navigation callback to Unit

- fix(voice): parse streaming text variants with diagnostics and use Material voice management

- fix(voice): keep untrained catalog slots out of personal voices

- fix: persist encrypted voice credentials and synchronize stream haptics with reveal

- fix(voice): align catalog signing headers and remember key ID locally

- fix(voice): allow local record removal and surface catalog authentication failures

- fix(voice): support free prepaid slots and explicit postpaid clone mode

- fix(chat): decouple manual scrolling from text reveal catch-up

- fix(chat): restore incremental reveal after scrolling and test resume lifecycle

- fix(chat): reset submit scrolling and keep browsed streaming text fully revealed

- fix(chat): preserve measured text while reveal nodes reattach during scrolling

- fix(chat): drive streaming haptics from live deltas independently of scrolling

- fix(voice): unify dictation master switch and pin switch and speech pack first

- fix(voice): preserve unavailable personal selections and expose ASR errors

- fix(chat): clear unfinished mention placeholders after navigation

- fix(chat): reject mismatched tool turns and remove deleted pending mentions

- fix(chat): export verified original tool responses for conversation mentions

- fix(diagnostics): format snapshots outside hot-path lock and bound log lines

- fix(chat): remove synchronous history markdown parsing from streaming gallery updates

- fix(chat): freeze completed markdown blocks and pause reveal while scrolling

- fix(chat): stop bottom-follow on drag and avoid bottom arrangement while streaming

- fix(chat): inherit active conversation reasoning effort when creating a draft

- fix(voice-ui): remove redundant realtime call heading

- fix(chat): isolate reasoning effort per conversation without mutating model defaults

- fix(chat): keep context usage ring visible for empty drafts and unknown limits

- fix(voice-ui): show voice names without internal IDs

- fix(voice-ui): right-align selection marks and link read-aloud settings

- fix(voice): restrict pickers to speech providers, models and listed realtime voices

- fix(duplex): request signed PCM16 output to match AudioTrack

- fix(duplex): isolate realtime voice from ordinary TTS voice settings

- fix(duplex): surface empty audio responses and log event schema

- fix(duplex): cancel before closing channels and fence stale session callbacks

- fix(duplex): decode audio field and replace ASR hypotheses

- fix(duplex): distinguish recognized speech from replies and reset response text

- fix(duplex): enable second-pass speech recognition

- fix(duplex): accept binary JSON events and diagnose audio delivery

- fix(voice): preserve voice sessions across first send and correct duplex API auth

- fix(providers): remove Responses reasoning-status compatibility toggle

- fix(responses): keep hosted web_search off unofficial endpoints

- fix(voice): speak committed sentences while the chat reply is still streaming

- fix(ui): drop overlay icons from voice conversation buttons

- fix(voice): keep TTS voice and play voice-mode audio on speaker

- fix(ui): pass voice mode state into the chat scaffold

- fix(chat): save previewed videos from the download button

- fix(chat): prevent link activation when releasing a text-selection long press

- fix(drawer): keep full conversation source for live search edits

- fix(settings): move General section above Tools

- fix(ui): remove compression selection ripples and collapse other providers

- fix(model): recover from opaque custom inference 400s

- fix(tts): speak visible turn text, not hidden reasoning

- fix(tts): read thinking before the visible answer

- fix(tts): keep Doubao speech models off the Ark provider

- fix(tts): expect both Doubao catalog models in the speech picker

- fix(tts): consume every Doubao JSON audio chunk

- fix(tts): accept short Doubao audio payloads in tests

- fix(tts): play Doubao PCM/WAV instead of forcing MP3

- fix(tts): parse Doubao unidirectional JSON audio chunks

- fix(tts): show Doubao speech models without a /models catalog

- fix(tts): read cloud speech settings from local prefs

- fix(compaction): split oversized history and bound summary merges

- fix(chat): keep conversation mentions as chips instead of message text

- fix(responses): persist scoped reasoning and preserve DeepSeek thinking replay

- fix(ui): vertically center composer actions beside dictation

- fix(ui): apply dictation alignment to outer visibility layout

- fix(chat): preserve caller compatibility and cover stop recovery

- fix(chat): seal stopped history and unify turn boundaries

- fix(ui): center the dictation indicator on the full composer row

- fix(ui): pass conversation assistant id into chat and drawer

- fix(ui): clear running dots on stop and center the edit indicator

- fix(chat): continue after stopping during API retries

- fix(chat): keep failed turns and continue retries on a new turn

- fix(chat): continue disconnected runs from the composer

- fix(voice): disable OpenMP affinity before loading ncnn


### Performance

- perf(overlay): remove fullscreen glow and bound orb draw updates

- perf(chat): isolate markdown display lists and yield follow to user scroll immediately

- perf(chat): remove quadratic history scans and reduce stream rendering overhead

- perf(chat): reuse stable text annotations and append reveal paths in batches


### Refactor

- refactor(mentions): store tool snapshots under 快照缓存/tools


### Other

- test(selection): isolate unsupported Robolectric magnifier surface

- test(voice): run signed HTTP transport with Android logging runtime

- test(terminal): cover long daemon and session commands; reset voice wizard navigation

- test(voice): verify diagnostic redaction and output bounds

- test(voice): verify master-switch migration and persistent off state

- test(voice): verify API-key auth and control-plane HMAC signature

- test(chat): compare cached image extraction against existing reference behavior

- test(tts): keep committed-sentence fixtures above the merge threshold

- test(tts): route Doubao speech tests through the official openspeech host

- test(tts): expect Doubao catalog extras before saved speech models

- test(markdown): use GFMTokenTypes.CELL in render cache tests

- Revert "fix(model): recover from opaque custom inference 400s"

- test(tts): harden visible turn speech selection

- chore: bump version to 5.3.0



## [v5.2.9] - 2026-09-18

### Features

- feat(voice): add opt-in offline streaming dictation and speech pack settings

- feat: align browser UI and improve accessibility and summary diagnostics

- feat(browser): integrate multi-tab browser UI and retire Antigravity backend

- feat(compaction): 只保留持续执行，压缩可单独选 Endpoint

- feat(compaction): add adaptive auto target to manual and automatic compression

- feat(provider): 增加反重力 Google Antigravity OAuth

- feat(repo): 开放 GitHub Issues 方便反馈

- feat(provider): 提供商支持 OpenAI Codex OAuth


### Fixes

- fix(ui): drop press ripples on compress endpoint help and speech indicator

- fix(voice): restore original circle when dictation animation stops

- fix(build): resolve generated speech JNI path for Android source sets

- fix(ui): refine compression controls and show completion count toast

- fix(test): use available Robolectric application runtime

- fix(provider): rebind application context for credential cleanup

- fix(antigravity): use callable model catalog keys

- fix(agent): preserve interrupted turns and improve compression and HTTP diagnostics

- fix: complete compaction endpoint wiring and separate paused supplements

- fix(resume): 带句号的短确认不要当接缝

- fix(resume): 单个汉字接缝也去掉

- fix(compaction): 切块预算与摘要请求使用同一生成预留

- fix(vision): 聊天图别名映射到缓存，避免 read_image 找不到源文件

- fix(resume): 三字中文接缝也去掉

- fix(compaction): 生成上限只差几百时不再重试

- fix(compaction): 大窗口摘要直接按 16k 生成并先校验归档

- fix(resume): 短中文接缝去重，暂停后续写恢复打字机

- fix(compaction): 摘要只挂这一次替换检查点

- fix(compaction): 修好摘要展示字符串字面量

- fix(compaction): 对齐 DeepSeek 摘要结构与保留尾

- fix(runtime): 暂停后执行完整工具调用，并修好侧栏搜索抢键盘

- fix(compaction): redact sensitive tools in the archived prefix

- fix(ui): keep streaming markdown tree across pause and completion

- fix(provider): classify HTML and Responses streams instead of leaking OkHttp types

- fix(compaction): update default callbacks after removing target argument

- fix(streaming): deduplicate resumed text seams and retain conversation reveal state

- fix(resume): preserve reasoning setting in continuation prompt path

- fix(resume): hide first reasoning block before UI event replay

- fix(compaction): give explicit measured length feedback on summary rewrite

- fix(compaction): separate text acceptance from generation and repair CI regressions

- fix(ui): suppress only first resumed reasoning block

- fix(ui): hide paused continuation reasoning

- fix(compaction): honor selected summary budget

- fix(compaction): bound continuation retention budget

- fix(streaming): preserve paused continuation text and logical turn identity

- fix(compaction): separate generation budget and retry output truncation once

- fix(compaction): unify retention, snapshot replay and cancellation

- fix(compress): 摘要进度日志不再弄崩单元测试，keep 0 按最后一条用户消息切

- fix(ui): 去掉段首 [[10]] [[9]] … 编号引用堆

- fix(compress): 对齐 DeepSeek harness，先剪工具、按用户留尾巴，摘要超时并给出原因

- fix(ui): 补上 isSteerSupplement 引用

- fix(runtime): 结束暂停任务时把已写出的助手正文写入模型历史

- fix(ui): 暂停后续写只恢复打字机，不把新字一次追平

- fix(compress): 摘要探测成功后仍检查取消，避免把已取消当成功

- fix(compress): 修好摘要正则，手动和默认模型也走同一套思考探测

- fix(compress): 压缩思考从关档往上试，成功后按模型记住

- fix(compress): 压缩思考档与对话分开，能关就关，不能关用最低档

- fix(compress): 摘要格式不对时先本地改成检查点，再单独重排

- fix(runtime): 无感衔接同时覆盖写正文和继续干活

- fix(runtime): 暂停续写改成无感衔接，不再宣布接着往下

- fix(runtime): 暂停后带着半截正文继续，仍算同一轮

- fix(runtime): 暂停后续同一轮，不把半截正文写入压缩历史

- fix(runtime): 暂停打断当前流，切会话回来不再重放打字

- fix(ui): 流式滑动时列表不再每帧跟着跟底重组

- fix(oauth): 反重力请求对齐 CLIProxyAPI Hub 客户端

- fix(ui): 反重力登录按钮改成 Google 文案

- fix(oauth): 反重力客户端改成官方 4.3.0

- fix(ui): 流式输出继续跟底，只有手指拖走才停

- fix(oauth): 反重力改用官方 MACOS 客户端标识

- fix(ui): 上滑聊天不再被拽回底部，工具步骤回到分组卡片

- fix(build): animateItem 放到 item 作用域，浏览器遮罩仍用 BoxScope

- fix(ui): 流式长内容和工具堆叠时聊天不再整卡重绘

- fix(oauth): 反重力 403 提取 Google 验证链接

- fix(ui): 提供商批量删除确认改用系统弹窗

- fix(oauth): 反重力补上免费档开通和真实项目 ID

- fix(oauth): 反重力 403 带上原文，并改走正式 Cloud Code

- fix(oauth): 反重力去掉占位模型和 uniqueItems

- fix(build): 修好反重力请求头和 URL 拼接

- fix(build): SSE Content-Type 补丁不要误伤 MCP

- fix(oauth): Codex SSE 缺 Content-Type 也能解析

- fix(oauth): Codex 补上账号 ID，并不再发送 none 推理

- fix(build): Codex 引用改为显式 import

- fix(oauth): Codex 拉取模型和对话请求对齐官方接口

- fix(test): 迁移测试补上 24 到 25

- fix(oauth): 单测不再依赖 android.net.Uri

- fix(oauth): 应用内 WebView 登录，拦截 localhost 回调


### Performance

- perf(compaction): constrain first-pass summary length to avoid rewrites


### Refactor

- refactor(compaction): queue safe-boundary maintenance and use 90 percent pressure


### Docs

- docs: 把仓库介绍改成代鱼


### Other

- chore: bump version to 5.2.9

- test(agent): supply Anthropic server request callback

- test: align compaction regressions with token-tail policy

- test(vision): 别名解析不要用 CI 上真实存在的 /etc/passwd

- test(compaction): 摘要生成上限按 128k 输入封顶对齐

- test(compaction): 脱敏压缩用例要能真正缩小上下文

- test(compaction): compare streaming result with canonical checkpoint

- test: 暂停续写先返回半截正文，再模拟打断 SSE

- ci: 单测失败不再继续打包



## [v5.2.8] - 2026-09-16

### Features

- feat(chat): 系统打开方式选代鱼后把文件放进输入框

- feat(compress): 优先持续执行时允许保留 0 轮

- feat(backup): 完整备份包含提供商密钥、MCP Token 和 Linux 环境

- feat(provider): 编辑模型时可手动开关视觉能力

- feat(backup): 备份覆盖助手技能设置附件，可选完整 Linux 环境


### Fixes

- fix(build): AgentAppShell 改为 internal，避免暴露压缩策略类型

- fix(compress): 默认优先持续执行并去掉保留轮数

- fix(ui): 进入会话时直接停在底部

- fix(ui): 用打开方式进代鱼时回到首页对话

- fix(compress): 严格切分继续拒绝孤儿工具，容错只用于 keep=0

- fix(test): keep=0 切分容忍缺 toolCallId，并钉住完整批次边界

- fix(build): SEND_MULTIPLE 按 Parcelable 列表读取再转 Uri

- fix(build): 打开方式 URI 读取改成显式 Uri 类型

- fix(build): 修正打开方式类型推断与推理标签编译

- fix(ui): 修好打开方式编译，并稳住模型行设置与推理标签

- fix(compress): keep=0 不再摘要当前轮，失败后停止空转

- fix(ui): 模型列表当前勾放到设置按钮左边

- fix(agent): 任务中自动压缩改到 90% 窗口再触发

- fix(ui): 设置页顶部显示当前会话的提供商和模型

- fix(backup): 备份还原模型用量账本和已归档用量

- fix(about): 修正 QQ 群网页加群链接

- fix(ui): 当前勾放到右侧，并跟随会话所选模型

- fix(test): 恢复按行更新提供商，并稳定模型请求头断言

- fix(provider): 更新提供商时一并写入模型配置

- fix(ui): 缩短导出会话说明

- fix(chat): 导出对话不再被进行中的任务拦住

- fix(ui): 当前提供商显示左侧勾，长按多选时隐藏

- fix(ui): 当前模型勾选放到列表左侧

- fix(ui): 自定义压缩模型未设上下文窗口时给出提示

- fix(provider): 去掉内置提供商，启动时清掉已有内置项

- fix(ui): 提供商只提示已创建/已保存，模型删除按钮独占一行

- fix(agent): 大窗口不要按 1MB 存档上限提前暂停，并修好检查点回读

- fix(agent): IPC 补上 supportsVision，避免所有模型都被当成纯文本

- fix(ui): 重做上下文已满弹窗的文案、排版和配色

- fix(gui): 屏幕观察改用有界 JPEG，避免无损全屏图撑爆上下文

- fix(settings): 诊断日志默认开启，关闭只停写不清空

- fix(model): 视觉默认发图，不再靠白名单加模型

- fix(model): 把 gpt-6 / astra 算作视觉模型

- fix(model): 恢复按模型 ID 识别视觉，避免未标模态就不发图

- fix(build): 补上会话存储多余括号，并去掉无效 CursorWindow API

- fix(chat): 保存对话时只保护本轮，避免旧历史把新消息卡住

- fix(backup): 恢复所有权按工作区 UID 校验，超大会话测试避开 CursorWindow

- fix(agent): 启动与技能复制容错，并修复附图正文匹配

- fix(build): 还原被误改的 JSONObject 标识符

- fix(build): 修正摘要提示换行字面量与备份 summary 重载

- fix(build): 修复测试构建的 Kotlin 编译错误

- fix(agent): 隔离助手技能会话绑定，并加固压缩与备份

- fix(model): 未知模型仍可按 image 模态识别视觉，DeepSeek 继续排除

- fix(model): 视觉能力按模型 ID 自动判断，开关只做覆盖

- fix(chat): 文本模型默认不发送图片，气泡预览先同步解码

- fix(chat): 发送后的附图预览改为异步加载，失败再读原图路径

- fix(chat,backup): 文本模型去掉历史图片，路径附图也能显示预览

- fix(backup): 识别会话备份清单，导入后正确显示 Linux 工具已就绪

- fix(backup): chroot Linux 环境改用 busybox 打包，导入不再走损坏归档

- fix(backup): 完整 Linux 环境先解到缓存再 root 拷贝，避免 EPIPE

- fix(backup): 补上 TarArchiveEntry 导入

- fix(backup): 完整 Linux 环境改为流式导入，避免整包进缓存

- fix(backup): 修正设置还原与提供商多选编译

- fix(backup): 修复备份与提供商列表的编译错误

- fix(model): 工具回放回传思考内容，避免 DeepSeek 400

- fix(ui): 提供商配置不再设置系统提示词

- fix(skills): 拷贝 Skill 时跳过 Python 缓存，避免启动闪退


### Other

- ci: 使用预装 cmdline-tools 路径调用 sdkmanager

- ci: 使用预装 SDK 接受许可证，绕过失效的 tools 包

- ci: 配置 Android SDK 时不再打印许可证正文



## [v5.2.7] - 2026-09-14

### Fixes

- fix(db): 重建 model_providers，补上余额列默认值



## [v5.2.6] - 2026-09-14

### Features

- feat(assistant): 技能和记忆开关立即生效

- feat(about): Telegram 下增加 QQ 群入口

- feat(about): 中转站展开显示名称，并加入打赏二维码

- feat(ui): 5.2.6 合并 Root 入口，中转站可展开

- feat(ui): 权限入口归位，设置去掉按压涟漪

- feat(agent): 对齐 MiniS 浏览器与技能安装约定

- feat(chat): 非视频模型也能通过封面帧看用户视频


### Fixes

- fix(db,skills): 余额列迁移可重复执行，安装后写入当前助手开关

- fix(skills): 下一轮按 run 快照暴露刚安装的 Skill

- fix(db,skills): 补余额字段迁移，安装后本轮不再列出新技能

- fix(test): 安装用例不再依赖立刻扫到磁盘索引

- fix(test): Wire 会话测试避开损坏的 history FD，迁移失败带上 Room 原文

- fix(test): Wire 用 pipe 传空 history，并放宽安装索引断言

- fix(test): 钉住技能测试条目，并保持 Wire history 文件描述符存活

- fix(test): 技能索引按目录隔离，并还原推理档与 token 口径

- fix(ci): 修 24 个失败单测并升级 Actions

- fix(skills): 管理列表忽略助手发布目录里的拷贝

- fix(chat): 补上代码块标题栏的 if 括号

- fix(chat): 思考过程保留打字机，未显现的块不再提前占位

- fix(ui): 打赏弹窗切换标签时不要在非组合上下文读 LocalView

- fix(about): 对调微信支付宝收款码，并为设置项加上触控震动

- fix(chat): 生成中也可点击消息操作栏的分支按钮

- fix(skills): 关掉的技能不再出现在 Linux 技能目录

- fix(skills): 关掉的技能不再出现在 /var/minis/skills

- fix(ui): Root 与系统增强只保留权限里一项，并改名为权限检查

- fix(ui): 补上 Root 状态描述的扩展导入

- fix(browser): 切换桌面/手机模式时强制按新 UA 重新加载

- fix(browser): 用户接管时停止预览截图，并修好切回手机版

- fix(models): 远端模型全部进入列表，并识别 grok 生视频

- fix(agent): cookie 写入 App 目录，并修正浏览器 UA 与模型多选

- fix(skills): 从仓库安装时只检查所选技能的兼容性

- fix(test): 放宽 cookie env 引号断言，单测失败不阻断测试包

- fix(test): 修复单元测试编译错误


### Other

- ci: 测试包工作流先跑 debug 单元测试



## [v5.2.5] - 2026-09-14

### Features

- feat(chat): 5.2.5 支持发视频、生视频，root 走全局挂载命名空间



## [v5.2.4] - 2026-09-14

### Features

- feat(ui): 根页面需连按两次返回才退出

- feat(chat): 模型绑定到会话，切换后互不影响

- feat(settings): 增加诊断日志开关和导出


### Fixes

- fix(ui): 首页系统返回也要按两次才退出

- fix(settings): 修复诊断日志编译时 Process 与 Settings 重名

- fix(chat): 没设窗口不自动压，压缩后续写关思考并改提示

- fix(chat): 压缩留在当前 run，有工具就等跑完再压

- fix(chat): 追加后压缩仍算同一轮，不占用保留轮数

- fix(chat): 中途压缩时不显示当前轮操作栏

- fix(chat): 自动和手动压缩的保留轮数只能是 1 到 100

- fix(chat): 压缩后从中断处继续，不再把原问题重发一遍

- fix(chat): 追加消息不再主线程调 Runtime，自动压缩也可中途停住再继续

- fix(chat): 手动压缩先停当前回复，压完再继续

- fix(chat): 手动压缩按当前轮判断，排队时先挡住发送

- fix(chat): 思考或输出时手动压缩先停住再压

- fix(chat): 回复和追加时禁止同时手动压缩



## [v5.2.3] - 2026-09-13

### Features

- feat(ui): 设置关于增加 Telegram 频道入口

- feat(update): 启动检查 GitHub Release，设置关于里可手动检查更新

- feat(chat): 图片模型走独立生图路径，结果直接写入气泡


### Fixes

- fix(update): 启动和回到前台都会重新检查更新



## [v5.2.1] - 2026-09-13

### Features

- feat(runtime): 支持多会话并行，前台工具排队不再互相顶掉

- feat(haptics): 触控反馈增加默认/低/中/高幅度调节


### Fixes

- fix(runtime): 屏幕操作按整条消息排队，不再按工具插花

- fix(haptics): 更新后默认档震动失效，改回系统单参数触感

- fix(ui): 触控反馈开关旁显示展开箭头

- fix(ui): 备份导出文件名改为代鱼-时间-备份.JSON



## [v5.2.0] - 2026-09-13

### Fixes

- fix(ui): 备份文件名用代鱼开头，源代码指向 fork 仓库


