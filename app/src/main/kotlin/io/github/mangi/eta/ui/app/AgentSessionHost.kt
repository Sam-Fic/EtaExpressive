package io.github.mangi.eta.ui.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Retains client-side run ownership across Activity destruction, not process death.
 * Runtime/Execution Service cancellation and foreground leases remain unchanged.
 */
internal class AgentSessionHost private constructor(application: Application) {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val state = AgentAppState(context = application.applicationContext, scope = scope)
    val remoteCoordinator =
        io.github.mangi.eta.ui.remote.RemoteCoordinator(application.applicationContext, scope)
            .also { it.start() }

    companion object {
        @Volatile private var instance: AgentSessionHost? = null

        fun get(application: Application): AgentSessionHost = instance ?: synchronized(this) {
            instance ?: AgentSessionHost(application).also { instance = it }
        }

        internal fun resetForTests() = synchronized(this) {
            instance?.scope?.cancel()
            instance = null
        }
    }
}
