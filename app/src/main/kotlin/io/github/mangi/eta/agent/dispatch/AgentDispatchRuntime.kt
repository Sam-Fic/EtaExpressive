package io.github.mangi.eta.agent.dispatch

/**
 * 调度协调器的进程内持有者。
 *
 * [AgentDispatchCoordinator] 由 AgentRuntimeService 装配（端口绑定到 run 注册表），
 * 而工具执行在 AgentRuntimeRunExecutor/AgentLocalTools 一侧拿不到服务实例；
 * 同进程内用这个单例桥接。服务未起时为 null，工具调用按不可用拒绝。
 */
internal object AgentDispatchRuntime {
    @Volatile
    var coordinator: AgentDispatchCoordinator? = null
}
