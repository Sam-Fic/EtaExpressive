# 子代理监督与后台执行设计

## 配置与接替原则
现有任务保留冻结的已解析模型配置；新父run新建coordinator用于新委派，老coordinator只查询/管理。明确受阻后主代理可以用最新配置发起接替，先确认旧实例停止/隔离，关联原taskId。未知媒体结果不重发，同不可用供应商不立即重派。


# 已返回审查要点（主代理摘要，仍需代码核验）
## 360秒与进度
Coordinator默认allowTimeoutContinuation=false会硬终止，Runtime创建true会pauseAtCheckpoint/awaiting_decision；需两条路径都改。Clock压缩累计预算独立，排队不计执行。lastHeartbeat每30秒写日志而非实际进展，当前无独立无进展保护。
diagnosticEvent已有请求/响应/重试/工具/压缩边界；不要原样公开AgentEvent。get只等Future，需有界after_seq/limit/oldest_seq/next_seq/truncated和新事件唤醒。ContextStats真实usage逻辑保留，不能伪造用量。摘要/指导不入Diagnostics，工具参数/结果/私有思维不入事件。
指导在模型安全边界应用，去重且有界；report_task_progress要本地拦截，不扩大子代理权限。旧ContinuationTest改显式暂停，旧Coordinator文本超时断言改软告警+独立兜底。媒体180/600终止、累计压缩预算保留。
## 子代理随父退出的根因
RunExecutor.execute局部children，controller.register{children.close()}；finally不管成功失败都childBinding.close();children.close();toolsBinding.close();toolExecutor.close()。ResourceBinding.close只是注销。孩子共享父AgentLocalTools，不能只删children.close却还关闭其工具依赖。
Session.complete/cancel -> sealTerminal -> controller.cancel，故孩子还需移出父controller所有权。AgentLoop pause/steer只interruptCurrentRequest，本来不同于整体停止。
RuntimeService.startRun worker finally释放父FGS租约；孩子必须独立任务组租约，组终结才释放，stop回调直接关组而非已终态父session。保留ExecutionService stop及onDestroy drainOwner，不改成detachOwner。RuntimeService.onDestroy还必须关闭新registry。
session.cancel只接受RUNNING，父TERMINAL后不能作为唯一取消入口。租约身份需独立世代token，避免同runId旧worker release新租约。
父sealTerminal清订阅/回放并被Service移除，必须会话级结果查询/下一run接管，隔离不同会话，不自动重委派旧任务。后台子任务活着时UI仍需可见并可停止全部，queued也取消。FGS获取失败不得静默保活。进程杀死不承诺继续执行。
## 用户明确要求
点击停止入口用Material AlertDialog，区分仅停止主回复（子代理继续，提示费用）/停止整个任务（包含所有子代理），关闭取消不能有停止副作用。绑定打开弹窗时run和会话，防误停新任务。不要改工具流程卡片外观。
## 验收重点
完整接管/释放工具依赖；父正常结束和网络失败不杀孩子；Service/FGS销毁及停止全部可靠关组；有进展越360不暂停；无进展保护；事件/指令有限容量与隐私；两个UI选项实际行为不同。

## 主代理额外核验清单
- 即使移除children.close，还需检查共享AgentLocalTools/媒体client的runController和取消回调是否仍绑定父controller；父Session.sealTerminal仍cancel父controller，不能让保活孩子的工具依赖被它关闭。
- 生命周期registry必须确保创建后FGS获取成功再启动子任务；父结束后后台入口不依赖isStreaming；新父接管须看到旧taskId不重派。
- 用户取消弹窗须零停止副作用；明确停止全部之后不得因下一父run取组而复活同一批孩子，需owner世代区分。


# UI只读审查结果与最新需求
用户明确点击停止整个任务要Material风格弹窗。本次实现任务已包含此授权。
现有输入器sendMode stop -> onStop -> Screen StopRun -> Root.pauseCurrentRun()，实际上是暂停，而不是取消全部；保留旧暂停/续行能力，不要误把pause当作整体cancel。continue长按isPaused时走onAbortPausedRun -> abandonPausedRun，不能无意识破坏原交互。
Home/Chat两路都要接。当前TopBarOverflowMenu只Home显示，不能仅在那加后台取消入口。若InputBar则Scaffold/BottomBar中间链也要接。
childContexts用于上下文计量，不可当精确后台运行数量。当前collaborationTaskRunning仅isStreaming||isPaused||isCompressingContext，父结束后后台孩子可能不可见。新增明确task-group状态/背景数量以及停止全部入口，不依赖父isStreaming。
建议Material AlertDialog提供保留原暂停能力的清晰命名，并区分仅停止主回复（保留子任务）/停止整个任务；取消/dismiss无动作。捕获runId/conversationId防切换任务后误停。背景费用提示。
AgentPromptBuilder.DELEGATION_RULE无旧六分钟文案，但可补新生命周期与接管说明。SubAgentTools delegate_task/continue_task描述有360 slice/awaiting_decision须同步。continue_task可保留给显式/无进展暂停，不再描述每6分钟续跑。
Manifest核验：RuntimeService与ExecutionService无独立process属性，voice相关service才单独process；可做同进程任务组registry，但不能依赖Activity。


# 网络/工具兜底只读审查回读
AgentHttpClient: connect20s/read600s/write120s，未设置callTimeout；仅此不能证明SSE有效保护，read timeout不是请求总时限。Provider用AgentSseClient.collect，已另派一小审查读SSE本身。
AgentModelRetry至多3次，退避2/4/8秒，已投递正文/工具/Completed后不自动重试；保留这些边界，不增加媒体重发。
AgentLocalTools实际没有runControl字段，只有closed与beforeToolExecution注入钩子。前述父controller风险是需核验的条件，不是既定事实：看创建时beforeToolExecution是否捕获父controller；明确close会使孩子共享实例失效。不要为不存在的字段盲改。
最低要求：持续SSE心跳不能无限延长“有效进展”；watchdog要保留独立无进展/资源边界。取消传播到HTTP与工具真实资源。

## SSE补验已完成
AgentSseClient.collect沿用modelClient的600秒readTimeout，无额外idle/absolute deadline，done.await()没有超时。持续心跳可无限避免read timeout。listener仅取消/steer/暂停检查；取消binding是interruptible=true；finally关binding并cancel源。StreamArrivalStats只有统计。故子代理新watchdog一定要监测业务进展并能interrupt当前请求，不能只pauseAtCheckpoint却永远等不到checkpoint，也不能把30秒心跳作为续命。


# 最新用户追问补充验收
- 仅停止主回复后改子代理模型：已有running/queued/paused任务冻结派发时配置；续跑仍旧模型。新父run/new delegate使用新配置，同会话能查/指导/取消旧配置任务。显示实际任务模型而非设置页当前模型；不自动迁移/重派。
- 旧供应商不可用或密钥撤销：明确旧任务受阻/失败；不默默切新模型继续。
- 子自身断网：只沿用有限、有副作用保护的既有重试，耗尽明确原因与已完成成果。可靠检查点存在才可恢复；不能恢复明确需重新派发。未知结果的媒体/副作用请求不得自动重发。主离线不无限空转。用户取消不自动复活。
- UI Material AlertDialog：仅停止主回复/停止整个任务/取消，说明保留子代理可能计费，dismiss不执行停止，绑定捕获的run/会话世代。
- 工具层跨轮现状证据：用户中断后旧get_task_result(task_id)返回INVALID_TASK_ARGUMENTS，本轮list为空；原候选7文件仍在磁盘，上一续接worktree无变更。实现不得依赖父run局部可查询性来找回后台任务。



## 额外审查事实
模型冻结目前在父run初始化，不是每delegate。execute读取profiles/filter enabled、selection.resolve、applyReasoning/imageResolution构造childModels；仅缓存providerId/modelId不够，应保留resolved config。新父run必须新coordinator，旧任务按taskId路由回旧owner。SubAgentPreferences.saveModel是copy不会就地改旧profile。manualCompactor闭包仍读当前压缩模式，currentPermissions和全局parallel pool允许动态收紧，不要因模型冻结绕过权限。AgentRuntimeRequestConfigResolver只改voice request主模型。钥匙/headers不得出现在日志/工具结果。
原七文件仍不完整：未实现registry/前台租约/UI；指导队列需界限，awaitPending需核对awaiting_decision语义，旧timeout测试也要改。不要将候选视作已验证。
